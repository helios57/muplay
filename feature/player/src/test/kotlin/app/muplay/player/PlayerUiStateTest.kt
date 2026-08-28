package app.muplay.player

import androidx.media3.common.MediaMetadata
import app.muplay.media.PlaybackState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test over a pure function. [PlaybackState] carries only primitives and strings — the
 * `artworkUri` is a `String`, not a `Uri` — precisely so this mapping can be gated by the fast
 * tier, which is where every field-level assertion in this project belongs when it can be.
 */
class PlayerUiStateTest {

  private val playing = PlaybackState(
    isPlaying = true,
    isBuffering = false,
    mediaId = "song-1",
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = "https://host/art-1",
    positionMs = 2_000L,
    durationMs = 5_000L,
    hasNext = true,
    hasPrevious = false,
    // A song. `mediaType` and `speed` arrived with Plan 4 Task 7's per-book speed;
    // they carry no default, deliberately, so a caller cannot silently omit the
    // field that decides whether a listener gets book controls or music ones.
    mediaType = MediaMetadata.MEDIA_TYPE_MUSIC,
    speed = 1.0f,
  )

  @Test
  fun `nothing playing is its own state`() {
    assertThat(playerUiState(PlaybackState.NOTHING_PLAYING, scrubPositionMs = null))
      .isEqualTo(PlayerUiState.NothingPlaying)
  }

  @Test
  fun `a state with a media id is content`() {
    // The discriminator is the media id, not `isPlaying`: a paused track is still something the
    // player screen must render, and a screen that emptied itself on pause would be unusable.
    val paused = playing.copy(isPlaying = false)

    assertThat(playerUiState(paused, null)).isInstanceOf(PlayerUiState.Content::class.java)
  }

  /**
   * The other half of the discriminator, and the half a `mediaId != null` implementation could get
   * wrong in the direction nothing else here would notice: a state that is *otherwise* entirely
   * empty but has an id is Content, and a state that is full of metadata but has no id is not.
   *
   * Without the second case, `playback.title == null` would pass every other test in this class —
   * `NOTHING_PLAYING` has a null title as well as a null id — while making a track whose server
   * sent no title render as an empty player screen.
   */
  @Test
  fun `the media id alone decides, not the metadata around it`() {
    val idOnly = PlaybackState.NOTHING_PLAYING.copy(mediaId = "song-9")
    val metadataWithoutAnId = playing.copy(mediaId = null)

    assertThat(playerUiState(idOnly, null)).isInstanceOf(PlayerUiState.Content::class.java)
    assertThat(playerUiState(metadataWithoutAnId, null)).isEqualTo(PlayerUiState.NothingPlaying)
  }

  @Test
  fun `the content carries the playback state it was given`() {
    val content = playerUiState(playing, null) as PlayerUiState.Content

    // The whole value, so no individual field can be dropped or replaced on the way through.
    assertThat(content.playback).isEqualTo(playing)
  }

