package app.muplay.cast.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Thrown when a peer sends something this codec will not treat as HTTP/1.1. */
class MalformedHttpException(message: String) : IOException(message)

/** A parsed request head: everything up to and including the blank line. */
data class HttpRequestHead(
  val method: String,
  val target: String,
  val version: String,
  val headers: HttpHeaders,
)

/** A parsed response head: everything up to and including the blank line. */
data class HttpResponseHead(
  val version: String,
  val code: Int,
  val reason: String,
  val headers: HttpHeaders,
)

/**
 * The HTTP/1.1 head codec this module owns, with three consumers:
 *
 * - the proxy server (`app.muplay.cast.proxy.MediaProxyServer`, Task 6) reads **request** heads;
 * - the control client ([CastHttpClient]) reads **response** heads;
 * - SSDP parses a bare **header block** out of a UDP datagram, which no HTTP library can do.
 *
 * Reading is **tolerant** (a bare LF is accepted; a missing reason phrase is accepted), writing is
 * **strict** (always CRLF). That asymmetry is Postel's rule applied where it earns its keep: the
 * peers here are embedded devices whose HTTP is approximate, while the peer reading *our* output
 * is one of those devices and deserves nothing to guess about.
 *
 * ### The threat model, stated once and applied everywhere
 *
 * Every byte this object parses comes from **a device on the local network that MuPlay did not
 * write** -- and, once Task 6's proxy exists, from any other app on the phone and any other host
 * on a coffee-shop Wi-Fi. Three limits follow, and none of them is decoration:
 *
 * - [MAX_LINE_BYTES]: without it, one line of `A`s exhausts the heap.
 * - [MAX_HEADERS]: without it, a block of empty headers does the same, one line at a time.
 * - a caller-supplied cap on every body ([readBody]): without it, a renderer that streams steadily
 *   is never interrupted by a per-read socket timeout, and the heap goes instead.
 *
 * The write side carries the mirror-image rule: **nothing that reaches a byte of output may
 * contain a CR, an LF or a NUL** ([headerLine], [requireToken]). A peer-supplied value that does
 * -- a `SOAPACTION` built from the service type in a device description, say -- would otherwise
 * end the header it is written into and begin one of its own, which is request splitting.
 */
object HttpWire {

  const val CRLF: String = "\r\n"

  /** The longest single line this codec will buffer. Generous for a `LOCATION`, fatal for a flood. */
  const val MAX_LINE_BYTES: Int = 8192

  /** The most headers this codec will accept in one block. */
  const val MAX_HEADERS: Int = 64

  fun readRequestHead(input: InputStream): HttpRequestHead {
    val startLine = readLine(input) ?: throw MalformedHttpException("connection closed before a request line arrived")
    // Split on the FIRST two spaces only: a target may not contain a space, but this keeps the
    // failure mode "reject" rather than "silently misparse".
    val parts = startLine.split(' ', limit = 3)
    if (parts.size != 3) {
      throw MalformedHttpException("malformed request line: \"$startLine\"")
    }
    return HttpRequestHead(parts[0], parts[1], parts[2], readHeaders(input))
  }

  fun readResponseHead(input: InputStream): HttpResponseHead {
    val startLine = readLine(input) ?: throw MalformedHttpException("connection closed before a status line arrived")
    val parts = startLine.split(' ', limit = 3)
    if (parts.size < 2) {
      throw MalformedHttpException("malformed status line: \"$startLine\"")
    }
    val code = parts[1].toIntOrNull()
      ?: throw MalformedHttpException("malformed status code \"${parts[1]}\" in \"$startLine\"")
    // A conformant server may send an empty reason phrase, in which case `parts` has two elements
    // and the trailing space has already been consumed by the split.
    val reason = if (parts.size == 3) parts[2].trim() else ""
    return HttpResponseHead(parts[0], code, reason, readHeaders(input))
  }

