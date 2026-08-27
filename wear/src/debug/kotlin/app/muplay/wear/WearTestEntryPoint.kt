package app.muplay.wear

import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the watch's real production Hilt graph for `WearSessionJourneyTest`.
 *
 * ### Why this is a file and not an interface nested in the test class
 *
 * The plan for this task declared it inside `WearSessionJourneyTest` itself. **That cannot work**,
 * and the failure is not a compile error: Hilt aggregates `@InstallIn` from a variant's **main
 * compilation**, and an `androidTest` source set is not part of it, so the generated
 * `SingletonComponent` never implements the interface and `EntryPointAccessors.fromApplication`
 * throws `ClassCastException: Cannot cast ...SingletonCImpl to ...WearTestEntryPoint` at run time.
 * `:core:database`'s own entry points record that measurement; this file is the same lesson applied
 * before it cost anything.
 *
 * A **build-type** source set *is* part of that compilation, and the instrumented tests run the
 * debug variant -- which is also why `ConventionTest`'s
 * `every Hilt entry point is declared in a debug source set` bans `src/main/` rather than requiring
 * `src/debug/`: this is compiled into debug, absent from release, and not public API of this module
 * in either build.
 *
 * ### Why only these three
 *
 * [CredentialStore][app.muplay.database.CredentialStore] is deliberately **not** here:
 * `:core:database`'s own `CredentialStoreEntryPoint` already exposes the singleton store, it is on
 * this module's debug classpath through the `:core:database` dependency, and a second entry point
 * for the same binding would be a second name for one thing. [LibraryRepository] and [SyncEngine]
 * have no such entry point reachable from here -- the existing one lives in `:feature:setup`, which
 * `:wear` does not and should not depend on -- and [WearBrowser] is this module's own.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WearTestEntryPoint {
  fun libraryRepository(): LibraryRepository
  fun syncEngine(): SyncEngine
  fun browser(): WearBrowser
}
