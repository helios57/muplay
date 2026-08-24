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
  implementation(project(":feature:setup"))

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
}
