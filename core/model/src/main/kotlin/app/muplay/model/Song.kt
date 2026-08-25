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
  /**
   * What the file's own ReplayGain tags say, or `null` for an untagged file.
   *
   * Carried on the song rather than on `media_progress` because the player needs it **before** the
   * track has ever been played: every track in a fresh library-scoped shuffle is a first play, and
   * a shuffled library is the exact situation ReplayGain exists for. It is also the reason
   * `MediaItems.of` did **not** grow a sixth parameter for it, the way `isAudiobook` and `format`
   * each had to: those two are not derivable from a `Song` at all, and this one is already on it.
   *
   * Defaulted so that no existing positional construction of this class had to move.
   */
  val replayGain: ReplayGain? = null,
)
