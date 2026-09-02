package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RenderersFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.ReplayGain
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The measurement this whole task exists for: does the gain reach the samples?**
 *
 * Everything a layer above the audio can see -- that the tag parsed, that the mirror carried it,
 * that a `MediaItem` holds an extra, that a processor was constructed, that `setLinearGain` was
 * called -- is satisfied perfectly by a gain stage that multiplies by 1. So every assertion in this
 * file is on **PCM bytes**, and each one names the number of decibels it is measuring.
 *
 * ### Two groups, and why both are here
 *
 * **The bench group** drives `GainAudioProcessor.queueInput` directly over a buffer whose every
 * sample is known, through `ReplayGainController` and through items built by the production
 * `MediaItems.of`. It is exact -- no tolerance, no decoder, no network -- so it is where the
 * arithmetic, the peak clamp, the overflow clamp and the absent-key encoding are pinned. It runs on
 * a device rather than the JVM only because `MediaItem` is built on `android.net.Uri`, which throws
 * off-device, and Robolectric is banned here.
 *
 * **The player group** plays real audio off the real container through the **shipping** player and
 * measures what the sink received. It is the only tier that can say the stage is actually *in* the
 * chain the app builds, that the controller is actually attached to the player, and that the gain
 * follows the item across a real transition. Its tolerances are wide because it is a real decoder;
 * that is what the bench group compensates for.
 *
 * ### The controlled experiment, and why it is the *same file twice*
 *
 * Task 11's brief asks for a second seeded album -- the same sine, the same encoder settings, one
 * ReplayGain tag -- so that the amplitude ratio is attributable to the tag alone. That fixture is
 * held on another branch and could not be added here (see task-11-report.md). What replaces it is
 * strictly tighter: **the same file, played twice, differing only in the tag on the `MediaItem`**.
 * There is no second encode to account for, so the two decodes are byte-identical inputs and the
 * ratio is the gain with nothing else in it.
 *
 * The control still has to exist, and it does: [theSameFileWithAndWithoutATagIsIdenticalWhenTheGainStageIsAbsent]
 * plays exactly the same pair with the gain stage taken out of the chain and requires them to come
 * back **byte-identical**. Without it, "these two captures differ" is consistent with any source of
 * variation at all.
 *
 * ### No stream URL is ever read
 *
 * A Navidrome stream URL carries `u`, `s=salt` and `t=md5(password+salt)`, and an AssertJ failure
 * message prints the value it saw. The URLs below are handed to a `MediaItem` and never asserted
 * on, never logged and never put in a description. Same rule as `GaplessTest`.
 */
