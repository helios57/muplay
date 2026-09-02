plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.settings"
}

// **This module names no other feature, and the only `:core:` module it names is the theme.**
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
//
// The one edge below is `:core:designsystem`, and it is not that: see its own note.
dependencies {
  // `MuPlaySpacing` and the shared `Message`. **This is not a hole in the rule above.**
  // `:core:designsystem` is the theme -- a palette, a type scale, a spacing grid and one empty/
  // loading/error component -- and it holds no feature, no setting and no `SettingsSection`. The
  // rule this module keeps is that it must not learn *what is in its own slot*; every module in
  // the build already draws through this one, so an edge to it says nothing about which features
  // exist. `ConventionTest`'s `the settings slot never learns what is in it` is unchanged and
  // still passes: it forbids `:core:cast` and `:feature:castpicker` by name.
  implementation(project(":core:designsystem"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  // `NavKey` and nothing else -- the marker interface `SettingsSection.Content` hands a section so
  // that a section can open a screen of its own. A navigation primitive with no members, in the
  // same category as `Modifier`: it names no route, no destination and no feature, so this module
  // still learns nothing about what is in it. See `SettingsSection`'s own note for the three worse
  // alternatives, and note that this is `navigation3-runtime` only -- `navigation3-ui`, which owns
  // `NavDisplay` and the back stack, stays in `:app` where the graph is.
  // `api`, not `implementation`: `NavKey` is in `SettingsSection.Content`'s own signature, so a
  // module that implements a section cannot compile without it on its compile classpath.
  api(libs.navigation3.runtime)

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
