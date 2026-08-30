plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
  // For the two `NavKey`s this module declares. They live here rather than in `:app`'s
  // `ui/navigation/` -- where every other route in this app lives -- for one structural reason:
  // `IntegrationsSection` pushes them through `SettingsSection.Content`'s `(NavKey) -> Unit`, and a
  // section cannot name a type declared in the application module that composes it.
  id("muplay.kotlin.serialization")
}

android {
  namespace = "app.muplay.requests"
}

// **This module and `:app` are the only two permitted to name an `:integrations:*` project**, and
// `ConventionTest`'s `nothing outside integrations depends on an integration` is what holds that.
// Deleting Plan 7 stays `git rm -r integrations feature/requests` plus these edges, the
// `settings.gradle.kts` includes and the `coverageFloors` entries.
dependencies {
  // `IntegrationService`, `MediaRequest`, `RequestStatus`, `IntegrationBaseUrl`/`BaseUrlResult`,
  // `CleartextPolicy`, `IntegrationCredentials` and `IntegrationCredentialStore`.
  implementation(project(":integrations:core"))
  // `RequestsRepository` -- this feature's one entry point to its data -- plus `RequestCandidate`,
  // `SearchReport`, `SubmitResult` and the `ConfiguredServices` port the setup screen reads.
  implementation(project(":integrations:requests"))
  // Named for exactly one thing each, and nothing else in this module reaches further into either:
  // the two `...SourceFactory` interfaces the connection probe builds a client with, and the two
  // `...UnauthorizedException` types `ConnectionCheck` recognises. A connection check runs against
  // a server that is NOT yet configured, so it cannot go through `RequestsRepository`, which is
  // built entirely around credentials that are already stored.
  implementation(project(":integrations:lidarr"))
  implementation(project(":integrations:bindery"))
  // The arrow that puts this feature in front of a user, and it points this way only.
  // `:feature:settings` names nothing here: the integrations row arrives through an `@IntoSet`
  // multibinding, so deleting this module takes the row with it. Same shape as
  // `:feature:castpicker` -> `:feature:settings`.
  implementation(project(":feature:settings"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.hilt.navigation.compose)
  // `NavKey`, written by this module's own route declarations and by `IntegrationsSection`'s
  // signature. `navigation3-ui` is deliberately absent: `NavDisplay` and the back stack belong to
  // `:app`, and a feature that could build its own graph would be a second place navigation lives.
  implementation(libs.navigation3.runtime)
  // `rememberNavBackStack` saves keys through `rememberSaveable`, which needs a `KSerializer` for
  // each one -- so a route declared here needs the runtime as well as the plugin.
  implementation(libs.serialization.json)
  // Declared, not inherited: this module names `MutableStateFlow`, `combine`, `stateIn`, `launch`
  // and `delay` itself.
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
  // Test-only, and only so a JVM test can build a **real** `RequestsRepository` rather than a fake
  // of it: its `RequestArrivalDetector` takes three ports whose signatures name `SyncState`
  // (`:core:database`) and `SearchResults` (`:core:model`). Nothing in `src/main` here touches
  // either module -- this feature reads no mirror and writes no library.
  testImplementation(project(":core:database"))
  testImplementation(project(":core:model"))
}
