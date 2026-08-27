package app.muplay.media

import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.dao.ChapterDao
import app.muplay.database.entity.ChapterEntity
import app.muplay.model.Chapter
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chapters, parsed once and then served from Room.
 *
 * Reading chapters is an HTTP round trip into a file's `moov` atom. Doing it every time a book
 * screen opens is a network request for data that cannot change unless the file does.
 *
 * The half that is easy to miss: **"this file has no chapters" is an answer, and it needs to be
 * remembered too.** Most audiobook files carry no chapter atoms at all, so a cache that can only
 * store chapters re-probes the common case forever. `chapter_scans` records that a probe happened;
 * `chapters` records what it found.
 *
 * Nothing here is ever sent to the server. The only outbound request is the `GET` the retriever
 * itself issues for the file's own bytes, and the stream URL that carries the credentials is built
 * per call and never stored, logged or written to Room.
 */
@Singleton
class ChapterRepository @Inject constructor(
  private val chapterDao: ChapterDao,
  private val chapterReader: ChapterReader,
  private val sourceProvider: SubsonicSourceProvider,
  private val clock: Clock,
) {

  /**
   * The chapters of [song]'s file: from Room if it has ever been probed, over HTTP if not.
   *
   * The short-circuit is on the **scan** row, not on the chapter rows: an empty `find` cannot
   * distinguish "no chapters" from "never looked", and the ambiguous reading re-probes every
   * chapterless file forever.
   */
  suspend fun chaptersFor(song: Song): List<Chapter> {
    chapterDao.findScan(song.id)?.let { return chapterDao.find(song.id).map(::toChapter) }

    val source = sourceProvider.current()
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    val chapters = chapterReader.read(
      mediaId = song.id,
      uri = source.streamUrl(song.id, format),
      contentDurationMs = song.durationSeconds * 1_000L,
    )

    chapterDao.store(
      mediaId = song.id,
      chapters = chapters.map {
        ChapterEntity(
          mediaId = song.id,
          chapterIndex = it.index,
          startMs = it.startMs,
          endMs = it.endMs,
          title = it.title,
        )
      },
      scannedAtEpochMs = clock.millis(),
    )
    return chapters
  }

  /**
   * Every file of a book at once, keyed by media id rather than by title -- two files of a book
   * can share a title, and nothing downstream looks a file up by name.
   */
  suspend fun chaptersFor(songs: List<Song>): Map<String, List<Chapter>> =
    songs.associate { it.id to chaptersFor(it) }

  /** Drops both the chapters and the record that a probe happened, so the next read re-probes. */
  suspend fun forget(mediaId: String) = chapterDao.clear(mediaId)

  private fun toChapter(entity: ChapterEntity) = Chapter(
    index = entity.chapterIndex,
    startMs = entity.startMs,
    endMs = entity.endMs,
    title = entity.title,
  )
}
