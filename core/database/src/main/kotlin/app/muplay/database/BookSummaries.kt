package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSummary
import app.muplay.model.ResumePoint

/**
 * The shelf's arithmetic and its order, with no Room, no Android and no coroutines in sight.
 *
 * Split out of [AudiobookRepository] so it is gated in Tier 1: a three-key comparator with two
 * direction flips is precisely the code that looks right and is wrong at one boundary, and an
 * emulator round trip per mutation is the wrong price for finding that out.
 *
 * ### It is the only derivation of "where is the listener in this book"
 *
 * Plan 5 Task 4 needed a bookshelf before this task landed and wrote one -- `MirrorBookshelf` over
 * a `BookProgress` object -- explicitly marked as Plan 4 Task 4's *"to delete or to reconcile"*.
 * It is reconciled here rather than merged: `BookProgress` is gone, [AudiobookRepository]
 * implements [Bookshelf], and the two rules that file established which this object did not
 * originally carry are folded into [currentFileIndex] and asserted in `BookSummariesTest`:
 *
 * - **a tie in `lastPlayedAtEpochMs` resolves to the later file**, because a batch write really
 *   does produce two rows in one millisecond and `maxByOrNull` would answer the *earlier* one
 *   forever; and
 * - the comparison is on the timestamp and never on position in the list, so "resolve ties to the
 *   later file" and "always take the later file" stay different programs.
 *
 * The one rule that deliberately did **not** survive the reconciliation is `BookProgress`'s
 * `isFinished`, which required the most recent row to be the last file's. This task's rule is the
 * last file's row alone: a listener who finishes a book and then replays chapter one has still
 * finished it, and un-finishing is what `AudiobookRepository.restart` is for. No assertion in
 * either plan distinguishes the two on the fixtures that exist; the difference is recorded because
 * it is a behaviour change, not because a test found it.
 */
internal object BookSummaries {

  /**
   * Disc, then track, then title, then id.
   *
   * A file with no track number sorts **after** every numbered one rather than before: real rips
   * carry untagged extras ("Afterword", "About the author") and putting them first means the book
   * opens on its own afterword. A file with no *disc* number sorts with disc **one** rather than
   * before it, which is the opposite default for the opposite reason: a single-disc rip leaves the
   * column null on every file, and reading that as disc zero would file an untagged extra ahead of
   * a two-disc set's first disc.
   *
   * The trailing `id` key is not decoration. Without it `sortedWith` is stable and therefore falls
   * back to the order SQLite happened to return, which it is entitled to change between two
   * identical queries.
   */
  fun playOrder(files: List<SongEntity>): List<SongEntity> = files.sortedWith(
    compareBy(
      { it.discNumber ?: 1 },
      { it.trackNumber ?: Int.MAX_VALUE },
      { it.sortTitle },
      { it.id },
    ),
  )

  /**
   * One shelf row.
   *
   * [progress] is the whole `media_progress` table rather than this book's slice of it, because
   * `AudiobookRepository.bookshelf()` reads that table once for every book on the shelf. Every
   * lookup below is by file id for that reason.
   */
  fun summarise(
    album: AlbumEntity,
    files: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): BookSummary {
    val ordered = playOrder(files)
    val durationMs = ordered.sumOf { it.durationSeconds * 1_000L }
    val currentIndex = currentFileIndex(ordered, progress)
    val current = ordered.getOrNull(currentIndex)
    val currentProgress = current?.let { progress[it.id] }

    // Every file before the current one, plus how far into the current one. For a single-file book
    // this is just the file position, which is exactly why a single-file book cannot prove the
    // offset is computed at all.
    val offsetMs = ordered.take(currentIndex.coerceAtLeast(0))
      .sumOf { it.durationSeconds * 1_000L }

    return BookSummary(
      bookId = album.id,
      libraryId = album.libraryId,
      title = album.name,
      // `BookSummary.author` is non-null and `AlbumEntity.artistName` is not: a rip with no album
      // artist tag is a real row, and it renders as a book with no author rather than as no book.
      author = album.artistName.orEmpty(),
      coverArtId = album.coverArtId,
      fileCount = ordered.size,
      durationMs = durationMs,
      positionMs = offsetMs + (currentProgress?.positionMs ?: 0L),
      // Reaching the end of the last file is what finishes a book. "Every file finished" would
      // leave a book unfinished forever because the listener skipped a five-second interlude.
      isFinished = ordered.isNotEmpty() && progress[ordered.last().id]?.isFinished == true,
      // `0` is `BookSummary`'s own "never opened"; see its declaration.
      lastPlayedAtEpochMs = currentProgress?.lastPlayedAtEpochMs ?: 0L,
    )
  }

