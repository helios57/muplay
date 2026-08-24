# MuPlay — Design (Kotlin / Compose)

**Supersedes** `2026-08-21-muplay-design.md`, which specified a Java-only,
Views-based implementation. That version is tagged `java-prototype`; its Plan 1
shipped a working Subsonic client and CI, and everything it *learned* is carried
forward here. Everything it *decided about language and UI* is discarded.

---

## 1. Why this exists

Four requirements, no existing player meets all of them:

- **Library-scoped shuffle.** Music and audiobooks live in separate Navidrome
  libraries. Hitting shuffle must not pull chapter 14 of a novel into a music
  session. Symfonium cannot restrict random playback to a library.
- **Real audiobook resume.** Every book remembers its own exact position and
  keeps it across an intervening music session.
- **Sonos**, plus generic DLNA, across three network situations.
- **Proper Android integration** — background playback, media notification,
  Android Auto, Wear OS.

### A correction worth keeping

The original premise included "Ultrasonic is not available on new phones". That
was a *distribution* failure — `targetSdk 33` against Play's target-API policy —
and it was **fixed in Ultrasonic 4.9.0 (2026-03-10)**. MuPlay is not justified by
that. It is justified by scoped shuffle, audiobook resume and Sonos.

---

## 2. Constraints

- **Kotlin 2.4.10**, JDK 21 toolchain. **Jetpack Compose** for all app UI.
- Licence **MIT**. No GPL code may be copied; all prior art is architecture-only.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`.
  > Play requires **API 36 for new apps and updates from 2026-08-31**.
- **Navidrome ≥ 0.62.0** (≥ 0.58.0 for multi-library).
- Subsonic client identifier **`c=MuPlay`**, protocol version `v=1.16.1`.
- Book positions are **local only**. No server sync.
- **A real Android emulator and a real Dockerised Navidrome are in the merge
  gate.** Not nightly, not optional. See §10.
- **Fakes, not mock frameworks.** This is Google's own testing guidance, and
  Now in Android ships zero mock frameworks. Coverage bought with mocks is worse
  than no coverage.

---

## 3. The core architectural decision

> **The queue is a list of pointers. Progress is a property of the item.**

The failure mode in other players is a single global "now playing position" that
the next thing played overwrites. AntennaPod proves the alternative: its queue
table holds *zero* playback state, while every episode carries its own position
row regardless of queue membership.

### Schema

```kotlin
@Entity(tableName = "media_progress")
data class MediaProgress(
  @PrimaryKey val mediaId: String,  // stable server id, never a rowid
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAt: Instant,
  val speed: Float,                 // per-item
  val skipSilence: Boolean,         // per-item
  val gainDb: Float,                // per-item
)
```

Music and audiobooks are **two pointer lists over one progress table**. Switching
from a book to music touches no progress row. Nothing about queue membership may
live in this table — a `queuePosition` column would invert the design.

### Structural enforcement

`MuPlayer` is a `ForwardingPlayer` overriding **all six** `setMediaItem(s)`
overloads to discard the caller's index and position and rehydrate from Room. No
code path can set a wrong position. (Idea from Voice; implementation written
fresh — Voice is GPL.)

Only books get resume treatment. Music restarts from 0 — progress is still
recorded, just not honoured on prepare.

### Persistence points

Write on all seven, plus a **5–10 s ticker** while playing:

1. `onPlayWhenReadyChanged` (covers user pause *and* audio-focus-loss pause)
2. `onIsPlayingChanged(false)`
3. `onPositionDiscontinuity` — **ignoring `DISCONTINUITY_REASON_SILENCE_SKIP` (6)**
4. `onMediaItemTransition`
5. `onPlaybackStateChanged` → `STATE_IDLE` / `STATE_ENDED`
6. the periodic ticker
7. `onDestroy`, with a deliberate blocking flush

UI collects the **live player position**, never the database at frame rate.

---

## 4. Server integration

### Library scoping — the headline feature

Navidrome **hardcodes `child.Type = "music"`** for every media file and always
sets `mediaType = song`. OpenSubsonic's `mediaType` enum is `song|album|artist` —
it describes the object kind, not the content. **A Navidrome server will never
tell a client that something is an audiobook**, and there is no server config for
it.

Library id is therefore the only mechanism, and it becomes a first-class app
concept: `getMusicFolders` once at setup, then the user tags each library Music
or Audiobooks. **Never infer the role from the library's name** — "Hörbücher" is
not "Audiobooks", and a wrong guess silently poisons shuffle scope.

`musicFolderId` is honoured on `getAlbumList2`, `getStarred2`,
**`getRandomSongs`**, `getSongsByGenre`, `search2`/`search3`.

> **Trap:** `getIndexes` and `getArtists` **discard the validation error** — an
> invalid `musicFolderId` silently returns **all** libraries. Never use those two
> to enforce a scope. Silent-wrong-answer is the worst failure class.

`getRandomSongs` caps `size` at 500. Ask for more and you silently get 500.

### Resume — local only

Book positions live in Room and are never sent to the server. This removes the
`createBookmark` write path, `savePlayQueue` sync, conflict resolution and a
background worker — and sidesteps a real spec hazard: `createBookmark.position`
is documented in **milliseconds** while `bookmarkPosition` on a `Child` is
documented in **seconds**. Getting that backwards puts every resume out by 1000×.

If sync is ever wanted, the local schema maps onto `createBookmark` plus
`savePlayQueueByIndex`/`getPlayQueueByIndex` — *not* `savePlayQueue`, whose
Navidrome implementation maps the current track id to an index by first match and
silently falls back to index 0, wrong for any queue with a repeated track.

### Auth

Navidrome 0.63.2 supports only `jwt=`, `p=` (plain or `enc:hex`), and `t`+`s`.
**`apiKeyAuthentication` is not implemented** despite third-party claims. Design
the auth layer so an API key drops in later, and call
`getOpenSubsonicExtensions` at connect time rather than assuming.

`t = md5(password + salt)`, lowercase hex, fresh salt per request. Credentials go
in **`EncryptedSharedPreferences`'s successor** — direct Android Keystore
(AES-GCM key, ciphertext in DataStore). The password is needed in cleartext at
request time to compute the token, so there is no hashed-at-rest option.

### Capability negotiation

Three-tier: `ping` → is `openSubsonic` present → `getOpenSubsonicExtensions`,
storing the **versions list, not a boolean** (`songLyrics` v1 and v2 differ).
Unsupported features are **silent no-ops, not errors**.

If the extensions call fails on a server that `ping` said was OpenSubsonic,
degrade to "OpenSubsonic, no known extensions" — **not** to "not OpenSubsonic".
Those are different facts and collapsing them loses information.

### Sync

`getIndexes?ifModifiedSince=` is the only delta primitive, Navidrome compares it
against one global watermark, and **deletions are never reported**. So MuPlay
uses **`getScanStatus`**, which Navidrome extends with a monotonic `lastScan`.
Poll, require seeing `scanning == true` first to avoid a race, invalidate on
true→false, and full-reconcile against `getAlbumList2` pages to detect deletions.

Advance the stored watermark **only after the reconcile transaction commits** —
otherwise a failed sync is never retried and the mirror stays permanently stale.

> **Trap:** Tempo's `getScanStatus()` calls `startScan()`, re-triggering a full
> server scan on every poll. `getScanStatus` is a **read**.

### Streaming

- Prefer **`format=raw`**. Raw and fully-cached transcodes get Range, 206 and an
  accurate `Content-Length`. **Live transcodes return `Accept-Ranges: none` with
  no `Content-Length`** — no seek.
- **Verified against a real container:** Navidrome's `format=raw` honours RFC 7233
  Range (206/416, clamping, byte-exact tail seek) and always sends
  `Content-Length`, never chunked.
- Transcoded seek uses `timeOffset` (the `transcodeOffset` extension), which means
  re-issuing the URI, not `AVTransport::Seek`.
- **Handle HTTP 429** — Navidrome 0.62.0 added `Transcoding.MaxConcurrent`.
  Unhandled, this looks like random playback failure.
- **Never Opus.** Sonos cannot decode it and Navidrome mislabels it `audio/ogg`.
- ReplayGain is exposed but **not applied server-side**; the client applies it.
- Gapless has **zero** server support. Use a real Media3 `setMediaItems` queue and
  let ExoPlayer read LAME/iTunSMPB. Never hand-roll.
- **Cache key must derive from the track id alone** via `setCustomCacheKey`. Tempo
  omits this, so its key includes the auth token and bitrate — changing bitrate
  orphans the entire cache.
- Cover art keys on the server's `coverArt` id, bumped when the album's `changed`
  timestamp moves.
- **`c=MuPlay`.** Navidrome's `LegacyClients` defaults to `DSub` and
  `MinimalClients` to `SubMusic`; matching clients get the OpenSubsonic field
  block stripped.

---

## 5. Audiobooks

### Chapters — a genuine differentiator

Navidrome never exposes chapters. Media3 1.11.0 (2026-08-05) added native chapter
extraction for Nero `chpl`, QuickTime `chap` and Matroska, so the client can read
what the server cannot see.

**Verified by spike S3, on a real emulator:**

- Media3 1.11 extracts **both** `chpl` and `chap` over **HTTP**.
- Works for `faststart` *and* non-faststart files. Non-faststart is cheap — the
  reader issues a targeted Range request to the tail rather than downloading the
  file. `-movflags +faststart` puts `moov` **before** `mdat`; without it `moov`
  trails at the end.
- **It only works when `MetadataRetriever.Builder` is given an explicit
  `MediaSourceFactory`.** The default path silently returns zero chapters. This
  is the single most expensive trap in the audiobook feature.
- Chapters live in the separate **`media3-inspector`** artifact —
  `media3-exoplayer` does not depend on it.

```kotlin
MetadataRetriever.Builder(context, mediaItem)
  .setMediaSourceFactory(factory)     // omit this and you get nothing
  .build()
  .retrieveTrackGroups()              // -> TrackGroupArray
  // TrackGroup.getFormat(i).metadata -> Metadata.Entry as Chapter
  // Chapter: startTimeMs, endTimeMs, isHidden, title -> Label(value, language)
