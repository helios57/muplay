package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Metadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec section 11 lists *"Video"* as a non-goal. Until Task 11 that was a statement of intent:
 * `DefaultRenderersFactory` builds a `MediaCodecVideoRenderer` for every player it makes, so a
 * media source offering a video track would have found one waiting. [MuPlayRenderersFactory] adds
 * none, and this is where that becomes a measured property of the array rather than a sentence.
 *
 * Both halves are needed. "No video renderer" on its own is satisfied by a factory that builds
 * **nothing at all**, which would also play no audio -- so the audio renderer is asserted present,
 * and `DefaultRenderersFactory`'s own array is measured alongside as the positive control that the
 * scan can see a video renderer when there is one.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayRenderersFactoryTest {

  @Test
  fun theShippingRendererSetCarriesNoVideoRendererAndDoesCarryAnAudioOne() {
    val context: Context = ApplicationProvider.getApplicationContext()

    val shipping = trackTypesOf(MuPlayRenderersFactory(context, GainAudioProcessor()))
    val media3Default = trackTypesOf(DefaultRenderersFactory(context))

    assertThat(shipping).describedAs("MuPlayRenderersFactory track types")
      .doesNotContain(C.TRACK_TYPE_VIDEO)
      .contains(C.TRACK_TYPE_AUDIO)
    // The positive control: the same scan, over Media3's own factory, does find a video renderer.
    // Without it, a scan that read track types wrongly would report "no video" for everything.
    assertThat(media3Default).describedAs("DefaultRenderersFactory track types")
      .contains(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
  }

  @OptIn(UnstableApi::class)
  private fun trackTypesOf(factory: DefaultRenderersFactory): List<Int> {
    lateinit var renderers: Array<Renderer>
    // On the main thread: a `Renderer` binds to nothing here, but `DefaultRenderersFactory` reads
    // the handler's looper while it builds, and the handler below has to belong to a live one.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      renderers = factory.createRenderers(
        Handler(Looper.getMainLooper()),
        NoOpVideoListener,
        NoOpAudioListener,
        NoOpTextOutput,
        NoOpMetadataOutput,
      )
    }
    return renderers.map { it.trackType }
  }

  private object NoOpVideoListener : VideoRendererEventListener
  private object NoOpAudioListener : AudioRendererEventListener
  private object NoOpTextOutput : TextOutput {
    override fun onCues(cueGroup: CueGroup) = Unit
  }
  private object NoOpMetadataOutput : MetadataOutput {
    override fun onMetadata(metadata: Metadata) = Unit
  }
}