  /**
   * Parses a header block with no start line — the shape an SSDP reply arrives in once the
   * `HTTP/1.1 200 OK` has been taken off the front.
   *
   * End of input ends the block here, unlike on a socket. A datagram is not a stream: the packet
   * *is* the whole message, so a block that stops without its trailing blank line is complete
   * rather than truncated, and real M-SEARCH replies arrive in both shapes. On a socket the same
   * condition is a peer that died mid-head, and [readRequestHead]/[readResponseHead] reject it —
   * see `a stream that ends inside the header block is rejected, not returned half-parsed`.
   */
  fun parseHeaderBlock(text: String): HttpHeaders =
    readHeaders(text.byteInputStream(Charsets.US_ASCII), endOfInputEndsBlock = true)

  /**
   * The body that follows a head just read from [input], framed the way [headers] says and never
   * larger than [maxBytes].
   *
   * Three framings, and the fourth possibility is a refusal rather than a guess:
   *
   * 1. `Transfer-Encoding: chunked` -- decoded, trailer section discarded. Reading such a response
   *    as a flat stream (which is what an unconditional `readBytes()` does) returns the chunk
   *    sizes *inside* the body -- `b<CRLF><s:Envelope><CRLF>...` -- which the layer above then
   *    reports as an XML parse failure, at the wrong layer, blaming the renderer.
   * 2. Any other transfer-coding -- **rejected**. A wrong body is worse than a clean refusal.
   * 3. `Content-Length` -- exactly that many bytes, and only once the declared length has been
   *    checked against [maxBytes]. That check is what makes the narrowing to `Int` below safe: a
   *    declared `2147483648` used to reach `readNBytes` as a negative `Int` and raise
   *    `IllegalArgumentException` (not an `IOException`, so a caller guarding a network call
   *    missed it), and `4294967296` narrowed to exactly `0` and produced a **silently empty body**.
   * 4. Neither header -- everything until the peer closes, which is legal under `Connection: close`
   *    and is what several embedded renderers do. Bounded, because `soTimeout` is per read: a peer
   *    that keeps sending steadily resets it forever and interrupts nothing.
   *
   * `Transfer-Encoding` **and** `Content-Length` together is refused outright (RFC 9112 section
   * 6.3): that disagreement is the request-smuggling primitive, and this codec does not get to
   * pick a winner on a stream two readers would frame differently.
   *
   * @throws MalformedHttpException on any refusal above, and when the body outgrows [maxBytes].
   *   Always an `IOException`, because every caller here is guarding a socket.
   */
  fun readBody(input: InputStream, headers: HttpHeaders, maxBytes: Int): ByteArray {
    val codings = headers.all(TRANSFER_ENCODING)
      .flatMap { it.split(',') }
      .map { it.trim() }
      .filter { it.isNotEmpty() }
    // `contentLength()` is called for its refusal as much as for its value: two disagreeing
    // `Content-Length` headers are rejected here even when a transfer-coding is present too.
    val contentLength = headers.contentLength()
    if (codings.isNotEmpty()) {
      if (codings.size != 1 || !codings[0].equals(CHUNKED, ignoreCase = true)) {
        throw MalformedHttpException(
          "unsupported $TRANSFER_ENCODING \"${quoteSafely(codings.joinToString(", "))}\": this " +
            "codec implements \"$CHUNKED\" only, and refuses rather than mis-framing a body.",
        )
      }
      if (headers.all(HttpHeaders.CONTENT_LENGTH).isNotEmpty()) {
        throw MalformedHttpException(
          "a message carrying both $TRANSFER_ENCODING and ${HttpHeaders.CONTENT_LENGTH} frames " +
            "two ways depending on which one the reader believes -- the smuggling primitive.",
        )
      }
      return readChunkedBody(input, maxBytes)
    }
    if (contentLength == null) return readUntilClosed(input, maxBytes)
    if (contentLength > maxBytes) {
      throw MalformedHttpException(
        "declared ${HttpHeaders.CONTENT_LENGTH} $contentLength exceeds the $maxBytes byte cap on " +
          "one body",
      )
    }
    return input.readNBytes(contentLength.toInt())
  }

  /**
   * One `Name: value` line, CRLF-terminated, with both halves checked first.
   *
   * **The single place a header is written**, which is what makes the check worth having: every
   * renderer in this module goes through here, so a value that arrived from a renderer's own XML
   * (Task 3 builds `SOAPACTION` from the service type and action name in a device description) or
   * from Navidrome metadata (Task 6) cannot smuggle a header of its own into a request, whichever
   * task assembles it.
   *
   * @throws IllegalArgumentException for an empty name, a name that is not an RFC 9110 token, or a
   *   value holding anything but printable US-ASCII and HTAB -- CR, LF and NUL above all.
   */
  fun headerLine(name: String, value: String): String {
    requireToken("header name", name)
    requireFieldValue("header \"$name\"", value)
    return "$name: $value$CRLF"
  }

