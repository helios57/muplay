package app.muplay.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The full-screen player.
 *
 * Every label below is asserted verbatim by the Tier 2 journey (`PlaybackJourneyTest`, Task 10),
 * so a change here is a change there. That duplication is deliberate: the journey is a black-box
 * walk through what a user sees, and a shared string constant would let a wording change pass
 * unnoticed.
 *
 * Split into a stateful entry point and a **stateless** overload, the same shape `LibraryScreen`
 * already uses in this codebase — and here it is what makes the screen testable at all: the
 * stateless one takes a [PlayerUiState] and five lambdas, so `PlayerScreenTest` composes it on a
 * device against a state built by hand, with no media session, no Hilt graph and no server. The
 * overload is `internal` rather than `private` for exactly that reason.
 */
@Composable
fun PlayerScreen(
  modifier: Modifier = Modifier,
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  PlayerScreen(
    uiState = uiState,
    onPlayPause = viewModel::playPause,
    onNext = viewModel::next,
    onPrevious = viewModel::previous,
    onScrubTo = viewModel::scrubTo,
    onScrubFinished = viewModel::commitScrub,
    modifier = modifier,
  )
}

@Composable
internal fun PlayerScreen(
  uiState: PlayerUiState,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  onPrevious: () -> Unit,
  onScrubTo: (Long) -> Unit,
  onScrubFinished: () -> Unit,
  modifier: Modifier = Modifier,
) {
  when (uiState) {
    PlayerUiState.NothingPlaying -> Text(
      text = NOTHING_PLAYING_LABEL,
      textAlign = TextAlign.Center,
      modifier = modifier.fillMaxSize().padding(32.dp),
    )

    is PlayerUiState.Content -> Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Artwork(
        uri = uiState.playback.artworkUri,
        cacheKey = uiState.playback.mediaId,
        contentDescription = ARTWORK_DESCRIPTION,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      )
      Text(text = uiState.playback.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
      Text(text = uiState.playback.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge)
      Text(text = uiState.playback.albumTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium)

      // A player that shows nothing at all while the first chunk arrives reads as frozen, and a
      // long buffer on a slow connection is the moment a user reaches for the back button.
      if (uiState.playback.isBuffering) {
        Text(text = BUFFERING_LABEL, style = MaterialTheme.typography.bodySmall)
      }

      Slider(
        value = uiState.displayPositionMs.toFloat(),
        onValueChange = { onScrubTo(it.toLong()) },
        onValueChangeFinished = onScrubFinished,
        // A zero-width range makes Slider throw; a track whose duration is not yet known renders a
        // full-width bar rather than crashing.
        valueRange = 0f..uiState.playback.durationMs.coerceAtLeast(1L).toFloat(),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(text = formatDuration(uiState.displayPositionMs))
        Text(text = formatDuration(uiState.playback.durationMs))
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Disabled, not hidden: a control that vanishes at the ends of a queue moves the two
        // beside it under the user's thumb.
        TextButton(onClick = onPrevious, enabled = uiState.playback.hasPrevious) {
          Text(PREVIOUS_LABEL)
        }
        Button(onClick = onPlayPause) {
          Text(if (uiState.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL)
        }
        TextButton(onClick = onNext, enabled = uiState.playback.hasNext) {
          Text(NEXT_LABEL)
        }
      }
    }
  }
}

/**
 * Text labels rather than icons, deliberately: they are what `onNodeWithText` finds in the Tier 2
 * journey, and this project has no icon-content-description convention yet. Switching to icons
 * means giving each one a `contentDescription` equal to the label here and changing the journey to
 * `onNodeWithContentDescription` — a change in two places, on purpose.
 */
internal const val NOTHING_PLAYING_LABEL = "Nothing playing"
internal const val ARTWORK_DESCRIPTION = "Cover art"
internal const val BUFFERING_LABEL = "Buffering…"
internal const val PLAY_LABEL = "Play"
internal const val PAUSE_LABEL = "Pause"
internal const val NEXT_LABEL = "Next"
internal const val PREVIOUS_LABEL = "Previous"
