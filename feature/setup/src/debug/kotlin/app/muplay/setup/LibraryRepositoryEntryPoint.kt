package app.muplay.setup

import app.muplay.database.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A minimal, ad-hoc way into the real production Hilt graph for `app.muplay.FirstRunJourneyTest`
 * (`:app`'s instrumented tests), which needs to reset every library back to
 * [app.muplay.model.LibraryRole.UNASSIGNED] between test methods -- the Room database this app
 * writes to is a real file, shared by every test method within one instrumentation process,
 * unlike the `Activity` each test relaunches fresh.
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
 * Not `@HiltAndroidTest`/`hilt-android-testing`: that would need a custom test `Application`, a
 * custom `AndroidJUnitRunner`, and a new `androidTestImplementation` dependency, none of which
 * this project has any other use for. `EntryPointAccessors.fromApplication` is Hilt's own smaller,
 * supported way to read one real binding from outside the graph without replacing anything in it.
 *
 * **N-8 (review round 1, task-8-review.md) asked for the release half of this cost to go away**:
 * anything holding the application `Context` could pull [LibraryRepository] straight out of the
 * singleton graph, bypassing constructor injection -- the exact pattern that task's Hilt migration
 * existed to *remove* from `SetupViewModel`. The `src/debug/` move above answers it: there is no
 * such surface in a release build, and none in this module's published API in either build.
 *
 * What it does not answer is the other half of that note -- this still sits in the module that owns
 * the setup screen rather than the one that owns [LibraryRepository]. Left where it is, because the
 * move that actually retires it is a per-test app-data reset, which would take all four of this
 * project's test-facing entry points with it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryRepositoryEntryPoint {
  fun libraryRepository(): LibraryRepository
}
