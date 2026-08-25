package app.muplay.cast.http

import app.muplay.cast.net.LocalNetworkOnly
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** A response head and its whole body. */
data class CastHttpResponse(val head: HttpResponseHead, val body: ByteArray) {
  val code: Int get() = head.code

  fun bodyText(): String = String(body, Charsets.UTF_8)

  // `data class` over a ByteArray needs these; the generated ones compare identity.
  override fun equals(other: Any?): Boolean =
    this === other || (other is CastHttpResponse && head == other.head && body.contentEquals(other.body))

  override fun hashCode(): Int = 31 * head.hashCode() + body.contentHashCode()
}

/**
 * An HTTP/1.1 client for **renderer-facing traffic only**.
 *
 * A `java.net.Socket` and [HttpWire], not OkHttp, and the reason is documented at length on
 * [LocalNetworkOnly]: renderers have no TLS, OkHttp refuses cleartext under the release build's
 * network security policy, and the platform's cleartext switch is host-blind so it cannot express
 * the rule this project actually wants. That rule -- *plain HTTP to the local network, never to
 * the internet* -- is enforced here, on every request, before a socket is opened.
 *
 * Everything MuPlay sends to **Navidrome** still goes through OkHttp and is still held to the
 * platform policy. Do not use this class for anything but a device on the LAN.
 *
 * Deliberately minimal: one request per connection (`Connection: close`), no redirects, no
 * connection pool, no cookies, no chunked request bodies. Control URLs do not redirect, and a SOAP
 * exchange is a few hundred bytes.
 */
class CastHttpClient(
  private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
  private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

  fun exchange(
    url: URI,
    method: String,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    body: ByteArray? = null,
  ): CastHttpResponse {
    require(url.scheme.equals("http", ignoreCase = true)) {
      "CastHttpClient speaks http only, and was given \"$url\". A renderer has no TLS, and this " +
        "client has no trust store to give it one."
    }
    val host = requireNotNull(url.host) { "no host in \"$url\"" }
    val port = if (url.port == -1) DEFAULT_HTTP_PORT else url.port
    val address = InetAddress.getByName(host)
    LocalNetworkOnly.require(host, address)

    Socket().use { socket ->
      socket.soTimeout = readTimeoutMs
      socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
      socket.getOutputStream().apply {
        write(renderRequestHead(method, url, host, port, headers, body))
        if (body != null && body.isNotEmpty()) write(body)
        flush()
      }
      val input = socket.getInputStream()
      val head = HttpWire.readResponseHead(input)
      return CastHttpResponse(head, readBody(input, head.headers.contentLength()))
    }
  }

  /**
   * The request head, byte for byte.
   *
   * `Host` includes the port whenever it is not 80: a Sonos control endpoint lives on 1400, and a
   * `Host` without the port is a request some servers answer 400.
   *
   * `Content-Length` is written **last and unconditionally when there is a body**, including when
   * the body is empty -- `Content-Length: 0` on a POST is what tells a server not to wait for one.
   * Caller headers go in between, in the order given, because Task 3 asserts a whole SOAP head
   * byte-for-byte and that is only writable against a deterministic order.
   *
   * `internal` rather than `private` for one reason, and it is a test-visibility reason stated
   * rather than hidden: the `Host`-without-a-port branch fires only for port 80, and a test cannot
   * bind port 80 unprivileged, so that one branch is observed here instead of on a socket. Every
   * other byte of this head is asserted end-to-end against a real `ServerSocket` in
   * `CastHttpClientTest`, which is also what pins that [exchange] writes exactly what this
   * returns -- so the port-80 observation is one layer in, not one layer adrift.
   */
  internal fun renderRequestHead(
    method: String,
    url: URI,
    host: String,
    port: Int,
    headers: HttpHeaders,
    body: ByteArray?,
  ): ByteArray {
    val target = buildString {
      // `rawPath` cannot be null here: `exchange` has already required a non-null `host`, and a
      // URI with an authority is hierarchical, so its path is `""` at worst -- measured, for
      // `http://127.0.0.1:8080` and for `http://127.0.0.1:8080?x=1` alike. An `?: "/"` in front of
      // this would be a branch no test could ever reach, which is its own kind of dishonesty.
      append(url.rawPath.ifEmpty { "/" })
      url.rawQuery?.let { append('?').append(it) }
    }
    val hostHeader = if (port == DEFAULT_HTTP_PORT) host else "$host:$port"
    return buildString {
      append(method).append(' ').append(target).append(" HTTP/1.1").append(HttpWire.CRLF)
      append("Host: ").append(hostHeader).append(HttpWire.CRLF)
      // One request per connection. A renderer's HTTP server is a small embedded thing and a
      // half-open keep-alive to it is a resource this app has no business holding.
      append("Connection: close").append(HttpWire.CRLF)
      headers.asList().forEach { (name, value) ->
        append(name).append(": ").append(value).append(HttpWire.CRLF)
      }
      if (body != null) append("Content-Length: ").append(body.size).append(HttpWire.CRLF)
      append(HttpWire.CRLF)
    }.toByteArray(Charsets.US_ASCII)
  }

  /**
   * The body. With a `Content-Length`, exactly that many bytes; without one, everything until the
   * peer closes -- which is legal under `Connection: close` and is what several embedded renderers
   * actually do.
   */
  private fun readBody(input: InputStream, contentLength: Long?): ByteArray =
    if (contentLength == null) input.readBytes() else input.readNBytes(contentLength.toInt())

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 4_000
    const val DEFAULT_READ_TIMEOUT_MS: Int = 8_000
    private const val DEFAULT_HTTP_PORT = 80
  }
}
