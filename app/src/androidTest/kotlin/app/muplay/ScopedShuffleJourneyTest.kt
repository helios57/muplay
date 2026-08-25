package app.muplay

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Tier 2: **the feature this application exists for**, end to end.
 *
 * Real app, real emulator, real Navidrome with two real libraries. Shuffle the music library
 * repeatedly and assert the audiobook never appears — the assertion the user actually cares
 * about, which no unit test and no fixture can make, because its subject is the whole chain: the
 * request the client builds, the scoping the server applies, the mirror's own stamp, and the
 * screen that renders the result.
 *
 * The audiobook control below is not decoration. Without it this suite would pass identically
 * against an app that shuffled nothing at all — which is the exact shape of the silent gate this
 * project has already shipped once (a live-Navidrome test that passed with no Navidrome).
 */
@RunWith(AndroidJUnit4::class)
class ScopedShuffleJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  /**
   * Every attempt re-selects the Music library first, which clears the previous shuffle
   * (`LibraryViewModel.selectLibrary`), and then waits for [SHUFFLE_HEADING] to be **gone** before
   * shuffling again.
   *
   * The brief's loop did neither, and without them the ten attempts are one attempt observed ten
   * times: `Shuffled` stays on screen after the first result, so every later
   * `waitUntil { headingPresent }` succeeds on its first poll against the *previous* shuffle's
   * songs and the click that was supposed to produce a fresh draw is never waited for at all.
   */
  @Test
  fun shufflingTheMusicLibraryNeverSurfacesAnAudiobook() {
    reachLibraryScreen()

    repeat(SHUFFLE_ATTEMPTS) {
      composeRule.onAllNodesWithText(MUSIC_LIBRARY)[LIBRARY_CHIP].performClick()
      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isEmpty()
      }

      composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
      }

      // The whole point, asserted on screen: the audiobook chapter is never in a music shuffle.
      composeRule.onNodeWithText(AUDIOBOOK_TITLE).assertDoesNotExist()
      // ...and something was actually shuffled, so the assertion above is not vacuous.
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes()
        .plus(composeRule.onAllNodesWithText("Track 2").fetchSemanticsNodes())
        .plus(composeRule.onAllNodesWithText("Track 3").fetchSemanticsNodes())
        .also { check(it.isNotEmpty()) { "a music shuffle returned no music" } }
    }
  }

  /**
   * The control that makes the first test mean something.
   *
   * Counted, not asserted by presence. The seeded audiobook's **album name and its one song's
   * title are the same string** — `ci/seed-fixtures.sh` writes `-metadata title="Test Book"` and
   * `-metadata album="Test Book"` — so `"Test Book"` is already on screen from the album list
   * before a shuffle happens at all. `onNodeWithText(AUDIOBOOK_TITLE).assertIsDisplayed()`, which
   * is what the brief asked for, would therefore either pass against an app that shuffled nothing
   * (the defect this control exists to rule out) or, once the shuffle did work, fail outright on
   * two matching nodes. What discriminates is that the shuffle *added* an occurrence.
   */
  @Test
  fun shufflingTheAudiobookLibraryDoesSurfaceTheAudiobook() {
    reachLibraryScreen()

    composeRule.onAllNodesWithText(AUDIOBOOK_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    val beforeShuffle = composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().size

    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    val afterShuffle = composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().size
    check(afterShuffle > beforeShuffle) {
      "an audiobook shuffle put no audiobook on screen: $AUDIOBOOK_TITLE appeared $beforeShuffle " +
        "time(s) before the shuffle and $afterShuffle after"
    }
  }

  @Test
  fun switchingLibraryClearsThePreviousShuffle() {
    reachLibraryScreen()

    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onAllNodesWithText(AUDIOBOOK_LIBRARY)[LIBRARY_CHIP].performClick()

    // A shuffle belongs to the library it was drawn from. Carrying it across a switch would show
    // music tracks under the audiobook tab, which is the exact confusion this app removes.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isEmpty()
    }
  }

  /** Identical in intent to `BrowseJourneyTest.reachLibraryScreen`; see that class for why. */
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
    // The launch sync has committed at least once, so the mirror really holds the seeded songs --
    // without which every shuffle here would draw from nothing. See
    // `BrowseJourneyTest.theLibraryCanBeRefreshedFromTheScreen` for why the watermark rather than
    // the on-screen "Checking the server for changes…" message.
    composeRule.waitUntil(TIMEOUT_MILLIS) { runBlocking { watermarkDao().read() } != null }
  }

  /** The real singleton [app.muplay.database.dao.SyncWatermarkDao] the app itself syncs through. */
  private fun watermarkDao() =
    EntryPointAccessors.fromApplication(
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
      SyncWatermarkEntryPoint::class.java,
    ).syncWatermarkDao()

  private companion object {
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val TAG_AS_MUSIC_LABEL = "Tag as Music"
    const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val SHUFFLE_HEADING = "Shuffled"

    /** The one seeded audiobook — ci/seed-fixtures.sh writes `Test Book.m4b`. */
    const val AUDIOBOOK_TITLE = "Test Book"

    /** The two library chips' own labels, i.e. the names ci/configure-libraries.sh gives them. */
    const val MUSIC_LIBRARY = "Music"
    const val AUDIOBOOK_LIBRARY = "Audiobooks"

    const val MUSIC_ROW_CHIP = 0
    const val AUDIOBOOKS_ROW_CHIP = 1
    const val LIBRARY_CHIP = 0

    /**
     * Ten on the device, against fifty in `LiveNavidromeTest`. The server-side scoping is already
     * proven fifty times over in Tier 1; what this journey adds is the whole chain through the
     * mirror and the UI, and each attempt here costs an emulator round trip against a 45-minute
     * job budget.
     */
    const val SHUFFLE_ATTEMPTS = 10

    const val TIMEOUT_MILLIS = 30_000L
  }
}
