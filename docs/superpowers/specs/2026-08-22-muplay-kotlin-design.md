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
- Licence **MIT**. No GPL code may be copied; all prior art is architecture-only. **This binds
  every plan and every task, whether or not a given plan restates it in its own constraints** —
  said here because one plan's constraints omit the clause, and a reader who checks the plan rather
  than the spec would find no mention of it in the very plan that vendored third-party material.
  Voice and AntennaPod are read for *architecture*; not a line of either is copied.
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
  val lastPlayedAtEpochMs: Long,    // epoch millis, from an injected java.time.Clock
  val speed: Float,                 // per-item
  val skipSilence: Boolean,         // per-item
  val gainDb: Float,                // per-item
)
```

> **Corrected against the implementation.** This block used to type the timestamp
> `lastPlayedAt: Instant`. The column is, and always was, epoch-millis `Long` — every plan chose
> `java.time.Clock` for a stated reason (native at `minSdk 26`, no desugaring, no extra
> dependency), `kotlinx-datetime` is not in `libs.versions.toml`, and §9's stack table has been
> corrected to match. A spec that types a column one way while the code types it another is a spec
> that will be believed by whoever writes the next migration.

Music and audiobooks are **two pointer lists over one progress table**. Switching
from a book to music touches no progress row. Nothing about queue membership may
live in this table — a `queuePosition` column would invert the design.

### Structural enforcement

`MuPlayer` is a `ForwardingPlayer` overriding **all six** `setMediaItem(s)`
overloads to discard the caller's **position** and rehydrate it from Room. No code
path can set a wrong position. (Idea from Voice; implementation written fresh —
Voice is GPL.)

> **The index is the caller's; only the position is the policy's.** An earlier
> wording here said the seam discards "the caller's index and position", and that
> is wrong in a way a user would notice immediately. The index is *queue
> membership* — "play track 3 of this album" is a legitimate request, and
> discarding it unconditionally breaks every tap-a-track-to-play path in the app.
> `ResumePolicy.resolve(mediaIds, requestedIndex)` is therefore never handed a
> position, and **must not override the index**: `"play this book"` and `"play
> chapter 1 from the top"` both arrive as `requestedIndex = 0`, so an override
> would make tapping chapter 1 jump to chapter 14. An audiobook resumes at chapter
> 14 by its own launcher *choosing* that index before `setMediaItems` is called.
> Plan 3 Task 10's `PlaybackJourneyTest` taps **Track 2**, not Track 1, precisely
> so a start index replaced by a constant is a failing gate rather than a silent
> one.

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

Navidrome honours `musicFolderId` on `getAlbumList2`, `getStarred2`, **`getRandomSongs`**,
`getSongsByGenre` and `search2`/`search3`. That is a statement about the *server*, and this
paragraph used to read like an instruction to this client. It is not one: **MuPlay calls
`getAlbumList2`, `getRandomSongs`, `getAlbum` and `search3`, and no others.**

`getStarred2` and `getSongsByGenre` are resolved here the same way `getIndexes` and `getArtists`
are below — by stating the decision rather than leaving the reader to infer one. **Favourites and
browse-by-genre are not v1 features**, on the phone or in the car, so neither endpoint has a
caller. They are a natural second-release addition and nothing in the client's design forecloses
them: the scoped-request rule below is what they would need, and it is already structural.

> **Trap, corrected against a live `deluan/navidrome:0.63.2` while writing Plan 2 —
> the earlier wording had this backwards, and it matters because scoping is the
> only mechanism the headline feature has.**
>
> What fails open is not a *command*, it is a *kind of value*, and it fails open on
> **every** scoped command including `getRandomSongs`:
>
> | `musicFolderId` | Behaviour, measured |
> |---|---|
> | a valid id | correctly scoped |
> | **non-numeric** (`abc`) | `status: ok`, **all libraries returned** |
> | **empty** | `status: ok`, **all libraries returned** |
> | unknown but numeric (`99`) | `status: failed`, error code **70**, *"Library 99 not found or not accessible"* |
>
> So a malformed value is silently ignored and widens the scope, while an unknown
> numeric one fails closed and loudly. The practical consequence: the parameter
> must be a non-null `Int` rendered with `toString()`, never a nullable or
> user-supplied string, because there is no runtime signal when it is wrong.
> Silent-wrong-answer is the worst failure class, and here it means chapter 14 of
> a novel starting after a song with nothing reported anywhere.
>
> `getIndexes` and `getArtists` remain unused for a stronger reason than this
> trap: Plan 2 derives artists from albums, so neither is called at all.

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

