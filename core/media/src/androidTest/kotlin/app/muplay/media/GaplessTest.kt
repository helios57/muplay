package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.testing.PcmAnalysis
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
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
 * ExoPlayer read LAME/iTunSMPB. Never hand-roll."* There are two claims in that sentence and this
 * class measures both, separately, because a test that conflates them proves neither:
 *
 * 1. **The encoder's delay and padding are trimmed.** Each seeded fixture carries a LAME header
 *    saying 576 samples of delay and 1260 of padding (read off `01 - Track 1.mp3` with `xxd`, not
 *    assumed), and the decoder emits 222336 samples for it. Trimmed, that is exactly 220500 --
 *    5.000 s. Untrimmed, three of them run 125 ms long. [aQueuePlaysAllThreeTracksWithNoInsertedSilence]
 *    holds the total to a band that excludes the untrimmed answer, and holds the longest run of
 *    silence anywhere in the stream under the audible threshold.
 * 2. **The audio pipeline is not torn down between tracks.**
 *    [aQueueReconfiguresTheAudioSinkFewerTimesThanThreeSeparatePreparations] is that one, and it is
 *    a comparison rather than a constant: one queue of three tracks against three separate
 *    `prepare()` cycles of the same three, everything else held identical.
 *
 * Neither is observable from `onMediaItemTransition` firing, which is what a "was asked to play"
 * gapless test looks like.
 *
 * ### The measurement is proved able to fail, on this tier, not only on the analyser's
 *
 * `PcmAnalysis` (`:core:testing`) has its own JVM tests over synthetic buffers with known silence in
 * them. That gates the *analyser*. It does not gate the *capture*: a `CapturingAudioSink` that
 * recorded nothing, or a player whose sink was never tapped, would leave the silence assertions here
 * green having measured an empty buffer. So [silenceInsertedIntoTheQueueIsMeasuredAsSilence] plays a
 * queue with a known 600 ms of digital silence spliced into it and requires this apparatus to find
 * exactly that -- the same player, the same tee, the same analyser, one item different. A one-sided
 * "no silence found" claim is indistinguishable from a broken sink; the pair is the evidence.
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
      "ci/seed-fixtures.sh seeds exactly $TRACK_COUNT music tracks; found ${songs.size}. This " +
        "test's arithmetic is over those three."
    }
    val client = RealTrackBytes.client()
    streamUrls = songs.map { client.streamUrl(it.id, StreamFormat.Raw) }
    // Serves the one thing the real library cannot: a track that is deliberately silent. Started
    // for every test rather than only the one that uses it, so there is no branch here that could
    // leave it unstarted.
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
    cacheDir.deleteRecursively()
  }

  @Test
  fun aQueuePlaysAllThreeTracksWithNoInsertedSilence() {
    val capture = playAsOneQueue()

    // Sanity first: a real 16-bit PCM stream at a real rate, or every number below it is a
    // quantity with no unit. `PcmAnalysis` handles 16-bit little-endian and nothing else, so this
    // is the assertion that stops anything else being silently mis-measured.
    assertThat(capture.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
    assertThat(capture.sampleRateHz).isEqualTo(FIXTURE_SAMPLE_RATE_HZ)
    assertThat(capture.channelCount).isEqualTo(FIXTURE_CHANNEL_COUNT)

    val playedMs = playedMs(capture)
    /*
     * Claim 1, and the band is what makes it a claim rather than a formality. Each fixture decodes
     * to 222336 samples and its LAME header asks for 576 + 1260 of them to be dropped, leaving
     * exactly 220500 -- 5.000 s. Three tracks trimmed are 15.000 s; three untrimmed are 15.125 s.
     * A player that never read the Xing/LAME header lands outside this band, which is the whole of
     * what "ExoPlayer reads LAME/iTunSMPB" means when it is measured instead of asserted.
     */
    assertThat(playedMs)
      .describedAs("decoded audio across a three-track queue, trimmed = 15000 ms")
      .isBetween(TRIMMED_TOTAL_MS - TOTAL_TOLERANCE_MS, TRIMMED_TOTAL_MS + TOTAL_TOLERANCE_MS)

    /*
     * The audible half of claim 1, and it is self-calibrating: no expected frame count appears in
     * it, so no constant can satisfy it. Every seeded track is a continuous sine wave
     * (ci/seed-fixtures.sh: 385, 440, 495 Hz), so a genuine signal is at most a sample or two from
     * one side of zero to the other -- measured on the decoded fixture, the longest run of exact
     * zeros in a trimmed track is one sample. Untrimmed encoder delay and padding are silence, and
     * they sit at exactly the two places a queue joins two tracks.
     *
     * [silenceInsertedIntoTheQueueIsMeasuredAsSilence] is what stops this assertion being satisfied
     * by a capture that recorded nothing.
     */
    val silentMs = longestSilenceMs(capture)
    assertThat(silentMs)
      .describedAs("longest run of silence anywhere in three gaplessly-queued tracks")
      .isLessThan(AUDIBLE_GAP_MS)
  }

  @Test
  fun aQueueReconfiguresTheAudioSinkFewerTimesThanThreeSeparatePreparations() {
    val queued = playAsOneQueue()
    val separate = playAsThreeSeparatePreparations()

    // Claim 2: the pipeline was not torn down between tracks. Strictly fewer, not "equal to one" --
    // the absolute count is a Media3 implementation detail, the comparison is the property.
    // Everything but the queueing is identical between the two runs: same tracks, same order, same
    // player construction, same capture.
    assertThat(queued.flushCount)
      .describedAs(
        "audio pipeline flushes for one queue of three (%d) vs three preparations (%d)",
        queued.flushCount,
        separate.flushCount,
      )
      .isLessThan(separate.flushCount)

    // ...and the queue did not achieve that by playing less. Within 10 ms of the same audio.
    val differenceMs = PcmAnalysis.framesToMs(
      abs(frames(queued) - frames(separate)),
      queued.sampleRateHz,
    )
    assertThat(differenceMs)
      .describedAs(
        "total decoded audio, queued (%d ms) vs separately prepared (%d ms)",
        playedMs(queued),
        playedMs(separate),
      )
      .isLessThan(AUDIBLE_GAP_MS)
  }

  @Test
  fun theQueueReallyPlayedEveryTrackAndNotTheFirstOneThreeTimes() {
    // The control for the two tests above. A "gapless" implementation that played track 1 three
    // times would satisfy the frame count and the zero-run check perfectly. Media3 reports each
    // transition; three distinct media ids, in order, is what rules that out.
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
   * Splices [SILENCE_MS] of digital silence into the middle of the same queue, as a WAV item in the
   * fixtures' own format so that nothing else about the pipeline changes -- no sample rate change,
   * no channel change, no sink reconfiguration -- and requires the apparatus to measure it. Every
   * one of the ways this measurement could be quietly dead fails here: a sink that captured nothing,
   * a player whose renderers were never tapped, a `longestZeroRunFrames` that returned a constant,
   * a `framesToMs` denominated in the wrong unit or read at the wrong rate. Each of those leaves
   * [aQueuePlaysAllThreeTracksWithNoInsertedSilence] **green**.
   *
   * It is a two-sided assertion on purpose: too little silence found means the capture is missing
   * audio, too much means it is finding silence the stream does not contain.
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
    // for a track. Without this, a run that played the silence and nothing else would satisfy the
    // assertion below perfectly.
    assertThat(playedMs(capture))
      .describedAs("decoded audio across three tracks plus %d ms of silence", SILENCE_MS)
      .isBetween(
        TRIMMED_TOTAL_MS + SILENCE_MS - TOTAL_TOLERANCE_MS,
        TRIMMED_TOTAL_MS + SILENCE_MS + TOTAL_TOLERANCE_MS,
      )

    assertThat(longestSilenceMs(capture))
      .describedAs("the %d ms of silence spliced into the queue, as this apparatus measures it", SILENCE_MS)
      .isBetween(SILENCE_MS - SILENCE_TOLERANCE_MS, SILENCE_MS + SILENCE_TOLERANCE_MS)
  }

  private fun frames(capture: CapturingAudioSink): Int =
    PcmAnalysis.frameCount(capture.pcm.size, capture.channelCount)

  private fun playedMs(capture: CapturingAudioSink): Long =
    PcmAnalysis.framesToMs(frames(capture), capture.sampleRateHz)

  private fun longestSilenceMs(capture: CapturingAudioSink): Long =
    PcmAnalysis.framesToMs(
      PcmAnalysis.longestZeroRunFrames(capture.pcm, capture.channelCount),
      capture.sampleRateHz,
    )

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
   * playback where it claims three. `setMediaItem` resets the position to zero synchronously, so
   * a position past four seconds can only belong to the track just queued.
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
      )
      harness = PlayerHarness(playerFactory.create(TappedRenderersFactory(context, capture)))
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
   * it decodes to a quantisation ripple around zero. Raw PCM has no encoder to argue with, and
   * ExoPlayer plays it through the same audio sink and the same processor chain as an MP3, with no
   * encoder delay or padding of its own to trim.
   */
  private fun silentWav(durationMs: Long, sampleRateHz: Int, channelCount: Int): ByteArray {
    val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
    val dataBytes = (durationMs * sampleRateHz / MILLIS_PER_SECOND).toInt() * bytesPerFrame
    val wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray(Charsets.US_ASCII))
    wav.putInt(WAV_HEADER_BYTES - 8 + dataBytes)
    wav.put("WAVE".toByteArray(Charsets.US_ASCII))
    wav.put("fmt ".toByteArray(Charsets.US_ASCII))
    wav.putInt(16) // PCM fmt chunk size
    wav.putShort(1) // PCM, uncompressed
    wav.putShort(channelCount.toShort())
    wav.putInt(sampleRateHz)
    wav.putInt(sampleRateHz * bytesPerFrame) // byte rate
    wav.putShort(bytesPerFrame.toShort()) // block align
    wav.putShort((BYTES_PER_SAMPLE * 8).toShort()) // bits per sample
    wav.put("data".toByteArray(Charsets.US_ASCII))
    wav.putInt(dataBytes)
    // The remaining `dataBytes` are already zero -- which is the point of the file.
    return wav.array()
  }

  private companion object {
    /** `ci/seed-fixtures.sh` seeds three, and this class's arithmetic is over those three. */
    const val TRACK_COUNT = 3

    /** `ffmpeg -ac 1 ... sample_rate=44100`, per `ci/seed-fixtures.sh`. */
    const val FIXTURE_SAMPLE_RATE_HZ = 44100
    const val FIXTURE_CHANNEL_COUNT = 1

    /**
     * Three 5.000 s tracks with their LAME delay and padding trimmed: 3 x 220500 frames.
     * Untrimmed they run to 15125 ms, which is what [TOTAL_TOLERANCE_MS] is sized to exclude.
     */
    const val TRIMMED_TOTAL_MS = 15_000L
    const val TOTAL_TOLERANCE_MS = 60L

    /**
     * The threshold "an audible gap" is denominated in. Untrimmed encoder delay is ~25 ms of
     * silence per track boundary; a genuine sine crosses zero in a sample or two.
     */
    const val AUDIBLE_GAP_MS = 10L

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
    const val MILLIS_PER_SECOND = 1_000L
    const val WAV_HEADER_BYTES = 44
  }
}
