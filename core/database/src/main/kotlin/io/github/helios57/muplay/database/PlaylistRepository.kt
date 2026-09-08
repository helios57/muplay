package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's server-side playlists, read live.
 *
 * **Not mirrored, unlike everything `BrowseRepository` serves.** A playlist is small, changes for
 * reasons no `lastScan` watermark reports (editing one is not a library rescan), and is the one
 * browse surface where a stale answer is actively wrong -- a listener who just added a track and
 * does not see it will conclude the app is broken. So this reads the server every time and has no
 * table. The cost is that playlists need a connection; the mirror still serves albums, folders and
 * books offline.
 *
 * The mirror is still consulted for one thing: which **library** each entry belongs to.
 */
@Singleton
class PlaylistRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  suspend fun playlists(): List<Playlist> = sourceProvider.current().getPlaylists()

  /**
   * One playlist and its entries, with each entry's library corrected from the mirror.
   *
   * `getPlaylist` takes a `musicFolderId` only as a *stamp*: the protocol has no per-entry library
   * and a playlist may legitimately mix a music track with an audiobook chapter. That stamp is not
   * cosmetic -- `libraryId` matched against the user's `LibraryRole` assignment is the **only**
   * thing that tells this application a track is an audiobook (Navidrome hardcodes `Type = "music"`
   * for every media file), so it decides which player screen opens and whether a position is
   * remembered per book. Stamping a whole playlist with one library would therefore open the music
   * player on a book chapter.
   *
   * So every entry the mirror recognises is re-stamped with the library the mirror has it in.
   * Entries the mirror has never seen keep [fallbackLibraryId] -- the library the user is browsing
   * -- which is a guess, and the honest one: the alternative is dropping a track the server says is
   * in the playlist, and a playlist that silently loses rows is worse than one whose unknown row
   * opens the wrong player.
   */
  suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs {
    val fetched = sourceProvider.current().getPlaylist(playlistId, fallbackLibraryId)
    if (fetched.songs.isEmpty()) return fetched

    val known = browseDao.songsByIds(fetched.songs.map { it.id }).associate { it.id to it.libraryId }
    return fetched.copy(
      songs = fetched.songs.map { song ->
        val libraryId = known[song.id]
        if (libraryId == null || libraryId == song.libraryId) song else song.copy(libraryId = libraryId)
      },
    )
  }
}
