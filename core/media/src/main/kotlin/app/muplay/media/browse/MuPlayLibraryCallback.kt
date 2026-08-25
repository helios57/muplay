package app.muplay.media.browse

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.muplay.database.BrowseTreeRepository
import app.muplay.media.ControllerAccessPolicy
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowsePaging
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The browse half of the media session -- what Android Auto, Wear OS and the Assistant read.
 *
 * Plan 3 Task 5 left `MediaLibrarySession.Callback`'s browse methods at their *"not supported"*
 * defaults rather than faking an empty root, and said why: "not supported" was true, an empty root
 * would have been a claim. This class is that deferral being paid off, and it is the **only** change
 * to `MuPlaybackService` in Tasks 1-7.
 *
 * **It carries the connection gate too, and that is not duplication.** This class replaces the
 * `LibraryCallback` Plan 3 Task 5 installed, and that class's whole body was [onConnect]. The
 * *rule* stays where it was -- in [ControllerAccessPolicy], reachable from the fast tier with no
 * Media3 type in its signature -- and what moved here is the two-line adapter that reads two
 * properties off a `ControllerInfo`. Losing it would re-open an exported session to every app on
 * the device; `ControllerAccessGateTest` drives this class's own [onConnect], so the gate is now
 * verified on exactly the object production installs rather than on a sibling of it.
 *
 * **Media3 makes the gate sequential with browsing.** `onGetLibraryRoot`, `onGetChildren` and
 * `onGetItem` are only ever called for a controller [onConnect] admitted, so the surface resolver's
 * input set is exactly what that gate passed. It follows that nothing below re-checks it.
 *
 * **The surface is resolved per request, never cached.** One session serves many controllers at
 * once -- the phone UI, a car head unit and the system's media controls can all be connected
 * simultaneously -- so a field holding "the surface" would give whichever connected second the
 * other one's tree.
 *
 * **`session.player` is read at the moment it is needed and never held.** Plan 6 (casting) swaps
 * the session's player when audio moves to a speaker, and a cached reference would leave the browse
 * tree driving a player nothing is listening to. Nothing in this class reads it *yet* -- Task 5's
 * `onAddMediaItems`/`onSetMediaItems` are the first that will -- and this is the property that has
 * to survive them.
 *
 * **`onSubscribe`/`notifyChildrenChanged` are deliberately not implemented.** A subscribing browser
 * is told when a folder's contents change; ours change when Plan 2's `SyncEngine` reconciles, which
 * is a background event a car cannot see happening. Implementing it means holding a subscription per
 * controller and pushing invalidations from a repository into a session -- a live-update path with
 * its own concurrency whose only observable effect is a list refreshing while parked. The cost is
 * that a car re-entering a folder sees new content and one already looking at it does not.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `MediaLibrarySession` and `LibraryParams` are
// `@UnstableApi`, which the Kotlin compiler cannot see at all -- `check` fails much later, at
// `lintDebug`, with `UnsafeOptInUsageError`. See `MuPlayerFactory` for the full argument.
@OptIn(UnstableApi::class)
@Singleton
class MuPlayLibraryCallback @Inject constructor(
  private val treeRepository: BrowseTreeRepository,
  private val surfaceResolver: SurfaceResolver,
) : MediaLibrarySession.Callback {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Refuses a controller [ControllerAccessPolicy] does not accept, and otherwise leaves the decision
   * exactly where Media3 had it.
   *
   * **`onConnect` and not `onConnectAsync`, and that is a measurement.**
   * `MediaSessionImpl.onConnectOnHandler` calls `Callback.onConnect` *first* and only falls through
   * to `onConnectAsync` when the returned result is accepted **and** carries Media3's
   * `androidx.media3.session.CALLBACK_NOT_IMPLEMENTED` sentinel in its `sessionExtras` -- which is
   * precisely what the interface's own default `onConnect` returns. Overriding the async half alone
   * would leave the sync half answering first.
   *
   * The accepted arm is `super.onConnect(...)`, i.e. that sentinel, rather than a hand-built
   * `AcceptedResultBuilder(session, controller).build()`. The two are the same thing today, and
   * delegating means a legitimate controller keeps whatever Media3's default is -- including the
   * narrowing it already applies to an untrusted-but-accepted caller -- instead of this file
   * freezing a copy of it.
   */
  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult =
    if (ControllerAccessPolicy.accepts(controller.packageName, controller.isTrusted)) {
      super.onConnect(session, controller)
    } else {
      MediaSession.ConnectionResult.reject()
    }

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    // Synchronously, with no repository call at all: `onGetLibraryRoot` is the first thing a car
    // does on connect, and a root that waits on a database read is a car that shows a spinner
    // before it shows an app name.
    return immediate(LibraryResult.ofItem(BrowseItems.root(surface), params))
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(parentId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      val children: List<BrowseNode> = treeRepository.children(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      // Paged *before* the artwork URLs are resolved, so a host asking for ten rows of a thousand
      // pays for ten.
      val items = BrowsePaging.page(children, page, pageSize).map { node ->
        BrowseItems.of(node, treeRepository.artworkUri(node.artworkId))
      }
      LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(mediaId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      if (id == BrowseId.Root) {
        return@future LibraryResult.ofItem(BrowseItems.root(surface), null)
      }
      val node = treeRepository.node(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      LibraryResult.ofItem(BrowseItems.of(node, treeRepository.artworkUri(node.artworkId)), null)
    }
  }

  /** Called from `MuPlaybackService.onDestroy`. */
  fun release() {
    scope.cancel()
  }

  private fun <T> immediate(value: T): ListenableFuture<T> =
    SettableFuture.create<T>().apply { set(value) }

  /**
   * Runs [block] off the session thread and answers Media3 with its result.
   *
   * A failure becomes `ERROR_UNKNOWN` rather than an exception on the future, deliberately: an
   * exception here is logged by Media3 and reaches a driver as an empty screen with no explanation,
   * whereas an error result is rendered as an error. `BrowseId.Track`'s own
   * `IllegalArgumentException` (Task 1) is the one this actually catches.
   *
   * **The throwable is not logged, and that is this module's standing rule rather than an
   * omission.** `ConventionTest`'s *nothing in the media module logs* holds it: every `MediaItem`
   * this class builds is one `toString()` away from an authenticated Subsonic URL, and a browse
   * failure is the moment somebody reaches for a log line.
   */
  // `T : Any`: Media3 declares `LibraryResult.ofError` as `<V> LibraryResult<V>` under
  // `@NonNull` defaults, so Kotlin reads `V` as non-nullable and a plain `<T>` here cannot
  // satisfy it. Both call sites pass a non-null payload type anyway.
  private fun <T : Any> future(
    block: suspend () -> LibraryResult<T>,
  ): ListenableFuture<LibraryResult<T>> {
    val future = SettableFuture.create<LibraryResult<T>>()
    scope.launch {
      // `<T>` spelled out: without it Kotlin infers `LibraryResult<T & Any>` from the platform
      // signature and the two arms of `getOrElse` no longer agree.
      val result: LibraryResult<T> = runCatching { block() }
        .getOrElse { LibraryResult.ofError<T>(SessionError.ERROR_UNKNOWN) }
      future.set(result)
    }
    return future
  }
}
