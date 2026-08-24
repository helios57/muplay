package app.muplay.setup

import app.muplay.database.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A minimal, ad-hoc way into the real production Hilt graph for `app.muplay.FirstRunJourneyTest`
 * (`:app`'s instrumented tests), which needs to reset every library back to
 * [app.muplay.model.LibraryRole.UNASSIGNED] between test methods — the Room database this app
 * writes to is a real file, shared by every test method within one instrumentation process,
 * unlike the `Activity` each test relaunches fresh.
 *
 * Declared here, in this module's `main` source set, rather than in `:app`'s `androidTest` source
 * set where it is actually used: Hilt aggregates `@InstallIn` entry points from the classes that
 * contribute to a variant's *main* compilation, not from a separate `androidTest` compilation unit
 * — an `@EntryPoint` declared only in `androidTest` is invisible to the real, already-running
 * `MuPlayApplication`'s generated `SingletonComponent` (confirmed directly: it threw
 * `ClassCastException: Cannot cast ...SingletonCImpl to LibraryRepositoryEntryPoint` when tried).
 * `:feature:setup` already depends on `:core:database` for [LibraryRepository] itself, and,
 * unlike `:core:database`, is a module this task owns.
 *
 * Not `@HiltAndroidTest`/`hilt-android-testing`: that would need a custom test `Application`, a
 * custom `AndroidJUnitRunner`, and a new `androidTestImplementation` dependency, none of which
 * this project has any other use for. `EntryPointAccessors.fromApplication` is Hilt's own smaller,
 * supported way to read one real binding from outside the graph without replacing anything in it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryRepositoryEntryPoint {
  fun libraryRepository(): LibraryRepository
}
