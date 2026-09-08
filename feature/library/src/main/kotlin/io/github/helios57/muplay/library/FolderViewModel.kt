package io.github.helios57.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.helios57.muplay.database.FolderRepository
import io.github.helios57.muplay.database.LibraryRepository
import io.github.helios57.muplay.database.LibrarySelection
import io.github.helios57.muplay.media.PlaybackLauncher
import io.github.helios57.muplay.media.QueueEditor
import io.github.helios57.muplay.model.FolderListing
import io.github.helios57.muplay.model.Song
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
interface FolderSource : QueueSink, LibraryFilterSource {
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
    libraryRepository: LibraryRepository,
    librarySelection: LibrarySelection,
    playbackLauncher: PlaybackLauncher,
    queueEditor: QueueEditor,
  ) : this(
    object :
      FolderSource,
      QueueSink by QueueEditorSink(queueEditor),
      LibraryFilterSource by LibrarySelectionFilter(libraryRepository, librarySelection) {
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

  /**
   * The chip row this screen draws at its root, and nowhere below it.
   *
   * Same shape as `LibraryViewModel`'s: the two flows are combined here rather than each collected
   * by the composable, so a recomposition cannot show a library list and a selection from two
   * different moments -- which is what draws a row with nothing lit.
   *
   * `Unknown` until the mirror answers, which renders nothing. On a first run that is the honest
   * state: there is no library to offer yet, and an empty row would be a filter the user cannot
   * use rather than a filter that is loading.
   */
  val libraryFilter: StateFlow<LibraryFilterState> =
    combine(source.libraries, source.selectedLibraryId, ::LibraryFilterState)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryFilterState.Unknown)

  /**
   * Switches library, for every screen at once -- see `LibrarySelection`.
   *
   * The folder screen the user is standing in keeps its **path** across the switch, which is
   * deliberate: `uiState` re-reads that path in the new library, so a shared prefix like
   * `Artists` survives and a path the other library does not have shows as an empty folder rather
   * than as a crash. Popping back to the root instead would throw away a position the user did not
   * ask to leave.
   */
  fun selectLibrary(id: Int) = source.selectLibrary(id)

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

  /**
   * Adds the tapped row to the end of the queue, and **changes nothing about what is playing**.
   *
   * From the listing, like [playTrack] and unlike [shuffleFolder]: the rows on screen are what a
   * user is pointing at, and `songsUnder`'s subtree is a different list with a different index.
   */
  fun enqueue(index: Int) {
    trackAt(index)?.let { song -> viewModelScope.launch { source.enqueue(listOf(song)) } }
  }

  /** Inserts the tapped row directly after whatever is playing. See [enqueue]. */
  fun playNext(index: Int) {
    trackAt(index)?.let { song -> viewModelScope.launch { source.playNext(listOf(song)) } }
  }

  /**
   * Puts **everything beneath this folder** on the end of the queue, in path order.
   *
   * From `songsUnder` and not from the listing -- the opposite of [enqueue] right above, and the
   * distinction is the point. A row is a track a user is pointing at; a folder in this app is the
   * subtree, because that is the feature as asked for. [playFolder] queues the same list, and this
   * one does it without taking over what is playing.
   */
  fun enqueueAll() {
    songsUnderThen { songs -> source.enqueue(songs) }
  }

  /** Inserts everything beneath this folder directly after whatever is playing. See [enqueueAll]. */
  fun playAllNext() {
    songsUnderThen { songs -> source.playNext(songs) }
  }

  /** `orEmpty` rather than a second `?.`, exactly as [playTrack] above does and for the same
   *  measured reason: `FolderUiState.tracks` is non-null, so `?.tracks?.getOrNull(..)` emits a null
   *  check nothing can take, and this class read 18/20 against a 1.00 floor until it went. */
  private fun trackAt(index: Int): Song? = uiState.value?.tracks.orEmpty().getOrNull(index)

  private fun withSongsUnder(arrange: (List<Song>) -> List<Song>) {
    songsUnderThen { songs -> source.play(arrange(songs), 0) }
  }

  /**
   * The three preconditions every whole-folder action shares: a folder is open, a library is
   * selected, and there is something beneath it.
   *
   * One place rather than four, so that [shuffleFolder], [playFolder], [enqueueAll] and
   * [playAllNext] cannot drift into disagreeing about which of them a closed folder or an empty
   * subtree is allowed to reach. `arrange` is applied by the caller and never changes the count, so
   * checking emptiness here is checking the same thing the old body checked after arranging.
   */
  private fun songsUnderThen(act: suspend (List<Song>) -> Unit) {
    val at = path.value ?: return
    viewModelScope.launch {
      val libraryId = source.selectedLibraryId.first() ?: return@launch
      val songs = source.songsUnder(libraryId, at)
      if (songs.isNotEmpty()) act(songs)
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