  /**
   * Checks that [token] is a non-empty RFC 9110 token: letters, digits and ``!#$%&'*+-.^_`|~``.
   *
   * [role] names what is being checked, so a message says which part of a request line was
   * refused. A method is a token too, and `"GET /x HTTP/1.1\r\nX-Injected: 1"` passed as one is
   * exactly the request-splitting vector a header value is.
   */
  fun requireToken(role: String, token: String) {
    require(token.isNotEmpty()) { "an empty $role cannot be written to the wire" }
    val offender = token.firstOrNull { !isTokenChar(it) }
    require(offender == null) {
      "illegal character ${describe(offender!!)} in $role \"${quoteSafely(token)}\": an HTTP " +
        "token is letters, digits and $TOKEN_PUNCTUATION, and nothing else."
    }
  }

  fun renderResponseHead(code: Int, reason: String, headers: HttpHeaders): ByteArray {
    require(code in MIN_STATUS_CODE..MAX_STATUS_CODE) {
      "status code $code is not an HTTP status code; writing it would produce a status line no " +
        "peer can parse."
    }
    requireFieldValue("the reason phrase", reason)
    val text = buildString {
      append("HTTP/1.1 ").append(code).append(' ').append(reason).append(CRLF)
      headers.asList().forEach { (name, value) -> append(headerLine(name, value)) }
      append(CRLF)
    }
    return text.toByteArray(Charsets.US_ASCII)
  }

  private fun readHeaders(input: InputStream, endOfInputEndsBlock: Boolean = false): HttpHeaders {
    val entries = ArrayList<Pair<String, String>>()
    while (true) {
      val line = readLine(input)
      if (line == null) {
        if (endOfInputEndsBlock) return HttpHeaders(entries)
        throw MalformedHttpException("connection closed inside a header block")
      }
      if (line.isEmpty()) return HttpHeaders(entries)
      if (entries.size == MAX_HEADERS) {
        throw MalformedHttpException("more than $MAX_HEADERS headers in one block")
      }
      val colon = line.indexOf(':')
      if (colon <= 0) {
        throw MalformedHttpException("malformed header line: \"$line\"")
      }
      // The name keeps its case; the value loses only the optional whitespace around it, which is
      // what RFC 7230 says OWS is. Interior spaces are part of the value.
      entries += line.substring(0, colon) to line.substring(colon + 1).trim()
    }
  }

