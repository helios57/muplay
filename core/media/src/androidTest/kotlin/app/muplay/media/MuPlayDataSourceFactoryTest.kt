package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real ExoPlayer, real HTTP, real MP3 bytes, real emulator.
 *
 * The bytes come from the **live Navidrome container** rather than from a fixture copied into this
 * module's assets: `ci/fixtures.md5` already pins those files and `ci/seed-fixtures.sh` already
 * builds them, and a second copy in `src/androidTest/assets` is a second thing to keep in sync
 * that nothing checks. `.github/workflows/e2e.yml` starts the container and
 * `ci/prepare-emulator.sh` sets up `adb reverse tcp:4533 tcp:4533` before any connected test runs,
 * so `http://localhost:4533` reaches it from inside the emulator.
 *
 * A [MockWebServer] sits in front of those bytes for one reason only: **a 429 has to be produced on
 * demand.** Making the real Navidrome emit one means configuring `Transcoding.MaxConcurrent` and
 * racing concurrent transcodes, which is flaky by construction. The status code is the only thing
 * faked here; the bytes, the decoder, the audio pipeline and the clock are all real.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayDataSourceFactoryTest {

  private lateinit var server: MockWebServer
  private lateinit var harness: PlayerHarness
  private lateinit var audio: ByteArray

  @Before
  fun setUp() {
    audio = runBlocking { fetchRealTrackBytes() }
    // Not vacuous: a zero-length body would make every playback assertion below fail in a way
    // that looks like a decoder problem. Fail here instead, where the message is true.
    assertThat(audio.size).isGreaterThan(1000)

    server = MockWebServer()
    server.start()

    val context = ApplicationProvider.getApplicationContext<Context>()
    // Through `MuPlayerFactory`, never `ExoPlayer.Builder` directly, and that is the point of this
    // block rather than an incidental tidy-up. The policy hangs off the **media source factory**,
    // not off `ExoPlayer.Builder` -- there is no `ExoPlayer.Builder.setLoadErrorHandlingPolicy` in
    // Media3 1.11.0 at all (checked against the resolved artifact, not assumed) -- and the sequence
    // that silently does nothing is easy to write: build the policy, inject it, never attach it,
    // and the player quietly keeps `DefaultLoadErrorHandlingPolicy`'s three-retries-in-five-seconds
    // while every unit test of the policy stays green.
    //
    // A test that assembled that arrangement for itself would be testing a *copy* of the production
    // wiring, and the copy is exactly what drifts: the shipping player could lose the policy and
    // `aRefusalBudgetThatRunsOutSurfacesAsAPlayerError` below would still be green. Calling the
    // production factory makes this suite's wiring the shipping wiring.
    // `PlayerConstructionTest` (JVM tier) is what stops a hand-built player coming back here.
    val playerFactory = MuPlayerFactory(
      context = context,
      dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient()),
      loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
    )
    // Built inside runOnMainSync: ExoPlayer.Builder captures the calling thread's Looper, and the
    // instrumentation thread has none. A violation throws
    // "Player is accessed on the wrong thread" -- clear, but only at the first access, which is
    // far from here.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(playerFactory.create())
    }
  }

  @After
  fun tearDown() {
    if (::harness.isInitialized) harness.release()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun realAudioPlaysAndThePositionAdvances() {
    server.enqueue(audioResponse())

    play(server.url("/stream").toString())

    // The whole point. `play()` returning proves nothing; a position past one second of a
    // five-second track proves the bytes were fetched, the container was parsed, the decoder
    // produced samples and the clock advanced.
    harness.awaitPositionAtLeast(1_000L)
    harness.awaitEnded()
    assertThat(harness.onMain { harness.player.currentPosition }).isGreaterThan(4_000L)
  }

  @Test
  fun twoRefusalsWithHttp429DoNotKillThePlayback() {
    // Navidrome 0.62.0's `Transcoding.MaxConcurrent` answers 429 when every slot is busy. Spec
    // section 4: unhandled, this looks like random playback failure.
    server.enqueue(refusal())
    server.enqueue(refusal())
    server.enqueue(audioResponse())

    play(server.url("/stream").toString())

    harness.awaitPositionAtLeast(1_000L)
    harness.assertNoPlaybackError()
    // Three requests reached the server: the two refusals and the one that carried audio. Without
    // this, a policy that gave up after the first 429 and a policy that retried correctly would be
    // told apart only by the position assertion above -- which is true but indirect.
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun aRefusalBudgetThatRunsOutSurfacesAsAPlayerError() {
    // The control that makes the previous test mean something. If 429s were being swallowed
    // rather than retried, *both* tests would pass.
    //
    // Comfortably more refusals than the budget can consume, and `Retry-After: 1` rather than
    // `0`. Both are corrections this test made after watching itself fail:
    //
    //  * With exactly six queued, MockWebServer's dispatcher **blocks** on the empty queue, the
    //    next read times out, and the error the player finally reports is a
    //    `SocketTimeoutException` with no 429 anywhere in its cause chain. The test then fails
    //    for a reason that has nothing to do with the policy.
    //  * With `Retry-After: 0` the retries are immediate, and Media3 enforces the budget on the
    //    *playback* thread (`Loader.maybeThrowError`, from `doSomeWork`) while the *loader*
    //    thread schedules the next attempt on its own -- so a seventh request goes out before the
    //    budget check runs. Measured: seven, not six. A one-second delay hands the playback
    //    thread its ordinary work cycle in between and makes the count deterministic.
    repeat(REFUSALS_ENQUEUED) { server.enqueue(refusal(retryAfterSeconds = 1)) }

    play(server.url("/stream").toString())

    // The give-up path, asserted as itself rather than as "a wait that timed out". A timeout is
    // what a player stuck retrying forever produces too, and the two must not look alike.
    val error = harness.awaitPlaybackError(timeoutMs = 20_000L)
    // The player itself is in the error state, not merely a listener that fired: the two are
    // different layers and only the first is what a media session would report to a notification.
    assertThat(harness.onMain { harness.player.playerError }).isSameAs(error)
    assertThat(harness.onMain { harness.player.playbackState }).isEqualTo(Player.STATE_IDLE)
    val chain = causeChain(error)
    assertThat(chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>())
      // The whole chain in the message, not just "was empty": this assertion's entire job is to
      // stop a timeout, a socket reset and a real refusal looking alike, and a failure that does
      // not name what it did find would put them back together again.
      .describedAs("the 429 itself, named in the error the player reports. chain=%s", chain.map { "${it.javaClass.name}: ${it.message}" })
      .isNotEmpty()
      .allSatisfy { assertThat(it.responseCode).isEqualTo(429) }

    // The budget is a number, and it is asserted as one: one initial attempt plus MAX_RETRIES.
    // A player that gave up early and one that retried forever both fail here, in opposite
    // directions, and neither can be mistaken for the other.
    assertThat(server.requestCount).isEqualTo(StreamRetryPolicy.MAX_RETRIES + 1)
  }

  @Test
  fun theUserAgentThisClientSendsIsOnTheWire() {
    server.enqueue(audioResponse())

    play(server.url("/stream").toString())
    harness.awaitPositionAtLeast(500L)

    assertThat(nextRequest().headers["User-Agent"]).isEqualTo(MuPlayDataSourceFactory.USER_AGENT)
  }

  private fun play(url: String) = harness.onMain {
    harness.player.setMediaItem(MediaItem.fromUri(url))
    harness.player.prepare()
    harness.player.play()
  }

  private fun audioResponse(): MockResponse =
    MockResponse.Builder()
      .code(200)
      .addHeader("Content-Type", "audio/mpeg")
      .addHeader("Accept-Ranges", "bytes")
      .body(Buffer().write(audio))
      .build()

  /** A 429 carrying the `Retry-After` Navidrome would send, in its delta-seconds form. */
  private fun refusal(retryAfterSeconds: Int = 0): MockResponse =
    MockResponse(code = 429, headers = Headers.headersOf("Retry-After", retryAfterSeconds.toString()))

  /**
   * The next request the player actually sent.
   *
   * The timed overload deliberately, never the no-argument one: that blocks forever on an empty
   * queue, so a regression where the player stops issuing a request at all would hang the run
   * until CI killed it and surface as infrastructure, not as this test failing. Same reasoning as
   * `SubsonicClientTest.nextRequest`.
   */
  private fun nextRequest() =
    checkNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "the player sent no request within $REQUEST_TIMEOUT_SECONDS s"
    }

  private fun causeChain(throwable: Throwable): List<Throwable> =
    generateSequence(throwable) { if (it.cause === it) null else it.cause }.toList()

  /** One real track's bytes, off the real container, through the real stream URL. */
  private suspend fun fetchRealTrackBytes(): ByteArray {
    val client = SubsonicClient(
      SubsonicCredentials(baseUrl = NAVIDROME_URL, username = "admin", password = "testpass"),
    )
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500)
      .first { it.suffix?.lowercase() == "mp3" }
    val request = Request.Builder().url(client.streamUrl(song.id, StreamFormat.Raw)).build()
    return OkHttpClient().newCall(request).execute().use { checkNotNull(it.body).bytes() }
  }

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
    const val REQUEST_TIMEOUT_SECONDS = 10L

    /**
     * Enough refusals that the server never runs out while the budget does.
     *
     * Twice the budget, deliberately loose: the number this test asserts is `server.requestCount`,
     * and that assertion is only meaningful while every request the player made was answered by a
     * refusal. A queue sized to the expected count instead makes the *dispatcher* the thing that
     * stops the player, which is a different experiment.
     */
    const val REFUSALS_ENQUEUED = 2 * StreamRetryPolicy.MAX_RETRIES + 2
  }
}
