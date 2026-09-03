package app.muplay.media

import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.dao.ChapterDao
import app.muplay.database.entity.ChapterEntity
import app.muplay.model.Chapter
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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
 *
 * ### A failed probe is remembered too, in memory, and deliberately not in Room
 *
 * A probe that throws used to be remembered nowhere at all, so a file whose read failed was
 * re-probed on **every** open, at up to [ChapterReader.TIMEOUT_MS] each -- and because
 * `chaptersFor(List)` fails the whole book at the first file that throws, one bad file in a
 * forty-file book cost thirty seconds and produced nothing, every time, forever.
 *
 * [failures] is the answer, and the two halves of what it is are equally deliberate:
 *
 * * **A failure is remembered**, so the second open is instant rather than another timeout. It is
 *   keyed by media id, so one unreadable file does not make its neighbours look unreadable.
 * * **It is remembered in memory only.** The `chapter_scans` row means *"this file's chapters are
 *   known"*, and a failure knows nothing; writing one there would make it survive the outage that
 *   caused it and would be indistinguishable from the genuinely chapterless file that row exists
 *   to record. A server that comes back would then never be asked again. In memory, the
 *   remembering lasts exactly as long as the process -- which is the longest a network verdict
 *   should ever be trusted for.
 *
 * So there are two ways back: a new process, and [forgetFailures], which is what the book screen's
 * "Try again" calls. There is deliberately **no** timed expiry: a cooldown is a second healing
 * path whose window nobody can measure, and whose expiry re-introduces exactly the stall it exists
 * to prevent, at a moment no one chose.
 *
 * Note what is *not* needed as a consequence: nothing removes an entry on success. A file that has
 * ever succeeded has a `chapter_scans` row, and the short-circuit on that row is read first, so a
 * remembered failure for it can never be reached again.
 */
@Singleton
class ChapterRepository @Inject constructor(
  private val chapterDao: ChapterDao,
  private val chapterReader: ChapterReader,
  private val sourceProvider: SubsonicSourceProvider,
  private val clock: Clock,
) {

  /**
   * Media ids whose probe threw, and what it threw. See this class's header for why this is a map
   * in memory rather than a column in `chapter_scans`.
   *
   * `ConcurrentHashMap`, because this is a `@Singleton` and `chaptersFor` is called from whatever
   * coroutine a screen or a browse callback happens to be on.
   */
  private val failures = ConcurrentHashMap<String, Throwable>()

  /**
   * The chapters of [song]'s file: from Room if it has ever been probed, over HTTP if not.
   *
   * The short-circuit is on the **scan** row, not on the chapter rows: an empty `find` cannot
   * distinguish "no chapters" from "never looked", and the ambiguous reading re-probes every
   * chapterless file forever.
   *
   * Two short-circuits, in this order. The Room one first, because a file that has ever succeeded
   * is answered from its rows and can have no remembered failure; the in-memory one second, which
   * **throws** rather than returning empty -- "we could not read this" is not "there were none",
   * and the caller has a screen to draw that says different things about the two. See the class
   * header for why the second lives in memory.
   */
  suspend fun chaptersFor(song: Song): List<Chapter> {
    chapterDao.findScan(song.id)?.let { return chapterDao.find(song.id).map(::toChapter) }
    // The remembered failure, rethrown as itself rather than wrapped. Its stack trace names the
    // `ChapterReader.read` that actually failed, which is the more useful of the two stacks on
    // offer; a fresh exception here would name this line and say nothing about why.
    failures[song.id]?.let { throw it }

    val source = sourceProvider.current()
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    val chapters = try {
      chapterReader.read(
        mediaId = song.id,
        uri = source.streamUrl(song.id, format),
        contentDurationMs = song.durationSeconds * 1_000L,
      )
    } catch (e: CancellationException) {
      // A cancelled caller is a screen going away, not a file this repository should give up on.
      throw e
    } catch (e: Exception) {
      failures[song.id] = e
      throw e
    }

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

  /**
   * Drops every remembered failure, so the next read of any of those files reaches the server.
   *
   * Called by the book screen's "Try again". Not `suspend`, and it touches no row: a failure was
   * never written to one.
   */
  fun forgetFailures() = failures.clear()

  private fun toChapter(entity: ChapterEntity) = Chapter(
    index = entity.chapterIndex,
    startMs = entity.startMs,
    endMs = entity.endMs,
    title = entity.title,
  )
}