The first and, for now, only gate is `transcodeOffset` (§4, Streaming). "Silent
no-op" means *the app does not offer the feature* — not that it accepts the
request and discards it. Applied to a seek, the second reading is a silent wrong
answer: the user drags the bar, the bar moves, the audio does not, and nothing is
reported anywhere. (Plan 3 Task 12, which is also the first caller of
`ServerCapabilities.supports` anywhere in this project — Plan 1 built the
negotiation and until then nothing had ever asked it a question.)

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
  re-issuing the URI, not `AVTransport::Seek`. **Gate it on the extension**, and
  when the server does not advertise it, *withdraw the seek command* rather than
  accepting a seek that does nothing — `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` is
  removed from the player's command set, so the transport controls disable the bar.
  A silent no-op on a seek is a silent wrong answer.
  - And in the other direction, measured on a device in Plan 3 Task 12: an
    `ExoPlayer` playing a live transcode does **not** offer that command by itself,
    because a body with no `Content-Length` is an unseekable timeline window to
    `ProgressiveMediaSource`. So the seam has to *grant* the command as well as
    withdraw it, or the session refuses the seek before the re-issue is ever
    reached.
  - The re-issued item needs its **own media-cache key**. §4's "cache key derives
    from the track id alone" was a complete identifier only while one track meant
    one stream of bytes; an offset stream filed under the bare id is written into
    the middle of the full track's cache entry.
- **Handle HTTP 429** — Navidrome 0.62.0 added `Transcoding.MaxConcurrent`.
  Unhandled, this looks like random playback failure.
- **Never send `estimateContentLength`.** It makes a transcoded response carry a
  *guessed* `Content-Length`; ExoPlayer trusts that header for seeking and lands in
  the wrong place with nothing reported anywhere. Preferring `format=raw` gives a
  real one. (Plan 3 Task 1.)
- **Never Opus** — Sonos cannot decode it and Navidrome mislabels it `audio/ogg`.
  "Never" needs a mechanism, so here is the one: `StreamFormat` is a sealed
  interface with exactly `Raw` and `Mp3`, so `opus` is **unrepresentable** in the
  type that builds the URL, and `StreamFormat.forSuffix` transcodes both `opus` and
  `ogg` — the latter because a file suffix cannot distinguish Ogg-Vorbis from
  Ogg-Opus.
- ReplayGain is exposed but **not applied server-side**; the client applies it — as a
  gain stage in the audio processor chain, upstream of the `AudioTrack`, driven by the
  file's own `replayGain` tags carried on the library mirror so a shuffled queue has
  them before a track is first played. Track gain is preferred, album gain is the
  fallback for a file that carries no track gain, and a positive gain is clamped by the
  file's peak. **No loudness analysis** is performed here and none is planned: an
  untagged file is played unchanged, bit for bit. A newly-started item takes up to the
  sink's own buffer (**measured: 700 ms**) to reach its own gain, because the transition
  is reported off the playback position while the samples are processed ahead of it.
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

> **Matroska is out of scope for v1, and that is a decision rather than an oversight.** The test
> corpus is M4B only — `chpl` and `chap`, faststart and non-faststart — because that is what the
> audiobook world ships. Media3 would extract `.mka` chapters too, but nothing here would have
> exercised that path, and an untested branch presented as a supported format is the kind of claim
> this document exists to avoid making. If an `.mka` audiobook ever needs to work, the change is a
> fixture and an assertion, not a design.

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
  `AudioAttributes.CONTENT_TYPE_SPEECH`, music `CONTENT_TYPE_MUSIC`. The switch is
  one line; **the signal it switches on is the user's own `LibraryRole`
  assignment**, carried to the player on `MediaMetadata.mediaType`
  (`MEDIA_TYPE_AUDIO_BOOK_CHAPTER` vs `MEDIA_TYPE_MUSIC`). No server field can
  answer it: Navidrome hardcodes `child.Type = "music"` for every media file, which
  is why §4's library scoping is what decides this too.
