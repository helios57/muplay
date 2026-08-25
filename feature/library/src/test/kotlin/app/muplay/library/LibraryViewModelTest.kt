package app.muplay.library

import app.muplay.database.ShuffleRepository
import app.muplay.database.SyncState
import app.muplay.database.dao.MirrorReplacement
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SearchResults
import app.muplay.model.ShuffleResult
import app.muplay.model.Song
import java.io.IOException
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
 * Proves [LibraryViewModel]'s own forwarding: which library id reaches [LibrarySource.albums]/
 * [LibrarySource.search]/[LibrarySource.shuffle], in what order, and with what value.
 * `libraryContent` (unit-tested in [LibraryUiStateTest]) already proves the *rules*; nothing
 * there proves the *wiring* -- a swapped or hardcoded argument between the ViewModel and its
 * sources would still pass every one of those tests. Every fake here is hand-written:
 * `ConventionTest`'s "no mock framework is declared" rule leaves no other option, and each
 * assertion below is built to fail if the argument it names were swapped for another value the
 * fake also knows about, not merely if the call were skipped entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Audiobooks", LibraryRole.AUDIOBOOKS)

  private fun album(id: String, name: String, libraryId: Int) =
    Album(id, libraryId, name, "artist-1", "Test Artist", "al-$id", 3, 15)

  private fun song(id: String, title: String, libraryId: Int) =
    Song(id, libraryId, title, "album-1", "Test Album", "artist-1", "Test Artist", 1, null, 5, "mp3", null)

  /** Records every call it receives, distinctly per argument, so a test can assert the exact
   * value forwarded rather than merely that some call happened. */
  private class FakeLibrarySource(libraries: List<MusicLibrary> = emptyList()) : LibrarySource {
    override val libraries = MutableStateFlow(libraries)
    private val albumsByLibrary = mutableMapOf<Int, MutableStateFlow<List<Album>>>()

    fun setAlbums(libraryId: Int, albums: List<Album>) {
      albumsByLibrary.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = albums
    }

    override fun albums(libraryId: Int): Flow<List<Album>> =
      albumsByLibrary.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

    val searchCalls = mutableListOf<Triple<Int, String, Int>>()
    var searchResult = SearchResults(emptyList(), emptyList(), emptyList())
    override suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
      searchCalls += Triple(libraryId, query, limit)
      return searchResult
    }

    val shuffleCalls = mutableListOf<Pair<Int, Int>>()
    var shuffleAnswer: () -> ShuffleResult = { ShuffleResult(emptyList(), discardedOutOfScope = 0) }
    override suspend fun shuffle(libraryId: Int, size: Int): ShuffleResult {
      shuffleCalls += libraryId to size
      return shuffleAnswer()
    }

    var syncCallCount = 0
    var syncAnswer: suspend () -> SyncState = { SyncState.UpToDate }
    override suspend fun syncIfStale(): SyncState {
      syncCallCount++
      return syncAnswer()
    }

    val coverArtCalls = mutableListOf<Pair<String, Int>>()
    override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String {
      coverArtCalls += coverArtId to sizePx
      return "cover:$coverArtId:$sizePx"
    }

    var ids: List<Int> = emptyList()
    override suspend fun allIds(): List<Int> = ids
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  /**
   * Constructs the view model and keeps [LibraryViewModel.uiState] warm for the rest of the test.
   * It is a `combine(...).stateIn(WhileSubscribed(...))`: with nothing collecting it, the
   * upstream flows never run at all and `.value` would sit at [LibraryUiState.Loading] forever --
   * `backgroundScope` (not a job the test has to join or cancel itself) is exactly
   * kotlinx-coroutines-test's tool for this.
   */
  private fun TestScope.warm(source: FakeLibrarySource): LibraryViewModel {
    val vm = LibraryViewModel(source)
    backgroundScope.launch { vm.uiState.collect {} }
    return vm
  }

  private fun content(vm: LibraryViewModel) = vm.uiState.value as LibraryUiState.Content

  @Test
  fun `selecting a library shows that library's own albums, not the previous selection's`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music, books))
      fake.setAlbums(1, listOf(album("a1", "Music Album", 1)))
      fake.setAlbums(2, listOf(album("a2", "Book Album", 2)))
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(content(vm).albums.map { it.name }).containsExactly("Music Album")

      vm.selectLibrary(2)
      dispatcher.scheduler.advanceUntilIdle()

      // Not "contains" and not the un-ordered form: a swapped id would still show library 1's
      // album here, which is exactly what this line is built to catch.
      assertThat(content(vm).selectedLibraryId).isEqualTo(2)
      assertThat(content(vm).albums.map { it.name }).containsExactly("Book Album")
    }

  @Test
  fun `album order from the mirror is preserved, not resorted`() = runTest(dispatcher) {
    // Order is a property here: a stray sortedBy{name} would turn this Zebra-Apple-Mango list
    // into Apple-Mango-Zebra and every other assertion in this class (which all happen to use
    // already-sorted fixtures) would stay green.
    val fake = FakeLibrarySource(listOf(music))
    fake.setAlbums(1, listOf(album("a1", "Zebra", 1), album("a2", "Apple", 1), album("a3", "Mango", 1)))
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).albums.map { it.name }).containsExactly("Zebra", "Apple", "Mango")
  }

  @Test
  fun `the first library is selected automatically when nothing has been chosen`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music, books))
    fake.setAlbums(1, listOf(album("a1", "Music Album", 1)))
    fake.setAlbums(2, listOf(album("a2", "Book Album", 2)))
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).selectedLibraryId).isEqualTo(1)
    assertThat(content(vm).albums.map { it.name }).containsExactly("Music Album")
  }

  @Test
  fun `switching the selected library clears the previous library's search text, results and shuffle`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music, books))
      fake.setAlbums(1, listOf(album("a1", "Music Album", 1)))
      fake.setAlbums(2, listOf(album("a2", "Book Album", 2)))
      fake.searchResult = SearchResults(emptyList(), listOf(album("a9", "Found It", 1)), emptyList())
      fake.shuffleAnswer = { ShuffleResult(listOf(song("s1", "Shuffled Track", 1)), discardedOutOfScope = 0) }
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      vm.search("found")
      vm.shuffle()
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(content(vm).query).isEqualTo("found")
      assertThat(content(vm).albums.map { it.name }).containsExactly("Found It")
      assertThat(content(vm).shuffled.map { it.title }).containsExactly("Shuffled Track")

      vm.selectLibrary(2)
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(content(vm).query).isEqualTo("")
      assertThat(content(vm).shuffled).isEmpty()
      // Back to library 2's own full list, not the stale search result carried over from library 1.
      assertThat(content(vm).albums.map { it.name }).containsExactly("Book Album")
    }

  @Test
  fun `searching forwards the exact query and the currently selected library, not a stale or swapped one`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music, books))
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()
      vm.selectLibrary(2)
      dispatcher.scheduler.advanceUntilIdle()

      vm.search("abc")
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(fake.searchCalls.last()).isEqualTo(Triple(2, "abc", 50))

      vm.search("xyz")
      dispatcher.scheduler.advanceUntilIdle()
      // A second, different call: proves the query is read fresh each time, not cached or
      // ignored after the first search.
      assertThat(fake.searchCalls.last()).isEqualTo(Triple(2, "xyz", 50))
    }

  @Test
  fun `a blank query clears the search results without calling search again`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music))
    fake.setAlbums(1, listOf(album("a1", "Music Album", 1)))
    fake.searchResult = SearchResults(emptyList(), listOf(album("a9", "Found It", 1)), emptyList())
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    vm.search("abc")
    dispatcher.scheduler.advanceUntilIdle()
    assertThat(fake.searchCalls).hasSize(1)

    vm.search("")
    dispatcher.scheduler.advanceUntilIdle()
    assertThat(fake.searchCalls).hasSize(1)
    assertThat(content(vm).albums.map { it.name }).containsExactly("Music Album")
  }

  @Test
  fun `shuffle forwards the exact selected library id and the default shuffle size`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music, books))
    fake.shuffleAnswer = { ShuffleResult(listOf(song("s1", "Track 1", 2)), discardedOutOfScope = 3) }
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()
    vm.selectLibrary(2)
    dispatcher.scheduler.advanceUntilIdle()

    vm.shuffle()
    dispatcher.scheduler.advanceUntilIdle()

    // ShuffleRepository.DEFAULT_SHUFFLE_SIZE, not a re-typed literal: a mutation that hardcodes
    // some other size here still reads as "a number was passed" to a weaker assertion.
    assertThat(fake.shuffleCalls.last()).isEqualTo(2 to ShuffleRepository.DEFAULT_SHUFFLE_SIZE)
    assertThat(content(vm).shuffled.map { it.title }).containsExactly("Track 1")
    assertThat(content(vm).discardedOutOfScope).isEqualTo(3)
  }

  @Test
  fun `a shuffle failure clears the result rather than keeping a stale one or crashing`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music))
    fake.shuffleAnswer = { throw IOException("boom") }
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    vm.shuffle()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).shuffled).isEmpty()
    assertThat(content(vm).discardedOutOfScope).isEqualTo(0)
  }

  @Test
  fun `actions fall back to the mirror's own known library ids when no library is selected yet`() =
    runTest(dispatcher) {
      // libraries is empty, so uiState is NoLibraries, not Content -- currentLibraryId's
      // `(uiState.value as? Content)?.selectedLibraryId` guard cannot supply an id here, and only
      // allIds().firstOrNull() can. A hardcoded fallback (or none at all) would either call
      // shuffle with the wrong library or never call it.
      val fake = FakeLibrarySource(emptyList())
      fake.ids = listOf(7)
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      vm.shuffle()
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(fake.shuffleCalls.last().first).isEqualTo(7)
    }

  @Test
  fun `an up-to-date sync leaves no message`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music)).apply { syncAnswer = { SyncState.UpToDate } }
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).syncMessage).isNull()
  }

  @Test
  fun `a completed reconcile leaves no message`() = runTest(dispatcher) {
    val fake = FakeLibrarySource(listOf(music)).apply {
      syncAnswer = { SyncState.Synced(mapOf(1 to MirrorReplacement(0, 1, 0, 1, 0, 1))) }
    }
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).syncMessage).isNull()
  }

  @Test
  fun `a scan in progress names the Refresh control by the screen's own label, not a promise nothing keeps`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music)).apply { syncAnswer = { SyncState.ScanInProgress } }
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      val message = content(vm).syncMessage
      assertThat(message).isNotNull()
      // The exact string LibraryScreen's Refresh button carries (REFRESH_LABEL), not a re-typed
      // copy that could drift from it -- and specifically not the old, never-kept "will update
      // shortly" promise this replaced. See "Why there is a Refresh action" in this task's brief.
      assertThat(message).contains(REFRESH_LABEL)
      assertThat(message).doesNotContain("will update shortly")
    }

  @Test
  fun `an unreachable server reports failure without naming a control that cannot fix it`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music)).apply { syncAnswer = { SyncState.Failed(IOException("down")) } }
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      assertThat(content(vm).syncMessage)
        .isEqualTo("Could not reach the server. Showing your last synced library.")
    }

  @Test
  fun `refresh calls syncIfStale exactly once on init and once per explicit call, never from browsing`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music, books))
      fake.setAlbums(1, listOf(album("a1", "Music Album", 1)))
      fake.setAlbums(2, listOf(album("a2", "Book Album", 2)))
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(fake.syncCallCount).isEqualTo(1)

      vm.selectLibrary(2)
      vm.search("x")
      vm.shuffle()
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(fake.syncCallCount).isEqualTo(1)

      vm.refresh()
      dispatcher.scheduler.advanceUntilIdle()
      assertThat(fake.syncCallCount).isEqualTo(2)
    }

  @Test
  fun `a syncing message is shown while the check is in flight, before it resolves`() = runTest(dispatcher) {
    val gate = CompletableDeferred<SyncState>()
    val fake = FakeLibrarySource(listOf(music)).apply { syncAnswer = { gate.await() } }
    val vm = warm(fake)
    dispatcher.scheduler.advanceUntilIdle()

    // The coroutine is parked on `gate`, having already set the syncing message -- proves the
    // two-phase sequence (syncing, then the resolved outcome) rather than only the end state.
    assertThat(content(vm).syncMessage).isEqualTo("Checking the server for changes…")

    gate.complete(SyncState.UpToDate)
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(content(vm).syncMessage).isNull()
  }

  @Test
  fun `coverArtUrl forwards the exact art id and size it is given, not a swapped or hardcoded pair`() =
    runTest(dispatcher) {
      val fake = FakeLibrarySource(listOf(music))
      val vm = warm(fake)
      dispatcher.scheduler.advanceUntilIdle()

      val first = vm.coverArtUrl("al-abc", 64)
      val second = vm.coverArtUrl("al-xyz", 256)

      assertThat(fake.coverArtCalls).containsExactly("al-abc" to 64, "al-xyz" to 256)
      assertThat(first).isEqualTo("cover:al-abc:64")
      assertThat(second).isEqualTo("cover:al-xyz:256")
    }
}
