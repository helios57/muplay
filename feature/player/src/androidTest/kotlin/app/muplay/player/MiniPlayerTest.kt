package app.muplay.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    composeRule.onNodeWithText(PLAY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(PAUSE_LABEL).assertDoesNotExist()
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

    composeRule.onNodeWithText(PAUSE_LABEL).performClick()

    assertThat(actions).containsExactly("playPause")
  }

  @Test
  fun theBarOffersPauseWhilePlaying() {
    show(content(PLAYING.copy(isPlaying = true)))

    composeRule.onNodeWithText(PAUSE_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(PLAY_LABEL).assertDoesNotExist()
  }

  @Test
  fun theBarOffersPlayWhilePaused() {
    show(content(PLAYING.copy(isPlaying = false)))

    composeRule.onNodeWithText(PLAY_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(PAUSE_LABEL).assertDoesNotExist()
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
}
