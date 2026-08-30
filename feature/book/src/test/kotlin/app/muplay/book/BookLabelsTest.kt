package app.muplay.book

import app.muplay.media.BookChapter
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The two strings the book screens build rather than merely render.
 *
 * Both are asserted as exact strings, because both are what a Plan 4 Task 10 journey will look a
 * control up by.
 */
class BookLabelsTest {

  private val default = Locale.getDefault()

  @AfterEach
  fun restoreLocale() = Locale.setDefault(default)

  @Test
  fun `the speed reads as one decimal place with an x`() {
    // Three speeds, so a formatter that printed the raw float ("1.4000001x", which is what a
    // repeated `+ 0.1f` produces) fails, and one that hardcoded 1.0 fails twice.
    assertThat(formatSpeed(1.0f)).isEqualTo("Speed 1.0x")
    assertThat(formatSpeed(1.4f)).isEqualTo("Speed 1.4x")
    assertThat(formatSpeed(0.8f)).isEqualTo("Speed 0.8x")
  }

  @Test
  fun `a speed arrived at by stepping still reads as one decimal place`() {
    // `1.0f + 0.1f + 0.1f + 0.1f` is 1.3000001 in binary floating point, and the screen's faster
    // button produces exactly that. Rounding is what makes the control readable at all.
    var speed = 1.0f
    repeat(3) { speed += 0.1f }

    assertThat(formatSpeed(speed)).isEqualTo("Speed 1.3x")
  }

  @Test
  fun `the speed's decimal separator is a dot under any default locale`() {
    // `"%.1f".format(1.4f)` renders "1,4" under a French default. This string is compared by an
    // `onNodeWithText` in a journey and is never parsed or persisted, so a locale-sensitive
    // separator is a suite that is green in CI and red on somebody's laptop.
    Locale.setDefault(Locale.FRANCE)

    assertThat(formatSpeed(1.4f)).isEqualTo("Speed 1.4x")
  }

  @Test
  fun `a chapter row is numbered from one`() {
    // Two chapters, so an implementation that dropped the `+ 1` -- or added it twice -- cannot
    // pass by coincidence on a single-chapter fixture.
    assertThat(chapterRowLabel(BookChapter(0, "Prologue", "m4b", 0, 0, 4_000, 0)))
      .isEqualTo("1. Prologue")
    assertThat(chapterRowLabel(BookChapter(2, "A Turn", "m4b", 0, 9_000, 15_000, 9_000)))
      .isEqualTo("3. A Turn")
  }
}
