package app.muplay.database

import app.muplay.database.dao.MediaProgressDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the real production Hilt graph for `app.muplay.CarResumeJourneyTest` (`:app`'s
 * instrumented tests), which has to **establish** a listening position before it taps a row and
 * then read back the row it was stored under.
 *
 * It has to be the singleton DAO over the app's own database file, not a second Room instance: the
 * subject is what the running service's browse tree and resume policy see, and a second database
 * would let the test assert against a book nothing in the app has heard of.
 *
 * ### Why `src/debug/`, and why it is not `src/main/`
 *
 * Hilt aggregates `@InstallIn` from a variant's **main compilation**, so an `@EntryPoint` declared
 * in `:app`'s `androidTest` source set is not part of the running `MuPlayApplication`'s generated
 * `SingletonComponent` at all -- confirmed directly once, as a
 * `ClassCastException: Cannot cast ...SingletonCImpl to ...EntryPoint`. A **build-type source set
 * is part of that same compilation**, and the instrumented tests run the debug variant. So this
 * file is compiled into debug, is absent from release, and is not public API of this module in
 * either build -- the same placement, for the same reasons, as [CredentialStoreEntryPoint] and
 * [SyncWatermarkEntryPoint] beside it. `ConventionTest`'s `every Hilt entry point is declared in a
 * debug source set` is what keeps it there.
 *
 * ### The surface this opens, stated plainly
 *
 * Anything in a **debug** build holding the application `Context` can read and write listening
 * positions. `media_progress` holds no credential; what it holds is what the user listened to and
 * how far they got, which is exactly the data spec section 4 says never leaves the device. This
 * adds no path off the device -- in-process code could already open the same Room file -- and it
 * is absent from release. Delete it the day a per-test app-data reset exists, which would take all
 * of this project's test-facing entry points with it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaProgressEntryPoint {
  fun mediaProgressDao(): MediaProgressDao
}
