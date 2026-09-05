package app.muplay.player

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.media.QueueItem
import app.muplay.media.QueueSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue screen, composed for real against a [QueueUiState] built by hand.
 *
 * `QueueUiStateTest` already holds every *decision* this screen renders -- which row is current,
 * which arrows are live -- so nothing here re-asserts those from the state's side. What is only
 * observable on a device is that each of the four controls in a row is wired to **its own row's**
 * index: four one-line lambdas in a `LazyColumn` item are exactly where a copy-paste swap runs,
 * measures covered, and silently edits the wrong track.
 */
@RunWith(AndroidJUnit4::class)
class QueueScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()

  private val threeTracks = QueueSnapshot(
    items = listOf(
      QueueItem(mediaId = "id-1", title = "First Fixture", artist = "Fixture Artist"),
      QueueItem(mediaId = "id-2", title = "Second Fixture", artist = null),
      QueueItem(mediaId = "id-3", title = "Third Fixture", artist = "Fixture Artist"),
    ),
    currentIndex = 1,
  )

  private fun show(snapshot: QueueSnapshot) {
    composeRule.setContent {
      QueueScreen(
        uiState = queueUiState(snapshot),
        onPlay = { actions += "play($it)" },
        onRemove = { actions += "remove($it)" },
        onMoveUp = { actions += "moveUp($it)" },
        onMoveDown = { actions += "moveDown($it)" },
      )
    }
  }

  @Test
  fun anEmptyQueueSaysSoRatherThanDrawingAnEmptyList() {
    show(QueueSnapshot.EMPTY)

    composeRule.onNodeWithText(QUEUE_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(EMPTY_QUEUE_LABEL).assertIsDisplayed()
  }

  @Test
  fun everyQueuedTrackIsListed() {
    show(threeTracks)

    composeRule.onNodeWithText("First Fixture").assertIsDisplayed()
    composeRule.onNodeWithText("Second Fixture").assertIsDisplayed()
    composeRule.onNodeWithText("Third Fixture").assertIsDisplayed()
    composeRule.onNodeWithText(EMPTY_QUEUE_LABEL).assertDoesNotExist()
  }

  /**
   * The rows are drawn in queue order, checked by y coordinate rather than by all three being
   * somewhere on screen -- a list rendered backwards satisfies every "is it displayed" assertion
   * above, and a queue drawn backwards is a queue that plays in the wrong order.
   */
  @Test
  fun theRowsAreDrawnInQueueOrder() {
    show(threeTracks)

    val tops = listOf("First Fixture", "Second Fixture", "Third Fixture").map { text ->
      composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
    }
    assertThat(tops).isSorted()
  }

  @Test
  fun tappingARowPlaysThatRowAndNotAnother() {
    show(threeTracks)

    composeRule.onNodeWithText("Third Fixture").performClick()

    assertThat(actions).containsExactly("play(2)")
  }

  /**
   * The four controls of one row, each proved to carry **its own** index and its own action.
   *
   * Row 1 deliberately: it is the only one with both arrows live, and an off-by-one that reached
   * for its neighbour would still land inside the list rather than throwing.
   */
  @Test
  fun eachRowControlCallsItsOwnActionForItsOwnRow() {
    show(threeTracks)

    composeRule.onAllNodesWithContentDescription(MOVE_UP_LABEL)[1].performClick()
    assertThat(actions).containsExactly("moveUp(1)")

    composeRule.onAllNodesWithContentDescription(MOVE_DOWN_LABEL)[1].performClick()
    assertThat(actions).containsExactly("moveUp(1)", "moveDown(1)")

    composeRule.onAllNodesWithContentDescription(REMOVE_LABEL)[1].performClick()
    assertThat(actions).containsExactly("moveUp(1)", "moveDown(1)", "remove(1)")
  }

  /**
   * Disabled rather than absent at the two ends. A control that disappears at the top of the list
   * shifts the two beside it under a thumb already on its way down.
   */
  @Test
  fun theEndsOfTheQueueOfferADisabledArrowRatherThanNone() {
    show(threeTracks)

    composeRule.onAllNodesWithContentDescription(MOVE_UP_LABEL)[0].assertIsNotEnabled()
    composeRule.onAllNodesWithContentDescription(MOVE_DOWN_LABEL)[0].assertIsEnabled()
    composeRule.onAllNodesWithContentDescription(MOVE_DOWN_LABEL)[2].assertIsNotEnabled()
    composeRule.onAllNodesWithContentDescription(MOVE_UP_LABEL)[2].assertIsEnabled()
  }

  /**
   * A row without an artist is a row of one line, and it is the one that can fall under the minimum
   * touch target -- `assertHeightIsAtLeast` on the node itself, which this repository has measured
   * going honestly red at 32.38dp on the cast picker's speaker row.
   */
  @Test
  fun aOneLineRowIsStillBigEnoughToHit() {
    show(threeTracks)

    composeRule.onNodeWithText("Second Fixture")
      .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
  }

  /**
   * The **Hilt-bound** entry point over a real [QueueViewModel] -- the hop `QueueViewModelTest`
   * stops before and every case above starts after: `uiState` out of the view model, into the
   * stateless overload, and each control back into a view-model method.
   */
  @Test
  fun theHiltBoundScreenFollowsItsViewModelAndItsControlsReachItAgain() {
    val controls = RecordingQueueControls()
    // Built here and not inside `setContent`: lint's `ViewModelConstructorInComposable` is right
    // about production code and this is the one place the rule does not apply, so the construction
    // moves out of the composition rather than the check being suppressed. `PlayerScreenTest` does
    // the same for the same reason.
    val viewModel = QueueViewModel(controls)
    composeRule.setContent { QueueScreen(viewModel = viewModel) }

    composeRule.onNodeWithText(EMPTY_QUEUE_LABEL).assertIsDisplayed()

    controls.publish(threeTracks)
    composeRule.waitUntil(WAIT_MILLIS) {
      composeRule.onAllNodesWithText("Third Fixture").fetchSemanticsNodes().isNotEmpty()
    }

    // The view model connects on construction, so that call is already recorded; clearing here
    // keeps the assertion below about the tap and nothing else.
    assertThat(controls.calls).containsExactly("connect")
    controls.calls.clear()

    composeRule.onNodeWithText("Third Fixture").performClick()
    composeRule.waitUntil(WAIT_MILLIS) { controls.calls.isNotEmpty() }
    assertThat(controls.calls).containsExactly("jumpTo(2)")
  }

  private companion object {
    /** Long enough for a `viewModelScope` round trip on a loaded emulator, short enough to fail. */
    const val WAIT_MILLIS = 5_000L
  }
}
