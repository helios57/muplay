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
}
