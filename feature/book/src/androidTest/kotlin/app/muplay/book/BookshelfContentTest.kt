package app.muplay.book

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audiobook shelf, composed for real on a device against a [BookshelfUiState] built by hand.
 *
 * No Hilt graph, no Room, no server: [BookshelfContent] is the stateless half this module was
 * split into for exactly this, which is what makes this suite hermetic. What it therefore does
 * **not** prove is the hop out of `BookshelfViewModel.uiState` into it, nor the `hiltViewModel()`
 * default argument; only an `:app` journey reaches those.
 *
 * **These tests have never been executed.** They were written while the emulator was unavailable,
 * so every assertion below is an argument about the code rather than a measurement of it. Nothing
 * in this repository has yet observed one of them pass or fail. See the `:feature:book` entry in
 * the root `coverageFloors` table for what has to be run and measured before any of it is
 * believed.
 *
 * camelCase method names, per `CLAUDE.md`: D8 refuses a space in any `SimpleName` at DEX 035,
 * which `minSdk 26` compiles, and the JVM tier's backticked style does not transfer here.
 */
@RunWith(AndroidJUnit4::class)
class BookshelfContentTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val opened = mutableListOf<String>()
  private val resumed = mutableListOf<String>()

  private fun show(state: BookshelfUiState) {
    composeRule.setContent {
      BookshelfContent(
        state = state,
        onBookClick = { opened += it },
        onResume = { resumed += it },
        coverArtUrl = NO_COVER,
      )
    }
  }

  private fun topOf(text: String): Float =
    composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

  private fun countOf(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  /** How many determinate progress bars the shelf drew, whatever their values. */
  private fun progressBarCount(): Int = composeRule
    .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    .fetchSemanticsNodes()
    .size

  @Test
  fun aShelfThatHasNotLoadedSaysSoRatherThanClaimingThereAreNoBooks() {
    show(BookshelfUiState.Loading)

    composeRule.onNodeWithText(LOADING_BOOKS_LABEL).assertIsDisplayed()
    // The half that matters. Collapsing "not loaded" into "empty" tells a listener their library
    // is empty every time the app is cold, which is the distinction `bookshelfUiState` exists for.
    composeRule.onNodeWithText(NO_BOOKS_LABEL).assertDoesNotExist()
  }

  @Test
  fun anEmptyShelfNamesTheThingThatWouldFixItRatherThanShowingNothing() {
    show(BookshelfUiState.Empty)

    composeRule.onNodeWithText(NO_BOOKS_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(LOADING_BOOKS_LABEL).assertDoesNotExist()
  }

  /**
   * The whole point of the shelf, read off the composition's own geometry rather than off the
   * list: `BookshelfUiStateTest` already asserts what `continueListening` and `rest` return, and
   * what could still be wrong *here* is the screen iterating the wrong one under a heading, or
   * emitting the two groups in the other order.
   */
  @Test
  fun aStartedBookSitsUnderContinueListeningAndAnUnopenedOneUnderTheShelfHeading() {
    show(BookshelfUiState.Content(listOf(startedBook(), unstartedBook())))

    val continueY = topOf(CONTINUE_LISTENING_LABEL)
    val startedY = topOf(STARTED_TITLE)
    val shelfY = topOf(BOOKSHELF_TITLE)
    val unstartedY = topOf(UNSTARTED_TITLE)

    assertThat(continueY).isLessThan(startedY)
    assertThat(startedY).isLessThan(shelfY)
    assertThat(shelfY).isLessThan(unstartedY)
  }

  @Test
  fun theContinueListeningHeadingIsAbsentWhenNothingHasBeenStarted() {
    show(BookshelfUiState.Content(listOf(unstartedBook())))

    // A heading over an empty list is a heading for nothing, which is what the two `isNotEmpty`
    // guards in `BookshelfContent` exist to avoid.
    composeRule.onNodeWithText(CONTINUE_LISTENING_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(BOOKSHELF_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(UNSTARTED_TITLE).assertIsDisplayed()
  }

  @Test
  fun theShelfHeadingIsAbsentWhenEveryBookIsPartWayThrough() {
    show(BookshelfUiState.Content(listOf(startedBook())))

    composeRule.onNodeWithText(BOOKSHELF_TITLE).assertDoesNotExist()
    composeRule.onNodeWithText(CONTINUE_LISTENING_LABEL).assertIsDisplayed()
  }

  /**
   * A book heard to the end keeps its position, so it is `hasStarted` and not
   * `continueListening`. The shelf must still show it -- it is still in the library -- under the
   * other heading.
   */
  @Test
  fun aFinishedBookIsOnTheShelfButNotInTheContinueListeningGroup() {
    val finished = bookSummary(
      bookId = "book-finished",
      title = "A Finished Book",
      author = "Finished Author",
      positionMs = BOOK_DURATION_MS,
      isFinished = true,
    )
    show(BookshelfUiState.Content(listOf(finished)))

    composeRule.onNodeWithText("A Finished Book").assertIsDisplayed()
    composeRule.onNodeWithText(BOOKSHELF_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(CONTINUE_LISTENING_LABEL).assertDoesNotExist()
  }

  /**
   * Progress, the time left and the resume button are the three things that make a row a
   * *resumable* row, and all three are conditional on the same flag. A shelf that drew them on
   * every book would be a wall of identical zero-length bars.
   */
  @Test
  fun onlyAStartedBookCarriesProgressATimeLeftAndAResumeButton() {
    show(BookshelfUiState.Content(listOf(startedBook(), unstartedBook())))

    composeRule.onNodeWithText(formatRemaining(BOOK_DURATION_MS - STARTED_POSITION_MS))
      .assertIsDisplayed()
    assertThat(countOf(RESUME_LABEL)).isEqualTo(1)
    assertThat(progressBarCount()).isEqualTo(1)
    // And the unopened book is on screen, so the counts above are one-of-two rather than
    // one-of-one with the second row never composed.
    composeRule.onNodeWithText(UNSTARTED_TITLE).assertIsDisplayed()
  }

  /**
   * Which book a Resume button resumes, with **two** started books on screen.
   *
   * A one-book version of this passes just as happily when every row's button is wired to the
   * first book, which is the defect worth catching. `onAllNodesWithText` walks the tree in layout
   * order; the y comparison pins that rather than assuming it.
   */
  @Test
  fun tappingResumeNamesTheBookOnThatRowRatherThanTheFirstOne() {
    show(BookshelfUiState.Content(listOf(startedBook(), secondStartedBook())))

    val buttons = composeRule.onAllNodesWithText(RESUME_LABEL)
    assertThat(buttons.fetchSemanticsNodes()).hasSize(2)
    assertThat(buttons[0].fetchSemanticsNode().positionInRoot.y)
      .isLessThan(buttons[1].fetchSemanticsNode().positionInRoot.y)

    buttons[1].performClick()

    assertThat(resumed).containsExactly(SECOND_STARTED_BOOK_ID)
    // Resuming is not opening: the row's own click opens the book screen, and a button that did
    // both would navigate twice.
    assertThat(opened).isEmpty()
  }

  /** The row itself opens the book, and it opens *its own* book. */
  @Test
  fun tappingARowOpensTheBookThatRowIsFor() {
    show(BookshelfUiState.Content(listOf(startedBook(), secondStartedBook())))

    composeRule.onNodeWithText(SECOND_STARTED_TITLE).performClick()

    assertThat(opened).containsExactly(SECOND_STARTED_BOOK_ID)
    assertThat(resumed).isEmpty()
  }

  /**
   * Title and author each in their own place. With two pairwise-different strings and no
   * positional assertion, a row that swapped them passes every "is it displayed" check.
   *
   * **`useUnmergedTree`, and it is load-bearing rather than a precaution.** The row is
   * `Modifier.clickable`, which merges its descendants' semantics, so in the merged tree the title
   * and the author are two text values on *one* node and both queries would return the same
   * coordinates -- an assertion that could never fail. The unmerged tree is where the two `Text`s
   * are still two nodes.
   */
  @Test
  fun theTitleAndTheAuthorEachRenderInTheirOwnPlaceOnARow() {
    show(BookshelfUiState.Content(listOf(unstartedBook())))

    val titleY = composeRule.onNodeWithText(UNSTARTED_TITLE, useUnmergedTree = true)
      .fetchSemanticsNode().positionInRoot.y
    val authorY = composeRule.onNodeWithText(UNSTARTED_AUTHOR, useUnmergedTree = true)
      .fetchSemanticsNode().positionInRoot.y

    assertThat(titleY).isLessThan(authorY)
  }

  @Test
  fun noTwoBooksOnTheShelfFightOverTheSamePixels() {
    // A started book carries a resume control *inside* its clickable row, which is the nesting the
    // sweep deliberately excludes -- a button in a row is the one overlap that is meant to be
    // there. An unstarted one beside it is the plain case.
    show(BookshelfUiState.Content(listOf(startedBook(), unstartedBook())))

    composeRule.assertEveryTapTargetIsBigEnough()
  }

}
