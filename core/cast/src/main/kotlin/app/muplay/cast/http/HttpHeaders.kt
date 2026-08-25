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
 * @param entries name/value pairs in wire order. Names keep the case the peer used.
 */
class HttpHeaders(private val entries: List<Pair<String, String>>) {

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
   */
  fun contentLength(): Long? = this["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }

  override fun toString(): String = entries.joinToString(", ") { "${it.first}: ${it.second}" }

  companion object {
    val EMPTY = HttpHeaders(emptyList())

    fun of(vararg pairs: Pair<String, String>): HttpHeaders = HttpHeaders(pairs.toList())
  }
}
