package app.muplay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    composeRule.reachLibraryScreen()

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
      // `notTheMiniPlayer()` on both, and neither is cosmetic -- see that helper. Unfiltered, the
      // first reports a leak whenever the bar is showing the book (whose track title *is*
      // [AUDIOBOOK_TITLE]), and the second is satisfied by a bar showing music even if this
      // shuffle returned none.
      composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).notTheMiniPlayer().assertCountEquals(0)
      // ...and something was actually shuffled, so the assertion above is not vacuous.
      MUSIC_TITLES.flatMap { composeRule.onAllNodesWithText(it).notTheMiniPlayer().fetchSemanticsNodes() }
        .also { check(it.isNotEmpty()) { "a music shuffle returned no music" } }
    }
  }

  /**
   * The control that makes the first test mean something: an audiobook shuffle really does return
   * audiobook content, so `shufflingTheMusicLibraryNeverSurfacesAnAudiobook` is not green merely
   * because nothing is ever shuffled.
   *
   * ### This counted occurrences of "Test Book" once, and the corpus grew out from under it
   *
   * The seeded audiobook's album name and its one song's title are the same string
   * (`ci/seed-fixtures.sh` writes both), so `"Test Book"` is on screen from the album list before
   * any shuffle. The old assertion therefore compared the count before and after, requiring the
   * shuffle to *add* an occurrence.
   *
   * That broke the moment the audiobook corpus grew past what fits one screen. Measured
   * 2026-08-28, from the semantics tree at the point of failure: after the shuffle the shuffled
   * section did contain `Test Book` — correctly — while the album `LazyColumn` below had been
   * pushed down far enough that it composed only `Multi Part Book`, `Second Book` and
   * `Tail Book`. One occurrence appeared and one stopped being composed, so the count stayed at 1
   * and the test failed against an app that was working. A lazily composed list is not a set of
   * nodes you can count across a screen.
   *
   * So the assertion is now the exact mirror of the music test above, which is immune to both:
   * [SHUFFLE_HEADING] renders only `if (uiState.shuffled.isNotEmpty())`, so waiting for it proves
   * the shuffle returned rows; and no music title may appear among them. Together the two tests
   * pin the scoping in both directions without counting anything.
   *
   * Falsified against the product, not the fixture: replace `shuffleRepository.shuffle(libraryId,
   * size)` with `shuffle(libraryRepository.allIds().first(), size)` — a shuffle that ignores which
   * library the user chose — and this test fails while the two beside it stay green. Measured
   * 2026-08-28.
   */
  @Test
  fun shufflingTheAudiobookLibraryDoesSurfaceTheAudiobook() {
    composeRule.reachLibraryScreen()

    composeRule.onAllNodesWithText(AUDIOBOOK_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).notTheMiniPlayer().fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    // Its presence *is* the "something was shuffled" assertion -- the heading is inside the
    // `isNotEmpty()` branch, so an empty shuffle times out here rather than passing quietly.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    // ...and what it shuffled is not music. The music library's three tracks are the only titles
    // that could leak in, and they are the same three the mirror test asserts on.
    val music = MUSIC_TITLES.flatMap {
      composeRule.onAllNodesWithText(it).notTheMiniPlayer().fetchSemanticsNodes()
    }
    check(music.isEmpty()) {
      "an audiobook shuffle surfaced music: ${MUSIC_TITLES.joinToString()} should not be on screen"
    }
  }

  /**
   * The same scoping claim, made while the **mini player is showing a book** — which is the state
   * every run of the full suite actually reaches, and which the two tests above cannot survive.
   *
   * `MiniPlayer` renders the playing *track's* title, and `books.tsv` gives the seeded book's one
   * file the track title `Test Book` — byte-for-byte [AUDIOBOOK_TITLE], the string
   * [shufflingTheMusicLibraryNeverSurfacesAnAudiobook] searches for to prove a leak. So a bar left
   * over from any earlier journey puts that string on the library screen with no shuffle involved,
   * and an unfiltered matcher reads the app's own "now playing" bar as a scoping defect.
   *
   * All three failure directions were measured here before the filter existed, and they are why
   * this is a test rather than a comment:
   *
   *  - **False red.** `assertDoesNotExist()` finds the bar and reports that a music shuffle
   *    surfaced an audiobook. The most important behavioural claim in this app, wrongly failed.
   *  - **False green.** The non-vacuity guard below it — "something really was shuffled" — is
   *    satisfied by a bar showing `Track 1`, so it would keep passing over a shuffle that returned
   *    nothing at all. That is the assertion-that-cannot-fail shape this repository exists to keep
   *    out of its own gates.
   *  - **False red again**, in [shufflingTheAudiobookLibraryDoesSurfaceTheAudiobook], whose
   *    `check(music.isEmpty())` reads a bar showing music as an audiobook shuffle leaking music.
   *
   * This test pins the first two deterministically. Before, they surfaced only when an earlier
   * class happened to leave the right thing playing — the "order-dependent flake whose failing
   * test moves between runs" CLAUDE.md records, with a cause rather than a shrug.
   */
  @Test
  fun aMusicShuffleIsScopedEvenWhileTheMiniPlayerIsShowingABook() {
    composeRule.reachLibraryScreen()

    // Leave a book playing, then come back. Paused rather than stopped, because a paused bar is
    // exactly what an earlier journey leaves behind and it stays on screen indefinitely.
    composeRule.openBookshelf()
    composeRule.openBookNamed(AUDIOBOOK_TITLE)
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    composeRule.pausePlayback()
    composeRule.pressBackToLibraryScreen()

    composeRule.onAllNodesWithText(MUSIC_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isEmpty()
    }

    // The premise, checked rather than assumed, and checked *here* rather than before the switch:
    // on the Music library nothing but the bar can carry the book's title, so this cannot be
    // satisfied by an Audiobooks album row that an earlier test left selected. Without the bar
    // this test proves nothing, so a change that stopped showing the title has to fail loudly
    // rather than make it quietly vacuous.
    composeRule.waitUntil("the mini player to carry the book's title", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    check(composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).fetchSemanticsNodes().size == 1) {
      "the mini player is not showing \"$AUDIOBOOK_TITLE\"; this test would prove nothing"
    }
    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    // The library screen holds no audiobook -- the bar above it is not the library.
    composeRule.onAllNodesWithText(AUDIOBOOK_TITLE).notTheMiniPlayer().assertCountEquals(0)
    // ...and the shuffle really returned music, counted outside the bar so that a bar showing a
    // music track cannot satisfy this on an empty shuffle's behalf.
    val shuffled = MUSIC_TITLES.flatMap {
      composeRule.onAllNodesWithText(it).notTheMiniPlayer().fetchSemanticsNodes()
    }
    check(shuffled.isNotEmpty()) { "a music shuffle returned no music" }
  }

  @Test
  fun switchingLibraryClearsThePreviousShuffle() {
    composeRule.reachLibraryScreen()

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

  private companion object {
    // The credentials and the setup screen's own labels live in `JourneyNavigation.kt`, with the
    // walk that types them in. What stays here is what this journey *asserts*.
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val SHUFFLE_HEADING = "Shuffled"

    /** The one seeded audiobook — ci/seed-fixtures.sh writes `Test Book.m4b`. */
    const val AUDIOBOOK_TITLE = "Test Book"

    /** The music library's seeded titles -- the only ones an audiobook shuffle could wrongly show. */
    val MUSIC_TITLES = listOf("Track 1", "Track 2", "Track 3")

    /** The two library chips' own labels, i.e. the names ci/configure-libraries.sh gives them. */
    /** `BookScreen`'s own button, duplicated from it -- see this companion's note above. */
    const val START_OVER_LABEL = "Start from the beginning"

    const val MUSIC_LIBRARY = "Music"
    const val AUDIOBOOK_LIBRARY = "Audiobooks"

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
