package app.muplay.database

import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.Song

/** Where a listener is in a whole book, as the facts `BookSummary` cannot derive. */
data class BookPosition(
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
  /**
   * The id of the **file** the most recent row was written against, or `null` for a book nothing
   * has been written for.
   *
   * Added by Plan 5 Task 5, which needs the file and not the offset: tapping a book means *"carry
   * on"*, and carrying on means starting the queue at the file the listener was in. It is derived
   * here rather than beside [of] because "which row is the listener's" is one rule -- the shelf's
   * percentage and the queue's start index are two readings of the same answer, and deriving it
   * twice is how they come to disagree about a book with rows on two files.
   *
   * **It is a file, not a position.** Nothing here decides where to seek; see this object's own
   * note, which Task 5 did not weaken.
   */
  val fileMediaId: String?,
)

/**
 * One book's position, from its files' `media_progress` rows.
 *
 * ### Provenance -- read this before extending it
 *
 * **Plan 4 Task 4's `AudiobookRepository` owns this question**, and at the time Plan 5 Task 4 was
 * implemented nothing of Plan 4 had landed: `grep -rn AudiobookRepository` over the whole
 * repository found nothing, and neither did `bookshelf` or `ResumePoint`. The browse tree cannot be
 * written without a bookshelf, so this is the smallest derivation that answers the three fields
 * [app.muplay.model.BookSummary] needs and no more -- exactly the precedent Plan 5 Task 2 set when
 * it declared `BookSummary` itself. When Plan 4 Task 4 lands, this file, [Bookshelf] and
 * [MirrorBookshelf] are its to delete or to reconcile.
 *
 * **It is not resume arithmetic and must not become it.** Nothing here decides where to *seek*;
 * Plan 5's own seam note is explicit that the caller picks the index and Plan 4's policy picks the
 * position. What this produces is what a car draws a progress pip from.
 *
 * ### The rule
 *
 * The listener is wherever their **most recently written** file row says they are, expressed over
 * the whole book by adding the durations of the files before it. A book is finished only when that
 * row is the **last** file's and is itself finished -- finishing chapter two of five is not
 * finishing the book, and a rule that read `row.isFinished` alone would take a part-heard book off
 * the Continue shelf the first time somebody let a chapter run out.
 */
object BookProgress {

  /** A book nothing has been written for: never started, never finished, never played. */
  val NONE: BookPosition =
    BookPosition(positionMs = 0L, isFinished = false, lastPlayedAtEpochMs = 0L, fileMediaId = null)

  fun of(files: List<Song>, rows: Map<String, MediaProgressEntity>): BookPosition {
    var latest: MediaProgressEntity? = null
    var latestIndex = -1
    files.forEachIndexed { index, song ->
      val row = rows[song.id]
      val incumbent = latest
      // `>=`, not `>`, and the tie goes to the **later file**: two rows written in the same
      // millisecond is reachable (a batch write does exactly that), and without a deterministic
      // tiebreak the same shelf would order itself differently between two identical requests --
      // the same defect `BrowseTree.continueNodes` breaks its own ties for.
      if (row != null && (incumbent == null || row.lastPlayedAtEpochMs >= incumbent.lastPlayedAtEpochMs)) {
        latest = row
        latestIndex = index
      }
    }

    val row = latest ?: return NONE
    val offsetMs = files.take(latestIndex).sumOf { it.durationSeconds * 1_000L }
    return BookPosition(
      positionMs = offsetMs + row.positionMs,
      isFinished = row.isFinished && latestIndex == files.lastIndex,
      lastPlayedAtEpochMs = row.lastPlayedAtEpochMs,
      fileMediaId = files[latestIndex].id,
    )
  }
}
