package app.muplay.library

import app.muplay.database.SyncFailure
import app.muplay.model.Playlist
import app.muplay.model.PlaylistWithSongs
import app.muplay.model.Song
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Both playlist view models, whose whole job is to read live and to **say so when that fails**.
 *
 * That is the difference from every other browse screen in this app: albums, folders and books are
 * served from the mirror and work offline, and playlists are not mirrored (see
 * `PlaylistRepository`). So a failure here is normal operation, not an exception -- and the defect
 * to guard is `LibraryViewModel.shuffle`'s old one, a `runCatching` whose `getOrElse` turns a
 * failure into a legitimate-looking empty success: server asleep, open Playlists, "no playlists",
 * no explanation anywhere.
 */
class PlaylistViewModelsTest {

  private fun song(id: String, libraryId: Int = 1) = Song(
    id = id,
    libraryId = libraryId,
    title = id,
    albumId = "al",
    albumName = "An Album",
    artistId = "ar",
    artistName = "A Band",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 100,
    suffix = "mp3",
    coverArtId = null,
    path = null,
  )

  private val roadTrip = Playlist(
    id = "p1",
    name = "Road Trip",
    songCount = 2,
    durationSeconds = 400,
    owner = "admin",
    coverArtId = null,
  )

  private class FakePlaylistSource : PlaylistSource {
    override val selectedLibraryId = MutableStateFlow<Int?>(7)

    var playlistsAnswer: () -> List<Playlist> = { emptyList() }
    var playlistsCallCount = 0
    override suspend fun playlists(): List<Playlist> {
      playlistsCallCount++
      return playlistsAnswer()
    }

    val playlistCalls = mutableListOf<Pair<String, Int>>()
    var playlistAnswer: () -> PlaylistWithSongs = { error("not set") }
    override suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs {
      playlistCalls += playlistId to fallbackLibraryId
      return playlistAnswer()
    }

    val playCalls = mutableListOf<Pair<List<String>, Int>>()
    override suspend fun play(songs: List<Song>, startIndex: Int) {
      playCalls += songs.map { it.id } to startIndex
    }

    /** The two queue edits, kept apart: appending and inserting are different promises. */
    val enqueueCalls = mutableListOf<List<String>>()
    override suspend fun enqueue(songs: List<Song>) {
      enqueueCalls += songs.map { it.id }
    }

    val playNextCalls = mutableListOf<List<String>>()
    override suspend fun playNext(songs: List<Song>) {
      playNextCalls += songs.map { it.id }
    }
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach fun tearDown() = Dispatchers.resetMain()

  @Test
  fun `the list is read once the screen exists`() = runTest {
    val source = FakePlaylistSource()
    source.playlistsAnswer = { listOf(roadTrip) }

    val vm = PlaylistsViewModel(source)
    advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(PlaylistsUiState.Content(listOf(roadTrip)))
  }

  @Test
  fun `a failure to load the list is reported, not rendered as an empty library`() = runTest {
    // The defect named in this class's doc. `Failed` and `Content(emptyList())` are two different
    // things and only one of them is the user's fault to fix.
    val source = FakePlaylistSource()
    source.playlistsAnswer = { throw IOException("no route to host") }

    val vm = PlaylistsViewModel(source)
    advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(PlaylistsUiState.Failed(SyncFailure.Unreachable))
  }

  @Test
  fun `a server with no playlists is content, not a failure`() = runTest {
    val source = FakePlaylistSource()
    source.playlistsAnswer = { emptyList() }

    val vm = PlaylistsViewModel(source)
    advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(PlaylistsUiState.Content(emptyList()))
  }

  @Test
  fun `refreshing asks the server again`() = runTest {
    // The point of not mirroring playlists: a listener who just added a track needs a way to see
    // it that does not involve a library rescan.
    val source = FakePlaylistSource()
    source.playlistsAnswer = { listOf(roadTrip) }
    val vm = PlaylistsViewModel(source)
    advanceUntilIdle()

    vm.refresh()
    advanceUntilIdle()

    assertThat(source.playlistsCallCount).isEqualTo(2)
  }

  @Test
  fun `opening a playlist reads it with the library the user is browsing as the stamp`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"), song("b"))) }
    val vm = PlaylistViewModel(source)

    vm.open("p1")
    advanceUntilIdle()

    // 7 is the selected library, and it reaches the source as the fallback stamp -- the value
    // `PlaylistRepository` uses only for entries the mirror has never seen.
    assertThat(source.playlistCalls).containsExactly("p1" to 7)
    assertThat(vm.uiState.value)
      .isEqualTo(PlaylistUiState.Content(roadTrip, listOf(song("a"), song("b"))))
  }

