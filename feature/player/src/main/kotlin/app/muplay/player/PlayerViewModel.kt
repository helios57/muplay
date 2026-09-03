package app.muplay.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The playback operations [PlayerViewModel] needs, abstracted for the same reason
 * `:feature:library`'s `LibrarySource` is: [PlaybackConnection] is a concrete,
 * `@Inject`-constructed class that binds a `MediaController` to the main `Looper`, so it cannot be
 * subclassed into a hand-written fake, and constructing the real one needs a device and a running
 * media session. This project bans mock frameworks (`ConventionTest`), so this interface is the
 * only way [PlayerViewModel]'s own decisions can be proved anywhere but on an emulator. Real usage
 * is bound to [PlaybackConnection] by the `@Inject` secondary constructor below.
 *
 * **Primitives, not intentions.** [play], [pause] and [isPlaying] are three members rather than one
 * `playPause()`, and [seekTo] takes the target rather than the view model handing over a "commit
 * the scrub" instruction. A coarser seam would move the decisions this view model exists to make
 * down into the adapter, where no test can reach them — the "verified at a different layer from
 * where it is applied" defect this project records by name.
 */
interface PlaybackControls {

  /** The live playback snapshot, as `PlaybackConnection` publishes it. */
  val state: StateFlow<PlaybackState>

  /** Connects to the session if it is not connected yet. Idempotent. */
  suspend fun connect()

  /**
   * What the **player** is doing right now, which is not always what [state] last published:
   * `PlaybackConnection` samples on a 250 ms ticker, and a second tap arriving inside that window
   * against a stale snapshot toggles the wrong way.
   */
  suspend fun isPlaying(): Boolean

  suspend fun play()

  suspend fun pause()

  suspend fun next()

  suspend fun previous()

  suspend fun seekTo(positionMs: Long)

  /**
   * Re-prepares the player after a failure, and starts it again.
   *
   * A member of its own rather than something [play] does when it notices an error, because a
   * player Media3 has moved to `STATE_IDLE` **ignores `play()` entirely** -- it sets
   * `playWhenReady` and returns, and nothing happens, forever. That silence is the second half of
   * the defect [app.muplay.media.PlaybackFailure] describes: the error was invisible, and the one
   * control a user would reach for did nothing.
   */
  suspend fun retry()
}

/**
 * Drives both the full player screen and the mini player, from one shared [PlaybackConnection].
 *
 * One view model for both surfaces on purpose: two would mean two subscriptions to the same
 * controller and two chances for them to disagree about what is playing, which a user sees as a
 * mini player showing one track while the screen behind it shows another.
 *
 * Every rule about *what the screen shows* lives in [playerUiState], which is pure and unit-tested
 * on the fast tier; this class combines flows, holds the scrub position, and runs the transport
 * actions.
 */
@HiltViewModel
class PlayerViewModel(private val controls: PlaybackControls) : ViewModel() {

  @Inject
  constructor(connection: PlaybackConnection) : this(
    object : PlaybackControls {
      override val state: StateFlow<PlaybackState> = connection.state

      override suspend fun connect() {
        connection.controller()
      }

      override suspend fun isPlaying(): Boolean = connection.controller().isPlaying

      override suspend fun play() = connection.controller().play()

      override suspend fun pause() = connection.controller().pause()

      override suspend fun next() = connection.controller().seekToNextMediaItem()

      override suspend fun previous() = connection.controller().seekToPreviousMediaItem()

      override suspend fun seekTo(positionMs: Long) = connection.controller().seekTo(positionMs)

      // `prepare()` then `play()`. `prepare()` alone is enough *if* `playWhenReady` survived the
      // error -- it usually does -- but "usually" is not a contract, and a retry that leaves the
      // player prepared and silent is the same complaint the user just made.
      override suspend fun retry() {
        val controller = connection.controller()
        controller.prepare()
        controller.play()
      }
    },
  )

  /** Non-null only while a finger is on the seek bar. See [PlayerUiState.Content]. */
  private val scrubPositionMs = MutableStateFlow<Long?>(null)

  val uiState: StateFlow<PlayerUiState> =
    combine(controls.state, scrubPositionMs, ::playerUiState)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = PlayerUiState.NothingPlaying,
      )

  init {
    // Connecting is what starts the state flowing at all; without it the screen renders
    // NothingPlaying forever while audio is audibly playing.
    viewModelScope.launch { controls.connect() }
  }

  /**
   * The transport button, and **after a failure it retries rather than doing nothing**.
   *
   * Read from `controls.state` rather than asked of the player, unlike [PlaybackControls.isPlaying]
   * one line below: `playerError` is state Media3 holds until `prepare()` clears it, so the 250 ms
   * snapshot cannot be stale about it in the way `isPlaying` can.
   */
  fun playPause() {
    viewModelScope.launch {
      when {
        controls.state.value.failure != null -> controls.retry()
        controls.isPlaying() -> controls.pause()
        else -> controls.play()
      }
    }
  }

  /** The error message's own action. Same call as a play tap on a failed player; see [playPause]. */
  fun retry() {
    viewModelScope.launch { controls.retry() }
  }

  fun next() {
    viewModelScope.launch { controls.next() }
  }

  fun previous() {
    viewModelScope.launch { controls.previous() }
  }

  /** Called on every drag. Moves the thumb only; the player is not touched until [commitScrub]. */
  fun scrubTo(positionMs: Long) {
    scrubPositionMs.value = positionMs.coerceAtLeast(0L)
  }

  /**
   * Called when the finger lifts.
   *
   * The early return is load-bearing rather than defensive: `Slider`'s `onValueChangeFinished`
   * fires for a plain tap that moved nothing as well as for a drag, and without it that tap would
   * seek to whatever the previous drag left behind.
   */
  fun commitScrub() {
    val target = scrubPositionMs.value ?: return
    viewModelScope.launch {
      controls.seekTo(target)
      scrubPositionMs.value = null
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
