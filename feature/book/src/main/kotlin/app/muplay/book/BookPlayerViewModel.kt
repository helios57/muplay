package app.muplay.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.AudiobookRepository
import app.muplay.database.BrowseRepository
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.BookChapter
import app.muplay.media.BookTimeline
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackState
import app.muplay.media.SleepTimerController
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the book player touches. A seam, for the reason [BookshelfSource] documents, and the
 * widest one in this module because a book player is the screen that talks to the most machinery.
 *
 * **Primitives, not intentions**, throughout, and the two that matter most are [isPlaying] and
 * [currentPositionMs]. Both read the **player** rather than the last published [playback]
 * snapshot: `PlaybackConnection` samples on a 250 ms ticker, and a nudge or a play/pause computed
 * from a stale snapshot moves the wrong way. That is the same defect `PlaybackControls` was shaped
 * to prevent in `:feature:player`, and it is worse here -- a listener taps +30 s repeatedly.
 */
interface BookPlayerControls {

  val playback: StateFlow<PlaybackState>

  val sleepTimer: StateFlow<SleepTimerState>

  /** Connects to the session. Without it [playback] never emits and the screen stays blank. */
  suspend fun connect()

  suspend fun isPlaying(): Boolean

  suspend fun play()

  suspend fun pause()

  /** What the player has loaded right now, not what was last sampled. */
  suspend fun currentMediaId(): String?

  /** The position **inside the current item**, which is what `BookTimeline` compares against. */
  suspend fun currentPositionMs(): Long

  suspend fun seekTo(positionMs: Long)

  suspend fun seekTo(itemIndex: Int, positionMs: Long)

  suspend fun setSpeed(speed: Float)

  fun startSleepTimer(request: SleepTimerRequest)

  fun cancelSleepTimer()

  /** Which book a playing media id belongs to, or `null` if it is not an audiobook at all. */
  suspend fun bookIdFor(mediaId: String): String?

  suspend fun book(bookId: String): BookSummary?

  suspend fun timeline(bookId: String): List<BookChapter>

  fun observeSettings(bookId: String): Flow<BookSettings>

  /** Suspending because building an authenticated cover URL reads the stored credentials. */
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String
}

/**
 * The audiobook player.
 *
 * Everything the screen *shows* is [bookPlayerUiState], which is pure and gated on the fast tier.
 * What lives here is the part that cannot be pure: resolving which book is playing, and the seven
 * actions.
 */
@HiltViewModel
class BookPlayerViewModel(private val controls: BookPlayerControls) : ViewModel() {

  @Inject
  constructor(
    playbackConnection: PlaybackConnection,
    audiobookRepository: AudiobookRepository,
    browseRepository: BrowseRepository,
    audiobookSnapshot: AudiobookSnapshot,
    timelineReader: BookTimelineReader,
    sleepTimerController: SleepTimerController,
  ) : this(
    object : BookPlayerControls {
      override val playback: StateFlow<PlaybackState> = playbackConnection.state
      override val sleepTimer: StateFlow<SleepTimerState> = sleepTimerController.state

      override suspend fun connect() {
        playbackConnection.controller()
      }

      override suspend fun isPlaying(): Boolean = playbackConnection.controller().isPlaying

      override suspend fun play() = playbackConnection.controller().play()

      override suspend fun pause() = playbackConnection.controller().pause()

      override suspend fun currentMediaId(): String? =
        playbackConnection.controller().currentMediaItem?.mediaId

      override suspend fun currentPositionMs(): Long =
        playbackConnection.controller().currentPosition

      override suspend fun seekTo(positionMs: Long) =
        playbackConnection.controller().seekTo(positionMs)

      override suspend fun seekTo(itemIndex: Int, positionMs: Long) =
        playbackConnection.controller().seekTo(itemIndex, positionMs)

      override suspend fun setSpeed(speed: Float) =
        playbackConnection.controller().setPlaybackSpeed(speed)

      override fun startSleepTimer(request: SleepTimerRequest) = sleepTimerController.start(request)

      override fun cancelSleepTimer() = sleepTimerController.cancel()

      // Through `AudiobookSnapshot`, which is the same in-memory map the resume policy reads --
      // so the player and the policy cannot disagree about which book is playing.
      override suspend fun bookIdFor(mediaId: String): String? =
        audiobookSnapshot.itemFor(mediaId)?.bookId

      override suspend fun book(bookId: String): BookSummary? = audiobookRepository.book(bookId)

      override suspend fun timeline(bookId: String): List<BookChapter> =
        timelineReader.timeline(bookId)

      override fun observeSettings(bookId: String): Flow<BookSettings> =
        audiobookRepository.observeSettings(bookId)

      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
    },
  )

  private val book = MutableStateFlow<BookSummary?>(null)
  private val timeline = MutableStateFlow<List<BookChapter>>(emptyList())
  private val settings = MutableStateFlow(BookSettings.default(""))

