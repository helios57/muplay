package app.muplay.cast.proxy

import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpRequestHead
import app.muplay.cast.http.HttpWire
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The upstream fetch, against a **real HTTP origin** on loopback that answers what this test tells
 * it to and records what it was asked.
 *
 * Not a mock and not a stub: the subject is what OkHttp puts on the wire and what this class does
 * with what comes back, and the only place to observe either is a socket. The origin is scripted
 * per request index, which is how the 429 retry loop is observed at all -- a policy object's
 * arithmetic is a different assertion from "the fetch really asked again".
 */
class ProxyUpstreamTest {

  private val content = ByteArray(4000) { (it % 251).toByte() }
  private val closeables = mutableListOf<Closeable>()

  /** Every delay [OkHttpProxyUpstream] asked to wait, instead of waiting it. */
  private val slept = CopyOnWriteArrayList<Long>()

  @AfterEach
  fun tearDown() {
    closeables.forEach { runCatching { it.close() } }
  }

  // ---- the length probe -------------------------------------------------------------------------

  @Test
  fun `the length is the total out of a one-byte probe's content-range`() {
    // Two different totals, so `totalLength` cannot be a constant, and the total is deliberately
    // NOT the length of the body that came back: the probe reads one byte and reports 4000.
    val origin = start { _, head -> partial(head, total = content.size) }
    val other = start { _, head -> partial(head, total = 999) }

    assertThat(upstream().totalLength(origin.url)).isEqualTo(4000L)
    assertThat(upstream().totalLength(other.url)).isEqualTo(999L)
  }

  @Test
  fun `the length probe costs exactly one byte`() {
    // The whole reason this is a probe rather than a `HEAD`: a length question that downloaded the
    // track would cost a track of bandwidth per renderer probe, and Sonos probes before each one.
    val origin = start { _, head -> partial(head, total = content.size) }

    upstream().totalLength(origin.url)

    assertThat(origin.ranges).containsExactly("bytes=0-0")
  }

  @Test
  fun `an origin that declares no content-range has no length, which is not an error`() {
    // Exactly what a live Navidrome transcode does: 200, `Accept-Ranges: none`, no length anywhere.
    // `null` is the answer the proxy turns into a 502; an exception here would be a 500 with no
    // explanation, and a crash in a connection thread rather than a status a renderer can read.
    val origin = start { _, _ -> lengthless() }

    assertThat(upstream().totalLength(origin.url)).isNull()
  }

  // ---- the body fetch ---------------------------------------------------------------------------

  @Test
  fun `open asks the origin for exactly the range it was given, and returns exactly those bytes`() {
    // Two ranges, neither starting at zero, so neither the request nor the body can be a constant.
    val origin = start { _, head -> partial(head, total = content.size) }

    val first = upstream().open(origin.url, ByteRange(100, 199)).use { it.readBytes() }
    val second = upstream().open(origin.url, ByteRange(2500, 2999)).use { it.readBytes() }

    assertThat(origin.ranges).containsExactly("bytes=100-199", "bytes=2500-2999")
    assertThat(first).isEqualTo(content.copyOfRange(100, 200))
    assertThat(second).isEqualTo(content.copyOfRange(2500, 3000))
  }

  // ---- Navidrome's 429 --------------------------------------------------------------------------

  @Test
  fun `a 429 is retried, with the backoff between attempts, and then succeeds`() {
    // Spec section 4's `Transcoding.MaxConcurrent`. The assertion is on the requests the origin
    // really received and the delays this class really asked for -- a policy object returning the
    // right numbers proves neither.
    val origin = start { index, head -> if (index < 2) throttled(retryAfter = null) else partial(head, content.size) }

    assertThat(upstream().totalLength(origin.url)).isEqualTo(4000L)

    assertThat(origin.ranges).containsExactly("bytes=0-0", "bytes=0-0", "bytes=0-0")
    assertThat(slept).containsExactly(500L, 1_000L)
  }

  @Test
  fun `the origin's own retry-after wins over the backoff`() {
    // The only direction that proves the header is read at all: 3 s where the backoff would have
    // waited 0.5 s.
    val origin = start { index, head -> if (index < 1) throttled(retryAfter = "3") else partial(head, content.size) }

    assertThat(upstream().totalLength(origin.url)).isEqualTo(4000L)

    assertThat(slept).containsExactly(3_000L)
  }