  @Test
  fun `playing a playlist starts at the row that was tapped, in the playlist's own order`() =
    runTest {
      // Order is the whole point of a playlist: sorting it would silently discard the one piece of
      // information the user put into it.
      val source = FakePlaylistSource()
      val songs = listOf(song("c"), song("a"), song("b"))
      source.playlistAnswer = { PlaylistWithSongs(roadTrip, songs) }
      val vm = PlaylistViewModel(source)
      vm.open("p1")
      advanceUntilIdle()

      vm.play(1)
      advanceUntilIdle()

      assertThat(source.playCalls.single()).isEqualTo(listOf("c", "a", "b") to 1)
    }

  @Test
  fun `shuffling a playlist plays all of it in another order`() = runTest {
    val source = FakePlaylistSource()
    val songs = (1..40).map { song("s$it") }
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, songs) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.shuffle()
    advanceUntilIdle()

    val (queue, startIndex) = source.playCalls.single()
    assertThat(startIndex).isZero
    assertThat(queue).containsExactlyInAnyOrderElementsOf(songs.map { it.id })
    assertThat(queue).isNotEqualTo(songs.map { it.id })
  }

  @Test
  fun `a playlist that cannot be read says so`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { throw IOException("gone") }
    val vm = PlaylistViewModel(source)

    vm.open("p1")
    advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(PlaylistUiState.Failed(SyncFailure.Unreachable))
  }

  @Test
  fun `a tap that names no row starts nothing`() = runTest {
    // `uiState` drops back to Loading five seconds after the screen goes away, so a stale tap can
    // genuinely arrive here with nothing loaded.
    val source = FakePlaylistSource()
    val vm = PlaylistViewModel(source)

    vm.play(0)
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `a row the playlist does not have starts nothing`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.play(3)
    // Both ends, for the reason `FolderViewModelTest` gives at its own row-index test.
    vm.play(-1)
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `shuffling an empty playlist starts nothing`() = runTest {
    // A playlist someone emptied on the server is a Content state with no songs, so this is the
    // one empty case that reaches shuffle looking loaded. Starting it would put the player on
    // screen with nothing in it.
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, emptyList()) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.shuffle()
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `shuffling before the playlist has loaded starts nothing`() = runTest {
    val source = FakePlaylistSource()
    val vm = PlaylistViewModel(source)

    vm.shuffle()
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `reopening the playlist already on screen does not read it again`() = runTest {
    // `open` runs from a `LaunchedEffect(playlistId)`, which re-runs on configuration change and
    // on every return to this screen. Without the guard, rotating the phone re-fetches -- and,
    // worse, drops the state back to Loading, so the list the user is looking at blinks away.
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.open("p1")
    advanceUntilIdle()

    assertThat(source.playlistCalls).containsExactly("p1" to 7)
    assertThat(vm.uiState.value).isInstanceOf(PlaylistUiState.Content::class.java)
  }

  @Test
  fun `opening a different playlist does read it`() = runTest {
    // The other half of the guard above, and the one that makes it a guard rather than a lock:
    // navigating from one playlist to another reuses this view model in the same back stack entry
    // only if the screens are the same route, but the id it is asked for really has changed.
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.open("p2")
    advanceUntilIdle()

    assertThat(source.playlistCalls).containsExactly("p1" to 7, "p2" to 7)
  }

  /**
   * **One song, and nothing started.** A playlist is the list most likely to be queued a track at a
   * time rather than played whole, and the control that does it must leave the current track alone.
   */
  @Test
  fun `adding a track to the queue queues that one track and starts nothing`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"), song("b"), song("c"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.enqueue(2)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).containsExactly(listOf("c"))
    assertThat(source.playNextCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `playing a track next inserts that one track and starts nothing`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"), song("b"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.playNext(1)
    advanceUntilIdle()

    assertThat(source.playNextCalls).containsExactly(listOf("b"))
    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  /**
   * The other guard on the queue path: a tap before `open` has answered reads a state that is not
   * `Content`, which is a different branch from an out-of-range index. `:feature:library`'s 1.00
   * BRANCH floor over this class is what found it missing.
   */
  @Test
  fun `queueing a row before the playlist has loaded touches nothing`() = runTest {
    val source = FakePlaylistSource()
    val vm = PlaylistViewModel(source)

    vm.enqueue(0)
    vm.playNext(0)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
  }

  /** `play`'s range guard, on the queue path, which is a different one. */
  @Test
  fun `queueing a row the playlist does not have touches nothing`() = runTest {
    val source = FakePlaylistSource()
    source.playlistAnswer = { PlaylistWithSongs(roadTrip, listOf(song("a"))) }
    val vm = PlaylistViewModel(source)
    vm.open("p1")
    advanceUntilIdle()

    vm.enqueue(3)
    vm.playNext(-1)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
  }
}
