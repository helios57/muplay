package io.github.helios57.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.helios57.muplay.database.LibraryRepository
import io.github.helios57.muplay.database.LibrarySelection
import io.github.helios57.muplay.database.PlaylistRepository
import io.github.helios57.muplay.database.OfflinePlaylist
import io.github.helios57.muplay.database.SyncFailure
import io.github.helios57.muplay.media.DownloadActivity
import io.github.helios57.muplay.media.OfflineDownloads
import io.github.helios57.muplay.media.PlaybackLauncher
import io.github.helios57.muplay.media.QueueEditor
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import io.github.helios57.muplay.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the two playlist screens need.
 *
 * Both are on one seam because they read the same two calls and fail the same way; splitting them
 * would be two interfaces with one implementation each and the same fake written twice.
 */
interface PlaylistSource : QueueSink, LibraryFilterSource {
  suspend fun playlists(): List<Playlist>

  /**
   * Which libraries each of these playlists' tracks are mirrored in -- `PlaylistRepository`.
   *
   * On this seam rather than derived on the screen because it costs one `getPlaylist` per playlist
   * the repository has not already cached, and because the answer is a fact about the mirror rather
   * than a decision about the UI. What is done with it is [playlistsContent]'s, and pure.
   */
  suspend fun librariesOf(playlists: List<Playlist>): Map<String, Set<Int>>
  suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs
  suspend fun play(songs: List<Song>, startIndex: Int)

  /** Playlists kept on this phone, and how much of each has arrived. */
  fun kept(): Flow<List<OfflinePlaylist>>

  val downloads: Flow<DownloadActivity>

  suspend fun keep(playlist: Playlist, songs: List<Song>)

  suspend fun forget(playlistId: String)

  /** The kept copy of [playlistId], downloaded songs only -- what can play with no connection. */
  suspend fun keptCopy(playlistId: String): PlaylistWithSongs?

  fun resumeDownloads()
}

