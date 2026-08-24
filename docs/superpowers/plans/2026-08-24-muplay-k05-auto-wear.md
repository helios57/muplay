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
| 11 | The gates — what each image proves, what nothing proves, coverage, and the spec | every gate has been watched failing, and what CI cannot see is written down instead |

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
- **Chapters as browse nodes.** Spec §5's chapter extraction is Plan 4's and is a real
  differentiator, but it is **not** exposed in the browse tree, and that is a decision rather than an
  oversight. Reading a book's chapters means a `MetadataRetriever` pass with an HTTP Range request
  **per file** (Plan 4 Task 3); `onGetChildren` runs on a car host's own timeout, and an app that
  answers it with a network round trip per row is an app that shows a spinner and then an error. A
  multi-file book's children are therefore its **files** — which is also what a listener of a ripped
  audiobook recognises — and chapter navigation stays where it is already gated: Plan 4's book
  player on the phone, and Task 9's player on the watch, both of which read `ChapterRepository`
  after playback has already started. Task 11 records it in the spec.
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
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseExtras.kt` | **new** — the Android Auto wire keys, as literals and a plain `Map` |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowsePaging.kt` | **new** — the page arithmetic, including the `Int.MAX_VALUE` overflow |
| `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSelection.kt` | **new** — what a playable id expands to: a queue and an index |
| `core/media/src/main/kotlin/app/muplay/media/browse/SurfaceResolver.kt` | **new** — `ControllerInfo` → `BrowseSurface`, injectable |
| `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt` | **new** — the whole `MediaLibrarySession.Callback` surface |
| `core/model/src/main/kotlin/app/muplay/model/browse/PlayFromSearch.kt` | **new** — pure: a spoken query → the one thing to play |
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
| `wear/src/main/kotlin/app/muplay/wear/WearApp.kt` | **new** — the two screens, a back stack, and no navigation library |
| `wear/src/main/kotlin/app/muplay/wear/WearBrowser.kt` | **new** — the watch's one `MediaBrowser`, carrying the watch hint |
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
    `artistNodes(artists)`, `artistChildren(albums)`, `albumChildrenOfArtist(albums)`,
    `albumChildren(songs)`, `songNodes(songs)`,
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

## Task 4: `BrowseItems` and `MuPlayLibraryCallback` — the tree on a real `MediaBrowser`

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowsePaging.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseExtras.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowsePagingTest.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/BrowseExtrasTest.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/browse/BrowseItems.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Modify: `core/media/build.gradle.kts` (`:core:database` and `:core:model` are already there via
  Plan 3; add nothing unless the import does not resolve)
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/browse/BrowseItemsTest.kt`
- Test: `app/src/androidTest/kotlin/app/muplay/BrowseTreeJourneyTest.kt`
- Modify: `app/build.gradle.kts`, `.github/workflows/e2e.yml`, `build.gradle.kts`

**Interfaces:**
- Consumes:
  - `BrowseId`, `BrowseNode`, `BrowseSurface`, `BrowseSurfaces`, `BrowseTree`, `BrowseStyle`,
    `BrowseCompletion`, `BrowseMediaType` — Tasks 1–3. `SurfaceResolver` — Task 3.
  - **`LibraryRepository.libraries`, `.idsWithRole(role)`** — Plan 2 Task 4.
  - **`BrowseRepository.albums`, `.artists`, `.albumsByArtist(artistId)`, `.songs(albumId)`,
    `.album(albumId)`, `.coverArtUrl(coverArtId, sizePx)`** — Plan 2 Task 5. **Their exact shapes
    (`Flow` versus `suspend`, and whether any is already library-scoped) are Plan 2's to fix.**
    Read the real file. Every call site below says which of the two it assumes and what to change
    if it landed the other way; the *invariant* — that music content is filtered to
    `idsWithRole(MUSIC)` — is this task's and does not change either way.
  - **`AudiobookRepository.bookshelf(): Flow<List<BookSummary>>`, `.book(bookId)`, `.files(bookId)`**
    — Plan 4 Task 4.
  - `MuPlaybackService`, `PlaybackNotification` — Plan 3 Task 5.
  - `QueueRepository.ARTWORK_SIZE_PX` — Plan 3 Task 4.
  - `app.muplay.testing.BookFixtures` — Plan 4 Task 1.
- Produces:
  - `object BrowsePaging` with `fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T>`
  - `object BrowseExtras` with the seven wire constants below, plus
    `fun forNode(node: BrowseNode): Map<String, Any>` and
    `fun forRoot(surface: BrowseSurface): Map<String, Any>`
  - `class BrowseTreeRepository @Inject constructor(libraryRepository, browseRepository, audiobookRepository)`
    with `suspend fun children(id: BrowseId, surface: BrowseSurface): List<BrowseNode>?`,
    `suspend fun node(id: BrowseId, surface: BrowseSurface): BrowseNode?`,
    `suspend fun artworkUri(artworkId: String?): String?`
  - `object BrowseItems` with `fun of(node: BrowseNode, artworkUri: String?): MediaItem`,
    `fun root(surface: BrowseSurface): MediaItem`, `fun mediaTypeOf(type: BrowseMediaType): Int`,
    `fun bundleOf(values: Map<String, Any>): Bundle`
  - `class MuPlayLibraryCallback @Inject constructor(treeRepository: BrowseTreeRepository, surfaceResolver: SurfaceResolver)`
    implementing `MediaLibrarySession.Callback`'s `onGetLibraryRoot`, `onGetChildren` and
    `onGetItem`, plus `fun release()` (Task 5 adds a `queueRepository` parameter)
  - `MediaModule` binds `SurfaceResolver` to `DefaultSurfaceResolver`

### Why the extras are literal strings in `:core:model` and not Media3 constants

The keys below are the contract Android Auto reads. They are also mirrored by
`androidx.media3.session.MediaConstants` under names that have changed across Media3 versions
(`EXTRAS_KEY_CONTENT_STYLE_BROWSABLE` and friends). Pinning the **strings** here does two things a
constant reference cannot:

1. **A Media3 rename cannot silently change what the car receives.** A renamed constant is a
   compile error; a *re-valued* one is not, and it would move a wire key with no test noticing.
2. **The whole extras decision becomes a JVM test.** `forNode` returns a `Map<String, Any>`, not a
   `Bundle`, so *what goes in it* is Tier 1 and only *putting it into a Bundle* is Tier 2. That
   split is worth a small amount of duplication, because the map's contents are where the
   interesting branching is (a partially-played book carries a percentage; a finished one does not).

### The three layers, and what each tier can see

| Layer | Module | Tier | What it proves |
|---|---|---|---|
| `BrowseTree`, `BrowseExtras`, `BrowsePaging` | `:core:model` | **1 (JVM)** | which nodes, in which order, with which extras, on which page |
| `BrowseTreeRepository` | `:core:database` | 1 for its filtering (Plan 2's repositories are already exercised against a real Room and a real container), 2 end-to-end | that music content is scoped to Music libraries |
| `BrowseItems` | `:core:media` | **2 (phone image)** | that a `BrowseNode` becomes exactly the `MediaItem` it claims to |
| `MuPlayLibraryCallback` | `:core:media` | **2 (phone image)** | that a **real `MediaBrowser`** reads the real tree over real IPC, at every surface |

**No Automotive OS image and no Wear image are needed for any of it.** Android Auto is projection:
the app, the session and this callback all run on the phone. What an Automotive image would add is
Google's *rendering* of the tree — not this project's code, and drivable only by UiAutomator
against a system app's UI. Task 11 says so once more, next to the manual DHU checklist.

### `page` and `pageSize` are not decoration

Android Auto pages a browse list, and `pageSize` arrives as `Int.MAX_VALUE` from a client that
wants everything. `page * pageSize` overflows to a negative number for any page above the first at
that size, and `subList` with a negative index throws inside a `ListenableFuture`, where it surfaces
as an unexplained empty list in a car. `BrowsePaging` is four lines and a test with that exact case
in it.

- [ ] **Step 1: Write the failing JVM tests**

`core/model/src/test/kotlin/app/muplay/model/browse/BrowsePagingTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrowsePagingTest {

  @Test
  fun `pages divide the list and the last page is short`() {
    assertThat(listOf(0, 1, 2).map { BrowsePaging.page(ITEMS, page = it, pageSize = 2) })
      .containsExactly(listOf("a", "b"), listOf("c", "d"), listOf("e"))
  }

  @Test
  fun `a page past the end is empty rather than an error`() {
    assertThat(BrowsePaging.page(ITEMS, page = 3, pageSize = 2)).isEmpty()
    assertThat(BrowsePaging.page(emptyList<String>(), page = 0, pageSize = 2)).isEmpty()
  }

  @Test
  fun `a page size of Int MAX_VALUE returns everything and does not overflow`() {
    // The case that matters: `page * pageSize` at page 1 with this size is -2147483648, and
    // `subList` with a negative index throws inside a ListenableFuture, where it reaches a car as
    // an unexplained empty list.
    assertThat(BrowsePaging.page(ITEMS, page = 0, pageSize = Int.MAX_VALUE)).isEqualTo(ITEMS)
    assertThat(BrowsePaging.page(ITEMS, page = 1, pageSize = Int.MAX_VALUE)).isEmpty()
    assertThat(BrowsePaging.page(ITEMS, page = 2, pageSize = Int.MAX_VALUE)).isEmpty()
  }

  @Test
  fun `a nonsensical page or size is empty rather than a crash`() {
    assertThat(
      listOf(
        BrowsePaging.page(ITEMS, page = -1, pageSize = 2),
        BrowsePaging.page(ITEMS, page = 0, pageSize = 0),
        BrowsePaging.page(ITEMS, page = 0, pageSize = -5),
      ),
    ).containsExactly(emptyList(), emptyList(), emptyList())
  }

  @Test
  fun `paging preserves order within a page`() {
    // A `page` implemented with a Set, or with `shuffled().take()`, passes every size assertion
    // above and fails this one.
    assertThat(BrowsePaging.page(ITEMS, page = 0, pageSize = 5)).containsExactly("a", "b", "c", "d", "e")
  }

  private companion object {
    val ITEMS = listOf("a", "b", "c", "d", "e")
  }
}
```

`core/model/src/test/kotlin/app/muplay/model/browse/BrowseExtrasTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The extras a car head unit reads.
 *
 * The **string keys** are asserted as literals, because they are a contract with software this
 * project does not own and no compiler checks them. The **values** are asserted as exact maps,
 * because "the bundle has a completion status in it" is satisfied by a status that is the same
 * constant for every book.
 */
class BrowseExtrasTest {

  @Test
  fun `the wire keys and values are exactly the ones Android Auto documents`() {
    assertThat(
      listOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED,
        BrowseExtras.CONTENT_STYLE_BROWSABLE,
        BrowseExtras.CONTENT_STYLE_PLAYABLE,
        BrowseExtras.COMPLETION_STATUS,
        BrowseExtras.COMPLETION_PERCENTAGE,
      ),
    ).containsExactly(
      "android.media.browse.CONTENT_STYLE_SUPPORTED",
      "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT",
      "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",
      "androidx.media.MediaBrowserCompat.extras.COMPLETION_STATUS",
      "androidx.media.MediaBrowserCompat.extras.COMPLETION_PERCENTAGE",
    )
    assertThat(
      listOf(
        BrowseExtras.STYLE_LIST,
        BrowseExtras.STYLE_GRID,
        BrowseExtras.STATUS_NOT_PLAYED,
        BrowseExtras.STATUS_PARTIALLY_PLAYED,
        BrowseExtras.STATUS_FULLY_PLAYED,
      ),
    ).containsExactly(1, 2, 0, 1, 2)
  }

  @Test
  fun `a browsable node carries its own child style and a list style for its playables`() {
    val grid = BrowseExtras.forNode(folder(BrowseStyle.GRID))
    val list = BrowseExtras.forNode(folder(BrowseStyle.LIST))

    assertThat(grid).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 2,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
    // Both observations, so the style is proven to come from the node rather than to be a constant.
    assertThat(list).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 1,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
  }

  @Test
  fun `a playable leaf carries no style extras at all`() {
    assertThat(BrowseExtras.forNode(leaf())).isEmpty()
  }

  @Test
  fun `only a partially played item carries a percentage`() {
    val notPlayed = BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0)))
    val partly = BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, 0.42)))
    val finished = BrowseExtras.forNode(book(BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0)))

    assertThat(notPlayed).isEqualTo(mapOf(BrowseExtras.COMPLETION_STATUS to 0))
    assertThat(partly).isEqualTo(
      mapOf(
        BrowseExtras.COMPLETION_STATUS to 1,
        BrowseExtras.COMPLETION_PERCENTAGE to 0.42,
      ),
    )
    assertThat(finished).isEqualTo(mapOf(BrowseExtras.COMPLETION_STATUS to 2))
  }

  @Test
  fun `the root advertises content style support and the surface's own default`() {
    assertThat(BrowseExtras.forRoot(BrowseSurface.CAR)).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED to true,
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 2,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
    assertThat(BrowseExtras.forRoot(BrowseSurface.WATCH)).isEqualTo(
      mapOf(
        BrowseExtras.CONTENT_STYLE_SUPPORTED to true,
        BrowseExtras.CONTENT_STYLE_BROWSABLE to 1,
        BrowseExtras.CONTENT_STYLE_PLAYABLE to 1,
      ),
    )
  }

  private companion object {
    fun folder(style: BrowseStyle) = BrowseNode(
      id = BrowseId.Albums,
      title = "Albums",
      isBrowsable = true,
      isPlayable = false,
      mediaType = BrowseMediaType.FOLDER_ALBUMS,
      childStyle = style,
    )

    fun leaf() = BrowseNode(
      id = BrowseId.Track("tr-1"),
      title = "Track 1",
      isBrowsable = false,
      isPlayable = true,
      mediaType = BrowseMediaType.TRACK,
    )

    fun book(completion: BrowseCompletion) = BrowseNode(
      id = BrowseId.Book("al-1"),
      title = "Test Book",
      isBrowsable = false,
      isPlayable = true,
      mediaType = BrowseMediaType.AUDIO_BOOK,
      completion = completion,
    )
  }
}
```

- [ ] **Step 2: Run them to verify they fail, then implement both**

Run: `./gradlew :core:model:test --tests '*BrowsePagingTest*' --tests '*BrowseExtrasTest*'`
Expected: FAIL — `Unresolved reference: BrowsePaging`.

`core/model/src/main/kotlin/app/muplay/model/browse/BrowsePaging.kt`:

```kotlin
package app.muplay.model.browse

/**
 * The slice of a child list one `onGetChildren` call asked for.
 *
 * Four lines, and every one of them exists because of a real value Android Auto sends.
 * `pageSize` arrives as `Int.MAX_VALUE` from a client that wants the whole list, and
 * `page * pageSize` is then negative for every page after the first — `subList` with a negative
 * index throws inside a `ListenableFuture`, where the exception never reaches a log a driver could
 * see and the symptom is an empty screen.
 */
object BrowsePaging {

  fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    // Long arithmetic, deliberately: this is the overflow, and it is not hypothetical.
    val from = page.toLong() * pageSize.toLong()
    if (from >= items.size) return emptyList()
    val to = minOf(from + pageSize.toLong(), items.size.toLong())
    return items.subList(from.toInt(), to.toInt())
  }
}
```

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseExtras.kt`:

```kotlin
package app.muplay.model.browse

/**
 * The extras a car head unit reads off a browse item, as plain values.
 *
 * **Literal strings, not `androidx.media3.session.MediaConstants` references.** These keys are a
 * contract with Android Auto — software this project does not own and no compiler checks against.
 * Media3 mirrors them under constant names that have moved between versions; pinning the strings
 * here means a rename upstream is a compile error somewhere else rather than a silent change to
 * what a car receives, and [BrowseExtrasTest] asserts each one as a literal.
 *
 * A `Map`, not a `Bundle`, so that *what goes in the extras* is decided in a pure-Kotlin module and
 * tested on the JVM. `BrowseItems.bundleOf` does the one Android-shaped step.
 */
object BrowseExtras {

  /** Told to the host once, on the root: this app sets content style hints at all. */
  const val CONTENT_STYLE_SUPPORTED: String = "android.media.browse.CONTENT_STYLE_SUPPORTED"

  /** How this item's **browsable** children should be laid out. */
  const val CONTENT_STYLE_BROWSABLE: String = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

  /** How this item's **playable** children should be laid out. */
  const val CONTENT_STYLE_PLAYABLE: String = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

  /** Whether this item has been played, and how far. Drawn as a progress pip in the car. */
  const val COMPLETION_STATUS: String =
    "androidx.media.MediaBrowserCompat.extras.COMPLETION_STATUS"

  /** How far, as a `Double` in `0.0..1.0`. Read only when the status is partially played. */
  const val COMPLETION_PERCENTAGE: String =
    "androidx.media.MediaBrowserCompat.extras.COMPLETION_PERCENTAGE"

  const val STYLE_LIST: Int = 1
  const val STYLE_GRID: Int = 2

  const val STATUS_NOT_PLAYED: Int = 0
  const val STATUS_PARTIALLY_PLAYED: Int = 1
  const val STATUS_FULLY_PLAYED: Int = 2

  /**
   * The extras for one node.
   *
   * Style hints only on a browsable node, because they describe that node's *children* and a leaf
   * has none. A percentage only on a partially-played item, because that is the only state in which
   * Android Auto reads it — sending `1.0` alongside `FULLY_PLAYED` is redundant, and sending `0.0`
   * alongside `NOT_PLAYED` draws an empty progress pip on every unheard book.
   */
  fun forNode(node: BrowseNode): Map<String, Any> = buildMap {
    if (node.isBrowsable) {
      put(CONTENT_STYLE_BROWSABLE, styleValue(node.childStyle))
      put(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
    }
    node.completion?.let { completion ->
      put(COMPLETION_STATUS, statusValue(completion.status))
      if (completion.status == BrowseCompletionStatus.PARTIALLY_PLAYED) {
        put(COMPLETION_PERCENTAGE, completion.fraction)
      }
    }
  }

  /** The extras on the root item — the host's default for everything below it. */
  fun forRoot(surface: BrowseSurface): Map<String, Any> = mapOf(
    CONTENT_STYLE_SUPPORTED to true,
    CONTENT_STYLE_BROWSABLE to styleValue(surface.browsableStyle),
    CONTENT_STYLE_PLAYABLE to STYLE_LIST,
  )

  private fun styleValue(style: BrowseStyle): Int = when (style) {
    BrowseStyle.LIST -> STYLE_LIST
    BrowseStyle.GRID -> STYLE_GRID
  }

  private fun statusValue(status: BrowseCompletionStatus): Int = when (status) {
    BrowseCompletionStatus.NOT_PLAYED -> STATUS_NOT_PLAYED
    BrowseCompletionStatus.PARTIALLY_PLAYED -> STATUS_PARTIALLY_PLAYED
    BrowseCompletionStatus.FULLY_PLAYED -> STATUS_FULLY_PLAYED
  }
}
```

Run: `./gradlew :core:model:test` — PASS.

- [ ] **Step 3: Implement `BrowseTreeRepository`**

`core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt`:

```kotlin
package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseTree
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Resolves a [BrowseId] into the data `BrowseTree` needs, and calls it.
 *
 * The only thing this class decides on its own is **scope**: music content is restricted to
 * libraries the user tagged `MUSIC`, and books to libraries tagged `AUDIOBOOKS`. Spec section 4 is
 * the reason that filter is applied here rather than trusted from upstream — Navidrome hardcodes
 * `child.Type = "music"` for every file and there is no server-side signal at all, so the library
 * role is the *only* mechanism, and a browse tree that forgot it would put chapter 14 of a novel in
 * the Albums tab.
 *
 * Returns `null` from [children] for an id that names no browsable node — a track, a shuffle row,
 * a one-file book, an album that is no longer in the mirror. The callback turns that into
 * `LibraryResult.ofError`, which is a different answer from an empty list and reads differently in
 * a car: "this is not a folder" rather than "this folder is empty".
 */
@Singleton
class BrowseTreeRepository @Inject constructor(
  private val libraryRepository: LibraryRepository,
  private val browseRepository: BrowseRepository,
  private val audiobookRepository: AudiobookRepository,
) {

  suspend fun children(id: BrowseId, surface: BrowseSurface): List<BrowseNode>? = when (id) {
    BrowseId.Root -> rootChildren(surface)

    BrowseId.Continue ->
      BrowseTree.continueNodes(audiobookRepository.bookshelf().first(), surface.continueLimit)

    BrowseId.Books -> BrowseTree.bookNodes(audiobookRepository.bookshelf().first())

    BrowseId.Albums -> BrowseTree.albumsNodes(librariesWithRole(LibraryRole.MUSIC), musicAlbums())

    BrowseId.Artists -> BrowseTree.artistNodes(musicArtists())

    BrowseId.Libraries -> BrowseTree.libraryNodes(libraries())

    is BrowseId.Library -> libraries()
      .firstOrNull { it.id == id.libraryId }
      ?.let { library -> BrowseTree.libraryChildren(library, albumsIn(library.id)) }

    is BrowseId.Book -> audiobookRepository.book(id.bookId)
      // A one-file book is not browsable -- BrowseTree.bookNodes already says so, and this is the
      // same rule on the other side of the wire, for a controller that guessed the id.
      ?.takeIf { it.fileCount > 1 }
      ?.let { BrowseTree.bookChildren(audiobookRepository.files(id.bookId)) }

    is BrowseId.Album -> browseRepository.album(id.albumId)
      ?.let { BrowseTree.albumChildren(browseRepository.songs(id.albumId)) }

    is BrowseId.Artist -> BrowseTree.artistChildren(browseRepository.albumsByArtist(id.artistId))

    // Playable leaves. Not an error and not empty: "not a folder".
    is BrowseId.Track, is BrowseId.Shuffle -> null
  }

  /**
   * One node, for `onGetItem`.
   *
   * Built by asking its **parent** for its children and picking it out, rather than by a second
   * construction path. Two paths that build the same node from the same data drift, and the drift
   * shows up as an item whose title in a list differs from its title on its own screen.
   */
  suspend fun node(id: BrowseId, surface: BrowseSurface): BrowseNode? = when (id) {
    BrowseId.Root -> null // The root has its own dedicated call; see MuPlayLibraryCallback.
    BrowseId.Continue, BrowseId.Books, BrowseId.Albums, BrowseId.Artists, BrowseId.Libraries ->
      rootChildren(surface).firstOrNull { it.id == id }
    is BrowseId.Library -> BrowseTree.libraryNodes(libraries()).firstOrNull { it.id == id }
    is BrowseId.Book -> audiobookRepository.book(id.bookId)?.let { BrowseTree.bookNodes(listOf(it)).single() }
    is BrowseId.Album -> browseRepository.album(id.albumId)?.let { BrowseTree.artistChildren(listOf(it)).single() }
    is BrowseId.Artist -> musicArtists().firstOrNull { it.id == id.artistId }
      ?.let { BrowseTree.artistNodes(listOf(it)).single() }
    is BrowseId.Shuffle -> libraries().firstOrNull { it.id == id.libraryId }
      ?.let { library -> BrowseTree.libraryChildren(library, emptyList()).firstOrNull { it.id == id } }
    is BrowseId.Track -> browseRepository.song(id.songId)?.let { BrowseTree.songNodes(listOf(it)).single() }
  }

  /** A `coverArt` id turned into a URL, or `null` when nothing is configured. */
  suspend fun artworkUri(artworkId: String?): String? =
    artworkId?.let { runCatching { browseRepository.coverArtUrl(it, ARTWORK_SIZE_PX) }.getOrNull() }

  private suspend fun rootChildren(surface: BrowseSurface): List<BrowseNode> {
    val libraries = libraries()
    return BrowseTree.root(
      surface = surface,
      hasAudiobooks = libraries.any { it.role == LibraryRole.AUDIOBOOKS },
      hasMusic = libraries.any { it.role == LibraryRole.MUSIC },
    )
  }

  private suspend fun libraries(): List<MusicLibrary> = libraryRepository.libraries.first()

  private suspend fun librariesWithRole(role: LibraryRole): List<MusicLibrary> =
    libraries().filter { it.role == role }

  private suspend fun musicAlbums(): List<Album> {
    val ids = librariesWithRole(LibraryRole.MUSIC).map(MusicLibrary::id).toSet()
    return browseRepository.albums.first().filter { it.libraryId in ids }
  }

  private suspend fun albumsIn(libraryId: Int): List<Album> =
    browseRepository.albums.first().filter { it.libraryId == libraryId }

  private suspend fun musicArtists(): List<Artist> {
    val ids = librariesWithRole(LibraryRole.MUSIC).map(MusicLibrary::id).toSet()
    return browseRepository.artists.first().filter { it.libraryId in ids }
  }

  private companion object {
    /** The same size Plan 3's queue asks for, so the two share one cover-art cache entry. */
    const val ARTWORK_SIZE_PX = 512
  }
}
```

> **Four things to reconcile against Plan 2 before this compiles**, and to record in the task report
> rather than to work around:
>
> 1. **`browseRepository.albums` / `.artists`.** Written above as `Flow`s and collected with
>    `.first()`. If they landed as `suspend fun albums(): List<Album>`, drop the `.first()`. **The
>    filter is the invariant** — never remove it on the grounds that "the repository is already
>    scoped", because whether it is scoped is Plan 2's decision and this rule is spec §4's.
> 2. **`browseRepository.song(songId)`.** Plan 2's Interfaces block lists `songs(albumId)` and
>    `album(albumId)` but **no single-song lookup**. If none exists, add one to `BrowseDao` and
>    `BrowseRepository` in this task — one `@Query("SELECT * FROM songs WHERE id = :id")` and one
>    delegating method — rather than fetching an album's whole song list to find one row.
> 3. **`ARTWORK_SIZE_PX`.** Plan 3 Task 4 declares `QueueRepository.ARTWORK_SIZE_PX = 512`. If it is
>    `public`, import it instead of redeclaring; a second copy of a cache-affecting number is how a
>    cover art cache ends up with two entries per album.
> 4. **`audiobookRepository.bookshelf()`** is Plan 4's `Flow<List<BookSummary>>` and is collected
>    with `.first()` here because a browse call is a request/response, not a subscription. Task 4
>    Step 8 covers `onSubscribe`/`notifyChildrenChanged` and explains why it is deliberately not
>    implemented.

- [ ] **Step 4: Write the failing device test for `BrowseItems`**

`core/media/src/androidTest/kotlin/app/muplay/media/browse/BrowseItemsTest.kt`:

```kotlin
package app.muplay.media.browse

import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.browse.BrowseCompletion
import app.muplay.model.browse.BrowseCompletionStatus
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseMediaType
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseStyle
import app.muplay.model.browse.BrowseSurface
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `BrowseNode` -> `MediaItem`, field by field.
 *
 * **On a device, and not because of Hilt.** `androidx.media3.common.MediaItem` reaches
 * `android.net.Uri` and `android.os.Bundle`, both unimplemented stubs in the JVM's `android.jar`;
 * a plain unit test of this mapping fails with *"not mocked"* and the only escape is Robolectric,
 * which spec sections 2 and 10 ban. Plan 3 Task 4 made the same call for the same reason.
 *
 * Every assertion below compares **two different nodes**, so that replacing any single assignment
 * with a constant fails here.
 */
@RunWith(AndroidJUnit4::class)
class BrowseItemsTest {

  @Test
  fun everyFieldOfANodeReachesTheMediaItem() {
    val items = listOf(BOOK_NODE to "http://host/art/1", ALBUM_NODE to "http://host/art/2")
      .map { (node, art) -> BrowseItems.of(node, art) }

    assertThat(items.map { it.mediaId }).containsExactly("muplay/book/al-7c3f", "muplay/album/al-9911")
    assertThat(items.map { it.mediaMetadata.title?.toString() })
      .containsExactly("Test Book", "Abbey Road")
    assertThat(items.map { it.mediaMetadata.subtitle?.toString() })
      .containsExactly("Test Author · 4 min left", "The Beatles")
    assertThat(items.map { it.mediaMetadata.artworkUri?.toString() })
      .containsExactly("http://host/art/1", "http://host/art/2")
    assertThat(items.map { it.mediaMetadata.isBrowsable }).containsExactly(true, true)
    assertThat(items.map { it.mediaMetadata.isPlayable }).containsExactly(true, true)
    assertThat(items.map { it.mediaMetadata.mediaType })
      .containsExactly(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, MediaMetadata.MEDIA_TYPE_ALBUM)
    assertThat(items.map { it.mediaMetadata.durationMs }).containsExactly(15_000L, 2_832_000L)
  }

  @Test
  fun aNodeWithNoArtworkProducesNoArtworkUriRatherThanAnEmptyOne() {
    // `Uri.parse("")` is a valid, empty Uri, and Coil renders it as a broken image rather than as
    // the placeholder a null would get.
    assertThat(BrowseItems.of(ALBUM_NODE, null).mediaMetadata.artworkUri).isNull()
  }

  @Test
  fun theExtrasBundleCarriesEveryKeyTheMapDid() {
    val extras = requireNamedExtras(BrowseItems.of(BOOK_NODE, null))

    assertThat(extras.keySet().sorted()).containsExactly(
      BrowseExtras.COMPLETION_PERCENTAGE,
      BrowseExtras.COMPLETION_STATUS,
      BrowseExtras.CONTENT_STYLE_BROWSABLE,
      BrowseExtras.CONTENT_STYLE_PLAYABLE,
    )
    assertThat(extras.getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_PARTIALLY_PLAYED)
    assertThat(extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE)).isEqualTo(0.2)
    assertThat(extras.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE))
      .isEqualTo(BrowseExtras.STYLE_LIST)
  }

  @Test
  fun everyMediaTypeMapsToADistinctMedia3Constant() {
    // Nine enum members, nine constants, asserted as an exact ordered list. A `when` with a wrong
    // arm, or an `else ->` that swallowed a member, fails here rather than showing a book as a
    // song in a car three screens away.
    assertThat(BrowseMediaType.entries.map(BrowseItems::mediaTypeOf)).containsExactly(
      MediaMetadata.MEDIA_TYPE_MIXED,
      MediaMetadata.MEDIA_TYPE_ALBUM,
      MediaMetadata.MEDIA_TYPE_ARTIST,
      MediaMetadata.MEDIA_TYPE_MUSIC,
      MediaMetadata.MEDIA_TYPE_AUDIO_BOOK,
      MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
      MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
      MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
      MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )
    // And they really are distinct -- a mapping that returned MEDIA_TYPE_MIXED for everything would
    // otherwise need nine separate assertions to catch.
    assertThat(BrowseMediaType.entries.map(BrowseItems::mediaTypeOf).toSet()).hasSize(9)
  }

  @Test
  fun theRootItemDiffersBySurface() {
    val car = requireNamedExtras(BrowseItems.root(BrowseSurface.CAR))
    val watch = requireNamedExtras(BrowseItems.root(BrowseSurface.WATCH))

    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaId).isEqualTo("muplay/root")
    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaMetadata.isBrowsable).isTrue
    assertThat(BrowseItems.root(BrowseSurface.CAR).mediaMetadata.isPlayable).isFalse
    assertThat(
      listOf(
        car.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE),
        watch.getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE),
      ),
    ).containsExactly(BrowseExtras.STYLE_GRID, BrowseExtras.STYLE_LIST)
    assertThat(car.getBoolean(BrowseExtras.CONTENT_STYLE_SUPPORTED)).isTrue
  }

  private fun requireNamedExtras(item: androidx.media3.common.MediaItem) =
    requireNotNull(item.mediaMetadata.extras) { "no extras on ${item.mediaId}" }

  private companion object {
    val BOOK_NODE = BrowseNode(
      id = BrowseId.Book("al-7c3f"),
      title = "Test Book",
      subtitle = "Test Author · 4 min left",
      isBrowsable = true,
      isPlayable = true,
      mediaType = BrowseMediaType.AUDIO_BOOK,
      artworkId = "cov-1",
      childStyle = BrowseStyle.LIST,
      completion = BrowseCompletion(BrowseCompletionStatus.PARTIALLY_PLAYED, 0.2),
      durationMs = 15_000L,
    )

    val ALBUM_NODE = BrowseNode(
      id = BrowseId.Album("al-9911"),
      title = "Abbey Road",
      subtitle = "The Beatles",
      isBrowsable = true,
      isPlayable = true,
      mediaType = BrowseMediaType.ALBUM,
      artworkId = "cov-2",
      childStyle = BrowseStyle.LIST,
      completion = null,
      durationMs = 2_832_000L,
    )
  }
}
```