  /** RFC 9112 section 7.1 chunked transfer coding. The trailer section is read and discarded. */
  private fun readChunkedBody(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readLine(input)
        ?: throw MalformedHttpException("connection closed where a chunk size was expected")
      // A chunk size may carry extensions after a `;`, which this codec has no use for.
      val size = sizeLine.substringBefore(';').trim().toIntOrNull(CHUNK_SIZE_RADIX)
        ?: throw MalformedHttpException("malformed chunk size \"${quoteSafely(sizeLine)}\"")
      if (size < 0) {
        throw MalformedHttpException("negative chunk size \"${quoteSafely(sizeLine)}\"")
      }
      if (out.size().toLong() + size > maxBytes) {
        throw MalformedHttpException("a chunked body exceeded the $maxBytes byte cap on one body")
      }
      if (size == 0) {
        // The trailer section, and the blank line that ends it. Tolerant of a peer that closes
        // instead of sending that blank line, for the same reason `parseHeaderBlock` is.
        readHeaders(input, endOfInputEndsBlock = true)
        return out.toByteArray()
      }
      val chunk = input.readNBytes(size)
      if (chunk.size != size) {
        throw MalformedHttpException("a chunk declared $size bytes and carried ${chunk.size}")
      }
      out.write(chunk)
      val terminator = readLine(input)
      if (terminator == null || terminator.isNotEmpty()) {
        throw MalformedHttpException("a chunk was not terminated by CRLF")
      }
    }
  }

  /**
   * Everything until the peer closes, and never more than [maxBytes].
   *
   * At most one read block past the cap is buffered before the refusal, which is the price of
   * noticing the overrun rather than blocking for a byte the peer may never send.
   */
  private fun readUntilClosed(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BODY_CHUNK_BYTES)
    while (true) {
      val read = input.read(buffer)
      if (read == -1) return out.toByteArray()
      out.write(buffer, 0, read)
      if (out.size() > maxBytes) {
        throw MalformedHttpException(
          "a body with no ${HttpHeaders.CONTENT_LENGTH} exceeded the $maxBytes byte cap: a peer " +
            "that keeps sending resets a per-read socket timeout forever",
        )
      }
    }
  }

  private fun requireFieldValue(role: String, value: String) {
    val offender = value.firstOrNull { !isFieldValueChar(it) }
    require(offender == null) {
      "illegal character ${describe(offender!!)} in the value of $role " +
        "(\"${quoteSafely(value)}\"): a CR, an LF or a NUL here ends the line it is written into " +
        "and begins one the peer chose, which is request splitting."
    }
  }

  private fun isTokenChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TOKEN_PUNCTUATION

  /** RFC 9110 `field-vchar` plus SP and HTAB. Non-ASCII is refused: this wire is US-ASCII. */
  private fun isFieldValueChar(c: Char): Boolean = c == '\t' || c in ' '..'~'

  private fun describe(c: Char): String = when (c.code) {
    CR_CODE -> "CR"
    LF_CODE -> "LF"
    NUL_CODE -> "NUL"
    else -> "U+%04X".format(c.code)
  }

  /** Never echoes a raw control character back into a message, a log line or a bug report. */
  private fun quoteSafely(text: String): String =
    text.map { if (isFieldValueChar(it) && it != '\t') "$it" else "\\u%04x".format(it.code) }
      .joinToString("")

  /**
   * One line, terminated by CRLF or by a bare LF. Returns `null` at end of stream, and an empty
   * string for the blank line that ends a header block — a distinction the caller depends on.
   *
   * The CR of a CRLF is held back rather than buffered, so [MAX_LINE_BYTES] counts the **line**
   * and not the line plus whichever terminator the peer chose. It used to count the CR: 8192 bytes
   * followed by a bare LF were accepted and the same 8192 bytes followed by CRLF were rejected,
   * one byte of a peer's line-ending deciding which. Fail-closed, so never a hole -- but no test
   * could observe the limit from the accepting side in both spellings, which is how it went
   * unnoticed. A held-back CR that turns out not to precede an LF is written out as the ordinary
   * content byte it is.
   */
  private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream(128)
    var pendingCr = false
    while (true) {
      val byte = input.read()
      if (byte == -1) {
        if (pendingCr) writeBounded(buffer, CR_CODE)
        return if (buffer.size() == 0) null else String(buffer.toByteArray(), Charsets.US_ASCII)
      }
      if (byte == LF_CODE) {
        return String(buffer.toByteArray(), Charsets.US_ASCII)
      }
      if (pendingCr) writeBounded(buffer, CR_CODE)
      pendingCr = byte == CR_CODE
      if (!pendingCr) writeBounded(buffer, byte)
    }
  }

  private fun writeBounded(buffer: ByteArrayOutputStream, byte: Int) {
    if (buffer.size() == MAX_LINE_BYTES) {
      throw MalformedHttpException("a line exceeded $MAX_LINE_BYTES bytes without a terminator")
    }
    buffer.write(byte)
  }

  /** RFC 9110 section 5.6.2 `tchar`, the alphabet of a method and of a header name. */
  private const val TOKEN_PUNCTUATION = "!#\$%&'*+-.^_`|~"
  private const val TRANSFER_ENCODING = "Transfer-Encoding"
  private const val CHUNKED = "chunked"
  private const val CHUNK_SIZE_RADIX = 16
  private const val MIN_STATUS_CODE = 100
  private const val MAX_STATUS_CODE = 599

  /** How much of a body is read at a time. Not a limit -- [readBody] takes that from its caller. */
  private const val BODY_CHUNK_BYTES = 8192
  private const val CR_CODE = 13
  private const val LF_CODE = 10
  private const val NUL_CODE = 0
}
