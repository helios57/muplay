package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequest
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.requests.RequestCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The three decisions the requests screen makes, as pure functions.
 *
 * The first of them carries this plan's whole severability contract: **empty means absent, not
 * empty**. A `Ready(emptySet(), emptyList())` renders a screen with a search box a user who runs
 * neither service cannot use, and no assertion on a screen could tell that from a working one.
 */
class RequestsUiStateTest {

  private fun request(service: IntegrationService, externalId: String, status: RequestStatus) =
    MediaRequest(
      id = MediaRequest.idFor(service, externalId),
      service = service,
      externalId = externalId,
      title = "Title",
      subtitle = "Subtitle",
      remoteId = "1",
      status = status,
      requestedAtEpochMs = 1L,
      updatedAtEpochMs = 1L,
    )

  private fun album(externalId: String, alreadyAdded: Boolean = false) =
    RequestCandidate.Album(lidarrCandidate(externalId, "A", "x"), alreadyAdded)

  @Test
  fun `nothing configured is NotConfigured, and nothing else`() {
    assertThat(requestsUiState(emptySet(), emptyList(), RequestSearchState()))
      .isEqualTo(RequestsUiState.NotConfigured)
  }

  @Test
  fun `nothing configured stays NotConfigured even with rows and results to show`() {
    // The state a user reaches by configuring a service, asking for something, and then forgetting
    // the service. The rows survive -- `forget` on a credential deletes no request -- and the screen
    // must still be absent, because there is nothing left that could act on them.
    val state = requestsUiState(
      services = emptySet(),
      requests = listOf(request(IntegrationService.LIDARR, "mbid-1", RequestStatus.Requested)),
      search = RequestSearchState(query = "blue", results = listOf(album("mbid-1"))),
    )

    assertThat(state).isEqualTo(RequestsUiState.NotConfigured)
  }

  @Test
  fun `one configured service is Ready with only that service`() {
    val state = requestsUiState(setOf(IntegrationService.LIDARR), emptyList(), RequestSearchState())

    assertThat((state as RequestsUiState.Ready).services).containsExactly(IntegrationService.LIDARR)
  }

  @Test
  fun `the other configured service alone is Ready with only the other service`() {
    // The second observation, and the one a suite that always configures Lidarr never makes.
    val state = requestsUiState(setOf(IntegrationService.BINDERY), emptyList(), RequestSearchState())

    assertThat((state as RequestsUiState.Ready).services).containsExactly(IntegrationService.BINDERY)
  }

  @Test
  fun `everything the search holds reaches the ready state unchanged`() {
    val search = RequestSearchState(
      query = "blue",
      searching = true,
      results = listOf(album("mbid-1")),
      error = "something",
    )

    val state = requestsUiState(setOf(IntegrationService.LIDARR), emptyList(), search) as RequestsUiState.Ready

    // Field by field, because a state mapping that dropped one is a screen that silently stops
    // showing an error or a spinner, and nothing else fails.
    assertThat(state.query).isEqualTo("blue")
    assertThat(state.searching).isTrue()
    assertThat(state.results).isEqualTo(search.results)
    assertThat(state.error).isEqualTo("something")
  }

  // ---- hasRequested ----------------------------------------------------------------------------

  private fun ready(vararg requests: MediaRequest) = RequestsUiState.Ready(
    services = setOf(IntegrationService.LIDARR, IntegrationService.BINDERY),
    requests = requests.toList(),
    query = "",
    searching = false,
    results = emptyList(),
    error = null,
  )

  @Test
  fun `a candidate the service already has is marked requested`() {
    assertThat(ready().hasRequested(album("mbid-1", alreadyAdded = true))).isTrue()
  }

