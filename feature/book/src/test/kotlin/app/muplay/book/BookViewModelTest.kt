package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * [BookViewModel]'s own logic, on the JVM, over the [BookSource] seam.
 *
 * The two things worth reading twice are `playChapter`'s **order** -- queue first, then seek --
 * and the fact that every action is a no-op before [BookViewModel.load] has been called, which is
 * a real window: `LaunchedEffect` runs after the first composition, and the screen is tappable in
 * between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelTest {

  private fun book(id: String) = BookSummary(
    bookId = id, libraryId = 2, title = "Title of $id", author = "$id Author", coverArtId = "art-$id",
    fileCount = 2, durationMs = 10_000, positionMs = 0, isFinished = false,
    lastPlayedAtEpochMs = 0L,
  )

  private val wantedChapters = listOf(
    BookChapter(0, "One", "p1", 0, 0, 4_000, 0),
    BookChapter(1, "Two", "m4b", 1, 4_000, 9_000, 4_000),
  )

  private class FakeBookSource : BookSource {
    val calls = mutableListOf<String>()
    val shelf = MutableStateFlow<List<BookSummary>>(emptyList())
    val settings = mutableMapOf<String, MutableStateFlow<BookSettings>>()
    var timelines: Map<String, List<BookChapter>> = emptyMap()

    fun settingsFor(bookId: String): MutableStateFlow<BookSettings> =
      settings.getOrPut(bookId) { MutableStateFlow(BookSettings.default(bookId)) }

    override fun bookshelf(): Flow<List<BookSummary>> = shelf

    override fun observeSettings(bookId: String): Flow<BookSettings> = settingsFor(bookId)

    override suspend fun timeline(bookId: String): List<BookChapter> {
      calls += "timeline($bookId)"
      return timelines[bookId].orEmpty()
    }

    override suspend fun resume(bookId: String) {
      calls += "resume($bookId)"
    }

    override suspend fun restart(bookId: String) {
      calls += "restart($bookId)"
    }

    override suspend fun playFile(bookId: String, mediaId: String) {
      calls += "playFile($bookId, $mediaId)"
    }

    override suspend fun seekTo(itemIndex: Int, positionMs: Long) {
      calls += "seekTo($itemIndex, $positionMs)"
    }

    override suspend fun setSpeed(bookId: String, speed: Float) {
      calls += "setSpeed($bookId, $speed)"
    }

    override suspend fun setSkipSilence(bookId: String, enabled: Boolean) {
      calls += "setSkipSilence($bookId, $enabled)"
    }

    override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String {
      calls += "coverArtUrl($coverArtId, $sizePx)"
      return "https://host/art?id=$coverArtId&size=$sizePx"
    }
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.warm(source: BookSource): BookViewModel {
    val viewModel = BookViewModel(source)
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
  }

  private fun source(): FakeBookSource = FakeBookSource().apply {
    shelf.value = listOf(book("other"), book("wanted"))
    timelines = mapOf("wanted" to wantedChapters)
  }

  @Test
  fun `before a book is loaded the screen is loading, not not-found`() = runTest(dispatcher) {
    // A view model that answered `NotFound` here would tell every listener their book was gone
    // for the frame between the first composition and `LaunchedEffect` running.
    val viewModel = warm(source())
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(BookUiState.Loading)
  }

  @Test
  fun `loading a book shows that book and reads its chapters`() = runTest(dispatcher) {
    val source = source()
    val viewModel = warm(source)

    viewModel.load("wanted")
    advanceUntilIdle()

    val content = viewModel.uiState.value as BookUiState.Content
    assertThat(content.book.bookId).isEqualTo("wanted")
    assertThat(content.chapters).isEqualTo(wantedChapters)
    assertThat(source.calls).containsExactly("timeline(wanted)")
  }

  @Test
  fun `a book the shelf does not have is not found`() = runTest(dispatcher) {
    val viewModel = warm(source())

    viewModel.load("missing")
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(BookUiState.NotFound)
  }

  @Test
  fun `loading the same book twice never reads its chapters a second time`() = runTest(dispatcher) {
    // The early return in `load`. A recomposition re-running `LaunchedEffect` with the same id
    // must not throw the extracted chapters away and pay another HTTP round trip per file.
    val source = source()
    val viewModel = warm(source)

    viewModel.load("wanted")
    advanceUntilIdle()
    viewModel.load("wanted")
    advanceUntilIdle()

    assertThat(source.calls).containsExactly("timeline(wanted)")
  }

  @Test
  fun `moving to a second book never shows the first book's chapters under it`() =
    runTest(dispatcher) {
      // One view model serves every book (no `ViewModelStoreNavEntryDecorator` is installed, so
      // `hiltViewModel()` resolves against the Activity's store). Without clearing the chapters
      // before the id changes, book B renders with book A's chapter list until the read returns.
      val source = source()
      source.timelines = mapOf("wanted" to wantedChapters, "other" to emptyList())
      val viewModel = warm(source)

      viewModel.load("wanted")
      advanceUntilIdle()
      assertThat((viewModel.uiState.value as BookUiState.Content).chapters).isEqualTo(wantedChapters)

      viewModel.load("other")
      advanceUntilIdle()
      val content = viewModel.uiState.value as BookUiState.Content
      assertThat(content.book.bookId).isEqualTo("other")
      assertThat(content.chapters).isEmpty()
    }

  @Test
  fun `the settings shown are the loaded book's, and they follow the row`() = runTest(dispatcher) {
    // Two observations of one flow: a `combine` wired to a constant, or one that latched onto the
    // first emission, passes the first assertion and fails the second.
    val source = source()
    val viewModel = warm(source)

    viewModel.load("wanted")
    advanceUntilIdle()
    assertThat((viewModel.uiState.value as BookUiState.Content).settings.speed).isEqualTo(1.0f)

    source.settingsFor("wanted").value = BookSettings("wanted", 1.6f, skipSilence = true)
    advanceUntilIdle()
    val settings = (viewModel.uiState.value as BookUiState.Content).settings
    assertThat(settings.speed).isEqualTo(1.6f)
    assertThat(settings.skipSilence).isTrue
  }

  @Test
  fun `resume and restart each ask for their own thing, on the loaded book`() = runTest(dispatcher) {
    // Two one-line delegating methods, which is exactly where a copy-paste swap survives every
    // coverage gate: both run, both are covered, and "Start from the beginning" resumes.
    val source = source()
    val viewModel = warm(source)
    viewModel.load("wanted")
    advanceUntilIdle()
    source.calls.clear()

    viewModel.resume()
    advanceUntilIdle()
    assertThat(source.calls).containsExactly("resume(wanted)")

    source.calls.clear()
    viewModel.restart()
    advanceUntilIdle()
    assertThat(source.calls).containsExactly("restart(wanted)")
  }

  @Test
  fun `playing a chapter sets the queue first and then seeks into it`() = runTest(dispatcher) {
    // The order is the assertion. A seek issued before the queue is set lands in whatever was
    // playing before -- and on a single-file M4B, where every chapter shares one media id, the
    // seek is the entire operation, so dropping it sends every tap to the resume position.
    val source = source()
    val viewModel = warm(source)
    viewModel.load("wanted")
    advanceUntilIdle()
    source.calls.clear()

    viewModel.playChapter(wantedChapters[1])
    advanceUntilIdle()

    assertThat(source.calls).containsExactly("playFile(wanted, m4b)", "seekTo(1, 4000)")
  }

  @Test
  fun `playing a different chapter names that chapter`() = runTest(dispatcher) {
    // Two disjoint observations of the same call. A `playChapter` that passed the first chapter,
    // or a constant zero offset, passes the test above and fails this one.
    val source = source()
    val viewModel = warm(source)
    viewModel.load("wanted")
    advanceUntilIdle()
    source.calls.clear()

    viewModel.playChapter(wantedChapters[0])
    advanceUntilIdle()

    assertThat(source.calls).containsExactly("playFile(wanted, p1)", "seekTo(0, 0)")
  }

  @Test
  fun `the speed and skip-silence writes name the loaded book and the value asked for`() =
    runTest(dispatcher) {
      val source = source()
      val viewModel = warm(source)
      viewModel.load("wanted")
      advanceUntilIdle()
      source.calls.clear()

      viewModel.setSpeed(1.4f)
      viewModel.setSkipSilence(true)
      advanceUntilIdle()

      assertThat(source.calls)
        .containsExactly("setSpeed(wanted, 1.4)", "setSkipSilence(wanted, true)")
    }

  @Test
  fun `an action before a book is loaded touches nothing rather than throwing`() =
    runTest(dispatcher) {
      // The window is real: `LaunchedEffect` runs after the first composition, and the screen is
      // on-screen and tappable in between. The alternative shape -- `bookId.value!!` -- crashes
      // exactly there and nowhere else.
      val source = source()
      val viewModel = warm(source)

      viewModel.resume()
      viewModel.restart()
      viewModel.playChapter(wantedChapters[0])
      viewModel.setSpeed(1.4f)
      viewModel.setSkipSilence(true)
      advanceUntilIdle()

      assertThat(source.calls).isEmpty()
    }

  @Test
  fun `the cover art url is the source's, for the id and size asked for`() = runTest(dispatcher) {
    val source = source()
    val viewModel = warm(source)

    assertThat(viewModel.coverArtUrl("art-wanted", 512))
      .isEqualTo("https://host/art?id=art-wanted&size=512")
    assertThat(viewModel.coverArtUrl("art-other", 128))
      .isEqualTo("https://host/art?id=art-other&size=128")
  }
}
