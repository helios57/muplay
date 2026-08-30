plugins {
  id("muplay.android.library")
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
}
