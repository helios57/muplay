package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.SearchResults
import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The browse surface, read entirely from the local mirror.
 *
 * Reading locally rather than per-screen from the server is what makes browsing work offline and
 * instantly, and it is why `SyncEngine` exists. [search] is a mirror search for the same reason:
 * `search3` is implemented and contract-tested on the client, but a complete mirror can answer
 * the same question with no network and no scoping risk.
 */
@Singleton
class BrowseRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  fun artists(libraryId: Int): Flow<List<Artist>> =
    browseDao.observeArtists(libraryId).map { rows -> rows.map(MirrorMapper::artist) }

  fun albums(libraryId: Int): Flow<List<Album>> =
    browseDao.observeAlbums(libraryId).map { rows -> rows.map(MirrorMapper::album) }

  fun albumsByArtist(artistId: String): Flow<List<Album>> =
    browseDao.observeAlbumsByArtist(artistId).map { rows -> rows.map(MirrorMapper::album) }

  fun songs(albumId: String): Flow<List<Song>> =
    browseDao.observeSongs(albumId).map { rows -> rows.map(MirrorMapper::song) }

  suspend fun album(albumId: String): Album? =
    browseDao.findAlbum(albumId)?.let(MirrorMapper::album)

  /**
   * Searches the mirror within one library.
   *
   * The LIKE pattern is built here, once, and the user's own `%` and `_` are escaped with a
   * backslash so a query containing them matches those characters literally instead of turning
   * into a wildcard the user did not type.
   */
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
    val pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    return SearchResults(
      artists = browseDao.searchArtists(libraryId, pattern, limit).map(MirrorMapper::artist),
      albums = browseDao.searchAlbums(libraryId, pattern, limit).map(MirrorMapper::album),
      songs = browseDao.searchSongs(libraryId, pattern, limit).map(MirrorMapper::song),
    )
  }

  /** An authenticated cover-art URL for the current server. */
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int?): String =
    sourceProvider.current().coverArtUrl(coverArtId, sizePx)
}
