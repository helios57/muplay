# MuPlay Kotlin Plan 6 — Casting: Sonos and DLNA

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MuPlay casts. A Sonos speaker or a generic DLNA renderer is discovered on the LAN,
appears in a picker, and plays the user's Navidrome library — fetching the bytes from an HTTP
server running on the phone, because a renderer on the LAN cannot authenticate to Navidrome the
way the phone can. Transport controls, volume and position work while cast; the position of a
book is still recorded; and when the speaker vanishes mid-track the app says so and comes back to
local playback at the right second instead of going quiet.

**Architecture:** A new **pure-JVM** module `:core:cast` owns everything on the wire — SSDP
discovery, the UPnP device description, the SOAP control layer, DIDL-Lite, the range-serving HTTP
proxy, and the routing decision — with **no Android type anywhere in it**, which is what puts the
whole protocol surface inside Tier 1. `:core:media` gains exactly two things: `UpnpPlayer`, a
`SimpleBasePlayer` that makes a remote renderer look like an ordinary Media3 `Player`, and the
switch that hands the `MediaSession` from one player to the other. That split is spec §9's own
(`core/cast` = *"UPnP/Sonos + the range-serving proxy + discovery"*; `core/media` = *"Media3,
MuPlayer, MediaLibraryService, cache"*), and it is what makes casting cheap: because the cast
output is a `Player`, `MuPlayer`'s `ForwardingPlayer` seam, the progress writer, the notification,
the media session, Android Auto and the Compose player all keep working with no change. A new
`:feature:castpicker` renders the device list and the cast controls.

**Tech Stack:** Kotlin 2.4.10, JDK 21, AGP 9.3.1, **KSP** (never KAPT), Media3 1.11.0
(`media3-common`/`media3-session` via `:core:media`; **no** `media3-cast` and **no**
`play-services-cast` — spec §11 rules Chromecast out), Room 2.8.4, Hilt 2.60.1, OkHttp 5.5.0
(upstream to Navidrome only — see Task 1), Compose BOM 2026.08.00 + Material 3 1.4.0,
Navigation 3 1.1.6, JUnit 5 (JVM) / JUnit 4 (device), AssertJ, Turbine, JaCoCo 0.8.12.
**No UPnP library.** Task 1 argues that case explicitly rather than assuming it.

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` — §6 is the core text; §4
(streaming and auth), §7 (platform integration and permissions), §10 (testing) and §12 (known
risks) all bind.

**Roadmap:** `docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md` — Plan 6, *"Cast across
all three network situations"*, depends on Plan 3.

---

## Global Constraints

Copied verbatim from the roadmap's **Global constraints** and from the spec. Every task inherits
these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI. `.kts` build scripts.
- Licence **MIT**. No GPL code may be copied.
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
  (`verifyReleaseManifest`, already wired into `check`). **This plan is the one that puts that
  constraint under real pressure**, because renderers have no TLS. Task 1 resolves it, in code and
  in the gate, and Task 11 widens the gate so the resolution cannot be undone quietly.
- **No Robolectric**, no Roborazzi, no ktlint/detekt/spotless.
- **Dependency minimalism.** Spec §6: *"This is a 'write it, don't depend on it' case."* Task 1
  states the case for and against a UPnP library rather than treating the spec sentence as
  settling it.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE` (both already declared),
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (Plan 3). **This plan adds none** —
  Task 1 says why `CHANGE_WIFI_MULTICAST_STATE` is not needed and Task 11 adds the gate that goes
  red the day `targetSdk` moves to 37 without `ACCESS_LOCAL_NETWORK`.

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
| 1 | `:core:cast` — the HTTP/1.1 codec written rather than depended on, and the rule that resolves the cleartext tension | the module exists, speaks HTTP over a socket, and refuses to speak it to the public internet |
| 2 | SSDP discovery, the device description, and Sonos's embedded `MediaRenderer` | three devices on one network become three named, deduplicated, correctly ordered entries |
| 3 | SOAP — the envelope, SOAPACTION's quotes, argument order, faults, and a fake renderer strict enough to reject | a real device's rejections are reproduced in Tier 1, and are asserted to happen |
| 4 | DIDL-Lite, `protocolInfo`, and the three-way MIME invariant | the metadata a renderer receives round-trips byte-for-byte, and names the format actually served |
| 5 | `UpnpRenderer` — `AVTransport`, `RenderingControl`, and the Sonos quirks | play, pause, stop, seek, volume and position against a strict renderer |
| 6 | The proxy — a range-serving HTTP/1.1 server, and the token that is not a track id | 206, 416, `HEAD`, and a byte-exact tail from the real Navidrome container |
| 7 | `CastRouter` — the routing rule, proved rather than guessed | the renderer either fetched from the phone or the app says out loud that it could not |
| 8 | `UpnpPlayer` — a `SimpleBasePlayer` over the renderer, and the renderer that disappears | Media3 sees an ordinary `Player`; a dead speaker becomes an error, not silence |
| 9 | Handover — the output switch, the one-shot resume target, and the progress row | casting mid-song lands on the same second, and a book's position is still written |
| 10 | `:feature:castpicker` — the device list, the cast button, and volume | a user picks a speaker and hears it, and sees why when they cannot |
| 11 | The gates — the Tier 1 Cast job, the Tier 2 cast journey, the coverage floors, the spec corrections | the whole subsystem is gated, and the spec's §6 no longer contradicts itself |

---

## What this plan builds on, and what it must not rebuild

Plan 6 depends on **Plan 3**. At the time this plan was written, **Plan 3 had not been executed**
(its checkbox file is pristine, and `:core:media` does not exist on disk), and **Plan 4 had not
been written at all**. Everything in the two tables below is therefore a *forward reference*.
Where a name is uncertain, the row says so, and the task that consumes it says so again at the
point of use. **Read the real file before writing code against any of these; if a name landed
differently, use the real one and record it in the task report. Do not add a second copy.**

### From Plan 3 (`docs/superpowers/plans/2026-08-24-muplay-k03-playback-core.md`)

| Symbol | Module | Where Plan 3 defines it |
|---|---|---|
| `StreamFormat` (`Raw`, `Mp3(maxBitRateKbps)`, `wireValue`, `forSuffix`, `DEFAULT_TRANSCODE_BITRATE_KBPS`) | `:core:model` | Task 1 |
| `SubsonicSource.streamUrl(songId, format): String` | `:core:network` | Task 1 |
| `MuPlayDataSourceFactory`, `MediaCache`, `TrackIdCacheKeyFactory` | `:core:media` | Tasks 2–3 |
| `MediaItems.of(song, streamUri, artworkUri, isAudiobook)` | `:core:media` | Tasks 4, 6 — **this plan adds a fifth parameter in Task 4** |
| `PlaybackQueue(songs, startIndex)`, `QueueRepository.mediaItems(queue)` | `:core:media` | Task 4 |
| `MuPlayerFactory.create(): ExoPlayer` | `:core:media` | Task 5 |
| `MuPlaybackService : MediaLibraryService`, `PlaybackNotification` | `:core:media` | Task 5 |
| `PlaybackState(...)`, `PlaybackState.NOTHING_PLAYING`, `PlaybackConnection` | `:core:media` | Task 5 |
| `PlaybackAudioAttributes`, `ContentTypeSwitcher` | `:core:media` | Task 6 |
| **`ResumeTarget(startIndex, startPositionMs)`**, **`fun interface ResumePolicy { resolve(mediaIds, requestedIndex): ResumeTarget }`**, **`NeverResume`** | `:core:media` | Task 8 |
| **`MuPlayer(player: Player, resumePolicy: ResumePolicy) : ForwardingPlayer`** | `:core:media` | Task 8 |
| **`ProgressWriter(player, dao, clock, scope)`** with `start()`, `write(mediaId, positionMs, finished)`, `flushBlocking()`, `TICK_MS` | `:core:media` | Task 8 |
| `PlaybackLauncher.play(songs, startIndex)`, `PlayerViewModel`, `PlayerScreen`, `MiniPlayer` | `:feature:player` | Task 9 |

### From Plan 2 (`docs/superpowers/plans/2026-08-24-muplay-k02-library-browse.md`)

`SubsonicSourceProvider.current()`, `LibraryRepository.idsWithRole(role)`, `BrowseRepository`,
`ShuffleRepository`. This plan touches **none** of them directly; `:core:media` does, and this
plan reaches Navidrome only through a stream URL that a `MediaItem` already carries.

### Already on disk and unchanged by this plan

`MediaProgressEntity(mediaId, positionMs, isFinished, lastPlayedAtEpochMs, speed, skipSilence,
gainDb)` and `MediaProgressDao` in `:core:database`, at **Room schema version 1**. **This plan adds
no table and no column, so the schema version does not move.** If you find yourself writing a Room
migration in this plan, stop — you have added something that belongs to Plan 4.

---

## The interaction with Plan 4, whose names are not yet fixed

**Plan 4 (Audiobooks) is being written concurrently and owns the resume, chapter and speed
surface.** This plan must not redesign any of it. The interaction is real and is stated here once,
and again in the Interfaces block of every task that touches it.

| What casting needs | What Plan 4 owns | How this plan behaves |
|---|---|---|
| A book cast to a speaker must still have its position recorded | the progress writer and the per-book columns | `UpnpPlayer` (Task 8) is a real `androidx.media3.common.Player`. Plan 3's `ProgressWriter` takes a `Player`, not an `ExoPlayer`, so **it attaches to the cast output unchanged**. This plan adds no second writer and no cast-specific progress path. |
| Casting mid-book must land on the right second | `ResumePolicy` and whatever policy Plan 4 installs | Task 9 wraps *whatever* `ResumePolicy` is bound with `OneShotResumePolicy`, a decorator over Plan 3's exact `fun interface`. It answers one handover target and then delegates. It has no independent shape. |
| A book has a per-item playback speed | `MediaProgressEntity.speed`, and Plan 4's accessor for it | **UPnP cannot deliver it.** `AVTransport::Play` takes `Speed="1"`, and `TransportPlaySpeed` values other than `1` are not implemented by Sonos or by any renderer this plan targets. Task 8 makes `UpnpPlayer` report `PlaybackParameters(1.0f)` and Task 10 surfaces that to the user as a visible statement. It does not silently accept a speed it cannot honour. |
| Chapters | `media3-inspector`, Plan 4 | Unaffected. Chapter extraction is local metadata reading; it does not care where the audio is rendered. |
| Sleep timer, smart rewind | Plan 4 | Unaffected. Both act on the `Player` interface. |

**Names that are not yet fixed, and what to do about each:**

1. **Whatever Plan 4 binds as the production `ResumePolicy`.** Task 9 must decorate that binding,
   not `NeverResume` specifically. If Plan 4 provides it under a Hilt qualifier, decorate the
   qualified binding.
2. **`ResumePolicy.resolve`'s signature.** Plan 3 specifies
   `resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget`. If Plan 4 widens it — a
   `LibraryRole`, a `mediaType`, a per-item speed — `OneShotResumePolicy` must be widened to match
   in the same shape. It is a decorator; it has no signature of its own.
3. **Plan 4's accessor for a book's stored speed.** Task 10's "this speaker plays at 1×" copy
   should name the stored speed if Plan 4 exposes one. If it does not exist yet, the copy states
   the rule without the number, and the task report records that it was left general on purpose.
4. **Whether Plan 4 adds columns to `media_progress`.** If it does, Task 9's handover write must
   remain a read-modify-write that preserves every column it does not own — the same rule Plan 3
   Task 8 imposes on the ticker, for the same reason.

**Nothing in this plan may define a `ResumePolicy`, a progress writer, a per-book column, or a
per-item speed mechanism of its own.** If a task seems to need one, it needs Plan 4 to have landed
first.

---

## The defect class this plan is written against

Five review rounds on this project have found the same failure: **assertions that execute but do
not discriminate.** Each round closed one "unit" and left the next unasked — endpoint, then
request parameter, then type, then **field**, then **collection order**. The rules that came out of
it bind every test in this plan:

1. **The unit is the field.** For every field this plan's code assigns, an assertion must fail when
   that field becomes a constant.
2. **A value observed at exactly one value is not tested.** Vary only the argument under test, hold
   everything else constant, and assert both observations.
3. **`allMatch`/`anyMatch`/`none` are vacuously true on an empty collection.** Map the field and
   assert the exact list.
4. **Order is a property** wherever order is meaningful — and in this plan it is meaningful in
   three separate places, which is unusual: SOAP argument order, the discovered-device list, and
   the character-replacement order inside XML escaping.
5. **A gate reporting the absence of a problem must be provably incapable of staying quiet when it
   did not run.**
6. **Coverage floors cannot catch this class.** A constant field assignment removes no branch.
   Verified at the bytecode level, not argued — `ci/mutation-probes.sh` is where the answers go.

**The four analogues that will bite this plan specifically**, named here so no task can pretend it
did not know:

- **A SOAP test that asserts a request was *sent*.** `assertThat(fake.requests).hasSize(1)` passes
  against an envelope with the wrong namespace, an unquoted SOAPACTION, arguments in the wrong
  order and doubly-escaped metadata — all four of which a real Sonos rejects. Every SOAP assertion
  in this plan is on **bytes the fake recorded**, not on a parsed convenience object.
- **A discovery test with exactly one device on the network.** One device cannot show ordering,
  cannot show deduplication, cannot show that the wrong `ST` was filtered out, and cannot show that
  a `MediaServer` was excluded. Every discovery test in Task 2 runs **at least three responders**,
  at least one of which must not appear in the result.
- **A Range test that only ever requests byte 0.** `bytes=0-` exercises neither the offset
  arithmetic nor `Content-Range` nor the 416 boundary. Task 6's range table is a parameterised list
  of nine cases with the exact expected status, `Content-Range` and body bytes for each.
- **A proxy test where the fake renderer accepts everything.** A permissive fake means no rejection
  path is ever executed and the strictness that makes the fake worth having is untested. Task 3
  builds the fake **strict by default** and gives its strictness **its own test class**
  (`FakeRendererStrictnessTest`), which asserts that the fake rejects each of the six things a real
  device rejects. A fake that cannot say no is a fake that proves nothing.

Spec §10 names the in-process renderer on `127.0.0.1:0` as the technique. It also files it at rung
2 (*"an in-process **real** UPnP renderer"*) rather than rung 4. That is the standard this plan
holds it to: it is a real HTTP server speaking real SOAP over a real socket, and the only thing
about it that is not real is the loudspeaker.

---

## Scope discipline

Plan 6 is **casting**. Explicitly **not** in this plan:

- **Android Auto and Wear OS** — Plan 5. Casting changes nothing there: the car and the watch talk
  to a `MediaSession`, and Task 9 hands the session a different `Player` without the session
  noticing.
- **Bindery and Lidarr** — Plan 7.
- **Core local playback, the queue, the notification, gapless and the cache** — Plan 3. This plan
  consumes all of it.
- **Per-book resume, chapters, speed and the sleep timer** — Plan 4. See the interaction table
  above.
- **Chromecast.** Spec §11: *"Chromecast. Sonos and DLNA are the requirement."* No
  `play-services-cast`, no `media3-cast`. Adding either would also drag a manifest into the merge
  that `verifyReleaseManifest` has opinions about.
- **GENA eventing (`SUBSCRIBE`/`NOTIFY`).** Task 8 polls, and the reason is not laziness: the
  `AVTransport:1` `LastChange` event **does not carry playback position at all** — `RelTime` and
  `AbsTime` are excluded from the evented state variable set. So an eventing implementation would
  still poll `GetPositionInfo` for the seek bar, and would additionally owe a callback endpoint,
  subscription renewal timers, and a parser for XML-escaped XML inside an XML document. It buys a
  faster `PLAYING`/`STOPPED` transition and nothing else.
- **Sonos zone-group topology and grouping.** Task 5 **detects** that a speaker is following
  another (its `AVTransport` `CurrentURI` starts with `x-rincon:`) and reports it as a named
  failure, because the alternative is a cast that appears to succeed and plays nowhere. It does
  not implement `ZoneGroupTopology`, join, or unjoin.
- **Sonos SMAPI / a Sonos music service.** A different protocol entirely, requiring a
  publicly-reachable HTTPS endpoint and a registered service id.
- **Serving the Media3 cache to the renderer.** A tempting optimisation — a cached track could be
  cast with no network at all — and deliberately deferred. `SimpleCache` is keyed and locked for a
  local `CacheDataSource`; a second reader that is not one is a concurrency question this plan does
  not need to answer to make casting work. The proxy relays from Navidrome.
- **Casting video, images, or a browsable `ContentDirectory`.** Spec §11 rules out video; MuPlay is
  a control point, never a `MediaServer`.
- **Wi-Fi Direct, Bluetooth, or any non-IP transport.**

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | **modify** — include `:core:cast`, `:feature:castpicker` |
| `build.gradle.kts` | **modify** — coverage floors for both new modules; `liveNavidromeTest` registration generalised to a list of projects |
| `gradle/libs.versions.toml` | **modify** — no new *third-party* library; Compose/Hilt aliases only if Plan 3 has not added them |
| `core/cast/build.gradle.kts` | **new** — `muplay.jvm.library`; OkHttp for upstream only |
| `core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt` | **new** — the HTTP/1.1 request/response head codec shared by the client, the server and SSDP |
| `core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt` | **new** — case-insensitive, order-preserving, multi-value headers |
| `core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt` | **new** — a socket HTTP/1.1 client for renderer-facing traffic |
| `core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt` | **new** — the private-address rule that replaces a manifest-wide cleartext switch |
| `core/cast/src/main/kotlin/app/muplay/cast/net/LocalAddress.kt` | **new** — the source address the kernel would use to reach a peer |
| `core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpSearch.kt` | **new** — the M-SEARCH datagram, byte for byte |
| `core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpTransport.kt` | **new** — send a search, collect replies, multicast or unicast |
| `core/cast/src/main/kotlin/app/muplay/cast/discovery/DeviceDescription.kt` | **new** — the UPnP description parser, `URLBase`, embedded devices, XXE refusal |
| `core/cast/src/main/kotlin/app/muplay/cast/discovery/CastDevice.kt` | **new** — a renderer this app can actually control |
| `core/cast/src/main/kotlin/app/muplay/cast/discovery/RendererDirectory.kt` | **new** — discovery, dedupe, ordering, and the remembered-device fallback |
| `core/cast/src/main/kotlin/app/muplay/cast/soap/SoapEnvelope.kt` | **new** — envelope rendering, SOAPACTION, response and fault parsing |
| `core/cast/src/main/kotlin/app/muplay/cast/soap/XmlText.kt` | **new** — escaping, in the one order that is correct |
| `core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpError.kt` | **new** — the fault codes a renderer really returns |
| `core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpTime.kt` | **new** — `H:MM:SS` in both directions, and `NOT_IMPLEMENTED` |
| `core/cast/src/main/kotlin/app/muplay/cast/didl/ServedMedia.kt` | **new** — MIME, file extension and `protocolInfo`, from one source |
| `core/cast/src/main/kotlin/app/muplay/cast/didl/DidlLite.kt` | **new** — the metadata document, and the item it describes |
| `core/cast/src/main/kotlin/app/muplay/cast/control/UpnpRenderer.kt` | **new** — `AVTransport` and `RenderingControl` |
| `core/cast/src/main/kotlin/app/muplay/cast/control/TransportState.kt` | **new** — the renderer's own state vocabulary |
| `core/cast/src/main/kotlin/app/muplay/cast/proxy/RangeHeader.kt` | **new** — RFC 7233 parsing and resolution |
| `core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyRegistry.kt` | **new** — the opaque token, minted and revoked |
| `core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyUpstream.kt` | **new** — the OkHttp fetch of Navidrome, and its length probe |
| `core/cast/src/main/kotlin/app/muplay/cast/proxy/MediaProxyServer.kt` | **new** — the phone's HTTP server |
| `core/cast/src/main/kotlin/app/muplay/cast/route/CastRoute.kt` | **new** — proxied, renderer-direct, or honestly unroutable |
| `core/cast/src/main/kotlin/app/muplay/cast/route/CastRouter.kt` | **new** — the decision, proved by observation |
| `core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRenderer.kt` | **new** — a real UPnP renderer in-process, strict by default |
| `core/cast/src/test/kotlin/app/muplay/cast/fake/FakeSsdpResponder.kt` | **new** — several devices on one loopback "network" |
| `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt` | **modify** — a fifth parameter, so the served MIME has one source |
| `core/media/src/main/kotlin/app/muplay/media/cast/UpnpPlayer.kt` | **new** — `SimpleBasePlayer` over a renderer |
| `core/media/src/main/kotlin/app/muplay/media/cast/CastSessionManager.kt` | **new** — start a session, tear one down, react to a dead speaker |
| `core/media/src/main/kotlin/app/muplay/media/cast/OneShotResumePolicy.kt` | **new** — the decorator that carries a position across a handover |
| `core/media/src/main/kotlin/app/muplay/media/PlaybackOutputSwitch.kt` | **new** — the seam `MuPlaybackService` observes |
| `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` | **modify** — observe the switch, `setPlayer`, move the writer |
| `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt` | **modify** — the cast graph |
| `feature/castpicker/build.gradle.kts` | **new** |
| `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastUiState.kt` | **new** |
| `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastViewModel.kt` | **new** |
| `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastPickerSheet.kt` | **new** |
| `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastButton.kt` | **new** |
| `feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt` | **modify** — the cast button and the "playing on" line |
| `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` | **modify** — host the picker sheet |
| `app/src/androidTest/kotlin/app/muplay/CastJourneyTest.kt` | **new** — Tier 2: discover, cast, hear it advance, lose the speaker |
| `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt` | **modify** — the release-manifest gate also forbids a network security config |
| `app/src/test/kotlin/app/muplay/ConventionTest.kt` | **modify** — the `targetSdk 37` / `ACCESS_LOCAL_NETWORK` rule |
| `.github/workflows/pr.yml` | **modify** — a fifth job, `cast` |
| `.github/workflows/e2e.yml` | **modify** — `:feature:castpicker` in the `script:` enumeration |
| `ci/mutation-probes.sh` | **modify** — the probes this plan's fields earn, and `:core:cast:test` in `run_suite` |
| `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` | **modify** — the §6, §10 and §12 corrections Task 11 lists |

---
