package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.muplay.database.entity.MediaProgressEntity
import kotlinx.coroutines.flow.Flow

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

  /**
   * The whole table, as a Flow. Backs the audiobook snapshot (Task 6), which exists because
   * `ResumePolicy.resolve` runs on the player's application thread and must not touch Room --
   * Plan 3's own `ResumePolicy` documentation says so.
   */
  @Query("SELECT * FROM media_progress")
  fun observeAll(): Flow<List<MediaProgressEntity>>

  /**
   * The rows for a specific set of media ids -- one book's files, typically.
   *
   * Not named `findAll(mediaIds)`: an overload of the existing no-argument [findAll] would compile
   * and would read, at every call site, as though it might be the other one.
   *
   * SQLite binds each element of `:mediaIds` as its own host variable, and there is a
   * per-statement limit (999 on the SQLite versions this app's `minSdk 26` floor can meet). A book
   * with more files than that does not exist, but a caller passing a whole library's song list
   * would fail at runtime with `too many SQL variables` -- call this with a book's files, not a
   * library's.
   */
  @Query("SELECT * FROM media_progress WHERE mediaId IN (:mediaIds)")
  suspend fun findIn(mediaIds: List<String>): List<MediaProgressEntity>

  /**
   * "Start this book from the beginning."
   *
   * Expressed as *clearing progress* rather than as overriding a position, because the
   * `ForwardingPlayer` seam (Plan 3 Task 8) makes a caller-chosen position unreachable --
   * correctly. Deleting the row is also the more honest state: there is no position, rather than a
   * position that happens to be zero and a `lastPlayedAt` that says the listener was there.
   */
  @Query("DELETE FROM media_progress WHERE mediaId IN (:mediaIds)")
  suspend fun clear(mediaIds: List<String>)
}
