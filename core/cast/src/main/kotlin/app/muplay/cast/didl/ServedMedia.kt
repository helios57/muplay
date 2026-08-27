package app.muplay.cast.didl

import app.muplay.model.StreamFormat

/**
 * **The one statement of what format a renderer is about to receive.**
 *
 * Three parties have to agree, and each of them gets it from here:
 *
 * - the **URL** the renderer fetches ends in `.$fileExtension`, because spec section 6 records
 *   that *"Sonos ... infers MIME **from the URL, not `Content-Type`**"* -- a path with the wrong
 *   extension, or none, is `714 Illegal MIME-type`;
 * - the **DIDL** metadata declares [protocolInfo], which is what a generic DLNA renderer trusts;
 * - the **proxy** serves [mimeType] as its `Content-Type`, which is what everyone except Sonos
 *   believes.
 *
 * Three statements of one fact is two chances to disagree. One value removes both.
 *
 * That is the *design*. What makes it a fact rather than an intention is [MimeAgreement], which
 * re-derives all three legs from the artifacts the three parties actually see -- the `<res>` URL's
 * own extension, the `protocolInfo` attribute parsed back out of the rendered document, and the
 * `Content-Type` header string -- and names every way they disagree. A comment cannot fail; that
 * can.
 */
data class ServedMedia(val mimeType: String, val fileExtension: String) {

  /**
   * `http-get:*:<mime>:<fourth field>`.
   *
   * `DLNA.ORG_OP=01` sets the byte-seek bit, which is a **promise** the proxy has to keep: a
   * renderer reading it may issue `Range` requests and expect 206. Task 6 keeps it, and asserts
   * that it does against this very string.
   *
   * `DLNA.ORG_PN` is absent on purpose. A profile name identifies an exact encoding, a wrong one is
   * a hard rejection on a strict renderer, and nothing Navidrome reports is precise enough to
   * compute one. Absent means "work it out from the bytes", which every renderer can do.
   */
  val protocolInfo: String get() = "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=$DLNA_FLAGS"

  /**
   * The last path segment of the URL the renderer will fetch: [stem] with this format's extension.
   *
   * The **only** place a proxy path's extension is minted, which is what stops the URL leg drifting
   * from the other two. Task 6's `MediaProxyServer` mints its paths through this rather than by
   * appending a suffix of its own, so "the extension the URL carries" and "the extension
   * [protocolInfo]'s MIME implies" are the same expression evaluated once.
   */
  fun fileName(stem: String): String = "$stem.$fileExtension"

