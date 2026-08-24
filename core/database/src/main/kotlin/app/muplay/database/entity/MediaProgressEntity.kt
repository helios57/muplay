package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single source of truth for "where was I in this item".
 *
 * There is exactly one of these tables. Music queues and audiobook queues are two pointer lists
 * over it, so switching from a book to music touches no row here — which is the entire reason a
 * book keeps its exact position across an intervening music session.
 *
 * [mediaId] is the server's stable id, never a rowid: a re-scan on the server must not orphan a
 * listener's progress.
 *
 * Nothing about queue membership belongs in this table. If you find yourself adding a
 * `queuePosition` or `isInQueue` column, the design has been inverted.
 *
 * [lastPlayedAtEpochMs] is milliseconds since the epoch rather than an `Instant`: spec §3 writes
 * the field as an `Instant`, but nothing in this plan writes this table, so adding a
 * `kotlinx-datetime` dependency and a Room type converter for a column no code sets would be
 * speculative. The plan that starts writing progress converts at its own boundary.
 */
@Entity(tableName = "media_progress")
data class MediaProgressEntity(
  @PrimaryKey val mediaId: String,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
  val speed: Float,
  val skipSilence: Boolean,
  val gainDb: Float,
)
