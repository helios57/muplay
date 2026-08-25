plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.player"
}

dependencies {
  // `:core:media` exposes `media3-session` and `kotlinx-coroutines-core` as `api`, so a
  // `MediaController` and a `StateFlow<PlaybackState>` are reachable here. `media3-exoplayer` is
  // `implementation` there and is deliberately *not* reachable: a feature module that can build an
  // `ExoPlayer` eventually does, and then the process holds two players, one of which is not the
  // one behind the media session.
  implementation(project(":core:media"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.hilt.navigation.compose)
  // Declared, not inherited from `:core:media`'s `api`: this module names `MutableStateFlow`,
  // `combine`, `stateIn` and `launch` itself, so it declares the artifact it uses.
  implementation(libs.coroutines.core)
  implementation(libs.coil.compose)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)

  // Tier 2, this module's own: `PlayerScreenTest` and `MiniPlayerTest` compose the two stateless
  // overloads against a `PlayerUiState` built by hand. They need no media session, no Hilt graph
  // and no server -- only a device, because Compose cannot be composed on the JVM here (no
  // Robolectric, by constraint). The same artifact set `:app` already documents in its own build
  // file, and for the same reasons; see there for why JUnit 4 is unavoidable on-device and why
  // `androidx-test-espresso` has to be named rather than left transitive at 3.5.0.
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
}
