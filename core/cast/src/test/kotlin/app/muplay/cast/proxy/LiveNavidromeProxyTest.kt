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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
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
 * ### The transcoding cache, and why nothing here depends on a cold entry
 *
 * `/rest/stream&format=mp3` behaves two ways for one URL. The **first** request for a given (track,
 * requested bitrate) is transcoded live: `Accept-Ranges: none`, chunked, no `Content-Length`. Every
 * request after it is served from Navidrome's transcoding cache as an ordinary file, with a length
 * and a working `Range`. The cache lives in the container's writable layer, so it is cold in CI and
 * warm on the long-lived shared container here -- a test pinned to one bitrate passes on its first
 * run and fails on its second.
 *
 * The obvious fix is to search for an unused bitrate first, and it **does not work**, for a reason
 * measured in this task rather than assumed: a `HEAD` on an uncached transcode answers
 * `Accept-Ranges: none` with no length -- it reports "cold" correctly -- and **starts a background
 * transcode that has populated the cache about a second later**. Two `HEAD`s issued back to back
 * both say cold; the same URL a few hundred milliseconds afterwards is warm. So any probe that
 * reports a cold entry has warmed the entry it reported, and the assertion that follows races the
 * transcoder. That is not hypothetical: the version of this class that probed with `HEAD` passed
 * once and then failed three runs running with `expected: 502 but was: 200`.
 *
 * Searching *through the proxy* instead -- so that the search's own response is the observation --
 * is correct and is worse: a run that finds nothing has requested every bitrate below the source,
 * which caches every one of them. Those entries are a **shared, exhaustible resource** on this
 * single-instance container, and `:core:network`'s `LiveNavidromeTest.coldTranscode` needs them
 * too. One such sweep here left one of that suite's three candidate tracks with no cold bitrate at
 * all.
 *
 * So no test in this class depends on a cold transcode. The proxy's `null`-length branch is
 * exercised against a **different** real Navidrome response that carries no `Content-Range` -- an
 * unauthenticated stream URL -- which is stable, costs nothing, and is a sharper assertion besides.
 * That a live transcode declares no length stays pinned where it already was, in `:core:network`'s
 * `coldTranscode`, in this same CI job.
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
  fun `a range at or past the end of a real track is 416 with the real length`() {
    val direct = fetchDirect(streamUrl)

    val atTheEnd = get(published.path, "bytes=${direct.size}-")
    val wellPast = get(published.path, "bytes=${direct.size + 1000}-")
    val lastByte = get(published.path, "bytes=${direct.size - 1}-")

    // The boundary from both sides, and the first offset is the one that matters: `>=` against `>`
    // is the classic off-by-one, and it changes the answer at **exactly** `firstByte == length` and
    // nowhere else. A test that only asked for `length + 1000` is green against the defect -- this
    // one was, and the mutation run that proved it is in task-6-report.md.
    assertThat(atTheEnd.code).describedAs("Range: bytes=%d-", direct.size).isEqualTo(416)
    assertThat(atTheEnd.head.headers["Content-Range"]).isEqualTo("bytes */${direct.size}")
    assertThat(wellPast.code).isEqualTo(416)
    assertThat(wellPast.head.headers["Content-Range"]).isEqualTo("bytes */${direct.size}")
    // ...and one byte earlier is a 206 carrying the file's real last byte, so the 416s above are a
    // boundary and not this proxy's answer to every open-ended range.
    assertThat(lastByte.code).isEqualTo(206)
    assertThat(lastByte.body).isEqualTo(direct.copyOfRange(direct.size - 1, direct.size))
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
   * The `null`-length branch, against a real Navidrome response that really has no length.
   *
   * A credential that no longer works is not a hypothetical: a Subsonic token is salted per client,
   * and a password change invalidates every URL a cast session has published at once. Navidrome
   * answers such a request with a **JSON error document** -- 200, `Content-Type: application/json`,
   * a real `Content-Length`, and no `Content-Range` at all.
   *
   * Which is what makes this sharp rather than merely convenient. `totalLength` reads the
   * `Content-Range` of a one-byte probe, so it answers `null` here and the renderer gets a 502. A
   * probe that read `Content-Length` instead -- the obvious implementation -- would answer 190, and
   * this proxy would hand a speaker a JSON error document as `audio/mpeg`, with a length that
   * agreed with itself and nothing reported anywhere. Measured: that mutation reddens this test.
   */
  @Test
  fun `a stream url that no longer authenticates is a 502, not a json error document served as audio`() {
    val unauthenticated = streamUrl.toHttpUrl().newBuilder()
      .removeAllQueryParameters("u").removeAllQueryParameters("t").removeAllQueryParameters("s")
      .build().toString()

    // What the server really answers, pinned here so the 502 below rests on a measurement rather
    // than on an assumption about how Navidrome refuses.
    OkHttpClient().newCall(Request.Builder().url(unauthenticated).header("Range", "bytes=0-0").build())
      .execute().use { refusal ->
        assertThat(refusal.code).isEqualTo(200)
        assertThat(refusal.header("Content-Type")).contains("json")
        assertThat(refusal.header("Content-Length")?.toLong()).isGreaterThan(0L)
        assertThat(refusal.header("Content-Range")).isNull()
      }

    val item = registry.publish(unauthenticated, ServedMedia.of(song.suffix, StreamFormat.Raw))
    val response = get(item.path, null)

    // 502 specifically, and an empty body: not a 200 carrying the error document, and not a 503,
    // which would tell the renderer to come back and try the same broken URL again.
    assertThat(response.code).isEqualTo(502)
    assertThat(response.body).isEmpty()
    // ...and the control, on this same proxy and this same run: the authenticated URL is a 200.
    assertThat(get(published.path, null).code).isEqualTo(200)
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

  private companion object {
    const val BASE_URL = "http://localhost:4533"

    /** `ci/configure-libraries.sh` makes library 1 "Music". */
    const val MUSIC_LIBRARY_ID = 1

    /** A seeded fixture is ~40 KB; this is headroom, not a measurement. */
    const val MAX_TRACK_BYTES = 8 * 1024 * 1024
  }
}
