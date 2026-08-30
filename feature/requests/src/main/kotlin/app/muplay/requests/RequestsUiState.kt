package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.requests.RequestCandidate

/**
 * What the requests screen shows.
 *
 * **[NotConfigured] renders nothing at all** -- not an empty list, not a "no requests yet" card, not
 * a "set up an integration" prompt. This plan's severability contract is explicit: a user who runs
 * neither service sees no degradation and no dead UI, and the one affordance that turns the feature
 * on is a settings row, not a screen.
 *
 * That is why this is a sealed interface rather than a `Ready` with empty collections. A
 * `Ready(emptySet(), emptyList())` would render an empty screen, which is exactly the dead UI the
 * contract forbids, and no assertion anywhere could tell the two apart.
 */
sealed interface RequestsUiState {

  data object NotConfigured : RequestsUiState

  data class Ready(
    /** In `IntegrationService.entries` order, so every list agrees without any screen sorting. */
    val services: Set<IntegrationService>,
    /** Newest first -- `MediaRequestRepository`'s own order, not this screen's. */
    val requests: List<MediaRequest>,
    val query: String,
    val searching: Boolean,
    val results: List<RequestCandidate>,
    /** A sentence, or `null`. Never an exception and never a service name on its own. */
    val error: String?,
  ) : RequestsUiState
}

/**
 * Whether [candidate] has already been asked for.
 *
 * Two facts, either of which is sufficient, and the second is why this is computed here rather than
 * read off `RequestCandidate.alreadyAdded` alone: that flag is decided when the **search** ran, and
 * a user who has just pressed the button on a row is looking at a stale one. The stored rows are
 * live, so the row corrects itself the moment the request is recorded.
 */
fun RequestsUiState.Ready.hasRequested(candidate: RequestCandidate): Boolean =
  candidate.alreadyAdded ||
    requests.any { it.id == MediaRequest.idFor(candidate.service, candidate.externalId) }

/**
 * The state, from the four things that decide it.
 *
 * A free function rather than logic inside the view model, so that the one decision that carries the
 * severability contract -- empty means *absent*, not empty -- is a pure function the fast tier gates
 * on BRANCH.
 */
internal fun requestsUiState(
  services: Set<IntegrationService>,
  requests: List<MediaRequest>,
  search: RequestSearchState,
): RequestsUiState =
  if (services.isEmpty()) {
    RequestsUiState.NotConfigured
  } else {
    RequestsUiState.Ready(
      // **Re-ordered here, and that is a fix rather than a formality.** The incoming set is the key
      // set of `ConfiguredServices`' map, and a `Set` has no order of its own: the shipped
      // `IntegrationCredentialStore` happens to build its map by iterating `IntegrationService`,
      // so it arrives in declaration order today -- but that is a property of one implementation of
      // a port, not of this state. The settings screen renders `IntegrationService.entries`
      // directly, so the two surfaces would disagree the moment any other implementation appeared.
      // Measured while writing this: a fake that inserted Bindery first produced
      // `[BINDERY, LIDARR]` here and `[LIDARR, BINDERY]` there.
      services = IntegrationService.entries.filterTo(LinkedHashSet()) { it in services },
      requests = requests,
      query = search.query,
      searching = search.searching,
      results = search.results,
      error = search.error,
    )
  }

/**
 * Everything about a search that is the view model's own, rather than the repository's.
 *
 * One holder rather than four `MutableStateFlow`s, because `combine` takes at most five typed flows
 * and because these four always change together -- a `searching = true` with yesterday's `results`
 * still in place is a state no screen should have to render.
 */
internal data class RequestSearchState(
  val query: String = "",
  val searching: Boolean = false,
  val results: List<RequestCandidate> = emptyList(),
  val error: String? = null,
)

/**
 * What to say when a search could not reach a service, or `null` when it reached all of them.
 *
 * Named services, not "a search failed": a user with both configured whose Bindery is down needs to
 * know it was Bindery, and a message that said "something went wrong" would send them to look at
 * the wrong one. Ordered by `IntegrationService.entries`, like every other list in this feature.
 */
internal fun searchFailureMessage(failed: Set<IntegrationService>): String? {
  if (failed.isEmpty()) return null
  val names = IntegrationService.entries.filter { it in failed }.joinToString(" and ") { it.displayName }
  return "MuPlay could not reach $names, so these results may be incomplete."
}
