package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary

/**
 * What one book's screen shows.
 *
 * Three states and not two. [NotFound] is *"the shelf came back and this book is not on it"* --
 * a book whose library lost its `AUDIOBOOKS` role, or one a server rescan removed while the
 * screen was open -- and [Loading] is *"the shelf has not come back yet"*. They render different
 * text, and collapsing them tells a listener their book is gone every time the app is cold.
 *
 * [Loading] carries no data because there is none to carry: it is [BookViewModel]'s `stateIn`
 * initial value and the state of a view model whose `load` has not been called, which is why
 * [bookUiState] never returns it. See that function's own note.
 */
sealed interface BookUiState {

  /** The shelf query has not returned, or no book has been asked for yet. */
  data object Loading : BookUiState

  /** It returned, and it does not contain the book that was asked for. */
  data object NotFound : BookUiState

  /**
   * How far the chapter read has got, as three states rather than as a possibly-empty list.
   *
   * **A list cannot say "that failed", and this used to be a list.** Reading a book's chapters is
   * an HTTP round trip into each file's `moov` atom, so it fails for every ordinary reason a
   * self-hosted server fails -- asleep, unreachable, mid-restart -- and `ChapterReader` throws
   * when it does. `BookViewModel` read that timeline inside a bare `viewModelScope.launch`, so the
   * throw reached the default handler and **killed the process**: opening a book with the server
   * down crashed MuPlay. The screen had nowhere to put the failure, because an empty list already
   * meant [Reading].
   *
   * The distinction this type exists for is that a chapter-read failure is **not** a book failure.
   * Everything else on the screen -- the cover, the title, how much is left, the speed, and
   * `Resume`, which needs no chapters at all -- has already arrived from Room and stays usable.
   * So [Unavailable] is a sentence in the chapter list's own place, with a retry beside it, and
   * not a screen.
   *
   * Nested inside [BookUiState] rather than declared beside it, deliberately: the root
   * `coverageFloors` table gates this feature's state types by the pattern
   * `app.muplay.book.BookUiState*`, which a nested `BookUiState$Chapters` matches and a top-level
   * `ChapterLoad` would not. A sibling type would have been ungated by the accident of where it
   * was declared.
   */
  sealed interface Chapters {

    /** The read is in flight. The rest of the screen has already drawn; see `BookScreen`. */
    data object Reading : Chapters

    /** It returned. Empty is a real answer -- most audiobook files carry no chapter atoms. */
    data class Ready(val chapters: List<BookChapter>) : Chapters

    /**
     * It threw. Recoverable, and the screen says so: `ChapterReader`'s failures are transport
     * failures, so the same read a minute later usually works.
     */
    data object Unavailable : Chapters
  }

  /**
   * [chapters] is [Chapters.Reading] until the extraction finishes and that is a normal state, not
   * an error -- reading a book's chapters is an HTTP round trip per file. The screen renders
   * everything else meanwhile; a screen that waited would be blank for a second every time a book
   * was opened.
   */
  data class Content(
    val book: BookSummary,
    val chapters: Chapters,
    val settings: BookSettings,
  ) : BookUiState
}

/**
 * One book, found on the shelf by its id.
 *
 * **The shelf is the lookup, rather than `AudiobookRepository.book(bookId)`.** The screen already
 * collects `bookshelf()` for the position, which `ProgressWriter` moves every five seconds; a
 * second `suspend` read of the same book would be a second answer to "how far through is this",
 * and the one on screen would be the stale one. The whole reason `BookSummaries` exists is that
 * this app derives that number once.
 *
 * Deliberately **not** returning [BookUiState.Loading]: "the shelf has not emitted" is not
 * expressible from a `List` that has already been emitted, so inventing a `null` parameter for it
 * would add an arm no production caller could ever take. `stateIn`'s initial value carries that
 * state instead, which is where it actually lives.
 */
internal fun bookUiState(
  books: List<BookSummary>,
  bookId: String,
  chapters: BookUiState.Chapters,
  settings: BookSettings,
): BookUiState = when (val book = books.firstOrNull { it.bookId == bookId }) {
  null -> BookUiState.NotFound
  else -> BookUiState.Content(book, chapters, settings)
}
