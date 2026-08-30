package app.muplay.requests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.integrations.IntegrationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This plan's entire always-present UI, composed for real: one settings row, and a second that
 * appears once a service is configured.
 *
 * [IntegrationsSectionContent] is the stateless half, so this suite needs no Hilt graph and no
 * credential store. What it cannot prove is `IntegrationsSection.Content`'s own
 * `collectAsStateWithLifecycle` over `configuredServices`, which needs the Hilt-bound section and
 * therefore an `:app` journey.
 *
 * A small `Column` with two rows, so nothing here scrolls and every absence assertion below is a
 * real absence rather than a node off the bottom of the screen.
 *
 * **These tests have never been executed.** They were written with the emulator unavailable; every
 * assertion is an argument, not a measurement.
 *
 * camelCase method names: D8 refuses a space in any `SimpleName` at DEX 035.
 */
@RunWith(AndroidJUnit4::class)
class IntegrationsSectionContentTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()

  private fun show(configured: Set<IntegrationService>) {
    composeRule.setContent {
      IntegrationsSectionContent(
        configured = configured,
        onOpenIntegrations = { actions += "integrations" },
        onOpenRequests = { actions += "requests" },
      )
    }
  }

  /**
   * **The severability contract, as an assertion.** A user who runs neither service sees the
   * integrations row -- which is a *switch*, present because the app has to be configurable -- and
   * nothing else. No requests row, greyed out or otherwise: a disabled row for a feature the user
   * does not have is the dead UI this plan forbids.
   */
  @Test
  fun withNothingSetUpTheRowExplainsTheFeatureAndTheRequestsRowIsAbsent() {
    show(emptySet())

    composeRule.onNodeWithTag(INTEGRATIONS_ROW).assertTextContains(INTEGRATIONS_TITLE)
    composeRule.onNodeWithTag(INTEGRATIONS_ROW).assertTextContains(INTEGRATIONS_NONE)
    composeRule.onNodeWithTag(REQUESTS_ROW).assertDoesNotExist()
    composeRule.onNodeWithText(REQUESTS_TITLE).assertDoesNotExist()
  }

  /** Once something is configured the feature exists, so its row does. */
  @Test
  fun withAServiceSetUpTheRequestsRowAppearsAndNamesWhatItIsFor() {
    show(setOf(IntegrationService.LIDARR))

    composeRule.onNodeWithTag(REQUESTS_ROW).assertTextContains(REQUESTS_TITLE)
    composeRule.onNodeWithTag(REQUESTS_ROW).assertTextContains(REQUESTS_SUBTITLE)
  }

  /**
   * The subtitle names what is set up rather than saying "1 configured", and it names them in
   * `IntegrationService.entries` order like every other list in this feature. Derived from the enum
   * rather than typed out, so a renamed service cannot leave this test asserting the old name.
   */
  @Test
  fun theIntegrationsRowNamesTheServicesThatAreActuallySetUp() {
    show(setOf(IntegrationService.LIDARR))

    composeRule.onNodeWithTag(INTEGRATIONS_ROW)
      .assertTextContains("Set up: ${IntegrationService.LIDARR.displayName}")
    // ...and stops saying that neither is.
    composeRule.onNodeWithText(INTEGRATIONS_NONE).assertDoesNotExist()
  }

  @Test
  fun bothConfiguredServicesAreNamedInDeclarationOrder() {
    show(IntegrationService.entries.toSet())

    val expected = "Set up: " + IntegrationService.entries.joinToString { it.displayName }
    composeRule.onNodeWithTag(INTEGRATIONS_ROW).assertTextContains(expected)
  }

  /**
   * The two rows go to two different places. Both are tapped in one composition, so a section that
   * wired both to the same route records the same string twice and fails.
   */
  @Test
  fun theTwoRowsOpenTwoDifferentDestinations() {
    show(setOf(IntegrationService.BINDERY))

    composeRule.onNodeWithTag(INTEGRATIONS_ROW).performClick()
    composeRule.onNodeWithTag(REQUESTS_ROW).performClick()

    assertThat(actions).containsExactly("integrations", "requests")
  }

  /** The integrations row is reachable with nothing configured -- that is the whole point of it. */
  @Test
  fun theIntegrationsRowIsTappableBeforeAnythingIsSetUp() {
    show(emptySet())

    composeRule.onNodeWithTag(INTEGRATIONS_ROW).assertIsDisplayed()
    composeRule.onNodeWithTag(INTEGRATIONS_ROW).performClick()

    assertThat(actions).containsExactly("integrations")
  }

  private companion object {
    const val INTEGRATIONS_ROW = "settings:integrations"
    const val REQUESTS_ROW = "settings:requests"
  }
}
