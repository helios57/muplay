package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.CastHttpResponse
import app.muplay.cast.http.HttpHeaders
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.io.Closeable
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The **whole proxy** -- registry, upstream and server -- in front of the real, pinned
 * `deluan/navidrome:0.63.2` container from `ci/navidrome.compose.yml`.
 *
 * `@Tag("live")`, so it is excluded from every ordinary `test` task (`Testing.kt`'s
 * `configureJUnit5` calls `excludeTags("live")` project-wide) and included only by
 * `:core:cast:liveNavidromeTest`, which the `live-navidrome` job in `.github/workflows/pr.yml`
 * runs after starting the container and running `ci/configure-libraries.sh`.
 *
 * What this class is for, and what `MediaProxyServerTest` cannot do: every assertion there is
 * against an upstream this project wrote, so all of them are consistent with a proxy that agrees
 * with a fake and disagrees with Navidrome. Here the bytes come out of a real server over a real
 * TLS-less HTTP connection, and the strongest assertion available is used throughout -- **not**
 * "a 206 came back" but "these are the same bytes as a direct fetch". A proxy that answered 206
 * with a correct `Content-Range` and streamed from byte 0 passes every status assertion ever
 * written and fails the byte-exact ones here.
 *
 * ### The transcoding cache, which makes a fixed-bitrate assertion flaky
 *
 * `/rest/stream&format=mp3` behaves two ways for one URL. The **first** request for a given (track,
 * requested bitrate) is transcoded live: `Accept-Ranges: none`, chunked, no `Content-Length`. Every
 * request after it is served from Navidrome's transcoding cache as an ordinary file, with a length
 * and a working `Range`. The cache lives in the container's writable layer, so it is cold in CI and
 * warm on the long-lived shared container here -- a test pinned to one bitrate passes on its first
 * run and fails on its second. [coldTranscodeUrl] searches for an unused one instead, exactly as
 * `:core:network`'s `LiveNavidromeTest.coldTranscode` does, and it searches with `HEAD`, which was
 * measured **not** to populate the cache: the search costs nothing, and only the assertion itself
 * consumes an entry.
 *
 * Stream URLs carry `u`, `t` and `s` and are never asserted on, printed, or put in a message.
 */
@Tag("live")
class LiveNavidromeProxyTest {

  private val closeables = mutableListOf<Closeable>()
  private val registry = ProxyRegistry()
  private val server = MediaProxyServer(
    OkHttpProxyUpstream(OkHttpClient()),
    registry,
    InetAddress.getLoopbackAddress(),
  ).also { closeables += it; it.start() }

  private val client = SubsonicClient(
    SubsonicCredentials(baseUrl = BASE_URL, username = "admin", password = "testpass"),
  )

