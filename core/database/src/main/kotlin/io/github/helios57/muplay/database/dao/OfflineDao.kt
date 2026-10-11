package io.github.helios57.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.helios57.muplay.database.entity.OfflinePlaylistEntity
import io.github.helios57.muplay.database.entity.OfflinePlaylistRow
import io.github.helios57.muplay.database.entity.OfflinePlaylistSongEntity
import io.github.helios57.muplay.database.entity.OfflineSongEntity
import io.github.helios57.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineDao {

  /**
   * Counted over songs the mirror still has: a song removed from the server since the playlist was
   * saved can never be downloaded, and counting it would leave "9 of 10" on screen forever.
   */
  @Query(
    "SELECT p.*, COUNT(m.id) AS total, COALESCE(SUM(CASE WHEN o.downloaded THEN 1 ELSE 0 END), 0) AS downloaded " +
      "FROM offline_playlists p " +
      "LEFT JOIN offline_playlist_songs e ON e.playlistId = p.id " +
      "LEFT JOIN songs m ON m.id = e.songId " +
      "LEFT JOIN offline_songs o ON o.songId = m.id " +
      "GROUP BY p.id ORDER BY p.name COLLATE NOCASE",
  )
  fun observe(): Flow<List<OfflinePlaylistRow>>

  @Query("SELECT * FROM offline_playlists WHERE id = :playlistId")
  suspend fun playlist(playlistId: String): OfflinePlaylistEntity?

  @Query(
    "SELECT m.* FROM offline_playlist_songs e JOIN songs m ON m.id = e.songId " +
      "WHERE e.playlistId = :playlistId ORDER BY e.position",
  )
  suspend fun songs(playlistId: String): List<SongEntity>

  @Query(
    "SELECT m.* FROM offline_songs o JOIN songs m ON m.id = o.songId WHERE NOT o.downloaded " +
      "ORDER BY m.id",
  )
  suspend fun pending(): List<SongEntity>

  @Query("SELECT songId FROM offline_songs WHERE downloaded")
  suspend fun downloadedIds(): List<String>

  @Upsert
  suspend fun upsertPlaylist(playlist: OfflinePlaylistEntity)

  @Insert
  suspend fun insertEntries(entries: List<OfflinePlaylistSongEntity>)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertSongs(songs: List<OfflineSongEntity>)

  @Query("DELETE FROM offline_playlist_songs WHERE playlistId = :playlistId")
  suspend fun deleteEntries(playlistId: String)

  @Query("DELETE FROM offline_playlists WHERE id = :playlistId")
  suspend fun deletePlaylist(playlistId: String)

  @Query("SELECT songId FROM offline_songs WHERE songId NOT IN (SELECT songId FROM offline_playlist_songs)")
  suspend fun orphans(): List<String>

  @Query("DELETE FROM offline_songs WHERE songId IN (:songIds)")
  suspend fun deleteSongs(songIds: List<String>)

  @Query("UPDATE offline_songs SET downloaded = 1 WHERE songId = :songId")
  suspend fun markDownloaded(songId: String): Int

  /** Saving again replaces the song order: the playlist may have changed on the server since. */
  @Transaction
  suspend fun save(
    playlist: OfflinePlaylistEntity,
    entries: List<OfflinePlaylistSongEntity>,
  ): List<String> {
    upsertPlaylist(playlist)
    deleteEntries(playlist.id)
    insertEntries(entries)
    insertSongs(entries.map { OfflineSongEntity(it.songId, downloaded = false) })
    return orphans().also { if (it.isNotEmpty()) deleteSongs(it) }
  }

  /** Forgets a playlist, and returns the songs no other saved playlist holds. */
  @Transaction
  suspend fun remove(playlistId: String): List<String> {
    deleteEntries(playlistId)
    deletePlaylist(playlistId)
    return orphans().also { if (it.isNotEmpty()) deleteSongs(it) }
  }

  @Query("DELETE FROM offline_playlist_songs")
  suspend fun clearEntries()

  @Query("DELETE FROM offline_playlists")
  suspend fun clearPlaylists()

  @Query("DELETE FROM offline_songs")
  suspend fun clearSongs()

  @Transaction
  suspend fun clear() {
    clearEntries()
    clearPlaylists()
    clearSongs()
  }
}
