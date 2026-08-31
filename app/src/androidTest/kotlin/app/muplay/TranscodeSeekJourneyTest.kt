package app.muplay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dragging the real seek bar inside a **forced transcode**, from the real screen.
 *
 * ### What this adds over `:core:media`'s `TranscodeSeekPlaybackTest`
 *
 * That suite builds the player itself and reads PCM out of the audio sink. This one builds nothing:
 * it taps the app, so every layer between the slider and the offset lands in the assertion --
 * `PlayerViewModel.commitScrub`, `PlaybackControls`, a `MediaController` bound over the **session**,
 * `MuPlaybackService`'s own `TranscodeOffsetSupport.refresh()`, the real Hilt graph, the real
 * `QueueRepository`, and `MediaItems.of` stamping the format on an item that came out of the real
 * mirror. Any one of those dropping the offset leaves the seek bar moving and the audio where it
 * was, and nothing else in this project would notice.
 *
 * ### The clock is stopped before the seek, and that is the whole design
 *
 * `CLAUDE.md`'s *"Five-second fixtures let time pass a test that its own defect should fail"*
 * records three device tests that were green against the very mutation they existed to catch,
 * because a test that **waits** for a position can be satisfied by playback reaching it unaided.
 * Every seek assertion is exactly that shape.
 *
 * So the player here is **paused** before the bar is touched, and the pause is asserted -- the
 * readout is sampled, left alone for longer than its own one-second granularity, and required to be
 * unchanged. A stopped clock cannot advance by itself at all, so the only thing that can move it to
 * twenty-four seconds is the seek. The fixture's thirty seconds then buy a second margin on top:
 * even a running player could not reach that readout inside the bounded waits below.
 *
 * ### The fixture
 *
 * `Offset Track`, `ci/seed-fixtures.sh`'s Opus file: thirty seconds, and the only one in the corpus
 * that `StreamFormat.forSuffix` sends through Navidrome's transcoder on the path a user takes.
 *
 * **No stream URL is ever read here**, per this suite's standing rule -- they carry `u`, `s=salt`
 * and `t=md5(password+salt)`, and an AssertJ failure message prints what it saw.
 */
