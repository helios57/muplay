package app.muplay.cast.http

/**
 * An HTTP/1.1 header block: **case-insensitive on lookup, order-preserving on iteration, and
 * multi-valued**.
 *
 * Not a `Map<String, String>`, because a `Map` gets all three of those wrong. Sonos sends
 * `CONTENT-TYPE`, an SSDP reply sends `LOCATION`, and most DLNA renderers send `Content-Type` --
 * a map lookup on the wrong case returns null, and a null `LOCATION` is a device that never
 * appears in the picker with nothing reported anywhere.
 *
 * Order is preserved because this type is also used to *render* responses (see [HttpWire]), and a
 * byte-exact assertion on a rendered response head is only writable if the order is deterministic.
 *
 * @param entries name/value pairs in wire order. Names keep the case the peer used. Copied on
 *   construction: this block is handed to a parser, a renderer and a body reader in turn, and a
 *   caller that kept a reference to a mutable list could otherwise change what `Content-Length`
 *   says between the check and the read.
 */
class HttpHeaders(entries: List<Pair<String, String>>) {

  private val entries: List<Pair<String, String>> = entries.toList()

  /** The first value for [name], or `null` if the peer sent no such header. */
  operator fun get(name: String): String? = all(name).firstOrNull()

  /** Every value for [name], in wire order. Empty if the peer sent none. */
  fun all(name: String): List<String> =
    entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

  /** Header names in wire order, with the case the peer used. */
  val names: List<String> get() = entries.map { it.first }

  val size: Int get() = entries.size

  fun asList(): List<Pair<String, String>> = entries

  /**
   * `Content-Length` as a non-negative `Long`, or `null` when it is absent or is not one.
   *
   * A negative value is treated as absent rather than passed through: `Content-Length: -1` is what
   * a broken server sends to mean "I don't know", and returning it would let a caller allocate or
   * loop against it. "Absent" is the honest translation.
   *
   * **Conflicting values are refused, not resolved.** RFC 9110 section 8.6 requires it, and the
   * reason is the classic one: a message carrying `Content-Length: 3` and `Content-Length: 10`
   * frames as two different messages depending on which value the reader believes, which is the
   * request-smuggling primitive itself. Taking the first value silently -- what this returned
   * before -- picks a side and hides the disagreement. Repeats that *agree* are accepted and
   * collapsed, as RFC 9110 permits, because a proxy duplicating a header is not an attack. A
   * comma-joined list (`Content-Length: 3, 10`) is the same disagreement in one line and is read
   * the same way.
   *
   * @throws MalformedHttpException when the peer sent two values that do not agree.
   */
  fun contentLength(): Long? {
    val values = all(CONTENT_LENGTH).flatMap { it.split(',') }.map { it.trim() }
    val distinct = values.distinct()
    if (distinct.size > 1) {
      throw MalformedHttpException(
        "conflicting $CONTENT_LENGTH values (${distinct.joinToString(", ")}): a message that " +
          "frames two ways is a smuggling primitive, not a message this codec will guess at.",
      )
    }
    return distinct.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 }
  }

  override fun toString(): String = entries.joinToString(", ") { "${it.first}: ${it.second}" }

  companion object {
    val EMPTY = HttpHeaders(emptyList())

    /** Named once: [contentLength] reads it and [CastHttpClient] refuses to let a caller write it. */
    const val CONTENT_LENGTH: String = "Content-Length"

    fun of(vararg pairs: Pair<String, String>): HttpHeaders = HttpHeaders(pairs.toList())
  }
}
