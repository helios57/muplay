package io.github.helios57.muplay.model

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

  /**
   * The file's path **relative to its library root**, as the server reports it — measured against
   * the CI Navidrome as `"Fourth Author/Multi Part Book/02 - Part Two.mp3"`, `/`-separated,
   * with no leading slash.
   *
   * This is what makes browsing and shuffling by folder a **local** question. With the path
   * mirrored, "every track under this folder, including its subfolders" is one `LIKE 'prefix/%'`
   * over the mirror: instant, offline, and recursive by construction. The alternative — walking
   * `getMusicDirectory` from the top on every request — is one round trip per directory for an
   * answer the app already has.
   *
   * Nullable because it is the server's to omit and no part of the app may depend on it being
   * present: a track with no path simply does not appear in the folder tree, and everything else
   * about it still works. Defaulted so that no existing positional construction of this class had
   * to move, the same as [replayGain] above.
   */
  val path: String? = null,
)
