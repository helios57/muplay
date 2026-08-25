package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * "Test Album" by "Test Artist" with tracks "Track 1"/"Track 2"/"Track 3"; Audiobooks holds
 * "Test Book" by "Test Author" with a single chaptered file. Neither album's text appears on the
 * other's screen, so each half of the walk falsifies the other half's constant.
 *
 * Preconditions are `FirstRunJourneyTest`'s, unchanged and for the same reasons: a seeded
 * `ci-navidrome-1` on 4533, `adb reverse tcp:4533 tcp:4533`, and an emulator started with
 * minigbm — see that class's own doc, and `ci/prepare-emulator.sh`.
 *
 * Setup is walked here rather than assumed: `SetupViewModel` starts at `Idle` on every Activity
 * launch, so reaching the browse screen at all means connecting and tagging first. Both libraries
 * are tagged unconditionally, so this test does not care what roles a previously-run test left
 * behind and needs no reset of its own.
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
    composeRule.onNodeWithText(MUSIC_ARTIST_NAME).assertIsDisplayed()
    composeRule.onNodeWithText(MUSIC_TRACK_ONE).assertIsDisplayed()
    composeRule.onNodeWithText(MUSIC_TRACK_TWO).assertIsDisplayed()
    composeRule.onNodeWithText(MUSIC_TRACK_THREE).assertIsDisplayed()
    // Not the browse screen any more, and not the "no longer in your library" state a wrong or
    // constant id would land on.
    composeRule.onNodeWithText(SEARCH_LIBRARY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(NOT_FOUND_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(BOOK_AUTHOR_NAME).assertDoesNotExist()

    // NavDisplay's onBack pops AlbumRoute and leaves LibraryRoute -- the browse screen is back.
    Espresso.pressBack()
    awaitText(SEARCH_LIBRARY_LABEL)

    // ---- Audiobooks: a different album, so no constant can satisfy both halves --------------
    composeRule.onNodeWithText(AUDIOBOOKS_LIBRARY_NAME).performClick()
    awaitText(BOOK_AUTHOR_NAME)
    composeRule.onNodeWithText(OPEN_LABEL).performClick()

    // The book's own author, on the album screen (the browse row shows it too, so the two
    // assertions below are what say *which* screen this is).
    awaitText(BOOK_AUTHOR_NAME)
    composeRule.onNodeWithText(SEARCH_LIBRARY_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(OPEN_LABEL).assertDoesNotExist()
    composeRule.onNodeWithText(BOOK_AUTHOR_NAME).assertIsDisplayed()
    composeRule.onNodeWithText(NOT_FOUND_LABEL).assertDoesNotExist()
    // The half that defeats a constant: an id hardcoded to the Music album would render Test
    // Album's tracks here, and an id hardcoded to this one would have failed above.
    composeRule.onNodeWithText(MUSIC_TRACK_ONE).assertDoesNotExist()
    composeRule.onNodeWithText(MUSIC_ALBUM_NAME).assertDoesNotExist()
  }

  /** Connect, tag both libraries, Continue — the shortest real path to the browse screen. */
  private fun reachTheBrowseScreen() {
    composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
    composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
    composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
    composeRule.onNodeWithText(CONNECT_LABEL).performClick()

    awaitText(TAG_AS_MUSIC_LABEL, CONNECT_TIMEOUT_MILLIS)
    composeRule.onAllNodesWithText(TAG_AS_MUSIC_LABEL)[MUSIC_ROW_CHIP].performClick()
    composeRule.onAllNodesWithText(TAG_AS_AUDIOBOOKS_LABEL)[AUDIOBOOKS_ROW_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled().performClick()

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
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    // Literal strings the real screens render, duplicated here rather than shared -- same rule
    // FirstRunJourneyTest states: a shared constant would let a change to what the user actually
    // sees pass unnoticed in a black-box journey.
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val TAG_AS_MUSIC_LABEL = "Tag as Music"
    const val TAG_AS_AUDIOBOOKS_LABEL = "Tag as Audiobooks"
    const val SEARCH_LIBRARY_LABEL = "Search this library"
    const val OPEN_LABEL = "Open"
    const val NOT_FOUND_LABEL = "That album is no longer in your library."

    /** Row positions in the tagging list, not roles -- see FirstRunJourneyTest's own note. */
    const val MUSIC_ROW_CHIP = 0
    const val AUDIOBOOKS_ROW_CHIP = 1

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

    /** A first `ping` plus `refreshFromServer` against the container. Same figure and same
     *  reasoning as FirstRunJourneyTest's: generous, because a gate that flakes is worse than none. */
    const val CONNECT_TIMEOUT_MILLIS = 30_000L

    /** The mirror reconcile LibraryViewModel's `init { refresh() }` starts: a `getScanStatus`
     *  round trip plus a full album/song fetch for both libraries, then a Room write. */
    const val SYNC_TIMEOUT_MILLIS = 60_000L

    /** Everything that is local: a back-stack edit, a chip tap, a Room-backed flow emission. */
    const val UI_TIMEOUT_MILLIS = 15_000L
  }
}
