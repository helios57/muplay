plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
}

android {
  namespace = "app.muplay.designsystem"
}

dependencies {
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
}
