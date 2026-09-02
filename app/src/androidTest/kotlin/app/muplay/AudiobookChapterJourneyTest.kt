package app.muplay

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.media.PlaybackConnection
import app.muplay.testing.BookFixtures
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chapters, on a real screen, in a real single-file `.m4b` read over HTTP from a real Navidrome.
 *
 * This is the half of the audiobook feature that a music player does not have, and the half that
 * nothing in `:app` had ever driven: the chapter list on a book's own screen, and the two transport
 * controls that mean *chapter* rather than *track*.
 *
 * ### Every observation here is taken with playback **paused**, and that is the whole design
 *
 * `Second Book` is 21 seconds long and its chapters start at 0 / 4 000 / 9 000 / 15 000 ms, so a
 * test that *waited* for the position to reach a chapter boundary would be satisfied by playback
 * simply arriving there — the exact defect `CLAUDE.md` records under "Five-second fixtures let time
 * pass a test that its own defect should fail". `BookPlayerViewModel.seekToChapter` reads the
 * controller's live position and issues a `seekTo`; it never calls `play()`. So the book is paused
 * first, and every chapter jump below is then a position a stopped player cannot reach by itself.
 *
 * The one place that cannot be done is [tappingAChapterOnTheBooksOwnScreenJumpsIntoIt], because
 * `BookViewModel.playChapter` deliberately *does* start playing. That assertion is bounded by a
 * deadline instead: from where the book is left, real time needs more than
 * [CHAPTER_TAP_TIMEOUT_MILLIS] to carry playback into the target chapter, so arriving inside it in
 * less than that is the seek and nothing else.
 *
 * Chapter titles and boundaries are read from `BookFixtures`, which parses the ffprobe-derived
 * table `ci/probe-chapters.sh` generates and `--check` re-derives in both CI tiers. Derived, never
 * written down: a hardcoded corpus fact is what turned this repository's whole device tier red the
 * day a fourth music fixture landed.
 *
 * Method and class names are camelCase: `minSdk 26` compiles DEX 035, which forbids a space in any
 * `SimpleName`.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookChapterJourneyTest {

  @get:Rule(order = 0)
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  @get:Rule(order = 1)
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private var connection: PlaybackConnection? = null

  /** Leaves nothing playing — no `lateinit` is read, so this cannot replace a real failure. */
  @After
  fun tearDown() {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }
    connection = null
    val controller = runBlocking { open.controller() }
    onMain {
      controller.stop()
      controller.clearMediaItems()
      open.release()
    }
  }

  /**
   * The chapters of a book, listed by name on its own screen, and one of them tapped.
   *
   * Four names rather than "a chapter list is displayed": a screen showing one chapter, or the
   * *previous* book's chapters, passes the second and fails this. They are read out of the file's
   * own `moov` atom over HTTP by `ChapterReader`, so this is the differentiator working end to end
   * — no chapter title in this assertion exists anywhere in the app's own sources or in its mirror.
   */
  @Test
  fun tappingAChapterOnTheBooksOwnScreenJumpsIntoIt() {
    val controller = connectController()
    composeRule.reachLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(BOOK_TITLE)

    composeRule.waitUntil("the chapters to be read out of the file", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(CHAPTER_ROWS.last()).fetchSemanticsNodes().isNotEmpty()
    }
    CHAPTER_ROWS.forEach { row ->
      scrollTo(row)
      composeRule.onNodeWithText(row).assertIsDisplayed()
    }

    // From the top, so the position this test then asserts on cannot be one an earlier test stored.
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    awaitCurrentItemTitled(controller, BOOK_TITLE)
    awaitOnMain("the book to start decoding") { controller.currentPosition > STARTED_MS }
    composeRule.pausePlayback()
    awaitOnMain("playback to stop") { !controller.isPlaying }
    val restingAt = onMain { controller.currentPosition }
    assertThat(restingAt)
      .describedAs("the book must be left in its first chapter for the jump to be a jump")
      .isLessThan(TARGET_CHAPTER.startMs)

    // Back to the book's own screen and into the third chapter.
    Espresso.pressBack()
    composeRule.waitUntil("the book screen again", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(START_OVER_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    scrollTo(TARGET_CHAPTER_ROW)
    composeRule.onNodeWithText(TARGET_CHAPTER_ROW).performClick()

    // Bounded, and the bound is the assertion: from ${restingAt}ms, real audio needs more than
    // ${CHAPTER_TAP_TIMEOUT_MILLIS}ms to reach ${TARGET_CHAPTER.startMs}ms, so arriving inside this
    // window is the seek. `BookViewModel.playChapter` is a `playFile` *and* a `seekTo`, and for a
    // single-file book the queue never changes — so without the seek nothing moves at all.
    awaitOnMain(
      "the third chapter to be reached",
      timeoutMs = CHAPTER_TAP_TIMEOUT_MILLIS,
    ) { controller.currentPosition >= TARGET_CHAPTER.startMs }
    assertThat(onMain { controller.currentPosition })
      .describedAs("inside \"${TARGET_CHAPTER.title}\", not past it")
      .isLessThan(TARGET_CHAPTER.endMs)
    assertThat(onMain { controller.currentMediaItemIndex })
      .describedAs("one file, so a chapter is a position in it and never a queue index")
      .isZero

    // And it is really playing from there.
    val landedAt = onMain { controller.currentPosition }
    awaitOnMain("audio to advance from the chapter it jumped to") {
      controller.currentPosition > landedAt + ADVANCED_MS
    }
  }

  /**
   * Next and previous mean **chapter**, and for a single-file book that is a seek inside one item.
   *
   * `seekToNextMediaItem` would end the book on the first tap — which is exactly what a music
   * player does when it meets an audiobook, and it is invisible to any test that only checks that
   * "the position changed".
   *
   * Both directions, and `previous` twice over: `BookTimeline.previous` restarts the *current*
   * chapter when the listener is deep inside it and only goes back a chapter near its start, so a
   * single tap cannot tell the two rules apart. Here the tap lands exactly on a boundary, which is
   * the "near its start" arm.
   */
  @Test
  fun nextAndPreviousChapterSeekInsideTheBookRatherThanChangingTrack() {
    val controller = connectController()
    composeRule.reachLibraryScreen()
    composeRule.openBookshelf()
    composeRule.openBookNamed(BOOK_TITLE)
    composeRule.onNodeWithText(START_OVER_LABEL).performClick()
    awaitCurrentItemTitled(controller, BOOK_TITLE)

    // The audiobook player, which is a different instrument from the music one: five transport
    // controls, a speed control and a sleep timer, none of which `PlayerScreen` has.
    composeRule.waitUntil("the audiobook player's own transport", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(NEXT_CHAPTER_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithContentDescription(PREVIOUS_CHAPTER_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).assertIsDisplayed()

    awaitOnMain("the book to start decoding") { controller.currentPosition > STARTED_MS }
    composeRule.pausePlayback()
    awaitOnMain("playback to stop") { !controller.isPlaying }
    assertThat(onMain { controller.currentPosition })
      .describedAs("paused inside the first chapter")
      .isLessThan(SECOND_CHAPTER.startMs)

    // Every jump below happens on a *stopped* player, so none of them is something real time could
    // have produced.
    composeRule.onNodeWithContentDescription(NEXT_CHAPTER_LABEL).performClick()
    awaitPositionNear(controller, SECOND_CHAPTER.startMs, "the second chapter's start")
    assertThat(onMain { controller.currentMediaItemIndex })
      .describedAs("\"next chapter\" must be a seek inside one file, never a queue move")
      .isZero

    composeRule.onNodeWithContentDescription(NEXT_CHAPTER_LABEL).performClick()
    awaitPositionNear(controller, THIRD_CHAPTER.startMs, "the third chapter's start")

    composeRule.onNodeWithContentDescription(PREVIOUS_CHAPTER_LABEL).performClick()
    awaitPositionNear(controller, SECOND_CHAPTER.startMs, "the second chapter's start again")
    assertThat(onMain { controller.currentMediaItemIndex }).isZero

    // The book still plays from where the chapter buttons left it — a player that seeked and then
    // failed to decode satisfies every assertion above.
    composeRule.onNodeWithContentDescription(PLAY_LABEL).performClick()
    awaitOnMain("audio to advance from the chapter that was seeked to") {
      controller.currentPosition > SECOND_CHAPTER.startMs + ADVANCED_MS
    }
  }

  // ---- plumbing ---------------------------------------------------------------------------------

  /**
   * Scrolls the book screen's `LazyColumn` to [text].
   *
   * `performScrollToNode` rather than `performScrollTo`: a lazy list does not compose a row that is
   * off screen at all, so there is no node for the simpler call to scroll to and the failure reads
   * as "the chapter is not there".
   */
  private fun scrollTo(text: String) {
    composeRule.onAllNodes(hasScrollAction())[FIRST_SCROLLABLE]
      .performScrollToNode(hasText(text))
  }

  /**
   * The same, for a control named by its `contentDescription` -- the book player's transport,
   * since the design pass made those icons. A separate helper rather than a widened one, because
   * `performScrollToNode(hasText(..))` for a control that carries no text fails at the scroll and
   * every assertion after it would then be about a node that was never composed.
   */
  private fun scrollToControl(description: String) {
    composeRule.onAllNodes(hasScrollAction())[FIRST_SCROLLABLE]
      .performScrollToNode(hasContentDescription(description))
  }

  /**
   * Waits for the position to land within [SEEK_TOLERANCE_MILLIS] of [targetMs] and stops there.
   *
   * A window rather than an equality: a seek inside a stream the server is transcoding is served by
   * re-issuing the URI with a whole-second time offset (`MuPlayer`'s `ReissueWithOffset`), so the
   * position that comes back can be rounded down to the second. The window is far narrower than the
   * four-second gap between this book's chapters, so it still names one chapter and only one.
   */
  private fun awaitPositionNear(controller: MediaController, targetMs: Long, what: String) {
    val deadline = System.currentTimeMillis() + SEEK_TIMEOUT_MILLIS
    var last = -1L
    while (System.currentTimeMillis() < deadline) {
      last = onMain { controller.currentPosition }
      if (kotlin.math.abs(last - targetMs) <= SEEK_TOLERANCE_MILLIS) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("$what: expected about ${targetMs}ms, last saw ${last}ms")
  }

  private fun awaitCurrentItemTitled(controller: MediaController, title: String): String {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
      val item = onMain { controller.currentMediaItem }
      last = item?.mediaMetadata?.title?.toString()
      if (item != null && last == title) return item.mediaId
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("the session never made \"$title\" current; last saw \"$last\"")
  }

  private fun connectController(): MediaController {
    val open = connection ?: onMain { PlaybackConnection(context, appArtworkUrls()) }.also { connection = it }
    // From the test thread: `controller()` hops to the main Looper itself, and a `runBlocking`
    // there would block the very Looper it is waiting on.
    return runBlocking { open.controller() }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun awaitOnMain(
    description: String,
    timeoutMs: Long = TIMEOUT_MILLIS,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (onMain(condition)) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError("timed out waiting for $description")
  }

  private companion object {
    /** Derived from `ci/fixtures/books.tsv`, never written down. */
    val BOOK = BookFixtures.SECOND_BOOK
    val BOOK_TITLE: String = BOOK.albumName

    /**
     * The rows the screen renders, one per chapter atom in the file.
     *
     * `"${index + 1}. ${title}"` is `chapterRowLabel`'s shape, typed out here rather than imported
     * from `:feature:book`: a journey is a black-box walk through what a user sees, and a shared
     * formatter would let a wording change pass unnoticed. The titles themselves are the file's.
     */
    val CHAPTER_ROWS: List<String> =
      BOOK.chapters.mapIndexed { index, chapter -> "${index + 1}. ${chapter.title}" }

    val SECOND_CHAPTER = BOOK.chapters[1]
    val THIRD_CHAPTER = BOOK.chapters[2]

    /** The one [tappingAChapterOnTheBooksOwnScreenJumpsIntoIt] taps — deliberately not the first. */
    val TARGET_CHAPTER = THIRD_CHAPTER
    val TARGET_CHAPTER_ROW: String = CHAPTER_ROWS[2]

    // The literal strings the real screens render. Duplicated from `BookLabels.kt` on purpose.
    const val START_OVER_LABEL = "Start from the beginning"
    const val NEXT_CHAPTER_LABEL = "Next chapter"
    const val PREVIOUS_CHAPTER_LABEL = "Previous chapter"
    const val SLEEP_TIMER_LABEL = "Sleep timer"
    const val PLAY_LABEL = "Play"

    /** Enough decoded audio to be sure the player really started before it is stopped again. */
    const val STARTED_MS = 300L

    /** Enough to tell a player that resumed from one that is parked on the right number. */
    const val ADVANCED_MS = 1_000L

    /**
     * The deadline that turns "it reached the chapter" into "it *jumped* to the chapter".
     *
     * The book is left inside its first chapter, under 4 000 ms, and the target starts at 9 000 ms.
     * Four seconds of real audio cannot cover that, so this bound is what the assertion rests on.
     */
    const val CHAPTER_TAP_TIMEOUT_MILLIS = 4_000L

    /** Far narrower than the gap between two of this book's chapters — see `awaitPositionNear`. */
    const val SEEK_TOLERANCE_MILLIS = 1_200L

    const val SEEK_TIMEOUT_MILLIS = 10_000L
    const val TIMEOUT_MILLIS = 30_000L
    const val POLL_MILLIS = 100L

    /** The book screen hosts exactly one `LazyColumn`; the mini player under it does not scroll. */
    const val FIRST_SCROLLABLE = 0
  }
}
