package app.muplay.media

import androidx.media3.common.MediaItem
import app.muplay.database.SubsonicSourceProvider
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
 * DataStore reads for an answer that cannot change mid-call.
 */
@Singleton
class QueueRepository @Inject constructor(private val sourceProvider: SubsonicSourceProvider) {

  suspend fun mediaItems(queue: PlaybackQueue): List<MediaItem> {
    val source = sourceProvider.current()
    return queue.songs.map { song -> mediaItem(source, song) }
  }

  private fun mediaItem(source: SubsonicSource, song: Song): MediaItem {
    // Per song, not per queue: a library can hold both an Opus file and a FLAC, and deciding once
    // for the whole queue would send one of them the wrong way.
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    return MediaItems.of(
      song = song,
      streamUri = source.streamUrl(song.id, format),
      artworkUri = song.coverArtId?.let { source.coverArtUrl(it, ARTWORK_SIZE_PX) },
      // The same value the URL was built from, passed rather than recomputed: the served MIME
      // type and the `format` query parameter are two statements of one decision, and deciding
      // twice is how they drift.
      format = format,
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
