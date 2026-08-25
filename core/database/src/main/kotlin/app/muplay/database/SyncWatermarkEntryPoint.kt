package app.muplay.database

import app.muplay.database.dao.SyncWatermarkDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the real production Hilt graph for `app.muplay.BrowseJourneyTest`
 * (`:app`'s instrumented tests), which needs to make the mirror **provably stale** before it
 * presses the screen's Refresh action.
 *
 * ### Why a journey needs this at all
 *
 * `LibraryViewModel.refresh` sets a *"Checking the server for changes…"* message, awaits
 * `SyncEngine.syncIfStale()`, and then replaces the message with the outcome (`null` when the
 * mirror was already up to date). The obvious black-box assertion — watch that message appear —
 * **does not work, and this is a measurement rather than a guess**: against an already-synced
 * mirror the whole call is one `getScanStatus` round trip over the loopback, and the message is
 * conflated away before a frame is ever drawn (`MutableStateFlow` → `combine` → `stateIn` →
 * `collectAsStateWithLifecycle` are each conflated, and Compose recomposes per frame). Observed
 * directly on `muplay37`: a `waitUntil` for that string, polling continuously, never saw it in
 * 30 seconds although the refresh had run.
 *
 * The alternative the brief proposed — waiting for the message to *clear* — is worse than not
 * asserting at all: with `refresh()` mutated to an empty body the message is never set, so the
 * wait succeeds on its first poll and the gate reports safety.
 *
 * Clearing the watermark is what makes the next `syncIfStale()` a real reconcile
 * (`SyncDecision.decide(null, …)` → `Reconcile`), whose stored watermark is a durable,
 * race-free observable: no Refresh, no watermark.
 *
 * Declared in this module's `main` source set rather than in `:app`'s `androidTest` source set
 * because Hilt aggregates `@InstallIn` entry points from a variant's *main* compilation only — an
 * `@EntryPoint` declared in `androidTest` is not part of the running `MuPlayApplication`'s
 * generated `SingletonComponent` at all. See `LibraryRepositoryEntryPoint` (`:feature:setup`) for
 * the same reasoning and for the review note asking that such entry points live **here**, beside
 * the data layer they expose, rather than in a feature module.
 *
 * It is the same real cost that one carries: anything holding the application `Context` can now
 * pull [SyncWatermarkDao] out of the singleton graph. Read-and-clear on a cache watermark is the
 * narrowest surface that makes the Refresh gate non-vacuous; delete it the day a per-test app-data
 * reset exists.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWatermarkEntryPoint {
  fun syncWatermarkDao(): SyncWatermarkDao
}