- [ ] **Step 5: Implement `BrowseItems`**

`core/media/src/main/kotlin/app/muplay/media/browse/BrowseItems.kt`:

```kotlin
package app.muplay.media.browse

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseMediaType
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseSurface

/**
 * The one place a [BrowseNode] becomes a Media3 `MediaItem`.
 *
 * Everything *interesting* was decided before this file: which nodes, in which order, with which
 * titles and which extras. What is left here is a translation, and it is on a device only because
 * `MediaItem` cannot exist off one.
 *
 * No `Uri` is set on the item itself. A browse item is an **identity**, not a stream: when a
 * controller asks to play one, `MuPlayLibraryCallback.onAddMediaItems` (Task 5) resolves it into
 * the real, authenticated, `format=raw` items Plan 3 builds. Putting a stream URL on a browse item
 * would put an authenticated URL into Android Auto's persisted recents, where it would outlive the
 * credentials in it.
 */
object BrowseItems {

  fun of(node: BrowseNode, artworkUri: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle(node.title)
      .setSubtitle(node.subtitle)
      .setIsBrowsable(node.isBrowsable)
      .setIsPlayable(node.isPlayable)
      .setMediaType(mediaTypeOf(node.mediaType))
      // `takeIf` rather than a bare `?.toUri()`: `Uri.parse("")` is a *valid, empty* Uri, and an
      // image loader renders it as a broken image instead of falling back to a placeholder.
      .setArtworkUri(artworkUri?.takeIf(String::isNotBlank)?.toUri())
      .setDurationMs(node.durationMs)
      .setExtras(bundleOf(BrowseExtras.forNode(node)))
      .build()

    return MediaItem.Builder()
      .setMediaId(node.id.encode())
      .setMediaMetadata(metadata)
      .build()
  }

  /** The tree's root, whose extras are the host's defaults for everything below it. */
  fun root(surface: BrowseSurface): MediaItem {
    val metadata = MediaMetadata.Builder()
      .setTitle("MuPlay")
      .setIsBrowsable(true)
      .setIsPlayable(false)
      .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
      .setExtras(bundleOf(BrowseExtras.forRoot(surface)))
      .build()

    return MediaItem.Builder()
      .setMediaId(BrowseId.Root.encode())
      .setMediaMetadata(metadata)
      .build()
  }

  /**
   * This app's vocabulary, mapped to Media3's.
   *
   * An exhaustive `when` with no `else`: a new [BrowseMediaType] must fail to compile here rather
   * than fall into a default that shows a book as a song.
   */
  fun mediaTypeOf(type: BrowseMediaType): Int = when (type) {
    BrowseMediaType.MIXED -> MediaMetadata.MEDIA_TYPE_MIXED
    BrowseMediaType.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
    BrowseMediaType.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
    BrowseMediaType.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
    BrowseMediaType.AUDIO_BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
    BrowseMediaType.AUDIO_BOOK_CHAPTER -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
    BrowseMediaType.FOLDER_ALBUMS -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
    BrowseMediaType.FOLDER_ARTISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
    BrowseMediaType.FOLDER_MIXED -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
  }

  /**
   * A `Bundle` from the plain map `BrowseExtras` produced.
   *
   * `Int`, `Double` and `Boolean` are the only value kinds those maps contain, and an unexpected
   * one throws rather than being silently dropped — a missing extra in a car is invisible, and
   * "the progress pip stopped appearing" is not a bug anyone traces back to a `Bundle` put.
   */
  fun bundleOf(values: Map<String, Any>): Bundle = Bundle(values.size).apply {
    values.forEach { (key, value) ->
      when (value) {
        is Int -> putInt(key, value)
        is Double -> putDouble(key, value)
        is Boolean -> putBoolean(key, value)
        else -> error("unsupported extra $key of ${value::class.java.name}")
      }
    }
  }
}
```

> Three API shapes to confirm with `./gradlew :core:media:compileDebugAndroidTestKotlin` before
> assuming they compile, and to fix in place if any has moved without touching the surrounding
> decisions: `MediaMetadata.Builder.setSubtitle`, `.setDurationMs`, and the nine
> `MediaMetadata.MEDIA_TYPE_*` constants named above. `androidx.core.net.toUri` needs
> `androidx.core:core-ktx`, which `:core:media` already resolves through Media3; declare it only if
> the import does not resolve.

- [ ] **Step 6: Implement the callback and install it**

`core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt`:

```kotlin
package app.muplay.media.browse

import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.muplay.database.BrowseTreeRepository
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowsePaging
import app.muplay.model.browse.BrowseSurface
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The browse half of the media session — what Android Auto, Wear OS and the Assistant read.
 *
 * Plan 3 Task 5 left `MediaLibrarySession.Callback`'s browse methods at their *"not supported"*
 * defaults rather than faking an empty root, and said why: "not supported" was true, an empty root
 * would have been a claim. This class is that deferral being paid off, and it is the **only**
 * change to `MuPlaybackService` in Tasks 1–7.
 *
 * **The surface is resolved per request, never cached.** One session serves many controllers at
 * once — the phone UI, a car head unit and the system's media controls can all be connected
 * simultaneously — so a field holding "the surface" would give whichever connected second the other
 * one's tree.
 *
 * **`session.player` is read at the moment it is needed and never held.** Plan 6 (casting) swaps
 * the session's player when audio moves to a speaker, and a cached reference would leave the browse
 * tree driving a player nothing is listening to.
 */
@Singleton
class MuPlayLibraryCallback @Inject constructor(
  private val treeRepository: BrowseTreeRepository,
  private val surfaceResolver: SurfaceResolver,
) : MediaLibrarySession.Callback {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onGetLibraryRoot(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    // Synchronously, with no repository call at all: `onGetLibraryRoot` is the first thing a car
    // does on connect, and a root that waits on a database read is a car that shows a spinner
    // before it shows an app name.
    return immediate(LibraryResult.ofItem(BrowseItems.root(surface), params))
  }

  override fun onGetChildren(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(parentId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      val children: List<BrowseNode> = treeRepository.children(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      val items = BrowsePaging.page(children, page, pageSize).map { node ->
        BrowseItems.of(node, treeRepository.artworkUri(node.artworkId))
      }
      LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }
  }

  override fun onGetItem(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val surface = surfaceResolver.surfaceOf(browser)
    return future {
      val id = BrowseId.decode(mediaId)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      if (id == BrowseId.Root) {
        return@future LibraryResult.ofItem(BrowseItems.root(surface), null)
      }
      val node = treeRepository.node(id, surface)
        ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      LibraryResult.ofItem(BrowseItems.of(node, treeRepository.artworkUri(node.artworkId)), null)
    }
  }

  /** Called from `MuPlaybackService.onDestroy`. */
  fun release() {
    scope.cancel()
  }

  private fun <T> immediate(value: T): ListenableFuture<T> =
    SettableFuture.create<T>().apply { set(value) }

  /**
   * Runs [block] off the session thread and answers Media3 with its result.
   *
   * A failure becomes `ERROR_UNKNOWN` rather than an exception on the future, deliberately: an
   * exception here is logged by Media3 and reaches a driver as an empty screen with no
   * explanation, whereas an error result is rendered as an error. `BrowseId.Track`'s own
   * `IllegalArgumentException` (Task 1) is the one this actually catches, and Task 1 promised it
   * would be loud rather than silently wrong — a car saying "something went wrong" is loud.
   */
  private fun <T> future(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
    val future = SettableFuture.create<LibraryResult<T>>()
    scope.launch {
      val result = runCatching { block() }
        .getOrElse { throwable ->
          android.util.Log.e(TAG, "browse request failed", throwable)
          LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }
      future.set(result)
    }
    return future
  }

  private companion object {
    const val TAG = "MuPlayLibraryCallback"
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` — add:

```kotlin
  @Binds
  @Singleton
  abstract fun bindSurfaceResolver(impl: DefaultSurfaceResolver): SurfaceResolver
```

> `MediaModule` is an `object` in Plan 3. `@Binds` needs an `abstract class` or an interface, so
> either add a nested `@Module @InstallIn(SingletonComponent::class) interface Bindings` inside it
> or provide the resolver with `@Provides fun provideSurfaceResolver(impl: DefaultSurfaceResolver): SurfaceResolver = impl`.
> Either is fine; do not convert Plan 3's module wholesale.

`core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` — three edits, and no more:

```kotlin
  @Inject lateinit var libraryCallback: MuPlayLibraryCallback
```

```kotlin
    session = MediaLibrarySession.Builder(this, player, libraryCallback)
```

```kotlin
  override fun onDestroy() {
    libraryCallback.release()
    session?.run {
      player.release()
      release()
    }
    session = null
    super.onDestroy()
  }
```

and **delete** Plan 3's `private class LibraryCallback : MediaLibrarySession.Callback` along with
the paragraph of its documentation that says the browse tree is deferred — that deferral is over,
and a comment that outlives its subject is how a reader learns to distrust the comments.

> Two API shapes to confirm with `./gradlew :core:media:compileDebugKotlin`:
> `LibraryResult.ofError(...)`'s parameter (an `@SessionError.Code Int` in the version this was
> written against; some Media3 versions take a `SessionError` instance), and whether
> `SessionError.ERROR_BAD_VALUE` / `ERROR_UNKNOWN` are spelled that way. If either has moved, use
> the real one — the *decision* is which of the two answers each failure gets, and that does not
> change.

- [ ] **Step 7: Write the Tier 2 browse journey**

`app/build.gradle.kts` — add, if Plan 3 has not already:

```kotlin
  androidTestImplementation(project(":core:media"))
  androidTestImplementation(project(":core:database"))
  androidTestImplementation(project(":core:model"))
  androidTestImplementation(project(":core:testing"))
  androidTestImplementation(libs.assertj)
```

`app/src/androidTest/kotlin/app/muplay/BrowseTreeJourneyTest.kt`:

```kotlin
package app.muplay

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import app.muplay.database.CredentialStore
import app.muplay.media.MuPlaybackService
import app.muplay.media.browse.BrowseItems
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseSurfaces
import app.muplay.testing.BookFixtures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browse tree, read by a **real `MediaBrowser`** from the **real service**, over real Media3
 * IPC, against the **real Navidrome container**.
 *
 * This is the strongest rung available for this feature and it needs no car: Android Auto is
 * projection, so the tree, the session and the callback all run on the phone. What a car adds is
 * Google's rendering, which is not this project's code.
 *
 * **The car surface is reached by a connection hint** (`BrowseSurfaces.HINT_CAR`), honoured only
 * from this app's own package. That is the mechanism `:wear` uses in production and it is what
 * makes the automotive branch exercisable here rather than only on a head unit.
 *
 * In `:app` rather than `:core:media` for Plan 3 Task 5's reason: `MuPlaybackService` is
 * `@AndroidEntryPoint` and needs a `@HiltAndroidApp` host, which a library module's self-
 * instrumenting APK does not have. Coverage still lands on `:core:media` — `Jacoco.kt` globs every
 * project's `.ec`.
 */
@RunWith(AndroidJUnit4::class)
class BrowseTreeJourneyTest {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface BrowseTestEntryPoint {
    fun credentialStore(): CredentialStore
    fun libraryRepository(): LibraryRepository
    fun syncEngine(): SyncEngine
  }

  private lateinit var context: Context
  private val browsers = mutableListOf<MediaBrowser>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val graph = EntryPointAccessors.fromApplication(context, BrowseTestEntryPoint::class.java)

    runBlocking {
      // Seeded here rather than inherited from whichever journey ran first: a test that depends on
      // another test having run is a test that fails alone.
      graph.credentialStore().save(SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"))
      graph.libraryRepository().refreshFromServer()
      graph.libraryRepository().setRole(MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
      graph.libraryRepository().setRole(AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)
      // The tree reads the mirror, not the server, so the mirror has to exist. This is Plan 2's
      // sync engine doing its normal job, not a test-only path.
      graph.syncEngine().syncIfStale()
    }
  }

  @After
  fun tearDown() {
    onMain { browsers.forEach(MediaBrowser::release) }
    browsers.clear()
  }

  @Test
  fun theRootIsBrowsableAndCarriesTheContentStyleTheSurfaceAsked_for() {
    val car = awaitItem(browser(BrowseSurfaces.HINT_CAR)) { it.getLibraryRoot(null) }
    val watch = awaitItem(browser(BrowseSurfaces.HINT_WATCH)) { it.getLibraryRoot(null) }

    assertThat(car.mediaId).isEqualTo("muplay/root")
    assertThat(car.mediaMetadata.isBrowsable).isTrue
    assertThat(
      listOf(car, watch).map {
        requireNotNull(it.mediaMetadata.extras).getInt(BrowseExtras.CONTENT_STYLE_BROWSABLE)
      },
    ).containsExactly(BrowseExtras.STYLE_GRID, BrowseExtras.STYLE_LIST)
  }

  @Test
  fun eachSurfaceReceivesItsOwnRootChildrenInOrder() {
    // The assertion this whole plan turns on. Three surfaces, three exact ordered lists, read over
    // real IPC. `containsExactly`, never `contains` -- the order is what a driver reads.
    assertThat(childIds(BrowseSurfaces.HINT_CAR, "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists",
    )
    assertThat(childIds(BrowseSurfaces.HINT_WATCH, "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums",
    )
    assertThat(childIds(hint = null, parentId = "muplay/root")).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists", "muplay/libraries",
    )
  }

  @Test
  fun theBooksTabListsEverySeededBookAlphabetically() {
    // The oracle is `ci/probe-chapters.sh`'s ffprobe-derived table (Plan 4 Task 1), not this app.
    val expected = BookFixtures.ALL_BOOKS.map { it.albumName }.sorted()

    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/books")).isEqualTo(expected)
    assertThat(expected).containsExactly(
      "Multi Part Book", "Second Book", "Tail Book", "Test Book",
    )
  }

  @Test
  fun aBookCarriesTheCompletionExtrasACarDrawsItsProgressPipFrom() {
    val books = children(BrowseSurfaces.HINT_CAR, "muplay/books")
    val statuses = books.map {
      requireNotNull(it.mediaMetadata.extras).getInt(BrowseExtras.COMPLETION_STATUS)
    }

    // Nothing has been played yet in this journey, so every book is NOT_PLAYED and none carries a
    // percentage. Both halves asserted: "the key is present" and "the other key is absent" are
    // different claims, and the second is the one that proves the branch in BrowseExtras.forNode.
    assertThat(statuses).containsOnly(BrowseExtras.STATUS_NOT_PLAYED)
    assertThat(statuses).isNotEmpty
    assertThat(
      books.map { requireNotNull(it.mediaMetadata.extras).containsKey(BrowseExtras.COMPLETION_PERCENTAGE) },
    ).containsOnly(false)
  }

  @Test
  fun theAlbumsTabOffersShuffleFirstAndThenTheAlbums() {
    val ids = childIds(BrowseSurfaces.HINT_CAR, "muplay/albums")

    // One Music library in the container, so exactly one shuffle row, and it is first.
    assertThat(ids.first()).isEqualTo("muplay/shuffle/$MUSIC_LIBRARY_ID")
    assertThat(childTitles(BrowseSurfaces.HINT_CAR, "muplay/albums").first())
      .isEqualTo("Shuffle Music")
    assertThat(ids.drop(1)).isNotEmpty
  }

  @Test
  fun anAudiobookLibraryIsNotOfferedAShuffleRow() {
    // Spec section 1, over real IPC: shuffle must never be able to pull a chapter into a music
    // session, and on a surface with no UI that is the absence of a row.
    val musicIds = childIds(hint = null, parentId = "muplay/library/$MUSIC_LIBRARY_ID")
    val bookIds = childIds(hint = null, parentId = "muplay/library/$AUDIOBOOK_LIBRARY_ID")

    assertThat(musicIds.first()).isEqualTo("muplay/shuffle/$MUSIC_LIBRARY_ID")
    assertThat(bookIds.filter { it.startsWith("muplay/shuffle/") }).isEmpty()
    assertThat(bookIds).isNotEmpty
  }

  @Test
  fun anAlbumsChildrenAreItsTracksInDiscAndTrackOrder() {
    val albumId = children(hint = null, parentId = "muplay/albums")
      .first { it.mediaId.startsWith("muplay/album/") }
      .mediaId

    assertThat(childTitles(hint = null, parentId = albumId))
      .containsExactly("Track 1", "Track 2", "Track 3")
  }

  @Test
  fun getItemAnswersForAKnownNodeAndRefusesAnUnknownOne() {
    val books = awaitResult(browser(null)) { it.getItem("muplay/books") }
    val nonsense = awaitResult(browser(null)) { it.getItem("muplay/nosuchkind/1") }
    val leaf = awaitResult(browser(null)) { it.getItem("muplay/shuffle/$AUDIOBOOK_LIBRARY_ID") }

    assertThat(books.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(books.value?.mediaMetadata?.title?.toString()).isEqualTo("Books")
    // Two different failures, so "refuses" is not one constant: an id that names no kind, and an
    // id that names a real kind but no existing row.
    assertThat(nonsense.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(leaf.resultCode).isNotEqualTo(LibraryResult.RESULT_SUCCESS)
  }

  @Test
  fun childrenArePagedAndThePagesTileTheList() {
    val all = childIds(hint = null, parentId = "muplay/books")
    val firstPage = childIds(hint = null, parentId = "muplay/books", page = 0, pageSize = 2)
    val secondPage = childIds(hint = null, parentId = "muplay/books", page = 1, pageSize = 2)
    val pastEnd = childIds(hint = null, parentId = "muplay/books", page = 9, pageSize = 2)

    assertThat(firstPage).isEqualTo(all.take(2))
    assertThat(secondPage).isEqualTo(all.drop(2).take(2))
    assertThat(pastEnd).isEmpty()
    assertThat(firstPage).isNotEqualTo(secondPage)
  }

  // --- plumbing --------------------------------------------------------------------------------

  private fun browser(hint: String?): MediaBrowser {
    val token = SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    val built = onMain {
      MediaBrowser.Builder(context, token).setConnectionHints(hints).buildAsync()
    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    browsers += built
    return built
  }

  private fun children(hint: String?, parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE) =
    awaitResult(browser(hint)) { it.getChildren(parentId, page, pageSize, null) }
      .value.orEmpty()

  private fun childIds(hint: String?, parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE) =
    children(hint, parentId, page, pageSize).map(MediaItem::mediaId)

  private fun childTitles(hint: String?, parentId: String) =
    children(hint, parentId).map { it.mediaMetadata.title?.toString() }

  private fun <T> awaitResult(
    browser: MediaBrowser,
    call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>,
  ): LibraryResult<T> =
    // Built and called on the main thread (Media3 controllers are thread-confined), awaited on the
    // test thread.
    onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitItem(browser: MediaBrowser, call: (MediaBrowser) -> ListenableFuture<LibraryResult<MediaItem>>) =
    requireNotNull(awaitResult(browser, call).value) { "no item returned" }

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

  private companion object {
    /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` — ci/prepare-emulator.sh. */
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val TIMEOUT_SECONDS = 30L
  }
}
```

> `BrowseItems` is imported above only so the file reads as one story; if the import is unused after
> you finish the file, delete it rather than leaving it. `SyncEngine.syncIfStale()` is Plan 2 Task
> 6's; if it landed under a different name, use the real one and say so in the task report.

- [ ] **Step 8: Run the journey, and record what `onSubscribe` deliberately does not do**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :core:media:connectedDebugAndroidTest --tests '*BrowseItemsTest*'
./gradlew :app:connectedDebugAndroidTest --tests '*BrowseTreeJourneyTest*'
```

Expected: PASS.

**`onSubscribe` / `notifyChildrenChanged` are not implemented, on purpose.** A subscribing browser
is told when a folder's contents change; ours change when Plan 2's sync engine reconciles, which is
a background event a car cannot see happening. Implementing it would mean holding a subscription per
controller and pushing invalidations from a repository into a session — a live-update path with its
own concurrency, whose only observable effect is a list refreshing while parked. The cost is that a
car re-entering a folder sees the new content and one already looking at it does not. Record it in
the task report; do not leave it as an unexplained gap.

- [ ] **Step 9: Prove the journey can fail**

1. In `MuPlayLibraryCallback.onGetChildren`, replace `surfaceResolver.surfaceOf(browser)` with
   `BrowseSurface.PHONE`. Expect `eachSurfaceReceivesItsOwnRootChildrenInOrder` to fail on the car
   and watch lists. **This is the branch this plan exists to make testable; watch it fail.**
2. In `BrowseTreeRepository.musicAlbums`, delete the `it.libraryId in ids` filter. Expect
   `theAlbumsTabOffersShuffleFirstAndThenTheAlbums` to keep passing (it does not assert absence) and
   `anAlbumsChildrenAreItsTracksInDiscAndTrackOrder` to fail once a book album is first in the list.
   **If neither fails, add an assertion that the Albums tab contains no book album** — the scoping
   rule is spec §1's and must not be provable only by inspection.
3. In `BrowseItems.of`, hardcode `.setTitle("MuPlay")`. Expect `theBooksTabListsEverySeededBook
   Alphabetically` and `getItemAnswersForAKnownNodeAndRefusesAnUnknownOne` to fail.
4. In `BrowseExtras.forNode`, always put `COMPLETION_PERCENTAGE`. Expect
   `aBookCarriesTheCompletionExtrasACarDrawsItsProgressPipFrom` to fail on the absence assertion.
5. In `BrowsePaging.page`, return `items` regardless of the page. Expect
   `childrenArePagedAndThePagesTileTheList` to fail on `pastEnd`.
6. In `BrowseTreeRepository.children`, return `emptyList()` instead of `null` for a `Shuffle` id.
   Expect `getItemAnswersForAKnownNodeAndRefusesAnUnknownOne`'s third assertion to fail — the
   difference between "not a folder" and "an empty folder", which is invisible without this test.

- [ ] **Step 10: Wire the workflow, measure the floors, commit**

`.github/workflows/e2e.yml` — the emulator step's `script:` currently runs
`:core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest`. Add
`:core:media:connectedDebugAndroidTest`. Keep them in one `./gradlew` invocation so a single logcat
dump covers all of them.

`build.gradle.kts` — measure `:core:model`, `:core:database` and `:core:media` from a merged
report and extend the floor table. `BrowseItems`, `MuPlayLibraryCallback` and
`DefaultSurfaceResolver` are **instrumented-only**: their floors need
`requiresInstrumentedData = true`, or Tier 1 will fail on data it cannot produce. Confirm
`warnUngatedClasses` names nothing new and that no `COVERAGE:` warning is left standing.

```bash
./gradlew :core:model:test :core:database:test :app:verifyDebugManifest
git add core/model core/database core/media app build.gradle.kts .github/workflows/e2e.yml
git commit -m "feat(media): serve the browse tree to Auto, Wear and the Assistant"
```

---

## Task 5: Playing from the tree — `onAddMediaItems`/`onSetMediaItems`, and resume at the stored position

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseSelection.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt` (add `expand`)
- Test: `core/database/src/test/kotlin/app/muplay/database/BrowseExpansionTest.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt`
- Test: `app/src/androidTest/kotlin/app/muplay/CarResumeJourneyTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - `BrowseId`, `BrowseTreeRepository` — Tasks 1, 4.
  - **`QueueRepository.mediaItems(queue: PlaybackQueue): List<MediaItem>`, `PlaybackQueue.of(songs, startIndex)`** — Plan 3 Tasks 4, 6.
  - **`MuPlayer`, `ResumePolicy.resolve(mediaIds, requestedIndex): ResumeTarget`** — Plan 3 Task 8.
    This task calls **neither**; it only relies on `MuPlayer` being the session's player.
  - **`AudiobookRepository.resumePoint(bookId): ResumePoint?`, `.files(bookId)`, `.book(bookId)`** —
    Plan 4 Task 4. **`AudiobookResumePolicy`, `AudiobookSnapshot`** — Plan 4 Task 6 (consumed only
    by being installed; this task never calls them).
  - **`ShuffleRepository.shuffle(libraryId, requestedSize): ShuffleResult`, `DEFAULT_SHUFFLE_SIZE`** —
    Plan 2 Task 7. **`ShuffleResult`'s shape is not fixed**; see the note at its call site.
  - `BrowseRepository.songs(albumId)`, `.song(songId)` — Plan 2 Task 5 plus Task 4's addition.
  - `MediaProgressDao.upsert/find` — Plan 2 Task 1, committed.
- Produces:
  - `data class BrowseSelection(val songs: List<Song>, val startIndex: Int)` with
    `companion object { val EMPTY: BrowseSelection }`
  - `BrowseTreeRepository.expand(id: BrowseId): BrowseSelection?` and
    `BrowseTreeRepository.Companion.startIndexOf(songs: List<Song>, mediaId: String): Int`
  - `MuPlayLibraryCallback.onAddMediaItems` and `.onSetMediaItems`

### The one rule that makes a tapped row do the right thing everywhere

A controller that plays a browse row sends **one `MediaItem` carrying only a `mediaId`**. Android
Auto never sends a URI, and Media3 strips `localConfiguration` when an item crosses a process
boundary, so the session has to rebuild the playable item from the id. That is what
`onAddMediaItems` is for.

The expansion rule is a single sentence: **a playable id becomes the queue it belongs to, positioned
at itself.**

| Tapped id | Queue | Start index |
|---|---|---|
| `muplay/album/<id>` | that album's tracks | 0 |
| `muplay/shuffle/<libraryId>` | Plan 2's library-scoped shuffle | 0 |
| a bare track id **in a music album** | that album's tracks | that track's position |
| a bare track id **that is a book's file** | that book's files | that file's position |
| `muplay/book/<id>` | that book's files | the file the listener was last in |
| anything browsable-only | nothing — `ERROR_BAD_VALUE` | — |

The third and fourth rows are the same code: a track is expanded through `Song.albumId`, and a book
*is* an album in a library the user tagged Audiobooks. One rule, and the audiobook case falls out of
it rather than being special-cased — which matters because spec §4 says the server will never tell
this app which one it is looking at.

### Where the position comes from, and why this task does not compute one

**It does not compute one.** Every returned `MediaItemsWithStartPosition` carries
`C.TIME_UNSET` as its start position, and Plan 3's `MuPlayer` — the session's player — discards it
and asks `ResumePolicy` (Plan 4's `AudiobookResumePolicy`) for the real one. Plan 3 Task 8 built
that seam so that *"no code path can set a wrong position"*; a browse callback that called `seekTo`,
or that read a `media_progress` row and passed a position, would be a code path that can.

The index is a different matter, and it is **this task's** to choose, because Plan 4's own seam
correction says so: `resolve(mediaIds, requestedIndex)` cannot tell *"play this book"* from *"play
chapter 1 from the top"*, so **the caller picks the index and the policy picks the position**.
Tapping `muplay/book/<id>` means "carry on", so the index is the file the listener was in; tapping a
specific file means that file. Both are decided here, from `AudiobookRepository.resumePoint`, and
neither touches a position.

### A resume test that asserts a request was made proves nothing

Named in this plan's own defect list. `setMediaItem` having returned, `onSetMediaItems` having been
invoked, the right `startIndex` having been chosen — every one of those is satisfied by a player
that ignores the answer, by a URL that 404s into a swallowed error, and by a decoder that never
produced a sample. `CarResumeJourneyTest` asserts:

1. the position playback **reached**, within a small window of the stored 11 500 ms;
2. that it is **neither 0 nor 9 000** — 9 000 ms is where `Second Book`'s third chapter starts, and
   a stored position that sat on a chapter boundary would make "resumed exactly" and "resumed at the
   chapter start" the same observation. Plan 4 makes the same point and picks its fixtures the same
   way;
3. that the position then **strictly increases** across two reads separated by real time, which is
   the weakest observation a player rendering silence cannot produce.

And it asserts the contrast: a **music** track played from the tree starts at **0** even though a
`media_progress` row exists for it, which is spec §3's *"music restarts from 0 — progress is still
recorded, just not honoured on prepare"*. One policy, two observations; without the second, a policy
that resumed everything would pass.

- [ ] **Step 1: Write the failing expansion test**

`core/database/src/test/kotlin/app/muplay/database/BrowseExpansionTest.kt`:

```kotlin
package app.muplay.database

import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The pure half of expansion: given an album's songs and the id that was tapped, which index does
 * playback start at.
 *
 * The rest of `expand` is repository plumbing and is proven end-to-end by `CarResumeJourneyTest`;
 * this is the arithmetic, and arithmetic belongs in the fast tier.
 */
class BrowseExpansionTest {

  @Test
  fun `the start index is the tapped song's own position`() {
    // Three positions, three answers. A `startIndexOf` that returned 0 would pass a one-case test
    // and fail here on two of three.
    assertThat(SONGS.map { BrowseTreeRepository.startIndexOf(SONGS, it.id) })
      .containsExactly(0, 1, 2)
  }

  @Test
  fun `an id that is not in the list starts at the beginning rather than at minus one`() {
    // `indexOf` returns -1, and `PlaybackQueue.of(songs, -1)` is an IllegalArgumentException inside
    // a ListenableFuture -- which reaches a car as an unexplained silence.
    assertThat(BrowseTreeRepository.startIndexOf(SONGS, "not-here")).isEqualTo(0)
    assertThat(BrowseTreeRepository.startIndexOf(emptyList(), "tr-1")).isEqualTo(0)
  }

  private companion object {
    fun song(id: String, track: Int) = Song(
      id = id,
      libraryId = 1,
      title = "Track $track",
      albumId = "al-a",
      albumName = "Test Album",
      artistId = "ar-1",
      artistName = "Test Artist",
      trackNumber = track,
      discNumber = 1,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = "cov-a",
    )

    val SONGS = listOf(song("tr-1", 1), song("tr-2", 2), song("tr-3", 3))
  }
}
```

- [ ] **Step 2: Run it to verify it fails, then implement the expansion**

Run: `./gradlew :core:database:test --tests '*BrowseExpansionTest*'`
Expected: FAIL — `Unresolved reference: startIndexOf`.

`core/model/src/main/kotlin/app/muplay/model/browse/BrowseSelection.kt`:

```kotlin
package app.muplay.model.browse

import app.muplay.model.Song

/**
 * What a playable browse id expands to: a queue, and where in it to start.
 *
 * **No position.** Spec section 3 puts the position under `MuPlayer`'s `ForwardingPlayer` seam so
 * that no caller can set a wrong one, and this type is a caller. The index is here because Plan 4's
 * seam correction gives the index to the caller — *"play this book"* and *"play chapter 1 from the
 * top"* are indistinguishable to a policy and obvious to whoever was tapped on.
 */
