package app.muplay.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode
import app.muplay.media.BookChapter
import app.muplay.model.BookSettings

/**
 * One book: what it is, how much is left, and the four things a listener can do to it.
 *
 * The header is a cover beside a title, an author and -- for a book that has been started -- a bar
 * and "how much is left" in the audiobook colour, so the answer to "where am I" is visible before
 * anything is read. Below it the two actions are a filled `Resume` and an outlined
 * `Start from the beginning`, each with a glyph: the words are unchanged and are still what a
 * journey finds, and the icon is what makes the pair distinguishable at a glance.
 *
 * The speed control is the same `- Speed 1.4x +` stepper the player uses, sharing `SLOWER_LABEL`
 * and `FASTER_LABEL` as the two buttons' `contentDescription`s. Two screens that set the same
 * setting should not offer it two different ways.
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
    BookUiState.Loading -> Message(LOADING_BOOK_LABEL, modifier)
    BookUiState.NotFound -> Message(BOOK_NOT_FOUND_LABEL, modifier)
    is BookUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        horizontal = MuPlaySpacing.gutter,
        vertical = MuPlaySpacing.lg,
      ),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
    ) {
      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BookCover(
            coverArtId = state.book.coverArtId,
            sizePx = COVER_DETAIL_PX,
            // Null, as on the shelf: the title is right beside it as text.
            contentDescription = null,
            urlProvider = coverArtUrl,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.size(COVER_DETAIL_DP.dp),
          )
          Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs)) {
            Text(
              text = state.book.title,
              style = MaterialTheme.typography.titleLarge,
              maxLines = TITLE_LINES,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = state.book.author,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = formatRemaining(state.book.remainingMs),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
        }
      }

      // The same "where am I" the shelf draws, and conditional on the same flag for the same
      // reason: a bar at zero on a book nobody has opened says nothing and looks broken.
      if (state.book.hasStarted) {
        item {
          LinearProgressIndicator(
            progress = { state.book.progressFraction.toFloat() },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT_DP.dp),
          )
        }
      }

      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Button(
            onClick = onResume,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.tertiary,
              contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              MuPlayIcons.Play,
              contentDescription = null,
              modifier = Modifier.size(INLINE_GLYPH_DP.dp),
            )
            Text(RESUME_LABEL, modifier = Modifier.padding(start = MuPlaySpacing.sm))
          }
          OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
            Icon(
              MuPlayIcons.RotateBack,
              contentDescription = null,
              modifier = Modifier.size(INLINE_GLYPH_DP.dp),
            )
            Text(
              text = START_OVER_LABEL,
              maxLines = START_OVER_LINES,
              modifier = Modifier.padding(start = MuPlaySpacing.sm),
            )
          }
        }
      }

      item {
        // The speed control on **this** screen writes the book's row rather than the player: a
        // listener can set a book's speed before ever starting it, and `BookPlayerViewModel` is
        // the other direction. They meet at the same row.
        SpeedStepper(
          speed = state.settings.speed,
          onSpeed = onSpeed,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(SKIP_SILENCE_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
          Switch(checked = state.settings.skipSilence, onCheckedChange = onSkipSilence)
        }
      }

      item {
        Text(
          text = CHAPTERS_HEADING,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = MuPlaySpacing.sm),
        )
      }

      // The chapter list is empty until extraction finishes -- an HTTP round trip per file -- and
      // **the rest of the screen has already rendered by then**, which is why this says "reading
      // chapters" rather than "loading". A screen that waited for the whole thing would be blank
      // for a second every time a book was opened.
      if (state.chapters.isEmpty()) {
        item {
          Text(
            text = CHAPTERS_LOADING_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        items(state.chapters, key = { it.index }) { chapter ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onPlayChapter(chapter) }
              .padding(vertical = MuPlaySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = chapterRowLabel(chapter),
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = formatClock(chapter.durationMs),
              style = MaterialTheme.typography.labelSmall.merge(MuPlayTimecode),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

/** Loading, and "that book is gone": one sentence, centred, in the muted voice. */
@Composable
private fun Message(text: String, modifier: Modifier) {
  Box(
    modifier = modifier.fillMaxSize().padding(MuPlaySpacing.xxl),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/** What the book screen asks the server for. Bigger than the shelf's, smaller than the player's. */
private const val COVER_DETAIL_PX = 256
private const val COVER_DETAIL_DP = 104
private const val TITLE_LINES = 2
private const val PROGRESS_HEIGHT_DP = 6
private const val INLINE_GLYPH_DP = 18

/** "Start from the beginning" is four words in half a row; it is allowed to wrap. */
private const val START_OVER_LINES = 2
