plugins {
  id("muplay.android.library")
  id("muplay.kotlin.serialization")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.lidarr"
}

dependencies {
  // `api`, not `implementation`: `LidarrSourceFactory.create()` returns a `LidarrSource` built
  // from an `IntegrationCredentials.Lidarr`, and `LidarrSourceFactory.create` takes one. Both
  // types are declared in `:integrations:core`, so a consumer of this module cannot call either
  // method without them on its own compile classpath.
  api(project(":integrations:core"))

  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  // `api`, not `implementation`, and that is a correction rather than a preference:
  // `LidarrAlbumCandidate.raw` is a `kotlinx.serialization.json.JsonObject` and that class is a
  // **public property of a public type this module returns**, so a consumer cannot so much as
  // construct or destructure a candidate without it. Found in Plan 7 Task 9, by a consumer:
  // `:integrations:requests`' own tests build a `LidarrAlbumCandidate` and failed with
  // "Cannot access class 'kotlinx.serialization.json.JsonObject'. Check your module classpath".
  // `:integrations:bindery` has the identical leak through `BinderyBookCandidate.raw` and is
  // another lane's file; see task-9-report.md.
  api(libs.serialization.json)
  implementation(libs.coroutines.core)

  // `mockwebserver3`, deliberately NOT `okhttp-mockwebserver` -- which resolves to
  // `mockwebserver3-junit5`. The JUnit 5 flavour's only addition is the `@StartStop` extension,
  // and it ships a second `META-INF/LICENSE.md` that fails `mergeDebugAndroidTestJavaResource`
  // (CLAUDE.md). This module has an androidTest source set, so that failure is reachable here in
  // a way it is not in `:core:network`; the cost is starting and stopping the server by hand in
  // `@BeforeEach`/`@AfterEach`, which is `:core:network`'s own pattern anyway.
  testImplementation(libs.mockwebserver3)
  testImplementation(libs.coroutines.test)

  // **This module has no instrumented tier.** It had exactly one class that needed a device --
  // `LidarrSourceProvider`, whose collaborator `IntegrationCredentialStore` is DataStore over the
  // Android Keystore -- and Plan 8 deleted that class as unreachable: nothing in any `src/main`
  // injected it, because `RequestsRepository` takes `LidarrSourceFactory` directly. Its eight
  // instrumented tests, the whole `androidTest` source set and its five test-only dependencies
  // went with it, and so did this module's two `requiresInstrumentedData` coverage floors and its
  // entries in `e2e.yml` and `pr.yml`. Everything this module still ships is gated on the JVM tier.
}
