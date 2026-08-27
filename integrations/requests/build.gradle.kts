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
}
