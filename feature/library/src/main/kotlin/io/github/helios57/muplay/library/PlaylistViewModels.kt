package io.github.helios57.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.helios57.muplay.database.LibrarySelection
import io.github.helios57.muplay.database.PlaylistRepository
import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.media.PlaybackLauncher
import io.github.helios57.muplay.media.QueueEditor
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import io.github.helios57.muplay.model.Song
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
interface PlaylistSource : QueueSink {
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
    queueEditor: QueueEditor,
  ) : this(
    PlaylistRepositorySource(playlistRepository, librarySelection, playbackLauncher, queueEditor),
  )

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
    queueEditor: QueueEditor,
  ) : this(
    PlaylistRepositorySource(playlistRepository, librarySelection, playbackLauncher, queueEditor),
  )

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

  /**
   * Adds the tapped row to the end of the queue, and **changes nothing about what is playing** --
   * the distinction the control exists for. [playNext] is its sibling.
   */
  fun enqueue(index: Int) {
    songAt(index)?.let { song -> viewModelScope.launch { source.enqueue(listOf(song)) } }
  }

  /** Inserts the tapped row directly after whatever is playing. See [enqueue]. */
  fun playNext(index: Int) {
    songAt(index)?.let { song -> viewModelScope.launch { source.playNext(listOf(song)) } }
  }

  /**
   * Puts the **whole playlist** on the end of the queue, in the playlist's own order.
   *
   * Not `play(0)` and not `shuffle()`: the promise every queue control in this app makes is that it
   * leaves what is playing alone, and the two methods above it are exactly the ones an
   * implementation would reach for by mistake. The order is the playlist's, because a playlist is
   * an order -- that is what distinguishes it from the album it was assembled from.
   */
  fun enqueueAll() {
    songsOnScreen()?.let { songs -> viewModelScope.launch { source.enqueue(songs) } }
  }

  /** Inserts the whole playlist directly after whatever is playing. See [enqueueAll]. */
  fun playAllNext() {
    songsOnScreen()?.let { songs -> viewModelScope.launch { source.playNext(songs) } }
  }

  private fun songsOnScreen(): List<Song>? = (state.value as? PlaylistUiState.Content)?.songs

  /** `orEmpty` rather than a second `?.`, for the reason [play] states: `Content.songs` is
   *  non-null, so a null check on it is a branch nothing can take. Measured at 29/32 before. */
  private fun songAt(index: Int): Song? =
    (state.value as? PlaylistUiState.Content)?.songs.orEmpty().getOrNull(index)
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
  queueEditor: QueueEditor,
) : PlaylistSource, QueueSink by QueueEditorSink(queueEditor) {
  override val selectedLibraryId: Flow<Int?> = librarySelection.selected
  override suspend fun playlists(): List<Playlist> = playlistRepository.playlists()
  override suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs =
    playlistRepository.playlist(playlistId, fallbackLibraryId)
  override suspend fun play(songs: List<Song>, startIndex: Int) =
    playbackLauncher.play(songs, startIndex)
}
