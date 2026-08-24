# MuPlay Kotlin Plan 4 — Audiobooks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The thing this project was started for. Every audiobook remembers its own exact position
and gives it back across an intervening music session — not zero, not the chapter start, the
position. On top of that: M4B chapters read out of the file's own bytes because no Subsonic server
can supply them, chapter navigation, a playback speed that is a property of the *book* and follows
it across chapter files, a sleep timer that fades rather than cutting, and a smart rewind
proportional to how long the book was away. Positions are **local only** and this plan makes that
structurally checkable rather than promised.

**Architecture:** Plan 3 built a `ForwardingPlayer` seam (`MuPlayer`) through which every
`setMediaItem(s)` call passes, a `ProgressWriter` that fills `media_progress` at spec §3's seven
persistence points, and a `ResumePolicy` whose Plan 3 implementation resumes nothing. **Plan 4
swaps that one Hilt binding.** `AudiobookResumePolicy` answers from an in-memory snapshot of
`media_progress` restricted to items the user tagged **Audiobooks**, so music continues to restart
from zero *structurally* rather than by convention. `:core:database` gains two tables
(`book_settings`, `chapters`) and the project's first Room migration. `:core:media` gains chapter
extraction (`media3-inspector`), the resume policy, a per-book speed controller and a sleep timer.
`:feature:book` is a new module with the bookshelf, the book screen and an audiobook player that
is a different instrument from the music player. Nothing this plan writes is ever sent to a server.

**Tech Stack:** Kotlin 2.4.10, JDK 21, AGP 9.3.1, **KSP** (never KAPT), Media3 1.11.0 — Plan 3's
five artifacts plus **`media3-inspector`** (`MetadataRetriever`, chapters; `media3-exoplayer` does
not depend on it) — Room 2.8.4 (+ `room-testing` for the migration test), Hilt 2.60.1, OkHttp
5.5.0, Compose BOM 2026.08.00 + Material 3 1.4.0, Navigation 3 1.1.6, JUnit 5 (JVM) / JUnit 4
(device), AssertJ, Turbine, `kotlinx-coroutines-test`, JaCoCo 0.8.12.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Roadmap:** `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` — Plan 4, *"Per-book
resume, M4B chapters, speed, sleep timer, smart rewind"*, depends on Plan 3.

**Builds directly on:** `docs/superpowers/plans/2026-08-24-muplay-k03-playback-core.md`, whose
**Task 8** is the seam this plan was designed into. Read that task before this one.

---

## Global Constraints

Copied verbatim from `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md`'s **Global
constraints** and the spec. Every task inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI. `.kts` build scripts.
- Licence **MIT**. No GPL code may be copied. (Sharp edge in this plan, again: **Voice** is GPL and
  is the origin of both the `ForwardingPlayer` idea *and* the smart-rewind idea. Read no Voice
  source. The idea travels, the code does not, and the rewind table below is derived from spec §5's
  one sentence plus stated reasoning, not copied from anywhere.)
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

- **No Robolectric**, no Roborazzi, no ktlint/detekt/spotless.
- **`LibraryRole` is the only signal that something is an audiobook.** Navidrome hardcodes
  `child.Type = "music"` for every media file and always sets `mediaType = song`; there is no
  server setting for it (spec §4). Never infer a book from a file suffix, a folder name, a
  duration, or a chapter count.
- Cleartext HTTP is debug-only and must never reach the release manifest.

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
| 1 | The corpus — three more books, because one book proves nothing | four distinct books on a real Navidrome, checksummed, with measured chapter data |
| 2 | Schema 5 — `book_settings`, `chapters`, and the project's first Room migration | a v4 database opens as v5 with its rows intact, proven by `MigrationTestHelper` |
| 3 | `ChapterReader` and `BookTimeline` — chapters out of the file's own bytes | real chapters out of a real Navidrome URL, in order, with populated end times |
| 4 | `AudiobookRepository` — what a book *is*, the shelf order, and the settings write | a shelf ordered by when each book was last heard; settings that survive a neighbour's write |
| 5 | `SmartRewind` — the band table, its boundaries, and the clamp | five distinct rewinds, every boundary asserted on both sides |
| 6 | `AudiobookResumePolicy` — the swap Plan 3 designed for, and local-only made checkable | a book resumes at its exact position; music still starts at zero; nothing reaches the server |
| 7 | Per-book speed and silence skipping — and the speed that follows you into music | 1.4× on a book, 1.0× on the next song, and a chapter transition that does not reset it |
| 8 | The sleep timer — fade, end of chapter, shake to extend | audio fades to silence, pauses, and the volume comes back |
| 9 | `:feature:book` — the shelf, the book, and an audiobook player | a player with chapters, speed and a sleep timer, reachable from a real screen |
| 10 | The gates — Tier 2 audiobook journeys, the coverage table, the spec corrections | the original complaint, as a test that fails when the feature is removed |

---

## What Plans 2 and 3 hand this plan — consume it, do not rebuild it

Plan 2 and Plan 3 are **written and sequenced ahead of this plan**; at the time this plan was
written the tree carried Plan 2's Tasks 1–3 (`:core:database` with `MediaProgressEntity`,
`MediaProgressDao`, `KeystoreCipher`, `CredentialStore`, `DataModule`; `:core:network`'s browse
commands and `SubsonicSource`). **Every symbol in the table below belongs to an earlier plan. This
plan consumes them and must not redefine, rename or re-derive any of them.** Where a name is
uncertain because the earlier plan had not landed it yet, the row says so, and the task that
consumes it says so again at the point of use.

| Symbol | Module | Owner |
|---|---|---|
| `Song(id, libraryId, title, albumId, albumName, artistId, artistName, trackNumber, discNumber, durationSeconds, suffix, coverArtId)` | `:core:model` | **Plan 2, committed.** Read the file; do not restate it. |
| `Album`, `AlbumWithSongs`, `Artist`, `MusicLibrary`, `LibraryRole { MUSIC, AUDIOBOOKS, UNASSIGNED }` | `:core:model` | **Plan 2, committed.** |
| `StreamFormat` (`Raw`, `Mp3(maxBitRateKbps)`, `forSuffix`, `DEFAULT_TRANSCODE_BITRATE_KBPS`) | `:core:model` | Plan 3 Task 1 |
| `SubsonicSource` (`ping`, `getMusicFolders`, `getScanStatus`, `getAlbumList2`, `getAlbum`, `search3`, `getRandomSongs`, `coverArtUrl`, `streamUrl`) | `:core:network` | Plan 2 Task 3 + Plan 3 Task 1 |
| `MediaProgressEntity(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb)`, `MediaProgressDao(upsert, find, findAll, recentlyPlayed)` | `:core:database` | **Plan 2 Task 1, committed.** Plan 4 adds queries; see Task 2 for exactly which. |
| `LibraryEntity`, `LibraryDao(observeAll, find, idsWithRole, allIds, setRole, mergeFromServer)` | `:core:database` | Plan 2 Task 4 |
| `SubsonicSourceProvider.current(): SubsonicSource`, `NotConfiguredException` | `:core:database` | Plan 2 Task 4 |
| `LibraryRepository(libraries, refreshFromServer, setRole, idsWithRole, allIds, hasUnassignedLibraries)` | `:core:database` | Plan 2 Task 4 |
| `AlbumEntity`, `SongEntity`, `ArtistEntity`, `BrowseDao(observeAlbums, observeSongs, findAlbum, …)`, `MirrorMapper`, `BrowseRepository` | `:core:database` | Plan 2 Task 5 |
| `SyncEngine.syncIfStale(): SyncState` | `:core:database` | Plan 2 Task 6. Plan 4 never calls it. |
| `ShuffleRepository.shuffle(libraryId, requestedSize)` | `:core:database` | Plan 2 Task 7. Plan 4 never calls it. |
| Room schema version **4** (`media_progress`, `libraries`, `artists`, `albums`, `songs`, `sync_watermark`) | `:core:database` | Plan 2 Task 6 leaves it at 4; Plan 3 does not move it. **Plan 4 moves it to 5.** |
| `MuPlayDataSourceFactory(callFactory, cache).create()`, `MediaCache`, `TrackIdCacheKeyFactory` | `:core:media` | Plan 3 Tasks 2–3 |
| `MediaItems.of(song, streamUri, artworkUri, isAudiobook)`, `PlaybackQueue.of(songs, startIndex)`, `QueueRepository.mediaItems(queue)` | `:core:media` | Plan 3 Tasks 4, 6 |
| `MuPlayerFactory(context, dataSourceFactory, loadErrorPolicy, resumePolicy)` with `createExoPlayer(): ExoPlayer` and `create(): MuPlayer` | `:core:media` | Plan 3 Tasks 5, 8. **Task 7 below adds one method to it.** |
| `MuPlaybackService : MediaLibraryService`, `PlaybackNotification.CHANNEL_ID/NOTIFICATION_ID` | `:core:media` | Plan 3 Task 5 |
| `PlaybackState(isPlaying, isBuffering, mediaId, title, artist, albumTitle, artworkUri, positionMs, durationMs, hasNext, hasPrevious)`, `PlaybackState.NOTHING_PLAYING`, `PlaybackConnection(state, controller(), release())` | `:core:media` | Plan 3 Task 5. **Task 7 below adds two fields.** |
| `PlaybackAudioAttributes.contentTypeFor/of`, `ContentTypeSwitcher` | `:core:media` | Plan 3 Task 6 |
| `ResumeTarget(startIndex, startPositionMs)`, `ResumePolicy.resolve(mediaIds, requestedIndex)`, `NeverResume`, `MuPlayer`, `ProgressWriter(player, dao, clock, scope)` with `TICK_MS = 5_000L`, `DEFAULT_SPEED`, `DEFAULT_GAIN_DB` | `:core:media` | **Plan 3 Task 8 — the seam this plan exists to fill.** |
| `PlaybackLauncher.play(songs, startIndex)`, `PlayerScreen`, `MiniPlayer`, `PlayerRoute` | `:feature:player` | Plan 3 Task 9 |
| `LibraryScreen`, `AlbumScreen`, `LibraryUiState`, `AlbumUiState`, `CoverArtImage`, `coverArtCacheKey` | `:feature:library` | Plan 2 Task 9 |
| `MuPlayApp`, `StartDestination`, `LibraryRoute`, `AlbumRoute`, `PlayerRoute`, `reachLibraryScreen` (androidTest) | `:app` | Plan 2 Task 10, Plan 3 Tasks 9–10 |
| `PlayerHarness(player: ExoPlayer)` — `onMain`, `await`, `awaitState`, `awaitPositionAtLeast`, `awaitEnded`, `assertNoPlaybackError`, `release` | `:core:media` androidTest | Plan 3 Task 2 |
| `RealTrackBytes.musicTracks()`, `.bytesOf(song)`, `.client()` | `:core:media` androidTest | Plan 3 Task 3 |
| `NoOpPlayer` — a hand-written inert `SimpleBasePlayer` | `:core:media` androidTest | Plan 3 Task 8 |
| `ci/mutation-probes.sh`, `ci/prepare-emulator.sh`, `ci/navidrome.compose.yml`, `ci/configure-libraries.sh`, `ci/seed-fixtures.sh`, `ci/fixtures.md5` | `ci/` | Plans 1–3 |

### Hard facts, re-verified while this plan was written

- **The seeded corpus today is three music tracks and exactly one book.** `ci/seed-fixtures.sh`
  builds three 5.000 s mono LAME MP3s (385/440/495 Hz, 44100 Hz, 64 kbps, `-bitexact`) and one
  15.000 s AAC M4B, `Test Book.m4b`, with **three Nero `chpl` chapters at 0–5000, 5000–10000,
  10000–15000 ms**, titled `Chapter 1`/`Chapter 2`/`Chapter 3`, `-movflags +faststart`.
  `-movflags +use_metadata_tags` is deliberately never used — it writes `mdta`/`keys` atoms that
  break Navidrome's tag scanning. Checksums are pinned in `ci/fixtures.md5`, verified by a step in
  both workflows. **One book, three equal-length chapters, and only the faststart case** is not
  enough corpus for this plan, and Task 1 is where that is fixed.
- **Library 1 is `Music`, library 2 is `Audiobooks`**, wired by `ci/configure-libraries.sh`.
  Library 1 is path-pinned and undeletable.
