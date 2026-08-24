# MuPlay Kotlin Plan 2 — Library Mirror + Browse, with Library-Scoped Shuffle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local Room mirror of the Navidrome library that can be browsed and searched
offline, with each library tagged **Music** or **Audiobooks** by the user — and
**library-scoped shuffle**, the feature no existing client offers: random playback restricted
to one Navidrome library, so hitting shuffle in a music session can never surface chapter 14
of a novel.

**Architecture:** A new `:core:database` module owns Room, the credential store, and every
repository — per the constraints, *repositories are the only entry point to data* and there is
no domain layer. `:core:network` gains the browse commands and a narrow `SubsonicSource`
port so a test can inject a mid-reconcile failure without a mock framework. `:feature:library`
owns the browse UI in Compose; `:feature:setup` gains the library-role tagging step. Sync is a
**full reconcile** triggered by `getScanStatus`'s monotonic `lastScan` watermark, because
Subsonic has no real delta primitive and never reports deletions.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.1, **KSP** (never KAPT), Room 2.8.4, Hilt 2.60.1,
DataStore 1.2.1 + Android Keystore, Coil 3.5.0, Compose BOM 2026.08.00, Navigation 3, Retrofit
+ kotlinx.serialization over OkHttp 5.5.0, JUnit 5 (JVM) / JUnit 4 (device), AssertJ, Turbine,
JaCoCo 0.8.12.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Supersedes:** `docs/superpowers/plans/2026-08-22-muplay-02-library-browse.md` (the Java-era
Plan 2). Its task decomposition survives; every line of its Java does not.

---

## Global Constraints

Copied from `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` and the spec. Every
task inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI. `.kts` build scripts.
- Licence **MIT**. No GPL code may be copied.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Play requires 36 from 2026-08-31.
- **KSP only. KAPT is dead** and KSP1 has been removed upstream.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow`, collected with `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer** unless logic is genuinely
  shared across features.
- Convention plugins in `build-logic/convention`. No copy-pasted build scripts.
- Subsonic client identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- Stream requests force **`format=raw` or `format=mp3`**. **Never Opus.** (No streaming in this
  plan — Plan 3.)
- Media3 cache keys derive from the **track id alone** via `setCustomCacheKey`. (No Media3 in
  this plan — Plan 3. The same *principle* binds Coil's cover-art cache key here; see Task 9.)
- Book positions are **local only**.
- **No mock frameworks.** Fakes only, and only where the real thing cannot run.
- **Coverage ≥ 90% per module**, generated code excluded, enforced by **JaCoCo** merging JVM and
  emulator execution data (Kover cannot collect instrumented coverage). The metric differs by
  kind of code: **branch** coverage for non-UI code, **line** coverage for Compose UI, because
  the Compose compiler emits synthetic branches inside author method bodies that no test can
  reach and no class-level exclusion can filter. Every floor is measured, never invented, and
  **must be able to fail**; a module with no floor entry warns loudly.
- **Two-tier merge gate, both required.** Tier 1 ≤ 10 minutes with a real Navidrome container
  but no emulator. **Tier 2 is emulator end-to-end and must be green to merge.**
- Inject a `Clock`; no direct wall-clock reads outside the injection point.

### Definition of done, per plan

1. All tasks' tests pass; both tiers green.
2. **Tier 2 carries this plan's E2E journeys.** A plan is not done until its journeys are in the
   emulator suite.
3. Coverage ≥ 90% on every module the plan touches — **branch** for non-UI code, **line** for
   Compose UI. Every floor measured, and able to fail.
4. No mock framework has entered the dependency graph.
5. Every new external-API assumption is backed by a contract test against the vendored OpenAPI
   spec, or a live test against the Navidrome container.
6. Anything discovered to be wrong in the spec is corrected **in the spec**.

---

## What Plan 1 shipped, and what it left this plan to decide

Read the code, not this summary — but do not re-derive any of it.

- `:core:network` is a **plain Kotlin/JVM module** (`muplay.jvm.library`), no Android
  framework. `SubsonicClient(credentials, api = buildApi(credentials.baseUrl))` builds its own
  Retrofit with `Json { ignoreUnknownKeys = true }`; its private `call { }` helper maps
  `HttpException` → `SubsonicHttpException` and a body with `error != null || status == "failed"`
  → `SubsonicErrorException`, and lets everything else (transport, parse) propagate untouched.
- `SubsonicAuth.authParams(credentials, salt)` returns `u`/`t`/`s`/`v`/`c`/`f`.
  `SubsonicAuth.CLIENT_NAME = "MuPlay"`, `PROTOCOL_VERSION = "1.16.1"`.
- `:core:model` has `SubsonicCredentials`, `ServerInfo`, `ServerCapabilities`, `MusicLibrary`,
  `LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }`.
- `OpenApiFixtureValidator.assertValid(endpointPath, jsonBody)` in `:core:testing` validates a
  body against the vendored OpenSubsonic spec. Path is the spec's literal key,
  `/rest/<operationId>`. Fixtures live in `core/testing/src/main/resources/fixtures/`, kebab-case.
- `configureKotlinAndroid` (build-logic) already sets `testInstrumentationRunner` and
  `enableAndroidTestCoverage` for **every** Android module, so a new module gets instrumented
  coverage without touching either.
- `Jacoco.kt`'s `mergedExecutionData` already globs **every** project's
  `build/outputs/code_coverage/**/*.ec`, and `connectedTestTasks()` already orders against every
  project's `connectedDebugAndroidTest`. A new Android module with an `androidTest` source set
  needs no change to that machinery.

### The four things Plan 1 handed this plan (`.superpowers/sdd/2026-08-22-muplay-k01-foundation/plan-2-inherited.md`)

1. **Request-contract coverage.** Plan 1's final review proved by mutation that `authParams()`
   returning an empty map — zero credentials on the wire — left all 81 JVM tests green at 100%
   branch coverage. It closed this for `ping`, `getMusicFolders` and
   `getOpenSubsonicExtensions` only. **This plan adds six commands and every one needs the same
   treatment** — Tasks 3 and 7 do it, and `musicFolderId` is the reason it is not optional
   polish: it is a *request* parameter, and it is the only mechanism library-scoped shuffle has.
2. **The Hilt decision.** Hilt is applied with zero participants. **Task 1 decides it** — and
   decides that it earns its place (see Task 1's ruling).
3. **The resolved-classpath mock guard.** Built in Task 10.
4. **`SubsonicResponseBody` widening**, the `api`-vs-`implementation` audit, the
   `musicFolder: []` gap, and duplicate extension names. Tasks 3 and 10 close them.

### Hard-won facts from Plan 1 and from live probes of the pinned container

Every claim below was re-verified against a running `deluan/navidrome:0.63.2` while this plan
was written. Each one is repeated at the task that trips over it.

- **Navidrome hardcodes `child.Type = "music"`** for every song. Confirmed live: the seeded
  `Test Book.m4b` comes back with `"type": "music"`, `"mediaType": "song"`. **A Navidrome server
  will never tell a client that something is an audiobook.** `LibraryRole` is therefore an
  out-of-band decision the user makes, never an inference from a response.
- **No response carries a library id.** `AlbumID3`, `ArtistID3` and `Child` have no
  `musicFolderId` property in the vendored spec, and Navidrome sends none. The library a mirror
  row belongs to is knowable **only from the scoped request that returned it** — stamp it from
  the request.
- **The `musicFolderId` scoping trap is a parse trap, and it is worse than the spec says.**
  Measured live on 0.63.2 against `getArtists`, `getIndexes`, `getAlbumList2`, `search3` **and
  `getRandomSongs`**:
  - a *valid* numeric id scopes correctly;
  - an *unknown numeric* id (`0`, `-1`, `99`) fails **closed** — `status: "failed"`, error code
    70, "Library 99 not found or not accessible";
  - a **non-numeric or empty** id (`abc`, `""`, `1abc`) is **silently ignored**: `status: "ok"`
    and the scope widens to **every** library. The audiobook appears in a "music" shuffle.
  This is the silent-wrong-answer failure class. The countermeasure is structural: this plan's
  scoped client methods take a non-null `Int`, so no call site can produce a blank or
  unparseable value, and Task 7 pins the widening behaviour with a committed live assertion.
- **`getRandomSongs` caps `size` at 500.** Ask for more and you silently get 500.
- Subsonic **returns HTTP 200 even for errors**; the failure lives in the body as
  `"status":"failed"` with an `error` object.
- Client id must be **`c=MuPlay`**, protocol `v=1.16.1`. Navidrome strips the OpenSubsonic field
  block for client ids matching its `LegacyClients` (`DSub`) / `MinimalClients` (`SubMusic`)
  defaults.
- **Room 2.8.x**, not Room 3 (Kotlin-codegen-only). **KSP, never KAPT.**
- The live container is `deluan/navidrome:0.63.2` with libraries `Music` (id 1) and
  `Audiobooks` (id 2); `ci/configure-libraries.sh` seeds them. Library 1 is path-pinned and
  undeletable.
- **Three live Navidrome responses do not validate against the vendored OpenAPI spec.**
  Measured by running the vendored spec through `OpenApiInteractionValidator` with
  `withResolveCombinators(true)` — the exact configuration `OpenApiFixtureValidator` uses —
  against bodies captured from the container:
  - `getAlbumList2`, `getAlbum`, `search3`: rejected solely because Navidrome sends
    `"userRating": 0` for an unrated album while `AlbumID3.userRating` is `minimum: 1,
    maximum: 5`. Deleting that one field from the capture makes all three validate.
  - `getScanStatus`: rejected because Navidrome adds `folderCount`, `lastScan`, `scanType`
    and `elapsedTime`, none of which the vendored `ScanStatus` schema declares — and `lastScan`
    is the field this plan's whole sync design rests on.
  - `getArtists`: rejected because Navidrome adds `lastModified` to the `artists` object.
  - `getRandomSongs` validates **cleanly** as captured.
  **Do not edit a fixture to make it pass.** Task 3 commits the captures as they came off the
  wire and commits an *assertion* that names each deviation.

### Two global constraints that are satisfied by not doing anything

- **"Inject a `Clock`; no direct wall-clock reads outside the injection point."** Nothing in this
  plan reads a wall clock. The sync watermark is the server's own `lastScan` token, compared for
  equality and never parsed as a time; `MediaProgressEntity.lastPlayedAtEpochMs` is written by no
  code in this plan. So there is no `Clock` to inject yet, and adding one for a caller that does
  not exist would be speculative. **The first task in any plan that writes a timestamp injects a
  `Clock` at that point** — and `ConventionTest` is where a ban on `System.currentTimeMillis()`
  would go if one is ever wanted.
- **"Stream requests force `format=raw` or `format=mp3`. Never Opus."** and **"Media3 cache keys
  derive from the track id alone."** No stream request and no Media3 exist in this plan. The
  cache-key *principle* does bind Coil's cover art, which Task 9 handles.

### Scope discipline

> **Task 11 was added after this plan was written, and nothing was renumbered.** A spec-coverage
> audit (`.superpowers/sdd/2026-08-24-muplay-k02-library-browse/spec-coverage-audit.md`) found spec
> §9's `feature/settings` module owned by no plan — Plan 7 Task 10 says so in as many words and
> routes around it — which leaves a user who mis-tags a library at first run with no way back. The
> ruling put it here, because this plan owns library roles. It arrives as **Task 11, after the
> gates task**, because Plans 3 through 7 reference this plan's tasks by number and four of them
> are outside that repair's editable set; renumbering would have silently redirected every one of
> those references. Task 11 therefore carries its own journey, coverage floors and module
> registration rather than reaching back into Task 10's.

Plan 2 is library mirror + browse + library-scoped shuffle. **Playback is Plan 3.** No Media3,
no `MediaLibraryService`, no `MuPlayer`, no streaming URLs. If you find yourself specifying
Media3, stop. The one exception is the `media_progress` table itself (Task 1) — spec §3 calls it
the core architectural decision, and its defining property ("progress for one item survives
playing another") is testable today with a DAO and no player.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | **modify** — include `:core:database`, `:feature:library` |
| `gradle/libs.versions.toml` | **modify** — Room, DataStore, Coil, `androidx.test:core` |
| `build-logic/convention/build.gradle.kts` | **modify** — register `muplay.android.room` |
| `build-logic/convention/src/main/kotlin/AndroidRoomConventionPlugin.kt` | **new** — Room via KSP, schema export, `room-testing` |
| `build-logic/convention/src/main/kotlin/Jacoco.kt` | **modify** — exclude Room's generated `*_Impl` from coverage |
| `build.gradle.kts` | **modify** — coverage floors for the new modules, `ConventionTest` input patterns, `verifyNoMockFrameworks` |
| `app/src/test/kotlin/app/muplay/ConventionTest.kt` | **modify** — every Gradle project must have a coverage-floor entry |
| `core/model/src/main/kotlin/app/muplay/model/Album.kt` | **new** — `Album`, `AlbumWithSongs` |
| `core/model/src/main/kotlin/app/muplay/model/Artist.kt` | **new** — `Artist` |
| `core/model/src/main/kotlin/app/muplay/model/Song.kt` | **new** — `Song` |
| `core/model/src/main/kotlin/app/muplay/model/SearchResults.kt` | **new** — `SearchResults` |
| `core/model/src/main/kotlin/app/muplay/model/ScanStatus.kt` | **new** — `ScanStatus` (`lastScan` is an opaque token) |
| `core/model/src/main/kotlin/app/muplay/model/AlbumListType.kt` | **new** — the `type` values this client sends |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicApi.kt` | **modify** — six new endpoints |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt` | **modify** — typed browse/shuffle/scan commands, cover-art URL |
| `core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt` | **modify** — album/artist/child/scan DTOs |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt` | **new** — the one port every repository depends on, plus its factory |
| `core/testing/src/main/resources/fixtures/*.json` | **new** — six responses captured from the live container |
| `core/database/build.gradle.kts` | **new** — Room + Hilt + DataStore |
| `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt` | **new** — `@Database` v1, `exportSchema = true` |
| `core/database/src/main/kotlin/app/muplay/database/entity/MediaProgressEntity.kt` | **new** — the one progress table (spec §3) |
| `core/database/src/main/kotlin/app/muplay/database/entity/LibraryEntity.kt` | **new** — `musicFolderId` + name + user-assigned role |
| `core/database/src/main/kotlin/app/muplay/database/entity/ArtistEntity.kt` | **new** — mirror row, carries `libraryId` |
| `core/database/src/main/kotlin/app/muplay/database/entity/AlbumEntity.kt` | **new** — mirror row, carries `libraryId` |
| `core/database/src/main/kotlin/app/muplay/database/entity/SongEntity.kt` | **new** — mirror row, carries `libraryId` |
| `core/database/src/main/kotlin/app/muplay/database/entity/SyncWatermarkEntity.kt` | **new** — one row, the last committed `lastScan` |
| `core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt` | **new** — position read/write, used by Plan 4 |
| `core/database/src/main/kotlin/app/muplay/database/dao/LibraryDao.kt` | **new** — role read/write, role-preserving merge |
| `core/database/src/main/kotlin/app/muplay/database/dao/BrowseDao.kt` | **new** — scoped artist/album/song queries, search, transactional replace |
| `core/database/src/main/kotlin/app/muplay/database/dao/SyncWatermarkDao.kt` | **new** — watermark read/write |
| `core/database/src/main/kotlin/app/muplay/database/KeystoreCipher.kt` | **new** — AES-GCM seal/open |
| `core/database/src/main/kotlin/app/muplay/database/CredentialStore.kt` | **new** — Keystore key + DataStore ciphertext |
| `core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt` | **new** — domain → entity, artists derived from albums |
| `core/database/src/main/kotlin/app/muplay/database/SyncDecision.kt` | **new** — the pure watermark ruling |
| `core/database/src/main/kotlin/app/muplay/database/SyncState.kt` | **new** — sealed result of a sync attempt |
| `core/database/src/main/kotlin/app/muplay/database/SyncEngine.kt` | **new** — `getScanStatus` watermark + full reconcile |
| `core/database/src/main/kotlin/app/muplay/database/LibraryRepository.kt` | **new** — libraries and their roles |
| `core/database/src/main/kotlin/app/muplay/database/BrowseRepository.kt` | **new** — the mirror, as Flows |
| `core/database/src/main/kotlin/app/muplay/database/ShuffleRepository.kt` | **new** — library-scoped shuffle + the local scope guard |
| `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` | **new** — the Hilt module: database, DAOs, client factory |
| `feature/setup/src/main/kotlin/app/muplay/setup/*` | **modify** — Hilt, and the library-role tagging step |
| `feature/library/build.gradle.kts` | **new** |
| `feature/library/src/main/kotlin/app/muplay/library/LibraryUiState.kt` | **new** |
| `feature/library/src/main/kotlin/app/muplay/library/LibraryViewModel.kt` | **new** |
| `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt` | **new** — albums, search, shuffle |
| `feature/library/src/main/kotlin/app/muplay/library/AlbumUiState.kt` | **new** |
| `feature/library/src/main/kotlin/app/muplay/library/AlbumViewModel.kt` | **new** |
| `feature/library/src/main/kotlin/app/muplay/library/AlbumScreen.kt` | **new** — one album's songs |
| `feature/library/src/main/kotlin/app/muplay/library/CoverArt.kt` | **new** — Coil request with a URL-independent cache key |
| `app/src/main/kotlin/app/muplay/MainActivity.kt` | **modify** — `@AndroidEntryPoint` |
| `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` | **modify** — library and album destinations |
| `app/src/main/kotlin/app/muplay/ui/navigation/LibraryRoute.kt` | **new** |
| `app/src/main/kotlin/app/muplay/ui/navigation/AlbumRoute.kt` | **new** |
| `app/src/androidTest/kotlin/app/muplay/BrowseJourneyTest.kt` | **new** — Tier 2 browse journey |
| `app/src/androidTest/kotlin/app/muplay/ScopedShuffleJourneyTest.kt` | **new** — Tier 2 scoped-shuffle journey |
| `app/src/androidTest/kotlin/app/muplay/FirstRunJourneyTest.kt` | **modify** — setup now ends in role tagging |
| `.github/workflows/pr.yml` | **modify** — mock-classpath guard step |
| `.github/workflows/e2e.yml` | **modify** — `:core:database:connectedDebugAndroidTest`, new journeys |
| `.github/workflows/openapi-drift.yml` | **new** (Task 10) — the nightly, non-blocking oracle drift check; the only thing here that is not a gate |
| `feature/settings/build.gradle.kts` | **new** (Task 11) — and pointedly **not** depending on `:core:cast`, `:core:media` or `:integrations:*` |
| `feature/settings/src/main/kotlin/app/muplay/settings/SettingsUiState.kt` | **new** (Task 11) — the sealed state and the pure builder |
| `feature/settings/src/main/kotlin/app/muplay/settings/SettingsViewModel.kt` | **new** (Task 11) — re-tagging, and the one operation that can lose data |
| `feature/settings/src/main/kotlin/app/muplay/settings/SettingsScreen.kt` | **new** (Task 11) — server, libraries, and whatever else is installed |
| `feature/settings/src/main/kotlin/app/muplay/settings/SettingsSection.kt` | **new** (Task 11) — a slot, not a preferences framework |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicAuthMethod.kt` | **new** (Task 11) — the seam spec §4 asks for, with one member |
| `core/database/src/main/kotlin/app/muplay/database/SyncEngine.kt` | **modify** (Task 11) — `resetForNewServer()`, which never touches `media_progress` |
| `app/src/main/kotlin/app/muplay/ui/navigation/SettingsRoute.kt` | **new** (Task 11) |
| `app/src/androidTest/kotlin/app/muplay/SettingsJourneyTest.kt` | **new** (Task 11) — the mis-tag, and the way back from it |

---

## Task 1: `:core:database`, Room 2.8.4 via KSP, and the Hilt ruling

**Files:**
- Create: `build-logic/convention/src/main/kotlin/AndroidRoomConventionPlugin.kt`
- Create: `core/database/build.gradle.kts`
- Create: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/MediaProgressEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `build-logic/convention/build.gradle.kts`,
  `build-logic/convention/src/main/kotlin/Jacoco.kt`, `build.gradle.kts`,
  `app/src/test/kotlin/app/muplay/ConventionTest.kt`, `app/build.gradle.kts`,
  `app/src/main/kotlin/app/muplay/MainActivity.kt`, `.github/workflows/e2e.yml`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/MediaProgressDaoTest.kt`
- Test: `app/src/test/kotlin/app/muplay/ConventionTest.kt` (new rule)

**Interfaces:**
- Consumes: nothing but the build conventions.
- Produces:
  - convention plugin id `muplay.android.room`
  - `abstract class MuPlayDatabase : RoomDatabase` with
    `abstract fun mediaProgressDao(): MediaProgressDao`
  - `MediaProgressEntity(mediaId: String, positionMs: Long, isFinished: Boolean,
    lastPlayedAtEpochMs: Long, speed: Float, skipSilence: Boolean, gainDb: Float)`
  - `MediaProgressDao.upsert(progress: MediaProgressEntity)`,
    `.find(mediaId: String): MediaProgressEntity?`, `.findAll(): List<MediaProgressEntity>`,
    `.recentlyPlayed(limit: Int): List<MediaProgressEntity>` — all `suspend`
  - Hilt object `DataModule` in `app.muplay.database.di`, `@InstallIn(SingletonComponent::class)`,
    `@Provides @Singleton fun provideDatabase(@ApplicationContext context: Context): MuPlayDatabase`
    and `@Provides fun provideMediaProgressDao(db: MuPlayDatabase): MediaProgressDao`
  - `MuPlayDatabase.DATABASE_NAME = "muplay.db"`

### Why this table is the whole point

Spec §3: *the queue is a list of pointers; progress is a property of the item.* Every other
player keeps one global "now playing position" that the next thing played overwrites — which is
exactly why the user cannot listen to music between two audiobook sessions without losing their
place. There is **one** progress table, keyed by the server's stable media id, and music and
audiobooks are two pointer lists over it. Nothing about queue membership may ever be stored
here: a `queuePosition` or `isInQueue` column inverts the design.

No player is built in this plan. The property this table exists for — *writing one item's
progress does not disturb another's* — is a property of the schema, and a DAO test proves it
today.

### The Hilt ruling — the decision `plan-2-inherited.md` asked this task to make

**Hilt stays, and from this task it has real participants.** The case is now concrete rather
than speculative:

- `MuPlayDatabase` is a process-singleton built from the application `Context`. Something has to
  own that lifetime; a hand-rolled service locator is the same object graph with worse tooling.
- Four repositories (Tasks 4, 5, 6 and 7) each need the database, the credential store and a
  Subsonic client factory. Wiring those by hand through composable parameters is exactly the
  drift the convention-plugin layer exists to prevent, one layer up.
- The spec's stack table names **Hilt via KSP** outright. Removing it would be a spec change,
  and nothing discovered in Plan 1 argues for one.

The cost is stated here rather than discovered later: `SetupViewModel`'s defaulted-lambda
constructor seam with `@JvmOverloads` — load-bearing today because `SetupScreen` uses the bare
`viewModel()` factory — is replaced by constructor injection in **Task 8**, which also updates
`FirstRunJourneyTest` and re-measures `:feature:setup`'s coverage floors. Two of those floors
(`app.muplay.setup.SetupViewModel*1` / `*2`) match the compiled default-lambda classes and will
match **nothing** afterwards, which is a vacuous floor — the exact defect
`UngatedClassChecker.warnVacuousFloors` was built to catch. Task 8 removes them.

`:core:network` deliberately does **not** apply Hilt: it is a plain Kotlin/JVM module with no
Android dependency, and it stays that way. `DataModule` in `:core:database` is what binds its
factory into the graph.

- [ ] **Step 1: Write the failing test**

`core/database/src/androidTest/kotlin/app/muplay/database/MediaProgressDaoTest.kt`:

```kotlin
package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented, not a JVM unit test, and not Robolectric — Robolectric is banned project-wide.
 * Room needs the Android framework's SQLite, so the strongest rung available is the real thing
 * on a real device: real Room codegen, real SQL, real SQLite. That puts this class in Tier 2's
 * emulator run, which is required to merge, and its execution data is what
 * `:core:database`'s instrumented coverage floors are measured from.
 */
@RunWith(AndroidJUnit4::class)
class MediaProgressDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: MediaProgressDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.mediaProgressDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun unknownMediaHasNoProgress() = runTest {
    assertThat(dao.find("does-not-exist")).isNull()
  }

  @Test
  fun progressRoundTrips() = runTest {
    dao.upsert(
      MediaProgressEntity(
        mediaId = "book-1",
        positionMs = 123_456L,
        isFinished = false,
        lastPlayedAtEpochMs = 1_000L,
        speed = 1.5f,
        skipSilence = true,
        gainDb = -3.5f,
      ),
    )

    val found = dao.find("book-1")
    assertThat(found).isNotNull
    assertThat(found!!.positionMs).isEqualTo(123_456L)
    assertThat(found.speed).isEqualTo(1.5f)
    assertThat(found.skipSilence).isTrue
    assertThat(found.gainDb).isEqualTo(-3.5f)
  }

  /**
   * The failure mode this schema exists to prevent, and the user's original complaint as a test:
   * playing a different item must not disturb the first item's position. A book keeps its place
   * across a music session because music's progress lives in a different row, not because
   * anything remembers to put it back.
   */
  @Test
  fun progressForOneItemSurvivesPlayingAnother() = runTest {
    dao.upsert(MediaProgressEntity("book-1", 900_000L, false, 1_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("song-1", 30_000L, false, 2_000L, 1.0f, false, 0f))

    assertThat(dao.find("book-1")!!.positionMs).isEqualTo(900_000L)
  }

  @Test
  fun upsertReplacesTheSameMediaId() = runTest {
    dao.upsert(MediaProgressEntity("book-1", 100L, false, 1L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("book-1", 200L, false, 2L, 1.0f, false, 0f))

    assertThat(dao.find("book-1")!!.positionMs).isEqualTo(200L)
    assertThat(dao.findAll()).hasSize(1)
  }

  @Test
  fun recentlyPlayedExcludesFinishedItemsAndOrdersByMostRecent() = runTest {
    dao.upsert(MediaProgressEntity("old", 1L, false, 1_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("new", 1L, false, 3_000L, 1.0f, false, 0f))
    dao.upsert(MediaProgressEntity("done", 1L, true, 9_000L, 1.0f, false, 0f))

    assertThat(dao.recentlyPlayed(limit = 10).map { it.mediaId })
      .containsExactly("new", "old")
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: FAIL — `Project 'database' not found in project ':core'`. The module does not exist.

- [ ] **Step 3: Register the module and its dependencies**

`settings.gradle.kts` — add below the existing includes:

```kotlin
include(":core:database")
```

`gradle/libs.versions.toml` — under `[versions]`, `room` is **already present** at `2.8.4`.
Add:

```toml
datastore = "1.2.1"
```

and under `[libraries]`:

```toml
room-runtime           = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler          = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing           = { module = "androidx.room:room-testing", version.ref = "room" }
datastore-preferences  = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-test-core     = { module = "androidx.test:core", version.ref = "androidxTest" }
```

**Room 2.8.x, not Room 3.** Room 3 is Kotlin-codegen-only and is not what this project's
spec pins. Verify every coordinate resolves before moving on, and **record every substitution**
as `plan said X, published is Y, used Y because Z` — the same discipline Plan 1's Task 1 used
when AGP and KSP both turned out to publish different coordinates than the plan named.

- [ ] **Step 4: Write the Room convention plugin**

`build-logic/convention/src/main/kotlin/AndroidRoomConventionPlugin.kt`:

```kotlin
import com.google.devtools.ksp.gradle.KspExtension
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.process.CommandLineArgumentProvider

/**
 * `muplay.android.room`: Room via **KSP**, never kapt (KSP1 is removed upstream and kapt is dead
 * for new projects). Applies the KSP plugin itself, so a module using this convention does not
 * also need `muplay.android.hilt`'s KSP wiring — applying both is harmless, since
 * `pluginManager.apply` is idempotent.
 *
 * Exports the schema to `<module>/schemas`. Without it the schema is invisible and every future
 * migration is unverifiable — and Room warns about it on every build, which is noise that
 * trains people to ignore warnings.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.google.devtools.ksp")

      extensions.configure<KspExtension> {
        // A CommandLineArgumentProvider, not a plain `arg("room.schemaLocation", path)`: a bare
        // string is an untracked absolute path, which makes every KSP task cache-miss on a
        // different checkout directory and silently non-relocatable. Declaring it as an
        // @InputDirectory-bearing provider is what Now in Android does, for the same reason.
        arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
        // Kotlin codegen rather than Java. Room 2.8 can emit either; this project has no Java.
        arg("room.generateKotlin", "true")
      }

      dependencies {
        add("implementation", libs.findLibrary("room-runtime").get())
        add("ksp", libs.findLibrary("room-compiler").get())
        add("androidTestImplementation", libs.findLibrary("room-testing").get())
      }
    }
  }

  /**
   * Declares the schema directory as a tracked input so KSP tasks stay relocatable and
   * up-to-date checks stay honest.
   */
  class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File,
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
  }
}
```

`build-logic/convention/build.gradle.kts` — register it alongside the existing plugins:

```kotlin
    register("androidRoom") {
      id = "muplay.android.room"
      implementationClass = "AndroidRoomConventionPlugin"
    }
```

`build-logic/convention/build.gradle.kts` already carries `implementation(libs.ksp.gradlePlugin)`,
so `KspExtension` is on build-logic's own classpath and needs no new dependency. The unused
import warning in `AndroidRoomConventionPlugin.kt` for `Provider` is not present — do not add
imports the file does not use.

- [ ] **Step 5: Exclude Room's generated code from coverage**

`build-logic/convention/src/main/kotlin/Jacoco.kt` — add to `generatedCodeExcludes`, after the
Hilt entries:

```kotlin
  // Room's KSP output. `MuPlayDatabase_Impl` and every `<Dao>_Impl` land inside this module's
  // own namespace package, so `debugClassesFileTree`'s namespace-scoped include picks them up
  // and no existing pattern removes them. They are generated code by exactly the same argument
  // the Hilt patterns above rest on: gating them would be gating Room's code generator, and
  // their branch count (nullable-column reads, cursor index lookups) would swamp this module's
  // own logic in every ratio.
  "**/*_Impl*.*",
```

- [ ] **Step 6: Write the module build file**

`core/database/build.gradle.kts`:

```kotlin
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
  androidTestImplementation(libs.assertj)
}
```

- [ ] **Step 7: Write the entity, the DAO and the database**

`core/database/src/main/kotlin/app/muplay/database/entity/MediaProgressEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single source of truth for "where was I in this item".
 *
 * There is exactly one of these tables. Music queues and audiobook queues are two pointer lists
 * over it, so switching from a book to music touches no row here — which is the entire reason a
 * book keeps its exact position across an intervening music session.
 *
 * [mediaId] is the server's stable id, never a rowid: a re-scan on the server must not orphan a
 * listener's progress.
 *
 * Nothing about queue membership belongs in this table. If you find yourself adding a
 * `queuePosition` or `isInQueue` column, the design has been inverted.
 *
 * [lastPlayedAtEpochMs] is milliseconds since the epoch rather than an `Instant`: spec §3 writes
 * the field as an `Instant`, but nothing in this plan writes this table, so adding a
 * `kotlinx-datetime` dependency and a Room type converter for a column no code sets would be
 * speculative. The plan that starts writing progress converts at its own boundary.
 */
@Entity(tableName = "media_progress")
data class MediaProgressEntity(
  @PrimaryKey val mediaId: String,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
  val speed: Float,
  val skipSilence: Boolean,
  val gainDb: Float,
)
```

`core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.muplay.database.entity.MediaProgressEntity

@Dao
interface MediaProgressDao {

  @Upsert
  suspend fun upsert(progress: MediaProgressEntity)

  @Query("SELECT * FROM media_progress WHERE mediaId = :mediaId")
  suspend fun find(mediaId: String): MediaProgressEntity?

  @Query("SELECT * FROM media_progress")
  suspend fun findAll(): List<MediaProgressEntity>

  @Query(
    "SELECT * FROM media_progress WHERE isFinished = 0 " +
      "ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit",
  )
  suspend fun recentlyPlayed(limit: Int): List<MediaProgressEntity>
}
```

`core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`:

```kotlin
package app.muplay.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity

/**
 * Version 1, and it stays version 1 through this plan: nothing has shipped, so every later task
 * in this plan adds its tables to this same `entities` list rather than writing a migration.
 * `exportSchema = true` (with the schema directory wired up by `muplay.android.room`) is what
 * makes the *first* post-release migration verifiable — a migration test needs the previous
 * schema JSON, and there is no way to recover one that was never exported.
 */
@Database(
  entities = [MediaProgressEntity::class],
  version = 1,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
```

- [ ] **Step 8: Write the Hilt module**

`core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`:

```kotlin
package app.muplay.database.di

import android.content.Context
import androidx.room.Room
import app.muplay.database.MuPlayDatabase
import app.muplay.database.dao.MediaProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one place the data layer's object graph is described. Every repository in this module
 * takes its collaborators through an `@Inject constructor`, so this module only has to provide
 * the things that are not themselves constructor-injectable: the Room database (built from the
 * application `Context`) and the DAOs it hands out.
 *
 * This is the module that gives Hilt its first real participants — see Task 1's ruling in the
 * plan for why it earns its place now rather than coming out.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): MuPlayDatabase =
    Room.databaseBuilder(context, MuPlayDatabase::class.java, MuPlayDatabase.DATABASE_NAME)
      .build()

  @Provides
  fun provideMediaProgressDao(database: MuPlayDatabase): MediaProgressDao =
    database.mediaProgressDao()
}
```

`app/build.gradle.kts` — add the module so the app's graph can see it:

```kotlin
  implementation(project(":core:database"))
```

`app/src/main/kotlin/app/muplay/MainActivity.kt` — add the annotation and its import:

```kotlin
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
```

Everything else in `MainActivity` is unchanged. Without `@AndroidEntryPoint` the
`hiltViewModel()` calls Tasks 8 and 9 add throw at runtime with a message about the activity not
being a Hilt entry point — which is a loud failure, not a silent one, but there is no reason to
meet it.

- [ ] **Step 9: Make an absent coverage floor a failing test, not a warning**

Today a module with no entry in `coverageFloors` produces a `logger.warn`, and Plan 1's own
record shows that warning disappearing entirely under configuration-cache reuse. A new module is
exactly when that matters, and this task adds one.

`app/src/test/kotlin/app/muplay/ConventionTest.kt` — add:

```kotlin
  @Test
  fun `every Gradle project has a coverage floor`() {
    // A module absent from `coverageFloors` is un-gated, and the build's own warning for it has
    // been shown to vanish under `--configuration-cache` reuse (Plan 1's sixth silent-gate
    // instance). A test that reads both files is invocation-mode-independent, so it cannot.
    val settings = File(repoRoot(), "settings.gradle.kts").readText()
    val rootBuild = File(repoRoot(), "build.gradle.kts").readText()

    val includedProjects = Regex("""^include\("(:[^"]+)"\)""", RegexOption.MULTILINE)
      .findAll(settings).map { it.groupValues[1] }.toList()

    // A scan that finds nothing is the failure mode every rule in this class guards against.
    assertThat(includedProjects).describedAs("projects included by settings.gradle.kts").isNotEmpty()

    val floored = Regex("""^\s*"(:[^"]+)" to listOf\(""", RegexOption.MULTILINE)
      .findAll(rootBuild).map { it.groupValues[1] }.toList()
    assertThat(floored).describedAs("entries in coverageFloors").isNotEmpty()

    assertThat(includedProjects)
      .describedAs("every module needs a measured floor in `coverageFloors` (build.gradle.kts)")
      .allMatch { it in floored }
  }
```

`build.gradle.kts` — the `project(":app")` block near the bottom declares
`scannedByConventionTest`, a hand-maintained mirror of what `ConventionTest` actually reads.
Its `include("**/build.gradle.kts")` already covers the root build script, but **nothing there
matches `settings.gradle.kts`**, which the new rule reads. Add one line to that `fileTree`:

```kotlin
        include("settings.gradle.kts")
```

A rule Gradle skips as UP-TO-DATE is the eighth silent gate this project found, and it was found
in this exact test class. Verify the declaration works rather than assuming it: run
`./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'` twice (the second must report
UP-TO-DATE), then add `include(":core:nonexistent")` to `settings.gradle.kts` and run the same
command a third time with no flags — it must **re-run and fail**, not report UP-TO-DATE. Revert
the injected line.

- [ ] **Step 10: Add the floor, measured**

Run `./gradlew :core:database:connectedDebugAndroidTest` (emulator up — see
`ci/prepare-emulator.sh`), then `./gradlew :core:database:jacocoTestReport`, then read the real
ratios out of `core/database/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`:

```bash
python3 - <<'PY'
import xml.etree.ElementTree as ET
t = ET.parse("core/database/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
for cls in t.getroot().iter("class"):
    for c in cls.findall("counter"):
        if c.get("type") in ("BRANCH", "LINE"):
            m, cv = int(c.get("missed")), int(c.get("covered"))
            if m + cv:
                print(cls.get("name"), c.get("type"), f"{cv}/{m+cv}", round(cv/(m+cv), 4))
PY
```

Add a `":core:database"` entry to `coverageFloors` in `build.gradle.kts` using `element = "CLASS"`
rules over the classes that report — **the number you write is the measured ratio rounded down a
little, never a round number you liked the look of.** A floor of `0.00`, or one whose matched
classes carry no counters of its own kind, passes at every minimum and gates nothing; this
project has already shipped that defect once. Set `requiresInstrumentedData = true` on any floor
whose classes measure ~0 from a plain `./gradlew :core:database:test` (all of them, at this
point — every class in the module is exercised only by the instrumented DAO test).

Then **prove the floor can fail**: delete one assertion from `MediaProgressDaoTest`, re-run the
instrumented test and `./gradlew :core:database:jacocoTestCoverageVerification`, and confirm it
goes red. Restore the assertion. A green light you have never seen go red is not evidence.

- [ ] **Step 11: Put the new instrumented tests in the Tier 2 gate**

`.github/workflows/e2e.yml` — inside the `script:` handed to
`reactivecircus/android-emulator-runner`, the emulator is alive only for that step, so the new
connected task has to run there too:

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

- [ ] **Step 12: Run everything and commit**

Run: `./gradlew build` then `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS — `ConventionTest` including the new rule, `MediaProgressDaoTest` 5/5.

```bash
git add settings.gradle.kts gradle/libs.versions.toml build.gradle.kts build-logic core/database app
git add .github/workflows/e2e.yml
git commit -m "feat(database): the single media_progress table, Room via KSP, Hilt earns its place"
```


---

## Task 2: Credential storage on the Android Keystore

**Files:**
- Create: `core/database/src/main/kotlin/app/muplay/database/KeystoreCipher.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/CredentialStore.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Test: `core/database/src/test/kotlin/app/muplay/database/KeystoreCipherTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/CredentialStoreTest.kt`

**Interfaces:**
- Consumes: `app.muplay.model.SubsonicCredentials(baseUrl: String, username: String, password: String)`
- Produces:
  - `object KeystoreCipher` with
    `seal(key: SecretKey, plaintext: String): ByteArray` and
    `open(key: SecretKey, sealed: ByteArray): String`, both throwing `GeneralSecurityException`
  - `class CredentialStore @Inject constructor(dataStore: DataStore<Preferences>)` with
    `suspend fun save(credentials: SubsonicCredentials)`,
    `suspend fun load(): SubsonicCredentials?`,
    `suspend fun clear()`, and
    `val credentials: Flow<SubsonicCredentials?>`
  - `DataModule.provideCredentialDataStore(@ApplicationContext context: Context): DataStore<Preferences>`

### Why not `EncryptedSharedPreferences`

It is deprecated wholesale. And the password is needed in **cleartext at request time** anyway —
Subsonic token auth computes `t = md5(password + salt)` with a fresh salt per request, so there
is no hashed-at-rest option available. The honest design is the one spec §4 names: an AES-GCM
key in the Android Keystore, the ciphertext in DataStore, decrypt on demand.

The key is deliberately **not** user-authentication-bound. Playback has to work from a locked
screen, and a key that needs an unlock would make Plan 3's background service fail in exactly
the situation it exists for.

**The cipher's contract is JVM-testable; the Keystore is not.** `KeystoreCipher` takes a
`SecretKey` as a parameter rather than fetching one, so its contract runs on the JVM against the
platform's software provider in Tier 1. `CredentialStore` — the Keystore alias lifecycle and
DataStore persistence — is proven on the real device in Tier 2, which is the only place a
hardware-backed keystore exists.

- [ ] **Step 1: Write the failing unit test**

`core/database/src/test/kotlin/app/muplay/database/KeystoreCipherTest.kt`:

```kotlin
package app.muplay.database

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A plain JVM test against the platform's software AES provider, which is the whole reason
 * [KeystoreCipher] takes a `SecretKey` instead of fetching one from `AndroidKeyStore`: the
 * *cryptographic contract* is testable in Tier 1, and only the key's storage needs a device.
 */
class KeystoreCipherTest {

  private fun key(): SecretKey =
    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @Test
  fun `round trips a non-ascii secret`() {
    val k = key()
    // A non-ASCII password is the case that breaks a charset-sloppy implementation, and this
    // project has a live user with a German-language server. Byte-for-byte UTF-8, both ways.
    val secret = "hunter2-Ünïcödé-🎵"

    assertThat(KeystoreCipher.open(k, KeystoreCipher.seal(k, secret))).isEqualTo(secret)
  }

  @Test
  fun `every seal uses a fresh iv`() {
    val k = key()

    val a = KeystoreCipher.seal(k, "same")
    val b = KeystoreCipher.seal(k, "same")

    // GCM with a reused IV under the same key is a catastrophic break — it leaks the XOR of the
    // two plaintexts and the authentication key — not a nitpick.
    assertThat(a).isNotEqualTo(b)
  }

  @Test
  fun `tampering with the ciphertext is detected`() {
    val k = key()
    val sealed = KeystoreCipher.seal(k, "secret")
    sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

    assertThatThrownBy { KeystoreCipher.open(k, sealed) }
      .isInstanceOf(GeneralSecurityException::class.java)
  }

  @Test
  fun `a different key cannot open it`() {
    val sealed = KeystoreCipher.seal(key(), "secret")

    assertThatThrownBy { KeystoreCipher.open(key(), sealed) }
      .isInstanceOf(GeneralSecurityException::class.java)
  }

  @Test
  fun `a blob too short to hold an iv is rejected by name`() {
    // Not a theoretical case: a truncated or corrupted DataStore value arrives here as a short
    // byte array, and the difference between a clear GeneralSecurityException and an
    // ArrayIndexOutOfBoundsException three frames down is the difference between a diagnosable
    // failure and a mystery.
    assertThatThrownBy { KeystoreCipher.open(key(), ByteArray(4)) }
      .isInstanceOf(GeneralSecurityException::class.java)
      .hasMessageContaining("too short")
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:test --tests '*KeystoreCipherTest*'`
Expected: FAIL — `Unresolved reference: KeystoreCipher`.

- [ ] **Step 3: Implement the cipher**

`core/database/src/main/kotlin/app/muplay/database/KeystoreCipher.kt`:

```kotlin
package app.muplay.database

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM sealing for credential material.
 *
 * The IV is generated fresh for every [seal] and prefixed to the ciphertext. Reusing an IV under
 * the same GCM key destroys both confidentiality and authenticity at once, so this is not a
 * detail — `everyy seal uses a fresh iv` in the test suite is the assertion that keeps it true.
 *
 * Takes a [SecretKey] rather than reaching for `AndroidKeyStore` itself, which is what makes the
 * whole contract testable in Tier 1; [CredentialStore] owns the key's lifetime.
 */
object KeystoreCipher {

  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private val random = SecureRandom()

  fun seal(key: SecretKey, plaintext: String): ByteArray {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    return iv + cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
  }

  fun open(key: SecretKey, sealed: ByteArray): String {
    if (sealed.size <= IV_BYTES) {
      throw GeneralSecurityException("sealed blob is too short to contain an IV")
    }
    val iv = sealed.copyOfRange(0, IV_BYTES)
    val ciphertext = sealed.copyOfRange(IV_BYTES, sealed.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
  }
}
```

Fix the typo in the doc comment (`everyy` → `every`) — it is written here so the implementer
reads the sentence rather than pasting it.

- [ ] **Step 4: Run the unit tests**

Run: `./gradlew :core:database:test --tests '*KeystoreCipherTest*'`
Expected: PASS, 5/5.

- [ ] **Step 5: Write the failing instrumented test**

`core/database/src/androidTest/kotlin/app/muplay/database/CredentialStoreTest.kt`:

```kotlin
package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.SubsonicCredentials
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing: the device's own `AndroidKeyStore` provider, a real DataStore file, a real
 * process. A JVM test cannot exercise a hardware-backed keystore at all, and the failure this
 * class exists to catch — a key that cannot be retrieved after the first process, or a `clear()`
 * that removes the DataStore entry but leaves the key behind — is invisible without one.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var store: CredentialStore

  private val credentials =
    SubsonicCredentials("http://localhost:4533", "admin", "Ünïcödé-pässwörd-🎵")

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = File(context.filesDir, "credential-store-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    store = CredentialStore(dataStore)
  }

  @After
  fun tearDown() = runTest {
    store.clear()
    file.delete()
  }

  @Test
  fun nothingIsStoredBeforeAnythingIsSaved() = runTest {
    assertThat(store.load()).isNull()
  }

  @Test
  fun credentialsRoundTripThroughTheRealKeystore() = runTest {
    store.save(credentials)

    assertThat(store.load()).isEqualTo(credentials)
  }

  /**
   * The property that makes this worth having at all: what lands on disk is not the password.
   * Reading the raw DataStore file and searching it for the plaintext is the only assertion that
   * actually proves encryption happened — a round trip alone would pass just as well against an
   * implementation that stored the password verbatim.
   */
  @Test
  fun thePasswordIsNotOnDiskInPlaintext() = runTest {
    store.save(credentials)

    val onDisk = file.readBytes().toString(Charsets.ISO_8859_1)
    assertThat(onDisk).doesNotContain("Ünïcödé-pässwörd-🎵")
    assertThat(onDisk).doesNotContain("pässwörd")
    // The non-secret half is deliberately stored in the clear, so this assertion also proves the
    // test is looking at the right file rather than at an empty one.
    assertThat(onDisk).contains("admin")
  }

  @Test
  fun savingAgainReplacesTheStoredCredentials() = runTest {
    store.save(credentials)
    val replacement = SubsonicCredentials("https://music.example", "alice", "sesame")

    store.save(replacement)

    assertThat(store.load()).isEqualTo(replacement)
  }

  @Test
  fun clearRemovesEverythingIncludingTheKey() = runTest {
    store.save(credentials)

    store.clear()

    assertThat(store.load()).isNull()
    // Not just the DataStore entry: the Keystore alias itself must be gone, or a "signed out"
    // device still holds the key that decrypts a backup of the ciphertext.
    assertThat(CredentialStore.keyExists()).isFalse
  }
}
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*CredentialStoreTest*'`
Expected: FAIL — `Unresolved reference: CredentialStore`.

- [ ] **Step 7: Implement `CredentialStore`**

`core/database/src/main/kotlin/app/muplay/database/CredentialStore.kt`:

```kotlin
package app.muplay.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.muplay.model.SubsonicCredentials
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The server URL, username and password the app connects with, persisted across launches.
 *
 * The URL and username are stored in the clear — they are not secrets, and having them readable
 * makes a support question answerable. The **password** is sealed with an AES-GCM key that lives
 * in the Android Keystore and never leaves it; only the ciphertext reaches DataStore.
 *
 * Storing the password at all, rather than a hash, is forced by the protocol: Subsonic token
 * auth needs `md5(password + salt)` with a **fresh salt per request**, so the plaintext must be
 * recoverable at request time. There is no hashed-at-rest option to choose instead.
 *
 * The key is not user-authentication-bound (`setUserAuthenticationRequired` is never called):
 * background playback must work from a locked screen, which is the whole point of the feature it
 * serves.
 */
@Singleton
class CredentialStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) {

  /** Emits the stored credentials, or `null` when nothing is stored or the blob cannot be opened. */
  val credentials: Flow<SubsonicCredentials?> = dataStore.data.map(::read)

  suspend fun save(credentials: SubsonicCredentials) {
    val sealed = KeystoreCipher.seal(secretKey(), credentials.password)
    dataStore.edit { preferences ->
      preferences[BASE_URL] = credentials.baseUrl
      preferences[USERNAME] = credentials.username
      preferences[SEALED_PASSWORD] = Base64.getEncoder().encodeToString(sealed)
    }
  }

  suspend fun load(): SubsonicCredentials? = credentials.first()

  /**
   * Forgets the credentials **and destroys the key**. Removing only the DataStore entry would
   * leave a key on the device that still opens any copy of the ciphertext — a backup, a forensic
   * image — which is not what "sign out" means to a user.
   */
  suspend fun clear() {
    dataStore.edit { it.clear() }
    val keyStore = androidKeyStore()
    if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
  }

  private fun read(preferences: Preferences): SubsonicCredentials? {
    val baseUrl = preferences[BASE_URL] ?: return null
    val username = preferences[USERNAME] ?: return null
    val sealed = preferences[SEALED_PASSWORD] ?: return null
    val keyStore = androidKeyStore()
    if (!keyStore.containsAlias(KEY_ALIAS)) return null
    val key = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    // A blob that will not open is indistinguishable, to a caller, from nothing being stored:
    // both mean "you have to log in again". Surfacing a GeneralSecurityException from a Flow
    // collected by the UI would crash the screen instead.
    return runCatching { KeystoreCipher.open(key, Base64.getDecoder().decode(sealed)) }
      .map { password -> SubsonicCredentials(baseUrl, username, password) }
      .getOrNull()
  }

  private fun secretKey(): SecretKey {
    val keyStore = androidKeyStore()
    if (keyStore.containsAlias(KEY_ALIAS)) {
      return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_SIZE_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "app.muplay.credentials"
    private const val KEY_SIZE_BITS = 256

    private val BASE_URL = stringPreferencesKey("server_base_url")
    private val USERNAME = stringPreferencesKey("server_username")
    private val SEALED_PASSWORD = stringPreferencesKey("server_sealed_password")

    private fun androidKeyStore(): KeyStore =
      KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    /** Whether the Keystore alias exists. Used by `CredentialStoreTest` to prove `clear()` means it. */
    fun keyExists(): Boolean = androidKeyStore().containsAlias(KEY_ALIAS)
  }
}
```

- [ ] **Step 8: Provide the DataStore**

`core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` — add, with the imports
`androidx.datastore.core.DataStore`, `androidx.datastore.preferences.core.Preferences`,
`androidx.datastore.preferences.core.PreferenceDataStoreFactory`:

```kotlin
  /**
   * One DataStore instance per process for this file. DataStore throws
   * `IllegalStateException: There are multiple DataStores active for the same file` if a second
   * one is created for the same path, so this being `@Singleton` is a correctness requirement,
   * not a performance choice.
   */
  @Provides
  @Singleton
  fun provideCredentialDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create {
      File(context.filesDir, "credentials.preferences_pb")
    }
```

with `import java.io.File` added to the file's imports.

- [ ] **Step 9: Run both suites**

Run: `./gradlew :core:database:test`
Expected: PASS, `KeystoreCipherTest` 5/5.

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS, `CredentialStoreTest` 5/5 and `MediaProgressDaoTest` 5/5.

- [ ] **Step 10: Re-measure the floors and commit**

Re-run `./gradlew :core:database:jacocoTestReport` and update `:core:database`'s entries in
`coverageFloors` with the new measured ratios. `KeystoreCipherKt`/`KeystoreCipher` is now
JVM-covered, so its floor must **not** carry `requiresInstrumentedData = true` — that flag is a
per-entry measurement, and marking a JVM-measurable floor as needing an emulator quietly moves a
security control's gate into the 45-minute tier while telling the reader it needs a device.

```bash
git add core/database build.gradle.kts
git commit -m "feat(database): keystore-backed credential storage"
```


---

## Task 3: Browse endpoints, DTOs, recorded fixtures and the request contract

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/Album.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/Artist.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/Song.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/SearchResults.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/ScanStatus.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/AlbumListType.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicApi.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicException.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt`
- Create: `core/testing/src/main/resources/fixtures/get-album-list2-music.json`
- Create: `core/testing/src/main/resources/fixtures/get-album-list2-audiobooks.json`
- Create: `core/testing/src/main/resources/fixtures/get-album-list2-empty.json`
- Create: `core/testing/src/main/resources/fixtures/get-album-with-songs.json`
- Create: `core/testing/src/main/resources/fixtures/search3-music.json`
- Create: `core/testing/src/main/resources/fixtures/get-random-songs-music.json`
- Create: `core/testing/src/main/resources/fixtures/get-scan-status.json`
- Test: `core/network/src/test/kotlin/app/muplay/network/BrowseEndpointsTest.kt`
- Test: `core/network/src/test/kotlin/app/muplay/network/NavidromeSpecDeviationTest.kt`

**Interfaces:**
- Consumes: `SubsonicAuth.authParams(credentials, salt)`, `SubsonicEnvelope`,
  `SubsonicResponseBody`, `SubsonicErrorException`, `SubsonicHttpException`,
  `OpenApiFixtureValidator.assertValid(endpointPath, jsonBody)`
- Produces:
  - `Album(id: String, libraryId: Int, name: String, artistId: String?, artistName: String?,
    coverArtId: String?, songCount: Int, durationSeconds: Int)`
  - `AlbumWithSongs(album: Album, songs: List<Song>)`
  - `Artist(id: String, libraryId: Int, name: String, coverArtId: String?, albumCount: Int)`
  - `Song(id: String, libraryId: Int, title: String, albumId: String?, albumName: String?,
    artistId: String?, artistName: String?, trackNumber: Int?, discNumber: Int?,
    durationSeconds: Int, suffix: String?, coverArtId: String?)`
  - `SearchResults(artists: List<Artist>, albums: List<Album>, songs: List<Song>)`
  - `ScanStatus(isScanning: Boolean, scannedCount: Int?, lastScan: String?)`
  - `enum class AlbumListType(val wireValue: String) { ALPHABETICAL_BY_NAME, NEWEST }`
  - `interface SubsonicSource` with
    `suspend fun ping(): ServerInfo`,
    `suspend fun getMusicFolders(): List<MusicLibrary>`,
    `suspend fun getScanStatus(): ScanStatus`,
    `suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album>`,
    `suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs`,
    `suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults`,
    `suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song>`,
    `fun coverArtUrl(coverArtId: String, sizePx: Int?): String`
  - `fun interface SubsonicSourceFactory { fun create(credentials: SubsonicCredentials): SubsonicSource }`
  - `object DefaultSubsonicSourceFactory : SubsonicSourceFactory`
  - `class SubsonicMalformedResponseException(val missingField: String) : Exception`
  - `SubsonicClient.MAX_RANDOM_SONGS = 500`, `SubsonicClient.MAX_ALBUM_LIST_PAGE = 500`
  - `SubsonicClient : SubsonicSource`

### The design decision that makes the whole plan work: the scope is not optional

**No Subsonic response carries a library id.** `AlbumID3`, `ArtistID3` and `Child` have no
`musicFolderId` property in the vendored spec, and Navidrome sends none — checked against both.
So `Album.libraryId`, `Artist.libraryId` and `Song.libraryId` are **stamped from the request**,
which is why `musicFolderId` is a **non-null `Int` parameter on every scoped method**, not a
`Int?` with a null default.

That is not stylistic. Measured live against `deluan/navidrome:0.63.2`:

| `musicFolderId` sent | `getAlbumList2` / `search3` / `getRandomSongs` / `getArtists` |
|---|---|
| `1` or `2` (real) | correctly scoped |
| `99`, `0`, `-1` (numeric, unknown) | **fails closed** — `status: "failed"`, error code 70 |
| `abc`, `1abc`, **empty string** | **silently ignored** — `status: "ok"`, scope widens to *every* library |

The last row is the silent-wrong-answer failure: a music shuffle that quietly includes
audiobooks. A `String` parameter, an `Int?` that renders as `""`, or an id read out of a
response would all reach it. A non-null `Int` rendered with `toString()` cannot. The type
system closes the hole; Task 7's live test proves the hole is real.

`getAlbum` is the exception that proves the rule: the spec gives it **only** an `id` parameter,
so its `musicFolderId` argument is *never sent* and exists purely to stamp the library the
caller already scoped by. The request test below asserts `musicFolderId` is **absent** from that
request — an assertion that fails if someone "helpfully" adds it.

### Why `SubsonicResponseBody` stays one flattened envelope

`plan-2-inherited.md` asked for this to be revisited once the command count grew. It has grown,
and the answer is: keep it. The spec models `subsonic-response` as a `oneOf` between a success
branch and `SubsonicFailureResponse`, and every success branch is `SubsonicBaseResponse` plus
exactly one payload field. One all-nullable DTO models that union with no custom polymorphic
deserializer; splitting it into six envelope types would mean six `@Serializable` classes whose
only difference is which single field is non-null.

The real hazard of the flattened shape is different and is closed here: a command whose payload
field is **absent** on an otherwise-successful response would decode to `null` and could be
mapped to "empty" instead of "wrong". So the mapping rule is explicit and asymmetric:

- `albumList2`, `searchResult3`, `randomSongs` absent → **empty result**. Legal and observed:
  `getAlbumList2` past the end of the list returns `"albumList2": {}` with no `album` key at all.
- `album` (from `getAlbum`) or `scanStatus` absent → **`SubsonicMalformedResponseException`**.
  Those payloads are the entire answer; "success with no answer" is not a state a caller can act
  on, and silently returning an empty album is how a mirror ends up deleting a real album.

`SubsonicMalformedResponseException` is deliberately **not** a member of the sealed
`SubsonicException` hierarchy. That hierarchy means "the server answered, on purpose"; a
success envelope with a missing payload is "we could not use the answer", which is the same
class as an unparseable body and propagates the same way.

- [ ] **Step 1: Record the fixtures from the live container**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh

Q='v=1.16.1&c=MuPlay&f=json&u=admin&p=testpass'
B=http://localhost:4533/rest
F=core/testing/src/main/resources/fixtures

curl -s "$B/getAlbumList2.view?$Q&type=alphabeticalByName&size=500&offset=0&musicFolderId=1" \
  | python3 -m json.tool > $F/get-album-list2-music.json
curl -s "$B/getAlbumList2.view?$Q&type=alphabeticalByName&size=500&offset=0&musicFolderId=2" \
  | python3 -m json.tool > $F/get-album-list2-audiobooks.json
curl -s "$B/getAlbumList2.view?$Q&type=alphabeticalByName&size=500&offset=99&musicFolderId=1" \
  | python3 -m json.tool > $F/get-album-list2-empty.json
curl -s "$B/search3.view?$Q&query=Test&musicFolderId=1" \
  | python3 -m json.tool > $F/search3-music.json
curl -s "$B/getRandomSongs.view?$Q&size=500&musicFolderId=1" \
  | python3 -m json.tool > $F/get-random-songs-music.json
curl -s "$B/getScanStatus.view?$Q" \
  | python3 -m json.tool > $F/get-scan-status.json

# getAlbum needs a real album id, which only the capture above knows.
ALBUM_ID=$(python3 -c "import json;print(json.load(open('$F/get-album-list2-music.json'))['subsonic-response']['albumList2']['album'][0]['id'])")
curl -s "$B/getAlbum.view?$Q&id=$ALBUM_ID" | python3 -m json.tool > $F/get-album-with-songs.json
```

**These are captures, not drafts.** Do not reformat, reorder, redact or "tidy" them beyond
`json.tool`'s indentation, and above all do not delete a field to make a validator happy — see
Step 2. The album and song ids are Navidrome's own base62 ids and will differ from any id
written in this plan; read them out of your own capture (`ALBUM_ID` above does exactly that) and
use those values in the test constants.

- [ ] **Step 2: Assert the oracle's verdict on every capture — including where it disagrees**

This is where "a demonstration is not a gate" bites. Three of these captures **do not validate**
against the vendored OpenSubsonic spec, and the useful artefact is not a note in a report — it
is a committed assertion that says exactly which field, so the day Navidrome or the vendored
spec changes, the build says so.

Measured while this plan was written, with the vendored spec and
`withResolveCombinators(true)` — i.e. `OpenApiFixtureValidator`'s own configuration:

| Capture | Verdict | Why |
|---|---|---|
| `get-random-songs-music.json` | **valid** | — |
| `get-album-list2-*.json`, `get-album-with-songs.json`, `search3-music.json` | **rejected** | Navidrome sends `"userRating": 0` for an unrated album; `AlbumID3.userRating` is `minimum: 1, maximum: 5`. Deleting only that field makes all of them validate. |
| `get-scan-status.json` | **rejected** | Navidrome adds `folderCount`, `lastScan`, `scanType`, `elapsedTime`; the vendored `ScanStatus` schema declares only `scanning` and `count`. |

Both disagreements are Navidrome deviating from the published schema, not the fixtures being
wrong — and `lastScan` is the one this plan's entire sync design rests on, which makes it worth
a permanent, named assertion rather than a footnote.

`core/network/src/test/kotlin/app/muplay/network/NavidromeSpecDeviationTest.kt`:

```kotlin
package app.muplay.network

import app.muplay.testing.OpenApiFixtureValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Where the real Navidrome and the vendored OpenSubsonic spec disagree, pinned.
 *
 * Every fixture this class names was captured from a running `deluan/navidrome:0.63.2` and is
 * committed **exactly as it came off the wire**. Three of them do not validate against the
 * vendored spec, and rather than editing the capture until the oracle is happy — which would
 * destroy the only external check this project has — each disagreement is asserted here by name.
 *
 * These assertions fail in both directions, which is the point. If Navidrome stops sending
 * `userRating: 0`, or the vendored spec is refreshed to model Navidrome's `scanStatus`
 * extensions, the `assertThatThrownBy` calls below go red and someone reads this file instead of
 * discovering the change six months later through a parsing bug.
 */
class NavidromeSpecDeviationTest {

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  @Test
  fun `getRandomSongs validates against the vendored spec exactly as captured`() {
    // The one browse capture with no deviation at all -- and, not coincidentally, the endpoint
    // the headline feature depends on. Asserted first so this class is not purely a list of
    // disagreements: the oracle does accept a real Navidrome response.
    OpenApiFixtureValidator.assertValid("/rest/getRandomSongs", fixture(RANDOM_SONGS_FIXTURE))
  }

  @Test
  fun `getAlbumList2 with no albums validates as captured`() {
    // A past-the-end offset returns `"albumList2": {}` -- the container present, the `album` key
    // absent entirely. Spec-legal, and the exact shape the reconcile paging loop terminates on.
    OpenApiFixtureValidator.assertValid("/rest/getAlbumList2", fixture(ALBUM_LIST_EMPTY_FIXTURE))
  }

  @Test
  fun `navidrome sends userRating 0 which the spec forbids`() {
    // AlbumID3.userRating is `minimum: 1, maximum: 5` ("The user rating of the album. [1-5]").
    // Navidrome sends 0 for an unrated album, which is not in that range. Every album-bearing
    // response is therefore rejected -- three endpoints, one cause.
    listOf(ALBUM_LIST_MUSIC_FIXTURE to "/rest/getAlbumList2",
           ALBUM_WITH_SONGS_FIXTURE to "/rest/getAlbum",
           SEARCH3_FIXTURE to "/rest/search3").forEach { (name, path) ->
      assertThatThrownBy { OpenApiFixtureValidator.assertValid(path, fixture(name)) }
        .describedAs(name)
        .isInstanceOf(AssertionError::class.java)
        .hasMessageContaining("minimum value of 1")
    }
  }

  @Test
  fun `stripping only userRating makes every album response validate`() {
    // The other half of the claim above, and the half that makes it actionable: `userRating` is
    // the *only* thing wrong with these three captures. Without this, "rejected" would be
    // consistent with any number of unnoticed deviations hiding behind the first one.
    listOf(ALBUM_LIST_MUSIC_FIXTURE to "/rest/getAlbumList2",
           ALBUM_WITH_SONGS_FIXTURE to "/rest/getAlbum",
           SEARCH3_FIXTURE to "/rest/search3").forEach { (name, path) ->
      OpenApiFixtureValidator.assertValid(path, withoutUserRating(fixture(name)))
    }
  }

  @Test
  fun `navidrome extends scanStatus with fields the spec does not model`() {
    // `lastScan` is the whole basis of this plan's sync design (spec section 4: "Navidrome
    // extends it with a monotonic lastScan"), and the vendored spec's ScanStatus schema has only
    // `scanning` and `count`. Asserting all four extension fields by name means a future spec
    // refresh that adds three of them still fails here rather than half-passing.
    assertThatThrownBy { OpenApiFixtureValidator.assertValid("/rest/getScanStatus", fixture(SCAN_STATUS_FIXTURE)) }
      .isInstanceOf(AssertionError::class.java)
      .hasMessageContaining("lastScan")
      .hasMessageContaining("folderCount")
      .hasMessageContaining("scanType")
      .hasMessageContaining("elapsedTime")
  }

  @Test
  fun `the captured scanStatus really does carry a lastScan token`() {
    // Not a shape assertion: the sync engine reads this exact field, so its presence in a real
    // capture is a precondition of the design, not a detail of the oracle's opinion about it.
    assertThat(fixture(SCAN_STATUS_FIXTURE)).contains("\"lastScan\"")
  }

  /**
   * The capture with every `userRating` key removed, wherever it appears. Textual, deliberately:
   * a JSON round-trip through a parser would also normalise key order and whitespace, and the
   * point of Step 4's assertion is that *only* this key differs.
   */
  private fun withoutUserRating(json: String): String =
    json.lineSequence().filterNot { it.trim().startsWith("\"userRating\"") }.joinToString("\n")
      // Removing a middle line leaves the previous line's trailing comma dangling only when the
      // removed line was last in its object; `json.tool` never emits `userRating` last for
      // Navidrome's field order, verified against the captures. If that ever changes this method
      // produces invalid JSON and every assertion using it fails loudly rather than silently.
      .also { check(it.contains("\"id\"")) { "the fixture filter removed more than it should" } }

  private companion object {
    const val ALBUM_LIST_MUSIC_FIXTURE = "get-album-list2-music.json"
    const val ALBUM_LIST_EMPTY_FIXTURE = "get-album-list2-empty.json"
    const val ALBUM_WITH_SONGS_FIXTURE = "get-album-with-songs.json"
    const val SEARCH3_FIXTURE = "search3-music.json"
    const val RANDOM_SONGS_FIXTURE = "get-random-songs-music.json"
    const val SCAN_STATUS_FIXTURE = "get-scan-status.json"
  }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :core:network:test --tests '*NavidromeSpecDeviationTest*'`
Expected: FAIL — `missing fixture: ...` if Step 1 has not been run, and otherwise a compile
failure only if the fixtures are absent. Once the fixtures exist this class passes with no
production code at all: it tests the oracle against the captures, not `SubsonicClient`. That is
correct — its subject is the fixtures.

- [ ] **Step 4: Correct the spec**

`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` §4, in the "Library scoping" block,
currently says:

> **Trap:** `getIndexes` and `getArtists` **discard the validation error** — an invalid
> `musicFolderId` silently returns **all** libraries. Never use those two to enforce a scope.

That is right about the danger and wrong about its shape, measured against 0.63.2. Replace it
with:

> **Trap:** `musicFolderId` is validated only when it *parses as a number*. An unknown numeric id
> (`0`, `-1`, `99`) fails closed on every command — `status: "failed"`, error code 70. A
> **non-numeric or empty** id (`abc`, `1abc`, `""`) is silently ignored and the response widens to
> **all** libraries with `status: "ok"` — on `getIndexes` and `getArtists` *and on
> `getAlbumList2`, `search3` and `getRandomSongs`*. Verified against `deluan/navidrome:0.63.2`.
> Silent-wrong-answer is the worst failure class, so the client's scoped methods take a non-null
> `Int` and no call site can produce an unparseable value.

Add to §10's "Tooling notes", or to §4, the three oracle deviations recorded in Step 2 — the
spec claims the OpenAPI validator is the external oracle for "every committed fixture", and it
is now known that three real Navidrome responses fail it for reasons that are Navidrome's, not
the fixtures'.

- [ ] **Step 5: Write the failing request-contract and mapping test**

`core/network/src/test/kotlin/app/muplay/network/BrowseEndpointsTest.kt`:

```kotlin
package app.muplay.network

import app.muplay.model.AlbumListType
import app.muplay.model.SubsonicCredentials
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Real HTTP over a real socket, through the real Retrofit + kotlinx.serialization stack, against
 * bodies captured from a real Navidrome — and, for every command, **an assertion on the request
 * this client actually sent**.
 *
 * That last part is why this class exists rather than a handful of response-mapping tests. Plan
 * 1's final review proved by mutation that `SubsonicAuth.authParams()` returning an empty map —
 * no credentials on the wire at all — left every test green at 100% branch coverage, because
 * nothing anywhere inspected a request. Coverage measures execution, not assertion. Six new
 * commands land here, and `musicFolderId` is not a nice-to-have parameter: it is the only
 * mechanism library-scoped shuffle has, and it is a *request* parameter. Omit it, mistype it, or
 * let it arrive empty and every response still parses, every mapping test still passes, and the
 * user's music shuffle quietly starts playing audiobook chapters.
 *
 * Protocol constants (`v`, `c`) are asserted as **literals**, never through
 * `SubsonicAuth.PROTOCOL_VERSION`/`CLIENT_NAME`: they are imposed from outside this codebase, and
 * reading them from the constant under test would let a change to that constant pass unnoticed.
 * `t` is recomputed here from the salt actually sent, so the assertion is on the bytes on the
 * wire rather than on `authParams()` agreeing with itself. This duplicates
 * `SubsonicClientTest`'s helper on purpose — sharing it would let one edit weaken both.
 */
class BrowseEndpointsTest {

  private lateinit var server: MockWebServer
  private lateinit var client: SubsonicClient

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = SubsonicClient(SubsonicCredentials(server.url("/").toString(), "alice", "sesame"))
  }

  @AfterEach
  fun tearDown() {
    server.close()
  }

  // --- getAlbumList2 -------------------------------------------------------------------------

  @Test
  fun `getAlbumList2 sends the scope, the type, the page and full authentication`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(musicFolderId = 1, type = AlbumListType.ALPHABETICAL_BY_NAME, size = 500, offset = 0)

    val url = assertAuthenticatedRequestTo("/rest/getAlbumList2")
    // The scope, on the wire, as a plain decimal integer. Navidrome silently ignores a
    // musicFolderId it cannot parse and widens the response to every library, so "" or "abc"
    // here would be a scope leak that no response assertion could ever catch.
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("type")).isEqualTo("alphabeticalByName")
    assertThat(url.queryParameter("size")).isEqualTo("500")
    assertThat(url.queryParameter("offset")).isEqualTo("0")
  }

  @Test
  fun `getAlbumList2 stamps every album with the library it was scoped to`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val albums = client.getAlbumList2(2, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0)

    // No Subsonic response carries a library id -- AlbumID3 has no such property and Navidrome
    // sends none. The only truthful source is the request's own scope, so this asserts the
    // stamp came from the argument (2) and not from anything in the body (which was captured
    // from library 1).
    assertThat(albums).isNotEmpty
    assertThat(albums).allMatch { it.libraryId == 2 }
  }

  @Test
  fun `getAlbumList2 maps the captured album fields`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    val album = client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).single()

    assertThat(album.name).isEqualTo("Test Album")
    assertThat(album.artistName).isEqualTo("Test Artist")
    assertThat(album.songCount).isEqualTo(3)
    assertThat(album.durationSeconds).isEqualTo(15)
    assertThat(album.id).isNotBlank
    assertThat(album.artistId).isNotBlank
    assertThat(album.coverArtId).isNotBlank
  }

  @Test
  fun `an albumList2 container with no album key maps to no albums, not to a failure`() = runTest {
    // Captured live from a past-the-end offset: `"albumList2": {}`. The reconcile paging loop in
    // the sync engine terminates on exactly this, so mapping it to an error would make a full
    // library sync impossible rather than merely wrong.
    enqueue(fixture(ALBUM_LIST_EMPTY_FIXTURE))

    assertThat(client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99)).isEmpty()
  }

  @Test
  fun `getAlbumList2 clamps its page size to the protocol maximum`() = runTest {
    enqueue(fixture(ALBUM_LIST_MUSIC_FIXTURE))

    client.getAlbumList2(1, AlbumListType.ALPHABETICAL_BY_NAME, size = 5_000, offset = 0)

    // Subsonic caps this at 500 and silently truncates, so a caller that asked for 5000 and
    // believed it would page straight past 4500 albums. Clamping in the client makes the number
    // on the wire and the number the caller reasons about the same one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  // --- getAlbum ------------------------------------------------------------------------------

  @Test
  fun `getAlbum sends only the album id and must not send a scope`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    client.getAlbum(albumId = "abc123", musicFolderId = 1)

    val url = assertAuthenticatedRequestTo("/rest/getAlbum")
    assertThat(url.queryParameter("id")).isEqualTo("abc123")
    // The spec gives getAlbum exactly one parameter. `musicFolderId` here is a *stamping*
    // argument -- the library the caller already scoped by -- and sending it would be inventing
    // a parameter the endpoint does not define.
    assertThat(url.queryParameter("musicFolderId")).isNull()
  }

  @Test
  fun `getAlbum maps the album and its songs and stamps both with the library`() = runTest {
    enqueue(fixture(ALBUM_WITH_SONGS_FIXTURE))

    val result = client.getAlbum("abc123", musicFolderId = 7)

    assertThat(result.album.libraryId).isEqualTo(7)
    assertThat(result.album.name).isEqualTo("Test Album")
    assertThat(result.songs).hasSize(3)
    assertThat(result.songs).allMatch { it.libraryId == 7 }
    assertThat(result.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(result.songs.map { it.trackNumber }).containsExactlyInAnyOrder(1, 2, 3)
    assertThat(result.songs).allMatch { it.suffix == "mp3" }
  }

  @Test
  fun `a successful getAlbum with no album payload is malformed, not empty`() = runTest {
    // `SubsonicResponseBody` is one flattened envelope, so every payload field is nullable and a
    // missing one decodes silently. For a list-shaped payload that means "no results"; for
    // getAlbum it means the server said "ok" and told us nothing, and mapping that to an empty
    // album is how a full reconcile deletes a real album's songs.
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThatThrownBy { client.getAlbum("abc123", 1) }
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("album")
  }

  // --- search3 -------------------------------------------------------------------------------

  @Test
  fun `search3 sends the query, the scope and the three counts`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    client.search3(query = "tra ck", musicFolderId = 1, artistCount = 5, albumCount = 10, songCount = 20)

    val url = assertAuthenticatedRequestTo("/rest/search3")
    // Read back through HttpUrl's own decoding, so a space that must be percent-encoded on the
    // wire is asserted as the value the server will actually see.
    assertThat(url.queryParameter("query")).isEqualTo("tra ck")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("artistCount")).isEqualTo("5")
    assertThat(url.queryParameter("albumCount")).isEqualTo("10")
    assertThat(url.queryParameter("songCount")).isEqualTo("20")
    // A raw space in a query string is not legal and OkHttp encodes it; asserting the encoded
    // form as well pins that this went out as one parameter rather than two.
    assertThat(request().url.encodedQuery).contains("query=tra%20ck")
  }

  @Test
  fun `search3 maps artists, albums and songs and stamps all three`() = runTest {
    enqueue(fixture(SEARCH3_FIXTURE))

    val results = client.search3("Test", musicFolderId = 3, artistCount = 5, albumCount = 5, songCount = 5)

    assertThat(results.artists.map { it.name }).contains("Test Artist")
    assertThat(results.albums.map { it.name }).contains("Test Album")
    assertThat(results.songs.map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2", "Track 3")
    assertThat(results.artists).allMatch { it.libraryId == 3 }
    assertThat(results.albums).allMatch { it.libraryId == 3 }
    assertThat(results.songs).allMatch { it.libraryId == 3 }
  }

  // --- getRandomSongs ------------------------------------------------------------------------

  @Test
  fun `getRandomSongs sends the scope and the size`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 50)

    val url = assertAuthenticatedRequestTo("/rest/getRandomSongs")
    assertThat(url.queryParameter("musicFolderId")).isEqualTo("1")
    assertThat(url.queryParameter("size")).isEqualTo("50")
  }

  @Test
  fun `getRandomSongs clamps size to the 500 Navidrome silently enforces`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 1_000)

    // Navidrome caps `size` at 500 and says nothing about it. A caller that asks for 1000 and
    // assumes it got 1000 is simply wrong; clamping here means the request and the caller's
    // model of it agree.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("500")
  }

  @Test
  fun `getRandomSongs clamps a non-positive size up to one`() = runTest {
    enqueue(fixture(RANDOM_SONGS_FIXTURE))

    client.getRandomSongs(musicFolderId = 1, size = 0)

    // `size=0` is not a documented value and Navidrome's behaviour for it is unknown; asking for
    // one song is the smallest well-defined request, and it keeps a caller's arithmetic error
    // from turning into an undefined server-side one.
    assertThat(nextRequest().url.queryParameter("size")).isEqualTo("1")
  }

  // --- getScanStatus -------------------------------------------------------------------------

  @Test
  fun `getScanStatus is a read and sends no scan-triggering parameter`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    client.getScanStatus()

    val url = assertAuthenticatedRequestTo("/rest/getScanStatus")
    // Tempo's getScanStatus calls startScan, re-triggering a full server scan on every poll
    // (spec section 4). This client polls this endpoint; it must never be the thing that makes
    // the server rescan. Asserting the path is `getScanStatus` and that no `fullScan` parameter
    // rides along is what keeps a future "convenience" from reintroducing it.
    assertThat(url.encodedPath).doesNotContain("startScan")
    assertThat(url.queryParameter("fullScan")).isNull()
  }

  @Test
  fun `getScanStatus maps navidrome's lastScan watermark`() = runTest {
    enqueue(fixture(SCAN_STATUS_FIXTURE))

    val status = client.getScanStatus()

    assertThat(status.isScanning).isFalse
    assertThat(status.scannedCount).isEqualTo(4)
    // An opaque token, never parsed as a date: all this client needs is "did it change".
    assertThat(status.lastScan).isNotNull
    assertThat(status.lastScan).isNotBlank
  }

  @Test
  fun `a successful getScanStatus with no scanStatus payload is malformed`() = runTest {
    enqueue(OK_WITH_NO_PAYLOAD)

    assertThatThrownBy { client.getScanStatus() }
      .isInstanceOf(SubsonicMalformedResponseException::class.java)
      .hasMessageContaining("scanStatus")
  }

  // --- cover art -----------------------------------------------------------------------------

  @Test
  fun `the cover art url carries full authentication and the art id`() {
    val url = client.coverArtUrl("al-abc_0", sizePx = 256).toHttpUrl()

    assertThat(url.encodedPath).isEqualTo("/rest/getCoverArt")
    assertThat(url.queryParameter("id")).isEqualTo("al-abc_0")
    assertThat(url.queryParameter("size")).isEqualTo("256")
    val salt = url.queryParameter("s")
    assertThat(salt).isNotNull.matches("[0-9a-f]{16}")
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
  }

  @Test
  fun `two cover art urls for the same art differ because the salt is fresh`() {
    // This is not a curiosity: it is the reason `:feature:library` must give Coil an explicit
    // cache key. Coil keys its memory and disk caches on the request URL by default, and a URL
    // that changes on every call can never hit either. Tempo shipped the same defect on Media3's
    // side, where the auth token was part of the cache key.
    assertThat(client.coverArtUrl("al-abc_0", null))
      .isNotEqualTo(client.coverArtUrl("al-abc_0", null))
  }

  @Test
  fun `the cover art url omits size when none is asked for`() {
    assertThat(client.coverArtUrl("al-abc_0", null).toHttpUrl().queryParameter("size")).isNull()
  }

  // --- shared helpers ------------------------------------------------------------------------

  private fun enqueue(body: String, code: Int = 200) {
    server.enqueue(
      MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build(),
    )
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
      .use { it.readBytes().decodeToString() }

  /** The single request this test made, asserted to carry the whole token-auth parameter set. */
  private fun assertAuthenticatedRequestTo(expectedPath: String): okhttp3.HttpUrl {
    val request = nextRequest()
    assertThat(request.method).isEqualTo("GET")
    val url = request.url
    assertThat(url.encodedPath).isEqualTo(expectedPath)

    val salt = url.queryParameter("s")
    assertThat(salt).describedAs("salt (s)").isNotNull.matches("[0-9a-f]{16}")
    assertThat(url.queryParameter("u")).isEqualTo("alice")
    assertThat(url.queryParameter("t")).isEqualTo(md5Hex("sesame" + salt))
    assertThat(url.queryParameter("v")).isEqualTo("1.16.1")
    assertThat(url.queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url.queryParameter("f")).isEqualTo("json")
    assertThat(url.queryParameter("p")).describedAs("plaintext password parameter").isNull()
    assertThat(url.query).describedAs("query string").doesNotContain("sesame")
    recorded = request
    return url
  }

  private var recorded: RecordedRequest? = null

  /** The request [assertAuthenticatedRequestTo] just examined, for assertions on its raw query. */
  private fun request(): RecordedRequest = checkNotNull(recorded) { "no request examined yet" }

  /**
   * The next request the client actually sent, or a failed assertion if it sent none.
   * Deliberately not the no-argument `takeRequest` overload: that blocks forever on an empty
   * queue, so the exact regression these assertions exist to catch — a code path that stops
   * issuing a request at all — would hang the build until CI's own timeout killed it.
   */
  private fun nextRequest(): RecordedRequest {
    val request = server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(request)
      .describedAs("a request within %d s -- the client sent none", REQUEST_TIMEOUT_SECONDS)
      .isNotNull()
    recorded = request
    return request!!
  }

  /** `hex(md5(utf8(input)))`, computed here rather than by calling `SubsonicAuth.token`. */
  private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
      .digest(input.toByteArray(StandardCharsets.UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte) }

  private companion object {
    const val REQUEST_TIMEOUT_SECONDS = 5L

    const val ALBUM_LIST_MUSIC_FIXTURE = "get-album-list2-music.json"
    const val ALBUM_LIST_EMPTY_FIXTURE = "get-album-list2-empty.json"
    const val ALBUM_WITH_SONGS_FIXTURE = "get-album-with-songs.json"
    const val SEARCH3_FIXTURE = "search3-music.json"
    const val RANDOM_SONGS_FIXTURE = "get-random-songs-music.json"
    const val SCAN_STATUS_FIXTURE = "get-scan-status.json"

    /**
     * A spec-valid success envelope with no command payload at all. Synthetic, and deliberately
     * so: no real Navidrome produces it. It exists to exercise the one hazard the flattened
     * `SubsonicResponseBody` introduces — a payload field that decodes to null.
     */
    const val OK_WITH_NO_PAYLOAD =
      """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
        """"serverVersion":"0.63.2 (be10f89c)","openSubsonic":true}}"""
  }
}
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `./gradlew :core:network:test --tests '*BrowseEndpointsTest*'`
Expected: FAIL — `Unresolved reference: getAlbumList2`, `AlbumListType`,
`SubsonicMalformedResponseException`, `coverArtUrl`.

- [ ] **Step 7: Write the domain models**

`core/model/src/main/kotlin/app/muplay/model/Album.kt`:

```kotlin
package app.muplay.model

/**
 * One album, as mirrored from a Subsonic `AlbumID3`.
 *
 * [libraryId] is **not** in any Subsonic response — `AlbumID3` has no such property and Navidrome
 * sends none. It is stamped by the network layer from the `musicFolderId` the request was scoped
 * to, which makes the scoped request the single source of truth about which library a row belongs
 * to. Every consumer that filters by library depends on that, including library-scoped shuffle.
 */
data class Album(
  val id: String,
  val libraryId: Int,
  val name: String,
  val artistId: String?,
  val artistName: String?,
  val coverArtId: String?,
  val songCount: Int,
  val durationSeconds: Int,
)

/** One album together with its tracks, as returned by `getAlbum`. */
data class AlbumWithSongs(
  val album: Album,
  val songs: List<Song>,
)
```

`core/model/src/main/kotlin/app/muplay/model/Artist.kt`:

```kotlin
package app.muplay.model

/**
 * One artist. [libraryId] is stamped from the scoped request, exactly as for [Album] — nothing in
 * an `ArtistID3` says which library the artist's music lives in.
 */
data class Artist(
  val id: String,
  val libraryId: Int,
  val name: String,
  val coverArtId: String?,
  val albumCount: Int,
)
```

`core/model/src/main/kotlin/app/muplay/model/Song.kt`:

```kotlin
package app.muplay.model

/**
 * One track, as mirrored from a Subsonic `Child`.
 *
 * There is deliberately no `contentKind`/`isAudiobook` property. Navidrome hardcodes
 * `child.Type = "music"` for **every** media file — confirmed against the real container, where
 * the seeded `Test Book.m4b` comes back as `"type": "music"`, `"mediaType": "song"` — so the
 * protocol simply cannot tell a client that something is an audiobook. [libraryId], stamped from
 * the scoped request and matched against the user's own `LibraryRole` assignment, is the only
 * mechanism there is.
 */
data class Song(
  val id: String,
  val libraryId: Int,
  val title: String,
  val albumId: String?,
  val albumName: String?,
  val artistId: String?,
  val artistName: String?,
  val trackNumber: Int?,
  val discNumber: Int?,
  val durationSeconds: Int,
  val suffix: String?,
  val coverArtId: String?,
)
```

`core/model/src/main/kotlin/app/muplay/model/SearchResults.kt`:

```kotlin
package app.muplay.model

/** The three result lists a Subsonic `search3` returns, each scoped to one library. */
data class SearchResults(
  val artists: List<Artist>,
  val albums: List<Album>,
  val songs: List<Song>,
) {
  val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}
```

`core/model/src/main/kotlin/app/muplay/model/ScanStatus.kt`:

```kotlin
package app.muplay.model

/**
 * The server's scan state, from `getScanStatus`.
 *
 * [lastScan] is Navidrome's own extension to the Subsonic `ScanStatus` element and is **not** in
 * the vendored OpenSubsonic spec (see `NavidromeSpecDeviationTest`). It is treated as an
 * **opaque token**, never parsed as a timestamp: the only question the sync engine asks is
 * "is this the same string as the one I last committed?". A server that changed its format, or
 * one that does not send the field at all, degrades to "cannot tell" rather than to a parse
 * error — which is why the type is `String?` and not an `Instant`.
 */
data class ScanStatus(
  val isScanning: Boolean,
  val scannedCount: Int?,
  val lastScan: String?,
)
```

`core/model/src/main/kotlin/app/muplay/model/AlbumListType.kt`:

```kotlin
package app.muplay.model

/**
 * The `type` values this client sends to `getAlbumList2`. An enum rather than a raw string so a
 * typo is a compile error instead of a Subsonic error code 10 at runtime — and so this file is
 * the complete list of what MuPlay actually asks for, rather than the much longer list the
 * protocol allows.
 */
enum class AlbumListType(val wireValue: String) {
  /** Every album, in a stable order — what a full reconcile pages through. */
  ALPHABETICAL_BY_NAME("alphabeticalByName"),

  /** Most recently added first — the browse screen's "recently added" ordering. */
  NEWEST("newest"),
}
```

- [ ] **Step 8: Widen the response DTOs**

`core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt` — add these fields
to `SubsonicResponseBody`, keeping the existing ones:

```kotlin
  val albumList2: AlbumList2Body? = null,
  val album: AlbumBody? = null,
  val searchResult3: SearchResult3Body? = null,
  val randomSongs: SongsBody? = null,
  val scanStatus: ScanStatusBody? = null,
```

and add these types to the same file:

```kotlin
/** The OpenSubsonic `AlbumList2` schema: a wrapper object around the album list. */
@Serializable
data class AlbumList2Body(
  val album: List<AlbumBody> = emptyList(),
)

/**
 * The OpenSubsonic `AlbumID3` schema, narrowed to the fields this client uses. Only `id`, `name`,
 * `songCount`, `duration` and `created` are required by the schema; everything optional is
 * nullable or defaulted here.
 *
 * [song] is present only on a `getAlbum` response (the schema puts it on `AlbumID3` itself), so
 * it defaults to empty for every `getAlbumList2`/`search3` album.
 *
 * `userRating` is deliberately **not** modelled. Navidrome sends `0` for an unrated album while
 * the schema declares `[1-5]`, which is the single reason three of this project's captured
 * fixtures fail the OpenAPI oracle (see `NavidromeSpecDeviationTest`); nothing here uses it, and
 * `Json(ignoreUnknownKeys = true)` drops it.
 */
@Serializable
data class AlbumBody(
  val id: String,
  val name: String,
  val artist: String? = null,
  val artistId: String? = null,
  val coverArt: String? = null,
  val songCount: Int = 0,
  val duration: Int = 0,
  val song: List<ChildBody> = emptyList(),
)

/**
 * The OpenSubsonic `Child` schema, narrowed to the fields this client uses. Only `id`, `isDir`
 * and `title` are required by the schema.
 *
 * [type] is modelled and then deliberately ignored by every mapper: Navidrome hardcodes it to
 * `"music"` for every media file including audiobooks, so reading it would be reading a constant.
 * It is kept so the next reader can see that it was considered rather than missed.
 */
@Serializable
data class ChildBody(
  val id: String,
  val title: String,
  val album: String? = null,
  val albumId: String? = null,
  val artist: String? = null,
  val artistId: String? = null,
  val track: Int? = null,
  val discNumber: Int? = null,
  val duration: Int = 0,
  val suffix: String? = null,
  val coverArt: String? = null,
  val type: String? = null,
  val isDir: Boolean = false,
)

/** The OpenSubsonic `ArtistID3` schema, narrowed. Only `id` and `name` are required. */
@Serializable
data class ArtistBody(
  val id: String,
  val name: String,
  val coverArt: String? = null,
  val albumCount: Int = 0,
)

/** The OpenSubsonic `SearchResult3` schema: three optional arrays. */
@Serializable
data class SearchResult3Body(
  val artist: List<ArtistBody> = emptyList(),
  val album: List<AlbumBody> = emptyList(),
  val song: List<ChildBody> = emptyList(),
)

/** The OpenSubsonic `Songs` schema, the payload of `getRandomSongs`. */
@Serializable
data class SongsBody(
  val song: List<ChildBody> = emptyList(),
)

/**
 * The Subsonic `ScanStatus` element. `scanning` is the only field the schema requires; `count` is
 * optional. [lastScan] is Navidrome's own extension and is absent from the vendored spec
 * entirely, together with `folderCount`, `scanType` and `elapsedTime` — see
 * `NavidromeSpecDeviationTest`. It is the field this project's whole sync design rests on, so it
 * is modelled here even though the oracle does not know about it, and typed `String?` so a server
 * that omits it degrades to "cannot tell" rather than failing to parse.
 */
@Serializable
data class ScanStatusBody(
  val scanning: Boolean,
  val count: Int? = null,
  val lastScan: String? = null,
)
```

- [ ] **Step 9: Add the endpoints**

`core/network/src/main/kotlin/app/muplay/network/SubsonicApi.kt` — add, keeping the existing
three and the file's existing documentation:

```kotlin
  @GET("rest/getAlbumList2")
  suspend fun getAlbumList2(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getAlbum")
  suspend fun getAlbum(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/search3")
  suspend fun search3(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getRandomSongs")
  suspend fun getRandomSongs(@QueryMap params: Map<String, String>): SubsonicEnvelope

  @GET("rest/getScanStatus")
  suspend fun getScanStatus(@QueryMap params: Map<String, String>): SubsonicEnvelope
```

- [ ] **Step 10: Add the malformed-response exception**

`core/network/src/main/kotlin/app/muplay/network/SubsonicException.kt` — append:

```kotlin
/**
 * The server answered `status: "ok"` but the response carried no [missingField] payload at all.
 *
 * Deliberately **not** a member of the sealed [SubsonicException] hierarchy. That hierarchy means
 * "the server produced a real answer on purpose", and its members are the ones a caller may
 * legitimately degrade on. A success envelope with no payload is not an answer — it is the same
 * class of event as an unparseable body, which this codebase already lets propagate as whatever
 * the parser threw. Adding it to the sealed set would invite callers to treat "we got nothing"
 * as "the server said no".
 */
class SubsonicMalformedResponseException(val missingField: String) :
  Exception("Subsonic reported success but carried no `$missingField` payload")
```

- [ ] **Step 11: Write the port and its factory**

`core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt`:

```kotlin
package app.muplay.network

import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials

/**
 * Everything the data layer asks of a Subsonic server, as one interface.
 *
 * This is a **port**, not a domain layer and not a use-case class: it declares no behaviour of
 * its own, adds no method [SubsonicClient] does not already implement, and exists for exactly one
 * reason — a test needs to be able to make a *specific call* fail at a *specific point*. The
 * sync engine's most important property is that a failure part-way through a reconcile must not
 * advance the watermark, and there is no way to make a real Navidrome fail on the fourth of
 * seven calls on demand. A hand-written fake implementing this interface can, with no mock
 * framework anywhere near the build.
 *
 * Every scoped method takes `musicFolderId` as a **non-null `Int`**. That is the structural half
 * of this project's defence against the scoping trap: Navidrome silently ignores a
 * `musicFolderId` it cannot parse and widens the response to every library with `status: "ok"`,
 * so a blank or non-numeric value is a scope leak that no response assertion can detect. An `Int`
 * rendered with `toString()` can never be blank or non-numeric.
 */
interface SubsonicSource {

  suspend fun ping(): ServerInfo

  suspend fun getMusicFolders(): List<MusicLibrary>

  suspend fun getScanStatus(): ScanStatus

  /**
   * One page of albums from one library. [size] is clamped to the protocol maximum of 500;
   * [offset] pages through. A page shorter than [size] is the last page — and a past-the-end
   * offset returns an empty list, not an error (confirmed live: the server sends
   * `"albumList2": {}`).
   */
  suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album>

  /**
   * One album and its tracks. [musicFolderId] is **not sent** — the endpoint takes only an id —
   * and exists solely to stamp the library the caller already scoped by onto the results.
   */
  suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs

  suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults

  /**
   * Random songs from one library — the server side of library-scoped shuffle. [size] is clamped
   * to 500, which Navidrome enforces silently.
   */
  suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song>

  /**
   * An authenticated cover-art URL. Not `suspend`: it opens no connection, it builds a URL.
   *
   * The URL carries a **fresh salt**, so two calls for the same art produce different strings.
   * Any image loader that keys its cache on the URL will therefore never hit that cache — see
   * `:feature:library`'s `CoverArt.kt`, which supplies an explicit, art-id-derived cache key for
   * exactly this reason.
   */
  fun coverArtUrl(coverArtId: String, sizePx: Int?): String
}

/**
 * Builds a [SubsonicSource] for a given set of credentials.
 *
 * A factory rather than an injectable singleton because the credentials are not known until the
 * user types them, and they change when the user signs into a different server. Repositories
 * inject this and the credential store, and build a source per operation.
 */
fun interface SubsonicSourceFactory {
  fun create(credentials: SubsonicCredentials): SubsonicSource
}

/** The production factory: a real [SubsonicClient], with its real Retrofit stack. */
object DefaultSubsonicSourceFactory : SubsonicSourceFactory {
  override fun create(credentials: SubsonicCredentials): SubsonicSource = SubsonicClient(credentials)
}
```

- [ ] **Step 12: Implement the commands on `SubsonicClient`**

`core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt` — declare the interface,
mark the two existing methods `override`, extract the base-URL normalisation so the cover-art
builder and `buildApi` share it, and add the new commands. The existing `call { }` helper,
`authParams()` and `generateSalt()` are reused unchanged — do not duplicate the callback plumbing
or the error mapping.

```kotlin
class SubsonicClient(
  private val credentials: SubsonicCredentials,
  private val api: SubsonicApi = buildApi(credentials.baseUrl),
) : SubsonicSource {

  // `ping()` and `getMusicFolders()` are Plan 1's, unchanged apart from gaining `override`:
  // their bodies, their KDoc and their use of `call { }` all stay exactly as they are. Do not
  // rewrite them while adding the `override` keyword.

  override suspend fun getScanStatus(): ScanStatus {
    val body = call { api.getScanStatus(authParams()) }
    val status = body.scanStatus ?: throw SubsonicMalformedResponseException("scanStatus")
    return ScanStatus(
      isScanning = status.scanning,
      scannedCount = status.count,
      lastScan = status.lastScan,
    )
  }

  override suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album> {
    val body = call {
      api.getAlbumList2(
        authParams() + mapOf(
          "type" to type.wireValue,
          "size" to size.coerceIn(1, MAX_ALBUM_LIST_PAGE).toString(),
          "offset" to offset.coerceAtLeast(0).toString(),
          "musicFolderId" to musicFolderId.toString(),
        ),
      )
    }
    // Absent container -> no albums. Legal, and exactly what a past-the-end offset returns.
    return body.albumList2?.album.orEmpty().map { it.toAlbum(musicFolderId) }
  }

  override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs {
    // No `musicFolderId` on the wire: `getAlbum` takes only `id` per the spec. The argument is a
    // stamp, not a parameter.
    val body = call { api.getAlbum(authParams() + mapOf("id" to albumId)) }
    val album = body.album ?: throw SubsonicMalformedResponseException("album")
    return AlbumWithSongs(
      album = album.toAlbum(musicFolderId),
      songs = album.song.map { it.toSong(musicFolderId) },
    )
  }

  override suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults {
    val body = call {
      api.search3(
        authParams() + mapOf(
          "query" to query,
          "musicFolderId" to musicFolderId.toString(),
          "artistCount" to artistCount.coerceAtLeast(0).toString(),
          "albumCount" to albumCount.coerceAtLeast(0).toString(),
          "songCount" to songCount.coerceAtLeast(0).toString(),
        ),
      )
    }
    val result = body.searchResult3
    return SearchResults(
      artists = result?.artist.orEmpty().map { it.toArtist(musicFolderId) },
      albums = result?.album.orEmpty().map { it.toAlbum(musicFolderId) },
      songs = result?.song.orEmpty().map { it.toSong(musicFolderId) },
    )
  }

  override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> {
    val body = call {
      api.getRandomSongs(
        authParams() + mapOf(
          // Navidrome caps this at 500 and silently truncates; clamping here keeps the number on
          // the wire and the number the caller reasons about the same one.
          "size" to size.coerceIn(1, MAX_RANDOM_SONGS).toString(),
          "musicFolderId" to musicFolderId.toString(),
        ),
      )
    }
    return body.randomSongs?.song.orEmpty().map { it.toSong(musicFolderId) }
  }

  override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
    val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()
      .addPathSegments("rest/getCoverArt")
      .addQueryParameter("id", coverArtId)
    authParams().forEach { (name, value) -> builder.addQueryParameter(name, value) }
    if (sizePx != null) builder.addQueryParameter("size", sizePx.toString())
    return builder.build().toString()
  }

  private fun AlbumBody.toAlbum(musicFolderId: Int) = Album(
    id = id,
    libraryId = musicFolderId,
    name = name,
    artistId = artistId,
    artistName = artist,
    coverArtId = coverArt,
    songCount = songCount,
    durationSeconds = duration,
  )

  private fun ArtistBody.toArtist(musicFolderId: Int) = Artist(
    id = id,
    libraryId = musicFolderId,
    name = name,
    coverArtId = coverArt,
    albumCount = albumCount,
  )

  private fun ChildBody.toSong(musicFolderId: Int) = Song(
    id = id,
    libraryId = musicFolderId,
    title = title,
    albumId = albumId,
    albumName = album,
    artistId = artistId,
    artistName = artist,
    trackNumber = track,
    discNumber = discNumber,
    durationSeconds = duration,
    suffix = suffix,
    coverArtId = coverArt,
  )

  companion object {
    // Unchanged from Plan 1, and repeated here in full so this block can be read as the finished
    // article rather than as a diff: the salt width, the generic-error fallback code, and the one
    // shared SecureRandom (seeding can block gathering entropy, so it is not constructed per call
    // -- freshness comes from nextBytes on the shared instance).
    private const val SALT_BYTES = 8
    private const val GENERIC_ERROR_CODE = 0
    private val secureRandom = SecureRandom()

    /** Subsonic's documented cap on `getRandomSongs.size`; Navidrome truncates silently at it. */
    const val MAX_RANDOM_SONGS = 500

    /** Subsonic's documented cap on `getAlbumList2.size`. */
    const val MAX_ALBUM_LIST_PAGE = 500

    /**
     * Extracted from `buildApi`, which had it inline, so the cover-art URL builder and the
     * Retrofit base URL cannot disagree about whether a user-entered URL needs a trailing slash.
     * `SubsonicClientTest`'s `ping succeeds when baseUrl has no trailing slash` already covers
     * the branch; nothing about its behaviour changes here, only where it lives.
     */
    private fun normalizeBaseUrl(baseUrl: String): String =
      if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private fun buildApi(baseUrl: String): SubsonicApi {
      val json = Json { ignoreUnknownKeys = true }
      val contentType = "application/json".toMediaType()
      val retrofit =
        Retrofit.Builder()
          .baseUrl(normalizeBaseUrl(baseUrl))
          .addConverterFactory(json.asConverterFactory(contentType))
          .build()
      return retrofit.create(SubsonicApi::class.java)
    }
  }
}
```

Add the imports these need: `app.muplay.model.Album`, `AlbumListType`, `AlbumWithSongs`,
`Artist`, `ScanStatus`, `SearchResults`, `Song`, the four new body types from
`app.muplay.network.model`, and `okhttp3.HttpUrl.Companion.toHttpUrl`.

- [ ] **Step 13: Run the tests**

Run: `./gradlew :core:network:test`
Expected: PASS — `BrowseEndpointsTest` 18/18, `NavidromeSpecDeviationTest` 6/6, and every
pre-existing `SubsonicClientTest` / `CapabilityNegotiatorTest` / `SubsonicAuthTest` still green.

- [ ] **Step 14: Prove the request assertions can fail**

This is the step that makes the assertions worth having, and it must end committed, not
narrated. Do all three, one at a time, restoring after each:

1. Delete `"musicFolderId" to musicFolderId.toString()` from `getRandomSongs`. Expect
   `getRandomSongs sends the scope and the size` to fail. **This is the exact mutation Plan 1
   could not detect anywhere in the codebase.**
2. Change the clamp in `getRandomSongs` to `size.coerceAtLeast(1)`. Expect
   `getRandomSongs clamps size to the 500 Navidrome silently enforces` to fail.
3. Add `"musicFolderId" to musicFolderId.toString()` to `getAlbum`'s parameter map. Expect
   `getAlbum sends only the album id and must not send a scope` to fail.

Record the three failure messages in the task report. Restore the code and confirm green.

- [ ] **Step 15: Commit**

```bash
git add core/model core/network core/testing/src/main/resources/fixtures docs/superpowers/specs
git commit -m "feat(network): browse, search, scan-status and random-songs commands with request contracts"
```


---

## Task 4: Library entities, user-assigned roles, and `LibraryRepository`

**Files:**
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/LibraryEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/LibraryDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/SubsonicSourceProvider.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/NotConfiguredException.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/LibraryRepository.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/FakeSubsonicSource.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/LibraryDaoTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/LibraryRepositoryTest.kt`

**Interfaces:**
- Consumes: `app.muplay.model.LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }`,
  `app.muplay.model.MusicLibrary(id: Int, name: String, role: LibraryRole)`,
  `app.muplay.network.SubsonicSource`, `app.muplay.network.SubsonicSourceFactory`,
  `app.muplay.network.DefaultSubsonicSourceFactory`, `CredentialStore`
- Produces:
  - `LibraryEntity(musicFolderId: Int, name: String, role: LibraryRole)`, table `libraries`
  - `abstract class LibraryDao` with
    `observeAll(): Flow<List<LibraryEntity>>`,
    `suspend find(musicFolderId: Int): LibraryEntity?`,
    `suspend idsWithRole(role: LibraryRole): List<Int>`,
    `suspend allIds(): List<Int>`,
    `suspend setRole(musicFolderId: Int, role: LibraryRole)`,
    `suspend mergeFromServer(libraries: List<LibraryEntity>)`
  - `class NotConfiguredException : IllegalStateException`
  - `class SubsonicSourceProvider @Inject constructor(credentialStore, factory)` with
    `suspend fun current(): SubsonicSource`
  - `class LibraryRepository @Inject constructor(libraryDao, sourceProvider)` with
    `val libraries: Flow<List<MusicLibrary>>`,
    `suspend fun refreshFromServer()`,
    `suspend fun setRole(musicFolderId: Int, role: LibraryRole)`,
    `suspend fun idsWithRole(role: LibraryRole): List<Int>`,
    `suspend fun allIds(): List<Int>`,
    `suspend fun hasUnassignedLibraries(): Boolean`
  - `MuPlayDatabase.libraryDao(): LibraryDao`, schema version **2**

### Why the library id is load-bearing, and why the role can only come from the user

Spec §4: Navidrome **hardcodes `child.Type = "music"`** for every media file and always sets
`mediaType = song`. Confirmed against the live container — the seeded `Test Book.m4b` comes back
as `"type": "music"`. **A Navidrome server will never tell a client that something is an
audiobook**, and there is no server setting for it. The library id is the only mechanism
available, which makes "which library is this, and what is it for" a first-class app concept
rather than an implementation detail.

The user tags each library once, at setup (Task 8). Everything downstream — shuffle scope,
which browse tree to show, and Plan 4's resume behaviour — keys off that tag.

**Never infer the role from the library's name.** "Hörbücher" is not "Audiobooks", "Livres
audio" is not "Audiobooks", and a wrong guess does not fail — it silently poisons shuffle scope,
which is the one thing this application exists to get right. There is no name-matching code in
this task and none may be added.

### The one non-obvious DAO requirement

A server re-scan re-reports the same libraries, and setup can be re-run. Re-syncing must
**preserve the roles the user chose** while still picking up a renamed or newly-added library.
A plain `@Upsert` of a full entity clobbers the `role` column with whatever the caller passed —
which would silently un-tag someone's audiobook library behind their back. `mergeFromServer` is
therefore `INSERT OR IGNORE` + an explicit name-only `UPDATE`, plus a delete of libraries the
server no longer reports.

- [ ] **Step 1: Write the failing DAO test**

`core/database/src/androidTest/kotlin/app/muplay/database/LibraryDaoTest.kt`:

```kotlin
package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.LibraryDao
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: LibraryDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.libraryDao()
  }

  @After
  fun tearDown() = db.close()

  private fun unassigned(id: Int, name: String) = LibraryEntity(id, name, LibraryRole.UNASSIGNED)

  @Test
  fun librariesArriveFromTheServerUnassigned() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    // Nothing in a Subsonic response says what a library is *for*, so this is the only correct
    // starting state -- not a placeholder, and not something to guess from the names above.
    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty()
    assertThat(dao.idsWithRole(LibraryRole.MUSIC)).isEmpty()
    assertThat(dao.idsWithRole(LibraryRole.UNASSIGNED)).containsExactly(1, 2)
  }

  @Test
  fun taggingIsWhatMakesALibraryAnAudiobookLibrary() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    dao.setRole(2, LibraryRole.AUDIOBOOKS)
    dao.setRole(1, LibraryRole.MUSIC)

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(dao.idsWithRole(LibraryRole.MUSIC)).containsExactly(1)
  }

  /**
   * The requirement a naive `@Upsert` silently breaks: a re-sync must update a library's name and
   * add a new library without touching the role the user chose. Getting this wrong un-tags
   * someone's audiobook library behind their back, and the only symptom is audiobooks turning up
   * in a music shuffle days later.
   */
  @Test
  fun resyncingPreservesUserAssignedRolesWhileUpdatingNames() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))
    dao.setRole(2, LibraryRole.AUDIOBOOKS)

    dao.mergeFromServer(
      listOf(unassigned(1, "Musik"), unassigned(2, "Hörbücher"), unassigned(3, "Podcasts")),
    )

    assertThat(dao.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
    assertThat(dao.find(1)!!.name).isEqualTo("Musik")
    assertThat(dao.find(2)!!.name).isEqualTo("Hörbücher")
    // ...and the new one is UNASSIGNED, not guessed at from its name.
    assertThat(dao.find(3)!!.role).isEqualTo(LibraryRole.UNASSIGNED)
  }

  @Test
  fun aLibraryTheServerNoLongerReportsIsRemoved() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music"), unassigned(2, "Audiobooks")))

    dao.mergeFromServer(listOf(unassigned(1, "Music")))

    assertThat(dao.allIds()).containsExactly(1)
    assertThat(dao.find(2)).isNull()
  }

  @Test
  fun mergingAnEmptyServerListRemovesEverything() = runTest {
    // The boundary case an `IN (:keep)` clause gets wrong if `keep` is empty and the SQL is
    // written carelessly: SQLite's `NOT IN ()` is a syntax error, and Room binds an empty list as
    // `NOT IN ()`. Room actually expands it to `NOT IN (NULL)`-safe SQL, but this asserts the
    // behaviour rather than trusting it.
    dao.mergeFromServer(listOf(unassigned(1, "Music")))

    dao.mergeFromServer(emptyList())

    assertThat(dao.allIds()).isEmpty()
  }

  @Test
  fun observeAllEmitsInIdOrder() = runTest {
    dao.mergeFromServer(listOf(unassigned(2, "Audiobooks"), unassigned(1, "Music")))

    assertThat(dao.observeAll().first().map { it.musicFolderId }).containsExactly(1, 2)
  }

  @Test
  fun theRoleEnumSurvivesTheRoundTripThroughSqlite() = runTest {
    dao.mergeFromServer(listOf(unassigned(1, "Music")))
    dao.setRole(1, LibraryRole.MUSIC)

    // Room converts enums automatically, and "automatically" is exactly the kind of thing worth
    // one assertion: a converter that stored an ordinal would silently reorder if a member were
    // ever inserted into `LibraryRole`.
    assertThat(dao.find(1)!!.role).isEqualTo(LibraryRole.MUSIC)
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*LibraryDaoTest*'`
Expected: FAIL — `Unresolved reference: libraryDao`, `LibraryEntity`, `LibraryDao`.

- [ ] **Step 3: Write the entity and the DAO**

`core/database/src/main/kotlin/app/muplay/database/entity/LibraryEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.muplay.model.LibraryRole

/**
 * One Navidrome library (a Subsonic "music folder"), and the role **the user** gave it.
 *
 * [musicFolderId] is the server's own id and the primary key. It is also the only thing that
 * connects a mirrored artist, album or song to a library — no Subsonic response carries a library
 * id, so every mirror row's `libraryId` was stamped from the request that fetched it, and this
 * table is what gives that number meaning.
 *
 * [role] is never derived from [name]. "Hörbücher" is not "Audiobooks", and a wrong guess does
 * not fail loudly — it silently poisons shuffle scope, which is the one thing this application
 * exists to get right.
 */
@Entity(tableName = "libraries")
data class LibraryEntity(
  @PrimaryKey val musicFolderId: Int,
  val name: String,
  val role: LibraryRole,
)
```

`core/database/src/main/kotlin/app/muplay/database/dao/LibraryDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.Flow

/**
 * An abstract class rather than an interface: [mergeFromServer] is a `@Transaction` method with a
 * body, and Room needs a class to put one in.
 */
@Dao
abstract class LibraryDao {

  @Query("SELECT * FROM libraries ORDER BY musicFolderId")
  abstract fun observeAll(): Flow<List<LibraryEntity>>

  @Query("SELECT * FROM libraries WHERE musicFolderId = :musicFolderId")
  abstract suspend fun find(musicFolderId: Int): LibraryEntity?

  @Query("SELECT musicFolderId FROM libraries WHERE role = :role ORDER BY musicFolderId")
  abstract suspend fun idsWithRole(role: LibraryRole): List<Int>

  @Query("SELECT musicFolderId FROM libraries ORDER BY musicFolderId")
  abstract suspend fun allIds(): List<Int>

  @Query("UPDATE libraries SET role = :role WHERE musicFolderId = :musicFolderId")
  abstract suspend fun setRole(musicFolderId: Int, role: LibraryRole)

  /**
   * Reconciles the stored libraries with what the server reports, **without touching the `role`
   * column of a library that already exists**.
   *
   * Not an `@Upsert`: an upsert writes every column of the entity it is given, and the caller
   * builds those entities from a Subsonic response, which cannot know a role. The result would be
   * every re-sync silently resetting the user's audiobook tag to UNASSIGNED.
   */
  @Transaction
  open suspend fun mergeFromServer(libraries: List<LibraryEntity>) {
    insertIgnoringExisting(libraries)
    libraries.forEach { updateName(it.musicFolderId, it.name) }
    deleteMissing(libraries.map { it.musicFolderId })
  }

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  protected abstract suspend fun insertIgnoringExisting(libraries: List<LibraryEntity>)

  @Query("UPDATE libraries SET name = :name WHERE musicFolderId = :musicFolderId")
  protected abstract suspend fun updateName(musicFolderId: Int, name: String)

  @Query("DELETE FROM libraries WHERE musicFolderId NOT IN (:keep)")
  protected abstract suspend fun deleteMissing(keep: List<Int>)
}
```

- [ ] **Step 4: Register the entity**

`core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`:

```kotlin
@Database(
  entities = [MediaProgressEntity::class, LibraryEntity::class],
  version = 2,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  abstract fun libraryDao(): LibraryDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
```

with the two new imports. **The version is bumped and no `Migration` is written**, which is the
right call exactly once: nothing has shipped, so there is no installed database whose data has
to survive. `DataModule.provideDatabase` gets the matching escape hatch:

```kotlin
    Room.databaseBuilder(context, MuPlayDatabase::class.java, MuPlayDatabase.DATABASE_NAME)
      // Pre-release only. Every task in this plan that adds a table bumps `version` and writes no
      // migration, so a developer's device (and the emulator that runs the required Tier 2 gate)
      // must be allowed to throw its mirror away and re-sync — the mirror is a cache of the
      // server, and re-fetching it costs one sync.
      //
      // THIS LINE MUST BE REMOVED BEFORE THE FIRST RELEASE, and replaced with real `Migration`
      // objects verified against the exported schema JSON in `core/database/schemas/`. Shipping
      // it means every future schema change silently deletes a user's `media_progress` — every
      // audiobook position they have.
      .fallbackToDestructiveMigration(dropAllTables = true)
      .build()
```

- [ ] **Step 5: Run the DAO test**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*LibraryDaoTest*'`
Expected: PASS, 7/7.

- [ ] **Step 6: Write the failing repository test**

First the fake. `core/database/src/androidTest/kotlin/app/muplay/database/FakeSubsonicSource.kt`:

```kotlin
package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.network.SubsonicSource

/**
 * A hand-written fake, not a mock: no framework, no stubbing DSL, no verification API — a real
 * object with real fields whose behaviour is visible by reading it.
 *
 * It exists because of one requirement no real server can satisfy on demand: the sync engine must
 * not advance its watermark when a reconcile fails **part-way through**, and a live Navidrome
 * cannot be asked to fail on the fourth of seven calls. [failAfterCalls] does exactly that.
 * Everything else here is served out of plain maps, so a test's setup reads as data.
 *
 * Counting calls in [callLog] is deliberately not a verification API in disguise: the sync tests
 * assert on the *database's* contents, and the log exists so a failure message can say what the
 * engine actually asked for.
 */
class FakeSubsonicSource : SubsonicSource {

  var musicFolders: List<MusicLibrary> = emptyList()
  var scanStatus: ScanStatus = ScanStatus(isScanning = false, scannedCount = 0, lastScan = "s0")
  /** Albums per library id, in the order `getAlbumList2` should page through them. */
  var albumsByLibrary: Map<Int, List<Album>> = emptyMap()
  /** Songs per album id. */
  var songsByAlbum: Map<String, List<Song>> = emptyMap()
  var randomSongsByLibrary: Map<Int, List<Song>> = emptyMap()
  var searchResults: SearchResults = SearchResults(emptyList(), emptyList(), emptyList())

  /** After this many calls to any method, every further call throws. `null` disables it. */
  var failAfterCalls: Int? = null

  val callLog: MutableList<String> = mutableListOf()

  private fun record(call: String) {
    callLog += call
    val limit = failAfterCalls
    if (limit != null && callLog.size > limit) {
      throw java.io.IOException("FakeSubsonicSource: forced failure after $limit calls")
    }
  }

  override suspend fun ping(): ServerInfo {
    record("ping")
    return ServerInfo("navidrome", "0.63.2", "1.16.1", isOpenSubsonic = true)
  }

  override suspend fun getMusicFolders(): List<MusicLibrary> {
    record("getMusicFolders")
    return musicFolders
  }

  override suspend fun getScanStatus(): ScanStatus {
    record("getScanStatus")
    return scanStatus
  }

  override suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album> {
    record("getAlbumList2($musicFolderId, offset=$offset)")
    return albumsByLibrary[musicFolderId].orEmpty().drop(offset).take(size)
  }

  override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs {
    record("getAlbum($albumId)")
    val album = albumsByLibrary[musicFolderId].orEmpty().first { it.id == albumId }
    return AlbumWithSongs(album, songsByAlbum[albumId].orEmpty())
  }

  override suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults {
    record("search3($query, $musicFolderId)")
    return searchResults
  }

  override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> {
    record("getRandomSongs($musicFolderId, size=$size)")
    return randomSongsByLibrary[musicFolderId].orEmpty().take(size)
  }

  override fun coverArtUrl(coverArtId: String, sizePx: Int?): String =
    "https://fake.invalid/rest/getCoverArt?id=$coverArtId" + (sizePx?.let { "&size=$it" } ?: "")
}
```

`core/database/src/androidTest/kotlin/app/muplay/database/LibraryRepositoryTest.kt`:

```kotlin
package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: LibraryRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "library-repo-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    source = FakeSubsonicSource()
    repository = LibraryRepository(
      libraryDao = db.libraryDao(),
      sourceProvider = SubsonicSourceProvider(
        credentialStore = credentialStore,
        factory = SubsonicSourceFactory { source },
      ),
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  private suspend fun signIn() =
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

  @Test
  fun refreshingWithNoStoredCredentialsFailsLoudly() = runTest {
    // "Not configured" and "the server is down" must not look the same to a caller: only the
    // first is fixable by the user typing a URL, and the setup flow keys off exactly that.
    assertThatThrownBy { repository.refreshFromServer() }
      .isInstanceOf(NotConfiguredException::class.java)
  }

  @Test
  fun refreshingStoresEveryLibraryTheServerReports() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )

    repository.refreshFromServer()

    assertThat(repository.libraries.first().map { it.name })
      .containsExactly("Music", "Audiobooks")
    assertThat(repository.libraries.first()).allMatch { it.role == LibraryRole.UNASSIGNED }
  }

  @Test
  fun aRefreshDoesNotDisturbTheRolesTheUserChose() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()
    repository.setRole(2, LibraryRole.AUDIOBOOKS)

    repository.refreshFromServer()

    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).containsExactly(2)
  }

  @Test
  fun unassignedLibrariesAreReportedUntilEveryOneIsTagged() = runTest {
    signIn()
    source.musicFolders = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    repository.refreshFromServer()

    assertThat(repository.hasUnassignedLibraries()).isTrue
    repository.setRole(1, LibraryRole.MUSIC)
    assertThat(repository.hasUnassignedLibraries()).isTrue
    repository.setRole(2, LibraryRole.AUDIOBOOKS)
    assertThat(repository.hasUnassignedLibraries()).isFalse
  }

  @Test
  fun theRepositoryNeverGuessesARoleFromALibraryName() = runTest {
    signIn()
    // The names most likely to tempt a name-matching heuristic, in two languages. Every one of
    // them must still come back UNASSIGNED: a wrong guess here is silent and its only symptom is
    // audiobooks appearing in a music shuffle.
    source.musicFolders = listOf(
      MusicLibrary(1, "Audiobooks", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Hörbücher", LibraryRole.UNASSIGNED),
      MusicLibrary(3, "Music", LibraryRole.UNASSIGNED),
    )

    repository.refreshFromServer()

    assertThat(repository.libraries.first()).allMatch { it.role == LibraryRole.UNASSIGNED }
    assertThat(repository.idsWithRole(LibraryRole.AUDIOBOOKS)).isEmpty()
    assertThat(repository.idsWithRole(LibraryRole.MUSIC)).isEmpty()
  }
}
```

- [ ] **Step 7: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*LibraryRepositoryTest*'`
Expected: FAIL — `Unresolved reference: LibraryRepository`, `SubsonicSourceProvider`,
`NotConfiguredException`.

- [ ] **Step 8: Implement**

`core/database/src/main/kotlin/app/muplay/database/NotConfiguredException.kt`:

```kotlin
package app.muplay.database

/**
 * Thrown when a repository is asked to talk to the server before any credentials were stored.
 *
 * A distinct type, not a bare `IllegalStateException`, because the UI genuinely has to tell it
 * apart from "the server is unreachable": only this one is fixed by the user entering a URL, and
 * conflating them produces a "check your connection" message on a device that has never been
 * configured.
 */
class NotConfiguredException :
  IllegalStateException("No Subsonic credentials are stored; run the setup flow first")
```

`core/database/src/main/kotlin/app/muplay/database/SubsonicSourceProvider.kt`:

```kotlin
package app.muplay.database

import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [SubsonicSource] from whatever credentials are stored right now.
 *
 * Every repository that talks to the server injects this rather than a `SubsonicSource` directly,
 * because there is no source to inject until the user has signed in, and the answer changes when
 * they sign into a different server. Reading the credentials per call, rather than caching a
 * client, is what makes "sign out, sign into another server" work with no invalidation logic.
 */
@Singleton
class SubsonicSourceProvider @Inject constructor(
  private val credentialStore: CredentialStore,
  private val factory: SubsonicSourceFactory,
) {
  suspend fun current(): SubsonicSource =
    factory.create(credentialStore.load() ?: throw NotConfiguredException())
}
```

`core/database/src/main/kotlin/app/muplay/database/LibraryRepository.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.LibraryDao
import app.muplay.database.entity.LibraryEntity
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The libraries this server has, and what the user decided each one is for.
 *
 * Per this project's constraints there is no domain layer and no use-case class between this and
 * the UI: a ViewModel injects this repository directly.
 *
 * **Nothing here inspects a library's name.** Role assignment comes from the user, through
 * [setRole], and from nowhere else — see `LibraryEntity`'s own documentation for what a name
 * heuristic would cost.
 */
@Singleton
class LibraryRepository @Inject constructor(
  private val libraryDao: LibraryDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  /** Every known library with its user-assigned role, in server id order. */
  val libraries: Flow<List<MusicLibrary>> =
    libraryDao.observeAll().map { rows ->
      rows.map { MusicLibrary(id = it.musicFolderId, name = it.name, role = it.role) }
    }

  /**
   * Re-reads `getMusicFolders` and merges it into the mirror: names are updated, new libraries
   * arrive [LibraryRole.UNASSIGNED], libraries the server no longer reports are removed, and the
   * roles the user already chose are untouched.
   */
  suspend fun refreshFromServer() {
    val folders = sourceProvider.current().getMusicFolders()
    libraryDao.mergeFromServer(
      folders.map { LibraryEntity(musicFolderId = it.id, name = it.name, role = it.role) },
    )
  }

  suspend fun setRole(musicFolderId: Int, role: LibraryRole) =
    libraryDao.setRole(musicFolderId, role)

  suspend fun idsWithRole(role: LibraryRole): List<Int> = libraryDao.idsWithRole(role)

  suspend fun allIds(): List<Int> = libraryDao.allIds()

  /**
   * Whether any library is still [LibraryRole.UNASSIGNED]. The setup flow uses this to decide
   * whether the user still has tagging to do — an untagged library is invisible to every browse
   * and shuffle path, so leaving one is a dead end rather than a default.
   */
  suspend fun hasUnassignedLibraries(): Boolean =
    libraryDao.idsWithRole(LibraryRole.UNASSIGNED).isNotEmpty()
}
```

Note that `refreshFromServer` passes `it.role` straight through: `SubsonicClient.getMusicFolders`
always reports `LibraryRole.UNASSIGNED` (the response says nothing about content), and
`mergeFromServer` ignores the role column for a library that already exists — so the value only
ever reaches the database for a genuinely new row.

- [ ] **Step 9: Bind the factory**

`core/database/build.gradle.kts` — promote the network dependency, because
`SubsonicSourceProvider.current()` is a **public** signature returning `SubsonicSource`:

```kotlin
  // `api` as of this task: `SubsonicSourceProvider.current()` returns `SubsonicSource`, a
  // `:core:network` type, from a public signature — with `implementation`, a consumer of this
  // module cannot resolve that type and the Kotlin compiler reports the supertype as
  // inaccessible. This is `plan-2-inherited.md` item 4's `api`-vs-`implementation` audit landing
  // on the first case where the answer actually changes.
  api(project(":core:network"))
```

replacing the `implementation(project(":core:network"))` line Task 1 wrote.

`core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` — add:

```kotlin
  @Provides
  fun provideLibraryDao(database: MuPlayDatabase): LibraryDao = database.libraryDao()

  /**
   * `:core:network` is a plain Kotlin/JVM module with no Hilt and no Android dependency, and it
   * stays that way — this is where its factory enters the graph.
   */
  @Provides
  fun provideSubsonicSourceFactory(): SubsonicSourceFactory = DefaultSubsonicSourceFactory
```

with imports for `LibraryDao`, `SubsonicSourceFactory` and `DefaultSubsonicSourceFactory`.

- [ ] **Step 10: Run, re-measure, commit**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS — `LibraryDaoTest` 7/7, `LibraryRepositoryTest` 5/5, and the two earlier suites.

Re-run `./gradlew :core:database:jacocoTestReport`, read the new per-class ratios out of the XML
(the snippet in Task 1, Step 10), and update `:core:database`'s `coverageFloors` entries. Prove
one of the new floors can fail by deleting `theRepositoryNeverGuessesARoleFromALibraryName` and
watching `jacocoTestCoverageVerification` go red; restore it.

```bash
git add core/database build.gradle.kts
git commit -m "feat(database): library entities with user-assigned roles"
```


---

## Task 5: The mirror tables, `BrowseDao` and `BrowseRepository`

**Files:**
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/ArtistEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/AlbumEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/SongEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/BrowseDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/BrowseRepository.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Test: `core/database/src/test/kotlin/app/muplay/database/MirrorMapperTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/BrowseDaoTest.kt`

**Interfaces:**
- Consumes: `Album`, `Artist`, `Song`, `SearchResults`, `SubsonicSourceProvider`
- Produces:
  - `ArtistEntity(id: String, libraryId: Int, name: String, coverArtId: String?, albumCount: Int, sortName: String)`, table `artists`
  - `AlbumEntity(id: String, libraryId: Int, artistId: String?, name: String, artistName: String?, coverArtId: String?, songCount: Int, durationSeconds: Int, sortName: String)`, table `albums`
  - `SongEntity(id: String, libraryId: Int, albumId: String?, artistId: String?, title: String, albumName: String?, artistName: String?, trackNumber: Int?, discNumber: Int?, durationSeconds: Int, suffix: String?, coverArtId: String?, sortTitle: String)`, table `songs`
  - `object MirrorMapper` with
    `sortKey(value: String): String`,
    `albumEntity(album: Album): AlbumEntity`,
    `songEntity(song: Song): SongEntity`,
    `artistEntities(albums: List<Album>): List<ArtistEntity>`,
    and the reverse `album(entity)`, `song(entity)`, `artist(entity)`
  - `data class MirrorReplacement(artistsBefore: Int, artistsAfter: Int, albumsBefore: Int, albumsAfter: Int, songsBefore: Int, songsAfter: Int)`
  - `abstract class BrowseDao` with
    `observeArtists(libraryId: Int): Flow<List<ArtistEntity>>`,
    `observeAlbums(libraryId: Int): Flow<List<AlbumEntity>>`,
    `observeAlbumsByArtist(artistId: String): Flow<List<AlbumEntity>>`,
    `observeSongs(albumId: String): Flow<List<SongEntity>>`,
    `suspend findAlbum(albumId: String): AlbumEntity?`,
    `suspend searchArtists(libraryId: Int, pattern: String, limit: Int): List<ArtistEntity>`,
    `suspend searchAlbums(libraryId: Int, pattern: String, limit: Int): List<AlbumEntity>`,
    `suspend searchSongs(libraryId: Int, pattern: String, limit: Int): List<SongEntity>`,
    `suspend songIdsInLibrary(libraryId: Int, ids: List<String>): List<String>`,
    `suspend replaceLibraryContents(libraryId: Int, artists: List<ArtistEntity>, albums: List<AlbumEntity>, songs: List<SongEntity>): MirrorReplacement`
  - `class BrowseRepository @Inject constructor(browseDao, sourceProvider)` with
    `artists(libraryId): Flow<List<Artist>>`, `albums(libraryId): Flow<List<Album>>`,
    `albumsByArtist(artistId): Flow<List<Album>>`, `songs(albumId): Flow<List<Song>>`,
    `suspend album(albumId): Album?`,
    `suspend search(libraryId: Int, query: String, limit: Int): SearchResults`,
    `suspend coverArtUrl(coverArtId: String, sizePx: Int?): String`
  - `MuPlayDatabase.browseDao(): BrowseDao`, schema version **3**

### Why the mirror carries `libraryId` on every row, and where it comes from

Nothing in a Subsonic response says which library an album or a song belongs to. The number on
each row was stamped by `SubsonicClient` from the `musicFolderId` of the request that fetched it
(Task 3), and it is the **only** link between a track and the user's Music/Audiobooks decision.
Every browse query and the shuffle scope guard filter on it. A mirror row with the wrong
`libraryId` is exactly the failure this whole application exists to prevent, which is why the
sync engine (Task 6) fetches strictly per library and never merges two libraries' pages.

### Why artists are derived, not fetched

`getArtists` and `getIndexes` are the two commands spec §4 says never to use to enforce a scope.
They are also the two whose live responses deviate from the vendored spec (`getArtists` adds
`lastModified` to `artists`). Neither is needed: `AlbumID3` carries `artistId` and `artist`, so
the artist list for a library is a `groupBy` over the albums that library already returned. That
removes one command, one deviation and one scoping hazard from the plan entirely.

The cost, stated rather than hidden: a derived artist has no artist-specific cover art, so
`ArtistEntity.coverArtId` is borrowed from that artist's first album by sort key. It is a real
image of the right artist, not a placeholder, and the field is documented as derived.

### Why a full replace, not a diff

`getIndexes?ifModifiedSince=` is the only delta primitive Subsonic has, Navidrome compares it
against one global watermark, and **deletions are never reported at all**. A client that trusts
deltas accumulates ghost albums forever. So a reconcile deletes everything in one library and
re-inserts what the server just reported, inside one transaction: anything locally present and
remotely absent is gone by construction, with no diff to get wrong.

`media_progress` is a **different table**, keyed by the server's stable media id and never
touched by a reconcile. That is not an accident of implementation — it is the property spec §3
demands, and `BrowseDaoTest` asserts it directly.

- [ ] **Step 1: Write the failing pure-logic test**

`core/database/src/test/kotlin/app/muplay/database/MirrorMapperTest.kt` — a plain JVM test in
Tier 1, because none of this touches SQLite:

```kotlin
package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MirrorMapperTest {

  private fun album(
    id: String,
    name: String,
    artistId: String? = "artist-1",
    artistName: String? = "Test Artist",
    coverArtId: String? = "al-$id",
    libraryId: Int = 1,
  ) = Album(
    id = id,
    libraryId = libraryId,
    name = name,
    artistId = artistId,
    artistName = artistName,
    coverArtId = coverArtId,
    songCount = 3,
    durationSeconds = 15,
  )

  @Test
  fun `the sort key is case-insensitive and trimmed`() {
    assertThat(MirrorMapper.sortKey("  The Wall ")).isEqualTo("the wall")
    assertThat(MirrorMapper.sortKey("ABBA")).isEqualTo(MirrorMapper.sortKey("abba"))
  }

  @Test
  fun `the sort key deliberately keeps leading articles`() {
    // Navidrome has its own per-server `ignoredArticles` list ("The El La Los Las Le Les Os As O
    // A" on the pinned container) and this plan never fetches it. Stripping articles with a
    // hardcoded English list would sort a German or French library wrongly and silently, so the
    // honest choice is not to strip at all until the server's own list is read.
    assertThat(MirrorMapper.sortKey("The Wall")).isEqualTo("the wall")
  }

  @Test
  fun `an album maps to an entity with its stamped library id intact`() {
    val entity = MirrorMapper.albumEntity(album("a1", "Test Album", libraryId = 2))

    assertThat(entity.id).isEqualTo("a1")
    assertThat(entity.libraryId).isEqualTo(2)
    assertThat(entity.name).isEqualTo("Test Album")
    assertThat(entity.artistId).isEqualTo("artist-1")
    assertThat(entity.artistName).isEqualTo("Test Artist")
    assertThat(entity.sortName).isEqualTo("test album")
    assertThat(entity.songCount).isEqualTo(3)
    assertThat(entity.durationSeconds).isEqualTo(15)
  }

  @Test
  fun `a song round-trips through its entity unchanged`() {
    val song = Song(
      id = "s1",
      libraryId = 2,
      title = "Track 1",
      albumId = "a1",
      albumName = "Test Album",
      artistId = "artist-1",
      artistName = "Test Artist",
      trackNumber = 1,
      discNumber = null,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = "al-a1_0",
    )

    assertThat(MirrorMapper.song(MirrorMapper.songEntity(song))).isEqualTo(song)
  }

  @Test
  fun `artists are derived from the albums of one library`() {
    val artists = MirrorMapper.artistEntities(
      listOf(
        album("a2", "Second", artistId = "artist-1", artistName = "Test Artist"),
        album("a1", "First", artistId = "artist-1", artistName = "Test Artist"),
        album("b1", "Other", artistId = "artist-2", artistName = "Other Artist"),
      ),
    )

    assertThat(artists.map { it.id }).containsExactlyInAnyOrder("artist-1", "artist-2")
    val first = artists.single { it.id == "artist-1" }
    assertThat(first.name).isEqualTo("Test Artist")
    assertThat(first.albumCount).isEqualTo(2)
    // Borrowed from the artist's first album by sort key ("first" < "second"), and documented as
    // derived -- AlbumID3 carries no artist image and this plan never calls getArtists.
    assertThat(first.coverArtId).isEqualTo("al-a1")
    assertThat(first.sortName).isEqualTo("test artist")
  }

  @Test
  fun `an album with no artist id contributes no artist but is not dropped`() {
    // A real possibility: `artistId` is optional on AlbumID3. Inventing an artist row keyed by
    // name would create a second "Various Artists" every time the name differed by a space.
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "Orphan", artistId = null, artistName = null)),
    )

    assertThat(artists).isEmpty()
    assertThat(MirrorMapper.albumEntity(album("a1", "Orphan", artistId = null, artistName = null)).artistId)
      .isNull()
  }

  @Test
  fun `a derived artist takes its library id from its albums`() {
    val artists = MirrorMapper.artistEntities(listOf(album("a1", "First", libraryId = 9)))

    assertThat(artists.single().libraryId).isEqualTo(9)
  }

  @Test
  fun `an album with a null artist name still yields an artist when it has an id`() {
    // `artistId` present, `artist` absent is spec-legal. Falling back to the id keeps the row
    // addressable instead of producing a blank line in the artist list.
    val artists = MirrorMapper.artistEntities(
      listOf(album("a1", "First", artistId = "artist-1", artistName = null)),
    )

    assertThat(artists.single().name).isEqualTo("artist-1")
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:database:test --tests '*MirrorMapperTest*'`
Expected: FAIL — `Unresolved reference: MirrorMapper`.

- [ ] **Step 3: Write the entities**

`core/database/src/main/kotlin/app/muplay/database/entity/ArtistEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored artist, **derived from the albums of one library** rather than fetched.
 *
 * `getArtists` and `getIndexes` are the two commands the spec says never to use to enforce a
 * scope, and `AlbumID3` already carries `artistId` and `artist` — so the artist list is a
 * `groupBy` over albums this client already has, with no extra request and no scoping hazard.
 *
 * [coverArtId] is therefore **borrowed** from the artist's first album by [sortName]: no artist
 * image is available without `getArtist`/`getArtistInfo2`. It is a real cover of the right
 * artist, not a placeholder.
 *
 * [libraryId] is the stamped scope of the request that produced the albums. The same artist
 * appearing in two libraries produces two rows only if the server gives them different ids; if it
 * gives them the same id, the later reconcile wins, which is correct for a browse mirror and is
 * why this table is never the source of truth for anything but display.
 */
@Entity(
  tableName = "artists",
  indices = [Index("libraryId"), Index("sortName")],
)
data class ArtistEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val name: String,
  val coverArtId: String?,
  val albumCount: Int,
  val sortName: String,
)
```

`core/database/src/main/kotlin/app/muplay/database/entity/AlbumEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored album. [libraryId] came from the `musicFolderId` of the request that fetched it —
 * no Subsonic response carries a library id — and every browse query filters on it.
 *
 * No foreign key to `artists`: the artist rows are derived from these albums, so a foreign key
 * would make insertion order load-bearing inside the reconcile transaction for no benefit. Room
 * would also then require the delete order to be exactly right, turning a data-quality question
 * into a constraint-violation crash.
 */
@Entity(
  tableName = "albums",
  indices = [Index("libraryId"), Index("artistId"), Index("sortName")],
)
data class AlbumEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val artistId: String?,
  val name: String,
  val artistName: String?,
  val coverArtId: String?,
  val songCount: Int,
  val durationSeconds: Int,
  val sortName: String,
)
```

`core/database/src/main/kotlin/app/muplay/database/entity/SongEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirrored track.
 *
 * There is no `contentKind` column, and there must never be one: Navidrome hardcodes
 * `child.Type = "music"` for every media file, audiobooks included, so any such column would be
 * a constant. [libraryId], matched against the user's `LibraryRole` assignment, is how this
 * application knows a track is an audiobook chapter.
 *
 * This table is a **cache of the server** and a reconcile deletes and re-inserts it wholesale.
 * Nothing durable may live here — playback position lives in `media_progress`, keyed by the same
 * server id, in a table no reconcile touches.
 */
@Entity(
  tableName = "songs",
  indices = [Index("libraryId"), Index("albumId"), Index("sortTitle")],
)
data class SongEntity(
  @PrimaryKey val id: String,
  val libraryId: Int,
  val albumId: String?,
  val artistId: String?,
  val title: String,
  val albumName: String?,
  val artistName: String?,
  val trackNumber: Int?,
  val discNumber: Int?,
  val durationSeconds: Int,
  val suffix: String?,
  val coverArtId: String?,
  val sortTitle: String,
)
```

- [ ] **Step 4: Write the mapper**

`core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.Song

/**
 * Domain models to mirror rows and back, plus the artist derivation.
 *
 * Deliberately a plain object with pure functions and no injected collaborators: it is the one
 * piece of this module's logic that needs no SQLite, so it is unit-tested on the JVM in Tier 1
 * rather than waiting 45 minutes for an emulator to say the same thing.
 */
object MirrorMapper {

  /**
   * The key rows are ordered by: trimmed and lower-cased, nothing else.
   *
   * Leading articles are **not** stripped. Navidrome publishes its own `ignoredArticles` list per
   * server ("The El La Los Las Le Les Os As O A" on the pinned container) and this plan never
   * fetches it; a hardcoded English list would mis-sort a German or French library silently,
   * which is worse than sorting "The Wall" under T.
   */
  fun sortKey(value: String): String = value.trim().lowercase()

  fun albumEntity(album: Album): AlbumEntity = AlbumEntity(
    id = album.id,
    libraryId = album.libraryId,
    artistId = album.artistId,
    name = album.name,
    artistName = album.artistName,
    coverArtId = album.coverArtId,
    songCount = album.songCount,
    durationSeconds = album.durationSeconds,
    sortName = sortKey(album.name),
  )

  fun album(entity: AlbumEntity): Album = Album(
    id = entity.id,
    libraryId = entity.libraryId,
    name = entity.name,
    artistId = entity.artistId,
    artistName = entity.artistName,
    coverArtId = entity.coverArtId,
    songCount = entity.songCount,
    durationSeconds = entity.durationSeconds,
  )

  fun songEntity(song: Song): SongEntity = SongEntity(
    id = song.id,
    libraryId = song.libraryId,
    albumId = song.albumId,
    artistId = song.artistId,
    title = song.title,
    albumName = song.albumName,
    artistName = song.artistName,
    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    durationSeconds = song.durationSeconds,
    suffix = song.suffix,
    coverArtId = song.coverArtId,
    sortTitle = sortKey(song.title),
  )

  fun song(entity: SongEntity): Song = Song(
    id = entity.id,
    libraryId = entity.libraryId,
    title = entity.title,
    albumId = entity.albumId,
    albumName = entity.albumName,
    artistId = entity.artistId,
    artistName = entity.artistName,
    trackNumber = entity.trackNumber,
    discNumber = entity.discNumber,
    durationSeconds = entity.durationSeconds,
    suffix = entity.suffix,
    coverArtId = entity.coverArtId,
  )

  fun artist(entity: ArtistEntity): Artist = Artist(
    id = entity.id,
    libraryId = entity.libraryId,
    name = entity.name,
    coverArtId = entity.coverArtId,
    albumCount = entity.albumCount,
  )

  /**
   * The artist rows implied by [albums]. Albums with no `artistId` contribute nothing — inventing
   * an artist keyed by name would create a second "Various Artists" the moment a name differed by
   * a space — but they are still stored as albums by the caller.
   */
  fun artistEntities(albums: List<Album>): List<ArtistEntity> =
    albums.filter { it.artistId != null }
      .groupBy { it.artistId!! }
      .map { (artistId, artistAlbums) ->
        val ordered = artistAlbums.sortedBy { sortKey(it.name) }
        val name = ordered.firstNotNullOfOrNull { it.artistName } ?: artistId
        ArtistEntity(
          id = artistId,
          libraryId = ordered.first().libraryId,
          name = name,
          coverArtId = ordered.firstNotNullOfOrNull { it.coverArtId },
          albumCount = ordered.size,
          sortName = sortKey(name),
        )
      }
}
```

- [ ] **Step 5: Run the pure tests**

Run: `./gradlew :core:database:test --tests '*MirrorMapperTest*'`
Expected: PASS, 8/8.

- [ ] **Step 6: Write the failing DAO test**

`core/database/src/androidTest/kotlin/app/muplay/database/BrowseDaoTest.kt`:

```kotlin
package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.BrowseDao
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BrowseDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    dao = db.browseDao()
  }

  @After
  fun tearDown() = db.close()

  private fun artist(id: String, name: String, libraryId: Int) =
    ArtistEntity(id, libraryId, name, coverArtId = null, albumCount = 1, sortName = name.lowercase())

  private fun album(id: String, name: String, libraryId: Int, artistId: String? = "artist-1") =
    AlbumEntity(id, libraryId, artistId, name, "Test Artist", null, 1, 5, name.lowercase())

  private fun song(id: String, title: String, libraryId: Int, albumId: String, track: Int?) =
    SongEntity(id, libraryId, albumId, "artist-1", title, "Test Album", "Test Artist", track, null, 5, "mp3", null, title.lowercase())

  private suspend fun seedTwoLibraries() {
    dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1)),
      songs = listOf(
        song("song-2", "Track 2", 1, "album-1", 2),
        song("song-1", "Track 1", 1, "album-1", 1),
      ),
    )
    dao.replaceLibraryContents(
      libraryId = 2,
      artists = listOf(artist("artist-2", "Test Author", 2)),
      albums = listOf(album("album-2", "Test Book", 2, artistId = "artist-2")),
      songs = listOf(song("song-3", "Test Book", 2, "album-2", null)),
    )
  }

  @Test
  fun everyBrowseQueryIsScopedToOneLibrary() = runTest {
    seedTwoLibraries()

    // The assertion the whole application rests on, at the storage layer: asking for library 1
    // returns nothing from library 2. Everything above this -- browse, search, shuffle -- is only
    // as scoped as this is.
    assertThat(dao.observeAlbums(1).first().map { it.name }).containsExactly("Test Album")
    assertThat(dao.observeAlbums(2).first().map { it.name }).containsExactly("Test Book")
    assertThat(dao.observeArtists(1).first().map { it.name }).containsExactly("Test Artist")
    assertThat(dao.observeArtists(2).first().map { it.name }).containsExactly("Test Author")
  }

  @Test
  fun songsComeBackInDiscThenTrackOrder() = runTest {
    seedTwoLibraries()

    assertThat(dao.observeSongs("album-1").first().map { it.title })
      .containsExactly("Track 1", "Track 2")
  }

  @Test
  fun replacingALibraryRemovesWhatTheServerNoLongerHas() = runTest {
    seedTwoLibraries()

    // The case a delta protocol cannot express at all: Subsonic never reports deletions, so the
    // only way to notice one is to replace the library wholesale with what the server just said.
    val result = dao.replaceLibraryContents(
      libraryId = 1,
      artists = listOf(artist("artist-1", "Test Artist", 1)),
      albums = listOf(album("album-1", "Test Album", 1)),
      songs = listOf(song("song-1", "Track 1", 1, "album-1", 1)),
    )

    assertThat(dao.observeSongs("album-1").first().map { it.id }).containsExactly("song-1")
    assertThat(result.songsBefore).isEqualTo(2)
    assertThat(result.songsAfter).isEqualTo(1)
  }

  @Test
  fun replacingOneLibraryLeavesTheOtherAlone() = runTest {
    seedTwoLibraries()

    dao.replaceLibraryContents(1, emptyList(), emptyList(), emptyList())

    assertThat(dao.observeAlbums(1).first()).isEmpty()
    assertThat(dao.observeAlbums(2).first().map { it.name }).containsExactly("Test Book")
    assertThat(dao.observeSongs("album-2").first()).hasSize(1)
  }

  /**
   * Spec section 3, as an assertion: the queue is a list of pointers and progress is a property
   * of the item. A reconcile wipes and re-inserts the whole song table for a library; if that
   * could take a listener's position with it, the resume feature would silently break on the
   * first server rescan — which is exactly when nobody would connect the two events.
   */
  @Test
  fun reconcilingTheMirrorDoesNotTouchPlaybackProgress() = runTest {
    seedTwoLibraries()
    db.mediaProgressDao().upsert(
      MediaProgressEntity("song-3", 900_000L, false, 1_000L, 1.0f, false, 0f),
    )

    dao.replaceLibraryContents(2, emptyList(), emptyList(), emptyList())

    assertThat(db.mediaProgressDao().find("song-3")!!.positionMs).isEqualTo(900_000L)
  }

  @Test
  fun searchIsScopedAndCaseInsensitive() = runTest {
    seedTwoLibraries()

    assertThat(dao.searchSongs(1, "%track%", 10).map { it.title })
      .containsExactlyInAnyOrder("Track 1", "Track 2")
    assertThat(dao.searchSongs(2, "%track%", 10)).isEmpty()
    assertThat(dao.searchAlbums(1, "%album%", 10).map { it.name }).containsExactly("Test Album")
    assertThat(dao.searchArtists(2, "%author%", 10).map { it.name }).containsExactly("Test Author")
  }

  @Test
  fun searchRespectsItsLimit() = runTest {
    seedTwoLibraries()

    assertThat(dao.searchSongs(1, "%track%", 1)).hasSize(1)
  }

  /**
   * The query that backs the shuffle scope guard. Given a set of song ids the server just
   * returned, it answers "which of these does the mirror agree are in this library" — which is
   * how a scope leak is caught locally even if the server's own scoping ever fails.
   */
  @Test
  fun songIdsInLibraryFiltersOutForeignAndUnknownIds() = runTest {
    seedTwoLibraries()

    val kept = dao.songIdsInLibrary(1, listOf("song-1", "song-3", "does-not-exist"))

    assertThat(kept).containsExactly("song-1")
  }

  @Test
  fun observingAlbumsByArtistCrossesNoLibraryBoundary() = runTest {
    seedTwoLibraries()

    assertThat(dao.observeAlbumsByArtist("artist-2").first().map { it.name })
      .containsExactly("Test Book")
  }

  @Test
  fun findAlbumReturnsNullForAnUnknownId() = runTest {
    seedTwoLibraries()

    assertThat(dao.findAlbum("nope")).isNull()
    assertThat(dao.findAlbum("album-1")!!.name).isEqualTo("Test Album")
  }
}
```

- [ ] **Step 7: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*BrowseDaoTest*'`
Expected: FAIL — `Unresolved reference: browseDao`.

- [ ] **Step 8: Write the DAO**

`core/database/src/main/kotlin/app/muplay/database/dao/BrowseDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.ArtistEntity
import app.muplay.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/** Row counts either side of one [BrowseDao.replaceLibraryContents] call. */
data class MirrorReplacement(
  val artistsBefore: Int,
  val artistsAfter: Int,
  val albumsBefore: Int,
  val albumsAfter: Int,
  val songsBefore: Int,
  val songsAfter: Int,
)

/**
 * Every read the browse UI makes, and the one write the sync engine makes.
 *
 * **Every query takes a `libraryId`** except the two that take an id already known to belong to
 * one library (`observeSongs(albumId)`, `observeAlbumsByArtist(artistId)`). That is not
 * boilerplate: an unscoped browse query is how an audiobook ends up in a music list, and the
 * absence of a "give me everything" query is the point.
 */
@Dao
abstract class BrowseDao {

  @Query("SELECT * FROM artists WHERE libraryId = :libraryId ORDER BY sortName")
  abstract fun observeArtists(libraryId: Int): Flow<List<ArtistEntity>>

  @Query("SELECT * FROM albums WHERE libraryId = :libraryId ORDER BY sortName")
  abstract fun observeAlbums(libraryId: Int): Flow<List<AlbumEntity>>

  @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY sortName")
  abstract fun observeAlbumsByArtist(artistId: String): Flow<List<AlbumEntity>>

  @Query(
    "SELECT * FROM songs WHERE albumId = :albumId " +
      "ORDER BY COALESCE(discNumber, 0), COALESCE(trackNumber, 0), sortTitle",
  )
  abstract fun observeSongs(albumId: String): Flow<List<SongEntity>>

  @Query("SELECT * FROM albums WHERE id = :albumId")
  abstract suspend fun findAlbum(albumId: String): AlbumEntity?

  // `pattern` is a full LIKE pattern including its wildcards, built by the caller -- so a query
  // containing a literal % or _ is the caller's problem to escape, once, rather than every query
  // here silently doing something different. SQLite's LIKE is case-insensitive for ASCII by
  // default, which is why these compare against the raw column and not against sortName.
  @Query("SELECT * FROM artists WHERE libraryId = :libraryId AND name LIKE :pattern ORDER BY sortName LIMIT :limit")
  abstract suspend fun searchArtists(libraryId: Int, pattern: String, limit: Int): List<ArtistEntity>

  @Query("SELECT * FROM albums WHERE libraryId = :libraryId AND name LIKE :pattern ORDER BY sortName LIMIT :limit")
  abstract suspend fun searchAlbums(libraryId: Int, pattern: String, limit: Int): List<AlbumEntity>

  @Query("SELECT * FROM songs WHERE libraryId = :libraryId AND title LIKE :pattern ORDER BY sortTitle LIMIT :limit")
  abstract suspend fun searchSongs(libraryId: Int, pattern: String, limit: Int): List<SongEntity>

  /**
   * Which of [ids] the mirror agrees are songs in [libraryId]. Backs the shuffle scope guard: a
   * song the server returned for a "music" shuffle that this mirror says lives in the audiobook
   * library is dropped rather than played.
   */
  @Query("SELECT id FROM songs WHERE libraryId = :libraryId AND id IN (:ids)")
  abstract suspend fun songIdsInLibrary(libraryId: Int, ids: List<String>): List<String>

  /**
   * Replaces **everything** the mirror holds for one library, in one transaction.
   *
   * A full replace rather than a diff because Subsonic never reports deletions: there is no delta
   * primitive that can say "this album is gone", so the only reliable way to notice is to keep
   * exactly what the server just listed. Scoped to one library so a failure while reconciling the
   * audiobook library cannot empty the music library.
   */
  @Transaction
  open suspend fun replaceLibraryContents(
    libraryId: Int,
    artists: List<ArtistEntity>,
    albums: List<AlbumEntity>,
    songs: List<SongEntity>,
  ): MirrorReplacement {
    val artistsBefore = countArtists(libraryId)
    val albumsBefore = countAlbums(libraryId)
    val songsBefore = countSongs(libraryId)

    deleteSongs(libraryId)
    deleteAlbums(libraryId)
    deleteArtists(libraryId)

    insertArtists(artists)
    insertAlbums(albums)
    insertSongs(songs)

    return MirrorReplacement(
      artistsBefore = artistsBefore,
      artistsAfter = countArtists(libraryId),
      albumsBefore = albumsBefore,
      albumsAfter = countAlbums(libraryId),
      songsBefore = songsBefore,
      songsAfter = countSongs(libraryId),
    )
  }

  @Query("SELECT COUNT(*) FROM artists WHERE libraryId = :libraryId")
  protected abstract suspend fun countArtists(libraryId: Int): Int

  @Query("SELECT COUNT(*) FROM albums WHERE libraryId = :libraryId")
  protected abstract suspend fun countAlbums(libraryId: Int): Int

  @Query("SELECT COUNT(*) FROM songs WHERE libraryId = :libraryId")
  protected abstract suspend fun countSongs(libraryId: Int): Int

  @Query("DELETE FROM artists WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteArtists(libraryId: Int)

  @Query("DELETE FROM albums WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteAlbums(libraryId: Int)

  @Query("DELETE FROM songs WHERE libraryId = :libraryId")
  protected abstract suspend fun deleteSongs(libraryId: Int)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertArtists(artists: List<ArtistEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertAlbums(albums: List<AlbumEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertSongs(songs: List<SongEntity>)
}
```

- [ ] **Step 9: Register the entities and write the repository**

`core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`:

```kotlin
@Database(
  entities = [
    MediaProgressEntity::class,
    LibraryEntity::class,
    ArtistEntity::class,
    AlbumEntity::class,
    SongEntity::class,
  ],
  version = 3,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {

  abstract fun mediaProgressDao(): MediaProgressDao

  abstract fun libraryDao(): LibraryDao

  abstract fun browseDao(): BrowseDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
```

`core/database/src/main/kotlin/app/muplay/database/BrowseRepository.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.SearchResults
import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The browse surface, read entirely from the local mirror.
 *
 * Reading locally rather than per-screen from the server is what makes browsing work offline and
 * instantly, and it is why `SyncEngine` exists. [search] is a mirror search for the same reason:
 * `search3` is implemented and contract-tested on the client, but a complete mirror can answer
 * the same question with no network and no scoping risk.
 */
@Singleton
class BrowseRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  fun artists(libraryId: Int): Flow<List<Artist>> =
    browseDao.observeArtists(libraryId).map { rows -> rows.map(MirrorMapper::artist) }

  fun albums(libraryId: Int): Flow<List<Album>> =
    browseDao.observeAlbums(libraryId).map { rows -> rows.map(MirrorMapper::album) }

  fun albumsByArtist(artistId: String): Flow<List<Album>> =
    browseDao.observeAlbumsByArtist(artistId).map { rows -> rows.map(MirrorMapper::album) }

  fun songs(albumId: String): Flow<List<Song>> =
    browseDao.observeSongs(albumId).map { rows -> rows.map(MirrorMapper::song) }

  suspend fun album(albumId: String): Album? =
    browseDao.findAlbum(albumId)?.let(MirrorMapper::album)

  /**
   * Searches the mirror within one library.
   *
   * The LIKE pattern is built here, once, and the user's own `%` and `_` are escaped with a
   * backslash so a query containing them matches those characters literally instead of turning
   * into a wildcard the user did not type.
   */
  suspend fun search(libraryId: Int, query: String, limit: Int): SearchResults {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
    val pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    return SearchResults(
      artists = browseDao.searchArtists(libraryId, pattern, limit).map(MirrorMapper::artist),
      albums = browseDao.searchAlbums(libraryId, pattern, limit).map(MirrorMapper::album),
      songs = browseDao.searchSongs(libraryId, pattern, limit).map(MirrorMapper::song),
    )
  }

  /** An authenticated cover-art URL for the current server. */
  suspend fun coverArtUrl(coverArtId: String, sizePx: Int?): String =
    sourceProvider.current().coverArtUrl(coverArtId, sizePx)
}
```

`core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` — add:

```kotlin
  @Provides
  fun provideBrowseDao(database: MuPlayDatabase): BrowseDao = database.browseDao()
```

**Note the LIKE escaping**: SQLite's `LIKE` only honours a backslash escape when the query says
`ESCAPE '\'`. Add that clause to the three search queries in `BrowseDao`:

```kotlin
  @Query("SELECT * FROM songs WHERE libraryId = :libraryId AND title LIKE :pattern ESCAPE '\\' ORDER BY sortTitle LIMIT :limit")
```

and the same for `searchArtists` and `searchAlbums`. Without it the escaping in `search` is
inert and a query of `%` matches everything.

- [ ] **Step 10: Run, measure, commit**

Run: `./gradlew :core:database:test` — `MirrorMapperTest` 8/8.
Run: `./gradlew :core:database:connectedDebugAndroidTest` — `BrowseDaoTest` 10/10 plus the
earlier suites.

Re-measure `:core:database`'s floors and update `coverageFloors`. `MirrorMapper` is
JVM-measurable, so its floor must **not** be marked `requiresInstrumentedData`; the DAO and
repository classes are instrumented-only and must be.

```bash
git add core/database build.gradle.kts
git commit -m "feat(database): the library mirror, scoped browse queries and search"
```


---

## Task 6: The sync engine — `getScanStatus` watermark and full reconcile

**Files:**
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/SyncWatermarkEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/SyncWatermarkDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/SyncDecision.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/SyncState.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/SyncEngine.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Test: `core/database/src/test/kotlin/app/muplay/database/SyncDecisionTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `ScanStatus(isScanning, scannedCount, lastScan)`, `SubsonicSource`,
  `SubsonicSourceProvider`, `LibraryRepository`, `BrowseDao`, `MirrorReplacement`,
  `MirrorMapper`, `SubsonicClient.MAX_ALBUM_LIST_PAGE`
- Produces:
  - `SyncWatermarkEntity(id: Int, lastScan: String)`, table `sync_watermark`, single row `id = 0`
  - `abstract class SyncWatermarkDao` with `suspend read(): String?` and `suspend store(lastScan: String)`
  - sealed `SyncDecision` with `UpToDate`, `ScanInProgress`, `Reconcile(watermark: String?)`, and
    `SyncDecision.decide(stored: String?, status: ScanStatus): SyncDecision`
  - sealed `SyncState` with `UpToDate`, `ScanInProgress`,
    `Synced(libraries: Map<Int, MirrorReplacement>)`, `Failed(cause: Throwable)`
  - `class SyncEngine(libraryRepository, browseDao, watermarkDao, sourceProvider, albumPageSize: Int)`
    with `suspend fun syncIfStale(): SyncState`
  - `SyncEngine.MAX_PAGES = 200`
  - `MuPlayDatabase.syncWatermarkDao(): SyncWatermarkDao`, schema version **4**

### Why a watermark and a full reconcile, and not a delta

Spec §4 is blunt about this. `getIndexes?ifModifiedSince=` is the only delta primitive Subsonic
has; Navidrome compares it against **one global watermark**, so you learn *that* something
changed and never *what* — and only for artists. **Deletions are never reported at all.** A
client that trusts deltas accumulates ghost albums forever.

So MuPlay uses `getScanStatus`, which Navidrome extends with a monotonic `lastScan`. Confirmed
live: `"lastScan": "2026-08-24T03:32:38.477978062Z"` alongside `scanning`, `count`, `folderCount`,
`scanType` and `elapsedTime` — four fields the vendored OpenAPI spec does not model, which is
why `NavidromeSpecDeviationTest` pins them by name.

`lastScan` is treated as an **opaque token**, never parsed as a timestamp. The only question
asked of it is "is this the same string I last committed?", which is exactly as much as the
design needs and immune to a format change.

> **Trap, spec §4:** Tempo's `getScanStatus()` calls `startScan()`, re-triggering a full server
> scan on every poll. `getScanStatus` is a **read**. `BrowseEndpointsTest` asserts this client's
> request goes to `getScanStatus` and carries no `fullScan` parameter.

### The two orderings that matter

1. **Never reconcile while `scanning == true`.** A mid-scan server reports a partially-populated
   library; mirroring it and then storing a watermark that says "done" leaves the mirror
   permanently missing whatever had not been scanned yet. Spec §4 phrases this as "require seeing
   `scanning == true` first to avoid a race, invalidate on true→false"; the same protection falls
   out of refusing to reconcile mid-scan, because `lastScan` only moves when a scan finishes.
2. **Advance the watermark only after every library's transaction has committed.** If it
   advances first, a failed sync is never retried and the mirror stays permanently stale — the
   single worst outcome available here, because it is silent and self-perpetuating.

- [ ] **Step 1: Write the failing pure-decision test**

`core/database/src/test/kotlin/app/muplay/database/SyncDecisionTest.kt` — the watermark ruling is
pure, so it is a JVM test in Tier 1:

```kotlin
package app.muplay.database

import app.muplay.model.ScanStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SyncDecisionTest {

  private fun status(scanning: Boolean = false, lastScan: String? = "s1") =
    ScanStatus(isScanning = scanning, scannedCount = 4, lastScan = lastScan)

  @Test
  fun `an unchanged watermark means there is nothing to do`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = "s1")))
      .isEqualTo(SyncDecision.UpToDate)
  }

  @Test
  fun `a moved watermark triggers a reconcile carrying the new value`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = "s2")))
      .isEqualTo(SyncDecision.Reconcile("s2"))
  }

  @Test
  fun `the first ever sync reconciles`() {
    assertThat(SyncDecision.decide(stored = null, status = status(lastScan = "s1")))
      .isEqualTo(SyncDecision.Reconcile("s1"))
  }

  @Test
  fun `a scan in progress is never a reason to reconcile`() {
    // A mid-scan server reports a partially-populated library. Mirroring that and then storing a
    // watermark saying "done" leaves the mirror permanently short of whatever had not been
    // scanned yet -- silently, and with nothing to trigger a retry.
    assertThat(SyncDecision.decide(stored = "s1", status = status(scanning = true, lastScan = "s2")))
      .isEqualTo(SyncDecision.ScanInProgress)
    // Even when the watermark has not moved: "busy" is not "up to date".
    assertThat(SyncDecision.decide(stored = "s1", status = status(scanning = true, lastScan = "s1")))
      .isEqualTo(SyncDecision.ScanInProgress)
  }

  @Test
  fun `a server that reports no lastScan reconciles every time and stores nothing`() {
    // `lastScan` is Navidrome's extension; a plain Subsonic server does not send it. Without it
    // there is no way to tell whether anything changed, so the safe answer is to reconcile -- and
    // to carry a null watermark, so nothing is stored that would later read as "up to date"
    // against a server that never says otherwise.
    val decision = SyncDecision.decide(stored = null, status = status(lastScan = null))

    assertThat(decision).isEqualTo(SyncDecision.Reconcile(null))
  }

  @Test
  fun `a stored watermark does not suppress a reconcile when the server stops reporting one`() {
    assertThat(SyncDecision.decide(stored = "s1", status = status(lastScan = null)))
      .isEqualTo(SyncDecision.Reconcile(null))
  }
}
```

- [ ] **Step 2: Run it, confirm it fails, implement**

Run: `./gradlew :core:database:test --tests '*SyncDecisionTest*'`
Expected: FAIL — `Unresolved reference: SyncDecision`.

`core/database/src/main/kotlin/app/muplay/database/SyncDecision.kt`:

```kotlin
package app.muplay.database

import app.muplay.model.ScanStatus

/**
 * What to do about the server's current scan state, given the watermark last committed.
 *
 * A sealed interface so the engine's `when` is exhaustive: adding a fourth outcome later cannot
 * be silently ignored by the one place that acts on it. Pure and free of collaborators, so the
 * rule that decides whether a sync happens at all is unit-tested on the JVM rather than only
 * observable through a database.
 */
sealed interface SyncDecision {

  /** The server has not rescanned since the last committed reconcile. */
  data object UpToDate : SyncDecision

  /** The server is scanning right now. Do nothing, and do not store anything; ask again later. */
  data object ScanInProgress : SyncDecision

  /**
   * Reconcile every library, then store [watermark] — **after** the last transaction commits.
   *
   * [watermark] is null when the server reports no `lastScan` at all (a plain Subsonic server, or
   * a future Navidrome that drops the extension). Storing nothing in that case is deliberate: a
   * stored value would later compare equal to a server that keeps not sending one and freeze the
   * mirror forever. Reconciling every time is wasteful; a mirror that never updates is wrong.
   */
  data class Reconcile(val watermark: String?) : SyncDecision

  companion object {
    fun decide(stored: String?, status: ScanStatus): SyncDecision = when {
      status.isScanning -> ScanInProgress
      status.lastScan == null -> Reconcile(null)
      status.lastScan == stored -> UpToDate
      else -> Reconcile(status.lastScan)
    }
  }
}
```

Run again: PASS, 6/6.

- [ ] **Step 3: Write the failing engine test**

`core/database/src/androidTest/kotlin/app/muplay/database/SyncEngineTest.kt`:

```kotlin
package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var engine: SyncEngine

  private fun album(id: String, name: String, libraryId: Int) = Album(
    id = id,
    libraryId = libraryId,
    name = name,
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    coverArtId = "al-$id",
    songCount = 1,
    durationSeconds = 5,
  )

  private fun song(id: String, title: String, libraryId: Int, albumId: String) = Song(
    id = id,
    libraryId = libraryId,
    title = title,
    albumId = albumId,
    albumName = "Album $albumId",
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = "al-${albumId}_0",
  )

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "sync-engine-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))

    source = FakeSubsonicSource().apply {
      musicFolders = listOf(
        MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
        MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
      )
      albumsByLibrary = mapOf(
        1 to listOf(album("album-1", "Test Album", 1), album("album-2", "Second Album", 1)),
        2 to listOf(album("book-1", "Test Book", 2)),
      )
      songsByAlbum = mapOf(
        "album-1" to listOf(song("song-1", "Track 1", 1, "album-1")),
        "album-2" to listOf(song("song-2", "Track 2", 1, "album-2")),
        "book-1" to listOf(song("chapter-1", "Chapter 1", 2, "book-1")),
      )
      scanStatus = ScanStatus(isScanning = false, scannedCount = 3, lastScan = "s1")
    }

    val sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source })
    engine = SyncEngine(
      libraryRepository = LibraryRepository(db.libraryDao(), sourceProvider),
      browseDao = db.browseDao(),
      watermarkDao = db.syncWatermarkDao(),
      sourceProvider = sourceProvider,
      // One album per page, so the paging loop is exercised by two albums instead of 501.
      albumPageSize = 1,
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  @Test
  fun theFirstSyncMirrorsEveryLibrary() = runTest {
    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Synced::class.java)
    assertThat(db.browseDao().observeAlbums(1).first().map { it.name })
      .containsExactly("Second Album", "Test Album")
    assertThat(db.browseDao().observeAlbums(2).first().map { it.name })
      .containsExactly("Test Book")
    assertThat(db.browseDao().observeSongs("book-1").first().map { it.title })
      .containsExactly("Chapter 1")
    // Artists are derived from albums -- this plan never calls getArtists or getIndexes.
    assertThat(db.browseDao().observeArtists(1).first().map { it.name }).containsExactly("Artist 1")
    assertThat(source.callLog).noneMatch { it.startsWith("getArtists") }
  }

  @Test
  fun everyMirroredRowCarriesTheLibraryItWasFetchedFor() = runTest {
    engine.syncIfStale()

    // The stamp is the only link between a track and the user's Music/Audiobooks decision, and
    // the reconcile is where it could be lost by merging two libraries' pages.
    assertThat(db.browseDao().observeAlbums(1).first()).allMatch { it.libraryId == 1 }
    assertThat(db.browseDao().observeAlbums(2).first()).allMatch { it.libraryId == 2 }
    assertThat(db.browseDao().songIdsInLibrary(1, listOf("chapter-1"))).isEmpty()
    assertThat(db.browseDao().songIdsInLibrary(2, listOf("chapter-1"))).containsExactly("chapter-1")
  }

  @Test
  fun anUnchangedWatermarkSkipsTheReconcileEntirely() = runTest {
    engine.syncIfStale()
    source.callLog.clear()

    val state = engine.syncIfStale()

    assertThat(state).isEqualTo(SyncState.UpToDate)
    assertThat(source.callLog).noneMatch { it.startsWith("getAlbumList2") }
  }

  @Test
  fun aMovedWatermarkReconcilesAgain() = runTest {
    engine.syncIfStale()
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")
    source.callLog.clear()

    assertThat(engine.syncIfStale()).isInstanceOf(SyncState.Synced::class.java)
    assertThat(source.callLog).anyMatch { it.startsWith("getAlbumList2") }
  }

  /** The case a delta protocol cannot express: Subsonic never reports a deletion. */
  @Test
  fun anAlbumDeletedOnTheServerVanishesFromTheMirror() = runTest {
    engine.syncIfStale()
    source.albumsByLibrary = source.albumsByLibrary + (1 to listOf(album("album-1", "Test Album", 1)))
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")

    engine.syncIfStale()

    assertThat(db.browseDao().observeAlbums(1).first().map { it.id }).containsExactly("album-1")
    assertThat(db.browseDao().observeSongs("album-2").first()).isEmpty()
  }

  /**
   * The most important test in this file. If the watermark advances before the reconcile
   * commits, a failed sync is never retried and the mirror stays permanently stale — silently,
   * and with nothing that would ever trigger a repair.
   *
   * A real Navidrome cannot be asked to fail on the fourth of seven calls, which is exactly why
   * `FakeSubsonicSource` exists.
   */
  @Test
  fun aFailureMidReconcileDoesNotAdvanceTheWatermark() = runTest {
    // getScanStatus, getMusicFolders, then the paging and album calls -- fail once inside them.
    source.failAfterCalls = 4

    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Failed::class.java)
    assertThat(db.syncWatermarkDao().read()).isNull()

    // ...and the retry, with the failure removed, genuinely reconciles rather than reporting
    // "up to date" off a watermark the failed attempt should never have written.
    source.failAfterCalls = null
    assertThat(engine.syncIfStale()).isInstanceOf(SyncState.Synced::class.java)
    assertThat(db.browseDao().observeAlbums(1).first()).hasSize(2)
    assertThat(db.syncWatermarkDao().read()).isEqualTo("s1")
  }

  @Test
  fun aScanInProgressReconcilesNothingAndStoresNothing() = runTest {
    source.scanStatus = ScanStatus(isScanning = true, scannedCount = 1, lastScan = "s2")

    val state = engine.syncIfStale()

    assertThat(state).isEqualTo(SyncState.ScanInProgress)
    assertThat(db.browseDao().observeAlbums(1).first()).isEmpty()
    assertThat(db.syncWatermarkDao().read()).isNull()
    assertThat(source.callLog).noneMatch { it.startsWith("getAlbumList2") }
  }

  @Test
  fun aNewLibraryOnTheServerIsPickedUpByTheNextSync() = runTest {
    engine.syncIfStale()

    source.musicFolders = source.musicFolders + MusicLibrary(3, "Podcasts", LibraryRole.UNASSIGNED)
    source.albumsByLibrary = source.albumsByLibrary + (3 to listOf(album("pod-1", "A Podcast", 3)))
    source.songsByAlbum = source.songsByAlbum + ("pod-1" to listOf(song("ep-1", "Episode 1", 3, "pod-1")))
    source.scanStatus = source.scanStatus.copy(lastScan = "s2")

    engine.syncIfStale()

    assertThat(db.browseDao().observeAlbums(3).first().map { it.name }).containsExactly("A Podcast")
    // ...and it arrives untagged, because nothing may guess a role from a name.
    assertThat(db.libraryDao().find(3)!!.role).isEqualTo(LibraryRole.UNASSIGNED)
  }

  @Test
  fun theEngineReportsWhatItChangedPerLibrary() = runTest {
    val state = engine.syncIfStale() as SyncState.Synced

    assertThat(state.libraries.keys).containsExactlyInAnyOrder(1, 2)
    assertThat(state.libraries.getValue(1).albumsBefore).isEqualTo(0)
    assertThat(state.libraries.getValue(1).albumsAfter).isEqualTo(2)
    assertThat(state.libraries.getValue(2).songsAfter).isEqualTo(1)
  }

  @Test
  fun syncingWithNoCredentialsFailsRatherThanThrowing() = runTest {
    credentialStore.clear()

    // The engine is called from a ViewModel's coroutine, so an escaping exception would surface
    // as a crash rather than as a state the UI can render.
    val state = engine.syncIfStale()

    assertThat(state).isInstanceOf(SyncState.Failed::class.java)
    assertThat((state as SyncState.Failed).cause).isInstanceOf(NotConfiguredException::class.java)
  }
}
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*SyncEngineTest*'`
Expected: FAIL — `Unresolved reference: SyncEngine`, `SyncState`, `syncWatermarkDao`.

- [ ] **Step 5: Implement the watermark storage**

`core/database/src/main/kotlin/app/muplay/database/entity/SyncWatermarkEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The last `getScanStatus.lastScan` value whose reconcile **committed**.
 *
 * One row, always id [SINGLETON_ID]. Navidrome's `lastScan` is global rather than per-library, so
 * a per-library watermark would be inventing a distinction the server does not make.
 *
 * [lastScan] is stored as the server's own string and never parsed: the only question asked of it
 * is whether it is the same string as before.
 */
@Entity(tableName = "sync_watermark")
data class SyncWatermarkEntity(
  @PrimaryKey val id: Int = SINGLETON_ID,
  val lastScan: String,
) {
  companion object {
    const val SINGLETON_ID = 0
  }
}
```

`core/database/src/main/kotlin/app/muplay/database/dao/SyncWatermarkDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.muplay.database.entity.SyncWatermarkEntity

@Dao
abstract class SyncWatermarkDao {

  @Query("SELECT lastScan FROM sync_watermark WHERE id = ${SyncWatermarkEntity.SINGLETON_ID}")
  abstract suspend fun read(): String?

  suspend fun store(lastScan: String) = upsert(SyncWatermarkEntity(lastScan = lastScan))

  @Query("DELETE FROM sync_watermark")
  abstract suspend fun clear()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun upsert(entity: SyncWatermarkEntity)
}
```

- [ ] **Step 6: Implement the state and the engine**

`core/database/src/main/kotlin/app/muplay/database/SyncState.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.MirrorReplacement

/**
 * The outcome of one [SyncEngine.syncIfStale] call — a sealed interface, not a boolean and a
 * nullable message, so every caller's `when` is exhaustive and a new outcome cannot be silently
 * ignored by a screen.
 */
sealed interface SyncState {

  /** The server has not rescanned since the last committed reconcile; the mirror is current. */
  data object UpToDate : SyncState

  /** The server is scanning. Nothing was fetched and nothing was stored; ask again later. */
  data object ScanInProgress : SyncState

  /** Every library was reconciled and the watermark committed. [libraries] is keyed by library id. */
  data class Synced(val libraries: Map<Int, MirrorReplacement>) : SyncState

  /**
   * The attempt failed. The watermark was **not** advanced, so the next attempt will try the
   * whole reconcile again rather than believing itself up to date.
   */
  data class Failed(val cause: Throwable) : SyncState
}
```

`core/database/src/main/kotlin/app/muplay/database/SyncEngine.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.database.dao.MirrorReplacement
import app.muplay.database.dao.SyncWatermarkDao
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.network.SubsonicSource
import kotlinx.coroutines.CancellationException

/**
 * Keeps the local mirror in step with the server.
 *
 * Not `@Inject constructor`: [albumPageSize] is a plain `Int` with no sensible Hilt binding, and
 * making it injectable would mean a qualifier and a `@Provides` for a number. `DataModule`
 * constructs this instead, which also lets a test pass a page size of 1 and exercise the paging
 * loop with two albums rather than 501.
 */
class SyncEngine(
  private val libraryRepository: LibraryRepository,
  private val browseDao: BrowseDao,
  private val watermarkDao: SyncWatermarkDao,
  private val sourceProvider: SubsonicSourceProvider,
  private val albumPageSize: Int,
) {

  /**
   * Reconciles every library if — and only if — the server's `lastScan` has moved since the last
   * committed reconcile.
   *
   * Never throws for an expected failure: a coroutine started by a ViewModel would turn one into
   * a crash rather than into something a screen can render, so everything except cancellation
   * becomes [SyncState.Failed].
   */
  suspend fun syncIfStale(): SyncState = try {
    val source = sourceProvider.current()
    when (val decision = SyncDecision.decide(watermarkDao.read(), source.getScanStatus())) {
      SyncDecision.UpToDate -> SyncState.UpToDate
      SyncDecision.ScanInProgress -> SyncState.ScanInProgress
      is SyncDecision.Reconcile -> reconcile(source, decision.watermark)
    }
  } catch (e: CancellationException) {
    // Cancelling the caller's scope is not a sync failure and must not be reported as one.
    throw e
  } catch (e: Exception) {
    SyncState.Failed(e)
  }

  private suspend fun reconcile(source: SubsonicSource, watermark: String?): SyncState {
    // Libraries first: a library added on the server since the last sync has to exist locally
    // before there is anything to reconcile into, and `mergeFromServer` leaves existing roles
    // alone.
    libraryRepository.refreshFromServer()

    val results = mutableMapOf<Int, MirrorReplacement>()
    for (libraryId in libraryRepository.allIds()) {
      results[libraryId] = reconcileLibrary(source, libraryId)
    }

    // Last, and only now. Advancing the watermark before the transactions commit would mean a
    // failed sync is never retried and the mirror stays permanently stale. A null watermark is
    // deliberately not stored -- see `SyncDecision.Reconcile`.
    watermark?.let { watermarkDao.store(it) }
    return SyncState.Synced(results)
  }

  private suspend fun reconcileLibrary(source: SubsonicSource, libraryId: Int): MirrorReplacement {
    val albums = fetchAllAlbums(source, libraryId)
    val songs = albums.flatMap { source.getAlbum(it.id, libraryId).songs }

    // One transaction per library: a failure reconciling the audiobook library must not be able
    // to empty the music library, and a library is the unit the user actually reasons about.
    return browseDao.replaceLibraryContents(
      libraryId = libraryId,
      artists = MirrorMapper.artistEntities(albums),
      albums = albums.map(MirrorMapper::albumEntity),
      songs = songs.map(MirrorMapper::songEntity),
    )
  }

  private suspend fun fetchAllAlbums(source: SubsonicSource, libraryId: Int): List<Album> {
    val albums = mutableListOf<Album>()
    var page = 0
    while (page < MAX_PAGES) {
      val batch = source.getAlbumList2(
        musicFolderId = libraryId,
        type = AlbumListType.ALPHABETICAL_BY_NAME,
        size = albumPageSize,
        offset = page * albumPageSize,
      )
      albums += batch
      // A short page is the last page. A past-the-end offset returns an empty list rather than an
      // error -- confirmed live, where the server sends `"albumList2": {}` with no album key.
      if (batch.size < albumPageSize) return albums
      page++
    }
    // A server that keeps returning full pages forever would otherwise spin here until the
    // process died. Failing loudly at a bound nothing real reaches is the lesser evil, and it
    // becomes SyncState.Failed rather than a hang the user cannot interpret.
    error("getAlbumList2 for library $libraryId did not terminate within $MAX_PAGES pages")
  }

  companion object {
    /** 200 pages of 500 is 100,000 albums — far past any real library, and not infinity. */
    const val MAX_PAGES = 200
  }
}
```

- [ ] **Step 7: Register the entity and provide the engine**

`MuPlayDatabase` — add `SyncWatermarkEntity::class` to `entities`, bump `version` to **4**, add
`abstract fun syncWatermarkDao(): SyncWatermarkDao`.

`core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` — add:

```kotlin
  @Provides
  fun provideSyncWatermarkDao(database: MuPlayDatabase): SyncWatermarkDao =
    database.syncWatermarkDao()

  @Provides
  @Singleton
  fun provideSyncEngine(
    libraryRepository: LibraryRepository,
    browseDao: BrowseDao,
    watermarkDao: SyncWatermarkDao,
    sourceProvider: SubsonicSourceProvider,
  ): SyncEngine = SyncEngine(
    libraryRepository = libraryRepository,
    browseDao = browseDao,
    watermarkDao = watermarkDao,
    sourceProvider = sourceProvider,
    albumPageSize = SubsonicClient.MAX_ALBUM_LIST_PAGE,
  )
```

with imports for `SyncWatermarkDao`, `SyncEngine` and `app.muplay.network.SubsonicClient`.

- [ ] **Step 8: Run, prove the ordering guard fires, measure, commit**

Run: `./gradlew :core:database:test` — `SyncDecisionTest` 6/6, `MirrorMapperTest` 8/8.
Run: `./gradlew :core:database:connectedDebugAndroidTest` — `SyncEngineTest` 10/10 plus the
earlier suites.

**Prove `aFailureMidReconcileDoesNotAdvanceTheWatermark` can fail.** Move
`watermark?.let { watermarkDao.store(it) }` to the line *before* the `for` loop in `reconcile`,
re-run that test, and confirm it goes red on `db.syncWatermarkDao().read()` being `"s1"` instead
of null. Put the line back. A test for an ordering constraint that has never been seen to fail
when the order is wrong is not evidence of anything.

Re-measure `:core:database`'s floors and update `coverageFloors`. `SyncDecision` is JVM-measured;
`SyncEngine` is instrumented-only.

```bash
git add core/database build.gradle.kts
git commit -m "feat(database): scan-watermark sync with a full reconcile"
```


---

## Task 7: Library-scoped shuffle — the headline feature

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/ShuffleResult.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/ShuffleRepository.kt`
- Modify: `core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/ShuffleRepositoryTest.kt`

**Interfaces:**
- Consumes: `SubsonicSource.getRandomSongs(musicFolderId: Int, size: Int): List<Song>`,
  `BrowseDao.songIdsInLibrary(libraryId: Int, ids: List<String>): List<String>`,
  `SubsonicSourceProvider`, `SubsonicClient.MAX_RANDOM_SONGS`, `SubsonicAuth.authParams`
- Produces:
  - `ShuffleResult(songs: List<Song>, discardedOutOfScope: Int)`
  - `class ShuffleRepository @Inject constructor(browseDao, sourceProvider)` with
    `suspend fun shuffle(libraryId: Int, requestedSize: Int): ShuffleResult`
  - `ShuffleRepository.DEFAULT_SHUFFLE_SIZE = 100`

### This is why MuPlay exists

Symfonium cannot restrict random playback to a library, which is the specific reason the user
cannot use it: hitting shuffle pulls audiobook chapters into a music session. `getRandomSongs`
honours `musicFolderId`, so the mechanism exists — the work is making sure it can never be
subverted, because the failure is **silent**. Nothing errors. Chapter 14 of a novel simply
starts playing after a song.

Three independent defences, because one that fails silently is worth very little:

1. **The type.** `getRandomSongs(musicFolderId: Int, size: Int)` — an `Int`, non-null, rendered
   with `toString()`. Measured live: Navidrome **silently ignores** a `musicFolderId` it cannot
   parse (`abc`, `1abc`, `""`) and answers `status: "ok"` with songs from *every* library. An
   `Int` cannot produce such a value.
2. **The request assertion.** `BrowseEndpointsTest` asserts `musicFolderId=1` is on the wire
   (Task 3). Deleting the parameter fails a test — which, before Plan 2, nothing in this codebase
   could have detected for any parameter at all.
3. **The local scope guard.** Every returned song id is checked against the mirror: a song the
   mirror does not agree is in this library is **dropped**, not played. If the server's own
   scoping ever regressed, the user would get a shorter shuffle, never an audiobook.

And the assertion that ties it to reality: a **live** test, against the real container with its
real `Music` and `Audiobooks` libraries, shuffling fifty times and asserting the audiobook never
appears. That is the assertion the user actually cares about, and only a real server can make it.

> `getRandomSongs` caps `size` at 500 and truncates silently. The clamp lives in `SubsonicClient`
> (Task 3) so the number on the wire and the number a caller reasons about are the same one.

### The scope guard's one trade-off, stated

Dropping a song the mirror does not recognise means a song added on the server since the last
sync is dropped too. That is the right way round: a shuffle that is briefly one track short is a
non-event; a shuffle that plays an audiobook chapter is the bug this application was written to
fix. `ShuffleResult.discardedOutOfScope` makes the drop visible rather than mysterious, and a
sync (Task 6) makes it stop happening.

- [ ] **Step 1: Write the failing live test**

`core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt` — add to the existing
class, keeping its three current tests and its `@Tag("live")`:

```kotlin
  @Test
  fun `shuffling the music library never returns the audiobook`() = runTest {
    // The whole feature in one assertion, against the real server. `ci/configure-libraries.sh`
    // seeds library 1 "Music" with three tracks and library 2 "Audiobooks" with "Test Book";
    // fifty draws over a four-item corpus makes an unscoped result overwhelmingly likely to
    // show up, and a scoped one certain not to.
    val client = client("testpass")

    repeat(50) {
      val titles = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).map { it.title }
      assertThat(titles).isNotEmpty
      assertThat(titles).doesNotContain(AUDIOBOOK_TITLE)
      assertThat(titles).allMatch { it in MUSIC_TITLES }
    }
  }

  @Test
  fun `shuffling the audiobook library returns the audiobook`() = runTest {
    // The control. Without it, the test above would pass just as well against a client that
    // returned nothing at all, or against a server with an empty audiobook library -- which is
    // exactly the shape of the eleventh silent gate this project already shipped once.
    val titles = client("testpass").getRandomSongs(musicFolderId = AUDIOBOOK_LIBRARY_ID, size = 500)
      .map { it.title }

    assertThat(titles).containsExactly(AUDIOBOOK_TITLE)
  }

  @Test
  fun `an unknown numeric library id fails closed`() = runTest {
    // Navidrome rejects a numeric id it does not know rather than widening the scope: error 70,
    // "Library 99 not found or not accessible". Pinned here because the *next* test depends on
    // this being the contrast case.
    val result = runCatching { client("testpass").getRandomSongs(musicFolderId = 99, size = 10) }

    assertThat(result.isFailure).isTrue
    val error = result.exceptionOrNull()
    assertThat(error).isInstanceOf(SubsonicErrorException::class.java)
    assertThat((error as SubsonicErrorException).code).isEqualTo(70)
  }

  /**
   * The trap that makes `musicFolderId` a non-null `Int` everywhere in this codebase, pinned
   * against the real server.
   *
   * A `musicFolderId` Navidrome cannot parse is **silently ignored** — `status: "ok"`, and the
   * response covers every library. This test deliberately bypasses [SubsonicClient], because
   * [SubsonicClient] is built so that this cannot be expressed: it takes an `Int`. The raw
   * request below is the only way to reach the behaviour, and the assertion is what stops anyone
   * "simplifying" that `Int` to a `String` or an `Int?` on the grounds that the server validates
   * its input. It does not.
   */
  @Test
  fun `a non-numeric library id is ignored and silently widens the scope`() = runTest {
    listOf("", "abc", "1abc").forEach { malformed ->
      val titles = rawRandomSongTitles(malformed)

      assertThat(titles).describedAs("musicFolderId='%s'", malformed).contains(AUDIOBOOK_TITLE)
      assertThat(titles).describedAs("musicFolderId='%s'", malformed).containsAll(MUSIC_TITLES)
    }
  }

  @Test
  fun `search3 is scoped by the same mechanism and the same trap`() = runTest {
    val client = client("testpass")

    assertThat(client.search3("Test", MUSIC_LIBRARY_ID, 10, 10, 10).songs.map { it.title })
      .containsExactlyInAnyOrderElementsOf(MUSIC_TITLES)
    assertThat(client.search3("Test", AUDIOBOOK_LIBRARY_ID, 10, 10, 10).songs.map { it.title })
      .containsExactly(AUDIOBOOK_TITLE)
  }

  @Test
  fun `getAlbumList2 is scoped and pages`() = runTest {
    val client = client("testpass")

    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactly("Test Album")
    assertThat(client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).map { it.name })
      .containsExactly("Test Book")
    // The paging loop's termination condition, against the real server: past the end is an empty
    // list, not an error.
    assertThat(client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 99))
      .isEmpty()
  }

  @Test
  fun `getAlbum returns the album's tracks and stamps the library the caller scoped by`() = runTest {
    val client = client("testpass")
    val album = client.getAlbumList2(MUSIC_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 500, 0).single()

    val withSongs = client.getAlbum(album.id, MUSIC_LIBRARY_ID)

    assertThat(withSongs.songs.map { it.title }).containsExactlyInAnyOrderElementsOf(MUSIC_TITLES)
    assertThat(withSongs.songs).allMatch { it.libraryId == MUSIC_LIBRARY_ID }
  }

  @Test
  fun `the real server reports every song as type music including the audiobook`() = runTest {
    // Spec section 4's central claim, asserted rather than quoted: Navidrome hardcodes
    // child.Type = "music" for every media file, so no response can ever tell a client that
    // something is an audiobook. This is why LibraryRole is an out-of-band, user-made decision
    // and why the mirror stamps a library id on every row.
    //
    // Asserted through the raw JSON because `Song` deliberately does not model `type` -- reading
    // a field that is always the same constant would be reading nothing.
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to AUDIOBOOK_LIBRARY_ID.toString()))

    assertThat(body).contains(AUDIOBOOK_TITLE)
    assertThat(body).contains("\"type\":\"music\"")
    assertThat(body).doesNotContain("\"audiobook\"")
  }

  @Test
  fun `getScanStatus reports a lastScan watermark and does not trigger a scan`() = runTest {
    val client = client("testpass")

    val first = client.getScanStatus()
    assertThat(first.isScanning).isFalse
    assertThat(first.lastScan).isNotNull.isNotBlank
    assertThat(first.scannedCount).isEqualTo(SEEDED_TRACK_COUNT)

    // Tempo's getScanStatus calls startScan, re-scanning the whole server on every poll. If this
    // client did the same, the second call would find a scan running (or a moved watermark).
    val second = client.getScanStatus()
    assertThat(second.isScanning).isFalse
    assertThat(second.lastScan).isEqualTo(first.lastScan)
  }
```

and, in the same class, the raw-request helpers and constants:

```kotlin
  /**
   * A `getRandomSongs` request built by hand, so a `musicFolderId` that [SubsonicClient]'s own
   * `Int` parameter makes unrepresentable can still be sent to the real server. Used only to pin
   * the server's silent-widening behaviour.
   */
  private fun rawRandomSongTitles(musicFolderId: String): List<String> {
    val body = rawRest("getRandomSongs", mapOf("size" to "500", "musicFolderId" to musicFolderId))
    return Regex(""""title":"([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList()
  }

  /** One raw Subsonic GET, authenticated exactly as the client authenticates, returning the body. */
  private fun rawRest(command: String, params: Map<String, String>): String {
    val salt = "0123456789abcdef"
    val auth = SubsonicAuth.authParams(
      SubsonicCredentials(baseUrl = baseUrl, username = "admin", password = "testpass"),
      salt,
    )
    val url = "$baseUrl/rest/$command".toHttpUrl().newBuilder().apply {
      (auth + params).forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()
    return OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
      .use { checkNotNull(it.body).string() }
  }

  private companion object {
    /** Library 1, renamed "Music" by ci/configure-libraries.sh; library 1 is path-pinned. */
    const val MUSIC_LIBRARY_ID = 1
    /** Library 2, created as "Audiobooks" at /audiobooks by ci/configure-libraries.sh. */
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val AUDIOBOOK_TITLE = "Test Book"
    val MUSIC_TITLES = listOf("Track 1", "Track 2", "Track 3")
    /** Three mp3s plus one m4b — ci/seed-fixtures.sh, and the count configure-libraries.sh waits for. */
    const val SEEDED_TRACK_COUNT = 4
  }
```

with imports for `app.muplay.model.AlbumListType`, `okhttp3.HttpUrl.Companion.toHttpUrl`,
`okhttp3.OkHttpClient` and `okhttp3.Request`. A fixed salt is fine here and only here: this
helper's subject is the server's parameter handling, not salt freshness, which
`SubsonicClientTest` already asserts on the wire.

- [ ] **Step 2: Run it against the real container and confirm it fails**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
./gradlew :core:network:liveNavidromeTest
```

Expected: FAIL — `Unresolved reference: getRandomSongs` is already resolved from Task 3, so the
real first failure will be whichever assertion is wrong. If it passes on the first run, **stop
and check the container is actually up**: `docker compose -f ci/navidrome.compose.yml down` and
re-run. The task must go red with no server. This is the eleventh silent gate this project
found — `liveNavidromeTest` passing UP-TO-DATE with no Navidrome — and the
`outputs.upToDateWhen { false }` / `outputs.cacheIf { false }` lines in root `build.gradle.kts`
are what closed it. Confirm they are still there before trusting a green run.

- [ ] **Step 3: Write the failing repository test**

`core/database/src/androidTest/kotlin/app/muplay/database/ShuffleRepositoryTest.kt`:

```kotlin
package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShuffleRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var file: File
  private lateinit var dataStore: DataStore<Preferences>
  private lateinit var credentialStore: CredentialStore
  private lateinit var source: FakeSubsonicSource
  private lateinit var repository: ShuffleRepository

  private fun song(id: String, title: String, libraryId: Int) = Song(
    id = id,
    libraryId = libraryId,
    title = title,
    albumId = "album-$libraryId",
    albumName = "Album $libraryId",
    artistId = "artist-$libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  private fun songEntity(id: String, title: String, libraryId: Int) = SongEntity(
    id = id,
    libraryId = libraryId,
    albumId = "album-$libraryId",
    artistId = "artist-$libraryId",
    title = title,
    albumName = "Album $libraryId",
    artistName = "Artist $libraryId",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
    sortTitle = title.lowercase(),
  )

  private fun albumEntity(libraryId: Int) = AlbumEntity(
    id = "album-$libraryId",
    libraryId = libraryId,
    artistId = "artist-$libraryId",
    name = "Album $libraryId",
    artistName = "Artist $libraryId",
    coverArtId = null,
    songCount = 1,
    durationSeconds = 5,
    sortName = "album $libraryId",
  )

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    file = File(context.filesDir, "shuffle-test-${System.nanoTime()}.preferences_pb")
    dataStore = PreferenceDataStoreFactory.create { file }
    credentialStore = CredentialStore(dataStore)
    credentialStore.save(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))
    source = FakeSubsonicSource()

    // A mirror that agrees library 1 holds two music tracks and library 2 one audiobook chapter.
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(albumEntity(1)),
      songs = listOf(songEntity("song-1", "Track 1", 1), songEntity("song-2", "Track 2", 1)),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = listOf(albumEntity(2)),
      songs = listOf(songEntity("chapter-1", "Chapter 1", 2)),
    )

    repository = ShuffleRepository(
      browseDao = db.browseDao(),
      sourceProvider = SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source }),
    )
  }

  @After
  fun tearDown() = runTest {
    credentialStore.clear()
    file.delete()
    db.close()
  }

  @Test
  fun aScopedShuffleReturnsThatLibrarysSongs() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-1", "Track 1", 1), song("song-2", "Track 2", 1)),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.title }).containsExactlyInAnyOrder("Track 1", "Track 2")
    assertThat(result.discardedOutOfScope).isZero
  }

  /**
   * The defence of last resort, and the reason this repository does more than forward a call: if
   * the server's own scoping ever failed — a regression, a proxy rewriting a query string, a
   * `musicFolderId` that arrived unparseable — the mirror still knows which library each track is
   * in, and an audiobook chapter is dropped rather than played.
   */
  @Test
  fun aSongFromAnotherLibraryIsDroppedAndCounted() = runTest {
    source.randomSongsByLibrary = mapOf(
      1 to listOf(
        song("song-1", "Track 1", 1),
        // The server "leaked" an audiobook chapter into a music shuffle.
        song("chapter-1", "Chapter 1", 1),
      ),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.title }).containsExactly("Track 1")
    assertThat(result.discardedOutOfScope).isEqualTo(1)
  }

  @Test
  fun aSongTheMirrorHasNeverSeenIsDropped() = runTest {
    // A track added on the server since the last sync. Dropping it makes the shuffle one track
    // short, which is a non-event; keeping it would mean trusting a claim the mirror cannot
    // check, which is the whole failure mode this guard exists for.
    source.randomSongsByLibrary = mapOf(
      1 to listOf(song("song-1", "Track 1", 1), song("brand-new", "Brand New", 1)),
    )

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs.map { it.id }).containsExactly("song-1")
    assertThat(result.discardedOutOfScope).isEqualTo(1)
  }

  @Test
  fun theRequestedSizeReachesTheServerUnchangedWhenItIsInRange() = runTest {
    source.randomSongsByLibrary = mapOf(1 to listOf(song("song-1", "Track 1", 1)))

    repository.shuffle(libraryId = 1, requestedSize = 25)

    assertThat(source.callLog).contains("getRandomSongs(1, size=25)")
  }

  @Test
  fun theScopeReachesTheServerAsTheLibraryAsked() = runTest {
    source.randomSongsByLibrary = mapOf(2 to listOf(song("chapter-1", "Chapter 1", 2)))

    repository.shuffle(libraryId = 2, requestedSize = 10)

    // The one parameter the whole feature depends on, asserted at this layer too: the repository
    // must not "helpfully" widen or default it.
    assertThat(source.callLog).contains("getRandomSongs(2, size=10)")
  }

  @Test
  fun anEmptyServerResponseIsAnEmptyResultRatherThanAnError() = runTest {
    source.randomSongsByLibrary = emptyMap()

    val result = repository.shuffle(libraryId = 1, requestedSize = 10)

    assertThat(result.songs).isEmpty()
    assertThat(result.discardedOutOfScope).isZero
  }
}
```

- [ ] **Step 4: Run it and confirm it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*ShuffleRepositoryTest*'`
Expected: FAIL — `Unresolved reference: ShuffleRepository`.

- [ ] **Step 5: Implement**

`core/model/src/main/kotlin/app/muplay/model/ShuffleResult.kt`:

```kotlin
package app.muplay.model

/**
 * The outcome of a library-scoped shuffle.
 *
 * [discardedOutOfScope] is the number of songs the server returned that the local mirror does not
 * agree belong to the requested library. It is normally zero. A non-zero value means either that
 * the mirror is behind the server, or that the server's own scoping did not hold — and the two
 * are worth telling apart, which is why this is a count on the result rather than a silent
 * `filter` inside a repository.
 */
data class ShuffleResult(
  val songs: List<Song>,
  val discardedOutOfScope: Int,
)
```

`core/database/src/main/kotlin/app/muplay/database/ShuffleRepository.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.model.ShuffleResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Random playback restricted to one library — the feature this application exists for.
 *
 * Music and audiobooks live in separate Navidrome libraries, and Navidrome hardcodes
 * `child.Type = "music"` for every media file, so nothing in a response can distinguish them. The
 * library id is the only mechanism, and `getRandomSongs` honours `musicFolderId`.
 *
 * This class adds the third defence on top of the type (`Int`, so an unparseable id is
 * unrepresentable) and the request assertion (`BrowseEndpointsTest`): **every returned id is
 * checked against the mirror**, and a song the mirror does not place in this library is dropped.
 * The failure being defended against is silent — an audiobook chapter simply starts playing —
 * so a defence that only works when something else already worked is not enough.
 */
@Singleton
class ShuffleRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  suspend fun shuffle(libraryId: Int, requestedSize: Int): ShuffleResult {
    // The size clamp lives in SubsonicClient: Navidrome caps `size` at 500 and truncates
    // silently, so the number on the wire and the number a caller reasons about are made the
    // same one at the point the request is built, not here.
    val returned = sourceProvider.current().getRandomSongs(libraryId, requestedSize)
    if (returned.isEmpty()) return ShuffleResult(emptyList(), discardedOutOfScope = 0)

    val confirmed = browseDao.songIdsInLibrary(libraryId, returned.map { it.id }).toSet()
    val kept = returned.filter { it.id in confirmed }
    return ShuffleResult(songs = kept, discardedOutOfScope = returned.size - kept.size)
  }

  companion object {
    /**
     * The size the browse UI asks for. Well under the protocol's 500 cap, and large enough that a
     * shuffle session does not run dry mid-listen.
     */
    const val DEFAULT_SHUFFLE_SIZE = 100
  }
}
```

- [ ] **Step 6: Run both suites**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*ShuffleRepositoryTest*'`
Expected: PASS, 6/6.

Run (container up): `./gradlew :core:network:liveNavidromeTest`
Expected: PASS — the three pre-existing tests plus the nine added here.

- [ ] **Step 7: Prove each defence can fail**

Three mutations, one at a time, each restored afterwards, each failure message recorded:

1. In `ShuffleRepository.shuffle`, replace the guard with `val kept = returned`. Expect
   `aSongFromAnotherLibraryIsDroppedAndCounted` and `aSongTheMirrorHasNeverSeenIsDropped` to fail.
2. In `SubsonicClient.getRandomSongs`, drop the `musicFolderId` parameter from the map. Expect
   the live `shuffling the music library never returns the audiobook` to fail with the audiobook
   present, **and** `BrowseEndpointsTest`'s request assertion to fail.
3. Stop the container and run `./gradlew :core:network:liveNavidromeTest`. Expect a red build,
   not UP-TO-DATE and not FROM-CACHE. Also run it a second time with `--build-cache` after
   deleting the task's outputs, per the note in root `build.gradle.kts`.

- [ ] **Step 8: Measure and commit**

Re-measure `:core:database`'s floors (`ShuffleRepository` is instrumented-only) and
`:core:network`'s (unchanged in shape — `getRandomSongs` was implemented in Task 3, and its
branches are already covered by `BrowseEndpointsTest`; confirm the module still clears its
existing BRANCH floor).

```bash
git add core/model core/database core/network build.gradle.kts
git commit -m "feat(database): library-scoped shuffle with a local scope guard"
```


---

## Task 8: Setup — tagging each library Music or Audiobooks

**Files:**
- Modify: `gradle/libs.versions.toml` (add `hilt-navigation-compose`)
- Modify: `feature/setup/build.gradle.kts`
- Modify: `feature/setup/src/main/kotlin/app/muplay/setup/SetupUiState.kt`
- Modify: `feature/setup/src/main/kotlin/app/muplay/setup/SetupViewModel.kt`
- Modify: `feature/setup/src/main/kotlin/app/muplay/setup/SetupScreen.kt`
- Modify: `feature/setup/src/test/kotlin/app/muplay/setup/SetupViewModelTest.kt`
- Modify: `app/src/androidTest/kotlin/app/muplay/FirstRunJourneyTest.kt`
- Modify: `build.gradle.kts` (`:feature:setup` floors)

**Interfaces:**
- Consumes: `SubsonicSourceFactory`, `CredentialStore`, `LibraryRepository`,
  `LibraryRole`, `MusicLibrary`, `ServerInfo`, `SubsonicErrorException`, `SubsonicHttpException`
- Produces:
  - `SetupUiState` gains `Tagging(serverInfo: ServerInfo, libraries: List<MusicLibrary>, canContinue: Boolean)`
    and `Ready`, and **loses** `Success`
  - `@HiltViewModel class SetupViewModel @Inject constructor(sourceFactory, credentialStore, libraryRepository)`
    with `val uiState: StateFlow<SetupUiState>`,
    `fun connect(serverUrl: String, username: String, password: String)`,
    `fun setRole(musicFolderId: Int, role: LibraryRole)`,
    `fun continueToLibrary()`
  - `SetupScreen(onSetupComplete: () -> Unit, modifier: Modifier = Modifier, viewModel: SetupViewModel = hiltViewModel())`

### The step that makes every downstream feature work

First run is: server URL and credentials → `ping` → save credentials → `getMusicFolders` →
**the user tags each library Music or Audiobooks**. That tag is what makes scoped shuffle,
scoped browse and (Plan 4) resume behaviour work at all, so the flow **must not be skippable and
must not guess**.

**Do not infer a role from a library's name.** "Hörbücher" is not "Audiobooks", and the wrong
guess is silent — the user finds out weeks later when a novel turns up in a music shuffle. There
is no name-matching code in this task, `LibraryRepositoryTest` already asserts the repository
does not guess, and `SetupViewModelTest` asserts the ViewModel does not either.

### The Hilt migration, and its cost paid here

Task 1 ruled that Hilt earns its place. This is where `SetupViewModel` stops being the
odd one out: its defaulted-lambda constructor seam with `@JvmOverloads` is replaced by ordinary
constructor injection, and `SetupScreen` uses `hiltViewModel()` instead of the bare `viewModel()`
factory. Constructor injection is strictly better here — the collaborators are real interfaces a
test can implement by hand — and it is what lets the ViewModel reach `LibraryRepository` at all.

**Two coverage floors die with that seam and must be removed, not left to rot.**
`build.gradle.kts` currently floors `app.muplay.setup.SetupViewModel*1` and
`app.muplay.setup.SetupViewModel*2` at `0.55`; those patterns match the compiled default-lambda
classes, which no longer exist after this task. A `"CLASS"`-element rule whose includes match
nothing yields `0/0`, JaCoCo returns `NaN`, and `Limit.check` treats `NaN` as **no violation** —
a floor that passes at every minimum and gates nothing. That is the seventh silent-gate defect
this project found, and `UngatedClassChecker.warnVacuousFloors` exists to shout about it. Delete
both entries in this task and re-measure what replaces them.

- [ ] **Step 1: Write the failing ViewModel test**

`feature/setup/src/test/kotlin/app/muplay/setup/SetupViewModelTest.kt` — replace the existing
file. It is a JVM test, and the collaborators are hand-written fakes for the two things that
genuinely cannot run here: an Android `CredentialStore` (Keystore) and a server.

```kotlin
package app.muplay.setup

import app.cash.turbine.test
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicErrorException
import app.muplay.network.SubsonicHttpException
import app.muplay.network.SubsonicSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

  /** A minimal hand-written source: nothing but the two commands setup makes. */
  private class StubSource(
    private val pingResult: () -> ServerInfo,
    private val folders: List<MusicLibrary> = emptyList(),
  ) : SubsonicSource {
    override suspend fun ping(): ServerInfo = pingResult()
    override suspend fun getMusicFolders(): List<MusicLibrary> = folders
    override suspend fun getScanStatus(): ScanStatus = error("not used by setup")
    override suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album> =
      error("not used by setup")
    override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs =
      error("not used by setup")
    override suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults =
      error("not used by setup")
    override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> =
      error("not used by setup")
    override fun coverArtUrl(coverArtId: String, sizePx: Int?): String = error("not used by setup")
  }

  /** Records what setup stored, in place of the real Keystore-backed store. */
  private class RecordingCredentials : SetupCredentialSink {
    var saved: SubsonicCredentials? = null
    override suspend fun save(credentials: SubsonicCredentials) { saved = credentials }
  }

  /** The library half of the flow, in memory. */
  private class FakeLibraries : SetupLibrarySink {
    var refreshed = 0
    val roles = mutableMapOf<Int, LibraryRole>()
    var reported: List<MusicLibrary> = emptyList()

    override suspend fun refreshFromServer() { refreshed++ }
    override suspend fun setRole(musicFolderId: Int, role: LibraryRole) { roles[musicFolderId] = role }
    override suspend fun current(): List<MusicLibrary> =
      reported.map { it.copy(role = roles[it.id] ?: LibraryRole.UNASSIGNED) }
  }

  private val dispatcher = StandardTestDispatcher()
  private val credentials = RecordingCredentials()
  private val libraries = FakeLibraries()

  @BeforeEach
  fun setUp() = Dispatchers.setMain(dispatcher)

  @AfterEach
  fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(source: SubsonicSource) =
    SetupViewModel({ source }, credentials, libraries)

  private fun serverInfo() = ServerInfo("navidrome", "0.63.2", "1.16.1", isOpenSubsonic = true)

  @Test
  fun `a blank url is rejected before any network call`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ error("must not be called") }))

    vm.connect("   ", "admin", "testpass")

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.InvalidUrl))
    assertThat(credentials.saved).isNull()
  }

  @Test
  fun `a successful connect saves the credentials and lists the libraries for tagging`() =
    runTest(dispatcher) {
      libraries.reported = listOf(
        MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
        MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
      )
      val vm = viewModel(StubSource({ serverInfo() }))

      vm.uiState.test {
        assertThat(awaitItem()).isEqualTo(SetupUiState.Idle)
        vm.connect("http://localhost:4533", "admin", "testpass")
        assertThat(awaitItem()).isEqualTo(SetupUiState.Connecting)

        val tagging = awaitItem() as SetupUiState.Tagging
        assertThat(tagging.serverInfo.type).isEqualTo("navidrome")
        assertThat(tagging.libraries.map { it.name }).containsExactly("Music", "Audiobooks")
        // Every library arrives untagged, and the flow cannot be finished until they are not.
        assertThat(tagging.libraries).allMatch { it.role == LibraryRole.UNASSIGNED }
        assertThat(tagging.canContinue).isFalse
        cancelAndIgnoreRemainingEvents()
      }

      assertThat(credentials.saved)
        .isEqualTo(SubsonicCredentials("http://localhost:4533", "admin", "testpass"))
      assertThat(libraries.refreshed).isEqualTo(1)
    }

  @Test
  fun `credentials are stored before the libraries are fetched`() = runTest(dispatcher) {
    // Not an ordering nicety: `LibraryRepository.refreshFromServer` reads the credential store,
    // so fetching first would throw NotConfiguredException on every first run.
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(credentials.saved).isNotNull
    assertThat(libraries.refreshed).isEqualTo(1)
  }

  @Test
  fun `tagging every library is what unlocks continuing`() = runTest(dispatcher) {
    libraries.reported = listOf(
      MusicLibrary(1, "Music", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Audiobooks", LibraryRole.UNASSIGNED),
    )
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    vm.setRole(1, LibraryRole.MUSIC)
    dispatcher.scheduler.advanceUntilIdle()
    assertThat((vm.uiState.value as SetupUiState.Tagging).canContinue).isFalse

    vm.setRole(2, LibraryRole.AUDIOBOOKS)
    dispatcher.scheduler.advanceUntilIdle()
    val tagged = vm.uiState.value as SetupUiState.Tagging
    assertThat(tagged.canContinue).isTrue
    assertThat(tagged.libraries.single { it.id == 2 }.role).isEqualTo(LibraryRole.AUDIOBOOKS)
  }

  @Test
  fun `continuing before everything is tagged does nothing`() = runTest(dispatcher) {
    // An untagged library is invisible to every browse and shuffle path, so letting the user past
    // this screen would hand them an app that silently shows nothing.
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    vm.continueToLibrary()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isInstanceOf(SetupUiState.Tagging::class.java)
  }

  @Test
  fun `continuing once everything is tagged reaches Ready`() = runTest(dispatcher) {
    libraries.reported = listOf(MusicLibrary(1, "Music", LibraryRole.UNASSIGNED))
    val vm = viewModel(StubSource({ serverInfo() }))
    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()
    vm.setRole(1, LibraryRole.MUSIC)
    dispatcher.scheduler.advanceUntilIdle()

    vm.continueToLibrary()
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value).isEqualTo(SetupUiState.Ready)
  }

  @Test
  fun `the view model never guesses a role from a library name`() = runTest(dispatcher) {
    libraries.reported = listOf(
      MusicLibrary(1, "Audiobooks", LibraryRole.UNASSIGNED),
      MusicLibrary(2, "Hörbücher", LibraryRole.UNASSIGNED),
      MusicLibrary(3, "Music", LibraryRole.UNASSIGNED),
    )
    val vm = viewModel(StubSource({ serverInfo() }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    val tagging = vm.uiState.value as SetupUiState.Tagging
    assertThat(tagging.libraries).allMatch { it.role == LibraryRole.UNASSIGNED }
    assertThat(tagging.canContinue).isFalse
    assertThat(libraries.roles).isEmpty()
  }

  @Test
  fun `a rejected sign-in reports the server's own code and stores nothing`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw SubsonicErrorException(40, "Wrong username or password") }))

    vm.connect("http://localhost:4533", "admin", "wrong")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.Rejected(40, "Wrong username or password")))
    assertThat(credentials.saved).isNull()
  }

  @Test
  fun `a bad http status is a rejection, not an unreachable server`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw SubsonicHttpException(502) }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    val failure = vm.uiState.value as SetupUiState.Failure
    assertThat(failure.reason).isInstanceOf(SetupFailureReason.Rejected::class.java)
    assertThat((failure.reason as SetupFailureReason.Rejected).code).isEqualTo(502)
  }

  @Test
  fun `a transport failure is unreachable`() = runTest(dispatcher) {
    val vm = viewModel(StubSource({ throw IOException("connection refused") }))

    vm.connect("http://localhost:4533", "admin", "testpass")
    dispatcher.scheduler.advanceUntilIdle()

    assertThat(vm.uiState.value)
      .isEqualTo(SetupUiState.Failure(SetupFailureReason.Unreachable))
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :feature:setup:test`
Expected: FAIL — `Unresolved reference: SetupCredentialSink`, `SetupLibrarySink`,
`SetupUiState.Tagging`.

- [ ] **Step 3: Add the dependency and the module wiring**

`gradle/libs.versions.toml`:

```toml
hiltNavigationCompose = "1.4.0"
```

```toml
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
```

`feature/setup/build.gradle.kts` — add the Hilt convention plugin and the two dependencies:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}
```

```kotlin
  implementation(project(":core:database"))
  implementation(libs.hilt.navigation.compose)
```

The `implementation(libs.okhttp)` entry stays: `connect` still validates the URL with
`toHttpUrlOrNull` before any network call.

- [ ] **Step 4: Write the state and the two sinks**

`feature/setup/src/main/kotlin/app/muplay/setup/SetupUiState.kt` — replace `Success` with
`Tagging` and add `Ready`:

```kotlin
package app.muplay.setup

import app.muplay.model.MusicLibrary
import app.muplay.model.ServerInfo

/**
 * The first-run flow's state, exposed by [SetupViewModel] as a `StateFlow` and collected with
 * `collectAsStateWithLifecycle()`. A sealed interface so a `when` over it is exhaustive at every
 * call site — the compiler, not convention, is what keeps a new state from being missed.
 */
sealed interface SetupUiState {

  /** No connection attempt has been made yet. */
  data object Idle : SetupUiState

  /** A `ping` request is in flight. */
  data object Connecting : SetupUiState

  /**
   * Connected, credentials stored, and the server's libraries listed **for the user to tag**.
   *
   * This state replaced the old `Success`, and the rename is the point: connecting is not the end
   * of setup. A Navidrome server cannot say what a library holds — it reports
   * `child.Type = "music"` for audiobooks too — so the Music/Audiobooks decision is made here, by
   * the user, once, and everything downstream keys off it.
   *
   * [canContinue] is false while any library is still [app.muplay.model.LibraryRole.UNASSIGNED]:
   * an untagged library is invisible to every browse and shuffle path, so letting the user past
   * this screen would hand them an app that silently shows nothing.
   */
  data class Tagging(
    val serverInfo: ServerInfo,
    val libraries: List<MusicLibrary>,
    val canContinue: Boolean,
  ) : SetupUiState

  /** Setup is complete; the host navigates away. */
  data object Ready : SetupUiState

  /** The attempt failed; [reason] is typed, not a bare message — see [SetupFailureReason]. */
  data class Failure(val reason: SetupFailureReason) : SetupUiState
}

/**
 * The slice of credential storage setup needs. A one-method interface rather than a dependency on
 * `CredentialStore` itself, so this module's JVM tests can run: the real store talks to the
 * Android Keystore, which does not exist on a JVM, and which `:core:database`'s own instrumented
 * `CredentialStoreTest` already proves on a device.
 */
interface SetupCredentialSink {
  suspend fun save(credentials: app.muplay.model.SubsonicCredentials)
}

/**
 * The slice of the library repository setup needs. Same reasoning as [SetupCredentialSink]: the
 * real one is backed by Room, which needs a device.
 */
interface SetupLibrarySink {
  suspend fun refreshFromServer()
  suspend fun setRole(musicFolderId: Int, role: app.muplay.model.LibraryRole)
  suspend fun current(): List<MusicLibrary>
}
```

- [ ] **Step 5: Write the ViewModel**

`feature/setup/src/main/kotlin/app/muplay/setup/SetupViewModel.kt`:

```kotlin
package app.muplay.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Drives first run: connect, store the credentials, then have the user tag every library.
 *
 * Constructor-injected, replacing the defaulted-lambda seam this class used to carry. That seam
 * existed because there was no DI graph to inject from; there is one now (Task 1's ruling), and
 * three real interfaces are both easier to fake and honest about what this class needs.
 *
 * [createSource] is a lambda rather than the `SubsonicSourceFactory` type directly so a test can
 * supply one without constructing credentials-shaped machinery; Hilt binds it from the real
 * factory in the constructor below.
 */
@HiltViewModel
class SetupViewModel(
  private val createSource: (SubsonicCredentials) -> SubsonicSource,
  private val credentials: SetupCredentialSink,
  private val libraries: SetupLibrarySink,
) : ViewModel() {

  @Inject
  constructor(
    sourceFactory: SubsonicSourceFactory,
    credentialStore: CredentialStore,
    libraryRepository: LibraryRepository,
  ) : this(
    createSource = { sourceFactory.create(it) },
    credentials = object : SetupCredentialSink {
      override suspend fun save(credentials: SubsonicCredentials) = credentialStore.save(credentials)
    },
    libraries = object : SetupLibrarySink {
      override suspend fun refreshFromServer() = libraryRepository.refreshFromServer()
      override suspend fun setRole(musicFolderId: Int, role: LibraryRole) =
        libraryRepository.setRole(musicFolderId, role)
      override suspend fun current(): List<MusicLibrary> = libraryRepository.libraries.first()
    },
  )

  private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Idle)
  val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

  private var serverInfo: app.muplay.model.ServerInfo? = null

  /**
   * Validates [serverUrl], connects, stores the credentials and lists the libraries for tagging.
   *
   * The order is load-bearing: the credentials are stored **before** the libraries are fetched,
   * because `LibraryRepository.refreshFromServer` reads them back out of the store — fetching
   * first would fail with `NotConfiguredException` on every first run.
   *
   * A [CancellationException] is rethrown rather than reported: cancelling the coroutine is not
   * the server saying anything, and must not flash an "unreachable" message nobody asked for.
   */
  fun connect(serverUrl: String, username: String, password: String) {
    val trimmedUrl = serverUrl.trim()
    if (trimmedUrl.toHttpUrlOrNull() == null) {
      _uiState.value = SetupUiState.Failure(SetupFailureReason.InvalidUrl)
      return
    }

    _uiState.value = SetupUiState.Connecting
    viewModelScope.launch {
      val entered = SubsonicCredentials(trimmedUrl, username, password)
      _uiState.value = try {
        val info = createSource(entered).ping()
        serverInfo = info
        credentials.save(entered)
        libraries.refreshFromServer()
        tagging(info)
      } catch (e: app.muplay.network.SubsonicErrorException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.code, detail = e.message))
      } catch (e: app.muplay.network.SubsonicHttpException) {
        SetupUiState.Failure(SetupFailureReason.Rejected(code = e.status, detail = e.message))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        SetupUiState.Failure(SetupFailureReason.Unreachable)
      }
    }
  }

  /**
   * Records the user's decision for one library. **Nothing here looks at the library's name** —
   * a name heuristic would be silently wrong for any non-English library, and its only symptom
   * would be audiobooks appearing in a music shuffle.
   */
  fun setRole(musicFolderId: Int, role: LibraryRole) {
    viewModelScope.launch {
      libraries.setRole(musicFolderId, role)
      serverInfo?.let { _uiState.value = tagging(it) }
    }
  }

  /** Leaves setup, but only once every library has a role. */
  fun continueToLibrary() {
    viewModelScope.launch {
      val current = libraries.current()
      if (current.none { it.role == LibraryRole.UNASSIGNED }) {
        _uiState.value = SetupUiState.Ready
      }
    }
  }

  private suspend fun tagging(info: app.muplay.model.ServerInfo): SetupUiState.Tagging {
    val current = libraries.current()
    return SetupUiState.Tagging(
      serverInfo = info,
      libraries = current,
      canContinue = current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED },
    )
  }
}
```

Note the `@HiltViewModel` on the class with `@Inject` on a **secondary** constructor: Hilt
requires exactly one `@Inject` constructor and does not care that it is secondary. The primary
constructor stays the test seam. If the Hilt processor rejects this arrangement, invert it —
make the injected constructor primary and add an `internal` secondary for tests — and record the
substitution; do **not** reintroduce defaulted lambdas, which is the pattern this task removes.

- [ ] **Step 6: Update the screen**

`feature/setup/src/main/kotlin/app/muplay/setup/SetupScreen.kt` — the form is unchanged
(including the deliberate `remember`-not-`rememberSaveable` for the password, which must stay:
`rememberSaveable` writes to the saved-instance-state Bundle, which survives process death, and
a plaintext password must not). Replace the `Success` branch of the `when` and add the callback:

```kotlin
@Composable
fun SetupScreen(
  onSetupComplete: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SetupViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  LaunchedEffect(uiState) {
    if (uiState is SetupUiState.Ready) onSetupComplete()
  }
  SetupScreen(
    uiState = uiState,
    onConnect = viewModel::connect,
    onRoleChosen = viewModel::setRole,
    onContinue = viewModel::continueToLibrary,
    modifier = modifier,
  )
}
```

and, inside the private overload's `when (uiState)`:

```kotlin
      is SetupUiState.Tagging -> {
        Text(
          text = "Connected to ${uiState.serverInfo.type} ${uiState.serverInfo.serverVersion}",
          color = MaterialTheme.colorScheme.primary,
        )
        // The server cannot tell us what a library holds -- Navidrome reports every file as
        // `type: "music"` -- so the user decides, once, here. No name is inspected: "Hörbücher"
        // is not "Audiobooks", and a wrong guess silently poisons shuffle scope.
        Text(text = "What is each library for?", style = MaterialTheme.typography.titleMedium)
        uiState.libraries.forEach { library ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(text = library.name, modifier = Modifier.weight(1f))
            FilterChip(
              selected = library.role == LibraryRole.MUSIC,
              onClick = { onRoleChosen(library.id, LibraryRole.MUSIC) },
              label = { Text("Music") },
            )
            FilterChip(
              selected = library.role == LibraryRole.AUDIOBOOKS,
              onClick = { onRoleChosen(library.id, LibraryRole.AUDIOBOOKS) },
              label = { Text("Audiobooks") },
            )
          }
        }
        Button(
          onClick = onContinue,
          enabled = uiState.canContinue,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Continue")
        }
      }
      is SetupUiState.Ready -> Text(text = "Setup complete")
```

with the private overload's signature widened to
`onRoleChosen: (Int, LibraryRole) -> Unit, onContinue: () -> Unit` and imports added for
`androidx.compose.foundation.layout.Row`, `androidx.compose.ui.Alignment`,
`androidx.compose.material3.FilterChip`, `androidx.compose.runtime.LaunchedEffect`,
`androidx.hilt.navigation.compose.hiltViewModel`, and `app.muplay.model.LibraryRole`.

Every label above is asserted by name in `FirstRunJourneyTest`; changing one means changing that
test, deliberately, not incidentally.

- [ ] **Step 7: Update the emulator journey**

`app/src/androidTest/kotlin/app/muplay/FirstRunJourneyTest.kt` — the existing success journey
still asserts `"Connected to navidrome"` and both library names, all of which the `Tagging` state
renders. Extend it so the journey covers what setup now actually is:

```kotlin
  @Test
  fun firstRunConnectsToNavidromeAndListsBothSeededLibraries() {
    connectAs(PASSWORD)

    composeRule.onNodeWithText("Connected to navidrome", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Music").assertIsDisplayed()
    composeRule.onNodeWithText("Audiobooks").assertIsDisplayed()
  }

  @Test
  fun theFlowCannotBeFinishedUntilEveryLibraryIsTagged() {
    connectAs(PASSWORD)

    // Both libraries untagged: Continue is inert. This is the assertion that keeps the tagging
    // step from becoming skippable, and an untagged library is invisible to browse and shuffle.
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText("Music")[MUSIC_ROLE_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsNotEnabled()

    composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOK_ROLE_CHIP].performClick()
    composeRule.onNodeWithText(CONTINUE_LABEL).assertIsEnabled()
  }
```

with `assertIsEnabled`/`assertIsNotEnabled` imported from `androidx.compose.ui.test`, and, in the
companion:

```kotlin
    const val CONTINUE_LABEL = "Continue"

    /**
     * The library named "Music" renders its own name and a "Music" role chip, so
     * `onAllNodesWithText("Music")` matches two nodes on this screen. Index 0 is the library
     * name in the first row; index 1 is that row's "Music" chip. Same for "Audiobooks", whose
     * row is second: index 0 is the "Audiobooks" chip in row one, index 1 the library name in
     * row two, index 2 that row's chip.
     *
     * Indices rather than test tags, deliberately: this journey is a black-box walk through what
     * a user sees, and adding tags to the production UI purely so a test can find things makes
     * the test pass on a screen the user could not use. If these indices become fragile, add
     * distinct visible labels ("Tag as Music") rather than invisible ones.
     */
    const val MUSIC_ROLE_CHIP = 1
    const val AUDIOBOOK_ROLE_CHIP = 2
```

**Verify the indices by running the test, not by reasoning about them.** If they are wrong, the
failure names the node count; adjust, and update the comment to match what was observed.

- [ ] **Step 8: Fix the coverage floors**

`build.gradle.kts` — in the `":feature:setup"` list:

1. **Delete** the `CoverageFloor` whose `includes` are `"app.muplay.setup.SetupViewModel*1"` and
   `"app.muplay.setup.SetupViewModel*2"`. Those classes no longer exist. Leaving them would be a
   `0/0` → `NaN` → "no violation" rule: a floor that can never fail.
2. Re-measure the rest. `SetupViewModel` gains real branches (the tagging predicate, the
   continue guard, the widened catch cascade); `SetupScreenKt` gains the whole `Tagging` branch,
   so its LINE ratio must be re-read from the report after the emulator journey runs, not
   guessed.
3. Add a floor for `SetupUiState`'s new members and the two sink interfaces if
   `warnUngatedClasses` reports them.

Run `./gradlew :feature:setup:jacocoTestReport` after both `:feature:setup:test` and
`:app:connectedDebugAndroidTest`, read the XML, and write the measured numbers.

- [ ] **Step 9: Run everything and commit**

Run: `./gradlew :feature:setup:test` — 10/10.
Run: `./gradlew :app:connectedDebugAndroidTest` — `FirstRunJourneyTest` 3/3.
Run: `./gradlew jacocoJvmCoverageVerification` — green, with **no** `COVERAGE:` warning about a
vacuous floor or an ungated class in `:feature:setup`. A warning here is the whole point of this
step; do not proceed past it.

```bash
git add gradle/libs.versions.toml feature/setup app/src/androidTest build.gradle.kts
git commit -m "feat(setup): tag each library Music or Audiobooks, on Hilt"
```


---

## Task 9: `:feature:library` — browse, search and shuffle UI

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `feature/library/build.gradle.kts`
- Create: `feature/library/src/main/kotlin/app/muplay/library/LibraryUiState.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/LibraryViewModel.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/AlbumUiState.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/AlbumViewModel.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/AlbumScreen.kt`
- Create: `feature/library/src/main/kotlin/app/muplay/library/CoverArt.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/LibraryRoute.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/AlbumRoute.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Modify: `app/src/main/kotlin/app/muplay/MuPlayApplication.kt`
- Test: `feature/library/src/test/kotlin/app/muplay/library/LibraryUiStateTest.kt`
- Test: `feature/library/src/test/kotlin/app/muplay/library/CoverArtTest.kt`

**Interfaces:**
- Consumes: `LibraryRepository.libraries`, `BrowseRepository.albums/songs/album/search/coverArtUrl`,
  `ShuffleRepository.shuffle` + `DEFAULT_SHUFFLE_SIZE`, `SyncEngine.syncIfStale`, `SyncState`,
  `Album`, `Song`, `MusicLibrary`, `LibraryRole`, `ShuffleResult`
- Produces:
  - sealed `LibraryUiState` with `Loading`, `NoLibraries`,
    `Content(libraries: List<MusicLibrary>, selectedLibraryId: Int, query: String, albums: List<Album>, shuffled: List<Song>, discardedOutOfScope: Int, syncMessage: String?)`
  - `internal fun libraryContent(libraries, selectedLibraryId, query, albums, searchAlbums, shuffle, syncMessage): LibraryUiState`
  - `@HiltViewModel class LibraryViewModel` with `uiState: StateFlow<LibraryUiState>`,
    `fun selectLibrary(id: Int)`, `fun search(query: String)`, `fun shuffle()`, `fun refresh()`
  - `LibraryScreen(onAlbumClick: (String) -> Unit, modifier: Modifier = Modifier, viewModel: LibraryViewModel = hiltViewModel())`
  - sealed `AlbumUiState` with `Loading`, `NotFound`, `Content(album: Album, songs: List<Song>)`
  - `@HiltViewModel class AlbumViewModel` with `uiState: StateFlow<AlbumUiState>`
  - `AlbumScreen(modifier: Modifier = Modifier, viewModel: AlbumViewModel = hiltViewModel())` —
    the album id comes from `AlbumViewModel`'s `SavedStateHandle`, not from a parameter
  - `internal fun coverArtCacheKey(coverArtId: String, sizePx: Int?): String`
  - `@Composable fun CoverArtImage(coverArtId: String?, sizePx: Int, contentDescription: String?, modifier: Modifier = Modifier)`
  - `SetupRoute`, `LibraryRoute`, `AlbumRoute(albumId: String)` navigation keys

### Where the logic lives, and why the ViewModels are thin

`:feature:setup` already showed the shape this project's floors reward: branching that a JVM test
can reach goes in a plain function, and the Composable keeps only the branching a real
composition has to exercise. So `LibraryUiState.kt` carries a **pure** `libraryContent(...)`
builder with every rule in it — which library is selected, whether a search is active, what
counts as empty — and `LibraryViewModel` does nothing but combine flows and call it.

That is also why this module ships **no** instrumented tests of its own. Its ViewModel and
Composables are exercised by Tier 2's `BrowseJourneyTest` and `ScopedShuffleJourneyTest`
(Task 10), running the real app against the real Navidrome, which is a stronger rung than a
fake-backed test here would be — and it avoids duplicating `FakeSubsonicSource` across module
boundaries, which this build's coverage wiring makes awkward (a JVM module's JaCoCo tasks read
only their own `.exec`, never the instrumented `.ec`, so a shared fake in `:core:testing` could
never be measured as covered).

### Why there is a Refresh action, and why it is a button

`SyncEngine.syncIfStale()` has exactly **one** caller — `LibraryViewModel.init`. There is no
periodic poll, no pull-to-refresh, and nothing that re-checks. Add an album to Navidrome mid-session
and it never appears. Worse, the `ScanInProgress` branch used to render *"The server is scanning;
your library will update shortly"*: **a promise no code kept.** A user whose first launch happens to
land during a server scan sees a partial library, is told it will fix itself, and it never does —
until they force-stop the app. That is the worst kind of defect this project recognises, because the
user has been given a reason not to investigate.

Two things fix it, and both are in this task. The copy now describes the situation and **names the
control that resolves it**, and the control exists.

**It is an explicit button, not a pull-to-refresh gesture, for three stated reasons.**

1. **A gesture is invisible.** The user this fixes is precisely the one who does not know the app —
   they opened it during a scan on day one. Discoverability is the whole requirement, and an
   affordance you have to already know about does not have it.
2. **This screen's scroll container is not the screen.** `LibraryScreen` is a `Column` holding a
   chip row, a search field, the actions and *then* a `LazyColumn`. `PullToRefreshBox` wants to own
   the scrollable, so the gesture would either restructure the screen or attach to the album list
   only — a pull that works in one region and silently does nothing eight dp higher.
3. **A label is assertable.** Tier 2 asserts it by name with no gesture timing, and this project's
   journeys are black-box walks through visible strings for exactly that reason.

**And there is deliberately no background poller.** Spec §4's *"poll"* is about `getScanStatus`'s
watermark being the only delta primitive Subsonic offers — it is a statement about *what to ask*,
not about asking on a timer. A periodic wake-up that asks a question nobody is waiting for costs
battery on a user who is not looking at the screen, and the spec does not ask for one. The user
asks; the app answers. Should a poller ever be wanted, `refresh()` is already the single entry point
it would call.

### The cover-art cache key, and the defect it prevents

`SubsonicSource.coverArtUrl` puts a **fresh salt** in every URL — token auth requires it, and
`BrowseEndpointsTest` asserts two calls for the same art produce different strings. Coil keys
its memory and disk caches on the request URL by default, so left alone **every cover art would
miss the cache, every time**, and re-download on every scroll.

This is the same defect Tempo shipped on the playback side, where the auth token and bitrate
were part of the Media3 cache key so changing bitrate orphaned the whole cache — which is why
the global constraints say cache keys derive from the item id alone. The same principle applies
here: the key is derived from the cover-art id and the requested size, and from nothing else.

Spec §4 adds that the cover-art key should be "bumped when the album's `changed` timestamp
moves". Nothing extra is needed for that against Navidrome, and the reason is worth writing down
rather than rediscovering: **Navidrome's `coverArt` id already carries a content hash** — the
live capture shows `"coverArt": "al-7orvCZZyWRqsduCdqXoguY_6a8bbb51"` for the album and
`"al-7orvCZZyWRqsduCdqXoguY_0"` for its tracks, where the suffix moves when the art does. Keying
on the id therefore *is* keying on the version. A server whose `coverArt` id were stable across
an art change would need the album's `changed` timestamp folded into the key; Navidrome is not
that server, and this note is here so the next reader can check rather than assume.

- [ ] **Step 1: Write the failing pure tests**

`feature/library/src/test/kotlin/app/muplay/library/CoverArtTest.kt`:

```kotlin
package app.muplay.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoverArtTest {

  @Test
  fun `the key is derived from the art id and the size`() {
    assertThat(coverArtCacheKey("al-abc_0", 256)).isEqualTo("al-abc_0@256")
    assertThat(coverArtCacheKey("al-abc_0", null)).isEqualTo("al-abc_0@full")
  }

  @Test
  fun `two different sizes of the same art are different cache entries`() {
    // Coil stores a decoded bitmap per key; sharing one key across sizes would serve a 64px
    // thumbnail into a full-width slot.
    assertThat(coverArtCacheKey("al-abc_0", 64)).isNotEqualTo(coverArtCacheKey("al-abc_0", 512))
  }

  @Test
  fun `the key contains nothing from the request url`() {
    // The whole point. An authenticated cover-art URL carries `u`, `t` and a fresh `s` per
    // request, so a URL-derived key can never hit the cache and every scroll re-downloads every
    // cover. Asserting the absence of those parameter names is what stops someone "simplifying"
    // this to `url` later.
    val key = coverArtCacheKey("al-abc_0", 256)

    assertThat(key).doesNotContain("t=")
    assertThat(key).doesNotContain("s=")
    assertThat(key).doesNotContain("u=")
    assertThat(key).doesNotContain("http")
  }

  @Test
  fun `the same art at the same size always produces the same key`() {
    repeat(8) { assertThat(coverArtCacheKey("al-abc_0", 256)).isEqualTo("al-abc_0@256") }
  }
}
```

`feature/library/src/test/kotlin/app/muplay/library/LibraryUiStateTest.kt`:

```kotlin
package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.ShuffleResult
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LibraryUiStateTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Audiobooks", LibraryRole.AUDIOBOOKS)

  private fun album(id: String, name: String, libraryId: Int) =
    Album(id, libraryId, name, "artist-1", "Test Artist", "al-$id", 3, 15)

  private fun song(id: String, title: String, libraryId: Int) =
    Song(id, libraryId, title, "album-1", "Test Album", "artist-1", "Test Artist", 1, null, 5, "mp3", null)

  @Test
  fun `no libraries at all is its own state, not an empty content screen`() {
    // "You have not finished setup" and "this library is empty" are different problems with
    // different fixes, and a screen that renders them identically strands the user.
    assertThat(
      libraryContent(
        libraries = emptyList(),
        selectedLibraryId = null,
        query = "",
        albums = emptyList(),
        searchAlbums = emptyList(),
        shuffle = null,
        syncMessage = null,
      ),
    ).isEqualTo(LibraryUiState.NoLibraries)
  }

  @Test
  fun `the first library is selected when nothing has been chosen`() {
    val state = libraryContent(
      libraries = listOf(music, books),
      selectedLibraryId = null,
      query = "",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.selectedLibraryId).isEqualTo(1)
    assertThat(state.albums.map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun `a selection that no longer exists falls back to the first library`() {
    // A library removed on the server between one sync and the next would otherwise leave the
    // screen pointed at an id nothing matches, showing an empty list forever.
    val state = libraryContent(
      libraries = listOf(music, books),
      selectedLibraryId = 99,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.selectedLibraryId).isEqualTo(1)
  }

  @Test
  fun `a non-blank query shows the search results instead of the full album list`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "book",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = listOf(album("a2", "Booked", 1)),
      shuffle = null,
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.albums.map { it.name }).containsExactly("Booked")
    assertThat(state.query).isEqualTo("book")
  }

  @Test
  fun `a whitespace-only query is not a search`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "   ",
      albums = listOf(album("a1", "Test Album", 1)),
      searchAlbums = emptyList(),
      shuffle = null,
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.albums.map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun `a shuffle result is carried through with its out-of-scope count`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = ShuffleResult(listOf(song("s1", "Track 1", 1)), discardedOutOfScope = 2),
      syncMessage = null,
    ) as LibraryUiState.Content

    assertThat(state.shuffled.map { it.title }).containsExactly("Track 1")
    // Surfaced rather than swallowed: a non-zero count means either a stale mirror or a server
    // whose scoping did not hold, and both are worth a user-visible line.
    assertThat(state.discardedOutOfScope).isEqualTo(2)
  }

  @Test
  fun `a sync message is passed through untouched`() {
    val state = libraryContent(
      libraries = listOf(music),
      selectedLibraryId = 1,
      query = "",
      albums = emptyList(),
      searchAlbums = emptyList(),
      shuffle = null,
      syncMessage = "Could not reach the server",
    ) as LibraryUiState.Content

    assertThat(state.syncMessage).isEqualTo("Could not reach the server")
  }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :feature:library:test`
Expected: FAIL — `Project 'library' not found in project ':feature'`.

- [ ] **Step 3: Register the module and its dependencies**

`settings.gradle.kts` — add `include(":feature:library")`.

`gradle/libs.versions.toml` — `coil` is **already present** at `3.5.0`. Add under `[libraries]`:

```toml
coil-compose         = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp  = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
```

Note the group: Coil 3 publishes under `io.coil-kt.coil3`, not `io.coil-kt`.

`feature/library/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.library"
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:database"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.coil.compose)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
```

`app/build.gradle.kts` — add `implementation(project(":feature:library"))` and
`implementation(libs.coil.network.okhttp)` (the app is where the image loader is built).

- [ ] **Step 4: Write the pure state builder**

`feature/library/src/main/kotlin/app/muplay/library/LibraryUiState.kt`:

```kotlin
package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.MusicLibrary
import app.muplay.model.ShuffleResult
import app.muplay.model.Song

/**
 * What the browse screen shows. A sealed interface so the screen's `when` is exhaustive.
 */
sealed interface LibraryUiState {

  /** The mirror has not been read yet. */
  data object Loading : LibraryUiState

  /**
   * There are no libraries at all — setup has not been completed, or the server reports none.
   * A distinct state from an empty [Content], because "finish setup" and "this library is empty"
   * are different problems with different fixes.
   */
  data object NoLibraries : LibraryUiState

  /**
   * @property selectedLibraryId always names a library present in [libraries] — see
   *   [libraryContent], which repairs a stale selection rather than rendering an empty screen.
   * @property albums either the whole selected library or the search results, depending on
   *   [query]. One list, so the screen has no branch of its own to get wrong.
   * @property discardedOutOfScope how many songs the last shuffle dropped because the mirror did
   *   not place them in the selected library. Normally zero.
   * @property syncMessage what the last [LibraryViewModel.refresh] found, or `null` when there is
   *   nothing to say. **Every value of it must be true at the moment it is shown**, which is not a
   *   platitude: this string used to read *"your library will update shortly"* while no code
   *   anywhere re-checked, so a user who first opened the app during a server scan was stranded
   *   with a partial library until they force-stopped it. Nothing here may promise a future the
   *   app does not bring about; where an outcome depends on the user, the message names the
   *   control that produces it.
   */
  data class Content(
    val libraries: List<MusicLibrary>,
    val selectedLibraryId: Int,
    val query: String,
    val albums: List<Album>,
    val shuffled: List<Song>,
    val discardedOutOfScope: Int,
    val syncMessage: String?,
  ) : LibraryUiState
}

/**
 * Every rule the browse screen follows, as one pure function.
 *
 * Deliberately not a method on the ViewModel: this is where the branching lives, and a plain
 * function is testable on the JVM in Tier 1, where a ViewModel wired to Room and a server would
 * not be. The ViewModel's own job is reduced to combining flows and calling this.
 */
internal fun libraryContent(
  libraries: List<MusicLibrary>,
  selectedLibraryId: Int?,
  query: String,
  albums: List<Album>,
  searchAlbums: List<Album>,
  shuffle: ShuffleResult?,
  syncMessage: String?,
): LibraryUiState {
  if (libraries.isEmpty()) return LibraryUiState.NoLibraries

  // A selection can go stale between syncs -- a library removed on the server leaves the screen
  // pointed at an id nothing matches, which would render as a permanently empty list.
  val selected = libraries.firstOrNull { it.id == selectedLibraryId }?.id ?: libraries.first().id
  val searching = query.isNotBlank()

  return LibraryUiState.Content(
    libraries = libraries,
    selectedLibraryId = selected,
    query = query,
    albums = if (searching) searchAlbums else albums,
    shuffled = shuffle?.songs.orEmpty(),
    discardedOutOfScope = shuffle?.discardedOutOfScope ?: 0,
    syncMessage = syncMessage,
  )
}
```

- [ ] **Step 5: Write the cover-art helper**

`feature/library/src/main/kotlin/app/muplay/library/CoverArt.kt`:

```kotlin
package app.muplay.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * The Coil cache key for one piece of cover art.
 *
 * **Derived from the art id and the requested size, and from nothing else** — in particular not
 * from the request URL. An authenticated Subsonic cover-art URL carries `u`, `t` and a **fresh
 * salt** per request, so Coil's default URL-derived key would miss the memory and disk caches on
 * every single load and re-download every cover on every scroll.
 *
 * This is the same defect Tempo shipped on the playback side (its Media3 cache key included the
 * auth token and the bitrate, so changing bitrate orphaned the entire cache), and the same rule
 * this project's global constraints state for Media3: the key comes from the item id.
 */
internal fun coverArtCacheKey(coverArtId: String, sizePx: Int?): String =
  "$coverArtId@${sizePx?.toString() ?: "full"}"

/**
 * One cover image, or a neutral placeholder when the server gave the item no `coverArt` id.
 *
 * [urlProvider] is a suspending lookup rather than a plain string because building the URL needs
 * the stored credentials, which are read asynchronously.
 */
@Composable
fun CoverArtImage(
  coverArtId: String?,
  sizePx: Int,
  contentDescription: String?,
  urlProvider: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  if (coverArtId == null) {
    Box(
      modifier = modifier
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    return
  }

  val url by produceState<String?>(initialValue = null, coverArtId, sizePx) {
    value = runCatching { urlProvider(coverArtId, sizePx) }.getOrNull()
  }
  val context = LocalContext.current
  val key = coverArtCacheKey(coverArtId, sizePx)

  AsyncImage(
    model = ImageRequest.Builder(context)
      .data(url)
      // Both caches, explicitly. Omitting either leaves that half keyed on the URL, which changes
      // on every request because the salt does.
      .memoryCacheKey(key)
      .diskCacheKey(key)
      .build(),
    contentDescription = contentDescription,
    contentScale = ContentScale.Crop,
    modifier = modifier.clip(RoundedCornerShape(4.dp)),
  )
}
```

`produceState` is imported from `androidx.compose.runtime`; `getValue` from the same package is
needed for the `by` delegate on it. Add it if the compiler asks — this listing shows the file's
own imports, not the two operator imports Compose's delegates require.

- [ ] **Step 6: Write the ViewModels**

`feature/library/src/main/kotlin/app/muplay/library/LibraryViewModel.kt`:

```kotlin
package app.muplay.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.BrowseRepository
import app.muplay.database.LibraryRepository
import app.muplay.database.ShuffleRepository
import app.muplay.database.SyncEngine
import app.muplay.database.SyncState
import app.muplay.model.Album
import app.muplay.model.ShuffleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Wiring only. Every rule about what the screen shows lives in [libraryContent], which is pure
 * and unit-tested; this class combines flows, runs the three actions, and holds the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
  private val libraryRepository: LibraryRepository,
  private val browseRepository: BrowseRepository,
  private val shuffleRepository: ShuffleRepository,
  private val syncEngine: SyncEngine,
) : ViewModel() {

  private val selectedLibraryId = MutableStateFlow<Int?>(null)
  private val query = MutableStateFlow("")
  private val shuffle = MutableStateFlow<ShuffleResult?>(null)
  private val syncMessage = MutableStateFlow<String?>(null)

  private val albums: kotlinx.coroutines.flow.Flow<List<Album>> =
    combine(libraryRepository.libraries, selectedLibraryId) { libraries, selected ->
      libraries.firstOrNull { it.id == selected }?.id ?: libraries.firstOrNull()?.id
    }.flatMapLatest { id ->
      if (id == null) flowOf(emptyList()) else browseRepository.albums(id)
    }

  private val searchAlbums = MutableStateFlow<List<Album>>(emptyList())

  val uiState: StateFlow<LibraryUiState> =
    combine(
      libraryRepository.libraries,
      selectedLibraryId,
      query,
      albums,
      combine(searchAlbums, shuffle, syncMessage) { results, shuffled, message ->
        Triple(results, shuffled, message)
      },
    ) { libraries, selected, currentQuery, currentAlbums, extras ->
      libraryContent(
        libraries = libraries,
        selectedLibraryId = selected,
        query = currentQuery,
        albums = currentAlbums,
        searchAlbums = extras.first,
        shuffle = extras.second,
        syncMessage = extras.third,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState.Loading)

  init {
    refresh()
  }

  fun selectLibrary(id: Int) {
    selectedLibraryId.value = id
    // A shuffle belongs to the library it was drawn from; carrying it across a switch would show
    // music tracks under the audiobook tab, which is precisely the confusion this app removes.
    shuffle.value = null
    query.value = ""
    searchAlbums.value = emptyList()
  }

  fun search(newQuery: String) {
    query.value = newQuery
    viewModelScope.launch {
      val id = currentLibraryId() ?: return@launch
      searchAlbums.value =
        if (newQuery.isBlank()) emptyList()
        else browseRepository.search(id, newQuery, SEARCH_LIMIT).albums
    }
  }

  fun shuffle() {
    viewModelScope.launch {
      val id = currentLibraryId() ?: return@launch
      shuffle.value = runCatching {
        shuffleRepository.shuffle(id, ShuffleRepository.DEFAULT_SHUFFLE_SIZE)
      }.getOrElse { ShuffleResult(emptyList(), discardedOutOfScope = 0) }
    }
  }

  /**
   * Reconciles the mirror if the server has rescanned since the last committed sync.
   *
   * **This is the only thing in the app that syncs, and it only runs when something calls it** —
   * once from [init], and once per tap of the screen's Refresh action. There is deliberately no
   * periodic poller: spec §4's *"poll"* is about `getScanStatus`'s watermark being the delta
   * primitive, not about a background service, and a timer that wakes to ask a question nobody is
   * waiting for is battery spent on a user who is not looking at the screen. The user asks; the
   * app answers.
   *
   * Every message below is true when it is shown. [SyncState.ScanInProgress] used to read *"your
   * library will update shortly"*, which was a promise no code kept — nothing re-checked, ever. It
   * now describes the situation and names the control that resolves it.
   */
  fun refresh() {
    viewModelScope.launch {
      syncMessage.value = SYNCING_MESSAGE
      syncMessage.value = when (val state = syncEngine.syncIfStale()) {
        SyncState.UpToDate, is SyncState.Synced -> null
        SyncState.ScanInProgress ->
          "The server is still scanning, so some albums may be missing. Tap $REFRESH_LABEL when it has finished."
        is SyncState.Failed -> "Could not reach the server. Showing your last synced library."
      }
    }
  }

  private suspend fun currentLibraryId(): Int? =
    (uiState.value as? LibraryUiState.Content)?.selectedLibraryId
      ?: libraryRepository.allIds().firstOrNull()

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    browseRepository.coverArtUrl(coverArtId, sizePx)

  private companion object {
    const val STOP_TIMEOUT_MILLIS = 5_000L
    const val SEARCH_LIMIT = 50

    /**
     * Shown while a refresh is in flight. It is the only feedback the action gives, and it is
     * enough: the alternative was a fourth field on `LibraryUiState.Content` and a signature
     * change to the pure builder, for a spinner.
     */
    const val SYNCING_MESSAGE = "Checking the server for changes…"
  }
}
```

`feature/library/src/main/kotlin/app/muplay/library/AlbumUiState.kt`:

```kotlin
package app.muplay.library

import app.muplay.model.Album
import app.muplay.model.Song

/** One album's detail screen. */
sealed interface AlbumUiState {
  data object Loading : AlbumUiState

  /** The mirror has no such album — it was deleted on the server and a reconcile removed it. */
  data object NotFound : AlbumUiState

  data class Content(val album: Album, val songs: List<Song>) : AlbumUiState
}
```

`feature/library/src/main/kotlin/app/muplay/library/AlbumViewModel.kt`:

```kotlin
package app.muplay.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.BrowseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AlbumViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val browseRepository: BrowseRepository,
) : ViewModel() {

  private val albumId: String = checkNotNull(savedStateHandle[ALBUM_ID_KEY]) {
    "AlbumViewModel needs an `$ALBUM_ID_KEY` argument"
  }

  private val album = MutableStateFlow<app.muplay.model.Album?>(null)

  val uiState: StateFlow<AlbumUiState> =
    combine(album, browseRepository.songs(albumId)) { current, songs ->
      if (current == null) AlbumUiState.NotFound else AlbumUiState.Content(current, songs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlbumUiState.Loading)

  init {
    viewModelScope.launch { album.value = browseRepository.album(albumId) }
  }

  suspend fun coverArtUrl(coverArtId: String, sizePx: Int): String =
    browseRepository.coverArtUrl(coverArtId, sizePx)

  companion object {
    const val ALBUM_ID_KEY = "albumId"
    private const val STOP_TIMEOUT_MILLIS = 5_000L
  }
}
```

- [ ] **Step 7: Write the screens**

`feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt` — a library selector row,
a search field, a shuffle button, the album list, and the shuffle result. Every visible string
here is asserted by name in Task 10's journeys.

```kotlin
package app.muplay.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.model.Album

@Composable
fun LibraryScreen(
  onAlbumClick: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LibraryViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  LibraryScreen(
    uiState = uiState,
    onLibrarySelected = viewModel::selectLibrary,
    onQueryChanged = viewModel::search,
    onShuffle = viewModel::shuffle,
    onRefresh = viewModel::refresh,
    onAlbumClick = onAlbumClick,
    coverArtUrl = viewModel::coverArtUrl,
    modifier = modifier,
  )
}

@Composable
private fun LibraryScreen(
  uiState: LibraryUiState,
  onLibrarySelected: (Int) -> Unit,
  onQueryChanged: (String) -> Unit,
  onShuffle: () -> Unit,
  onRefresh: () -> Unit,
  onAlbumClick: (String) -> Unit,
  coverArtUrl: suspend (String, Int) -> String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    when (uiState) {
      LibraryUiState.Loading -> Text("Loading your library…")
      LibraryUiState.NoLibraries ->
        // Distinct from "this library is empty": the fix is finishing setup, not syncing.
        Text("No libraries yet. Finish setup to choose what each library is for.")
      is LibraryUiState.Content -> {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          uiState.libraries.forEach { library ->
            FilterChip(
              selected = library.id == uiState.selectedLibraryId,
              onClick = { onLibrarySelected(library.id) },
              label = { Text(library.name) },
            )
          }
        }

        OutlinedTextField(
          value = uiState.query,
          onValueChange = onQueryChanged,
          label = { Text(SEARCH_LABEL) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(onClick = onShuffle, modifier = Modifier.weight(1f)) { Text(SHUFFLE_LABEL) }
          // The only way a user has to pick up a change made on the server after the app started.
          // See "Why there is a Refresh action, and why it is a button" above.
          OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text(REFRESH_LABEL) }
        }

        // `onSurfaceVariant`, not `error`. All four of this string's values are *states* — checking,
        // the server is mid-scan, the server was unreachable, or nothing to say — and three of them
        // are ordinary. Painting "the server is scanning" red tells the user something is broken
        // when nothing is.
        uiState.syncMessage?.let {
          Text(text = it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (uiState.shuffled.isNotEmpty()) {
          Text(text = SHUFFLE_HEADING, style = MaterialTheme.typography.titleMedium)
          uiState.shuffled.forEach { song -> Text(text = song.title) }
          if (uiState.discardedOutOfScope > 0) {
            Text(
              text = "${uiState.discardedOutOfScope} tracks were outside this library and were skipped.",
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        if (uiState.albums.isEmpty()) {
          Text(EMPTY_LIBRARY_LABEL)
        } else {
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.albums, key = Album::id) { album ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                CoverArtImage(
                  coverArtId = album.coverArtId,
                  sizePx = COVER_THUMBNAIL_PX,
                  contentDescription = album.name,
                  urlProvider = coverArtUrl,
                  modifier = Modifier.size(56.dp),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                  Text(text = album.name, style = MaterialTheme.typography.bodyLarge)
                  album.artistName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                }
              }
              Button(onClick = { onAlbumClick(album.id) }) { Text(OPEN_LABEL) }
            }
          }
        }
      }
    }
  }
}

private const val SEARCH_LABEL = "Search this library"
private const val SHUFFLE_LABEL = "Shuffle this library"
/** `internal`, not `private`: [LibraryViewModel]'s scan-in-progress message names this control, and
 *  a message that names a button by a string typed twice is a message that drifts. */
internal const val REFRESH_LABEL = "Refresh library"
private const val SHUFFLE_HEADING = "Shuffled"
private const val EMPTY_LIBRARY_LABEL = "Nothing here yet."
private const val OPEN_LABEL = "Open"
private const val COVER_THUMBNAIL_PX = 128
```

`feature/library/src/main/kotlin/app/muplay/library/AlbumScreen.kt`:

```kotlin
package app.muplay.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AlbumScreen(modifier: Modifier = Modifier, viewModel: AlbumViewModel = hiltViewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (uiState) {
      AlbumUiState.Loading -> Text("Loading…")
      AlbumUiState.NotFound -> Text("That album is no longer in your library.")
      is AlbumUiState.Content -> {
        val content = uiState as AlbumUiState.Content
        CoverArtImage(
          coverArtId = content.album.coverArtId,
          sizePx = COVER_DETAIL_PX,
          contentDescription = content.album.name,
          urlProvider = viewModel::coverArtUrl,
          modifier = Modifier.size(160.dp),
        )
        Text(text = content.album.name, style = MaterialTheme.typography.headlineSmall)
        content.album.artistName?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        content.songs.forEach { song -> Text(text = song.title) }
      }
    }
  }
}

private const val COVER_DETAIL_PX = 512
```

- [ ] **Step 8: Wire navigation and the image loader**

`app/src/main/kotlin/app/muplay/ui/navigation/LibraryRoute.kt`:

```kotlin
package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The browse screen. `@Serializable` because `rememberNavBackStack` saves keys with `rememberSaveable`. */
@Serializable
data object LibraryRoute : NavKey
```

`app/src/main/kotlin/app/muplay/ui/navigation/AlbumRoute.kt`:

```kotlin
package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** One album's detail screen. [albumId] is the server's own stable album id. */
@Serializable
data class AlbumRoute(val albumId: String) : NavKey
```

`app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`:

```kotlin
@Composable
fun MuPlayApp(modifier: Modifier = Modifier) {
  val backStack = rememberNavBackStack(SetupRoute)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    modifier = modifier,
    entryProvider = entryProvider {
      entry<SetupRoute> {
        SetupScreen(
          onSetupComplete = {
            // Replace rather than push: going "back" into setup after finishing it would offer
            // to re-enter credentials the app already has.
            backStack.clear()
            backStack.add(LibraryRoute)
          },
        )
      }
      entry<LibraryRoute> {
        LibraryScreen(onAlbumClick = { albumId -> backStack.add(AlbumRoute(albumId)) })
      }
      entry<AlbumRoute> { AlbumScreen() }
    },
  )
}
```

`AlbumViewModel` reads `albumId` from its `SavedStateHandle`. Navigation 3 does not populate a
`SavedStateHandle` from a `NavKey`'s properties the way Navigation Compose's typed routes did, so
**verify this during Step 9** rather than assuming: if `savedStateHandle["albumId"]` is null on a
real device, pass the id explicitly instead —
`AlbumScreen(albumId = it.albumId)` with the id forwarded into
`viewModel.load(albumId)` from a `LaunchedEffect` — and record the substitution. The
`checkNotNull` in `AlbumViewModel` is what makes the wrong answer fail loudly and immediately
rather than showing an empty album.

`app/src/main/kotlin/app/muplay/MuPlayApplication.kt` — Coil needs an image loader that can fetch
`http(s)` URLs:

```kotlin
package app.muplay

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp

/**
 * Builds Coil's image loader explicitly rather than relying on service-loader discovery of the
 * network fetcher: a missing fetcher fails as "the image just never appears", which is the
 * hardest possible failure to diagnose from a screenshot.
 */
@HiltAndroidApp
class MuPlayApplication : Application(), SingletonImageLoader.Factory {

  override fun newImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
      .components { add(OkHttpNetworkFetcherFactory()) }
      .build()
}
```

- [ ] **Step 9: Run everything**

Run: `./gradlew :feature:library:test`
Expected: PASS — `CoverArtTest` 4/4, `LibraryUiStateTest` 7/7.

Run: `./gradlew build` — the whole project compiles and every JVM suite is green.

Then run the app against the container and walk the flow by hand once
(`ci/prepare-emulator.sh` sets up `adb reverse`): connect, tag both libraries, continue, see
albums with cover art, search, open an album, see its tracks. Task 10 turns that walk into the
required journeys; doing it by hand first is how the `SavedStateHandle` question above gets
answered before a journey is written against the wrong assumption.

- [ ] **Step 10: Add the floors and commit**

`:feature:library` is a **new module**, so `ConventionTest`'s
`every Gradle project has a coverage floor` (Task 1) fails until it has an entry. Measure after
Task 10's journeys exist — the LINE floors over `LibraryScreenKt`/`AlbumScreenKt` and the BRANCH
floor over `LibraryViewModel` need instrumented data, and from the JVM alone they read ~0. Until
then, add the two floors this task's own JVM tests can support (`LibraryUiStateKt`,
`CoverArtKt`), measured, and finish the table in Task 10.

```bash
git add settings.gradle.kts gradle/libs.versions.toml feature/library app build.gradle.kts
git commit -m "feat(library): browse, search and library-scoped shuffle UI"
```


---

## Task 10: The gates — Tier 2 journeys, the coverage table, and the classpath mock guard

**Files:**
- Create: `app/src/main/kotlin/app/muplay/ui/StartDestination.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/StartDestinationViewModel.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/kotlin/app/muplay/BrowseJourneyTest.kt`
- Create: `app/src/androidTest/kotlin/app/muplay/ScopedShuffleJourneyTest.kt`
- Modify: `build.gradle.kts` (the `verifyNoMockFrameworks` task, the completed floor table)
- Modify: `.github/workflows/pr.yml`, `.github/workflows/e2e.yml`
- Create: `.github/workflows/openapi-drift.yml` (the nightly, non-blocking oracle drift check)
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` (§10's Tier 2 table, §10's
  countermeasure 1)

**Interfaces:**
- Consumes: `CredentialStore.credentials`, `LibraryRepository.hasUnassignedLibraries()`,
  `SetupRoute`, `LibraryRoute`, `AlbumRoute`, every visible label from Tasks 8 and 9
- Produces:
  - sealed `StartDestination` with `Loading`, `Setup`, `Library`
  - `@HiltViewModel class StartDestinationViewModel` with `val startDestination: StateFlow<StartDestination>`
  - Gradle task `verifyNoMockFrameworks` in every subproject, wired into `check`
  - Tier 2 journeys `BrowseJourneyTest` and `ScopedShuffleJourneyTest`
  - the scheduled, **non-blocking** `openapi-drift` workflow — the third workflow in the repository
    and the only one that is not a gate

### Why the app must decide where it starts

Every launch currently lands on setup, which makes the stored credentials pointless and makes
every journey depend on which journey ran before it. Deciding the start destination from stored
state fixes both: the product behaviour is right, and a journey can call one helper that reaches
the library screen from **either** starting state, so the emulator suite has no hidden ordering.

- [ ] **Step 1: Decide the start destination**

`app/src/main/kotlin/app/muplay/ui/StartDestination.kt`:

```kotlin
package app.muplay.ui

/**
 * Where the app opens.
 *
 * [Setup] covers two different situations on purpose — no credentials at all, and credentials
 * with a library still untagged. Both need the same screen, and an untagged library is not a
 * lesser problem: it is invisible to every browse and shuffle path, so opening the library screen
 * with one outstanding would show a user an app that silently does nothing.
 */
sealed interface StartDestination {
  data object Loading : StartDestination
  data object Setup : StartDestination
  data object Library : StartDestination
}
```

`app/src/main/kotlin/app/muplay/ui/StartDestinationViewModel.kt`:

```kotlin
package app.muplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
  private val credentialStore: CredentialStore,
  private val libraryRepository: LibraryRepository,
) : ViewModel() {

  private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
  val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

  init {
    viewModelScope.launch {
      val configured = credentialStore.load() != null
      _startDestination.value =
        if (configured && !libraryRepository.hasUnassignedLibraries()) StartDestination.Library
        else StartDestination.Setup
    }
  }
}
```

`app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`:

```kotlin
@Composable
fun MuPlayApp(
  modifier: Modifier = Modifier,
  viewModel: StartDestinationViewModel = hiltViewModel(),
) {
  val start by viewModel.startDestination.collectAsStateWithLifecycle()

  when (start) {
    StartDestination.Loading -> Unit
    StartDestination.Setup -> MuPlayNavigation(SetupRoute, modifier)
    StartDestination.Library -> MuPlayNavigation(LibraryRoute, modifier)
  }
}

@Composable
private fun MuPlayNavigation(start: NavKey, modifier: Modifier) {
  val backStack = rememberNavBackStack(start)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    modifier = modifier,
    entryProvider = entryProvider {
      entry<SetupRoute> {
        SetupScreen(
          onSetupComplete = {
            // Replace rather than push: going "back" into setup after finishing it would offer to
            // re-enter credentials the app already has.
            backStack.clear()
            backStack.add(LibraryRoute)
          },
        )
      }
      entry<LibraryRoute> {
        LibraryScreen(onAlbumClick = { albumId -> backStack.add(AlbumRoute(albumId)) })
      }
      entry<AlbumRoute> { AlbumScreen() }
    },
  )
}
```

with imports for `androidx.hilt.navigation.compose.hiltViewModel`,
`androidx.lifecycle.compose.collectAsStateWithLifecycle`, `androidx.compose.runtime.getValue`,
`androidx.navigation3.runtime.NavKey`, and the two new routes.

`app/build.gradle.kts` — three additions:

```kotlin
  // `MuPlayApp` now hosts a ViewModel and collects a StateFlow, neither of which this module
  // depended on before: it was a pure navigation shell.
  implementation(libs.hilt.navigation.compose)
  implementation(libs.lifecycle.runtime.compose)

  // ActivityScenario and ApplicationProvider for the journeys.
  androidTestImplementation(libs.androidx.test.core)
```

- [ ] **Step 2: Write the failing browse journey**

`app/src/androidTest/kotlin/app/muplay/BrowseJourneyTest.kt`:

```kotlin
package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: browsing a real Navidrome's real library, on a real emulator.
 *
 * Nothing here is faked. The app is the real debug APK; the server is the pinned
 * `deluan/navidrome:0.63.2` from `ci/navidrome.compose.yml`, seeded by `ci/seed-fixtures.sh` and
 * configured by `ci/configure-libraries.sh` into `Music` (library 1) and `Audiobooks`
 * (library 2). The preconditions this test cannot establish for itself — the container being up,
 * `adb reverse tcp:4533 tcp:4533`, and the emulator's `-feature Minigbm -prop
 * qemu.hardware.gralloc=minigbm` boot flags — are all handled by `ci/prepare-emulator.sh`, which
 * `.github/workflows/e2e.yml` runs and which a local run must run too. See
 * `FirstRunJourneyTest`'s own documentation for what each one costs when it is missing.
 *
 * [reachLibraryScreen] makes every test here independent of which test ran before it: the app
 * opens on setup or on the library depending on stored state, and this helper handles both.
 */
@RunWith(AndroidJUnit4::class)
class BrowseJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun theLibraryScreenListsTheAlbumsOfTheSelectedLibrary() {
    reachLibraryScreen()

    // The seeded music library: one album, "Test Album", from ci/seed-fixtures.sh. A contract on
    // real server state, not on a response shape.
    composeRule.onNodeWithText("Test Album").assertIsDisplayed()
    composeRule.onNodeWithText("Test Artist").assertIsDisplayed()
  }

  @Test
  fun switchingLibraryShowsTheOtherLibrarysContentAndOnlyThat() {
    reachLibraryScreen()

    composeRule.onAllNodesWithText("Audiobooks")[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Test Book").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Test Book").assertIsDisplayed()
    // The scoping contract at the UI level: the music album must be gone, not merely further
    // down the list.
    composeRule.onNodeWithText("Test Album").assertDoesNotExist()
  }

  @Test
  fun openingAnAlbumShowsItsTracks() {
    reachLibraryScreen()

    composeRule.onAllNodesWithText("Open")[FIRST_ALBUM].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Track 1").assertIsDisplayed()
    composeRule.onNodeWithText("Track 2").assertIsDisplayed()
    composeRule.onNodeWithText("Track 3").assertIsDisplayed()
  }

  @Test
  fun searchNarrowsTheListAndClearingItRestoresTheList() {
    reachLibraryScreen()

    composeRule.onNodeWithText(SEARCH_LABEL).performTextInput("Nothing Matches This")
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Test Album").fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithText("Nothing here yet.").assertIsDisplayed()

    composeRule.onNodeWithText(SEARCH_LABEL).performTextClearance()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Test Album").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Test Album").assertIsDisplayed()
  }

  @Test
  fun searchFindsTheAlbumByAPartialName() {
    reachLibraryScreen()

    composeRule.onNodeWithText(SEARCH_LABEL).performTextInput("alb")
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Test Album").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Test Album").assertIsDisplayed()
  }

  /**
   * The user has a way to pick up a server-side change, and it is on the screen.
   *
   * `syncIfStale()` had exactly one caller — `LibraryViewModel.init` — so before this journey
   * existed, an album added to Navidrome mid-session never appeared, and the `ScanInProgress`
   * branch told the user their library would "update shortly" while nothing re-checked. This test
   * is the standing guarantee that the control the copy names is really there: delete the button
   * and it goes red, which is the point.
   */
  @Test
  fun theLibraryCanBeRefreshedFromTheScreen() {
    reachLibraryScreen()

    composeRule.onNodeWithText(REFRESH_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText(REFRESH_LABEL).performClick()

    // A refresh against an up-to-date mirror settles back to no message at all. Waiting for the
    // message to *clear* is what proves the call completed rather than that a button existed:
    // `syncIfStale` sets the "checking" message first, so a click that reached nothing would
    // leave that string on screen.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SYNCING_MESSAGE).fetchSemanticsNodes().isEmpty()
    }
    composeRule.onNodeWithText("Test Album").assertIsDisplayed()
  }

  /**
   * Spec §7: *"Predictive back is default-on and must be implemented."*
   *
   * Plan 1 set `android:enableOnBackInvokedCallback="true"` and this plan gives `NavDisplay` a real
   * back stack with `onBack` — so the phone side works. **No plan named it as a deliverable and no
   * journey asserted it**, which is how a working behaviour becomes an unnoticed regression: the
   * day someone replaces `backStack.removeLastOrNull()` with a no-op, every test stays green and
   * the back gesture closes the app from the album screen. Plan 5 owns the watch side properly;
   * this is the phone side, and it is one assertion.
   */
  @Test
  fun backFromAnAlbumReturnsToTheLibraryRatherThanLeavingTheApp() {
    reachLibraryScreen()
    composeRule.onAllNodesWithText("Open")[FIRST_ALBUM].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }

    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
      .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    // Back to the library, still inside the app. `SHUFFLE_LABEL` is only on the library screen, so
    // finding it proves both halves at once — and the activity not having been destroyed is what
    // `composeRule` finding anything at all proves.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Test Album").assertIsDisplayed()
  }

  /**
   * Drives the app from whatever state it opened in to the library screen.
   *
   * The app opens on setup when no credentials are stored **or** any library is still untagged,
   * and on the library otherwise — so which branch this takes depends on what earlier tests in
   * the same instrumentation run left behind. Handling both here is what makes every test in this
   * class independent of run order, without any test needing to clear app data (which, from
   * inside the app's own process, is not something a test can do cleanly).
   */
  private fun reachLibraryScreen() {
    val needsSetup = composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
    if (needsSetup) {
      composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
      composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
      composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
      composeRule.onNodeWithText(CONNECT_LABEL).performClick()

      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onAllNodesWithText("Music")[MUSIC_ROLE_CHIP].performClick()
      composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOK_ROLE_CHIP].performClick()
      composeRule.onNodeWithText(CONTINUE_LABEL).performClick()
    }

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    // The literal strings the real screens render. Duplicated from the production code rather
    // than shared with it: these journeys are a black-box walk through what a user sees, and a
    // shared constant would let a change to that pass unnoticed.
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val SEARCH_LABEL = "Search this library"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val REFRESH_LABEL = "Refresh library"
    const val SYNCING_MESSAGE = "Checking the server for changes…"

    /** See FirstRunJourneyTest for why these are indices; verify them by running, not by reasoning. */
    const val MUSIC_ROLE_CHIP = 1
    const val AUDIOBOOK_ROLE_CHIP = 2
    const val LIBRARY_CHIP = 0
    const val FIRST_ALBUM = 0

    /** Generous: a first sync fetches every album and every album's tracks over the loopback. */
    const val TIMEOUT_MILLIS = 30_000L
  }
}
```

- [ ] **Step 3: Write the failing scoped-shuffle journey**

`app/src/androidTest/kotlin/app/muplay/ScopedShuffleJourneyTest.kt`:

```kotlin
package app.muplay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: **the feature this application exists for**, end to end.
 *
 * Real app, real emulator, real Navidrome with two real libraries. Shuffle the music library
 * repeatedly and assert the audiobook never appears — the assertion the user actually cares
 * about, which no unit test and no fixture can make, because its subject is the whole chain: the
 * request the client builds, the scoping the server applies, the mirror's own stamp, and the
 * screen that renders the result.
 *
 * The audiobook control below is not decoration. Without it this suite would pass identically
 * against an app that shuffled nothing at all — which is the exact shape of the silent gate this
 * project has already shipped once (a live-Navidrome test that passed with no Navidrome).
 */
@RunWith(AndroidJUnit4::class)
class ScopedShuffleJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun shufflingTheMusicLibraryNeverSurfacesAnAudiobook() {
    reachLibraryScreen()

    repeat(SHUFFLE_ATTEMPTS) {
      composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
      }

      // The whole point, asserted on screen: the audiobook chapter is never in a music shuffle.
      composeRule.onNodeWithText(AUDIOBOOK_TITLE).assertDoesNotExist()
      // ...and something was actually shuffled, so the assertion above is not vacuous.
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes()
        .plus(composeRule.onAllNodesWithText("Track 2").fetchSemanticsNodes())
        .plus(composeRule.onAllNodesWithText("Track 3").fetchSemanticsNodes())
        .also { check(it.isNotEmpty()) { "a music shuffle returned no music" } }
    }
  }

  @Test
  fun shufflingTheAudiobookLibraryDoesSurfaceTheAudiobook() {
    reachLibraryScreen()

    composeRule.onAllNodesWithText("Audiobooks")[LIBRARY_CHIP].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Test Book").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    // The control that makes the first test mean something.
    composeRule.onNodeWithText(AUDIOBOOK_TITLE).assertIsDisplayed()
  }

  @Test
  fun switchingLibraryClearsThePreviousShuffle() {
    reachLibraryScreen()

    composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onAllNodesWithText("Audiobooks")[LIBRARY_CHIP].performClick()

    // A shuffle belongs to the library it was drawn from. Carrying it across a switch would show
    // music tracks under the audiobook tab, which is the exact confusion this app removes.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isEmpty()
    }
  }

  /** Identical in intent to `BrowseJourneyTest.reachLibraryScreen`; see that class for why. */
  private fun reachLibraryScreen() {
    val needsSetup = composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
    if (needsSetup) {
      composeRule.onNodeWithText(SERVER_URL_LABEL).performTextInput(SERVER_URL)
      composeRule.onNodeWithText(USERNAME_LABEL).performTextInput(USERNAME)
      composeRule.onNodeWithText(PASSWORD_LABEL).performTextInput(PASSWORD)
      composeRule.onNodeWithText(CONNECT_LABEL).performClick()

      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onAllNodesWithText("Music")[MUSIC_ROLE_CHIP].performClick()
      composeRule.onAllNodesWithText("Audiobooks")[AUDIOBOOK_ROLE_CHIP].performClick()
      composeRule.onNodeWithText(CONTINUE_LABEL).performClick()
    }

    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(SHUFFLE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private companion object {
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val SHUFFLE_HEADING = "Shuffled"

    /** The one seeded audiobook — ci/seed-fixtures.sh writes `Test Book.m4b`. */
    const val AUDIOBOOK_TITLE = "Test Book"

    const val MUSIC_ROLE_CHIP = 1
    const val AUDIOBOOK_ROLE_CHIP = 2
    const val LIBRARY_CHIP = 0

    /**
     * Ten on the device, against fifty in `LiveNavidromeTest`. The server-side scoping is already
     * proven fifty times over in Tier 1; what this journey adds is the whole chain through the
     * mirror and the UI, and each attempt here costs an emulator round trip against a 45-minute
     * job budget.
     */
    const val SHUFFLE_ATTEMPTS = 10

    const val TIMEOUT_MILLIS = 30_000L
  }
}
```

- [ ] **Step 4: Run the journeys and prove they can fail**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
# emulator up, then:
./ci/prepare-emulator.sh
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS — `FirstRunJourneyTest` 3/3, `BrowseJourneyTest` 7/7,
`ScopedShuffleJourneyTest` 3/3.

Then **prove each can fail**, restoring after each:

1. In `SubsonicClient.getRandomSongs`, drop `musicFolderId` from the parameter map **and** make
   `ShuffleRepository.shuffle` return `returned` unfiltered. Expect
   `shufflingTheMusicLibraryNeverSurfacesAnAudiobook` to fail with "Test Book" on screen. Both
   mutations are needed at once, which is itself the evidence that the two defences are
   independent.
2. Stop the container and re-run. Expect red, not green.
3. Skip `./ci/prepare-emulator.sh` (no `adb reverse`) and re-run. Expect the connect attempt to
   time out — spike S1's finding, that a blocked connection manifests as a **silent connect
   timeout** rather than an error, is why this shows up as a `waitUntil` timeout and not as
   anything naming the real cause. Record the message so the next person recognises it.
4. Delete the Refresh button from `LibraryScreen`. Expect `theLibraryCanBeRefreshedFromTheScreen`
   to fail. Then restore the button and make `LibraryViewModel.refresh` a no-op body: expect the
   same test to fail on the *second* assertion, because the "checking" message never clears. The
   two halves are separate defects — a control that is not there, and a control that does nothing
   — and each has to be independently visible.
5. Replace `onBack = { backStack.removeLastOrNull() }` in `MuPlayApp` with `onBack = {}`. Expect
   `backFromAnAlbumReturnsToTheLibraryRatherThanLeavingTheApp` to fail. This is the assertion spec
   §7's predictive-back requirement never had; before it, the behaviour worked by construction and
   nothing would have noticed it stopping.

- [ ] **Step 5: Build the resolved-classpath mock guard**

`plan-2-inherited.md` item 3: `ConventionTest` bans mock frameworks by scanning *declared* names
in the catalogue, module build files and build-logic sources. That cannot catch one arriving
transitively — and this plan adds Room, DataStore, Coil, Hilt-navigation and their transitive
graphs, which is exactly when "declared" and "resolved" stop being the same set.

`build.gradle.kts` — add, following this file's existing convention that anything a task action
calls lives inside a genuine Kotlin `object` (a script-level function captures the whole script
object, which the configuration cache refuses to serialize):

```kotlin
/**
 * Every mock framework this project bans, by Maven group. Groups rather than artifact names
 * because a framework's artifact set changes between versions while its group does not, and
 * because a group match cannot be defeated by a rename.
 *
 * `org.objenesis` is on the list although it is not itself a mock framework: it is the
 * instantiation engine every JVM mocking library depends on, so its presence on a test runtime
 * classpath means one of them arrived, whatever it is called.
 */
val BANNED_MOCK_GROUPS = listOf(
  "org.mockito",
  "io.mockk",
  "org.easymock",
  "org.powermock",
  "dev.mokkery",
  "io.mockative",
  "org.jmockit",
  "org.objenesis",
)

/** The resolvable configurations whose contents actually reach a test JVM or a test APK. */
val MOCK_GUARD_CONFIGURATIONS = listOf(
  "testRuntimeClasspath",
  "testDebugRuntimeClasspath",
  "androidTestDebugRuntimeClasspath",
)

object MockFrameworkChecker {
  /**
   * Fails when any [banned] group appears in [resolved], and **also** fails when [resolved] is
   * empty. The second half is the point: a check that cannot report its own subject's absence is
   * not a check, and a guard that silently inspects zero classpaths reads exactly like a guard
   * that found nothing wrong.
   */
  fun check(projectPath: String, resolved: Map<String, List<String>>, banned: List<String>) {
    if (resolved.isEmpty()) {
      throw GradleException(
        "$projectPath: verifyNoMockFrameworks resolved no classpaths at all. Either this project " +
          "has no test configuration (in which case it should not have this task) or the " +
          "configuration names in MOCK_GUARD_CONFIGURATIONS have drifted. A guard that inspects " +
          "nothing passes for the wrong reason.",
      )
    }
    val offenders = resolved.flatMap { (configuration, artifacts) ->
      artifacts.filter { artifact -> banned.any { artifact.startsWith("$it:") } }
        .map { "$configuration -> $it" }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException(
        "$projectPath: a mock framework reached a test classpath: $offenders. This project uses " +
          "hand-written fakes only (see the spec's testing section); a test satisfied by a mock " +
          "returning what it was told returns no information.",
      )
    }
  }
}

subprojects {
  // `afterEvaluate`, because a project's configurations do not exist until its own build script
  // and its plugins have run -- the same reason the `liveNavidromeTest` registration above uses it.
  afterEvaluate {
    val resolvedByConfiguration = MOCK_GUARD_CONFIGURATIONS.mapNotNull { name ->
      configurations.findByName(name)?.let { configuration ->
        // A lazy Provider captured at configuration time, never a live Configuration read inside
        // the task action: this is the pattern `Jacoco.kt`'s agent assertion already uses, and it
        // is what keeps the configuration cache able to serialize this task.
        name to configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
          artifacts.map { it.id.componentIdentifier.displayName }
        }
      }
    }
    if (resolvedByConfiguration.isEmpty()) return@afterEvaluate

    val guard = tasks.register("verifyNoMockFrameworks") {
      group = "verification"
      description = "Fails if any mock framework is on a resolved test runtime classpath."
      val projectPath = path
      val banned = BANNED_MOCK_GROUPS
      val inputs = resolvedByConfiguration
      doLast {
        MockFrameworkChecker.check(projectPath, inputs.associate { it.first to it.second.get() }, banned)
      }
    }
    tasks.named("check") { dependsOn(guard) }
  }
}
```

**Prove it can fail** before trusting it: add `testImplementation("org.mockito:mockito-core:5.14.2")`
to `core/model/build.gradle.kts`, run `./gradlew :core:model:verifyNoMockFrameworks`, and confirm
it names the artifact. (`ConventionTest`'s textual rule fires too — that is two independent
guards agreeing, not redundancy.) Remove the dependency and confirm green. Then prove the
empty-input half: temporarily change `MOCK_GUARD_CONFIGURATIONS` to a name no project has and
confirm the task fails with the "resolved no classpaths at all" message rather than passing.

`.github/workflows/pr.yml` — add to the `static-analysis` job, after "Convention rules":

```yaml
      - name: No mock framework on any test classpath
        # ConventionTest scans *declared* names; this resolves every test runtime classpath and
        # catches one arriving transitively. Two guards, deliberately: the textual one runs in
        # seconds with no resolution, this one is the real answer.
        run: ./gradlew verifyNoMockFrameworks
```

- [ ] **Step 6: Complete and prove the coverage table**

Run the whole thing, in the order the two tiers actually run it:

```bash
./gradlew test
./gradlew jacocoJvmCoverageVerification
# emulator + container up:
./ci/prepare-emulator.sh
./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
```

Then, for **every** module this plan touched — `:core:model`, `:core:network`, `:core:database`,
`:feature:setup`, `:feature:library`, `:app` — read the measured per-class ratios out of each
module's `jacocoTestReport.xml` (the snippet in Task 1, Step 10) and make the `coverageFloors`
entry match:

- **Branch ≥ 0.90 for non-UI code, line ≥ 0.90 for `@Composable`-bearing files.** Where a
  measured branch ratio on non-UI code is below 0.90, the answer is another test, not a lower
  floor — Plan 1 closed every one of its gaps this way rather than excusing them.
- **`requiresInstrumentedData` is a measurement, not a judgement.** Delete the instrumented
  `.ec` files, run `jacocoJvmCoverageVerification`, and set the flag on exactly the floors that
  fail. Getting it wrong still fails safe (a floor left `false` that needs a device fails Tier 1
  loudly), but the table is read by people and must be true.
- **Every floor must be able to fail.** For each module, delete one assertion, confirm its floor
  goes red, restore it. Record which assertion, per module, in the task report. A floor nobody
  has watched fail is a floor nobody knows works.
- **No `COVERAGE:` warning may be left standing.** `warnUngatedClasses` and `warnVacuousFloors`
  print to the build log and, under GitHub Actions, as annotations. An ungated new class or a
  vacuous floor is exactly what this plan must not add.

Confirm `ConventionTest`'s `every Gradle project has a coverage floor` passes — with
`:core:database` and `:feature:library` both in the table.

- [ ] **Step 7: Confirm the Tier 2 workflow runs everything**

`.github/workflows/e2e.yml` — the `script:` block should already run
`:core:database:connectedDebugAndroidTest` from Task 1. Confirm it, and that the coverage steps
that follow it are unchanged:

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

The job's `timeout-minutes: 45` now covers two connected test tasks and three journey classes
instead of one. Measure the real wall-clock time of a full run and, if it lands within ten
minutes of the limit, raise the limit **and say what was measured** — a gate that starts flaking
on time gets disabled, which is the worst outcome available.

- [ ] **Step 8: The nightly OpenAPI drift check**

Spec §10's first countermeasure says the OpenSubsonic OpenAPI spec is *"vendored, with a nightly
non-blocking drift check"*. **There is no such check.** The repository has `pr.yml` and `e2e.yml`
and nothing else, and the word "nightly" appears in the seven plans only to say that Tier 2 is *not*
nightly. So the oracle — the thing this project's whole testing stance rests on — ages invisibly.

That matters more here than the phrase "invisible to a user" suggests. `NavidromeSpecDeviationTest`
pins two divergences by asserting the **rejection**, in *both* directions: if a vendored-spec refresh
ever models Navidrome's `scanStatus`, or upstream fixes `AlbumID3.userRating`'s `minimum: 1`, the
build goes red and someone reads that file. But that only happens if somebody refreshes the vendored
copy, and nothing schedules it. The pinned assertions are a trap with nobody scheduled to check it.

**This is the one thing in this plan that is deliberately not a gate**, and it belongs here rather
than in a plan of its own for the same reason the `contract` job does: this task owns the gates, and
"the gate this project chose not to make blocking" is a decision about gates.

`.github/workflows/openapi-drift.yml`:

```yaml
name: OpenAPI drift

# Nightly and on demand. NOT on pull_request: this workflow asks a question about the *upstream
# world*, which can change while this repository does not, so a failure here is never a reason to
# block a change somebody wrote. Spec section 10 calls it "non-blocking" and means it.
on:
  schedule:
    - cron: "17 3 * * *"
  workflow_dispatch:

# Read-only. This workflow reports; it does not open an issue, push a refreshed spec, or comment
# on anything. Refreshing the vendored copy is a human decision, because it can turn
# `NavidromeSpecDeviationTest`'s pinned assertions red on purpose.
permissions:
  contents: read

jobs:
  drift:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v7

      # The vendored copy, byte for byte, is the subject. `opensubsonic-1.16.1.json` is the file
      # `OpenApiFixtureValidator` loads, so this compares the exact artefact the oracle uses --
      # not a rendering of it, and not a version string that can lag the content.
      - name: Fetch the upstream specification
        id: fetch
        run: |
          set -euo pipefail
          curl -fsSL "$UPSTREAM_SPEC_URL" -o /tmp/upstream.json
          # Normalised through `jq -S` so a key reordering is not reported as drift. A reordering
          # is not drift; a changed schema is.
          jq -S . /tmp/upstream.json > /tmp/upstream.norm.json
          jq -S . core/testing/src/main/resources/openapi/opensubsonic-1.16.1.json > /tmp/vendored.norm.json
          if diff -q /tmp/vendored.norm.json /tmp/upstream.norm.json >/dev/null; then
            echo "drifted=false" >> "$GITHUB_OUTPUT"
          else
            echo "drifted=true" >> "$GITHUB_OUTPUT"
            diff -u /tmp/vendored.norm.json /tmp/upstream.norm.json | head -400 > /tmp/drift.diff
          fi
        env:
          # The exact URL Plan 1 vendored from -- see Plan 1 Task 3, which fetched this file with
          # the same `curl`. Verified while this step was written: HTTP 200, 453,720 bytes,
          # `openapi: 3.0.0`, `info.version: 1.16.1`, **87 paths and 195 schemas** -- the same two
          # numbers spec section 10 quotes.
          UPSTREAM_SPEC_URL: https://opensubsonic.netlify.app/docs/openapi/openapi.json

      # A workflow whose only failure mode is silence is the defect class this project keeps
      # finding. Say the answer out loud on every run, including the boring one.
      - name: Report
        run: |
          if [ "${{ steps.fetch.outputs.drifted }}" = "true" ]; then
            echo "DRIFT: the vendored OpenSubsonic specification differs from upstream."
            cat /tmp/drift.diff
          else
            echo "NO DRIFT: the vendored OpenSubsonic specification matches upstream."
          fi

      - name: Upload the diff
        if: steps.fetch.outputs.drifted == 'true'
        uses: actions/upload-artifact@v7
        with:
          name: openapi-drift
          path: /tmp/drift.diff
```

Three things about it are decisions rather than defaults, and each is written into the file:

- **It never runs on `pull_request`.** Its subject is upstream, which changes without anybody here
  doing anything. A red mark on somebody's unrelated change would get the workflow deleted inside a
  month, which is how a non-blocking check becomes no check.
- **`jq -S` before the diff.** A key reordering upstream is not drift, and a checker that cries
  every night is one nobody reads.
- **It reports on the quiet path too.** This project has found eleven gates that passed by never
  running. A drift check that only speaks when it has something to say is indistinguishable, from
  the outside, from one whose `curl` has been 404ing for six weeks.

**The URL was confirmed while this step was written, and the answer is worth recording rather than
re-deriving.** `curl -fsSL https://opensubsonic.netlify.app/docs/openapi/openapi.json` returns HTTP
200 and 453,720 bytes; parsed, it is `openapi: 3.0.0`, `info.version: 1.16.1`, **87 paths and 195
schemas**, matching spec §10's own description of the oracle. Normalised with sorted keys it is
**byte-identical to the vendored copy** as of 2026-08-25 — so the check's first real run should
report `NO DRIFT`, and anything else on day one means the fetch is wrong rather than that upstream
moved.

Two guesses were wrong on the way to that, and both are recorded so nobody repeats them:
`raw.githubusercontent.com/opensubsonic/open-subsonic-api/main/static/open-subsonic-api.json` and
the same path without `static/` both 404. **Re-confirm before committing anyway** — pointing a drift
check at a 404 is the purest possible form of the defect it exists to prevent, and if the file has
moved since, that discovery *is* the first drift finding.

**And confirm the check can report drift**, the way every other gate in this project is proved:
change one character in the vendored copy, run `gh workflow run "OpenAPI drift"`, and require
`DRIFT:` in the log. Restore the character afterwards. A drift check nobody has watched report drift
is a drift check nobody knows works — the same rule the coverage floors are held to.

- [ ] **Step 9: Correct the spec**

`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` §10, the **Tier 2** table. It already
lists `Browse` and `Library-scoped shuffle` as journeys. Both are now real, so nothing needs
adding there — but the "Tier 2 grows with each plan" line means the table should say which suite
holds them. Add a line under the table:

> Plan 2 added `BrowseJourneyTest` (browse, search, album detail, cover art, **the Refresh action**,
> and **phone predictive back** — spec §7 requires it and no plan had ever asserted it) and
> `ScopedShuffleJourneyTest` (shuffle Music repeatedly; assert no audiobook ever appears, with the
> Audiobooks library as the control that keeps the assertion non-vacuous), plus
> `:core:database`'s instrumented Room suite. Tier 1 gained `:core:network`'s live scoping
> assertions, including the pinned proof that a **non-numeric `musicFolderId` is silently ignored
> and widens the scope to every library**.

Also fold in Task 3's spec corrections if they were not already committed there: the
`musicFolderId` trap's real shape, and the three fixture/spec deviations.

And §10's **countermeasure 1**, which says the vendored spec comes *"with a nightly non-blocking
drift check"* — a claim that was false for seven plans. Step 8 makes it true; name the workflow so
the claim is checkable rather than aspirational:

> … vendored, with a **nightly non-blocking drift check** — `.github/workflows/openapi-drift.yml`,
> scheduled and `workflow_dispatch`-able, never run on a pull request, and reporting on the quiet
> path as well as the loud one. It is deliberately **not** a gate: its subject is upstream, which
> changes without anything here changing, so a failure is a prompt to look rather than a reason to
> block somebody's work.

- [ ] **Step 10: Final green run and commit**

Run, and require every one to pass:

```bash
./gradlew build
./gradlew verifyNoMockFrameworks
./gradlew jacocoJvmCoverageVerification
./gradlew :core:network:liveNavidromeTest        # container up
./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

```bash
git add app build.gradle.kts .github/workflows docs/superpowers/specs
git commit -m "ci: tier 2 browse and scoped-shuffle journeys, resolved-classpath mock guard"
```

The nightly drift check is a separate commit, because it is a separate decision and the only thing
in this repository that is not a gate:

```bash
git add .github/workflows/openapi-drift.yml docs/superpowers/specs
git commit -m "ci: the nightly OpenAPI drift check spec §10 has always claimed"
```

---

## Task 11: `:feature:settings` — the server connection, re-tagging, and one slot

**Files:**
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`, `build.gradle.kts`
- Create: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/main/kotlin/app/muplay/settings/SettingsUiState.kt`
- Create: `feature/settings/src/main/kotlin/app/muplay/settings/SettingsViewModel.kt`
- Create: `feature/settings/src/main/kotlin/app/muplay/settings/SettingsScreen.kt`
- Create: `feature/settings/src/main/kotlin/app/muplay/settings/SettingsSection.kt`
- Create: `feature/settings/src/main/kotlin/app/muplay/settings/di/SettingsModule.kt`
- Create: `core/network/src/main/kotlin/app/muplay/network/SubsonicAuthMethod.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicAuth.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/SyncEngine.kt`
- Modify: `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/SettingsRoute.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Test: `feature/settings/src/test/kotlin/app/muplay/settings/SettingsUiStateTest.kt`
- Test: `core/network/src/test/kotlin/app/muplay/network/SubsonicAuthMethodTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/SyncEngineResetTest.kt`
- Create: `app/src/androidTest/kotlin/app/muplay/SettingsJourneyTest.kt`
- Modify: `.github/workflows/e2e.yml`

**Interfaces:**
- Consumes: `CredentialStore` (`save`, `load`, `clear`, `credentials` — Task 2),
  `SubsonicSourceFactory` / `SubsonicSource.ping` / `.getMusicFolders` (Task 3),
  `LibraryRepository` (`libraries`, `refreshFromServer`, `setRole` — Task 4),
  `SyncEngine` (Task 6), `SubsonicCredentials`, `MusicLibrary`, `LibraryRole`,
  `SubsonicErrorException`, `SubsonicHttpException`
- Produces:
  - sealed `SettingsUiState` with `Loading`, `NotConfigured`,
    `Content(serverUrl: String, username: String, libraries: List<MusicLibrary>, connectionMessage: String?, isBusy: Boolean)`
  - `internal fun settingsContent(credentials, libraries, connectionMessage, isBusy): SettingsUiState`
  - `@HiltViewModel class SettingsViewModel` with `uiState: StateFlow<SettingsUiState>`,
    `fun setRole(musicFolderId: Int, role: LibraryRole)`,
    `fun reconnect(serverUrl: String, username: String, password: String)`
  - `SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel(), sections: Set<SettingsSection> = ...)`
  - `interface SettingsSection { val order: Int; @Composable fun Content() }` and an empty
    `@Multibinds` declaration for it
  - `SettingsRoute` navigation key; `SETTINGS_LABEL` on the library screen
  - `sealed interface SubsonicAuthMethod` with `data object Token`, and
    `SubsonicAuth.authParams` delegating to it
  - `SyncEngine.resetForNewServer()`
- **Plan 6 interaction:** Plan 6 Task 12 contributes its `RendererDirect` toggle as a
  `SettingsSection`. That is the *whole* reason the slot exists — see below.
- **Plan 7 interaction:** Plan 7 Task 10 records that *"spec §9 names a `feature/settings` module;
  it does not exist in the tree as this plan is written, and no plan before this one creates it"*,
  and works around it with an overflow menu item. **That workaround is superseded by this task.**
  Plan 7 is outside this repair's editable set, so it is not edited here; its integration settings
  should become a `SettingsSection` like Plan 6's rather than an overflow item.

### The two things a user cannot do today, and why the second one is serious

Spec §9's module list names `feature/settings`. **No plan creates it**, and Plan 7 Task 10 says so
in as many words before routing around it. The consequences are not evenly weighted:

- **There is no way to change the server URL.** Annoying: a user who moves Navidrome behind a
  different hostname reinstalls the app.
- **There is no way to re-tag a library.** Not annoying — **unrecoverable.** The Music/Audiobooks
  tag is the only mechanism library-scoped shuffle has (Task 4: Navidrome hardcodes
  `child.Type = "music"` and will never tell a client that something is an audiobook). The user
  sets it exactly once, during first-run setup, from a screen that deliberately *"must not guess"*
  — and a mistake there poisons the headline feature permanently. Their only route back is clearing
  app data, which also destroys every audiobook position, which is the other thing this app exists
  for.

So a user who taps the wrong chip once, in the thirty seconds they spend on the setup screen, has
to choose between a poisoned shuffle and losing their listening history. That is what this task
removes.

### Minimal means minimal

There is **no preferences framework here**. No `Preference` type, no key registry, no schema, no
category system, no search. Two sections this task writes, and one slot other modules can fill:

```
Server        the URL and username it is connected as, and a way to change them.
Libraries     the same Music/Audiobooks chips as setup, re-tappable.
<slot>        whatever else is installed. Empty today, one entry once Plan 6 lands.
```

**The slot is a `Set<SettingsSection>` supplied by Hilt multibinding, and it exists for exactly one
reason:** Plan 6's `RendererDirect` toggle has to appear here, and `:feature:settings` **must not
depend on `:core:cast`**. Plan 6's own definition of done requires that dropping casting stays a
`git rm -r core/cast feature/castpicker` rather than that plus surgery elsewhere. The alternative
considered and rejected was passing a `List<@Composable () -> Unit>` down from `:app` — simpler, but
it makes `:app` responsible for ordering and for knowing which section belongs to which feature,
which is the thing `:app` is meant not to know. A section is a Composable and an `order`. That is
the whole contract, and if it ever grows a second member, that is the moment to ask whether a
preferences framework was wanted after all.

### The one genuinely dangerous operation

**Changing the server URL must throw the mirror away.** The mirror's rows are keyed on *that
server's* ids; pointing the app at a different Navidrome while keeping them shows the user albums
that do not exist, whose covers 404 and whose tracks fail to stream, with the sync watermark saying
everything is current. That is the silent-wrong-answer class, reached by a screen whose whole
purpose is to be helpful.

So `reconnect` is: `ping` the new details **first** (never store an unverified URL — a saved bad URL
is a bricked app with no way back), then, only if the base URL actually changed, run
`SyncEngine.resetForNewServer()` inside one transaction before storing the new credentials and
re-running `getMusicFolders`.

**Changing a *role*, by contrast, needs no mirror work at all**, and that is worth writing down
rather than rediscovering: mirror rows carry `libraryId`, and shuffle, browse and the role are three
separate joins over it. Re-tagging library 2 from Audiobooks to Music changes which rows the shuffle
scope selects and rewrites nothing. `LibraryDao.mergeFromServer` already refuses to clobber a chosen
role, so a later sync cannot undo the change either.

- [ ] **Step 1: Write the failing pure test**

`feature/settings/src/test/kotlin/app/muplay/settings/SettingsUiStateTest.kt` — same stance as
`LibraryUiStateTest`: every rule in a pure function, so the branches are gated by a BRANCH floor in
Tier 1 and the Composable takes a LINE floor on the device.

```kotlin
package app.muplay.settings

import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SubsonicCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SettingsUiStateTest {

  private val music = MusicLibrary(1, "Music", LibraryRole.MUSIC)
  private val books = MusicLibrary(2, "Audiobooks", LibraryRole.AUDIOBOOKS)
  private val credentials = SubsonicCredentials("https://music.example.com", "luc", "hunter2")

  @Test
  fun `with no credentials there is nothing to configure`() {
    // Reachable: the settings destination survives a `CredentialStore.clear()`. Rendering an empty
    // "Server:" row with a blank URL would look like a bug in the store.
    assertThat(settingsContent(null, emptyList(), null, isBusy = false))
      .isEqualTo(SettingsUiState.NotConfigured)
  }

  @Test
  fun `the url and username are shown, and they come from the credentials`() {
    // Two observations of each: a screen that hardcoded either passes neither.
    val first = settingsContent(credentials, listOf(music), null, false) as SettingsUiState.Content
    val second = settingsContent(
      SubsonicCredentials("http://10.0.0.5:4533", "ada", "x"), listOf(music), null, false,
    ) as SettingsUiState.Content

    assertThat(first.serverUrl).isEqualTo("https://music.example.com")
    assertThat(first.username).isEqualTo("luc")
    assertThat(second.serverUrl).isEqualTo("http://10.0.0.5:4533")
    assertThat(second.username).isEqualTo("ada")
  }

  @Test
  fun `the password is never part of the state`() {
    // Asserted on the whole rendered state, not on an absent property, so a password smuggled into
    // any field fails too. A `toString()` of a UiState reaches a crash report.
    assertThat(settingsContent(credentials, listOf(music), null, false).toString())
      .doesNotContain("hunter2")
  }

  @Test
  fun `every library is offered with its current role`() {
    val state = settingsContent(credentials, listOf(music, books), null, false) as SettingsUiState.Content

    assertThat(state.libraries.map { it.name }).containsExactly("Music", "Audiobooks")
    // The roles, mapped and asserted exactly -- an `allMatch` here would be vacuous on an empty
    // list, which is the rule Plan 2 Task 3's review rounds produced.
    assertThat(state.libraries.map { it.role })
      .containsExactly(LibraryRole.MUSIC, LibraryRole.AUDIOBOOKS)
  }

  @Test
  fun `a connection message and the busy flag are carried through independently`() {
    val busy = settingsContent(credentials, listOf(music), null, isBusy = true) as SettingsUiState.Content
    val failed = settingsContent(credentials, listOf(music), "Could not connect.", false) as SettingsUiState.Content

    assertThat(busy.isBusy).isTrue
    assertThat(busy.connectionMessage).isNull()
    assertThat(failed.isBusy).isFalse
    assertThat(failed.connectionMessage).isEqualTo("Could not connect.")
  }
}
```

Run: `./gradlew :feature:settings:test` — FAIL, the module does not exist.

- [ ] **Step 2: Add the module, the state and the ViewModel**

`settings.gradle.kts` — `include(":feature:settings")` at column 0. `feature/settings/build.gradle.kts`
takes `muplay.android.library`, `muplay.android.compose`, `muplay.android.hilt`, and depends on
`:core:model`, `:core:database`, `:core:designsystem` — **and not on `:core:cast`, `:core:media` or
any `:integrations:*` module.** That absence is the slot's whole justification; a dependency added
here is a dependency Plan 6's severability contract forbids.

`SettingsUiState.kt` carries the sealed state and the pure `settingsContent(...)`.

`SettingsViewModel.kt`:

```kotlin
/**
 * The settings surface. Two collaborators and no cleverness: [CredentialStore] for the connection,
 * [LibraryRepository] for the roles.
 *
 * `reconnect` is the only method here that can lose data, and it is written defensively for that
 * reason -- see [reconnect].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val credentialStore: CredentialStore,
  private val libraryRepository: LibraryRepository,
  private val sourceFactory: SubsonicSourceFactory,
  private val syncEngine: SyncEngine,
) : ViewModel() {

  private val connectionMessage = MutableStateFlow<String?>(null)
  private val busy = MutableStateFlow(false)

  val uiState: StateFlow<SettingsUiState> =
    combine(
      credentialStore.credentials,
      libraryRepository.libraries,
      connectionMessage,
      busy,
    ) { credentials, libraries, message, isBusy ->
      settingsContent(credentials, libraries, message, isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState.Loading)

  /**
   * Re-tags one library. **No mirror work is needed and none is done** -- mirror rows carry
   * `libraryId`, and the role is a separate column the shuffle scope joins against, so re-tagging
   * changes which rows are in scope and rewrites nothing. `LibraryDao.mergeFromServer` already
   * preserves a chosen role, so the next sync cannot undo this either.
   */
  fun setRole(musicFolderId: Int, role: LibraryRole) {
    viewModelScope.launch { libraryRepository.setRole(musicFolderId, role) }
  }

  /**
   * Points the app at a server, verifying **before** storing.
   *
   * Three orderings matter here and all three are the difference between a working app and a
   * bricked one:
   *
   * 1. `ping` first. Storing an unverified URL leaves the app pointed somewhere it cannot reach,
   *    from a screen it can no longer load the current values into -- with no way back short of
   *    clearing app data, which destroys every audiobook position.
   * 2. If the base URL changed, **throw the mirror away before storing the new credentials.** The
   *    mirror's ids belong to the old server; kept, they render albums that do not exist, whose
   *    covers 404 and whose tracks fail to stream, while the watermark reports everything current.
   * 3. `getMusicFolders` last, so the role-tagging list below is the *new* server's libraries.
   *    They arrive `UNASSIGNED`, which is correct: nobody has said what they are for.
   */
  fun reconnect(serverUrl: String, username: String, password: String) {
    viewModelScope.launch {
      busy.value = true
      connectionMessage.value = null
      val candidate = SubsonicCredentials(serverUrl, username, password)
      val result = runCatching { sourceFactory.create(candidate).ping() }
      connectionMessage.value = when {
        result.isSuccess -> {
          if (credentialStore.load()?.baseUrl != candidate.baseUrl) syncEngine.resetForNewServer()
          credentialStore.save(candidate)
          libraryRepository.refreshFromServer()
          CONNECTED_MESSAGE
        }
        // The three failures a user can actually act on, kept apart. "Something went wrong" is
        // what makes a settings screen useless.
        result.exceptionOrNull() is SubsonicErrorException -> "The server rejected those credentials."
        result.exceptionOrNull() is SubsonicHttpException -> "The server answered, but with an error."
        else -> "Could not reach that address."
      }
      busy.value = false
    }
  }
```

- [ ] **Step 3: Write the screen and the slot**

`SettingsSection.kt` — twenty lines, and the file says why it is not more:

```kotlin
package app.muplay.settings

import androidx.compose.runtime.Composable

/**
 * One extra block on the settings screen, contributed by a module `:feature:settings` does not
 * depend on.
 *
 * **This is a slot, not a preferences framework.** There is no `Preference` type, no key registry,
 * no schema and no categories — a section is a Composable and a sort key. It exists for exactly one
 * reason: the cast plan's renderer-direct toggle has to appear on this screen, and this module must
 * not depend on `:core:cast`, because dropping casting has to stay
 * `git rm -r core/cast feature/castpicker` and nothing else.
 *
 * If this interface ever grows a second member, stop and ask whether a preferences framework was
 * what was wanted after all.
 */
interface SettingsSection {
  /** Ascending. Sections this module writes occupy 0 and 100; contributions sort after them. */
  val order: Int

  @Composable
  fun Content()
}
```

`di/SettingsModule.kt` declares `@Multibinds abstract fun settingsSections(): Set<SettingsSection>`,
so the set resolves to **empty** when nothing contributes one. Without that declaration Hilt fails
to compile a graph with no contributors, which would make `:feature:settings` unbuildable until
Plan 6 lands — the exact coupling the slot exists to avoid.

`SettingsScreen.kt` renders `Server`, `Libraries`, then `sections.sortedBy { it.order }.forEach { it.Content() }`.
The library chips are the **same control as setup's**, deliberately: a user who mis-tapped once
should meet the thing they mis-tapped, not a different-looking one. Labels: `SETTINGS_TITLE =
"Settings"`, `SERVER_HEADING = "Server"`, `LIBRARIES_HEADING = "What each library is for"`,
`RECONNECT_LABEL = "Connect"`, `CONNECTED_MESSAGE = "Connected."`.

- [ ] **Step 4: The reset, and the test that it is not vacuous**

`SyncEngine.resetForNewServer()` — on `SyncEngine` rather than `BrowseRepository` because the
watermark and the mirror have to go **in one transaction**, and `SyncEngine` is the one class that
already holds both:

```kotlin
  /**
   * Forgets everything mirrored from the previous server: artists, albums, songs, libraries and the
   * sync watermark, in one transaction.
   *
   * `media_progress` is **deliberately not touched.** Its ids are the server's, so most rows will
   * be meaningless against a new server — but a user re-pointing at the same library behind a new
   * hostname keeps every audiobook position, and a stale progress row costs nothing: it is looked
   * up by media id and simply never matches. Deleting listening history to tidy up a cache is not a
   * trade this app makes.
   */
  suspend fun resetForNewServer()
```

`SyncEngineResetTest` (instrumented, real Room): seed mirror rows, a library with a chosen role, a
watermark **and** a `media_progress` row; call it; assert the first four are gone **and the progress
row is still there, with its position**. That last assertion is the one that matters — a reset that
quietly took the positions with it would pass every other test in this task.

- [ ] **Step 5: The auth seam an API key drops into**

Spec §4: **"Design the auth layer so an API key drops in later."** No plan carries that sentence.
`SubsonicAuth` is an `object` with a fixed `authParams()` returning `u`/`t`/`s`/`v`/`c`/`f`, and
today the cost of that is zero — Navidrome 0.63.2 does not implement `apiKeyAuthentication` and
Plan 1 Task 5 pins that fact. The cost the day it ships is every call site, because there is no seam
to change instead of them.

It is done here because this is the screen an API key would be entered on, and because it is a
twenty-line refactor with no behaviour change:

```kotlin
/**
 * How this client proves who it is.
 *
 * Spec §4 asks for an auth layer an API key can drop into. Today there is exactly one member and
 * Navidrome implements exactly one scheme — `apiKeyAuthentication` is **not** implemented on
 * 0.63.2 despite third-party claims (Plan 1 Task 5 pins it). So this is not an abstraction over
 * two things; it is the *shape* that makes adding the second one a new `data object` and one
 * binding, rather than an edit to every call site that builds a query string.
 *
 * Adding a member is the whole change. If adding one turns out to require editing a call site, the
 * seam is in the wrong place and this comment is the evidence that somebody meant it to be here.
 */
sealed interface SubsonicAuthMethod {
  fun params(credentials: SubsonicCredentials, salt: String): Map<String, String>

  /** `t = md5(password + salt)`, lowercase hex, **fresh salt per request**. */
  data object Token : SubsonicAuthMethod { /* Plan 1's existing body, moved unchanged */ }
}
```

`SubsonicAuth.authParams` delegates to a `method: SubsonicAuthMethod = SubsonicAuthMethod.Token`.
`SubsonicAuthMethodTest` asserts the delegation is real: a second, test-only member returning a
sentinel map changes what `SubsonicClient` puts on the wire, **without any edit to `SubsonicClient`**.
That assertion is the seam; without it this is a rename.

> **Every existing auth assertion stays exactly as it is** — Plan 1's byte-by-byte hex test, the
> leading-zero case, the fresh-salt-per-request assertions in Tasks 3 and 7, and Plan 3's
> `the token on this url is a real md5 of the password and the salt beside it`. If any of them needs
> editing, the refactor changed behaviour and is wrong.

- [ ] **Step 6: Navigation and the way in**

`SettingsRoute` (a `@Serializable data object NavKey`), an `entry<SettingsRoute> { SettingsScreen() }`
in `MuPlayApp`, and a `SETTINGS_LABEL = "Settings"` action on `LibraryScreen` that pushes it. Pushed,
not replaced, so system back returns to the library — which
`backFromAnAlbumReturnsToTheLibraryRatherThanLeavingTheApp` (Task 10) already proves works.

- [ ] **Step 7: The Tier 2 journey**

`app/src/androidTest/kotlin/app/muplay/SettingsJourneyTest.kt`. The headline test is **not** "the
screen renders" — it is the recovery this task exists for, end to end against the real two-library
Navidrome:

```kotlin
  /**
   * The mis-tag, and the way back from it. This is the whole task in one test.
   *
   * Tag Audiobooks as **Music** from the settings screen, shuffle the Audiobooks library, and the
   * book is there — proving the change reached the shuffle scope. Then tag it back and shuffle
   * again, and it is gone. Two directions, because a screen that always returned "Music" passes
   * the first half.
   */
  @Test
  fun retaggingALibraryFromSettingsChangesWhatShuffleDraws() { /* ... */ }

  /** The URL and username on screen are the ones the app is connected as, not placeholders. */
  @Test
  fun theServerSectionShowsTheConnectionInUse() { /* ... */ }

  /**
   * A bad address is refused and **nothing is stored**: the library still loads afterwards. This is
   * the bricking path, and it is the one worth a journey rather than a unit test, because "nothing
   * is stored" is only true if `ping` really ran before `save`.
   */
  @Test
  fun aServerThatCannotBeReachedIsRefusedAndChangesNothing() { /* ... */ }
```

- [ ] **Step 8: Prove each can fail**

1. Make `setRole` a no-op. Expect `retaggingALibraryFromSettingsChangesWhatShuffleDraws` to fail on
   its first half — and, restored, hardcode the role to `MUSIC` and expect it to fail on the second.
2. In `reconnect`, `save` before `ping`. Expect `aServerThatCannotBeReachedIsRefusedAndChangesNothing`
   to fail, and to leave the emulator's app unusable until the journey's own teardown repairs it —
   which is the point, and is why this mutation is run last.
3. Delete the `resetForNewServer()` call. Expect a mirror-staleness assertion to fail. Add one if
   the journey does not have it: reconnect to the same server under a different base URL spelling
   and assert the album list is rebuilt rather than kept.
4. In `resetForNewServer`, also delete `media_progress`. Expect `SyncEngineResetTest` to fail on the
   position assertion. **This mutation is the reason that test exists.**
5. Delete the `@Multibinds` declaration. Expect the Hilt graph to fail to compile with no
   contributors — proving the empty-set case is handled by declaration and not by luck.
6. Point `SubsonicAuth.authParams` back at its own body instead of the method. Expect
   `SubsonicAuthMethodTest`'s delegation assertion to fail while every other auth test stays green,
   which is exactly the shape of "this refactor is a rename".

- [ ] **Step 9: Floors and commit**

`:feature:settings` is a new module, so `ConventionTest`'s *every Gradle project has a coverage
floor* fails until it has an entry. BRANCH over `SettingsUiStateKt` and `SettingsViewModel`, LINE
over `SettingsScreenKt`, both measured from a real report, `requiresInstrumentedData` measured
rather than judged. `.github/workflows/e2e.yml` runs `:app`'s suite whole, so `SettingsJourneyTest`
needs no workflow change — confirm that rather than assuming it.

**Do not correct spec §9's module list in this task.** It is corrected in the same repair commit
that gave this task its ruling (`feature/search` consolidated into `:feature:library`,
`feature/settings` now owned here), and correcting it twice would leave one of the two edits
looking like a conflict.

```bash
./gradlew :feature:settings:test :core:network:test
./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
git add settings.gradle.kts feature/settings core app build.gradle.kts .github/workflows
git commit -m "feat(settings): change the server, and re-tag a library that was tagged wrong"
```

---

## Definition of done

1. All tasks' tests pass; **both tiers green**.
2. **Tier 2 carries this plan's journeys**: `BrowseJourneyTest` and `ScopedShuffleJourneyTest`
   in the emulator suite, plus `:core:database`'s instrumented Room suite, and each has been
   watched go red. `BrowseJourneyTest` includes the two assertions this plan added last: that the
   user has a **Refresh** control on screen and that it really re-syncs, and that **back from an
   album returns to the library** rather than leaving the app (spec §7's predictive-back
   requirement, which no plan previously named as a deliverable).
3. Coverage ≥ 90% on every module this plan touched — **branch** for non-UI code, **line** for
   `@Composable`-bearing files. Every floor measured from a real report, every
   `requiresInstrumentedData` flag measured rather than judged, and **every floor watched fail
   once**. No module absent from `coverageFloors` (`ConventionTest` enforces it), and no
   `COVERAGE:` warning left standing.
4. No mock framework anywhere in the dependency graph — now enforced twice, by
   `ConventionTest`'s declared-name scan **and** by `verifyNoMockFrameworks` resolving every test
   runtime classpath, the latter proven able to fail both ways (a planted Mockito, and an empty
   input set).
5. Every new external-API assumption is backed by a contract test against the vendored OpenAPI
   spec or a live test against the Navidrome container — including the three places where the
   real server and the vendored spec **disagree**, each pinned by a committed assertion naming
   the field rather than by a note in a report.
6. Every new network command has a **request** assertion, not only a response one:
   `getAlbumList2`, `getAlbum` (including that it must *not* send a scope), `search3`,
   `getRandomSongs`, `getScanStatus` and the cover-art URL. The mutation that Plan 1 proved
   nothing could detect — a scoping parameter silently dropped — now fails a test.
7. **Library-scoped shuffle is proven three ways**: a request assertion on the wire, a live
   fifty-attempt assertion against the real two-library Navidrome (with the audiobook library as
   the control), and an emulator journey through the real UI. The silent-widening trap that
   motivates the non-null `Int` is itself pinned by a live assertion.
8. Anything discovered to be wrong in the spec is corrected **in the spec** — §4's
   `musicFolderId` trap and §10's oracle claim, at minimum.
9. **A mis-tagged library is recoverable** (Task 11). `:feature:settings` exists, spec §9's module
   list is no longer describing something nobody built, and the Tier 2 journey proves the recovery
   in **both** directions — re-tag Audiobooks as Music and the book appears in that library's
   shuffle; tag it back and it is gone. Changing the server URL verifies with `ping` before storing
   anything and throws the mirror away when the base URL moved, **without touching
   `media_progress`** — that last is asserted, because a reset that quietly took the audiobook
   positions with it would pass every other test in the task.
10. **The auth layer has the seam spec §4 asks for.** `SubsonicAuthMethod` is a sealed interface
    with one member, and the assertion that makes it a seam rather than a rename is that a
    test-only second member changes what `SubsonicClient` puts on the wire with no edit to
    `SubsonicClient`. Every existing auth assertion is unchanged; if one needed editing, the
    refactor changed behaviour and is wrong.
11. **The oracle has a scheduled way of ageing visibly.** `.github/workflows/openapi-drift.yml`
   exists, is scheduled, never runs on a pull request, reports on the quiet path as well as the
   loud one, and has been **watched report drift once** against a deliberately altered vendored
   copy. Spec §10 has claimed this check since it was written; until this task nothing implemented
   it, which left `NavidromeSpecDeviationTest`'s two both-directions assertions waiting for a
   refresh nobody scheduled.
