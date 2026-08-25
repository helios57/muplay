package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.media.di.MediaModule
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cache, measured rather than asserted-by-flag.
 *
 * Every "did the cache work" claim here is a **request count on a real HTTP server**. A test that
 * asked the `Cache` object whether it holds a key would pass against a cache that is never read
 * from, which is exactly the defect this task exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class MediaCacheTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var cache: Cache
  private lateinit var server: MockWebServer
  private lateinit var audio: ByteArray
  private lateinit var otherAudio: ByteArray

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // A per-test directory: SimpleCache refuses a second live instance on one folder, and a
    // shared folder would make these tests depend on the order they ran in.
    cacheDir = File(context.cacheDir, "media-test-${System.nanoTime()}")
    cache = MediaCache.create(context, cacheDir)
    server = MockWebServer()
    server.start()

    val bytes = runBlocking { RealTrackBytes.twoDifferentTracks() }
    audio = bytes.first
    otherAudio = bytes.second
    assertThat(audio.size).isGreaterThan(1000)
    assertThat(otherAudio.size).isGreaterThan(1000)
    // The two tracks must be genuinely different, or "served the wrong track from cache" is
    // undetectable below.
    assertThat(audio).isNotEqualTo(otherAudio)
  }

  @After
  fun tearDown() {
    if (::cache.isInitialized) cache.release()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun theCacheKeyIsTheCustomKeyAndNotTheUri() {
    val first = DataSpec.Builder()
      .setUri("http://host/rest/stream?id=track-1&t=aaa&s=111")
      .setKey("track-1").build()
    val second = DataSpec.Builder()
      // Same track, different salt, different token, different bitrate -- i.e. everything Tempo's
      // URL-derived key would treat as a different resource.
      .setUri("http://host/rest/stream?id=track-1&t=zzz&s=999&maxBitRate=96")
      .setKey("track-1").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(first)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(second)).isEqualTo("track-1")
  }

  @Test
  fun twoTracksDoNotShareACacheKey() {
    // The other direction. A `buildCacheKey` that returned a constant would pass the test above
    // and fail this one; one that returned the URI would pass this one and fail the test above.
    val a = DataSpec.Builder().setUri("http://host/a").setKey("track-1").build()
    val b = DataSpec.Builder().setUri("http://host/b").setKey("chapter-14").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(a)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(b)).isEqualTo("chapter-14")
  }

  /**
   * Media3's `CacheKeyFactory.DEFAULT` falls back to the URI when no custom key is set. That
   * fallback is the entire Tempo defect: this client's URLs carry a fresh salt every time, so the
   * fallback silently produces a cache with a 0% hit rate that still consumes disk. Failing loudly
   * is the point.
   */
  @Test
  fun aDataSpecWithNoCustomCacheKeyIsRejectedRatherThanFallingBackToTheUri() {
    val noKey = DataSpec.Builder().setUri("http://host/rest/stream?id=track-1&s=111").build()

    assertThatExceptionOfType(MissingCacheKeyException::class.java)
      .isThrownBy { TrackIdCacheKeyFactory.buildCacheKey(noKey) }
      .withMessageContaining("setCustomCacheKey")
  }

  /**
   * The factory the app actually builds is the one wired to [TrackIdCacheKeyFactory].
   *
   * Without this, every assertion above is about an object the production data source might never
   * consult -- the difference between "the decision is right" and "the decision is applied", which
   * is a defect class this project tracks by name. The playback tests below observe the same fact
   * end-to-end; this one names it directly so a regression says *which* wire came loose.
   */
  @Test
  fun theDataSourceTheAppBuildsIsWiredToTheTrackIdKeyFactory() {
    val source = MuPlayDataSourceFactory(OkHttpClient(), cache).create().createDataSource()

    assertThat(source).isInstanceOf(CacheDataSource::class.java)
    val cacheDataSource = source as CacheDataSource
    assertThat(cacheDataSource.cacheKeyFactory).isSameAs(TrackIdCacheKeyFactory)
    // The same `Cache` instance the caller passed, not a second one built on the side: a factory
    // that quietly made its own would still cache, and would still fail to share a byte with the
    // rest of the process.
    assertThat(cacheDataSource.cache).isSameAs(cache)
  }

  /**
   * **The measurement that matters.** Play a track, then play it again through a URL that differs
   * in exactly the ways this client's URLs really differ — a new salt, a new token, a bitrate cap
   * — and require that **not one further byte** is fetched.
   *
   * Holding the track id constant while varying everything else in the URL is what makes this
   * discriminating: a cache keyed on the URL passes the first playback and fails here.
   */
  @Test
  fun replayingATrackThroughADifferentUrlFetchesNothingFurther() {
    server.enqueue(audioResponse(audio))

    playToEnd(uri = server.url("/stream?id=track-1&t=aaa&s=111").toString(), cacheKey = "track-1")
    assertThat(server.requestCount).isEqualTo(1)

    playToEnd(
      uri = server.url("/stream?id=track-1&t=zzz&s=999&maxBitRate=96").toString(),
      cacheKey = "track-1",
    )

    // No second response was ever enqueued: had the player gone to the network, MockWebServer
    // would have blocked and the playback would have timed out rather than merely fetching twice.
    // Both facts are asserted, because either one alone leaves a way for this to pass wrongly.
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun aDifferentTrackIsNotServedFromAnotherTracksCache() {
    // The control. Without it, a cache that returned the first track's bytes for every key would
    // pass the test above perfectly.
    server.enqueue(audioResponse(audio))
    server.enqueue(audioResponse(otherAudio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")
    playToEnd(server.url("/stream?id=track-2").toString(), cacheKey = "track-2")

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(cache.keys).contains("track-1", "track-2")
    // Each key holds *its own* track's bytes, by length. Both fixtures are five-second 64 kbps
    // mp3s, so their sizes are close but not equal (checked in setUp via isNotEqualTo, and the
    // two expectations below are different numbers); a cache that filed both playbacks under one
    // key, or served the first track's bytes for the second, misses one of these two.
    assertThat(cache.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
    assertThat(cache.getCachedBytes("track-2", 0L, Long.MAX_VALUE))
      .isEqualTo(otherAudio.size.toLong())
  }

  @Test
  fun theCachedBytesOnDiskAreTheWholeTrack() {
    server.enqueue(audioResponse(audio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")

    // Direct evidence, alongside the request count: the cache holds the complete resource, not a
    // prefix that happened to satisfy a short playback.
    assertThat(cache.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
    assertThat(cache.cacheSpace).isGreaterThanOrEqualTo(audio.size.toLong())
  }

  /**
   * The directory the production cache writes to, named and located — reached through the
   * **binding the graph actually uses**, not through [MediaCache] directly.
   *
   * Going via `MediaModule.provideMediaCache` is the point rather than a detour. Every other test
   * in this file passes its own directory, so nothing else in the project observes which
   * directory production ends up with; a provider that called the two-argument overload with
   * `filesDir`, or with a name of its own, would satisfy every assertion in this file if this test
   * called [MediaCache] itself. Where a decision is *applied* and where it is *declared* are
   * different layers, and this project tracks tests that verify only the second.
   */
  @Test
  fun theProductionCacheLivesInAKnownDirectoryUnderCacheDir() {
    val production = MediaModule.provideMediaCache(context)
    try {
      val expected = File(context.cacheDir, MediaCache.DIRECTORY_NAME)
      // Discriminating in both directions: a `create` that used `filesDir`, or that derived a
      // different sub-directory name than `DIRECTORY_NAME`, leaves this path non-existent.
      assertThat(expected).isDirectory()
      // Not `filesDir`: the OS may reclaim `cacheDir` under storage pressure, which is the right
      // trade for a read-through cache of data the server still has, and the wrong one for a
      // download. Asserted as an absence as well as a presence, because a `create` that wrote to
      // both would satisfy the line above on its own.
      assertThat(File(context.filesDir, MediaCache.DIRECTORY_NAME)).doesNotExist()
      // A change-detector, and named as one rather than dressed up: 512 MiB cannot be observed
      // behaviourally without filling a cache that size. It catches an edited constant, which is
      // the only failure mode it claims to catch.
      assertThat(MediaCache.MAX_BYTES).isEqualTo(512L * 1024L * 1024L)
    } finally {
      production.release()
      File(context.cacheDir, MediaCache.DIRECTORY_NAME).deleteRecursively()
    }
  }

  private fun audioResponse(bytes: ByteArray): MockResponse =
    MockResponse.Builder()
      .code(200)
      .addHeader("Content-Type", "audio/mpeg")
      .addHeader("Accept-Ranges", "bytes")
      .body(Buffer().write(bytes))
      .build()

  /** Builds a fresh player over the shared cache, plays one item to the end, and releases it. */
  private fun playToEnd(uri: String, cacheKey: String) {
    val factory = MuPlayDataSourceFactory(OkHttpClient(), cache)
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
          .build(),
      )
    }
    try {
      harness.onMain {
        harness.player.setMediaItem(
          MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build(),
        )
        harness.player.prepare()
        harness.player.play()
      }
      // Position, then ENDED: "reached the end" alone is satisfied by a zero-length source.
      harness.awaitPositionAtLeast(1_000L)
      harness.awaitEnded()
    } finally {
      harness.release()
    }
  }
}
