package app.muplay.media

/**
 * One audiobook file, and everything the player needs to know about it, in memory.
 *
 * `speed` and `skipSilence` come from `book_settings` -- the **book's** grain -- and are carried on
 * the item because the player only ever knows a media id. `media_progress.speed` is deliberately
 * not consulted; see `BookSettings`'s own documentation for why that column is the wrong grain, and
 * `BookSpeedAuthorityTest` for the gate that keeps it out of the applier.
 *
 * ### This file is a placeholder for Plan 4 Task 6's, and the merge is deliberate
 *
 * Task 6 creates `AudiobookSnapshot.kt` at this exact path holding these two declarations **and**
 * the `AudiobookSnapshot` that keeps a live map of them. Task 7 (this lane) consumes both types and
 * ran concurrently with Task 6, so it declares them here rather than inventing a second name for
 * one idea -- a second name would merge *cleanly* into two types meaning the same thing, which is
 * the silent direction. The same path makes git report an add/add conflict instead, and the
 * resolution is *take Task 6's file whole*: it is a strict superset of this one.
 */
data class AudiobookItem(
  val mediaId: String,
  val bookId: String,
  val positionMs: Long,
  val lastPlayedAtEpochMs: Long,
  val isFinished: Boolean,
  val speed: Float,
  val skipSilence: Boolean,
)

/**
 * The one question the resume policy and the speed controller ask.
 *
 * A `fun interface` rather than `AudiobookSnapshot` itself, so both of those are pure enough to be
 * gated in Tier 1 with a `Map` standing in -- which is a complete implementation of a one-method
 * interface, not a stand-in for one.
 */
fun interface AudiobookItemSource {
  /** `null` means "not an audiobook", which is how music restarts from zero structurally. */
  fun itemFor(mediaId: String): AudiobookItem?
}
