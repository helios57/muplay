package app.muplay.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measurements over raw 16-bit little-endian PCM, as captured from a real audio pipeline.
 *
 * Lives in `:core:testing` beside `OpenApiFixtureValidator` for the same reason that class does:
 * it is an **oracle**, and an oracle has to be gated by the fast tier or it is just another thing
 * that might be wrong. `:core:media`'s gapless test consumes it from `androidTest`, which cannot
 * see that module's own `test` source set — so a shared JVM module is where it has to live if its
 * own correctness is to be a Tier 1 concern.
 *
 * 16-bit little-endian is the only encoding handled, and that is deliberate rather than a
 * limitation: `C.ENCODING_PCM_16BIT` is what the capture asserts it received, so anything else
 * fails at the capture rather than being silently mis-measured here.
 *
 * Nothing here holds state and nothing here is Android-aware; every function is total over its
 * arguments or throws `IllegalArgumentException`. There are three `require`s and no more: one per
 * genuinely distinct guard, each of which a test reddens when it is deleted. A fourth was written
 * and then removed for being indistinguishable from its own absence — see [longestZeroRunFrames].
 */
object PcmAnalysis {

  private const val BYTES_PER_SAMPLE = 2

  private const val MILLIS_PER_SECOND = 1000L

  /** Frames (one sample per channel) in [byteCount] bytes of interleaved 16-bit PCM. */
  fun frameCount(byteCount: Int, channelCount: Int): Int {
    require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
    return byteCount / (BYTES_PER_SAMPLE * channelCount)
  }

  /**
   * The length, in frames, of the longest run of **completely silent** frames in [pcm].
   *
   * A frame counts as silent only when every one of its channels is exactly zero — a single silent
   * channel in a stereo stream is a real signal, not a gap.
   *
   * This is how untrimmed encoder delay and padding are detected. LAME writes roughly 1105 samples
   * of exact silence at the start of an MP3 and pads the final frame at the end; both are recorded
   * in the Xing/LAME header, and both survive as audible silence if that header is not read. The
   * seeded fixtures are continuous sine waves, so a genuine signal never produces a run longer
   * than a sample or two.
   *
   * A non-positive [channelCount] is rejected — by [frameCount]'s own `require`, reached below
   * before a single frame is examined, and not by a second copy of that guard here. This function
   * did carry its own, and a mutation sweep proved no test could tell the two apart: deleting it
   * left the identical `IllegalArgumentException`, naming the identical argument, on the identical
   * input. A guard whose removal changes nothing observable is not defence in depth; it is the
   * "assertion that executes but cannot fail" defect one layer down. What is worth having — and is
   * kept — is the *test*, which reaches [frameCount]'s guard through this door rather than through
   * [frameCount] directly, so deleting that guard now reddens two tests instead of one.
   *
   * The byte order below is the input's real contract, not a lever this function pulls: a 16-bit
   * sample is zero exactly when both of its bytes are zero, so silence is invariant under byte
   * order and `BIG_ENDIAN` here is a provably equivalent mutant. Do not go looking for the test
   * that pins it; there cannot be one while the only question asked of a sample is "is it zero".
   */
  fun longestZeroRunFrames(pcm: ByteArray, channelCount: Int): Int {
    val frames = frameCount(pcm.size, channelCount)
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    var longest = 0
    var current = 0
    for (frame in 0 until frames) {
      var silent = true
      for (channel in 0 until channelCount) {
        if (buffer.get(frame * channelCount + channel).toInt() != 0) {
          silent = false
          break
        }
      }
      if (silent) {
        current++
        // Updated inside the run rather than when it ends: encoder padding sits at the very end of
        // a stream, so a scan that only closed a run on the next non-zero sample would miss the
        // exact case this function was written for. (`>=` in its place would be a provably
        // equivalent mutant -- it assigns `longest` a value it already holds -- so no probe pins
        // this operator; `pcm/zero-run-closes-late` pins its *position* inside the branch, which
        // is the part that can actually be got wrong.)
        if (current > longest) longest = current
      } else {
        current = 0
      }
    }
    return longest
  }

  /**
   * [frames] at [sampleRateHz], in milliseconds, truncated toward zero.
   *
   * Both arguments are guarded, and for the same reason rather than out of habit. The device tier
   * spends the result of this function inside `isLessThan(10L)` — an assertion that a *negative*
   * number satisfies just as happily as a small positive one. So a negative [frames] (a caller's
   * subtraction that came out the wrong way round, say) must not be expressible as a duration; it
   * has to be loud. A zero [sampleRateHz] would be an `ArithmeticException` from the division
   * rather than a wrong answer, but it is named here so the failure says which argument was wrong.
   */
  fun framesToMs(frames: Int, sampleRateHz: Int): Long {
    require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
    require(frames >= 0) { "frames must not be negative, was $frames" }
    return frames.toLong() * MILLIS_PER_SECOND / sampleRateHz
  }
}
