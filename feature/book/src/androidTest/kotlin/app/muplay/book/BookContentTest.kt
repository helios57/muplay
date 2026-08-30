package app.muplay.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One book's screen, composed for real on a device against a [BookUiState] built by hand.
 *
 * No Hilt graph, no Room, no server: [BookContent] is the stateless half. What this suite cannot
 * prove is the `LaunchedEffect(bookId) { viewModel.load(bookId) }` in the stateful entry point,
 * which is the one line an `:app` journey has to cover.
 *
 * **These tests have never been executed.** They were written with the emulator unavailable; every
 * assertion is an argument, not a measurement. See the `:feature:book` entry in the root
 * `coverageFloors` table.
 *
 * camelCase method names: D8 refuses a space in any `SimpleName` at DEX 035.
 */
@RunWith(AndroidJUnit4::class)
class BookContentTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()
  private val playedChapters = mutableListOf<BookChapter>()
  private val speeds = mutableListOf<Float>()
  private val skipSilence = mutableListOf<Boolean>()

  private fun show(state: BookUiState) {
    composeRule.setContent {
      BookContent(
        state = state,
        onResume = { actions += "resume" },
        onRestart = { actions += "restart" },
        onPlayChapter = { playedChapters += it },
        onSpeed = { speeds += it },
        onSkipSilence = { skipSilence += it },
        coverArtUrl = NO_COVER,
      )
    }
  }

  private fun content(
    chapters: List<BookChapter> = chapters(),
    settings: BookSettings = bookSettings(),
  ) = BookUiState.Content(book = startedBook(), chapters = chapters, settings = settings)

  /**
   * Brings [text] into the composition before asserting on it.
   *
   * The screen is a `LazyColumn`, so a row below the fold is not composed at all and an assertion
   * about it fails with "no node found" -- and, worse, an `assertDoesNotExist` about it *passes*
   * for the same reason. Every assertion below that concerns a chapter row scrolls first; the
   * absence assertions in this file are all about rows near the top, which are always composed.
   */
  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
  }

  private fun topOf(text: String): Float =
    composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

  @Test
  fun aBookThatHasNotLoadedSaysSoAndOffersNothingToPress() {
    show(BookUiState.Loading)

    composeRule.onNodeWithText(LOADING_BOOK_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(RESUME_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(START_OVER_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(CHAPTERS_HEADING).assertDoesNotExist()
  }

  /**
   * "Gone" and "not loaded yet" render different sentences, which is the whole reason
   * [BookUiState] has three members rather than two.
   */
  @Test
  fun aBookThatIsNoLongerInTheLibrarySaysSoRatherThanSpinning() {
    show(BookUiState.NotFound)

    composeRule.onNodeWithText(BOOK_NOT_FOUND_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(LOADING_BOOK_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(RESUME_LABEL).assertDoesNotExist()
  }

  /**
   * Title, author and time-left each in their own place. Three pairwise-different fixtures with no
   * positional assertion would let every permutation of the three pass.
   */
  @Test
  fun theTitleTheAuthorAndTheTimeLeftEachRenderInTheirOwnPlace() {
    show(content())

    val titleY = topOf(STARTED_TITLE)
    val authorY = topOf(STARTED_AUTHOR)
    val remainingY = topOf(formatRemaining(BOOK_DURATION_MS - STARTED_POSITION_MS))

    assertThat(titleY).isLessThan(authorY)
    assertThat(authorY).isLessThan(remainingY)
  }

  /**
   * Chapter extraction is an HTTP round trip per file, so an empty chapter list is a normal state
   * and the rest of the screen has already drawn by the time it resolves. A screen that waited for
   * it would be blank for a second every time a book was opened.
   */
  @Test
  fun aBookWhoseChaptersHaveNotArrivedStillRendersItselfAndSaysTheyAreComing() {
    show(content(chapters = emptyList()))

    composeRule.onNodeWithText(STARTED_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(RESUME_LABEL).assertIsDisplayed()
    scrollTo(CHAPTERS_LOADING_LABEL)
    composeRule.onNodeWithText(CHAPTERS_LOADING_LABEL).assertIsDisplayed()
    // Not "loading", which over a fully drawn book reads as a stuck screen.
    composeRule.onNodeWithText(LOADING_BOOK_LABEL).assertDoesNotExist()
  }

  /**
   * A chapter row is numbered from one and carries **its own** length.
   *
   * `chapterRowLabel` does the `+ 1`, and the three fixture chapters have three different lengths,
   * so a row that rendered the neighbouring chapter's clock fails here rather than passing.
   */
  @Test
  fun aChapterRowIsNumberedFromOneAndCarriesItsOwnLength() {
    show(content())

    scrollTo(chapterRowLabel(chapters()[1]))
    composeRule.onNodeWithText("2. $SECOND_CHAPTER_TITLE").assertIsDisplayed()
    composeRule.onNodeWithText(formatClock(SECOND_CHAPTER_MS)).assertIsDisplayed()
    // Zero-based numbering is what this label exists to prevent.
    composeRule.onNodeWithText("0. $FIRST_CHAPTER_TITLE").assertDoesNotExist()
  }

  /** The row plays *its* chapter, not the first one. Three rows, and the third is tapped. */
  @Test
  fun tappingAChapterPlaysThatChapterRatherThanTheFirst() {
    show(content())

    val label = chapterRowLabel(chapters()[2])
    scrollTo(label)
    composeRule.onNodeWithText(label).performClick()

    assertThat(playedChapters).containsExactly(chapters()[2])
  }

  /**
   * Resume and start-over are two different buttons wired to two different things. Clicking both
   * in one composition is what makes the assertion value-bearing: a screen that wired both to
   * `onResume` records `["resume", "resume"]`.
   */
  @Test
  fun resumeAndStartOverAreWiredToTwoDifferentActions() {
    show(content())

    composeRule.onNodeWithText(RESUME_LABEL).performClick()
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()

    assertThat(actions).containsExactly("resume", "restart")
  }

  /** The speed shown is the book's own stored speed, to one decimal place with a dot. */
  @Test
  fun theSpeedShownIsTheBooksOwnStoredSetting() {
    show(content(settings = bookSettings(speed = 1.5f)))

    scrollTo(formatSpeed(1.5f))
    composeRule.onNodeWithText(formatSpeed(1.5f)).assertIsDisplayed()
    composeRule.onNodeWithText(formatSpeed(BookSettings.DEFAULT_SPEED)).assertDoesNotExist()
  }

  /**
   * Faster and slower step from **what is displayed**, in opposite directions.
   *
   * Both are clicked in one composition and the two requested values are compared against the
   * fixture's own speed, so a screen that sent a constant, or that sent the same sign twice,
   * fails. The screen deliberately does not clamp -- `setSpeed` owns that -- so the raw sums are
   * what must arrive.
   */
  @Test
  fun fasterAndSlowerStepFromTheDisplayedSpeedInOppositeDirections() {
    show(content(settings = bookSettings(speed = 1.5f)))

    scrollTo(FASTER_LABEL)
    composeRule.onNodeWithText(FASTER_LABEL).performClick()
    composeRule.onNodeWithText(SLOWER_LABEL).performClick()

    assertThat(speeds).containsExactly(1.5f + BookSettings.SPEED_STEP, 1.5f - BookSettings.SPEED_STEP)
  }

  @Test
  fun theSkipSilenceSwitchShowsTheStoredValueAndReportsTheOppositeWhenPressed() {
    show(content(settings = bookSettings(skipSilence = true)))

    scrollTo(SKIP_SILENCE_LABEL)
    composeRule.onNode(isToggleable()).assertIsOn()
    composeRule.onNode(isToggleable()).performClick()

    assertThat(skipSilence).containsExactly(false)
  }

  @Test
  fun theSkipSilenceSwitchIsOffWhenTheStoredValueIsFalse() {
    show(content(settings = bookSettings(skipSilence = false)))

    scrollTo(SKIP_SILENCE_LABEL)
    composeRule.onNode(isToggleable()).assertIsOff()
    composeRule.onNode(isToggleable()).performClick()

    assertThat(skipSilence).containsExactly(true)
  }
}
