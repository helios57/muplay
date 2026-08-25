package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.cache.Cache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MuPlayerFactory] is a delegating construction site, so this test mutates its arguments.
 *
 * A factory whose whole body forwards three injected collaborators into a builder is precisely the
 * shape where an assertion can run without being able to fail: "a player was returned" is true of a
 * factory that ignored every argument it was given. So each argument is observed at a value nothing
 * else could have produced:
 *
 *  * **`dataSourceFactory`** -- the `Call.Factory` handed to it stamps a header no other client in
 *    this process sends, and the test reads that header off the request the player actually made.
 *    A factory that ignored the injected data source and built its own `MuPlayDataSourceFactory`
 *    internally still sends the right `User-Agent`, so `User-Agent` cannot tell the two apart; this
 *    header can.
 *  * **`loadErrorPolicy`** -- observed by request count, in
 *    `MuPlayDataSourceFactoryTest.aRefusalBudgetThatRunsOutSurfacesAsAPlayerError`, which now builds
 *    its player through this same factory: `StreamRetryPolicy.MAX_RETRIES + 1` = 6 requests, where
 *    Media3's own `DefaultLoadErrorHandlingPolicy` would give 4. It is asserted there rather than
 *    duplicated here, because it needs a whole retry budget's worth of device time and there should
 *    be one place that number is written down. Its neighbour
 *    `twoRefusalsWithHttp429DoNotKillThePlayback` is **not** that evidence: two retries fit inside
 *    Media3's default budget too, so it is green either way.
 *  * **`context`** -- not observable, and honestly so. It is the sole positional argument of
 *    `ExoPlayer.Builder`, so there is no way to drop it that compiles, and the only substitution
 *    available in this process (`context.applicationContext`) is behaviourally identical.
 *
 * That there is no *second* construction site to bypass this factory is a structural claim no
 * runtime test can make; `PlayerConstructionTest` (JVM tier) makes it.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayerFactoryTest {

  private lateinit var server: MockWebServer
  private lateinit var harness: PlayerHarness

  /**
   * This test's own cache directory, not the production one. `SimpleCache` refuses a second live
   * instance on a folder another instance holds, and `MediaCacheTest` opens the production folder
   * (`cacheDir/media`) in this same process -- so sharing it would make these two suites fail each
   * other depending on run order. Same construction, same reason, as
   * `MuPlayDataSourceFactoryTest`'s.
   */
  private lateinit var cacheDir: File
  private lateinit var cache: Cache

  @Before
  fun setUp() {
    val audio = runBlocking { fetchRealTrackBytes() }
    // Not vacuous: a zero-length body would make the playback wait below fail in a way that looks
    // like a decoder problem. Fail here instead, where the message is true.
    assertThat(audio.size).isGreaterThan(1000)

    server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "audio/mpeg")
        .addHeader("Accept-Ranges", "bytes")
        .body(Buffer().write(audio))
        .build(),
    )

    val context = ApplicationProvider.getApplicationContext<Context>()
    cacheDir = File(context.cacheDir, "playerfactory-test-${System.nanoTime()}")
    cache = MediaCache.create(context, cacheDir)
    val factory = MuPlayerFactory(
      context = context,
      // The tag. An interceptor rather than a header on a request the test builds, because the
      // requests here are built by Media3's `OkHttpDataSource`, not by this test.
      dataSourceFactory = MuPlayDataSourceFactory(
        OkHttpClient.Builder()
          .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(PROBE_HEADER, PROBE_VALUE).build())
          }
          .build(),
        cache,
      ),
      loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
    )
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(factory.create())
    }
  }

  @After
  fun tearDown() {
    if (::harness.isInitialized) harness.release()
    // Released before the directory is deleted, and always: an unreleased `SimpleCache` keeps its
    // folder locked for the life of the process.
    if (::cache.isInitialized) cache.release()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun theInjectedDataSourceFactoryIsTheOneEveryByteGoesThrough() {
    harness.onMain {
      harness.player.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
      harness.player.prepare()
      harness.player.play()
    }
    // The player really played, so the request below is one the audio pipeline made rather than a
    // probe the test issued for itself.
    harness.awaitPositionAtLeast(500L)

    assertThat(nextRequest().headers[PROBE_HEADER]).isEqualTo(PROBE_VALUE)
  }

  /**
   * The next request the player actually sent.
   *
   * The timed overload deliberately, never the no-argument one: that blocks forever on an empty
   * queue, so a regression where the player stops issuing a request at all would hang the run until
   * CI killed it and surface as infrastructure, not as this test failing.
   */
  private fun nextRequest() =
    checkNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "the player sent no request within $REQUEST_TIMEOUT_SECONDS s"
    }

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
     * A header no other client in this process sends, so seeing it on the wire identifies *this*
     * `Call.Factory` and not merely "a `MuPlayDataSourceFactory`".
     */
    const val PROBE_HEADER = "X-MuPlay-Injected-Call-Factory"
    const val PROBE_VALUE = "the one this test handed to MuPlayerFactory"
  }
}
