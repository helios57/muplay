package app.muplay.media

import java.time.Clock

/**
 * The policy that replaces [NeverResume], and the reason this project exists.
 *
 * Spec section 1: *"Every book remembers its own exact position and keeps it across an intervening
 * music session."* The seam Plan 3 built (`MuPlayer`, all six `setMediaItem(s)` overloads) makes
 * this the **only** thing in the application permitted to choose a playback position; this class is
 * what makes that permission mean something.
 *
 * **The index is not overridden.** Plan 3 anticipated that it might be -- *"how the audiobook plan
 * resumes a book at chapter 14"* -- but [resolve]`(mediaIds, requestedIndex)` cannot distinguish
 * *"play this book"* from *"play chapter 1 from the top"*, since both arrive as index 0. Guessing
 * would make tapping chapter 1 jump to chapter 14: a worse bug than the one being fixed, and
 * unfixable from inside this signature. So the **caller** chooses the item, because the caller is
 * the only party that knows the intent, and this chooses the position. `ResumptionQueue` is one
 * such caller; the bookshelf UI is the other.
 *
 * Never blocks and never throws, both of which are [ResumePolicy]'s stated contract rather than
 * this class's preference: every answer comes from [source]'s in-memory map, and every index is
 * coerced before it is used. See [AudiobookSnapshot] for the two ways the map goes wrong and what
 * stops them.
 */
class AudiobookResumePolicy(
  private val source: AudiobookItemSource,
  private val clock: Clock,
) : ResumePolicy {

  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
    // A stale index from another process -- a car, a watch, a headset -- must not throw out of
    // `setMediaItems` and take the session down with it. `ResumePolicyTest` pins that an empty
    // queue is answered rather than thrown on; this is the same promise for a bad index.
    val index = requestedIndex.coerceIn(0, (mediaIds.size - 1).coerceAtLeast(0))
    val mediaId = mediaIds.getOrNull(index) ?: return ResumeTarget(index, 0L)

    // `null` is "not an audiobook". Music restarts from zero because there is nothing here to
    // resume from, not because a branch says so.
    val item = source.itemFor(mediaId) ?: return ResumeTarget(index, 0L)

    // A finished item starts again. Otherwise pressing play on a book you finished drops you two
    // seconds before the end, which reads as "the resume is broken" rather than as "you finished
    // this".
    if (item.isFinished) return ResumeTarget(index, 0L)

    // Routed through `SmartRewind` rather than subtracting something of its own: the table -- and
    // in particular what it does with a *negative* away time, which a clock that moved backwards
    // really produces -- is one decision, argued in one place, and gated by `SmartRewindTest` on
    // the fast tier.
    val awayMs = clock.millis() - item.lastPlayedAtEpochMs
    return ResumeTarget(index, SmartRewind.resumePositionMs(item.positionMs, awayMs))
  }
}
