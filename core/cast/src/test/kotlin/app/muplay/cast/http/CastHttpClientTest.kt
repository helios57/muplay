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
