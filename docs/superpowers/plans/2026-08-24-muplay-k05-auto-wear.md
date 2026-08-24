# MuPlay Kotlin Plan 5 — Android Auto and Wear OS

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The two surfaces where an audiobook is actually listened to. A car head unit browses
MuPlay's library from the `MediaLibraryService` Plan 3 deliberately chose — a root whose first tab
is **Continue**, books that carry their own completion percentage, albums and artists scoped to the
libraries the user tagged Music, and a library-scoped shuffle that a driver can reach in one tap —
and tapping a book there **starts playing at the position that book was left at**, because Plan 4's
resume policy is underneath. A watch runs the same stack standalone: it browses the same tree
through the same session code, streams from the same Navidrome, shows position and chapter, and
writes its own progress row. Nothing in the car or on the watch is a second implementation of
anything.

**Architecture:** The browse tree is split into three layers so that the part a car exercises can
be tested without a car. **Layer 1** is pure Kotlin in `:core:model` — `BrowseId` (a node id that
round-trips a real Navidrome id), `BrowseNode` (one row, Android-free), and `BrowseTree` (a set of
pure functions from data to an *ordered* list of nodes, branching on `BrowseSurface`). **Layer 2**
is `BrowseTreeRepository` in `:core:database`, which resolves a `BrowseId` into the data Layer 1
needs and — separately — expands a playable `BrowseId` into a list of `Song`. **Layer 3** is
`:core:media`: `BrowseItems` maps a `BrowseNode` to a Media3 `MediaItem` with the extras Android
Auto actually reads, and `MuPlayLibraryCallback` implements
`MediaLibrarySession.Callback`'s browse, search and item-resolution methods. Plan 3 left those
methods at their `ERROR_NOT_SUPPORTED` defaults precisely so this plan could fill them without
changing a base class under a live session; that is the whole of the change to
`MuPlaybackService`. Wear OS is a **second application module**, `:wear`, that depends on
`:core:media` and hosts the same `MuPlaybackService`, browsing it with a `MediaBrowser` that
declares itself a watch through a connection hint. `:core:watchlink` carries credentials and
progress rows between the phone and the watch over the Wearable Data Layer, behind a one-method
interface with an in-process fake, because that wire is the one thing in this plan no emulator can
carry.

**Tech Stack:** Kotlin 2.4.10, JDK 21, AGP 9.3.1, **KSP** (never KAPT), Media3 1.11.0
(`media3-session`'s `MediaLibraryService`, `MediaBrowser`, `MediaConstants`; no new Media3
artifact), Room 2.8.4, Hilt 2.60.1, OkHttp 5.5.0, **Compose for Wear OS Material3**
(`androidx.wear.compose:compose-material3`, `-foundation`, `-navigation`), **Horologist**
`horologist-media3-backend` and `horologist-audio-ui` (the specific modules spec §7 names, never
the umbrella), `com.google.android.gms:play-services-wearable`, JUnit 5 (JVM) / JUnit 4 (device),
AssertJ, Turbine, JaCoCo 0.8.12.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` — **§7 is the core text**;
§2 (constraints), §3 (the queue is a list of pointers), §5 (audiobook behaviour that must survive
onto these surfaces), §9 (structure), §10 (testing) and §11 (non-goals) all bind.

**Roadmap:** `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` — Plan 5, *"Car and
watch playback with resume progress"*, depends on 3, better after 4. Both are written.

**Builds directly on:**
- `docs/superpowers/plans/2026-08-24-muplay-k03-playback-core.md` — **Task 5** is the service this
  plan opens up, and its "`MediaLibraryService`, not `MediaSessionService`" section is the reason
  this plan changes no base class. Read it before this one.
- `docs/superpowers/plans/2026-08-24-muplay-k04-audiobooks.md` — **Tasks 4 and 6** own the
  bookshelf, per-book resume and `onPlaybackResumption`. This plan consumes them and redesigns
  none of it.

---

## Global Constraints

Copied verbatim from `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md`'s **Global
constraints** and the spec. Every task inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI. `.kts` build scripts.
- Licence **MIT**. No GPL code may be copied.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Play requires 36 from 2026-08-31.
  *(The one carve-out this plan needs is stated in Task 8 and nowhere else: a Wear OS application
  module cannot ship `minSdk 26` — Wear OS 3 is API 30 — so `:wear` alone raises its own floor.
  Every shared module stays at 26.)*
- **KSP only. KAPT is dead** and KSP1 has been removed upstream.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow`, collected with `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer** unless logic is genuinely
  shared across features.
- Convention plugins in `build-logic/convention`. No copy-pasted build scripts.
- Subsonic client identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- Stream requests force **`format=raw` or `format=mp3`**. **Never Opus.**
- Media3 cache keys derive from the **track id alone** via `setCustomCacheKey`.
- **Book positions are local only.**
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

- **No Robolectric**, no Roborazzi, no ktlint/detekt/spotless. This is the constraint that shapes
  every test in Tasks 1–6: `androidx.media3.common.MediaItem` cannot be built on the JVM (it
  reaches `android.net.Uri` and `android.os.Bundle`, which are unimplemented stubs in `android.jar`
  and throw *"not mocked"*), so **every assertion about a `MediaItem` runs on a device.** Plan 3
  Task 4 hit exactly this and put its field-by-field mapping test in `androidTest`; this plan does
  the same and pushes as much of the tree as possible *above* the `MediaItem` boundary, where a JVM
  test can reach it.
- **Android Auto needs no UI work** (spec §7). No Compose from this app reaches a car screen, and
  nothing in this plan adds a car UI. If you find yourself adding `androidx.car.app`, stop — that
  is the Car App Library for *template* apps, and a media app is not one.
- **Edge-to-edge is enforced at API 35+**; `Scaffold` handles insets. Wear OS has its own
  equivalent — `AppScaffold`/`ScreenScaffold` from Wear Compose Material3 — and Task 9 uses it.
- **Predictive back is default-on and must be implemented.** On the watch this is the swipe-to-
  dismiss gesture, which Wear Compose's `SwipeDismissableNavHost` provides; Task 9 states what it
  does and does not give you for free.
- Permissions: `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (spec §7).
  `:wear` inherits all three through `:core:media`'s manifest, which Plan 3 Task 5 declared there
  rather than in `:app` **for this exact reason** — read that manifest's own comment.
- **`LibraryRole` is the only signal that something is an audiobook.** Navidrome hardcodes
  `child.Type = "music"` and always sets `mediaType = song` (spec §4). Never infer a book from a
  suffix, a folder name, a duration, or a chapter count — including in the browse tree, where the
  temptation is strongest because a "Books" node has to come from somewhere.

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
| 1 | `BrowseId` — the node id, and the round trip a real Navidrome id must survive | every id shape encodes and decodes, including ids containing the separator |
| 2 | `BrowseTree` — one tree, three surfaces, and order as a property | three surfaces produce three different, exactly-asserted, ordered root lists |
| 3 | `BrowseSurfaces` — the `isAutomotiveController` branch, and the one expression no CI can see | four inputs, each independently proven to change the answer |
| 4 | `BrowseItems` and `MuPlayLibraryCallback` — the tree on a real `MediaBrowser` | a real browser reads the real root, in order, with the extras a car reads |
| 5 | Playing from the tree — `onAddMediaItems`/`onSetMediaItems`, and resume at the stored position | tapping a book in the tree makes audio advance **from** the stored second |
| 6 | Voice and search — `onSearch`, `onGetSearchResult` and `ACTION_MEDIA_PLAY_FROM_SEARCH` | a spoken query becomes playing audio, driven by a real intent |
| 7 | The Android Auto declaration and its validator rules, gated at build time | the build fails when the car descriptor is missing, and is watched failing |
| 8 | `:wear` — the module, the convention plugin, and a watch that reaches the same service | an APK that installs on a watch image, refuses to pass on a phone image, and plays |
| 9 | The watch surface — Wear Material3, Horologist, progress, and the audio-output trap | a watch screen that browses, plays and shows a book's position |
| 10 | `WatchLink` — credentials and progress across the wrist, and the wire no emulator can carry | the merge is proven on the JVM; the transport is one file and says so |
| 11 | The gates — the Wear emulator job, what each image proves, coverage, spec corrections | every gate in this plan has been watched failing, and the spec matches the code |

---

## What Plans 2, 3 and 4 hand this plan — consume it, do not rebuild it

Plans 2, 3 and 4 are written and sequenced ahead of this one. At the time this plan was written
the tree carried Plan 2's Tasks 1–3 only (`:core:database` with `MediaProgressEntity`,
`MediaProgressDao`, `KeystoreCipher`, `CredentialStore`, `DataModule`; `:core:network`'s browse
commands, `SubsonicSource`, `SubsonicClient`). **Every symbol below belongs to an earlier plan.
This plan consumes them and must not redefine, rename or re-derive any of them.** Where a name
could not be fixed because the owning plan had not landed, the row says so, and the task that
consumes it says so again at the point of use.

