plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.library"
}

dependencies {
  implementation(project(":core:model"))
  // The design system: `MuPlaySpacing`/`MuPlayShapes`/`Type.kt` and the shared `Message`. Added in
  // the design pass -- until then this screen wrote `16.dp` by hand and said "loading" with a bare
  // top-left `Text`, which is exactly the drift a shared system exists to stop.
  implementation(project(":core:designsystem"))
  implementation(project(":core:database"))
  // `PlaybackLauncher`. This module starts playback; it does not render it -- `:feature:player`
  // does that -- and it reaches Media3 through exactly one type, which is the whole reason that
  // launcher exists rather than three ViewModels each assembling a queue.
  implementation(project(":core:media"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.coil.compose)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
