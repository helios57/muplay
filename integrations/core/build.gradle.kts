plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.integrations"
}

dependencies {
  // OkHttp's own URL parser, not a Regex: the string `IntegrationBaseUrl` produces is handed
  // straight to `Retrofit.Builder().baseUrl(...)`, which parses it with this same class. A
  // separate validator would eventually disagree with the thing that actually connects.
  //
  // This is the module's only dependency. `kotlinx-coroutines-core` is deliberately absent:
  // nothing here suspends, and `:core:media`'s build file already records this project removing
  // exactly that dependency from a production classpath that did not use it. Task 2's credential
  // store is what will need it (and `muplay.android.hilt`'s `@Inject`, applied above ahead of its
  // first user because the module's Android/Hilt shape is fixed by that store, not by this file).
  implementation(libs.okhttp)
}
