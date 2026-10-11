plugins {
  id("muplay.jvm.library")
  id("muplay.kotlin.serialization")
}

dependencies {
  // The profile document is JSON and the models are `@Serializable`, so consumers that encode or
  // decode them need the runtime too.
  api(libs.serialization.json)
  // `ProfileLibrary` serialises its writes with a `Mutex` and publishes a `StateFlow`.
  api(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
}