  @Test
  fun `a candidate muplay has a row for is marked requested even though the search said otherwise`() {
    // The live half. `RequestCandidate.alreadyAdded` was decided when the search ran; a user who has
    // just pressed the button on a row is looking at a stale flag, and the stored rows are what
    // correct it without a second search.
    val state = ready(request(IntegrationService.LIDARR, "mbid-1", RequestStatus.Requested))

    assertThat(state.hasRequested(album("mbid-1", alreadyAdded = false))).isTrue()
  }

  @Test
  fun `a candidate nobody has is not marked requested`() {
    val state = ready(request(IntegrationService.LIDARR, "other", RequestStatus.Requested))

    assertThat(state.hasRequested(album("mbid-1"))).isFalse()
  }

  @Test
  fun `a row for the other service does not mark an identical id as requested`() {
    // `MediaRequest.idFor` namespaces by service precisely so two services sharing an identifier
    // space cannot collide, and a `requests.any { it.externalId == ... }` here would undo that.
    val state = ready(request(IntegrationService.BINDERY, "same-id", RequestStatus.Requested))

    assertThat(state.hasRequested(album("same-id"))).isFalse()
  }

  // ---- the failure sentence --------------------------------------------------------------------

  @Test
  fun `reaching every service is not an error`() {
    assertThat(searchFailureMessage(emptySet())).isNull()
  }

  @Test
  fun `a failed service is named, and one failing does not implicate the other`() {
    // "Something went wrong" sends a user with both configured to look at the wrong server.
    assertThat(searchFailureMessage(setOf(IntegrationService.BINDERY)))
      .contains("Bindery")
      .doesNotContain("Lidarr")
  }

  @Test
  fun `two failed services are both named, in declaration order`() {
    val message = searchFailureMessage(setOf(IntegrationService.BINDERY, IntegrationService.LIDARR))

    // Order is a property: every list in this feature renders in `IntegrationService.entries` order,
    // and a `Set`'s own iteration order is whatever the caller happened to build it in.
    assertThat(message).isNotNull().asString().contains("Lidarr and Bindery")
  }

  // ---- the status line -------------------------------------------------------------------------

  @Test
  fun `every status says something, and downloaded is not confused with playable`() {
    // `Imported` and `Arrived` are a whole Navidrome scan apart, and telling a user their book is in
    // their library when the service has merely finished downloading it is the exact confusion
    // `RequestStatus` splits those two members to prevent.
    assertThat(statusLabel(RequestStatus.Imported)).isNotEqualTo(statusLabel(RequestStatus.Arrived("a1")))
    assertThat(statusLabel(RequestStatus.Imported)).doesNotContain("In your library")
    assertThat(statusLabel(RequestStatus.Arrived("a1"))).contains("In your library")
  }

  @Test
  fun `a download with a percentage says it, and one without still says something`() {
    // `percentComplete` is null when the service does not say, and "Downloading, null% done" is the
    // shape this branch exists to avoid.
    assertThat(statusLabel(RequestStatus.Downloading(42))).contains("42%")
    assertThat(statusLabel(RequestStatus.Downloading(null))).isNotBlank().doesNotContain("null")
  }

  @Test
  fun `a failure shows the service's own reason and the requested state says it was asked for`() {
    assertThat(statusLabel(RequestStatus.Failed("the indexer had nothing"))).isEqualTo("the indexer had nothing")
    assertThat(statusLabel(RequestStatus.Requested)).isNotBlank()
  }

  // ---- the search box label --------------------------------------------------------------------

  @Test
  fun `the search box names what is actually configured`() {
    // A box that said "Search Lidarr and Bindery" to a user who runs only one of them is offering
    // something that will never answer.
    assertThat(searchLabel(setOf(IntegrationService.LIDARR))).isEqualTo("Search Lidarr")
    assertThat(searchLabel(setOf(IntegrationService.BINDERY))).isEqualTo("Search Bindery")
    assertThat(searchLabel(setOf(IntegrationService.BINDERY, IntegrationService.LIDARR)))
      .isEqualTo("Search Lidarr and Bindery")
  }
}
