package app.muplay.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.BrowseRepository
import app.muplay.model.Album
import app.muplay.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

@HiltViewModel
class AlbumViewModel(
  savedStateHandle: SavedStateHandle,
  private val source: AlbumSource,
) : ViewModel() {

  @Inject
  constructor(
    savedStateHandle: SavedStateHandle,
    browseRepository: BrowseRepository,
  ) : this(
    savedStateHandle,
    object : AlbumSource {
      override fun songs(albumId: String): Flow<List<Song>> = browseRepository.songs(albumId)
      override suspend fun album(albumId: String): Album? = browseRepository.album(albumId)
      override suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
        browseRepository.coverArtUrl(coverArtId, sizePx)
    },
  )

  private val albumId: String = checkNotNull(savedStateHandle[ALBUM_ID_KEY]) {
    "AlbumViewModel needs an `$ALBUM_ID_KEY` argument"
  }

  private val album = MutableStateFlow<Album?>(null)

  val uiState: StateFlow<AlbumUiState> =
    combine(album, source.songs(albumId)) { current, songs ->
      if (current == null) AlbumUiState.NotFound else AlbumUiState.Content(current, songs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlbumUiState.Loading)

  init {
    viewModelScope.launch { album.value = source.album(albumId) }
  }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    source.coverArtUrl(coverArtId, sizePx)

  companion object {
    const val ALBUM_ID_KEY = "albumId"
    private const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
