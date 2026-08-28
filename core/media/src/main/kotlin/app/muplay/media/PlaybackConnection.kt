package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * site is `runBlocking` from an instrumented test thread, where a continuation resumed on the
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

  /**
   * The **in-flight** connection, not the resolved controller, and that distinction is the whole of
   * this field's reason to exist.
   *
   * [connect] suspends, which releases the main thread. Caching the resolved `MediaController` -- as
   * this class did -- therefore leaves a window in which a second caller sees no controller yet and
   * starts a *second* `MediaController.Builder(..).buildAsync()`. Both complete, the field keeps
   * whichever landed last, and the other one stays bound forever: the service cannot stop because
   * something is still connected to it, its `Player.Listener` keeps publishing into the same
   * [_state], and [release] cannot reach it because [release] can only release one.
   *
   * That is not a theoretical interleaving. This is a `@Singleton`; a mini-player and a now-playing
   * screen starting together is the ordinary case, and both call [controller].
   *
   * Cleared when the attempt fails **or is cancelled**, so a connection failure is retried by the
   * next caller rather than cached and re-thrown forever -- but only if it is still the current
   * attempt, or a `release()`-then-`controller()` pair would have the old attempt's completion
   * discard the new one.
   */
  private var connection: Deferred<MediaController>? = null

  private val listener = object : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = publish(player)
  }

  /**
   * Connects to [MuPlaybackService] if necessary and returns the controller.
   *
   * Callable from any thread; the returned controller is bound to the main thread and every use of
   * it belongs there.
   *
   * **One connection, however many concurrent callers.** What is shared is the in-flight
   * [connection], not the resolved controller -- see that field for the leak the second spelling
   * produces. Every caller entering this function sees either a connection already under way or
   * starts the only one there will be, because the whole `?:` runs on the main thread with no
   * suspension point inside it: `async` returns its `Deferred` before its body is dispatched, so the
   * assignment cannot be skipped over by a second caller.
   *
   * Throws `CancellationException` if [release] is called while this call is still connecting. That
   * is the honest answer -- the connection the caller asked for was torn down -- and it is what stops
   * a controller arriving after a release from re-populating the field and restarting the ticker on
   * a connection that no longer exists.
   */
  suspend fun controller(): MediaController = withContext(mainDispatcher) {
    (
      connection ?: scope.async { connect() }.also { attempt ->
        connection = attempt
        // A failed or cancelled attempt must not be the cached answer for the rest of the process's
        // life. The identity check is what keeps that from undoing a *newer* attempt: cancellation
        // resumes through the main dispatcher, so an attempt cancelled by `release()` can complete
        // one main-loop task after a caller has already started its replacement.
        attempt.invokeOnCompletion { cause ->
          if (cause != null && connection === attempt) connection = null
        }
      }
      ).await()
  }

  /**
   * Releases the controller and resets [state].
   *
   * Order matters: the ticker is cancelled *before* the controller is released, or its next tick
   * reads a released controller. Resetting to [PlaybackState.NOTHING_PLAYING] rather than leaving
   * the last frame behind means a UI that outlives the connection renders "nothing is playing"
   * instead of a track that is no longer loaded, with a progress bar frozen part-way through it.
   *
   * `cancelChildren()` covers an in-flight [connect] as well as the ticker, and both halves matter.
   * Without it, a `controller()` suspended inside `connect()` when this runs resumes afterwards,
   * assigns [controller], and starts a fresh ticker -- on a connection that was released, against a
   * `MediaController` that [release] had already handed to `MediaController.releaseFuture`. The
   * result is a permanent 4 Hz timer reading a dead controller, and it survives every subsequent
   * `release()` because the field it would have to be reached through was cleared first.
   */
  fun release() {
    scope.coroutineContext.cancelChildren()
    connection = null
    controller?.removeListener(listener)
    controller?.release()
    controller = null
    // Releases the controller whenever it arrives, including one still being built by an attempt
    // the line above just cancelled.
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    _state.value = PlaybackState.NOTHING_PLAYING
  }

  /**
   * Builds one controller and installs it, or throws.
   *
   * Everything after the suspension point -- the field, the listener, the first publish and the
   * ticker -- is inside this function rather than in [controller]'s `also` block on purpose: it makes
   * "a released connection installs nothing" a property of *cancellation* rather than of a flag,
   * because a cancelled coroutine never reaches the line after the suspension point.
   */
  private suspend fun connect(): MediaController {
    val future = MediaController.Builder(context, MuPlaybackService.sessionToken(context))
      .buildAsync()
    controllerFuture = future
    val connected = suspendCancellableCoroutine { continuation ->
      future.addListener(
        {
          // Runs on the main thread, which is where `future.get()` hands back a controller already
          // bound to it. `resumeWith(runCatching { .. })` rather than a bare `get()`: a connection
          // failure has to arrive at the caller as its own exception, not as a coroutine that never
          // resumes.
          //
          // `suspendCancellableCoroutine`, not `suspendCoroutine`: the latter resumes a *cancelled*
          // coroutine's continuation normally (`DispatchedContinuation` resumes atomically), so the
          // installation below would run after `release()` had already torn everything down.
          continuation.resumeWith(runCatching { future.get() })
        },
        mainExecutor,
      )
    }
    controller = connected
    connected.addListener(listener)
    publish(connected)
    startTicker(connected)
    return connected
  }

  /**
   * Samples [player] until this connection's scope is cancelled.
   *
   * Takes the controller as an argument rather than re-reading the field, and loops on `while
   * (true)` rather than on `isActive`, for the same reason in both cases: each of the alternatives
   * adds a branch that can never take its other arm. The field cannot be null while the ticker
   * runs -- [release] cancels this coroutine *before* it clears the field -- and the loop never
   * exits by its own condition, because `delay` is the cancellation point and throws out of the
   * body rather than returning to the top. A null check that is never null and a condition that is
   * never false are not safety; they are two uncoverable branches and a reader's false impression
   * that either case was thought about.
   */
  private fun startTicker(player: Player) {
    scope.launch {
      while (true) {
        publish(player)
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
      // `MediaMetadata.mediaType` is a nullable `Integer` -- genuinely null for an item built
      // without metadata -- and `MEDIA_TYPE_MIXED` is the honest answer for one, the same value
      // [PlaybackState.NOTHING_PLAYING] carries. It is not "music": claiming a type nothing
      // declared is how a book ends up rendered with music controls.
      mediaType = metadata.mediaType ?: MediaMetadata.MEDIA_TYPE_MIXED,
      // Read here rather than derived, because it is player state a car or a watch can change
      // without this process seeing the tap. The listener below refreshes on **every** `onEvents`,
      // so `EVENT_PLAYBACK_PARAMETERS_CHANGED` is already one of the events that republishes -- the
      // readout does not freeze at whatever the speed was when the item changed. Checked rather
      // than assumed: there is no explicit event list in this class to add it to.
      speed = player.playbackParameters.speed,
    )
  }

  companion object {
    /** ~4 Hz. Smooth enough for a seek bar, cheap enough to run while the screen is on. */
    const val POSITION_TICK_MS = 250L
  }
}
