package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.Song
import app.muplay.testing.BookFixtures
import app.muplay.testing.PcmAnalysis
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gapless, measured in PCM frames off a real decoder on a real device.
 *
 * Spec section 4: *"Gapless has zero server support. Use a real Media3 `setMediaItems` queue and let
 * ExoPlayer read LAME/iTunSMPB. Never hand-roll."* Neither half of that is observable from
 * `onMediaItemTransition` firing, which is what a "was asked to play" gapless test looks like. What
 * is observable is the audio: a `TeeAudioProcessor` inside the audio sink captures every frame the
 * decoder produced, and the silence in it is measured by `PcmAnalysis`.
 *
 * ### Where the measurement looks, and why it needs no expected frame count
 *
 * A gap is silence **at a join**, and this capture knows where its own joins are: the audio
 * processing pipeline is drained once per media period, and [CapturingAudioSink.flushOffsets]
 * records how many bytes had been captured each time that happened. Every interior entry is a
 * boundary between two tracks, measured rather than assumed -- and if a teardown ever happened
 * somewhere unexpected, that is precisely where this would then look for silence. So no expected
 * duration appears in the gap assertion and no constant can satisfy it.
 *
 * The trailing padding of the **last** track is deliberately outside the claim: it is silence after
 * the final sample, with no following track to be gapless with. It is real -- 529 frames, 11 ms,
 * measured -- and it is the reason this class measures joins rather than the whole stream.
 *
 * ### What the joins actually contain, measured on `muplay37`
 *
 * | run | frames | silence at each join |
 * |---|---|---|
 * | one `setMediaItems` queue of three | 662231 | none at all |
 * | three separate `prepare()` cycles of the same three | 663693 | 535 and 529 frames (12 ms, 11 ms) |
 *
 * The queue is 1462 frames *shorter*, and that number is not slack: it is exactly twice the 731
 * frames of encoder padding left on a track played by itself, dropped at each of the two interior
 * joins. That is what "ExoPlayer reads LAME/iTunSMPB" looks like when it is measured -- the join is
 * sample-exact, and three separate preparations of the same three files are not.
 *
 * ### The measurement is proved able to fail, on this tier, not only on the analyser's
 *
 * `PcmAnalysis` (`:core:testing`) has its own JVM tests over synthetic buffers with known silence in
 * them. That gates the *analyser*. It does not gate the *capture*: a `CapturingAudioSink` that
 * recorded nothing, or a player whose sink was never tapped, would leave every silence assertion
 * here green having measured an empty buffer, and so would a join scan whose windows landed on no
 * data. So [silenceInsertedIntoTheQueueIsMeasuredAsSilence] plays the same queue with a known
 * [SILENCE_MS] of digital silence spliced into it and requires this apparatus to find exactly that,
 * at the join, through the same player and the same analyser. A one-sided "no silence found" claim
 * is indistinguishable from a broken sink; the pair is the evidence.
 *
 * ### The player is the shipping player
 *
 * Every experiment builds its player through [MuPlayerFactory], which is the only construction site
 * in this project and the only place the 429 retry policy is attached -- `PlayerConstructionTest`
 * refuses a second one, including in this file. The one thing these experiments change is the
 * `RenderersFactory`, because Media3 offers no way to reach the audio processor chain after
 * construction; see that factory's own note on why the seam is a parameter there rather than a
 * player assembled here.
 *
 * ### No stream URL is ever read
 *
 * A Navidrome stream URL carries `u`, `s=salt` and `t=md5(password+salt)`, and an AssertJ failure
 * message prints the value it saw. The URLs below are handed to a `MediaItem` and never asserted on,
 * never logged and never put in a description; every identity assertion in this class is on a media
 * id. Same rule as `MuPlaybackServiceTest`.
 */
