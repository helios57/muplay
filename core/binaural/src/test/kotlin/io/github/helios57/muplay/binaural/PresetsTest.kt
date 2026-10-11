package io.github.helios57.muplay.binaural

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PresetsTest {

  @Test
  fun `the six profiles the owner asked for are all there`() {
    assertThat(Presets.all.map { it.name })
      .contains("Meditate", "Focus", "Concentrate", "Creative", "Sleep", "Deep sleep", "Power nap")
  }

  @Test
  fun `every preset is playable and raises no warning`() {
    for (profile in Presets.all) {
      assertThat(problemsOf(profile)).describedAs(profile.name).isEmpty()
    }
  }

  @Test
  fun `ids are unique, because an edit is stored under its preset's id`() {
    assertThat(Presets.all.map { it.id }).doesNotHaveDuplicates()
    assertThat(Presets.builtIn("concentrate")).isSameAs(Presets.concentrate)
    assertThat(Presets.builtIn("nope")).isNull()
  }

  @Test
  fun `each steady profile sits in the band its name promises`() {
    assertThat(Band.of(Presets.deepSleep.segments.single().startBeatHz)).isEqualTo(Band.DELTA)
    assertThat(Band.of(Presets.meditate.segments.single().startBeatHz)).isEqualTo(Band.THETA)
    assertThat(Band.of(Presets.focus.segments.single().startBeatHz)).isEqualTo(Band.BETA)
    assertThat(Presets.concentrate.isStatic).isTrue
    assertThat(Presets.powerNap.isStatic).isFalse
  }

  @Test
  fun `nothing descends faster than half a hertz a minute`() {
    for (profile in Presets.all) {
      for (s in profile.segments) {
        val perMinute = (s.startBeatHz - s.endBeatHz) / (s.durationSeconds / 60.0)
        assertThat(perMinute).describedAs("${profile.name}: ${s.startBeatHz} -> ${s.endBeatHz}").isLessThanOrEqualTo(0.5)
      }
    }
  }

  @Test
  fun `each steady tone sits in the band its name promises`() {
    // 4 Hz is where theta starts, so a "Sleep" at exactly 4 Hz was labelled Theta on the screen.
    assertThat(Band.of(Presets.meditate.segments.single().startBeatHz)).isEqualTo(Band.THETA)
    assertThat(Band.of(Presets.creative.segments.single().startBeatHz)).isEqualTo(Band.ALPHA)
    assertThat(Band.of(Presets.concentrate.segments.single().startBeatHz)).isEqualTo(Band.SMR)
    assertThat(Band.of(Presets.focus.segments.single().startBeatHz)).isEqualTo(Band.BETA)
    assertThat(Band.of(Presets.sleep.segments.single().startBeatHz)).isEqualTo(Band.DELTA)
    assertThat(Band.of(Presets.deepSleep.segments.single().startBeatHz)).isEqualTo(Band.DELTA)
  }
}
