package io.github.helios57.muplay.database

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Which library the user is browsing, shared by every browse screen.
 *
 * A `@Singleton` rather than state inside one ViewModel, because the choice is the user's and not
 * the screen's: picking "Audiobooks" on the albums tab and finding the folders tab still showing
 * "Music" is two screens disagreeing about a decision the user made once, and it reads as the app
 * having lost track of itself. Each screen owning its own selection is also how the same chip row
 * ends up drawn twice with two different answers in it.
 *
 * **Resolved here, once.** [selected] never names a library the server has stopped reporting: an
 * explicit choice is honoured only while it still exists, and otherwise the first library stands
 * in. Leaving that fallback to each caller is the same duplication in a smaller place -- and the
 * copy that is wrong would be the one nobody looked at.
 *
 * In memory and process-scoped, deliberately: it is a browsing position, not a setting. A user who
 * comes back to a killed app expects to start where the library starts, and persisting it would
 * mean a stale choice surviving a server whose libraries have since been re-tagged.
 */
@Singleton
class LibrarySelection @Inject constructor(libraryRepository: LibraryRepository) {

  private val explicit = MutableStateFlow<Int?>(null)

  /** The chosen library if it still exists, else the first one, else null while none are known. */
  val selected: Flow<Int?> =
    combine(libraryRepository.libraries, explicit) { libraries, chosen ->
      libraries.firstOrNull { it.id == chosen }?.id ?: libraries.firstOrNull()?.id
    }

  fun select(id: Int) {
    explicit.value = id
  }
}
