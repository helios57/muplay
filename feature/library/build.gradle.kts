plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "io.github.helios57.muplay.library"
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

  // The device tier, added 2026-09-09 to close a hole `:app` cannot reach. Two of `ShuffledRow`'s
  // callbacks and the A-Z rail's `scrollToItem` had sat at 0.00 LINE since 2026-09-06 -- not for
  // want of a test but because the shared CI Navidrome's music library holds **one album**, and
  // `FastScrollBar` refuses to draw over fewer than `FAST_SCROLL_MIN_ITEMS` rows. No `:app` journey
  // against that container can drag a rail that is not drawn, and none ever will. Composing the
  // stateless `LibraryScreen` here against a shelf built by hand is the only thing that can, and it
  // is the same tier, for the same reason, that `:feature:setup` added for `ServerSection`.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that `createComposeRule()`
  // needs to host a composition. `debugImplementation`, because a library module's test APK merges
  // this module's debug manifest.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  // Not optional and not decorative, which is worth stating because the reason is invisible:
  // `compose-ui-test-junit4` calls `Espresso.onIdle()` on every `waitForIdle`, and the Espresso it
  // drags in transitively is old enough to reach `InputManager.getInstance` reflectively -- a
  // method API 37 no longer has. Without this line every test in this module fails at composition
  // with a `NoSuchMethodException` naming that platform method, and naming neither Espresso's
  // version nor this file. `:feature:player` and `:feature:setup` pin it for the same reason.
  //
  // (The fully-qualified name of that method is *not* written here on purpose:
  // `ConventionTest`'s `no module configures android or kotlin blocks directly` is a plain regex
  // over the whole build file and reads comments, so spelling it out fails the build on prose.
  // Third gate in this repository to do that; see CLAUDE.md.)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
}