  val uiState: StateFlow<BookPlayerUiState> = combine(
    controls.playback,
    book,
    timeline,
    settings,
    controls.sleepTimer,
    ::bookPlayerUiState,
  ).stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
    initialValue = BookPlayerUiState.NothingPlaying,
  )

  init {
    // Without this the flow never emits and the screen renders "Nothing playing" while audio is
    // audibly playing. `PlayerViewModel` carries the same line for the same measured reason.
    viewModelScope.launch { controls.connect() }

    // **`collectLatest`, not `collect`.** The inner `observeSettings` collection never returns, so
    // a plain `collect` would leave the previous book's settings collector alive and the two would
    // fight over `settings.value` every time a listener moved between books.
    viewModelScope.launch {
      controls.playback
        .map { it.mediaId }
        .distinctUntilChanged()
        .collectLatest { mediaId -> loadBookFor(mediaId) }
    }
  }

  /**
   * The two `null`s here are different facts and neither may be folded into the other. A `null`
   * [mediaId] is "nothing is loaded"; a `null` book id is "something is loaded and it is not an
   * audiobook" -- a music track, reachable the instant a listener plays a song with this screen
   * still on the back stack. Both clear the same fields, which is why folding them looks free.
   */
  private suspend fun loadBookFor(mediaId: String?) {
    val bookId = mediaId?.let { controls.bookIdFor(it) }
    if (bookId == null) {
      book.value = null
      timeline.value = emptyList()
      settings.value = BookSettings.default("")
      return
    }
    book.value = controls.book(bookId)
    timeline.value = controls.timeline(bookId)
    controls.observeSettings(bookId).collect { settings.value = it }
  }

  fun playPause() {
    viewModelScope.launch {
      if (controls.isPlaying()) controls.pause() else controls.play()
    }
  }

  fun nextChapter() = seekToChapter { chapters, current, _ -> BookTimeline.next(chapters, current) }

  fun previousChapter() = seekToChapter { chapters, current, positionInItemMs ->
    BookTimeline.previous(chapters, current, positionInItemMs)
  }

  fun seekTo(chapter: BookChapter) {
    viewModelScope.launch { controls.seekTo(chapter.itemIndex, chapter.startInItemMs) }
  }

  /**
   * Plus or minus thirty seconds, clamped at zero.
   *
   * Reads [BookPlayerControls.currentPositionMs] rather than `uiState`, because `uiState` carries
   * the position **inside the chapter** and a book's chapter rarely starts at zero. Nudging from
   * that number is off by the chapter's own start on every chapter but the first -- silently, and
   * only on books whose chapters are tagged.
   */
  fun nudge(byMs: Long) {
    viewModelScope.launch {
      controls.seekTo((controls.currentPositionMs() + byMs).coerceAtLeast(0L))
    }
  }

  /**
   * Set on the **player**, not in the database. `BookSpeedController` hears
   * `onPlaybackParametersChanged` and persists it against the right book -- which is also what
   * makes a speed change from a car or a watch stick without this screen being involved.
   *
   * Clamped here as well as there, because `ExoPlayer.setPlaybackSpeed(NaN)` throws from inside a
   * listener callback and surfaces as playback dying with no message.
   */
  fun setSpeed(speed: Float) {
    viewModelScope.launch { controls.setSpeed(BookSettings.clampSpeed(speed)) }
  }

  fun startSleepTimer(request: SleepTimerRequest) = controls.startSleepTimer(request)

  fun cancelSleepTimer() = controls.cancelSleepTimer()

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    controls.coverArtUrl(coverArtId, sizePx)

  /**
   * "Until the end of this chapter", turned into the position the timer actually takes.
   *
   * The timer is given a `mediaId` as well as a position on purpose (see [SleepTimerRequest]): a
   * position with no item attached is a position in whatever happens to be playing when the tick
   * fires, so a book that advanced to its next file would keep counting toward a mark belonging to
   * the file before it.
   *
   * Both early returns are real. Nothing playing, and a book whose chapters have not been
   * extracted -- an HTTP round trip -- are both states a listener can tap this in.
   */
  fun endOfChapterTimer() {
    viewModelScope.launch {
      val mediaId = controls.currentMediaId() ?: return@launch
      val positionInItemMs = controls.currentPositionMs()
      val chapter = BookTimeline.chapterAt(timeline.value, mediaId, positionInItemMs) ?: return@launch
      controls.startSleepTimer(
        SleepTimerRequest.UntilPosition(chapter.mediaId, chapter.endInItemMs),
      )
    }
  }

  /**
   * The one place chapter navigation happens.
   *
   * Note which position is handed to [BookTimeline]: the **in-item** position, not the book
   * position. `previous` compares it against the chapter's `startInItemMs` to choose between
   * "restart this chapter" and "go to the one before it", and a book position there makes that
   * comparison wrong for every file after the first.
   */
  private fun seekToChapter(
    pick: (List<BookChapter>, BookChapter?, Long) -> BookChapter?,
  ) {
    viewModelScope.launch {
      val mediaId = controls.currentMediaId() ?: return@launch
      val positionInItemMs = controls.currentPositionMs()
      val current = BookTimeline.chapterAt(timeline.value, mediaId, positionInItemMs)
      val target = pick(timeline.value, current, positionInItemMs) ?: return@launch
      controls.seekTo(target.itemIndex, target.startInItemMs)
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
