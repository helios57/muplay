package app.muplay.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.model.Album

@Composable
fun LibraryScreen(
  onAlbumClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LibraryViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  LibraryScreen(
    uiState = uiState,
    onLibrarySelected = viewModel::selectLibrary,
    onQueryChanged = viewModel::search,
    onShuffle = viewModel::shuffle,
    onRefresh = viewModel::refresh,
    onAlbumClick = onAlbumClick,
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
private fun LibraryScreen(
  uiState: LibraryUiState,
  onLibrarySelected: (Int) -> Unit,
  onQueryChanged: (String) -> Unit,
  onShuffle: () -> Unit,
  onRefresh: () -> Unit,
  onAlbumClick: (String) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    when (uiState) {
      LibraryUiState.Loading -> Text("Loading your library…")
      LibraryUiState.NoLibraries ->
        // Distinct from "this library is empty": the fix is finishing setup, not syncing.
        Text("No libraries yet. Finish setup to choose what each library is for.")
      is LibraryUiState.Content -> {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          uiState.libraries.forEach { library ->
            FilterChip(
              selected = library.id == uiState.selectedLibraryId,
              onClick = { onLibrarySelected(library.id) },
              label = { Text(library.name) },
            )
          }
        }

        OutlinedTextField(
          value = uiState.query,
          onValueChange = onQueryChanged,
          label = { Text(SEARCH_LABEL) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onShuffle, modifier = Modifier.weight(1f)) { Text(SHUFFLE_LABEL) }
          // The only way a user has to pick up a change made on the server after the app started.
          // See "Why there is a Refresh action, and why it is a button" above.
          OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text(REFRESH_LABEL) }
        }

        // `onSurfaceVariant`, not `error`. All four of this string's values are *states* -- checking,
        // the server is mid-scan, the server was unreachable, or nothing to say -- and three of them
        // are ordinary. Painting "the server is scanning" red tells the user something is broken
        // when nothing is.
        uiState.syncMessage?.let {
          Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (uiState.shuffled.isNotEmpty()) {
          Text(text = SHUFFLE_HEADING, style = MaterialTheme.typography.titleMedium)
          uiState.shuffled.forEach { song -> Text(text = song.title) }
          if (uiState.discardedOutOfScope > 0) {
            Text(
              text = "${uiState.discardedOutOfScope} tracks were outside this library and were skipped.",
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        if (uiState.albums.isEmpty()) {
          Text(EMPTY_LIBRARY_LABEL)
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.albums, key = Album::id) { album ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                CoverArtImage(
                  coverArtId = album.coverArtId,
                  sizePx = COVER_THUMBNAIL_PX,
                  contentDescription = album.name,
                  urlProvider = coverArtUrl,
                  modifier = Modifier.size(56.dp),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(text = album.name, style = MaterialTheme.typography.bodyLarge)
                  album.artistName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                }
              }
              Button(onClick = { onAlbumClick(album.id) }) { Text(OPEN_LABEL) }
            }
          }
        }
      }
    }
  }
}

private const val SEARCH_LABEL = "Search this library"
private const val SHUFFLE_LABEL = "Shuffle this library"

/** `internal`, not `private`: [LibraryViewModel]'s scan-in-progress message names this control, and
 *  a message that names a button by a string typed twice is a message that drifts. */
internal const val REFRESH_LABEL = "Refresh library"
private const val SHUFFLE_HEADING = "Shuffled"
private const val EMPTY_LIBRARY_LABEL = "Nothing here yet."
private const val OPEN_LABEL = "Open"
private const val COVER_THUMBNAIL_PX = 128
