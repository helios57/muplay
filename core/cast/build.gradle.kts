plugins {
  id("muplay.jvm.library")
}

dependencies {
  // `api`, not `implementation`: this module's public surface takes and returns `StreamFormat`
  // (see `ServedMedia` in Task 4), so a consumer cannot compile against it without `:core:model`.
  api(project(":core:model"))

  // OkHttp is here for exactly one job: the proxy's *upstream* fetch of Navidrome over HTTPS
  // (Task 6), where TLS, redirects, connection reuse and Navidrome's 429 all matter. It is
  // deliberately NOT used for any traffic to a renderer -- see `LocalNetworkOnly`'s KDoc and this
  // task's own note on the cleartext constraint.
  //
  // That split is this module's central security claim, and it used to be enforced by this
  // comment: an `okhttp3` import under `cast/http` would route renderer traffic through a stack
  // that consults `NetworkSecurityPolicy` instead of `LocalNetworkOnly`, bypassing the rule
  // entirely -- and because the debug manifest carries `usesCleartextTraffic=true`, it would work
  // perfectly on the bench and ship the bypass. `ConventionTest`'s `only the cast module's proxy
  // package may reach for OkHttp` now enforces it mechanically, the same way `BANNED_MOCK_GROUPS`
  // and `forbiddenAttributes` are enforced rather than requested. That test reads the exemption
  // from the line below, so this is a declaration and not a note: change the package here and the
  // rule follows; delete the line and the test fails rather than silently scanning nothing.
  //
  // OKHTTP EXEMPT PACKAGE: app.muplay.cast.proxy
  implementation(libs.okhttp)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
