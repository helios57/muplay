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
   *(Task 9's `bookPlayerUiState` treats `PlaybackState.mediaId` as **nullable**, with `null`
   meaning "nothing playing" — which is what `PlaybackState.NOTHING_PLAYING` implies. **Read Plan
   3's declaration.** If `mediaId` landed as a non-null `String` with `""` for nothing, add
   `.takeIf { it.isNotEmpty() }` at that one call site rather than changing Plan 3's type.)
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
| `ci/probe-chapters.sh` | **new** — derives the chapter oracle from the fixtures with `ffprobe`; `--check` fails on drift |
| `core/testing/src/main/resources/fixtures/books.tsv` | **new (generated, committed)** — the `ffprobe` oracle: durations, chapters, order |
| `core/testing/src/main/kotlin/app/muplay/testing/BookFixtures.kt` | **new** — the oracle, parsed; `ExpectedBook`/`ExpectedChapter`/`ExpectedTrack` |
| `core/testing/src/main/kotlin/app/muplay/testing/AudiobookFixtures.kt` | **new** — `seed(db)` (the hand-built three-book corpus) and `seedFromServer(db, albums, songs)` |
| `core/media/src/androidTest/kotlin/app/muplay/media/BookPlaybackHarness.kt` | **new** — the shared device fixture three instrumented suites use |
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
    // have satisfied. Nine: three music tracks, three single-file books, three book parts.
    assertThat(named).hasSize(9)
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

## Task 3: `ChapterReader` and `BookTimeline` — chapters out of the file's own bytes

**Files:**
- Modify: `gradle/libs.versions.toml`, `core/media/build.gradle.kts`
- Create: `core/media/src/main/kotlin/app/muplay/media/ChapterAssembly.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ChapterReader.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ChapterRepository.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/BookTimeline.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/ChapterAssemblyTest.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/BookTimelineTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ChapterReaderTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ChapterRepositoryTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - `app.muplay.model.Chapter(index, startMs, endMs, title)` — Task 2.
  - `ChapterDao.store/find/findScan/clear` — Task 2.
  - `MuPlayDataSourceFactory(callFactory, cache).create(): DataSource.Factory` — Plan 3 Task 3.
  - `SubsonicSourceProvider.current()` (Plan 2 Task 4), `SubsonicSource.streamUrl(songId, format)`
    (Plan 3 Task 1), `StreamFormat.forSuffix(suffix, bitrate)` (Plan 3 Task 1).
  - `app.muplay.model.Song` — Plan 2 Task 3.
  - `java.time.Clock` from `MediaModule` — Plan 3 Task 8.
  - `app.muplay.testing.BookFixtures` — Task 1.
- Produces:
  - `internal data class RawChapter(val startMs: Long, val endMs: Long?, val title: String?)`
  - `object ChapterAssembly` with
    `fun assemble(raw: List<RawChapter>, contentDurationMs: Long): List<Chapter>` and
    `const val UNTITLED_FORMAT = "Chapter %d"`
  - `class ChapterReader @Inject constructor(context, dataSourceFactory)` with
    `suspend fun read(uri: String, contentDurationMs: Long): List<Chapter>` and
    `companion object { const val TIMEOUT_MS = 30_000L }`
  - `class ChapterRepository @Inject constructor(chapterDao, chapterReader, sourceProvider, clock)`
    with `suspend fun chaptersFor(song: Song): List<Chapter>`,
    `suspend fun chaptersFor(songs: List<Song>): Map<String, List<Chapter>>`,
    `suspend fun forget(mediaId: String)`
  - `data class BookFile(val mediaId: String, val title: String, val durationMs: Long)`
  - `data class BookChapter(val index: Int, val title: String, val mediaId: String, val itemIndex: Int,
    val startInItemMs: Long, val endInItemMs: Long, val bookStartMs: Long)` with `val durationMs: Long`
  - `object BookTimeline` with
    `fun of(files: List<BookFile>, chaptersByMediaId: Map<String, List<Chapter>>): List<BookChapter>`,
    `fun chapterAt(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): BookChapter?`,
    `fun next(timeline: List<BookChapter>, current: BookChapter?): BookChapter?`,
    `fun previous(timeline: List<BookChapter>, current: BookChapter?, positionInItemMs: Long, restartThresholdMs: Long = RESTART_THRESHOLD_MS): BookChapter?`,
    `fun bookPositionMs(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): Long`,
    `const val RESTART_THRESHOLD_MS = 3_000L`

### The differentiator, and the one trap that makes it silently useless

Navidrome never exposes chapters and OpenSubsonic has no chapter schema. Media3 1.11.0 reads them
out of the file, so this client can show what the server cannot see. Spike S3 measured the whole
mechanism; **its single most load-bearing finding is a wiring detail with no failure signal**:

> The bare `MetadataRetriever.Builder(context, item).build()` form **drops `chap`-track chapters
> entirely** and returns `endTimeMs = -9223372036854775807` (`C.TIME_UNSET`) for every `chpl`
> chapter. No exception. No log line. `chpl` files come back looking plausible.

A test that asserts "three chapters came back with the right titles" passes on the broken wiring.
So this task's device test does something stronger than assert the right answer: **it builds the
broken retriever too, and asserts it produces the broken answer.** That turns the footgun into a
permanent executable record — if someone deletes `setMediaSourceFactory`, one test goes red because
the good path broke and another goes red because the bad path stopped being distinguishable.

### Why `contentDurationMs` is a parameter

Spike S3 could not confirm how Media3 decides the last chapter's end time; it inferred *"next
chapter's start, or content duration"* from the data and said so. This code does not depend on that
inference. Whatever comes back is used where it is populated, and where it is not, the end is
filled from the next chapter's start or — for the last chapter — from the duration the caller
already knows, because `Song.durationSeconds` is right there. No sentinel value ever reaches a
`Chapter`, so nothing downstream has to know that `C.TIME_UNSET` exists.

### Why the assembly is a separate, Android-free object

`ChapterAssembly.assemble` takes `RawChapter`s and returns `Chapter`s and touches no Media3 type.
That is deliberate: sorting, de-duplicating, end-time filling and untitled-chapter naming are the
parts most likely to be wrong, and they are gated in **Tier 1** where a mutation costs seconds
rather than an emulator boot. Same split as `StreamRetryPolicy` and `PlaybackAudioAttributes` in
Plan 3.

- [ ] **Step 1: Add the artifact**

`gradle/libs.versions.toml` — under `[libraries]`, at the existing `media3` version ref:

```toml
# Chapters. NOT pulled in by media3-exoplayer: spike S3 proved it with a javac probe --
# `androidx.media3.exoplayer.MetadataRetriever` does not exist in 1.11.0 at all (deprecated in
# 1.9, then removed), and `androidx.media3.inspector.MetadataRetriever` ships only here.
media3-inspector = { group = "androidx.media3", name = "media3-inspector", version.ref = "media3" }
```

`core/media/build.gradle.kts` — `implementation(libs.media3.inspector)`, and in the
`androidTestImplementation` block `androidTestImplementation(project(":core:testing"))` so the
device tests can read `BookFixtures`.

- [ ] **Step 2: Write the failing assembly tests**

`core/media/src/test/kotlin/app/muplay/media/ChapterAssemblyTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Android-free half of chapter reading, gated in Tier 1.
 *
 * Every assertion is over an **exact list in exact order**. Chapter order is the property this
 * whole feature rests on: a book whose chapters come back sorted by title, or de-duplicated into a
 * different order, is a book that plays its epilogue third, and `containsExactlyInAnyOrder` would
 * say nothing about it.
 */
class ChapterAssemblyTest {

  @Test
  fun `chapters are ordered by start time, whatever order they arrived in`() {
    val raw = listOf(
      RawChapter(9_000, 15_000, "A Turn"),
      RawChapter(0, 4_000, "Prologue"),
      RawChapter(15_000, 21_000, "Epilogue"),
      RawChapter(4_000, 9_000, "The Long Middle"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 21_000)

    assertThat(chapters.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(chapters.map { it.startMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
  }

  @Test
  fun `the index is the position in the ordered list, not the order of arrival`() {
    val raw = listOf(RawChapter(5_000, 9_000, "second"), RawChapter(0, 5_000, "first"))

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    // Two observations of `index`. An implementation that copied an arrival index would give
    // "first" index 1 here, and a constant 0 would give both the same.
    assertThat(chapters.map { it.index }).containsExactly(0, 1)
    assertThat(chapters.map { it.title }).containsExactly("first", "second")
  }

  @Test
  fun `a missing end time is filled from the next chapter's start`() {
    // The `C.TIME_UNSET` case spike S3 found, arriving here as a null. Three chapters, two of them
    // missing an end, and the two fills are DIFFERENT numbers -- a constant satisfies neither.
    val raw = listOf(
      RawChapter(0, null, "one"),
      RawChapter(4_000, null, "two"),
      RawChapter(9_000, 21_000, "three"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 21_000)

    assertThat(chapters.map { it.endMs }).containsExactly(4_000L, 9_000L, 21_000L)
  }

  @Test
  fun `the last chapter's missing end time comes from the content duration`() {
    // Two observations with two different durations, because "the content duration" and "21000"
    // are the same program if only one duration is ever passed.
    val raw = listOf(RawChapter(0, null, "only"))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 21_000).single().endMs)
      .isEqualTo(21_000L)
    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 12_000).single().endMs)
      .isEqualTo(12_000L)
  }

  @Test
  fun `a populated end time is never overwritten by the duration`() {
    // The other direction, and the one that would hide a reader that ignored what Media3 returned.
    val raw = listOf(RawChapter(0, 4_000, "one"), RawChapter(4_000, 9_000, "two"))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 21_000).map { it.endMs })
      .containsExactly(4_000L, 9_000L)
  }

  @Test
  fun `duplicate entries for the same start time collapse to one, keeping the titled one`() {
    // Media3 surfaces metadata per track format, and a file with more than one track can present
    // the same chapter list twice. Left alone that doubles every book's chapter list.
    val raw = listOf(
      RawChapter(0, 4_000, null),
      RawChapter(0, 4_000, "Prologue"),
      RawChapter(4_000, 9_000, "The Long Middle"),
      RawChapter(4_000, 9_000, "The Long Middle"),
    )

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    assertThat(chapters.map { it.title }).containsExactly("Prologue", "The Long Middle")
  }

  @Test
  fun `an untitled chapter gets a numbered name, and a titled one keeps its own`() {
    // Two observations in one list. A formatter applied to everything would rename "Prologue".
    val raw = listOf(RawChapter(0, 4_000, "Prologue"), RawChapter(4_000, 9_000, null))

    val chapters = ChapterAssembly.assemble(raw, contentDurationMs = 9_000)

    assertThat(chapters.map { it.title }).containsExactly("Prologue", null)
    // The *display* name is the caller's business -- `BookTimeline` numbers it -- and this is the
    // assertion that keeps "untitled" a fact rather than a string that looks like a title.
  }

  @Test
  fun `a blank title is the same fact as no title`() {
    val raw = listOf(RawChapter(0, 4_000, "   "), RawChapter(4_000, 9_000, ""))

    assertThat(ChapterAssembly.assemble(raw, contentDurationMs = 9_000).map { it.title })
      .containsExactly(null, null)
  }

  @Test
  fun `an end time before its own start is clamped rather than producing a negative duration`() {
    // A malformed atom is a real thing in the wild. A negative `durationMs` reaches a progress bar
    // and reaches `seekTo`, and neither of those has a sensible behaviour for it.
    val raw = listOf(RawChapter(9_000, 4_000, "backwards"))

    val chapter = ChapterAssembly.assemble(raw, contentDurationMs = 21_000).single()

    assertThat(chapter.endMs).isEqualTo(9_000L)
    assertThat(chapter.durationMs).isZero
  }

  @Test
  fun `an empty input produces an empty list rather than an invented chapter`() {
    // "No chapters" is a real answer for most audiobook files there are. Fabricating a single
    // whole-file chapter here would make `Multi Part Book` indistinguishable from a chaptered one
    // and would put a wrong chapter title on every music track that ever passed through.
    assertThat(ChapterAssembly.assemble(emptyList(), contentDurationMs = 21_000)).isEmpty()
  }
}
```

- [ ] **Step 3: Write the failing timeline tests**

`core/media/src/test/kotlin/app/muplay/media/BookTimelineTest.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Chapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two shapes an audiobook comes in, unified.
 *
 * A single-file M4B has many chapters inside one media item; a ripped book has one media item per
 * chapter and no chapter atoms at all. Chapter navigation has to work identically over both, and
 * the only way to be sure is to test both **and** a book that is neither — a multi-file book whose
 * files *also* carry chapters, where "one chapter per file" and "chapter start equals book
 * position" are both wrong.
 */
class BookTimelineTest {

  private val singleFile = listOf(BookFile("m4b", "Second Book", 21_000))
  private val secondBookChapters = mapOf(
    "m4b" to listOf(
      Chapter(0, 0, 4_000, "Prologue"),
      Chapter(1, 4_000, 9_000, "The Long Middle"),
      Chapter(2, 9_000, 15_000, "A Turn"),
      Chapter(3, 15_000, 21_000, "Epilogue"),
    ),
  )

  private val multiFile = listOf(
    BookFile("p1", "Part One", 4_000),
    BookFile("p2", "Part Two", 6_000),
    BookFile("p3", "Part Three", 5_000),
  )

  @Test
  fun `a single-file book's chapters all live in item zero`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)

    assertThat(timeline.map { it.title })
      .containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 0, 0, 0)
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
    // For a one-file book the book position and the in-item position coincide, which is exactly
    // why this book alone cannot prove `bookStartMs` is computed at all -- see the mixed case.
    assertThat(timeline.map { it.bookStartMs }).containsExactly(0L, 4_000L, 9_000L, 15_000L)
  }

  @Test
  fun `a chapterless multi-file book gets one chapter per file, named after the file`() {
    val timeline = BookTimeline.of(multiFile, chaptersByMediaId = emptyMap())

    assertThat(timeline.map { it.title }).containsExactly("Part One", "Part Two", "Part Three")
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 1, 2)
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 0L, 0L)
    assertThat(timeline.map { it.endInItemMs }).containsExactly(4_000L, 6_000L, 5_000L)
    // The cumulative offsets. 0 / 4000 / 10000 -- unequal, so a constant or an `index * length`
    // is wrong at the second entry and at the third.
    assertThat(timeline.map { it.bookStartMs }).containsExactly(0L, 4_000L, 10_000L)
  }

  @Test
  fun `a multi-file book whose files also carry chapters is neither of the easy cases`() {
    // The discriminating shape. Two files, 2 + 3 chapters, so:
    //   * "one chapter per file" is wrong (five entries, not two);
    //   * "bookStartMs == startInItemMs" is wrong from the third entry on;
    //   * "index == itemIndex" is wrong from the second entry on.
    val files = listOf(BookFile("a", "Disc One", 9_000), BookFile("b", "Disc Two", 12_000))
    val chapters = mapOf(
      "a" to listOf(Chapter(0, 0, 4_000, "One"), Chapter(1, 4_000, 9_000, "Two")),
      "b" to listOf(
        Chapter(0, 0, 3_000, "Three"),
        Chapter(1, 3_000, 7_000, "Four"),
        Chapter(2, 7_000, 12_000, "Five"),
      ),
    )

    val timeline = BookTimeline.of(files, chapters)

    assertThat(timeline.map { it.title }).containsExactly("One", "Two", "Three", "Four", "Five")
    assertThat(timeline.map { it.index }).containsExactly(0, 1, 2, 3, 4)
    assertThat(timeline.map { it.itemIndex }).containsExactly(0, 0, 1, 1, 1)
    assertThat(timeline.map { it.startInItemMs }).containsExactly(0L, 4_000L, 0L, 3_000L, 7_000L)
    assertThat(timeline.map { it.bookStartMs })
      .containsExactly(0L, 4_000L, 9_000L, 12_000L, 16_000L)
  }

  @Test
  fun `an untitled chapter is numbered by its position in the book`() {
    val files = listOf(BookFile("a", "Disc One", 9_000))
    val chapters = mapOf("a" to listOf(Chapter(0, 0, 4_000, null), Chapter(1, 4_000, 9_000, "Named")))

    val timeline = BookTimeline.of(files, chapters)

    // Two observations: the untitled one is numbered, the titled one is not renamed.
    assertThat(timeline.map { it.title }).containsExactly("Chapter 1", "Named")
  }

  @Test
  fun `a position exactly on a boundary belongs to the chapter that starts there`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)

    // The half-open rule, asserted on both sides of one boundary. One millisecond apart, two
    // different answers -- which is what makes this an assertion about the rule rather than about
    // one number.
    assertThat(BookTimeline.chapterAt(timeline, "m4b", 3_999)?.title).isEqualTo("Prologue")
    assertThat(BookTimeline.chapterAt(timeline, "m4b", 4_000)?.title).isEqualTo("The Long Middle")
  }

  @Test
  fun `chapterAt is scoped to the item it was asked about`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    // Same in-item position, three different answers. A `chapterAt` that ignored `mediaId` and
    // searched by book position would give "Part One" for all three.
    assertThat(BookTimeline.chapterAt(timeline, "p1", 1_000)?.title).isEqualTo("Part One")
    assertThat(BookTimeline.chapterAt(timeline, "p2", 1_000)?.title).isEqualTo("Part Two")
    assertThat(BookTimeline.chapterAt(timeline, "p3", 1_000)?.title).isEqualTo("Part Three")
    assertThat(BookTimeline.chapterAt(timeline, "not-in-this-book", 1_000)).isNull()
  }

  @Test
  fun `next walks forward across a file boundary and stops at the end`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    assertThat(BookTimeline.next(timeline, timeline[0])?.title).isEqualTo("Part Two")
    assertThat(BookTimeline.next(timeline, timeline[1])?.title).isEqualTo("Part Three")
    assertThat(BookTimeline.next(timeline, timeline[2])).isNull()
    assertThat(BookTimeline.next(timeline, null)?.title).isEqualTo("Part One")
  }

  @Test
  fun `previous restarts the current chapter unless you are already near its start`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val third = timeline[2] // "A Turn", 9000..15000

    // Well inside the chapter: "previous" means "start this one again", which is what every
    // audiobook player does and what a listener who overshot expects.
    assertThat(BookTimeline.previous(timeline, third, positionInItemMs = 12_000)?.title)
      .isEqualTo("A Turn")
    // Just after its start: "previous" means the chapter before.
    assertThat(BookTimeline.previous(timeline, third, positionInItemMs = 9_500)?.title)
      .isEqualTo("The Long Middle")
    // ...and at the very first chapter there is nothing before it, so it restarts.
    assertThat(BookTimeline.previous(timeline, timeline[0], positionInItemMs = 100)?.title)
      .isEqualTo("Prologue")
  }

  @Test
  fun `the restart threshold is a parameter and it actually moves the answer`() {
    val timeline = BookTimeline.of(singleFile, secondBookChapters)
    val third = timeline[2]

    // Same position, two thresholds, two answers. Without this, `RESTART_THRESHOLD_MS` is a
    // constant that could be any value at all.
    assertThat(
      BookTimeline.previous(timeline, third, positionInItemMs = 11_000, restartThresholdMs = 1_000)?.title,
    ).isEqualTo("A Turn")
    assertThat(
      BookTimeline.previous(timeline, third, positionInItemMs = 11_000, restartThresholdMs = 5_000)?.title,
    ).isEqualTo("The Long Middle")
  }

  @Test
  fun `the book position adds the offset of the item you are in`() {
    val timeline = BookTimeline.of(multiFile, emptyMap())

    // Three items, three offsets. The whole-book progress bar is this number, and with one item
    // it is indistinguishable from the in-item position.
    assertThat(BookTimeline.bookPositionMs(timeline, "p1", 1_000)).isEqualTo(1_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "p2", 1_000)).isEqualTo(5_000L)
    assertThat(BookTimeline.bookPositionMs(timeline, "p3", 1_000)).isEqualTo(11_000L)
  }

  @Test
  fun `a book with no files has an empty timeline and no navigation`() {
    // The vacuous-collection case, asserted rather than assumed. Every function above iterates a
    // computed list; on an empty one they run zero times and must still answer sensibly.
    val timeline = BookTimeline.of(emptyList(), emptyMap())

    assertThat(timeline).isEmpty()
    assertThat(BookTimeline.chapterAt(timeline, "anything", 0)).isNull()
    assertThat(BookTimeline.next(timeline, null)).isNull()
    assertThat(BookTimeline.previous(timeline, null, 0)).isNull()
    assertThat(BookTimeline.bookPositionMs(timeline, "anything", 5_000)).isEqualTo(5_000L)
  }
}
```

- [ ] **Step 4: Run both, watch them fail, then implement**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*ChapterAssemblyTest*' --tests '*BookTimelineTest*'`
Expected: FAIL, `Unresolved reference: ChapterAssembly` / `BookTimeline`.

`core/media/src/main/kotlin/app/muplay/media/ChapterAssembly.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Chapter

/**
 * One chapter entry exactly as it came out of Media3, before anything has been decided about it.
 *
 * [endMs] is nullable because `androidx.media3.extractor.metadata.Chapter.getEndTimeMs()` returns
 * `C.TIME_UNSET` when the retriever was wired without an explicit `MediaSourceFactory` — spike S3's
 * central finding. Mapping that sentinel to `null` at the boundary is what keeps every type below
 * this one free of it.
 */
internal data class RawChapter(val startMs: Long, val endMs: Long?, val title: String?)

/**
 * Turns whatever Media3 handed back into an ordered, gap-free, de-duplicated chapter list.
 *
 * Android-free on purpose: this is the part most likely to be subtly wrong, so it is gated in
 * Tier 1 where a mutation costs seconds rather than an emulator boot.
 */
object ChapterAssembly {

  fun assemble(raw: List<RawChapter>, contentDurationMs: Long): List<Chapter> {
    // De-duplicate by start time, preferring the entry that actually carries a title: Media3
    // surfaces metadata per track format, so a file with more than one track can present the same
    // chapter list twice, once titled and once not. Left alone that doubles every book.
    val byStart = LinkedHashMap<Long, RawChapter>()
    for (entry in raw) {
      if (entry.startMs < 0L) continue
      val existing = byStart[entry.startMs]
      val keep = when {
        existing == null -> entry
        existing.normalisedTitle == null && entry.normalisedTitle != null -> entry
        existing.endMs == null && entry.endMs != null -> existing.copy(endMs = entry.endMs)
        else -> existing
      }
      byStart[entry.startMs] = keep
    }

    val ordered = byStart.values.sortedBy { it.startMs }

    return ordered.mapIndexed { index, entry ->
      // The end is what Media3 said, or the next chapter's start, or -- for the last chapter --
      // the duration the caller already knows from `Song.durationSeconds`. Spike S3 *inferred*
      // that Media3 fills the last end from the content duration but could not confirm it; this
      // code does not rely on the inference either way.
      val fallback = ordered.getOrNull(index + 1)?.startMs ?: contentDurationMs
      val end = (entry.endMs ?: fallback).coerceAtLeast(entry.startMs)
      Chapter(
        index = index,
        startMs = entry.startMs,
        endMs = end,
        title = entry.normalisedTitle,
      )
    }
  }

  /** Blank and absent are one fact, not two. */
  private val RawChapter.normalisedTitle: String?
    get() = title?.trim()?.takeIf { it.isNotEmpty() }
}
```

`core/media/src/main/kotlin/app/muplay/media/BookTimeline.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.Chapter

/**
 * One playable file of a book, reduced to the three things a timeline needs.
 *
 * Not `Song`: `BookTimeline` is a pure function gated in Tier 1, and a twelve-field model would
 * make every test in `BookTimelineTest` twelve lines longer without adding a single assertion. The
 * repository maps `Song` to this at the boundary.
 */
data class BookFile(val mediaId: String, val title: String, val durationMs: Long)

/**
 * One chapter of a book, located in the **queue** as well as in the book.
 *
 * [itemIndex] and [startInItemMs] are what a seek needs (`seekTo(itemIndex, startInItemMs)`);
 * [bookStartMs] is what a whole-book progress bar needs. They coincide for a single-file book,
 * which is exactly why a single-file book cannot prove either of them is computed.
 */
data class BookChapter(
  val index: Int,
  val title: String,
  val mediaId: String,
  val itemIndex: Int,
  val startInItemMs: Long,
  val endInItemMs: Long,
  val bookStartMs: Long,
) {
  val durationMs: Long get() = (endInItemMs - startInItemMs).coerceAtLeast(0L)
}

/**
 * The two shapes an audiobook comes in, unified into one navigable list.
 *
 * A single-file M4B carries its chapters as atoms inside one media item. A ripped book is one media
 * item per chapter with no atoms at all. A book can also be both — a two-disc rip where each disc
 * is a chaptered M4B — and that third shape is what stops "one chapter per file" and "book position
 * equals in-item position" from both looking correct.
 *
 * A file with no chapters contributes exactly one chapter named after the file. Fabricating
 * chapters for a file that has none would put a chapter title on every music track that ever
 * passed through; contributing none would make a ripped book unnavigable.
 */
object BookTimeline {

  /** How far into a chapter "previous" stops meaning "restart this one". */
  const val RESTART_THRESHOLD_MS = 3_000L

  fun of(files: List<BookFile>, chaptersByMediaId: Map<String, List<Chapter>>): List<BookChapter> {
    val result = mutableListOf<BookChapter>()
    var bookOffset = 0L
    files.forEachIndexed { itemIndex, file ->
      val chapters = chaptersByMediaId[file.mediaId].orEmpty()
      if (chapters.isEmpty()) {
        result += BookChapter(
          index = result.size,
          title = file.title,
          mediaId = file.mediaId,
          itemIndex = itemIndex,
          startInItemMs = 0L,
          endInItemMs = file.durationMs,
          bookStartMs = bookOffset,
        )
      } else {
        for (chapter in chapters.sortedBy { it.startMs }) {
          result += BookChapter(
            index = result.size,
            // Numbered by position in the **book**, not in the file: chapter 1 of disc two is not
            // "Chapter 1".
            title = chapter.title ?: "Chapter ${result.size + 1}",
            mediaId = file.mediaId,
            itemIndex = itemIndex,
            startInItemMs = chapter.startMs,
            endInItemMs = chapter.endMs,
            bookStartMs = bookOffset + chapter.startMs,
          )
        }
      }
      bookOffset += file.durationMs
    }
    return result
  }

  fun chapterAt(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): BookChapter? {
    val inItem = timeline.filter { it.mediaId == mediaId }
    if (inItem.isEmpty()) return null
    // Half-open `[start, end)`: a position exactly on a boundary belongs to the chapter that
    // starts there. `lastOrNull` rather than `firstOrNull` so a position past the final chapter's
    // end (encoder padding routinely puts it there) still answers the final chapter rather than
    // null.
    return inItem.lastOrNull { positionInItemMs >= it.startInItemMs } ?: inItem.first()
  }

  fun next(timeline: List<BookChapter>, current: BookChapter?): BookChapter? =
    when (current) {
      null -> timeline.firstOrNull()
      else -> timeline.getOrNull(current.index + 1)
    }

  fun previous(
    timeline: List<BookChapter>,
    current: BookChapter?,
    positionInItemMs: Long,
    restartThresholdMs: Long = RESTART_THRESHOLD_MS,
  ): BookChapter? {
    if (current == null) return timeline.firstOrNull()
    val intoChapter = positionInItemMs - current.startInItemMs
    // Deep inside the chapter, "previous" restarts it -- which is what a listener who overshot
    // means. Only near its start does it mean the chapter before.
    if (intoChapter >= restartThresholdMs) return current
    return timeline.getOrNull(current.index - 1) ?: current
  }

  fun bookPositionMs(timeline: List<BookChapter>, mediaId: String, positionInItemMs: Long): Long {
    val itemStart = timeline.firstOrNull { it.mediaId == mediaId } ?: return positionInItemMs
    return itemStart.bookStartMs - itemStart.startInItemMs + positionInItemMs
  }
}
```

- [ ] **Step 5: Run the Tier 1 pair**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*ChapterAssemblyTest*' --tests '*BookTimelineTest*'`
Expected: PASS — `ChapterAssemblyTest` 10/10, `BookTimelineTest` 12/12.

- [ ] **Step 6: Write the failing reader test — the one that closes spike S3's open question**

`core/media/src/androidTest/kotlin/app/muplay/media/ChapterReaderTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.model.StreamFormat
import app.muplay.testing.BookFixtures
import app.muplay.testing.ExpectedBook
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **This class closes the one thing spike S3 left open**, and spec sections 5 and 12 both name it:
 * chapter extraction has never been run against a real Navidrome `format=raw` URL, only against a
 * hand-rolled Python server. Everything below runs against the pinned container.
 *
 * Two things are asserted, and the second is the unusual one:
 *
 * 1. `ChapterReader` returns exactly what **ffprobe** reads out of the same bytes. ffprobe is an
 *    oracle independent of Media3; a golden file recording what Media3 returned last time is not.
 * 2. The **wrong** wiring is asserted to produce the **wrong** answer. Spike S3's central finding
 *    is that `MetadataRetriever.Builder(...).build()` without `setMediaSourceFactory` silently
 *    returns `C.TIME_UNSET` end times and drops `chap` chapters, with no exception and no log. A
 *    test that only asserted the right answer would pass on `chpl` files with that bug in place;
 *    this one turns the footgun into a permanent, executable record of itself.
 */
@RunWith(AndroidJUnit4::class)
class ChapterReaderTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var reader: ChapterReader
  private lateinit var urls: Map<String, String>
  private lateinit var durations: Map<String, Long>

  @Before
  fun setUp() = runBlocking {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "chapters-${System.nanoTime()}")
    val dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), MediaCache.create(context, cacheDir))
    reader = ChapterReader(context, dataSourceFactory)

    // The corpus, resolved through the same client the app uses: album -> songs -> stream URLs.
    // `RealTrackBytes.client()` is Plan 3 Task 3's helper over the pinned container.
    val client = RealTrackBytes.client()
    val books = client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, app.muplay.model.AlbumListType.ALPHABETICAL_BY_NAME, 50, 0)
    val songs = books.flatMap { client.getAlbum(it.id, AUDIOBOOK_LIBRARY_ID).songs }
    urls = songs.associate { it.title to client.streamUrl(it.id, StreamFormat.forSuffix(it.suffix, 192)) }
    durations = songs.associate { it.title to it.durationSeconds * 1_000L }
  }

  @After
  fun tearDown() {
    cacheDir.deleteRecursively()
  }

  private fun read(title: String): List<app.muplay.model.Chapter> = runBlocking {
    reader.read(urls.getValue(title), durations.getValue(title))
  }

  private fun assertMatchesOracle(book: ExpectedBook, fileTitle: String) {
    val actual = read(fileTitle)

    // Exact lists, in order, field by field. Titles alone would pass for a reader that returned
    // the right names with wrong times; start times alone would pass for one that lost the titles.
    assertThat(actual.map { it.startMs })
      .describedAs("${book.albumName} chapter starts")
      .containsExactlyElementsOf(book.chapters.map { it.startMs })
    assertThat(actual.map { it.endMs })
      .describedAs("${book.albumName} chapter ends")
      .containsExactlyElementsOf(book.chapters.map { it.endMs })
    assertThat(actual.map { it.title.orEmpty() })
      .describedAs("${book.albumName} chapter titles")
      .containsExactlyElementsOf(book.chapters.map { it.title })
    assertThat(actual.map { it.index })
      .containsExactlyElementsOf(book.chapters.indices.toList())
  }

  @Test
  fun theThreeChapterFaststartBookMatchesFfprobe() {
    assertMatchesOracle(BookFixtures.TEST_BOOK, "Test Book")
  }

  @Test
  fun theFourChapterBookWithUnequalChaptersMatchesFfprobe() {
    // The second observation, and the one that breaks `index * 5000`. Test Book alone cannot
    // distinguish a reader from a constant.
    assertMatchesOracle(BookFixtures.SECOND_BOOK, "Second Book")
  }

  @Test
  fun aNonFaststartBookServedByNavidromeStillYieldsItsChapters() {
    // Spike S3's tail-Range finding, against Navidrome rather than against a Python script. If
    // this fails, the answer is in the server's response headers for a Range request into the
    // last kilobyte -- Task 1's live test asserts those, so read that failure first.
    assertMatchesOracle(BookFixtures.TAIL_BOOK, "Tail Book")
  }

  @Test
  fun aFileWithNoChapterAtomsReturnsNoChaptersRatherThanOne() {
    // `Multi Part Book`'s parts. "No chapters" must be an empty list, not a fabricated whole-file
    // chapter -- a fabricated one would make every chapterless file look chaptered and would put a
    // chapter title on a music track.
    assertThat(read("Part One")).isEmpty()
    assertThat(read("Part Two")).isEmpty()
  }

  @Test
  fun everyChapterCarriesAPopulatedEndTime() {
    // The direct assertion on the footgun's symptom. `C.TIME_UNSET` is `Long.MIN_VALUE + 1`; if
    // any of these is negative, `setMediaSourceFactory` is missing.
    val all = BookFixtures.ALL_BOOKS.flatMap { book -> book.tracks.map { read(it.title) } }.flatten()

    assertThat(all).describedAs("chapters read across the whole corpus").isNotEmpty
    assertThat(all.map { it.endMs > it.startMs || it.endMs == it.startMs })
      .describedAs("every end time populated and not before its start")
      .containsOnly(true)
    assertThat(all.none { it.endMs == C.TIME_UNSET }).isTrue
  }

  @Test
  fun theBareRetrieverBuilderReturnsUnusableEndTimesAndThisIsWhyTheFactoryIsMandatory() {
    // Spike S3's central finding, executable. This is deliberately NOT a test of production code:
    // it builds the broken retriever by hand and asserts it is broken, so that "the factory is
    // required" is a fact this suite re-checks rather than a comment somebody can delete.
    //
    // If this test starts failing because the bare builder now works, Media3 fixed the bug --
    // delete the test, update spec section 5, and say so loudly. Do not weaken it.
    val item = MediaItem.fromUri(urls.getValue("Test Book"))
    val ends = MetadataRetriever.Builder(context, item).build().use { retriever ->
      retriever.retrieveTrackGroups().get(30, TimeUnit.SECONDS).let { groups ->
        (0 until groups.length).flatMap { i ->
          val group = groups.get(i)
          (0 until group.length).flatMap { j ->
            val metadata = group.getFormat(j).metadata
            (0 until (metadata?.length() ?: 0)).mapNotNull { k ->
              (metadata!!.get(k) as? androidx.media3.extractor.metadata.Chapter)?.endTimeMs
            }
          }
        }
      }
    }

    assertThat(ends).describedAs("the bare builder found chapters at all").isNotEmpty
    assertThat(ends).describedAs("every end time unset, exactly as spike S3 measured")
      .containsOnly(C.TIME_UNSET)
    // ...and the wired reader, over the same URL, does not.
    assertThat(read("Test Book").map { it.endMs }).containsExactly(5_000L, 10_000L, 15_000L)
  }

  private companion object {
    const val AUDIOBOOK_LIBRARY_ID = 2
  }
}
```

- [ ] **Step 7: Implement the reader**

`core/media/src/main/kotlin/app/muplay/media/ChapterReader.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroupArray
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.Chapter as Media3Chapter
import androidx.media3.inspector.MetadataRetriever
import app.muplay.model.Chapter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads an audio file's own chapter atoms over HTTP.
 *
 * Navidrome exposes no chapter API and OpenSubsonic has no chapter schema (spec section 5), so this
 * is the only source there is. `media3-inspector` supplies `MetadataRetriever`; `media3-exoplayer`
 * does not depend on it, and `androidx.media3.exoplayer.MetadataRetriever` does not exist in
 * 1.11.0 at all — spike S3 proved that with a `javac` probe.
 *
 * **`setMediaSourceFactory` is not optional.** Without it, spike S3 measured — twice — that
 * QuickTime `chap` chapters are dropped entirely and every Nero `chpl` chapter comes back with
 * `endTimeMs == C.TIME_UNSET`. There is no exception, no log line and no other signal; a `chpl`
 * file returns plausible-looking data. `ChapterReaderTest` asserts the broken form is broken, so
 * deleting the line below reddens two tests rather than none.
 *
 * `retrieveTrackGroups()` returns a Guava `ListenableFuture`, and it is awaited with a blocking
 * `get` on [Dispatchers.IO] rather than by adding `kotlinx-coroutines-guava` — a whole artifact for
 * one `await()` is exactly what the dependency-minimalism constraint rules out.
 */
@Singleton
class ChapterReader @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dataSourceFactory: MuPlayDataSourceFactory,
) {

  suspend fun read(uri: String, contentDurationMs: Long): List<Chapter> = withContext(Dispatchers.IO) {
    MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
      // Required. See the class documentation; deleting this line is a silent correctness bug.
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory.create()))
      .build()
      .use { retriever ->
        val groups = retriever.retrieveTrackGroups().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        ChapterAssembly.assemble(rawChaptersOf(groups), contentDurationMs)
      }
  }

  private fun rawChaptersOf(groups: TrackGroupArray): List<RawChapter> = buildList {
    for (groupIndex in 0 until groups.length) {
      val group = groups.get(groupIndex)
      for (formatIndex in 0 until group.length) {
        val metadata = group.getFormat(formatIndex).metadata ?: continue
        for (entryIndex in 0 until metadata.length()) {
          val entry = metadata.get(entryIndex) as? Media3Chapter ?: continue
          add(
            RawChapter(
              startMs = entry.startTimeMs,
              // The sentinel stops here. Nothing below this line knows `C.TIME_UNSET` exists.
              endMs = entry.endTimeMs.takeIf { it != C.TIME_UNSET },
              // `getTitle()` is an `androidx.media3.common.Label`, not a `String` -- a detail easy
              // to miss, and `.toString()` on it does not print the title text.
              title = entry.title?.value,
            ),
          )
        }
      }
    }
  }

  companion object {
    /**
     * Generous, because a non-faststart file costs an extra Range round trip into the tail and a
     * cold container can be slow. A retriever that hangs forever would block a book screen with no
     * way out; a `TimeoutException` at least surfaces as "chapters unavailable".
     */
    const val TIMEOUT_MS = 30_000L
  }
}
```

> Confirm every symbol above against the resolved 1.11.0 artifacts before assuming: `Media3Chapter`
> is `androidx.media3.extractor.metadata.Chapter` (an interface), `entry.title` is an
> `androidx.media3.common.Label` with `.value` and `.language`, and `MetadataRetriever` is
> `AutoCloseable`. Spike S3 recorded all three from a real run; if a signature differs, match the
> real one and record the difference in the task report.

- [ ] **Step 8: Write the failing cache test, then the repository**