| Symbol | Module | Owner |
|---|---|---|
| `Song(id, libraryId, title, albumId, albumName, artistId, artistName, trackNumber, discNumber, durationSeconds, suffix, coverArtId)` | `:core:model` | **Plan 2, committed.** Read the file. |
| `Album`, `AlbumWithSongs`, `Artist`, `MusicLibrary`, `LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }`, `SearchResults`, `AlbumListType` | `:core:model` | **Plan 2, committed.** |
| `SubsonicSource.coverArtUrl(coverArtId, sizePx)`, `.streamUrl(songId, format)`, `.search3(...)` | `:core:network` | Plan 2 Task 3, Plan 3 Task 1 |
| `SubsonicSourceProvider.current(): SubsonicSource`, `NotConfiguredException` | `:core:database` | Plan 2 Task 4 |
| `CredentialStore(save, load, clear, credentials)` | `:core:database` | **Plan 2 Task 1, committed.** |
| `LibraryRepository(libraries, refreshFromServer, setRole, idsWithRole, allIds, hasUnassignedLibraries)` | `:core:database` | Plan 2 Task 4 |
| `BrowseRepository(artists, albums, albumsByArtist, songs(albumId), album(albumId), search, coverArtUrl)` | `:core:database` | Plan 2 Task 5 |
| `ShuffleRepository.shuffle(libraryId, requestedSize): ShuffleResult`, `DEFAULT_SHUFFLE_SIZE` | `:core:database` | Plan 2 Task 7 |
| `MediaItems.of(song, streamUri, artworkUri, isAudiobook)`, `PlaybackQueue.of(songs, startIndex)`, `QueueRepository.mediaItems(queue)`, `QueueRepository.ARTWORK_SIZE_PX` | `:core:media` | Plan 3 Tasks 4, 6 |
| `MuPlaybackService : MediaLibraryService`, `PlaybackNotification.CHANNEL_ID/NOTIFICATION_ID`, `PlaybackConnection(state, controller(), release(), sessionToken(context))`, `PlaybackState(...)`, `PlaybackState.NOTHING_PLAYING` | `:core:media` | Plan 3 Task 5 (+ Plan 4 Task 7's two extra `PlaybackState` fields) |
| `MuPlayerFactory.createExoPlayer()`, `.create()`, `.wrap(exoPlayer)` | `:core:media` | Plan 3 Task 5, Plan 4 Task 7 |
| `ResumeTarget(startIndex, startPositionMs)`, `ResumePolicy.resolve(mediaIds, requestedIndex)`, `MuPlayer`, `ProgressWriter` | `:core:media` | **Plan 3 Task 8 — and read the correction in "Where the seams sit" below.** |
| `AudiobookRepository(bookshelf(), book(bookId), files(bookId), resumePoint(bookId), settings(bookId), restart(bookId), observeAudiobookItems(), bookIdOf(song))`, `BookSummary(bookId, libraryId, title, author, coverArtId, fileCount, durationMs, positionMs, isFinished, lastPlayedAtEpochMs, remainingMs, progressFraction, hasStarted)`, `ResumePoint(mediaId, positionMs, lastPlayedAtEpochMs)` | `:core:database` | **Plan 4 Task 4.** |
| `AudiobookSnapshot`, `AudiobookItemSource.itemFor(mediaId)`, `AudiobookResumePolicy`, `ResumptionQueue.mostRecent(): PlaybackQueue?` | `:core:media` | **Plan 4 Task 6.** |
| `BookPlaybackLauncher.resume(bookId)/playFile(bookId, mediaId)/restart(bookId)`, `startIndexFor(files, resumeAt)`, `startIndexFor(files, mediaId)` | `:feature:book` | **Plan 4 Task 9.** |
| `ChapterRepository.chaptersFor(song)/chaptersFor(songs)`, `BookTimeline.of/chapterAt/next/previous/bookPositionMs`, `BookChapter`, `BookFile` | `:core:media` | Plan 4 Task 3 |
| `PlayerHarness(player)` — `onMain`, `await`, `awaitState`, `awaitPositionAtLeast`, `awaitEnded`, `assertNoPlaybackError`, `release` | `:core:media` androidTest | Plan 3 Task 2 |
| `VerifyMergedManifestTask.forbiddenAttributes` + **`.requiredDeclarations`**, per-variant `verify<Variant>Manifest` registration | `build-logic` | **Plan 3 Task 5.** `requiredDeclarations` and the per-variant registration are Plan 3's addition; the committed tree has only `forbiddenAttributes` and a release-only task. |
| `ci/prepare-emulator.sh`, `ci/navidrome.compose.yml`, `ci/configure-libraries.sh`, `ci/seed-fixtures.sh`, `ci/fixtures.md5`, `ci/probe-chapters.sh`, `ci/mutation-probes.sh` | `ci/` | Plans 1–4 |
| `reachLibraryScreen` and the label constants | `:app` androidTest | Plan 2 Task 10, Plan 3 Task 10 |

### Where the seams sit, and the one correction that reached this plan late

**Plan 3's `ResumePolicy` may not override the index, and this plan is a direct beneficiary of
that rule rather than an exception to it.** Plan 4's own "Where Plan 3's seam does not fit" section
states it: *"play this book"* and *"play chapter 1 from the top"* both arrive as a list of media ids
and index 0, so a policy that overrode index 0 would make tapping chapter 1 jump to chapter 14.
**The caller decides the index; the policy decides the position.**

That is exactly the division a browse tree needs, and it is why Task 5 is short. When a car plays
the node `book:<id>`, `MuPlayLibraryCallback.onSetMediaItems` builds the queue and chooses
`startIndex` — the file the listener was in, from Plan 4's `AudiobookRepository.resumePoint` — and
passes `C.TIME_UNSET` as the start position. `MuPlayer` discards that position and asks
`AudiobookResumePolicy` for the real one. **This plan writes no resume arithmetic, reads no
`media_progress` row for a position, and calls no `seekTo`.** If you find yourself doing any of
those three, you have re-implemented Plan 4 inside a browse callback.

Three more seam facts, each of which changes what a task below is allowed to do:

1. **`onPlaybackResumption` is Plan 4's** (its Task 6), and it is already implemented when this
   plan starts. It is what the *lock screen* and *headset button* use, and Android Auto's own
   "resume playback" affordance on a fresh connection uses it too. **Task 4 must not override it
   again.** What this plan adds beside it is a *browsable* `Continue` node, which is a different
   thing: `onPlaybackResumption` answers "carry on", the `Continue` node answers "show me my
   books, most recent first, and let me pick".
2. **`MuPlaybackService` builds its session with a `LibraryCallback` that has no members** (Plan 3
   Task 5's last declaration in that file). This plan replaces that class with
   `MuPlayLibraryCallback` and injects it. That single-line change to `MuPlaybackService` is the
   only change to Plan 3's service in Tasks 1–7.
3. **Plan 6 (casting) is being written concurrently and owns routing audio to a renderer.** It
   introduces `PlaybackOutputSwitch.activePlayer: StateFlow<Player?>` and calls
   `MediaSession.setPlayer(...)` when the output changes. **Those names are not fixed.** This plan
   deliberately touches neither: `MuPlayLibraryCallback` reads `session.player` at the moment it is
   called and never caches a `Player`, which is the one property that makes it correct under a
   later `setPlayer`. Task 4 Step 9 states this as an explicit non-dependency, and Task 11 records
   the one cross-plan check to run once both have landed. Do not invent a cast interaction here.

### Hard facts, established while this plan was written

- **The Tier 2 emulator today is one image: `system-images;android-37.0;google_apis;x86_64`**,
  emulator build `15917651`, booted with `-feature Minigbm -prop qemu.hardware.gralloc=minigbm`
  (without which SurfaceFlinger and `system_server` abort on every activity teardown — the full
  evidence is in `ci/prepare-emulator.sh`'s header). `ci/prepare-emulator.sh` asserts API level,
  ABI and the gralloc property before doing `adb reverse tcp:4533 tcp:4533`, and
  `ConventionTest`'s *"the emulator coordinates in e2e yml and prepare-emulator sh cannot drift
  apart"* holds the workflow and the script equal. **A Wear image is a different package, a
  different profile and a different AVD**, so it needs its own preflight and its own drift rule;
  Task 8 adds both rather than widening the existing ones.
- **A phone emulator image is not an Automotive image and this plan does not add one.** The full
  reasoning is in Task 3 and again in Task 11; the short version is that Android Auto is
  *projection* — the app runs on the phone, and the head unit is a display — so the code path this
  plan writes runs on the phone image already. What an Automotive OS image would add is
  **Google's rendering of our tree**, which is not our code, and driving it would mean UiAutomator
  against a system app's UI.
- **`ConventionTest` already anticipates this plan's second application module** by name: its
  `no module configures android or kotlin blocks directly` rule allows `namespace` and
  `applicationId`/`versionCode`/`versionName` in a module's own `android { }` block, and its
  comment says *"namespace/applicationId are genuinely per-module, and a future second application
  module, e.g. the roadmap's Wear OS app, needs its own"*. Everything else `:wear` needs goes in a
  convention plugin.
- **`ConventionTest` will fail a new Gradle project that has no `coverageFloors` entry.**
  `:wear` and `:core:watchlink` both need one.
- **`excludeByteBuddyFromInstrumentedTests`** already strips Byte Buddy from every `androidTest*`
  configuration project-wide, and `configureKotlinAndroid` already sets `testInstrumentationRunner`
  and `enableAndroidTestCoverage` for every Android module. `Jacoco.kt`'s `mergedExecutionData`
  globs **every** project's `build/outputs/code_coverage/**/*.ec`, so instrumented coverage
  produced by one module's device run counts toward another module's report — which is how
  `:core:media`'s browse code gets its coverage from `:app`'s and `:wear`'s device suites.
- **Library 1 is `Music`, library 2 is `Audiobooks`**, wired by `ci/configure-libraries.sh`;
  library 1 is path-pinned and undeletable. Plan 4 Task 1 seeds four books into library 2 and
  commits an `ffprobe`-derived oracle at
  `core/testing/src/main/resources/fixtures/books.tsv`, parsed by `app.muplay.testing.BookFixtures`
  (`TEST_BOOK`, `SECOND_BOOK`, `TAIL_BOOK`, `MULTI_PART_BOOK`, `ALL_BOOKS`, `MUSIC_TRACKS`).
  **This plan's browse assertions use that oracle**, so "the tree lists the books" is checked
  against a list derived from the files rather than from the app.
- **Three Wear-side coordinates cannot be pinned from memory and must be resolved before use** —
  `androidx.wear.compose:compose-material3`, `com.google.android.horologist:horologist-media3-backend`
  / `-audio-ui`, and `com.google.android.gms:play-services-wearable`. Task 8 Step 1 resolves all
  three against their real `maven-metadata.xml` and pins them, exactly the way Plan 3 resolved every
  Media3 artifact. Spec §7 already warns that **Horologist is actively maintained but perpetually
  0.x**; Task 9 confines its API surface to one file for that reason.

### The defect class this plan is written against

Six review rounds on this project have now found the same failure: **assertions that execute but do
not discriminate.** Each round closed one "unit" and left the next unasked — endpoint, then request
parameter, then type, then **field**, then **collection order**, then **argument passthrough on a
delegating method**. The rules bind every test in this plan:

1. **The unit is the field.** For every field this plan's code assigns, an assertion must fail when
   that field becomes a constant.
2. **A value observed at exactly one value is not tested**, and a value observed only as an empty
   list is not observed at all. Vary only the argument under test; hold everything else constant;
   assert both observations.
3. **`allMatch`/`anyMatch`/`none` are vacuously true on an empty collection.** Map the field and
   assert the exact list.
4. **Order is a property**, and **browse-tree child order is meaningful** — it is literally the
   order a driver reads at 70 km/h. `containsExactly`, never `containsExactlyInAnyOrder`, for every
   list of nodes in this plan.
5. **A delegating method must be proved to pass its argument through**, not merely to have been
   called.
6. **A gate reporting the absence of a problem must be provably incapable of staying quiet when it
   did not run.**
7. **Coverage floors cannot catch this class.** A constant field assignment removes no branch;
   verified at the bytecode level on this project, not argued.

**The three analogues that will bite this plan specifically, named here and again at each point of
use:**

- **A browse-tree test that asserts a node *exists*.** `assertThat(children).isNotEmpty` and
  `assertThat(children.map { it.mediaId }).contains("books")` are both satisfied by a tree that
  returns every node under every parent, in any order. Every children assertion below maps a field
  and uses `containsExactly`.
- **An `isAutomotiveController` test where both branches return the same tree.** If the car root
  and the phone root were the same list, the branch would be dead code with 100% branch coverage
  over it. Task 2 makes the three surfaces produce **three lists of different lengths and different
  contents**, and asserts each exactly, so a `when` that fell through to one arm fails three tests.
- **A resume-from-car test that asserts a request was made.** `setMediaItem` having returned,
  `onSetMediaItems` having been invoked, `ResumeTarget.startPositionMs` being right — every one of
  those is satisfied by a player that ignores the answer, a URL that 404s into a swallowed error,
  and a decoder that never produced a sample. Task 5 asserts the **position playback actually
  reached, and then advanced from**, on a book whose stored position is deliberately **off every
  chapter boundary** so that "resumed exactly" and "resumed at a chapter start" are
  distinguishable.

### Scope discipline

Plan 5 is **the car and the watch**. Explicitly **not** in this plan:

- **Casting** — Plan 6, being written concurrently. No `:core:cast`, no proxy, no SSDP, no
  `PlaybackOutputSwitch`. See seam fact 3 above for the one property this plan maintains so the two
  compose later.
- **Bindery and Lidarr** — Plan 7, being written concurrently.
- **Core playback, the queue, the notification, gapless, the media cache** — **Plan 3's**.
- **Per-book resume, chapters, per-item speed, silence skipping, the sleep timer, smart rewind,
  `onPlaybackResumption`** — **Plan 4's**. This plan consumes every one of them and implements none.
- **Library browsing, search and library-scoped shuffle as *features*** — **Plan 2's**. This plan
  calls `BrowseRepository` and `ShuffleRepository`; it does not add a second search or a second
  shuffle, and the shuffle node in the tree is a *node*, not a shuffle implementation.
- **A car UI.** Spec §7: *"Android Auto needs no UI work."* No `androidx.car.app`, no Compose in a
  car, no `CarAppService`.
- **Wear tiles, complications and watch faces.** Spec §7 names Compose for Wear OS Material3 and
  the Horologist media modules and stops there. A tile is a separate surface with its own
  lifecycle, its own testing story and its own Play requirements; adding one here would be scope
  the spec does not ask for.
- **Offline downloads to the watch.** Spec §9 defers downloads outright, and names the specific
  reason (`DownloadService` hardcodes FGS type `dataSync` and hits Android 15's 6 h cap; the escape
  is raw `JobScheduler` with `RUN_USER_INITIATED_JOBS`). A watch that streams over Wi-Fi is what
  this plan ships; a watch that downloads is the deferred feature, on the watch.
- **Server-side progress sync.** Spec §4 and §11 rule it out and Plan 4 Task 6 made it checkable.
  Task 10's phone↔watch replication is **device-to-device over the user's own paired devices** and
  sends nothing to Navidrome; Task 10 asserts that with the same positive-control technique Plan 4
  used, and Task 11 corrects the spec's wording so "local only" cannot be read as "one device only".

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | **modify** — include `:core:watchlink`, `:wear` |
| `gradle/libs.versions.toml` | **modify** — Wear Compose, Horologist's two modules, `play-services-wearable`, `androidx-wear-tooling-preview` |
| `build.gradle.kts` | **modify** — coverage floors for `:wear` and `:core:watchlink`, new floors in `:core:model`, `:core:database`, `:core:media` |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt` | **new** — the node id, and the round trip |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseNode.kt` | **new** — one row of a browse tree, Android-free |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt` | **new** — `BrowseSurface` and `BrowseSurfaces.of` |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt` | **new** — pure: data → ordered nodes, branching on surface |
| `core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt` | **new** — resolve a `BrowseId` to data; expand a playable one to songs |
| `core/media/src/main/kotlin/app/muplay/media/browse/BrowseItems.kt` | **new** — `BrowseNode` → `MediaItem`, with the extras a car reads |
| `core/media/src/main/kotlin/app/muplay/media/browse/BrowseExtras.kt` | **new** — the `MediaConstants` strings, asserted rather than trusted |
| `core/media/src/main/kotlin/app/muplay/media/browse/SurfaceResolver.kt` | **new** — `ControllerInfo` → `BrowseSurface`, injectable |
| `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt` | **new** — the whole `MediaLibrarySession.Callback` surface |
| `core/media/src/main/kotlin/app/muplay/media/browse/PlayFromSearch.kt` | **new** — pure: a spoken query → a search plan |
| `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` | **modify** — inject and install `MuPlayLibraryCallback`; the `MEDIA_PLAY_FROM_SEARCH` intent |
| `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` | **modify** — provide the callback, the resolver and the tree repository |
| `core/media/src/main/AndroidManifest.xml` | **modify** — the `MEDIA_PLAY_FROM_SEARCH` intent filter on the service |
| `core/watchlink/build.gradle.kts` | **new** |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchLink.kt` | **new** — the one-method transport interface |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncPayload.kt` | **new** — what crosses the wrist, and its wire form |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/ProgressMerge.kt` | **new** — pure: two devices' progress → one |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncEngine.kt` | **new** — when to send, what to do with what arrives |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/DataLayerWatchLink.kt` | **new** — the ~60 lines no emulator can run |
| `core/watchlink/src/main/kotlin/app/muplay/watchlink/di/WatchLinkModule.kt` | **new** |
| `core/testing/src/main/kotlin/app/muplay/testing/InMemoryWatchLink.kt` | **new** — the fake both tiers use |
| `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` | **modify** — the `muplayApplication { androidAuto }` extension and the automotive gates |
| `build-logic/convention/src/main/kotlin/MuPlayApplicationExtension.kt` | **new** — the extension type |
| `build-logic/convention/src/main/kotlin/VerifyAutomotiveDescriptorTask.kt` | **new** — the descriptor's *content*, not just its reference |
| `build-logic/convention/src/main/kotlin/AndroidWearConventionPlugin.kt` | **new** — `muplay.android.wear` |
| `app/build.gradle.kts` | **modify** — `muplayApplication { androidAuto = true }` |
| `app/src/main/AndroidManifest.xml` | **modify** — the `com.google.android.gms.car.application` meta-data |
| `app/src/main/res/xml/automotive_app_desc.xml` | **new** — `<uses name="media"/>` |
| `app/src/test/kotlin/app/muplay/ConventionTest.kt` | **modify** — the Auto declaration rule, the Wear AVD drift rule |
| `app/src/androidTest/kotlin/app/muplay/BrowseTreeJourneyTest.kt` | **new** — Tier 2 (phone image): a real `MediaBrowser` over the real tree |
| `app/src/androidTest/kotlin/app/muplay/CarResumeJourneyTest.kt` | **new** — Tier 2 (phone image): play a book from the tree and land on the stored second |
| `wear/build.gradle.kts` | **new** |
| `wear/src/main/AndroidManifest.xml` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/MuPlayWearApplication.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/WearActivity.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/WearApp.kt` | **new** — `SwipeDismissableNavHost` and the two screens |
| `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseUiState.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseViewModel.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseScreen.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerUiState.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerViewModel.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerScreen.kt` | **new** |
| `wear/src/main/kotlin/app/muplay/wear/audio/WatchAudioOutput.kt` | **new** — the whole Horologist surface, in one file |
| `wear/src/main/kotlin/app/muplay/wear/di/WearModule.kt` | **new** |
| `wear/src/androidTest/kotlin/app/muplay/wear/WearPlaybackJourneyTest.kt` | **new** — Tier 2 (wear image) |
| `ci/prepare-wear-emulator.sh` | **new** — the watch's own preflight, and the check that it *is* a watch |
| `.github/workflows/e2e.yml` | **modify** — a second emulator step in the same job, and the new suites |
| `.github/workflows/pr.yml` | **modify** — the automotive gate in the Static job |
| `ci/mutation-probes.sh` | **modify** — this plan's probes, plus its `run_suite`/`revert` lists |
| `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` | **modify** — the §7, §10 and §12 corrections Task 11 lists |

---

## Task 1: `BrowseId` — the node id, and the round trip a real Navidrome id must survive

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowseIdTest.kt`
- Modify: `build.gradle.kts` (a `:core:model` floor for the new class)

**Interfaces:**
- Consumes: nothing. This is pure Kotlin over `String` and `Int`, in a module spec §9 defines as
  *"pure Kotlin, no Android"*, and that is the point — everything downstream of it needs a device.
- Produces:
  - `sealed interface BrowseId` with `fun encode(): String` and the members
    `Root`, `Continue`, `Books`, `Albums`, `Artists`, `Libraries` (all `data object`),
    `Library(libraryId: Int)`, `Shuffle(libraryId: Int)`, `Book(bookId: String)`,
    `Album(albumId: String)`, `Artist(artistId: String)`, `Track(songId: String)`
  - `BrowseId.Companion.decode(encoded: String): BrowseId?`
  - the wire constants `PREFIX = "muplay/"`, `SEPARATOR = "/"`, and one `KIND_*` per kind

### Why a media id needs a type, and why the wire form is a contract

Every node a car, a watch or the Assistant ever sees is a `MediaItem` whose only stable handle is
`mediaId: String`. That string travels three ways this project does not control:

1. **Back to us, later, out of context.** `onGetChildren(parentId)`, `onGetItem(mediaId)` and
   `onAddMediaItems` all hand back a bare string with no memory of how it was produced.
2. **Into Android Auto's own storage.** Auto keeps recently-browsed nodes and offers them again on
   the next drive, days later, after the app was reinstalled. A media id whose meaning depends on
   in-memory state is a media id that resolves to the wrong thing on the second drive.
3. **Into the system's media resumption row**, which persists mediaIds across reboots.

So `mediaId` is a serialisation format, and it gets a type and a test rather than string
concatenation at eleven call sites. **The tests below assert the exact encoded strings, not only
that encode/decode round-trip** — a round-trip test passes if `encode` returns `toString()` and
`decode` parses that, which would silently change the wire format the day someone adds a field to
one of these `data class`es.

### The one trap in the scheme, and why the leaf id is bare

A playable track's id is the **bare Navidrome song id**, with no prefix, while every other node
carries `muplay/`. That asymmetry is deliberate and it buys a real feature.

Android Auto marks the currently-playing row in a browse list by comparing the row's `mediaId`
against the session's current `MediaItem.mediaId`. Plan 3's `MediaItems.of(song, …)` sets that to
`song.id`. If a track row in the tree were `muplay/track/<songId>`, the two strings would never
match and **the car would never highlight the track it is playing** — a defect that no test of ours
would notice, because both halves would be individually correct.

The cost is one ambiguity: a server id that itself began with `muplay/` would decode as a browse
node. `Track`'s constructor `require`s that it does not. That check fires as an exception inside
`onGetChildren`, which Task 4 turns into a `LibraryResult.ofError` — **loud and visible**, which is
the whole point. Navidrome ids are hex, but "Navidrome ids are hex" is an assumption, and spec §4
is a catalogue of what happens when this project trusts one silently.

### Library ids are `Int`, and that is spec §4 speaking

`Library` and `Shuffle` carry an `Int`, and `decode` rejects any payload that is not the canonical
decimal form of one. Spec §4 measured what a malformed `musicFolderId` does against a live
`deluan/navidrome:0.63.2`: **a non-numeric or empty value returns `status: ok` with every library's
content**, while an unknown numeric one fails loudly with error 70. A browse id is a *user-supplied
string* by the time it comes back from a car head unit, so the parse is the only place the type can
be re-established. Getting this wrong means "shuffle my music" playing chapter 14 of a novel, with
nothing reported anywhere — the exact failure the whole project exists to prevent.

- [ ] **Step 1: Write the failing test**

`core/model/src/test/kotlin/app/muplay/model/browse/BrowseIdTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The wire format of every browse node id.
 *
 * Two separate questions, asked separately on purpose:
 *
 * - **What exactly does each id encode to?** Asserted against literal strings, because Android Auto
 *   persists these across reinstalls and reboots — the format is a contract with a piece of
 *   software this project does not own, and a round-trip test cannot see a format change at all.
 * - **What does a hostile payload do?** Asserted with ids containing the separator, a colon,
 *   spaces, unicode and the scheme's own prefix, because by the time an id comes back from a head
 *   unit it is untrusted input.
 */
class BrowseIdTest {

  @Test
  fun `every id encodes to its exact documented string`() {
    // The whole table in one assertion, mapped to the field under test and compared as an exact
    // ordered list. A per-id loop of `assertThat(id.encode()).isNotEmpty()` would pass against a
    // single constant; this cannot.
    val ids: List<BrowseId> = listOf(
      BrowseId.Root,
      BrowseId.Continue,
      BrowseId.Books,
      BrowseId.Albums,
      BrowseId.Artists,
      BrowseId.Libraries,
      BrowseId.Library(2),
      BrowseId.Shuffle(1),
      BrowseId.Book("al-7c3f"),
      BrowseId.Album("al-9911"),
      BrowseId.Artist("ar-0042"),
      BrowseId.Track("tr-abcdef"),
    )

    assertThat(ids.map(BrowseId::encode)).containsExactly(
      "muplay/root",
      "muplay/continue",
      "muplay/books",
      "muplay/albums",
      "muplay/artists",
      "muplay/libraries",
      "muplay/library/2",
      "muplay/shuffle/1",
      "muplay/book/al-7c3f",
      "muplay/album/al-9911",
      "muplay/artist/ar-0042",
      // Bare, with no prefix at all -- see BrowseId's own documentation. This is the one line in
      // this file whose *absence of* a prefix is the assertion.
      "tr-abcdef",
    )
  }

  @Test
  fun `every documented string decodes to its exact id`() {
    val encoded = listOf(
      "muplay/root",
      "muplay/continue",
      "muplay/books",
      "muplay/albums",
      "muplay/artists",
      "muplay/libraries",
      "muplay/library/2",
      "muplay/shuffle/1",
      "muplay/book/al-7c3f",
      "muplay/album/al-9911",
      "muplay/artist/ar-0042",
      "tr-abcdef",
    )

    assertThat(encoded.map(BrowseId::decode)).containsExactly(
      BrowseId.Root,
      BrowseId.Continue,
      BrowseId.Books,
      BrowseId.Albums,
      BrowseId.Artists,
      BrowseId.Libraries,
      BrowseId.Library(2),
      BrowseId.Shuffle(1),
      BrowseId.Book("al-7c3f"),
      BrowseId.Album("al-9911"),
      BrowseId.Artist("ar-0042"),
      BrowseId.Track("tr-abcdef"),
    )
  }

  @Test
  fun `the payload survives every character a server id could contain`() {
    // Each of these has broken a hand-rolled `split(":")` somewhere. The separator one is the
    // important case: `split` without a limit turns "a/b" into two fragments and silently drops
    // the second.
    val payloads = listOf(
      "a/b/c",
      "with:colon",
      "with space",
      "Hörbücher",
      "muplay",            // the prefix's first component, but not the prefix
      "%2F",
      "-",
    )

    assertThat(payloads.map { BrowseId.decode(BrowseId.Book(it).encode()) })
      .containsExactly(*payloads.map { BrowseId.Book(it) }.toTypedArray())
  }

  @Test
  fun `a bare id decodes to a track, including one containing a slash`() {
    assertThat(listOf(BrowseId.decode("tr-1"), BrowseId.decode("tr/1"), BrowseId.decode("muplayer")))
      .containsExactly(BrowseId.Track("tr-1"), BrowseId.Track("tr/1"), BrowseId.Track("muplayer"))
  }

  @Test
  fun `a library id that is not canonically numeric is rejected rather than widened`() {
    // Spec section 4: a non-numeric musicFolderId returns `status: ok` and EVERY library's
    // content. There is no runtime signal when it is wrong, so this parse is the only place the
    // type can be re-established, and it has to be strict rather than lenient.
    val hostile = listOf(
      "muplay/library/abc",
      "muplay/library/",
      "muplay/library/1abc",
      "muplay/library/ 1",
      "muplay/library/+1",
      "muplay/library/01",
      "muplay/shuffle/abc",
      "muplay/shuffle/",
    )

    assertThat(hostile.map(BrowseId::decode)).containsExactly(
      null, null, null, null, null, null, null, null,
    )
  }

  @Test
  fun `a malformed browse id decodes to null rather than to something plausible`() {
    val malformed = listOf(
      "",                       // Media3's default mediaId. Must never be a valid node.
      "muplay/",
      "muplay/nosuchkind",
      "muplay/nosuchkind/1",
      "muplay/root/1",          // a kind that takes no payload, given one
      "muplay/continue/x",
      "muplay/book",            // a kind that requires a payload, given none
      "muplay/book/",
      "muplay/album/",
      "muplay/artist/",
    )

    assertThat(malformed.map(BrowseId::decode))
      .containsExactly(null, null, null, null, null, null, null, null, null, null)
  }

  @Test
  fun `a track id that would collide with the scheme is refused loudly`() {
    // The one ambiguity the bare-leaf decision buys, and it fails at construction rather than
    // resolving to the wrong node three screens later.
    assertThatThrownBy { BrowseId.Track("muplay/books") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("muplay/")

    assertThatThrownBy { BrowseId.Track("") }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `the payload is what distinguishes two ids of the same kind`() {
    // Rule 2: a value observed at exactly one value is not tested. Two books, two encodings, two
    // decodings, and the two are asserted to differ -- so an `encode` that ignored its payload
    // fails here even though every single-value assertion above would still pass.
    val first = BrowseId.Book("al-1")
    val second = BrowseId.Book("al-2")

    assertThat(listOf(first.encode(), second.encode()))
      .containsExactly("muplay/book/al-1", "muplay/book/al-2")
    assertThat(first.encode()).isNotEqualTo(second.encode())
    assertThat(BrowseId.decode(first.encode())).isNotEqualTo(BrowseId.decode(second.encode()))
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:model:test --tests '*BrowseIdTest*'`
Expected: FAIL — `Unresolved reference: BrowseId`.

- [ ] **Step 3: Implement `BrowseId`**

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt`:

```kotlin
package app.muplay.model.browse

/**
 * The identity of one node in the browse tree Android Auto, Wear OS and the Assistant read.
 *
 * A `MediaItem`'s `mediaId` is the only handle any of those surfaces keeps, and it comes back to
 * this app out of context: `onGetChildren(parentId)`, `onGetItem(mediaId)` and `onAddMediaItems`
 * are all handed a bare string. Android Auto additionally *persists* browse ids and offers them
 * again days later, after a reinstall, so the encoded form below is a wire contract rather than an
 * implementation detail — `BrowseIdTest` asserts the literal strings for that reason, and changing
 * one is a deliberate act with a migration cost, not a refactor.
 *
 * **A playable track encodes to the bare server id, with no [PREFIX].** Android Auto marks the
 * currently-playing row by comparing its `mediaId` with the session's current item, and Plan 3's
 * `MediaItems.of` sets that to `Song.id`; a prefixed track id would silently disable that
 * highlight while leaving both halves individually correct. The cost is that a server id starting
 * with `muplay/` would be ambiguous, which [Track] refuses at construction rather than resolving
 * wrongly later.
 */
sealed interface BrowseId {

  /** The stable string that goes on the wire as a `MediaItem`'s `mediaId`. */
  fun encode(): String

  /** The tree's root. Returned by `onGetLibraryRoot`; never has children of its own kind. */
  data object Root : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ROOT"
  }

  /** Books with a stored position, most recently heard first. The car's first tab. */
  data object Continue : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_CONTINUE"
  }

  /** Every book in every library the user tagged Audiobooks. */
  data object Books : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_BOOKS"
  }

  /** Albums across every library the user tagged Music. */
  data object Albums : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ALBUMS"
  }

  /** Artists across every library the user tagged Music. */
  data object Artists : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ARTISTS"
  }

  /** The library picker. Phone surface only: an unbounded list is what a driver must not get. */
  data object Libraries : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_LIBRARIES"
  }

  /** One Navidrome library, by its numeric id. See this file's note on why the type is `Int`. */
  data class Library(val libraryId: Int) : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_LIBRARY$SEPARATOR$libraryId"
  }

  /** Playable. Library-scoped shuffle, the headline feature, as one tap in a car. */
  data class Shuffle(val libraryId: Int) : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_SHUFFLE$SEPARATOR$libraryId"
  }

  /** Playable **and** browsable: playing it resumes, opening it lists its files. */
  data class Book(val bookId: String) : BrowseId {
    init { require(bookId.isNotEmpty()) { "a book id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_BOOK$SEPARATOR$bookId"
  }

  /** Playable and browsable: playing it plays the album, opening it lists its tracks. */
  data class Album(val albumId: String) : BrowseId {
    init { require(albumId.isNotEmpty()) { "an album id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_ALBUM$SEPARATOR$albumId"
  }

  /** Browsable. Its children are that artist's albums. */
  data class Artist(val artistId: String) : BrowseId {
    init { require(artistId.isNotEmpty()) { "an artist id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_ARTIST$SEPARATOR$artistId"
  }

  /**
   * Playable leaf. Encodes to the **bare** server id so that it equals the `mediaId` Plan 3's
   * `MediaItems.of` puts on the playing item — see this file's own documentation.
   */
  data class Track(val songId: String) : BrowseId {
    init {
      require(songId.isNotEmpty()) { "a track id may not be empty" }
      require(!songId.startsWith(PREFIX)) {
        "a server id starting with '$PREFIX' is indistinguishable from a browse node id; refusing " +
          "'$songId' here rather than resolving it to the wrong node later"
      }
    }

    override fun encode(): String = songId
  }

  companion object {
    /** Everything except a [Track] carries this. */
    const val PREFIX: String = "muplay/"

    /** Between the kind and its payload. A payload may itself contain this. */
    const val SEPARATOR: String = "/"

    const val KIND_ROOT: String = "root"
    const val KIND_CONTINUE: String = "continue"
    const val KIND_BOOKS: String = "books"
    const val KIND_ALBUMS: String = "albums"
    const val KIND_ARTISTS: String = "artists"
    const val KIND_LIBRARIES: String = "libraries"
    const val KIND_LIBRARY: String = "library"
    const val KIND_SHUFFLE: String = "shuffle"
    const val KIND_BOOK: String = "book"
    const val KIND_ALBUM: String = "album"
    const val KIND_ARTIST: String = "artist"

    /**
     * Parses [encoded] back into a node id, or `null` if it names no node this app serves.
     *
     * `null`, never a default and never an exception: [encoded] arrives from a car head unit, a
     * watch, the Assistant or the system's media-resumption store, and the correct answer to an id
     * this build does not recognise is `LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)` —
     * which Task 4 produces from this `null`. Returning [Root] for an unparseable id would send a
     * driver to the top of the tree with no explanation.
     */
    fun decode(encoded: String): BrowseId? {
      if (encoded.isEmpty()) return null
      // No prefix means a bare server id, i.e. a track. This is checked before anything else so
      // that a song id containing a slash is never mistaken for a kind.
      if (!encoded.startsWith(PREFIX)) return Track(encoded)

      val body = encoded.removePrefix(PREFIX)
      val kind = body.substringBefore(SEPARATOR)
      // `body.length > kind.length` distinguishes "muplay/root" from "muplay/root/", which are
      // different strings and must not be the same node -- a lenient parse here is how a trailing
      // slash in someone's persisted recents turns into a wrong answer.
      val hasPayload = body.length > kind.length
      val payload = if (hasPayload) body.substring(kind.length + SEPARATOR.length) else ""

      return when (kind) {
        KIND_ROOT -> Root.takeUnless { hasPayload }
        KIND_CONTINUE -> Continue.takeUnless { hasPayload }
        KIND_BOOKS -> Books.takeUnless { hasPayload }
        KIND_ALBUMS -> Albums.takeUnless { hasPayload }
        KIND_ARTISTS -> Artists.takeUnless { hasPayload }
        KIND_LIBRARIES -> Libraries.takeUnless { hasPayload }
        KIND_LIBRARY -> canonicalInt(payload)?.let(::Library)
        KIND_SHUFFLE -> canonicalInt(payload)?.let(::Shuffle)
        KIND_BOOK -> payload.takeIf { it.isNotEmpty() }?.let(::Book)
        KIND_ALBUM -> payload.takeIf { it.isNotEmpty() }?.let(::Album)
        KIND_ARTIST -> payload.takeIf { it.isNotEmpty() }?.let(::Artist)
        else -> null
      }
    }

    /**
     * [payload] as an `Int`, but only in its canonical decimal form.
     *
     * `toIntOrNull` alone accepts `"+1"` and `"01"`, neither of which this encoder ever produces,
     * so accepting them would make `decode(encode(x))` an identity while `encode(decode(s))` was
     * not — and, worse, would let two different strings name the same node in Auto's persisted
     * recents. Spec section 4 is the reason strictness is the right default here: a library id
     * that is *not* a number is silently ignored by Navidrome and widens the scope to every
     * library, with no runtime signal at all.
     */
    private fun canonicalInt(payload: String): Int? =
      payload.toIntOrNull()?.takeIf { it.toString() == payload }
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:model:test --tests '*BrowseIdTest*'`
Expected: PASS, 8/8.

- [ ] **Step 5: Prove each assertion can fail**

Each mutation below must fail **the named test and no other**, which is what makes them
independent questions rather than one question asked eight times.

1. In `Book.encode()`, drop `$SEPARATOR$bookId` so it returns `"$PREFIX$KIND_BOOK"`. Expect
   `every id encodes to its exact documented string` and `the payload is what distinguishes two ids
   of the same kind` to fail.
2. In `Track.encode()`, return `"$PREFIX$KIND_BOOK$SEPARATOR$songId"`. Expect `every id encodes to
   its exact documented string` to fail on the last element — the bare-leaf decision, asserted.
3. In `decode`, replace `canonicalInt(payload)` with `payload.toIntOrNull()`. Expect `a library id
   that is not canonically numeric is rejected rather than widened` to fail on `"+1"` and `"01"`
   and nothing else to move. **This is the spec §4 assertion; watch it fail.**
4. In `decode`, change `hasPayload` to `payload.isNotEmpty()` — which reads identically and is not.
   Expect `a malformed browse id decodes to null rather than to something plausible` to fail on
   `"muplay/root/"`.
5. In `decode`, use `body.split(SEPARATOR)` and take index 1 as the payload. Expect `the payload
   survives every character a server id could contain` to fail on `"a/b/c"`.
6. Delete `Track`'s `require(!songId.startsWith(PREFIX))`. Expect `a track id that would collide
   with the scheme is refused loudly` to fail.

- [ ] **Step 6: Add the probes and a coverage floor, then commit**

`ci/mutation-probes.sh` — add mutations 1, 3 and 5 above, and make sure
`core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt` is in `revert()`'s explicit file
list and `:core:model:test` is in `run_suite()`. **Read that script's header first**: a probe whose
file is missing from `revert()` mutates the tree and never puts it back.

`build.gradle.kts` — `BrowseId` is a real branching class in a module whose existing rule is a
`"CLASS"`-element list. Add `"app.muplay.model.browse.BrowseId"` and `"app.muplay.model.browse.BrowseId*"`
to the existing `":core:model"` BRANCH rule's `includes` rather than adding a second rule; the
nested `data object`/`data class` members carry no branches of their own, so they cannot move the
ratio, and including them keeps `warnUngatedClasses` quiet. **Measure the real number first**
(`./gradlew :core:model:jacocoTestReport`) and only then decide whether the existing `0.90` still
holds — never adjust a floor to whatever was measured without reading why it moved.

```bash
./gradlew :core:model:test :core:model:jacocoJvmCoverageVerification
git add core/model gradle build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(model): BrowseId, the wire format for every browse node"
```

---

## Task 2: `BrowseTree` — one tree, three surfaces, and order as a property

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseNode.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseText.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowseTreeTest.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowseTextTest.kt`
- Modify: `build.gradle.kts` (`:core:model` floors)

**Interfaces:**
- Consumes:
  - `BrowseId` and every member — Task 1.
  - `app.muplay.model.Album(id, libraryId, name, artistId, artistName, coverArtId, songCount, durationSeconds)`,
    `Artist(id, libraryId, name, coverArtId, albumCount)`,
    `Song(id, libraryId, title, albumId, albumName, artistId, artistName, trackNumber, discNumber, durationSeconds, suffix, coverArtId)`,
    `MusicLibrary(id, name, role)`, `LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }` — `:core:model`,
    **committed**. Read the files; do not restate them.
  - **`app.muplay.model.BookSummary(bookId, libraryId, title, author, coverArtId, fileCount,
    durationMs, positionMs, isFinished, lastPlayedAtEpochMs)` with `remainingMs`,
    `progressFraction`, `hasStarted`** — **Plan 4 Task 4**, whose File Structure puts it in
    `core/model/src/main/kotlin/app/muplay/model/BookSummary.kt`. If it landed with different
    property names, use the real ones and record it in the task report; do **not** add a second
    summary type.
- Produces:
  - `enum class BrowseMediaType { MIXED, ALBUM, ARTIST, TRACK, AUDIO_BOOK, AUDIO_BOOK_CHAPTER, FOLDER_ALBUMS, FOLDER_ARTISTS, FOLDER_MIXED }`
  - `enum class BrowseStyle { LIST, GRID }`
  - `enum class BrowseCompletionStatus { NOT_PLAYED, PARTIALLY_PLAYED, FULLY_PLAYED }`
  - `data class BrowseCompletion(val status: BrowseCompletionStatus, val fraction: Double)`
  - `data class BrowseNode(id, title, subtitle, isBrowsable, isPlayable, mediaType, artworkId, childStyle, completion, durationMs)`
  - `enum class BrowseSurface { CAR, WATCH, PHONE }` with `val continueLimit: Int` and
    `val browsableStyle: BrowseStyle`, and `companion object { const val MAX_CAR_ROOT_TABS = 4 }`
  - `object BrowseText` with `fun remainingLabel(remainingMs: Long): String`,
    `fun albumCountLabel(count: Int): String`, `fun partLabel(index: Int, total: Int): String`,
    `const val UNKNOWN_ARTIST: String`
  - `object BrowseTree` with `root(surface, hasAudiobooks, hasMusic)`, `continueNodes(books, limit)`,
    `bookNodes(books)`, `bookChildren(files)`, `albumsNodes(musicLibraries, albums)`,
    `artistNodes(artists)`, `artistChildren(albums)`, `albumChildren(songs)`, `songNodes(songs)`,
    `libraryNodes(libraries)`, `libraryChildren(library, albums)`, and the title constants

### Why the tree is a pure function in `:core:model`, and not a `MediaLibrarySession.Callback`

Because the callback cannot be tested. `androidx.media3.common.MediaItem` reaches `android.net.Uri`
and `android.os.Bundle`, both of which are unimplemented stubs in the JVM's `android.jar` and throw
*"not mocked"*; the escape is Robolectric, which spec §2 and §10 ban outright. Plan 3 Task 4 hit
exactly this and moved its field-by-field `MediaItem` mapping onto a device.

So the tree is split at the last point where it is still ordinary Kotlin. Everything about *which
nodes, in which order, with which titles* is decided here, in a module spec §9 defines as *"pure
Kotlin, no Android"*, where a JVM test can vary one argument at a time and assert an exact list.
Task 4's device test then has one much smaller question left: does a `BrowseNode` become the
`MediaItem` it claims to.

### The three surfaces produce three different roots, and that is the whole point of the branch

The defect this plan was warned about by name is *"an `isAutomotiveController` test where both
branches return the same tree, so the branch is untested"*. A `when (surface)` whose arms are equal
has 100% branch coverage and asserts nothing. So the roots differ in **length and in content**, for
reasons that are real rather than manufactured:

| Surface | Root children, in order | Why |
|---|---|---|
| `CAR` | Continue, Books, Albums, Artists | **Android Auto renders root children as tabs and shows at most four.** A fifth is dropped by the host, silently. `MAX_CAR_ROOT_TABS` names that limit and Step 1 asserts it for every configuration. |
| `WATCH` | Continue, Books, Albums | A watch list is read one row at a time on a 45 mm screen. Artists is a level of indirection that costs two more crown scrolls to reach the same album. |
| `PHONE` | Continue, Books, Albums, Artists, Libraries | The phone root is what the Assistant, the system's media resumption and any third-party browser see. There is no four-tab render and no driver-distraction limit, so it exposes the per-library scoping — the app's headline feature — that a car must not be handed as an unbounded picker. |

Two further surface-dependent properties, both on the enum rather than in a `when` inside the tree:

- **`continueLimit`** — 8 in a car, 5 on a watch, 25 on a phone.
- **`browsableStyle`** — `GRID` in a car and on a phone (cover art is the fastest thing to
  recognise), `LIST` on a watch (a two-column grid of 40 px covers is unreadable).

### What is deliberately *not* branched on the surface

**Nothing below the root.** An album's children are its tracks on every surface, in the same order,
with the same titles. Branching deeper would double the number of trees to reason about and would
give a driver and a passenger different answers to the same question. The root is where the four-tab
limit and the screen size actually bite; everything under it is the same library.

### The three orders, and why two of them must differ

`Continue` and `Books` are built from the **same** `List<BookSummary>` and must come out in
**different orders** — most-recently-heard-first for Continue, alphabetical for Books. That is
correct behaviour, and it is also the assertion that catches a `bookNodes` implementation that
forgot to sort at all: with one order, "sorted" and "as supplied" are the same list and neither test
can tell. Step 1 feeds both functions the same shuffled input and asserts two different exact lists.

The third order is **library id ascending** for the shuffle nodes, so that a driver who learns
"Shuffle Music is the first row under Albums" is right every time rather than most of the time.

### The one place the headline feature shows up in the tree

`libraryChildren` gives a **`MUSIC`** library a `Shuffle` node and gives an **`AUDIOBOOKS`** library
none. Spec §1: *"Hitting shuffle must not pull chapter 14 of a novel into a music session."* In a
car that rule has to be expressed as the absence of a button, because there is no UI to disable.
Step 1 asserts both halves — a shuffle node present for Music, absent for Audiobooks — because
asserting only the presence would pass against a tree that offered shuffle everywhere.

- [ ] **Step 1: Write the failing tests**

`core/model/src/test/kotlin/app/muplay/model/browse/BrowseTextTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The strings a driver reads at 70 km/h.
 *
 * Every band is asserted at both sides of its boundary. A single sample per band cannot tell a
 * correct `when` cascade from one whose comparisons are all `<=` by mistake, and "1 h 0 min left"
 * versus "1 h left" is exactly the kind of difference that survives review and then reads badly on
 * a real screen.
 */
class BrowseTextTest {

  @Test
  fun `the remaining label names one band per magnitude, at both sides of every boundary`() {
    val inputs = listOf(
      0L,
      59_999L,
      60_000L,
      119_999L,
      3_599_999L,
      3_600_000L,
      3_660_000L,
      7_200_000L,
      45_296_000L,
    )

    assertThat(inputs.map(BrowseText::remainingLabel)).containsExactly(
      "under a minute left",
      "under a minute left",
      "1 min left",
      "1 min left",
      "59 min left",
      "1 h left",
      "1 h 1 min left",
      "2 h left",
      "12 h 34 min left",
    )
  }

  @Test
  fun `a negative remaining time is treated as none rather than rendered`() {
    // `remainingMs` is `durationMs - positionMs`, and a position past the end is reachable: Media3
    // reports a position beyond a container's declared duration on a stream whose duration was
    // estimated. "-3 min left" on a car screen is worse than "under a minute left".
    assertThat(BrowseText.remainingLabel(-1L)).isEqualTo("under a minute left")
  }

  @Test
  fun `the album count label is singular at exactly one and plural elsewhere`() {
    assertThat(listOf(0, 1, 2, 17).map(BrowseText::albumCountLabel))
      .containsExactly("no albums", "1 album", "2 albums", "17 albums")
  }

  @Test
  fun `the part label is one-based and carries the total`() {
    assertThat(listOf(0 to 3, 1 to 3, 2 to 3).map { BrowseText.partLabel(it.first, it.second) })
      .containsExactly("Part 1 of 3", "Part 2 of 3", "Part 3 of 3")
  }
}
```

`core/model/src/test/kotlin/app/muplay/model/browse/BrowseTreeTest.kt`:

```kotlin
package app.muplay.model.browse

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The shape of the browse tree, one pure function at a time.
 *
 * Every assertion in this file maps a **field** and compares an **exact ordered list**. Two habits
 * this project has been bitten by six times are banned here outright: `isNotEmpty`, which is
 * satisfied by a tree that returns everything under everything, and `containsExactlyInAnyOrder`,
 * which asserts nothing about the one property a car list actually has — the order a driver reads
 * it in.
 */
class BrowseTreeTest {

  // --- roots -----------------------------------------------------------------------------------

  @Test
  fun `the three surfaces produce three different roots`() {
    // The single most important assertion in this plan. If these three lists were equal, the
    // surface branch would be dead code carrying 100% branch coverage.
    val car = BrowseTree.root(BrowseSurface.CAR, hasAudiobooks = true, hasMusic = true)
    val watch = BrowseTree.root(BrowseSurface.WATCH, hasAudiobooks = true, hasMusic = true)
    val phone = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = true)

    assertThat(car.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists",
    )
    assertThat(watch.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums",
    )
    assertThat(phone.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists", "muplay/libraries",
    )

    // Stated as its own assertion so a future edit that made two of them equal fails on a line
    // that says why, rather than on three separate list comparisons.
    assertThat(listOf(car.size, watch.size, phone.size)).containsExactly(4, 3, 5)
  }

  @Test
  fun `the car root never exceeds the four tabs Android Auto renders`() {
    // All four configurations, because the limit has to hold for the *largest* one and a single
    // sample cannot show that.
    val sizes = listOf(true to true, true to false, false to true, false to false)
      .map { (books, music) ->
        BrowseTree.root(BrowseSurface.CAR, hasAudiobooks = books, hasMusic = music).size
      }

    assertThat(sizes).containsExactly(4, 2, 2, 0)
    assertThat(sizes.max()).isLessThanOrEqualTo(BrowseSurface.MAX_CAR_ROOT_TABS)
  }

  @Test
  fun `each configuration flag removes exactly the tabs it owns`() {
    // Rule 2, applied to a boolean: hold the surface constant, vary one flag, assert both
    // observations. A root that ignored `hasAudiobooks` would pass the first test above.
    val noBooks = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = false, hasMusic = true)
    val noMusic = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = false)
    val nothing = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = false, hasMusic = false)

    assertThat(noBooks.map { it.id.encode() })
      .containsExactly("muplay/albums", "muplay/artists", "muplay/libraries")
    assertThat(noMusic.map { it.id.encode() })
      .containsExactly("muplay/continue", "muplay/books")
    assertThat(nothing).isEmpty()
  }

  @Test
  fun `the browsable style follows the surface`() {
    val albumsTab = { surface: BrowseSurface ->
      BrowseTree.root(surface, hasAudiobooks = true, hasMusic = true)
        .single { it.id == BrowseId.Albums }
        .childStyle
    }

    assertThat(listOf(BrowseSurface.CAR, BrowseSurface.WATCH, BrowseSurface.PHONE).map(albumsTab))
      .containsExactly(BrowseStyle.GRID, BrowseStyle.LIST, BrowseStyle.GRID)
  }

  @Test
  fun `every root tab is browsable and none is playable`() {
    // Mapped and compared as exact lists, not `allMatch`: `allMatch` over an empty list is
    // vacuously true, and an empty root is a reachable state (see the test above).
    val root = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = true)

    assertThat(root.map { it.isBrowsable }).containsExactly(true, true, true, true, true)
    assertThat(root.map { it.isPlayable }).containsExactly(false, false, false, false, false)
    assertThat(root.map { it.title })
      .containsExactly("Continue", "Books", "Albums", "Artists", "Libraries")
  }

  // --- books -----------------------------------------------------------------------------------

  @Test
  fun `continue lists only started unfinished books, most recently heard first`() {
    val nodes = BrowseTree.continueNodes(SHELF, limit = 10)

    assertThat(nodes.map { it.id.encode() }).containsExactly(
      "muplay/book/b-tail",   // lastPlayedAt 900
      "muplay/book/b-multi",  // lastPlayedAt 500
      "muplay/book/b-second", // lastPlayedAt 100
      // b-test is unstarted and b-done is finished; neither belongs on a Continue shelf.
    )
  }

  @Test
  fun `continue is capped by the surface's own limit`() {
    assertThat(BrowseTree.continueNodes(SHELF, limit = BrowseSurface.WATCH.continueLimit).size)
      .isEqualTo(3)
    assertThat(BrowseTree.continueNodes(SHELF, limit = 2).map { it.id.encode() })
      .containsExactly("muplay/book/b-tail", "muplay/book/b-multi")
    assertThat(BrowseTree.continueNodes(SHELF, limit = 0)).isEmpty()

    // The three surfaces' limits, asserted as values rather than trusted: a limit that was the
    // same everywhere would make the two assertions above indistinguishable.
    assertThat(BrowseSurface.entries.map { it.continueLimit }).containsExactly(8, 5, 25)
  }

  @Test
  fun `the book shelf is alphabetical, which is a different order from continue`() {
    // Same input, two functions, two different exact lists. This is what proves `bookNodes` sorts
    // at all: with a single order, "sorted" and "as supplied" are the same list.
    assertThat(BrowseTree.bookNodes(SHELF).map { it.title }).containsExactly(
      "A Wizard of Earthsea", // b-second
      "Multi Part Book",      // b-multi
      "Tail Book",            // b-tail
      "Test Book",            // b-test
      "Zero Hour",            // b-done
    )
    assertThat(BrowseTree.bookNodes(SHELF).map { it.title })
      .isNotEqualTo(BrowseTree.continueNodes(SHELF, limit = 25).map { it.title })
  }

  @Test
  fun `a book's completion is one of three distinct values`() {
    val byId = BrowseTree.bookNodes(SHELF).associateBy { it.id.encode() }

    assertThat(byId.getValue("muplay/book/b-test").completion)
      .isEqualTo(BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0))
    assertThat(byId.getValue("muplay/book/b-done").completion)
      .isEqualTo(BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0))

    val partial = byId.getValue("muplay/book/b-multi").completion
    assertThat(partial?.status).isEqualTo(BrowseCompletionStatus.PARTIALLY_PLAYED)
    // 3_000 of 15_000 ms. Asserted as a number, not as "greater than zero": a fraction hardcoded
    // to 0.5 would pass every "partially played" assertion there is.
    assertThat(partial?.fraction).isEqualTo(0.2, org.assertj.core.api.Assertions.within(1e-9))
  }

  @Test
  fun `a single-file book is playable but not browsable, and a multi-file one is both`() {
    val byId = BrowseTree.bookNodes(SHELF).associateBy { it.id.encode() }

    // Opening a one-file M4B would show a screen with one row that says what the row above it
    // already said. Both flags asserted for both books, so neither can be a constant.
    assertThat(byId.getValue("muplay/book/b-test").let { it.isPlayable to it.isBrowsable })
      .isEqualTo(true to false)
    assertThat(byId.getValue("muplay/book/b-multi").let { it.isPlayable to it.isBrowsable })
      .isEqualTo(true to true)
  }

  @Test
  fun `a book node carries every field of its summary`() {
    // The field-level rule. Two books that differ in every field, asserted field by field, so that
    // replacing any one assignment with a constant fails here.
    val nodes = BrowseTree.bookNodes(listOf(SECOND, MULTI))

    assertThat(nodes.map { it.title }).containsExactly("A Wizard of Earthsea", "Multi Part Book")
    assertThat(nodes.map { it.subtitle }).containsExactly(
      "Ursula K. Le Guin · 1 min left",
      "Terry Pratchett · 4 min left",
    )
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-second", "cov-multi")
    assertThat(nodes.map { it.durationMs }).containsExactly(21_000L, 15_000L)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.AUDIO_BOOK, BrowseMediaType.AUDIO_BOOK)
  }

  @Test
  fun `an unstarted book's subtitle is its author alone`() {
    assertThat(BrowseTree.bookNodes(listOf(TEST_BOOK)).single().subtitle)
      .isEqualTo("Anonymous")
  }

  @Test
  fun `a book with no author reads as unknown rather than as an empty line`() {
    assertThat(BrowseTree.bookNodes(listOf(TEST_BOOK.copy(author = "  "))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
  }

  @Test
  fun `a book's children are its files, numbered and in the order supplied`() {
    val files = listOf(song("f-1", "Chapter One"), song("f-2", "Chapter Two"), song("f-3", "Chapter Three"))

    val children = BrowseTree.bookChildren(files)

    assertThat(children.map { it.id.encode() }).containsExactly("f-1", "f-2", "f-3")
    assertThat(children.map { it.title }).containsExactly("Chapter One", "Chapter Two", "Chapter Three")
    assertThat(children.map { it.subtitle })
      .containsExactly("Part 1 of 3", "Part 2 of 3", "Part 3 of 3")
    assertThat(children.map { it.isPlayable }).containsExactly(true, true, true)
    assertThat(children.map { it.isBrowsable }).containsExactly(false, false, false)
    assertThat(children.map { it.mediaType }).containsExactly(
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
    )
  }

  // --- music -----------------------------------------------------------------------------------

  @Test
  fun `the albums node puts one shuffle per music library first, in library id order`() {
    val libraries = listOf(
      MusicLibrary(id = 3, name = "Vinyl rips", role = LibraryRole.MUSIC),
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC),
    )

    val nodes = BrowseTree.albumsNodes(libraries, listOf(ALBUM_A, ALBUM_B))

    assertThat(nodes.map { it.id.encode() }).containsExactly(
      "muplay/shuffle/1", "muplay/shuffle/3", "muplay/album/al-a", "muplay/album/al-b",
    )
    assertThat(nodes.map { it.title })
      .containsExactly("Shuffle Music", "Shuffle Vinyl rips", "Abbey Road", "Blue Train")
    assertThat(nodes.map { it.isPlayable }).containsExactly(true, true, true, true)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(false, false, true, true)
  }

  @Test
  fun `an audiobook library gets no shuffle node and a music library does`() {
    // Spec section 1: shuffle must never pull chapter 14 of a novel into a music session. In a car
    // that is expressed as the absence of a row, because there is no UI to disable.
    val music = MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC)
    val books = MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.AUDIOBOOKS)

    assertThat(BrowseTree.libraryChildren(music, listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/shuffle/1", "muplay/album/al-a")
    assertThat(BrowseTree.libraryChildren(books, listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/album/al-a")
  }

  @Test
  fun `an album node carries every field of its album`() {
    val nodes = BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A, ALBUM_B))

    assertThat(nodes.map { it.id.encode() }).containsExactly("muplay/album/al-a", "muplay/album/al-b")
    assertThat(nodes.map { it.title }).containsExactly("Abbey Road", "Blue Train")
    assertThat(nodes.map { it.subtitle }).containsExactly("The Beatles", "John Coltrane")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-a", "cov-b")
    assertThat(nodes.map { it.durationMs }).containsExactly(2_832_000L, 2_142_000L)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.ALBUM, BrowseMediaType.ALBUM)
  }

  @Test
  fun `an album with no artist reads as unknown`() {
    assertThat(BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A.copy(artistName = null))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
  }

  @Test
  fun `an artist node carries its own fields and counts its albums`() {
    val artists = listOf(
      Artist(id = "ar-1", libraryId = 1, name = "The Beatles", coverArtId = "cov-ar1", albumCount = 13),
      Artist(id = "ar-2", libraryId = 1, name = "Nobody", coverArtId = null, albumCount = 1),
    )

    val nodes = BrowseTree.artistNodes(artists)

    assertThat(nodes.map { it.id.encode() }).containsExactly("muplay/artist/ar-1", "muplay/artist/ar-2")
    assertThat(nodes.map { it.title }).containsExactly("The Beatles", "Nobody")
    assertThat(nodes.map { it.subtitle }).containsExactly("13 albums", "1 album")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-ar1", null)
    assertThat(nodes.map { it.isPlayable }).containsExactly(false, false)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(true, true)
  }

  @Test
  fun `a track node carries every field of its song`() {
    val songs = listOf(
      song("tr-1", "Come Together").copy(artistName = "The Beatles", coverArtId = "cov-a", durationSeconds = 259),
      song("tr-2", "Something").copy(artistName = "George", coverArtId = "cov-b", durationSeconds = 182),
    )

    val nodes = BrowseTree.songNodes(songs)

    assertThat(nodes.map { it.id.encode() }).containsExactly("tr-1", "tr-2")
    assertThat(nodes.map { it.title }).containsExactly("Come Together", "Something")
    assertThat(nodes.map { it.subtitle }).containsExactly("The Beatles", "George")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-a", "cov-b")
    assertThat(nodes.map { it.durationMs }).containsExactly(259_000L, 182_000L)
    assertThat(nodes.map { it.isPlayable }).containsExactly(true, true)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(false, false)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.TRACK, BrowseMediaType.TRACK)
  }

  @Test
  fun `song nodes keep the order they were given`() {
    // The mirror's order is disc-then-track (Plan 2). A tree that sorted by title here would put
    // an album's tracks in the wrong order and nothing else in this file would notice.
    val songs = listOf(song("tr-3", "Zulu"), song("tr-1", "Alpha"), song("tr-2", "Mike"))

    assertThat(BrowseTree.songNodes(songs).map { it.id.encode() })
      .containsExactly("tr-3", "tr-1", "tr-2")
  }

  @Test
  fun `a library node names its role in its subtitle`() {
    val libraries = listOf(
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC),
      MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.AUDIOBOOKS),
      MusicLibrary(id = 9, name = "New disk", role = LibraryRole.UNASSIGNED),
    )

    val nodes = BrowseTree.libraryNodes(libraries)

    assertThat(nodes.map { it.id.encode() })
      .containsExactly("muplay/library/1", "muplay/library/2", "muplay/library/9")
    assertThat(nodes.map { it.title }).containsExactly("Music", "Audiobooks", "New disk")
    assertThat(nodes.map { it.subtitle }).containsExactly("Music", "Audiobooks", "Not assigned")
  }

  @Test
  fun `library nodes are ordered by id, not by the order they arrived in`() {
    val libraries = listOf(
      MusicLibrary(id = 9, name = "New disk", role = LibraryRole.UNASSIGNED),
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC),
    )

    assertThat(BrowseTree.libraryNodes(libraries).map { it.id.encode() })
      .containsExactly("muplay/library/1", "muplay/library/9")
  }

  private companion object {

    fun song(id: String, title: String) = Song(
      id = id,
      libraryId = 1,
      title = title,
      albumId = "al-a",
      albumName = "Abbey Road",
      artistId = "ar-1",
      artistName = "The Beatles",
      trackNumber = 1,
      discNumber = 1,
      durationSeconds = 300,
      suffix = "mp3",
      coverArtId = "cov-a",
    )

    val ALBUM_A = Album(
      id = "al-a",
      libraryId = 1,
      name = "Abbey Road",
      artistId = "ar-1",
      artistName = "The Beatles",
      coverArtId = "cov-a",
      songCount = 17,
      durationSeconds = 2_832,
    )

    val ALBUM_B = Album(
      id = "al-b",
      libraryId = 1,
      name = "Blue Train",
      artistId = "ar-2",
      artistName = "John Coltrane",
      coverArtId = "cov-b",
      songCount = 5,
      durationSeconds = 2_142,
    )

    fun book(
      id: String,
      title: String,
      author: String,
      cover: String?,
      fileCount: Int,
      durationMs: Long,
      positionMs: Long,
      isFinished: Boolean,
      lastPlayedAtEpochMs: Long,
    ) = BookSummary(
      bookId = id,
      libraryId = 2,
      title = title,
      author = author,
      coverArtId = cover,
      fileCount = fileCount,
      durationMs = durationMs,
      positionMs = positionMs,
      isFinished = isFinished,
      lastPlayedAtEpochMs = lastPlayedAtEpochMs,
    )

    /** One file, never started. */
    val TEST_BOOK = book("b-test", "Test Book", "Anonymous", "cov-test", 1, 15_000, 0, false, 0)

    /** One file, started, 20_000 of 21_000 ms in -- 1 min left. */
    val SECOND = book("b-second", "A Wizard of Earthsea", "Ursula K. Le Guin", "cov-second", 1, 21_000, 20_000, false, 100)

    /** Three files, started at 3_000 of 15_000 ms -- exactly 0.2, and 4 min left. */
    val MULTI = book("b-multi", "Multi Part Book", "Terry Pratchett", "cov-multi", 3, 15_000, 3_000, false, 500)

    /** Two files, started, most recently heard. */
    val TAIL = book("b-tail", "Tail Book", "Anonymous", "cov-tail", 2, 10_000, 4_000, false, 900)

    /** Finished. Belongs on the shelf, never on Continue. */
    val DONE = book("b-done", "Zero Hour", "Anonymous", null, 1, 5_000, 5_000, true, 1_000)

    /**
     * Deliberately **not** in either the alphabetical or the recency order, so that a function that
     * returned its input unchanged fails both order assertions rather than accidentally passing one.
     */
    val SHELF = listOf(MULTI, DONE, TEST_BOOK, TAIL, SECOND)
  }
}
```

> Two things to reconcile against Plan 4 before running this file, and to record in the task report
> rather than to work around silently:
>
> 1. **`BookSummary`'s constructor.** The fixtures above pass ten named arguments in Plan 4 Task 4's
>    declared order. `remainingMs`, `progressFraction` and `hasStarted` are declared there as
>    *computed* properties, so they are not constructor arguments; if any of them landed as a stored
>    field, pass it and delete the corresponding derivation assumption in the fixture comments.
> 2. **`durationMs` versus 21 s.** `SECOND`'s numbers are chosen so `remainingMs` is exactly
>    60_000 — the boundary `BrowseTextTest` pins — which requires `remainingMs == durationMs -
>    positionMs` clamped at zero. Plan 4 Task 4's Interfaces block declares exactly that. If it
>    clamps differently, fix the fixture's numbers, not the assertion's expected string.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:model:test --tests '*BrowseTreeTest*' --tests '*BrowseTextTest*'`
Expected: FAIL — `Unresolved reference: BrowseTree`.

