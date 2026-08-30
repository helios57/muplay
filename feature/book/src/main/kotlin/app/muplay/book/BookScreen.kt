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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.media.BookChapter
import app.muplay.model.BookSettings

/**
 * One book: what it is, how much is left, and the four things a listener can do to it.
 *
 * **[bookId] is an ordinary parameter, forwarded from a `LaunchedEffect`**, not a
 * `SavedStateHandle` argument. Navigation 3 populates nothing from a `NavKey`'s own properties;
 * `AlbumScreen` carries the device transcript of the crash that proved it.
 */
@Composable
fun BookScreen(
  bookId: String,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: BookViewModel = hiltViewModel(),
) {
  LaunchedEffect(bookId) { viewModel.load(bookId) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookContent(
    state = state,
    // Start playing, then open the player, for the reason `BookshelfScreen` gives.
    onResume = {
      viewModel.resume()
      onOpenPlayer()
    },
    onRestart = {
      viewModel.restart()
      onOpenPlayer()
    },
    onPlayChapter = { chapter ->
      viewModel.playChapter(chapter)
      onOpenPlayer()
    },
    onSpeed = viewModel::setSpeed,
    onSkipSilence = viewModel::setSkipSilence,
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
internal fun BookContent(
  state: BookUiState,
  onResume: () -> Unit,
  onRestart: () -> Unit,
  onPlayChapter: (BookChapter) -> Unit,
  onSpeed: (Float) -> Unit,
  onSkipSilence: (Boolean) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  when (state) {
    BookUiState.Loading -> Text(LOADING_BOOK_LABEL, modifier.padding(16.dp))
    BookUiState.NotFound -> Text(BOOK_NOT_FOUND_LABEL, modifier.padding(16.dp))
    is BookUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BookCover(
            coverArtId = state.book.coverArtId,
            sizePx = COVER_DETAIL_PX,
            // Null, as on the shelf: the title is right beside it as text.
            contentDescription = null,
            urlProvider = coverArtUrl,
            modifier = Modifier.size(96.dp),
          )
          Column {
            Text(state.book.title, style = MaterialTheme.typography.titleLarge)
            Text(state.book.author, style = MaterialTheme.typography.bodyMedium)
            Text(formatRemaining(state.book.remainingMs), style = MaterialTheme.typography.bodySmall)
          }
        }
      }

      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text(RESUME_LABEL) }
          OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
            Text(START_OVER_LABEL)
          }
        }
      }

      item {
        // The speed control on **this** screen writes the book's row rather than the player: a
        // listener can set a book's speed before ever starting it, and `BookPlayerViewModel` is
        // the other direction. They meet at the same row.
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { onSpeed(state.settings.speed - BookSettings.SPEED_STEP) }) {
            Text(SLOWER_LABEL)
          }
          Text(formatSpeed(state.settings.speed))
          TextButton(onClick = { onSpeed(state.settings.speed + BookSettings.SPEED_STEP) }) {
            Text(FASTER_LABEL)
          }
        }
      }

      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(SKIP_SILENCE_LABEL)
          Switch(checked = state.settings.skipSilence, onCheckedChange = onSkipSilence)
        }
      }

      item { Text(CHAPTERS_HEADING, style = MaterialTheme.typography.titleMedium) }

      // The chapter list is empty until extraction finishes -- an HTTP round trip per file -- and
      // **the rest of the screen has already rendered by then**, which is why this says "reading
      // chapters" rather than "loading". A screen that waited for the whole thing would be blank
      // for a second every time a book was opened.
      if (state.chapters.isEmpty()) {
        item { Text(CHAPTERS_LOADING_LABEL, style = MaterialTheme.typography.bodySmall) }
      } else {
        items(state.chapters, key = { it.index }) { chapter ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPlayChapter(chapter) }
              .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(chapterRowLabel(chapter), modifier = Modifier.weight(1f))
            Text(formatClock(chapter.durationMs), style = MaterialTheme.typography.bodySmall)
          }
        }
      }
    }
  }
}

/** What the book screen asks the server for. Bigger than the shelf's, smaller than the player's. */
private const val COVER_DETAIL_PX = 256
