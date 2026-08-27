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

  // Plan 6 Task 4. `MediaItems.of` sets `MediaItem.localConfiguration.mimeType` from
  // `ServedMedia`, the one statement of what format a renderer is about to receive.
  //
  // `implementation`, not `api`: no `:core:cast` type appears in this module's public surface --
  // `MediaItems.of` takes a `StreamFormat` (already `api` via `:core:model`) and the MIME leaves
  // as a `String` on the `MediaItem`.
  //
  // `:core:cast` is pure JVM. This dependency is one-directional and stays that way for the rest
  // of the plan: `:core:cast` must never see a Media3 type, which is why `UpnpPlayer` (Task 8)
  // lives here and not there.
  implementation(project(":core:cast"))

  implementation(libs.media3.exoplayer)
  // Plan 4 Task 3. `MetadataRetriever` -- the only way this client can show chapters, because
  // Navidrome exposes none and OpenSubsonic has no chapter schema. `implementation`, not `api`:
  // `ChapterReader` returns `:core:model`'s `Chapter`, so no `media3-inspector` type appears in
  // this module's public surface.
  implementation(libs.media3.inspector)
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

  // `kotlinx-coroutines-core` used to be declared here, androidTest-only, under a comment saying
  // it was deliberately off the production classpath -- `QueueRepository.mediaItems` being
  // `suspend` was not a reason to add it, because `suspend` is a Kotlin *language* feature carried
  // by `kotlin-stdlib`, and nothing in `src/main` imported `kotlinx.coroutines` at all. Task 5's
  // `PlaybackConnection` is the first production class that does, so it moved to `api` at the end
  // of this block; the instrumented declaration is gone rather than duplicated, since `api` puts it
  // on every one of this module's own compile classpaths.
  //
  // `coroutines-test` stays androidTest-only -- it was on `testImplementation` once, and no JVM
  // test in this module has ever imported it.
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
  // Plan 3 Task 6. `QueueRepositoryTest` builds a **real** `LibraryRepository` over a real
  // in-memory Room database, because `idsWithRole` is a SQL `WHERE role = :role` and the defect it
  // has to be able to catch is the repository asking for the wrong role. `:core:database` declares
  // `room-runtime` as `implementation`, which is not transitive, so the test classpath has to name
  // it here. Test-scope only: no production code in this module touches Room.
  androidTestImplementation(libs.room.runtime)

  // Plan 3 Task 7. `GaplessTest` reads its whole claim through `PcmAnalysis`, and that analyser
  // lives in `:core:testing` rather than beside the test for a reason this module cannot supply on
  // its own: `longestZeroRunFrames` is a check that reports the *absence* of a problem, so it needs
  // its own tests, and an instrumented source set cannot see this module's `src/test`. A shared JVM
  // module is the only place its correctness is a Tier 1 concern.
  //
  // `:core:testing` also carries `OpenApiFixtureValidator`, whose `implementation(libs.openapi
  // .validator)` stays on a consumer's *runtime* classpath -- swagger-parser, jackson, and their
  // duplicated `META-INF` entries, all of it dragged into this APK behind one pure-Kotlin object.
  // That is the same shape as the `mockwebserver3-junit5` duplicate-LICENSE.md failure recorded in
  // CLAUDE.md, so it is excluded deliberately rather than discovered later: nothing in this module's
  // instrumented sources references `OpenApiFixtureValidator`, and excluding the validator's root
  // artifact takes its whole transitive subtree with it.
  androidTestImplementation(project(":core:testing")) {
    exclude(group = "com.atlassian.oai", module = "openapi-request-validator-core")
  }

  // Plan 3 Task 5. `PlaybackConnection` is the first production class in this module that imports
  // `kotlinx.coroutines` at all -- `MutableStateFlow`, `CoroutineScope`, `launch`, `delay`,
  // `withContext` -- so the comment above, which said this module deliberately keeps coroutines off
  // the production classpath, stopped being true here and this is that dependency declared where it
  // is now genuinely used.
  //
  // `api`, not `implementation`, and for the module's usual reason: `PlaybackConnection.state` is
  // `StateFlow<PlaybackState>`, so `:feature:player` cannot compile against this module's public
  // surface without `StateFlow` on its own compile classpath.
  //
  // `kotlinx-coroutines-android` is deliberately NOT added alongside it. `PlaybackConnection` needs
  // a main-thread dispatcher and gets one from `Handler(Looper.getMainLooper())` rather than from
  // `Dispatchers.Main`, which is the only thing in this file's reach that would have required that
  // artifact. It is on the runtime classpath transitively today (1.7.3, via Hilt and AndroidX), and
  // an undeclared-but-used transitive dependency is exactly the audit `plan-2-inherited.md` item 4
  // asked for -- so this module does not use it.
  api(libs.coroutines.core)
}
