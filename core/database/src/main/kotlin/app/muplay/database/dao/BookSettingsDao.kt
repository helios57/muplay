package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.muplay.database.entity.BookSettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `book_settings`, the **only** authority for a book's speed and silence
 * skipping.
 *
 * `media_progress.speed` and `.skipSilence` still exist -- Plan 2 declared them and Plan 3's
 * `ProgressTableShapeTest` guards their shape -- but nothing in the audiobook feature reads or
 * writes them, and Task 7 carries the assertion that makes that structural rather than stated.
 */
@Dao
interface BookSettingsDao {

  @Upsert
  suspend fun upsert(settings: BookSettingsEntity)

  @Query("SELECT * FROM book_settings WHERE bookId = :bookId")
  suspend fun find(bookId: String): BookSettingsEntity?

  @Query("SELECT * FROM book_settings WHERE bookId = :bookId")
  fun observe(bookId: String): Flow<BookSettingsEntity?>

  /** Backs the in-memory snapshot the resume policy and the speed controller read (Tasks 6, 7). */
  @Query("SELECT * FROM book_settings")
  fun observeAll(): Flow<List<BookSettingsEntity>>
}
