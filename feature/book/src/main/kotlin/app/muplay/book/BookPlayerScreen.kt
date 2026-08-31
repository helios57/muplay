package app.muplay.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.designsystem.theme.MuPlayTimecode
import app.muplay.media.BookChapter
import app.muplay.media.SleepTimerController
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState

/**
 * The audiobook player -- a different instrument from `:feature:player`'s, not a mode of it.
 *
 * Next and previous mean **chapter**, a plus/minus thirty-second nudge matters more than a
 * scrubber, the speed control is used constantly, the sleep timer lives here, and the progress a
 * listener cares about is "three hours left in this book" rather than "1:42 of 4:03".Which of the
 * two players opens is decided in `:app` from `PlaybackState.isAudiobook` and nowhere else;
 * neither feature module knows the other exists.
 *
 * ### The controls are icons, and every label constant survived unchanged
 *
 * This file used to say that every control was a text button "which is this project's convention",
 * and that switching to icons meant `material-icons-extended`, which is banned. `:core:designsystem`
 * now draws the ten glyphs this app needs, including the two -- the wind-back and wind-forward
 * rings -- that were the reason for the banned artifact. So `BACK_30_LABEL`, `PLAY_LABEL`,
 * `NEXT_CHAPTER_LABEL` and the rest are still the strings in `BookLabels.kt`, still not reworded,
 * and still the accessible name of the control they name: they are `contentDescription`s on icons
 * instead of the text of text buttons. A journey reads them with `onNodeWithContentDescription`
 * rather than `onNodeWithText`; that is a change of finder, not of contract.
 *
 * **The `30` inside the two nudge buttons is drawn as text over the ring and carries no semantics
 * of its own** (`clearAndSetSemantics { }`). Baking numerals into a vector path would have to be
 * redrawn the day the nudge becomes configurable, and letting the digits describe themselves would
 * make a screen reader say "Back 30 seconds, 30".
 *
 * What is deliberately still text: the speed (`Speed 1.4x` -- no glyph carries a number), the sleep
 * timer's own button (while a timer runs the button **is** the countdown), the presets, the chapter
 * counter and the clocks.
 */