data class BrowseSelection(
  val songs: List<Song>,
  val startIndex: Int,
) {
  companion object {
    val EMPTY: BrowseSelection = BrowseSelection(emptyList(), 0)
  }
}
```

Append to `core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt`:

```kotlin
  /**
   * The queue a playable browse id stands for, or `null` if the id is not playable.
   *
   * One rule: **a playable id becomes the queue it belongs to, positioned at itself.** A track
   * expands through its own album, which makes "play this track" mean "play this album from here"
   * for music and "play this book from this file" for a book — the same code, because a book *is*
   * an album in a library the user tagged Audiobooks and spec section 4 says the server will never
   * distinguish them.
   */
  suspend fun expand(id: BrowseId): BrowseSelection? = when (id) {
    is BrowseId.Album -> browseRepository.songs(id.albumId)
      .takeIf { it.isNotEmpty() }
      ?.let { BrowseSelection(it, startIndex = 0) }

    is BrowseId.Book -> {
      val files = audiobookRepository.files(id.bookId)
      files.takeIf { it.isNotEmpty() }?.let { songs ->
        // The caller picks the index; the policy picks the position. `resumePoint` is Plan 4's, and
        // it answers "which file was I in", never "at what second".
        val resumeAt = audiobookRepository.resumePoint(id.bookId)
        BrowseSelection(songs, startIndex = resumeAt?.let { startIndexOf(songs, it.mediaId) } ?: 0)
      }
    }

    is BrowseId.Track -> {
      val song = browseRepository.song(id.songId)
      when {
        song == null -> null
        // A single loose track with no album is still playable; it is just a queue of one.
        song.albumId == null -> BrowseSelection(listOf(song), startIndex = 0)
        else -> {
          val siblings = browseRepository.songs(song.albumId)
          if (siblings.isEmpty()) {
            BrowseSelection(listOf(song), startIndex = 0)
          } else {
            BrowseSelection(siblings, startIndexOf(siblings, song.id))
          }
        }
      }
    }

    is BrowseId.Shuffle -> shuffleSongs(id.libraryId)
      ?.takeIf { it.isNotEmpty() }
      ?.let { BrowseSelection(it, startIndex = 0) }

    // Browsable-only ids. The callback turns null into ERROR_BAD_VALUE, which reads as "this is not
    // something to play" rather than as an empty queue.
    BrowseId.Root, BrowseId.Continue, BrowseId.Books, BrowseId.Albums, BrowseId.Artists,
    BrowseId.Libraries, is BrowseId.Library, is BrowseId.Artist,
    -> null
  }

  /**
   * The songs Plan 2's library-scoped shuffle produced, or `null` if it produced none.
   *
   * **`ShuffleResult`'s shape is Plan 2 Task 7's and was not fixed when this plan was written.**
   * Read the real sealed interface and write an exhaustive `when` over it here — a success arm that
   * yields its songs, and every failure arm yielding `null`. Do **not** add an `else`: a new failure
   * kind must fail to compile here rather than be silently treated as "no music".
   */
  private suspend fun shuffleSongs(libraryId: Int): List<Song>? =
    shuffleRepository.shuffle(libraryId, ShuffleRepository.DEFAULT_SHUFFLE_SIZE).songsOrNull()

  companion object {
    /**
     * Where in [songs] the item with [mediaId] sits, or `0` if it is not there at all.
     *
     * Never `-1`: `indexOf` returns that for a miss, and `PlaybackQueue.of(songs, -1)` throws
     * inside a `ListenableFuture`, where the exception reaches a car as unexplained silence. A
     * missing id means the mirror moved under a stale browse row, and starting at the beginning is
     * the right answer to that.
     */
    fun startIndexOf(songs: List<Song>, mediaId: String): Int =
      songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
  }
```

`BrowseTreeRepository`'s constructor gains `private val shuffleRepository: ShuffleRepository`.

> `songsOrNull()` above is a placeholder for the exhaustive `when` described in that method's own
> documentation — **write the `when`, do not add an extension called `songsOrNull`.** It is written
> as one call only so the surrounding code reads; replace it with the real `when` over Plan 2's
> `ShuffleResult` in this step and record the real shape in the task report.

Run: `./gradlew :core:database:test --tests '*BrowseExpansionTest*'` — PASS.

- [ ] **Step 3: Implement the two session callbacks**

Append to `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt`, and add
`private val queueRepository: QueueRepository` to its constructor:

```kotlin
  /**
   * Turns whatever a controller asked to play into items this player can actually stream.
   *
   * Two kinds of caller, and the difference is one field:
   *
   * - **This app's own UI** hands over items Plan 3 already built, complete with an authenticated
   *   `format=raw` URL in their `localConfiguration`. Those pass through **unchanged** — rebuilding
   *   them would discard the very fields the caller computed.
   * - **A car, a watch, the Assistant or the system's resumption row** hands over a bare `mediaId`
   *   and nothing else, because Media3 strips `localConfiguration` when an item crosses a process
   *   boundary and Android Auto never had one to begin with. Those are expanded.
   */
  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    val future = SettableFuture.create<List<MediaItem>>()
    scope.launch {
      val resolved = runCatching { resolve(mediaItems) }
        .getOrElse {
          android.util.Log.e(TAG, "could not resolve requested items", it)
          emptyList()
        }
      future.set(resolved)
    }
    return future
  }

  /**
   * The same resolution, plus the **index** — which is this plan's to choose and the resume
   * policy's not to.
   *
   * The returned start position is always `C.TIME_UNSET`. Plan 3's `MuPlayer` discards it and asks
   * `ResumePolicy` (Plan 4's `AudiobookResumePolicy`) for the real one, which is the guarantee that
   * *no code path can set a wrong position*. This callback is a code path; it does not get one.
   */
  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
    scope.launch {
      val result = runCatching {
        val single = mediaItems.singleOrNull()
        val selection = single
          ?.takeIf { it.localConfiguration == null }
          ?.let { BrowseId.decode(it.mediaId) }
          ?.let { treeRepository.expand(it) }

        if (selection == null) {
          // Not a browse row: the caller already knows its own queue and its own index. Resolve the
          // items and keep the index it asked for, unchanged.
          MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, C.TIME_UNSET)
        } else {
          val items = queueRepository.mediaItems(
            PlaybackQueue.of(selection.songs, selection.startIndex),
          )
          MediaSession.MediaItemsWithStartPosition(items, selection.startIndex, C.TIME_UNSET)
        }
      }.getOrElse {
        android.util.Log.e(TAG, "could not build a queue for the requested items", it)
        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
      }
      future.set(result)
    }
    return future
  }

  private suspend fun resolve(mediaItems: List<MediaItem>): List<MediaItem> =
    mediaItems.flatMap { item ->
      // The one field that separates the two kinds of caller. Present means "already playable".
      if (item.localConfiguration != null) {
        listOf(item)
      } else {
        val selection = BrowseId.decode(item.mediaId)?.let { treeRepository.expand(it) }
        if (selection == null) {
          emptyList()
        } else {
          queueRepository.mediaItems(PlaybackQueue.of(selection.songs, selection.startIndex))
        }
      }
    }
```

> Confirm three names with `./gradlew :core:media:compileDebugKotlin`:
> `MediaSession.MediaItemsWithStartPosition`'s package and constructor, `MediaItem.localConfiguration`,
> and `androidx.media3.common.C.TIME_UNSET`. All three are long-standing Media3 API; if one has
> moved, use the real one and leave the decisions alone.

- [ ] **Step 4: Write the failing Tier 2 resume journey**

`app/src/androidTest/kotlin/app/muplay/CarResumeJourneyTest.kt`:

```kotlin
package app.muplay

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.MuPlaybackService
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The original complaint, asked from a car.
 *
 * A book is left mid-chapter; a car connects, browses to Continue, and taps it; audio starts at
 * **that second** and goes on from there. Every assertion below is about something a player
 * rendering silence could not produce: a position that is where it was left, that is not zero, that
 * is not a chapter boundary, and that then moves.
 */
@RunWith(AndroidJUnit4::class)
class CarResumeJourneyTest {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface CarResumeEntryPoint {
    fun credentialStore(): CredentialStore
    fun libraryRepository(): LibraryRepository
    fun syncEngine(): SyncEngine
    fun mediaProgressDao(): MediaProgressDao
    fun clock(): Clock
  }

  private lateinit var context: Context
  private lateinit var graph: CarResumeEntryPoint
  private lateinit var browser: MediaBrowser

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = EntryPointAccessors.fromApplication(context, CarResumeEntryPoint::class.java)

    runBlocking {
      graph.credentialStore().save(SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"))
      graph.libraryRepository().refreshFromServer()
      graph.libraryRepository().setRole(MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
      graph.libraryRepository().setRole(AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)
      graph.syncEngine().syncIfStale()
    }

    browser = connect(BrowseSurfaces.HINT_CAR)
  }

  @After
  fun tearDown() {
    onMain {
      browser.stop()
      browser.clearMediaItems()
      browser.release()
    }
  }

  @Test
  fun aBookTappedInTheCarStartsAtTheSecondItWasLeftAtAndCarriesOn() {
    val book = bookItem(SECOND_BOOK_TITLE)
    val fileId = fileIdOf(book)

    // What a previous listening session would have left. `lastPlayedAt = now`, so Plan 4's smart
    // rewind is in its zero band (under 15 s away) and the stored position is the expected one --
    // otherwise this test would be asserting the rewind table, which is Plan 4's to assert.
    runBlocking {
      graph.mediaProgressDao().upsert(
        MediaProgressEntity(
          mediaId = fileId,
          positionMs = STORED_POSITION_MS,
          isFinished = false,
          lastPlayedAtEpochMs = graph.clock().millis(),
          speed = 1.0f,
          skipSilence = false,
          gainDb = 0f,
        ),
      )
    }

    // The Continue shelf now has to show it, with a progress pip. Asserted before playing, because
    // a shelf that only becomes right after playback is a shelf that is wrong when it is read.
    val continueRow = children("muplay/continue").single { it.mediaId == book.mediaId }
    val extras = requireNotNull(continueRow.mediaMetadata.extras)
    assertThat(extras.getInt(BrowseExtras.COMPLETION_STATUS))
      .isEqualTo(BrowseExtras.STATUS_PARTIALLY_PLAYED)
    assertThat(extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE))
      .isBetween(0.4, 0.7) // 11 500 of 21 000 ms

    playFromTree(book)

    // Not "setMediaItem returned". Not "playWhenReady is true". The position audio reached.
    val reached = awaitPositionAtLeast(STORED_POSITION_MS - RESUME_SLACK_MS)
    assertThat(reached).isGreaterThan(0L)
    assertThat(reached).isLessThan(STORED_POSITION_MS + RESUME_SLACK_MS)

    // The two discriminating negatives. 0 is what a player that ignored the policy would report;
    // 9 000 is where this book's third chapter starts, and a stored position on a boundary would
    // make "resumed exactly" and "resumed at the chapter start" the same observation.
    assertThat(reached).isNotEqualTo(0L)
    assertThat(reached).isNotBetween(CHAPTER_THREE_START_MS - 250L, CHAPTER_THREE_START_MS + 250L)

    // And it is playing, not parked: two reads separated by real time, strictly increasing.
    val first = onMain { browser.currentPosition }
    Thread.sleep(1_500)
    val second = onMain { browser.currentPosition }
    assertThat(second).isGreaterThan(first)

    // On the right file, too -- a queue that resumed the right second of the wrong file is a
    // failure this would otherwise miss entirely.
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(fileId)
  }

  @Test
  fun aMusicTrackTappedInTheCarStartsAtZeroEvenThoughItHasAStoredPosition() {
    // Spec section 3: music restarts from 0, and progress is still recorded. The contrast is the
    // assertion -- with only the book case above, a policy that resumed everything would pass.
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }
    val track = children(album.mediaId).first()

    runBlocking {
      graph.mediaProgressDao().upsert(
        MediaProgressEntity(
          mediaId = track.mediaId,
          positionMs = 3_000L,
          isFinished = false,
          lastPlayedAtEpochMs = graph.clock().millis(),
          speed = 1.0f,
          skipSilence = false,
          gainDb = 0f,
        ),
      )
    }

    playFromTree(track)
    awaitPositionAtLeast(500L)

    // It started from the top and is now somewhere under a second and a bit in -- never at 3 000.
    assertThat(onMain { browser.currentPosition }).isLessThan(2_500L)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(track.mediaId)

    // ...and the row it was stored under is still there. "Recorded but not honoured" is two claims.
    assertThat(runBlocking { graph.mediaProgressDao().find(track.mediaId) }).isNotNull
  }

  @Test
  fun tappingAnAlbumInTheCarQueuesTheWholeAlbumFromTheTop() {
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }

    playFromTree(album)
    awaitPositionAtLeast(500L)

    assertThat(onMain { browser.mediaItemCount }).isEqualTo(3)
    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(0)
  }

  @Test
  fun tappingATrackInTheMiddleOfAnAlbumQueuesTheAlbumFromThatTrack() {
    // The passthrough assertion, at the level that matters: the *index* has to come from the id
    // that was tapped, not be a constant 0.
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }
    val tracks = children(album.mediaId)

    playFromTree(tracks[1])
    awaitPositionAtLeast(500L)

    assertThat(onMain { browser.mediaItemCount }).isEqualTo(3)
    assertThat(onMain { browser.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(tracks[1].mediaId)
  }

  @Test
  fun tappingShuffleInTheCarPlaysMusicAndNeverABook() {
    // Spec section 1, from a car seat. Library-scoped shuffle is Plan 2's; what this asserts is
    // that the browse row reaches it with the right library id.
    playFromTree(shuffleRow())
    awaitPositionAtLeast(500L)

    val queuedTitles = onMain {
      (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaMetadata.title?.toString() }
    }
    assertThat(queuedTitles).isNotEmpty
    // The seeded music library holds exactly Track 1..3, and the audiobook library holds four books
    // whose file titles are all chapter or book names. An out-of-scope item would show up here.
    assertThat(queuedTitles.toSet()).isSubsetOf(setOf("Track 1", "Track 2", "Track 3"))
  }

  // --- plumbing --------------------------------------------------------------------------------

  private fun shuffleRow(): MediaItem =
    children("muplay/albums").first { it.mediaId == "muplay/shuffle/$MUSIC_LIBRARY_ID" }

  private fun bookItem(title: String): MediaItem =
    children("muplay/books").single { it.mediaMetadata.title?.toString() == title }

  private fun fileIdOf(book: MediaItem): String =
    // A one-file book is not browsable, so its own id is not its file's id: play it and read what
    // the player picked, or -- for a multi-file book -- take the first child. `Second Book` is a
    // single file, so this is the first branch.
    children(book.mediaId).firstOrNull()?.mediaId ?: run {
      playFromTree(book)
      awaitPositionAtLeast(0L)
      val id = requireNotNull(onMain { browser.currentMediaItem?.mediaId })
      onMain { browser.stop(); browser.clearMediaItems() }
      id
    }

  private fun playFromTree(item: MediaItem) {
    onMain {
      browser.setMediaItem(item)
      browser.prepare()
      browser.play()
    }
  }

  private fun connect(hint: String?): MediaBrowser {
    val token = SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
    val hints = Bundle().apply { hint?.let { putString(BrowseSurfaces.HINT_KEY, it) } }
    return onMain { MediaBrowser.Builder(context, token).setConnectionHints(hints).buildAsync() }
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  private fun children(parentId: String): List<MediaItem> =
    awaitResult { it.getChildren(parentId, 0, Int.MAX_VALUE, null) }.value.orEmpty()

  private fun <T> awaitResult(call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>): LibraryResult<T> =
    onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1_000
    while (System.currentTimeMillis() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(50)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; state=${onMain { browser.playbackState }} " +
        "isPlaying=${onMain { browser.isPlaying }} error=${onMain { browser.playerError }} " +
        "item=${onMain { browser.currentMediaItem?.mediaId }}",
    )
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

  private companion object {
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val TIMEOUT_SECONDS = 40L

    /** Plan 4 Task 1's second book: 4 chapters of 4/5/6/6 s, boundaries at 0/4000/9000/15000. */
    const val SECOND_BOOK_TITLE = "Second Book"

    /** Inside chapter three and **on no boundary**, on purpose. */
    const val STORED_POSITION_MS = 11_500L
    const val CHAPTER_THREE_START_MS = 9_000L

    /**
     * How far past the stored position playback may have advanced by the time it is first read.
     * Generous, because a cold start on a CI emulator includes an HTTP round trip and a decoder
     * warm-up — and small enough that 0 and 9 000 are both outside it.
     */
    const val RESUME_SLACK_MS = 2_500L
  }
}
```

- [ ] **Step 5: Run it**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :app:connectedDebugAndroidTest --tests '*CarResumeJourneyTest*'
```

Expected: PASS. If `aBookTappedInTheCarStartsAtTheSecondItWasLeftAtAndCarriesOn` reports a position
of `0`, the fault is upstream of this task — Plan 4's `AudiobookResumePolicy` is not the bound
`ResumePolicy`, or `AudiobookSnapshot` has not loaded. **Do not fix that here.** Check
`MediaModule`'s binding first, and say so in the task report.

- [ ] **Step 6: Prove the resume journey can fail**

1. In `onSetMediaItems`, ignore `selection.startIndex` and pass `0`. Expect
   `tappingATrackInTheMiddleOfAnAlbumQueuesTheAlbumFromThatTrack` to fail, and — importantly —
   `aBookTappedInTheCarStartsAtTheSecondItWasLeftAtAndCarriesOn` to keep passing for a single-file
   book. **That is why both tests exist**: the index only matters for a multi-file queue, and a
   single-file resume cannot see it.
2. In `onSetMediaItems`, pass `startPositionMs` through instead of `C.TIME_UNSET`. Expect nothing
   to fail — Media3 sends `0` for a fresh `setMediaItem`, and `MuPlayer` discards it either way.
   **Record that this mutation is invisible**; it is not a hole, it is the seam working, and a
   reviewer who does not know that will file it as one.
3. In `BrowseTreeRepository.expand`, return `BrowseSelection(listOf(song), 0)` for every `Track`.
   Expect `tappingATrackInTheMiddleOfAnAlbumQueuesTheAlbumFromThatTrack` to fail on
   `mediaItemCount`.
4. In `resolve`, drop the `item.localConfiguration != null` passthrough and always expand. Expect
   Plan 3's `PlaybackJourneyTest` to fail (its launcher hands over already-built items) while
   everything in this file still passes. **Run Plan 3's suite for this mutation**; a passthrough
   proved only by the tests written alongside it is the exact defect this plan's rule 5 names.
5. In `BrowseTreeRepository.expand`, return the book's files in reverse. Expect
   `aBookTappedInTheCarStartsAtTheSecondItWasLeftAtAndCarriesOn`'s final `currentMediaItem`
   assertion to fail for a multi-file book — add `Multi Part Book` as a second case if the
   single-file book cannot see it.
6. Point the shuffle row at the audiobook library id. Expect
   `tappingShuffleInTheCarPlaysMusicAndNeverABook` to fail on the subset assertion. **This is spec
   §1's rule reaching a car seat; watch it fail.**

- [ ] **Step 7: Probes, floors, commit**

`ci/mutation-probes.sh` — add mutations 1, 3 and 6. Add
`core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt` and
`core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt` to `revert()`'s file
list, and `:core:database:test` to `run_suite()` if Plan 2 has not already.

```bash
./gradlew :core:database:test
git add core/model core/database core/media app ci/mutation-probes.sh build.gradle.kts
git commit -m "feat(media): play from the browse tree, at the position the book was left at"
```

---

## Task 6: Voice and search — `onSearch`, `onGetSearchResult` and `ACTION_MEDIA_PLAY_FROM_SEARCH`

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/browse/PlayFromSearch.kt`
- Test: `core/model/src/test/kotlin/app/muplay/model/browse/PlayFromSearchTest.kt`
- Modify: `core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt` (add `searchNodes`)
- Modify: `core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/browse/MuPlayLibraryCallback.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/kotlin/app/muplay/VoiceSearchJourneyTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `BrowseNode`, `BrowseTree`, `BrowseSelection`, `BrowseTreeRepository.expand` — Tasks 2, 5.
  **`BrowseRepository.search(query): SearchResults`** and
  **`app.muplay.model.SearchResults(artists, albums, songs, isEmpty)`** — Plan 2 Tasks 3, 5.
  `AudiobookRepository.bookshelf()` — Plan 4 Task 4. `QueueRepository`, `PlaybackQueue` — Plan 3.
- Produces:
  - `BrowseTree.searchNodes(books, albums, artists, songs): List<BrowseNode>`
  - `object PlayFromSearch` with `fun pick(query: String, nodes: List<BrowseNode>): BrowseNode?`
    and `fun normalise(text: String): String`
  - `BrowseTreeRepository.search(query: String): List<BrowseNode>` and
    `suspend fun searchSelection(query: String): BrowseSelection?`
  - `MuPlayLibraryCallback.onSearch` and `.onGetSearchResult`
  - `MuPlaybackService.onStartCommand` handling `android.media.action.MEDIA_PLAY_FROM_SEARCH`

### Why §7's "the car renders itself from the browse tree and session state" includes search

Android Auto's media screen has a search affordance and a microphone, and both arrive at the same
place: the host calls `onSearch`, then `onGetSearchResult`, and renders whatever comes back with the
same content styles as any other list. An app that implements the browse tree and not search has a
search box in the car that returns nothing, which reads as a broken app rather than as an unsupported
feature — there is no way to say "not supported" to a text field the host has already drawn.

Spoken playback — *"Hey Google, play Second Book on MuPlay"* — is a **different** path, and knowing
which is which is the whole of this task's design:

| Path | Arrives as | Answer |
|---|---|---|
| The car's search box | `onSearch` then `onGetSearchResult` | a list of browse nodes |
| The Assistant, with the app in the foreground | `onSetMediaItems` with `MediaItem.requestMetadata.searchQuery` set | play the best match |
| The Assistant, cold | an `Intent` with action `android.media.action.MEDIA_PLAY_FROM_SEARCH` at the service | play the best match |

The second and third are the same decision reached two ways, so they share one function —
`PlayFromSearch.pick` — and that function is pure.

### `onSearch` computes a count, `onGetSearchResult` computes the list, and neither caches

Media3's contract is two calls: `onSearch` acknowledges the query and calls
`notifySearchResultChanged` with an item count, and the browser then asks for pages through
`onGetSearchResult`. The obvious implementation caches the result between the two.

**This one does not**, and the reason is that the search runs against Plan 2's **local mirror** —
Room, on the device, with no network in the path. Recomputing costs a query; caching costs a map
keyed by (controller, query) with an eviction policy, a staleness question when the sync engine
reconciles mid-drive, and a second code path that can disagree with the first about how many results
there are. Recording that decision here rather than leaving it to be rediscovered.

### Books rank above music in a search, and that is not a tie-break

`searchNodes` puts books first, then albums, then artists, then tracks. In an app whose reason to
exist is audiobook resume, a spoken or typed title is far more likely to be a book than an album, and
the first row is the one a driver takes. `PlayFromSearch.pick` then walks that list in order with
three tiers — exact title, then containment, then "the first playable thing" — so a query that
matches a book exactly beats an album that merely contains the words.

### What CI can and cannot see here

- **`onSearch`/`onGetSearchResult`: fully verified**, on the phone emulator, through a real
  `MediaBrowser` against the real service and the real container.
- **`ACTION_MEDIA_PLAY_FROM_SEARCH`: fully verified.** The journey builds the exact `Intent` the
  Assistant sends — same action, same `SearchManager.QUERY` extra — and starts the service with it,
  then asserts that **audio advanced**. Nothing about that needs a voice or an Assistant.
- **Not verified, and no gate is written for it:** that Google Assistant really sends that action
  with that extra to this service, and speech recognition itself. Task 11 carries it as a manual
  check with a written `adb shell am start-service` line a human can run against a real device, and
  says out loud that it is not gated.

- [ ] **Step 1: Write the failing pure test**

`core/model/src/test/kotlin/app/muplay/model/browse/PlayFromSearchTest.kt`:

```kotlin
package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which one thing a spoken query plays.
 *
 * Three tiers, and each is asserted **against a fixture where the other tiers would give a
 * different answer** — otherwise "exact match wins" and "the first playable wins" are the same
 * observation and only one of them is being tested.
 */
class PlayFromSearchTest {

  @Test
  fun `an exact title match wins over an earlier partial match`() {
    // "Book" is contained in every title here, and "Tail Book" is third in the list. If tiering
    // were reversed, or absent, this would return "Multi Part Book".
    assertThat(PlayFromSearch.pick("Tail Book", NODES)?.title).isEqualTo("Tail Book")
  }

  @Test
  fun `a partial match wins over the first playable node`() {
    assertThat(PlayFromSearch.pick("wizard", NODES)?.title).isEqualTo("A Wizard of Earthsea")
  }

  @Test
  fun `a query that matches nothing still plays something`() {
    // A car that answers "no results" to a spoken request is a car that has done nothing. The
    // first playable node is the best available answer, and it is deliberately not the first node
    // in the fixture -- NODES starts with a browsable folder.
    assertThat(PlayFromSearch.pick("zzzz nothing", NODES)?.title).isEqualTo("Multi Part Book")
  }

  @Test
  fun `an empty query plays the first playable node`() {
    assertThat(listOf("", "   ").map { PlayFromSearch.pick(it, NODES)?.title })
      .containsExactly("Multi Part Book", "Multi Part Book")
  }

  @Test
  fun `a browsable-only node is never picked`() {
    // "Continue" is an exact title match for the first node in the fixture, and it is not playable.
    assertThat(PlayFromSearch.pick("Continue", NODES)?.title).isNotEqualTo("Continue")
    assertThat(PlayFromSearch.pick("Continue", NODES)?.isPlayable).isTrue
  }

  @Test
  fun `nothing playable at all yields null rather than a browsable node`() {
    assertThat(PlayFromSearch.pick("anything", listOf(NODES.first()))).isNull()
    assertThat(PlayFromSearch.pick("anything", emptyList())).isNull()
  }

  @Test
  fun `matching ignores case, punctuation and repeated spaces`() {
    // What a speech recogniser actually hands over: no capitals, no punctuation, stray spaces.
    assertThat(
      listOf("tail book", "TAIL BOOK", "  tail   book  ", "tail-book", "Tail, Book!")
        .map { PlayFromSearch.pick(it, NODES)?.title },
    ).containsExactly("Tail Book", "Tail Book", "Tail Book", "Tail Book", "Tail Book")
  }

  @Test
  fun `normalise is the exact transformation the tiers compare on`() {
    assertThat(listOf("Tail Book", "  TAIL,  book! ", "A Wizard of Earthsea").map(PlayFromSearch::normalise))
      .containsExactly("tail book", "tail book", "a wizard of earthsea")
  }

  private companion object {
    fun node(title: String, playable: Boolean) = BrowseNode(
      id = if (playable) BrowseId.Book(title) else BrowseId.Continue,
      title = title,
      isBrowsable = !playable,
      isPlayable = playable,
      mediaType = if (playable) BrowseMediaType.AUDIO_BOOK else BrowseMediaType.FOLDER_MIXED,
    )

    /**
     * Deliberately shaped so the three tiers disagree: a browsable node first, a partial-match
     * node before the exact-match node, and "Book" contained in three of the four titles.
     */
    val NODES = listOf(
      node("Continue", playable = false),
      node("Multi Part Book", playable = true),
      node("A Wizard of Earthsea", playable = true),
      node("Tail Book", playable = true),
    )
  }
}
```

- [ ] **Step 2: Run it to verify it fails, then implement**

Run: `./gradlew :core:model:test --tests '*PlayFromSearchTest*'`
Expected: FAIL — `Unresolved reference: PlayFromSearch`.

`core/model/src/main/kotlin/app/muplay/model/browse/PlayFromSearch.kt`:

```kotlin
package app.muplay.model.browse

/**
 * Which single node a *spoken* query plays.
 *
 * Distinct from search: a search returns a list for someone to look at, and this returns the one
 * thing to start now, for someone whose hands are on a steering wheel. Three tiers, each of which
 * has to beat the next, and a last resort that is never "nothing" — a car that answers a spoken
 * request with "no results" has done nothing at all, and the app is what gets blamed.
 */
object PlayFromSearch {

  fun pick(query: String, nodes: List<BrowseNode>): BrowseNode? {
    val playable = nodes.filter(BrowseNode::isPlayable)
    if (playable.isEmpty()) return null
    if (query.isBlank()) return playable.first()

    val wanted = normalise(query)
    return playable.firstOrNull { normalise(it.title) == wanted }
      ?: playable.firstOrNull { normalise(it.title).contains(wanted) }
      ?: playable.first()
  }

  /**
   * Lower case, no punctuation, single spaces.
   *
   * This is what a speech recogniser hands over — no capitals, no commas, and sometimes a stray
   * double space where it hesitated — so comparing raw strings would fail on the most common input
   * there is.
   */
  fun normalise(text: String): String =
    text.lowercase()
      .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
      .joinToString("")
      .split(" ")
      .filter(String::isNotEmpty)
      .joinToString(" ")
}
```

Append to `BrowseTree`:

```kotlin
  /**
   * A search result list: books, then albums, then artists, then tracks.
   *
   * The order is a decision, not a tie-break. In an app whose reason to exist is audiobook resume,
   * a typed or spoken title is far more likely to be a book than an album, and the first row is the
   * one a driver takes without reading the rest.
   */
  fun searchNodes(
    books: List<BookSummary>,
    albums: List<Album>,
    artists: List<Artist>,
    songs: List<Song>,
  ): List<BrowseNode> =
    bookNodes(books) + artistChildren(albums) + artistNodes(artists) + songNodes(songs)
```

Run: `./gradlew :core:model:test` — PASS.

- [ ] **Step 3: Implement search in the repository**

Append to `BrowseTreeRepository`:

```kotlin
  /**
   * What a search box in a car should show.
   *
   * Plan 2's `search` answers against the **local mirror**, so this costs a Room query and no
   * network — which is why nothing here is cached between `onSearch` and `onGetSearchResult`.
   *
   * The albums Plan 2 returns are split by **library role**, not by any property of the album:
   * spec section 4 is explicit that Navidrome never reports that something is an audiobook, so an
   * album in a library the user tagged Audiobooks *is* a book and one anywhere else is not.
   */
  suspend fun search(query: String): List<BrowseNode> {
    val results = browseRepository.search(query)
    val bookLibraryIds = librariesWithRole(LibraryRole.AUDIOBOOKS).map(MusicLibrary::id).toSet()
    val musicLibraryIds = librariesWithRole(LibraryRole.MUSIC).map(MusicLibrary::id).toSet()

    val matchedBookIds = results.albums.filter { it.libraryId in bookLibraryIds }.map(Album::id).toSet()
    val books = audiobookRepository.bookshelf().first().filter { it.bookId in matchedBookIds }

    return BrowseTree.searchNodes(
      books = books,
      albums = results.albums.filter { it.libraryId in musicLibraryIds },
      artists = results.artists.filter { it.libraryId in musicLibraryIds },
      songs = results.songs.filter { it.libraryId in musicLibraryIds },
    )
  }

  /** The one thing a **spoken** query should start, already expanded into a queue. */
  suspend fun searchSelection(query: String): BrowseSelection? =
    PlayFromSearch.pick(query, search(query))?.let { expand(it.id) }
```

> `SearchResults`' property names (`artists`, `albums`, `songs`) are Plan 2 Task 3's, and its
> committed `isEmpty` implies exactly those three lists. If they landed under other names, use the
> real ones. **Keep the library-role filters**: they are spec §4's rule, not Plan 2's.

- [ ] **Step 4: Implement the session callbacks and the intent**

Append to `MuPlayLibraryCallback`:

```kotlin
  /**
   * Acknowledges a query and tells the browser how many results there are.
   *
   * Media3's contract is two calls — this one, then `onGetSearchResult` for each page — and this
   * implementation deliberately keeps **no cache** between them. The search runs against the local
   * mirror, so recomputing is a Room query; caching would be a map keyed by controller and query,
   * with an eviction policy and a staleness question every time Plan 2's sync engine reconciles.
   */
  override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    val future = SettableFuture.create<LibraryResult<Void>>()
    scope.launch {
      val count = runCatching { treeRepository.search(query).size }.getOrDefault(0)
      // Must happen before the future completes, or a browser that asks for page 0 the instant it
      // is resolved races the notification and sees nothing.
      session.notifySearchResultChanged(browser, query, count, params)
      future.set(LibraryResult.ofVoid(params))
    }
    return future
  }

  override fun onGetSearchResult(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
    val nodes = treeRepository.search(query)
    val items = BrowsePaging.page(nodes, page, pageSize).map { node ->
      BrowseItems.of(node, treeRepository.artworkUri(node.artworkId))
    }
    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
  }
```

and, inside `onSetMediaItems`, **before** the browse-id expansion, add the spoken-query case:

```kotlin
        // The Assistant with the app already connected: Media3 turns a legacy `playFromSearch` into
        // `onSetMediaItems` with a query on the item's request metadata and no media id at all.
        val spoken = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
        if (spoken != null) {
          val picked = treeRepository.searchSelection(spoken)
            ?: return@runCatching MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
          val items = queueRepository.mediaItems(PlaybackQueue.of(picked.songs, picked.startIndex))
          return@runCatching MediaSession.MediaItemsWithStartPosition(items, picked.startIndex, C.TIME_UNSET)
        }
```

`core/media/src/main/AndroidManifest.xml` — add a second intent filter to the service:

```xml
      <!-- "Hey Google, play <something> on MuPlay" when the app is not already connected: the
           Assistant starts the service with this action and a SearchManager.QUERY extra. Android
           Auto's own microphone reaches the same code through onSetMediaItems' requestMetadata,
           which is why MuPlaybackService funnels both into one function. -->
      <intent-filter>
        <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH" />
        <category android:name="android.intent.category.DEFAULT" />
      </intent-filter>
```

`core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` — add:

```kotlin
  @Inject lateinit var treeRepository: BrowseTreeRepository
  @Inject lateinit var queueRepository: QueueRepository

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  /**
   * Handles the Assistant's cold-start play intent, then hands the intent on to Media3 unchanged.
   *
   * `super.onStartCommand` still runs for every intent, including this one: `MediaSessionService`
   * uses it for media-button routing and for its own foreground bookkeeping, and skipping it for
   * one action is how a service ends up alive but not listening to a headset.
   */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
      playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }
    return super.onStartCommand(intent, flags, startId)
  }

  private fun playFromSearch(query: String) {
    serviceScope.launch {
      val selection = withContext(Dispatchers.Default) { treeRepository.searchSelection(query) }
        ?: return@launch
      val items = withContext(Dispatchers.Default) {
        queueRepository.mediaItems(PlaybackQueue.of(selection.songs, selection.startIndex))
      }
      val player = session?.player ?: return@launch
      // C.TIME_UNSET, like everywhere else in this plan: MuPlayer discards it and asks the resume
      // policy, so "play my book" out loud resumes for the same reason tapping it in a car does.
      player.setMediaItems(items, selection.startIndex, C.TIME_UNSET)
      player.prepare()
      player.play()
    }
  }
```

and cancel `serviceScope` in `onDestroy`.

> `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` is `"android.media.action.MEDIA_PLAY_FROM_SEARCH"`
> and `SearchManager.QUERY` is `"query"`. Both are platform constants (`android.provider.MediaStore`,
> `android.app.SearchManager`); use the constants in code and the literals in the manifest, and note
> that the journey below asserts the literal action string so a wrong constant cannot pass.

- [ ] **Step 5: Write the failing Tier 2 voice journey**

`app/src/androidTest/kotlin/app/muplay/VoiceSearchJourneyTest.kt`:

```kotlin
package app.muplay

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import app.muplay.media.MuPlaybackService
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import app.muplay.model.browse.BrowseSurfaces
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search from a car's search box, and playback from the Assistant's cold-start intent.
 *
 * The second half is the interesting one: the intent below is byte-for-byte what the Assistant
 * sends — the literal action string and the literal `query` extra — so the handler is exercised
 * end to end without a voice, an Assistant or a car. What is **not** proven here is that Google's
 * Assistant sends exactly this; Task 11 carries that as a manual check with a written `adb` line
 * and no gate, because a gate that cannot run is worse than no gate.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSearchJourneyTest {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface VoiceEntryPoint {
    fun credentialStore(): CredentialStore
    fun libraryRepository(): LibraryRepository
    fun syncEngine(): SyncEngine
  }

  private lateinit var context: Context
  private lateinit var browser: MediaBrowser

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val graph = EntryPointAccessors.fromApplication(context, VoiceEntryPoint::class.java)
    runBlocking {
      graph.credentialStore().save(SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"))
      graph.libraryRepository().refreshFromServer()
      graph.libraryRepository().setRole(1, LibraryRole.MUSIC)
      graph.libraryRepository().setRole(2, LibraryRole.AUDIOBOOKS)
      graph.syncEngine().syncIfStale()
    }
    val token = SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
    val hints = android.os.Bundle().apply {
      putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_CAR)
    }
    browser = onMain { MediaBrowser.Builder(context, token).setConnectionHints(hints).buildAsync() }
      .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  @After
  fun tearDown() {
    onMain { browser.stop(); browser.clearMediaItems(); browser.release() }
  }

  @Test
  fun theCarsSearchBoxFindsABookAndPutsItFirst() {
    val titles = searchTitles("Book")

    // Books before music, and both present -- a result list of only books would pass a `contains`
    // check and would not prove the ordering.
    assertThat(titles).isNotEmpty
    assertThat(titles.first()).isIn("Multi Part Book", "Second Book", "Tail Book", "Test Book")
    assertThat(searchTitles("Track")).contains("Track 1", "Track 2", "Track 3")
  }

  @Test
  fun aSearchWithNoMatchesIsAnEmptyListAndNotAnError() {
    val result = awaitResult { it.getSearchResult("zzzz-nothing-matches", 0, Int.MAX_VALUE, null) }

    assertThat(result.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
    assertThat(result.value.orEmpty()).isEmpty()
  }

  @Test
  fun searchResultsArePagedTheSameWayChildrenAre() {
    val all = searchTitles("Book")
    val first = awaitResult { it.getSearchResult("Book", 0, 2, null) }.value.orEmpty()
    val second = awaitResult { it.getSearchResult("Book", 1, 2, null) }.value.orEmpty()

    assertThat(first.map { it.mediaMetadata.title?.toString() }).isEqualTo(all.take(2))
    assertThat(second.map { it.mediaMetadata.title?.toString() }).isEqualTo(all.drop(2).take(2))
  }

  @Test
  fun theAssistantsColdStartIntentMakesAudioAdvance() {
    // Exactly what the Assistant sends: this action string and this extra. Asserted as literals
    // rather than as constants, so a wrong constant in the service cannot pass this test.
    val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
      component = ComponentName(context, MuPlaybackService::class.java)
      putExtra("query", "Second Book")
    }
    assertThat(intent.action).isEqualTo(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
    assertThat(intent.getStringExtra(SearchManager.QUERY)).isEqualTo("Second Book")

    context.startService(intent)

    // Not "the service was started". Not "a queue was set". A position that moved.
    val reached = awaitPositionAtLeast(1_000L)
    assertThat(reached).isGreaterThanOrEqualTo(1_000L)
    val before = onMain { browser.currentPosition }
    Thread.sleep(1_200)
    assertThat(onMain { browser.currentPosition }).isGreaterThan(before)

    // And it played the book that was asked for, not the first thing in the library.
    assertThat(onMain { browser.currentMediaItem?.mediaMetadata?.albumTitle?.toString() })
      .isEqualTo("Second Book")
  }

  @Test
  fun aSpokenQueryThatMatchesNothingStillPlaysSomethingRatherThanNothing() {
    val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
      component = ComponentName(context, MuPlaybackService::class.java)
      putExtra("query", "qqqq no such title")
    }

    context.startService(intent)

    // PlayFromSearch's last tier. A car that answers a spoken request with silence has done
    // nothing, and the app is what gets blamed.
    assertThat(awaitPositionAtLeast(1_000L)).isGreaterThanOrEqualTo(1_000L)
  }

  private fun searchTitles(query: String): List<String> {
    awaitResult { it.search(query, null) }
    return awaitResult { it.getSearchResult(query, 0, Int.MAX_VALUE, null) }
      .value.orEmpty()
      .mapNotNull { it.mediaMetadata.title?.toString() }
  }

  private fun <T> awaitResult(call: (MediaBrowser) -> ListenableFuture<LibraryResult<T>>): LibraryResult<T> =
    onMain { call(browser) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1_000
    while (System.currentTimeMillis() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(50)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; item=${onMain { browser.currentMediaItem?.mediaId }} " +
        "error=${onMain { browser.playerError }}",
    )
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

  private companion object {
    const val NAVIDROME_URL = "http://localhost:4533"
    const val TIMEOUT_SECONDS = 40L
  }
}
```

- [ ] **Step 6: Run it, then prove it can fail**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*VoiceSearchJourneyTest*'
```

Expected: PASS. Then:

1. In `BrowseTree.searchNodes`, put `songNodes(songs)` first. Expect
   `theCarsSearchBoxFindsABookAndPutsItFirst` to fail on the first element.
2. In `PlayFromSearch.pick`, drop the exact-match tier. Expect
   `theAssistantsColdStartIntentMakesAudioAdvance`'s album assertion to fail — the fixture's other
   books all contain "Book" and one of them sorts earlier.
3. In `PlayFromSearch.pick`, return `null` when nothing matches. Expect
   `aSpokenQueryThatMatchesNothingStillPlaysSomethingRatherThanNothing` to fail.
4. In `MuPlaybackService.onStartCommand`, compare against `Intent.ACTION_SEARCH` instead. Expect
   both intent tests to fail with *"position never reached"*. **Record that message** — it is the
   difference between a handler that ran and one that silently did not.
5. Remove the `<intent-filter>` from the manifest. Expect the two intent tests to **still pass**,
   because the journey names the component explicitly. **That is a real gap**: close it in Task 7,
   where `verifyDebugManifest`'s `requiredDeclarations` gains the action string, and note here that
   the manifest half is gated at build time rather than at run time.
6. In `onSearch`, call `notifySearchResultChanged` after `future.set(...)`. Expect
   `searchResultsArePagedTheSameWayChildrenAre` to become flaky rather than to fail outright — run
   it five times. A flaky gate is a finding; record it and keep the ordering.

- [ ] **Step 7: Probes and commit**

`ci/mutation-probes.sh` — add mutations 1 and 3. Add
`core/model/src/main/kotlin/app/muplay/model/browse/PlayFromSearch.kt` to `revert()`'s file list.

```bash
./gradlew :core:model:test :core:database:test
git add core/model core/database core/media app ci/mutation-probes.sh build.gradle.kts
git commit -m "feat(media): search and spoken playback from the car"
```

---

## Task 7: The Android Auto declaration and its validator rules, gated at build time

**Files:**
- Modify: `core/media/src/main/AndroidManifest.xml` (the two discovery actions Auto needs)
- Create: `app/src/main/res/xml/automotive_app_desc.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `build-logic/convention/src/main/kotlin/MuPlayApplicationExtension.kt`
- Create: `build-logic/convention/src/main/kotlin/VerifyAutomotiveDescriptorTask.kt`
- Modify: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/test/kotlin/app/muplay/ConventionTest.kt`
- Modify: `.github/workflows/pr.yml`

**Interfaces:**
- Consumes: **`VerifyMergedManifestTask.requiredDeclarations: ListProperty<String>` and the
  per-variant `verify<Variant>Manifest` registration — Plan 3 Task 5.** The committed tree has only
  `forbiddenAttributes` and a release-only task; if Plan 3 has not landed, do its Step 1 first
  rather than duplicating the mechanism here.
- Produces:
  - `abstract class MuPlayApplicationExtension { abstract val androidAuto: Property<Boolean> }`,
    registered as `muplayApplication`
  - `abstract class VerifyAutomotiveDescriptorTask : DefaultTask` with `descriptor: RegularFileProperty`
    and `requiredUses: ListProperty<String>`
  - `verifyAutomotiveDescriptor`, wired into `check`
  - `AndroidApplicationConventionPlugin.AUTOMOTIVE_DECLARATIONS`

### The trap: Android Auto does not discover a `MediaLibraryService` by its Media3 action

Plan 3 Task 5 declared the service with one intent filter,
`androidx.media3.session.MediaSessionService`, and that is correct for everything Plan 3 needed —
the notification, media buttons, the system's media controls, and a `MediaController` in this app's
own process.

**Android Auto does not look for that action.** It enumerates media apps by the *legacy* browse
action, `android.media.browse.MediaBrowserService`, because Auto's host talks
`MediaBrowserCompat` and Media3's session library bridges it. An app that declares only the Media3
action installs fine, runs fine, passes every test in Tasks 1–6 — and **does not appear in the car
at all**. There is no error, no log line and no crash; the app is simply not in the list.

So the service declares **three** actions:

| Action | Who uses it |
|---|---|
| `androidx.media3.session.MediaSessionService` | Media3 `MediaController`s — this app's UI, `:wear` |
| `androidx.media3.session.MediaLibraryService` | Media3 `MediaBrowser`s asking for the library half |
| `android.media.browse.MediaBrowserService` | **Android Auto**, Wear OS's bridged media surface, and every legacy browser |

This is the single highest-value line in the task, and it is exactly the class of defect a build-time
gate exists for: invisible at runtime on the phone, fatal on the surface the plan is about, and
impossible for any test in this repository to observe without a car.

### The declaration Auto requires, and what a build can actually check

Android Auto's media-app requirements that are *statically checkable*:

1. `<meta-data android:name="com.google.android.gms.car.application"
   android:resource="@xml/automotive_app_desc" />` inside `<application>`.
2. That resource's root is `<automotiveApp>` and it contains `<uses name="media" />`.
3. The service is `exported` and declares the three actions above.

Everything else Google's own media validator checks — that the browse tree loads within the host's
timeout, that content styles render, that the app handles the driver-distraction list limits — is a
**runtime** property of a host this repository cannot start. Tasks 4–6 prove the parts of it that
are ours; Task 11 lists the rest as a manual DHU checklist and writes no gate for them.

### Why the descriptor's *content* gets its own task

`verifyDebugManifest` proves the merged manifest carries `@xml/automotive_app_desc`. It cannot prove
that the file behind that reference says anything, because the merged manifest carries a *resource
reference*, not the resource — the same limitation `AndroidApplicationConventionPlugin`'s existing
documentation already records for `networkSecurityConfig`. A descriptor that existed but was empty
would satisfy the manifest check and still leave the app invisible in a car.

`VerifyAutomotiveDescriptorTask` reads the project's own `res/xml` file rather than a merged
artifact, and that is a real, stated limitation: AGP exposes no public single-artifact handle for
merged resources. It is acceptable here because exactly one module in this build declares the
descriptor and `ConventionTest` asserts that the module opting in is the one that has it — a
library dependency cannot introduce a second one behind the check's back the way it can with a
manifest attribute.

- [ ] **Step 1: Add the two discovery actions and watch the gate fail without them**

`core/media/src/main/AndroidManifest.xml` — replace the service's single intent filter with:

```xml
      <!-- Three actions, three different discoverers, and only the first of them is what Plan 3
           needed:
             - MediaSessionService  -> Media3 MediaControllers (this app's UI, the :wear app)
             - MediaLibraryService  -> Media3 MediaBrowsers asking for the browse tree
             - MediaBrowserService  -> ANDROID AUTO, Wear OS's bridged media surface, and every
                                       legacy browser. Auto enumerates media apps by THIS action and
                                       by no other; without it the app installs, runs, passes every
                                       test in this repository and never appears in a car. There is
                                       no error and no log line. That is why verifyDebugManifest
                                       requires it by name.
           exported="true" is what makes any of that possible; Media3 gates the command surface
           behind MediaSession.Callback.onConnect. -->
      <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
        <action android:name="androidx.media3.session.MediaLibraryService" />
        <action android:name="android.media.browse.MediaBrowserService" />
      </intent-filter>
```

Run: `./gradlew :app:verifyDebugManifest` — **PASS** (the required list has not grown yet). This
step's own check comes at Step 5; the action list is added first so that the gate, once tightened,
goes red only for the reason under test.

- [ ] **Step 2: Add the extension and the descriptor task to build-logic**

`build-logic/convention/src/main/kotlin/MuPlayApplicationExtension.kt`:

```kotlin
import org.gradle.api.provider.Property

/**
 * Per-module application policy that cannot live in a convention plugin.
 *
 * One flag today. `:app` is an Android Auto media app and `:wear` is not, and neither of those is a
 * mechanism a shared plugin can decide — but both need the *mechanism* (the manifest requirements,
 * the descriptor check) to come from one place. Same split as the root build script's
 * `coverageFloors`: policy per module, mechanism once.
 *
 * Not an `android { }` block and not a `kotlin { }` block, so `ConventionTest`'s
 * "no module configures android or kotlin blocks directly" rule is untouched — and that rule gains
 * a companion in this task that asserts `:app` really does opt in, so deleting the opt-in fails the
 * fast tier rather than silently removing a gate.
 */
abstract class MuPlayApplicationExtension {

  /**
   * Whether this application ships to Android Auto.
   *
   * `true` adds the car declarations to `verify<Variant>Manifest`'s required list and registers
   * `verifyAutomotiveDescriptor`. `false` (the default) leaves both alone — a watch app declaring
   * itself a car app would be a wrong claim in a shipped manifest.
   */
  abstract val androidAuto: Property<Boolean>
}
```

`build-logic/convention/src/main/kotlin/VerifyAutomotiveDescriptorTask.kt`:

```kotlin
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if the Android Auto descriptor does not declare every `<uses name="…"/>` in
 * [requiredUses].
 *
 * The companion to [VerifyMergedManifestTask]'s `requiredDeclarations`, and it exists because that
 * task structurally cannot do this job: a merged manifest carries
 * `android:resource="@xml/automotive_app_desc"` — a *reference* — so a descriptor that existed but
 * was empty would satisfy every manifest check there is and still leave the app invisible in a car.
 * The same limitation is already recorded, for `networkSecurityConfig`, in
 * `AndroidApplicationConventionPlugin`'s own documentation.
 *
 * Reads the project's own resource file rather than a merged-resources artifact, because AGP
 * exposes no public single-artifact handle for merged resources. That is a real limitation and not
 * a preference: it means a *library* dependency could in principle contribute a competing
 * descriptor this task never sees. It is acceptable here only because exactly one module in this
 * build opts in (`muplayApplication { androidAuto = true }`) and `ConventionTest` asserts that the
 * module that opts in is the module that has the file.
 */
abstract class VerifyAutomotiveDescriptorTask : DefaultTask() {

  @get:InputFile
  abstract val descriptor: RegularFileProperty

  /** `<uses name="…"/>` values that must be present. `"media"` is what makes this a media app. */
  @get:Input
  abstract val requiredUses: ListProperty<String>

  @TaskAction
  fun verify() {
    val file: File = descriptor.get().asFile
    val text = file.readText()
    if (!text.contains("<automotiveApp")) {
      throw GradleException(
        "$file is not an Android Auto descriptor: its root element must be <automotiveApp>. " +
          "Android Auto reads this file to decide what kind of app this is; a file it cannot " +
          "parse makes the app invisible in a car, with no error anywhere.",
      )
    }
    val missing = requiredUses.get().filterNot { use ->
      // Attribute order and quoting style both vary between hand-written and generated files, so
      // this matches the one thing that cannot: a `uses` element naming this value.
      Regex("""<uses\s+[^>]*name\s*=\s*"${Regex.escape(use)}"""").containsMatchIn(text)
    }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "$file does not declare <uses name=\"${missing.joinToString("\"/> or <uses name=\"")}\"/>. " +
          "Without it Android Auto does not treat this app as a media app, and it does not appear " +
          "in the car's media app list. Nothing at runtime reports this.",
      )
    }
  }
}
```

- [ ] **Step 3: Wire both into the application convention plugin**

`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` — inside `apply`,
after `extensions.configure<ApplicationExtension> { … }`:

```kotlin
      val muplay = extensions.create("muplayApplication", MuPlayApplicationExtension::class.java)
      muplay.androidAuto.convention(false)

      configureMergedManifestVerification(muplay)
      configureAutomotiveDescriptorVerification(muplay)
