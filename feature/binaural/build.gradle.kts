plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "io.github.helios57.muplay.waves"
}

dependencies {
  // The generator itself: profiles, presets, the timeline and the synthesiser. Pure JVM, so every
  // rule about what a profile sounds like is held on the fast tier there; this module adds only
  // what needs Android -- an `AudioTrack`, a foreground service, and the screens.
  implementation(project(":core:binaural"))
  // `MuPlayIcons` and `Message`, the same edge every feature takes.
  implementation(project(":core:designsystem"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.hilt.navigation.compose)
  // `BackHandler`: the editor has to know it is being left, not just recomposed. See `WaveEditorScreen`.
  implementation(libs.activity.compose)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)

  // Tier 2, this module's own: the generator driven through a real `AudioTrack` and a real
  // foreground service on the emulator, and the screens composed over the real profile file. Same
  // artifact set `:feature:player` documents in its own build file.
  androidTestImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
  androidTestImplementation(libs.coroutines.core)
  androidTestImplementation(libs.coroutines.test)
}
