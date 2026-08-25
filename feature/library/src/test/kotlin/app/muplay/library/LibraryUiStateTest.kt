package app.muplay.library

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
        syncMessage = null,
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
      syncMessage = null,
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
      syncMessage = null,
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
      syncMessage = null,
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
      syncMessage = null,
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
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.shuffled.map { it.title }).containsExactly("Track 1")
    // Surfaced rather than swallowed: a non-zero count means either a stale mirror or a server
    // whose scoping did not hold, and both are worth a user-visible line.
    assertThat(state.discardedOutOfScope).isEqualTo(2)
  }

  @Test
  fun `a sync message is passed through untouched`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      syncMessage = "Could not reach the server",
    ) as LibraryUiState.Content

    assertThat(state.syncMessage).isEqualTo("Could not reach the server")
  }
}
