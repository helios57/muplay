package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: **which album the user tapped is the album that gets loaded** — the two hops above
 * `AlbumViewModel.load()`, on a real emulator against the real Navidrome container.
 *
 * N-6, `task-9-review.md`. `AlbumViewModelTest` proves `load(id) -> songs(id)/album(id)`. Nothing
 * proved the two hops that decide *which* id `load` is called with:
 *
 * ```
 * MuPlayApp:   entry<AlbumRoute> { route -> AlbumScreen(albumId = route.albumId) }
 * AlbumScreen: LaunchedEffect(albumId) { viewModel.load(albumId) }
 * ```
 *
 * Both survived every tier that existed: `AlbumScreen(albumId = "al-constant")` and
 * `LaunchedEffect(Unit)` each left the whole build green, and the only thing standing behind them
 * was a one-off manual walk that cannot be re-run. This is the same shape as the defect class this
 * project has now found nine times — *a value verified at one layer while the layer that supplies
 * it is unprotected* — and the same hop the `SavedStateHandle` crash lived on.
 *
 * **Two albums, in two different libraries, with disjoint contents**, because one is not enough: a
 * hardcoded id that happens to be the Music album's own would satisfy every assertion about the
 * Music album and none about the audiobook. `ci/seed-fixtures.sh` seeds exactly that — Music holds
 * "Test Album" by "Test Artist" with tracks "Track 1"/"Track 2"/"Track 3"; Audiobooks holds four
 * books since Plan 4 Task 1, of which this walk opens "Test Book" by "Test Author" (a single
 * chaptered file). Neither album's text appears on the other's screen, so each half of the walk
 * falsifies the other half's constant — and the four books carry four *different* authors, so
 * opening the wrong book fails on `BOOK_AUTHOR_NAME` rather than passing quietly.
 *
 * Preconditions are `FirstRunJourneyTest`'s, unchanged and for the same reasons: a seeded
 * `ci-navidrome-1` on 4533, `adb reverse tcp:4533 tcp:4533`, and an emulator started with
 * minigbm — see that class's own doc, and `ci/prepare-emulator.sh`.
 *
 * Setup is walked **only when this install needs it** — see [reachTheBrowseScreen] for the
 * measured failure that taught the difference. The roles this walk relies on therefore come from
 * whatever configured the app, which is safe here without being assumed: every path that
 * configures it tags Music as music and Audiobooks as audiobooks, and if one had not, the
 * assertions below fail loudly on [BOOK_AUTHOR_NAME] rather than passing quietly. That is the
 * same property the two disjoint albums give the rest of the walk.
 */
@RunWith(AndroidJUnit4::class)
class AlbumRouteJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun openingAnAlbumInEitherLibraryShowsThatAlbumsOwnTracks() {
    reachTheBrowseScreen()

    // ---- Music: the album the user tapped, and its own three tracks ------------------------
    // The mirror is filled by LibraryViewModel's own refresh() on init, so this is the first
    // wait that involves the server; everything after it is local.
    awaitText(MUSIC_ALBUM_NAME, SYNC_TIMEOUT_MILLIS)
    composeRule.onNodeWithText(OPEN_LABEL).performClick()

