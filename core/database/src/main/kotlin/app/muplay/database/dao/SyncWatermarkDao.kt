package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.muplay.database.entity.SyncWatermarkEntity

@Dao
abstract class SyncWatermarkDao {

  @Query("SELECT lastScan FROM sync_watermark WHERE id = ${SyncWatermarkEntity.SINGLETON_ID}")
  abstract suspend fun read(): String?

  suspend fun store(lastScan: String) = upsert(SyncWatermarkEntity(lastScan = lastScan))

  @Query("DELETE FROM sync_watermark")
  abstract suspend fun clear()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun upsert(entity: SyncWatermarkEntity)
}