```

and, extending Plan 3 Task 5's `configureMergedManifestVerification`:

```kotlin
private fun Project.configureMergedManifestVerification(muplay: MuPlayApplicationExtension) {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants { variant ->
    val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}Manifest"
    val verifyTask = tasks.register<VerifyMergedManifestTask>(taskName) {
      group = "verification"
      description = "Checks the ${variant.name} variant's merged manifest."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      forbiddenAttributes.set(
        if (variant.buildType == "release") listOf("usesCleartextTraffic", "networkSecurityConfig")
        else emptyList(),
      )
      requiredDeclarations.set(BASE_DECLARATIONS)
      // A Provider, not a read: the module's build script sets `androidAuto` after this plugin is
      // applied, and reading the Property here would capture its default forever.
      requiredDeclarations.addAll(
        muplay.androidAuto.map { if (it) AUTOMOTIVE_DECLARATIONS else emptyList() },
      )
    }
    tasks.named("check").configure { dependsOn(verifyTask) }
  }
}

private fun Project.configureAutomotiveDescriptorVerification(muplay: MuPlayApplicationExtension) {
  val verifyTask = tasks.register<VerifyAutomotiveDescriptorTask>("verifyAutomotiveDescriptor") {
    group = "verification"
    description = "Checks res/xml/automotive_app_desc.xml declares this app as an Auto media app."
    onlyIf { muplay.androidAuto.get() }
    descriptor.set(layout.projectDirectory.file("src/main/res/xml/automotive_app_desc.xml"))
    requiredUses.set(listOf("media"))
  }
  tasks.named("check").configure { dependsOn(verifyTask) }
}

/** Spec section 7's permission list, plus the service that would otherwise fail only in the wild. */
private val BASE_DECLARATIONS = listOf(
  "android.permission.INTERNET",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
  "app.muplay.media.MuPlaybackService",
  "androidx.media3.session.MediaSessionService",
  "mediaPlayback",
)

/**
 * What Android Auto needs, and what nothing at runtime on a phone will ever tell you is missing.
 *
 * `android.media.browse.MediaBrowserService` is the one to read twice: Auto enumerates media apps
 * by that legacy action and by no other, so an app declaring only Media3's own actions is simply
 * absent from the car's list. `android.media.action.MEDIA_PLAY_FROM_SEARCH` is the Assistant's
 * cold-start entry point (Task 6) — its handler is covered by an instrumented test, but that test
 * names the component explicitly, so the *filter* is gated only here.
 */
private val AUTOMOTIVE_DECLARATIONS = listOf(
  "androidx.media3.session.MediaLibraryService",
  "android.media.browse.MediaBrowserService",
  "android.media.action.MEDIA_PLAY_FROM_SEARCH",
  "com.google.android.gms.car.application",
  "@xml/automotive_app_desc",
)
```

- [ ] **Step 4: Opt `:app` in, and write the two files**

`app/build.gradle.kts` — beside the existing `android { namespace = … }` block:

```kotlin
// This application ships to Android Auto: it adds the car declarations to the merged-manifest gate
// and turns on `verifyAutomotiveDescriptor`. `:wear` deliberately does not set this -- a watch app
// claiming to be a car app would be a wrong claim in a shipped manifest.
muplayApplication {
  androidAuto = true
}
```

`app/src/main/res/xml/automotive_app_desc.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  What Android Auto reads to decide what kind of app this is. `media` is the only entry a media app
  needs; `template` is for apps built on the Car App Library, which spec section 7 rules out
  explicitly ("Android Auto needs no UI work").

  Checked by `verifyAutomotiveDescriptor` (build-logic), because the merged manifest can only prove
  that this file is *referenced*, never that it says anything.
-->
<automotiveApp>
  <uses name="media" />
</automotiveApp>
```

`app/src/main/AndroidManifest.xml` — inside `<application>`:

```xml
    <!-- Android Auto's entry point into the app's declaration. The resource is checked by
         `verifyAutomotiveDescriptor`; the presence of this line is checked by
         `verifyDebugManifest`/`verifyReleaseManifest`. -->
    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />
```

- [ ] **Step 5: Watch both gates fail, one at a time**

**A gate nobody has watched fail is a gate nobody knows works.** Six separate runs, each reverted
before the next:

1. Delete the `<meta-data>` block from `app/src/main/AndroidManifest.xml`.
   Run `./gradlew :app:verifyDebugManifest` → **FAIL**, naming
   `com.google.android.gms.car.application` and `@xml/automotive_app_desc`.
2. Delete `android.media.browse.MediaBrowserService` from `:core:media`'s service filter.
   Run `./gradlew :app:verifyDebugManifest` → **FAIL**, naming it. **This is the invisible-in-the-car
   defect; record the message.**
3. Delete `android.media.action.MEDIA_PLAY_FROM_SEARCH` from the same filter.
   Run `./gradlew :app:verifyDebugManifest` → **FAIL**, naming it — this closes Task 6 Step 6's
   recorded gap.
4. Empty `automotive_app_desc.xml` to `<automotiveApp />`.
   Run `./gradlew :app:verifyAutomotiveDescriptor` → **FAIL**, naming `<uses name="media"/>`.
5. Replace its root element with `<app/>`.
   Run `./gradlew :app:verifyAutomotiveDescriptor` → **FAIL**, naming `<automotiveApp>`.
6. Run `./gradlew :wear:verifyDebugManifest :wear:verifyAutomotiveDescriptor` **after Task 8 has
   created `:wear`** → the manifest task PASSES without the car entries, and the descriptor task is
   **SKIPPED** (`onlyIf`). If the descriptor task *fails* for `:wear`, the `onlyIf` is wrong and a
   watch is being held to a car's requirements.

- [ ] **Step 6: Close the loop in `ConventionTest`, so the opt-in cannot be deleted quietly**

`app/src/test/kotlin/app/muplay/ConventionTest.kt` — add:

```kotlin
  /**
   * The Android Auto gate is only as real as the opt-in that turns it on.
   *
   * `muplayApplication { androidAuto = true }` is four words in one build file, and deleting them
   * removes `verifyAutomotiveDescriptor` and five entries from the merged-manifest gate **without
   * failing anything** — the app would still build, still install, still pass every instrumented
   * test, and quietly stop appearing in a car. That is precisely the shape of "a gate that reports
   * the absence of a problem must be provably incapable of staying quiet when it did not run", and
   * this test is what makes it incapable.
   */
  @Test
  fun `the app module opts in to Android Auto and ships the descriptor it promises`() {
    val appBuildFile = File(repoRoot, "app/build.gradle.kts").readText()
    assertThat(appBuildFile)
      .describedAs("app/build.gradle.kts must keep muplayApplication { androidAuto = true }")
      .containsPattern("""muplayApplication\s*\{[^}]*androidAuto\s*=\s*true""")

    val descriptor = File(repoRoot, "app/src/main/res/xml/automotive_app_desc.xml")
    assertThat(descriptor).exists()

    // And the module that opts in is the module that has the file -- the one assumption
    // VerifyAutomotiveDescriptorTask makes about reading a source resource rather than a merged one.
    val optedIn = moduleBuildFiles()
      .filter { it.readText().containsPattern("""androidAuto\s*=\s*true""") }
      .map { it.parentFile.name }
    assertThat(optedIn).containsExactly("app")
  }

  /** The three actions Android Auto, Media3 and legacy browsers each need — none of them optional. */
  @Test
  fun `the playback service declares every action a media browser discovers it by`() {
    val manifest = File(repoRoot, "core/media/src/main/AndroidManifest.xml").readText()

    assertThat(
      listOf(
        "androidx.media3.session.MediaSessionService",
        "androidx.media3.session.MediaLibraryService",
        "android.media.browse.MediaBrowserService",
        "android.media.action.MEDIA_PLAY_FROM_SEARCH",
      ).map(manifest::contains),
    ).containsExactly(true, true, true, true)
  }
```

> `containsPattern` above is AssertJ's on a `String` assertion; for the `moduleBuildFiles()` filter
> use `Regex("""androidAuto\s*=\s*true""").containsMatchIn(text)`. `moduleBuildFiles()` and
> `repoRoot` are `ConventionTest`'s own existing helpers — read the file rather than reinventing
> them.

- [ ] **Step 7: Put the gate in Tier 1 and commit**

`.github/workflows/pr.yml` — the Static job's release-manifest step becomes:

```yaml
      - name: Manifest and Android Auto declaration
        # Both variants, plus the descriptor's own content. All three are build-time checks for
        # defects that are invisible at runtime on a phone and fatal in a car — see
        # AndroidApplicationConventionPlugin's AUTOMOTIVE_DECLARATIONS for the specific one that
        # makes an app disappear from a head unit with no error anywhere.
        run: ./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest :app:verifyAutomotiveDescriptor
```

```bash
./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest :app:verifyAutomotiveDescriptor
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
git add core/media app build-logic .github/workflows/pr.yml
git commit -m "feat(app): declare MuPlay to Android Auto, and gate the declaration"
```

---

## Task 8: `:wear` — the module, the convention plugin, and a watch that reaches the same service

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`
- Create: `build-logic/convention/src/main/kotlin/AndroidWearConventionPlugin.kt`
- Create: `wear/build.gradle.kts`
- Create: `wear/src/main/AndroidManifest.xml`, `wear/src/debug/AndroidManifest.xml`
- Create: `wear/src/main/kotlin/app/muplay/wear/MuPlayWearApplication.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/WearActivity.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/WearBrowser.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/di/WearModule.kt`
- Test: `wear/src/androidTest/kotlin/app/muplay/wear/WearSessionJourneyTest.kt`
- Create: `ci/prepare-wear-emulator.sh`
- Modify: `.github/workflows/e2e.yml`, `app/src/test/kotlin/app/muplay/ConventionTest.kt`

**Interfaces:**
- Consumes: `MuPlaybackService`, `PlaybackConnection` (Plan 3 Task 5), `BrowseSurfaces.HINT_KEY`/
  `HINT_WATCH` (Task 3), the browse tree (Tasks 4–6), `CredentialStore`, `LibraryRepository`,
  `SyncEngine` (Plan 2), `configureKotlinAndroid` and the application convention plugin
  (build-logic, Plan 1 + Task 7).
- Produces:
  - convention plugin id `muplay.android.wear`
  - Gradle module `:wear`, namespace `app.muplay.wear`, applicationId `app.muplay`, `minSdk 30`
  - `class MuPlayWearApplication : Application` (`@HiltAndroidApp`)
  - `class WearActivity : ComponentActivity` (`@AndroidEntryPoint`)
  - `class WearBrowser @Inject constructor(@ApplicationContext context)` with
    `suspend fun browser(): MediaBrowser`, `suspend fun children(parentId: String): List<MediaItem>`,
    `fun release()`
  - `ci/prepare-wear-emulator.sh`
  - a second emulator step in `.github/workflows/e2e.yml`'s existing job

### What a Wear OS app in this project *is*, decided once

**Standalone, streaming, hosting the same `MuPlaybackService`.** The watch app depends on
`:core:media`, `:core:database` and `:core:network`, starts the very same service class, and browses
it with a `MediaBrowser` that declares itself a watch through `BrowseSurfaces.HINT_WATCH`. There is
no second player, no second browse tree and no second progress schema.

Two alternatives were considered and rejected, and the reasons are worth keeping because both are
what a reader will ask:

- **A remote control for the phone's session.** Wear OS already bridges a phone's `MediaSession` to
  the watch's system media controls, for free, with no code. Building an app to duplicate that would
  ship a screen that does what the platform already does — and it would still be useless on a run
  with the phone left at home, which is exactly when a watch matters.
- **A watch that reads the phone's library over the Data Layer.** That makes every browse request a
  round trip over Bluetooth to a device that may be off. Streaming from Navidrome over the watch's
  own Wi-Fi is one hop and works with the phone in another building.

What the watch does need from the phone is **credentials** and **progress**, and that is Task 10.
Task 8 stops at "a watch that can reach the service", and its test seeds credentials directly —
which is precisely what Task 10's transport will later write.

### `minSdk 30`, and why it is the only place the global constraint bends

