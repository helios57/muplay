package io.github.helios57.muplay.database

/**
 * How far a running [SyncEngine.syncIfStale] has got, so a first run can show something that moves.
 *
 * **Why this type exists.** The first sync after setup is the longest thing this app ever does and
 * the only one a user waits on with nothing else to look at: [SyncEngine] lists a library's albums
 * a page at a time and then makes **one `getAlbum` request per album**, so a library of a few
 * hundred albums is a few hundred sequential round trips. The browse screen showed one motionless
 * sentence for all of it, which is indistinguishable from an app that has hung — and a user who
 * force-stops the app during the first sync gets no mirror at all, because the watermark is
 * committed last.
 *
 * **Every member is what is honestly known at that moment, and nothing more.** A sealed interface
 * rather than a nullable `Int` pair, so the screen's `when` is exhaustive and "we do not know the
 * total yet" is a state rather than a sentinel somebody has to remember to check. The states are
 * ordered by how much is known:
 *
 * | State        | Known                          | Bar           |
 * |--------------|--------------------------------|---------------|
 * | [Idle]       | nothing is running             | none          |
 * | [Preparing]  | a sync started                 | indeterminate |
 * | [Listing]    | how many albums so far         | indeterminate |
 * | [Reading]    | how many of how many           | determinate   |
 *
 * The counters are **per library**, not per sync, because that is the only thing that can be
 * stated without lying: [SyncEngine] reconciles one library at a time and learns each library's
 * album count only when it has finished listing that library, so a total spanning the whole sync
 * would be unknown for exactly as long as it mattered. With two libraries the count therefore
 * restarts once. Each value is true when it is shown, which is the property this screen's wordings
 * are already held to; a smooth-looking number that was extrapolated would not be.
 */
sealed interface SyncProgress {

  /** No sync is running. */
  data object Idle : SyncProgress

  /**
   * A sync has started and nothing countable has come back yet — the scan-status poll and the
   * music-folder list, which is two round trips before the first album is known.
   */
  data object Preparing : SyncProgress

  /**
   * Albums are being listed. [found] is how many have arrived so far; the total is **not** known,
   * because the paging loop learns it is on the last page only by receiving a short one.
   */
  data class Listing(val found: Int) : SyncProgress

  /**
   * Album details are being read, one request per album. [done] of [total] have come back.
   *
   * This is the state a sync spends nearly all of its time in, and the only one that can offer a
   * real fraction.
   */
  data class Reading(val done: Int, val total: Int) : SyncProgress
}

/**
 * [this] as a fraction in `0f..1f`, or `null` when the work genuinely cannot be counted yet.
 *
 * `null` means *indeterminate*, and every caller must render it as such rather than substituting a
 * number. Note the zero-total case, which is reachable: a library the server reports with no albums
 * at all is a state [SyncEngine] deliberately mirrors rather than skips, and `0f / 0` is `NaN` —
 * which `LinearProgressIndicator` draws as an empty bar that never moves, i.e. exactly like a
 * sync that has hung.
 */
val SyncProgress.fraction: Float?
  get() = when (this) {
    SyncProgress.Idle, SyncProgress.Preparing -> null
    is SyncProgress.Listing -> null
    is SyncProgress.Reading -> if (total > 0) done.toFloat() / total else null
  }
