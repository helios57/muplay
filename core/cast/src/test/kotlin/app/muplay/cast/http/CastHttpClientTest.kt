package app.muplay.cast.http

import app.muplay.cast.net.NonLocalAddressException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The socket HTTP/1.1 client, against a **real** `ServerSocket` on loopback that records the exact
 * bytes it was sent.
 *
 * Not a fake and not a stub: the subject is what goes out on the wire, and the only way to observe
 * that is to read it off a socket. The recording server accepts one connection, reads a head,
 * reads exactly `Content-Length` body bytes, and answers -- which is also the smallest possible
 * proof that this client frames a request the way an HTTP server expects.
 */
class CastHttpClientTest {

  private val started = CopyOnWriteArrayList<Closeable>()

  @AfterEach
  fun tearDown() {
    started.forEach { runCatching { it.close() } }
  }

  private fun start(response: ByteArray): RecordingServer =
    RecordingServer(response).also { started += it; it.start() }

  private val okResponse =
    ("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 7\r\n\r\n" + "<root/>")
      .toByteArray(Charsets.US_ASCII)

  @Test
  fun `a get request is framed with the request line, the host header and a blank line`() {
    val running = start(okResponse)

    CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/xml/device_description.xml"), "GET")

    // The exact head, byte for byte. `Host` is mandatory in HTTP/1.1 and includes the port when
    // it is not the default -- a renderer on 1400 that receives `Host: 10.0.0.5` may answer 400.
    assertThat(running.headText()).isEqualTo(
      "GET /xml/device_description.xml HTTP/1.1\r\n" +
        "Host: 127.0.0.1:${running.port}\r\n" +
        "Connection: close\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `the request line carries the path and query the caller asked for`() {
    // Two observations, so the target cannot be a constant.
    val first = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
    CastHttpClient().exchange(URI("http://127.0.0.1:${first.port}/MediaRenderer/AVTransport/Control"), "POST")
    assertThat(first.headText()).startsWith("POST /MediaRenderer/AVTransport/Control HTTP/1.1\r\n")

    val second = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
    CastHttpClient().exchange(URI("http://127.0.0.1:${second.port}/upnp/control/rendertransport1?x=1"), "POST")
    assertThat(second.headText()).startsWith("POST /upnp/control/rendertransport1?x=1 HTTP/1.1\r\n")
  }

  @Test
  fun `the method is the one the caller gave`() {
    // The third field of the request line, observed at a value no other test in this class uses.
    // Without it a `renderRequestHead` that wrote "GET" for everything would still pass the two
    // POST assertions above, which read only the target.
    val running = start(okResponse)

    CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/media/1.mp3"), "HEAD")

    assertThat(running.headText()).startsWith("HEAD /media/1.mp3 HTTP/1.1\r\n")
  }

  @Test
  fun `a request with a body sends content length and then exactly that many bytes`() {
    val running = start(okResponse)
    val body = "<s:Envelope/>".toByteArray(Charsets.UTF_8)

    CastHttpClient().exchange(
      URI("http://127.0.0.1:${running.port}/control"),
      method = "POST",
      headers = HttpHeaders.of("Content-Type" to "text/xml; charset=\"utf-8\""),
      body = body,
    )

    assertThat(running.headText()).isEqualTo(
      "POST /control HTTP/1.1\r\n" +
        "Host: 127.0.0.1:${running.port}\r\n" +
        "Connection: close\r\n" +
        "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "\r\n",
    )
    assertThat(running.body).isEqualTo(body)
  }

  @Test
  fun `caller headers are sent in the order given, after the client's own`() {
    // Order is a property here for a concrete reason: Task 3 asserts a SOAP request's whole head
    // byte-for-byte, and that assertion is only writable if this order is deterministic.
    val running = start(okResponse)

    CastHttpClient().exchange(
      URI("http://127.0.0.1:${running.port}/control"),
      method = "POST",
      headers = HttpHeaders.of("SOAPACTION" to "\"urn:x#Y\"", "X-Second" to "2"),
      body = ByteArray(0),
    )

    val lines = running.headText().split("\r\n")
    assertThat(lines.filter { it.contains(':') }).containsExactly(
      "Host: 127.0.0.1:${running.port}",
      "Connection: close",
      "SOAPACTION: \"urn:x#Y\"",
      "X-Second: 2",
      "Content-Length: 0",
    )
  }

  @Test
  fun `the response head and body both come back`() {
    val running = start(okResponse)

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.code).isEqualTo(200)
    assertThat(response.head.headers["content-type"]).isEqualTo("text/xml")
    assertThat(response.bodyText()).isEqualTo("<root/>")
  }

