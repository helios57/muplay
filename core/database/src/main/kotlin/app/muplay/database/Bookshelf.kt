package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.LibraryDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The audiobook shelf, as the browse tree needs to see it.
 *
 * **A seam, and a temporary one.** Plan 4 Task 4's `AudiobookRepository` owns the bookshelf and had
 * not landed when Plan 5 Task 4 was implemented -- see [BookProgress]'s own provenance note for the
 * measurement. This interface is the one line Plan 4 has to repoint: `BrowseTreeRepository` takes a
 * [Bookshelf], and swapping the binding in `DataModule` swaps the whole implementation without
 * touching a browse decision.
 *
 * Four methods and no more. The browse tree asks "which books", "this one book", "this book's
 * files" and "which file was the listener in"; every other audiobook question -- chapters, speed,
 * silence skipping, and **at what second** to resume -- is Plan 4's and is deliberately absent here
 * rather than stubbed.
 *
 * **The fourth method was three when Task 4 wrote this, and the correction is worth reading rather
 * than reverting.** That doc said "where to resume from" was Plan 4's, and half of it still is:
 * Plan 4's `AudiobookResumePolicy` owns the *position*. What Plan 5 Task 5 needs is the *file*,
 * because `ResumePolicy.resolve(mediaIds, requestedIndex)` cannot tell "play this book" from "play
 * chapter 1 from the top" -- the caller picks the index and the policy picks the position, which is
 * Plan 4's own seam correction. [resumeFileId] is that index's input and carries no offset at all.
 */
interface Bookshelf {

  /** Every book in every library the user tagged `AUDIOBOOKS`. Order is the caller's to impose. */
  suspend fun books(): List<BookSummary>

  /** One book, or `null` when no audiobook library holds it. */
  suspend fun book(bookId: String): BookSummary?

  /** One book's files, in disc/track order -- the same order `BrowseDao.observeSongs` imposes. */
  suspend fun files(bookId: String): List<Song>

  /**
   * The id of the file the listener was last in, or `null` when nothing has been written for this
   * book at all.
   *
   * A **file id**, never an offset. `BrowseTreeRepository.expand` turns it into a queue index; the
   * second within that file is `ResumePolicy`'s and reaches the player through `MuPlayer`, which is
   * the seam spec section 3 exists for.
   */
  suspend fun resumeFileId(bookId: String): String?
}

/**
 * The shipped [Bookshelf]: albums in an `AUDIOBOOKS` library, joined to `media_progress`.
 *
 * **`LibraryRole` is the only signal that something is a book**, per spec section 4 -- Navidrome
 * hardcodes `child.Type = "music"` for every file and reports no audiobook flag at all. Nothing
 * here reads a suffix, a folder name, a duration or a chapter count, and nothing may.
 *
 * The album *is* the book: a multi-file M4B rip is one album of parts, which is exactly what
 * [BookSummary.fileCount] and `BrowseTree.bookChildren` render. Every number below comes from the
 * files this mirror actually holds rather than from the album row's own `songCount`/`durationSeconds`
 * so that a position derived by adding file durations can never exceed the duration it is a
 * fraction of.
 */
@Singleton
class MirrorBookshelf @Inject constructor(
  private val libraryDao: LibraryDao,
  private val browseDao: BrowseDao,
  private val mediaProgressDao: MediaProgressDao,
) : Bookshelf {

  override suspend fun books(): List<BookSummary> {
    val libraryIds = libraryDao.idsWithRole(LibraryRole.AUDIOBOOKS)
    if (libraryIds.isEmpty()) return emptyList()

    // One songs query for the whole shelf rather than one per book: a car asks for the shelf on
    // every connect, and the per-book form is an N+1 over a list a user chose the length of.
    val filesByAlbum = browseDao.songsInLibraries(libraryIds)
      .map(MirrorMapper::song)
      .groupBy(Song::albumId)
    val rows = mediaProgressDao.findAll().associateBy { it.mediaId }

    return libraryIds.flatMap { libraryId -> browseDao.observeAlbums(libraryId).first() }
      .map { album ->
        val files = filesByAlbum[album.id].orEmpty()
        val position = BookProgress.of(files, rows)
        BookSummary(
          bookId = album.id,
          libraryId = album.libraryId,
          title = album.name,
          author = album.artistName.orEmpty(),
          coverArtId = album.coverArtId,
          fileCount = files.size,
          durationMs = files.sumOf { it.durationSeconds * 1_000L },
          positionMs = position.positionMs,
          isFinished = position.isFinished,
          lastPlayedAtEpochMs = position.lastPlayedAtEpochMs,
        )
      }
  }

  /**
   * Looked up through [books] rather than by a second query, so that "this album is in an audiobook
   * library" is decided once. A book id that names a *music* album must answer `null` here, and a
   * dedicated `findAlbum` would have answered it happily.
   */
  override suspend fun book(bookId: String): BookSummary? =
    books().firstOrNull { it.bookId == bookId }

  override suspend fun files(bookId: String): List<Song> =
    browseDao.observeSongs(bookId).first().map(MirrorMapper::song)

  /**
   * Answered through [BookProgress] rather than by a `MAX(lastPlayedAtEpochMs)` query, and the
   * reason is the same one [book] gives for going through [books]: the rule that decides which row
   * is the listener's -- most recently written, ties to the later file -- is one rule, and a second
   * query would be a second copy of it that drifts. A book the shelf draws a pip on and a queue
   * that starts on a different file is exactly the disagreement that produces.
   */
  override suspend fun resumeFileId(bookId: String): String? {
    val files = files(bookId)
    if (files.isEmpty()) return null
    val rows = mediaProgressDao.findAll().associateBy { it.mediaId }
    return BookProgress.of(files, rows).fileMediaId
  }
}
