package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.LibraryRepository
import io.github.helios57.muplay.database.LibrarySelection
import io.github.helios57.muplay.model.MusicLibrary
import kotlinx.coroutines.flow.Flow

/**
 * The library filter, as every browse tab that offers one needs it.
 *
 * Three tabs draw the same chip row now — albums, folders and playlists — and the alternative to
 * one interface was the same three members declared on three seams and forwarded by three `object`
 * expressions. `QueueSink` right beside this makes the same argument for the same reason: three
 * copies of a one-line forward are three chances for one of them to forward to the wrong thing,
 * and that swap is invisible in review and green under every coverage gate.
 */
interface LibraryFilterSource {

  /** Every library the mirror knows about, in the server's order. */
  val libraries: Flow<List<MusicLibrary>>

  /**
   * The library being browsed, already resolved to one that exists — see `LibrarySelection`.
   *
   * Null only while no library is known at all, which is a first run before the first sync.
   */
  val selectedLibraryId: Flow<Int?>

  fun selectLibrary(id: Int)
}

/**
 * The one real implementation, delegated to by every `@Inject` constructor that needs it.
 *
 * The selection deliberately lives in a `@Singleton` and not here: picking "Audiobooks" on the
 * albums tab and finding the folders tab still on "Music" is two screens disagreeing about a
 * decision the user made once.
 */
internal class LibrarySelectionFilter(
  libraryRepository: LibraryRepository,
  private val librarySelection: LibrarySelection,
) : LibraryFilterSource {
  override val libraries: Flow<List<MusicLibrary>> = libraryRepository.libraries
  override val selectedLibraryId: Flow<Int?> = librarySelection.selected
  override fun selectLibrary(id: Int) = librarySelection.select(id)
}

/**
 * What the chip row draws: the libraries to offer and which of them is lit.
 *
 * @property offersChoice whether to draw the row at all. **A filter with one option is not a
 *   filter**, it is a permanently-selected chip taking a row of screen on every browse tab — and
 *   this app is already short of vertical space, which is what the same round of work is about
 *   elsewhere. A single-library server is the common case for somebody who has not tagged an
 *   audiobook library, so this is not an edge case; it is most installs.
 */
data class LibraryFilterState(
  val libraries: List<MusicLibrary>,
  val selectedLibraryId: Int?,
) {
  val offersChoice: Boolean get() = libraries.size > 1

  companion object {
    /** Before the mirror has answered. Renders nothing, which is what an unknown filter should. */
    val Unknown = LibraryFilterState(libraries = emptyList(), selectedLibraryId = null)
  }
}
