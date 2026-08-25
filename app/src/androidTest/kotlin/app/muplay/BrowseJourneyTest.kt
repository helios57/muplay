package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.SyncWatermarkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
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
   * The user has a way to pick up a server-side change, and it is on the screen **and wired**.
   *
   * `syncIfStale()` had exactly one caller — `LibraryViewModel.init` — so before this journey
   * existed, an album added to Navidrome mid-session never appeared, and the `ScanInProgress`
   * branch told the user their library would "update shortly" while nothing re-checked. This test
   * is the standing guarantee that the control the copy names is really there **and really runs
   * a sync**: the two are separate defects (a control that is not there, and a control that does
   * nothing) and each is independently visible here.
   *
   * **Not asserted on the "Checking the server for changes…" message, which the brief for this
   * task asked for, because that assertion cannot be made to work either way round.** Measured on
   * `muplay37`, against an already-synced mirror: a `waitUntil` polling continuously for that
   * string never saw it in 30 seconds, although the refresh had demonstrably run — one
   * `getScanStatus` round trip over the loopback is shorter than a frame, and every hop between
   * the ViewModel and the screen (`MutableStateFlow` → `combine` → `stateIn` →
   * `collectAsStateWithLifecycle` → Compose's per-frame recomposition) is conflated, so the
   * transient is swallowed before anything is drawn. And waiting for the message to *clear*, which
   * is what the brief proposed, is strictly worse than no assertion: with `refresh()` mutated to
   * an empty body the message is never set at all, so that wait succeeds on its first poll and the
   * gate reports safety while the button does nothing.
   *
   * The watermark is the durable observable that does discriminate. Cleared through
   * [SyncWatermarkEntryPoint], the next `syncIfStale()` is a real reconcile
   * (`SyncDecision.decide(null, …)` → `Reconcile`) and stores one again; nothing else in the app
   * writes it, and the app's own launch sync has long since finished by the time this test clears
   * it.
   */
  @Test
  fun theLibraryCanBeRefreshedFromTheScreen() {
    reachLibraryScreen()

    // The app's own launch sync has to be finished before this test may make the mirror stale on
    // purpose. On a cold install `LibraryViewModel.init`'s refresh is a full reconcile that stores
    // a watermark of its own at the very end, and `reachLibraryScreen` returns while it is still
    // running -- so without this, the wait below could be satisfied by the launch rather than by
    // the button, which is the "verified at a different layer than it was applied" defect in
    // miniature.
    awaitQuietWatermark()

    // Provably stale, and provably so *before* the click: an assertion that a value changed is
    // worth nothing without having read its starting value. A sentinel rather than `clear()`, so
    // that "the refresh wrote this" and "something left it null" cannot be confused.
    runBlocking { watermarkDao().store(STALE_WATERMARK) }
    check(runBlocking { watermarkDao().read() } == STALE_WATERMARK) {
      "the watermark was not made stale, so this test could not tell a refresh from a no-op"
    }

    composeRule.onNodeWithText(REFRESH_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(REFRESH_LABEL).performClick()

    // A reconcile ran, all the way through to its own last step: `SyncEngine` stores the watermark
    // only after every library's replacement transaction has committed.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      runBlocking { watermarkDao().read() }.let { it != null && it != STALE_WATERMARK }
    }
    composeRule.onNodeWithText(MUSIC_ALBUM).assertIsDisplayed()
  }

  /** The real singleton [app.muplay.database.dao.SyncWatermarkDao] the app itself syncs through. */
  private fun watermarkDao() =
    EntryPointAccessors.fromApplication(
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
      SyncWatermarkEntryPoint::class.java,
    ).syncWatermarkDao()

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
    // The launch sync has committed at least once, so the mirror really holds the seeded content.
    // See this helper's own doc, point 2, and `theLibraryCanBeRefreshedFromTheScreen` for why the
    // watermark rather than the on-screen "Checking the server for changes…" message.
    composeRule.waitUntil(TIMEOUT_MILLIS) { runBlocking { watermarkDao().read() } != null }
  }

  /**
   * Blocks until two reads [SETTLE_MILLIS] apart return the same non-null watermark, i.e. until
   * nothing is still writing it.
   */
  private fun awaitQuietWatermark() {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      val first = runBlocking { watermarkDao().read() }
      Thread.sleep(SETTLE_MILLIS)
      if (first != null && first == runBlocking { watermarkDao().read() }) return
    }
    error("the sync watermark never settled: something is still reconciling after $TIMEOUT_MILLIS ms")
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

    /**
     * Older than any real Navidrome `lastScan`, so `SyncDecision.decide` reads it as stale and
     * the next `syncIfStale()` is a genuine reconcile.
     */
    const val STALE_WATERMARK = "1970-01-01T00:00:00Z"

    /** Long enough for one loopback `getScanStatus` plus a Room commit; see `awaitQuietWatermark`. */
    const val SETTLE_MILLIS = 2_000L

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
