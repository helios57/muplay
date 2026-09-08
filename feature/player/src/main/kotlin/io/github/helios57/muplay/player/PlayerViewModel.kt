package io.github.helios57.muplay.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.helios57.muplay.media.PlaybackConnection
import io.github.helios57.muplay.media.PlaybackState
import io.github.helios57.muplay.model.SongRating
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
   * the defect [io.github.helios57.muplay.media.PlaybackFailure] describes: the error was invisible, and the one
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
class PlayerViewModel(
  private val controls: PlaybackControls,
  private val ratings: Ratings,
) : ViewModel() {

  @Inject
  constructor(connection: PlaybackConnection, ratings: Ratings.Impl) : this(
    ratings = ratings,
    controls =
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

      // `prepare()` then `play()`, and the first of the two is belt-and-braces rather than the
      // load-bearing half -- measured, because an earlier version of this comment guessed the
      // other way. A `MediaController.play()` does not reach the player directly: it becomes
      // `MediaSessionImpl.handleMediaControllerPlayRequest`, which calls
      // `Util.handlePlayButtonAction`, which prepares an `STATE_IDLE` player *itself* before
      // playing. (Read out of `media3-common-1.11.0`'s bytecode; and `PlaybackJourneyTest`'s retry
      // journey stays green with this line deleted, which is the same fact from the other side.)
      //
      // It stays because it is what this method *means*. `PlaybackControls` is an interface whose
      // other implementations are hand-built, and "restart a player that failed" should not be
      // spelled `play()` and left to depend on a courtesy inside somebody else's session.
      override suspend fun retry() {
        val controller = connection.controller()
        controller.prepare()
        controller.play()
      }
    },
  )

  /** Non-null only while a finger is on the seek bar. See [PlayerUiState.Content]. */
  private val scrubPositionMs = MutableStateFlow<Long?>(null)

  /** Set when a thumb tap does not reach the server; see [PlayerUiState.Content.ratingFailed]. */
  private val ratingFailed = MutableStateFlow(false)

  /**
   * The thumb on whatever is playing, re-subscribed when the track changes.
   *
   * `distinctUntilChanged` on the **media id**, not on the whole state: `controls.state` ticks
   * about four times a second with a new position, and without it every tick would tear down the
   * database query and start it again.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val rating: StateFlow<SongRating> =
    controls.state
      .map { it.mediaId }
      .distinctUntilChanged()
      .flatMapLatest { mediaId ->
        if (mediaId == null) flowOf(SongRating.Neutral) else ratings.ratingOf(mediaId)
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SongRating.Neutral,
      )

  val uiState: StateFlow<PlayerUiState> =
    combine(controls.state, scrubPositionMs, rating, ratingFailed, ::playerUiState)
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

  /**
   * A thumb tap on whatever is playing.
   *
   * The media id is read at the moment the tap is handled and passed down, so a track change
   * mid-request cannot redirect the rating onto the song that followed. A failure sets
   * [PlayerUiState.Content.ratingFailed] rather than throwing: the write is the listener's, not the
   * app's, and a crash is not a reasonable answer to a server that said no.
   */
  fun thumb(tapped: SongRating) {
    val mediaId = (uiState.value as? PlayerUiState.Content)?.playback?.mediaId ?: return
    viewModelScope.launch {
      val outcome = runCatching { ratings.rate(mediaId, tapped) }
      ratingFailed.value = outcome.isFailure
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