- [ ] **Step 3: Implement the node, the surface and the text**

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseNode.kt`:

```kotlin
package app.muplay.model.browse

/**
 * What kind of thing a node is, in this app's own vocabulary.
 *
 * Deliberately **not** `androidx.media3.common.MediaMetadata.MEDIA_TYPE_*`. Those are `Int`s on an
 * Android class, and referencing them here would drag `:core:model` — which spec section 9 defines
 * as *"pure Kotlin, no Android"* — onto an Android runtime, taking every test in this file with it.
 * `BrowseItems` in `:core:media` maps this enum to Media3's constants, on a device, in one place.
 */
enum class BrowseMediaType {
  MIXED,
  ALBUM,
  ARTIST,
  TRACK,
  AUDIO_BOOK,
  AUDIO_BOOK_CHAPTER,
  FOLDER_ALBUMS,
  FOLDER_ARTISTS,
  FOLDER_MIXED,
}

/** How a node's **children** should be laid out by whatever is rendering them. */
enum class BrowseStyle { LIST, GRID }

/** How far through an item the listener is, as a car head unit understands it. */
enum class BrowseCompletionStatus { NOT_PLAYED, PARTIALLY_PLAYED, FULLY_PLAYED }

/**
 * A book's progress, as two facts rather than one.
 *
 * Android Auto reads both: the status decides whether a progress bar is drawn at all, and
 * [fraction] decides how far along it is. They are separable — a book at 0.0 that has been started
 * is `PARTIALLY_PLAYED`, and drawing no bar for it would lose the only signal that it is in
 * progress.
 */
