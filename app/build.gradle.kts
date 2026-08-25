plugins {
  id("muplay.android.application")
  id("muplay.android.compose")
  id("muplay.android.hilt")
  id("muplay.kotlin.serialization")
}

android {
  namespace = "app.muplay"

  defaultConfig {
    applicationId = "app.muplay"
    versionCode = 1
    versionName = "0.1.0"
  }
}

dependencies {
  implementation(project(":core:database"))
  implementation(project(":core:designsystem"))
  // The `:app` -> `:core:media` edge, and the first time anything depends on that module at all.
  // Two things ride on it that nothing else in the build supplies: the manifest merger pulls
  // `:core:media`'s service declaration and its three playback permissions into the application's
  // merged manifest (which `verifyDebugManifest`/`verifyReleaseManifest` then check), and Hilt's
  // aggregating processor finally sees `MediaModule` inside a real `@HiltAndroidApp` component --
  // until this line existed, that module's bindings had never been through a Dagger component
  // compile at all.
  implementation(project(":core:media"))
  implementation(project(":feature:setup"))
  implementation(project(":feature:library"))

  implementation(libs.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)

  // Navigation 3, not Navigation Compose — androidx.navigation:navigation-compose must never be
  // added alongside these.
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)
  // Backs SetupRoute's @Serializable NavKey — rememberNavBackStack saves the back stack via
  // rememberSaveable, which needs a KSerializer for each key.
  implementation(libs.serialization.json)
  // The app is where the image loader is built (MuPlayApplication), so this is the one module
  // that needs Coil's base ImageLoader/SingletonImageLoader API and its OkHttp network fetcher
  // as direct dependencies -- neither is exposed transitively by :feature:library's own
  // implementation-scoped coil-compose dependency.
  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)

  // `MuPlayApp` now hosts a ViewModel and collects a StateFlow, neither of which this module
  // depended on before: it was a pure navigation shell.
  implementation(libs.hilt.navigation.compose)
  implementation(libs.lifecycle.runtime.compose)

  // Tier 2 (.github/workflows/e2e.yml): FirstRunJourneyTest drives the real app on a real
  // emulator against a real Navidrome container. `compose-ui-test-junit4` supplies
  // `createAndroidComposeRule` and the finder/assertion API; `androidx-test-ext` supplies the
  // `AndroidJUnit4` runner class the test is annotated with, and `androidx-test-runner` the
  // `AndroidJUnitRunner` instrumentation itself declares as `testInstrumentationRunner` (see
  // `configureKotlinAndroid` in build-logic). Only `compose-ui-test-junit4` is version-less --
  // it is resolved by the Compose BOM `muplay.android.compose` already adds to
  // `androidTestImplementation`; the two AndroidX test artifacts carry their own catalogue
  // versions.
  //
  // JUnit 4, not this project's JUnit 5: `createAndroidComposeRule` is a JUnit 4 `TestRule`, and
  // AndroidJUnitRunner runs JUnit 4 — unavoidable on-device, and harmless, since the ban that
  // matters here is on mock frameworks (`ConventionTest`), not on JUnit 4. It arrives
  // transitively through the two AndroidX test artifacts above rather than being declared here,
  // so nothing in this file pins a JUnit 4 version of its own.
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  // Declared, not left transitive: `compose-ui-test-junit4` drags in espresso-core 3.5.0, which
  // cannot run at all on an API 37 device -- see this entry's own note in libs.versions.toml for
  // the exact failure. Nothing in this module calls Espresso directly; Compose's own
  // `waitForIdle` does, via `Espresso.onIdle`.
  androidTestImplementation(libs.androidx.test.espresso)
  // ActivityScenario and ApplicationProvider for the journeys.
  androidTestImplementation(libs.androidx.test.core)

  // `MuPlaybackServiceTest`. The service is `@AndroidEntryPoint`, so it can only be started from an
  // application that is `@HiltAndroidApp` -- which is this module and no other. See that test's own
  // documentation for why it cannot live in `:core:media`, and `Jacoco.kt`'s `mergedExecutionData`
  // for why its coverage still lands there.
  androidTestImplementation(project(":core:media"))
  // The test lists the seeded tracks off the real container to know what titles to expect.
  androidTestImplementation(project(":core:network"))
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.assertj)
}
