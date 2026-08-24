package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.muplay.database.entity.MediaProgressEntity

@Dao
interface MediaProgressDao {

  @Upsert
  suspend fun upsert(progress: MediaProgressEntity)

  @Query("SELECT * FROM media_progress WHERE mediaId = :mediaId")
  suspend fun find(mediaId: String): MediaProgressEntity?

  @Query("SELECT * FROM media_progress")
  suspend fun findAll(): List<MediaProgressEntity>

  @Query(
    "SELECT * FROM media_progress WHERE isFinished = 0 " +
      "ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit",
  )
  suspend fun recentlyPlayed(limit: Int): List<MediaProgressEntity>
}