`core/media/src/androidTest/kotlin/app/muplay/media/ChapterRepositoryTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.MuPlayDatabase
import app.muplay.model.Song
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parse once, then serve from Room — measured as **HTTP requests that did not happen**, the same
 * shape as Plan 3 Task 3's media-cache proof.
 *
 * Counting requests rather than timing anything matters: a cache that "felt fast" because the
 * media cache had the bytes would still be re-parsing, and the parse is the expensive part.
 */
@RunWith(AndroidJUnit4::class)
class ChapterRepositoryTest {

  private lateinit var context: Context
  private lateinit var db: MuPlayDatabase
  private lateinit var repository: ChapterRepository
  private lateinit var cacheDir: File
  private lateinit var songs: List<Song>
  private val requests = AtomicInteger()

  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() = runBlocking {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    cacheDir = File(context.cacheDir, "chapter-repo-${System.nanoTime()}")

    val counting = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) })
      .build()
    val reader = ChapterReader(context, MuPlayDataSourceFactory(counting, MediaCache.create(context, cacheDir)))

    val client = RealTrackBytes.client()
    val books = client.getAlbumList2(2, app.muplay.model.AlbumListType.ALPHABETICAL_BY_NAME, 50, 0)
    songs = books.flatMap { client.getAlbum(it.id, 2).songs }
    repository = ChapterRepository(db.chapterDao(), reader, RealTrackBytes.sourceProvider(), clock)
  }

  @After
  fun tearDown() {
    db.close()
    cacheDir.deleteRecursively()
  }

  private fun song(title: String): Song = songs.single { it.title == title }

  @Test
  fun aSecondReadOfTheSameBookCostsNoFurtherHttpRequests() = runBlocking {
    val first = repository.chaptersFor(song("Second Book"))
    val afterFirst = requests.get()

    val second = repository.chaptersFor(song("Second Book"))

    assertThat(afterFirst).describedAs("the first read must actually go to the network").isPositive
    assertThat(requests.get() - afterFirst).isZero
    // ...and it is the same answer, not an empty one. A cache that returned nothing would also
    // make zero requests.
    assertThat(second.map { it.title }).containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(second).isEqualTo(first)
  }

  @Test
  fun aChapterlessFileIsProbedOnceAndThenRemembered() = runBlocking {
    // The negative cache, which is the common case rather than the rare one: most audiobook files
    // in the world carry no chapter atoms. Without `chapter_scans` this file is re-probed over
    // HTTP every time a screen opens.
    repository.chaptersFor(song("Part One"))
    val afterFirst = requests.get()

    val second = repository.chaptersFor(song("Part One"))

    assertThat(afterFirst).isPositive
    assertThat(requests.get() - afterFirst).isZero
    assertThat(second).isEmpty()
  }

  @Test
  fun oneBooksCachedChaptersAreNotAnotherBooksChapters() = runBlocking {
    // Two books, two answers, from one cache. With one book, "cached chapters for X" and "the
    // cached chapters" are the same query.
    val second = repository.chaptersFor(song("Second Book"))
    val tail = repository.chaptersFor(song("Tail Book"))

    assertThat(second.map { it.title }).containsExactly("Prologue", "The Long Middle", "A Turn", "Epilogue")
    assertThat(tail.map { it.title }).containsExactly("Head", "Tail")
  }

  @Test
  fun forgettingAFileMakesTheNextReadGoBackToTheNetwork() = runBlocking {
    repository.chaptersFor(song("Test Book"))
    val afterFirst = requests.get()

    repository.forget(song("Test Book").id)
    repository.chaptersFor(song("Test Book"))

    // The control for the two "zero further requests" assertions above: if the counter could not
    // go up, those assertions would be measuring nothing.
    assertThat(requests.get()).isGreaterThan(afterFirst)
  }

  @Test
  fun readingAWholeBookAtOnceReturnsAMapKeyedByMediaId() = runBlocking {
    val parts = songs.filter { it.albumName == "Multi Part Book" }.sortedBy { it.trackNumber }

    val byId = repository.chaptersFor(parts)

    // The keys, exactly. A batch read that returned one entry, or that keyed by title, would fail
    // here and pass every single-song test above.
    assertThat(byId.keys).containsExactlyInAnyOrderElementsOf(parts.map { it.id })
    assertThat(byId.values.map { it.size }).containsExactly(0, 0, 0)
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/ChapterRepository.kt`:

```kotlin
package app.muplay.media

import app.muplay.database.SubsonicSourceProvider
import app.muplay.database.dao.ChapterDao
import app.muplay.database.entity.ChapterEntity
import app.muplay.model.Chapter
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chapters, parsed once and then served from Room.
 *
 * Reading chapters is an HTTP round trip into a file's `moov` atom. Doing it every time a book
 * screen opens is a network request for data that cannot change unless the file does.
 *
 * The half that is easy to miss: **"this file has no chapters" is an answer, and it needs to be
 * remembered too.** Most audiobook files carry no chapter atoms at all, so a cache that can only
 * store chapters re-probes the common case forever. `chapter_scans` records that a probe happened;
 * `chapters` records what it found.
 */
@Singleton
class ChapterRepository @Inject constructor(
  private val chapterDao: ChapterDao,
  private val chapterReader: ChapterReader,
  private val sourceProvider: SubsonicSourceProvider,
  private val clock: Clock,
) {

  suspend fun chaptersFor(song: Song): List<Chapter> {
    chapterDao.findScan(song.id)?.let { return chapterDao.find(song.id).map(::toChapter) }

    val source = sourceProvider.current()
    val format = StreamFormat.forSuffix(song.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)
    val chapters = chapterReader.read(
      uri = source.streamUrl(song.id, format),
      contentDurationMs = song.durationSeconds * 1_000L,
    )

    chapterDao.store(
      mediaId = song.id,
      chapters = chapters.map {
        ChapterEntity(
          mediaId = song.id,
          chapterIndex = it.index,
          startMs = it.startMs,
          endMs = it.endMs,
          title = it.title,
        )
      },
      scannedAtEpochMs = clock.millis(),
    )
    return chapters
  }

  /**
   * One `SubsonicSource` for the whole book, and the results keyed by media id rather than by
   * title — two files of a book can share a title, and nothing downstream looks a file up by name.
   */
  suspend fun chaptersFor(songs: List<Song>): Map<String, List<Chapter>> =
    songs.associate { it.id to chaptersFor(it) }

  /** Drops both the chapters and the record that a probe happened, so the next read re-probes. */
  suspend fun forget(mediaId: String) = chapterDao.clear(mediaId)

  private fun toChapter(entity: ChapterEntity) = Chapter(
    index = entity.chapterIndex,
    startMs = entity.startMs,
    endMs = entity.endMs,
    title = entity.title,
  )
}
```

> `RealTrackBytes.sourceProvider()` does not exist in Plan 3 Task 3 — that helper exposes
> `musicTracks()`, `bytesOf(song)` and `client()`. Add a one-line `sourceProvider()` to it that
> wraps `client()` in a `SubsonicSourceProvider` whose `current()` returns it, or construct the
> repository with a two-line hand-written provider in this test's `@Before`. Either is fine; a
> mock framework is not.

- [ ] **Step 9: Run the device suites**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :core:media:connectedDebugAndroidTest --tests '*ChapterReaderTest*' --tests '*ChapterRepositoryTest*'
```

Expected: PASS — `ChapterReaderTest` 6/6, `ChapterRepositoryTest` 5/5.

**If `aNonFaststartBookServedByNavidromeStillYieldsItsChapters` fails**, that is a real finding, not
a test to relax: it means Navidrome's `format=raw` path does not give Media3 what a tail Range
needs. Read Task 1's live header assertions first, then record it in the task report and in spec
§12's risk row with the measured behaviour. The multi-file fallback ("one file = one chapter")
stays available either way, so it does not block the plan — but it must not be papered over.

- [ ] **Step 10: Prove every part of this can fail**

One mutation at a time, reverted after each:

1. Delete `.setMediaSourceFactory(...)` from `ChapterReader.read`. Expect
   `everyChapterCarriesAPopulatedEndTime` **and**
   `theBareRetrieverBuilderReturnsUnusableEndTimesAndThisIsWhyTheFactoryIsMandatory` to fail — the
   first because the good path broke, the second because the two paths stopped differing. Spike
   S3's footgun, caught twice.
2. In `ChapterAssembly.assemble`, drop the `sortedBy { it.startMs }`. Expect
   `chapters are ordered by start time, whatever order they arrived in` to fail.
3. In `ChapterAssembly.assemble`, replace the `fallback` with `contentDurationMs` unconditionally.
   Expect `a missing end time is filled from the next chapter's start` to fail.
4. In `BookTimeline.of`, use `chapter.startMs` for `bookStartMs` instead of
   `bookOffset + chapter.startMs`. Expect
   `a multi-file book whose files also carry chapters is neither of the easy cases` and
   `the book position adds the offset of the item you are in` to fail — and note that
   `a single-file book's chapters all live in item zero` stays **green**, which is the whole reason
   the mixed-shape fixture exists.
5. In `BookTimeline.previous`, change `>=` to `>`. Expect
   `the restart threshold is a parameter and it actually moves the answer` to fail.
6. In `ChapterRepository.chaptersFor`, drop the `findScan` short-circuit. Expect
   `aSecondReadOfTheSameBookCostsNoFurtherHttpRequests` and
   `aChapterlessFileIsProbedOnceAndThenRemembered` to fail.
7. In `ChapterRepository.chaptersFor`, skip `chapterDao.store` when `chapters.isEmpty()`. Expect
   only `aChapterlessFileIsProbedOnceAndThenRemembered` to fail — the negative cache, isolated.

- [ ] **Step 11: Record the probes, re-measure, commit**

Mutations 2, 3, 4 and 5 are JVM-side, so they belong in `ci/mutation-probes.sh`. Adding them needs
**two** edits the script's shape makes easy to forget:

- `run_suite()` must run `:core:media:testDebugUnitTest` as well as the two modules it names today,
  and `failures()` must glob `core/*/build/test-results/testDebugUnitTest/TEST-*.xml` as well as
  `.../test/...` — an Android module's JVM results land under a different directory name than a
  JVM module's.
- `revert()` takes an explicit file list for `git checkout --`. A probe whose file is not in that
  list **mutates the tree and never reverts it**, and the script's own header records that this
  exact revert destroyed real work twice. Add `ChapterAssembly.kt` and `BookTimeline.kt` to it.

Mutations 1, 6 and 7 are device-side; record them in the task report as the script's SCOPE note
requires, not in the table.

Re-measure `:core:media`'s floors: `ChapterAssembly` and `BookTimeline` are JVM-enforceable
(`requiresInstrumentedData = false`); `ChapterReader` and `ChapterRepository` are instrumented.

```bash
git add core/media gradle/libs.versions.toml build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): chapters out of the file's own bytes, and a timeline over them"
```

---

## Task 4: `AudiobookRepository` — what a book *is*, the shelf order, and the settings write

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/BookSummary.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/dao/AudiobookDao.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/BookSummaries.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/AudiobookRepository.kt`
- Modify: `core/database/src/main/kotlin/app/muplay/database/MuPlayDatabase.kt` (one dao accessor)
- Modify: `core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt`
- Test: `core/database/src/test/kotlin/app/muplay/database/BookSummariesTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/AudiobookRepositoryTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `LibraryRole` (Plan 2), `AlbumEntity`, `SongEntity` (Plan 2 Task 5),
  `MediaProgressEntity`, `MediaProgressDao.observeAll/findIn/clear/upsert` (Task 2),
  `BookSettingsEntity`, `BookSettingsDao` (Task 2), `BookSettings` (Task 2),
  `MirrorMapper.song(entity)` (Plan 2 Task 5), `java.time.Clock` (Plan 3 Task 8).
- Produces:
  - `data class BookSummary(bookId, libraryId, title, author, coverArtId, fileCount, durationMs,
    positionMs, isFinished, lastPlayedAtEpochMs)` with `val remainingMs: Long`,
    `val progressFraction: Float`, `val hasStarted: Boolean`
  - `data class ResumePoint(val mediaId: String, val positionMs: Long, val lastPlayedAtEpochMs: Long)`
  - `data class AudiobookItemRow(val mediaId: String, val albumId: String?)`
  - `AudiobookDao` — `observeBookAlbums(role): Flow<List<AlbumEntity>>`,
    `observeItems(role): Flow<List<AudiobookItemRow>>`,
    `observeSongsInRole(role): Flow<List<SongEntity>>`,
    `suspend files(bookId: String): List<SongEntity>`,
    `suspend findBookAlbum(bookId: String): AlbumEntity?`
  - `internal object BookSummaries` — `fun playOrder(files: List<SongEntity>): List<SongEntity>`,
    `fun summarise(album: AlbumEntity, files: List<SongEntity>, progress: Map<String, MediaProgressEntity>): BookSummary`,
    `fun resumePoint(files: List<SongEntity>, progress: Map<String, MediaProgressEntity>): ResumePoint?`,
    `fun order(books: List<BookSummary>): List<BookSummary>`
  - `class AudiobookRepository @Inject constructor(audiobookDao, mediaProgressDao, bookSettingsDao, clock)`
    with `fun bookshelf(): Flow<List<BookSummary>>`, `suspend fun book(bookId): BookSummary?`,
    `suspend fun files(bookId): List<Song>`, `suspend fun resumePoint(bookId): ResumePoint?`,
    `suspend fun settings(bookId): BookSettings`, `fun observeSettings(bookId): Flow<BookSettings>`,
    `suspend fun setSpeed(bookId, speed: Float)`, `suspend fun setSkipSilence(bookId, enabled: Boolean)`,
    `suspend fun restart(bookId)`, `suspend fun markFinished(bookId)`,
    `fun observeAudiobookItems(): Flow<Map<String, String>>`,
    `companion object { fun bookIdOf(song: Song): String; fun bookIdOf(song: SongEntity): String }`

### What a book is, stated once so nothing has to guess

**A book is an album in a library the user tagged `AUDIOBOOKS`.** Its id is the album id. That is
the whole definition, and every other component in this plan asks this repository rather than
re-deriving it.

Two consequences worth stating rather than discovering:

- **The library role is the only signal.** Navidrome hardcodes `child.Type = "music"` for every
  media file and there is no server setting for it (spec §4). Not the suffix — `Multi Part Book` is
  three MP3s and `Test Album` could contain an `.m4b`. Not the folder name — "Hörbücher" is not
  "Audiobooks", and spec §4 says never to guess from a name. Not the chapter count — most audiobook
  files have none.
- **A loose file with no album is not on the shelf, and that is a bounded limitation, not a bug.**
  `bookIdOf` falls back to the song's own id so its settings and its position still work if it is
  played from the ordinary browse screens; it simply has no shelf row, because the shelf is a list
  of albums. Recorded here so nobody "fixes" it by inventing synthetic albums.

### The shelf order, and why it is a pure function

The shelf answers *"what do I carry on with?"*, so the order is three groups:

1. **Started and unfinished**, most recently heard first. This is the entire point of the screen.
2. **Never started**, alphabetically by title.
3. **Finished**, most recently heard first, at the bottom.

That is a `Comparator` with three keys and two direction flips, which is exactly the kind of code
that looks right and is wrong at one boundary. It lives in `BookSummaries.order`, takes and returns
a plain list, and is gated in **Tier 1** with a corpus that has at least one book in each group —
because a shelf with one group is sorted correctly by every implementation there is.

### The write that must not clobber its neighbour

Plan 3 Task 8 named the trap on `media_progress`: a writer that constructs a fresh entity resets a
listener's per-book speed every five seconds. **The same trap exists one table over.**
`setSpeed(bookId, 1.4f)` that writes `BookSettingsEntity(bookId, 1.4f, skipSilence = false)` turns
silence skipping off every time the listener touches the speed control, and nothing reports it. So
both setters are read-modify-write and `AudiobookRepositoryTest` asserts each preserves the other.

- [ ] **Step 1: Write the failing pure tests**

`core/database/src/test/kotlin/app/muplay/database/BookSummariesTest.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * The shelf's arithmetic and its order, gated in Tier 1.
 *
 * Everything here is a pure function over plain data classes, which is the point: a three-key
 * comparator with two direction flips is exactly the code that looks right and is wrong at one
 * boundary, and gating it on an emulator would mean discovering that at four minutes a run.
 */
class BookSummariesTest {

  private fun album(id: String, name: String, artist: String? = "Author", library: Int = 2) =
    AlbumEntity(
      id = id, libraryId = library, artistId = null, name = name, artistName = artist,
      coverArtId = "art-$id", songCount = 0, durationSeconds = 0, sortName = name.lowercase(),
    )

  private fun file(id: String, book: String, track: Int?, disc: Int? = null, seconds: Int, title: String) =
    SongEntity(
      id = id, libraryId = 2, albumId = book, artistId = null, title = title, albumName = "book",
      artistName = "Author", trackNumber = track, discNumber = disc, durationSeconds = seconds,
      suffix = "mp3", coverArtId = null, sortTitle = title.lowercase(),
    )

  private fun progress(id: String, position: Long, finished: Boolean = false, at: Long = 0L) =
    id to MediaProgressEntity(id, position, finished, at, 1f, false, 0f)

  private fun summary(
    id: String,
    name: String,
    position: Long,
    finished: Boolean,
    lastPlayedAt: Long?,
  ) = app.muplay.model.BookSummary(
    bookId = id, libraryId = 2, title = name, author = "Author", coverArtId = null,
    fileCount = 1, durationMs = 60_000, positionMs = position, isFinished = finished,
    lastPlayedAtEpochMs = lastPlayedAt,
  )

  // ---- play order ----------------------------------------------------------------------------

  @Test
  fun `files play in disc then track order, whatever order the mirror handed them over in`() {
    val files = listOf(
      file("c", "b", track = 1, disc = 2, seconds = 5, title = "Disc 2 Track 1"),
      file("b", "b", track = 2, disc = 1, seconds = 6, title = "Disc 1 Track 2"),
      file("a", "b", track = 1, disc = 1, seconds = 4, title = "Disc 1 Track 1"),
    )

    // `containsExactly`. A book whose files play in mirror order is a book that plays chapter 12
    // after chapter 3, and `containsExactlyInAnyOrder` would have nothing to say about it.
    assertThat(BookSummaries.playOrder(files).map { it.id }).containsExactly("a", "b", "c")
  }

  @Test
  fun `a file with no track number sorts after every numbered one, by title`() {
    // Real rips have untagged bonus files. `null` sorting first would put "Afterword" before
    // chapter 1, which is the wrong end of the book.
    val files = listOf(
      file("z", "b", track = null, seconds = 3, title = "Afterword"),
      file("a", "b", track = 1, seconds = 4, title = "One"),
      file("m", "b", track = null, seconds = 3, title = "About the author"),
    )

    assertThat(BookSummaries.playOrder(files).map { it.title })
      .containsExactly("One", "About the author", "Afterword")
  }

  // ---- summarising ---------------------------------------------------------------------------

  @Test
  fun `a book's duration is the sum of its files, not the album row's guess`() {
    // Two books with different file sets, so a constant fails one of them. The album row's own
    // `durationSeconds` is deliberately wrong here (0) -- the mirror's album duration comes from
    // the server and the files are the truth this screen shows.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
      file("c", "b", 3, seconds = 5, title = "Three"),
    )

    assertThat(BookSummaries.summarise(album("b", "Multi Part Book"), files, emptyMap()).durationMs)
      .isEqualTo(15_000L)
    assertThat(BookSummaries.summarise(album("b", "Multi Part Book"), files.take(2), emptyMap()).durationMs)
      .isEqualTo(10_000L)
  }

  @Test
  fun `a book's position is the files before the current one plus the position inside it`() {
    // The number a whole-book progress bar shows, and the one place a multi-file book differs from
    // a single-file one. Three observations, one per file, so "the position in the current file"
    // and "the position in the book" cannot be the same program.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
      file("c", "b", 3, seconds = 5, title = "Three"),
    )

    assertThat(BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 1_000, at = 5))).positionMs)
      .isEqualTo(1_000L)
    assertThat(BookSummaries.summarise(album("b", "B"), files, mapOf(progress("b", 1_000, at = 5))).positionMs)
      .isEqualTo(5_000L)
    assertThat(BookSummaries.summarise(album("b", "B"), files, mapOf(progress("c", 1_000, at = 5))).positionMs)
      .isEqualTo(11_000L)
  }

  @Test
  fun `the current file is the most recently heard one, not the furthest one`() {
    // A listener who jumped back to chapter 1 is in chapter 1. "Furthest" would drag them forward
    // every time they went back, which is a bug you only notice by losing your place.
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )
    val progress = mapOf(progress("a", 2_000, at = 900), progress("b", 3_000, at = 100))

    assertThat(BookSummaries.summarise(album("b", "B"), files, progress).positionMs).isEqualTo(2_000L)
    assertThat(BookSummaries.resumePoint(files, progress)?.mediaId).isEqualTo("a")
  }

  @Test
  fun `a book nobody has opened has no resume point and no last-played time`() {
    val files = listOf(file("a", "b", 1, seconds = 4, title = "One"))

    val summary = BookSummaries.summarise(album("b", "B"), files, emptyMap())

    assertThat(summary.lastPlayedAtEpochMs).isNull()
    assertThat(summary.positionMs).isZero
    assertThat(summary.hasStarted).isFalse
    assertThat(BookSummaries.resumePoint(files, emptyMap())).isNull()
  }

  @Test
  fun `a book is finished when its last file is finished, and not before`() {
    val files = listOf(
      file("a", "b", 1, seconds = 4, title = "One"),
      file("b", "b", 2, seconds = 6, title = "Two"),
    )

    // Two observations of the same field with only the *which file* varied.
    assertThat(BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 4_000, finished = true, at = 5))).isFinished)
      .isFalse
    assertThat(BookSummaries.summarise(album("b", "B"), files, mapOf(progress("b", 6_000, finished = true, at = 5))).isFinished)
      .isTrue
  }

  @Test
  fun `the remaining time and the fraction follow the position`() {
    val files = listOf(file("a", "b", 1, seconds = 10, title = "One"))

    val quarter = BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 2_500, at = 5)))
    val most = BookSummaries.summarise(album("b", "B"), files, mapOf(progress("a", 9_000, at = 5)))

    assertThat(quarter.remainingMs).isEqualTo(7_500L)
    assertThat(most.remainingMs).isEqualTo(1_000L)
    assertThat(quarter.progressFraction).isCloseTo(0.25f, Offset.offset(0.001f))
    assertThat(most.progressFraction).isCloseTo(0.9f, Offset.offset(0.001f))
  }

  @Test
  fun `a book with no files at all does not divide by zero`() {
    // The vacuous case. `positionMs / durationMs` with an empty file list is NaN, and NaN reaches
    // a `LinearProgressIndicator`, which throws.
    val summary = BookSummaries.summarise(album("b", "B"), emptyList(), emptyMap())

    assertThat(summary.durationMs).isZero
    assertThat(summary.progressFraction).isZero
    assertThat(summary.remainingMs).isZero
  }

  // ---- order ---------------------------------------------------------------------------------

  @Test
  fun `the shelf is continue-listening first, then unstarted alphabetically, then finished`() {
    // Three groups, and at least two members in the two groups whose order is by time -- a single
    // member per group is sorted correctly by every implementation there is.
    val books = listOf(
      summary("zed", "Zed", position = 0, finished = false, lastPlayedAt = null),
      summary("old-finished", "Old Finished", position = 100, finished = true, lastPlayedAt = 10),
      summary("recent", "Recent", position = 100, finished = false, lastPlayedAt = 900),
      summary("alpha", "Alpha", position = 0, finished = false, lastPlayedAt = null),
      summary("older", "Older", position = 100, finished = false, lastPlayedAt = 500),
      summary("new-finished", "New Finished", position = 100, finished = true, lastPlayedAt = 800),
    )

    assertThat(BookSummaries.order(books).map { it.title })
      .containsExactly("Recent", "Older", "Alpha", "Zed", "New Finished", "Old Finished")
  }

  @Test
  fun `a finished book drops below an unstarted one even though it was heard more recently`() {
    // The boundary between group 2 and group 3, isolated. Sorting purely by `lastPlayedAt` would
    // put the finished book on top, which is the most annoying possible shelf.
    val books = listOf(
      summary("done", "Done", position = 100, finished = true, lastPlayedAt = 999),
      summary("fresh", "Fresh", position = 0, finished = false, lastPlayedAt = null),
    )

    assertThat(BookSummaries.order(books).map { it.title }).containsExactly("Fresh", "Done")
  }

  @Test
  fun `ordering an empty shelf is an empty shelf`() {
    assertThat(BookSummaries.order(emptyList())).isEmpty()
  }
}
```

- [ ] **Step 2: Run, watch it fail, then write the model and the pure object**

Run: `./gradlew :core:database:testDebugUnitTest --tests '*BookSummariesTest*'`
Expected: FAIL, `Unresolved reference: BookSummaries`.

`core/model/src/main/kotlin/app/muplay/model/BookSummary.kt`:

```kotlin
package app.muplay.model

/**
 * One row of the bookshelf: a book, and how far into it the listener is.
 *
 * [positionMs] is a position in the **book**, not in a file — for a thirty-file rip those are very
 * different numbers, and the shelf shows the first one. [lastPlayedAtEpochMs] is null for a book
 * nobody has opened, which is a different fact from "opened, and at zero": the first belongs in the
 * alphabetical group of the shelf and the second belongs at the top.
 */
data class BookSummary(
  val bookId: String,
  val libraryId: Int,
  val title: String,
  val author: String?,
  val coverArtId: String?,
  val fileCount: Int,
  val durationMs: Long,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long?,
) {
  val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)

  /** `0f` for a book with no files: `0 / 0` is `NaN`, and `NaN` reaching a progress indicator throws. */
  val progressFraction: Float
    get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

  val hasStarted: Boolean get() = lastPlayedAtEpochMs != null
}

/**
 * Where a book carries on: which file, and how far into it.
 *
 * This is *not* a resume position — the smart rewind has not been applied and [ResumePolicy]
 * (Task 6) is the only thing allowed to decide the position playback starts at. This type answers
 * the question the policy is not asked: **which item**.
 */
data class ResumePoint(
  val mediaId: String,
  val positionMs: Long,
  val lastPlayedAtEpochMs: Long,
)
```

`core/database/src/main/kotlin/app/muplay/database/BookSummaries.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSummary
import app.muplay.model.ResumePoint

/**
 * The shelf's arithmetic and its order, with no Room, no Android and no coroutines in sight.
 *
 * Split out of [AudiobookRepository] so it is gated in Tier 1: a three-key comparator with two
 * direction flips is precisely the code that looks right and is wrong at one boundary, and an
 * emulator round trip per mutation is the wrong price for finding that out.
 */
internal object BookSummaries {

  /**
   * Disc, then track, then title, then id.
   *
   * A file with no track number sorts **after** every numbered one rather than before: real rips
   * carry untagged extras ("Afterword", "About the author") and putting them first means the book
   * opens on its own afterword.
   */
  fun playOrder(files: List<SongEntity>): List<SongEntity> = files.sortedWith(
    compareBy(
      { it.discNumber ?: 1 },
      { it.trackNumber ?: Int.MAX_VALUE },
      { it.sortTitle },
      { it.id },
    ),
  )

  fun summarise(
    album: AlbumEntity,
    files: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): BookSummary {
    val ordered = playOrder(files)
    val durationMs = ordered.sumOf { it.durationSeconds * 1_000L }
    val current = currentFile(ordered, progress)
    val currentProgress = current?.let { progress[it.id] }

    // Every file before the current one, plus how far into the current one. For a single-file book
    // this is just the file position, which is exactly why a single-file book cannot prove the
    // offset is computed at all.
    val offsetMs = current?.let { file ->
      ordered.takeWhile { it.id != file.id }.sumOf { it.durationSeconds * 1_000L }
    } ?: 0L

    return BookSummary(
      bookId = album.id,
      libraryId = album.libraryId,
      title = album.name,
      author = album.artistName,
      coverArtId = album.coverArtId,
      fileCount = ordered.size,
      durationMs = durationMs,
      positionMs = offsetMs + (currentProgress?.positionMs ?: 0L),
      // Reaching the end of the last file is what finishes a book. "Every file finished" would
      // leave a book unfinished forever because the listener skipped a five-second interlude.
      isFinished = ordered.isNotEmpty() && progress[ordered.last().id]?.isFinished == true,
      lastPlayedAtEpochMs = ordered.mapNotNull { progress[it.id]?.lastPlayedAtEpochMs }.maxOrNull(),
    )
  }

  fun resumePoint(
    files: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): ResumePoint? {
    val ordered = playOrder(files)
    val file = currentFile(ordered, progress) ?: return null
    val row = progress[file.id] ?: return null
    return ResumePoint(
      mediaId = file.id,
      positionMs = row.positionMs,
      lastPlayedAtEpochMs = row.lastPlayedAtEpochMs,
    )
  }

  /**
   * Continue-listening first (most recent first), then never-opened (alphabetical), then finished
   * (most recent first).
   *
   * The group key is what makes a finished book sink below an unopened one even though it was
   * heard a minute ago — sorting purely by time produces the most annoying shelf available.
   */
  fun order(books: List<BookSummary>): List<BookSummary> = books.sortedWith(
    compareBy<BookSummary> { group(it) }
      .thenByDescending { if (group(it) == GROUP_UNSTARTED) 0L else it.lastPlayedAtEpochMs ?: 0L }
      .thenBy { it.title.lowercase() }
      .thenBy { it.bookId },
  )

  private const val GROUP_IN_PROGRESS = 0
  private const val GROUP_UNSTARTED = 1
  private const val GROUP_FINISHED = 2

  private fun group(book: BookSummary): Int = when {
    book.isFinished -> GROUP_FINISHED
    book.hasStarted -> GROUP_IN_PROGRESS
    else -> GROUP_UNSTARTED
  }

  /**
   * The most recently heard file, not the furthest one. A listener who jumped back to chapter 1 is
   * in chapter 1; "furthest" would drag them forward every time they went back.
   */
  private fun currentFile(
    ordered: List<SongEntity>,
    progress: Map<String, MediaProgressEntity>,
  ): SongEntity? = ordered
    .filter { progress.containsKey(it.id) }
    .maxByOrNull { progress.getValue(it.id).lastPlayedAtEpochMs }
}
```

- [ ] **Step 3: Run the pure tests**

Run: `./gradlew :core:database:testDebugUnitTest --tests '*BookSummariesTest*'`
Expected: PASS, 12/12.

- [ ] **Step 4: Write the failing repository test**

`core/database/src/androidTest/kotlin/app/muplay/database/AudiobookRepositoryTest.kt`:

```kotlin
package app.muplay.database

