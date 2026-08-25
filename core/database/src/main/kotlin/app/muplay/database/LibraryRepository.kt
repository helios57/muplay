package app.muplay.database

import app.muplay.database.dao.LibraryDao
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The libraries this server has, and what the user decided each one is for.
 *
 * Per this project's constraints there is no domain layer and no use-case class between this and
 * the UI: a ViewModel injects this repository directly.
 *
 * **Nothing here inspects a library's name.** Role assignment comes from the user, through
 * [setRole], and from nowhere else — see `LibraryEntity`'s own documentation for what a name
 * heuristic would cost.
 */
@Singleton
class LibraryRepository @Inject constructor(
  private val libraryDao: LibraryDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  /** Every known library with its user-assigned role, in server id order. */
  val libraries: Flow<List<MusicLibrary>> =
    libraryDao.observeAll().map { rows ->
      rows.map { MusicLibrary(id = it.musicFolderId, name = it.name, role = it.role) }
    }

  /**
   * Re-reads `getMusicFolders` and merges it into the mirror: names are updated, new libraries
   * arrive [LibraryRole.UNASSIGNED], libraries the server no longer reports are removed, and the
   * roles the user already chose are untouched.
   *
   * @throws EmptyLibraryListException if the server reports **no** libraries at all while the
   * local mirror already has at least one. See that exception's own kdoc for why this is a
   * permanent refusal rather than a transient-error heuristic: `getMusicFolders` maps an absent
   * payload to an empty list rather than throwing, so this is the one place in the merge that can
   * still catch the ambiguity before [LibraryDao.mergeFromServer]'s delete makes it irreversible.
   * A first-ever sync (nothing known locally yet) is unaffected -- there is nothing to protect.
   */
  suspend fun refreshFromServer() {
    val folders = sourceProvider.current().getMusicFolders()
    if (folders.isEmpty() && libraryDao.allIds().isNotEmpty()) {
      throw EmptyLibraryListException()
    }
    libraryDao.mergeFromServer(
      folders.map { LibraryEntity(musicFolderId = it.id, name = it.name, role = it.role) },
    )
  }

  suspend fun setRole(musicFolderId: Int, role: LibraryRole) =
    libraryDao.setRole(musicFolderId, role)

  suspend fun idsWithRole(role: LibraryRole): List<Int> = libraryDao.idsWithRole(role)

  suspend fun allIds(): List<Int> = libraryDao.allIds()

  /**
   * Whether any library is still [LibraryRole.UNASSIGNED]. The setup flow uses this to decide
   * whether the user still has tagging to do — an untagged library is invisible to every browse
   * and shuffle path, so leaving one is a dead end rather than a default.
   */
  suspend fun hasUnassignedLibraries(): Boolean =
    libraryDao.idsWithRole(LibraryRole.UNASSIGNED).isNotEmpty()
}
