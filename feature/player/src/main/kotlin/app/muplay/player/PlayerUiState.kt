package app.muplay.player

import app.muplay.media.PlaybackState

/**
 * What the player screen renders. A sealed interface, per the constraints, so a `when` over it is
 * exhaustive at every call site.
 *
 * The discriminator between the two states is **`mediaId != null`**, not `isPlaying`: a paused
 * track is still something to render, and a screen that emptied itself on pause would be unusable.
 */
sealed interface PlayerUiState {

  /** Nothing has been queued in this session. The mini player hides; the full screen says so. */
  data object NothingPlaying : PlayerUiState

  /**
   * @property displayPositionMs where the seek bar's thumb goes. **Not always
   *   `playback.positionMs`**: while a finger is on the bar it follows the finger, because
   *   otherwise every position tick drags the thumb back to where playback actually is and the bar
   *   cannot be used at all.
   */
  data class Content(
    val playback: PlaybackState,
    val displayPositionMs: Long,
    val isScrubbing: Boolean,
  ) : PlayerUiState
}

/** Pure mapping — see `PlayerUiStateTest` for why this is a function and not a `ViewModel` method. */
internal fun playerUiState(playback: PlaybackState, scrubPositionMs: Long?): PlayerUiState =
  if (playback.mediaId == null) {
    PlayerUiState.NothingPlaying
  } else {
    PlayerUiState.Content(
      playback = playback,
      displayPositionMs = scrubPositionMs ?: playback.positionMs,
      isScrubbing = scrubPositionMs != null,
    )
  }

/**
 * `m:ss`, or `h:mm:ss` past an hour. A negative or nonsensical input renders as `0:00` rather than
 * as a negative time: `Player.getDuration()` is `C.TIME_UNSET` (a large negative) until the
 * extractor has read the container, and "-9223372036854:775" on a lock screen is a memorable bug.
 */
internal fun formatDuration(millis: Long): String {
  val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
  val seconds = totalSeconds % 60
  val minutes = (totalSeconds / 60) % 60
  val hours = totalSeconds / 3600
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
  else "%d:%02d".format(minutes, seconds)
}
