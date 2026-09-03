package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import java.io.IOException
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

  /**
   * The seam, and -- for the chapter read alone -- a small working model of what is behind it.
   *
   * [serverDown] is the server; the private `remembered` field is `ChapterRepository`'s in-memory
   * record of a probe that threw. The model is worth its six lines because the interesting
   * property is not "does the view model catch" but **"does its retry actually reach the
   * server"**: a `retryChapters` wired to [timeline] rather than [retryTimeline] is handed the
   * remembered failure and cannot succeed however healthy the server is, and only a fake that
   * remembers can fail that.
   */
  private class FakeBookSource : BookSource {
    val calls = mutableListOf<String>()
    val shelf = MutableStateFlow<List<BookSummary>>(emptyList())
    val settings = mutableMapOf<String, MutableStateFlow<BookSettings>>()
    var timelines: Map<String, List<BookChapter>> = emptyMap()

    /** Flip it, and every read of a file's `moov` atom fails, as an unreachable server does. */
    var serverDown = false
    private var remembered: Throwable? = null

    fun settingsFor(bookId: String): MutableStateFlow<BookSettings> =
      settings.getOrPut(bookId) { MutableStateFlow(BookSettings.default(bookId)) }

    override fun bookshelf(): Flow<List<BookSummary>> = shelf

    override fun observeSettings(bookId: String): Flow<BookSettings> = settingsFor(bookId)

    override suspend fun timeline(bookId: String): List<BookChapter> {
      calls += "timeline($bookId)"
      remembered?.let { throw it }
      return read(bookId)
    }

    override suspend fun retryTimeline(bookId: String): List<BookChapter> {
      calls += "retryTimeline($bookId)"
      // `ChapterRepository.forgetFailures()`, which is the entire difference between the two.
      remembered = null
      return read(bookId)
    }

    private fun read(bookId: String): List<BookChapter> {
      if (serverDown) {
        val failure = IOException("the server is not reachable")
        remembered = failure
        throw failure
      }
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
    assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Ready(wantedChapters))
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
      assertThat((viewModel.uiState.value as BookUiState.Content).chapters)
        .isEqualTo(BookUiState.Chapters.Ready(wantedChapters))

      viewModel.load("other")
      advanceUntilIdle()
      val content = viewModel.uiState.value as BookUiState.Content
      assertThat(content.book.bookId).isEqualTo("other")
      assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Ready(emptyList()))
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
  fun `a chapter read that throws leaves the book on screen instead of killing the app`() =
    runTest(dispatcher) {
      // THE regression. `load` used to be `viewModelScope.launch { chapters.value =
      // source.timeline(id) }` with no `catch`, and `ChapterReader.read` throws
      // `ExecutionException`/`TimeoutException` whenever the server cannot be reached. An
      // exception out of a bare `launch` reaches the thread's default uncaught handler --
      // `viewModelScope`'s `SupervisorJob` keeps the *scope* alive, not the process -- so opening
      // a book with the server asleep killed MuPlay.
      //
      // **What this test sees is the state, not the kill**, and that was measured rather than
      // assumed: with the `catch` removed it goes red as `expected: Unavailable but was: Reading`,
      // not as a reported uncaught exception. `runTest` never sees the throw at all, because
      // `viewModelScope` is not a child of the test coroutine -- on the JVM the exception is
      // printed and dropped, and the chapter state is simply never assigned. That is the same
      // defect from the only side a fast-tier test can stand on: the assignment that does not
      // happen is exactly what the process death interrupts.
      val source = source()
      source.serverDown = true
      val viewModel = warm(source)

      viewModel.load("wanted")
      advanceUntilIdle()

      val content = viewModel.uiState.value as BookUiState.Content
      assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Unavailable)
      // Everything that did not come from the chapter read is still there and still right.
      assertThat(content.book.title).isEqualTo("Title of wanted")
      assertThat(content.settings.speed).isEqualTo(1.0f)
    }

  @Test
  fun `Resume still works while the chapters are unavailable`() = runTest(dispatcher) {
    // The reason a failed chapter read is a sentence inside the screen rather than a screen: the
    // one thing a listener came for needs no chapters at all. A fix that had published `NotFound`,
    // or a screen-level error state, would take this away and pass every assertion above.
    val source = source()
    source.serverDown = true
    val viewModel = warm(source)
    viewModel.load("wanted")
    advanceUntilIdle()
    source.calls.clear()

    viewModel.resume()
    viewModel.playChapter(wantedChapters[1])
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isInstanceOf(BookUiState.Content::class.java)
    assertThat(source.calls)
      .containsExactly("resume(wanted)", "playFile(wanted, m4b)", "seekTo(1, 4000)")
  }

  @Test
  fun `retrying after the server comes back reads the chapters through the retry path`() =
    runTest(dispatcher) {
      // Two assertions, and the second is the one with teeth. A retry that re-rendered without
      // re-reading leaves `Unavailable`; a retry that called `timeline` re-reads but is handed the
      // failure `ChapterRepository` remembered -- which is exactly why that memory exists -- and
      // also leaves `Unavailable`. Only a retry that goes through `retryTimeline`, which forgets
      // the remembered failure first, can reach a server that has come back.
      val source = source()
      source.serverDown = true
      val viewModel = warm(source)
      viewModel.load("wanted")
      advanceUntilIdle()
      assertThat((viewModel.uiState.value as BookUiState.Content).chapters)
        .isEqualTo(BookUiState.Chapters.Unavailable)

      source.serverDown = false
      source.calls.clear()
      viewModel.retryChapters()
      advanceUntilIdle()

      assertThat((viewModel.uiState.value as BookUiState.Content).chapters)
        .isEqualTo(BookUiState.Chapters.Ready(wantedChapters))
      assertThat(source.calls).containsExactly("retryTimeline(wanted)")
    }

  @Test
  fun `a retry that fails again is unavailable again rather than a crash`() = runTest(dispatcher) {
    // The retry runs in the same bare `launch` as the first read, so it needs the same `catch`;
    // a fix that guarded only `load` crashes here instead, on the second press.
    val source = source()
    source.serverDown = true
    val viewModel = warm(source)
    viewModel.load("wanted")
    advanceUntilIdle()
    source.calls.clear()

    viewModel.retryChapters()
    advanceUntilIdle()

    assertThat((viewModel.uiState.value as BookUiState.Content).chapters)
      .isEqualTo(BookUiState.Chapters.Unavailable)
    assertThat(source.calls).containsExactly("retryTimeline(wanted)")
  }

  @Test
  fun `a retry before a book is loaded touches nothing`() = runTest(dispatcher) {
    // Same window, same guard as the five actions above: `LaunchedEffect` runs after the first
    // composition, and this screen is tappable in between.
    val source = source()
    val viewModel = warm(source)

    viewModel.retryChapters()
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
