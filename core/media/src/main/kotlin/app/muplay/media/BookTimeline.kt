package app.muplay.media

import app.muplay.model.Chapter
import java.util.Locale

/**
 * One playable file of a book, reduced to the three things a timeline needs.
 *
 * Not `Song`: [BookTimeline] is a pure function gated in Tier 1, and a thirteen-field model would
 * make every test in `BookTimelineTest` thirteen lines longer without adding a single assertion.
 * The repository maps `Song` to this at the boundary.
 */
data class BookFile(val mediaId: String, val title: String, val durationMs: Long)

/**
 * One chapter of a book, located in the **queue** as well as in the book.
 *
 * [itemIndex] and [startInItemMs] are what a seek needs (`seekTo(itemIndex, startInItemMs)`);
 * [bookStartMs] is what a whole-book progress bar needs. They coincide for a single-file book,
 * which is exactly why a single-file book cannot prove either of them is computed.
 */
data class BookChapter(
  val index: Int,
  val title: String,
  val mediaId: String,
  val itemIndex: Int,
  val startInItemMs: Long,
  val endInItemMs: Long,
  val bookStartMs: Long,
) {
  /**
   * How long this chapter runs for, clamped at zero.
   *
   * Clamped for the same reason `Chapter.durationMs` is: the two numbers come from a container's
   * own atoms, which a bad tagger can write out of order, and this one reaches a progress bar.
   */
  val durationMs: Long get() = (endInItemMs - startInItemMs).coerceAtLeast(0L)
}

/**
 * The two shapes an audiobook comes in, unified into one navigable list.
 *
 * A single-file M4B carries its chapters as atoms inside one media item. A ripped book is one media
 * item per chapter with no atoms at all. A book can also be both -- a two-disc rip where each disc
 * is a chaptered M4B -- and that third shape is what stops "one chapter per file" and "book
 * position equals in-item position" from both looking correct.
 *
 * A file with no chapters contributes exactly one chapter named after the file. Fabricating
 * chapters for a file that has none would put a chapter title on every music track that ever
 * passed through; contributing none would make a ripped book unnavigable.
 */
object BookTimeline {

  /** How far into a chapter "previous" stops meaning "restart this one". */
  const val RESTART_THRESHOLD_MS = 3_000L

  /**
   * What an untitled chapter is called. Numbered by position in the **book**, so chapter 1 of
   * disc two is not "Chapter 1".
   *
   * Formatted with [Locale.ROOT] rather than the default locale: `%d` under an Arabic or Devanagari
   * locale renders digits a `containsExactly("Chapter 1")` would not match, and more to the point
   * that string is persisted nowhere and compared everywhere.
   */
  const val UNTITLED_FORMAT = "Chapter %d"

  fun of(files: List<BookFile>, chaptersByMediaId: Map<String, List<Chapter>>): List<BookChapter> {
    val result = mutableListOf<BookChapter>()
    var bookOffset = 0L
    files.forEachIndexed { itemIndex, file ->
      val chapters = chaptersByMediaId[file.mediaId].orEmpty()
      if (chapters.isEmpty()) {
        result += BookChapter(
          index = result.size,
          title = file.title,
          mediaId = file.mediaId,
          itemIndex = itemIndex,
          startInItemMs = 0L,
          endInItemMs = file.durationMs,
          bookStartMs = bookOffset,
        )
      } else {
        // Sorted here as well as in `ChapterAssembly`: this map also arrives out of Room and out
        // of a caller's own hands, neither of which is `ChapterAssembly`'s output by construction.
        for (chapter in chapters.sortedBy { it.startMs }) {
          result += BookChapter(
            index = result.size,
            title = chapter.title ?: String.format(Locale.ROOT, UNTITLED_FORMAT, result.size + 1),
            mediaId = file.mediaId,
            itemIndex = itemIndex,
            startInItemMs = chapter.startMs,
            endInItemMs = chapter.endMs,
            bookStartMs = bookOffset + chapter.startMs,
          )
        }
      }
      bookOffset += file.durationMs
    }
    return result
  }

  fun chapterAt(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): BookChapter? {
    val inItem = timeline.filter { it.mediaId == mediaId }
    if (inItem.isEmpty()) return null
    // Half-open `[start, end)`: a position exactly on a boundary belongs to the chapter that
    // starts there. `lastOrNull` rather than `firstOrNull` so a position past the final chapter's
    // end (encoder padding routinely puts it there) still answers the final chapter rather than
    // null.
    return inItem.lastOrNull { positionInItemMs >= it.startInItemMs } ?: inItem.first()
  }

  fun next(timeline: List<BookChapter>, current: BookChapter?): BookChapter? =
    when (current) {
      null -> timeline.firstOrNull()
      else -> timeline.getOrNull(current.index + 1)
    }

  fun previous(
    timeline: List<BookChapter>,
    current: BookChapter?,
    positionInItemMs: Long,
    restartThresholdMs: Long = RESTART_THRESHOLD_MS,
  ): BookChapter? {
    if (current == null) return timeline.firstOrNull()
    val intoChapter = positionInItemMs - current.startInItemMs
    // Deep inside the chapter, "previous" restarts it -- which is what a listener who overshot
    // means. Only near its start does it mean the chapter before.
    if (intoChapter >= restartThresholdMs) return current
    return timeline.getOrNull(current.index - 1) ?: current
  }

  fun bookPositionMs(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): Long {
    val itemStart = timeline.firstOrNull { it.mediaId == mediaId } ?: return positionInItemMs
    return itemStart.bookStartMs - itemStart.startInItemMs + positionInItemMs
  }
}
