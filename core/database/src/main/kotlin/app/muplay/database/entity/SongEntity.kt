package app.muplay.database.entity

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
)
