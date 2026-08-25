package app.muplay.media

import app.muplay.model.ReplayGain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Pure arithmetic, no Android type, Tier 1. The decisions here are the ones that can be wrong
 * quietly: a sign error halves everything instead of doubling it, and a missing clamp turns a
 * corrupt tag into a burst of full-scale noise in someone's headphones.
 *
 * Every value is observed at **two** disjoint inputs wherever two exist, for this project's
 * standing reason: a function replaced by a constant satisfies any single-value check. The one
 * assertion here that a constant *could* satisfy is labelled where it appears.
 */
class ReplayGainPolicyTest {

  @Test
  fun `the track gain is preferred over the album gain`() {
    // Two observations, so a policy that always returned one of the two fields fails here.
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(-6.0f, -3.0f, null))).isEqualTo(-6.0f)
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(-9.0f, -3.0f, null))).isEqualTo(-9.0f)
  }

  @Test
  fun `the album gain is the fallback and only the fallback`() {
    // A shuffled queue has no album to be consistent within, so track gain is the right default.
    // Album gain still beats nothing at all for a file that only carries one.
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, -3.0f, null))).isEqualTo(-3.0f)
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, -7.5f, null))).isEqualTo(-7.5f)
  }

  @Test
  fun `an untagged file and an absent object are both no decision at all`() {
    assertThat(ReplayGainPolicy.gainDbFor(null)).isNull()
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, null, 0.9f))).isNull()
  }

  /**
   * A track gain of exactly zero is a **decision**, not an absence: it says "this file needs no
   * adjustment". Without this, `trackGainDb ?: albumGainDb` written as a numeric fallback --
   * `takeIf { it != 0f }`, say -- would silently prefer the album gain for every correctly
   * levelled file, which is the one input where the two answers differ and nobody would notice.
   */
  @Test
  fun `a track gain of zero is still the track's own decision`() {
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(0.0f, -8.0f, null))).isEqualTo(0.0f)
  }

  @Test
  fun `no decision means the samples are not touched`() {
    // Not "gain of 0 dB, applied": literally the multiplicative identity, which the processor
    // fast-paths. An untagged library must be bit-identical to no gain stage at all.
    assertThat(ReplayGainPolicy.linearGain(null, null)).isEqualTo(ReplayGainPolicy.UNCHANGED)
    // ...and a peak on its own is not a decision either: a file that reports how loud it is has
    // still not asked for anything to be done about it.
    assertThat(ReplayGainPolicy.linearGain(null, 0.5f)).isEqualTo(ReplayGainPolicy.UNCHANGED)
  }

  @Test
  fun `zero decibels is the identity rather than an absent decision`() {
    // The other half of `a track gain of zero is still the track's own decision`: 0 dB survives
    // the arithmetic as exactly 1.0, so "the file says no adjustment" and "the file says nothing"
    // agree on the multiplier while staying different facts one layer up.
    assertThat(ReplayGainPolicy.linearGain(0.0f, null))
      .isCloseTo(ReplayGainPolicy.UNCHANGED, within(0.0001f))
  }

  @Test
  fun `minus six dB is half the amplitude and plus six is double`() {
    // The one piece of arithmetic in the whole feature. 10^(-6/20) = 0.5012.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, null)).isCloseTo(0.5012f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(6.0f, null)).isCloseTo(1.9953f, within(0.001f))
    // A third point, off the +/-6 symmetry, so a table lookup of two values cannot satisfy this.
    assertThat(ReplayGainPolicy.linearGain(-12.0f, null)).isCloseTo(0.2512f, within(0.001f))
    // Sign check, stated separately because a sign error is the defect that produces a plausible
    // but exactly-wrong result: a track tagged quiet gets louder and nobody reads it as a bug.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, null)).isLessThan(1.0f)
    assertThat(ReplayGainPolicy.linearGain(6.0f, null)).isGreaterThan(1.0f)
  }

  @Test
  fun `a peak clamps a positive gain to the point of clipping and no further`() {
    // +6 dB on a file that already peaks at 0.9 would clip. The clamp is 1/peak, and it is
    // asserted at two peaks so a hardcoded 1.0 cannot satisfy it.
    assertThat(ReplayGainPolicy.linearGain(6.0f, 0.9f)).isCloseTo(1.1111f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(6.0f, 0.5f)).isCloseTo(1.9953f, within(0.001f))
  }

  @Test
  fun `a peak never pushes a gain up`() {
    // The clamp is a ceiling, not a target. A quiet-tagged track with a low peak must stay quiet;
    // "normalise everything to full scale" is a different feature and not this one.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, 0.1f)).isCloseTo(0.5012f, within(0.001f))
  }

  @Test
  fun `an absent or nonsensical peak is ignored rather than trusted`() {
    assertThat(ReplayGainPolicy.linearGain(3.0f, null)).isCloseTo(1.4125f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(3.0f, 0.0f)).isCloseTo(1.4125f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(3.0f, -1.0f)).isCloseTo(1.4125f, within(0.001f))
  }

  @Test
  fun `a corrupt tag cannot deafen anyone`() {
    // A tag reading "+90 dB" is a real thing that happens to real files. Clamped at both ends,
    // asserted at both ends, because a one-sided clamp reads as correct until the day it isn't.
    assertThat(ReplayGainPolicy.linearGain(90.0f, null))
      .isEqualTo(ReplayGainPolicy.linearGain(ReplayGainPolicy.MAX_GAIN_DB, null))
    assertThat(ReplayGainPolicy.linearGain(-90.0f, null))
      .isEqualTo(ReplayGainPolicy.linearGain(ReplayGainPolicy.MIN_GAIN_DB, null))
  }

  /**
   * The clamp bounds are values, not just a `coerceIn` that executes.
   *
   * Without these two, `MIN_GAIN_DB = -0.001f` and `MAX_GAIN_DB = 0.001f` -- a clamp that silences
   * the whole feature while keeping every relative assertion above true of *something* -- passes.
   * Both are stated as multipliers rather than as decibels, because a multiplier is what reaches
   * the samples: no tag may make a track quieter than a sixteenth or louder than four times.
   */
  @Test
  fun `the clamp bounds are wide enough to carry a real library and narrow enough to be safe`() {
    assertThat(ReplayGainPolicy.linearGain(ReplayGainPolicy.MIN_GAIN_DB, null))
      .isCloseTo(0.0631f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(ReplayGainPolicy.MAX_GAIN_DB, null))
      .isCloseTo(3.9811f, within(0.001f))
  }
}
