package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
 * ### [flushOffsets], and the count that was tried first and does not work
 *
 * `TeeAudioProcessor` calls [flush] from `onFlush`, from `onQueueEndOfStream` and from `onReset`
 * (read off the Media3 1.11.0 bytecode, each guarded by `isActive()`), so this callback fires
 * whenever the audio processing pipeline is drained -- which Media3 does **once per media period**,
 * gapless transition or not.
 *
 * That is why a bare flush *count* is not a gapless measurement, and this class no longer offers
 * one. Measured on `muplay37`: one `setMediaItems` queue of three tracks and three separate
 * `prepare()` cycles of the same three both produced **12** flushes. A count cannot tell a drain
 * that was followed seamlessly by the next track from one that tore the pipeline down.
 *
 * What can is *where* each drain fell in the audio. [flushOffsets] records the number of bytes
 * already captured at each one, so an entry strictly inside the capture marks a join between two
 * tracks -- the exact place a gap would be, reported by the pipeline itself rather than computed
 * from an expected duration. `GaplessTest` measures silence there.
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

  /** The number of bytes already captured at the moment of each [flush], in order. */
  val flushOffsets: List<Int> get() = flushes.toList()

  private val flushes = mutableListOf<Int>()

  var sampleRateHz: Int = 0
    private set
  var channelCount: Int = 0
    private set
  var encoding: Int = 0
    private set

  val pcm: ByteArray get() = captured.toByteArray()

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    flushes += captured.size()
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
 * The shipping renderer set with its audio sink tapped by [capture], for a suite that has to see
 * what the decoder produced.
 *
 * `MuPlayRenderersFactory` is what production builds, and this is that factory with one extra
 * processor appended **after** the gain stage -- so a capture sees the audio exactly as the
 * `AudioTrack` would have received it, gain included. That ordering is the whole measurement in
 * `GainAudioProcessorTest`, and it is why the tee is a parameter of the production factory rather
 * than a factory of its own.
 *
 * [gainProcessor] has to be the *same object* the player's `ReplayGainController` was given, which
 * is why the caller passes it in rather than this function inventing one: a controller pointed at a
 * processor that is not in the chain is the silent failure this task exists to remove. Pass the
 * result and the processor to `MuPlayerFactory.createExoPlayer(gainProcessor, renderersFactory)`.
 */
@OptIn(UnstableApi::class)
fun tappedShippingRenderers(
  context: Context,
  gainProcessor: GainAudioProcessor,
  capture: CapturingAudioSink,
): MuPlayRenderersFactory =
  MuPlayRenderersFactory(context, gainProcessor, listOf(TeeAudioProcessor(capture)))

/**
 * The audio pipeline **as it was before the gain stage existed**: `DefaultRenderersFactory` with
 * its sink tapped, and no `GainAudioProcessor` anywhere in the chain.
 *
 * It used to be what `GaplessTest` measured, on the argument that `MuPlayerFactory.create()` built
 * a plain `DefaultRenderersFactory` and so this was the shipping shape. Task 11 changed what ships,
 * and `GaplessTest` moved to [tappedShippingRenderers] with it -- measuring the pipeline that no
 * longer ships would be exactly the "verified at a different layer than it is applied" defect this
 * project keeps finding.
 *
 * What it is *for* now is the **control** in `GainAudioProcessorTest`: the same player, the same
 * items, the same capture, with the gain stage absent. A ratio between two amplitudes means nothing
 * without it -- two files that differ for some unrelated reason would produce one too.
 *
 * The single override replaces the sink's *construction*; both flags go to the same two setters
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
