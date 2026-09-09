package io.github.helios57.muplay.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.designsystem.component.ADD_ALL_TO_QUEUE_LABEL
import io.github.helios57.muplay.designsystem.component.ADD_TO_QUEUE_LABEL
import io.github.helios57.muplay.designsystem.component.FAST_SCROLL_BAR_TAG
import io.github.helios57.muplay.designsystem.component.PLAY_ALL_NEXT_LABEL
import io.github.helios57.muplay.designsystem.component.PLAY_NEXT_LABEL
import io.github.helios57.muplay.designsystem.component.QUEUE_ALL_MENU_LABEL
import io.github.helios57.muplay.designsystem.component.QUEUE_MENU_LABEL
import io.github.helios57.muplay.model.FolderNode
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.MusicLibrary
import io.github.helios57.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The folders tab, composed against a [FolderUiState] built by hand.
 *
 * The same hole as `LibraryScreenTest`'s, one screen over, and found by the same measurement: the
 * per-row queue actions and the A-Z rail's jump read **0.00 LINE** off a full `:app` run.
 * `BrowseFilterJourneyTest` walks both libraries' trees against the real container and cannot reach
 * any of them -- it never opens a row's overflow menu, and the deepest folder in the seeded corpus
 * holds two files where `FAST_SCROLL_MIN_ITEMS` is twenty, so the rail is never drawn for it to
 * drag. A folder built here can hold twenty-four.
 *
 * `FolderUiStateTest` and `FolderViewModelTest` already hold every decision behind this screen on
 * the fast tier. What is only observable on a device is that each row's three callbacks carry
 * **that row's own index** -- three one-line lambdas inside an `itemsIndexed`, which is exactly
 * where a copy-paste swap runs, measures covered, and silently queues the wrong track.
 */