- **Spike S3's API surface, re-read from
  `docs/superpowers/spikes/2026-08-21-s3-m4b-chapters-over-http.md`, is exact and non-obvious:**
  - `androidx.media3.inspector.MetadataRetriever` — in `androidx.media3:media3-inspector:1.11.0`.
    **`androidx.media3.exoplayer.MetadataRetriever` genuinely does not exist in 1.11.0** (removed
    after deprecation in 1.9); a `javac` probe proved it.
  - `androidx.media3.extractor.metadata.Chapter` — an interface, in `media3-extractor`, with
    `getStartTimeMs(): long`, `getEndTimeMs(): long`, `getTitle(): androidx.media3.common.Label`,
    `isHidden(): boolean`. **`getTitle()` is a `Label`, not a `String`** — use `.value`.
  - `retriever.retrieveTrackGroups(): ListenableFuture<TrackGroupArray>`, then
    `groups.get(i).getFormat(j).metadata` (nullable) → iterate `Metadata.Entry`.
  - `MetadataRetriever` is `AutoCloseable`; `close()` it.
  - **The footgun, measured twice:** the bare `MetadataRetriever.Builder(context, item).build()`
    form drops `chap`-track chapters entirely *and* returns
    `endTimeMs = -9223372036854775807` (`C.TIME_UNSET`) for every `chpl` chapter — no exception,
    no warning. **`setMediaSourceFactory(...)` is required.** Task 3's tests are shaped around
    making that failure loud.
- **Spike S3 did not test against Navidrome.** Its own words: *"this spike only demonstrated the
  mechanism works over HTTP … against a generic Range-compliant server — not against Navidrome's
  actual `format=raw` streaming path, its auth handshake, or its real response headers … Plan 4
  should treat that as still open."* Spec §5 says the same, and §12 carries it as a risk row.
  **Task 3 closes it, live, and Task 10 updates both.**
- **`media3-inspector:1.11.0` publishes**, resolved against
  `https://dl.google.com/dl/android/maven2/androidx/media3/media3-inspector/maven-metadata.xml`
  while Plan 3 was written. It is not pulled in by `media3-exoplayer`.
- **`ci/mutation-probes.sh` is a committed regression list, not a gate.** Read its header before
  adding to it. Two mechanical details bite anyone extending it: `run_suite()` names the Gradle
  test tasks it runs, and `revert()` names an explicit **file list** for `git checkout --`. A probe
  whose file is not in that list mutates the tree and never reverts it. Both must grow together.
- **`ConventionTest` will fail a new Gradle project that has no `coverageFloors` entry**, and its
  `no module configures android or kotlin blocks directly` rule allows only `namespace` in a
  module's own `android { }` block. `:feature:book` inherits both.
- **`excludeByteBuddyFromInstrumentedTests`** already strips Byte Buddy from every `androidTest*`
  configuration project-wide, and `configureKotlinAndroid` already sets
  `testInstrumentationRunner` and `enableAndroidTestCoverage`. A new Android module needs no
  build-logic change to be measured.

### The defect class this plan is written against

Five review rounds on this project have now found the same failure: **assertions that execute but
do not discriminate.** Each round closed one "unit" and left the next unasked — endpoint, then
request parameter, then type, then **field**, then **collection order**. The rules bind every test
in this plan:

1. **The unit is the field.** For every field this plan's code assigns, an assertion must fail when
   that field becomes a constant.
2. **A value observed at exactly one value is not tested.** Vary only the argument under test; hold
   everything else constant; assert both observations.
3. **`allMatch`/`anyMatch`/`none` are vacuously true on an empty collection**, and an assertion
   loop over a computed collection runs zero times on an empty one. Map the field and assert the
   exact list.
4. **Order is a property.** `containsExactlyInAnyOrder` where order is meaningful asserts nothing
   about order — **and chapter order is meaningful**, as is bookshelf order, as is the order of the
   files in a multi-file book.
5. **A gate that reports the absence of a problem must be provably incapable of staying quiet when
   it did not run.**
6. **Coverage floors cannot catch this class.** A constant field assignment removes no branch; this
   was verified at the bytecode level on this project, not argued.

**This plan's own version of rule 1, and the one to watch:**

- **A resume test that asserts the player was *told* to seek proves nothing.** `seekTo` having been
  called, `ResumeTarget.startPositionMs` being right, the policy having been consulted — all of
  those are satisfied by a player that then ignores the answer, by a URL that 404s into a swallowed
  error, and by a decoder that never produced a sample. What must be asserted is the **position
  playback actually reached, and then advanced from**.
- **One book is not a corpus.** With one book, "resume book X at position P" and "resume at the
  only stored position there is" are the same program. Every resume assertion in this plan involves
  **at least two books and at least two positions**, and Task 1 exists to make that possible.
- **A position that lands on a chapter boundary is not a test.** If the stored position is 5000 ms
  and chapter 2 starts at 5000 ms, "resumed exactly" and "resumed at the chapter start" are
  indistinguishable. Every resume fixture position in this plan is deliberately **off** every
  chapter boundary in its book, and the assertions say so.
- **A smart-rewind case whose rewind is zero cannot fail.** The 0 ms band is asserted *as* zero
  exactly once, on purpose, and every other band carries a **distinct non-zero** value so that a
  constant satisfies at most one of them.
- **"No position reached the server" is a claim about absence** — rule 5. Task 6 asserts it with a
  positive control, so a check that recorded nothing cannot pass.

### Where Plan 3's seam does not fit, and what this plan does about it

Plan 3 Task 8 shaped `ResumePolicy` so that Plan 4 *"swaps the policy, and touches nothing else"*.
That held for the position, which is the guarantee that matters. It did not hold completely. Four
findings, three of them additive and one of them a design correction to Plan 3's stated intent:

1. **`resolve(mediaIds, requestedIndex)` cannot express intent, and this plan does not pretend it
   can.** Plan 3's own prose says a policy *"may still override [the index], which is how the
   audiobook plan resumes a book at chapter 14."* **Plan 4 deliberately does not do that**, because
   the signature cannot distinguish *"play this book"* from *"play chapter 5 of this book"* — both
   arrive as a list of media ids and an index, and index 0 is simultaneously the natural default
   and the legitimate value for *"play chapter 1 from the top"*. A policy that overrode index 0 to
   "wherever you were" would make tapping chapter 1 jump to chapter 14, which is a worse bug than
   the one being fixed.
   **The resolution keeps the seam intact:** the **caller** decides the index, because the caller
   is the only party that knows the intent; the **policy** decides the position, which is the thing
   no caller is permitted to choose. `BookPlaybackLauncher.resume(bookId)` builds the queue with
   `startIndex` = the file the listener was in; `BookPlaybackLauncher.playFile(bookId, mediaId)`
   builds it with `startIndex` = that file. `ResumePolicy` is unchanged, `MuPlayer` is unchanged,
   and the guarantee *"no code path can set a wrong position"* is unchanged. Task 6 states this at
   the point of use, and Task 10 corrects spec §3's wording accordingly.
2. **`PlaybackState` carries no `mediaType` and no `speed`**, so nothing above `:core:media` can
   tell a book from a song or show the speed a book is playing at. Both are needed by Task 9's
   navigation and player. **Additive:** Task 7 adds two fields to Plan 3's `PlaybackState` and maps
   them in `PlaybackConnection`. No existing field changes meaning.
3. **`MediaProgressDao` has no `Flow` and no way to clear a row.** The resume snapshot must be kept
   current without a blocking read on the player's application thread — which Plan 3's own
   `ResumePolicy` doc demands — and "start this book from the beginning" is expressed by clearing
   progress, not by overriding a position (which the seam forbids, correctly). **Additive:** Task 2
   adds `observeAll()`, `findAll(mediaIds)` and `clear(mediaIds)`.
4. **`MuPlaybackService` builds its player through `MuPlayerFactory.create()` and never keeps the
   `ExoPlayer`.** `setSkipSilenceEnabled` is on `ExoPlayer`, not on `Player`, so silence skipping
   is unreachable from what the service holds. **Additive:** Task 7 adds
   `MuPlayerFactory.wrap(exoPlayer: ExoPlayer): MuPlayer` and has the service call
   `createExoPlayer()` then `wrap(...)`. `create()` keeps working and keeps its meaning
   (`wrap(createExoPlayer())`), so Plan 3's tests do not move.

And one **debt Plan 3 recorded honestly and handed here**: its Task 8 Step 10, mutation 4, removed
the `DISCONTINUITY_REASON_SILENCE_SKIP` guard from `ProgressWriter` and observed that **no test
failed**, because nothing in Plan 3 skips silence. It named the audiobook plan as the one that owes
the assertion. **Task 7 pays it**, with a real player really skipping real silence.

### Scope discipline

Plan 4 is **audiobooks**. Explicitly **not** in this plan:

- **Android Auto and Wear OS** — Plan 5. This plan adds no browse tree; `onGetLibraryRoot` and
  `onGetChildren` keep returning `SessionError.ERROR_NOT_SUPPORTED`. It *does* implement
  `onPlaybackResumption` (Task 6), because that callback is the lock screen and the headset button
  asking "carry on with what I was listening to", which is this plan's subject and not Plan 5's —
  spec §10's own note says it belongs to *"the plan that resumes"*.
- **Sonos and DLNA casting** — Plan 6. No `core/cast`, no proxy, no SSDP.
- **Bindery and Lidarr** — Plan 7.
- **Library browsing, search and library-scoped shuffle** — **Plan 2's**. This plan consumes
  `LibraryRepository` and `BrowseDao`; it does not rebuild either, and it adds no second shuffle.
- **Core playback, the queue, the notification, gapless and the media cache** — **Plan 3's**. This
  plan consumes `MuPlaybackService`, `QueueRepository`, `MuPlayer` and `MediaCache`.
- **ReplayGain.** `media_progress.gainDb` stays unwritten and unapplied. Spec §4 says the client
  applies ReplayGain; that is a gain stage in the audio pipeline and it is nobody's task yet.
  Task 2 states what the column means and Task 10 records it in the spec as still deferred, rather
  than leaving a column that looks implemented.
- **Server-side progress sync.** Spec §4 and §11 rule it out. **Task 6 makes that checkable**
  rather than promised: no `createBookmark`, no `savePlayQueue`, no `savePlayQueueByIndex`, no
  `scrobble`. If you find yourself reading spec §4's note about `createBookmark.position` being in
  milliseconds while `bookmarkPosition` is in seconds, you are already off the plan — that note
  exists to explain why the write path does not exist.
- **Offline downloads** — deferred by spec §9.
- **A second player screen for music.** Plan 3's `PlayerScreen` and `MiniPlayer` stay exactly as
  they are; Task 9 adds a *book* player beside them and lets `:app` choose between them.

---

## File Structure

