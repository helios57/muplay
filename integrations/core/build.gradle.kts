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
  implementation(libs.okhttp)

  // Task 2. `implementation`, not `api`: `KeystoreKeys` and `KeystoreCipher` are what seal the API
  // key, and neither appears in any public signature this module exposes -- `IntegrationCredentials`
  // and `IntegrationBaseUrl`, which do, are declared here. A consumer of this module therefore
  // needs nothing from `:core:database` to compile against it.
  //
  // The edge runs this way only. `:core:database` must never depend on `:integrations:core`, and
  // `ConventionTest`'s `nothing outside integrations depends on an integration` is what keeps
  // Plan 7 severable; deleting this plan deletes this module and leaves `KeystoreKeys` behind with
  // one caller, which is a 40-line refactor's worth of residue rather than a feature's worth.
  implementation(project(":core:database"))

  // Both new in Task 2, and both were deliberately absent before it: `IntegrationCredentialStore`
  // is the module's first suspending code and its first DataStore.
  implementation(libs.coroutines.core)
  implementation(libs.datastore.preferences)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  // AssertJ is added explicitly because `configureJUnit5` only puts it on `testImplementation`,
  // not `androidTestImplementation`. Byte Buddy -- which assertj-core drags in at compile scope
  // and AGP cannot dex -- is stripped from every androidTest configuration project-wide by
  // `excludeByteBuddyFromInstrumentedTests` in build-logic, so nothing is needed here for it.
  androidTestImplementation(libs.assertj)
}
