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

  // `SubsonicSourceProvider` and, from Task 8, `MediaProgressDao`. `implementation`, not `api`:
  // nothing this module exposes publicly mentions a `:core:database` type.
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
