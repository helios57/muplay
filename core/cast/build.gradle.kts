plugins {
  id("muplay.jvm.library")
  // The **fake renderer** -- a real UPnP renderer over a real socket, strict in the ways a Sonos is
  // strict -- has a second consumer as of Plan 6 Task 9: `:core:media`'s instrumented `HandoverTest`
  // drives a handover against it on the device, where `SimpleBasePlayer` and a real `ExoPlayer` can
  // exist and this module's own JVM tier cannot follow.
  //
  // A test-fixtures source set rather than a copy in `src/androidTest`, because the alternative is
  // ~1000 lines of protocol fake maintained in two places, which is two renderers to keep strict.
  // `src/test` still sees it: the plugin puts the fixtures on this module's own test compile
  // classpath automatically, which is why `FakeRendererStrictnessTest` did not move with it.
  `java-test-fixtures`
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

  // Test-only, and only for `LiveNavidromeProxyTest` (Task 6): that class needs a real Subsonic
  // stream URL for a real seeded track, and `SubsonicClient.streamUrl` is the one that Task 7 will
  // hand this proxy in production -- so the live test exercises the real pairing rather than a
  // second, hand-rolled implementation of Subsonic's md5 auth living in this module.
  //
  // `testImplementation`, never `implementation`: nothing in `:core:cast`'s main source set knows
  // that Navidrome exists. The proxy takes a URL string and relays it, which is what keeps this
  // module a protocol module and lets Task 7 decide where the URL comes from.
  testImplementation(project(":core:network"))
}
