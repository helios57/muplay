package app.muplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.model.LibraryRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the app knows about setup having been finished, as two facts and nothing else.
 *
 * A seam for the same reason `SetupViewModel` has [app.muplay.setup.SetupCredentialSink]: the real
 * `CredentialStore` opens an AndroidKeystore key, so a view model that names it directly cannot be
 * constructed off a device, and this decision -- which screen the app opens on -- would then be
 * testable only through a full journey. It had no test of any kind before this seam existed.
 */
internal interface SetupProgress {
  /** Whether a server URL, username and password are stored. */
  suspend fun hasCredentials(): Boolean

  /** Every known library's role. **Empty when the mirror has never been filled.** */
  suspend fun libraryRoles(): List<LibraryRole>
}

@HiltViewModel
class StartDestinationViewModel internal constructor(
  private val progress: SetupProgress,
) : ViewModel() {

  @Inject
  constructor(
    credentialStore: CredentialStore,
    libraryRepository: LibraryRepository,
  ) : this(
    object : SetupProgress {
      override suspend fun hasCredentials(): Boolean = credentialStore.load() != null
      override suspend fun libraryRoles(): List<LibraryRole> =
        libraryRepository.libraries.first().map { it.role }
    },
  )

  private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
  val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

  init {
    viewModelScope.launch {
      val roles = progress.libraryRoles()
      // **`roles.isNotEmpty() &&` is the fix, and it is the same emptiness trap
      // `SetupViewModel.continueToLibrary` names in its own doc** -- `none { UNASSIGNED }` is
      // vacuously true over an empty list, and this site read exactly that through
      // `!hasUnassignedLibraries()`, which is `idsWithRole(UNASSIGNED).isNotEmpty()` negated.
      //
      // The defect it caused was not cosmetic. `SetupViewModel.connect` stores the credentials
      // *before* it fetches the libraries -- it has to, because `refreshFromServer` reads them back
      // out of the store -- so a first run whose fetch then failed left the device with credentials
      // stored and **zero** libraries. This predicate read that as "configured, nothing untagged",
      // opened the library screen, and there was no route back to setup from it: the app was
      // bricked into an empty browse screen until it was reinstalled. Losing a server for one
      // minute during first run was enough.
      //
      // Guarded here independently rather than by delegating to `SetupViewModel`'s guard, for the
      // reason that one gives for not delegating either: a caller can reach this decision without
      // having gone through the other, which is precisely what a relaunch is.
      _startDestination.value =
        if (progress.hasCredentials() && roles.isNotEmpty() && roles.none { it == LibraryRole.UNASSIGNED }) {
          StartDestination.Library
        } else {
          StartDestination.Setup
        }
    }
  }
}
