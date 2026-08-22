plugins {
  id("muplay.jvm.library")
  id("muplay.kotlin.serialization")
}

dependencies {
  implementation(project(":core:model"))

  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  implementation(libs.serialization.json)
  implementation(libs.coroutines.core)

  // Backs SubsonicClientTest: OpenApiFixtureValidator.assertValid proves every fixture against
  // the vendored OpenSubsonic spec before this module's tests trust it as a stand-in for a real
  // server response.
  testImplementation(project(":core:testing"))
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.coroutines.test)
}
