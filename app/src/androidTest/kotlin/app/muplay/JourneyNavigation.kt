package app.muplay

import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.MediaProgressEntryPoint
import app.muplay.database.SyncWatermarkEntryPoint
import app.muplay.database.dao.MediaProgressDao
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
  // Named, not bare. A missing `adb reverse tcp:4533 tcp:4533` is a *silent* connect timeout --
  // spike S1's finding -- so this is the wait that expires when the container is unreachable, and
  // an unnamed `ComposeTimeoutException` here is the least useful message this suite can produce.
  waitUntil("the app to decide between setup and the library", JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty() ||
      onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
  }
  val needsSetup = onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
  if (needsSetup) {
    onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
    onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
    onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
    onNodeWithText(CONNECT_LABEL).performClick()

    waitUntil("the server to answer the connect attempt", JOURNEY_TIMEOUT_MILLIS) {
      onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick()
    onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick()
    onNodeWithText(CONTINUE_LABEL).performClick()
  }

  waitUntil("the library screen to be reached", JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
  }
  // The launch sync has committed at least once, so the mirror really holds the seeded content.
  // See this helper's own doc, point 2, and `BrowseJourneyTest.theLibraryCanBeRefreshedFromTheScreen`
  // for why the watermark rather than the on-screen "Checking the server for changes…" message.
  waitUntil("the launch sync to commit a watermark", JOURNEY_TIMEOUT_MILLIS) {
    runBlocking { journeyWatermarkDao().read() } != null
  }
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

// ---- Audiobooks ---------------------------------------------------------------------------------

/**
 * From the library screen to the audiobook shelf.
 *
 * Plan 4 Task 10. Until these helpers existed nothing in `:app` had ever navigated to
 * [app.muplay.ui.navigation.BookshelfRoute] or [app.muplay.ui.navigation.BookRoute] — both keys
 * measured **0 lines covered**, and `MuPlayApp`'s own comment beside those two `entry` blocks says
 * so from the other side.
 *
 * The same stance as everything above: what is shared here is *navigation*, and the strings it taps
 * are duplicated from `:feature:book`'s `BookLabels.kt` rather than imported from it, so a wording
 * change is caught rather than silently followed.
 */
internal fun ComposeTestRule.openBookshelf() {
  onNodeWithText(BOOKS_LABEL).performClick()
  waitUntil("the bookshelf to leave its loading state", JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(LOADING_BOOKS_LABEL).fetchSemanticsNodes().isEmpty()
  }
}

/**
 * Taps a book on the shelf and waits for its own screen.
 *
 * **Not `onNodeWithText(title)`**, and the reason is the mini player: it renders the playing item's
 * title too, so from the second visit onwards there are *two* nodes carrying a book's name and the
 * single-node matcher throws before anything is asserted. The mini player's whole `Row` carries
 * `contentDescription = "Now playing"` (`MiniPlayer` sets it so a journey has a handle on the bar),
 * which is what tells the two apart — a filter on the *bar*, not on a position on screen, so it
 * needs no re-measuring at another screen density.
 */
internal fun ComposeTestRule.openBookNamed(title: String) {
  waitUntil("the bookshelf to list \"$title\"", JOURNEY_TIMEOUT_MILLIS) {
    bookRows(title).fetchSemanticsNodes().isNotEmpty()
  }
  bookRows(title)[0].performClick()
  waitUntil("\"$title\"'s own screen", JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithText(CHAPTERS_HEADING).fetchSemanticsNodes().isNotEmpty()
  }
}

private fun ComposeTestRule.bookRows(title: String): SemanticsNodeInteractionCollection =
  onAllNodesWithText(title).filter(hasContentDescription(MINI_PLAYER_LABEL).not())

/**
 * The one pause control on screen, whichever surface is showing it.
 *
 * All three of them label it with the same word -- as an icon's `contentDescription` since the
 * design pass, which is the same accessible name and the same string — `:feature:player`'s full player, `:feature:book`'s
 * book player, and the mini player under everything else — and exactly one of them is ever visible
 * at a time, because `MuPlayApp` hides the mini player on both player screens. The count is
 * therefore asserted rather than assumed: a helper that quietly clicked nothing would make every
 * assertion after it meaningless, which is the failure this repository keeps finding in its own
 * gates.
 */
internal fun ComposeTestRule.pausePlayback() {
  waitUntil("a pause control to be on screen", JOURNEY_TIMEOUT_MILLIS) {
    onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
  }
  val found = onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().size
  check(found == 1) { "expected exactly one \"$PAUSE_LABEL\" control on screen, found $found" }
  onNodeWithContentDescription(PAUSE_LABEL).performClick()
}

/**
 * Backs out to the library screen with real system back presses.
 *
 * A real `pressBack` and not a node labelled "Back": predictive back is on, and the app's back
 * affordance is the system gesture rather than a labelled button — looking for text would find
 * nothing and the failure would read as "the screen did not render".
 *
 * The Shuffle check happens **before** each press, never after: a back press on the library screen
 * finishes the activity, and every Espresso call after that dies with `NoActivityResumedException`
 * naming nothing that happened here.
 */
internal fun ComposeTestRule.pressBackToLibraryScreen() {
  repeat(MAX_BACK_PRESSES) {
    if (onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()) return
    Espresso.pressBack()
    waitForIdle()
  }
  throw AssertionError(
    "the library screen was still not on top after $MAX_BACK_PRESSES back presses",
  )
}

/** The real singleton [MediaProgressDao] — the table the app itself writes listening positions to. */
internal fun journeyProgressDao(): MediaProgressDao =
  EntryPointAccessors.fromApplication(
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
    MediaProgressEntryPoint::class.java,
  ).mediaProgressDao()

/** The library screen's button into the shelf. `LibraryScreen`'s `BOOKS_LABEL`. */
private const val BOOKS_LABEL = "Books"

/** `BookshelfScreen`'s loading state. The shelf renders this and nothing else until it has rows. */
private const val LOADING_BOOKS_LABEL = "Loading books"

/** `BookScreen`'s chapter heading — present on every book, chapters extracted or not. */
private const val CHAPTERS_HEADING = "Chapters"

/** Shared by `PlayerScreen`, `BookPlayerScreen` and `MiniPlayer`. */
private const val PAUSE_LABEL = "Pause"

/** `MiniPlayer`'s own accessible name, which is what tells its title apart from a shelf row's. */
private const val MINI_PLAYER_LABEL = "Now playing"

/** Book player -> book -> shelf -> library is three; the fourth is the margin that reports rather than exits. */
private const val MAX_BACK_PRESSES = 4