| File | Responsibility |
|---|---|
| `ci/seed-fixtures.sh` | **modify** — three more books: a second chaptered M4B, a non-faststart M4B, and a multi-file book |
| `ci/fixtures.md5` | **regenerated** by the script above |
| `settings.gradle.kts` | **modify** — include `:feature:book` |
| `gradle/libs.versions.toml` | **modify** — `media3-inspector` at the existing `media3` ref |
| `build.gradle.kts` | **modify** — coverage floors for `:feature:book`, new floors in `:core:media` and `:core:database` |
| `core/model/src/main/kotlin/app/muplay/model/Chapter.kt` | **new** — one chapter, as read from a file's own bytes |
| `core/model/src/main/kotlin/app/muplay/model/BookSettings.kt` | **new** — speed and silence skipping, at the grain of a *book* |
| `core/model/src/main/kotlin/app/muplay/model/BookSummary.kt` | **new** — one shelf row |
| `core/model/src/main/kotlin/app/muplay/model/SleepTimer.kt` | **new** — `SleepTimerRequest`, `SleepTimerState` |
| `core/database/src/main/kotlin/app/muplay/database/entity/BookSettingsEntity.kt` | **new** — table `book_settings`, keyed on the book id |
| `core/database/src/main/kotlin/app/muplay/database/entity/ChapterEntity.kt` | **new** — table `chapters`, the parsed-once cache |
| `core/database/src/main/kotlin/app/muplay/database/dao/BookSettingsDao.kt` | **new** |
| `core/database/src/main/kotlin/app/muplay/database/dao/ChapterDao.kt` | **new** |
| `core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt` | **modify** — `observeAll`, `findAll(mediaIds)`, `clear(mediaIds)` |
| `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt` | **modify** — version **5**, two entities, `MIGRATION_4_5` |
| `core/database/src/main/kotlin/app/muplay/database/Migrations.kt` | **new** — the project's first migration, SQL copied from the exported schema |
| `core/database/src/main/kotlin/app/muplay/database/AudiobookRepository.kt` | **new** — what a book is, the shelf, the settings |
| `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt` | **modify** — the two new DAOs, and the migration on the builder |
| `core/media/build.gradle.kts` | **modify** — `media3-inspector` |
| `core/media/src/main/kotlin/app/muplay/media/ChapterReader.kt` | **new** — `MetadataRetriever` with an explicit `MediaSourceFactory` |
| `core/media/src/main/kotlin/app/muplay/media/ChapterRepository.kt` | **new** — parse once, then serve from Room |
| `core/media/src/main/kotlin/app/muplay/media/BookTimeline.kt` | **new** — pure: files + chapters → one ordered chapter list, and navigation over it |
| `core/media/src/main/kotlin/app/muplay/media/SmartRewind.kt` | **new** — pure: how far back, given how long away |
| `core/media/src/main/kotlin/app/muplay/media/AudiobookSnapshot.kt` | **new** — the in-memory, never-blocking view of `media_progress` restricted to books |
| `core/media/src/main/kotlin/app/muplay/media/AudiobookResumePolicy.kt` | **new** — the `ResumePolicy` that replaces `NeverResume` |
| `core/media/src/main/kotlin/app/muplay/media/BookSpeedController.kt` | **new** — speed and silence skipping, applied and persisted per book |
| `core/media/src/main/kotlin/app/muplay/media/SleepTimerFade.kt` | **new** — pure: remaining time → volume |
| `core/media/src/main/kotlin/app/muplay/media/SleepTimerController.kt` | **new** — the countdown, the fade, end-of-chapter |
| `core/media/src/main/kotlin/app/muplay/media/ShakeDetector.kt` | **new** — pure: accelerometer samples → "that was a shake" |
| `core/media/src/main/kotlin/app/muplay/media/ShakeSensor.kt` | **new** — the `SensorEventListener` shim, and nothing else |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt` | **modify** — `mediaType`, `speed` |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackConnection.kt` | **modify** — map the two new fields |
| `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt` | **modify** — `wrap(exoPlayer)` |
| `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` | **modify** — snapshot, speed controller, sleep timer, `onPlaybackResumption` |
| `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` | **modify** — the resume-policy binding is swapped |
| `feature/book/build.gradle.kts` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookshelfUiState.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookshelfViewModel.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookshelfScreen.kt` | **new** — continue listening, then the rest |
| `feature/book/src/main/kotlin/app/muplay/book/BookUiState.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookViewModel.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookScreen.kt` | **new** — one book, its chapters, resume |
| `feature/book/src/main/kotlin/app/muplay/book/BookPlayerUiState.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookPlayerViewModel.kt` | **new** |
| `feature/book/src/main/kotlin/app/muplay/book/BookPlayerScreen.kt` | **new** — chapters, speed, sleep timer |
| `feature/book/src/main/kotlin/app/muplay/book/BookPlaybackLauncher.kt` | **new** — the caller that decides the index |
| `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` | **modify** — the shelf, the book and the book-player destinations |
| `app/src/main/kotlin/app/muplay/ui/navigation/BookshelfRoute.kt` | **new** |
| `app/src/main/kotlin/app/muplay/ui/navigation/BookRoute.kt` | **new** |
| `app/src/main/kotlin/app/muplay/ui/navigation/BookPlayerRoute.kt` | **new** |
| `app/src/androidTest/kotlin/app/muplay/AudiobookResumeJourneyTest.kt` | **new** — Tier 2: the original complaint |
| `app/src/androidTest/kotlin/app/muplay/AudiobookChapterJourneyTest.kt` | **new** — Tier 2: chapters, speed, sleep timer on a real screen |
| `.github/workflows/e2e.yml` | **modify** — `:feature:book:connectedDebugAndroidTest`, the new journeys |
| `.github/workflows/pr.yml` | **modify** — the fixture-checksum step covers the new books |
| `ci/mutation-probes.sh` | **modify** — this plan's probes, plus the `run_suite`/`revert` lists |
| `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` | **modify** — §3, §5, §10, §12 corrections listed in Task 10 |

---

## Task 1: The corpus — three more books, because one book proves nothing

**Files:**
- Modify: `ci/seed-fixtures.sh`
- Create: `ci/probe-chapters.sh`
- Create (generated, committed): `ci/fixtures/Audiobooks/Second Author/Second Book/Second Book.m4b`,
  `ci/fixtures/Audiobooks/Third Author/Tail Book/Tail Book.m4b`,
  `ci/fixtures/Audiobooks/Fourth Author/Multi Part Book/0{1,2,3} - Part {One,Two,Three}.mp3`
- Modify (regenerated, committed): `ci/fixtures.md5`
- Create (generated, committed): `core/testing/src/main/resources/fixtures/books.tsv`
- Create: `core/testing/src/main/kotlin/app/muplay/testing/BookFixtures.kt`
- Test: `core/testing/src/test/kotlin/app/muplay/testing/BookFixturesTest.kt`
- Modify: `core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt`
- Modify: `.github/workflows/pr.yml`, `.github/workflows/e2e.yml`
- Modify: `build.gradle.kts` (`:core:testing` floors)

**Interfaces:**
- Consumes: `app.muplay.network.SubsonicSource.getAlbumList2/getAlbum/streamUrl` (Plan 2 Task 3,
  Plan 3 Task 1), `app.muplay.model.AlbumListType.ALPHABETICAL_BY_NAME`,
  `app.muplay.model.StreamFormat.Raw`, and `LiveNavidromeTest`'s existing container helpers.
- Produces:
  - `data class ExpectedTrack(val path: String, val durationMs: Long, val title: String, val trackNumber: Int)`
  - `data class ExpectedChapter(val startMs: Long, val endMs: Long, val title: String)`
  - `data class ExpectedBook(val albumName: String, val authorName: String, val tracks: List<ExpectedTrack>, val chapters: List<ExpectedChapter>)`
    with `val durationMs: Long` (the sum of its tracks' durations)
  - `object BookFixtures` with `val TEST_BOOK`, `val SECOND_BOOK`, `val TAIL_BOOK`,
    `val MULTI_PART_BOOK`, `val ALL_BOOKS: List<ExpectedBook>`, `val MUSIC_TRACKS: List<ExpectedTrack>`,
    and `const val RESOURCE = "/fixtures/books.tsv"`
  - `ci/probe-chapters.sh` (regenerate) and `ci/probe-chapters.sh --check` (fail on drift)

### Why the corpus is a task and not a footnote

The seeded corpus today is **one book with three equal-length chapters**. Against that corpus, all
of the following programs are indistinguishable:

- "resume book *B* at position *P*" and "resume at the only stored position there is";
- "chapter *k* starts at `chapters[k].startMs`" and "chapter *k* starts at `k * 5000`";
- "a book's chapters come from its own bytes" and "a book has three chapters";
- "the shelf is ordered by when each book was last heard" and "the shelf has one row".

Every one of those is the defect class in §"The defect class this plan is written against" — a
value observed at exactly one value. **No amount of test-writing later in this plan can recover
from a corpus that cannot tell two answers apart**, so the corpus comes first.

Three books are added, each earning its place by being different from the others in a way that
breaks a specific constant:

| Book | Why it exists |
|---|---|
| **Second Book** — 21 s, **four** chapters at 0/4000/9000/15000 ms, **unequal** lengths (4 s, 5 s, 6 s, 6 s), faststart `chpl` | Breaks *"three chapters"* and *"chapters are 5 s long"* at the same time. Unequal lengths are the point: with uniform chapters, `startMs = index * length` passes every assertion. |
| **Tail Book** — 12 s, **two** chapters at 0/7000 ms, **no `-movflags +faststart`** so `moov` trails `mdat` | Spike S3 says non-faststart works because Media3 issues a targeted Range request to the tail. That was measured **against a hand-rolled Python server, never against Navidrome** — S3 says so itself, spec §5 says so, and spec §12 carries it as a risk. This file is what closes it. |
| **Multi Part Book** — three MP3s, **4 s / 6 s / 5 s**, no chapter atoms at all | The ordinary shape of a ripped audiobook: one file per chapter, no `chpl` anywhere. It is the only fixture that exercises resuming onto **the right file**, which is half of "per-book resume", and its three **distinct** durations mean a duration mapping cannot pass on a constant. |

The existing `Test Book` is not changed. It is the faststart three-chapter baseline and every
committed checksum for it must stay exactly as it is.

### `ffprobe` is the oracle, and the oracle is committed

The chapter values these fixtures carry are not this project's opinion — they are what is in the
files, and `ffprobe` reads them independently of Media3. So the expectations live in a committed
`books.tsv` derived by `ffprobe`, and Task 3 asserts that **Media3 agrees with `ffprobe`**. Two
independent readers of the same bytes agreeing is an oracle; a test asserting that Media3 returns
whatever Media3 returned last time is not. This is the same stance §10 takes with the OpenSubsonic
OpenAPI spec.

`ci/probe-chapters.sh --check` re-derives the table and fails on any difference, so the oracle
cannot rot silently. It is a **read** — it never re-encodes anything, which is why it can run in CI
while `ci/seed-fixtures.sh` (which re-encodes, and whose output is only bit-reproducible for one
ffmpeg version) cannot.

### What this task will break elsewhere, on purpose

The corpus is shared. Adding five files to library 2 moves numbers that earlier plans pinned:

- **`getScanStatus.count` goes from 4 to 9.** Any assertion on that number — Plan 2 Task 6's sync
  tests, `LiveNavidromeTest` — must be updated to the new value, not loosened to "greater than
  zero". A count assertion that survives adding five files was never asserting the count.
- **Library 2's album list goes from one album to four.** Plan 2's browse and shuffle tests that
  say "the Audiobooks library contains `Test Book`" become "contains exactly these four".
- **`ScopedShuffleJourneyTest` gets stronger for free**: there are now four books that must never
  appear in a music shuffle instead of one.

Run `./gradlew test` and the Tier 1 live suite immediately after regenerating the fixtures and fix
every number the change moved. **Do not weaken an assertion to make it pass** — re-measure it.

- [ ] **Step 1: Extend the seed script**

`ci/seed-fixtures.sh` — append, after the existing `Test Book` block and before the `find … md5sum`
line at the end:

```bash
mkdir -p "$OUT/Audiobooks/Second Author/Second Book" \
         "$OUT/Audiobooks/Third Author/Tail Book" \
         "$OUT/Audiobooks/Fourth Author/Multi Part Book"

# ---------------------------------------------------------------------------------------------
# Second Book: four chapters of DELIBERATELY UNEQUAL length (4 s, 5 s, 6 s, 6 s).
#
# Equal-length chapters are why the original single fixture cannot discriminate: with 5 s
# chapters, `startMs == index * 5000` satisfies every assertion anyone would write, and a chapter
# reader that ignored the file entirely would pass. Unequal lengths make that constant wrong at
# chapter 2 and every chapter after it.
# ---------------------------------------------------------------------------------------------
CHAPTERS="$(mktemp)"
cat > "$CHAPTERS" << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=4000
title=Prologue
[CHAPTER]
TIMEBASE=1/1000
START=4000
END=9000
title=The Long Middle
[CHAPTER]
TIMEBASE=1/1000
START=9000
END=15000
title=A Turn
[CHAPTER]
TIMEBASE=1/1000
START=15000
END=21000
title=Epilogue
EOF
ffmpeg -y -f lavfi -i "sine=frequency=180:duration=21:sample_rate=44100" \
  -i "$CHAPTERS" -map_metadata 1 \
  -c:a aac -b:a 32k -ac 1 -bitexact \
  -metadata title="Second Book" -metadata artist="Second Author" -metadata album="Second Book" \
  -movflags +faststart \
  "$OUT/Audiobooks/Second Author/Second Book/Second Book.m4b"
rm -f "$CHAPTERS"

