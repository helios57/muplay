package io.github.helios57.muplay.library

import io.github.helios57.muplay.model.FolderListing
import io.github.helios57.muplay.model.FolderNode
import io.github.helios57.muplay.model.LibraryRole
import io.github.helios57.muplay.model.MusicLibrary
import io.github.helios57.muplay.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [FolderViewModel]'s own forwarding: which library id and which path reach the repository, and
 * which queue reaches playback from where.
 *
 * The shape `BrowseRepositoryTest`'s doc names is the risk here too -- every method is a
 * delegation, and a delegation that discards an argument and hardcodes a value passes every test
 * that never observes that argument at more than one value. So the fake records both arguments of
 * every call, and the library is switched underneath a fixed path (and the path underneath a fixed
 * library) rather than only ever varying one of them.
 */
class FolderViewModelTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Books", LibraryRole.AUDIOBOOKS)

  private fun song(id: String, path: String) = Song(
    id = id,
    libraryId = 1,
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
    path = path,
  )

  private class FakeFolderSource(libraries: List<MusicLibrary> = emptyList()) : FolderSource {
    override val libraries = MutableStateFlow(libraries)
    override val selectedLibraryId = MutableStateFlow<Int?>(1)

    /** The shared selection, as this fake sees it -- a tap on a chip has to move the same flow the
     *  listing is read from, or the folders tab would light a chip and go on showing the old
     *  library. */
    override fun selectLibrary(id: Int) {
      selectedLibraryId.value = id
    }

    val listings = mutableMapOf<Pair<Int, String>, MutableStateFlow<FolderListing>>()
    val listingCalls = mutableListOf<Pair<Int, String>>()

    fun setListing(libraryId: Int, path: String, listing: FolderListing) {
      listings.getOrPut(libraryId to path) { MutableStateFlow(empty(path)) }.value = listing
    }

    override fun listing(libraryId: Int, path: String): Flow<FolderListing> {
      listingCalls += libraryId to path
      return listings.getOrPut(libraryId to path) { MutableStateFlow(empty(path)) }
    }

    val pathedCounts = mutableMapOf<Int, MutableStateFlow<Int>>()
    override fun pathedSongCount(libraryId: Int): Flow<Int> =
      pathedCounts.getOrPut(libraryId) { MutableStateFlow(99) }

    val songsUnderCalls = mutableListOf<Pair<Int, String>>()
    var songsUnderAnswer: List<Song> = emptyList()
    override suspend fun songsUnder(libraryId: Int, path: String): List<Song> {
      songsUnderCalls += libraryId to path
      return songsUnderAnswer
    }

    /** Song **ids** and the start index: which queue was launched, and from where. */
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

    private fun empty(path: String) = FolderListing(path, emptyList(), emptyList())
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.warm(source: FakeFolderSource): FolderViewModel {
    val vm = FolderViewModel(source)
    backgroundScope.launch { vm.uiState.collect {} }
    return vm
  }

  @Test
  fun `the path the screen opened at is the path that is read`() = runTest {
    val source = FakeFolderSource()
    source.setListing(
      1,
      "Fourth Author",
      FolderListing(
        path = "Fourth Author",
        folders = listOf(FolderNode("Fourth Author/Single", "Single", 1)),
        tracks = emptyList(),
      ),
    )
    val vm = warm(source)

    vm.open("Fourth Author")
    advanceUntilIdle()

    assertThat(source.listingCalls).contains(1 to "Fourth Author")
    assertThat(vm.uiState.value?.folders?.map { it.name }).containsExactly("Single")
    assertThat(vm.uiState.value?.title).isEqualTo("Fourth Author")
  }

  @Test
  fun `switching library re-reads the same path in the new library`() = runTest {
    // The delegation defect this exists for: a ViewModel that captured the library id once shows
    // the previous library's folders after the user switches, which looks like stale data rather
    // than a wrong argument.
    val source = FakeFolderSource()
    source.setListing(1, "", FolderListing("", listOf(FolderNode("Music", "Music", 2)), emptyList()))
    source.setListing(2, "", FolderListing("", listOf(FolderNode("Books", "Books", 5)), emptyList()))
    val vm = warm(source)

    vm.open("")
    advanceUntilIdle()
    assertThat(vm.uiState.value?.folders?.map { it.name }).containsExactly("Music")

    source.selectedLibraryId.value = 2
    advanceUntilIdle()

    assertThat(vm.uiState.value?.folders?.map { it.name }).containsExactly("Books")
    assertThat(source.listingCalls).contains(2 to "")
  }

  @Test
  fun `nothing is read until a library is known`() = runTest {
    val source = FakeFolderSource()
    source.selectedLibraryId.value = null
    val vm = warm(source)

    vm.open("")
    advanceUntilIdle()

    assertThat(source.listingCalls).isEmpty()
    assertThat(vm.uiState.value).isNull()
  }

  // ---- the library filter ------------------------------------------------------------------------
  //
  // The same chip row the albums tab has always drawn, on the folders tab -- asked for as *"I want
  // to be able to filter playlists and folders by library"*. It is a `LibraryFilterSource` on the
  // seam rather than three members here, so what these two tests hold is the wiring: that this
  // screen reads the shared selection and that a tap on a chip writes it.

  @Test
  fun `the folders tab offers the libraries the mirror knows, with the shared one lit`() = runTest {
    val source = FakeFolderSource(listOf(music, books))
    source.selectedLibraryId.value = 2
    val vm = warm(source)
    backgroundScope.launch { vm.libraryFilter.collect {} }
    advanceUntilIdle()

    assertThat(vm.libraryFilter.value.libraries.map { it.name }).containsExactly("Music", "Books")
    assertThat(vm.libraryFilter.value.selectedLibraryId).isEqualTo(2)
    assertThat(vm.libraryFilter.value.offersChoice).isTrue()
  }

  @Test
  fun `tapping a chip here moves the selection the listing is read from`() = runTest {
    // The defect worth naming: a chip row that lights up and leaves the list alone. Both halves are
    // asserted -- the selection moved *and* the new library's folders were read -- because a
    // `selectLibrary` wired to nothing satisfies the first on its own.
    val source = FakeFolderSource(listOf(music, books))
    source.setListing(1, "", FolderListing("", listOf(FolderNode("Music", "Music", 2)), emptyList()))
    source.setListing(2, "", FolderListing("", listOf(FolderNode("Books", "Books", 5)), emptyList()))
    val vm = warm(source)
    vm.open("")
    advanceUntilIdle()

    vm.selectLibrary(2)
    advanceUntilIdle()

    assertThat(source.selectedLibraryId.value).isEqualTo(2)
    assertThat(vm.uiState.value?.folders?.map { it.name }).containsExactly("Books")
  }

  @Test
  fun `a server with one library offers no choice, so the row is not drawn`() = runTest {
    // Most installs. A chip that cannot be unselected is a row of screen spent saying nothing, and
    // this app is short of vertical space -- which is the other half of the same request.
    val source = FakeFolderSource(listOf(music))
    val vm = warm(source)
    backgroundScope.launch { vm.libraryFilter.collect {} }
    advanceUntilIdle()

    assertThat(vm.libraryFilter.value.offersChoice).isFalse()
  }

  @Test
  fun `shuffling a folder plays everything beneath it, in an order the repository did not choose`()
    = runTest {
      val source = FakeFolderSource()
      source.songsUnderAnswer = (1..40).map { song("s$it", "Fourth Author/$it.mp3") }
      val vm = warm(source)
      vm.open("Fourth Author")
      advanceUntilIdle()

      vm.shuffleFolder()
      advanceUntilIdle()

      assertThat(source.songsUnderCalls).containsExactly(1 to "Fourth Author")
      val (queue, startIndex) = source.playCalls.single()
      assertThat(startIndex).isZero
      // Every song, none lost and none duplicated -- a shuffle that drops tracks is the failure
      // that hides behind "it played something".
      assertThat(queue).containsExactlyInAnyOrderElementsOf(source.songsUnderAnswer.map { it.id })
      // ...and actually shuffled. Forty tracks make an accidental identity ordering impossible to
      // reach by chance, so this asserts the shuffle happened rather than hoping it did.
      assertThat(queue).isNotEqualTo(source.songsUnderAnswer.map { it.id })
    }

  @Test
  fun `playing a folder in order keeps the order the repository gave`() = runTest {
    val source = FakeFolderSource()
    source.songsUnderAnswer = listOf(
      song("a", "F/01.mp3"), song("b", "F/02.mp3"), song("c", "F/03.mp3"),
    )
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.playFolder()
    advanceUntilIdle()

    assertThat(source.playCalls.single()).isEqualTo(listOf("a", "b", "c") to 0)
  }

  @Test
  fun `tapping a track plays this folder's tracks from that track`() = runTest {
    // From the *listing*, not from `songsUnder`: the rows the user is looking at are the folder's
    // own files, and starting a queue of the whole subtree from index 2 would start it on a
    // different song than the one they tapped.
    val source = FakeFolderSource()
    val tracks = listOf(song("a", "F/01.mp3"), song("b", "F/02.mp3"), song("c", "F/03.mp3"))
    source.setListing(1, "F", FolderListing("F", emptyList(), tracks))
    source.songsUnderAnswer = listOf(song("z", "F/sub/99.mp3")) + tracks
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.playTrack(1)
    advanceUntilIdle()

    assertThat(source.playCalls.single()).isEqualTo(listOf("a", "b", "c") to 1)
    assertThat(source.songsUnderCalls).isEmpty()
  }

  // ---- the whole folder at once ------------------------------------------------------------------
  //
  // From `songsUnder` and **not** from the listing, which is the opposite of what the per-row
  // control does and is deliberate: "add this folder to the queue" means the folder, and this app's
  // folder feature is recursive by the user's own asking. The subtree track is first in the fixture
  // so that an implementation reading the listing instead is caught by the contents, not just by
  // the length.

  @Test
  fun `adding the whole folder to the queue queues everything beneath it and starts nothing`() =
    runTest {
      val source = FakeFolderSource()
      val tracks = listOf(song("a", "F/01.mp3"), song("b", "F/02.mp3"))
      source.setListing(1, "F", FolderListing("F", emptyList(), tracks))
      source.songsUnderAnswer = listOf(song("z", "F/sub/99.mp3")) + tracks
      val vm = warm(source)
      vm.open("F")
      advanceUntilIdle()

      vm.enqueueAll()
      advanceUntilIdle()

      assertThat(source.enqueueCalls).containsExactly(listOf("z", "a", "b"))
      assertThat(source.playNextCalls).isEmpty()
      assertThat(source.playCalls).isEmpty()
    }

  @Test
  fun `playing the whole folder next inserts everything beneath it and starts nothing`() = runTest {
    val source = FakeFolderSource()
    source.songsUnderAnswer = listOf(song("z", "F/sub/99.mp3"), song("a", "F/01.mp3"))
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.playAllNext()
    advanceUntilIdle()

    assertThat(source.playNextCalls).containsExactly(listOf("z", "a"))
    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `queueing a folder with nothing beneath it touches nothing`() = runTest {
    val source = FakeFolderSource()
    source.songsUnderAnswer = emptyList()
    val vm = warm(source)
    vm.open("Empty")
    advanceUntilIdle()

    vm.enqueueAll()
    vm.playAllNext()
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
  }

  @Test
  fun `queueing a whole folder before one is open touches nothing`() = runTest {
    val source = FakeFolderSource()
    source.songsUnderAnswer = listOf(song("a", "F/01.mp3"))
    val vm = warm(source)

    vm.enqueueAll()
    vm.playAllNext()
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
    assertThat(source.songsUnderCalls).isEmpty()
  }

  @Test
  fun `a shuffle with nothing beneath it starts no playback at all`() = runTest {
    // Rather than launching an empty queue, which puts the player on screen showing nothing.
    val source = FakeFolderSource()
    source.songsUnderAnswer = emptyList()
    val vm = warm(source)
    vm.open("Empty")
    advanceUntilIdle()

    vm.shuffleFolder()
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `tapping a row before the folder has loaded plays nothing`() = runTest {
    // The screen composes before `uiState` has an answer, and a `LazyColumn` that has not been
    // given rows cannot be tapped -- but `playTrack` is public and the state it reads is nullable.
    // What holds this is the range check, not a null check: measured, replacing the null handling
    // with an empty list leaves this green, because no index is in an empty list's indices.
    val source = FakeFolderSource()
    val vm = warm(source)

    vm.playTrack(0)
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `a row index the folder does not have plays nothing`() = runTest {
    // A recomposition can hand a stale index to a shorter list -- the folder is re-read whenever
    // the library changes underneath an open screen, which is exactly when the two disagree.
    val source = FakeFolderSource()
    source.setListing(1, "F", FolderListing("F", emptyList(), listOf(song("a", "F/01.mp3"))))
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.playTrack(4)
    // Both ends of the range, because `indices` is what makes this a range check: a length-only
    // guard (`startIndex >= tracks.size`) passes the line above and starts a queue at -1.
    vm.playTrack(-1)
    advanceUntilIdle()

    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `shuffling before a folder is open reads nothing`() = runTest {
    // `open` runs from the screen's `LaunchedEffect`, which is a composition later than the first
    // frame. Without the guard the prefix query runs against a null path.
    val source = FakeFolderSource()
    source.songsUnderAnswer = listOf(song("a", "F/01.mp3"))
    val vm = warm(source)

    vm.shuffleFolder()
    advanceUntilIdle()

    assertThat(source.songsUnderCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  @Test
  fun `shuffling with no library selected reads nothing`() = runTest {
    // Distinct from the case above: the path is known and the library is not, which is what a
    // signed-out or still-syncing install looks like.
    val source = FakeFolderSource()
    source.selectedLibraryId.value = null
    source.songsUnderAnswer = listOf(song("a", "F/01.mp3"))
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.shuffleFolder()
    advanceUntilIdle()

    assertThat(source.songsUnderCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  /**
   * **The row's own track, and nothing started.** The folder screen is the one place this could
   * plausibly queue the wrong thing: `songsUnder` is right there and returns the whole subtree,
   * which is what the two folder-level buttons on the same screen use.
   */
  @Test
  fun `adding a row to the queue queues that row alone and reads no subtree`() = runTest {
    val source = FakeFolderSource()
    val tracks = listOf(song("a", "F/01.mp3"), song("b", "F/02.mp3"), song("c", "F/03.mp3"))
    source.setListing(1, "F", FolderListing("F", emptyList(), tracks))
    source.songsUnderAnswer = listOf(song("z", "F/sub/99.mp3")) + tracks
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.enqueue(1)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).containsExactly(listOf("b"))
    assertThat(source.playNextCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
    assertThat(source.songsUnderCalls).isEmpty()
  }

  @Test
  fun `playing a row next inserts that row alone and starts nothing`() = runTest {
    val source = FakeFolderSource()
    source.setListing(1, "F", FolderListing("F", emptyList(), listOf(song("a", "F/01.mp3"), song("b", "F/02.mp3"))))
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.playNext(0)
    advanceUntilIdle()

    assertThat(source.playNextCalls).containsExactly(listOf("a"))
    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playCalls).isEmpty()
  }

  /**
   * The other guard on the queue path: a tap before `open` has answered reads a null `uiState`,
   * which is a different branch from an out-of-range index. `:feature:library`'s 1.00 BRANCH floor
   * over this class is what found it missing.
   */
  @Test
  fun `queueing a row before the folder has loaded touches nothing`() = runTest {
    val source = FakeFolderSource()
    val vm = warm(source)

    vm.enqueue(0)
    vm.playNext(0)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
  }

  /** `playTrack`'s range guard, on the queue path, which is a different one. */
  @Test
  fun `queueing a row the folder does not have touches nothing`() = runTest {
    val source = FakeFolderSource()
    source.setListing(1, "F", FolderListing("F", emptyList(), listOf(song("a", "F/01.mp3"))))
    val vm = warm(source)
    vm.open("F")
    advanceUntilIdle()

    vm.enqueue(4)
    vm.playNext(-1)
    advanceUntilIdle()

    assertThat(source.enqueueCalls).isEmpty()
    assertThat(source.playNextCalls).isEmpty()
  }
}
