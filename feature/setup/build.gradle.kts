plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
  // `SetupRoute` is `@Serializable`, because `rememberNavBackStack` saves keys with
  // `rememberSaveable`. Same reason `:feature:requests` applies it for `IntegrationsRoute`.
  id("muplay.kotlin.serialization")
}

android {
  namespace = "app.muplay.setup"
}

dependencies {
  implementation(project(":core:model"))
  // `MuPlaySpacing` and, through the theme, `MuPlayShapes`/`MuPlayTypography`. The first-run
  // screen is the one screen every user sees, and it was the last one still writing `16.dp` by
  // hand -- see `SetupScreen`'s `LibraryTagCard` for the one place the palette is actually taught
  // rather than merely applied.
  implementation(project(":core:designsystem"))
  implementation(project(":core:network"))
  implementation(project(":core:database"))
  // `ServerSection` is a `SettingsSection`, bound `@IntoSet` in this module's own Hilt module. The
  // arrow points this way -- setup names settings, never the reverse -- which is what
  // `ConventionTest`'s `the settings slot never learns what is in it` holds, and what makes the
  // section disappear with this module rather than needing an edit somewhere else.
  implementation(project(":feature:settings"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.hilt.navigation.compose)
  // `SetupRoute` is a `NavKey`, and `ServerSection` pushes it through `SettingsSection.Content`'s
  // `(NavKey) -> Unit`. Runtime only: `NavDisplay` and the back stack belong to `:app`, and a
  // feature module that could name them could navigate on its own.
  implementation(libs.navigation3.runtime)
  implementation(libs.serialization.json)
  implementation(libs.coroutines.core)
  // SetupViewModel.connect rejects a blank/malformed server URL before any network call, using
  // HttpUrl.Companion.toHttpUrlOrNull — the same URL-parsing OkHttp already brings in transitively
  // for SubsonicClient, declared directly here rather than relied on transitively.
  implementation(libs.okhttp)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)

  // The device tier. `ServerSection` is a `@Composable` over a flow, a confirmation dialog and a
  // `rememberSaveable`, and none of those three has a JVM tier here -- this project takes no
  // Robolectric. Until this source set existed the only thing that had ever composed this section
  // was an `:app` journey, which reaches it through the whole app and cannot vary its state.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that `createComposeRule()`
  // needs to host a composition. `debugImplementation`, because a library module's test APK merges
  // this module's debug manifest.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
}
