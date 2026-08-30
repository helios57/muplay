plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.book"
}

dependencies {
  // `BookSummary`, `BookSettings`, `SleepTimerState`, `Song`, `ResumePoint` -- every input the
  // pure derivations below take. Declared even though `:core:media` already `api`s it: this
  // module imports those types by name, and a dependency you use is one you declare.
  implementation(project(":core:model"))
  // `AudiobookRepository` -- the shelf, one book's files, its resume point, and `restart`.
  implementation(project(":core:database"))
  // `BookChapter`/`BookTimeline`/`PlaybackState` for the state derivations, and
  // `PlaybackLauncher`/`AudiobookSnapshot` for `BookPlaybackLauncher`. This module starts
  // playback through exactly one type, the same way `:feature:library` does, and it renders
  // nothing Media3 hands back.
  implementation(project(":core:media"))

  // The three screens. Copied from `:feature:library`, which is this module's model in every
  // other respect too -- the Compose BOM arrives with `muplay.android.compose`, so none of these
  // carries a version of its own.
  //
  // `coil.compose` is here for `BookCover`, which is this module's own and deliberately not
  // `:feature:library`'s `CoverArtImage`: a feature-to-feature edge is the one thing
  // `settings.gradle.kts` says this module must not have, and `:feature:player`'s `Artwork` is
  // the precedent for solving it locally.
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.coil.compose)

  testImplementation(libs.coroutines.test)

  // Tier 2, this module's own three screens. The same artifact set `:feature:player` and
  // `:feature:settings` document in their own build files, and for the same reasons; see
  // `:feature:player` for why JUnit 4 is unavoidable on-device and why `androidx-test-espresso`
  // has to be named rather than left transitive at 3.5.0.
  //
  // The suites compose the **stateless** halves -- `BookshelfContent`, `BookContent`,
  // `BookPlayerContent` -- against a state built by hand, so they need no Hilt graph, no Room and
  // no media session. What they therefore cannot prove is the `hiltViewModel()` default argument
  // or the hop out of each view model, which only an `:app` journey reaches.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that
  // `createComposeRule()` needs to host a composition. `debugImplementation`, because a library
  // module's test APK merges this module's debug manifest.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
}
