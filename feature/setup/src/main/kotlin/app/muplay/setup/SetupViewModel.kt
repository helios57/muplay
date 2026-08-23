package app.muplay.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import app.muplay.network.SubsonicErrorException
import app.muplay.network.SubsonicHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Drives the first-run setup screen: takes a server URL, username and password, calls `ping` then
 * `getMusicFolders`, and reports success or a typed failure as [uiState].
 *
 * [ping] and [fetchLibraries] are the seams that make this class testable without a mock framework
 * or a real network call: plain suspend functions, defaulted to real [SubsonicClient] calls, that
 * [SetupViewModelTest] replaces with hand-written fake lambdas per test. `SubsonicClient` itself
 * cannot be faked directly — it is a concrete, non-open class whose primary constructor already
 * builds a real Retrofit instance (see its own documentation) — so each seam sits one level up,
 * at exactly the two calls [connect] needs.
 *
 * Two independent lambdas rather than one that returns both halves: keeping [ping]'s signature
 * exactly as it was means every existing test, and every existing call site, still says what it
 * meant. The cost is that the two defaults build a [SubsonicClient] each — two `Retrofit`
 * instances per connect attempt, on a flow a user runs once. `SubsonicClient` holds no per-call
 * state worth sharing (its one shared object, the `SecureRandom` behind salt generation, is
 * already a companion-level singleton), so this is a small allocation cost, not a correctness or
 * connection-reuse one.
 *
 * No repository, use-case, or domain layer sits between this ViewModel and [SubsonicClient]:
 * per this project's constraints, `core/network`'s client already *is* the entry point to data.
 *
 * `@JvmOverloads` is not decorative here: [SetupScreen] constructs this ViewModel with the plain
 * `viewModel()` composable, no custom factory. That default factory instantiates a ViewModel via
 * a genuine zero-argument JVM constructor found through reflection — a Kotlin constructor with
 * defaulted parameters compiles to a single constructor plus a synthetic `$default` bridge, not a
 * real no-arg overload, so without `@JvmOverloads` that reflective lookup fails at runtime. It is
 * `FirstRunJourneyTest` that actually proves this end to end: that journey reaches the real screen
 * through `viewModel()` on a real device, which no JVM test in this module can do.
 */
class SetupViewModel @JvmOverloads constructor(
  private val ping: suspend (SubsonicCredentials) -> ServerInfo = { credentials ->
    SubsonicClient(credentials).ping()
  },
  private val fetchLibraries: suspend (SubsonicCredentials) -> List<MusicLibrary> = { credentials ->
    SubsonicClient(credentials).getMusicFolders()
  },
) : ViewModel() {

  private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
  val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

  /**
   * Validates [serverUrl] and, if it is a well-formed `http`/`https` URL, attempts to connect.
   * A blank or malformed URL is rejected synchronously, before [ping] is ever called — see
   * [SetupUiState.Failure] with [SetupFailureReason.InvalidUrl].
   *
   * Otherwise moves to [SetupUiState.Connecting] and, once [ping] and then [fetchLibraries]
   * settle, to exactly one of:
   * - [SetupUiState.Success], if both returned — carrying the server's identity *and* its
   *   libraries. [fetchLibraries] runs only after [ping] has succeeded, and its failure is a
   *   failure of the whole attempt: a setup that cannot list a single library has not produced
   *   anything the user can go on to browse, and reporting it as a `Success` with an empty list
   *   would be indistinguishable from a server that genuinely has none.
   * - [SetupUiState.Failure] with [SetupFailureReason.Rejected], if either threw
   *   [SubsonicErrorException] (a Subsonic-level error, e.g. wrong credentials) or
   *   [SubsonicHttpException] (an unsuccessful HTTP status) — the server answered, on purpose.
   * - [SetupUiState.Failure] with [SetupFailureReason.Unreachable] for anything else — a
   *   transport failure or an unparseable response, where nothing came back to interpret at all.
   *
   * A [CancellationException] is rethrown, not reported as a failure: cancelling the coroutine
   * (e.g. the ViewModel being cleared mid-connect) is not the server saying anything at all, and
   * must not flash an "unreachable" message a user never asked for.
   */
  fun connect(serverUrl: String, username: String, password: String) {
    val trimmedUrl = serverUrl.trim()
    if (trimmedUrl.toHttpUrlOrNull() == null) {
      _uiState.value = SetupUiState.Failure(SetupFailureReason.InvalidUrl)
      return
    }

    _uiState.value = SetupUiState.Connecting
    viewModelScope.launch {
      val credentials = SubsonicCredentials(trimmedUrl, username, password)
      _uiState.value = try {
        val serverInfo = ping(credentials)
        SetupUiState.Success(serverInfo, fetchLibraries(credentials))
      } catch (e: SubsonicErrorException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.code, detail = e.message))
      } catch (e: SubsonicHttpException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.status, detail = e.message))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SetupUiState.Failure(SetupFailureReason.Unreachable)
      }
    }
  }
}
