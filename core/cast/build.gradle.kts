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
  // task's own note on the cleartext constraint. If you find an `okhttp3` import under
  // `app/muplay/cast/http`, `discovery`, `soap`, `didl` or `control`, that is the bug.
  implementation(libs.okhttp)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
