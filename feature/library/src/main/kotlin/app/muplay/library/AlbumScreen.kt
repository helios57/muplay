package app.muplay.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * [albumId] is an ordinary parameter, not read from [AlbumViewModel]'s `SavedStateHandle` --
 * see [AlbumViewModel]'s own doc for why: verified on a real device that Navigation 3 populates
 * no such argument for a `NavKey`'s properties. [MuPlayApp][app.muplay.ui.MuPlayApp] passes the
 * `AlbumRoute` key's own `albumId` here, and the `LaunchedEffect` below is what forwards it into
 * the view model exactly once per distinct id.
 */
@Composable
fun AlbumScreen(
  albumId: String,
  modifier: Modifier = Modifier,
  viewModel: AlbumViewModel = hiltViewModel(),
) {
  LaunchedEffect(albumId) { viewModel.load(albumId) }
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (uiState) {
      AlbumUiState.Loading -> Text("Loading…")
      AlbumUiState.NotFound -> Text("That album is no longer in your library.")
      is AlbumUiState.Content -> {
        val content = uiState as AlbumUiState.Content
        CoverArtImage(
          coverArtId = content.album.coverArtId,
          sizePx = COVER_DETAIL_PX,
          contentDescription = content.album.name,
          urlProvider = viewModel::coverArtUrl,
          modifier = Modifier.size(160.dp),
        )
        Text(text = content.album.name, style = MaterialTheme.typography.headlineSmall)
        content.album.artistName?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        content.songs.forEach { song -> Text(text = song.title) }
      }
    }
  }
}

private const val COVER_DETAIL_PX = 512
