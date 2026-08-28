plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.settings"
}

// **This module names no other feature and no `:core:` module at all, and that is the point of it.**
//
// The settings screen is a *slot*: it renders whatever `SettingsSection` implementations the Hilt
// graph happens to contain, sorted, and it knows nothing about any of them. The dependency arrow
// runs `:feature:castpicker` -> `:feature:settings` and never back, so `git rm -r core/cast
// feature/castpicker` takes the `@IntoSet` binding with it, the multibound set goes back to empty,
// and this screen loses a section without noticing. That is Plan 6's definition-of-done item 5
// expressed as a build file: a dependency has to be *declared* to be used, and this file declares
// none.
//
// `ConventionTest`'s `the settings slot never learns what is in it` holds that against the tree,
// because a single `implementation(project(":core:cast"))` added here later would compile, work,
// and quietly turn a severable feature into a load-bearing one.
dependencies {
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.lifecycle.viewmodel.compose)

  // Tier 2, this module's own composables. Same artifact set `:feature:player` documents in its own
  // build file, and for the same reasons; see there for why JUnit 4 is unavoidable on-device and
  // why `androidx-test-espresso` has to be named rather than left transitive at 3.5.0.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that `createComposeRule()`
  // needs to host a composition.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
}
