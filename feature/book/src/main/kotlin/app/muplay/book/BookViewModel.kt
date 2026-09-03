package app.muplay.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.AudiobookRepository
import app.muplay.database.BrowseRepository
import app.muplay.media.BookChapter
import app.muplay.media.PlaybackConnection
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What one book's screen needs. A seam, for the reason [BookshelfSource] documents.
 *
 * [playFile] and [seekTo] are two members and not one `playChapter`, deliberately. Composing them
 * is the single place where a ripped book and a single-file M4B differ, and putting that decision
 * on the interface would move it into the un-testable adapter -- the "verified at a different
 * layer from where it is applied" defect this project records by name.
 */
interface BookSource {

  fun bookshelf(): Flow<List<BookSummary>>

  fun observeSettings(bookId: String): Flow<BookSettings>

  suspend fun timeline(bookId: String): List<BookChapter>

  /**
   * The same read, after a failure, with whatever the last failure was remembered downstream
   * dropped first. Two members and not a boolean parameter, for the reason [playFile] and [seekTo]
   * are two members: the caller states which of the two things it means.
   */
  suspend fun retryTimeline(bookId: String): List<BookChapter>

  suspend fun resume(bookId: String)

  suspend fun restart(bookId: String)

  /** Sets the book's queue and starts it on one file. The position is the resume policy's. */
  suspend fun playFile(bookId: String, mediaId: String)

  /** A seek inside the loaded queue: which item, and how far into it. */
  suspend fun seekTo(itemIndex: Int, positionMs: Long)

  suspend fun setSpeed(bookId: String, speed: Float)

  suspend fun setSkipSilence(bookId: String, enabled: Boolean)

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String
}

