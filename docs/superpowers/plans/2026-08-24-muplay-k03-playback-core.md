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
`media3-ui-compose-material3`, `media3-test-utils`), Room 2.8.4, Hilt 2.60.1, OkHttp 5.5.0,
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

1. `streamUrl` — an authenticated `format=raw` URL, and the four traps on it
2. `:core:media` — the module, Media3 1.11.0, and the OkHttp data source
3. The media cache, keyed on the track id alone
4. `Song` → `MediaItem`, and the queue as a list of pointers
5. `MuPlaybackService` — `MediaLibraryService`, foreground lifecycle, notification, permissions
6. Audio focus, becoming-noisy, and the content-type switch
7. Gapless, measured in PCM frames
8. `MuPlayer` — the `ForwardingPlayer` seam and the progress writer
9. `:feature:player` — the Compose player UI over a `MediaController`
10. The gates — Tier 2 playback journeys, the coverage table, the spec corrections

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
| `Song(id, libraryId, title, albumId, albumName, artistId, artistName, trackNumber, discNumber, durationSeconds, suffix, coverArtId)` | `:core:model` | **Committed.** Read the file; do not restate it. |
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

**This plan adds no table and no column, so the schema version does not move.** `media_progress`
has existed since Plan 2 Task 1 and Task 8 below is the first code in the project to write a row
into it. If you find yourself writing a Room migration in this plan, stop — you have added a
column that belongs in Plan 4.

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

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | **modify** — include `:core:media`, `:feature:player` |
| `gradle/libs.versions.toml` | **modify** — the six Media3 artifacts, `androidx.media` (for `MediaButtonReceiver`? no — see Task 5), `guava` listenablefuture pin |
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
| `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` | **modify** — §10's Tier 1/Tier 2 tables, and the corrections Task 10 lists |

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
    harness = PlayerHarness(
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
        .setLoadErrorHandlingPolicy(NavidromeLoadErrorHandlingPolicy())
        .build().also { player ->
          // Building on the test thread is not allowed; ExoPlayer.Builder captures the current
          // Looper. Constructed inside runOnMainSync via the harness's own init instead.
        },
    )
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

> **On building the player:** `ExoPlayer.Builder.build()` must run on a thread with a `Looper`.
> Move the construction inside `InstrumentationRegistry.getInstrumentation().runOnMainSync { }` —
> the sketch above shows the shape; the working form is to build the player inside `onMain` and
> pass it to `PlayerHarness`. Verify by running: a violation throws
> `IllegalStateException: Player is accessed on the wrong thread` with a clear message.

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

- [ ] **Step 4: Update Task 2's test for the new constructor**

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

    // Everything else on the port is out of this test's scope. `TODO()` rather than a benign
    // default: a call that should never happen must fail loudly rather than return something
    // plausible.
    override suspend fun ping(): ServerInfo = TODO("not used")
    override suspend fun getMusicFolders(): List<MusicLibrary> = TODO("not used")
    override suspend fun getScanStatus(): ScanStatus = TODO("not used")
    override suspend fun getAlbumList2(musicFolderId: Int, type: AlbumListType, size: Int, offset: Int): List<Album> = TODO("not used")
    override suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs = TODO("not used")
    override suspend fun search3(query: String, musicFolderId: Int, artistCount: Int, albumCount: Int, songCount: Int): SearchResults = TODO("not used")
    override suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song> = TODO("not used")
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

  private fun repository(source: SubsonicSource) =
    QueueRepository(FixedSubsonicSourceProvider(source))

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

`FixedSubsonicSourceProvider` is the one-line adapter that lets this test hand a
`SubsonicSourceProvider` a source directly. **Plan 2 Task 4 defines
`SubsonicSourceProvider(credentialStore, factory)`; check its real constructor before writing
this.** If it is `class SubsonicSourceProvider @Inject constructor(credentialStore: CredentialStore,
factory: SubsonicSourceFactory)` as Plan 2's Interfaces block says, the adapter is:

