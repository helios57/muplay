package app.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.LibrarySelection
import app.muplay.database.PlaylistRepository
import app.muplay.database.SyncFailure
import app.muplay.media.PlaybackLauncher
import app.muplay.model.Playlist
import app.muplay.model.PlaylistWithSongs
import app.muplay.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the two playlist screens need.
 *
 * Both are on one seam because they read the same two calls and fail the same way; splitting them
 * would be two interfaces with one implementation each and the same fake written twice.
 */
interface PlaylistSource {
  val selectedLibraryId: Flow<Int?>
  suspend fun playlists(): List<Playlist>
  suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs
  suspend fun play(songs: List<Song>, startIndex: Int)
}

/** The playlist list. */
sealed interface PlaylistsUiState {
  data object Loading : PlaylistsUiState
  data class Content(val playlists: List<Playlist>) : PlaylistsUiState
  data class Failed(val failure: SyncFailure) : PlaylistsUiState
}

/** One playlist. */
sealed interface PlaylistUiState {
  data object Loading : PlaylistUiState
  data class Content(val playlist: Playlist, val songs: List<Song>) : PlaylistUiState
  data class Failed(val failure: SyncFailure) : PlaylistUiState
}

/**
 * Wiring for the playlist list.
 *
 * **`Failed` is a first-class state here, and that is the whole design.** Playlists are the one
 * browse surface this app does not mirror, so a server that cannot be reached is ordinary
 * operation rather than an exception -- and a `runCatching { }.getOrDefault(emptyList())` would
 * render that as "you have no playlists", which is a different and untrue statement. That exact
 * collapse was a live defect in `LibraryViewModel.shuffle`; this is the same shape, so it gets the
 * same treatment.
 */
@HiltViewModel
class PlaylistsViewModel(
  private val source: PlaylistSource,
) : ViewModel() {

  @Inject
  constructor(
    playlistRepository: PlaylistRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
  ) : this(PlaylistRepositorySource(playlistRepository, librarySelection, playbackLauncher))

  private val state = MutableStateFlow<PlaylistsUiState>(PlaylistsUiState.Loading)
  val uiState: StateFlow<PlaylistsUiState> = state.asStateFlow()

  init {
    refresh()
  }

  /**
   * Re-reads the list from the server.
   *
   * Offered as a control, because not mirroring playlists is only useful if the user has a way to
   * ask again: the alternative is waiting for a library rescan that has nothing to do with the
   * edit they just made in another client.
   */
  fun refresh() {
    viewModelScope.launch {
      state.value = runCatching { source.playlists() }
        .fold(
          onSuccess = { PlaylistsUiState.Content(it) },
          onFailure = { PlaylistsUiState.Failed(SyncFailure.of(it)) },
        )
    }
  }
}

/** Wiring for one playlist's songs. */
@HiltViewModel
class PlaylistViewModel(
  private val source: PlaylistSource,
) : ViewModel() {

  @Inject
  constructor(
    playlistRepository: PlaylistRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
  ) : this(PlaylistRepositorySource(playlistRepository, librarySelection, playbackLauncher))

  private val state = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
  val uiState: StateFlow<PlaylistUiState> = state.asStateFlow()

  private var openedId: String? = null

  /** Called from the screen's `LaunchedEffect(playlistId)`, the way `AlbumViewModel.load` is. */
  fun open(playlistId: String) {
    if (openedId == playlistId) return
    openedId = playlistId
    state.value = PlaylistUiState.Loading
    viewModelScope.launch {
      val fallback = source.selectedLibraryId.first()
      state.value = runCatching { source.playlist(playlistId, fallback ?: 0) }
        .fold(
          onSuccess = { PlaylistUiState.Content(it.playlist, it.songs) },
          onFailure = { PlaylistUiState.Failed(SyncFailure.of(it)) },
        )
    }
  }

  /** Plays the playlist from the row tapped, in the playlist's own order. */
  fun play(startIndex: Int) {
    // `orEmpty` rather than a second `?: return`: a not-yet-loaded playlist and an out-of-range
    // index are the same behaviour, so a null guard beside the range check is a branch no test can
    // tell apart from it. Same reasoning as `FolderViewModel.playTrack`.
    val songs = (state.value as? PlaylistUiState.Content)?.songs.orEmpty()
    if (startIndex !in songs.indices) return
    viewModelScope.launch { source.play(songs, startIndex) }
  }

  fun shuffle() {
    val songs = (state.value as? PlaylistUiState.Content)?.songs.orEmpty()
    if (songs.isEmpty()) return
    viewModelScope.launch { source.play(songs.shuffled(), 0) }
  }
}

/**
 * The one real implementation of [PlaylistSource], shared by both view models.
 *
 * A named class rather than two `object :` expressions, so the two screens cannot drift into
 * reading the same playlist through two slightly different calls.
 */
private class PlaylistRepositorySource(
  private val playlistRepository: PlaylistRepository,
  librarySelection: LibrarySelection,
  private val playbackLauncher: PlaybackLauncher,
) : PlaylistSource {
  override val selectedLibraryId: Flow<Int?> = librarySelection.selected
  override suspend fun playlists(): List<Playlist> = playlistRepository.playlists()
  override suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs =
    playlistRepository.playlist(playlistId, fallbackLibraryId)
  override suspend fun play(songs: List<Song>, startIndex: Int) =
    playbackLauncher.play(songs, startIndex)
}
