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
)
