package app.muplay.integrations.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaRequestDao {

  /**
   * Newest first. The `ORDER BY` is not decoration: without it SQLite returns rowid order, which
   * is insertion order, which is not what the UI promises — and `MediaRequestRepositoryTest`
   * inserts in an order that makes the difference visible.
   */
  @Query("SELECT * FROM media_requests ORDER BY requestedAtEpochMs DESC, id ASC")
  fun observeAll(): Flow<List<MediaRequestEntity>>

  @Query(
    "SELECT * FROM media_requests WHERE service = :service ORDER BY requestedAtEpochMs DESC, id ASC",
  )
  fun observeByService(service: String): Flow<List<MediaRequestEntity>>

  @Query("SELECT * FROM media_requests WHERE id = :id")
  suspend fun find(id: String): MediaRequestEntity?

  @Upsert
  suspend fun upsert(entity: MediaRequestEntity)

  @Query(
    "UPDATE media_requests SET status = :status, statusDetail = :statusDetail, " +
      "updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id",
  )
  suspend fun updateStatus(id: String, status: String, statusDetail: String?, updatedAtEpochMs: Long)

  @Query("DELETE FROM media_requests WHERE id = :id")
  suspend fun delete(id: String)
}