@RunWith(AndroidJUnit4::class)
class TranscodeSeekJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  /**
   * Seek to eight tenths of a thirty-second transcode from the real seek bar, on a **stopped**
   * clock, and the readout lands there; resume, and it keeps running from there. And throughout,
   * the track knows it is thirty seconds long.
   *
   * One test rather than two, deliberately: two tests here walked the same seven screens to make
   * two gestures, and the second gesture in a process was measured failing with *"Failed to inject
   * touch input"* while the first succeeded. Merging halves the device time this suite holds the
   * shared emulator for and leaves one gesture, which is all the walk was ever for.
   *
   * Five observations, and each rules out a different failure:
   *
   *  1. **The total readout is thirty seconds before anything is seeked.** This is the first
   *     end-to-end test of a chain this project has carried untested since Plan 3 Task 1: a live
   *     transcode declares no `Content-Length`, so ExoPlayer reports `duration == C.TIME_UNSET`,
   *     and the only reason a length reaches this readout at all is that `MediaItems.of` sets
   *     `MediaMetadata.durationMs` unconditionally from the mirrored `Song`. Until `Offset Track`
   *     was seeded there was no file in the corpus that could reach that path.
   *  2. **The readout does not move while paused** -- so the jump below cannot be playback.
   *  3. **It is in the low twenties immediately after the drag, bounded both sides** -- so a seek
   *     that did nothing (still near zero) and one that landed elsewhere both fail.
   *  4. **The total is still thirty seconds afterwards**, not the six that are left of the
   *     re-issued stream. (This one discriminates only when Navidrome answers the offset request
   *     out of its transcoding cache, where it carries a length for the extractor to read;
   *     `TranscodeSeekPlaybackTest.aReissuedTranscodeReportsTheWholeTracksDuration` warms that
   *     entry deliberately and is where the override is gated.)
   *  5. **Resumed, it passes a further position inside a bounded wait** far shorter than the
   *     twenty-plus seconds ordinary playback would need to get there -- so the re-issued stream
   *     really is producing audio from the offset.
   */
  @Test
  fun seekingInsideAForcedTranscodeMovesTheStoppedClockAndTheTrackStillKnowsHowLongItIs() {
    playTheOpusTrack()

    assertThat(totalSeconds())
      .describedAs("the total readout for a live transcode, which declares no Content-Length")
      .isBetween(FIXTURE_SECONDS_FLOOR, FIXTURE_SECONDS_CEILING)

    val whenPaused = pauseAndConfirmTheClockIsStopped()
    assertThat(whenPaused)
      .describedAs("the paused position the seek below has to move away from")
      .isLessThan(SEEK_TARGET_FLOOR_SECONDS)

    seekBarTo(SEEK_FRACTION)

    awaitElapsedAtLeast(SEEK_TARGET_FLOOR_SECONDS, SEEK_SETTLE_MILLIS)
    val afterSeek = elapsedSeconds()
    assertThat(afterSeek)
      .describedAs("the elapsed readout after seeking to $SEEK_FRACTION of a 30 s track")
      .isBetween(SEEK_TARGET_FLOOR_SECONDS, SEEK_TARGET_CEILING_SECONDS)
    assertThat(totalSeconds())
      .describedAs("the total readout after seeking, which must still be the whole track's")
      .isBetween(FIXTURE_SECONDS_FLOOR, FIXTURE_SECONDS_CEILING)
    // ...and the elapsed readout is not the total, which is what stops a screen that rendered the
    // duration in both slots from satisfying either of the two assertions above.
    assertThat(afterSeek).isLessThan(totalSeconds())

    composeRule.onNodeWithContentDescription(PLAY_LABEL).performClick()
    awaitControl(PAUSE_LABEL)
    awaitElapsedAtLeast(afterSeek + RESUME_ADVANCE_SECONDS, RESUME_WAIT_MILLIS)
    assertThat(elapsedSeconds()).isGreaterThan(afterSeek)
  }

  // ---- the walk ---------------------------------------------------------------------------------

  /**
   * Stops the clock and proves it stopped, returning the position it stopped at.
   *
   * Both tests here seek from a **stopped** clock, for the reason this class's own header gives:
   * a readout that cannot advance on its own is the only kind whose jump is unambiguously the
   * seek's doing. Shared between them rather than written twice because it is also what settles
   * the input dispatcher between the tap that pauses and the drag that seeks -- without the wait,
   * the second gesture was measured failing with *"Failed to inject touch input"* in the test that
   * did not have it, and passing in the test that did.
   */
  private fun pauseAndConfirmTheClockIsStopped(): Int {
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    awaitControl(PLAY_LABEL)
    val whenPaused = elapsedSeconds()
    Thread.sleep(PAUSE_OBSERVATION_MILLIS)
    assertThat(elapsedSeconds())
      .describedAs("the elapsed readout ${PAUSE_OBSERVATION_MILLIS}ms after pausing")
      .isEqualTo(whenPaused)
    return whenPaused
  }

  /** Reach the library, open the one music album, tap the Opus track, and wait for real audio. */
  private fun playTheOpusTrack() {
    composeRule.reachLibraryScreen()
    composeRule.onAllNodesWithText(MUSIC_LIBRARY)[LIBRARY_CHIP].performClick()
    composeRule.waitUntil("the album list to arrive from the mirror", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    // One music album, per `ci/seed-fixtures.sh` -- which files the Opus fixture inside `Test
    // Album` rather than in one of its own precisely so that this walk needs no row pairing. A
    // `check`, so a second album is a loud failure here rather than this quietly opening it.
    check(composeRule.onAllNodesWithText(OPEN_LABEL).fetchSemanticsNodes().size == 1) {
      "the music library has more than one album; this walk would open the wrong one"
    }
    composeRule.onNodeWithText(OPEN_LABEL).performClick()

    composeRule.waitUntil("the album's tracks to be listed", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(OPUS_TRACK).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(OPUS_TRACK).performClick()
    composeRule.waitUntil("the player screen to open", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(PLAY_LABEL).fetchSemanticsNodes().isNotEmpty() ||
        composeRule.onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    // Audio advancing, not a button pressed. The Opus fixture's first ten seconds are silent by
    // design, and the position readout is what says the decoder is running regardless.
    awaitElapsedAtLeast(1, TIMEOUT_MILLIS)
  }

  /**
   * Drags the real seek bar to [fraction] of its width.
   *
   * `down(center)` then `moveTo`, rather than a tap at the target: `PlayerScreenTest` measured that
   * a touch which *starts* at the far edge of the slider's semantics bounds reaches none of its
   * gesture handlers, and `PlaybackJourneyTest` records the same for the left edge. Starting in the
   * middle and moving is the gesture a finger actually makes.
   */
  private fun seekBarTo(fraction: Float): Float {
    composeRule.waitForIdle()
    val bar = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    bar.performTouchInput {
      down(center)
      moveTo(Offset(width * fraction, center.y))
      up()
    }
    composeRule.waitForIdle()
    lastSeekBarValue = bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    return lastSeekBarValue
  }

  /**
   * The seek bar's value once the drag has been committed, in milliseconds.
   *
   * Recorded for [awaitElapsedAtLeast]'s failure message and **not asserted**, because of what it
   * actually is: `PlayerViewModel.commitScrub` clears the scrub position as soon as the finger
   * lifts, so the bar snaps back to the *player's* position and this reads that, not the value the
   * drag reached. Measured under the `gate/refresh-is-a-no-op` mutation: a drag to eight tenths of a
   * thirty-second track read back as `1248.0ms`, which is where the clock was, not where the finger
   * went. A `check(after > before)` was written here first and claimed to separate "the gesture
   * never reached the slider" from "the slider moved and the player did not"; it cannot, and it
   * passed under that mutation. What gates the seek is the readout assertion in the test body.
   */
  private var lastSeekBarValue: Float = -1f

  // ---- observations -------------------------------------------------------------------------------

  /**
   * The player screen's two `m:ss` readouts, **left first**: elapsed, then total.
   *
   * By x coordinate rather than by finder order, so a screen that swapped them fails. A deliberate
   * duplicate of `PlaybackJourneyTest`'s helper of the same name -- the readouts are this suite's
   * black-box contract with what a user sees, and a shared helper would let a layout change fix
   * itself in one suite while breaking the other silently.
   */
  private fun timeReadouts(): Pair<String, String> {
    val readouts = composeRule
      .onAllNodes(
        SemanticsMatcher("is an m:ss readout") { node ->
          node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            .any { TIME_READOUT.matches(it.text) }
        },
        useUnmergedTree = true,
      )
      .fetchSemanticsNodes()
      .map { node ->
        node.positionInRoot.x to
          node.config[SemanticsProperties.Text].first { TIME_READOUT.matches(it.text) }.text
      }
      .sortedBy { it.first }
    check(readouts.size == 2) {
      "expected exactly two m:ss readouts on the player screen, found ${readouts.map { it.second }}"
    }
    return readouts[0].second to readouts[1].second
  }

  private fun secondsOf(readout: String): Int =
    readout.split(":").map { it.toInt() }.fold(0) { total, part -> total * 60 + part }

  private fun elapsedSeconds(): Int = secondsOf(timeReadouts().first)

  private fun totalSeconds(): Int = secondsOf(timeReadouts().second)

  /**
   * Blocks until the elapsed readout shows at least [seconds], for at most [timeoutMs].
   *
   * **[timeoutMs] is an argument, and every caller passes a small one.** A thirty-second default
   * would let a seek that did nothing pass this by simply playing for twenty-four seconds, which is
   * the exact defect class `CLAUDE.md` records against this project's five-second fixtures.
   */
  private fun awaitElapsedAtLeast(seconds: Int, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = ""
    while (System.currentTimeMillis() < deadline) {
      last = timeReadouts().first
      if (secondsOf(last) >= seconds) return
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "the elapsed readout never reached ${seconds}s within ${timeoutMs}ms; last saw '$last', " +
        "total '${timeReadouts().second}', seek bar last set to ${lastSeekBarValue}ms. " +
        "Either the seek did not reach the stream, or nothing was decoded.",
    )
  }

  /** Blocks until [text] is on screen, naming what it was waiting for if it never arrives. */
  private fun awaitLabel(text: String) {
    composeRule.waitUntil("'$text' to appear on screen", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * [awaitLabel], for a control named by its `contentDescription` -- the transport buttons, since
   * the design pass made them icons carrying the same label constants they used to render.
   */
  private fun awaitControl(description: String) {
    composeRule.waitUntil("'$description' to appear on screen", TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    // Every label this suite makes a claim about, duplicated here rather than shared -- see
    // `JourneyNavigation.kt`'s own note on why only *navigation* strings live in one place.
    const val OPEN_LABEL = "Open"
    const val PLAY_LABEL = "Play"
    const val PAUSE_LABEL = "Pause"
    const val MUSIC_LIBRARY = "Music"
    const val LIBRARY_CHIP = 0

    /** `ci/seed-fixtures.sh`'s Opus fixture: the one track this app's own path transcodes. */
    const val OPUS_TRACK = "Offset Track"

    /** Thirty seconds, per `books.tsv` (30006 ms) and Navidrome (30). */
    const val FIXTURE_SECONDS_FLOOR = 29
    const val FIXTURE_SECONDS_CEILING = 31

    /** Eight tenths of thirty seconds is twenty-four -- the fixture's loud third region. */
    const val SEEK_FRACTION = 0.8f
    const val SEEK_TARGET_FLOOR_SECONDS = 20
    const val SEEK_TARGET_CEILING_SECONDS = 28

    /**
     * How long the readout may take to reflect a seek on a **paused** player.
     *
     * Four seconds, which is generous for a state update over a bound `MediaController` and is
     * still six times less than the twenty-four seconds a running player would need to reach the
     * same readout on its own. The player is paused here anyway, so it needs neither -- this bound
     * is the belt to the pause's braces.
     */
    const val SEEK_SETTLE_MILLIS = 4_000L

    /** Two more seconds of audio after resuming, and eight seconds of wall clock to find them. */
    const val RESUME_ADVANCE_SECONDS = 2
    const val RESUME_WAIT_MILLIS = 8_000L

    /**
     * Longer than the readout's own one-second granularity, so a *running* clock is guaranteed to
     * have changed the string. Equality after this interval therefore means stopped, rather than
     * "sampled twice inside the same second".
     */
    const val PAUSE_OBSERVATION_MILLIS = 1_500L

    /** `m:ss` or `h:mm:ss`, which is every shape `formatDuration` produces. */
    val TIME_READOUT = Regex("""\d+:\d\d(:\d\d)?""")

    const val TIMEOUT_MILLIS = 30_000L
    const val POLL_MILLIS = 100L
  }
}
