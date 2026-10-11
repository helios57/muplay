package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.BinauralProfile
import io.github.helios57.muplay.binaural.EndBehaviour
import io.github.helios57.muplay.binaural.Presets
import io.github.helios57.muplay.binaural.ProfileTimeline
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WavesTextTest {

  @Test
  fun `hertz keep the owner's two decimals and drop trailing zeros`() {
    assertThat(formatHz(13.46)).isEqualTo("13.46")
    assertThat(formatHz(8.0)).isEqualTo("8")
    assertThat(formatHz(2.5)).isEqualTo("2.5")
    assertThat(formatHz(158.444)).isEqualTo("158.44")
    assertThat(formatHz(0.005)).isEqualTo("0.01")
  }

  @Test
  fun `lengths read in minutes and hours`() {
    assertThat(formatLength(36 * 60.0)).isEqualTo("36 min")
    assertThat(formatLength(8 * 3600.0)).isEqualTo("8 h")
    assertThat(formatLength(5400.0)).isEqualTo("1 h 30 min")
    assertThat(formatLength(10.0)).isEqualTo("1 min")
  }

  @Test
  fun `a clock shows hours only once there are some`() {
    assertThat(formatClock(725.4)).isEqualTo("12:05")
    assertThat(formatClock(3723.0)).isEqualTo("1:02:03")
    assertThat(formatClock(-3.0)).isEqualTo("0:00")
  }

  @Test
  fun `the concentrate row shows the owner's left and right tones`() {
    assertThat(summaryOf(Presets.concentrate)).isEqualTo("13.46 Hz · SMR · 45 min")
    assertThat(detailOf(Presets.concentrate)).isEqualTo("Left 158.44 Hz · right 144.98 Hz")
  }

  @Test
  fun `a program row shows its length and the beats it turns at`() {
    assertThat(summaryOf(Presets.powerNap)).isEqualTo("36 min · 8 → 2.5 → 14 Hz")
    assertThat(programPath(Presets.night)).isEqualTo("6 → 1.5 Hz")
    assertThat(programPath(Presets.focusSession)).isEqualTo("12 → 18 → 10 Hz")
    assertThat(detailOf(Presets.powerNap)).isEqualTo(Presets.powerNap.description)
  }

  @Test
  fun `the notification says the band, the beat and the time left`() {
    val profile = Presets.powerNap
    val at = 25 * 60.0
    val playing = BinauralStatus.Playing(profile, at, ProfileTimeline(profile).at(at))

    assertThat(notificationText(playing)).matches("Delta · 2\\.\\d+ Hz · 11 min left")
    assertThat(notificationText(playing.copy(stopping = true))).isEqualTo("Stopping…")
  }

  @Test
  fun `a held tone past its end says it is holding rather than a negative time`() {
    val held: BinauralProfile = Presets.sleep.copy(endBehaviour = EndBehaviour.HOLD_LAST)
    val at = held.totalSeconds + 60
    val playing = BinauralStatus.Playing(held, at, ProfileTimeline(held).at(at))

    assertThat(notificationText(playing)).endsWith("holding")
  }
}