  @Test
  fun `an origin that never stops refusing gives up, and says what it was told to wait`() {
    val origin = start { _, _ -> throttled(retryAfter = "5") }

    val thrown = assertThatExceptionOfType(UpstreamThrottledException::class.java)
      .isThrownBy { upstream().totalLength(origin.url) }

    thrown.satisfies({ assertThat(it.retryAfterSeconds).isEqualTo(5L) })
    // The bound, counted on the origin rather than read off a constant: `MAX_ATTEMPTS` is the last
    // attempt that may be followed by another, so five requests go out and four waits happen
    // between them. Not an unbounded loop, and not a single try either.
    assertThat(origin.ranges).hasSize(ProxyRetry.MAX_ATTEMPTS + 1)
    // The exact delays, so a policy that gave up early or waited the wrong amount is visible here
    // and not only in the arithmetic test below.
    assertThat(slept).containsExactly(5_000L, 5_000L, 5_000L, 5_000L)
  }

  @Test
  fun `an origin that refuses without saying how long has the backoff ladder, and gives up`() {
    // The other arm of the same give-up, and the one that pins the ladder: with no `Retry-After`
    // to obey, the four waits are the doubling backoff and the exception carries no delay to pass
    // on to the renderer -- which is what makes the proxy's 503 send no `Retry-After` either.
    val origin = start { _, _ -> throttled(retryAfter = null) }

    val thrown = assertThatExceptionOfType(UpstreamThrottledException::class.java)
      .isThrownBy { upstream().totalLength(origin.url) }

    thrown.satisfies({ assertThat(it.retryAfterSeconds).isNull() })
    assertThat(slept).containsExactly(500L, 1_000L, 2_000L, 4_000L)
    assertThat(origin.ranges).hasSize(ProxyRetry.MAX_ATTEMPTS + 1)
  }

  @Test
  fun `the throttled exception says what happened without ever naming the url`() {
    // A stream URL carries Subsonic's `u`, `t` and `s`, and an exception message is the one string
    // in this project that reliably reaches a bug report. Both arms, because the delay is the only
    // variable part of this message and a message observed at one value is not observed.
    assertThat(UpstreamThrottledException(retryAfterSeconds = 7).message)
      .isEqualTo("the upstream server is throttling this client, and asked for 7 s before the next attempt")
    assertThat(UpstreamThrottledException(retryAfterSeconds = null).message)
      .isEqualTo("the upstream server is throttling this client")
  }

  @Test
  fun `a refusal that is not a 429 is not retried`() {
    // A 404 is not transient, and retrying it spends a renderer's patience on a track that will
    // never arrive. One request, no waits, and a `null` length the proxy answers 502 to.
    val origin = start { _, _ -> notFound() }

    assertThat(upstream().totalLength(origin.url)).isNull()

    assertThat(origin.ranges).hasSize(1)
    assertThat(slept).isEmpty()
  }

  // ---- the arithmetic itself ---------------------------------------------------------------------

  @Test
  fun `the default upstream is the one production builds, seam and all`() {
    // Constructed with defaults -- exactly as `MediaProxyServer`'s caller does -- so the class is
    // observed working with the real `Thread::sleep` and not only with a seam. No 429 here, so
    // nothing waits; the subject is the construction, not the delay.
    val origin = start { _, head -> partial(head, total = content.size) }

    assertThat(OkHttpProxyUpstream(OkHttpClient()).totalLength(origin.url)).isEqualTo(4000L)
  }