@Composable
fun BookPlayerScreen(
  modifier: Modifier = Modifier,
  viewModel: BookPlayerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookPlayerContent(
    state = state,
    onPlayPause = viewModel::playPause,
    onPreviousChapter = viewModel::previousChapter,
    onNextChapter = viewModel::nextChapter,
    onNudge = viewModel::nudge,
    onSpeed = viewModel::setSpeed,
    onChapter = viewModel::seekTo,
    onSleepPreset = { viewModel.startSleepTimer(SleepTimerRequest.Duration(it)) },
    onEndOfChapter = viewModel::endOfChapterTimer,
    onCancelTimer = viewModel::cancelSleepTimer,
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
internal fun BookPlayerContent(
  state: BookPlayerUiState,
  onPlayPause: () -> Unit,
  onPreviousChapter: () -> Unit,
  onNextChapter: () -> Unit,
  onNudge: (Long) -> Unit,
  onSpeed: (Float) -> Unit,
  onChapter: (BookChapter) -> Unit,
  onSleepPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancelTimer: () -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  when (state) {
    BookPlayerUiState.NothingPlaying -> Box(
      modifier = modifier.fillMaxSize().padding(MuPlaySpacing.xxl),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = NOTHING_PLAYING_LABEL,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }

    is BookPlayerUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxSize().padding(horizontal = MuPlaySpacing.gutter),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = MuPlaySpacing.xl),
    ) {
      item {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          BookCover(
            coverArtId = state.coverArtId,
            sizePx = COVER_PLAYER_PX,
            contentDescription = BOOK_COVER_LABEL,
            urlProvider = coverArtUrl,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(COVER_PLAYER_DP.dp),
          )
        }
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.xs)) {
          Text(
            text = state.bookTitle,
            style = MaterialTheme.typography.titleLarge,
            maxLines = TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
          )
          // The chapter is the audiobook voice: the shelf, this line and "how much is left" are
          // the three places a listener looks, and they are the three things lit in `tertiary`.
          Text(
            text = state.chapterTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          // Only once chapters are known. "Chapter 0 of 0" on a book whose extraction has not
          // returned is worse than saying nothing.
          if (state.chapterCount > 0) {
            Text(
              text = "Chapter ${state.chapterNumber} of ${state.chapterCount}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
          LinearProgressIndicator(
            progress = {
              // The guard is the `0` an unmeasured duration arrives as -- `x / 0f` is Infinity, and
              // an Infinity into a progress bar renders as full for a book that has not started.
              if (state.chapterDurationMs <= 0L) 0f
              else state.positionInChapterMs.toFloat() / state.chapterDurationMs
            },
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT_DP.dp),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            // One string rather than two nodes: it reads as a fraction ("where I am, out of how
            // long this chapter is"), and the journey asserts the whole sentence.
            Text(
              text = "${formatClock(state.positionInChapterMs)} / ${formatClock(state.chapterDurationMs)}",
              style = MaterialTheme.typography.labelSmall.merge(MuPlayTimecode),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = formatRemaining(state.bookRemainingMs),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
        }
      }

      // **One row, five controls, in the order a thumb expects them**: chapter back, thirty back,
      // play, thirty forward, chapter next. This used to be two rows -- chapters above, nudges and
      // play below -- which put the two most-used controls in different places and made the play
      // button the same size as everything else. Five icons fit across a 360dp phone with room to
      // spare; five *words* never did.
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ChapterButton(MuPlayIcons.SkipPrevious, PREVIOUS_CHAPTER_LABEL, onPreviousChapter)
          NudgeButton(MuPlayIcons.RotateBack, BACK_30_LABEL) { onNudge(-NUDGE_MS) }
          FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(MuPlaySpacing.transportPrimary),
          ) {
            Icon(
              imageVector = if (state.isPlaying) MuPlayIcons.Pause else MuPlayIcons.Play,
              contentDescription = if (state.isPlaying) PAUSE_LABEL else PLAY_LABEL,
              modifier = Modifier.size(PRIMARY_GLYPH_DP.dp),
            )
          }
          NudgeButton(MuPlayIcons.RotateForward, FORWARD_30_LABEL) { onNudge(NUDGE_MS) }
          ChapterButton(MuPlayIcons.SkipNext, NEXT_CHAPTER_LABEL, onNextChapter)
        }
      }

      item {
        SpeedStepper(
          speed = state.speed,
          onSpeed = onSpeed,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      item { SleepTimerRow(state.sleepTimer, onSleepPreset, onEndOfChapter, onCancelTimer) }

      items(state.chapters, key = { it.index }) { chapter ->
        Text(
          text = chapterRowLabel(chapter),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onChapter(chapter) }
            .padding(vertical = MuPlaySpacing.md),
        )
      }
    }
  }
}

/** Chapter back and forward: the quietest pair, at the ends of the row. */
@Composable
private fun ChapterButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  IconButton(
    onClick = onClick,
    colors = IconButtonDefaults.iconButtonColors(
      contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
  ) {
    Icon(icon, contentDescription = label, modifier = Modifier.size(CHAPTER_GLYPH_DP.dp))
  }
}

/**
 * A thirty-second nudge: the ring, with `30` drawn inside it.
 *
 * The numerals are a `Text` with [clearAndSetSemantics], so the control has exactly one accessible
 * name -- the label the button already carried -- rather than a name plus a loose "30".
 */
@Composable
private fun NudgeButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  FilledTonalIconButton(
    onClick = onClick,
    modifier = Modifier.size(MuPlaySpacing.transportSecondary),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = label, modifier = Modifier.size(NUDGE_GLYPH_DP.dp))
      Text(
        text = NUDGE_SECONDS,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clearAndSetSemantics { },
      )
    }
  }
}

