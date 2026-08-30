package app.muplay.book

import app.muplay.model.BookSummary

/** What the shelf shows. A sealed interface so the screen's `when` is exhaustive. */
sealed interface BookshelfUiState {

  /** The first query has not returned. */
  data object Loading : BookshelfUiState

  /** It has, and this library holds no audiobooks. */
  data object Empty : BookshelfUiState

  data class Content(val books: List<BookSummary>) : BookshelfUiState {

    /**
     * The top of the shelf: what the listener is part-way through, in the repository's order.
     *
     * `&& !isFinished`, not `hasStarted` alone. A finished book keeps its position — `markFinished`
     * writes the position, it does not clear it — so grouping on position alone goes on offering to
     * resume a book the listener has already heard to the end.
     */
    val continueListening: List<BookSummary> get() = books.filter { it.hasStarted && !it.isFinished }

    /**
     * Everything else, as `filterNot` over the *same* predicate rather than as a second one
     * written the other way round. Two hand-written predicates drift, and the symptom is a book
     * that appears twice or not at all.
     */
    val rest: List<BookSummary> get() = books.filterNot { it.hasStarted && !it.isFinished }
  }
}

/**
 * `null` is "the first query has not returned", which is a different fact from "there are no
 * audiobooks" — collapsing them shows "you have no audiobooks" for the second before the shelf
 * loads, and that reads as a broken app rather than as a slow one.
 */
internal fun bookshelfUiState(books: List<BookSummary>?): BookshelfUiState = when {
  books == null -> BookshelfUiState.Loading
  books.isEmpty() -> BookshelfUiState.Empty
  // The order is `BookSummaries.order`'s and is preserved exactly. Re-sorting here -- by title,
  // because that looks tidier -- silently undoes the one thing the shelf is for.
  else -> BookshelfUiState.Content(books)
}
