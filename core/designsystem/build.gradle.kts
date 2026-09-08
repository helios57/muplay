plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
}

android {
  namespace = "io.github.helios57.muplay.designsystem"
}

dependencies {
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
}