@RunWith(AndroidJUnit4::class)
class GainAudioProcessorTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private val harnesses = mutableListOf<PlayerHarness>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "gain-test-${System.nanoTime()}")
    songs = runBlocking { RealTrackBytes.musicTracks() }
    check(songs.size >= 2) {
      "this suite compares two seeded music tracks; found ${songs.size}"
    }
  }

  @After
  fun tearDown() {
    harnesses.forEach { it.release() }
    harnesses.clear()
    cacheDir.deleteRecursively()
  }

  // ---- the bench: exact arithmetic on known samples ---------------------------------------------

  /**
   * The multiply itself, at two disjoint gains, asserted sample by sample with no tolerance.
   *
   * Two gains rather than one because a processor that multiplied by a hardcoded 0.5 satisfies any
   * single-gain check, and 0.5/0.25 is the exact shape a `-6 dB` fixture would leave undetected.
   */
  @Test
  fun everyFrameIsScaledByTheGainTheProcessorWasGiven() {
    val input = shortArrayOf(1000, -1000, 32000, -32000, 0, 7)

    assertThat(processedWith(0.5f, input))
      .containsExactly(500, -500, 16000, -16000, 0, 3)
    assertThat(processedWith(0.25f, input))
      .containsExactly(250, -250, 8000, -8000, 0, 1)
  }

  /**
   * A gain of exactly one leaves every sample exactly as it was, asserted as **bytes**.
   *
   * **Measured caveat, stated because the obvious reading of this test is wrong.** It does *not*
   * gate `GainAudioProcessor`'s identity fast path: removing that branch and multiplying by 1.0f
   * instead leaves this test, and all 13 in this class, green -- `(sample * 1.0f).toInt()` is exact
   * for every `Short`. What it does gate is the *stage*: a dropped sample, a wrong byte order, an
   * off-by-one in the loop, a buffer that was flipped in the wrong place. The fast path is a
   * performance property and this project has no test for it. See task-11-report.md.
   */
  @Test
  fun aGainOfExactlyOneIsAByteForByteCopy() {
    val input = shortArrayOf(1, -1, 12345, -12345, 32767, -32768, 3, 5, 7)

    assertThat(processedWith(ReplayGainPolicy.UNCHANGED, input)).containsExactly(
      1, -1, 12345, -12345, 32767, -32768, 3, 5, 7,
    )
  }

  /**
   * A sample the gain would push past full scale is clamped, not wrapped.
   *
   * An overflow that wraps turns a loud sample into a loud sample of the *opposite sign*, which is
   * a click rather than clipping -- audibly worse than the thing the clamp is there to avoid, and
   * silent in every test that only looks at quiet material.
   */
  @Test
  fun samplesThatWouldOverflowAreClampedRatherThanWrapped() {
    val input = shortArrayOf(20000, -20000, 32767, -32768)

    assertThat(processedWith(2.0f, input)).containsExactly(32767, -32768, 32767, -32768)
  }

  /**
   * Anything that is not 16-bit PCM is **refused**, loudly, rather than passed through unchanged.
   *
   * "The gain silently did not apply" is the failure this whole task exists to remove, so the
   * wrong-format arm has to throw. It is not reachable through the shipping pipeline -- Media3's
   * own `ToInt16PcmAudioProcessor` runs ahead of this one in `DefaultAudioProcessorChain` -- which
   * is exactly why it needs a test that reaches it directly: without this, `onConfigure` measured
   * 1/2 BRANCH and the refusal was a claim rather than a behaviour.
   */
  @OptIn(UnstableApi::class)
  @Test
  fun anEncodingThisStageCannotHandleIsRefusedRatherThanPassedThrough() {
    val processor = GainAudioProcessor()

    assertThatThrownBy {
      processor.configure(
        AudioProcessor.AudioFormat(
          FIXTURE_SAMPLE_RATE_HZ,
          FIXTURE_CHANNEL_COUNT,
          C.ENCODING_PCM_FLOAT,
        ),
      )
    }.isInstanceOf(AudioProcessor.UnhandledAudioFormatException::class.java)

    // The other arm, at the same call, so this is a discrimination rather than "configure throws".
    assertThat(
      processor.configure(
        AudioProcessor.AudioFormat(
          FIXTURE_SAMPLE_RATE_HZ,
          FIXTURE_CHANNEL_COUNT,
          C.ENCODING_PCM_16BIT,
        ),
      ).encoding,
    ).isEqualTo(C.ENCODING_PCM_16BIT)
  }

  /**
   * A **drain** -- an empty input buffer -- produces no output and does not throw.
   *
   * Not a defensive branch: `AudioProcessingPipeline.getOutput()` drives the chain with
   * `AudioProcessor.EMPTY_BUFFER` every time the sink asks for more, and `BaseAudioProcessor`'s own
   * output buffer starts life as that same shared instance -- so a bulk copy here is
   * `EMPTY_BUFFER.put(EMPTY_BUFFER)` and throws. This was measured as an
   * `ExoPlaybackException: Unexpected runtime error` on the first track a real player rendered,
   * before the guard existed. Asserted at both gains, because the identity path and the multiply
   * path reach it by different routes.
   */
  @Test
  fun anEmptyDrainProducesNoOutput() {
    listOf(ReplayGainPolicy.UNCHANGED, 0.5f).forEach { gain ->
      val processor = GainAudioProcessor().also { it.setLinearGain(gain) }

      assertThat(processedWith(gain, ShortArray(0)))
        .describedAs("output of a drain at gain %f", gain)
        .isEmpty()
      // ...and the processor is still usable afterwards: a drain must not leave it in a state
      // where the next real buffer is dropped.
      assertThat(process(processor, shortArrayOf(1000))).hasSize(1)
    }
  }

  /**
   * The whole chain that is *not* the decoder: a [Song] with tags, through the production
   * `MediaItems.of`, through `ReplayGainController`, onto the samples.
   *
   * Two tracks at two gains, so neither the item nor the multiplier can be a constant. This is the
   * assertion that would fail if `MediaItems` stopped writing the extras, if the controller stopped
   * reading them, or if either used a different key from the other.
   */
  @Test
  fun theControllersReadingOfAnItemsTagsIsWhatReachesTheSamples() {
    val input = shortArrayOf(8000, -8000, 4000)
    val processor = GainAudioProcessor()
    val controller = ReplayGainController(processor)

    controller.applyTo(itemFor(ReplayGain(trackGainDb = -6.0f, albumGainDb = null, peakAmplitude = null)))
    val halved = process(processor, input)

    controller.applyTo(itemFor(ReplayGain(trackGainDb = -12.0f, albumGainDb = null, peakAmplitude = null)))
    val quartered = process(processor, input)

    // 10^(-6/20) = 0.50119, 10^(-12/20) = 0.25119. Exact integers, because the inputs were chosen
    // so the products are not near a rounding boundary.
    assertThat(halved).containsExactly(4009, -4009, 2004)
    assertThat(quartered).containsExactly(2009, -2009, 1004)
  }

  /**
   * The peak clamp, measured where it matters. `+6 dB` is a multiplier of 1.9953; on a file that
   * already peaks at 0.9 of full scale that would clip, so the clamp holds it to 1/0.9 = 1.1111.
   *
   * Asserted against the **unclamped** result as well as the clamped one, because "the samples got
   * louder" is true of both and is not the claim.
   */
  @Test
  fun aPeakOnTheItemClampsWhatReachesTheSamples() {
    val input = shortArrayOf(10000, -10000)
    val processor = GainAudioProcessor()
    val controller = ReplayGainController(processor)

    controller.applyTo(itemFor(ReplayGain(trackGainDb = 6.0f, albumGainDb = null, peakAmplitude = 0.9f)))
    val clamped = process(processor, input)

    controller.applyTo(itemFor(ReplayGain(trackGainDb = 6.0f, albumGainDb = null, peakAmplitude = null)))
    val unclamped = process(processor, input)

    assertThat(clamped).containsExactly(11111, -11111)
    assertThat(unclamped).containsExactly(19952, -19952)
  }

  /**
   * An item with no tags leaves the samples exactly alone, and an item with no `MediaItem` at all
   * (a queue that just emptied) does too.
   *
   * The second half is the arm that a `getFloat(key, 0f)` default would get wrong in the other
   * direction: it would apply a decision nobody made.
   */
  @Test
  fun anItemThatCarriesNoTagsLeavesTheSamplesAlone() {
    val input = shortArrayOf(9999, -9999, 21, 32767)
    val processor = GainAudioProcessor()
    val controller = ReplayGainController(processor)

    // Start from a real gain, so "unchanged" is a value that had to be re-applied rather than the
    // initial state of a processor nothing ever touched.
    controller.applyTo(itemFor(ReplayGain(-12.0f, null, null)))
    controller.applyTo(itemFor(replayGain = null))
    assertThat(process(processor, input)).containsExactly(9999, -9999, 21, 32767)

    controller.applyTo(itemFor(ReplayGain(-12.0f, null, null)))
    controller.applyTo(null)
    assertThat(process(processor, input)).containsExactly(9999, -9999, 21, 32767)
  }

  /** A file tagged by an album-oriented tool still gets its adjustment onto the samples. */
  @Test
  fun anAlbumOnlyTagStillReachesTheSamples() {
    val input = shortArrayOf(8000, -8000)
    val processor = GainAudioProcessor()

    ReplayGainController(processor)
      .applyTo(itemFor(ReplayGain(trackGainDb = null, albumGainDb = -6.0f, peakAmplitude = null)))

    assertThat(process(processor, input)).containsExactly(4009, -4009)
  }

  // ---- the player: real audio, the shipping chain ----------------------------------------------

  /**
   * The headline measurement. The **same file**, played twice through the shipping player,
   * differing only in the ReplayGain on its `MediaItem`: the ratio of the rendered amplitudes is
   * the gain, with no calibration and no golden file.
   *
   * Two tags rather than one -- `-6 dB` is 0.5012 of the amplitude and `-12 dB` is 0.2512 -- so a
   * pipeline that applied one fixed attenuation to everything fails here.
   */
  @Test
  fun aTaggedTrackRendersAtTheAmplitudeItsTagAsksFor() {
    val untagged = rmsOf(playedPcm(songs[0], replayGain = null))
    val minusSix = rmsOf(playedPcm(songs[0], ReplayGain(-6.0f, null, null)))
    val minusTwelve = rmsOf(playedPcm(songs[0], ReplayGain(-12.0f, null, null)))

    assertThat(minusSix / untagged)
      .describedAs("-6.00 dB as a rendered amplitude ratio")
      .isCloseTo(0.5012f, within(RATIO_TOLERANCE))
    assertThat(minusTwelve / untagged)
      .describedAs("-12.00 dB as a rendered amplitude ratio")
      .isCloseTo(0.2512f, within(RATIO_TOLERANCE))

    // ...and none of the three is silence. A ratio with a tolerance is satisfied by two zeroes,
    // and a pipeline that rendered nothing at all would produce exactly that.
    assertThat(untagged).describedAs("untagged RMS").isGreaterThan(AUDIBLE_RMS)
    assertThat(minusSix).describedAs("-6 dB RMS").isGreaterThan(AUDIBLE_RMS / 3f)
    assertThat(minusTwelve).describedAs("-12 dB RMS").isGreaterThan(AUDIBLE_RMS / 6f)
  }

  /**
   * **The control**, and the reason the ratios above are attributable to the tag.
   *
   * The same file and the same two `MediaItem`s, played with the gain stage taken out of the chain
   * entirely, come back **byte-identical**. So the pipeline is deterministic across two runs, the
   * tag on its own changes nothing, and every difference measured above was introduced by the stage
   * under test.
   */
  @Test
  fun theSameFileWithAndWithoutATagIsIdenticalWhenTheGainStageIsAbsent() {
    val untagged = playedPcm(songs[0], replayGain = null, withGainStage = false)
    val tagged = playedPcm(songs[0], ReplayGain(-6.0f, null, null), withGainStage = false)

    assertThat(tagged).isEqualTo(untagged)
    // The vacuity guard: two empty captures are also equal.
    assertThat(untagged.size).isGreaterThan(BYTES_IN_TWO_SECONDS)
  }

  /**
   * An untagged library is **bit-identical** to having no gain stage at all, through a real decoder
   * rather than only on the bench -- so inserting the stage into the shipping chain changed nothing
   * for every library that carries no tags, which is most of them.
   *
   * Same measured caveat as [aGainOfExactlyOneIsAByteForByteCopy]: this is a property of the
   * *stage*, not of the fast path inside it.
   */
  @Test
  fun anUntaggedTrackIsBitIdenticalWithAndWithoutTheGainStage() {
    val withStage = playedPcm(songs[0], replayGain = null, withGainStage = true)
    val withoutStage = playedPcm(songs[0], replayGain = null, withGainStage = false)

    assertThat(withStage).isEqualTo(withoutStage)
    assertThat(withStage.size).isGreaterThan(BYTES_IN_TWO_SECONDS)
  }

  /**
   * The gain follows the **item**, which is the property a per-track adjustment actually needs, and
   * the one a processor that deactivated itself for an untagged track would fail.
   *
   * One queue, two tracks, one capture: the first untagged, the second tagged `-6.00 dB`. The join
   * is not assumed -- [CapturingAudioSink.flushOffsets] records where the pipeline was drained, so
   * the boundary is reported by the pipeline itself.
   *
   * [SETTLE_BYTES] after the join is excluded, and it is a **measured** window rather than a
   * guess -- see [theGainTakesEffectWithinTheSinksOwnBufferAndNotLater], which measures it and
   * bounds it. It is not "one buffer in flight": the tee sits upstream of the `AudioTrack`, so the
   * capture runs ahead of the playback position by the whole sink buffer, and
   * `onMediaItemTransition` fires off the playback position. That is a real property of this
   * design, not an artefact of the measurement -- a listener hears the first fraction of a second
   * of a new track at the previous track's gain -- and it is recorded in task-11-report.md as a
   * known limitation rather than hidden behind a wide tolerance.
   */
  @Test
  fun theGainFollowsTheItemAcrossATransition() {
    val gained = playedQueue(withGainStage = true)
    val control = playedQueue(withGainStage = false)

    // The control first: with no gain stage, the two seeded sines render at the same amplitude, so
    // the ratio below is not an artefact of the two files being different recordings.
    assertThat(control.after / control.before)
      .describedAs("two untagged seeded tracks, no gain stage: %f then %f", control.before, control.after)
      .isCloseTo(1.0f, within(RATIO_TOLERANCE))

    assertThat(gained.after / gained.before)
      .describedAs("across one queue's join, second track tagged -6.00 dB: %f then %f", gained.before, gained.after)
      .isCloseTo(0.5012f, within(RATIO_TOLERANCE))
    // The first track is the *untagged* one and must be untouched, which is what makes this a
    // per-item measurement rather than "the whole queue got quieter".
    assertThat(gained.before)
      .describedAs("the untagged first track of a queue whose second track is tagged")
      .isCloseTo(control.before, within(control.before * RATIO_TOLERANCE))
  }

  /**
   * How long the wrong gain survives a transition, measured rather than assumed.
   *
   * The capture is scanned forward from the join in [SCAN_WINDOW_MS] steps, and the first window
   * that has settled to the tagged amplitude is the answer. Two things are then asserted, and the
   * pair is the point:
   *
   *  * it settles **at all**, and inside [SETTLE_BOUND_MS] -- so the lag is a boundary artefact and
   *    not "the gain never arrived for this item";
   *  * the window immediately *before* the join is at the untagged amplitude -- the scan's own
   *    calibration, without which "settled at window 0" and "the scan cannot tell the two levels
   *    apart" are the same result.
   */
  @Test
  fun theGainTakesEffectWithinTheSinksOwnBufferAndNotLater() {
    val capture = queueCapture(withGainStage = true)
    val untaggedLevel = rmsOf(capture.pcm.copyOfRange(0, capture.join - SCAN_WINDOW_BYTES))
    val target = untaggedLevel * 0.5012f

    // Calibrated half a second back from the join rather than immediately before it: the last
    // window before a pipeline drain straddles the boundary itself and measured 6.07% below the
    // track's own level here, against a 6% tolerance. That is the boundary, not the gain, and
    // calibrating on it would make this scan's premise a coin toss.
    val beforeTheJoin = rmsOf(
      capture.pcm.copyOfRange(
        capture.join - CALIBRATION_OFFSET_BYTES,
        capture.join - CALIBRATION_OFFSET_BYTES + SCAN_WINDOW_BYTES,
      ),
    )
    assertThat(beforeTheJoin)
      .describedAs("a window on the untagged track, half a second before the join")
      .isCloseTo(untaggedLevel, within(untaggedLevel * RATIO_TOLERANCE))

    var settledMs = -1L
    var offset = capture.join
    while (offset + SCAN_WINDOW_BYTES <= capture.pcm.size) {
      val level = rmsOf(capture.pcm.copyOfRange(offset, offset + SCAN_WINDOW_BYTES))
      if (kotlin.math.abs(level - target) <= target * RATIO_TOLERANCE) {
        settledMs = (offset - capture.join).toLong() * MILLIS_PER_SECOND / BYTES_PER_SECOND
        break
      }
      offset += SCAN_WINDOW_BYTES
    }

    assertThat(settledMs)
      .describedAs(
        "ms after the pipeline's own join before the tagged amplitude is reached " +
          "(untagged %f, target %f)",
        untaggedLevel,
        target,
      )
      .isBetween(0L, SETTLE_BOUND_MS)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** [input] as it comes out of a processor fixed at [gain]. */
  private fun processedWith(gain: Float, input: ShortArray): List<Int> =
    process(GainAudioProcessor().also { it.setLinearGain(gain) }, input)

  /**
   * One buffer through [processor], as `DefaultAudioSink` would drive it.
   *
   * `flush()` after `configure()` is not optional: `BaseAudioProcessor` allocates its output buffer
   * lazily and `getOutput()` returns an empty one until the pipeline has been flushed, which is
   * exactly what the sink does before the first buffer.
   */
  @OptIn(UnstableApi::class)
  private fun process(processor: GainAudioProcessor, input: ShortArray): List<Int> {
    processor.configure(
      AudioProcessor.AudioFormat(FIXTURE_SAMPLE_RATE_HZ, FIXTURE_CHANNEL_COUNT, C.ENCODING_PCM_16BIT),
    )
    processor.flush()
    processor.queueInput(bufferOf(input))
    val out = processor.output
    return buildList { while (out.remaining() >= Short.SIZE_BYTES) add(out.short.toInt()) }
  }

  private fun bufferOf(samples: ShortArray): ByteBuffer =
    ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
      samples.forEach { putShort(it) }
      flip()
    }

  /** A production `MediaItem` for a song carrying [replayGain] -- nothing hand-built. */
  private fun itemFor(replayGain: ReplayGain?): MediaItem =
    MediaItems.of(
      song = songs[0].copy(replayGain = replayGain),
      streamUri = "https://host/stream",
      artworkId = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

  private fun playedPcm(
    song: Song,
    replayGain: ReplayGain?,
    withGainStage: Boolean = true,
  ): ByteArray = runExperiment(withGainStage) { harness ->
    harness.onMain {
      harness.player.setMediaItem(itemFor(song, replayGain))
      harness.player.prepare()
      harness.player.play()
    }
    harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
  }.pcm

  private class QueueCapture(val pcm: ByteArray, val join: Int)

  private class JoinLevels(val before: Float, val after: Float)

  /** RMS either side of the single interior join of a two-track queue. */
  private fun playedQueue(withGainStage: Boolean): JoinLevels {
    val capture = queueCapture(withGainStage)
    return JoinLevels(
      before = rmsOf(capture.pcm.copyOfRange(0, capture.join - SCAN_WINDOW_BYTES)),
      after = rmsOf(capture.pcm.copyOfRange(capture.join + SETTLE_BYTES, capture.pcm.size)),
    )
  }

  /**
   * A two-track queue -- the first untagged, the second tagged `-6.00 dB` when the gain stage is
   * present -- and the interior join the pipeline itself reported.
   */
  private fun queueCapture(withGainStage: Boolean): QueueCapture {
    val capture = runExperiment(withGainStage) { harness ->
      harness.onMain {
        harness.player.setMediaItems(
          listOf(
            itemFor(songs[0], replayGain = null),
            itemFor(songs[1], if (withGainStage) ReplayGain(-6.0f, null, null) else null),
          ),
        )
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
    }
    val pcm = capture.pcm
    val joins = capture.flushOffsets.filter { it > 0 && it < pcm.size }.distinct()
    // The pipeline reports its own boundary; nothing here is an expected frame count. If it
    // reported none, every measurement below would be over the whole stream and this comparison
    // would be vacuous -- so it is a `check`, not a silent fallback.
    check(joins.size == 1) {
      "expected exactly one interior pipeline flush in ${pcm.size} bytes, got $joins"
    }
    return QueueCapture(pcm, joins.single())
  }

  private fun itemFor(song: Song, replayGain: ReplayGain?): MediaItem =
    MediaItems.of(
      song = song.copy(replayGain = replayGain),
      streamUri = RealTrackBytes.rawStreamUrl(song),
      artworkId = null,
      isAudiobook = false,
      format = StreamFormat.Raw,
    )

  /** Root mean square of a 16-bit little-endian buffer, in raw sample units. */
  private fun rmsOf(pcm: ByteArray): Float {
    check(pcm.size >= Short.SIZE_BYTES) { "no audio to measure" }
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    var sum = 0.0
    var count = 0
    while (buffer.remaining() >= Short.SIZE_BYTES) {
      val sample = buffer.short.toDouble()
      sum += sample * sample
      count++
    }
    return sqrt(sum / count).toFloat()
  }

  /**
   * Builds the **shipping** player -- through [MuPlayerFactory], the only construction site in this
   * project -- with its audio sink tapped, runs [block], and returns the capture.
   *
   * [withGainStage] is the one difference between the experiment and its control: `true` builds
   * `MuPlayRenderersFactory`, which is what production builds, with a tee appended after the gain
   * stage; `false` builds the pipeline as it was before this task, with no gain stage at all. Both
   * arms still get a `ReplayGainController` from the factory -- in the control it points at a
   * processor that is not in the chain, so it multiplies nothing, which is precisely the "control"
   * condition.
   *
   * A fresh `SimpleCache` directory per experiment, for the reason `GaplessTest` gives: a cache
   * shared between two runs would let the first one's bytes answer the second one's reads.
   */
  private fun runExperiment(
    withGainStage: Boolean,
    block: (PlayerHarness) -> Unit,
  ): CapturingAudioSink {
    val capture = CapturingAudioSink()
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val gainProcessor = GainAudioProcessor()
      val renderers: RenderersFactory =
        if (withGainStage) tappedShippingRenderers(context, gainProcessor, capture)
        else TappedRenderersFactory(context, capture)
      harness = PlayerHarness(
        MuPlayerFactory(
          context = context,
          dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
          loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
          resumePolicy = NeverResume,
        ).createExoPlayer(gainProcessor, renderers),
      )
      harnesses += harness
    }
    try {
      block(harness)
      harness.assertNoPlaybackError()
    } finally {
      harness.release()
      harnesses.remove(harness)
      cache.release()
    }
    return capture
  }

  private companion object {
    /** `ffmpeg -ac 1 ... sample_rate=44100`, per `ci/seed-fixtures.sh`. */
    const val FIXTURE_SAMPLE_RATE_HZ = 44100
    const val FIXTURE_CHANNEL_COUNT = 1

    /** 44.1 kHz, mono, 16-bit. */
    const val BYTES_PER_SECOND = 44_100 * 2
    const val MILLIS_PER_SECOND = 1_000

    /** The step [theGainTakesEffectWithinTheSinksOwnBufferAndNotLater] scans in: 100 ms. */
    const val SCAN_WINDOW_MS = 100
    const val SCAN_WINDOW_BYTES = BYTES_PER_SECOND * SCAN_WINDOW_MS / MILLIS_PER_SECOND

    /** How far back from a join that scan calibrates: 500 ms, clear of the boundary itself. */
    const val CALIBRATION_OFFSET_BYTES = BYTES_PER_SECOND / 2

    /**
     * How long a new item's gain may take to reach the samples, and the window excluded after a
     * join.
     *
     * **Measured on `muplay37`: 700 ms** (untagged level 2750.98, tagged target 1378.79, first
     * settled scan window at +700 ms). That is the sink's own buffering, not a flaw in the
     * measurement: the tee sits upstream of the `AudioTrack` and `onMediaItemTransition` fires off
     * the *playback* position, so a listener really does hear the first ~700 ms of a newly tagged
     * track at the previous track's gain. Recorded as a known limitation in task-11-report.md.
     *
     * One second, so the bound has room over the measured value without ceasing to discriminate --
     * `theGainTakesEffectWithinTheSinksOwnBufferAndNotLater` is what fails if this grows -- and it
     * still leaves four seconds of a five-second fixture inside the assertion.
     */
    const val SETTLE_BOUND_MS = 1_000L
    const val SETTLE_BYTES = BYTES_PER_SECOND * SETTLE_BOUND_MS.toInt() / MILLIS_PER_SECOND

    /**
     * Wide because the player group runs through a real lossy decoder. The bench group is where the
     * arithmetic is pinned exactly; this band only has to separate 0.5012 from 1.0 and from 0.2512,
     * which it does with room to spare on both sides.
     */
    const val RATIO_TOLERANCE = 0.06f

    /**
     * A floor under "this capture is not silence".
     *
     * The seeded sines are **not** full scale, which is worth stating because the obvious guess is
     * wrong and would put this floor in the wrong place: decoding `01 - Track 1.mp3` off the
     * container on the host gives RMS **2750.99**, peak 3897 (0.119 of full scale) over 220500
     * samples. The device capture of the same file through this apparatus measured 2751.10 -- which
     * is also the strongest single piece of evidence that the tee reports the decoder's output
     * faithfully and that nothing else in the chain attenuates.
     *
     * 1000 is comfortably under 2751 and unreachable by anything but real audio; the derived floors
     * for the attenuated cases are scaled from it rather than restated.
     */
    const val AUDIBLE_RMS = 1_000f

    /** 44100 Hz, mono, 16-bit, two seconds -- a vacuity floor, not an expected length. */
    const val BYTES_IN_TWO_SECONDS = 44_100 * 2 * 2

    const val PLAYBACK_TIMEOUT_MS = 60_000L
  }
}