/**
 * One book: what it is, how far through it the listener is, its chapters, and the four things
 * they can do to it.
 *
 * **[load] takes the book id rather than this class reading a `SavedStateHandle`**, which is what
 * the plan's listing specified. That was measured wrong on a device for `AlbumViewModel` and the
 * transcript is in its KDoc: `MuPlayApp`'s `NavDisplay` wires no entry-scoped argument source, so
 * Navigation 3 populates nothing from a `NavKey`'s own properties and
 * `savedStateHandle["bookId"]` is `null`. `BookScreen` takes `bookId` as an ordinary parameter and
 * forwards it from a `LaunchedEffect`, exactly as `AlbumScreen` does.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModel(private val source: BookSource) : ViewModel() {

  @Inject
  constructor(
    audiobookRepository: AudiobookRepository,
    browseRepository: BrowseRepository,
    timelineReader: BookTimelineReader,
    launcher: BookPlaybackLauncher,
    playbackConnection: PlaybackConnection,
  ) : this(
    object : BookSource {
      override fun bookshelf(): Flow<List<BookSummary>> = audiobookRepository.bookshelf()

      override fun observeSettings(bookId: String): Flow<BookSettings> =
        audiobookRepository.observeSettings(bookId)

      override suspend fun timeline(bookId: String): List<BookChapter> =
        timelineReader.timeline(bookId)

      override suspend fun retryTimeline(bookId: String): List<BookChapter> =
        timelineReader.reread(bookId)

      override suspend fun resume(bookId: String) = launcher.resume(bookId)

      override suspend fun restart(bookId: String) = launcher.restart(bookId)

      override suspend fun playFile(bookId: String, mediaId: String) =
        launcher.playFile(bookId, mediaId)

      override suspend fun seekTo(itemIndex: Int, positionMs: Long) {
        playbackConnection.controller().seekTo(itemIndex, positionMs)
      }

      override suspend fun setSpeed(bookId: String, speed: Float) =
        audiobookRepository.setSpeed(bookId, speed)

      override suspend fun setSkipSilence(bookId: String, enabled: Boolean) =
        audiobookRepository.setSkipSilence(bookId, enabled)

      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
    },
  )

  private val bookId = MutableStateFlow<String?>(null)
  private val chapters = MutableStateFlow<BookUiState.Chapters>(BookUiState.Chapters.Reading)

  /**
   * `flatMapLatest` over the id, for the reason `AlbumViewModel`'s own comment gives: this app
   * installs no `ViewModelStoreNavEntryDecorator`, so `hiltViewModel()` inside a `NavDisplay`
   * entry resolves against the *Activity's* store and one instance serves every book. Book A ->
   * back -> book B runs [load] on an instance that still holds A's chapters, and without the
   * switch the new book's shelf row would be combined with the old book's settings flow.
   */
  val uiState: StateFlow<BookUiState> = bookId.flatMapLatest { id ->
    if (id == null) {
      flowOf(BookUiState.Loading)
    } else {
      combine(source.bookshelf(), chapters, source.observeSettings(id)) { books, timeline, settings ->
        bookUiState(books, id, timeline, settings)
      }
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BookUiState.Loading)

  /**
   * Called once from `BookScreen`'s `LaunchedEffect(bookId)`.
   *
   * The early return is the same belt `AlbumViewModel.load` carries and covers the same case: a
   * recomposition that re-runs this with the book already on screen must not throw the extracted
   * chapters away and re-read them over HTTP.
   *
   * The chapters are cleared **before** the id changes, so `flatMapLatest`'s new inner flow can
   * never combine a new book's shelf row with the previous book's chapter list -- which would put
   * one book's chapter titles under another book's cover, briefly, and only when navigating
   * between two books.
   */
  fun load(bookId: String) {
    if (this.bookId.value == bookId) return
    chapters.value = BookUiState.Chapters.Reading
    this.bookId.value = bookId
    viewModelScope.launch { chapters.value = readChapters(bookId) }
  }

  /**
   * "Try again", from the sentence [BookUiState.Chapters.Unavailable] puts where the chapter list
   * would be.
   *
   * It goes back through [BookSource.retryTimeline] rather than [BookSource.timeline], and that is
   * the whole substance of this method: the read that just failed is remembered one layer down --
   * `ChapterRepository` keeps a failed probe in memory so a reopened book does not pay the same
   * timeout again -- so a retry that called `timeline` would be handed the remembered failure
   * without a packet leaving the device. A retry button that cannot reach the server is worse than
   * no button.
   *
   * Routed through [onBook] rather than reading `bookId` itself, so a tap that arrives with no
   * book loaded is the same no-op every other action here is, through the same one guard.
   */
  fun retryChapters() = onBook { id ->
    chapters.value = BookUiState.Chapters.Reading
    chapters.value = readChapters(id, retry = true)
  }

  /**
   * The chapter read, and the `catch` that stops it killing the app.
   *
   * **An exception out of a bare `viewModelScope.launch` reaches the thread's default handler**,
   * which on Android is a crash -- `viewModelScope`'s `SupervisorJob` stops the *scope* dying, not
   * the process. So `chapters.value = source.timeline(id)` with no `catch` meant that opening a
   * book while the server was unreachable killed MuPlay, and `ChapterReader.read` throws
   * `ExecutionException`/`TimeoutException` for exactly that.
   *
   * `CancellationException` is rethrown first, as `SyncEngine.syncIfStale` does for the same
   * reason: a cancelled `viewModelScope` is a screen going away, not a failed read, and swallowing
   * it would publish [BookUiState.Chapters.Unavailable] into a state nobody is looking at.
   */
  private suspend fun readChapters(bookId: String, retry: Boolean = false): BookUiState.Chapters =
    try {
      val timeline = if (retry) source.retryTimeline(bookId) else source.timeline(bookId)
      BookUiState.Chapters.Ready(timeline)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      BookUiState.Chapters.Unavailable
    }

  fun resume() = onBook { source.resume(it) }

  fun restart() = onBook { source.restart(it) }

  /**
   * "Play this part", in two steps, and both are needed.
   *
   * For a ripped book [BookPlaybackLauncher.playFile] already lands on the right file and the seek
   * is a no-op at `startInItemMs == 0`. For a single-file M4B **every chapter shares one media
   * id**, so the queue never changes and the seek is the entire operation -- without it, tapping
   * chapter 12 resumes wherever the listener last stopped.
   */
  fun playChapter(chapter: BookChapter) = onBook { id ->
    source.playFile(id, chapter.mediaId)
    source.seekTo(chapter.itemIndex, chapter.startInItemMs)
  }

  /**
   * The speed is written to the **book's row** here, not to the player, because this screen can be
   * open with nothing playing. `BookPlayerViewModel.setSpeed` is the other direction and sets the
   * player, which `BookSpeedController` then persists; the two meet at the same row.
   */
  fun setSpeed(speed: Float) = onBook { source.setSpeed(it, speed) }

  fun setSkipSilence(enabled: Boolean) = onBook { source.setSkipSilence(it, enabled) }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  /**
   * Every action needs the id and every action is a no-op without one.
   *
   * Written once rather than five times: five copies of `?: return` is five chances to write the
   * one that throws instead, and the tap that would find it is the one that arrives in the
   * fraction of a second before `LaunchedEffect` has run.
   */
  private fun onBook(action: suspend (String) -> Unit) {
    val id = bookId.value ?: return
    viewModelScope.launch { action(id) }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
