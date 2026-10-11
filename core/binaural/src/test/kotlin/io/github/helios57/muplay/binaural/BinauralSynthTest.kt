package io.github.helios57.muplay.binaural

import kotlin.math.abs
import kotlin.math.sqrt
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * The synthesiser is held to what an ear would measure: the frequency in each channel, the beat
 * between them, and the absence of clicks. Frequencies are measured by counting upward zero
 * crossings over whole seconds, which needs no FFT and cannot be fooled by the code's own idea of
 * what it rendered.
 */
class BinauralSynthTest {

  private val rate = 48_000

  private fun steady(beat: Double, carrier: Double, seconds: Double = 10.0) = BinauralProfile.static(
    id = "t", name = "t", beatHz = beat, carrierHz = carrier, durationSeconds = seconds,
    fadeInSeconds = 0.0, fadeOutSeconds = 0.0,
  )

  private fun render(synth: BinauralSynth, seconds: Double): FloatArray {
    val frames = (seconds * rate).toInt()
    val out = FloatArray(frames * 2)
    var done = 0
    val block = FloatArray(1024 * 2)
    while (done < frames) {
      val n = synth.render(block, minOf(1024, frames - done))
      if (n == 0) break
      System.arraycopy(block, 0, out, done * 2, n * 2)
      done += n
    }
    return out.copyOf(done * 2)
  }

  private fun channel(pcm: FloatArray, right: Boolean) =
    FloatArray(pcm.size / 2) { pcm[2 * it + if (right) 1 else 0] }

  private fun frequency(samples: FloatArray, fromSecond: Double, seconds: Double): Double {
    val from = (fromSecond * rate).toInt()
    val to = from + (seconds * rate).toInt()
    var crossings = 0
    for (i in from + 1 until to) if (samples[i - 1] < 0f && samples[i] >= 0f) crossings++
    return crossings / seconds
  }

  @Test
  fun `the owner's concentrate example sounds 158_44 Hz left and 144_98 Hz right`() {
    val pcm = render(BinauralSynth(Presets.concentrate.copy(fadeInSeconds = 0.0)), 5.0)

    assertThat(frequency(channel(pcm, right = false), 1.0, 4.0)).isCloseTo(158.44, Offset.offset(0.5))
    assertThat(frequency(channel(pcm, right = true), 1.0, 4.0)).isCloseTo(144.98, Offset.offset(0.5))
  }

  @Test
  fun `the two ears differ by exactly the beat, which is the whole effect`() {
    val pcm = render(BinauralSynth(steady(beat = 6.0, carrier = 200.0)), 10.0)

    val left = frequency(channel(pcm, false), 0.0, 10.0)
    val right = frequency(channel(pcm, true), 0.0, 10.0)
    assertThat(left - right).isCloseTo(6.0, Offset.offset(0.2))
    assertThat((left + right) / 2).isCloseTo(200.0, Offset.offset(0.2))
  }

  @Test
  fun `a ramp lands on the frequency the timeline says, at the time it says`() {
    val ramp = BinauralProfile(
      id = "r", name = "r", fadeInSeconds = 0.0, fadeOutSeconds = 0.0,
      segments = listOf(Segment(10.0, 2.0, 200.0, 120.0, durationSeconds = 20.0)),
    )
    val pcm = render(BinauralSynth(ramp), 20.0)

    // Around t = 15 s: beat 4, carrier 140, so left 142 and right 138. One second centred there.
    assertThat(frequency(channel(pcm, false), 14.5, 1.0)).isCloseTo(142.0, Offset.offset(2.0))
    assertThat(frequency(channel(pcm, true), 14.5, 1.0)).isCloseTo(138.0, Offset.offset(2.0))
  }

  @Test
  fun `a frequency change never steps the waveform, so there is no click`() {
    // Two segments with a hard frequency jump at the seam: the phase must carry on regardless. The
    // largest sample-to-sample step a sine of amplitude a at f Hz can take is 2 pi f a / fs.
    val jump = BinauralProfile(
      id = "j", name = "j", fadeInSeconds = 0.0, fadeOutSeconds = 0.0,
      segments = listOf(Segment(4.0, 200.0, 1.0), Segment(30.0, 400.0, 1.0)),
    )
    val left = channel(render(BinauralSynth(jump), 2.0), right = false)

    val bound = 2 * Math.PI * 415.0 * Defaults.AMPLITUDE / rate * 1.05
    val worst = (1 until left.size).maxOf { abs(left[it] - left[it - 1]).toDouble() }
    assertThat(worst).isLessThan(bound)
  }

