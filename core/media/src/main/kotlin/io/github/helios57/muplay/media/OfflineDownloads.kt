package io.github.helios57.muplay.media

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheWriter
import io.github.helios57.muplay.database.OfflinePlaylist
import io.github.helios57.muplay.database.OfflineRepository
import io.github.helios57.muplay.database.SubsonicSourceProvider
import io.github.helios57.muplay.media.di.OfflineAudioCache
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.StreamFormat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** What the downloader is doing right now. Per-playlist counts come from [OfflineDownloads.saved]. */
sealed interface DownloadActivity {
  data object Idle : DownloadActivity

  data class Downloading(val title: String) : DownloadActivity

  /** Stopped on a failure; [OfflineDownloads.resume] tries again. */
  data class Stalled(val reason: String) : DownloadActivity
}

/**
 * **Keeps saved playlists' audio on the phone.**
 *
 * One song at a time, in the background, into the [OfflineAudioCache] under the song's id -- the
 * same key playback reads, which is the whole mechanism: `MuPlayDataSourceFactory.create` looks in
 * that cache first, so a downloaded song plays with no request. What should be there is
 * [OfflineRepository]'s; this class makes the bytes match it.
 *
 * Stops at the first failure rather than walking the rest of the list into the same dead
 * connection, and says so ([DownloadActivity.Stalled]); [resume] picks up where it stopped, and a
 * half-written song continues from its last byte because `CacheWriter` reads what is cached first.
 */
@Singleton
@OptIn(UnstableApi::class)
class OfflineDownloads @Inject constructor(
  private val repository: OfflineRepository,
  private val sourceProvider: SubsonicSourceProvider,
  private val dataSources: MuPlayDataSourceFactory,
  @OfflineAudioCache private val cache: Cache,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activity = MutableStateFlow<DownloadActivity>(DownloadActivity.Idle)
  private var job: Job? = null

  @Volatile private var writing: Pair<String, CacheWriter>? = null

  val state: StateFlow<DownloadActivity> = activity.asStateFlow()

  fun saved(): Flow<List<OfflinePlaylist>> = repository.observe()

  /** Keeps [playlist] as [songs] and starts fetching whatever of it is not here yet. */
  suspend fun save(playlist: Playlist, songs: List<Song>) {
    delete(repository.save(playlist, songs))
    resume()
  }

  /** Forgets [playlistId] and deletes the audio no other saved playlist needs. */
  suspend fun remove(playlistId: String) = delete(repository.remove(playlistId))

  /** The kept copy of [playlistId], downloaded songs only, or null when it is not kept. */
  suspend fun keptCopy(playlistId: String): PlaylistWithSongs? {
    val playlist = repository.playlist(playlistId) ?: return null
    val downloaded = repository.downloadedIds()
    return PlaylistWithSongs(playlist, repository.songs(playlistId).filter { it.id in downloaded })
  }

  /** Starts the downloader if it is not running. Safe to call as often as a screen opens. */
  @Synchronized
  fun resume() {
    if (job?.isActive == true) return
    job = scope.launch { drain() }
  }

  /** Deletes every saved song's audio. Sign-out; the bookkeeping is cleared by its own entry. */
  suspend fun clearAudio() {
    job?.cancel()
    writing?.second?.cancel()
    runInterruptible(Dispatchers.IO) { cache.keys.toList().forEach(cache::removeResource) }
    activity.value = DownloadActivity.Idle
  }

  private suspend fun drain() {
    while (true) {
      val next = repository.pending().firstOrNull() ?: break
      activity.value = DownloadActivity.Downloading(next.title)
      try {
        fetch(next)
      } catch (e: IOException) {
        if (repository.pending().none { it.id == next.id }) {
          // Removed while it downloaded, and the cancel is what threw. The sink commits what it had
          // written when it closes, after `delete` ran, so delete again now the writer is done.
          delete(listOf(next.id))
          continue
        }
        activity.value = DownloadActivity.Stalled(e.message ?: e.javaClass.simpleName)
        return
      }
      if (!repository.markDownloaded(next.id)) delete(listOf(next.id))
    }
    activity.value = DownloadActivity.Idle
  }

  private suspend fun fetch(song: Song) {
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    val url = sourceProvider.current().streamUrl(song.id, format)
    val spec = DataSpec.Builder().setUri(url.toUri()).setKey(song.id).build()
    val writer = CacheWriter(dataSources.createDownload(), spec, null, null)
    writing = song.id to writer
    try {
      runInterruptible(Dispatchers.IO) { writer.cache() }
    } finally {
      writing = null
    }
  }

  private suspend fun delete(songIds: List<String>) {
    if (songIds.isEmpty()) return
    writing?.let { (id, writer) -> if (id in songIds) writer.cancel() }
    runInterruptible(Dispatchers.IO) { songIds.forEach(cache::removeResource) }
  }
}
