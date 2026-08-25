package app.muplay.database

import app.muplay.database.dao.MirrorReplacement

/**
 * The outcome of one [SyncEngine.syncIfStale] call — a sealed interface, not a boolean and a
 * nullable message, so every caller's `when` is exhaustive and a new outcome cannot be silently
 * ignored by a screen.
 */
sealed interface SyncState {

  /** The server has not rescanned since the last committed reconcile; the mirror is current. */
  data object UpToDate : SyncState

  /** The server is scanning. Nothing was fetched and nothing was stored; ask again later. */
  data object ScanInProgress : SyncState

  /** Every library was reconciled and the watermark committed. [libraries] is keyed by library id. */
  data class Synced(val libraries: Map<Int, MirrorReplacement>) : SyncState

  /**
   * The attempt failed. The watermark was **not** advanced, so the next attempt will try the
   * whole reconcile again rather than believing itself up to date.
   */
  data class Failed(val cause: Throwable) : SyncState
}
