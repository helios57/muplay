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
 * Three methods and no more. The browse tree asks "which books", "this one book" and "this book's
 * files"; every other audiobook question -- chapters, speed, silence skipping, where to resume from
 * -- is Plan 4's and is deliberately absent here rather than stubbed.
 */
interface Bookshelf {

  /** Every book in every library the user tagged `AUDIOBOOKS`. Order is the caller's to impose. */
  suspend fun books(): List<BookSummary>

  /** One book, or `null` when no audiobook library holds it. */
  suspend fun book(bookId: String): BookSummary?

  /** One book's files, in disc/track order -- the same order `BrowseDao.observeSongs` imposes. */
  suspend fun files(bookId: String): List<Song>
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
}