# ---------------------------------------------------------------------------------------------
# Tail Book: two chapters, and NO +faststart -- `moov` trails `mdat`.
#
# Spike S3 found that Media3 reads a non-faststart file over HTTP by issuing a targeted Range
# request to the tail rather than downloading the whole file, but it measured that against a
# hand-rolled Python server. Whether Navidrome's `format=raw` path behaves the same is the open
# question spec section 5 and section 12 both carry. This file is the only way to answer it.
#
# Note the missing `-movflags +faststart`. That omission is the entire point of this fixture; do
# not "fix" it.
# ---------------------------------------------------------------------------------------------
CHAPTERS="$(mktemp)"
cat > "$CHAPTERS" << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=7000
title=Head
[CHAPTER]
TIMEBASE=1/1000
START=7000
END=12000
title=Tail
EOF
ffmpeg -y -f lavfi -i "sine=frequency=160:duration=12:sample_rate=44100" \
  -i "$CHAPTERS" -map_metadata 1 \
  -c:a aac -b:a 32k -ac 1 -bitexact \
  -metadata title="Tail Book" -metadata artist="Third Author" -metadata album="Tail Book" \
  "$OUT/Audiobooks/Third Author/Tail Book/Tail Book.m4b"
rm -f "$CHAPTERS"

# ---------------------------------------------------------------------------------------------
# Multi Part Book: one file per chapter, no chapter atoms anywhere.
#
# This is the ordinary shape of a ripped audiobook and it is the only fixture that can prove
# "resume came back on the RIGHT FILE", which is half of per-book resume. The three durations are
# 4 s / 6 s / 5 s -- deliberately different from each other, so a duration mapping cannot pass by
# returning a constant, and deliberately NOT monotonic, so "sorted by duration" is not
# accidentally the same list as "sorted by track number".
# ---------------------------------------------------------------------------------------------
part_titles=("Part One" "Part Two" "Part Three")
part_durations=(4 6 5)
part_freqs=(200 240 280)
for i in 0 1 2; do
  n=$((i + 1))
  ffmpeg -y -f lavfi \
    -i "sine=frequency=${part_freqs[$i]}:duration=${part_durations[$i]}:sample_rate=44100" \
    -c:a libmp3lame -b:a 64k -ac 1 -bitexact -map_metadata -1 \
    -metadata title="${part_titles[$i]}" -metadata artist="Fourth Author" \
    -metadata album="Multi Part Book" -metadata track="$n" \
    "$OUT/Audiobooks/Fourth Author/Multi Part Book/0$n - ${part_titles[$i]}.mp3"
done
```

- [ ] **Step 2: Write the chapter-oracle script**

`ci/probe-chapters.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
#
# Derives the committed fixture oracle at core/testing/src/main/resources/fixtures/books.tsv from
# the committed audio files, using ffprobe.
#
# WHY THIS EXISTS AND WHY IT IS SEPARATE FROM seed-fixtures.sh
# ============================================================================================
# `ci/seed-fixtures.sh` *encodes*, and its output is bit-reproducible only for one ffmpeg version
# -- which is why the fixtures are committed binaries verified by `md5sum -c ci/fixtures.md5`
# rather than rebuilt in CI. This script only *reads*. Reading a `chpl`/`chap` atom or a stream
# duration is stable across ffprobe versions in a way that encoding is not, so this one CAN run in
# CI, and does.
#
# WHAT IT IS FOR. Task 3 asserts that Media3 reads the same chapters ffprobe reads. Two
# independent readers of the same bytes agreeing is an oracle. A test asserting Media3 returns
# whatever Media3 returned last time is not, and this project has been bitten five times by
# assertions that execute without discriminating.
#
# USAGE:  ci/probe-chapters.sh           # regenerate the table
#         ci/probe-chapters.sh --check   # fail if the committed table no longer matches the files
#
# The table is tab-separated, two record kinds, and its ORDER IS PART OF THE ORACLE:
#   track    <path>   <durationMs>  <title>  <trackNumber>
#   chapter  <path>   <startMs>     <endMs>  <title>
# Tracks are emitted sorted by path; chapters are emitted in the order ffprobe lists them, which
# is the order they appear in the file. Task 3 asserts chapter order with `containsExactly`, so an
# oracle that sorted them would quietly destroy the only evidence of ordering there is.

cd "$(dirname "$0")/.."
FIXTURES="ci/fixtures"
TABLE="core/testing/src/main/resources/fixtures/books.tsv"

if ! command -v ffprobe > /dev/null; then
  echo "ffprobe not found. Install ffmpeg." >&2
  exit 2
fi

emit() {
  printf '# generated by ci/probe-chapters.sh -- do not hand-edit\n'
  printf '# kind\tpath\ta\tb\tc\n'
  find "$FIXTURES" -type f \( -name '*.mp3' -o -name '*.m4b' \) | LC_ALL=C sort | while read -r f; do
    rel="${f#"$FIXTURES"/}"
    json="$(ffprobe -v quiet -print_format json -show_format -show_chapters "$f")"
    duration_ms="$(printf '%s' "$json" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(int(round(float(d["format"]["duration"])*1000)))')"
    title="$(printf '%s' "$json" | python3 -c '
import json,sys
print(json.load(sys.stdin)["format"].get("tags",{}).get("title",""))')"
    track="$(printf '%s' "$json" | python3 -c '
import json,sys
t=json.load(sys.stdin)["format"].get("tags",{}).get("track","0")
print(int(str(t).split("/")[0] or 0))')"
    printf 'track\t%s\t%s\t%s\t%s\n' "$rel" "$duration_ms" "$title" "$track"
    printf '%s' "$json" | python3 -c '
import json,sys
for c in json.load(sys.stdin)["chapters"]:
    tb_num, tb_den = (int(x) for x in c["time_base"].split("/"))
    start = int(round(int(c["start"]) * tb_num * 1000 / tb_den))
    end = int(round(int(c["end"]) * tb_num * 1000 / tb_den))
    print("chapter\t'"$rel"'\t%d\t%d\t%s" % (start, end, c.get("tags", {}).get("title", "")))'
  done
}

if [ "${1:-}" = "--check" ]; then
  if ! diff -u "$TABLE" <(emit); then
    echo "" >&2
    echo "The committed chapter oracle no longer matches the committed audio fixtures." >&2
    echo "Either a fixture changed (regenerate the table: ci/probe-chapters.sh) or ffprobe" >&2
    echo "disagrees with what was recorded. Read the diff before doing either." >&2
    exit 1
  fi
  echo "chapter oracle matches the fixtures"
else
  mkdir -p "$(dirname "$TABLE")"
  emit > "$TABLE"
  echo "wrote $TABLE"
fi
```

Then run both, in order:

```bash
chmod +x ci/probe-chapters.sh
./ci/seed-fixtures.sh          # rebuilds every fixture and rewrites ci/fixtures.md5
./ci/probe-chapters.sh
git diff --stat ci/ core/testing/src/main/resources/fixtures/
```

**Read the diff.** `ci/fixtures.md5` must gain exactly five lines and change none of the four
existing ones. If an existing checksum moved, the local ffmpeg is not the one the committed
fixtures were built with; **stop, and rebuild only the new files by hand** rather than committing
a corpus that invalidates every earlier plan's measurements.

- [ ] **Step 3: Write the failing fixture-parser test**

`core/testing/src/test/kotlin/app/muplay/testing/BookFixturesTest.kt`:

```kotlin
package app.muplay.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The committed oracle, parsed.
 *
 * Every assertion here is over **exact lists in exact order**, never `containsAnyOf` or
 * `anyMatch`: chapter order is a property of a book, and a `containsExactlyInAnyOrder` here would
 * let a reader that returned chapters backwards pass in Task 3 and produce a book that plays its
 * epilogue first.
 */
class BookFixturesTest {

  @Test
  fun `the four books are distinguishable by chapter count alone`() {
    // The corpus exists to break constants. If two books had the same chapter count *and* the
    // same boundaries, a chapter reader could return one of them for both and pass.
    assertThat(BookFixtures.ALL_BOOKS.map { it.albumName to it.chapters.size })
      .containsExactly(
        "Multi Part Book" to 0,
        "Second Book" to 4,
        "Tail Book" to 2,
        "Test Book" to 3,
      )
  }

  @Test
  fun `Second Book's chapters are unequal in length and in order`() {
    // Unequal lengths are the whole reason this fixture exists: `startMs == index * 5000` is true
    // of Test Book and false here, at chapter 2 and at every chapter after it.
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.startMs })
      .containsExactly(0L, 4_000L, 9_000L, 15_000L)
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.endMs })
      .containsExactly(4_000L, 9_000L, 15_000L, 21_000L)
    assertThat(BookFixtures.SECOND_BOOK.chapters.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
  }

  @Test
  fun `Test Book is still the equal-length baseline it always was`() {
    // A second observation of the same fields on a different book. Together with the assertion
    // above, a parser that returned a hardcoded chapter list fails one of the two.
    assertThat(BookFixtures.TEST_BOOK.chapters.map { it.startMs })
      .containsExactly(0L, 5_000L, 10_000L)
    assertThat(BookFixtures.TEST_BOOK.chapters.map { it.title })
      .containsExactly("Chapter 1", "Chapter 2", "Chapter 3")
  }

  @Test
  fun `Multi Part Book is three files with three different durations, in track order`() {
    // Not monotonic on purpose (4, 6, 5): "sorted by duration" and "sorted by track number" are
    // different lists here, so a repository that sorted by the wrong key cannot pass by accident.
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.title })
      .containsExactly("Part One", "Part Two", "Part Three")
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.durationMs })
      .containsExactly(4_000L, 6_000L, 5_000L)
    assertThat(BookFixtures.MULTI_PART_BOOK.tracks.map { it.trackNumber })
      .containsExactly(1, 2, 3)
  }

  @Test
  fun `a book's duration is the sum of its files`() {
    // Two observations, one single-file and one multi-file, because "duration == the one file's
    // duration" and "duration == the sum" are the same program for every book but this one.
    assertThat(BookFixtures.MULTI_PART_BOOK.durationMs).isEqualTo(15_000L)
    assertThat(BookFixtures.TAIL_BOOK.durationMs).isEqualTo(12_000L)
  }

  @Test
  fun `the music tracks are not books`() {
    // The oracle covers the whole corpus, and the Music subtree must stay chapterless and
    // three-strong -- Plan 3's gapless measurement is arithmetic over exactly these.
    assertThat(BookFixtures.MUSIC_TRACKS.map { it.title })
      .containsExactly("Track 1", "Track 2", "Track 3")
    assertThat(BookFixtures.MUSIC_TRACKS.map { it.durationMs }).containsExactly(5_000L, 5_000L, 5_000L)
  }

  @Test
  fun `every fixture in the table is accounted for by a named constant`() {
    // Rule 5, applied to this class. If someone adds a fixture and forgets to name it, the parsed
    // table and the named constants diverge -- and every other test in this file would stay green,
    // because they only look at the constants they know about.
    val named = (BookFixtures.ALL_BOOKS.flatMap { it.tracks } + BookFixtures.MUSIC_TRACKS)
      .map { it.path }

    assertThat(named).containsExactlyInAnyOrderElementsOf(BookFixtures.allTrackPaths())
    // ...and the table is not empty, which `containsExactlyInAnyOrder` on two empty lists would
    // have satisfied.
    assertThat(named).hasSize(8)
  }
}
```

- [ ] **Step 4: Run it, watch it fail, then write the parser**

Run: `./gradlew :core:testing:test --tests '*BookFixturesTest*'`
Expected: FAIL with `Unresolved reference: BookFixtures`.

`core/testing/src/main/kotlin/app/muplay/testing/BookFixtures.kt`:

```kotlin
package app.muplay.testing

/** One audio file in the seeded corpus, as `ffprobe` reads it. */
data class ExpectedTrack(
  val path: String,
  val durationMs: Long,
  val title: String,
  val trackNumber: Int,
)

/** One chapter atom in a file, as `ffprobe` reads it. */
data class ExpectedChapter(val startMs: Long, val endMs: Long, val title: String)

/**
 * One book in the seeded corpus: its files in track order, and every chapter atom across them in
 * file order.
 */
