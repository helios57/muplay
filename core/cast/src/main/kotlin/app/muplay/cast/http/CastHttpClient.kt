package app.muplay.cast.http

import app.muplay.cast.net.LocalNetworkOnly
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
 *
 * ### What this client refuses, and why each refusal is here rather than in a caller
 *
 * The peer is a device on the LAN that MuPlay did not write, and the *inputs* to a request are
 * increasingly peer-derived too -- Task 3 builds `SOAPACTION` out of the service type and action
 * name parsed from a renderer's own device-description XML. So:
 *
 * - **Nothing with a CR, an LF or a NUL is written to the wire** ([HttpWire.headerLine],
 *   [HttpWire.requireToken]), which closes request splitting for every task that will ever call
 *   this. Refused with `IllegalArgumentException`, before the socket is opened, so no half-written
 *   request reaches the renderer.
 * - **The framing headers belong to this client** ([FRAMING_HEADERS]). A caller-supplied
 *   `Content-Length` used to sit alongside the one this class appends, and two `Content-Length`
 *   headers is a message that frames two ways.
 * - **Every response body is capped** ([maxBodyBytes]) and a transfer-coding this codec does not
 *   implement is refused rather than mis-parsed. See [HttpWire.readBody].
 *
 * @param maxBodyBytes the most response body this client will buffer. A device description and a
 *   SOAP fault are kilobytes; the cap exists because `soTimeout` is per read, so a renderer
 *   streaming steadily is never interrupted by it and an uncapped read is an out-of-memory kill
 *   waiting for a bad (or hostile) device.
 */
class CastHttpClient(
  private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
  private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
  private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {

  fun exchange(
    url: URI,
    method: String,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    body: ByteArray? = null,
  ): CastHttpResponse {
    require(url.scheme.equals("http", ignoreCase = true)) {
      "CastHttpClient speaks http only, and was given \"${url.withoutUserInfo()}\". A renderer " +
        "has no TLS, and this client has no trust store to give it one."
    }
    val host = requireNotNull(url.host) { "no host in \"${url.withoutUserInfo()}\"" }
    val port = if (url.port == -1) DEFAULT_HTTP_PORT else url.port
    // Rendered -- and therefore validated -- before anything is resolved or opened. A method or a
    // header this client will not write must cost no packet at all, and the recording server in
    // `CastHttpClientTest` observes exactly that: nothing arrives.
    val requestHead = renderRequestHead(method, url, host, port, headers, body)
    val address = InetAddress.getByName(host)
    LocalNetworkOnly.require(host, address)

    Socket().use { socket ->
      socket.soTimeout = readTimeoutMs
      socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
      socket.getOutputStream().apply {
        write(requestHead)
        if (body != null && body.isNotEmpty()) write(body)
        flush()
      }
      val input = socket.getInputStream()
      val head = HttpWire.readResponseHead(input)
      return CastHttpResponse(head, HttpWire.readBody(input, head.headers, maxBodyBytes))
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
   * Every line here goes through [HttpWire.headerLine], which is the point: one place writes a
   * header, so one check covers the method, this client's own three headers and every caller
   * header alike. A caller may not supply any of [FRAMING_HEADERS] -- those decide where this
   * message ends, and a second opinion about that is a smuggling primitive rather than a
   * customisation.
   *
   * `internal` rather than `private` for one reason, and it is a test-visibility reason stated
   * rather than hidden: the `Host`-without-a-port branch fires only for port 80, and a test cannot
   * bind port 80 unprivileged, so that one branch is observed here instead of on a socket. Every
   * other byte of this head is asserted end-to-end against a real `ServerSocket` in
   * `CastHttpClientTest`, which is also what pins that [exchange] writes exactly what this
   * returns -- so the port-80 observation is one layer in, not one layer adrift.
   *
   * @throws IllegalArgumentException for a method that is not an HTTP token, for a header name
   *   that is not one, for a header value carrying CR, LF, NUL or any other non-ASCII byte, and
   *   for a caller-supplied framing header.
   */
  internal fun renderRequestHead(
    method: String,
    url: URI,
    host: String,
    port: Int,
    headers: HttpHeaders,
    body: ByteArray?,
  ): ByteArray {
    HttpWire.requireToken("method", method)
    headers.names.forEach { name ->
      require(FRAMING_HEADERS.none { it.equals(name, ignoreCase = true) }) {
        "\"$name\" frames the message and belongs to CastHttpClient, which writes it itself. A " +
          "second one alongside it is a message that frames two ways -- the smuggling primitive."
      }
    }
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
      append(HttpWire.headerLine("Host", hostHeader))
      // One request per connection. A renderer's HTTP server is a small embedded thing and a
      // half-open keep-alive to it is a resource this app has no business holding.
      append(HttpWire.headerLine("Connection", "close"))
      headers.asList().forEach { (name, value) -> append(HttpWire.headerLine(name, value)) }
      if (body != null) append(HttpWire.headerLine(HttpHeaders.CONTENT_LENGTH, "${body.size}"))
      append(HttpWire.CRLF)
    }.toByteArray(Charsets.US_ASCII)
  }

  /**
   * This URI as text with any `user:password@` removed.
   *
   * Task 6 puts Subsonic's `u`, `t` and `s` parameters into URLs this class is handed, and an
   * exception message is the one string in this project that reliably ends up in a bug report.
   * The query is left alone -- stripping it would hide which control endpoint failed -- so a
   * caller putting a credential in a query string still owns that; the userinfo is the part a URI
   * can carry invisibly and that no renderer ever needs.
   */
  private fun URI.withoutUserInfo(): String =
    if (userInfo == null) toString() else toString().replace("$userInfo@", "")

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 4_000
    const val DEFAULT_READ_TIMEOUT_MS: Int = 8_000

    /**
     * 1 MiB. A UPnP device description is a few kilobytes and a SOAP response smaller still, so
     * this is three orders of magnitude of headroom for an honest renderer and a hard stop for one
     * that streams forever. Media never comes through this client -- Task 6's proxy fetches that
     * from Navidrome over OkHttp.
     */
    const val DEFAULT_MAX_BODY_BYTES: Int = 1024 * 1024

    /**
     * The headers that decide where a message ends, plus the two this client writes from the URL
     * it was given. A caller supplies none of them.
     */
    val FRAMING_HEADERS: List<String> =
      listOf(HttpHeaders.CONTENT_LENGTH, "Transfer-Encoding", "Host", "Connection")

    private const val DEFAULT_HTTP_PORT = 80
  }
}
