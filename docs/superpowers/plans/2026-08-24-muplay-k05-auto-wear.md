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
