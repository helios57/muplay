package io.github.helios57.muplay.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A playlist the listener asked to keep on the phone, as it was when they asked. Kept so the
 * playlist can be listed and opened with no connection -- the live playlist screens fetch from the
 * server every time and show nothing offline.
 */
@Entity(tableName = "offline_playlists")
data class OfflinePlaylistEntity(
  @PrimaryKey val id: String,
  val name: String,
  val songCount: Int,
  val durationSeconds: Int,
  val owner: String?,
  val coverArtId: String?,
  val savedAtEpochMs: Long,
)

/** A saved playlist's song order. The songs themselves are read from the library mirror. */
@Entity(
  tableName = "offline_playlist_songs",
  primaryKeys = ["playlistId", "position"],
  indices = [Index("songId")],
)
data class OfflinePlaylistSongEntity(
  val playlistId: String,
  val position: Int,
  val songId: String,
)

/**
 * One row per song any saved playlist holds, and whether its audio is on the phone yet. Per song
 * rather than per entry: a song in two saved playlists is downloaded once.
 */
@Entity(tableName = "offline_songs")
data class OfflineSongEntity(
  @PrimaryKey val songId: String,
  val downloaded: Boolean,
)

/** A saved playlist and how much of it is on the phone. */
data class OfflinePlaylistRow(
  @Embedded val playlist: OfflinePlaylistEntity,
  val total: Int,
  val downloaded: Int,
)
