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
   * [chapters] is empty until extraction finishes and that is a normal state, not an error --
   * reading a book's chapters is an HTTP round trip per file. The screen renders everything else
   * meanwhile; a screen that waited would be blank for a second every time a book was opened.
   */
  data class Content(
    val book: BookSummary,
    val chapters: List<BookChapter>,
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
  chapters: List<BookChapter>,
  settings: BookSettings,
): BookUiState = when (val book = books.firstOrNull { it.bookId == bookId }) {
  null -> BookUiState.NotFound
  else -> BookUiState.Content(book, chapters, settings)
}