data class BrowseCompletion(
  val status: BrowseCompletionStatus,
  val fraction: Double,
)

/**
 * One row of the browse tree, with no Android type in it.
 *
 * [artworkId] is a **Navidrome `coverArt` id**, not a URL: turning it into a URL needs credentials
 * and a configured server, which live behind `SubsonicSourceProvider` in `:core:database`, and a
 * URL embedded here would be stale the moment the user's session changed. `BrowseItems` resolves it
 * at the last possible moment.
 *
 * [childStyle] describes this node's **children**, not itself, which is how Android Auto's content
 * style hints are defined — a browsable item carries the hint that applies inside it.
 */
data class BrowseNode(
  val id: BrowseId,
  val title: String,
  val subtitle: String? = null,
  val isBrowsable: Boolean,
  val isPlayable: Boolean,
  val mediaType: BrowseMediaType,
  val artworkId: String? = null,
  val childStyle: BrowseStyle = BrowseStyle.LIST,
  val completion: BrowseCompletion? = null,
  val durationMs: Long? = null,
)
```

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt`:

```kotlin
package app.muplay.model.browse

/**
 * Which of the three kinds of client is asking for the tree.
 *
 * The values are *kinds of screen*, not *kinds of device*: Android Auto (a phone projecting to a
 * head unit) and Android Automotive OS (the app running natively in the car) are both [CAR],
 * because what differs is the render and the distraction limit, not the runtime.
 */
enum class BrowseSurface(
  /** How many books the Continue shelf offers before it stops. */
  val continueLimit: Int,
  /** The layout hint this surface's browsable tabs carry for their children. */
  val browsableStyle: BrowseStyle,
) {
  /**
   * A car head unit. Four root tabs, a generous Continue shelf because a drive is long, and a grid
   * of cover art because recognising a cover is faster than reading a title at speed.
   */
  CAR(continueLimit = 8, browsableStyle = BrowseStyle.GRID),

  /**
   * A watch. Three root tabs and a short Continue shelf: every extra row is another crown scroll,
   * and a two-column grid of 40 px covers on a 45 mm screen is unreadable.
   */
  WATCH(continueLimit = 5, browsableStyle = BrowseStyle.LIST),

  /**
   * A phone, the system's media resumption, the Assistant, or any other browser. No four-tab
   * render and no distraction limit, so this is the surface that exposes the flat track list and
   * the per-library scoping.
   */
  PHONE(continueLimit = 25, browsableStyle = BrowseStyle.GRID),
  ;

  companion object {
    /**
     * Android Auto renders the root's children as tabs and shows at most this many. A fifth is
     * dropped by the host **silently**, which is why this is a named constant with a test on it
     * rather than a comment above a list.
     */
    const val MAX_CAR_ROOT_TABS: Int = 4
  }
}
```

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseText.kt`:

```kotlin
package app.muplay.model.browse

