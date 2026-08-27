package app.muplay.media

import app.muplay.model.Chapter

/**
 * One chapter entry exactly as it came out of Media3, before anything has been decided about it.
 *
 * [endMs] is nullable because `androidx.media3.extractor.metadata.Chapter.getEndTimeMs()` returns
 * `C.TIME_UNSET` when the retriever was wired without an explicit `MediaSourceFactory` -- spike
 * S3's central finding. Mapping that sentinel to `null` at the boundary is what keeps every type
 * below this one free of it.
 */
internal data class RawChapter(val startMs: Long, val endMs: Long?, val title: String?)

/**
 * Turns whatever Media3 handed back into an ordered, gap-free, de-duplicated chapter list.
 *
 * Android-free on purpose: this is the part most likely to be subtly wrong, so it is gated in
 * Tier 1 where a mutation costs seconds rather than an emulator boot. Same split as
 * `StreamRetryPolicy` and `PlaybackAudioAttributes`.
 *
 * `internal`, and not by preference: [RawChapter] is `internal` because `C.TIME_UNSET` must not
 * escape this module, and a `public` function taking a `List<RawChapter>` does not compile --
 * *"'public' function exposes its 'internal' parameter type argument 'RawChapter'"*. The plan's
 * listing declared it `public`; the visibility that follows from the type is this one. Kotlin's
 * test source sets are friends of the module they test, so `ChapterAssemblyTest` sees both.
 */
internal object ChapterAssembly {

  fun assemble(raw: List<RawChapter>, contentDurationMs: Long): List<Chapter> {
    // De-duplicate by start time, preferring the entry that actually carries a title: Media3
    // surfaces metadata per track format, so a file with more than one track can present the same
    // chapter list twice, once titled and once not. Left alone that doubles every book.
    val byStart = LinkedHashMap<Long, RawChapter>()
    for (entry in raw) {
      // A negative start is not a position anything can seek to. Dropped rather than clamped to
      // zero, which would put a second chapter 1 in front of the real one.
      if (entry.startMs < 0L) continue
      val existing = byStart[entry.startMs]
      val keep = when {
        existing == null -> entry
        existing.normalisedTitle == null && entry.normalisedTitle != null -> entry
        // The complementary case, and the one a real two-track M4B presents: the titled twin has
        // no end, the untitled one does. Take the end without losing the title.
        existing.endMs == null && entry.endMs != null -> existing.copy(endMs = entry.endMs)
        else -> existing
      }
      byStart[entry.startMs] = keep
    }

    val ordered = byStart.values.sortedBy { it.startMs }

    return ordered.mapIndexed { index, entry ->
      // The end is what Media3 said, or the next chapter's start, or -- for the last chapter --
      // the duration the caller already knows from `Song.durationSeconds`. Spike S3 *inferred*
      // that Media3 fills the last end from the content duration but could not confirm it; this
      // code does not rely on the inference either way.
      val fallback = ordered.getOrNull(index + 1)?.startMs ?: contentDurationMs
      val end = (entry.endMs ?: fallback).coerceAtLeast(entry.startMs)
      Chapter(
        index = index,
        startMs = entry.startMs,
        endMs = end,
        title = entry.normalisedTitle,
      )
    }
  }

  /** Blank and absent are one fact, not two. */
  private val RawChapter.normalisedTitle: String?
    get() = title?.trim()?.takeIf { it.isNotEmpty() }
}