/** One playlist. */
sealed interface PlaylistUiState {
  data object Loading : PlaylistUiState
  data class Content(
    val playlist: Playlist,
    val songs: List<Song>,
    val offline: OfflineStatus = OfflineStatus.NotKept,
    /** The server could not be reached; this is the copy kept on the phone. */
    val fromKept: Boolean = false,
  ) : PlaylistUiState
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
    libraryRepository: LibraryRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
    queueEditor: QueueEditor,
    offlineDownloads: OfflineDownloads,
  ) : this(
    PlaylistRepositorySource(
      playlistRepository,
      libraryRepository,
      librarySelection,
      playbackLauncher,
      queueEditor,
      offlineDownloads,
    ),
  )

  /**
   * The server's answer, before the library filter -- which is a separate flow and must not
   * re-fetch when it changes. Switching library re-folds this value; it does not re-read the
   * server, and a user flicking between two chips would otherwise issue a `getPlaylists` per tap.
   */
  private val fetch = MutableStateFlow<PlaylistsFetch>(PlaylistsFetch.Loading)

  val uiState: StateFlow<PlaylistsUiState> =
    combine(fetch, source.libraries, source.selectedLibraryId, source.kept()) { answer, libraries, selected, kept ->
      playlistsContent(answer, LibraryFilterState(libraries, selected), kept)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PlaylistsUiState.Loading)

  init {
    refresh()
    // A download stopped by a lost connection, or by the process ending, picks up here.
    source.resumeDownloads()
  }

  /** Switches library, for every browse screen at once -- see `LibrarySelection`. */
  fun selectLibrary(id: Int) = source.selectLibrary(id)

  private var shown = false

  /**
   * The screen came (back) into view. Playlists are fetched live, and this view model outlives
   * the screen -- measured, it outlived a sign-out and a sign-in to another server, and went on
   * listing the first server's playlists until the process died. The first showing is covered by
   * `init`; every later one asks again, keeping what is on screen until the answer arrives.
   */
  fun onShown() {
    if (shown) refresh()
    shown = true
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
      fetch.value = runCatching {
        val playlists = source.playlists()
        // **Only when there is a choice to make.** Placing a playlist costs a `getPlaylist` for
        // every one the repository has not already cached, and on a single-library server the
        // answer cannot change anything: `playlistsContent` filters nothing at all in that case,
        // so paying for the derivation would be N requests spent on a value nobody reads.
        val libraries =
          if (source.libraries.first().size > 1) source.librariesOf(playlists) else emptyMap()
        PlaylistsFetch.Loaded(playlists, libraries)
      }.fold(
        onSuccess = { it },
        onFailure = { PlaylistsFetch.Failed(SyncFailure.of(it)) },
      )
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
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
    libraryRepository: LibraryRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
    queueEditor: QueueEditor,
    offlineDownloads: OfflineDownloads,
  ) : this(
    PlaylistRepositorySource(
      playlistRepository,
      libraryRepository,
      librarySelection,
      playbackLauncher,
      queueEditor,
      offlineDownloads,
    ),
  )

  private val state = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
  val uiState: StateFlow<PlaylistUiState> =
    combine(state, source.kept(), source.downloads) { current, kept, activity ->
      if (current !is PlaylistUiState.Content) return@combine current
      current.copy(offline = offlineStatus(kept.firstOrNull { it.playlist.id == current.playlist.id }, activity))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistUiState.Loading)

  private var openedId: String? = null

  /** Called from the screen's `LaunchedEffect(playlistId)`, the way `AlbumViewModel.load` is. */
  fun open(playlistId: String) {
    val current = state.value
    val showingLive = current is PlaylistUiState.Loading ||
      (current is PlaylistUiState.Content && !current.fromKept)
    // Only a live answer (or one on its way) is worth keeping on a reopen. A kept copy or a
    // failure is what the server could not give last time, so the next showing asks again.
    if (openedId == playlistId && showingLive) return
    if (openedId != playlistId) state.value = PlaylistUiState.Loading
    openedId = playlistId
    fetch(playlistId)
  }

  /** Asks the server again for the playlist on screen, keeping what is shown until it answers. */
  fun retry() {
    openedId?.let(::fetch)
  }

  private fun fetch(playlistId: String) {
    viewModelScope.launch {
      val fallback = source.selectedLibraryId.first()
      val answer = runCatching { source.playlist(playlistId, fallback ?: 0) }
        .fold(
          onSuccess = { PlaylistUiState.Content(it.playlist, it.songs) },
          onFailure = { error ->
            val failure = SyncFailure.of(error)
            val kept = if (failure.isUnreachable()) source.keptCopy(playlistId) else null
            kept?.let { PlaylistUiState.Content(it.playlist, it.songs, fromKept = true) }
              ?: PlaylistUiState.Failed(failure)
          },
        )
      if (openedId == playlistId) state.value = answer
    }
  }

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

  /** Downloads this playlist as it is on screen now. Not offered on the kept copy itself. */
  fun keepOffline() {
    val content = state.value as? PlaylistUiState.Content ?: return
    if (content.fromKept) return
    viewModelScope.launch { source.keep(content.playlist, content.songs) }
  }

  fun removeOffline() {
    val content = state.value as? PlaylistUiState.Content ?: return
    viewModelScope.launch { source.forget(content.playlist.id) }
  }

  fun retryDownloads() = source.resumeDownloads()

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
  libraryRepository: LibraryRepository,
  librarySelection: LibrarySelection,
  private val playbackLauncher: PlaybackLauncher,
  queueEditor: QueueEditor,
  private val offlineDownloads: OfflineDownloads,
) : PlaylistSource,
  QueueSink by QueueEditorSink(queueEditor),
  LibraryFilterSource by LibrarySelectionFilter(libraryRepository, librarySelection) {
  override suspend fun playlists(): List<Playlist> = playlistRepository.playlists()
  override suspend fun librariesOf(playlists: List<Playlist>): Map<String, Set<Int>> =
    playlistRepository.librariesOf(playlists)
  override suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs =
    playlistRepository.playlist(playlistId, fallbackLibraryId)
  override suspend fun play(songs: List<Song>, startIndex: Int) =
    playbackLauncher.play(songs, startIndex)

  override fun kept(): Flow<List<OfflinePlaylist>> = offlineDownloads.saved()

  override val downloads: Flow<DownloadActivity> = offlineDownloads.state

  override suspend fun keep(playlist: Playlist, songs: List<Song>) = offlineDownloads.save(playlist, songs)

  override suspend fun forget(playlistId: String) = offlineDownloads.remove(playlistId)

  override suspend fun keptCopy(playlistId: String): PlaylistWithSongs? =
    offlineDownloads.keptCopy(playlistId)

  override fun resumeDownloads() = offlineDownloads.resume()
}
