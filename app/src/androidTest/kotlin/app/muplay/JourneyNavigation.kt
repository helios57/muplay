package app.muplay

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.SyncWatermarkEntryPoint
import app.muplay.database.dao.SyncWatermarkDao
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking

/**
 * The walk from whatever state the app opened in to a **settled** library screen, shared by every
 * Tier 2 journey that starts there.
 *
 * One copy, deliberately. This sequence used to be duplicated verbatim in `BrowseJourneyTest` and
 * `ScopedShuffleJourneyTest`, and Plan 3 Task 10 would have made it three: a wording change on the
 * setup screen would then have reddened two suites and quietly fixed the third, which is how a
 * navigation helper becomes the thing that disagrees with itself.
 *
 * **What is shared here is navigation, not assertion.** The labels below are the ones this helper
 * has to *type into and tap* to get anywhere; every string a journey makes a claim about stays
 * duplicated inside that journey's own companion, because those are the black-box contract with
 * what a user sees and a shared constant would let a wording change pass unnoticed. See
 * `BrowseJourneyTest`'s own note.
 *
 * Two things this does that a naive version does not, both required against the real app rather
 * than against a description of it:
 *
 *  1. **It waits for the start destination to be decided before reading it.**
 *     `StartDestinationViewModel` opens on `StartDestination.Loading`, which renders nothing at
 *     all, so a bare `onAllNodesWithText(SERVER_URL_LABEL).isNotEmpty()` taken immediately after
 *     launch answers "no setup needed" for the Loading frame and every caller then dies on a
 *     30-second timeout waiting for a screen it never navigated to.
 *  2. **It waits for the launch sync to settle.** `LibraryViewModel.init` calls `refresh()`, and
 *     on a first run that is what populates the mirror. Returning as soon as the Shuffle button
 *     exists hands the caller a screen whose album list is still empty.
 *
 * The role chips are found by their own `"Tag as …"` labels, not by the bare library names:
 * `SetupScreen` deliberately labels them distinctly, so `onAllNodesWithText("Music")` matches
 * exactly one node — the library's *name* — and indices 1 and 2 into it do not exist.
 */
internal fun ComposeTestRule.reachLibraryScreen() {
  waitUntil(JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty() ||
      onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
  }
  val needsSetup = onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
  if (needsSetup) {
    onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
    onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
    onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
    onNodeWithText(CONNECT_LABEL).performClick()

    waitUntil(JOURNEY_TIMEOUT_MILLIS) {
      onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick()
    onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick()
    onNodeWithText(CONTINUE_LABEL).performClick()
  }

  waitUntil(JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
  }
  // The launch sync has committed at least once, so the mirror really holds the seeded content.
  // See this helper's own doc, point 2, and `BrowseJourneyTest.theLibraryCanBeRefreshedFromTheScreen`
  // for why the watermark rather than the on-screen "Checking the server for changes…" message.
  waitUntil(JOURNEY_TIMEOUT_MILLIS) { runBlocking { journeyWatermarkDao().read() } != null }
}

/** The real singleton [SyncWatermarkDao] the app itself syncs through. */
internal fun journeyWatermarkDao(): SyncWatermarkDao =
  EntryPointAccessors.fromApplication(
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
    SyncWatermarkEntryPoint::class.java,
  ).syncWatermarkDao()

/** ci/navidrome.compose.yml's credentials, reached over `adb reverse tcp:4533 tcp:4533`. */
internal const val SERVER_URL = "http://localhost:4533"
internal const val USERNAME = "admin"
internal const val PASSWORD = "testpass"

// The labels this helper types into and taps. Only these -- see the doc above on why every
// *asserted* label stays inside the journey that asserts it.
private const val SERVER_URL_LABEL = "Server URL"
private const val USERNAME_LABEL = "Username"
private const val PASSWORD_LABEL = "Password"
private const val CONNECT_LABEL = "Connect"
private const val CONTINUE_LABEL = "Continue"
private const val TAG_AS_MUSIC_LABEL = "Tag as Music"
private const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"

/**
 * The library screen's own Shuffle button, which is how this helper knows it has arrived. Also
 * asserted by name in `ScopedShuffleJourneyTest` and `PlaybackJourneyTest`, which keep their own
 * copies for that.
 */
private const val SHUFFLE_LABEL = "Shuffle this library"

/** Row positions in the tagging list, not roles -- see `FirstRunJourneyTest`'s own note. */
private const val MUSIC_ROW_CHIP = 0
private const val AUDIOBOOKS_ROW_CHIP = 1

/** Generous: a first sync fetches every album and every album's tracks over the loopback. */
private const val JOURNEY_TIMEOUT_MILLIS = 30_000L
