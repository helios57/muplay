plugins {
  id("muplay.android.library")
  id("muplay.kotlin.serialization")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.bindery"
}

dependencies {
  // `api`, not `implementation`, for the same reason `:integrations:lidarr` declares it that way:
  // `BinderySourceFactory.create()` returns a `BinderySource` built from an
  // `IntegrationCredentials.Bindery`, and `BinderySourceFactory.create` takes one. Both types are
  // declared in `:integrations:core`, so a consumer of this module cannot call either method
  // without them on its own compile classpath.
  //
  // **`:integrations:lidarr` is deliberately absent, and so is every other module in the build.**
  // The severability contract's third clause -- neither service is reachable from the other's code
  // path -- is a claim about dependency edges before it is a claim about behaviour, and this file
  // is where it is either true or not.
  api(project(":integrations:core"))

  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  implementation(libs.serialization.json)
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
  // `BinderySourceProvider`, whose collaborator `IntegrationCredentialStore` is DataStore over the
  // Android Keystore -- and Plan 8 deleted that class as unreachable: nothing in any `src/main`
  // injected it, because `RequestsRepository` takes `BinderySourceFactory` directly. Its eight
  // instrumented tests, the whole `androidTest` source set and its five test-only dependencies
  // went with it, and so did this module's two `requiresInstrumentedData` coverage floors and its
  // entries in `e2e.yml` and `pr.yml`. Everything this module still ships is gated on the JVM tier.
}
