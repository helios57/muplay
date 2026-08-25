package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.Song

/** One album's detail screen. */
sealed interface AlbumUiState {

  /**
   * Nothing has been asked for yet, or the lookup for the id that was asked for has not returned.
   *
   * Distinct from [NotFound], and the distinction is the whole point of this state existing: for
   * one review round these two were the same value on screen, because `AlbumViewModel` reset its
   * album to `null` before fetching and read `null` as [NotFound], so a perfectly healthy album
   * announced itself as deleted while it loaded. See [AlbumViewModel]'s own `Fetch` type.
   */
  data object Loading : AlbumUiState

  /**
   * The mirror has no such album — it was deleted on the server and a reconcile removed it.
   *
   * **Only ever shown after a lookup has actually come back empty.** A state that says something
   * untrue about the user's library at the moment it is shown is the defect class this app's
   * `syncMessage` copy already exists to avoid; this is the same rule applied to a screen.
   */
  data object NotFound : AlbumUiState

  data class Content(val album: Album, val songs: List<Song>) : AlbumUiState
}