  @Test
  fun `the retry policy backs off, honours retry-after, and gives up`() {
    // Four observations of a computed number, so `return 500L` passes one and fails three -- the
    // same shape as Plan 3's `StreamRetryPolicyTest`, in the module that owns this HTTP path.
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 1)).isEqualTo(500L)
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 2)).isEqualTo(1_000L)
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 3)).isEqualTo(2_000L)
    // The server's own number wins where the two disagree, which is the only direction that proves
    // the header is read at all.
    assertThat(ProxyRetry.retryDelayMs(429, "3", attempt = 3)).isEqualTo(3_000L)
    assertThat(ProxyRetry.retryDelayMs(429, "600", attempt = 1)).isEqualTo(ProxyRetry.MAX_BACKOFF_MS)
    // Not this policy's business, and not retried forever.
    assertThat(ProxyRetry.retryDelayMs(404, null, attempt = 1)).isNull()
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = ProxyRetry.MAX_ATTEMPTS + 1)).isNull()
  }

  @Test
  fun `a retry-after this client cannot use falls through to the backoff, and never goes negative`() {
    // `Retry-After` may legally be an HTTP-date, which this client does not parse -- falling
    // through to the backoff is the correct answer there, not an oversight. And a hostile or broken
    // origin can send a negative one: `Thread.sleep(-5000)` is an IllegalArgumentException thrown
    // out of a fetch whose caller is guarding an IOException.
    assertThat(ProxyRetry.retryDelayMs(429, "Wed, 21 Oct 2026 07:28:00 GMT", attempt = 2)).isEqualTo(1_000L)
    assertThat(ProxyRetry.retryDelayMs(429, "-5", attempt = 1)).isEqualTo(0L)
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private fun upstream() = OkHttpProxyUpstream(OkHttpClient(), sleep = { slept += it })

  private fun start(answer: (Int, HttpRequestHead) -> ByteArray): FakeOrigin =
    FakeOrigin(answer).also { closeables += it; it.start() }

  /** A 206 for whatever `Range` the request carried, sliced out of [content] for real. */
  private fun partial(head: HttpRequestHead, total: Int): ByteArray {
    // Parsed here rather than through `RangeHeader`: an origin that used this module's own parser
    // would agree with a broken one, and these tests would then be blind to the disagreement.
    val spec = head.headers["Range"].orEmpty().removePrefix("bytes=").split('-')
    val first = spec[0].toInt()
    val last = spec[1].toInt()
    val body = content.copyOfRange(first, last + 1)
    return HttpWire.renderResponseHead(
      206,
      "Partial Content",
      HttpHeaders.of(
        "Content-Type" to "audio/mpeg",
        "Accept-Ranges" to "bytes",
        "Content-Range" to "bytes $first-$last/$total",
        "Content-Length" to "${body.size}",
        "Connection" to "close",
      ),
    ) + body
  }

  /** What a live Navidrome transcode answers: 200, unseekable, and no length at all. */
  private fun lengthless(): ByteArray =
    HttpWire.renderResponseHead(
      200,
      "OK",
      HttpHeaders.of("Content-Type" to "audio/mpeg", "Accept-Ranges" to "none", "Connection" to "close"),
    ) + ByteArray(64)

  private fun throttled(retryAfter: String?): ByteArray =
    HttpWire.renderResponseHead(
      ProxyRetry.TOO_MANY_REQUESTS,
      "Too Many Requests",
      HttpHeaders(
        buildList {
          retryAfter?.let { add("Retry-After" to it) }
          add("Content-Length" to "0")
          add("Connection" to "close")
        },
      ),
    )

  private fun notFound(): ByteArray =
    HttpWire.renderResponseHead(
      404,
      "Not Found",
      HttpHeaders.of("Content-Length" to "0", "Connection" to "close"),
    )

  /**
   * A real HTTP/1.1 origin on loopback, scripted by request index.
   *
   * By index rather than by a queue of responses because every interesting case here is *the same
   * request answered differently the second time*, which is what a 429 retry is.
   */
  private class FakeOrigin(private val answer: (Int, HttpRequestHead) -> ByteArray) : Closeable {

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    /** The `Range` header of each request, in arrival order. */
    val ranges = CopyOnWriteArrayList<String?>()

    val url: String get() = "http://127.0.0.1:${socket.localPort}/rest/stream"

    fun start() {
      thread(isDaemon = true, name = "fake-origin") {
        while (!socket.isClosed) {
          val connection = runCatching { socket.accept() }.getOrNull() ?: continue
          thread(isDaemon = true) { runCatching { serve(connection) }; runCatching { connection.close() } }
        }
      }
    }

    private fun serve(connection: Socket) {
      val head = HttpWire.readRequestHead(connection.getInputStream())
      val index = ranges.size
      ranges += head.headers["Range"]
      connection.getOutputStream().apply { write(answer(index, head)); flush() }
    }

    override fun close() = socket.close()

    private companion object {
      const val BACKLOG = 8
    }
  }
}
