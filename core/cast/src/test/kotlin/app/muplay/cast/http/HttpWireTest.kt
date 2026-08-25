package app.muplay.cast.http

import java.io.ByteArrayInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The HTTP/1.1 head codec, which has three consumers: the proxy server reads request heads, the
 * SOAP client reads response heads, and SSDP parses a bare header block out of a UDP datagram.
 *
 * Every rejection below is a real defence, not a formality. This parser reads bytes from a device
 * on the local network that MuPlay did not write and cannot vouch for, and a parser with no size
 * limits will happily consume a gigabyte of `A`s from a single line.
 */
class HttpWireTest {

  private fun request(raw: String) = HttpWire.readRequestHead(ByteArrayInputStream(raw.toByteArray()))
  private fun response(raw: String) = HttpWire.readResponseHead(ByteArrayInputStream(raw.toByteArray()))

  @Test
  fun `a request line is split into its three parts, and each one is the one that was sent`() {
    // Three fields, two observations each, so no single constant satisfies any of them.
    val get = request("GET /media/abc.mp3 HTTP/1.1\r\nHost: 10.0.0.5:8080\r\n\r\n")
    val head = request("HEAD /media/def.flac HTTP/1.0\r\nHost: 10.0.0.5:8080\r\n\r\n")

    assertThat(get.method).isEqualTo("GET")
    assertThat(get.target).isEqualTo("/media/abc.mp3")
    assertThat(get.version).isEqualTo("HTTP/1.1")
    assertThat(head.method).isEqualTo("HEAD")
    assertThat(head.target).isEqualTo("/media/def.flac")
    assertThat(head.version).isEqualTo("HTTP/1.0")
  }

  @Test
  fun `a status line is split into its three parts, and the reason phrase may contain spaces`() {
    val ok = response("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
    val partial = response("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\n\r\n")

    assertThat(ok.code).isEqualTo(200)
    assertThat(ok.reason).isEqualTo("OK")
    assertThat(partial.code).isEqualTo(206)
    // "Partial Content" has a space in it. A naive three-way split on whitespace loses the second
    // word, and a reason phrase is what a UPnP fault's human-readable half arrives in.
    assertThat(partial.reason).isEqualTo("Partial Content")
    assertThat(partial.version).isEqualTo("HTTP/1.1")
  }

  @Test
  fun `a status line with no reason phrase is legal and parses`() {
    // RFC 7230: the reason phrase may be empty. Sonos does not do this; some embedded renderers do.
    val terse = response("HTTP/1.1 500 \r\nContent-Length: 0\r\n\r\n")

    assertThat(terse.code).isEqualTo(500)
    assertThat(terse.reason).isEmpty()
  }

  @Test
  fun `headers survive the trip with their values and their order`() {
    val head = response(
      "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://10.0.0.5:1400/xml/device_description.xml\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/84.1-52250\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )

    // The exact list of names, in order. `hasSize(5)` would pass with every value swapped.
    assertThat(head.headers.names)
      .containsExactly("CACHE-CONTROL", "EXT", "LOCATION", "SERVER", "ST")
    assertThat(head.headers["location"])
      .isEqualTo("http://10.0.0.5:1400/xml/device_description.xml")
    assertThat(head.headers["st"]).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    // `EXT:` with nothing after it is a *mandatory* header in a UPnP response and its value is the
    // empty string. Dropping it because it looks blank would make this parser reject a conformant
    // device.
    assertThat(head.headers["ext"]).isEqualTo("")
  }

  @Test
  fun `optional whitespace after the colon is trimmed and interior whitespace is not`() {
    val head = response(
      "HTTP/1.1 200 OK\r\nX-A:   spaced   \r\nX-B:tight\r\nX-C: two words\r\n\r\n",
    )

    assertThat(head.headers["X-A"]).isEqualTo("spaced")
    assertThat(head.headers["X-B"]).isEqualTo("tight")
    assertThat(head.headers["X-C"]).isEqualTo("two words")
  }

  @Test
  fun `a bare LF instead of CRLF is accepted, because embedded renderers send it`() {
    // RFC 7230 recommends tolerating this on receipt. This parser never *emits* a bare LF -- see
    // `renderResponseHead`, which is asserted byte-for-byte below.
    val head = response("HTTP/1.1 200 OK\nContent-Length: 3\n\n")

    assertThat(head.code).isEqualTo(200)
    assertThat(head.headers.contentLength()).isEqualTo(3L)
  }

  @Test
  fun `a request line with the wrong number of parts is rejected`() {
    // Rejections, plural, and each with a distinguishable message. A single catch-all
    // `MalformedHttpException("bad request")` would make every one of these pass while telling a
    // debugger nothing.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("GET\r\n\r\n") }
      .withMessageContaining("request line")
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("GET /a\r\n\r\n") }
      .withMessageContaining("request line")
  }

  @Test
  fun `a status line whose code is not a number is rejected`() {
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 OK OK\r\n\r\n") }
      .withMessageContaining("status code")
  }

  @Test
  fun `a status line with only one part is rejected`() {
    // The other half of `readResponseHead`'s size guard, and a distinguishable message from the
    // status-code rejection above: a peer that sends only `HTTP/1.1` has sent no code at all, so
    // there is nothing to parse as an integer and the failure must name the line, not the code.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1\r\n\r\n") }
      .withMessageContaining("status line")
  }

  @Test
  fun `a header with no colon is rejected`() {
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 200 OK\r\nnot a header\r\n\r\n") }
      .withMessageContaining("header")
  }

  @Test
  fun `a header line beginning with a colon is rejected rather than given an empty name`() {
    // `colon <= 0`, not `colon < 0`: a line whose first byte is `:` has a colon at index 0 and
    // would otherwise parse into a header with an empty name, which no lookup could ever find.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 200 OK\r\n: orphaned\r\n\r\n") }
      .withMessageContaining("header")
  }

  @Test
  fun `an empty stream is rejected rather than parsed as an empty request`() {
    // A renderer that opens a connection and closes it without sending anything is ordinary. This
    // must be an exception, not a `HttpRequestHead("", "", "")` that the proxy then routes.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("") }
      .withMessageContaining("closed")
  }

  @Test
  fun `a stream that ends inside the header block is rejected, not returned half-parsed`() {
    // The counterpart of `a bare header block from a udp datagram parses without a start line`
    // below, and the reason the two cannot share one code path. On a *socket* a head that never
    // reached its blank line is a truncated read: the peer died, or a proxy cut the connection.
    // Returning the headers that did arrive would let the proxy route a request whose `Range` was
    // still in flight. On a *datagram* there is no such thing as truncation-by-close -- the packet
    // is the whole message -- so end of input there is the end of the block.
    //
    // Without this test the tolerant end-of-input branch could be made unconditional and every
    // other test in this class would stay green.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n") }
      .withMessageContaining("closed")
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("GET /x HTTP/1.1\r\nHost: 10.0.0.5\r\n") }
      .withMessageContaining("closed")
  }

