package app.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.BrowseRepository
import app.muplay.database.LibraryRepository
import app.muplay.database.ShuffleRepository
import app.muplay.database.SyncEngine
import app.muplay.database.SyncState
import app.muplay.media.PlaybackLauncher
import app.muplay.model.Album
import app.muplay.model.MusicLibrary
import app.muplay.model.SearchResults
import app.muplay.model.ShuffleResult
import app.muplay.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The library-and-browse operations [LibraryViewModel] needs, abstracted so a JVM test can fake
 * them with no Room database and no network client.
 *
 * [LibraryRepository], [BrowseRepository], [ShuffleRepository] and [SyncEngine] are all concrete,
 * `@Inject`-constructed classes backed by Room DAOs and a `SubsonicSourceProvider` -- they cannot
 * be subclassed into a hand-written fake, and constructing the real ones needs a device (Room) or
 * a live server. This project bans mock frameworks (`ConventionTest`), so this interface is the
 * only way [LibraryViewModel]'s own forwarding logic -- which library id reaches which call, in
 * which order, with which value -- can be proved on the JVM at all. Real usage is bound to the
 * four classes above by the `@Inject` secondary constructor below, the same shape
 * `:feature:setup`'s `SetupCredentialSink`/`SetupLibrarySink` split already established.
 */
interface LibrarySource {
  val libraries: Flow<List<MusicLibrary>>
  fun albums(libraryId: Int): Flow<List<Album>>
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults
  suspend fun shuffle(libraryId: Int, size: Int): ShuffleResult
  suspend fun syncIfStale(): SyncState
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String
  suspend fun allIds(): List<Int>

  /**
   * Starts playback. On the seam for the same reason every other member is: [PlaybackLauncher]
   * builds a `MediaController` handshake, which needs a bound media session and therefore a device.
   * The arguments are the whole contract -- **which** songs, from **which** index -- and a JVM test
   * can hold both to a value here.
   */
  suspend fun play(songs: List<Song>, startIndex: Int)
}

