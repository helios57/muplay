package app.muplay.library

import androidx.lifecycle.SavedStateHandle
import app.muplay.model.Album
import app.muplay.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Proves [AlbumViewModel] reads the *right* album, not just *an* album: every fake below knows
 * about two disjoint albums so a swapped id -- reading `savedStateHandle`'s value but passing a
 * different one to [AlbumSource.album]/[AlbumSource.songs], or vice versa -- has something to be
 * caught against. A single-album fixture could not tell "forwarded correctly" from "forwarded
 * some constant that happens to match this test's one value".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumViewModelTest {

  private fun album(id: String, name: String, libraryId: Int) =
    Album(id, libraryId, name, "artist-1", "Test Artist", "al-$id", 3, 15)

  private fun song(id: String, title: String, albumId: String, libraryId: Int) =
    Song(id, libraryId, title, albumId, "Test Album", "artist-1", "Test Artist", 1, null, 5, "mp3", null)

  private class FakeAlbumSource : AlbumSource {
    val albumCalls = mutableListOf<String>()
    val songsCalls = mutableListOf<String>()
    val albumsById = mutableMapOf<String, Album?>()
    private val songsByAlbum = mutableMapOf<String, MutableStateFlow<List<Song>>>()

    fun setSongs(albumId: String, songs: List<Song>) {
      songsByAlbum.getOrPut(albumId) { MutableStateFlow(emptyList()) }.value = songs
    }

    override fun songs(albumId: String): Flow<List<Song>> {
      songsCalls += albumId
      return songsByAlbum.getOrPut(albumId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun album(albumId: String): Album? {
      albumCalls += albumId
      return albumsById[albumId]
    }

    val coverArtCalls = mutableListOf<Pair<String, Int>>()
    override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String {
      coverArtCalls += coverArtId to sizePx
      return "cover:$coverArtId:$sizePx"
    }
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  /** Same reasoning as `LibraryViewModelTest.warm`: [AlbumViewModel.uiState] is a
   * `combine(...).stateIn(WhileSubscribed(...))`, so it needs an active collector to run at all. */
  private fun TestScope.warm(albumId: String, source: FakeAlbumSource): AlbumViewModel {
    val handle = SavedStateHandle(mapOf(AlbumViewModel.ALBUM_ID_KEY to albumId))
    val vm = AlbumViewModel(handle, source)
    backgroundScope.launch { vm.uiState.collect {} }
    return vm
  }

  @Test
  fun `the album shown is the one named by the saved state handle, not a different one the source also knows`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Correct Album", 1)
      fake.albumsById["a2"] = album("a2", "Wrong Album", 1)
      fake.setSongs("a1", listOf(song("s1", "Correct Track", "a1", 1)))
      fake.setSongs("a2", listOf(song("s2", "Wrong Track", "a2", 1)))

      val vm = warm("a1", fake)
      dispatcher.scheduler.advanceUntilIdle()

      val content = vm.uiState.value as AlbumUiState.Content
      assertThat(content.album.id).isEqualTo("a1")
      assertThat(content.album.name).isEqualTo("Correct Album")
      assertThat(content.songs.map { it.title }).containsExactly("Correct Track")
      // Both calls carry the same id the handle was constructed with, not "a2" or any other
      // value the fake also knows an answer for.
      assertThat(fake.albumCalls).containsExactly("a1")
      assertThat(fake.songsCalls).containsExactly("a1")
    }

  @Test
  fun `a different saved state handle id shows the other album, proving the id is read, not hardcoded`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Correct Album", 1)
      fake.albumsById["a2"] = album("a2", "Wrong Album", 1)
      fake.setSongs("a2", listOf(song("s2", "Second Album Track", "a2", 1)))

      val vm = warm("a2", fake)
      dispatcher.scheduler.advanceUntilIdle()

      val content = vm.uiState.value as AlbumUiState.Content
      assertThat(content.album.id).isEqualTo("a2")
      assertThat(content.songs.map { it.title }).containsExactly("Second Album Track")
    }

  @Test
  fun `song order from the mirror is preserved, not resorted`() = runTest(dispatcher) {
    val fake = FakeAlbumSource()
    fake.albumsById["a1"] = album("a1", "Album", 1)
    fake.setSongs(
      "a1",
      listOf(song("s3", "Third", "a1", 1), song("s1", "First", "a1", 1), song("s2", "Second", "a1", 1)),
    )

    val vm = warm("a1", fake)
    dispatcher.scheduler.advanceUntilIdle()

    val content = vm.uiState.value as AlbumUiState.Content
    assertThat(content.songs.map { it.title }).containsExactly("Third", "First", "Second")
  }

  @Test
  fun `an album missing from the mirror is NotFound, not a crash or an empty Content`() = runTest(dispatcher) {
    // fake.albumsById has no entry for "gone" -- album() returns null, same as a real deleted
    // album a reconcile has already removed from the mirror.
    val fake = FakeAlbumSource()
    val vm = warm("gone", fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(AlbumUiState.NotFound)
  }

  @Test
  fun `a missing albumId argument fails loudly rather than silently showing an empty album`() {
    // checkNotNull's whole point: a wrong navigation wiring must crash immediately and namedly,
    // not render a blank screen nobody can diagnose from a bug report.
    val fake = FakeAlbumSource()
    val handle = SavedStateHandle(emptyMap())

    assertThatThrownBy { AlbumViewModel(handle, fake) }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining(AlbumViewModel.ALBUM_ID_KEY)
  }

  @Test
  fun `coverArtUrl forwards the exact art id and size it is given, not a swapped or hardcoded pair`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Album", 1)
      val vm = warm("a1", fake)
      dispatcher.scheduler.advanceUntilIdle()

      val first = vm.coverArtUrl("al-abc", 64)
      val second = vm.coverArtUrl("al-xyz", 512)

      assertThat(fake.coverArtCalls).containsExactly("al-abc" to 64, "al-xyz" to 512)
      assertThat(first).isEqualTo("cover:al-abc:64")
      assertThat(second).isEqualTo("cover:al-xyz:512")
    }
}
