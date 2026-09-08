package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.model.Playlist

/**
 * What the playlist list screen shows, and the pure fold that decides it.
 *
 * Split out of `PlaylistViewModels.kt` when the library filter arrived, for the reason
 * [FolderUiState]'s own file gives: these are decisions a user reads back off the screen, and they
 * are worth holding to a unit test rather than to an emulator this repository regularly does not
 * have.
 */

/** The list, as read from the server and before any library filter is applied. */
sealed interface PlaylistsFetch {

  /** Nothing asked yet, or a refresh in flight over a list that was never loaded. */
  data object Loading : PlaylistsFetch

  /**
   * The server answered.
   *
   * @property libraries which libraries each playlist's own tracks are mirrored in, keyed by
   *   playlist id -- `PlaylistRepository.librariesOf`. **An absent or empty entry means "could not
   *   place it"**, not "belongs nowhere", and [playlistsContent] is where that distinction is
   *   honoured. Empty for every playlist when the server has one library, because nothing is
   *   derived in that case at all.
   */
  data class Loaded(
    val playlists: List<Playlist>,
    val libraries: Map<String, Set<Int>>,
  ) : PlaylistsFetch

  /** The server could not be read. Never collapsed into an empty [Loaded] -- see below. */
  data class Failed(val failure: SyncFailure) : PlaylistsFetch
}

/** Why the playlist list has nothing on it. Null when it has something. */
enum class PlaylistsEmptyReason {

  /** The server really has no playlists. */
  NoneAtAll,

  /**
   * There are playlists, and none of them belongs to the library being browsed.
   *
   * Distinct from [NoneAtAll] because the sentences are not interchangeable: telling somebody with
   * four playlists that they have none is a false statement about their server, and it hides the
   * one control that fixes it -- the chip row directly above the message.
   */
  NoneInThisLibrary,
}

/** The playlist list. */
sealed interface PlaylistsUiState {
  data object Loading : PlaylistsUiState

  /**
   * @property playlists what to draw, already filtered to [filter]'s selection.
   * @property filter the chip row to draw above them, from the same value so that a recomposition
   *   cannot pair one moment's chips with another moment's list.
   */
  data class Content(
    val playlists: List<Playlist>,
    val filter: LibraryFilterState,
    val emptyReason: PlaylistsEmptyReason?,
  ) : PlaylistsUiState

  data class Failed(val failure: SyncFailure) : PlaylistsUiState
}

/**
 * Folds the server's answer and the chosen library into what the screen renders.
 *
 * ### Why the scope is derived rather than requested
 *
 * Subsonic has no library-scoped playlist call. `getPlaylists` **accepts `musicFolderId` and
 * ignores it** -- measured twice against the CI Navidrome with one probe playlist per library, and
 * both came back for both ids -- and no `getPlaylist` entry names a library either. So the only
 * honest answer available is the one `PlaylistRepository.librariesOf` computes: the libraries the
 * playlist's own tracks are mirrored in.
 *
 * ### An unknown scope is shown, not hidden
 *
 * A playlist whose tracks the mirror has never seen (added from another client since the last sync)
 * or whose read the server refused comes back with an **empty** set. Both available answers are
 * wrong in some case, and they are not equally wrong: showing it under both libraries is a mild
 * over-inclusion the user can see and ignore, while hiding it is a filter that silently deletes
 * playlists from the user's own server with no message and no way back.
 *
 * A one-library server filters nothing at all, for the same reason from the other side: there is no
 * second place a playlist could be, so applying the derivation could only ever remove a playlist
 * that failed to place.
 */
fun playlistsContent(fetch: PlaylistsFetch, filter: LibraryFilterState): PlaylistsUiState =
  when (fetch) {
    PlaylistsFetch.Loading -> PlaylistsUiState.Loading
    is PlaylistsFetch.Failed -> PlaylistsUiState.Failed(fetch.failure)
    is PlaylistsFetch.Loaded -> {
      val selected = filter.selectedLibraryId
      val visible = if (!filter.offersChoice || selected == null) {
        fetch.playlists
      } else {
        fetch.playlists.filter { playlist ->
          val scope = fetch.libraries[playlist.id].orEmpty()
          scope.isEmpty() || selected in scope
        }
      }
      PlaylistsUiState.Content(
        playlists = visible,
        filter = filter,
        emptyReason = when {
          fetch.playlists.isEmpty() -> PlaylistsEmptyReason.NoneAtAll
          visible.isEmpty() -> PlaylistsEmptyReason.NoneInThisLibrary
          else -> null
        },
      )
    }
  }

/** The sentence the list shows instead of rows, for each reason it can have none. */
internal fun PlaylistsEmptyReason.toMessage(): String = when (this) {
  PlaylistsEmptyReason.NoneAtAll -> NO_PLAYLISTS_LABEL
  PlaylistsEmptyReason.NoneInThisLibrary -> NO_PLAYLISTS_IN_LIBRARY_LABEL
}
