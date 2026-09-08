package io.github.helios57.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored track.
 *
 * There is no `contentKind` column, and there must never be one: Navidrome hardcodes
 * `child.Type = "music"` for every media file, audiobooks included, so any such column would be
 * a constant. [libraryId], matched against the user's `LibraryRole` assignment, is how this
 * application knows a track is an audiobook chapter.
 *
 * This table is a **cache of the server** and a reconcile deletes and re-inserts it wholesale.
 * Nothing durable may live here — playback position lives in `media_progress`, keyed by the same
 * server id, in a table no reconcile touches.
 *
 * **Primary key is `id` alone**, and `BrowseDao.observeSongs(albumId)` takes no `libraryId` of
 * its own, for the same reason [AlbumEntity] gives: Navidrome's `MediaFile` Go struct carries its
 * own `LibraryID` field (unlike `Artist`, which carries none and *is* globally shared — see
 * `ArtistEntity`'s doc for the bug that produced), and every song is additionally tied to one
 * physical file path, which cannot itself exist under two different library roots. See
 * `AlbumEntity`'s doc for the fuller investigation and its source citations.
 */
@Entity(
  tableName = "songs",
  indices = [Index("libraryId"), Index("albumId"), Index("sortTitle")],
)
data class SongEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val albumId: String?,
  val artistId: String?,
  val title: String,
  val albumName: String?,
  val artistName: String?,
  val trackNumber: Int?,
  val discNumber: Int?,
  val durationSeconds: Int,
  val suffix: String?,
  val coverArtId: String?,
  val sortTitle: String,
  /**
   * The listener's own thumb on this track, as the Subsonic `userRating` number: 5 promoted,
   * 1 demoted, 0 for no thumb.
   *
   * Stored as the wire number rather than as the `SongRating` enum so that no Room type converter
   * is needed and so that a rating written by another client -- three stars, say -- survives a
   * round trip through this mirror unchanged. `SongRating.ofUserRating` is what narrows it to the
   * three states this app acts on, and it is applied when the row is read rather than when it is
   * written.
   *
   * A rating is server truth, per user, so it belongs in this cache exactly the way a title does:
   * a reconcile re-fetches it, and losing it costs nothing. Contrast `media_progress`, which is
   * this app's own and lives in a table no reconcile touches.
   */
  val userRating: Int = 0,
  /**
   * The file's own ReplayGain, mirrored so the player has it **before** the track is first played.
   *
   * That timing is the whole reason these three columns are here rather than on `media_progress`:
   * a `media_progress` row only exists for an item with a history, and every track in a fresh
   * library-scoped shuffle is a first play -- which is precisely the queue this feature exists for.
   *
   * Three columns rather than an `@Embedded ReplayGain` because two of the three are independently
   * nullable and an embedded all-null instance is indistinguishable from an absent one -- the exact
   * collapse `SubsonicClient` refuses to make one layer up.
   */
  val replayGainTrackDb: Float? = null,
  val replayGainAlbumDb: Float? = null,
  val replayGainPeak: Float? = null,
  /**
   * The file's path relative to its library root, `/`-separated, as the server reports it
   * (`"Fourth Author/Multi Part Book/02 - Part Two.mp3"`).
   *
   * Mirrored so that browsing and shuffling by folder are local queries over this column rather
   * than a walk of the server's `getIndexes`/`getMusicDirectory` tree, which would need one
   * request per folder and would not work offline at all.
   *
   * **Nullable, and null means "not known yet" rather than "at the root".** A row written before
   * version 8 has no path until the next reconcile, and `""` would be a real prefix matching every
   * path -- so the folder queries below all require a non-null value and such a row is invisible
   * to them until it is refreshed. [io.github.helios57.muplay.database.MIGRATION_7_8] clears the sync watermark
   * so that refresh happens on the next poll rather than whenever the server next rescans.
   */
  val path: String? = null,
)
