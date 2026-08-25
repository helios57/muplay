package app.muplay.database

import app.muplay.model.ScanStatus

/**
 * What to do about the server's current scan state, given the watermark last committed.
 *
 * A sealed interface so the engine's `when` is exhaustive: adding a fourth outcome later cannot
 * be silently ignored by the one place that acts on it. Pure and free of collaborators, so the
 * rule that decides whether a sync happens at all is unit-tested on the JVM rather than only
 * observable through a database.
 */
sealed interface SyncDecision {

  /** The server has not rescanned since the last committed reconcile. */
  data object UpToDate : SyncDecision

  /** The server is scanning right now. Do nothing, and do not store anything; ask again later. */
  data object ScanInProgress : SyncDecision

  /**
   * Reconcile every library, then store [watermark] — **after** the last transaction commits.
   *
   * [watermark] is null when the server reports no `lastScan` at all (a plain Subsonic server, or
   * a future Navidrome that drops the extension). Storing nothing in that case is deliberate: a
   * stored value would later compare equal to a server that keeps not sending one and freeze the
   * mirror forever. Reconciling every time is wasteful; a mirror that never updates is wrong.
   */
  data class Reconcile(val watermark: String?) : SyncDecision

  companion object {
    fun decide(stored: String?, status: ScanStatus): SyncDecision = when {
      status.isScanning -> ScanInProgress
      status.lastScan == null -> Reconcile(null)
      status.lastScan == stored -> UpToDate
      else -> Reconcile(status.lastScan)
    }
  }
}
