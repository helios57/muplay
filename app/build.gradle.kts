import org.gradle.api.tasks.PathSensitivity

plugins {
  id("muplay.android.application")
  id("muplay.android.compose")
  id("muplay.android.hilt")
  id("muplay.kotlin.serialization")
}

android {
  namespace = "app.muplay"

  defaultConfig {
    applicationId = "app.muplay"
    // Two literals a human edits, paired by `verifyReleaseVersion`: versionCode is
    // MAJOR * 10000 + MINOR * 100 + PATCH of versionName, and neither may reuse a value listed in
    // app/release-history.tsv. Nothing computes them, and nothing may -- `ConventionTest`'s
    // `android { }` allow-list permits a bare integer and a bare string here and nothing else.
    // See build-logic/convention/src/main/kotlin/VerifyReleaseVersionTask.kt for both rules.
    versionCode = 200
    versionName = "0.2.0"
  }
}

// This application ships to Android Auto. Four words that turn on two gates: they add
// AUTOMOTIVE_DECLARATIONS to `verify<Variant>Manifest`'s required list -- the browse actions and
// the car meta-data -- and they turn `verifyAutomotiveDescriptor` from SKIPPED into a real check of
// res/xml/automotive_app_desc.xml. Deleting them removes both gates and fails nothing at all, which
// is why `ConventionTest`'s `the app module opts in to Android Auto and ships the descriptor it
// promises` asserts this line against the tree.
//
// The roadmap's `:wear` module deliberately will not set this: a watch app claiming to be a car app
// would be a wrong claim in a shipped manifest.
muplayApplication {
  androidAuto = true
}

/**
 * The files `StoreListingTest` reads that Gradle would otherwise not know this task depends on.
 *
 * **Measured, and it is the defect this repository exists to keep out.** Edit
 * `docs/STORE-LISTING.md` to claim a sleep timer -- which that test's banned list forbids -- and
 * `./gradlew :app:testDebugUnitTest` answers `Task :app:testDebugUnitTest FROM-CACHE`,
 * `BUILD SUCCESSFUL`, in four seconds. The gate over the one document in this repository that is
 * read by strangers does not run when that document changes; only an unrelated Kotlin edit, or a
 * cold cache, makes it run at all. A falsification of that test that forgets `--rerun` therefore
 * reports every rule as unfalsifiable, which is how this was found.
 *
 * Only the inputs a human edits are listed. The test also walks every `src/main` source, and
 * declaring the whole tree would make the unit-test task depend on files it has no business
 * rebuilding for -- the Kotlin sources are already inputs by another route, and a source edit is
 * not the change that silently skips this gate.
 */
