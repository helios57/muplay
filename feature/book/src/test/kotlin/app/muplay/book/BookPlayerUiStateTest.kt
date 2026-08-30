package app.muplay.book

import androidx.media3.common.MediaMetadata
import app.muplay.media.BookChapter
import app.muplay.media.PlaybackState
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Everything the book player shows, derived from four inputs and no Android.
 *
 * The chapter a listener is in is **computed from the position**, not stored, so this is where
 * "which chapter am I in" is actually gated. The timeline used below is a multi-file book, because
 * on a single-file book the in-item position and the book position are the same number and half of
 * these assertions would be true of any implementation.
 */
class BookPlayerUiStateTest {

  private val timeline = listOf(
    BookChapter(0, "One", "p1", 0, 0, 4_000, 0),
    BookChapter(1, "Two", "p2", 1, 0, 6_000, 4_000),
    BookChapter(2, "Three", "p3", 2, 0, 5_000, 10_000),
  )

  private val book = BookSummary(
    bookId = "book", libraryId = 2, title = "Multi Part Book", author = "Fourth Author",
    coverArtId = "art", fileCount = 3, durationMs = 15_000, positionMs = 0,
    isFinished = false, lastPlayedAtEpochMs = 0L,
  )

  private fun playback(mediaId: String, positionMs: Long, playing: Boolean = true, speed: Float = 1f) =
    PlaybackState.NOTHING_PLAYING.copy(
      isPlaying = playing, mediaId = mediaId, title = mediaId, positionMs = positionMs,
      durationMs = 6_000, mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, speed = speed,
    )

  private fun state(mediaId: String, positionMs: Long, speed: Float = 1f) = bookPlayerUiState(
    playback = playback(mediaId, positionMs, speed = speed),
    book = book,
    timeline = timeline,
    settings = BookSettings("book", speed, skipSilence = false),
    sleepTimer = SleepTimerState.Off,
  ) as BookPlayerUiState.Content

  @Test
  fun `nothing playing is its own state`() {
    assertThat(
      bookPlayerUiState(PlaybackState.NOTHING_PLAYING, null, emptyList(), BookSettings.default("b"), SleepTimerState.Off),
    ).isEqualTo(BookPlayerUiState.NothingPlaying)
  }

  @Test
  fun `an item that belongs to no known book is nothing playing too`() {
    // The other half of the same guard, and a state the player really reaches: `AudiobookSnapshot`
    // answers `null` for a music track, and for a book file whose row has not been mirrored yet.
    // Without this case only the `mediaId == null` half of the guard is ever executed, and a
    // `bookPlayerUiState` that dereferenced `book!!` would still pass every other test here.
    assertThat(
      bookPlayerUiState(playback("p1", 1_000), null, timeline, BookSettings.default("b"), SleepTimerState.Off),
    ).isEqualTo(BookPlayerUiState.NothingPlaying)
  }

  @Test
  fun `the chapter shown follows the position`() {
    // Three observations across three files. A UI state that took the first chapter, or the
    // current media item's index, passes at most one of them.
    assertThat(state("p1", 1_000).chapterTitle).isEqualTo("One")
    assertThat(state("p2", 1_000).chapterTitle).isEqualTo("Two")
    assertThat(state("p3", 1_000).chapterTitle).isEqualTo("Three")
  }

  @Test
  fun `the chapter number and count are what a listener counts`() {
    // One-based, because "Chapter 0 of 3" is not a thing anybody says.
    assertThat(state("p2", 1_000).chapterNumber).isEqualTo(2)
    assertThat(state("p2", 1_000).chapterCount).isEqualTo(3)
  }

  @Test
  fun `the book position adds the files before this one`() {
    // The number under the whole-book progress bar. Three observations, three offsets: on a
    // single-file book this is indistinguishable from the in-file position.
    assertThat(state("p1", 1_000).bookPositionMs).isEqualTo(1_000L)
    assertThat(state("p2", 1_000).bookPositionMs).isEqualTo(5_000L)
    assertThat(state("p3", 1_000).bookPositionMs).isEqualTo(11_000L)
  }

