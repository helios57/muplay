plugins {
  id("muplay.android.library")
  id("muplay.kotlin.serialization")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations.lidarr"
}

dependencies {
  // `api`, not `implementation`: `LidarrSourceProvider.current()` returns a `LidarrSource` built
  // from an `IntegrationCredentials.Lidarr`, and `LidarrSourceFactory.create` takes one. Both
  // types are declared in `:integrations:core`, so a consumer of this module cannot call either
  // method without them on its own compile classpath.
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

  // The instrumented tier exists for exactly one class: `LidarrSourceProvider`, whose collaborator
  // `IntegrationCredentialStore` is backed by DataStore and the Android Keystore and therefore has
  // no JVM tier at all. See `LidarrSourceProviderTest`.
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  androidTestImplementation(libs.datastore.preferences)
  // `KeystoreCipher` and `KeystoreKeys`, needed only to *plant* a stored entry that
  // `IntegrationCredentials` has no member to produce -- a Bindery blob, and a cleartext URL.
  // `:integrations:core` declares `:core:database` with `implementation`, so neither type is on
  // this module's compile classpath transitively, and neither is on its *main* classpath at all:
  // this module ships no code that touches the Keystore.
  androidTestImplementation(project(":core:database"))
  // AssertJ is added explicitly because `configureJUnit5` only puts it on `testImplementation`.
  // Byte Buddy -- which assertj-core drags in at compile scope and AGP cannot dex -- is stripped
  // from every androidTest configuration project-wide by `excludeByteBuddyFromInstrumentedTests`.
  androidTestImplementation(libs.assertj)
}
