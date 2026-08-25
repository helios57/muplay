package app.muplay.model

/**
 * The format this client is allowed to ask a Subsonic server for on `/rest/stream`.
 *
 * A sealed interface with exactly two members, deliberately. The global constraints say *"Stream
 * requests force `format=raw` or `format=mp3`. **Never Opus.**"*, and the way to enforce a rule
 * like that is to make the forbidden value unrepresentable rather than to check for it — the same
 * structural argument that makes `musicFolderId` a non-null `Int` everywhere in this codebase.
 * There is no `StreamFormat("opus")` to write.
 *
 * [Mp3] carries its bitrate cap because that cap is the only reason to prefer a transcode over
 * [Raw] at all; [Raw] has no bitrate property because `format=raw` disables transcoding outright
 * and a bitrate alongside it would be a parameter the server ignores. "Raw at 128 kbps" is not a
 * request anyone can make.
 */
sealed interface StreamFormat {

  /** The value sent as the `format` query parameter. */
  val wireValue: String

  /**
   * No transcoding: the server sends the file's own bytes.
   *
   * The strongly preferred choice, and not for bandwidth reasons. Verified against a real
   * `deluan/navidrome:0.63.2`: a raw response honours RFC 7233 `Range` (206/416, clamping,
   * byte-exact tail seek) and always carries an accurate `Content-Length`, never chunked, while a
   * **live transcode returns `Accept-Ranges: none` with no `Content-Length` at all** — which means
   * the seek bar cannot work. See `LiveNavidromeTest`, where both halves are pinned.
   */
  data object Raw : StreamFormat {
    override val wireValue: String = "raw"
  }

  /**
   * A server-side transcode to MP3, capped at [maxBitRateKbps] kilobits per second.
   *
   * Reached only through [forSuffix], and only for a source whose container could hold Opus.
   */
  data class Mp3(val maxBitRateKbps: Int) : StreamFormat {

    init {
      require(maxBitRateKbps in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) {
        "maxBitRateKbps must be in $MIN_BITRATE_KBPS..$MAX_BITRATE_KBPS, was $maxBitRateKbps"
      }
    }

    override val wireValue: String = "mp3"
  }

  companion object {

    /** MPEG-1 Layer III's own bitrate range. Below 32 is Layer III at MPEG-2 rates; above 320 is not MP3. */
    const val MIN_BITRATE_KBPS: Int = 1
    const val MAX_BITRATE_KBPS: Int = 320

    /**
     * The bitrate a forced transcode uses. High enough that the transcode is not the reason a
     * listener notices anything, low enough to stay well inside what Navidrome's default
     * transcoding profile will produce.
     */
    const val DEFAULT_TRANSCODE_BITRATE_KBPS: Int = 192

    /**
     * The formats whose bytes must never reach this client, keyed by the file suffix the mirror
     * carries.
     *
     * `opus` is the rule spec section 4 states outright. `ogg` is here because the suffix cannot
     * distinguish Ogg-Vorbis from Ogg-Opus, and Navidrome mislabels Opus as `audio/ogg` anyway —
     * so an `ogg` file that is really Opus would arrive looking exactly like one that is not.
     * Transcoding both is a small, visible cost; letting one through is a silent one.
     */
    private val TRANSCODE_ONLY_SUFFIXES = setOf("opus", "ogg")

    /**
     * The format to request for a source file with this [suffix].
     *
     * [Raw] for everything the client may stream as-is, [Mp3] at [transcodeBitRateKbps] for the
     * containers that could hold Opus. A `null` or unrecognised suffix streams raw: the mirror
     * may not know a suffix, and Media3 identifies the container by sniffing it, so raw is both
     * the correct and the honest answer — inventing a transcode for an unknown file would degrade
     * every FLAC whose suffix a future server stopped reporting.
     */
    fun forSuffix(suffix: String?, transcodeBitRateKbps: Int): StreamFormat =
      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(transcodeBitRateKbps) else Raw
  }
}
