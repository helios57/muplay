package app.muplay.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The bar above the library. Renders **nothing at all** when nothing is playing — an empty bar
 * taking 64dp off a browse screen is worse than no bar.
 *
 * Same stateful/stateless split as `PlayerScreen`, and for the same reason: the stateless overload
 * is what `MiniPlayerTest` composes on a device without a media session.
 */
@Composable
fun MiniPlayer(
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  MiniPlayer(
    uiState = uiState,
    onOpenPlayer = onOpenPlayer,
    onPlayPause = viewModel::playPause,
    modifier = modifier,
  )
}

@Composable
internal fun MiniPlayer(
  uiState: PlayerUiState,
  onOpenPlayer: () -> Unit,
  onPlayPause: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val content = uiState as? PlayerUiState.Content ?: return

  Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        // The whole bar opens the player, which is what a user reaches for; the button inside it
        // keeps its own click, so tapping play does not also navigate.
        .clickable(onClick = onOpenPlayer)
        .semantics { contentDescription = MINI_PLAYER_LABEL }
        .padding(8.dp),
    ) {
      Artwork(
        uri = content.playback.artworkUri,
        cacheKey = content.playback.mediaId,
        // Null: the bar as a whole is already labelled, and a second description here would read
        // the same element out twice.
        contentDescription = null,
        modifier = Modifier.size(48.dp),
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 12.dp),
      ) {
        Text(
          text = content.playback.title.orEmpty(),
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = content.playback.artist.orEmpty(),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      TextButton(onClick = onPlayPause) {
        Text(if (content.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL)
      }
    }
  }
}

/** The bar's own accessible name, and the handle Task 10's journey taps to open the player. */
internal const val MINI_PLAYER_LABEL = "Now playing"
