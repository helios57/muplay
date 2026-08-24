package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/** Row counts either side of one [BrowseDao.replaceLibraryContents] call. */
data class MirrorReplacement(
  val artistsBefore: Int,
  val artistsAfter: Int,
  val albumsBefore: Int,
  val albumsAfter: Int,
  val songsBefore: Int,
  val songsAfter: Int,
)

/**
 * Every read the browse UI makes, and the one write the sync engine makes.
 *
 * **Every query takes a `libraryId`** except `observeSongs(albumId)`, whose `albumId` is already
 * known to belong to one library. `observeAlbumsByArtist` takes **both**: Navidrome artist ids are
 * global, so `artistId` alone cannot scope by library the way `albumId` can — see `ArtistEntity`'s
 * own doc for the two live bugs an unscoped version of this query and a bare-`id` primary key
 * produced. The absence of a "give me everything" query elsewhere is still the point.
 */
@Dao
abstract class BrowseDao {

  @Query("SELECT * FROM artists WHERE libraryId = :libraryId ORDER BY sortName")
  abstract fun observeArtists(libraryId: Int): Flow<List<ArtistEntity>>

  @Query("SELECT * FROM albums WHERE libraryId = :libraryId ORDER BY sortName")
  abstract fun observeAlbums(libraryId: Int): Flow<List<AlbumEntity>>

  @Query("SELECT * FROM albums WHERE libraryId = :libraryId AND artistId = :artistId ORDER BY sortName")
  abstract fun observeAlbumsByArtist(libraryId: Int, artistId: String): Flow<List<AlbumEntity>>

  @Query(
    "SELECT * FROM songs WHERE albumId = :albumId " +
      "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), sortTitle",
  )
  abstract fun observeSongs(albumId: String): Flow<List<SongEntity>>

  @Query("SELECT * FROM albums WHERE id = :albumId")
  abstract suspend fun findAlbum(albumId: String): AlbumEntity?

  // `pattern` is a full LIKE pattern including its wildcards, built by the caller -- so a query
  // containing a literal % or _ is the caller's problem to escape, once, rather than every query
  // here silently doing something different. SQLite's LIKE is case-insensitive for ASCII by
  // default, which is why these compare against the raw column and not against sortName.
  @Query(
    "SELECT * FROM artists WHERE libraryId = :libraryId AND name LIKE :pattern ESCAPE '\\' " +
      "ORDER BY sortName LIMIT :limit",
  )
  abstract suspend fun searchArtists(libraryId: Int, pattern: String, limit: Int): List<ArtistEntity>

  @Query(
    "SELECT * FROM albums WHERE libraryId = :libraryId AND name LIKE :pattern ESCAPE '\\' " +
      "ORDER BY sortName LIMIT :limit",
  )
  abstract suspend fun searchAlbums(libraryId: Int, pattern: String, limit: Int): List<AlbumEntity>

  @Query(
    "SELECT * FROM songs WHERE libraryId = :libraryId AND title LIKE :pattern ESCAPE '\\' " +
      "ORDER BY sortTitle LIMIT :limit",
  )
  abstract suspend fun searchSongs(libraryId: Int, pattern: String, limit: Int): List<SongEntity>

  /**
   * Which of [ids] the mirror agrees are songs in [libraryId]. Backs the shuffle scope guard: a
   * song the server returned for a "music" shuffle that this mirror says lives in the audiobook
   * library is dropped rather than played.
   */
  @Query("SELECT id FROM songs WHERE libraryId = :libraryId AND id IN (:ids)")
  abstract suspend fun songIdsInLibrary(libraryId: Int, ids: List<String>): List<String>

  /**
   * Replaces **everything** the mirror holds for one library, in one transaction.
   *
   * A full replace rather than a diff because Subsonic never reports deletions: there is no delta
   * primitive that can say "this album is gone", so the only reliable way to notice is to keep
   * exactly what the server just listed. Scoped to one library so a failure while reconciling the
   * audiobook library cannot empty the music library.
   *
   * The three `require` checks are the one thing standing between a caller's bug and a mirror row
   * landing in the wrong library: [libraryId] scopes the deletes and the counts, but the inserts
   * write whatever `libraryId` each entity already carries, and nothing about Room or SQLite
   * checks that the two agree. `libraryId` is the *only* link between a track and the user's
   * Music/Audiobooks decision (see this module's own doc), so a caller that built [songs] from the
   * wrong library's server pages must fail loudly here rather than silently mis-scope a row the
   * shuffle guard and every browse query then trust.
   */
  @Transaction
  open suspend fun replaceLibraryContents(
    libraryId: Int,
    artists: List<ArtistEntity>,
    albums: List<AlbumEntity>,
    songs: List<SongEntity>,
  ): MirrorReplacement {
    require(artists.all { it.libraryId == libraryId }) {
      "replaceLibraryContents($libraryId, ...) was given an artist row scoped to a different library"
    }
    require(albums.all { it.libraryId == libraryId }) {
      "replaceLibraryContents($libraryId, ...) was given an album row scoped to a different library"
    }
    require(songs.all { it.libraryId == libraryId }) {
      "replaceLibraryContents($libraryId, ...) was given a song row scoped to a different library"
    }

    val artistsBefore = countArtists(libraryId)
    val albumsBefore = countAlbums(libraryId)
    val songsBefore = countSongs(libraryId)

    deleteSongs(libraryId)
    deleteAlbums(libraryId)
    deleteArtists(libraryId)

    insertArtists(artists)
    insertAlbums(albums)
    insertSongs(songs)

    return MirrorReplacement(
      artistsBefore = artistsBefore,
      artistsAfter = countArtists(libraryId),
      albumsBefore = albumsBefore,
      albumsAfter = countAlbums(libraryId),
      songsBefore = songsBefore,
      songsAfter = countSongs(libraryId),
    )
  }

  @Query("SELECT COUNT(*) FROM artists WHERE libraryId = :libraryId")
  protected abstract suspend fun countArtists(libraryId: Int): Int

  @Query("SELECT COUNT(*) FROM albums WHERE libraryId = :libraryId")
  protected abstract suspend fun countAlbums(libraryId: Int): Int

  @Query("SELECT COUNT(*) FROM songs WHERE libraryId = :libraryId")
  protected abstract suspend fun countSongs(libraryId: Int): Int

  @Query("DELETE FROM artists WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteArtists(libraryId: Int)

  @Query("DELETE FROM albums WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteAlbums(libraryId: Int)

  @Query("DELETE FROM songs WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteSongs(libraryId: Int)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertArtists(artists: List<ArtistEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertAlbums(albums: List<AlbumEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertSongs(songs: List<SongEntity>)
}
