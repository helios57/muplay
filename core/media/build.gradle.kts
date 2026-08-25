plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.media"
}

dependencies {
  // `api`, not `implementation`: this module's public surface returns and accepts `:core:model`
  // types (`Song`, `LibraryRole`, `StreamFormat`), so a consumer cannot compile against it
  // without them. Same audit `plan-2-inherited.md` item 4 asked for, applied here.
  api(project(":core:model"))

  // `api` for the same reason, and only for this one artifact: `PlaybackConnection` (Task 9)
  // hands `:feature:player` a `MediaController`, which is a `media3-session` type. Everything
  // else Media3 offers stays `implementation`, and `media3-exoplayer` in particular must never
  // become `api` -- see this task's own note on why a feature module that can build an
  // `ExoPlayer` eventually does.
  api(libs.media3.session)

  implementation(libs.media3.exoplayer)
  implementation(libs.media3.datasource.okhttp)
  implementation(libs.okhttp)

  // `SubsonicSourceProvider` and, from Task 8, `MediaProgressDao`. `implementation`, not `api` --
  // and NOT because nothing leaks. Something does: `QueueRepository`'s public `@Inject`
  // constructor takes a `SubsonicSourceProvider`, which is a `:core:database` type, so by the
  // literal rule stated for `:core:model` six lines above this would be `api`. An earlier version
  // of this comment claimed the opposite ("nothing this module exposes publicly mentions a
  // `:core:database` type") and it was simply false.
  //
  // Kept as `implementation` on a judgement, recorded here so it can be revisited rather than
  // rediscovered:
  //
  //   * The leak is ONE type. `api` would put the whole module on every consumer's compile
  //     classpath -- `LibraryDao`, the entities, `MuPlayDatabase`, `SyncEngine`, `CredentialStore`
  //     -- including `:feature:player`, a UI module that must never see a DAO. That is the same
  //     argument this file already makes about `media3-exoplayer` never becoming `api`: a feature
  //     module that *can* build an `ExoPlayer` eventually does, and a feature module that can see
  //     `LibraryDao` eventually queries it. Widening a module boundary to declare one constructor
  //     parameter is the larger of the two harms.
  //   * The failure mode of getting this wrong is LOUD, and it is not reachable in this app's
  //     graph. A consumer missing `:core:database` fails at compile time in its own Hilt codegen,
  //     naming `SubsonicSourceProvider` -- never silently, never at runtime. And the only consumer
  //     that can exist is the module holding `SingletonComponent`, i.e. `:app`, which must depend
  //     on `:core:database` anyway to bind `CredentialStore`, the `DataStore<Preferences>` and
  //     `SubsonicSourceFactory` that `SubsonicSourceProvider` itself is built from. It does
  //     (`app/build.gradle.kts`).
  //
  // FLIP THIS TO `api` if a module that does not already depend on `:core:database` ever injects
  // `QueueRepository` -- a second Hilt component, or a consumer outside this build. That is the
  // condition, and it is not met today.
  implementation(project(":core:database"))
  // Also what `ProgressTableShapeTest` reflects over: it holds `media_progress` to spec section
  // 3's shape from the JVM tier, and a JVM test sees `implementation` dependencies, so the
  // narrower `testImplementation` it arrived with is subsumed here rather than duplicated.

  // `kotlinx-coroutines-core` is deliberately NOT on the production classpath, and
  // `QueueRepository.mediaItems` being `suspend` is not a reason to put it back: `suspend` is a
  // Kotlin *language* feature carried by `kotlin-stdlib` (`kotlin.coroutines.Continuation`), and
  // nothing in `src/main` imports `kotlinx.coroutines` at all -- checked, not assumed. It was on
  // `implementation` from Task 2 and used only by `runBlocking` in `src/androidTest`, reaching it
  // transitively; this is that dependency declared where it is actually used. `coroutines-test`
  // is likewise androidTest-only -- it was also on `testImplementation`, and no JVM test in this
  // module has ever imported it.
  androidTestImplementation(libs.coroutines.core)
  androidTestImplementation(libs.coroutines.test)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  // `mockwebserver3`, not `okhttp-mockwebserver` (= `mockwebserver3-junit5`): see that
  // catalogue entry's own note. The JUnit 5 extension cannot run under AndroidJUnitRunner and its
  // transitive junit-jupiter-api/junit-platform-commons break `mergeDebugAndroidTestJavaResource`
  // on a duplicate `META-INF/LICENSE.md`.
  androidTestImplementation(libs.mockwebserver3)
  // Byte Buddy is stripped from every androidTest configuration project-wide by
  // `excludeByteBuddyFromInstrumentedTests` (build-logic); nothing to do here.
  androidTestImplementation(libs.assertj)
  // The instrumented tests build real stream URLs against the real container.
  androidTestImplementation(project(":core:network"))
  // `FixedSubsonicSourceProvider` builds a **real** `CredentialStore` over a **real** DataStore
  // file, the same construction `:core:database`'s own `ShuffleRepositoryTest` uses, rather than
  // inventing an interface in production code so one test can substitute a provider. `:core:
  // database` declares `datastore-preferences` as `implementation`, which is not transitive, so
  // the test classpath has to name it here. Test-scope only: no production code in this module
  // touches DataStore.
  androidTestImplementation(libs.datastore.preferences)
}
