plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
}

android {
  namespace = "app.muplay.setup"
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:network"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)
  // SetupViewModel.connect rejects a blank/malformed server URL before any network call, using
  // HttpUrl.Companion.toHttpUrlOrNull — the same URL-parsing OkHttp already brings in transitively
  // for SubsonicClient, declared directly here rather than relied on transitively.
  implementation(libs.okhttp)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
  // Backs the one SetupViewModelTest that exercises the *default* ping wiring's success path
  // (SetupViewModel$1, the compiled default-lambda class) against a real socket rather than a
  // fake -- the same MockWebServer-is-real-enough stance core/network's SubsonicClientTest
  // itself documents.
  testImplementation(libs.okhttp.mockwebserver)
}
