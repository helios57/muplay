package app.muplay.book

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.media.BookChapter
import app.muplay.media.SleepTimerController
import app.muplay.model.BookSettings
import app.muplay.model.SleepTimerState
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audiobook player, composed for real on a device against a [BookPlayerUiState] built by hand.
 *
 * No Hilt graph, no media session, no sleep-timer controller: [BookPlayerContent] is the stateless
 * half. `BookPlayerUiStateTest` already gates what the state *is*; what this suite gates is what
 * the screen does with it -- which control sends which value, and in which direction.
 *
 * **These tests have never been executed.** They were written with the emulator unavailable. Every
 * assertion below is an argument about the code, not a measurement of it; see the `:feature:book`
 * entry in the root `coverageFloors` table for the list of what has to be run before any number is
 * written down anywhere.
 *
 * camelCase method names: D8 refuses a space in any `SimpleName` at DEX 035.
 */
@RunWith(AndroidJUnit4::class)
class BookPlayerContentTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()
  private val nudges = mutableListOf<Long>()
  private val speeds = mutableListOf<Float>()
  private val seeked = mutableListOf<BookChapter>()
  private val presets = mutableListOf<Long>()

  private fun show(state: BookPlayerUiState) {
    composeRule.setContent {
      BookPlayerContent(
        state = state,
        onPlayPause = { actions += "playPause" },
        onPreviousChapter = { actions += "previous" },
        onNextChapter = { actions += "next" },
        onNudge = { nudges += it },
        onSpeed = { speeds += it },
        onChapter = { seeked += it },
        onSleepPreset = { presets += it },
        onEndOfChapter = { actions += "endOfChapter" },
        onCancelTimer = { actions += "cancelTimer" },
        coverArtUrl = NO_COVER,
      )
    }
  }

  /**
   * Playing the second of three chapters, ninety seconds in.
   *
   * Ninety seconds of a two-minute chapter is a progress of exactly **0.75**, and the fixture's
   * book position is one hour of four, which is **0.25**. The two are deliberately different: at
   * thirty seconds they would both have been 0.25, and a bar wired to the wrong pair of numbers
   * would have passed.
   */
  private fun content(
    chapterDurationMs: Long = FIRST_CHAPTER_MS,
    chapterCount: Int = 3,
    isPlaying: Boolean = true,
    speed: Float = 1.5f,
    sleepTimer: SleepTimerState = SleepTimerState.Off,
    coverArtId: String? = "cover-1",
    chapters: List<BookChapter> = chapters(),
  ) = BookPlayerUiState.Content(
    bookTitle = STARTED_TITLE,
    author = STARTED_AUTHOR,
    coverArtId = coverArtId,
    chapterTitle = SECOND_CHAPTER_TITLE,
    chapterNumber = 2,
    chapterCount = chapterCount,
    positionInChapterMs = POSITION_IN_CHAPTER_MS,
    chapterDurationMs = chapterDurationMs,
    bookPositionMs = STARTED_POSITION_MS,
    bookDurationMs = BOOK_DURATION_MS,
    bookRemainingMs = BOOK_DURATION_MS - STARTED_POSITION_MS,
    isPlaying = isPlaying,
    speed = speed,
    skipSilence = false,
    sleepTimer = sleepTimer,
    chapters = chapters,
  )

  /**
   * Brings [text] into the composition before asserting on it.
   *
   * The player is a `LazyColumn` under a 200dp cover, so most of its controls start below the fold
   * on a phone and are not composed at all. That matters twice: an assertion about an uncomposed
   * node fails with "no node found", and an `assertDoesNotExist` about one *passes* for a reason
   * that has nothing to do with the code. Every absence assertion in this file is therefore made
   * either on a state that renders one node in total, or after scrolling to the very item that
   * would have contained the missing node -- and the sleep-timer presets live inside the same
   * `item { }` as the button that opens them, so composing one composes the other.
   */
  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
  }

  private fun topOf(text: String): Float =
    composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

  private fun progress(): Float = composeRule
    .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    .fetchSemanticsNode()
    .config[SemanticsProperties.ProgressBarRangeInfo]
    .current

  private fun countOf(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  // ---- nothing playing -------------------------------------------------------------------------

  /**
   * The one state that renders a single node, which is what makes every absence below sound: there
   * is no lazy list here to hide a control in.
   */
  @Test
  fun nothingPlayingSaysSoAndOffersNoTransportAtAll() {
    show(BookPlayerUiState.NothingPlaying)

    composeRule.onNodeWithText(NOTHING_PLAYING_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(PLAY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(PAUSE_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(NEXT_CHAPTER_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(PREVIOUS_CHAPTER_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(BACK_30_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(FORWARD_30_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(BOOK_COVER_LABEL).assertDoesNotExist()
  }

  // ---- what is on screen -----------------------------------------------------------------------

  /**
   * The book's title and the chapter's title each in their own place. Two strings and no
   * positional assertion would let a screen that printed the chapter twice pass.
   */
  @Test
  fun theBookTitleAndTheChapterTitleEachRenderInTheirOwnPlace() {
    show(content())

    assertThat(topOf(STARTED_TITLE)).isLessThan(topOf(SECOND_CHAPTER_TITLE))
  }

  /**
   * The cover carries an accessible name here and not on the shelf.
   *
   * That asymmetry is deliberate and documented in `BookCover`: a shelf row already renders the
   * title as text beside the image, so a described cover would make an
   * `onNodeWithContentDescription` in a journey ambiguous and read the book out twice. The player
   * has one cover and it is the screen's subject.
   */
  @Test
  fun theCoverIsNamedForAScreenReaderOnThePlayer() {
    show(content(coverArtId = "cover-1"))

    composeRule.onNodeWithContentDescription(BOOK_COVER_LABEL).assertExists()
  }

  /** A book with no `coverArt` id gets a neutral placeholder, which names nothing. */
  @Test
  fun aBookWithNoCoverArtGetsAPlaceholderRatherThanADescribedImage() {
    show(content(coverArtId = null))

    composeRule.onNodeWithContentDescription(BOOK_COVER_LABEL).assertDoesNotExist()
    // The rest of the screen is unaffected -- a missing cover is not a missing player.
    composeRule.onNodeWithText(STARTED_TITLE).assertIsDisplayed()
  }

  @Test
  fun theChapterCounterReadsOneBasedAndNamesHowManyThereAre() {
    show(content())

    composeRule.onNodeWithText("Chapter 2 of 3").assertIsDisplayed()
  }

  /**
   * "Chapter 0 of 0" on a book whose extraction has not returned is worse than saying nothing, so
   * the whole line is absent rather than zeroed. The counter is the third item in the list, so it
   * is composed whenever it exists and this absence is not a scrolling artefact.
   */
  @Test
  fun theChapterCounterIsAbsentUntilTheChaptersAreKnown() {
    show(content(chapterCount = 0, chapters = emptyList()))

    assertThat(countOf("Chapter 0 of 0")).isZero()
    assertThat(countOf("Chapter 2 of 0")).isZero()
    // ...and the transport is still there, because a book whose chapters have not arrived is still
    // playing and still has to be pausable.
    scrollTo(PAUSE_LABEL)
    composeRule.onNodeWithText(PAUSE_LABEL).assertIsDisplayed()
  }

  @Test
  fun theClockReadsThePositionWithinTheChapterOverTheChaptersOwnLength() {
    show(content())

    val expected = "${formatClock(POSITION_IN_CHAPTER_MS)} / ${formatClock(FIRST_CHAPTER_MS)}"
    scrollTo(expected)
    composeRule.onNodeWithText(expected).assertIsDisplayed()
    // The book's remaining time is a different sentence in a different place, not the same number
    // twice.
    composeRule.onNodeWithText(formatRemaining(BOOK_DURATION_MS - STARTED_POSITION_MS))
      .assertIsDisplayed()
  }

  /**
   * 90 s of a 120 s chapter is 0.75; the same player's book position is one hour of four, which is
   * 0.25. So a bar wired to the book rather than to the chapter reads a different number here
   * rather than coincidentally the same one.
   */
  @Test
  fun theProgressBarReadsThePositionWithinTheChapterRatherThanWithinTheBook() {
    show(content(chapterDurationMs = FIRST_CHAPTER_MS))

    scrollTo("${formatClock(POSITION_IN_CHAPTER_MS)} / ${formatClock(FIRST_CHAPTER_MS)}")

    assertThat(progress())
      .isEqualTo(POSITION_IN_CHAPTER_MS.toFloat() / FIRST_CHAPTER_MS)
  }

  /**
   * A chapter whose length is not known yet arrives as `0`, and `x / 0f` is `Infinity` -- which a
   * progress bar renders as **full** for a book that has barely started. The guard in
   * `BookPlayerContent` folds that to zero, and this is the only tier that can see it.
   */
  @Test
  fun aChapterOfUnknownLengthLeavesTheProgressBarEmptyRatherThanFull() {
    show(content(chapterDurationMs = 0L))

    scrollTo("${formatClock(POSITION_IN_CHAPTER_MS)} / ${formatClock(0L)}")

    assertThat(progress()).isEqualTo(0f)
  }

  // ---- the controls ----------------------------------------------------------------------------

  @Test
  fun aPlayingBookOffersPauseRatherThanPlay() {
    show(content(isPlaying = true))

    scrollTo(PAUSE_LABEL)
    composeRule.onNodeWithText(PAUSE_LABEL).assertIsDisplayed()
    assertThat(countOf(PLAY_LABEL)).isZero()

    composeRule.onNodeWithText(PAUSE_LABEL).performClick()
    assertThat(actions).containsExactly("playPause")
  }

  @Test
  fun aPausedBookOffersPlayRatherThanPause() {
    show(content(isPlaying = false))

    scrollTo(PLAY_LABEL)
    composeRule.onNodeWithText(PLAY_LABEL).assertIsDisplayed()
    assertThat(countOf(PAUSE_LABEL)).isZero()
  }

  /**
   * The two nudges move thirty seconds in **opposite** directions, and both are clicked in one
   * composition so a screen that sent the same sign twice fails. A sign swap here is invisible to
   * every other tier and immediately obvious to a listener.
   */
  @Test
  fun theNudgeButtonsMoveThirtySecondsInOppositeDirections() {
    show(content())

    scrollTo(BACK_30_LABEL)
    composeRule.onNodeWithText(BACK_30_LABEL).performClick()
    composeRule.onNodeWithText(FORWARD_30_LABEL).performClick()

    assertThat(nudges).containsExactly(-NUDGE_MS, NUDGE_MS)
  }

  @Test
  fun previousAndNextChapterAreWiredToTwoDifferentActions() {
    show(content())

    scrollTo(PREVIOUS_CHAPTER_LABEL)
    composeRule.onNodeWithText(PREVIOUS_CHAPTER_LABEL).performClick()
    composeRule.onNodeWithText(NEXT_CHAPTER_LABEL).performClick()

    assertThat(actions).containsExactly("previous", "next")
  }

  @Test
  fun fasterAndSlowerStepFromThePlayersOwnSpeedInOppositeDirections() {
    show(content(speed = 1.5f))

    scrollTo(FASTER_LABEL)
    composeRule.onNodeWithText(formatSpeed(1.5f)).assertIsDisplayed()
    composeRule.onNodeWithText(FASTER_LABEL).performClick()
    composeRule.onNodeWithText(SLOWER_LABEL).performClick()

    assertThat(speeds).containsExactly(1.5f + BookSettings.SPEED_STEP, 1.5f - BookSettings.SPEED_STEP)
  }

  /** Tapping a chapter seeks to *that* chapter. The third of three is tapped, not the first. */
  @Test
  fun tappingAChapterSeeksToThatChapterRatherThanTheFirst() {
    show(content())

    val label = chapterRowLabel(chapters()[2])
    scrollTo(label)
    composeRule.onNodeWithText(label).performClick()

    assertThat(seeked).containsExactly(chapters()[2])
  }

  // ---- the sleep timer -------------------------------------------------------------------------

  /**
   * The presets are behind the button rather than always on screen. They live in the same
   * `item { }` as the button, so scrolling the button into view composes them if they exist --
   * which is what makes this absence a real one.
   */
  @Test
  fun theSleepTimerPresetsAreHiddenUntilTheTimerButtonIsPressed() {
    show(content())

    scrollTo(SLEEP_TIMER_LABEL)
    assertThat(countOf(presetLabel(SleepTimerController.PRESETS.first()))).isZero()
    assertThat(countOf(END_OF_CHAPTER_LABEL)).isZero()

    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).performClick()

    // `assertExists`, not `assertIsDisplayed`, and the reason is a property of the production
    // layout rather than of this test: `SleepTimerRow` puts six presets and `End of chapter` in a
    // plain `Row` with no horizontal scroll, which is wider than a phone. Whether the last of them
    // is reachable by a finger is a real question this suite deliberately does not answer -- see
    // `choosingEndOfChapterIsADifferentRequestFromAnyDuration` below.
    composeRule.onNodeWithText(presetLabel(SleepTimerController.PRESETS.first())).assertExists()
    composeRule.onNodeWithText(END_OF_CHAPTER_LABEL).assertExists()
  }

  /**
   * Every preset the **controller** defines is offered, derived from
   * [SleepTimerController.PRESETS] rather than from a list typed out here. A second list is a
   * second answer that drifts, and this project has lost time to exactly that.
   */
  @Test
  fun everyPresetTheControllerDefinesIsOffered() {
    show(content())

    scrollTo(SLEEP_TIMER_LABEL)
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).performClick()

    SleepTimerController.PRESETS.forEach { preset ->
      // Existence, for the layout reason given above.
      composeRule.onNodeWithText(presetLabel(preset)).assertExists()
    }
  }

  /** Choosing a preset asks for *that* many milliseconds, and closes the row behind it. */
  @Test
  fun choosingAPresetAsksForThatDurationAndClosesTheRow() {
    show(content())

    scrollTo(SLEEP_TIMER_LABEL)
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).performClick()
    // The second preset, not the first: a row that wired every button to `PRESETS.first()` passes
    // a one-preset version of this test.
    val chosen = SleepTimerController.PRESETS[1]
    composeRule.onNodeWithText(presetLabel(chosen)).performClick()

    assertThat(presets).containsExactly(chosen)
    assertThat(countOf(presetLabel(chosen))).isZero()
  }

  @Test
  fun choosingEndOfChapterIsADifferentRequestFromAnyDuration() {
    show(content())

    scrollTo(SLEEP_TIMER_LABEL)
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).performClick()
    // **`performSemanticsAction` rather than `performClick`, and this is a compromise worth
    // reading.** `performClick` refuses a node that is not displayed, and `End of chapter` is the
    // seventh control in a non-scrolling `Row` that is wider than a phone -- so a `performClick`
    // here would fail for a layout reason and report it as a wiring failure. What this test is
    // about is the wiring: end-of-chapter is a different request from every duration. Whether the
    // control can be reached by a finger is a separate question, and the answer on this fixture is
    // very likely "no"; that is a defect in `SleepTimerRow`, not in this test, and it needs a
    // device to confirm before anybody changes the layout.
    composeRule.onNodeWithText(END_OF_CHAPTER_LABEL)
      .performSemanticsAction(SemanticsActions.OnClick)

    assertThat(actions).containsExactly("endOfChapter")
    assertThat(presets).isEmpty()
  }

  /**
   * While a timer runs the button **is** the countdown, so how long is left is readable without
   * opening anything -- the whole affordance for somebody half asleep -- and cancelling is offered
   * beside it.
   */
  @Test
  fun aRunningTimerShowsItsCountdownInPlaceOfTheLabelAndOffersToCancelIt() {
    // 2:05, which collides with nothing else this screen renders.
    val remaining = 125_000L
    show(content(sleepTimer = SleepTimerState.Running(remaining, untilEndOfChapter = false, isFading = false)))

    scrollTo(CANCEL_TIMER_LABEL)
    composeRule.onNodeWithText(formatClock(remaining)).assertIsDisplayed()
    assertThat(countOf(SLEEP_TIMER_LABEL)).isZero()

    composeRule.onNodeWithText(CANCEL_TIMER_LABEL).performClick()
    assertThat(actions).containsExactly("cancelTimer")
  }

  @Test
  fun cancellingIsNotOfferedWhileNoTimerIsRunning() {
    show(content(sleepTimer = SleepTimerState.Off))

    scrollTo(SLEEP_TIMER_LABEL)
    assertThat(countOf(CANCEL_TIMER_LABEL)).isZero()
  }

  private companion object {

    /** Ninety seconds in: three quarters of the first chapter, and no other ratio on this screen. */
    const val POSITION_IN_CHAPTER_MS = 90_000L

    /** What `SleepTimerRow` renders for a preset. Derived from the preset, never typed out. */
    fun presetLabel(presetMs: Long): String = "${presetMs / 60_000} min"
  }
}
