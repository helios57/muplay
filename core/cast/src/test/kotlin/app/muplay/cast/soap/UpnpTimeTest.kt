package app.muplay.cast.soap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `H:MM:SS` in both directions.
 *
 * Small, and worth its own class because every value it returns is a **position**, and a position
 * that is wrong by a factor or an offset is the silent-wrong-answer class this project treats as
 * the worst kind. The spec already records one of these hazards elsewhere -- `createBookmark`'s
 * milliseconds against `bookmarkPosition`'s seconds, "getting that backwards puts every resume out
 * by 1000x". This is the same shape of mistake in a different unit.
 */
class UpnpTimeTest {

  @Test
  fun `a clock value parses to milliseconds`() {
    // Five observations spanning hours, minutes and seconds independently, so no single constant
    // and no wrong multiplier satisfies them.
    assertThat(UpnpTime.parseClock("0:00:00")).isEqualTo(0L)
    assertThat(UpnpTime.parseClock("0:00:01")).isEqualTo(1_000L)
    assertThat(UpnpTime.parseClock("0:01:00")).isEqualTo(60_000L)
    assertThat(UpnpTime.parseClock("1:00:00")).isEqualTo(3_600_000L)
    assertThat(UpnpTime.parseClock("1:02:03")).isEqualTo(3_723_000L)
  }

  @Test
  fun `both the padded and unpadded hour forms parse`() {
    // Sonos sends "0:01:23"; several DLNA renderers send "00:01:23". Handling one and not the
    // other produces a seek bar that works on one brand of speaker.
    assertThat(UpnpTime.parseClock("00:01:23")).isEqualTo(83_000L)
    assertThat(UpnpTime.parseClock("0:01:23")).isEqualTo(83_000L)
    assertThat(UpnpTime.parseClock("10:01:23")).isEqualTo(36_083_000L)
  }

  @Test
  fun `surrounding whitespace is trimmed rather than making the value unreadable`() {
    // A device that pretty-prints its XML puts newlines around the text of an element, and the
    // DOM hands them back. A position lost to indentation is a seek bar stuck at zero.
    assertThat(UpnpTime.parseClock("  0:01:23 ")).isEqualTo(83_000L)
    assertThat(UpnpTime.parseClock("\n      NOT_IMPLEMENTED\n    ")).isNull()
  }

  @Test
  fun `a fractional second is parsed rather than making the whole value unreadable`() {
    assertThat(UpnpTime.parseClock("0:00:01.500")).isEqualTo(1_500L)
    assertThat(UpnpTime.parseClock("0:00:01.5")).isEqualTo(1_500L)
    // Two digits is its own multiplier, and the one a `when` over the length is most likely to
    // get wrong: ".05" is fifty milliseconds, not five and not five hundred.
    assertThat(UpnpTime.parseClock("0:00:01.05")).isEqualTo(1_050L)
    assertThat(UpnpTime.parseClock("0:00:01.50")).isEqualTo(1_500L)
    assertThat(UpnpTime.parseClock("0:02:03.250")).isEqualTo(123_250L)
  }

  @Test
  fun `NOT_IMPLEMENTED and the other unusable values are null, not zero`() {
    // Null and zero are different facts. `AbsTime` is `NOT_IMPLEMENTED` on most renderers, and a
    // player that read it as 0 would jump the seek bar to the start once a second.
    assertThat(UpnpTime.parseClock("NOT_IMPLEMENTED")).isNull()
    assertThat(UpnpTime.parseClock("")).isNull()
    assertThat(UpnpTime.parseClock(null)).isNull()
    assertThat(UpnpTime.parseClock("garbage")).isNull()
    assertThat(UpnpTime.parseClock("1:2")).isNull()
    assertThat(UpnpTime.parseClock("a:b:c")).isNull()
    // Out-of-range fields, which a lenient `split(":")` implementation would happily accept and
    // turn into a position past the end of the track.
    assertThat(UpnpTime.parseClock("0:60:00")).isNull()
    assertThat(UpnpTime.parseClock("0:00:60")).isNull()
    assertThat(UpnpTime.parseClock("-0:00:01")).isNull()
    assertThat(UpnpTime.parseClock("0:00:01.1234")).isNull()
    assertThat(UpnpTime.NOT_IMPLEMENTED).isEqualTo("NOT_IMPLEMENTED")
  }

  @Test
  fun `formatting a clock value is the inverse of parsing it`() {
    assertThat(UpnpTime.formatClock(0L)).isEqualTo("0:00:00")
    assertThat(UpnpTime.formatClock(1_000L)).isEqualTo("0:00:01")
    assertThat(UpnpTime.formatClock(83_000L)).isEqualTo("0:01:23")
    assertThat(UpnpTime.formatClock(3_723_000L)).isEqualTo("1:02:03")
    assertThat(UpnpTime.formatClock(36_083_000L)).isEqualTo("10:01:23")
  }

  @Test
  fun `formatting truncates rather than rounding, because a seek target must not overshoot`() {
    // Rounding 4999 ms up to 0:00:05 would seek past where the user asked. Truncation is the safe
    // direction and is stated as an assertion rather than left to `Math.round`'s default.
    assertThat(UpnpTime.formatClock(4_999L)).isEqualTo("0:00:04")
    assertThat(UpnpTime.formatClock(5_000L)).isEqualTo("0:00:05")
  }

  @Test
  fun `a negative position formats as zero rather than as a negative clock`() {
    // `Player.currentPosition` can be `C.TIME_UNSET` and arithmetic on it goes negative. A
    // `Seek` target of "-1:-1:-1" is a 711 from the device; "0:00:00" is a correct answer.
    assertThat(UpnpTime.formatClock(-1L)).isEqualTo("0:00:00")
    assertThat(UpnpTime.formatClock(Long.MIN_VALUE / 2)).isEqualTo("0:00:00")
    assertThat(UpnpTime.formatDuration(-1L)).isEqualTo("0:00:00.000")
  }

  @Test
  fun `a duration for DIDL carries milliseconds`() {
    // A different format from `formatClock`, on purpose: DIDL's `res@duration` conventionally
    // carries three decimal places and renderers use it to size the progress bar.
    assertThat(UpnpTime.formatDuration(0L)).isEqualTo("0:00:00.000")
    assertThat(UpnpTime.formatDuration(83_000L)).isEqualTo("0:01:23.000")
    assertThat(UpnpTime.formatDuration(83_250L)).isEqualTo("0:01:23.250")
    assertThat(UpnpTime.formatDuration(3_723_004L)).isEqualTo("1:02:03.004")
  }

  @Test
  fun `every clock this client writes is one it can read back`() {
    // The two halves are separate implementations, so the round trip is a real observation and
    // not a restatement: an off-by-one in either direction breaks it.
    listOf(0L, 999L, 1_000L, 83_000L, 3_723_000L, 36_083_000L).forEach { millis ->
      assertThat(UpnpTime.parseClock(UpnpTime.formatClock(millis)))
        .describedAs("round trip of %d ms", millis)
        .isEqualTo(millis / 1_000 * 1_000)
    }
  }
}
