package app.muplay.wear

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import app.muplay.media.MuPlaybackService
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.suspendCoroutine

/**
 * The watch's single connection to the browse tree.
 *
 * **The connection hint is the whole point of this class.** This app's package is `app.muplay` --
 * the same application id as the phone -- so nothing about the *identity* of this controller could
 * ever distinguish it from the phone's own. [BrowseSurfaces.HINT_WATCH] is the self-declaration
 * that gets it the watch tree (three root tabs rather than five, a short Continue shelf, list
 * layout), and `BrowseSurfaces.of` honours it only because it arrives from our own package. Delete
 * the `putString` below and everything still connects, browses and plays -- it just gets the
 * phone's tree. `WearSessionJourneyTest.theWatchGetsTheWatchTreeAndNotThePhoneOne` is the only test
 * in this build that can see that line.
 *
 * ### Threading, and why there is exactly one future-awaiting idiom here
 *
 * A `MediaBrowser` binds to a `Looper` and throws *"Player is accessed on the wrong thread"* from
 * every access off it, so every call into one below is posted to the main thread first. The
 * `Handler`-backed `Executor` is the same shape `PlaybackConnection` uses and for the same reason:
 * `Dispatchers.Main` lives in `kotlinx-coroutines-android`, which this module does not declare.
 *
 * The plan offered `kotlinx.coroutines.guava.await` as an alternative to hand-rolling the
 * `ListenableFuture` bridge. **This file chose the hand-rolled one**: it is six lines, it adds no
 * dependency, and -- the reason that matters -- it keeps *one* idiom in the module. Two ways of
 * awaiting a future in one class is how a later reader ends up unsure which one is load-bearing.
 *
 * ### Why the in-flight future is cached rather than the resolved browser
 *
 * Straight from `PlaybackConnection`'s own recorded defect. [browser] suspends, which releases the
 * main thread; caching the *resolved* `MediaBrowser` leaves a window in which a second caller sees
 * none yet and starts a second connection. Both complete, the field keeps whichever landed last,
 * and the other stays bound forever -- so the service cannot stop and [release] cannot reach it.
 * Every read and write of [pending] here happens on the main thread, so there is no window at all.
 */
@Singleton
class WearBrowser @Inject constructor(@ApplicationContext private val context: Context) {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { command -> mainHandler.post(command) }

  /** The **in-flight** connection, not the resolved browser. Touched only on the main thread. */
  private var pending: ListenableFuture<MediaBrowser>? = null

  /** The connected browser, connecting first if nothing has yet. Safe to call from any thread. */
  suspend fun browser(): MediaBrowser {
    val future = onMain { pending ?: connectAsync().also { pending = it } }
    return try {
      future.await()
    } catch (failure: Throwable) {
      // Cleared so the next caller retries, and only if this is still the current attempt -- or a
      // `release()`-then-`browser()` pair would have this failure discard the new connection.
      onMain { if (pending === future) pending = null }
      throw failure
    }
  }

  /**
   * The children of [parentId], or an empty list if the session refuses the id.
   *
   * Empty rather than an exception: an unknown id comes back from Media3 as a `LibraryResult` with
   * an error code and no value, and a browse surface's honest render of that is "nothing here".
   */
  suspend fun children(parentId: String): List<MediaItem> {
    val browser = browser()
    val future = onMain { browser.getChildren(parentId, 0, Int.MAX_VALUE, null) }
    return future.await().value.orEmpty()
  }

  /**
   * Releases the connection, if any.
   *
   * Not `suspend`, so it can be called from `onDestroy`/`@After`, and the actual release is posted
   * to the main thread because that is the only thread a `MediaBrowser` may be touched from.
   */
  fun release() {
    mainHandler.post {
      val future = pending ?: return@post
      pending = null
      // No `if (isDone)` branch, deliberately, and it is not a shortcut. `cancel` on an already
      // completed future is a documented no-op returning `false`, and Media3 releases the
      // controller when a build future is cancelled -- so this one pair of lines covers both the
      // in-flight and the resolved case. On a cancelled future `get()` throws, which is what
      // `runCatching` is here for; on a resolved one it returns the browser and releases it.
      //
      // The branch that would tell them apart is one no test can drive on purpose (it needs a
      // release timed inside a connection), and an untestable branch in a five-line function is a
      // permanently uncovered line pretending to be a decision.
      future.cancel(true)
      runCatching { future.get().release() }
    }
  }

  private fun connectAsync(): ListenableFuture<MediaBrowser> {
    // `MuPlaybackService.sessionToken`, not a hand-built `SessionToken(context, ComponentName(..))`:
    // the service owns the name of its own component, and the phone app's browsers already reach it
    // through that helper. A second construction of the same token here is a second place a rename
    // has to be remembered.
    val hints = Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_WATCH) }
    return MediaBrowser.Builder(context, MuPlaybackService.sessionToken(context))
      .setConnectionHints(hints)
      .buildAsync()
  }

  /** Runs [block] on the main thread and returns its result to the calling coroutine. */
  private suspend fun <T> onMain(block: () -> T): T = suspendCoroutine { continuation ->
    mainHandler.post { continuation.resumeWith(runCatching(block)) }
  }

  /** The one future-awaiting idiom in this module. See this class's header. */
  private suspend fun <T> ListenableFuture<T>.await(): T = suspendCoroutine { continuation ->
    addListener({ continuation.resumeWith(runCatching { get() }) }, mainExecutor)
  }
}
