package io.github.helios57.muplay.binaural

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProfileValidationTest {

  private val ok = Presets.concentrate

  private fun blocking(profile: BinauralProfile) = problemsOf(profile).filter { it.blocking }.map { it.message }
  private fun warnings(profile: BinauralProfile) = problemsOf(profile).filterNot { it.blocking }.map { it.message }

  private fun withSegment(s: Segment) = ok.copy(segments = listOf(s))

  @Test
  fun `a blank name is refused`() {
    assertThat(blocking(ok.copy(name = " "))).containsExactly("Give the profile a name.")
    assertThat(blocking(ok.copy(name = "x".repeat(61)))).hasSize(1)
  }

  @Test
  fun `a beat outside 0_1 to 40 Hz is refused and above 30 is a warning`() {
    assertThat(blocking(withSegment(Segment(45.0, 200.0, 60.0)))).singleElement().asString().contains("beat")
    assertThat(blocking(withSegment(Segment(0.0, 200.0, 60.0)))).isNotEmpty
    assertThat(blocking(withSegment(Segment(Double.NaN, 200.0, 60.0)))).isNotEmpty
    assertThat(warnings(withSegment(Segment(35.0, 300.0, 60.0)))).singleElement().asString().contains("two tones")
  }

  @Test
  fun `a carrier outside 40 to 1000 Hz is refused and outside 100 to 500 is a warning`() {
    assertThat(blocking(withSegment(Segment(4.0, 2000.0, 60.0)))).singleElement().asString().contains("carrier")
    assertThat(warnings(withSegment(Segment(4.0, 80.0, 60.0)))).singleElement().asString().contains("100 and 500")
  }

  @Test
  fun `a beat too wide for its carrier is refused`() {
    assertThat(blocking(withSegment(Segment(40.0, 45.0, 60.0)))).anyMatch { it.contains("too wide") }
  }

  @Test
  fun `steps, durations, levels and fades are bounded`() {
    assertThat(blocking(ok.copy(segments = emptyList()))).contains("A profile needs at least one step.")
    assertThat(blocking(ok.copy(segments = List(65) { Segment(4.0, 200.0, 60.0) }))).isNotEmpty
    assertThat(blocking(withSegment(Segment(4.0, 200.0, 0.5)))).isNotEmpty
    assertThat(blocking(withSegment(Segment(4.0, 200.0, 13 * 3600.0)))).contains("A profile can run for at most 12 hours.")
    assertThat(blocking(ok.copy(amplitude = 0.0))).isNotEmpty
    assertThat(blocking(ok.copy(amplitude = 0.9))).isNotEmpty
    assertThat(warnings(ok.copy(amplitude = 0.3))).isNotEmpty
    assertThat(blocking(ok.copy(noiseLevel = 0.5))).isNotEmpty
    assertThat(blocking(ok.copy(fadeInSeconds = -1.0))).isNotEmpty
    assertThat(blocking(ok.copy(fadeOutSeconds = 3600.0))).isNotEmpty
  }

  @Test
  fun `the same problem in two places is said once`() {
    assertThat(blocking(ok.copy(fadeInSeconds = -1.0, fadeOutSeconds = -1.0))).hasSize(1)
  }

  @Test
  fun `a valid profile is playable and an invalid one is not`() {
    assertThat(ok.isPlayable()).isTrue
    assertThat(ok.copy(name = "").isPlayable()).isFalse
  }
}
