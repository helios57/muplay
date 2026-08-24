package app.muplay.model

/**
 * One artist. [libraryId] is stamped from the scoped request, exactly as for [Album] — nothing in
 * an `ArtistID3` says which library the artist's music lives in.
 */
data class Artist(
  val id: String,
  val libraryId: Int,
  val name: String,
  val coverArtId: String?,
  val albumCount: Int,
)
