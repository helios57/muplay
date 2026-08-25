package app.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.BrowseRepository
import app.muplay.model.Album
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
 * The browse operations [AlbumViewModel] needs, abstracted for the same reason [LibrarySource]
 * is: [BrowseRepository] is a concrete, `@Inject`-constructed class backed by Room, so a
 * hand-written fake needs a seam rather than a subclass. Real usage is bound to it by the
 * `@Inject` secondary constructor below.
 */
interface AlbumSource {
  fun songs(albumId: String): Flow<List<Song>>
  suspend fun album(albumId: String): Album?
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String
}

/**
 * [load] takes the album id as a plain argument rather than this class reading it from a
 * `SavedStateHandle` at construction, which is what the task brief originally specified and what
 * every other `@HiltViewModel` in this codebase does (`AlbumViewModel`'s own git history still
 * carries that version).
 *
 * **Verified wrong on a real device, not assumed:** `MuPlayApp`'s `NavDisplay` wires no
 * entry-scoped `SavedStateHandle` argument source for Navigation 3's `NavKey`s -- Navigation 3
 * does not populate one from a key's own properties the way Navigation Compose's typed routes
 * did, and nothing in this codebase adds the missing piece. Installed on `muplay37` and driven
 * through the real first-run flow against the live Navidrome container, tapping a real album's
 * "Open" button crashed immediately with `java.lang.IllegalStateException: AlbumViewModel needs
 * an \`albumId\` argument`, thrown from the exact `checkNotNull` the SavedStateHandle version
 * carried -- confirming `savedStateHandle["albumId"]` really is `null` there, not a
 * theoretical gap. `AlbumScreen` now takes `albumId` as an ordinary parameter, sourced from the
 * `AlbumRoute` key `MuPlayApp`'s `entry<AlbumRoute> { route -> ... }` already holds, and forwards
 * it here from a `LaunchedEffect` -- see task-9-report.md for the crash transcript.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumViewModel(
  private val source: AlbumSource,
) : ViewModel() {

  @Inject
  constructor(browseRepository: BrowseRepository) : this(
    object : AlbumSource {
      override fun songs(albumId: String): Flow<List<Song>> = browseRepository.songs(albumId)
      override suspend fun album(albumId: String): Album? = browseRepository.album(albumId)
      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
    },
  )

  /**
   * Whether [load]'s album lookup has come back yet -- the one distinction a bare `Album?` cannot
   * draw, and the cause of N-3(b) in `task-9-review.md`.
   *
   * `album` used to be a `MutableStateFlow<Album?>` that [load] reset to `null` before launching
   * the lookup, and `uiState` read `null` as [AlbumUiState.NotFound]. So between the songs flow's
   * first emission and the album row arriving, the screen said **"That album is no longer in your
   * library."** about an album that was loading perfectly well. Reachable for certain, not
   * theoretically: this app installs no `ViewModelStoreNavEntryDecorator`, so `hiltViewModel()`
   * inside a `NavDisplay` entry resolves against the *Activity's* store and this view model is
   * shared -- album A -> back -> album B runs `load("B")` on an instance that already holds A's
   * songs, and the reset published `NotFound` immediately.
   *
   * A sealed type rather than a second `Boolean` flow beside the album: "fetched, and it is null"
   * and "not fetched yet" are the two states that must never be confused, and a pair of flows
   * makes the confusable combination representable -- and momentarily *real*, between two
   * assignments -- again. `object`/`class`, not `data object`/`data class`, deliberately: no
   * generated `equals`, so [Done] is compared by identity and a re-fetch returning an equal album
   * still emits rather than being conflated away by `MutableStateFlow`.
   */
  private sealed interface Fetch {
    /** [load] has set an id and its lookup has not returned. The screen is [AlbumUiState.Loading]. */
    object Pending : Fetch

    /** The lookup returned. [album] is `null` **only** when the mirror genuinely has no such row. */
    class Done(val album: Album?) : Fetch
  }

  private val albumId = MutableStateFlow<String?>(null)
  private val album = MutableStateFlow<Fetch>(Fetch.Pending)

  val uiState: StateFlow<AlbumUiState> =
    albumId.flatMapLatest { id ->
      if (id == null) {
        flowOf(AlbumUiState.Loading)
      } else {
        combine(album, source.songs(id)) { fetch, songs ->
          when (fetch) {
            Fetch.Pending -> AlbumUiState.Loading
            is Fetch.Done ->
              if (fetch.album == null) AlbumUiState.NotFound
              else AlbumUiState.Content(fetch.album, songs)
          }
        }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlbumUiState.Loading)

  /**
   * Called once from `AlbumScreen`'s `LaunchedEffect(albumId)`. `flatMapLatest` above is what
   * makes a second call with a *different* id (the same view model instance navigating straight
   * from one album to another, which the Activity-scoped store above makes routine) switch cleanly
   * rather than combining the new album with the previous id's still-collecting songs flow.
   *
   * The early return is the second belt: `LaunchedEffect(albumId)` re-launches only when its key
   * changes, but a recomposition that re-runs this with the id already on screen must not throw
   * the loaded album away and re-fetch it. It is covered by `AlbumViewModelTest`'s
   * `loading the same album twice never fetches it a second time...` -- before review round 1 it
   * was covered by nothing at all (the class measured 1/2 BRANCH) and deleting it left every test
   * in this module green.
   */
  fun load(albumId: String) {
    if (this.albumId.value == albumId) return
    // Before the id, so `flatMapLatest`'s new inner flow can never see the previous album's Done.
    album.value = Fetch.Pending
    this.albumId.value = albumId
    viewModelScope.launch { album.value = Fetch.Done(source.album(albumId)) }
  }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
