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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.BookSummary

/**
 * The audiobook shelf: what the listener is part-way through, then everything else.
 *
 * **A shelf of objects rather than a list of strings**, which is the one job this screen has. Each
 * book is a card on its own surface with a rounded cover, its title above its author, and -- for a
 * book that has been started -- a thick progress bar in the audiobook colour with "how much is
 * left" beside it. Before this pass the rows were a cover, three `Text`s at three sizes and a
 * hairline indicator, all flush against the page, and the answer to "where am I in this" was a
 * sentence you had to read.
 *
 * The two headings are eyebrows: sentence case, `labelMedium`, wide tracking, in the muted colour,
 * over a hairline rule. **Not** `text.uppercase()` -- `Continue listening` and `Books` are this
 * feature's contract with its journeys (see `BookLabels.kt`), and transforming the string would
 * break every finder while a screen reader spelled the result out. Tracking buys the same effect
 * and changes no character.
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
    BookshelfUiState.Loading -> Centred(modifier) { Message(text = LOADING_BOOKS_LABEL, loading = true) }
    BookshelfUiState.Empty -> Centred(modifier) { Message(text = NO_BOOKS_LABEL) }
    is BookshelfUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        horizontal = MuPlaySpacing.lg,
        vertical = MuPlaySpacing.md,
      ),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    ) {
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

/**
 * Loading, and "no audiobooks yet", in the middle of an otherwise empty screen.
 *
 * The sentence is `:core:designsystem`'s [Message] now -- one component for every "nothing here",
 * "still loading" and "that did not work" in this app, rather than a bare `Text` per screen. What
 * stays here is the *vertical* centring: `Message` centres its own content horizontally and takes
 * no position on the page, so a caller that wants it in the middle of a full screen says so.
 */
@Composable
private fun Centred(modifier: Modifier, content: @Composable () -> Unit) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * An eyebrow: sentence case, `labelMedium`'s wide tracking, muted -- **over the hairline rule this
 * file's own header has always described.**
 *
 * The rule did not exist. The KDoc above said "over a hairline rule" through two passes while
 * `SectionHeader` drew a `Text` and nothing else, which is exactly the drift this repository keeps
 * catching in its own comments: prose describing something absent reads, to the next person, as
 * something they have already got. Drawing it is the cheaper of the two fixes and the better one --
 * it is what makes `Continue listening` read as a division of the shelf rather than as one more
 * small grey line above a card.
 *
 * [Dp.Hairline] rather than `DividerDefaults.Thickness`: a true one-pixel line at any density, which
 * at `outlineVariant` is a seam rather than a border. A 1dp rule here reads as a table.
 */
@Composable
private fun SectionHeader(text: String) {
  Column(
    modifier = Modifier.padding(top = MuPlaySpacing.lg, bottom = MuPlaySpacing.sm),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier
        .padding(start = MuPlaySpacing.xs)
        // The two groups are what this screen is: a screen reader should be able to jump between
        // them the way a sighted listener jumps between the two rules.
        .semantics { heading() },
    )
    HorizontalDivider(
      thickness = Dp.Hairline,
      color = MaterialTheme.colorScheme.outlineVariant,
    )
  }
}

@Composable
private fun BookRow(
  book: BookSummary,
  onBookClick: (String) -> Unit,
  onResume: (String) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
) {
  // `Surface` for the card, and the click on the `Row` inside it rather than on the `Surface`
  // itself. That is a semantics decision, not a style one: `Surface(onClick = ...)` merges its
  // descendants, which would fold each row's title, author, time-left and `Resume` into one node
  // and change what every finder in `BookshelfContentTest` resolves to. `Modifier.clickable` does
  // not merge, so the tree this shelf presents is exactly the one it presented before the design
  // pass.
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .clickable { onBookClick(book.bookId) }
        .padding(MuPlaySpacing.md),
      horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
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
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.size(COVER_THUMBNAIL_DP.dp),
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs),
      ) {
        Text(
          text = book.title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = TITLE_LINES,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = book.author,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Only for a book that has been started. A progress bar at zero and "under a minute left"
        // on every unopened book turns the shelf into a wall of identical rectangles.
        if (book.hasStarted) {
          LinearProgressIndicator(
            // `.toFloat()`: `BookSummary.progressFraction` is a `Double`, and the plan's listing
            // passed it straight in. It is a Double because it is derived from two `Long`s and
            // narrowing at the source would lose the distinction between 0.0 and "not quite 0".
            progress = { book.progressFraction.toFloat() },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = MuPlaySpacing.xs)
              .height(PROGRESS_HEIGHT_DP.dp),
          )
          Text(
            text = formatRemaining(book.remainingMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
      if (book.hasStarted) {
        // Icon **and** text. The glyph is what makes the shelf scannable; the word is what a
        // journey finds and what makes the action unambiguous for anyone who has not met the
        // glyph before. `RESUME_LABEL` is unchanged and is still this button's text.
        FilledTonalButton(
          onClick = { onResume(book.bookId) },
          // `tertiaryContainer`, not `FilledTonalButton`'s default `secondaryContainer`. The
          // palette's thesis is that `primary`/`secondary` are the music voice and `tertiary` the
          // audiobook one; the most-pressed control on the audiobook shelf was speaking the wrong
          // one, purely because nobody had named a colour.
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
          ),
          contentPadding = PaddingValues(
            horizontal = MuPlaySpacing.md,
            vertical = MuPlaySpacing.sm,
          ),
        ) {
          Icon(
            MuPlayIcons.Play,
            contentDescription = null,
            modifier = Modifier.size(INLINE_GLYPH_DP.dp),
          )
          Text(RESUME_LABEL, modifier = Modifier.padding(start = MuPlaySpacing.sm))
        }
      }
    }
  }
}

/** What the shelf asks the server for. Matches `LibraryScreen`'s album thumbnails. */
private const val COVER_THUMBNAIL_PX = 128
private const val COVER_THUMBNAIL_DP = 64
private const val TITLE_LINES = 2
private const val PROGRESS_HEIGHT_DP = 5
private const val INLINE_GLYPH_DP = 16