import app.cash.turbine.test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.BookSettings
import app.muplay.model.LibraryRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real in-memory Room, real SQL, three books.
 *
 * **Three, not one.** With one book, "the settings for book X" and "the settings" are the same
 * value, "the shelf order" is sorted by every implementation there is, and "resume onto the right
 * file" is unfalsifiable. The corpus here mirrors `ci/fixtures`: a single-file book, a
 * three-file book, and a book in the *music* library that must never appear.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookRepositoryTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var repository: AudiobookRepository
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    repository = AudiobookRepository(db.audiobookDao(), db.mediaProgressDao(), db.bookSettingsDao(), clock)

    db.libraryDao().mergeFromServer(
      listOf(
        LibraryEntity(musicFolderId = 1, name = "Music", role = LibraryRole.MUSIC),
        LibraryEntity(musicFolderId = 2, name = "Audiobooks", role = LibraryRole.AUDIOBOOKS),
      ),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 2,
      artists = emptyList(),
      albums = listOf(album("single", "Test Book", 2), album("multi", "Multi Part Book", 2)),
      songs = listOf(
        song("single-1", "single", track = 1, seconds = 15, title = "Test Book", library = 2),
        song("multi-1", "multi", track = 1, seconds = 4, title = "Part One", library = 2),
        song("multi-2", "multi", track = 2, seconds = 6, title = "Part Two", library = 2),
        song("multi-3", "multi", track = 3, seconds = 5, title = "Part Three", library = 2),
      ),
    )
    db.browseDao().replaceLibraryContents(
      libraryId = 1,
      artists = emptyList(),
      albums = listOf(album("record", "Test Album", 1)),
      songs = listOf(song("track-1", "record", track = 1, seconds = 5, title = "Track 1", library = 1)),
    )
  }

  @After
  fun tearDown() = db.close()

  private fun album(id: String, name: String, library: Int) = AlbumEntity(
    id = id, libraryId = library, artistId = null, name = name, artistName = "$name Author",
    coverArtId = "art-$id", songCount = 0, durationSeconds = 0, sortName = name.lowercase(),
  )

  private fun song(id: String, albumId: String, track: Int, seconds: Int, title: String, library: Int) =
    SongEntity(
      id = id, libraryId = library, albumId = albumId, artistId = null, title = title,
      albumName = albumId, artistName = "Author", trackNumber = track, discNumber = 1,
      durationSeconds = seconds, suffix = "mp3", coverArtId = null, sortTitle = title.lowercase(),
    )

  private suspend fun record(mediaId: String, positionMs: Long, at: Long, finished: Boolean = false) =
    db.mediaProgressDao().upsert(MediaProgressEntity(mediaId, positionMs, finished, at, 1f, false, 0f))

  @Test
  fun theShelfHoldsBooksAndOnlyBooks() = runTest {
    // The headline constraint, at the repository. A music album in the shelf means a music album
    // in the audiobook resume path, which is spec section 3's "music restarts from 0" broken.
    repository.bookshelf().test {
      assertThat(awaitItem().map { it.title }).containsExactlyInAnyOrder("Test Book", "Multi Part Book")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun theShelfPutsTheBookYouWereListeningToFirst() = runTest {
    record("multi-2", positionMs = 1_000, at = 900)
    record("single-1", positionMs = 2_000, at = 100)

    repository.bookshelf().test {
      // Two started books with two different times. With one, "sorted by time" and "any order" are
      // the same list.
      assertThat(awaitItem().map { it.title }).containsExactly("Multi Part Book", "Test Book")
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun theShelfUpdatesWhenProgressIsWritten() = runTest {
    repository.bookshelf().test {
      assertThat(awaitItem().single { it.title == "Multi Part Book" }.positionMs).isZero

      record("multi-2", positionMs = 1_000, at = 900)

      // A second emission with a different value. A `bookshelf()` that emitted once and stopped
      // would leave the shelf showing the app's first second of life forever, and an assertion on
      // the first emission alone would never notice.
      val updated = awaitItem().single { it.title == "Multi Part Book" }
      assertThat(updated.positionMs).isEqualTo(5_000L)
      assertThat(updated.lastPlayedAtEpochMs).isEqualTo(900L)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun theResumePointNamesTheFileTheListenerWasIn() = runBlocking {
    record("multi-2", positionMs = 3_500, at = 900)

    val point = repository.resumePoint("multi")

    // The file, not the first file. A resume that always answered file one would pass every
    // single-file test in this plan.
    assertThat(point?.mediaId).isEqualTo("multi-2")
    assertThat(point?.positionMs).isEqualTo(3_500L)
    assertThat(point?.lastPlayedAtEpochMs).isEqualTo(900L)
  }

  @Test
  fun twoBooksKeepTwoResumePoints() = runBlocking {
    // The original complaint, at its smallest. One book cannot express it.
    record("multi-3", positionMs = 1_500, at = 900)
    record("single-1", positionMs = 12_345, at = 800)

    assertThat(repository.resumePoint("multi")?.mediaId).isEqualTo("multi-3")
    assertThat(repository.resumePoint("multi")?.positionMs).isEqualTo(1_500L)
    assertThat(repository.resumePoint("single")?.positionMs).isEqualTo(12_345L)
  }

  @Test
  fun filesComeBackInPlayOrder() = runBlocking {
    assertThat(repository.files("multi").map { it.title })
      .containsExactly("Part One", "Part Two", "Part Three")
  }

  @Test
  fun settingsDefaultWhenNobodyHasSetThem() = runBlocking {
    val settings = repository.settings("multi")

    assertThat(settings.speed).isEqualTo(BookSettings.DEFAULT_SPEED)
    assertThat(settings.skipSilence).isFalse
  }

  @Test
  fun twoBooksKeepTwoSpeeds() = runBlocking {
    repository.setSpeed("multi", 1.4f)
    repository.setSpeed("single", 0.8f)

    assertThat(listOf(repository.settings("multi").speed, repository.settings("single").speed))
      .containsExactly(1.4f, 0.8f)
  }

  @Test
  fun settingTheSpeedDoesNotTurnSilenceSkippingOff() = runBlocking {
    // Plan 3 Task 8 named this trap on `media_progress`; it exists identically on `book_settings`.
    // A setter that constructs a whole fresh row turns off a feature the listener switched on, and
    // nothing reports it.
    repository.setSkipSilence("multi", true)

    repository.setSpeed("multi", 1.4f)

    assertThat(repository.settings("multi").skipSilence).isTrue
    assertThat(repository.settings("multi").speed).isEqualTo(1.4f)
  }

  @Test
  fun turningSilenceSkippingOnDoesNotResetTheSpeed() = runBlocking {
    // The same trap in the other direction, because a read-modify-write can be got right one way
    // and wrong the other.
    repository.setSpeed("multi", 1.4f)

    repository.setSkipSilence("multi", true)

    assertThat(repository.settings("multi").speed).isEqualTo(1.4f)
  }

  @Test
  fun anImpossibleSpeedIsClampedOnTheWayInAndOnTheWayOut() = runBlocking {
    repository.setSpeed("multi", 99f)
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.MAX_SPEED)

    repository.setSpeed("multi", 0.01f)
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.MIN_SPEED)

    // ...and a row that got past the setter -- a hand-edited database, a future bug -- still cannot
    // reach `ExoPlayer.setPlaybackSpeed`. `Float.NaN.coerceIn(...)` returns NaN, and NaN there
    // throws from inside a listener callback, which surfaces as playback dying with no message.
    db.bookSettingsDao().upsert(app.muplay.database.entity.BookSettingsEntity("multi", Float.NaN, false))
    assertThat(repository.settings("multi").speed).isEqualTo(BookSettings.DEFAULT_SPEED)
  }

  @Test
  fun restartingABookClearsItsProgressAndNobodyElses() = runBlocking {
    record("multi-2", positionMs = 3_500, at = 900)
    record("single-1", positionMs = 12_345, at = 800)

    repository.restart("multi")

    assertThat(repository.resumePoint("multi")).isNull()
    // The control. A `clear` with a wrong `IN` clause takes the neighbour's place with it, and
    // that is the one failure this whole application exists to prevent.
    assertThat(repository.resumePoint("single")?.positionMs).isEqualTo(12_345L)
  }

  @Test
  fun markingABookFinishedShowsUpOnTheShelfAndCanBeUndone() = runTest {
    // Plan 3 Task 8 deferred "un-finish on replay" to the plan with a UI to express it. This is
    // that plan, and `restart` is that expression.
    repository.markFinished("multi")
    assertThat(repository.book("multi")?.isFinished).isTrue

    repository.restart("multi")
    assertThat(repository.book("multi")?.isFinished).isFalse
  }

  @Test
  fun theAudiobookItemMapNamesEveryBookFileAndNoMusicFile() = runTest {
    repository.observeAudiobookItems().test {
      val items = awaitItem()
      // Exact keys and exact values. A map that answered every media id would make music resume,
      // and a map keyed by book would make no file resume.
      assertThat(items.keys).containsExactlyInAnyOrder("single-1", "multi-1", "multi-2", "multi-3")
      assertThat(items["multi-2"]).isEqualTo("multi")
      assertThat(items["single-1"]).isEqualTo("single")
      assertThat(items).doesNotContainKey("track-1")
      cancelAndIgnoreRemainingEvents()
    }
  }
}
```

- [ ] **Step 5: Write the DAO and the repository**

`core/database/src/main/kotlin/app/muplay/database/dao/AudiobookDao.kt`:

```kotlin
package app.muplay.database.dao

import androidx.room.Dao
import androidx.room.Query
import app.muplay.database.entity.AlbumEntity
import app.muplay.database.entity.SongEntity
import app.muplay.model.LibraryRole
import kotlinx.coroutines.flow.Flow

/** One mirrored song, reduced to "which book does this file belong to". */
data class AudiobookItemRow(val mediaId: String, val albumId: String?)

/**
 * The mirror, read through the **user's own library-role assignment**.
 *
 * Every query here joins `libraries` on `role`, because that assignment is the only thing in the
 * world that says a file is an audiobook — Navidrome hardcodes `child.Type = "music"` for every
 * media file (spec section 4). A query that filtered on a suffix, a folder name or a duration would
 * be guessing.
 *
 * `LibraryRole` binds through the type converter Plan 2 Task 4 registered for `LibraryEntity.role`.
 * If that converter is `@TypeConverters`-scoped to that entity rather than to the database, widen
 * it there rather than adding a second one.
 */
@Dao
interface AudiobookDao {

  @Query(
    "SELECT * FROM albums WHERE libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeBookAlbums(role: LibraryRole = LibraryRole.AUDIOBOOKS): Flow<List<AlbumEntity>>

  @Query(
    "SELECT s.id AS mediaId, s.albumId AS albumId FROM songs s WHERE s.libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeItems(role: LibraryRole = LibraryRole.AUDIOBOOKS): Flow<List<AudiobookItemRow>>

  @Query(
    "SELECT * FROM songs WHERE libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  fun observeSongsInRole(role: LibraryRole = LibraryRole.AUDIOBOOKS): Flow<List<SongEntity>>

  /**
   * A book's files. The `OR` arm is the loose-file case: a song with no album is its own book, so
   * its book id is its own media id.
   */
  @Query("SELECT * FROM songs WHERE albumId = :bookId OR (albumId IS NULL AND id = :bookId)")
  suspend fun files(bookId: String): List<SongEntity>

  /**
   * One book's album row, scoped to the role so a music album can never be looked up as a book —
   * the same guard every other query here carries, for the same reason.
   */
  @Query(
    "SELECT * FROM albums WHERE id = :bookId AND libraryId IN " +
      "(SELECT musicFolderId FROM libraries WHERE role = :role)",
  )
  suspend fun findBookAlbum(bookId: String, role: LibraryRole = LibraryRole.AUDIOBOOKS): AlbumEntity?
}
```

`core/database/src/main/kotlin/app/muplay/database/AudiobookRepository.kt`:

```kotlin
package app.muplay.database

import app.muplay.database.dao.AudiobookDao
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.BookSettingsEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.ResumePoint
import app.muplay.model.Song
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Everything the application knows about audiobooks, in one place.
 *
 * **A book is an album in a library the user tagged `AUDIOBOOKS`**, and its id is the album id.
 * That definition lives here and nowhere else; every other component asks rather than re-deriving,
 * because a second definition is how "is this an audiobook" ends up answered two different ways in
 * two different screens.
 *
 * A loose file with no album is its own book for settings and for position (see [bookIdOf]) but has
 * no shelf row, because the shelf is a list of albums. That is a stated limitation, not an
 * oversight; inventing synthetic albums for it would put a fake book on a real shelf.
 */
@Singleton
class AudiobookRepository @Inject constructor(
  private val audiobookDao: AudiobookDao,
  private val mediaProgressDao: MediaProgressDao,
  private val bookSettingsDao: BookSettingsDao,
  private val clock: Clock,
) {

  fun bookshelf(): Flow<List<BookSummary>> = combine(
    audiobookDao.observeBookAlbums(),
    audiobookDao.observeSongsInRole(),
    mediaProgressDao.observeAll(),
  ) { albums, songs, progress ->
    val filesByBook = songs.groupBy { it.albumId ?: it.id }
    val progressById = progress.associateBy { it.mediaId }
    BookSummaries.order(
      albums.map { album -> BookSummaries.summarise(album, filesByBook[album.id].orEmpty(), progressById) },
    )
  }

  suspend fun book(bookId: String): BookSummary? {
    val files = audiobookDao.files(bookId)
    if (files.isEmpty()) return null
    val album = audiobookDao.findBookAlbum(bookId) ?: return null
    return BookSummaries.summarise(album, files, progressFor(files.map { it.id }))
  }

  /** The book's files, in play order, as domain models. */
  suspend fun files(bookId: String): List<Song> =
    BookSummaries.playOrder(audiobookDao.files(bookId)).map(MirrorMapper::song)

  suspend fun resumePoint(bookId: String): ResumePoint? {
    val files = audiobookDao.files(bookId)
    return BookSummaries.resumePoint(files, progressFor(files.map { it.id }))
  }

  suspend fun settings(bookId: String): BookSettings = bookSettingsDao.find(bookId).toSettings(bookId)

  fun observeSettings(bookId: String): Flow<BookSettings> =
    bookSettingsDao.observe(bookId).map { it.toSettings(bookId) }

  /** Read-modify-write: a setter that writes a whole fresh row turns off the other setting. */
  suspend fun setSpeed(bookId: String, speed: Float) {
    val existing = bookSettingsDao.find(bookId)
    bookSettingsDao.upsert(
      BookSettingsEntity(
        bookId = bookId,
        speed = BookSettings.clampSpeed(speed),
        skipSilence = existing?.skipSilence ?: false,
      ),
    )
  }

  suspend fun setSkipSilence(bookId: String, enabled: Boolean) {
    val existing = bookSettingsDao.find(bookId)
    bookSettingsDao.upsert(
      BookSettingsEntity(
        bookId = bookId,
        speed = BookSettings.clampSpeed(existing?.speed ?: BookSettings.DEFAULT_SPEED),
        skipSilence = enabled,
      ),
    )
  }

  /**
   * "Start from the beginning", expressed as **removing** progress rather than as setting a
   * position to zero.
   *
   * The seam (Plan 3 Task 8) makes a caller-chosen position unreachable, correctly, so this is the
   * only honest way to say it — and it is the better state anyway: there is no position, rather
   * than a position that happens to be zero next to a `lastPlayedAt` claiming the listener was
   * there. It also un-finishes a finished book, which is the behaviour Plan 3 deferred to "the
   * plan that has a UI to express it".
   */
  suspend fun restart(bookId: String) =
    mediaProgressDao.clear(audiobookDao.files(bookId).map { it.id })

  suspend fun markFinished(bookId: String) {
    val files = BookSummaries.playOrder(audiobookDao.files(bookId))
    val existing = progressFor(files.map { it.id })
    for (file in files) {
      val row = existing[file.id]
      mediaProgressDao.upsert(
        MediaProgressEntity(
          mediaId = file.id,
          positionMs = file.durationSeconds * 1_000L,
          isFinished = true,
          lastPlayedAtEpochMs = clock.millis(),
          // The columns this plan does not own, preserved -- the same discipline Plan 3's
          // ProgressWriter applies, for the same reason.
          speed = row?.speed ?: 1.0f,
          skipSilence = row?.skipSilence ?: false,
          gainDb = row?.gainDb ?: 0.0f,
        ),
      )
    }
  }

  /**
   * Every audiobook file, mapped to its book. The input to `AudiobookSnapshot` (Task 6), which is
   * what makes "only books resume" structural rather than conventional: a media id absent from this
   * map is not an audiobook, and the resume policy has nothing to answer with.
   */
  fun observeAudiobookItems(): Flow<Map<String, String>> =
    audiobookDao.observeItems().map { rows -> rows.associate { it.mediaId to (it.albumId ?: it.mediaId) } }

  private suspend fun progressFor(mediaIds: List<String>): Map<String, MediaProgressEntity> =
    mediaProgressDao.findIn(mediaIds).associateBy { it.mediaId }

  private fun BookSettingsEntity?.toSettings(bookId: String): BookSettings = when (this) {
    null -> BookSettings.default(bookId)
    else -> BookSettings(bookId, BookSettings.clampSpeed(speed), skipSilence)
  }

  companion object {
    /**
     * A book is an album; a file with no album is its own book.
     *
     * Both overloads exist because callers hold whichever of the two types is nearer to hand, and
     * the alternative — everyone writing `song.albumId ?: song.id` — is exactly how one screen ends
     * up disagreeing with another about what a book is.
     */
    fun bookIdOf(song: Song): String = song.albumId ?: song.id

    fun bookIdOf(song: app.muplay.database.entity.SongEntity): String = song.albumId ?: song.id
  }
}
```

> `findBookAlbum` carries the same `role` guard as every other query in `AudiobookDao`. Without it,
> `book(bookId)` would happily summarise a **music** album as a book the moment a caller passed a
> music album id — and `AudiobookRepositoryTest.theShelfHoldsBooksAndOnlyBooks` would not notice,
> because it goes through `bookshelf()` rather than through `book()`. Add an assertion for it:
> `assertThat(repository.book("record")).isNull()`.

`MuPlayDatabase` gains `abstract fun audiobookDao(): AudiobookDao`; `DataModule` gains
`@Provides fun provideAudiobookDao(db: MuPlayDatabase): AudiobookDao = db.audiobookDao()`.
**No entity is added, so the schema version does not move** — `AudiobookDao` only queries tables
Task 2 and Plan 2 already created.

- [ ] **Step 6: Run the repository suite**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests '*AudiobookRepositoryTest*'`
Expected: PASS, 14/14.

- [ ] **Step 7: Prove it can fail**

One mutation at a time, reverted after each:

1. In `BookSummaries.currentFile`, use `maxByOrNull { it.trackNumber ?: 0 }` (the furthest file
   rather than the most recent). Expect `the current file is the most recently heard one, not the
   furthest one` and `theResumePointNamesTheFileTheListenerWasIn` to fail.
2. In `BookSummaries.summarise`, drop the `offsetMs` term. Expect `a book's position is the files
   before the current one plus the position inside it` to fail on its second and third
   observations — and note that every single-file assertion stays green.
3. In `BookSummaries.order`, sort by `lastPlayedAtEpochMs` alone. Expect
   `the shelf is continue-listening first…` and `a finished book drops below an unstarted one…` to
   fail.
4. In `BookSummaries.playOrder`, replace `it.trackNumber ?: Int.MAX_VALUE` with
   `it.trackNumber ?: 0`. Expect `a file with no track number sorts after every numbered one` to
   fail.
5. In `AudiobookRepository.setSpeed`, write `skipSilence = false` instead of preserving. Expect
   `settingTheSpeedDoesNotTurnSilenceSkippingOff` to fail. **This is Plan 3's trap, one table
   over.**
6. In `AudiobookDao.observeItems`, drop the `libraryId IN (…)` clause. Expect
   `theAudiobookItemMapNamesEveryBookFileAndNoMusicFile` to fail on `track-1`. **This is the one
   that would make music resume**, so watch it go red personally.
7. In `AudiobookRepository.restart`, clear `mediaProgressDao.clear(listOf(bookId))` (the book id
   rather than the file ids). Expect `restartingABookClearsItsProgressAndNobodyElses` to fail on
   the multi-file book.

- [ ] **Step 8: Record the probes, re-measure, commit**

Mutations 1–4 are JVM-side and belong in `ci/mutation-probes.sh` (add `BookSummaries.kt` to
`revert()`'s file list, and make sure `run_suite()` runs `:core:database:testDebugUnitTest`).
Mutations 5–7 are device-side; record them in the task report.

`BookSummaries` is a JVM floor (`requiresInstrumentedData = false`); `AudiobookRepository` and
`AudiobookDao`'s generated implementation are instrumented — and Room's `*_Impl` is already
excluded, so what is gated is the repository's own combine/map/read-modify-write branches.

```bash
git add core/model core/database build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(database): what a book is, the shelf order, and per-book settings"
```

---

## Task 5: `SmartRewind` — the band table, its boundaries, and the clamp

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/SmartRewind.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/SmartRewindTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` (§5's rewind sentence — see
  Task 10, which does the whole spec edit in one commit; this task only records what to write)

**Interfaces:**
- Consumes: nothing. This is a pure function over two `Long`s, and that is the point.
- Produces:
  - `object SmartRewind` with
    `fun rewindMs(awayMs: Long): Long`,
    `fun resumePositionMs(storedPositionMs: Long, awayMs: Long): Long`,
    and the constants `AWAY_NONE_MS = 15_000L`, `AWAY_SHORT_MS = 60_000L`,
    `AWAY_MEDIUM_MS = 3_600_000L`, `AWAY_LONG_MS = 86_400_000L`,
    `REWIND_NONE_MS = 0L`, `REWIND_SHORT_MS = 2_000L`, `REWIND_MEDIUM_MS = 5_000L`,
    `REWIND_LONG_MS = 10_000L`, `REWIND_MAX_MS = 20_000L`

### The spec says one sentence, and one sentence is not a specification

Spec §5, in full: *"**Smart rewind** on resume, scaled to how long the book was paused."* That
names the idea and specifies nothing that can be tested — "scaled" admits a constant, a linear
ramp, and doing nothing at all. **Task 10 writes the table below into the spec**, because a
behaviour every resume in the app goes through cannot live only in a source file.

The reasoning behind the numbers, so they are a decision rather than a guess:

| Away for | Rewind | Why |
|---|---|---|
| under **15 s** | **0 s** | You paused to say something. You know exactly where you are; rewinding is an insult. |
| 15 s – **1 min** | **2 s** | Long enough to lose the last clause, short enough that a sentence is still in your head. |
| 1 min – **1 h** | **5 s** | You did something else. A sentence, roughly. |
| 1 h – **1 day** | **10 s** | You came back later in the day. Enough to re-enter a paragraph. |
| over **1 day** | **20 s** | You came back another day. Enough to remember who is talking. |

Five bands, five **distinct** values, and one of them is zero. That last point matters for the
tests rather than the design: **a smart-rewind case whose expected rewind is zero cannot fail** —
`rewindMs` returning 0 for everything satisfies it. So the 0 band is asserted exactly once, on
purpose, and every other band carries a value no other band carries, so a constant satisfies at
most one assertion in the file.

Boundaries are **half-open on the low side**: a band's threshold is the first value of the *next*
band. Every threshold is asserted at `threshold - 1` and at `threshold`, one millisecond apart with
two different answers, which is what turns "the table is right" into an assertion about the
comparison operator rather than about one number.

- [ ] **Step 1: Write the failing test**

`core/media/src/test/kotlin/app/muplay/media/SmartRewindTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Spec section 5's *"smart rewind ... scaled to how long the book was paused"*, made testable.
 *
 * Two rules shape every assertion here:
 *
 * 1. **A case whose expected rewind is zero cannot fail.** `rewindMs` returning 0 for every input
 *    satisfies it. The zero band is therefore asserted exactly once, and every other band carries
 *    a value no other band carries, so a constant satisfies at most one assertion in this file.
 * 2. **A threshold is asserted on both sides, one millisecond apart.** That is what makes the test
 *    about the comparison rather than about one number — a `<` silently becoming `<=` moves
 *    exactly one input's answer, and nothing else in a suite would notice.
 */
class SmartRewindTest {

  @Test
  fun `a pause you barely noticed rewinds nothing`() {
    // The one zero assertion in this file. It is here because 0 is the specified answer for this
    // band, not because zero is a safe default.
    assertThat(SmartRewind.rewindMs(0L)).isZero
    assertThat(SmartRewind.rewindMs(14_999L)).isZero
  }

  @Test
  fun `each band rewinds its own distinct amount`() {
    // The whole table in one assertion, over five inputs that are each well inside their band.
    // Five different answers: no constant, no `index * k`, no linear ramp satisfies this list.
    val awayTimes = listOf(
      5_000L,          // under 15 s
      30_000L,         // 15 s .. 1 min
      600_000L,        // 1 min .. 1 h
      7_200_000L,      // 1 h .. 1 day
      172_800_000L,    // over 1 day
    )

    assertThat(awayTimes.map { SmartRewind.rewindMs(it) })
      .containsExactly(0L, 2_000L, 5_000L, 10_000L, 20_000L)
  }

  @Test
  fun `the fifteen second threshold is where rewinding starts`() {
    assertThat(SmartRewind.rewindMs(14_999L)).isEqualTo(0L)
    assertThat(SmartRewind.rewindMs(15_000L)).isEqualTo(2_000L)
  }

  @Test
  fun `the one minute threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(59_999L)).isEqualTo(2_000L)
    assertThat(SmartRewind.rewindMs(60_000L)).isEqualTo(5_000L)
  }

  @Test
  fun `the one hour threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(3_599_999L)).isEqualTo(5_000L)
    assertThat(SmartRewind.rewindMs(3_600_000L)).isEqualTo(10_000L)
  }

  @Test
  fun `the one day threshold moves the answer`() {
    assertThat(SmartRewind.rewindMs(86_399_999L)).isEqualTo(10_000L)
    assertThat(SmartRewind.rewindMs(86_400_000L)).isEqualTo(20_000L)
  }

  @Test
  fun `a month away rewinds the same as a day away and no more`() {
    // The top band is open-ended, and an unbounded scale would rewind a listener to the start of
    // the chapter after a holiday. Two very different inputs, one answer -- which is the *only*
    // place in this file where two inputs sharing an answer is the assertion.
    assertThat(SmartRewind.rewindMs(30L * 86_400_000L)).isEqualTo(SmartRewind.REWIND_MAX_MS)
    assertThat(SmartRewind.rewindMs(365L * 86_400_000L)).isEqualTo(SmartRewind.REWIND_MAX_MS)
  }

  @Test
  fun `a clock that went backwards rewinds nothing rather than something enormous`() {
    // `awayMs` is `clock.millis() - lastPlayedAtEpochMs`, and a device whose clock moved backwards
    // -- NTP correction, a manual change, a timezone-confused restore -- produces a negative. A
    // negative reaching a `when` chain built from `<` comparisons lands in the *first* band by
    // accident rather than by decision; this makes it a decision.
    assertThat(SmartRewind.rewindMs(-1L)).isZero
    assertThat(SmartRewind.rewindMs(Long.MIN_VALUE)).isZero
  }

  @Test
  fun `the resume position is the stored position minus the band's rewind`() {
    // Two bands, two results, from the same stored position. A `resumePositionMs` that ignored
    // `awayMs` would pass either one alone.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 60_000L, awayMs = 30_000L))
      .isEqualTo(58_000L)
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 60_000L, awayMs = 600_000L))
      .isEqualTo(55_000L)
  }

  @Test
  fun `the resume position varies with the stored position too`() {
    // The other argument. Holding `awayMs` constant and moving the stored position is what stops
    // `resumePositionMs` from being a function of one input.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 10_000L, awayMs = 600_000L))
      .isEqualTo(5_000L)
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 90_000L, awayMs = 600_000L))
      .isEqualTo(85_000L)
  }

  @Test
  fun `a rewind never goes past the start of the file`() {
    // Two seconds into a chapter, gone for a week. A negative position reaches `seekTo`, and
    // ExoPlayer's behaviour for one is not something a listener should discover.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 2_000L, awayMs = 30L * 86_400_000L))
      .isZero
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = 0L, awayMs = 30_000L)).isZero
  }

  @Test
  fun `a negative stored position is treated as the start`() {
    // Not reachable from `ProgressWriter`, which coerces at write time -- but it is reachable from
    // a hand-edited database, and this function is the last thing between that row and `seekTo`.
    assertThat(SmartRewind.resumePositionMs(storedPositionMs = -5_000L, awayMs = 0L)).isZero
  }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*SmartRewindTest*'`
Expected: FAIL, `Unresolved reference: SmartRewind`.

`core/media/src/main/kotlin/app/muplay/media/SmartRewind.kt`:

```kotlin
package app.muplay.media

/**
 * How far back a book goes when you come back to it.
 *
 * Spec section 5 asks for *"smart rewind on resume, scaled to how long the book was paused"* and
 * says nothing more, which admits a constant, a linear ramp and doing nothing. The table below is
 * the decision; Task 10 writes it into the spec, because a behaviour every resume in the
 * application passes through cannot live only in a source file.
 *
 * | Away for | Rewind | Why |
 * |---|---|---|
 * | under 15 s | 0 s | You paused to say something. You know where you are. |
 * | 15 s - 1 min | 2 s | Enough to lose the last clause. |
 * | 1 min - 1 h | 5 s | You did something else. A sentence, roughly. |
 * | 1 h - 1 day | 10 s | Later the same day. Enough to re-enter a paragraph. |
 * | over 1 day | 20 s | Another day. Enough to remember who is talking. |
 *
 * The top band is deliberately **bounded**. An unbounded scale rewinds a listener into the
 * previous chapter after a holiday, which loses more than it recovers.
 *
 * (The idea is Voice's, and Voice is GPL: none of it was read. The table above is derived from
 * spec section 5's sentence plus the reasoning in the last column.)
 */
object SmartRewind {

  const val AWAY_NONE_MS = 15_000L
  const val AWAY_SHORT_MS = 60_000L
  const val AWAY_MEDIUM_MS = 3_600_000L
  const val AWAY_LONG_MS = 86_400_000L

  const val REWIND_NONE_MS = 0L
  const val REWIND_SHORT_MS = 2_000L
  const val REWIND_MEDIUM_MS = 5_000L
  const val REWIND_LONG_MS = 10_000L
  const val REWIND_MAX_MS = 20_000L

  fun rewindMs(awayMs: Long): Long = when {
    // A device whose clock moved backwards -- NTP correction, a manual change, a restore -- makes
    // `clock.millis() - lastPlayedAtEpochMs` negative. Without this arm a negative lands in the
    // first band by accident rather than by decision, which is the same answer for the wrong
    // reason and stops being the same answer the moment the table is reordered.
    awayMs < 0L -> REWIND_NONE_MS
    awayMs < AWAY_NONE_MS -> REWIND_NONE_MS
    awayMs < AWAY_SHORT_MS -> REWIND_SHORT_MS
    awayMs < AWAY_MEDIUM_MS -> REWIND_MEDIUM_MS
    awayMs < AWAY_LONG_MS -> REWIND_LONG_MS
    else -> REWIND_MAX_MS
  }

  /** Never negative: a negative position reaches `seekTo`, and that is not a listener's problem. */
  fun resumePositionMs(storedPositionMs: Long, awayMs: Long): Long =
    (storedPositionMs - rewindMs(awayMs)).coerceAtLeast(0L)
}
```

- [ ] **Step 3: Run it green**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*SmartRewindTest*'`
Expected: PASS, 12/12.

- [ ] **Step 4: Prove every band can fail**

This is a table, so mutate it like one. Reverted after each:

1. `rewindMs` returns `REWIND_MEDIUM_MS` unconditionally. Expect
   `each band rewinds its own distinct amount` and every threshold test to fail. Count them:
   **eight** tests should redden. If fewer do, a band is asserted at only one value.
2. Change `awayMs < AWAY_SHORT_MS` to `awayMs <= AWAY_SHORT_MS`. Expect exactly
   `the one minute threshold moves the answer` to fail. **One test, one boundary** — this is the
   mutation that a suite without both-sides assertions cannot see.
3. Remove the `awayMs < 0L` arm. Expect `a clock that went backwards…` to fail on
   `Long.MIN_VALUE`… **and check whether it fails on `-1L` too.** It will not, because `-1 < 15_000`
   lands in the first band anyway. Record that: the `-1` observation is documentation, the
   `Long.MIN_VALUE` one is the gate. An honest note beats pretending both discriminate.
4. Remove `.coerceAtLeast(0L)` from `resumePositionMs`. Expect `a rewind never goes past the start
   of the file` and `a negative stored position is treated as the start` to fail.
5. Swap `REWIND_LONG_MS` and `REWIND_MAX_MS`'s values. Expect
   `each band rewinds its own distinct amount`, `the one day threshold moves the answer` and
   `a month away rewinds the same as a day away and no more` to fail — the last one because
   `REWIND_MAX_MS` is asserted by name *and* by value elsewhere, so renaming cannot hide it.

- [ ] **Step 5: Record the probes, re-measure, commit**

All five mutations are JVM-side; add them to `ci/mutation-probes.sh`, with `SmartRewind.kt` in
`revert()`'s file list. `SmartRewind`'s branch count is exactly the table, so its floor is a JVM
floor at or above 0.90 with `requiresInstrumentedData = false`.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): the smart rewind table, and its boundaries"
```

---

## Task 6: `AudiobookResumePolicy` — the swap Plan 3 designed for, and local-only made checkable

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/AudiobookSnapshot.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/AudiobookResumePolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ResumptionQueue.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/AudiobookResumePolicyTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/AudiobookSnapshotTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/AudiobookResumeTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ResumptionQueueTest.kt`
- Test: `core/network/src/test/kotlin/app/muplay/network/LocalOnlyProgressTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes:
  - **`ResumeTarget(startIndex, startPositionMs)`, `fun interface ResumePolicy { fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget }`, `NeverResume`, `MuPlayer(player, resumePolicy)`** — **Plan 3 Task 8.** Read that task before this one.
  - `AudiobookRepository.observeAudiobookItems()`, `.files(bookId)`, `.resumePoint(bookId)`,
    `.bookshelf()`, `.settings(bookId)` and `AudiobookRepository.bookIdOf` — Task 4.
  - `MediaProgressDao.observeAll()` (Task 2), `BookSettingsDao.observeAll()` (Task 2).
  - `SmartRewind.resumePositionMs(storedPositionMs, awayMs)` — Task 5.
  - `QueueRepository.mediaItems(queue)`, `PlaybackQueue.of(songs, startIndex)` — Plan 3 Tasks 4, 6.
  - `MuPlayerFactory`, `PlayerHarness`, `RealTrackBytes` — Plan 3 Tasks 5, 2, 3.
  - `java.time.Clock` from `MediaModule` — Plan 3 Task 8.
- Produces:
  - `data class AudiobookItem(val mediaId: String, val bookId: String, val positionMs: Long,
    val lastPlayedAtEpochMs: Long, val isFinished: Boolean, val speed: Float, val skipSilence: Boolean)`
  - `fun interface AudiobookItemSource { fun itemFor(mediaId: String): AudiobookItem? }`
  - `class AudiobookSnapshot @Inject constructor(audiobookRepository, mediaProgressDao, bookSettingsDao)`
    implementing `AudiobookItemSource`, with `fun start(scope: CoroutineScope)`,
    `suspend fun refresh()`, `suspend fun awaitLoaded()`, `val isLoaded: Boolean`,
    `fun items(): Map<String, AudiobookItem>`, `fun stop()`
  - `class AudiobookResumePolicy(private val source: AudiobookItemSource, private val clock: Clock) : ResumePolicy`
  - `class ResumptionQueue @Inject constructor(audiobookRepository, queueRepository)` with
    `suspend fun mostRecent(): PlaybackQueue?`
  - `MediaModule.provideResumePolicy` now returns an `AudiobookResumePolicy`

### This is the task the project exists for

Spec §1: *"**Real audiobook resume.** Every book remembers its own exact position and keeps it
across an intervening music session."* Plan 3 built the seam and deliberately installed a policy
that resumes nothing. **This task swaps that one binding.**

Plan 3 Task 8's design holds, with one correction to its stated *intent* that must be read out
loud, because a later reader will otherwise think this task under-delivered:

> Plan 3 wrote that a policy *"may still override [the index], which is how the audiobook plan
> resumes a book at chapter 14."* **This plan does not override the index, and that is the correct
> reading of the seam rather than a shortfall.**
>
> `resolve(mediaIds, requestedIndex)` cannot tell *"play this book"* from *"play chapter 5 of this
> book"*. Both arrive as a list of ids and an index, and index 0 is simultaneously the default for
> the first and a legitimate value for *"play chapter 1 from the top"*. A policy that overrode index
> 0 with "wherever you were" would make tapping chapter 1 jump to chapter 14 — a worse bug than the
> one being fixed, and unfixable from inside the signature.
>
> So: **the caller decides the index, the policy decides the position.** The caller is the only
> party that knows the intent, and `BookPlaybackLauncher` (Task 9) is where that intent lives.
> The seam's actual guarantee — *"no code path can set a wrong position"* — is untouched, `MuPlayer`
> is untouched, and `ResumePolicy` is untouched. Task 10 corrects spec §3's wording.

### Why the policy is never allowed to touch Room

Plan 3's `ResumePolicy` documentation is explicit: *"Implementations must answer without blocking.
`MuPlayer` calls this from `setMediaItems`, which runs on the player's application thread; a Room
query there would jank the UI. The intended mechanism for the audiobook plan is an in-memory
snapshot of `media_progress` kept current by a Flow collector, not a blocking read."*

That is exactly what `AudiobookSnapshot` is. It carries two traps worth naming before they are
written:

1. **A cold snapshot silently resumes nothing.** If the first `setMediaItems` arrives before the
   collector's first emission, every book starts at zero — the precise defect this application
   exists to fix, arriving as a race that reproduces once a month on a slow device. Two defences,
   both needed: `refresh()` is a one-shot suspend read that the launcher calls **before** building
   a queue, and `awaitLoaded()` blocks a caller that genuinely has to wait. `AudiobookSnapshotTest`
   asserts a cold snapshot resolves correctly after `refresh()` alone, with no collector running.
2. **A snapshot that knows about every item makes music resume.** The map is restricted to media
   ids in a library the user tagged `AUDIOBOOKS`. A media id absent from it has no entry, and the
   policy has nothing to answer with, so `ResumeTarget(requestedIndex, 0)` is *structural* for
   music rather than a branch someone could delete. Spec §3: *"Only books get resume treatment.
   Music restarts from 0 — progress is still recorded, just not honoured on prepare."*

### Local-only, made checkable rather than promised

Spec §2, §4 and §11 all say book positions never leave the device, and the global constraints
repeat it. Every statement of it so far has been prose. **Rule 5 says a gate reporting the absence
of a problem must be provably incapable of staying quiet**, so this task adds one:
`LocalOnlyProgressTest` asserts `SubsonicSource`'s method set and `SubsonicApi`'s declared endpoint
paths **exactly**, so adding `createBookmark`, `savePlayQueue`, `savePlayQueueByIndex` or
`scrobble` fails the build with a message naming the constraint. Task 10 adds the runtime half — a
request counter over a real resume journey, with a positive control.

- [ ] **Step 1: Write the failing policy test**

`core/media/src/test/kotlin/app/muplay/media/AudiobookResumePolicyTest.kt`:

```kotlin
package app.muplay.media

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A **JVM** test, because the policy takes media ids and an index and asks an
 * [AudiobookItemSource] — no `MediaItem`, no `Uri`, no Room. That is why the source is a narrow
 * `fun interface` rather than `AudiobookSnapshot` itself: the decision is gated in Tier 1 where a
 * mutation costs seconds, and the Room plumbing is gated separately.
 *
 * Everything here uses **two books and at least two positions**. With one book, "resume book B at
 * P" and "resume at the only position there is" are the same program.
 */
class AudiobookResumePolicyTest {

  private val now = 1_700_000_000_000L
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

  private fun item(
    mediaId: String,
    bookId: String,
    positionMs: Long,
    agoMs: Long = 0L,
    finished: Boolean = false,
  ) = AudiobookItem(
    mediaId = mediaId,
    bookId = bookId,
    positionMs = positionMs,
    lastPlayedAtEpochMs = now - agoMs,
    isFinished = finished,
    speed = 1.0f,
    skipSilence = false,
  )

  private fun policy(vararg items: AudiobookItem): AudiobookResumePolicy {
    val byId = items.associateBy { it.mediaId }
    // A hand-written source, not a mock: this project bans mock frameworks, and a map is a
    // complete implementation of a one-method interface.
    return AudiobookResumePolicy({ mediaId -> byId[mediaId] }, clock)
  }

  @Test
  fun `a book resumes at the position stored for the item the caller asked for`() {
    // Two books, two positions, one policy. A policy returning "the stored position" without
    // looking at which item would pass either observation alone and fail this pair.
    val subject = policy(
      item("book-a-1", "book-a", positionMs = 12_345L),
      item("book-b-1", "book-b", positionMs = 60_000L),
    )

    assertThat(subject.resolve(listOf("book-a-1"), 0).startPositionMs).isEqualTo(12_345L)
    assertThat(subject.resolve(listOf("book-b-1"), 0).startPositionMs).isEqualTo(60_000L)
  }

  @Test
  fun `music is not resumed, however much progress it has`() {
    // Spec section 3: "Music restarts from 0 -- progress is still recorded, just not honoured on
    // prepare." Structural rather than conditional: a music item has no entry in the source at
    // all, so there is nothing to honour.
    val subject = policy(item("book-a-1", "book-a", positionMs = 12_345L))

    assertThat(subject.resolve(listOf("a-song"), 0).startPositionMs).isZero
    // ...and the mixed case, which is what a shuffle queue looks like: one known id and one not.
    assertThat(subject.resolve(listOf("a-song", "book-a-1"), 0).startPositionMs).isZero
  }

  @Test
  fun `the caller's index is honoured, and it selects which position is used`() {
    // The seam correction, asserted. `BookPlaybackLauncher` decides the index because it knows
    // whether the listener said "resume this book" or "play chapter 3"; the policy answers the
    // position of whatever the caller chose.
    val subject = policy(
      item("p1", "multi", positionMs = 1_000L),
      item("p2", "multi", positionMs = 3_500L),
      item("p3", "multi", positionMs = 500L),
    )
    val queue = listOf("p1", "p2", "p3")

    assertThat(subject.resolve(queue, 0)).isEqualTo(ResumeTarget(0, 1_000L))
    assertThat(subject.resolve(queue, 1)).isEqualTo(ResumeTarget(1, 3_500L))
    assertThat(subject.resolve(queue, 2)).isEqualTo(ResumeTarget(2, 500L))
  }

