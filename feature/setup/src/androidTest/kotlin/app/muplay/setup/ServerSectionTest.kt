package app.muplay.setup

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The settings section that lets a user change or leave a server, composed on a device.
 *
 * `ServerSection.kt` has named this class in a KDoc since it was written; until now the class did
 * not exist. Nothing in this module had a device tier at all, so the section's three interesting
 * decisions -- collect the identity rather than read it once, ask before destroying a Keystore key,
 * and sign out *before* navigating, on the main thread -- were held by their own comments. Each of
 * those comments is now a test below, and each was watched to fail against the mutation it
 * describes.
 *
 * The section is composed **whole**, over a fake [ServerAccount] rather than its stateless half
 * alone: `collectAsStateWithLifecycle` and the ordering of `signOut()` against `onNavigate` are
 * exactly the parts a `ServerSummary`-only test would skip, and they are the parts that can be
 * wrong in ways nobody sees.
 *
 * ### Falsified, one mutation at a time
 *
 * Every line below is a mutation of `ServerSection.kt` that was applied, built, run on `muplay37`
 * and reverted. "red" is the test that failed; the suite is 10 green without any of them.
 *
 * | mutation | red |
 * | --- | --- |
 * | `remember` for `rememberSaveable` | `theConfirmationSurvivesARotation…` |
 * | `onNavigate(SetupRoute)` before `account.signOut()` | `confirmingForgetsTheCredentialsFirst…` (`["navigate", "signOut"]`) |
 * | drop `withContext(Dispatchers.Main)` | `theNavigationHappensOnTheMainThread…` |
 * | sign out on the first tap, no dialog | six of ten |
 * | `Cancel` signs out as well | `cancellingTheConfirmation…` (`["signOut", "navigate"]`) |
 *
 * The one that did **not** fire is worth as much as the five that did:
 * `everyControlOnTheConfirmationIsBigEnoughToTap` stayed green with the sweep's cross-window filter
 * deleted, so it does not gate that filter here. See `TapTargets.kt` in this package for what it
 * does and does not hold, and `:app` for where the filter is actually held.
 *
 * camelCase names, per CLAUDE.md: D8 refuses a space in any SimpleName at `minSdk 26`.
 */
