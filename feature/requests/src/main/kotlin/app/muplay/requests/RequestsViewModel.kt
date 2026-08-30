package app.muplay.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.integrations.requests.RequestCandidate
import app.muplay.integrations.requests.RequestsRepository
import app.muplay.integrations.requests.SubmitResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The requests screen: what has been asked for, and how to ask for something else.
 *
 * **`RequestsRepository` is injected directly rather than behind a seam of this module's own**, and
 * that is a deliberate departure from `:feature:book`'s and `:feature:library`'s shape. Those seams
 * exist because their repositories are concrete classes over Room and a bound `MediaSession`, so a
 * JVM test could not build one. This one *can* be built on the JVM -- every one of its five
 * collaborators is an interface or an `@Inject` class over an interface, which is what Task 9 bought
 * by taking the two `…SourceFactory` ports rather than the two providers -- so a seam here would buy
 * nothing and cost a forwarding adapter that this module has **no device tier to cover**. An
 * un-gated adapter that could be wired to the wrong thing is precisely the defect class this project
 * keeps finding at 0/1.
 *
 * Every rule about *what the screen shows* lives in [requestsUiState], [hasRequested] and
 * [searchFailureMessage], which are pure and gated on BRANCH.
 */
@HiltViewModel
class RequestsViewModel @Inject constructor(
  private val repository: RequestsRepository,
) : ViewModel() {

  private val search = MutableStateFlow(RequestSearchState())

  private var searchJob: Job? = null

  val uiState: StateFlow<RequestsUiState> =
    combine(repository.configuredServices, repository.all, search, ::requestsUiState)
      // `Eagerly`, so that `:app` can ask "is anything configured" from the same instance the screen
      // uses without a first collector having to appear first.
      .stateIn(viewModelScope, SharingStarted.Eagerly, RequestsUiState.NotConfigured)

  init {
    // One poll when the screen opens. Not a background loop: every status this asks about is a
    // proxied call to a service the user is paying attention to, and nothing here is worth waking a
    // device for.
    refresh()
  }

  /**
   * Ask every configured service what happened to the stored requests.
   *
   * A service that failed is reported and the others' rows are still updated -- `RefreshReport`'s
   * own rule -- and a service that is merely unconfigured is not mentioned at all, because "we did
   * not ask" is not "it went wrong".
   */
  fun refresh() {
    viewModelScope.launch {
      val report = repository.refresh()
      search.update { it.copy(error = searchFailureMessage(report.failed)) }
    }
  }

  /**
   * The search box changed.
   *
   * **Debounced by [SEARCH_DEBOUNCE_MS], and the delay is not a UI nicety.** Both lookups are
   * proxied to a third party by the user's own server -- `api.lidarr.audio` and Open Library -- and
   * both are rate-limited upstream, so a request per keystroke would spend somebody else's quota on
   * prefixes nobody meant to search for.
   *
   * A blank query cancels the pending search and clears the results outright rather than leaving the
   * previous ones under an empty box, which reads as a list that failed to update.
   */
  fun search(term: String) {
    searchJob?.cancel()
    if (term.isBlank()) {
      search.value = RequestSearchState(query = term)
      return
    }
    search.update { it.copy(query = term) }
    searchJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MS)
      search.update { it.copy(searching = true) }
      val report = repository.search(term)
      search.update {
        it.copy(
          searching = false,
          results = report.candidates,
          error = searchFailureMessage(report.failed),
        )
      }
    }
  }

  /**
   * Ask for [candidate].
   *
   * The result list is left exactly as it was on success: the row corrects itself because
   * [hasRequested] reads the **stored rows**, which the repository has just written and which this
   * state collects live. Rewriting the candidate in place would be a second source of truth for the
   * same fact, and the one that goes stale.
   */
  fun request(candidate: RequestCandidate) {
    viewModelScope.launch {
      val message = when (val result = repository.submit(candidate)) {
        is SubmitResult.Recorded -> null
        is SubmitResult.Refused -> result.reason
      }
      search.update { it.copy(error = message) }
    }
  }

  /** Forget one request. Deletes MuPlay's row and nothing on any server. */
  fun forget(id: String) {
    viewModelScope.launch { repository.forget(id) }
  }

  private companion object {
    /**
     * 250 ms, which is the plan's number and is chosen for the upstream rate limit rather than for
     * the feel of the field.
     */
    const val SEARCH_DEBOUNCE_MS = 250L
  }
}
