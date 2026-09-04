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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.BookVoice
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode
import app.muplay.media.BookChapter

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
 *
 * ### Spacing is per item rather than a list-wide `verticalArrangement`
 *
 * The `LazyColumn` used to space every child by `lg`, which is right for the five blocks at the top
 * and wrong for a chapter list: with a 48dp row and 16dp between rows the list reads as fifty
 * separate cards. The four blocks carry their own bottom padding instead, and the chapter rows sit
 * directly on each other with their height doing the separating.
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
    onRetryChapters = viewModel::retryChapters,
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
  onRetryChapters: () -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  BookVoice {
    when (state) {
      BookUiState.Loading -> Centred(modifier) { Message(text = LOADING_BOOK_LABEL, loading = true) }
      BookUiState.NotFound -> Centred(modifier) { Message(text = BOOK_NOT_FOUND_LABEL) }
      is BookUiState.Content -> LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          horizontal = MuPlaySpacing.gutter,
          vertical = MuPlaySpacing.lg,
        ),
      ) {
        item {
          Column(
            modifier = Modifier.padding(bottom = MuPlaySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
          ) {
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
                  // The screen's subject. TalkBack's heading navigation is how somebody using a
                  // screen reader skips the header and gets to the chapters.
                  modifier = Modifier.semantics { heading() },
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

            // The same "where am I" the shelf draws, and conditional on the same flag for the same
            // reason: a bar at zero on a book nobody has opened says nothing and looks broken.
            if (state.book.hasStarted) {
              LinearProgressIndicator(
                progress = { state.book.progressFraction.toFloat() },
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT_DP.dp),
              )
            }
          }
        }

        item {
          Row(
            horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
            modifier = Modifier.fillMaxWidth().padding(bottom = MuPlaySpacing.lg),
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
            modifier = Modifier.fillMaxWidth().padding(bottom = MuPlaySpacing.sm),
          )
        }

        item {
          SkipSilenceRow(
            checked = state.settings.skipSilence,
            onCheckedChange = onSkipSilence,
          )
        }

        item {
          Text(
            text = CHAPTERS_HEADING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
              .padding(top = MuPlaySpacing.sm, bottom = MuPlaySpacing.xs)
              .semantics { heading() },
          )
        }

        // Three arms, and every one of them leaves the four blocks above on screen. Extraction is an
        // HTTP round trip per file, so it is slow when it works and it fails for every ordinary
        // reason a self-hosted server fails -- and **neither is a reason to take the book down**:
        // `Resume`, the progress bar and both settings need no chapters at all.
        //
        // `Reading` says "reading chapters" rather than "loading" because the rest of the screen has
        // already drawn by then, and "loading" over a fully drawn book reads as a stuck screen.
        // `Unavailable` is the first caller of `Message`'s `onRetry` in this app: the read is a
        // transport failure, so the same read a minute later usually works, and the button is how a
        // listener says so without leaving the screen and coming back.
        when (val chapters = state.chapters) {
          BookUiState.Chapters.Reading -> item {
            Message(text = CHAPTERS_LOADING_LABEL, loading = true)
          }

          BookUiState.Chapters.Unavailable -> item {
            Message(
              text = CHAPTERS_UNAVAILABLE_LABEL,
              onRetry = onRetryChapters,
              retryLabel = RETRY_CHAPTERS_LABEL,
            )
          }

          is BookUiState.Chapters.Ready -> items(chapters.chapters, key = { it.index }) { chapter ->
            ChapterRow(chapter = chapter, onClick = { onPlayChapter(chapter) })
          }
        }
      }
    }
  }
}

/**
 * `Skip silence`, as **one** control rather than a label and a switch that happen to be adjacent.
 *
 * The word and the `Switch` used to be two sibling semantics nodes, so TalkBack read out an
 * unlabelled switch and, separately, a piece of text -- with nothing saying they were the same
 * thing. `Modifier.toggleable` on the row merges them, gives the setting the whole row as a target
 * (rather than the 52dp of the switch), and states the [Role.Switch] the merged node then reports.
 *
 * The `Switch` takes `onCheckedChange = null` because the row owns the gesture now; two toggleables
 * would be two nodes again, which is what `BookContentTest`'s `onNode(isToggleable())` would then
 * fail on -- deliberately, since that finder is the thing pinning this shape.
 */
@Composable
private fun SkipSilenceRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = MuPlaySpacing.sm)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
  ) {
    Text(SKIP_SILENCE_LABEL, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = null)
  }
}

/**
 * One chapter and its own length, at least [MuPlaySpacing.minTouchTarget] tall.
 *
 * It measured about 44dp before this pass -- `bodyMedium`'s 20dp line box plus 12dp of padding
 * either side -- which is under the 48dp Android's accessibility guidance and Material's
 * `minimumInteractiveComponentSize` both name. `heightIn` sits outside `clickable`, so the row's
 * ripple and hit rectangle are the tall ones rather than the text's own box.
 */
@Composable
private fun ChapterRow(chapter: BookChapter, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .clickable(onClick = onClick)
      .padding(vertical = MuPlaySpacing.xs),
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

/**
 * Loading, and "that book is gone", in the middle of an otherwise empty screen.
 *
 * The sentence itself is `:core:designsystem`'s [Message] now -- one component for every "nothing
 * here", "still loading" and "that did not work" in the app, which is what stops four screens
 * inventing four voices. What stays here is the *vertical* centring: `Message` centres its own
 * content horizontally and deliberately takes no position on the page, so a caller that wants it in
 * the middle of a full screen says so.
 */
@Composable
private fun Centred(modifier: Modifier, content: @Composable () -> Unit) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** What the book screen asks the server for. Bigger than the shelf's, smaller than the player's. */
private const val COVER_DETAIL_PX = 256
private const val COVER_DETAIL_DP = 104
private const val TITLE_LINES = 2
private const val PROGRESS_HEIGHT_DP = 6
private const val INLINE_GLYPH_DP = 18

/** "Start from the beginning" is four words in half a row; it is allowed to wrap. */
private const val START_OVER_LINES = 2