The roadmap and spec §2 fix `minSdk 26` for this project. **Wear OS 3 is API 30 and there is no
earlier Wear release worth targeting** — Wear OS 2 uses a different app model entirely and Compose
for Wear OS does not support it. So `:wear` alone raises its floor, in a convention plugin, and every
shared module stays at 26. Stated in Global Constraints above as well, so it is not discovered here.

### No navigation library on the watch

Spec §9 fixes **Navigation 3** for this app, and `androidx.wear.compose:compose-navigation` is built
on Navigation **2**. Putting both in one build to serve two screens and a browse stack is a real cost
for no benefit, so the watch does neither: its whole navigation state is a `List<BrowseId>` back
stack plus a "player is showing" flag, and the back gesture is Wear Compose Material3's own
`SwipeToDismissBox`, which is the platform's predictive-back on a watch. Task 9 builds it; this task
records the decision so Task 9 does not relitigate it.

### The CI question this task exists to answer honestly

**A Wear emulator is not the phone emulator this project uses.** Different system-image package,
different device profile, different AVD. So:

- It gets **its own preflight** (`ci/prepare-wear-emulator.sh`), which asserts the device really is
  a watch before doing anything else — because the failure mode of running the wear suite on the
  phone image is a **green run that proves nothing**, which is rule 6.
- It gets **its own emulator step inside the existing Tier 2 job**, not a new job. The reason is
  coverage: `Jacoco.kt` merges execution data from one workspace, and a separate job's `.ec` files
  would not be there when `jacocoTestCoverageVerification` runs. Two emulator steps in one job cost
  wall-clock time; a split job would cost the coverage gate its data, and quality outranks gate
  speed by this project's own rule.
- The instrumented test **asserts `PackageManager.FEATURE_WATCH`** as its first act, so a suite that
  somehow ran on the wrong image fails loudly instead of passing.

- [ ] **Step 1: Resolve and pin the Wear Compose coordinates**

**These are the plan's only unpinned versions, and they are unpinned because they could not be
resolved while it was written rather than because they are unimportant.** Resolve them exactly the
way Plan 3 resolved every Media3 artifact, and put the values you read into the catalogue:

```bash
for a in compose-material3 compose-foundation; do
  echo -n "androidx.wear.compose:$a -> "
  curl -s "https://dl.google.com/dl/android/maven2/androidx/wear/compose/$a/maven-metadata.xml" \
    | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'
done
echo -n "androidx.wear:wear-tooling-preview -> "
curl -s "https://dl.google.com/dl/android/maven2/androidx/wear/wear-tooling-preview/maven-metadata.xml" \
  | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'
```

`gradle/libs.versions.toml` — add, with **the values that command printed** (the two
`androidx.wear.compose` artifacts share one version train, so one ref covers both; if the command
shows them diverging, give each its own ref and say so in the task report):

```toml
[versions]
# Resolved against dl.google.com's maven-metadata while this task was executed -- record the exact
# values in the task report. Compose for Wear OS Material3 is a different artifact family from
# `androidx.compose.material3`; the two are not interchangeable and a watch must use this one.
wearCompose = "<the <latest> printed above>"
wearToolingPreview = "<the <latest> printed above>"

[libraries]
androidx-wear-compose-material3  = { module = "androidx.wear.compose:compose-material3", version.ref = "wearCompose" }
androidx-wear-compose-foundation = { module = "androidx.wear.compose:compose-foundation", version.ref = "wearCompose" }
androidx-wear-tooling-preview    = { module = "androidx.wear:wear-tooling-preview", version.ref = "wearToolingPreview" }
```

Verify they resolve before writing any code:

```bash
./gradlew --refresh-dependencies :wear:dependencies --configuration debugRuntimeClasspath
```

Expected: no `Could not find androidx.wear.compose:compose-material3:<version>`. If there is one, the
version is wrong — re-read the metadata rather than guessing a neighbouring number.

- [ ] **Step 2: Add the convention plugin and the module**

`build-logic/convention/src/main/kotlin/AndroidWearConventionPlugin.kt`:

```kotlin
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `muplay.android.wear`: an application module that runs on a watch.
 *
 * Everything `muplay.android.application` sets up — including `verify<Variant>Manifest`, which a
 * Wear APK needs just as much as a phone one — plus the single thing that genuinely differs:
 * **`minSdk 30`**. Wear OS 3 is API 30, there is no earlier Wear release Compose for Wear OS
 * supports, and the project's `minSdk 26` (spec section 2) therefore cannot apply here. This is the
 * only module in the build that moves it, and it moves it in build-logic rather than in a module's
 * own `android { }` block, which `ConventionTest` forbids.
 *
 * `muplayApplication.androidAuto` is deliberately left at its `false` default: a watch app
 * declaring itself an Android Auto media app would be a wrong claim in a shipped manifest, and
 * Task 7's `verifyAutomotiveDescriptor` is skipped for exactly that reason.
 */
class AndroidWearConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("muplay.android.application")

      extensions.configure<ApplicationExtension> {
        // After the application plugin, so this wins over configureKotlinAndroid's 26.
        defaultConfig.minSdk = 30
      }
    }
  }
}
```

Register it in `build-logic/convention/build.gradle.kts`'s `gradlePlugin { plugins { … } }` block
beside the existing ids, as `muplay.android.wear` →
`AndroidWearConventionPlugin`.

`settings.gradle.kts` — `include(":wear")`.

`wear/build.gradle.kts`:

```kotlin
plugins {
  alias(libs.plugins.muplay.android.wear)
  alias(libs.plugins.muplay.android.compose)
  alias(libs.plugins.muplay.android.hilt)
}

android {
  namespace = "app.muplay.wear"
  defaultConfig {
    // The **same** applicationId as `:app`. Play requires a Wear APK to share the phone app's
    // application id to be distributed alongside it, and the two never land on the same device.
    applicationId = "app.muplay"
    versionCode = 1
    versionName = "0.1.0"
  }
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:network"))
  implementation(project(":core:database"))
  implementation(project(":core:media"))
  implementation(project(":core:designsystem"))

  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.androidx.wear.compose.foundation)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.media3.session)
  implementation(libs.coil.compose)

  debugImplementation(libs.androidx.wear.tooling.preview)

  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.assertj)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

> `libs.androidx.activity.compose`, `libs.androidx.lifecycle.runtime.compose`, `libs.coil.compose`,
> `libs.androidx.compose.ui.test.junit4` and `libs.androidx.compose.ui.test.manifest` are Plan 1's
> and Plan 2's catalogue aliases — **read `gradle/libs.versions.toml` and use the real alias names**;
> do not add duplicates under new names. `libs.media3.session` is Plan 3 Task 2's.

`build.gradle.kts` — `:wear` needs a `coverageFloors` entry or `ConventionTest`'s *"every Gradle
project has a coverage floor"* fails the build. Add a placeholder-free entry only **after** Task 9
has produced a measurable report; until then, add the module to the table with the floors Task 11
measures and note in the task report that the entry is provisional. **Never write `0.00`** — this
table has shipped a floor that could not fail once already.

- [ ] **Step 3: The manifests**

`wear/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

  <!-- Declares this APK as a watch app. Play uses it to route the right APK to the right device,
       and the instrumented suite asserts PackageManager.FEATURE_WATCH so a run on the wrong image
       fails loudly instead of passing. -->
  <uses-feature android:name="android.hardware.type.watch" />

  <!-- INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE(_MEDIA_PLAYBACK) and MuPlaybackService
       itself all arrive through :core:media's manifest, which Plan 3 Task 5 put there rather than
       in :app precisely so a second application module would get them without anyone copying four
       lines. `verifyDebugManifest` proves that claim for this module too. -->

  <application
      android:name=".MuPlayWearApplication"
      android:allowBackup="false"
      android:icon="@mipmap/ic_launcher"
      android:label="MuPlay"
      android:supportsRtl="true"
      android:theme="@android:style/Theme.DeviceDefault">

    <!-- Standalone: this app works with the phone off, out of range, or an iPhone. It is a factual
         declaration -- the watch streams from Navidrome over its own Wi-Fi and never needs the
         phone to play. Task 10's credential transfer is a convenience on top, not a dependency. -->
    <meta-data android:name="com.google.android.wearable.standalone" android:value="true" />

    <uses-library android:name="com.google.android.wearable" android:required="false" />

    <activity
        android:name=".WearActivity"
        android:exported="true"
        android:label="MuPlay"
        android:taskAffinity=""
        android:theme="@android:style/Theme.DeviceDefault">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

`wear/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Debug only, and the same reason as app/src/debug/AndroidManifest.xml: Tier 2 reaches the Navidrome
  container at http://localhost:4533 through `adb reverse`. `verifyReleaseManifest` fails the build
  if this ever reaches release, for this module exactly as for :app -- the application convention
  plugin registers that task per variant, so :wear inherited the gate by existing.
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:usesCleartextTraffic="true" />
</manifest>
```

- [ ] **Step 4: The application, the activity and the browser**

`wear/src/main/kotlin/app/muplay/wear/MuPlayWearApplication.kt`:

```kotlin
package app.muplay.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The watch's own Hilt graph.
 *
 * A separate `Application` from `:app`'s, over the same `:core:*` modules — which means the watch
 * has its **own** Room database, its own credential store and its own `media_progress` rows. That
 * is a consequence of two devices, not a design choice, and it is what Task 10 exists to reconcile.
 */
@HiltAndroidApp
class MuPlayWearApplication : Application()
```

`wear/src/main/kotlin/app/muplay/wear/WearActivity.kt`:

```kotlin
package app.muplay.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/**
 * The watch's one activity. Task 9 fills [WearApp] in; this task needs only somewhere to host it.
 */
@AndroidEntryPoint
class WearActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { WearApp() }
  }
}
```

`wear/src/main/kotlin/app/muplay/wear/WearBrowser.kt`:

```kotlin
package app.muplay.wear

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import app.muplay.media.MuPlaybackService
import app.muplay.model.browse.BrowseSurfaces
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.guava.await

/**
 * The watch's single connection to the browse tree.
 *
 * **The connection hint is the whole point of this class.** The watch app's package is `app.muplay`
 * — the same application id as the phone — so nothing about the *identity* of this controller could
 * ever distinguish it. `BrowseSurfaces.HINT_WATCH` is the self-declaration that gets it the watch
 * tree (three root tabs, a short Continue shelf, list layout), and `BrowseSurfaces.of` honours it
 * only because it comes from our own package.
 */
@Singleton
class WearBrowser @Inject constructor(@ApplicationContext private val context: Context) {

  private var connected: MediaBrowser? = null

  suspend fun browser(): MediaBrowser = connected ?: connect().also { connected = it }

  suspend fun children(parentId: String): List<MediaItem> =
    browser().getChildren(parentId, 0, Int.MAX_VALUE, null).await().value.orEmpty()

  fun release() {
    connected?.release()
    connected = null
  }

  private suspend fun connect(): MediaBrowser {
    val token = SessionToken(context, ComponentName(context, MuPlaybackService::class.java))
    val hints = Bundle().apply { putString(BrowseSurfaces.HINT_KEY, BrowseSurfaces.HINT_WATCH) }
    val future = MediaBrowser.Builder(context, token).setConnectionHints(hints).buildAsync()
    return suspendCoroutine { continuation ->
      future.addListener(
        { continuation.resume(future.get()) },
        androidx.core.content.ContextCompat.getMainExecutor(context),
      )
    }
  }
}
```

> `kotlinx.coroutines.guava.await` needs `org.jetbrains.kotlinx:kotlinx-coroutines-guava`. If you do
> not want that dependency — and dependency minimalism says think twice — replace the `.await()` call
> with the same `suspendCoroutine`/`addListener` shape `connect` already uses, factored into one
> private helper. **Decide once, in this file, and record which you chose**; do not have two
> future-awaiting idioms in one module.

`wear/src/main/kotlin/app/muplay/wear/di/WearModule.kt` — nothing to provide yet;
`WearBrowser` is `@Inject constructor` and every `:core:*` binding comes from those modules' own
Hilt modules. **Create this file only when Task 9 or 10 actually needs a binding**; an empty Hilt
module is a class that exists to be deleted.

- [ ] **Step 5: The wear preflight, and the check that it really is a watch**

`ci/prepare-wear-emulator.sh`:

```bash
#!/usr/bin/env bash
#
# The Wear OS half of `ci/prepare-emulator.sh`: waits for a booted watch emulator, checks it really
# is a watch, and sets up the `adb reverse` that lets `http://localhost:4533` inside the emulator
# reach the Navidrome container on the host.
#
# WHY THIS IS A SEPARATE SCRIPT. A Wear system image is a different SDK package, a different device
# profile and a different AVD from the API 37 phone image `ci/prepare-emulator.sh` guards, and the
# two are launched by two separate steps of the same job. Widening the phone script to accept either
# would mean it could no longer fail on "wrong system image", which is the main thing it is for.
#
# WHY THE WATCH CHECK MATTERS MORE THAN THE API CHECK. `:wear:connectedDebugAndroidTest` installs
# and runs happily on a phone image -- `uses-feature` filters Play, not `adb install`. A wear suite
# that ran on the phone emulator would be **green and worthless**, which is precisely the defect
# class this project has found six times. So this script fails, and `WearSessionJourneyTest` asserts
# `PackageManager.FEATURE_WATCH` again from inside the APK. Two independent checks, because a gate
# that reports the absence of a problem must be provably incapable of staying quiet.
set -euo pipefail

readonly NAVIDROME_PORT=4533

# Held equal to `.github/workflows/e2e.yml`'s `WEAR_*` job env by ConventionTest's
# "the wear emulator coordinates cannot drift apart" rule, the same mechanism the phone coordinates
# already have.
readonly WEAR_API_LEVEL=36
readonly WEAR_TARGET=android-wear
readonly WEAR_ARCH=x86_64
readonly WEAR_PROFILE=wearos_small_round

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

fail() { echo "$1" >&2; exit 1; }
prop() { adb shell getprop "$1" | tr -d '\r'; }

characteristics="$(prop ro.build.characteristics)"
case ",$characteristics," in
  *,watch,*) : ;;
  *) fail "ro.build.characteristics is '$characteristics', which does not include 'watch'.
This is not a Wear OS system image. A wear suite that runs here is green and proves nothing --
see this script's header." ;;
esac

api_level="$(prop ro.build.version.sdk)"
[ "$api_level" = "$WEAR_API_LEVEL" ] ||
  fail "device reports API $api_level, expected $WEAR_API_LEVEL -- wrong Wear system image"

abi="$(prop ro.product.cpu.abi)"
[ "$abi" = "$WEAR_ARCH" ] ||
  fail "device reports ABI $abi, expected $WEAR_ARCH -- wrong Wear system image"

# Reported, not asserted. The Minigbm workaround `ci/prepare-emulator.sh` documents was measured
# against the API 37 *phone* image and this repository has no measurement for the Wear image. If a
# wear run dies with "INSTRUMENTATION_ABORTED: System has crashed", add
#   -feature Minigbm -prop qemu.hardware.gralloc=minigbm
# to this emulator's options in e2e.yml, re-measure, and turn the line below into a hard check --
# with the evidence written down, the way the phone script's header does it.
echo "gralloc=$(prop ro.hardware.gralloc) (not asserted -- no measurement for the Wear image yet)"

adb reverse "tcp:$NAVIDROME_PORT" "tcp:$NAVIDROME_PORT"

echo "wear emulator ready: android-$api_level $WEAR_TARGET $abi ($WEAR_PROFILE)," \
     "tcp:$NAVIDROME_PORT reversed to the host"
adb reverse --list
```

`chmod +x ci/prepare-wear-emulator.sh`.

- [ ] **Step 6: Write the failing wear session journey**

`wear/src/androidTest/kotlin/app/muplay/wear/WearSessionJourneyTest.kt`:

```kotlin
package app.muplay.wear

import android.content.Context
import android.content.pm.PackageManager
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.CredentialStore
import app.muplay.database.LibraryRepository
import app.muplay.database.SyncEngine
import app.muplay.media.PlaybackConnection
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A watch reaching the same service, browsing the same tree, playing the same audio.
 *
 * **The first assertion in this file is that this really is a watch.** `:wear` installs and runs on
 * a phone image without complaint — `uses-feature` filters Play, not `adb install` — so a suite that
 * ran on the wrong emulator would be green and prove nothing. `ci/prepare-wear-emulator.sh` checks
 * the same thing from outside; this checks it from inside the APK, because one of the two could be
 * skipped and neither knows about the other.
 */
@RunWith(AndroidJUnit4::class)
class WearSessionJourneyTest {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface WearTestEntryPoint {
    fun credentialStore(): CredentialStore
    fun libraryRepository(): LibraryRepository
    fun syncEngine(): SyncEngine
    fun browser(): WearBrowser
    fun playbackConnection(): PlaybackConnection
  }

  private lateinit var context: Context
  private lateinit var graph: WearTestEntryPoint

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    graph = EntryPointAccessors.fromApplication(context, WearTestEntryPoint::class.java)

    runBlocking {
      // Exactly what Task 10's Data Layer transport will later write, written here directly. The
      // watch's database is its own -- this is not the phone's row being read.
      graph.credentialStore().save(SubsonicCredentials(NAVIDROME_URL, "admin", "testpass"))
      graph.libraryRepository().refreshFromServer()
      graph.libraryRepository().setRole(MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
      graph.libraryRepository().setRole(AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)
      graph.syncEngine().syncIfStale()
    }
  }

  @After
  fun tearDown() {
    onMain {
      runBlocking { graph.browser().browser() }.let { it.stop(); it.clearMediaItems() }
      graph.browser().release()
    }
  }

  @Test
  fun thisIsActuallyAWatch() {
    assertThat(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
      .describedAs("this suite is only meaningful on a Wear OS image; see ci/prepare-wear-emulator.sh")
      .isTrue
  }

  @Test
  fun theWatchGetsTheWatchTreeAndNotThePhoneOne() {
    // Three tabs, exactly, in order. If the hint were ignored the watch would get the phone's five
    // and this is the only place in the build that would notice.
    assertThat(childIds("muplay/root"))
      .containsExactly("muplay/continue", "muplay/books", "muplay/albums")
  }

  @Test
  fun theWatchCanBrowseDownToATrack() {
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }

    assertThat(children(album.mediaId).map { it.mediaMetadata.title?.toString() })
      .containsExactly("Track 1", "Track 2", "Track 3")
  }

  @Test
  fun theWatchStreamsFromNavidromeAndThePositionAdvances() {
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }
    val track = children(album.mediaId).first()
    val browser = runBlocking { graph.browser().browser() }

    onMain {
      browser.setMediaItem(track)
      browser.prepare()
      browser.play()
    }

    // The same standard as every other playback assertion in this project: a position that moved,
    // not a player that was asked to play. A CI watch emulator has no audio device (`-no-audio`),
    // and ExoPlayer's clock advances anyway -- which is the same reason Plan 3's journeys work.
    val first = awaitPositionAtLeast(1_000L)
    Thread.sleep(1_200)
    assertThat(onMain { browser.currentPosition }).isGreaterThan(first)
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(track.mediaId)
  }

  @Test
  fun theWatchWritesItsOwnProgressRow() {
    // The fact Task 10 exists because of: the watch has its own media_progress table, and nothing
    // reconciles it with the phone's yet. Asserting it here means Task 10's merge has a documented
    // starting state rather than an assumed one.
    val album = children("muplay/albums").first { it.mediaId.startsWith("muplay/album/") }
    val track = children(album.mediaId).first()
    val browser = runBlocking { graph.browser().browser() }

    onMain { browser.setMediaItem(track); browser.prepare(); browser.play() }
    awaitPositionAtLeast(2_000L)
    onMain { browser.pause() }
    Thread.sleep(1_000) // Plan 3's ProgressWriter persists on pause; give it the round trip.

    assertThat(graph.playbackConnection().state.value.mediaId).isEqualTo(track.mediaId)
  }

  private fun children(parentId: String): List<MediaItem> =
    runBlocking { graph.browser().children(parentId) }

  private fun childIds(parentId: String): List<String> = children(parentId).map(MediaItem::mediaId)

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val browser = runBlocking { graph.browser().browser() }
    val deadline = System.currentTimeMillis() + TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(50)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; item=${onMain { browser.currentMediaItem?.mediaId }} " +
        "error=${onMain { browser.playerError }}",
    )
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

  private companion object {
    const val NAVIDROME_URL = "http://localhost:4533"
    const val MUSIC_LIBRARY_ID = 1
    const val AUDIOBOOK_LIBRARY_ID = 2
    const val TIMEOUT_MS = 40_000L
  }
}
```

> `WearApp()` does not exist until Task 9. Add exactly this, purely so `WearActivity` compiles, and
> **replace the whole file in Task 9** — do not build UI here:
>
> ```kotlin
> package app.muplay.wear
>
> import androidx.compose.runtime.Composable
> import androidx.wear.compose.material3.AppScaffold
> import androidx.wear.compose.material3.MaterialTheme
> import androidx.wear.compose.material3.Text
>
> /** Placeholder. Task 9 replaces this file entirely. */
> @Composable
> fun WearApp() {
>   MaterialTheme { AppScaffold { Text("MuPlay") } }
> }
> ```

- [ ] **Step 7: Add the second emulator step to Tier 2**

`.github/workflows/e2e.yml` — in the **same** job (see this task's header for why not a second job):

```yaml
    env:
      EMULATOR_API_LEVEL: "37.0"
      EMULATOR_TARGET: google_apis
      EMULATOR_ARCH: x86_64
      EMULATOR_BUILD: "15917651"
      # The watch. A different package, a different profile and a different AVD from the phone
      # image above — see ci/prepare-wear-emulator.sh's header. Held equal to that script's own
      # declarations by ConventionTest.
      WEAR_API_LEVEL: "36"
      WEAR_TARGET: android-wear
      WEAR_ARCH: x86_64
      WEAR_PROFILE: wearos_small_round
```

Extend the existing **"Resolve SDK packages"** step's `for pkg in …` list with
`"system-images;android-$WEAR_API_LEVEL;$WEAR_TARGET;$WEAR_ARCH"`, so a wrong Wear coordinate fails
by name in seconds instead of deep inside the emulator action — the same failure `api-level: 37`
already caused once for the phone.

> **If that step reports the Wear package does not exist**, walk the API level down — 36, then 35,
> then 34 — until one resolves, put the value that resolved into `WEAR_API_LEVEL` **and**
> `ci/prepare-wear-emulator.sh`, and record which one in the task report. Do **not** ship a workflow
> whose package the preflight has said is not published: that is a gate that cannot run, and the
> whole reason this preflight step exists.

Then, after the existing phone emulator step:

```yaml
      - name: Wear journey
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: ${{ env.WEAR_API_LEVEL }}
          system-image-api-level: ${{ env.WEAR_API_LEVEL }}
          target: ${{ env.WEAR_TARGET }}
          arch: ${{ env.WEAR_ARCH }}
          profile: ${{ env.WEAR_PROFILE }}
          emulator-build: ${{ env.EMULATOR_BUILD }}
          emulator-options: >-
            -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
            -camera-back none -camera-front none
          # The phone emulator has already been shut down by the previous step (the action tears its
          # own emulator down as the step ends), so `connectedDebugAndroidTest` sees exactly one
          # device: the watch. Only :wear runs here — the phone suites ran on the phone image, and
          # running them again on a watch would prove nothing new and cost fifteen minutes.
          script: |
            ./ci/prepare-wear-emulator.sh
            ./gradlew :wear:connectedDebugAndroidTest || { adb logcat -d > wear-logcat.txt; exit 1; }
```

and add `wear-logcat.txt` and `wear/build/reports/androidTests/**` to the failure artifact upload.
Raise the job's `timeout-minutes` from 45 to **90**: two emulator boots and two APK assemblies.

- [ ] **Step 8: Hold the wear coordinates against drift, the way the phone ones already are**

`app/src/test/kotlin/app/muplay/ConventionTest.kt` — add a rule modelled on the existing *"the
emulator coordinates in e2e yml and prepare-emulator sh cannot drift apart"*:

```kotlin
  /**
   * The wear AVD is declared twice — in `.github/workflows/e2e.yml`'s job `env:` and in
   * `ci/prepare-wear-emulator.sh` — for the same reason the phone AVD is: the workflow launches it
   * and the script validates it, and neither can import from the other. If they disagree, the
   * script's own "wrong system image" check fires on a correct emulator, or worse, passes on a
   * wrong one.
   */
  @Test
  fun `the wear emulator coordinates in e2e yml and prepare-wear-emulator sh cannot drift apart`() {
    val workflow = File(repoRoot, ".github/workflows/e2e.yml").readText()
    val script = File(repoRoot, "ci/prepare-wear-emulator.sh").readText()

    val fromWorkflow = listOf("WEAR_API_LEVEL", "WEAR_TARGET", "WEAR_ARCH", "WEAR_PROFILE")
      .map { key -> Regex("""$key:\s*"?([^"\s]+)"?""").find(workflow)?.groupValues?.get(1) }
    val fromScript = listOf("WEAR_API_LEVEL", "WEAR_TARGET", "WEAR_ARCH", "WEAR_PROFILE")
      .map { key -> Regex("""readonly $key=([^\s]+)""").find(script)?.groupValues?.get(1) }

    assertThat(fromWorkflow).doesNotContainNull()
    assertThat(fromWorkflow).isEqualTo(fromScript)

    // And the action really reads the job env rather than carrying a literal beside it -- the same
    // second half the phone rule already asserts.
    assertThat(workflow).contains("api-level: \${{ env.WEAR_API_LEVEL }}")
    assertThat(workflow).contains("profile: \${{ env.WEAR_PROFILE }}")
  }
```

- [ ] **Step 9: Run it, prove it can fail, commit**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
# a Wear AVD, created with the coordinates Step 7 resolved:
$ANDROID_HOME/emulator/emulator -avd muplaywear -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot &
./ci/prepare-wear-emulator.sh
./gradlew :wear:connectedDebugAndroidTest
```

Expected: PASS. Then:

1. Run `./ci/prepare-wear-emulator.sh` against the **phone** emulator. Expect it to fail with the
   `ro.build.characteristics` message. **Record that output** — it is the check that stops a green,
   worthless run.
2. Delete the `putString(BrowseSurfaces.HINT_KEY, …)` line from `WearBrowser.connect`. Expect
   `theWatchGetsTheWatchTreeAndNotThePhoneOne` to fail with the phone's five tabs. **This is the
   only test in the build that can see that line.**
3. Change `WEAR_API_LEVEL` in the workflow only. Expect `the wear emulator coordinates … cannot
   drift apart` to fail in Tier 1.
4. Point `WearBrowser`'s `SessionToken` at a `ComponentName` that does not exist. Expect
   `theWatchCanBrowseDownToATrack` to fail on connection rather than to hang — if it hangs, add a
   timeout to `connect` and say so.

```bash
git add settings.gradle.kts gradle build.gradle.kts build-logic wear ci .github/workflows/e2e.yml app/src/test
git commit -m "feat(wear): a standalone watch app on the same playback service"
```

---

## Task 9: The watch surface — Wear Material3, Horologist, progress, and the audio-output trap

**Files:**
- Modify: `gradle/libs.versions.toml`, `wear/build.gradle.kts`
- Create: `wear/src/main/kotlin/app/muplay/wear/audio/WatchAudioOutput.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/audio/HorologistWatchAudioOutput.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseUiState.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseViewModel.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseScreen.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerUiState.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerViewModel.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/player/WearPlayerScreen.kt`
- Replace: `wear/src/main/kotlin/app/muplay/wear/WearApp.kt`
- Create: `wear/src/main/kotlin/app/muplay/wear/di/WearModule.kt`
- Test: `wear/src/test/kotlin/app/muplay/wear/audio/WatchAudioStateTest.kt`
- Test: `wear/src/test/kotlin/app/muplay/wear/player/WearPlayerUiStateTest.kt`
- Test: `wear/src/androidTest/kotlin/app/muplay/wear/WearUiJourneyTest.kt`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: `WearBrowser` (Task 8), `PlaybackConnection.state`/`.controller()` and
  `PlaybackState(isPlaying, isBuffering, mediaId, title, artist, albumTitle, artworkUri, positionMs,
  durationMs, hasNext, hasPrevious)` (Plan 3 Task 5) **plus `mediaType` and `speed`** (Plan 4
  Task 7), `BrowseId` (Task 1), `BrowseExtras` (Task 4), `MuPlayTheme`-equivalent colours from
  `:core:designsystem` where they apply (Plan 1).
- Produces:
  - `enum class WatchAudioKind { BLUETOOTH, WATCH_SPEAKER, NONE }`
  - `data class WatchAudioState(name, kind, volumePercent)` with `val isSuitableForPlayback: Boolean`
  - `interface WatchAudioOutput` with `val state: StateFlow<WatchAudioState>`,
    `fun ensureOutputThenPlay(play: () -> Unit)`, `fun openOutputPicker()`
  - `class HorologistWatchAudioOutput @Inject constructor(...) : WatchAudioOutput`
  - sealed `WearBrowseUiState` — `Loading`, `NotConfigured`, `Content(title, rows)`;
    `data class WearBrowseRow(mediaId, title, subtitle, isBrowsable, isPlayable, completionPercent)`
  - `@HiltViewModel class WearBrowseViewModel` with `uiState: StateFlow<WearBrowseUiState>`,
    `val canGoBack: StateFlow<Boolean>`, `fun open(row)`, `fun back(): Boolean`
  - sealed `WearPlayerUiState` — `NothingPlaying`, `Content(title, subtitle, positionLabel,
    remainingLabel, progress, isPlaying, hasNext, hasPrevious, audio)`
  - `internal fun wearPlayerUiState(playback: PlaybackState, audio: WatchAudioState): WearPlayerUiState`
  - `@HiltViewModel class WearPlayerViewModel` with `uiState`, `fun playPause()`, `fun next()`,
    `fun previous()`, `fun openOutputPicker()`
  - `WearApp()`, `WearBrowseScreen(...)`, `WearPlayerScreen(...)` and the label constants
    `PLAY_LABEL`, `PAUSE_LABEL`, `NEXT_LABEL`, `PREVIOUS_LABEL`, `OUTPUT_LABEL`,
    `NOTHING_PLAYING_LABEL`, `NOT_CONFIGURED_LABEL`

### The trap Horologist exists to solve, and why it must not be able to block a test

**Wear OS refuses to be a loudspeaker.** Google's own guidance is that a watch must not start media
playback on its built-in speaker without the user explicitly choosing it, and Horologist's
`media3-backend` implements that: `WearConfiguredPlayer` intercepts `play()` and, if no Bluetooth
output is connected, opens the output picker instead of playing.

That is correct behaviour and it is also **a hard stop for CI**: a headless Wear emulator has no
Bluetooth audio device and never will, so a `play()` routed through the real selector never plays,
and `WearSessionJourneyTest`'s "the position advances" assertion would be unreachable.

The resolution is the one spec §10 already sanctions — *"Fakes … only where the real thing cannot
run: an injected `Clock`, a severed socket, a forced 429"*. A headless emulator with no Bluetooth
radio is that case exactly. But the fake is kept **as small as the trap allows**:

| Piece | Where it runs | Verified by |
|---|---|---|
| `WatchAudioState.isSuitableForPlayback` | pure Kotlin | **Tier 1**, at all three `WatchAudioKind` values |
| `wearPlayerUiState` mapping | pure Kotlin | **Tier 1**, field by field, two observations each |
| `HorologistWatchAudioOutput.state` | the watch | **Tier 2 (wear image)** — the emulator does report an output device |
| the whole UI, browse and transport | the watch | **Tier 2 (wear image)**, Compose UI tests |
| `ensureOutputThenPlay`'s *"no Bluetooth, open the picker"* branch | a real watch | **nothing.** Task 11's manual checklist. |

