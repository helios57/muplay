package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one bridge between the playback service and everything that renders it.
 *
 * `:feature:player` gets a [PlaybackState] `StateFlow` and a `MediaController`, and never an
 * `ExoPlayer`. That boundary is the reason `:core:media` exists as a module: a feature that can
 * construct an `ExoPlayer` eventually does, and then there are two players in the process, one of
 * them not the one holding the media session -- which is how a media app ends up with a
 * notification that controls nothing.
 *
 * ### Threading
 *
 * A `MediaController` binds to a `Looper` and throws *"Player is accessed on the wrong thread"* from
 * every access off it. Everything in this class that touches one therefore runs on [mainDispatcher],
 * which is built from the main `Looper` directly rather than from `Dispatchers.Main`: the latter
 * lives in `kotlinx-coroutines-android`, which this module does not declare and which would be an
 * undeclared transitive dependency if it were used. A `Handler`-backed `Executor` is one line, is
 * `kotlinx-coroutines-core` only, and says which thread it means.
 *
 * [controller] is a `suspend fun` and may be called from any thread; it hops to the main thread
 * itself rather than making that the caller's problem. That is not politeness -- the natural call
 * site is `runBlocking` from an instrumented test thread, where a `suspendCoroutine` resumed on the
 * main thread would resume the *coroutine* back on the calling thread and every subsequent player
 * read would throw.
 *
 * ### The ticker
 *
 * The position ticker is a **UI** concern and is separate from the progress writer's own ticker
 * (Task 8). This one samples the live player at a frame-friendly rate for a seek bar; that one
 * persists a row every few seconds. Merging them would tie how often a database is written to how
 * smooth a progress bar looks.
 *
 * It is also not redundant with the `Player.Listener` below. `onEvents` fires when something
 * *changes*; a position that is merely advancing is not an event, so without the ticker a seek bar
 * would move only when the track did.
 *
 * ### One thing a snapshot of a `MediaController` cannot promise
 *
 * `currentMediaItem` and `mediaMetadata` do not update in the same instant. Measured on `muplay37`:
 * immediately after a `seekToNextMediaItem`, a sample taken by the ticker can carry the new item's
 * `mediaId` beside the previous item's `title`. The window is shorter than one tick.
 *
 * It is left rather than papered over, and the alternative is worse than it sounds: reading the
 * title from `currentMediaItem.mediaMetadata` instead would be internally consistent but would drop
 * the *combined* metadata -- the merge of the item's own tags with the stream's -- which is exactly
 * what Media3's notification renders from. The app's own UI and its notification disagreeing
 * permanently is a worse defect than either of them lagging for a frame.
 *
 * ### What never reaches [PlaybackState]
 *
 * The stream URI. It carries an auth token and a fresh salt, the UI has no use for it, and the
 * surest way never to leak one into a log, a crash report or a screenshot is not to hand it to the
 * layer whose job is to display things. [PlaybackState.artworkUri] is the one URL that does cross,
 * because an image loader needs it, and it is subject to the same rule about not being logged.
 */
@Singleton
class PlaybackConnection @Inject constructor(@ApplicationContext private val context: Context) {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val mainExecutor = Executor { command -> mainHandler.post(command) }
  private val mainDispatcher = mainExecutor.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

  private val _state = MutableStateFlow(PlaybackState.NOTHING_PLAYING)
  val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var controller: MediaController? = null

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = publish(player)
  }

  /**
   * Connects to [MuPlaybackService] if necessary and returns the controller.
   *
   * Callable from any thread; the returned controller is bound to the main thread and every use of
   * it belongs there.
   */
  suspend fun controller(): MediaController = withContext(mainDispatcher) {
    controller ?: connect().also { connected ->
      controller = connected
      connected.addListener(listener)
      publish(connected)
      startTicker()
    }
  }

  /**
   * Releases the controller and resets [state].
   *
   * Order matters: the ticker is cancelled *before* the controller is released, or its next tick
   * reads a released controller. Resetting to [PlaybackState.NOTHING_PLAYING] rather than leaving
   * the last frame behind means a UI that outlives the connection renders "nothing is playing"
   * instead of a track that is no longer loaded, with a progress bar frozen part-way through it.
   */
  fun release() {
    scope.coroutineContext.cancelChildren()
    controller?.removeListener(listener)
    controller?.release()
    controller = null
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    _state.value = PlaybackState.NOTHING_PLAYING
  }

  private suspend fun connect(): MediaController {
    val future = MediaController.Builder(context, MuPlaybackService.sessionToken(context))
      .buildAsync()
    controllerFuture = future
    return suspendCoroutine { continuation ->
      future.addListener(
        {
          // Runs on the main thread, which is where `future.get()` hands back a controller already
          // bound to it. `resumeWith(runCatching { .. })` rather than a bare `get()`: a connection
          // failure has to arrive at the caller as its own exception, not as a coroutine that never
          // resumes.
          continuation.resumeWith(runCatching { future.get() })
        },
        mainExecutor,
      )
    }
  }

  private fun startTicker() {
    scope.launch {
      while (isActive) {
        controller?.let(::publish)
        delay(POSITION_TICK_MS)
      }
    }
  }

  private fun publish(player: Player) {
    val metadata = player.mediaMetadata
    _state.value = PlaybackState(
      isPlaying = player.isPlaying,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      mediaId = player.currentMediaItem?.mediaId,
      title = metadata.title?.toString(),
      artist = metadata.artist?.toString(),
      albumTitle = metadata.albumTitle?.toString(),
      artworkUri = metadata.artworkUri?.toString(),
      positionMs = player.currentPosition.coerceAtLeast(0L),
      // `C.TIME_UNSET` until the extractor has read the container -- and for a stream the server
      // transcodes on the fly, forever. Recognising that sentinel is this adapter's job; what to do
      // about it is `PlaybackState.durationMsOf`'s, where the fast tier can gate it.
      durationMs = PlaybackState.durationMsOf(
        playerDurationMs = player.duration.takeIf { it != C.TIME_UNSET },
        metadataDurationMs = metadata.durationMs,
      ),
      hasNext = player.hasNextMediaItem(),
      hasPrevious = player.hasPreviousMediaItem(),
    )
  }

  companion object {
    /** ~4 Hz. Smooth enough for a seek bar, cheap enough to run while the screen is on. */
    const val POSITION_TICK_MS = 250L
  }
}
