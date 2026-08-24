package app.muplay.model

/**
 * One track, as mirrored from a Subsonic `Child`.
 *
 * There is deliberately no `contentKind`/`isAudiobook` property. Navidrome hardcodes
 * `child.Type = "music"` for **every** media file — confirmed against the real container, where
 * the seeded `Test Book.m4b` comes back as `"type": "music"`, `"mediaType": "song"` — so the
 * protocol simply cannot tell a client that something is an audiobook. [libraryId], stamped from
 * the scoped request and matched against the user's own `LibraryRole` assignment, is the only
 * mechanism there is.
 */
data class Song(
  val id: String,
  val libraryId: Int,
  val title: String,
  val albumId: String?,
  val albumName: String?,
  val artistId: String?,
  val artistName: String?,
  val trackNumber: Int?,
  val discNumber: Int?,
  val durationSeconds: Int,
  val suffix: String?,
  val coverArtId: String?,
)