That last row is the second and last unverifiable region in this plan, after
`DefaultSurfaceResolver`'s single expression. It is **one `if`**, over a value whose two sides are
each tested, and no gate is written for it.

### The player screen shows a position, and a label is not a position

A watch player that renders `"0:00"` forever looks exactly like one that works, in a screenshot. So
`WearUiJourneyTest` reads the position label **twice, separated by real time, and asserts the second
differs from the first** — the Compose-node version of "the position advanced". A label whose text
is a constant fails it; a player that renders silence fails it.

- [ ] **Step 1: Resolve and pin Horologist**

Spec §7 names the modules and warns that **Horologist is perpetually 0.x**, which is why its surface
is confined to one file below.

```bash
for a in horologist-audio horologist-audio-ui horologist-media3-backend; do
  echo -n "com.google.android.horologist:$a -> "
  curl -s "https://repo1.maven.org/maven2/com/google/android/horologist/$a/maven-metadata.xml" \
    | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'
done
```

`gradle/libs.versions.toml` — add with **the value that command printed**:

```toml
[versions]
# Perpetually 0.x by spec section 7's own warning. Everything this project uses from it lives in
# `wear/src/main/kotlin/app/muplay/wear/audio/HorologistWatchAudioOutput.kt` and nowhere else, so a
# breaking 0.x bump is a one-file change rather than a search across a module.
horologist = "<the <latest> printed above>"

[libraries]
horologist-audio           = { module = "com.google.android.horologist:horologist-audio", version.ref = "horologist" }
horologist-audio-ui        = { module = "com.google.android.horologist:horologist-audio-ui", version.ref = "horologist" }
horologist-media3-backend  = { module = "com.google.android.horologist:horologist-media3-backend", version.ref = "horologist" }
```

`wear/build.gradle.kts` — add the three to `dependencies`. **Not the umbrella
`horologist-compose-layout` or `horologist-media-ui`**: spec §7 says the specific modules, and an
unused dependency is what the constraints ban by name.

Verify: `./gradlew :wear:dependencies --configuration debugRuntimeClasspath | grep horologist`

- [ ] **Step 2: Write the two failing JVM tests**

`wear/src/test/kotlin/app/muplay/wear/audio/WatchAudioStateTest.kt`:

```kotlin
package app.muplay.wear.audio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WatchAudioStateTest {

  @Test
  fun `only a bluetooth output is suitable for playback`() {
    // All three values, asserted as an exact list. Google's rule is that a watch must not start
    // playing on its own speaker unasked; the watch speaker existing is not the same as it being
    // an acceptable destination.
    assertThat(
      WatchAudioKind.entries.map { WatchAudioState("out", it, volumePercent = 50).isSuitableForPlayback },
    ).containsExactly(true, false, false)
  }

  @Test
  fun `the state carries the output's own name and volume`() {
    // Two observations per field, so neither can be a constant.
    val states = listOf(
      WatchAudioState("Pixel Buds", WatchAudioKind.BLUETOOTH, 80),
      WatchAudioState("Watch speaker", WatchAudioKind.WATCH_SPEAKER, 20),
    )

    assertThat(states.map { it.name }).containsExactly("Pixel Buds", "Watch speaker")
    assertThat(states.map { it.volumePercent }).containsExactly(80, 20)
  }
}
```

`wear/src/test/kotlin/app/muplay/wear/player/WearPlayerUiStateTest.kt`:

```kotlin
package app.muplay.wear.player

import app.muplay.media.PlaybackState
import app.muplay.wear.audio.WatchAudioKind
import app.muplay.wear.audio.WatchAudioState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * `PlaybackState` -> what a 45 mm screen shows.
 *
 * Every field asserted against **two different inputs**, because a mapping with one observation per
 * field is satisfied by a constant — the defect this project has now found six times.
 */
class WearPlayerUiStateTest {

  @Test
  fun `nothing playing is its own state, not a Content with blanks`() {
    assertThat(wearPlayerUiState(PlaybackState.NOTHING_PLAYING, SPEAKER))
      .isEqualTo(WearPlayerUiState.NothingPlaying)
  }

  @Test
  fun `every displayed field comes from the playback state`() {
    val first = wearPlayerUiState(playing(title = "Track 1", artist = "Test Artist", position = 65_000, duration = 300_000), BUDS)
    val second = wearPlayerUiState(playing(title = "Chapter 2", artist = "Test Author", position = 3_000, duration = 15_000), SPEAKER)

    assertThat(listOf(first, second).map { (it as WearPlayerUiState.Content).title })
      .containsExactly("Track 1", "Chapter 2")
    assertThat(listOf(first, second).map { (it as WearPlayerUiState.Content).subtitle })
      .containsExactly("Test Artist", "Test Author")
    assertThat(listOf(first, second).map { (it as WearPlayerUiState.Content).positionLabel })
      .containsExactly("1:05", "0:03")
    assertThat(listOf(first, second).map { (it as WearPlayerUiState.Content).remainingLabel })
      .containsExactly("-3:55", "-0:12")
    assertThat(listOf(first, second).map { (it as WearPlayerUiState.Content).audio.name })
      .containsExactly("Pixel Buds", "Watch speaker")
  }

  @Test
  fun `progress is the position over the duration, and never divides by zero`() {
    val known = wearPlayerUiState(playing(position = 30_000, duration = 120_000), BUDS)
    val unknown = wearPlayerUiState(playing(position = 30_000, duration = 0), BUDS)
    val past = wearPlayerUiState(playing(position = 130_000, duration = 120_000), BUDS)

    assertThat((known as WearPlayerUiState.Content).progress).isEqualTo(0.25f, within(1e-6f))
    // A live-transcode stream has no Content-Length and therefore no duration (spec section 4);
    // a NaN here becomes a crash inside a progress indicator's draw pass.
    assertThat((unknown as WearPlayerUiState.Content).progress).isEqualTo(0f)
    assertThat((past as WearPlayerUiState.Content).progress).isEqualTo(1f)
  }

  @Test
  fun `transport availability is passed through rather than assumed`() {
    val both = wearPlayerUiState(playing(hasNext = true, hasPrevious = true), BUDS) as WearPlayerUiState.Content
    val neither = wearPlayerUiState(playing(hasNext = false, hasPrevious = false), BUDS) as WearPlayerUiState.Content

    assertThat(listOf(both.hasNext, both.hasPrevious, neither.hasNext, neither.hasPrevious))
      .containsExactly(true, true, false, false)
  }

  @Test
  fun `a title the server never sent reads as the media id rather than as an empty row`() {
    val state = wearPlayerUiState(playing(title = null, artist = null), BUDS) as WearPlayerUiState.Content

    assertThat(state.title).isEqualTo("tr-1")
    assertThat(state.subtitle).isEmpty()
  }

  private companion object {
    val BUDS = WatchAudioState("Pixel Buds", WatchAudioKind.BLUETOOTH, 80)
    val SPEAKER = WatchAudioState("Watch speaker", WatchAudioKind.WATCH_SPEAKER, 20)

    fun playing(
      title: String? = "Track 1",
      artist: String? = "Test Artist",
      position: Long = 0,
      duration: Long = 300_000,
      hasNext: Boolean = false,
      hasPrevious: Boolean = false,
    ) = PlaybackState(
      isPlaying = true,
      isBuffering = false,
      mediaId = "tr-1",
      title = title,
      artist = artist,
      albumTitle = "Test Album",
      artworkUri = null,
      positionMs = position,
      durationMs = duration,
      hasNext = hasNext,
      hasPrevious = hasPrevious,
    )
  }
}
```

> `PlaybackState`'s constructor gains `mediaType` and `speed` in Plan 4 Task 7. **Read the real
> declaration** and add the two arguments to `playing(...)` above; do not change the assertions.

- [ ] **Step 3: Run them to verify they fail, then implement the pure halves**

Run: `./gradlew :wear:testDebugUnitTest` — FAIL, `Unresolved reference: WatchAudioState`.

`wear/src/main/kotlin/app/muplay/wear/audio/WatchAudioOutput.kt`:

```kotlin
package app.muplay.wear.audio

import kotlinx.coroutines.flow.StateFlow

/** What kind of thing the watch's audio is currently going to. */
enum class WatchAudioKind { BLUETOOTH, WATCH_SPEAKER, NONE }

/**
 * The watch's current audio output.
 *
 * [isSuitableForPlayback] is the whole rule, and it is a **pure property** rather than a call into
 * Horologist precisely so both of its answers are testable: Google's Wear guidance is that a watch
 * must not start media playback on its built-in speaker without the user choosing it, so the
 * speaker existing is not the same as it being an acceptable destination.
 */
data class WatchAudioState(
  val name: String,
  val kind: WatchAudioKind,
  val volumePercent: Int,
) {
  val isSuitableForPlayback: Boolean get() = kind == WatchAudioKind.BLUETOOTH

  companion object {
    val NONE = WatchAudioState(name = "", kind = WatchAudioKind.NONE, volumePercent = 0)
  }
}

/**
 * The **entire** Horologist surface of this project, behind one interface.
 *
 * Spec section 7 warns that Horologist is *"actively maintained but perpetually 0.x"*, so exactly
 * one implementation class touches it and everything else — the ViewModels, the screens, the tests
 * — talks to this. A breaking 0.x bump is then a one-file change.
 */
interface WatchAudioOutput {
  val state: StateFlow<WatchAudioState>

  /**
   * Starts playback if the current output can take it, and otherwise asks the user to pick one.
   *
   * The `if` inside the real implementation is the one branch in this plan that **no gate covers**:
   * a headless CI watch emulator has no Bluetooth radio, so its false side is unreachable there and
   * its true side is unreachable in production without one. Both sides of the *value* it branches
   * on are tested (`WatchAudioStateTest`); the branch itself is Task 11's manual checklist.
   */
  fun ensureOutputThenPlay(play: () -> Unit)

  fun openOutputPicker()
}
```

`wear/src/main/kotlin/app/muplay/wear/player/WearPlayerUiState.kt`:

```kotlin
package app.muplay.wear.player

import app.muplay.media.PlaybackState
import app.muplay.wear.audio.WatchAudioState
import kotlin.math.abs

/** What the watch's player screen shows. A sealed interface, per the project's Kotlin discipline. */
sealed interface WearPlayerUiState {

  data object NothingPlaying : WearPlayerUiState

  data class Content(
    val title: String,
    val subtitle: String,
    val positionLabel: String,
    val remainingLabel: String,
    val progress: Float,
    val isPlaying: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val audio: WatchAudioState,
  ) : WearPlayerUiState
}

/**
 * The mapping, as a pure function so it can be tested without a device.
 *
 * `durationMs == 0` is a reachable, ordinary state and not an error: Plan 3 maps Media3's
 * `C.TIME_UNSET` to `0`, and spec section 4 records that a **live transcode returns no
 * `Content-Length` at all**. Dividing by it would put a `NaN` into a progress indicator's draw pass.
 */
internal fun wearPlayerUiState(
  playback: PlaybackState,
  audio: WatchAudioState,
): WearPlayerUiState {
  val mediaId = playback.mediaId ?: return WearPlayerUiState.NothingPlaying
  val remaining = (playback.durationMs - playback.positionMs).coerceAtLeast(0L)
  return WearPlayerUiState.Content(
    // A server that sent no title still has to render as something a finger can aim at.
    title = playback.title?.takeIf(String::isNotBlank) ?: mediaId,
    subtitle = playback.artist?.takeIf(String::isNotBlank).orEmpty(),
    positionLabel = clockLabel(playback.positionMs),
    remainingLabel = "-${clockLabel(remaining)}",
    progress = if (playback.durationMs <= 0L) {
      0f
    } else {
      (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
    },
    isPlaying = playback.isPlaying,
    hasNext = playback.hasNext,
    hasPrevious = playback.hasPrevious,
    audio = audio,
  )
}

/** `1:05`, `12:34`, `1:02:03`. Negative input is clamped rather than rendered. */
internal fun clockLabel(millis: Long): String {
  val total = abs(millis) / 1_000
  val hours = total / 3_600
  val minutes = (total % 3_600) / 60
  val seconds = total % 60
  return if (hours > 0) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%d:%02d".format(minutes, seconds)
  }
}
```

Run: `./gradlew :wear:testDebugUnitTest` — PASS.

- [ ] **Step 4: Implement the Horologist adapter — the one file that touches 0.x API**

`wear/src/main/kotlin/app/muplay/wear/audio/HorologistWatchAudioOutput.kt`:

```kotlin
package app.muplay.wear.audio

import android.content.Context
import com.google.android.horologist.audio.SystemAudioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The only class in this project that imports Horologist.
 *
 * Spec section 7 asks for `horologist-audio`/`-audio-ui`/`-media3-backend` specifically and warns
 * that the library is perpetually 0.x, so every one of those imports is here and nowhere else.
 *
 * **Two shapes to confirm against the resolved version before assuming they compile** — Step 1's
 * `curl` printed which version that is:
 *
 * 1. `SystemAudioRepository.fromContext(context)`, and its `audioOutput` / `volumeState` flows.
 *    Older releases spell the factory `SystemAudioRepository(context)`.
 * 2. The `AudioOutput` subtype names (`AudioOutput.BluetoothHeadset`, `AudioOutput.WatchSpeaker`,
 *    `AudioOutput.None`) and the launcher for the output picker
 *    (`com.google.android.horologist.audio.ui.…` / `AudioOutputSelector`).
 *
 * If a name has moved, fix it **here** and change nothing else: [WatchAudioOutput] is the seam that
 * makes that true.
 */
@Singleton
class HorologistWatchAudioOutput @Inject constructor(
  @ApplicationContext private val context: Context,
) : WatchAudioOutput {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val repository = SystemAudioRepository.fromContext(context)
  private val _state = MutableStateFlow(WatchAudioState.NONE)
  override val state: StateFlow<WatchAudioState> = _state.asStateFlow()

  init {
    scope.launch {
      repository.audioOutput.collect { output ->
        _state.value = WatchAudioState(
          name = output.name,
          kind = kindOf(output),
          volumePercent = repository.volumeState.value.let { volume ->
            if (volume.max <= 0) 0 else volume.current * 100 / volume.max
          },
        )
      }
    }
  }

  /**
   * The branch no gate in this repository covers.
   *
   * A headless CI watch emulator has no Bluetooth radio, so `isSuitableForPlayback` is `false`
   * there and playing would open a picker forever; a real watch with buds paired never takes the
   * other side. Both **values** are tested (`WatchAudioStateTest`); this `if` is Task 11's manual
   * checklist item, and no gate is written for it — a gate that cannot run is worse than none.
   */
  override fun ensureOutputThenPlay(play: () -> Unit) {
    if (state.value.isSuitableForPlayback) play() else openOutputPicker()
  }

  override fun openOutputPicker() {
    repository.launchOutputSelection(closeOnConnect = true)
  }
}
```

`wear/src/main/kotlin/app/muplay/wear/di/WearModule.kt`:

```kotlin
package app.muplay.wear.di

import app.muplay.wear.audio.HorologistWatchAudioOutput
import app.muplay.wear.audio.WatchAudioOutput
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WearModule {

  /**
   * The production output gate. `WearUiJourneyTest` replaces it — see that file for the
   * justification, which is spec section 10's rung 4: *"only where the real thing cannot run"*, and
   * a headless emulator with no Bluetooth radio is that case.
   */
  @Binds
  @Singleton
  abstract fun bindWatchAudioOutput(impl: HorologistWatchAudioOutput): WatchAudioOutput
}
```

- [ ] **Step 5: The two ViewModels and the two screens**

`wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseUiState.kt`:

```kotlin
package app.muplay.wear.browse

/** One row of the watch's browse list. Flat, because a 45 mm screen has room for a title and a line. */
data class WearBrowseRow(
  val mediaId: String,
  val title: String,
  val subtitle: String,
  val isBrowsable: Boolean,
  val isPlayable: Boolean,
  /** `null` unless the item carries Android Auto's completion extras — books do; tracks do not. */
  val completionPercent: Int?,
)

sealed interface WearBrowseUiState {
  data object Loading : WearBrowseUiState
  data object NotConfigured : WearBrowseUiState
  data class Content(val title: String, val rows: List<WearBrowseRow>) : WearBrowseUiState
}
```

`wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseViewModel.kt`:

```kotlin
package app.muplay.wear.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import app.muplay.model.browse.BrowseExtras
import app.muplay.model.browse.BrowseId
import app.muplay.wear.WearBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The watch's browse stack.
 *
 * **No navigation library.** Task 8 recorded the decision: this app's navigation is Navigation 3
 * (spec section 9) and `androidx.wear.compose:compose-navigation` is built on Navigation 2, so
 * putting both in one build to serve a homogeneous stack of browse ids would be a real cost for no
 * benefit. The stack *is* a `List<String>` of media ids, and the back gesture pops it.
 */
@HiltViewModel
class WearBrowseViewModel @Inject constructor(
  private val browser: WearBrowser,
) : ViewModel() {

  private val stack = ArrayDeque(listOf(BrowseId.Root.encode() to "MuPlay"))
  private val _uiState = MutableStateFlow<WearBrowseUiState>(WearBrowseUiState.Loading)
  val uiState: StateFlow<WearBrowseUiState> = _uiState.asStateFlow()

  /**
   * A `StateFlow`, not a `val` with a getter.
   *
   * `WearApp` reads it to decide whether its `BackHandler` is enabled, and a plain getter over a
   * mutable `ArrayDeque` is not Compose state: the handler would keep whatever value it saw at the
   * composition that created it, and the back gesture would stop popping one level too early or too
   * late. This is the kind of bug a screenshot cannot show and a UI test can, which is why Task 9's
   * back test navigates **down and then up** rather than only down.
   */
  private val _canGoBack = MutableStateFlow(false)
  val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

  init { load() }

  fun open(row: WearBrowseRow) {
    if (!row.isBrowsable) return
    stack.addLast(row.mediaId to row.title)
    load()
  }

  /** Returns true if it consumed the gesture, so the screen knows whether to let the OS dismiss. */
  fun back(): Boolean {
    if (stack.size <= 1) return false
    stack.removeLast()
    load()
    return true
  }

  private fun load() {
    val (mediaId, title) = stack.last()
    _canGoBack.value = stack.size > 1
    _uiState.value = WearBrowseUiState.Loading
    viewModelScope.launch {
      val children = runCatching { browser.children(mediaId) }.getOrDefault(emptyList())
      _uiState.value = when {
        // An empty root means nothing has been tagged yet, which on a watch is the "set this up on
        // your phone" case rather than "your library is empty".
        children.isEmpty() && mediaId == BrowseId.Root.encode() -> WearBrowseUiState.NotConfigured
        else -> WearBrowseUiState.Content(title, children.map(::rowOf))
      }
    }
  }

  private fun rowOf(item: MediaItem): WearBrowseRow {
    val extras = item.mediaMetadata.extras
    val hasCompletion = extras?.containsKey(BrowseExtras.COMPLETION_PERCENTAGE) == true
    return WearBrowseRow(
      mediaId = item.mediaId,
      title = item.mediaMetadata.title?.toString().orEmpty(),
      subtitle = item.mediaMetadata.subtitle?.toString().orEmpty(),
      isBrowsable = item.mediaMetadata.isBrowsable == true,
      isPlayable = item.mediaMetadata.isPlayable == true,
      completionPercent = if (hasCompletion) {
        (extras.getDouble(BrowseExtras.COMPLETION_PERCENTAGE) * 100).toInt()
      } else {
        null
      },
    )
  }
}
```

`wear/src/main/kotlin/app/muplay/wear/player/WearPlayerViewModel.kt`:

```kotlin
package app.muplay.wear.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muplay.media.PlaybackConnection
import app.muplay.wear.audio.WatchAudioOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WearPlayerViewModel @Inject constructor(
  private val connection: PlaybackConnection,
  private val audioOutput: WatchAudioOutput,
) : ViewModel() {

  val uiState: StateFlow<WearPlayerUiState> =
    combine(connection.state, audioOutput.state, ::wearPlayerUiState)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearPlayerUiState.NothingPlaying)

  /**
   * Play through the output gate, pause directly.
   *
   * Only *starting* needs an output: pausing something already audible cannot be routed to a
   * speaker nobody chose.
   */
  fun playPause() {
    viewModelScope.launch {
      val controller = connection.controller()
      if (controller.isPlaying) controller.pause() else audioOutput.ensureOutputThenPlay(controller::play)
    }
  }

  fun next() {
    viewModelScope.launch { connection.controller().seekToNextMediaItem() }
  }

  fun previous() {
    viewModelScope.launch { connection.controller().seekToPreviousMediaItem() }
  }

  fun openOutputPicker() = audioOutput.openOutputPicker()
}
```

`wear/src/main/kotlin/app/muplay/wear/browse/WearBrowseScreen.kt`:

```kotlin
package app.muplay.wear.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

const val NOT_CONFIGURED_LABEL = "Open MuPlay on your phone to sign in"
const val BROWSE_LIST_TAG = "wear-browse-list"

/**
 * The watch's browse list.
 *
 * `ScalingLazyColumn` from `androidx.wear.compose:compose-foundation` with Material3 components
 * inside it: the foundation list is the one every Wear release ships, while the Material3 list
 * wrappers have moved between versions. If the resolved `compose-material3` offers its own list
 * container, use it — the layout decision is "a scaling list", not the type's name.
 */
@Composable
fun WearBrowseScreen(
  onPlay: (WearBrowseRow) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: WearBrowseViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val listState = rememberScalingLazyListState()

  ScreenScaffold(scrollState = listState, modifier = modifier) {
    ScalingLazyColumn(state = listState, modifier = Modifier.testTag(BROWSE_LIST_TAG)) {
      when (val current = state) {
        WearBrowseUiState.Loading -> Unit
        WearBrowseUiState.NotConfigured -> item { Text(NOT_CONFIGURED_LABEL) }
        is WearBrowseUiState.Content -> {
          item { ListHeader { Text(current.title) } }
          items(current.rows, key = WearBrowseRow::mediaId) { row ->
            Button(
              onClick = { if (row.isBrowsable) viewModel.open(row) else onPlay(row) },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(row.title) },
              secondaryLabel = {
                // The remaining-time line BrowseTree built, plus the car's own completion
                // percentage where there is one -- the same data, on a smaller screen.
                Text(
                  row.completionPercent
                    ?.let { percent -> "${row.subtitle} · $percent%" }
                    ?: row.subtitle,
                )
              },
            )
          }
        }
      }
    }
  }
}
```

`wear/src/main/kotlin/app/muplay/wear/player/WearPlayerScreen.kt`:

```kotlin
package app.muplay.wear.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

const val PLAY_LABEL = "Play"
const val PAUSE_LABEL = "Pause"
const val NEXT_LABEL = "Next"
const val PREVIOUS_LABEL = "Previous"
const val OUTPUT_LABEL = "Audio output"
const val NOTHING_PLAYING_LABEL = "Nothing playing"
const val POSITION_TAG = "wear-position"

@Composable
fun WearPlayerScreen(
  modifier: Modifier = Modifier,
  viewModel: WearPlayerViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  ScreenScaffold(modifier = modifier) {
    when (val current = state) {
      WearPlayerUiState.NothingPlaying -> Text(NOTHING_PLAYING_LABEL)
      is WearPlayerUiState.Content -> Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(current.title, maxLines = 2)
        Text(current.subtitle, maxLines = 1)
        // Two labels, not one: a listener wants "how far in" and "how much left", and the second
        // is what an audiobook listener actually reads.
        Text(
          text = "${current.positionLabel} / ${current.remainingLabel}",
          modifier = Modifier.testTag(POSITION_TAG),
        )
        CircularProgressIndicator(progress = { current.progress })
        Row(horizontalArrangement = Arrangement.Center) {
          Button(
            onClick = viewModel::previous,
            enabled = current.hasPrevious,
            modifier = Modifier.semantics { contentDescription = PREVIOUS_LABEL },
            label = { Text("<") },
          )
          Button(
            onClick = viewModel::playPause,
            modifier = Modifier.semantics {
              contentDescription = if (current.isPlaying) PAUSE_LABEL else PLAY_LABEL
            },
            label = { Text(if (current.isPlaying) "||" else ">") },
          )
          Button(
            onClick = viewModel::next,
            enabled = current.hasNext,
            modifier = Modifier.semantics { contentDescription = NEXT_LABEL },
            label = { Text(">|") },
          )
        }
        Button(
          onClick = viewModel::openOutputPicker,
          modifier = Modifier.semantics { contentDescription = OUTPUT_LABEL },
          label = { Text(current.audio.name.ifBlank { OUTPUT_LABEL }) },
        )
      }
    }
  }
}
```

`wear/src/main/kotlin/app/muplay/wear/WearApp.kt` — replace Task 8's placeholder:

```kotlin
package app.muplay.wear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import app.muplay.wear.browse.WearBrowseScreen
import app.muplay.wear.browse.WearBrowseViewModel
import app.muplay.wear.player.WearPlayerScreen
import app.muplay.wear.player.WearPlayerViewModel

/**
 * Two screens and a back stack, with no navigation library — Task 8's recorded decision.
 *
 * `BackHandler` is what makes the watch's swipe-to-dismiss gesture pop a browse level instead of
 * closing the app, which is the constraints' *"predictive back is default-on and must be
 * implemented"* on this surface: Wear routes the dismiss gesture through the ordinary back
 * dispatcher, so handling it here handles both the gesture and a hardware back key.
 */
@Composable
fun WearApp() {
  MaterialTheme {
    AppScaffold {
      val browse: WearBrowseViewModel = hiltViewModel()
      val player: WearPlayerViewModel = hiltViewModel()
      var showingPlayer by remember { mutableStateOf(false) }
      val canGoBack by browse.canGoBack.collectAsStateWithLifecycle()

      BackHandler(enabled = showingPlayer || canGoBack) {
        if (showingPlayer) showingPlayer = false else browse.back()
      }

      if (showingPlayer) {
        WearPlayerScreen(viewModel = player)
      } else {
        WearBrowseScreen(
          onPlay = { row ->
            player.playRow(row.mediaId)
            showingPlayer = true
          },
          viewModel = browse,
        )
      }
    }
  }
}
```

and add to `WearPlayerViewModel`:

```kotlin
  /**
   * Plays a browse row by its id.
   *
   * A bare `MediaItem` with only a media id, exactly as a car sends: `MuPlayLibraryCallback`
   * (Task 5) expands it into the queue it belongs to and `MuPlayer` supplies the position. **The
   * watch deliberately takes the same path as the car** rather than building a queue of its own —
   * one expansion rule, tested once.
   */
  fun playRow(mediaId: String) {
    viewModelScope.launch {
      val controller = connection.controller()
      controller.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId(mediaId).build())
      controller.prepare()
      audioOutput.ensureOutputThenPlay(controller::play)
    }
  }
```

- [ ] **Step 6: Write the failing UI journey**

`wear/src/androidTest/kotlin/app/muplay/wear/WearUiJourneyTest.kt`:

```kotlin
package app.muplay.wear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.wear.player.PAUSE_LABEL
import app.muplay.wear.player.POSITION_TAG
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The watch's own screen, on a real Wear emulator, against the real container.
 *
 * `WatchAudioOutput` is replaced by a hand-written permissive one for the whole suite. That is
 * spec section 10's rung 4 — *"fakes … only where the real thing cannot run"* — and the real thing
 * genuinely cannot: Google's Wear guidance forbids starting playback on the watch speaker, a
 * headless CI emulator has no Bluetooth radio, and `HorologistWatchAudioOutput` therefore opens an
 * output picker instead of playing, forever. The replaced surface is **one `if`**; everything else
 * in this suite is production code.
 */
