package app.muplay.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * **They have been executed now.** The header used to say they never had -- they were written with
 * the emulator down -- and that is no longer true: the whole module suite ran green on
 * `muplay37`, 45 of 45, on the run that added the three chapter-failure tests below. The claim is
 * corrected rather than deleted because the difference matters to anyone reading an assertion
 * here: these are measurements, not arguments.
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
        onRetryChapters = { actions += "retryChapters" },
        coverArtUrl = NO_COVER,
      )
    }
  }

  private fun content(
    chapters: BookUiState.Chapters = BookUiState.Chapters.Ready(chapters()),
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

  /**
   * The same, for a control whose accessible name is a `contentDescription` rather than its own
   * text -- the two speed steppers, since the design pass made them icons. See
   * `BookPlayerContentTest.scrollToControl` for why this is a second helper and not a widened
   * first one.
   */
  private fun scrollToControl(description: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(description))
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
    show(content(chapters = BookUiState.Chapters.Reading))

    composeRule.onNodeWithText(STARTED_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(RESUME_LABEL).assertIsDisplayed()
    scrollTo(CHAPTERS_LOADING_LABEL)
    composeRule.onNodeWithText(CHAPTERS_LOADING_LABEL).assertIsDisplayed()
    // Not "loading", which over a fully drawn book reads as a stuck screen.
    composeRule.onNodeWithText(LOADING_BOOK_LABEL).assertDoesNotExist()
    // And no retry, because there is nothing to retry yet. `Message`'s own KDoc makes the case:
    // a retry button that does nothing is worse than no button, which is why `onRetry` is
    // nullable rather than defaulted to a no-op.
    composeRule.onNodeWithText(RETRY_CHAPTERS_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(CHAPTERS_UNAVAILABLE_LABEL).assertDoesNotExist()
  }

  /**
   * A chapter read that failed takes the chapter *list* down and nothing else.
   *
   * This is the assertion the whole `BookUiState.Chapters` type exists for, and it is on the
   * device tier because it is the only tier that can see it: no JVM test in this project composes
   * anything, so "the sentence is where the chapters were, and `Resume` is still above it and
   * still reports" is unobservable from `:feature:book:test`.
   *
   * `Resume` is pressed rather than merely looked at. A screen that rendered the button but had
   * stopped wiring it -- which is what an error state at the *screen* level would produce, since
   * the whole `Content` arm would be gone -- passes an `assertIsDisplayed` and fails this.
   */
  @Test
  fun chaptersThatCouldNotBeReadSaySoAndLeaveResumeWorking() {
    show(content(chapters = BookUiState.Chapters.Unavailable))

    // Before scrolling: `Resume` is above the fold, and pressing it is what proves it still works.
    composeRule.onNodeWithText(RESUME_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(RESUME_LABEL).performClick()

    scrollTo(CHAPTERS_UNAVAILABLE_LABEL)
    composeRule.onNodeWithText(CHAPTERS_UNAVAILABLE_LABEL).assertIsDisplayed()
    // Two different sentences for two different states. "Reading chapters..." left up over a read
    // that has already failed is a screen that spins forever.
    composeRule.onNodeWithText(CHAPTERS_LOADING_LABEL).assertDoesNotExist()
    // Not a screen: the book is not "gone" and must not say so.
    composeRule.onNodeWithText(BOOK_NOT_FOUND_LABEL).assertDoesNotExist()
    assertThat(actions).containsExactly("resume")
  }

  /**
   * The retry reports, which is `Message.onRetry`'s first caller in this app.
   *
   * `playedChapters` is asserted empty in the same breath: the retry sits where the chapter rows
   * would be, and a press that had landed on a row instead would still leave the screen looking
   * right.
   */
  @Test
  fun theRetryUnderAFailedChapterReadReportsThePress() {
    show(content(chapters = BookUiState.Chapters.Unavailable))

    scrollTo(RETRY_CHAPTERS_LABEL)
    composeRule.onNodeWithText(RETRY_CHAPTERS_LABEL).performClick()

    assertThat(actions).containsExactly("retryChapters")
    assertThat(playedChapters).isEmpty()
  }

  /**
   * An empty answer is not a failed one, and they render differently.
   *
   * Most audiobook files carry no chapter atoms at all, so `Ready(emptyList())` is the common case
   * -- the read worked and there was nothing in the file. A screen that folded it into
   * `Unavailable` would offer a retry that can only ever find the same nothing.
   */
  @Test
  fun aBookWhoseFilesCarryNoChaptersIsNotAFailedRead() {
    show(content(chapters = BookUiState.Chapters.Ready(emptyList())))

    composeRule.onNodeWithText(CHAPTERS_HEADING).assertExists()
    composeRule.onNodeWithText(CHAPTERS_UNAVAILABLE_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(RETRY_CHAPTERS_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(CHAPTERS_LOADING_LABEL).assertDoesNotExist()
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

    scrollToControl(FASTER_LABEL)
    composeRule.onNodeWithContentDescription(FASTER_LABEL).performClick()
    composeRule.onNodeWithContentDescription(SLOWER_LABEL).performClick()

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

  @Test
  fun noTwoChapterRowsFightOverTheSamePixels() {
    // The chapter list is the crowded case in this app: `BookScreen` gives the `LazyColumn` no
    // `verticalArrangement` on purpose, so chapter rows sit directly on each other and their own
    // height is the only thing separating them. Zero gap means a short row has nowhere to expand
    // into that is not its neighbour.
    show(content())

    composeRule.assertEveryTapTargetIsBigEnough()
  }

}
