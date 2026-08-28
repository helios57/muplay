package app.muplay.media

import app.muplay.model.BookSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "What should the player be doing for this item" -- one function, no Android, gated in Tier 1.
 *
 * The reason it is a separate type from the controller that applies it: applying is `ExoPlayer`
 * plumbing and needs a device; **deciding** is where the bug is, and a bug that costs an emulator
 * boot per mutation does not get mutated.
 */
class BookPlaybackSettingsTest {

  private fun item(speed: Float, skipSilence: Boolean) = AudiobookItem(
    mediaId = "m",
    bookId = "b",
    positionMs = 0L,
    lastPlayedAtEpochMs = 0L,
    isFinished = false,
    speed = speed,
    skipSilence = skipSilence,
  )

  @Test
  fun `an audiobook item plays at its book's speed`() {
    // Two speeds, so a constant satisfies at most one -- and one above 1.0 and one below, so a
    // `coerceAtLeast`/`coerceAtMost` written the wrong way round cannot satisfy both either.
    assertThat(BookPlaybackSettings.of(item(1.4f, false)).speed).isEqualTo(1.4f)
    assertThat(BookPlaybackSettings.of(item(0.8f, false)).speed).isEqualTo(0.8f)
  }

  @Test
  fun `anything that is not an audiobook plays at normal speed with no silence skipping`() {
    // The trap this task is named for. `null` is "not in the audiobook snapshot", i.e. music.
    assertThat(BookPlaybackSettings.of(null)).isEqualTo(BookPlaybackSettings.MUSIC)
    assertThat(BookPlaybackSettings.MUSIC.speed).isEqualTo(BookSettings.DEFAULT_SPEED)
    assertThat(BookPlaybackSettings.MUSIC.skipSilence).isFalse
  }

  @Test
  fun `silence skipping follows the book too`() {
    // Both values, from two items that differ in nothing else: a hardcoded `false` -- which is what
    // "music never skips silence" looks like if it leaks into the book arm -- satisfies one of them.
    assertThat(BookPlaybackSettings.of(item(1.0f, true)).skipSilence).isTrue
    assertThat(BookPlaybackSettings.of(item(1.0f, false)).skipSilence).isFalse
  }

  @Test
  fun `the speed and the silence flag are read from the same item, not swapped`() {
    // One item carrying two values that no constant and no swap could both satisfy: a `false` flag
    // beside a non-default speed, and a `true` flag beside the default one. Without this pair,
    // `of` could read `skipSilence` from a book's *speed* being non-default -- which is exactly
    // what a "books skip silence, music does not" shortcut would compile to.
    assertThat(BookPlaybackSettings.of(item(1.4f, false)))
      .isEqualTo(BookPlaybackSettings(1.4f, skipSilence = false))
    assertThat(BookPlaybackSettings.of(item(1.0f, true)))
      .isEqualTo(BookPlaybackSettings(1.0f, skipSilence = true))
  }

  @Test
  fun `an impossible stored speed never reaches the player`() {
    // `AudiobookRepository` clamps on the way in, and this clamps again on the way out. Two clamps
    // rather than one because `ExoPlayer.setPlaybackSpeed(NaN)` throws from inside a listener
    // callback, which surfaces as playback dying with no message a listener could act on -- and a
    // `NaN` reaches an `AudiobookItem` from a corrupted `REAL` column without passing a setter.
    assertThat(BookPlaybackSettings.of(item(99f, false)).speed).isEqualTo(BookSettings.MAX_SPEED)
    assertThat(BookPlaybackSettings.of(item(0f, false)).speed).isEqualTo(BookSettings.MIN_SPEED)
    assertThat(BookPlaybackSettings.of(item(Float.NaN, false)).speed)
      .isEqualTo(BookSettings.DEFAULT_SPEED)
  }

  @Test
  fun `an impossible speed does not also turn silence skipping off`() {
    // The clamp touches one field. Written as `BookPlaybackSettings.MUSIC.copy(speed = clamped)` --
    // which reads perfectly well -- it would touch both, and a book with a corrupt speed column
    // would silently lose the listener's silence-skipping choice as well.
    assertThat(BookPlaybackSettings.of(item(99f, true)))
      .isEqualTo(BookPlaybackSettings(BookSettings.MAX_SPEED, skipSilence = true))
  }
}
