package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.Flow

/** One mirrored song, reduced to "which book does this file belong to". */
data class AudiobookItemRow(val mediaId: String, val albumId: String?)

/**
 * The mirror, read through the **user's own library-role assignment**.
 *
 * Every query here joins `libraries` on `role`, because that assignment is the only thing in the
 * world that says a file is an audiobook -- Navidrome hardcodes `child.Type = "music"` for every
 * media file (spec section 4). A query that filtered on a suffix, a folder name or a duration
 * would be guessing, and the guess is silent: a mis-tagged music album on the shelf is a music
 * album in the resume path, which is spec section 3's *"music restarts from 0"* broken.
 *
 * `LibraryRole` binds through Room's own enum handling, the same way `LibraryDao.idsWithRole`
 * already binds it; no `@TypeConverter` is declared for it anywhere in this module.
 *
 * **`role` is a parameter with no default value on purpose.** A default on an interface method
 * makes Kotlin emit an `AudiobookDao$DefaultImpls` class carrying real lines that no rule in
 * `coverageFloors` names, and `warnUngatedClasses` is loud about exactly that. The one caller
 * passes [LibraryRole.AUDIOBOOKS] at every call site instead, where it is also visible.
 */
@Dao
interface AudiobookDao {

  @Query(
    "SELECT * FROM albums WHERE libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeBookAlbums(role: LibraryRole): Flow<List<AlbumEntity>>

  @Query(
    "SELECT s.id AS mediaId, s.albumId AS albumId FROM songs s WHERE s.libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeItems(role: LibraryRole): Flow<List<AudiobookItemRow>>

  @Query(
    "SELECT * FROM songs WHERE libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeSongsInRole(role: LibraryRole): Flow<List<SongEntity>>

  /**
   * A book's files. The `albumId IS NULL` arm is the loose-file case: a song that belongs to no
   * album is its own book, so its book id is its own media id.
   *
   * Role-scoped like everything else here, which is what makes `AudiobookRepository.book("record")`
   * answer `null` for a *music* album id rather than summarising it as a book. Order is imposed by
   * `BookSummaries.playOrder` rather than by an `ORDER BY`, because the tie-break that makes it
   * total (title, then id) is the same rule the shelf's arithmetic needs and belongs in one place.
   */
  @Query(
    "SELECT * FROM songs WHERE (albumId = :bookId OR (albumId IS NULL AND id = :bookId)) " +
      "AND libraryId IN (SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  suspend fun files(bookId: String, role: LibraryRole): List<SongEntity>

  /**
   * One book's album row, scoped to the role so a music album can never be looked up as a book --
   * the same guard every other query here carries, for the same reason.
   */
  @Query(
    "SELECT * FROM albums WHERE id = :bookId AND libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  suspend fun findBookAlbum(bookId: String, role: LibraryRole): AlbumEntity?
}
