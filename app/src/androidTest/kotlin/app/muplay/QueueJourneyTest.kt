package app.muplay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.component.ADD_TO_QUEUE_LABEL
import app.muplay.designsystem.component.PLAY_NEXT_LABEL
import app.muplay.designsystem.component.QUEUE_MENU_LABEL
import app.muplay.player.MOVE_UP_LABEL
import app.muplay.player.QUEUE_ADDED_LABEL
import app.muplay.player.QUEUE_NEXT_LABEL
import app.muplay.player.QUEUE_TITLE
import app.muplay.player.QUEUE_VIEW_LABEL
import app.muplay.player.REMOVE_LABEL
import app.muplay.ui.NAV_TAB_TAG_PREFIX
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: the queue the user asked for -- *"a possibility to enqueue a song instead of playing it
 * directly, so it needs a play-queue (visible, editable and easy to add songs)"* -- driven end to
 * end against the real media session.
 *
 * ### Why this exists at all, which is the interesting half
 *
 * Every piece of the feature had a test and the feature had none. `QueueSnapshotTest` proves the
 * three index guards, `QueueViewModelTest` proves the mapping, `QueueScreenTest` composes the
 * screen against `RecordingQueueControls`, and `queueEditMessage` has a floor of its own. What
 * none of them touches is [app.muplay.media.QueueEditor], which is the class that actually edits
 * the timeline -- measured at **0 of 22 branches and 8 of 41 lines** by `:core:media`'s own
 * coverage notice while every one of those suites was green.
 *
 * That is this repository's recurring shape aimed at a whole feature rather than at one call: the
 * decision is verified at every layer except the one that applies it. A `QueueEditor` whose
 * `enqueue` appended nothing, whose `move` moved the wrong row, or whose `jumpTo` seeked into the
 * item beside the one tapped would have left the entire build green.
 *
 * ### It edits the *player's* timeline, and that is what is asserted
 *
 * There is no queue object in this app; the timeline inside the media session is the queue. So
 * every assertion here reads the rows back off `QueueScreen`, which renders what
 * `PlaybackConnection` publishes from the player itself -- not what the tap said should happen.
 * An edit the controller refused (a stale index, a track that ended mid-tap) therefore shows up as
 * an unchanged list rather than as a green test.
 *
 * ### Nothing about the seeded album is written down here
 *
 * The album's tracks are **read off the album screen** and every expectation below is derived from
 * that list. The first version of this class hardcoded three tracks and their titles, and the
 * corpus has four: the count assertion failed with `1 of 5`, and -- worse -- the *order* assertion
 * had passed, because the fourth fixture is called `Offset Track` and the walk was matching titles
 * beginning `"Track "`. A filter that quietly drops one row makes a queue of five look like the
 * queue of four the test wanted. This file's neighbours record the general rule (CLAUDE.md, "A
 * shared fixture corpus breaks every hardcoded count at once"); this is what it costs when the
 * hardcoded thing is a *shape* rather than a number.
 *
 * The duplicate row is still deliberate: the walk queues a track the album has already queued, so
 * the timeline holds one title twice. That is a legal queue -- `QueueRow.key` exists because a
 * `LazyColumn` throws on a duplicate key -- and it is the only way a reorder is visible at all,
 * since moving one of two *identical* neighbours changes nothing anybody can assert on.
 *
 * ### Falsified against `QueueEditor`, four mutations, all caught
 *
 * Run rather than predicted, one mutation at a time in `core/media`, with the file restored from a
 * byte snapshot by a `trap ... EXIT INT TERM` rather than by git -- CLAUDE.md, "A revert built on
 * `git checkout --` destroys uncommitted work".
 *
 * | mutation in `QueueEditor` | what the run reported |
 * |---|---|
 * | `enqueue` drops `addMediaItems` | the queue never grew past the album |
 * | `moveMediaItem(from, from)` | `expected:<... "[Track 1", "Offset Track]"> but was:<... "[Offset Track", "Track ...` |
 * | `removeMediaItem(0)` | the first row vanished instead of the moved one |
 * | `seekToDefaultPosition(0)` | `[the queue after playing its last row] expected:<"[4] of 4"> but was:<"[1] of 4">` |
 *
 * `moveMediaItem(to, from)` was tried first and proves nothing: for the adjacent swap this walk
 * makes, it is the same call. And the last row of the table only became a red after the pause
 * below was added -- see the comment at that line, which is the whole reason it is there.
 *
 * Preconditions are `FirstRunJourneyTest`'s: a seeded `ci-navidrome-1` on 4533, `adb reverse
 * tcp:4533 tcp:4533`, and an emulator started with minigbm. See `ci/prepare-emulator.sh`.
 */
@RunWith(AndroidJUnit4::class)
class QueueJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  /**
   * Append, confirm, look, reorder, remove, and play a row -- the whole editable queue.
   *
   * One test rather than five because each step is the previous step's precondition: there is
   * nothing to reorder until something has been queued, and a five-class split would pay the
   * sync-and-play walk five times on an emulator this repository has watched die inside ten
   * minutes.
   */
  @Test
  fun aQueuedTrackIsConfirmedThenReorderedRemovedAndPlayedFromTheQueueScreen() {
    val album = playTheAlbumFromTheTopAndPause()

    // ---- Append -------------------------------------------------------------------------------
    // The first row's menu -- the header's control that queues the whole album has a different
    // accessible name (`QUEUE_ALL_MENU_LABEL`), so it is not in this list at all.
    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[FIRST_ROW].performClick()
    composeRule.onNodeWithText(ADD_TO_QUEUE_LABEL).performClick()

    // The confirmation is raised by `QueueEditor.edits` *after* the controller took the edit, so
    // reading it here is an assertion about the timeline and not about the tap.
    awaitText(QUEUE_ADDED_LABEL)
    // And the queue screen is reachable from it -- for many users the only way they find out the
    // screen exists at all.
    composeRule.onNodeWithText(QUEUE_VIEW_LABEL).performClick()
    awaitText(QUEUE_TITLE)

    val appended = album + album.first()
    awaitQueue(appended, "the album with its first track appended")
    // "1 of 5": still playing the first row, and the append did not disturb it. This is the half of
    // "add to queue" the user cannot see, and the reason the control needed a confirmation.
    //
    // Read back as a string rather than asserted with `onNodeWithText(..).assertIsDisplayed()`,
    // which is what this line was: that form can only report that the sentence it was handed is
    // absent, so a queue counting from the wrong row fails with a message naming the number the
    // test wanted and never the number the screen drew. It named `1 of 5` the first time it ran,
    // which is the whole reason this class no longer writes the album's length down.
    assertThat(queueSummary())
      .describedAs("the line under the queue's title after appending one track")
      .isEqualTo("1 of ${appended.size}")

    // ---- Reorder ------------------------------------------------------------------------------
    // The last row's "Move up". Every row carries one, in list order, so the index is the row.
    // Two statements, not `add(size - 1, removeAt(lastIndex))`: Kotlin evaluates arguments left to
    // right, so that one-liner computes the index against the list's length *before* the removal
    // shortens it and appends the row back where it started. It typechecks, it reads like a swap,
    // and it produced a list identical to `appended` -- so the wait below timed out against an
    // expectation the move could never satisfy.
    val reordered = appended.toMutableList().apply {
      val moved = removeAt(lastIndex)
      add(size - 1, moved)
    }
    composeRule.onAllNodesWithContentDescription(MOVE_UP_LABEL)[appended.lastIndex].performClick()
    awaitQueue(reordered, "the queue after moving its last row up one")

    // ---- Remove -------------------------------------------------------------------------------
    // The row that was just moved, which is now one off the end.
    composeRule.onAllNodesWithContentDescription(REMOVE_LABEL)[reordered.lastIndex - 1].performClick()
    awaitQueue(album, "the queue after removing the row that was moved")

    // ---- Play a row ---------------------------------------------------------------------------
    // The row itself, not a control on it: tapping a queued track is how a user skips ahead, and it
    // is the one edit here that changes what is coming out of the speaker.
    queueRows()[album.lastIndex].performClick()
    // **Paused before the count is read, and that is what makes this assertion mean anything.**
    // `jumpTo` seeks *and* resumes, so the queue starts moving the moment the row is tapped. The
    // first version of this step simply waited thirty seconds for "4 of 4" -- and a `jumpTo` that
    // seeked to index 0 instead of to the tapped row passed it, because four five-second fixtures
    // play from the first row to the last in about fifteen. The test waited, playback arrived, and
    // the defect went unnoticed. CLAUDE.md records the same shape ("Five-second fixtures let time
    // pass a test that its own defect should fail"), and the fix there is the fix here: make the
    // observation one playback cannot reach on its own. Pausing costs a second or two, in which
    // the mutated build advances at most one row -- so the number below is the seek's answer and
    // not the transport's.
    composeRule.pausePlayback()
    awaitSummary("${album.size} of ${album.size}", "the queue after playing its last row")
    // Nothing was reordered by playing it -- `jumpTo` names an item, it does not move one.
    assertThat(queueRowTitles())
      .describedAs("the queue after playing its last row")
      .isEqualTo(album)
  }

  /**
   * "Play next" is not "add to queue", and the queue is where the difference is visible.
   *
   * Both controls sit in one menu and both end in `controller.addMediaItems`; the only thing that
   * separates them is the index, which is `playNextIndexIn(currentIndex, itemCount)` for one and
   * absent for the other. A `playNext` that appended would say the right sentence, leave the right
   * count, and be wrong about the only thing it does -- so the last assertion here states, against
   * this corpus, that the two results really are different lists.
   */
  @Test
  fun playNextInsertsAfterTheCurrentTrackRatherThanAtTheEnd() {
    val album = playTheAlbumFromTheTopAndPause()

    // The third row, so an insert after the current item lands somewhere no append could put it.
    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[THIRD_ROW].performClick()
    composeRule.onNodeWithText(PLAY_NEXT_LABEL).performClick()

    awaitText(QUEUE_NEXT_LABEL)
    composeRule.onNodeWithText(QUEUE_VIEW_LABEL).performClick()
    awaitText(QUEUE_TITLE)

    val inserted = album.toMutableList().apply { add(NEXT_ROW, album[THIRD_ROW]) }
    awaitQueue(inserted, "the queue after playing the third track next while the first plays")
    assertThat(inserted)
      .describedAs("an insert and an append are different lists on this album, so this walk means something")
      .isNotEqualTo(album + album[THIRD_ROW])
  }

  /**
   * Reaches the album, plays it from the top, pauses, and comes back to it. Answers its tracks in
   * the order the screen lists them, which is the order the timeline now holds.
   *
   * **Paused before anything is asserted about an index.** Every fixture in the seeded music
   * library is about five seconds long, so a queue left running advances through it while the walk
   * taps a menu -- and both walks turn on which item is *current*: the `1 of n` line, and the index
   * `playNextIndexIn` inserts after. CLAUDE.md records the same trap from the other side ("Five-second
   * fixtures let time pass a test that its own defect should fail"); an assertion that holds only
   * while the track has not ended is the same defect whichever way it fails. `jumpTo` resumes
   * playback later, which is its job.
   *
   * Tapping a track **replaces** the timeline, which is what makes the rest deterministic: whatever
   * an earlier class left playing is gone, and the queue is this album in this order.
   */
  private fun playTheAlbumFromTheTopAndPause(): List<String> {
    composeRule.reachLibraryScreen()
    awaitText(MUSIC_ALBUM)
    composeRule.onNodeWithText(OPEN_LABEL).performClick()
    composeRule.waitUntil(UI_TIMEOUT_MILLIS) { albumTrackTitles().isNotEmpty() }

    val album = albumTrackTitles()
    // Three is what both walks need -- a third row to queue, and a last row that is not it.
    assertThat(album).describedAs("the tracks the seeded album lists").hasSizeGreaterThan(2)

    composeRule.onAllNodes(rowMatcher())[FIRST_ROW].performClick()
    composeRule.pausePlayback()
    Espresso.pressBack()
    composeRule.waitUntil(UI_TIMEOUT_MILLIS) { albumTrackTitles() == album }
    return album
  }

  /**
   * Blocks until the queue is [expected], then says so with the list it actually found.
   *
   * The wait and the assertion read the same helper deliberately: a `waitUntil` on one predicate
   * followed by an assertion on another is how a wait returns early and the failure then names the
   * wrong thing -- a mistake this repository has made often enough to write down.
   */
  private fun awaitQueue(expected: List<String>, describedAs: String) {
    // The timeout is caught and re-raised as the comparison, so a red says which rows are on the
    // screen. A bare `waitUntil` reports only that fifteen seconds passed, which is true of a queue
    // that never changed, a queue that changed into something else, and an expectation that was
    // wrong -- and one of those three is what this walk actually hit.
    try {
      composeRule.waitUntil(UI_TIMEOUT_MILLIS) { queueRowTitles() == expected }
    } catch (timeout: ComposeTimeoutException) {
      assertThat(queueRowTitles()).describedAs(describedAs).isEqualTo(expected)
      throw timeout
    }
    assertThat(queueRowTitles()).describedAs(describedAs).isEqualTo(expected)
  }

  /**
   * The titles of the album screen's track rows, in order.
   *
   * `AlbumScreen`'s `TrackRow` composes the position and then the title into one clickable node, so
   * the merged `Text` is `[number, title]` and the **last** entry is the title. Its sibling queue
   * button carries a `contentDescription` and no text, so it is not in this list.
   */
  private fun albumTrackTitles(): List<String> = rowTexts().map { it.last() }

  /**
   * The titles of the queue screen's rows, in order.
   *
   * `QueueScreen`'s `QueueRowItem` composes the title and then the artist into one clickable node,
   * so the merged `Text` is `[title, artist]` and the **first** entry is the title -- the opposite
   * end from the album screen, because the two screens put their secondary line on opposite sides
   * of the one that names the track.
   */
  private fun queueRowTitles(): List<String> = rowTexts().map { it.first() }

  private fun rowTexts(): List<List<String>> =
    queueRows().fetchSemanticsNodes()
      .map { node -> node.config[SemanticsProperties.Text].map { it.text } }

  /**
   * Every list row on screen: something with a click action that says something.
   *
   * Two things on every screen in this app match that and are not rows, and both are excluded by
   * name rather than by position. The **mini player** is a merging node carrying the playing
   * track's title, which is one of the strings being counted -- see `JourneyNavigation`'s own note
   * on what that bar does to a text match. The **navigation bar's** four tabs are clickable text
   * too, and they carry `NAV_TAB_TAG_PREFIX` test tags precisely so a journey can tell them from
   * content.
   *
   * Tree order is list order for a `LazyColumn`, which is why the same collection is safe to index
   * for a click and to read for the titles.
   */
  private fun queueRows(): SemanticsNodeInteractionCollection =
    composeRule.onAllNodes(hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
      .notTheMiniPlayer()
      .filter(
        SemanticsMatcher("is not a navigation tab") { node ->
          node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(NAV_TAB_TAG_PREFIX) != true
        },
      )

  private fun rowMatcher(): SemanticsMatcher =
    hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

  /** [awaitQueue] for the count line, and for the same reason: a red has to say what it read. */
  private fun awaitSummary(expected: String, describedAs: String) {
    try {
      composeRule.waitUntil(UI_TIMEOUT_MILLIS) { queueSummary() == expected }
    } catch (timeout: ComposeTimeoutException) {
      assertThat(queueSummary()).describedAs(describedAs).isEqualTo(expected)
      throw timeout
    }
    assertThat(queueSummary()).describedAs(describedAs).isEqualTo(expected)
  }

  /**
   * The one line under the queue's title: "2 of 3", or "3 tracks" when nothing in it is playing.
   *
   * `null` when the screen draws neither, which is a real state -- an empty queue says "Nothing is
   * queued yet." and no count at all -- and worth distinguishing from a count that is merely wrong.
   */
  private fun queueSummary(): String? =
    composeRule.onAllNodesWithText(SUMMARY_INFIX, substring = true)
      .notTheMiniPlayer()
      .fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
      .map { it.text }
      .firstOrNull { it.contains(SUMMARY_INFIX) }

  private fun awaitText(text: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodesWithText(text).notTheMiniPlayer().fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    /**
     * The two strings this walk types into the app rather than reads out of it, retyped rather than
     * shared for the reason `AlbumRouteJourneyTest`'s companion states: a black-box journey that
     * imports the screen's own strings stops noticing when the screen changes what it says. The
     * queue and menu *control* names above are imported instead, because those are accessible names
     * three modules agree on and a retyped copy would go on passing after one of them was reworded.
     */
    const val MUSIC_ALBUM = "Test Album"
    const val OPEN_LABEL = "Open"

    /** What `QueueUiState.Content.summary` puts between the position and the count. */
    const val SUMMARY_INFIX = " of "

    const val FIRST_ROW = 0
    const val THIRD_ROW = 2

    /** Where an insert lands while the first row is playing: directly after it. */
    const val NEXT_ROW = 1

    /** A back-stack edit, a snackbar, a Room-backed flow emission. */
    const val UI_TIMEOUT_MILLIS = 15_000L
  }
}