```kotlin
// core/media/src/androidTest/kotlin/app/muplay/media/FixedSubsonicSourceProvider.kt
package app.muplay.media

import app.muplay.database.SubsonicSourceProvider
import app.muplay.network.SubsonicSource

/**
 * A [SubsonicSourceProvider] that always yields one already-built [SubsonicSource].
 *
 * Not a mock: a three-line hand-written stand-in for a class whose only job is to read credentials
 * and call a factory, in a test whose subject is neither. If `SubsonicSourceProvider` turns out to
 * be `final` with no seam, prefer building a real one over a real `CredentialStore` seeded with
 * throwaway credentials and a `SubsonicSourceFactory { source }` — Plan 2's own
 * `ShuffleRepositoryTest` does exactly that, and copying its setup is better than adding an
 * interface to production code for one test.
 */
fun FixedSubsonicSourceProvider(source: SubsonicSource): SubsonicSourceProvider = TODO(
  "Replace with whichever of the two forms Plan 2's SubsonicSourceProvider actually permits; " +
    "see ShuffleRepositoryTest for the real-CredentialStore form.",
)
```

**Do not leave that `TODO` in the tree.** Step 9 resolves it: read
`core/database/src/main/kotlin/app/muplay/database/SubsonicSourceProvider.kt`, and if the class is
open or an interface, subclass it; otherwise delete this file and build a real provider over a
real `CredentialStore` exactly as `ShuffleRepositoryTest` does, which also means
`QueueRepositoryTest` gains a `CredentialStore` and a DataStore file in `@Before`. Record which
form was used and why in the task report.

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
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/MuPlaybackServiceTest.kt`
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

`core/media/src/androidTest/kotlin/app/muplay/media/MuPlaybackServiceTest.kt`:

```kotlin
package app.muplay.media

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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

  private lateinit var context: Context
  private lateinit var connection: PlaybackConnection
  private lateinit var controller: MediaController
  private lateinit var songs: List<app.muplay.model.Song>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    songs = runBlocking { RealTrackBytes.musicTracks() }
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

  private fun setQueueAndPlay(items: List<app.muplay.model.Song>) {
    val mediaItems = runBlocking {
      QueueRepositoryProvider.forTest(context).mediaItems(PlaybackQueue.of(items))
    }
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
    const val TIMEOUT_MS = 30_000L
  }
}
```

and the one-line test seam that gives the test a `QueueRepository` without a Hilt graph —
`core/media/src/androidTest/kotlin/app/muplay/media/QueueRepositoryProvider.kt`:

```kotlin
package app.muplay.media

import android.content.Context

/**
 * A [QueueRepository] over the real container's credentials, for instrumented tests.
 *
 * Builds whatever `SubsonicSourceProvider` Plan 2 Task 4 actually shipped — see
 * `FixedSubsonicSourceProvider` and `ShuffleRepositoryTest` for the two forms. Hilt is not used
 * here on purpose: `@HiltAndroidTest` in a library module's `androidTest` needs a test
 * application, and every one of these tests is about Media3, not about the object graph.
 */
object QueueRepositoryProvider {
  fun forTest(context: Context): QueueRepository = TODO(
    "Build a SubsonicSourceProvider over a real CredentialStore seeded with " +
      "SubsonicCredentials(\"http://localhost:4533\", \"admin\", \"testpass\"), exactly as " +
      "Plan 2's ShuffleRepositoryTest does, and wrap it in QueueRepository.",
  )
}
```

**Resolve that `TODO` in Step 6 — it must not be committed.** It is written as a `TODO(...)` rather
than as prose precisely so that a build that reaches it fails loudly instead of a reviewer
scrolling past a comment.

- [ ] **Step 6: Run it to verify it fails, then implement**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*MuPlaybackServiceTest*'`
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
./gradlew :core:media:connectedDebugAndroidTest --tests '*MuPlaybackServiceTest*'
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
git add core/media build-logic build.gradle.kts gradle/libs.versions.toml .github/workflows/pr.yml
git commit -m "feat(media): MediaLibraryService with a real notification and a checked manifest"
```