```

Every `chpl` chapter observed had a populated end time, including the last one.
The mechanism is inferred to be "next chapter's start, or content duration" —
inferred from the data, not confirmed against Media3's source.

*Not yet verified:* Navidrome's `format=raw` path was confirmed to meet the HTTP
Range precondition, but `MetadataRetriever` extraction has not been re-run against
a Navidrome URL end-to-end. First audiobook plan closes this.

### Other audiobook behaviour

- **Audio focus:** a one-line switch — books use
  `AudioAttributes.CONTENT_TYPE_SPEECH`, music `CONTENT_TYPE_MUSIC`.
- **Smart rewind** on resume, scaled to how long the book was paused.
- Per-item speed, silence skipping and gain, all stored on the progress row.
- Sleep timer, with a shake-to-extend affordance.

---

## 6. Casting — Sonos and DLNA

Sonos is a **UPnP MediaRenderer**: SOAP on port 1400, DIDL-Lite mandatory, and it
infers MIME **from the URL, not `Content-Type`**.

### The routing rule

One rule covers all three situations:

> **Same subnet as the speaker → stream through the phone proxy.
> Otherwise → the speaker fetches Navidrome directly.**

| Situation | Phone | Speaker | Navidrome | Route |
|---|---|---|---|---|
| Home | LAN | LAN | LAN | proxy |
| Office | office LAN | office LAN | public HTTPS | proxy |
| Remote + VPN | VPN into home | home LAN | home | proxy over the tunnel |

Detection is a **subnet comparison**, not SSID sniffing — SSID needs
`ACCESS_FINE_LOCATION` and fails silently without it.

Under this rule no speaker ever fetches over public HTTPS, which designs the
Let's Encrypt trust question out of existence entirely.

Playback stopping when the phone leaves the network is **intended behaviour**.

### The proxy

An embedded HTTP server on the phone, serving byte ranges to the renderer.
**This is a "write it, don't depend on it" case** — a servlet container to serve
range requests to one speaker is a large dependency used for a fraction of its
surface. A minimal HTTP/1.1 range server is a few hundred lines we own.

- Must implement `Range` → 206/416 and `HEAD`.
- The URL's extension must match the real format, because Sonos sniffs the URL.
- Multicast never crosses a VPN tunnel; apps cannot escape a VPN without
  `allowBypass()`. SSDP discovery therefore needs a unicast fallback.

---

## 7. Platform integration

- **`MediaLibraryService`** + `MediaSession`, with a `MediaController` in the UI.
- **Android Auto needs no UI work.** The car renders itself from the browse tree
  and session state; no app Compose reaches the car screen. `isAutomotiveController`
  branching lets it be tested with no car.
- **Wear OS** uses Compose for Wear OS Material3, plus the specific Horologist
  media modules (`media3-backend`, audio UI) — not the umbrella. Horologist is
  actively maintained but perpetually 0.x.
- **Edge-to-edge is enforced** at API 35+; `Scaffold` handles insets.
- **Predictive back** is default-on and must be implemented.
- Notification, lock screen, Bluetooth and headset controls come from the session.

### Permissions

`INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

