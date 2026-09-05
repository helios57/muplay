package app.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.FolderRepository
import app.muplay.database.LibrarySelection
import app.muplay.media.PlaybackLauncher
import app.muplay.model.FolderListing
import app.muplay.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What [FolderViewModel] needs, abstracted for the reason [LibrarySource] is: `FolderRepository`
 * is Room-backed and `PlaybackLauncher` needs a bound media session, so neither can be constructed
 * on the JVM, and this project bans mock frameworks.
 */
interface FolderSource {
  val selectedLibraryId: Flow<Int?>
  fun listing(libraryId: Int, path: String): Flow<FolderListing>
  fun pathedSongCount(libraryId: Int): Flow<Int>
  suspend fun songsUnder(libraryId: Int, path: String): List<Song>
  suspend fun play(songs: List<Song>, startIndex: Int)
}

/**
 * One folder screen.
 *
 * Wiring only: every rule about what is shown lives in [folderContent], which is pure and unit
 * tested.
 *
 * **The library id is read continuously, not captured.** A folder screen stays open while the user
 * switches library on another tab, and a ViewModel that took the id once would go on showing the
 * previous library's folders -- which looks like stale data rather than like the wrong argument it
 * is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderViewModel(
  private val source: FolderSource,
) : ViewModel() {

  @Inject
  constructor(
    folderRepository: FolderRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
  ) : this(
    object : FolderSource {
      override val selectedLibraryId: Flow<Int?> = librarySelection.selected
      override fun listing(libraryId: Int, path: String): Flow<FolderListing> =
        folderRepository.listing(libraryId, path)
      override fun pathedSongCount(libraryId: Int): Flow<Int> =
        folderRepository.pathedSongCount(libraryId)
      override suspend fun songsUnder(libraryId: Int, path: String): List<Song> =
        folderRepository.songsUnder(libraryId, path)
      override suspend fun play(songs: List<Song>, startIndex: Int) =
        playbackLauncher.play(songs, startIndex)
    },
  )

  private val path = MutableStateFlow<String?>(null)

  /**
   * Null until both the path and a library are known -- which the screen renders as loading.
   *
   * Two genuinely different "not yet"s collapse into one here on purpose: before [open] has run
   * there is no question to ask, and before a library exists there is nothing to ask it of. The
   * screen has the same thing to say in both cases and no action that distinguishes them.
   */
  val uiState: StateFlow<FolderUiState?> =
    combine(path, source.selectedLibraryId) { at, libraryId -> at to libraryId }
      .flatMapLatest { (at, libraryId) ->
        if (at == null || libraryId == null) {
          flowOf(null)
        } else {
          combine(source.listing(libraryId, at), source.pathedSongCount(libraryId)) { listing, count ->
            folderContent(listing, count)
          }
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

  /** Called from the screen's `LaunchedEffect(path)`, the way `AlbumViewModel.load` is. */
  fun open(at: String) {
    path.value = at
  }

  /**
   * Plays everything beneath this folder, shuffled -- the feature as asked for: *"shuffle on a
   * folder and all its subfolders (if any)"*.
   *
   * The recursion is `FolderRepository.songsUnder`'s prefix query, not a walk, so a folder holding
   * nothing but subfolders shuffles all of them. The shuffle is applied here rather than in the
   * repository because the same query serves [playFolder], which must not shuffle.
   *
   * An empty result starts nothing. Launching an empty queue would put the player on screen with
   * nothing in it, which is a worse answer than the disabled control the screen already shows.
   */
  fun shuffleFolder() {
    withSongsUnder { songs -> songs.shuffled() }
  }

  /** Plays everything beneath this folder in path order. */
  fun playFolder() {
    withSongsUnder { it }
  }

  /**
   * Plays the tracks lying **in** this folder, from the row tapped.
   *
   * From the listing rather than from `songsUnder`: those are the rows on screen, and starting a
   * queue of the whole subtree at the tapped row's index would start on a different song.
   */
  fun playTrack(startIndex: Int) {
    // `orEmpty` rather than a second `?: return` for the not-loaded-yet case: measured, a null
    // state and an out-of-range index are the same behaviour and no test can tell the two guards
    // apart, so one of them was a branch nothing could ever gate.
    val tracks = uiState.value?.tracks.orEmpty()
    if (startIndex !in tracks.indices) return
    viewModelScope.launch { source.play(tracks, startIndex) }
  }

  private fun withSongsUnder(arrange: (List<Song>) -> List<Song>) {
    val at = path.value ?: return
    viewModelScope.launch {
      val libraryId = source.selectedLibraryId.first() ?: return@launch
      val songs = arrange(source.songsUnder(libraryId, at))
      if (songs.isNotEmpty()) source.play(songs, 0)
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
