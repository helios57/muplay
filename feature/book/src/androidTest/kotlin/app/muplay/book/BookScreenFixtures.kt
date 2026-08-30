package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary

/**
 * Shared fixtures for this module's three instrumented suites.
 *
 * **Every string here is pairwise different, and that is the point rather than tidiness.** A screen
 * that rendered `author` where `title` belongs satisfies every "is it displayed" assertion in all
 * three suites if the two fixtures share a value; `:feature:player`'s `PlayerFixtures` records the
 * same rule for the same reason, and the assertions in these suites are positional or
 * value-bearing on top of it.
 *
 * The numbers are chosen so that no two rendered strings collide either: [STARTED_POSITION_MS] and
 * [FINISHED_DURATION_MS] give different `formatRemaining` sentences, and the player's chapter clock
 * and its sleep-timer countdown format to different strings.
 */
internal const val STARTED_BOOK_ID = "book-started"
internal const val SECOND_STARTED_BOOK_ID = "book-started-two"
internal const val UNSTARTED_BOOK_ID = "book-unstarted"

internal const val STARTED_TITLE = "A Started Book"
internal const val STARTED_AUTHOR = "Started Author"
internal const val SECOND_STARTED_TITLE = "Another Started Book"
internal const val SECOND_STARTED_AUTHOR = "Second Author"
internal const val UNSTARTED_TITLE = "An Unopened Book"
internal const val UNSTARTED_AUTHOR = "Unopened Author"

/** Four hours, so `formatRemaining` reaches its `h m` arm rather than only its minutes arm. */
internal const val BOOK_DURATION_MS = 4L * 60 * 60 * 1_000

/** One hour in: "3 h 0 m left", which no other fixture below produces. */
internal const val STARTED_POSITION_MS = 60L * 60 * 1_000

/**
 * A book, with every field an argument so that a test states the one thing it is about.
 *
 * `positionMs` decides [BookSummary.hasStarted], which is what splits the shelf in two, so it is
 * never defaulted at a call site that cares about the split.
 */
internal fun bookSummary(
  bookId: String,
  title: String,
  author: String,
  positionMs: Long = 0L,
  isFinished: Boolean = false,
  coverArtId: String? = null,
  durationMs: Long = BOOK_DURATION_MS,
): BookSummary = BookSummary(
  bookId = bookId,
  libraryId = 1,
  title = title,
  author = author,
  coverArtId = coverArtId,
  fileCount = 1,
  durationMs = durationMs,
  positionMs = positionMs,
  isFinished = isFinished,
  lastPlayedAtEpochMs = 1_700_000_000_000L,
)

internal fun startedBook(): BookSummary =
  bookSummary(STARTED_BOOK_ID, STARTED_TITLE, STARTED_AUTHOR, positionMs = STARTED_POSITION_MS)

internal fun secondStartedBook(): BookSummary = bookSummary(
  SECOND_STARTED_BOOK_ID,
  SECOND_STARTED_TITLE,
  SECOND_STARTED_AUTHOR,
  // A different position from the first, so a row that rendered the wrong book's progress shows a
  // different sentence rather than the same one.
  positionMs = 2L * 60 * 60 * 1_000,
)

internal fun unstartedBook(): BookSummary =
  bookSummary(UNSTARTED_BOOK_ID, UNSTARTED_TITLE, UNSTARTED_AUTHOR, positionMs = 0L)

/**
 * Three chapters, **each a different length**, all inside one media item.
 *
 * The lengths differ because a chapter row renders its own `formatClock(durationMs)` beside its
 * title: with three equal lengths every row would carry the string `"2:00"` and an
 * `onNodeWithText` for it would be ambiguous -- which reads as a broken query rather than as the
 * missing distinction it is. Different lengths also mean a row that rendered the *wrong* chapter's
 * length fails rather than passing.
 */
internal const val FIRST_CHAPTER_MS = 2L * 60 * 1_000
internal const val SECOND_CHAPTER_MS = 3L * 60 * 1_000
internal const val THIRD_CHAPTER_MS = 4L * 60 * 1_000

internal const val FIRST_CHAPTER_TITLE = "The Opening"
internal const val SECOND_CHAPTER_TITLE = "The Middle"
internal const val THIRD_CHAPTER_TITLE = "The End"

internal fun chapters(): List<BookChapter> = listOf(
  BookChapter(
    index = 0,
    title = FIRST_CHAPTER_TITLE,
    mediaId = "file-1",
    itemIndex = 0,
    startInItemMs = 0L,
    endInItemMs = FIRST_CHAPTER_MS,
    bookStartMs = 0L,
  ),
  BookChapter(
    index = 1,
    title = SECOND_CHAPTER_TITLE,
    mediaId = "file-1",
    itemIndex = 0,
    startInItemMs = FIRST_CHAPTER_MS,
    endInItemMs = FIRST_CHAPTER_MS + SECOND_CHAPTER_MS,
    bookStartMs = FIRST_CHAPTER_MS,
  ),
  BookChapter(
    index = 2,
    title = THIRD_CHAPTER_TITLE,
    mediaId = "file-1",
    itemIndex = 0,
    startInItemMs = FIRST_CHAPTER_MS + SECOND_CHAPTER_MS,
    endInItemMs = FIRST_CHAPTER_MS + SECOND_CHAPTER_MS + THIRD_CHAPTER_MS,
    bookStartMs = FIRST_CHAPTER_MS + SECOND_CHAPTER_MS,
  ),
)

/** 1.5x, so `formatSpeed` renders a decimal that a default-locale bug would render as `1,5`. */
internal fun bookSettings(speed: Float = 1.5f, skipSilence: Boolean = false): BookSettings =
  BookSettings(bookId = STARTED_BOOK_ID, speed = speed, skipSilence = skipSilence)

/**
 * The cover-art lookup every screen takes, wired to fail.
 *
 * `BookCover` wraps this in `runCatching { }.getOrNull()`, so a throwing provider leaves the URL
 * `null` and Coil is handed `data(null)` -- no request, no network, nothing to wait for. That is
 * what keeps these suites hermetic; a provider that returned a plausible URL would have every one
 * of them making an HTTP call to a host that does not exist.
 */
internal val NO_COVER: suspend (String, Int) -> String = { _, _ ->
  error("these suites render no cover art and must make no network call")
}