tasks.withType<Test>().configureEach {
  inputs.files(
    rootProject.file("docs/STORE-LISTING.md"),
    rootProject.file(".github/workflows/release.yml"),
  )
    .withPropertyName("storeListingDocuments")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.dir(rootProject.file("play"))
    .withPropertyName("storeListingAssets")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
  implementation(project(":core:database"))
  implementation(project(":core:designsystem"))
  // The `:app` -> `:core:media` edge, and the first time anything depends on that module at all.
  // Two things ride on it that nothing else in the build supplies: the manifest merger pulls
  // `:core:media`'s service declaration and its three playback permissions into the application's
  // merged manifest (which `verifyDebugManifest`/`verifyReleaseManifest` then check), and Hilt's
  // aggregating processor finally sees `MediaModule` inside a real `@HiltAndroidApp` component --
  // until this line existed, that module's bindings had never been through a Dagger component
  // compile at all.
  implementation(project(":core:media"))
  implementation(project(":feature:setup"))
  implementation(project(":feature:library"))
  // The player screen and the mini player `MuPlayApp` hosts, and the `PlayerViewModel` behind
  // both. `:app` is the only module that composes them.
  implementation(project(":feature:player"))
  // Plan 4 Task 9. The shelf, one book and the audiobook player `MuPlayApp` hosts, and the three
  // view models behind them. `:app` is the only module that composes them -- in particular
  // `:feature:player` does not, and `:feature:book` does not compose `:feature:player`: the two
  // are different instruments over one transport, and `:app` chooses between them from
  // `PlaybackState.isAudiobook`.
  implementation(project(":feature:book"))
  // Plan 6 Task 10. The cast button and the picker sheet `MuPlayApp` hosts, and the `CastViewModel`
  // both share. `:app` is the only module that composes them -- in particular `:feature:player`
  // does not: it takes the button as a `@Composable` slot and the connected speaker's name as a
  // `String?`, so two feature modules never depend on each other and dropping casting stays
  // `git rm -r core/cast feature/castpicker` plus this line.
  // Plan 6 Task 12. The settings screen is a *slot*: `:app` composes it and names none of the
  // sections in it. See `SettingsSection`.
  implementation(project(":feature:settings"))
  // Plan 6 Task 12 needs this edge only so the renderer-direct section's `@IntoSet` binding is on
  // the application's classpath and therefore in the `SingletonComponent` -- nothing in `:app`
  // names a type from it. Plan 6 Task 10 owns this module and will widen the edge to the picker UI.
  implementation(project(":feature:castpicker"))

  // One of the two edges from :app into integrations/. Nothing else in the tree may name an
  // :integrations:* project, and `ConventionTest`'s `nothing outside integrations depends on an
  // integration` enforces that. This one exists for the two variant-specific
  // `CleartextPolicyModule`s under src/debug and src/release, which are what decides whether an
  // http:// integration URL is accepted at all.
  implementation(project(":integrations:core"))
  // Plan 7 Task 10. The requests screen and the integrations screen `MuPlayApp` hosts, the two
  // `NavKey`s they are reached by, and the `IntegrationsPresenceViewModel` that decides whether the
  // first of them is registered at all. `:app` is the only module that composes them. Note what
  // this edge is NOT for: the always-present settings row arrives through an `@IntoSet` binding on
  // `SettingsSection`, so nothing in `:app` names it and deleting `feature/requests/` takes it away.
  implementation(project(":feature:requests"))

  implementation(libs.activity.compose)
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  debugImplementation(libs.compose.ui.tooling)

  // Navigation 3, not Navigation Compose — androidx.navigation:navigation-compose must never be
  // added alongside these.
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)
  // Backs SetupRoute's @Serializable NavKey — rememberNavBackStack saves the back stack via
  // rememberSaveable, which needs a KSerializer for each key.
  implementation(libs.serialization.json)
  // The app is where the image loader is built (MuPlayApplication), so this is the one module
  // that needs Coil's base ImageLoader/SingletonImageLoader API and its OkHttp network fetcher
  // as direct dependencies -- neither is exposed transitively by :feature:library's own
  // implementation-scoped coil-compose dependency.
  implementation(libs.coil)
  implementation(libs.coil.network.okhttp)

  // `MuPlayApp` now hosts a ViewModel and collects a StateFlow, neither of which this module
  // depended on before: it was a pure navigation shell.
  implementation(libs.hilt.navigation.compose)
  implementation(libs.lifecycle.runtime.compose)

  // `StartDestinationViewModelTest` drives `viewModelScope` -- which is `Dispatchers.Main` -- so it
  // needs `Dispatchers.setMain` and a scheduler to advance. This is the first JVM test in `:app`
  // that touches a coroutine at all; the other three (`ConventionTest`, `StoreListingTest`,
  // `MuPlayApplicationTest`) are plain file and reflection scans.
  testImplementation(libs.coroutines.test)

  // Tier 2 (.github/workflows/e2e.yml): FirstRunJourneyTest drives the real app on a real
  // emulator against a real Navidrome container. `compose-ui-test-junit4` supplies
  // `createAndroidComposeRule` and the finder/assertion API; `androidx-test-ext` supplies the
  // `AndroidJUnit4` runner class the test is annotated with, and `androidx-test-runner` the
  // `AndroidJUnitRunner` instrumentation itself declares as `testInstrumentationRunner` (see
  // `configureKotlinAndroid` in build-logic). Only `compose-ui-test-junit4` is version-less --
  // it is resolved by the Compose BOM `muplay.android.compose` already adds to
  // `androidTestImplementation`; the two AndroidX test artifacts carry their own catalogue
  // versions.
  //
  // JUnit 4, not this project's JUnit 5: `createAndroidComposeRule` is a JUnit 4 `TestRule`, and
  // AndroidJUnitRunner runs JUnit 4 — unavoidable on-device, and harmless, since the ban that
  // matters here is on mock frameworks (`ConventionTest`), not on JUnit 4. It arrives
  // transitively through the two AndroidX test artifacts above rather than being declared here,
  // so nothing in this file pins a JUnit 4 version of its own.
  androidTestImplementation(libs.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  // Declared, not left transitive: `compose-ui-test-junit4` drags in espresso-core 3.5.0, which
  // cannot run at all on an API 37 device -- see this entry's own note in libs.versions.toml for
  // the exact failure. Nothing in this module calls Espresso directly; Compose's own
  // `waitForIdle` does, via `Espresso.onIdle`.
  androidTestImplementation(libs.androidx.test.espresso)
  // ActivityScenario and ApplicationProvider for the journeys.
  androidTestImplementation(libs.androidx.test.core)

  // `MuPlaybackServiceTest` and `PlaybackJourneyTest`. The service is `@AndroidEntryPoint`, so it
  // can only be started from an application that is `@HiltAndroidApp` -- which is this module and
  // no other. See that test's own documentation for why it cannot live in `:core:media`, and
  // `Jacoco.kt`'s `mergedExecutionData` for why its coverage still lands there. The journey uses
  // the same edge for `PlaybackConnection` (a controller on the app's own live session, for the
  // position while the app is backgrounded) and `PlaybackNotification`'s channel identity.
  androidTestImplementation(project(":core:media"))
  // The test lists the seeded tracks off the real container to know what titles to expect.
  androidTestImplementation(project(":core:network"))
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.assertj)

  // Plan 4 Task 10. `AudiobookResumeJourneyTest` and `AudiobookChapterJourneyTest` read the seeded
  // corpus -- book titles, chapter titles and chapter boundaries -- out of `BookFixtures`, which
  // parses `ci/probe-chapters.sh`'s ffprobe-derived table. Derived rather than written down: a
  // hardcoded corpus fact is what turned this repository's whole device tier red the day a fourth
  // music fixture landed, and `ci/probe-chapters.sh --check` runs in both CI tiers so the table
  // cannot rot silently.
  //
  // The `exclude` is `:core:media`'s, for the reason its own build file records: `:core:testing`
  // carries `OpenApiFixtureValidator`, whose `implementation(libs.openapi.validator)` stays on a
  // consumer's *runtime* classpath and drags swagger-parser, jackson and their duplicated
  // `META-INF` entries into this APK -- the same shape as the `mockwebserver3-junit5`
  // duplicate-LICENSE.md failure `CLAUDE.md` records. Nothing here references that validator, and
  // excluding its root artifact takes the whole subtree with it.
  androidTestImplementation(project(":core:testing")) {
    exclude(group = "com.atlassian.oai", module = "openapi-request-validator-core")
  }
  // `AudiobookResumeJourneyTest` asks Navidrome directly whether it holds a bookmark or a saved
  // play queue for the book that was just listened to. Declared here because okhttp is
  // `implementation` in `:core:network` and therefore not on this source set's classpath -- and it
  // has to be a client of the test's own anyway: a question asked through `SubsonicClient` would be
  // answered by the same code the assertion is about.
  androidTestImplementation(libs.okhttp)
}
