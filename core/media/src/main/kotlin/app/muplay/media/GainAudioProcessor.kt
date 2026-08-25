package app.muplay.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The gain stage spec section 4 asks for: *"ReplayGain is exposed but not applied server-side; the
 * client applies it."*
 *
 * One multiply per sample, sitting in the audio processor chain upstream of the `AudioTrack` -- the
 * same place Task 7's `TeeAudioProcessor` sits, which is why `GainAudioProcessorTest` can measure
 * this at all and why the measurement works on the `-no-audio` CI emulator.
 *
 * ### [isActive] does not consult the gain, and that is deliberate
 *
 * A processor's activity is decided once, when the chain is **configured**, and the gain changes
 * per *item* long after that. A stage that deactivated itself for a track with no tag would be
 * absent from the chain when the next track needed it -- and a queue whose first track is untagged
 * is the ordinary case in a shuffled library, so that defect would apply the gain to almost nothing
 * while every per-item unit test stayed green. `theGainFollowsTheItemAcrossATransition` is the test
 * that refuses it; this paragraph is not the guard, that test is.
 *
 * The inherited [isActive] is what gives that: `BaseAudioProcessor` reports active from the moment
 * [onConfigure] has answered with a format, which for this class is unconditional. Read off the
 * 1.11.0 bytecode (`pendingOutputAudioFormat != AudioFormat.NOT_SET`), not assumed -- and left
 * un-overridden rather than restated as `= super.isActive()`, because lint's `MissingSuperCall`
 * makes any override of it call the same method anyway. **Do not add an override here.**
 *
 * The cost of staying in the chain is paid back by [queueInput]'s fast path, which copies rather
 * than multiplies when the gain is exactly [ReplayGainPolicy.UNCHANGED].
 *
 * **That fast path is a performance property and nothing else, and no test here gates it.** An
 * earlier version of this note claimed `anUntaggedTrackIsBitIdenticalWithAndWithoutTheGainStage`
 * asserted it; the falsification says otherwise. Deleting the branch -- multiplying every sample by
 * 1.0f instead -- left all 13 tests in `GainAudioProcessorTest` **green**, because
 * `(sample * 1.0f).toInt()` is exact for every value a `Short` can hold, so the two paths agree bit
 * for bit by construction. The bit-identity tests are still worth their keep: they gate the *stage*
 * (a dropped sample, a wrong byte order, an off-by-one in the loop) rather than the branch. If this
 * branch is ever removed as dead weight, nothing will go red, and that is the honest state of it.
 *
 * ### 16-bit PCM only, refused loudly
 *
 * Anything else throws [AudioProcessor.UnhandledAudioFormatException] rather than passing through
 * unchanged, because "the gain silently did not apply" is the failure this whole task exists to
 * remove. Media3's own `ToInt16PcmAudioProcessor` runs ahead of this one in
 * `DefaultAudioProcessorChain`, so on this project's decoders the other arm is not reached; it is
 * still the right refusal, and it is why this class never has to ask what encoding it was handed.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `BaseAudioProcessor`, `AudioProcessor.AudioFormat` and `AudioProcessor.UnhandledAudioFormat-
// Exception` are all `@UnstableApi`, which the Kotlin compiler cannot see at all: without this the
// file compiles clean and `check` fails much later at `lintDebug`.
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {

  /**
   * Written from the player's application thread by [ReplayGainController], read on the playback
   * thread by [queueInput]. `@Volatile` rather than a lock: it is one 32-bit value, a torn read is
   * impossible for it, and the only ordering this needs is that a write becomes visible -- a gain
   * that arrives one buffer late is the boundary window `GainAudioProcessorTest` measures around
   * and excludes, not a correctness problem.
   */
  @Volatile private var linearGain: Float = ReplayGainPolicy.UNCHANGED

  fun setLinearGain(gain: Float) {
    linearGain = gain
  }

  override fun onConfigure(
    inputAudioFormat: AudioProcessor.AudioFormat,
  ): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
    else throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)

  override fun queueInput(inputBuffer: ByteBuffer) {
    val remaining = inputBuffer.remaining()
    // An empty input is a **drain**, not a buffer of zero samples, and it must produce no output
    // rather than an empty one. Media3's own `TeeAudioProcessor` guards identically; this class
    // learned why the hard way, on `muplay37`, and the reason is worth writing down because it is
    // invisible from the source: `AudioProcessingPipeline.getOutput()` drives the chain with
    // `AudioProcessor.EMPTY_BUFFER`, and `BaseAudioProcessor`'s own output buffer *starts life as
    // that same shared instance*, so `replaceOutputBuffer(0)` hands back the very buffer that was
    // passed in. The copy below is then `EMPTY_BUFFER.put(EMPTY_BUFFER)`:
    //
    //   java.lang.IllegalArgumentException: The source buffer is this buffer
    //     at app.muplay.media.GainAudioProcessor.queueInput
    //     at androidx.media3.common.audio.AudioProcessingPipeline.processData
    //
    // -- which arrives as an `ExoPlaybackException: Unexpected runtime error` on the first track
    // the player ever renders, so it is not a rare edge at all. `anEmptyDrainProducesNoOutput`
    // is the test.
    if (remaining == 0) return

    val limit = inputBuffer.limit()
    val output = replaceOutputBuffer(remaining)
    val gain = linearGain

    if (gain == ReplayGainPolicy.UNCHANGED) {
      output.put(inputBuffer)
    } else {
      var position = inputBuffer.position()
      while (position < limit) {
        // Clamped at the `Short` bounds rather than allowed to wrap: an overflow that wraps turns
        // a loud sample into a loud sample of the opposite sign, which is a click, not clipping.
        val scaled = (inputBuffer.getShort(position) * gain).toInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output.putShort(scaled.toShort())
        position += Short.SIZE_BYTES
      }
      inputBuffer.position(limit)
    }
    output.flip()
  }
}
