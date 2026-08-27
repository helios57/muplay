plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.castpicker"
}

dependencies {
  // `RememberedRenderer` (the "not answering" rows) and `RememberedRenderers` (the store the
  // directory provider below reads). Both are `:core:model` types precisely so that this module
  // and `:core:database` can share them without either seeing the other.
  implementation(project(":core:model"))
  // `DiscoveryResult`, `CastDevice`, `CastSessionState`, and the production wiring of
  // `RendererDirectory` in this module's own DI. A pure-JVM module, which is what puts the whole
  // state mapping on Tier 1.
  implementation(project(":core:cast"))
  // For `CastSessionManager` and its `CastSessionState`. This module gets no `Player` factory and
  // no `ExoPlayer`: `media3-exoplayer` is `implementation` in `:core:media` precisely so that a
  // feature cannot reach it. What *is* reachable is `media3-session`'s `Player`, which is `api`
  // there, and this module uses exactly one method of it -- `setDeviceVolume`.
  implementation(project(":core:media"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.hilt.navigation.compose)
  // Declared, not inherited: this module names `MutableStateFlow`, `stateIn`, `combine` and
  // `launch` itself.
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)

  // Tier 2, this module's own -- the same artifact set and the same reasons `:feature:player`
  // records in its build file. The sheet and the button are composed against a `CastViewModel`
  // built by hand over two fake seams, so these suites need no Hilt graph, no speaker and no
  // network; what they cannot prove is the `hiltViewModel()` default argument, which only `:app`'s
  // journey reaches.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that
  // `createComposeRule()` needs to host a composition.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
  androidTestImplementation(libs.coroutines.core)
}