@RunWith(AndroidJUnit4::class)
class ServerSectionTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val account = FakeServerAccount()
  private val events = Collections.synchronizedList(mutableListOf<String>())
  private val navigated = Collections.synchronizedList(mutableListOf<NavKey>())

  /** Whether `onNavigate` was called on the main thread. `null` until it is called at all. */
  @Volatile private var navigatedOnMainThread: Boolean? = null

  private val section = ServerSection(
    account = account,
    // `Dispatchers.Default`, as `SetupModule` provides it. A test scope on the main thread would
    // make `withContext(Dispatchers.Main)` a no-op and the thread assertion below vacuous.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  )

  private fun show() {
    composeRule.setContent {
      section.Content(
        onNavigate = {
          events += NAVIGATE
          navigatedOnMainThread = Looper.myLooper() == Looper.getMainLooper()
          navigated += it
        },
      )
    }
  }

  @Test
  fun aSectionWithNothingStoredSaysSoAndStillOffersTheWayOut() {
    show()

    composeRule.onNodeWithText(SERVER_NOT_CONNECTED).assertIsDisplayed()
    // The half a "renders the identity" test would miss. A user whose credentials failed to open --
    // a restored backup, a reset Keystore -- lands in exactly this state and needs the control that
    // gets them back to setup more than anyone; hiding it here would strand them.
    composeRule.onNodeWithText(SIGN_OUT_LABEL).assertIsDisplayed()
  }

  @Test
  fun theSectionNamesTheServerAndTheAccountItIsSignedInAs() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)

    show()

    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText(BASE_URL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(BASE_URL).assertIsDisplayed()
    composeRule.onNodeWithText("Signed in as $USERNAME").assertIsDisplayed()
    composeRule.onNodeWithText(SERVER_NOT_CONNECTED).assertDoesNotExist()
  }

  @Test
  fun theSectionKeepsAnsweringTheStoreRatherThanTheValueItFirstSaw() {
    // Composed while nothing is stored, which is the frame `initialValue = null` renders, and then
    // the store answers. A section that read the identity once -- or held it in `remember` -- shows
    // "Not connected" forever, which is what a user sees after a sign-in on some other screen.
    show()
    composeRule.onNodeWithText(SERVER_NOT_CONNECTED).assertIsDisplayed()

    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)

    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText(BASE_URL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(SERVER_NOT_CONNECTED).assertDoesNotExist()
  }

  @Test
  fun signingOutAsksBeforeItDestroysAnything() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()

    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()

    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()
    // The body says what it costs. A confirmation whose honest answer is "not much" has to say so,
    // or the control that rescues a mistyped server is the one nobody dares press.
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_BODY).assertIsDisplayed()
    // And nothing has happened yet. This is the assertion the dialog exists for.
    assertThat(events).describedAs("what the section did merely on opening the dialog").isEmpty()
  }

  @Test
  fun cancellingTheConfirmationLeavesTheServerConnected() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()
    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()

    composeRule.onNodeWithText(CANCEL_LABEL).performClick()

    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertDoesNotExist()
    assertThat(events).describedAs("what the section did after Cancel").isEmpty()
    composeRule.onNodeWithText(BASE_URL).assertIsDisplayed()
  }

  @Test
  fun confirmingForgetsTheCredentialsFirstAndOnlyThenNavigates() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()
    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()

    // **Inside the dialog, by ancestry, not by index.** The confirm button carries the same label
    // as the row that opened it, so with the dialog up two nodes read "Sign out" and index 0 is the
    // one behind the scrim -- clicking that merely re-opens the dialog. `ServerChangeJourneyTest`
    // records the twenty-second timeout that mistake produces.
    composeRule.onNode(hasText(SIGN_OUT_LABEL) and hasAnyAncestor(isDialog())).performClick()

    composeRule.waitUntil(TIMEOUT_MS) { events.size == 2 }
    // **The order is the assertion.** `:app` turns `SetupRoute` into a stack reset, so navigating
    // first would tear down the library over a credential store that is still being cleared
    // underneath it. Both orders leave the same two entries in this list; only one is correct.
    assertThat(events).containsExactly(SIGN_OUT, NAVIGATE)
    assertThat(navigated).containsExactly(SetupRoute)
  }

  @Test
  fun theNavigationHappensOnTheMainThreadRatherThanWhereverTheSignOutFinished() {
    // `SetupScope` is `Dispatchers.Default`, and `onNavigate` mutates a `NavBackStack`. A snapshot
    // state list accepts a write from any thread, so without the `withContext(Dispatchers.Main)`
    // this would *appear* to work and would misbehave when a frame composed between the write and
    // its layout. That is a defect measured in weeks, and this is the only assertion that sees it.
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()
    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNode(hasText(SIGN_OUT_LABEL) and hasAnyAncestor(isDialog())).performClick()

    composeRule.waitUntil(TIMEOUT_MS) { navigatedOnMainThread != null }
    assertThat(navigatedOnMainThread)
      .describedAs("whether onNavigate was called on the main thread")
      .isTrue()
  }

  @Test
  fun theConfirmationSurvivesARotationRatherThanAnsweringNoOnTheUsersBehalf() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    val restoration = StateRestorationTester(composeRule)
    restoration.setContent { section.Content(onNavigate = { navigated += it }) }
    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()

    restoration.emulateSavedInstanceStateRestore()

    // `rememberSaveable`, not `remember`. Dismissing a destructive confirmation on the user's
    // behalf because the phone turned is a silent "no" to a question they were still reading.
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()
  }

  @Test
  fun everyControlOnTheServerSectionIsBigEnoughToTap() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()

    composeRule.assertEveryTapTargetIsBigEnough()
  }

  @Test
  fun everyControlOnTheConfirmationIsBigEnoughToTap() {
    account.identityFlow.value = ServerIdentity(BASE_URL, USERNAME)
    show()
    composeRule.onNodeWithText(SIGN_OUT_LABEL).performClick()
    composeRule.onNodeWithText(SIGN_OUT_CONFIRM_TITLE).assertIsDisplayed()

    // The dialog is a second window, so this also exercises the sweep's cross-root filter: without
    // it, the two dialog buttons are reported as crowding the row behind the scrim.
    composeRule.assertEveryTapTargetIsBigEnough()
  }

  /**
   * The seam this section was given a `ServerAccount` for: the real one opens an AndroidKeystore
   * key, and a settings section has no business holding a password.
   */
  private inner class FakeServerAccount : ServerAccount {
    val identityFlow = MutableStateFlow<ServerIdentity?>(null)
    override val identity: Flow<ServerIdentity?> = identityFlow

    override suspend fun signOut() {
      events += SIGN_OUT
      identityFlow.value = null
    }
  }

  private companion object {
    const val BASE_URL = "https://navidrome.example.test"
    const val USERNAME = "listener"

    /**
     * This suite's own copy, per the rule `JourneyNavigation.kt` states: a string a test makes a
     * claim about is the contract with what a user reads, and sharing the production constant lets
     * a wording change pass unnoticed. `SIGN_OUT_LABEL` and friends are deliberately *not* copied
     * -- they are `public const` in this module and asserting them here is what `ServerSection.kt`'s
     * KDoc has always said this class does.
     */
    const val CANCEL_LABEL = "Cancel"

    const val SIGN_OUT = "signOut"
    const val NAVIGATE = "navigate"

    const val TIMEOUT_MS = 5_000L
  }
}
