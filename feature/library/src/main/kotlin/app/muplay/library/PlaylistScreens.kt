package app.muplay.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.muplay.model.Playlist
import app.muplay.model.Song

/**
 * The user's server-side playlists.
 *
 * Read live rather than from the mirror -- see `PlaylistRepository` -- which is why this screen has
 * a Refresh of its own and an honest failure state. Every other browse screen in this app works
 * offline; this one does not, and says so rather than showing an empty list.
 */
@Composable
fun PlaylistsScreen(
  onOpenPlaylist: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlaylistsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (val state = uiState) {
    PlaylistsUiState.Loading -> Message(text = PLAYLISTS_LOADING_LABEL, loading = true, modifier = modifier)
    is PlaylistsUiState.Failed -> Column(modifier = modifier.padding(MuPlaySpacing.gutter)) {
      Message(text = state.failure.describe(PLAYLISTS_UNKNOWN_FAILURE_LABEL))
      TextButton(onClick = viewModel::refresh) { Text(RETRY_LABEL) }
    }
    is PlaylistsUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(MuPlaySpacing.gutter),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    ) {
      if (state.playlists.isEmpty()) {
        item { Message(text = NO_PLAYLISTS_LABEL) }
      }
      items(state.playlists, key = { "playlist:" + it.id }) { playlist ->
        PlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist.id) })
      }
    }
  }
}

/** One playlist's songs, in the order the playlist puts them in. */
@Composable
fun PlaylistScreen(
  playlistId: String,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlaylistViewModel = hiltViewModel(),
) {
  LaunchedEffect(playlistId) { viewModel.open(playlistId) }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  when (val state = uiState) {
    PlaylistUiState.Loading -> Message(text = PLAYLISTS_LOADING_LABEL, loading = true, modifier = modifier)
    is PlaylistUiState.Failed ->
      Message(
        text = state.failure.describe(PLAYLIST_UNKNOWN_FAILURE_LABEL),
        modifier = modifier.padding(MuPlaySpacing.gutter),
      )
    is PlaylistUiState.Content -> LazyColumn(
      modifier = modifier.fillMaxWidth(),
      contentPadding = PaddingValues(MuPlaySpacing.gutter),
      verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
    ) {
      if (state.songs.isNotEmpty()) {
        item {
          Button(onClick = { viewModel.shuffle(); onOpenPlayer() }) {
            Icon(MuPlayIcons.Shuffle, contentDescription = null, modifier = Modifier.size(MuPlaySpacing.xl))
            Text(text = SHUFFLE_FOLDER_LABEL, modifier = Modifier.padding(start = MuPlaySpacing.sm))
          }
        }
      } else {
        item { Message(text = EMPTY_PLAYLIST_LABEL) }
      }
      itemsIndexed(state.songs, key = { index, song -> "song:$index:" + song.id }) { index, song ->
        // Keyed on index as well as id, deliberately: a playlist may legitimately hold the same
        // song twice, and a duplicate key crashes `LazyColumn` rather than merely looking wrong.
        PlaylistSongRow(song = song, onClick = { viewModel.play(index); onOpenPlayer() })
      }
    }
  }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.md),
  ) {
    Icon(MuPlayIcons.Playlist, contentDescription = null, modifier = Modifier.size(MuPlaySpacing.xl))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = playlist.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = trackCountLabel(playlist.songCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun PlaylistSongRow(song: Song, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .heightIn(min = MuPlaySpacing.minTouchTarget)
      .padding(vertical = MuPlaySpacing.xs),
    verticalAlignment = Alignment.CenterVertically,
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

internal const val PLAYLISTS_LOADING_LABEL = "Loading…"
internal const val NO_PLAYLISTS_LABEL = "No playlists on the server yet."
internal const val EMPTY_PLAYLIST_LABEL = "This playlist is empty."
internal const val RETRY_LABEL = "Try again"
internal const val PLAYLISTS_UNKNOWN_FAILURE_LABEL = "Could not load your playlists."
internal const val PLAYLIST_UNKNOWN_FAILURE_LABEL = "Could not load this playlist."
