package app.muplay.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Drives first run: connect, store the credentials, then have the user tag every library.
 *
 * Constructor-injected, replacing the defaulted-lambda seam this class used to carry. That seam
 * existed because there was no DI graph to inject from; there is one now (Task 1's ruling), and
 * three real interfaces are both easier to fake and honest about what this class needs.
 *
 * [createSource] is a lambda rather than the `SubsonicSourceFactory` type directly so a test can
 * supply one without constructing credentials-shaped machinery; Hilt binds it from the real
 * factory in the constructor below.
 */
@HiltViewModel
class SetupViewModel(
  private val createSource: (SubsonicCredentials) -> SubsonicSource,
  private val credentials: SetupCredentialSink,
  private val libraries: SetupLibrarySink,
) : ViewModel() {

  @Inject
  constructor(
    sourceFactory: SubsonicSourceFactory,
    credentialStore: CredentialStore,
    libraryRepository: LibraryRepository,
  ) : this(
    createSource = { sourceFactory.create(it) },
    credentials = object : SetupCredentialSink {
      override suspend fun save(credentials: SubsonicCredentials) = credentialStore.save(credentials)
    },
    libraries = object : SetupLibrarySink {
      override suspend fun refreshFromServer() = libraryRepository.refreshFromServer()
      override suspend fun setRole(musicFolderId: Int, role: LibraryRole) =
        libraryRepository.setRole(musicFolderId, role)
      override suspend fun current(): List<MusicLibrary> = libraryRepository.libraries.first()
    },
  )

  private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
  val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

  private var serverInfo: app.muplay.model.ServerInfo? = null

  /**
   * Validates [serverUrl], connects, stores the credentials and lists the libraries for tagging.
   *
   * The order is load-bearing: the credentials are stored **before** the libraries are fetched,
   * because `LibraryRepository.refreshFromServer` reads them back out of the store — fetching
   * first would fail with `NotConfiguredException` on every first run.
   *
   * A [CancellationException] is rethrown rather than reported: cancelling the coroutine is not
   * the server saying anything, and must not flash an "unreachable" message nobody asked for.
   */
  fun connect(serverUrl: String, username: String, password: String) {
    val trimmedUrl = serverUrl.trim()
    val parsed = trimmedUrl.toHttpUrlOrNull()
    if (parsed == null) {
      _uiState.value = SetupUiState.Failure(SetupFailureReason.InvalidUrl)
      return
    }

    _uiState.value = SetupUiState.Connecting
    viewModelScope.launch {
      val entered = SubsonicCredentials(trimmedUrl, username, password)
      _uiState.value = try {
        val info = createSource(entered).ping()
        serverInfo = info
        credentials.save(entered)
        libraries.refreshFromServer()
        tagging(info)
      } catch (e: app.muplay.network.SubsonicErrorException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.code, detail = e.message))
      } catch (e: app.muplay.network.SubsonicHttpException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.status, detail = e.message))
      } catch (e: CancellationException) {
        throw e
      } catch (e: java.net.UnknownServiceException) {
        // Android's cleartext block. Before the generic clause below, because it *is* an
        // IOException and would otherwise render as "check the URL and your connection" for a URL
        // and a connection that are both fine. The host comes from what the user typed rather than
        // from `e.message`, which is a platform string this app does not control.
        SetupUiState.Failure(SetupFailureReason.CleartextForbidden(parsed.host))
      } catch (e: Exception) {
        SetupUiState.Failure(SetupFailureReason.Unreachable)
      }
    }
  }

  /**
   * Records the user's decision for one library. **Nothing here looks at the library's name** —
   * a name heuristic would be silently wrong for any non-English library, and its only symptom
   * would be audiobooks appearing in a music shuffle.
   */
  fun setRole(musicFolderId: Int, role: LibraryRole) {
    viewModelScope.launch {
      libraries.setRole(musicFolderId, role)
      serverInfo?.let { _uiState.value = tagging(it) }
    }
  }

  /**
   * Leaves setup, but only once every library has a role.
   *
   * `current.isNotEmpty() &&` is not decorative: `current.none { UNASSIGNED }` is vacuously true
   * over an empty list, so without this guard a server reporting zero libraries would reach
   * [SetupUiState.Ready] the moment this is called -- the same emptiness trap [tagging]'s
   * `canContinue` guards against, and this predicate has to guard against it independently rather
   * than delegate to that one, because a caller could reach here without ever having read
   * `canContinue` at all (a retry path, a restored state, a test).
   */
  fun continueToLibrary() {
    viewModelScope.launch {
      val current = libraries.current()
      if (current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED }) {
        _uiState.value = SetupUiState.Ready
      }
    }
  }

  private suspend fun tagging(info: app.muplay.model.ServerInfo): SetupUiState.Tagging {
    val current = libraries.current()
    return SetupUiState.Tagging(
      serverInfo = info,
      libraries = current,
      canContinue = current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED },
    )
  }
}
