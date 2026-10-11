package io.github.helios57.muplay.binaural

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class ProfileTimelineTest {

  private val nap = ProfileTimeline(Presets.powerNap)

  @Test
  fun `left is carrier plus half the beat and right is carrier minus half`() {
    assertThat(leftHz(151.71, 13.46)).isCloseTo(158.44, Offset.offset(1e-9))
    assertThat(rightHz(151.71, 13.46)).isCloseTo(144.98, Offset.offset(1e-9))
  }

  @Test
  fun `the power nap goes down for ten minutes, stays deep for twenty, and comes back up`() {
    assertThat(nap.totalSeconds).isEqualTo(36 * 60.0)
    assertThat(nap.beatAt(0.0)).isEqualTo(8.0)
    assertThat(nap.beatAt(10 * 60.0)).isEqualTo(4.0)
    // Deep for twenty minutes: delta or the bottom of theta the whole way.
    for (minute in 10..30) assertThat(nap.beatAt(minute * 60.0)).isLessThanOrEqualTo(4.0)
    assertThat(Band.of(nap.beatAt(25 * 60.0))).isEqualTo(Band.DELTA)
    assertThat(nap.beatAt(36 * 60.0)).isEqualTo(14.0)
  }

  @Test
  fun `a segment seam is continuous, so a program never jumps`() {
    for (profile in Presets.all) {
      profile.segments.zipWithNext().forEach { (a, b) ->
        assertThat(b.startBeatHz).describedAs("${profile.name} beat seam").isEqualTo(a.endBeatHz)
        assertThat(b.startCarrierHz).describedAs("${profile.name} carrier seam").isEqualTo(a.endCarrierHz)
      }
    }
  }

  @Test
  fun `each curve shape starts and ends where the segment says`() {
    for (shape in CurveShape.entries) {
      val s = Segment(8.0, 2.0, 200.0, 100.0, 60.0, shape)
      assertThat(s.beatAt(0.0)).isEqualTo(8.0)
      assertThat(s.beatAt(1.0)).isCloseTo(2.0, Offset.offset(1e-9))
      assertThat(s.beatAt(0.5)).isBetween(2.0, 8.0)
    }
    assertThat(CurveShape.LINEAR.interpolate(8.0, 2.0, 0.5)).isEqualTo(5.0)
    // Exponential halves the ratio, not the difference: halfway from 8 to 2 is 4.
    assertThat(CurveShape.EXPONENTIAL.interpolate(8.0, 2.0, 0.5)).isCloseTo(4.0, Offset.offset(1e-9))
    assertThat(CurveShape.SMOOTH.interpolate(8.0, 2.0, 0.1)).isGreaterThan(CurveShape.LINEAR.interpolate(8.0, 2.0, 0.1))
  }

  @Test
  fun `the segment index is found at, between and past the seams`() {
    assertThat(nap.indexAt(-5.0)).isEqualTo(0)
    assertThat(nap.indexAt(299.0)).isEqualTo(0)
    assertThat(nap.indexAt(300.0)).isEqualTo(1)
    assertThat(nap.indexAt(31 * 60.0)).isEqualTo(3)
    assertThat(nap.indexAt(99_999.0)).isEqualTo(4)
  }

  @Test
  fun `the envelope fades in, holds, fades out and is silent after the end`() {
    val t = ProfileTimeline(Presets.meditate)
    assertThat(t.envelopeAt(0.0)).isEqualTo(0.0)
    assertThat(t.envelopeAt(5.0)).isCloseTo(0.5, Offset.offset(1e-9))
    assertThat(t.envelopeAt(600.0)).isEqualTo(1.0)
    assertThat(t.envelopeAt(t.totalSeconds - 15.0)).isCloseTo(0.5, Offset.offset(1e-9))
    assertThat(t.envelopeAt(t.totalSeconds)).isEqualTo(0.0)
  }

  @Test
  fun `a sample names the band its beat falls in`() {
    val sample = ProfileTimeline(Presets.concentrate).at(600.0)

    assertThat(sample.band).isEqualTo(Band.SMR)
    assertThat(sample.leftHz).isCloseTo(158.44, Offset.offset(1e-9))
    assertThat(sample.rightHz).isCloseTo(144.98, Offset.offset(1e-9))
  }

  @Test
  fun `the bands meet at 4, 8, 12, 15 and 30 Hz`() {
    assertThat(listOf(0.5, 3.99, 4.0, 7.99, 8.0, 12.0, 14.99, 15.0, 30.0).map(Band::of)).containsExactly(
      Band.DELTA, Band.DELTA, Band.THETA, Band.THETA, Band.ALPHA, Band.SMR, Band.SMR, Band.BETA, Band.GAMMA,
    )
  }
}