    awaitText(MUSIC_TRACK_ONE)
    composeRule.onNodeWithText(MUSIC_ALBUM_NAME).assertIsDisplayed()
    // `…OutsideMiniPlayer` on every track title and on the artist: the bar carries both, so an
    // earlier journey's leftover playback makes the plain matcher find two nodes and throw. See
    // `JourneyNavigation.notTheMiniPlayer`.
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_ARTIST_NAME).assertIsDisplayed()
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TRACK_ONE).assertIsDisplayed()
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TRACK_TWO).assertIsDisplayed()
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TRACK_THREE).assertIsDisplayed()
    // Not the browse screen any more, and not the "no longer in your library" state a wrong or
    // constant id would land on.
    composeRule.onNodeWithText(SEARCH_LIBRARY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(NOT_FOUND_LABEL).assertDoesNotExist()
    composeRule.onNodeWithTextOutsideMiniPlayer(BOOK_AUTHOR_NAME).assertDoesNotExist()

    // NavDisplay's onBack pops AlbumRoute and leaves LibraryRoute -- the browse screen is back.
    Espresso.pressBack()
    awaitText(SEARCH_LIBRARY_LABEL)

    // ---- Audiobooks: a different album, so no constant can satisfy both halves --------------
    composeRule.onNodeWithText(AUDIOBOOKS_LIBRARY_NAME).performClick()
    awaitText(BOOK_AUTHOR_NAME)
    // `onAllNodesWithText(...)[BOOK_ROW]`, not `onNodeWithText(...)`: the Audiobooks library holds
    // four books since Plan 4 Task 1, so there are four "Open" buttons and the single-node matcher
    // throws *"Expected exactly '1' node but could not find any"* before any assertion runs. See
    // BOOK_ROW for where the index comes from; `awaitText(BOOK_AUTHOR_NAME)` above is what
    // guarantees that row is composed at all, this list being a LazyColumn.
    composeRule.onAllNodesWithText(OPEN_LABEL)[BOOK_ROW].performClick()

    // The book's own author, on the album screen (the browse row shows it too, so the two
    // assertions below are what say *which* screen this is).
    awaitText(BOOK_AUTHOR_NAME)
    composeRule.onNodeWithText(SEARCH_LIBRARY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(OPEN_LABEL).assertDoesNotExist()
    composeRule.onNodeWithTextOutsideMiniPlayer(BOOK_AUTHOR_NAME).assertIsDisplayed()
    composeRule.onNodeWithText(NOT_FOUND_LABEL).assertDoesNotExist()
    // The half that defeats a constant: an id hardcoded to the Music album would render Test
    // Album's tracks here, and an id hardcoded to this one would have failed above.
    composeRule.onNodeWithTextOutsideMiniPlayer(MUSIC_TRACK_ONE).assertDoesNotExist()
    composeRule.onNodeWithText(MUSIC_ALBUM_NAME).assertDoesNotExist()
  }

  /**
   * The shortest real path to the browse screen, **whether or not this install is already set up**.
   *
   * This used to type into the setup screen unconditionally, on the reasoning quoted in this
   * class's own header: `SetupViewModel` starts at `Idle` on every Activity launch. That is true
   * of the setup *screen* and says nothing about whether it is shown — `StartDestinationViewModel`
   * reads the stored credentials and opens straight on the library when there are some, and
   * `connectedDebugAndroidTest` reinstalls the APK **without clearing app data**. So the walk
   * worked only while some earlier class happened to have signed out, and failed with
   *
   *     Failed to perform text input.
   *     Reason: Expected exactly '1' node but could not find any node that satisfies:
   *       (Text + InputText + EditableText contains 'Server URL' ...)
   *
   * measured as the *first* test of a full run, where nothing had signed out yet.
   *
   * [reachLibraryScreen] is that branch, already written and used by eight other journeys here,
   * so this defers to it rather than growing a ninth copy of the setup walk.
   */
  private fun reachTheBrowseScreen() {
    composeRule.reachLibraryScreen()
    awaitText(SEARCH_LIBRARY_LABEL)
  }

  /**
   * Blocks until [text] is on screen, or fails after [timeoutMillis].
   *
   * Compose's test framework synchronises with the composition and its own effects, but knows
   * nothing about a `viewModelScope` coroutine waiting on a socket or on Room — so every
   * transition here that crosses one of those needs an explicit wait, not a bare assertion.
   */
  private fun awaitText(text: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
    composeRule.waitUntil(timeoutMillis = timeoutMillis) {
      composeRule.onAllNodesWithText(text).notTheMiniPlayer().fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    // Literal strings the real screens render, duplicated here rather than shared -- same rule
    // FirstRunJourneyTest states: a shared constant would let a change to what the user actually
    // sees pass unnoticed in a black-box journey. (The setup screen's own labels went with the
    // hand-written setup walk; `JourneyNavigation` owns those now.)
    const val SEARCH_LIBRARY_LABEL = "Search this library"
    const val OPEN_LABEL = "Open"
    const val NOT_FOUND_LABEL = "That album is no longer in your library."

    /**
     * What `ci/configure-libraries.sh` and `ci/seed-fixtures.sh` actually put on the server. The
     * two albums are disjoint in every string, which is what makes each half of this walk a
     * falsification of the other half's possible constant.
     */
    const val AUDIOBOOKS_LIBRARY_NAME = "Audiobooks"
    const val MUSIC_ALBUM_NAME = "Test Album"
    const val MUSIC_ARTIST_NAME = "Test Artist"
    const val MUSIC_TRACK_ONE = "Track 1"
    const val MUSIC_TRACK_TWO = "Track 2"
    const val MUSIC_TRACK_THREE = "Track 3"
    const val BOOK_AUTHOR_NAME = "Test Author"

    /**
     * `Test Book`'s row among the four seeded books.
     *
     * `BrowseDao.observeAlbums` is `ORDER BY sortName`, and `ci/seed-fixtures.sh` writes
     * "Multi Part Book", "Second Book", "Tail Book", "Test Book" — so Test Book is last. It is
     * still the book this walk opens, and deliberately so: its author, `Test Author`, is the one
     * string the assertions below turn on, and each of the four books has a *different* author, so
     * landing on the wrong row fails on `BOOK_AUTHOR_NAME` rather than passing quietly.
     *
     * A row index rather than a text match on the book itself because `LibraryScreen` emits each
     * album's `Row` and its `Open` `Button` as two separate `LazyColumn` children — they share no
     * per-album parent for an ancestor or sibling matcher to reach through.
     */
    const val BOOK_ROW = 3

    /** The mirror reconcile LibraryViewModel's `init { refresh() }` starts: a `getScanStatus`
     *  round trip plus a full album/song fetch for both libraries, then a Room write. */
    const val SYNC_TIMEOUT_MILLIS = 60_000L

    /** Everything that is local: a back-stack edit, a chip tap, a Room-backed flow emission. */
    const val UI_TIMEOUT_MILLIS = 15_000L
  }
}