**Verified by spike S1, on a real API 37 emulator:** `ACCESS_LOCAL_NETWORK`
gating keys off the app's **`targetSdkVersion`**, not the device API level. At
`targetSdk 36` it is inert — a blocked connection manifests as a **silent connect
timeout**, not `EPERM`. The permission is `protectionLevel:dangerous`, so it needs
a runtime grant, and an instrumentation test needs `pm grant`.

*Not tested:* whether loopback via `adb reverse` is gated the same way as
`10.0.2.2`. Moot at `targetSdk 36`; it matters the day we move to 37.

---

## 8. Optional integrations

- **Bindery** — request audiobooks from inside the app.
- **Lidarr** — request music. `POST /api/v1/album` payload is unverified against
  a live instance.

Both are opt-in, both fail closed, and neither may block core playback.

---

## 9. Stack and structure

```
core/model         pure Kotlin, no Android
core/network       Subsonic client + capability negotiation
core/database      Room — progress, libraries, library mirror
core/media         Media3, MuPlayer, MediaLibraryService, cache
core/cast          UPnP/Sonos + the range-serving proxy + discovery
core/designsystem  theme, Compose components
core/testing       fakes, fixtures, the OpenAPI validator
feature/*          library, player, book, search, settings, cast picker
integrations/*     bindery, lidarr
app                wiring + E2E journeys
```

