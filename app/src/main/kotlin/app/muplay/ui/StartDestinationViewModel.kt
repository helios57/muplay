package app.muplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
  private val credentialStore: CredentialStore,
  private val libraryRepository: LibraryRepository,
) : ViewModel() {

  private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
  val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

  init {
    viewModelScope.launch {
      val configured = credentialStore.load() != null
      _startDestination.value =
        if (configured && !libraryRepository.hasUnassignedLibraries()) StartDestination.Library
        else StartDestination.Setup
    }
  }
}