@RunWith(AndroidJUnit4::class)
class FolderScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()

  @Test
  fun playNextOnATrackRowQueuesThatRowsOwnIndex() {
    show(folders = emptyList(), tracks = tracksOf(3))

    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[1].performClick()
    composeRule.onNodeWithText(PLAY_NEXT_LABEL).performClick()

    assertThat(actions).containsExactly("trackPlayNext(1)")
  }

  @Test
  fun addToQueueOnATrackRowQueuesThatRowsOwnIndex() {
    show(folders = emptyList(), tracks = tracksOf(3))

    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[2].performClick()
    composeRule.onNodeWithText(ADD_TO_QUEUE_LABEL).performClick()

    assertThat(actions).containsExactly("trackAddToQueue(2)")
  }

  /**
   * The row itself, which is the tap everybody tries first and the one `AddToQueueButton` sits
   * beside without being inside -- `Modifier.clickable` merges its descendants, so a queue button
   * nested in the clickable area would be tappable by a finger and invisible to every merged-tree
   * matcher.
   */
  @Test
  fun tappingATrackRowPlaysThatRowsOwnIndex() {
    show(folders = emptyList(), tracks = tracksOf(3))

    composeRule.onNodeWithText(tracksOf(3)[2].title).performClick()

    assertThat(actions).containsExactly("track(2)")
  }

  /** And a subfolder row opens that folder rather than the one above or below it. */
  @Test
  fun tappingASubfolderRowOpensThatFoldersOwnPath() {
    show(folders = foldersOf(3), tracks = emptyList())

    composeRule.onNodeWithText(folderName(1)).performClick()

    assertThat(actions).containsExactly("open(Fixture/${folderName(1)})")
  }

  /** Tapping the row plays it; the menu beside it must not. */
  @Test
  fun queueingATrackRowDoesNotAlsoStartIt() {
    show(folders = emptyList(), tracks = tracksOf(3))

    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[0].performClick()
    composeRule.onNodeWithText(ADD_TO_QUEUE_LABEL).performClick()

    assertThat(actions).doesNotContain("track(0)")
  }

  /**
   * The whole-folder menu is a different control with different strings, and it queues the folder
   * rather than any row in it -- which is only checkable where both menus are on screen at once.
   */
  @Test
  fun theFolderMenuQueuesTheWholeFolderRatherThanARow() {
    show(folders = emptyList(), tracks = tracksOf(3), canShuffle = true)

    composeRule.onNodeWithContentDescriptionOnce(QUEUE_ALL_MENU_LABEL).performClick()
    composeRule.onNodeWithText(PLAY_ALL_NEXT_LABEL).performClick()

    assertThat(actions).containsExactly("folderPlayNext")
  }

  @Test
  fun theFolderMenuCanAppendTheWholeFolderToTheQueue() {
    show(folders = emptyList(), tracks = tracksOf(3), canShuffle = true)

    composeRule.onNodeWithContentDescriptionOnce(QUEUE_ALL_MENU_LABEL).performClick()
    composeRule.onNodeWithText(ADD_ALL_TO_QUEUE_LABEL).performClick()

    assertThat(actions).containsExactly("folderAddToQueue")
  }

  /**
   * Asserted as a scroll, not as a callback: `X Folder` is the twenty-fourth row and is not
   * composed until something scrolls to it, so its appearing is the jump having happened.
   */
  @Test
  fun tappingTheFootOfTheAZRailScrollsToTheEndOfTheFolderAndTheHeadScrollsBack() {
    show(folders = foldersOf(SHELF_WITH_A_RAIL), tracks = emptyList())

    composeRule.onNodeWithText(folderName(SHELF_WITH_A_RAIL - 1)).assertDoesNotExist()

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput { down(bottomCenter); up() }
    awaitFolder(SHELF_WITH_A_RAIL - 1)

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput { down(topCenter); up() }
    awaitFolder(0)
  }

  /**
   * A folder of tracks does not get a rail even when it is long enough, because tracks are ordered
   * by path and labelled by title: the bar would promise a landing place it cannot honour. This is
   * the rule that keeps the feature honest and nothing else asserts it on a real screen.
   */
  @Test
  fun aFolderOfTracksOrderedByPathGetsNoRailEvenWhenItIsLongEnough() {
    show(folders = emptyList(), tracks = tracksOf(SHELF_WITH_A_RAIL))

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).assertDoesNotExist()
  }

  private fun awaitFolder(index: Int) {
    val name = folderName(index)
    try {
      composeRule.waitUntil(SCROLL_TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
      }
    } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
      assertThat(composeRule.onAllNodesWithText(name).fetchSemanticsNodes())
        .describedAs("the rail should have scrolled %s into view", name)
        .isNotEmpty()
      throw timeout
    }
    composeRule.onNodeWithText(name).assertIsDisplayed()
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionOnce(
    label: String,
  ) = onAllNodesWithContentDescription(label)[0]

  private fun show(
    folders: List<FolderNode>,
    tracks: List<Song>,
    canShuffle: Boolean = false,
  ) {
    composeRule.setContent {
      screen(
        FolderUiState(
          path = "Fixture",
          title = "Fixture",
          parentPath = "",
          folders = folders,
          tracks = tracks,
          emptyReason = null,
          canShuffle = canShuffle,
        ),
      )
    }
  }

  @Composable
  private fun screen(uiState: FolderUiState) {
    FolderScreen(
      uiState = uiState,
      libraryFilter = LibraryFilterState(
        libraries = listOf(MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC)),
        selectedLibraryId = 1,
      ),
      onLibrarySelected = { actions += "library($it)" },
      onOpenFolder = { actions += "open($it)" },
      onShuffle = { actions += "shuffle" },
      onPlayAll = { actions += "playAll" },
      onTrackClick = { actions += "track($it)" },
      onTrackPlayNext = { actions += "trackPlayNext($it)" },
      onTrackAddToQueue = { actions += "trackAddToQueue($it)" },
      onFolderPlayNext = { actions += "folderPlayNext" },
      onFolderAddToQueue = { actions += "folderAddToQueue" },
    )
  }

  /** One folder per letter, ascending, which is the only shape a rail draws over. */
  private fun foldersOf(count: Int): List<FolderNode> = List(count) { index ->
    FolderNode(path = "Fixture/${folderName(index)}", name = folderName(index), trackCount = 1)
  }

  /**
   * Twenty-four **distinct** initials that are not in alphabetical order -- `F`, `G`, … `X`, `A`,
   * `B` … -- so the only thing standing between this list and a rail is the ascending check itself.
   * Distinct initials matter: a folder of `Track 01`, `Track 02` … derives one bucket and would be
   * refused for having nothing to offer, which would make this test pass for a reason that has
   * nothing to do with order.
   *
   * It is also what a real folder looks like. Tracks are ordered by path and labelled by title, and
   * those two disagree the moment a filename carries a track number -- so `M` on a bar over them
   * would promise a landing place the list cannot honour.
   */
  private fun tracksOf(count: Int): List<Song> = List(count) { index ->
    val title = "${'A' + (index + 5) % count} Track"
    Song(
      id = "song-$index",
      libraryId = 1,
      title = title,
      albumId = null,
      albumName = null,
      artistId = null,
      artistName = "Fixture Artist",
      trackNumber = null,
      discNumber = null,
      durationSeconds = 60,
      suffix = "mp3",
      coverArtId = null,
      path = "Fixture/%02d %s.mp3".format(index, title),
    )
  }

  private fun folderName(index: Int): String = "${'A' + index} Folder"
}

/** Twenty-four, four clear of `FAST_SCROLL_MIN_ITEMS`, and inside the letters `A`..`X`. */
private const val SHELF_WITH_A_RAIL = 24

private const val SCROLL_TIMEOUT_MILLIS = 5_000L
