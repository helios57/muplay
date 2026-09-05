package app.muplay.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.Message
import app.muplay.designsystem.theme.MuPlayIcons
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.model.FolderNode
import app.muplay.model.Song

/**
 * Browsing the library by folder, and shuffling a folder with everything under it.
 *
 * One screen per folder, pushed onto the back stack, so the system back gesture is what walks back
 * up the tree -- there is no bespoke "up" control to get out of step with it, and a user who has
 * descended six folders leaves the same way they came.
 *
 * [path] is read off the navigation key at the call site and handed to the ViewModel from a
 * `LaunchedEffect`, exactly as `AlbumScreen` does with `albumId`: Navigation 3 populates no
 * `SavedStateHandle` argument from a key's own properties, and `AlbumViewModel`'s KDoc carries the
 * device crash transcript that established it.
 */
@Composable
fun FolderScreen(
  path: String,
  onOpenFolder: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: FolderViewModel = hiltViewModel(),
) {
  LaunchedEffect(path) { viewModel.open(path) }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  val state = uiState
  if (state == null) {
    Message(text = FOLDER_LOADING_LABEL, loading = true, modifier = modifier)
    return
  }
  FolderScreen(
    uiState = state,
    onOpenFolder = onOpenFolder,
    onShuffle = { viewModel.shuffleFolder(); onOpenPlayer() },
    onPlayAll = { viewModel.playFolder(); onOpenPlayer() },
    onTrackClick = { index -> viewModel.playTrack(index); onOpenPlayer() },
    modifier = modifier,
  )
}

@Composable
private fun FolderScreen(
  uiState: FolderUiState,
  onOpenFolder: (String) -> Unit,
  onShuffle: () -> Unit,
  onPlayAll: () -> Unit,
  onTrackClick: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(MuPlaySpacing.gutter),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
  ) {
    if (uiState.canShuffle) {
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm)) {
          // Shuffle first and filled: it is what the user asked this screen for, and on a folder
          // of a hundred files it is the only one of the two anybody taps.
          Button(onClick = onShuffle) {
            Icon(MuPlayIcons.Shuffle, contentDescription = null, modifier = Modifier.size(ICON_DP))
            Text(
              text = SHUFFLE_FOLDER_LABEL,
              modifier = Modifier.padding(start = MuPlaySpacing.sm),
            )
          }
          OutlinedButton(onClick = onPlayAll) { Text(PLAY_FOLDER_LABEL) }
        }
      }
    }

    if (uiState.emptyReason != null) {
      item { Message(text = uiState.emptyReason.toMessage()) }
    }

    items(uiState.folders, key = { "folder:" + it.path }) { folder ->
      FolderRow(folder = folder, onClick = { onOpenFolder(folder.path) })
    }

    // Indexed so a tap can name its own row, which is what makes the queue start on the song the
    // user pointed at rather than on the first one.
    itemsIndexed(uiState.tracks, key = { _, song -> "track:" + song.id }) { index, song ->
      FolderTrackRow(song = song, onClick = { onTrackClick(index) })
    }
  }
}

@Composable
private fun FolderRow(folder: FolderNode, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      // Not a bare `heightIn` on a text row: two short rows in a column each grow to the 48dp
      // minimum touch target and then overlap, which is the defect the tap-target sweeps in this
      // repository were written to catch. Holding the row itself at 48dp is what stops it.
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Icon(MuPlayIcons.Folder, contentDescription = null, modifier = Modifier.size(ICON_DP))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = folder.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        // The number the shuffle will actually draw from, counted through every subfolder --
        // see `FolderNode.trackCount`. A count of this folder's own files would promise less
        // than the button beside it delivers.
        text = trackCountLabel(folder.trackCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun FolderTrackRow(song: Song, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = song.title,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      song.artistName?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private val ICON_DP = MuPlaySpacing.xl

internal const val FOLDER_LOADING_LABEL = "Loading…"
internal const val SHUFFLE_FOLDER_LABEL = "Shuffle"
internal const val PLAY_FOLDER_LABEL = "Play in order"