- **Smart rewind** on resume, scaled to how long the book was paused.
- Per-item speed, silence skipping and gain, all stored on the progress row.
  Per-item gain is applied from the file's own ReplayGain tags (§4) and *recorded* on the
  progress row; the file is the authority, the row is the log.
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

`INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`.

`FOREGROUND_SERVICE` was missing from this list and is not optional: a foreground
service needs the plain permission from API 28 as well as the typed one that
matches its `foregroundServiceType` from API 34, and without either
`startForeground` throws. Measured, with only the typed one removed and the
manifest gate temporarily relaxed: nothing fails to build or install, Media3
swallows the platform refusal and logs `E Util: The service must be declared with a
foregroundServiceType that includes mediaPlayback`, no media notification is ever
posted, and `PlaybackJourneyTest.playbackSurvivesTheAppGoingToTheBackground` is the
gate that goes red. Both permissions and the service's `foregroundServiceType` are
held present in the **merged** manifest of *every* variant by
`:app:verifyDebugManifest`/`verifyReleaseManifest`; each entry carries its own
`android:name="…"` wrapper, because `android.permission.FOREGROUND_SERVICE` is a
prefix of `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` and a bare-name
list reports the shorter one present in a manifest declaring only the longer.

**Verified by spike S1, on a real API 37 emulator:** `ACCESS_LOCAL_NETWORK`
gating keys off the app's **`targetSdkVersion`**, not the device API level. At
`targetSdk 36` it is inert — a blocked connection manifests as a **silent connect
timeout**, not `EPERM`. The permission is `protectionLevel:dangerous`, so it needs
a runtime grant, and an instrumentation test needs `pm grant`.

*Not tested:* whether loopback via `adb reverse` is gated the same way as
`10.0.2.2`. Moot at `targetSdk 36`; it matters the day we move to 37.

---

## 8. Optional integrations

