package app.muplay.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.media.PlaybackFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar above the library, composed on a device against a [PlayerUiState] built by hand.
 *
 * Two of these tests are about a bug that only exists because the bar has a click of its own *and*
 * a button inside it: tapping the button must not also navigate, and tapping the bar must not also
 * toggle playback. Nested clickables get that wrong routinely and nothing about it is visible in a
 * screenshot.
 */
@RunWith(AndroidJUnit4::class)
class MiniPlayerTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()

  private fun show(uiState: PlayerUiState) {
    composeRule.setContent {
      MiniPlayer(
        uiState = uiState,
        onOpenPlayer = { actions += "open" },
        onPlayPause = { actions += "playPause" },
      )
    }
  }

  /**
   * Nothing at all, not an empty bar. 64dp of blank surface across the bottom of a browse screen
   * is worse than no bar, and a bar that is present-but-blank is also a tap target that does
   * nothing.
   */
  @Test
  fun nothingPlayingRendersNoBarAtAll() {
    show(PlayerUiState.NothingPlaying)

    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertDoesNotExist()
  }

  @Test
  fun theBarShowsTheTrackAboveItsArtist() {
    show(content())

    composeRule.onNodeWithText(TRACK_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText(TRACK_ARTIST).assertIsDisplayed()

    // Positional, so a title/artist swap fails here rather than passing two "is it displayed"
    // assertions that a swap satisfies equally well.
    //
    // `useUnmergedTree`, and it is load-bearing: the bar's `Modifier.clickable` merges its
    // descendants, so on the merged tree BOTH texts resolve to the one Row node and this
    // comparison reads 0.0f < 0.0f -- measured, on the first run of this suite, as a failure. A
    // version of this test that had asserted `isLessThanOrEqualTo` would have passed that way
    // while comparing a node with itself.
    val title = composeRule.onNodeWithText(TRACK_TITLE, useUnmergedTree = true)
      .fetchSemanticsNode().boundsInRoot.top
    val artist = composeRule.onNodeWithText(TRACK_ARTIST, useUnmergedTree = true)
      .fetchSemanticsNode().boundsInRoot.top
    assertThat(title).isLessThan(artist)
  }

  /** The album title belongs to the full screen. A three-line mini player is a full player. */
  @Test
  fun theBarDoesNotRepeatTheAlbum() {
    show(content())

    composeRule.onNodeWithText(TRACK_ALBUM).assertDoesNotExist()
  }

  @Test
  fun tappingTheBarOpensThePlayerAndDoesNotTouchPlayback() {
    show(content())

    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).performClick()

    assertThat(actions).containsExactly("open")
  }

  /**
   * The other direction, and the one that actually bites: an inner button inside an outer
   * `clickable` that lets the tap through navigates *and* toggles, so a user who meant to pause
   * lands on the full player with the music still going.
   */
  @Test
  fun tappingPlayTogglesPlaybackAndDoesNotOpenThePlayer() {
    show(content(PLAYING.copy(isPlaying = true)))

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()

    assertThat(actions).containsExactly("playPause")
  }

  @Test
  fun theBarOffersPauseWhilePlaying() {
    show(content(PLAYING.copy(isPlaying = true)))

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertDoesNotExist()
  }

  @Test
  fun theBarOffersPlayWhilePaused() {
    show(content(PLAYING.copy(isPlaying = false)))

    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertDoesNotExist()
  }

  /**
   * The **Hilt-bound** entry point — `MiniPlayer(onOpenPlayer)`, which is what `MuPlayApp` puts in
   * its `Scaffold`'s `bottomBar` — over a real [PlayerViewModel]. See `PlayerScreenTest`'s own
   * version for why this hop needs its own case.
   *
   * It also pins the bar's most visible behaviour end to end: nothing on screen until something is
   * playing, and the bar appearing by itself when something starts.
   */
  @Test
  fun theHiltBoundBarAppearsOnlyOncePlaybackStartsAndItsButtonReachesTheViewModel() {
    val controls = RecordingPlaybackControls()
    val viewModel = PlayerViewModel(controls)
    composeRule.setContent { MiniPlayer(onOpenPlayer = { actions += "open" }, viewModel = viewModel) }

    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertDoesNotExist()

    controls.publish(PLAYING)
    composeRule.waitUntil(5_000L) {
      composeRule.onAllNodesWithText(TRACK_TITLE).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertIsDisplayed()

    controls.playerIsPlaying = true
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    composeRule.waitUntil(5_000L) { controls.calls.contains("pause") }
    // Tapping the button did not also navigate -- the same nested-clickable rule as above, now
    // through the real view model.
    assertThat(actions).isEmpty()
  }

  /** Same rule as the full screen's: an authenticated cover-art URL reaches the image loader and
   *  nothing else. See `PlayerScreenTest`'s own version for why this is asserted over the whole
   *  tree rather than over one node. */
  @Test
  fun theAuthenticatedArtworkUrlNeverReachesTheBar() {
    show(content())

    val tree = composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE)
    assertThat(tree).doesNotContain(ARTWORK_TOKEN)
    assertThat(tree).doesNotContain(ARTWORK_SALT)
    assertThat(tree).doesNotContain("getCoverArt")
    // The dump is real, so the three assertions above are not passing over nothing.
    assertThat(tree).contains(MINI_PLAYER_LABEL)
  }

  // ---- the design review's fixes ---------------------------------------------------------------

  /**
   * **A blind user can hear what is playing.**
   *
   * A `contentDescription` on a *merging* node replaces the text of everything beneath it, so this
   * bar announced "Now playing, button" and stopped -- the title and the artist were on screen and
   * unreachable, and the only way to find out what was playing was to open the full player. The fix
   * is a `stateDescription`, which TalkBack reads *after* the name rather than instead of it, and
   * which leaves [MINI_PLAYER_LABEL] untouched: `:app`'s journeys find this bar by that exact
   * string and `PlaybackJourneyTest.notTheMiniPlayer` filters a text match on it.
   *
   * Both halves are asserted. A fix that had reached the track by rewriting the description would
   * satisfy the second assertion and break every journey in another module.
   */
  @Test
  fun theBarTellsAScreenReaderWhatIsPlayingAndNotOnlyThatSomethingIs() {
    show(content())

    val bar = composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).fetchSemanticsNode()

    assertThat(bar.config[SemanticsProperties.ContentDescription]).containsExactly(MINI_PLAYER_LABEL)

    val spoken = bar.config.getOrNull(SemanticsProperties.StateDescription)
    assertThat(spoken).contains(TRACK_TITLE)
    assertThat(spoken).contains(TRACK_ARTIST)
    // Not the album. The bar does not show it -- `theBarDoesNotRepeatTheAlbum` -- so it must not
    // claim it either; a read-out richer than the screen is its own kind of wrong.
    assertThat(spoken).doesNotContain(TRACK_ALBUM)
  }

  /**
   * **The bar leaves when playback stops**, and this is the guard on the machinery that lets it
   * leave *gracefully*.
   *
   * The exit transition needs the track still drawn on the bar while it shrinks, and by then the
   * state naming that track is already `NothingPlaying` -- so the composable holds the last content
   * it saw. Hold it without also driving `visible` from the live state and the bar never goes away
   * again for the life of the process, which no other case here can see:
   * `nothingPlayingRendersNoBarAtAll` starts empty and never transitions.
   *
   * `waitUntil` rather than a bare assertion because the bar is animating out, and the assertion is
   * "it is gone", not "it went instantly".
   */
  @Test
  fun theBarLeavesWhenPlaybackStops() {
    var uiState: PlayerUiState by mutableStateOf(content())
    composeRule.setContent {
      MiniPlayer(
        uiState = uiState,
        onOpenPlayer = { actions += "open" },
        onPlayPause = { actions += "playPause" },
      )
    }

    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL).assertIsDisplayed()

    uiState = PlayerUiState.NothingPlaying

    composeRule.waitUntil(5_000L) {
      composeRule.onAllNodesWithContentDescription(MINI_PLAYER_LABEL).fetchSemanticsNodes().isEmpty()
    }
  }

  /**
   * The bar and its one button are both at least [MuPlaySpacing.minTouchTarget] tall.
   *
   * The bar is the app's most-tapped control that is not a list row -- it is how a user gets back to
   * what is playing from anywhere -- and it sits at the very bottom edge of the screen, which is
   * where a thumb is least accurate.
   */
  @Test
  fun theBarAndItsButtonAreBothAtLeastAThumbAcross() {
    show(content(PLAYING.copy(isPlaying = true)))

    composeRule.onNodeWithContentDescription(MINI_PLAYER_LABEL)
      .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
    composeRule.onNodeWithContentDescription(PAUSE_LABEL)
      .assertWidthIsAtLeast(MuPlaySpacing.minTouchTarget)
      .assertHeightIsAtLeast(MuPlaySpacing.minTouchTarget)
  }

  /**
   * **The bar is where a failure is actually seen**, because it is what is on screen when playback
   * stops -- the full player is a place a user has to have navigated to.
   *
   * The message takes the artist's line rather than adding a third: this bar's height is what
   * pushes the browse list up, so a track failing must not move the whole library.
   */
  @Test
  fun aFailedTrackReplacesTheArtistLineWithWhatWentWrong() {
    show(content(PLAYING.copy(isPlaying = false, failure = PlaybackFailure.Connection)))

    composeRule.onNodeWithText(PlaybackFailure.Connection.message()).assertIsDisplayed()
    // And the artist is gone rather than pushed off-screen: two lines, still 64dp.
    composeRule.onAllNodesWithText(TRACK_ARTIST).fetchSemanticsNodes().let {
      assertThat(it).isEmpty()
    }
  }

  /**
   * The button retries, so it must **say** it retries.
   *
   * A screen reader user told "Play" who then hears nothing has been misled twice -- once by the
   * silence and once by the label. This is the assertion that makes the `contentDescription`
   * branch load-bearing; without it, leaving the label at "Play" costs nothing.
   */
  @Test
  fun theButtonOnAFailedTrackIsNamedForWhatItDoes() {
    show(content(PLAYING.copy(isPlaying = false, failure = PlaybackFailure.Server)))

    composeRule.onNodeWithContentDescription(RETRY_LABEL).assertIsDisplayed()
    composeRule.onAllNodesWithContentDescription(PLAY_LABEL).fetchSemanticsNodes().let {
      assertThat(it).isEmpty()
    }
  }

  /** The healthy bar is unchanged: the artist is back and the button is a plain play. */
  @Test
  fun aHealthyTrackKeepsItsArtistLineAndItsPlayLabel() {
    show(content(PLAYING.copy(isPlaying = false)))

    composeRule.onNodeWithText(TRACK_ARTIST).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PLAY_LABEL).assertIsDisplayed()
  }
}