`build-logic/convention` holds Gradle convention plugins applied by id from the
version catalogue — no copy-pasted build scripts. This is Now in Android's
pattern and it is the thing that keeps ten modules consistent.

| Concern | Choice | Version |
|---|---|---|
| Language | **Kotlin** | 2.4.10 |
| Build | AGP, **KSP** (KSP1 is removed; KAPT is dead) | 9.x, 2.3.11 |
| UI | **Jetpack Compose**, Material 3 | BOM 2026.08.00, M3 1.4.0 |
| Navigation | **Navigation 3** (stable since 2025-11; NIA has migrated) | 1.1.6 |
| Player | Media3 + **`media3-ui-compose`** + `-compose-material3` | 1.11.0 |
| Chapters | **`media3-inspector`** (separate artifact) | 1.11.0 |
| HTTP | OkHttp + **`okhttp-coroutines`** (`Call.executeAsync`) | 5.5.0 |
| REST | Retrofit + kotlinx.serialization converter | 2.11.x |
| JSON | **kotlinx.serialization** (compiler plugin, no reflection) | 1.11.0 |
| Database | Room + KSP | 2.8.4 |
| Images | **Coil 3** | 3.5.0 |
| Async | Coroutines + Flow | 1.11.0 |
| DI | **Hilt via KSP** | 2.60.x |
| Time | `kotlinx-datetime` / injected `Clock` | — |
| Background | WorkManager | — |

`media3-ui-compose` matters more than its line in the table suggests: it supplies
`PlayPauseButtonState`, `CurrentMediaItemState`, `PlaylistState` and `ErrorState`
as real Compose state holders, and `-compose-material3` supplies `Player`,
`MiniController` and transport controls. Google's guidance is explicit —
build Compose-first, with no `AndroidView` interop.

### Kotlin discipline

- `data class` for models, **sealed interfaces for state and results** with
  exhaustive `when`.
