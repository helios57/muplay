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

  // `libraryId` is required, not folded into `artistId`: Navidrome artist ids are global, so the
  // same artist id can genuinely exist in two libraries (see `ArtistEntity`'s own doc) and only
  // the caller's own scope decides which library's album list this should return.
  fun albumsByArtist(libraryId: Int, artistId: String): Flow<List<Album>> =
    browseDao.observeAlbumsByArtist(libraryId, artistId).map { rows -> rows.map(MirrorMapper::album) }

  fun songs(albumId: String): Flow<List<Song>> =
    browseDao.observeSongs(albumId).map { rows -> rows.map(MirrorMapper::song) }

  suspend fun album(albumId: String): Album? =
    browseDao.findAlbum(albumId)?.let(MirrorMapper::album)

  /**
   * One song by its server id, or `null` when the mirror has never seen it.
   *
   * Unscoped by library, and that is not an oversight: a Subsonic song id is globally unique (see
   * `AlbumEntity`'s own note on which ids are and are not), and the caller that needs this --
   * `BrowseTreeRepository.node` answering `onGetItem` for a bare track id out of a car's persisted
   * recents -- has no library to scope by.
   */
  suspend fun song(songId: String): Song? =
    browseDao.findSong(songId)?.let(MirrorMapper::song)

  /**
   * Searches the mirror within one library.
   *
   * The LIKE pattern itself is built by [MirrorMapper.searchPattern] — pure string work with no
   * DAO/Android dependency, moved there so it is JVM-testable rather than needing an emulator to
   * exercise the trim/escape logic that actually decides what a query matches.
   */
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
    val pattern = MirrorMapper.searchPattern(query)
      ?: return SearchResults(emptyList(), emptyList(), emptyList())
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