  @Test
  fun `an over-long line is rejected before it is buffered`() {
    val enormous = "GET /" + "a".repeat(HttpWire.MAX_LINE_BYTES) + " HTTP/1.1\r\n\r\n"

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request(enormous) }
      .withMessageContaining("${HttpWire.MAX_LINE_BYTES}")
  }

  @Test
  fun `too many headers is rejected`() {
    val flood = buildString {
      append("HTTP/1.1 200 OK\r\n")
      repeat(HttpWire.MAX_HEADERS + 1) { append("X-$it: v\r\n") }
      append("\r\n")
    }

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response(flood) }
      .withMessageContaining("${HttpWire.MAX_HEADERS}")
  }

  @Test
  fun `exactly the maximum number of headers is accepted`() {
    // The boundary from the other side. Without this, an off-by-one that rejected at MAX_HEADERS
    // would pass every other test in this class.
    val atLimit = buildString {
      append("HTTP/1.1 200 OK\r\n")
      repeat(HttpWire.MAX_HEADERS) { append("X-$it: v\r\n") }
      append("\r\n")
    }

    assertThat(response(atLimit).headers.size).isEqualTo(HttpWire.MAX_HEADERS)
  }

  @Test
  fun `a bare header block from a udp datagram parses without a start line`() {
    // SSDP's shape: `parseHeaderBlock` is given everything after the status line. This is the
    // third consumer that makes this codec worth owning -- no HTTP library will parse a datagram.
    val headers = HttpWire.parseHeaderBlock(
      "LOCATION: http://10.0.0.9:2869/desc.xml\r\nUSN: uuid:abc::urn:x\r\n",
    )

    assertThat(headers.names).containsExactly("LOCATION", "USN")
    assertThat(headers["usn"]).isEqualTo("uuid:abc::urn:x")
  }

  @Test
  fun `a datagram whose block does end with a blank line parses to the same thing`() {
    // Real SSDP datagrams end `\r\n\r\n`; the one above does not. Both shapes have to reach the
    // same answer, and the two observations together are what pin the end-of-block rule at both
    // of its exits rather than at whichever one the fixture happened to use.
    val headers = HttpWire.parseHeaderBlock(
      "LOCATION: http://10.0.0.9:2869/desc.xml\r\nUSN: uuid:abc::urn:x\r\n\r\n",
    )

    assertThat(headers.names).containsExactly("LOCATION", "USN")
    assertThat(headers["location"]).isEqualTo("http://10.0.0.9:2869/desc.xml")
  }

  @Test
  fun `a rendered response head is byte-exact and always uses CRLF`() {
    val rendered = HttpWire.renderResponseHead(
      code = 206,
      reason = "Partial Content",
      headers = HttpHeaders.of(
        "Content-Type" to "audio/mpeg",
        "Content-Range" to "bytes 100-199/1000",
        "Content-Length" to "100",
      ),
    )

    // The whole thing, as a string, with the line endings visible. Anything less than a byte-exact
    // assertion here would pass with LF-only endings, which some renderers' HTTP clients drop.
    assertThat(String(rendered, Charsets.US_ASCII)).isEqualTo(
      "HTTP/1.1 206 Partial Content\r\n" +
        "Content-Type: audio/mpeg\r\n" +
        "Content-Range: bytes 100-199/1000\r\n" +
        "Content-Length: 100\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `a rendered response head carries the status it was given`() {
    // The second observation of the status line, so `renderResponseHead` cannot hardcode 206.
    assertThat(String(HttpWire.renderResponseHead(416, "Range Not Satisfiable", HttpHeaders.EMPTY)))
      .isEqualTo("HTTP/1.1 416 Range Not Satisfiable\r\n\r\n")
  }

  @Test
  fun `a rendered head round-trips through the parser`() {
    val rendered = HttpWire.renderResponseHead(
      404,
      "Not Found",
      HttpHeaders.of("Content-Length" to "0", "Connection" to "close"),
    )

    val reparsed = HttpWire.readResponseHead(ByteArrayInputStream(rendered))

    assertThat(reparsed.code).isEqualTo(404)
    assertThat(reparsed.reason).isEqualTo("Not Found")
    assertThat(reparsed.headers.names).containsExactly("Content-Length", "Connection")
  }
}