  companion object {

    /**
     * The conventional DLNA 1.5 flag word: streaming transfer mode, background transfer allowed,
     * connection stall allowed, DLNA v1.5. 32 hex digits, of which the leading eight carry the
     * meaning and the remainder are reserved zeroes.
     */
    const val DLNA_FLAGS: String = "01700000000000000000000000000000"

    /**
     * What an unrecognised suffix is served as.
     *
     * `application/octet-stream` would be refused outright by Sonos, and this is a fallback for a
     * case the mirror almost never produces. MP3 is the guess most likely to play, and if it is
     * wrong the renderer sniffs the bytes -- which every renderer does when the profile is not
     * pinned.
     */
    const val FALLBACK_MIME: String = "audio/mpeg"
    const val FALLBACK_EXTENSION: String = "mp3"

    /**
     * Suffix to (MIME, extension) for a **raw** stream.
     *
     * `oga` maps to `audio/mpeg` and `mp3` because it can only arrive here via a forced transcode
     * -- an `.oga` file is Ogg, `StreamFormat.forSuffix` transcodes Ogg (spec section 4: the suffix
     * cannot rule out Opus), so the `Raw` branch is unreachable for it. Mapping it to `audio/ogg`
     * would encode a lie that nothing can execute.
     *
     * **Every value in this map round-trips through its own extension**, i.e.
     * `RAW_TYPES[v.fileExtension]!!.mimeType == v.mimeType` for every `v`. That is not a tidiness
     * property, it is the URL leg of the three-way invariant: a renderer that ignores
     * `Content-Type` and `protocolInfo` and sniffs `.$fileExtension` off the URL has to reach the
     * same MIME this entry declares. `ServedMediaTest`'s round-trip test holds it, and it is the
     * assertion that would have caught an `"oga" to ServedMedia("audio/ogg", "mp3")` -- a pair
     * that looks reasonable, reads reasonably, and promises Ogg on a `.mp3` URL.
     */
    private val RAW_TYPES: Map<String, ServedMedia> = mapOf(
      "mp3" to ServedMedia("audio/mpeg", "mp3"),
      "flac" to ServedMedia("audio/flac", "flac"),
      "m4a" to ServedMedia("audio/mp4", "m4a"),
      "m4b" to ServedMedia("audio/mp4", "m4b"),
      "mp4" to ServedMedia("audio/mp4", "mp4"),
      "alac" to ServedMedia("audio/mp4", "m4a"),
      "aac" to ServedMedia("audio/aac", "aac"),
      "wav" to ServedMedia("audio/wav", "wav"),
      "aiff" to ServedMedia("audio/aiff", "aiff"),
      "aif" to ServedMedia("audio/aiff", "aiff"),
      "wma" to ServedMedia("audio/x-ms-wma", "wma"),
      "oga" to ServedMedia("audio/mpeg", "mp3"),
    )

    /** Every mapping, for the round-trip property [RAW_TYPES]'s own doc states. Test-facing. */
    val rawTypes: Map<String, ServedMedia> get() = RAW_TYPES

    /**
     * What the renderer will actually receive.
     *
     * **[format] wins over [suffix], and that is the whole point.** With [StreamFormat.Mp3] --
     * which is what `StreamFormat.forSuffix` returns for `opus` and `ogg`, per spec section 4's
     * *"Never Opus"* -- Navidrome transcodes and the bytes are MP3 whatever the source was.
     * Deriving from the suffix alone would promise Sonos `audio/ogg`, hand it a `.opus` URL, and
     * serve MP3; Sonos would refuse the format it was promised, which is spec section 12's
     * "Sonos rejects a served format" risk arriving in the most confusing possible form.
     */
    fun of(suffix: String?, format: StreamFormat): ServedMedia = when (format) {
      is StreamFormat.Mp3 -> ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)
      StreamFormat.Raw ->
        RAW_TYPES[suffix?.lowercase()] ?: ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)
    }

    /**
     * **The URL leg, read the way a renderer reads it**: what a device that sniffs MIME from a
     * path ending `.$extension` will conclude, or `null` for an extension nothing here recognises.
     *
     * The inverse of [of]'s `Raw` branch, and deliberately *not* falling back to [FALLBACK_MIME]:
     * `of` guesses because it must produce something, whereas this answers a question about what a
     * *peer* will believe, and "I do not know what it will believe" is a different answer from
     * "MP3". [MimeAgreement] depends on that difference -- a URL ending `.opus` must read as
     * unknown, not as MP3, or the disagreement it exists to report would be silently agreed away.
     */
    fun forExtension(extension: String?): ServedMedia? = RAW_TYPES[extension?.lowercase()]

    /**
     * **The MIME leg, read backwards**: what this app must serve, and under what extension, for a
     * body it has already decided is [mimeType] -- or `null` for a MIME nothing here recognises.
     *
     * The inverse of [of]'s *result* rather than of its arguments, and it exists because a
     * `MediaItem` carries the served MIME (`MediaItems.of` puts `ServedMedia.of(suffix, format)
     * .mimeType` on it) and nothing else: by the time a queue reaches the cast layer the `Song`'s
     * suffix and the `StreamFormat` it was fetched with are both gone. Re-deriving them there
     * would be a second decision about one fact, free to drift from the URL the item already
     * carries -- the exact failure [of]'s own note describes in the other direction.
     *
     * **Answering with the first entry whose MIME matches is correct rather than arbitrary**,
     * because [RAW_TYPES] round-trips: every value satisfies
     * `RAW_TYPES[v.fileExtension]!!.mimeType == v.mimeType`, so whichever entry is picked, a
     * renderer that sniffs `.$fileExtension` off the URL reaches the same MIME the `protocolInfo`
     * and the `Content-Type` declare. `audio/mp4` has three entries (`m4a`, `m4b`, `mp4`) and all
     * three are the same container; the three-way invariant holds for any of them.
     *
     * `null` rather than [FALLBACK_MIME] for the same reason [forExtension] answers `null`: this
     * is a question about a value that *came from somewhere*, and "I do not recognise it" is a
     * different answer from "MP3". The caller decides what an unrecognised MIME deserves.
     */
    fun forMimeType(mimeType: String?): ServedMedia? =
      RAW_TYPES.values.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }
  }
}
