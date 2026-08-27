package app.muplay.database

import app.muplay.database.dao.AudiobookDao
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.ResumePoint
import app.muplay.model.Song
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Everything the application knows about audiobooks, in one place.
 *
 * **A book is an album in a library the user tagged `AUDIOBOOKS`**, and its id is the album id.
 * That definition lives here and nowhere else; every other component asks rather than re-deriving,
 * because a second definition is how "is this an audiobook" ends up answered two different ways in
 * two different screens.
 *
 * A loose file with no album is its own book for settings and for position (see [bookIdOf]) but
 * has no shelf row, because the shelf is a list of albums. That is a stated limitation, not an
 * oversight; inventing synthetic albums for it would put a fake book on a real shelf.
 *
 * ### It is also the [Bookshelf] the browse tree reads
 *
 * Plan 5 Task 4 needed a shelf before this class existed and wrote `MirrorBookshelf` as an
 * explicitly temporary stand-in, recording that Plan 4 Task 4 owned deleting or reconciling it.
 * This class **is** that reconciliation: `MirrorBookshelf` and `BookProgress` are deleted, the
 * `Bookshelf` interface is kept exactly as it was, and `DataModule` binds this class to it. There
 * is one derivation of "where is the listener in this book" in the repository -- [BookSummaries] --
 * and `BrowseTreeRepository` and the bookshelf screen now read the same one.
 */