/**
 * The sleep timer, and **the layout defect this pass came here to fix.**
 *
 * `SleepTimerRow` used to put the six presets and `End of chapter` into a plain, non-scrolling
 * `Row`. Seven controls is wider than any phone, so the last of them were clipped off the right
 * edge and could not be tapped at all. `BookPlayerContentTest` recorded that in its own prose --
 * it asserted `assertExists` rather than `assertIsDisplayed`, and reached `End of chapter` through
 * `performSemanticsAction(OnClick)` because `performClick` refuses a node that is not displayed,
 * with a comment saying the answer on a real phone was "very likely no". It was: on `muplay37`
 * (411dp wide) the row needs about 560dp.
 *
 * A `FlowRow` fixes it by **wrapping** rather than by scrolling, and the difference matters here.
 * A horizontally scrolling row hides options behind a gesture nobody knows to make; wrapping shows
 * a half-asleep listener all seven at once, which is the entire job of this control. Those tests
 * now assert `assertIsDisplayed` and use a real `performClick`, so they fail against the old
 * layout and pass against this one.
 *
 * Chips rather than buttons, and Material's chips are 32dp tall -- but `Surface`'s
 * `minimumInteractiveComponentSize` still gives each one a 48dp touch target, which is why the
 * vertical spacing below is [MuPlaySpacing.md] rather than [MuPlaySpacing.sm]: at 8dp the
 * neighbouring rows' touch targets would overlap.
 */
@Composable
private fun SleepTimerRow(
  timer: SleepTimerState,
  onPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancel: () -> Unit,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // While a timer runs the button **is** the countdown, so how long is left is visible without
      // opening anything -- which is the whole affordance for somebody half asleep.
      OutlinedButton(onClick = { open = !open }) {
        Icon(
          MuPlayIcons.Moon,
          contentDescription = null,
          modifier = Modifier.size(INLINE_GLYPH_DP.dp),
        )
        Text(
          text = if (timer is SleepTimerState.Running) formatClock(timer.remainingMs) else SLEEP_TIMER_LABEL,
          modifier = Modifier.padding(start = MuPlaySpacing.sm),
        )
      }
      if (timer is SleepTimerState.Running) {
        IconButton(onClick = onCancel) {
          Icon(MuPlayIcons.Close, contentDescription = CANCEL_TIMER_LABEL)
        }
      }
    }
    if (open) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
        modifier = Modifier.fillMaxWidth(),
      ) {
        // `SleepTimerController.PRESETS`, not a list typed out here: the controller owns what a
        // preset is, and a second list is a second answer that drifts.
        SleepTimerController.PRESETS.forEach { preset ->
          SuggestionChip(
            onClick = {
              onPreset(preset)
              open = false
            },
            label = { Text("${preset / 60_000} min") },
          )
        }
        SuggestionChip(
          onClick = {
            onEndOfChapter()
            open = false
          },
          label = { Text(END_OF_CHAPTER_LABEL) },
        )
      }
    }
  }
}

/** The biggest cover this app asks for; it fills most of the player. */
private const val COVER_PLAYER_PX = 512
private const val COVER_PLAYER_DP = 240
private const val TITLE_LINES = 2
private const val PROGRESS_HEIGHT_DP = 6
private const val PRIMARY_GLYPH_DP = 32
private const val CHAPTER_GLYPH_DP = 26
private const val NUDGE_GLYPH_DP = 34
private const val INLINE_GLYPH_DP = 18

/**
 * The numerals inside the two nudge rings. Derived from [NUDGE_MS] so the drawing and the seek can
 * never disagree -- the label constants beside them are already derived from nothing else.
 */
private const val NUDGE_SECONDS_VALUE = NUDGE_MS / 1000L
private val NUDGE_SECONDS = NUDGE_SECONDS_VALUE.toString()