  @Test
  fun `the smart rewind is applied, and it depends on how long the book was away`() {
    // Same stored position, two away times, two answers. Without the second observation, "the
    // rewind is applied" and "the rewind is 5000" are the same claim.
    val recent = policy(item("b1", "b", positionMs = 60_000L, agoMs = 5_000L))
    val awhile = policy(item("b1", "b", positionMs = 60_000L, agoMs = 600_000L))
    val ages = policy(item("b1", "b", positionMs = 60_000L, agoMs = 30L * 86_400_000L))

    assertThat(recent.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(60_000L)
    assertThat(awhile.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(55_000L)
    assertThat(ages.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(40_000L)
  }

  @Test
  fun `a finished item starts again from the beginning`() {
    // Otherwise pressing play on a book you finished drops you two seconds before the end, which
    // reads as "the resume is broken" rather than as "you finished this".
    val subject = policy(item("b1", "b", positionMs = 20_000L, finished = true))

    assertThat(subject.resolve(listOf("b1"), 0).startPositionMs).isZero
    // ...and the control: the same item unfinished does resume.
    assertThat(policy(item("b1", "b", positionMs = 20_000L)).resolve(listOf("b1"), 0).startPositionMs)
      .isEqualTo(20_000L)
  }

  @Test
  fun `an audiobook item nobody has played yet starts at zero`() {
    val subject = policy(item("b1", "b", positionMs = 0L))

    assertThat(subject.resolve(listOf("b1"), 0)).isEqualTo(ResumeTarget(0, 0L))
  }

  @Test
  fun `an index outside the queue does not throw`() {
    // `MediaController`s from other processes -- a car, a watch, a headset -- can and do send
    // stale indices. An exception thrown from inside `setMediaItems` takes the whole session down.
    val subject = policy(item("b1", "b", positionMs = 12_345L))

    assertThat(subject.resolve(listOf("b1"), requestedIndex = 7)).isEqualTo(ResumeTarget(0, 12_345L))
    assertThat(subject.resolve(listOf("b1"), requestedIndex = -3)).isEqualTo(ResumeTarget(0, 12_345L))
    assertThat(subject.resolve(emptyList(), requestedIndex = 0)).isEqualTo(ResumeTarget(0, 0L))
  }

  @Test
  fun `a clock that moved backwards does not rewind wildly`() {
    // `agoMs` negative -- the row claims to have been written in the future. `SmartRewind` handles
    // it, and this asserts the policy actually routes through `SmartRewind` rather than
    // subtracting something of its own.
    val subject = policy(item("b1", "b", positionMs = 60_000L, agoMs = -86_400_000L))

    assertThat(subject.resolve(listOf("b1"), 0).startPositionMs).isEqualTo(60_000L)
  }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement the policy and the snapshot**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*AudiobookResumePolicyTest*'`
Expected: FAIL, `Unresolved reference: AudiobookResumePolicy`.

`core/media/src/main/kotlin/app/muplay/media/AudiobookSnapshot.kt`:

```kotlin
package app.muplay.media

import app.muplay.database.AudiobookRepository
import app.muplay.database.dao.BookSettingsDao
import app.muplay.database.dao.MediaProgressDao
import app.muplay.model.BookSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One audiobook file, and everything the player needs to know about it, in memory.
 *
 * `speed` and `skipSilence` come from `book_settings` — the **book's** grain — and are carried on
 * the item because the player only ever knows a media id. `media_progress.speed` is deliberately
 * not consulted; see `BookSettings`'s own documentation for why that column is the wrong grain.
 */
data class AudiobookItem(
  val mediaId: String,
  val bookId: String,
  val positionMs: Long,
  val lastPlayedAtEpochMs: Long,
  val isFinished: Boolean,
  val speed: Float,
  val skipSilence: Boolean,
)

/**
 * The one question the resume policy and the speed controller ask.
 *
 * A `fun interface` rather than [AudiobookSnapshot] itself, so both of those are pure enough to be
 * gated in Tier 1 with a `Map` standing in — which is a complete implementation of a one-method
 * interface, not a stand-in for one.
 */
fun interface AudiobookItemSource {
  /** `null` means "not an audiobook", which is how music restarts from zero structurally. */
  fun itemFor(mediaId: String): AudiobookItem?
}

/**
 * An in-memory view of every audiobook file's position and settings, kept current by a Flow
 * collector.
 *
 * It exists because Plan 3's `ResumePolicy` contract forbids blocking: `MuPlayer` calls `resolve`
 * from `setMediaItems`, on the player's application thread, and a Room query there janks the UI.
 *
 * **Two traps, both of which produce the exact defect this application exists to fix:**
 *
 * 1. *A cold snapshot resumes nothing.* If the first `setMediaItems` beats the collector's first
 *    emission, every book starts at zero — reproducing once a month on a slow device and never in
 *    a test that happens to warm it up first. [refresh] is a one-shot read the launcher calls
 *    **before** building a queue; [awaitLoaded] is for a caller that genuinely has to wait.
 * 2. *A snapshot that knows about everything makes music resume.* The map holds only media ids in
 *    a library the user tagged `AUDIOBOOKS`, so a music id has no entry and the policy has nothing
 *    to honour. Spec section 3's "music restarts from 0" is then structural rather than a branch
 *    somebody can delete.
 */
@Singleton
class AudiobookSnapshot @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val mediaProgressDao: MediaProgressDao,
  private val bookSettingsDao: BookSettingsDao,
) : AudiobookItemSource {

  @Volatile
  private var items: Map<String, AudiobookItem> = emptyMap()

  private var collector: Job? = null
  private val loaded = CompletableDeferred<Unit>()

  val isLoaded: Boolean get() = loaded.isCompleted

  fun start(scope: CoroutineScope) {
    if (collector != null) return
    collector = scope.launch {
      combine(
        audiobookRepository.observeAudiobookItems(),
        mediaProgressDao.observeAll(),
        bookSettingsDao.observeAll(),
      ) { bookIds, progress, settings -> build(bookIds, progress, settings) }
        .collect { publish(it) }
    }
  }

  fun stop() {
    collector?.cancel()
    collector = null
  }

  /**
   * A one-shot read, straight from Room. Called on the play path, so the answer a listener gets is
   * never the answer the collector had a second ago.
   */
  suspend fun refresh() {
    publish(
      build(
        audiobookRepository.observeAudiobookItems().first(),
        mediaProgressDao.observeAll().first(),
        bookSettingsDao.observeAll().first(),
      ),
    )
  }

  suspend fun awaitLoaded() = loaded.await()

  fun items(): Map<String, AudiobookItem> = items

  override fun itemFor(mediaId: String): AudiobookItem? = items[mediaId]

  private fun publish(next: Map<String, AudiobookItem>) {
    items = next
    loaded.complete(Unit)
  }

  private fun build(
    bookIdByMediaId: Map<String, String>,
    progress: List<app.muplay.database.entity.MediaProgressEntity>,
    settings: List<app.muplay.database.entity.BookSettingsEntity>,
  ): Map<String, AudiobookItem> {
    val progressById = progress.associateBy { it.mediaId }
    val settingsByBook = settings.associateBy { it.bookId }
    // Keyed off the audiobook item map, never off `media_progress`: a music row must not become an
    // entry here, and a book file with no progress row still needs its settings.
    return bookIdByMediaId.mapValues { (mediaId, bookId) ->
      val row = progressById[mediaId]
      val bookSettings = settingsByBook[bookId]
      AudiobookItem(
        mediaId = mediaId,
        bookId = bookId,
        positionMs = row?.positionMs ?: 0L,
        lastPlayedAtEpochMs = row?.lastPlayedAtEpochMs ?: 0L,
        isFinished = row?.isFinished ?: false,
        speed = BookSettings.clampSpeed(bookSettings?.speed ?: BookSettings.DEFAULT_SPEED),
        skipSilence = bookSettings?.skipSilence ?: false,
      )
    }
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/AudiobookResumePolicy.kt`:

```kotlin
package app.muplay.media

import java.time.Clock

/**
 * The policy that replaces `NeverResume`, and the reason this project exists.
 *
 * Spec section 1: *"Every book remembers its own exact position and keeps it across an intervening
 * music session."* The seam Plan 3 built (`MuPlayer`, all six `setMediaItem(s)` overloads) makes
 * this the **only** thing in the application permitted to choose a playback position; this class
 * makes that permission mean something.
 *
 * **The index is not overridden.** Plan 3 anticipated that it might be — "how the audiobook plan
 * resumes a book at chapter 14" — but `resolve(mediaIds, requestedIndex)` cannot distinguish
 * *"play this book"* from *"play chapter 1 from the top"*, since both arrive as index 0. Guessing
 * would make tapping chapter 1 jump to chapter 14. So the caller, which knows the intent, chooses
 * the item (`BookPlaybackLauncher`), and this chooses the position — which is the guarantee the
 * seam was actually built for.
 *
 * Never blocks: every answer comes from [source]'s in-memory map. See `AudiobookSnapshot` for the
 * two ways that goes wrong and what stops them.
 */
class AudiobookResumePolicy(
  private val source: AudiobookItemSource,
  private val clock: Clock,
) : ResumePolicy {

  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
    // A stale index from another process -- a car, a watch, a headset -- must not throw out of
    // `setMediaItems` and take the session down with it.
    val index = requestedIndex.coerceIn(0, (mediaIds.size - 1).coerceAtLeast(0))
    val mediaId = mediaIds.getOrNull(index) ?: return ResumeTarget(index, 0L)

    // `null` is "not an audiobook". Music restarts from zero because there is nothing here to
    // resume from, not because a branch says so.
    val item = source.itemFor(mediaId) ?: return ResumeTarget(index, 0L)

    // A finished item starts again. Otherwise pressing play on a book you finished drops you two
    // seconds before the end.
    if (item.isFinished) return ResumeTarget(index, 0L)

    val awayMs = clock.millis() - item.lastPlayedAtEpochMs
    return ResumeTarget(index, SmartRewind.resumePositionMs(item.positionMs, awayMs))
  }
}
```

`MediaModule` — the swap Plan 3 designed for, and the only line of Plan 3's wiring this plan
changes:

```kotlin
  /**
   * Plan 3 shipped `NeverResume` here and said the audiobook plan would replace it and change
   * nothing else. This is that replacement.
   *
   * `AudiobookSnapshot` is a `@Singleton`, and `MuPlaybackService.onCreate` starts its collector —
   * a snapshot nobody started answers `null` for everything, which resumes nothing and looks
   * exactly like the bug this replaced.
   */
  @Provides
  @Singleton
  fun provideResumePolicy(snapshot: AudiobookSnapshot, clock: Clock): ResumePolicy =
    AudiobookResumePolicy(snapshot, clock)
```

`MuPlaybackService.onCreate` — one added line, beside the `ProgressWriter` start Plan 3 put there:

```kotlin
    // Without this the snapshot is empty for the life of the process and every book starts at
    // zero -- silently, and only on a device where nothing else warmed it.
    audiobookSnapshot.start(serviceScope)
```

with `@Inject lateinit var audiobookSnapshot: AudiobookSnapshot`, and `audiobookSnapshot.stop()` in
`onDestroy` beside `progressWriter?.stop()`.

- [ ] **Step 3: Run the policy test**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*AudiobookResumePolicyTest*'`
Expected: PASS, 8/8.

- [ ] **Step 4: Write the snapshot test**

`core/media/src/androidTest/kotlin/app/muplay/media/AudiobookSnapshotTest.kt`:

```kotlin
package app.muplay.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.AudiobookRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.MediaProgressEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-memory view, over a real Room.
 *
 * The test that matters most here is the **cold** one: a snapshot whose collector has never run
 * must still answer correctly after `refresh()`. If it does not, the first book played after a
 * process start resumes at zero — once a month, on a slow device, and never in a suite that
 * happened to warm it up first.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookSnapshotTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var repository: AudiobookRepository
  private lateinit var snapshot: AudiobookSnapshot
  private lateinit var scope: CoroutineScope
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    repository = AudiobookRepository(db.audiobookDao(), db.mediaProgressDao(), db.bookSettingsDao(), clock)
    snapshot = AudiobookSnapshot(repository, db.mediaProgressDao(), db.bookSettingsDao())
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The same two-library, three-book fixture `AudiobookRepositoryTest` seeds. Extract that
    // seeding into a shared `AudiobookFixtures.seed(db)` in androidTest rather than copying it --
    // two copies of a seed is how one test's corpus drifts from another's.
    AudiobookFixtures.seed(db)
  }

  @After
  fun tearDown() {
    snapshot.stop()
    scope.cancel()
    db.close()
  }

  @Test
  fun aColdSnapshotAnswersCorrectlyAfterRefreshWithNoCollectorRunning() = runBlocking {
    db.mediaProgressDao().upsert(MediaProgressEntity("multi-2", 3_500L, false, 900L, 1f, false, 0f))

    // Deliberately no `start(scope)`. This is the process-start path.
    snapshot.refresh()

    assertThat(snapshot.itemFor("multi-2")?.positionMs).isEqualTo(3_500L)
    assertThat(snapshot.isLoaded).isTrue
  }

  @Test
  fun aSnapshotNobodyStartedOrRefreshedKnowsNothing() = runBlocking {
    db.mediaProgressDao().upsert(MediaProgressEntity("multi-2", 3_500L, false, 900L, 1f, false, 0f))

    // The control for the test above. Without it, "refresh() works" and "the constructor works"
    // are the same claim, and the cold-start race would be invisible.
    assertThat(snapshot.itemFor("multi-2")).isNull()
    assertThat(snapshot.isLoaded).isFalse
  }

  @Test
  fun theCollectorKeepsTheSnapshotCurrent() = runBlocking {
    snapshot.start(scope)
    withTimeout(5_000) { snapshot.awaitLoaded() }
    assertThat(snapshot.itemFor("multi-2")?.positionMs).isZero

    db.mediaProgressDao().upsert(MediaProgressEntity("multi-2", 7_777L, false, 900L, 1f, false, 0f))

    // Two observations of the same field, the second after a write. A snapshot that loaded once
    // and stopped would pass an assertion on the first alone -- and would hand back a stale
    // position for the rest of the process's life.
    awaitValue("multi-2 to reach 7777") { snapshot.itemFor("multi-2")?.positionMs == 7_777L }
  }

  @Test
  fun onlyAudiobookFilesAreInTheSnapshot() = runBlocking {
    db.mediaProgressDao().upsert(MediaProgressEntity("track-1", 4_000L, false, 900L, 1f, false, 0f))
    db.mediaProgressDao().upsert(MediaProgressEntity("multi-2", 3_500L, false, 900L, 1f, false, 0f))

    snapshot.refresh()

    // Exact keys. A snapshot built from `media_progress` rather than from the audiobook item map
    // would contain "track-1", and that one extra key is the whole of "music resumes too".
    assertThat(snapshot.items().keys)
      .containsExactlyInAnyOrder("single-1", "multi-1", "multi-2", "multi-3")
    assertThat(snapshot.itemFor("track-1")).isNull()
  }

  @Test
  fun anAudiobookFileWithNoProgressRowIsStillInTheSnapshot() = runBlocking {
    // Because the snapshot also carries `speed` and `skipSilence` (Task 7), and a book whose
    // settings were set before it was ever played must still play at that speed.
    db.bookSettingsDao().upsert(app.muplay.database.entity.BookSettingsEntity("multi", 1.4f, true))

    snapshot.refresh()

    val item = snapshot.itemFor("multi-1")!!
    assertThat(item.positionMs).isZero
    assertThat(item.speed).isEqualTo(1.4f)
    assertThat(item.skipSilence).isTrue
    // ...and the other book keeps the defaults, so "1.4" is not a constant.
    assertThat(snapshot.itemFor("single-1")!!.speed).isEqualTo(1.0f)
  }

  @Test
  fun everyFileOfABookCarriesTheBooksSettings() = runBlocking {
    db.bookSettingsDao().upsert(app.muplay.database.entity.BookSettingsEntity("multi", 1.6f, false))

    snapshot.refresh()

    // The whole reason `book_settings` exists. Per-item storage gives three different answers
    // here; the book's grain gives one, and this is the assertion that says which.
    assertThat(listOf("multi-1", "multi-2", "multi-3").map { snapshot.itemFor(it)!!.speed })
      .containsExactly(1.6f, 1.6f, 1.6f)
  }

  private fun awaitValue(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(50)
    }
    throw AssertionError("timed out waiting for $description")
  }
}
```

> `AudiobookFixtures.seed(db)` is a new androidTest helper holding the two-library, three-book
> corpus `AudiobookRepositoryTest` builds in its `@Before`. **Extract it there when writing this
> task** and have `AudiobookRepositoryTest` call it too — that test lives in `:core:database` and
> this one in `:core:media`, so put the helper in `core/testing`'s main source set beside
> `BookFixtures` if the two modules cannot otherwise share it. Two copies of a seed is how one
> test's corpus silently drifts from another's.

- [ ] **Step 5: Write the shared device harness, then the test that proves audio actually resumed**

Three device suites in this plan need the same expensive setup — a real container, a real mirror in
an in-memory Room, a real `ExoPlayer` over the real data-source factory, and the real stream URLs.
**Write it once.** `AudiobookResumeTest` (this task), `BookSpeedControllerTest` (Task 7) and
`SleepTimerControllerTest` (Task 8) all use it, and three copies of a setup is how one suite's
corpus silently drifts from another's.

`core/media/src/androidTest/kotlin/app/muplay/media/BookPlaybackHarness.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.AudiobookRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.AlbumListType
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Everything three device suites in this plan need, built once.
 *
 * Real Navidrome, real mirror, real `ExoPlayer`, real stream URLs. Nothing here is a fake — the
 * only stand-in in the whole harness is the `Clock`, which is the one thing spec section 10's test
 * hierarchy names as legitimately fakeable ("an injected `Clock`, a severed socket, a forced 429").
 *
 * A fixed clock matters more here than it looks: it makes the smart rewind an **exact expected
 * value** rather than a band that widens with however slow the emulator is that day.
 */
class BookPlaybackHarness private constructor(
  val context: Context,
  val db: MuPlayDatabase,
  val repository: AudiobookRepository,
  val snapshot: AudiobookSnapshot,
  val songs: List<Song>,
  val streamUrls: Map<String, String>,
  val clock: Clock,
  private val cacheDir: File,
  val scope: CoroutineScope,
) {

  var player: PlayerHarness? = null
    private set

  fun song(title: String): Song = songs.single { it.title == title }

  fun songId(title: String): String = song(title).id

  /** A book is an album, so a book id is an album id — `AudiobookRepository.bookIdOf`'s rule. */
  fun bookIdOf(title: String): String = AudiobookRepository.bookIdOf(song(title))

  suspend fun store(title: String, positionMs: Long, agoMs: Long, finished: Boolean = false) {
    db.mediaProgressDao().upsert(
      MediaProgressEntity(songId(title), positionMs, finished, clock.millis() - agoMs, 1f, false, 0f),
    )
  }

  private fun mediaItem(title: String): MediaItem {
    val s = song(title)
    return MediaItem.Builder().setMediaId(s.id).setUri(streamUrls.getValue(s.id))
      .setCustomCacheKey(s.id).build()
  }

  private fun newExoPlayer(): ExoPlayer = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
      DefaultMediaSourceFactory(
        MuPlayDataSourceFactory(OkHttpClient(), MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}")))
          .create(),
      ),
    )
    .build()

  /**
   * Sets a queue **through the `MuPlayer` seam**, with a caller-supplied position of 99 000 ms —
   * an impossible position for any fixture in this corpus, so if it ever reaches the player the
   * assertion that catches it is unambiguous.
   */
  fun startThroughTheSeam(titles: List<String>, requestedIndex: Int, policy: ResumePolicy): PlayerHarness {
    lateinit var built: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val exo = newExoPlayer()
      built = PlayerHarness(exo)
      MuPlayer(exo, policy).setMediaItems(titles.map(::mediaItem).toMutableList(), requestedIndex, 99_000L)
      exo.prepare()
      exo.play()
    }
    player = built
    return built
  }

  /**
   * Sets a queue on a raw `ExoPlayer`, no seam.
   *
   * Used by the speed and sleep-timer suites, whose subjects are `ExoPlayer` behaviours: routing
   * them through the seam would add a layer with nothing to say about them, and would make a
   * failure ambiguous between the two.
   */
  fun startPlain(titles: List<String>): PlayerHarness {
    lateinit var built: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val exo = newExoPlayer()
      built = PlayerHarness(exo)
      exo.setMediaItems(titles.map(::mediaItem))
      exo.prepare()
      exo.play()
    }
    player = built
    return built
  }

  fun close() {
    player?.release()
    scope.cancel()
    snapshot.stop()
    db.close()
    cacheDir.deleteRecursively()
  }

  companion object {
    const val AUDIOBOOK_LIBRARY_ID = 2

    fun create(clock: Clock): BookPlaybackHarness = runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
      val cacheDir = File(context.cacheDir, "book-harness-${System.nanoTime()}")

      val client = RealTrackBytes.client()
      val albums = client.getAlbumList2(AUDIOBOOK_LIBRARY_ID, AlbumListType.ALPHABETICAL_BY_NAME, 50, 0)
      val bookSongs = albums.flatMap { client.getAlbum(it.id, AUDIOBOOK_LIBRARY_ID).songs }
      val songs = bookSongs + RealTrackBytes.musicTracks()

      // The real mirror, seeded from the real server, so `AudiobookRepository` reads the same rows
      // the app would -- including library 2 tagged AUDIOBOOKS and library 1 tagged MUSIC.
      AudiobookFixtures.seedFromServer(db, albums, songs)

      val repository = AudiobookRepository(db.audiobookDao(), db.mediaProgressDao(), db.bookSettingsDao(), clock)
      BookPlaybackHarness(
        context = context,
        db = db,
        repository = repository,
        snapshot = AudiobookSnapshot(repository, db.mediaProgressDao(), db.bookSettingsDao()),
        songs = songs,
        streamUrls = songs.associate {
          it.id to client.streamUrl(it.id, StreamFormat.forSuffix(it.suffix, StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
        },
        clock = clock,
        cacheDir = cacheDir,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
      )
    }
  }
}
```

`core/media/src/androidTest/kotlin/app/muplay/media/AudiobookResumeTest.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.AudiobookRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.Song
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Real audio, resuming at a real position.**
 *
 * The rule this class is built around: *a resume test that asserts the player was told to seek
 * proves nothing.* `ResumeTarget.startPositionMs` being right, `seekTo` having been called and the
 * policy having been consulted are all satisfied by a player that then ignores the answer, by a
 * URL that 404s into a swallowed error, and by a decoder that produced no sample. So every test
 * here asserts **two** things: the position playback started at, and that playback then **advanced
 * past it** — which only a decoder that actually decoded can do.
 *
 * Positions are chosen **off every chapter boundary in their book**. Second Book's chapters start
 * at 0 / 4000 / 9000 / 15000; 17 500 is 2.5 s into the last one, so "resumed at the chapter start"
 * and "resumed exactly" are different numbers. With a position of 15 000 they would not be.
 *
 * The clock is **fixed** and the stored `lastPlayedAtEpochMs` is set relative to it, so the smart
 * rewind is an exact expected value rather than a band that drifts with how slow the emulator is.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookResumeTest {

  private val now = 1_700_000_000_000L

  /** Fixed, so the smart rewind is an exact expected value rather than an emulator-speed band. */
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

  private lateinit var fixture: BookPlaybackHarness
  private val snapshot: AudiobookSnapshot get() = fixture.snapshot

  @Before
  fun setUp() {
    fixture = BookPlaybackHarness.create(clock)
  }

  @After
  fun tearDown() = fixture.close()

  private suspend fun store(title: String, positionMs: Long, agoMs: Long, finished: Boolean = false) =
    fixture.store(title, positionMs, agoMs, finished)

  /** Through the seam, with the real policy and a caller-supplied position the seam must discard. */
  private fun startThroughTheSeam(titles: List<String>, requestedIndex: Int): PlayerHarness =
    fixture.startThroughTheSeam(titles, requestedIndex, AudiobookResumePolicy(snapshot, clock))

  @Test
  fun aBookResumesExactlyWhereItWasLeftAndThenKeepsPlaying() = runBlocking {
    // 17 500 ms: 2.5 s into Second Book's last chapter, which starts at 15 000. Off the boundary
    // on purpose -- at 15 000, "resumed exactly" and "resumed at the chapter start" would be the
    // same number and this test would prove neither.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    // 1. It started where it was left. `agoMs = 0` puts the away time in the 0 ms rewind band, so
    //    this is an equality rather than a band.
    harness.awaitPositionAtLeast(17_500L, timeoutMs = 15_000L)
    // 2. It kept going. A player parked at 17 500 -- seeked and then silent -- fails here, and a
    //    player that started at 0 cannot reach 19 000 inside this timeout.
    harness.awaitPositionAtLeast(19_000L, timeoutMs = 8_000L)
    harness.assertNoPlaybackError()
  }

  @Test
  fun aSecondBookResumesAtItsOwnPositionAndNotTheFirstBooks() = runBlocking {
    // The original complaint's smallest possible form. With one book, "resumes at B's position" and
    // "resumes at the only position stored" are the same program.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)
    store("Test Book", positionMs = 11_200L, agoMs = 0L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Test Book"), requestedIndex = 0)

    harness.awaitPositionAtLeast(11_200L, timeoutMs = 15_000L)
    assertThat(harness.onMain { harness.player.currentPosition })
      .describedAs("Test Book must not resume at Second Book's position")
      .isLessThan(15_000L)
    harness.awaitPositionAtLeast(12_500L, timeoutMs = 8_000L)
  }

  @Test
  fun aBookLeftAWhileAgoRewindsByTheBandsAmount() = runBlocking {
    // Ten minutes away -> the 5 s band. Expected start 12 500, which is 3.5 s into Second Book's
    // third chapter (9 000..15 000): again, off every boundary and nowhere near zero.
    store("Second Book", positionMs = 17_500L, agoMs = 600_000L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    harness.awaitPositionAtLeast(12_500L, timeoutMs = 15_000L)
    val started = harness.onMain { harness.player.currentPosition }
    // Strictly inside the window between "no rewind at all" and "rewound into the previous
    // chapter". Both endpoints are real bugs, and both are excluded.
    assertThat(started).isBetween(12_000L, 13_500L)
    harness.awaitPositionAtLeast(14_000L, timeoutMs = 8_000L)
  }

  @Test
  fun aMultiFileBookResumesOntoTheRightFile() = runBlocking {
    // Half of "per-book resume", and the half a single-file corpus cannot express: coming back to
    // the right *file*, not just the right offset.
    store("Part Two", positionMs = 3_500L, agoMs = 0L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Part One", "Part Two", "Part Three"), requestedIndex = 1)

    harness.await("the second file to be current") { harness.player.currentMediaItemIndex == 1 }
    harness.awaitPositionAtLeast(3_500L, timeoutMs = 15_000L)
    // Part Two is 6 s long, so reaching index 2 proves it played on from 3.5 s rather than
    // restarting -- a restart would take 6 s to get there and this timeout will not allow it.
    harness.await("playback to advance into the third file", timeoutMs = 5_000L) {
      harness.player.currentMediaItemIndex == 2
    }
  }

  @Test
  fun musicStartsAtZeroEvenWithProgressStored() = runBlocking {
    // Spec section 3, asserted against a real player. The music track has a real progress row and a
    // real position; it must be ignored on prepare.
    store("Track 1", positionMs = 3_000L, agoMs = 0L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Track 1"), requestedIndex = 0)

    harness.awaitState(androidx.media3.common.Player.STATE_READY, timeoutMs = 15_000L)
    // Read early and assert small: a track that started at 3 000 would already be past 2 000 here.
    assertThat(harness.onMain { harness.player.currentPosition }).isLessThan(2_000L)
    harness.awaitPositionAtLeast(2_000L, timeoutMs = 6_000L)
  }

  @Test
  fun aFinishedBookStartsOver() = runBlocking {
    store("Test Book", positionMs = 14_500L, agoMs = 0L, finished = true)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Test Book"), requestedIndex = 0)

    harness.awaitState(androidx.media3.common.Player.STATE_READY, timeoutMs = 15_000L)
    assertThat(harness.onMain { harness.player.currentPosition }).isLessThan(2_000L)
    harness.awaitPositionAtLeast(2_000L, timeoutMs = 6_000L)
  }

  @Test
  fun theCallersRequestedPositionIsStillDiscarded() = runBlocking {
    // Plan 3 Task 8's guarantee, re-asserted now that a policy actually returns something. The
    // caller above always passes 99 000 -- an impossible position for a 21 s book -- and it must
    // never be what the player does.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)
    snapshot.refresh()

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    harness.awaitState(androidx.media3.common.Player.STATE_READY, timeoutMs = 15_000L)
    assertThat(harness.onMain { harness.player.currentPosition }).isLessThan(21_000L)
  }
}
```

> `AudiobookFixtures.seedFromServer(db, albums, songs)` writes the real server's albums and songs
> into the in-memory mirror with library 2 tagged `AUDIOBOOKS` and library 1 `MUSIC`, using Plan 2's
> `LibraryDao.mergeFromServer`, `MirrorMapper` and `BrowseDao.replaceLibraryContents` — one
> `replaceLibraryContents` call per library, with the songs partitioned by `Song.libraryId`. It sits
> beside `AudiobookFixtures.seed(db)` (the hand-built three-book corpus `AudiobookRepositoryTest`
> and `AudiobookSnapshotTest` use). Both live in `core/testing`'s main source set so `:core:media`
> and `:core:database` can share them; if the module graph makes that awkward, put them in
> `core/testing` anyway rather than copying — two copies of a seed is how one suite's corpus
> silently drifts from another's.

- [ ] **Step 6: The resumption callback**

`core/media/src/main/kotlin/app/muplay/media/ResumptionQueue.kt`:

```kotlin
package app.muplay.media

import app.muplay.database.AudiobookRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * What comes back when the system says "carry on", with nothing playing.
 *
 * Android 13+ shows a resumption control in the notification shade after a reboot, and a headset
 * or lock-screen play button reaches a session whose player is empty. `MediaLibraryService`'s
 * `onPlaybackResumption` is the callback for it, and spec section 10 explicitly assigns that
 * callback to *"the plan that resumes"* — this one.
 *
 * The answer is **the most recently heard unfinished book**, which is the top row of the shelf.
 * Not the most recent *anything*: pressing play after a reboot and getting a random song is a
 * worse answer than getting nothing, and the shelf's ordering already encodes what "carry on"
 * means.
 *
 * The **position** is not decided here. The queue names the item; `AudiobookResumePolicy` supplies
 * the position when `MuPlayer` sets the items, exactly as on every other play path. Two places
 * deciding a position is how they come to disagree.
 */
@Singleton
class ResumptionQueue @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
) {

  suspend fun mostRecent(): PlaybackQueue? {
    val book = audiobookRepository.bookshelf().first()
      .firstOrNull { it.hasStarted && !it.isFinished }
      ?: return null
    val files = audiobookRepository.files(book.bookId)
    if (files.isEmpty()) return null
    val resumeAt = audiobookRepository.resumePoint(book.bookId)?.mediaId
    val index = files.indexOfFirst { it.id == resumeAt }.coerceAtLeast(0)
    return PlaybackQueue.of(files, startIndex = index)
  }
}
```

`MuPlaybackService` — the callback, on the `MediaLibrarySession.Callback` Plan 3 Task 5 installed:

```kotlin
    /**
     * Spec section 10 assigns `onPlaybackResumption` to "the plan that resumes". The future is
     * completed off the main thread because building the queue reads Room and builds authenticated
     * URLs; returning `Futures.immediateFuture(...)` after a blocking read here would block the
     * main thread at exactly the moment the system is waiting for an answer.
     */
    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
      serviceScope.launch {
        val queue = resumptionQueue.mostRecent()
        if (queue == null) {
          // Nothing to carry on with. Failing the future is what tells the system to leave the
          // resumption control alone, rather than starting silence.
          future.setException(UnsupportedOperationException("no book to resume"))
          return@launch
        }
        audiobookSnapshot.refresh()
        future.set(
          MediaSession.MediaItemsWithStartPosition(
            queueRepository.mediaItems(queue),
            queue.startIndex,
            // The position the policy will overwrite anyway. Passing anything else here would be a
            // second opinion about where a book resumes.
            C.TIME_UNSET,
          ),
        )
      }
      return future
    }
```

> `MediaSession.MediaItemsWithStartPosition` and `SettableFuture` (from Guava, already on the
> classpath via Media3) — confirm both against the resolved 1.11.0 sources. If
> `MediaItemsWithStartPosition` lives at a different path or the callback's signature differs,
> match the real one; the shape above is the contract, not the coordinates.

`core/media/src/androidTest/kotlin/app/muplay/media/ResumptionQueueTest.kt`:

```kotlin
package app.muplay.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.AudiobookRepository
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.MediaProgressEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What "carry on" resolves to, tested at the level that can be tested: the queue, not the callback.
 *
 * The callback itself is driven by the system's media-resumption path; Task 10's journey exercises
 * it through a real session. What is gated here is the decision — which book, which file — because
 * that is where "carry on" can be wrong in a way nobody notices until a reboot.
 */
@RunWith(AndroidJUnit4::class)
class ResumptionQueueTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var subject: ResumptionQueue
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    // The same hand-built three-book corpus `AudiobookRepositoryTest` and `AudiobookSnapshotTest`
    // seed: one single-file book ("single"/`single-1`), one three-file book ("multi"/`multi-1..3`),
    // and one music track (`track-1`) in library 1 that must never be an answer here.
    AudiobookFixtures.seed(db)
    subject = ResumptionQueue(
      AudiobookRepository(db.audiobookDao(), db.mediaProgressDao(), db.bookSettingsDao(), clock),
    )
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun theMostRecentlyHeardUnfinishedBookComesBack() = runBlocking {
    db.mediaProgressDao().upsert(MediaProgressEntity("single-1", 5_000L, false, 100L, 1f, false, 0f))
    db.mediaProgressDao().upsert(MediaProgressEntity("multi-2", 3_500L, false, 900L, 1f, false, 0f))

    val queue = subject.mostRecent()!!

    // Two books with two times: with one book this assertion is true of every implementation.
    assertThat(queue.songs.map { it.id }).containsExactly("multi-1", "multi-2", "multi-3")
    assertThat(queue.startIndex).isEqualTo(1)
  }

  @Test
  fun aFinishedBookIsNotWhatYouCarryOnWith() = runBlocking {
    db.mediaProgressDao().upsert(MediaProgressEntity("multi-3", 5_000L, true, 900L, 1f, false, 0f))
    db.mediaProgressDao().upsert(MediaProgressEntity("single-1", 5_000L, false, 100L, 1f, false, 0f))

    assertThat(subject.mostRecent()!!.songs.map { it.id }).containsExactly("single-1")
  }

  @Test
  fun aMusicTrackIsNeverWhatYouCarryOnWith() = runBlocking {
    // Pressing play after a reboot and getting a random song is a worse answer than getting
    // nothing, and it is what happens if "most recent" is read off `media_progress` directly.
    db.mediaProgressDao().upsert(MediaProgressEntity("track-1", 4_000L, false, 9_999L, 1f, false, 0f))

    assertThat(subject.mostRecent()).isNull()
  }

  @Test
  fun nothingHeardMeansNothingToCarryOnWith() = runBlocking {
    assertThat(subject.mostRecent()).isNull()
  }
}
```

- [ ] **Step 7: Write the local-only guard**

`core/network/src/test/kotlin/app/muplay/network/LocalOnlyProgressTest.kt`:

```kotlin
package app.muplay.network

import app.muplay.model.SearchResults
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import retrofit2.http.GET

/**
 * **Book positions are local only** — spec sections 2, 4 and 11, and a global constraint of every
 * plan in this project. Until now that has been prose.
 *
 * Rule 5: a gate reporting the absence of a problem must be provably incapable of staying quiet
 * when it did not run. So this asserts the **exact** method set and the **exact** endpoint set,
 * rather than asserting that four forbidden names are missing — a `doesNotContain` check passes
 * just as happily against an empty list, and would not notice a fifth way to send a position that
 * nobody thought to name.
 *
 * Spec section 4 records the specific hazard this removes: `createBookmark.position` is documented
 * in **milliseconds** while `bookmarkPosition` on a `Child` is documented in **seconds**, so a
 * sync path built on them puts every resume out by 1000x. The reason that note exists is to explain
 * why the write path does not.
 */
class LocalOnlyProgressTest {

  @Test
  fun `the Subsonic port exposes exactly these operations and no way to write progress`() {
    val methods = SubsonicSource::class.java.methods
      .filterNot { it.isSynthetic || it.isBridge }
      .map { it.name }
      .distinct()

    assertThat(methods)
      .describedAs(
        "Book positions are local only (spec sections 2, 4, 11). If you are adding a method here, " +
          "and it is createBookmark / savePlayQueue / savePlayQueueByIndex / scrobble / any other " +
          "way to send a position to a server, stop: that is a non-goal of this project, not an " +
          "omission. If it is something else, add it to this list deliberately.",
      )
      .containsExactlyInAnyOrder(
        "ping", "getMusicFolders", "getScanStatus", "getAlbumList2", "getAlbum",
        "search3", "getRandomSongs", "coverArtUrl", "streamUrl",
      )
  }

  @Test
  fun `every declared endpoint is a read`() {
    val paths = SubsonicApi::class.java.declaredMethods
      .mapNotNull { it.getAnnotation(GET::class.java)?.value }
      .distinct()
      .sorted()

    // The exact list, and it is non-empty -- a reflection call that found nothing would satisfy
    // any `noneMatch` and any `doesNotContain`.
    assertThat(paths).isNotEmpty
    assertThat(paths).allSatisfy { path ->
      assertThat(path).doesNotContain("createBookmark").doesNotContain("savePlayQueue")
        .doesNotContain("scrobble").doesNotContain("setRating").doesNotContain("star")
    }
    assertThat(paths).containsExactly(
      "getAlbum", "getAlbumList2", "getCoverArt", "getMusicFolders",
      "getOpenSubsonicExtensions", "getRandomSongs", "getScanStatus", "ping", "search3",
    )
  }

  @Test
  fun `the client declares no POST at all`() {
    // Subsonic is a GET protocol, so every write in it is still a GET -- which is exactly why the
    // assertion above is on the path list rather than on the HTTP verb. This asserts the weaker
    // fact anyway, because a POST appearing here would mean something new and unexamined.
    val posts = SubsonicApi::class.java.declaredMethods
      .filter { method -> method.annotations.any { it.annotationClass.simpleName == "POST" } }

    assertThat(posts).isEmpty()
    // The control: there ARE methods on this interface, so "no POSTs" is not vacuous.
    assertThat(SubsonicApi::class.java.declaredMethods).isNotEmpty
  }
}
```

> The two exact lists above are what Plans 2 and 3 are expected to have left behind. **Read the real
> `SubsonicSource` and `SubsonicApi` and write down what is actually there.** A list you had to
> correct is still a gate; a list you loosened to `contains` is not. Note that
> `SubsonicSource::class.java.methods` on an interface with default methods or Kotlin-generated
> `DefaultImpls` may surface extra names — filter synthetics as above and, if a `$default` bridge
> still appears, filter by `!it.name.contains('$')` and say so in a comment.

- [ ] **Step 8: Run everything**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./gradlew :core:media:testDebugUnitTest :core:network:test
./ci/prepare-emulator.sh
./gradlew :core:media:connectedDebugAndroidTest
```

Expected: PASS — `AudiobookResumePolicyTest` 8/8, `LocalOnlyProgressTest` 3/3,
`AudiobookSnapshotTest` 6/6, `AudiobookResumeTest` 7/7, `ResumptionQueueTest` 4/4, and **every Plan
3 suite still green** — in particular `MuPlayerTest`, which asserts the seam, and
`ProgressWriterTest`. If `MuPlayerTest`'s `aCallersRequestedPositionNeverReachesThePlayer` broke,
this task changed something it was not supposed to.

> Plan 3's `ResumePolicyTest` asserts `NeverResume`'s behaviour and the two-parameter shape of
> `resolve`. **Both still pass and both stay**: `NeverResume` is not deleted. It remains the
> reference implementation of "no resume", it is what a future non-audiobook policy would start
> from, and its test is what keeps `resolve`'s signature from growing a position parameter.

- [ ] **Step 9: Prove the resume can fail**

One mutation at a time, reverted after each:

1. Restore `provideResumePolicy(): ResumePolicy = NeverResume` in `MediaModule`. Expect **no**
   `:core:media` test to fail, because `AudiobookResumeTest` constructs the policy directly.
   **Record that as a known ungated line and fix it in Task 10**, whose journey goes through the
   real Hilt graph. This is the single most important line in the plan; it must be gated
   *somewhere*, and honesty about where beats a probe that cannot fire.
2. In `AudiobookResumePolicy.resolve`, return `ResumeTarget(index, 0L)` unconditionally. Expect
   `AudiobookResumePolicyTest` (five tests) and `AudiobookResumeTest`'s four resume tests to fail.
3. Remove the `item.isFinished` arm. Expect `a finished item starts again from the beginning` and
   `aFinishedBookStartsOver` to fail.
4. In `AudiobookResumePolicy.resolve`, use `item.positionMs` directly instead of
   `SmartRewind.resumePositionMs(...)`. Expect `the smart rewind is applied…` and
   `aBookLeftAWhileAgoRewindsByTheBandsAmount` to fail.
5. In `AudiobookSnapshot.build`, key off `progress` instead of `bookIdByMediaId` (i.e.
   `progress.associate { ... }`). Expect `onlyAudiobookFilesAreInTheSnapshot` and
   `musicStartsAtZeroEvenWithProgressStored` to fail. **This is "music resumes too"**, reproduced
   on demand.
6. Delete `snapshot.refresh()` from `AudiobookResumeTest`'s helpers (test-side, so record it rather
   than committing it). Expect every resume assertion to fail — which is the cold-start race, and
   the reason `refresh()` is on the play path.
7. In `ResumptionQueue.mostRecent`, drop the `!it.isFinished` filter. Expect
   `aFinishedBookIsNotWhatYouCarryOnWith` to fail.
8. Add a `createBookmark` method to `SubsonicSource` (and a stub in `SubsonicClient`). Expect
   `the Subsonic port exposes exactly these operations…` to fail with the message naming the
   constraint. **Read the message** — it is what a future contributor will see.

- [ ] **Step 10: Record the probes, re-measure, commit**

Mutations 2, 3, 4 and 8 are JVM-side; add them to `ci/mutation-probes.sh` with
`AudiobookResumePolicy.kt` and `SubsonicSource.kt` in `revert()`'s file list, and make sure
`run_suite()` covers `:core:media:testDebugUnitTest` and `:core:network:test`. Mutations 1, 5, 6
and 7 go in the task report, with mutation 1 flagged as **Task 10's to close**.

`AudiobookResumePolicy` is a JVM floor; `AudiobookSnapshot` and `ResumptionQueue` are instrumented.

```bash
git add core/media core/network build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): books resume at their own exact position, and only books do"
```

---

## Task 7: Per-book speed and silence skipping — and the speed that follows you into music

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/BookSpeedController.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/PlaybackConnection.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/BookPlaybackSettingsTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/BookSpeedControllerTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/ProgressWriterSilenceSkipTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `AudiobookItemSource.itemFor(mediaId)`, `AudiobookItem` (Task 6),
  `AudiobookRepository.setSpeed/setSkipSilence` (Task 4), `BookSettings` (Task 2),
  `MuPlayerFactory.createExoPlayer()` (Plan 3 Task 5),
  `ProgressWriter(player, dao, clock, scope)` (Plan 3 Task 8),
  `PlaybackState(...)`, `PlaybackConnection` (Plan 3 Task 5), `PlayerHarness` (Plan 3 Task 2).
- Produces:
  - `data class BookPlaybackSettings(val speed: Float, val skipSilence: Boolean)` with
    `companion object { val MUSIC: BookPlaybackSettings; fun of(item: AudiobookItem?): BookPlaybackSettings }`
  - `class BookSpeedController(player: ExoPlayer, source: AudiobookItemSource, persist: (bookId: String, speed: Float) -> Unit)`
    implementing `Player.Listener`, with `fun start()`, `fun stop()`, `fun applyFor(mediaId: String?)`
  - `MuPlayerFactory.wrap(exoPlayer: ExoPlayer): MuPlayer`
  - `PlaybackState` gains `val mediaType: Int` and `val speed: Float`;
    `PlaybackState.NOTHING_PLAYING` gains `MediaMetadata.MEDIA_TYPE_MIXED` and `1.0f`
  - `PlaybackState.isAudiobook: Boolean` (computed)

### The trap that gives this task its name

Set a book to 1.4×. Skip to a music track. **Playback parameters are a property of the player, not
of the item** — `ExoPlayer` keeps the speed across a media-item transition, so the song plays at
1.4× and nothing anywhere reports it. The same in reverse: finish a song, go back to your book, and
the book has quietly lost the speed you chose.

So the speed is **applied on every item transition**, from the item's own book: a book's speed for a
book file, `1.0f` for anything else. `BookPlaybackSettings.of(item)` is that decision, it is
Android-free, and it is gated in Tier 1.

The second trap is the write-back. The speed control in the UI reaches the player through a
`MediaController` (and, later, through a car or a watch), so the persistence has to live at the
player rather than at the button: `onPlaybackParametersChanged` writes the new speed to the current
item's book. But `applyFor` *also* changes the playback parameters, which fires the same callback —
so applying book B's speed on a transition would write it back to whatever the current book is a
moment before or after the transition settles. A re-entrancy flag around the programmatic apply is
the fix, and `BookSpeedControllerTest` asserts a transition between two books leaves **both** their
stored speeds untouched.

### Silence skipping, and the debt Plan 3 recorded

`setSkipSilenceEnabled` is on `ExoPlayer`, not on `Player`, and `MuPlaybackService` currently holds
only the `MuPlayer` seam. That is the fourth seam gap this plan records: `MuPlayerFactory` gains
`wrap(exoPlayer)` so the service can build the `ExoPlayer`, keep it, and hand the wrapped seam to
the session. `create()` keeps working and keeps meaning `wrap(createExoPlayer())`, so no Plan 3 test
moves.

Plan 3 Task 8 Step 10, mutation 4, removed `ProgressWriter`'s
`DISCONTINUITY_REASON_SILENCE_SKIP` guard, watched **no test fail**, and named the audiobook plan as
owing the assertion. **`ProgressWriterSilenceSkipTest` pays it**, and it does so by calling the
listener callback directly with hand-built `Player.PositionInfo`s rather than by hunting for a
fixture with silence in it. That is the right instrument here: the subject is the writer's `when`
on `reason`, not Media3's silence detector, and a synthesised callback discriminates on exactly the
value under test while a fixture would also be testing ExoPlayer.

- [ ] **Step 1: Write the failing Tier 1 decision test**

`core/media/src/test/kotlin/app/muplay/media/BookPlaybackSettingsTest.kt`:

```kotlin
package app.muplay.media

import app.muplay.model.BookSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "What should the player be doing for this item" — one function, no Android, gated in Tier 1.
 *
 * The reason it is a separate type from the controller that applies it: applying is `ExoPlayer`
 * plumbing and needs a device; **deciding** is where the bug is, and a bug that costs an emulator
 * boot per mutation does not get mutated.
 */
class BookPlaybackSettingsTest {

  private fun item(speed: Float, skipSilence: Boolean) = AudiobookItem(
    mediaId = "m", bookId = "b", positionMs = 0L, lastPlayedAtEpochMs = 0L,
    isFinished = false, speed = speed, skipSilence = skipSilence,
  )

  @Test
  fun `an audiobook item plays at its book's speed`() {
    // Two speeds, so a constant satisfies at most one.
    assertThat(BookPlaybackSettings.of(item(1.4f, false)).speed).isEqualTo(1.4f)
    assertThat(BookPlaybackSettings.of(item(0.8f, false)).speed).isEqualTo(0.8f)
  }

  @Test
  fun `anything that is not an audiobook plays at normal speed with no silence skipping`() {
    // The trap this task is named for. `null` is "not in the audiobook snapshot", i.e. music.
    assertThat(BookPlaybackSettings.of(null)).isEqualTo(BookPlaybackSettings.MUSIC)
    assertThat(BookPlaybackSettings.MUSIC.speed).isEqualTo(BookSettings.DEFAULT_SPEED)
    assertThat(BookPlaybackSettings.MUSIC.skipSilence).isFalse
  }

  @Test
  fun `silence skipping follows the book too`() {
    assertThat(BookPlaybackSettings.of(item(1.0f, true)).skipSilence).isTrue
    assertThat(BookPlaybackSettings.of(item(1.0f, false)).skipSilence).isFalse
  }

  @Test
  fun `an impossible stored speed never reaches the player`() {
    // `AudiobookSnapshot` clamps on the way in, and this clamps again on the way out. Two clamps
    // rather than one because `ExoPlayer.setPlaybackSpeed(NaN)` throws from inside a listener
    // callback, which surfaces as playback dying with no message a listener could act on.
    assertThat(BookPlaybackSettings.of(item(99f, false)).speed).isEqualTo(BookSettings.MAX_SPEED)
    assertThat(BookPlaybackSettings.of(item(0f, false)).speed).isEqualTo(BookSettings.MIN_SPEED)
    assertThat(BookPlaybackSettings.of(item(Float.NaN, false)).speed).isEqualTo(BookSettings.DEFAULT_SPEED)
  }
}
```

- [ ] **Step 2: Implement the decision, the controller and the factory method**

`core/media/src/main/kotlin/app/muplay/media/BookSpeedController.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.muplay.model.BookSettings

/** What the player should be doing for the item that is playing right now. */
data class BookPlaybackSettings(val speed: Float, val skipSilence: Boolean) {
  companion object {
    /**
     * What anything that is not a book plays like.
     *
     * This constant is the fix for the bug that names this task: playback parameters live on the
     * **player**, so without an explicit reset a song after a book plays at the book's speed.
     */
    val MUSIC = BookPlaybackSettings(BookSettings.DEFAULT_SPEED, skipSilence = false)

    fun of(item: AudiobookItem?): BookPlaybackSettings = when (item) {
      null -> MUSIC
      else -> BookPlaybackSettings(BookSettings.clampSpeed(item.speed), item.skipSilence)
    }
  }
}

/**
 * Keeps the player's speed and silence skipping matched to whatever is playing, and persists a
 * speed the listener changed.
 *
 * **Applied on every transition**, because `PlaybackParameters` are a property of the player rather
 * than of the item: a book at 1.4x followed by a song plays the song at 1.4x, and a song followed
 * by a book plays the book at 1.0x. Neither reports anything.
 *
 * **Persisted from the player**, not from the button, because the speed control reaches the player
 * through a `MediaController` — and later through a car and a watch — so the callback is the only
 * place that sees every change. The re-entrancy flag matters: [applyFor] changes the parameters
 * itself, which fires the same callback, and without the guard a transition from book A to book B
 * writes B's speed onto A.
 *
 * Takes the raw [ExoPlayer] rather than the `MuPlayer` seam, because `setSkipSilenceEnabled` is on
 * `ExoPlayer` and not on `Player`.
 */
class BookSpeedController(
  private val player: ExoPlayer,
  private val source: AudiobookItemSource,
  private val persist: (bookId: String, speed: Float) -> Unit,
) : Player.Listener {

  private var applying = false

  fun start() = player.addListener(this)

  fun stop() = player.removeListener(this)

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
    applyFor(mediaItem?.mediaId)

  override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
    // Ours, not the listener's. Writing here would persist what we just applied, and on a
    // book-to-book transition it would write the new book's speed onto whichever book the player
    // reports as current at that instant.
    if (applying) return
    val mediaId = player.currentMediaItem?.mediaId ?: return
    val item = source.itemFor(mediaId) ?: return
    persist(item.bookId, BookSettings.clampSpeed(playbackParameters.speed))
  }

  fun applyFor(mediaId: String?) {
    val settings = BookPlaybackSettings.of(mediaId?.let(source::itemFor))
    applying = true
    try {
      player.setPlaybackSpeed(settings.speed)
      player.skipSilenceEnabled = settings.skipSilence
    } finally {
      applying = false
    }
  }
}
```

`MuPlayerFactory` — one added method, and `create()` re-expressed in terms of it so nothing that
called it changes:

```kotlin
  /**
   * Wraps an `ExoPlayer` the caller intends to keep.
   *
   * `MuPlaybackService` needs both halves: the seam, which is what the session and every
   * `MediaController` see, and the raw `ExoPlayer`, because `setSkipSilenceEnabled` is on
   * `ExoPlayer` and not on `Player`. Plan 3's `create()` returned only the seam, so the raw player
   * was unreachable from the service — the one place it is genuinely needed.
   */
  fun wrap(exoPlayer: ExoPlayer): MuPlayer = MuPlayer(exoPlayer, resumePolicy)

  fun create(): MuPlayer = wrap(createExoPlayer())
```

`MuPlaybackService.onCreate` — keep the `ExoPlayer`, and install the controller beside the progress
writer:

```kotlin
    val exoPlayer = playerFactory.createExoPlayer()
    val player = playerFactory.wrap(exoPlayer)
    audiobookSnapshot.start(serviceScope)
    progressWriter = ProgressWriter(player, mediaProgressDao, clock, serviceScope).also { it.start() }
    speedController = BookSpeedController(exoPlayer, audiobookSnapshot) { bookId, speed ->
      // Fire-and-forget into the service scope: this runs on the player's application thread and a
      // Room write there would jank playback. `onDestroy` cancels the scope after
      // `progressWriter.flushBlocking()`, so a speed change in the last instant of the service's
      // life can be lost -- which costs a listener one tap, unlike a lost position.
      serviceScope.launch { audiobookRepository.setSpeed(bookId, speed) }
    }.also { it.start() }
```

and in `onDestroy`, `speedController?.stop()` beside `progressWriter?.stop()`.

`PlaybackState` — two fields, and one computed:

```kotlin
data class PlaybackState(
  // ... every existing field, unchanged ...
  /**
   * `MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER` for a book, `MEDIA_TYPE_MUSIC` for a song.
   *
   * Set by `MediaItems.of` from the user's own `LibraryRole` assignment (Plan 3 Task 6), because
   * Navidrome hardcodes `child.Type = "music"` for every media file and no server field can answer
   * it. Carried here so navigation can choose a player and the UI can choose controls without
   * anything above `:core:media` re-deriving what a book is.
   */
  val mediaType: Int,
  /** The player's current speed. A book's, or 1.0 for anything else — see `BookSpeedController`. */
  val speed: Float,
) {
  val isAudiobook: Boolean
    get() = mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER ||
      mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK

  companion object {
    val NOTHING_PLAYING = PlaybackState(
      // ... existing values ...
      mediaType = MediaMetadata.MEDIA_TYPE_MIXED,
      speed = 1.0f,
    )
  }
}
```

`PlaybackConnection` — map them where it maps everything else, and make sure a speed change
re-emits:

```kotlin
      mediaType = controller.mediaMetadata.mediaType ?: MediaMetadata.MEDIA_TYPE_MIXED,
      speed = controller.playbackParameters.speed,
```

> Plan 3's `PlaybackConnection` refreshes its `StateFlow` from a `Player.Listener`. Whichever
> callback it uses, **`EVENT_PLAYBACK_PARAMETERS_CHANGED` must be one of the events that triggers a
> refresh**, or the speed readout freezes at whatever it was when the item changed. If Plan 3 used
> `onEvents` with an explicit event list, add it there; if it refreshes on every `onEvents`, nothing
> to do — check, do not assume.

- [ ] **Step 3: Write the failing device tests**

`core/media/src/androidTest/kotlin/app/muplay/media/BookSpeedControllerTest.kt`:

```kotlin
package app.muplay.media

// The same imports as `AudiobookResumeTest`, plus:
//   import app.muplay.database.entity.BookSettingsEntity
//   import java.util.concurrent.ConcurrentHashMap

/**
 * Speed and silence skipping, on a real `ExoPlayer` playing real audio.
 *
 * The speed assertions measure **elapsed audio**, not a property: a 15 s book at 2.0x passes 8 s of
 * media inside 5 s of wall clock, and at 1.0x it cannot. `player.playbackParameters.speed` being
 * right is the "was asked to play" mistake — it is satisfied by a decoder that never produced a
 * sample.
 *
 * `skipSilenceEnabled` is asserted as a property, deliberately and with the reason stated: the
 * field under test is *the mapping from book to flag*, and the flag's effect on the audio is
 * Media3's contract, not this project's. Reading it back off the real player is a real
 * observation; recording that a setter was called would not be.
 */
@RunWith(AndroidJUnit4::class)
class BookSpeedControllerTest {

  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
  private lateinit var fixture: BookPlaybackHarness
  private val persisted = ConcurrentHashMap<String, Float>()

  private val db get() = fixture.db
  private val snapshot get() = fixture.snapshot
  private fun bookIdOf(title: String) = fixture.bookIdOf(title)
  private fun songId(title: String) = fixture.songId(title)
  private val now get() = clock.millis()

  @Before
  fun setUp() {
    fixture = BookPlaybackHarness.create(clock)
  }

  @After
  fun tearDown() = fixture.close()

  /**
   * A raw `ExoPlayer`, not the seam: the subject here is `ExoPlayer` behaviour (playback parameters
   * and silence skipping), and routing through `MuPlayer` would add a layer with nothing to say
   * about it while making a failure ambiguous between the two.
   */
  private fun startQueue(titles: List<String>): PlayerHarness {
    val harness = fixture.startPlain(titles)
    harness.onMain {
      val controller =
        BookSpeedController(harness.player, snapshot) { bookId, speed -> persisted[bookId] = speed }
      controller.start()
      // The initial apply is explicit, because a queue set *before* the listener was attached fires
      // no `onMediaItemTransition` for its first item. In production `MuPlaybackService` attaches
      // the controller in `onCreate`, before any queue exists, so the transition does fire -- but a
      // test that relied on that would be relying on ordering it set up itself.
      //
      // Forgetting this in production would make the first book of every session play at 1.0x and
      // every book after it correct, which is the hardest kind of bug to see: it works the second
      // time.
      controller.applyFor(harness.player.currentMediaItem?.mediaId)
    }
    return harness
  }

  private fun startBook(title: String): PlayerHarness = startQueue(listOf(title))

  private fun awaitValue(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline) {
      if (condition()) return
      Thread.sleep(50)
    }
    throw AssertionError("timed out waiting for $description")
  }

  @Test
  fun aBookPlaysAtItsOwnSpeedAndTheAudioActuallyMovesFaster() = runBlocking {
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Second Book"), 2.0f, false))
    snapshot.refresh()

    val harness = startBook("Second Book")

    // 8 s of media inside 5 s of wall clock is impossible at 1.0x. This is the assertion; the
    // `playbackParameters` check below is the diagnostic that tells you *why* when it fails.
    harness.awaitPositionAtLeast(8_000L, timeoutMs = 5_000L)
    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(2.0f)
  }

  @Test
  fun theSameBookAtNormalSpeedCannotReachThere() = runBlocking {
    // The control for the test above. Without it, "reached 8 s in 5 s" proves nothing about speed,
    // because nothing says 5 s was not enough anyway.
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Second Book"), 1.0f, false))
    snapshot.refresh()

    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(1_000L, timeoutMs = 10_000L)
    Thread.sleep(4_000L)

    assertThat(harness.onMain { harness.player.currentPosition })
      .describedAs("at 1.0x, five seconds of wall clock cannot be eight seconds of media")
      .isLessThan(8_000L)
  }

  @Test
  fun aBooksSpeedDoesNotFollowYouIntoMusic() = runBlocking {
    // The bug this task is named for. Playback parameters live on the player, so without an
    // explicit reset the song after a book plays at the book's speed and nothing reports it.
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.5f, false))
    snapshot.refresh()

