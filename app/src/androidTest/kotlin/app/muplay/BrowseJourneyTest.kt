package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: browsing a real Navidrome's real library, on a real emulator.
 *
 * Nothing here is faked. The app is the real debug APK; the server is the pinned
 * `deluan/navidrome:0.63.2` from `ci/navidrome.compose.yml`, seeded by `ci/seed-fixtures.sh` and
 * configured by `ci/configure-libraries.sh` into `Music` (library 1) and `Audiobooks`
 * (library 2). The preconditions this test cannot establish for itself — the container being up,
 * `adb reverse tcp:4533 tcp:4533`, and the emulator's `-feature Minigbm -prop
 * qemu.hardware.gralloc=minigbm` boot flags — are all handled by `ci/prepare-emulator.sh`, which
 * `.github/workflows/e2e.yml` runs and which a local run must run too. See
 * `FirstRunJourneyTest`'s own documentation for what each one costs when it is missing.
 *
 * [reachLibraryScreen] makes every test here independent of which test ran before it: the app
 * opens on setup or on the library depending on stored state, and this helper handles both.
 */
@RunWith(AndroidJUnit4::class)
class BrowseJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun theLibraryScreenListsTheAlbumsOfTheSelectedLibrary() {
    reachLibraryScreen()

    // The seeded music library: one album, "Test Album", from ci/seed-fixtures.sh. A contract on
    // real server state, not on a response shape.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
    composeRule.onNodeWithText("Test Artist").assertIsDisplayed()
  }

  @Test
  fun switchingLibraryShowsTheOtherLibrarysContentAndOnlyThat() {
    reachLibraryScreen()

    composeRule.onAllNodesWithText(AUDIOBOOK_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(AUDIOBOOK_TITLE).assertIsDisplayed()
    // The scoping contract at the UI level: the music album must be gone, not merely further
    // down the list.
    composeRule.onNodeWithText(MUSIC_ALBUM).assertDoesNotExist()
  }

  @Test
  fun openingAnAlbumShowsItsTracks() {
    reachLibraryScreen()

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodesWithText(OPEN_LABEL)[FIRST_ALBUM].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Track 1").assertIsDisplayed()
    composeRule.onNodeWithText("Track 2").assertIsDisplayed()
    composeRule.onNodeWithText("Track 3").assertIsDisplayed()
  }

  @Test
  fun searchNarrowsTheListAndClearingItRestoresTheList() {
    reachLibraryScreen()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SEARCH_LABEL).performTextInput("Nothing Matches This")
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()

    composeRule.onNodeWithText(SEARCH_LABEL).performTextClearance()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
  }

  /**
   * Search reaches the *server's* search, not a local `contains` over what is already on screen.
   *
   * "alb" is a strict substring of neither the album's first word nor any whole token a naive
   * equality filter would match, so this fails against a screen that only ever filters the
   * already-loaded list by exact name — and it is the one assertion here that would notice
   * `LibraryViewModel.search` forwarding a hardcoded query.
   */
  @Test
  fun searchFindsTheAlbumByAPartialName() {
    reachLibraryScreen()
    // The list has to have been there *before* the search, or "it came back" proves nothing.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SEARCH_LABEL).performTextInput("alb")
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
  }

  /**
   * The user has a way to pick up a server-side change, and it is on the screen.
   *
   * `syncIfStale()` had exactly one caller — `LibraryViewModel.init` — so before this journey
   * existed, an album added to Navidrome mid-session never appeared, and the `ScanInProgress`
   * branch told the user their library would "update shortly" while nothing re-checked. This test
   * is the standing guarantee that the control the copy names is really there: delete the button
   * and it goes red, which is the point.
   *
   * **Three waits, in this order, and none of them is ceremony.** The brief for this task asked
   * only for the third — wait for [SYNCING_MESSAGE] to *clear* — on the reasoning that
   * `syncIfStale` sets the "checking" message first, so a click that reached nothing would leave
   * that string on screen. Measured against the real code, that is exactly backwards and would
   * have shipped this project's own signature defect: with `LibraryViewModel.refresh` mutated to
   * an empty body the message is never set **at all**, so a lone wait-for-absence succeeds on its
   * first poll and the test stays green against a Refresh button that does nothing. Waiting for
   * the message to *appear* is what discriminates; waiting for it to be absent **first** is what
   * stops the appearance being satisfied by the `init { refresh() }` sync that is still in flight
   * from the app's own launch — without which a disconnected `onClick = {}` also passes.
   */
  @Test
  fun theLibraryCanBeRefreshedFromTheScreen() {
    reachLibraryScreen()

    composeRule.onNodeWithText(REFRESH_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(REFRESH_LABEL).performClick()

    // 1. This click's own sync started, and said so. A Refresh whose `onClick` reached nothing,
    //    or a `refresh()` with an empty body, never gets here.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SYNCING_MESSAGE).fetchSemanticsNodes().isNotEmpty()
    }
    // 2. ...and it completed rather than hanging: a refresh against an up-to-date mirror settles
    //    back to no message at all.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SYNCING_MESSAGE).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
  }

  /**
   * Spec §7: *"Predictive back is default-on and must be implemented."*
   *
   * Plan 1 set `android:enableOnBackInvokedCallback="true"` and this plan gives `NavDisplay` a real
   * back stack with `onBack` — so the phone side works. **No plan named it as a deliverable and no
   * journey asserted it**, which is how a working behaviour becomes an unnoticed regression: the
   * day someone replaces `backStack.removeLastOrNull()` with a no-op, every test stays green and
   * the back gesture closes the app from the album screen. Plan 5 owns the watch side properly;
   * this is the phone side, and it is one assertion.
   */
  @Test
  fun backFromAnAlbumReturnsToTheLibraryRatherThanLeavingTheApp() {
    reachLibraryScreen()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodesWithText(OPEN_LABEL)[FIRST_ALBUM].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }
    // The album screen really did replace the library screen -- otherwise "back returned us to
    // the library" would be satisfied by never having left it.
    composeRule.onNodeWithText(SHUFFLE_LABEL).assertDoesNotExist()

    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
      .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    // Back to the library, still inside the app. `SHUFFLE_LABEL` is only on the library screen, so
    // finding it proves both halves at once — and the activity not having been destroyed is what
    // `composeRule` finding anything at all proves.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
  }

  /**
   * Drives the app from whatever state it opened in to a settled library screen.
   *
   * The app opens on setup when no credentials are stored **or** any library is still untagged,
   * and on the library otherwise — so which branch this takes depends on what earlier tests in
   * the same instrumentation run left behind. Handling both here is what makes every test in this
   * class independent of run order, without any test needing to clear app data (which, from
   * inside the app's own process, is not something a test can do cleanly).
   *
   * Two things the brief's version of this helper did not do, both required for it to work
   * against the real app rather than against a description of it:
   *
   * 1. **It waits for the start destination to be decided before reading it.**
   *    `StartDestinationViewModel` opens on `StartDestination.Loading`, which renders nothing at
   *    all, so a bare `onAllNodesWithText(SERVER_URL_LABEL).isNotEmpty()` taken immediately after
   *    launch answers "no setup needed" for the Loading frame and every test then dies on a
   *    30-second timeout waiting for a screen it never navigated to.
   * 2. **It waits for the launch sync to settle.** `LibraryViewModel.init` calls `refresh()`, and
   *    on a first run that is what populates the mirror. Returning as soon as the Shuffle button
   *    exists hands every caller a screen whose album list is still empty.
   *
   * The role chips are found by their own `"Tag as …"` labels, not by the bare library names the
   * brief indexed into: `SetupScreen` deliberately labels them distinctly (see its own doc, and
   * `FirstRunJourneyTest`'s), so `onAllNodesWithText("Music")` matches exactly one node — the
   * library's *name* — and indices 1 and 2 into it do not exist.
   */
  private fun reachLibraryScreen() {
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty() ||
        composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    val needsSetup = composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
    if (needsSetup) {
      composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
      composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
      composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
      composeRule.onNodeWithText(CONNECT_LABEL).performClick()

      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick()
      composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick()
      composeRule.onNodeWithText(CONTINUE_LABEL).performClick()
    }

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    // The launch sync has finished. See this helper's own doc, point 2.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SYNCING_MESSAGE).fetchSemanticsNodes().isEmpty()
    }
  }

  private companion object {
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    // The literal strings the real screens render. Duplicated from the production code rather
    // than shared with it: these journeys are a black-box walk through what a user sees, and a
    // shared constant would let a change to that pass unnoticed.
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val TAG_AS_MUSIC_LABEL = "Tag as Music"
    const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"
    const val SEARCH_LABEL = "Search this library"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val REFRESH_LABEL = "Refresh library"
    const val OPEN_LABEL = "Open"
    const val SYNCING_MESSAGE = "Checking the server for changes…"

    /** The seeded content, per `ci/seed-fixtures.sh`. */
    const val MUSIC_ALBUM = "Test Album"
    const val AUDIOBOOK_TITLE = "Test Book"
    const val AUDIOBOOK_LIBRARY = "Audiobooks"

    /** See FirstRunJourneyTest for why these are indices; verify them by running, not by reasoning. */
    const val MUSIC_ROW_CHIP = 0
    const val AUDIOBOOKS_ROW_CHIP = 1
    const val LIBRARY_CHIP = 0
    const val FIRST_ALBUM = 0

    /** Generous: a first sync fetches every album and every album's tracks over the loopback. */
    const val TIMEOUT_MILLIS = 30_000L
  }
}
