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

    override val wireValue: String = WIRE_VALUE

    companion object {
      /**
       * `"mp3"`, as a constant, because two things outside this module have to *recognise* it and
       * neither of them holds a [StreamFormat].
       *
       * A `MediaItem` carries the wire value it was built with as a string (see `:core:media`'s
       * `MediaItems.KEY_STREAM_FORMAT` -- a `Bundle` cannot hold a sealed interface), and
       * `TranscodeSeek` decides how to seek by comparing against it. Both would otherwise compare
       * against a string literal `"mp3"`, in two files, neither of which would move if
       * [wireValue] ever did.
       */
      const val WIRE_VALUE: String = "mp3"
    }
  }

  companion object {

    /**
     * The bounds on the cap this client may *ask* for, not on what MP3 can encode.
     *
     * MPEG-1 Layer III runs 32–320 kbps and MPEG-2/2.5 Layer III go down to 8, so the low end
     * here is deliberately below every one of them: Navidrome answers a `maxBitRate` under its
     * own profile floor with a real 200 and real audio (measured against the container at 1, 2,
     * 5, 7, 8, 16 and 63 kbps), and `LiveNavidromeTest`'s cold-transcode search depends on that.
     * 320 is a genuine ceiling — above it the response is no longer MP3 — and 0 or negative is
     * not a request anyone can mean, which is what the `require` above rejects.
     */
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
     * `opus` is the rule spec section 4 states outright. `ogg` and `oga` are here because the
     * suffix cannot distinguish Ogg-Vorbis from Ogg-Opus, and Navidrome mislabels Opus as
     * `audio/ogg` anyway — so an Ogg file that is really Opus would arrive looking exactly like
     * one that is not. Transcoding all three is a small, visible cost; letting one through is a
     * silent one.
     *
     * This is the **whole Ogg family as the pinned server indexes it**, and stating it that way
     * is the point: `oga` was missing for one review round, `forSuffix("oga", …)` returned [Raw],
     * and nothing went red — a set like this fails silently by omission, so the source of truth
     * for its membership is written down. That source is `deluan/navidrome:0.63.2`'s own
     * audio-extension table, read out of the running container: `… m4a mp4 m4b m4p ogg oga aif
     * asf mpp ac3 als wav raw mid`, with `opus` and `flac` in the MIME table beside it. `oga` is
     * the IANA-registered Ogg *audio* extension and sits directly beside `ogg` there, so this
     * server indexes a `.oga` file and reports `suffix = "oga"` like any other. `mka` is not in
     * that table at all and `webm` appears only as a MIME type, never as an indexed audio
     * extension, so neither is here: adding them would be a guess, and this set is observations.
     *
     * `StreamFormatTest`'s `every suffix the ogg container is indexed under is transcoded` holds
     * the same list independently, so a member deleted from here reddens a test rather than
     * quietly widening what streams raw.
     */
    private val TRANSCODE_ONLY_SUFFIXES = setOf("opus", "ogg", "oga")

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
