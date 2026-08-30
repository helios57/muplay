package app.muplay.watchlink

import kotlinx.coroutines.flow.Flow

/**
 * The wire between a phone and its watch.
 *
 * Two methods, no decisions. Everything that decides anything -- what crosses, when, and who wins --
 * is in [WatchSyncEngine] and [ProgressMerge], both of which are gated on the JVM tier. This
 * interface exists so that the ~60 lines which genuinely need a paired phone and watch are ~60 lines
 * and not a module.
 *
 * Keep it that way. A `suspend fun publishIfChanged`, a `lastPublishedAt`, or a
 * `Flow<WatchSyncPayload>` that filtered by version would each be a decision moved from a place
 * every test can reach into the one place no test can.
 */
interface WatchLink {

  /** Makes [payload] this device's current published state. Replaces whatever it published before. */
  suspend fun publish(payload: WatchSyncPayload)

  /** What the peer has published, now and whenever it changes. */
  fun incoming(): Flow<WatchSyncPayload>
}
