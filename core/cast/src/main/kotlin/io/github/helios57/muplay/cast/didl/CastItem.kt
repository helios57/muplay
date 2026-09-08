package io.github.helios57.muplay.cast.didl

/**
 * One track, as a renderer needs to be told about it.
 *
 * [resourceUrl] is the URL the **renderer** will fetch -- the proxy URL in the ordinary case, not
 * the Navidrome stream URL. Task 7 decides which, and this type carries whichever was decided.
 *
 * [resourceUrl] and [served] are the two legs of the three-way MIME invariant that live on the same
 * object, and nothing in this constructor forces them to agree -- deliberately, because Task 7 may
 * hand a renderer a URL this module did not mint. [MimeAgreement] is where that agreement is
 * checked, on the rendered document rather than on this object, so the check reads what the device
 * reads.
 */
data class CastItem(
  val mediaId: String,
  val title: String,
  val artist: String?,
  val albumTitle: String?,
  val artworkUri: String?,
  val durationMs: Long,
  val upnpClass: String,
  val resourceUrl: String,
  val served: ServedMedia,
) {

  /**
   * The two URLs redacted; everything a reader actually wants kept.
   *
   * [resourceUrl] is whatever the chosen route decided: a proxy URL whose path **is** the
   * capability token, or -- on [io.github.helios57.muplay.cast.route.CastRoute.RendererDirect] -- Navidrome's own
   * stream URL carrying `u`, `t` and `s`. [artworkUri] is the same story for the cover. So this
   * type holds, at various moments, every secret `:core:cast` has, and it was the one type on the
   * path that let the compiler print them.
   *
   * `null` stays `null` for [artworkUri]: whether a renderer was sent a cover at all is a real
   * question when a speaker shows no art, and "absent" is not a secret. [resourceUrl] is
   * non-nullable and always redacted.
   */
  override fun toString(): String =
    "CastItem(mediaId=$mediaId, title=$title, artist=$artist, albumTitle=$albumTitle, " +
      "artworkUri=${if (artworkUri == null) "null" else "<redacted>"}, durationMs=$durationMs, " +
      "upnpClass=$upnpClass, resourceUrl=<redacted>, served=$served)"
}
