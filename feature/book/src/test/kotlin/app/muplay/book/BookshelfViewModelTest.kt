package app.muplay.book

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
 * [BookshelfViewModel]'s own logic, on the JVM.
 *
 * Reachable at all because the view model is built over [BookshelfSource] rather than over
 * `AudiobookRepository`/`BrowseRepository`/`BookPlaybackLauncher` directly -- the same seam, for
 * the same stated reason, as `:feature:library`'s `LibrarySource` and `:feature:player`'s
 * `PlaybackControls`: all three are concrete `@Inject`-constructed classes over Room or a bound
 * `MediaSession`, and this project bans mock frameworks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

  private fun book(id: String, started: Boolean) = BookSummary(
    bookId = id, libraryId = 2, title = "Title of $id", author = "$id Author", coverArtId = "art-$id",
    fileCount = 1, durationMs = 1_000, positionMs = if (started) 500 else 0, isFinished = false,
    lastPlayedAtEpochMs = if (started) 1L else 0L,
  )

  /** Records every call in order, with its argument; order is as much of the contract as value. */
  private class FakeBookshelfSource : BookshelfSource {
    val calls = mutableListOf<String>()
    val shelf = MutableStateFlow<List<BookSummary>>(emptyList())

    override fun bookshelf(): Flow<List<BookSummary>> = shelf

    override suspend fun resume(bookId: String) {
      calls += "resume($bookId)"
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

  /**
   * `uiState` is a `stateIn(WhileSubscribed(..))`, so it produces nothing without an active
   * collector -- the same reason every other view-model test in this codebase has a `warm`.
   */
  private fun TestScope.warm(source: BookshelfSource): BookshelfViewModel {
    val viewModel = BookshelfViewModel(source)
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
  }

  @Test
  fun `before the first query returns the shelf is loading, not empty`() {
    // The initial value, and the one assertion that would go unnoticed if it were wrong: an empty
    // shelf and an un-loaded shelf render different screens, and getting this backwards tells
    // every listener they have no audiobooks for as long as the first Room query takes.
    val viewModel = BookshelfViewModel(FakeBookshelfSource())

    assertThat(viewModel.uiState.value).isEqualTo(BookshelfUiState.Loading)
  }

  @Test
  fun `the shelf renders whatever the repository emits, in its order`() = runTest(dispatcher) {
    val source = FakeBookshelfSource()
    val viewModel = warm(source)

    source.shelf.value = listOf(book("zed", started = true), book("alpha", started = false))
    advanceUntilIdle()

    val content = viewModel.uiState.value as BookshelfUiState.Content
    assertThat(content.books.map { it.bookId }).containsExactly("zed", "alpha")
  }

  @Test
  fun `a shelf that empties again says so`() = runTest(dispatcher) {
    // A second, disjoint observation. A `uiState` that latched onto the first non-empty emission
    // passes the test above and fails this one -- and on a device this is what a listener sees
    // after the library that held their books loses its audiobook role.
    val source = FakeBookshelfSource()
    val viewModel = warm(source)

    source.shelf.value = listOf(book("zed", started = true))
    advanceUntilIdle()
    assertThat(viewModel.uiState.value).isInstanceOf(BookshelfUiState.Content::class.java)

    source.shelf.value = emptyList()
    advanceUntilIdle()
    assertThat(viewModel.uiState.value).isEqualTo(BookshelfUiState.Empty)
  }

  @Test
  fun `resuming names the book it was asked for`() = runTest(dispatcher) {
    // Two books, the second resumed. A `resume` that always passed the first shelf row's id -- the
    // ordinary version of this bug -- looks completely correct on screen.
    val source = FakeBookshelfSource()
    val viewModel = warm(source)
    source.shelf.value = listOf(book("zed", started = true), book("alpha", started = true))
    advanceUntilIdle()

    viewModel.resume("alpha")
    advanceUntilIdle()

    assertThat(source.calls).containsExactly("resume(alpha)")
  }

  @Test
  fun `the cover art url is the source's, for the id and size asked for`() = runTest(dispatcher) {
    // Two disjoint observations: a provider that ignored either argument passes one of them.
    val source = FakeBookshelfSource()
    val viewModel = warm(source)

    assertThat(viewModel.coverArtUrl("art-zed", 128)).isEqualTo("https://host/art?id=art-zed&size=128")
    assertThat(viewModel.coverArtUrl("art-alpha", 512))
      .isEqualTo("https://host/art?id=art-alpha&size=512")
  }
}
