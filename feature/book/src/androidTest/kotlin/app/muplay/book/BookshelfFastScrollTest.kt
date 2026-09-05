package app.muplay.book

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.component.FAST_SCROLL_BAR_TAG
import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The A–Z index, driven on a real composition of a real screen.
 *
 * `:core:designsystem` has no instrumented tier of its own and `FastScrollBar` is the kind of thing
 * that cannot be proven without one: it is a raw pointer region over a measured height, and every
 * part of it that can be wrong — the height it divides, which bucket a y lands in, which row of the
 * `LazyColumn` that bucket names once the header above it is counted — only exists once something
 * has been laid out. `FastScrollIndexTest` holds the arithmetic on the fast tier; this holds the
 * wiring, and the shelf is the cheapest screen in this repository that composes the bar over a list
 * long enough to need it.
 *
 * **These tests have never been executed.** The emulator was replaced mid-session with an instance
 * built with different flags and never reached `sys.boot_completed`, so `adb devices` reports it
 * `offline` and no device suite can run. Every assertion below is an argument about the code rather
 * than a measurement of it, and no coverage floor is claimed for any of it. `CLAUDE.md`'s rule for
 * exactly this situation is to write the tests, compile them, and say so.
 *
 * camelCase method names, per `CLAUDE.md`: D8 refuses a space in any `SimpleName` at DEX 035.
 */
@RunWith(AndroidJUnit4::class)
class BookshelfFastScrollTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun show(books: List<BookSummary>) {
    composeRule.setContent {
      BookshelfContent(
        state = BookshelfUiState.Content(books),
        onBookClick = {},
        onResume = {},
        coverArtUrl = NO_COVER,
      )
    }
  }

  /**
   * [count] unopened books whose titles ascend, which is the shape `BookSummaries.order` really
   * produces for a shelf nobody has started: grouped first, and the never-opened group sorted by
   * `title.lowercase()`.
   *
   * `positionMs = 0` on every one of them is load-bearing rather than tidy. A started book would
   * be lifted into the Continue group, out of the block the bar indexes, and the assertions below
   * would then be about a list of a different length than the one they name.
   */
  private fun alphabeticalShelf(count: Int): List<BookSummary> = List(count) { index ->
    bookSummary(
      bookId = "book-$index",
      title = "${'A' + index} Book",
      author = "Author $index",
      positionMs = 0L,
    )
  }

  private fun isOnScreen(text: String): Boolean =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

  @Test
  fun aShelfLongEnoughToNeedAnIndexGetsOne() {
    show(alphabeticalShelf(SHELF_NEEDING_AN_INDEX))

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).assertExists()
  }

  @Test
  fun aShelfShortEnoughToScrollByHandGetsNoIndex() {
    // The other side of the same rule, and the one that matters for a screen's ordinary state: a
    // strip of letters laid over five books covers content the reader can already see.
    show(alphabeticalShelf(SHELF_NOT_NEEDING_AN_INDEX))

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).assertDoesNotExist()
  }

  /**
   * The jump itself, and the reason the whole feature exists.
   *
   * Asserted as "was off screen, then is on screen" rather than on a scroll offset, because the
   * offset is arithmetic `FastScrollIndexTest` already holds and this is the part that arithmetic
   * cannot see: the header row above the shelf, the bar's measured height, and a pointer position
   * turning into all three.
   */
  @Test
  fun draggingToTheBottomOfTheIndexBringsTheEndOfTheShelfIntoView() {
    show(alphabeticalShelf(SHELF_NEEDING_AN_INDEX))
    val last = "${'A' + SHELF_NEEDING_AN_INDEX - 1} Book"
    // Non-vacuity: a shelf short enough to fit on screen would satisfy the assertion below without
    // scrolling anywhere, and this test would then pass against a bar wired to nothing.
    assertThat(isOnScreen(last)).isFalse

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput {
      down(bottomCenter)
      moveTo(bottomCenter)
      up()
    }

    composeRule.waitUntil { isOnScreen(last) }
  }

  /**
   * A drag, not a series of taps — and a drag that crosses buckets has to keep answering.
   *
   * Down at the top and up at the bottom is one gesture and the shelf must end at the end. Twenty-
   * seven individually clickable letters would fail this while passing the test above, which is the
   * defect this bar's single pointer region exists to make impossible.
   */
  @Test
  fun aDragDownTheIndexFollowsTheFingerRatherThanStoppingWhereItStarted() {
    show(alphabeticalShelf(SHELF_NEEDING_AN_INDEX))
    val last = "${'A' + SHELF_NEEDING_AN_INDEX - 1} Book"

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput {
      down(topCenter)
      moveTo(centerLeft)
      moveTo(bottomCenter)
      up()
    }

    composeRule.waitUntil { isOnScreen(last) }
  }

  /**
   * The letters are not in the semantics tree, which is what keeps every journey in this repository
   * working. `:app`'s journeys and `StoreScreenshotsTest` find rows by their visible text, and a
   * screen carrying a bare `A` … `Z` is a screen where a single-letter or `substring` finder starts
   * matching furniture. It is also what a screen reader would otherwise read out in front of every
   * row of a list it cannot operate.
   */
  @Test
  fun theIndexPutsNoTextOnTheScreenForATextFinderToTripOver() {
    show(alphabeticalShelf(SHELF_NEEDING_AN_INDEX))

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).assertExists()
    assertThat(isOnScreen("A")).isFalse
    assertThat(isOnScreen("B")).isFalse
  }

  private companion object {
    /** Comfortably over `FAST_SCROLL_MIN_ITEMS`, and inside the 26 distinct letters available. */
    const val SHELF_NEEDING_AN_INDEX = 25
    const val SHELF_NOT_NEEDING_AN_INDEX = 5
  }
}
