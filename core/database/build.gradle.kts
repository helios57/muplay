plugins {
  id("muplay.android.library")
  id("muplay.android.room")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.database"
}

dependencies {
  // `api`, not `implementation`: every repository in this module returns `:core:model` types
  // (`MusicLibrary`, `Album`, `Song`, ...) from its public signatures, so a consumer cannot
  // compile against this module without them.
  //
  // `:core:network` is `implementation` **at this point in the plan** and only at this point:
  // nothing public here mentions a network type yet. Task 4 introduces `SubsonicSourceProvider`,
  // whose `current(): SubsonicSource` is public, and promotes this line to `api` for that reason.
  // (`plan-2-inherited.md` item 4 asked for exactly this audit; this is it being done rather
  // than assumed.)
  api(project(":core:model"))
  implementation(project(":core:network"))

  implementation(libs.coroutines.core)
  implementation(libs.datastore.preferences)

  testImplementation(libs.coroutines.test)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  // JUnit 5 is this project's JVM stack; AndroidJUnitRunner runs JUnit 4, unavoidable on-device
  // and harmless — the ban that matters is on mock frameworks (`ConventionTest`), not JUnit 4.
  // JUnit 4 arrives transitively through the two AndroidX test artifacts above, so nothing here
  // pins a version of its own. AssertJ is added explicitly because `configureJUnit5` only puts
  // it on `testImplementation`, not `androidTestImplementation`.
  // Byte Buddy is kept off this classpath by `excludeByteBuddyFromInstrumentedTests` in
  // build-logic, not here: assertj-core drags in a compile-scope dependency AGP cannot dex, and
  // every module that puts AssertJ on a device would otherwise rediscover the same failure. See
  // that function for the full account.
  androidTestImplementation(libs.assertj)
}