  @Test
  fun `the position inside the chapter is relative to the chapter, not to the file`() {
    // For a single-file M4B these differ: chapter 3 of Second Book starts at 9 000, so a position
    // of 12 000 is 3 000 into the chapter.
    val singleFile = listOf(
      BookChapter(0, "A", "m4b", 0, 0, 9_000, 0),
      BookChapter(1, "B", "m4b", 0, 9_000, 15_000, 9_000),
    )

    val content = bookPlayerUiState(
      playback = playback("m4b", 12_000),
      book = book,
      timeline = singleFile,
      settings = BookSettings.default("book"),
      sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(content.positionInChapterMs).isEqualTo(3_000L)
    assertThat(content.chapterDurationMs).isEqualTo(6_000L)
  }

  @Test
  fun `a position before the first chapter's own start reports zero rather than a negative`() {
    // `BookTimeline.chapterAt` answers the first chapter for a position that precedes it, which is
    // what a tagger writing a first chapter at a non-zero offset produces. The subtraction then
    // goes negative, and a negative position drives a progress bar backwards off its own track.
    val late = listOf(BookChapter(0, "A", "m4b", 0, 500, 9_000, 500))

    val content = bookPlayerUiState(
      playback = playback("m4b", 0), book = book, timeline = late,
      settings = BookSettings.default("book"), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(content.positionInChapterMs).isZero
  }

  @Test
  fun `the remaining time in the book is what the shelf promised`() {
    assertThat(state("p2", 1_000).bookRemainingMs).isEqualTo(10_000L)
    assertThat(state("p3", 4_000).bookRemainingMs).isEqualTo(1_000L)
  }

  @Test
  fun `a position past the declared duration has nothing remaining rather than less than nothing`() {
    // Media3 reports positions past a declared duration on streams whose duration was estimated --
    // which is every Opus book this app transcodes. `BookSummary.remainingMs` clamps for exactly
    // this reason; so must the number under the player's own progress bar.
    assertThat(state("p3", 9_000).bookRemainingMs).isZero
  }

  @Test
  fun `the speed shown is the book's`() {
    // Two speeds. A UI that hardcoded 1.0 -- easy, because that is what the player reports for
    // most of a session -- passes one of these.
    assertThat(state("p1", 0, speed = 1.4f).speed).isEqualTo(1.4f)
    assertThat(state("p1", 0, speed = 0.8f).speed).isEqualTo(0.8f)
  }

  @Test
  fun `the sleep timer is carried through, running or not`() {
    val running = bookPlayerUiState(
      playback = playback("p1", 0), book = book, timeline = timeline,
      settings = BookSettings.default("book"),
      sleepTimer = SleepTimerState.Running(90_000L, untilEndOfChapter = true, isFading = false),
    ) as BookPlayerUiState.Content

    assertThat(running.sleepTimer).isEqualTo(SleepTimerState.Running(90_000L, true, false))
    assertThat(state("p1", 0).sleepTimer).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun `skip silence comes from the book's settings and not from the player`() {
    // The one field on this state that is read from `settings` rather than from `playback`, and
    // therefore the one a state built entirely out of `PlaybackState` would get wrong.
    val on = bookPlayerUiState(
      playback = playback("p1", 0), book = book, timeline = timeline,
      settings = BookSettings("book", 1f, skipSilence = true), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(on.skipSilence).isTrue
    assertThat(state("p1", 0).skipSilence).isFalse
  }

  @Test
  fun `a book whose chapters have not loaded yet still shows the transport`() {
    // Chapter extraction is an HTTP round trip. A player that showed nothing until it finished
    // would be blank for a second every time a book opened.
    val content = bookPlayerUiState(
      playback = playback("p1", 1_000), book = book, timeline = emptyList(),
      settings = BookSettings.default("book"), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(content.chapterTitle).isEqualTo("Multi Part Book")
    assertThat(content.chapterCount).isZero
    assertThat(content.bookPositionMs).isEqualTo(1_000L)
  }

  @Test
  fun `with no chapters the chapter length falls back to the playing item's own duration`() {
    // And it is clamped, because `PlaybackState.durationMs` is `0` before the extractor has
    // measured anything and `C.TIME_UNSET`-derived negatives have reached this field before.
    val unknown = bookPlayerUiState(
      playback = playback("p1", 1_000).copy(durationMs = -1L), book = book, timeline = emptyList(),
      settings = BookSettings.default("book"), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(unknown.chapterDurationMs).isZero

    val known = bookPlayerUiState(
      playback = playback("p1", 1_000), book = book, timeline = emptyList(),
      settings = BookSettings.default("book"), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(known.chapterDurationMs).isEqualTo(6_000L)
  }

  @Test
  fun `the book's own identity is carried through for the screen to render`() {
    val content = state("p1", 0)

    assertThat(content.bookTitle).isEqualTo("Multi Part Book")
    assertThat(content.author).isEqualTo("Fourth Author")
    assertThat(content.coverArtId).isEqualTo("art")
    assertThat(content.bookDurationMs).isEqualTo(15_000L)
    assertThat(content.isPlaying).isTrue
    assertThat(content.chapters).isEqualTo(timeline)
  }
}
