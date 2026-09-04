package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * `JourneyNavigation.kt`'s shared [reachLibraryScreen] makes every test here independent of which
 * test ran before it: the app opens on setup or on the library depending on stored state, and that
 * helper handles both. It lives outside this class because `ScopedShuffleJourneyTest` and
 * `PlaybackJourneyTest` walk the same path, and three copies of it is how a label change reddens
 * two suites and quietly fixes the third.
 */
@RunWith(AndroidJUnit4::class)
class BrowseJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun theLibraryScreenListsTheAlbumsOfTheSelectedLibrary() {
    composeRule.reachLibraryScreen()

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
    composeRule.reachLibraryScreen()

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
    composeRule.reachLibraryScreen()

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
    composeRule.reachLibraryScreen()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SEARCH_LABEL).performTextInput("Nothing Matches This")
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_ALBUM).fetchSemanticsNodes().isEmpty()
    }
    // Not "Nothing here yet." -- that sentence used to cover a search with no match, a sync still
    // running, a failed sync and a genuinely empty library all at once, so a typo'd server URL was
    // indistinguishable from a working app with no music. See `LibraryEmptyReason`.
    composeRule.onNodeWithText("No albums match \u201CNothing Matches This\u201D.").assertIsDisplayed()

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
    composeRule.reachLibraryScreen()
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
    composeRule.reachLibraryScreen()

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
    runBlocking { journeyWatermarkDao().store(STALE_WATERMARK) }
    check(runBlocking { journeyWatermarkDao().read() } == STALE_WATERMARK) {
      "the watermark was not made stale, so this test could not tell a refresh from a no-op"
    }

    composeRule.onNodeWithText(REFRESH_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(REFRESH_LABEL).performClick()

    // A reconcile ran, all the way through to its own last step: `SyncEngine` stores the watermark
    // only after every library's replacement transaction has committed.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      runBlocking { journeyWatermarkDao().read() }.let { it != null && it != STALE_WATERMARK }
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
    composeRule.reachLibraryScreen()
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
   * Blocks until two reads [SETTLE_MILLIS] apart return the same non-null watermark, i.e. until
   * nothing is still writing it.
   */
  private fun awaitQuietWatermark() {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      val first = runBlocking { journeyWatermarkDao().read() }
      Thread.sleep(SETTLE_MILLIS)
      if (first != null && first == runBlocking { journeyWatermarkDao().read() }) return
    }
    error("the sync watermark never settled: something is still reconciling after $TIMEOUT_MILLIS ms")
  }

  private companion object {
    // The literal strings the real screens render. Duplicated from the production code rather
    // than shared with it: these journeys are a black-box walk through what a user sees, and a
    // shared constant would let a change to that pass unnoticed. The credentials and the setup
    // screen's own labels moved to `JourneyNavigation.kt` with the walk that types them in.
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
    const val LIBRARY_CHIP = 0
    const val FIRST_ALBUM = 0

    /** Generous: a first sync fetches every album and every album's tracks over the loopback. */
    const val TIMEOUT_MILLIS = 30_000L
  }
}
