package app.muplay.book

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two strings this feature puts on a screen, held to exact text.
 *
 * Both are pure `Long -> String`, so every branch either has an input here or does not exist. The
 * zero and negative cases are not defensive padding: [formatRemaining]'s argument is
 * `bookDurationMs - bookPositionMs`, and [formatClock]'s is a sleep-timer countdown, so both
 * subtract two numbers that come from different places and can cross.
 */
class BookFormattingTest {

  @Test
  fun `a clock below an hour is minutes and seconds`() {
    assertThat(formatClock(0L)).isEqualTo("0:00")
    assertThat(formatClock(9_000L)).isEqualTo("0:09")
    assertThat(formatClock(90_000L)).isEqualTo("1:30")
    assertThat(formatClock(3_599_000L)).isEqualTo("59:59")
  }

  @Test
  fun `a clock at or past an hour grows an hours field`() {
    // The boundary and one past it. A `>=` written as `>` renders one hour exactly as "60:00",
    // which is the kind of thing nobody notices until an audiobook is exactly an hour long.
    assertThat(formatClock(3_600_000L)).isEqualTo("1:00:00")
    assertThat(formatClock(3_725_000L)).isEqualTo("1:02:05")
    assertThat(formatClock(45_296_000L)).isEqualTo("12:34:56")
  }

  @Test
  fun `a negative clock reads as zero rather than as a minus sign`() {
    assertThat(formatClock(-1L)).isEqualTo("0:00")
    assertThat(formatClock(-3_725_000L)).isEqualTo("0:00")
  }

  @Test
  fun `under a minute left says so rather than counting seconds`() {
    // Zero, a whole minute short of one, and a negative. "0 m left" is both uglier and less true.
    assertThat(formatRemaining(0L)).isEqualTo("under a minute left")
    assertThat(formatRemaining(59_999L)).isEqualTo("under a minute left")
    assertThat(formatRemaining(-60_000L)).isEqualTo("under a minute left")
  }

  @Test
  fun `minutes left below an hour`() {
    assertThat(formatRemaining(60_000L)).isEqualTo("1 m left")
    assertThat(formatRemaining(3_540_000L)).isEqualTo("59 m left")
  }

  @Test
  fun `hours and minutes left at and past an hour`() {
    assertThat(formatRemaining(3_600_000L)).isEqualTo("1 h 0 m left")
    assertThat(formatRemaining(3_720_000L)).isEqualTo("1 h 2 m left")
    assertThat(formatRemaining(45_296_000L)).isEqualTo("12 h 34 m left")
  }
}
