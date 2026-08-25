package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last `getScanStatus.lastScan` value whose reconcile **committed**.
 *
 * One row, always id [SINGLETON_ID]. Navidrome's `lastScan` is global rather than per-library, so
 * a per-library watermark would be inventing a distinction the server does not make.
 *
 * [lastScan] is stored as the server's own string and never parsed: the only question asked of it
 * is whether it is the same string as before.
 */
@Entity(tableName = "sync_watermark")
data class SyncWatermarkEntity(
  @PrimaryKey val id: Int = SINGLETON_ID,
  val lastScan: String,
) {
  companion object {
    const val SINGLETON_ID = 0
  }
}