- **Bindery** — acquire audiobooks from inside the app. **Correction, established
  from the project's source rather than its README:** `vavallee/bindery` is a Readarr
  replacement — acquisition automation with **no request or approval concept** anywhere
  in its router or roadmap. Adding a book *is* acquiring it. Read literally, the earlier
  wording here ("request audiobooks") would have produced a request/approval state
  machine the server cannot satisfy. Note also that **three projects share the name**,
  and the two wrong ones are the easier to find: `evanbrooks/bindery` (browser book
  layout, archived 2023) and `jarynclouatre/bindery` (an e-book format converter, and
  the only Bindery listed in awesome-selfhosted). If a genuine request/approval workflow
  is ever wanted, `markbeep/audiobookrequest` is the service that has one.
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
feature/*          library (browse + search + shuffle), player, book, settings, cast picker
integrations/*     bindery, lidarr
app                wiring + E2E journeys
```

> **Two corrections to that list.** It named **`feature/search`** as its own module; search is
> three calls and one text field over the same mirror the browse screen already reads, so it was
> consolidated into `:feature:library` — a decision, not a gap. And it named **`feature/settings`**
> while no plan created it, which Plan 7 stated in as many words before routing around it with an
> overflow menu item; it is now owned by **Plan 2**, which owns library roles, because the thing a
> user most needs it for is re-tagging a library they tagged wrong. Until that landed, a single
> mis-tap on the first-run screen permanently poisoned library-scoped shuffle with no route back
> except clearing app data — which also destroys every audiobook position.

`build-logic/convention` holds Gradle convention plugins applied by id from the
version catalogue — no copy-pasted build scripts. This is Now in Android's
pattern and it is the thing that keeps ten modules consistent.

| Concern | Choice | Version |
|---|---|---|
| Language | **Kotlin** | 2.4.10 |
| Build | AGP, **KSP** (KSP1 is removed; KAPT is dead) | 9.x, 2.3.11 |
| UI | **Jetpack Compose**, Material 3 | BOM 2026.08.00, M3 1.4.0 |
| Navigation | **Navigation 3** (stable since 2025-11; NIA has migrated) | 1.1.6 |
| Player | Media3. **`media3-ui-compose` and `-compose-material3` are NOT adopted** — both exist at 1.11.0 and both catalogue aliases exist, but no module declares either; see below | 1.11.0 |
| Chapters | **`media3-inspector`** (separate artifact) | 1.11.0 |
| HTTP | OkHttp + **`okhttp-coroutines`** (`Call.executeAsync`) | 5.5.0 |
| REST | Retrofit + kotlinx.serialization converter | 2.11.x |
| JSON | **kotlinx.serialization** (compiler plugin, no reflection) | 1.11.0 |
| Database | Room + KSP | 2.8.4 |
| Images | **Coil 3** | 3.5.0 |
| Async | Coroutines + Flow | 1.11.0 |
| DI | **Hilt via KSP** | 2.60.x |
| Time | **injected `java.time.Clock`**. `kotlinx-datetime` was listed as an alternative here and is **not** adopted — it is absent from `libs.versions.toml`, and `java.time` is native at `minSdk 26` with no desugaring. §3's schema block has been corrected to match | — |
| Background | WorkManager — **named here but NOT adopted: absent from `libs.versions.toml`.** No plan currently requires it; add it to the catalogue in the plan that first needs it, and do not assume it is available. | — |

`media3-ui-compose` supplies `PlayPauseButtonState`, `CurrentMediaItemState`,
`PlaylistState` and `ErrorState` as Compose state holders, and `-compose-material3`
supplies `Player`, `MiniController` and transport controls.

> **Neither was adopted, and that is a decision with a reason** (Plan 3 Task 9).
> Those state holders read a `Player` directly. `:feature:player`'s source of truth
> is `PlaybackConnection`'s `StateFlow` — which is what makes the whole
> state-to-screen mapping a pure function the fast tier can gate — so they would be
> a *second*, competing subscription to the same controller. Nothing in either
> artifact saved code here. The table above asserted a dependency the build does not
> have; it now says so. Compose-first with no `AndroidView` interop still holds, and
> is met without them.

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
   non-blocking drift check, implemented as `.github/workflows/openapi-drift.yml`.

   **It must fail loudly on the drift path, not only on the quiet one.** The
   first version did the opposite: `diff` exits 1 when it finds a difference,
   so under `set -euo pipefail` the job died silently *whenever there was drift*
   and passed cleanly whenever there was none — a check that worked in exactly
   the case it was not needed. Any rewrite must be watched failing against a
   deliberately altered spec before it is believed.

   **The oracle is not always right, and where it disagrees with the server the
   server wins.** Two divergences measured against a live
   `deluan/navidrome:0.63.2` while writing Plan 2, both of which would otherwise
   read as "our fixture is wrong":

   - `AlbumID3.userRating` is declared `{"type":"integer","minimum":1,"maximum":5}`,
     but Navidrome returns **`userRating: 0`** for an unrated album — on *every*
     album-bearing response. Validated naively, the oracle rejects correct server
     output for the most common case there is.
   - `ScanStatus` models only `count` and `scanning`; the real `getScanStatus`
     also returns `elapsedTime`, `folderCount`, `lastScan` and `scanType`.

   A fixture recorded from the real server that fails the oracle is evidence
   about the *oracle*. Each such divergence is recorded at the code that works
   around it, never silenced by loosening validation wholesale — the oracle's
   value is that it fails, and a validator relaxed until everything passes has
   stopped being one.

   **And "recorded" means a committed assertion, not a comment.** Both
   divergences above are pinned by name in
   `core/network/src/test/kotlin/app/muplay/network/NavidromeSpecDeviationTest.kt`,
   which asserts the *rejection* — `hasMessageContaining("minimum value of 1")`
   for `userRating`, and one `hasMessageContaining` per extension field for
   `scanStatus` — and separately asserts that stripping only `userRating` makes
   every album-bearing capture validate, so "rejected" cannot hide a second,
   unnoticed deviation behind the first. Those assertions fail in **both**
   directions: if Navidrome stops sending `userRating: 0`, or a vendored-spec
   refresh models Navidrome's `scanStatus`, the build goes red and someone reads
   that file instead of finding out through a parsing bug. Of Plan 2's seven
   browse captures, two validate as recorded (`getRandomSongs`, and a
   past-the-end `getAlbumList2` whose payload is `{}`), four fail on
   `userRating`, and one fails on the four `scanStatus` extension fields.
2. **A real server.** Anything whose subject is Navidrome's behaviour is tested
   against a **pinned Navidrome container**, not a fixture. Docker is not an
   emulator; the container starts in 5–11 s, so this belongs in the fast tier.
3. **Fakes, never mock frameworks.** Google's own guidance, and NIA ships none.
   A test satisfied by a mock returning what it was told returns no information.
4. **A measurement, never a golden file.** This countermeasure used to read *"golden files for
   playback dumps and chapter assertions"*. Both halves have been replaced by something strictly
   stronger, and the first replacement is **required** by this section's own correction below:
   `PlaybackOutput`, `CapturingRenderersFactory` and `DumpFileAsserts` live in `media3-test-utils`,
   need an Android runtime, and reach the JVM only through the Robolectric variant §2 and §10 ban.
   The second is a judgement worth keeping: **a file recording what Media3 returned last time is
   not an oracle** — it agrees with the code by construction, including when the code is wrong,
   which is exactly the failure this list of countermeasures exists to prevent.

   What is actually done:

   - **Playback** is measured as real PCM. A `TeeAudioProcessor` upstream of the `AudioTrack`
     captures what a real decoder produced on a real emulator, and the assertions are arithmetic
     over those bytes — frame counts, the longest run of silence across a queue, amplitude ratios.
     It works on the `-no-audio` CI emulator because the capture is upstream of the sound card.
   - **Chapters** are checked against an **independent** oracle. `ci/probe-chapters.sh` runs
     `ffprobe` over the same file, so the assertion compares Media3's answer to a *different
     program's* answer rather than to a recording of Media3's previous one.
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
| Cast | in-process fake renderer on `127.0.0.1:0` — SOAPACTION quoting, DIDL escaping round-trip, `protocolInfo` vs served `Content-Type`, Range → 206/416/HEAD |

**Tier 2 — emulator end-to-end, required to merge**

Real API 37 emulator, hardware-accelerated, against a real Navidrome.

| Journey | Proves |
|---|---|
| First run — `FirstRunJourneyTest` | URL + credentials → `ping` → `getMusicFolders` → tag each library, and **read the persisted role back** |
| Browse — `BrowseJourneyTest`, `AlbumRouteJourneyTest` | artists, albums, tracks, cover art, search, refresh |
| **Library-scoped shuffle** — `ScopedShuffleJourneyTest` | shuffle Music repeatedly, assert **no audiobook ever appears** |
| Playback | audio renders — PCM captured by a `TeeAudioProcessor` upstream of the `AudioTrack`, so it works on the `-no-audio` CI emulator — gapless measured in frames, notification and lock screen respond, survives backgrounding |
| **The resume journey** | play a book, leave mid-chapter, play music, return — the book resumes **exactly**. The original complaint, as a test. |
| Cast | discover and stream to a renderer |
| Auto / Wear | browse tree and controls from car and watch surfaces |

Plan 3 added `PlaybackJourneyTest` (the position readout on the real screen advances
at wall-clock rate and the platform's own `AudioManager.isMusicActive()` agrees in
both directions, the media notification names the track that was tapped, the app's
own transport controls drive the real session over a queue frozen so the buttons are
the only thing that can move it, a system media button pauses and resumes, playback
survives the app going to the background, and a played shuffle never surfaces the
audiobook) and `MuPlaybackServiceTest` (the real service, a real `MediaController`,
and the notification the system is holding — in `:app` because `@AndroidEntryPoint`
needs an `@HiltAndroidApp` application), plus `:core:media`'s own instrumented suite —
`GaplessTest`, `MuPlayDataSourceFactoryTest`, `MediaCacheTest`, `MediaItemsTest`,
`QueueRepositoryTest`, `MuPlayerFactoryTest`, `NavidromeLoadErrorHandlingPolicyTest` —
and `:feature:player`'s `PlayerScreenTest`/`MiniPlayerTest`. Tier 1 gained
`:core:network`'s live `/rest/stream` assertions (Range → 206/416, accurate
`Content-Length` on `format=raw`, `Accept-Ranges: none` on a live transcode, and auth
carried on the URL) and the pure decisions `:core:media` deliberately keeps free of
Android types.

Plan 3 Task 12 added `TranscodeSeekJourneyTest` and `:core:media`'s
`TranscodeSeekPlaybackTest`, and with them the **first Opus file in the CI corpus** —
`Offset Track`, thirty seconds in three ten-second regions (silence, a quiet 440 Hz
tone, a loud 1760 Hz one). It is the only fixture that reaches Navidrome's transcoder
on the path a user takes, because `StreamFormat.forSuffix` forces `format=mp3` for
`opus` and for nothing else; its regions make "did the seek land where it was asked"
answerable from the decoder's own output; and its length is what stops a seek
assertion being satisfied by playback simply reaching the position on its own, which
is a defect this repository has already found three times on its five-second
fixtures.

Tier 2 grows with each plan. **A plan is not done until its journeys are in it.**

#### A correction worth keeping — two Tier 1 rows that could never have fired

This table previously placed **Playback goldens** and **Session** in Tier 1. Neither
could ever have run there. `PlaybackOutput`, `CapturingRenderersFactory` and
`DumpFileAsserts` live in `media3-test-utils`, need an Android runtime, and reach the
JVM only through `media3-test-utils-robolectric` — which §2 and §10 of this same
document ban. A `MediaSession` likewise needs an Android runtime. Both rows named a
gate that would have reported success by never running, which is the exact defect
class §10's countermeasures exist to prevent, sitting inside the countermeasures
section itself.

Playback verification moves to Tier 2 by the technique named in the journey table
above. The Session row is deleted outright rather than moved: all three of its
subjects — the browse tree, `onPlaybackResumption` and `isAutomotiveController`
branching — belong to the Auto/Wear and resume plans, so it was asserting a gate over
code no plan had yet written.

**Both edits confirmed still applied at the end of Plan 3** (Task 10 was asked to
check, because a correction that gets reverted by a later merge is worse than one
never made). Deleting the Session row rather than moving it was the stronger call and
it stands: two of its three subjects still do not exist, and a Tier 2 row naming them
would be a second gate that has never fired. The plan that writes them owns adding it
back.

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
  survive ten modules and seven plans. This bullet used to end *"so adding a formatter is worth
  doing"*, which read as a scheduled intention; nobody schedules it and it is **deferred past v1**,
  deliberately. Adopting one is a repository-wide reformat, and with seven plans in flight over the
  same files that is a merge conflict in every one of them for a benefit — consistent formatting —
  that the project currently has anyway. The right moment is after the last plan lands, and it is a
  one-commit change then. Until it happens the spec must not claim it, and the Tier 1 "Static" row
  above says what actually runs.
- **Roborazzi is Robolectric-based**, so it is out. Google's own screenshot plugin is
  `0.0.1-alpha16` and Canary-only. This bullet used to go on to say that *"screenshots come from
  `captureToImage()` inside the emulator suite, with a small golden-diff helper"*. **No such helper
  exists, no plan schedules one, and nothing is screenshot-tested** — so that sentence described a
  safety net that was not there, which is worse than an admitted gap. **Visual regression testing
  is a stated non-goal for v1** (§11), for the same reason countermeasure 4 above rejects golden
  files: a stored image records what the renderer did last time, agrees with the layout by
  construction including when the layout is wrong, and this project has no design reference to diff
  against. What Tier 2 does assert is that named strings are *displayed* — which catches a view
  that vanished, and does not catch one that moved.
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

Everything here is **stated** rather than merely absent. An unstated omission is a bug nobody knows
about; a stated one is a decision somebody can argue with.

- Server-side progress sync.
- Offline downloads in v1 (designed for, deferred).
- Compose Multiplatform. Nothing indicates it matters for an Android-only app.
- Video.
- Chromecast. Sonos and DLNA are the requirement.
- **Favourites and browse-by-genre** — so `getStarred2` and `getSongsByGenre` have no caller (§4).
  A natural second release; nothing in the client forecloses it.
- **Matroska (`.mka`) audiobooks** — the corpus is M4B, and Media3's Matroska chapter path would go
  untested (§5).
- **Visual regression testing.** No screenshot suite and no golden-diff helper: a stored image
  agrees with the layout by construction, including when the layout is wrong, and there is no design
  reference to diff against (§10).
- **A code formatter** (ktlint / detekt / spotless) before the last plan lands. Deferred, not
  rejected — the reason is merge cost across seven in-flight plans, and it is a one-commit change
  afterwards (§10).
- **Album-gain ReplayGain mode, and loudness analysis of any kind.** The client applies the gain a
  file's own tags carry (§4), preferring track gain and falling back to album gain only for a file
  that carries no track gain; there is no album-versus-track choice for the user to make, because
  the queue this levels is a library-scoped shuffle, which has no album to be consistent within.
  Nothing here *measures* loudness, and an untagged file plays unchanged.

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
