plugins {
  id("muplay.android.wear")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.wear"

  defaultConfig {
    // The **same** applicationId as `:app`, deliberately. Play requires a Wear APK to share the
    // phone app's application id to be distributed alongside it, and the two never land on the same
    // device -- a watch cannot install the phone APK and a phone cannot install this one.
    //
    // It is also a live hazard on a shared development host, and it is written down here because it
    // has already cost this repository two instrumented runs from a *different* cause with the same
    // shape (see CLAUDE.md, "Two concurrent instrumented runs corrupt each other's results"):
    // installing this module on the phone emulator REPLACES `:app`. Never run
    // `:wear:connectedDebugAndroidTest` against `muplay37`. `WearSessionJourneyTest`'s first
    // assertion and `ci/prepare-wear-emulator.sh` both refuse that device for the separate reason
    // that a wear suite on a phone image is green and worthless.
    applicationId = "app.muplay"
    // Paired by `verifyReleaseVersion`, exactly as `:app`'s are: versionCode is
    // MAJOR * 10000 + MINOR * 100 + PATCH of versionName, and neither may reuse a value listed in
    // wear/release-history.tsv. This module has its own ledger because it is its own artifact.
    versionCode = 100
    versionName = "0.1.0"
  }
}

// No `muplayApplication { androidAuto = true }` here, and its absence is a decision rather than an
// omission: a watch app declaring itself an Android Auto media app would be a wrong claim in a
// shipped manifest. `AndroidWearConventionPlugin`'s own header says so, and the consequence is that
// `:wear:verifyAutomotiveDescriptor` reports SKIPPED rather than checking a descriptor that has no
// business existing here.

dependencies {
  // `BrowseSurfaces.HINT_WATCH` -- the self-declaration that gets this app the watch tree.
  implementation(project(":core:model"))
  // The service, `PlaybackConnection`, and -- through the manifest merger -- INTERNET,
  // POST_NOTIFICATIONS, FOREGROUND_SERVICE(_MEDIA_PLAYBACK) and `MuPlaybackService` itself.
  // `:core:media`'s manifest exists in that module rather than in `:app` precisely so a second
  // application module gets all of it without anyone copying four lines, and
  // `:wear:verifyDebugManifest`/`verifyReleaseManifest` are what turn that from a claim into
  // evidence for this module too.
  implementation(project(":core:media"))
  // The watch's own Room database, credential store and sync engine. Its own, not the phone's:
  // two devices, two databases. Task 10 is what reconciles them.
  implementation(project(":core:database"))

  // Compose for Wear OS. A different artifact family from `androidx.compose.material3`, which this
  // module deliberately does not depend on.
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.activity.compose)
  // `MediaBrowser`/`SessionToken`, for `WearBrowser`.
  implementation(libs.media3.session)

  debugImplementation(libs.wear.tooling.preview)

  // Tier 2, on a WEAR image only -- see ci/prepare-wear-emulator.sh and the first assertion in
  // WearSessionJourneyTest. `androidx-test-ext` supplies the AndroidJUnit4 runner class the test is
  // annotated with, `androidx-test-runner` the AndroidJUnitRunner instrumentation itself
  // (`configureKotlinAndroid` sets it as testInstrumentationRunner), `androidx-test-core`
  // ApplicationProvider.
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.assertj)
  // Re-declared rather than inherited: an androidTest compile classpath does not extend the
  // production `implementation` configuration, which is why `:app` re-declares `:core:media` for
  // its own journeys too.
  androidTestImplementation(project(":core:media"))
  androidTestImplementation(project(":core:database"))
  androidTestImplementation(project(":core:model"))
  // `runBlocking`. Reaches the runtime transitively through the two modules above, but an
  // `implementation`-scoped transitive dependency is not on anybody's compile classpath.
  androidTestImplementation(libs.coroutines.core)
}
