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
  private val chapters = MutableStateFlow<List<BookChapter>>(emptyList())

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
    chapters.value = emptyList()
    this.bookId.value = bookId
    viewModelScope.launch { chapters.value = source.timeline(bookId) }
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
