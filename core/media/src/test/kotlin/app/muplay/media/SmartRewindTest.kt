package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Spec section 5's *"smart rewind ... scaled to how long the book was paused"*, made testable.
 *
 * Three rules shape every assertion here:
 *
 * 1. **A case whose expected rewind is zero cannot fail.** `rewindMs` returning 0 for every input
 *    satisfies it. The zero band is therefore asserted exactly once as a band, and every other
 *    band carries a value no other band carries, so a constant satisfies at most one assertion in
 *    this file.
 * 2. **A threshold is asserted on both sides, one millisecond apart.** That is what makes the test
 *    about the comparison rather than about one number -- a `<` silently becoming `<=` moves
 *    exactly one input's answer, and nothing else in a suite would notice.
 * 3. **The inputs are literals, never the constants under test.** `rewindMs(SmartRewind
 *    .AWAY_SHORT_MS)` moves with the constant it is meant to pin and can therefore never fail;
 *    this project has already paid for one test parameterised by the constant it was testing.
 *    `REWIND_MAX_MS` is named in exactly one test, and only because its value is separately
 *    asserted as the literal `20_000L` two tests above.
 */
class SmartRewindTest {

  @Test
  fun `a pause you barely noticed rewinds nothing`() {
    // The one zero-as-a-band assertion in this file. It is here because 0 is the specified answer
    // for this band, not because zero is a safe default.
    assertThat(SmartRewind.rewindMs(0L)).isZero
    assertThat(SmartRewind.rewindMs(14_999L)).isZero
  }

  @Test
  fun `each band rewinds its own distinct amount`() {
    // The whole table in one assertion, over five inputs that are each well inside their band.
    // Five different answers: no constant, no `index * k`, no linear ramp satisfies this list.
    val awayTimes = listOf(
      5_000L,          // under 15 s
      30_000L,         // 15 s .. 1 min
      600_000L,        // 1 min .. 1 h
      7_200_000L,      // 1 h .. 1 day
      172_800_000L,    // over 1 day
    )

    assertThat(awayTimes.map { SmartRewind.rewindMs(it) })
      .containsExactly(0L, 2_000L, 5_000L, 10_000L, 20_000L)
  }

  @Test
  fun `the fifteen second threshold is where rewinding starts`() {
    assertThat(SmartRewind.rewindMs(14_999L)).isEqualTo(0L)
    assertThat(SmartRewind.rewindMs(15_000L)).isEqualTo(2_000L)
  }

  @Test
  fun `the one minute threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(59_999L)).isEqualTo(2_000L)
    assertThat(SmartRewind.rewindMs(60_000L)).isEqualTo(5_000L)
  }

  @Test
  fun `the one hour threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(3_599_999L)).isEqualTo(5_000L)
    assertThat(SmartRewind.rewindMs(3_600_000L)).isEqualTo(10_000L)
  }

  @Test
  fun `the one day threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(86_399_999L)).isEqualTo(10_000L)
    assertThat(SmartRewind.rewindMs(86_400_000L)).isEqualTo(20_000L)
  }

  @Test
  fun `a month away rewinds the same as a day away and no more`() {
    // The top band is open-ended, and an unbounded scale would rewind a listener to the start of
    // the chapter after a holiday. Two very different inputs, one answer -- which is the *only*
    // place in this file where two inputs sharing an answer is the assertion.
    assertThat(SmartRewind.rewindMs(30L * 86_400_000L)).isEqualTo(SmartRewind.REWIND_MAX_MS)
    assertThat(SmartRewind.rewindMs(365L * 86_400_000L)).isEqualTo(SmartRewind.REWIND_MAX_MS)
  }

  @Test
  fun `a clock that went backwards rewinds nothing rather than something enormous`() {
    // `awayMs` is `clock.millis() - lastPlayedAtEpochMs`, and a device whose clock moved backwards
    // -- NTP correction, a manual change, a timezone-confused restore -- produces a negative.
    //
    // MEASURED, and it corrects this task's plan: **neither of these two assertions discriminates
    // a dedicated `awayMs < 0L` arm.** Removing such an arm changes the answer at no input,
    // `Long.MIN_VALUE` included, because every negative is already below the first threshold. The
    // plan predicted `Long.MIN_VALUE` would catch it; it does not, so the arm is not in the source
    // and its reasoning is prose in `SmartRewind`'s KDoc instead.
    //
    // What these two assertions *do* gate is the specified behaviour, against an implementation
    // that reached for absolute values, unsigned arithmetic, or a table scan that throws on an
    // input below its first key. That is worth two lines; a branch pretending to implement it was
    // not.
    assertThat(SmartRewind.rewindMs(-1L)).isZero
    assertThat(SmartRewind.rewindMs(Long.MIN_VALUE)).isZero
  }

  @Test
  fun `the resume position is the stored position minus the band's rewind`() {
    // Two bands, two results, from the same stored position. A `resumePositionMs` that ignored
    // `awayMs` would pass either one alone.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 60_000L, awayMs = 30_000L))
      .isEqualTo(58_000L)
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 60_000L, awayMs = 600_000L))
      .isEqualTo(55_000L)
  }

  @Test
  fun `the resume position varies with the stored position too`() {
    // The other argument. Holding `awayMs` constant and moving the stored position is what stops
    // `resumePositionMs` from being a function of one input.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 10_000L, awayMs = 600_000L))
      .isEqualTo(5_000L)
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 90_000L, awayMs = 600_000L))
      .isEqualTo(85_000L)
  }

  @Test
  fun `a rewind never goes past the start of the file`() {
    // Two seconds into a chapter, gone for a week. A negative position reaches `seekTo`, and
    // ExoPlayer's behaviour for one is not something a listener should discover.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 2_000L, awayMs = 30L * 86_400_000L))
      .isZero
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 0L, awayMs = 30_000L)).isZero
  }

  @Test
  fun `a negative stored position is treated as the start`() {
    // Not reachable from `ProgressWriter`, which coerces at write time -- but it is reachable from
    // a hand-edited database, and this function is the last thing between that row and `seekTo`.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = -5_000L, awayMs = 0L)).isZero
  }

  @Test
  fun `a stored position at the bottom of the range does not wrap into the far future`() {
    // The input that breaks the shorter `(storedPositionMs - rewindMs(awayMs)).coerceAtLeast(0L)`
    // this function's KDoc describes: `Long.MIN_VALUE - 20_000` wraps to a huge POSITIVE, which a
    // lower clamp cannot see. So this is not a duplicate of the test above -- that one passes
    // against the wrapping form and this one does not, which is the whole reason it is here.
    assertThat(
      SmartRewind.resumePositionMs(storedPositionMs = Long.MIN_VALUE, awayMs = 30L * 86_400_000L),
    ).isZero
  }
}
