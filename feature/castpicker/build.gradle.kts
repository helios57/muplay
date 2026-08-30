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
  // `NavKey`, named by `RendererDirectSection.Content`'s own signature. Declared rather than left
  // to `:feature:settings`'s `api`, because this module writes the type itself.
  implementation(libs.navigation3.runtime)

  // The arrow that matters, and it points this way only. `:feature:settings` must never name this
  // module or `:core:cast`: removing casting is `git rm -r core/cast feature/castpicker`, which
  // takes the `@IntoSet` binding below with it and leaves the settings screen with one fewer
  // section and nothing to edit. See `SettingsSection`'s own documentation and `ConventionTest`'s
  // `the settings slot never learns what is in it`.
  implementation(project(":feature:settings"))

  // Where the stored choice lives -- `CastSettings`, on the cast DataStore, beside the credentials
  // and deliberately **not** in `:core:cast`. `:core:cast` is pure JVM by design and `:core:database`
  // must not gain a dependency on it, which is the same item-5 argument from the other end: a
  // `core/database/build.gradle.kts` that named `:core:cast` would make dropping casting a change to
  // the lowest module in the tree.
  implementation(project(":core:database"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)

  // Tier 2, this module's own: the section composed for real, and the switch driven against a real
  // DataStore. Same artifact set `:feature:player` documents in its own build file, and for the
  // same reasons; see there for why JUnit 4 is unavoidable on-device and why `androidx-test-espresso`
  // has to be named rather than left transitive at 3.5.0.
  androidTestImplementation(libs.compose.ui.test.junit4)
  // Manifest only -- it declares the `androidx.activity.ComponentActivity` that `createComposeRule()`
  // needs to host a composition. `debugImplementation`, because a library module's test APK merges
  // this module's debug manifest.
  debugImplementation(libs.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso)
  androidTestImplementation(libs.assertj)
  androidTestImplementation(libs.coroutines.core)
  androidTestImplementation(libs.coroutines.test)
  // `:core:database` declares `datastore-preferences` as `implementation`, which is not transitive,
  // so the instrumented classpath names it here: the section is driven against a **real** DataStore
  // file rather than a hand-written stand-in for the one thing this screen exists to write to.
  androidTestImplementation(libs.datastore.preferences)
}