  @Test
  fun `a 500 with a body is returned rather than thrown, because a upnp fault is a 500 with a body`() {
    // The single most important behaviour of this client. Every UPnP error arrives as HTTP 500
    // carrying a SOAP Fault, and a client that threw on 5xx would turn "Sonos said 714, illegal
    // MIME type" into "the network failed" -- the exact loss of information this project treats as
    // the worst failure class.
    val fault = "<s:Fault/>"
    val running = start(
      ("HTTP/1.1 500 Internal Server Error\r\nContent-Length: ${fault.length}\r\n\r\n$fault")
        .toByteArray(Charsets.US_ASCII),
    )

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/control"), "POST", body = ByteArray(0))

    assertThat(response.code).isEqualTo(500)
    assertThat(response.bodyText()).isEqualTo(fault)
  }

  @Test
  fun `a body sent without content length is read until the peer closes`() {
    // Legal in HTTP/1.1 with `Connection: close`, and some embedded renderers do it. Without this
    // branch the response body would come back empty and a device description would parse as
    // "no services".
    val running = start("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n<root/>".toByteArray(Charsets.US_ASCII))

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.bodyText()).isEqualTo("<root/>")
  }

  @Test
  fun `a content length shorter than what the peer sent bounds the body`() {
    // The other side of the branch above, and the reason it cannot be collapsed into "read to
    // EOF": with a `Content-Length` this client must stop there. Reading past it would, on a
    // pipelined connection, splice the next response onto this one's body.
    val running = start(
      "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcdefghij".toByteArray(Charsets.US_ASCII),
    )

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.bodyText()).isEqualTo("abc")
  }

  @Test
  fun `a public address is refused before a socket is opened`() {
    // The guard, observed refusing. `example.com` is not contacted: `NonLocalAddressException` is
    // thrown after resolution and before `connect`, so this test needs no network and is not flaky.
    assertThatExceptionOfType(NonLocalAddressException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("http://93.184.216.34/x"), "GET") }
      .withMessageContaining("93.184.216.34")
  }

  @Test
  fun `an https url is refused, because a renderer has no tls and this client has no trust store`() {
    // Better a loud refusal than a silently-plaintext request to port 443, which is what a naive
    // "ignore the scheme" implementation produces.
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("https://192.168.1.50/x"), "GET") }
      .withMessageContaining("http")
  }

  @Test
  fun `a url with no path at all asks for the root`() {
    // `URI("http://host:port")` has a rawPath of `""`, not `"/"` -- measured. A request line whose
    // target is empty (`GET  HTTP/1.1`) is malformed, and this codec's own parser would reject it.
    val running = start(okResponse)

    CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}"), "GET")

    assertThat(running.headText()).startsWith("GET / HTTP/1.1\r\n")
  }

  @Test
  fun `a url with no host is refused`() {
    // `http:///x` parses, resolves to a null host, and would otherwise reach `InetAddress
    // .getByName(null)` -- which returns *loopback*, quietly turning a malformed URL into a
    // request against this phone.
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("http:///x"), "GET") }
      .withMessageContaining("no host")
  }

  @Test
  fun `the host header carries the port unless it is the default, and both spellings are exact`() {
    // The one branch of the head that a socket test cannot reach: port 80 cannot be bound
    // unprivileged. Both spellings are asserted whole rather than as a `contains`, and the two
    // observations are what stop `Host` being either "always bare" or "always suffixed".
    val onDefaultPort = CastHttpClient().renderRequestHead(
      method = "GET",
      url = URI("http://192.168.1.50/xml/device_description.xml"),
      host = "192.168.1.50",
      port = 80,
      headers = HttpHeaders.EMPTY,
      body = null,
    )
    val onSonosPort = CastHttpClient().renderRequestHead(
      method = "GET",
      url = URI("http://192.168.1.50:1400/xml/device_description.xml"),
      host = "192.168.1.50",
      port = 1400,
      headers = HttpHeaders.EMPTY,
      body = null,
    )

    assertThat(String(onDefaultPort, Charsets.US_ASCII)).isEqualTo(
      "GET /xml/device_description.xml HTTP/1.1\r\n" +
        "Host: 192.168.1.50\r\n" +
        "Connection: close\r\n" +
        "\r\n",
    )
    assertThat(String(onSonosPort, Charsets.US_ASCII)).isEqualTo(
      "GET /xml/device_description.xml HTTP/1.1\r\n" +
        "Host: 192.168.1.50:1400\r\n" +
        "Connection: close\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `two responses are equal when their bodies are, and a data class alone cannot say that`() {
    // `CastHttpResponse` holds a `ByteArray`, and a `data class`'s generated `equals` compares
    // that by identity -- so two responses carrying the same bytes would be unequal, and a Task 3
    // assertion comparing an expected response to an actual one would fail for no reason. The
    // hand-written override is the fix; these are the observations that stop it being deleted as
    // boilerplate.
    val head = HttpResponseHead("HTTP/1.1", 200, "OK", HttpHeaders.of("A" to "b"))
    val response = CastHttpResponse(head, "<root/>".toByteArray())
    val sameBytes = CastHttpResponse(head, "<root/>".toByteArray())
    val otherBytes = CastHttpResponse(head, "<other/>".toByteArray())
    val otherHead = CastHttpResponse(HttpResponseHead("HTTP/1.1", 500, "OK", HttpHeaders.of("A" to "b")), "<root/>".toByteArray())

    assertThat(response).isEqualTo(response)
    assertThat(response).isEqualTo(sameBytes)
    assertThat(response.hashCode()).isEqualTo(sameBytes.hashCode())
    // Three ways of being different, each observed: the bytes, the head, and the type.
    assertThat(response).isNotEqualTo(otherBytes)
    assertThat(response).isNotEqualTo(otherHead)
    assertThat(response).isNotEqualTo(head)
    assertThat(response.hashCode()).isNotEqualTo(otherBytes.hashCode())
  }

  @Test
  fun `the read timeout is the one the caller gave, and is not the connect timeout`() {
    // Two timeouts of the same type, adjacent in one constructor, is this project's recorded
    // "wrong argument" shape (see `media/read-timeout-copied` in ci/mutation-probes.sh). The two
    // values here are chosen so a swap is observable rather than merely slow: the silent server
    // holds the connection open well past the read timeout and then closes it, so a client that
    // applied the 30s connect timeout as its socket timeout would reach end of stream and raise
    // `MalformedHttpException`, not `SocketTimeoutException`.
    val silent = SilentServer(holdMillis = 4_000).also { started += it; it.start() }

    assertThatExceptionOfType(SocketTimeoutException::class.java).isThrownBy {
      CastHttpClient(connectTimeoutMs = 30_000, readTimeoutMs = 200)
        .exchange(URI("http://127.0.0.1:${silent.port}/x"), "GET")
    }
  }

  @Test
  fun `a header value carrying CRLF cannot write a header of its own, and nothing reaches the wire`() {
    // THE request-splitting proof, and it is deliberately made against a real socket rather than
    // against a rendered string: this exact payload, on this exact call, put a genuine
    // `X-Injected:` header into the request a `ServerSocket` received. Task 3 builds `SOAPACTION`
    // out of the service type and action name parsed from the device-description XML **the
    // renderer itself serves**, so the value in this test is peer-controlled in production.
    val running = start(okResponse)

    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy {
        CastHttpClient().exchange(
          URI("http://127.0.0.1:${running.port}/control"),
          method = "POST",
          headers = HttpHeaders.of("SOAPACTION" to "\"urn:x#Y\"\r\nX-Injected: from-a-header-value"),
          body = ByteArray(0),
        )
      }
      .withMessageContaining("SOAPACTION")
      .withMessageContaining("CR")

    // The assertion that matters is this one, and it is about bytes rather than about a message:
    // the refusal happens before the socket is opened, so the server never saw a request at all --
    // no partially-written head, nothing for a renderer to act on.
    assertThat(running.headTextOrNull())
      .describedAs("no bytes at all may reach a renderer once a header has been refused")
      .isNull()
  }

  @Test
  fun `every character that could end a header line early is refused, in a name and in a value`() {
    // Six payloads, each of which produced a syntactically valid extra header (or a truncated
    // one) on the wire before this check existed. No server is started deliberately: the refusal
    // is required to happen before anything is resolved or connected, so a regression that let one
    // through would fail here on a connection error rather than passing.
    val url = URI("http://127.0.0.1:1/control")
    val refused = listOf(
      "a CR in a value" to HttpHeaders.of("X-A" to "one\rX-Injected: 1"),
      "an LF in a value" to HttpHeaders.of("X-A" to "one\nX-Injected: 1"),
      "a NUL in a value" to HttpHeaders.of("X-A" to "one\u0000two"),
      "a non-ASCII byte in a value" to HttpHeaders.of("X-A" to "café"),
      "a CRLF in a name" to HttpHeaders.of("X-A\r\nX-Injected" to "1"),
      "a space in a name" to HttpHeaders.of("X-A X-Injected" to "1"),
      "a colon in a name" to HttpHeaders.of("X-A: 1\r\nX-Injected" to "1"),
      "an empty name" to HttpHeaders.of("" to "1"),
    )

    refused.forEach { (what, headers) ->
      assertThatExceptionOfType(IllegalArgumentException::class.java)
        .describedAs(what)
        .isThrownBy { CastHttpClient().exchange(url, "POST", headers, ByteArray(0)) }
    }
  }

  @Test
  fun `a method that is not a token is refused, so the request line cannot be split either`() {
    // The request line splits the same way a header does, and `method` was appended with no check
    // at all. The first payload below is a whole second request; the second is the degenerate
    // case that would render `" / HTTP/1.1"`.
    val url = URI("http://127.0.0.1:1/control")

    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { CastHttpClient().exchange(url, "GET /evil HTTP/1.1\r\nX-Injected: 1") }
      .withMessageContaining("method")
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { CastHttpClient().exchange(url, "") }
      .withMessageContaining("method")
  }

  @Test
  fun `a caller may not supply a header that decides where the message ends`() {
    // `Content-Length` used to be accepted from a caller AND appended by `exchange`, so a request
    // with a body went out carrying two of them -- a message that frames two ways, which is the
    // smuggling primitive. All four framing headers are refused, in the caller's own casing and in
    // another, because the check is case-insensitive and a `==` would pass half of these.
    val url = URI("http://127.0.0.1:1/control")
    // Driven from the declared list rather than a copy of it, so a header added to
    // `FRAMING_HEADERS` is covered here the day it is added -- and each one is tried in two
    // spellings, because the check is case-insensitive and an `==` would pass half of these.
    assertThat(CastHttpClient.FRAMING_HEADERS)
      .containsExactly("Content-Length", "Transfer-Encoding", "Host", "Connection")
    CastHttpClient.FRAMING_HEADERS.flatMap { listOf(it, it.uppercase()) }.forEach { name ->
      assertThatExceptionOfType(IllegalArgumentException::class.java)
        .describedAs(name)
        .isThrownBy {
          CastHttpClient().exchange(url, "POST", HttpHeaders.of(name to "0"), ByteArray(0))
        }
        .withMessageContaining(name)
    }
  }

  @Test
  fun `a chunked response is decoded, not returned with its chunk sizes inside the body`() {
    // Measured before the fix, on a real socket: this response came back as
    // "b\r\n<s:Envelope>\r\n8\r\n</s:Env>\r\n0\r\n\r\n" -- the chunk framing inside the body,
    // which Task 3 would then report as an XML parse failure blaming the renderer.
    val running = start(
      (
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
          "c\r\n<s:Envelope>\r\n" +
          "d\r\n</s:Envelope>\r\n" +
          "0\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII),
    )

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/control"), "GET")

    assertThat(response.bodyText()).isEqualTo("<s:Envelope></s:Envelope>")
  }

  @Test
  fun `a transfer-coding this codec does not implement is refused rather than mis-framed`() {
    // The other half of the branch above, and the more important half: a wrong body is worse than
    // a clean refusal, and `MalformedHttpException` is an IOException so a caller already guarding
    // its socket catches it.
    val running = start(
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n".toByteArray(Charsets.US_ASCII),
    )

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET") }
      .withMessageContaining("gzip")
  }

  @Test
  fun `a response with no content length cannot exhaust the heap, however long the peer streams`() {
    // `soTimeout` is per read, so a renderer that keeps sending resets it forever: the old
    // `input.readBytes()` here had no stopping condition that a *steady* peer would ever meet.
    // The flood server below sends far more than the cap and the exchange must end on the cap,
    // not on a timeout and not on an OutOfMemoryError.
    val flooding = FloodServer(headText = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
      .also { started += it; it.start() }

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy {
        CastHttpClient(maxBodyBytes = 64 * 1024)
          .exchange(URI("http://127.0.0.1:${flooding.port}/x"), "GET")
      }
      .withMessageContaining("65536")
  }

  @Test
  fun `a content length that does not fit an Int is refused, not narrowed into a wrong body`() {
    // Both measured values from the review, and they failed in two different wrong ways.
    // 2147483648 raised `IllegalArgumentException: len < 0` -- not an IOException, so a caller
    // catching IOException around a network call missed it entirely. 4294967296 narrowed to
    // exactly 0 and produced a SILENTLY EMPTY body, which is the worse of the two: Task 3 would
    // report "this renderer has no services".
    listOf(2147483648L, 4294967296L).forEach { declared ->
      val running = start("HTTP/1.1 200 OK\r\nContent-Length: $declared\r\n\r\nabc".toByteArray(Charsets.US_ASCII))

      assertThatExceptionOfType(MalformedHttpException::class.java)
        .describedAs("Content-Length: $declared")
        .isThrownBy { CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET") }
        .withMessageContaining("$declared")
    }
  }

  @Test
  fun `a body inside the cap still comes back whole, so the cap is a limit and not a ceiling`() {
    // The permitting half of the two assertions above. Without it, a `readBody` that refused
    // everything would pass both refusal tests.
    val payload = "x".repeat(4096)
    val running = start(
      "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\n\r\n$payload".toByteArray(Charsets.US_ASCII),
    )

    val response = CastHttpClient(maxBodyBytes = 4096).exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.bodyText()).isEqualTo(payload)
  }

  @Test
  fun `a password in a url's userinfo never reaches an exception message`() {
    // Task 6 puts Subsonic's `u`, `t` and `s` into URLs handed to this client, and an exception
    // message is the one string in this project that reliably ends up in a bug report. The host is
    // still named, because a message that says only "refused" sends the next debugger to the wrong
    // layer -- that is the whole argument `NonLocalAddressException` makes about itself.
    val thrown = catchThrowable {
      CastHttpClient().exchange(URI("https://alice:hunter2@192.168.1.50/x"), "GET")
    }

    assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    assertThat(thrown.message).doesNotContain("hunter2").contains("192.168.1.50")
  }

  /** A real HTTP server on loopback that records exactly what it was sent. */
  private class RecordingServer(private val response: ByteArray) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val received = CopyOnWriteArrayList<ByteArray>()
    private val done = CountDownLatch(1)
    var body: ByteArray = ByteArray(0)
      private set

    val port: Int get() = socket.localPort

    fun start() {
      thread(isDaemon = true, name = "recording-server") {
        runCatching {
          socket.accept().use { connection ->
            val input = connection.getInputStream()
            val head = readHeadBytes(input)
            received += head
            val length = HttpWire.parseHeaderBlock(
              String(head, Charsets.US_ASCII).substringAfter("\r\n"),
            ).contentLength() ?: 0L
            body = ByteArray(length.toInt()).also { if (it.isNotEmpty()) input.readNBytes(it, 0, it.size) }
            connection.getOutputStream().write(response)
            connection.getOutputStream().flush()
          }
        }
        done.countDown()
      }
    }

    fun headText(): String {
      done.await(5, TimeUnit.SECONDS)
      return String(received.first(), Charsets.US_ASCII)
    }

    /**
     * The head this server received, or `null` when nothing arrived at all.
     *
     * The distinction is the assertion in the request-splitting tests: a refused header must cost
     * the renderer no bytes, and `headText()` cannot say "nothing arrived" -- it throws on an empty
     * list, which reads as a broken test rather than as the observation it is. The wait is short
     * because the expected answer is "nothing", and a client that did write would have written
     * before it finished connecting.
     */
    fun headTextOrNull(): String? {
      done.await(1, TimeUnit.SECONDS)
      return received.firstOrNull()?.let { String(it, Charsets.US_ASCII) }
    }

    override fun close() = socket.close()

    /** Reads up to and including the CRLFCRLF, returning the raw bytes so tests assert on them. */
    private fun readHeadBytes(input: InputStream): ByteArray {
      val out = ByteArrayOutputStream()
      var matched = 0
      val terminator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
      while (matched < terminator.size) {
        val b = input.read()
        if (b == -1) break
        out.write(b)
        matched = if (b == terminator[matched].toInt()) matched + 1 else if (b == '\r'.code) 1 else 0
      }
      return out.toByteArray()
    }
  }

  /**
   * Sends a head and then a body that never legitimately ends -- the peer the body cap exists for.
   *
   * A renderer streaming steadily is exactly the shape `soTimeout` cannot catch: every read
   * succeeds, so the per-read timeout is reset forever and only a cap ever stops it.
   */
  private class FloodServer(private val headText: String) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    val port: Int get() = socket.localPort

    fun start() {
      thread(isDaemon = true, name = "flood-server") {
        runCatching {
          socket.accept().use { connection ->
            val out = connection.getOutputStream()
            out.write(headText.toByteArray(Charsets.US_ASCII))
            val block = ByteArray(BLOCK_BYTES) { 'A'.code.toByte() }
            // Bounded, so a subject that never stops reading cannot leave this thread writing for
            // the rest of the suite. The bound is far above any cap a test here sets, and the
            // client's own refusal closes the socket long before it is reached.
            repeat(BLOCKS) {
              out.write(block)
              out.flush()
            }
          }
        }
      }
    }

    override fun close() = socket.close()

    private companion object {
      const val BLOCK_BYTES = 8192
      const val BLOCKS = 512
    }
  }

  /** Accepts a connection, answers nothing at all, and closes after [holdMillis]. */
  private class SilentServer(private val holdMillis: Long) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    val port: Int get() = socket.localPort

    fun start() {
      thread(isDaemon = true, name = "silent-server") {
        runCatching {
          socket.accept().use { connection ->
            Thread.sleep(holdMillis)
            connection.close()
          }
        }
      }
    }

    override fun close() = socket.close()
  }
}