    val harness = startQueue(listOf("Test Book", "Track 1"))
    harness.await("the book to be playing") { harness.player.currentMediaItemIndex == 0 }
    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.5f)

    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.await("the song to be playing") { harness.player.currentMediaItemIndex == 1 }

    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.0f)
  }

  @Test
  fun goingBackToTheBookRestoresItsSpeed() = runBlocking {
    // The other direction, which a one-way reset would break: reset-to-1.0-on-transition applied
    // to every item leaves the book at 1.0 when you come back to it.
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.5f, false))
    snapshot.refresh()

    val harness = startQueue(listOf("Track 1", "Test Book"))
    harness.await("the song to be playing") { harness.player.currentMediaItemIndex == 0 }
    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.0f)

    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.await("the book to be playing") { harness.player.currentMediaItemIndex == 1 }

    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.5f)
  }

  @Test
  fun twoBooksInOneQueueEachPlayAtTheirOwnSpeed() = runBlocking {
    // The observation that a single book cannot make. "The book's speed" and "the speed" are the
    // same value until there are two of them.
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.5f, false))
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Tail Book"), 0.75f, false))
    snapshot.refresh()

    val harness = startQueue(listOf("Test Book", "Tail Book"))
    harness.await("the first book") { harness.player.currentMediaItemIndex == 0 }
    val first = harness.onMain { harness.player.playbackParameters.speed }
    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.await("the second book") { harness.player.currentMediaItemIndex == 1 }
    val second = harness.onMain { harness.player.playbackParameters.speed }

    assertThat(listOf(first, second)).containsExactly(1.5f, 0.75f)
  }

  @Test
  fun aTransitionBetweenTwoBooksDoesNotWriteEitherOnesSpeedOntoTheOther() = runBlocking {
    // The re-entrancy trap. `applyFor` changes the playback parameters, which fires
    // `onPlaybackParametersChanged`; without the guard, transitioning to book B persists B's speed
    // against whichever book the player reports as current at that instant.
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.5f, false))
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Tail Book"), 0.75f, false))
    snapshot.refresh()

    val harness = startQueue(listOf("Test Book", "Tail Book"))
    harness.await("the first book") { harness.player.currentMediaItemIndex == 0 }
    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.await("the second book") { harness.player.currentMediaItemIndex == 1 }
    Thread.sleep(1_000L)

    assertThat(persisted)
      .describedAs("a programmatic apply must never be written back as a listener's choice")
      .isEmpty()
  }

  @Test
  fun aSpeedChangeMadeThroughThePlayerIsPersistedAgainstTheRightBook() = runBlocking {
    // The other half: a real change, from the control surface, must be stored. Two books so the
    // "right book" part is falsifiable.
    snapshot.refresh()
    val harness = startQueue(listOf("Test Book", "Tail Book"))
    harness.await("the first book") { harness.player.currentMediaItemIndex == 0 }

    harness.onMain { harness.player.setPlaybackSpeed(1.3f) }
    awaitValue("the speed to be persisted") { persisted[bookIdOf("Test Book")] == 1.3f }

    assertThat(persisted.keys).containsExactly(bookIdOf("Test Book"))
  }

  @Test
  fun silenceSkippingFollowsTheBookAndIsOffForMusic() = runBlocking {
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.0f, true))
    snapshot.refresh()

    val harness = startQueue(listOf("Test Book", "Track 1"))
    harness.await("the book") { harness.player.currentMediaItemIndex == 0 }
    val onBook = harness.onMain { harness.player.skipSilenceEnabled }
    harness.onMain { harness.player.seekToNextMediaItem() }
    harness.await("the song") { harness.player.currentMediaItemIndex == 1 }
    val onMusic = harness.onMain { harness.player.skipSilenceEnabled }

    // Two observations, two values. One alone would be satisfied by a constant.
    assertThat(listOf(onBook, onMusic)).containsExactly(true, false)
  }

  @Test
  fun theItemGrainSpeedColumnIsNotTheAuthorityForABook() = runBlocking {
    // `media_progress.speed` exists (spec section 3) and this plan does not write it. This makes
    // "does not write it" into "does not read it either", so a future change that wires the item
    // column back in goes red here rather than producing a book with two speeds.
    db.mediaProgressDao().upsert(
      MediaProgressEntity(songId("Test Book"), 0L, false, now, speed = 2.0f, skipSilence = true, gainDb = 0f),
    )
    db.bookSettingsDao().upsert(BookSettingsEntity(bookIdOf("Test Book"), 1.4f, false))
    snapshot.refresh()

    val harness = startBook("Test Book")
    harness.await("the book") { harness.player.currentMediaItemIndex == 0 }

    assertThat(harness.onMain { harness.player.playbackParameters.speed }).isEqualTo(1.4f)
    assertThat(harness.onMain { harness.player.skipSilenceEnabled }).isFalse
  }
}
```

`core/media/src/androidTest/kotlin/app/muplay/media/ProgressWriterSilenceSkipTest.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.database.MuPlayDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The debt Plan 3 recorded and this plan owes.**
 *
 * Plan 3 Task 8 Step 10, mutation 4: removing `ProgressWriter`'s
 * `DISCONTINUITY_REASON_SILENCE_SKIP` guard reddened **nothing**, because nothing in that plan
 * skipped silence. It named the audiobook plan as owing the assertion. This is it.
 *
 * Why the callback is synthesised rather than provoked with a silent fixture: the subject is
 * `ProgressWriter`'s `when` on `reason`, not Media3's silence detector. A synthesised callback
 * varies exactly the value under test and holds everything else constant, which is the rule; a
 * silent fixture would also be testing whether ExoPlayer decided to skip, and would need a fixture
 * built for it.
 *
 * The **positive control** is the whole design: the same call with a different `reason` must write.
 * Without it, "no row was written" is equally satisfied by a writer that never writes at all.
 */
@RunWith(AndroidJUnit4::class)
class ProgressWriterSilenceSkipTest {

  private lateinit var db: MuPlayDatabase
  private lateinit var scope: CoroutineScope
  private lateinit var writer: ProgressWriter
  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MuPlayDatabase::class.java)
      .build()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    writer = ProgressWriter(NoOpPlayer(), db.mediaProgressDao(), clock, scope)
  }

  @After
  fun tearDown() {
    scope.cancel()
    db.close()
  }

  private fun positionInfo(mediaId: String, positionMs: Long) = Player.PositionInfo(
    /* windowUid = */ null,
    /* mediaItemIndex = */ 0,
    /* mediaItem = */ MediaItem.Builder().setMediaId(mediaId).build(),
    /* periodUid = */ null,
    /* periodIndex = */ 0,
    /* positionMs = */ positionMs,
    /* contentPositionMs = */ positionMs,
    /* adGroupIndex = */ -1,
    /* adIndexInAdGroup = */ -1,
  )

  private fun rowFor(mediaId: String) = runBlocking { db.mediaProgressDao().find(mediaId) }

  private fun await(mediaId: String): Boolean {
    val deadline = System.currentTimeMillis() + 5_000L
    while (System.currentTimeMillis() < deadline) {
      if (rowFor(mediaId) != null) return true
      Thread.sleep(50)
    }
    return false
  }

  @Test
  fun skippingSilenceDoesNotInchTheBookForward() {
    // Silence skipping moves the position without the listener having moved. Recorded as progress,
    // a book creeps forward every time it skips a pause -- and a long book is mostly pauses.
    writer.onPositionDiscontinuity(
      positionInfo("chapter-14", 90_000L),
      positionInfo("chapter-14", 90_400L),
      Player.DISCONTINUITY_REASON_SILENCE_SKIP,
    )

    Thread.sleep(1_000L)
    assertThat(rowFor("chapter-14")).isNull()
  }

  @Test
  fun aSeekIsStillRecorded() {
    // The positive control, and the reason the test above means anything. Same call, same
    // arguments, one different `reason` -- and now a row must exist.
    writer.onPositionDiscontinuity(
      positionInfo("chapter-14", 90_000L),
      positionInfo("chapter-14", 30_000L),
      Player.DISCONTINUITY_REASON_SEEK,
    )

    assertThat(await("chapter-14")).describedAs("a seek must write the position it left").isTrue
    assertThat(rowFor("chapter-14")?.positionMs).isEqualTo(90_000L)
  }

  @Test
  fun anAutomaticTrackTransitionIsStillRecorded() {
    // A second positive control with a third `reason`, so "everything except SILENCE_SKIP writes"
    // is observed at two values rather than one.
    writer.onPositionDiscontinuity(
      positionInfo("part-two", 5_900L),
      positionInfo("part-three", 0L),
      Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
    )

    assertThat(await("part-two")).isTrue
    assertThat(rowFor("part-two")?.positionMs).isEqualTo(5_900L)
    // ...and it wrote the position of the item being LEFT, not the one being entered.
    assertThat(rowFor("part-three")).isNull()
  }
}
```

> `Player.PositionInfo`'s constructor arity changed across Media3 versions (an `adGroupIndex` pair
> was added, and `windowUid`/`periodUid` are `@Nullable Object`). Match the resolved 1.11.0
> signature exactly; if it takes a different argument list, supply what it asks for and change
> nothing else about the test.

- [ ] **Step 4: Run everything**

```bash
./gradlew :core:media:testDebugUnitTest
./gradlew :core:media:connectedDebugAndroidTest
```

Expected: PASS — `BookPlaybackSettingsTest` 4/4, `BookSpeedControllerTest` 9/9,
`ProgressWriterSilenceSkipTest` 3/3, and every Plan 3 suite still green (`MuPlayerTest`,
`ProgressWriterTest`, `AudioFocusTest`, `GaplessTest`).

**`GaplessTest` deserves a look rather than a glance.** It measures silence across a three-track
queue in PCM frames, and this task installs a listener that can change the speed and can enable
silence skipping. Neither applies to a music queue — `BookPlaybackSettings.MUSIC` is 1.0x with
skipping off — but if `GaplessTest` moved, the reset is not happening and the gapless number is now
measuring this task's bug.

- [ ] **Step 5: Prove it can fail**

One mutation at a time, reverted after each:

1. In `BookPlaybackSettings.of`, return the item's settings for `null` too (i.e. remove the `MUSIC`
   arm and default to `BookSettings.DEFAULT_SPEED` only when the item is null but keep
   `skipSilence` from a stale variable). Simpler: make `of(null)` return
   `BookPlaybackSettings(1.0f, skipSilence = true)`. Expect
   `anything that is not an audiobook plays at normal speed…` and
   `silenceSkippingFollowsTheBookAndIsOffForMusic` to fail.
2. Remove `BookSpeedController.onMediaItemTransition`'s call to `applyFor`. Expect
   `aBooksSpeedDoesNotFollowYouIntoMusic`, `goingBackToTheBookRestoresItsSpeed` and
   `twoBooksInOneQueueEachPlayAtTheirOwnSpeed` to fail. **This is the trap this task is named
   for**; watch all three go red.
3. Remove the `applying` guard. Expect
   `aTransitionBetweenTwoBooksDoesNotWriteEitherOnesSpeedOntoTheOther` to fail, and read what
   `persisted` actually contains — the value it wrote and the book it wrote it against are the
   whole story.
4. In `BookSpeedController.applyFor`, drop `player.skipSilenceEnabled = …`. Expect
   `silenceSkippingFollowsTheBookAndIsOffForMusic` to fail.
5. Restore `ProgressWriter`'s `DISCONTINUITY_REASON_SILENCE_SKIP` guard removal — the exact
   mutation Plan 3 Task 8 Step 10 item 4 performed and could not detect. Expect
   `skippingSilenceDoesNotInchTheBookForward` to fail. **Record this in the task report by name**:
   it is a debt from a previous plan, paid, and the record is what makes that visible.
6. In `BookSpeedController.applyFor`, read `source.itemFor(mediaId)?.speed` from
   `media_progress` instead (wire it to `MediaProgressDao`). Expect
   `theItemGrainSpeedColumnIsNotTheAuthorityForABook` to fail — the spec-defect guard, doing its
   job.

- [ ] **Step 6: Record the probes, re-measure, commit**

Mutation 1 is JVM-side (`BookSpeedController.kt` into `revert()`'s list). Mutations 2–6 are
device-side and go in the task report. Re-measure `:core:media`'s floors:
`BookPlaybackSettings` is a JVM floor, `BookSpeedController` is instrumented, and `PlaybackState`'s
new `isAudiobook` getter carries branches that need a floor of their own.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): a speed that belongs to the book, and stays with it"
```

---

## Task 8: The sleep timer — fade, end of chapter, shake to extend

**Files:**
- Create: `core/model/src/main/kotlin/app/muplay/model/SleepTimer.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/SleepTimerFade.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/SleepTimerController.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ShakeDetector.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/ShakeSensor.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/SleepTimerFadeTest.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/ShakeDetectorTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/SleepTimerControllerTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `PlayerHarness`, `RealTrackBytes` (Plan 3 Tasks 2, 3), `java.time.Clock` (Plan 3 Task 8),
  `BookTimeline.chapterAt` (Task 3) — used by the caller that builds an `UntilPosition` request,
  not by the controller.
- Produces:
  - `sealed interface SleepTimerRequest` with
    `data class Duration(val millis: Long)` and
    `data class UntilPosition(val mediaId: String, val positionMs: Long)`
  - `sealed interface SleepTimerState` with `data object Off` and
    `data class Running(val remainingMs: Long, val untilEndOfChapter: Boolean, val isFading: Boolean)`
  - `object SleepTimerFade` with `fun volumeFor(remainingMs: Long, fadeMs: Long): Float` and
    `const val DEFAULT_FADE_MS = 20_000L`
  - `class SleepTimerController internal constructor(clock: Clock, fadeMs: Long, graceMs: Long)`
    with a secondary `@Inject constructor(clock: Clock)`, and
    `val state: StateFlow<SleepTimerState>`, `fun attach(player: Player, scope: CoroutineScope)`,
    `fun detach()`, `fun start(request: SleepTimerRequest)`, `fun cancel()`,
    `fun extend(byMs: Long = EXTENSION_MS)`, `fun onShake()`,
    `companion object { const val TICK_MS = 250L; const val EXTENSION_MS = 300_000L;
    const val GRACE_MS = 60_000L; val PRESETS: List<Long> }`
  - `class ShakeDetector(thresholdG: Float, windowMs: Long, requiredPeaks: Int, minPeakGapMs: Long)`
    with `fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean`, `fun reset()`,
    and `companion object { const val DEFAULT_THRESHOLD_G = 2.2f; const val WINDOW_MS = 1_000L;
    const val REQUIRED_PEAKS = 3; const val MIN_PEAK_GAP_MS = 80L; const val GRAVITY = 9.80665f }`
  - `class ShakeSensor @Inject constructor(@ApplicationContext context)` with
    `fun start(onShake: () -> Unit)` and `fun stop()`

### What "sleep timer" has to mean before it can be built

Spec §5 says *"Sleep timer, with a shake-to-extend affordance"* and nothing else. Four decisions
that sentence does not make, made here and written into the spec by Task 10:

1. **It fades, it does not cut.** Audio dropping to silence mid-word wakes people up, which is the
   opposite of the feature. The last 20 s ramp the player's volume linearly to zero.
2. **It pauses; it does not stop.** Pausing goes through `ProgressWriter`'s persistence points
   (Plan 3 Task 8, points 1 and 2), so the position is written exactly as if the listener had
   pressed pause. Stopping the service would drop it.
3. **"End of chapter" is a position, not a duration.** For a single-file M4B that is the current
   chapter's `endMs`; for a ripped book it is the current file's end. The *caller* computes it from
   `BookTimeline`, so this class has one mechanism and no chapter knowledge.
4. **A shake extends, and it works for a minute after the timer fired.** Waking up just after the
   audio stopped is the ordinary case; requiring the shake to land before the deadline makes the
   feature useless exactly when it is needed.

### The trap: a fade that never fades back

`player.volume` is player state. Ramp it to 0, pause, and then **the next thing the listener plays
is silent** — no error, no indication, and the only recovery is reinstalling the app. It is the
single worst bug available in this task and it is one forgotten line.

So: the volume is restored **at every exit** — on expiry (after the pause), on `cancel`, on
`extend`, and on `detach`. `SleepTimerControllerTest` asserts `player.volume` is back to `1.0f`
after the timer has fired, and separately that audio actually plays afterwards.

- [ ] **Step 1: Write the failing Tier 1 tests**

`core/media/src/test/kotlin/app/muplay/media/SleepTimerFadeTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class SleepTimerFadeTest {

  private val tolerance = Offset.offset(0.001f)

  @Test
  fun `outside the fade window the volume is untouched`() {
    // Two observations well outside, so "returns 1.0 always" is not yet distinguished -- the tests
    // below do that.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 120_000L, fadeMs = 20_000L)).isEqualTo(1.0f)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 20_001L, fadeMs = 20_000L)).isEqualTo(1.0f)
  }

  @Test
  fun `inside the fade window the volume falls linearly`() {
    // Four distinct values from one fade length. A constant satisfies at most one of them, and a
    // step function satisfies at most two.
    val volumes = listOf(20_000L, 15_000L, 10_000L, 5_000L, 0L)
      .map { SleepTimerFade.volumeFor(it, fadeMs = 20_000L) }

    assertThat(volumes[0]).isCloseTo(1.0f, tolerance)
    assertThat(volumes[1]).isCloseTo(0.75f, tolerance)
    assertThat(volumes[2]).isCloseTo(0.5f, tolerance)
    assertThat(volumes[3]).isCloseTo(0.25f, tolerance)
    assertThat(volumes[4]).isCloseTo(0.0f, tolerance)
  }

  @Test
  fun `the fade length is a parameter and it moves the answer`() {
    // Same remaining time, two fade lengths, two volumes. Without this, `fadeMs` could be ignored
    // entirely and every assertion above would still pass.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 20_000L)).isCloseTo(0.25f, tolerance)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 10_000L)).isCloseTo(0.5f, tolerance)
  }

  @Test
  fun `a negative remaining time is silence, not a negative volume`() {
    // The timer ticks every 250 ms, so the last observation before expiry is routinely past zero.
    // `player.volume` rejects negatives, and it does so by throwing.
    assertThat(SleepTimerFade.volumeFor(remainingMs = -3_000L, fadeMs = 20_000L)).isZero
  }

  @Test
  fun `a fade length of zero does not divide by zero`() {
    // Reachable from a caller that turns the fade off. `x / 0f` is `Infinity` or `NaN`, and both
    // reach `player.volume`, which throws for one and behaves unpredictably for the other.
    assertThat(SleepTimerFade.volumeFor(remainingMs = 5_000L, fadeMs = 0L)).isEqualTo(1.0f)
    assertThat(SleepTimerFade.volumeFor(remainingMs = 0L, fadeMs = 0L)).isEqualTo(0.0f)
  }
}
```

`core/media/src/test/kotlin/app/muplay/media/ShakeDetectorTest.kt`:

```kotlin
package app.muplay.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Shake detection over real accelerometer numbers, with no `SensorManager` anywhere.
 *
 * That split is what makes this testable at all: `SensorEvent` has no public constructor, so a
 * detector that took one could only be tested by shaking a physical phone. `ShakeSensor` is the
 * three-line adapter that unpacks the event; everything that can be wrong is here.
 *
 * A phone lying still reads ~9.81 m/s^2 on one axis — 1 g — which is why the threshold is in g and
 * why "at rest" is the first test in the file.
 */
class ShakeDetectorTest {

  private fun detector() = ShakeDetector()

  /** A resting phone: 1 g straight down, nothing on the other axes. */
  private fun rest(detector: ShakeDetector, atMs: Long) =
    detector.onSample(0f, 0f, ShakeDetector.GRAVITY, atMs)

  /** A jolt: 3 g. */
  private fun jolt(detector: ShakeDetector, atMs: Long) =
    detector.onSample(0f, 0f, 3f * ShakeDetector.GRAVITY, atMs)

  @Test
  fun `a phone lying on a bedside table never fires`() {
    val subject = detector()

    val fired = (0..100).map { rest(subject, it * 20L) }

    // The exact list, not `noneMatch`: `noneMatch` over an empty list is true, and a detector that
    // consumed no samples would produce one.
    assertThat(fired).hasSize(101)
    assertThat(fired).containsOnly(false)
  }

  @Test
  fun `two jolts are not a shake`() {
    val subject = detector()

    assertThat(jolt(subject, 0L)).isFalse
    assertThat(jolt(subject, 200L)).isFalse
  }

  @Test
  fun `three jolts inside the window are a shake`() {
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 200L)

    assertThat(jolt(subject, 400L)).isTrue
  }

  @Test
  fun `three jolts spread beyond the window are not`() {
    // Picking the phone up, putting it down, and picking it up again over three seconds is not a
    // shake. Same three samples as the test above, moved apart -- so the window is what is being
    // asserted, not the count.
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 900L)

    assertThat(jolt(subject, 1_800L)).isFalse
  }

  @Test
  fun `two samples from the same jolt do not count twice`() {
    // An accelerometer at 100 Hz produces several samples above threshold per physical jolt.
    // Without a minimum gap, one sharp knock is three peaks and every knock is a shake.
    val subject = detector()

    jolt(subject, 0L)
    jolt(subject, 10L)
    jolt(subject, 20L)

    assertThat(jolt(subject, 30L)).isFalse
  }

  @Test
  fun `a second shake is detected after the first`() {
    // Without a reset on fire, the peak buffer keeps every old peak and the next single jolt fires
    // -- so one shake makes the phone hair-trigger for as long as the app runs.
    val subject = detector()
    jolt(subject, 0L)
    jolt(subject, 200L)
    assertThat(jolt(subject, 400L)).isTrue

    jolt(subject, 2_000L)
    jolt(subject, 2_200L)
    assertThat(jolt(subject, 2_400L)).isTrue
    // ...and one jolt on its own still is not a shake.
    assertThat(jolt(subject, 4_000L)).isFalse
  }

  @Test
  fun `the threshold is a parameter and it moves the answer`() {
    // The same three samples, two thresholds, two answers. Without this, `thresholdG` could be
    // ignored and every test above would still pass.
    val gentle = 1.5f * ShakeDetector.GRAVITY
    val sensitive = ShakeDetector(thresholdG = 1.2f)
    val strict = ShakeDetector(thresholdG = 2.5f)

    listOf(0L, 200L).forEach { sensitive.onSample(0f, 0f, gentle, it); strict.onSample(0f, 0f, gentle, it) }

    assertThat(sensitive.onSample(0f, 0f, gentle, 400L)).isTrue
    assertThat(strict.onSample(0f, 0f, gentle, 400L)).isFalse
  }

  @Test
  fun `the magnitude uses all three axes`() {
    // A shake along x is a shake. A detector reading only z -- easy to write, and correct-looking
    // because a resting phone's gravity is on z -- fails here and passes every other test in this
    // file, because every other test jolts z.
    val subject = detector()

    subject.onSample(3f * ShakeDetector.GRAVITY, 0f, 0f, 0L)
    subject.onSample(0f, 3f * ShakeDetector.GRAVITY, 0f, 200L)

    assertThat(subject.onSample(3f * ShakeDetector.GRAVITY, 0f, 0f, 400L)).isTrue
  }
}
```

- [ ] **Step 2: Implement the pure halves**

`core/model/src/main/kotlin/app/muplay/model/SleepTimer.kt`:

```kotlin
package app.muplay.model

/**
 * What the listener asked the sleep timer to do.
 *
 * [UntilPosition] rather than an "end of chapter" case: the *caller* knows where the chapter ends
 * (`BookTimeline`), and giving the timer chapter knowledge would mean a second component deciding
 * what a chapter is. One mechanism, one owner.
 */
sealed interface SleepTimerRequest {
  data class Duration(val millis: Long) : SleepTimerRequest
  data class UntilPosition(val mediaId: String, val positionMs: Long) : SleepTimerRequest
}

/** What the sleep timer is doing, as the UI sees it. */
sealed interface SleepTimerState {
  data object Off : SleepTimerState

  data class Running(
    val remainingMs: Long,
    val untilEndOfChapter: Boolean,
    val isFading: Boolean,
  ) : SleepTimerState
}
```

`core/media/src/main/kotlin/app/muplay/media/SleepTimerFade.kt`:

```kotlin
package app.muplay.media

/**
 * How loud the player should be, given how long the sleep timer has left.
 *
 * Audio cutting to silence mid-word wakes people up, which is the opposite of what a sleep timer is
 * for. The last stretch ramps down instead.
 */
object SleepTimerFade {

  const val DEFAULT_FADE_MS = 20_000L

