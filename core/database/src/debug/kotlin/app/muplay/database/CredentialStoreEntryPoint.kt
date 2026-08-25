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
 * ### Why `src/debug/`, and why it is not `src/main/`
 *
 * Hilt aggregates `@InstallIn` from a variant's **main compilation**, so an `@EntryPoint` declared
 * in `:app`'s `androidTest` source set is not part of the running `MuPlayApplication`'s generated
 * `SingletonComponent` at all -- confirmed directly once, as a
 * `ClassCastException: Cannot cast ...SingletonCImpl to ...EntryPoint`. That is true, and it was
 * read as "therefore it must be in `src/main/`", which does not follow: a **build-type source set
 * is part of that same compilation**. `app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt`
 * and its `src/release/` twin are this repository's own proof, and the instrumented tests run the
 * debug variant.
 *
 * So this file is compiled into debug, is absent from release, and -- the part that actually
 * matters -- is not public API of its module. In `src/main/` any module depending on this one could
 * pull the binding below out of the graph instead of injecting it, which is the pattern
 * constructor injection exists to remove. The confidentiality difference is close to zero either
 * way (in-process code can already reach what this exposes); the architectural difference is not.
 *
 * `ConventionTest`'s `every Hilt entry point is declared in a debug source set` is what keeps this
 * from drifting back.
 *
 * ### The surface this opens, stated plainly
 *
 * Anything in a **debug** build holding the application `Context` can reach the credential store.
 * That is a smaller change than it first reads as -- in-process code could already open the same
 * DataStore file, or re-open `KeyStore` alias `app.muplay.credentials` directly, and
 * `SubsonicCredentials.toString()` is already redacted so a leak through a log line is not the
 * failure mode -- but it is not nothing, and it exists for a test. Since the `src/debug/` move it
 * is at least absent from release and from this module's API in either build. Delete it the day a
 * per-test app-data reset exists, which would let all four of this project's test-facing entry
 * points go with it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CredentialStoreEntryPoint {
  fun credentialStore(): CredentialStore
}