@Singleton
class AudiobookRepository @Inject constructor(
  private val audiobookDao: AudiobookDao,
  private val mediaProgressDao: MediaProgressDao,
  private val bookSettingsDao: BookSettingsDao,
  private val clock: Clock,
) : Bookshelf {

  /**
   * The whole shelf, in shelf order, re-emitted whenever the mirror or `media_progress` changes.
   *
   * Three flows and not three reads: a position written by `ProgressWriter` five seconds into a
   * chapter has to move the row the listener is looking at, and a shelf that read once at
   * subscription would show the app's first second of life until the screen was left and
   * re-entered.
   *
   * **It can emit a transient inconsistent shelf, and that is measured rather than assumed.**
   * Room's invalidation tracker fires per *table*, so a `BrowseDao.replaceLibraryContents` that
   * rewrites `albums` and `songs` inside one transaction still reaches this `combine` as two
   * separate re-emissions -- and the first was observed on-device with a newly synced book present
   * and `durationMs = 0`, its songs query not yet re-run. It settles on the next emission, so a
   * screen collecting this Flow sees a flicker during a sync rather than a wrong shelf.
   * `AudiobookRepositoryTest.theShelfUpdatesWhenTheMirrorChanges` drains to the settled emission
   * and says so at the line. Removing the flicker means one `@Transaction`-scoped query with an
   * `@Relation` in place of the first two flows; it is not done here because nothing in this plan
   * renders the shelf yet, and Task 9 is where a flicker would first be visible.
   */
  fun bookshelf(): Flow<List<BookSummary>> = combine(
    audiobookDao.observeBookAlbums(LibraryRole.AUDIOBOOKS),
    audiobookDao.observeSongsInRole(LibraryRole.AUDIOBOOKS),
    mediaProgressDao.observeAll(),
  ) { albums, songs, progress ->
    val filesByBook = songs.groupBy { it.albumId ?: it.id }
    val progressById = progress.associateBy { it.mediaId }
    BookSummaries.order(
      albums.map { album ->
        BookSummaries.summarise(album, filesByBook[album.id].orEmpty(), progressById)
      },
    )
  }

  /**
   * [Bookshelf]'s snapshot read, answered from [bookshelf] rather than by a second set of queries.
   *
   * Room's `Flow` queries emit their first value as soon as they are collected, so this is one
   * round trip and not a subscription left open -- and it is what keeps a car's Continue shelf and
   * the phone's bookshelf screen from being two derivations that disagree about one book.
   */
  override suspend fun books(): List<BookSummary> = bookshelf().first()

  /**
   * One book, or `null` when no audiobook library holds it.
   *
   * The album is looked up **first** and its absence is the `null`: an album row with no mirrored
   * files yet is a real state (a sync that has not reached its songs) and is still a book, whereas
   * a music album id must answer `null` however many files it has. `findBookAlbum` carries the
   * role guard that decides both.
   */
  override suspend fun book(bookId: String): BookSummary? {
    val album = audiobookDao.findBookAlbum(bookId, LibraryRole.AUDIOBOOKS) ?: return null
    val files = filesOf(bookId)
    return BookSummaries.summarise(album, files, progressFor(files.map { it.id }))
  }

  /** The book's files, in play order, as domain models. */
  override suspend fun files(bookId: String): List<Song> =
    BookSummaries.playOrder(filesOf(bookId)).map(MirrorMapper::song)

  /**
   * The **file** the listener was in, and nothing else -- [Bookshelf]'s narrowest method, kept
   * narrow on purpose so a browse tree cannot see a position it might be tempted to seek with.
   */
  override suspend fun resumeFileId(bookId: String): String? = resumePoint(bookId)?.mediaId

  /**
   * Which file the listener was in and how far into that file, or `null` for a book nothing has
   * been written for.
   *
   * The position here is **inside one file**, not inside the book, and it is not a resume
   * position: the smart rewind has not been applied and `ResumePolicy` is the only thing allowed
   * to decide the second playback starts at.
   */
  suspend fun resumePoint(bookId: String): ResumePoint? {
    val files = filesOf(bookId)
    return BookSummaries.resumePoint(files, progressFor(files.map { it.id }))
  }

  suspend fun settings(bookId: String): BookSettings =
    bookSettingsDao.find(bookId).toSettings(bookId)

  fun observeSettings(bookId: String): Flow<BookSettings> =
    bookSettingsDao.observe(bookId).map { it.toSettings(bookId) }

  /**
   * Read-modify-write, and that is the whole point.
   *
   * Plan 3 Task 8 named this trap on `media_progress`: a writer that constructs a fresh entity
   * resets a listener's other setting every time it runs. It exists identically one table over --
   * a `setSpeed` that wrote `BookSettingsEntity(bookId, speed, skipSilence = false)` would turn
   * silence skipping off every time the listener touched the speed control, and nothing would
   * report it.
   */
  suspend fun setSpeed(bookId: String, speed: Float) {
    val existing = bookSettingsDao.find(bookId)
    bookSettingsDao.upsert(
      BookSettingsEntity(
        bookId = bookId,
        speed = BookSettings.clampSpeed(speed),
        skipSilence = existing?.skipSilence ?: false,
      ),
    )
  }

  /** The same read-modify-write, from the other side; see [setSpeed]. */
  suspend fun setSkipSilence(bookId: String, enabled: Boolean) {
    val existing = bookSettingsDao.find(bookId)
    bookSettingsDao.upsert(
      BookSettingsEntity(
        bookId = bookId,
        speed = BookSettings.clampSpeed(existing?.speed ?: BookSettings.DEFAULT_SPEED),
        skipSilence = enabled,
      ),
    )
  }

  /**
   * "Start from the beginning", expressed as **removing** progress rather than as setting a
   * position to zero.
   *
   * The seam (Plan 3 Task 8) makes a caller-chosen position unreachable, correctly, so this is the
   * only honest way to say it -- and it is the better state anyway: there is no position, rather
   * than a position that happens to be zero next to a `lastPlayedAt` claiming the listener was
   * there. It also un-finishes a finished book, which is the behaviour Plan 3 deferred to "the
   * plan that has a UI to express it".
   *
   * The ids cleared are the **files'**, never the book's. A `clear(listOf(bookId))` looks right,
   * deletes nothing for a multi-file book, and is invisible on a single-file one.
   */
  suspend fun restart(bookId: String) =
    mediaProgressDao.clear(filesOf(bookId).map { it.id })

  /**
   * Mark every file of a book heard to its end.
   *
   * The columns this plan does not own -- `speed`, `skipSilence`, `gainDb` -- are read back and
   * preserved rather than defaulted, which is the same discipline `ProgressWriter` applies, for
   * the same reason: `gainDb` is Plan 3 Task 11's measured ReplayGain and re-deriving it is not
   * this class's business.
   */
  suspend fun markFinished(bookId: String) {
    val files = BookSummaries.playOrder(filesOf(bookId))
    val existing = progressFor(files.map { it.id })
    val now = clock.millis()
    for (file in files) {
      val row = existing[file.id]
      mediaProgressDao.upsert(
        MediaProgressEntity(
          mediaId = file.id,
          positionMs = file.durationSeconds * 1_000L,
          isFinished = true,
          lastPlayedAtEpochMs = now,
          speed = row?.speed ?: BookSettings.DEFAULT_SPEED,
          skipSilence = row?.skipSilence ?: false,
          gainDb = row?.gainDb ?: 0.0f,
        ),
      )
    }
  }

  /**
   * Every audiobook file, mapped to its book. The input to `AudiobookSnapshot` (Task 6), which is
   * what makes "only books resume" structural rather than conventional: a media id absent from
   * this map is not an audiobook, and the resume policy has nothing to answer with.
   */
  fun observeAudiobookItems(): Flow<Map<String, String>> =
    audiobookDao.observeItems(LibraryRole.AUDIOBOOKS)
      .map { rows -> rows.associate { it.mediaId to (it.albumId ?: it.mediaId) } }

  private suspend fun filesOf(bookId: String): List<SongEntity> =
    audiobookDao.files(bookId, LibraryRole.AUDIOBOOKS)

  private suspend fun progressFor(mediaIds: List<String>): Map<String, MediaProgressEntity> =
    mediaProgressDao.findIn(mediaIds).associateBy { it.mediaId }

  /**
   * Clamped on the way **out** as well as on the way in, so a row that got past the setters -- a
   * hand-edited database, a future bug, a `NaN` from arithmetic on a corrupt `REAL` column --
   * still cannot reach `ExoPlayer.setPlaybackSpeed`, which throws from inside a listener callback
   * and surfaces as playback dying with no message.
   */
  private fun BookSettingsEntity?.toSettings(bookId: String): BookSettings = when (this) {
    null -> BookSettings.default(bookId)
    else -> BookSettings(bookId, BookSettings.clampSpeed(speed), skipSilence)
  }

  companion object {
    /**
     * A book is an album; a file with no album is its own book.
     *
     * Both overloads exist because callers hold whichever of the two types is nearer to hand, and
     * the alternative -- everyone writing `song.albumId ?: song.id` -- is exactly how one screen
     * ends up disagreeing with another about what a book is.
     */
    fun bookIdOf(song: Song): String = song.albumId ?: song.id

    fun bookIdOf(song: SongEntity): String = song.albumId ?: song.id
  }
}
