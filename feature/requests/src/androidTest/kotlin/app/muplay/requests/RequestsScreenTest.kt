package app.muplay.requests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.integrations.IntegrationService
import app.muplay.integrations.RequestStatus
import app.muplay.integrations.requests.RequestCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The requests screen, composed for real on a device against a [RequestsUiState] built by hand.
 *
 * No Hilt graph, no credential store, no Lidarr and no Bindery: the stateless overload takes the
 * state and four lambdas. What this suite cannot prove is the hop out of `RequestsViewModel`, nor
 * the `hiltViewModel()` default argument; only an `:app` journey reaches those.
 *
 * **These tests have never been executed.** They were written with the emulator unavailable, so
 * every assertion below is an argument about the code rather than a measurement of it. See the
 * `:feature:requests` entry in the root `coverageFloors` table for what has to be run, and by whom,
 * before any number is written down.
 *
 * camelCase method names, per `CLAUDE.md`: D8 refuses a space in any `SimpleName` at DEX 035, and
 * the JVM tier's backticked style does not transfer here.
 */
@RunWith(AndroidJUnit4::class)
class RequestsScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val queries = mutableListOf<String>()
  private val requested = mutableListOf<RequestCandidate>()
  private val forgotten = mutableListOf<String>()
  private val opened = mutableListOf<String>()

  private fun show(uiState: RequestsUiState) {
    composeRule.setContent {
      RequestsScreen(
        uiState = uiState,
        onQueryChange = { queries += it },
        onRequest = { requested += it },
        onForget = { forgotten += it },
        onOpenAlbum = { opened += it },
      )
    }
  }

  /**
   * **Every fixture in this file is deliberately small enough to fit one screenful, and that is a
   * correctness property rather than a convenience.** The screen is a `LazyColumn`: a row below the
   * fold is never composed, so an assertion about it fails with "no node found" -- and, far worse,
   * an `assertDoesNotExist` about it *passes* for a reason that has nothing to do with the code.
   * Two candidates or two request rows plus a heading is ~230dp of content, so nothing here is
   * scrolled and no absence assertion can be satisfied by a node merely being off-screen.
   */

  private fun topOfTag(tag: String): Float =
    composeRule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

  private fun countOf(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  // ---- the severability contract -----------------------------------------------------------

  /**
   * **Nothing at all** -- not an empty list, not a "no requests yet" card, not a prompt to set a
   * service up. A user who runs neither service must see no degradation and no dead UI, and the one
   * affordance that turns the feature on is a settings row rather than this screen.
   *
   * Everything this screen draws lives inside the `requests:root` list, so its absence is the whole
   * assertion.
   */
  @Test
  fun nothingConfiguredRendersNothingAtAll() {
    show(RequestsUiState.NotConfigured)

    composeRule.onNodeWithTag(ROOT).assertDoesNotExist()
    composeRule.onNodeWithTag(SEARCH).assertDoesNotExist()
    assertThat(countOf(searchLabel(setOf(IntegrationService.LIDARR)))).isZero()
  }

  // ---- searching -----------------------------------------------------------------------------

  /**
   * The search box is named after what is **actually configured**, so a user with one service is
   * never told that the other was searched.
   */
  @Test
  fun theSearchBoxIsNamedAfterTheServicesThatAreActuallyConfigured() {
    show(ready(services = setOf(IntegrationService.LIDARR)))

    composeRule.onNodeWithText(searchLabel(setOf(IntegrationService.LIDARR))).assertIsDisplayed()
    assertThat(countOf(searchLabel(IntegrationService.entries.toSet()))).isZero()
  }

  @Test
  fun bothServicesAreNamedInTheSearchBoxWhenBothAreConfigured() {
    show(ready(services = IntegrationService.entries.toSet()))

    composeRule.onNodeWithText(searchLabel(IntegrationService.entries.toSet())).assertIsDisplayed()
  }

  /**
   * Typing reports what was typed. The field is *controlled* -- its `value` is the state's own
   * `query` -- so nothing on screen changes here; what is under test is that the box is wired to
   * `onQueryChange` at all, which is the difference between a search that runs and a dead box.
   */
  @Test
  fun typingInTheSearchBoxReportsWhatWasTyped() {
    show(ready(query = ""))

    composeRule.onNodeWithTag(SEARCH).performTextInput("dune")

    assertThat(queries).containsExactly("dune")
  }

  @Test
  fun aSearchInFlightSaysSo() {
    show(ready(searching = true))

    composeRule.onNodeWithText(SEARCHING_LABEL).assertIsDisplayed()
  }

  @Test
  fun aSearchThatIsNotRunningSaysNothingAboutSearching() {
    show(ready(searching = false))

    assertThat(countOf(SEARCHING_LABEL)).isZero()
  }

  /**
   * A failed search names the service, and the sentence is `searchFailureMessage`'s rather than
   * this screen's -- derived here rather than typed out, so a reworded message does not silently
   * pass a test that was checking a copy of the old wording.
   */
  @Test
  fun aSearchThatCouldNotReachAServiceShowsThatServicesOwnSentence() {
    val message = checkNotNull(searchFailureMessage(setOf(IntegrationService.BINDERY)))
    show(ready(services = IntegrationService.entries.toSet(), error = message))

    composeRule.onNodeWithTag(ERROR).assertTextEquals(message)
  }

  @Test
  fun aSearchThatReachedEveryServiceShowsNoErrorLine() {
    show(ready(error = null))

    composeRule.onNodeWithTag(ERROR).assertDoesNotExist()
  }

  // ---- candidates ------------------------------------------------------------------------------

  /**
   * The label on a candidate's button is information rather than a refusal: Lidarr recognises its
   * own duplicate and Bindery upserts, so asking twice is harmless and the control stays live.
   * What must be right is *which* row says which.
   */
  @Test
  fun aCandidateAlreadyAskedForSaysSoAndTheOtherOffersToAsk() {
    show(
      ready(
        services = IntegrationService.entries.toSet(),
        results = listOf(albumCandidate(alreadyAdded = true), bookCandidate(alreadyAdded = false)),
      ),
    )

    composeRule
      .onNode(hasTestTag(candidateTag(ALBUM_ID)) and hasAnyDescendant(hasText(ASKED_ALREADY_LABEL)))
      .assertExists()
    composeRule
      .onNode(hasTestTag(candidateTag(BOOK_ID)) and hasAnyDescendant(hasText(REQUEST_LABEL)))
      .assertExists()
    // One of each, so neither row is carrying both labels.
    assertThat(countOf(ASKED_ALREADY_LABEL)).isEqualTo(1)
    assertThat(countOf(REQUEST_LABEL)).isEqualTo(1)
  }

  /**
   * A row with a stored request for the same work says "asked already" even when the *search* said
   * otherwise, because the search's own flag was decided before the user pressed anything.
   * `hasRequested` is what folds the two facts together, and this is where a screen that read only
   * `RequestCandidate.alreadyAdded` would show a stale row.
   */
  @Test
  fun aCandidateWithAStoredRequestSaysAskedAlreadyEvenThoughTheSearchSaidOtherwise() {
    show(
      ready(
        results = listOf(albumCandidate(alreadyAdded = false)),
        requests = listOf(
          mediaRequest(
            service = IntegrationService.LIDARR,
            externalId = ALBUM_ID,
            title = LIDARR_REQUEST_TITLE,
            status = RequestStatus.Requested,
          ),
        ),
      ),
    )

    composeRule
      .onNode(hasTestTag(candidateTag(ALBUM_ID)) and hasAnyDescendant(hasText(ASKED_ALREADY_LABEL)))
      .assertExists()
  }

  /**
   * Which candidate a Request button asks for, with **two** rows on screen. A one-row version of
   * this passes just as happily when every button is wired to `results.first()`.
   */
  @Test
  fun askingForACandidateNamesThatCandidateRatherThanTheFirstOne() {
    val album = albumCandidate()
    val book = bookCandidate()
    show(ready(services = IntegrationService.entries.toSet(), results = listOf(album, book)))

    val buttons = composeRule.onAllNodesWithText(REQUEST_LABEL)
    assertThat(buttons.fetchSemanticsNodes()).hasSize(2)
    assertThat(buttons[0].fetchSemanticsNode().positionInRoot.y)
      .isLessThan(buttons[1].fetchSemanticsNode().positionInRoot.y)

    buttons[1].performClick()

    assertThat(requested).containsExactly(book)
  }

  // ---- what has been asked for -----------------------------------------------------------------

  /**
   * One section per configured service, and each service's rows under its own heading -- read off
   * the composition's geometry rather than off the list, because what could still be wrong here is
   * the screen filtering the wrong way and putting every row under the first heading.
   */
  @Test
  fun eachConfiguredServiceGetsItsOwnHeadingAndItsOwnRowsUnderIt() {
    val lidarr = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-1",
      title = LIDARR_REQUEST_TITLE,
      status = RequestStatus.Requested,
    )
    val bindery = mediaRequest(
      service = IntegrationService.BINDERY,
      externalId = "bindery-1",
      title = BINDERY_REQUEST_TITLE,
      status = RequestStatus.Imported,
    )
    show(ready(services = IntegrationService.entries.toSet(), requests = listOf(lidarr, bindery)))

    val lidarrHeading = topOfTag(sectionTag(IntegrationService.LIDARR))
    val lidarrRow = topOfTag(rowTag(lidarr.id))
    val binderyHeading = topOfTag(sectionTag(IntegrationService.BINDERY))
    val binderyRow = topOfTag(rowTag(bindery.id))

    assertThat(lidarrHeading).isLessThan(lidarrRow)
    assertThat(lidarrRow).isLessThan(binderyHeading)
    assertThat(binderyHeading).isLessThan(binderyRow)
  }

  /**
   * The status line is the shared sentence for that status, derived from `statusLabel` rather than
   * typed out -- and a `Downloading` percentage really does reach the screen.
   */
  @Test
  fun theStatusLineIsTheSharedSentenceForThatRequestsOwnStatus() {
    val downloading = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-1",
      title = LIDARR_REQUEST_TITLE,
      status = RequestStatus.Downloading(percentComplete = 42),
    )
    show(ready(requests = listOf(downloading)))

    composeRule.onNodeWithTag(statusTag(downloading.id))
      .assertTextEquals(statusLabel(downloading.status))
  }

  /**
   * `Imported` and `Arrived` are two different facts and only the second can be played: "the
   * service has the files" is a whole Navidrome scan away from "you can press play".
   */
  @Test
  fun onlyAnArrivedRequestOffersToPlayIt() {
    val imported = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-1",
      title = LIDARR_REQUEST_TITLE,
      status = RequestStatus.Imported,
    )
    val arrived = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-2",
      title = SECOND_LIDARR_REQUEST_TITLE,
      status = RequestStatus.Arrived(albumId = ARRIVED_ALBUM_ID),
    )
    show(ready(requests = listOf(imported, arrived)))

    assertThat(countOf(PLAY_LABEL)).isEqualTo(1)
    composeRule
      .onNode(hasTestTag(rowTag(arrived.id)) and hasAnyDescendant(hasText(PLAY_LABEL)))
      .assertExists()

    composeRule.onNodeWithText(PLAY_LABEL).performClick()
    assertThat(opened).containsExactly(ARRIVED_ALBUM_ID)
  }

  /**
   * Which request a Forget button forgets, with **two** rows on screen -- the same shape as the
   * candidate test above, and for the same reason.
   */
  @Test
  fun forgettingARequestNamesThatRequestRatherThanTheFirstOne() {
    val first = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-1",
      title = LIDARR_REQUEST_TITLE,
      status = RequestStatus.Requested,
    )
    val second = mediaRequest(
      service = IntegrationService.LIDARR,
      externalId = "lidarr-2",
      title = SECOND_LIDARR_REQUEST_TITLE,
      status = RequestStatus.Requested,
    )
    show(ready(requests = listOf(first, second)))

    val buttons = composeRule.onAllNodesWithText(FORGET_LABEL)
    assertThat(buttons.fetchSemanticsNodes()).hasSize(2)
    assertThat(buttons[0].fetchSemanticsNode().positionInRoot.y)
      .isLessThan(buttons[1].fetchSemanticsNode().positionInRoot.y)

    buttons[1].performClick()

    assertThat(forgotten).containsExactly(second.id)
  }

  /**
   * The strings this screen draws that are not derived from a named function, written out here
   * exactly once each. A journey types them out again on purpose -- see `BookLabels.kt`'s header
   * for why -- but within one suite a second literal is a second answer that drifts.
   */
  private companion object {
    const val ROOT = "requests:root"
    const val SEARCH = "requests:search"
    const val ERROR = "requests:error"
    const val SEARCHING_LABEL = "Searching…"
    const val REQUEST_LABEL = "Request"
    const val ASKED_ALREADY_LABEL = "Asked already"
    const val PLAY_LABEL = "Play"
    const val FORGET_LABEL = "Forget"

    fun candidateTag(externalId: String) = "requests:candidate:$externalId"
    fun sectionTag(service: IntegrationService) = "requests:section:${service.name}"
    fun rowTag(id: String) = "requests:row:$id"
    fun statusTag(id: String) = "requests:status:$id"
  }
}