  private val song = runBlocking { client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500) }
    .first { it.suffix.equals("mp3", ignoreCase = true) }

  private val streamUrl = client.streamUrl(song.id, StreamFormat.Raw)

  private val published = registry.publish(streamUrl, ServedMedia.of(song.suffix, StreamFormat.Raw))

  @AfterEach
  fun tearDown() {
    closeables.forEach { runCatching { it.close() } }
  }

  @Test
  fun `the proxy relays a real navidrome track byte for byte`() {
    // The strongest available assertion: not "a 200 came back" but "these are the same bytes".
    val direct = fetchDirect(streamUrl)
    assertThat(direct.size).isGreaterThan(1000) // not vacuous against an empty 200

    val throughProxy = get(published.path, null)

    assertThat(throughProxy.code).isEqualTo(200)
    assertThat(throughProxy.body).isEqualTo(direct)
    assertThat(throughProxy.head.headers.contentLength()).isEqualTo(direct.size.toLong())
    assertThat(throughProxy.head.headers["Content-Type"]).isEqualTo("audio/mpeg")
  }

  @Test
  fun `a ranged request through the proxy returns the byte-exact tail of the real file`() {
    val direct = fetchDirect(streamUrl)
    val offset = direct.size / 2

    val tail = get(published.path, "bytes=$offset-")

    assertThat(tail.code).isEqualTo(206)
    assertThat(tail.head.headers["Content-Range"])
      .isEqualTo("bytes $offset-${direct.size - 1}/${direct.size}")
    // Byte-exact, not merely the right length: a proxy that answered 206 with the START of the
    // file would pass a length check and make every seek jump back to the beginning.
    assertThat(tail.body).isEqualTo(direct.copyOfRange(offset, direct.size))
  }

  @Test
  fun `a middle range through the proxy is byte-exact too`() {
    // A second, non-tail range, so the offset arithmetic is observed at two values.
    val direct = fetchDirect(streamUrl)

    val middle = get(published.path, "bytes=1000-1999")

    assertThat(middle.code).isEqualTo(206)
    assertThat(middle.head.headers["Content-Range"]).isEqualTo("bytes 1000-1999/${direct.size}")
    assertThat(middle.body).isEqualTo(direct.copyOfRange(1000, 2000))
  }

  @Test
  fun `a suffix range through the proxy is the real file's real last bytes`() {
    // The third offset, and the one whose arithmetic runs the other way round: `bytes=-500` is
    // resolved against a length this proxy learned from the server, not from the request.
    val direct = fetchDirect(streamUrl)

    val suffix = get(published.path, "bytes=-500")

    assertThat(suffix.code).isEqualTo(206)
    assertThat(suffix.head.headers["Content-Range"])
      .isEqualTo("bytes ${direct.size - 500}-${direct.size - 1}/${direct.size}")
    assertThat(suffix.body).isEqualTo(direct.copyOfRange(direct.size - 500, direct.size))
  }

  @Test
  fun `a range past the end of a real track is 416 with the real length`() {
    val direct = fetchDirect(streamUrl)

    val past = get(published.path, "bytes=${direct.size + 1000}-")

    assertThat(past.code).isEqualTo(416)
    assertThat(past.head.headers["Content-Range"]).isEqualTo("bytes */${direct.size}")
  }

  @Test
  fun `the length probe against a real navidrome returns the real length`() {
    // The mechanism `totalLength` depends on, against the server it depends on.
    assertThat(OkHttpProxyUpstream(OkHttpClient()).totalLength(streamUrl))
      .isEqualTo(fetchDirect(streamUrl).size.toLong())
  }

  /**
   * **Measured, then pinned.** Whether `/rest/stream` answers a `HEAD` at all is not something this
   * plan gets to assume, and "we chose the robust option" and "we never checked" look identical in
   * a green build.
   *
   * Measured against `deluan/navidrome:0.63.2` on 2026-08-25, `format=raw`: **200**, with an
   * accurate `Content-Length` and `Accept-Ranges: bytes` -- so a `HEAD`-based length probe would
   * have worked. `OkHttpProxyUpstream` uses the one-byte range probe anyway: it costs one byte, it
   * is right whatever this answers, and it relies only on RFC 7233 behaviour spec section 4 already
   * verified. What this test buys is that a change in Navidrome's answer is a red build here rather
   * than a discovery during a cast.
   */
  @Test
  fun `what a HEAD on rest slash stream really does, pinned`() {
    val head = OkHttpClient().newCall(Request.Builder().url(streamUrl).head().build()).execute()

    head.use {
      assertThat(it.code).describedAs("Navidrome's answer to HEAD /rest/stream").isEqualTo(200)
      assertThat(it.header("Content-Length")?.toLong())
        .describedAs("...and the length it declares there")
        .isEqualTo(fetchDirect(streamUrl).size.toLong())
      assertThat(it.header("Accept-Ranges")).isEqualTo("bytes")
    }
  }

  /**
   * The other half of the same measurement, and the reason the proxy has a 502 branch at all.
   *
   * A **live** transcode answers 200 with `Accept-Ranges: none` and no length anywhere -- measured
   * here for `HEAD` as well as for the ranged `GET` the proxy's probe issues. Serving that as a 200
   * with no `Content-Length` would give a renderer no way to know when the track ends.
   */
  @Test
  fun `a live transcode has no length, so the proxy refuses it rather than truncating it`() {
    val cold = coldTranscodeUrl()
    val transcoded = registry.publish(cold, ServedMedia.of(song.suffix, StreamFormat.Mp3(1)))

    // The measurement the 502 rests on, taken before the proxy consumes this cache entry.
    OkHttpClient().newCall(Request.Builder().url(cold).head().build()).execute().use {
      assertThat(it.header("Accept-Ranges")).isEqualTo("none")
      assertThat(it.header("Content-Length")).isNull()
    }

    assertThat(get(transcoded.path, null).code).isEqualTo(502)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun get(path: String, range: String?): CastHttpResponse =
    CastHttpClient(maxBodyBytes = MAX_TRACK_BYTES).exchange(
      URI("http://127.0.0.1:${server.port}$path"),
      "GET",
      range?.let { HttpHeaders.of("Range" to it) } ?: HttpHeaders.EMPTY,
    )

  /**
   * The track's bytes, straight from Navidrome, with no proxy anywhere.
   *
   * A plain `OkHttpClient` rather than anything in this module: the point of comparing against it
   * is that it shares no code with the thing under test.
   */
  private fun fetchDirect(url: String): ByteArray =
    OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
      .use { it.body.bytes() }

  /**
   * A `format=mp3` URL for a bitrate this container has not transcoded yet.
   *
   * Searched with `HEAD`, which was measured not to populate the transcoding cache: a warm entry
   * answers `Accept-Ranges: bytes` with a length, a cold one answers `Accept-Ranges: none` with
   * neither, and the `HEAD` leaves it cold either way. Only bitrates **below** the fixture's own
   * are searched: at or above it Navidrome selects "no cap", and every such request shares one
   * cache entry, so the "one entry per requested bitrate" rule this search depends on does not
   * hold there.
   */
  private fun coldTranscodeUrl(): String {
    (1 until FIXTURE_BITRATE_KBPS).shuffled().forEach { kbps ->
      val url = client.streamUrl(song.id, StreamFormat.Mp3(kbps))
      val cold = OkHttpClient().newCall(Request.Builder().url(url).head().build()).execute()
        .use { it.header("Accept-Ranges") == "none" }
      if (cold) return url
    }
    return fail(
      "no bitrate below $FIXTURE_BITRATE_KBPS kbps is still an uncached transcode of this track. " +
        "Either this container has cached every one of them (recreate it: the transcoding cache " +
        "lives in the container's writable layer), or Navidrome no longer streams a first-time " +
        "transcode unseekably -- in which case this proxy's 502 branch has lost the behaviour it " +
        "exists for, and the format policy should be revisited rather than this test relaxed.",
    )
  }

  private companion object {
    const val BASE_URL = "http://localhost:4533"

    /** `ci/configure-libraries.sh` makes library 1 "Music". */
    const val MUSIC_LIBRARY_ID = 1

    /** `ci/seed-fixtures.sh` encodes the music fixtures at 64 kbps. */
    const val FIXTURE_BITRATE_KBPS = 64

    /** A seeded fixture is ~40 KB; this is headroom, not a measurement. */
    const val MAX_TRACK_BYTES = 8 * 1024 * 1024
  }
}