  /**
   * Which file the listener was in and how far into **that file** they were.
   *
   * Deliberately not the book position: what this feeds is a queue index plus a seek inside one
   * item, and `summarise`'s whole-book number would seek past the end of the first file.
   */
  fun resumePoint(
    files: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): ResumePoint? {
    val ordered = playOrder(files)
    val file = ordered.getOrNull(currentFileIndex(ordered, progress)) ?: return null
    // `getValue`, not `progress[file.id] ?: return null`. [currentFileIndex] only ever names a file
    // that has a row, so that second null arm is unreachable -- measured at 39/40 BRANCH with the
    // elvis form, the one missed branch being exactly it. Deleted rather than covered, which is the
    // same call `BrowseText`'s own floor comment records for its unreachable `max(0L, ..)`: a
    // branch no input can take is a branch no assertion can gate.
    val row = progress.getValue(file.id)
    return ResumePoint(
      mediaId = file.id,
      positionMs = row.positionMs,
      lastPlayedAtEpochMs = row.lastPlayedAtEpochMs,
    )
  }

  /**
   * Continue-listening first (most recent first), then never-opened (alphabetical), then finished
   * (most recent first).
   *
   * The group key is what makes a finished book sink below an unopened one even though it was
   * heard a minute ago -- sorting purely by time produces the most annoying shelf available.
   *
   * The last two keys make the order total. A shelf that reorders itself between two identical
   * reads is the same defect a car list has when its tie-break is missing, and it is invisible
   * until a user notices their books moving.
   */
  fun order(books: List<BookSummary>): List<BookSummary> = books.sortedWith(
    compareBy<BookSummary> { group(it) }
      .thenByDescending { if (group(it) == GROUP_UNSTARTED) 0L else it.lastPlayedAtEpochMs }
      .thenBy { it.title.lowercase() }
      .thenBy { it.bookId },
  )

  private const val GROUP_IN_PROGRESS = 0
  private const val GROUP_UNSTARTED = 1
  private const val GROUP_FINISHED = 2

  /**
   * `hasStarted` and not a timestamp test, so this module holds exactly one definition of "the
   * listener has begun this book" -- [BookSummary.hasStarted] -- rather than a second one that
   * drifts from the one `BrowseTree.continueNodes` filters a car's Continue shelf with.
   */
  private fun group(book: BookSummary): Int = when {
    book.isFinished -> GROUP_FINISHED
    book.hasStarted -> GROUP_IN_PROGRESS
    else -> GROUP_UNSTARTED
  }

  /**
   * The index of the most recently heard file, or `-1` when nothing has been written for this
   * book.
   *
   * The most recently *heard* file, not the furthest one: a listener who jumped back to chapter 1
   * is in chapter 1, and "furthest" would drag them forward every time they went back.
   *
   * `>=`, so a tie goes to the **later file**. Two rows in one millisecond is what a batch write
   * produces, and `maxByOrNull` -- which answers the first maximal element -- would leave such a
   * listener pinned to part one for good.
   */
  private fun currentFileIndex(
    ordered: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): Int {
    var bestIndex = -1
    var best: MediaProgressEntity? = null
    ordered.forEachIndexed { index, file ->
      val row = progress[file.id]
      val incumbent = best
      if (row != null && (incumbent == null || row.lastPlayedAtEpochMs >= incumbent.lastPlayedAtEpochMs)) {
        best = row
        bestIndex = index
      }
    }
    return bestIndex
  }
}
