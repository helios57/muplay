package app.muplay.player

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.theme.MuPlaySpacing
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full player screen, composed for real on a device against a [PlayerUiState] built by hand.
 *
 * No media session, no Hilt graph, no server: [PlayerScreen]'s stateless overload takes the state
 * and five lambdas, which is what makes this suite fast and hermetic. What it therefore does *not*
 * prove is the hop from `PlayerViewModel.uiState` into that overload — Task 10's end-to-end journey
 * is what closes that, and this file cannot.
 *
 * **Every assertion here is positional or value-bearing, never "it rendered".** A test that asserts
 * three strings are on screen passes just as happily when `title` and `artist` are swapped, which
 * is the exact defect class this project records; the fixtures are pairwise different and the
 * assertions below compare coordinates.
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val scrubbedTo = mutableListOf<Long>()
  private val actions = mutableListOf<String>()

  /** Long enough for a `viewModelScope` round trip on a loaded emulator, short enough to fail. */
  private val WAIT_MILLIS = 5_000L

  private fun show(uiState: PlayerUiState) {
    composeRule.setContent {
      PlayerScreen(
        uiState = uiState,
        onPlayPause = { actions += "playPause" },
        onNext = { actions += "next" },
        onPrevious = { actions += "previous" },
        onScrubTo = { scrubbedTo += it },
        onScrubFinished = { actions += "scrubFinished" },
      )
    }
  }

  /** [show], plus the two parameters `:app` supplies when casting is in the build. */
  private fun showWithCast(uiState: PlayerUiState, castDeviceName: String?) {
    composeRule.setContent {
      PlayerScreen(
        uiState = uiState,
        onPlayPause = { actions += "playPause" },
        onNext = { actions += "next" },
        onPrevious = { actions += "previous" },
        onScrubTo = { scrubbedTo += it },
        onScrubFinished = { actions += "scrubFinished" },
        castDeviceName = castDeviceName,
        castButton = { TextButton(onClick = { actions += "cast" }) { Text(CAST_SLOT_LABEL) } },
      )
    }
  }

  /**
   * The seek bar, found by the semantics a `Slider` carries rather than by a test tag: a tag in
   * production code is a hook that only tests use, and this project has none.
   */
  private fun seekBar() =
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

  private fun topOf(text: String): Float =
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

  private fun leftOf(text: String): Float =
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left

  /**
   * The same two readings for a control whose accessible name is a `contentDescription` rather than
   * its own text -- which is every transport button since the design pass. `centreYOf` and not
   * `topOf`, because the play button is deliberately the tallest thing in the row: two controls
   * that share a row no longer share a top edge, and asserting that they did would fail on a
   * layout that is correct.
   */
  private fun centreYOf(description: String): Float =
    composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center.y

  private fun centreYOfText(text: String): Float =
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center.y

  private fun leftOfControl(description: String): Float =
    composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.left

  private fun centreXOf(description: String): Float =
    composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center.x

  /** The compose root, which `PlayerScreen`'s `fillMaxSize` fills exactly. */
  private fun screen() = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

  @Test
  fun nothingPlayingSaysSoAndOffersNoTransportControls() {
    show(PlayerUiState.NothingPlaying)

    composeRule.onNodeWithText(NOTHING_PLAYING_LABEL).assertIsDisplayed()
    // The other half, and the half that matters: a screen that rendered the message *and* a row of
    // dead buttons would pass the assertion above on its own.
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(NEXT_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(PREVIOUS_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(ARTWORK_DESCRIPTION).assertDoesNotExist()
  }

  /**
   * Title, artist and album each in **its own place**, proved by comparing their y coordinates
   * rather than by asserting all three are somewhere on screen. With three pairwise-different
   * fixtures and no positional assertion, every permutation of the three passes.
   */
  @Test
  fun theTitleTheArtistAndTheAlbumEachRenderInTheirOwnPlace() {
    show(content())

    composeRule.onNodeWithText(TRACK_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(TRACK_ARTIST).assertIsDisplayed()
    composeRule.onNodeWithText(TRACK_ALBUM).assertIsDisplayed()

    assertThat(topOf(TRACK_TITLE)).isLessThan(topOf(TRACK_ARTIST))
    assertThat(topOf(TRACK_ARTIST)).isLessThan(topOf(TRACK_ALBUM))
  }

  /**
   * The elapsed time on the left and the total on the right, each formatted from **its own** field.
   * The fixture's position (61 s) and duration (1 h 1 min 1 s) format to different strings on
   * purpose: a screen that printed the duration twice, or swapped them, fails here.
   */
  @Test
  fun theElapsedTimeIsOnTheLeftAndTheTotalOnTheRight() {
    show(content())

    composeRule.onNodeWithText("1:01").assertIsDisplayed()
    composeRule.onNodeWithText("1:01:01").assertIsDisplayed()
    assertThat(leftOf("1:01")).isLessThan(leftOf("1:01:01"))
  }

  /**
   * The seek bar reads [PlayerUiState.Content.displayPositionMs], not `playback.positionMs`. The
   * two are set apart here (a finger parked at 2:30 while the player is at 1:01), which is the only
   * configuration that can tell them apart — and the whole reason `displayPositionMs` exists.
   */
  @Test
  fun theSeekBarFollowsTheDisplayedPositionAndNotThePlayer() {
    show(content(displayPositionMs = 150_000L, isScrubbing = true))

    val range = seekBar()
      .fetchSemanticsNode()
      .config[SemanticsProperties.ProgressBarRangeInfo]

    assertThat(range).isEqualTo(ProgressBarRangeInfo(150_000f, 0f..3_661_000f))
    // ...and the elapsed label under it agrees with the thumb rather than with the player.
    composeRule.onNodeWithText("2:30").assertIsDisplayed()
  }

  @Test
  fun theTransportButtonsFollowWhetherThereIsAnythingEitherSide() {
    show(content(PLAYING.copy(hasPrevious = false, hasNext = true)))

    composeRule.onNodeWithContentDescription(PREVIOUS_LABEL).assertIsNotEnabled()
    composeRule.onNodeWithContentDescription(NEXT_LABEL).assertIsEnabled()
  }

  /** The other way round, so neither button can be wired to the wrong flag or to a constant. */
  @Test
  fun theTransportButtonsFollowTheOtherEndOfTheQueueToo() {
    show(content(PLAYING.copy(hasPrevious = true, hasNext = false)))

    composeRule.onNodeWithContentDescription(PREVIOUS_LABEL).assertIsEnabled()
    composeRule.onNodeWithContentDescription(NEXT_LABEL).assertIsNotEnabled()
  }

  @Test
  fun theButtonOffersPauseWhilePlaying() {
    show(content(PLAYING.copy(isPlaying = true)))

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertDoesNotExist()
  }

  @Test
  fun theButtonOffersPlayWhilePaused() {
    show(content(PLAYING.copy(isPlaying = false)))

    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertDoesNotExist()
  }

  /**
   * Each control calls **its own** action. A pair of one-line lambdas is exactly where a
   * copy-paste swap survives every coverage gate: both run, both measure covered, and the queue
   * walks backwards.
   */
  @Test
  fun eachTransportControlCallsItsOwnAction() {
    show(content(PLAYING.copy(hasPrevious = true, hasNext = true)))

    composeRule.onNodeWithContentDescription(NEXT_LABEL).performClick()
    assertThat(actions).containsExactly("next")

    composeRule.onNodeWithContentDescription(PREVIOUS_LABEL).performClick()
    assertThat(actions).containsExactly("next", "previous")

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    assertThat(actions).containsExactly("next", "previous", "playPause")
  }

  /**
   * A real finger drag reports positions **while** it moves and finishes **once**, when it lifts.
   *
   * The order is the assertion: a screen that called `onScrubFinished` on every value change would
   * seek on every drag pixel, and one that never called it would leave the thumb stuck away from
   * the playhead forever. Neither is visible in a screenshot, and neither is reachable from the
   * `SetProgress` semantics action the test below uses — that action invokes both callbacks itself,
   * in one shot, so only a real gesture can tell "many changes, one finish" from "one of each".
   *
   * **`down(center)`, not `down(centerLeft)`, and that cost three device runs to learn.** A press at
   * the exact left edge of the slider's own semantics bounds reaches none of its gesture handlers:
   * the drag reported zero positions and zero finishes, indistinguishable from a screen that had
   * never wired the callbacks at all. From the centre it works, and `advanceEventTime` between the
   * injected events is what makes the two moves arrive as two moves.
   *
   * Two moves rather than one, because pressing the track jumps the thumb to the finger before the
   * delta is applied: a single `moveTo(centerRight)` lands on the far end of the range in one step,
   * and `first < last` then compares a value with itself — which is exactly the vacuous assertion
   * this project keeps finding.
   */
  @Test
  fun draggingTheSeekBarReportsPositionsAndThenFinishesOnce() {
    show(content(displayPositionMs = 0L))

    seekBar().performTouchInput {
      down(center)
      advanceEventTime(16)
      moveTo(Offset(center.x + width * 0.2f, center.y))
      advanceEventTime(16)
      moveTo(centerRight)
      advanceEventTime(16)
      up()
    }
    composeRule.waitForIdle()

    assertThat(scrubbedTo).hasSizeGreaterThanOrEqualTo(2)
    assertThat(scrubbedTo.first()).isLessThan(scrubbedTo.last())
    // Once, at the end. Not once per drag event.
    assertThat(actions).containsExactly("scrubFinished")
  }

  /**
   * The exact value, twice, through the control's own `SetProgress` semantics.
   *
   * The drag above proves the gesture path is wired at all; this proves the **value** survives it.
   * A screen that reported `playback.positionMs`, or a constant, on every change passes the drag
   * test and fails here on the second observation -- the one-observation defect this project
   * records having shipped repeatedly.
   */
  @Test
  fun theSeekBarReportsTheExactPositionItWasSetTo() {
    show(content(displayPositionMs = 0L))

    seekBar().performSemanticsAction(SemanticsActions.SetProgress) { it(1_200_000f) }
    composeRule.waitForIdle()
    assertThat(scrubbedTo).containsExactly(1_200_000L)

    seekBar().performSemanticsAction(SemanticsActions.SetProgress) { it(2_400_000f) }
    composeRule.waitForIdle()
    assertThat(scrubbedTo).containsExactly(1_200_000L, 2_400_000L)
    // Material3's `setProgress` invokes `onValueChange` and then `onValueChangeFinished` (read out
    // of `SliderKt.sliderSemantics`'s bytecode, not assumed), so this also pins that the finish
    // callback is wired to `onScrubFinished` and not to something else.
    assertThat(actions).containsExactly("scrubFinished", "scrubFinished")
  }

  @Test
  fun aBufferingTrackSaysSo() {
    show(content(PLAYING.copy(isBuffering = true)))

    composeRule.onNodeWithText(BUFFERING_LABEL).assertIsDisplayed()
  }

  @Test
  fun aTrackThatIsNotBufferingDoesNotSaySo() {
    show(content(PLAYING.copy(isBuffering = false)))

    composeRule.onNodeWithText(BUFFERING_LABEL).assertDoesNotExist()
  }

  /**
   * The **Hilt-bound** entry point — `PlayerScreen()` with no state argument, which is what `:app`
   * calls — over a real [PlayerViewModel] built on a hand-written [PlaybackControls].
   *
   * This is the one hop nothing else covers. `PlayerViewModelTest` stops at the view model and
   * every case above starts after it, so between them sits an untested wire: `uiState` out of the
   * view model, into the stateless overload, and each control back into a view-model method. A
   * screen that collected the wrong flow, or passed `viewModel::next` to the Previous button, would
   * be green in both of the other suites. "The layer at which a decision was verified versus
   * applied" is this project's own name for that defect.
   *
   * `hiltViewModel()` remains the default argument, so production wiring is unchanged; only that
   * default expression goes unexercised here.
   */
  @Test
  fun theHiltBoundScreenFollowsItsViewModelAndItsControlsReachItAgain() {
    val controls = RecordingPlaybackControls()
    val viewModel = PlayerViewModel(controls)
    composeRule.setContent { PlayerScreen(viewModel = viewModel) }

    composeRule.onNodeWithText(NOTHING_PLAYING_LABEL).assertIsDisplayed()

    controls.publish(PLAYING)
    composeRule.waitUntil(WAIT_MILLIS) {
      composeRule.onAllNodesWithText(TRACK_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(TRACK_ARTIST).assertIsDisplayed()

    // ...and back the other way, to the method that belongs to the control that was tapped.
    controls.playerIsPlaying = true
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    composeRule.waitUntil(WAIT_MILLIS) { controls.calls.contains("pause") }
    assertThat(controls.calls).doesNotContain("play", "next", "previous")

    composeRule.onNodeWithContentDescription(NEXT_LABEL).performClick()
    composeRule.waitUntil(WAIT_MILLIS) { controls.calls.contains("next") }
    assertThat(controls.calls).doesNotContain("previous")
  }

  /**
   * **The cover-art URL never reaches the screen.** It carries the same auth token and per-request
   * salt a stream URL does, and an artwork surface is the easiest place in an app to leak one — into
   * a screenshot, an accessibility read-out, or a `printToString` in somebody's debugging session.
   *
   * Asserted against the whole semantics tree, not against the one node it would most likely land
   * on: a leak into any node's text or content description is the same leak.
   */
  @Test
  fun theArtworkIsLabelledAndItsAuthenticatedUrlNeverReachesTheScreen() {
    show(content())

    composeRule.onNodeWithContentDescription(ARTWORK_DESCRIPTION).assertIsDisplayed()

    val tree = composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
    assertThat(tree).doesNotContain(ARTWORK_TOKEN)
    assertThat(tree).doesNotContain(ARTWORK_SALT)
    assertThat(tree).doesNotContain("getCoverArt")
    // A guard on the guard: if the dump were empty or truncated the three assertions above would
    // pass over nothing at all, which is this project's recorded "assertion that cannot fail".
    assertThat(tree).contains(ARTWORK_DESCRIPTION)
  }

  // ---- Plan 6 Task 10: where the sound is coming from ------------------------------------------

  /**
   * A player that keeps showing a play button and says nothing about the speaker is one whose user
   * reaches for the phone's own volume keys and hears nothing change.
   *
   * Two names, not one: a line that read "Playing on Kitchen" from a constant would pass a
   * single-fixture assertion.
   */
  @Test
  fun theScreenNamesTheSpeakerItIsPlayingOn() {
    showWithCast(content(), castDeviceName = "Küche")

    composeRule.onNodeWithText(PLAYING_ON_PREFIX + "Küche").assertIsDisplayed()
    composeRule.onNodeWithText(PLAYING_ON_PREFIX + "Study Amp").assertDoesNotExist()
  }

  @Test
  fun aSecondSpeakerGetsASecondLine() {
    showWithCast(content(), castDeviceName = "Study Amp")

    composeRule.onNodeWithText(PLAYING_ON_PREFIX + "Study Amp").assertIsDisplayed()
  }

  @Test
  fun nothingSaysWhereItIsPlayingWhileTheSoundIsOnThePhone() {
    // The other direction. A line that was always rendered would tell a user their phone is a
    // speaker somewhere else.
    showWithCast(content(), castDeviceName = null)

    composeRule.onNodeWithText(PLAYING_ON_PREFIX, substring = true).assertDoesNotExist()
  }

  @Test
  fun theCastSlotSitsInTheTransportRowAndIsTappable() {
    // Explicitly NOT playing, because the transport button this row is measured against renders
    // `PAUSE_LABEL` while `isPlaying` is true and `PLAY_LABEL` only when it is false -- and the
    // shared `PLAYING` fixture is playing. Written with the default, this asserted the position of
    // a node that was never on screen.
    showWithCast(content(PLAYING.copy(isPlaying = false)), castDeviceName = null)

    // Positional, not "it rendered": a slot dropped at the top of the screen is a control a user's
    // thumb never finds. Same row as Play, to the right of Next.
    //
    // Centre lines rather than top edges, because the design pass made the play button the tallest
    // control in the row on purpose: the cast slot is a text button of ordinary height beside a
    // 68dp circle, so their tops are legitimately ~14dp apart while their centres coincide (the
    // row is `verticalAlignment = CenterVertically`). Comparing tops here would fail on a layout
    // that is correct.
    //
    // Within [SAME_ROW_TOLERANCE_PX], and the number is measured rather than defensive: on
    // `muplay37` this read **2247.5 vs 2248.5**, one pixel apart, because two controls of
    // different odd heights centred on one line round to centres a pixel apart. The defect this
    // assertion exists for -- a slot laid out somewhere other than the transport row -- is
    // hundreds of pixels, so two is a tolerance that cannot hide it.
    assertThat(centreYOfText(CAST_SLOT_LABEL))
      .isCloseTo(centreYOf(PLAY_LABEL), within(SAME_ROW_TOLERANCE_PX))
    assertThat(leftOf(CAST_SLOT_LABEL)).isGreaterThan(leftOfControl(NEXT_LABEL))

    composeRule.onNodeWithText(CAST_SLOT_LABEL).performClick()
    assertThat(actions).contains("cast")
  }

  @Test
  fun aScreenWithNoCastingInTheBuildRendersTheTransportRowUnchanged() {
    // `:feature:player` must build and behave with no casting in the tree at all -- this plan's
    // definition of done requires that dropping casting stays two `git rm`s. Not playing, for the
    // same reason as the test above: `PLAY_LABEL` is what a paused transport row renders.
    show(content(PLAYING.copy(isPlaying = false)))

    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(CAST_SLOT_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(PLAYING_ON_PREFIX, substring = true).assertDoesNotExist()
  }


  // ---- the design review's fixes ---------------------------------------------------------------

  /**
   * **The play button is on the screen's vertical axis, with or without a cast slot in the row.**
   *
   * This is the defect that was visible in `play/screenshots/phone/06-now-playing.png`: the slot
   * was appended *inside* an `Arrangement.Center` row, so the row's own midpoint moved left by half
   * the slot's width and the one control a thumb finds without looking no longer lined up with the
   * artwork above it or with the slider's travel.
   *
   * Measured against the compose root rather than against a neighbour, because "centred" is a claim
   * about the screen. `:app` supplies an `IconButton` here and this suite a `TextButton`; both are
   * wider than [SAME_ROW_TOLERANCE_PX], so a regression cannot slip through on slot width.
   */
  @Test
  fun thePlayButtonSitsOnTheScreensCentreLineWithACastSlotInTheRow() {
    showWithCast(content(PLAYING.copy(isPlaying = false)), castDeviceName = null)

    val axis = screen().center.x
    assertThat(centreXOf(PLAY_LABEL)).isCloseTo(axis, within(SAME_ROW_TOLERANCE_PX))
    // The other half of "on the axis": the artwork it is supposed to line up with.
    assertThat(centreXOf(ARTWORK_DESCRIPTION)).isCloseTo(axis, within(SAME_ROW_TOLERANCE_PX))
    // ...and the slot is still in the row, and still to the right of Next. Both are asserted by
    // `theCastSlotSitsInTheTransportRowAndIsTappable`; repeated here only as the guard that this
    // test is not passing because the slot stopped being rendered.
    composeRule.onNodeWithText(CAST_SLOT_LABEL).assertIsDisplayed()
  }

  /**
   * **The artwork is at the top of the screen, and the slack is below it.**
   *
   * A weighted box that *centres* its child splits the leftover height into two dead bands, so the
   * art floats and the title sits as far from the picture it belongs to as the picture sits from
   * the status bar. Asserted as a distance rather than as an ordering: the two bands were roughly
   * 310px and 320px on `muplay37`, so "above is less than below" was already true of the layout
   * this replaces and would have been a test that could not fail.
   */
  @Test
  fun theArtworkIsAtTheTopOfTheScreenAndTheSlackIsBelowIt() {
    show(content())

    val artwork = composeRule.onNodeWithContentDescription(ARTWORK_DESCRIPTION)
      .fetchSemanticsNode().boundsInRoot
    val above = artwork.top - screen().top
    val below = topOf(TRACK_TITLE) - artwork.bottom

    // The gap above the art is the screen's own vertical padding and nothing else.
    val padding = with(composeRule.density) { MuPlaySpacing.xl.toPx() }
    assertThat(above).isCloseTo(padding, within(SAME_ROW_TOLERANCE_PX))
    // ...and the metadata block gets the room, which is the point of moving it.
    assertThat(below).isGreaterThan(above)
  }

  /**
   * The track name is a heading, so TalkBack's headings gesture lands on "what is this screen
   * about" instead of walking the transport row first.
   *
   * The artist and album deliberately are **not**: three headings stacked in a column is the same
   * as none, and the assertion below is what stops a later pass from marking all three.
   */
  @Test
  fun theTrackTitleIsAHeadingAndTheTwoLinesUnderItAreNot() {
    show(content())

    composeRule.onNodeWithText(TRACK_TITLE)
      .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    for (line in listOf(TRACK_ARTIST, TRACK_ALBUM)) {
      val heading = composeRule.onNodeWithText(line)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Heading)
      assertThat(heading).describedAs("\"%s\" is marked as a heading", line).isNull()
    }
  }

  /**
   * Every transport control is at least [MuPlaySpacing.minTouchTarget] in both directions.
   *
   * `IconButton`'s own container is 40dp and it relies on `minimumInteractiveComponentSize` to pad
   * the *touch* area out to 48; that is hittable but it measures as 40, so this asserts the size
   * the screen actually asks for rather than the padding a default happens to add.
   */
  @Test
  fun everyTransportControlIsAtLeastAThumbAcross() {
    show(content(PLAYING.copy(hasPrevious = true, hasNext = true, isPlaying = false)))

    for (label in listOf(PREVIOUS_LABEL, PLAY_LABEL, NEXT_LABEL)) {
      composeRule.onNodeWithContentDescription(label)
        .assertWidthIsAtLeast(MuPlaySpacing.minTouchTarget)
        .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
    }
  }

  private companion object {
    /**
     * How far apart two controls' centre lines may be and still be "in the same row".
     *
     * Two pixels, measured: see `theCastSlotSitsInTheTransportRowAndIsTappable`. A control in a
     * different row is off by hundreds.
     */
    const val SAME_ROW_TOLERANCE_PX = 2f

    /**
     * Stands in for `:feature:castpicker`'s `CastButton`, which this module deliberately cannot see.
     * The slot is a `@Composable () -> Unit`; what goes in it is `:app`'s business.
     */
    const val CAST_SLOT_LABEL = "Cast slot"
  }
}