  @Test
  fun `the fade in starts from silence and the program ends in silence`() {
    val profile = steady(4.0, 150.0, seconds = 6.0).copy(fadeInSeconds = 2.0, fadeOutSeconds = 2.0)
    val synth = BinauralSynth(profile)
    val left = channel(render(synth, 7.0), right = false)

    assertThat(abs(left[0])).isLessThan(1e-6f)
    assertThat(peak(left, 2.5, 3.5)).isCloseTo(Defaults.AMPLITUDE, Offset.offset(0.005))
    assertThat(peak(left, 5.95, 6.0)).isLessThan(0.002)
    assertThat(left.size).isEqualTo(6 * rate)
    assertThat(synth.isFinished).isTrue
  }

  @Test
  fun `stopping fades out over the time asked and then finishes`() {
    val synth = BinauralSynth(steady(4.0, 150.0, seconds = 600.0))
    render(synth, 1.0)
    synth.release(seconds = 1.0)

    val tail = channel(render(synth, 5.0), right = false)

    assertThat(tail.size).isEqualTo(rate)
    assertThat(peak(tail, 0.0, 0.1)).isGreaterThan(0.08)
    assertThat(peak(tail, 0.95, 1.0)).isLessThan(0.002)
    assertThat(synth.isFinished).isTrue
  }

  @Test
  fun `a program set to hold its last tone keeps playing past its end`() {
    val synth = BinauralSynth(steady(4.0, 150.0, seconds = 1.0).copy(endBehaviour = EndBehaviour.HOLD_LAST))
    val left = channel(render(synth, 3.0), right = false)

    assertThat(left.size).isEqualTo(3 * rate)
    assertThat(peak(left, 2.5, 3.0)).isGreaterThan(0.09)
    assertThat(synth.isFinished).isFalse
  }

  @Test
  fun `the listener's volume scales the tones without touching the program`() {
    val synth = BinauralSynth(steady(4.0, 150.0))
    synth.masterGain = 0.25
    val left = channel(render(synth, 2.0), right = false)

    assertThat(peak(left, 1.0, 2.0)).isCloseTo(Defaults.AMPLITUDE * 0.25, Offset.offset(0.002))
    assertThat(synth.profile.amplitude).isEqualTo(Defaults.AMPLITUDE)
  }

  @Test
  fun `replacing the profile mid-play glides to the new tone and keeps the clock`() {
    val synth = BinauralSynth(steady(4.0, 150.0, seconds = 60.0))
    render(synth, 2.0)
    synth.replaceProfile(steady(4.0, 300.0, seconds = 60.0))

    val left = channel(render(synth, 2.0), right = false)

    assertThat(synth.positionSeconds).isEqualTo(4.0)
    assertThat(frequency(left, 1.0, 1.0)).isCloseTo(302.0, Offset.offset(1.0))
    val worst = (1 until left.size).maxOf { abs(left[it] - left[it - 1]).toDouble() }
    assertThat(worst).isLessThan(2 * Math.PI * 302.0 * Defaults.AMPLITUDE / rate * 1.05)
  }

  @Test
  fun `noise sits under the tones at the level asked, and the ears get different noise`() {
    for (type in listOf(NoiseType.PINK, NoiseType.BROWN, NoiseType.WHITE)) {
      val profile = steady(4.0, 150.0).copy(amplitude = 0.0001, noiseType = type, noiseLevel = 0.1)
      val pcm = render(BinauralSynth(profile), 3.0)
      val left = channel(pcm, false)
      val right = channel(pcm, true)

      assertThat(rms(left, 1.0, 3.0)).describedAs("$type rms").isCloseTo(0.1, Offset.offset(0.035))
      assertThat(left.contentEquals(right)).describedAs("$type left == right").isFalse
    }
  }

  @Test
  fun `the same profile and seed render the same samples`() {
    val profile = Presets.powerNap
    assertThat(render(BinauralSynth(profile), 0.5)).isEqualTo(render(BinauralSynth(profile), 0.5))
  }

  @Test
  fun `the output never leaves the float range however loud the settings`() {
    val loud = steady(4.0, 150.0).copy(amplitude = Limits.MAX_AMPLITUDE, noiseType = NoiseType.WHITE, noiseLevel = Limits.MAX_NOISE_LEVEL)
    val pcm = render(BinauralSynth(loud), 2.0)

    assertThat(pcm.maxOf { abs(it) }).isLessThanOrEqualTo(1.0f)
  }

  private fun peak(samples: FloatArray, from: Double, to: Double): Double =
    ((from * rate).toInt() until minOf(samples.size, (to * rate).toInt())).maxOf { abs(samples[it]).toDouble() }

  private fun rms(samples: FloatArray, from: Double, to: Double): Double {
    val range = (from * rate).toInt() until (to * rate).toInt()
    return sqrt(range.sumOf { samples[it].toDouble() * samples[it] } / range.count())
  }
}
