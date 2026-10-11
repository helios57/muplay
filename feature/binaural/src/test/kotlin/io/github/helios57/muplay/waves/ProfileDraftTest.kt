package io.github.helios57.muplay.waves

import io.github.helios57.muplay.binaural.NoiseType
import io.github.helios57.muplay.binaural.Presets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProfileDraftTest {

  @Test
  fun `every preset survives a round trip through the editor unchanged`() {
    for (profile in Presets.all) {
      assertThat(ProfileDraft.of(profile).toProfile()).describedAs(profile.name).isEqualTo(profile)
    }
  }

  @Test
  fun `a comma is a decimal point, because the owner's keyboard is Swiss`() {
    assertThat(parseNumber("13,46")).isEqualTo(13.46)
    assertThat(parseNumber(" 7.5 ")).isEqualTo(7.5)
    assertThat(parseNumber("13.")).isEqualTo(13.0)
    assertThat(parseNumber("")).isNull()
    assertThat(parseNumber("abc")).isNull()
    assertThat(parseNumber("NaN")).isNull()
  }

  @Test
  fun `a half-typed number is a problem to show, not a crash`() {
    val draft = ProfileDraft.of(Presets.concentrate).withSegment(0) { it.copy(startBeat = "") }

    assertThat(draft.toProfile()).isNull()
    assertThat(draft.problems().map { it.message }).containsExactly("Step 1: every field needs a number.")
    assertThat(draft.canSave).isFalse
  }

  @Test
  fun `the generator's own rules are what a readable draft is held to`() {
    val draft = ProfileDraft.of(Presets.concentrate).withSegment(0) { it.copy(startBeat = "45", endBeat = "45") }

    assertThat(draft.problems()).anyMatch { it.blocking && it.message.contains("beat") }
  }

  @Test
  fun `an unreadable fade is named`() {
    val draft = ProfileDraft.of(Presets.sleep).copy(fadeOutSeconds = "x")

    assertThat(draft.problems().map { it.message }).containsExactly("Fades need a number of seconds.")
  }

  @Test
  fun `a new step carries on from where the last one ends`() {
    val draft = ProfileDraft.of(Presets.powerNap).withStepAdded()

    val added = draft.segments.last()
    assertThat(added.startBeat).isEqualTo("14")
    assertThat(added.startCarrier).isEqualTo("220")
    assertThat(added.minutes).isEqualTo("5")
    assertThat(draft.toProfile()!!.totalSeconds).isEqualTo(41 * 60.0)
  }

  @Test
  fun `the last step cannot be removed, any other can`() {
    val one = ProfileDraft.of(Presets.focus)
    assertThat(one.withStepRemoved(0)).isEqualTo(one)

    val nap = ProfileDraft.of(Presets.powerNap).withStepRemoved(2)
    assertThat(nap.segments).hasSize(4)
    assertThat(nap.segments.map { it.minutes }).containsExactly("5", "5", "3", "3")
  }

  @Test
  fun `no noise means no noise level, whatever the slider was left at`() {
    val draft = ProfileDraft.of(Presets.sleep).copy(noiseType = NoiseType.NONE, noiseLevel = 0.2)

    assertThat(draft.toProfile()!!.noiseLevel).isEqualTo(0.0)
  }

  @Test
  fun `minutes are stored as seconds`() {
    val draft = ProfileDraft.of(Presets.focus).withSegment(0) { it.copy(minutes = "1,5") }

    assertThat(draft.toProfile()!!.totalSeconds).isEqualTo(90.0)
  }
}
