package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.media.BookTimeline
import app.muplay.media.PlaybackFailure
import app.muplay.media.PlaybackState
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerState
import java.util.Locale

sealed interface BookPlayerUiState {

  data object NothingPlaying : BookPlayerUiState

  data class Content(
    val bookTitle: String,
    /**
     * Non-null, because `BookSummary.author` is. The plan's listing had it nullable on both types;
     * `:core:model` settled that question the other way and every existing caller compiles against
     * the non-null form.
     */
    val author: String,
    val coverArtId: String?,
    val chapterTitle: String,
    val chapterNumber: Int,
    val chapterCount: Int,
    val positionInChapterMs: Long,
    val chapterDurationMs: Long,
    val bookPositionMs: Long,
    val bookDurationMs: Long,
    val bookRemainingMs: Long,
    val isPlaying: Boolean,
    val speed: Float,
    val skipSilence: Boolean,
    val sleepTimer: SleepTimerState,
    val chapters: List<BookChapter>,
    /**
     * Why playback stopped, or `null`. Carried onto this screen for the same reason the music
     * player carries it: a failed book published `isPlaying = false` and nothing else, which is
     * exactly what a pause looks like, and the play button then called `play()` into a
     * `STATE_IDLE` player that ignores it.
     *
     * **Defaulted**, unlike every other property here, because the fixtures in this module build a
     * `Content` by hand and "nothing has gone wrong" is the uninteresting case at all of them.
     */
    val failure: PlaybackFailure? = null,
  ) : BookPlayerUiState
}

/**
 * Everything the book player shows, from five inputs and no Android.
 *
 * The chapter is **computed from the position** rather than stored, so this function is where
 * "which chapter am I in" is actually decided — and where it is gated, in Tier 1.
 *
 * An empty [timeline] is a real state, not an error: chapter extraction is an HTTP round trip, and
 * a player that showed nothing until it finished would be blank for a second every time a book
 * opened. The book's own title stands in until the chapters arrive.
 *
 * Two guards, not one, and they are different facts. A `null` [PlaybackState.mediaId] is "nothing
 * is loaded"; a `null` [book] is "something is loaded and it is not a book this app knows about" —
 * a music track, or a file whose row the mirror has not written yet. Both render the same screen
 * and neither may be folded into the other, because folding them is how a `book!!` gets written.
 */
internal fun bookPlayerUiState(
  playback: PlaybackState,
  book: BookSummary?,
  timeline: List<BookChapter>,
  settings: BookSettings,
  sleepTimer: SleepTimerState,
): BookPlayerUiState {
  val mediaId = playback.mediaId ?: return BookPlayerUiState.NothingPlaying
  if (book == null) return BookPlayerUiState.NothingPlaying

  val chapter = BookTimeline.chapterAt(timeline, mediaId, playback.positionMs)
  val bookPositionMs = BookTimeline.bookPositionMs(timeline, mediaId, playback.positionMs)

  return BookPlayerUiState.Content(
    bookTitle = book.title,
    author = book.author,
    coverArtId = book.coverArtId,
    chapterTitle = chapter?.title ?: book.title,
    // One-based: "Chapter 0 of 3" is not a thing anybody says.
    chapterNumber = (chapter?.index ?: -1) + 1,
    chapterCount = timeline.size,
    // Clamped: `chapterAt` answers the first chapter for a position that precedes it, which is
    // what a tagger that writes a first chapter at a non-zero offset produces, and the
    // subtraction then goes negative under a progress bar.
    positionInChapterMs = chapter?.let { (playback.positionMs - it.startInItemMs).coerceAtLeast(0L) }
      ?: playback.positionMs,
    // With no chapters the playing item *is* the chapter, so its own duration is the honest
    // fallback -- clamped for the reason `PlaybackState.durationMsOf` clamps: an unmeasured
    // duration arrives here as 0 or as something derived from `C.TIME_UNSET`.
    chapterDurationMs = chapter?.durationMs ?: playback.durationMs.coerceAtLeast(0L),
    bookPositionMs = bookPositionMs,
    bookDurationMs = book.durationMs,
    // Clamped for the same reason `BookSummary.remainingMs` is: the two numbers come from a
    // container's declared duration and a player's reported position, and Media3 reports positions
    // past a declared duration on any stream whose duration was estimated.
    bookRemainingMs = (book.durationMs - bookPositionMs).coerceAtLeast(0L),
    isPlaying = playback.isPlaying,
    // The player's, not the settings row's. A speed changed from a car or a watch reaches the
    // player first and `BookSpeedController` (Task 7) persists it afterwards, so reading the row
    // here would render the old value until that write landed.
    speed = playback.speed,
    skipSilence = settings.skipSilence,
    sleepTimer = sleepTimer,
    chapters = timeline,
    failure = playback.failure,
  )
}

/**
 * `h:mm:ss` past an hour, `m:ss` below it — a book is routinely longer than an hour.
 *
 * [Locale.ROOT], for the reason `BookTimeline.UNTITLED_FORMAT` gives for the same choice: `%d`
 * under an Arabic or Devanagari default locale renders digits that an `onNodeWithText("1:30")` in
 * a journey does not match, and this string is persisted nowhere and compared everywhere.
 */
internal fun formatClock(millis: Long): String {
  val total = (millis / 1_000).coerceAtLeast(0)
  val h = total / 3_600
  val m = (total % 3_600) / 60
  val s = total % 60
  return if (h > 0) {
    String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
  } else {
    String.format(Locale.ROOT, "%d:%02d", m, s)
  }
}

/** "3 h 12 m left" / "12 m left" / "under a minute left" — what a listener actually wants to know. */
internal fun formatRemaining(millis: Long): String {
  val minutes = (millis / 60_000).coerceAtLeast(0)
  return when {
    minutes == 0L -> "under a minute left"
    minutes < 60L -> "$minutes m left"
    else -> "${minutes / 60} h ${minutes % 60} m left"
  }
}
