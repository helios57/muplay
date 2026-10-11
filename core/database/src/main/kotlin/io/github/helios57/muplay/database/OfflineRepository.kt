package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.OfflineDao
import io.github.helios57.muplay.database.entity.OfflinePlaylistEntity
import io.github.helios57.muplay.database.entity.OfflinePlaylistRow
import io.github.helios57.muplay.database.entity.OfflinePlaylistSongEntity
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.Song
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A playlist kept on the phone, and how much of its audio has arrived. */
data class OfflinePlaylist(val playlist: Playlist, val total: Int, val downloaded: Int) {
  val isComplete: Boolean get() = total > 0 && downloaded >= total
}

/**
 * **Which playlists are kept on the phone, and which of their songs are downloaded.**
 *
 * Bookkeeping only. The audio lives in `:core:media`'s offline cache, under each song's id; this
 * repository says which ids belong there, and hands back the ids nothing needs any more so the
 * caller can delete their bytes.
 */
@Singleton
class OfflineRepository @Inject constructor(
  private val dao: OfflineDao,
  private val clock: Clock,
) {

  fun observe(): Flow<List<OfflinePlaylist>> = dao.observe().map { rows -> rows.map(::toModel) }

  suspend fun playlist(playlistId: String): Playlist? = dao.playlist(playlistId)?.toPlaylist()

  /** The saved songs, in the playlist's order, read from the mirror -- no connection needed. */
  suspend fun songs(playlistId: String): List<Song> = dao.songs(playlistId).map(MirrorMapper::song)

  /** Keeps [playlist] with [songs] as its order. Returns song ids no saved playlist holds any more. */
  suspend fun save(playlist: Playlist, songs: List<Song>): List<String> = dao.save(
    OfflinePlaylistEntity(
      id = playlist.id,
      name = playlist.name,
      songCount = songs.size,
      durationSeconds = songs.sumOf { it.durationSeconds },
      owner = playlist.owner,
      coverArtId = playlist.coverArtId,
      savedAtEpochMs = clock.millis(),
    ),
    songs.mapIndexed { index, song -> OfflinePlaylistSongEntity(playlist.id, index, song.id) },
  )

  /** Forgets [playlistId]. Returns the song ids whose audio can now be deleted. */
  suspend fun remove(playlistId: String): List<String> = dao.remove(playlistId)

  /** Songs a saved playlist holds whose audio is not on the phone yet. */
  suspend fun pending(): List<Song> = dao.pending().map(MirrorMapper::song)

  suspend fun downloadedIds(): Set<String> = dao.downloadedIds().toSet()

  /**
   * Records [songId]'s audio as on the phone. False when no saved playlist holds it any more -- it
   * was removed while it downloaded -- and the caller should delete the bytes it just wrote.
   */
  suspend fun markDownloaded(songId: String): Boolean = dao.markDownloaded(songId) > 0

  suspend fun clear() = dao.clear()

  private fun toModel(row: OfflinePlaylistRow) =
    OfflinePlaylist(row.playlist.toPlaylist(), total = row.total, downloaded = row.downloaded)

  private fun OfflinePlaylistEntity.toPlaylist() = Playlist(
    id = id,
    name = name,
    songCount = songCount,
    durationSeconds = durationSeconds,
    owner = owner,
    coverArtId = coverArtId,
  )
}
