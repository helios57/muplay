plugins {
  id("muplay.android.application")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay"

  defaultConfig {
    applicationId = "app.muplay"
    versionCode = 1
    versionName = "0.1.0"
  }
}

dependencies {
  implementation(libs.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)
}
