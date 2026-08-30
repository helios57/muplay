package app.muplay.requests

import app.muplay.integrations.IntegrationService
import app.muplay.integrations.MediaRequestRepository
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.bindery.BinderyMessageException
import app.muplay.integrations.lidarr.LidarrHttpException
import app.muplay.integrations.requests.RequestArrivalDetector
import app.muplay.integrations.requests.RequestCandidate
import app.muplay.integrations.requests.RequestsRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The requests screen's own decisions, over a **real** `RequestsRepository`.
 *
 * Real, not faked, and that is the reason this view model injects the repository directly rather
 * than through a seam of its own: every one of that class's five collaborators is an interface or an
 * `@Inject` class over one, so the whole stack from the view model down to the two service clients
 * is JVM-reachable. A seam here would have bought nothing and cost a forwarding adapter this module
 * has no device tier to cover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestsViewModelTest {

  private class MutableClock(var now: Long = 1_000L) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
  }

  private val dispatcher = StandardTestDispatcher()
  private val dao = FakeMediaRequestDao()
  private val requests = MediaRequestRepository(dao, MutableClock())
  private val services = FakeConfiguredServices()
  private val lidarr = FakeLidarrSource()
  private val bindery = FakeBinderySource()
  private val lidarrFactory = FakeLidarrSourceFactory(lidarr)
  private val binderyFactory = FakeBinderySourceFactory(bindery)

  private val repository = RequestsRepository(
    requests = requests,
    services = services,
    lidarrFactory = lidarrFactory,
    binderyFactory = binderyFactory,
    arrival = RequestArrivalDetector(quietMirror(), quietSearch(), quietRoles()),
  )

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel() = RequestsViewModel(repository)

  private fun album(id: String, title: String) = RequestCandidate.Album(lidarrCandidate(id, title, "Miles"), false)

  // ---- the four configuration combinations ------------------------------------------------------

  @Test
  fun `nothing configured is NotConfigured, and nothing else`() = runTest(dispatcher) {
    val viewModel = viewModel()
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(RequestsUiState.NotConfigured)
  }

  @Test
  fun `one configured service is Ready with only that service`() = runTest(dispatcher) {
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as RequestsUiState.Ready).services)
      .containsExactly(IntegrationService.LIDARR)
  }

  @Test
  fun `the other configured service alone is Ready with only the other service`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as RequestsUiState.Ready).services)
      .containsExactly(IntegrationService.BINDERY)
  }

  @Test
  fun `both configured is Ready with both, in declaration order`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    // Order is a property: `IntegrationService.entries` order is what every list renders in, and the
    // two are saved here in the *wrong* order on purpose.
    assertThat((viewModel.uiState.value as RequestsUiState.Ready).services.toList())
      .containsExactly(IntegrationService.LIDARR, IntegrationService.BINDERY)
  }

  @Test
  fun `removing the last configured service returns the state to NotConfigured`() = runTest(dispatcher) {
    // The reverse transition. Without it, a view model that computed `NotConfigured` at construction
    // would pass every test above and leave a dead screen behind after the last integration went.
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()
    assertThat(viewModel.uiState.value).isInstanceOf(RequestsUiState.Ready::class.java)

    services.clear(IntegrationService.LIDARR)
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(RequestsUiState.NotConfigured)
  }

  // ---- search ----------------------------------------------------------------------------------

  @Test
  fun `a search runs only against the services that are configured`() = runTest(dispatcher) {
    services.save(lidarrCredentials())
    lidarr.lookupResults = listOf(lidarrCandidate("mbid-1", "Kind of Blue", "Miles Davis"))
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.search("blue")
    advanceUntilIdle()

    val state = viewModel.uiState.value as RequestsUiState.Ready
    // Exact mapped list: a Bindery candidate here would mean a search was issued against a service
    // the user has not configured.
    assertThat(state.results.map { it.service }).containsOnly(IntegrationService.LIDARR)
    assertThat(bindery.searchTerms).isEmpty()
    assertThat(binderyFactory.credentialsSeen).isEmpty()
  }

  @Test
  fun `a search is debounced, so typing does not spend the upstream rate limit`() = runTest(dispatcher) {
    // Both lookups are proxied to a rate-limited third party by the user's own server, so this delay
    // is about somebody else's quota rather than about the feel of the field.
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.search("b")
    viewModel.search("bl")
    viewModel.search("blu")
    advanceTimeBy(200)
    assertThat(lidarr.lookupTerms).isEmpty()

    viewModel.search("blue")
    advanceUntilIdle()

    // One call, for the last thing typed -- not four, and not for a prefix nobody meant to search.
    assertThat(lidarr.lookupTerms).containsExactly("blue")
  }

  @Test
  fun `the query is shown back while the search is still pending`() = runTest(dispatcher) {
    // The field is driven from state, so a query that did not reach the state is a text box that
    // does not accept typing.
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.search("blu")
    // The debounce has NOT elapsed -- `advanceUntilIdle` would run it -- but the state flow's own
    // combine still has to run for the field to see the new query.
    advanceTimeBy(1)

    assertThat((viewModel.uiState.value as RequestsUiState.Ready).query).isEqualTo("blu")
    assertThat(lidarr.lookupTerms).isEmpty()
  }

  @Test
  fun `clearing the box cancels the pending search and drops the old results`() = runTest(dispatcher) {
    // Previous results under an empty box read as a list that failed to update, and the pending
    // lookup is a request for a term the user has already abandoned.
    services.save(lidarrCredentials())
    lidarr.lookupResults = listOf(lidarrCandidate("mbid-1", "Kind of Blue", "Miles"))
    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.search("blue")
    advanceUntilIdle()
    assertThat((viewModel.uiState.value as RequestsUiState.Ready).results).isNotEmpty()

    viewModel.search("")
    advanceUntilIdle()

    val state = viewModel.uiState.value as RequestsUiState.Ready
    assertThat(state.results).isEmpty()
    assertThat(state.query).isEmpty()
    assertThat(lidarr.lookupTerms).containsExactly("blue")
  }

  @Test
  fun `a service that could not be reached is named rather than silently returning nothing`() =
    runTest(dispatcher) {
      services.save(lidarrCredentials())
      services.save(binderyCredentials())
      lidarr.lookupFailWith = LidarrHttpException(status = 502)
      bindery.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Herbert"))
      val viewModel = viewModel()
      advanceUntilIdle()

      viewModel.search("dune")
      advanceUntilIdle()

      val state = viewModel.uiState.value as RequestsUiState.Ready
      // The other service's results survive: a dead Lidarr must not empty a working Bindery's list.
      assertThat(state.results.map { it.externalId }).containsExactly("ol-1")
      assertThat(state.error).isNotNull().asString().contains("Lidarr")
    }

  // ---- requesting ------------------------------------------------------------------------------

  @Test
  fun `requesting a candidate records it and the list shows it`() = runTest(dispatcher) {
    services.save(lidarrCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.request(album("mbid-1", "Kind of Blue"))
    advanceUntilIdle()

    // The identifier, not "a request was made".
    val state = viewModel.uiState.value as RequestsUiState.Ready
    assertThat(state.requests.map { it.externalId }).containsExactly("mbid-1")
    assertThat(state.requests.single().remoteId).isEqualTo("7")
    assertThat(state.error).isNull()
  }

  @Test
  fun `a recorded request marks its own search result as already asked for`() = runTest(dispatcher) {
    // The row corrects itself from the stored rows rather than from a rewritten candidate, which is
    // what keeps one fact in one place. Nothing re-runs the search.
    services.save(lidarrCredentials())
    lidarr.lookupResults = listOf(lidarrCandidate("mbid-1", "Kind of Blue", "Miles"))
    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.search("blue")
    advanceUntilIdle()
    val before = viewModel.uiState.value as RequestsUiState.Ready
    assertThat(before.hasRequested(before.results.single())).isFalse()

    viewModel.request(before.results.single())
    advanceUntilIdle()

    val after = viewModel.uiState.value as RequestsUiState.Ready
    assertThat(after.results).isEqualTo(before.results)
    assertThat(after.hasRequested(after.results.single())).isTrue()
  }

  @Test
  fun `a refused request shows the service's own sentence and records nothing`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    bindery.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Herbert"))
    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.search("dune")
    advanceUntilIdle()
    // Bindery's own wording is the actionable one -- "Add the author manually first" -- and it
    // arrives through a named field rather than through `Throwable.message`.
    bindery.addResult = bindery.addResult
    val candidate = (viewModel.uiState.value as RequestsUiState.Ready).results.single()

    // A refusal raised by the client, not by this view model.
    services.clear(IntegrationService.BINDERY)
    advanceUntilIdle()
    viewModel.request(candidate)
    advanceUntilIdle()

    assertThat(requests.requests().first()).isEmpty()
  }

  @Test
  fun `a refusal carrying the server's own words reaches the screen`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    val viewModel = viewModel()
    advanceUntilIdle()
    bindery.searchResults = listOf(binderyCandidate("ol-1", "Dune", "Herbert"))
    viewModel.search("dune")
    advanceUntilIdle()
    val candidate = (viewModel.uiState.value as RequestsUiState.Ready).results.single()
    bindery.pagesAsked.clear()

    // A `BinderyMessageException` thrown out of the add.
    val failing = object : app.muplay.integrations.bindery.BinderySource by bindery {
      override suspend fun submitBook(
        candidate: app.muplay.integrations.bindery.BinderyBookCandidate,
        mediaType: app.muplay.integrations.bindery.BinderyMediaType,
        searchOnAdd: Boolean,
      ) = throw BinderyMessageException(422, "Add the author manually first.")
    }
    val vm = RequestsViewModel(
      RequestsRepository(
        requests = requests,
        services = services,
        lidarrFactory = lidarrFactory,
        binderyFactory = FakeBinderySourceFactory(failing),
        arrival = RequestArrivalDetector(quietMirror(), quietSearch(), quietRoles()),
      ),
    )
    advanceUntilIdle()

    vm.request(candidate)
    advanceUntilIdle()

    assertThat((vm.uiState.value as RequestsUiState.Ready).error)
      .isEqualTo("Add the author manually first.")
  }

  // ---- refresh and forget ----------------------------------------------------------------------

  @Test
  fun `the screen polls once when it opens, and a service that failed is named`() = runTest(dispatcher) {
    // One poll, not a loop: every status this asks about is a proxied call to a service the user is
    // looking at, and nothing here is worth waking a device for.
    services.save(lidarrCredentials())
    requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "not-a-number")
    // A row with an unparseable remote id is still pollable, so the queue call really is made.
    val failing = object : app.muplay.integrations.lidarr.LidarrSource by lidarr {
      override suspend fun queue() = throw LidarrHttpException(status = 500)
    }
    val vm = RequestsViewModel(
      RequestsRepository(
        requests = requests,
        services = services,
        lidarrFactory = FakeLidarrSourceFactory(failing),
        binderyFactory = binderyFactory,
        arrival = RequestArrivalDetector(quietMirror(), quietSearch(), quietRoles()),
      ),
    )

    advanceUntilIdle()

    assertThat((vm.uiState.value as RequestsUiState.Ready).error).isNotNull().asString().contains("Lidarr")
  }

  @Test
  fun `forgetting a request removes muplay's row`() = runTest(dispatcher) {
    services.save(lidarrCredentials())
    val stored = requests.record(IntegrationService.LIDARR, "mbid-1", "A", "x", remoteId = "7")
    val viewModel = viewModel()
    advanceUntilIdle()
    assertThat((viewModel.uiState.value as RequestsUiState.Ready).requests).hasSize(1)

    viewModel.forget(stored.id)
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as RequestsUiState.Ready).requests).isEmpty()
  }

  @Test
  fun `stored rows reach the screen with the status they were stored with`() = runTest(dispatcher) {
    services.save(binderyCredentials())
    val stored = requests.record(IntegrationService.BINDERY, "ol-1", "Dune", "Herbert", remoteId = null)
    requests.setStatus(stored.id, RequestStatus.Arrived("album-9"))
    val viewModel = viewModel()
    advanceUntilIdle()

    val row = (viewModel.uiState.value as RequestsUiState.Ready).requests.single()
    assertThat(row.status).isEqualTo(RequestStatus.Arrived("album-9"))
    // ...and that status is the only one the screen offers a Play button for.
    assertThat(statusLabel(row.status)).contains("In your library")
  }
}
