package app.muplay.media

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Captures every PCM frame the decoder produced, upstream of the `AudioTrack`.
 *
 * Upstream matters: the CI emulator boots with `-no-audio` (`.github/workflows/e2e.yml`), and this
 * measurement must not depend on a sound card existing. The `TeeAudioProcessor` this backs sits
 * inside the `DefaultAudioSink`'s processor chain, *after* Media3's own `TrimmingAudioProcessor` --
 * which is the processor that applies the encoder delay and padding read from an MP3's Xing/LAME
 * header. So what lands here is exactly the audio "gapless" is a claim about: the decoder's output
 * with the trimming already applied, or without it if the header was never read.
 *
 * ### [flushCount] counts pipeline resets, not sink configurations
 *
 * Read from the Media3 1.11.0 bytecode rather than from the class's own comment, because the two
 * disagree: `TeeAudioProcessor` calls [flush] from `onFlush`, from `onQueueEndOfStream` **and** from
 * `onReset`, each guarded by `isActive()`. So this counter moves when the processing pipeline is
 * flushed, when a stream ends, and when the processor is reset -- every one of which is a
 * discontinuity in the audio pipeline. That is the property the gapless comparison turns on: three
 * separate `prepare()` cycles tear the pipeline down and rebuild it between tracks, and one
 * `setMediaItems` queue of the same three tracks does not. The absolute numbers are Media3
 * implementation detail; the comparison is the claim.
 *
 * ### It reports what it was told, never what it assumes
 *
 * [sampleRateHz], [channelCount] and [encoding] are the format the pipeline announced, and
 * `GaplessTest` asserts on all three before it converts a single frame to milliseconds. A capture
 * that quietly assumed 44100/stereo/16-bit would turn every measurement below it into a number with
 * no unit.
 */
class CapturingAudioSink : TeeAudioProcessor.AudioBufferSink {

  private val captured = ByteArrayOutputStream()

  var flushCount: Int = 0
    private set
  var sampleRateHz: Int = 0
    private set
  var channelCount: Int = 0
    private set
  var encoding: Int = 0
    private set

  val pcm: ByteArray get() = captured.toByteArray()

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    flushCount++
    this.sampleRateHz = sampleRateHz
    this.channelCount = channelCount
    this.encoding = encoding
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    // `TeeAudioProcessor.queueInput` hands over `Util.createReadOnlyByteBuffer(inputBuffer)` -- a
    // separate buffer object over the same bytes -- and only then copies the original into its own
    // output buffer. Draining this one therefore cannot starve the chain downstream. Checked
    // against the 1.11.0 bytecode rather than assumed, because the opposite assumption is cheap to
    // hold and expensive to be wrong about: consuming a shared buffer here would silence playback
    // in a way that looks like a decoder fault.
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    captured.write(bytes)
  }
}

/**
 * The shipping renderer set, with its audio sink tapped by [capture].
 *
 * A subclass of [DefaultRenderersFactory] rather than a hand-rolled audio-only `RenderersFactory`,
 * and that is the whole point of it: `MuPlayerFactory.create()` with no argument builds
 * `DefaultRenderersFactory(context)`, so the renderers `GaplessTest` measures are produced by the
 * same code, in the same shape, as the ones that ship. The single override replaces the sink's
 * *construction* and adds one processor to its chain; both flags go to the same two setters
 * `DefaultRenderersFactory` itself calls, read off the 1.11.0 bytecode --
 * `setEnableAudioOutputPlaybackParameters`, not the deprecated `setEnableAudioTrackPlaybackParams`
 * the overridden parameter is still named after. Nothing else about the player changes.
 *
 * `DefaultAudioProcessorChain` appends Media3's own silence-skipping and Sonic processors *after*
 * the ones handed to it, so the tee sees the decoder's output before either of them could alter it.
 */
class TappedRenderersFactory(
  context: Context,
  private val capture: CapturingAudioSink,
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink =
    DefaultAudioSink.Builder(context)
      .setEnableFloatOutput(enableFloatOutput)
      .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
      .setAudioProcessorChain(
        DefaultAudioSink.DefaultAudioProcessorChain(TeeAudioProcessor(capture)),
      )
      .build()
}
