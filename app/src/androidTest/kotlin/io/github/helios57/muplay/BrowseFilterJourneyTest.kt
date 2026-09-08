package io.github.helios57.muplay

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: the library filter on the **folders** and **playlists** tabs, against the real server.
 *
 * Asked for as *"I want to be able to filter playlists and folders by library"*. The albums tab has
 * had that chip row since it existed; these two did not, and this is the first thing in `:app` that
 * has ever opened either of them — measured before it was written, `FolderScreenKt` read **0/127
 * LINE** and `PlaylistScreensKt` **0/126** with the whole fleet's instrumented data merged in. Two
 * whole screens that no tier had ever composed.
 *
 * ### What is here and what is not
 *
 * The **folders** half is the whole feature end to end: two libraries with genuinely different
 * trees on the seeded container (`Test Artist` under Music, four author directories under
 * Audiobooks), so switching the chip is observable and a filter that did nothing would fail.
 *
 * The **playlists** half asserts the row is drawn and the screen is not in its failure state, and
 * stops there. `ci/navidrome.compose.yml`'s container is shared single-instance and seeded with no
 * playlists at all, so there is nothing on that screen to filter — and creating one would be a
 * write to state every other agent's suite reads. The rule itself (which playlist survives which
 * selection, and what an *underivable* scope does) is held by `PlaylistsContentTest` on the fast
 * tier, where the fixture is free. Said plainly here rather than left as a gap somebody rediscovers.
 *
 * ### Falsified by mutation, run rather than predicted
 *
 * Three mutations of the shipped screens, each rebuilt and re-run on `muplay37`:
 *
 * | mutation | red |
 * |---|---|
 * | `FolderScreen` draws the chips at every depth (drop `parentPath == null`) | `theChipRowIsDrawnAtTheFolderRootAndNotInsideAFolder` |
 * | `FolderScreen` draws no chips at all | that one **and** `theFoldersTabShowsTheChosenLibrarysTreeAndSwitchingItShowsTheOther` |
 * | `PlaylistsScreen` draws no chips | `thePlaylistsTabOffersTheSameFilterAndIsNotInItsFailureState` |
 *
 * Nothing else went red in any of the three, which is the half worth stating: each test fails for
 * its own reason rather than three tests failing together on any change to either screen.
 */
@RunWith(AndroidJUnit4::class)
class BrowseFilterJourneyTest {

  /** See `BrowseJourneyTest`'s own note: an ungranted POST_NOTIFICATIONS puts a system dialog over
   *  the whole UI and the failure names Compose rather than the permission. */
  @get:Rule(order = 0)
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  @get:Rule(order = 1)
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun theFoldersTabShowsTheChosenLibrarysTreeAndSwitchingItShowsTheOther() {
    composeRule.reachLibraryScreen()

    openTab(FOLDERS_TAB)
    awaitOutsideMiniPlayer(MUSIC_TOP_FOLDER)

    // The scoping contract at the UI level, both ways round on one run: the audiobook tree is not
    // merely further down the list, it is absent -- and after the switch the music one is.
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TOP_FOLDER).assertIsDisplayed()
    assertAbsentOutsideMiniPlayer(AUDIOBOOK_TOP_FOLDER)

    composeRule.onAllNodesWithText(AUDIOBOOK_LIBRARY).notTheMiniPlayer()[LIBRARY_CHIP].performClick()
    awaitOutsideMiniPlayer(AUDIOBOOK_TOP_FOLDER)

