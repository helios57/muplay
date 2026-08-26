package app.muplay.model

/**
 * One chapter, as read from an audio file's own bytes.
 *
 * There is no server-side source for this. Navidrome exposes no chapter API and OpenSubsonic has
 * no chapter schema, so every value here comes from a Nero `chpl` or QuickTime `chap` atom that
 * Media3's `MetadataRetriever` read out of the stream (spec section 5).
 *
 * [startMs] and [endMs] are **relative to the file**, not to the book. A multi-file book's chapter
 * 7 starts at 0 in its own file; turning that into a position within the whole book is
 * `BookTimeline`'s job (Task 3) and deliberately not this type's.
 *
 * [title] is nullable because a chapter atom genuinely may carry no title -- spike S3 observed a
 * trailing, empty-titled chapter on a `chap` fixture. Blank is normalised to `null` at the reader
 * so "untitled" is one value rather than two.
 */
data class Chapter(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  val title: String?,
) {

  /**
   * How long this chapter runs for, clamped at zero.
   *
   * Clamped rather than signed for the same reason [BookSummary.remainingMs] is: the two numbers
   * come from a container's own atoms, which a bad tagger can write out of order, and every
   * consumer renders this to a screen.
   */
  val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

  /**
   * Half-open: `[startMs, endMs)`, so a position exactly on a boundary belongs to the **later**
   * chapter.
   *
   * Closed at both ends -- `positionMs <= endMs` -- would make the instant a chapter ends belong
   * to two chapters at once, and "which chapter am I in" would answer differently depending on
   * which end of the list the caller searched from.
   */
  fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}