import kotlin.math.max

/**
 * The handful of strings the browse tree puts in front of a driver.
 *
 * English literals, not string resources, and that is a decision rather than an oversight:
 * `:core:model` is a pure-Kotlin module with no `Context` and no resource table, and moving these
 * into `:core:media` to reach one would move the *tree* there with them, which is what makes the
 * tree untestable (see Task 2's own header). Localisation, when it happens, belongs at the
 * `BrowseItems` boundary where a `Context` already exists — and it is a whole-app concern that no
 * plan has yet taken on, so no string in this app is localised today.
 */
object BrowseText {

  /** What a subtitle says when the server gave no artist or author at all. */
  const val UNKNOWN_ARTIST: String = "Unknown artist"

  private const val MINUTE_MS = 60_000L
  private const val HOUR_MS = 3_600_000L

  /**
   * "12 h 34 min left", "59 min left", "under a minute left".
   *
   * Clamped at zero: `remainingMs` is a subtraction of two independently-sourced numbers (a
   * container's declared duration and a player's reported position), and Media3 reports positions
   * past a declared duration on streams whose duration was estimated. "-3 min left" is a worse
   * answer than "under a minute left".
   */
  fun remainingLabel(remainingMs: Long): String {
    val clamped = max(0L, remainingMs)
    val hours = clamped / HOUR_MS
    val minutes = (clamped % HOUR_MS) / MINUTE_MS
    return when {
      clamped < MINUTE_MS -> "under a minute left"
      hours == 0L -> "$minutes min left"
      minutes == 0L -> "$hours h left"
      else -> "$hours h $minutes min left"
    }
  }

