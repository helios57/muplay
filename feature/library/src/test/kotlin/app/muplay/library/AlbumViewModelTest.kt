package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.Song
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Proves [AlbumViewModel] shows the *right* album, not just *an* album: every fake below knows
 * about two disjoint albums so a swapped id -- reading one value but passing a different one to
 * [AlbumSource.album]/[AlbumSource.songs], or vice versa -- has something to be caught against. A
 * single-album fixture could not tell "forwarded correctly" from "forwarded some constant that
 * happens to match this test's one value".
 *
 * [AlbumViewModel.load] takes the id as a plain argument rather than this class reading a
 * `SavedStateHandle` at construction -- see [AlbumViewModel]'s own doc for why: verified on a
 * real device that Navigation 3 leaves `savedStateHandle["albumId"]` null.
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

    /** When set, [album] parks on it -- lets a test observe the state *while* a lookup is in
     *  flight, which is where N-3(b)'s false "no longer in your library" lived. */
    var albumGate: CompletableDeferred<Unit>? = null

    override suspend fun album(albumId: String): Album? {
      albumCalls += albumId
      albumGate?.await()
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
  private fun TestScope.warm(source: FakeAlbumSource): AlbumViewModel {
    val vm = AlbumViewModel(source)
    backgroundScope.launch { vm.uiState.collect {} }
    return vm
  }

  @Test
  fun `before load is called the screen is Loading, not a crash or an empty album`() = runTest(dispatcher) {
    val vm = warm(FakeAlbumSource())
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(AlbumUiState.Loading)
  }

  @Test
  fun `the album shown is the one load was called with, not a different one the source also knows`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Correct Album", 1)
      fake.albumsById["a2"] = album("a2", "Wrong Album", 1)
      fake.setSongs("a1", listOf(song("s1", "Correct Track", "a1", 1)))
      fake.setSongs("a2", listOf(song("s2", "Wrong Track", "a2", 1)))

      val vm = warm(fake)
      vm.load("a1")
      dispatcher.scheduler.advanceUntilIdle()

      val content = vm.uiState.value as AlbumUiState.Content
      assertThat(content.album.id).isEqualTo("a1")
      assertThat(content.album.name).isEqualTo("Correct Album")
      assertThat(content.songs.map { it.title }).containsExactly("Correct Track")
      // Both calls carry the same id load() was given, not "a2" or any other value the fake also
      // knows an answer for.
      assertThat(fake.albumCalls).containsExactly("a1")
      assertThat(fake.songsCalls).containsExactly("a1")
    }

  @Test
  fun `a different load call shows the other album, proving the id is forwarded, not hardcoded`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Correct Album", 1)
      fake.albumsById["a2"] = album("a2", "Wrong Album", 1)
      fake.setSongs("a2", listOf(song("s2", "Second Album Track", "a2", 1)))

      val vm = warm(fake)
      vm.load("a2")
      dispatcher.scheduler.advanceUntilIdle()

      val content = vm.uiState.value as AlbumUiState.Content
      assertThat(content.album.id).isEqualTo("a2")
      assertThat(content.songs.map { it.title }).containsExactly("Second Album Track")
    }

  @Test
  fun `loading a second, different album switches cleanly to it`() = runTest(dispatcher) {
    // flatMapLatest's own job: without it, the new album could combine with the previous id's
    // still-collecting songs flow instead of switching to the new one's.
    val fake = FakeAlbumSource()
    fake.albumsById["a1"] = album("a1", "First Album", 1)
    fake.albumsById["a2"] = album("a2", "Second Album", 1)
    fake.setSongs("a1", listOf(song("s1", "First Track", "a1", 1)))
    fake.setSongs("a2", listOf(song("s2", "Second Track", "a2", 1)))

    val vm = warm(fake)
    vm.load("a1")
    dispatcher.scheduler.advanceUntilIdle()
    assertThat((vm.uiState.value as AlbumUiState.Content).album.id).isEqualTo("a1")

    vm.load("a2")
    dispatcher.scheduler.advanceUntilIdle()

    val content = vm.uiState.value as AlbumUiState.Content
    assertThat(content.album.id).isEqualTo("a2")
    assertThat(content.songs.map { it.title }).containsExactly("Second Track")
  }

  @Test
  fun `song order from the mirror is preserved, not resorted`() = runTest(dispatcher) {
    val fake = FakeAlbumSource()
    fake.albumsById["a1"] = album("a1", "Album", 1)
    fake.setSongs(
      "a1",
      listOf(song("s3", "Third", "a1", 1), song("s1", "First", "a1", 1), song("s2", "Second", "a1", 1)),
    )

    val vm = warm(fake)
    vm.load("a1")
    dispatcher.scheduler.advanceUntilIdle()

    val content = vm.uiState.value as AlbumUiState.Content
    assertThat(content.songs.map { it.title }).containsExactly("Third", "First", "Second")
  }

  @Test
  fun `an album missing from the mirror is NotFound, not a crash or an empty Content`() = runTest(dispatcher) {
    // fake.albumsById has no entry for "gone" -- album() returns null, same as a real deleted
    // album a reconcile has already removed from the mirror.
    val fake = FakeAlbumSource()
    val vm = warm(fake)
    vm.load("gone")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(AlbumUiState.NotFound)
  }

  @Test
  fun `an album still being fetched is Loading, not the deleted-album message`() = runTest(dispatcher) {
    // N-3(b), task-9-review.md. `load()` clears the album row before the lookup returns, and this
    // view model used to read that cleared value as NotFound -- so between the songs flow's first
    // emission and the album arriving, a perfectly healthy album rendered "That album is no longer
    // in your library." The songs are already there below (setSongs before load), which is exactly
    // the shape a re-navigation produces on a device: this view model is Activity-scoped, so
    // album A -> back -> album B calls load("B") on an instance that already holds songs.
    val fake = FakeAlbumSource()
    fake.albumsById["a1"] = album("a1", "Still Loading", 1)
    fake.setSongs("a1", listOf(song("s1", "Track", "a1", 1)))
    val gate = CompletableDeferred<Unit>()
    fake.albumGate = gate

    val vm = warm(fake)
    vm.load("a1")
    dispatcher.scheduler.advanceUntilIdle()

    // Parked inside album(): the lookup has been issued and has not answered.
    assertThat(fake.albumCalls).containsExactly("a1")
    assertThat(vm.uiState.value).isEqualTo(AlbumUiState.Loading)

    gate.complete(Unit)
    dispatcher.scheduler.advanceUntilIdle()

    // ...and the same fetch, once it answers, is Content -- so this is a two-value observation of
    // the same field, not just "it was not NotFound once".
    val content = vm.uiState.value as AlbumUiState.Content
    assertThat(content.album.name).isEqualTo("Still Loading")
  }

  @Test
  fun `loading the same album twice never fetches it a second time, and keeps what is on screen`() =
    runTest(dispatcher) {
      // The early return in load(). Untested until review round 1 (N-3a): the class measured 1/2
      // BRANCH and deleting the guard outright left all 34 tests in this module green. Both halves
      // matter -- the call count proves the guard returns, and the state proves it returns
      // *before* the album row is cleared, i.e. that a redundant call cannot flash Loading over an
      // album the user is already reading.
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Only Album", 1)
      fake.setSongs("a1", listOf(song("s1", "Only Track", "a1", 1)))

      val vm = warm(fake)
      vm.load("a1")
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(fake.albumCalls).containsExactly("a1")

      vm.load("a1")
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(fake.albumCalls).containsExactly("a1")
      val content = vm.uiState.value as AlbumUiState.Content
      assertThat(content.album.name).isEqualTo("Only Album")
      assertThat(content.songs.map { it.title }).containsExactly("Only Track")
    }

  @Test
  fun `coverArtUrl forwards the exact art id and size it is given, not a swapped or hardcoded pair`() =
    runTest(dispatcher) {
      val fake = FakeAlbumSource()
      fake.albumsById["a1"] = album("a1", "Album", 1)
      val vm = warm(fake)
      vm.load("a1")
      dispatcher.scheduler.advanceUntilIdle()

      val first = vm.coverArtUrl("al-abc", 64)
      val second = vm.coverArtUrl("al-xyz", 512)

      assertThat(fake.coverArtCalls).containsExactly("al-abc" to 64, "al-xyz" to 512)
      assertThat(first).isEqualTo("cover:al-abc:64")
      assertThat(second).isEqualTo("cover:al-xyz:512")
    }
}