@RunWith(AndroidJUnit4::class)
class WearUiJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<WearActivity>()

  @Test
  fun theWatchListsItsThreeRootTabsInOrder() {
    // The same three the session journey asserts over IPC, now as rendered rows -- so a UI that
    // reordered or dropped one fails here even though the tree is right.
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Continue").assertIsDisplayed()
    composeRule.onNodeWithText("Books").assertIsDisplayed()
    composeRule.onNodeWithText("Albums").assertIsDisplayed()
  }

  @Test
  fun tappingABrowsableRowGoesDownAndBackGoesUp() {
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Books").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Books").performClick()

    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Test Book").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Test Book").assertIsDisplayed()

    // The back gesture on a watch is the dismiss swipe, routed through the ordinary back
    // dispatcher -- so pressing back is the same code path the gesture takes.
    composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Albums").assertIsDisplayed()
  }

  @Test
  fun playingATrackShowsThePlayerAndThePositionAdvances() {
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Albums").performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Test Album").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Test Album").performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Track 1").performClick()

    // The button says Pause, so the player believes it is playing...
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }

    // ...and this is the assertion that a screenshot could not make. A position label read twice,
    // separated by real time. A label whose text is a constant fails; a player that renders nothing
    // fails; only audio that advanced passes.
    // The real clock, deliberately: `mainClock` drives *composition*, and the thing under test is
    // the decoder's own progress, which no test clock advances.
    val first = positionText()
    Thread.sleep(2_000)
    val second = positionText()

    assertThat(second).isNotEqualTo(first)
    assertThat(second).isNotEqualTo("0:00 / -0:00")
  }

  @Test
  fun thePlayerOffersTheTransportAndTheOutputControl() {
    playFirstTrack()

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(app.muplay.wear.player.NEXT_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(app.muplay.wear.player.PREVIOUS_LABEL).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(app.muplay.wear.player.OUTPUT_LABEL).assertIsDisplayed()
  }

  @Test
  fun pausingChangesTheButtonBackToPlay() {
    playFirstTrack()

    composeRule.onNodeWithContentDescription(PAUSE_LABEL).performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithContentDescription(app.muplay.wear.player.PLAY_LABEL)
        .fetchSemanticsNodes().isNotEmpty()
    }
    // Both states observed, so the label is proven to follow `isPlaying` rather than being a
    // constant that happened to read "Pause".
    composeRule.onNodeWithContentDescription(app.muplay.wear.player.PLAY_LABEL).assertIsDisplayed()
  }

  private fun playFirstTrack() {
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Albums").performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Test Album").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Test Album").performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Track 1").performClick()
    composeRule.waitUntil(TIMEOUT_MS) {
      composeRule.onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun positionText(): String =
    composeRule.onNodeWithTag(POSITION_TAG)
      .fetchSemanticsNode()
      .config
      .let { config ->
        config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
      }

  private companion object {
    const val TIMEOUT_MS = 40_000L
  }
}
```

> Three practical points to settle while writing this file, none of which changes a decision:
> `createAndroidComposeRule` needs the credentials seeded **before** the activity composes — do it in
> a `@Before` through `EntryPointAccessors`, exactly as `WearSessionJourneyTest` does, and if the
> activity has already composed by then add a "reload" affordance rather than sleeping. The
> permissive `WatchAudioOutput` is installed by giving `:wear` a `debug` source set binding or an
> `androidTest` Hilt replacement; **whichever you choose, it must be visible in the file that does
> it** — a fake installed somewhere a reader will not look is the same defect as a silent gate.
> `playingATrackShowsThePlayerAndThePositionAdvances` duplicates `playFirstTrack()`'s clicks inline
> rather than calling it, on purpose: that test's four steps are the subject of the test, and a
> helper would hide which click failed.

- [ ] **Step 7: Run, prove failure, measure, commit**

```bash
./gradlew :wear:testDebugUnitTest
./ci/prepare-wear-emulator.sh && ./gradlew :wear:connectedDebugAndroidTest
```

Mutations, each reverted before the next:

1. In `wearPlayerUiState`, hardcode `positionLabel = "0:00"`. Expect
   `playingATrackShowsThePlayerAndThePositionAdvances` to fail — **this is the assertion that
   separates a working watch player from a screenshot of one**.
2. In `WatchAudioState`, make `isSuitableForPlayback` always `true`. Expect
   `only a bluetooth output is suitable for playback` to fail in Tier 1.
3. In `WearBrowseViewModel.open`, drop the `if (!row.isBrowsable) return` guard. Expect
   `tappingABrowsableRowGoesDownAndBackGoesUp` to keep passing and the player test to fail once a
   track push shows an empty list — if neither fails, add an assertion that tapping a track shows
   the player.
4. In `WearApp`, remove the `BackHandler`. Expect `tappingABrowsableRowGoesDownAndBackGoesUp` to
   fail (the activity finishes instead of popping). **That is the predictive-back constraint,
   gated.**
5. In `wearPlayerUiState`, divide by `durationMs` unconditionally. Expect `progress is the position
   over the duration, and never divides by zero` to fail in Tier 1 with a `NaN`.

`build.gradle.kts` — measure `:wear` from a merged report and write its floors: **BRANCH** for
`WatchAudioState`, `wearPlayerUiState`/`clockLabel` (`WearPlayerUiStateKt`), `WearBrowseViewModel`
and `WearPlayerViewModel`; **LINE** for `WearAppKt`, `WearBrowseScreenKt`, `WearPlayerScreenKt`, per
the constraints' Compose rule. Give `HorologistWatchAudioOutput` its own **LINE** floor at the
measured value with a comment naming `ensureOutputThenPlay`'s uncovered branch and pointing at Task
11's manual checklist — **do not exclude the class**, and do not invent a number.

```bash
git add gradle wear build.gradle.kts
git commit -m "feat(wear): a watch screen that browses, plays and shows where the book is"
```

---

## Task 10: `WatchLink` — credentials and progress across the wrist, and the wire no emulator can carry

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`
- Create: `core/watchlink/build.gradle.kts`, `core/watchlink/src/main/AndroidManifest.xml`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncPayload.kt`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/ProgressMerge.kt`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchLink.kt`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncEngine.kt`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/DataLayerWatchLink.kt`
- Create: `core/watchlink/src/main/kotlin/app/muplay/watchlink/di/WatchLinkModule.kt`
- Create: `core/testing/src/main/kotlin/app/muplay/testing/InMemoryWatchLink.kt`
- Test: `core/watchlink/src/test/kotlin/app/muplay/watchlink/ProgressMergeTest.kt`
- Test: `core/watchlink/src/test/kotlin/app/muplay/watchlink/WatchSyncPayloadTest.kt`
- Test: `core/watchlink/src/androidTest/kotlin/app/muplay/watchlink/WatchSyncEngineTest.kt`
- Modify: `app/build.gradle.kts`, `wear/build.gradle.kts` and both `Application` classes
- Modify: `.github/workflows/e2e.yml`

**Interfaces:**
- Consumes: `MediaProgressEntity(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed,
  skipSilence, gainDb)` and `MediaProgressDao(upsert, find, findAll, recentlyPlayed)` — **Plan 2
  Task 1, committed** — plus `observeAll()` and `findIn(mediaIds)` from **Plan 4 Task 2**;
  `CredentialStore(save, load, clear, credentials)` — Plan 2 Task 1; `SubsonicCredentials` —
  `:core:model`; `java.time.Clock` — Plan 3 Task 8's `MediaModule`.
- Produces:
  - `@Serializable data class ProgressSnapshot(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb)`
  - `@Serializable data class WatchSyncPayload(version: Int, credentials: CredentialSnapshot?, progress: List<ProgressSnapshot>)`
    with `companion object { const val VERSION = 1; const val MAX_PROGRESS_ROWS = 200;
    fun encode(payload): ByteArray; fun decode(bytes): WatchSyncPayload? }`
  - `@Serializable data class CredentialSnapshot(baseUrl, username, password)`
  - `object ProgressMerge` with `fun updates(local, remote): List<MediaProgressEntity>` and
    `fun winner(local, remote): MediaProgressEntity`
  - `interface WatchLink` with `suspend fun publish(payload: WatchSyncPayload)` and
    `fun incoming(): Flow<WatchSyncPayload>`
  - `class DataLayerWatchLink @Inject constructor(@ApplicationContext context) : WatchLink` with
    `const val PATH_SYNC = "/muplay/sync"`
  - `class WatchSyncEngine @Inject constructor(link, credentialStore, mediaProgressDao, clock)` with
    `fun start(scope: CoroutineScope)`, `suspend fun publishLocalState()`,
    `suspend fun apply(payload: WatchSyncPayload): Int`, `fun stop()`
  - `class InMemoryWatchLink : WatchLink` (in `:core:testing`) with `val published: List<WatchSyncPayload>`
    and `suspend fun deliver(payload: WatchSyncPayload)`

### The spec tension this task resolves, and the correction it earns

Spec §2 and §4 say **"Book positions are local only. No server sync."** §7 requires **Wear OS**. The
roadmap's row for this plan says **"Car and watch playback with resume progress."** Those three
sentences cannot all be true of two devices without something in between: a phone and a watch each
holding local-only progress cannot agree on where a book is.

The resolution is that **"local only" is a statement about the *server*, not about the *device*.**
Everything spec §4 argues for it — no `createBookmark` write path, no `savePlayQueue`, no conflict
resolution against a server, no background worker, and no exposure to the milliseconds-versus-seconds
hazard — remains exactly true of a phone-to-watch replication that never speaks to Navidrome. Task 11
corrects the spec's wording so a future reader cannot read "local only" as "one device only", and
Task 10's tests assert the *absence* the phrase actually protects, with a positive control, the way
Plan 4 Task 6 did.

### `play-services-wearable`, and why dependency minimalism yields here

There is **no other API**. The Wearable Data Layer is the only sanctioned channel between a phone
app and its watch app; a raw Bluetooth socket between two apps that cannot see each other's
lifecycles is not an alternative anyone should build. So the dependency goes in, and it is contained:

- **One module** (`:core:watchlink`) declares it, and **one file** in that module imports it.
- Everything that decides anything — what crosses, when, and who wins — is behind `WatchLink` and
  is pure or Room-backed, so it is testable without a paired device.

### What CI can and cannot see, stated before the code

| Piece | Tier | What proves it |
|---|---|---|
| `ProgressMerge` | **1 (JVM)** | every rule, every boundary, both directions |
| `WatchSyncPayload` encode/decode | **1 (JVM)** | exact wire bytes, version handling, malformed input |
| `WatchSyncEngine` against a **real Room** | **2 (phone image)** | rows written, rows refused, credentials stored |
| `DataLayerWatchLink` | **nothing** | a paired phone and watch, by hand — Task 11's checklist |

`DataLayerWatchLink` is roughly sixty lines and contains no decision: it puts bytes on a path and
reads bytes off it. Every decision is upstream of it and gated. **No CI gate is written for it**, in
either tier, because a gate that cannot run is worse than no gate.

### Three rules, and the one honest limitation

1. **Last writer wins, by `lastPlayedAtEpochMs`.**
2. **A tie is broken by the greater `positionMs`.** Two devices writing in the same millisecond is
   reachable (a batch apply writes many rows at one clock read), and an arbitrary tie-break would
   make the merge non-deterministic — the same row could flip back and forth on every sync.
   "Further along wins" is also the answer a listener wants.
3. **Deletions do not replicate.** Plan 4's `restart(bookId)` clears a progress row; a cleared row is
   an absence, and an absence is indistinguishable from "this device has never seen that book". The
   consequence, stated rather than hidden: **restarting a book on the phone does not restart it on
   the watch until the watch plays it**, at which point the watch's newer row wins normally. A
   tombstone protocol would fix it and would add a table, a retention policy and a second merge rule
   for something a user does rarely and can redo on the second device in two taps.

**And a named risk: two devices' clocks are not the same clock.** `lastPlayedAtEpochMs` from a watch
is only comparable with the phone's because Wear OS forces automatic time from the paired phone, so
the skew is a network round trip rather than a user's setting. Rule 2 limits the damage when it is
not: a device whose clock is behind loses ties it should win, but never loses a position it is ahead
on. Task 11 records this in spec §12's risk table.

- [ ] **Step 1: Resolve the dependency and create the module**

```bash
echo -n "com.google.android.gms:play-services-wearable -> "
curl -s "https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-wearable/maven-metadata.xml" \
  | sed -n 's:.*<latest>\(.*\)</latest>.*:\1:p'
```

`gradle/libs.versions.toml` — add with the value printed:

```toml
[versions]
# The only API for phone-to-watch messaging. Declared by :core:watchlink alone and imported by
# exactly one file in it (DataLayerWatchLink) -- see that file's own documentation.
playServicesWearable = "<the <latest> printed above>"

[libraries]
play-services-wearable = { module = "com.google.android.gms:play-services-wearable", version.ref = "playServicesWearable" }
```

`settings.gradle.kts` — `include(":core:watchlink")`.

`core/watchlink/build.gradle.kts`:

```kotlin
plugins {
  alias(libs.plugins.muplay.android.library)
  alias(libs.plugins.muplay.android.hilt)
  alias(libs.plugins.muplay.kotlin.serialization)
}

android {
  namespace = "app.muplay.watchlink"
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:database"))
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj)
  androidTestImplementation(project(":core:testing"))
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.assertj)
}
```

`core/watchlink/src/main/AndroidManifest.xml` — empty `<manifest/>`; the Data Layer needs no
permission and no service declaration for the polling client this task builds. (A
`WearableListenerService` would need one; see Step 5 for why this uses `DataClient.addListener`
instead.)

> Use the real catalogue alias names — read `gradle/libs.versions.toml`. `libs.plugins.muplay.kotlin.serialization`
> is `KotlinSerializationConventionPlugin`'s registered id (Plan 1); if it is registered under a
> different id, use that one.

- [ ] **Step 2: Write the failing pure tests**

`core/watchlink/src/test/kotlin/app/muplay/watchlink/ProgressMergeTest.kt`:

```kotlin
package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Who wins when a phone and a watch disagree about where a book is.
 *
 * Every rule asserted **in both directions** — remote newer *and* local newer — because a merge
 * that always took the remote row passes every one-directional test there is and silently discards
 * whatever the user did on the device they are holding.
 */
class ProgressMergeTest {

  @Test
  fun `the newer row wins, whichever side it is on`() {
    val local = row("b-1", positionMs = 1_000, lastPlayedAtEpochMs = 100)
    val newerRemote = row("b-1", positionMs = 9_000, lastPlayedAtEpochMs = 200)
    val olderRemote = row("b-1", positionMs = 9_000, lastPlayedAtEpochMs = 50)

    assertThat(ProgressMerge.updates(listOf(local), listOf(newerRemote)))
      .containsExactly(newerRemote)
    assertThat(ProgressMerge.updates(listOf(local), listOf(olderRemote)))
      .isEmpty()
  }

  @Test
  fun `a tie is broken by the greater position, deterministically`() {
    // Two devices writing in the same millisecond is reachable: a batch apply writes many rows at
    // one clock read. An arbitrary tie-break makes the same row flip on every sync.
    val local = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)
    val aheadRemote = row("b-1", positionMs = 6_000, lastPlayedAtEpochMs = 100)
    val behindRemote = row("b-1", positionMs = 4_000, lastPlayedAtEpochMs = 100)
    val identicalRemote = row("b-1", positionMs = 5_000, lastPlayedAtEpochMs = 100)

    assertThat(ProgressMerge.updates(listOf(local), listOf(aheadRemote))).containsExactly(aheadRemote)
    assertThat(ProgressMerge.updates(listOf(local), listOf(behindRemote))).isEmpty()
    assertThat(ProgressMerge.updates(listOf(local), listOf(identicalRemote))).isEmpty()
  }

  @Test
  fun `a row this device has never seen is always taken`() {
    assertThat(ProgressMerge.updates(emptyList(), listOf(row("b-9", 3_000, 10))))
      .containsExactly(row("b-9", 3_000, 10))
  }

  @Test
  fun `a row the remote has never seen is left alone`() {
    // Deletions do not replicate — see this task's own header. The local row survives because the
    // remote's silence is not evidence of anything.
    val local = row("b-1", 1_000, 100)
    assertThat(ProgressMerge.updates(listOf(local), emptyList())).isEmpty()
  }

  @Test
  fun `the winning row carries its own per-item settings, not the loser's`() {
    // speed, skipSilence and gainDb belong to the row (spec section 3), so they travel with the
    // winner rather than being merged field by field.
    val local = row("b-1", 1_000, 100).copy(speed = 1.0f, skipSilence = false, gainDb = 0f)
    val remote = row("b-1", 2_000, 200).copy(speed = 1.4f, skipSilence = true, gainDb = -3f)

    val updates = ProgressMerge.updates(listOf(local), listOf(remote))

    assertThat(updates.map { it.speed }).containsExactly(1.4f)
    assertThat(updates.map { it.skipSilence }).containsExactly(true)
    assertThat(updates.map { it.gainDb }).containsExactly(-3f)
  }

  @Test
  fun `many rows are merged independently and the result is exactly the ones that changed`() {
    // Mapped and compared as an exact list, so "some rows were returned" cannot pass for "the right
    // rows were returned".
    val local = listOf(row("a", 1_000, 100), row("b", 1_000, 300), row("c", 1_000, 100))
    val remote = listOf(row("a", 2_000, 200), row("b", 2_000, 200), row("d", 5_000, 500))

    assertThat(ProgressMerge.updates(local, remote).map { it.mediaId }).containsExactly("a", "d")
  }

  @Test
  fun `winner is symmetric with updates and never invents a row`() {
    val older = row("b-1", 1_000, 100)
    val newer = row("b-1", 2_000, 200)

    assertThat(ProgressMerge.winner(older, newer)).isEqualTo(newer)
    assertThat(ProgressMerge.winner(newer, older)).isEqualTo(newer)
  }

  private companion object {
    fun row(mediaId: String, positionMs: Long, lastPlayedAtEpochMs: Long) = MediaProgressEntity(
      mediaId = mediaId,
      positionMs = positionMs,
      isFinished = false,
      lastPlayedAtEpochMs = lastPlayedAtEpochMs,
      speed = 1.0f,
      skipSilence = false,
      gainDb = 0f,
    )
  }
}
```

`core/watchlink/src/test/kotlin/app/muplay/watchlink/WatchSyncPayloadTest.kt`:

```kotlin
package app.muplay.watchlink

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WatchSyncPayloadTest {

  @Test
  fun `a payload round-trips with every field intact`() {
    val payload = WatchSyncPayload(
      version = WatchSyncPayload.VERSION,
      credentials = CredentialSnapshot("https://music.example", "luc", "hunter2"),
      progress = listOf(
        ProgressSnapshot("b-1", 11_500, false, 1_700_000_000_000, 1.4f, true, -3f),
        ProgressSnapshot("b-2", 0, true, 1_600_000_000_000, 1.0f, false, 0f),
      ),
    )

    val decoded = WatchSyncPayload.decode(WatchSyncPayload.encode(payload))

    // Field by field, and with two rows that differ in every field, so a decoder that dropped one
    // fails here rather than in a car six months later.
    assertThat(decoded).isEqualTo(payload)
    assertThat(decoded?.progress?.map { it.positionMs }).containsExactly(11_500, 0)
    assertThat(decoded?.progress?.map { it.isFinished }).containsExactly(false, true)
    assertThat(decoded?.progress?.map { it.speed }).containsExactly(1.4f, 1.0f)
    assertThat(decoded?.progress?.map { it.skipSilence }).containsExactly(true, false)
    assertThat(decoded?.progress?.map { it.gainDb }).containsExactly(-3f, 0f)
    assertThat(decoded?.credentials?.password).isEqualTo("hunter2")
  }

  @Test
  fun `the row order survives, because it is the order the sender chose`() {
    val payload = WatchSyncPayload(
      WatchSyncPayload.VERSION,
      credentials = null,
      progress = listOf(snapshot("c"), snapshot("a"), snapshot("b")),
    )

    assertThat(WatchSyncPayload.decode(WatchSyncPayload.encode(payload))?.progress?.map { it.mediaId })
      .containsExactly("c", "a", "b")
  }

  @Test
  fun `a payload from a future version is refused rather than half-read`() {
    val future = WatchSyncPayload.encode(
      WatchSyncPayload(version = WatchSyncPayload.VERSION + 1, credentials = null, progress = emptyList()),
    )

    // The other device may be a newer build of this app. Applying half of a payload whose shape
    // this build does not know is how a progress row ends up at a position nobody was ever at.
    assertThat(WatchSyncPayload.decode(future)).isNull()
  }

  @Test
  fun `malformed bytes decode to null rather than throwing into a listener`() {
    assertThat(
      listOf(ByteArray(0), "not json".toByteArray(), "{".toByteArray()).map(WatchSyncPayload::decode),
    ).containsExactly(null, null, null)
  }

  @Test
  fun `a payload with no credentials is legal and carries none`() {
    val decoded = WatchSyncPayload.decode(
      WatchSyncPayload.encode(WatchSyncPayload(WatchSyncPayload.VERSION, null, listOf(snapshot("a")))),
    )

    assertThat(decoded?.credentials).isNull()
    assertThat(decoded?.progress).hasSize(1)
  }

  private companion object {
    fun snapshot(id: String) = ProgressSnapshot(id, 1_000, false, 100, 1.0f, false, 0f)
  }
}
```

- [ ] **Step 3: Run them to verify they fail, then implement the pure halves**

Run: `./gradlew :core:watchlink:test` — FAIL, `Unresolved reference: ProgressMerge`.

`core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncPayload.kt`:

```kotlin
package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One `media_progress` row, on the wire. */
@Serializable
data class ProgressSnapshot(
  val mediaId: String,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
  val speed: Float,
  val skipSilence: Boolean,
  val gainDb: Float,
) {
  fun toEntity(): MediaProgressEntity = MediaProgressEntity(
    mediaId = mediaId,
    positionMs = positionMs,
    isFinished = isFinished,
    lastPlayedAtEpochMs = lastPlayedAtEpochMs,
    speed = speed,
    skipSilence = skipSilence,
    gainDb = gainDb,
  )

  companion object {
    fun of(entity: MediaProgressEntity) = ProgressSnapshot(
      mediaId = entity.mediaId,
      positionMs = entity.positionMs,
      isFinished = entity.isFinished,
      lastPlayedAtEpochMs = entity.lastPlayedAtEpochMs,
      speed = entity.speed,
      skipSilence = entity.skipSilence,
      gainDb = entity.gainDb,
    )
  }
}

/**
 * The server the other device should talk to, and how.
 *
 * The password crosses the wrist in cleartext **inside the payload**, and that is not a shortcut:
 * spec section 4 records that Navidrome's token auth needs the password in cleartext at request
 * time, so there is no hashed-at-rest form to send. What protects it is the channel and the store —
 * the Wearable Data Layer is encrypted between paired devices and scoped to items written by an app
 * with the same package **and signing key**, and the receiving device puts it straight into
 * `CredentialStore`, which is Android Keystore backed (spec section 4). It is never logged and
 * never written to a file.
 */
@Serializable
data class CredentialSnapshot(
  val baseUrl: String,
  val username: String,
  val password: String,
)

/**
 * Everything one device tells the other.
 *
 * One payload rather than two channels, because the two facts arrive together in the only case that
 * matters: a watch that has just been set up needs credentials *and* the positions it should show.
 */
@Serializable
data class WatchSyncPayload(
  val version: Int,
  val credentials: CredentialSnapshot?,
  val progress: List<ProgressSnapshot>,
) {
  companion object {
    /** Bumped whenever this shape changes. A payload from a newer version is refused, not guessed at. */
    const val VERSION: Int = 1

    /**
     * The Wearable Data Layer caps one data item at 100 KB. At roughly 130 bytes a row that is
     * thousands of rows, but a cap that is a measured number beats one that is discovered in the
     * field: the most recently played rows are the ones a second device can use.
     */
    const val MAX_PROGRESS_ROWS: Int = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payload: WatchSyncPayload): ByteArray =
      json.encodeToString(serializer(), payload).toByteArray()

    /**
     * `null` for anything this build cannot fully understand.
     *
     * A newer version on the other device is the case worth naming: applying the half of a payload
     * whose shape this build knows is how a progress row ends up at a position nobody was ever at,
     * and there is no user-visible signal when it happens.
     */
    fun decode(bytes: ByteArray): WatchSyncPayload? = try {
      json.decodeFromString(serializer(), bytes.decodeToString())
        .takeIf { it.version == VERSION }
    } catch (e: SerializationException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  }
}
```

`core/watchlink/src/main/kotlin/app/muplay/watchlink/ProgressMerge.kt`:

```kotlin
package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity

/**
 * Who wins when a phone and a watch disagree about where a book is.
 *
 * Three rules and one honest limitation, all argued in Task 10's own header: last writer wins by
 * `lastPlayedAtEpochMs`; a tie goes to the greater `positionMs`, deterministically; and **deletions
 * do not replicate**, because a cleared row is an absence and an absence is indistinguishable from
 * "this device has never seen that book".
 *
 * Pure, and therefore fully gated in Tier 1 — which matters more here than almost anywhere else in
 * this plan, because the transport that carries these rows is the one thing no emulator can run.
 */
object ProgressMerge {

  /** The rows [remote] should cause this device to write. Never includes a row [local] already wins. */
  fun updates(
    local: List<MediaProgressEntity>,
    remote: List<MediaProgressEntity>,
  ): List<MediaProgressEntity> {
    val byId = local.associateBy(MediaProgressEntity::mediaId)
    return remote.filter { candidate ->
      val existing = byId[candidate.mediaId] ?: return@filter true
      winner(existing, candidate) !== existing
    }
  }

  /**
   * The row that should survive.
   *
   * Returns [local] itself (by identity) when local wins, which is what lets [updates] tell "remote
   * won" from "they are equal" without comparing every field.
   */
  fun winner(local: MediaProgressEntity, remote: MediaProgressEntity): MediaProgressEntity = when {
    remote.lastPlayedAtEpochMs > local.lastPlayedAtEpochMs -> remote
    remote.lastPlayedAtEpochMs < local.lastPlayedAtEpochMs -> local
    remote.positionMs > local.positionMs -> remote
    else -> local
  }
}
```

Run: `./gradlew :core:watchlink:test` — PASS.

- [ ] **Step 4: The transport interface, the fake, and the engine**

`core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchLink.kt`:

```kotlin
package app.muplay.watchlink

import kotlinx.coroutines.flow.Flow

/**
 * The wire between a phone and its watch.
 *
 * Two methods, no decisions. Everything that decides anything — what crosses, when, and who wins —
 * is in [WatchSyncEngine] and [ProgressMerge], both of which are gated. This interface exists so
 * that the ~60 lines which genuinely need a paired phone and watch are ~60 lines and not a module.
 */
interface WatchLink {

  /** Makes [payload] this device's current published state. Replaces whatever it published before. */
  suspend fun publish(payload: WatchSyncPayload)

  /** What the peer has published, now and whenever it changes. */
  fun incoming(): Flow<WatchSyncPayload>
}
```

`core/testing/src/main/kotlin/app/muplay/testing/InMemoryWatchLink.kt`:

```kotlin
package app.muplay.testing

import app.muplay.watchlink.WatchLink
import app.muplay.watchlink.WatchSyncPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A `WatchLink` that goes nowhere.
 *
 * Spec section 10's rung 4 — *"only where the real thing cannot run"* — and the real thing needs two
 * physically paired devices, which no CI runner has. Hand-written, with no mock framework anywhere
 * near it, and it records what was published so a test can assert on the **arguments**, not merely
 * that publishing happened.
 */
class InMemoryWatchLink : WatchLink {

  private val _published = mutableListOf<WatchSyncPayload>()
  val published: List<WatchSyncPayload> get() = _published.toList()

  private val delivered = MutableSharedFlow<WatchSyncPayload>(replay = 1, extraBufferCapacity = 8)

  override suspend fun publish(payload: WatchSyncPayload) {
    _published += payload
  }

  override fun incoming(): Flow<WatchSyncPayload> = delivered.asSharedFlow()

  /** Pretends the peer published [payload]. */
  suspend fun deliver(payload: WatchSyncPayload) {
    delivered.emit(payload)
  }
}
```

> `:core:testing` gaining a dependency on `:core:watchlink` is the one direction to check: if that
> creates a cycle (because `:core:watchlink`'s `androidTest` uses `:core:testing`), put
> `InMemoryWatchLink` in `core/watchlink/src/testFixtures` or, simpler, in
> `core/watchlink/src/androidTest` and duplicate nothing. **Decide once and record it**; a fake that
> exists in two places is a fake that disagrees with itself.

`core/watchlink/src/main/kotlin/app/muplay/watchlink/WatchSyncEngine.kt`:

```kotlin
package app.muplay.watchlink

import app.muplay.database.CredentialStore
import app.muplay.database.dao.MediaProgressDao
import app.muplay.model.SubsonicCredentials
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a phone and its watch agreeing about where every book is.
 *
 * Never talks to Navidrome. Spec sections 4 and 11 rule out **server** progress sync, and every
 * reason they give — no `createBookmark` write path, no `savePlayQueue`, no conflict resolution
 * against a server, no background worker, no exposure to the milliseconds-versus-seconds hazard —
 * is still true of a replication that goes phone-to-watch and nowhere else. `WatchSyncEngineTest`
 * asserts that absence with a positive control, the way Plan 4 Task 6 did.
 */
@Singleton
class WatchSyncEngine @Inject constructor(
  private val link: WatchLink,
  private val credentialStore: CredentialStore,
  private val mediaProgressDao: MediaProgressDao,
  private val clock: Clock,
) {

  private var job: Job? = null

  fun start(scope: CoroutineScope) {
    job?.cancel()
    job = scope.launch {
      link.incoming().collect { payload -> apply(payload) }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
  }

  /** Publishes this device's credentials and its most recently played rows. */
  suspend fun publishLocalState() {
    val credentials = credentialStore.load()
    link.publish(
      WatchSyncPayload(
        version = WatchSyncPayload.VERSION,
        credentials = credentials?.let {
          CredentialSnapshot(it.baseUrl, it.username, it.password)
        },
        // `recentlyPlayed` and not `findAll`: the Data Layer caps an item at 100 KB, and the rows a
        // second device can use are the recent ones. Plan 2 Task 1's DAO already orders by
        // lastPlayedAt descending.
        progress = mediaProgressDao.recentlyPlayed(WatchSyncPayload.MAX_PROGRESS_ROWS)
          .map(ProgressSnapshot::of),
      ),
    )
  }

  /**
   * Applies a peer's payload. Returns how many progress rows were written.
   *
   * Credentials are taken **only when this device has none**. A watch that has been set up
   * independently, or a phone whose server moved, must not be silently repointed by whatever the
   * other device last published — and the two devices have no way to tell which of two different
   * configurations is the newer intention.
   */
  suspend fun apply(payload: WatchSyncPayload): Int {
    payload.credentials?.let { snapshot ->
      if (credentialStore.load() == null) {
        credentialStore.save(
          SubsonicCredentials(snapshot.baseUrl, snapshot.username, snapshot.password),
        )
      }
    }

    val remote = payload.progress.map(ProgressSnapshot::toEntity)
    if (remote.isEmpty()) return 0
    val local = mediaProgressDao.findIn(remote.map { it.mediaId })
    val updates = ProgressMerge.updates(local, remote)
    updates.forEach { mediaProgressDao.upsert(it) }
    return updates.size
  }
}
```

> `clock` is injected and currently unread. **Either use it** — the obvious use is stamping a
> `publishedAtEpochMs` on the payload for diagnostics — **or remove the parameter.** An injected
> dependency nobody reads is the kind of thing a reviewer has to ask about twice; decide in this
> step and say which you chose. `credentialStore.load()` and `SubsonicCredentials`' property names
> are Plan 2 Task 1's, committed — read them.

- [ ] **Step 5: The one file that cannot be gated**

`core/watchlink/src/main/kotlin/app/muplay/watchlink/DataLayerWatchLink.kt`:

```kotlin
package app.muplay.watchlink

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * **The one file in this plan that no gate covers, in either tier.**
 *
 * It needs a physically paired phone and watch — a companion app, a Bluetooth bond and two Google
 * Play services installs — which no CI runner has and no emulator pair can fake. It is therefore
 * kept as small as the job allows and contains **no decision**: it puts bytes on a path and reads
 * bytes off it. Everything that decides anything is in [WatchSyncEngine], [ProgressMerge] and
 * [WatchSyncPayload], all of which are gated in Tier 1.
 *
 * Task 11 carries the manual verification procedure for this file. Do not write a CI gate for it:
 * a gate that cannot run reports the absence of a problem it never looked for, which is the exact
 * defect class this project's testing section exists to prevent.
 *
 * `DataClient` rather than `MessageClient`, and the reason is the case that actually happens: a
 * data item is **persisted and replicated when the peer reconnects**, so a watch that was in a
 * drawer receives the phone's state the moment it comes back. A message requires both ends online
 * at the same instant, which for a watch is the exception rather than the rule.
 *
 * `setUrgent()` because the default is best-effort and can be delayed by tens of minutes; a
 * position that arrives after the next listening session has started is worse than useless.
 */
@Singleton
class DataLayerWatchLink @Inject constructor(
  @ApplicationContext private val context: Context,
) : WatchLink {

  private val dataClient: DataClient get() = Wearable.getDataClient(context)

  override suspend fun publish(payload: WatchSyncPayload) {
    val request = PutDataRequest.create(PATH_SYNC).apply {
      data = WatchSyncPayload.encode(payload)
      setUrgent()
    }
    dataClient.putDataItem(request).await()
  }

  override fun incoming(): Flow<WatchSyncPayload> = callbackFlow {
    val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
      events.forEach { event ->
        if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_SYNC) {
          WatchSyncPayload.decode(event.dataItem.data ?: ByteArray(0))?.let(::trySend)
        }
      }
      events.release()
    }
    dataClient.addListener(listener)
    awaitClose { dataClient.removeListener(listener) }
  }

  companion object {
    /**
     * One path, both directions.
     *
     * Each device writes its own item at this path in its **own** node's namespace, so the two do
     * not overwrite each other — the Data Layer keys an item by (node, path), which is why one
     * constant is enough and a per-device suffix would only make the path harder to read.
     */
    const val PATH_SYNC: String = "/muplay/sync"
  }
}
```

`core/watchlink/src/main/kotlin/app/muplay/watchlink/di/WatchLinkModule.kt`:

```kotlin
package app.muplay.watchlink.di

import app.muplay.watchlink.DataLayerWatchLink
import app.muplay.watchlink.WatchLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WatchLinkModule {
  @Binds
  @Singleton
  abstract fun bindWatchLink(impl: DataLayerWatchLink): WatchLink
}
```

> `kotlinx.coroutines.tasks.await` needs `org.jetbrains.kotlinx:kotlinx-coroutines-play-services`.
> If you would rather not add it for one call, wrap `putDataItem(...)` in a
> `suspendCancellableCoroutine` with `addOnSuccessListener`/`addOnFailureListener` — the same choice
> Task 8 made about `ListenableFuture`. **Decide once and record it.**

- [ ] **Step 6: The engine against a real Room, and the absence with a positive control**

`core/watchlink/src/androidTest/kotlin/app/muplay/watchlink/WatchSyncEngineTest.kt`:

```kotlin
package app.muplay.watchlink

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.testing.InMemoryWatchLink
import java.time.Clock
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine, against a **real in-memory Room** and a hand-written link.
 *
 * On a device because Room is; with a fake link because two paired devices are not something CI has.
 * Everything the fake stands in for is two method bodies with no decision in them (see
 * `DataLayerWatchLink`).
 */
@RunWith(AndroidJUnit4::class)
class WatchSyncEngineTest {

