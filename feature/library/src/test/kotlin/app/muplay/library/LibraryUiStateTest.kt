package app.muplay.library

import app.muplay.database.SyncFailure
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ShuffleResult
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LibraryUiStateTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Audiobooks", LibraryRole.AUDIOBOOKS)

  private fun album(id: String, name: String, libraryId: Int) =
    Album(id, libraryId, name, "artist-1", "Test Artist", "al-$id", 3, 15)

  private fun song(id: String, title: String, libraryId: Int) =
    Song(id, libraryId, title, "album-1", "Test Album", "artist-1", "Test Artist", 1, null, 5, "mp3", null)

  @Test
  fun `no libraries at all is its own state, not an empty content screen`() {
    // "You have not finished setup" and "this library is empty" are different problems with
    // different fixes, and a screen that renders them identically strands the user.
    assertThat(
      libraryContent(
        libraries = emptyList(),
        selectedLibraryId = null,
        query = "",
        albums = emptyList(),
        searchAlbums = emptyList(),
        shuffle = null,
        notice = LibraryNotice.Idle,
      ),
    ).isEqualTo(LibraryUiState.NoLibraries)
  }

  @Test
  fun `the first library is selected when nothing has been chosen`() {
    val state = libraryContent(
      libraries = listOf(music, books),
      selectedLibraryId = null,
      query = "",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.selectedLibraryId).isEqualTo(1)
    assertThat(state.albums.map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun `a selection that no longer exists falls back to the first library`() {
    // A library removed on the server between one sync and the next would otherwise leave the
    // screen pointed at an id nothing matches, showing an empty list forever.
    val state = libraryContent(
      libraries = listOf(music, books),
      selectedLibraryId = 99,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.selectedLibraryId).isEqualTo(1)
  }

  @Test
  fun `a non-blank query shows the search results instead of the full album list`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "book",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = listOf(album("a2", "Booked", 1)),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.albums.map { it.name }).containsExactly("Booked")
    assertThat(state.query).isEqualTo("book")
  }

  @Test
  fun `a whitespace-only query is not a search`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "   ",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.albums.map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun `a shuffle result is carried through with its out-of-scope count`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = ShuffleResult(listOf(song("s1", "Track 1", 1)), discardedOutOfScope = 2),
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.shuffled.map { it.title }).containsExactly("Track 1")
    // Surfaced rather than swallowed: a non-zero count means either a stale mirror or a server
    // whose scoping did not hold, and both are worth a user-visible line.
    assertThat(state.discardedOutOfScope).isEqualTo(2)
  }

  @Test
  fun `the library selector carries every library, exactly, in the order the mirror gave them`() {
    // N-2 (task-9-review.md). `LibraryScreen` renders `uiState.libraries.forEach { FilterChip(..) }`
    // -- that list *is* the Music/Audiobooks chip row, the only control a user has for choosing
    // which library to browse and shuffle, and the distinction this whole app exists to make.
    // Before review round 1 this field was asserted in no test at all: `libraries = emptyList()`
    // (the entire chip row deleted) and `libraries.reversed()` each left all 34 tests green.
    //
    // Two calls with the two libraries in opposite orders, because one call cannot discriminate:
    // `listOf(books, music)` alone is also what `sortedBy { it.name }` produces, and
    // `listOf(music, books)` alone is also what `sortedBy { it.id }` produces. Asserting both,
    // exactly and in order, is what no single sort, reversal or constant can satisfy at once.
    val asGiven = libraryContent(
      libraries = listOf(music, books),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(asGiven.libraries).containsExactly(music, books)

    val theOtherWayRound = libraryContent(
      libraries = listOf(books, music),
      selectedLibraryId = 2,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(theOtherWayRound.libraries).containsExactly(books, music)
  }

  @Test
  fun `shuffle order is the order the shuffle produced, not resorted`() {
    // N-5. Every shuffle fixture in this module held exactly one song, and `containsExactly` over
    // a one-element list cannot discriminate order -- so `shuffled = shuffle?.songs.orEmpty()`
    // becoming `.reversed()` passed. `Content.shuffled` is the play order the user reads under the
    // "Shuffled" heading, so its order is the value, not an incidental property of it.
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = ShuffleResult(
        listOf(song("s3", "Zebra", 1), song("s1", "Apple", 1), song("s2", "Mango", 1)),
        discardedOutOfScope = 0,
      ),
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    // Neither alphabetical nor its reverse, so neither a stray sort nor a reversal survives.
    assertThat(state.shuffled.map { it.title }).containsExactly("Zebra", "Apple", "Mango")
  }

  @Test
  fun `search result order is preserved, not resorted`() {
    // N-5, the search half. The browse list's order is asserted by
    // `LibraryViewModelTest.album order from the mirror is preserved` and by the searching = false
    // fixtures here; the *searching* branch chooses a different list, and a sort applied to that
    // branch alone was invisible to every test in this module.
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "a",
      albums = emptyList(),
      searchAlbums = listOf(album("a1", "Zebra", 1), album("a2", "Apple", 1), album("a3", "Mango", 1)),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.albums.map { it.name }).containsExactly("Zebra", "Apple", "Mango")
  }

  @Test
  fun `a sync failure is rendered from its cause rather than flattened`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Failed(SyncFailure.ServerError(502)),
    ) as LibraryUiState.Content

    assertThat(state.syncMessage).contains("502")
  }

  @Test
  fun `a failure with a mirror on screen offers it, and one without never does`() {
    fun messageWith(albums: List<Album>) = (
      libraryContent(
        libraries = listOf(music),
        selectedLibraryId = 1,
        query = "",
        albums = albums,
        searchAlbums = emptyList(),
        shuffle = null,
        notice = LibraryNotice.Failed(SyncFailure.Unreachable),
      ) as LibraryUiState.Content
      ).syncMessage

    assertThat(messageWith(listOf(album("a1", "Test Album", 1)))).contains("last synced")
    // The first run. There is no last synced library, so offering one is a false promise -- the
    // exact defect `LibraryUiState.Content`'s own KDoc forbids.
    assertThat(messageWith(emptyList())).doesNotContain("last synced")
  }

  @Test
  fun `a search that matches nothing does not claim the library is empty`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "brubeck",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.emptyReason).isEqualTo(LibraryEmptyReason.SearchNoMatch("brubeck"))
  }

  @Test
  fun `an empty mirror behind a failed sync blames the sync, not the library`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Failed(SyncFailure.SignInRejected(40)),
    ) as LibraryUiState.Content

    assertThat(state.emptyReason)
      .isEqualTo(LibraryEmptyReason.SyncFailed(SyncFailure.SignInRejected(40)))
  }

  @Test
  fun `only a completed sync over an empty library reports it as empty`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.emptyReason).isEqualTo(LibraryEmptyReason.Empty)
  }

  @Test
  fun `a list with albums in it has no empty reason at all`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      notice = LibraryNotice.Idle,
    ) as LibraryUiState.Content

    assertThat(state.emptyReason).isNull()
  }
}