data class ExpectedBook(
  val albumName: String,
  val authorName: String,
  val tracks: List<ExpectedTrack>,
  val chapters: List<ExpectedChapter>,
) {
  val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/**
 * The committed fixture oracle, parsed from `/fixtures/books.tsv`.
 *
 * That file is derived from the committed audio by `ci/probe-chapters.sh`, which uses **ffprobe**
 * — a reader entirely independent of Media3. Task 3 asserts that Media3 agrees with it. Two
 * independent readers of the same bytes agreeing is evidence; a golden file recording what this
 * project's own code produced is not, and this project has paid for that distinction repeatedly.
 *
 * The resource is on the JVM classpath, which means it is also packaged into the APK's Java
 * resources, so instrumented tests read it the same way.
 *
 * Parsed rather than transcribed on purpose: a hand-written copy of the table is a second truth
 * that drifts, and `ci/probe-chapters.sh --check` would have no way to notice.
 */
object BookFixtures {

  const val RESOURCE = "/fixtures/books.tsv"

  private val rows: List<List<String>> by lazy {
    val text = checkNotNull(BookFixtures::class.java.getResourceAsStream(RESOURCE)) {
      "$RESOURCE is not on the classpath. Run ci/probe-chapters.sh and commit the result."
    }.use { it.readBytes().decodeToString() }

    text.lineSequence()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { it.split('\t') }
      .toList()
      .also { check(it.isNotEmpty()) { "$RESOURCE parsed to zero rows" } }
  }

  private val tracksByPath: List<ExpectedTrack> by lazy {
    rows.filter { it[0] == "track" }
      .map { ExpectedTrack(path = it[1], durationMs = it[2].toLong(), title = it[3], trackNumber = it[4].toInt()) }
  }

  private val chaptersByPath: Map<String, List<ExpectedChapter>> by lazy {
    rows.filter { it[0] == "chapter" }
      .groupBy({ it[1] }) { ExpectedChapter(startMs = it[2].toLong(), endMs = it[3].toLong(), title = it[4]) }
  }

  private fun book(albumName: String, authorName: String, directory: String): ExpectedBook {
    // Ordered by track number, then by path -- the order a listener expects a book's files in, and
    // the order `AudiobookRepository.chapterFiles` must reproduce (Task 4).
    val tracks = tracksByPath.filter { it.path.startsWith("$directory/") }
      .sortedWith(compareBy({ it.trackNumber }, { it.path }))
    check(tracks.isNotEmpty()) { "no fixture tracks under $directory" }
    return ExpectedBook(
      albumName = albumName,
      authorName = authorName,
      tracks = tracks,
      chapters = tracks.flatMap { chaptersByPath[it.path].orEmpty() },
    )
  }

  val TEST_BOOK: ExpectedBook by lazy { book("Test Book", "Test Author", "Audiobooks/Test Author/Test Book") }
  val SECOND_BOOK: ExpectedBook by lazy { book("Second Book", "Second Author", "Audiobooks/Second Author/Second Book") }
  val TAIL_BOOK: ExpectedBook by lazy { book("Tail Book", "Third Author", "Audiobooks/Third Author/Tail Book") }
  val MULTI_PART_BOOK: ExpectedBook by lazy {
    book("Multi Part Book", "Fourth Author", "Audiobooks/Fourth Author/Multi Part Book")
  }

  /** Alphabetical by album name — the order `getAlbumList2(ALPHABETICAL_BY_NAME)` returns them in. */
  val ALL_BOOKS: List<ExpectedBook> by lazy { listOf(MULTI_PART_BOOK, SECOND_BOOK, TAIL_BOOK, TEST_BOOK) }

  val MUSIC_TRACKS: List<ExpectedTrack> by lazy {
    tracksByPath.filter { it.path.startsWith("Music/") }.sortedBy { it.trackNumber }
  }

  /** Every path the table knows about — the input to the "nothing is unaccounted for" assertion. */
  fun allTrackPaths(): List<String> = tracksByPath.map { it.path }
}
```

- [ ] **Step 5: Run the parser test**

Run: `./gradlew :core:testing:test --tests '*BookFixturesTest*'`
Expected: PASS, 7/7.

If `the four books are distinguishable by chapter count alone` fails with
`"Multi Part Book" to 3`, the MP3s picked up chapter atoms — they must not have any. If
`Second Book`'s end times come back one millisecond off, `ffprobe` rounded differently than the
script assumed; fix the arithmetic in `probe-chapters.sh`, not the assertion.

- [ ] **Step 6: Prove Navidrome actually serves all four**

`core/network/src/test/kotlin/app/muplay/network/LiveNavidromeTest.kt` — add. These run against
the pinned container in Tier 1; follow the file's existing container-guard and client-construction
helpers rather than inventing new ones.

```kotlin
  @Test
  fun `the audiobook library holds exactly the four seeded books, in name order`() = runTest {
    // `containsExactly`, not `contains`: this is the assertion that would have to be weakened if
    // someone dropped a fixture, and weakening it is exactly what must not happen quietly. The
    // ordering is `ALPHABETICAL_BY_NAME`, which is a property of the request -- see the control
    // below for the same call scoped to the other library.
    val books = client.getAlbumList2(
      musicFolderId = AUDIOBOOK_LIBRARY_ID,
      type = AlbumListType.ALPHABETICAL_BY_NAME,
      size = 50,
      offset = 0,
    )

    assertThat(books.map { it.name })
      .containsExactly("Multi Part Book", "Second Book", "Tail Book", "Test Book")
  }

  @Test
  fun `the music library still holds exactly one album after the corpus grew`() = runTest {
    // The control. Without it, "the audiobook library has four albums" is equally satisfied by a
    // server that ignores `musicFolderId` and returns five albums to everyone -- which is spec
    // section 4's silent-scope-widening trap, and the one failure mode with no runtime signal.
    val music = client.getAlbumList2(
      musicFolderId = MUSIC_LIBRARY_ID,
      type = AlbumListType.ALPHABETICAL_BY_NAME,
      size = 50,
      offset = 0,
    )

    assertThat(music.map { it.name }).containsExactly("Test Album")
  }

  @Test
  fun `a multi-file book comes back as ordered tracks with the durations the fixture has`() = runTest {
    val books = client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 50, 0)
    val multiPart = books.single { it.name == "Multi Part Book" }

    val album = client.getAlbum(multiPart.id, AUDIOBOOK_LIBRARY_ID)

    // Track order, and three DIFFERENT durations. A server (or a mapper) that returned the files
    // sorted by name would still pass the titles assertion here, so the durations carry the real
    // discrimination: 4, 6, 5 is not sorted, and it is not constant.
    assertThat(album.songs.sortedBy { it.trackNumber }.map { it.title })
      .containsExactly("Part One", "Part Two", "Part Three")
    assertThat(album.songs.sortedBy { it.trackNumber }.map { it.durationSeconds })
      .containsExactly(4, 6, 5)
  }

  @Test
  fun `every seeded book streams raw with an accurate content length and honours Range`() = runTest {
    // The precondition for chapter extraction. Spike S3 measured Media3's tail-Range behaviour
    // against a hand-rolled Python server; whether Navidrome's `format=raw` path offers the same
    // guarantees for a NON-FASTSTART file is the open question spec section 5 records, and this is
    // where the server half of it is answered. Task 3 answers the Media3 half.
    val books = client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 50, 0)
    val observed = mutableListOf<Triple<String, Int, Boolean>>()

    for (book in books) {
      for (song in client.getAlbum(book.id, AUDIOBOOK_LIBRARY_ID).songs) {
        val url = client.streamUrl(song.id, StreamFormat.Raw)
        val full = httpClient.newCall(Request.Builder().url(url).build()).execute()
        val length = full.use { it.header("Content-Length")?.toLong() }
        checkNotNull(length) { "no Content-Length for ${song.title}" }

        // The last 16 bytes -- where a non-faststart file's `moov` lives, and the exact request
        // Media3 issues for one.
        val tail = httpClient.newCall(
          Request.Builder().url(url).header("Range", "bytes=${length - 16}-${length - 1}").build(),
        ).execute()
        tail.use {
          observed += Triple(song.title, it.code, it.body!!.bytes().size == 16)
        }
      }
    }

    // The exact list, not `allMatch`: `allMatch` over an empty list is true, and an empty list is
    // precisely what a broken album lookup would produce here.
    assertThat(observed).hasSize(6)
    assertThat(observed.map { it.second }).containsOnly(206)
    assertThat(observed.map { it.third }).containsOnly(true)
    assertThat(observed.map { it.first })
      .containsExactlyInAnyOrder(
        "Test Book", "Second Book", "Tail Book", "Part One", "Part Two", "Part Three",
      )
  }
```

> `httpClient` and `client` are `LiveNavidromeTest`'s existing fields; `AUDIOBOOK_LIBRARY_ID = 2`
> and `MUSIC_LIBRARY_ID = 1` are the ids `ci/configure-libraries.sh` creates. If that file already
> defines constants for them, use those and do not add a second pair.

- [ ] **Step 7: Fix every number this corpus moved**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./gradlew test
./gradlew :core:network:liveNavidromeTest
```

Expect failures in tests that pinned the old corpus — most likely a `getScanStatus.count` of 4 and
an audiobook album list of one. **Re-measure each and update it to the new value.** A count
assertion that you can make pass by deleting it was never asserting the count; a
`containsExactly("Test Book")` that becomes `contains("Test Book")` has stopped asserting anything
about what else is there.

Record, in the task report, every assertion this step changed and its old and new value.

- [ ] **Step 8: Wire the oracle into both tiers**

`.github/workflows/pr.yml` and `.github/workflows/e2e.yml` — after each existing
`Verify audio fixtures` step:

```yaml
      - name: Verify chapter oracle
        # `books.tsv` is what Task 3 asserts Media3 against, so it has to be re-derivable from the
        # committed audio rather than taken on trust. This step re-runs ffprobe over the fixtures
        # and diffs; it never re-encodes, which is why it can run here while seed-fixtures.sh
        # cannot. A drifted oracle would let a chapter reader that returns last week's answer stay
        # green.
        run: |
          command -v ffprobe > /dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; }
          ./ci/probe-chapters.sh --check
```

- [ ] **Step 9: Prove the oracle gate can fail**

Rule 5 applied to the gate this task adds. One at a time, reverted after each:

1. Edit one `startMs` in `core/testing/src/main/resources/fixtures/books.tsv`. Expect
   `./ci/probe-chapters.sh --check` to exit 1 and print the diff, **and** `BookFixturesTest` to
   fail. Two independent detectors, which is what makes the oracle worth committing.
2. Delete the whole `books.tsv`. Expect `BookFixtures` to throw the "not on the classpath" message
   rather than yielding empty lists that every `containsExactly` on an empty list would... *not*
   satisfy — confirm the failure names the resource, because an oracle that silently parsed to
   nothing is the vacuous-collection defect in its purest form.
3. Remove `-metadata track=` from one Multi Part Book file, re-seed only that file, re-probe.
   Expect `Multi Part Book is three files with three different durations, in track order` to fail
   on the track numbers.

- [ ] **Step 10: Measure `:core:testing`'s floor and commit**

`BookFixtures` adds real branches (`filter`, `check`, the `startsWith` scoping). Run
`./gradlew :core:testing:test jacocoTestReport`, read the measured per-class BRANCH ratio out of
`core/testing/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`, and make
`:core:testing`'s `coverageFloors` entry match — at or above 0.90, with another test rather than a
lower floor if it is short. Delete one assertion, confirm the floor goes red, restore it, and
record which one in the task report.

```bash
git add ci core/testing build.gradle.kts .github/workflows core/network/src/test
git commit -m "test: a corpus of four books, and an ffprobe oracle for their chapters"
```

---

## Task 2: Schema 5 — `book_settings`, `chapters`, and the project's first Room migration

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/Chapter.kt`
- Create: `core/model/src/main/kotlin/app/muplay/model/BookSettings.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/BookSettingsEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/ChapterEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/entity/ChapterScanEntity.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/BookSettingsDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/ChapterDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/Migrations.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Create (generated, committed): `core/database/schemas/app.muplay.database.MuPlayDatabase/5.json`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/BookSettingsDaoTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/ChapterDaoTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/MediaProgressDaoTest.kt` (modify)
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/MigrationTest.kt`
- Modify: `build.gradle.kts` (`:core:database` floors)

**Interfaces:**
- Consumes:
  - `MediaProgressEntity(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb)`
    and `MediaProgressDao(upsert, find, findAll, recentlyPlayed)` — **Plan 2 Task 1, committed.**
  - `MuPlayDatabase` at version **4** with `mediaProgressDao()`, `libraryDao()`, `browseDao()`,
    `syncWatermarkDao()` — Plan 2 Tasks 1, 4, 5, 6. If Plan 2 landed a different final version
    number, **use the real one** and rename the migration and the schema JSON to match; do not
    invent a gap.
  - The `muplay.android.room` convention plugin, which wires `room.schemaLocation` and
    `room-testing` — Plan 2 Task 1.