    composeRule.onNodeWithTextOutsideMiniPlayer(AUDIOBOOK_TOP_FOLDER).assertIsDisplayed()
    assertAbsentOutsideMiniPlayer(MUSIC_TOP_FOLDER)
  }

  @Test
  fun theChipRowIsDrawnAtTheFolderRootAndNotInsideAFolder() {
    // The rule `FolderScreen` states: below the root the screen is showing one library's subtree,
    // and offering to swap the tree out from under a path the user walked down reads as the folder
    // having emptied itself. The tab bar is one tap away and lands back at the root, so nothing is
    // unreachable -- which is why this is a deliberate absence rather than an oversight.
    composeRule.reachLibraryScreen()

    openTab(FOLDERS_TAB)
    awaitOutsideMiniPlayer(MUSIC_TOP_FOLDER)
    composeRule.onNodeWithTextOutsideMiniPlayer(AUDIOBOOK_LIBRARY).assertIsDisplayed()

    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TOP_FOLDER).performClick()
    awaitOutsideMiniPlayer(MUSIC_ALBUM_FOLDER)

    assertAbsentOutsideMiniPlayer(AUDIOBOOK_LIBRARY)
  }

  @Test
  fun thePlaylistsTabOffersTheSameFilterAndIsNotInItsFailureState() {
    composeRule.reachLibraryScreen()

    openTab(PLAYLISTS_TAB)
    awaitOutsideMiniPlayer(AUDIOBOOK_LIBRARY)

    // Both chips, so this cannot pass on a row that lost one of its libraries.
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_LIBRARY).assertIsDisplayed()
    composeRule.onNodeWithTextOutsideMiniPlayer(AUDIOBOOK_LIBRARY).assertIsDisplayed()
    // `Failed` renders this and `Content` never does. Without it the assertions above would be
    // satisfied by a screen that could not reach the server at all -- the chip row is drawn from
    // the mirror, which works offline, while the list itself is not.
    assertAbsentOutsideMiniPlayer(RETRY_LABEL)
  }

  private fun openTab(label: String) {
    composeRule.onAllNodesWithText(label).notTheMiniPlayer()[TAB].performClick()
  }

  private fun awaitOutsideMiniPlayer(text: String) {
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(text).notTheMiniPlayer().fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * That nothing **outside the bar** carries [text].
   *
   * Not `onNodeWithText(text).assertDoesNotExist()`: from the second class of a full run onwards
   * something is always playing, and the bar carries the track's title and artist -- so the plain
   * matcher reports a scoping leak that did not happen. `JourneyNavigation.notTheMiniPlayer` has
   * the measurement.
   */
  private fun assertAbsentOutsideMiniPlayer(text: String) {
    val found = composeRule.onAllNodesWithText(text).notTheMiniPlayer().fetchSemanticsNodes()
    check(found.isEmpty()) { "\"$text\" is still on screen (${found.size} node(s)) outside the mini player" }
  }

  private companion object {
    /** The tab bar's own labels -- `TopLevelDestination`. Duplicated from the production code
     *  rather than imported, the rule every journey here follows. */
    const val FOLDERS_TAB = "Folders"
    const val PLAYLISTS_TAB = "Playlists"

    /** `ci/configure-libraries.sh` names them; the chips carry the server's strings. */
    const val MUSIC_LIBRARY = "Music"
    const val AUDIOBOOK_LIBRARY = "Audiobooks"

    /**
     * The top of each library's tree, per `ci/fixtures/`: `Music/Test Artist/Test Album/...` and
     * `Audiobooks/Fourth Author/...`.
     *
     * `Fourth Author` rather than `Test Author`, deliberately: `books.tsv` gives the seeded book's
     * file the artist `Test Author`, so that string is one the mini player can carry and one this
     * suite already filters for elsewhere. A folder name nothing plays is the cleaner probe.
     */
    const val MUSIC_TOP_FOLDER = "Test Artist"
    const val AUDIOBOOK_TOP_FOLDER = "Fourth Author"
    const val MUSIC_ALBUM_FOLDER = "Test Album"

    /** `PlaylistScreens`' failure control. */
    const val RETRY_LABEL = "Try again"

    /** See `FirstRunJourneyTest` on why these are indices: verify by running, not by reasoning. */
    const val TAB = 0
    const val LIBRARY_CHIP = 0

    const val TIMEOUT_MILLIS = 30_000L
  }
}
