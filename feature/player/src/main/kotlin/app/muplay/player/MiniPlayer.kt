package app.muplay.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing

/**
 * The bar above the library. Renders **nothing at all** when nothing is playing — an empty bar
 * taking 64dp off a browse screen is worse than no bar.
 *
 * Two things changed in the design pass, and one of them is a feature rather than a finish. The
 * play control is an icon carrying [PLAY_LABEL]/[PAUSE_LABEL] as its `contentDescription` (see
 * `PlayerScreen`'s header for why that is the same accessible name and not a weaker one), and the
 * bar now draws a **hairline of progress along its bottom edge**. That line is the only place in
 * the app that answers "how far into this am I" without opening anything, and it costs two pixels.
 *
 * The rule is drawn by [ProgressRule] rather than by `LinearProgressIndicator` for a semantics
 * reason rather than a visual one: this bar's own `contentDescription` is the handle every journey
 * uses to find it, and a describable child inside it would make `onNodeWithContentDescription`
 * ambiguous. See that function.
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

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 3.dp,
  ) {
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .fillMaxWidth()
          // The whole bar opens the player, which is what a user reaches for; the button inside it
          // keeps its own click, so tapping play does not also navigate.
          .clickable(onClick = onOpenPlayer)
          .semantics { contentDescription = MINI_PLAYER_LABEL }
          .padding(MuPlaySpacing.sm),
      ) {
        Artwork(
          uri = content.playback.artworkUri,
          cacheKey = content.playback.mediaId,
          // Null: the bar as a whole is already labelled, and a second description here would read
          // the same element out twice.
          contentDescription = null,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.size(THUMBNAIL_DP.dp),
        )
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = MuPlaySpacing.md),
        ) {
          Text(
            text = content.playback.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = content.playback.artist.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        FilledTonalIconButton(
          onClick = onPlayPause,
          modifier = Modifier.size(MuPlaySpacing.minTouchTarget),
        ) {
          Icon(
            imageVector = if (content.playback.isPlaying) MuPlayIcons.Pause else MuPlayIcons.Play,
            contentDescription = if (content.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL,
            modifier = Modifier.size(GLYPH_DP.dp),
          )
        }
      }
      // A track whose duration is not yet known reads as zero rather than as full: `x / 0` would
      // be a division by zero and `x / 0f` an Infinity, and an Infinity into a width fraction is a
      // bar that claims a track just starting is over.
      ProgressRule(
        fraction = content.playback.durationMs
          .takeIf { it > 0L }
          ?.let { content.displayPositionMs.toFloat() / it }
          ?: 0f,
      )
    }
  }
}

/** The bar's own accessible name, and the handle Task 10's journey taps to open the player. */
internal const val MINI_PLAYER_LABEL = "Now playing"

private const val THUMBNAIL_DP = 48
private const val GLYPH_DP = 22