- Immutable `UiState` exposed as `StateFlow`, collected with
  `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data; one per data type. Business
  logic lives there, never in a Composable or a Service.
- **No domain layer and no use-case classes** unless a specific piece of logic is
  genuinely shared across features — matching Google's "only when needed".
- Nullability is the type system's job. No annotation scheme, no NullAway.

### Deferred, but designed for

- **Offline downloads.** Media3's `DownloadService` hardcodes FGS type `dataSync`
  and calls `stopSelf()` on timeout, so bulk downloads hit Android 15's 6 h cap.
  The escape is user-initiated data-transfer jobs (API 34+), which WorkManager
  does not support — raw `JobScheduler` + `RUN_USER_INITIATED_JOBS`.
- **Server-side progress sync.** Explicitly not wanted.

---

## 10. Testing

The honest problem: the same agent writes the code and the tests, and left alone
it will assert what the code *does* rather than what it *should*.

### Countermeasures

1. **An external oracle.** The **OpenSubsonic OpenAPI spec** (87 paths, 195
   schemas) validates every committed fixture — vendored, with a nightly
   non-blocking drift check.
2. **A real server.** Anything whose subject is Navidrome's behaviour is tested
   against a **pinned Navidrome container**, not a fixture. Docker is not an
   emulator; the container starts in 5–11 s, so this belongs in the fast tier.
3. **Fakes, never mock frameworks.** Google's own guidance, and NIA ships none.
   A test satisfied by a mock returning what it was told returns no information.
4. **Golden files** for playback dumps and chapter assertions.
5. **Write the test first, from the spec** — for Subsonic, the spec exists.

### The test hierarchy

Reach for the strongest available rung. Coverage earned on rung 4 may be worth
nothing.

| | Rung | Here |
|---|---|---|
| 1 | **End-to-end** | Real emulator + real Navidrome container. The journeys below. |
| 2 | **Integration** | Real in-memory Room and real SQL; real HTTP over MockWebServer; real Media3 with `PlaybackOutput` dumps; an in-process real UPnP renderer. |
| 3 | **Unit, real inputs** | Token derivation, resume maths, DIDL escaping. |
| 4 | **Fakes** | Only where the real thing cannot run: an injected `Clock`, a severed socket, a forced 429. |

### The merge gate — two tiers, both required

Quality outranks gate speed. Both tiers must be green to merge.

**Tier 1 — fast, ≤ 10 minutes, no emulator**

| Job | Content |
|---|---|
| Static | Android Lint, convention-plugin checks (`ConventionTest`), release-manifest check |
| Unit + integration | mappers, token derivation, queue logic, resume maths |
| Live server | JVM tests against the **pinned Navidrome container** |
| Contract | the OpenAPI **oracle itself** — `OpenApiFixtureValidator` and its own suite, over inline JSON. It validates no committed fixture: the fixture `assertValid` calls live in `:core:network`'s tests and run in Unit + integration |
| Playback goldens | `PlaybackOutput` dumps; gapless byte-compare; chapter assertions on the M4B fixture |
| Session | browse tree and `onPlaybackResumption`; `isAutomotiveController` branching |
| Cast | in-process fake renderer on `127.0.0.1:0` — SOAPACTION quoting, DIDL escaping round-trip, `protocolInfo` vs served `Content-Type`, Range → 206/416/HEAD |

**Tier 2 — emulator end-to-end, required to merge**

Real API 37 emulator, hardware-accelerated, against a real Navidrome.

| Journey | Proves |
|---|---|
| First run | URL + credentials → `ping` → `getMusicFolders` → tag each library |
| Browse | artists, albums, tracks, cover art, search |
| **Library-scoped shuffle** | shuffle Music repeatedly, assert **no audiobook ever appears** |
| Playback | audio renders, notification and lock screen respond, survives backgrounding |
| **The resume journey** | play a book, leave mid-chapter, play music, return — the book resumes **exactly**. The original complaint, as a test. |
| Cast | discover and stream to a renderer |
| Auto / Wear | browse tree and controls from car and watch surfaces |

Tier 2 grows with each plan. **A plan is not done until its journeys are in it.**

### Tooling notes that are not obvious

- **Compose UI tests run on a real emulator** via `createAndroidComposeRule` —
  banning Robolectric costs nothing here.
- **Kover cannot collect instrumented coverage** — its own docs say so. Since
  device coverage is required, coverage is **JaCoCo**, merging `testDebugUnitTest`
  and `connectedDebugAndroidTest` execution data into one figure per module. This
  is also what NIA does. The gate is therefore **split across the two tiers along
  the line the data draws**: Tier 1 enforces every floor a JVM run can measure —
  all the branch floors — against JVM execution data alone, and Tier 2 enforces
  the whole table, including the line floors over `@Composable` code, which
  measure ~0% without a real composition. **Neither tier may skip its half
  quietly**, and that has to survive Gradle skipping the gate itself: the JaCoCo
  tasks carry an `onlyIf` ("any execution data exists") that short-circuits their
  actions, so each gate is *finalized by* a plain reporting task that always runs
  and says, per module, how many floors were evaluated, how many were left to the
  other tier, and — loudly — when the gate did not run at all. Both tiers run the
  per-class checks (ungated class, vacuous floor), because the one time this build
  had a floor that could not fail it was in the fast tier's own subset.
- **A pin asserted from a configured property is not a pin.** The JaCoCo version
  is declared as a real dependency, forced, *and* asserted from the **resolved
  artifact** — the analyzer on every JaCoCo task, the agent on every `Test` task.
  It had to be: `jacoco.toolVersion` read back as the pinned version at every
  probe point while a different jar did the analysis, because three separate
  things write that property in an Android module and the ordering that decides
  which one wins is *not understood*. The assertions hold precisely because they
  make no claim about that ordering. Every floor is measured against one JaCoCo
  version; changing it changes every number in the table.
- **No ktlint, detekt or spotless yet, and the spec used to say otherwise.** The Tier 1 "Static"
  row above promised *"ktlint/detekt"*; there is none in the repository — no plugin, no catalogue
  entry, no `.editorconfig`. What exists, and what the row now says, is Android Lint plus
  `ConventionTest` plus the release-manifest check. The codebase is uniformly formatted today by
  discipline alone, which is exactly what the convention-plugin layer exists because it does *not*
  survive ten modules and seven plans, so adding a formatter is worth doing — but the spec must
  not claim it before it is there.
- **Roborazzi is Robolectric-based**, so it is out. Google's own screenshot plugin
  is `0.0.1-alpha16` and Canary-only. Screenshots come from `captureToImage()`
  inside the emulator suite, with a small golden-diff helper — no new framework.
- Turbine for Flow assertions; `kotlinx-coroutines-test` for time control.
- **Coverage floor: 90%**, generated code excluded, enforced per module and
  failing the build. The *metric* differs by kind of code: **branch** for non-UI
  code, **line** for Compose UI. Branch coverage over `@Composable` code measures
  the Compose compiler, not this project — decompiling showed `MuPlayTheme`'s body
  carries 28 branches from zero author-written conditionals, and ~94 of
  `SetupScreenKt`'s 100 are `$changed`/`$dirty` bitmask tests and skip logic
  emitted *inside* the author's own method bodies, where no class-level exclusion
  can reach them. Every floor is measured from a real run, never invented, and
  **must be able to fail**: a floor of `0.00`, or one whose matched classes carry
  no counters of its own kind, passes at every minimum and gates nothing.

---

## 11. Non-goals

- Server-side progress sync.
- Offline downloads in v1 (designed for, deferred).
- Compose Multiplatform. Nothing indicates it matters for an Android-only app.
- Video.
- Chromecast. Sonos and DLNA are the requirement.

---

## 12. Known risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `MetadataRetriever` fails against a real Navidrome `format=raw` URL | Low | Range precondition already verified; first audiobook plan closes it end-to-end |
| Sonos rejects a served format | Medium | Never Opus; URL extension must match the real format |
| SSDP discovery fails over VPN | High | Multicast never crosses a tunnel — unicast fallback is required, not optional |
| Navidrome 429 under concurrent transcode | Medium | Explicit handling; prefer `format=raw` |
| `targetSdk` 37 makes `ACCESS_LOCAL_NETWORK` live | Certain, later | S1 documented the behaviour and the `pm grant` step |
| Emulator flakiness slows the gate | Medium | Hardware acceleration, headless, wait on `sys.boot_completed` — never a fixed sleep |
