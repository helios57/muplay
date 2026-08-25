package app.muplay.cast.didl

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
)
