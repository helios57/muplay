package app.muplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.SleepTimerRequest
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.util.concurrent.Executor
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The deliverable, measured on samples: the audio fades to silence, and it comes back.**
 *
 * Everything a layer above the audio can see -- that a state said `isFading`, that a getter reads
 * back the number the timer just wrote to it, that `isPlaying` went false -- is satisfied perfectly
 * by a timer that never touched the audio at all. So this class measures the **PCM a real decoder
 * produced**, off the real container, through the shipping player, against the **gain the player
 * actually applied** while producing it.
 *
 * ### Why the two are measured separately and multiplied here
 *
 * A sleep-timer fade cannot be captured post-gain from inside this process. Media3 forwards
 * `player.volume` to `DefaultAudioSink.setVolume`, which calls `AudioTrack.setVolume` -- the
 * platform mixer. Every tap an app can place (a `TeeAudioProcessor`, an `AudioProcessor`, a
 * `ForwardingAudioSink`) is *upstream* of that call, so a capture is byte-identical at volume 1.0
 * and at volume 0.0. `GainAudioProcessor` is measurable directly for the opposite reason: it is a
 * processor **in** that chain. See [TimedAudioCapture] for the full argument and for the one
 * approximation this makes.
 *
 * That approximation -- pairing a buffer with the gain in effect when the sink *received* it,
 * rather than when the `AudioTrack` *played* it -- is not taken on trust. Every assertion below
 * rests on the raw, pre-gain RMS being **flat**, which is asserted, and which is what makes the two
 * pairings agree on this fixture set.
 *
 * ### The control
 *
 * [theTimerLeavesTheAudioAloneOutsideTheFadeWindow] plays the same file with a timer running whose
 * fade window it never enters. Without it, "the amplitude fell" is consistent with any source of
 * variation at all -- and with a fade that is always on.
 *
 * No stream URL is ever asserted on, logged, or put in a description: they carry `u`, `s` and `t`.
 * Same rule as `GaplessTest` and `GainAudioProcessorTest`.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerFadeAudioTest {

  /** A real countdown needs a real clock; a fixed one never counts down. */
  private val clock: Clock = Clock.systemUTC()

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var book: Song
  private lateinit var scope: CoroutineScope
  private val harnesses = mutableListOf<PlayerHarness>()
  /**
   * Release actions, as lambdas rather than as a list of `Cache`: `androidx.media3.datasource
   * .cache.Cache` is `@UnstableApi`, and naming it on a property would push the opt-in from the one
   * function that builds a player onto this whole class.
   */
  private val cleanups = mutableListOf<() -> Unit>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "sleep-fade-${System.nanoTime()}")
    // The longest seeded book, looked up by the title `ci/seed-fixtures.sh` writes -- 21 s, so a
    // countdown of several seconds and the audio after it both fit inside one file. Nothing here
    // depends on how many books the corpus holds.
    book = runBlocking { RealTrackBytes.audiobookFiles() }.first { it.title == BOOK_TITLE }
    // Main-thread-confined, the same construction `MuPlaybackService` builds `serviceScope` from
    // and for the same reason: the ticker touches `player.volume` and `player.currentPosition`,
    // and `ExoPlayerImpl.verifyApplicationThread()` throws from anywhere else.
    scope = CoroutineScope(SupervisorJob() + MAIN_EXECUTOR.asCoroutineDispatcher())
  }

  @After
  fun tearDown() {
    scope.cancel()
    harnesses.forEach { it.release() }
    harnesses.clear()
    cleanups.forEach { it() }
    cleanups.clear()
    cacheDir.deleteRecursively()
  }

  /**
   * The whole claim, in one run: real audio at full amplitude, a linear ramp down to inaudibility,
   * a pause, and then real audio at full amplitude again.
   */
  @Test
  fun theAudibleAudioRampsDownToSilenceAndComesBackAtFullAmplitude() {
    val run = playTheBook()
    run.harness.awaitPositionAtLeast(SETTLE_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
    val subject = timer(run, fadeMs = FADE_MS)

    run.harness.onMain { subject.start(SleepTimerRequest.Duration(COUNTDOWN_MS)) }
    run.harness.await("the sleep timer to pause playback", timeoutMs = PLAYBACK_TIMEOUT_MS) {
      !run.harness.player.isPlaying
    }
    // ...and then the listener presses play again, which is the half of the deliverable a
    // `player.volume == 1.0f` assertion cannot reach.
    val resumedFrom = run.harness.onMain { run.harness.player.currentPosition }
    run.harness.onMain { run.harness.player.play() }
    run.harness.awaitPositionAtLeast(resumedFrom + RESUME_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
    run.harness.onMain { run.harness.player.pause() }
    run.harness.assertNoPlaybackError()

    // ---- the format, before a single byte is interpreted -----------------------------------
    assertThat(run.capture.encoding)
      .describedAs("the pipeline's own announced encoding; the RMS below reads 16-bit samples")
      .isEqualTo(C.ENCODING_PCM_16BIT)
    assertThat(run.capture.channelCount).isEqualTo(FIXTURE_CHANNEL_COUNT)
    assertThat(run.capture.sampleRateHz).isEqualTo(FIXTURE_SAMPLE_RATE_HZ)

    // ---- the gains the player applied ------------------------------------------------------
    val changes = run.volumes.snapshot()
    val fadeSteps = changes.filter { it.volume < VolumeTimeline.FULL }
    assertThat(fadeSteps.size)
      .describedAs("ramp steps below full volume over a %d ms fade at %d ms per tick", FADE_MS, SleepTimerController.TICK_MS)
      .isGreaterThanOrEqualTo(MIN_RAMP_STEPS)
    val descending = fadeSteps.zipWithNext { earlier, later -> later.volume < earlier.volume }
    assertThat(descending).hasSize(fadeSteps.size - 1)
    assertThat(descending)
      .describedAs("every ramp step strictly quieter than the one before: %s", fadeSteps.map { it.volume })
      .containsOnly(true)
    assertThat(fadeSteps.last().volume)
      .describedAs("the quietest gain the player applied before it paused")
      .isLessThanOrEqualTo(SILENT_ENOUGH)

    val restore = changes.last()
    assertThat(restore.volume)
      .describedAs("the last gain change of the run -- the restore that keeps the app from being silent forever")
      .isEqualTo(VolumeTimeline.FULL)
    assertThat(restore.atNanos).isGreaterThan(fadeSteps.last().atNanos)
    assertThat(run.harness.onMain { run.harness.player.volume }).isEqualTo(VolumeTimeline.FULL)

    // ---- the samples ------------------------------------------------------------------------
    val chunks = run.capture.snapshot()
    assertThat(chunks.size).describedAs("captured PCM buffers").isGreaterThan(MIN_BUFFERS)

    val fullLevel = rmsOf(
      chunks.filter { it.atNanos > chunks.first().atNanos + SETTLE_NANOS && it.atNanos < fadeSteps.first().atNanos },
    )
    assertThat(fullLevel)
      .describedAs("RMS of the decoder's output before the ramp begins")
      .isGreaterThan(MIN_AUDIBLE_RMS)

    val windows = fadeSteps.mapIndexed { index, step ->
      val until = fadeSteps.getOrNull(index + 1)?.atNanos ?: restore.atNanos
      RampWindow(step.volume, chunks.filter { it.atNanos >= step.atNanos && it.atNanos < until })
    }.filter { it.chunks.isNotEmpty() }
    assertThat(windows.size)
      .describedAs("ramp steps that had real audio flowing through them")
      .isGreaterThanOrEqualTo(MIN_MEASURED_STEPS)

    // The premise the whole reconstruction rests on: the decoder kept producing, at the same
    // amplitude, all the way through the ramp. A fade that worked by *muting the source* -- or a
    // player that simply stopped decoding -- fails here and would otherwise be indistinguishable.
    windows.forEach { window ->
      assertThat(rmsOf(window.chunks))
        .describedAs("raw, pre-gain RMS inside the ramp step at gain %f", window.gain)
        .isCloseTo(fullLevel, within(fullLevel * FLATNESS_TOLERANCE))
    }

    // ...and therefore the audible level at each step is the full level times the gain: a linear
    // ramp, measured, in sample units.
    windows.forEach { window ->
      assertThat(rmsOf(window.chunks) * window.gain)
        .describedAs("audible RMS at gain %f", window.gain)
        .isCloseTo(fullLevel * window.gain, within(fullLevel * FLATNESS_TOLERANCE))
    }
    assertThat(windows.last().let { rmsOf(it.chunks) * it.gain })
      .describedAs("audible RMS of the last audio before the pause, against a full level of %f", fullLevel)
      .isLessThan(fullLevel * SILENT_ENOUGH)

    // ---- and it came back --------------------------------------------------------------------
    // Skipped past the sink's own buffer (Plan 3 Task 11 measured ~700 ms of it), so nothing
    // written before the restore lands in this window.
    val afterRestore = chunks.filter { it.atNanos > restore.atNanos + SINK_LEAD_NANOS }
    assertThat(afterRestore.size)
      .describedAs("PCM buffers produced after the timer fired and the listener pressed play")
      .isGreaterThan(MIN_BUFFERS)
    assertThat(afterRestore.map { run.volumes.volumeAt(it.atNanos) })
      .describedAs("the gain applied to every buffer after the restore")
      .containsOnly(VolumeTimeline.FULL)
    assertThat(rmsOf(afterRestore))
      .describedAs("audible RMS after the timer fired and playback was resumed")
      .isCloseTo(fullLevel, within(fullLevel * FLATNESS_TOLERANCE))
  }

  /**
   * The control: a timer whose fade window the run never enters leaves every sample alone.
   *
   * Without this, "the amplitude fell" is consistent with a fade that is always on, with a decoder
   * that tails off, and with any other source of variation in the apparatus.
   */
  @Test
  fun theTimerLeavesTheAudioAloneOutsideTheFadeWindow() {
    val run = playTheBook()
    run.harness.awaitPositionAtLeast(SETTLE_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
    val subject = timer(run, fadeMs = FADE_MS)

    // Far longer than this run: the ticker runs the whole time and must touch nothing.
    run.harness.onMain { subject.start(SleepTimerRequest.Duration(LONG_COUNTDOWN_MS)) }
    run.harness.awaitPositionAtLeast(SETTLE_MS + CONTROL_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
    run.harness.onMain { run.harness.player.pause() }
    run.harness.assertNoPlaybackError()

    assertThat(run.volumes.snapshot().map { it.volume })
      .describedAs("every gain the player applied while a timer ran outside its fade window")
      .isEmpty()

    val chunks = run.capture.snapshot()
      .filter { it.atNanos > run.capture.snapshot().first().atNanos + SETTLE_NANOS }
    assertThat(chunks.size).isGreaterThan(MIN_BUFFERS)

    // Split in half and compare: the same flatness assertion the experiment's premise rests on,
    // made against a run where nothing is supposed to move.
    val half = chunks.size / 2
    val first = rmsOf(chunks.take(half))
    val second = rmsOf(chunks.drop(half))
    assertThat(first).isGreaterThan(MIN_AUDIBLE_RMS)
    assertThat(second)
      .describedAs("second half of an untouched run against its first half")
      .isCloseTo(first, within(first * FLATNESS_TOLERANCE))
  }

  // ---- apparatus ------------------------------------------------------------------------------

  private class RampWindow(val gain: Float, val chunks: List<TimedAudioCapture.Chunk>)

  private class Run(
    val harness: PlayerHarness,
    val capture: TimedAudioCapture,
    val volumes: VolumeTimeline,
  )

  private fun timer(run: Run, fadeMs: Long): SleepTimerController =
    SleepTimerController(clock, fadeMs, SleepTimerController.GRACE_MS)
      .also { controller -> run.harness.onMain { controller.attach(run.harness.player, scope) } }

  /**
   * The **shipping** player -- built through `MuPlayerFactory`, the only construction site in this
   * project -- with its audio sink tapped, playing the seeded book.
   *
   * The tee is appended to `MuPlayRenderersFactory`'s own chain rather than replacing it, exactly
   * as `tappedShippingRenderers` does for `GainAudioProcessorTest`; the difference is only which
   * `AudioBufferSink` backs it, because this suite needs each buffer's arrival time.
   */
  @OptIn(UnstableApi::class)
  private fun playTheBook(): Run {
    val capture = TimedAudioCapture()
    val volumes = VolumeTimeline()
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    cleanups += { cache.release() }
    lateinit var run: Run
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val gainProcessor = GainAudioProcessor()
      val player = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
      ).createExoPlayer(
        gainProcessor,
        MuPlayRenderersFactory(context, gainProcessor, listOf(TeeAudioProcessor(capture))),
      )
      player.addListener(volumes)
      val harness = PlayerHarness(player)
      harnesses += harness
      run = Run(harness, capture, volumes)
      player.setMediaItem(itemFor(book))
      player.prepare()
      player.play()
    }
    return run
  }

  private fun itemFor(song: Song): MediaItem =
    MediaItems.of(
      song = song,
      streamUri = RealTrackBytes.rawStreamUrl(song),
      artworkId = null,
      isAudiobook = true,
      format = StreamFormat.Raw,
    )

  /** Root mean square of every 16-bit little-endian sample in [chunks], in raw sample units. */
  private fun rmsOf(chunks: List<TimedAudioCapture.Chunk>): Float {
    var sum = 0.0
    var count = 0L
    chunks.forEach { chunk ->
      val buffer = ByteBuffer.wrap(chunk.bytes).order(ByteOrder.LITTLE_ENDIAN)
      while (buffer.remaining() >= Short.SIZE_BYTES) {
        val sample = buffer.short.toDouble()
        sum += sample * sample
        count++
      }
    }
    check(count > 0L) { "no audio to measure" }
    return sqrt(sum / count).toFloat()
  }

  private companion object {
    /** `ci/seed-fixtures.sh`'s longest book: 21 s, four unequal chapters, a 180 Hz sine. */
    const val BOOK_TITLE = "Second Book"
    const val FIXTURE_SAMPLE_RATE_HZ = 44_100
    const val FIXTURE_CHANNEL_COUNT = 1

    /** Four seconds of ramp at 250 ms a tick: sixteen steps, the last of them at 1/16 of full. */
    const val FADE_MS = 4_000L
    const val COUNTDOWN_MS = 7_000L

    /** Long enough that this run never reaches its fade window at all. */
    const val LONG_COUNTDOWN_MS = 120_000L

    /** How much audio the control plays past [SETTLE_MS]. */
    const val CONTROL_MS = 4_000L

    /** Media played before anything is measured, so the decoder is in steady state. */
    const val SETTLE_MS = 1_500L
    const val SETTLE_NANOS = SETTLE_MS * 1_000_000L

    /** Media played after the resume, so "it came back" is audio and not a flag. */
    const val RESUME_MS = 1_500L

    /**
     * How far the sink runs ahead of the speaker, measured on `muplay37` in Plan 3 Task 11 at
     * ~700 ms. One second, so the window this excludes has room over the measured value.
     */
    const val SINK_LEAD_NANOS = 1_000L * 1_000_000L

    /** [FADE_MS] / [SleepTimerController.TICK_MS] is sixteen; twelve leaves room for tick jitter. */
    const val MIN_RAMP_STEPS = 12
    const val MIN_MEASURED_STEPS = 8

    /**
     * -20 dB. The ramp's last step is `TICK_MS / FADE_MS` = 1/16 of full scale, so this both
     * discriminates against a fade that stops part-way and leaves room for the tick that lands
     * closest to the deadline.
     */
    const val SILENT_ENOUGH = 0.10f

    /** Wide because it is a real lossy decoder; the exact arithmetic is `SleepTimerFadeTest`'s. */
    const val FLATNESS_TOLERANCE = 0.10f

    /**
     * A floor under "this capture is not silence", well below the seeded sines' measured level --
     * `GainAudioProcessorTest` records the music fixtures at RMS 2751. Derived assertions above
     * scale from the run's own measured `fullLevel` rather than from this.
     */
    const val MIN_AUDIBLE_RMS = 200f

    /** A vacuity floor on the capture: a few tenths of a second of buffers, not an expected count. */
    const val MIN_BUFFERS = 10

    const val PLAYBACK_TIMEOUT_MS = 30_000L

    val MAIN_EXECUTOR = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
  }
}
