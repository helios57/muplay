package app.muplay.watchlink

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * **The one file in this plan that no gate covers, in either tier.**
 *
 * It needs a physically paired phone and watch -- a companion app, a Bluetooth bond and two Google
 * Play services installs -- which no CI runner has and no emulator pair can fake. It is therefore
 * kept as small as the job allows and contains **no decision**: it puts bytes on a path and reads
 * bytes off it. Everything that decides anything is in [WatchSyncEngine], [ProgressMerge] and
 * [WatchSyncPayload], all of which are gated on the JVM tier.
 *
 * It is named in `coverageFloors`' `excludes` for this module rather than given a floor of its own,
 * and `warnUngatedClasses` reports it by name on every run. That noise is the point: a floor that
 * cannot be measured reports the absence of a problem it never looked for, which is the exact defect
 * class this project's gates exist to prevent. Do not "fix" the warning by writing one.
 *
 * `DataClient` rather than `MessageClient`, and the reason is the case that actually happens: a data
 * item is **persisted and replicated when the peer reconnects**, so a watch that was in a drawer
 * receives the phone's state the moment it comes back. A message requires both ends online at the
 * same instant, which for a watch is the exception rather than the rule.
 *
 * `setUrgent()` because the default is best-effort and can be delayed by tens of minutes; a position
 * that arrives after the next listening session has started is worse than useless.
 *
 * ### One future-awaiting idiom, hand-rolled -- the same choice `WearBrowser` made
 *
 * The plan offered `kotlinx-coroutines-play-services` for `Task.await()`. This file hand-rolls it
 * instead, exactly as `:wear`'s `WearBrowser` hand-rolled the `ListenableFuture` bridge in Plan 5
 * Task 8: it is six lines, it adds no dependency to a module whose whole justification is that it
 * contains **one** unavoidable one, and it keeps a single idiom in the module.
 *
 * `suspendCancellableCoroutine` and not `suspendCoroutine`, which is where this differs from
 * `WearBrowser`: `PlaybackConnection` records why -- the latter resumes a *cancelled* continuation
 * and throws, and unlike a `MediaBrowser` connection this call is made from a scope
 * ([WatchSyncEngine.publishLocalState]'s caller) that a service teardown really does cancel.
 */
@Singleton
class DataLayerWatchLink @Inject constructor(
  @param:ApplicationContext private val context: Context,
) : WatchLink {

  private val dataClient: DataClient get() = Wearable.getDataClient(context)

  override suspend fun publish(payload: WatchSyncPayload) {
    // Chained rather than `apply { data = ... }`: `PutDataRequest.setData` returns the request, so
    // Kotlin synthesises no `data` property for it and the plan's listing would not compile.
    val request = PutDataRequest.create(PATH_SYNC)
      .setData(WatchSyncPayload.encode(payload))
      .setUrgent()
    dataClient.putDataItem(request).await()
  }

  override fun incoming(): Flow<WatchSyncPayload> = callbackFlow {
    val listener = DataClient.OnDataChangedListener { events ->
      events.forEach { event ->
        if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_SYNC) {
          WatchSyncPayload.decode(event.dataItem.data ?: ByteArray(0))?.let(::trySend)
        }
      }
      events.release()
    }
    dataClient.addListener(listener)
    awaitClose { dataClient.removeListener(listener) }
  }

  private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { failure -> continuation.resumeWithException(failure) }
    addOnCanceledListener { continuation.cancel() }
  }

  companion object {
    /**
     * One path, both directions.
     *
     * Each device writes its own item at this path in its **own** node's namespace, so the two do
     * not overwrite each other -- the Data Layer keys an item by (node, path), which is why one
     * constant is enough and a per-device suffix would only make the path harder to read.
     */
    const val PATH_SYNC: String = "/muplay/sync"
  }
}