  /** "no albums", "1 album", "13 albums". */
  fun albumCountLabel(count: Int): String = when (count) {
    0 -> "no albums"
    1 -> "1 album"
    else -> "$count albums"
  }

  /** "Part 2 of 3", from a zero-based [index]. */
  fun partLabel(index: Int, total: Int): String = "Part ${index + 1} of $total"
}
```

- [ ] **Step 4: Implement the tree**

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt`:

```kotlin
package app.muplay.model.browse

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.Song

/**
 * The browse tree, as pure functions from data to an **ordered** list of nodes.
 *
 * Nothing here touches Android, a repository, a coroutine or a clock. `BrowseTreeRepository`
 * (`:core:database`) decides *which* data a given [BrowseId] needs and fetches it;
 * `MuPlayLibraryCallback` (`:core:media`) turns the result into `MediaItem`s and answers Media3.
 * This object decides only what the tree *is*, which is the half a JVM test can hold to account.
 *
 * **Order is the property under test everywhere in this file.** A browse list in a car is read
 * top-down at speed; "the right items in some order" is not the same answer as "the right items".
 */
object BrowseTree {

  const val CONTINUE_TITLE: String = "Continue"
  const val BOOKS_TITLE: String = "Books"
  const val ALBUMS_TITLE: String = "Albums"
  const val ARTISTS_TITLE: String = "Artists"
  const val LIBRARIES_TITLE: String = "Libraries"

  /**
   * The root's children.
   *
   * [hasAudiobooks] and [hasMusic] are what the user's library tagging produced, not a guess from a
   * library's name — spec section 4 is explicit that a Navidrome server never reports that
   * something is an audiobook, and that inferring the role from the name ("Hörbücher" is not
   * "Audiobooks") silently poisons shuffle scope.
   *
   * An empty result is a legitimate answer, on every surface: it means nothing has been configured
   * yet. Task 4 returns it as an empty child list rather than as an error, because a car that says
   * "no media available" is telling the truth, whereas an error makes the app look broken.
   */
  fun root(surface: BrowseSurface, hasAudiobooks: Boolean, hasMusic: Boolean): List<BrowseNode> =
    buildList {
      if (hasAudiobooks) {
        add(folder(BrowseId.Continue, CONTINUE_TITLE, BrowseMediaType.FOLDER_MIXED, surface.browsableStyle))
        add(folder(BrowseId.Books, BOOKS_TITLE, BrowseMediaType.FOLDER_MIXED, surface.browsableStyle))
      }
      if (hasMusic) {
        add(folder(BrowseId.Albums, ALBUMS_TITLE, BrowseMediaType.FOLDER_ALBUMS, surface.browsableStyle))
        // A watch skips Artists: it is a level of indirection that costs two more crown scrolls to
        // reach exactly the album the Albums tab already lists.
        if (surface != BrowseSurface.WATCH) {
          add(folder(BrowseId.Artists, ARTISTS_TITLE, BrowseMediaType.FOLDER_ARTISTS, BrowseStyle.LIST))
        }
        // A library picker is unbounded and is one more level of depth, which is exactly what a
        // driver must not be handed -- and it would push the car root past its four tabs.
        if (surface == BrowseSurface.PHONE) {
          add(folder(BrowseId.Libraries, LIBRARIES_TITLE, BrowseMediaType.FOLDER_MIXED, BrowseStyle.LIST))
        }
      }
    }

  /**
   * Books with somewhere to carry on from, most recently heard first, capped at [limit].
   *
   * Finished books are excluded rather than sorted to the bottom: "Continue" is a promise that
   * every row has more to play.
   */
  fun continueNodes(books: List<BookSummary>, limit: Int): List<BrowseNode> =
    books.asSequence()
      .filter { it.hasStarted && !it.isFinished }
      // `thenBy { bookId }` is not decoration: two books last played in the same millisecond is
      // reachable (a device-to-device merge writes a batch), and an unstable sort would reorder a
      // car list between two identical requests.
      .sortedWith(compareByDescending<BookSummary> { it.lastPlayedAtEpochMs }.thenBy { it.bookId })
      .take(limit)
      .map(::bookNode)
      .toList()

  /** Every book, alphabetically — a **different** order from [continueNodes], on purpose. */
  fun bookNodes(books: List<BookSummary>): List<BrowseNode> =
    books
      .sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, BookSummary::title).thenBy(BookSummary::bookId),
      )
      .map(::bookNode)

  /** One book's files, in the order given, numbered for a listener who cannot see a file name. */
  fun bookChildren(files: List<Song>): List<BrowseNode> =
    files.mapIndexed { index, song ->
      BrowseNode(
        id = BrowseId.Track(song.id),
        title = song.title,
        subtitle = BrowseText.partLabel(index, files.size),
        isBrowsable = false,
        isPlayable = true,
        mediaType = BrowseMediaType.AUDIO_BOOK_CHAPTER,
        artworkId = song.coverArtId,
        durationMs = song.durationSeconds * 1_000L,
      )
    }

  /**
   * The Albums tab: one shuffle per Music library first, in library id order, then the albums.
   *
   * Shuffle is first because it is the one thing a driver should be able to reach without reading,
   * and library id order because a driver who learns "the first row is Shuffle Music" must be right
   * every time rather than most of the time.
   */
  fun albumsNodes(musicLibraries: List<MusicLibrary>, albums: List<Album>): List<BrowseNode> =
    musicLibraries.sortedBy(MusicLibrary::id).map(::shuffleNode) + albums.map(::albumNode)

  /** Artists, in the order given (the mirror's, which Plan 2 defines as alphabetical). */
  fun artistNodes(artists: List<Artist>): List<BrowseNode> =
    artists.map { artist ->
      BrowseNode(
        id = BrowseId.Artist(artist.id),
        title = artist.name,
        subtitle = BrowseText.albumCountLabel(artist.albumCount),
        isBrowsable = true,
        isPlayable = false,
        mediaType = BrowseMediaType.ARTIST,
        artworkId = artist.coverArtId,
        // An artist's children are albums, so they get the cover-art grid regardless of surface --
        // there is no Artists tab on a watch, which is the only surface that would want a list.
        childStyle = BrowseStyle.GRID,
      )
    }

  /** One artist's albums. Same shape as [albumsNodes] without the shuffle rows. */
  fun artistChildren(albums: List<Album>): List<BrowseNode> = albums.map(::albumNode)

  /** Alias of [artistChildren], named for the call site that reads better: an album's siblings. */
  fun albumChildrenOfArtist(albums: List<Album>): List<BrowseNode> = artistChildren(albums)

  /** One album's tracks, in the order given (Plan 2's mirror orders by disc then track). */
  fun albumChildren(songs: List<Song>): List<BrowseNode> = songNodes(songs)

  /** Tracks, in the order given. Playable leaves; see [BrowseId.Track] for why the id is bare. */
  fun songNodes(songs: List<Song>): List<BrowseNode> =
    songs.map { song ->
      BrowseNode(
        id = BrowseId.Track(song.id),
        title = song.title,
        subtitle = song.artistName?.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST,
        isBrowsable = false,
        isPlayable = true,
        mediaType = BrowseMediaType.TRACK,
        artworkId = song.coverArtId,
        durationMs = song.durationSeconds * 1_000L,
      )
    }

  /** Every library, by id, with its role spelled out — the phone-only Libraries tab. */
  fun libraryNodes(libraries: List<MusicLibrary>): List<BrowseNode> =
    libraries.sortedBy(MusicLibrary::id).map { library ->
      BrowseNode(
        id = BrowseId.Library(library.id),
        title = library.name,
        subtitle = when (library.role) {
          LibraryRole.MUSIC -> "Music"
          LibraryRole.AUDIOBOOKS -> "Audiobooks"
          LibraryRole.UNASSIGNED -> "Not assigned"
        },
        isBrowsable = true,
        isPlayable = false,
        mediaType = BrowseMediaType.FOLDER_MIXED,
        childStyle = BrowseStyle.GRID,
      )
    }

  /**
   * One library's contents: its albums, preceded by a shuffle row **only if it is a Music library**.
   *
   * Spec section 1: shuffle must not pull chapter 14 of a novel into a music session. On a surface
   * with no UI of its own, that rule is expressed as the absence of a row.
   */
  fun libraryChildren(library: MusicLibrary, albums: List<Album>): List<BrowseNode> =
    if (library.role == LibraryRole.MUSIC) {
      listOf(shuffleNode(library)) + albums.map(::albumNode)
    } else {
      albums.map(::albumNode)
    }

  private fun folder(
    id: BrowseId,
    title: String,
    mediaType: BrowseMediaType,
    childStyle: BrowseStyle,
  ) = BrowseNode(
    id = id,
    title = title,
    subtitle = null,
    isBrowsable = true,
    isPlayable = false,
    mediaType = mediaType,
    childStyle = childStyle,
  )

  private fun shuffleNode(library: MusicLibrary) = BrowseNode(
    id = BrowseId.Shuffle(library.id),
    title = "Shuffle ${library.name}",
    subtitle = null,
    isBrowsable = false,
    isPlayable = true,
    mediaType = BrowseMediaType.MIXED,
  )

  private fun albumNode(album: Album) = BrowseNode(
    id = BrowseId.Album(album.id),
    title = album.name,
    subtitle = album.artistName?.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST,
    isBrowsable = true,
    isPlayable = true,
    mediaType = BrowseMediaType.ALBUM,
    artworkId = album.coverArtId,
    childStyle = BrowseStyle.LIST,
    durationMs = album.durationSeconds * 1_000L,
  )

  private fun bookNode(book: BookSummary) = BrowseNode(
    id = BrowseId.Book(book.bookId),
    title = book.title,
    subtitle = bookSubtitle(book),
    // A one-file book has nothing worth a second screen: opening it would show one row repeating
    // the row above it. It stays playable, so tapping it still resumes.
    isBrowsable = book.fileCount > 1,
    isPlayable = true,
    mediaType = BrowseMediaType.AUDIO_BOOK,
    artworkId = book.coverArtId,
    childStyle = BrowseStyle.LIST,
    completion = completionOf(book),
    durationMs = book.durationMs,
  )

  private fun bookSubtitle(book: BookSummary): String {
    val author = book.author.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST
    return if (book.hasStarted && !book.isFinished) {
      "$author · ${BrowseText.remainingLabel(book.remainingMs)}"
    } else {
      author
    }
  }

  private fun completionOf(book: BookSummary): BrowseCompletion = when {
    book.isFinished -> BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0)
    book.hasStarted -> BrowseCompletion(
      BrowseCompletionStatus.PARTIALLY_PLAYED,
      book.progressFraction.toDouble(),
    )
    else -> BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0)
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:model:test --tests '*BrowseTreeTest*' --tests '*BrowseTextTest*'`
Expected: PASS.

