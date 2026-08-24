plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.setup"
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:network"))
  implementation(project(":core:database"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.coroutines.core)
  // SetupViewModel.connect rejects a blank/malformed server URL before any network call, using
  // HttpUrl.Companion.toHttpUrlOrNull — the same URL-parsing OkHttp already brings in transitively
  // for SubsonicClient, declared directly here rather than relied on transitively.
  implementation(libs.okhttp)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
