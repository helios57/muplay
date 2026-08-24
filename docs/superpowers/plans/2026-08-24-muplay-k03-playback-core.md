# MuPlay Kotlin Plan 3 — Playback Core

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MuPlay plays music. A real Media3/ExoPlayer queue streaming from Navidrome over an
authenticated `format=raw` URL, hosted in a `MediaLibraryService` so playback survives
backgrounding, with a notification and lock-screen controls, audio focus and becoming-noisy
handling, gapless transitions proven at the PCM level, a media cache keyed on the track id alone,
and a Compose player UI — with an emulator journey that proves **audio advanced**, not that the
player was asked to play.

**Architecture:** A new `:core:media` module owns every Media3 type in the project: the
`ExoPlayer`, the cache-backed OkHttp data source, the `Song` → `MediaItem` mapping, the
`MuPlayer` `ForwardingPlayer` seam, the progress writer, and `MuPlaybackService`
(a `MediaLibraryService`). `:core:network` grows exactly one thing — `streamUrl`, an
authenticated `format=raw` URL. `:feature:player` owns the Compose UI and talks to the service
only through a `MediaController`, never through an `ExoPlayer`. Nothing in `:feature:*` or `:app`
imports `androidx.media3.exoplayer`.

**Tech Stack:** Kotlin 2.4.10, JDK 21, AGP 9.3.1, **KSP** (never KAPT), **Media3 1.11.0**
(`media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`, `media3-ui-compose`,
`media3-ui-compose-material3` — **not** `media3-test-utils`, which needs an Android runtime and
whose JVM path is the banned Robolectric; see Task 7), Room 2.8.4, Hilt 2.60.1, OkHttp 5.5.0,
Compose BOM 2026.08.00 + Material 3 1.4.0, Navigation 3 1.1.6, JUnit 5 (JVM) / JUnit 4 (device),
AssertJ, Turbine, JaCoCo 0.8.12.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Roadmap:** `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` — Plan 3, *"Plays music.
Background, notification, lock screen, gapless, cache."*, depends on Plan 2.

---

## Global Constraints

Copied verbatim from `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md`'s **Global
constraints** and the spec. Every task inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI. `.kts` build scripts.
- Licence **MIT**. No GPL code may be copied. (Sharp edge in this plan: **Voice** is GPL and is the
  origin of the `ForwardingPlayer` *idea* in Task 8. Read no Voice source; the idea travels, the
  code does not.)
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Play requires 36 from 2026-08-31.
- **KSP only. KAPT is dead** and KSP1 has been removed upstream.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow`, collected with `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer** unless logic is genuinely
  shared across features.
- Convention plugins in `build-logic/convention`. No copy-pasted build scripts.
- Subsonic client identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- Stream requests force **`format=raw` or `format=mp3`**. **Never Opus.**
- Media3 cache keys derive from the **track id alone** via `setCustomCacheKey`.
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

Additionally, from the spec, binding on this plan specifically:

- **Cleartext HTTP is debug-only** and must never reach the release manifest
  (`verifyReleaseManifest`, already wired into `check`).
- **No Robolectric**, no Roborazzi, no ktlint/detekt/spotless.
- Permissions: `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (spec §7).

### Definition of done, per plan

Copied verbatim from the roadmap's **Definition of done, per plan**:

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

## Task list

| # | Task | Deliverable a reviewer can accept or reject on its own |
|---|---|---|
| 1 | `streamUrl` — an authenticated `format=raw` URL, and the four traps on it | Navidrome serves this client seekable, length-declared, never-Opus audio |
| 2 | `:core:media` — the module, Media3 1.11.0, the OkHttp data source and the 429 policy | a real ExoPlayer fetches real audio and survives a transcoding refusal |
| 3 | The media cache, keyed on the track id alone | a replayed track costs **zero** further HTTP requests |
| 4 | `Song` → `MediaItem`, and the queue as a list of pointers | every mapped field proven against two inputs; the queue carries no position |
| 5 | `MuPlaybackService` — `MediaLibraryService`, foreground lifecycle, notification, permissions | the system holds a notification whose title follows the track |
| 6 | Audio focus, becoming-noisy, and the content-type switch | another app taking focus stops the clock; a noisy route pauses |
| 7 | Gapless, measured in PCM frames | under 10 ms of silence across a three-track queue, from a real decoder |
| 8 | `MuPlayer` — the `ForwardingPlayer` seam and the progress writer | all six overloads go through the policy; `media_progress` is written |
| 9 | `:feature:player` — the Compose player UI over a `MediaController` | a player and a mini player, with no `ExoPlayer` reachable from a feature |
| 10 | The gates — Tier 2 playback journeys, the coverage table, the spec corrections | audio advances on a real screen, in the background, and the spec is fixed |
| 11 | ReplayGain — parsed, mirrored, and applied as a gain stage | two identical sine tracks, one tagged −6 dB, render at half the amplitude |

> **Why 11 comes after the gates task, and why nothing was renumbered.** A spec-coverage audit
> (`.superpowers/sdd/2026-08-24-muplay-k02-library-browse/spec-coverage-audit.md`) found spec §4's
> ReplayGain sentence owned by **no** plan: this plan deferred it to Plan 4, and Plan 4 deferred it
> back. The ruling put it here, because this plan owns audio processing. It arrives as Task 11
> rather than being slotted in beside the tasks it belongs next to because **four plans this repair
> may not edit reference this plan's tasks by number** — Plan 4 builds on "Plan 3 Task 8", Plan 6 on
> "Plan 3 Task 9" and "Plan 3 Task 10". Renumbering would silently redirect every one of those. So
> the numbers stand, and Task 11 carries **its own** journey, coverage-floor and spec-correction
> steps rather than reaching back into Task 10's.

---

## What Plan 2 hands this plan — consume it, do not rebuild it

Plan 2 is **mid-flight** at the time this plan was written: its Tasks 1–3 are committed
(`:core:database` with `MediaProgressEntity`/`MediaProgressDao`, `KeystoreCipher`,
`CredentialStore`, `DataModule`; `:core:network`'s six browse commands, `SubsonicSource`,
`SubsonicSourceFactory`, `DefaultSubsonicSourceFactory`, and the recorded fixtures). Its Tasks
4–10 are specified in `docs/superpowers/plans/2026-08-24-muplay-k02-library-browse.md` but not yet
in the tree.

**Every symbol in the table below is Plan 2's to define. This plan consumes them and must not
redefine, rename or re-derive any of them.** Where a name is uncertain because Plan 2 had not
landed it yet, the row says so, and the task that consumes it says so again at the point of use.

| Symbol | Module | Status when this plan was written |
|---|---|---|
| `Song(id, libraryId, title, albumId, albumName, artistId, artistName, trackNumber, discNumber, durationSeconds, suffix, coverArtId)` | `:core:model` | **Committed.** Read the file; do not restate it. **Task 11 adds one field to it** (`replayGain: ReplayGain?`) and says why the addition is this plan's rather than Plan 2's. |
| `Album`, `AlbumWithSongs`, `Artist`, `MusicLibrary`, `LibraryRole`, `SearchResults`, `ScanStatus`, `AlbumListType`, `SubsonicCredentials` | `:core:model` | **Committed.** |
| `SubsonicSource` (`ping`, `getMusicFolders`, `getScanStatus`, `getAlbumList2`, `getAlbum`, `search3`, `getRandomSongs`, `coverArtUrl`) | `:core:network` | **Committed.** Task 1 adds exactly one method to it. |
| `SubsonicSourceFactory`, `DefaultSubsonicSourceFactory`, `SubsonicClient`, `SubsonicAuth`, `SubsonicErrorException`, `SubsonicHttpException`, `SubsonicMalformedResponseException` | `:core:network` | **Committed.** |
| `CredentialStore` (`save`, `load`, `clear`, `credentials`) | `:core:database` | **Committed.** |
| `MediaProgressEntity`, `MediaProgressDao` (`upsert`, `find`, `findAll`, `recentlyPlayed`) | `:core:database` | **Committed, and written by nothing.** Task 8 is the first writer. |
| `SubsonicSourceProvider.current(): SubsonicSource`, `NotConfiguredException` | `:core:database` | **Plan 2 Task 4.** Name taken from that plan's Interfaces block. If it landed under a different name, use the real one and say so in the task report — do not add a second provider. |
| `LibraryRepository` (`libraries`, `refreshFromServer`, `setRole`, `idsWithRole(role)`, `allIds`, `hasUnassignedLibraries`) | `:core:database` | **Plan 2 Task 4.** |
| `BrowseRepository` (`artists`, `albums`, `albumsByArtist`, `songs(albumId)`, `album(albumId)`, `search`, `coverArtUrl`) | `:core:database` | **Plan 2 Task 5.** |
| `ShuffleRepository.shuffle(libraryId, requestedSize): ShuffleResult`, `DEFAULT_SHUFFLE_SIZE` | `:core:database` | **Plan 2 Task 7.** |
| `SyncEngine.syncIfStale(): SyncState` | `:core:database` | **Plan 2 Task 6.** Plan 3 never calls it. |
| `LibraryScreen(onAlbumClick, modifier, viewModel)`, `AlbumScreen(modifier, viewModel)`, `LibraryUiState`, `AlbumUiState` | `:feature:library` | **Plan 2 Task 9.** Task 9 of *this* plan adds play callbacks to both. |
| `StartDestination`, `StartDestinationViewModel`, `MuPlayApp`, `LibraryRoute`, `AlbumRoute` | `:app` | **Plan 2 Task 10.** |
| Room schema version **4** (`media_progress`, `libraries`, `artists`, `albums`, `songs`, `sync_watermark`) | `:core:database` | **Plan 2 Task 6 leaves it at 4.** |

**Tasks 1–10 add no table and no column, so through Task 10 the schema version does not move.**
`media_progress` has existed since Plan 2 Task 1 and Task 8 below is the first code in the project
to write a row into it. If you find yourself writing a Room migration anywhere in Tasks 1–10, stop
— you have added a column that belongs in Plan 4.

**Task 11 is the single exception, and it is a deliberate one.** ReplayGain is a property of the
*file*, reported by the server on every browse response, and the player needs it **before** the
track has ever been played — a shuffled library is exactly the case where every track is a first
play. So it has to live on the library mirror: `songs` gains three nullable columns and the schema
moves **4 → 5**. Like every schema bump in Plan 2 it writes **no `Migration`** — `provideDatabase`
still carries the pre-release `fallbackToDestructiveMigration(dropAllTables = true)` escape hatch,
nothing has shipped, and the mirror is a cache of the server that costs one sync to rebuild. Task 11
restates that at the point it bumps the version, because "no schema change in this plan" was true
when this plan was written and is the kind of sentence that gets quoted at someone doing the right
thing.

### Hard facts, re-verified while this plan was written

- **Every Media3 1.11.0 artifact this plan names exists.** Resolved against
  `https://dl.google.com/dl/android/maven2/androidx/media3/<artifact>/maven-metadata.xml`:
  `media3-common`, `media3-exoplayer`, `media3-session`, `media3-datasource`,
  `media3-datasource-okhttp`, `media3-ui`, `media3-ui-compose`, `media3-ui-compose-material3`,
  `media3-test-utils` and `media3-inspector` all publish `1.11.0` as their `<latest>`. The spec's
  stack table is correct, including the separate `-compose-material3` artifact, which was the one
  coordinate worth doubting. `media3-inspector` is **Plan 4's** (chapters); do not add it here.
- **The seeded corpus is three 5.000 s mono LAME MP3s and one 15.000 s AAC M4B.**
  `ci/seed-fixtures.sh` builds them with `-bitexact` so their MD5s are reproducible
  (`ci/fixtures.md5`, verified by a step in both workflows). Track 1/2/3 are sine waves at
  385/440/495 Hz, 44100 Hz, 64 kbps, mono. Those exact numbers are what Task 7's gapless
  measurement is arithmetic over — a re-encode moves them, which is why the checksum step exists.
- **Library 1 is `Music`, library 2 is `Audiobooks`**, wired by `ci/configure-libraries.sh`.
  Library 1 is path-pinned and undeletable.
- **Navidrome returns HTTP 200 for API errors**, with the failure in the body. That is *not* true
  of `/rest/stream`, which is a binary endpoint: an auth failure there returns a body, and the
  status code question is exactly what Task 1's live test settles rather than assumes.
- **`ci/mutation-probes.sh` is a committed regression list of mutation probes**, not a gate. Every
  task below that fixes a value one constant could satisfy adds a probe to it. Read its header
  before adding one — it is explicit about what a green run does and does not mean.
- **`ConventionTest` will fail a new module that has no `coverageFloors` entry**, and its
  `no module configures android or kotlin blocks directly` rule allows only `namespace` in a
  module's own `android { }` block (plus `applicationId`/`versionCode`/`versionName` inside
  `defaultConfig`). Everything else goes in a convention plugin.
- **`excludeByteBuddyFromInstrumentedTests`** (build-logic) already strips Byte Buddy from every
  `androidTest*` configuration project-wide, so a new module can put AssertJ on a device without
  rediscovering the `mergeExtDexDebugAndroidTest` failure.
- **`configureKotlinAndroid`** already sets `testInstrumentationRunner` and
  `enableAndroidTestCoverage` for every Android module, and `Jacoco.kt`'s `mergedExecutionData`
  already globs every project's `build/outputs/code_coverage/**/*.ec`. A new Android module with
  an `androidTest` source set needs no build-logic change to be measured.

### The defect class this plan is written against

Four review rounds on Plan 2 Task 3 found the same failure over and over: **assertions that
execute but do not discriminate.** A mapped field replaced by a hardcoded constant left the whole
suite green at 100% branch coverage. The rules that came out of it bind every test in this plan:

1. **The unit of the question is the field, not the type or the endpoint.** Every field this
   plan's code assigns needs an assertion that fails when that field becomes a constant.
2. **A value observed at exactly one value is not tested.** Prove an argument's effect by holding
   everything else constant and varying only that argument, and assert both observations.
3. **`allMatch`/`anyMatch` over a collection is vacuously true on an empty collection.** Prefer
   mapping the field and asserting the exact list.
4. **A gate that reports the absence of a problem must be provably incapable of staying quiet
   when it did not run.**

**Playback has its own version of rule 1, and it is the one to watch here: a test that asserts the
player was *asked* to play is not a test that audio *advanced*.** `player.play()` returning,
`playWhenReady == true`, `STATE_READY`, a `MediaSession` reporting `isPlaying` — every one of
those is satisfied by a player that renders silence, by a URL that 404s into a swallowed error, and
by a decoder that never produced a sample. The discriminating observations, in increasing order of
strength, are:

- `player.currentPosition` **strictly increasing** across two reads separated by real time;
- playback reaching `STATE_ENDED` after approximately the media's real duration, not immediately;
- **PCM frames actually delivered to the audio sink**, counted — which is what Task 7 measures and
  what makes the gapless claim a measurement rather than a hope.

Where a task's test could be satisfied by "was asked to play", the task says so out loud and says
which of the three it uses instead.

### Scope discipline

Plan 3 is **playback core**. Explicitly **not** in this plan:

- **Per-book resume, M4B chapters, per-item speed, silence skipping, gain, sleep timer, smart
  rewind** — Plan 4. Task 8 builds the `ForwardingPlayer` seam that makes resume *impossible to
  bypass* and the writer that gives it data, and deliberately installs a resume policy that
  resumes **nothing**. Spec §3 says music restarts from 0 and progress is still recorded; that is
  exactly what Task 8 ships. Plan 4 swaps the policy, and touches nothing else.
- **Android Auto and Wear OS** — Plan 5. Task 5 uses `MediaLibraryService` because spec §7 names
  it and it is a strict superset of `MediaSessionService`, so Plan 5 does not have to change a
  base class under a live service. Its `onGetLibraryRoot`/`onGetChildren` return
  `SessionError.ERROR_NOT_SUPPORTED` here, and Task 5 says why that is a deferral and not a
  silent hole.
- **Sonos and DLNA casting** — Plan 6. No `core/cast`, no proxy, no SSDP.
- **Bindery and Lidarr** — Plan 7.
- **Library browsing, search, and library-scoped shuffle** — **Plan 2's** deliverables. This plan
  *consumes* `BrowseRepository` and `ShuffleRepository`; it does not rebuild either. Task 9 adds
  play callbacks to Plan 2's screens and nothing else.
- **Offline downloads** — deferred by spec §9. No `DownloadService`, no `JobScheduler`.
- **`media3-inspector`** — chapters are Plan 4's. Adding the artifact here would be an unused
  dependency, which the constraints call out by name.
- ~~**ReplayGain.**~~ **Corrected — ReplayGain is this plan's, and it is Task 11.** This paragraph
  used to defer it to Plan 4 on the grounds that spec §5 groups `gainDb` with per-item speed and
  silence skipping. Plan 4 read the same sentence and deferred it back — its Scope discipline says
  *"it is nobody's task yet"* — so nothing parsed the value, nothing stored it, `Song` carried no
  field for it, and nothing applied it. Two plans each pointing at the other is how a requirement
  ships as a defect. **Library-scoped shuffle is this application's headline feature, and loudness
  whiplash across a shuffled library degrades exactly that**, while the tags that fix it are already
  sitting in the user's own files and already on the wire in every browse response. Applying a gain
  is audio processing, and audio processing is this plan. Task 11 owns it end to end: the model
  field, the OpenSubsonic `replayGain` object parsed against the vendored oracle, the mirror columns
  the shuffle path reads before a track has ever been played, and the gain stage itself. Task 8's
  responsibility toward `gainDb` changes accordingly — it **stamps** the column from the item now
  rather than merely preserving it; see Task 8's own trap section.
- **`savePlayQueue` / `createBookmark` / any server-side progress sync.** Spec §4 and §11 rule this
  out outright, and spec §4 records the specific hazards (`createBookmark.position` in
  milliseconds against `bookmarkPosition` in seconds; Navidrome's `savePlayQueue` mapping a track
  id to an index by first match and silently falling back to 0). Nothing in this plan sends a
  position to a server.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | **modify** — include `:core:media`, `:feature:player` |
| `gradle/libs.versions.toml` | **modify** — five Media3 artifacts at the existing `media3 = "1.11.0"` ref, `androidx-test-rules`, and Coil's aliases if Plan 2 has not added them |
| `build.gradle.kts` | **modify** — coverage floors for `:core:media` and `:feature:player` |
| `core/model/src/main/kotlin/app/muplay/model/StreamFormat.kt` | **new** — `RAW`/`MP3`, the two formats this client is allowed to ask for. Opus is unrepresentable. |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt` | **modify** — one new method, `streamUrl` |
| `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt` | **modify** — `streamUrl` implementation |
| `core/network/src/test/kotlin/app/muplay/network/StreamUrlTest.kt` | **new** — the request contract for `/rest/stream`, every parameter varied |
| `core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt` | **modify** — Range/206/416, `Content-Length`, live-transcode `Accept-Ranges: none`, auth-on-the-URL |
| `core/media/build.gradle.kts` | **new** — Media3, Hilt, OkHttp |
| `core/media/src/main/kotlin/app/muplay/media/MuPlayDataSourceFactory.kt` | **new** — OkHttp data source, 429 retry policy, cache key factory |
| `core/media/src/main/kotlin/app/muplay/media/MediaCache.kt` | **new** — the one `SimpleCache` for the process |
| `core/media/src/main/kotlin/app/muplay/media/TrackIdCacheKeyFactory.kt` | **new** — the key is the track id, and a missing one is loud |
| `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt` | **new** — `Song` → `MediaItem`, every field |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackQueue.kt` | **new** — the queue as a list of pointers |
| `core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt` | **new** — the only entry point that turns songs into a live queue |
| `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` | **new** — `MediaLibraryService`, foreground lifecycle |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackNotification.kt` | **new** — channel id, notification id, the `MediaNotification.Provider` decision |
| `core/media/src/main/kotlin/app/muplay/media/AudioAttributesForRole.kt` | **new** — `CONTENT_TYPE_SPEECH` vs `CONTENT_TYPE_MUSIC` |
| `core/media/src/main/kotlin/app/muplay/media/MuPlayer.kt` | **new** — the `ForwardingPlayer` seam, all six overloads |
| `core/media/src/main/kotlin/app/muplay/media/ResumePolicy.kt` | **new** — sealed; Plan 3 ships `NeverResume`, Plan 4 swaps it |
| `core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt` | **new** — the seven persistence points plus the ticker |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackConnection.kt` | **new** — `MediaController` acquisition, `StateFlow<PlaybackState>` |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt` | **new** — the immutable state the UI collects |
| `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` | **new** — the Hilt module for cache, data source, player, queue |
| `core/media/src/main/AndroidManifest.xml` | **new** — the service declaration and its foreground-service type |
| `feature/player/build.gradle.kts` | **new** |
| `feature/player/src/main/kotlin/app/muplay/player/PlayerUiState.kt` | **new** |
| `feature/player/src/main/kotlin/app/muplay/player/PlayerViewModel.kt` | **new** |
| `feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt` | **new** — the full-screen player |
| `feature/player/src/main/kotlin/app/muplay/player/MiniPlayer.kt` | **new** — the bar above the library |
| `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt` | **modify** — a shuffle result and a song row become playable |
| `feature/library/src/main/kotlin/app/muplay/library/AlbumScreen.kt` | **modify** — a track row becomes playable |
| `app/src/main/AndroidManifest.xml` | **modify** — `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` | **modify** — the player destination and the mini player |
| `app/src/main/kotlin/app/muplay/ui/navigation/PlayerRoute.kt` | **new** |
| `app/src/androidTest/kotlin/app/muplay/PlaybackJourneyTest.kt` | **new** — Tier 2: audio advances, notification, backgrounding |
| `.github/workflows/e2e.yml` | **modify** — `:core:media:connectedDebugAndroidTest`, `pm grant` for notifications |
| `ci/mutation-probes.sh` | **modify** — the probes this plan's fields earn |
| `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` | **modify** — §10's Tier 1/Tier 2 tables, and the corrections Tasks 10 and 11 list |
| `core/model/src/main/kotlin/app/muplay/model/ReplayGain.kt` | **new** (Task 11) — the four numbers the server reports, and nothing else |
| `core/model/src/main/kotlin/app/muplay/model/Song.kt` | **modify** (Task 11) — one nullable `replayGain` field |
| `core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt` | **modify** (Task 11) — the OpenSubsonic `replayGain` object on `Child` |
| `core/database/src/main/kotlin/app/muplay/database/entity/SongEntity.kt` | **modify** (Task 11) — three nullable columns, schema 4 → 5 |
| `core/media/src/main/kotlin/app/muplay/media/ReplayGainPolicy.kt` | **new** (Task 11) — dB to a linear multiplier, clamped by peak. Pure, no Android type |
| `core/media/src/main/kotlin/app/muplay/media/GainAudioProcessor.kt` | **new** (Task 11) — the gain stage in the audio pipeline |
| `core/media/src/main/kotlin/app/muplay/media/ReplayGainController.kt` | **new** (Task 11) — sets the stage's gain from the current item |
| `ci/seed-fixtures.sh`, `ci/fixtures.md5`, `ci/configure-libraries.sh` | **modify** (Task 11) — one ReplayGain-tagged track, and the scan count that waits for it |
| `app/src/androidTest/kotlin/app/muplay/ReplayGainJourneyTest.kt` | **new** (Task 11) — the measurement, on a real decoder |

---

## Task 1: `streamUrl` — an authenticated `format=raw` URL, and the four traps on it

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/StreamFormat.kt`
- Create: `core/model/src/test/kotlin/app/muplay/model/StreamFormatTest.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt`
- Create: `core/network/src/test/kotlin/app/muplay/network/StreamUrlTest.kt`
- Modify: `core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt`
- Modify: `build.gradle.kts` (`:core:model`'s `includes` list gains `StreamFormat*`)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `app.muplay.model.SubsonicCredentials`, `app.muplay.network.SubsonicAuth.authParams`
  and `.token`, `SubsonicAuth.CLIENT_NAME`, `SubsonicAuth.PROTOCOL_VERSION`,
  `SubsonicSource`, `SubsonicClient` (its private `normalizeBaseUrl` in the companion),
  `app.muplay.model.Song.suffix`.
- Produces:
  - `sealed interface StreamFormat` with `val wireValue: String`; members
    `data object Raw` (`wireValue = "raw"`) and
    `data class Mp3(val maxBitRateKbps: Int)` (`wireValue = "mp3"`, `init` requires
    `maxBitRateKbps in 1..320`)
  - `StreamFormat.Companion.forSuffix(suffix: String?, transcodeBitRateKbps: Int): StreamFormat`
  - `StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS = 192`
  - `SubsonicSource.streamUrl(songId: String, format: StreamFormat): String` — **not** `suspend`
  - `SubsonicClient.streamUrl(songId, format)` implementation

### Why a stream URL is a different problem from every other request in this client

Every other command in `SubsonicClient` is issued *by* `SubsonicClient`, through Retrofit, and
comes back as JSON. This one is not issued here at all: it is handed to ExoPlayer, which fetches
it with its own OkHttp stack, with no interceptor of ours in the path. So **every credential has
to be on the URL**, and there is no second chance to add one.

That single fact creates four traps, and this task closes all four.

**Trap 1 — the format.** `format=raw` disables transcoding. Spec §4, verified against a real
container: raw responses honour RFC 7233 Range (206/416, clamping, byte-exact tail seek) and
always carry an accurate `Content-Length`, never chunked. **A live transcode returns
`Accept-Ranges: none` with no `Content-Length`, which means no seek at all.** Preferring raw is
therefore not a bandwidth preference, it is what makes the seek bar work. Step 4 pins both halves
against the real server, because if Navidrome's behaviour ever flipped, the symptom would be a
seek bar that silently does nothing.

**Trap 2 — Opus.** Spec §4: *"Never Opus. Sonos cannot decode it and Navidrome mislabels it
`audio/ogg`."* With `format=raw`, the format on the wire is whatever the file is — so "never
Opus" is not a thing the URL builder can achieve by *omission*. It has to be a decision, made
from the one signal available (`Song.suffix`), and it has to be made here rather than at each
call site. `StreamFormat.forSuffix` is that decision.

**Trap 3 — `estimateContentLength`.** The spec's own `/rest/stream` parameter list offers
`estimateContentLength=true`, which makes a transcoded response carry a `Content-Length` header.
It is an **estimate**. ExoPlayer would trust it, compute seek offsets against it, and land in the
wrong place — a silent-wrong-answer, which is the worst failure class this project recognises.
This client never sends it, and `StreamUrlTest` asserts its absence so that nobody adds it later
as an apparent improvement.

**Trap 4 — the salt.** `authParams()` generates a **fresh salt per call**, so two `streamUrl`
calls for the same song return different strings. Anything that keys a cache on the URL therefore
never hits that cache. Coil already hit this in Plan 2 (`CoverArt.kt`); Media3 hits it harder,
because a media cache that never hits re-downloads whole tracks. Task 3 is where
`setCustomCacheKey` closes it; Step 2's `two urls for the same song carry different salts` is the
committed assertion that makes the reason visible from here.

### What this task deliberately does not add

**`timeOffset`.** Spec §4 says transcoded seek uses `timeOffset` (the `transcodeOffset`
extension) and means re-issuing the URI. That is true and it stays true — but nothing in this plan
streams a transcode it then seeks: Trap 2's Opus path is the only transcode Plan 3 can produce,
and an Opus library is not in the CI corpus. A parameter with no caller, negotiated against a
capability with no consumer, is exactly the speculative work the constraints rule out. The plan
that streams a transcode and seeks it adds `timeOffset` and the `transcodeOffset` capability gate
together, at the point both are used.

- [ ] **Step 1: Write the failing format-policy test**

`core/model/src/test/kotlin/app/muplay/model/StreamFormatTest.kt`:

```kotlin
package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class StreamFormatTest {

  @Test
  fun `raw is the wire value raw and carries no bitrate`() {
    assertThat(StreamFormat.Raw.wireValue).isEqualTo("raw")
  }

  @Test
  fun `mp3 is the wire value mp3 and carries the bitrate it was given`() {
    // Two observations of the same field, so a constant cannot satisfy both.
    assertThat(StreamFormat.Mp3(96).maxBitRateKbps).isEqualTo(96)
    assertThat(StreamFormat.Mp3(320).maxBitRateKbps).isEqualTo(320)
    assertThat(StreamFormat.Mp3(96).wireValue).isEqualTo("mp3")
  }

  @Test
  fun `a bitrate outside the mp3 range is rejected at construction`() {
    assertThatIllegalArgumentException().isThrownBy { StreamFormat.Mp3(0) }
      .withMessageContaining("maxBitRateKbps")
    assertThatIllegalArgumentException().isThrownBy { StreamFormat.Mp3(321) }
      .withMessageContaining("maxBitRateKbps")
  }

  /**
   * Spec section 4: **never Opus**. With `format=raw` the bytes on the wire are whatever the file
   * is, so "never Opus" cannot be achieved by leaving a parameter off — it has to be an explicit
   * decision, and this is it.
   */
  @Test
  fun `an opus source is transcoded rather than streamed raw`() {
    assertThat(StreamFormat.forSuffix("opus", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  /**
   * `ogg` too, and this is the deliberate over-reach: an Ogg container may hold Vorbis (fine) or
   * Opus (forbidden), and the suffix cannot tell them apart. Transcoding an Ogg-Vorbis file that
   * did not need it costs bandwidth on a library almost nobody has; letting an Opus stream through
   * breaks Sonos in Plan 6 and hands ExoPlayer a stream Navidrome has mislabelled `audio/ogg`.
   * The trade is made here, on purpose, rather than discovered later.
   */
  @Test
  fun `an ogg source is transcoded because the suffix cannot rule out opus`() {
    assertThat(StreamFormat.forSuffix("ogg", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  @Test
  fun `the suffix is matched case-insensitively`() {
    // Navidrome sends lower case today. A mirror row is a String and nothing enforces that.
    assertThat(StreamFormat.forSuffix("OPUS", 192)).isEqualTo(StreamFormat.Mp3(192))
    assertThat(StreamFormat.forSuffix("Ogg", 192)).isEqualTo(StreamFormat.Mp3(192))
  }

  @Test
  fun `the transcode bitrate is the one the caller passed`() {
    // The argument's effect, proven by varying only it. Without this, `forSuffix` returning a
    // hardcoded Mp3(192) passes every other test in this class.
    assertThat(StreamFormat.forSuffix("opus", 64)).isEqualTo(StreamFormat.Mp3(64))
    assertThat(StreamFormat.forSuffix("opus", 256)).isEqualTo(StreamFormat.Mp3(256))
  }

  @Test
  fun `every other suffix streams raw`() {
    // The exact mapped list, not `allMatch`: `allMatch` over an empty list is vacuously true, and
    // a `forSuffix` that returned Raw for everything would also pass an `allMatch` written the
    // obvious way. Pairing this with the two transcode cases above is what makes both real.
    val suffixes = listOf("mp3", "flac", "m4a", "m4b", "aac", "wav", "wma", "MP3", "", null)

    assertThat(suffixes.map { StreamFormat.forSuffix(it, 192) })
      .containsExactly(*Array(suffixes.size) { StreamFormat.Raw })
  }

  @Test
  fun `the default transcode bitrate is a real number this project chose`() {
    assertThat(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS).isEqualTo(192)
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:model:test --tests '*StreamFormatTest*'`
Expected: FAIL — `Unresolved reference: StreamFormat`.

- [ ] **Step 3: Implement `StreamFormat`**

`core/model/src/main/kotlin/app/muplay/model/StreamFormat.kt`:

```kotlin
package app.muplay.model

/**
 * The format this client is allowed to ask a Subsonic server for on `/rest/stream`.
 *
 * A sealed interface with exactly two members, deliberately. The global constraints say *"Stream
 * requests force `format=raw` or `format=mp3`. **Never Opus.**"*, and the way to enforce a rule
 * like that is to make the forbidden value unrepresentable rather than to check for it — the same
 * structural argument that makes `musicFolderId` a non-null `Int` everywhere in this codebase.
 * There is no `StreamFormat("opus")` to write.
 *
 * [Mp3] carries its bitrate cap because that cap is the only reason to prefer a transcode over
 * [Raw] at all; [Raw] has no bitrate property because `format=raw` disables transcoding outright
 * and a bitrate alongside it would be a parameter the server ignores. "Raw at 128 kbps" is not a
 * request anyone can make.
 */
sealed interface StreamFormat {

  /** The value sent as the `format` query parameter. */
  val wireValue: String

  /**
   * No transcoding: the server sends the file's own bytes.
   *
   * The strongly preferred choice, and not for bandwidth reasons. Verified against a real
   * `deluan/navidrome:0.63.2`: a raw response honours RFC 7233 `Range` (206/416, clamping,
   * byte-exact tail seek) and always carries an accurate `Content-Length`, never chunked, while a
   * **live transcode returns `Accept-Ranges: none` with no `Content-Length` at all** — which means
   * the seek bar cannot work. See `LiveNavidromeTest`, where both halves are pinned.
   */
  data object Raw : StreamFormat {
    override val wireValue: String = "raw"
  }

  /**
   * A server-side transcode to MP3, capped at [maxBitRateKbps] kilobits per second.
   *
   * Reached only through [forSuffix], and only for a source whose container could hold Opus.
   */
  data class Mp3(val maxBitRateKbps: Int) : StreamFormat {

    init {
      require(maxBitRateKbps in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) {
        "maxBitRateKbps must be in $MIN_BITRATE_KBPS..$MAX_BITRATE_KBPS, was $maxBitRateKbps"
      }
    }

    override val wireValue: String = "mp3"
  }

  companion object {

    /** MPEG-1 Layer III's own bitrate range. Below 32 is Layer III at MPEG-2 rates; above 320 is not MP3. */
    const val MIN_BITRATE_KBPS: Int = 1
    const val MAX_BITRATE_KBPS: Int = 320

    /**
     * The bitrate a forced transcode uses. High enough that the transcode is not the reason a
     * listener notices anything, low enough to stay well inside what Navidrome's default
     * transcoding profile will produce.
     */
    const val DEFAULT_TRANSCODE_BITRATE_KBPS: Int = 192

    /**
     * The formats whose bytes must never reach this client, keyed by the file suffix the mirror
     * carries.
     *
     * `opus` is the rule spec section 4 states outright. `ogg` is here because the suffix cannot
     * distinguish Ogg-Vorbis from Ogg-Opus, and Navidrome mislabels Opus as `audio/ogg` anyway —
     * so an `ogg` file that is really Opus would arrive looking exactly like one that is not.
     * Transcoding both is a small, visible cost; letting one through is a silent one.
     */
    private val TRANSCODE_ONLY_SUFFIXES = setOf("opus", "ogg")

    /**
     * The format to request for a source file with this [suffix].
     *
     * [Raw] for everything the client may stream as-is, [Mp3] at [transcodeBitRateKbps] for the
     * containers that could hold Opus. A `null` or unrecognised suffix streams raw: the mirror
     * may not know a suffix, and Media3 identifies the container by sniffing it, so raw is both
     * the correct and the honest answer — inventing a transcode for an unknown file would degrade
     * every FLAC whose suffix a future server stopped reporting.
     */
    fun forSuffix(suffix: String?, transcodeBitRateKbps: Int): StreamFormat =
      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(transcodeBitRateKbps) else Raw
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:model:test --tests '*StreamFormatTest*'`
Expected: PASS, 9/9.

- [ ] **Step 5: Write the failing stream-URL contract test**

`core/network/src/test/kotlin/app/muplay/network/StreamUrlTest.kt`:

```kotlin
package app.muplay.network

import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The request contract for `/rest/stream`.
 *
 * This is a URL builder, not a request, so there is no server here and none is needed: the whole
 * subject is what the string contains. It is nonetheless a *request-contract* test in this
 * project's sense — Plan 1 proved by mutation that `authParams()` returning an empty map left
 * every response assertion in the codebase green, and this URL is handed to ExoPlayer with no
 * interceptor of ours in the path, so a missing parameter here has no second chance to be added.
 *
 * Every argument is observed at **two different values**. A field observed once is satisfied by a
 * constant, and this project has shipped that defect four times.
 */
class StreamUrlTest {

  private val credentials =
    SubsonicCredentials(baseUrl = "https://music.example.com", username = "luc", password = "hunter2")

  private fun url(
    songId: String = "track-1",
    format: StreamFormat = StreamFormat.Raw,
    baseUrl: String = credentials.baseUrl,
  ): HttpUrl =
    SubsonicClient(credentials.copy(baseUrl = baseUrl)).streamUrl(songId, format).toHttpUrl()

  @Test
  fun `the path is rest slash stream with no dot-view suffix`() {
    assertThat(url().encodedPath).isEqualTo("/rest/stream")
  }

  @Test
  fun `the song id is on the url and is the one the caller asked for`() {
    // Two disjoint observations. `id` hardcoded to either value passes one of these and fails the
    // other; hardcoded to anything else fails both.
    assertThat(url(songId = "track-1").queryParameter("id")).isEqualTo("track-1")
    assertThat(url(songId = "chapter-14").queryParameter("id")).isEqualTo("chapter-14")
  }

  @Test
  fun `a raw request sends format raw and no bitrate cap`() {
    val raw = url(format = StreamFormat.Raw)

    assertThat(raw.queryParameter("format")).isEqualTo("raw")
    // Not merely "absent by accident": `format=raw` disables transcoding, so a bitrate alongside
    // it is a parameter the server ignores and a reader misinterprets.
    assertThat(raw.queryParameter("maxBitRate")).isNull()
  }

  @Test
  fun `an mp3 request sends format mp3 and the bitrate cap it was given`() {
    assertThat(url(format = StreamFormat.Mp3(96)).queryParameter("format")).isEqualTo("mp3")
    // Two observations again: `maxBitRate` hardcoded to "192" passes nothing here.
    assertThat(url(format = StreamFormat.Mp3(96)).queryParameter("maxBitRate")).isEqualTo("96")
    assertThat(url(format = StreamFormat.Mp3(320)).queryParameter("maxBitRate")).isEqualTo("320")
  }

  /**
   * `estimateContentLength=true` makes a transcoded response carry a `Content-Length` header whose
   * value is a **guess**. ExoPlayer would trust it and compute seek offsets against it, landing in
   * the wrong place with nothing reported anywhere — the silent-wrong-answer class. This client
   * never sends it, and this assertion is what stops it being added later as an improvement.
   */
  @Test
  fun `estimateContentLength is never sent`() {
    assertThat(url(format = StreamFormat.Raw).queryParameter("estimateContentLength")).isNull()
    assertThat(url(format = StreamFormat.Mp3(192)).queryParameter("estimateContentLength")).isNull()
  }

  /**
   * `timeOffset` belongs to the plan that seeks a transcode; it has no caller in this one. Pinned
   * as absent rather than left unmentioned, so that adding it is a deliberate act with a test to
   * change, not a drive-by.
   */
  @Test
  fun `timeOffset is not sent by this plan`() {
    assertThat(url().queryParameter("timeOffset")).isNull()
  }

  @Test
  fun `the token on this url is a real md5 of the password and the salt beside it`() {
    val built = url()
    val salt = checkNotNull(built.queryParameter("s"))

    // Recomputed, not compared to a literal: a `t` hardcoded to any fixed string fails this, and
    // so does one computed from the wrong inputs.
    assertThat(built.queryParameter("t")).isEqualTo(SubsonicAuth.token("hunter2", salt))
    assertThat(built.queryParameter("u")).isEqualTo("luc")
  }

  @Test
  fun `the plaintext password never appears on the url`() {
    // The whole point of token auth. Asserted on the raw string, not on a parsed parameter, so an
    // accidental `p=` or a password smuggled into any other parameter fails too.
    assertThat(url().toString()).doesNotContain("hunter2")
  }

  /**
   * Two calls for the same song produce different strings, because `authParams()` generates a
   * fresh salt every time.
   *
   * This is a **problem statement**, committed as an assertion. Anything that keys a cache on this
   * URL can never hit that cache — which is precisely the defect Tempo ships, and precisely why
   * Task 3 keys the media cache on the track id via `setCustomCacheKey`. If salt freshness ever
   * went away, this test failing is the signal to go and simplify the cache key, not to delete
   * the test.
   */
  @Test
  fun `two urls for the same song carry different salts and are therefore different strings`() {
    val first = url(songId = "track-1")
    val second = url(songId = "track-1")

    assertThat(first.queryParameter("s")).isNotEqualTo(second.queryParameter("s"))
    assertThat(first.queryParameter("t")).isNotEqualTo(second.queryParameter("t"))
    assertThat(first.toString()).isNotEqualTo(second.toString())
    // ...and the one thing that must not vary with the salt.
    assertThat(first.queryParameter("id")).isEqualTo(second.queryParameter("id"))
  }

  /**
   * `c` and `v` are fixed, not arguments — so rule 2 ("observed at one value is not tested") does
   * not apply to them, and asserting them is still worth doing: Navidrome's `LegacyClients`
   * default is `DSub` and `MinimalClients` is `SubMusic`, and a client whose `c` matches either
   * has the OpenSubsonic field block stripped from every response.
   */
  @Test
  fun `the client id and protocol version are the ones navidrome must not strip`() {
    assertThat(url().queryParameter("c")).isEqualTo("MuPlay")
    assertThat(url().queryParameter("v")).isEqualTo("1.16.1")
  }

  @Test
  fun `f json rides along and that is deliberate`() {
    // /rest/stream returns audio, so `f` is meaningless on success. On a *failure* Navidrome
    // answers with an error document, and `f=json` makes that document JSON rather than XML —
    // which is what the rest of this client already knows how to read. Sharing one `authParams()`
    // across every command is what keeps auth in one place; this assertion records that the
    // consequence was noticed rather than overlooked.
    assertThat(url().queryParameter("f")).isEqualTo("json")
  }

  @Test
  fun `a base url without a trailing slash produces the same path as one with`() {
    assertThat(url(baseUrl = "https://music.example.com").encodedPath).isEqualTo("/rest/stream")
    assertThat(url(baseUrl = "https://music.example.com/").encodedPath).isEqualTo("/rest/stream")
  }

  @Test
  fun `a base url with a sub-path keeps it`() {
    // Reverse proxies that mount Navidrome under a path are ordinary. Losing the prefix here
    // produces a 404 from the proxy that looks like a missing track.
    assertThat(url(baseUrl = "https://example.com/navidrome").encodedPath)
      .isEqualTo("/navidrome/rest/stream")
    assertThat(url(baseUrl = "https://example.com/navidrome/").encodedPath)
      .isEqualTo("/navidrome/rest/stream")
  }

  @Test
  fun `the host and scheme come from the credentials`() {
    // Two observations of a value a constant could satisfy — N-2 in `ci/mutation-probes.sh` was
    // exactly this defect on the cover-art URL.
    assertThat(url(baseUrl = "https://music.example.com").host).isEqualTo("music.example.com")
    assertThat(url(baseUrl = "http://localhost:4533").host).isEqualTo("localhost")
    assertThat(url(baseUrl = "http://localhost:4533").port).isEqualTo(4533)
    assertThat(url(baseUrl = "http://localhost:4533").scheme).isEqualTo("http")
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:network:test --tests '*StreamUrlTest*'`
Expected: FAIL — `Unresolved reference: streamUrl`.

- [ ] **Step 7: Implement `streamUrl`**

`core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt` — add to the interface, after
`coverArtUrl`:

```kotlin
  /**
   * An authenticated `/rest/stream` URL for one song. Not `suspend`: it opens no connection, it
   * builds a URL.
   *
   * Handed to Media3, which fetches it with **its own** HTTP stack and none of this client's
   * interceptors, so every credential has to be in the string. It carries a **fresh salt**, so two
   * calls for the same song produce different strings — see [coverArtUrl] for the same property
   * and `:core:media`'s `TrackIdCacheKeyFactory` for why that makes a URL-derived cache key
   * unusable.
   *
   * [format] is a [StreamFormat], never a `String`: the global constraints say stream requests
   * force `raw` or `mp3` and **never** Opus, and the way to enforce that is to make `opus`
   * unrepresentable rather than to check for it.
   */
  fun streamUrl(songId: String, format: StreamFormat): String
```

with an `import app.muplay.model.StreamFormat` at the top of the file.

`core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt` — add after `coverArtUrl`,
and `import app.muplay.model.StreamFormat`:

```kotlin
  /**
   * An authenticated `/rest/stream` URL — see [SubsonicSource.streamUrl].
   *
   * Three parameters are conspicuously absent, and each absence is a decision:
   *
   * - **`estimateContentLength`.** It makes a transcoded response carry a *guessed*
   *   `Content-Length`. ExoPlayer trusts that header for seeking, so a guess produces seeks that
   *   land in the wrong place with nothing reported anywhere.
   * - **`timeOffset`.** Only reachable on a transcode this plan does not seek; the plan that seeks
   *   one adds it together with the `transcodeOffset` capability gate.
   * - **`maxBitRate` on a raw request.** `format=raw` disables transcoding, so a bitrate cap
   *   beside it is a parameter the server ignores and a reader misreads.
   */
  override fun streamUrl(songId: String, format: StreamFormat): String {
    val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()
      .addPathSegments("rest/stream")
      .addQueryParameter("id", songId)
      .addQueryParameter("format", format.wireValue)
    if (format is StreamFormat.Mp3) {
      builder.addQueryParameter("maxBitRate", format.maxBitRateKbps.toString())
    }
    authParams().forEach { (name, value) -> builder.addQueryParameter(name, value) }
    return builder.build().toString()
  }
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew :core:network:test --tests '*StreamUrlTest*'`
Expected: PASS, 13/13.

- [ ] **Step 9: Write the failing live tests**

`core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt` — add to the existing
class, keeping every test already there and its `@Tag("live")`:

```kotlin
  /**
   * The precondition the whole streaming design rests on: a raw response is a plain, seekable,
   * length-declared HTTP body.
   *
   * `assertThat(bytes).hasSizeGreaterThan(1000)` is not decoration. Without it this test passes
   * against a server that answers 200 with an empty body — the same vacuity that let a
   * live-Navidrome suite pass with no Navidrome running.
   */
  @Test
  fun `a raw stream is a 200 with an accurate content length and byte ranges`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(response.header("Content-Length")?.toLong()).isEqualTo(bytes.size.toLong())
    assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
    // Chunked would mean no Content-Length, and no Content-Length means no seek.
    assertThat(response.header("Transfer-Encoding")).isNull()
    assertThat(response.header("Content-Type")).startsWith("audio/")
  }

  @Test
  fun `a range request on a raw stream is a byte-exact 206`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val url = client.streamUrl(song.id, StreamFormat.Raw)
    val (_, whole) = fetch(url)
    val offset = whole.size / 2

    val (response, tail) = fetch(url, range = "bytes=$offset-")

    assertThat(response.code).isEqualTo(206)
    assertThat(response.header("Content-Range"))
      .isEqualTo("bytes $offset-${whole.size - 1}/${whole.size}")
    // Byte-exact, not merely "the right length": a server that answered 206 with the *start* of
    // the file would pass a length check and produce audio that jumps back on every seek.
    assertThat(tail).isEqualTo(whole.copyOfRange(offset, whole.size))
  }

  @Test
  fun `a range past the end of a raw stream is 416`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val url = client.streamUrl(song.id, StreamFormat.Raw)
    val (_, whole) = fetch(url)

    val (response, _) = fetch(url, range = "bytes=${whole.size + 1000}-")

    assertThat(response.code).isEqualTo(416)
  }

  /**
   * The other half of the raw preference, and the reason it is a preference at all: **a live
   * transcode cannot be seeked.**
   *
   * If this ever started reporting `Accept-Ranges: bytes` and a `Content-Length`, the correct
   * response is to go and simplify the format policy — not to delete this test. If it silently
   * reversed the other way while nobody was looking, the symptom would be a seek bar that does
   * nothing, on a code path no unit test can reach.
   */
  @Test
  fun `a live transcode returns no content length and refuses ranges`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Mp3(32)))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(response.header("Accept-Ranges")).isEqualTo("none")
    assertThat(response.header("Content-Length")).isNull()
  }

  /**
   * The URL authenticates itself, which is the only reason handing it to ExoPlayer works at all.
   *
   * Asserted by *removing* the credentials and checking the audio does not come back, rather than
   * by checking a status code: Navidrome answers a `/rest/stream` auth failure with an error
   * document, and this assertion holds whether that arrives as 200-plus-JSON or as a 4xx. The
   * status code is attached to the failure message so the real behaviour is recorded either way.
   */
  @Test
  fun `stripping the credentials from a stream url stops the audio`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).first()
    val authenticated = client.streamUrl(song.id, StreamFormat.Raw).toHttpUrl()
    val stripped = authenticated.newBuilder().removeAllQueryParameters("t")
      .removeAllQueryParameters("s").removeAllQueryParameters("u").build()

    val (authorisedResponse, audio) = fetch(authenticated.toString())
    val (response, body) = fetch(stripped.toString())

    assertThat(authorisedResponse.code).isEqualTo(200)
    assertThat(response.header("Content-Type"))
      .describedAs("unauthenticated /rest/stream answered %s", response.code)
      .doesNotContain("audio/")
    assertThat(body).isNotEqualTo(audio)
  }

  /**
   * The audiobook streams raw too, and the assertion is on its actual container bytes rather than
   * on a header: every ISO-BMFF file (`.m4b`, `.m4a`, `.mp4`) begins with a four-byte size
   * followed by the literal `ftyp`. A server returning an error page, silence, or the wrong file
   * fails this; a `Content-Type` check would not.
   */
  @Test
  fun `the audiobook streams raw as an mp4 container`() = runTest {
    val client = client("testpass")
    val song = client.getRandomSongs(musicFolderId = AUDIOBOOK_LIBRARY_ID, size = 500).single()

    val (response, bytes) = fetch(client.streamUrl(song.id, StreamFormat.Raw))

    assertThat(response.code).isEqualTo(200)
    assertThat(bytes.size).isGreaterThan(1000)
    assertThat(String(bytes.copyOfRange(4, 8), Charsets.US_ASCII)).isEqualTo("ftyp")
  }
```

and, in the same class, the one helper these share:

```kotlin
  /**
   * One raw HTTP GET of [url], returning the response and its whole body.
   *
   * A plain `OkHttpClient`, not [SubsonicClient]'s Retrofit stack, on purpose: the subject of
   * every test above is what an *arbitrary* HTTP client sees when handed a stream URL, because
   * that is exactly what Media3 is.
   */
  private fun fetch(url: String, range: String? = null): Pair<Response, ByteArray> {
    val request = Request.Builder().url(url).apply {
      if (range != null) header("Range", range)
    }.build()
    return OkHttpClient().newCall(request).execute().use { response ->
      response to (response.body?.bytes() ?: ByteArray(0))
    }
  }
```

with imports for `app.muplay.model.StreamFormat`, `okhttp3.HttpUrl.Companion.toHttpUrl`,
`okhttp3.OkHttpClient`, `okhttp3.Request` and `okhttp3.Response`. (`OkHttpClient` and `Request`
are already imported by Plan 2 Task 7's `rawRest` helper; add only what is missing.)

> `response.use { ... }` reading `body?.bytes()` inside the block is deliberate — the body must be
> consumed before the response is closed, and returning the `Response` afterwards is safe because
> every assertion above reads only its status line and headers.

- [ ] **Step 10: Run the live tests against the real container**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
./gradlew :core:network:liveNavidromeTest
```

Expected: PASS — every pre-existing test plus the six added here.

**If it passes on the first run without the container, stop.** `docker compose -f
ci/navidrome.compose.yml down`, re-run, and require a red build. `liveNavidromeTest` passing
`UP-TO-DATE` with no Navidrome is the eleventh silent gate this project found; the
`outputs.upToDateWhen { false }` / `outputs.cacheIf { false }` lines in root `build.gradle.kts`
are what closed it. Confirm they are still there before trusting a green run.

- [ ] **Step 11: Prove each new assertion can fail**

One mutation at a time, restored after each, failure message recorded in the task report:

1. In `SubsonicClient.streamUrl`, replace `songId` with the literal `"track-1"`. Expect
   `the song id is on the url and is the one the caller asked for` to fail on its second
   observation, and the live range test to fail or 404.
2. Replace `format.wireValue` with `"raw"`. Expect `an mp3 request sends format mp3 and the
   bitrate cap it was given` and the live `a live transcode returns no content length and refuses
   ranges` to fail. **Both**, and that is the point: the JVM test proves the parameter is built,
   the live test proves the server acts on it.
3. Delete the `authParams().forEach` line. Expect `the token on this url is a real md5...` and the
   live `stripping the credentials...` to fail. This is Plan 1's own finding, re-armed for a URL
   that no Retrofit call site covers.
4. In `StreamFormat.forSuffix`, return `Raw` unconditionally. Expect the two transcode tests to
   fail. Then return `Mp3(transcodeBitRateKbps)` unconditionally and expect `every other suffix
   streams raw` to fail — proving the branch discriminates in both directions.
5. In `StreamFormat.forSuffix`, return `Mp3(192)` instead of `Mp3(transcodeBitRateKbps)`. Expect
   `the transcode bitrate is the one the caller passed` to fail. This is the constant-in-a-mapped-
   field defect in its purest form, and it is the reason that test exists at all.

- [ ] **Step 12: Record the probes**

`ci/mutation-probes.sh` — add five entries to the `PROBES` table, one per mutation above, each
naming the single test that must go red. Read the script's header first: it is explicit that it is
a regression list, not a gate, and that adding a probe records an answer rather than generating a
question.

- [ ] **Step 13: Re-measure `:core:model`'s floor and commit**

`StreamFormat` has real author-written branches (`forSuffix`'s membership test and `Mp3`'s
`require`), so it needs a real BRANCH floor rather than a ride-along slot. Add
`"app.muplay.model.StreamFormat"`, `"app.muplay.model.StreamFormat*"` to the existing
`":core:model"` BRANCH `CoverageFloor`'s `includes` list, run
`./gradlew :core:model:test jacocoTestReport`, read the measured ratio out of
`core/model/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`, and confirm it clears
0.90. If it does not, add the missing case — do not lower the floor.

```bash
./gradlew :core:model:test :core:network:test
./gradlew jacocoJvmCoverageVerification
git add core/model core/network build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(network): authenticated format=raw stream URLs, and never Opus"
```

---

## Task 2: `:core:media` — the module, Media3 1.11.0, the OkHttp data source and the 429 policy

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `core/media/build.gradle.kts`
- Create: `core/media/src/main/kotlin/app/muplay/media/StreamRetryPolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/NavidromeLoadErrorHandlingPolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/MuPlayDataSourceFactory.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/StreamRetryPolicyTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/PlayerHarness.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/MuPlayDataSourceFactoryTest.kt`
- Modify: `build.gradle.kts` (a `":core:media"` entry in `coverageFloors`)
- Modify: `.github/workflows/e2e.yml` (`:core:media:connectedDebugAndroidTest`)

**Interfaces:**
- Consumes: nothing from Plan 2 yet — this task is deliberately the one that stands alone, so the
  module and its Media3 wiring can be proven before any repository is involved.
- Produces:
  - Gradle module `:core:media`, namespace `app.muplay.media`, plugins
    `muplay.android.library` + `muplay.android.hilt`
  - catalogue aliases `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`,
    `media3-ui-compose`, `media3-ui-compose-material3`, all at `version.ref = "media3"` (`1.11.0`,
    already in the catalogue)
  - `object StreamRetryPolicy` with
    `fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, errorCount: Int): Long?`,
    `const val MAX_RETRIES: Int = 5`, `const val BASE_BACKOFF_MS: Long = 1_000L`,
    `const val MAX_BACKOFF_MS: Long = 30_000L`, `const val TOO_MANY_REQUESTS: Int = 429`
  - `class NavidromeLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy`
  - `class MuPlayDataSourceFactory @Inject constructor(callFactory: Call.Factory)` with
    `fun create(): DataSource.Factory` and `companion object { const val USER_AGENT = "MuPlay" }`
  - Hilt `MediaModule` providing `@Singleton OkHttpClient` (as `Call.Factory`) and
    `@Singleton NavidromeLoadErrorHandlingPolicy`
  - androidTest helper `PlayerHarness` with `onMain`, `awaitState`, `awaitPositionAtLeast`,
    `awaitEnded`, `release` (signatures in Step 7 — later tasks repeat them rather than
    cross-referencing)

### Why a whole module for this

Spec §9 puts `core/media` in the structure for a reason that only becomes visible once something
imports Media3: **`androidx.media3.exoplayer` must not be reachable from a feature module.** A
`:feature:player` that can construct an `ExoPlayer` will eventually construct one, and then there
are two players in the process, one of them not the one holding the media session — which is the
single most common way a media app ends up with a notification that controls nothing.

So the module boundary is the enforcement: `:core:media` `implementation`s
`media3-exoplayer`, and `:feature:player` gets `media3-session` only (Task 9). Nothing else in the
project may declare either.

### Why OkHttp rather than `DefaultHttpDataSource`

Two concrete reasons, not a preference:

1. **Cross-protocol redirects.** A Navidrome behind a reverse proxy commonly redirects `http` to
   `https`. `DefaultHttpDataSource` refuses cross-protocol redirects unless explicitly told
   otherwise, and the failure looks like a dead track. OkHttp follows them by default.
2. **One HTTP implementation in the project.** `:core:network` already uses OkHttp 5.5.0 and the
   spec's stack table names it. Adding Cronet or leaving Media3 on its own stack would mean two
   TLS configurations, two proxy behaviours and two sets of timeouts to reason about.

The one thing that is **not** shared is the `OkHttpClient` instance. `:core:network` builds its
own inside Retrofit for short JSON calls; this one is configured for long-lived streaming reads
(a generous read timeout, no call timeout at all — a call timeout on a streaming body kills
playback mid-track by design). Two clients with different timeout policies is correct; one client
with the union of both policies is not.

### Why the 429 policy is a real decision and not a default

Spec §4: *"**Handle HTTP 429** — Navidrome 0.62.0 added `Transcoding.MaxConcurrent`. Unhandled,
this looks like random playback failure."*

Media3's `DefaultLoadErrorHandlingPolicy` does not treat 429 specially. It retries an
`InvalidResponseCodeException` `getMinimumLoadableRetryCount` times (3, by default) with a delay of
`min((errorCount - 1) * 1000, 5000)` ms — so the **first** retry is immediate, and all three are
gone inside five seconds. Against a server that is refusing because a transcoding slot is busy,
three requests in five seconds is the worst possible thing to do: it fails, and it makes the
contention marginally worse on the way.

The policy here: on 429 only, honour a `Retry-After` in its delta-seconds form when present, and
otherwise back off exponentially from one second to a thirty-second ceiling, over five attempts.
Everything that is not a 429 keeps Media3's own behaviour untouched — a policy that overrode
everything would be re-implementing the library.

**The `Retry-After` HTTP-date form is deliberately not parsed.** Parsing it needs "now", which
means injecting a `Clock` for a header Navidrome has never been observed to send. The
delta-seconds branch covers the realistic case; the date form falls through to the same backoff a
missing header gets, which is a correct answer, just not the tightest possible one.

- [ ] **Step 1: Add the module and the catalogue entries**

`settings.gradle.kts` — add beside the existing includes:

```kotlin
include(":core:media")
```

`gradle/libs.versions.toml` — the `media3 = "1.11.0"` version already exists. Add to `[libraries]`:

```toml
# Media3 1.11.0. Every coordinate below was resolved against
# https://dl.google.com/dl/android/maven2/androidx/media3/<artifact>/maven-metadata.xml while this
# plan was written; all publish 1.11.0 as <latest>. `media3-inspector` (chapters) is deliberately
# absent -- it is Plan 4's, and an unused dependency is exactly what "dependency minimalism" bans.
media3-exoplayer            = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-session              = { module = "androidx.media3:media3-session", version.ref = "media3" }
media3-datasource-okhttp    = { module = "androidx.media3:media3-datasource-okhttp", version.ref = "media3" }
media3-ui-compose           = { module = "androidx.media3:media3-ui-compose", version.ref = "media3" }
media3-ui-compose-material3 = { module = "androidx.media3:media3-ui-compose-material3", version.ref = "media3" }
```

`core/media/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.media"
}

dependencies {
  // `api`, not `implementation`: this module's public surface returns and accepts `:core:model`
  // types (`Song`, `LibraryRole`, `StreamFormat`), so a consumer cannot compile against it
  // without them. Same audit `plan-2-inherited.md` item 4 asked for, applied here.
  api(project(":core:model"))

  // `api` for the same reason, and only for this one artifact: `PlaybackConnection` (Task 9)
  // hands `:feature:player` a `MediaController`, which is a `media3-session` type. Everything
  // else Media3 offers stays `implementation`, and `media3-exoplayer` in particular must never
  // become `api` -- see this task's own note on why a feature module that can build an
  // `ExoPlayer` eventually does.
  api(libs.media3.session)

  implementation(libs.media3.exoplayer)
  implementation(libs.media3.datasource.okhttp)
  implementation(libs.okhttp)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)

  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.coroutines.test)
  androidTestImplementation(libs.okhttp.mockwebserver)
  // Byte Buddy is stripped from every androidTest configuration project-wide by
  // `excludeByteBuddyFromInstrumentedTests` (build-logic); nothing to do here.
  androidTestImplementation(libs.assertj)
  // The instrumented tests build real stream URLs against the real container.
  androidTestImplementation(project(":core:network"))
}
```

`build.gradle.kts` — add a placeholder entry to `coverageFloors` so `ConventionTest`'s
`every Gradle project has a coverage floor` passes from this task onward. The numbers are measured
in Step 11 and again in Task 10; the entry's *shape* is what has to exist now:

```kotlin
  // `:core:media`. Measured in this task's Step 11 and re-measured in Task 10 once the service,
  // the queue and the progress writer are in. `StreamRetryPolicy` is a pure object with real
  // branches and no Android dependency at all -- that is why it exists as a separate type from
  // the Media3 adapter, and why this is the module's one Tier-1-enforceable floor.
  ":core:media" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.StreamRetryPolicy"),
    ),
  ),
```

- [ ] **Step 2: Confirm the module resolves before writing anything else**

```bash
./gradlew :core:media:dependencies --configuration debugRuntimeClasspath | grep media3
```

Expected: every one of `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp` and their
transitive `media3-common`/`media3-datasource`/`media3-decoder`/`media3-extractor` resolved at
**1.11.0**. If any line shows a different version, a transitive constraint is winning and the
catalogue pin is not doing what it looks like it is doing — fix that here, before any code depends
on it.

Also run the guards this module has to satisfy from birth:

```bash
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
./gradlew :core:media:verifyNoMockFrameworks
```

Expected: both PASS. (`verifyNoMockFrameworks` is Plan 2 Task 10's resolved-classpath guard.
Media3 pulls in Guava; confirm nothing else arrived with it.)

- [ ] **Step 3: Write the failing retry-policy test**

`core/media/src/test/kotlin/app/muplay/media/StreamRetryPolicyTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test, on purpose. [StreamRetryPolicy] takes an HTTP status code, a header string and
 * an attempt count, and returns a delay — no `android.net.Uri`, no `DataSpec`, no Media3 type at
 * all — which is what lets Tier 1 gate the branch that decides whether a 429 kills playback. The
 * Media3 adapter around it (`NavidromeLoadErrorHandlingPolicy`) is three lines with no logic, and
 * is covered on the device.
 *
 * This is the same shape as `KeystoreCipher` taking a `SecretKey` rather than reaching into
 * AndroidKeyStore, and `SyncDecision.decide` being a pure ruling: put the decision where the fast
 * tier can hold it to a floor.
 */
class StreamRetryPolicyTest {

  @Test
  fun `a status that is not 429 is not this policy's business`() {
    // `null` means "defer to Media3's own DefaultLoadErrorHandlingPolicy". Overriding every status
    // would be re-implementing the library, and getting 404 or 416 wrong breaks seeking.
    assertThat(StreamRetryPolicy.retryDelayMs(404, retryAfterHeader = null, errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(416, retryAfterHeader = null, errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(500, retryAfterHeader = "1", errorCount = 1)).isNull()
    assertThat(StreamRetryPolicy.retryDelayMs(200, retryAfterHeader = null, errorCount = 1)).isNull()
  }

  @Test
  fun `a 429 with no retry-after backs off exponentially from the base delay`() {
    // Four observations of a value one constant could satisfy. This is the field-level rule
    // applied to a computed number: a `return 1_000L` passes the first of these and fails three.
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 1)).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 2)).isEqualTo(2_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 3)).isEqualTo(4_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 4)).isEqualTo(8_000L)
  }

  @Test
  fun `the backoff is capped rather than doubling forever`() {
    // Without a ceiling, attempt 10 waits over eight minutes and the user believes the app hung.
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 6)).isEqualTo(30_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, null, errorCount = 50)).isEqualTo(30_000L)
    assertThat(StreamRetryPolicy.MAX_BACKOFF_MS).isEqualTo(30_000L)
  }

  @Test
  fun `a retry-after in seconds is honoured and is the value the server sent`() {
    // Two values, so "honoured" cannot be satisfied by returning a constant that happens to match
    // one of them.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "3", errorCount = 1)).isEqualTo(3_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "17", errorCount = 1)).isEqualTo(17_000L)
  }

  @Test
  fun `a retry-after the server sent beats the backoff the client would have chosen`() {
    // errorCount 4 would back off 8s on its own; the server said 2, and the server wins. Asserted
    // in the direction where the two disagree, because in the direction where they agree the test
    // would pass with the header ignored.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "2", errorCount = 4)).isEqualTo(2_000L)
  }

  @Test
  fun `an oversized retry-after is clamped to the same ceiling as the backoff`() {
    assertThat(StreamRetryPolicy.retryDelayMs(429, "600", errorCount = 1)).isEqualTo(30_000L)
  }

  @Test
  fun `a retry-after this policy cannot parse falls through to the backoff`() {
    // The HTTP-date form is deliberately not parsed -- parsing it needs a clock, for a header
    // Navidrome has never been observed to send. Falling through is a correct answer, and these
    // assertions are what stop that from being mistaken for an oversight.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "Wed, 21 Oct 2026 07:28:00 GMT", 1)).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "soon", 2)).isEqualTo(2_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "", 3)).isEqualTo(4_000L)
    assertThat(StreamRetryPolicy.retryDelayMs(429, "-5", 1)).isEqualTo(1_000L)
  }

  @Test
  fun `a retry-after of zero means retry now and is not mistaken for absent`() {
    // 0 is a legal delta-seconds value and it is *not* the same as "no header": the header form
    // returns 0, the absent form returns the base backoff. A parser using `toLongOrNull() ?: base`
    // would get this right; one using `takeIf { it > 0 }` would silently turn it into 1000.
    assertThat(StreamRetryPolicy.retryDelayMs(429, "0", errorCount = 1)).isEqualTo(0L)
  }

  @Test
  fun `five attempts is the retry budget`() {
    assertThat(StreamRetryPolicy.MAX_RETRIES).isEqualTo(5)
    assertThat(StreamRetryPolicy.BASE_BACKOFF_MS).isEqualTo(1_000L)
    assertThat(StreamRetryPolicy.TOO_MANY_REQUESTS).isEqualTo(429)
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*StreamRetryPolicyTest*'`
Expected: FAIL — `Unresolved reference: StreamRetryPolicy`.

- [ ] **Step 5: Implement the policy and its Media3 adapter**

`core/media/src/main/kotlin/app/muplay/media/StreamRetryPolicy.kt`:

```kotlin
package app.muplay.media

/**
 * What to do about an HTTP status Media3 hit while loading a stream.
 *
 * Deliberately free of every Media3 and Android type: it takes a status code, a header value and
 * an attempt number, and returns a delay in milliseconds — or `null`, meaning *"not my business,
 * use Media3's own policy"*. That shape is what lets the fast tier gate this branch, and it is the
 * same trade `KeystoreCipher` makes by taking a `SecretKey` instead of opening the Android
 * Keystore itself.
 *
 * Only **429** is this policy's business. Navidrome 0.62.0 added `Transcoding.MaxConcurrent`, and
 * a 429 from it means "a transcoding slot is busy, ask again". Media3's own
 * `DefaultLoadErrorHandlingPolicy` treats it like any other response-code error: three retries,
 * the first immediate, all of them inside five seconds — which fails the track *and* adds to the
 * contention on the way out. Spec section 4 records the symptom: unhandled, this looks like random
 * playback failure.
 */
object StreamRetryPolicy {

  /** The only status this policy has an opinion about. */
  const val TOO_MANY_REQUESTS: Int = 429

  /** Attempts before giving up. Five, over a backoff that reaches the ceiling at attempt six. */
  const val MAX_RETRIES: Int = 5

  /** The first wait. One second, not zero: an immediate retry is what makes contention worse. */
  const val BASE_BACKOFF_MS: Long = 1_000L

  /**
   * The longest this will ever wait. Doubling without a ceiling reaches eight minutes by attempt
   * ten, which a listener experiences as the app having hung.
   */
  const val MAX_BACKOFF_MS: Long = 30_000L

  /**
   * The delay before Media3 should retry, or `null` to defer to Media3's own policy.
   *
   * [retryAfterHeader] is honoured only in its **delta-seconds** form (RFC 9110 §10.2.3's first
   * alternative). The HTTP-date form is not parsed: doing so needs a wall clock — and therefore an
   * injected `Clock`, per this project's constraints — for a header Navidrome has never been
   * observed to send. An unparseable value falls through to the backoff, which is a correct
   * answer rather than a wrong one.
   *
   * A `Retry-After` of `"0"` returns `0`, not the base backoff: zero is a legal value that means
   * "now", and collapsing it into "absent" would be a client silently disagreeing with a server
   * that answered the question.
   */
  fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, errorCount: Int): Long? {
    if (responseCode != TOO_MANY_REQUESTS) return null
    val fromHeader = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    val delay = fromHeader?.times(1000L) ?: backoffMs(errorCount)
    return delay.coerceIn(0L, MAX_BACKOFF_MS)
  }

  /** `BASE_BACKOFF_MS * 2^(errorCount - 1)`, computed by shifting so it cannot overflow. */
  private fun backoffMs(errorCount: Int): Long {
    val doublings = (errorCount - 1).coerceIn(0, 30)
    return BASE_BACKOFF_MS shl doublings
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/NavidromeLoadErrorHandlingPolicy.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import javax.inject.Inject

/**
 * Media3's own load-error policy, with one exception carved out of it: HTTP 429.
 *
 * Everything this class does that is interesting lives in [StreamRetryPolicy], which is a plain
 * object with no Media3 or Android types in its signature. This is only the adapter that reaches
 * a response code and a header out of an [HttpDataSource.InvalidResponseCodeException] and hands
 * them over — the split exists so the decision is gated by the fast tier rather than only by an
 * emulator.
 *
 * Extending `DefaultLoadErrorHandlingPolicy` rather than implementing
 * [LoadErrorHandlingPolicy] from scratch: everything that is not a 429 must keep Media3's own
 * behaviour, and 404/416/`ParserException` handling in particular is what makes seeking work.
 */
class NavidromeLoadErrorHandlingPolicy @Inject constructor() :
  DefaultLoadErrorHandlingPolicy(StreamRetryPolicy.MAX_RETRIES) {

  override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
    val exception = loadErrorInfo.exception
    if (exception is HttpDataSource.InvalidResponseCodeException) {
      val retryAfter = exception.headerFields["Retry-After"]?.firstOrNull()
      val delay = StreamRetryPolicy.retryDelayMs(
        responseCode = exception.responseCode,
        retryAfterHeader = retryAfter,
        errorCount = loadErrorInfo.errorCount,
      )
      if (delay != null) return delay
    }
    return super.getRetryDelayMsFor(loadErrorInfo)
  }

  /**
   * `C.TIME_UNSET` is Media3's own "do not retry" sentinel and is referenced here only so the
   * import documents the contract this override lives inside: a non-negative return retries after
   * that many milliseconds, `C.TIME_UNSET` gives up. `super` returns it for the cases this class
   * does not touch.
   */
  private companion object {
    @Suppress("unused")
    const val DO_NOT_RETRY: Long = C.TIME_UNSET
  }
}
```

> `LoadErrorInfo` is a nested class of `LoadErrorHandlingPolicy`; its `exception`, `errorCount`
> and the exception's `responseCode`/`headerFields` are public. **Confirm the exact nesting and
> property names against the resolved 1.11.0 sources before assuming this compiles** — `./gradlew
> :core:media:compileDebugKotlin` is the check, and if a name has moved, fix the adapter and leave
> `StreamRetryPolicy` untouched. That is the whole reason the decision does not live in here.

`core/media/src/main/kotlin/app/muplay/media/MuPlayDataSourceFactory.kt`:

```kotlin
package app.muplay.media

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call

/**
 * The HTTP half of every byte this app plays.
 *
 * OkHttp rather than `DefaultHttpDataSource`, for two concrete reasons. A Navidrome behind a
 * reverse proxy commonly redirects `http` to `https`, and `DefaultHttpDataSource` refuses a
 * cross-protocol redirect unless told otherwise — a refusal that presents as a dead track.
 * And this project already has exactly one HTTP implementation; a second would mean two TLS
 * configurations and two proxy behaviours to reason about.
 *
 * The `Call.Factory` is injected rather than built here so that the client's timeout policy is
 * declared in one place (`MediaModule`) and so an instrumented test can point the same factory at
 * a `MockWebServer`.
 */
@Singleton
class MuPlayDataSourceFactory @Inject constructor(private val callFactory: Call.Factory) {

  /**
   * A fresh `DataSource.Factory`. Not cached: Media3 factories are cheap, and Task 3 wraps this
   * one in a cache-backed factory whose lifetime is different from this object's.
   */
  fun create(): DataSource.Factory =
    OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT)

  companion object {
    /**
     * Sent as `User-Agent`. Navidrome identifies clients by the `c` query parameter, not by this,
     * so nothing behavioural hangs on it — but a server log that says which client issued a
     * request is worth the one line, and an absent `User-Agent` is the kind of thing a proxy
     * decides to reject.
     */
    const val USER_AGENT: String = "MuPlay"
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`:

```kotlin
package app.muplay.media.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * The media layer's object graph.
 *
 * The `OkHttpClient` here is **not** the one `:core:network` uses, and that is deliberate rather
 * than an oversight. `:core:network` issues short JSON requests where a call timeout is a safety
 * net; this one reads a media body that is legitimately open for the length of a track, where a
 * call timeout is a guaranteed mid-song failure. Two clients with different, correct policies beats
 * one client with the union of both.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

  @Provides
  @Singleton
  fun provideMediaCallFactory(): Call.Factory =
    OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // How long a *read* may stall, not how long the whole body may take.
      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // No `callTimeout`: the default is already "none", and setting one would cap the total
      // duration of a streaming read, i.e. cut off any track longer than the cap. Stated as a
      // comment because "we did not set it" and "we thought about it and must not set it" are
      // different facts, and only one of them survives a refactor.
      .build()

  private const val CONNECT_TIMEOUT_SECONDS = 15L
  private const val READ_TIMEOUT_SECONDS = 30L
}
```

- [ ] **Step 6: Run the JVM test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*StreamRetryPolicyTest*'`
Expected: PASS, 9/9.

- [ ] **Step 7: Write the instrumented player harness**

Every instrumented playback test in this plan needs the same three things: a player built on the
main `Looper` (Media3 requires it), a way to wait for a condition without a fixed sleep, and a way
to surface an `ExoPlaybackException` as itself rather than as an unexplained timeout. That last one
is what makes these tests debuggable at all.

`core/media/src/androidTest/kotlin/app/muplay/media/PlayerHarness.kt`:

```kotlin
package app.muplay.media

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a real [ExoPlayer] from an instrumented test thread.
 *
 * Three jobs, and the third is the one worth reading:
 *
 * 1. Every touch of the player happens on the main `Looper`, which Media3 requires.
 * 2. Waiting is done by polling a condition with a deadline — never a fixed sleep. A fixed sleep
 *    is either flaky or slow, and on a CI emulator it is reliably both.
 * 3. **A playback error fails the test as that error.** Without this, a 404, a codec that would
 *    not initialise and a URL that was never fetched all present identically: a `waitUntil` that
 *    timed out. The captured [PlaybackException] is rethrown as the assertion failure's cause,
 *    so the message names the real problem.
 */
class PlayerHarness(val player: ExoPlayer) {

  private val error = AtomicReference<PlaybackException?>(null)

  init {
    onMain {
      player.addListener(object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
          error.set(e)
        }
      })
    }
  }

  /** Runs [block] on the main thread and returns its result, propagating any exception. */
  fun <T> onMain(block: () -> T): T {
    val result = AtomicReference<Any?>(null)
    val thrown = AtomicReference<Throwable?>(null)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result.set(it) }.onFailure { thrown.set(it) }
    }
    thrown.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result.get() as T
  }

  /** Polls [condition] on the main thread until it is true or [timeoutMs] elapses. */
  fun await(description: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      error.get()?.let { throw AssertionError("playback failed while waiting for $description", it) }
      if (onMain(condition)) return
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error.get()?.let { throw AssertionError("playback failed while waiting for $description", it) }
    throw AssertionError(
      "timed out after ${timeoutMs}ms waiting for $description; " +
        "state=${onMain { player.playbackState }} playWhenReady=${onMain { player.playWhenReady }} " +
        "position=${onMain { player.currentPosition }}",
    )
  }

  fun awaitState(state: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
    await("playbackState == $state", timeoutMs) { player.playbackState == state }

  /**
   * Waits until the player's own position passes [positionMs].
   *
   * **This is the assertion that distinguishes "playing" from "was asked to play".** `play()`
   * returning, `playWhenReady == true` and `STATE_READY` are all satisfied by a player that never
   * produced a sample. A position that has genuinely moved past a second of media is not.
   */
  fun awaitPositionAtLeast(positionMs: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
    await("currentPosition >= $positionMs", timeoutMs) { player.currentPosition >= positionMs }

  fun awaitEnded(timeoutMs: Long = DEFAULT_TIMEOUT_MS) = awaitState(Player.STATE_ENDED, timeoutMs)

  /** Rethrows any error the player reported, whether or not a wait was in progress. */
  fun assertNoPlaybackError() {
    error.get()?.let { throw AssertionError("player reported an error", it) }
  }

  fun release() = onMain { player.release() }

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 50L
  }
}
```

- [ ] **Step 8: Write the failing data-source instrumented test**

`core/media/src/androidTest/kotlin/app/muplay/media/MuPlayDataSourceFactoryTest.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real ExoPlayer, real HTTP, real MP3 bytes, real emulator.
 *
 * The bytes come from the **live Navidrome container** rather than from a fixture copied into this
 * module's assets: `ci/fixtures.md5` already pins those files and `ci/seed-fixtures.sh` already
 * builds them, and a second copy in `src/androidTest/assets` is a second thing to keep in sync
 * that nothing checks. `.github/workflows/e2e.yml` starts the container and
 * `ci/prepare-emulator.sh` sets up `adb reverse tcp:4533 tcp:4533` before any connected test runs,
 * so `http://localhost:4533` reaches it from inside the emulator.
 *
 * A [MockWebServer] sits in front of those bytes for one reason only: **a 429 has to be produced on
 * demand.** Making the real Navidrome emit one means configuring `Transcoding.MaxConcurrent` and
 * racing concurrent transcodes, which is flaky by construction. The status code is the only thing
 * faked here; the bytes, the decoder, the audio pipeline and the clock are all real.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayDataSourceFactoryTest {

  private lateinit var server: MockWebServer
  private lateinit var harness: PlayerHarness
  private lateinit var audio: ByteArray
  private val requestCount = AtomicInteger()

  @Before
  fun setUp() {
    audio = runBlocking { fetchRealTrackBytes() }
    // Not vacuous: a zero-length body would make every playback assertion below fail in a way
    // that looks like a decoder problem. Fail here instead, where the message is true.
    assertThat(audio.size).isGreaterThan(1000)

    server = MockWebServer()
    server.start()

    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val factory = MuPlayDataSourceFactory(OkHttpClient())
    // Built inside runOnMainSync: ExoPlayer.Builder captures the calling thread's Looper, and the
    // instrumentation thread has none. A violation throws
    // "Player is accessed on the wrong thread" -- clear, but only at the first access, which is
    // far from here.
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
          .setLoadErrorHandlingPolicy(NavidromeLoadErrorHandlingPolicy())
          .build(),
      )
    }
  }

  @After
  fun tearDown() {
    harness.release()
    server.close()
  }

  @Test
  fun realAudioPlaysAndThePositionAdvances() {
    server.enqueue(audioResponse())

    harness.onMain {
      harness.player.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
      harness.player.prepare()
      harness.player.play()
    }

    // The whole point. `play()` returning proves nothing; a position past one second of a
    // five-second track proves the bytes were fetched, the container was parsed, the decoder
    // produced samples and the clock advanced.
    harness.awaitPositionAtLeast(1_000L)
    harness.awaitEnded()
    assertThat(harness.onMain { harness.player.currentPosition }).isGreaterThan(4_000L)
  }

  @Test
  fun twoRefusalsWithHttp429DoNotKillThePlayback() {
    // Navidrome 0.62.0's `Transcoding.MaxConcurrent` answers 429 when every slot is busy. Spec
    // section 4: unhandled, this looks like random playback failure.
    server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "0")))
    server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "0")))
    server.enqueue(audioResponse())

    harness.onMain {
      harness.player.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
      harness.player.prepare()
      harness.player.play()
    }

    harness.awaitPositionAtLeast(1_000L)
    harness.assertNoPlaybackError()
    // Three requests reached the server: the two refusals and the one that carried audio. Without
    // this, a policy that gave up after the first 429 and a policy that retried correctly would be
    // told apart only by the position assertion above -- which is true but indirect.
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun aRefusalBudgetThatRunsOutSurfacesAsAPlayerError() {
    // The control that makes the previous test mean something. If 429s were being swallowed
    // rather than retried, *both* tests would pass. Six refusals exceeds MAX_RETRIES = 5.
    repeat(6) { server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "0"))) }

    harness.onMain {
      harness.player.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
      harness.player.prepare()
      harness.player.play()
    }

    var failed = false
    runCatching { harness.awaitPositionAtLeast(1_000L, timeoutMs = 15_000L) }
      .onFailure { failed = true }
    assertThat(failed).describedAs("playback should not have started off six refusals").isTrue
  }

  @Test
  fun theUserAgentThisClientSendsIsOnTheWire() {
    server.enqueue(audioResponse())

    harness.onMain {
      harness.player.setMediaItem(MediaItem.fromUri(server.url("/stream").toString()))
      harness.player.prepare()
      harness.player.play()
    }
    harness.awaitPositionAtLeast(500L)

    assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo(MuPlayDataSourceFactory.USER_AGENT)
  }

  private fun audioResponse(): MockResponse =
    MockResponse.Builder()
      .code(200)
      .header("Content-Type", "audio/mpeg")
      .header("Accept-Ranges", "bytes")
      .body(Buffer().write(audio))
      .build()

  /** One real track's bytes, off the real container, through the real stream URL. */
  private suspend fun fetchRealTrackBytes(): ByteArray {
    val client = SubsonicClient(
      SubsonicCredentials(baseUrl = NAVIDROME_URL, username = "admin", password = "testpass"),
    )
    val song = client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500)
      .first { it.suffix?.lowercase() == "mp3" }
    val request = Request.Builder().url(client.streamUrl(song.id, StreamFormat.Raw)).build()
    return OkHttpClient().newCall(request).execute().use { checkNotNull(it.body).bytes() }
  }

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
  }
}
```

> **On `MockResponse`:** this project uses `mockwebserver3-junit5` (OkHttp 5.5.0), whose
> `mockwebserver3.MockResponse` is an immutable type built either through its constructor
> (`MockResponse(code = ..., headers = ...)`) or `MockResponse.Builder()`. Both forms appear above
> deliberately. If a signature differs in the resolved version, fix the call, not the assertion —
> and note that `MockWebServer` is a **real HTTP server**, not a mock framework, which is why it is
> allowed here at all.

> **On threading:** every `ExoPlayer` in this plan is built and touched on the main `Looper`.
> `PlayerHarness.onMain` covers the touching; the construction has to be wrapped explicitly, as
> above, because the harness cannot wrap its own constructor argument. `androidx.test.platform.app.InstrumentationRegistry`
> needs importing in this test.

- [ ] **Step 9: Run the instrumented tests**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
# emulator up (see ci/prepare-emulator.sh's header for the required boot flags), then:
./ci/prepare-emulator.sh
./gradlew :core:media:connectedDebugAndroidTest
```

Expected: PASS, 4/4.

- [ ] **Step 10: Prove the tests can fail**

Each mutation applied alone, restored after, message recorded:

1. In `NavidromeLoadErrorHandlingPolicy.getRetryDelayMsFor`, delete the whole `if` body so every
   error uses `super`. Expect `twoRefusalsWithHttp429DoNotKillThePlayback` to fail — Media3's own
   policy retries three times, so this may still *pass* with `Retry-After: 0`. **If it does,
   change the enqueued refusals to seven and re-run**, and record which count discriminates. A
   test that cannot tell this policy from the default one is not testing this policy.
2. In `StreamRetryPolicy.retryDelayMs`, `return null` unconditionally. Expect the JVM suite to go
   red on six of its nine tests.
3. Point the player at a URL that 404s. Expect `realAudioPlaysAndThePositionAdvances` to fail with
   the `InvalidResponseCodeException` **named in the message** — this is what proves
   `PlayerHarness`'s error capture works, and it is worth doing once, because every later task in
   this plan depends on that message existing.
4. Serve a 200 with an empty body. Expect the setup assertion or the position assertion to fail,
   not a green run. A player that renders nothing must not look like a player that worked.

- [ ] **Step 11: Measure the floor and wire the workflow**

```bash
./gradlew :core:media:testDebugUnitTest :core:media:connectedDebugAndroidTest
./gradlew :core:media:jacocoTestReport
```

Read `core/media/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml` and set the real
numbers in `coverageFloors`. `StreamRetryPolicy` must clear 0.90 BRANCH **from JVM data alone** —
delete the `.ec` files and re-run `jacocoJvmCoverageVerification` to prove it, and leave
`requiresInstrumentedData = false` on that floor only if that run is green.

`.github/workflows/e2e.yml` — add `:core:media` to the connected-test line:

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest :app:connectedDebugAndroidTest || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

Measure the wall-clock time of the whole job afterwards. If it lands within ten minutes of
`timeout-minutes: 45`, raise the limit **and say what was measured** — a gate that starts flaking
on time gets disabled, which is the worst outcome available.

- [ ] **Step 12: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml core/media build.gradle.kts .github/workflows/e2e.yml
git commit -m "feat(media): :core:media, Media3 1.11.0 over OkHttp, and a real 429 policy"
```

---

## Task 3: The media cache, keyed on the track id alone

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/MediaCache.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/TrackIdCacheKeyFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlayDataSourceFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Modify: `core/media/src/androidTest/kotlin/app/muplay/media/MuPlayDataSourceFactoryTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/MediaCacheTest.kt`
- Modify: `build.gradle.kts` (`:core:media` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `MuPlayDataSourceFactory` and `PlayerHarness` from Task 2 —
  `PlayerHarness(player: ExoPlayer)` with `onMain`, `await(description, timeoutMs, condition)`,
  `awaitState(state, timeoutMs)`, `awaitPositionAtLeast(positionMs, timeoutMs)`,
  `awaitEnded(timeoutMs)`, `assertNoPlaybackError()`, `release()`.
- Produces:
  - `object TrackIdCacheKeyFactory : CacheKeyFactory` with
    `override fun buildCacheKey(dataSpec: DataSpec): String`
  - `class MissingCacheKeyException(uri: String) : IllegalStateException`
  - `object MediaCache` with `fun create(context: Context): Cache`,
    `const val DIRECTORY_NAME = "media"`, `const val MAX_BYTES = 512L * 1024 * 1024`
  - `MuPlayDataSourceFactory` constructor becomes
    `@Inject constructor(callFactory: Call.Factory, cache: Cache)`; `create()` now returns a
    `CacheDataSource.Factory`
  - `MediaModule` provides `@Singleton Cache`

### The Tempo defect, stated precisely

Spec §4: *"**Cache key must derive from the track id alone** via `setCustomCacheKey`. Tempo omits
this, so its key includes the auth token and bitrate — changing bitrate orphans the entire
cache."*

Media3's default `CacheKeyFactory` is `CacheKeyFactory.DEFAULT`, which returns
`dataSpec.key != null ? dataSpec.key : dataSpec.uri.toString()`. That fallback is the whole bug.
This client's stream URLs carry a **fresh salt on every call** (Task 1 pins that as an assertion),
so a URL-derived key is different every single time — the cache is written, never read, and grows
without ever serving a byte. It is not a degraded cache; it is a cache with a 0% hit rate that
still consumes disk.

`MediaItem.setCustomCacheKey(song.id)` (Task 4) populates `dataSpec.key`. The defence here is that
**the fallback is removed**: `TrackIdCacheKeyFactory` throws when `dataSpec.key` is null rather
than quietly using the URI. A missing custom cache key then fails a test loudly on the first run
instead of manifesting six months later as "the offline cache does not seem to do anything".

### The cache and `format=raw` are the same decision

A `CacheDataSource` can only cache a *bounded* resource. Spec §4, verified against a real
container: `format=raw` always sends an accurate `Content-Length`, while a **live transcode sends
none** and refuses ranges. So a transcoded stream is not merely unseekable — it is also
uncacheable as a complete resource. Task 1 already prefers raw for the seek bar; this task is the
second reason, and it is worth saying once so that nobody later "optimises" bandwidth by
defaulting to mp3.

### Why the cache is a process singleton, and why `cacheDir`

`SimpleCache` **throws** if a second instance is constructed for a directory a live instance
already holds (`IllegalStateException`, "Another SimpleCache instance uses the folder"). `@Singleton`
here is therefore a correctness requirement, not a performance choice — exactly the same
situation, and the same reasoning, as `DataModule.provideCredentialDataStore`'s note about
DataStore refusing a second instance for one file.

`context.cacheDir` rather than `filesDir`: the OS may reclaim it under storage pressure, and that
is the correct trade for a read-through cache of data the server still has. Losing it costs a
re-download and never a wrong answer. Downloads that must survive are Plan-deferred (spec §9) and
would use a different directory and a different service entirely.

- [ ] **Step 1: Write the failing cache tests**

`core/media/src/androidTest/kotlin/app/muplay/media/MediaCacheTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cache, measured rather than asserted-by-flag.
 *
 * Every "did the cache work" claim here is a **request count on a real HTTP server**. A test that
 * asked the `Cache` object whether it holds a key would pass against a cache that is never read
 * from, which is exactly the defect this task exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class MediaCacheTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var cache: Cache
  private lateinit var server: MockWebServer
  private lateinit var audio: ByteArray
  private lateinit var otherAudio: ByteArray

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // A per-test directory: SimpleCache refuses a second live instance on one folder, and a
    // shared folder would make these tests depend on the order they ran in.
    cacheDir = File(context.cacheDir, "media-test-${System.nanoTime()}")
    cache = MediaCache.create(context, cacheDir)
    server = MockWebServer()
    server.start()

    val bytes = runBlocking { RealTrackBytes.twoDifferentTracks() }
    audio = bytes.first
    otherAudio = bytes.second
    assertThat(audio.size).isGreaterThan(1000)
    assertThat(otherAudio.size).isGreaterThan(1000)
    // The two tracks must be genuinely different, or "served the wrong track from cache" is
    // undetectable below.
    assertThat(audio).isNotEqualTo(otherAudio)
  }

  @After
  fun tearDown() {
    cache.release()
    cacheDir.deleteRecursively()
    server.close()
  }

  @Test
  fun theCacheKeyIsTheCustomKeyAndNotTheUri() {
    val first = DataSpec.Builder()
      .setUri("http://host/rest/stream?id=track-1&t=aaa&s=111")
      .setKey("track-1").build()
    val second = DataSpec.Builder()
      // Same track, different salt, different token, different bitrate -- i.e. everything Tempo's
      // URL-derived key would treat as a different resource.
      .setUri("http://host/rest/stream?id=track-1&t=zzz&s=999&maxBitRate=96")
      .setKey("track-1").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(first)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(second)).isEqualTo("track-1")
  }

  @Test
  fun twoTracksDoNotShareACacheKey() {
    // The other direction. A `buildCacheKey` that returned a constant would pass the test above
    // and fail this one; one that returned the URI would pass this one and fail the test above.
    val a = DataSpec.Builder().setUri("http://host/a").setKey("track-1").build()
    val b = DataSpec.Builder().setUri("http://host/b").setKey("chapter-14").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(a)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(b)).isEqualTo("chapter-14")
  }

  /**
   * Media3's `CacheKeyFactory.DEFAULT` falls back to the URI when no custom key is set. That
   * fallback is the entire Tempo defect: this client's URLs carry a fresh salt every time, so the
   * fallback silently produces a cache with a 0% hit rate that still consumes disk. Failing loudly
   * is the point.
   */
  @Test
  fun aDataSpecWithNoCustomCacheKeyIsRejectedRatherThanFallingBackToTheUri() {
    val noKey = DataSpec.Builder().setUri("http://host/rest/stream?id=track-1&s=111").build()

    assertThatExceptionOfType(MissingCacheKeyException::class.java)
      .isThrownBy { TrackIdCacheKeyFactory.buildCacheKey(noKey) }
      .withMessageContaining("setCustomCacheKey")
  }

  /**
   * **The measurement that matters.** Play a track, then play it again through a URL that differs
   * in exactly the ways this client's URLs really differ — a new salt, a new token, a bitrate cap
   * — and require that **not one further byte** is fetched.
   *
   * Holding the track id constant while varying everything else in the URL is what makes this
   * discriminating: a cache keyed on the URL passes the first playback and fails here.
   */
  @Test
  fun replayingATrackThroughADifferentUrlFetchesNothingFurther() {
    server.enqueue(audioResponse(audio))

    playToEnd(uri = server.url("/stream?id=track-1&t=aaa&s=111").toString(), cacheKey = "track-1")
    assertThat(server.requestCount).isEqualTo(1)

    playToEnd(
      uri = server.url("/stream?id=track-1&t=zzz&s=999&maxBitRate=96").toString(),
      cacheKey = "track-1",
    )

    // No second response was ever enqueued: had the player gone to the network, MockWebServer
    // would have blocked and the playback would have timed out rather than merely fetching twice.
    // Both facts are asserted, because either one alone leaves a way for this to pass wrongly.
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun aDifferentTrackIsNotServedFromAnotherTracksCache() {
    // The control. Without it, a cache that returned the first track's bytes for every key would
    // pass the test above perfectly.
    server.enqueue(audioResponse(audio))
    server.enqueue(audioResponse(otherAudio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")
    playToEnd(server.url("/stream?id=track-2").toString(), cacheKey = "track-2")

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(cache.keys).contains("track-1", "track-2")
  }

  @Test
  fun theCachedBytesOnDiskAreTheWholeTrack() {
    server.enqueue(audioResponse(audio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")

    // Direct evidence, alongside the request count: the cache holds the complete resource, not a
    // prefix that happened to satisfy a short playback.
    assertThat(cache.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
    assertThat(cache.cacheSpace).isGreaterThanOrEqualTo(audio.size.toLong())
  }

  private fun audioResponse(bytes: ByteArray): MockResponse =
    MockResponse.Builder()
      .code(200)
      .header("Content-Type", "audio/mpeg")
      .header("Accept-Ranges", "bytes")
      .body(Buffer().write(bytes))
      .build()

  /** Builds a fresh player over the shared cache, plays one item to the end, and releases it. */
  private fun playToEnd(uri: String, cacheKey: String) {
    val factory = MuPlayDataSourceFactory(OkHttpClient(), cache)
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
          .build(),
      )
    }
    try {
      harness.onMain {
        harness.player.setMediaItem(
          MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build(),
        )
        harness.player.prepare()
        harness.player.play()
      }
      // Position, then ENDED: "reached the end" alone is satisfied by a zero-length source.
      harness.awaitPositionAtLeast(1_000L)
      harness.awaitEnded()
    } finally {
      harness.release()
    }
  }
}
```

and the small helper the two tracks come from —
`core/media/src/androidTest/kotlin/app/muplay/media/RealTrackBytes.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Real audio, off the real container, through the real stream URL.
 *
 * Not a fixture copied into `src/androidTest/assets`: `ci/seed-fixtures.sh` builds those four
 * files and `ci/fixtures.md5` pins them, and a second copy is a second thing to keep in sync that
 * nothing checks. `.github/workflows/e2e.yml` starts the container and `ci/prepare-emulator.sh`
 * sets up `adb reverse tcp:4533 tcp:4533`, so `http://localhost:4533` reaches it from the guest.
 */
object RealTrackBytes {

  const val NAVIDROME_URL = "http://localhost:4533"
  const val MUSIC_LIBRARY_ID = 1
  const val AUDIOBOOK_LIBRARY_ID = 2

  fun client(): SubsonicClient = SubsonicClient(
    SubsonicCredentials(baseUrl = NAVIDROME_URL, username = "admin", password = "testpass"),
  )

  /** The three seeded music tracks, in title order — "Track 1", "Track 2", "Track 3". */
  suspend fun musicTracks(): List<app.muplay.model.Song> =
    client().getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).sortedBy { it.title }

  suspend fun bytesOf(song: app.muplay.model.Song): ByteArray {
    val request = Request.Builder().url(client().streamUrl(song.id, StreamFormat.Raw)).build()
    return OkHttpClient().newCall(request).execute().use { checkNotNull(it.body).bytes() }
  }

  /** Two genuinely different tracks' bytes — the pair `MediaCacheTest` needs for its control. */
  suspend fun twoDifferentTracks(): Pair<ByteArray, ByteArray> {
    val tracks = musicTracks()
    check(tracks.size >= 2) { "the seeded music library must hold at least two tracks, found ${tracks.size}" }
    return bytesOf(tracks[0]) to bytesOf(tracks[1])
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*MediaCacheTest*'`
Expected: FAIL — `Unresolved reference: MediaCache`, `TrackIdCacheKeyFactory`.

- [ ] **Step 3: Implement the cache and the key factory**

`core/media/src/main/kotlin/app/muplay/media/TrackIdCacheKeyFactory.kt`:

```kotlin
package app.muplay.media

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Thrown when a [DataSpec] reaches the cache with no custom cache key.
 *
 * A distinct type rather than a bare `IllegalStateException` so a test can assert on it and a
 * reader can find every throw site by its name.
 */
class MissingCacheKeyException(uri: String) : IllegalStateException(
  "A media request reached the cache with no custom cache key: $uri. Every MediaItem this app " +
    "plays must set setCustomCacheKey(song.id) -- see MediaItems.kt. Media3's default factory " +
    "would fall back to the URI here, and this client's stream URLs carry a fresh auth salt on " +
    "every call, so a URL-derived key produces a cache that is written, never read, and grows " +
    "forever. That is the defect Tempo ships and spec section 4 names.",
)

/**
 * The cache key is the **track id**, and nothing else.
 *
 * Media3's `CacheKeyFactory.DEFAULT` returns `dataSpec.key` when present and the URI otherwise.
 * The fallback is removed here on purpose: a missing key is a programming error that this project
 * would otherwise discover as an unexplained 0% hit rate, months later, on a device.
 */
object TrackIdCacheKeyFactory : CacheKeyFactory {
  override fun buildCacheKey(dataSpec: DataSpec): String =
    dataSpec.key ?: throw MissingCacheKeyException(dataSpec.uri.toString())
}
```

`core/media/src/main/kotlin/app/muplay/media/MediaCache.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The one read-through media cache in the process.
 *
 * **Exactly one**, and that is a correctness requirement rather than a performance choice:
 * `SimpleCache` throws if a second live instance is constructed for a directory another instance
 * already holds. `MediaModule` provides it `@Singleton` for that reason — the same situation, and
 * the same reasoning, as `DataModule`'s note about DataStore refusing a second instance for one
 * file.
 *
 * The directory lives under `context.cacheDir`, which the OS may reclaim under storage pressure.
 * That is the right trade for a cache of data the server still has: losing it costs a re-download
 * and never a wrong answer. Downloads that must survive are deferred by spec section 9 and would
 * need a different directory and a different service.
 */
object MediaCache {

  /** Under `cacheDir`. Named, not derived, so an on-device inspection knows what it is looking at. */
  const val DIRECTORY_NAME: String = "media"

  /**
   * 512 MiB. Large enough to hold a long listening session and several audiobooks; small enough
   * that it is a fraction of any device this app targets. Eviction is least-recently-used, so the
   * book being listened to survives a shuffle session.
   */
  const val MAX_BYTES: Long = 512L * 1024L * 1024L

  fun create(context: Context, directory: File = File(context.cacheDir, DIRECTORY_NAME)): Cache =
    SimpleCache(
      directory,
      LeastRecentlyUsedCacheEvictor(MAX_BYTES),
      StandaloneDatabaseProvider(context),
    )
}
```

> `StandaloneDatabaseProvider` lives in `androidx.media3:media3-database`, which
> `media3-datasource` depends on transitively. If the import does not resolve, add
> `media3-database` to the catalogue and to this module — do **not** reach for
> `ExoDatabaseProvider`, which was its ExoPlayer 2 name and does not exist in Media3.

`core/media/src/main/kotlin/app/muplay/media/MuPlayDataSourceFactory.kt` — replace the class body:

```kotlin
@Singleton
class MuPlayDataSourceFactory @Inject constructor(
  private val callFactory: Call.Factory,
  private val cache: Cache,
) {

  /**
   * A cache-backed `DataSource.Factory`: read from [cache] where possible, fall through to HTTP
   * otherwise, and write what HTTP returns back into the cache.
   *
   * `FLAG_IGNORE_CACHE_ON_ERROR` is set so that a corrupt or unreadable cache entry degrades to a
   * network read rather than failing the track. The failure mode being avoided is a cache that,
   * once damaged, permanently breaks one specific song with no way for a user to tell why.
   */
  fun create(): DataSource.Factory =
    CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(callFactory).setUserAgent(USER_AGENT))
      .setCacheKeyFactory(TrackIdCacheKeyFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

  companion object {
    const val USER_AGENT: String = "MuPlay"
  }
}
```

with imports for `androidx.media3.datasource.cache.Cache` and
`androidx.media3.datasource.cache.CacheDataSource`.

`core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` — add:

```kotlin
  /**
   * One `SimpleCache` per process. `@Singleton` is load-bearing: a second instance on the same
   * directory throws. See [MediaCache]'s own documentation.
   */
  @Provides
  @Singleton
  fun provideMediaCache(@ApplicationContext context: Context): Cache = MediaCache.create(context)
```

with imports for `android.content.Context`, `androidx.media3.datasource.cache.Cache`,
`app.muplay.media.MediaCache` and `dagger.hilt.android.qualifiers.ApplicationContext`.

- [ ] **Step 4: Update Task 2's test for the new constructor and the shared fixture helper**

`MuPlayDataSourceFactoryTest` has a private `fetchRealTrackBytes()` that does what
`RealTrackBytes.bytesOf` now does. Delete the private copy and call the shared object — two ways to
fetch the same bytes is two things to keep pointing at the same container.


`MuPlayDataSourceFactoryTest` constructs `MuPlayDataSourceFactory(OkHttpClient())`. It now needs a
cache. Give that test its **own** per-test cache directory (so it neither shares nor evicts
`MediaCacheTest`'s) and release it in `@After`:

```kotlin
  private lateinit var cacheDir: File
  private lateinit var cache: Cache

  // in setUp(), before building the player:
  cacheDir = File(context.cacheDir, "datasource-test-${System.nanoTime()}")
  cache = MediaCache.create(context, cacheDir)
  val factory = MuPlayDataSourceFactory(OkHttpClient(), cache)

  // in tearDown():
  cache.release()
  cacheDir.deleteRecursively()
```

`twoRefusalsWithHttp429DoNotKillThePlayback` and `aRefusalBudgetThatRunsOutSurfacesAsAPlayerError`
now run through a cache, which is fine — nothing is cached on a 429 — but
`realAudioPlaysAndThePositionAdvances` and `theUserAgentThisClientSendsIsOnTheWire` must use
**distinct** cache keys per test or the second one to run reads from the first one's cache and
never reaches MockWebServer. Give each test its own key via
`MediaItem.Builder().setUri(...).setCustomCacheKey("datasource-test-<name>").build()`. This is not
incidental tidying: it is the first time in this plan that a cache silently satisfying a request
would make a test lie, and it will not be the last.

- [ ] **Step 5: Run both instrumented suites**

```bash
./gradlew :core:media:connectedDebugAndroidTest
```

Expected: PASS — `MuPlayDataSourceFactoryTest` 4/4, `MediaCacheTest` 6/6.

- [ ] **Step 6: Prove the cache tests can fail**

1. Replace `TrackIdCacheKeyFactory` with `CacheKeyFactory.DEFAULT` in
   `MuPlayDataSourceFactory.create()`. Expect
   `replayingATrackThroughADifferentUrlFetchesNothingFurther` to fail — the second playback goes
   to the network and hangs on an empty MockWebServer queue. **This is the Tempo defect,
   reproduced on demand.** Record the failure message.
2. In `TrackIdCacheKeyFactory.buildCacheKey`, `return "constant"`. Expect
   `twoTracksDoNotShareACacheKey` and `aDifferentTrackIsNotServedFromAnotherTracksCache` to fail.
3. In `TrackIdCacheKeyFactory.buildCacheKey`, `return dataSpec.key ?: dataSpec.uri.toString()`.
   Expect `aDataSpecWithNoCustomCacheKeyIsRejectedRatherThanFallingBackToTheUri` to fail — this is
   the exact line the task exists to not write.
4. Remove `.setCache(cache)` so nothing is cached. Expect
   `theCachedBytesOnDiskAreTheWholeTrack` and the replay test to fail.

- [ ] **Step 7: Record the probes, re-measure, commit**

Add probes 1–3 above to `ci/mutation-probes.sh`'s `PROBES` table (they run on the device, so mark
them as such in the entry — read the script's header for the table's shape and its explicit scope
note about JVM-only probes; if the table cannot express a device probe, record these in the task
report and say so rather than adding an entry the runner would silently skip).

Re-measure `:core:media`'s floors from a merged report and update `coverageFloors`.
`TrackIdCacheKeyFactory` and `MediaCache` are instrumented-only, so their floors carry
`requiresInstrumentedData = true`; confirm that by deleting the `.ec` files and watching
`jacocoJvmCoverageVerification` stay green with them set, and go red with them unset.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): read-through media cache keyed on the track id alone"
```

---

## Task 4: `Song` → `MediaItem`, and the queue as a list of pointers

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackQueue.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt`
- Modify: `core/media/build.gradle.kts` (depend on `:core:database`)
- Test: `core/media/src/test/kotlin/app/muplay/media/PlaybackQueueTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/MediaItemsTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/QueueRepositoryTest.kt`
- Modify: `build.gradle.kts` (`:core:media` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - `app.muplay.model.Song(id, libraryId, title, albumId, albumName, artistId, artistName,
    trackNumber, discNumber, durationSeconds, suffix, coverArtId)` — `:core:model`, committed.
  - `app.muplay.model.StreamFormat.forSuffix(suffix, transcodeBitRateKbps)` and
    `StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS` — Task 1.
  - `app.muplay.network.SubsonicSource.streamUrl(songId, format)` — Task 1 — and
    `.coverArtUrl(coverArtId, sizePx)` — Plan 2 Task 3, committed.
  - **`app.muplay.database.SubsonicSourceProvider.current(): SubsonicSource`** — **Plan 2 Task 4.**
    If that class landed under a different name, use the real one and record it in the task
    report; do **not** add a second provider or reach past it to `CredentialStore`.
  - `app.muplay.network.SubsonicSourceFactory` (a `fun interface`) — used to build the fake in
    `QueueRepositoryTest`.
- Produces:
  - `data class PlaybackQueue(val songs: List<Song>, val startIndex: Int)` with `val size: Int`,
    `fun songAt(index: Int): Song`, `companion object { fun of(songs: List<Song>, startIndex: Int = 0) }`
  - `object MediaItems` with
    `fun of(song: Song, streamUri: String, artworkUri: String?): MediaItem`
  - `class QueueRepository @Inject constructor(sourceProvider: SubsonicSourceProvider)` with
    `suspend fun mediaItems(queue: PlaybackQueue): List<MediaItem>` and
    `companion object { const val ARTWORK_SIZE_PX = 512 }`

### The queue is a list of pointers, and this is where that is enforced

Spec §3, the core architectural decision: *"The queue is a list of pointers. Progress is a
property of the item."* Plan 2 built the other half — `media_progress`, keyed on the server's
stable media id, with the standing prohibition that nothing about queue membership may live in it.

This task builds the queue, and the same prohibition runs the other way: **`PlaybackQueue` carries
no position.** Not `positionMs`, not `elapsed`, not `resumeFrom`. A queue that knew where it was
would be the single global "now playing position" that every other player has, and that
overwriting is precisely why the user cannot listen to music between two audiobook sessions
without losing their place.

That is not a rule a comment can keep. `PlaybackQueueTest` asserts the type's **declared fields**
are exactly `songs` and `startIndex`, so adding a third one fails a test with a message that
explains why. `startIndex` is not a position: it says which *item* to start with, which is queue
membership, not progress.

### Why the field-by-field mapping test runs on the device

`MediaItem` and `MediaMetadata` are built on `android.net.Uri`, which throws on a plain JVM
without Robolectric — and Robolectric is banned. So this mapping's tests are instrumented, and
they carry the full field-level rigour that would otherwise have lived in Tier 1: **every field is
observed at two different values**, because a field observed once is satisfied by a constant and
this project has shipped that defect four times running.

Splitting the mapping into a pure "spec" object and a thin builder — the trick that put
`StreamRetryPolicy` in Tier 1 — was considered and rejected here: the spec object would be a
field-for-field duplicate of `MediaMetadata` with no decision of its own in it, so the split would
add a type and move zero logic. The `429` policy earned its split because it contained arithmetic;
this does not.

- [ ] **Step 1: Write the failing queue test**

`core/media/src/test/kotlin/app/muplay/media/PlaybackQueueTest.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class PlaybackQueueTest {

  private fun song(id: String) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = null,
  )

  @Test
  fun `a queue holds the songs it was given in the order it was given them`() {
    val queue = PlaybackQueue.of(listOf(song("a"), song("b"), song("c")))

    assertThat(queue.songs.map { it.id }).containsExactly("a", "b", "c")
    assertThat(queue.size).isEqualTo(3)
  }

  @Test
  fun `the start index is the one the caller asked for`() {
    // Two observations. A `startIndex` hardcoded to 0 -- the obvious accident -- passes the first
    // of these and fails the second, which is the entire reason both are here.
    assertThat(PlaybackQueue.of(listOf(song("a"), song("b")), startIndex = 0).startIndex).isZero
    assertThat(PlaybackQueue.of(listOf(song("a"), song("b")), startIndex = 1).startIndex).isEqualTo(1)
  }

  @Test
  fun `songAt returns the song at that index`() {
    val queue = PlaybackQueue.of(listOf(song("a"), song("b"), song("c")))

    assertThat(queue.songAt(0).id).isEqualTo("a")
    assertThat(queue.songAt(2).id).isEqualTo("c")
  }

  @Test
  fun `an empty queue is rejected`() {
    // "Play nothing" is not a request a caller can make by accident and have silently succeed:
    // an empty setMediaItems leaves the session in a state where the notification shows a track
    // that is not there.
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(emptyList()) }
      .withMessageContaining("empty")
  }

  @Test
  fun `a start index outside the queue is rejected`() {
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(listOf(song("a")), startIndex = 1) }
      .withMessageContaining("startIndex")
    assertThatIllegalArgumentException()
      .isThrownBy { PlaybackQueue.of(listOf(song("a")), startIndex = -1) }
      .withMessageContaining("startIndex")
  }

  /**
   * Spec section 3's core architectural decision, asserted structurally rather than trusted to a
   * comment: **the queue is a list of pointers, and progress is a property of the item.**
   *
   * A `positionMs` on this type would be the single global "now playing position" that every other
   * player has and that the next thing played overwrites — the exact reason a user cannot listen
   * to music between two audiobook sessions without losing their place. `startIndex` is *not* a
   * position: it names an item, which is queue membership, not progress.
   *
   * `declaredFields` rather than Kotlin reflection, so this needs no `kotlin-reflect` dependency.
   * A new property therefore fails this test with a message that says what to do instead: put it
   * on `media_progress`, keyed by the media id.
   */
  @Test
  fun `the queue carries no playback position of its own`() {
    val fields = PlaybackQueue::class.java.declaredFields
      .filterNot { it.isSynthetic }
      .map { it.name }

    assertThat(fields)
      .describedAs(
        "PlaybackQueue must stay a list of pointers (spec section 3). Progress belongs on " +
          "media_progress, keyed by media id -- never on the queue.",
      )
      .containsExactlyInAnyOrder("songs", "startIndex")
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*PlaybackQueueTest*'`
Expected: FAIL — `Unresolved reference: PlaybackQueue`.

- [ ] **Step 3: Implement `PlaybackQueue`**

`core/media/src/main/kotlin/app/muplay/media/PlaybackQueue.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Song

/**
 * What to play, and which item to start with. **Nothing else.**
 *
 * Spec section 3: *the queue is a list of pointers; progress is a property of the item.* This type
 * therefore carries no position, and `PlaybackQueueTest` asserts its declared fields to keep it
 * that way. A `positionMs` here would be the single global "now playing position" that the next
 * thing played overwrites — which is the specific defect that makes every other player lose an
 * audiobook's place after a music session.
 *
 * [startIndex] is not a position. It names an item, which is queue membership; where playback
 * begins *within* that item is `media_progress`'s answer and `MuPlayer`'s to apply (Task 8).
 *
 * Constructed through [of], which validates. The constructor is not private — a `data class` with
 * a private constructor loses `copy` — but the `init` block runs either way, so there is no path
 * to an invalid queue.
 */
data class PlaybackQueue(val songs: List<Song>, val startIndex: Int) {

  init {
    require(songs.isNotEmpty()) { "a playback queue cannot be empty" }
    require(startIndex in songs.indices) {
      "startIndex $startIndex is outside a queue of ${songs.size}"
    }
  }

  val size: Int get() = songs.size

  fun songAt(index: Int): Song = songs[index]

  companion object {
    fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*PlaybackQueueTest*'`
Expected: PASS, 6/6.

- [ ] **Step 5: Write the failing `MediaItem` mapping test**

`core/media/src/androidTest/kotlin/app/muplay/media/MediaItemsTest.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Song` → `MediaItem` mapping, field by field.
 *
 * **Every field is observed at two different values**, in one assertion per field, by mapping two
 * deliberately dissimilar songs and asserting the resulting *pair*. That shape is not stylistic:
 * a mapped field replaced by a hardcoded constant is the defect this project found four times in
 * a row on Plan 2 Task 3, and it survives any test that looks at one input.
 *
 * Instrumented rather than JVM because `MediaItem` is built on `android.net.Uri`, which throws
 * off-device, and Robolectric is banned. The rigour moves with the test; it does not get dropped.
 */
@RunWith(AndroidJUnit4::class)
class MediaItemsTest {

  private val first = Song(
    id = "song-1",
    libraryId = 1,
    title = "Track 1",
    albumId = "album-1",
    albumName = "Test Album",
    artistId = "artist-1",
    artistName = "Test Artist",
    trackNumber = 1,
    discNumber = 1,
    durationSeconds = 5,
    suffix = "mp3",
    coverArtId = "art-1",
  )

  private val second = Song(
    id = "chapter-14",
    libraryId = 2,
    title = "Chapter 14",
    albumId = "album-2",
    albumName = "Test Book",
    artistId = "artist-2",
    artistName = "Test Author",
    trackNumber = 14,
    discNumber = 2,
    durationSeconds = 900,
    suffix = "m4b",
    coverArtId = "art-2",
  )

  private val firstItem = MediaItems.of(first, "https://host/rest/stream?id=song-1&s=aaa", "https://host/art-1")
  private val secondItem = MediaItems.of(second, "https://host/rest/stream?id=chapter-14&s=bbb", "https://host/art-2")

  private fun <T> pair(select: (MediaItem) -> T): List<T> = listOf(select(firstItem), select(secondItem))

  @Test
  fun theMediaIdIsTheSongId() {
    // The single most important field in the app: `media_progress` is keyed on it, so a constant
    // here would make every book share one position row.
    assertThat(pair { it.mediaId }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theUriIsTheStreamUrlItWasGiven() {
    assertThat(pair { it.localConfiguration?.uri?.toString() })
      .containsExactly("https://host/rest/stream?id=song-1&s=aaa", "https://host/rest/stream?id=chapter-14&s=bbb")
  }

  /**
   * The cache key is the **track id**, never the URI — spec section 4, and the defect Tempo ships.
   * This client's stream URLs carry a fresh auth salt per call (`StreamUrlTest` pins that), so a
   * URL-derived key produces a cache with a 0% hit rate. `TrackIdCacheKeyFactory` refuses a
   * `DataSpec` with no key at all; this is where the key is actually put on.
   */
  @Test
  fun theCustomCacheKeyIsTheSongIdAndNotTheUri() {
    assertThat(pair { it.localConfiguration?.customCacheKey }).containsExactly("song-1", "chapter-14")
  }

  @Test
  fun theTitleIsTheSongTitle() {
    assertThat(pair { it.mediaMetadata.title?.toString() }).containsExactly("Track 1", "Chapter 14")
  }

  @Test
  fun theArtistIsTheSongArtist() {
    assertThat(pair { it.mediaMetadata.artist?.toString() }).containsExactly("Test Artist", "Test Author")
  }

  @Test
  fun theAlbumTitleIsTheSongAlbum() {
    assertThat(pair { it.mediaMetadata.albumTitle?.toString() }).containsExactly("Test Album", "Test Book")
  }

  @Test
  fun theTrackNumberIsTheSongTrackNumber() {
    assertThat(pair { it.mediaMetadata.trackNumber }).containsExactly(1, 14)
  }

  @Test
  fun theDiscNumberIsTheSongDiscNumber() {
    assertThat(pair { it.mediaMetadata.discNumber }).containsExactly(1, 2)
  }

  @Test
  fun theArtworkUriIsTheOneItWasGiven() {
    assertThat(pair { it.mediaMetadata.artworkUri?.toString() })
      .containsExactly("https://host/art-1", "https://host/art-2")
  }

  @Test
  fun aSongWithNoArtworkGetsNoArtworkUriRatherThanAPlaceholder() {
    val item = MediaItems.of(first.copy(coverArtId = null), "https://host/stream", artworkUri = null)

    assertThat(item.mediaMetadata.artworkUri).isNull()
    // ...and the rest of the mapping is unaffected, so "no artwork" is not silently "no metadata".
    assertThat(item.mediaMetadata.title?.toString()).isEqualTo("Track 1")
  }

  @Test
  fun absentTrackAndDiscNumbersStayAbsent() {
    // Navidrome omits these for a single-file audiobook. Mapping a missing number to 0 would put
    // "0" on a lock screen and sort a book above every real track.
    val item = MediaItems.of(first.copy(trackNumber = null, discNumber = null), "https://host/s", null)

    assertThat(item.mediaMetadata.trackNumber).isNull()
    assertThat(item.mediaMetadata.discNumber).isNull()
  }

  @Test
  fun everyItemIsPlayableAndNotBrowsable() {
    // Fixed values rather than mapped ones, so the two-observation rule does not apply -- but they
    // are load-bearing: Android Auto (Plan 5) renders a browse tree from exactly these flags, and
    // an item marked browsable shows up as a folder that opens onto nothing.
    assertThat(pair { it.mediaMetadata.isPlayable }).containsExactly(true, true)
    assertThat(pair { it.mediaMetadata.isBrowsable }).containsExactly(false, false)
    assertThat(pair { it.mediaMetadata.mediaType })
      .containsExactly(MediaMetadata.MEDIA_TYPE_MUSIC, MediaMetadata.MEDIA_TYPE_MUSIC)
  }

  /**
   * Navidrome hardcodes `child.Type = "music"` for every media file — the seeded `Test Book.m4b`
   * comes back as `"type": "music"` — so `MEDIA_TYPE_MUSIC` above is not this app agreeing that a
   * book is music; it is the only value the protocol supports, and the library id is what actually
   * distinguishes them. Recorded here so nobody later "fixes" it by inferring a book from a
   * suffix.
   */
  @Test
  fun theMediaTypeIsNotAnAudiobookInferenceAndTheSuffixDoesNotChangeIt() {
    assertThat(MediaItems.of(second, "https://host/s", null).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*MediaItemsTest*'`
Expected: FAIL — `Unresolved reference: MediaItems`.

- [ ] **Step 7: Implement the mapping**

`core/media/src/main/kotlin/app/muplay/media/MediaItems.kt`:

```kotlin
package app.muplay.media

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.muplay.model.Song

/**
 * Turns one mirrored [Song] into the `MediaItem` Media3 plays.
 *
 * Three of the values here are load-bearing well beyond this file:
 *
 * - **`mediaId = song.id`.** `media_progress` is keyed on the server's stable media id, and
 *   `MuPlayer` (Task 8) looks a row up by exactly this value. A constant here would make every
 *   audiobook share one position.
 * - **`customCacheKey = song.id`.** Spec section 4: the cache key must derive from the track id
 *   alone. This client's stream URLs carry a fresh auth salt per call, so Media3's default
 *   URL-derived key produces a cache that is written and never read — the defect Tempo ships.
 * - **`mediaType = MEDIA_TYPE_MUSIC`, always.** Not this app agreeing that an audiobook is music:
 *   Navidrome hardcodes `child.Type = "music"` for every media file, so the protocol offers no
 *   other answer, and the library id is what actually distinguishes a book. Do not "fix" this by
 *   inferring a book from a file suffix.
 *
 * [artworkUri] is passed in rather than derived, because building it needs credentials and this
 * function is pure. [QueueRepository] is where the two are joined.
 *
 * A note on artwork and the salt: like the stream URL, a cover-art URL carries a fresh salt, so
 * the same art gets a different URI in a later session. Media3's session bitmap loader caches by
 * URI, so that costs **one artwork fetch per session per item** and never a wrong image. Within a
 * queue the URI is fixed, because it is built once here.
 */
object MediaItems {

  fun of(song: Song, streamUri: String, artworkUri: String?): MediaItem =
    MediaItem.Builder()
      .setMediaId(song.id)
      .setUri(streamUri)
      .setCustomCacheKey(song.id)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(song.title)
          .setArtist(song.artistName)
          .setAlbumTitle(song.albumName)
          .setTrackNumber(song.trackNumber)
          .setDiscNumber(song.discNumber)
          .setArtworkUri(artworkUri?.toUri())
          .setIsPlayable(true)
          // Android Auto (Plan 5) renders its browse tree from these flags; an item marked
          // browsable becomes a folder that opens onto nothing.
          .setIsBrowsable(false)
          .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
          .build(),
      )
      .build()
}
```

> `androidx.core.net.toUri` comes from `androidx.core:core-ktx`, which every Android module here
> already resolves transitively through Compose/AppCompat. If it does not resolve in
> `:core:media`, use `android.net.Uri.parse(artworkUri)` rather than adding a dependency for one
> extension function.

- [ ] **Step 8: Write the failing `QueueRepository` test**

`core/media/src/androidTest/kotlin/app/muplay/media/QueueRepositoryTest.kt`:

```kotlin
package app.muplay.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The join between a queue of mirrored songs and the URLs Media3 needs.
 *
 * The `SubsonicSource` here is a **hand-written fake**, not a mock: it records the arguments it was
 * called with and answers deterministically. That is what lets this test assert the *format
 * decision* — the one place "never Opus" is actually made — without a server that has an Opus file
 * in it. There is no Opus in the CI corpus and there is not going to be one.
 */
@RunWith(AndroidJUnit4::class)
class QueueRepositoryTest {

  private class RecordingSource : SubsonicSource {
    val streamCalls = mutableListOf<Pair<String, StreamFormat>>()
    val coverArtCalls = mutableListOf<Pair<String, Int?>>()

    override fun streamUrl(songId: String, format: StreamFormat): String {
      streamCalls += songId to format
      return "https://host/rest/stream?id=$songId&format=${format.wireValue}"
    }

    override fun coverArtUrl(coverArtId: String, sizePx: Int?): String {
      coverArtCalls += coverArtId to sizePx
      return "https://host/rest/getCoverArt?id=$coverArtId&size=$sizePx"
    }

    // Everything else on the port is out of this test's scope. `error(...)` rather than a benign
    // default: a call that should never happen must fail loudly rather than return something
    // plausible that the test would then be quietly asserting about.
    override suspend fun ping(): ServerInfo = error("not used by QueueRepositoryTest")
    override suspend fun getMusicFolders(): List<MusicLibrary> = error("not used by QueueRepositoryTest")
    override suspend fun getScanStatus(): ScanStatus = error("not used by QueueRepositoryTest")
    override suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album> = error("not used by QueueRepositoryTest")
    override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs = error("not used by QueueRepositoryTest")
    override suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults = error("not used by QueueRepositoryTest")
    override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> = error("not used by QueueRepositoryTest")
  }

  private fun song(id: String, suffix: String?, coverArtId: String?) = Song(
    id = id,
    libraryId = 1,
    title = "Title $id",
    albumId = "album",
    albumName = "Album",
    artistId = "artist",
    artistName = "Artist",
    trackNumber = 1,
    discNumber = null,
    durationSeconds = 5,
    suffix = suffix,
    coverArtId = coverArtId,
  )

  private lateinit var storeFile: File

  private fun repository(source: SubsonicSource): QueueRepository {
    val (provider, file) = fixedSubsonicSourceProvider(
      ApplicationProvider.getApplicationContext(),
      source,
    )
    storeFile = file
    return QueueRepository(provider)
  }

  @After
  fun tearDown() {
    if (::storeFile.isInitialized) storeFile.delete()
  }

  @Test
  fun everySongInTheQueueBecomesOneMediaItemInTheSameOrder() = runTest {
    val source = RecordingSource()
    val queue = PlaybackQueue.of(
      listOf(song("a", "mp3", null), song("b", "flac", null), song("c", "mp3", null)),
    )

    val items = repository(source).mediaItems(queue)

    assertThat(items.map { it.mediaId }).containsExactly("a", "b", "c")
  }

  @Test
  fun anMp3SourceIsStreamedRaw() = runTest {
    val source = RecordingSource()

    repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null))))

    assertThat(source.streamCalls).containsExactly("a" to StreamFormat.Raw)
  }

  /**
   * **"Never Opus", at the one place the decision is actually made.** Spec section 4 states the
   * rule; `StreamFormat.forSuffix` implements it; this is the assertion that the repository
   * actually consults it rather than defaulting every song to raw.
   */
  @Test
  fun anOpusSourceIsTranscodedRatherThanStreamedRaw() = runTest {
    val source = RecordingSource()

    repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "opus", null))))

    assertThat(source.streamCalls)
      .containsExactly("a" to StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
  }

  @Test
  fun aMixedQueueGetsAPerSongFormatDecision() = runTest {
    // The discriminating shape: one queue, two answers. A repository that decided the format once
    // for the whole queue passes both single-song tests above and fails this one.
    val source = RecordingSource()

    repository(source).mediaItems(
      PlaybackQueue.of(listOf(song("a", "mp3", null), song("b", "opus", null), song("c", "flac", null))),
    )

    assertThat(source.streamCalls).containsExactly(
      "a" to StreamFormat.Raw,
      "b" to StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS),
      "c" to StreamFormat.Raw,
    )
  }

  @Test
  fun aSongWithCoverArtGetsAnArtworkUriAtTheSizeThisAppAsksFor() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", "art-1"))))

    assertThat(source.coverArtCalls).containsExactly("art-1" to QueueRepository.ARTWORK_SIZE_PX)
    assertThat(items.single().mediaMetadata.artworkUri.toString())
      .isEqualTo("https://host/rest/getCoverArt?id=art-1&size=${QueueRepository.ARTWORK_SIZE_PX}")
  }

  @Test
  fun aSongWithNoCoverArtAsksForNoneAndGetsNone() = runTest {
    val source = RecordingSource()

    val items = repository(source).mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null))))

    assertThat(source.coverArtCalls).isEmpty()
    assertThat(items.single().mediaMetadata.artworkUri).isNull()
  }

  @Test
  fun theStreamUrlLandsOnTheItemItWasBuiltFor() = runTest {
    // Two songs, two URLs, asserted as a pair: a repository that built one URL and reused it for
    // every item would pass every test above.
    val source = RecordingSource()

    val items = repository(source)
      .mediaItems(PlaybackQueue.of(listOf(song("a", "mp3", null), song("b", "mp3", null))))

    assertThat(items.map { it.localConfiguration?.uri?.toString() }).containsExactly(
      "https://host/rest/stream?id=a&format=raw",
      "https://host/rest/stream?id=b&format=raw",
    )
  }
}
```

`FixedSubsonicSourceProvider` is the helper that gives this test a real
`SubsonicSourceProvider` whose factory always yields one already-built `SubsonicSource`. It builds
a **real** `CredentialStore` over a **real** `DataStore` file — exactly what Plan 2's
`ShuffleRepositoryTest` does — rather than introducing an interface into production code for one
test:

```kotlin
// core/media/src/androidTest/kotlin/app/muplay/media/FixedSubsonicSourceProvider.kt
package app.muplay.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.muplay.database.CredentialStore
import app.muplay.database.SubsonicSourceProvider
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicSource
import app.muplay.network.SubsonicSourceFactory
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * A real [SubsonicSourceProvider] whose factory always yields [source].
 *
 * Real `CredentialStore`, real DataStore file, real credentials — the only thing substituted is
 * the `SubsonicSourceFactory`, which is already a `fun interface` in production code and therefore
 * needs no seam invented for it. This is the same construction Plan 2's `ShuffleRepositoryTest`
 * uses; copying it is better than adding an interface to `SubsonicSourceProvider` for a test.
 *
 * Returns the store's backing [File] alongside the provider so the caller can delete it in
 * `@After`. DataStore refuses a second instance for one path, so the path must be unique per test.
 */
fun fixedSubsonicSourceProvider(
  context: Context,
  source: SubsonicSource,
  baseUrl: String = "http://localhost:4533",
): Pair<SubsonicSourceProvider, File> {
  val file = File(context.filesDir, "media-test-${System.nanoTime()}.preferences_pb")
  val credentialStore = CredentialStore(PreferenceDataStoreFactory.create { file })
  runBlocking { credentialStore.save(SubsonicCredentials(baseUrl, "admin", "testpass")) }
  return SubsonicSourceProvider(credentialStore, SubsonicSourceFactory { source }) to file
}
```

> `CredentialStore`'s committed constructor is
> `CredentialStore @Inject constructor(dataStore: DataStore<Preferences>)` with
> `suspend save/load/clear` — read the file to confirm the parameter list before relying on this.
> `SubsonicSourceProvider(credentialStore, factory)` is **Plan 2 Task 4's**, taken from that plan's
> Interfaces block; if it landed with a different parameter order or name, adapt here and record it
> in the task report. `QueueRepositoryTest` calls this in `@Before` and deletes the returned `File`
> in `@After`.

- [ ] **Step 9: Resolve the provider seam, then implement `QueueRepository`**

`core/media/build.gradle.kts` — add:

```kotlin
  // `SubsonicSourceProvider` and, from Task 8, `MediaProgressDao`. `implementation`, not `api`:
  // nothing this module exposes publicly mentions a `:core:database` type.
  implementation(project(":core:database"))
```

`core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import app.muplay.database.SubsonicSourceProvider
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.network.SubsonicSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [PlaybackQueue] of mirrored songs into the `MediaItem`s Media3 plays.
 *
 * The only entry point that builds a playable queue — per the constraints, repositories are the
 * only entry point to data and there is no domain layer, so the format decision and the URL
 * construction live here rather than in a use case or a ViewModel.
 *
 * One `SubsonicSource` for the whole queue, not one per song: `SubsonicSourceProvider.current()`
 * reads credentials, and doing that once per track in a hundred-track shuffle would be a hundred
 * DataStore reads for an answer that cannot change mid-call.
 */
@Singleton
class QueueRepository @Inject constructor(private val sourceProvider: SubsonicSourceProvider) {

  suspend fun mediaItems(queue: PlaybackQueue): List<MediaItem> {
    val source = sourceProvider.current()
    return queue.songs.map { song -> mediaItem(source, song) }
  }

  private fun mediaItem(source: SubsonicSource, song: Song): MediaItem {
    // Per song, not per queue: a library can hold both an Opus file and a FLAC, and deciding once
    // for the whole queue would send one of them the wrong way.
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    return MediaItems.of(
      song = song,
      streamUri = source.streamUrl(song.id, format),
      artworkUri = song.coverArtId?.let { source.coverArtUrl(it, ARTWORK_SIZE_PX) },
    )
  }

  companion object {
    /**
     * The cover-art edge length requested for a notification and lock screen. Large enough for a
     * modern lock screen at 3x density, small enough that a hundred-item queue does not pull a
     * hundred full-resolution images through the notification's bitmap loader.
     */
    const val ARTWORK_SIZE_PX: Int = 512
  }
}
```

- [ ] **Step 10: Run both suites**

```bash
./gradlew :core:media:testDebugUnitTest
./gradlew :core:media:connectedDebugAndroidTest --tests '*MediaItemsTest*' --tests '*QueueRepositoryTest*'
```

Expected: PASS — `PlaybackQueueTest` 6/6, `MediaItemsTest` 13/13, `QueueRepositoryTest` 7/7.

- [ ] **Step 11: Prove the mapping tests can fail**

The whole point of this task's test shape, exercised. One mutation at a time, restored after each:

1. In `MediaItems.of`, replace `song.id` in `setMediaId` with `"song-1"`. Expect
   `theMediaIdIsTheSongId` to fail on its second observation. **This is the exact defect four Plan
   2 reviews found** — confirm the message names the field.
2. Replace `song.id` in `setCustomCacheKey` with `streamUri`. Expect
   `theCustomCacheKeyIsTheSongIdAndNotTheUri` to fail, **and** `MediaCacheTest`'s
   `replayingATrackThroughADifferentUrlFetchesNothingFurther` to fail. Two independent tests, two
   layers — record both.
3. Replace `.setArtist(song.artistName)` with `.setArtist(song.albumName)`. Expect
   `theArtistIsTheSongArtist` to fail. This is why the two fixture songs have different artists
   *and* different albums; identical values there would have made this mutation invisible.
4. In `QueueRepository.mediaItem`, replace the per-song `format` with a hoisted
   `StreamFormat.Raw`. Expect `anOpusSourceIsTranscodedRatherThanStreamedRaw` and
   `aMixedQueueGetsAPerSongFormatDecision` to fail.
5. In `QueueRepository.mediaItems`, build the stream URL once outside `map` and reuse it. Expect
   `theStreamUrlLandsOnTheItemItWasBuiltFor` to fail.
6. Add a `positionMs: Long = 0` property to `PlaybackQueue`. Expect
   `the queue carries no playback position of its own` to fail, with a message that says where
   progress belongs.

- [ ] **Step 12: Record the probes, re-measure, commit**

Add the six mutations to `ci/mutation-probes.sh`'s `PROBES` table, following its existing entry
shape and its scope note about device-side probes. Re-measure `:core:media`'s floors and update
`coverageFloors`: `PlaybackQueue` is a JVM floor (`requiresInstrumentedData = false`), `MediaItems`
and `QueueRepository` are instrumented.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): Song to MediaItem, and a queue that carries no position"
```

---

## Task 5: `MuPlaybackService` — `MediaLibraryService`, foreground lifecycle, notification, permissions

**Files:**
- Create: `core/media/src/main/AndroidManifest.xml`
- Create: `core/media/src/main/res/values/strings.xml`
- Create: `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackNotification.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackConnection.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt`
- Modify: `core/media/build.gradle.kts`
- Modify: `gradle/libs.versions.toml` (`androidx-test-rules`, `guava` for `ListenableFuture`)
- Modify: `build-logic/convention/src/main/kotlin/VerifyMergedManifestTask.kt`
- Modify: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Test: `app/src/androidTest/kotlin/app/muplay/MuPlaybackServiceTest.kt` — **in `:app`, not
  `:core:media`; see "Why the service test lives in `:app`" below**
- Modify: `app/build.gradle.kts`
- Modify: `build.gradle.kts` (`:core:media` floors)

**Interfaces:**
- Consumes: `MuPlayDataSourceFactory.create()` (Task 3), `NavidromeLoadErrorHandlingPolicy`
  (Task 2), `QueueRepository.mediaItems(queue)` and `PlaybackQueue` (Task 4), `PlayerHarness`
  (Task 2).
- Produces:
  - `class MuPlayerFactory @Inject constructor(context, dataSourceFactory, loadErrorPolicy)` with
    `fun create(): ExoPlayer`
  - `object PlaybackNotification` with `const val CHANNEL_ID = "muplay_playback"` and
    `const val NOTIFICATION_ID = 1001`
  - `class MuPlaybackService : MediaLibraryService` with `companion object { fun sessionToken(context: Context): SessionToken }`
  - `data class PlaybackState(isPlaying, isBuffering, mediaId, title, artist, albumTitle,
    artworkUri, positionMs, durationMs, hasNext, hasPrevious)` and
    `PlaybackState.NOTHING_PLAYING`
  - `class PlaybackConnection @Inject constructor(@ApplicationContext context)` with
    `val state: StateFlow<PlaybackState>`, `suspend fun controller(): MediaController`,
    `fun release()`
  - `VerifyMergedManifestTask.requiredDeclarations: ListProperty<String>`

### `MediaLibraryService`, not `MediaSessionService`

Spec §7 names **`MediaLibraryService`** outright, and it is a strict subclass of
`MediaSessionService` — everything a `MediaSessionService` gives (the notification, the media
button routing, the foreground lifecycle) comes with it. Plan 5 needs the library half for Android
Auto's browse tree, and changing a service's base class underneath a live session, a live
notification and a live `MediaController` is a genuinely unpleasant migration to schedule for
later when it costs nothing to avoid now.

**The browse tree itself is Plan 5's, and this task does not fake one.** `MediaLibrarySession.Callback`'s
`onGetLibraryRoot` and `onGetChildren` have defaults that answer *"not supported"*, and those
defaults are left in place rather than overridden with an empty root. The distinction matters:
"not supported" is true today, whereas an empty root is a claim — a car head unit would render it
as a library with nothing in it, which is a wrong answer rather than an absent one.

### Why the service test lives in `:app`

`MuPlaybackService` is `@AndroidEntryPoint`, which requires the hosting `Application` to be
`@HiltAndroidApp`. A **library** module's instrumented tests run in a self-instrumenting APK whose
application is the plain `android.app.Application`, so starting this service from
`:core:media`'s own `androidTest` fails at runtime with Hilt's *"must be attached to an
@HiltAndroidApp Application"*. The usual fix — `HiltTestApplication` plus a custom
`testInstrumentationRunner` — is not available here: `configureKotlinAndroid` sets that runner for
every Android module in build-logic, and `ConventionTest` forbids a module overriding it in its own
`android { }` block.

So the service test lives in `:app`, where `MuPlayApplication` really is `@HiltAndroidApp` and the
test can reach the **production** object graph through `EntryPointAccessors`. This is not a
workaround with a cost: it is a stronger test, because the service is exercised inside the
application that actually hosts it.

Coverage still lands on `:core:media`. `Jacoco.kt`'s `mergedExecutionData` globs **every** project's
`build/outputs/code_coverage/**/*.ec`, and `:core:media`'s classes are offline-instrumented by its
own `enableAndroidTestCoverage`, so a run of `:app:connectedDebugAndroidTest` contributes to
`:core:media`'s report. Plan 2 already depends on exactly this — `:core:designsystem`'s floors are
met by the emulator journey composing `MuPlayTheme`, not by any test in that module.

### Why the notification is tested by reading the real notification

`MediaSessionService` posts its own notification through `DefaultMediaNotificationProvider`, and
the temptation is to assert that the provider was configured. That is the "was asked to play"
mistake wearing a different hat. The test below reads
`NotificationManager.getActiveNotifications()` — the app's own live notifications, from the real
notification service — and asserts the **title on it changes when the track changes**. A provider
that was configured but never posted, and a notification whose title is a constant, both fail.

`POST_NOTIFICATIONS` is `dangerous` from API 33, so without a runtime grant the notification is
silently not shown and `getActiveNotifications()` comes back empty — a green-looking nothing.
`GrantPermissionRule` handles it inside the test rather than as an `adb` step someone can reorder
away, which is the same reasoning `ci/prepare-emulator.sh` gives for doing `adb reverse` in the
same shell as the test run.

### Why the permissions get a build-time gate

Spec §7's permission list is `INTERNET`, `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`. Missing the last one does not fail a build, does not fail an
install, and does not fail until the service actually calls `startForeground` — at which point
Android throws `SecurityException` and playback dies **only when the app is backgrounded with
audio playing**, which is precisely the case a quick manual test does not cover.

`verifyReleaseManifest` already reads AGP's own merged manifest to prove `usesCleartextTraffic`
is *absent*. This task teaches the same task to prove things are *present*, and points it at every
variant rather than only release. The task then fails at build time on the exact class of defect
that otherwise surfaces as "music stops when I lock the screen, sometimes".

- [ ] **Step 1: Teach the manifest task to require declarations**

`build-logic/convention/src/main/kotlin/VerifyMergedManifestTask.kt` — add a second property and
extend the action:

```kotlin
  /**
   * Substrings that **must** appear in [mergedManifest].
   *
   * The mirror of [forbiddenAttributes], and needed for the same reason: a permission or a service
   * declared in a library module's own manifest reaches the application only through the manifest
   * merger, and "it is declared in `:core:media`" is a claim about source layout, not about what
   * ships. Only the merged file is evidence.
   *
   * Substrings, not XML lookups, deliberately — but in the *opposite* safe direction from
   * [forbiddenAttributes]: a required-presence check that over-matches would pass wrongly, so each
   * entry should be specific enough to identify one declaration (a full permission name, a service
   * class name, a foreground-service type value).
   */
  @get:Input
  abstract val requiredDeclarations: ListProperty<String>
```

and, inside `verify()`, after the existing `found` check:

```kotlin
    val missing = requiredDeclarations.get().filterNot { text.contains(it) }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "$manifest is missing ${missing.joinToString(", ")}. A media playback service that is " +
          "not declared, or that lacks FOREGROUND_SERVICE_MEDIA_PLAYBACK, does not fail the " +
          "build, the install, or a foreground test -- it throws SecurityException from " +
          "startForeground the first time the app is backgrounded with audio playing. This is " +
          "the check that turns that into a build failure.",
      )
    }
```

`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` — replace
`configureReleaseManifestVerification` with a per-variant registration. The forbidden-attribute
half stays release-only (debug legitimately carries `usesCleartextTraffic`); the required half runs
for **every** variant, because a missing permission is wrong in both:

```kotlin
private fun Project.configureMergedManifestVerification() {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants { variant ->
    val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}Manifest"
    val verifyTask = tasks.register<VerifyMergedManifestTask>(taskName) {
      group = "verification"
      description = "Checks the ${variant.name} variant's merged manifest."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      // Debug talks to a plain-HTTP container on localhost:4533 (Tier 2's journey); release must
      // never carry the attribute. Same task, different expectation per variant.
      forbiddenAttributes.set(
        if (variant.buildType == "release") listOf("usesCleartextTraffic") else emptyList(),
      )
      requiredDeclarations.set(
        listOf(
          "android.permission.INTERNET",
          "android.permission.POST_NOTIFICATIONS",
          "android.permission.FOREGROUND_SERVICE",
          "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
          "app.muplay.media.MuPlaybackService",
          "androidx.media3.session.MediaSessionService",
          "mediaPlayback",
        ),
      )
    }
    tasks.named("check").configure { dependsOn(verifyTask) }
  }
}
```

and call `configureMergedManifestVerification()` where `configureReleaseManifestVerification()`
was called.

> The task name for the release variant is unchanged (`verifyReleaseManifest`), so
> `.github/workflows/pr.yml`'s "Release manifest" step still works. A new `verifyDebugManifest`
> now exists as well and runs from `check`; add it to that workflow step so the fast tier
> exercises both:
> `run: ./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest`

- [ ] **Step 2: Run it and watch it fail for the right reason**

Run: `./gradlew :app:verifyDebugManifest`
Expected: **FAIL**, naming `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and
`app.muplay.media.MuPlaybackService` as missing. That failure is the test for the rest of this
task.

- [ ] **Step 3: Declare the service and its permissions**

`core/media/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Declared in :core:media rather than in :app, because these are this module's own requirements:
  the service class lives here, and a second application module (the roadmap's Wear OS app, Plan 5)
  that depends on this module must get them too without anyone remembering to copy four lines.

  The manifest merger pulls all of it into the application. That is a claim, not a fact, until
  something checks it -- `verifyDebugManifest`/`verifyReleaseManifest` (build-logic) read AGP's own
  merged manifest and fail the build if any of these is absent.
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

  <!-- The stream comes over the network. -->
  <uses-permission android:name="android.permission.INTERNET" />

  <!-- Spec section 7. FOREGROUND_SERVICE_MEDIA_PLAYBACK is required from API 34 for a service
       whose foregroundServiceType includes mediaPlayback; without it startForeground throws
       SecurityException, and only when the app is actually backgrounded with audio playing. -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

  <!-- dangerous from API 33: needs a runtime grant, and without it the media notification is
       silently not shown rather than reported as an error. -->
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

  <application>
    <service
        android:name="app.muplay.media.MuPlaybackService"
        android:exported="true"
        android:foregroundServiceType="mediaPlayback">
      <!-- exported="true" with this action is how a MediaSessionService is discovered by
           Android Auto, Wear, Assistant and the system media controls. It is not a lapse: the
           service exposes only the MediaSession command surface, which Media3 gates through
           MediaSession.Callback.onConnect. -->
      <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
      </intent-filter>
    </service>
  </application>
</manifest>
```

`core/media/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <!-- The name a user sees in Settings > Apps > MuPlay > Notifications. Not "MuPlay": the app
       name is already the heading there, so a channel called "MuPlay" reads as "MuPlay > MuPlay". -->
  <string name="playback_notification_channel_name">Playback</string>
</resources>
```

- [ ] **Step 4: Run the manifest gate again**

Run: `./gradlew :app:verifyDebugManifest :app:verifyReleaseManifest`
Expected: PASS. Then delete the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` line, re-run, confirm it goes
red naming that permission, and restore it. **A gate nobody has watched fail is a gate nobody
knows works.**

- [ ] **Step 5: Write the failing service test**

`core/media/build.gradle.kts` — add:

```kotlin
  androidTestImplementation(libs.androidx.test.rules)
```

`gradle/libs.versions.toml` — add beside the other AndroidX test entries:

```toml
androidx-test-rules    = { module = "androidx.test:rules", version.ref = "androidxTest" }
```

`app/build.gradle.kts` — the service test needs the media layer's types, a `SubsonicClient` to
find the seeded tracks, and the notification permission rule:

```kotlin
  androidTestImplementation(project(":core:media"))
  androidTestImplementation(project(":core:network"))
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.assertj)
```

`app/src/androidTest/kotlin/app/muplay/MuPlaybackServiceTest.kt`:

```kotlin
package app.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackNotification
import app.muplay.media.PlaybackQueue
import app.muplay.media.QueueRepository
import app.muplay.model.LibraryRole
import app.muplay.model.Song
import app.muplay.model.SubsonicCredentials
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real service, the real session, a real `MediaController` over real IPC, and the real
 * notification the system is holding.
 *
 * Every assertion here is about something *observable from outside the app's own code*: a position
 * that moved, a notification the `NotificationManager` is actually holding, a title that changed
 * when the track changed. Asserting that a provider was configured, or that `play()` was called,
 * would pass against a service that renders silence.
 */
@RunWith(AndroidJUnit4::class)
class MuPlaybackServiceTest {

  /**
   * `POST_NOTIFICATIONS` is `dangerous` from API 33. Without the grant, the notification is
   * silently not posted and `getActiveNotifications()` returns an empty array — a green-looking
   * nothing. Granted by a rule rather than by an `adb` step so it cannot be reordered away from
   * the test that needs it.
   */
  @get:Rule
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  /**
   * The production object graph, reached without `@HiltAndroidTest`.
   *
   * `MuPlayApplication` is `@HiltAndroidApp`, so `EntryPointAccessors.fromApplication` hands back
   * the **real** singletons this app runs on — the same `QueueRepository` and `CredentialStore` the
   * service and the UI use. No test application, no custom runner, and nothing substituted.
   */
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface PlaybackTestEntryPoint {
    fun queueRepository(): QueueRepository
    fun credentialStore(): CredentialStore
    fun libraryRepository(): LibraryRepository
  }

  private lateinit var context: Context
  private lateinit var connection: PlaybackConnection
  private lateinit var controller: MediaController
  private lateinit var graph: PlaybackTestEntryPoint
  private lateinit var songs: List<Song>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = EntryPointAccessors.fromApplication(context, PlaybackTestEntryPoint::class.java)

    runBlocking {
      // Seeded here rather than inherited from whichever journey ran first: a test that depends on
      // another test having run is a test that fails alone, and this suite must not have a hidden
      // ordering. These are ci/navidrome.compose.yml's credentials and
      // ci/configure-libraries.sh's two libraries.
      graph.credentialStore().save(SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"))
      graph.libraryRepository().refreshFromServer()
      graph.libraryRepository().setRole(MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
      graph.libraryRepository().setRole(AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)

      songs = app.muplay.network.SubsonicClient(
        SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"),
      ).getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = 500).sortedBy { it.title }
    }
    check(songs.size >= 2) { "the seeded music library must hold at least two tracks" }

    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      connection = PlaybackConnection(context)
    }
    controller = runBlocking { connection.controller() }
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      controller.stop()
      controller.clearMediaItems()
      connection.release()
    }
  }

  @Test
  fun aControllerCanConnectToTheServiceAndPlayRealAudio() {
    setQueueAndPlay(songs.take(1))

    // Not "playWhenReady is true": a position past a second of a five-second track is the only
    // observation here that a player rendering silence could not produce.
    awaitPositionAtLeast(1_000L)
    assertThat(onMain { controller.isPlaying }).isTrue
  }

  @Test
  fun theSystemHoldsAMediaNotificationWhosePropertiesFollowTheTrack() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    val firstTitle = awaitNotificationTitle()
    assertThat(firstTitle).isEqualTo(songs[0].title)

    // The discriminating half: change the track and require the notification to follow. A title
    // that is a constant, or a notification posted once at startup and never updated, fails here
    // and passes every single-track assertion.
    setQueueAndPlay(listOf(songs[1]))
    awaitPositionAtLeast(1_000L)

    assertThat(awaitNotificationTitle()).isEqualTo(songs[1].title)
    assertThat(songs[0].title).isNotEqualTo(songs[1].title)
  }

  @Test
  fun theNotificationIsOnThisAppsOwnChannel() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    val notification = awaitNotification()
    assertThat(notification.notification.channelId).isEqualTo(PlaybackNotification.CHANNEL_ID)
    // A media notification with no actions is one a user cannot control from the lock screen.
    assertThat(notification.notification.actions).isNotEmpty
  }

  @Test
  fun theSessionOffersTheTransportCommandsALockScreenNeeds() {
    setQueueAndPlay(songs.take(3))
    awaitPositionAtLeast(500L)

    val commands = onMain { controller.availableCommands }
    // The exact list, not `anyMatch`: an empty command set would make an `anyMatch` check
    // vacuously false and a badly-written `allMatch` vacuously true.
    assertThat(
      listOf(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
      ).map { commands.contains(it) },
    ).containsExactly(true, true, true, true, true)
  }

  @Test
  fun tappingTheNotificationHasSomewhereToGo() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(500L)

    // A media notification with no content intent is one that does nothing when tapped -- a
    // defect a user reports as "the notification is broken" and a developer cannot reproduce from
    // a log.
    assertThat(awaitNotification().notification.contentIntent).isNotNull
  }

  @Test
  fun playbackStateReachesTheUiSideOfTheConnection() {
    setQueueAndPlay(listOf(songs[0]))
    awaitPositionAtLeast(1_000L)

    val state = connection.state.value
    assertThat(state.isPlaying).isTrue
    assertThat(state.mediaId).isEqualTo(songs[0].id)
    assertThat(state.title).isEqualTo(songs[0].title)
    assertThat(state.positionMs).isGreaterThan(0L)
    // Duration comes from the extractor, not from the mirror: proving it is a real number is
    // proving the container was actually parsed.
    assertThat(state.durationMs).isGreaterThan(0L)
  }

  private fun setQueueAndPlay(items: List<Song>) {
    val mediaItems = runBlocking { graph.queueRepository().mediaItems(PlaybackQueue.of(items)) }
    onMain {
      controller.setMediaItems(mediaItems, 0, 0L)
      controller.prepare()
      controller.play()
    }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun awaitPositionAtLeast(positionMs: Long) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      if (onMain { controller.currentPosition } >= positionMs) return
      Thread.sleep(50)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; " +
        "state=${onMain { controller.playbackState }} isPlaying=${onMain { controller.isPlaying }} " +
        "error=${onMain { controller.playerError }}",
    )
  }

  private fun awaitNotification(): android.service.notification.StatusBarNotification {
    val manager = context.getSystemService(NotificationManager::class.java)
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      manager.activeNotifications.firstOrNull { it.packageName == context.packageName }?.let { return it }
      Thread.sleep(100)
    }
    throw AssertionError("no notification was ever posted by ${context.packageName}")
  }

  private fun awaitNotificationTitle(): String? {
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    var last: String? = null
    while (System.currentTimeMillis() < deadline) {
      last = awaitNotification().notification.extras
        .getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
      if (last != null) return last
      Thread.sleep(100)
    }
    return last
  }

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val TIMEOUT_MS = 30_000L
  }
}
```

- [ ] **Step 6: Run it to verify it fails, then implement**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*MuPlaybackServiceTest*'`
Expected: FAIL — `Unresolved reference: PlaybackConnection` / `MuPlaybackService`.

`core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Builds the one `ExoPlayer` the service owns.
 *
 * A factory rather than a `@Provides @Singleton ExoPlayer`, and the reason is a hard Media3
 * requirement rather than a preference: an `ExoPlayer` binds to the `Looper` of the thread that
 * built it, and every subsequent access must come from that thread. Hilt would construct a
 * singleton on whichever thread first asked for it. [MuPlaybackService.onCreate] runs on the main
 * thread, so building it there is the only way to be sure.
 */
class MuPlayerFactory @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dataSourceFactory: MuPlayDataSourceFactory,
  private val loadErrorPolicy: NavidromeLoadErrorHandlingPolicy,
) {

  fun create(): ExoPlayer =
    ExoPlayer.Builder(context)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory.create()))
      .setLoadErrorHandlingPolicy(loadErrorPolicy)
      .build()
}
```

`core/media/src/main/kotlin/app/muplay/media/PlaybackNotification.kt`:

```kotlin
package app.muplay.media

/**
 * The identity of the one notification this app posts.
 *
 * Constants rather than literals scattered through the service, because two of them are asserted
 * by `MuPlaybackServiceTest` and one of them (the channel id) is visible to a user in system
 * settings for as long as the app is installed — changing it strands the old channel's settings.
 */
object PlaybackNotification {
  const val CHANNEL_ID: String = "muplay_playback"
  const val NOTIFICATION_ID: Int = 1001
}
```

`core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`:

```kotlin
package app.muplay.media

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The service that owns playback.
 *
 * A `MediaLibraryService`, per spec section 7, rather than a bare `MediaSessionService`: it is a
 * strict subclass, so the notification, media-button routing and foreground lifecycle are
 * identical, and Plan 5's Android Auto browse tree does not require changing a base class
 * underneath a live session.
 *
 * **The browse tree is deliberately not implemented here.** `MediaLibrarySession.Callback`'s
 * defaults answer "not supported" for `onGetLibraryRoot` and `onGetChildren`, and that is the
 * truthful answer today. Overriding them to return an empty root would be a *claim* — a car head
 * unit renders that as a library containing nothing, which is worse than a library it knows it
 * cannot browse.
 */
@AndroidEntryPoint
class MuPlaybackService : MediaLibraryService() {

  @Inject lateinit var playerFactory: MuPlayerFactory

  private var session: MediaLibrarySession? = null

  override fun onCreate() {
    super.onCreate()

    // Built here, on the main thread, because an ExoPlayer binds to its creating thread's Looper.
    val player: ExoPlayer = playerFactory.create()

    setMediaNotificationProvider(
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelId(PlaybackNotification.CHANNEL_ID)
        .setChannelName(R.string.playback_notification_channel_name)
        .setNotificationId(PlaybackNotification.NOTIFICATION_ID)
        .build(),
    )

    session = MediaLibrarySession.Builder(this, player, LibraryCallback())
      // Tapping the notification opens the app. Resolved through the package manager rather than
      // by referencing MainActivity: :core:media must not depend on :app, and a launch intent is
      // exactly what "open the app" means.
      .setSessionActivity(
        android.app.PendingIntent.getActivity(
          this,
          0,
          checkNotNull(packageManager.getLaunchIntentForPackage(packageName)) {
            "no launcher activity for $packageName; the notification would do nothing when tapped"
          },
          android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        ),
      )
      .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

  /**
   * Stops the service when the user swipes the app away **and nothing is playing**.
   *
   * Both halves matter. Stopping unconditionally kills music the user is listening to while they
   * clear their recents list; never stopping leaves an idle foreground service and a stale
   * notification the user cannot get rid of.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = session?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }

  /**
   * No browse overrides, on purpose — see this class's own documentation. The type exists so Plan 5
   * has one place to add them, and so the "not supported" answer is a decision with a name rather
   * than an omission.
   */
  private class LibraryCallback : MediaLibrarySession.Callback
}
```

> Three API shapes above are worth confirming against the resolved Media3 1.11.0 sources before
> assuming they compile — `./gradlew :core:media:compileDebugKotlin` is the check:
> `DefaultMediaNotificationProvider.Builder`'s `setChannelName`/`setNotificationId`,
> `MediaLibrarySession.Builder`'s three-argument constructor, and whether
> `MediaLibrarySession.Callback` can be implemented with no members. If any has moved, fix the call
> and leave the surrounding decisions alone.

`core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt`:

```kotlin
package app.muplay.media

/**
 * Everything the UI needs to know about playback, as one immutable value.
 *
 * A `data class` rather than a sealed hierarchy: unlike `SetupUiState` or `SyncState`, there are no
 * mutually exclusive *shapes* here — a player is always playing something or nothing, and "nothing"
 * is [NOTHING_PLAYING] rather than a separate type with different fields. Collected as a
 * `StateFlow` and read with `collectAsStateWithLifecycle()`, per the constraints.
 *
 * [positionMs] is a **snapshot**, refreshed on a timer by [PlaybackConnection]. Spec section 3 is
 * explicit that the UI collects the live player position and never the database at frame rate; this
 * is the live player position, sampled.
 */
data class PlaybackState(
  val isPlaying: Boolean,
  val isBuffering: Boolean,
  val mediaId: String?,
  val title: String?,
  val artist: String?,
  val albumTitle: String?,
  val artworkUri: String?,
  val positionMs: Long,
  val durationMs: Long,
  val hasNext: Boolean,
  val hasPrevious: Boolean,
) {
  companion object {
    val NOTHING_PLAYING = PlaybackState(
      isPlaying = false,
      isBuffering = false,
      mediaId = null,
      title = null,
      artist = null,
      albumTitle = null,
      artworkUri = null,
      positionMs = 0L,
      durationMs = 0L,
      hasNext = false,
      hasPrevious = false,
    )
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/PlaybackConnection.kt`:

```kotlin
package app.muplay.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one bridge between the playback service and everything that renders it.
 *
 * `:feature:player` gets a [PlaybackState] `StateFlow` and a `MediaController`, and never an
 * `ExoPlayer`. That boundary is the reason `:core:media` exists as a module: a feature that can
 * construct an `ExoPlayer` eventually does, and then there are two players in the process, one of
 * them not the one holding the media session — which is how a media app ends up with a
 * notification that controls nothing.
 *
 * The position ticker is a **UI** concern and is separate from the progress writer's own ticker
 * (Task 8). This one samples the live player at a frame-friendly rate for a seek bar; that one
 * persists a row every few seconds. Merging them would tie how often a database is written to how
 * smooth a progress bar looks.
 */
@Singleton
class PlaybackConnection @Inject constructor(@ApplicationContext private val context: Context) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val _state = MutableStateFlow(PlaybackState.NOTHING_PLAYING)
  val state: StateFlow<PlaybackState> = _state.asStateFlow()

  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var controller: MediaController? = null

  /** Connects to the service if necessary and returns the controller. Main-thread only. */
  suspend fun controller(): MediaController {
    controller?.let { return it }
    val future = MediaController.Builder(context, sessionToken(context)).buildAsync()
    controllerFuture = future
    val connected = suspendCoroutine { continuation ->
      future.addListener({ continuation.resume(future.get()) }, ContextCompat_getMainExecutor(context))
    }
    controller = connected
    connected.addListener(object : Player.Listener {
      override fun onEvents(player: Player, events: Player.Events) = publish(player)
    })
    publish(connected)
    startTicker()
    return connected
  }

  fun release() {
    scope.coroutineContext.cancelChildren()
    controller?.release()
    controller = null
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    _state.value = PlaybackState.NOTHING_PLAYING
  }

  private fun startTicker() {
    scope.launch {
      while (true) {
        controller?.let(::publish)
        delay(POSITION_TICK_MS)
      }
    }
  }

  private fun publish(player: Player) {
    val metadata = player.mediaMetadata
    _state.value = PlaybackState(
      isPlaying = player.isPlaying,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      mediaId = player.currentMediaItem?.mediaId,
      title = metadata.title?.toString(),
      artist = metadata.artist?.toString(),
      albumTitle = metadata.albumTitle?.toString(),
      artworkUri = metadata.artworkUri?.toString(),
      positionMs = player.currentPosition.coerceAtLeast(0L),
      // C.TIME_UNSET until the extractor has read the container; 0 is a better answer for a UI
      // than a large negative sentinel it would render as a duration.
      durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
      hasNext = player.hasNextMediaItem(),
      hasPrevious = player.hasPreviousMediaItem(),
    )
  }

  companion object {
    /** ~4 Hz. Smooth enough for a seek bar, cheap enough to run while the screen is on. */
    const val POSITION_TICK_MS = 250L

    fun sessionToken(context: Context): SessionToken =
      SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
  }
}
```

> Two mechanical details to settle while implementing, both of which
> `./gradlew :core:media:compileDebugKotlin` will point at:
> `ContextCompat_getMainExecutor` above is a placeholder for `ContextCompat.getMainExecutor(context)`
> (`androidx.core.content.ContextCompat`) — use the real call, and add `androidx.core:core-ktx` to
> the module only if it does not already resolve. `scope.coroutineContext.cancelChildren()` needs
> `kotlinx.coroutines.cancelChildren`. Neither is a decision; both are imports.
>
> `com.google.common.util.concurrent.ListenableFuture` comes from Guava, which `media3-session`
> depends on. Declare it in the catalogue and in this module only if the import does not resolve
> transitively — an undeclared-but-used transitive dependency is exactly the audit
> `plan-2-inherited.md` item 4 asked for.

- [ ] **Step 7: Run the service test**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :app:connectedDebugAndroidTest --tests '*MuPlaybackServiceTest*'
```

Expected: PASS, 6/6.

- [ ] **Step 8: Prove the service test can fail**

1. Remove `setMediaNotificationProvider(...)`. Expect `theNotificationIsOnThisAppsOwnChannel` to
   fail on the channel id (Media3's own default channel id is not `muplay_playback`), while
   `theSystemHoldsAMediaNotificationWhosePropertiesFollowTheTrack` **still passes** — which is
   itself informative: the notification is Media3's, and only its identity is ours.
2. Remove `.setSessionActivity(...)`. Expect `tappingTheNotificationHasSomewhereToGo` to fail.
3. In `PlaybackConnection.publish`, replace `metadata.title?.toString()` with `"MuPlay"`. Expect
   `playbackStateReachesTheUiSideOfTheConnection` to fail. Then do the same to
   `player.currentMediaItem?.mediaId` and confirm the `mediaId` assertion fails independently —
   two fields, two failures, which is the field-level rule applied to a state holder.
4. Revoke `POST_NOTIFICATIONS` by deleting the `GrantPermissionRule`. Expect the notification
   tests to fail with *"no notification was ever posted"* rather than passing on an empty array.
   **Record this message.** It is the difference between a real gate and a silent one, and it is
   the reason `awaitNotification` throws instead of returning `null`.

- [ ] **Step 9: Re-measure and commit**

Re-measure `:core:media`'s floors from a merged report. `MuPlaybackService`, `PlaybackConnection`,
`MuPlayerFactory` and `PlaybackNotification` are all instrumented-only. Confirm no `COVERAGE:`
warning is left standing, and that `warnUngatedClasses` does not name a new class.

```bash
./gradlew :app:verifyDebugManifest :app:verifyReleaseManifest
git add core/media app build-logic build.gradle.kts gradle/libs.versions.toml .github/workflows/pr.yml
git commit -m "feat(media): MediaLibraryService with a real notification and a checked manifest"
```

---

## Task 6: Audio focus, becoming-noisy, and the content-type switch

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackAudioAttributes.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ContentTypeSwitcher.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt`
- Modify: `core/media/src/androidTest/kotlin/app/muplay/media/MediaItemsTest.kt`
- Modify: `core/media/src/androidTest/kotlin/app/muplay/media/QueueRepositoryTest.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/PlaybackAudioAttributesTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/AudioFocusTest.kt`
- Modify: `build.gradle.kts` (`:core:media` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - `app.muplay.model.LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }` — `:core:model`, committed.
  - **`app.muplay.database.LibraryRepository.idsWithRole(role: LibraryRole): List<Int>`** —
    **Plan 2 Task 4.** Named exactly as that plan's Interfaces block states. If it landed as
    `libraryIdsWithRole` or returns a `Set`, use the real signature and record it.
  - `MediaItems.of` and `QueueRepository` from Task 4, `MuPlayerFactory` from Task 5,
    `PlayerHarness` from Task 2 (`onMain`, `await`, `awaitState`, `awaitPositionAtLeast`,
    `awaitEnded`, `assertNoPlaybackError`, `release`).
- Produces:
  - `object PlaybackAudioAttributes` with
    `fun contentTypeFor(mediaType: Int): Int` and `fun of(mediaType: Int): AudioAttributes`
  - `class ContentTypeSwitcher(player: Player) : Player.Listener`
  - `MediaItems.of(song, streamUri, artworkUri, isAudiobook: Boolean)` — **fourth parameter added**
  - `QueueRepository` gains a `LibraryRepository` constructor parameter

### Spec §5's "one-line switch", and the field it actually switches on

Spec §5: *"**Audio focus:** a one-line switch — books use `AudioAttributes.CONTENT_TYPE_SPEECH`,
music `CONTENT_TYPE_MUSIC`."* The switch is one line. Getting the *input* to it right is the work,
because the one thing the protocol cannot tell this app is which of the two a track is.

Navidrome hardcodes `child.Type = "music"` for every media file — spec §4, confirmed live on the
seeded `Test Book.m4b`. So the only signal is the **user's own `LibraryRole` assignment** from
Plan 2's setup flow, joined to `Song.libraryId`. Task 4 deliberately hardcoded
`MEDIA_TYPE_MUSIC` on every item because it had no access to that assignment; **this task is where
that constant stops being a constant**, and `MediaItemsTest`'s
`theMediaTypeIsNotAnAudiobookInferenceAndTheSuffixDoesNotChangeIt` is rewritten accordingly — the
suffix still must not decide it, but the library role now does.

`MediaMetadata.mediaType` carries the answer rather than a custom `extras` key, for two reasons:
it is the field that means this, and Plan 5's car and watch surfaces render from it. One field, no
parallel truth.

### Why `handleAudioFocus` is not enough on its own

`ExoPlayer.Builder.setAudioAttributes(attributes, handleAudioFocus = true)` makes Media3 request
and respond to audio focus. It is the right mechanism and it is one call. The reason this task is
more than one line is that **a wrong `contentType` is invisible**: focus still works, the app still
pauses for a phone call, and the difference only shows up in the two places nobody tests — a
navigation prompt ducking music but interrupting speech, and a car deciding how to mix a
notification over what is playing. So the switch gets a real test with the role varied, or it is
worth nothing.

### Becoming-noisy, and why the test drives a real system broadcast

`setHandleAudioBecomingNoisy(true)` makes Media3 register a receiver for
`android.media.AUDIO_BECOMING_NOISY` — headphones unplugged, Bluetooth disconnected — and pause.
Without it, yanking headphones plays a podcast out loud on a train.

`ACTION_AUDIO_BECOMING_NOISY` is a **protected broadcast**: an app cannot send it, and
`context.sendBroadcast(...)` from a test throws `SecurityException`. The `shell` uid *is* on
`ActivityManagerService`'s allow-list for protected broadcasts, so the test sends it through
`UiAutomation.executeShellCommand("am broadcast -a android.media.AUDIO_BECOMING_NOISY")` — a real
system broadcast, reaching Media3's real receiver. There is no way to unplug headphones on an
emulator, and asserting that a receiver was registered would be the "was asked to play" mistake
again.

- [ ] **Step 1: Write the failing content-type test**

`core/media/src/test/kotlin/app/muplay/media/PlaybackAudioAttributesTest.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A JVM test, because [PlaybackAudioAttributes.contentTypeFor] takes an `Int` and returns an `Int`.
 *
 * That signature is the whole reason this type exists separately from the `AudioAttributes` builder
 * beside it: the decision is gated by the fast tier, the object construction is not. Same split as
 * `StreamRetryPolicy` and, one layer down, as `KeystoreCipher` taking a `SecretKey`.
 */
class PlaybackAudioAttributesTest {

  @Test
  fun `an audiobook chapter is speech`() {
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }

  @Test
  fun `an audiobook is speech`() {
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }

  @Test
  fun `music is music`() {
    // The other observation. Without it, `contentTypeFor` returning SPEECH unconditionally passes
    // both tests above -- and a music player that declares everything to be speech ducks under a
    // navigation prompt in a way nobody would notice for months.
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_MUSIC))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
  }

  @Test
  fun `anything this app has no opinion about is music`() {
    // MEDIA_TYPE_MIXED is what an unassigned library's items carry. Music is the safe default: it
    // is what the user is most likely playing, and speech attributes on music is the more
    // audible mistake of the two.
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_MIXED))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
  }

  @Test
  fun `the usage is always media`() {
    // USAGE_MEDIA is what puts this app on the media volume stream rather than the notification or
    // assistant stream. It does not vary with content type and it must not.
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC).usage)
      .isEqualTo(C.USAGE_MEDIA)
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).usage)
      .isEqualTo(C.USAGE_MEDIA)
  }

  @Test
  fun `the built attributes carry the content type the switch chose`() {
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC).contentType)
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).contentType)
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }
}
```

> If `androidx.media3.common.AudioAttributes` turns out to touch `android.media.AudioAttributes`
> eagerly and throws off-device, move **only** the two `of(...)` tests to `androidTest` and leave
> the four `contentTypeFor` tests in the JVM suite. That is the exact split the type was designed
> for; do not move the decision.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*PlaybackAudioAttributesTest*'`
Expected: FAIL — `Unresolved reference: PlaybackAudioAttributes`.

- [ ] **Step 3: Implement the switch**

`core/media/src/main/kotlin/app/muplay/media/PlaybackAudioAttributes.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata

/**
 * Spec section 5's one-line switch: books are speech, everything else is music.
 *
 * The switch is genuinely one line. It gets a type of its own — and a JVM test — because a wrong
 * `contentType` is **invisible**: focus still works, the app still pauses for a call, and the
 * difference only shows up where nobody looks. A navigation prompt should duck music and interrupt
 * speech, and a car mixes a notification differently over each.
 *
 * The input is `MediaMetadata.mediaType`, which `MediaItems` sets from the user's own `LibraryRole`
 * assignment. It is never inferred from a file suffix or from any server field: Navidrome hardcodes
 * `child.Type = "music"` for every media file, so the protocol cannot answer this question at all.
 */
object PlaybackAudioAttributes {

  /** The Media3 `C.AUDIO_CONTENT_TYPE_*` for a `MediaMetadata.MEDIA_TYPE_*`. */
  fun contentTypeFor(mediaType: Int): Int = when (mediaType) {
    MediaMetadata.MEDIA_TYPE_AUDIO_BOOK,
    MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
    -> C.AUDIO_CONTENT_TYPE_SPEECH
    // Music by default, including for the media types this app never sets. Speech attributes on
    // music is the more audible of the two mistakes, so the default is the quieter one.
    else -> C.AUDIO_CONTENT_TYPE_MUSIC
  }

  /**
   * The full attributes for a media type. `USAGE_MEDIA` always — it is what puts this app on the
   * media volume stream rather than the notification or assistant stream, and it does not vary
   * with content.
   */
  fun of(mediaType: Int): AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(contentTypeFor(mediaType))
      .build()
}
```

`core/media/src/main/kotlin/app/muplay/media/ContentTypeSwitcher.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Keeps the player's audio attributes matching what is currently playing.
 *
 * A queue can hold both music and an audiobook chapter — a user can queue a chapter after a song,
 * and Plan 5's car surface can too — so the attributes cannot be decided once when the player is
 * built. This listener re-applies them at every item transition.
 *
 * `handleAudioFocus = true` on every call, which is what makes Media3 request focus, duck and pause
 * on its own. Re-applying attributes while playing can cause the underlying `AudioTrack` to be
 * recreated, which is audible as a brief gap — accepted knowingly, because it happens only at a
 * boundary between a song and a book, which is already a hard cut.
 */
class ContentTypeSwitcher(private val player: ExoPlayer) : Player.Listener {

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    val mediaType = mediaItem?.mediaMetadata?.mediaType ?: return
    player.setAudioAttributes(PlaybackAudioAttributes.of(mediaType), /* handleAudioFocus = */ true)
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt` — extend `create()`:

```kotlin
  fun create(): ExoPlayer =
    ExoPlayer.Builder(context)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory.create()))
      .setLoadErrorHandlingPolicy(loadErrorPolicy)
      // Music until the first item transition says otherwise -- ContentTypeSwitcher below keeps it
      // honest from then on. `handleAudioFocus = true` is what makes Media3 request focus, duck for
      // a navigation prompt and pause for a call, all of it without a line of focus code here.
      .setAudioAttributes(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC), true)
      // Headphones unplugged, Bluetooth disconnected. Without this, yanking headphones plays an
      // audiobook out loud on a train.
      .setHandleAudioBecomingNoisy(true)
      .build()
      .also { player -> player.addListener(ContentTypeSwitcher(player)) }
```

with an import for `androidx.media3.common.MediaMetadata`.

- [ ] **Step 4: Give the queue the user's own role assignment**

`core/media/src/main/kotlin/app/muplay/media/MediaItems.kt` — `of` gains a fourth parameter, and
`setMediaType` stops being a constant:

```kotlin
  /**
   * @param isAudiobook whether the user tagged this song's library **Audiobooks** in setup. Not
   *   inferable from anything the server sends: Navidrome hardcodes `child.Type = "music"` for
   *   every media file, and the OpenSubsonic `mediaType` enum describes the object kind
   *   (`song|album|artist`), not the content. The library id plus the user's own `LibraryRole` is
   *   the only mechanism there is — spec section 4.
   */
  fun of(song: Song, streamUri: String, artworkUri: String?, isAudiobook: Boolean): MediaItem =
    // ... unchanged, except:
    .setMediaType(
      if (isAudiobook) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
      else MediaMetadata.MEDIA_TYPE_MUSIC,
    )
```

`core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt` — inject `LibraryRepository`, ask
it once per queue, and pass the answer down:

```kotlin
@Singleton
class QueueRepository @Inject constructor(
  private val sourceProvider: SubsonicSourceProvider,
  private val libraryRepository: LibraryRepository,
) {

  suspend fun mediaItems(queue: PlaybackQueue): List<MediaItem> {
    val source = sourceProvider.current()
    // Once per queue, not once per song: the set cannot change mid-call, and a hundred-track
    // shuffle would otherwise be a hundred identical database reads.
    val audiobookLibraries = libraryRepository.idsWithRole(LibraryRole.AUDIOBOOKS).toSet()
    return queue.songs.map { song -> mediaItem(source, song, song.libraryId in audiobookLibraries) }
  }

  private fun mediaItem(source: SubsonicSource, song: Song, isAudiobook: Boolean): MediaItem {
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    return MediaItems.of(
      song = song,
      streamUri = source.streamUrl(song.id, format),
      artworkUri = song.coverArtId?.let { source.coverArtUrl(it, ARTWORK_SIZE_PX) },
      isAudiobook = isAudiobook,
    )
  }
  // companion object unchanged
}
```

with imports for `app.muplay.database.LibraryRepository` and `app.muplay.model.LibraryRole`.

- [ ] **Step 5: Update Task 4's tests for the new parameter**

`MediaItemsTest` — every `MediaItems.of(...)` call gains `isAudiobook = false`, and the media-type
test is rewritten. Replace `everyItemIsPlayableAndNotBrowsable`'s media-type assertion and
`theMediaTypeIsNotAnAudiobookInferenceAndTheSuffixDoesNotChangeIt` with:

```kotlin
  @Test
  fun theMediaTypeFollowsTheUsersOwnLibraryRoleAndNothingElse() {
    // The one fact the protocol cannot supply, and the one this app is allowed to decide. Two
    // observations, so a constant satisfies neither.
    assertThat(MediaItems.of(first, "https://host/s", null, isAudiobook = false).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(MediaItems.of(second, "https://host/s", null, isAudiobook = true).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }

  @Test
  fun theFileSuffixNeverDecidesWhetherSomethingIsAnAudiobook() {
    // `second` has suffix "m4b" -- the shape that tempts an inference. It is still music unless
    // the user's LibraryRole says otherwise, and an mp3 in an Audiobooks library is still a book.
    assertThat(MediaItems.of(second, "https://host/s", null, isAudiobook = false).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    assertThat(MediaItems.of(first, "https://host/s", null, isAudiobook = true).mediaMetadata.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
  }
```

`QueueRepositoryTest` — `repository(source)` now needs a `LibraryRepository`. Build a real one over
an in-memory Room database exactly as Plan 2's `LibraryRepository` tests do (see
`core/database/src/androidTest/.../LibraryRepositoryTest.kt` once Plan 2 Task 4 has landed), seed
library 1 as `MUSIC` and library 2 as `AUDIOBOOKS`, and add:

```kotlin
  @Test
  fun aSongFromAnAudiobookLibraryIsMarkedAsAnAudiobookChapter() = runTest {
    val source = RecordingSource()
    val items = repository(source).mediaItems(
      // libraryId 1 is Music, libraryId 2 is Audiobooks -- seeded in @Before.
      PlaybackQueue.of(listOf(song("a", "mp3", null).copy(libraryId = 1), song("b", "mp3", null).copy(libraryId = 2))),
    )

    // One queue, two answers: a repository that decided once for the whole queue fails here and
    // passes any single-song test.
    assertThat(items.map { it.mediaMetadata.mediaType }).containsExactly(
      MediaMetadata.MEDIA_TYPE_MUSIC,
      MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
    )
  }
```

- [ ] **Step 6: Write the failing audio-focus and becoming-noisy tests**

`core/media/src/androidTest/kotlin/app/muplay/media/AudioFocusTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focus and becoming-noisy, observed as **playback that stopped**, never as a flag that was set.
 *
 * `handleAudioFocus = true` and `setHandleAudioBecomingNoisy(true)` are both single builder calls,
 * and a test that asserted they were called would be satisfied by a player that ignores both. What
 * is asserted here instead is that the position stops advancing and `isPlaying` goes false when
 * another app takes focus and when the system says the audio route became noisy.
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusTest {

  private lateinit var context: Context
  private lateinit var audioManager: AudioManager
  private lateinit var server: MockWebServer
  private lateinit var harness: PlayerHarness
  private lateinit var cacheDir: File
  private lateinit var audio: ByteArray
  private var focusRequest: AudioFocusRequest? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    audioManager = context.getSystemService(AudioManager::class.java)
    audio = runBlocking { RealTrackBytes.twoDifferentTracks().first }
    assertThat(audio.size).isGreaterThan(1000)

    server = MockWebServer()
    server.start()
    server.enqueue(
      MockResponse.Builder().code(200).header("Content-Type", "audio/mpeg")
        .header("Accept-Ranges", "bytes").body(Buffer().write(audio)).build(),
    )

    cacheDir = File(context.cacheDir, "focus-test-${System.nanoTime()}")
    val cache = MediaCache.create(context, cacheDir)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        MuPlayerFactory(
          context,
          MuPlayDataSourceFactory(OkHttpClient(), cache),
          NavidromeLoadErrorHandlingPolicy(),
        ).create(),
      )
      harness.player.setMediaItem(
        MediaItem.Builder().setUri(server.url("/stream").toString())
          .setCustomCacheKey("focus-test").build(),
      )
      harness.player.prepare()
      harness.player.play()
    }
    harness.awaitPositionAtLeast(500L)
  }

  @After
  fun tearDown() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    harness.release()
    cacheDir.deleteRecursively()
    server.close()
  }

  @Test
  fun anotherAppTakingTransientFocusPausesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    harness.await("playback to pause for a transient focus loss") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }
    Thread.sleep(1_000L)
    // Not merely `isPlaying == false`: the position must actually have stopped moving. A player
    // reporting paused while its clock ran would look identical to the first assertion alone.
    assertThat(harness.onMain { harness.player.currentPosition }).isEqualTo(paused)
  }

  @Test
  fun givingTransientFocusBackResumesPlayback() {
    takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    harness.await("playback to pause") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }

    abandonFocus()

    harness.await("playback to resume after focus is returned") { harness.player.isPlaying }
    harness.await("the position to move past where it paused") {
      harness.player.currentPosition > paused
    }
  }

  @Test
  fun aPermanentFocusLossStopsPlaybackAndKeepsItStopped() {
    // The control that makes the resume test mean something: transient and permanent must produce
    // different outcomes, or "resumed" is just "never really paused".
    takeFocus(AudioManager.AUDIOFOCUS_GAIN)

    harness.await("playback to pause for a permanent focus loss") { !harness.player.isPlaying }
    abandonFocus()
    Thread.sleep(2_000L)
    assertThat(harness.onMain { harness.player.isPlaying }).isFalse
  }

  /**
   * `ACTION_AUDIO_BECOMING_NOISY` is a **protected broadcast**: an app cannot send it and
   * `sendBroadcast` throws `SecurityException`. The `shell` uid is on `ActivityManagerService`'s
   * allow-list, so this drives the real system broadcast through `UiAutomation` — which is as
   * close to unplugging headphones as an emulator gets, and closer than asserting a receiver was
   * registered.
   */
  @Test
  fun theAudioRouteBecomingNoisyPausesPlayback() {
    InstrumentationRegistry.getInstrumentation().uiAutomation
      .executeShellCommand("am broadcast -a android.media.AUDIO_BECOMING_NOISY")
      .close()

    harness.await("playback to pause when the audio route became noisy") { !harness.player.isPlaying }
    val paused = harness.onMain { harness.player.currentPosition }
    Thread.sleep(1_000L)
    assertThat(harness.onMain { harness.player.currentPosition }).isEqualTo(paused)
  }

  private fun takeFocus(gain: Int) {
    val request = AudioFocusRequest.Builder(gain)
      .setAudioAttributes(
        PlatformAudioAttributes.Builder()
          .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
          .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
          .build(),
      )
      .setOnAudioFocusChangeListener { }
      .build()
    focusRequest = request
    val result = audioManager.requestAudioFocus(request)
    // If the request itself was refused, every assertion below would be testing nothing.
    assertThat(result).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
  }

  private fun abandonFocus() {
    focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    focusRequest = null
  }
}
```

- [ ] **Step 7: Run them**

```bash
./gradlew :core:media:connectedDebugAndroidTest --tests '*AudioFocusTest*' --tests '*PlaybackAudioAttributesTest*'
```

Expected: PASS, 4/4 on the device suite.

- [ ] **Step 8: Prove the focus tests actually test focus — this step is not optional**

Both focus requests here come from the **same process** as the player, because a library module's
instrumented tests run in one APK with the code under test. Whether Android's focus stack preempts
one client from another client in the same uid is a real question and this plan does not assert an
answer to it.

Settle it by mutation, before trusting any of these tests:

1. In `MuPlayerFactory.create()`, change `setAudioAttributes(..., true)` to
   `setAudioAttributes(..., false)`.
2. Re-run `AudioFocusTest`.
3. **Expected: `anotherAppTakingTransientFocusPausesPlayback`, `givingTransientFocusBackResumesPlayback`
   and `aPermanentFocusLossStopsPlaybackAndKeepsItStopped` all fail.** Restore the flag and
   confirm they pass again.

If they **do not** fail — i.e. the tests are green with focus handling disabled — then same-uid
focus contention does not preempt on this emulator and **these three tests prove nothing**. In that
case: delete them, record the finding in the task report and in this plan's file, and move the
audio-focus proof into Task 10's Tier 2 journey, where the app under test and the instrumentation
are separate processes. Do not keep a test that cannot fail; that is the specific defect class this
project has now shipped eleven times.

`theAudioRouteBecomingNoisyPausesPlayback` has its own mutation: change
`setHandleAudioBecomingNoisy(true)` to `false` and confirm it goes red.

- [ ] **Step 9: Record the probes, re-measure, commit**

Add to `ci/mutation-probes.sh`: the `contentTypeFor` mutation (return `SPEECH` always → the JVM
suite goes red), the `isAudiobook` hoist in `QueueRepository` (→
`aSongFromAnAudiobookLibraryIsMarkedAsAnAudiobookChapter` goes red), and whichever of the focus
mutations Step 8 proved discriminating.

Re-measure `:core:media`'s floors. `PlaybackAudioAttributes.contentTypeFor` is a JVM-enforceable
BRANCH floor and must be in the `jacocoJvmCoverageVerification` half — prove it by deleting the
`.ec` files and running that task alone.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): audio focus, becoming-noisy, and speech attributes for books"
```

---

## Task 7: Gapless, measured in PCM frames

**Files:**
- Create: `core/testing/src/main/kotlin/app/muplay/testing/PcmAnalysis.kt`
- Create: `core/testing/src/test/kotlin/app/muplay/testing/PcmAnalysisTest.kt`
- Create: `core/media/src/androidTest/kotlin/app/muplay/media/CapturingAudioSink.kt`
- Create: `core/media/src/androidTest/kotlin/app/muplay/media/GaplessTest.kt`
- Modify: `core/media/build.gradle.kts` (`androidTestImplementation(project(":core:testing"))`)
- Modify: `build.gradle.kts` (`:core:testing` floor comment; `:core:media` floors)
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` (§10's Tier 1 "Playback
  goldens" row — see Step 8)

**Interfaces:**
- Consumes: `MuPlayDataSourceFactory` (Task 3), `MediaCache` (Task 3), `PlayerHarness` (Task 2 —
  `onMain`, `await`, `awaitState`, `awaitPositionAtLeast`, `awaitEnded`, `assertNoPlaybackError`,
  `release`), `RealTrackBytes` (Task 3 — `musicTracks()`, `bytesOf(song)`, `client()`).
- Produces:
  - `object PcmAnalysis` with
    `fun frameCount(byteCount: Int, channelCount: Int): Int`,
    `fun longestZeroRunFrames(pcm: ByteArray, channelCount: Int): Int`,
    `fun framesToMs(frames: Int, sampleRateHz: Int): Long`
  - `class CapturingAudioSink : TeeAudioProcessor.AudioBufferSink` with
    `flushCount`, `sampleRateHz`, `channelCount`, `encoding`, `pcm`

### What "gapless" actually is, and what it is not

Spec §4: *"Gapless has **zero** server support. Use a real Media3 `setMediaItems` queue and let
ExoPlayer read LAME/iTunSMPB. Never hand-roll."*

There are two separate claims in that sentence, and a test that conflates them proves neither.

1. **The encoder's delay and padding are trimmed.** A LAME-encoded MP3 begins with roughly 1105
   samples of encoder delay and ends with padding to fill the last MPEG frame — both **silence**,
   both recorded in the Xing/LAME header. If ExoPlayer does not read that header, every track gains
   ~25 ms of silence at its start and ~20 ms at its end. That is what a listener hears as "a gap".
2. **The audio pipeline is not torn down between tracks.** A `setMediaItems` queue of
   identically-formatted tracks reuses one `AudioTrack`; three separate `prepare()` cycles do not.
   A rebuild is a hard discontinuity regardless of trimming.

Both are measurable, and neither is measurable from `onMediaItemTransition` firing — which is what
a "was asked to play" gapless test looks like.

### How each one is measured

A `TeeAudioProcessor` is inserted into the audio processor chain, **upstream of the `AudioTrack`**,
so every PCM frame the decoder produced is captured whether or not the emulator has a sound card
(it does not — `.github/workflows/e2e.yml` boots it with `-no-audio`). From that capture:

- **Claim 1** is the *longest run of consecutive zero samples anywhere in the stream*. Every seeded
  track is a continuous sine wave (`ci/seed-fixtures.sh`: 385, 440 and 495 Hz), so a real sine
  crosses zero for at most a sample or two at a time. Untrimmed encoder delay is ~25 ms of exact
  silence and shows up immediately. **This measurement is self-calibrating: it needs no expected
  frame count, and it cannot be satisfied by a constant.**
- **Claim 2** is `flushCount`. `AudioBufferSink.flush` is called when the sink is configured or
  reconfigured. One queue of three tracks against three separate prepare cycles of the same three
  tracks — everything else held constant — must produce **strictly fewer** flushes for the queue.
- And a cross-check that the queue lost nothing: the two experiments must produce the **same total
  frame count**, within 10 ms.

### The analyser is itself gated, because rule 4 applies to it

`longestZeroRunFrames` is a check that reports *the absence* of a problem. Rule 4: a gate that
reports the absence of a problem must be provably incapable of staying quiet when it did not run.
So the analyser is a pure function in `:core:testing`, with a JVM test that feeds it a synthetic
buffer containing a **known** zero run and requires it to find exactly that run. If the analyser
could not see silence, the whole gapless claim would be a green light with nothing behind it.

- [ ] **Step 1: Write the failing analyser test**

`core/testing/src/test/kotlin/app/muplay/testing/PcmAnalysisTest.kt`:

```kotlin
package app.muplay.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class PcmAnalysisTest {

  /** 16-bit little-endian PCM from a list of per-channel sample values. */
  private fun pcm(vararg samples: Short): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach(buffer::putShort)
    return buffer.array()
  }

  @Test
  fun `frames are bytes divided by two per channel`() {
    // Two observations of the channel-count argument, because a `frameCount` that ignored it would
    // pass a mono-only test and silently halve every stereo measurement.
    assertThat(PcmAnalysis.frameCount(byteCount = 400, channelCount = 1)).isEqualTo(200)
    assertThat(PcmAnalysis.frameCount(byteCount = 400, channelCount = 2)).isEqualTo(100)
  }

  @Test
  fun `a zero channel count is rejected rather than dividing by zero`() {
    assertThatIllegalArgumentException()
      .isThrownBy { PcmAnalysis.frameCount(400, 0) }
      .withMessageContaining("channelCount")
  }

  /**
   * The measurement this analyser exists for, proven against input whose answer is known by
   * construction.
   *
   * Rule 4: a check that reports the absence of a problem must be provably incapable of staying
   * quiet when it did not run. `longestZeroRunFrames` is exactly that kind of check — the gapless
   * test passes when it returns a small number — so it gets a test that requires it to return a
   * **large** one for input that deserves it.
   */
  @Test
  fun `a known run of silence is found and measured exactly`() {
    val samples = ShortArray(1000) { 500 } + ShortArray(137) { 0 } + ShortArray(1000) { -500 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(137)
  }

  @Test
  fun `the longest run is reported and not the first or the last`() {
    val samples = ShortArray(10) { 0 } + ShortArray(50) { 100 } +
      ShortArray(400) { 0 } + ShortArray(50) { 100 } + ShortArray(20) { 0 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(400)
  }

  @Test
  fun `a run that ends at the end of the buffer still counts`() {
    // Encoder padding lives at the *end* of a track, so a scan that only closed a run on the next
    // non-zero sample would miss the exact case this measurement was built for.
    val samples = ShortArray(50) { 100 } + ShortArray(300) { 0 }

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 1)).isEqualTo(300)
  }

  @Test
  fun `a stream with no silence at all reports zero`() {
    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(1, -1, 2, -2, 3, -3), channelCount = 1)).isZero
  }

  @Test
  fun `a frame counts as silent only when every channel is silent`() {
    // Interleaved stereo: L=0,R=500 twice, then L=0,R=0 three times, then L=700,R=700.
    // Only the middle three frames are silent.
    val samples = shortArrayOf(0, 500, 0, 500, 0, 0, 0, 0, 0, 0, 700, 700)

    assertThat(PcmAnalysis.longestZeroRunFrames(pcm(*samples), channelCount = 2)).isEqualTo(3)
  }

  @Test
  fun `frames convert to milliseconds at the sample rate given`() {
    // Two rates, because a hardcoded 44100 is the obvious accident.
    assertThat(PcmAnalysis.framesToMs(frames = 44100, sampleRateHz = 44100)).isEqualTo(1000L)
    assertThat(PcmAnalysis.framesToMs(frames = 48000, sampleRateHz = 48000)).isEqualTo(1000L)
    assertThat(PcmAnalysis.framesToMs(frames = 22050, sampleRateHz = 44100)).isEqualTo(500L)
  }

  @Test
  fun `an empty buffer has no frames and no silence`() {
    assertThat(PcmAnalysis.frameCount(0, 1)).isZero
    assertThat(PcmAnalysis.longestZeroRunFrames(ByteArray(0), channelCount = 1)).isZero
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:testing:test --tests '*PcmAnalysisTest*'`
Expected: FAIL — `Unresolved reference: PcmAnalysis`.

- [ ] **Step 3: Implement the analyser**

`core/testing/src/main/kotlin/app/muplay/testing/PcmAnalysis.kt`:

```kotlin
package app.muplay.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measurements over raw 16-bit little-endian PCM, as captured from a real audio pipeline.
 *
 * Lives in `:core:testing` beside `OpenApiFixtureValidator` for the same reason that class does:
 * it is an **oracle**, and an oracle has to be gated by the fast tier or it is just another thing
 * that might be wrong. `:core:media`'s gapless test consumes it from `androidTest`, which cannot
 * see that module's own `test` source set — so a shared JVM module is where it has to live if its
 * own correctness is to be a Tier 1 concern.
 *
 * 16-bit little-endian is the only encoding handled, and that is deliberate rather than a
 * limitation: `C.ENCODING_PCM_16BIT` is what the capture asserts it received, so anything else
 * fails at the capture rather than being silently mis-measured here.
 */
object PcmAnalysis {

  private const val BYTES_PER_SAMPLE = 2

  /** Frames (one sample per channel) in [byteCount] bytes of interleaved 16-bit PCM. */
  fun frameCount(byteCount: Int, channelCount: Int): Int {
    require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
    return byteCount / (BYTES_PER_SAMPLE * channelCount)
  }

  /**
   * The length, in frames, of the longest run of **completely silent** frames in [pcm].
   *
   * A frame counts as silent only when every one of its channels is exactly zero — a single silent
   * channel in a stereo stream is a real signal, not a gap.
   *
   * This is how untrimmed encoder delay and padding are detected. LAME writes roughly 1105 samples
   * of exact silence at the start of an MP3 and pads the final frame at the end; both are recorded
   * in the Xing/LAME header, and both survive as audible silence if that header is not read. The
   * seeded fixtures are continuous sine waves, so a genuine signal never produces a run longer
   * than a sample or two.
   */
  fun longestZeroRunFrames(pcm: ByteArray, channelCount: Int): Int {
    require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val frames = frameCount(pcm.size, channelCount)

    var longest = 0
    var current = 0
    for (frame in 0 until frames) {
      var silent = true
      for (channel in 0 until channelCount) {
        if (buffer.get(frame * channelCount + channel).toInt() != 0) {
          silent = false
          break
        }
      }
      if (silent) {
        current++
        // Updated inside the run rather than when it ends: encoder padding sits at the very end of
        // a stream, so a scan that only closed a run on the next non-zero sample would miss the
        // exact case this function was written for.
        if (current > longest) longest = current
      } else {
        current = 0
      }
    }
    return longest
  }

  /** [frames] at [sampleRateHz], in milliseconds. */
  fun framesToMs(frames: Int, sampleRateHz: Int): Long {
    require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
    return frames.toLong() * 1000L / sampleRateHz
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:testing:test --tests '*PcmAnalysisTest*'`
Expected: PASS, 9/9. `:core:testing` already carries a `BRANCH >= 0.90` BUNDLE floor; run
`./gradlew :core:testing:jacocoTestReport jacocoJvmCoverageVerification` and confirm the module
still clears it with the new class in it.

- [ ] **Step 5: Write the PCM capture and the failing gapless test**

`core/media/build.gradle.kts` — add:

```kotlin
  androidTestImplementation(project(":core:testing"))
```

`core/media/src/androidTest/kotlin/app/muplay/media/CapturingAudioSink.kt`:

```kotlin
package app.muplay.media

import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Captures every PCM frame the decoder produced, upstream of the `AudioTrack`.
 *
 * Upstream matters: the CI emulator boots with `-no-audio`, and this measurement must not depend on
 * a sound card existing. The `TeeAudioProcessor` this backs sits inside the audio processor chain,
 * so it sees exactly what the decoder emitted after Media3 applied the encoder-delay and padding
 * trimming that "gapless" actually consists of.
 *
 * [flushCount] is the second half of the measurement. `flush` is called when the sink is configured
 * or reconfigured, so a queue that keeps one `AudioTrack` across three tracks flushes strictly
 * fewer times than three separate `prepare()` cycles of the same three tracks.
 */
class CapturingAudioSink : TeeAudioProcessor.AudioBufferSink {

  private val captured = ByteArrayOutputStream()

  var flushCount: Int = 0
    private set
  var sampleRateHz: Int = 0
    private set
  var channelCount: Int = 0
    private set
  var encoding: Int = 0
    private set

  val pcm: ByteArray get() = captured.toByteArray()

  override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
    flushCount++
    this.sampleRateHz = sampleRateHz
    this.channelCount = channelCount
    this.encoding = encoding
  }

  override fun handleBuffer(buffer: ByteBuffer) {
    // Position saved and restored: the processor chain reads this same buffer after this call, and
    // consuming it here would silence playback in a way that looks like a decoder fault.
    val position = buffer.position()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    buffer.position(position)
    captured.write(bytes)
  }
}
```

`core/media/src/androidTest/kotlin/app/muplay/media/GaplessTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.Song
import app.muplay.testing.PcmAnalysis
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gapless, measured in PCM frames off a real decoder on a real device.
 *
 * Two experiments over the same three real tracks, differing in exactly one thing:
 *
 * - **A**: one `setMediaItems([t1, t2, t3])`, one `prepare()`, played to the end.
 * - **B**: three separate `setMediaItem` + `prepare()` + play-to-end cycles.
 *
 * Everything else is held constant — same player configuration, same tracks, same order, same
 * capture. That is what makes the comparison an argument rather than an observation.
 */
@RunWith(AndroidJUnit4::class)
class GaplessTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private lateinit var streamUrls: List<String>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "gapless-test-${System.nanoTime()}")
    songs = runBlocking { RealTrackBytes.musicTracks() }
    check(songs.size == 3) {
      "ci/seed-fixtures.sh seeds exactly three music tracks; found ${songs.size}. This test's " +
        "arithmetic is over those three."
    }
    val client = RealTrackBytes.client()
    streamUrls = songs.map { client.streamUrl(it.id, app.muplay.model.StreamFormat.Raw) }
  }

  @After
  fun tearDown() {
    cacheDir.deleteRecursively()
  }

  @Test
  fun aQueuePlaysAllThreeTracksWithNoInsertedSilence() {
    val capture = playAsOneQueue()

    // Sanity first: the capture is real 16-bit PCM at a real rate, or every number below is
    // meaningless. This is the "hasSizeGreaterThan" of an audio test.
    assertThat(capture.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
    assertThat(capture.sampleRateHz).isGreaterThan(0)
    assertThat(capture.channelCount).isGreaterThan(0)

    val frames = PcmAnalysis.frameCount(capture.pcm.size, capture.channelCount)
    val playedMs = PcmAnalysis.framesToMs(frames, capture.sampleRateHz)
    // Three 5.000 s tracks. A wide band, because this is a "did all three actually decode" check,
    // not the gapless assertion -- that one is the zero-run below.
    assertThat(playedMs).isBetween(14_500L, 15_500L)

    /*
     * The gapless assertion proper, and it is self-calibrating: no expected frame count appears in
     * it, so no constant can satisfy it.
     *
     * Every seeded track is a continuous sine wave (ci/seed-fixtures.sh: 385, 440, 495 Hz), so a
     * genuine signal crosses zero for at most a sample or two at a time. LAME writes ~1105 samples
     * of exact silence as encoder delay at the start of each file and pads the final MPEG frame at
     * the end; both are recorded in the Xing/LAME header, and both survive as audible silence at
     * every track boundary if ExoPlayer does not read it. 25 ms of silence would be a run of ~1100
     * frames; the threshold below is 10 ms.
     */
    val silentFrames = PcmAnalysis.longestZeroRunFrames(capture.pcm, capture.channelCount)
    assertThat(PcmAnalysis.framesToMs(silentFrames, capture.sampleRateHz))
      .describedAs("longest run of silence anywhere in three gaplessly-queued tracks")
      .isLessThan(10L)
  }

  @Test
  fun aQueueReconfiguresTheAudioSinkFewerTimesThanThreeSeparatePreparations() {
    val queued = playAsOneQueue()
    val separate = playAsThreeSeparatePreparations()

    // The second half of gapless: the pipeline was not torn down between tracks. Strictly fewer,
    // not "equal to one" -- the absolute count is a Media3 implementation detail, the comparison
    // is the property. Everything but the queueing is identical between the two runs.
    assertThat(queued.flushCount)
      .describedAs("audio sink configurations for one queue of three vs three preparations")
      .isLessThan(separate.flushCount)

    // ...and the queue did not achieve that by playing less. Within 10 ms of the same audio.
    val queuedFrames = PcmAnalysis.frameCount(queued.pcm.size, queued.channelCount)
    val separateFrames = PcmAnalysis.frameCount(separate.pcm.size, separate.channelCount)
    val differenceMs = PcmAnalysis.framesToMs(
      kotlin.math.abs(queuedFrames - separateFrames),
      queued.sampleRateHz,
    )
    assertThat(differenceMs)
      .describedAs("total decoded audio, queued vs separately prepared")
      .isLessThan(10L)
  }

  @Test
  fun theQueueReallyPlayedEveryTrackAndNotTheFirstOneThreeTimes() {
    // The control for the whole class. A "gapless" implementation that played track 1 three times
    // would satisfy the frame count and the zero-run check perfectly. Media3 reports each
    // transition; three distinct media ids, in order, is what rules that out.
    val transitions = mutableListOf<String>()

    runExperiment(transitions) { harness ->
      harness.onMain {
        harness.player.setMediaItems(streamUrls.indices.map { mediaItem(it) })
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitEnded(timeoutMs = 60_000L)
    }

    assertThat(transitions).containsExactly(songs[0].id, songs[1].id, songs[2].id)
  }

  private fun mediaItem(index: Int): MediaItem =
    MediaItem.Builder()
      .setMediaId(songs[index].id)
      .setUri(streamUrls[index])
      .setCustomCacheKey(songs[index].id)
      .build()

  private fun playAsOneQueue(): CapturingAudioSink = runExperiment { harness ->
    harness.onMain {
      harness.player.setMediaItems(streamUrls.indices.map { mediaItem(it) })
      harness.player.prepare()
      harness.player.play()
    }
    harness.awaitEnded(timeoutMs = 60_000L)
  }

  private fun playAsThreeSeparatePreparations(): CapturingAudioSink = runExperiment { harness ->
    streamUrls.indices.forEach { index ->
      harness.onMain {
        harness.player.setMediaItem(mediaItem(index))
        harness.player.prepare()
        harness.player.play()
      }
      harness.awaitEnded(timeoutMs = 60_000L)
    }
  }

  /**
   * Builds a player whose audio processor chain contains a [TeeAudioProcessor] feeding a fresh
   * [CapturingAudioSink], runs [block], and returns the capture.
   *
   * An audio-only `RenderersFactory` rather than [DefaultRenderersFactory], because supplying a
   * custom `AudioSink` is the supported way to insert a processor chain and because a music player
   * has no use for a video renderer. The renderer constructor used here —
   * `MediaCodecAudioRenderer(context, mediaCodecSelector, eventHandler, eventListener, audioSink)`
   * — and `DefaultAudioSink.Builder(context)` are both public API; **confirm their exact shapes
   * against the resolved Media3 1.11.0 sources with `./gradlew :core:media:compileDebugAndroidTestKotlin`
   * before assuming this compiles.** If a signature has moved, fix the construction; the experiment
   * itself does not change.
   */
  private fun runExperiment(
    transitions: MutableList<String>? = null,
    block: (PlayerHarness) -> Unit,
  ): CapturingAudioSink {
    val capture = CapturingAudioSink()
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val audioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(TeeAudioProcessor(capture)))
        .build()
      val renderersFactory = RenderersFactory { handler, _, audioListener, _, _ ->
        arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink))
      }
      harness = PlayerHarness(
        ExoPlayer.Builder(context, renderersFactory)
          .setMediaSourceFactory(
            DefaultMediaSourceFactory(MuPlayDataSourceFactory(OkHttpClient(), cache).create()),
          )
          .build(),
      )
      if (transitions != null) {
        harness.player.addListener(object : androidx.media3.common.Player.Listener {
          override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let(transitions::add)
          }
        })
      }
    }
    try {
      block(harness)
      harness.assertNoPlaybackError()
    } finally {
      harness.release()
      cache.release()
    }
    return capture
  }
}
```

- [ ] **Step 6: Run it**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :core:media:connectedDebugAndroidTest --tests '*GaplessTest*'
```

Expected: PASS, 3/3. Each experiment plays 15 s of real-time audio, so this class is roughly a
minute on its own; that is the price of measuring audio rather than intent.

**If `aQueuePlaysAllThreeTracksWithNoInsertedSilence` fails on the silence assertion, do not raise
the threshold.** Print the measured run length first. A run near 1100 frames means the LAME header
is not being read, which is a real finding about the fixtures or about Media3 and belongs in the
task report and in the spec — spec §4 asserts ExoPlayer reads LAME/iTunSMPB, and if the seeded
files carry no Xing/LAME header at all then `ci/seed-fixtures.sh` needs `-write_xing 1`, not the
test needs a looser bound.

- [ ] **Step 7: Prove the gapless tests can fail**

1. In `GaplessTest.playAsOneQueue`, replace the three-item `setMediaItems` with three sequential
   `setMediaItem` calls (i.e. make experiment A into experiment B). Expect
   `aQueueReconfiguresTheAudioSinkFewerTimesThanThreeSeparatePreparations` to fail. **This is the
   only mutation that proves the comparison is load-bearing.**
2. In `CapturingAudioSink.handleBuffer`, write nothing. Expect every frame-count assertion to
   fail rather than a green run over an empty capture.
3. In `PcmAnalysis.longestZeroRunFrames`, `return 0`. Expect
   `a known run of silence is found and measured exactly` in the JVM suite to fail — and note that
   `aQueuePlaysAllThreeTracksWithNoInsertedSilence` would have **passed**. That asymmetry is
   exactly why the analyser has its own test in its own tier.
4. Make `mediaItem(index)` always return `mediaItem(0)`. Expect
   `theQueueReallyPlayedEveryTrackAndNotTheFirstOneThreeTimes` to fail, and note that the other
   two tests pass — which is why that control exists.

- [ ] **Step 8: Correct the spec**

`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`, §10, the **Tier 1** table's
*"Playback goldens"* row currently reads *"`PlaybackOutput` dumps; gapless byte-compare; chapter
assertions on the M4B fixture"* and sits in the tier described as **"fast, ≤ 10 minutes, no
emulator"**.

That is not achievable as written, and the contradiction is internal to the spec rather than a
matter of taste: Media3's `PlaybackOutput`, `CapturingRenderersFactory` and `DumpFileAsserts` live
in `media3-test-utils`, they require an Android runtime, and the JVM path to them is
`media3-test-utils-robolectric` — while §2 and §10 of the same document ban Robolectric outright.
A row that cannot run in the tier it is filed under is a gate that will never fire.

Move the row to **Tier 2** and say what actually runs there:

> | Gapless | `GaplessTest` — a `TeeAudioProcessor` captures the PCM a real decoder produced on a real emulator; the longest run of silence across a three-track queue must stay under 10 ms, and one queue must reconfigure the audio sink strictly fewer times than three separate preparations of the same tracks. |

and add a line under §10's tooling notes:

> **`PlaybackOutput` dumps are a Tier 2 technique here, not a Tier 1 one.** `media3-test-utils`
> needs an Android runtime and its JVM path is `media3-test-utils-robolectric`, which this project
> bans. Playback is therefore measured on the emulator, from the real audio pipeline —
> `TeeAudioProcessor` upstream of the `AudioTrack`, which also means the measurement does not
> depend on the CI emulator having a sound card (it boots with `-no-audio`). The *analyser* over
> those PCM bytes (`PcmAnalysis`, `:core:testing`) is pure Kotlin and **is** gated by Tier 1, which
> is what stops "no silence found" from being a green light with nothing behind it.

- [ ] **Step 9: Re-measure and commit**

```bash
./gradlew :core:testing:test :core:media:testDebugUnitTest
./gradlew :core:media:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
git add core/testing core/media build.gradle.kts docs/superpowers/specs
git commit -m "test(media): gapless measured in PCM frames, and the analyser that can see silence"
```

---

## Task 8: `MuPlayer` — the `ForwardingPlayer` seam and the progress writer

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/ResumePolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/MuPlayer.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/ResumePolicyTest.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/ProgressTableShapeTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/MuPlayerTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ProgressWriterTest.kt`
- Modify: `build.gradle.kts` (`:core:media` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - **`app.muplay.database.entity.MediaProgressEntity(mediaId: String, positionMs: Long,
    isFinished: Boolean, lastPlayedAtEpochMs: Long, speed: Float, skipSilence: Boolean,
    gainDb: Float)`** — `:core:database`, **committed** (Plan 2 Task 1). Read the file; this plan
    adds no column to it.
  - **`app.muplay.database.dao.MediaProgressDao`** — `suspend upsert(progress)`,
    `suspend find(mediaId): MediaProgressEntity?`, `suspend findAll(): List<MediaProgressEntity>`,
    `suspend recentlyPlayed(limit): List<MediaProgressEntity>`. Committed, and **written by
    nothing until this task**.
  - `MuPlayerFactory` (Task 5), `PlayerHarness` (Task 2), `RealTrackBytes` (Task 3).
- Produces:
  - `data class ResumeTarget(val startIndex: Int, val startPositionMs: Long)`
  - `fun interface ResumePolicy { fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget }`
  - `object NeverResume : ResumePolicy`
  - `class MuPlayer(player: Player, resumePolicy: ResumePolicy) : ForwardingPlayer`
  - `class ProgressWriter(player: Player, dao: MediaProgressDao, clock: Clock, scope: CoroutineScope)`
    with `fun start()`, `suspend fun write(mediaId: String, positionMs: Long, finished: Boolean)`,
    `fun flushBlocking()`, `companion object { const val TICK_MS = 5_000L;
    const val DEFAULT_SPEED = 1.0f; const val DEFAULT_GAIN_DB = 0.0f }`.
    All three constants are declared by this task's own code (see Step 6) and **the two defaults are
    consumed by name from Plan 4** — the read-modify-write uses them for a row that does not exist
    yet, and the audiobook plan reads them as the values a book starts from.
  - `MediaModule` provides `@Singleton java.time.Clock`

**Forward requirement recorded by Plan 6 (casting) — do not design it out.** When playback moves
to a Sonos or DLNA renderer, the local `Player` is replaced by a remote one, and **one**
`ProgressWriter` must follow that switch — a second writer on a second player would race the first
for the same `media_progress` row. Plan 6 therefore adds `fun attach(player: Player)` at the point
it needs it.

This plan does **not** add that method: an unused method would be untested and would sit under the
coverage floors as dead weight, and this plan has no second player to attach. What this plan owes
Plan 6 is only that `attach` remains *possible* — so do not capture the constructor's `player` in a
way that a long-lived ticker coroutine closes over irreversibly. Hold it behind a field the class
can repoint, and `start()`/`flushBlocking()` keep their meaning unchanged.

### The seam, and what it is actually for

Spec §3: *"`MuPlayer` is a `ForwardingPlayer` overriding **all six** `setMediaItem(s)` overloads to
discard the caller's index and position and rehydrate from Room. No code path can set a wrong
position."* (The idea is Voice's; **Voice is GPL, so read none of it** — the licence constraint is
not decorative here.)

The six, exactly, on `androidx.media3.common.Player`:

```
setMediaItem(MediaItem)
setMediaItem(MediaItem, long startPositionMs)
setMediaItem(MediaItem, boolean resetPosition)
setMediaItems(List<MediaItem>)
setMediaItems(List<MediaItem>, boolean resetPosition)
setMediaItems(List<MediaItem>, int startIndex, long startPositionMs)
```

Miss one and the guarantee is gone: a `MediaController` in a car, a headset, or a future feature
calls the one that was not overridden and sets whatever position it likes.

**This plan installs a policy that resumes nothing**, and that is not a placeholder — it is exactly
what spec §3 says music does: *"Only books get resume treatment. Music restarts from 0 — progress
is still recorded, just not honoured on prepare."* Plan 4 replaces `NeverResume` with a policy that
answers from `media_progress` for audiobooks, and changes nothing else.

The stronger structural point is in the policy's *signature*: `resolve(mediaIds, requestedIndex)`
is never given the caller's requested position, so there is no position for an implementation to
accidentally honour. The requested **index** is passed, because an index is queue membership — "play
track 3 of this album" is a legitimate thing for a caller to say — and the index is the caller's to choose.

**Correction, found by Plan 4 before this was implemented.** An earlier draft of this paragraph
said a policy is free to override the index, "which is how Plan 4 resumes a book at chapter 14".
It cannot be. `"play this book"` and `"play chapter 1 from the top"` both arrive as
`requestedIndex = 0`, so a policy that overrode the index would make tapping chapter 1 jump to
chapter 14. The **index** belongs to the caller; only the **position** belongs to the policy.
Plan 4 resumes at chapter 14 by having its own launcher choose the index before `setMediaItems`
is called — which leaves this seam unchanged and the guarantee it exists for, that no code path
can set a wrong position, exactly as strong.

### The seven persistence points, and the trap inside them

Spec §3 lists seven, plus a 5–10 s ticker, and one of them carries a footnote worth restating:
`onPositionDiscontinuity` must **ignore `DISCONTINUITY_REASON_SILENCE_SKIP` (6)**. Silence skipping
(Plan 4) moves the position without the listener having moved; recording it as progress would
inch a book forward every time it skipped a pause.

The trap that is *not* in the spec, and that this task must not fall into:
**`media_progress` already has columns this task does not write.** `speed` and `skipSilence` are
per-item settings **Plan 4** writes; `gainDb` is **this plan's**, and **Task 11** is the task that
starts writing it. Either way this task writes none of the three. A writer that constructs a fresh
`MediaProgressEntity(mediaId, positionMs, false, now, 1f, false, 0f)` and upserts it **resets a
listener's per-book speed every five seconds**. So every write is a read-modify-write that
preserves the columns it does not write, and `ProgressWriterTest` asserts exactly that.

> **Task 11 changes exactly one line of this, and nothing else.** When ReplayGain lands,
> `gainDb = existing?.gainDb ?: DEFAULT_GAIN_DB` becomes a stamp from the currently-playing item, so
> the row records the gain the item was actually played at. `speed` and `skipSilence` stay
> preserved-not-written for as long as Plan 4 has not landed. Task 11 makes that edit, extends
> `aWriteDoesNotClobberTheColumnsThisPlanDoesNotOwn` to match, and does not touch anything else in
> this file — the read-modify-write shape, and the reason for it, are this task's and stay this
> task's.

`isFinished` gets the same treatment in the other direction: it is set to `true` at `STATE_ENDED`
and otherwise **preserved**, never written as `false`. A ticker that wrote `false` would un-finish
a completed book on the next accidental tap. "Un-finish on replay" is a real behaviour and it is
Plan 4's, at the point it has a UI to express it.

### The `Clock`

This is the **first code in the project to write a timestamp**, so — per the global constraint and
per Plan 2's own note that "the first task in any plan that writes a timestamp injects a `Clock`
at that point" — this is where a `Clock` is injected. `java.time.Clock`, not `kotlinx-datetime`:
`java.time` is available natively at `minSdk 26`, `MediaProgressEntity.lastPlayedAtEpochMs` is
already an epoch-millis `Long`, and adding a datetime library plus a Room type converter for a
column that is already a `Long` would be a dependency bought for nothing.

- [ ] **Step 1: Write the failing policy and table-shape tests**

`core/media/src/test/kotlin/app/muplay/media/ResumePolicyTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A JVM test, because [ResumePolicy] takes media ids and an index — no `MediaItem`, no
 * `android.net.Uri`. That signature is chosen for exactly this reason, and it has a second
 * benefit: the policy is *structurally incapable* of honouring a caller's requested position,
 * because it is never told one.
 */
class ResumePolicyTest {

  @Test
  fun `the plan-3 policy starts every item from zero`() {
    // Spec section 3: "Music restarts from 0 -- progress is still recorded, just not honoured on
    // prepare." Not a placeholder; the specified behaviour.
    assertThat(NeverResume.resolve(listOf("a", "b", "c"), requestedIndex = 0).startPositionMs).isZero
    assertThat(NeverResume.resolve(listOf("a", "b", "c"), requestedIndex = 2).startPositionMs).isZero
  }

  @Test
  fun `the caller's chosen item is respected`() {
    // The index is queue membership -- "play track 3 of this album" is a legitimate request -- so
    // it is *not* discarded. Two observations, because a policy that returned 0 always would pass
    // the first alone.
    assertThat(NeverResume.resolve(listOf("a", "b", "c"), requestedIndex = 0).startIndex).isZero
    assertThat(NeverResume.resolve(listOf("a", "b", "c"), requestedIndex = 2).startIndex).isEqualTo(2)
  }

  @Test
  fun `a policy cannot be handed a position to honour`() {
    // A structural assertion, and the reason this test class exists at all. `resolve` has exactly
    // two parameters; adding a `requestedPositionMs` would give a future implementation something
    // to accidentally trust, which is the failure mode spec section 3's seam exists to remove.
    val parameters = ResumePolicy::class.java.methods.single { it.name == "resolve" }.parameterTypes

    assertThat(parameters.map { it.simpleName }).containsExactly("List", "int")
  }
}
```

`core/media/src/test/kotlin/app/muplay/media/ProgressTableShapeTest.kt`:

```kotlin
package app.muplay.media

import app.muplay.database.entity.MediaProgressEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The other half of `PlaybackQueueTest`'s structural guard, now that this plan has a writer.
 *
 * Spec section 3: *"Nothing about queue membership may live in this table — a `queuePosition`
 * column would invert the design."* `PlaybackQueue` is guarded against gaining a position; this
 * guards the progress table against gaining queue membership. The two together are what make
 * "two pointer lists over one progress table" a fact rather than an intention.
 */
class ProgressTableShapeTest {

  @Test
  fun `the progress table carries no queue membership`() {
    val fields = MediaProgressEntity::class.java.declaredFields
      .filterNot { it.isSynthetic }
      .map { it.name }

    assertThat(fields)
      .describedAs(
        "media_progress is a property of the item, never of a queue (spec section 3). A " +
          "queuePosition or isInQueue column inverts the design: it makes one global now-playing " +
          "position that the next thing played overwrites, which is the exact defect this app " +
          "exists to fix.",
      )
      .containsExactlyInAnyOrder(
        "mediaId", "positionMs", "isFinished", "lastPlayedAtEpochMs", "speed", "skipSilence", "gainDb",
      )
  }
}
```

- [ ] **Step 2: Run them, then implement the policy**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*ResumePolicyTest*' --tests '*ProgressTableShapeTest*'`
Expected: `ResumePolicyTest` FAILs on `Unresolved reference: NeverResume`;
`ProgressTableShapeTest` should **pass immediately** — it is a guard on an existing type, and a
guard that failed on arrival would mean Plan 2's table is already wrong.

`core/media/src/main/kotlin/app/muplay/media/ResumePolicy.kt`:

```kotlin
package app.muplay.media

/** Where playback should actually start: which item, and how far into it. */
data class ResumeTarget(val startIndex: Int, val startPositionMs: Long)

/**
 * Decides where a queue starts. The **only** thing in this application permitted to choose a
 * playback position.
 *
 * [resolve] is deliberately never given the caller's requested position. Spec section 3's seam
 * exists because a single global "now playing position" that the next thing played overwrites is
 * why every other player loses an audiobook's place; taking the position out of the signature
 * means no implementation can accidentally trust one.
 *
 * The requested **index** is passed, because an index is queue membership rather than progress —
 * "play track 3 of this album" is a legitimate request. The index belongs to the CALLER and a policy
 * must not override it: "play this book" and "play chapter 1 from the top" both arrive as index
 * 0, so overriding would make tapping chapter 1 jump to chapter 14. The audiobook plan resumes at
 * chapter 14 by choosing the index before it calls `setMediaItems`. This policy chooses only the
 * position.
 *
 * **Implementations must answer without blocking.** [MuPlayer] calls this from `setMediaItems`,
 * which runs on the player's application thread; a Room query there would jank the UI. The
 * intended mechanism for the audiobook plan is an in-memory snapshot of `media_progress` kept
 * current by a Flow collector, not a blocking read.
 */
fun interface ResumePolicy {
  fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget
}

/**
 * Plan 3's policy: start the item the caller chose, from the beginning.
 *
 * Not a placeholder. Spec section 3: *"Only books get resume treatment. Music restarts from 0 —
 * progress is still recorded, just not honoured on prepare."* This is that behaviour, and
 * [ProgressWriter] is the "progress is still recorded" half. The audiobook plan replaces this
 * object and changes nothing else.
 */
object NeverResume : ResumePolicy {
  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget =
    ResumeTarget(startIndex = requestedIndex, startPositionMs = 0L)
}
```

- [ ] **Step 3: Write the failing seam test**

`core/media/src/androidTest/kotlin/app/muplay/media/MuPlayerTest.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam, exercised through **all six** `setMediaItem(s)` overloads.
 *
 * Missing one is not a partial failure, it is a total one: a `MediaController` in a car, a headset
 * button handler, or a feature written next year calls the one that was not overridden and sets
 * whatever position it likes, and the guarantee spec section 3 rests on is gone.
 *
 * The policy here is a **recording fake**, so each test asserts two independent things: that the
 * policy was consulted at all, and that its answer — not the caller's — is what reached the player.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayerTest {

  private class RecordingPolicy(private val target: ResumeTarget) : ResumePolicy {
    val calls = mutableListOf<Pair<List<String>, Int>>()
    override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
      calls += mediaIds to requestedIndex
      return target
    }
  }

  private lateinit var inner: ExoPlayer

  private fun item(id: String) =
    MediaItem.Builder().setMediaId(id).setUri("https://host/$id").setCustomCacheKey(id).build()

  private val items = listOf(item("a"), item("b"), item("c"))

  @Before
  fun setUp() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      inner = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<android.content.Context>()).build()
    }
  }

  @After
  fun tearDown() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync { inner.release() }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun player(policy: ResumePolicy): MuPlayer = onMain { MuPlayer(inner, policy) }

  @Test
  fun allSixOverloadsConsultTheResumePolicy() {
    val policy = RecordingPolicy(ResumeTarget(startIndex = 0, startPositionMs = 0L))
    val muPlayer = player(policy)

    onMain {
      muPlayer.setMediaItem(items[0])
      muPlayer.setMediaItem(items[0], 30_000L)
      muPlayer.setMediaItem(items[0], /* resetPosition = */ false)
      muPlayer.setMediaItems(items)
      muPlayer.setMediaItems(items, /* resetPosition = */ false)
      muPlayer.setMediaItems(items, /* startIndex = */ 1, /* startPositionMs = */ 30_000L)
    }

    // Six calls, not "at least one". A `ForwardingPlayer` that overrode five and inherited the
    // sixth records five, and this is the assertion that catches it.
    assertThat(policy.calls).hasSize(6)
    assertThat(policy.calls.map { it.first }).containsExactly(
      listOf("a"), listOf("a"), listOf("a"), listOf("a", "b", "c"), listOf("a", "b", "c"), listOf("a", "b", "c"),
    )
  }

  @Test
  fun aCallersRequestedPositionNeverReachesThePlayer() {
    val muPlayer = player(NeverResume)

    onMain {
      muPlayer.setMediaItem(items[0], 30_000L)
    }

    // The single most important assertion in this class. 30 seconds was asked for; zero is where
    // the player is.
    assertThat(onMain { inner.currentPosition }).isZero
  }

  @Test
  fun aCallersRequestedPositionNeverReachesThePlayerThroughTheListOverloadEither() {
    val muPlayer = player(NeverResume)

    onMain { muPlayer.setMediaItems(items, 1, 30_000L) }

    assertThat(onMain { inner.currentPosition }).isZero
    // ...and the item the caller chose is still the item that is queued up: an index is queue
    // membership, not progress, and discarding it would break "play track 3 of this album".
    assertThat(onMain { inner.currentMediaItemIndex }).isEqualTo(1)
  }

  @Test
  fun thePolicysPositionIsWhatThePlayerActuallyGets() {
    // The other observation, and the one that stops "always zero" from being mistaken for "the
    // policy was consulted". A policy answering 7000 must produce a player at 7000.
    val muPlayer = player(RecordingPolicy(ResumeTarget(startIndex = 2, startPositionMs = 7_000L)))

    onMain { muPlayer.setMediaItems(items, 0, 0L) }

    assertThat(onMain { inner.currentPosition }).isEqualTo(7_000L)
    assertThat(onMain { inner.currentMediaItemIndex }).isEqualTo(2)
  }

  @Test
  fun theOverloadsThatTakeNoPositionStillGoThroughThePolicy() {
    // `setMediaItem(item)` and `setMediaItems(items)` look harmless -- they name no position -- so
    // they are the two most likely to be left un-overridden. A policy answering 7000 proves they
    // were not.
    val muPlayer = player(RecordingPolicy(ResumeTarget(startIndex = 1, startPositionMs = 7_000L)))

    onMain { muPlayer.setMediaItems(items) }

    assertThat(onMain { inner.currentPosition }).isEqualTo(7_000L)
    assertThat(onMain { inner.currentMediaItemIndex }).isEqualTo(1)
  }

  @Test
  fun everythingElseStillForwards() {
    // A ForwardingPlayer that broke ordinary delegation would be worse than no seam at all.
    val muPlayer = player(NeverResume)

    onMain {
      muPlayer.setMediaItems(items)
      muPlayer.playWhenReady = false
    }

    assertThat(onMain { muPlayer.mediaItemCount }).isEqualTo(3)
    assertThat(onMain { inner.playWhenReady }).isFalse
    assertThat(onMain { muPlayer.currentMediaItem?.mediaId }).isEqualTo("a")
  }
}
```

- [ ] **Step 4: Implement `MuPlayer`**

`core/media/src/main/kotlin/app/muplay/media/MuPlayer.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * The structural enforcement of spec section 3.
 *
 * **All six** `setMediaItem(s)` overloads are overridden and funnelled into one place, so no code
 * path — not a `MediaController` in a car, not a headset button, not a feature written next year —
 * can set a playback position. Only [resumePolicy] can. Miss one overload and the guarantee is
 * gone entirely, which is why `MuPlayerTest` counts the policy's calls rather than merely checking
 * that one of them happened.
 *
 * (The idea is Voice's. Voice is GPL and none of it was read: this is written from spec section 3's
 * description, which is what the licence constraint requires.)
 *
 * The overloads that take a `resetPosition` flag ignore it, deliberately: `resetPosition = false`
 * means "keep the current position", which is precisely the caller-chosen position this class
 * exists to remove. The policy is asked instead, every time.
 */
class MuPlayer(player: Player, private val resumePolicy: ResumePolicy) : ForwardingPlayer(player) {

  override fun setMediaItem(mediaItem: MediaItem) = setResolved(listOf(mediaItem), 0)

  override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
    setResolved(listOf(mediaItem), 0)

  override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
    setResolved(listOf(mediaItem), 0)

  override fun setMediaItems(mediaItems: MutableList<MediaItem>) = setResolved(mediaItems, 0)

  override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
    setResolved(mediaItems, 0)

  override fun setMediaItems(
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) = setResolved(mediaItems, startIndex)

  /**
   * The one place a queue is actually set. Note what is *not* a parameter: the caller's position.
   */
  private fun setResolved(mediaItems: List<MediaItem>, requestedIndex: Int) {
    val target = resumePolicy.resolve(mediaItems.map { it.mediaId }, requestedIndex)
    super.setMediaItems(mediaItems.toMutableList(), target.startIndex, target.startPositionMs)
  }
}
```

> `ForwardingPlayer`'s `setMediaItems` overloads take `MutableList<MediaItem>` in Kotlin because
> the Java signature is `List<MediaItem>` and Kotlin maps a Java `List` parameter to
> `MutableList` for overriding. If the resolved 1.11.0 signatures differ, match them exactly — an
> overload whose signature does not match is not an override, it is a new method that nothing
> calls, and the compiler will only tell you if `override` is present.

- [ ] **Step 5: Run the seam test**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*MuPlayerTest*'`
Expected: PASS, 6/6.

- [ ] **Step 6: Write the failing progress-writer test**

`core/media/src/androidTest/kotlin/app/muplay/media/ProgressWriterTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.MuPlayDatabase
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first code in this project to write a `media_progress` row.
 *
 * Two halves, tested separately because they fail separately: the **write** (a read-modify-write
 * that must not clobber columns this plan does not own) against a real in-memory Room, and the
 * **wiring** (the seven persistence points) against a real ExoPlayer playing real audio.
 */
@RunWith(AndroidJUnit4::class)
class ProgressWriterTest {

  private lateinit var context: Context
  private lateinit var db: MuPlayDatabase
  private lateinit var dao: MediaProgressDao
  private lateinit var scope: CoroutineScope
  private lateinit var cacheDir: File
  private lateinit var songs: List<app.muplay.model.Song>
  private lateinit var streamUrls: List<String>
  private var harness: PlayerHarness? = null

  /** Fixed, so a timestamp assertion is an equality rather than a range. */
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    dao = db.mediaProgressDao()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    cacheDir = File(context.cacheDir, "progress-test-${System.nanoTime()}")
    songs = runBlocking { RealTrackBytes.musicTracks() }
    val client = RealTrackBytes.client()
    streamUrls = songs.map { client.streamUrl(it.id, app.muplay.model.StreamFormat.Raw) }
  }

  @After
  fun tearDown() {
    harness?.release()
    scope.cancel()
    db.close()
    cacheDir.deleteRecursively()
  }

  private fun writer(player: androidx.media3.common.Player) =
    ProgressWriter(player, dao, clock, scope)

  // ---- the write ----------------------------------------------------------------------------

  @Test
  fun aFirstWriteCreatesARowWithThePositionAndTheClocksTime() = runBlocking {
    // No player needed: the write is the unit under test here.
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    subject.write("song-1", positionMs = 12_345L, finished = false)

    val row = dao.find("song-1")!!
    assertThat(row.positionMs).isEqualTo(12_345L)
    assertThat(row.lastPlayedAtEpochMs).isEqualTo(1_700_000_000_000L)
    assertThat(row.isFinished).isFalse
  }

  @Test
  fun thePositionWrittenIsThePositionGiven() = runBlocking {
    // Two observations. A `positionMs` hardcoded to anything passes at most one.
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    subject.write("song-1", positionMs = 1_000L, finished = false)
    subject.write("song-2", positionMs = 9_999L, finished = false)

    assertThat(listOf(dao.find("song-1")!!.positionMs, dao.find("song-2")!!.positionMs))
      .containsExactly(1_000L, 9_999L)
  }

  /**
   * The trap. `media_progress` carries `speed`, `skipSilence` and `gainDb` — none of which this
   * task writes. `speed` and `skipSilence` are the audiobook plan's; `gainDb` is this plan's, and
   * Task 11 is where it starts being written. A writer that constructs a fresh entity and upserts
   * it resets a listener's per-book speed **every five seconds**, which is a data-loss bug that no
   * test of this task's own fields would ever catch.
   */
  @Test
  fun aWriteDoesNotClobberTheColumnsThisPlanDoesNotOwn() = runBlocking {
    dao.upsert(
      MediaProgressEntity(
        mediaId = "chapter-14",
        positionMs = 500L,
        isFinished = false,
        lastPlayedAtEpochMs = 1L,
        speed = 1.4f,
        skipSilence = true,
        gainDb = 6.0f,
      ),
    )
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    subject.write("chapter-14", positionMs = 90_000L, finished = false)

    val row = dao.find("chapter-14")!!
    assertThat(row.positionMs).isEqualTo(90_000L)
    assertThat(row.speed).isEqualTo(1.4f)
    assertThat(row.skipSilence).isTrue
    assertThat(row.gainDb).isEqualTo(6.0f)
  }

  /**
   * `isFinished` is set to `true` and never back to `false`. A ticker that wrote `false` would
   * un-finish a completed book on the next accidental tap. "Un-finish on replay" is real behaviour
   * and it belongs to the plan that has a UI to express it.
   */
  @Test
  fun finishedStaysFinished() = runBlocking {
    val subject = ProgressWriter(NoOpPlayer(), dao, clock, scope)

    subject.write("chapter-14", positionMs = 900_000L, finished = true)
    subject.write("chapter-14", positionMs = 1_000L, finished = false)

    assertThat(dao.find("chapter-14")!!.isFinished).isTrue
    // ...and the position still moved, so "preserved" does not mean "frozen".
    assertThat(dao.find("chapter-14")!!.positionMs).isEqualTo(1_000L)
  }

  // ---- the wiring ---------------------------------------------------------------------------

  @Test
  fun pausingRealPlaybackWritesTheRealPosition() {
    val harness = startPlaying(songs.take(1))
    harness.awaitPositionAtLeast(2_000L)

    harness.onMain { harness.player.pause() }

    // Persistence point 1 and 2 (onPlayWhenReadyChanged, onIsPlayingChanged(false)). Asserted as a
    // *range*, because the number is a real position from a real clock: a hardcoded constant
    // cannot be in this band by accident, and neither can zero.
    val row = awaitRow(songs[0].id)
    assertThat(row.positionMs).isBetween(1_500L, 6_000L)
  }

  @Test
  fun skippingToTheNextTrackLeavesThePreviousTracksPositionBehind() {
    val harness = startPlaying(songs.take(2))
    harness.awaitPositionAtLeast(2_000L)

    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.awaitPositionAtLeast(500L)

    // Persistence points 3 and 4. The first track keeps a real position; the second gets its own
    // row. Two rows, two ids -- which is the whole "progress is a property of the item" claim in
    // its smallest form.
    val first = awaitRow(songs[0].id)
    val second = awaitRow(songs[1].id)
    assertThat(first.positionMs).isBetween(1_500L, 6_000L)
    assertThat(first.mediaId).isNotEqualTo(second.mediaId)
  }

  @Test
  fun playingToTheEndMarksTheItemFinished() {
    val harness = startPlaying(songs.take(1))

    harness.awaitEnded(timeoutMs = 30_000L)

    // Persistence point 5 (STATE_ENDED).
    assertThat(awaitRow(songs[0].id) { it.isFinished }.isFinished).isTrue
  }

  @Test
  fun theTickerWritesWhileNothingElseHappens() {
    val harness = startPlaying(songs.take(3))
    harness.awaitPositionAtLeast(1_000L)

    // Persistence point 6. Nothing is paused, nothing transitions -- the only thing that can
    // produce a row here is the ticker.
    val first = awaitRow(songs[0].id) { it.positionMs > 0 }
    Thread.sleep(ProgressWriter.TICK_MS + 2_000L)
    val second = dao.let { runBlocking { it.find(songs[0].id)!! } }

    assertThat(second.positionMs).isGreaterThan(first.positionMs)
  }

  /**
   * **Spec section 3's whole point, in one test.** A book's row is written, then something else
   * plays entirely, and the book's row is untouched. Plan 3 does not yet *honour* that position on
   * prepare — that is the audiobook plan — but the property that makes honouring it possible is
   * true today, and this is where it is proven.
   */
  @Test
  fun playingSomethingElseDoesNotDisturbAnotherItemsProgress() = runBlocking {
    dao.upsert(
      MediaProgressEntity("a-book-chapter", 3_600_000L, false, 1L, 1.4f, true, 6.0f),
    )

    val harness = startPlaying(songs.take(1))
    harness.awaitPositionAtLeast(2_000L)
    harness.onMain { harness.player.pause() }
    awaitRow(songs[0].id)

    val book = dao.find("a-book-chapter")!!
    assertThat(book.positionMs).isEqualTo(3_600_000L)
    assertThat(book.speed).isEqualTo(1.4f)
    assertThat(book.lastPlayedAtEpochMs).isEqualTo(1L)
  }

  // ---- helpers ------------------------------------------------------------------------------

  private fun startPlaying(items: List<app.muplay.model.Song>): PlayerHarness {
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    lateinit var built: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      built = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(
            DefaultMediaSourceFactory(MuPlayDataSourceFactory(OkHttpClient(), cache).create()),
          )
          .build(),
      )
      writer(built.player).start()
      built.player.setMediaItems(
        items.mapIndexed { index, song ->
          MediaItem.Builder().setMediaId(song.id).setUri(streamUrls[songs.indexOf(song)])
            .setCustomCacheKey(song.id).build()
        },
      )
      built.player.prepare()
      built.player.play()
    }
    harness = built
    return built
  }

  private fun awaitRow(
    mediaId: String,
    predicate: (MediaProgressEntity) -> Boolean = { true },
  ): MediaProgressEntity {
    val deadline = System.currentTimeMillis() + 20_000L
    while (System.currentTimeMillis() < deadline) {
      runBlocking { dao.find(mediaId) }?.takeIf(predicate)?.let { return it }
      Thread.sleep(100)
    }
    throw AssertionError("no media_progress row for $mediaId satisfying the predicate")
  }
}
```

`NoOpPlayer` is the hand-written inert `Player` the four write-focused tests above hand to
`ProgressWriter` — `core/media/src/androidTest/kotlin/app/muplay/media/NoOpPlayer.kt`:

```kotlin
package app.muplay.media

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer

/**
 * A `Player` that plays nothing and holds nothing.
 *
 * Hand-written, not a mock: this project bans mock frameworks, and a mock would in any case add
 * nothing here — the four tests that use it are about the **write**, and never touch the player.
 * `SimpleBasePlayer` supplies every `Player` method from one `State`, so an empty state is a
 * complete, correct, inert implementation in five lines.
 *
 * `ProgressWriter` handles it correctly by construction: `currentMediaItem` is `null` for an empty
 * playlist, so `captureCurrent` and `flushBlocking` return early and only the explicit
 * `write(...)` calls reach the database.
 */
class NoOpPlayer(looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {
  override fun getState(): State =
    State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
}
```

> `SimpleBasePlayer` is `androidx.media3.common.SimpleBasePlayer`; its constructor takes an
> application `Looper` and `getState()` is its one abstract member. Confirm both against the
> resolved 1.11.0 sources — if `State.Builder()` requires more than `setAvailableCommands`, supply
> what it asks for and change nothing else.

- [ ] **Step 7: Implement the writer and wire it into the service**

`core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Writes `media_progress` at spec section 3's seven persistence points, plus a ticker.
 *
 * The seven, and what each one catches:
 *
 * 1. `onPlayWhenReadyChanged` — the user pressing pause, **and** an audio-focus loss pausing for
 *    them. One callback, both causes.
 * 2. `onIsPlayingChanged(false)` — anything that stopped playback that point 1 did not see.
 * 3. `onPositionDiscontinuity` — a seek, or a track boundary. Writes the position of the item
 *    being *left*, from `oldPosition`, which is the only place that number still exists.
 *    **`DISCONTINUITY_REASON_SILENCE_SKIP` is ignored**: silence skipping (the audiobook plan)
 *    moves the position without the listener having moved, and recording it would inch a book
 *    forward every time it skipped a pause.
 * 4. `onMediaItemTransition` — stamps the newly-current item so a "recently played" list is right
 *    even if the listener stops immediately.
 * 5. `onPlaybackStateChanged` at `STATE_IDLE` or `STATE_ENDED`.
 * 6. the ticker, every [TICK_MS].
 * 7. [flushBlocking], called from `MuPlaybackService.onDestroy` — deliberately blocking, because a
 *    coroutine launched into a scope that is about to be cancelled writes nothing.
 *
 * Every write is a **read-modify-write**. `media_progress` carries `speed`, `skipSilence` and
 * `gainDb`, none of which this class writes today: the first two belong to the audiobook plan, and
 * `gainDb` is stamped from the playing item once ReplayGain lands. Constructing a fresh entity here
 * would reset a listener's per-book speed every five seconds. And `isFinished` is only ever set to
 * `true` — writing `false` on a ticker would un-finish a completed book.
 */
class ProgressWriter(
  private val player: Player,
  private val dao: MediaProgressDao,
  private val clock: Clock,
  private val scope: CoroutineScope,
) : Player.Listener {

  private var ticker: Job? = null

  fun start() {
    player.addListener(this)
    ticker = scope.launch {
      while (true) {
        delay(TICK_MS)
        captureCurrent(finished = false)
      }
    }
  }

  fun stop() {
    ticker?.cancel()
    ticker = null
    player.removeListener(this)
  }

  // 1
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
    captureCurrent(finished = false)

  // 2
  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (!isPlaying) captureCurrent(finished = false)
  }

  // 3
  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    if (reason == Player.DISCONTINUITY_REASON_SILENCE_SKIP) return
    val mediaId = oldPosition.mediaItem?.mediaId ?: return
    scope.launch { write(mediaId, oldPosition.positionMs, finished = false) }
  }

  // 4
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
    captureCurrent(finished = false)

  // 5
  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      captureCurrent(finished = playbackState == Player.STATE_ENDED)
    }
  }

  /** 7. Blocking on purpose — see the class documentation. */
  fun flushBlocking() {
    val mediaId = player.currentMediaItem?.mediaId ?: return
    val positionMs = player.currentPosition
    runBlocking { write(mediaId, positionMs, finished = false) }
  }

  suspend fun write(mediaId: String, positionMs: Long, finished: Boolean) {
    val existing = dao.find(mediaId)
    dao.upsert(
      MediaProgressEntity(
        mediaId = mediaId,
        positionMs = positionMs.coerceAtLeast(0L),
        // Only ever set, never cleared. See the class documentation.
        isFinished = finished || (existing?.isFinished ?: false),
        lastPlayedAtEpochMs = clock.millis(),
        // Columns this class does not write. Preserved, not defaulted. `speed` and `skipSilence`
        // are the audiobook plan's; `gainDb` is this plan's and Task 11 turns this line into a
        // stamp from the playing item.
        speed = existing?.speed ?: DEFAULT_SPEED,
        skipSilence = existing?.skipSilence ?: false,
        gainDb = existing?.gainDb ?: DEFAULT_GAIN_DB,
      ),
    )
  }

  private fun captureCurrent(finished: Boolean) {
    val mediaId = player.currentMediaItem?.mediaId ?: return
    val positionMs = player.currentPosition
    scope.launch { write(mediaId, positionMs, finished) }
  }

  companion object {
    /** Spec section 3 asks for 5-10 s. Five: cheap, and the most a crash can cost is five seconds. */
    const val TICK_MS = 5_000L
    const val DEFAULT_SPEED = 1.0f
    const val DEFAULT_GAIN_DB = 0.0f
  }
}
```

`MediaModule` — add the `Clock` and the resume policy:

```kotlin
  /**
   * The project's first injected clock. Global constraint: *"Inject a `Clock`; no direct
   * wall-clock reads outside the injection point."* This is that injection point, and
   * `ProgressWriter` is its only consumer today.
   *
   * `java.time.Clock`, not `kotlinx-datetime`: `java.time` is native at `minSdk 26`,
   * `MediaProgressEntity.lastPlayedAtEpochMs` is already an epoch-millis `Long`, and a datetime
   * library plus a Room type converter would be bought for nothing.
   */
  @Provides
  @Singleton
  fun provideClock(): Clock = Clock.systemUTC()

  /**
   * Plan 3 resumes nothing — spec section 3's stated behaviour for music. The audiobook plan
   * replaces this binding and changes nothing else.
   */
  @Provides
  @Singleton
  fun provideResumePolicy(): ResumePolicy = NeverResume
```

`MuPlayerFactory` — wrap the player and return the seam, not the raw `ExoPlayer`:

```kotlin
class MuPlayerFactory @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dataSourceFactory: MuPlayDataSourceFactory,
  private val loadErrorPolicy: NavidromeLoadErrorHandlingPolicy,
  private val resumePolicy: ResumePolicy,
) {

  /** The raw player, for the two places that genuinely need `ExoPlayer` (audio attributes). */
  fun createExoPlayer(): ExoPlayer = /* unchanged body from Tasks 5 and 6 */

  /**
   * What the session is given. Everything outside this module sees a `Player` that cannot be told
   * where to start.
   */
  fun create(): MuPlayer = MuPlayer(createExoPlayer(), resumePolicy)
}
```

`MuPlaybackService` — build through the seam, install the writer, flush on destroy:

```kotlin
  @Inject lateinit var playerFactory: MuPlayerFactory
  @Inject lateinit var mediaProgressDao: MediaProgressDao
  @Inject lateinit var clock: Clock

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var progressWriter: ProgressWriter? = null

  override fun onCreate() {
    super.onCreate()
    val player = playerFactory.create()
    progressWriter = ProgressWriter(player, mediaProgressDao, clock, serviceScope).also { it.start() }
    // ... notification provider and session builder, unchanged, with `player` in place of the
    // raw ExoPlayer.
  }

  override fun onDestroy() {
    // Persistence point 7, and it must block: a coroutine launched into a scope that is about to
    // be cancelled writes nothing, and this is the last chance to record where the listener was.
    progressWriter?.flushBlocking()
    progressWriter?.stop()
    progressWriter = null
    serviceScope.cancel()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }
```

- [ ] **Step 8: Update the call sites this task's signature changes break**

`MuPlayerFactory` gained a `resumePolicy` constructor parameter and its `create()` now returns a
`MuPlayer` rather than an `ExoPlayer`. Two existing tests construct it directly:

- `AudioFocusTest` (Task 6) builds
  `MuPlayerFactory(context, MuPlayDataSourceFactory(OkHttpClient(), cache), NavidromeLoadErrorHandlingPolicy()).create()`
  and hands the result to `PlayerHarness(player: ExoPlayer)`. Change it to pass `NeverResume` as
  the fourth argument and to call **`createExoPlayer()`** — that test's subject is audio focus and
  becoming-noisy, which are `ExoPlayer` behaviours, and routing it through the seam would add a
  layer with nothing to say about them.
- Any other direct construction the compiler flags. `./gradlew :core:media:compileDebugAndroidTestKotlin`
  finds all of them; there is no need to guess.

Do **not** relax `PlayerHarness` to take a `Player`. It exists to drive an `ExoPlayer`, and
widening it would let a future test wire the harness to something that is not the player behind the
session — which is the exact confusion the module boundary exists to prevent.

- [ ] **Step 9: Run everything**

```bash
./gradlew :core:media:testDebugUnitTest
./gradlew :core:media:connectedDebugAndroidTest
```

Expected: PASS — `ResumePolicyTest` 3/3, `ProgressTableShapeTest` 1/1, `MuPlayerTest` 6/6,
`ProgressWriterTest` 9/9, and every earlier suite still green.

- [ ] **Step 10: Prove the seam and the writer can fail**

1. Delete **one** override from `MuPlayer` — the `setMediaItem(MediaItem)` one, which names no
   position and therefore looks harmless. Expect
   `theOverloadsThatTakeNoPositionStillGoThroughThePolicy` and
   `allSixOverloadsConsultTheResumePolicy` to fail. Repeat for each of the six in turn; **all six
   must be individually detectable**, and record which test catches each.
2. In `MuPlayer.setResolved`, pass the caller's `startPositionMs` through instead of the policy's.
   Expect `aCallersRequestedPositionNeverReachesThePlayer` to fail.
3. In `ProgressWriter.write`, replace the read-modify-write with a fresh
   `MediaProgressEntity(mediaId, positionMs, finished, clock.millis(), 1f, false, 0f)`. Expect
   `aWriteDoesNotClobberTheColumnsThisPlanDoesNotOwn` and `finishedStaysFinished` to fail.
   **This is the trap, reproduced on demand.**
4. Remove the `DISCONTINUITY_REASON_SILENCE_SKIP` guard. Nothing in this plan skips silence, so
   **no test fails** — record that, and add the assertion the audiobook plan will need instead of
   pretending this plan gated it. Honesty about an ungated line beats a test that cannot fail.
5. Replace `clock.millis()` with a literal. Expect `aFirstWriteCreatesARowWithThePositionAndTheClocksTime`
   to fail.
6. Cancel `serviceScope` before `flushBlocking()` in `onDestroy`. Expect no *unit* test to fail —
   which is exactly why the flush is blocking, and why Task 10's Tier 2 journey covers it.

- [ ] **Step 11: Record the probes, re-measure, commit**

Add mutations 1, 2, 3 and 5 to `ci/mutation-probes.sh`, and record 4 and 6 in the task report as
**known ungated lines with the plan that gates them named** — the script's own header is explicit
that recording an answer is not the same as generating a question, and an honest "no probe exists
for this" is worth more than a probe that passes vacuously.

Re-measure `:core:media`'s floors. `ResumePolicy`/`NeverResume` and `ProgressTableShapeTest`'s
subject are JVM-enforceable; `MuPlayer` and `ProgressWriter` are instrumented.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): the ForwardingPlayer seam and the progress writer"
```

---

## Task 9: `:feature:player` — the Compose player UI over a `MediaController`

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackLauncher.kt`
- Create: `feature/player/build.gradle.kts`
- Create: `feature/player/src/main/kotlin/app/muplay/player/PlayerUiState.kt`
- Create: `feature/player/src/main/kotlin/app/muplay/player/PlayerViewModel.kt`
- Create: `feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt`
- Create: `feature/player/src/main/kotlin/app/muplay/player/MiniPlayer.kt`
- Create: `feature/player/src/main/kotlin/app/muplay/player/Artwork.kt`
- Test: `feature/player/src/test/kotlin/app/muplay/player/PlayerUiStateTest.kt`
- Modify: `feature/library/build.gradle.kts`
- Modify: `feature/library/src/main/kotlin/app/muplay/library/LibraryViewModel.kt`
- Modify: `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/app/muplay/library/AlbumViewModel.kt`
- Modify: `feature/library/src/main/kotlin/app/muplay/library/AlbumScreen.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/PlayerRoute.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Modify: `build.gradle.kts` (`:feature:player` floors)

**Interfaces:**
- Consumes:
  - `PlaybackConnection.state: StateFlow<PlaybackState>`, `.controller(): MediaController`,
    `.release()` (Task 5); `PlaybackState(isPlaying, isBuffering, mediaId, title, artist,
    albumTitle, artworkUri, positionMs, durationMs, hasNext, hasPrevious)` and
    `PlaybackState.NOTHING_PLAYING` (Task 5).
  - `QueueRepository.mediaItems(queue)` and `PlaybackQueue.of(songs, startIndex)` (Tasks 4, 6).
  - **Plan 2 Task 9's `:feature:library` surface** — `LibraryUiState.Content(libraries,
    selectedLibraryId, query, albums, shuffled, discardedOutOfScope, syncMessage)`,
    `LibraryViewModel(uiState, selectLibrary, search, shuffle, refresh)`,
    `LibraryScreen(onAlbumClick, modifier, viewModel)`, `AlbumUiState.Content(album, songs)`,
    `AlbumViewModel`, `AlbumScreen(modifier, viewModel)`, and Plan 2 Task 10's `MuPlayApp`,
    `StartDestination`, `LibraryRoute`, `AlbumRoute`. **All of these are Plan 2's and may have
    landed with different names.** Read the real files and adapt; do not create parallel screens.
- Produces:
  - `class PlaybackLauncher @Inject constructor(queueRepository, playbackConnection)` with
    `suspend fun play(songs: List<Song>, startIndex: Int)`
  - sealed `PlayerUiState` with `data object NothingPlaying` and
    `data class Content(playback: PlaybackState, displayPositionMs: Long, isScrubbing: Boolean)`
  - `internal fun playerUiState(playback: PlaybackState, scrubPositionMs: Long?): PlayerUiState`
  - `internal fun formatDuration(millis: Long): String`
  - `@HiltViewModel class PlayerViewModel` with `uiState: StateFlow<PlayerUiState>`,
    `fun playPause()`, `fun next()`, `fun previous()`, `fun scrubTo(ms: Long)`, `fun commitScrub()`
  - `PlayerScreen(modifier: Modifier = Modifier, viewModel: PlayerViewModel = hiltViewModel())`
  - `MiniPlayer(onOpenPlayer: () -> Unit, modifier: Modifier = Modifier, viewModel: PlayerViewModel = hiltViewModel())`
  - `@Serializable data object PlayerRoute : NavKey`

### The module boundary is the point

`:feature:player` depends on `media3-session` and **not** on `media3-exoplayer`. It gets a
`MediaController` and a `StateFlow<PlaybackState>` and nothing else. A feature module that can
construct an `ExoPlayer` eventually constructs one, and then the process holds two players, one of
which is not the one behind the media session — which is how a media app ends up with a
notification that controls nothing and a seek bar that moves the wrong thing.

`:core:media`'s build file already enforces the half of this that a build file can:
`api(libs.media3.session)` and `implementation(libs.media3.exoplayer)`.

### `media3-ui-compose`, and why the state still comes from a `StateFlow`

Spec §9 names `media3-ui-compose` and `-compose-material3`, and both artifacts really do publish
1.11.0 (resolved while this plan was written). They supply Compose state holders —
`PlayPauseButtonState` and friends — that read a `Player` directly.

This plan uses **`PlaybackConnection`'s `StateFlow`** as the source of truth for the screen, and the
reason is a global constraint rather than a preference: *"Immutable `UiState` as `StateFlow`,
collected with `collectAsStateWithLifecycle()`."* A `StateFlow` also gives the mini player and the
full player one shared state instead of two independent subscriptions to the same controller, and —
the part that actually matters for this project — it makes the **state mapping a pure function that
Tier 1 can gate**, which the Compose state holders cannot be.

`media3-ui-compose-material3` is therefore declared but used only for transport-control
composables where it saves real code. If, on reading its resolved API, it does not, **leave it out
of the module's dependencies entirely and say so in the task report** — an unused dependency is
exactly what "dependency minimalism" bans, and Task 10 records the outcome in the spec.

### Artwork, and the salt again

A cover-art URL carries a fresh auth salt per call, so Coil keyed on the URL never hits its cache.
Plan 2 solved this for the library grid with an explicit cache key in `CoverArt.kt`. This module
does the same thing with the one stable identifier it has: **`PlaybackState.mediaId`**. Same
principle as `setCustomCacheKey`, one layer up. When a later plan consolidates the two into
`:core:designsystem`, that is a refactor with two call sites, not a design change.

- [ ] **Step 1: Write the failing state-mapping test**

`feature/player/src/test/kotlin/app/muplay/player/PlayerUiStateTest.kt`:

```kotlin
package app.muplay.player

import app.muplay.media.PlaybackState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test over a pure function. [PlaybackState] carries only primitives and strings — the
 * `artworkUri` is a `String`, not a `Uri` — precisely so this mapping can be gated by the fast
 * tier, which is where every field-level assertion in this project belongs when it can be.
 */
class PlayerUiStateTest {

  private val playing = PlaybackState(
    isPlaying = true,
    isBuffering = false,
    mediaId = "song-1",
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = "https://host/art-1",
    positionMs = 2_000L,
    durationMs = 5_000L,
    hasNext = true,
    hasPrevious = false,
  )

  @Test
  fun `nothing playing is its own state`() {
    assertThat(playerUiState(PlaybackState.NOTHING_PLAYING, scrubPositionMs = null))
      .isEqualTo(PlayerUiState.NothingPlaying)
  }

  @Test
  fun `a state with a media id is content`() {
    // The discriminator is the media id, not `isPlaying`: a paused track is still something the
    // player screen must render, and a screen that emptied itself on pause would be unusable.
    val paused = playing.copy(isPlaying = false)

    assertThat(playerUiState(paused, null)).isInstanceOf(PlayerUiState.Content::class.java)
  }

  @Test
  fun `the content carries the playback state it was given`() {
    val content = playerUiState(playing, null) as PlayerUiState.Content

    // The whole value, so no individual field can be dropped or replaced on the way through.
    assertThat(content.playback).isEqualTo(playing)
  }

  @Test
  fun `the displayed position is the player's own position when nobody is scrubbing`() {
    // Two observations of a value a constant could satisfy.
    assertThat((playerUiState(playing, null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(2_000L)
    assertThat((playerUiState(playing.copy(positionMs = 4_100L), null) as PlayerUiState.Content).displayPositionMs)
      .isEqualTo(4_100L)
  }

  /**
   * While a finger is on the seek bar, the thumb must follow the finger and not the player. Without
   * this, every position tick drags the thumb back to where playback actually is and the bar
   * becomes impossible to use — a bug that is obvious on a device and invisible in a screenshot.
   */
  @Test
  fun `the displayed position is the scrub position while scrubbing`() {
    val content = playerUiState(playing, scrubPositionMs = 4_500L) as PlayerUiState.Content

    assertThat(content.displayPositionMs).isEqualTo(4_500L)
    assertThat(content.isScrubbing).isTrue
    // ...and the underlying playback state is untouched, so releasing the finger has something
    // truthful to fall back to.
    assertThat(content.playback.positionMs).isEqualTo(2_000L)
  }

  @Test
  fun `not scrubbing is reported as not scrubbing`() {
    assertThat((playerUiState(playing, null) as PlayerUiState.Content).isScrubbing).isFalse
  }

  @Test
  fun `a duration formats as minutes and seconds`() {
    // The exact mapped list, one call per input, so a formatter that ignored its argument fails
    // on the second entry rather than passing an `allMatch`.
    val inputs = listOf(0L, 1_000L, 61_000L, 599_000L, 600_000L, 3_661_000L)

    assertThat(inputs.map(::formatDuration))
      .containsExactly("0:00", "0:01", "1:01", "9:59", "10:00", "1:01:01")
  }

  @Test
  fun `an unknown duration formats as a placeholder rather than a negative time`() {
    // `Player.getDuration()` is C.TIME_UNSET until the extractor has read the container.
    // PlaybackConnection maps that to 0, but a negative can still arrive from a stale controller,
    // and "-9223372036854:775" on a lock screen is a memorable bug.
    assertThat(formatDuration(-1L)).isEqualTo("0:00")
    assertThat(formatDuration(Long.MIN_VALUE)).isEqualTo("0:00")
  }
}
```

- [ ] **Step 2: Run it to verify it fails, then create the module**

Run: `./gradlew :feature:player:testDebugUnitTest --tests '*PlayerUiStateTest*'`
Expected: FAIL — the project does not exist yet.

`settings.gradle.kts`:

```kotlin
include(":feature:player")
```

`gradle/libs.versions.toml` — Coil's version (`coil = "3.5.0"`) is already in `[versions]`. Add the
library aliases **only if Plan 2 Task 9 has not already added them** (check first; a duplicate
alias fails the build):

```toml
coil-compose        = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
```

`feature/player/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.player"
}

dependencies {
  // `:core:media` exposes `media3-session` as `api`, so a MediaController is reachable here.
  // `media3-exoplayer` is `implementation` there and is deliberately *not* reachable: a feature
  // module that can build an ExoPlayer eventually does.
  implementation(project(":core:media"))
  implementation(project(":core:designsystem"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.coroutines.core)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
```

`build.gradle.kts` — a `coverageFloors` entry, so `ConventionTest` passes from this task on:

```kotlin
  // `:feature:player`. Two rules, on the line this table draws everywhere else: BRANCH for the
  // author-written logic, LINE for the Compose-bearing files, because the Compose compiler emits
  // synthetic branches inside author method bodies that no test can reach.
  ":feature:player" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.player.PlayerUiStateKt",
        "app.muplay.player.PlayerUiState",
        "app.muplay.player.PlayerUiState*",
      ),
    ),
    // Measured in Task 10 once the Tier 2 journey composes these for real. From the JVM alone
    // they measure ~0, which is the whole reason `requiresInstrumentedData` exists.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.player.PlayerScreenKt",
        "app.muplay.player.MiniPlayerKt",
        "app.muplay.player.ArtworkKt",
        "app.muplay.player.PlayerViewModel",
      ),
      requiresInstrumentedData = true,
    ),
  ),
```

- [ ] **Step 3: Implement the state and the formatter**

`feature/player/src/main/kotlin/app/muplay/player/PlayerUiState.kt`:

```kotlin
package app.muplay.player

import app.muplay.media.PlaybackState

/**
 * What the player screen renders. A sealed interface, per the constraints, so a `when` over it is
 * exhaustive at every call site.
 *
 * The discriminator between the two states is **`mediaId != null`**, not `isPlaying`: a paused
 * track is still something to render, and a screen that emptied itself on pause would be unusable.
 */
sealed interface PlayerUiState {

  /** Nothing has been queued in this session. The mini player hides; the full screen says so. */
  data object NothingPlaying : PlayerUiState

  /**
   * @property displayPositionMs where the seek bar's thumb goes. **Not always
   *   `playback.positionMs`**: while a finger is on the bar it follows the finger, because
   *   otherwise every position tick drags the thumb back to where playback actually is and the bar
   *   cannot be used at all.
   */
  data class Content(
    val playback: PlaybackState,
    val displayPositionMs: Long,
    val isScrubbing: Boolean,
  ) : PlayerUiState
}

/** Pure mapping — see `PlayerUiStateTest` for why this is a function and not a `ViewModel` method. */
internal fun playerUiState(playback: PlaybackState, scrubPositionMs: Long?): PlayerUiState =
  if (playback.mediaId == null) {
    PlayerUiState.NothingPlaying
  } else {
    PlayerUiState.Content(
      playback = playback,
      displayPositionMs = scrubPositionMs ?: playback.positionMs,
      isScrubbing = scrubPositionMs != null,
    )
  }

/**
 * `m:ss`, or `h:mm:ss` past an hour. A negative or nonsensical input renders as `0:00` rather than
 * as a negative time: `Player.getDuration()` is `C.TIME_UNSET` (a large negative) until the
 * extractor has read the container, and "-9223372036854:775" on a lock screen is a memorable bug.
 */
internal fun formatDuration(millis: Long): String {
  val totalSeconds = (millis.coerceAtLeast(0L)) / 1000
  val seconds = totalSeconds % 60
  val minutes = (totalSeconds / 60) % 60
  val hours = totalSeconds / 3600
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
  else "%d:%02d".format(minutes, seconds)
}
```

- [ ] **Step 4: Run the JVM test**

Run: `./gradlew :feature:player:testDebugUnitTest --tests '*PlayerUiStateTest*'`
Expected: PASS, 9/9.

- [ ] **Step 5: Implement the launcher, the ViewModel and the screens**

`core/media/src/main/kotlin/app/muplay/media/PlaybackLauncher.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way anything in this app starts playing something.
 *
 * A single entry point rather than three ViewModels each assembling a queue: the format decision,
 * the URL construction and the controller handshake all have to happen in the right order, and a
 * second copy of that sequence is a second place for it to drift.
 *
 * `Dispatchers.Main` is not a convenience. A `MediaController` must be built and touched on the
 * thread whose `Looper` it was created with, and every caller here is a ViewModel coroutine that
 * may be on anything.
 */
@Singleton
class PlaybackLauncher @Inject constructor(
  private val queueRepository: QueueRepository,
  private val playbackConnection: PlaybackConnection,
) {

  suspend fun play(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    val queue = PlaybackQueue.of(songs, startIndex.coerceIn(songs.indices))
    val items = queueRepository.mediaItems(queue)
    withContext(Dispatchers.Main) {
      val controller = playbackConnection.controller()
      // startIndex is honoured; the position argument is not, and cannot be -- MuPlayer's seam
      // discards it and asks the ResumePolicy instead. Passing 0 here documents the intent; the
      // guarantee is structural.
      controller.setMediaItems(items, queue.startIndex, 0L)
      controller.prepare()
      controller.play()
    }
  }
}
```

`feature/player/src/main/kotlin/app/muplay/player/PlayerViewModel.kt`:

```kotlin
package app.muplay.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.media.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives both the full player screen and the mini player, from one shared [PlaybackConnection].
 *
 * One ViewModel for both surfaces on purpose: two would mean two subscriptions to the same
 * controller and two chances for them to disagree about what is playing, which a user sees as a
 * mini player showing one track while the screen behind it shows another.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
  private val connection: PlaybackConnection,
) : ViewModel() {

  /** Non-null only while a finger is on the seek bar. See `PlayerUiState.Content`. */
  private val scrubPositionMs = MutableStateFlow<Long?>(null)

  val uiState: StateFlow<PlayerUiState> =
    combine(connection.state, scrubPositionMs, ::playerUiState)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlayerUiState.NothingPlaying,
      )

  init {
    // Connecting is what starts the state flowing at all; without it the screen renders
    // NothingPlaying forever while audio is audibly playing.
    viewModelScope.launch { connection.controller() }
  }

  fun playPause() = viewModelScope.launch {
    val controller = connection.controller()
    if (controller.isPlaying) controller.pause() else controller.play()
  }.let { }

  fun next() = viewModelScope.launch { connection.controller().seekToNextMediaItem() }.let { }

  fun previous() = viewModelScope.launch { connection.controller().seekToPreviousMediaItem() }.let { }

  /** Called on every drag. Moves the thumb only; the player is not touched until [commitScrub]. */
  fun scrubTo(ms: Long) {
    scrubPositionMs.value = ms.coerceAtLeast(0L)
  }

  /** Called when the finger lifts. */
  fun commitScrub() {
    val target = scrubPositionMs.value ?: return
    viewModelScope.launch {
      connection.controller().seekTo(target)
      scrubPositionMs.value = null
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}
```

`feature/player/src/main/kotlin/app/muplay/player/Artwork.kt`:

```kotlin
package app.muplay.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Cover art, cached on the **media id** rather than on the URL.
 *
 * Same principle as `setCustomCacheKey` one layer down, and for the same reason: a cover-art URL
 * carries a fresh auth salt on every call (`StreamUrlTest` pins that), so a loader keyed on the URL
 * re-downloads the same image on every session and never hits its cache. `mediaId` is stable for
 * as long as the server's own id is.
 */
@Composable
fun Artwork(uri: String?, cacheKey: String?, contentDescription: String?, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
  ) {
    if (uri != null) {
      AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
          .data(uri)
          .memoryCacheKey(cacheKey)
          .diskCacheKey(cacheKey)
          .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
```

> `LocalPlatformContext` is `coil3.compose.LocalPlatformContext`, and `dp` is
> `androidx.compose.ui.unit.dp`. Add both imports. If Plan 2 Task 9 has already put an equivalent
> helper in `:core:designsystem`, **use that one and delete this file** — two cover-art loaders with
> two cache-key policies is exactly the drift the design-system module exists to prevent.

`feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt`:

```kotlin
package app.muplay.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The full-screen player.
 *
 * Every label below is asserted verbatim by the Tier 2 journey (`PlaybackJourneyTest`), so a
 * change here is a change there. That duplication is deliberate: the journey is a black-box walk
 * through what a user sees, and a shared string constant would let a wording change pass unnoticed.
 */
@Composable
fun PlayerScreen(
  modifier: Modifier = Modifier,
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  when (val current = state) {
    PlayerUiState.NothingPlaying -> Text(
      text = NOTHING_PLAYING_LABEL,
      textAlign = TextAlign.Center,
      modifier = modifier.fillMaxSize().padding(32.dp),
    )

    is PlayerUiState.Content -> Column(
      modifier = modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Artwork(
        uri = current.playback.artworkUri,
        cacheKey = current.playback.mediaId,
        contentDescription = ARTWORK_DESCRIPTION,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      )
      Text(text = current.playback.title.orEmpty())
      Text(text = current.playback.artist.orEmpty())
      Text(text = current.playback.albumTitle.orEmpty())

      Slider(
        value = current.displayPositionMs.toFloat(),
        onValueChange = { viewModel.scrubTo(it.toLong()) },
        onValueChangeFinished = viewModel::commitScrub,
        // A zero range makes Slider throw; a track whose duration is not yet known renders a
        // disabled-looking full-width bar rather than crashing.
        valueRange = 0f..current.playback.durationMs.coerceAtLeast(1L).toFloat(),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(text = formatDuration(current.displayPositionMs))
        Text(text = formatDuration(current.playback.durationMs))
      }

      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        IconButton(onClick = viewModel::previous, enabled = current.playback.hasPrevious) {
          Text(PREVIOUS_LABEL)
        }
        IconButton(onClick = viewModel::playPause) {
          Text(if (current.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL)
        }
        IconButton(onClick = viewModel::next, enabled = current.playback.hasNext) {
          Text(NEXT_LABEL)
        }
      }
    }
  }
}

internal const val NOTHING_PLAYING_LABEL = "Nothing playing"
internal const val ARTWORK_DESCRIPTION = "Cover art"
internal const val PLAY_LABEL = "Play"
internal const val PAUSE_LABEL = "Pause"
internal const val NEXT_LABEL = "Next"
internal const val PREVIOUS_LABEL = "Previous"
```

> `Icon` is imported but unused above; either use Material icons for the transport buttons or drop
> the import. Text labels are used deliberately: they are what `onNodeWithText` finds in the Tier 2
> journey, and this project has no icon-content-description convention yet. If you switch to icons,
> give each one a `contentDescription` equal to the label above and change the journey to
> `onNodeWithContentDescription`.

`feature/player/src/main/kotlin/app/muplay/player/MiniPlayer.kt`:

```kotlin
package app.muplay.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The bar above the library. Renders nothing at all when nothing is playing — an empty bar taking
 * up 64dp of a browse screen is worse than no bar.
 */
@Composable
fun MiniPlayer(
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PlayerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val content = state as? PlayerUiState.Content ?: return

  Surface(modifier = modifier.fillMaxWidth()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer).padding(8.dp),
    ) {
      Artwork(
        uri = content.playback.artworkUri,
        cacheKey = content.playback.mediaId,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
      )
      Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(text = content.playback.title.orEmpty())
        Text(text = content.playback.artist.orEmpty())
      }
      IconButton(onClick = viewModel::playPause) {
        Text(if (content.playback.isPlaying) PAUSE_LABEL else PLAY_LABEL)
      }
    }
  }
}
```

- [ ] **Step 6: Make Plan 2's screens playable**

`feature/library/build.gradle.kts` — add:

```kotlin
  implementation(project(":core:media"))
```

`LibraryViewModel` — inject `PlaybackLauncher` and add:

```kotlin
  /**
   * Plays a shuffle result. The songs come from `ShuffleRepository`, which has already dropped
   * anything the mirror does not agree belongs to this library — see Plan 2 Task 7. This method
   * adds no scope check of its own, deliberately: a second, weaker copy of that guard here would
   * be a place for the two to disagree.
   */
  fun playShuffled(startIndex: Int) {
    val content = uiState.value as? LibraryUiState.Content ?: return
    viewModelScope.launch { playbackLauncher.play(content.shuffled, startIndex) }
  }
```

`AlbumViewModel` — inject `PlaybackLauncher` and add:

```kotlin
  /** Plays this album from [startIndex], in track order. */
  fun play(startIndex: Int) {
    val content = uiState.value as? AlbumUiState.Content ?: return
    viewModelScope.launch { playbackLauncher.play(content.songs, startIndex) }
  }
```

`AlbumScreen` — each track row becomes clickable, calling `viewModel.play(index)` and then
`onOpenPlayer()`. `LibraryScreen` — each shuffled-song row does the same via `playShuffled(index)`.
Both screens gain an `onOpenPlayer: () -> Unit` parameter. **Read Plan 2 Task 9's real files
first**: the row composables and the exact `LibraryUiState.Content` property names are that task's,
and this task adapts to them rather than the other way round.

`app/src/main/kotlin/app/muplay/ui/navigation/PlayerRoute.kt`:

```kotlin
package app.muplay.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The full-screen player's destination. `@Serializable` for the same reason every other route is. */
@Serializable
data object PlayerRoute : NavKey
```

`app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` — add the destination and the mini player. The
mini player goes in a `Scaffold`'s `bottomBar` **around** the `NavDisplay`, not inside a
destination, so it survives navigation:

```kotlin
      entry<PlayerRoute> { PlayerScreen() }
```

and wrap the `NavDisplay` so every screen except the player itself shows the bar:

```kotlin
  Scaffold(
    bottomBar = {
      // Hidden on the player screen itself: a mini player under a full player is two controls for
      // one thing.
      if (backStack.lastOrNull() != PlayerRoute) {
        MiniPlayer(onOpenPlayer = { backStack.add(PlayerRoute) })
      }
    },
  ) { padding -> NavDisplay(/* ... */, modifier = Modifier.padding(padding)) }
```

`app/build.gradle.kts` — add `implementation(project(":feature:player"))`.

- [ ] **Step 7: Run everything and see it on a device**

```bash
./gradlew build
./gradlew :feature:player:testDebugUnitTest
```

Expected: PASS. Then install the debug APK on the running emulator, play a track from an album,
and confirm by eye: the mini player appears, tapping it opens the player, the seek bar tracks
playback, and dragging it moves the audio. **This is the one step in this plan that is a human
looking at the screen** — the automated proof is Task 10's journey, but a UI nobody has looked at
has a way of being technically correct and unusable.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml build.gradle.kts core/media feature/player feature/library app
git commit -m "feat(player): Compose player and mini player over a MediaController"
```

---

## Task 10: The gates — Tier 2 playback journeys, the coverage table, the spec corrections

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/kotlin/app/muplay/PlaybackJourneyTest.kt`
- Modify: `app/src/androidTest/kotlin/app/muplay/BrowseJourneyTest.kt` (Plan 2 Task 10's file —
  its `reachLibraryScreen` helper is reused; do not fork it)
- Modify: `.github/workflows/e2e.yml`, `.github/workflows/pr.yml`
- Modify: `build.gradle.kts` (the completed floor table)
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: every visible label from Task 9 (`PLAY_LABEL`, `PAUSE_LABEL`, `NEXT_LABEL`,
  `PREVIOUS_LABEL`, `NOTHING_PLAYING_LABEL`), Plan 2 Task 10's `reachLibraryScreen` helper and its
  label constants, **`MuPlaybackService.sessionToken(context)`** (Task 5 — it is the *service*'s
  companion, not `PlaybackConnection`'s; `PlaybackConnection` calls it, which is where the wrong
  name came from. Plan 4 Task 10 and Plan 6 Task 11 both build journeys on this symbol, so it is
  worth getting right here),
  `PlaybackNotification.CHANNEL_ID` (Task 5).
- Produces: Tier 2 journey `PlaybackJourneyTest`; the completed `coverageFloors` entries for
  `:core:media` and `:feature:player`; the spec corrections listed in Step 5.

### What a playback journey has to prove that no unit test can

Spec §10's Tier 2 table has one row for this plan: *"Playback — audio renders, notification and
lock screen respond, survives backgrounding."* Every clause of it is a whole-chain claim:

- **audio renders** — the request the client built, the URL the server honoured, the container the
  extractor parsed, the decoder, the audio pipeline, and the clock. Observed as **the position
  readout on the real screen reaching a later time**, not as `isPlaying`.
- **lock screen responds** — the system's media-button dispatch finding this app's session. Driven
  by a real `KEYCODE_MEDIA_PLAY_PAUSE` through the shell, which is the same path a headset button
  and the lock-screen controls take.
- **survives backgrounding** — the foreground service actually staying up. Observed as the
  position having advanced *while the app was not on screen*.

- [ ] **Step 1: Write the failing playback journey**

`app/build.gradle.kts` — add:

```kotlin
  // The journey reads the app's own live notification and talks to the real session.
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(project(":core:media"))
```

`app/src/androidTest/kotlin/app/muplay/PlaybackJourneyTest.kt`:

```kotlin
package app.muplay

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackNotification
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2: **audio actually plays**, on a real emulator, out of a real Navidrome.
 *
 * The distinction this whole class is built around: `play()` returning, `playWhenReady == true`,
 * `STATE_READY` and a session reporting `isPlaying` are all satisfied by a player that renders
 * silence, by a URL that 404s into a swallowed error, and by a decoder that never produced a
 * sample. What is asserted here instead is that **the position readout on the real screen reaches
 * a later time**, and that it keeps doing so while the app is not on screen.
 *
 * Preconditions this test cannot establish for itself — the container being up,
 * `adb reverse tcp:4533 tcp:4533`, and the emulator's `-feature Minigbm -prop
 * qemu.hardware.gralloc=minigbm` boot flags — are all handled by `ci/prepare-emulator.sh`, which
 * `.github/workflows/e2e.yml` runs and which a local run must run too.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  /** Without the grant the media notification is silently not posted -- an empty array, not an error. */
  @get:Rule
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  @Test
  fun playingATrackFromAnAlbumMakesTheAudioAdvance() {
    startFirstTrackOfTheAlbum()

    // The whole point of the plan, on screen. A five-second track that reaches 0:03 has decoded
    // three seconds of audio; nothing that merely "started playing" can produce this.
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("0:03").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("0:03").assertIsDisplayed()
  }

  @Test
  fun theNotificationShowsTheTrackThatIsPlaying() {
    startFirstTrackOfTheAlbum()
    awaitAudioAdvancing()

    val manager = context.getSystemService(NotificationManager::class.java)
    val notification = manager.activeNotifications.single { it.packageName == context.packageName }

    assertThat(notification.notification.channelId).isEqualTo(PlaybackNotification.CHANNEL_ID)
    assertThat(
      notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
    ).isIn(MUSIC_TITLES)
    assertThat(notification.notification.actions).isNotEmpty
  }

  /**
   * The lock screen, the headset button and Android Auto's play/pause all arrive the same way: as a
   * media button event the system routes to the active session. Driving a real
   * `KEYCODE_MEDIA_PLAY_PAUSE` through the shell exercises that whole path, and needs no UI
   * automation dependency.
   */
  @Test
  fun aMediaButtonFromTheSystemPausesAndResumesPlayback() {
    startFirstTrackOfTheAlbum()
    awaitAudioAdvancing()
    val controller = connectController()

    shell("input keyevent 85") // KEYCODE_MEDIA_PLAY_PAUSE
    awaitOnMain("playback to pause") { !controller.isPlaying }
    val paused = onMain { controller.currentPosition }
    Thread.sleep(1_500L)
    // Paused means the clock stopped, not merely that a flag flipped.
    assertThat(onMain { controller.currentPosition }).isEqualTo(paused)

    shell("input keyevent 85")
    awaitOnMain("playback to resume") { controller.isPlaying }
    awaitOnMain("the position to move past where it paused") { controller.currentPosition > paused }
  }

  /**
   * Backgrounding. The app goes off screen, and audio has to keep going — which is the entire
   * reason a `MediaLibraryService` with `foregroundServiceType="mediaPlayback"` and
   * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` exists. A missing permission here does not fail a build or
   * an install; it throws `SecurityException` from `startForeground` at exactly this moment.
   */
  @Test
  fun playbackSurvivesTheAppGoingToTheBackground() {
    startFirstTrackOfTheAlbum()
    awaitAudioAdvancing()
    val controller = connectController()

    val beforeHome = onMain { controller.currentPosition }
    shell("input keyevent 3") // KEYCODE_HOME
    Thread.sleep(3_000L)

    val afterHome = onMain { controller.currentPosition }
    assertThat(afterHome)
      .describedAs("position after three seconds on the home screen")
      .isGreaterThan(beforeHome + 2_000L)
    assertThat(onMain { controller.isPlaying }).isTrue
  }

  /**
   * The headline feature, now that it can actually be *played*. Plan 2 proved the shuffle result
   * never contains the audiobook; this proves the thing that comes out of the speaker never is it
   * either.
   */
  @Test
  fun shufflingMusicAndPlayingItNeverPlaysTheAudiobook() {
    reachLibraryScreen()

    repeat(SHUFFLE_ATTEMPTS) {
      composeRule.onNodeWithText(SHUFFLE_LABEL).performClick()
      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(SHUFFLE_HEADING).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onAllNodesWithText(MUSIC_TITLES[0], substring = false)
        .fetchSemanticsNodes().firstOrNull()
      composeRule.onAllNodesWithText(PLAY_SHUFFLE_LABEL)[0].performClick()

      composeRule.waitUntil(TIMEOUT_MILLIS) {
        composeRule.onAllNodesWithText(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
      }
      val controller = connectController()
      val title = onMain { controller.mediaMetadata.title?.toString() }

      // Not `assertDoesNotContain(AUDIOBOOK_TITLE)`: an empty or null title would satisfy that
      // vacuously. What is playing must be one of the three music tracks, by name.
      assertThat(title).describedAs("what is actually coming out of the speaker").isIn(MUSIC_TITLES)
    }
  }

  // ---- helpers ------------------------------------------------------------------------------

  private fun startFirstTrackOfTheAlbum() {
    reachLibraryScreen()
    composeRule.onAllNodesWithText("Open")[0].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(MUSIC_TITLES[0]).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(MUSIC_TITLES[0]).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun awaitAudioAdvancing() {
    val controller = connectController()
    awaitOnMain("audio to advance past one second") { controller.currentPosition > 1_000L }
  }

  private var controller: MediaController? = null

  private fun connectController(): MediaController = controller ?: onMain {
    val connection = PlaybackConnection(context)
    kotlinx.coroutines.runBlocking { connection.controller() }
  }.also { controller = it }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun awaitOnMain(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      if (onMain(condition)) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $description")
  }

  private fun shell(command: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
  }

  /**
   * Identical in intent to `BrowseJourneyTest.reachLibraryScreen` (Plan 2 Task 10) — **reuse that
   * one**. It is reproduced in the shape below only so this file reads on its own; when
   * implementing, extract Plan 2's helper into a shared `JourneyNavigation.kt` in
   * `app/src/androidTest` and call it from all three journey classes. Three copies of a
   * twenty-line navigation sequence is how a label change breaks two tests and fixes one.
   */
  private fun reachLibraryScreen() {
    val needsSetup =
      composeRule.onAllNodesWithText(SERVER_URL_LABEL).fetchSemanticsNodes().isNotEmpty()
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

    // Duplicated from the production code rather than shared with it: a journey is a black-box
    // walk through what a user sees, and a shared constant would let a wording change pass
    // unnoticed. Same stance as Plan 2's journeys.
    const val SERVER_URL_LABEL = "Server URL"
    const val USERNAME_LABEL = "Username"
    const val PASSWORD_LABEL = "Password"
    const val CONNECT_LABEL = "Connect"
    const val CONTINUE_LABEL = "Continue"
    const val SHUFFLE_LABEL = "Shuffle this library"
    const val SHUFFLE_HEADING = "Shuffled"
    const val PLAY_SHUFFLE_LABEL = "Play"
    const val PAUSE_LABEL = "Pause"

    val MUSIC_TITLES = listOf("Track 1", "Track 2", "Track 3")
    const val AUDIOBOOK_TITLE = "Test Book"

    const val MUSIC_ROLE_CHIP = 1
    const val AUDIOBOOK_ROLE_CHIP = 2

    /** Five, not Plan 2's ten: each attempt here starts real audio and costs real seconds. */
    const val SHUFFLE_ATTEMPTS = 5
    const val TIMEOUT_MILLIS = 30_000L
  }
}
```

- [ ] **Step 2: Run the journey, and prove each part can fail**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS — `FirstRunJourneyTest`, `BrowseJourneyTest`, `ScopedShuffleJourneyTest` (all Plan
2's) plus `PlaybackJourneyTest` 5/5.

Then, one mutation at a time, restored after each:

1. Remove `android:foregroundServiceType="mediaPlayback"` from `:core:media`'s manifest. Expect
   `verifyDebugManifest` to fail at build time — i.e. the journey never even runs, which is the
   point of having a build-time gate for it. Then remove only the
   `FOREGROUND_SERVICE_MEDIA_PLAYBACK` **permission** while leaving the type, temporarily drop it
   from `requiredDeclarations`, and expect `playbackSurvivesTheAppGoingToTheBackground` to fail
   with a `SecurityException` in logcat. **Record that logcat line**; it is what a future
   contributor will search for.
2. In `MuPlaybackService.onCreate`, do not call `setSessionActivity`. Expect the notification test
   to still pass and `tappingTheNotificationHasSomewhereToGo` (`:core:media`) to fail — two
   suites, one defect, and only one of them catches it.
3. Point `LibraryViewModel.playShuffled` at `content.albums.flatMap { … }` — anything that
   bypasses `ShuffleRepository`'s scope guard. Expect
   `shufflingMusicAndPlayingItNeverPlaysTheAudiobook` to fail.
4. Stop the Navidrome container and re-run. Expect red, not green.
5. Skip `./ci/prepare-emulator.sh` (no `adb reverse`) and re-run. Expect the connect attempt to
   time out — spike S1's finding that a blocked connection manifests as a **silent connect
   timeout** is why this shows up as a `waitUntil` timeout naming nothing. Record the message.

- [ ] **Step 3: Complete and prove the coverage table**

Run the whole thing, in the order the two tiers actually run it:

```bash
./gradlew test
./gradlew jacocoJvmCoverageVerification
# emulator + container up:
./ci/prepare-emulator.sh
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
```

For **every** module this plan touched — `:core:model`, `:core:network`, `:core:testing`,
`:core:media`, `:feature:library`, `:feature:player`, `:app` — read the measured per-class ratios
out of each module's `jacocoTestReport.xml` and make the `coverageFloors` entry match:

- **Branch ≥ 0.90 for non-UI code, line ≥ 0.90 for `@Composable`-bearing files.** Where a measured
  branch ratio on non-UI code is below 0.90, the answer is another test, not a lower floor.
- **`requiresInstrumentedData` is a measurement, not a judgement.** Delete the instrumented `.ec`
  files, run `jacocoJvmCoverageVerification`, and set the flag on exactly the floors that fail.
- **Every floor must be able to fail.** For each module, delete one assertion, confirm its floor
  goes red, restore it. Record which assertion, per module, in the task report.
- **No `COVERAGE:` warning may be left standing.** `warnUngatedClasses` and `warnVacuousFloors`
  print to the build log and as GitHub annotations. `:core:media` is a large new module and will
  produce several on the first run; each one is either a class that needs a floor or a floor that
  matches nothing.
- Confirm `ConventionTest`'s `every Gradle project has a coverage floor` passes with `:core:media`
  and `:feature:player` both in the table, in the exact `"path" to listOf(` form that test's regex
  matches.

`:core:media`'s floors need particular care, because the module holds five different kinds of code
and one blended `BUNDLE` rule would hide a regression in any of them behind the others:

| Kind | Classes | Metric | Tier |
|---|---|---|---|
| Pure decisions | `StreamRetryPolicy`, `PlaybackAudioAttributes`, `PlaybackQueue`, `ResumePolicy`/`NeverResume` | BRANCH | Tier 1 — **these must clear their floors from JVM data alone** |
| Media3 adapters | `NavidromeLoadErrorHandlingPolicy`, `TrackIdCacheKeyFactory`, `MuPlayer`, `ContentTypeSwitcher` | BRANCH | instrumented |
| Mapping and repositories | `MediaItems`, `QueueRepository`, `ProgressWriter`, `PlaybackLauncher` | BRANCH | instrumented |
| Android plumbing with no author conditional | `MuPlaybackService`, `MuPlayerFactory`, `MediaCache`, `PlaybackNotification`, `PlaybackState`, `di.MediaModule` | **LINE** — a BRANCH rule over a zero-branch class matches only zero-total counters and passes silently at every minimum through JaCoCo's `isNaN` path | instrumented |
| Coroutine and `Flow` codegen | `PlaybackConnection$*`, `ProgressWriter$*` | LINE, at a **measured** low floor | instrumented |

The last row follows `:core:database`'s existing precedent exactly: gated low and honestly rather
than excluded by a pattern broad enough to swallow author-written nested classes.

- [ ] **Step 4: Confirm the Tier 2 workflow runs everything, and measure its wall clock**

`.github/workflows/e2e.yml`'s `script:` block should already carry `:core:media` from Task 2:

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest :app:connectedDebugAndroidTest || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

**This job now plays real audio in real time, repeatedly.** `GaplessTest` alone runs two 15-second
experiments; `ProgressWriterTest`, `AudioFocusTest`, `MuPlaybackServiceTest` and
`PlaybackJourneyTest` each play several seconds more, several times over. That is minutes of
irreducible wall clock that no amount of hardware removes.

Measure the real duration of a full run and record it in the task report. If it lands within ten
minutes of `timeout-minutes: 45`, **raise the limit and say what was measured** — a gate that
starts flaking on time gets disabled, which is the worst outcome available. If it lands well past
45, the answer is to split the emulator job in two (module suites and app journeys) rather than to
trim assertions; say which was done and why.

`.github/workflows/pr.yml` — the "Release manifest" step becomes both variants (Task 5):

```yaml
      - name: Merged manifests
        # Release must not carry cleartext; *both* variants must carry the playback service, its
        # foreground-service type, and the three permissions spec section 7 lists. A missing
        # FOREGROUND_SERVICE_MEDIA_PLAYBACK does not fail a build, an install, or a foreground
        # test -- it throws SecurityException from startForeground the first time the app is
        # backgrounded with audio playing.
        run: ./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest
```

- [ ] **Step 5: Correct the spec**

Everything this plan found wrong, incomplete or self-contradictory in
`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`. Per the roadmap's definition of done
item 6, these are corrected **in the spec**, not recorded in a report.

1. **§10, Tier 1, the "Playback goldens" row** — already moved to Tier 2 by Task 7. Confirm the
   edit landed.
2. **§10, Tier 1, the "Session" row** reads *"browse tree and `onPlaybackResumption`;
   `isAutomotiveController` branching"* and sits in the **no-emulator** tier. It has the same
   defect the "Playback goldens" row had: a `MediaSession` needs an Android runtime, and the JVM
   path to one is Robolectric, which §2 and §10 ban. Move the row to Tier 2 and note that **none
   of its three subjects exists yet** — the browse tree and `isAutomotiveController` branching are
   Plan 5's, and `onPlaybackResumption` is the plan that resumes. A row describing three
   unimplemented things, filed under a tier that cannot run them, is a gate that has never fired
   and never will.
3. **§7's permission list** omits `FOREGROUND_SERVICE`. It lists `INTERNET`,
   `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; a foreground service also needs
   the plain `FOREGROUND_SERVICE` permission from API 28, and without it `startForeground` throws.
   Add it.
4. **§4, Streaming** — add the `estimateContentLength` rule, which is new knowledge from Task 1:
   > **Never send `estimateContentLength`.** It makes a transcoded response carry a *guessed*
   > `Content-Length`; ExoPlayer trusts that header for seeking and lands in the wrong place with
   > nothing reported anywhere. Preferring `format=raw` gives a real one.
5. **§4, Streaming, "Never Opus"** — record *where* the rule is enforced, because "never" needs a
   mechanism: `StreamFormat` is a sealed interface with exactly `Raw` and `Mp3`, so `opus` is
   unrepresentable, and `StreamFormat.forSuffix` transcodes both `opus` and `ogg` — the latter
   because a suffix cannot distinguish Ogg-Vorbis from Ogg-Opus.
6. **§3, the `MuPlayer` paragraph** says the seam discards *"the caller's index and position"*.
   That is imprecise in a way that matters: the **index** is queue membership — "play track 3 of
   this album" is a legitimate request — and discarding it unconditionally would break every
   tap-a-track-to-play path in the app. What is unconditionally removed is the **position**;
   `ResumePolicy.resolve(mediaIds, requestedIndex)` is never given one. **The policy must NOT
   override the index** — `"play this book"` and `"play chapter 1 from the top"` both arrive as
   `requestedIndex = 0`, so overriding it would make tapping chapter 1 jump to chapter 14. The
   index belongs to the caller; only the position belongs to the policy. The audiobook plan
   resumes at chapter 14 by choosing the index in its own launcher before `setMediaItems` is
   called. Reword §3 accordingly, and do not reintroduce the override claim — Plan 4 rewords this
   same paragraph, and the two must not contradict.
7. **§5, "Audio focus: a one-line switch"** — record the input. The switch is one line; the signal
   it switches on is the **user's own `LibraryRole` assignment**, carried on
   `MediaMetadata.mediaType` (`MEDIA_TYPE_AUDIO_BOOK_CHAPTER` vs `MEDIA_TYPE_MUSIC`), because
   Navidrome hardcodes `child.Type = "music"` and no server field can answer it.
8. **§9's stack table** — record what was actually adopted for `media3-ui-compose` and
   `-compose-material3` in Task 9. Both artifacts exist at 1.11.0; if the module ended up not
   using one of them, say so and why, rather than leaving the table asserting a dependency the
   build does not have.
9. **§10's Tier 2 table** — add a line under it, in the same form Plan 2 used:
   > Plan 3 added `PlaybackJourneyTest` (audio advances on screen, the media notification names
   > the track, a system media button pauses and resumes, playback survives the app going to the
   > background, and a played shuffle never surfaces the audiobook) and `MuPlaybackServiceTest`
   > (the real service, a real `MediaController`, and the notification the system is holding —
   > in `:app` because `@AndroidEntryPoint` needs an `@HiltAndroidApp` application), plus
   > `:core:media`'s own instrumented suite — `AudioFocusTest`, `GaplessTest`, `MuPlayerTest`,
   > `ProgressWriterTest`, `MediaCacheTest`, `MediaItemsTest`, `QueueRepositoryTest`,
   > `MuPlayDataSourceFactoryTest`. Tier 1 gained `:core:network`'s live
   > `/rest/stream` assertions (Range → 206/416, accurate `Content-Length` on `format=raw`,
   > `Accept-Ranges: none` on a live transcode, and auth carried on the URL) and the pure
   > decisions `:core:media` deliberately keeps free of Android types.

- [ ] **Step 6: Final green run and commit**

Every one of these must pass:

```bash
./gradlew build
./gradlew verifyNoMockFrameworks
./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest
./gradlew jacocoJvmCoverageVerification
./gradlew :core:network:liveNavidromeTest                                  # container up
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
./ci/mutation-probes.sh                                                    # every probe still caught
```

```bash
git add app build.gradle.kts .github/workflows ci/mutation-probes.sh docs/superpowers/specs
git commit -m "ci: tier 2 playback journeys, the completed coverage table, and the spec corrections"
```

---

## Task 11: ReplayGain — parsed, mirrored, and applied as a gain stage

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/ReplayGain.kt`
- Modify: `core/model/src/main/kotlin/app/muplay/model/Song.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt`
- Modify: `core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt`
- Create: `core/network/src/test/kotlin/app/muplay/network/ReplayGainMappingTest.kt`
- Create: `core/testing/src/main/resources/fixtures/get-album-replay-gain.json`
- Modify: `core/database/src/main/kotlin/app/muplay/database/entity/SongEntity.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt` (version 4 → 5)
- Create: `core/media/src/main/kotlin/app/muplay/media/ReplayGainPolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/GainAudioProcessor.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ReplayGainController.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/MuPlayRenderersFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt` (one line — see Task 8)
- Test: `core/media/src/test/kotlin/app/muplay/media/ReplayGainPolicyTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/GainAudioProcessorTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ProgressWriterTest.kt` (extend)
- Create: `app/src/androidTest/kotlin/app/muplay/ReplayGainJourneyTest.kt`
- Modify: `ci/seed-fixtures.sh`, `ci/fixtures.md5`, `ci/configure-libraries.sh`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`, `.github/workflows/e2e.yml`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Interfaces:**
- Consumes: `Song` (`:core:model`, Plan 2 Task 3), `SongEntity` and `MirrorMapper` (`:core:database`,
  Plan 2 Task 5), `MuPlayDataSourceFactory` (Task 2), `MuPlayerFactory` (Tasks 5–8),
  `ProgressWriter` and `DEFAULT_GAIN_DB` (Task 8), `CapturingAudioSink` and `PlayerHarness`
  (Tasks 2 and 7), `MediaProgressEntity` / `MediaProgressDao` (`:core:database`, Plan 2 Task 1).
- Produces:
  - `data class ReplayGain(val trackGainDb: Float?, val albumGainDb: Float?, val peakAmplitude: Float?)`
    in `:core:model`
  - `Song.replayGain: ReplayGain?`
  - `SongEntity.replayGainTrackDb`, `.replayGainAlbumDb`, `.replayGainPeak` — three nullable
    `Float` columns; `MuPlayDatabase` version **5**
  - `object ReplayGainPolicy` with `fun gainDbFor(replayGain: ReplayGain?): Float?`,
    `fun linearGain(gainDb: Float?, peakAmplitude: Float?): Float`, and
    `const val UNCHANGED = 1.0f`, `MIN_GAIN_DB = -24.0f`, `MAX_GAIN_DB = 12.0f`
  - `class GainAudioProcessor : BaseAudioProcessor` with `fun setLinearGain(gain: Float)`
  - `class ReplayGainController(processor: GainAudioProcessor) : Player.Listener` with
    `fun applyTo(mediaItem: MediaItem?)`
  - `class MuPlayRenderersFactory(context, gainProcessor, extraProcessors)` — an **audio-only**
    `RenderersFactory` for production
  - `MediaItems.KEY_REPLAY_GAIN_DB`, `MediaItems.KEY_REPLAY_GAIN_PEAK` — the two `MediaMetadata`
    extras the controller reads
- **Plan 4 interaction:** Plan 4's Scope discipline says *"`media_progress.gainDb` stays unwritten
  and unapplied … it is nobody's task yet"*, and Plan 4 Task 10 is scheduled to write those words
  into spec §5. **That sentence is false once this task lands.** Plan 4's grain correction (speed and
  `skipSilence` moving to `book_settings`) stands untouched and is still Plan 4's; only the five
  words "unwritten and unapplied" must go. This plan does not edit Plan 4 — Task 11 Step 11 corrects
  the **spec** directly, which is the artefact both plans are correcting, so whichever lands second
  finds the sentence already right.

### Why this is here at all, and why it is not a loudness engine

Spec §4: *"ReplayGain is exposed but **not applied** server-side; the client applies it."* Nothing in
this project applied it, and the reason it went unnoticed for seven plans is instructive: this plan's
Scope discipline pointed at Plan 4 because spec §5 lists gain beside per-item speed, and Plan 4's
Scope discipline pointed back with *"it is nobody's task yet"*. `Song` carried no field for the
value, so it was never even parsed off a response that was already carrying it.

**The user impact is on the headline feature.** Library-scoped shuffle draws fifty tracks from across
a whole library — different albums, different masters, different decades. That is precisely the
situation ReplayGain exists for, and precisely the situation where its absence is loudest: the user
reaches for the volume knob every third track, in an app whose reason to exist is that the shuffle is
better here. The tags are already in their files and already on the wire.

**And it is a gain adjustment, nothing more.** No loudness analysis, no scanning, no EBU R128
measurement, no normalisation of untagged material, no album-versus-track *mode* the user chooses.
Three numbers off the response, one multiply per sample, and a clamp so a corrupt tag cannot deafen
anyone. Album-gain mode is a **stated non-goal**: the policy prefers the track gain and falls back to
the album gain only when a file carries no track gain, because a shuffled queue has no album to be
consistent within, and that is the queue this feature exists for.

### The one place this gets structurally interesting

The value has to be available **before the track has ever been played**. That rules out
`media_progress`, whose rows only exist for items with a history — every track in a fresh shuffle is
a first play. So the value rides the same path as every other fact about a song: response → `Song` →
mirror → `MediaItem`. `songs` gains three nullable columns and the schema moves **4 → 5**, with no
`Migration`, for exactly the reason Plan 2 gives every time it does this: nothing has shipped,
`provideDatabase` still carries the pre-release `fallbackToDestructiveMigration(dropAllTables = true)`
escape hatch, and the mirror is a cache of the server that costs one sync to rebuild. **That escape
hatch must still be deleted before the first release** — this task does not make it more permanent,
it makes it load-bearing one more time.

`media_progress.gainDb` is then written for the first time in the project's life, by Task 8's writer,
as a **record of the gain the item was played at** — one authority (the file's tag), one writer (the
`ProgressWriter`, because Plan 6 needs exactly one writer following the player switch), and a column
that stops being decoration.

### What the gate can see, and what it cannot

| Claim | Where it is proved |
|---|---|
| the server reports `replayGain` and the client parses every field | Tier 1 — a fixture captured from the pinned container, validated against the vendored OpenAPI oracle, plus a mapping test per field |
| dB becomes the right linear multiplier, and a peak clamps it | Tier 1 — `ReplayGainPolicyTest`, pure arithmetic with no Android type |
| the multiplier reaches the PCM | Tier 2 — `GainAudioProcessorTest` on a real decoder, comparing **two identical sine tracks that differ only in their tag** |
| the whole chain, from a browse response to what comes out | Tier 2 — `ReplayGainJourneyTest` |
| a real user's real library sounds level | nothing in CI can see this. It is one seeded fixture, not a corpus. Stated here rather than implied by a green build. |

- [ ] **Step 1: Add the tagged fixture, and prove Navidrome reports it**

`ci/seed-fixtures.sh` — add a **second album** rather than tagging an existing track. Track 2 stays
byte-identical, and that is the whole point: the new file is the *same waveform, same encoder
settings*, differing only in a tag, which makes it a control rather than a comparison across two
different recordings.

```bash
# One more track, in its own album so that "Test Album" keeps its three tracks and Task 7's
# gapless arithmetic keeps its inputs. Same 440 Hz sine and the same encoder settings as
# Track 2 -- the ONLY difference is the ReplayGain tag, which is what makes the gain test a
# controlled experiment rather than a comparison of two different recordings.
mkdir -p "$OUT/Music/Test Artist/Gain Album"
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=5:sample_rate=44100" \
  -c:a libmp3lame -b:a 64k -ac 1 -bitexact -map_metadata -1 \
  -metadata title="Quiet Track" -metadata artist="Test Artist" \
  -metadata album="Gain Album" -metadata track="1" \
  -metadata REPLAYGAIN_TRACK_GAIN="-6.00 dB" \
  -metadata REPLAYGAIN_TRACK_PEAK="0.500000" \
  "$OUT/Music/Test Artist/Gain Album/01 - Quiet Track.mp3"
```

`ci/configure-libraries.sh` — the scan-convergence loop waits for `"count":4`. It is now **five**.
Change the literal in both the `[ "$count" = "4" ]` test and the failure message. A hardcoded 4 with
a fifth file on disk makes `configure-libraries.sh` fail after five scan attempts, which reads as a
Navidrome bug and is not one.

Regenerate the checksums and confirm the four existing lines did not move:

```bash
./ci/seed-fixtures.sh
git diff ci/fixtures.md5    # exactly one added line; the four existing hashes UNCHANGED
```

**If any existing hash moved, stop.** Task 7's gapless measurement is arithmetic over those exact
bytes, and a re-encode invalidates every frame count in this plan.

Then prove the server actually reports the tag — this is the assumption the whole task rests on and
it is one `curl` away:

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
curl -s "http://localhost:4533/rest/getAlbumList2.view?type=alphabeticalByName&musicFolderId=1&<auth>" | grep -i replaygain
```

**If Navidrome reports no `replayGain` object, that is the finding and it belongs in the task
report and in the spec**, not in a relaxed assertion. The fallback is to tag with a tool whose output
Navidrome does read (verify against Navidrome's own tag mapping) — never to weaken the test until the
absence passes. Record which tag writer produced the file that worked.

- [ ] **Step 2: Write the failing policy test**

`core/media/src/test/kotlin/app/muplay/media/ReplayGainPolicyTest.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.ReplayGain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Pure arithmetic, no Android type, Tier 1. The decisions here are the ones that can be wrong
 * quietly: a sign error halves everything instead of doubling it, and a missing clamp turns a
 * corrupt tag into a burst of full-scale noise in someone's headphones.
 */
class ReplayGainPolicyTest {

  @Test
  fun `the track gain is preferred over the album gain`() {
    // Two observations, so a policy that always returned one of the two fields fails here.
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(-6.0f, -3.0f, null))).isEqualTo(-6.0f)
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(-9.0f, -3.0f, null))).isEqualTo(-9.0f)
  }

  @Test
  fun `the album gain is the fallback and only the fallback`() {
    // A shuffled queue has no album to be consistent within, so track gain is the right default.
    // Album gain still beats nothing at all for a file that only carries one.
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, -3.0f, null))).isEqualTo(-3.0f)
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, -7.5f, null))).isEqualTo(-7.5f)
  }

  @Test
  fun `an untagged file and an absent object are both no decision at all`() {
    assertThat(ReplayGainPolicy.gainDbFor(null)).isNull()
    assertThat(ReplayGainPolicy.gainDbFor(ReplayGain(null, null, 0.9f))).isNull()
  }

  @Test
  fun `no decision means the samples are not touched`() {
    // Not "gain of 0 dB, applied": literally the multiplicative identity, which the processor
    // fast-paths. An untagged library must be bit-identical to no gain stage at all.
    assertThat(ReplayGainPolicy.linearGain(null, null)).isEqualTo(ReplayGainPolicy.UNCHANGED)
  }

  @Test
  fun `minus six dB is half the amplitude and plus six is double`() {
    // The one piece of arithmetic in the whole feature. 10^(-6/20) = 0.5012.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, null)).isCloseTo(0.5012f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(6.0f, null)).isCloseTo(1.9953f, within(0.001f))
    // Sign check, stated separately because a sign error is the defect that produces a plausible
    // but exactly-wrong result: a track tagged quiet gets louder and nobody reads it as a bug.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, null)).isLessThan(1.0f)
    assertThat(ReplayGainPolicy.linearGain(6.0f, null)).isGreaterThan(1.0f)
  }

  @Test
  fun `a peak clamps a positive gain to the point of clipping and no further`() {
    // +6 dB on a file that already peaks at 0.9 would clip. The clamp is 1/peak, and it is
    // asserted at two peaks so a hardcoded 1.0 cannot satisfy it.
    assertThat(ReplayGainPolicy.linearGain(6.0f, 0.9f)).isCloseTo(1.1111f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(6.0f, 0.5f)).isCloseTo(1.9953f, within(0.001f))
  }

  @Test
  fun `a peak never pushes a gain up`() {
    // The clamp is a ceiling, not a target. A quiet-tagged track with a low peak must stay quiet;
    // "normalise everything to full scale" is a different feature and not this one.
    assertThat(ReplayGainPolicy.linearGain(-6.0f, 0.1f)).isCloseTo(0.5012f, within(0.001f))
  }

  @Test
  fun `an absent or nonsensical peak is ignored rather than trusted`() {
    assertThat(ReplayGainPolicy.linearGain(3.0f, null)).isCloseTo(1.4125f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(3.0f, 0.0f)).isCloseTo(1.4125f, within(0.001f))
    assertThat(ReplayGainPolicy.linearGain(3.0f, -1.0f)).isCloseTo(1.4125f, within(0.001f))
  }

  @Test
  fun `a corrupt tag cannot deafen anyone`() {
    // A tag reading "+90 dB" is a real thing that happens to real files. Clamped at both ends,
    // asserted at both ends, because a one-sided clamp reads as correct until the day it isn't.
    assertThat(ReplayGainPolicy.linearGain(90.0f, null))
      .isEqualTo(ReplayGainPolicy.linearGain(ReplayGainPolicy.MAX_GAIN_DB, null))
    assertThat(ReplayGainPolicy.linearGain(-90.0f, null))
      .isEqualTo(ReplayGainPolicy.linearGain(ReplayGainPolicy.MIN_GAIN_DB, null))
  }
}
```

Run: `./gradlew :core:media:test --tests '*ReplayGainPolicyTest*'`
Expected: FAIL — `Unresolved reference: ReplayGainPolicy`.

- [ ] **Step 3: Write the model and the policy**

`core/model/src/main/kotlin/app/muplay/model/ReplayGain.kt`:

```kotlin
package app.muplay.model

/**
 * The ReplayGain values a server reports for one file.
 *
 * Spec section 4: **ReplayGain is exposed but not applied server-side; the client applies it.**
 * Navidrome reads these out of the file's own tags and hands them over on every browse response;
 * nothing computes them here and nothing ever will — this project does no loudness analysis.
 *
 * Every field is nullable because every field is genuinely optional: an untagged file reports
 * none of them, and a file tagged by an album-oriented tool may carry an album gain and no track
 * gain. `null` means "the file does not say", which is a different fact from `0.0f` ("the file
 * says no adjustment is needed") and the two must not be collapsed.
 *
 * @property trackGainDb the adjustment for this file played on its own, in decibels.
 * @property albumGainDb the adjustment for this file played as part of its album, in decibels.
 * @property peakAmplitude the file's highest sample as a fraction of full scale, so that a
 *   positive gain can be clamped short of clipping. Taken from the track peak, falling back to the
 *   album peak.
 */
data class ReplayGain(
  val trackGainDb: Float?,
  val albumGainDb: Float?,
  val peakAmplitude: Float?,
)
```

`core/model/src/main/kotlin/app/muplay/model/Song.kt` — one field, at the end of the parameter list
so no positional call site moves:

```kotlin
  /**
   * What the file's own ReplayGain tags say, or `null` for an untagged file.
   *
   * Carried on the song rather than on `media_progress` because the player needs it **before** the
   * track has ever been played: every track in a fresh library-scoped shuffle is a first play, and
   * a shuffled library is the exact situation ReplayGain exists for.
   */
  val replayGain: ReplayGain? = null,
```

`core/media/src/main/kotlin/app/muplay/media/ReplayGainPolicy.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.ReplayGain
import kotlin.math.pow

/**
 * Turns what a file's tags say into the number the gain stage multiplies samples by.
 *
 * Pure, and deliberately in a file with no Android import: this is where the defects that matter
 * live — a sign error, a missing clamp — and Tier 1 can see all of them.
 *
 * **Track gain is preferred and album gain is only a fallback.** There is no album-versus-track
 * *mode* for the user to choose, and that is a stated decision rather than an oversight: the queue
 * this feature exists to fix is a library-scoped shuffle, which has no album to be consistent
 * within. A file that carries only an album gain still gets it, because that beats nothing.
 */
object ReplayGainPolicy {

  /** The multiplicative identity. An untagged library is bit-identical to having no gain stage. */
  const val UNCHANGED: Float = 1.0f

  /** A tag below this is a corrupt tag, not a quiet file. */
  const val MIN_GAIN_DB: Float = -24.0f

  /** A tag above this is a corrupt tag, not a quiet file — and it is the one that hurts. */
  const val MAX_GAIN_DB: Float = 12.0f

  private const val DB_PER_AMPLITUDE_DECADE = 20.0f

  /** The decibel adjustment to apply, or `null` when the file does not say. */
  fun gainDbFor(replayGain: ReplayGain?): Float? =
    replayGain?.trackGainDb ?: replayGain?.albumGainDb

  /**
   * [gainDb] as a linear multiplier, clamped so that a corrupt tag cannot deafen anyone and a
   * positive gain cannot push a known peak past full scale.
   *
   * The peak clamp is a **ceiling, not a target**: a quiet-tagged track with a low peak stays
   * quiet. Normalising everything to full scale is a different feature and is not this one.
   */
  fun linearGain(gainDb: Float?, peakAmplitude: Float?): Float {
    if (gainDb == null) return UNCHANGED
    val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    val linear = 10.0f.pow(clamped / DB_PER_AMPLITUDE_DECADE)
    // A peak of zero or a negative one is a nonsensical tag, not a silent file: ignore it rather
    // than dividing by it.
    if (peakAmplitude == null || peakAmplitude <= 0.0f) return linear
    return minOf(linear, 1.0f / peakAmplitude)
  }
}
```

Run: `./gradlew :core:media:test --tests '*ReplayGainPolicyTest*'` — PASS, 9/9.

- [ ] **Step 4: Write the failing mapping test, and record the fixture**

Capture `getAlbum` for `Gain Album` off the live container into
`core/testing/src/main/resources/fixtures/get-album-replay-gain.json`, **exactly as it came off the
wire**, the way Plan 2 Task 3 records every capture.

`core/network/src/test/kotlin/app/muplay/network/ReplayGainMappingTest.kt`:

```kotlin
package app.muplay.network

import app.muplay.testing.OpenApiFixtureValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The `replayGain` object, from the wire to [app.muplay.model.Song].
 *
 * Every field gets its own assertion at **two** values wherever two are available, because Plan 2
 * Task 3's four review rounds established the rule this whole plan is written against: a mapped
 * field replaced by a constant leaves a suite green, and a `replayGain` block mapped to
 * `ReplayGain(-6f, null, null)` regardless of input would pass any single-value check.
 */
class ReplayGainMappingTest {

  @Test
  fun `the recorded capture is what the oracle says a getAlbum response looks like`() {
    // The external oracle, on a body nobody wrote by hand. If Navidrome's `replayGain` block does
    // not validate, that is evidence about the vendored spec and belongs in
    // `NavidromeSpecDeviationTest` as a named, committed deviation -- never in a loosened
    // validator.
    OpenApiFixtureValidator.assertValid("/rest/getAlbum", fixture("get-album-replay-gain.json"))
  }

  @Test
  fun `every replay gain field arrives on the song`() {
    val song = client(fixture("get-album-replay-gain.json")).getAlbum("al-gain", musicFolderId = 1)
      .songs.single { it.title == "Quiet Track" }
    val gain = checkNotNull(song.replayGain)

    assertThat(gain.trackGainDb).isCloseTo(-6.0f, within(0.01f))
    assertThat(gain.peakAmplitude).isCloseTo(0.5f, within(0.01f))
  }

  @Test
  fun `each field comes from its own key and not from a neighbour`() {
    // Four distinct values in one inline body: a mapper that read `albumGain` into `trackGainDb`,
    // or `albumPeak` into the track peak, passes the capture test above and fails here.
    val song = client(
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al-1","name":"A",
      "songCount":1,"duration":5,"song":[{"id":"s-1","title":"T","isDir":false,
      "replayGain":{"trackGain":-6.0,"albumGain":-3.0,"trackPeak":0.5,"albumPeak":0.9}}]}}}
      """.trimIndent(),
    ).getAlbum("al-1", musicFolderId = 1).songs.single()
    val gain = checkNotNull(song.replayGain)

    assertThat(gain.trackGainDb).isEqualTo(-6.0f)
    assertThat(gain.albumGainDb).isEqualTo(-3.0f)
    assertThat(gain.peakAmplitude).isEqualTo(0.5f)
  }

  @Test
  fun `the album peak is the fallback for a file with no track peak`() {
    val song = client(
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al-1","name":"A",
      "songCount":1,"duration":5,"song":[{"id":"s-1","title":"T","isDir":false,
      "replayGain":{"trackGain":-6.0,"albumPeak":0.8}}]}}}
      """.trimIndent(),
    ).getAlbum("al-1", musicFolderId = 1).songs.single()

    assertThat(checkNotNull(song.replayGain).peakAmplitude).isEqualTo(0.8f)
  }

  @Test
  fun `an untagged file carries no replay gain at all, rather than zeroes`() {
    // `null` means "the file does not say"; `0.0` means "the file says no adjustment is needed".
    // Collapsing the two would apply a decision nobody made, to every untagged library there is.
    val song = client(
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al-1","name":"A",
      "songCount":1,"duration":5,"song":[{"id":"s-1","title":"T","isDir":false}]}}}
      """.trimIndent(),
    ).getAlbum("al-1", musicFolderId = 1).songs.single()

    assertThat(song.replayGain).isNull()
  }

  @Test
  fun `an empty replay gain object is also no decision`() {
    val song = client(
      """
      {"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al-1","name":"A",
      "songCount":1,"duration":5,"song":[{"id":"s-1","title":"T","isDir":false,"replayGain":{}}]}}}
      """.trimIndent(),
    ).getAlbum("al-1", musicFolderId = 1).songs.single()

    assertThat(song.replayGain).isNull()
  }
}
```

> `client(body)` and `fixture(name)` are Plan 2 Task 3's helpers in this source set — a
> `MockWebServer` enqueueing one body and a classpath resource reader. **Reuse them; do not fork
> them.** If their names differ on disk, use the real ones and say so in the task report.

Run: `./gradlew :core:network:test --tests '*ReplayGainMappingTest*'`
Expected: FAIL — `Unresolved reference: replayGain`.

- [ ] **Step 5: Parse it, mirror it, and move the schema to 5**

`core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt` — model the object the
**vendored spec** declares, not a subset of it, and add it to `Child`:

```kotlin
/**
 * OpenSubsonic's `ReplayGain` object, as the vendored spec declares it. Modelled whole rather than
 * trimmed to the fields this client uses, because the oracle validates against the whole thing and
 * a partial model is a fixture that silently stops being validated.
 */
@Serializable
data class ReplayGainBody(
  val trackGain: Float? = null,
  val albumGain: Float? = null,
  val trackPeak: Float? = null,
  val albumPeak: Float? = null,
  val baseGain: Float? = null,
  val fallbackGain: Float? = null,
)
```

with `val replayGain: ReplayGainBody? = null` on `Child`.

`core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt` — in the existing `Child` → `Song`
mapping:

```kotlin
  /**
   * `null` rather than an all-null [ReplayGain]: "this file carries no gain tags" and "this file
   * carries tags whose every value happens to be absent" are the same fact, and the player's one
   * question is "is there a decision to apply". `baseGain` and `fallbackGain` are parsed by
   * [ReplayGainBody] so the oracle keeps validating the whole object, and deliberately dropped
   * here — they configure a *server-side* normaliser this client does not use.
   */
  private fun ReplayGainBody?.toDomain(): ReplayGain? {
    val trackGainDb = this?.trackGain
    val albumGainDb = this?.albumGain
    val peak = this?.trackPeak ?: this?.albumPeak
    if (trackGainDb == null && albumGainDb == null) return null
    return ReplayGain(trackGainDb = trackGainDb, albumGainDb = albumGainDb, peakAmplitude = peak)
  }
```

`core/database/.../entity/SongEntity.kt` — three nullable columns, and the reason on the class:

```kotlin
  /**
   * The file's own ReplayGain, mirrored so the player has it **before** the track is first played.
   *
   * Three columns rather than an `@Embedded ReplayGain` because two of the three are independently
   * nullable and an embedded all-null instance is indistinguishable from an absent one — the exact
   * collapse `SubsonicClient` refuses to make one layer up.
   */
  val replayGainTrackDb: Float? = null,
  val replayGainAlbumDb: Float? = null,
  val replayGainPeak: Float? = null,
```

`MirrorMapper` carries them both ways. `MuPlayDatabase` moves to `version = 5` and writes **no**
`Migration`, for the reason stated at the head of this task; confirm the exported schema JSON in
`core/database/schemas/` gains a `5.json` and that `provideDatabase` still carries the pre-release
`fallbackToDestructiveMigration(dropAllTables = true)` line **with its "must be removed before the
first release" comment intact**.

`MediaItems.kt` — the two extras the controller reads, on `MediaMetadata`:

```kotlin
    val extras = Bundle().apply {
      // ... whatever Task 4 already puts here
      ReplayGainPolicy.gainDbFor(song.replayGain)?.let { putFloat(KEY_REPLAY_GAIN_DB, it) }
      song.replayGain?.peakAmplitude?.let { putFloat(KEY_REPLAY_GAIN_PEAK, it) }
    }
```

with `const val KEY_REPLAY_GAIN_DB = "app.muplay.replayGainDb"` and
`KEY_REPLAY_GAIN_PEAK = "app.muplay.replayGainPeak"`. **Absent keys, not sentinel values** — a
`-100f` sentinel is a number the policy would happily clamp and apply.

Run: `./gradlew :core:network:test :core:media:test :core:database:connectedDebugAndroidTest` — green,
including Plan 2's mirror round-trip tests, which now have three more fields to carry.

- [ ] **Step 6: Write the failing gain measurement**

`core/media/src/androidTest/kotlin/app/muplay/media/GainAudioProcessorTest.kt` — on the device, with
a real decoder, a real Navidrome behind it and Task 7's `CapturingAudioSink`.

**This is the assertion the whole task exists for, and it is a controlled experiment**: two files
with the same waveform and the same encoder settings, one tagged `-6.00 dB`. Anything else — an
assertion that the processor was constructed, that `setLinearGain` was called, that a `Player` was
asked to play — is satisfied by a gain stage that multiplies by 1.

```kotlin
  /**
   * The measurement. Track 2 and "Quiet Track" are the same 440 Hz sine at the same bitrate; the
   * only difference between the files is a tag. So the ratio of their rendered amplitudes **is**
   * the gain, with no calibration and no golden file.
   */
  @Test
  fun aTaggedTrackRendersAtTheAmplitudeItsTagAsksFor() {
    val untagged = captureRms(songIdByTitle("Track 2"))
    val tagged = captureRms(songIdByTitle("Quiet Track"))

    // -6.00 dB is 0.5012 of the amplitude. Generous tolerance: the two files are separately
    // encoded, so their decoded amplitudes are close but not identical, and lossy coding of a
    // pure tone is where that shows.
    assertThat(tagged / untagged).isCloseTo(0.5012f, within(0.06f))
    // ...and stated as an inequality too, because a ratio assertion with a wide tolerance is
    // satisfiable by two silences.
    assertThat(untagged).isGreaterThan(1000f)
    assertThat(tagged).isGreaterThan(400f)
  }

  /**
   * The control, and the reason the ratio above is not an artefact of the two files: with the gain
   * stage told to leave the samples alone, the two tracks measure the *same*.
   */
  @Test
  fun withNoGainAppliedTheTwoTracksMeasureTheSame() {
    val untagged = captureRms(songIdByTitle("Track 2"), applyReplayGain = false)
    val tagged = captureRms(songIdByTitle("Quiet Track"), applyReplayGain = false)

    assertThat(tagged / untagged).isCloseTo(1.0f, within(0.06f))
  }

  /**
   * An untagged library must be **bit-identical** to having no gain stage at all, not merely close
   * to it. `GainAudioProcessor` fast-paths a gain of exactly 1.0 into a buffer copy, and this is
   * what stops that fast path being quietly removed as a micro-optimisation nobody needed.
   */
  @Test
  fun anUntaggedTrackIsBitIdenticalWithAndWithoutTheGainStage() {
    val withStage = capturePcm(songIdByTitle("Track 2"), applyReplayGain = true)
    val withoutStage = capturePcm(songIdByTitle("Track 2"), applyReplayGain = false)

    assertThat(withStage).isEqualTo(withoutStage)
    assertThat(withStage.size).isGreaterThan(100_000)
  }

  /**
   * The gain follows the item, which is the property a per-track adjustment actually needs. One
   * queue, two tracks, one capture: the second half of the capture is quieter than the first.
   *
   * **Known and stated:** the controller sets the gain on `onMediaItemTransition`, and up to one
   * audio buffer already in flight can still carry the previous item's gain -- tens of
   * milliseconds at the boundary. The window either side of the transition is excluded from this
   * measurement for that reason, and the reason is written here rather than discovered later as a
   * flake. It is well under the 10 ms of silence Task 7 measures across the same boundary.
   */
  @Test
  fun theGainFollowsTheItemAcrossATransition() {
    val pcm = capturePcm(queueOf("Track 2", "Quiet Track"), applyReplayGain = true)
    val boundary = pcm.size / 2

    val first = rmsOf(pcm, from = 0, to = boundary - GUARD_BYTES)
    val second = rmsOf(pcm, from = boundary + GUARD_BYTES, to = pcm.size)

    assertThat(second / first).isCloseTo(0.5012f, within(0.06f))
  }
```

with, in the companion, `/** One audio buffer at 44.1 kHz mono 16-bit, rounded up. */ const val GUARD_BYTES = 8_192`.

`captureRms` / `capturePcm` build the player the way Task 7's `runExperiment` does — a
`DefaultAudioSink` whose processor chain holds **both** the `GainAudioProcessor` under test and a
`TeeAudioProcessor` feeding a `CapturingAudioSink`, with the gain processor **first** so the capture
sees what the sink would have received. `songIdByTitle` resolves against the real Navidrome, so the
test names files rather than assuming an ordering.

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*GainAudioProcessorTest*'`
Expected: FAIL — `Unresolved reference: GainAudioProcessor`.

- [ ] **Step 7: Write the gain stage and put it in the production pipeline**

`core/media/src/main/kotlin/app/muplay/media/GainAudioProcessor.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The gain stage spec section 4 asks for: *"ReplayGain is exposed but not applied server-side; the
 * client applies it."*
 *
 * One multiply per sample, sitting in the audio processor chain upstream of the `AudioTrack` — the
 * same place Task 7's `TeeAudioProcessor` sits, which is why the measurement in
 * `GainAudioProcessorTest` can see this and works on the `-no-audio` CI emulator.
 *
 * **[isActive] returns `true` unconditionally, and that is deliberate.** A processor's activity is
 * decided when the chain is configured, and the gain changes per *item* long after that; a stage
 * that deactivated itself for a track with no tag would be absent from the chain when the next
 * track needed it. The cost of staying in the chain is paid back by [queueInput]'s fast path,
 * which copies rather than multiplies when the gain is exactly [ReplayGainPolicy.UNCHANGED] — so
 * an untagged library is bit-identical to having no gain stage at all, and
 * `anUntaggedTrackIsBitIdenticalWithAndWithoutTheGainStage` asserts precisely that.
 *
 * Only 16-bit PCM is handled. Anything else is refused loudly through
 * [AudioProcessor.UnhandledAudioFormatException] rather than passed through unchanged, because
 * "the gain silently did not apply" is the failure this whole task exists to remove.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

  /** Written from the application thread by [ReplayGainController], read on the playback thread. */
  @Volatile private var linearGain: Float = ReplayGainPolicy.UNCHANGED

  fun setLinearGain(gain: Float) {
    linearGain = gain
  }

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
    else throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)

  override fun isActive(): Boolean = true

  override fun queueInput(inputBuffer: ByteBuffer) {
    val limit = inputBuffer.limit()
    val output = replaceOutputBuffer(limit - inputBuffer.position())
    val gain = linearGain

    if (gain == ReplayGainPolicy.UNCHANGED) {
      output.put(inputBuffer)
    } else {
      var position = inputBuffer.position()
      while (position < limit) {
        val scaled = (inputBuffer.getShort(position) * gain).toInt()
          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output.putShort(scaled.toShort())
        position += Short.SIZE_BYTES
      }
      inputBuffer.position(limit)
    }
    output.flip()
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/ReplayGainController.kt`:

```kotlin
package app.muplay.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Points [GainAudioProcessor] at whatever the current item's tags asked for.
 *
 * A `Player.Listener` rather than something the queue builder calls, because the current item
 * changes for reasons no caller announces — an automatic transition, a `seekToNext`, a media
 * button on a headset.
 */
class ReplayGainController(private val processor: GainAudioProcessor) : Player.Listener {

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyTo(mediaItem)

  fun applyTo(mediaItem: MediaItem?) {
    val extras: Bundle? = mediaItem?.mediaMetadata?.extras
    val gainDb = extras?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_DB) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)
    val peak = extras?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_PEAK) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_PEAK)
    processor.setLinearGain(ReplayGainPolicy.linearGain(gainDb, peak))
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/MuPlayRenderersFactory.kt` — production now needs a
processor chain, which means production now needs its own `RenderersFactory`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * An **audio-only** `RenderersFactory` carrying [GainAudioProcessor].
 *
 * Supplying a custom `AudioSink` is the supported way to insert a processor into the chain, and
 * until this task the production player used `DefaultRenderersFactory` with no chain of its own —
 * Task 7 built one, but only inside a test. That also makes spec section 11's *"Video"* non-goal a
 * **production** property rather than a test-only observation: there is no video renderer in this
 * array to construct one.
 *
 * Confirm `MediaCodecAudioRenderer`'s and `DefaultAudioSink.Builder`'s exact shapes against the
 * resolved Media3 1.11.0 sources before assuming this compiles, exactly as Task 7 says for the
 * same two constructors.
 */
@UnstableApi
class MuPlayRenderersFactory(
  private val context: Context,
  private val gainProcessor: GainAudioProcessor,
) : RenderersFactory by RenderersFactory({ handler, _, audioListener, _, _ ->
  val audioSink = DefaultAudioSink.Builder(context)
    .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(gainProcessor))
    .build()
  arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink))
})
```

> **If `RenderersFactory` cannot be delegated that way in 1.11.0**, write it as a plain class with a
> single `createRenderers` override doing the same thing. The shape is not the point; a production
> player with a gain stage in its chain is.

`MuPlayerFactory` — one processor and one controller per player, both `@Singleton`-free because they
belong to the player they were built for:

```kotlin
  fun createExoPlayer(): ExoPlayer {
    val gainProcessor = GainAudioProcessor()
    return ExoPlayer.Builder(context, MuPlayRenderersFactory(context, gainProcessor))
      /* ...the rest of Tasks 5 and 6's body, unchanged... */
      .build()
      .also { it.addListener(ReplayGainController(gainProcessor)) }
  }
```

- [ ] **Step 8: Stamp `gainDb`, and extend Task 8's own assertion**

Spec section 5 puts a per-item gain on the progress row. It has never been written. Now that a value
exists, `ProgressWriter.write` stamps it — **one** writer, because Plan 6 needs exactly one following
the player switch:

```kotlin
        // Columns this class does not own the *meaning* of. `speed` and `skipSilence` are the
        // audiobook plan's and stay preserved-not-written. `gainDb` is this plan's: it records the
        // gain the item was actually played at, so the row stops being decoration and Plan 5's
        // watch snapshot carries something true.
        speed = existing?.speed ?: DEFAULT_SPEED,
        skipSilence = existing?.skipSilence ?: false,
        gainDb = currentItemGainDb() ?: existing?.gainDb ?: DEFAULT_GAIN_DB,
```

with

```kotlin
  /** The playing item's ReplayGain decision, or `null` when the item carries none. */
  private fun currentItemGainDb(): Float? =
    player.currentMediaItem?.mediaMetadata?.extras
      ?.takeIf { it.containsKey(MediaItems.KEY_REPLAY_GAIN_DB) }
      ?.getFloat(MediaItems.KEY_REPLAY_GAIN_DB)
```

Extend Task 8's `aWriteDoesNotClobberTheColumnsThisPlanDoesNotOwn` rather than replacing it: `speed`
and `skipSilence` must still survive untouched, and add a second test asserting that a write **for an
item carrying a tag** stores that tag's value, at two different values so a constant cannot satisfy
it, and that a write for an untagged item preserves whatever was there.

- [ ] **Step 9: The Tier 2 journey**

`app/src/androidTest/kotlin/app/muplay/ReplayGainJourneyTest.kt` — the whole chain on a real screen,
reusing Plan 2 Task 10's `reachLibraryScreen` helper and Plan 3 Task 10's playback helpers. It plays
"Quiet Track" from the real UI against the real Navidrome and asserts the rendered amplitude ratio
against "Track 2" played the same way — the same measurement as Step 6, but through browse, the
mirror, the session and the service instead of through a hand-built player.

Its value over Step 6 is precisely the parts Step 6 skips: that `getAlbum`'s `replayGain` survived
the **mirror round trip** and the `MediaItem` mapping. A defect that drops the columns in
`MirrorMapper` leaves every unit test green and this one red.

- [ ] **Step 10: Prove each new assertion can fail**

One mutation at a time, restored after each, message recorded in the task report:

1. In `ReplayGainPolicy.linearGain`, negate the exponent. Expect
   `minus six dB is half the amplitude and plus six is double`, both device measurements, and the
   journey to fail. **This is the sign error**, and it is the one that would otherwise ship as
   "ReplayGain works, but backwards".
2. In `ReplayGainPolicy.linearGain`, return `UNCHANGED` always. Expect every device measurement to
   fail and `withNoGainAppliedTheTwoTracksMeasureTheSame` to still pass — the control staying green
   is what proves the experiment is controlled.
3. In `ReplayGainPolicy.gainDbFor`, return `albumGainDb ?: trackGainDb`. Expect
   `the track gain is preferred over the album gain` to fail.
4. Delete the peak clamp. Expect `a peak clamps a positive gain to the point of clipping` to fail.
5. In `GainAudioProcessor.isActive`, return `linearGain != UNCHANGED`. Expect
   `theGainFollowsTheItemAcrossATransition` to fail — this is the trap the KDoc describes, and it
   must be a test, not a comment.
6. In `MirrorMapper`, drop the three columns on the way in. Expect the journey to fail and every
   `:core:network` test to stay green — which is exactly why the journey exists.
7. In `SubsonicClient`'s mapping, return `ReplayGain(-6f, null, null)` unconditionally. Expect
   `each field comes from its own key and not from a neighbour` and
   `an untagged file carries no replay gain at all` to fail.

Record 1, 2, 5 and 6 in `ci/mutation-probes.sh`.

- [ ] **Step 11: Floors, the spec, and commit**

Add `"app.muplay.media.ReplayGainPolicy"`, `"app.muplay.media.GainAudioProcessor"` and
`"app.muplay.media.ReplayGainController"` to `:core:media`'s **BRANCH** floor includes, and
`"app.muplay.model.ReplayGain"` to `:core:model`'s. Measure from a real report; if a measured ratio
is under 0.90 the answer is another test, not a lower floor. `GainAudioProcessor` needs instrumented
data, so set its `requiresInstrumentedData` by **measuring** — delete the `.ec` files, run
`jacocoJvmCoverageVerification`, and set the flag on exactly the floors that fail.

`.github/workflows/e2e.yml` — `ReplayGainJourneyTest` is in `:app`'s suite, which the workflow
already runs whole; confirm rather than assume, and confirm the fixture checksum step still passes
with the fifth file.

Then the spec, `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`:

1. **§4, Streaming, the ReplayGain line.** *"ReplayGain is exposed but not applied server-side; the
   client applies it"* is right and now has an owner. Say what applying it means, so the next reader
   does not have to guess and no plan has to defer it again:
   > ReplayGain is exposed but **not applied server-side; the client applies it** — as a gain stage
   > in the audio processor chain, upstream of the `AudioTrack`, driven by the file's own
   > `replayGain` tags carried on the library mirror so a shuffled queue has them before a track is
   > first played. Track gain is preferred, album gain is the fallback for a file that carries no
   > track gain, and a positive gain is clamped by the file's peak. **No loudness analysis** is
   > performed here and none is planned: an untagged file is played unchanged.
2. **§5, "Per-item speed, silence skipping and gain, all stored on the progress row."** The gain half
   is now true — `media_progress.gainDb` records the gain the item was played at. Say where the
   *authority* is, because the row is a record and not the source:
   > Per-item gain is applied from the file's own ReplayGain tags (§4) and recorded on the progress
   > row; the file is the authority, the row is the log.

   Leave the `speed` / `skipSilence` half of that sentence alone — Plan 4 Task 10 owns its
   correction to `book_settings` and the two must not collide.

```bash
./gradlew :core:model:test :core:network:test :core:media:test
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
git add core ci build.gradle.kts docs/superpowers/specs app
git commit -m "feat(media): apply ReplayGain, from the file's own tags to the audio pipeline"
```

---

## Definition of done

1. All tasks' tests pass; **both tiers green**.
2. **Tier 2 carries this plan's journeys**: `PlaybackJourneyTest`, `MuPlaybackServiceTest` and
   `ReplayGainJourneyTest` (Task 11) in `:app`'s emulator suite, plus `:core:media`'s instrumented
   classes, and **each has been watched go red**.
3. Coverage ≥ 90% on every module this plan touched — **branch** for non-UI code, **line** for
   `@Composable`-bearing files and for Android plumbing that carries no author-written conditional.
   Every floor measured from a real report, every `requiresInstrumentedData` flag measured rather
   than judged, and **every floor watched fail once**. No module absent from `coverageFloors`, and
   no `COVERAGE:` warning left standing.
4. No mock framework anywhere in the dependency graph — `verifyNoMockFrameworks` resolves every
   test runtime classpath including `:core:media`'s and `:feature:player`'s. `MockWebServer` is a
   real HTTP server and is not one; every stand-in in this plan (`RecordingSource`,
   `RecordingPolicy`, `CapturingAudioSink`, the inert `Player`) is hand-written.
5. Every new external-API assumption is backed by a live test against the Navidrome container:
   `/rest/stream` honours Range with 206/416 and an accurate `Content-Length` under `format=raw`;
   a **live transcode** sends `Accept-Ranges: none` and no `Content-Length`; the stream URL
   authenticates itself; and the audiobook streams as a real MP4 container.
6. **Audio is proven to have advanced, never merely to have been requested.** Every playback
   assertion in this plan is a position that moved, a PCM frame count, a notification the system
   is holding, or a time on the real screen. No test in this plan passes against a player that
   renders silence.
7. **The cache key derives from the track id alone**, proven by replaying a track through a URL
   with a different salt, a different token and a different bitrate and measuring **zero** further
   HTTP requests — with a different track as the control.
8. **Gapless is measured, not assumed**: the longest run of silence across a three-track queue is
   under 10 ms, one queue reconfigures the audio sink strictly fewer times than three separate
   preparations of the same tracks, and the analyser that reports "no silence" has its own Tier 1
   test proving it can see silence.
9. **All six `setMediaItem(s)` overloads go through the resume policy**, each one individually
   proven detectable by deleting it, and no caller-supplied position can reach the player.
10. **`media_progress` is written at spec §3's persistence points without clobbering the columns
    this plan does not own**, and one item's progress survives another item playing — the property
    the whole architecture rests on, asserted today even though honouring it is Plan 4's.
    `gainDb` stops being decoration: Task 11 stamps it from the item's own ReplayGain, through the
    one writer, so that Plan 5's watch snapshot carries something true.
11. **ReplayGain is applied, and the proof is a controlled experiment** (Task 11): two files with
    the same waveform and the same encoder settings, differing only in a tag, render at amplitudes
    whose ratio is the tag. The untagged control measures the same with and without the stage, and
    an untagged track is **bit-identical** with the gain stage in the chain.
12. **The CI corpus can see the case the code handles.** It gained a ReplayGain-tagged track whose
    only difference from Track 2 is a tag, `ci/fixtures.md5` regenerated with **no existing hash
    moved**, and `ci/configure-libraries.sh`'s scan-convergence count moved with it. A gate that
    cannot see the failing case is not a gate, and this feature was unowned for exactly as long as
    no fixture could show it failing.
13. Anything discovered to be wrong in the spec is corrected **in the spec** — the nine items in
    Task 10 Step 5 and the two in Task 11 Step 11, at minimum.
