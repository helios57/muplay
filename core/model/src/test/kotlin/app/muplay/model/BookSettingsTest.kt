package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [BookSettings]'s companion: the defaults a book with no row gets, and the speed clamp. */
class BookSettingsTest {

  @Test
  fun `a book with no stored settings plays at one times with no silence skipping`() {
    val settings = BookSettings.default("book-42")

    // The book id is asserted because `default` is the only thing that carries it through, and a
    // hardcoded id would make every unopened book share one settings object.
    assertThat(settings.bookId).isEqualTo("book-42")
    assertThat(settings.speed).isEqualTo(1.0f)
    assertThat(settings.skipSilence).isFalse()
    // A second id, so "it returned the id it was given" is distinguishable from "it returned the
    // id the previous assertion used".
    assertThat(BookSettings.default("another-book").bookId).isEqualTo("another-book")
  }

  @Test
  fun `the speed clamp holds both ends and passes everything between them through`() {
    assertThat(listOf(-1.0f, 0.0f, 0.49f, 0.5f, 1.0f, 1.4f, 3.0f, 3.01f, 100.0f).map { BookSettings.clampSpeed(it) })
      .containsExactly(0.5f, 0.5f, 0.5f, 0.5f, 1.0f, 1.4f, 3.0f, 3.0f, 3.0f)
  }

  @Test
  fun `not a number becomes the default rather than surviving the clamp`() {
    // `Float.NaN.coerceIn(0.5f, 3.0f)` is `NaN`, and `ExoPlayer.setPlaybackSpeed(NaN)` throws from
    // a listener callback -- playback dies with no message a user could act on. This is the one
    // input `coerceIn` alone does not handle, so it is asserted on its own rather than folded into
    // the row above, where `containsExactly` would compare NaN to NaN and pass either way.
    val clamped = BookSettings.clampSpeed(Float.NaN)

    assertThat(clamped.isNaN()).isFalse()
    assertThat(clamped).isEqualTo(1.0f)
  }

  @Test
  fun `the bounds are the numbers the UI steps between`() {
    // The constants are read by the speed control (Task 7) and by the clamp above. Pinning them
    // here is what makes a change to one of them a decision somebody took rather than a typo.
    assertThat(BookSettings.MIN_SPEED).isEqualTo(0.5f)
    assertThat(BookSettings.MAX_SPEED).isEqualTo(3.0f)
    assertThat(BookSettings.DEFAULT_SPEED).isEqualTo(1.0f)
    assertThat(BookSettings.SPEED_STEP).isEqualTo(0.1f)
  }
}
