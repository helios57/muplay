package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which book a book screen is showing, and what it says when there is not one.
 *
 * The interesting half is [BookUiState.NotFound]: it is the arm that fires when a listener has a
 * book screen open and the book leaves the shelf underneath them -- a server rescan, or a library
 * whose `AUDIOBOOKS` role was cleared. Without it the screen would need a `!!` on the lookup, and
 * the crash would land on whoever had the app open longest.
 */
class BookUiStateTest {

  private fun book(id: String) = BookSummary(
    bookId = id, libraryId = 2, title = "Title of $id", author = "$id Author", coverArtId = null,
    fileCount = 1, durationMs = 1_000, positionMs = 0, isFinished = false,
    lastPlayedAtEpochMs = 0L,
  )

  private val chapterList = listOf(BookChapter(0, "One", "p1", 0, 0, 4_000, 0))
  private val chapters = BookUiState.Chapters.Ready(chapterList)
  private val settings = BookSettings("wanted", speed = 1.4f, skipSilence = true)

  @Test
  fun `a book on the shelf is the book that is shown`() {
    // Two books, and the SECOND one asked for. With one book on the shelf, "the book that was
    // asked for" and "the only book there is" are the same object, and a lookup that ignored its
    // own `bookId` argument would pass.
    val content =
      bookUiState(listOf(book("other"), book("wanted")), "wanted", chapters, settings)
        as BookUiState.Content

    assertThat(content.book.bookId).isEqualTo("wanted")
    assertThat(content.book.title).isEqualTo("Title of wanted")
  }

  @Test
  fun `a book that is not on the shelf is not found rather than a crash`() {
    // The shelf has returned and does not contain it. This is reachable while a screen is open:
    // `bookshelf()` re-emits on every mirror and progress change, so a rescan that drops a book
    // pushes this state into a screen that was showing Content a moment ago.
    assertThat(bookUiState(listOf(book("other")), "wanted", chapters, settings))
      .isEqualTo(BookUiState.NotFound)
  }

  @Test
  fun `an empty shelf finds nothing`() {
    // The other way into the same arm, and the one a first sync produces: `firstOrNull` over an
    // empty list never evaluates its predicate at all.
    assertThat(bookUiState(emptyList(), "wanted", chapters, settings))
      .isEqualTo(BookUiState.NotFound)
  }

  @Test
  fun `the chapters and the settings are carried through untouched`() {
    // Both, and by value. They arrive from two different flows -- an HTTP extraction and a Room
    // row -- and a state that dropped either would render a book with no chapter list or at 1.0x
    // with no test to say so.
    val content =
      bookUiState(listOf(book("wanted")), "wanted", chapters, settings) as BookUiState.Content

    assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Ready(chapterList))
    assertThat(content.settings).isEqualTo(settings)
  }

  @Test
  fun `a book whose chapters have not been extracted yet is still Content`() {
    // Chapter extraction is an HTTP round trip per file. A screen that treated "not read yet" as
    // "not loaded" would be blank for a second every time a book was opened.
    val content = bookUiState(
      listOf(book("wanted")),
      "wanted",
      BookUiState.Chapters.Reading,
      settings,
    ) as BookUiState.Content

    assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Reading)
    assertThat(content.book.bookId).isEqualTo("wanted")
  }

  @Test
  fun `a book whose chapters could not be read is still Content, with the book intact`() {
    // The point of the whole type. A chapter read fails for every ordinary reason a self-hosted
    // server fails, and none of them is a reason to stop showing the book: the title, the author,
    // the position and the settings all came from Room and are untouched by it.
    val content = bookUiState(
      listOf(book("wanted")),
      "wanted",
      BookUiState.Chapters.Unavailable,
      settings,
    ) as BookUiState.Content

    assertThat(content.chapters).isEqualTo(BookUiState.Chapters.Unavailable)
    assertThat(content.book.title).isEqualTo("Title of wanted")
    assertThat(content.settings).isEqualTo(settings)
  }

  @Test
  fun `an empty chapter list is Ready, which is not the same answer as Unavailable`() {
    // Most audiobook files carry no chapter atoms at all, so "read it, there were none" is the
    // common case and must not render as a failure. A `List` could not tell the two apart, which
    // is why this is a type and not a list -- and `isNotEqualTo` is the assertion that says so.
    val ready = BookUiState.Chapters.Ready(emptyList())

    assertThat(ready.chapters).isEmpty()
    assertThat(ready).isNotEqualTo(BookUiState.Chapters.Unavailable)
    assertThat(ready).isNotEqualTo(BookUiState.Chapters.Reading)
  }
}
