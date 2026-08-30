package app.muplay.book

import androidx.media3.common.MediaMetadata
import app.muplay.media.BookChapter
import app.muplay.media.PlaybackState
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * [BookPlayerViewModel]'s own logic, on the JVM, over the [BookPlayerControls] seam.
 *
 * The fake keeps **the player's own answers separate from the last published snapshot**, because
 * that is the distinction the class exists to respect: `PlaybackConnection` samples on a 250 ms
 * ticker, and a `+30 s` computed from a stale snapshot moves by the wrong amount every time a
 * listener taps twice quickly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookPlayerViewModelTest {

  private val chapters = listOf(
    BookChapter(0, "Prologue", "m4b", 0, 0, 4_000, 0),
    BookChapter(1, "The Long Middle", "m4b", 0, 4_000, 9_000, 4_000),
    BookChapter(2, "A Turn", "m4b", 0, 9_000, 15_000, 9_000),
  )

  private val book = BookSummary(
    bookId = "book", libraryId = 2, title = "Second Book", author = "Second Author",
    coverArtId = "art", fileCount = 1, durationMs = 15_000, positionMs = 0, isFinished = false,
    lastPlayedAtEpochMs = 0L,
  )

  private val otherBook = book.copy(bookId = "other", title = "Another Book", coverArtId = "art-2")

  private class FakeControls : BookPlayerControls {
    val calls = mutableListOf<String>()

    private val published = MutableStateFlow(PlaybackState.NOTHING_PLAYING)
    override val playback: StateFlow<PlaybackState> = published

    private val timer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    override val sleepTimer: StateFlow<SleepTimerState> = timer

    fun publish(state: PlaybackState) {
      published.value = state
    }

    fun publishTimer(state: SleepTimerState) {
      timer.value = state
    }

    /** What the **player** answers, deliberately independent of [playback]'s last snapshot. */
    var playerIsPlaying = false
    var playerMediaId: String? = null
    var playerPositionMs = 0L

    var booksByMediaId: Map<String, String> = emptyMap()
    var booksById: Map<String, BookSummary> = emptyMap()
    var timelines: Map<String, List<BookChapter>> = emptyMap()
    val settings = mutableMapOf<String, MutableStateFlow<BookSettings>>()

    fun settingsFor(bookId: String): MutableStateFlow<BookSettings> =
      settings.getOrPut(bookId) { MutableStateFlow(BookSettings.default(bookId)) }

    override suspend fun connect() {
      calls += "connect"
    }

    override suspend fun isPlaying(): Boolean {
      calls += "isPlaying"
      return playerIsPlaying
    }

    override suspend fun play() {
      calls += "play"
    }

    override suspend fun pause() {
      calls += "pause"
    }

    override suspend fun currentMediaId(): String? = playerMediaId

    override suspend fun currentPositionMs(): Long = playerPositionMs

    override suspend fun seekTo(positionMs: Long) {
      calls += "seekTo($positionMs)"
    }

    override suspend fun seekTo(itemIndex: Int, positionMs: Long) {
      calls += "seekTo($itemIndex, $positionMs)"
    }

    override suspend fun setSpeed(speed: Float) {
      calls += "setSpeed($speed)"
    }

    override fun startSleepTimer(request: SleepTimerRequest) {
      calls += "startSleepTimer($request)"
    }

    override fun cancelSleepTimer() {
      calls += "cancelSleepTimer"
    }

    override suspend fun bookIdFor(mediaId: String): String? = booksByMediaId[mediaId]

    override suspend fun book(bookId: String): BookSummary? = booksById[bookId]

    override suspend fun timeline(bookId: String): List<BookChapter> = timelines[bookId].orEmpty()

    override fun observeSettings(bookId: String): Flow<BookSettings> = settingsFor(bookId)

    override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
      "https://host/art?id=$coverArtId&size=$sizePx"
  }

  private val dispatcher = StandardTestDispatcher()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun TestScope.warm(controls: BookPlayerControls): BookPlayerViewModel {
    val viewModel = BookPlayerViewModel(controls)
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
  }

  private fun controls(): FakeControls = FakeControls().apply {
    booksByMediaId = mapOf("m4b" to "book", "p1" to "other")
    booksById = mapOf("book" to book, "other" to otherBook)
    timelines = mapOf("book" to chapters, "other" to emptyList())
  }

  private fun playing(mediaId: String, positionMs: Long, speed: Float = 1.0f) =
    PlaybackState.NOTHING_PLAYING.copy(
      isPlaying = true, mediaId = mediaId, title = mediaId, positionMs = positionMs,
      durationMs = 15_000, mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, speed = speed,
    )

  private fun BookPlayerViewModel.content(): BookPlayerUiState.Content =
    uiState.value as BookPlayerUiState.Content

  @Test
  fun `before anything plays the screen says nothing is playing`() = runTest(dispatcher) {
    val viewModel = warm(controls())
    advanceUntilIdle()

    assertThat(viewModel.uiState.value).isEqualTo(BookPlayerUiState.NothingPlaying)
  }

  /**
   * Connecting is what starts the state flowing at all. Without it the screen renders
   * `NothingPlaying` forever while audio is audibly playing -- a bug with no test to fail, since
   * every other assertion here publishes state through the fake directly.
   */
  @Test
  fun `constructing the view model connects to the session`() = runTest(dispatcher) {
    val controls = controls()
    warm(controls)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("connect")
  }

  @Test
  fun `a playing book resolves its own summary, chapters and settings`() = runTest(dispatcher) {
    val controls = controls()
    controls.settingsFor("book").value = BookSettings("book", 1.4f, skipSilence = true)
    val viewModel = warm(controls)

    controls.publish(playing("m4b", 5_000, speed = 1.4f))
    advanceUntilIdle()

    val content = viewModel.content()
    assertThat(content.bookTitle).isEqualTo("Second Book")
    assertThat(content.chapterTitle).isEqualTo("The Long Middle")
    assertThat(content.chapterCount).isEqualTo(3)
    assertThat(content.skipSilence).isTrue
    assertThat(content.speed).isEqualTo(1.4f)
  }

  @Test
  fun `a track that is not an audiobook leaves the book player with nothing to show`() =
    runTest(dispatcher) {
      // Reachable with this screen still on the back stack: a listener opens the book player, then
      // starts a song from a car or a watch. Folding this into "nothing is loaded" is what makes
      // a `book!!` look safe.
      val controls = controls()
      val viewModel = warm(controls)

      controls.publish(playing("a-song", 1_000))
      advanceUntilIdle()

      assertThat(viewModel.uiState.value).isEqualTo(BookPlayerUiState.NothingPlaying)
    }

  @Test
  fun `a book that stops playing clears the book rather than leaving the last one on screen`() =
    runTest(dispatcher) {
      val controls = controls()
      val viewModel = warm(controls)

      controls.publish(playing("m4b", 5_000))
      advanceUntilIdle()
      assertThat(viewModel.uiState.value).isInstanceOf(BookPlayerUiState.Content::class.java)

      controls.publish(PlaybackState.NOTHING_PLAYING)
      advanceUntilIdle()
      assertThat(viewModel.uiState.value).isEqualTo(BookPlayerUiState.NothingPlaying)
    }

  /**
   * `collectLatest`, not `collect`, on the outer media-id flow. The inner `observeSettings`
   * collection never returns, so a plain `collect` leaves the previous book's collector alive and
   * the two fight over one field every time a listener moves between books -- visible as a speed
   * that flickers back to the other book's whenever that row is written.
   */
  @Test
  fun `switching books stops listening to the previous book's settings`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)

    controls.publish(playing("m4b", 1_000))
    advanceUntilIdle()
    controls.publish(playing("p1", 0))
    advanceUntilIdle()
    assertThat(viewModel.content().bookTitle).isEqualTo("Another Book")

    // The FIRST book's row changes, after the switch. A surviving collector writes it into the
    // field the second book is being rendered from.
    controls.settingsFor("book").value = BookSettings("book", 2.5f, skipSilence = true)
    advanceUntilIdle()

    assertThat(viewModel.content().skipSilence).isFalse
  }

  @Test
  fun `play pause asks the player what it is doing, not the last sampled snapshot`() =
    runTest(dispatcher) {
      // The two are set to disagree. `PlaybackConnection` samples on a 250 ms ticker, so a second
      // tap inside that window against a stale snapshot toggles the wrong way -- the classic
      // "pause, tap again, and it pauses again".
      val controls = controls()
      val viewModel = warm(controls)
      controls.publish(playing("m4b", 1_000))
      controls.playerIsPlaying = false
      advanceUntilIdle()
      controls.calls.clear()

      viewModel.playPause()
      advanceUntilIdle()

      assertThat(controls.calls).containsExactly("isPlaying", "play")
    }

  @Test
  fun `play pause pauses a playing player`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    controls.playerIsPlaying = true
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.playPause()
    advanceUntilIdle()

    // `containsExactly`: an implementation that called both would satisfy a `contains("pause")`
    // while making the button do nothing visible.
    assertThat(controls.calls).containsExactly("isPlaying", "pause")
  }

  @Test
  fun `next chapter seeks to the start of the following chapter`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    controls.publish(playing("m4b", 5_000))
    controls.playerMediaId = "m4b"
    controls.playerPositionMs = 5_000
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.nextChapter()
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("seekTo(0, 9000)")
  }

  @Test
  fun `previous chapter restarts this chapter before it leaves it`() = runTest(dispatcher) {
    // Two observations of one control, and they are the whole reason `previous` takes a position.
    // Well into a chapter it restarts that chapter; just inside its start it goes back one.
    val controls = controls()
    val viewModel = warm(controls)
    controls.publish(playing("m4b", 8_000))
    controls.playerMediaId = "m4b"
    controls.playerPositionMs = 8_000
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.previousChapter()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(0, 4000)")

    controls.calls.clear()
    controls.playerPositionMs = 4_100
    viewModel.previousChapter()
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(0, 0)")
  }

  @Test
  fun `chapter navigation with nothing loaded touches the player not at all`() =
    runTest(dispatcher) {
      val controls = controls()
      val viewModel = warm(controls)
      advanceUntilIdle()
      controls.calls.clear()

      viewModel.nextChapter()
      viewModel.previousChapter()
      advanceUntilIdle()

      assertThat(controls.calls).isEmpty()
    }

  @Test
  fun `next chapter at the end of the book seeks nowhere`() = runTest(dispatcher) {
    // `BookTimeline.next` answers null past the last chapter, and a `!!` there is a crash the
    // listener reaches by finishing a book.
    val controls = controls()
    val viewModel = warm(controls)
    controls.publish(playing("m4b", 14_000))
    controls.playerMediaId = "m4b"
    controls.playerPositionMs = 14_000
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.nextChapter()
    advanceUntilIdle()

    assertThat(controls.calls).isEmpty()
  }

  @Test
  fun `tapping a chapter seeks to that chapter's own item and offset`() = runTest(dispatcher) {
    // Two disjoint observations: a `seekTo` that passed the first chapter, or a constant zero
    // offset, looks correct on screen and sends every listener to the prologue.
    val controls = controls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.seekTo(chapters[2])
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(0, 9000)")

    controls.calls.clear()
    viewModel.seekTo(chapters[0])
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(0, 0)")
  }

  /**
   * The nudge reads the **player's** position, not the position inside the chapter that `uiState`
   * carries. A book's chapter rarely starts at zero, so nudging from the in-chapter number is off
   * by the chapter's own start on every chapter but the first -- silently, and only on books whose
   * chapters are tagged. The fixture below has the two disagree by 4 000 ms for that reason.
   */
  @Test
  fun `a nudge moves the player's own position, forwards and back`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    controls.publish(playing("m4b", 5_000))
    controls.playerMediaId = "m4b"
    controls.playerPositionMs = 5_000
    advanceUntilIdle()
    assertThat(viewModel.content().positionInChapterMs).isEqualTo(1_000L)
    controls.calls.clear()

    viewModel.nudge(30_000L)
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(35000)")

    controls.calls.clear()
    viewModel.nudge(-30_000L)
    advanceUntilIdle()
    assertThat(controls.calls).containsExactly("seekTo(0)")
  }

  @Test
  fun `a nudge back past the start of the item is clamped to zero`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    controls.playerPositionMs = 1_000
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.nudge(-30_000L)
    advanceUntilIdle()

    // Not -29 000. `MediaController.seekTo` with a negative is undefined.
    assertThat(controls.calls).containsExactly("seekTo(0)")
  }

  @Test
  fun `the speed reaches the player, clamped`() = runTest(dispatcher) {
    // Three observations: one inside the range, one over the maximum, and a NaN. The NaN is not
    // decoration -- `ExoPlayer.setPlaybackSpeed(NaN)` throws from inside a listener callback and
    // surfaces as playback dying with no message a listener could act on.
    val controls = controls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.setSpeed(1.4f)
    viewModel.setSpeed(9.0f)
    viewModel.setSpeed(Float.NaN)
    advanceUntilIdle()

    assertThat(controls.calls).containsExactly("setSpeed(1.4)", "setSpeed(3.0)", "setSpeed(1.0)")
  }

  @Test
  fun `the sleep timer's requests and cancellation go straight through`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.startSleepTimer(SleepTimerRequest.Duration(900_000L))
    viewModel.cancelSleepTimer()

    assertThat(controls.calls).containsExactly(
      "startSleepTimer(${SleepTimerRequest.Duration(900_000L)})",
      "cancelSleepTimer",
    )
  }

  @Test
  fun `the sleep timer's state reaches the screen`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    controls.publish(playing("m4b", 5_000))
    advanceUntilIdle()

    controls.publishTimer(SleepTimerState.Running(90_000L, untilEndOfChapter = true, isFading = false))
    advanceUntilIdle()

    assertThat(viewModel.content().sleepTimer)
      .isEqualTo(SleepTimerState.Running(90_000L, untilEndOfChapter = true, isFading = false))
  }

  @Test
  fun `end of chapter asks the timer for the end of the chapter being listened to`() =
    runTest(dispatcher) {
      // Two observations from two positions in the same file, because with one chapter "the end of
      // this chapter" and "the end of the file" are the same number.
      val controls = controls()
      val viewModel = warm(controls)
      controls.publish(playing("m4b", 5_000))
      controls.playerMediaId = "m4b"
      controls.playerPositionMs = 5_000
      advanceUntilIdle()
      controls.calls.clear()

      viewModel.endOfChapterTimer()
      advanceUntilIdle()
      assertThat(controls.calls)
        .containsExactly("startSleepTimer(${SleepTimerRequest.UntilPosition("m4b", 9_000L)})")

      controls.calls.clear()
      controls.playerPositionMs = 10_000
      viewModel.endOfChapterTimer()
      advanceUntilIdle()
      assertThat(controls.calls)
        .containsExactly("startSleepTimer(${SleepTimerRequest.UntilPosition("m4b", 15_000L)})")
    }

  @Test
  fun `the cover art url is the controls', for the id and size asked for`() = runTest(dispatcher) {
    // Two disjoint observations: a provider that ignored either argument passes one of them.
    val viewModel = warm(controls())

    assertThat(viewModel.coverArtUrl("art", 512)).isEqualTo("https://host/art?id=art&size=512")
    assertThat(viewModel.coverArtUrl("art-2", 128)).isEqualTo("https://host/art?id=art-2&size=128")
  }

  @Test
  fun `end of chapter with nothing playing starts no timer`() = runTest(dispatcher) {
    val controls = controls()
    val viewModel = warm(controls)
    advanceUntilIdle()
    controls.calls.clear()

    viewModel.endOfChapterTimer()
    advanceUntilIdle()

    assertThat(controls.calls).isEmpty()
  }

  @Test
  fun `end of chapter on a book whose chapters have not arrived starts no timer`() =
    runTest(dispatcher) {
      // Chapter extraction is an HTTP round trip per file, so this is a second or so of every
      // book's life -- and a `!!` on the chapter lookup is a crash reachable by tapping quickly.
      val controls = controls()
      val viewModel = warm(controls)
      controls.publish(playing("p1", 0))
      controls.playerMediaId = "p1"
      advanceUntilIdle()
      controls.calls.clear()

      viewModel.endOfChapterTimer()
      advanceUntilIdle()

      assertThat(controls.calls).isEmpty()
    }
}
