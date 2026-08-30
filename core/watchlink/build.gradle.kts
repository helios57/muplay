plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
  id("muplay.kotlin.serialization")
}

android {
  namespace = "app.muplay.watchlink"
}

dependencies {
  // `SubsonicCredentials`, which `CredentialSnapshot` is the wire form of.
  implementation(project(":core:model"))
  // `MediaProgressEntity`, `MediaProgressDao` and `CredentialStore` -- the two stores this module
  // replicates between. It never talks to Navidrome; see `WatchSyncEngine`'s own header.
  implementation(project(":core:database"))
  implementation(libs.coroutines.core)
  implementation(libs.serialization.json)
  // The one dependency this module exists to contain. Imported by `DataLayerWatchLink` and by
  // nothing else in the repository -- there is no other API for phone-to-watch messaging.
  implementation(libs.play.services.wearable)

  testImplementation(libs.coroutines.test)
}
