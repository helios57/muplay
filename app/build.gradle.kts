plugins {
  id("muplay.android.application")
  id("muplay.android.compose")
  id("muplay.android.hilt")
  id("muplay.kotlin.serialization")
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
  implementation(project(":core:designsystem"))
  implementation(project(":feature:setup"))

  implementation(libs.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)

  // Navigation 3, not Navigation Compose — androidx.navigation:navigation-compose must never be
  // added alongside these.
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)
  // Backs SetupRoute's @Serializable NavKey — rememberNavBackStack saves the back stack via
  // rememberSaveable, which needs a KSerializer for each key.
  implementation(libs.serialization.json)
}