  fun volumeFor(remainingMs: Long, fadeMs: Long = DEFAULT_FADE_MS): Float = when {
    // A caller can turn the fade off, and `x / 0f` is Infinity or NaN -- both of which reach
    // `player.volume`, which throws for one and does something unpredictable for the other.
    fadeMs <= 0L -> if (remainingMs <= 0L) 0f else 1f
    remainingMs >= fadeMs -> 1f
    // The tick lands past zero routinely; a negative volume throws.
    remainingMs <= 0L -> 0f
    else -> remainingMs.toFloat() / fadeMs
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/ShakeDetector.kt`:

```kotlin
package app.muplay.media

/**
 * "That was a shake", from raw accelerometer samples.
 *
 * No `SensorManager`, no `SensorEvent`, no Android at all — which is the only reason this is
 * testable: `SensorEvent` has no public constructor, so a detector that took one could be tested
 * only by shaking a physical phone. [ShakeSensor] is the adapter that unpacks the event and does
 * nothing else.
 *
 * Three things it has to get right, each of which is a real false positive otherwise:
 *
 * - **All three axes.** A phone shaken along x is shaken. Reading only z looks correct because a
 *   resting phone's gravity is on z.
 * - **A minimum gap between peaks.** A 100 Hz accelerometer produces several above-threshold
 *   samples per physical jolt, so without a gap one sharp knock is three peaks.
 * - **A reset after firing.** Otherwise the peak buffer keeps every old peak and the next single
 *   jolt fires, leaving the phone hair-trigger for the life of the process.
 */
class ShakeDetector(
  private val thresholdG: Float = DEFAULT_THRESHOLD_G,
  private val windowMs: Long = WINDOW_MS,
  private val requiredPeaks: Int = REQUIRED_PEAKS,
  private val minPeakGapMs: Long = MIN_PEAK_GAP_MS,
) {

  private val peaks = ArrayDeque<Long>()

  fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
    val magnitudeG = kotlin.math.sqrt(x * x + y * y + z * z) / GRAVITY
    if (magnitudeG < thresholdG) return false
    if (peaks.isNotEmpty() && timestampMs - peaks.last() < minPeakGapMs) return false

    peaks.addLast(timestampMs)
    while (peaks.isNotEmpty() && timestampMs - peaks.first() > windowMs) peaks.removeFirst()

    if (peaks.size < requiredPeaks) return false
    reset()
    return true
  }

  fun reset() = peaks.clear()

  companion object {
    /** 2.2 g: comfortably above a pocket jostle, comfortably below a deliberate shake. */
    const val DEFAULT_THRESHOLD_G = 2.2f
    const val WINDOW_MS = 1_000L
    const val REQUIRED_PEAKS = 3
    const val MIN_PEAK_GAP_MS = 80L

    /** Standard gravity, so the threshold is in a unit a human can reason about. */
    const val GRAVITY = 9.80665f
  }
}
```

- [ ] **Step 3: Run the Tier 1 pair**

Run: `./gradlew :core:media:testDebugUnitTest --tests '*SleepTimerFadeTest*' --tests '*ShakeDetectorTest*'`
Expected: PASS — `SleepTimerFadeTest` 5/5, `ShakeDetectorTest` 8/8.

- [ ] **Step 4: Write the failing controller test**

`core/media/src/androidTest/kotlin/app/muplay/media/SleepTimerControllerTest.kt`:

```kotlin
package app.muplay.media

// The same imports as `AudiobookResumeTest`, plus:
//   import app.muplay.model.SleepTimerRequest
//   import app.muplay.model.SleepTimerState

/**
 * The countdown, against a real player playing real audio.
 *
 * Short durations and a short fade, both passed in: the controller takes `fadeMs` as a constructor
 * argument precisely so a test can use 1 s rather than 20, and so that the fade length is a value
 * observed at more than one value.
 *
 * **The assertion this class exists for is the volume restore.** Ramping to zero and forgetting to
 * ramp back leaves the app permanently silent — no error, no indication, and the only recovery a
 * listener has is reinstalling it.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerControllerTest {

  /**
   * The **real** clock, unlike every other device suite in this plan: this is a real countdown, and
   * a fixed clock would mean it never counts down.
   */
  private val clock: Clock = Clock.systemUTC()

  private lateinit var fixture: BookPlaybackHarness

  @Before
  fun setUp() {
    fixture = BookPlaybackHarness.create(clock)
  }

  @After
  fun tearDown() = fixture.close()

  private fun startBook(title: String): PlayerHarness = fixture.startPlain(listOf(title))

  private fun songId(title: String) = fixture.songId(title)

  /** One second of fade, not twenty: `fadeMs` is a constructor argument for exactly this reason. */
  private fun timer(fadeMs: Long = 1_000L): SleepTimerController =
    SleepTimerController(clock, fadeMs, SleepTimerController.GRACE_MS)
      .also { it.attach(fixture.player!!.player, fixture.scope) }

  private fun awaitOnMain(description: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
    val harness = fixture.player!!
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (harness.onMain(condition)) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $description")
  }

  @Test
  fun theTimerPausesPlaybackWhenItRunsOut() = runBlocking {
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(3_000L))

    // Two observations, before and after. "It is paused" alone is satisfied by a timer that fired
    // immediately, which is a real bug and a very annoying one.
    Thread.sleep(1_500L)
    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("still playing halfway through a three-second timer").isTrue
    awaitOnMain("playback to pause", timeoutMs = 5_000L) { !harness.player.isPlaying }
  }

  @Test
  fun theVolumeComesBackAfterTheTimerFires() = runBlocking {
    // The trap. One forgotten line here leaves every later playback silent, with no error anywhere.
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(3_000L))
    awaitOnMain("playback to pause", timeoutMs = 8_000L) { !harness.player.isPlaying }

    assertThat(harness.onMain { harness.player.volume }).isEqualTo(1.0f)
    // ...and it is not merely the number: audio must actually come back.
    val before = harness.onMain { harness.player.currentPosition }
    harness.onMain { harness.player.play() }
    harness.awaitPositionAtLeast(before + 1_000L, timeoutMs = 6_000L)
  }

  @Test
  fun theVolumeActuallyGoesDownBeforeItFires() = runBlocking {
    // The control for the test above. Without it, "volume is 1.0 at the end" is satisfied by a
    // timer that never faded at all, and the fade would be untested.
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer(fadeMs = 2_000L)

    subject.start(SleepTimerRequest.Duration(2_500L))
    awaitOnMain("the fade to start", timeoutMs = 4_000L) { harness.player.volume < 0.9f }

    assertThat(harness.onMain { harness.player.volume }).isBetween(0.0f, 0.9f)
  }

  @Test
  fun cancellingStopsTheCountdownAndRestoresTheVolume() = runBlocking {
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(3_000L))
    Thread.sleep(2_200L)
    subject.cancel()
    Thread.sleep(2_000L)

    assertThat(harness.onMain { harness.player.isPlaying })
      .describedAs("a cancelled timer must not fire").isTrue
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(1.0f)
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun extendingPushesTheDeadlineOut() = runBlocking {
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(3_000L))
    Thread.sleep(2_200L)
    subject.extend(byMs = 4_000L)
    Thread.sleep(2_000L)

    // Past the original deadline, still playing. And the volume came back, because the extend
    // happened during the fade.
    assertThat(harness.onMain { harness.player.isPlaying }).isTrue
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(1.0f)
  }

  @Test
  fun aShakeJustAfterItFiredResumesPlayback() = runBlocking {
    // Waking up a moment after the audio stopped is the ordinary case. A timer that only accepted
    // a shake *before* the deadline would be useless exactly when it is wanted.
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(2_000L))
    awaitOnMain("playback to pause", timeoutMs = 6_000L) { !harness.player.isPlaying }

    subject.onShake()

    awaitOnMain("playback to resume", timeoutMs = 5_000L) { harness.player.isPlaying }
    assertThat(subject.state.value).isInstanceOf(SleepTimerState.Running::class.java)
  }

  @Test
  fun aShakeLongAfterItFiredDoesNothing() = runBlocking {
    // The control for the grace period. Without it, "a shake resumes" is true forever, and picking
    // the phone up the next morning restarts the audiobook.
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = SleepTimerController(clock, fadeMs = 1_000L, graceMs = 500L)
      .also { it.attach(harness.player, fixture.scope) }

    subject.start(SleepTimerRequest.Duration(2_000L))
    awaitOnMain("playback to pause", timeoutMs = 6_000L) { !harness.player.isPlaying }
    Thread.sleep(1_500L)

    subject.onShake()

    Thread.sleep(1_000L)
    assertThat(harness.onMain { harness.player.isPlaying }).isFalse
    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun endOfChapterPausesAtThePositionRatherThanAtAWallClockTime() = runBlocking {
    // Second Book's third chapter ends at 15 000 ms. Started from 12 000, that is three seconds of
    // media -- and the assertion is on the POSITION, so a timer that happened to fire after three
    // seconds of wall clock for the wrong reason would still have to land in the right place.
    val harness = startBook("Second Book")
    harness.onMain { harness.player.seekTo(12_000L) }
    harness.awaitPositionAtLeast(12_100L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.UntilPosition(songId("Second Book"), positionMs = 15_000L))

    awaitOnMain("playback to pause at the chapter end", timeoutMs = 10_000L) { !harness.player.isPlaying }
    assertThat(harness.onMain { harness.player.currentPosition }).isBetween(14_500L, 16_000L)
    assertThat(harness.onMain { harness.player.volume }).isEqualTo(1.0f)
  }

  @Test
  fun theStateCountsDownAndThenGoesOff() = runBlocking {
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    assertThat(subject.state.value).isEqualTo(SleepTimerState.Off)
    subject.start(SleepTimerRequest.Duration(3_000L))

    val first = (subject.state.value as SleepTimerState.Running).remainingMs
    Thread.sleep(1_200L)
    val second = (subject.state.value as SleepTimerState.Running).remainingMs

    // Two readings, strictly decreasing. A state holding a constant "remaining" satisfies a single
    // reading, and the countdown on screen would never move.
    assertThat(second).isLessThan(first)
    awaitOnMain("the timer to go off", timeoutMs = 6_000L) { subject.state.value == SleepTimerState.Off }
  }

  @Test
  fun theEndOfChapterFlagIsCarriedSoTheUiCanSayWhichKindItIs() = runBlocking {
    val harness = startBook("Second Book")
    harness.awaitPositionAtLeast(500L, timeoutMs = 15_000L)
    val subject = timer()

    subject.start(SleepTimerRequest.Duration(60_000L))
    assertThat((subject.state.value as SleepTimerState.Running).untilEndOfChapter).isFalse

    subject.start(SleepTimerRequest.UntilPosition(songId("Second Book"), 20_000L))
    assertThat((subject.state.value as SleepTimerState.Running).untilEndOfChapter).isTrue
  }
}
```

- [ ] **Step 5: Implement the controller and the sensor shim**

`core/media/src/main/kotlin/app/muplay/media/SleepTimerController.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.Player
import app.muplay.model.SleepTimerRequest
import app.muplay.model.SleepTimerState
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sleep timer: it fades, it pauses, and a shake brings it back.
 *
 * Spec section 5 asks for *"a sleep timer, with a shake-to-extend affordance"* and specifies
 * nothing else. The four decisions behind this implementation are in the task's own notes and in
 * spec section 5 after Task 10 writes them there. The ones that show up in this code:
 *
 * - **It pauses, it does not stop.** Pausing runs through `ProgressWriter`'s persistence points, so
 *   the position is written exactly as if the listener had pressed pause. Stopping would drop it.
 * - **The volume is restored at every exit** — expiry, cancel, extend, detach. Ramping to zero and
 *   forgetting to ramp back leaves the app permanently silent with no error anywhere, and it is one
 *   forgotten line.
 * - **A shake works for [graceMs] after the timer fired**, because waking up just after the audio
 *   stopped is the ordinary case rather than the exception.
 *
 * [fadeMs] and [graceMs] are constructor arguments rather than constants so a test can run a real
 * countdown in seconds — and so that neither is a value observed at exactly one value.
 */
@Singleton
class SleepTimerController internal constructor(
  private val clock: Clock,
  private val fadeMs: Long,
  private val graceMs: Long,
) {

  /**
   * The constructor Hilt uses.
   *
   * **Not** default parameter values on the primary constructor: Hilt ignores Kotlin defaults and
   * would try to find a binding for `Long`, which fails at build time with a message about an
   * unbound `java.lang.Long` that reads like a Dagger bug rather than like this. A secondary
   * `@Inject` constructor is the shape that works, and it keeps the two timings injectable from a
   * test — which is what stops `fadeMs` and `graceMs` being values observed at exactly one value.
   */
  @Inject
  constructor(clock: Clock) : this(clock, SleepTimerFade.DEFAULT_FADE_MS, GRACE_MS)

  private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
  val state: StateFlow<SleepTimerState> = _state.asStateFlow()

  private var player: Player? = null
  private var scope: CoroutineScope? = null
  private var ticker: Job? = null

  private var deadlineEpochMs: Long? = null
  private var stopAtPositionMs: Long? = null
  private var stopForMediaId: String? = null
  private var firedAtEpochMs: Long? = null

  fun attach(player: Player, scope: CoroutineScope) {
    this.player = player
    this.scope = scope
  }

  fun detach() {
    cancel()
    player = null
    scope = null
  }

  fun start(request: SleepTimerRequest) {
    firedAtEpochMs = null
    when (request) {
      is SleepTimerRequest.Duration -> {
        deadlineEpochMs = clock.millis() + request.millis
        stopAtPositionMs = null
        stopForMediaId = null
      }
      is SleepTimerRequest.UntilPosition -> {
        deadlineEpochMs = null
        stopAtPositionMs = request.positionMs
        stopForMediaId = request.mediaId
      }
    }
    restoreVolume()
    publish()
    startTicking()
  }

  fun cancel() {
    ticker?.cancel()
    ticker = null
    deadlineEpochMs = null
    stopAtPositionMs = null
    stopForMediaId = null
    firedAtEpochMs = null
    restoreVolume()
    _state.value = SleepTimerState.Off
  }

  fun extend(byMs: Long = EXTENSION_MS) {
    val resuming = firedAtEpochMs != null
    firedAtEpochMs = null
    when {
      stopAtPositionMs != null -> stopAtPositionMs = (stopAtPositionMs ?: 0L) + byMs
      // Extending after it fired counts from now, not from a deadline already in the past.
      else -> deadlineEpochMs = maxOf(deadlineEpochMs ?: 0L, clock.millis()) + byMs
    }
    restoreVolume()
    if (resuming) player?.play()
    publish()
    startTicking()
  }

  /** The shake affordance. Ignored unless a timer is running or fired within [graceMs]. */
  fun onShake() {
    val running = deadlineEpochMs != null || stopAtPositionMs != null
    val recentlyFired = firedAtEpochMs?.let { clock.millis() - it <= graceMs } == true
    if (!running && !recentlyFired) return
    if (firedAtEpochMs != null && !recentlyFired) return
    extend()
  }

  private fun startTicking() {
    ticker?.cancel()
    val scope = scope ?: return
    ticker = scope.launch {
      while (true) {
        tick()
        delay(TICK_MS)
      }
    }
  }

  private fun tick() {
    val player = player ?: return
    val remaining = remainingMs(player) ?: return
    if (remaining <= 0L) {
      fire(player)
      return
    }
    player.volume = SleepTimerFade.volumeFor(remaining, fadeMs)
    publish(remaining)
  }

  private fun fire(player: Player) {
    ticker?.cancel()
    ticker = null
    // Pause first, restore second: the listener must never see the volume jump back up on audio
    // that is still playing.
    player.pause()
    restoreVolume()
    firedAtEpochMs = clock.millis()
    deadlineEpochMs = null
    stopAtPositionMs = null
    stopForMediaId = null
    _state.value = SleepTimerState.Off
  }

  private fun remainingMs(player: Player): Long? {
    deadlineEpochMs?.let { return it - clock.millis() }
    val stopAt = stopAtPositionMs ?: return null
    // A transition to another file ends an "until this position" timer: the position it named
    // belongs to a file that is no longer playing.
    if (stopForMediaId != null && player.currentMediaItem?.mediaId != stopForMediaId) return 0L
    // Divided by the speed, because at 2x the remaining *media* is half the remaining wall clock,
    // and the fade is a wall-clock ramp.
    val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
    return ((stopAt - player.currentPosition) / speed).toLong()
  }

  private fun publish(remaining: Long? = null) {
    val player = player ?: return
    val left = remaining ?: remainingMs(player) ?: return
    _state.value = SleepTimerState.Running(
      remainingMs = left.coerceAtLeast(0L),
      untilEndOfChapter = stopAtPositionMs != null,
      isFading = left <= fadeMs,
    )
  }

  private fun restoreVolume() {
    player?.volume = 1f
  }

  companion object {
    const val TICK_MS = 250L

    /** One shake buys five more minutes. */
    const val EXTENSION_MS = 300_000L

    /** How long after firing a shake still counts as "no, keep going". */
    const val GRACE_MS = 60_000L

    /** What the UI offers, in minutes. */
    val PRESETS: List<Long> = listOf(5, 10, 15, 30, 45, 60).map { it * 60_000L }
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/ShakeSensor.kt`:

```kotlin
package app.muplay.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three lines that turn a `SensorEvent` into three floats, and nothing else.
 *
 * Everything that can be wrong about shake detection is in [ShakeDetector], which has no Android in
 * it and is gated in Tier 1. This class exists because `SensorEvent` has no public constructor, so
 * any logic living here could be tested only by shaking a physical phone.
 *
 * `TYPE_ACCELEROMETER` includes gravity, which is why the detector's threshold is in g and why a
 * resting phone reads 1. `SENSOR_DELAY_UI` is deliberate: `SENSOR_DELAY_GAME` and `_FASTEST` wake
 * the CPU far more often for a feature whose whole purpose is to run while someone falls asleep.
 * A device with no accelerometer — rare, but they exist — silently has no shake affordance rather
 * than crashing.
 */
@Singleton
class ShakeSensor @Inject constructor(@ApplicationContext private val context: Context) {

  private val manager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  private val detector = ShakeDetector()
  private var listener: SensorEventListener? = null

  fun start(onShake: () -> Unit) {
    val manager = manager ?: return
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    if (listener != null) return
    detector.reset()
    listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        if (detector.onSample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L)) {
          onShake()
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
  }

  fun stop() {
    listener?.let { manager?.unregisterListener(it) }
    listener = null
  }
}
```

`MuPlaybackService` — attach the timer and register the sensor only while a book is playing:

```kotlin
    sleepTimer.attach(player, serviceScope)
    // The accelerometer is registered only while a timer is running: a sensor listener held for the
    // life of the service wakes the CPU for a feature nobody switched on, which is the opposite of
    // what a sleep timer is for.
    serviceScope.launch {
      sleepTimer.state.collect { state ->
        if (state is SleepTimerState.Running) shakeSensor.start { sleepTimer.onShake() }
        else shakeSensor.stop()
      }
    }
```

with `sleepTimer.detach()` and `shakeSensor.stop()` in `onDestroy`.

- [ ] **Step 6: Run the controller suite**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*SleepTimerControllerTest*'`
Expected: PASS, 10/10.

This suite runs several real countdowns and costs roughly forty seconds of wall clock on its own.
**Record the measured time** — Task 10 has to fit the emulator job inside its timeout, and a number
measured now is worth more than one estimated then.

- [ ] **Step 7: Prove it can fail**

One mutation at a time, reverted after each:

1. Delete `restoreVolume()` from `fire`. Expect `theVolumeComesBackAfterTheTimerFires` to fail —
   **on both assertions**, the number and the audio. This is the worst bug in the task and it is
   one line.
2. Delete `restoreVolume()` from `extend`. Expect `extendingPushesTheDeadlineOut` to fail on the
   volume while still passing on `isPlaying`, which is the point: a partially-restored timer looks
   like it worked.
3. In `tick`, drop the `player.volume = …` line. Expect `theVolumeActuallyGoesDownBeforeItFires` to
   fail and `theVolumeComesBackAfterTheTimerFires` to **pass** — which is exactly why the fade has
   its own test rather than being implied by the restore.
4. In `fire`, call `player.stop()` instead of `player.pause()`. Expect
   `theVolumeComesBackAfterTheTimerFires` to fail at the "audio must actually come back" step, and
   record what `ProgressWriter` wrote — `STATE_IDLE` is one of spec §3's persistence points, so the
   position is *still* recorded, and the visible damage is the queue rather than the position.
5. In `onShake`, drop the `recentlyFired` arm. Expect `aShakeJustAfterItFiredResumesPlayback` to
   fail.
6. In `onShake`, drop the grace *bound* (accept any shake after firing). Expect
   `aShakeLongAfterItFiredDoesNothing` to fail.
7. In `remainingMs`, drop the `/ speed` division. Expect **no test to fail**, because every timer
   test here runs at 1.0×. **Record it as a known ungated line** and add the assertion rather than
   pretending: set the book to 2.0× before an `UntilPosition` timer and assert it still pauses at
   the named position. Do this — an honest note is second best to a test that exists.
8. In `SleepTimerFade.volumeFor`, remove the `fadeMs <= 0L` arm. Expect
   `a fade length of zero does not divide by zero` to fail.

- [ ] **Step 8: Record the probes, re-measure, commit**

Mutation 8 is JVM-side; add `SleepTimerFade.kt` and `ShakeDetector.kt` to `revert()`'s file list and
add probes for `volumeFor`'s guard arms and for `ShakeDetector`'s minimum gap and reset. The rest go
in the task report.

Floors: `SleepTimerFade` and `ShakeDetector` are JVM floors; `SleepTimerController` and
`ShakeSensor` are instrumented. `ShakeSensor` has two early returns that no emulator test reaches
(no `SensorManager`, no accelerometer) — gate it on **LINE** at a measured floor rather than
inventing a BRANCH one it cannot meet, and say so in the table.

```bash
git add core/model core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(media): a sleep timer that fades, and a shake that brings it back"
```

---

## Task 9: `:feature:book` — the shelf, the book, and an audiobook player

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `feature/book/build.gradle.kts`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookPlaybackLauncher.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookshelfUiState.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookshelfViewModel.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookshelfScreen.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookUiState.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookViewModel.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookScreen.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookPlayerUiState.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookPlayerViewModel.kt`
- Create: `feature/book/src/main/kotlin/app/muplay/book/BookPlayerScreen.kt`
- Create: `app/src/main/kotlin/app/muplay/ui/navigation/BookshelfRoute.kt`, `BookRoute.kt`, `BookPlayerRoute.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Modify: `app/build.gradle.kts`
- Test: `feature/book/src/test/kotlin/app/muplay/book/BookPlayerUiStateTest.kt`
- Test: `feature/book/src/test/kotlin/app/muplay/book/StartIndexTest.kt`
- Test: `feature/book/src/test/kotlin/app/muplay/book/BookshelfUiStateTest.kt`
- Test: `feature/book/src/androidTest/kotlin/app/muplay/book/BookshelfScreenTest.kt`
- Test: `feature/book/src/androidTest/kotlin/app/muplay/book/BookPlayerScreenTest.kt`
- Modify: `app/src/test/kotlin/app/muplay/ConventionTest.kt` (nothing to change if the floors table
  gains `:feature:book` — confirm, do not assume)

**Interfaces:**
- Consumes: `AudiobookRepository` (Task 4), `ChapterRepository` (Task 3), `BookTimeline`,
  `BookFile`, `BookChapter` (Task 3), `SleepTimerController`, `SleepTimerRequest`,
  `SleepTimerState` (Task 8), `AudiobookSnapshot` (Task 6), `PlaybackConnection`, `PlaybackState`
  (Plan 3 Task 5 + Task 7), `QueueRepository`, `PlaybackQueue` (Plan 3 Tasks 4, 6),
  `CoverArtImage`, `coverArtCacheKey` (Plan 2 Task 9), `MuPlayApp`, `LibraryRoute`, `PlayerRoute`
  (Plan 2 Task 10, Plan 3 Task 9), `MiniPlayer` (Plan 3 Task 9), `BookSettings` (Task 2).
- Produces:
  - `class BookPlaybackLauncher @Inject constructor(audiobookRepository, queueRepository, playbackConnection, audiobookSnapshot)`
    with `suspend fun resume(bookId: String)`, `suspend fun playFile(bookId: String, mediaId: String)`,
    `suspend fun restart(bookId: String)`
  - `internal fun startIndexFor(files: List<Song>, resumeAt: ResumePoint?): Int`
  - `internal fun startIndexFor(files: List<Song>, mediaId: String): Int`
  - sealed `BookshelfUiState` — `Loading`, `Empty`, `Content(books: List<BookSummary>)`
  - `internal fun bookshelfUiState(books: List<BookSummary>?): BookshelfUiState`
  - `@HiltViewModel class BookshelfViewModel` with `uiState: StateFlow<BookshelfUiState>`,
    `fun resume(bookId: String)`
  - `BookshelfScreen(onBookClick: (String) -> Unit, onOpenPlayer: () -> Unit, modifier, viewModel)`
  - sealed `BookUiState` — `Loading`, `NotFound`, `Content(book: BookSummary, chapters: List<BookChapter>, settings: BookSettings)`
  - `@HiltViewModel class BookViewModel` with `uiState: StateFlow<BookUiState>`, `fun resume()`,
    `fun restart()`, `fun playChapter(chapter: BookChapter)`, `fun setSpeed(speed: Float)`,
    `fun setSkipSilence(enabled: Boolean)`
  - `BookScreen(onOpenPlayer: () -> Unit, modifier, viewModel)`
  - sealed `BookPlayerUiState` — `NothingPlaying`, `Content(...)` (fields below)
  - `internal fun bookPlayerUiState(playback, book, timeline, settings, sleepTimer): BookPlayerUiState`
  - `internal fun formatClock(millis: Long): String`, `internal fun formatRemaining(millis: Long): String`
  - `@HiltViewModel class BookPlayerViewModel` with `uiState: StateFlow<BookPlayerUiState>`,
    `fun playPause()`, `fun nextChapter()`, `fun previousChapter()`, `fun seekTo(chapter: BookChapter)`,
    `fun nudge(byMs: Long)`, `fun setSpeed(speed: Float)`, `fun startSleepTimer(request: SleepTimerRequest)`,
    `fun cancelSleepTimer()`, `fun endOfChapterTimer()`
  - `BookPlayerScreen(modifier, viewModel)`
  - label constants: `BOOKSHELF_TITLE`, `CONTINUE_LISTENING_LABEL`, `RESUME_LABEL`,
    `START_OVER_LABEL`, `NEXT_CHAPTER_LABEL`, `PREVIOUS_CHAPTER_LABEL`, `SPEED_LABEL`,
    `SLEEP_TIMER_LABEL`, `END_OF_CHAPTER_LABEL`, `CANCEL_TIMER_LABEL`, `BACK_30_LABEL`,
    `FORWARD_30_LABEL`
  - `@Serializable data object BookshelfRoute : NavKey`, `data class BookRoute(val bookId: String) : NavKey`,
    `data object BookPlayerRoute : NavKey`

### Why an audiobook player is a different instrument

A music player is transport plus a scrubber. A book player is a different set of controls entirely,
and the difference is not cosmetic: **next/previous mean chapter, not track**; a ±30 s nudge matters
more than a scrubber; the speed control is used constantly; the sleep timer lives here; and the
progress a listener cares about is *"three hours left in this book"*, not *"1:42 of 4:03"*.

So `:feature:book` ships its own player rather than adding modes to Plan 3's `PlayerScreen`, and
`:app` chooses between them from `PlaybackState.isAudiobook` (Task 7). **`:feature:book` does not
depend on `:feature:player` and `:feature:player` does not depend on `:feature:book`** — a
feature-to-feature dependency is how two screens end up unable to change independently. `MiniPlayer`
stays shared and stays Plan 3's; only where tapping it *goes* is decided in `:app`.

### Where the intent lives — the seam correction, made concrete

Task 6 established that the policy answers the position and the **caller** chooses the item, because
`resolve(mediaIds, requestedIndex)` cannot tell *"resume this book"* from *"play chapter 1"*.
`BookPlaybackLauncher` is that caller, and its three methods are the three intents:

| Method | Intent | `startIndex` |
|---|---|---|
| `resume(bookId)` | "carry on" | the file the listener was last in |
| `playFile(bookId, mediaId)` | "play *this* part" | that file |
| `restart(bookId)` | "start again" | 0, **after clearing the book's progress** |

`restart` is the one worth reading twice. The seam forbids handing the player a position, correctly,
so "start from the beginning" cannot be expressed as `setMediaItems(items, 0, 0L)` — the policy
would resolve the stored position straight back. It is expressed by **removing the progress**, which
is also the more honest state. Then `refresh()` the snapshot, because the policy reads memory.

- [ ] **Step 1: Create the module**

`settings.gradle.kts` — `include(":feature:book")`.

`feature/book/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.feature")
}

android {
  namespace = "app.muplay.book"
}

dependencies {
  implementation(project(":core:media"))
  implementation(project(":core:database"))
  implementation(project(":core:model"))
  implementation(project(":core:designsystem"))
  implementation(libs.coil.compose)
}
```

> The plugin id is whatever Plan 2 Task 9 registered for `:feature:library` — read
> `feature/library/build.gradle.kts` and match it exactly. `ConventionTest`'s
> `no module configures android or kotlin blocks directly` rule allows **only** `namespace` in a
> module's own `android { }` block, and it will fail this module the moment anything else appears
> there. `ConventionTest` will also fail the build until `:feature:book` has a `coverageFloors`
> entry — add a placeholder now and measure it in Step 9; a placeholder that is never measured is
> exactly the unfireable floor this project has been bitten by, so do not stop at the placeholder.

- [ ] **Step 2: Write the failing Tier 1 state tests**

`feature/book/src/test/kotlin/app/muplay/book/StartIndexTest.kt`:

```kotlin
package app.muplay.book

import app.muplay.model.ResumePoint
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which file a book starts on — the decision Task 6 moved out of the resume policy and into the
 * caller, because the caller is the only party that knows what the listener asked for.
 */
class StartIndexTest {

  private fun song(id: String) = Song(
    id = id, libraryId = 2, title = id, albumId = "book", albumName = "Book", artistId = null,
    artistName = "Author", trackNumber = null, discNumber = null, durationSeconds = 5,
    suffix = "mp3", coverArtId = null,
  )

  private val files = listOf(song("p1"), song("p2"), song("p3"))

  @Test
  fun `resuming starts on the file the listener was in`() {
    // Two different resume points, two different indices. With one, "the resume point's index" and
    // "1" are the same number.
    assertThat(startIndexFor(files, ResumePoint("p2", 3_500L, 900L))).isEqualTo(1)
    assertThat(startIndexFor(files, ResumePoint("p3", 500L, 900L))).isEqualTo(2)
  }

  @Test
  fun `a book nobody has opened starts on its first file`() {
    assertThat(startIndexFor(files, resumeAt = null)).isZero
  }

  @Test
  fun `a resume point naming a file that is no longer in the book starts at the beginning`() {
    // A server rescan can remove a file. `indexOf` returning -1 reaches `setMediaItems` as a
    // start index, and an out-of-range index there is a crash rather than a fallback.
    assertThat(startIndexFor(files, ResumePoint("deleted", 3_500L, 900L))).isZero
  }

  @Test
  fun `playing a specific file starts on that file`() {
    assertThat(startIndexFor(files, mediaId = "p1")).isZero
    assertThat(startIndexFor(files, mediaId = "p3")).isEqualTo(2)
  }

  @Test
  fun `playing a file that is not in the book starts at the beginning rather than out of range`() {
    assertThat(startIndexFor(files, mediaId = "elsewhere")).isZero
  }

  @Test
  fun `an empty book has no start index to get wrong`() {
    assertThat(startIndexFor(emptyList(), ResumePoint("p2", 1L, 1L))).isZero
    assertThat(startIndexFor(emptyList(), mediaId = "p2")).isZero
  }
}
```

`feature/book/src/test/kotlin/app/muplay/book/BookPlayerUiStateTest.kt`:

```kotlin
package app.muplay.book

import androidx.media3.common.MediaMetadata
import app.muplay.media.BookChapter
import app.muplay.media.PlaybackState
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Everything the book player shows, derived from four inputs and no Android.
 *
 * The chapter a listener is in is **computed from the position**, not stored, so this is where
 * "which chapter am I in" is actually gated. The timeline used below is a multi-file book, because
 * on a single-file book the in-item position and the book position are the same number and half of
 * these assertions would be true of any implementation.
 */
class BookPlayerUiStateTest {

  private val timeline = listOf(
    BookChapter(0, "One", "p1", 0, 0, 4_000, 0),
    BookChapter(1, "Two", "p2", 1, 0, 6_000, 4_000),
    BookChapter(2, "Three", "p3", 2, 0, 5_000, 10_000),
  )

  private val book = BookSummary(
    bookId = "book", libraryId = 2, title = "Multi Part Book", author = "Fourth Author",
    coverArtId = "art", fileCount = 3, durationMs = 15_000, positionMs = 0,
    isFinished = false, lastPlayedAtEpochMs = null,
  )

  private fun playback(mediaId: String, positionMs: Long, playing: Boolean = true, speed: Float = 1f) =
    PlaybackState.NOTHING_PLAYING.copy(
      isPlaying = playing, mediaId = mediaId, title = mediaId, positionMs = positionMs,
      durationMs = 6_000, mediaType = MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER, speed = speed,
    )

  private fun state(mediaId: String, positionMs: Long, speed: Float = 1f) = bookPlayerUiState(
    playback = playback(mediaId, positionMs, speed = speed),
    book = book,
    timeline = timeline,
    settings = BookSettings("book", speed, skipSilence = false),
    sleepTimer = SleepTimerState.Off,
  ) as BookPlayerUiState.Content

  @Test
  fun `nothing playing is its own state`() {
    assertThat(
      bookPlayerUiState(PlaybackState.NOTHING_PLAYING, null, emptyList(), BookSettings.default("b"), SleepTimerState.Off),
    ).isEqualTo(BookPlayerUiState.NothingPlaying)
  }

  @Test
  fun `the chapter shown follows the position`() {
    // Three observations across three files. A UI state that took the first chapter, or the
    // current media item's index, passes at most one of them.
    assertThat(state("p1", 1_000).chapterTitle).isEqualTo("One")
    assertThat(state("p2", 1_000).chapterTitle).isEqualTo("Two")
    assertThat(state("p3", 1_000).chapterTitle).isEqualTo("Three")
  }

  @Test
  fun `the chapter number and count are what a listener counts`() {
    // One-based, because "Chapter 0 of 3" is not a thing anybody says.
    assertThat(state("p2", 1_000).chapterNumber).isEqualTo(2)
    assertThat(state("p2", 1_000).chapterCount).isEqualTo(3)
  }

  @Test
  fun `the book position adds the files before this one`() {
    // The number under the whole-book progress bar. Three observations, three offsets: on a
    // single-file book this is indistinguishable from the in-file position.
    assertThat(state("p1", 1_000).bookPositionMs).isEqualTo(1_000L)
    assertThat(state("p2", 1_000).bookPositionMs).isEqualTo(5_000L)
    assertThat(state("p3", 1_000).bookPositionMs).isEqualTo(11_000L)
  }

  @Test
  fun `the position inside the chapter is relative to the chapter, not to the file`() {
    // For a single-file M4B these differ: chapter 3 of Second Book starts at 9 000, so a position
    // of 12 000 is 3 000 into the chapter.
    val singleFile = listOf(
      BookChapter(0, "A", "m4b", 0, 0, 9_000, 0),
      BookChapter(1, "B", "m4b", 0, 9_000, 15_000, 9_000),
    )

    val content = bookPlayerUiState(
      playback = playback("m4b", 12_000),
      book = book,
      timeline = singleFile,
      settings = BookSettings.default("book"),
      sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(content.positionInChapterMs).isEqualTo(3_000L)
    assertThat(content.chapterDurationMs).isEqualTo(6_000L)
  }

  @Test
  fun `the remaining time in the book is what the shelf promised`() {
    assertThat(state("p2", 1_000).bookRemainingMs).isEqualTo(10_000L)
    assertThat(state("p3", 4_000).bookRemainingMs).isEqualTo(1_000L)
  }

  @Test
  fun `the speed shown is the book's`() {
    // Two speeds. A UI that hardcoded 1.0 -- easy, because that is what the player reports for
    // most of a session -- passes one of these.
    assertThat(state("p1", 0, speed = 1.4f).speed).isEqualTo(1.4f)
    assertThat(state("p1", 0, speed = 0.8f).speed).isEqualTo(0.8f)
  }

  @Test
  fun `the sleep timer is carried through, running or not`() {
    val running = bookPlayerUiState(
      playback = playback("p1", 0), book = book, timeline = timeline,
      settings = BookSettings.default("book"),
      sleepTimer = SleepTimerState.Running(90_000L, untilEndOfChapter = true, isFading = false),
    ) as BookPlayerUiState.Content

    assertThat(running.sleepTimer).isEqualTo(SleepTimerState.Running(90_000L, true, false))
    assertThat(state("p1", 0).sleepTimer).isEqualTo(SleepTimerState.Off)
  }

  @Test
  fun `a book whose chapters have not loaded yet still shows the transport`() {
    // Chapter extraction is an HTTP round trip. A player that showed nothing until it finished
    // would be blank for a second every time a book opened.
    val content = bookPlayerUiState(
      playback = playback("p1", 1_000), book = book, timeline = emptyList(),
      settings = BookSettings.default("book"), sleepTimer = SleepTimerState.Off,
    ) as BookPlayerUiState.Content

    assertThat(content.chapterTitle).isEqualTo("Multi Part Book")
    assertThat(content.chapterCount).isZero
    assertThat(content.bookPositionMs).isEqualTo(1_000L)
  }
}
```

`feature/book/src/test/kotlin/app/muplay/book/BookshelfUiStateTest.kt`:

```kotlin
package app.muplay.book

import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookshelfUiStateTest {

  private fun book(id: String, started: Boolean) = BookSummary(
    bookId = id, libraryId = 2, title = id, author = null, coverArtId = null, fileCount = 1,
    durationMs = 1_000, positionMs = if (started) 500 else 0, isFinished = false,
    lastPlayedAtEpochMs = if (started) 1L else null,
  )

  @Test
  fun `nothing loaded yet is loading, and no books is empty`() {
    // Three distinct states from three inputs. Collapsing "not loaded" and "none" shows a listener
    // "you have no audiobooks" for the second before the first query returns, which reads as a
    // broken app rather than as a slow one.
    assertThat(bookshelfUiState(null)).isEqualTo(BookshelfUiState.Loading)
    assertThat(bookshelfUiState(emptyList())).isEqualTo(BookshelfUiState.Empty)
    assertThat(bookshelfUiState(listOf(book("a", started = false))))
      .isInstanceOf(BookshelfUiState.Content::class.java)
  }

  @Test
  fun `the repository's order is preserved exactly`() {
    // The shelf order is `BookSummaries.order`'s (Task 4). A UI state that re-sorted -- by title,
    // say, because that looks tidy -- would silently undo it.
    val books = listOf(book("zed", started = true), book("alpha", started = false))

    assertThat((bookshelfUiState(books) as BookshelfUiState.Content).books.map { it.bookId })
      .containsExactly("zed", "alpha")
  }

  @Test
  fun `the continue-listening group is what has been started`() {
    val books = listOf(book("zed", started = true), book("alpha", started = false))

    val content = bookshelfUiState(books) as BookshelfUiState.Content

    assertThat(content.continueListening.map { it.bookId }).containsExactly("zed")
    assertThat(content.rest.map { it.bookId }).containsExactly("alpha")
  }
}
```

- [ ] **Step 3: Implement the state and the launcher**

`feature/book/src/main/kotlin/app/muplay/book/BookPlaybackLauncher.kt`:

```kotlin
package app.muplay.book

import app.muplay.database.AudiobookRepository
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.PlaybackConnection
import app.muplay.media.PlaybackQueue
import app.muplay.media.QueueRepository
import app.muplay.model.ResumePoint
import app.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which file a book starts on.
 *
 * Task 6 moved this decision out of `ResumePolicy` deliberately: `resolve(mediaIds, requestedIndex)`
 * cannot tell "resume this book" from "play chapter 1 from the top", because both arrive as index
 * 0. The caller can, so the caller decides — and the policy still decides the position, which is the
 * guarantee the seam was built for.
 *
 * Out-of-range is folded to 0 rather than passed through: `indexOf` returns -1 for a file a server
 * rescan removed, and -1 reaching `setMediaItems` is a crash rather than a fallback.
 */
internal fun startIndexFor(files: List<Song>, resumeAt: ResumePoint?): Int =
  files.indexOfFirst { it.id == resumeAt?.mediaId }.coerceAtLeast(0)

internal fun startIndexFor(files: List<Song>, mediaId: String): Int =
  files.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)

/**
 * The three things a listener can ask a book to do.
 *
 * Every one of them refreshes [AudiobookSnapshot] **before** setting the queue. The policy reads
 * memory, by design (Plan 3 forbids a Room read on the player's application thread), and a stale
 * snapshot resumes at zero — silently, and only on the run where nothing warmed it.
 */
