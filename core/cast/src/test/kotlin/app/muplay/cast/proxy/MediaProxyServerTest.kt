package app.muplay.cast.proxy

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.MimeAgreement
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.CastHttpResponse
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.net.LocalNetworkOnly
import app.muplay.model.StreamFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The proxy, over a **real socket**, answered by the module's own real HTTP client.
 *
 * The upstream is a fake, but not a permissive one: [SliceUpstream] honours the range it is given
 * for real. That is the point -- an upstream that returned the whole body for every range would
 * make every 206 body assertion below pass against a proxy that ignored `Range` too, which is
 * precisely the defect this task exists to prevent.
 *
 * And `bytes=0-` proves nothing on its own: it is served identically by a correct implementation
 * and by one that ignores the header, so it appears here only as one row of a table whose other
 * ten rows do not.
 */
class MediaProxyServerTest {

  /** Not a constant byte: a slice of this is checkable, and a slice of `ByteArray(1000)` is not. */
  private val content = ByteArray(1000) { (it % 251).toByte() }

  private val closeables = mutableListOf<Closeable>()
  private val registry = ProxyRegistry()
  private val upstream = SliceUpstream(content)
  private val server = start(upstream)
  private val published = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))

  @AfterEach
  fun tearDown() {
    closeables.forEach { runCatching { it.close() } }
  }

  // ---- the table ------------------------------------------------------------------------------

  @Test
  fun `the eleven range cases produce exactly the documented status, content-range and bytes`() {
    // The whole table in one place, asserted as an exact list. Eleven separate tests would let a
    // reviewer miss that a row is absent; this cannot.
    val cases = listOf(
      null to Triple(200, null, content),
      "bytes=0-" to Triple(206, "bytes 0-999/1000", content),
      "bytes=100-199" to Triple(206, "bytes 100-199/1000", content.copyOfRange(100, 200)),
      "bytes=999-" to Triple(206, "bytes 999-999/1000", content.copyOfRange(999, 1000)),
      "bytes=-1" to Triple(206, "bytes 999-999/1000", content.copyOfRange(999, 1000)),
      "bytes=-500" to Triple(206, "bytes 500-999/1000", content.copyOfRange(500, 1000)),
      "bytes=0-99999" to Triple(206, "bytes 0-999/1000", content),
      "bytes=1000-" to Triple(416, "bytes */1000", ByteArray(0)),
      "bytes=-0" to Triple(416, "bytes */1000", ByteArray(0)),
      "bytes=abc" to Triple(200, null, content),
      "bytes=0-0,10-20" to Triple(200, null, content),
    )

    cases.forEach { (header, expected) ->
      val (status, contentRange, body) = expected
      val response = get(published.path, header)

      assertThat(response.code).describedAs("status for Range: %s", header).isEqualTo(status)
      assertThat(response.head.headers["Content-Range"])
        .describedAs("Content-Range for Range: %s", header).isEqualTo(contentRange)
      assertThat(response.body).describedAs("body for Range: %s", header).isEqualTo(body)
      assertThat(response.head.headers.contentLength())
        .describedAs("Content-Length for Range: %s", header).isEqualTo(body.size.toLong())
    }
  }

  @Test
  fun `a 206 body is the bytes that were asked for and not the bytes at the start of the file`() {
    // The table above already asserts this, and it is worth its own test because it is the ONE
    // defect a status-code assertion cannot see: a proxy that answers 206 with the right
    // Content-Range and streams from byte 0 makes every seek land at the beginning of the track
    // with nothing reported anywhere. Two offsets, neither of them 0.
    assertThat(get(published.path, "bytes=250-259").body).isEqualTo(content.copyOfRange(250, 260))
    assertThat(get(published.path, "bytes=700-709").body).isEqualTo(content.copyOfRange(700, 710))
    // ...and the upstream really was asked for those bytes, rather than for the whole file twice.
    assertThat(upstream.opened).containsExactly(ByteRange(250, 259), ByteRange(700, 709))
  }

  // ---- headers --------------------------------------------------------------------------------

  @Test
  fun `every response advertises byte ranges, because protocolInfo promised them`() {
    // `DLNA.ORG_OP=01` in `ServedMedia.protocolInfo` tells the renderer it may seek by byte. This
    // is the other half of that promise, and the two are asserted against each other here so the
    // pair cannot drift.
    assertThat(get(published.path, null).head.headers["Accept-Ranges"]).isEqualTo("bytes")
    assertThat(get(published.path, "bytes=100-199").head.headers["Accept-Ranges"]).isEqualTo("bytes")
    assertThat(get(published.path, "bytes=1000-").head.headers["Accept-Ranges"]).isEqualTo("bytes")
    assertThat(published.served.protocolInfo).contains("DLNA.ORG_OP=01")
  }

  @Test
  fun `the content type is the served mime type, at two different formats`() {
    val mp3 = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))
    val flac = registry.publish(UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw))

    assertThat(get(mp3.path, null).head.headers["Content-Type"]).isEqualTo("audio/mpeg")
    assertThat(mp3.path).endsWith(".mp3")
    assertThat(mp3.served.protocolInfo).contains(":audio/mpeg:")

    assertThat(get(flac.path, null).head.headers["Content-Type"]).isEqualTo("audio/flac")
    assertThat(flac.path).endsWith(".flac")
    assertThat(flac.served.protocolInfo).contains(":audio/flac:")
  }

  @Test
  fun `the url extension, the protocolInfo and the served content-type agree, for every format`() {
    // Task 4's three-way invariant, closed here with the served leg coming off a real socket.
    // Through `MimeAgreement` rather than as a fourth statement of the rule, and the reason is the
    // reason that class exists: it re-derives each leg from the artifact its own party reads -- the
    // extension of the `<res>` URL, the `protocolInfo` attribute parsed back out of the rendered
    // document, and this response's `Content-Type` header -- so the three arrive as unrelated
    // strings. An assertion comparing `served.mimeType` with `served.protocolInfo` compares one
    // object with itself and is blind to exactly the case where two legs agree and the third does
    // not.
    ServedMedia.rawTypes.values.distinct().forEach { served ->
      val item = registry.publish(UPSTREAM, served)
      val response = get(item.path, null)

      assertThat(MimeAgreement.disagreements(didlFor(item), response.head.headers["Content-Type"]))
        .describedAs("three-way agreement for .%s", served.fileExtension)
        .isEmpty()
    }
  }

  @Test
  fun `a mime agreement failure is visible through this same assertion`() {
    // The control for the sweep above, which would be vacuous if `disagreements` returned an empty
    // list whatever it was given. The proxy serves what the registry published, so the way to make
    // the legs disagree is to describe one item and serve another.
    val flac = registry.publish(UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw))
    val mp3 = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))

    assertThat(MimeAgreement.disagreements(didlFor(flac), get(mp3.path, null).head.headers["Content-Type"]))
      .isNotEmpty()
  }

  // ---- HEAD -----------------------------------------------------------------------------------

  @Test
  fun `a HEAD returns the same headers as the GET and no body`() {
    val get = get(published.path, null)
    val head = request("HEAD", published.path, null)

    assertThat(head.code).isEqualTo(200)
    assertThat(head.body).isEmpty()
    // The exact header list, so a HEAD that omitted Content-Length -- which is the whole reason a
    // renderer sends one -- fails here.
    assertThat(head.head.headers.names).isEqualTo(get.head.headers.names)
    assertThat(head.head.headers.contentLength()).isEqualTo(1000L)
    assertThat(head.head.headers["Content-Type"]).isEqualTo(get.head.headers["Content-Type"])
    assertThat(head.head.headers["Accept-Ranges"]).isEqualTo("bytes")
  }

  @Test
  fun `a ranged HEAD reports the range's length without sending it`() {
    val head = request("HEAD", published.path, "bytes=100-199")

    assertThat(head.code).isEqualTo(206)
    assertThat(head.head.headers["Content-Range"]).isEqualTo("bytes 100-199/1000")
    assertThat(head.head.headers.contentLength()).isEqualTo(100L)
    assertThat(head.body).isEmpty()
  }

  @Test
  fun `a HEAD does not open the upstream body`() {
    // A HEAD that streamed the file to discard it would cost a whole track of bandwidth per probe,
    // and Sonos probes before every track.
    upstream.opened.clear()

    request("HEAD", published.path, null)
    request("HEAD", published.path, "bytes=0-499")

    assertThat(upstream.opened).isEmpty()
    // ...and the control, so "opened nothing" is a discrimination rather than an upstream nobody
    // ever reaches.
    get(published.path, "bytes=0-499")
    assertThat(upstream.opened).containsExactly(ByteRange(0, 499))
  }

  // ---- refusals -------------------------------------------------------------------------------

  @Test
  fun `an unknown token is 404 and a revoked one stops working`() {
    // The rejection paths, and the second one in both directions.
    assertThat(get("/media/00000000000000000000000000000000.mp3", null).code).isEqualTo(404)

    assertThat(get(published.path, null).code).isEqualTo(200)
    registry.revoke(published.token)
    assertThat(get(published.path, null).code).isEqualTo(404)
  }

  @Test
  fun `a path traversal attempt is 404 and reaches no upstream`() {
    listOf("/../../etc/passwd", "/media/../../etc/passwd", "/media/", "/").forEach { path ->
      assertThat(get(path, null).code).describedAs("status for %s", path).isEqualTo(404)
    }
    assertThat(upstream.opened).isEmpty()
  }

  @Test
  fun `a method other than GET or HEAD is 405 and says what is allowed`() {
    val response = request("POST", published.path, null)

    assertThat(response.code).isEqualTo(405)
    assertThat(response.head.headers["Allow"]).isEqualTo("GET, HEAD")
    assertThat(upstream.opened).isEmpty()
  }

  @Test
  fun `a malformed request line is answered 400 rather than dropped`() {
    // Written straight onto a socket, because CastHttpClient cannot produce a malformed request.
    assertThat(rawExchange("GARBAGE\r\n\r\n")).startsWith("HTTP/1.1 400 ")
    assertThat(server.requestLog).containsExactly(ProxyRequest("?", null, null, 400))
  }

  @Test
  fun `a throttled upstream becomes 503 with a retry-after, not 502`() {
    // Spec section 4: Navidrome 0.62.0 added `Transcoding.MaxConcurrent`, and an unhandled 429
    // "looks like random playback failure". 503 tells the renderer to try again; 502 tells it the
    // resource is broken, and a renderer that believes that stops.
    val throttled = object : ProxyUpstream {
      override fun totalLength(url: String): Long = throw UpstreamThrottledException(retryAfterSeconds = 7)
      override fun open(url: String, range: ByteRange): InputStream = throw UpstreamThrottledException(null)
    }

    val response = get(published.path, null, start(throttled))

    assertThat(response.code).isEqualTo(503)
    assertThat(response.head.headers["Retry-After"]).isEqualTo("7")
  }

  @Test
  fun `a throttled upstream that named no delay still answers 503, with no retry-after`() {
    // The other arm of the same header. Without this, `Retry-After` could be a constant.
    val throttled = object : ProxyUpstream {
      override fun totalLength(url: String): Long = throw UpstreamThrottledException(retryAfterSeconds = null)
      override fun open(url: String, range: ByteRange): InputStream = throw UpstreamThrottledException(null)
    }

    val response = get(published.path, null, start(throttled))

    assertThat(response.code).isEqualTo(503)
    assertThat(response.head.headers["Retry-After"]).isNull()
  }

  @Test
  fun `an upstream that cannot supply a length is 502 rather than a truncated 200`() {
    // A live transcode has no Content-Length (spec section 4). Serving it as a 200 with no length
    // would give the renderer no way to know when the track ends, and Sonos would cut it short.
    val lengthless = object : ProxyUpstream {
      override fun totalLength(url: String): Long? = null
      override fun open(url: String, range: ByteRange): InputStream = ByteArray(0).inputStream()
    }

    assertThat(get(published.path, null, start(lengthless)).code).isEqualTo(502)
  }

  @Test
  fun `an upstream that stops early ends the response instead of waiting for bytes that never come`() {
    // A track deleted mid-cast, a Navidrome restart, a 429 that arrives after the head has already
    // gone out: the origin's stream ends before the range it promised. The relay loop must notice
    // and stop -- a loop that only counted down `remaining` would block on a stream at EOF and
    // hold the connection (and its thread) until the renderer gave up.
    val truncating = object : ProxyUpstream {
      override fun totalLength(url: String): Long = content.size.toLong()
      override fun open(url: String, range: ByteRange): InputStream =
        content.copyOfRange(0, 10).inputStream()
    }

    val response = get(published.path, null, start(truncating))

    // The head is honest about what was promised -- it was written before the upstream failed --
    // and the body is what actually arrived. A renderer reads that as a truncated track and
    // retries, which is the correct outcome and the reason `totalLength` is probed first.
    assertThat(response.code).isEqualTo(200)
    assertThat(response.head.headers.contentLength()).isEqualTo(1000L)
    assertThat(response.body).isEqualTo(content.copyOfRange(0, 10))
  }

  // ---- the accept guard, the log, and the advertised url ---------------------------------------

  @Test
  fun `connections are taken through the inbound local-network guard`() {
    // A ServerSocket serving Navidrome-authenticated audio is reachable by every device on the LAN
    // and every other app on the phone, and `NetworkSecurityPolicy` governs none of it -- see
    // `LocalNetworkOnly.acceptLocal`, which exists for this call site. Its refusal cannot be
    // observed from a loopback-only test (loopback is local by construction), so what is asserted
    // here is that this server accepts through it rather than through `ServerSocket.accept`.
    assertThat(server.acceptConnection).isEqualTo(LocalNetworkOnly::acceptLocal)
  }

  @Test
  fun `a refused connection is not the end of the server`() {
    // The other half: what this accept loop does when the guard says no. A `?: break` here would
    // let one connection from the wrong place stop the renderer that is legitimately playing.
    val refusals = CountDownLatch(1)
    val refusing = MediaProxyServer(
      upstream,
      registry,
      InetAddress.getLoopbackAddress(),
      acceptConnection = { serverSocket ->
        val socket = serverSocket.accept()
        if (refusals.count > 0) {
          refusals.countDown()
          socket.close()
          null
        } else {
          socket
        }
      },
    ).also { closeables += it; it.start() }

    // The first connection is refused: the client sees a closed socket rather than a response.
    assertThat(runCatching { get(published.path, null, refusing) }.isFailure).isTrue()
    assertThat(refusals.await(2, TimeUnit.SECONDS)).isTrue()
    // ...and the server is still serving.
    assertThat(get(published.path, null, refusing).code).isEqualTo(200)
  }

  @Test
  fun `every request is logged with its method, token, range and status`() {
    // The log is what Task 7's routing proof reads. If it recorded nothing, the router would
    // conclude the renderer never fetched and fall back on every cast.
    get(published.path, "bytes=0-99")
    get("/media/00000000000000000000000000000000.mp3", null)
    request("HEAD", published.path, null)

    assertThat(server.requestLog.map { Triple(it.method, it.token, it.status) }).containsExactly(
      Triple("GET", published.token, 206),
      Triple("GET", null, 404),
      Triple("HEAD", published.token, 200),
    )
    // The range, at two values, because one of them is `null` and a field observed only at null is
    // not observed at all.
    assertThat(server.requestLog.map { it.rangeHeader }).containsExactly("bytes=0-99", null, null)
  }

  @Test
  fun `awaitRequest waits for a fetch of that token, and is not satisfied by anything else`() {
    val other = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))

    // Nothing has been fetched yet.
    assertThat(server.awaitRequest(published.token, 50)).isFalse()
    // A method this server refuses is not a fetch -- the renderer proved nothing by sending it.
    request("POST", published.path, null)
    assertThat(server.awaitRequest(published.token, 50)).isFalse()

    request("HEAD", published.path, null)

    assertThat(server.awaitRequest(published.token, AWAIT_TIMEOUT_MS)).isTrue()
    // ...and a fetch of one token says nothing about another, which is the whole point of the
    // token being per-item.
    assertThat(server.awaitRequest(other.token, 50)).isFalse()
  }

  @Test
  fun `the advertised url names the host it was given and the published path`() {
    // Two hosts, because the address the renderer must use is not always the same one -- see
    // LocalAddress.towards and Task 7's VPN case.
    assertThat(server.urlFor(published, "192.168.1.20"))
      .isEqualTo("http://192.168.1.20:${server.port}${published.path}")
    assertThat(server.urlFor(published, "10.8.0.3"))
      .isEqualTo("http://10.8.0.3:${server.port}${published.path}")
  }

  // ---- concurrency ----------------------------------------------------------------------------

  @Test
  fun `two renderers reading two ranges at once both get the right bytes`() {
    // Renderers do this: a HEAD and a GET overlap, and a seek opens a second read before the first
    // is closed. A server holding one shared upstream stream returns interleaved garbage.
    //
    // The upstream holds both reads at a barrier before either gets a byte, so the overlap is
    // forced rather than hoped for: a server that served these one after the other would wait out
    // the barrier's timeout and fail here rather than passing by accident.
    val overlapping = start(BarrierUpstream(content, parties = 2))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val first = pool.submit<CastHttpResponse> { get(published.path, "bytes=0-499", overlapping) }
      val second = pool.submit<CastHttpResponse> { get(published.path, "bytes=500-999", overlapping) }

      assertThat(first.get(BARRIER_TIMEOUT_S * 2, TimeUnit.SECONDS).body)
        .isEqualTo(content.copyOfRange(0, 500))
      assertThat(second.get(BARRIER_TIMEOUT_S * 2, TimeUnit.SECONDS).body)
        .isEqualTo(content.copyOfRange(500, 1000))
    } finally {
      pool.shutdownNow()
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun start(source: ProxyUpstream): MediaProxyServer =
    MediaProxyServer(source, registry, InetAddress.getLoopbackAddress())
      .also { closeables += it; it.start() }

  private fun request(
    method: String,
    path: String,
    range: String?,
    target: MediaProxyServer = server,
  ): CastHttpResponse =
    CastHttpClient().exchange(
      URI("http://127.0.0.1:${target.port}$path"),
      method,
      range?.let { HttpHeaders.of("Range" to it) } ?: HttpHeaders.EMPTY,
    )

  private fun get(path: String, range: String?, target: MediaProxyServer = server): CastHttpResponse =
    request("GET", path, range, target)

  /** One exchange of raw bytes, for a request `CastHttpClient` refuses to produce. */
  private fun rawExchange(request: String): String =
    Socket("127.0.0.1", server.port).use { socket ->
      socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
      val out = ByteArrayOutputStream()
      socket.getInputStream().copyTo(out)
      String(out.toByteArray(), Charsets.US_ASCII)
    }

  /** The DIDL document a renderer would be sent for [item], with the URL this server advertises. */
  private fun didlFor(item: PublishedMedia): String = DidlLite.render(
    CastItem(
      mediaId = "track-1",
      title = "Test Track",
      artist = null,
      albumTitle = null,
      artworkUri = null,
      durationMs = 1_000L,
      upnpClass = DidlLite.CLASS_MUSIC_TRACK,
      resourceUrl = server.urlFor(item, "127.0.0.1"),
      served = item.served,
    ),
  )

  /**
   * An upstream that honours the range **for real**.
   *
   * A fake that returned the whole body whatever it was asked for would make every 206 body
   * assertion in this class pass against a proxy that ignored `Range` as well -- the two defects
   * would cancel, and the suite would be green with a seek bar that does nothing.
   */
  private class SliceUpstream(private val content: ByteArray) : ProxyUpstream {
    val opened = CopyOnWriteArrayList<ByteRange>()

    override fun totalLength(url: String): Long = content.size.toLong()

    override fun open(url: String, range: ByteRange): InputStream {
      opened += range
      return content.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()
    }
  }

  /** [SliceUpstream] that will not hand out any bytes until [parties] reads are inside it at once. */
  private class BarrierUpstream(private val content: ByteArray, parties: Int) : ProxyUpstream {
    private val barrier = CyclicBarrier(parties)

    override fun totalLength(url: String): Long = content.size.toLong()

    override fun open(url: String, range: ByteRange): InputStream {
      try {
        barrier.await(BARRIER_TIMEOUT_S, TimeUnit.SECONDS)
      } catch (broken: BrokenBarrierException) {
        throw IllegalStateException("the other read never arrived: this server serialises", broken)
      }
      return content.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()
    }
  }

  private companion object {
    const val UPSTREAM = "https://nav.example/rest/stream?id=1&format=raw"
    const val AWAIT_TIMEOUT_MS = 2_000L
    const val BARRIER_TIMEOUT_S = 5L
  }
}