- [ ] **Step 6: Prove the surface branch and every order can fail**

Mutations 1–3 are the ones this plan was written against by name. Run each, record which tests go
red, then revert.

1. **Collapse the surface branch.** Make `root` ignore `surface` and always return the `PHONE`
   list. Expect `the three surfaces produce three different roots`, `the car root never exceeds the
   four tabs Android Auto renders` and `the browsable style follows the surface` to fail. **If any
   of those three still passes, the branch is not tested and the rest of this task is worthless.**
2. **Make the two book orders the same.** Have `bookNodes` return `continueNodes(books, books.size)`.
   Expect `the book shelf is alphabetical, which is a different order from continue` to fail.
3. **Make `bookNodes` return its input unsorted.** Expect the same test to fail on the first
   element. Two different mutations, one test, and it catches both — which is the point of feeding
   both functions a deliberately unsorted fixture.
4. **Give every library a shuffle row.** Drop the `role == MUSIC` guard in `libraryChildren`.
   Expect `an audiobook library gets no shuffle node and a music library does` to fail. **This is
   spec §1's rule; watch it fail.**
5. **Fix one field.** In `albumNode`, replace `album.coverArtId` with `"cov-a"`. Expect `an album
   node carries every field of its album` to fail on `artworkId` **and nothing else** — the
   field-level rule, demonstrated.
6. **Fix the fraction.** In `completionOf`, replace `book.progressFraction.toDouble()` with `0.5`.
   Expect `a book's completion is one of three distinct values` to fail. A `isGreaterThan(0.0)`
   assertion would have passed; that is why the fixture's numbers make the fraction exactly `0.2`.
7. **Sort the shuffle rows by name instead of id.** Expect `the albums node puts one shuffle per
   music library first, in library id order` to fail — the fixture's two libraries are deliberately
   supplied in the wrong order and named so that name order and id order disagree.
8. **Drop the `take(limit)`.** Expect `continue is capped by the surface's own limit` to fail.

- [ ] **Step 7: Probes, floors, commit**

`ci/mutation-probes.sh` — add mutations 1, 4 and 5. Add
`core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt` to `revert()`'s file list.

`build.gradle.kts` — measure, then extend `:core:model`'s existing `"CLASS"`-element BRANCH rule
with `"app.muplay.model.browse.BrowseTree"`, `"app.muplay.model.browse.BrowseText"`,
`"app.muplay.model.browse.BrowseSurface"` and the zero-branch data holders
(`"app.muplay.model.browse.BrowseNode"`, `"app.muplay.model.browse.BrowseCompletion"`,
`"app.muplay.model.browse.BrowseMediaType"`, `"app.muplay.model.browse.BrowseStyle"`,
`"app.muplay.model.browse.BrowseCompletionStatus"`) so `warnUngatedClasses` stays quiet. **Read the
measured report before choosing a minimum**, and if any class comes in under `0.90` find the
uncovered branch and test it rather than lowering the floor.

```bash
./gradlew :core:model:test :core:model:jacocoJvmCoverageVerification
git add core/model build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(model): the browse tree, as three surfaces over one library"
```

---

## Task 3: `BrowseSurfaces` — the `isAutomotiveController` branch, and the one expression no CI can see

**Files:**
- Modify: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt` (add `BrowseSurfaces`)
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowseSurfacesTest.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/browse/SurfaceResolver.kt`
- Modify: `build.gradle.kts` (`:core:model` floors)

**Interfaces:**
- Consumes: `BrowseSurface` — Task 2. `androidx.media3.session.MediaSession.ControllerInfo`
  (`packageName`, `connectionHints`, and the two predicates named below) — Media3 1.11.0.
- Produces:
  - `object BrowseSurfaces` with
    `fun of(packageName: String, ownPackageName: String, isCarController: Boolean, hintSurface: String?): BrowseSurface`,
    `const val HINT_KEY`, `const val HINT_CAR`, `const val HINT_WATCH`,
    `val CAR_PACKAGES: Set<String>`, `val WATCH_PACKAGES: Set<String>`
  - `fun interface SurfaceResolver { fun surfaceOf(browser: MediaSession.ControllerInfo): BrowseSurface }`
  - `class DefaultSurfaceResolver @Inject constructor(@ApplicationContext context: Context) : SurfaceResolver`

### What spec §7 means by "`isAutomotiveController` branching lets it be tested with no car"

It means the branch must be reachable from something other than a car, and this task is where that
is arranged. The classification is a **pure function of four values**, none of which is an Android
type:

| Argument | Where it comes from | Why it is a separate argument |
|---|---|---|
| `packageName` | `ControllerInfo.packageName` | The identity of whatever connected. |
| `ownPackageName` | `Context.packageName` | So the hint below can be honoured from this app and refused from every other. |
| `isCarController` | Media3's own `ControllerInfo` predicates | Google keeps the real list of car controller packages; ours is a backstop, not the authority. |
| `hintSurface` | `ControllerInfo.connectionHints` | How this app's own Wear client declares itself a watch. |

Everything about *"is this a car"* is decided in that function, on the JVM, with all four arguments
varied one at a time. What remains on an Android type is one expression, in
`DefaultSurfaceResolver`, that reads four values off a `ControllerInfo` and passes them along.

### The connection hint, and why it is honoured only from our own package

A `MediaBrowser` may declare which surface it is by putting `HINT_KEY` in its connection hints. Two
users, one production and one test:

- **`:wear`'s browser sets `HINT_WATCH`.** Its `packageName` is `app.muplay` — the same
  application id as the phone app — so no package-based rule could ever tell the watch apart from
  the phone. A self-declaration is the only mechanism available.
- **`:app`'s Tier 2 browse journey sets `HINT_CAR`.** That is what makes the whole car path —
  tree, `MediaItem` mapping, extras, real Media3 IPC — exercisable on the phone emulator, with no
  car and no Automotive image.

The hint is refused from any other package. Without that rule, any installed app could ask for a
different tree, and — more to the point here — the test would prove nothing, because a hint that
everyone can set is not a self-declaration, it is a request. `BrowseSurfacesTest` asserts the
refusal directly.

**A code path whose only production user is `:wear` and whose other user is a test is worth naming
as such rather than hiding.** The alternative was an untestable branch, and this project's own
history — six rounds of assertions that executed without discriminating — ranks that strictly
worse.

### The one expression no CI in this repository can observe

```kotlin
isCarController = browser.isAutomotiveController || browser.isAutomobileController
```

`MediaSession.ControllerInfo` cannot be constructed by a test — it is created by Media3 when a
controller connects — and no controller this project can start will make either predicate `true`.
So:

- Everything **downstream** of that expression is proven on the phone emulator, over real IPC, by
  Task 4's browser connecting with `HINT_CAR`.
- Everything **inside** `BrowseSurfaces.of` is proven on the JVM, including the `isCarController`
  argument at both of its values.