@Singleton
class BookPlaybackLauncher @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val queueRepository: QueueRepository,
  private val playbackConnection: PlaybackConnection,
  private val audiobookSnapshot: AudiobookSnapshot,
) {

  suspend fun resume(bookId: String) {
    val files = audiobookRepository.files(bookId)
    if (files.isEmpty()) return
    play(files, startIndexFor(files, audiobookRepository.resumePoint(bookId)))
  }

  suspend fun playFile(bookId: String, mediaId: String) {
    val files = audiobookRepository.files(bookId)
    if (files.isEmpty()) return
    play(files, startIndexFor(files, mediaId))
  }

  /**
   * "Start from the beginning."
   *
   * Expressed by **clearing the book's progress**, not by asking for position 0 — the seam would
   * resolve the stored position straight back, correctly. Clearing first, then refreshing, is what
   * makes the policy answer zero.
   */
  suspend fun restart(bookId: String) {
    audiobookRepository.restart(bookId)
    val files = audiobookRepository.files(bookId)
    if (files.isEmpty()) return
    play(files, 0)
  }

  private suspend fun play(files: List<Song>, startIndex: Int) {
    audiobookSnapshot.refresh()
    val controller = playbackConnection.controller()
    val items = queueRepository.mediaItems(PlaybackQueue.of(files, startIndex))
    // The position argument is discarded by `MuPlayer` and replaced by the policy's answer. It is
    // passed as 0 rather than as anything meaningful precisely so nobody reads this line as a
    // second opinion about where a book starts.
    controller.setMediaItems(items, startIndex, 0L)
    controller.prepare()
    controller.play()
  }
}
```

`feature/book/src/main/kotlin/app/muplay/book/BookshelfUiState.kt`:

```kotlin
package app.muplay.book

import app.muplay.model.BookSummary

sealed interface BookshelfUiState {
  data object Loading : BookshelfUiState
  data object Empty : BookshelfUiState

  data class Content(val books: List<BookSummary>) : BookshelfUiState {
    /** The top of the shelf: what the listener is part-way through, in the repository's order. */
    val continueListening: List<BookSummary> get() = books.filter { it.hasStarted && !it.isFinished }
    val rest: List<BookSummary> get() = books.filterNot { it.hasStarted && !it.isFinished }
  }
}

/**
 * `null` is "the first query has not returned", which is a different fact from "there are no
 * audiobooks" — collapsing them shows "you have no audiobooks" for the second before the shelf
 * loads, and that reads as a broken app rather than as a slow one.
 */
internal fun bookshelfUiState(books: List<BookSummary>?): BookshelfUiState = when {
  books == null -> BookshelfUiState.Loading
  books.isEmpty() -> BookshelfUiState.Empty
  // The order is `BookSummaries.order`'s and is preserved exactly. Re-sorting here -- by title,
  // because that looks tidier -- silently undoes the one thing the shelf is for.
  else -> BookshelfUiState.Content(books)
}
```

`feature/book/src/main/kotlin/app/muplay/book/BookPlayerUiState.kt`:

```kotlin
package app.muplay.book

import app.muplay.media.BookChapter
import app.muplay.media.BookTimeline
import app.muplay.media.PlaybackState
import app.muplay.model.BookSettings
import app.muplay.model.BookSummary
import app.muplay.model.SleepTimerState

sealed interface BookPlayerUiState {
  data object NothingPlaying : BookPlayerUiState

  data class Content(
    val bookTitle: String,
    val author: String?,
    val coverArtId: String?,
    val chapterTitle: String,
    val chapterNumber: Int,
    val chapterCount: Int,
    val positionInChapterMs: Long,
    val chapterDurationMs: Long,
    val bookPositionMs: Long,
    val bookDurationMs: Long,
    val bookRemainingMs: Long,
    val isPlaying: Boolean,
    val speed: Float,
    val skipSilence: Boolean,
    val sleepTimer: SleepTimerState,
    val chapters: List<BookChapter>,
  ) : BookPlayerUiState
}

/**
 * Everything the book player shows, from four inputs and no Android.
 *
 * The chapter is **computed from the position** rather than stored, so this function is where
 * "which chapter am I in" is actually decided — and where it is gated, in Tier 1.
 *
 * An empty [timeline] is a real state, not an error: chapter extraction is an HTTP round trip, and
 * a player that showed nothing until it finished would be blank for a second every time a book
 * opened. The book's own title stands in until the chapters arrive.
 */
internal fun bookPlayerUiState(
  playback: PlaybackState,
  book: BookSummary?,
  timeline: List<BookChapter>,
  settings: BookSettings,
  sleepTimer: SleepTimerState,
): BookPlayerUiState {
  val mediaId = playback.mediaId ?: return BookPlayerUiState.NothingPlaying
  if (book == null) return BookPlayerUiState.NothingPlaying

  val chapter = BookTimeline.chapterAt(timeline, mediaId, playback.positionMs)
  val bookPositionMs = BookTimeline.bookPositionMs(timeline, mediaId, playback.positionMs)

  return BookPlayerUiState.Content(
    bookTitle = book.title,
    author = book.author,
    coverArtId = book.coverArtId,
    chapterTitle = chapter?.title ?: book.title,
    // One-based: "Chapter 0 of 3" is not a thing anybody says.
    chapterNumber = (chapter?.index ?: -1) + 1,
    chapterCount = timeline.size,
    positionInChapterMs = chapter?.let { playback.positionMs - it.startInItemMs }?.coerceAtLeast(0L) ?: playback.positionMs,
    chapterDurationMs = chapter?.durationMs ?: playback.durationMs.coerceAtLeast(0L),
    bookPositionMs = bookPositionMs,
    bookDurationMs = book.durationMs,
    bookRemainingMs = (book.durationMs - bookPositionMs).coerceAtLeast(0L),
    isPlaying = playback.isPlaying,
    speed = playback.speed,
    skipSilence = settings.skipSilence,
    sleepTimer = sleepTimer,
    chapters = timeline,
  )
}

