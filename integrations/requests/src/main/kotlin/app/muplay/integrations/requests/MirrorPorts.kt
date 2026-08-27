package app.muplay.integrations.requests

import app.muplay.database.SyncState
import app.muplay.integrations.IntegrationCredentials
import app.muplay.integrations.IntegrationService
import app.muplay.model.LibraryRole
import app.muplay.model.SearchResults
import kotlinx.coroutines.flow.Flow

/**
 * The four things this module needs from code it does not own, as four single-method interfaces.
 *
 * Declared here rather than consumed directly for one reason, the same one `SubsonicSource` gives:
 * **a test needs a specific call to fail at a specific point** — [MirrorSync.syncIfStale] returning
 * [SyncState.ScanInProgress] must stop [RequestArrivalDetector] before it searches — and
 * `SyncEngine` is a concrete class with five constructor dependencies including a
 * `SubsonicSourceProvider`. There is no mock framework in this build and there will not be one.
 *
 * The first three are **read-only**. Nothing in this plan writes the mirror, moves the sync
 * watermark or changes a library role, and these ports are what makes that structural rather than
 * a promise.
 *
 * [ConfiguredServices] has a second reason on top of that one: `IntegrationCredentialStore` reaches
 * `AndroidKeystore`, so it is device-only. Behind this port, the four-configuration-combination
 * test the plan's severability contract demands is a **JVM** test rather than an emulator one —
 * the difference between a rule that runs on every push and one that runs only in Tier 2.
 */
fun interface MirrorSync {
  /** Plan 2's `SyncEngine.syncIfStale`. Never called for a request that has nothing to find. */
  suspend fun syncIfStale(): SyncState
}

fun interface AlbumSearch {
  /** Plan 2's `BrowseRepository.search`, scoped to one library. */
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults
}

fun interface LibraryRoles {
  /** Plan 2's `LibraryRepository.idsWithRole`. */
  suspend fun idsWithRole(role: LibraryRole): List<Int>
}

/**
 * Every configured service and its credentials — `IntegrationCredentialStore.configured`.
 *
 * **A method, not the `val configured: Flow<…>` property the plan's Step 5 wrote.** Kotlin refuses
 * an abstract property in a `fun interface` outright (*"Fun interfaces cannot have abstract
 * properties"*), so the plan's declaration does not compile; a method keeps the SAM conversion the
 * production binding is written as, `ConfiguredServices { store.configured }`.
 *
 * The credentials, not just the service names, because this is also where [RequestsRepository]
 * gets what it needs to build each service's client. One read of the store answers both "is this
 * configured" and "what do I talk to it with", so the two cannot disagree — the same argument
 * `MediaRequestRepository` makes for having one field-mapping site rather than two.
 */
fun interface ConfiguredServices {
  fun configured(): Flow<Map<IntegrationService, IntegrationCredentials>>
}
