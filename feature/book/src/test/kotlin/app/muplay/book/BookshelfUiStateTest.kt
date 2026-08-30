package app.muplay.book

import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookshelfUiStateTest {

  private fun book(id: String, started: Boolean, finished: Boolean = false) = BookSummary(
    bookId = id, libraryId = 2, title = id, author = "$id Author", coverArtId = null, fileCount = 1,
    durationMs = 1_000, positionMs = if (started) 500 else 0, isFinished = finished,
    lastPlayedAtEpochMs = if (started) 1L else 0L,
  )

  @Test
  fun `nothing loaded yet is loading, and no books is empty`() {
    // Three distinct states from three inputs. Collapsing "not loaded" and "none" shows a listener
    // "you have no audiobooks" for the second before the first query returns, which reads as a
    // broken app rather than as a slow one.
    assertThat(bookshelfUiState(null)).isEqualTo(BookshelfUiState.Loading)
    assertThat(bookshelfUiState(emptyList())).isEqualTo(BookshelfUiState.Empty)
    assertThat(bookshelfUiState(listOf(book("a", started = false))))
      .isInstanceOf(BookshelfUiState.Content::class.java)
  }

  @Test
  fun `the repository's order is preserved exactly`() {
    // The shelf order is `BookSummaries.order`'s (Task 4). A UI state that re-sorted -- by title,
    // say, because that looks tidy -- would silently undo it.
    val books = listOf(book("zed", started = true), book("alpha", started = false))

    assertThat((bookshelfUiState(books) as BookshelfUiState.Content).books.map { it.bookId })
      .containsExactly("zed", "alpha")
  }

  @Test
  fun `the continue-listening group is what has been started`() {
    val books = listOf(book("zed", started = true), book("alpha", started = false))

    val content = bookshelfUiState(books) as BookshelfUiState.Content

    assertThat(content.continueListening.map { it.bookId }).containsExactly("zed")
    assertThat(content.rest.map { it.bookId }).containsExactly("alpha")
  }

  @Test
  fun `a book heard to the end leaves the continue-listening group without leaving the shelf`() {
    // The second half of `hasStarted && !isFinished`, and the only case that can tell that
    // conjunction from `hasStarted` alone: a finished book still has a position, so a shelf that
    // grouped on position keeps offering to resume a book the listener has finished. It stays on
    // the shelf -- deleting it from the shelf is a different, wrong answer.
    val books = listOf(book("done", started = true, finished = true), book("open", started = true))

    val content = bookshelfUiState(books) as BookshelfUiState.Content

    assertThat(content.continueListening.map { it.bookId }).containsExactly("open")
    assertThat(content.rest.map { it.bookId }).containsExactly("done")
  }

  @Test
  fun `the two groups always partition the shelf`() {
    // `rest` is `filterNot` over the very predicate `continueListening` filters on, so the pair
    // cannot drop a book or show one twice -- which a hand-written second predicate can, and
    // which is invisible on a shelf of two.
    val books = listOf(
      book("zed", started = true),
      book("alpha", started = false),
      book("done", started = true, finished = true),
    )

    val content = bookshelfUiState(books) as BookshelfUiState.Content

    assertThat(content.continueListening + content.rest).containsExactlyInAnyOrderElementsOf(books)
  }
}