- The expression **itself** — that Media3's two predicates really are `true` for a genuine Android
  Auto controller — is verified by Google's Desktop Head Unit or a real car, by hand, once. Task 11
  records it as a manual step with a written procedure and **does not write a gate for it**, because
  a gate that cannot run is worse than no gate.

The residue is deliberately one line. If it is wrong, `CAR_PACKAGES` still catches every Android
Auto and Automotive OS host Google ships today, and the failure mode is a car receiving the six-tab
phone root — which Auto truncates to four tabs. Degraded, not wrong.

- [ ] **Step 1: Write the failing test**

`core/model/src/test/kotlin/app/muplay/model/browse/BrowseSurfacesTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which tree a given client gets.
 *
 * Four arguments, and each one is varied **alone**, with the other three held constant, at two or
 * more values — rule 2. A test that fed one combination and asserted one answer would pass against
 * a function that ignored three of its inputs, which is exactly the shape of the defect this plan
 * was warned about by name.
 */
class BrowseSurfacesTest {

  @Test
  fun `each of the four arguments changes the answer on its own`() {
    // The baseline: this app's own phone UI.
    val baseline = BrowseSurfaces.of(
      packageName = OWN,
      ownPackageName = OWN,
      isCarController = false,
      hintSurface = null,
    )
    // Vary exactly one argument per row. Four rows, four different reasons, and the exact answers.
    val varied = listOf(
      // packageName alone
      BrowseSurfaces.of(ANDROID_AUTO, OWN, isCarController = false, hintSurface = null),
      // isCarController alone
      BrowseSurfaces.of(OWN, OWN, isCarController = true, hintSurface = null),
      // hintSurface alone
      BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
      // ownPackageName alone -- the same hint, now from a package that is not ours
      BrowseSurfaces.of(OWN, "com.example.other", isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
    )

    assertThat(baseline).isEqualTo(BrowseSurface.PHONE)
    assertThat(varied).containsExactly(
      BrowseSurface.CAR,
      BrowseSurface.CAR,
      BrowseSurface.WATCH,
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun `every known car and watch package maps to its surface`() {
    val cars = BrowseSurfaces.CAR_PACKAGES.toList().sorted()
    val watches = BrowseSurfaces.WATCH_PACKAGES.toList().sorted()

    // Mapped and compared as exact lists rather than `allMatch`, which is vacuously true on an
    // empty set -- and an empty set is exactly what a bad refactor of these constants produces.
    assertThat(cars.map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) })
      .isNotEmpty
      .allSatisfy { assertThat(it).isEqualTo(BrowseSurface.CAR) }
    assertThat(watches.map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) })
      .isNotEmpty
      .allSatisfy { assertThat(it).isEqualTo(BrowseSurface.WATCH) }

    // The lists themselves, pinned. These are Google's package names, and a silent edit to one of
    // them is a silent change to which tree a car gets.
    assertThat(cars).containsExactly(
      "com.android.car.carlauncher",
      "com.android.car.media",
      "com.google.android.apps.automotive.templates.host",
      "com.google.android.gms.car",
      "com.google.android.projection.gearhead",
    )
    assertThat(watches).containsExactly(
      "com.google.android.wearable.app",
      "com.google.android.wearable.media.sessions",
    )
  }

  @Test
  fun `the media3 predicate wins over every package and every hint`() {
    // Google owns the real answer to "is this a car". Our package list is a backstop for hosts
    // Media3 does not know yet, never an override of one it does.
    assertThat(
      listOf(
        BrowseSurfaces.of(OWN, OWN, isCarController = true, hintSurface = BrowseSurfaces.HINT_WATCH),
        BrowseSurfaces.of(WEAR_APP, OWN, isCarController = true, hintSurface = null),
        BrowseSurfaces.of("com.example.unknown", OWN, isCarController = true, hintSurface = null),
      ),
    ).containsExactly(BrowseSurface.CAR, BrowseSurface.CAR, BrowseSurface.CAR)
  }

  @Test
  fun `a hint is honoured from our own package and refused from any other`() {
    val fromUs = listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH)
      .map { BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = it) }
    val fromThem = listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH)
      .map { BrowseSurfaces.of("com.example.other", OWN, isCarController = false, hintSurface = it) }

    assertThat(fromUs).containsExactly(BrowseSurface.CAR, BrowseSurface.WATCH)
    assertThat(fromThem).containsExactly(BrowseSurface.PHONE, BrowseSurface.PHONE)
  }

  @Test
  fun `an unrecognised hint from our own package is the phone tree, not a crash`() {
    assertThat(
      listOf("", "  ", "automotive", "WATCH", "car ").map {
        BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = it)
      },
    ).containsExactly(
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      // Hints are exact, lower-case tokens. "WATCH" is not HINT_WATCH.
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun `package matching is exact, not a prefix and not case-insensitive`() {
    // Both of these have shipped in real apps: a `startsWith` check that a repackaged app can
    // satisfy, and an `equalsIgnoreCase` that treats a different package as the same one.
    assertThat(
      listOf(
        "com.google.android.projection.gearhead.evil",
        "evil.com.google.android.projection.gearhead",
        "COM.GOOGLE.ANDROID.PROJECTION.GEARHEAD",
        "com.google.android.projection.gearhea",
      ).map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) },
    ).containsExactly(
      BrowseSurface.PHONE, BrowseSurface.PHONE, BrowseSurface.PHONE, BrowseSurface.PHONE,
    )
  }

  @Test
  fun `the hint key is a namespaced constant, because it goes into someone else's bundle`() {
    // Connection hints are a shared Bundle. A key of "surface" would collide with anything else
    // that had the same idea, and the collision would be silent.
    assertThat(BrowseSurfaces.HINT_KEY).isEqualTo("app.muplay.browse.SURFACE")
    assertThat(listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH))
      .containsExactly("car", "watch")
  }

  private companion object {
    const val OWN = "app.muplay"
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    const val WEAR_APP = "com.google.android.wearable.app"
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:model:test --tests '*BrowseSurfacesTest*'`
Expected: FAIL — `Unresolved reference: BrowseSurfaces`.

- [ ] **Step 3: Implement `BrowseSurfaces`**

Append to `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt`:

```kotlin
/**
 * Which [BrowseSurface] a connected client is.
 *
 * A pure function of four values so that the whole decision is testable without a car, a watch or
 * an Android runtime — spec section 7's *"`isAutomotiveController` branching lets it be tested with
 * no car"*, made literal. `DefaultSurfaceResolver` in `:core:media` is the only place these four
 * values are read off a Media3 `ControllerInfo`, and it is one expression long.
 */
object BrowseSurfaces {

  /**
   * The connection-hint key by which this app's own clients declare their surface.
   *
   * Namespaced, because connection hints are one shared `Bundle` handed to a session by whatever
   * connected: a key of `"surface"` would collide with any other library that had the same idea,
   * and a `Bundle` key collision is silent.
   */
  const val HINT_KEY: String = "app.muplay.browse.SURFACE"

  /** Used by `:app`'s Tier 2 browse journey. See Task 3's header for why this exists. */
  const val HINT_CAR: String = "car"

  /** Used by `:wear`'s `MediaBrowser`. Its package is this app's, so nothing else could tell. */
  const val HINT_WATCH: String = "watch"

  /**
   * Hosts that render a media browse tree in a car.
   *
   * A **backstop** for hosts Media3's own predicates do not know, never an override of one they do
   * — `of` consults the predicate first. `com.google.android.projection.gearhead` is Android Auto
   * (projection from the phone); `com.android.car.media` and `com.android.car.carlauncher` are
   * Android Automotive OS; `com.google.android.gms.car` is the older projection host;
   * `com.google.android.apps.automotive.templates.host` is the templates host used on AAOS
   * headends. All five get the same tree, because what differs is the render, not the runtime.
   */
  val CAR_PACKAGES: Set<String> = setOf(
    "com.google.android.projection.gearhead",
    "com.google.android.gms.car",
    "com.android.car.media",
    "com.android.car.carlauncher",
    "com.google.android.apps.automotive.templates.host",
  )

  /** Wear OS's own bridged media surfaces — the companion app and the media-session controller. */
  val WATCH_PACKAGES: Set<String> = setOf(
    "com.google.android.wearable.app",
    "com.google.android.wearable.media.sessions",
  )

  /**
   * The classification, in strict precedence order.
   *
   * 1. Media3's own answer, if it says car. Google maintains that list; this file does not.
   * 2. Our backstop package lists, matched **exactly** — not by prefix and not case-insensitively.
   *    A prefix match is satisfiable by a repackaged app; a case-insensitive one treats a genuinely
   *    different package as the same one.
   * 3. A self-declared hint, and **only** from [ownPackageName]. From anyone else it is a request,
   *    not a declaration, and is ignored.
   * 4. Otherwise a phone: the fullest tree, which is also the right answer for the Assistant, for
   *    the system's media resumption and for a browser this app has never heard of.
   */
  fun of(
    packageName: String,
    ownPackageName: String,
    isCarController: Boolean,
    hintSurface: String?,
  ): BrowseSurface = when {
    isCarController -> BrowseSurface.CAR
    packageName in CAR_PACKAGES -> BrowseSurface.CAR
    packageName in WATCH_PACKAGES -> BrowseSurface.WATCH
    packageName == ownPackageName -> when (hintSurface) {
      HINT_CAR -> BrowseSurface.CAR
      HINT_WATCH -> BrowseSurface.WATCH
      else -> BrowseSurface.PHONE
    }
    else -> BrowseSurface.PHONE
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:model:test --tests '*BrowseSurfacesTest*'`
Expected: PASS, 7/7.

- [ ] **Step 5: Implement the resolver, and probe Media3's two predicates**

`core/media/src/main/kotlin/app/muplay/media/browse/SurfaceResolver.kt`:

```kotlin
package app.muplay.media.browse

import android.content.Context
import androidx.media3.session.MediaSession
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseSurfaces
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Turns a connected controller into the [BrowseSurface] whose tree it should get.
 *
 * An interface with one method, injected into `MuPlayLibraryCallback`, for one reason: a
 * `MediaSession.ControllerInfo` cannot be constructed by a test, so an implementation that reads
 * one is unreachable from a JVM test and only partly reachable from a device test. Keeping the
 * *decision* behind this seam means the callback's own behaviour — which tree it builds, in which
 * order, with which extras — is exercisable at every surface from a real `MediaBrowser` and from a
 * plain unit test alike.
 */
fun interface SurfaceResolver {
  fun surfaceOf(browser: MediaSession.ControllerInfo): BrowseSurface
}

/**
 * The production resolver: four values off the controller, straight into [BrowseSurfaces.of].
 *
 * **This class is the one place in the plan that CI cannot fully observe**, and it is one
 * expression long on purpose. `isAutomotiveController`/`isAutomobileController` are `true` only for
 * a controller this project cannot start, so their `true` case is verified by hand against the
 * Desktop Head Unit (Task 11's manual checklist) and never by a gate. Everything else here —
 * `packageName`, `connectionHints`, and the whole of [BrowseSurfaces.of] — is proven, on the JVM in
 * Task 3 and over real Media3 IPC in Task 4.
 */
class DefaultSurfaceResolver @Inject constructor(
  @ApplicationContext private val context: Context,
) : SurfaceResolver {

  override fun surfaceOf(browser: MediaSession.ControllerInfo): BrowseSurface =
    BrowseSurfaces.of(
      packageName = browser.packageName,
      ownPackageName = context.packageName,
      isCarController = browser.isAutomotiveController || browser.isAutomobileController,
      hintSurface = browser.connectionHints.getString(BrowseSurfaces.HINT_KEY),
    )
}
```

Run: `./gradlew :core:media:compileDebugKotlin`

**Expected: PASS.** If instead it fails with `Unresolved reference: isAutomotiveController` or
`isAutomobileController`, those predicates are not in the resolved Media3 1.11.0 — in which case
make exactly this one-line change and nothing else:

```kotlin
      // Media3 1.11.0 exposes no car predicate on ControllerInfo; CAR_PACKAGES carries the whole
      // decision. A car host Google adds later, and does not appear in that list, receives the
      // phone root -- which Android Auto truncates to its four tabs. Degraded, not wrong.
      isCarController = false,
```

**Either way, record which branch you took in the task report**, and — if you took the fallback —
say so again in Task 11's spec correction, because spec §7 names `isAutomotiveController` by name
and would then be describing an API that does not exist.

- [ ] **Step 6: Prove the classification can fail**

1. In `of`, move the `isCarController` arm below the `packageName in WATCH_PACKAGES` arm. Expect
   `the media3 predicate wins over every package and every hint` to fail on the second element.
2. Drop the `packageName == ownPackageName` guard so any package's hint is honoured. Expect `a hint
   is honoured from our own package and refused from any other` to fail — **and confirm that `each
   of the four arguments changes the answer on its own` fails too**, on its fourth row, since that
   row exists precisely to make `ownPackageName` a live argument.
3. Replace `packageName in CAR_PACKAGES` with `CAR_PACKAGES.any { packageName.startsWith(it) }`.
   Expect `package matching is exact, not a prefix and not case-insensitive` to fail on the first
   element.
4. Delete one entry from `CAR_PACKAGES`. Expect `every known car and watch package maps to its
   surface` to fail on the pinned list.
5. Return `BrowseSurface.PHONE` unconditionally. Expect **five** of the seven tests to fail; if any
   of `each of the four arguments…`, `the media3 predicate wins…` or `a hint is honoured…` survives
   that, it is not testing what its name says.

- [ ] **Step 7: Probes, floors, commit**

`ci/mutation-probes.sh` — add mutations 2 and 3, and add
`core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt` to `revert()`'s file list.

`build.gradle.kts` — add `"app.muplay.model.browse.BrowseSurfaces"` to `:core:model`'s existing
`"CLASS"`-element BRANCH rule. `DefaultSurfaceResolver` lives in `:core:media` and is exercised
only from the device; add it to that module's instrumented-only floor in Task 4 rather than here,
once there is a report to measure.

```bash
./gradlew :core:model:test :core:media:compileDebugKotlin :core:model:jacocoJvmCoverageVerification
git add core/model core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): classify a connected controller into a browse surface"
```

---
