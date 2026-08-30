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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.media.BookChapter
import app.muplay.media.SleepTimerController
import app.muplay.model.BookSettings
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState

/**
 * The audiobook player -- a different instrument from `:feature:player`'s, not a mode of it.
 *
 * Next and previous mean **chapter**, a plus/minus thirty-second nudge matters more than a
 * scrubber, the speed control is used constantly, the sleep timer lives here, and the progress a
 * listener cares about is "three hours left in this book" rather than "1:42 of 4:03". Which of the
 * two players opens is decided in `:app` from `PlaybackState.isAudiobook` and nowhere else;
 * neither feature module knows the other exists.
 *
 * Every control is a text button rather than an icon, which is this project's convention (there is
 * no `Icons.` anywhere in the tree, and the plan's `Replay30`/`Forward30` are in
 * `material-icons-extended`, which is banned). The visible text **is** the accessible name, so
 * every string in `BookLabels.kt` is findable by both `onNodeWithText` and a screen reader.
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
    BookPlayerUiState.NothingPlaying -> Text(NOTHING_PLAYING_LABEL, modifier.padding(16.dp))
    is BookPlayerUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        BookCover(
          coverArtId = state.coverArtId,
          sizePx = COVER_PLAYER_PX,
          contentDescription = BOOK_COVER_LABEL,
          urlProvider = coverArtUrl,
          modifier = Modifier.size(200.dp),
        )
      }
      item {
        Text(state.bookTitle, style = MaterialTheme.typography.titleLarge)
        Text(state.chapterTitle, style = MaterialTheme.typography.titleMedium)
      }
      // Only once chapters are known. "Chapter 0 of 0" on a book whose extraction has not returned
      // is worse than saying nothing.
      if (state.chapterCount > 0) {
        item { Text("Chapter ${state.chapterNumber} of ${state.chapterCount}") }
      }
      item {
        LinearProgressIndicator(
          progress = {
            // The guard is the `0` an unmeasured duration arrives as -- `x / 0f` is Infinity, and
            // an Infinity into a progress bar renders as full for a book that has not started.
            if (state.chapterDurationMs <= 0L) 0f
            else state.positionInChapterMs.toFloat() / state.chapterDurationMs
          },
          modifier = Modifier.fillMaxWidth(),
        )
        Text("${formatClock(state.positionInChapterMs)} / ${formatClock(state.chapterDurationMs)}")
        Text(formatRemaining(state.bookRemainingMs))
      }

      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(onClick = onPreviousChapter, modifier = Modifier.weight(1f)) {
            Text(PREVIOUS_CHAPTER_LABEL)
          }
          OutlinedButton(onClick = onNextChapter, modifier = Modifier.weight(1f)) {
            Text(NEXT_CHAPTER_LABEL)
          }
        }
      }

      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(onClick = { onNudge(-NUDGE_MS) }, modifier = Modifier.weight(1f)) {
            Text(BACK_30_LABEL)
          }
          Button(onClick = onPlayPause, modifier = Modifier.weight(1f)) {
            Text(if (state.isPlaying) PAUSE_LABEL else PLAY_LABEL)
          }
          OutlinedButton(onClick = { onNudge(NUDGE_MS) }, modifier = Modifier.weight(1f)) {
            Text(FORWARD_30_LABEL)
          }
        }
      }

      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { onSpeed(state.speed - BookSettings.SPEED_STEP) }) {
            Text(SLOWER_LABEL)
          }
          // The raw sum is asked for and the clamp is `setSpeed`'s: two places that both clamp is
          // two places that are each half right.
          Text(formatSpeed(state.speed))
          TextButton(onClick = { onSpeed(state.speed + BookSettings.SPEED_STEP) }) {
            Text(FASTER_LABEL)
          }
        }
      }

      item { SleepTimerRow(state.sleepTimer, onSleepPreset, onEndOfChapter, onCancelTimer) }

      items(state.chapters, key = { it.index }) { chapter ->
        Text(
          chapterRowLabel(chapter),
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onChapter(chapter) }
            .padding(vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun SleepTimerRow(
  timer: SleepTimerState,
  onPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancel: () -> Unit,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // While a timer runs the button **is** the countdown, so how long is left is visible without
      // opening anything -- which is the whole affordance for somebody half asleep.
      TextButton(onClick = { open = !open }) {
        Text(if (timer is SleepTimerState.Running) formatClock(timer.remainingMs) else SLEEP_TIMER_LABEL)
      }
      if (timer is SleepTimerState.Running) {
        TextButton(onClick = onCancel) { Text(CANCEL_TIMER_LABEL) }
      }
    }
    if (open) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // `SleepTimerController.PRESETS`, not a list typed out here: the controller owns what a
        // preset is, and a second list is a second answer that drifts.
        SleepTimerController.PRESETS.forEach { preset ->
          TextButton(
            onClick = {
              onPreset(preset)
              open = false
            },
          ) {
            Text("${preset / 60_000} min")
          }
        }
        TextButton(
          onClick = {
            onEndOfChapter()
            open = false
          },
        ) {
          Text(END_OF_CHAPTER_LABEL)
        }
      }
    }
  }
}

/** The biggest cover this app asks for; it fills most of the player. */
private const val COVER_PLAYER_PX = 512
