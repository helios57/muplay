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
 * The two size limits are not decoration. This parser reads from a device on the local network
 * that MuPlay did not write; without them, one line of `A`s exhausts the heap.
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

  fun renderResponseHead(code: Int, reason: String, headers: HttpHeaders): ByteArray {
    val text = buildString {
      append("HTTP/1.1 ").append(code).append(' ').append(reason).append(CRLF)
      headers.asList().forEach { (name, value) ->
        append(name).append(": ").append(value).append(CRLF)
      }
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

  /**
   * One line, terminated by CRLF or by a bare LF. Returns `null` at end of stream, and an empty
   * string for the blank line that ends a header block — a distinction the caller depends on.
   */
  private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream(128)
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (buffer.size() == 0) null else String(buffer.toByteArray(), Charsets.US_ASCII)
      if (byte == '\n'.code) {
        val bytes = buffer.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      if (buffer.size() == MAX_LINE_BYTES) {
        throw MalformedHttpException("a line exceeded $MAX_LINE_BYTES bytes without a terminator")
      }
      buffer.write(byte)
    }
  }
}
