package app.muplay.requests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.integrations.IntegrationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The integrations setup screen, composed for real on a device against an [IntegrationsUiState]
 * built by hand.
 *
 * No Hilt graph, no Keystore, no server: the stateless overload takes the state and eight lambdas.
 * What this suite cannot prove is the hop out of `IntegrationsViewModel`, nor the `hiltViewModel()`
 * default argument.
 *
 * The screen is an ordinary `Column` with `verticalScroll`, **not** a `LazyColumn`, so every child
 * is composed whatever the screen height -- which is what makes the absence assertions below sound
 * rather than accidents of the emulator's resolution. Clicks still scroll first, because a click
 * needs a node that is actually on screen.
 *
 * **These tests have never been executed.** They were written with the emulator unavailable; every
 * assertion is an argument, not a measurement. See the `:feature:requests` entry in the root
 * `coverageFloors` table.
 *
 * camelCase method names: D8 refuses a space in any `SimpleName` at DEX 035.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationsScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val edited = mutableListOf<IntegrationService>()
  private val forgotten = mutableListOf<IntegrationService>()
  private val urls = mutableListOf<String>()
  private val keys = mutableListOf<String>()
  private val actions = mutableListOf<String>()

  private fun show(uiState: IntegrationsUiState) {
    composeRule.setContent {
      IntegrationsScreen(
        uiState = uiState,
        onEdit = { edited += it },
        onCancel = { actions += "cancel" },
        onUrlChange = { urls += it },
        onKeyChange = { keys += it },
        onTest = { actions += "test" },
        onSave = { actions += "save" },
        onForget = { forgotten += it },
      )
    }
  }

  /** A form for [service], with every field an argument so a test states the one thing it is about. */
  private fun form(
    service: IntegrationService = IntegrationService.LIDARR,
    urlText: String = "https://lidarr.example",
    keyText: String = "a-key",
    urlError: String? = null,
    check: ConnectionCheck? = null,
    saving: Boolean = false,
  ) = IntegrationSetupUiState(
    service = service,
    urlText = urlText,
    keyText = keyText,
    urlError = urlError,
    check = check,
    saving = saving,
  )

  private fun countOf(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  private fun serviceTag(service: IntegrationService) = "integrations:service:${service.name}"
  private fun setupTag(service: IntegrationService) = "integrations:setup:${service.name}"
  private fun forgetTag(service: IntegrationService) = "integrations:forget:${service.name}"

  // ---- the list ------------------------------------------------------------------------------

  /**
   * **Always reachable, whatever is configured.** This screen is the one affordance that turns the
   * feature on, so a service that is not set up has to be on it -- an "integrations" screen that
   * listed only configured integrations could never be used to configure the first one.
   */
  @Test
  fun everyServiceIsListedWhetherOrNotAnyOfThemIsSetUp() {
    show(IntegrationsUiState(configured = emptySet()))

    composeRule.onNodeWithText(INTEGRATIONS_SCREEN_TITLE).assertIsDisplayed()
    IntegrationService.entries.forEach { service ->
      composeRule.onNodeWithTag(serviceTag(service)).assertExists()
      composeRule.onNodeWithTag(setupTag(service)).assertExists()
    }
  }

  /**
   * A configured service says so and can be forgotten; an unconfigured one says so and cannot.
   * Both blocks are on screen at once, so a screen that rendered one state for both fails here.
   */
  @Test
  fun aConfiguredServiceSaysSoAndOffersToForgetItWhileTheOtherDoesNot() {
    show(IntegrationsUiState(configured = setOf(IntegrationService.LIDARR)))

    composeRule
      .onNode(hasTestTag(serviceTag(IntegrationService.LIDARR)) and hasAnyDescendant(hasText(SET_UP_STATUS)))
      .assertExists()
    composeRule
      .onNode(hasTestTag(serviceTag(IntegrationService.BINDERY)) and hasAnyDescendant(hasText(NOT_SET_UP_STATUS)))
      .assertExists()

    composeRule.onNodeWithTag(forgetTag(IntegrationService.LIDARR)).assertExists()
    composeRule.onNodeWithTag(forgetTag(IntegrationService.BINDERY)).assertDoesNotExist()

    // ...and the button on a configured service offers to *replace* rather than to set up.
    assertThat(countOf(REPLACE_LABEL)).isEqualTo(1)
    assertThat(countOf(SET_UP_LABEL)).isEqualTo(1)
  }

  @Test
  fun settingUpNamesTheServiceThatRowIsForRatherThanTheFirstOne() {
    show(IntegrationsUiState(configured = emptySet()))

    composeRule.onNodeWithTag(setupTag(IntegrationService.BINDERY)).performScrollTo().performClick()

    assertThat(edited).containsExactly(IntegrationService.BINDERY)
  }

  @Test
  fun forgettingNamesTheServiceThatRowIsForRatherThanTheFirstOne() {
    show(IntegrationsUiState(configured = IntegrationService.entries.toSet()))

    composeRule.onNodeWithTag(forgetTag(IntegrationService.BINDERY)).performScrollTo().performClick()

    assertThat(forgotten).containsExactly(IntegrationService.BINDERY)
  }

  // ---- one form at a time ----------------------------------------------------------------------

  /**
   * Exactly one form is open at a time. Two half-filled forms is two sets of credentials a user can
   * confuse, and the failure -- a Lidarr key saved under Bindery -- is one `RequestsRepository` has
   * to carry a corrupt-store branch for.
   */
  @Test
  fun openingOneServicesFormLeavesTheOtherServiceAsAPlainRow() {
    show(IntegrationsUiState(editing = form(service = IntegrationService.LIDARR)))

    assertThat(countOf(API_KEY_FIELD_LABEL)).isEqualTo(1)
    composeRule.onNodeWithTag(URL_FIELD).assertExists()
    // The service whose form is open no longer offers to open it...
    composeRule.onNodeWithTag(setupTag(IntegrationService.LIDARR)).assertDoesNotExist()
    // ...and the other one still does.
    composeRule.onNodeWithTag(setupTag(IntegrationService.BINDERY)).assertExists()
  }

  /** The address field is named after the service whose form it is, not after either in general. */
  @Test
  fun theAddressFieldIsNamedAfterTheServiceWhoseFormIsOpen() {
    show(IntegrationsUiState(editing = form(service = IntegrationService.BINDERY)))

    composeRule.onNodeWithText("${IntegrationService.BINDERY.displayName} address").assertExists()
    assertThat(countOf("${IntegrationService.LIDARR.displayName} address")).isZero()
  }

  /**
   * The sentence under the URL field is `BaseUrlResult.message`'s rather than this screen's, and it
   * is derived here rather than typed out so a reworded parser message cannot leave this test
   * checking a copy of the old wording.
   */
  @Test
  fun theAddressErrorIsShownUnderTheFieldWordForWord() {
    val message = "MuPlay could not read that address."
    show(IntegrationsUiState(editing = form(urlText = "not a url", urlError = message)))

    composeRule.onNodeWithTag(ERROR_LINE).assertTextEquals(message)
  }

  @Test
  fun anAddressThatParsedShowsNoErrorLine() {
    show(IntegrationsUiState(editing = form(urlError = null)))

    composeRule.onNodeWithTag(ERROR_LINE).assertDoesNotExist()
  }

  @Test
  fun typingAnAddressReportsWhatWasTyped() {
    show(IntegrationsUiState(editing = form(urlText = "")))

    composeRule.onNodeWithTag(URL_FIELD).performScrollTo().performTextInput("lidarr.example")

    assertThat(urls).containsExactly("lidarr.example")
  }

  @Test
  fun typingAnApiKeyReportsWhatWasTyped() {
    show(IntegrationsUiState(editing = form(keyText = "")))

    composeRule.onNodeWithTag(KEY_FIELD).performScrollTo().performTextInput("abc123")

    assertThat(keys).containsExactly("abc123")
  }

  // ---- what may be pressed, and when -----------------------------------------------------------

  /**
   * Both fields, because both are needed: Lidarr's `/ping` answers without a key, and telling a
   * user that their empty key works is the exact unhelpfulness the whole check exists to avoid.
   */
  @Test
  fun testConnectionIsRefusedWhileTheApiKeyIsEmpty() {
    show(IntegrationsUiState(editing = form(keyText = "")))

    composeRule.onNodeWithTag(TEST_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun testConnectionIsRefusedWhileTheAddressIsUnreadable() {
    show(IntegrationsUiState(editing = form(urlText = "not a url", urlError = "unreadable")))

    composeRule.onNodeWithTag(TEST_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun testConnectionIsOfferedOnceBothFieldsAreFilledAndTheAddressParses() {
    show(IntegrationsUiState(editing = form()))

    composeRule.onNodeWithTag(TEST_BUTTON).assertIsEnabled()
    composeRule.onNodeWithTag(TEST_BUTTON).performScrollTo().performClick()

    assertThat(actions).containsExactly("test")
  }

  /**
   * **Saving requires a connection check that came back `Ok`, not merely a well-formed address.**
   * The alternative is storing a credential that has never once been shown to work and then
   * reporting every later failure as a service outage; it is also what makes "Test connection"
   * load-bearing rather than decoration.
   */
  @Test
  fun saveIsRefusedWhileNoConnectionCheckHasBeenRun() {
    show(IntegrationsUiState(editing = form(check = null)))

    composeRule.onNodeWithTag(SAVE_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun saveIsRefusedWhenTheConnectionCheckDidNotComeBackOk() {
    show(IntegrationsUiState(editing = form(check = ConnectionCheck.Unreachable)))

    composeRule.onNodeWithTag(SAVE_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun saveIsOfferedOnceTheConnectionCheckHasComeBackOk() {
    show(IntegrationsUiState(editing = form(check = ConnectionCheck.Ok("Lidarr"))))

    composeRule.onNodeWithTag(SAVE_BUTTON).assertIsEnabled()
    composeRule.onNodeWithTag(SAVE_BUTTON).performScrollTo().performClick()

    assertThat(actions).containsExactly("save")
  }

  /** A save in flight disables the whole form, so a second tap cannot start a second write. */
  @Test
  fun aFormThatIsSavingOffersNeitherTestNorSave() {
    show(IntegrationsUiState(editing = form(check = ConnectionCheck.Ok("Lidarr"), saving = true)))

    composeRule.onNodeWithTag(TEST_BUTTON).assertIsNotEnabled()
    composeRule.onNodeWithTag(SAVE_BUTTON).assertIsNotEnabled()
  }

  // ---- what the check says ---------------------------------------------------------------------

  /**
   * The sentence is `ConnectionCheck.message`'s, derived rather than copied.
   *
   * The `Unreachable` case is the one that matters most: its whole job is to *not* blame the API
   * key for a server that never answered, and it is the case a user is most likely to meet first.
   */
  @Test
  fun anUnreachableServerIsReportedInItsOwnWordsAndDoesNotBlameTheKey() {
    val check = ConnectionCheck.Unreachable
    show(IntegrationsUiState(editing = form(check = check)))

    composeRule.onNodeWithTag(CHECK_LINE)
      .assertTextEquals(check.message(IntegrationService.LIDARR))
  }

  /**
   * A Sonarr URL pasted into the Lidarr field is the single most likely real mistake -- `/ping` is
   * byte-identical across every Servarr application -- so the screen has to say which application
   * actually answered.
   */
  @Test
  fun anAddressThatAnswersAsADifferentApplicationSaysWhichOne() {
    val check = ConnectionCheck.WrongApplication(appName = "Sonarr")
    show(IntegrationsUiState(editing = form(check = check)))

    composeRule.onNodeWithTag(CHECK_LINE)
      .assertTextEquals(check.message(IntegrationService.LIDARR))
  }

  @Test
  fun aFormThatHasNotBeenTestedShowsNoCheckLine() {
    show(IntegrationsUiState(editing = form(check = null)))

    composeRule.onNodeWithTag(CHECK_LINE).assertDoesNotExist()
  }

  @Test
  fun cancellingTheFormIsItsOwnActionAndSavesNothing() {
    show(IntegrationsUiState(editing = form(check = ConnectionCheck.Ok("Lidarr"))))

    composeRule.onNodeWithText(CANCEL_LABEL).performScrollTo().performClick()

    assertThat(actions).containsExactly("cancel")
  }

  /** Every literal this screen draws that no named function owns, written out once each. */
  private companion object {
    const val URL_FIELD = "setup:url"
    const val KEY_FIELD = "setup:key"
    const val ERROR_LINE = "setup:error"
    const val CHECK_LINE = "setup:check"
    const val TEST_BUTTON = "setup:test"
    const val SAVE_BUTTON = "setup:save"

    const val SET_UP_STATUS = "Set up."
    const val NOT_SET_UP_STATUS = "Not set up."
    const val SET_UP_LABEL = "Set up"
    const val REPLACE_LABEL = "Replace"
    const val API_KEY_FIELD_LABEL = "API key"
    const val CANCEL_LABEL = "Cancel"
  }

  @Test
  fun everyControlIsBigEnoughToTap() {
    // The editing state, which is the one with the most controls on it: each service block's own
    // buttons plus the open form's test, save and cancel.
    show(IntegrationsUiState(configured = setOf(IntegrationService.LIDARR), editing = form()))

    composeRule.assertEveryTapTargetIsBigEnough()
  }

}
