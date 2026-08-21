# MuPlay — Design

An open-source Android music **and** audiobook player for Navidrome/Subsonic,
with Sonos and DLNA casting.

Date: 2026-08-21 · Status: approved for planning · Licence: MIT

---

## 1. Why this exists

Three things are wanted together and no single app provides them:

1. **Shuffle restricted to one library.** Symfonium cannot scope random
   playback to a Navidrome library.
2. **Sonos.** No open-source Subsonic Android client supports Sonos at all.
3. **Audiobooks that keep their place** — per book, exactly, surviving music
   played in between.

Research established that this gap is real and structural:

- **No OSS Android player does music and audiobooks well together.** Voice,
  AntennaPod, Lissen, Escapepod each pick one lane.
- **No OSS Android Subsonic client implements chapters.** Zero hits across
  every repository checked.
- **No OSS Android Subsonic client supports Sonos.** Symfonium does, via
  UPnP, and it is closed and paid.

### A correction to the original premise

Ultrasonic's unavailability on new phones was a **distribution** failure, not
a runtime one. It shipped `targetSdk 33`; Google Play's target-API policy then
made it invisible to new users on newer devices. **It was fixed in 4.9.0
(2026-03-10)** — now `targetSdk 37`, Media3 1.10.1, actively developed at
`gitlab.com/ultrasonic/ultrasonic` (the GitHub repo is an archived stub).

MuPlay is therefore **not** justified by "Ultrasonic doesn't run on new
phones". It is justified by Sonos, audiobooks, and library-scoped shuffle —
none of which Ultrasonic has (it has no sleep timer and no playback speed
anywhere in its 800-file tree).

---

## 2. Constraints

| | |
|---|---|
| Server | Navidrome, **≥ 0.62.0** required (share security fix), ≥ 0.58.0 for multi-library |
| Libraries | Music and audiobooks are **separate Navidrome libraries** |
| Server reachability | Public HTTPS with a **Let's Encrypt** certificate |
| Networks | Home (phone+speaker+server), office (phone+speaker, server remote), remote (phone via VPN into home) |
| Licence | **MIT** — no GPL code may be copied |
| **Language** | **Java. No Kotlin anywhere**, including tests and build logic |
| Platform | Android Auto, Wear OS, background playback, lock screen — full parity with mainstream players |
| Testing | Everything end-to-end tested; the suite is the primary correctness gate |
| Authorship | Claude writes most of the code; the plan must be agent-executable in reviewable slices |

### Licence + language consequences

**MIT and Java together mean there is no liftable reference implementation.**

- The permissively-licensed Subsonic and audiobook clients — Lissen (MIT),
  Youamp (MIT), Escapepod (MIT), Chora (Apache-2.0) — are **all Kotlin**.
- The Java clients — DSub2000, Tempus, Tempo, Ultrasonic — are **all GPL-3.0**.

So every line is written fresh. All prior art is **architecture-only**: schema
shapes, which Media3 call to make, the rewind-at-pause inversion, SoCo's endpoint
tables. Facts and interfaces are unprotectable; implementations are not.

- **Still liftable:** Media3 (Apache-2.0, and itself written in Java),
  `PRO-2684/dlna-dmr` (MIT) as a test fixture, the OpenSubsonic OpenAPI spec.
- **Excluded:** Jellyfin's Media3 FFmpeg decoder (GPL-3.0). Exotic codecs are
  handled by **Navidrome server-side transcoding** instead. No GPL anywhere in
  the tree.
- jUPnP is CDDL-1.0 (file-level copyleft) — may be *depended on*, not copied.

The upside of Java: **Media3 is written in Java**, including `media3-test-utils`,
`PlaybackOutput`, `DumpFileAsserts` and `TestExoPlayerBuilder`. The most
demanding part of this app is more natural in Java than in Kotlin.

---

## 3. The core architectural decision

> **The queue is a list of pointers. Progress is a property of the item.**

The failure mode in other players is a single global "now playing position"
that the next thing played overwrites. AntennaPod proves the alternative: its
queue table is literally `(id, feeditem, feed)` holding *zero* playback state,
while every episode carries its own position row regardless of queue membership.

### Schema

```
media_progress(
  mediaId       TEXT PRIMARY KEY,   -- stable server id, never a rowid
  positionMs    INTEGER,
  isFinished    INTEGER,
  lastPlayedAt  INTEGER,
  speed         REAL,               -- per-item
  skipSilence   INTEGER,            -- per-item
  gainDb        REAL                -- per-item
)
```

Music and audiobooks are **two pointer lists over one progress table**.
Switching from a book to music touches no progress row.

### Structural enforcement

