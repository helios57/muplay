plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.requests"
}

dependencies {
  // All three are `api`: `RequestsRepository` returns `MediaRequest` and `RefreshReport` (whose
  // `skippedUnconfigured` is a `Set<IntegrationService>`), and `recordLidarrAdd`/`recordBinderyAdd`
  // take a `LidarrAlbumCandidate` and a `BinderyBookCandidate`/`BinderyBook`. A consumer of this
  // module cannot call a single one of those methods without all three on its compile classpath.
  api(project(":integrations:core"))
  api(project(":integrations:lidarr"))
  api(project(":integrations:bindery"))

  // `api`, and NOT the `implementation` the plan's Step 1 wrote. `MirrorSync.syncIfStale()`
  // returns `SyncState` and `AlbumSearch.search(...)` returns `SearchResults`, both of which are
  // declared outside this module -- `SyncState` here, `SearchResults`/`Album`/`LibraryRole` in
  // `:core:model`, which `:core:database` itself exposes with `api`. Three of this module's four
  // ports are public and every one of them has such a type in its signature, so `implementation`
  // would be a claim about this module's surface that is not true of it.
  //
  // Read-only, and through four single-method ports this module declares itself -- see
  // `MirrorPorts.kt`. Nothing here writes to the mirror, the watermark or a library role, and the
  // ports are what makes that structural rather than a promise.
  api(project(":core:database"))

  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)

  // The device tier exists for **one** class, `di.RequestsModule`, and it exists because there is
  // no JVM seam for it at all: every one of its four `@Provides` takes a concrete class that
  // transitively needs the Android Keystore (`SubsonicSourceProvider` -> `CredentialStore`, and
  // `IntegrationCredentialStore` directly). Measured before it was written -- all four providers
  // and their three SAM lambdas at **LINE 0/1 or 0/4**, exercised by nothing, while all 60 JVM
  // tests in this module passed. That is the same "obviously fine, exercised by nothing" shape
  // `:integrations:lidarr` and `:integrations:bindery` each found in their own wiring, and it is
  // the layer where a port could be bound to a collaborator that answers a different question.
  //
  // Room and DataStore are here rather than transitively: `muplay.android.room` puts
  // `room-runtime` on `:core:database`'s `implementation`, so `Room.inMemoryDatabaseBuilder` is
  // not on this module's classpath without asking. `room-testing` is deliberately NOT added --
  // nothing here migrates a database.
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  androidTestImplementation(libs.room.runtime)
  androidTestImplementation(libs.datastore.preferences)
  // AssertJ is added explicitly because `configureJUnit5` only puts it on `testImplementation`,
  // not `androidTestImplementation`. Byte Buddy -- which assertj-core drags in at compile scope
  // and AGP cannot dex -- is stripped from every androidTest configuration project-wide by
  // `excludeByteBuddyFromInstrumentedTests` in build-logic, so nothing is needed here for it.
  androidTestImplementation(libs.assertj)
}
