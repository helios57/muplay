package app.muplay.book

import app.muplay.media.BookChapter
import java.util.Locale

/**
 * **Its own file, on purpose**, and the reason is this module's most load-bearing convention:
 * every top-level declaration in one Kotlin file compiles into that file's single file-class, and
 * a coverage floor can only filter by class name. The `@Composable` screens beside this file
 * measure LINE 0 from the fast tier, so anything sharing a file with one is ungateable -- which is
 * exactly what `CoverArtCacheKey.kt`'s own header records about `coverArtCacheKey` before it was
 * split out of `CoverArt.kt`. The two functions below are gated today because they are here.
 *
 * The constants are `internal const val`, so they are inlined at every use site and carry no
 * counters of their own. They are in one file rather than at the bottom of three screens because
 * they are this feature's **contract with the journeys** (Plan 4 Task 10): a journey finds a
 * control by typing the string out again, deliberately, so that a wording change is caught rather
 * than silently followed -- and one file is where somebody changing a word will see all of them.
 */

// ---- The shelf ----

/** The heading over everything that is not part-way through. */
internal const val BOOKSHELF_TITLE = "Books"
internal const val CONTINUE_LISTENING_LABEL = "Continue listening"
internal const val LOADING_BOOKS_LABEL = "Loading books"
internal const val NO_BOOKS_LABEL = "No audiobooks in this library"
internal const val RESUME_LABEL = "Resume"

// ---- One book ----

internal const val LOADING_BOOK_LABEL = "Loading this book…"
internal const val BOOK_NOT_FOUND_LABEL = "That book is no longer in your library."
internal const val START_OVER_LABEL = "Start from the beginning"
internal const val CHAPTERS_HEADING = "Chapters"

/**
 * Chapter extraction is an HTTP round trip per file, so this is a second or so of every book's
 * life. It is a distinct string from [LOADING_BOOK_LABEL] because the rest of the screen has
 * already rendered by then -- "loading" over a fully drawn book reads as a stuck screen.
 */
internal const val CHAPTERS_LOADING_LABEL = "Reading chapters…"
internal const val SKIP_SILENCE_LABEL = "Skip silence"

// ---- The player ----

internal const val NOTHING_PLAYING_LABEL = "Nothing playing"
internal const val PLAY_LABEL = "Play"
internal const val PAUSE_LABEL = "Pause"
internal const val PREVIOUS_CHAPTER_LABEL = "Previous chapter"
internal const val NEXT_CHAPTER_LABEL = "Next chapter"
internal const val BACK_30_LABEL = "Back 30 seconds"
internal const val FORWARD_30_LABEL = "Forward 30 seconds"
internal const val SPEED_LABEL = "Speed"
internal const val SLOWER_LABEL = "Slower"
internal const val FASTER_LABEL = "Faster"
internal const val SLEEP_TIMER_LABEL = "Sleep timer"
internal const val END_OF_CHAPTER_LABEL = "End of chapter"
internal const val CANCEL_TIMER_LABEL = "Cancel sleep timer"

/** The player's cover art. The shelf's rows pass `null` -- see `BookCover`. */
internal const val BOOK_COVER_LABEL = "Book cover"

/** What a nudge moves. Thirty back and thirty forward is the audiobook convention. */
internal const val NUDGE_MS = 30_000L

/**
 * "Speed 1.4x".
 *
 * [Locale.ROOT], and this is not defensive noise -- it is the same defect `formatClock` documents
 * one file over. `"%.1f".format(1.4f)` under a French or German default locale renders **"1,4"**,
 * and this string is compared by an `onNodeWithText("Speed 1.4x")` in a journey, never persisted
 * and never parsed. A locale-sensitive decimal separator here is a suite that is green on the CI
 * emulator and red on a developer's machine, for a reason nothing in the failure names.
 */
internal fun formatSpeed(speed: Float): String =
  "$SPEED_LABEL ${String.format(Locale.ROOT, "%.1f", speed)}x"

/**
 * "3. A Turn" -- one-based, because [BookChapter.index] is zero-based and "Chapter 0" is not a
 * thing anybody says. The same `+ 1` `bookPlayerUiState` applies to `chapterNumber`, and it is
 * here rather than inlined into two screens so the two cannot disagree about it.
 */
internal fun chapterRowLabel(chapter: BookChapter): String = "${chapter.index + 1}. ${chapter.title}"
