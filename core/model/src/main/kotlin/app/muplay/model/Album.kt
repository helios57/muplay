package app.muplay.model

/**
 * One album, as mirrored from a Subsonic `AlbumID3`.
 *
 * [libraryId] is **not** in any Subsonic response — `AlbumID3` has no such property and Navidrome
 * sends none. It is stamped by the network layer from the `musicFolderId` the request was scoped
 * to, which makes the scoped request the single source of truth about which library a row belongs
 * to. Every consumer that filters by library depends on that, including library-scoped shuffle.
 */
data class Album(
  val id: String,
  val libraryId: Int,
  val name: String,
  val artistId: String?,
  val artistName: String?,
  val coverArtId: String?,
  val songCount: Int,
  val durationSeconds: Int,
)

/** One album together with its tracks, as returned by `getAlbum`. */
data class AlbumWithSongs(
  val album: Album,
  val songs: List<Song>,
)
