package app.muplay.integrations.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per submitted request.
 *
 * [service] is stored as the enum's `name`, not its ordinal: an ordinal is a number whose meaning
 * changes when someone reorders the enum, and `IntegrationService`'s declaration order is
 * deliberately load-bearing for rendering.
 *
 * `status` and `statusDetail` are two plain TEXT columns rather than a serialised
 * `RequestStatus` — see that type's own doc for why.
 */
@Entity(tableName = "media_requests")
data class MediaRequestEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(index = true) val service: String,
  val externalId: String,
  val title: String,
  val subtitle: String,
  val remoteId: String?,
  val status: String,
  val statusDetail: String?,
  val requestedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)
