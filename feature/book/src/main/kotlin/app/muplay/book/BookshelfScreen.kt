package app.muplay.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.model.BookSummary

/**
 * The audiobook shelf: what the listener is part-way through, then everything else.
 *
 * Split into a stateful entry point and a **stateless** `BookshelfContent`, the shape every screen
 * in this codebase uses, and here it is what would let a Compose test render the shelf without
 * Hilt. `internal` rather than `private` for exactly that reason -- see `PlayerScreen`.
 */
@Composable
fun BookshelfScreen(
  onBookClick: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: BookshelfViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookshelfContent(
    state = state,
    onBookClick = onBookClick,
    // Play first, then navigate -- the order `LibraryScreen` records for its own shuffle rows:
    // navigating away first is what lets `stateIn(WhileSubscribed)` drop the state the resume
    // is being read out of.
    onResume = { bookId ->
      viewModel.resume(bookId)
      onOpenPlayer()
    },
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
internal fun BookshelfContent(
  state: BookshelfUiState,
  onBookClick: (String) -> Unit,
  onResume: (String) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  when (state) {
    BookshelfUiState.Loading -> Text(LOADING_BOOKS_LABEL, modifier.padding(16.dp))
    BookshelfUiState.Empty -> Text(NO_BOOKS_LABEL, modifier.padding(16.dp))
    is BookshelfUiState.Content -> LazyColumn(modifier.fillMaxSize()) {
      // Both headers are conditional, and both conditions are real: a listener who has started
      // everything has no second group, and one who has started nothing has no first. A header
      // over an empty list is a heading for nothing.
      if (state.continueListening.isNotEmpty()) {
        item { SectionHeader(CONTINUE_LISTENING_LABEL) }
        items(state.continueListening, key = { it.bookId }) { book ->
          BookRow(book, onBookClick, onResume, coverArtUrl)
        }
      }
      if (state.rest.isNotEmpty()) {
        item { SectionHeader(BOOKSHELF_TITLE) }
        items(state.rest, key = { it.bookId }) { book ->
          BookRow(book, onBookClick, onResume, coverArtUrl)
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun BookRow(
  book: BookSummary,
  onBookClick: (String) -> Unit,
  onResume: (String) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onBookClick(book.bookId) }
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BookCover(
      coverArtId = book.coverArtId,
      sizePx = COVER_THUMBNAIL_PX,
      // Null: the row already renders the title, and a cover carrying the same string would make
      // an `onNodeWithContentDescription` in a journey ambiguous and read the book out twice.
      // `MiniPlayer` makes the same call for the same reason.
      contentDescription = null,
      urlProvider = coverArtUrl,
      modifier = Modifier.size(56.dp),
    )
    Column(Modifier.weight(1f)) {
      Text(book.title, style = MaterialTheme.typography.titleSmall)
      Text(book.author, style = MaterialTheme.typography.bodySmall)
      // Only for a book that has been started. A progress bar at zero and "under a minute left"
      // on every unopened book turns the shelf into a wall of identical rectangles.
      if (book.hasStarted) {
        LinearProgressIndicator(
          // `.toFloat()`: `BookSummary.progressFraction` is a `Double`, and the plan's listing
          // passed it straight in. It is a Double because it is derived from two `Long`s and
          // narrowing at the source would lose the distinction between 0.0 and "not quite 0".
          progress = { book.progressFraction.toFloat() },
          modifier = Modifier.fillMaxWidth(),
        )
        Text(formatRemaining(book.remainingMs), style = MaterialTheme.typography.bodySmall)
      }
    }
    if (book.hasStarted) {
      TextButton(onClick = { onResume(book.bookId) }) { Text(RESUME_LABEL) }
    }
  }
}

/** What the shelf asks the server for. Matches `LibraryScreen`'s album thumbnails. */
private const val COVER_THUMBNAIL_PX = 128