/** `h:mm:ss` past an hour, `m:ss` below it — a book is routinely longer than an hour. */
internal fun formatClock(millis: Long): String {
  val total = (millis / 1_000).coerceAtLeast(0)
  val h = total / 3_600
  val m = (total % 3_600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "3 h 12 m left" / "12 m left" / "under a minute left" — what a listener actually wants to know. */
internal fun formatRemaining(millis: Long): String {
  val minutes = (millis / 60_000).coerceAtLeast(0)
  return when {
    minutes == 0L -> "under a minute left"
    minutes < 60L -> "$minutes m left"
    else -> "${minutes / 60} h ${minutes % 60} m left"
  }
}
```

> `formatClock` and `formatRemaining` need their own Tier 1 test, with **at least three inputs
> each** spanning every branch — under a minute, under an hour, and over an hour — plus zero and a
> negative. Add `BookFormattingTest` beside the others; the assertions are exact strings, and the
> negative case exists because `bookRemainingMs` is a subtraction.

- [ ] **Step 4: The ViewModels**

`BookshelfViewModel`, `BookViewModel` and `BookPlayerViewModel` are thin, in the shape Plan 2 Task 9
established: a `StateFlow` built by `combine` over repository Flows and mapped through the pure
function above, plus one method per user action that delegates to a repository or to
`BookPlaybackLauncher`. Concretely:

```kotlin
@HiltViewModel
class BookshelfViewModel @Inject constructor(
  private val audiobookRepository: AudiobookRepository,
  private val launcher: BookPlaybackLauncher,
) : ViewModel() {

  val uiState: StateFlow<BookshelfUiState> = audiobookRepository.bookshelf()
    .map { bookshelfUiState(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookshelfUiState.Loading)

  fun resume(bookId: String) {
    viewModelScope.launch { launcher.resume(bookId) }
  }
}
```

```kotlin
@HiltViewModel
class BookViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val audiobookRepository: AudiobookRepository,
  private val chapterRepository: ChapterRepository,
  private val launcher: BookPlaybackLauncher,
) : ViewModel() {

  // The book id comes from the navigation key, as `AlbumViewModel`'s album id does (Plan 2 Task 9).
  private val bookId: String = checkNotNull(savedStateHandle["bookId"])

  private val chapters = MutableStateFlow<List<BookChapter>>(emptyList())

  val uiState: StateFlow<BookUiState> = combine(
    audiobookRepository.bookshelf(),
    audiobookRepository.observeSettings(bookId),
    chapters,
  ) { books, settings, timeline ->
    when (val book = books.firstOrNull { it.bookId == bookId }) {
      null -> BookUiState.NotFound
      else -> BookUiState.Content(book, timeline, settings)
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState.Loading)

  init {
    viewModelScope.launch {
      // Chapters are read once, off the main thread, and cached in Room by `ChapterRepository`.
      // The screen renders without them and fills in when they arrive -- see `bookPlayerUiState`.
      val files = audiobookRepository.files(bookId)
      val byId = chapterRepository.chaptersFor(files)
      chapters.value = BookTimeline.of(
        files.map { BookFile(it.id, it.title, it.durationSeconds * 1_000L) },
        byId,
      )
    }
  }

  fun resume() { viewModelScope.launch { launcher.resume(bookId) } }
  fun restart() { viewModelScope.launch { launcher.restart(bookId) } }
  fun playChapter(chapter: BookChapter) {
    viewModelScope.launch {
      launcher.playFile(bookId, chapter.mediaId)
      // A chapter inside a single-file book is a seek, not a queue change: every chapter of an M4B
      // shares one media id, so `playFile` alone would land at the resume position rather than at
      // the chapter.
      playbackConnection.controller().seekTo(chapter.itemIndex, chapter.startInItemMs)
    }
  }
  fun setSpeed(speed: Float) { viewModelScope.launch { audiobookRepository.setSpeed(bookId, speed) } }
  fun setSkipSilence(enabled: Boolean) {
    viewModelScope.launch { audiobookRepository.setSkipSilence(bookId, enabled) }
  }
}
```

> `playChapter`'s two-step is worth reading twice, and it is the one place a single-file book and a
> ripped book genuinely differ. For a ripped book `startIndexFor` already lands on the right item
> and the `seekTo` is a no-op at `startInItemMs == 0`; for an M4B every chapter has the same media
> id, so the queue never changes and the seek is the whole operation. One code path, both shapes —
> and `BookPlayerScreenTest` asserts it on both.

```kotlin
@HiltViewModel
class BookPlayerViewModel @Inject constructor(
  private val playbackConnection: PlaybackConnection,
  private val audiobookRepository: AudiobookRepository,
  private val chapterRepository: ChapterRepository,
  private val sleepTimer: SleepTimerController,
  private val audiobookSnapshot: AudiobookSnapshot,
) : ViewModel() {

  private val book = MutableStateFlow<BookSummary?>(null)
  private val timeline = MutableStateFlow<List<BookChapter>>(emptyList())
  private val settings = MutableStateFlow(BookSettings.default(""))

  val uiState: StateFlow<BookPlayerUiState> = combine(
    playbackConnection.state, book, timeline, settings, sleepTimer.state,
  ) { playback, summary, chapters, bookSettings, timer ->
    bookPlayerUiState(playback, summary, chapters, bookSettings, timer)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookPlayerUiState.NothingPlaying)

  init {
    // The playing item's book, resolved through `AudiobookSnapshot` -- the same map the resume
    // policy reads, so the player and the policy cannot disagree about which book is playing.
    //
    // `settings` is a separate flow rather than a `suspend` call inside `combine`'s transform,
    // which is not allowed and does not compile. It is re-collected whenever the book changes, and
    // the inner collection is cancelled by `collectLatest` when it does -- without that, switching
    // books leaves the old book's settings collector running and the two race.
    viewModelScope.launch {
      playbackConnection.state
        .map { it.mediaId }
        .distinctUntilChanged()
        .collectLatest { mediaId ->
          val bookId = mediaId?.let { audiobookSnapshot.itemFor(it)?.bookId }
          if (bookId == null) {
            book.value = null
            timeline.value = emptyList()
            settings.value = BookSettings.default("")
            return@collectLatest
          }
          book.value = audiobookRepository.book(bookId)
          val files = audiobookRepository.files(bookId)
          timeline.value = BookTimeline.of(
            files.map { BookFile(it.id, it.title, it.durationSeconds * 1_000L) },
            chapterRepository.chaptersFor(files),
          )
          audiobookRepository.observeSettings(bookId).collect { settings.value = it }
        }
    }
  }

  fun playPause() {
    viewModelScope.launch {
      val controller = playbackConnection.controller()
      if (controller.isPlaying) controller.pause() else controller.play()
    }
  }

  fun nextChapter() = seekToChapter { timeline, current, _ -> BookTimeline.next(timeline, current) }

  fun previousChapter() = seekToChapter { timeline, current, positionInItemMs ->
    BookTimeline.previous(timeline, current, positionInItemMs)
  }

  fun seekTo(chapter: BookChapter) {
    viewModelScope.launch {
      playbackConnection.controller().seekTo(chapter.itemIndex, chapter.startInItemMs)
    }
  }

  /** +/- 30 s. Clamped at zero, because a negative seek target is not a listener's problem. */
  fun nudge(byMs: Long) {
    viewModelScope.launch {
      val controller = playbackConnection.controller()
      controller.seekTo((controller.currentPosition + byMs).coerceAtLeast(0L))
    }
  }

  /**
   * Set on the player, not in the database. `BookSpeedController` (Task 7) hears
   * `onPlaybackParametersChanged` and persists it against the right book — which is also what makes
   * a speed change from a car or a watch persist, without this screen being involved.
   */
  fun setSpeed(speed: Float) {
    viewModelScope.launch {
      playbackConnection.controller().setPlaybackSpeed(BookSettings.clampSpeed(speed))
    }
  }

  fun startSleepTimer(request: SleepTimerRequest) = sleepTimer.start(request)

  fun cancelSleepTimer() = sleepTimer.cancel()

  /** "Until the end of this chapter", turned into the position the timer actually needs. */
  fun endOfChapterTimer() {
    val playing = playbackConnection.state.value
    val mediaId = playing.mediaId ?: return
    val chapter = BookTimeline.chapterAt(timeline.value, mediaId, playing.positionMs) ?: return
    sleepTimer.start(SleepTimerRequest.UntilPosition(chapter.mediaId, chapter.endInItemMs))
  }

  /**
   * The one place chapter navigation happens.
   *
   * Note which position is passed to [BookTimeline]: the **in-item** position, not the book
   * position. `previous` compares it against the chapter's `startInItemMs` to decide between
   * "restart this chapter" and "go to the one before", and a book position there makes that
   * comparison wrong for every file after the first.
   */
  private fun seekToChapter(
    pick: (List<BookChapter>, BookChapter?, Long) -> BookChapter?,
  ) {
    viewModelScope.launch {
      val controller = playbackConnection.controller()
      val mediaId = controller.currentMediaItem?.mediaId ?: return@launch
      val positionInItemMs = controller.currentPosition
      val current = BookTimeline.chapterAt(timeline.value, mediaId, positionInItemMs)
      val target = pick(timeline.value, current, positionInItemMs) ?: return@launch
      controller.seekTo(target.itemIndex, target.startInItemMs)
    }
  }
}
```

> Two things in there are not style choices. **`collectLatest`, not `collect`**, on the outer flow:
> the inner `observeSettings` collection never returns, so a plain `collect` would leave the
> previous book's settings collector running and the two would fight over `settings.value` every
> time a listener switched books. And **`combine` over five flows**, with `settings` as its own
> flow: a `suspend` call inside `combine`'s transform does not compile, and the shape that tempts
> you to write one is exactly this.


- [ ] **Step 5: The screens**

Three composables, in the shape Plan 2 Task 9 established: `collectAsStateWithLifecycle()`, an
exhaustive `when` over the sealed state, a `Scaffold` for insets, and **every interactive element
carrying a stable text or content description** — the journeys find them by those strings, and Plan
2's stance is that a journey duplicates the string rather than sharing the constant, so a wording
change is caught rather than silently followed.

Each screen is split into a stateful entry point and a **stateless `…Content` composable**, because
the stateless one is what the Compose tests render — a screen that can only be shown through a
ViewModel can only be tested through Hilt, and that is a large price for a layout assertion.

`feature/book/src/main/kotlin/app/muplay/book/BookshelfScreen.kt`:

```kotlin
package app.muplay.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.muplay.designsystem.component.CoverArtImage
import app.muplay.model.BookSummary

@Composable
fun BookshelfScreen(
  onBookClick: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: BookshelfViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookshelfContent(
    state = state,
    onBookClick = onBookClick,
    onResume = { bookId -> viewModel.resume(bookId); onOpenPlayer() },
    modifier = modifier,
  )
}

@Composable
internal fun BookshelfContent(
  state: BookshelfUiState,
  onBookClick: (String) -> Unit,
  onResume: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier.fillMaxSize()) { insets ->
    when (state) {
      BookshelfUiState.Loading -> Text(LOADING_LABEL, Modifier.padding(insets).padding(16.dp))
      BookshelfUiState.Empty -> Text(NO_BOOKS_LABEL, Modifier.padding(insets).padding(16.dp))
      is BookshelfUiState.Content -> LazyColumn(Modifier.padding(insets)) {
        if (state.continueListening.isNotEmpty()) {
          item { SectionHeader(CONTINUE_LISTENING_LABEL) }
          items(state.continueListening, key = { it.bookId }) { book ->
            BookRow(book, onBookClick, onResume)
          }
        }
        if (state.rest.isNotEmpty()) {
          item { SectionHeader(BOOKSHELF_TITLE) }
          items(state.rest, key = { it.bookId }) { book -> BookRow(book, onBookClick, onResume) }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}

@Composable
private fun BookRow(book: BookSummary, onBookClick: (String) -> Unit, onResume: (String) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onBookClick(book.bookId) }
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CoverArtImage(coverArtId = book.coverArtId, sizePx = 128, contentDescription = book.title)
    Column(Modifier.weight(1f)) {
      Text(book.title, style = MaterialTheme.typography.titleSmall)
      book.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      // Only for a book that has been started: a progress bar at zero on every unopened book turns
      // the shelf into a wall of empty rectangles.
      if (book.hasStarted) {
        LinearProgressIndicator(progress = { book.progressFraction }, modifier = Modifier.fillMaxWidth())
        Text(formatRemaining(book.remainingMs), style = MaterialTheme.typography.bodySmall)
      }
    }
    if (book.hasStarted) TextButton(onClick = { onResume(book.bookId) }) { Text(RESUME_LABEL) }
  }
}
```

`feature/book/src/main/kotlin/app/muplay/book/BookScreen.kt` follows the same split. Its
`BookContent` shows the cover, the title, the author, `formatRemaining(book.remainingMs)`, a
`RESUME_LABEL` button, a `START_OVER_LABEL` button, the speed control, and a `LazyColumn` of chapter
rows — each `"${chapter.index + 1}. ${chapter.title}"` plus `formatClock(chapter.durationMs)`, and
`clickable { onPlayChapter(chapter) }`. It renders the chapter list from
`BookUiState.Content.chapters`, which is empty until extraction finishes; **the rest of the screen
renders anyway**, because chapter extraction is an HTTP round trip and a screen that waited for it
would be blank for a second every time a book opened.

`feature/book/src/main/kotlin/app/muplay/book/BookPlayerScreen.kt`:

```kotlin
@Composable
fun BookPlayerScreen(modifier: Modifier = Modifier, viewModel: BookPlayerViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  BookPlayerContent(
    state = state,
    onPlayPause = viewModel::playPause,
    onPreviousChapter = viewModel::previousChapter,
    onNextChapter = viewModel::nextChapter,
    onNudge = viewModel::nudge,
    onSpeed = viewModel::setSpeed,
    onChapter = viewModel::seekTo,
    onSleepPreset = { viewModel.startSleepTimer(SleepTimerRequest.Duration(it)) },
    onEndOfChapter = viewModel::endOfChapterTimer,
    onCancelTimer = viewModel::cancelSleepTimer,
    modifier = modifier,
  )
}

@Composable
internal fun BookPlayerContent(
  state: BookPlayerUiState,
  onPlayPause: () -> Unit,
  onPreviousChapter: () -> Unit,
  onNextChapter: () -> Unit,
  onNudge: (Long) -> Unit,
  onSpeed: (Float) -> Unit,
  onChapter: (BookChapter) -> Unit,
  onSleepPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancelTimer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier.fillMaxSize()) { insets ->
    when (state) {
      BookPlayerUiState.NothingPlaying -> Text(NOTHING_PLAYING_LABEL, Modifier.padding(insets).padding(16.dp))
      is BookPlayerUiState.Content -> Column(
        Modifier.padding(insets).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        CoverArtImage(coverArtId = state.coverArtId, sizePx = 512, contentDescription = state.bookTitle)
        Text(state.bookTitle, style = MaterialTheme.typography.titleLarge)
        Text(state.chapterTitle, style = MaterialTheme.typography.titleMedium)
        // Only when chapters are known: "Chapter 0 of 0" on a book whose extraction has not
        // returned is worse than nothing.
        if (state.chapterCount > 0) {
          Text("Chapter ${state.chapterNumber} of ${state.chapterCount}")
        }
        LinearProgressIndicator(
          progress = {
            if (state.chapterDurationMs <= 0L) 0f
            else state.positionInChapterMs.toFloat() / state.chapterDurationMs
          },
          modifier = Modifier.fillMaxWidth(),
        )
        Text("${formatClock(state.positionInChapterMs)} / ${formatClock(state.chapterDurationMs)}")
        Text(formatRemaining(state.bookRemainingMs))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          IconButton(onClick = onPreviousChapter) {
            Icon(Icons.Default.SkipPrevious, contentDescription = PREVIOUS_CHAPTER_LABEL)
          }
          IconButton(onClick = { onNudge(-NUDGE_MS) }) {
            Icon(Icons.Default.Replay30, contentDescription = BACK_30_LABEL)
          }
          IconButton(onClick = onPlayPause) {
            if (state.isPlaying) Icon(Icons.Default.Pause, contentDescription = PAUSE_LABEL)
            else Icon(Icons.Default.PlayArrow, contentDescription = PLAY_LABEL)
          }
          IconButton(onClick = { onNudge(NUDGE_MS) }) {
            Icon(Icons.Default.Forward30, contentDescription = FORWARD_30_LABEL)
          }
          IconButton(onClick = onNextChapter) {
            Icon(Icons.Default.SkipNext, contentDescription = NEXT_CHAPTER_LABEL)
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = { onSpeed(state.speed - BookSettings.SPEED_STEP) }) {
            Icon(Icons.Default.Remove, contentDescription = SLOWER_LABEL)
          }
          Text("$SPEED_LABEL ${"%.1f".format(state.speed)}x")
          IconButton(onClick = { onSpeed(state.speed + BookSettings.SPEED_STEP) }) {
            Icon(Icons.Default.Add, contentDescription = FASTER_LABEL)
          }
        }

        SleepTimerRow(state.sleepTimer, onSleepPreset, onEndOfChapter, onCancelTimer)

        LazyColumn {
          items(state.chapters, key = { it.index }) { chapter ->
            Text(
              "${chapter.index + 1}. ${chapter.title}",
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onChapter(chapter) }
                .padding(vertical = 8.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SleepTimerRow(
  timer: SleepTimerState,
  onPreset: (Long) -> Unit,
  onEndOfChapter: () -> Unit,
  onCancel: () -> Unit,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    // While a timer runs the button IS the countdown, so it is visible without opening anything --
    // which is the whole affordance for someone half asleep.
    TextButton(onClick = { open = !open }) {
      Text(if (timer is SleepTimerState.Running) formatClock(timer.remainingMs) else SLEEP_TIMER_LABEL)
    }
    if (timer is SleepTimerState.Running) {
      TextButton(onClick = onCancel) { Text(CANCEL_TIMER_LABEL) }
    }
  }
  if (open) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SleepTimerController.PRESETS.forEach { preset ->
        TextButton(onClick = { onPreset(preset); open = false }) { Text("${preset / 60_000} min") }
      }
      TextButton(onClick = { onEndOfChapter(); open = false }) { Text(END_OF_CHAPTER_LABEL) }
    }
  }
}
```

```kotlin
internal const val BOOKSHELF_TITLE = "Books"
internal const val CONTINUE_LISTENING_LABEL = "Continue listening"
internal const val LOADING_LABEL = "Loading books"
internal const val NO_BOOKS_LABEL = "No audiobooks in this library"
internal const val NOTHING_PLAYING_LABEL = "Nothing playing"
internal const val RESUME_LABEL = "Resume"
internal const val START_OVER_LABEL = "Start from the beginning"
internal const val PREVIOUS_CHAPTER_LABEL = "Previous chapter"
internal const val NEXT_CHAPTER_LABEL = "Next chapter"
internal const val BACK_30_LABEL = "Back 30 seconds"
internal const val FORWARD_30_LABEL = "Forward 30 seconds"
internal const val PLAY_LABEL = "Play"
internal const val PAUSE_LABEL = "Pause"
internal const val SPEED_LABEL = "Speed"
internal const val SLOWER_LABEL = "Slower"
internal const val FASTER_LABEL = "Faster"
internal const val SLEEP_TIMER_LABEL = "Sleep timer"
internal const val END_OF_CHAPTER_LABEL = "End of chapter"
internal const val CANCEL_TIMER_LABEL = "Cancel sleep timer"

/** What `nudge` moves. Thirty back and thirty forward is the audiobook convention. */
internal const val NUDGE_MS = 30_000L
```

> `CoverArtImage` is Plan 2 Task 9's, in `:core:designsystem` or `:feature:library` — **read the
> real file** and import from wherever it landed. If it lives in `:feature:library`, move it to
> `:core:designsystem` rather than depending on one feature from another; that is a two-file change
> and it is the right one.
>
> The Material icons named above (`Replay30`, `Forward30`) are in `material-icons-extended`, which
> this project does not depend on. **Do not add it** — it is a large artifact for two glyphs.
> Use `Icons.Default.Replay`/`Icons.Default.FastForward` from the core set, or draw the two as
> vector resources. Whatever you choose, the **content descriptions above do not change**: the
> journeys find these buttons by them.

- [ ] **Step 6: Wire the destinations**

`app/src/main/kotlin/app/muplay/ui/navigation/` — three `NavKey`s in the shape Plan 2 Task 10
established:

```kotlin
@Serializable data object BookshelfRoute : NavKey
@Serializable data class BookRoute(val bookId: String) : NavKey
@Serializable data object BookPlayerRoute : NavKey
```

`MuPlayApp` — the shelf becomes reachable, and the mini player learns where to go:

```kotlin
    // Which player the mini player opens is decided here, from `PlaybackState.mediaType` (Task 7),
    // and nowhere else. `:feature:book` and `:feature:player` do not know about each other; a
    // feature-to-feature dependency is how two screens stop being able to change independently.
    MiniPlayer(
      onOpenPlayer = { backStack.add(if (playbackState.isAudiobook) BookPlayerRoute else PlayerRoute) },
    )
```

with `BookshelfRoute` reachable from the library screen's library switcher (Plan 2 Task 9 already
lets the user pick a library; selecting one whose role is `AUDIOBOOKS` navigates to `BookshelfRoute`
rather than showing an album grid). **Read Plan 2's `LibraryScreen` before wiring this** — if it
switches libraries in place rather than navigating, add the branch at the call site in `MuPlayApp`
rather than reaching into `:feature:library`.

`app/build.gradle.kts` — `implementation(project(":feature:book"))`.

- [ ] **Step 7: Run the Tier 1 state tests**

Run: `./gradlew :feature:book:testDebugUnitTest`
Expected: PASS — `StartIndexTest` 6/6, `BookPlayerUiStateTest` 9/9, `BookshelfUiStateTest` 3/3,
`BookFormattingTest` (as written in Step 3's note).

- [ ] **Step 8: Write the Compose tests**

Both suites use `createAndroidComposeRule` on a real emulator — spec §10: *"Compose UI tests run on
a real emulator via `createAndroidComposeRule` — banning Robolectric costs nothing here."* Each
renders the **stateless** `…Content` composable with a hand-built state, so no ViewModel and no Hilt
is involved and the assertion is about the layout rather than about the graph.

`feature/book/src/androidTest/kotlin/app/muplay/book/BookshelfScreenTest.kt`:

```kotlin
package app.muplay.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.theme.MuPlayTheme
import app.muplay.model.BookSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookshelfScreenTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private fun summary(id: String, title: String, started: Boolean, remainingMs: Long) = BookSummary(
    bookId = id, libraryId = 2, title = title, author = "$title Author", coverArtId = null,
    fileCount = 1, durationMs = 3_600_000L, positionMs = if (started) 3_600_000L - remainingMs else 0L,
    isFinished = false, lastPlayedAtEpochMs = if (started) 1L else null,
  )

  private fun show(state: BookshelfUiState, onResume: (String) -> Unit = {}) {
    composeRule.setContent {
      MuPlayTheme { BookshelfContent(state = state, onBookClick = {}, onResume = onResume) }
    }
  }

  @Test
  fun theShelfShowsContinueListeningAboveTheRest() {
    show(
      BookshelfUiState.Content(
        listOf(
          summary("second", "Second Book", started = true, remainingMs = 3_600_000L),
          summary("test", "Test Book", started = false, remainingMs = 0L),
        ),
      ),
    )

    // The header and BOTH rows. `onNodeWithText("Second Book")` alone would pass if the started
    // book rendered twice and the unstarted one not at all -- which is what a `when` on the wrong
    // list produces.
    composeRule.onNodeWithText(CONTINUE_LISTENING_LABEL).assertIsDisplayed()
    composeRule.onNodeWithText("Second Book").assertIsDisplayed()
    composeRule.onNodeWithText("Test Book").assertIsDisplayed()
    composeRule.onNodeWithText("1 h 0 m left").assertIsDisplayed()
  }

  @Test
  fun anUnstartedBookHasNoRemainingTimeAndNoResumeButton() {
    // The other half of the same branch. Rendering a progress bar and "1 h 0 m left" on every
    // unopened book turns the shelf into a wall of identical rectangles.
    show(BookshelfUiState.Content(listOf(summary("test", "Test Book", started = false, remainingMs = 0L))))

    composeRule.onNodeWithText("Test Book").assertIsDisplayed()
    assertThat(composeRule.onAllNodesWithText(RESUME_LABEL).fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun tappingResumeNamesTheBookItWasTappedOn() {
    // Two started books, the SECOND one tapped. A callback that always passed the first book's id
    // is the ordinary version of this bug, and it looks completely fine on screen.
    val resumed = mutableListOf<String>()
    show(
      BookshelfUiState.Content(
        listOf(
          summary("second", "Second Book", started = true, remainingMs = 600_000L),
          summary("test", "Test Book", started = true, remainingMs = 60_000L),
        ),
      ),
      onResume = { resumed += it },
    )

    composeRule.onAllNodesWithText(RESUME_LABEL)[1].performClick()

    assertThat(resumed).containsExactly("test")
  }

  @Test
  fun loadingAndEmptyAreDifferentScreens() {
    // Collapsing them shows "No audiobooks in this library" for the second before the first query
    // returns, which reads as a broken app rather than as a slow one.
    show(BookshelfUiState.Loading)
    composeRule.onNodeWithText(LOADING_LABEL).assertIsDisplayed()

    show(BookshelfUiState.Empty)
    composeRule.onNodeWithText(NO_BOOKS_LABEL).assertIsDisplayed()
  }
}
```

`feature/book/src/androidTest/kotlin/app/muplay/book/BookPlayerScreenTest.kt`:

```kotlin
package app.muplay.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.designsystem.theme.MuPlayTheme
import app.muplay.media.BookChapter
import app.muplay.model.BookSettings
import app.muplay.model.SleepTimerState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookPlayerScreenTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

  private val chapters = listOf(
    BookChapter(0, "Prologue", "m4b", 0, 0, 4_000, 0),
    BookChapter(1, "The Long Middle", "m4b", 0, 4_000, 9_000, 4_000),
    BookChapter(2, "A Turn", "m4b", 0, 9_000, 15_000, 9_000),
    BookChapter(3, "Epilogue", "m4b", 0, 15_000, 21_000, 15_000),
  )

  private fun content(
    speed: Float = 1.0f,
    timer: SleepTimerState = SleepTimerState.Off,
  ) = BookPlayerUiState.Content(
    bookTitle = "Second Book", author = "Second Author", coverArtId = null,
    chapterTitle = "The Long Middle", chapterNumber = 2, chapterCount = 4,
    positionInChapterMs = 1_000, chapterDurationMs = 5_000,
    bookPositionMs = 5_000, bookDurationMs = 21_000, bookRemainingMs = 3_720_000,
    isPlaying = true, speed = speed, skipSilence = false, sleepTimer = timer, chapters = chapters,
  )

  private fun show(
    state: BookPlayerUiState,
    onSpeed: (Float) -> Unit = {},
    onChapter: (BookChapter) -> Unit = {},
  ) {
    composeRule.setContent {
      MuPlayTheme {
        BookPlayerContent(
          state = state, onPlayPause = {}, onPreviousChapter = {}, onNextChapter = {},
          onNudge = {}, onSpeed = onSpeed, onChapter = onChapter, onSleepPreset = {},
          onEndOfChapter = {}, onCancelTimer = {},
        )
      }
    }
  }

  @Test
  fun theBookTheChapterAndTheTimeRemainingAreAllOnScreen() {
    show(content())

    // Four fields, so a screen that rendered only the title passes none of the other three.
    composeRule.onNodeWithText("Second Book").assertIsDisplayed()
    composeRule.onNodeWithText("The Long Middle").assertIsDisplayed()
    composeRule.onNodeWithText("Chapter 2 of 4").assertIsDisplayed()
    composeRule.onNodeWithText("1 h 2 m left").assertIsDisplayed()
  }

  @Test
  fun theTransportRowExposesEveryControlAJourneyLooksFor() {
    show(content())

    listOf(PREVIOUS_CHAPTER_LABEL, BACK_30_LABEL, PAUSE_LABEL, FORWARD_30_LABEL, NEXT_CHAPTER_LABEL)
      .forEach { composeRule.onNodeWithContentDescription(it).assertIsDisplayed() }
  }

  @Test
  fun theSpeedShownIsTheStates() {
    // Two observations. A screen that printed "1.0x" -- which is what the player reports for most
    // of a session -- passes one of them.
    show(content(speed = 1.4f))
    composeRule.onNodeWithText("$SPEED_LABEL 1.4x").assertIsDisplayed()

    show(content(speed = 0.8f))
    composeRule.onNodeWithText("$SPEED_LABEL 0.8x").assertIsDisplayed()
  }

  @Test
  fun steppingTheSpeedAsksForOneStepAndTheClampIsTheViewModelsJob() {
    // The screen's job is `state.speed + SPEED_STEP`; clamping belongs to `setSpeed`, which is
    // asserted in `AudiobookRepositoryTest`. Asserting the raw value here is what keeps the two
    // responsibilities from both being implemented and both being half right.
    val asked = mutableListOf<Float>()
    show(content(speed = 2.95f), onSpeed = { asked += it })

    composeRule.onNodeWithContentDescription(FASTER_LABEL).performClick()
    composeRule.onNodeWithContentDescription(SLOWER_LABEL).performClick()

    assertThat(asked).hasSize(2)
    assertThat(asked[0]).isCloseTo(3.05f, Offset.offset(0.001f))
    assertThat(asked[1]).isCloseTo(2.85f, Offset.offset(0.001f))
  }

  @Test
  fun theSleepButtonBecomesTheCountdownWhileATimerRuns() {
    // Two states, two labels. Someone half asleep should not have to open a sheet to see how long
    // is left.
    show(content())
    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).assertIsDisplayed()

    show(content(timer = SleepTimerState.Running(90_000L, untilEndOfChapter = false, isFading = false)))
    composeRule.onNodeWithText("1:30").assertIsDisplayed()
    composeRule.onNodeWithText(CANCEL_TIMER_LABEL).assertIsDisplayed()
  }

  @Test
  fun tappingAChapterNamesThatChapter() {
    // Four rows, the third tapped. A callback that always passed the first chapter looks correct
    // on screen and sends every listener to the prologue.
    val tapped = mutableListOf<BookChapter>()
    show(content(), onChapter = { tapped += it })

    composeRule.onNodeWithText("3. A Turn").performClick()

    assertThat(tapped.map { it.title }).containsExactly("A Turn")
    assertThat(tapped.single().startInItemMs).isEqualTo(9_000L)
  }

  @Test
  fun aBookWhoseChaptersHaveNotArrivedStillShowsTheTransport() {
    // Chapter extraction is an HTTP round trip. "Chapter 0 of 0" is worse than nothing, and a
    // blank screen for a second every time a book opens is worse than both.
    show(content().copy(chapterCount = 0, chapterNumber = 0, chapters = emptyList()))

    composeRule.onNodeWithText("Second Book").assertIsDisplayed()
    composeRule.onNodeWithContentDescription(PAUSE_LABEL).assertIsDisplayed()
    assertThat(composeRule.onAllNodesWithText("Chapter 0 of 0").fetchSemanticsNodes()).isEmpty()
  }

  @Test
  fun nothingPlayingIsItsOwnScreen() {
    show(BookPlayerUiState.NothingPlaying)

    composeRule.onNodeWithText(NOTHING_PLAYING_LABEL).assertIsDisplayed()
  }
}
```

> `createAndroidComposeRule<ComponentActivity>` needs `androidTestImplementation(libs.androidx.activity.compose)`
> (or whatever alias the catalogue uses) and `debugImplementation(libs.androidx.compose.ui.test.manifest)`
> so the empty activity exists in the test APK. Plan 2 Task 9's `:feature:library` Compose tests
> already solved this — **read that module's build file and copy what it does**, rather than
> discovering the missing manifest as a `ClassNotFoundException` on the device.

- [ ] **Step 9: Run, measure, and commit**

```bash
./gradlew :feature:book:testDebugUnitTest
./gradlew :feature:book:connectedDebugAndroidTest
```

Then measure the floors. `:feature:book` is a **Compose** module, so per the global constraints and
spec §10 its `@Composable`-bearing files are gated on **LINE**, not BRANCH — the Compose compiler
emits `$changed`/`$dirty` bitmask branches inside author method bodies that no test can reach. The
non-UI files in the module (`BookPlaybackLauncher`, the three `UiState` files' pure functions, the
ViewModels) are gated on **BRANCH**. Two rule kinds in one module, exactly as `:feature:library` and
`:feature:setup` already do — read their entries and follow the shape.

Delete one assertion per rule, confirm the floor goes red, restore it, and record which in the task
report. Confirm `ConventionTest`'s `every Gradle project has a coverage floor` passes with
`:feature:book` present in the exact `"path" to listOf(` form its regex matches.

```bash
git add feature/book app settings.gradle.kts build.gradle.kts
git commit -m "feat(book): the shelf, the book, and a player built for listening"
```

---

## Task 10: The gates — Tier 2 audiobook journeys, the coverage table, the spec corrections

**Files:**
- Create: `app/src/androidTest/kotlin/app/muplay/AudiobookResumeJourneyTest.kt`
- Create: `app/src/androidTest/kotlin/app/muplay/AudiobookChapterJourneyTest.kt`
- Modify: `app/src/androidTest/kotlin/app/muplay/JourneyNavigation.kt` (Plan 3 Task 10's shared
  helper — extend it, do not fork it)
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/e2e.yml`, `.github/workflows/pr.yml`
- Modify: `build.gradle.kts` (the completed floor table)
- Modify: `ci/mutation-probes.sh`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Interfaces:**
- Consumes: every visible label from Task 9, Plan 3 Task 10's `reachLibraryScreen` and its label
  constants, Plan 3 Task 9's `PLAY_LABEL`/`PAUSE_LABEL`, `PlaybackConnection.controller()`
  (Plan 3 Task 5), `MuPlayDatabase.DATABASE_NAME` (Plan 2 Task 1).
- Produces: Tier 2 journeys `AudiobookResumeJourneyTest` and `AudiobookChapterJourneyTest`; the
  completed `coverageFloors` entries for `:feature:book`, `:core:media`, `:core:database`,
  `:core:model` and `:core:testing`; the spec corrections in Step 6.

### What the resume journey has to prove that no unit test can

Spec §10's Tier 2 table has one row for this plan, and it is the row the whole project is for:

> **The resume journey** — *play a book, leave mid-chapter, play music, return — the book resumes
> **exactly**. The original complaint, as a test.*

Every unit test in this plan can be satisfied by a component that is correct in isolation and
unwired. The journey is the only thing that exercises **the real Hilt graph** — including
`MediaModule.provideResumePolicy`, whose reversion to `NeverResume` reddened **nothing** in Task 6
(recorded there, and closed here). It is also the only thing that exercises the real service, the
real notification, real audio focus and a real screen.

Three properties, and each one is asserted as something that **moved**, never as something that was
requested:

- **the book resumes exactly** — asserted against the position the app itself stored, read out of
  the app's own database, and then asserted to keep advancing from there;
- **music in between changes nothing** — asserted with the book's row read before and after;
- **nothing reached the server** — asserted by asking Navidrome, with a positive control that
  proves the question can come back "yes".

### Reading the app's own database from the journey

`androidTest` in `:app` runs **inside the app's process**, so the journey can open the real
`muplay.db` and read what the app actually wrote. That is worth more than an on-screen readout for
the resume assertion: an on-screen `0:12` is a formatted string that could come from anywhere,
whereas `media_progress.positionMs` is the number the resume path will consume.

It is deliberately **read-only**. A journey that seeded the position it later asserted would be
testing its own arithmetic; the whole point is that playing the book is what put the number there.

- [ ] **Step 1: Extend the shared navigation helper**

`app/src/androidTest/kotlin/app/muplay/JourneyNavigation.kt` — Plan 3 Task 10 extracted
`reachLibraryScreen` here from `BrowseJourneyTest`. Add, in the same style:

```kotlin
/**
 * From the library screen to the bookshelf: switch to the Audiobooks library.
 *
 * The label constants are duplicated from the production code rather than shared with it — Plan 2's
 * stance and Plan 3's: a journey is a black-box walk through what a user sees, and a shared
 * constant would let a wording change pass unnoticed.
 */
fun ComposeTestRule.reachBookshelf() {
  reachLibraryScreen()
  onNodeWithText(AUDIOBOOKS_LIBRARY_NAME).performClick()
  waitUntil(TIMEOUT_MILLIS) {
    onAllNodesWithText(CONTINUE_LISTENING_LABEL).fetchSemanticsNodes().isNotEmpty() ||
      onAllNodesWithText(SECOND_BOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
  }
}

/** Opens one book from the shelf and starts it playing from wherever it left off. */
fun ComposeTestRule.startBook(title: String) {
  onNodeWithText(title).performClick()
  waitUntil(TIMEOUT_MILLIS) { onAllNodesWithText(RESUME_LABEL).fetchSemanticsNodes().isNotEmpty() }
  onNodeWithText(RESUME_LABEL).performClick()
  awaitPauseControl()
}

/**
 * The two players label their pause control differently, and a journey walks through both.
 *
 * Plan 3's music player uses a **text** label; Plan 4's book player uses a **content description**
 * on an icon button, because a book player's transport row is five icons and five words would not
 * fit across a phone. Rather than pretending they are the same, these helpers accept either — and
 * assert they found **exactly one**, so "neither" can never pass as a silent no-op. That is rule 5
 * applied to a navigation helper: a helper that quietly did nothing would make every assertion
 * after it meaningless.
 */
private fun ComposeTestRule.pauseControlCount(): Int =
  onAllNodesWithText(PAUSE_LABEL).fetchSemanticsNodes().size +
    onAllNodesWithContentDescription(PAUSE_LABEL).fetchSemanticsNodes().size

fun ComposeTestRule.awaitPauseControl() {
  waitUntil(TIMEOUT_MILLIS) { pauseControlCount() > 0 }
}

fun ComposeTestRule.pausePlayback() {
  awaitPauseControl()
  check(pauseControlCount() == 1) { "expected exactly one pause control, found ${pauseControlCount()}" }
  if (onAllNodesWithText(PAUSE_LABEL).fetchSemanticsNodes().isNotEmpty()) {
    onNodeWithText(PAUSE_LABEL).performClick()
  } else {
    onNodeWithContentDescription(PAUSE_LABEL).performClick()
  }
}

/**
 * A real system back press.
 *
 * Not a node labelled "Back": predictive back is default-on (spec section 7) and the app's back
 * affordance is the system gesture rather than a labelled button, so looking for text would find
 * nothing and the failure would read as "the screen did not render".
 */
fun goBack() {
  InstrumentationRegistry.getInstrumentation().uiAutomation
    .executeShellCommand("input keyevent 4").close()
}

const val PAUSE_LABEL = "Pause"

const val AUDIOBOOKS_LIBRARY_NAME = "Audiobooks"
const val MUSIC_LIBRARY_NAME = "Music"
const val SECOND_BOOK_TITLE = "Second Book"
const val TEST_BOOK_TITLE = "Test Book"
const val MULTI_PART_BOOK_TITLE = "Multi Part Book"
const val CONTINUE_LISTENING_LABEL = "Continue listening"
const val RESUME_LABEL = "Resume"
```

- [ ] **Step 2: Write the resume journey — the original complaint, as a test**

`app/src/androidTest/kotlin/app/muplay/AudiobookResumeJourneyTest.kt`:

```kotlin
package app.muplay

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.muplay.database.MuPlayDatabase
import app.muplay.media.PlaybackConnection
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The reason this application exists, as a test.**
 *
 * Spec section 1: *"Real audiobook resume. Every book remembers its own exact position and keeps it
 * across an intervening music session."* Spec section 10's Tier 2 table: *"play a book, leave
 * mid-chapter, play music, return — the book resumes exactly. The original complaint, as a test."*
 *
 * This is the only test in the plan that runs the **real Hilt graph**, so it is the only thing that
 * can catch `MediaModule.provideResumePolicy` being wired back to `NeverResume` — a one-line
 * reversion that Task 6's own suite could not see, because those tests construct the policy
 * directly.
 *
 * Two habits, deliberately:
 *
 * - The position is read out of **the app's own `media_progress` table**, read-only. An on-screen
 *   `0:12` is a formatted string that could come from anywhere; `positionMs` is the number the
 *   resume path will actually consume. Seeding that number from the test would be testing this
 *   test's arithmetic.
 * - Every "it resumed" assertion is paired with "and then it kept going". A player parked at the
 *   right position, silent, satisfies the first alone — and so does a player that seeked and then
 *   failed to decode.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookResumeJourneyTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule
  val notificationPermission: GrantPermissionRule =
    GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /**
   * The app's real database, opened read-only from the same process.
   *
   * `createFromAsset`/`fallbackToDestructiveMigration` are deliberately absent: if this open ever
   * fails because of a schema change, that is the migration (Task 2) failing on a real device,
   * which is exactly the thing worth finding out about here.
   */
  private fun db(): MuPlayDatabase =
    Room.databaseBuilder(context, MuPlayDatabase::class.java, MuPlayDatabase.DATABASE_NAME)
      .addMigrations(app.muplay.database.MIGRATION_4_5)
      .build()

  @Test
  fun theOriginalComplaint() {
    // 1. Play a book, and leave it mid-chapter.
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("the book to advance past six seconds") { controller.currentPosition > 6_000L }
    val bookMediaId = onMain { controller.currentMediaItem!!.mediaId }
    composeRule.pausePlayback()
    val leftAt = awaitStoredPosition(bookMediaId) { it > 5_000L }

    // The position it stopped at is mid-chapter, not on a boundary. Second Book's chapters start at
    // 0 / 4000 / 9000 / 15000, so anything between 6 s and 9 s is inside chapter 2 and is not any
    // chapter's start -- which is what makes "resumed exactly" and "resumed at the chapter start"
    // different numbers.
    assertThat(leftAt).describedAs("left mid-chapter").isBetween(5_000L, 9_000L)

    // 2. Listen to music for a while. A real queue, real audio, and long enough that the book's
    //    away time is a real away time.
    goBack()
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText(MUSIC_LIBRARY_NAME).performClick()
    composeRule.onAllNodesWithText("Open")[0].performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Track 1").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Track 1").performClick()
    composeRule.awaitPauseControl()
    awaitOnMain("music to advance") { controller.currentPosition > 2_000L }
    assertThat(onMain { controller.currentMediaItem!!.mediaId })
      .describedAs("music must actually be what is playing").isNotEqualTo(bookMediaId)
    composeRule.pausePlayback()

    // 3. The book's own row is untouched by any of that. This is spec section 3's whole claim --
    //    "two pointer lists over one progress table" -- observed rather than argued.
    assertThat(awaitStoredPosition(bookMediaId) { true })
      .describedAs("an intervening music session must not touch the book's row")
      .isEqualTo(leftAt)

    // 4. Come back to the book.
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)

    // 5. It is the book that is playing, and it is playing from where it was left -- minus a smart
    //    rewind bounded by the largest band reachable inside this journey's wall clock (5 s, the
    //    1-minute-to-1-hour band).
    awaitOnMain("the book to be current again") {
      controller.currentMediaItem?.mediaId == bookMediaId
    }
    val resumedAt = onMain { controller.currentPosition }
    assertThat(resumedAt)
      .describedAs("resumed position, having left at $leftAt")
      .isGreaterThan(leftAt - 5_500L)
      .isLessThanOrEqualTo(leftAt)
    // Not zero and not a chapter start -- the two ways a broken resume looks plausible.
    assertThat(resumedAt).isNotZero
    assertThat(resumedAt).isNotIn(0L, 4_000L, 9_000L, 15_000L)

    // 6. And it kept going. A player parked at the right number is not a book that resumed.
    awaitOnMain("audio to advance past where it resumed") { controller.currentPosition > resumedAt + 1_500L }
  }

  @Test
  fun twoBooksKeepTwoPlaces() {
    // The assertion one book cannot make. With a single book, "book B resumed at B's position" and
    // "resumed at the only position stored" are the same program -- which is why Task 1 exists.
    composeRule.reachBookshelf()
    val controller = connectController()

    composeRule.startBook(SECOND_BOOK_TITLE)
    val secondId = onMain { controller.currentMediaItem!!.mediaId }
    awaitOnMain("the second book to advance") { controller.currentPosition > 6_000L }
    composeRule.pausePlayback()
    val secondLeftAt = awaitStoredPosition(secondId) { it > 5_000L }

    goBack()
    composeRule.startBook(TEST_BOOK_TITLE)
    val testId = onMain { controller.currentMediaItem!!.mediaId }
    awaitOnMain("the first book to advance") { controller.currentPosition > 2_000L }
    composeRule.pausePlayback()
    val testLeftAt = awaitStoredPosition(testId) { it > 1_500L }

    // Two different books, two different positions, and they must be far enough apart that one
    // could not be mistaken for the other.
    assertThat(secondLeftAt).isGreaterThan(testLeftAt + 2_000L)

    goBack()
    composeRule.startBook(SECOND_BOOK_TITLE)
    awaitOnMain("the second book to be current") { controller.currentMediaItem?.mediaId == secondId }

    val resumed = onMain { controller.currentPosition }
    assertThat(resumed).isGreaterThan(testLeftAt + 1_000L)
    assertThat(resumed).isLessThanOrEqualTo(secondLeftAt)
    awaitOnMain("audio to advance") { controller.currentPosition > resumed + 1_000L }
  }

  @Test
  fun aMultiFileBookComesBackOnTheRightPart() {
    // Half of "per-book resume", and the half a single-file corpus cannot express.
    composeRule.reachBookshelf()
    composeRule.startBook(MULTI_PART_BOOK_TITLE)
    val controller = connectController()

    // Play into the second file: Part One is four seconds long.
    awaitOnMain("playback to reach the second part") { controller.currentMediaItemIndex == 1 }
    awaitOnMain("the second part to advance") { controller.currentPosition > 2_000L }
    val partTwoId = onMain { controller.currentMediaItem!!.mediaId }
    composeRule.pausePlayback()
    awaitStoredPosition(partTwoId) { it > 1_500L }

    goBack()
    composeRule.reachLibraryScreen()
    composeRule.reachBookshelf()
    composeRule.startBook(MULTI_PART_BOOK_TITLE)

    // The right FILE, not just a position. A resume that always answered file one would satisfy
    // every single-file assertion in this suite.
    awaitOnMain("the second part to be current again") {
      controller.currentMediaItem?.mediaId == partTwoId
    }
    assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(1)
    awaitOnMain("audio to advance") { controller.currentPosition > 500L }
  }

  @Test
  fun theBookKeepsPlayingWithTheAppOffScreen() {
    // A book is listened to with the screen off. If the foreground service does not survive it,
    // nothing else in this plan matters.
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("audio to advance") { controller.currentPosition > 1_000L }

    val before = onMain { controller.currentPosition }
    shell("input keyevent 3") // KEYCODE_HOME
    Thread.sleep(3_000L)

    assertThat(onMain { controller.currentPosition })
      .describedAs("position after three seconds on the home screen")
      .isGreaterThan(before + 2_000L)
  }

  /**
   * **Book positions are local only** (spec sections 2, 4, 11), asserted at the server rather than
   * at this app's own source.
   *
   * Task 6's `LocalOnlyProgressTest` asserts the client has no way to send a position. This asserts
   * that after a whole resume journey, Navidrome holds none — a genuinely independent observation.
   *
   * The **positive control** is what makes it a gate rather than a wish: the test writes a bookmark
   * of its own through raw HTTP, confirms the query can see it, and deletes it. Without that, "no
   * bookmarks" is equally satisfied by a query that never worked.
   */
  @Test
  fun nothingAboutAPositionEverReachesTheServer() {
    // The control, first: prove the question can come back "yes".
    http("createBookmark", "id" to firstAudiobookSongId(), "position" to "12345")
    assertThat(http("getBookmarks")).contains("12345")
    http("deleteBookmark", "id" to firstAudiobookSongId())
    assertThat(http("getBookmarks")).doesNotContain("12345")

    // Now the journey.
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("the book to advance") { controller.currentPosition > 4_000L }
    composeRule.pausePlayback()
    Thread.sleep(2_000L)

    val bookmarks = http("getBookmarks")
    val playQueue = http("getPlayQueue")

    // Navidrome returns an envelope with no `bookmarks`/`playQueue` member when there are none.
    assertThat(bookmarks).describedAs("this app must never create a server-side bookmark")
      .doesNotContain("\"bookmark\"")
    assertThat(playQueue).describedAs("this app must never save a server-side play queue")
      .doesNotContain("\"entry\"")
  }

  // ---- helpers ------------------------------------------------------------------------------

  /**
   * Reads the app's own `media_progress` row for a media id, waiting for it to satisfy `predicate`.
   *
   * Read-only, and it is the app that put the number there. A journey that wrote the position it
   * later asserted would be testing its own arithmetic rather than the writer.
   */
  private fun awaitStoredPosition(mediaId: String, predicate: (Long) -> Boolean): Long {
    val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      val row = runBlocking { db().use { it.mediaProgressDao().find(mediaId) } }
      if (row != null && predicate(row.positionMs)) return row.positionMs
      Thread.sleep(200)
    }
    throw AssertionError("no media_progress row for $mediaId satisfying the predicate")
  }

  private val httpClient = OkHttpClient()

  /** A raw Subsonic call from the **test**, never through the app's client. */
  private fun http(command: String, vararg params: Pair<String, String>): String {
    val url = okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(4533)
      .addPathSegments("rest/$command.view")
      .addQueryParameter("u", USERNAME).addQueryParameter("p", PASSWORD)
      .addQueryParameter("v", "1.16.1").addQueryParameter("c", "JourneyTest")
      .addQueryParameter("f", "json")
      .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
      .build()
    return httpClient.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
  }

  private fun firstAudiobookSongId(): String {
    val json = http("getAlbumList2", "type" to "alphabeticalByName", "size" to "50", "musicFolderId" to "2")
    return Regex("\"id\":\"([^\"]+)\"").find(json)!!.groupValues[1]
      .let { albumId -> http("getAlbum", "id" to albumId) }
      .let { Regex("\"id\":\"([^\"]+)\"").findAll(it).last().groupValues[1] }
  }

  private var controller: MediaController? = null

  private fun connectController(): MediaController = controller ?: onMain {
    runBlocking { PlaybackConnection(context).controller() }
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

  private fun awaitOnMain(description: String, timeoutMs: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (onMain(condition)) return
      Thread.sleep(100)
    }
    throw AssertionError("timed out waiting for $description")
  }

  private fun shell(command: String) {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
  }

  private companion object {
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"
    const val TIMEOUT_MILLIS = 30_000L
  }
}
```

> **Look the media id up from the controller, never from the mirror.** `currentMediaItem.mediaId`
> is exactly the `media_progress` primary key, so the journey never needs a title-to-id resolution
> and never needs to know how the mirror stores anything. That is also what keeps this a black-box
> walk: the only two things the journey touches are what a user sees and what the app persisted.
>
> Opening the app's Room database from the same process while the app also has it open is safe —
> SQLite handles multiple connections in one process, and Room's own `MigrationTestHelper` does the
> same thing. Do **not** hold the handle open across the whole test; `use` it per read, as above.

- [ ] **Step 3: Write the chapter, speed and sleep-timer journey**

`app/src/androidTest/kotlin/app/muplay/AudiobookChapterJourneyTest.kt` — same rules, same helpers:

```kotlin
  @Test
  fun aBooksChaptersAreListedInOrderAndTappingOneJumpsThere() {
    composeRule.reachBookshelf()
    composeRule.onNodeWithText(SECOND_BOOK_TITLE).performClick()
    composeRule.waitUntil(TIMEOUT_MILLIS) {
      composeRule.onAllNodesWithText("Epilogue").fetchSemanticsNodes().isNotEmpty()
    }

    // All four, by name, on screen. Chapters read out of the file's own bytes over HTTP from a real
    // Navidrome -- the differentiator, end to end. Four names, so a screen showing one chapter or
    // the wrong book's chapters fails.
    listOf("Prologue", "The Long Middle", "A Turn", "Epilogue").forEach {
      composeRule.onNodeWithText(it).assertIsDisplayed()
    }

    composeRule.onNodeWithText("A Turn").performClick()
    val controller = connectController()
    // "A Turn" spans 9 000..15 000. Landing inside it, from a standing start, is a seek that
    // happened -- and it is not 0, not the resume position, and not the previous chapter.
    awaitOnMain("playback to reach the third chapter") { controller.currentPosition >= 9_000L }
    assertThat(onMain { controller.currentPosition }).isBetween(9_000L, 15_000L)
    awaitOnMain("audio to advance from there") { controller.currentPosition > 10_500L }
  }

  @Test
  fun nextChapterMovesToTheNextChapterAndNotToTheNextTrack() {
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("playback to start") { controller.currentPosition > 500L }

    composeRule.onNodeWithContentDescription(NEXT_CHAPTER_LABEL).performClick()

    // Second Book is ONE media item with four chapters, so "next" here must be a seek within the
    // item. A `seekToNextMediaItem` would end the book -- which is exactly the bug a music player
    // has when it meets an audiobook.
    awaitOnMain("the position to jump forward") { controller.currentPosition >= 4_000L }
    assertThat(onMain { controller.currentMediaItemIndex }).isZero
    assertThat(onMain { controller.currentPosition }).isBetween(4_000L, 9_000L)
  }

  @Test
  fun theSpeedIsPerBookAndItSurvivesLeavingAndComingBack() {
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("playback to start") { controller.currentPosition > 500L }

    repeat(4) { composeRule.onNodeWithContentDescription(FASTER_LABEL).performClick() }
    awaitOnMain("the speed to reach 1.4x") { controller.playbackParameters.speed > 1.35f }

    // Leave the book entirely, play music, and come back.
    composeRule.pausePlayback()
    goBack()
    composeRule.reachLibraryScreen()
    composeRule.onNodeWithText(MUSIC_LIBRARY_NAME).performClick()
    composeRule.onAllNodesWithText("Open")[0].performClick()
    composeRule.onNodeWithText("Track 1").performClick()
    awaitOnMain("music to be playing") { controller.currentPosition > 1_000L }

    // The music is at normal speed. This is the bug Task 7 is named for, on a real screen.
    assertThat(onMain { controller.playbackParameters.speed }).isEqualTo(1.0f)

    composeRule.pausePlayback()
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)

    // ...and the book still has the speed the listener chose.
    awaitOnMain("the book's speed to come back") { controller.playbackParameters.speed > 1.35f }
  }

  @Test
  fun theSleepTimerPausesTheBookAndTheAudioComesBack() {
    composeRule.reachBookshelf()
    composeRule.startBook(SECOND_BOOK_TITLE)
    val controller = connectController()
    awaitOnMain("playback to start") { controller.currentPosition > 500L }

    composeRule.onNodeWithText(SLEEP_TIMER_LABEL).performClick()
    composeRule.onNodeWithText(END_OF_CHAPTER_LABEL).performClick()

    // Second Book's chapters are 4/5/6/6 seconds, so end-of-chapter from a standing start is a few
    // seconds away -- the shortest real assertion available without a special fixture.
    awaitOnMain("the timer to pause playback", timeoutMs = 20_000L) { !controller.isPlaying }
    val pausedAt = onMain { controller.currentPosition }
    assertThat(pausedAt).describedAs("paused at a chapter boundary").isBetween(3_500L, 5_000L)

    // The trap, on a real screen: the volume must have come back, which is observable as audio
    // advancing again.
    onMain { controller.play() }
    awaitOnMain("audio to advance after the timer") { controller.currentPosition > pausedAt + 1_500L }
  }
```

> `FASTER_LABEL` and `NEXT_CHAPTER_LABEL` are content descriptions on icon buttons in
> `BookPlayerScreen` (Task 9). Duplicate the strings here rather than importing them, per Plan 2's
> and Plan 3's stance on journeys.

- [ ] **Step 4: Run the journeys and prove each part can fail**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./ci/prepare-emulator.sh
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS — Plan 1's `FirstRunJourneyTest`, Plan 2's `BrowseJourneyTest` and
`ScopedShuffleJourneyTest`, Plan 3's `PlaybackJourneyTest` and `MuPlaybackServiceTest`, plus
`AudiobookResumeJourneyTest` 5/5 and `AudiobookChapterJourneyTest` 4/4.

Then, one mutation at a time, restored after each:

1. **`MediaModule.provideResumePolicy` back to `NeverResume`.** Expect `theOriginalComplaint`,
   `twoBooksKeepTwoPlaces` and `aMultiFileBookComesBackOnTheRightPart` to fail. **This closes the
   hole Task 6 Step 9 mutation 1 recorded as ungated** — record here, by name, that it is now gated
   and by which test.
2. In `AudiobookResumePolicy`, ignore `SmartRewind` and return `item.positionMs`. Expect **no
   journey to fail**, because the journey's band deliberately admits any rewind up to 5 s. That is
   correct — the rewind's own gate is `SmartRewindTest`, in Tier 1, where the table is asserted band
   by band. Record it, so nobody later reads the journey as covering the rewind.
3. In `BookPlaybackLauncher.play`, drop `audiobookSnapshot.refresh()`. Expect
   `theOriginalComplaint` to fail **intermittently** — which is the cold-snapshot race, and the
   reason `refresh()` is on the play path. Run it five times; record how many failed. An
   intermittent gate is worth naming as intermittent rather than trusting.
4. In `BookViewModel.playChapter`, drop the `seekTo`. Expect
   `aBooksChaptersAreListedInOrderAndTappingOneJumpsThere` to fail — a single-file book's chapters
   all share one media id, so without the seek nothing moves.
5. In `BookSpeedController`, remove the reset for non-audiobook items. Expect
   `theSpeedIsPerBookAndItSurvivesLeavingAndComingBack` to fail on the music assertion.
6. In `SleepTimerController.fire`, remove `restoreVolume()`. Expect
   `theSleepTimerPausesTheBookAndTheAudioComesBack` to fail at the "audio must advance again" step.
7. Add a `savePlayQueue` call anywhere on the play path. Expect
   `nothingAboutAPositionEverReachesTheServer` to fail **and** Task 6's `LocalOnlyProgressTest` to
   fail. Two independent detectors for the constraint that matters most.
8. Stop the Navidrome container and re-run. Expect red, not green.

- [ ] **Step 5: Complete and prove the coverage table**

Run the whole thing, in the order the two tiers run it:

```bash
./gradlew test
./gradlew jacocoJvmCoverageVerification
./ci/prepare-emulator.sh
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest \
          :feature:book:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
```

For every module this plan touched — `:core:model`, `:core:testing`, `:core:network`,
`:core:database`, `:core:media`, `:feature:book`, `:app` — read the measured per-class ratios out of
each module's `jacocoTestReport.xml` and make its `coverageFloors` entry match.

`:core:media` is now large enough that one blended rule would hide a regression in any of its parts
behind the others. Its floors, by kind:

| Kind | Classes added by this plan | Metric | Tier |
|---|---|---|---|
| Pure decisions | `SmartRewind`, `ChapterAssembly`, `BookTimeline`, `BookPlaybackSettings`, `SleepTimerFade`, `ShakeDetector`, `AudiobookResumePolicy` | BRANCH | **Tier 1 — these must clear their floors from JVM data alone** |
| Media3 adapters | `ChapterReader`, `BookSpeedController`, `SleepTimerController` | BRANCH | instrumented |
| Repositories and snapshots | `ChapterRepository`, `AudiobookSnapshot`, `ResumptionQueue` | BRANCH | instrumented |
| Android plumbing with no author conditional | `ShakeSensor` | **LINE** — a BRANCH rule over a class whose only branches are two unreachable early returns matches zero-total counters and passes silently at every minimum through JaCoCo's `isNaN` path | instrumented |

`:feature:book` carries **two** rule kinds, as `:feature:library` already does: **LINE** for the
`@Composable`-bearing files (`BookshelfScreen`, `BookScreen`, `BookPlayerScreen`), because branch
coverage over Compose measures the Compose compiler's `$changed`/`$dirty` bitmask tests rather than
this project; **BRANCH** for everything else in the module.

Rules that are not negotiable here, and each has bitten this build once:

- **Every floor is measured from a real report**, never invented. Where a measured branch ratio on
  non-UI code is below 0.90, the answer is another test, not a lower floor.
- **`requiresInstrumentedData` is a measurement.** Delete the instrumented `.ec` files, run
  `jacocoJvmCoverageVerification`, and set the flag on exactly the floors that fail.
- **Every floor must be watched fail once.** Delete one assertion per rule, confirm red, restore,
  and record which assertion per rule in the task report.
- **No `COVERAGE:` warning may be left standing.** `warnUngatedClasses` and `warnVacuousFloors`
  print to the build log and as GitHub annotations; `:feature:book` is a new module and will produce
  several on the first run. Each is either a class that needs a floor or a floor that matches
  nothing.
- **A floor of `0.00`, or one whose matched classes carry no counters of its own kind, gates
  nothing.** That is the defect this build has already had once, in the fast tier's own subset.

- [ ] **Step 6: Correct the spec**

Everything this plan found wrong, incomplete or unspecified in
`docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`. Per the roadmap's definition of done
item 6, these are corrected **in the spec**, not recorded in a report.

1. **§3, the schema block and §5's "Other audiobook behaviour" — the grain defect.** The spec puts
   `speed`, `skipSilence` and `gainDb` on `media_progress`, keyed on the media id, and §5 repeats
   *"Per-item speed, silence skipping and gain, all stored on the progress row."* That is right for
   a single-file M4B and **wrong for a book that is many files** — the ordinary shape of a ripped
   audiobook — where it stores one speed per file and loses the listener's choice at every chapter
   boundary. Correct it to:
   > Position and finished-ness are per **item**, on `media_progress`. **Speed and silence skipping
   > are per book**, on `book_settings`, keyed on the book id (the album id of a book in a library
   > the user tagged Audiobooks). `media_progress.speed` and `.skipSilence` remain as columns but
   > are not the authority for a book and are written by nothing; `media_progress.gainDb` is
   > genuinely per-file (ReplayGain is a property of the file) and is **unwritten and unapplied** —
   > applying it means a gain stage in the audio pipeline, which no plan has yet taken.
2. **§3, the `MuPlayer` paragraph.** It says the seam discards *"the caller's index and position"*.
   Plan 3 already flagged the index half; Plan 4 completes it with the reason. Reword to:
   > `MuPlayer` is a `ForwardingPlayer` overriding all six `setMediaItem(s)` overloads to discard
   > the caller's **position** and ask a `ResumePolicy` instead. The **index is the caller's** — it
   > is queue membership, and "play chapter 5" is a legitimate request that `resolve(mediaIds,
   > requestedIndex)` cannot distinguish from "resume this book", since both arrive as index 0. The
   > caller therefore chooses the item (`BookPlaybackLauncher`) and the policy chooses the position.
   > "Start from the beginning" is expressed by **clearing** the book's progress, not by requesting
   > position 0.
3. **§5, "Smart rewind on resume, scaled to how long the book was paused."** One sentence that
   admits a constant, a linear ramp and doing nothing. Replace it with the band table from Task 5 —
   under 15 s → 0 s, 15 s–1 min → 2 s, 1 min–1 h → 5 s, 1 h–1 day → 10 s, over 1 day → 20 s, top
   band bounded — and note that the position is clamped at zero.
4. **§5, "Sleep timer, with a shake-to-extend affordance."** Add the four decisions it does not
   make: it **fades** over the last 20 s rather than cutting; it **pauses** rather than stopping, so
   the position is written through the ordinary persistence points; **"end of chapter" is a
   position**, computed by the caller from the chapter timeline, not a duration; and **a shake
   counts for 60 s after the timer fired**, because waking up just after the audio stopped is the
   ordinary case.
5. **§5, "Not yet verified: Navidrome's `format=raw` path … `MetadataRetriever` extraction has not
   been re-run against a Navidrome URL end-to-end. First audiobook plan closes this."** **Closed.**
   Replace with the measured result from Task 3, including the non-faststart case, the four-book
   corpus it was measured over, and — if the non-faststart case behaved differently against
   Navidrome than against spike S3's Python server — exactly how.
6. **§5, the chapter API block.** Add the two details spike S3 recorded that the spec does not:
   `androidx.media3.exoplayer.MetadataRetriever` **does not exist in 1.11.0** (it is
   `androidx.media3.inspector.MetadataRetriever`), and `Chapter.getTitle()` returns an
   `androidx.media3.common.Label`, not a `String`.
7. **§12, the risk row** *"`MetadataRetriever` fails against a real Navidrome `format=raw` URL |
   Low | Range precondition already verified; first audiobook plan closes it end-to-end."* Replace
   the mitigation column with the measurement and mark the risk closed, naming `ChapterReaderTest`.
8. **§4, "Resume — local only".** It states the intention. Add the **mechanism**, because a
   constraint with no gate is a comment: `LocalOnlyProgressTest` asserts `SubsonicSource`'s method
   set and `SubsonicApi`'s endpoint set **exactly**, so any way of sending a position fails the
   build by name; and `AudiobookResumeJourneyTest.nothingAboutAPositionEverReachesTheServer` asks
   Navidrome directly, with a positive control that writes and deletes a bookmark of its own so the
   query is provably able to come back "yes".
9. **§10's Tier 2 table** — add a line under it, in the form Plans 2 and 3 used:
   > Plan 4 added `AudiobookResumeJourneyTest` (the original complaint — a book left mid-chapter,
   > a real music session in between, and the book resuming at the position the app itself stored
   > and then advancing from it; two books keeping two places; a multi-file book coming back on the
   > right file; playback surviving the app going off screen; and nothing about a position reaching
   > the server, with a positive control) and `AudiobookChapterJourneyTest` (chapters read from the
   > file's own bytes over a real Navidrome and listed in order, chapter navigation seeking inside a
   > single-file book, a per-book speed surviving an intervening music session, and a sleep timer
   > that pauses and gives the audio back). `:core:media` gained `ChapterReaderTest`,
   > `ChapterRepositoryTest`, `AudiobookSnapshotTest`, `AudiobookResumeTest`, `ResumptionQueueTest`,
   > `BookSpeedControllerTest`, `ProgressWriterSilenceSkipTest` and `SleepTimerControllerTest`;
   > `:core:database` gained `ChapterDaoTest`, `BookSettingsDaoTest`, `MigrationTest` and
   > `AudiobookRepositoryTest`; `:feature:book` gained `BookshelfScreenTest` and
   > `BookPlayerScreenTest`. Tier 1 gained `SmartRewindTest`, `ChapterAssemblyTest`,
   > `BookTimelineTest`, `BookSummariesTest`, `AudiobookResumePolicyTest`,
   > `BookPlaybackSettingsTest`, `SleepTimerFadeTest`, `ShakeDetectorTest`, `LocalOnlyProgressTest`,
   > `BookFixturesTest` and `:feature:book`'s three state suites.
10. **§10's countermeasure 1 (the external oracle).** Add the second oracle this plan introduced:
    **`ffprobe` over the committed audio fixtures**, recorded in
    `core/testing/src/main/resources/fixtures/books.tsv` and re-derived in both tiers by
    `ci/probe-chapters.sh --check`. Same stance as the OpenAPI spec: an independent reader of the
    same bytes, and a golden file recording what this project's own code produced would not be one.
11. **§10's corpus description.** The nightly-fixture note says *"one chaptered M4B"*. The corpus is
    now **four books**: a three-chapter faststart M4B, a four-chapter M4B with **unequal** chapters,
    a two-chapter **non-faststart** M4B, and a three-file book with **no chapter atoms**. Say what
    each is for — the reasons are in Task 1's table and they are the reason the resume tests can
    discriminate at all.
12. **§9's "Deferred, but designed for".** `onPlaybackResumption` is now implemented (Task 6), and
    §10's old "Session" Tier 1 row named it as unimplemented. Update whichever text still says so.

- [ ] **Step 7: Confirm the workflows run everything, and measure the wall clock**

`.github/workflows/e2e.yml` — the `script:` block gains `:feature:book`:

```yaml
          script: |
            ./ci/prepare-emulator.sh
            ./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest \
                      :feature:book:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
              || { adb logcat -d > emulator-logcat.txt; exit 1; }
```

**This job now plays real audio in real time, a great deal more of it than Plan 3's did.**
`SleepTimerControllerTest` runs several real countdowns; `AudiobookResumeTest` plays a 21-second
book five times over; the two journeys each play a book, then music, then the book again.
**Measure the real duration of a full run and record it in the task report.** If it lands within
ten minutes of `timeout-minutes: 45`, raise the limit and say what was measured — a gate that starts
flaking on time gets disabled, which is the worst outcome available. If it lands well past 45, split
the emulator job in two (module suites and app journeys) rather than trimming assertions, and say
which was done and why.

Both workflows also gain the `Verify chapter oracle` step from Task 1 Step 8, if it is not already
there.

- [ ] **Step 8: Final green run and commit**

Every one of these must pass:

```bash
./gradlew build
./gradlew verifyNoMockFrameworks
./gradlew :app:verifyReleaseManifest :app:verifyDebugManifest
./gradlew jacocoJvmCoverageVerification
md5sum -c ci/fixtures.md5 && ./ci/probe-chapters.sh --check
./gradlew :core:network:liveNavidromeTest                                  # container up
./gradlew :core:database:connectedDebugAndroidTest :core:media:connectedDebugAndroidTest \
          :feature:book:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
./ci/mutation-probes.sh                                                    # every probe still caught
```

```bash
git add app build.gradle.kts .github/workflows ci docs/superpowers/specs
git commit -m "ci: tier 2 audiobook journeys, the completed coverage table, and the spec corrections"
```

---

## Definition of done

1. All tasks' tests pass; **both tiers green**.
2. **Tier 2 carries this plan's journeys**: `AudiobookResumeJourneyTest` and
   `AudiobookChapterJourneyTest` in `:app`'s emulator suite, plus `:core:media`'s eight new
   instrumented classes, `:core:database`'s four, and `:feature:book`'s two — and **each has been
   watched go red**.
3. Coverage ≥ 90% on every module this plan touched — **branch** for non-UI code, **line** for
   `@Composable`-bearing files and for Android plumbing that carries no author-written conditional.
   Every floor measured from a real report, every `requiresInstrumentedData` flag measured rather
   than judged, and **every floor watched fail once**. `:feature:book` is in `coverageFloors`, and
   no `COVERAGE:` warning is left standing.
4. No mock framework anywhere in the dependency graph — `verifyNoMockFrameworks` resolves every test
   runtime classpath including `:feature:book`'s. Every stand-in in this plan is hand-written: the
   map-backed `AudiobookItemSource`, `NoOpPlayer` (Plan 3's), the recording `persist` lambda, and
   the seeded in-memory Room.
5. Every new external-API assumption is backed by a live test against the Navidrome container:
   all four books stream `format=raw` with an accurate `Content-Length` and honour a tail Range
   (`LiveNavidromeTest`); Media3 reads their chapters over that path and agrees with `ffprobe`,
   **including the non-faststart file** (`ChapterReaderTest`, closing spike S3's stated limitation
   and spec §12's risk row).
6. **The book resumes, and the resume is proven by audio that advanced** — never by a seek that was
   requested. Every resume assertion in this plan is a position playback reached *and then moved on
   from*, a row the app itself wrote, or a media item that became current. No test here passes
   against a player that seeked and then rendered silence.
7. **The corpus can tell two answers apart.** Four books: a three-chapter faststart M4B, a
   four-chapter M4B with unequal chapters, a two-chapter non-faststart M4B, and a three-file book
   with no chapter atoms. Every resume position used in a test is deliberately **off** every chapter
   boundary in its book, every rewind band carries a **distinct** value, and every "two books" claim
   is asserted with two.
8. **Music still restarts from zero**, and it does so structurally: a music media id has no entry in
   `AudiobookSnapshot`, so `AudiobookResumePolicy` has nothing to resume from. Asserted against a
   real player with a real `media_progress` row in place (`musicStartsAtZeroEvenWithProgressStored`).
9. **A book's speed is the book's**, at the grain a multi-file book actually has, and it does not
   follow the listener into music (`BookSpeedControllerTest`, `theSpeedIsPerBookAndItSurvivesLeavingAndComingBack`).
   `media_progress.speed` is proven **not** to be consulted.
10. **Chapters come from the file's own bytes**, in order, with populated end times — and the wiring
    that silently breaks them is asserted to be broken, so `setMediaSourceFactory` cannot be deleted
    quietly.
11. **The sleep timer gives the audio back.** The volume is restored at every exit and the restore
    is asserted as audio that advanced, not as a float that read 1.0.
12. **Book positions are local only, and it is checkable.** `LocalOnlyProgressTest` asserts the
    client's method and endpoint sets exactly; the journey asks Navidrome directly, with a positive
    control that proves the question can come back "yes".
13. **Plan 3's seam is intact.** `MuPlayerTest`, `ResumePolicyTest`, `ProgressWriterTest`,
    `AudioFocusTest` and `GaplessTest` are all still green, `NeverResume` still exists with its
    test, and `ResumePolicy.resolve` still has exactly two parameters. The four additive changes to
    Plan 3's files (`PlaybackState`'s two fields, `MuPlayerFactory.wrap`, `MediaProgressDao`'s three
    queries, the `provideResumePolicy` binding) are the only ones, and each is named in this plan's
    seam section.
14. **Plan 3's recorded debt is paid.** Its Task 8 Step 10 mutation 4 — removing `ProgressWriter`'s
    `DISCONTINUITY_REASON_SILENCE_SKIP` guard, which reddened nothing — now reddens
    `ProgressWriterSilenceSkipTest.skippingSilenceDoesNotInchTheBookForward`.
15. **The one binding that matters is gated.** Reverting `MediaModule.provideResumePolicy` to
    `NeverResume` reddens `AudiobookResumeJourneyTest.theOriginalComplaint`, and it has been watched
    do so.
16. Anything discovered to be wrong in the spec is corrected **in the spec** — the twelve items in
    Task 10 Step 6, at minimum.
