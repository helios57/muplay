package app.muplay.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class PcmAnalysisTest {

  /** 16-bit little-endian PCM from a list of per-channel sample values. */
  private fun pcm(vararg samples: Short): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach(buffer::putShort)
    return buffer.array()
  }

  /**
   * A continuous sine, the shape every seeded fixture actually has (`ci/seed-fixtures.sh` writes
   * 385, 440 and 495 Hz tones), so the two tests that use it argue about real signal rather than
   * about a hand-picked pattern of zeroes.
   */
  private fun sine(frames: Int, frequencyHz: Double, sampleRateHz: Int): ShortArray =
    ShortArray(frames) { n ->
      (SINE_AMPLITUDE * sin(2.0 * PI * frequencyHz * n / sampleRateHz)).toInt().toShort()
    }

  @Test
  fun `frames are bytes divided by two per channel`() {
    // Two observations of the channel-count argument, because a `frameCount` that ignored it would
    // pass a mono-only test and silently halve every stereo measurement.
    assertThat(PcmAnalysis.frameCount(byteCount = 400, channelCount = 1)).isEqualTo(200)
    assertThat(PcmAnalysis.frameCount(byteCount = 400, channelCount = 2)).isEqualTo(100)
  }

  @Test
  fun `a zero channel count is rejected rather than dividing by zero`() {
    assertThatIllegalArgumentException()
      .isThrownBy { PcmAnalysis.frameCount(400, 0) }
      .withMessageContaining("channelCount")
  }

  /**
   * The measurement this analyser exists for, proven against input whose answer is known by
   * construction.
   *
   * Rule 4: a check that reports the absence of a problem must be provably incapable of staying
   * quiet when it did not run. `longestZeroRunFrames` is exactly that kind of check — the gapless
   * test passes when it returns a small number — so it gets a test that requires it to return a
   * **large** one for input that deserves it.
   */
  @Test
  fun `a known run of silence is found and measured exactly`() {
    val samples = ShortArray(1000) { 500 } + ShortArray(137) { 0 } + ShortArray(1000) { -500 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(137)
  }

  @Test
  fun `the longest run is reported and not the first or the last`() {
    val samples = ShortArray(10) { 0 } + ShortArray(50) { 100 } +
      ShortArray(400) { 0 } + ShortArray(50) { 100 } + ShortArray(20) { 0 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(400)
  }

  @Test
  fun `a run that ends at the end of the buffer still counts`() {
    // Encoder padding lives at the *end* of a track, so a scan that only closed a run on the next
    // non-zero sample would miss the exact case this measurement was built for.
    val samples = ShortArray(50) { 100 } + ShortArray(300) { 0 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(300)
  }

  @Test
  fun `a stream with no silence at all reports zero`() {
    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(1, -1, 2, -2, 3, -3), channelCount = 1)).isZero()
  }

  @Test
  fun `a frame counts as silent only when every channel is silent`() {
    // Interleaved stereo: L=0,R=500 twice, then L=0,R=0 three times, then L=700,R=700.
    // Only the middle three frames are silent.
    val samples = shortArrayOf(0, 500, 0, 500, 0, 0, 0, 0, 0, 0, 700, 700)

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 2)).isEqualTo(3)
  }

  @Test
  fun `a zero channel count is rejected by the silence scan too`() {
    // `frameCount`'s guard, reached through a second door. The scan calls `frameCount` before it
    // examines anything, so this is the same `require` the test above reaches directly — and that
    // is the point: deleting that one guard has to redden both tests, because without it this
    // call divides by zero (`ArithmeticException`, not `IllegalArgumentException`) instead of
    // reporting which argument was wrong. Measured: `pcm/frame-count-unguarded` fails 2.
    assertThatIllegalArgumentException()
      .isThrownBy { PcmAnalysis.longestZeroRunFrames(ByteArray(400), channelCount = 0) }
      .withMessageContaining("channelCount")
  }

  @Test
  fun `frames convert to milliseconds at the sample rate given`() {
    // Two rates, because a hardcoded 44100 is the obvious accident.
    assertThat(PcmAnalysis.framesToMs(frames = 44100, sampleRateHz = 44100)).isEqualTo(1000L)
    assertThat(PcmAnalysis.framesToMs(frames = 48000, sampleRateHz = 48000)).isEqualTo(1000L)
    assertThat(PcmAnalysis.framesToMs(frames = 22050, sampleRateHz = 44100)).isEqualTo(500L)
  }

  @Test
  fun `a zero sample rate is rejected rather than dividing by zero`() {
    assertThatIllegalArgumentException()
      .isThrownBy { PcmAnalysis.framesToMs(44100, 0) }
      .withMessageContaining("sampleRateHz")
  }

  @Test
  fun `a negative frame count is rejected rather than reported as a negative duration`() {
    // The device tier spends this function inside `isLessThan(10L)` assertions. A negative
    // duration satisfies every one of those, so it must not be expressible: a negative frame
    // count is a caller's arithmetic error and has to be loud, not quietly under the threshold.
    assertThatIllegalArgumentException()
      .isThrownBy { PcmAnalysis.framesToMs(-1, 44100) }
      .withMessageContaining("frames")
  }

  @Test
  fun `an empty buffer has no frames and no silence`() {
    assertThat(PcmAnalysis.frameCount(0, 1)).isZero()
    assertThat(PcmAnalysis.longestZeroRunFrames(ByteArray(0), channelCount = 1)).isZero()
  }

  /**
   * The device tier's threshold, argued against real signal rather than asserted.
   *
   * `GaplessTest` passes when the longest run of silence anywhere in a three-track queue is under
   * 10 ms, and that threshold rests on two claims neither of which is obvious: that a continuous
   * sine is never silent for more than a sample or two, and that LAME's ~1105 samples of untrimmed
   * encoder delay would blow straight past it. Both are checked here, on the same generated tone,
   * so no constant return value can satisfy the pair.
   */
  @Test
  fun `untrimmed encoder delay in a real sine is far over the device tier's threshold`() {
    val tone = sine(frames = 44_100, frequencyHz = 440.0, sampleRateHz = 44_100)
    val withEncoderDelay = ShortArray(LAME_ENCODER_DELAY_SAMPLES) { 0 } + tone

    val clean = PcmAnalysis.longestZeroRunFrames(pcm(*tone), channelCount = 1)
    val delayed = PcmAnalysis.longestZeroRunFrames(pcm(*withEncoderDelay), channelCount = 1)

    assertThat(clean)
      .describedAs("longest silence inside one second of a continuous 440 Hz sine")
      .isLessThanOrEqualTo(2)
    assertThat(PcmAnalysis.framesToMs(clean, sampleRateHz = 44_100))
      .describedAs("that same run, in the milliseconds GaplessTest compares against 10")
      .isZero()

    // The run swallows the tone's own leading zero sample, which is why this is not exactly 1105.
    assertThat(delayed).isEqualTo(LAME_ENCODER_DELAY_SAMPLES + clean)
    assertThat(PcmAnalysis.framesToMs(delayed, sampleRateHz = 44_100))
      .describedAs("untrimmed LAME encoder delay, in the same milliseconds")
      .isEqualTo(25L)
  }

  private companion object {
    /** ~80% of full scale: loud enough that no sample rounds to zero except at a real crossing. */
    const val SINE_AMPLITUDE = 26_000.0

    /** What LAME writes at the head of every MP3 it encodes, and what the Xing/LAME header trims. */
    const val LAME_ENCODER_DELAY_SAMPLES = 1105
  }
}