- Produces:
  - `data class Chapter(val index: Int, val startMs: Long, val endMs: Long, val title: String?)`
    with `val durationMs: Long` and `fun contains(positionMs: Long): Boolean`
  - `data class BookSettings(val bookId: String, val speed: Float, val skipSilence: Boolean)` with
    `companion object { const val DEFAULT_SPEED = 1.0f; const val MIN_SPEED = 0.5f;
    const val MAX_SPEED = 3.0f; const val SPEED_STEP = 0.1f;
    fun default(bookId: String): BookSettings; fun clampSpeed(speed: Float): Float }`
  - `BookSettingsEntity(bookId: String, speed: Float, skipSilence: Boolean)`, table `book_settings`
  - `ChapterScanEntity(mediaId: String, chapterCount: Int, scannedAtEpochMs: Long)`, table `chapter_scans`
  - `ChapterEntity(mediaId: String, chapterIndex: Int, startMs: Long, endMs: Long, title: String?)`,
    table `chapters`, primary key `(mediaId, chapterIndex)`, indexed and foreign-keyed on `mediaId`
  - `BookSettingsDao` — `suspend upsert(settings)`, `suspend find(bookId): BookSettingsEntity?`,
    `observe(bookId): Flow<BookSettingsEntity?>`, `observeAll(): Flow<List<BookSettingsEntity>>`
  - `abstract class ChapterDao` — `suspend store(mediaId: String, chapters: List<ChapterEntity>, scannedAtEpochMs: Long)`,
    `suspend find(mediaId): List<ChapterEntity>`, `suspend findScan(mediaId): ChapterScanEntity?`,
    `suspend clear(mediaId: String)`
  - `MediaProgressDao` gains `observeAll(): Flow<List<MediaProgressEntity>>`,
    `suspend findIn(mediaIds: List<String>): List<MediaProgressEntity>`,
    `suspend clear(mediaIds: List<String>)`
  - `MuPlayDatabase` at version **5** with `bookSettingsDao()` and `chapterDao()`
  - `val MIGRATION_4_5: Migration`
  - `DataModule` provides `BookSettingsDao` and `ChapterDao`, and the database builder gains
    `.addMigrations(MIGRATION_4_5)`

### Why a table for settings, and the spec defect that forces it

Spec §3 puts `speed`, `skipSilence` and `gainDb` on `media_progress`, keyed on the **media id**,
and spec §5 repeats it: *"Per-item speed, silence skipping and gain, all stored on the progress
row."*

**For a book that is one M4B file, per-item and per-book are the same grain, and it works. For a
book that is thirty MP3s — the ordinary shape of a ripped audiobook, and the shape `Multi Part
Book` exists to represent — it is the wrong grain.** Per-item speed stores thirty independent
speeds for one book. The listener sets 1.4× in chapter 3; chapter 4 plays at 1.0×; nothing reports
anything, and the failure is invisible until a chapter boundary. That is a real defect in the spec
and Task 10 corrects it there.

The fix is a table at the grain the setting actually has:

```
book_settings(bookId TEXT PRIMARY KEY, speed REAL NOT NULL, skipSilence INTEGER NOT NULL)
```

`media_progress.speed` and `.skipSilence` are **left exactly as Plan 2 declared them and Plan 3
preserves them** — dropping three columns from a schema nothing has shipped buys nothing and would
break Plan 3's `ProgressTableShapeTest`, which is a guard doing its job. But they are **not the
authority for a book**, and "two truths for one value" is not an acceptable resting state, so this
plan does two things about it:

1. `book_settings` is the **only** thing any audiobook code reads or writes for speed and silence
   skipping. Nothing in this plan writes `media_progress.speed`.
2. Task 7 carries an assertion that makes that structural rather than stated: a book whose
   `media_progress.speed` is set to `2.0f` directly in the database still plays at
   `book_settings.speed`. If someone later wires the item column back in, that test goes red.

`media_progress.gainDb` stays unwritten and unapplied — ReplayGain is genuinely per-track (it is a
property of the file, measured by the tagger), so that column is at the right grain; it is simply
nobody's task yet. Task 10 records that in the spec rather than leaving a column that looks
implemented.

### Why `chapters` is cached in Room, and why the negative result needs a row

Reading chapters means an HTTP round trip into the file's `moov` atom (Task 3). Doing that every
time a book screen opens is a network request per open for data that cannot change unless the file
changes. So the parse is cached.

The non-obvious half: **"this file has no chapters" is a result too.** `Multi Part Book`'s three
MP3s carry no chapter atoms at all, and the great majority of real audiobook files are like that.
A cache that can only store chapters has no way to remember a negative answer, so it re-probes
those files forever — which is the worst case, not the rare one. `chapter_scans` is the record
that a probe *happened*; `chapters` holds what it found. `find(mediaId)` returning an empty list is
ambiguous; `findScan(mediaId)` returning non-null is not.

The foreign key from `chapters.mediaId` to `chapter_scans.mediaId` with `ON DELETE CASCADE` makes
"forget this file" one delete. **Room requires an index on the child column of a foreign key** and
emits it as a *warning* that behaves like an error later; declare `@Index("mediaId")` on
`ChapterEntity` explicitly.

### The migration, and the one way to get its SQL right

This is the project's **first** Room migration, and the reason `exportSchema = true` has been on
since Plan 2 Task 1. Room verifies at open time that the database a migration produced is
*identical* to the schema it generated — column order, affinity, `NOT NULL`, defaults, index names,
all of it. A hand-written `CREATE TABLE` that differs in any of those fails with
`Migration didn't properly handle: chapters(...)` and a diff that is painful to read.

**So do not write the SQL from memory. Generate the schema first, then copy it:**

1. Add the entities and bump `version = 5`, with **no** migration.
2. `./gradlew :core:database:kspDebugKotlin` — Room writes
   `core/database/schemas/app.muplay.database.MuPlayDatabase/5.json`.
3. Copy each new table's `createSql` out of that file into `MIGRATION_4_5`, replacing
   `${TABLE_NAME}` with the real table name, and each `createSql` under `indices` likewise.

An `@AutoMigration(from = 4, to = 5)` would also work for three added tables and would be less
code. It is deliberately not used: an auto-migration is not reviewable, and this migration's whole
purpose is to be the one everybody reads when they write the second one.

- [ ] **Step 1: Write the failing DAO tests**

`core/database/src/androidTest/kotlin/app/muplay/database/ChapterDaoTest.kt`:

```kotlin
package app.muplay.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.ChapterDao
import app.muplay.database.entity.ChapterEntity
import app.muplay.database.entity.ChapterScanEntity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real in-memory Room and real SQL — rung 2 of the test hierarchy, not a fake.
 *
 * The subject worth stating: **chapter order is a property**. SQLite makes no promise about the
 * order rows come back in without an `ORDER BY`, and on a small table it very often *happens* to
 * return insertion order, which is exactly how a missing `ORDER BY` ships. Every read assertion
 * here inserts out of order on purpose.
 */
@RunWith(AndroidJUnit4::class)
class ChapterDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: ChapterDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    dao = db.chapterDao()
  }

  @After
  fun tearDown() = db.close()

  private fun chapter(mediaId: String, index: Int, start: Long, end: Long, title: String?) =
    ChapterEntity(mediaId = mediaId, chapterIndex = index, startMs = start, endMs = end, title = title)

  @Test
  fun chaptersComeBackInIndexOrderNoMatterWhatOrderTheyWentIn() = runBlocking {
    dao.store(
      "book-1",
      listOf(
        chapter("book-1", 2, 9_000, 15_000, "A Turn"),
        chapter("book-1", 0, 0, 4_000, "Prologue"),
        chapter("book-1", 3, 15_000, 21_000, "Epilogue"),
        chapter("book-1", 1, 4_000, 9_000, "The Long Middle"),
      ),
      scannedAtEpochMs = 1_700_000_000_000L,
    )

    // `containsExactly`, in order. `containsExactlyInAnyOrder` here would let a DAO with no
    // ORDER BY pass, and a book whose chapters come back shuffled plays its epilogue third.
    assertThat(dao.find("book-1").map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(dao.find("book-1").map { it.startMs })
      .containsExactly(0L, 4_000L, 9_000L, 15_000L)
  }

  @Test
  fun oneFilesChaptersAreNotAnotherFilesChapters() = runBlocking {
    // Two files, two answers. With one file in the table, "chapters for X" and "every chapter
    // there is" are the same query.
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, "Chapter 1")), 1L)
    dao.store("book-2", listOf(chapter("book-2", 0, 0, 7_000, "Head"), chapter("book-2", 1, 7_000, 12_000, "Tail")), 1L)

    assertThat(dao.find("book-1").map { it.title }).containsExactly("Chapter 1")
    assertThat(dao.find("book-2").map { it.title }).containsExactly("Head", "Tail")
  }

  @Test
  fun aFileWithNoChaptersIsARecordedAnswerAndNotAMissingOne() = runBlocking {
    // The whole reason `chapter_scans` exists. `find` returning empty is ambiguous between "no
    // chapters" and "never looked"; `findScan` is not. Without this distinction every chapterless
    // file is re-probed over HTTP on every screen open, which is the common case and not the rare
    // one.
    dao.store("part-one", chapters = emptyList(), scannedAtEpochMs = 42L)

    assertThat(dao.find("part-one")).isEmpty()
    assertThat(dao.findScan("part-one")?.chapterCount).isEqualTo(0)
    assertThat(dao.findScan("part-one")?.scannedAtEpochMs).isEqualTo(42L)
    // ...and a file nobody looked at is distinguishable from that.
    assertThat(dao.findScan("never-probed")).isNull()
  }

  @Test
  fun storingAgainReplacesRatherThanAccumulates() = runBlocking {
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, "old"), chapter("book-1", 1, 5_000, 9_000, "older")), 1L)

    dao.store("book-1", listOf(chapter("book-1", 0, 0, 4_000, "new")), 2L)

    // Not `hasSize(1)` alone: a store that inserted without deleting would leave "older" behind at
    // index 1 and produce a book with a chapter that no longer exists in the file.
    assertThat(dao.find("book-1").map { it.title }).containsExactly("new")
    assertThat(dao.findScan("book-1")?.chapterCount).isEqualTo(1)
    assertThat(dao.findScan("book-1")?.scannedAtEpochMs).isEqualTo(2L)
  }

  @Test
  fun clearingAFileTakesItsChaptersWithIt() = runBlocking {
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, "Chapter 1")), 1L)
    dao.store("book-2", listOf(chapter("book-2", 0, 0, 7_000, "Head")), 1L)

    dao.clear("book-1")

    assertThat(dao.find("book-1")).isEmpty()
    assertThat(dao.findScan("book-1")).isNull()
    // The control: the cascade must not take the neighbour with it.
    assertThat(dao.find("book-2").map { it.title }).containsExactly("Head")
  }

  @Test
  fun aChapterWithNoTitleIsStoredAsNullAndComesBackAsNull() = runBlocking {
    // Spike S3 observed a trailing, empty-titled chapter on one `chap` fixture. A `String?` column
    // that silently became "" would make "untitled" and "titled empty" the same thing.
    dao.store("book-1", listOf(chapter("book-1", 0, 0, 5_000, null), chapter("book-1", 1, 5_000, 9_000, "named")), 1L)

    assertThat(dao.find("book-1").map { it.title }).containsExactly(null, "named")
  }
}
```

`core/database/src/androidTest/kotlin/app/muplay/database/BookSettingsDaoTest.kt`:

```kotlin
package app.muplay.database

import app.cash.turbine.test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.entity.BookSettingsEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSettingsDaoTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var dao: BookSettingsDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    dao = db.bookSettingsDao()
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun twoBooksKeepTwoSpeeds() = runBlocking {
    // The single most important property of this table, and the reason it is keyed on the book.
    // With one book, "the speed for book X" and "the speed" are the same value.
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))
    dao.upsert(BookSettingsEntity("book-2", speed = 0.9f, skipSilence = false))

    assertThat(listOf(dao.find("book-1")!!.speed, dao.find("book-2")!!.speed))
      .containsExactly(1.4f, 0.9f)
    assertThat(listOf(dao.find("book-1")!!.skipSilence, dao.find("book-2")!!.skipSilence))
      .containsExactly(true, false)
  }

  @Test
  fun aBookNobodyHasTouchedHasNoRow() = runBlocking {
    // `null` and "the defaults" are different facts; the repository turns one into the other
    // (Task 4) and the DAO must not pre-empt it.
    assertThat(dao.find("never-opened")).isNull()
  }

  @Test
  fun observingABookEmitsItsCurrentValueAndThenEveryChange() = runTest {
    dao.upsert(BookSettingsEntity("book-1", speed = 1.0f, skipSilence = false))

    dao.observe("book-1").test {
      assertThat(awaitItem()?.speed).isEqualTo(1.0f)
      dao.upsert(BookSettingsEntity("book-1", speed = 1.6f, skipSilence = false))
      // Two distinct values from one Flow. An `observe` that emitted once and stopped would pass
      // an assertion on the first value alone.
      assertThat(awaitItem()?.speed).isEqualTo(1.6f)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun aNeighboursWriteDoesNotMoveThisBooksSettings() = runBlocking {
    // The read-modify-write trap Plan 3 named, one table over. Whatever writes speed must not
    // write anything else, and whatever writes silence skipping must not write speed.
    dao.upsert(BookSettingsEntity("book-1", speed = 1.4f, skipSilence = true))

    dao.upsert(BookSettingsEntity("book-2", speed = 3.0f, skipSilence = false))

    val untouched = dao.find("book-1")!!
    assertThat(untouched.speed).isEqualTo(1.4f)
    assertThat(untouched.skipSilence).isTrue
  }
}
```

`MediaProgressDaoTest` — add, keeping the file's existing setup:

```kotlin
  @Test
  fun observeAllEmitsTheCurrentRowsAndThenEveryChange() = runTest {
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1f, false, 0f))

    dao.observeAll().test {
      assertThat(awaitItem().map { it.mediaId }).containsExactly("a")
      dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))
      // Two emissions, two different contents. The resume snapshot (Task 6) is built on this Flow;
      // an `observeAll` that emitted once would make every resume answer the app's first second of
      // life forever.
      assertThat(awaitItem().map { it.mediaId }).containsExactlyInAnyOrder("a", "b")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun findInReturnsOnlyTheRequestedIdsAndOnlyTheOnesThatExist() = runBlocking {
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1f, false, 0f))
    dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))
    dao.upsert(MediaProgressEntity("c", 3_000L, false, 30L, 1f, false, 0f))

    val found = dao.findIn(listOf("a", "c", "missing"))

    // Exact contents, and a positive control ("b" exists and must not appear). `hasSize(2)` alone
    // would be satisfied by returning "a" and "b".
    assertThat(found.map { it.mediaId }).containsExactlyInAnyOrder("a", "c")
    assertThat(found.map { it.positionMs }).containsExactlyInAnyOrder(1_000L, 3_000L)
  }

  @Test
  fun clearRemovesExactlyTheGivenIds() = runBlocking {
    // "Start this book from the beginning" is expressed by clearing progress, because the
    // ForwardingPlayer seam (Plan 3 Task 8) forbids handing the player a position -- correctly.
    dao.upsert(MediaProgressEntity("a", 1_000L, false, 10L, 1.4f, true, 0f))
    dao.upsert(MediaProgressEntity("b", 2_000L, false, 20L, 1f, false, 0f))

    dao.clear(listOf("a"))

    assertThat(dao.find("a")).isNull()
    assertThat(dao.find("b")?.positionMs).isEqualTo(2_000L)
  }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*ChapterDaoTest*' --tests '*BookSettingsDaoTest*' --tests '*MediaProgressDaoTest*'`
Expected: compilation failure — `Unresolved reference: chapterDao`, `bookSettingsDao`, `findIn`.

- [ ] **Step 3: Write the models**

`core/model/src/main/kotlin/app/muplay/model/Chapter.kt`:

```kotlin
package app.muplay.model

/**
 * One chapter, as read from an audio file's own bytes.
 *
 * There is no server-side source for this. Navidrome exposes no chapter API and OpenSubsonic has
 * no chapter schema, so every value here comes from a Nero `chpl` or QuickTime `chap` atom that
 * Media3's `MetadataRetriever` read out of the stream (spec section 5).
 *
 * [startMs] and [endMs] are **relative to the file**, not to the book. A multi-file book's chapter
 * 7 starts at 0 in its own file; turning that into a position within the whole book is
 * `BookTimeline`'s job and deliberately not this type's.
 *
 * [title] is nullable because a chapter atom genuinely may carry no title — spike S3 observed a
 * trailing, empty-titled chapter on a `chap` fixture. Blank is normalised to `null` at the reader
 * so "untitled" is one value rather than two.
 */
data class Chapter(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  val title: String?,
) {
  val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

  /** Half-open: `[startMs, endMs)`, so a position exactly on a boundary belongs to the later chapter. */
  fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}
```

`core/model/src/main/kotlin/app/muplay/model/BookSettings.kt`:

```kotlin
package app.muplay.model

/**
 * How one **book** plays. Not how one file plays.
 *
 * Spec section 3 puts `speed` and `skipSilence` on the per-item progress row. For a book that is a
 * single M4B file those are the same grain and it works; for a book that is thirty MP3s they are
 * not, and per-item storage means the speed a listener chose in chapter 3 does not survive the
 * transition to chapter 4. This type, and the `book_settings` table behind it, are at the grain
 * the setting actually has. Spec section 3 and section 5 are corrected accordingly (Task 10).
 *
 * `gainDb` is deliberately absent: ReplayGain is a property of the individual file, measured by
 * whatever tagged it, so `media_progress.gainDb` is at the right grain already. It is unwritten
 * and unapplied because applying it means a gain stage in the audio pipeline, which is nobody's
 * task yet.
 */
data class BookSettings(
  val bookId: String,
  val speed: Float,
  val skipSilence: Boolean,
) {
  companion object {
    const val DEFAULT_SPEED = 1.0f

    /**
     * Below 0.5x speech is unintelligible and above 3.0x it is a sound effect. The bounds are also
     * what stops a corrupted row from handing `ExoPlayer` a speed of 0 (silence that looks like a
     * hang) or 100 (a burst and an immediate `STATE_ENDED`).
     */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    /** What one press of the faster/slower control moves. */
    const val SPEED_STEP = 0.1f

    fun default(bookId: String): BookSettings =
      BookSettings(bookId = bookId, speed = DEFAULT_SPEED, skipSilence = false)

    fun clampSpeed(speed: Float): Float = when {
      speed.isNaN() -> DEFAULT_SPEED
      else -> speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }
  }
}
```

> `speed.isNaN()` is not defensive noise: `Float.NaN.coerceIn(0.5f, 3.0f)` returns `NaN`, and
> `ExoPlayer.setPlaybackSpeed(NaN)` throws `IllegalArgumentException` from a listener callback,
> which surfaces as playback dying with no message a user could act on. Task 4 asserts it.

- [ ] **Step 4: Write the entities, the DAOs and the database**

`core/database/src/main/kotlin/app/muplay/database/entity/BookSettingsEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * How one book plays, keyed on the **book id** — the album id of a book in a library the user
 * tagged `AUDIOBOOKS`, or the song id for a loose file that belongs to no album.
 *
 * Deliberately *not* on `media_progress`: that table is keyed on the media id, which is one file,
 * and a thirty-file book would carry thirty independent speeds. See `BookSettings`'s own doc.
 *
 * Nothing about position belongs here. Position is `media_progress`'s, at the file's grain, and a
 * `positionMs` column here would create a second answer to "where was I" — which is the exact
 * inversion spec section 3 exists to prevent, arriving from the other direction.
 */
@Entity(tableName = "book_settings")
data class BookSettingsEntity(
  @PrimaryKey val bookId: String,
  val speed: Float,
  val skipSilence: Boolean,
)
```

`core/database/src/main/kotlin/app/muplay/database/entity/ChapterScanEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The record that a file's chapters were *looked for*, whatever was found.
 *
 * Existence of this row, not the emptiness of `chapters`, is what makes "this file has no
 * chapters" a remembered answer. Most audiobook files in the world carry no chapter atoms at all,
 * so without it the common case is an HTTP round trip into the file's `moov` atom every time a
 * screen opens.
 */
@Entity(tableName = "chapter_scans")
data class ChapterScanEntity(
  @PrimaryKey val mediaId: String,
  val chapterCount: Int,
  val scannedAtEpochMs: Long,
)
```

`core/database/src/main/kotlin/app/muplay/database/entity/ChapterEntity.kt`:

```kotlin
package app.muplay.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One chapter atom of one file, cached so it is parsed once rather than fetched over HTTP on every
 * screen open.
 *
 * The primary key is `(mediaId, chapterIndex)`, so a re-store of the same file overwrites rather
 * than duplicating, and `chapterIndex` is what every read orders by. SQLite promises nothing about
 * row order without an `ORDER BY`, and on a four-row table it very often *happens* to return
 * insertion order — which is exactly how a missing `ORDER BY` ships and how a book ends up playing
 * its epilogue third.
 *
 * The foreign key onto `chapter_scans` makes forgetting a file one delete. Room requires an index
 * on a foreign key's child column and warns rather than failing when it is missing, so it is
 * declared explicitly here.
 */
@Entity(
  tableName = "chapters",
  primaryKeys = ["mediaId", "chapterIndex"],
  foreignKeys = [
    ForeignKey(
      entity = ChapterScanEntity::class,
      parentColumns = ["mediaId"],
      childColumns = ["mediaId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index("mediaId")],
)
data class ChapterEntity(
  val mediaId: String,
  val chapterIndex: Int,
  val startMs: Long,
  val endMs: Long,
  val title: String?,
)
```

`core/database/src/main/kotlin/app/muplay/database/dao/BookSettingsDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.muplay.database.entity.BookSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSettingsDao {

  @Upsert
  suspend fun upsert(settings: BookSettingsEntity)

  @Query("SELECT * FROM book_settings WHERE bookId = :bookId")
  suspend fun find(bookId: String): BookSettingsEntity?

  @Query("SELECT * FROM book_settings WHERE bookId = :bookId")
  fun observe(bookId: String): Flow<BookSettingsEntity?>

  /** Backs the in-memory snapshot the resume policy and the speed controller read (Tasks 6, 7). */
  @Query("SELECT * FROM book_settings")
  fun observeAll(): Flow<List<BookSettingsEntity>>
}
```

`core/database/src/main/kotlin/app/muplay/database/dao/ChapterDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.muplay.database.entity.ChapterEntity
import app.muplay.database.entity.ChapterScanEntity

/**
 * `abstract class`, not `interface`, because [store] is a `@Transaction` with a body. Same shape
 * as Plan 2's `BrowseDao.replaceLibraryContents`.
 */
@Dao
abstract class ChapterDao {

  /**
   * Records one probe of one file: the scan marker first (the chapters' foreign-key parent), the
   * previous chapters gone, then the new ones.
   *
   * Ordering inside the transaction matters. Inserting chapters before the scan row violates the
   * foreign key; deleting after inserting removes what was just written.
   */
  @Transaction
  open suspend fun store(mediaId: String, chapters: List<ChapterEntity>, scannedAtEpochMs: Long) {
    upsertScan(ChapterScanEntity(mediaId = mediaId, chapterCount = chapters.size, scannedAtEpochMs = scannedAtEpochMs))
    deleteChapters(mediaId)
    insertChapters(chapters)
  }

  @Query("SELECT * FROM chapters WHERE mediaId = :mediaId ORDER BY chapterIndex ASC")
  abstract suspend fun find(mediaId: String): List<ChapterEntity>

  @Query("SELECT * FROM chapter_scans WHERE mediaId = :mediaId")
  abstract suspend fun findScan(mediaId: String): ChapterScanEntity?

  /** Cascades to `chapters`. */
  @Query("DELETE FROM chapter_scans WHERE mediaId = :mediaId")
  abstract suspend fun clear(mediaId: String)

  @Upsert
  protected abstract suspend fun upsertScan(scan: ChapterScanEntity)

  @Query("DELETE FROM chapters WHERE mediaId = :mediaId")
  protected abstract suspend fun deleteChapters(mediaId: String)

  @Insert
  protected abstract suspend fun insertChapters(chapters: List<ChapterEntity>)
}
```

`MediaProgressDao` — append the three new members, leaving every existing one untouched:

```kotlin
  /**
   * The whole table, as a Flow. Backs `AudiobookSnapshot` (Task 6), which exists because
   * `ResumePolicy.resolve` runs on the player's application thread and must not touch Room —
   * Plan 3's own `ResumePolicy` documentation says so.
   */
  @Query("SELECT * FROM media_progress")
  fun observeAll(): Flow<List<MediaProgressEntity>>

  /**
   * The rows for a specific set of media ids — one book's files, typically.
   *
   * Not named `findAll(mediaIds)`: an overload of the existing no-argument `findAll()` would
   * compile and would read, at every call site, as though it might be the other one.
   *
   * SQLite binds each element of `:mediaIds` as its own host variable, and there is a per-statement
   * limit (999 on the SQLite versions this app's `minSdk 26` floor can meet). A book with more
   * files than that does not exist, but a caller passing a whole library's song list would fail at
   * runtime with `too many SQL variables` — call this with a book's files, not a library's.
   */
  @Query("SELECT * FROM media_progress WHERE mediaId IN (:mediaIds)")
  suspend fun findIn(mediaIds: List<String>): List<MediaProgressEntity>

  /**
   * "Start this book from the beginning."
   *
   * Expressed as *clearing progress* rather than as overriding a position, because the
   * `ForwardingPlayer` seam (Plan 3 Task 8) makes a caller-chosen position unreachable — correctly.
   * Deleting the row is also the more honest state: there is no position, rather than a position
   * that happens to be zero and a `lastPlayedAt` that says the listener was there.
   */
  @Query("DELETE FROM media_progress WHERE mediaId IN (:mediaIds)")
  suspend fun clear(mediaIds: List<String>)
```

`MuPlayDatabase` — three entities added, version 5, and the doc comment updated because its current
text says the version stays at 1:

```kotlin
@Database(
  entities = [
    MediaProgressEntity::class,
    LibraryEntity::class,
    ArtistEntity::class,
    AlbumEntity::class,
    SongEntity::class,
    SyncWatermarkEntity::class,
    BookSettingsEntity::class,
    ChapterScanEntity::class,
    ChapterEntity::class,
  ],
  version = 5,
  exportSchema = true,
)
abstract class MuPlayDatabase : RoomDatabase() {
  // ... every existing dao accessor unchanged ...
  abstract fun bookSettingsDao(): BookSettingsDao
  abstract fun chapterDao(): ChapterDao

  companion object {
    const val DATABASE_NAME = "muplay.db"
  }
}
```

> The exact `entities` list above is what Plan 2 is expected to have left behind. **Read the real
> file**: if Plan 2's final entity set differs, add the three new ones to whatever is actually
> there and bump from whatever version is actually there. Do not delete an entity to match this
> listing.

- [ ] **Step 5: Generate the schema, then write the migration from it**

```bash
./gradlew :core:database:kspDebugKotlin
cat "core/database/schemas/app.muplay.database.MuPlayDatabase/5.json" | python3 -m json.tool | grep -A2 createSql
```

`core/database/src/main/kotlin/app/muplay/database/Migrations.kt`:

```kotlin
package app.muplay.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The project's first migration, and the reason `exportSchema = true` has been on since Plan 2
 * Task 1.
 *
 * **Every statement below is copied verbatim out of
 * `core/database/schemas/app.muplay.database.MuPlayDatabase/5.json`**, with `${'$'}{TABLE_NAME}`
 * replaced by the literal table name. That is not a stylistic preference: Room verifies at open
 * time that the database a migration produced is byte-for-byte the schema it generated — column
 * order, type affinity, `NOT NULL`, defaults, index names, all of it — and reports any difference
 * as `Migration didn't properly handle` with a diff that is genuinely hard to read. SQL written
 * from memory gets this wrong roughly always.
 *
 * An `@AutoMigration(from = 4, to = 5)` would also work here, and would be less code. It is
 * deliberately not used: an auto-migration is not reviewable, and this migration's job is to be
 * the one everyone reads before writing the second one.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `book_settings` (" +
        "`bookId` TEXT NOT NULL, `speed` REAL NOT NULL, `skipSilence` INTEGER NOT NULL, " +
        "PRIMARY KEY(`bookId`))",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `chapter_scans` (" +
        "`mediaId` TEXT NOT NULL, `chapterCount` INTEGER NOT NULL, " +
        "`scannedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS `chapters` (" +
        "`mediaId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
        "`endMs` INTEGER NOT NULL, `title` TEXT, PRIMARY KEY(`mediaId`, `chapterIndex`), " +
        "FOREIGN KEY(`mediaId`) REFERENCES `chapter_scans`(`mediaId`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_mediaId` ON `chapters` (`mediaId`)")
  }
}
```

> If the generated `5.json` disagrees with any string above — a different index name, a
> `DEFERRABLE` clause, a different column order — **the generated file wins**. Replace the literal
> and record the difference in the task report; a mismatch is Room telling you something about its
> own code generation, not about this plan.

`DataModule` — the builder gains the migration:

```kotlin
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): MuPlayDatabase =
    Room.databaseBuilder(context, MuPlayDatabase::class.java, MuPlayDatabase.DATABASE_NAME)
      // Without this, a device carrying the version-4 database from an earlier build crashes on
      // first open with IllegalStateException, and `fallbackToDestructiveMigration()` would
      // silently delete every listener's book position -- which is the one thing this application
      // exists to keep.
      .addMigrations(MIGRATION_4_5)
      .build()

  @Provides
  fun provideBookSettingsDao(db: MuPlayDatabase): BookSettingsDao = db.bookSettingsDao()

  @Provides
  fun provideChapterDao(db: MuPlayDatabase): ChapterDao = db.chapterDao()
```

- [ ] **Step 6: Write the migration test**

`core/database/src/androidTest/kotlin/app/muplay/database/MigrationTest.kt`:

```kotlin
package app.muplay.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration, run against a **real version-4 database file** built from the exported schema.
 *
 * What makes this worth writing rather than trusting: `fallbackToDestructiveMigration()` is one
 * line away in `DataModule`, it makes every migration failure disappear, and what it destroys is
 * every listener's book position — the one thing this application exists to keep. A migration
 * test is the only thing standing between "the schema changed" and "the shelf is empty".
 *
 * The rows written below carry **distinct** values on purpose. A migration that dropped and
 * recreated `media_progress` would still leave a row-count assertion green.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

  private companion object {
    const val TEST_DB = "migration-test.db"
  }

  @get:Rule
  val helper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    MuPlayDatabase::class.java,
  )

  @Test
  fun everyProgressRowSurvivesTheMoveToFive() {
    helper.createDatabase(TEST_DB, 4).use { db ->
      db.execSQL(
        "INSERT INTO media_progress " +
          "(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb) " +
          "VALUES ('chapter-14', 3600000, 0, 1700000000000, 1.4, 1, 6.0)",
      )
      db.execSQL(
        "INSERT INTO media_progress " +
          "(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence, gainDb) " +
          "VALUES ('a-song', 12345, 1, 1600000000000, 1.0, 0, 0.0)",
      )
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

    db.query("SELECT mediaId, positionMs, isFinished, speed FROM media_progress ORDER BY mediaId").use { c ->
      // Exact values, in order, for two rows that differ in every column. Row counts survive a
      // migration that wrote defaults over everything; these do not.
      assertThat(c.count).isEqualTo(2)
      c.moveToFirst()
      assertThat(c.getString(0)).isEqualTo("a-song")
      assertThat(c.getLong(1)).isEqualTo(12_345L)
      assertThat(c.getInt(2)).isEqualTo(1)
      assertThat(c.getFloat(3)).isEqualTo(1.0f)
      c.moveToNext()
      assertThat(c.getString(0)).isEqualTo("chapter-14")
      assertThat(c.getLong(1)).isEqualTo(3_600_000L)
      assertThat(c.getInt(2)).isEqualTo(0)
      assertThat(c.getFloat(3)).isEqualTo(1.4f)
    }
  }

  @Test
  fun theNewTablesExistAndAcceptRowsAfterTheMigration() {
    helper.createDatabase(TEST_DB, 4).close()

    val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

    // `runMigrationsAndValidate` already compares the resulting schema against the exported one,
    // which is the strongest assertion in this class. These add the behaviour that validation
    // cannot see: the cascade actually cascades.
    db.execSQL("INSERT INTO book_settings (bookId, speed, skipSilence) VALUES ('book-1', 1.4, 1)")
    db.execSQL("INSERT INTO chapter_scans (mediaId, chapterCount, scannedAtEpochMs) VALUES ('m-1', 2, 5)")
    db.execSQL("INSERT INTO chapters (mediaId, chapterIndex, startMs, endMs, title) VALUES ('m-1', 0, 0, 7000, 'Head')")
    db.execSQL("INSERT INTO chapters (mediaId, chapterIndex, startMs, endMs, title) VALUES ('m-1', 1, 7000, 12000, 'Tail')")
    db.execSQL("PRAGMA foreign_keys = ON")
    db.execSQL("DELETE FROM chapter_scans WHERE mediaId = 'm-1'")

    db.query("SELECT COUNT(*) FROM chapters").use { c ->
      c.moveToFirst()
      assertThat(c.getInt(0)).describedAs("chapters left behind by a cascade that did not fire").isZero
    }
    db.query("SELECT speed FROM book_settings WHERE bookId = 'book-1'").use { c ->
      c.moveToFirst()
      assertThat(c.getFloat(0)).isEqualTo(1.4f)
    }
  }
}
```

> `MigrationTestHelper`'s constructor changed across Room versions. In 2.8.4 the two-argument
> `(Instrumentation, Class<out RoomDatabase>)` form exists; if the resolved signature wants an
> `openFactory` or a `specs` list too, supply what it asks for and change nothing else. The
> `room-testing` dependency comes from the `muplay.android.room` convention plugin (Plan 2 Task 1);
> if it is `testImplementation`-scoped there rather than `androidTestImplementation`, fix it in the
> convention plugin, not in this module's build file — `ConventionTest` forbids the latter.

- [ ] **Step 7: Run everything in `:core:database`**

```bash
./gradlew :core:database:connectedDebugAndroidTest
```

Expected: PASS — `ChapterDaoTest` 6/6, `BookSettingsDaoTest` 4/4, `MigrationTest` 2/2,
`MediaProgressDaoTest` (Plan 2's tests plus the three added above), and every Plan 2 suite still
green.

- [ ] **Step 8: Prove the new storage can fail**

One mutation at a time, reverted after each:

1. Remove `ORDER BY chapterIndex ASC` from `ChapterDao.find`. Expect
   `chaptersComeBackInIndexOrderNoMatterWhatOrderTheyWentIn` to fail. **If it passes**, SQLite
   happened to return insertion order — insert five chapters instead of four and re-check, and
   record what it took, because that is the shape of the bug this assertion exists for.
2. Remove `deleteChapters(mediaId)` from `ChapterDao.store`. Expect
   `storingAgainReplacesRatherThanAccumulates` to fail.
3. Make `ChapterDao.store` skip `upsertScan` when `chapters.isEmpty()`. Expect
   `aFileWithNoChaptersIsARecordedAnswerAndNotAMissingOne` to fail. This is the negative-cache
   defect, reproduced on demand.
4. Drop `ON DELETE CASCADE` from `ChapterEntity`'s foreign key, regenerate the schema, and update
   `MIGRATION_4_5` to match. Expect `theNewTablesExistAndAcceptRowsAfterTheMigration` to fail on
   the leftover chapters.
5. Change `MIGRATION_4_5`'s `book_settings.speed` column to `INTEGER NOT NULL`. Expect
   `runMigrationsAndValidate` to fail with a schema mismatch naming the column. **Record the
   message** — it is what the second migration's author will be reading.
6. Replace `MIGRATION_4_5` in `DataModule` with `fallbackToDestructiveMigration()`. Expect
   `everyProgressRowSurvivesTheMoveToFive` to keep passing (it names the migration explicitly) and
   record that as a **known ungated line**: the destructive fallback is a `DataModule` decision no
   test in this task can see. Task 10's journey, which runs against a real installed app, is where
   it would surface. Honesty about an ungated line beats a probe that cannot fire.

- [ ] **Step 9: Re-measure and commit**

Read the measured BRANCH ratios for `ChapterDao`, `BookSettingsDao` and `MediaProgressDao_Impl`'s
non-generated callers out of `:core:database`'s report, and extend `coverageFloors`. Room's
generated `*_Impl` classes are already excluded by `Jacoco.kt` (Plan 2 Task 1); `ChapterDao`'s
`store` body is **not** generated and must be gated.

```bash
git add core/model core/database build.gradle.kts
git commit -m "feat(database): book_settings, chapters, and the first Room migration"
```

---
