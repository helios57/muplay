package app.muplay.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.AudiobookRepository
import app.muplay.database.BrowseRepository
import app.muplay.model.BookSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the shelf needs, abstracted for the reason every other view model in this codebase
 * abstracts its repositories: [AudiobookRepository] and [BrowseRepository] are concrete,
 * `@Inject`-constructed classes over Room, and [BookPlaybackLauncher] needs a bound
 * `MediaSession`. None of the three can be subclassed into a hand-written fake, this project bans
 * mock frameworks, so a seam is the only way this class's own decisions are provable anywhere but
 * on an emulator. Same shape and same reason as `:feature:library`'s `LibrarySource` and
 * `:feature:player`'s `PlaybackControls`.
 *
 * **Primitives, not intentions.** [resume] is the launcher's `resume` and nothing more; the
 * "play it and open the player" pair that the screen performs is composed at the call site, where
 * a test can see the order.
 */
interface BookshelfSource {

  fun bookshelf(): Flow<List<BookSummary>>

  suspend fun resume(bookId: String)

  /** Suspending because building an authenticated cover URL reads the stored credentials. */
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String
}

/**
 * The shelf.
 *
 * Every rule about *what it shows* lives in [bookshelfUiState], which is pure and gated on the
 * fast tier. This class collects one flow and forwards two actions.
 */
@HiltViewModel
class BookshelfViewModel(private val source: BookshelfSource) : ViewModel() {

  @Inject
  constructor(
    audiobookRepository: AudiobookRepository,
    browseRepository: BrowseRepository,
    launcher: BookPlaybackLauncher,
  ) : this(
    object : BookshelfSource {
      override fun bookshelf(): Flow<List<BookSummary>> = audiobookRepository.bookshelf()

      override suspend fun resume(bookId: String) = launcher.resume(bookId)

      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
    },
  )

  /**
   * `initialValue = bookshelfUiState(null)` rather than the literal [BookshelfUiState.Loading],
   * and that is not a flourish. "The first query has not returned" is a decision that function
   * already owns and is already gated for; writing the constant here would be a second place that
   * answers it, and the day somebody decides an un-returned shelf should render something else,
   * one of the two would be missed.
   */
  val uiState: StateFlow<BookshelfUiState> = source.bookshelf()
    .map { bookshelfUiState(it) }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = bookshelfUiState(null),
    )

  fun resume(bookId: String) {
    viewModelScope.launch { source.resume(bookId) }
  }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
