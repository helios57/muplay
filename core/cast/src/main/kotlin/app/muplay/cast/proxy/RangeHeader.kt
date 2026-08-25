package app.muplay.cast.proxy

/** An inclusive byte range, both ends already resolved against a known length. */
data class ByteRange(val firstByte: Long, val lastByte: Long) {
  /**
   * Inclusive on both ends, so this is `last - first + 1`.
   *
   * Off by one here is off by one in `Content-Length`, which is a renderer waiting for a byte that
   * never arrives -- or one that stops a byte early and calls the track corrupt.
   */
  val length: Long get() = lastByte - firstByte + 1
}

/** What a `Range` header asked for, before the entity's length is known. */
sealed interface RangeRequest {

  /** No `Range` header at all. */
  data object Absent : RangeRequest

  /**
   * A `Range` header this server will not act on -- malformed, a unit other than `bytes`, or a
   * multi-range request.
   *
   * **Not the same as unsatisfiable.** RFC 7233 permits a server to ignore a range it does not
   * understand and answer 200 with the whole entity, and that is what this means. A 416 here would
   * refuse a request that should have been served whole.
   */
  data object Ignored : RangeRequest

  /** `bytes=first-last` or `bytes=first-`. */
  data class Bounded(val firstByte: Long, val lastByte: Long?) : RangeRequest

  /** `bytes=-n`: the last `n` bytes. */
  data class Suffix(val lastBytes: Long) : RangeRequest
}

/** What should actually be sent, once the entity's length is known. */
sealed interface RangeResolution {

  /** 200, the whole entity. */
  data object Whole : RangeResolution

  /** 206, with a `Content-Range`. */
  data class Partial(val range: ByteRange) : RangeResolution

  /** 416, and a `Content-Range` that names no range at all -- only the entity's real length. */
  data object Unsatisfiable : RangeResolution
}

/**
 * RFC 7233 `Range` parsing and resolution.
 *
 * Split from the server so that the eleven cases in this task's table are gated in Tier 1 without
 * a socket -- the same reason `StreamRetryPolicy` is a pure object in Plan 3.
 *
 * Only **single** ranges are served. A multi-range request would need a `multipart/byteranges`
 * body; no renderer sends one, and answering 200 with the whole entity is both legal and what a
 * renderer can use.
 *
 * ### The two distinctions this object exists to keep apart
 *
 * A `Range` a server cannot read and a `Range` a server cannot satisfy are different failures with
 * different answers, and collapsing them is wrong in both directions. `bytes=abc` is **ignored**
 * (200, the whole entity); `bytes=1000-` against a 1000-byte resource is **unsatisfiable** (416, and a
 * `Content-Range` whose range field is a bare asterisk followed by the real length). A server that 416s a malformed header refuses a request it
 * should have served; one that 200s an unsatisfiable range hands a seeking renderer the *start* of
 * the file, and it plays from the beginning again with nothing reported anywhere.
 *
 * And `bytes=-0` is unsatisfiable rather than "the whole thing": a suffix length of zero names no
 * bytes at all.
 */
object RangeHeader {

  private val BOUNDED = Regex("""^\s*(\d+)\s*-\s*(\d*)\s*$""")
  private val SUFFIX = Regex("""^\s*-\s*(\d+)\s*$""")

  fun parse(value: String?): RangeRequest {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return RangeRequest.Absent
    if (!text.substringBefore('=').trim().equals(BYTES_UNIT, ignoreCase = true)) return RangeRequest.Ignored
    val spec = text.substringAfter('=', missingDelimiterValue = "")
    // Multi-range: legal, and never sent by a renderer. Ignoring it answers 200, which is legal too.
    if (spec.contains(',')) return RangeRequest.Ignored

    SUFFIX.matchEntire(spec)?.let {
      // `toLongOrNull`, not `toLong`: `bytes=-99999999999999999999` matches this regex and would
      // otherwise throw NumberFormatException out of a parser whose whole contract is that it
      // returns a decision for every string a peer can send. A number this codec cannot hold is a
      // header it does not understand, which is exactly what `Ignored` means.
      val lastBytes = it.groupValues[1].toLongOrNull() ?: return RangeRequest.Ignored
      return RangeRequest.Suffix(lastBytes)
    }
    val bounded = BOUNDED.matchEntire(spec) ?: return RangeRequest.Ignored
    val first = bounded.groupValues[1].toLongOrNull() ?: return RangeRequest.Ignored
    // Same overflow argument as the suffix above, on the other end of the range. A `-1` from a
    // `takeIf`-less `toLongOrNull` would be indistinguishable from an absent last-byte-pos, so the
    // empty case is decided before the parse rather than by its result.
    val lastText = bounded.groupValues[2].takeIf { it.isNotEmpty() }
    val last = if (lastText == null) null else lastText.toLongOrNull() ?: return RangeRequest.Ignored
    // `bytes=5-2` is not a range. Ignored rather than 416: it is malformed, not unsatisfiable.
    if (last != null && last < first) return RangeRequest.Ignored
    return RangeRequest.Bounded(first, last)
  }

  fun resolve(request: RangeRequest, totalLength: Long): RangeResolution = when (request) {
    RangeRequest.Absent, RangeRequest.Ignored -> RangeResolution.Whole

    is RangeRequest.Bounded -> when {
      totalLength <= 0 -> RangeResolution.Unsatisfiable
      // `>=`, not `>`: the last valid offset is `totalLength - 1`.
      request.firstByte >= totalLength -> RangeResolution.Unsatisfiable
      else -> RangeResolution.Partial(
        // Clamped, not refused: a renderer asking past the end is asking for "the rest", and 416
        // there would stall playback near the end of every track.
        ByteRange(request.firstByte, (request.lastByte ?: (totalLength - 1)).coerceAtMost(totalLength - 1)),
      )
    }

    is RangeRequest.Suffix -> when {
      totalLength <= 0 -> RangeResolution.Unsatisfiable
      // `bytes=-0` names no bytes at all. Reading it as "everything" hands a renderer the start of
      // the file when it asked for nothing.
      request.lastBytes <= 0 -> RangeResolution.Unsatisfiable
      else -> RangeResolution.Partial(
        ByteRange((totalLength - request.lastBytes).coerceAtLeast(0), totalLength - 1),
      )
    }
  }

  private const val BYTES_UNIT = "bytes"
}