`MuPlayer` is a `ForwardingPlayer` that overrides **all six** `setMediaItem(s)`
overloads to discard the caller's index and position and rehydrate from Room.
No code path can set a wrong position. (Idea taken from Voice; implementation
written fresh.)

Only books get resume treatment. Music restarts from 0 — progress is still
recorded, just not honoured on prepare.

### Persistence points

Write on all seven, plus a **5–10 s ticker** while playing:

1. `onPlayWhenReadyChanged` (covers user pause *and* audio-focus-loss pause)
2. `onIsPlayingChanged(false)`
3. `onPositionDiscontinuity` — **ignoring `DISCONTINUITY_REASON_SILENCE_SKIP` (6)**
4. `onMediaItemTransition`
5. `onPlaybackStateChanged` → `STATE_IDLE` / `STATE_ENDED`
6. periodic ticker (Voice's 400 ms is too aggressive; its maintainer is moving to 5 min)
7. `onDestroy` with a deliberate blocking flush

UI reads the **live player position**, never the database at frame rate.

---

## 4. Server integration

### Library scoping — the headline feature

Navidrome **hardcodes `child.Type = "music"`** for every media file and always
sets `mediaType = song`. OpenSubsonic's `mediaType` enum is `song|album|artist`
— it describes the object kind, not the content. **A Navidrome server will
never tell a client that something is an audiobook.** There is no config for it.

Library ID is therefore the only mechanism, and it becomes a first-class app
concept: `getMusicFolders` once at setup, then the user tags each library as
Music or Audiobooks.

`musicFolderId` is honoured on `getAlbumList2`, `getStarred2`,
**`getRandomSongs`**, `getSongsByGenre`, `search2`/`search3`.

> **Trap:** `getIndexes` and `getArtists` discard the validation error — an
> invalid `musicFolderId` silently returns **all** libraries. Never rely on
> those two to enforce a scope.

`getRandomSongs` caps `size` at 500.

### Resume — local only

**Book positions live in the app's Room database and are never sent to the
server.** Single-device use; no sharing required.

This removes real complexity and risk from v1:

- No `createBookmark` write path, no `savePlayQueue` sync, no conflict
  resolution, no background sync worker.
- It sidesteps a genuine spec hazard: `createBookmark.position` is documented in
  **milliseconds** while `bookmarkPosition` on a `Child` is documented in
  **seconds**. Getting that backwards puts every resume out by 1000×. Storing
  locally makes the question moot.
- Position is stored once, in one unit (ms), by code we control.

**If server sync is ever wanted**, the primitives are known and the local schema
maps onto them cleanly: `createBookmark` per track, and
`savePlayQueueByIndex`/`getPlayQueueByIndex` — *not* `savePlayQueue`, whose
Navidrome implementation maps the current track ID to an index by first match and
silently falls back to index 0, which is wrong for any queue with a repeated
track. Prefer furthest-position-wins over last-write-wins if it is ever added.

### Auth

Navidrome 0.63.2 supports only `jwt=`, `p=` (plain or `enc:hex`), and `t`+`s`.
**`apiKeyAuthentication` is not implemented** despite third-party claims; two
PRs are open. Design the auth layer so an API key drops in later, and call
`getOpenSubsonicExtensions` at connect time rather than assuming.

Credentials are stored via **direct Android Keystore** (AES-GCM key, ciphertext
in DataStore). `EncryptedSharedPreferences` is deprecated wholesale, and the
password is needed in cleartext anyway to compute the per-request salt+token.

### Capability negotiation

Three-tier, after Feishin: `ping` → is `openSubsonic` present →
`getOpenSubsonicExtensions`, storing the **versions array, not a boolean**
(`songLyrics` v1 and v2 differ). Unsupported features are **silent no-ops, not
errors** (after Supersonic).

### Sync

Nobody in this ecosystem syncs properly — Ultrasonic and Tempo both *declare*
`ifModifiedSince` and pass `null` at every call site, and neither has a single
SQLite index. `getIndexes?ifModifiedSince=` is the only delta primitive and
Navidrome compares it against one global watermark, so you learn *that*
something changed, never *what*, and only for artists. Deletions are never
reported.

**MuPlay uses `getScanStatus`**, which Navidrome extends with a monotonic
`lastScan`. Poll every 2 s, require seeing `scanning == true` first to avoid a
race, give up after 30 s, invalidate on true→false. Full reconcile against
`getAlbumList2` pages detects deletions.

> **Trap:** Tempo's `getScanStatus()` calls `startScan()`, re-triggering a full
> server scan on every poll. Do not reproduce.

### Streaming

- Prefer **`format=raw`**. Raw and fully-cached transcodes get
  `http.ServeContent` → Range, 206, accurate `Content-Length`. **Live
  transcodes return `Accept-Ranges: none` with no `Content-Length`** — no seek.
- Transcoded seek uses `timeOffset` (the `transcodeOffset` extension), not byte
  ranges — which means re-issuing the URI, not `AVTransport::Seek`.
- **Handle HTTP 429.** Navidrome 0.62.0 added `Transcoding.MaxConcurrent` /
  `MaxConcurrentPerUser`. Unhandled, this looks like random playback failure.
- **ReplayGain is exposed but not applied server-side.** The client applies it.
- **Gapless has zero server/protocol support.** Use a real Media3
  `setMediaItems` queue and let ExoPlayer read LAME/iTunSMPB. Never hand-roll.
- **Cache key must derive from the track ID alone** via `setCustomCacheKey`.
  Tempo omits this, so its cache key includes the auth token and bitrate —
  changing bitrate orphans the entire cache.
- Cover art keys on the server's `coverArt` id, bumped when the album's
  `changed` timestamp moves. (Ultrasonic keys on a path hash, so server-side art
  changes are invisible.)
- **Client identifier is `c=MuPlay`.** Navidrome's `Subsonic.LegacyClients`
  defaults to `DSub` and `MinimalClients` to `SubMusic`; matching clients get
  the entire OpenSubsonic field block stripped.

---

## 5. Audiobooks

### Chapters — a genuine differentiator

**Media3 1.11.0 (2026-08-05) added native chapter extraction**: MP4 Nero `chpl`,
QuickTime `chap` text tracks, and Matroska, exposed as
`androidx.media3.extractor.metadata.Chapter`. The issue it closed was opened by
the audiobook community.

Navidrome indexes an `.m4b` as **one multi-hour track** and cannot see chapters.
MuPlay extracts them **client-side from the stream**. Nothing in the Subsonic
ecosystem does this.

Requirements and caveats:
- Needs HTTP Range → **`format=raw`**, no transcoding.
- `faststart` files put the chapter atom at the **end** — a range request to the
  file tail before playback.
- Chapter times are **period-relative, not window-relative**: subtract
  `timeline.getWindow(idx, w).positionInFirstPeriodUs / 1000` before `seekTo`.
- `chpl` v0 vs v1 differ in the count field width; Media3 assumes v1.
- Read without playing via `androidx.media3.inspector.MetadataRetriever` (the
  old `exoplayer.MetadataRetriever` was deprecated in 1.9 and removed).
- Cache extracted chapters by `(id, size, lastModified)`.

Multi-file books (one track = one chapter) remain the default convention and
are fully supported: one album = one book, album artist = author.

### Audio focus — the one-line switch

`AudioFocusManager.willPauseWhenDucked()` returns true **only** for
`C.AUDIO_CONTENT_TYPE_SPEECH`, converting `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`
from a duck into a pause.

- Music → `CONTENT_TYPE_MUSIC` (ducks to 0.2×)
- Audiobooks → `CONTENT_TYPE_SPEECH` (pauses)

Rebuilt at runtime on content-type switch, and exposed as a user setting (after
Lissen).

### Smart rewind

BookPlayer's formula (reimplemented — BookPlayer is GPL), applied **at pause
time** so the rewound position is what gets persisted:

```
delta  = min(timePassed, 3600s) / 3600s
ease(d) = 1 - (1-d)^4
rewind = max(ease(delta) * maxInterval, 2s)
final  = min(rewind, timeInChapter, timePassed)
```

The two clamps are the non-obvious part: never cross a chapter boundary
backwards, never rewind 30 s because you paused for 3.

### Other audiobook features

- **Speed** via `setPlaybackSpeed()` — Sonic preserves pitch by default.
  Three-level cascade: temporary (session) → per-book → global.
- **Silence skipping**: `setSkipSilenceEnabled(true)`.
- **Volume boost**: `LoudnessEnhancer` bound to `audioSessionId`, re-attached on
  `onAudioSessionIdChanged`, **wrapped in try/catch** (it throws in practice).
  `setVolume()` is clamped to 1.0 and cannot boost.
- **Sleep timer**: fade out over the last 10 s with an eased curve, and **rewind
  by exactly the fade duration** so no audio is lost. Count *listening* time,
  not wall clock. Auto-bookmark on timer *enable*, not on fire. Shake-to-reset
  within 30 s. End-of-chapter mode.
- **Seek bar**: whole-book with chapter ticks, tap to toggle chapter-relative.
  Show time left in chapter *and* in book.
- **Bluetooth buttons are content-conditional**: for books, `seekToNext/Previous`
  scrub ±20 s instead of skipping chapters. Correct for books, wrong for music.

---

## 6. Casting — Sonos and DLNA

Sonos speakers **are** UPnP MediaRenderers, so one generic UPnP AV
implementation serves both, with Sonos-specific capabilities layered on top
(Queue service, `ZoneGroupTopology`, `x-rincon:` grouping, group volume).

Google Cast is a dead end — Sonos has never supported it and, given ongoing
Sonos–Google litigation, never will. AirPlay 2 is blocked on Android: PTP
requires binding UDP ports 319/320, which an unrooted app cannot do.

### The routing rule

> **Are the phone and the speaker on the same local network?**
> **Yes → phone proxy. No → speaker fetches Navidrome directly.**

| Scenario | Path | Rationale |
|---|---|---|
| Home | Proxy | Same subnet; credentials stay on the phone |
| Office (server remote) | **Proxy — required** | Only the phone can bridge to a remote Navidrome |
| Remote via VPN | **Direct — required** | Proxy cannot route back to a VPN address (both WireGuard and Tailscale NAT by default); speaker and server are co-located |

Detection is a subnet comparison against the speaker's IP — no SSID sniffing,
no permissions. (SSID access requires `ACCESS_FINE_LOCATION` plus the system
Location toggle and **fails silently** to `"<unknown ssid>"`.)

**Consequence:** in no scenario does a speaker fetch over public HTTPS, so the
Let's Encrypt / ISRG Root X1 concern is designed out rather than mitigated.

**Playback stopping when the phone leaves the LAN is intended behaviour**, and
falls out of the proxy path rather than being engineered.

### Why the proxy earns its place

It solves four problems at once:
1. **TLS** — the phone fetches over HTTPS (Android trusts LE); the speaker gets plain HTTP.
2. **MIME** — we serve `/t/<id>.mp3` with a real extension. Sonos infers MIME
   **from the URL, not `Content-Type`**, and Navidrome's `/rest/stream?id=…` is
   extensionless — the exact shape that produces `UPnP Error 714`. Sonos rejects
   the SOAP call *before ever fetching the URL*.
3. **Seek** — we control `Content-Length`, `Accept-Ranges` and 206 ourselves, so
   seeking works even for transcoded audio.
4. **Credentials** never leave the phone.

It also avoids the ~300 s stream-drop seen on Sonos: that affects *duration-less*
streams, and a finite `Content-Length` makes each track a normal file.

Served from the **same Media3 cache** as local playback, so casting an album you
just played doesn't re-fetch it.

Implemented with **embedded Jetty** (Java, Apache-2.0/EPL, actively maintained —
and the same choice DSub2000 made for its cast proxy). `com.sun.net.httpserver`
does not exist on Android, NanoHTTPD has been frozen since 2016, and Ktor's
server is Kotlin-only and unsupported on Android regardless.

Range handling is written and tested by us rather than inherited, because Sonos's
contract is strict and specific: 206 with `Content-Range` for valid ranges, 416
for invalid, an accurate `Content-Length` always, and a correct response to
`HEAD` — *"or listeners won't be able to seek"*. Never chunked for a seekable
track.

### Three independent addresses

Conflating these is the most common bug in this space:

1. **Control** — how we reach the speaker (`http://ip:1400`)
2. **Callback** — what we advertise for GENA `NOTIFY`
3. **Media** — the URL handed to the speaker; **it fetches this itself**, so it
   must resolve *from the speaker*

Derived per target with a source-IP probe (`DatagramSocket.connect()`, no packets
sent) — the same technique SoCo and Music Assistant use, needing no permission.
It also detects a VPN capturing LAN traffic.

### Server configuration: two URLs

Navidrome is configured with an **external** URL (used by the phone) and a
**LAN** URL (handed to speakers) — Supersonic's alternate-hostname pattern.
Resolved by a **happy-eyeballs race**: both pinged concurrently, primary given a
333 ms head start, first success wins, 10 s ceiling.

### Discovery

**Multicast never crosses a VPN tunnel** — Tailscale drops it in its own filter,
both directions; WireGuard is L3-only. Discovery while remote is impossible.

So **remembered speakers are a persisted data model, not a cache**: discover at
home, persist by UUID + IP + name + model + capabilities, reach by unicast when
away. Four discovery paths, because office networks commonly filter multicast:

1. SSDP M-SEARCH multicast
2. Unicast M-SEARCH to a known IP
3. TCP probe of `:1400` across the subnet (SoCo's approach)
4. Manual IP entry

Plus the household trick: **one reachable speaker IP yields the entire household**
via `ZoneGroupTopology.GetZoneGroupState()` — no multicast needed.

`MulticastLock` is always acquired (`CHANGE_WIFI_MULTICAST_STATE`, install-granted).
It is a no-op on the emulator but **required on real hardware** — code works in
tests and silently drops packets on device without it. Note that from API 36 the
lock stops taking effect once the process becomes `IMPORTANCE_CACHED`.

### Control

Transport commands go to the **group coordinator**. Join by sending
`SetAVTransportURI(CurrentURI="x-rincon:RINCON_<coordUUID>")` **to the joining
speaker**; play the queue with `x-rincon-queue:RINCON_<coordUUID>#0`.
Group volume is coordinator-only (error 701 otherwise).

Gapless requires the **queue** — `AddURIToQueue`/`AddMultipleURIs`. Per-track
`SetAVTransportURI` can never be gapless. Sonos learns the next track ~20 s ahead.

Service classes are generated from `svrooij/sonos-api-docs` rather than
hand-written. There is no usable Kotlin or Java Sonos library — the Java ones are
archived or unlicensed, and jUPnP needs an EOL Jetty on Android.

### DIDL-Lite is mandatory

Element order matters; `protocolInfo` is required and camelCase; the
Sonos-specific `<desc id="cdudn">RINCON_AssociatedZPUDN</desc>` is effectively
mandatory for locally-served content. The DIDL is a **string-escaped XML blob**
inside `<CurrentURIMetaData>` — escape once, unescape once.

### Events

GENA subscription **plus an always-on polling fallback** — subscription failure is
normal, and callbacks cannot reach a remote phone. One shared `ZoneGroupTopology`
subscription per household (after Home Assistant), respond 200 within ~1 s and
defer processing, renew at ~85 % of the granted timeout (Sonos grants 86400 s
regardless of what is requested). Poll every 10 s, 1 s during transitions.

### Format constraints

Every Sonos ever made: **48 kHz max, stereo max**, lossy ≤320 kbps, FLAC ≤24-bit
(16-bit on S1). Supported: AAC, FLAC, MP3, Ogg Vorbis, WMA.

> **The Opus trap.** Navidrome's default downsampling format is **Opus. Sonos
> cannot decode Opus.** Worse, Navidrome serves `.opus` as `Content-Type:
> audio/ogg`, which *looks* like supported Ogg Vorbis. Force `f=raw` or `f=mp3`;
> never allow a downsample path to reach Opus.

### Risk and mitigation

Sonos ships a **Connection Security** panel (app v80.24.32, July 2025) with a
**UPnP toggle** — default on, but Sonos calls UPnP *"the unsupported UPnP
protocol"*. No removal date announced.

Mitigation: the app detects it being off (403 / no response) and tells the user
where to re-enable it. A second backend exists if needed — every S2 speaker runs
an undocumented **local websocket API on port 1443** that Home Assistant and
Music Assistant use in production, supporting `loadCloudQueue` against a
phone-hosted queue server. Not in v1; the design keeps room for it.

SMAPI (appearing inside the Sonos app) is out of scope — it requires a publicly
reachable HTTPS:443 server with a CA-signed cert and a Sonos developer-portal
registration. `bonob` is documented as the option for users who want it, with the
caveat that S2 requires internet exposure.

---

## 7. Platform integration

`MediaLibraryService` (required for Auto), living in **`:core:media`, not `:app`**,
with a DI subgraph scoped to service lifetime.

- `foregroundServiceType="mediaPlayback|connectedDevice"`.
  `mediaPlayback` is exempt from the Android 15 six-hour FGS timeout;
  `connectedDevice`'s prerequisite is satisfied by `CHANGE_WIFI_MULTICAST_STATE`.
  **Avoid `dataSync`** (6 h/24 h cap and `onTimeout()`).
- **`onConnect` must be overridden.** As of Media3 1.11 the default grants only
  **read access to untrusted controllers** — and Auto, Wear and Assistant are
  untrusted. Missing this makes browsing work while playback controls silently
  don't.
- `onPlaybackResumption(session, controller, isForPlayback)` — the 3-arg form.
  When `isForPlayback == false` the system only wants metadata for the boot
  resumption notification: serve from cache, start no sync, touch no state.
  Required if `MediaButtonReceiver` is registered. Android 15 forbids
  `BOOT_COMPLETED` receivers from starting a `mediaPlayback` FGS, which is why
  this exists.
- **Android Auto**: two browse subtrees under one root; book items are
  `browsable=false, isPlayable=true` so a tap plays rather than descends. Set
  `EXTRAS_KEY_COMPLETION_STATUS` + `EXTRAS_KEY_COMPLETION_PERCENTAGE` for resume
  progress bars — **neither Voice nor AntennaPod does this**. Honour
  `LibraryParams.isRecent`; special-case `com.google.android.projection.gearhead`
  to eagerly prepare when idle.
- **Wear OS**: media controls and browsing via the same session.
- One `Player`, one `MediaSession`. Two sessions means two entries in the system
  media carousel and unpredictable media-button routing.

### Permissions

```
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE,
CHANGE_WIFI_MULTICAST_STATE, POST_NOTIFICATIONS,
FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
FOREGROUND_SERVICE_CONNECTED_DEVICE,
ACCESS_LOCAL_NETWORK   (targetSdk 37+)
```

No `ACCESS_FINE_LOCATION`, no `NEARBY_WIFI_DEVICES`, no `WAKE_LOCK`.

**Android 17 / API 37** makes `ACCESS_LOCAL_NETWORK` mandatory, gating outgoing
TCP to `:1400`, **inbound TCP to our proxy**, and all UDP. Failures are TCP
timeouts and `EPERM`. Built behind `SDK_INT >= 37` from the start, with the
`NsdManager` `FLAG_SHOW_PICKER` path as a no-permission fallback for discovery
(useless when remote, since it is mDNS-based).

**Google Play requires `targetSdk 36` from 2026-08-31.** Target 36, compile 37.

---

## 8. Optional integrations

Both are **additive and invisible when unconfigured**. Neither may become a
dependency of playback.

### Bindery (audiobooks)

`vavallee/bindery` — MIT, Go, the Readarr successor. Auth `X-Api-Key`; API-key
clients are **exempt from its CSRF requirement**, so a mobile client is a
supported shape.

- `GET /api/v1/search/book?q=` · `GET /api/v1/search/author?q=`
- `POST /api/v1/author {foreignAuthorId, monitored, searchOnAdd}`
- `GET /api/v1/book?status=wanted`
- **`?format=audiobook`** scopes to the audiobook edition — each title holds
  ebook and audiobook in independent slots, so "request the audiobook" is a
  first-class operation.
- `GET /api/v1/queue` for status.

### Lidarr (music)

- `GET /api/v1/album/lookup?term=` · `POST /api/v1/album`
- `foreignArtistId`/`foreignAlbumId` are **MusicBrainz IDs**, and Navidrome
  tracks MBIDs — so "more from this artist" is an exact ID match, not fuzzy
  search.

> `addOptions.searchForNewAlbum` and the `X-Api-Key` header name follow standard
> *arr convention but were **not** spec-verified (the OpenAPI fetch truncated).
> Confirm against a live instance during implementation.

### Closing the loop

Poll the queue endpoint while the app is foregrounded; surface a "requested"
state on the item. No webhooks (they need a reachable server).

---

## 9. Stack and structure

```
:core:model        plain Java, no Android
:core:network      Subsonic client + capability negotiation
:core:database     Room — media_progress, remembered_speakers, library mirror
:core:media        Media3, MuPlayer, MediaLibraryService, cache
:core:cast         UPnP AV + Sonos layer + Jetty proxy + discovery
:core:ui           shared views, themes, adapters
:feature:*         library, player, book, search, settings, cast picker
:integrations:*    bindery, lidarr
:app               wiring + E2E journeys
```

**Java 17**, `sourceCompatibility`/`targetCompatibility` 17, with desugaring for
`java.time`. **No Kotlin plugin is applied to any module** — that is enforced by
a build check, not convention, so a stray dependency cannot reintroduce it.

| Concern | Choice | Why |
|---|---|---|
| Language | **Java 17** | User requirement |
| UI | **Views + XML + Material Components 1.13**, ViewBinding | Compose has no Java API |
| Navigation | Navigation Component, single Activity + Fragments | |
| Async | **Guava `ListenableFuture`** + `Executors` + `Handler` | Media3's own async type; no Kotlin coroutines |
| Reactive streams | **RxJava 3** where a stream is genuinely needed | Java-native; replaces `Flow` |
| Player | **Media3 1.11.0** | Java-native. Chapters, `PlayerFence`, `InMemoryDatabaseRule` |
| DI | **Hilt** | Full Java support; Google's recommendation |
| Database | **Room 2.8.x** | Room 3 is **Kotlin-codegen-only** — Java rules it out, independently of it being 7 weeks old |
| HTTP client | **OkHttp 5** + Retrofit 3 | Java-friendly |
| JSON | **Moshi** (reflection-free, codegen) | kotlinx.serialization is Kotlin-only |
| Images | **Glide** | Coil 3 is Kotlin-first |
| Phone HTTP server | **Embedded Jetty** | See §6 |
| Background work | WorkManager | Java API is first-class |
| Time | `java.time.Clock`, injected | `kotlin.time.Clock` is Kotlin-only; the Java one is the original |

`api`/`impl` split only where an implementation actually swaps. **No `:domain`
module and no use-case classes** — matching Google's own demotion of the domain
layer.

### Java-specific discipline

Java lacks null-safety, data classes and sealed types, so three conventions carry
weight they wouldn't in Kotlin:

- **`@NonNull`/`@Nullable` on every public signature**, with `NullAway` failing
  the build. This replaces what the Kotlin type system would have given for free
  and is not optional.
- **Immutable value types** — `record` for DTOs and domain models, `List.copyOf`
  at boundaries. Records make the model layer nearly as terse as Kotlin's.
- **Sealed interfaces + pattern-matching `switch`** (Java 17) for state and result
  types, so exhaustiveness is still compiler-checked.

### Deferred, but designed for

- **Offline downloads.** Media3's `DownloadService` **hardcodes** FGS type
  `dataSync` and calls `stopSelf()` on timeout, so bulk downloads hit Android 15's
  6 h cap and cannot restart until the app is foregrounded. The escape is
  user-initiated data transfer jobs (API 34+), which **WorkManager does not
  support** through 2.12.0-rc01 — raw `JobScheduler` + `RUN_USER_INITIATED_JOBS`.
- **Server-side progress sync.** Explicitly not wanted — positions are local. The
  local schema maps cleanly onto `createBookmark` + `savePlayQueueByIndex` if that
  ever changes.

---

## 10. Testing

The suite is the correctness gate, and the honest problem is that the same agent
writes the code and the tests — left alone it will write tests asserting what the
code *does*, not what it *should*.

### Countermeasures

1. **Golden files beat assertions.** Committed API fixtures and screenshots are
   artefacts that cannot be made to pass without a visible diff.
2. **An external oracle.** The **OpenSubsonic OpenAPI spec** (87 paths, 195
   schemas) validates every committed fixture — vendored, with a nightly
   non-blocking drift check.
3. **ArchUnit rules** that cannot be argued with: layering, no `Thread.sleep`, no
   `System.currentTimeMillis()` outside `:di` (inject `java.time.Clock`), no
   Kotlin plugin on any module, `@Nullable`/`@NonNull` coverage on public API.
4. **Goldens are recorded as separate bot commits**, never in the same commit as
   the change.
5. **Write the test first, from the spec** — for Subsonic, the spec exists.
6. **Budget the suite.** Past ~10 minutes the iteration loop degrades and humans
   merge on red. Speed is a correctness property.

### PR gate — ≤ 10 minutes, no emulator

| Job | Content |
|---|---|
| Static | detekt, ktlint, ArchUnit |
| Unit | mappers, token derivation, queue logic, resume maths with injected `java.time.Clock` |
| **Playback goldens** | `PlaybackOutput` + dump files; `ShadowAudioTrack` byte-compare for gapless; silence-skip frame counts; chapter assertion on the M4B fixture |
| **Session** | Browse tree and all `onPlaybackResumption` cases — under Robolectric, since they only need `Bundle`/`MediaItem`. `isAutomotiveController` branching tests Auto with no car. |
| **Contract** | Every fixture validated against the vendored OpenAPI spec |
| **Cast** | In-process fake renderer on `127.0.0.1:0` — SOAPACTION quoting, DIDL escaping round-trip, `protocolInfo` vs served `Content-Type`, renderer GETs the advertised URL, Range → 206/416/HEAD |
| Screenshot | **Roborazzi** on Views (`captureRoboImage()` works on any `View`), sharded, phone/tablet × light/dark |
| Coverage | **JaCoCo** branch coverage on `:core:*`, ratcheting (Kover is Kotlin-oriented) |

Java is an advantage across this table rather than a tax: Media3's test utilities,
Robolectric, JUnit 4, Espresso, ArchUnit, Truth and Mockito are all Java-native,
and the golden-file playback machinery is Java by origin.

Media3's Robolectric machinery covers playback end-to-end on the JVM. Coded audio
yields no bytes under `ShadowMediaCodec` (audio decoders drop input), so those
tests assert timing, buffer sequences, format changes and metadata; raw PCM
bypasses MediaCodec and gives real hashes.

### Nightly — emulator + real Navidrome container

`deluan/navidrome:0.63.2` pinned by digest, seeded with committed fixtures
(~183 KiB: three tagged MP3s and one chaptered M4B). Reached via **`adb reverse`**,
so the app uses `http://127.0.0.1:4533` identically on emulator and device.

Setup details that took measurement to find:
- `ND_DEFAULTADMINPASSWORD` **does not exist**; the real flag is
  `ND_DEVAUTOCREATEADMINPASSWORD`. Better still, `ND_EXTAUTH_USERHEADER` bypasses
  Subsonic auth entirely in CI.
- The image has no `curl`, and `/rest/ping.view` returns 200 even on auth failure
  — the healthcheck must match the body.
- Don't poll `scanning == false` (it is already false before the scan starts) —
  poll the expected track count.
- **Album and artist IDs are content-derived and stable; song IDs are random per
  scan.** Tests must look song IDs up.

Journeys: login → browse → play → background → resume; audiobook resume across a
**process kill** (`Shell.process.killPid`); offline degradation
(`Shell.wifi.turnOff`); local-network-permission denial; real audio via
`TeeAudioProcessor.WavFileAudioBufferSink` → WAV → RMS; Wear; generic DLNA
interop against `PRO-2684/dlna-dmr`.

### Why the fake renderer is in-process

**SSDP multicast does not work in the Android emulator** — measured: multicast
sent from the guest never arrives, while the same host listener receives real LAN
traffic. The emulator's SLIRP fork never joins multicast groups, and Google's docs
confirm no IGMP. Unicast works.

So the fake renderer binds Jetty to `127.0.0.1:0` inside the test process, with
`DeviceDiscovery` injected — exactly what Home Assistant does in production. Port
**0, not 1400**, which proves the code honours the device description rather than
hardcoding a port.

### Agent verification loop

`-Proborazzi.dumpUiTree=true` emits a deterministic JSON view hierarchy with
**exact bounds**. Per Roborazzi's own documentation, agents are bad at judging
layout changes from screenshots and will claim "fixed" when nothing moved. Exact
coordinates are checkable; a screenshot is not.

For a Views-based UI this is if anything more direct than for Compose — the
hierarchy is real `View` bounds rather than derived semantics. Where the dump
proves insufficient, Espresso's `ViewAssertion`s on `getLocationOnScreen` give the
same guarantee.

### Media3 test traps

- `FakeClock` auto-advance is **capped at 1 s** since 1.9.0 — tests fast-forwarding
  virtual minutes time out. Raise `maxAutoAdvancingTimeDiffMs`.
- Stuck-player detection is on by default; parking a player in `STATE_BUFFERING`
  now fails with `ERROR_CODE_TIMEOUT`.
- `MediaSession` getters **throw off the application looper** (1.11).
- Espresso's master idling policy is 60 s and its idling-resource timeout 26 s;
  raise both on CI emulators. A media player with a ticking progress bar is
  **never idle**, so idling resources are scoped to *preparation* only, never to
  playback — `PlayerFence` (`awaitPlaybackState`, `awaitContentPositionAtLeast`)
  is used instead.
- `DUMP_FILE_ACTION` is private in Media3; we need ~40 lines of our own
  regeneration switch.

---

## 11. Week-one spikes

Each is cheap and each could invalidate an assumption:

1. **`ACCESS_LOCAL_NETWORK` vs `10.0.2.2`.** If Android 17 gates RFC1918 addresses,
   the containerized-CI approach needs a debug-manifest change. Testable today on
   Android 16 via `am compat enable RESTRICT_LOCAL_NETWORK`.
2. **Navidrome transcode-cache seekability** — does a *completed* cached transcode
   become Range-seekable? Affects the format policy. A `curl -r` answers it.
3. **M4B chapter extraction over HTTP** — confirm Media3 1.11 extracts chapters
   from a `faststart` file served by Navidrome with `format=raw`, including the
   tail range request. This is the differentiator; prove it early.
4. **Sonos + Let's Encrypt.** No longer load-bearing, but five minutes to know.
5. **Lidarr payload shape** against a live instance.

*(The bookmark-units spike is gone — storing positions locally makes it moot.)*

---

## 12. Non-goals for v1

Offline downloads · server-side progress sync · SMAPI/bonob (documented, not
built) · the Sonos 1443 websocket backend · AirPlay · Google Cast · podcasts
(Navidrome has no podcast API) · Jellyfin emulation (not in Navidrome stable) ·
jukebox mode · multi-user.

---

## 13. Known risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Sonos disables UPnP by default or removes it | Medium, 2–3 years | Kills casting | Detect and guide; the 1443 websocket backend is designed for |
| Android 17 `ACCESS_LOCAL_NETWORK` semantics | Certain when targeting 37 | Breaks discovery and the proxy | Built in from the start; spike #2 |
| Navidrome Opus downsampling reaching a speaker | High if unguarded | Silent playback failure | Hard-force `f=raw`/`f=mp3` |
| Office AP client isolation | Medium | Proxy unreachable | Detect (proxy never receives the GET) and say so plainly |
| Full-tunnel VPN capturing LAN traffic | Medium | Discovery and proxy fail | Source-IP probe detects it; the fix is the user's VPN setting — a platform contract, not something we can engineer around |
| Agent-written tests that assert current behaviour | High | Suite stops being a gate | Golden files, OpenAPI oracle, ArchUnit, bot-committed goldens |
