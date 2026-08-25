package app.muplay.database

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A way into the real production Hilt graph for `app.muplay.MuPlaybackServiceTest` (`:app`'s
 * instrumented tests), which has to establish the credentials its own queue is built from rather
 * than inherit whatever an earlier journey happened to leave behind -- a test that depends on
 * another test having run is a test that fails alone.
 *
 * It has to be the **singleton** store, not a second one built over the same file: `CredentialStore`
 * is backed by DataStore, and constructing a second `DataStore` over a file that already has a live
 * instance in the same process throws *"There are multiple DataStores active for the same file"*.
 *
 * Declared here rather than in `:app`'s `androidTest` source set for the reason
 * [SyncWatermarkEntryPoint] records: Hilt aggregates `@InstallIn` from a variant's main compilation
 * only.
 *
 * ### The surface this opens, stated plainly
 *
 * Anything in this process holding the application `Context` can now reach the credential store.
 * That is a smaller change than it first reads as -- in-process code could already open the same
 * DataStore file, and `SubsonicCredentials.toString()` is already redacted so a leak through a log
 * line is not the failure mode -- but it is not nothing, and it exists for a test. Delete it the
 * day a per-test app-data reset exists, which would let all three of this project's test-facing
 * entry points go with it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CredentialStoreEntryPoint {
  fun credentialStore(): CredentialStore
}
