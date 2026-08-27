package app.muplay.media

import android.os.Handler
import android.os.Looper
import app.muplay.model.Song
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The one way anything in this app starts playing something.
 *
 * A single entry point rather than three ViewModels each assembling a queue: the format decision,
 * the URL construction and the controller handshake all have to happen in the right order, and a
 * second copy of that sequence is a second place for it to drift.
 *
 * ### Threading
 *
 * A `MediaController` must be built and touched on the thread whose `Looper` it was created with,
 * and every caller here is a ViewModel coroutine that may be on anything. So the three controller
 * calls below run on the main `Looper` explicitly.
 *
 * **`Dispatchers.Main` is deliberately not what supplies it.** That property's Android
 * implementation lives in `kotlinx-coroutines-android`, which this module does not declare — it is
 * on the runtime classpath transitively today (via Hilt and AndroidX), and an undeclared-but-used
 * transitive dependency is exactly what this project's dependency audit bans. A `Handler`-backed
 * `Executor` is two lines, is `kotlinx-coroutines-core` only, and names the thread it means. This
 * is the same construction, for the same stated reason, as [PlaybackConnection]'s own
 * `mainDispatcher`, and it resolves to the same `Looper` — so a controller obtained there is safe
 * to touch here.
 */
@Singleton
class PlaybackLauncher @Inject constructor(
  private val queueRepository: QueueRepository,
  private val playbackConnection: PlaybackConnection,
  /**
   * The `transcodeOffset` gate (Plan 3 Task 12), negotiated here because this is the earliest point
   * at which it *can* be: a capability query needs credentials, and credentials are what a stream
   * URL is built from one line down. Defaulted to the inert [TranscodeSeekSupport.None] so the two
   * hand-constructions in `:app`'s instrumented suite -- neither of which plays a transcode -- keep
   * compiling; Hilt supplies the real one.
   */
  private val transcodeSeek: TranscodeSeekSupport = TranscodeSeekSupport.None,
) {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { command -> mainHandler.post(command) }
  private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

  suspend fun play(songs: List<Song>, startIndex: Int) {
    val queue = launchQueue(songs, startIndex) ?: return
    // Concurrently with building the queue, not before it: the negotiation is one round trip and
    // happens once per session, and serialising it would put that round trip between a user's tap
    // and the first audio. `mediaItems` is mostly local (credentials, one library read, URL
    // building), so the pair costs about what the slower of the two costs.
    val items = coroutineScope {
      val negotiating = async { transcodeSeek.refreshIfUnknown() }
      val built = queueRepository.mediaItems(queue)
      negotiating.await()
      built
    }
    withContext(mainDispatcher) {
      val controller = playbackConnection.controller()
      // `queue.startIndex` is honoured; the position argument is not, and cannot be -- MuPlayer's
      // seam discards it and asks the ResumePolicy instead. Passing 0 here documents the intent;
      // the guarantee is structural (see `MuPlayerFactory`).
      controller.setMediaItems(items, queue.startIndex, 0L)
      controller.prepare()
      controller.play()
    }
  }
}

/**
 * The queue [PlaybackLauncher.play] will hand the controller, or `null` when there is nothing to
 * play.
 *
 * A top-level function rather than a private method, and gated by `PlaybackLauncherTest` on the
 * JVM tier, for the reason that test's own header gives: everything else in `play` needs a bound
 * media session and therefore a device, but *which item the caller asked to start from* is the one
 * value here a user notices immediately when it is wrong.
 *
 * Two rules, and both are about not failing a user for a race they did not cause:
 *
 *  * an empty list produces `null` rather than [PlaybackQueue]'s own `IllegalArgumentException` —
 *    "play this album" against songs the mirror has not delivered yet is ordinary, not a
 *    programming error;
 *  * an out-of-range index is **clamped** rather than rejected, because a shuffle list can shrink
 *    between the tap and the launch, and starting from the nearest real song is a better answer
 *    than losing playback to an exception.
 */
internal fun launchQueue(songs: List<Song>, startIndex: Int): PlaybackQueue? =
  if (songs.isEmpty()) null else PlaybackQueue.of(songs, startIndex.coerceIn(songs.indices))
