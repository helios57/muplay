package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.entity.MediaProgressEntity

/**
 * The one definition of "this book is finished", shared by the shelf ([BookSummaries]) and by the
 * playback side (`AudiobookSnapshot`), which have to agree or a finished book is resumed from a
 * place the shelf says it has already left.
 *
 * ### The rule, and the way it used to be wrong
 *
 * A book is finished when its **last file's** row says so *and no other file has been heard since*.
 * The first half is what `ProgressWriter` writes at `STATE_ENDED`; the second half is what makes
 * listening again possible. Before it, the flag lived on the last file's row for good, so a listener
 * who pressed Resume on a finished multi-file book restarted at part one, heard it for an hour, and
 * still found the book in the Finished group -- the flag on part three had nothing to tell it that
 * part one was newer.
 *
 * "Since" is compared on `lastPlayedAtEpochMs` with a **strict** `>`: `markFinished` stamps every
 * file with one `now`, and a tie must leave the book finished.
 */
object BookFinish {

  /**
   * [orderedIds] are the book's file ids in play order; [progress] is any map containing their
   * rows. An empty book is not finished.
   */
  fun isFinished(orderedIds: List<String>, progress: Map<String, MediaProgressEntity>): Boolean {
    val last = orderedIds.lastOrNull()?.let(progress::get) ?: return false
    if (!last.isFinished) return false
    return orderedIds.none { id ->
      val row = progress[id]
      row != null && row.lastPlayedAtEpochMs > last.lastPlayedAtEpochMs
    }
  }
}