/**
 * Wiring only. Every rule about what the screen shows lives in [libraryContent], which is pure
 * and unit-tested; this class combines flows, runs the three actions, and holds the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel(
  private val source: LibrarySource,
) : ViewModel() {

  @Inject
  constructor(
    libraryRepository: LibraryRepository,
    browseRepository: BrowseRepository,
    shuffleRepository: ShuffleRepository,
    syncEngine: SyncEngine,
    playbackLauncher: PlaybackLauncher,
  ) : this(
    object : LibrarySource {
      override val libraries: Flow<List<MusicLibrary>> = libraryRepository.libraries
      override fun albums(libraryId: Int): Flow<List<Album>> = browseRepository.albums(libraryId)
      override suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults =
        browseRepository.search(libraryId, query, limit)
      override suspend fun shuffle(libraryId: Int, size: Int): ShuffleResult =
        shuffleRepository.shuffle(libraryId, size)
      override suspend fun syncIfStale(): SyncState = syncEngine.syncIfStale()
      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
      override suspend fun allIds(): List<Int> = libraryRepository.allIds()
      override suspend fun play(songs: List<Song>, startIndex: Int) =
        playbackLauncher.play(songs, startIndex)
    },
  )

  private val selectedLibraryId = MutableStateFlow<Int?>(null)
  private val query = MutableStateFlow("")
  private val shuffleResult = MutableStateFlow<ShuffleResult?>(null)
  private val syncMessage = MutableStateFlow<String?>(null)
  private val searchAlbums = MutableStateFlow<List<Album>>(emptyList())

  private val albums: Flow<List<Album>> =
    combine(source.libraries, selectedLibraryId) { libraries, selected ->
      libraries.firstOrNull { it.id == selected }?.id ?: libraries.firstOrNull()?.id
    }.flatMapLatest { id ->
      if (id == null) flowOf(emptyList()) else source.albums(id)
    }

  val uiState: StateFlow<LibraryUiState> =
    combine(
      source.libraries,
      selectedLibraryId,
      query,
      albums,
      combine(searchAlbums, shuffleResult, syncMessage) { results, shuffled, message ->
        Triple(results, shuffled, message)
      },
    ) { libraries, selected, currentQuery, currentAlbums, extras ->
      libraryContent(
        libraries = libraries,
        selectedLibraryId = selected,
        query = currentQuery,
        albums = currentAlbums,
        searchAlbums = extras.first,
        shuffle = extras.second,
        syncMessage = extras.third,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState.Loading)

  init {
    refresh()
  }

  fun selectLibrary(id: Int) {
    selectedLibraryId.value = id
    // A shuffle belongs to the library it was drawn from; carrying it across a switch would show
    // music tracks under the audiobook tab, which is precisely the confusion this app removes.
    shuffleResult.value = null
    query.value = ""
    searchAlbums.value = emptyList()
  }

  fun search(newQuery: String) {
    query.value = newQuery
    viewModelScope.launch {
      val id = currentLibraryId() ?: return@launch
      searchAlbums.value =
        if (newQuery.isBlank()) emptyList()
        else source.search(id, newQuery, SEARCH_LIMIT).albums
    }
  }

  fun shuffle() {
    viewModelScope.launch {
      val id = currentLibraryId() ?: return@launch
      shuffleResult.value = runCatching {
        source.shuffle(id, ShuffleRepository.DEFAULT_SHUFFLE_SIZE)
      }.getOrElse { ShuffleResult(emptyList(), discardedOutOfScope = 0) }
    }
  }

  /**
   * Reconciles the mirror if the server has rescanned since the last committed sync.
   *
   * **This is the only thing in the app that syncs, and it only runs when something calls it** --
   * once from [init], and once per tap of the screen's Refresh action. There is deliberately no
   * periodic poller: spec §4's *"poll"* is about `getScanStatus`'s watermark being the delta
   * primitive, not about a background service, and a timer that wakes to ask a question nobody is
   * waiting for is battery spent on a user who is not looking at the screen. The user asks; the
   * app answers.
   *
   * Every message below is true when it is shown. [SyncState.ScanInProgress] used to read *"your
   * library will update shortly"*, which was a promise no code kept -- nothing re-checked, ever. It
   * now describes the situation and names the control that resolves it.
   */
  fun refresh() {
    viewModelScope.launch {
      syncMessage.value = SYNCING_MESSAGE
      syncMessage.value = when (val state = source.syncIfStale()) {
        SyncState.UpToDate, is SyncState.Synced -> null
        SyncState.ScanInProgress ->
          "The server is still scanning, so some albums may be missing. Tap $REFRESH_LABEL when it has finished."
        is SyncState.Failed -> "Could not reach the server. Showing your last synced library."
      }
    }
  }

  /**
   * Plays the shuffle result, from the row the user tapped.
   *
   * The songs come from `ShuffleRepository`, which has already dropped anything the mirror does not
   * agree belongs to the selected library -- see Plan 2 Task 7. This method adds no scope check of
   * its own, deliberately: a second, weaker copy of that guard here would be a place for the two to
   * disagree, and the one that is wrong would be the one nobody tested.
   *
   * The early return is not defensive padding: `uiState` is `Loading` until the mirror has been
   * read, and a shuffle row cannot be tapped then -- but `stateIn(WhileSubscribed)` also drops back
   * to its initial value once the screen has been gone for five seconds, so a stale tap really can
   * arrive here in a non-`Content` state.
   */
  fun playShuffled(startIndex: Int) {
    val content = uiState.value as? LibraryUiState.Content ?: return
    viewModelScope.launch { source.play(content.shuffled, startIndex) }
  }

  private suspend fun currentLibraryId(): Int? =
    (uiState.value as? LibraryUiState.Content)?.selectedLibraryId
      ?: source.allIds().firstOrNull()

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
    const val SEARCH_LIMIT = 50

    /**
     * Shown while a refresh is in flight. It is the only feedback the action gives, and it is
     * enough: the alternative was a fourth field on `LibraryUiState.Content` and a signature
     * change to the pure builder, for a spinner.
     */
    const val SYNCING_MESSAGE = "Checking the server for changes…"
  }
}
