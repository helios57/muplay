package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: **changing the server the app is connected to**, which was not possible at all.
 *
 * `SetupRoute` was `MuPlayApp`'s start destination and nothing else. There was no `entry` reachable
 * from any screen, `CredentialStore.clear()` had no caller anywhere in the app, and
 * `StartDestinationViewModel` sent every launch with stored credentials straight to the library. So
 * a user who mistyped a server URL, changed their Navidrome password, moved servers, or wanted
 * their password off a phone they were selling had exactly one route: uninstall.
 *
 * This journey walks the route that now exists, on the real app against the real container, and it
 * is deliberately the *whole* route rather than a composition of the section on its own -- the
 * defect was never in a widget, it was that no path led to one.
 *
 * **It signs out for real**, which destroys the AndroidKeystore key and leaves the app on setup.
 * That is safe for the suite because every journey reaches the library through
 * [reachLibraryScreen], which handles both starting states; it is the same reason
 * `FirstRunJourneyTest` can run in any position. Library *tags* survive a sign-out by design, so
 * the app comes back to a tagged mirror and the next journey's setup pass is the short one.
 *
 * camelCase names, per CLAUDE.md: D8 refuses spaces in any SimpleName at `minSdk 26`.
 */
@RunWith(AndroidJUnit4::class)
class ServerChangeJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun theSettingsScreenNamesTheServerTheAppIsConnectedTo() {
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText(SETTINGS_LABEL).performClick()

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SERVER_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    // The real URL and the real username, read back out of the real credential store. Asserting
    // the *values* rather than the presence of a heading is the point: a section wired to the
    // wrong flow, or one that lost the identity mapping, renders a heading either way.
    composeRule.onNodeWithText(SERVER_URL).assertIsDisplayed()
    composeRule.onNodeWithText("Signed in as $USERNAME").assertIsDisplayed()
  }

  /**
   * A mis-tap must not destroy a credential. This is the only control in the app that asks twice,
   * and it sits directly above switches where a mis-tap costs nothing -- so "does Cancel actually
   * cancel" is a real question rather than a formality.
   */
  @Test
  fun signingOutAsksFirstAndCancellingLeavesTheServerConnected() {
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText(SETTINGS_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SERVER_TITLE).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(CANCEL_LABEL).performClick()

    // Still on settings, still connected -- and the URL is read back out of the store rather than
    // remembered by the composable, so this fails if Cancel cleared anything.
    composeRule.onNodeWithText(SERVER_URL).assertIsDisplayed()
  }

  /**
   * **The defect, end to end.** Confirming lands the app back on setup with the credentials gone.
   *
   * The assertion is on the setup screen's own field labels rather than on the settings screen
   * being absent, because "the screen I came from is no longer showing" is satisfied by a crash.
   */
  @Test
  fun signingOutClearsTheCredentialsAndReturnsToSetup() {
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText(SETTINGS_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SERVER_TITLE).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()
    // **Inside the dialog, by ancestry, and not by index.**
    //
    // The dialog's confirm button carries the same label as the row that opened it, so with the
    // dialog up there are two nodes reading "Sign out". The first version of this line took
    // `onAllNodesWithText(...)[0]` on the reasoning that the dialog is on top -- it is not: index 0
    // is the row behind the scrim, and clicking it merely sets `confirming = true` again. The
    // symptom was a 20-second `ComposeTimeoutException` on the wait below, which reads exactly like
    // "sign-out is broken" and is not.
    composeRule.onNode(hasText(SIGN_OUT_LABEL) and hasAnyAncestor(isDialog())).performClick()

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(SERVER_URL_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(PASSWORD_LABEL).assertIsDisplayed()

    // **The back stack was reset, not pushed onto**, and the assertion is that back leaves the
    // app rather than that some screen is on show.
    //
    // `:app` turns `SetupRoute` into a clear-and-add, so after a sign-out the stack holds exactly
    // one entry and a back press pops it and finishes the activity -- the same thing back does on
    // a first run, which is what this state *is*. Without the reset the stack would be
    // [Library, Settings, Setup] and back would reveal the settings screen, sitting over a library
    // built from credentials that no longer exist; `DESTROYED` is therefore a strictly stronger
    // claim than "setup is still showing", and one that only the reset can satisfy.
    //
    // Back leaves the app, and that is the assertion.
    //
    // After the reset the stack holds exactly one entry, so Navigation 3 disables its own back
    // handler and the gesture reaches the system, which finishes the activity -- the same thing
    // back does on a first run, which is the state this *is*. Without the reset the stack would be
    // [Library, Settings, Setup], the handler would be enabled, and back would reveal the settings
    // screen over a library built from credentials that no longer exist. So `DESTROYED` is a
    // strictly stronger claim than "setup is still showing", and only the reset can satisfy it.
    //
    // Two wrong versions of this assertion were measured before this one, and both misread the
    // same evidence: asserting the setup screen still showed failed with
    // `IllegalStateException: No compose hierarchies found` (no semantics owners, because the
    // activity is going away), and reading `scenario.state` immediately after the press read
    // `STARTED`, because `finish()` is asynchronous. Hence the poll rather than a bare read.
    composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (composeRule.activityRule.scenario.state != Lifecycle.State.DESTROYED &&
      System.currentTimeMillis() < deadline
    ) {
      Thread.sleep(POLL_MILLIS)
    }
    assertThat(composeRule.activityRule.scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
  }

  private companion object {
    /**
     * This journey's own copies, per `JourneyNavigation.kt`'s rule: a string this test makes a
     * claim about is the black-box contract with what a user sees, and sharing a constant with the
     * production source would let a wording change pass unnoticed.
     */
    const val SETTINGS_LABEL = "Settings"
    const val SERVER_TITLE = "Server"
    const val SIGN_OUT_LABEL = "Sign out"
    const val SIGN_OUT_CONFIRM_TITLE = "Sign out of this server?"
    const val CANCEL_LABEL = "Cancel"
    const val SERVER_URL_LABEL = "Server URL"
    const val PASSWORD_LABEL = "Password"

    const val TIMEOUT_MILLIS = 20_000L

    /** Lifecycle polling: `finish()` is asynchronous and no compose hierarchy survives to wait on. */
    const val POLL_MILLIS = 50L
  }
}
