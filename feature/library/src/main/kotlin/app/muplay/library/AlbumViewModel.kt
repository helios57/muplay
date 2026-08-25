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

  private val albumId = MutableStateFlow<String?>(null)
  private val album = MutableStateFlow<Album?>(null)

  val uiState: StateFlow<AlbumUiState> =
    albumId.flatMapLatest { id ->
      if (id == null) {
        flowOf(AlbumUiState.Loading)
      } else {
        combine(album, source.songs(id)) { current, songs ->
          if (current == null) AlbumUiState.NotFound else AlbumUiState.Content(current, songs)
        }
      }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlbumUiState.Loading)

  /**
   * Called once from `AlbumScreen`'s `LaunchedEffect(albumId)`. `flatMapLatest` above is what
   * makes a second call with a *different* id (the same view model instance navigating straight
   * from one album to another, should Navigation 3 ever reuse it that way) switch cleanly rather
   * than combining the new album with the previous id's still-collecting songs flow.
   */
  fun load(albumId: String) {
    if (this.albumId.value == albumId) return
    this.albumId.value = albumId
    album.value = null
    viewModelScope.launch { album.value = source.album(albumId) }
  }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