  @Test
  fun `the displayed position is the player's own position when nobody is scrubbing`() {
    // Two observations of a value a constant could satisfy.
    assertThat((playerUiState(playing, null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(2_000L)
    assertThat((playerUiState(playing.copy(positionMs = 4_100L), null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(4_100L)
  }

  /**
   * While a finger is on the seek bar, the thumb must follow the finger and not the player. Without
   * this, every position tick drags the thumb back to where playback actually is and the bar
   * becomes impossible to use — a bug that is obvious on a device and invisible in a screenshot.
   */
  @Test
  fun `the displayed position is the scrub position while scrubbing`() {
    val content = playerUiState(playing, scrubPositionMs = 4_500L) as PlayerUiState.Content

    assertThat(content.displayPositionMs).isEqualTo(4_500L)
    assertThat(content.isScrubbing).isTrue
    // ...and the underlying playback state is untouched, so releasing the finger has something
    // truthful to fall back to.
    assertThat(content.playback.positionMs).isEqualTo(2_000L)
  }

  /**
   * A second observation of the scrub position, at a different value *and* against a different
   * player position, so `displayPositionMs = 4_500L` and `displayPositionMs = scrubPositionMs ?:
   * 4_500L` are both excluded — and so is an implementation that takes the larger of the two.
   */
  @Test
  fun `a scrub behind the player still wins`() {
    val content = playerUiState(playing.copy(positionMs = 4_100L), scrubPositionMs = 300L)
      as PlayerUiState.Content

    assertThat(content.displayPositionMs).isEqualTo(300L)
  }

  /**
   * Zero is a real scrub position — a finger dragged to the very start of the track — and it is the
   * one value an `if (scrubPositionMs > 0)` or a `takeIf { it != 0L }` implementation would treat
   * as "not scrubbing", snapping the thumb back to the player mid-drag.
   */
  @Test
  fun `scrubbing to the very start is scrubbing`() {
    val content = playerUiState(playing, scrubPositionMs = 0L) as PlayerUiState.Content

    assertThat(content.displayPositionMs).isEqualTo(0L)
    assertThat(content.isScrubbing).isTrue
  }

  /**
   * The pair, not each number on its own. An MP3's duration is an estimate read from the
   * container and the player's position runs past it at the end of a track — measured on
   * `muplay37` against the seeded five-second fixture, where the screen rendered `0:05 / 0:04`
   * while both fields were individually correct.
   */
  @Test
  fun `the displayed position never runs past the end of the track`() {
    val overrun = playing.copy(positionMs = 5_010L, durationMs = 4_995L)

    assertThat((playerUiState(overrun, null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(4_995L)
    // A second observation at a different duration, so the clamp cannot be a constant.
    assertThat(
      (playerUiState(playing.copy(positionMs = 9_000L, durationMs = 6_000L), null)
        as PlayerUiState.Content).displayPositionMs,
    ).isEqualTo(6_000L)
  }

  /**
   * A duration of 0 means **not known yet**, not a zero-length track: `PlaybackConnection` maps
   * `C.TIME_UNSET` to 0. Clamping to it would freeze the elapsed label at `0:00` for the whole of
   * a track whose container has not been read — a worse bug than the one the clamp fixes, and one
   * that a clamp written without this case would ship.
   */
  @Test
  fun `an unknown duration does not clamp the position to zero`() {
    val unknown = playing.copy(positionMs = 7_000L, durationMs = 0L)

    assertThat((playerUiState(unknown, null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(7_000L)
  }

  /** A negative position from a stale controller is still floored, with or without a duration. */
  @Test
  fun `a negative position is floored at zero`() {
    assertThat(
      (playerUiState(playing.copy(positionMs = -20L, durationMs = 0L), null)
        as PlayerUiState.Content).displayPositionMs,
    ).isZero
    assertThat(
      (playerUiState(playing.copy(positionMs = -20L, durationMs = 5_000L), null)
        as PlayerUiState.Content).displayPositionMs,
    ).isZero
  }

  @Test
  fun `not scrubbing is reported as not scrubbing`() {
    assertThat((playerUiState(playing, null) as PlayerUiState.Content).isScrubbing).isFalse
  }

  @Test
  fun `a duration formats as minutes and seconds`() {
    // The exact mapped list, one call per input, so a formatter that ignored its argument fails
    // on the second entry rather than passing an `allMatch`.
    val inputs = listOf(0L, 1_000L, 61_000L, 599_000L, 600_000L, 3_661_000L)

    assertThat(inputs.map(::formatDuration))
      .containsExactly("0:00", "0:01", "1:01", "9:59", "10:00", "1:01:01")
  }

  /**
   * The hour boundary from both sides, plus a sub-second remainder. `3_599_999` must not round up
   * into an hour and must not render `60:00`; `3_600_000` must grow the hours field and reset the
   * minutes to a *zero-padded* `00` rather than to `0`.
   */
  @Test
  fun `the hour boundary is crossed exactly once`() {
    val inputs = listOf(59_999L, 3_599_999L, 3_600_000L, 3_600_999L, 36_000_000L)

    assertThat(inputs.map(::formatDuration))
      .containsExactly("0:59", "59:59", "1:00:00", "1:00:00", "10:00:00")
  }

  @Test
  fun `an unknown duration formats as a placeholder rather than a negative time`() {
    // `Player.getDuration()` is C.TIME_UNSET until the extractor has read the container.
    // PlaybackConnection maps that to 0, but a negative can still arrive from a stale controller,
    // and "-9223372036854:775" on a lock screen is a memorable bug.
    assertThat(formatDuration(-1L)).isEqualTo("0:00")
    assertThat(formatDuration(Long.MIN_VALUE)).isEqualTo("0:00")
  }
}
