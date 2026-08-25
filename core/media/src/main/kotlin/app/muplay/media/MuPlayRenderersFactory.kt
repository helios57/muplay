package app.muplay.media

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The shipping renderer set: **audio only**, with [GainAudioProcessor] in its processor chain.
 *
 * Two production properties live here, and neither was true before this task.
 *
 * ### The gain stage is in the chain that ships
 *
 * Supplying a custom `AudioSink` is the only supported way to insert a processor into the chain --
 * Media3 offers no setter, no listener and nothing at all after construction -- and until this task
 * the production player used a plain `DefaultRenderersFactory` with no chain of its own. Task 7
 * built one, but only inside a test. So the choice of where the gain applies is a *construction*
 * decision, which is why it is a `RenderersFactory` and why [MuPlayerFactory] owns building it.
 *
 * A subclass of [DefaultRenderersFactory] rather than a hand-rolled `RenderersFactory`, for the
 * same reason `TappedRenderersFactory` gives on the test side: the single [buildAudioSink] override
 * replaces the sink's *construction* and adds processors to its chain, and both flags go to the
 * same two setters `DefaultRenderersFactory` itself calls. Everything else about the audio path is
 * the one Media3 ships.
 *
 * ### There is no video renderer to construct
 *
 * Spec section 11 lists *"Video"* as a non-goal, and until this task that was a statement about
 * intent: `DefaultRenderersFactory` builds a `MediaCodecVideoRenderer` for every player, and a
 * media source that offered a video track would have found one waiting. [buildVideoRenderers] adds
 * nothing here, so the non-goal is a property of the renderer array rather than of a document.
 * `MuPlayRenderersFactoryTest.theShippingRendererSetCarriesNoVideoRenderer` measures the array.
 *
 * [extraProcessors] exist for the tier that has to *observe* the samples: `GaplessTest` and
 * `GainAudioProcessorTest` pass a `TeeAudioProcessor` and read what the sink would have received.
 * They are appended **after** the gain stage, so a capture sees the audio with the gain already
 * applied -- which is the whole measurement. Production passes none.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`, and on the class rather than propagated as an
// `@UnstableApi` of our own -- the same argument `MuPlayerFactory` records, for the same reason.
// `DefaultRenderersFactory`, `DefaultAudioSink`, `AudioProcessor` and `MediaCodecSelector` are all
// annotated, and the Kotlin compiler cannot see any of it.
@OptIn(UnstableApi::class)
class MuPlayRenderersFactory(
  context: Context,
  private val gainProcessor: GainAudioProcessor,
  private val extraProcessors: List<AudioProcessor> = emptyList(),
) : DefaultRenderersFactory(context) {

  override fun buildAudioSink(
    context: Context,
    enableFloatOutput: Boolean,
    enableAudioTrackPlaybackParams: Boolean,
  ): AudioSink =
    DefaultAudioSink.Builder(context)
      .setEnableFloatOutput(enableFloatOutput)
      // `setEnableAudioOutputPlaybackParameters`, not the deprecated
      // `setEnableAudioTrackPlaybackParams` the overridden parameter is still named after -- read
      // off the 1.11.0 bytecode, the same way `TappedRenderersFactory` did.
      .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
      // `DefaultAudioProcessorChain` appends Media3's own silence-skipping and Sonic processors
      // *after* the ones handed to it, so the gain applies to the decoder's output before either
      // of them, and an `extraProcessors` tee sees the gained audio rather than the raw one.
      .setAudioProcessorChain(
        DefaultAudioSink.DefaultAudioProcessorChain(
          *(listOf(gainProcessor) + extraProcessors).toTypedArray(),
        ),
      )
      .build()

  /**
   * No video renderer, on purpose. See this class's own note: spec section 11's *"Video"* non-goal
   * becomes a property of the array rather than a statement of intent.
   */
  override fun buildVideoRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    allowedVideoJoiningTimeMs: Long,
    out: ArrayList<Renderer>,
  ) = Unit
}