  private lateinit var database: MuPlayDatabase
  private lateinit var link: InMemoryWatchLink
  private lateinit var credentials: RecordingCredentialStore
  private lateinit var engine: WatchSyncEngine

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      MuPlayDatabase::class.java,
    ).build()
    link = InMemoryWatchLink()
    credentials = RecordingCredentialStore()
    engine = WatchSyncEngine(link, credentials, database.mediaProgressDao(), Clock.systemUTC())
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun anIncomingPayloadWritesTheRowsThatWinAndNoOthers() = runTest {
    database.mediaProgressDao().upsert(row("b-1", positionMs = 1_000, lastPlayedAt = 100))
    database.mediaProgressDao().upsert(row("b-2", positionMs = 8_000, lastPlayedAt = 900))

    val written = engine.apply(
      WatchSyncPayload(
        WatchSyncPayload.VERSION,
        credentials = null,
        progress = listOf(
          ProgressSnapshot("b-1", 11_500, false, 500, 1.4f, true, -3f), // newer -> wins
          ProgressSnapshot("b-2", 2_000, false, 100, 1.0f, false, 0f),  // older -> loses
          ProgressSnapshot("b-3", 3_000, false, 700, 1.0f, false, 0f),  // unseen -> written
        ),
      ),
    )

    assertThat(written).isEqualTo(2)
    // Read back from the database, not from the return value: "the engine said it wrote two" and
    // "two rows are in the table" are different claims.
    val stored = database.mediaProgressDao().findAll().associateBy { it.mediaId }
    assertThat(stored.keys.sorted()).containsExactly("b-1", "b-2", "b-3")
    assertThat(stored.getValue("b-1").positionMs).isEqualTo(11_500)
    assertThat(stored.getValue("b-1").speed).isEqualTo(1.4f)
    assertThat(stored.getValue("b-2").positionMs).isEqualTo(8_000) // untouched
    assertThat(stored.getValue("b-3").positionMs).isEqualTo(3_000)
  }

  @Test
  fun credentialsAreTakenOnlyWhenThisDeviceHasNone() = runTest {
    engine.apply(WatchSyncPayload(WatchSyncPayload.VERSION, CredentialSnapshot("https://a", "u", "p"), emptyList()))
    assertThat(credentials.saved.map { it.baseUrl }).containsExactly("https://a")

    // A second, different payload must not silently repoint a device that is already configured.
    engine.apply(WatchSyncPayload(WatchSyncPayload.VERSION, CredentialSnapshot("https://b", "u2", "p2"), emptyList()))
    assertThat(credentials.saved.map { it.baseUrl }).containsExactly("https://a")
  }

  @Test
  fun publishingSendsTheCredentialsAndTheRecentRowsWithEveryFieldIntact() = runTest {
    credentials.save(app.muplay.model.SubsonicCredentials("https://music.example", "luc", "hunter2"))
    database.mediaProgressDao().upsert(row("b-1", 11_500, 900).copy(speed = 1.4f, skipSilence = true))
    database.mediaProgressDao().upsert(row("b-2", 2_000, 100))

    engine.publishLocalState()

    // Asserting the **argument**, not that publishing happened -- rule 5.
    val payload = link.published.single()
    assertThat(payload.version).isEqualTo(WatchSyncPayload.VERSION)
    assertThat(payload.credentials).isEqualTo(CredentialSnapshot("https://music.example", "luc", "hunter2"))
    // recentlyPlayed orders by lastPlayedAt descending, so the order is a property here.
    assertThat(payload.progress.map { it.mediaId }).containsExactly("b-1", "b-2")
    assertThat(payload.progress.map { it.positionMs }).containsExactly(11_500, 2_000)
    assertThat(payload.progress.map { it.speed }).containsExactly(1.4f, 1.0f)
    assertThat(payload.progress.map { it.skipSilence }).containsExactly(true, false)
  }

  @Test
  fun nothingThisEngineSendsIsAddressedAtNavidrome() = runTest {
    // The absence spec sections 4 and 11 demand, asserted **with a positive control** so a check
    // that recorded nothing cannot pass. Same technique Plan 4 Task 6 used for the same claim.
    credentials.save(app.muplay.model.SubsonicCredentials("https://music.example", "luc", "hunter2"))
    database.mediaProgressDao().upsert(row("b-1", 11_500, 900))

    engine.publishLocalState()

    // Positive control: the engine really did publish something, so "no server call" is not
    // "nothing happened at all".
    assertThat(link.published).hasSize(1)
    assertThat(link.published.single().progress).isNotEmpty

    // And the payload's own text contains no Subsonic verb. `createBookmark`, `savePlayQueue` and
    // `scrobble` are the three spec section 4 names as the write paths this design removes.
    val wire = WatchSyncPayload.encode(link.published.single()).decodeToString()
    assertThat(listOf("createBookmark", "savePlayQueue", "scrobble", "/rest/").map(wire::contains))
      .containsExactly(false, false, false, false)
  }

  /** A hand-written `CredentialStore` that records. No mock framework enters this build. */
  private class RecordingCredentialStore : app.muplay.database.CredentialStore {
    val saved = mutableListOf<app.muplay.model.SubsonicCredentials>()
    private var current: app.muplay.model.SubsonicCredentials? = null
    override suspend fun save(credentials: app.muplay.model.SubsonicCredentials) {
      saved += credentials
      current = credentials
    }
    override suspend fun load() = current
    override suspend fun clear() { current = null }
  }

  private companion object {
    fun row(mediaId: String, positionMs: Long, lastPlayedAt: Long) = MediaProgressEntity(
      mediaId = mediaId,
      positionMs = positionMs,
      isFinished = false,
      lastPlayedAtEpochMs = lastPlayedAt,
      speed = 1.0f,
      skipSilence = false,
      gainDb = 0f,
    )
  }
}
```

> **`CredentialStore` is a class in Plan 2 Task 1, not an interface.** If it cannot be implemented,
> do one of two things and record which: extract an interface in `:core:database` (preferred — this
> is the second consumer), or construct the real `CredentialStore` over an in-memory DataStore. Do
> **not** reach around it to a DAO; spec §4 puts credentials behind Keystore for a reason.

- [ ] **Step 7: Start the engine on both devices, run everything, prove failure**

`app/src/main/kotlin/app/muplay/MuPlayApplication.kt` and
`wear/src/main/kotlin/app/muplay/wear/MuPlayWearApplication.kt` — inject `WatchSyncEngine`, call
`start(applicationScope)` in `onCreate`, and call `publishLocalState()` once from the same scope.
**On both**, symmetrically: the phone publishes credentials and progress, the watch publishes
progress and (usually) no credentials, and each applies what it receives.

```bash
./gradlew :core:watchlink:test
./ci/prepare-emulator.sh && ./gradlew :core:watchlink:connectedDebugAndroidTest
```

Add `:core:watchlink:connectedDebugAndroidTest` to `.github/workflows/e2e.yml`'s **phone** emulator
step (it needs Room, not a watch), and add a `:core:watchlink` entry to `coverageFloors`.

Mutations:

1. In `ProgressMerge.winner`, drop the tie-break arm. Expect `a tie is broken by the greater
   position, deterministically` to fail.
2. In `ProgressMerge.updates`, return `remote` unchanged. Expect `the newer row wins, whichever side
   it is on` and `many rows are merged independently…` to fail. **This is the mutation that matters
   most**: an engine that always takes the remote row silently discards what the user just did.
3. In `WatchSyncEngine.apply`, save credentials unconditionally. Expect `credentialsAreTakenOnly
   WhenThisDeviceHasNone` to fail.
4. In `publishLocalState`, send `mediaProgressDao.findAll()` instead of `recentlyPlayed(...)`.
   Expect `publishingSendsTheCredentialsAndTheRecentRowsWithEveryFieldIntact` to fail on the order.
5. In `WatchSyncPayload.decode`, drop the `takeIf { it.version == VERSION }`. Expect `a payload from
   a future version is refused rather than half-read` to fail.
6. Delete the positive-control lines from `nothingThisEngineSendsIsAddressedAtNavidrome` and make
   `publishLocalState` a no-op. Confirm the test then **passes** — and restore both. That is the
   demonstration that the positive control is doing work, and it is why it is there.

```bash
git add settings.gradle.kts gradle build.gradle.kts core/watchlink core/testing app wear .github/workflows/e2e.yml
git commit -m "feat(watchlink): replicate credentials and book positions between phone and watch"
```

---

## Task 11: The gates — what each image proves, what nothing proves, coverage, and the spec

**Files:**
- Modify: `.github/workflows/e2e.yml`, `.github/workflows/pr.yml`
- Modify: `build.gradle.kts` (the finished `coverageFloors` entries)
- Modify: `ci/mutation-probes.sh`
- Create: `docs/superpowers/manual-checks/2026-08-24-auto-wear.md`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Interfaces:**
- Consumes: every test file Tasks 1–10 produced, `ci/prepare-emulator.sh`,
  `ci/prepare-wear-emulator.sh`, `ci/mutation-probes.sh`, `ConventionTest`.
- Produces: the finished Tier 1 and Tier 2 job definitions, the `:wear`/`:core:watchlink` coverage
  floors, the manual-verification document, and the spec corrections listed in Step 5.

### The honest verification matrix

This is the table the plan's brief asked for, and it is the deliverable of this task as much as any
code. **Every "gated" row has been watched failing** in its own task's mutation step; every
"not gated" row is in the manual document and has **no CI job pretending to cover it**.

| Claim | Where it runs | Gated? |
|---|---|---|
| Browse-node ids round-trip, including hostile payloads | Tier 1, JVM | yes — Task 1 |
| Three surfaces produce three different, ordered root lists | Tier 1, JVM | yes — Task 2 |
| A Music library gets a shuffle row and an Audiobooks library does not | Tier 1, JVM **and** Tier 2 phone | yes — Tasks 2, 5 |
| Controller → surface classification, all four arguments | Tier 1, JVM | yes — Task 3 |
| Content-style and completion extras, exact keys and values | Tier 1, JVM | yes — Task 4 |
| Paging arithmetic, including `Int.MAX_VALUE` | Tier 1, JVM | yes — Task 4 |
| `BrowseNode` → `MediaItem`, every field | Tier 2, **phone image** | yes — Task 4 |
| A real `MediaBrowser` reads the real root/children/item over real IPC, **per surface** | Tier 2, **phone image** | yes — Task 4 |
| Playing a browse row expands to the right queue at the right index | Tier 2, **phone image** | yes — Task 5 |
| **A book tapped in the car resumes at its stored second and advances** | Tier 2, **phone image** | yes — Task 5 |
| Music tapped in the car starts at 0 despite a stored row | Tier 2, **phone image** | yes — Task 5 |
| Car search box results, order and paging | Tier 2, **phone image** | yes — Task 6 |
| The Assistant's `MEDIA_PLAY_FROM_SEARCH` intent makes audio advance | Tier 2, **phone image** | yes — Task 6 |
| The manifest declares everything Auto discovers the app by | Tier 1, static | yes — Task 7 |
| `automotive_app_desc.xml` says `<uses name="media"/>` | Tier 1, static | yes — Task 7 |
| The Auto opt-in cannot be deleted quietly | Tier 1, `ConventionTest` | yes — Task 7 |
| The wear suite is running on an actual watch | Tier 2, **wear image** + the preflight | yes — Task 8 |
| A watch gets the watch tree and streams from Navidrome, position advancing | Tier 2, **wear image** | yes — Task 8 |
| The watch UI browses, plays, and shows a position that **changes** | Tier 2, **wear image** | yes — Task 9 |
| Wear back gesture pops a browse level instead of closing the app | Tier 2, **wear image** | yes — Task 9 |
| Progress merge rules, both directions, every boundary | Tier 1, JVM | yes — Task 10 |
| Sync payload wire form, version refusal, malformed input | Tier 1, JVM | yes — Task 10 |
| The sync engine writes the right rows to a real Room, and sends nothing to Navidrome | Tier 2, **phone image** | yes — Task 10 |
| **`isAutomotiveController`/`isAutomobileController` are `true` for a real Auto controller** | a car or the DHU | **no** |
| **Google's own rendering of this tree in a head unit** | a car or the DHU | **no** |
| **Google Assistant really sends `MEDIA_PLAY_FROM_SEARCH` to this service** | a real phone | **no** |
| **Horologist's output picker opening when no Bluetooth output is connected** | a real watch | **no** |
| **`DataLayerWatchLink` actually moving bytes between two devices** | a paired phone + watch | **no** |
| **Rotary crown / physical bezel scrolling** | a real watch | **no** |

Five rows, and they are five for a reason: each is a **boundary with someone else's software**, and
each is downstream of code that *is* gated. The residue is one expression (Task 3), one `if`
(Task 9), one manifest filter that is checked statically instead of dynamically (Task 7), and one
file of sixty decision-free lines (Task 10).

### Why there is no Android Automotive OS emulator in this gate

Not an omission, and worth writing down once so nobody adds one on the assumption it was overlooked:

1. **Android Auto is projection.** The app, the session, `MuPlayLibraryCallback` and the browse tree
   all run on the phone. The API-37 phone image the Tier 2 job already boots runs every line of this
   plan's Auto code, and Task 4 reaches the car branch through a connection hint.
2. **What an AAOS image would add is Google's rendering** — a system app's UI, drivable only by
   UiAutomator against layouts this project does not own and does not control the version of. That
   is the definition of a flaky gate.
3. **AAOS distribution is a separate product decision.** Shipping to Automotive OS means a separate
   Play track, `<uses-feature android:name="android.hardware.type.automotive"/>` and its own review.
   Spec §7 asks for Android Auto; when AAOS is wanted, it is a plan, not a line in this one.

The runtime code path is identical — `BrowseSurfaces.of` maps `com.android.car.media` to `CAR` right
next to `com.google.android.projection.gearhead`, and both are asserted in Task 3.

- [ ] **Step 1: Write the manual checklist — and give it no gate**

`docs/superpowers/manual-checks/2026-08-24-auto-wear.md`:

```markdown
# Manual checks — Android Auto and Wear OS (Plan 5)

Five things this repository's CI **cannot** verify, with the procedure for each. Run them before a
release that changes anything in `:core:media/browse`, `:wear` or `:core:watchlink`.

**There is deliberately no CI job for any of this.** A gate that cannot run reports the absence of a
problem it never looked for, which is the defect class spec §10 exists to prevent.

## 1. The app appears in a car, and the tree renders (Task 7, Task 4)

1. Install the Desktop Head Unit: `sdkmanager "extras;google;auto"`, then
   `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.
2. On a real phone: enable developer mode in Android Auto, "Start head unit server".
3. `adb forward tcp:5277 tcp:5277`, then run the DHU.
4. Open MuPlay in the car UI. **Expected:** four tabs, in order — Continue, Books, Albums, Artists.
   Books shows a grid of covers with a progress pip on any partly-heard book.
5. **If the app is not in the media app list at all**, the cause is almost certainly the missing
   `android.media.browse.MediaBrowserService` action — which `verifyDebugManifest` gates, so check
   that the gate ran before looking anywhere else.

## 2. `isAutomotiveController` is true for a real Auto controller (Task 3)

With the DHU connected, confirm the car received the **four-tab** root and not the phone's five.
That is the only observation that distinguishes `DefaultSurfaceResolver`'s one ungated expression
working from `CAR_PACKAGES` catching it as a backstop; if the car shows five tabs, the predicates
returned false and the package list did not match — record the controller's package name.

## 3. Google Assistant reaches the service (Task 6)

Say "Hey Google, play Second Book on MuPlay" with the app installed and not running.
**Expected:** the book plays, from its stored position.
The handler itself is gated; this checks that the Assistant addresses it. The equivalent without a
voice, which is what CI runs:

```
adb shell am start-foreground-service -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
  -n app.muplay/app.muplay.media.MuPlaybackService --es query "Second Book"
```

## 4. The watch refuses to play on its own speaker (Task 9)

On a real watch with **no** Bluetooth output connected, tap play.
**Expected:** the output picker opens; nothing plays. Connect buds, tap play, audio starts.
This is `HorologistWatchAudioOutput.ensureOutputThenPlay`'s one ungated branch.

## 5. Phone and watch agree about a book (Task 10)

1. Phone and watch paired, both signed in.
2. Play a book on the phone to a distinctive position; pause.
3. Open MuPlay on the watch. **Expected:** Continue shows that book at that position, within a
   couple of seconds.
4. Play it on the watch a further minute; pause; return to the phone. **Expected:** the phone now
   shows the later position.
5. **Known limitation, not a bug:** restarting a book on one device does not restart it on the other
   until the second device plays it. Deletions do not replicate — see Plan 5 Task 10.
```

- [ ] **Step 2: Finish the Tier 2 job**

`.github/workflows/e2e.yml` — the job now boots **two** emulators, in order, in one workspace:

```yaml
    timeout-minutes: 90
```

Phone step's `script:` (one Gradle invocation, one logcat dump):

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew \
              :core:database:connectedDebugAndroidTest \
              :core:media:connectedDebugAndroidTest \
              :core:watchlink:connectedDebugAndroidTest \
              :app:connectedDebugAndroidTest \
              || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

Wear step's `script:` (Task 8 added it; this is its final form):

```yaml
          script: |
            ./ci/prepare-wear-emulator.sh
            ./gradlew :wear:connectedDebugAndroidTest || { adb logcat -d > wear-logcat.txt; exit 1; }
```

The **coverage report and gate stay after both**, unchanged, and that ordering is the whole reason
the wear run is a second step of this job rather than a second job: `Jacoco.kt` merges every
project's `build/outputs/code_coverage/**/*.ec` from **this workspace**, and a separate job's
execution data would not be in it. `:wear`'s LINE floors over Compose would then be evaluated
against no instrumented data at all — which is a floor that cannot pass, evaluated by a gate that
never saw the run.

Failure artifacts:

```yaml
          path: |
            app/build/reports/androidTests/**
            wear/build/reports/androidTests/**
            **/build/reports/jacoco/**
            emulator-logcat.txt
            wear-logcat.txt
```

- [ ] **Step 3: Finish the Tier 1 job**

`.github/workflows/pr.yml`:

- **Static** — Task 7 replaced the release-manifest step with
  `:app:verifyReleaseManifest :app:verifyDebugManifest :app:verifyAutomotiveDescriptor`. Add
  `:wear:verifyReleaseManifest :wear:verifyDebugManifest` to the same line: the watch APK must not
  ship cleartext either, and that gate came free with the module existing.
- **Unit + integration** — the new JVM suites are picked up by the existing `test` aggregation; if
  that step enumerates modules by name, add `:core:watchlink` and `:wear` (`:wear:testDebugUnitTest`
  — an application module's JVM tests are per-variant).
- **Coverage gate (JVM floors)** — unchanged task, more entries.

- [ ] **Step 4: Measure and write the coverage floors**

Run the whole thing and read the reports; **do not carry forward any number this plan guessed**:

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest \
          :core:watchlink:connectedDebugAndroidTest :app:connectedDebugAndroidTest
# then the wear AVD:
./ci/prepare-wear-emulator.sh && ./gradlew :wear:connectedDebugAndroidTest
./gradlew jacocoTestReport
```

Then fill `coverageFloors` for the classes this plan added, following the table's existing rules
(`"CLASS"` element wherever a rule needs to scope; never `"BUNDLE"` with `includes`; `*` rather than
`$` in a nested class pattern):

| Module | Classes | Counter | Tier |
|---|---|---|---|
| `:core:model` | `browse.BrowseId*`, `browse.BrowseTree`, `browse.BrowseText`, `browse.BrowseExtras`, `browse.BrowseSurface`, `browse.BrowseSurfaces`, `browse.BrowsePaging`, `browse.PlayFromSearch` | BRANCH | 1 |
| `:core:model` | the zero-branch holders (`browse.BrowseNode`, `browse.BrowseCompletion`, `browse.BrowseSelection`, the three enums) | BRANCH, riding along | 1 |
| `:core:database` | `BrowseTreeRepository`, `BrowseTreeRepository*` | BRANCH | **2** (`requiresInstrumentedData = true`) |
| `:core:media` | `browse.BrowseItems`, `browse.MuPlayLibraryCallback*`, `browse.DefaultSurfaceResolver` | BRANCH | **2** |
| `:core:watchlink` | `ProgressMerge`, `WatchSyncPayload*`, `ProgressSnapshot`, `CredentialSnapshot` | BRANCH | 1 |
| `:core:watchlink` | `WatchSyncEngine*` | BRANCH | **2** |
| `:core:watchlink` | `DataLayerWatchLink` | see below | — |
| `:wear` | `audio.WatchAudioState`, `player.WearPlayerUiStateKt`, `browse.WearBrowseViewModel*`, `player.WearPlayerViewModel*` | BRANCH | mixed |
| `:wear` | `WearAppKt`, `browse.WearBrowseScreenKt`, `player.WearPlayerScreenKt` | **LINE** | **2** |
| `:wear` | `audio.HorologistWatchAudioOutput` | **LINE**, at the measured value | **2** |

**Two entries need a decision made in the open rather than a number picked to make a build green:**

- **`DataLayerWatchLink` has no test and cannot have one.** Do **not** give it a `0.00` floor — this
  table has already shipped one floor that could never fail. Give it an explicit `excludes` entry on
  `:core:watchlink`'s rule, with a comment naming Task 11's manual document and the reason, and
  confirm `warnUngatedClasses` then names it — **and that the warning is left visible**, because the
  right state for an unverifiable class is "loudly excluded", not "quietly included".
- **`HorologistWatchAudioOutput` keeps a real floor** at whatever the emulator run measures, because
  its `state` flow *is* exercised there. Its one uncovered branch is named in the comment.

- [ ] **Step 5: Correct the spec**

Roadmap definition-of-done item 6: *"Anything discovered to be wrong in the spec is corrected in the
spec."* Eight corrections, each with the evidence that produced it.

**§2 and §4 — "local only" is about the server, not about the device.** §2 says *"Book positions are
**local only**. No server sync."* and §4 repeats it. Plan 5 adds a phone-to-watch replication, and a
reader who took "local only" to mean "one device" would read this plan as violating the spec.
Amend §4's *"Resume — local only"* heading paragraph to:

> Book positions live in Room and are **never sent to the server**. This removes the
> `createBookmark` write path, `savePlayQueue` sync, conflict resolution and a background worker …
> **Positions may be replicated directly between a user's own paired devices** — Plan 5's phone-to-
> watch link — which involves no server, no Subsonic write verb and none of the hazards above. The
> rule is *"nothing about a position reaches Navidrome"*, and it is checked rather than promised:
> `WatchSyncEngineTest` asserts the absence with a positive control.

and change §2's bullet to **"Book positions never reach the server. Device-to-device replication between
the user's own paired devices is allowed; see §7."**

**§7 — the discovery action Android Auto actually uses.** Add, under the Android Auto bullet:

> A `MediaLibraryService` is **not** discovered by a car through its Media3 action. Android Auto
> enumerates media apps by the legacy **`android.media.browse.MediaBrowserService`** intent-filter
> action; an app declaring only `androidx.media3.session.MediaSessionService` installs, runs, passes
> its own tests and **never appears in the car**, with no error anywhere. The service therefore
> declares all three actions, and `verify<Variant>Manifest` requires them by name.

**§7 — Wear OS is standalone, and it raises `minSdk`.** Add:

> The Wear app is a **second application module** (`:wear`), standalone: it hosts the same
> `MediaLibraryService`, streams from Navidrome over the watch's own Wi-Fi, and works with the phone
> switched off. It is the one module in the build that cannot honour §2's `minSdk 26` — Wear OS 3 is
> **API 30** and Compose for Wear OS supports nothing earlier — so `:wear` alone declares
> `minSdk 30`, in a convention plugin. Credentials and book positions reach it over the Wearable
> Data Layer (`play-services-wearable`), which is the only API for the purpose.

**§7 — what `isAutomotiveController` branching is actually tested by.** Replace *"`isAutomotiveController`
branching lets it be tested with no car"* with the accurate version, **and record which branch Task 3
Step 5 took**:

> `MediaSession.ControllerInfo`'s car predicates decide the branch in production. They cannot be
> made true by any controller this project can start, so the classification is a pure function of
> four values (`BrowseSurfaces.of`) tested on the JVM at every value, and the car *tree* is reached
> over real Media3 IPC on the ordinary phone emulator through a connection hint honoured only from
> this app's own package. What remains unverifiable by CI is one expression, and it is listed in
> `docs/superpowers/manual-checks/2026-08-24-auto-wear.md`.

**§10 — the Tier 2 table's "Auto / Wear" row names one journey and needs two, on two images.**
Replace:

> | Auto / Wear | browse tree and controls from car and watch surfaces |

with:

> | **Auto (browse tree)** | On the **ordinary API 37 phone image**, because Android Auto is projection — the app, the session and the browse tree all run on the phone. A real `MediaBrowser` reads the root, children, item and search results per surface, and a book tapped from the tree resumes at its stored second. **No Automotive OS image is in the gate**, and the reason is written down in Plan 5 Task 11 rather than left as an omission. |
> | **Wear OS** | On a **second emulator step in the same job**, booting a Wear system image (`system-images;android-<n>;android-wear;x86_64`, profile `wearos_small_round`). Same job, not a second job, because JaCoCo merges execution data from one workspace. `ci/prepare-wear-emulator.sh` fails unless `ro.build.characteristics` contains `watch`, and the suite asserts `FEATURE_WATCH` again from inside the APK — a wear suite that ran on the phone image would be green and worthless. |
> | **Not gated** | Google's rendering of the tree in a head unit, the Assistant actually addressing the service, the Wear audio-output picker, and the Data Layer wire. Listed with procedures in `docs/superpowers/manual-checks/2026-08-24-auto-wear.md`, with **no CI job pretending to cover them**. |

**§9 — the module list is missing two modules this plan adds.** §9's structure block ends at
`integrations/*` and `app`. Add:

```
core/watchlink    the Wearable Data Layer link: credentials and book positions, phone <-> watch
wear              the Wear OS application module (standalone; minSdk 30)
```

and note beside them that `:wear` is the build's **second application module**, which is why
`ConventionTest`'s `android { }` allow-list includes `applicationId` at all.

**§5 — chapters are not browse nodes, and that is a decision.** Add, after the chapter section's
API sketch:

> Chapters are **not** exposed as browse-tree children. Reading them costs a `MetadataRetriever`
> pass with an HTTP Range request per file, and `onGetChildren` answers on a car host's own timeout —
> an app that answers it with a network round trip per row shows a spinner and then an error. A
> multi-file book's children in the tree are its **files**, which is also the shape a ripped
> audiobook actually has; chapter navigation lives in the book player (Plan 4) and the watch player
> (Plan 5), both of which read chapters after playback has already started.

**§12 — three risk rows this plan earned.** Add:

> | Horologist's 0.x API moves under the watch app | High | Its entire surface is one file (`HorologistWatchAudioOutput`), behind `WatchAudioOutput` |
> | A Wear system image for the pinned API is not published, so the wear gate cannot run | Medium | The workflow's "Resolve SDK packages" step fails by name in seconds; Plan 5 Task 8 records the fallback ladder and forbids shipping an unresolvable coordinate |
> | Phone and watch clocks disagree, so last-writer-wins picks the wrong row | Low | Wear OS forces automatic time from the paired phone; the merge's tie-break is "further along wins", so a slow clock loses ties but never loses a position it is ahead on |

- [ ] **Step 6: Finish the mutation-probe list**

`ci/mutation-probes.sh` — the probes this plan earned, in one pass, each with its file in `revert()`
and its suite in `run_suite()`. **Read the script's header first**; a probe whose file is missing
from `revert()` mutates the tree and never puts it back.

| Probe | File | Expected red |
|---|---|---|
| `root` ignores `surface` | `BrowseTree.kt` | `the three surfaces produce three different roots` |
| `libraryChildren` gives every library a shuffle row | `BrowseTree.kt` | `an audiobook library gets no shuffle node…` |
| `albumNode` hardcodes `artworkId` | `BrowseTree.kt` | `an album node carries every field of its album` |
| `of` honours a hint from any package | `BrowseSurface.kt` | `a hint is honoured from our own package…` |
| `of` matches packages by prefix | `BrowseSurface.kt` | `package matching is exact…` |
| `decode` accepts a non-canonical library id | `BrowseId.kt` | `a library id that is not canonically numeric…` |
| `expand` returns a single song for a `Track` | `BrowseTreeRepository.kt` | `tappingATrackInTheMiddleOfAnAlbum…` |
| `searchNodes` puts songs first | `BrowseTree.kt` | `theCarsSearchBoxFindsABookAndPutsItFirst` |
| `pick` returns null on no match | `PlayFromSearch.kt` | `aSpokenQueryThatMatchesNothing…` |
| `updates` returns `remote` unchanged | `ProgressMerge.kt` | `the newer row wins, whichever side it is on` |
| `winner` drops the tie-break | `ProgressMerge.kt` | `a tie is broken by the greater position…` |
| `isSuitableForPlayback` is always true | `WatchAudioOutput.kt` | `only a bluetooth output is suitable for playback` |

Then update the probe **count** the script asserts about itself — `ci/mutation-probes.sh` carries a
self-check on its own list length (a stale count was caught by that check once already; see the
commit history). Run the whole script and confirm every probe goes red for the named test and green
again after `revert()`.

- [ ] **Step 7: Run both tiers, then commit**

```bash
# Tier 1
./gradlew check :app:verifyReleaseManifest :app:verifyDebugManifest :app:verifyAutomotiveDescriptor \
  :wear:verifyReleaseManifest :wear:verifyDebugManifest jacocoJvmCoverageVerification

# Tier 2, phone image
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest \
          :core:watchlink:connectedDebugAndroidTest :app:connectedDebugAndroidTest

# Tier 2, wear image
./ci/prepare-wear-emulator.sh && ./gradlew :wear:connectedDebugAndroidTest

# The whole table, against merged data
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

Confirm, before committing: no `COVERAGE:` warning is left standing except the deliberate
`DataLayerWatchLink` exclusion; `warnUngatedClasses` names nothing this plan added other than that
one; and both coverage **notice** tasks report a non-zero number of floors evaluated in their tier.
A notice saying "0 floors evaluated" is the gate telling you it did not run.

```bash
git add .github ci build.gradle.kts docs
git commit -m "ci: gate the car and the watch, and say what CI cannot see"
```

---

## Definition of done

1. **All tasks' tests pass; both tiers green.** Tier 1 ≤ 10 minutes with the Navidrome container and
   no emulator; Tier 2 green on **both** emulator images.
2. **Tier 2 carries this plan's journeys:** `BrowseTreeJourneyTest`, `CarResumeJourneyTest`,
   `VoiceSearchJourneyTest` and `BrowseItemsTest` on the phone image; `WearSessionJourneyTest` and
   `WearUiJourneyTest` on the wear image; `WatchSyncEngineTest` on the phone image.
3. **Coverage ≥ 90%** on `:core:model`, `:core:database`, `:core:media`, `:core:watchlink` and
   `:wear` — BRANCH for non-UI, LINE for Compose UI. Every floor **measured** from a real merged
   report and **able to fail**. The one class with no floor (`DataLayerWatchLink`) is excluded by
   name, with a reason, and the exclusion is visible in `warnUngatedClasses`' output.
4. **No mock framework has entered the dependency graph.** `ConventionTest`'s existing rule covers
   `:wear` and `:core:watchlink` the moment they exist; `InMemoryWatchLink`,
   `RecordingCredentialStore` and the permissive `WatchAudioOutput` are hand-written, and each says
   at its declaration why the real thing cannot run.
5. **Every new external-API assumption is backed by a test or a recorded probe.** Media3's browse
   callbacks, `MediaConstants`' wire strings, `MediaMetadata`'s media-type constants and the three
   Wear/Horologist/Play-services coordinates were each resolved or compiled against rather than
   assumed, and each task that took a fallback branch recorded which.
6. **The spec is corrected** — §2, §4, §7, §10 and §12, as Task 11 Step 5 lists.
7. **Every gate in this plan has been watched failing**, by the mutation step in its own task, and
   the mutations that matter are committed to `ci/mutation-probes.sh`.
8. **What CI cannot see is written down** in `docs/superpowers/manual-checks/2026-08-24-auto-wear.md`
   and is **not** covered by a CI job that would report success by never running.
