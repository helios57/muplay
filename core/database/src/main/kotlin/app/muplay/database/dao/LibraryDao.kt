package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.Flow

/**
 * An abstract class rather than an interface: [mergeFromServer] is a `@Transaction` method with a
 * body, and Room needs a class to put one in.
 */
@Dao
abstract class LibraryDao {

  /**
   * Ordered by [LibraryEntity.musicFolderId] -- `LibraryRepository.libraries` documents this as
   * "in server id order", and callers (a future tagging screen among them) are entitled to rely
   * on emissions not silently reordering between them.
   *
   * That ordering is doubly guaranteed today, not singly: `musicFolderId` is a bare `Int`
   * `@PrimaryKey` on this table, which SQLite's `INTEGER PRIMARY KEY` aliases to the table's own
   * rowid, so a plain `SELECT *` already scans in this order regardless of insertion order --
   * confirmed by `LibraryDaoTest.observeAllEmitsInIdOrder` staying green even with the explicit
   * `ORDER BY` below deleted. The clause is not redundant, though: the moment [musicFolderId]
   * stops being a bare `Int` primary key (a composite key, a `String` id, `WITHOUT ROWID`, or this
   * query growing a `JOIN`), the rowid-aliasing guarantee disappears and only the explicit clause
   * is left protecting the contract above. Recorded here so that future change is understood as
   * removing one of two guarantees, not the only one.
   */
  @Query("SELECT * FROM libraries ORDER BY musicFolderId")
  abstract fun observeAll(): Flow<List<LibraryEntity>>

  @Query("SELECT * FROM libraries WHERE musicFolderId = :musicFolderId")
  abstract suspend fun find(musicFolderId: Int): LibraryEntity?

  @Query("SELECT musicFolderId FROM libraries WHERE role = :role ORDER BY musicFolderId")
  abstract suspend fun idsWithRole(role: LibraryRole): List<Int>

  @Query("SELECT musicFolderId FROM libraries ORDER BY musicFolderId")
  abstract suspend fun allIds(): List<Int>

  @Query("UPDATE libraries SET role = :role WHERE musicFolderId = :musicFolderId")
  abstract suspend fun setRole(musicFolderId: Int, role: LibraryRole)

  /**
   * Reconciles the stored libraries with what the server reports, **without touching the `role`
   * column of a library that already exists**.
   *
   * Not an `@Upsert`: an upsert writes every column of the entity it is given, and the caller
   * builds those entities from a Subsonic response, which cannot know a role. The result would be
   * every re-sync silently resetting the user's audiobook tag to UNASSIGNED.
   */
  @Transaction
  open suspend fun mergeFromServer(libraries: List<LibraryEntity>) {
    insertIgnoringExisting(libraries)
    libraries.forEach { updateName(it.musicFolderId, it.name) }
    deleteMissing(libraries.map { it.musicFolderId })
  }

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  protected abstract suspend fun insertIgnoringExisting(libraries: List<LibraryEntity>)

  @Query("UPDATE libraries SET name = :name WHERE musicFolderId = :musicFolderId")
  protected abstract suspend fun updateName(musicFolderId: Int, name: String)

  @Query("DELETE FROM libraries WHERE musicFolderId NOT IN (:keep)")
  protected abstract suspend fun deleteMissing(keep: List<Int>)
}
