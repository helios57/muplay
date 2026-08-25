package app.muplay.media

import androidx.media3.common.MediaItem
import app.muplay.database.LibraryRepository
import app.muplay.database.SubsonicSourceProvider
import app.muplay.model.LibraryRole
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [PlaybackQueue] of mirrored songs into the `MediaItem`s Media3 plays.
 *
 * The only entry point that builds a playable queue — per the constraints, repositories are the
 * only entry point to data and there is no domain layer, so the format decision and the URL
 * construction live here rather than in a use case or a ViewModel.
 *
 * One `SubsonicSource` for the whole queue, not one per song: `SubsonicSourceProvider.current()`
 * reads credentials, and doing that once per track in a hundred-track shuffle would be a hundred
 * DataStore reads for an answer that cannot change mid-call. The audiobook library set is read once
 * for the same reason, and is the only thing in this app that can answer "is this a book" -- see
 * [MediaItems] for why the protocol cannot.
 *
 * **`startIndex` is deliberately not consumed here.** `mediaItems` returns the *whole* queue,
 * whatever `PlaybackQueue.startIndex` names, because that index is an argument to
 * `Player.setMediaItems(items, startIndex, positionMs)`: dropping items here would hand Media3 a
 * truncated queue *and* an index into it. `PlaybackConnection.play` is the one place the two are
 * joined, and `MuPlaybackServiceTest.aQueueStartsPlayingAtTheTrackItsStartIndexNames` is the
 * assertion that they are -- on a device, because that is the only layer where it is observable.
 */
@Singleton
class QueueRepository @Inject constructor(
  private val sourceProvider: SubsonicSourceProvider,
  private val libraryRepository: LibraryRepository,
) {

  suspend fun mediaItems(queue: PlaybackQueue): List<MediaItem> {
    val source = sourceProvider.current()
    // Once per queue, not once per song: the set cannot change mid-call, and a hundred-track
    // shuffle would otherwise be a hundred identical database reads.
    val audiobookLibraries = libraryRepository.idsWithRole(LibraryRole.AUDIOBOOKS).toSet()
    return queue.songs.map { song -> mediaItem(source, song, song.libraryId in audiobookLibraries) }
  }

  private fun mediaItem(source: SubsonicSource, song: Song, isAudiobook: Boolean): MediaItem {
    // Per song, not per queue: a library can hold both an Opus file and a FLAC, and deciding once
    // for the whole queue would send one of them the wrong way.
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    return MediaItems.of(
      song = song,
      streamUri = source.streamUrl(song.id, format),
      artworkUri = song.coverArtId?.let { source.coverArtUrl(it, ARTWORK_SIZE_PX) },
      isAudiobook = isAudiobook,
    )
  }

  companion object {
    /**
     * The cover-art edge length requested for a notification and lock screen. Large enough for a
     * modern lock screen at 3x density, small enough that a hundred-item queue does not pull a
     * hundred full-resolution images through the notification's bitmap loader.
     */
    const val ARTWORK_SIZE_PX: Int = 512
  }
}