@RunWith(AndroidJUnit4::class)
class GaplessTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private lateinit var streamUrls: List<String>
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "gapless-test-${System.nanoTime()}")
    songs = runBlocking { RealTrackBytes.musicTracks() }
    check(songs.size == TRACK_COUNT) {
      "ci/seed-fixtures.sh seeds $TRACK_COUNT mp3 music tracks; found ${songs.size}. This test's " +
        "arithmetic is over exactly those."
    }
    streamUrls = songs.map { RealTrackBytes.rawStreamUrl(it) }
    // Serves the one thing the real library cannot: a track that is deliberately silent. Started
    // for every test rather than only the one that uses it, so there is no branch here that could
    // leave it unstarted.
    server = MockWebServer()
    server.start()
  }

  /**
   * Guarded, because an un-guarded `@After` **replaces the real failure with its own**.
   *
   * `server` is set on the last line of [setUp]. Any failure before it -- the fixture check above,
   * a refused connection to the container -- leaves the property unset, and a bare `server.close()`
   * then throws `UninitializedPropertyAccessException` from `tearDown`, which is the only message
   * the report carries. Measured on 2026-08-27: a corpus change added a fourth music file, the
   * `check` above fired, and all four tests here reported nothing but the cleanup exception. One
   * lane read that and concluded this module's suites were broken; the cause was a hardcoded count.
   */
  @After
  fun tearDown() {
    if (::server.isInitialized) server.close()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
  }

  @Test
  fun aQueueJoinsItsTracksWithNoSilenceBetweenThem() {
    val capture = playAsOneQueue()

    // Sanity first: a real 16-bit PCM stream at a real rate, or every number below it is a quantity
    // with no unit. `PcmAnalysis` handles 16-bit little-endian and nothing else, so this is the
    // assertion that stops any other encoding being silently mis-measured rather than rejected.
    assertThat(capture.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
    assertThat(capture.sampleRateHz).isEqualTo(FIXTURE_SAMPLE_RATE_HZ)
    assertThat(capture.channelCount).isEqualTo(FIXTURE_CHANNEL_COUNT)

    /*
     * All three decoded, and decoded once. 220500 + 220500 + 221231 frames: two interior tracks
     * trimmed exactly to their 5.000 s of real audio, and the last one carrying the 731 frames of
     * encoder padding that no following track asked it to drop. A player that never read the
     * Xing/LAME header would run 15125 ms, which this band excludes; one that dropped a track would
     * run 10 s, which it excludes by a mile.
     */
    assertThat(playedMs(capture))
      .describedAs("decoded audio across a three-track queue")
      .isBetween(QUEUED_TOTAL_MS - TOTAL_TOLERANCE_MS, QUEUED_TOTAL_MS + TOTAL_TOLERANCE_MS)

    /*
     * The gapless assertion proper. Every seeded track is a continuous sine wave
     * (ci/seed-fixtures.sh: 385, 440, 495 Hz), so a genuine signal is never more than a sample or
     * two from one side of zero to the other; silence at a join is encoder delay or padding that
     * was not trimmed, and it is what a listener hears as "a gap".
     *
     * Measured here: none at all. [aQueueLeavesLessSilenceAtItsJoinsThanThreeSeparatePreparations]
     * is what makes that a result rather than a formality, and
     * [silenceInsertedIntoTheQueueIsMeasuredAsSilence] is what makes it a measurement rather than an
     * empty buffer.
     */
    assertThat(joinSilenceMs(capture))
      .describedAs("longest silence at a join of a three-track queue")
      .isLessThan(AUDIBLE_GAP_MS)
  }

  @Test
  fun aQueueLeavesLessSilenceAtItsJoinsThanThreeSeparatePreparations() {
    val queued = playAsOneQueue()
    val separate = playAsThreeSeparatePreparations()

    val queuedSilenceMs = joinSilenceMs(queued)
    val separateSilenceMs = joinSilenceMs(separate)

    // The comparison is the argument: the same three tracks, in the same order, through the same
    // player construction and the same capture, differing in one thing only -- whether they were
    // queued or prepared one at a time.
    //
    // Both bounds are needed. Strictly less is the property; the floor under `separate` is what
    // stops this passing as a comparison between two silences that were both absent, which is
    // exactly what it would degrade to if the capture stopped recording.
    assertThat(separateSilenceMs)
      .describedAs("silence at the joins of three separate preparations of the same three tracks")
      .isGreaterThanOrEqualTo(AUDIBLE_GAP_MS)
    assertThat(queuedSilenceMs)
      .describedAs("silence at a join: queued (%d ms) vs separately prepared (%d ms)", queuedSilenceMs, separateSilenceMs)
      .isLessThan(separateSilenceMs)

    /*
     * And the queue did not achieve that by dropping audio. It is *shorter* -- by the encoder
     * padding it trimmed at each of the two interior joins, 731 frames each, 33 ms in total -- and
     * that is the point rather than a caveat: a join is seamless precisely because the padding
     * between the two tracks is gone. A queue that lost a whole track would show up here as
     * seconds, not tens of milliseconds.
     */
    val differenceMs = playedMs(separate) - playedMs(queued)
    assertThat(differenceMs)
      .describedAs(
        "audio the queue trimmed at its joins: queued %d ms, separately prepared %d ms",
        playedMs(queued),
        playedMs(separate),
      )
      .isBetween(TRIMMED_AT_JOINS_MS - JOIN_TRIM_TOLERANCE_MS, TRIMMED_AT_JOINS_MS + JOIN_TRIM_TOLERANCE_MS)
  }

  @Test
  fun theQueueReallyPlayedEveryTrackAndNotTheFirstOneThreeTimes() {
    // The control for the two tests above. A "gapless" implementation that played track 1 three
    // times would satisfy the frame count and every silence measurement perfectly. Media3 reports
    // each transition; three distinct media ids, in order, is what rules that out.
    val transitions = mutableListOf<String>()

    runExperiment(transitions) { harness ->
      harness.onMain {
        harness.player.setMediaItems(songs.indices.map(::trackItem))
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
    }

    assertThat(transitions).containsExactly(songs[0].id, songs[1].id, songs[2].id)
  }

  /**
   * The falsification, run as a test rather than written down as a claim.
   *
   * Splices [SILENCE_MS] of digital silence into the same queue, as a WAV item in the fixtures' own
   * format so that nothing else about the pipeline changes -- no sample rate change, no channel
   * change, no sink reconfiguration -- and requires the apparatus to find it, twice over: once as
   * the largest silence anywhere in the stream, which pins the *magnitude*, and once through the
   * join scan that [aQueueJoinsItsTracksWithNoSilenceBetweenThem] reads its whole claim through.
   *
   * Every way that claim could be quietly dead fails here and *only* here: a sink that captured
   * nothing, a player whose renderers were never tapped, a `longestZeroRunFrames` that returned a
   * constant, a `framesToMs` denominated in the wrong unit or read at the wrong rate, a join scan
   * whose windows covered no audio. Each of those leaves the gapless assertion **green**.
   */
  @Test
  fun silenceInsertedIntoTheQueueIsMeasuredAsSilence() {
    val silence = silentWav(SILENCE_MS, FIXTURE_SAMPLE_RATE_HZ, FIXTURE_CHANNEL_COUNT)
    repeat(SILENCE_RESPONSES) { server.enqueue(wavResponse(silence)) }

    val capture = runExperiment { harness ->
      harness.onMain {
        harness.player.setMediaItems(
          listOf(trackItem(0), silenceItem(), trackItem(1), trackItem(2)),
        )
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
    }

    // The three real tracks are still all there: the silence was added to the queue, not swapped in
    // for a track. Without this, a run that played the silence and nothing else would satisfy both
    // assertions below perfectly.
    assertThat(playedMs(capture))
      .describedAs("decoded audio across three tracks plus %d ms of silence", SILENCE_MS)
      .isBetween(
        QUEUED_TOTAL_MS + SILENCE_MS - TOTAL_TOLERANCE_MS,
        QUEUED_TOTAL_MS + SILENCE_MS + TOTAL_TOLERANCE_MS,
      )

    // Two-sided on purpose: too little silence found means the capture is losing audio, too much
    // means it is finding silence the stream does not contain.
    val wholeStreamSilenceMs = PcmAnalysis.framesToMs(
      PcmAnalysis.longestZeroRunFrames(capture.pcm, capture.channelCount),
      capture.sampleRateHz,
    )
    assertThat(wholeStreamSilenceMs)
      .describedAs("the %d ms spliced into the queue, as this capture and analyser measure it", SILENCE_MS)
      .isBetween(SILENCE_MS - SILENCE_TOLERANCE_MS, SILENCE_MS + SILENCE_TOLERANCE_MS)

    // ...and the join scan sees it too, which is the half that gates
    // `aQueueJoinsItsTracksWithNoSilenceBetweenThem`. It reads a window either side of each join, so
    // what it reports for a gap this large is the window, not the gap -- greater than the audible
    // threshold is the claim, and the magnitude is the assertion above.
    assertThat(joinSilenceMs(capture))
      .describedAs("silence at the join of a deliberately gapped queue")
      .isGreaterThan(AUDIBLE_GAP_MS)
  }

  /**
   * The longest run of silence at any join, in milliseconds.
   *
   * A join is wherever the audio processing pipeline was drained with audio on both sides of it --
   * that is, an entry of [CapturingAudioSink.flushOffsets] strictly inside the capture. The
   * pipeline reports its own boundaries, so nothing here is an expected frame count: a queue's joins
   * fall where its tracks meet, three separate preparations' fall where each `prepare()` cycle ends,
   * and a teardown in an unexpected place would simply move where this looks.
   *
   * The final entry -- the end of the stream -- is not a join. The last track's encoder padding
   * lives there, 529 frames of it, with no following track for it to be a gap between.
   */
  private fun joinSilenceMs(capture: CapturingAudioSink): Long {
    val total = frames(capture)
    val windowFrames = capture.sampleRateHz * JOIN_WINDOW_MS / MILLIS_PER_SECOND
    val bytesPerFrame = BYTES_PER_SAMPLE * capture.channelCount
    val joins = capture.flushOffsets
      .map { PcmAnalysis.frameCount(it, capture.channelCount) }
      .filter { it > 0 && it < total }
      .distinct()

    // The vacuity guard, and it is not decoration: with no join to look at, `maxOf` below would
    // have nothing to reduce and this measurement would be an assertion over no audio at all.
    check(joins.isNotEmpty()) {
      "no interior pipeline flush was recorded in $total frames (offsets=${capture.flushOffsets}), " +
        "so there is no join to measure silence at and this assertion would be vacuous"
    }

    return joins.maxOf { join ->
      val from = maxOf(0, join - windowFrames)
      val to = minOf(total, join + windowFrames)
      PcmAnalysis.framesToMs(
        PcmAnalysis.longestZeroRunFrames(
          capture.pcm.copyOfRange(from * bytesPerFrame, to * bytesPerFrame),
          capture.channelCount,
        ),
        capture.sampleRateHz,
      )
    }
  }

  private fun frames(capture: CapturingAudioSink): Int =
    PcmAnalysis.frameCount(capture.pcm.size, capture.channelCount)

  private fun playedMs(capture: CapturingAudioSink): Long =
    PcmAnalysis.framesToMs(frames(capture), capture.sampleRateHz)

  /**
   * [setCustomCacheKey][MediaItem.Builder.setCustomCacheKey] is not decoration: every byte travels
   * through a `CacheDataSource` and `TrackIdCacheKeyFactory` throws outright on an item without one.
   * The key is the track id, which is what production uses.
   */
  private fun trackItem(index: Int): MediaItem =
    MediaItem.Builder()
      .setMediaId(songs[index].id)
      .setUri(streamUrls[index])
      .setCustomCacheKey(songs[index].id)
      .build()

  private fun silenceItem(): MediaItem =
    MediaItem.Builder()
      .setMediaId(SILENCE_MEDIA_ID)
      .setUri(server.url("/$SILENCE_MEDIA_ID.wav").toString())
      .setCustomCacheKey("$SILENCE_MEDIA_ID-${System.nanoTime()}")
      .build()

  private fun wavResponse(wav: ByteArray): MockResponse =
    MockResponse.Builder()
      .code(200)
      .addHeader("Content-Type", "audio/wav")
      // A `CacheDataSource` can only cache a bounded resource, and `Accept-Ranges` plus the
      // `Content-Length` the body carries is what makes this one bounded -- the same property
      // `MediaCache`'s own note explains `format=raw` is asked for.
      .addHeader("Accept-Ranges", "bytes")
      .body(Buffer().write(wav))
      .build()

  private fun playAsOneQueue(): CapturingAudioSink = runExperiment { harness ->
    harness.onMain {
      harness.player.setMediaItems(songs.indices.map(::trackItem))
      harness.player.prepare()
      harness.player.play()
    }
    harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
  }

  /**
   * The same three tracks, each its own `prepare()` cycle -- the arrangement gapless queueing is
   * being compared against.
   *
   * The position wait before each `awaitEnded` is load-bearing rather than belt-and-braces: after
   * the first track the player is already in `STATE_ENDED`, so a bare `awaitEnded` for the second
   * would return immediately on the *previous* track's state and this experiment would report one
   * playback where it claims three. `setMediaItem` resets the position to zero synchronously, so a
   * position past four seconds can only belong to the track just queued.
   */
  private fun playAsThreeSeparatePreparations(): CapturingAudioSink = runExperiment { harness ->
    songs.indices.forEach { index ->
      harness.onMain {
        harness.player.setMediaItem(trackItem(index))
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitPositionAtLeast(NEARLY_A_WHOLE_TRACK_MS, timeoutMs = PLAYBACK_TIMEOUT_MS)
      harness.awaitEnded(timeoutMs = PLAYBACK_TIMEOUT_MS)
    }
  }

  /**
   * Builds the shipping player with its audio sink tapped, runs [block], and returns the capture.
   *
   * A fresh `SimpleCache` directory per experiment, for the reason `MuPlayDataSourceFactoryTest`
   * gives: `SimpleCache` refuses a second live instance over a directory another instance holds,
   * and a cache shared between two experiments would let the first one's bytes answer the second
   * one's reads -- which is a difference between the two runs that this comparison claims not to
   * have.
   */
  private fun runExperiment(
    transitions: MutableList<String>? = null,
    block: (PlayerHarness) -> Unit,
  ): CapturingAudioSink {
    val capture = CapturingAudioSink()
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    lateinit var harness: PlayerHarness
    // Built on the main thread because an ExoPlayer binds to its creating thread's Looper; a
    // violation surfaces at the first access, far from here.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val playerFactory = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        // This suite's subject is an `ExoPlayer` behaviour, so it takes the raw player from
        // `createExoPlayer()`. The policy is still required to construct the factory; the seam it
        // feeds is `MuPlayerTest`'s subject, not this file's.
        resumePolicy = NeverResume,
      )
      // The production chain, tapped -- not a `DefaultRenderersFactory` of this suite's own. Task
      // 11 put a `GainAudioProcessor` in the shipping chain, and a gapless measurement taken on a
      // pipeline that no longer ships is a measurement of the wrong thing. The gain is
      // `ReplayGainPolicy.UNCHANGED` throughout here: these items carry no gain extras, so the
      // stage copies rather than multiplies and the frame counts below are unaffected. Measured,
      // not assumed -- the totals in this file's own table were re-run after the change.
      val gainProcessor = GainAudioProcessor()
      harness = PlayerHarness(
        playerFactory.createExoPlayer(
          gainProcessor,
          tappedShippingRenderers(context, gainProcessor, capture),
        ),
      )
      if (transitions != null) {
        harness.player.addListener(object : Player.Listener {
          override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let(transitions::add)
          }
        })
      }
    }
    try {
      block(harness)
      harness.assertNoPlaybackError()
    } finally {
      harness.release()
      // Released before the directory is deleted, and always: an unreleased `SimpleCache` holds
      // its folder for the life of the process, so a leak here fails the *next* experiment with a
      // message about a folder rather than about playback.
      cache.release()
    }
    return capture
  }

  /**
   * A canonical 44-byte-header WAV of [durationMs] of exact digital silence.
   *
   * Built here rather than seeded, because `ci/seed-fixtures.sh` cannot help: the file has to be
   * *exactly* zero in every sample for this to be a known quantity, and an MP3 of silence is not --
   * it decodes to a quantisation ripple around zero, and it would arrive with encoder delay and
   * padding of its own. Raw PCM has no encoder to argue with, and ExoPlayer plays it through the
   * same audio sink and the same processor chain as an MP3.
   */
  private fun silentWav(durationMs: Long, sampleRateHz: Int, channelCount: Int): ByteArray {
    val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
    val dataBytes = (durationMs * sampleRateHz / MILLIS_PER_SECOND).toInt() * bytesPerFrame
    val wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray(Charsets.US_ASCII))
    wav.putInt(WAV_HEADER_BYTES - RIFF_PREFIX_BYTES + dataBytes)
    wav.put("WAVE".toByteArray(Charsets.US_ASCII))
    wav.put("fmt ".toByteArray(Charsets.US_ASCII))
    wav.putInt(PCM_FMT_CHUNK_BYTES)
    wav.putShort(WAV_FORMAT_PCM)
    wav.putShort(channelCount.toShort())
    wav.putInt(sampleRateHz)
    wav.putInt(sampleRateHz * bytesPerFrame) // byte rate
    wav.putShort(bytesPerFrame.toShort()) // block align
    wav.putShort((BYTES_PER_SAMPLE * BITS_PER_BYTE).toShort())
    wav.put("data".toByteArray(Charsets.US_ASCII))
    wav.putInt(dataBytes)
    // The remaining `dataBytes` are already zero -- which is the point of the file.
    return wav.array()
  }

  private companion object {
    /**
     * How many **mp3** music fixtures the corpus holds, **derived from the committed oracle**
     * rather than written down.
     *
     * It was a literal `3`, and Plan 3 Task 12's Opus fixture made that literal wrong -- silently,
     * because `RealTrackBytes.musicTracks()` returned four and the `check` in [setUp] then fired
     * with a message the cleanup exception buried. A count that has to be edited every time the
     * corpus grows is the same defect one number later, so it is read off `books.tsv`, which
     * `ci/probe-chapters.sh --check` re-derives from the audio on every CI run.
     *
     * `.mp3` and not every music track: `RealTrackBytes.musicTracks()` filters to the mp3 fixtures
     * for this suite's benefit -- a thirty-second Opus track whose first ten seconds are silent
     * would break every measurement below -- so this has to count the same set.
     */
    val TRACK_COUNT: Int = BookFixtures.MUSIC_TRACKS.count { it.path.endsWith(".mp3") }

    /** `ffmpeg -ac 1 ... sample_rate=44100`, per `ci/seed-fixtures.sh`. */
    const val FIXTURE_SAMPLE_RATE_HZ = 44100
    const val FIXTURE_CHANNEL_COUNT = 1

    /**
     * 220500 + 220500 + 221231 frames, measured on `muplay37`. The two interior tracks are trimmed
     * to exactly their 5.000 s of real audio; the last keeps the 731 frames of encoder padding that
     * no following track asked it to drop. Untrimmed, three of these files run 15125 ms -- which
     * [TOTAL_TOLERANCE_MS] is sized to exclude.
     */
    const val QUEUED_TOTAL_MS = 15_016L
    const val TOTAL_TOLERANCE_MS = 60L

    /**
     * 2 x 731 frames: the encoder padding a queue drops at each of its two interior joins, and so
     * the amount by which it is *shorter* than three separate preparations of the same three files.
     */
    const val TRIMMED_AT_JOINS_MS = 33L
    const val JOIN_TRIM_TOLERANCE_MS = 12L

    /**
     * The threshold "an audible gap" is denominated in. Measured against it: a queue's joins hold
     * no silence at all, and three separate preparations of the same tracks hold 11 and 12 ms.
     */
    const val AUDIBLE_GAP_MS = 10L

    /**
     * How far either side of a join [joinSilenceMs] looks. Wide enough to contain the whole of an
     * untrimmed encoder delay or padding (~12 ms, either side of the boundary), narrow enough that
     * it can only report silence that is genuinely at the join.
     */
    const val JOIN_WINDOW_MS = 50

    /** The known gap [silenceInsertedIntoTheQueueIsMeasuredAsSilence] splices in, and its band. */
    const val SILENCE_MS = 600L
    const val SILENCE_TOLERANCE_MS = 20L
    const val SILENCE_MEDIA_ID = "gapless-test-silence"

    /**
     * More responses than the one request this needs. A queue sized to the expected count makes
     * MockWebServer's dispatcher -- which blocks on an empty queue -- the thing that ends the
     * playback, and a blocked read surfaces as a socket timeout rather than as this test failing.
     */
    const val SILENCE_RESPONSES = 4

    const val PLAYBACK_TIMEOUT_MS = 60_000L

    /** Past four seconds of a five-second track: a position that only a real decode reaches. */
    const val NEARLY_A_WHOLE_TRACK_MS = 4_000L

    const val BYTES_PER_SAMPLE = 2
    const val BITS_PER_BYTE = 8
    const val MILLIS_PER_SECOND = 1_000
    const val WAV_HEADER_BYTES = 44
    const val RIFF_PREFIX_BYTES = 8
    const val PCM_FMT_CHUNK_BYTES = 16
    const val WAV_FORMAT_PCM: Short = 1
  }
}
