package app.muplay.model

/**
 * One audiobook, as a browse surface needs to see it: what it is, how long it is, and how far
 * through it the listener got.
 *
 * **Provenance, because this matters to whoever reads it next.** Plan 4 Task 4 owns this type and
 * declares it at exactly this path with exactly these ten constructor properties and these three
 * *computed* ones. At the time Plan 5 Task 2 was implemented, Plan 4 had only a Task 1 brief
 * written and nothing of it had landed on `master` — `grep -rn BookSummary` over the whole
 * repository found nothing at all — and the browse tree cannot be written without it. It is
 * therefore declared here, once, to that plan's own declaration, rather than as a second summary
 * type under a different name that would later have to be unpicked.
 *
 * **Plan 4 Task 4 has landed and reconciled it by keeping this declaration unchanged.** That plan's
 * own text described `author` and `lastPlayedAtEpochMs` as nullable and `progressFraction` as a
 * `Float`; the shapes below are the ones every existing caller compiles against — `BrowseTree`
 * sorts a car's Continue shelf on `lastPlayedAtEpochMs` and cannot take a `Long?` into
 * `compareByDescending` — and `0`-means-never carries the same information the null did. Changing
 * three field types to match a document, across a live browse tree, would have been a rename
 * dressed up as a reconciliation. What Plan 4 Task 4 did instead was delete the *second derivation*
 * (`MirrorBookshelf`, `BookProgress`) and leave one, `app.muplay.database.BookSummaries`. That is
 * the duplication that was actually costing something.
 *
 * [durationMs] and [positionMs] are the *whole book*, not one file: a multi-file M4B is one
 * listening position to a user, and splitting it per file is what makes "resume" wrong after a
 * chapter boundary.
 *
 * [lastPlayedAtEpochMs] is what orders the Continue shelf. `0` means "never", which is consistent
 * with [hasStarted] being false at position zero.
 */
data class BookSummary(
  val bookId: String,
  val libraryId: Int,
  val title: String,
  val author: String,
  val coverArtId: String?,
  val fileCount: Int,
  val durationMs: Long,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
) {

  /**
   * How much is left to hear, clamped at zero.
   *
   * Clamped rather than signed because the two numbers it subtracts come from different places: a
   * container's declared duration and a player's reported position. Media3 reports positions past
   * a declared duration on streams whose duration was estimated, and every consumer of this value
   * renders it to a screen.
   */
  val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)

  /**
   * Progress as a fraction in `0.0..1.0`.
   *
   * A book whose duration is unknown (`0`, what an unscanned or unreadable container reports)
   * reports `0.0` rather than `NaN`: `NaN` survives `coerceIn` unchanged and would silently poison
   * every progress bar and completion status downstream of it.
   */
  val progressFraction: Double
    get() = if (durationMs <= 0L) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

  /**
   * Whether there is a position worth resuming from.
   *
   * `> 0`, not `>= 0`: this is what puts a book on the Continue shelf, and at `>= 0` every book
   * the user has never opened would be on it.
   */
  val hasStarted: Boolean get() = positionMs > 0L
}

/**
 * Where a book carries on: which **file**, and how far into it.
 *
 * Not a resume position. The smart rewind (Plan 4 Task 5) has not been applied and `ResumePolicy`
 * (Plan 4 Task 6) is the only thing allowed to decide the second playback starts at; this type
 * answers the question the policy is never asked, which is **which item**. `Bookshelf.resumeFileId`
 * is this type's [mediaId] and nothing else, and that narrowing is deliberate: a browse tree that
 * could see [positionMs] would eventually seek with it.
 *
 * [lastPlayedAtEpochMs] is a real timestamp here rather than the `0`-means-never that
 * [BookSummary] carries, because a `ResumePoint` only exists when a row exists.
 */
data class ResumePoint(
  val mediaId: String,
  val positionMs: Long,
  val lastPlayedAtEpochMs: Long,
)
