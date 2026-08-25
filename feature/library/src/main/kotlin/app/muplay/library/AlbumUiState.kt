package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.Song

/** One album's detail screen. */
sealed interface AlbumUiState {
  data object Loading : AlbumUiState

  /** The mirror has no such album — it was deleted on the server and a reconcile removed it. */
  data object NotFound : AlbumUiState

  data class Content(val album: Album, val songs: List<Song>) : AlbumUiState
}
