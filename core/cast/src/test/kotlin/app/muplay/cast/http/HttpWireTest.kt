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

  /** A body read the way a socket would deliver it, with an explicit cap named at every call. */
  private fun body(raw: String, headers: HttpHeaders, maxBytes: Int = 1024) =
    HttpWire.readBody(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)), headers, maxBytes)

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
    // Both spellings, because they take different paths through the split: with the trailing space
    // the line has three parts and the third is empty, without it the line has only two and there
    // is no third to read. A parser that handled one and indexed past the end on the other would
    // pass whichever of the two happened to be the fixture.
    val terse = response("HTTP/1.1 500 \r\nContent-Length: 0\r\n\r\n")
    val terser = response("HTTP/1.1 500\r\nContent-Length: 0\r\n\r\n")

    assertThat(terse.code).isEqualTo(500)
    assertThat(terse.reason).isEmpty()
    assertThat(terser.code).isEqualTo(500)
    assertThat(terser.reason).isEmpty()
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
  fun `an empty stream is rejected rather than parsed as an empty response`() {
    // The status-line half of the rule the request-line test above pins, and it needs its own
    // observation: `readResponseHead` has its own end-of-stream check, and a message naming the
    // request line would send a debugger of a failed SOAP call to the proxy.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("") }
      .withMessageContaining("closed")
      .withMessageContaining("status line")
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
  fun `a datagram whose last header has no line terminator at all still yields that header`() {
    // A datagram is sized by the packet, not by a terminator, and a renderer that omits the final
    // CRLF has still sent the header. Dropping the bytes buffered at end of input would lose the
    // last header of the block -- and `USN` being last in an M-SEARCH reply is ordinary.
    val headers = HttpWire.parseHeaderBlock(
      "LOCATION: http://10.0.0.9:2869/desc.xml\r\nUSN: uuid:abc::urn:x",
    )

    assertThat(headers.names).containsExactly("LOCATION", "USN")
    assertThat(headers["usn"]).isEqualTo("uuid:abc::urn:x")
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

  @Test
  fun `a line of exactly the maximum length is accepted whichever terminator the peer chose`() {
    // MAX_LINE_BYTES used to count the CR of a CRLF: 8192 bytes plus a bare LF were accepted and
    // the same 8192 bytes plus CRLF were rejected, one byte of a peer's line-ending deciding
    // which. Fail-closed, so never a hole -- and invisible, because nothing observed the limit
    // from the ACCEPTING side at all. `MAX_HEADERS` is guarded from both sides; this now is too.
    val value = "a".repeat(HttpWire.MAX_LINE_BYTES - "X: ".length)

    val withCrlf = response("HTTP/1.1 200 OK\r\nX: $value\r\n\r\n")
    val withBareLf = response("HTTP/1.1 200 OK\nX: $value\n\n")

    assertThat(withCrlf.headers["X"]).isEqualTo(value)
    assertThat(withBareLf.headers["X"]).isEqualTo(value)
  }

  @Test
  fun `a line one byte past the maximum is rejected whichever terminator the peer chose`() {
    // The refusing side of the same boundary, in both spellings, so the limit cannot drift by a
    // byte in either direction without one of these two tests going red.
    val value = "a".repeat(HttpWire.MAX_LINE_BYTES - "X: ".length + 1)

    listOf("\r\n", "\n").forEach { terminator ->
      assertThatExceptionOfType(MalformedHttpException::class.java)
        .describedAs(if (terminator == "\r\n") "CRLF" else "bare LF")
        .isThrownBy { response("HTTP/1.1 200 OK${terminator}X: $value$terminator$terminator") }
        .withMessageContaining("${HttpWire.MAX_LINE_BYTES}")
    }
  }

  @Test
  fun `a rendered response head refuses CR, LF and NUL wherever a peer-supplied value lands`() {
    // The mirror of the request side's rule, on the object that owns it. Task 6's proxy renders
    // response heads whose values it did not choose (a `Content-Type` that came from Navidrome
    // metadata, a `Content-Range` computed from a `Range` an unknown peer sent), and a value that
    // ends its own line writes a header of the peer's choosing into MuPlay's response.
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy {
        HttpWire.renderResponseHead(200, "OK", HttpHeaders.of("X-A" to "1\r\nX-Injected: 1"))
      }
      .withMessageContaining("X-A")
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { HttpWire.renderResponseHead(200, "OK", HttpHeaders.of("X-A" to "1\u0000")) }
      .withMessageContaining("NUL")
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { HttpWire.renderResponseHead(200, "OK", HttpHeaders.of("X A" to "1")) }
      .withMessageContaining("header name")
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { HttpWire.renderResponseHead(200, "OK", HttpHeaders.of("" to "1")) }
      .withMessageContaining("empty")
    // The reason phrase is on the status line, and a CRLF there splits the message just as well.
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { HttpWire.renderResponseHead(200, "OK\r\nX-Injected: 1", HttpHeaders.EMPTY) }
      .withMessageContaining("reason phrase")
  }

  @Test
  fun `a status code that is not one is refused, and the two that are boundaries still render`() {
    listOf(0, 99, 600, 1000, -200).forEach { code ->
      assertThatExceptionOfType(IllegalArgumentException::class.java)
        .describedAs("$code")
        .isThrownBy { HttpWire.renderResponseHead(code, "OK", HttpHeaders.EMPTY) }
    }

    // Both ends of the accepted range, so a refusal cannot widen into "everything is refused".
    assertThat(String(HttpWire.renderResponseHead(100, "Continue", HttpHeaders.EMPTY)))
      .startsWith("HTTP/1.1 100 Continue")
    assertThat(String(HttpWire.renderResponseHead(599, "Odd", HttpHeaders.EMPTY)))
      .startsWith("HTTP/1.1 599 Odd")
  }

  @Test
  fun `a chunked body is decoded, its extensions ignored and its trailer discarded`() {
    // The framing an unconditional `readBytes()` mis-read: it returned the chunk sizes as part of
    // the body, which the layer above reports as an XML parse failure blaming the renderer.
    val decoded = body(
      "4;name=value\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trailer: t\r\n\r\n",
      HttpHeaders.of("Transfer-Encoding" to "chunked"),
    )

    assertThat(String(decoded, Charsets.US_ASCII)).isEqualTo("Wikipedia")
  }

  @Test
  fun `a chunked body whose trailer section never arrives is still the body that did`() {
    // The peer closing right after the last-chunk marker is the same "a packet is the whole
    // message" tolerance `parseHeaderBlock` has, applied to a trailer nothing here reads anyway.
    assertThat(String(body("3\r\nabc\r\n0\r\n", HttpHeaders.of("Transfer-Encoding" to "chunked"))))
      .isEqualTo("abc")
  }

  @Test
  fun `every way a chunked body can be malformed is a refusal rather than a partial body`() {
    val chunked = HttpHeaders.of("Transfer-Encoding" to "chunked")
    // Each of these returned *something* under a reader that trusted the framing, and something
    // is worse than nothing here: a half-decoded SOAP envelope parses as a different fault.
    mapOf(
      "a chunk size that is not hexadecimal" to "zz\r\nabc\r\n0\r\n\r\n",
      "a negative chunk size" to "-1\r\nabc\r\n0\r\n\r\n",
      "a chunk shorter than it declared" to "10\r\nabc",
      "a chunk not terminated by CRLF" to "3\r\nabcXX\r\n0\r\n\r\n",
      "a chunk with nothing at all after it" to "3\r\nabc",
      "a stream that ends where a chunk size belongs" to "",
    ).forEach { (what, raw) ->
      assertThatExceptionOfType(MalformedHttpException::class.java)
        .describedAs(what)
        .isThrownBy { body(raw, chunked) }
    }
  }

  @Test
  fun `a chunked body is held to the same cap as any other`() {
    // Chunked framing is an attacker's way around a `Content-Length` check: the length is never
    // declared, so a cap that only read `Content-Length` would not bound this at all.
    val chunked = HttpHeaders.of("Transfer-Encoding" to "chunked")

    // Exactly the cap, accepted: the permitting half, so the refusal below cannot be "always".
    assertThat(String(body("8\r\nAAAAAAAA\r\n0\r\n\r\n", chunked, maxBytes = 8))).isEqualTo("AAAAAAAA")

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { body("8\r\nAAAAAAAA\r\n1\r\nB\r\n0\r\n\r\n", chunked, maxBytes = 8) }
      .withMessageContaining("8")
  }

  @Test
  fun `a message carrying both transfer-encoding and content-length is refused`() {
    // RFC 9112 section 6.3. The two headers frame the message differently, and a codec that picks
    // one has just chosen a side in a request-smuggling attack. Refusing is the only honest move.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy {
        body(
          "3\r\nabc\r\n0\r\n\r\n",
          HttpHeaders.of("Transfer-Encoding" to "chunked", "Content-Length" to "3"),
        )
      }
      .withMessageContaining("Content-Length")
  }

  @Test
  fun `a transfer-coding this codec does not implement is refused rather than guessed at`() {
    // Both shapes: a coding on its own, and a list ending in `chunked` that this codec would have
    // to un-gzip after un-chunking. A wrong body is worse than a clean refusal.
    listOf("gzip", "gzip, chunked", "chunked, gzip").forEach { coding ->
      assertThatExceptionOfType(MalformedHttpException::class.java)
        .describedAs(coding)
        .isThrownBy { body("abc", HttpHeaders.of("Transfer-Encoding" to coding)) }
        .withMessageContaining("Transfer-Encoding")
    }
  }

  @Test
  fun `a body with a content length is exactly that many bytes, up to and including the cap`() {
    // The accepting side of the cap, at the boundary: `> maxBytes` and `>= maxBytes` differ by
    // exactly this observation, and the second would refuse a body that fits.
    assertThat(String(body("abcdefghij", HttpHeaders.of("Content-Length" to "3"), maxBytes = 1024)))
      .isEqualTo("abc")
    assertThat(String(body("abcdefgh", HttpHeaders.of("Content-Length" to "8"), maxBytes = 8)))
      .isEqualTo("abcdefgh")
  }

  @Test
  fun `a declared content length past the cap is refused before a single byte is allocated`() {
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { body("abcdefghij", HttpHeaders.of("Content-Length" to "9"), maxBytes = 8) }
      .withMessageContaining("9")
  }

  @Test
  fun `a body with no framing at all is read to the end of the stream, and no further than the cap`() {
    // `Connection: close` framing, which several embedded renderers use -- and the arm with no
    // stopping condition of its own, which is why the cap is what stops it.
    assertThat(String(body("<root/>", HttpHeaders.EMPTY, maxBytes = 1024))).isEqualTo("<root/>")

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { body("a".repeat(2048), HttpHeaders.EMPTY, maxBytes = 1024) }
      .withMessageContaining("1024")
  }

  @Test
  fun `a transfer-encoding header with no value at all is treated as absent, not as a coding`() {
    // UPnP devices send valueless headers routinely (`EXT:` is in the spec), and a `""` coding is
    // not an unimplemented transfer-coding -- refusing it would turn a working renderer into an
    // unreachable one on a header that says nothing.
    val decoded = body(
      "abc",
      HttpHeaders.of("Transfer-Encoding" to "", "Content-Length" to "3"),
    )

    assertThat(String(decoded)).isEqualTo("abc")
  }

  @Test
  fun `every class of token character is accepted in a header name, and a method is a token too`() {
    // `isTokenChar` is four ranges and a punctuation set; without an observation from each, three
    // of the four could be deleted and every other test in this class would stay green.
    assertThat(HttpWire.headerLine("X-Trial9._~", "v")).isEqualTo("X-Trial9._~: v\r\n")
    assertThat(HttpWire.headerLine("x", "v")).isEqualTo("x: v\r\n")
    HttpWire.requireToken("method", "M-SEARCH")
    HttpWire.requireToken("method", "X9")

    // ...and one refused character from each side of each range, so the ranges cannot widen.
    listOf("X-A@b", "X-A[b", "X-A{b", "X-A/b", "X-A(b").forEach { name ->
      assertThatExceptionOfType(IllegalArgumentException::class.java)
        .describedAs(name)
        .isThrownBy { HttpWire.headerLine(name, "v") }
    }
  }

  @Test
  fun `a tab is legal inside a header value and reaches the wire as one`() {
    // RFC 9110's `field-value` admits HTAB between visible characters, and this is the one
    // character a "printable ASCII only" check gets wrong in the refusing direction. A refusal
    // message quotes it rather than echoing it, so a control character never lands in a log line.
    assertThat(HttpWire.headerLine("X-A", "one\ttwo")).isEqualTo("X-A: one\ttwo\r\n")

    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { HttpWire.headerLine("X-A", "one\ttwo\rX-Injected: 1") }
      .withMessageContaining("\\u0009")
  }

  @Test
  fun `a bare CR that is not part of a CRLF is content, wherever in the line it falls`() {
    // Reading is tolerant, writing is strict: this codec accepts a stray CR from a device it did
    // not write (`headerLine` refuses to write one back out). Both positions matter, because the
    // CR is now held back rather than buffered as it arrives -- a held CR that turns out not to
    // precede an LF has to be put back, and these are the two places it can be put back from.
    assertThat(HttpWire.parseHeaderBlock("A: 1\r2\r\n")["A"]).isEqualTo("1\r2")
    assertThat(HttpWire.parseHeaderBlock("A: 1\r2")["A"]).isEqualTo("1\r2")

    // ...and a "line" that is nothing but a held-back CR is still a line, and a malformed one. It
    // is the observation that tells a CR put back at end of input from one silently dropped: drop
    // it and this block ends cleanly with one header instead of being refused.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { HttpWire.parseHeaderBlock("A: 1\r\n\r") }
      .withMessageContaining("malformed header line")
  }
}
