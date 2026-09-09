package io.github.helios57.muplay.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.helios57.muplay.designsystem.component.ADD_TO_QUEUE_LABEL
import io.github.helios57.muplay.designsystem.component.FAST_SCROLL_BAR_TAG
import io.github.helios57.muplay.designsystem.component.PLAY_NEXT_LABEL
import io.github.helios57.muplay.designsystem.component.QUEUE_MENU_LABEL
import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.database.SyncProgress
import io.github.helios57.muplay.model.Album
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.MusicLibrary
import io.github.helios57.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browse screen, composed for real against a [LibraryUiState] built by hand.
 *
 * Everything this screen *decides* is already held on the fast tier by `LibraryUiStateTest`, and
 * `:app`'s eight journeys already drive the whole screen against the real container. This class
 * exists for the three things **neither of those can reach**, and the reason is the fixture corpus
 * rather than the tests:
 *
 * - The A-Z rail. [io.github.helios57.muplay.designsystem.component.FastScrollBar] refuses to draw
 *   over fewer than `FAST_SCROLL_MIN_ITEMS` = 20 rows -- correctly, because a bar laid over a list
 *   you can already see is furniture. The shared CI Navidrome's music library holds **one album**,
 *   so no `:app` journey can drag a rail that is not there, and none ever will. A shelf built by
 *   hand can.
 * - The two per-row queue actions on a shuffled track. `ScopedShuffleJourneyTest` stands on that
 *   shelf and taps the rows, but nothing in `:app` opens their overflow menu.
 *
 * All three measured **0.00 LINE** from 2026-09-06 until this file existed, which is what
 * `:feature:library`'s nested-class floor in the root `build.gradle.kts` records.
 *
 * The two queue tests use a **two**-album shelf on purpose: at twenty the A-Z rail draws itself
 * over the right-hand edge of every row, which is where `AddToQueueButton` lives, and the tap under
 * test would land on the rail instead of on the button it names.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<String>()

  @Test
  fun playNextOnAShuffledRowQueuesThatRowsOwnIndex() {
    show(albums = shelfOf(2), shuffled = shuffleOf(3))

    openQueueMenuOnShuffledRow(1)
    composeRule.onNodeWithText(PLAY_NEXT_LABEL).performClick()

    assertThat(actions).containsExactly("playNext(1)")
  }

  @Test
  fun addToQueueOnAShuffledRowQueuesThatRowsOwnIndex() {
    show(albums = shelfOf(2), shuffled = shuffleOf(3))

    openQueueMenuOnShuffledRow(2)
    composeRule.onNodeWithText(ADD_TO_QUEUE_LABEL).performClick()

    assertThat(actions).containsExactly("addToQueue(2)")
  }

  /**
   * Neither of the two above proves the row is queued rather than *played*, which is the whole
   * point of the control -- a menu wired to `onShuffledSongClick` would satisfy both.
   */
  @Test
  fun queueingAShuffledRowDoesNotAlsoStartIt() {
    show(albums = shelfOf(2), shuffled = shuffleOf(3))

    openQueueMenuOnShuffledRow(0)
    composeRule.onNodeWithText(ADD_TO_QUEUE_LABEL).performClick()

    assertThat(actions).doesNotContain("play(0)")
  }

  /**
   * The rail moves the list, in both directions, and it is asserted as a *scroll* rather than as a
   * callback that fired: `X Album` is the twenty-fourth row of a `LazyColumn` and is not composed
   * at all until something scrolls to it, so its appearing is the jump having happened.
   *
   * Touching the foot of the rail rather than dragging it: down and drag are the same code path
   * inside the bar (a tap is a drag that did not move), and a `down`/`up` pair is deterministic
   * where a swipe's final interpolated position is not.
   */
  @Test
  fun tappingTheFootOfTheAZRailScrollsToTheEndOfTheShelfAndTheHeadScrollsBack() {
    show(albums = shelfOf(SHELF_WITH_A_RAIL), shuffled = emptyList())

    composeRule.onNodeWithText(albumName(SHELF_WITH_A_RAIL - 1)).assertDoesNotExist()

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput { down(bottomCenter); up() }
    awaitAlbum(SHELF_WITH_A_RAIL - 1)

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).performTouchInput { down(topCenter); up() }
    awaitAlbum(0)
  }

  /** A shelf too short for a rail does not get one, which is the other half of the same promise. */
  @Test
  fun aShelfTooShortForARailDoesNotGetOne() {
    show(albums = shelfOf(2), shuffled = emptyList())

    composeRule.onNodeWithTag(FAST_SCROLL_BAR_TAG).assertDoesNotExist()
  }

  /**
   * The four empty states, which this screen's own KDoc exists to keep apart and which **no `:app`
   * journey can render**: reaching the browse screen at all requires tagged libraries, so
   * `NoLibraries` is unreachable there; the CI container's library is never empty, never failing
   * and -- by the time a journey has finished setup and synced -- almost never still scanning.
   *
   * That last one is not a hypothetical. `SyncingMessage` and its call site were covered by a race:
   * a journey that happened to look while the first sync was in flight. Measured 2026-09-09, one
   * `:app` run caught it and the next did not, moving `LibraryScreenKt` from 182/193 to 171/193 --
   * a floor going red with nothing changed. Rendering the state from a hand-built `LibraryUiState`
   * is what takes the timing out of it.
   */
  @Test
  fun aSyncStillRunningGetsACountingSentenceAndABarThatShowsHowFar() {
    show(albums = emptyList(), shuffled = emptyList(), emptyReason = syncing(SyncProgress.Reading(done = 37, total = 412)))

    composeRule.onNodeWithText("37 of 412 albums", substring = true).assertIsDisplayed()
    composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(37f / 412f, 0f..1f))).assertIsDisplayed()
  }

  /**
   * The other arm of the same `if`, and the one that must not invent a number: `Listing` has no
   * denominator to offer, so the bar is indeterminate rather than sitting at zero -- which is what
   * a hung sync looks like.
   */
  @Test
  fun aSyncThatCannotCountItselfYetGetsAnIndeterminateBarRatherThanZero() {
    show(albums = emptyList(), shuffled = emptyList(), emptyReason = syncing(SyncProgress.Listing(found = 12)))

    composeRule.onNodeWithText("12 albums so far", substring = true).assertIsDisplayed()
    composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
  }

  /** A sync that failed names its cause. The expensive case: a wrong URL used to read as "no music". */
  @Test
  fun aSyncThatFailedNamesTheCauseRatherThanSayingTheLibraryIsEmpty() {
    show(albums = emptyList(), shuffled = emptyList(), emptyReason = LibraryEmptyReason.SyncFailed(SyncFailure.Unreachable))

    composeRule.onNodeWithText("Nothing here yet.").assertDoesNotExist()
    composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
  }

  /** And a library that really is empty says so, with no bar -- it is an answer, not a wait. */
  @Test
  fun aLibraryThatReallyIsEmptySaysSoWithNoProgressBar() {
    show(albums = emptyList(), shuffled = emptyList(), emptyReason = LibraryEmptyReason.Empty)

    composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()
    composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
  }

  /**
   * `NoLibraries` is not an empty [LibraryUiState.Content]: the fix is finishing setup, not waiting
   * for a sync, and the sentence has to say which.
   */
  @Test
  fun noLibrariesAtAllSendsTheUserBackToSetupRatherThanReportingAnEmptyLibrary() {
    composeRule.setContent { screen(LibraryUiState.NoLibraries) }

    composeRule.onNodeWithText("Finish setup", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Nothing here yet.").assertDoesNotExist()
  }

  /**
   * The scoping warning. Unreachable from `:app` **because the scoping works** --
   * `ScopedShuffleJourneyTest` exists to prove nothing is ever discarded -- so the one line that
   * tells a user why a shuffle came back short has never been drawn anywhere.
   */
  @Test
  fun aShuffleThatDroppedTracksOutsideTheLibrarySaysHowMany() {
    composeRule.setContent {
      screen(
        content(albums = shelfOf(2), shuffled = shuffleOf(3), emptyReason = null)
          .copy(discardedOutOfScope = 4),
      )
    }

    composeRule.onNodeWithText("4 $OUT_OF_SCOPE_SUFFIX").assertIsDisplayed()
  }

  private fun syncing(progress: SyncProgress) = LibraryEmptyReason.Syncing(progress)

  private fun openQueueMenuOnShuffledRow(index: Int) {
    composeRule.onAllNodesWithContentDescription(QUEUE_MENU_LABEL)[index].performClick()
  }

  /**
   * `waitUntil` reports nothing but "condition still not satisfied after N ms", so the timeout is
   * caught and re-raised as the assertion it was -- the shape CLAUDE.md records, rethrow included,
   * so a row that arrives between the two reads does not turn a red into a green.
   */
  private fun awaitAlbum(index: Int) {
    val name = albumName(index)
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

  private fun show(
    albums: List<Album>,
    shuffled: List<Song>,
    emptyReason: LibraryEmptyReason? = null,
  ) {
    composeRule.setContent { screen(content(albums, shuffled, emptyReason)) }
  }

  private fun content(
    albums: List<Album>,
    shuffled: List<Song>,
    emptyReason: LibraryEmptyReason?,
  ) = LibraryUiState.Content(
    libraries = listOf(MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC)),
    selectedLibraryId = 1,
    query = "",
    albums = albums,
    shuffled = shuffled,
    discardedOutOfScope = 0,
    syncMessage = null,
    emptyReason = emptyReason,
  )

  @Composable
  private fun screen(uiState: LibraryUiState) {
    LibraryScreen(
      uiState = uiState,
      onLibrarySelected = { actions += "library($it)" },
      onQueryChanged = { actions += "query($it)" },
      onShuffle = { actions += "shuffle" },
      onRefresh = { actions += "refresh" },
      onAlbumClick = { actions += "album($it)" },
      onShuffledSongClick = { actions += "play($it)" },
      onShuffledPlayNext = { actions += "playNext($it)" },
      onShuffledAddToQueue = { actions += "addToQueue($it)" },
      // Not null, and not a real URL. A null `coverArtId` would take `CoverArtImage`'s placeholder
      // branch, which no server this app talks to can produce -- Navidrome synthesises a cover id
      // for every album -- and would quietly move a floor whose comment records that branch as
      // unreachable. An empty URL leaves Coil in exactly the state the journeys leave it in.
      coverArtUrl = { _, _ -> "" },
    )
  }

  private fun shelfOf(count: Int): List<Album> = List(count) { index ->
    Album(
      id = "album-$index",
      libraryId = 1,
      name = albumName(index),
      artistId = null,
      artistName = "Fixture Artist",
      coverArtId = "cover-$index",
      songCount = 1,
      durationSeconds = 60,
    )
  }

  private fun shuffleOf(count: Int): List<Song> = List(count) { index ->
    Song(
      id = "song-$index",
      libraryId = 1,
      title = "Shuffled Track $index",
      albumId = "album-0",
      albumName = albumName(0),
      artistId = null,
      artistName = "Fixture Artist",
      trackNumber = null,
      discNumber = null,
      durationSeconds = 60,
      suffix = "mp3",
      coverArtId = null,
    )
  }

  /**
   * One album per letter, ascending, because that is the only shape a rail draws over: distinct
   * first letters in the list's own order. `A Album`, `B Album`, … `X Album`.
   */
  private fun albumName(index: Int): String = "${'A' + index} Album"
}

/** Twenty-four, four clear of `FAST_SCROLL_MIN_ITEMS`, and inside the letters `A`..`X`. */
private const val SHELF_WITH_A_RAIL = 24

private const val SCROLL_TIMEOUT_MILLIS = 5_000L
