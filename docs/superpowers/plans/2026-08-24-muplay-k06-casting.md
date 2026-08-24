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
| `core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyUpstream.kt` | **new** — the OkHttp fetch of Navidrome, its length probe, and the 429 policy this HTTP path needs of its own |
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

## Task 1: `:core:cast` — the HTTP/1.1 codec written rather than depended on, and the rule that resolves the cleartext tension

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/cast/build.gradle.kts`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/net/LocalAddress.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/http/HttpHeadersTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/http/HttpWireTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/http/CastHttpClientTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/net/LocalNetworkOnlyTest.kt`
- Modify: `build.gradle.kts` (`coverageFloors` gains `":core:cast"`)

**Interfaces:**
- Consumes: nothing. This task is deliberately the one that stands alone — the module and its
  socket layer are proven before any UPnP concept is involved.
- Produces:
  - Gradle module `:core:cast`, plugin `muplay.jvm.library`, package `app.muplay.cast`
  - `class HttpHeaders(entries: List<Pair<String, String>>)` with
    `operator fun get(name: String): String?`, `fun all(name: String): List<String>`,
    `val names: List<String>`, `val size: Int`, `fun contentLength(): Long?`,
    `companion object { fun of(vararg pairs: Pair<String, String>): HttpHeaders; val EMPTY }`
  - `data class HttpRequestHead(val method: String, val target: String, val version: String, val headers: HttpHeaders)`
  - `data class HttpResponseHead(val version: String, val code: Int, val reason: String, val headers: HttpHeaders)`
  - `class MalformedHttpException(message: String) : IOException`
  - `object HttpWire` with
    `fun readRequestHead(input: InputStream): HttpRequestHead`,
    `fun readResponseHead(input: InputStream): HttpResponseHead`,
    `fun parseHeaderBlock(text: String): HttpHeaders`,
    `fun renderResponseHead(code: Int, reason: String, headers: HttpHeaders): ByteArray`,
    `const val MAX_LINE_BYTES = 8192`, `const val MAX_HEADERS = 64`, `const val CRLF = "\r\n"`
  - `data class CastHttpResponse(val head: HttpResponseHead, val body: ByteArray)` with
    `val code: Int`, `fun bodyText(): String`
  - `class CastHttpClient(connectTimeoutMs: Int = 4_000, readTimeoutMs: Int = 8_000)` with
    `fun exchange(url: URI, method: String, headers: HttpHeaders = HttpHeaders.EMPTY, body: ByteArray? = null): CastHttpResponse`
  - `class NonLocalAddressException(host: String, address: InetAddress) : IOException`
  - `object LocalNetworkOnly` with `fun isLocal(address: InetAddress): Boolean` and
    `fun require(host: String, address: InetAddress)`
  - `object LocalAddress` with `fun towards(peer: InetAddress): InetAddress?`

### Why this module writes its own HTTP, and why that is not the usual mistake

Three separate arguments converge on the same answer, and the plan states all three because the
constraints say *"resist pulling in a large UPnP library without arguing the case explicitly."*

**1. The spec already committed to writing the server half.** §6: *"This is a 'write it, don't
depend on it' case — a servlet container to serve range requests to one speaker is a large
dependency used for a fraction of its surface. A minimal HTTP/1.1 range server is a few hundred
lines we own."* Given that the server exists, a request-line-and-headers codec exists. The client
half is then **the same parser run over a response instead of a request** — the marginal cost is
one function.

**2. SSDP is a third consumer that no HTTP library can serve.** An M-SEARCH reply is literally
`HTTP/1.1 200 OK` followed by headers — over **UDP**. OkHttp cannot parse a datagram; neither can
`HttpURLConnection`. So `parseHeaderBlock` has to exist regardless of what the control plane uses.
Three consumers (SSDP, the SOAP client, the proxy server) over one ~200-line codec is not a
not-invented-here decision; it is the shape of the problem.

**3. It is the only way to satisfy the cleartext constraint honestly — see below.**

**And the argument against a UPnP library, stated fairly.** `jUPnP` (the maintained fork of Cling)
is real, works, and would supply discovery, description parsing, SOAP, and eventing. Its licence is
CDDL-1.0, which permits linking, so the MIT constraint is not the objection. The objections are:
it brings an HTTP server stack of its own onto Android — historically an EOL Jetty — which is
precisely the "servlet container for one speaker" the spec rejected; it owns its own threading and
lifecycle, which would have to be reconciled with a `MediaLibraryService` and a Hilt graph; and
**it would be a second HTTP server in the process alongside the proxy the spec requires anyway.**
Set against that, what MuPlay actually needs from UPnP is: one datagram format, one XML document
shape, four SOAP actions on `AVTransport`, three on `RenderingControl`, and no eventing. That is
the fraction-of-the-surface test failing in the same direction the spec called.

**What is *not* hand-written:** the proxy's upstream fetch of Navidrome. That is HTTPS to a real
server on the public internet, with redirects, TLS, connection reuse and Navidrome's 429 — and it
uses **OkHttp 5.5.0**, the same library the rest of the project uses (Task 6). The split is exact:
**OkHttp for traffic to Navidrome; this module's socket code for traffic to a renderer.** That is
not a stylistic split. It is the next section.

### The cleartext tension, and how it is resolved rather than dodged

The constraint is `usesCleartextTraffic` is debug-only and must never reach the release manifest.
That is enforced today by `verifyReleaseManifest`, a substring scan over AGP's *merged* release
manifest (`build-logic/convention/src/main/kotlin/VerifyMergedManifestTask.kt`). Confirmed on disk:
`app/src/main/AndroidManifest.xml` declares no `usesCleartextTraffic` and no
`android:networkSecurityConfig`, and there is **no `res/xml/network_security_config*.xml` anywhere
in the repository**. So in a release build `NetworkSecurityPolicy.isCleartextTrafficPermitted()` is
`false` for every host, which is the correct posture for a client of a public HTTPS Navidrome.

Casting pushes on that from two directions, and they are **not** the same problem:

**Direction A — the phone is the server.** The proxy accepts plaintext TCP from the renderer.
`NetworkSecurityPolicy` governs **outbound** connections made by stacks that consult it —
`HttpURLConnection`, OkHttp, Cronet, `WebView`, `DownloadManager`, `MediaPlayer`. It has no
mechanism to affect a `ServerSocket`, and Android's own documentation for the flag says it is
honoured "on a best-effort basis" by the platform's network stacks and does not cover libraries
that do not use them. **A plaintext listening socket therefore needs no permission and no manifest
change.** This half of the tension is a non-problem, and the plan says so out loud rather than
leaving a reader to wonder why no manifest entry appears.

**Direction B — the phone is the client.** Fetching `http://192.168.1.50:1400/xml/device_description.xml`
and `POST`ing SOAP to a control URL are outbound cleartext requests. **OkHttp does consult the
policy**: in `RealConnection.connectSocket`, for a route with no `sslSocketFactory`, it calls
`Platform.get().isCleartextTrafficPermitted(host)` and throws
`java.net.UnknownServiceException: CLEARTEXT communication to <host> not permitted by network
security policy`. Renderers have no TLS and never will. So a release build of MuPlay using OkHttp
for the control plane **cannot cast at all**, and the failure would arrive as an exception nobody
sees until a release build is on a phone next to a speaker.

Three ways out, two of which are worse:

- **Set `usesCleartextTraffic="true"` in release.** Rejected: it is host-blind. It would permit
  cleartext to Navidrome as well, which is the one host the constraint exists to protect, and it
  would fail `verifyReleaseManifest`, correctly.
- **Ship a release `network_security_config.xml` permitting cleartext for the LAN.** Rejected on a
  technical fact, not a preference: the format takes `<domain>` entries — hostnames and IP literals
  — and **cannot express a subnet**. There is no way to write "RFC 1918 only". The only expressible
  version is `<base-config cleartextTrafficPermitted="true">`, which is the previous option wearing
  a different hat. Worse, it would slip past today's gate, whose `forbiddenAttributes` list is
  `listOf("usesCleartextTraffic")` and says nothing about `networkSecurityConfig`. **Task 11 closes
  that hole**, because a casting plan is exactly the plan that would otherwise open it.
- **Do not route renderer traffic through a policy-consulting stack, and enforce the real rule in
  code.** Chosen. `CastHttpClient` opens a `java.net.Socket` and speaks HTTP/1.1 over it, and
  before it connects it resolves the host and **refuses any address that is not loopback,
  link-local, RFC 1918 private, RFC 6598 carrier-grade NAT, or an IPv6 unique local address**.

The third option is not a loophole; it is a **stronger and more precise** guarantee than the
manifest can express. `usesCleartextTraffic` says "cleartext, anywhere, yes or no". The rule the
project actually wants is *"cleartext to a device on my own network, never cleartext to the
internet"*, and `LocalNetworkOnly` says exactly that, in one place, with a test that observes it
failing as well as passing. The release manifest keeps its `false`, so `:core:network`'s OkHttp
still cannot talk cleartext to a public Navidrome, and the proxy's own upstream — which is OkHttp
— is still held to that.

Write this reasoning into `LocalNetworkOnly`'s KDoc, not only into this plan. A future reader
finding a hand-rolled HTTP client will otherwise assume it was an accident.

> **RFC 6598 (`100.64.0.0/10`) is in the allow-list on purpose and is easy to miss.**
> `InetAddress.isSiteLocalAddress()` returns `false` for it, and Tailscale hands out addresses from
> exactly that block. Spec §6's third routing situation is *"Remote + VPN"*. Leaving 100.64/10 out
> would make the feature the spec calls a named requirement fail for the most common way people
> build the network it describes. IPv6 unique local addresses (`fc00::/7`) need the same explicit
> handling: `isSiteLocalAddress()` covers only the deprecated `fec0::/10`.

- [ ] **Step 1: Add the module**

`settings.gradle.kts` — add, at column 0, beside the existing includes:

```kotlin
include(":core:cast")
```

> `ConventionTest`'s `every Gradle project has a coverage floor` rule reads this file with
> `Regex("""^include\("(:[^"]+)"\)""", RegexOption.MULTILINE)`. Leading whitespace or an
> `include(project(...))` form makes the module invisible to the rule, and an invisible module is
> one whose missing coverage floor goes unreported.

`core/cast/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.jvm.library")
}

dependencies {
  // `api`, not `implementation`: this module's public surface takes and returns `StreamFormat`
  // (see `ServedMedia` in Task 4), so a consumer cannot compile against it without `:core:model`.
  api(project(":core:model"))

  // OkHttp is here for exactly one job: the proxy's *upstream* fetch of Navidrome over HTTPS
  // (Task 6), where TLS, redirects, connection reuse and Navidrome's 429 all matter. It is
  // deliberately NOT used for any traffic to a renderer -- see `LocalNetworkOnly`'s KDoc and this
  // task's own note on the cleartext constraint. If you find an `okhttp3` import under
  // `app/muplay/cast/http`, `discovery`, `soap`, `didl` or `control`, that is the bug.
  implementation(libs.okhttp)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
```

> A **JVM** module, not an Android one, and that is a load-bearing choice. Every type in this
> module is `java.net`, `java.io`, `javax.xml` or Kotlin — there is no `Context`, no
> `ConnectivityManager`, no `WifiManager`, no `android.net.Uri`. That is what puts the entire
> protocol surface, the proxy and the routing decision inside **Tier 1**, which has no emulator.
> The enforcement is free and automatic: an Android type that creeps in makes this module's own
> JVM tests throw `RuntimeException: Stub!` on the first call. Media3 types are Android types, so
> `UpnpPlayer` lives in `:core:media` (Task 8) — which is also where spec §9 puts it.

`build.gradle.kts` — add to `coverageFloors`, keeping the `" to listOf(` on the same line as the
path (`ConventionTest` extracts the keys with
`Regex("""^\s*"(:[^"]+)" to listOf\(""", RegexOption.MULTILINE)`):

```kotlin
  // `:core:cast`. A pure-JVM module with no Compose and no Android, so every floor here is BRANCH
  // and every one of them is enforceable in Tier 1 -- `requiresInstrumentedData` appears nowhere
  // in this entry, deliberately. The include list grows one task at a time and is completed in
  // Task 11; each task measures its own classes and refuses to lower a floor to make a number fit.
  ":core:cast" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.http.HttpHeaders",
        "app.muplay.cast.http.HttpHeaders*",
        "app.muplay.cast.http.HttpWire",
        "app.muplay.cast.net.LocalNetworkOnly",
      ),
    ),
  ),
```

- [ ] **Step 2: Confirm the module resolves and the guards accept it**

```bash
./gradlew :core:cast:dependencies --configuration runtimeClasspath
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
```

Expected: OkHttp resolves at **5.5.0**; `ConventionTest` passes all seven rules, including
`every Gradle project has a coverage floor` — which fails loudly if Step 1's two edits landed in
only one file. Run it now rather than at the end of the task: it is the cheapest possible check
that the module was added the way the build expects.

- [ ] **Step 3: Write the failing header tests**

`core/cast/src/test/kotlin/app/muplay/cast/http/HttpHeadersTest.kt`:

```kotlin
package app.muplay.cast.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * HTTP header semantics, which are not the same as `Map<String, String>` semantics in three ways
 * that all matter on this wire:
 *
 * 1. **Names are case-insensitive.** Sonos sends `CONTENT-TYPE`, most DLNA renderers send
 *    `Content-Type`, and an SSDP reply sends `LOCATION` in capitals. A `Map` lookup on the wrong
 *    case returns null, and a null `LOCATION` is a device that silently never appears.
 * 2. **A name may repeat.** Rare here but legal, and dropping a repeat silently is the kind of
 *    thing that is only noticed years later.
 * 3. **Order is preserved on the way out.** A renderer must never be able to tell this client
 *    apart by header order changing between runs, and a byte-exact assertion on a rendered
 *    response head is only possible if the order is deterministic.
 */
class HttpHeadersTest {

  private val headers = HttpHeaders.of(
    "Content-Type" to "text/xml; charset=\"utf-8\"",
    "CONTENT-LENGTH" to "128",
    "Server" to "Linux UPnP/1.0 Sonos/84.1-52250",
  )

  @Test
  fun `a header is found whatever case the peer used`() {
    // Two different lookups of two different headers, so a `get` that lowercases only the needle
    // and not the haystack fails, and so does one that does the reverse.
    assertThat(headers["content-type"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["Content-Type"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["CONTENT-TYPE"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["content-length"]).isEqualTo("128")
    assertThat(headers["Content-Length"]).isEqualTo("128")
  }

  @Test
  fun `a header that is not there is null rather than empty`() {
    // The distinction is load-bearing in Task 6: an absent `Range` means "send the whole thing",
    // and an empty one is malformed. Collapsing them loses the difference.
    assertThat(headers["Range"]).isNull()
    assertThat(headers.all("Range")).isEmpty()
  }

  @Test
  fun `a repeated name keeps every value, in the order the peer sent them`() {
    val repeated = HttpHeaders.of(
      "X-Trial" to "first",
      "x-trial" to "second",
      "X-TRIAL" to "third",
    )

    // The exact list, not `hasSize(3)` and not `contains(...)`: order is the property under test,
    // and `containsExactly` is the only assertion that fails when the order changes.
    assertThat(repeated.all("x-trial")).containsExactly("first", "second", "third")
    // `get` is "the first value", which is what every consumer in this module wants.
    assertThat(repeated["X-Trial"]).isEqualTo("first")
  }

  @Test
  fun `names come back in the order they were given, with the case the peer used`() {
    // Rendering (Task 6) writes these back out verbatim. A `names` that sorted or lowercased would
    // make the byte-exact response-head assertions in `HttpWireTest` unwritable.
    assertThat(headers.names).containsExactly("Content-Type", "CONTENT-LENGTH", "Server")
    assertThat(headers.size).isEqualTo(3)
  }

  @Test
  fun `content length is parsed as a number, and refuses what is not one`() {
    // Four observations, three of them rejections. A `contentLength()` returning a constant passes
    // at most one.
    assertThat(HttpHeaders.of("Content-Length" to "0").contentLength()).isEqualTo(0L)
    assertThat(HttpHeaders.of("Content-Length" to "4096").contentLength()).isEqualTo(4096L)
    assertThat(HttpHeaders.of("Content-Length" to "chunked").contentLength()).isNull()
    assertThat(HttpHeaders.of("Content-Length" to "-1").contentLength()).isNull()
    assertThat(HttpHeaders.EMPTY.contentLength()).isNull()
  }

  @Test
  fun `the empty headers really are empty`() {
    // Rule 3: `EMPTY.names` being empty is what makes every `allMatch`-shaped assertion elsewhere
    // in this module suspect, so the emptiness itself is pinned here where it is the subject.
    assertThat(HttpHeaders.EMPTY.names).isEmpty()
    assertThat(HttpHeaders.EMPTY.size).isZero
    assertThat(HttpHeaders.EMPTY["anything"]).isNull()
  }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*HttpHeadersTest*'`
Expected: FAIL — `Unresolved reference: HttpHeaders`.

- [ ] **Step 5: Implement `HttpHeaders`**

`core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt`:

```kotlin
package app.muplay.cast.http

/**
 * An HTTP/1.1 header block: **case-insensitive on lookup, order-preserving on iteration, and
 * multi-valued**.
 *
 * Not a `Map<String, String>`, because a `Map` gets all three of those wrong. Sonos sends
 * `CONTENT-TYPE`, an SSDP reply sends `LOCATION`, and most DLNA renderers send `Content-Type` --
 * a map lookup on the wrong case returns null, and a null `LOCATION` is a device that never
 * appears in the picker with nothing reported anywhere.
 *
 * Order is preserved because this type is also used to *render* responses (see [HttpWire]), and a
 * byte-exact assertion on a rendered response head is only writable if the order is deterministic.
 *
 * @param entries name/value pairs in wire order. Names keep the case the peer used.
 */
class HttpHeaders(private val entries: List<Pair<String, String>>) {

  /** The first value for [name], or `null` if the peer sent no such header. */
  operator fun get(name: String): String? = all(name).firstOrNull()

  /** Every value for [name], in wire order. Empty if the peer sent none. */
  fun all(name: String): List<String> =
    entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

  /** Header names in wire order, with the case the peer used. */
  val names: List<String> get() = entries.map { it.first }

  val size: Int get() = entries.size

  fun asList(): List<Pair<String, String>> = entries

  /**
   * `Content-Length` as a non-negative `Long`, or `null` when it is absent or is not one.
   *
   * A negative value is treated as absent rather than passed through: `Content-Length: -1` is what
   * a broken server sends to mean "I don't know", and returning it would let a caller allocate or
   * loop against it. "Absent" is the honest translation.
   */
  fun contentLength(): Long? = this["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0 }

  override fun toString(): String = entries.joinToString(", ") { "${it.first}: ${it.second}" }

  companion object {
    val EMPTY = HttpHeaders(emptyList())

    fun of(vararg pairs: Pair<String, String>): HttpHeaders = HttpHeaders(pairs.toList())
  }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*HttpHeadersTest*'`
Expected: PASS, 6/6.

- [ ] **Step 7: Write the failing wire-codec tests**

`core/cast/src/test/kotlin/app/muplay/cast/http/HttpWireTest.kt`:

```kotlin
package app.muplay.cast.http

import java.io.ByteArrayInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The HTTP/1.1 head codec, which has three consumers: the proxy server reads request heads, the
 * SOAP client reads response heads, and SSDP parses a bare header block out of a UDP datagram.
 *
 * Every rejection below is a real defence, not a formality. This parser reads bytes from a device
 * on the local network that MuPlay did not write and cannot vouch for, and a parser with no size
 * limits will happily consume a gigabyte of `A`s from a single line.
 */
class HttpWireTest {

  private fun request(raw: String) = HttpWire.readRequestHead(ByteArrayInputStream(raw.toByteArray()))
  private fun response(raw: String) = HttpWire.readResponseHead(ByteArrayInputStream(raw.toByteArray()))

  @Test
  fun `a request line is split into its three parts, and each one is the one that was sent`() {
    // Three fields, two observations each, so no single constant satisfies any of them.
    val get = request("GET /media/abc.mp3 HTTP/1.1\r\nHost: 10.0.0.5:8080\r\n\r\n")
    val head = request("HEAD /media/def.flac HTTP/1.0\r\nHost: 10.0.0.5:8080\r\n\r\n")

    assertThat(get.method).isEqualTo("GET")
    assertThat(get.target).isEqualTo("/media/abc.mp3")
    assertThat(get.version).isEqualTo("HTTP/1.1")
    assertThat(head.method).isEqualTo("HEAD")
    assertThat(head.target).isEqualTo("/media/def.flac")
    assertThat(head.version).isEqualTo("HTTP/1.0")
  }

  @Test
  fun `a status line is split into its three parts, and the reason phrase may contain spaces`() {
    val ok = response("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
    val partial = response("HTTP/1.1 206 Partial Content\r\nContent-Length: 5\r\n\r\n")

    assertThat(ok.code).isEqualTo(200)
    assertThat(ok.reason).isEqualTo("OK")
    assertThat(partial.code).isEqualTo(206)
    // "Partial Content" has a space in it. A naive three-way split on whitespace loses the second
    // word, and a reason phrase is what a UPnP fault's human-readable half arrives in.
    assertThat(partial.reason).isEqualTo("Partial Content")
    assertThat(partial.version).isEqualTo("HTTP/1.1")
  }

  @Test
  fun `a status line with no reason phrase is legal and parses`() {
    // RFC 7230: the reason phrase may be empty. Sonos does not do this; some embedded renderers do.
    val terse = response("HTTP/1.1 500 \r\nContent-Length: 0\r\n\r\n")

    assertThat(terse.code).isEqualTo(500)
    assertThat(terse.reason).isEmpty()
  }

  @Test
  fun `headers survive the trip with their values and their order`() {
    val head = response(
      "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://10.0.0.5:1400/xml/device_description.xml\r\n" +
        "SERVER: Linux UPnP/1.0 Sonos/84.1-52250\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )

    // The exact list of names, in order. `hasSize(5)` would pass with every value swapped.
    assertThat(head.headers.names)
      .containsExactly("CACHE-CONTROL", "EXT", "LOCATION", "SERVER", "ST")
    assertThat(head.headers["location"])
      .isEqualTo("http://10.0.0.5:1400/xml/device_description.xml")
    assertThat(head.headers["st"]).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    // `EXT:` with nothing after it is a *mandatory* header in a UPnP response and its value is the
    // empty string. Dropping it because it looks blank would make this parser reject a conformant
    // device.
    assertThat(head.headers["ext"]).isEqualTo("")
  }

  @Test
  fun `optional whitespace after the colon is trimmed and interior whitespace is not`() {
    val head = response(
      "HTTP/1.1 200 OK\r\nX-A:   spaced   \r\nX-B:tight\r\nX-C: two words\r\n\r\n",
    )

    assertThat(head.headers["X-A"]).isEqualTo("spaced")
    assertThat(head.headers["X-B"]).isEqualTo("tight")
    assertThat(head.headers["X-C"]).isEqualTo("two words")
  }

  @Test
  fun `a bare LF instead of CRLF is accepted, because embedded renderers send it`() {
    // RFC 7230 recommends tolerating this on receipt. This parser never *emits* a bare LF -- see
    // `renderResponseHead`, which is asserted byte-for-byte below.
    val head = response("HTTP/1.1 200 OK\nContent-Length: 3\n\n")

    assertThat(head.code).isEqualTo(200)
    assertThat(head.headers.contentLength()).isEqualTo(3L)
  }

  @Test
  fun `a request line with the wrong number of parts is rejected`() {
    // Rejections, plural, and each with a distinguishable message. A single catch-all
    // `MalformedHttpException("bad request")` would make every one of these pass while telling a
    // debugger nothing.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("GET\r\n\r\n") }
      .withMessageContaining("request line")
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("GET /a\r\n\r\n") }
      .withMessageContaining("request line")
  }

  @Test
  fun `a status line whose code is not a number is rejected`() {
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 OK OK\r\n\r\n") }
      .withMessageContaining("status code")
  }

  @Test
  fun `a header with no colon is rejected`() {
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response("HTTP/1.1 200 OK\r\nnot a header\r\n\r\n") }
      .withMessageContaining("header")
  }

  @Test
  fun `an empty stream is rejected rather than parsed as an empty request`() {
    // A renderer that opens a connection and closes it without sending anything is ordinary. This
    // must be an exception, not a `HttpRequestHead("", "", "")` that the proxy then routes.
    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request("") }
      .withMessageContaining("closed")
  }

  @Test
  fun `an over-long line is rejected before it is buffered`() {
    val enormous = "GET /" + "a".repeat(HttpWire.MAX_LINE_BYTES) + " HTTP/1.1\r\n\r\n"

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { request(enormous) }
      .withMessageContaining("${HttpWire.MAX_LINE_BYTES}")
  }

  @Test
  fun `too many headers is rejected`() {
    val flood = buildString {
      append("HTTP/1.1 200 OK\r\n")
      repeat(HttpWire.MAX_HEADERS + 1) { append("X-$it: v\r\n") }
      append("\r\n")
    }

    assertThatExceptionOfType(MalformedHttpException::class.java)
      .isThrownBy { response(flood) }
      .withMessageContaining("${HttpWire.MAX_HEADERS}")
  }

  @Test
  fun `exactly the maximum number of headers is accepted`() {
    // The boundary from the other side. Without this, an off-by-one that rejected at MAX_HEADERS
    // would pass every other test in this class.
    val atLimit = buildString {
      append("HTTP/1.1 200 OK\r\n")
      repeat(HttpWire.MAX_HEADERS) { append("X-$it: v\r\n") }
      append("\r\n")
    }

    assertThat(response(atLimit).headers.size).isEqualTo(HttpWire.MAX_HEADERS)
  }

  @Test
  fun `a bare header block from a udp datagram parses without a start line`() {
    // SSDP's shape: `parseHeaderBlock` is given everything after the status line. This is the
    // third consumer that makes this codec worth owning -- no HTTP library will parse a datagram.
    val headers = HttpWire.parseHeaderBlock(
      "LOCATION: http://10.0.0.9:2869/desc.xml\r\nUSN: uuid:abc::urn:x\r\n",
    )

    assertThat(headers.names).containsExactly("LOCATION", "USN")
    assertThat(headers["usn"]).isEqualTo("uuid:abc::urn:x")
  }

  @Test
  fun `a rendered response head is byte-exact and always uses CRLF`() {
    val rendered = HttpWire.renderResponseHead(
      code = 206,
      reason = "Partial Content",
      headers = HttpHeaders.of(
        "Content-Type" to "audio/mpeg",
        "Content-Range" to "bytes 100-199/1000",
        "Content-Length" to "100",
      ),
    )

    // The whole thing, as a string, with the line endings visible. Anything less than a byte-exact
    // assertion here would pass with LF-only endings, which some renderers' HTTP clients drop.
    assertThat(String(rendered, Charsets.US_ASCII)).isEqualTo(
      "HTTP/1.1 206 Partial Content\r\n" +
        "Content-Type: audio/mpeg\r\n" +
        "Content-Range: bytes 100-199/1000\r\n" +
        "Content-Length: 100\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `a rendered response head carries the status it was given`() {
    // The second observation of the status line, so `renderResponseHead` cannot hardcode 206.
    assertThat(String(HttpWire.renderResponseHead(416, "Range Not Satisfiable", HttpHeaders.EMPTY)))
      .isEqualTo("HTTP/1.1 416 Range Not Satisfiable\r\n\r\n")
  }

  @Test
  fun `a rendered head round-trips through the parser`() {
    val rendered = HttpWire.renderResponseHead(
      404,
      "Not Found",
      HttpHeaders.of("Content-Length" to "0", "Connection" to "close"),
    )

    val reparsed = HttpWire.readResponseHead(ByteArrayInputStream(rendered))

    assertThat(reparsed.code).isEqualTo(404)
    assertThat(reparsed.reason).isEqualTo("Not Found")
    assertThat(reparsed.headers.names).containsExactly("Content-Length", "Connection")
  }
}
```

- [ ] **Step 8: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*HttpWireTest*'`
Expected: FAIL — `Unresolved reference: HttpWire`.

- [ ] **Step 9: Implement the codec**

`core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt`:

```kotlin
package app.muplay.cast.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Thrown when a peer sends something this codec will not treat as HTTP/1.1. */
class MalformedHttpException(message: String) : IOException(message)

/** A parsed request head: everything up to and including the blank line. */
data class HttpRequestHead(
  val method: String,
  val target: String,
  val version: String,
  val headers: HttpHeaders,
)

/** A parsed response head: everything up to and including the blank line. */
data class HttpResponseHead(
  val version: String,
  val code: Int,
  val reason: String,
  val headers: HttpHeaders,
)

/**
 * The HTTP/1.1 head codec this module owns, with three consumers:
 *
 * - the proxy server ([app.muplay.cast.proxy.MediaProxyServer]) reads **request** heads;
 * - the control client ([CastHttpClient]) reads **response** heads;
 * - SSDP parses a bare **header block** out of a UDP datagram, which no HTTP library can do.
 *
 * Reading is **tolerant** (a bare LF is accepted; a missing reason phrase is accepted), writing is
 * **strict** (always CRLF). That asymmetry is Postel's rule applied where it earns its keep: the
 * peers here are embedded devices whose HTTP is approximate, while the peer reading *our* output
 * is one of those devices and deserves nothing to guess about.
 *
 * The two size limits are not decoration. This parser reads from a device on the local network
 * that MuPlay did not write; without them, one line of `A`s exhausts the heap.
 */
object HttpWire {

  const val CRLF: String = "\r\n"

  /** The longest single line this codec will buffer. Generous for a `LOCATION`, fatal for a flood. */
  const val MAX_LINE_BYTES: Int = 8192

  /** The most headers this codec will accept in one block. */
  const val MAX_HEADERS: Int = 64

  fun readRequestHead(input: InputStream): HttpRequestHead {
    val startLine = readLine(input) ?: throw MalformedHttpException("connection closed before a request line arrived")
    // Split on the FIRST two spaces only: a target may not contain a space, but this keeps the
    // failure mode "reject" rather than "silently misparse".
    val parts = startLine.split(' ', limit = 3)
    if (parts.size != 3) {
      throw MalformedHttpException("malformed request line: \"$startLine\"")
    }
    return HttpRequestHead(parts[0], parts[1], parts[2], readHeaders(input))
  }

  fun readResponseHead(input: InputStream): HttpResponseHead {
    val startLine = readLine(input) ?: throw MalformedHttpException("connection closed before a status line arrived")
    val parts = startLine.split(' ', limit = 3)
    if (parts.size < 2) {
      throw MalformedHttpException("malformed status line: \"$startLine\"")
    }
    val code = parts[1].toIntOrNull()
      ?: throw MalformedHttpException("malformed status code \"${parts[1]}\" in \"$startLine\"")
    // A conformant server may send an empty reason phrase, in which case `parts` has two elements
    // and the trailing space has already been consumed by the split.
    val reason = if (parts.size == 3) parts[2].trim() else ""
    return HttpResponseHead(parts[0], code, reason, readHeaders(input))
  }

  /**
   * Parses a header block with no start line — the shape an SSDP reply arrives in once the
   * `HTTP/1.1 200 OK` has been taken off the front.
   */
  fun parseHeaderBlock(text: String): HttpHeaders =
    readHeaders(text.byteInputStream())

  fun renderResponseHead(code: Int, reason: String, headers: HttpHeaders): ByteArray {
    val text = buildString {
      append("HTTP/1.1 ").append(code).append(' ').append(reason).append(CRLF)
      headers.asList().forEach { (name, value) ->
        append(name).append(": ").append(value).append(CRLF)
      }
      append(CRLF)
    }
    return text.toByteArray(Charsets.US_ASCII)
  }

  private fun readHeaders(input: InputStream): HttpHeaders {
    val entries = ArrayList<Pair<String, String>>()
    while (true) {
      val line = readLine(input) ?: throw MalformedHttpException("connection closed inside a header block")
      if (line.isEmpty()) return HttpHeaders(entries)
      if (entries.size == MAX_HEADERS) {
        throw MalformedHttpException("more than $MAX_HEADERS headers in one block")
      }
      val colon = line.indexOf(':')
      if (colon <= 0) {
        throw MalformedHttpException("malformed header line: \"$line\"")
      }
      // The name keeps its case; the value loses only the optional whitespace around it, which is
      // what RFC 7230 says OWS is. Interior spaces are part of the value.
      entries += line.substring(0, colon) to line.substring(colon + 1).trim()
    }
  }

  /**
   * One line, terminated by CRLF or by a bare LF. Returns `null` at end of stream, and an empty
   * string for the blank line that ends a header block — a distinction the caller depends on.
   */
  private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream(128)
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
      if (byte == '\n'.code) {
        val bytes = buffer.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      if (buffer.size() == MAX_LINE_BYTES) {
        throw MalformedHttpException("a line exceeded $MAX_LINE_BYTES bytes without a terminator")
      }
      buffer.write(byte)
    }
  }
}
```

> **`String(bytes, 0, end, Charsets.US_ASCII)` and not UTF-8, deliberately.** HTTP header field
> values are ISO-8859-1/ASCII by RFC 7230; a `friendlyName` with a non-ASCII character travels in
> the **XML body** of the device description, which is parsed as UTF-8 by the XML parser in Task 2.
> Decoding headers as UTF-8 would be a silent, occasional corruption on exactly the byte sequences
> a European user's speaker name produces.

- [ ] **Step 10: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*HttpWireTest*'`
Expected: PASS, 16/16.

- [ ] **Step 11: Write the failing address-rule test**

`core/cast/src/test/kotlin/app/muplay/cast/net/LocalNetworkOnlyTest.kt`:

```kotlin
package app.muplay.cast.net

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The rule that replaces a manifest-wide cleartext switch.
 *
 * MuPlay talks plain HTTP to renderers because renderers have no TLS, and it must never talk plain
 * HTTP to anything else. `android:usesCleartextTraffic` cannot express that -- it is host-blind --
 * and a `network_security_config.xml` cannot express it either, because that format takes domain
 * names and IP literals and has no way to say "a subnet". This does say it, in one place.
 *
 * Every address below is asserted **in both directions**: an `isLocal` that returned `true`
 * unconditionally fails the second half of every test, and one that returned `false`
 * unconditionally fails the first. That symmetry is the whole point -- a guard observed only
 * succeeding is not a guard.
 */
class LocalNetworkOnlyTest {

  private fun local(literal: String) = LocalNetworkOnly.isLocal(InetAddress.getByName(literal))

  @Test
  fun `rfc 1918 private ipv4 is local, and the addresses just outside each block are not`() {
    // The boundaries are the whole test. An implementation that checked only the first octet
    // passes "10.0.0.1" and "172.16.0.1" and fails here.
    assertThat(local("10.0.0.1")).isTrue
    assertThat(local("10.255.255.255")).isTrue
    assertThat(local("9.255.255.255")).isFalse
    assertThat(local("11.0.0.0")).isFalse

    assertThat(local("172.16.0.1")).isTrue
    assertThat(local("172.31.255.255")).isTrue
    assertThat(local("172.15.255.255")).isFalse
    assertThat(local("172.32.0.0")).isFalse

    assertThat(local("192.168.0.1")).isTrue
    assertThat(local("192.168.255.255")).isTrue
    assertThat(local("192.167.255.255")).isFalse
    assertThat(local("192.169.0.0")).isFalse
  }

  @Test
  fun `loopback is local, because that is where the in-process renderer lives`() {
    // Spec section 10's Tier 1 row puts the fake renderer on 127.0.0.1:0. If this guard forbade
    // loopback, the entire cast test suite would be untestable -- and, worse, a reader would
    // "fix" it by relaxing the guard instead of by reading this comment.
    assertThat(local("127.0.0.1")).isTrue
    assertThat(local("127.255.255.254")).isTrue
    assertThat(local("::1")).isTrue
  }

  @Test
  fun `link-local is local, because that is what a renderer with no dhcp lease has`() {
    assertThat(local("169.254.0.1")).isTrue
    assertThat(local("169.254.255.255")).isTrue
    assertThat(local("169.253.255.255")).isFalse
    assertThat(local("fe80::1")).isTrue
  }

  /**
   * RFC 6598 carrier-grade NAT, `100.64.0.0/10`.
   *
   * `InetAddress.isSiteLocalAddress()` returns **false** for this whole block, and Tailscale hands
   * out addresses from exactly it. Spec section 6's third routing situation is "Remote + VPN", so
   * leaving this out would make a named user requirement fail on the most common way people build
   * the network the spec describes -- and fail as a refusal to connect, which reads as "the
   * speaker is not there".
   */
  @Test
  fun `carrier-grade nat is local, and the addresses either side of the block are not`() {
    assertThat(local("100.64.0.0")).isTrue
    assertThat(local("100.100.100.100")).isTrue
    assertThat(local("100.127.255.255")).isTrue
    assertThat(local("100.63.255.255")).isFalse
    assertThat(local("100.128.0.0")).isFalse
  }

  /**
   * IPv6 unique local addresses, `fc00::/7`. `isSiteLocalAddress()` covers only the deprecated
   * `fec0::/10`, so this needs its own check, and its own boundary observations.
   */
  @Test
  fun `ipv6 unique local addresses are local`() {
    assertThat(local("fd00::1")).isTrue
    assertThat(local("fc00::1")).isTrue
    assertThat(local("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).isTrue
    assertThat(local("fe00::1")).isFalse
  }

  @Test
  fun `a public address is not local`() {
    // Four of them, from four different registries, because "not local" is the assertion the whole
    // guard exists to make and one example is not a test.
    assertThat(local("8.8.8.8")).isFalse
    assertThat(local("93.184.216.34")).isFalse
    assertThat(local("1.1.1.1")).isFalse
    assertThat(local("2001:4860:4860::8888")).isFalse
  }

  @Test
  fun `require throws for a public address and names both the host and the address`() {
    // The message matters: this exception is what a user sees behind "could not reach that
    // speaker", and an exception that says only "refused" sends the next debugger to the wrong
    // layer entirely.
    assertThatExceptionOfType(NonLocalAddressException::class.java)
      .isThrownBy { LocalNetworkOnly.require("evil.example.com", InetAddress.getByName("93.184.216.34")) }
      .withMessageContaining("evil.example.com")
      .withMessageContaining("93.184.216.34")
      .withMessageContaining("local network")
  }

  @Test
  fun `require returns quietly for a private address`() {
    // The other direction. Without it, a `require` that threw unconditionally passes the test
    // above and breaks every cast.
    LocalNetworkOnly.require("192.168.1.50", InetAddress.getByName("192.168.1.50"))
    LocalNetworkOnly.require("localhost", InetAddress.getByName("127.0.0.1"))
  }
}
```

- [ ] **Step 12: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*LocalNetworkOnlyTest*'`
Expected: FAIL — `Unresolved reference: LocalNetworkOnly`.

- [ ] **Step 13: Implement the address rule and the source-address helper**

`core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt`:

```kotlin
package app.muplay.cast.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Thrown when a cast connection would leave the local network. */
class NonLocalAddressException(host: String, address: InetAddress) : IOException(
  "refusing to open a cleartext connection to $host ($address): MuPlay speaks plain HTTP only " +
    "to devices on the local network. See LocalNetworkOnly's documentation for why this rule " +
    "lives in code rather than in the manifest.",
)

/**
 * **The rule that lets MuPlay cast without weakening the app's cleartext posture.**
 *
 * Renderers have no TLS and never will. Talking to one means plain HTTP. The project's constraint
 * is that cleartext is debug-only and must never reach the release manifest, enforced by
 * `verifyReleaseManifest`. Those two facts collide, and the collision has exactly one honest
 * resolution.
 *
 * `android:usesCleartextTraffic` is **host-blind**: it is one boolean for the whole process, so
 * turning it on to reach a speaker also turns it on for Navidrome, which is the one host the
 * constraint exists to protect. A release `network_security_config.xml` cannot help either -- the
 * format takes `<domain>` entries (host names and IP literals) and has **no way to express a
 * subnet**, so "RFC 1918 only" is unwritable in it. Both mechanisms are therefore strictly weaker
 * than the rule the project wants.
 *
 * This is that rule, stated once, in code, with a test that observes it refusing as well as
 * permitting. The cast control client ([app.muplay.cast.http.CastHttpClient]) is a plain
 * `java.net.Socket`, so it never consults `NetworkSecurityPolicy` -- and that is not a loophole
 * being exploited but the point: the platform's switch cannot express the requirement, so the
 * requirement is enforced where it can be. Everything MuPlay sends to **Navidrome** still goes
 * through OkHttp, which does consult the policy, and the release manifest still permits it nothing
 * in cleartext.
 *
 * The proxy's listening socket needs no rule of this kind at all: `NetworkSecurityPolicy` governs
 * outbound connections made by the platform's HTTP stacks and has no mechanism to affect a
 * `ServerSocket`.
 *
 * Local means, exactly:
 *
 * | Range | Why |
 * |---|---|
 * | `127.0.0.0/8`, `::1` | the in-process renderer of the Tier 1 suite, and `adb reverse` |
 * | `10/8`, `172.16/12`, `192.168/16` | RFC 1918 -- an ordinary home or office LAN |
 * | `169.254/16`, `fe80::/10` | link-local: a renderer that never got a DHCP lease |
 * | `100.64/10` | RFC 6598 CGNAT -- **what Tailscale hands out**, and spec section 6's VPN row |
 * | `fc00::/7` | IPv6 unique local addresses |
 */
object LocalNetworkOnly {

  fun isLocal(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> isLocalIpv4(address)
    is Inet6Address -> address.isLoopbackAddress || address.isLinkLocalAddress || isUniqueLocalIpv6(address)
    else -> false
  }

  fun require(host: String, address: InetAddress) {
    if (!isLocal(address)) throw NonLocalAddressException(host, address)
  }

  private fun isLocalIpv4(address: Inet4Address): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
      return true
    }
    // RFC 6598, 100.64.0.0/10. `isSiteLocalAddress()` says false for the whole block, and this is
    // the block a routed VPN most often puts the phone in.
    val bytes = address.address
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return first == 100 && second in 64..127
  }

  /** `fc00::/7`: the top seven bits are `1111110`. `isSiteLocalAddress()` covers only `fec0::/10`. */
  private fun isUniqueLocalIpv6(address: Inet6Address): Boolean =
    (address.address[0].toInt() and 0xFE) == 0xFC
}
```

`core/cast/src/main/kotlin/app/muplay/cast/net/LocalAddress.kt`:

```kotlin
package app.muplay.cast.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The address of *this* machine that a given [peer] would see traffic arrive from.
 *
 * Needed because the proxy has to advertise a URL the renderer can actually fetch, and on a phone
 * "the local address" is not one thing: there is a Wi-Fi address, possibly a cellular one, and --
 * in spec section 6's third routing situation -- a VPN one. Handing a speaker on the home LAN the
 * phone's cellular address produces a cast that appears to start and plays nothing.
 *
 * The technique is to **ask the kernel**: `connect` an unbound UDP socket to the peer and read the
 * local address it chose. No packet is sent (a connected `DatagramSocket` only fixes the peer and
 * selects a route), and the answer is by construction the source address of the route to that
 * peer -- which is right for Wi-Fi, right for a VPN tunnel, and right for loopback.
 *
 * The alternative, enumerating `NetworkInterface`s and picking the first non-loopback IPv4
 * address, is what most implementations do and it is wrong on a multi-homed device: it picks by
 * enumeration order, which has nothing to do with which interface routes to the speaker.
 *
 * Returns `null` when no route exists, which is a real answer the router (Task 7) turns into a
 * named failure rather than an exception.
 */
object LocalAddress {

  /** The UDP port is irrelevant -- no datagram is sent -- but it must be a legal one. */
  private const val ROUTE_PROBE_PORT = 1900

  fun towards(peer: InetAddress): InetAddress? =
    runCatching {
      DatagramSocket().use { socket ->
        socket.connect(InetSocketAddress(peer, ROUTE_PROBE_PORT))
        socket.localAddress.takeUnless { it.isAnyLocalAddress }
      }
    }.getOrNull()
}
```

- [ ] **Step 14: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*LocalNetworkOnlyTest*'`
Expected: PASS, 8/8.

- [ ] **Step 15: Write the failing client test**

`core/cast/src/test/kotlin/app/muplay/cast/http/CastHttpClientTest.kt`:

```kotlin
package app.muplay.cast.http

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The socket HTTP/1.1 client, against a **real** `ServerSocket` on loopback that records the exact
 * bytes it was sent.
 *
 * Not a fake and not a stub: the subject is what goes out on the wire, and the only way to observe
 * that is to read it off a socket. The recording server accepts one connection, reads a head,
 * reads exactly `Content-Length` body bytes, and answers -- which is also the smallest possible
 * proof that this client frames a request the way an HTTP server expects.
 */
class CastHttpClientTest {

  private var server: RecordingServer? = null

  @AfterEach
  fun tearDown() {
    server?.close()
  }

  private fun start(response: ByteArray): RecordingServer =
    RecordingServer(response).also { server = it; it.start() }

  private val okResponse =
    ("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 7\r\n\r\n" + "<root/>")
      .toByteArray(Charsets.US_ASCII)

  @Test
  fun `a get request is framed with the request line, the host header and a blank line`() {
    val running = start(okResponse)

    CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/xml/device_description.xml"), "GET")

    // The exact head, byte for byte. `Host` is mandatory in HTTP/1.1 and includes the port when
    // it is not the default -- a renderer on 1400 that receives `Host: 10.0.0.5` may answer 400.
    assertThat(running.headText()).isEqualTo(
      "GET /xml/device_description.xml HTTP/1.1\r\n" +
        "Host: 127.0.0.1:${running.port}\r\n" +
        "Connection: close\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `the request line carries the path and query the caller asked for`() {
    // Two observations, so the target cannot be a constant.
    val first = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
    CastHttpClient().exchange(URI("http://127.0.0.1:${first.port}/MediaRenderer/AVTransport/Control"), "POST")
    assertThat(running(first).headText()).startsWith("POST /MediaRenderer/AVTransport/Control HTTP/1.1\r\n")
    first.close()

    val second = start("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
    CastHttpClient().exchange(URI("http://127.0.0.1:${second.port}/upnp/control/rendertransport1?x=1"), "POST")
    assertThat(running(second).headText()).startsWith("POST /upnp/control/rendertransport1?x=1 HTTP/1.1\r\n")
  }

  @Test
  fun `a request with a body sends content length and then exactly that many bytes`() {
    val running = start(okResponse)
    val body = "<s:Envelope/>".toByteArray(Charsets.UTF_8)

    CastHttpClient().exchange(
      URI("http://127.0.0.1:${running.port}/control"),
      method = "POST",
      headers = HttpHeaders.of("Content-Type" to "text/xml; charset=\"utf-8\""),
      body = body,
    )

    assertThat(running.headText()).isEqualTo(
      "POST /control HTTP/1.1\r\n" +
        "Host: 127.0.0.1:${running.port}\r\n" +
        "Connection: close\r\n" +
        "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "\r\n",
    )
    assertThat(running.body).isEqualTo(body)
  }

  @Test
  fun `caller headers are sent in the order given, after the client's own`() {
    // Order is a property here for a concrete reason: Task 3 asserts a SOAP request's whole head
    // byte-for-byte, and that assertion is only writable if this order is deterministic.
    val running = start(okResponse)

    CastHttpClient().exchange(
      URI("http://127.0.0.1:${running.port}/control"),
      method = "POST",
      headers = HttpHeaders.of("SOAPACTION" to "\"urn:x#Y\"", "X-Second" to "2"),
      body = ByteArray(0),
    )

    val lines = running.headText().split("\r\n")
    assertThat(lines.filter { it.contains(':') }).containsExactly(
      "Host: 127.0.0.1:${running.port}",
      "Connection: close",
      "SOAPACTION: \"urn:x#Y\"",
      "X-Second: 2",
      "Content-Length: 0",
    )
  }

  @Test
  fun `the response head and body both come back`() {
    val running = start(okResponse)

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.code).isEqualTo(200)
    assertThat(response.head.headers["content-type"]).isEqualTo("text/xml")
    assertThat(response.bodyText()).isEqualTo("<root/>")
  }

  @Test
  fun `a 500 with a body is returned rather than thrown, because a upnp fault is a 500 with a body`() {
    // The single most important behaviour of this client. Every UPnP error arrives as HTTP 500
    // carrying a SOAP Fault, and a client that threw on 5xx would turn "Sonos said 714, illegal
    // MIME type" into "the network failed" -- the exact loss of information this project treats as
    // the worst failure class.
    val fault = "<s:Fault/>"
    val running = start(
      ("HTTP/1.1 500 Internal Server Error\r\nContent-Length: ${fault.length}\r\n\r\n$fault")
        .toByteArray(Charsets.US_ASCII),
    )

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/control"), "POST", body = ByteArray(0))

    assertThat(response.code).isEqualTo(500)
    assertThat(response.bodyText()).isEqualTo(fault)
  }

  @Test
  fun `a body sent without content length is read until the peer closes`() {
    // Legal in HTTP/1.1 with `Connection: close`, and some embedded renderers do it. Without this
    // branch the response body would come back empty and a device description would parse as
    // "no services".
    val running = start("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n<root/>".toByteArray(Charsets.US_ASCII))

    val response = CastHttpClient().exchange(URI("http://127.0.0.1:${running.port}/x"), "GET")

    assertThat(response.bodyText()).isEqualTo("<root/>")
  }

  @Test
  fun `a public address is refused before a socket is opened`() {
    // The guard, observed refusing. `example.com` is not contacted: `NonLocalAddressException` is
    // thrown after resolution and before `connect`, so this test needs no network and is not flaky.
    assertThatExceptionOfType(NonLocalAddressException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("http://93.184.216.34/x"), "GET") }
      .withMessageContaining("93.184.216.34")
  }

  @Test
  fun `an https url is refused, because a renderer has no tls and this client has no trust store`() {
    // Better a loud refusal than a silently-plaintext request to port 443, which is what a naive
    // "ignore the scheme" implementation produces.
    assertThatExceptionOfType(IllegalArgumentException::class.java)
      .isThrownBy { CastHttpClient().exchange(URI("https://192.168.1.50/x"), "GET") }
      .withMessageContaining("http")
  }

  private fun running(server: RecordingServer): RecordingServer = server

  /** A real HTTP server on loopback that records exactly what it was sent. */
  private class RecordingServer(private val response: ByteArray) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val received = CopyOnWriteArrayList<ByteArray>()
    private val done = CountDownLatch(1)
    var body: ByteArray = ByteArray(0)
      private set

    val port: Int get() = socket.localPort

    fun start() {
      thread(isDaemon = true, name = "recording-server") {
        runCatching {
          socket.accept().use { connection ->
            val input = connection.getInputStream()
            val head = readHeadBytes(input)
            received += head
            val length = HttpWire.parseHeaderBlock(
              String(head, Charsets.US_ASCII).substringAfter("\r\n"),
            ).contentLength() ?: 0L
            body = ByteArray(length.toInt()).also { if (it.isNotEmpty()) input.readNBytes(it, 0, it.size) }
            connection.getOutputStream().write(response)
            connection.getOutputStream().flush()
          }
        }
        done.countDown()
      }
    }

    fun headText(): String {
      done.await(5, TimeUnit.SECONDS)
      return String(received.first(), Charsets.US_ASCII)
    }

    override fun close() = socket.close()

    /** Reads up to and including the CRLFCRLF, returning the raw bytes so tests assert on them. */
    private fun readHeadBytes(input: java.io.InputStream): ByteArray {
      val out = java.io.ByteArrayOutputStream()
      var matched = 0
      val terminator = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
      while (matched < terminator.size) {
        val b = input.read()
        if (b == -1) break
        out.write(b)
        matched = if (b == terminator[matched].toInt()) matched + 1 else if (b == '\r'.code) 1 else 0
      }
      return out.toByteArray()
    }
  }
}
```

- [ ] **Step 16: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*CastHttpClientTest*'`
Expected: FAIL — `Unresolved reference: CastHttpClient`.

- [ ] **Step 17: Implement `CastHttpClient`**

`core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt`:

```kotlin
package app.muplay.cast.http

import app.muplay.cast.net.LocalNetworkOnly
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** A response head and its whole body. */
data class CastHttpResponse(val head: HttpResponseHead, val body: ByteArray) {
  val code: Int get() = head.code
  fun bodyText(): String = String(body, Charsets.UTF_8)

  // `data class` over a ByteArray needs these; the generated ones compare identity.
  override fun equals(other: Any?): Boolean =
    this === other || (other is CastHttpResponse && head == other.head && body.contentEquals(other.body))

  override fun hashCode(): Int = 31 * head.hashCode() + body.contentHashCode()
}

/**
 * An HTTP/1.1 client for **renderer-facing traffic only**.
 *
 * A `java.net.Socket` and [HttpWire], not OkHttp, and the reason is documented at length on
 * [LocalNetworkOnly]: renderers have no TLS, OkHttp refuses cleartext under the release build's
 * network security policy, and the platform's cleartext switch is host-blind so it cannot express
 * the rule this project actually wants. That rule -- *plain HTTP to the local network, never to
 * the internet* -- is enforced here, on every request, before a socket is opened.
 *
 * Everything MuPlay sends to **Navidrome** still goes through OkHttp and is still held to the
 * platform policy. Do not use this class for anything but a device on the LAN.
 *
 * Deliberately minimal: one request per connection (`Connection: close`), no redirects, no
 * connection pool, no cookies, no chunked request bodies. Control URLs do not redirect, and a SOAP
 * exchange is a few hundred bytes.
 */
class CastHttpClient(
  private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
  private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

  fun exchange(
    url: URI,
    method: String,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    body: ByteArray? = null,
  ): CastHttpResponse {
    require(url.scheme.equals("http", ignoreCase = true)) {
      "CastHttpClient speaks http only, and was given \"$url\". A renderer has no TLS, and this " +
        "client has no trust store to give it one."
    }
    val host = requireNotNull(url.host) { "no host in \"$url\"" }
    val port = if (url.port == -1) DEFAULT_HTTP_PORT else url.port
    val address = InetAddress.getByName(host)
    LocalNetworkOnly.require(host, address)

    Socket().use { socket ->
      socket.soTimeout = readTimeoutMs
      socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
      socket.getOutputStream().apply {
        write(renderRequestHead(method, url, host, port, headers, body))
        if (body != null && body.isNotEmpty()) write(body)
        flush()
      }
      val input = socket.getInputStream()
      val head = HttpWire.readResponseHead(input)
      return CastHttpResponse(head, readBody(input, head.headers.contentLength()))
    }
  }

  /**
   * The request head, byte for byte.
   *
   * `Host` includes the port whenever it is not 80: a Sonos control endpoint lives on 1400, and a
   * `Host` without the port is a request some servers answer 400.
   *
   * `Content-Length` is written **last and unconditionally when there is a body**, including when
   * the body is empty -- `Content-Length: 0` on a POST is what tells a server not to wait for one.
   * Caller headers go in between, in the order given, because Task 3 asserts a whole SOAP head
   * byte-for-byte and that is only writable against a deterministic order.
   */
  private fun renderRequestHead(
    method: String,
    url: URI,
    host: String,
    port: Int,
    headers: HttpHeaders,
    body: ByteArray?,
  ): ByteArray {
    val target = buildString {
      append(url.rawPath?.ifEmpty { "/" } ?: "/")
      url.rawQuery?.let { append('?').append(it) }
    }
    val hostHeader = if (port == DEFAULT_HTTP_PORT) host else "$host:$port"
    return buildString {
      append(method).append(' ').append(target).append(" HTTP/1.1").append(HttpWire.CRLF)
      append("Host: ").append(hostHeader).append(HttpWire.CRLF)
      // One request per connection. A renderer's HTTP server is a small embedded thing and a
      // half-open keep-alive to it is a resource this app has no business holding.
      append("Connection: close").append(HttpWire.CRLF)
      headers.asList().forEach { (name, value) ->
        append(name).append(": ").append(value).append(HttpWire.CRLF)
      }
      if (body != null) append("Content-Length: ").append(body.size).append(HttpWire.CRLF)
      append(HttpWire.CRLF)
    }.toByteArray(Charsets.US_ASCII)
  }

  /**
   * The body. With a `Content-Length`, exactly that many bytes; without one, everything until the
   * peer closes -- which is legal under `Connection: close` and is what several embedded renderers
   * actually do.
   */
  private fun readBody(input: InputStream, contentLength: Long?): ByteArray =
    if (contentLength == null) input.readBytes() else input.readNBytes(contentLength.toInt())

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 4_000
    const val DEFAULT_READ_TIMEOUT_MS: Int = 8_000
    private const val DEFAULT_HTTP_PORT = 80
  }
}
```

- [ ] **Step 18: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*CastHttpClientTest*'`
Expected: PASS, 9/9.

- [ ] **Step 19: Prove each new assertion can fail**

One mutation at a time, restored after each, the failure message recorded in the task report:

1. In `HttpHeaders.all`, replace `equals(name, ignoreCase = true)` with `==`. Expect
   `a header is found whatever case the peer used` to fail. This is the defect that makes a Sonos
   invisible.
2. In `HttpWire.readHeaders`, replace `line.substring(colon + 1).trim()` with
   `line.substring(colon + 1)`. Expect
   `optional whitespace after the colon is trimmed and interior whitespace is not` to fail on its
   first observation and pass on `X-B`, which is the point of having both.
3. In `HttpWire.renderResponseHead`, replace `CRLF` with `"\n"`. Expect
   `a rendered response head is byte-exact and always uses CRLF` to fail. A test that parsed its
   own output would not catch this, because `readLine` tolerates a bare LF — which is exactly why
   that assertion is on the string and not on a round trip.
4. In `HttpWire.readResponseHead`, replace `parts[1].toIntOrNull()` with `200`. Expect
   `a 500 with a body is returned rather than thrown...` and
   `a status line is split into its three parts...` to fail.
5. In `LocalNetworkOnly.isLocalIpv4`, delete the RFC 6598 clause. Expect
   `carrier-grade nat is local...` to fail on its first three observations and pass on its last
   two. Then make the whole method `return true` and expect `a public address is not local` to
   fail — the branch discriminating in both directions.
6. In `CastHttpClient.exchange`, delete the `LocalNetworkOnly.require` line. Expect
   `a public address is refused before a socket is opened` to fail. **This is the mutation that
   matters most in this task**: without that line the app quietly becomes one that will send
   plaintext anywhere it is pointed.
7. In `CastHttpClient.renderRequestHead`, replace `hostHeader` with `host`. Expect
   `a get request is framed with the request line, the host header and a blank line` to fail — the
   port is the part a Sonos on 1400 needs.

- [ ] **Step 20: Record the probes**

`ci/mutation-probes.sh` — add entries for mutations 1, 3, 5 and 6 to the `PROBES` table, each
naming the single test that must go red. Two edits are needed beyond the table itself:

```python
CAST_HTTP = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt"
CAST_HEADERS = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt"
CAST_NET = "core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt"
CAST_CLIENT = "core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt"
```

and `run_suite()` must actually run this module — today it is
`./gradlew --quiet :core:network:test :core:model:test`, which would report every cast probe as
`MISSED` for the wrong reason. Add `:core:cast:test`, and add `core/cast` to `revert()`'s
`git checkout --` list. Read the script's header first; it is explicit that it is a regression
list, not a gate, and that a probe records an answer rather than generating a question.

> **Check this by breaking it deliberately.** Add the probes, then run
> `./ci/mutation-probes.sh cast` **before** widening `run_suite`. Every cast probe must report
> `MISSED`. That is rule 4 applied to the probe script itself: a probe list that runs no tests for
> a module reports "caught" for nothing and "missed" for nothing, and the only way to know which
> you have is to make it fail once on purpose.

- [ ] **Step 21: Measure the floor and commit**

```bash
./gradlew :core:cast:test jacocoTestReport
```

Read the measured BRANCH ratios for `app.muplay.cast.http.HttpHeaders`, `HttpWire` and
`app.muplay.cast.net.LocalNetworkOnly` out of
`core/cast/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml` and confirm each clears
0.90. If one does not, **add the missing case; do not lower the floor.** `LocalAddress` and
`CastHttpClient` are deliberately absent from the include list at this point: `LocalAddress.towards`
is a kernel query with one `runCatching` and no author-written branch worth a floor, and
`CastHttpClient` gains its branches in Task 6 — Task 11 completes the table.

```bash
./gradlew :core:cast:test :app:testDebugUnitTest --tests '*ConventionTest*'
./gradlew jacocoJvmCoverageVerification
git add settings.gradle.kts build.gradle.kts core/cast ci/mutation-probes.sh
git commit -m "feat(cast): the cast module, an HTTP/1.1 codec we own, and cleartext confined to the LAN"
```

---

## Task 2: SSDP discovery, the device description, and Sonos's embedded `MediaRenderer`

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpSearch.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpTransport.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/DeviceDescription.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/CastDevice.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/RendererDirectory.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/discovery/RememberedRenderers.kt`
- Create: `core/database/src/main/kotlin/app/muplay/database/RendererStore.kt`
- Modify: `core/database/build.gradle.kts`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/discovery/SsdpSearchTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/discovery/DeviceDescriptionTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/discovery/RendererDirectoryTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/fake/FakeSsdpResponder.kt`
- Test: `core/database/src/androidTest/kotlin/app/muplay/database/RendererStoreTest.kt`
- Modify: `build.gradle.kts` (`:core:cast` and `:core:database` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `HttpWire.parseHeaderBlock`, `HttpHeaders`, `CastHttpClient`, `LocalNetworkOnly`,
  `MalformedHttpException` (Task 1).
- Produces:
  - `object SsdpSearch` with
    `fun request(host: String, port: Int, searchTarget: String, mxSeconds: Int?): ByteArray`,
    `fun parseResponse(datagram: String, from: InetAddress): SsdpResponse?`,
    `const val MULTICAST_IPV4 = "239.255.255.250"`, `const val PORT = 1900`,
    `const val TARGET_MEDIA_RENDERER`, `const val TARGET_SONOS_ZONE_PLAYER`,
    `const val DEFAULT_MX_SECONDS = 2`, `val MULTICAST_ENDPOINT: InetSocketAddress`
  - `data class SsdpResponse(val location: URI, val searchTarget: String, val usn: String, val server: String?, val from: InetAddress)`
    with `val udn: String`
  - `interface SsdpTransport { suspend fun search(destination: InetSocketAddress, targets: List<String>, mxSeconds: Int?, listenWindowMs: Long): List<SsdpResponse> }`
  - `class DatagramSsdpTransport : SsdpTransport` with
    `companion object { fun multicastDestinations(): List<InetSocketAddress> }`
  - `data class UpnpService(val serviceType: String, val serviceId: String, val controlUrl: URI, val scpdUrl: URI?)`
  - `data class UpnpDevice(val deviceType: String, val udn: String, val friendlyName: String, val manufacturer: String?, val modelName: String?, val services: List<UpnpService>, val embedded: List<UpnpDevice>)`
    with `fun flatten(): List<UpnpDevice>` and `fun service(serviceType: String): UpnpService?`
  - `object DeviceDescription` with
    `fun parse(xml: String, descriptionUrl: URI): UpnpDevice`,
    `const val MAX_DESCRIPTION_BYTES = 512 * 1024`,
    `const val SERVICE_AV_TRANSPORT`, `const val SERVICE_RENDERING_CONTROL`,
    `const val DEVICE_MEDIA_RENDERER`
  - `class MalformedDescriptionException(message: String) : IOException`
  - `data class CastDevice(...)` with `val isSonos: Boolean` and `companion object { fun from(root: UpnpDevice, descriptionUrl: URI): CastDevice? }`
  - `data class RememberedRenderer(val udn: String, val friendlyName: String, val descriptionUrl: String)`
  - `interface RememberedRenderers` with `suspend fun load(): List<RememberedRenderer>`,
    `suspend fun remember(devices: List<CastDevice>)`, `suspend fun forget(udn: String)`,
    `companion object { const val MAX_REMEMBERED = 16 }`
  - `class RendererDirectory(transport, http, remembered, clock)` with
    `suspend fun discover(mxSeconds: Int = SsdpSearch.DEFAULT_MX_SECONDS): DiscoveryResult`
  - `data class DiscoveryResult(val devices: List<CastDevice>, val unreachable: List<RememberedRenderer>)`
  - `class RendererStore @Inject constructor(dataStore)` in `:core:database`, implementing `RememberedRenderers`
- **Plan 4 interaction:** none. Discovery does not know what is playing.

### What a discovery test with one device cannot prove

Four things, and each of them is a real defect this project would otherwise ship:

1. **Filtering.** A Sonos household answers an `ssdp:all`-shaped search with a `ZonePlayer`, a
   `MediaRenderer`, a `MediaServer` and an `AlarmClock` service, from the same IP. Only one of
   those is castable. A single-device test cannot show the other three being dropped.
2. **Deduplication.** A device answers **once per search target it matches**, so searching for both
   `MediaRenderer:1` and `ZonePlayer:1` returns a Sonos twice with two different `ST` values and
   the same `LOCATION`. A picker showing "Kitchen" twice is a visible bug that one device cannot
   produce.
3. **Ordering.** Datagram arrival order is a property of the network, not of the app. A picker
   whose entries shuffle between openings is unusable, and order can only be observed with more
   than one entry.
4. **The `MediaServer` exclusion.** Sonos advertises a `MediaServer` device with a
   `ContentDirectory` and **no `AVTransport`**. Casting to it is impossible. A directory that
   returned it would put an entry in the picker that fails at `SetAVTransportURI` with error 401,
   long after the user chose it.

So every test in this task runs **at least three responders**, and in every one of them **at least
one responder must not appear in the result**.

### Sonos's device description is not shaped like the examples

The UPnP examples all show a root device that *is* the `MediaRenderer`. Sonos is not. Its root
device at `http://<ip>:1400/xml/device_description.xml` is:

```
device  deviceType = urn:schemas-upnp-org:device:ZonePlayer:1
  serviceList     -> AlarmClock, MusicServices, DeviceProperties, SystemProperties, ZoneGroupTopology, GroupManagement, QPlay
  deviceList
    device  deviceType = urn:schemas-upnp-org:device:MediaServer:1
      serviceList   -> ContentDirectory, ConnectionManager
    device  deviceType = urn:schemas-upnp-org:device:MediaRenderer:1
      serviceList   -> RenderingControl, ConnectionManager, AVTransport, Queue, GroupRenderingControl
```

A parser that reads `root/device/serviceList` and stops finds **no `AVTransport`** and concludes
that a Sonos speaker is not a renderer. That is the single most likely way this task fails, and it
fails as *"Sonos does not appear in the picker"* — the named user requirement, absent, with nothing
logged. `UpnpDevice.flatten()` and the `deviceList` recursion exist for exactly this, and
`DeviceDescriptionTest` asserts it against a Sonos-shaped document, not a textbook one.

The `controlURL` in that embedded device is `/MediaRenderer/AVTransport/Control` — **relative**, and
must be resolved against the description URL (or `<URLBase>` if the device sends one). Resolving it
wrong produces a `POST` to `/MediaRenderer/AVTransport/Control` on the wrong host, or a 404 that
looks like an offline speaker.

> **`<URLBase>` is deprecated in UPnP 1.1 and still emitted by real devices.** When present it wins
> over the description URL; when absent the description URL is the base. Both branches are
> observed, because "present" and "absent" are two different behaviours and one of them is what
> ships.

### Why no `CHANGE_WIFI_MULTICAST_STATE` and no `MulticastLock`

The reflex is to acquire a `WifiManager.MulticastLock` before any SSDP, which needs the
`CHANGE_WIFI_MULTICAST_STATE` permission. It is not needed here, and adding a permission that does
nothing is not free — it is visible on the store listing and it invites a reader to assume the code
depends on it.

The lock exists so that Wi-Fi hardware does not filter **incoming** multicast and broadcast frames.
MuPlay's discovery **sends** multicast (outbound, unaffected) and receives **unicast** replies:
UPnP requires an M-SEARCH response to be sent by unicast UDP to the source address and port of the
request. Nothing MuPlay listens for arrives as multicast.

The thing MuPlay gives up by not holding the lock is the ability to receive unsolicited
`NOTIFY ssdp:alive` / `ssdp:byebye` announcements on the multicast group — that is, learning about
a speaker without asking. This plan does not use them: the picker searches when it opens. Say so in
`SsdpTransport`'s KDoc, so the day someone wants passive discovery they find the reason rather than
rediscovering the permission.

### What CI can and cannot prove about discovery, stated before it is claimed

There is no UPnP renderer on the CI network and there is no multicast domain shared with one. So
**multicast delivery itself is not provable in either tier**, and any test claiming otherwise would
be reporting the absence of a problem it never looked for.

The technique is to **shrink the unproven delta to something a reader can hold in their head**:

- The **unicast** path is proved end to end, against a real `DatagramSocket` and a real responder
  on loopback — and it is not a test-only path, it is spec §12's required VPN fallback.
- The **payload** is asserted byte-for-byte, and separately asserted to be **identical** for a
  multicast and a unicast destination apart from the `HOST` line and the presence of `MX`.
- The **destination** is asserted to be `239.255.255.250:1900`, as a constant.

What is left unproven is then exactly: *does a datagram sent to that address reach a speaker on
this Wi-Fi network*. That is a property of the network, not of this code, and Task 11's definition
of done names it as such rather than pretending a green build settled it.

- [ ] **Step 1: Write the failing M-SEARCH payload test**

`core/cast/src/test/kotlin/app/muplay/cast/discovery/SsdpSearchTest.kt`:

```kotlin
package app.muplay.cast.discovery

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The M-SEARCH datagram, byte for byte, and the reply parser.
 *
 * Byte-exactness is not pedantry here. `MAN: "ssdp:discover"` is quoted **in the protocol**, and a
 * device that receives it unquoted is within its rights to ignore the search entirely -- which
 * manifests as "no speakers found", with no error anywhere, on somebody else's network.
 */
class SsdpSearchTest {

  @Test
  fun `a multicast search is the exact datagram the protocol specifies`() {
    val datagram = String(
      SsdpSearch.request(
        host = SsdpSearch.MULTICAST_IPV4,
        port = SsdpSearch.PORT,
        searchTarget = SsdpSearch.TARGET_MEDIA_RENDERER,
        mxSeconds = 2,
      ),
      Charsets.US_ASCII,
    )

    // Every line, every CRLF, and the terminating blank line. An assertion on `contains("MAN")`
    // would pass with the quotes missing, which is the one thing this test exists to catch.
    assertThat(datagram).isEqualTo(
      "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )
  }

  @Test
  fun `the search target is the one the caller asked for`() {
    // Two observations. A hardcoded ST passes the test above and fails here.
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_SONOS_ZONE_PLAYER, 2)))
      .contains("ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n")
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 2)))
      .contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
  }

  @Test
  fun `mx is the value the caller asked for`() {
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 1)))
      .contains("MX: 1\r\n")
    assertThat(String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 5)))
      .contains("MX: 5\r\n")
  }

  /**
   * A **unicast** M-SEARCH (UPnP Device Architecture 1.1, section 1.3.3) omits `MX`: `MX` exists so
   * that many devices spread their replies over a window and do not storm the multicast group, and
   * there is one recipient here. Devices differ in how they treat an `MX` they did not expect, and
   * this is the fallback path spec section 12 calls *required, not optional*, so it is sent exactly
   * as specified.
   */
  @Test
  fun `a unicast search names the unicast host and omits mx entirely`() {
    val datagram = String(
      SsdpSearch.request("192.168.1.50", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, mxSeconds = null),
    )

    assertThat(datagram).isEqualTo(
      "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 192.168.1.50:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
        "\r\n",
    )
    assertThat(datagram).doesNotContain("MX")
  }

  @Test
  fun `the host line carries the destination it was given, port included`() {
    // Two hosts and two ports, because "HOST" hardcoded to the multicast group passes every other
    // assertion in this class and breaks the entire VPN fallback.
    assertThat(String(SsdpSearch.request("10.0.0.9", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, null)))
      .contains("HOST: 10.0.0.9:1900\r\n")
    assertThat(String(SsdpSearch.request("127.0.0.1", 41234, SsdpSearch.TARGET_MEDIA_RENDERER, null)))
      .contains("HOST: 127.0.0.1:41234\r\n")
  }

  @Test
  fun `the multicast endpoint is the one the protocol reserves`() {
    // Pinned as a constant, because it is the one part of the multicast path CI cannot exercise
    // and therefore the one part most worth stating as an assertion rather than a literal.
    assertThat(SsdpSearch.MULTICAST_IPV4).isEqualTo("239.255.255.250")
    assertThat(SsdpSearch.PORT).isEqualTo(1900)
    assertThat(SsdpSearch.MULTICAST_ENDPOINT.address.hostAddress).isEqualTo("239.255.255.250")
    assertThat(SsdpSearch.MULTICAST_ENDPOINT.port).isEqualTo(1900)
  }

  /**
   * The delta between the path CI proves and the path a real network takes, made small enough to
   * read. Everything except `HOST` and `MX` must be identical.
   */
  @Test
  fun `a multicast and a unicast search differ only in the host line and mx`() {
    val multicast = String(SsdpSearch.request("239.255.255.250", 1900, SsdpSearch.TARGET_MEDIA_RENDERER, 2))
    val unicast = String(SsdpSearch.request("127.0.0.1", 45000, SsdpSearch.TARGET_MEDIA_RENDERER, null))

    val stripped = { text: String ->
      text.split("\r\n").filterNot { it.startsWith("HOST:") || it.startsWith("MX:") }
    }
    assertThat(stripped(unicast)).isEqualTo(stripped(multicast))
    assertThat(stripped(multicast)).containsExactly(
      "M-SEARCH * HTTP/1.1",
      "MAN: \"ssdp:discover\"",
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
      "",
      "",
    )
  }

  @Test
  fun `a reply is parsed into its location, target, usn and server`() {
    val reply = "HTTP/1.1 200 OK\r\n" +
      "CACHE-CONTROL: max-age=1800\r\n" +
      "EXT:\r\n" +
      "LOCATION: http://192.168.1.50:1400/xml/device_description.xml\r\n" +
      "SERVER: Linux UPnP/1.0 Sonos/84.1-52250\r\n" +
      "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "USN: uuid:RINCON_5CAAFD0A1F4A01400::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    val parsed = SsdpSearch.parseResponse(reply, InetAddress.getByName("192.168.1.50"))!!

    // Every field, individually. `isNotNull` on the response would pass with four of them empty.
    assertThat(parsed.location.toString()).isEqualTo("http://192.168.1.50:1400/xml/device_description.xml")
    assertThat(parsed.searchTarget).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.usn)
      .isEqualTo("uuid:RINCON_5CAAFD0A1F4A01400::urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(parsed.server).isEqualTo("Linux UPnP/1.0 Sonos/84.1-52250")
    assertThat(parsed.from.hostAddress).isEqualTo("192.168.1.50")
  }

  @Test
  fun `the udn is the uuid half of the usn, which is what deduplicates a device`() {
    // The whole point of extracting it. Two replies from one Sonos differ in `ST` and in the
    // suffix of `USN`, and agree on this.
    val renderer = SsdpSearch.parseResponse(
      reply(usn = "uuid:RINCON_ABC01400::urn:schemas-upnp-org:device:MediaRenderer:1"),
      InetAddress.getLoopbackAddress(),
    )!!
    val zonePlayer = SsdpSearch.parseResponse(
      reply(usn = "uuid:RINCON_ABC01400::urn:schemas-upnp-org:device:ZonePlayer:1"),
      InetAddress.getLoopbackAddress(),
    )!!

    assertThat(renderer.udn).isEqualTo("uuid:RINCON_ABC01400")
    assertThat(zonePlayer.udn).isEqualTo("uuid:RINCON_ABC01400")
    assertThat(renderer.udn).isEqualTo(zonePlayer.udn)
  }

  @Test
  fun `a usn with no service suffix is its own udn`() {
    // A root-device announcement carries `USN: uuid:x` with nothing after it. Splitting on "::"
    // and taking index 1 would throw here; taking `substringBefore` is why this passes.
    val parsed = SsdpSearch.parseResponse(reply(usn = "uuid:plain-device"), InetAddress.getLoopbackAddress())!!

    assertThat(parsed.udn).isEqualTo("uuid:plain-device")
  }

  @Test
  fun `a reply with no location is discarded rather than half-parsed`() {
    // Without a LOCATION there is nothing to fetch, so there is no device. Returning a
    // half-populated object here would push the failure into the description fetcher, where the
    // message would be about a URL rather than about a malformed announcement.
    val noLocation = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
      "USN: uuid:x\r\n\r\n"

    assertThat(SsdpSearch.parseResponse(noLocation, InetAddress.getLoopbackAddress())).isNull()
  }

  @Test
  fun `a reply whose location is not a local address is discarded`() {
    // A hostile or misconfigured device on the LAN can announce any LOCATION it likes, including
    // one on the public internet, and this client would then fetch it in cleartext. The address
    // rule from Task 1 applies to announcements as well as to connections.
    val remote = reply(location = "http://93.184.216.34/desc.xml")

    assertThat(SsdpSearch.parseResponse(remote, InetAddress.getLoopbackAddress())).isNull()
  }

  @Test
  fun `a non-200 reply is discarded`() {
    assertThat(
      SsdpSearch.parseResponse(
        "HTTP/1.1 404 Not Found\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\nUSN: uuid:x\r\n" +
          "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        InetAddress.getLoopbackAddress(),
      ),
    ).isNull()
  }

  @Test
  fun `a notify datagram is discarded, because this transport only asked a question`() {
    // Unsolicited `NOTIFY * HTTP/1.1` announcements arrive on the multicast group. This client
    // does not subscribe to them (see SsdpTransport's KDoc on MulticastLock) and must not
    // misparse one that leaks through as an answer to its own search.
    val notify = "NOTIFY * HTTP/1.1\r\nLOCATION: http://127.0.0.1:1400/d.xml\r\nUSN: uuid:x\r\n" +
      "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    assertThat(SsdpSearch.parseResponse(notify, InetAddress.getLoopbackAddress())).isNull()
  }

  private fun reply(
    location: String = "http://127.0.0.1:1400/xml/device_description.xml",
    usn: String = "uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1",
  ) = "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nUSN: $usn\r\n" +
    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nEXT:\r\n\r\n"
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*SsdpSearchTest*'`
Expected: FAIL — `Unresolved reference: SsdpSearch`.

- [ ] **Step 3: Implement `SsdpSearch` and the transport**

`core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpSearch.kt`:

```kotlin
package app.muplay.cast.discovery

import app.muplay.cast.http.HttpWire
import app.muplay.cast.net.LocalNetworkOnly
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** One device's answer to one M-SEARCH. */
data class SsdpResponse(
  val location: URI,
  val searchTarget: String,
  val usn: String,
  val server: String?,
  val from: InetAddress,
) {
  /**
   * The device's unique identity, which is the `uuid:` half of the USN.
   *
   * A device answers **once per search target it matches**, so one Sonos answers a search for both
   * `MediaRenderer:1` and `ZonePlayer:1` twice, with two `ST` values, two `USN` values and one
   * `LOCATION`. This is what collapses those into one picker entry.
   */
  val udn: String get() = usn.substringBefore("::")
}

/**
 * The SSDP M-SEARCH datagram and its reply.
 *
 * SSDP is HTTP's syntax over UDP: the request is an HTTP request line plus headers, and the reply
 * is an HTTP status line plus headers, in a datagram. That is why [app.muplay.cast.http.HttpWire]
 * exists as a codec rather than as a server -- no HTTP library will parse a datagram, so this is
 * the third consumer that made writing it worthwhile.
 */
object SsdpSearch {

  const val MULTICAST_IPV4: String = "239.255.255.250"
  const val PORT: Int = 1900

  /** The generic DLNA renderer. Sonos answers this too. */
  const val TARGET_MEDIA_RENDERER: String = "urn:schemas-upnp-org:device:MediaRenderer:1"

  /**
   * Sonos's own root device type. Searched **in addition** because some firmware answers a
   * `ZonePlayer` search more reliably than a `MediaRenderer` one, and because the answer identifies
   * the device as a Sonos before its description has been fetched.
   */
  const val TARGET_SONOS_ZONE_PLAYER: String = "urn:schemas-upnp-org:device:ZonePlayer:1"

  /**
   * Devices wait a random interval up to `MX` seconds before replying, to keep a large household
   * from storming the group. 2 s keeps the picker responsive; 1 s risks losing the slowest device
   * on a busy network, and 5 s is a five-second empty list.
   */
  const val DEFAULT_MX_SECONDS: Int = 2

  val MULTICAST_ENDPOINT: InetSocketAddress =
    InetSocketAddress(InetAddress.getByName(MULTICAST_IPV4), PORT)

  /**
   * One M-SEARCH datagram.
   *
   * @param mxSeconds the response-spreading window for a **multicast** search, or `null` for a
   *   **unicast** one. UPnP Device Architecture 1.1 section 1.3.3 defines the unicast form without
   *   `MX`, since spreading replies is meaningless with one recipient.
   *
   * `MAN: "ssdp:discover"` carries its quotes because the protocol specifies them. Sent unquoted,
   * a conformant device is entitled to ignore the search, and the symptom is an empty picker with
   * nothing logged anywhere.
   */
  fun request(host: String, port: Int, searchTarget: String, mxSeconds: Int?): ByteArray =
    buildString {
      append("M-SEARCH * HTTP/1.1").append(HttpWire.CRLF)
      append("HOST: ").append(host).append(':').append(port).append(HttpWire.CRLF)
      append("MAN: \"ssdp:discover\"").append(HttpWire.CRLF)
      if (mxSeconds != null) append("MX: ").append(mxSeconds).append(HttpWire.CRLF)
      append("ST: ").append(searchTarget).append(HttpWire.CRLF)
      append(HttpWire.CRLF)
    }.toByteArray(Charsets.US_ASCII)

  /**
   * One reply, or `null` when the datagram is not a usable answer to our own search.
   *
   * Four things are dropped, each for its own reason:
   *
   * - anything that is not `HTTP/1.1 200` -- including an unsolicited `NOTIFY`, which shares the
   *   group and would otherwise be misread as an answer;
   * - a reply with no `LOCATION` or no `USN`, which names nothing to fetch and nothing to identify;
   * - a `LOCATION` this client cannot parse as a URI;
   * - a `LOCATION` pointing off the local network. A device on the LAN can announce any URL it
   *   likes; the rule from Task 1 applies to what a device *claims* as well as to what this app
   *   dials.
   */
  fun parseResponse(datagram: String, from: InetAddress): SsdpResponse? {
    val startLine = datagram.substringBefore("\r\n").substringBefore("\n")
    if (!startLine.startsWith("HTTP/1.")) return null
    if (startLine.split(' ').getOrNull(1) != "200") return null

    val headers = runCatching {
      HttpWire.parseHeaderBlock(datagram.substringAfter("\n"))
    }.getOrNull() ?: return null

    val location = headers["LOCATION"] ?: return null
    val usn = headers["USN"] ?: return null
    val searchTarget = headers["ST"] ?: return null
    val uri = runCatching { URI(location) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    if (!LocalNetworkOnly.isLocal(address)) return null

    return SsdpResponse(uri, searchTarget, usn, headers["SERVER"], from)
  }
}
```

`core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpTransport.kt`:

```kotlin
package app.muplay.cast.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends M-SEARCH datagrams and collects the replies.
 *
 * A `fun`-shaped seam so that [RendererDirectory] can be tested against a transport that answers
 * from a list, while [DatagramSsdpTransport] is exercised for real against a loopback responder.
 *
 * **No `MulticastLock` and no `CHANGE_WIFI_MULTICAST_STATE`, deliberately.** That lock stops Wi-Fi
 * hardware filtering *incoming* multicast frames. MuPlay sends multicast (outbound, unaffected)
 * and receives **unicast** replies -- UPnP requires an M-SEARCH response to be sent by unicast UDP
 * back to the request's source address and port. What the lock would additionally buy is
 * unsolicited `NOTIFY ssdp:alive`/`ssdp:byebye` announcements on the group, i.e. passive
 * discovery. This plan searches when the picker opens and does not use them. Adding a
 * `dangerous`-adjacent permission the code does not depend on is a cost with no benefit, and it
 * teaches the next reader that the code depends on it.
 */
interface SsdpTransport {
  suspend fun search(
    destination: InetSocketAddress,
    targets: List<String>,
    mxSeconds: Int?,
    listenWindowMs: Long,
  ): List<SsdpResponse>
}

/**
 * The real transport: one ephemeral UDP socket, one datagram per search target, then read until
 * the window closes.
 *
 * One socket for every target, not one each, because replies come back to the socket's source
 * port and a second socket would need a second listen window -- doubling the time the picker
 * spends empty for no extra information.
 */
class DatagramSsdpTransport : SsdpTransport {

  override suspend fun search(
    destination: InetSocketAddress,
    targets: List<String>,
    mxSeconds: Int?,
    listenWindowMs: Long,
  ): List<SsdpResponse> = withContext(Dispatchers.IO) {
    val responses = ArrayList<SsdpResponse>()
    DatagramSocket().use { socket ->
      socket.reuseAddress = true
      socket.soTimeout = SOCKET_POLL_MS
      targets.forEach { target ->
        val payload = SsdpSearch.request(
          host = destination.address.hostAddress,
          port = destination.port,
          searchTarget = target,
          mxSeconds = mxSeconds,
        )
        runCatching { socket.send(DatagramPacket(payload, payload.size, destination)) }
      }

      val deadline = System.nanoTime() + listenWindowMs * NANOS_PER_MILLI
      val buffer = ByteArray(MAX_DATAGRAM_BYTES)
      while (System.nanoTime() < deadline) {
        val packet = DatagramPacket(buffer, buffer.size)
        val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
        if (!received) continue
        val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
        SsdpSearch.parseResponse(text, packet.address)?.let(responses::add)
      }
    }
    responses
  }

  companion object {
    /** SSDP replies are a few hundred bytes; 4 KiB is generous and bounds the read. */
    private const val MAX_DATAGRAM_BYTES = 4096

    /** Short enough that the listen window is honoured to within a fifth of a second. */
    private const val SOCKET_POLL_MS = 200

    private const val NANOS_PER_MILLI = 1_000_000L

    /**
     * One multicast endpoint per interface that could plausibly carry it.
     *
     * A phone is multi-homed: Wi-Fi, possibly cellular, possibly a VPN. Sending from a single
     * unbound socket lets the kernel pick by the multicast route, which on Android is not reliably
     * the interface the speaker is on. Sending once per usable interface costs three datagrams and
     * removes the guess.
     *
     * Loopback is excluded here and supplied explicitly by the tests, so that a device on the
     * machine's own loopback never appears in a real user's picker.
     */
    fun multicastDestinations(): List<InetSocketAddress> =
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .map { SsdpSearch.MULTICAST_ENDPOINT }
        .ifEmpty { listOf(SsdpSearch.MULTICAST_ENDPOINT) }
  }
}
```

> **`multicastDestinations()` returning the same endpoint N times is intentional and looks wrong.**
> The datagram is addressed to the group; what differs between sends is the *source interface* the
> kernel picks, which a `DatagramSocket` chooses per send. This is the one place in this module
> where the honest implementation is "send it more than once and let the network sort it out", and
> it is worth a comment in the source saying so, because the obvious "simplification" is to
> `distinct()` it and lose the multi-homing behaviour. If a later plan needs a per-interface bind,
> the shape to reach for is `MulticastSocket.setNetworkInterface`, and it should arrive with a test
> that can observe the difference — which this repository's CI cannot.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*SsdpSearchTest*'`
Expected: PASS, 13/13.

- [ ] **Step 5: Write the failing device-description tests**

`core/cast/src/test/kotlin/app/muplay/cast/discovery/DeviceDescriptionTest.kt`:

```kotlin
package app.muplay.cast.discovery

import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The UPnP device description parser.
 *
 * Two documents, deliberately: a **generic** renderer whose root device is the `MediaRenderer`, and
 * a **Sonos-shaped** one whose root is a `ZonePlayer` with the `MediaRenderer` and a `MediaServer`
 * nested inside `deviceList`. A parser written against the first alone reports that a Sonos speaker
 * has no `AVTransport` -- which is "Sonos does not appear in the picker", the named user
 * requirement, silently absent.
 */
class DeviceDescriptionTest {

  private val genericUrl = URI("http://192.168.1.77:2869/upnp/desc.xml")
  private val sonosUrl = URI("http://192.168.1.50:1400/xml/device_description.xml")

  private val generic = """
    <?xml version="1.0"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <specVersion><major>1</major><minor>0</minor></specVersion>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>Study Amp</friendlyName>
        <manufacturer>Yamaha Corporation</manufacturer>
        <modelName>WXA-50</modelName>
        <UDN>uuid:9ab0c000-f668-11de-9976-00a0ded1e211</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
            <SCPDURL>/RenderingControl/desc.xml</SCPDURL>
            <controlURL>/RenderingControl/ctrl</controlURL>
            <eventSubURL>/RenderingControl/evt</eventSubURL>
          </service>
          <service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <SCPDURL>/AVTransport/desc.xml</SCPDURL>
            <controlURL>/AVTransport/ctrl</controlURL>
            <eventSubURL>/AVTransport/evt</eventSubURL>
          </service>
        </serviceList>
      </device>
    </root>
  """.trimIndent()

  private val sonos = """
    <?xml version="1.0" encoding="utf-8"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <specVersion><major>1</major><minor>0</minor></specVersion>
      <device>
        <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
        <friendlyName>192.168.1.50 - Sonos One</friendlyName>
        <manufacturer>Sonos, Inc.</manufacturer>
        <modelName>Sonos One</modelName>
        <roomName>K&#252;che</roomName>
        <UDN>uuid:RINCON_5CAAFD0A1F4A01400</UDN>
        <serviceList>
          <service>
            <serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:ZoneGroupTopology</serviceId>
            <controlURL>/ZoneGroupTopology/Control</controlURL>
            <SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>
          </service>
        </serviceList>
        <deviceList>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
            <friendlyName>192.168.1.50 - Sonos One Media Server</friendlyName>
            <UDN>uuid:RINCON_5CAAFD0A1F4A01400_MS</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
                <SCPDURL>/xml/ContentDirectory1.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>192.168.1.50 - Sonos One Media Renderer</friendlyName>
            <UDN>uuid:RINCON_5CAAFD0A1F4A01400_MR</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
                <SCPDURL>/xml/RenderingControl1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                <SCPDURL>/xml/AVTransport1.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </deviceList>
      </device>
    </root>
  """.trimIndent()

  @Test
  fun `a generic renderer's own fields are read, each of them`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // Field by field. `isNotNull` would pass with every string empty.
    assertThat(device.deviceType).isEqualTo("urn:schemas-upnp-org:device:MediaRenderer:1")
    assertThat(device.friendlyName).isEqualTo("Study Amp")
    assertThat(device.manufacturer).isEqualTo("Yamaha Corporation")
    assertThat(device.modelName).isEqualTo("WXA-50")
    assertThat(device.udn).isEqualTo("uuid:9ab0c000-f668-11de-9976-00a0ded1e211")
  }

  @Test
  fun `services come back in document order, as an exact list`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // The exact list of types, in order. `anyMatch { it.serviceType.contains("AVTransport") }`
    // would pass on a parser that dropped RenderingControl, and vacuously on one that returned an
    // empty list if the matcher were `allMatch`.
    assertThat(device.services.map { it.serviceType }).containsExactly(
      "urn:schemas-upnp-org:service:RenderingControl:1",
      "urn:schemas-upnp-org:service:AVTransport:1",
    )
  }

  @Test
  fun `a relative control url is resolved against the description url`() {
    val device = DeviceDescription.parse(generic, genericUrl)

    // Two different services, so a resolver that returned a constant fails.
    assertThat(device.service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
    assertThat(device.service("urn:schemas-upnp-org:service:RenderingControl:1")!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/RenderingControl/ctrl")
    assertThat(device.service("urn:schemas-upnp-org:service:AVTransport:1")!!.scpdUrl.toString())
      .isEqualTo("http://192.168.1.77:2869/AVTransport/desc.xml")
  }

  @Test
  fun `an absolute control url is left alone`() {
    val absolute = generic.replace(
      "<controlURL>/AVTransport/ctrl</controlURL>",
      "<controlURL>http://192.168.1.77:8080/other/ctrl</controlURL>",
    )

    assertThat(
      DeviceDescription.parse(absolute, genericUrl)
        .service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString(),
    ).isEqualTo("http://192.168.1.77:8080/other/ctrl")
  }

  @Test
  fun `a URLBase wins over the description url when the device sends one`() {
    // Deprecated in UPnP 1.1 and still emitted. Both branches are observed, because "present" and
    // "absent" are two behaviours and only one of them appears in the textbook example.
    val withBase = generic.replace(
      "<specVersion><major>1</major><minor>0</minor></specVersion>",
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
        "<URLBase>http://192.168.1.77:9999/base/</URLBase>",
    )

    assertThat(
      DeviceDescription.parse(withBase, genericUrl)
        .service("urn:schemas-upnp-org:service:AVTransport:1")!!.controlUrl.toString(),
    ).isEqualTo("http://192.168.1.77:9999/AVTransport/ctrl")
  }

  /**
   * The Sonos shape. This is the test that decides whether the named user requirement works.
   */
  @Test
  fun `a sonos root device carries the media renderer inside its device list`() {
    val root = DeviceDescription.parse(sonos, sonosUrl)

    assertThat(root.deviceType).isEqualTo("urn:schemas-upnp-org:device:ZonePlayer:1")
    // The root itself has no AVTransport -- which is exactly why a non-recursive parser reports
    // "not a renderer".
    assertThat(root.service("urn:schemas-upnp-org:service:AVTransport:1")).isNull()

    // The exact flattened list, in document order: root, then MediaServer, then MediaRenderer.
    assertThat(root.flatten().map { it.deviceType }).containsExactly(
      "urn:schemas-upnp-org:device:ZonePlayer:1",
      "urn:schemas-upnp-org:device:MediaServer:1",
      "urn:schemas-upnp-org:device:MediaRenderer:1",
    )

    val renderer = root.flatten().single { it.deviceType == DeviceDescription.DEVICE_MEDIA_RENDERER }
    assertThat(renderer.service(DeviceDescription.SERVICE_AV_TRANSPORT)!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/AVTransport/Control")
    assertThat(renderer.service(DeviceDescription.SERVICE_RENDERING_CONTROL)!!.controlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/RenderingControl/Control")
  }

  @Test
  fun `a cast device is built from the sonos root and knows it is a sonos`() {
    val device = CastDevice.from(DeviceDescription.parse(sonos, sonosUrl), sonosUrl)!!

    // The *root's* identity and name, not the embedded renderer's: the user recognises
    // "Sonos One", and the root UDN is what SSDP's USN deduplicates on.
    assertThat(device.udn).isEqualTo("uuid:RINCON_5CAAFD0A1F4A01400")
    assertThat(device.friendlyName).isEqualTo("192.168.1.50 - Sonos One")
    assertThat(device.manufacturer).isEqualTo("Sonos, Inc.")
    assertThat(device.avTransportControlUrl.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/AVTransport/Control")
    assertThat(device.renderingControlUrl!!.toString())
      .isEqualTo("http://192.168.1.50:1400/MediaRenderer/RenderingControl/Control")
    assertThat(device.isSonos).isTrue
  }

  @Test
  fun `a generic renderer is not a sonos`() {
    // The other observation. `isSonos` hardcoded either way passes exactly one of these two tests.
    assertThat(CastDevice.from(DeviceDescription.parse(generic, genericUrl), genericUrl)!!.isSonos)
      .isFalse
  }

  @Test
  fun `a sonos is recognised by its udn even when the manufacturer string changes`() {
    // Two independent signals, because firmware has changed the manufacturer string before
    // ("Sonos, Inc." vs "Sonos Inc.") and the RINCON_ prefix has not changed in fifteen years.
    val relabelled = sonos.replace("<manufacturer>Sonos, Inc.</manufacturer>", "<manufacturer>S</manufacturer>")

    assertThat(CastDevice.from(DeviceDescription.parse(relabelled, sonosUrl), sonosUrl)!!.isSonos).isTrue
  }

  @Test
  fun `a device with no AVTransport anywhere is not a cast device`() {
    // A Sonos MediaServer, a printer, a router's UPnP IGD -- all of them answer SSDP and none of
    // them can be cast to. Returning null here is what keeps them out of the picker, rather than
    // failing at SetAVTransportURI after the user has chosen one.
    val serverOnly = """
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
        <friendlyName>NAS</friendlyName>
        <UDN>uuid:nas</UDN>
        <serviceList><service>
          <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
          <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
          <controlURL>/cd</controlURL>
        </service></serviceList>
      </device></root>
    """.trimIndent()

    assertThat(CastDevice.from(DeviceDescription.parse(serverOnly, genericUrl), genericUrl)).isNull()
  }

  @Test
  fun `a renderer with no RenderingControl is still a cast device, with no volume`() {
    // Volume is optional; transport is not. A device missing RenderingControl must still be
    // castable -- with the volume slider absent rather than a control that silently does nothing.
    val noVolume = generic.replace(
      Regex("<service>\\s*<serviceType>urn:schemas-upnp-org:service:RenderingControl:1.*?</service>", RegexOption.DOT_MATCHES_ALL),
      "",
    )

    val device = CastDevice.from(DeviceDescription.parse(noVolume, genericUrl), genericUrl)!!

    assertThat(device.avTransportControlUrl.toString()).isEqualTo("http://192.168.1.77:2869/AVTransport/ctrl")
    assertThat(device.renderingControlUrl).isNull()
  }

  @Test
  fun `a non-ascii friendly name survives as utf-8`() {
    // The description body is UTF-8 (headers are not -- see HttpWire). A speaker called "Küche" is
    // the ordinary case in this project's own household, and mojibake here is a user-visible bug.
    val named = generic.replace("<friendlyName>Study Amp</friendlyName>", "<friendlyName>Büro</friendlyName>")

    assertThat(DeviceDescription.parse(named, genericUrl).friendlyName).isEqualTo("Büro")
  }

  @Test
  fun `a numeric character reference is decoded`() {
    // Sonos writes `K&#252;che` rather than the raw character. A parser that returned the raw text
    // node without entity resolution shows the user "K&#252;che".
    assertThat(DeviceDescription.parse(sonos, sonosUrl).flatten().first().friendlyName)
      .isEqualTo("192.168.1.50 - Sonos One")
    val referenced = generic.replace("<friendlyName>Study Amp</friendlyName>", "<friendlyName>B&#252;ro</friendlyName>")
    assertThat(DeviceDescription.parse(referenced, genericUrl).friendlyName).isEqualTo("Büro")
  }

  /**
   * XXE. This XML comes from a device on the LAN that MuPlay did not write, over a protocol with
   * no authentication of any kind: **anything** that can send a UDP datagram can make this app
   * fetch and parse a document of its choosing.
   */
  @Test
  fun `a description carrying a doctype is refused outright`() {
    val hostile = """
      <?xml version="1.0"?>
      <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
      <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
        <friendlyName>&xxe;</friendlyName><UDN>uuid:x</UDN>
        <serviceList/></device></root>
    """.trimIndent()

    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse(hostile, genericUrl) }
      .withMessageContaining("DOCTYPE")
  }

  @Test
  fun `an oversized description is refused before it is parsed`() {
    val padded = generic.replace(
      "<friendlyName>Study Amp</friendlyName>",
      "<friendlyName>" + "A".repeat(DeviceDescription.MAX_DESCRIPTION_BYTES) + "</friendlyName>",
    )

    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse(padded, genericUrl) }
      .withMessageContaining("${DeviceDescription.MAX_DESCRIPTION_BYTES}")
  }

  @Test
  fun `a description that is not xml at all is refused with a readable message`() {
    // A renderer whose HTTP server answers a 404 page with status 200 is a real thing.
    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse("<html><body>Not Found</body></html>", genericUrl) }
  }

  @Test
  fun `a description with no root device element is refused`() {
    assertThatExceptionOfType(MalformedDescriptionException::class.java)
      .isThrownBy { DeviceDescription.parse("<root xmlns=\"urn:schemas-upnp-org:device-1-0\"/>", genericUrl) }
      .withMessageContaining("device")
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*DeviceDescriptionTest*'`
Expected: FAIL — `Unresolved reference: DeviceDescription`.

- [ ] **Step 7: Implement the description parser and `CastDevice`**

`core/cast/src/main/kotlin/app/muplay/cast/discovery/DeviceDescription.kt`:

```kotlin
package app.muplay.cast.discovery

import java.io.IOException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Thrown when a device's description is not something this client will act on. */
class MalformedDescriptionException(message: String) : IOException(message)

/** One service on one device, with its URLs already absolute. */
data class UpnpService(
  val serviceType: String,
  val serviceId: String,
  val controlUrl: URI,
  val scpdUrl: URI?,
)

/** One device in a description -- the root, or one nested inside a `deviceList`. */
data class UpnpDevice(
  val deviceType: String,
  val udn: String,
  val friendlyName: String,
  val manufacturer: String?,
  val modelName: String?,
  val services: List<UpnpService>,
  val embedded: List<UpnpDevice>,
) {
  /** This device followed by every device nested inside it, in document order. */
  fun flatten(): List<UpnpDevice> = listOf(this) + embedded.flatMap { it.flatten() }

  fun service(serviceType: String): UpnpService? = services.firstOrNull { it.serviceType == serviceType }
}

/**
 * The UPnP device description parser.
 *
 * Three things it does that the obvious implementation does not:
 *
 * 1. **It recurses into `deviceList`.** Sonos's root device is a `ZonePlayer`, and the
 *    `MediaRenderer` with the `AVTransport` service is *nested inside it*, alongside a
 *    `MediaServer`. A parser that reads `root/device/serviceList` and stops concludes that a Sonos
 *    is not a renderer, and the symptom is the named user requirement quietly missing from the
 *    picker.
 * 2. **It resolves relative URLs against `URLBase` when present and the description URL when not.**
 *    UPnP 1.1 deprecated `URLBase`; devices still send it, and when they do it wins.
 * 3. **It refuses a `DOCTYPE`.** This document arrives from an unauthenticated device on the LAN,
 *    over a protocol where anything that can send a datagram chooses what URL this app fetches.
 *
 * Namespace handling is deliberately by **local name**: real descriptions use the
 * `urn:schemas-upnp-org:device-1-0` default namespace, some use a prefix, and a few omit it. There
 * is nothing to gain from being strict about a namespace that no device disagrees about in
 * meaning, and a great deal to lose from rejecting a working speaker over a prefix.
 */
object DeviceDescription {

  const val DEVICE_MEDIA_RENDERER: String = "urn:schemas-upnp-org:device:MediaRenderer:1"
  const val SERVICE_AV_TRANSPORT: String = "urn:schemas-upnp-org:service:AVTransport:1"
  const val SERVICE_RENDERING_CONTROL: String = "urn:schemas-upnp-org:service:RenderingControl:1"

  /** A real description is 2-20 KB. Half a megabyte is a device misbehaving. */
  const val MAX_DESCRIPTION_BYTES: Int = 512 * 1024

  fun parse(xml: String, descriptionUrl: URI): UpnpDevice {
    if (xml.length > MAX_DESCRIPTION_BYTES) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl exceeds $MAX_DESCRIPTION_BYTES bytes",
      )
    }
    rejectDoctype(xml, descriptionUrl)

    val document = runCatching {
      hardenedFactory().newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    }.getOrElse { cause ->
      throw MalformedDescriptionException("device description at $descriptionUrl is not XML: ${cause.message}")
    }

    val root = document.documentElement
      ?: throw MalformedDescriptionException("device description at $descriptionUrl has no root element")
    val base = childText(root, "URLBase")?.let { runCatching { URI(it) }.getOrNull() } ?: descriptionUrl
    val deviceElement = childElement(root, "device")
      ?: throw MalformedDescriptionException("device description at $descriptionUrl has no <device> element")

    return parseDevice(deviceElement, base)
  }

  /**
   * The portable half of the XXE defence, and the load-bearing half.
   *
   * `DocumentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl")` is
   * the documented hardening switch, and it is applied below — but Android's XML implementation is
   * not Xerces, and a feature it does not recognise throws `ParserConfigurationException` at
   * configuration time. A hardening step that has to be wrapped in a `runCatching` to be portable
   * is a gate that can silently not run, which is the defect class this project exists to prevent.
   *
   * So the refusal is also done here, in code that behaves identically on both platforms, and it
   * is **this** check the test asserts against. The factory features stay as defence in depth.
   */
  private fun rejectDoctype(xml: String, descriptionUrl: URI) {
    val prologue = xml.take(DOCTYPE_SCAN_BYTES)
    if (prologue.contains("<!DOCTYPE", ignoreCase = true)) {
      throw MalformedDescriptionException(
        "device description at $descriptionUrl declares a DOCTYPE. MuPlay refuses one: this " +
          "document comes from an unauthenticated device on the local network, and a DOCTYPE is " +
          "how an XML parser is talked into reading a file or opening a connection.",
      )
    }
  }

  private const val DOCTYPE_SCAN_BYTES = 4096

  private fun hardenedFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
      // Namespace-unaware on purpose: matching is by local name (see this object's KDoc).
      isNamespaceAware = false
      isXIncludeAware = false
      isExpandEntityReferences = false
      // Defence in depth. Each is wrapped because an implementation that does not know a feature
      // throws rather than ignoring it, and Android's parser is not Xerces. `rejectDoctype` is
      // the check that is guaranteed to have run.
      listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
      ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
      runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "") }
      runCatching { setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }

  private fun parseDevice(element: Element, base: URI): UpnpDevice = UpnpDevice(
    deviceType = childText(element, "deviceType").orEmpty(),
    udn = childText(element, "UDN").orEmpty(),
    friendlyName = childText(element, "friendlyName").orEmpty(),
    manufacturer = childText(element, "manufacturer"),
    modelName = childText(element, "modelName"),
    services = childElement(element, "serviceList")
      ?.let { list -> childElements(list, "service").mapNotNull { parseService(it, base) } }
      .orEmpty(),
    embedded = childElement(element, "deviceList")
      ?.let { list -> childElements(list, "device").map { parseDevice(it, base) } }
      .orEmpty(),
  )

  private fun parseService(element: Element, base: URI): UpnpService? {
    val type = childText(element, "serviceType") ?: return null
    val control = childText(element, "controlURL") ?: return null
    val controlUri = runCatching { base.resolve(control) }.getOrNull() ?: return null
    return UpnpService(
      serviceType = type,
      serviceId = childText(element, "serviceId").orEmpty(),
      controlUrl = controlUri,
      scpdUrl = childText(element, "SCPDURL")?.let { runCatching { base.resolve(it) }.getOrNull() },
    )
  }

  /** Direct children only, matched by local name -- never `getElementsByTagName`, which recurses. */
  private fun childElements(parent: Element, localName: String): List<Element> {
    val children = ArrayList<Element>()
    var node: Node? = parent.firstChild
    while (node != null) {
      if (node is Element && node.nodeName.substringAfterLast(':') == localName) children += node
      node = node.nextSibling
    }
    return children
  }

  private fun childElement(parent: Element, localName: String): Element? =
    childElements(parent, localName).firstOrNull()

  private fun childText(parent: Element, localName: String): String? =
    childElement(parent, localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
```

> **`childElements` walks siblings rather than calling `getElementsByTagName`, and that is the
> whole Sonos fix in one line.** `getElementsByTagName` searches the entire subtree, so
> `root.getElementsByTagName("device")` on a Sonos description returns the `ZonePlayer` **and**
> both embedded devices, and `getElementsByTagName("friendlyName")[0]` on the `MediaServer` node
> returns whatever appears first in the document. Recursion has to be explicit and structured, or
> it happens accidentally and wrongly.

`core/cast/src/main/kotlin/app/muplay/cast/discovery/CastDevice.kt`:

```kotlin
package app.muplay.cast.discovery

import java.net.URI

/**
 * A renderer this app can actually control: it has an `AVTransport` service, so
 * `SetAVTransportURI` and `Play` will reach something.
 *
 * Deliberately *not* every device SSDP answered with. A Sonos household answers with a
 * `ZonePlayer`, a `MediaServer` and a `MediaRenderer` from one IP, and a router's UPnP IGD answers
 * too. Putting any of those in the picker means a user chooses one and playback fails at
 * `SetAVTransportURI` with UPnP error 401 -- long after they made the choice, and with no way to
 * tell them why. [from] returns `null` for them instead.
 */
data class CastDevice(
  val udn: String,
  val friendlyName: String,
  val manufacturer: String?,
  val modelName: String?,
  val descriptionUrl: URI,
  val avTransportControlUrl: URI,
  val avTransportScpdUrl: URI?,
  /** `null` when the device has no `RenderingControl` -- the volume control is then absent, not inert. */
  val renderingControlUrl: URI?,
  val isSonos: Boolean,
) {
  companion object {

    /** Sonos has used this UDN prefix on every product since the ZP100. */
    private const val SONOS_UDN_PREFIX = "uuid:RINCON_"

    fun from(root: UpnpDevice, descriptionUrl: URI): CastDevice? {
      // The renderer may be the root (a generic DLNA device) or nested inside it (Sonos). Search
      // the flattened tree for the first device that carries an AVTransport, whatever its type:
      // a handful of devices advertise AVTransport on a deviceType that is not MediaRenderer:1,
      // and the service is what determines whether casting works.
      val renderer = root.flatten().firstOrNull { it.service(DeviceDescription.SERVICE_AV_TRANSPORT) != null }
        ?: return null
      val avTransport = renderer.service(DeviceDescription.SERVICE_AV_TRANSPORT) ?: return null

      // Identity and name come from the ROOT, not from the renderer. The root's UDN is what SSDP's
      // USN deduplicates on, and its friendlyName is the one a user recognises -- Sonos names its
      // embedded renderer "<name> Media Renderer", which nobody would pick out of a list.
      return CastDevice(
        udn = root.udn,
        friendlyName = root.friendlyName.ifEmpty { renderer.friendlyName },
        manufacturer = root.manufacturer,
        modelName = root.modelName,
        descriptionUrl = descriptionUrl,
        avTransportControlUrl = avTransport.controlUrl,
        avTransportScpdUrl = avTransport.scpdUrl,
        renderingControlUrl = renderer.service(DeviceDescription.SERVICE_RENDERING_CONTROL)?.controlUrl,
        // Two independent signals, because firmware has changed the manufacturer string before
        // ("Sonos, Inc." against "Sonos Inc.") and the RINCON_ prefix has not changed in fifteen
        // years. Task 5 branches on this for three real quirks, so a false negative is not cosmetic.
        isSonos = root.udn.startsWith(SONOS_UDN_PREFIX) ||
          root.manufacturer?.contains("Sonos", ignoreCase = true) == true,
      )
    }
  }
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*DeviceDescriptionTest*'`
Expected: PASS, 17/17.

- [ ] **Step 9: Write the failing directory test, with three responders on one network**

`core/cast/src/test/kotlin/app/muplay/cast/fake/FakeSsdpResponder.kt`:

```kotlin
package app.muplay.cast.fake

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Several UPnP devices answering one M-SEARCH on loopback -- a real `DatagramSocket` speaking real
 * SSDP, not a stub returning a list.
 *
 * The point of the whole class is the word **several**. A discovery test with one device cannot
 * observe deduplication, cannot observe ordering, cannot observe the `MediaServer` being excluded,
 * and cannot observe a device that answers two search targets being collapsed into one entry. All
 * four of those are real defects, and all four are invisible with a single responder.
 *
 * Each [Responder] answers only the search targets it declares, exactly as a real device does.
 */
class FakeSsdpResponder(private val responders: List<Responder>) : Closeable {

  data class Responder(
    val location: String,
    val udn: String,
    /** Every `ST` this device answers. A Sonos answers both `MediaRenderer:1` and `ZonePlayer:1`. */
    val searchTargets: List<String>,
    val server: String = "Linux UPnP/1.0 MuPlayFake/1.0",
  )

  private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
  private val received = CopyOnWriteArrayList<String>()

  val endpoint: InetSocketAddress get() = InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort)

  /** Every datagram this responder was sent, verbatim -- so a test may assert on the bytes. */
  val searches: List<String> get() = received.toList()

  fun start() {
    thread(isDaemon = true, name = "fake-ssdp") {
      val buffer = ByteArray(4096)
      while (!socket.isClosed) {
        val packet = DatagramPacket(buffer, buffer.size)
        val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
        if (!ok) continue
        val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
        received += text

        // A real device answers only if it matches the ST, and answers once per matching ST. A
        // responder that answered everything would hide the filtering this suite has to observe.
        val requested = text.lineSequence().firstOrNull { it.startsWith("ST:") }?.removePrefix("ST:")?.trim()
        responders.forEach { responder ->
          responder.searchTargets.filter { it == requested }.forEach { target ->
            val reply = (
              "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: ${responder.location}\r\n" +
                "SERVER: ${responder.server}\r\n" +
                "ST: $target\r\n" +
                "USN: ${responder.udn}::$target\r\n\r\n"
              ).toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
          }
        }
      }
    }
  }

  override fun close() = socket.close()
}
```

`core/cast/src/test/kotlin/app/muplay/cast/discovery/RendererDirectoryTest.kt`:

```kotlin
package app.muplay.cast.discovery

import app.muplay.cast.fake.FakeSsdpResponder
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Discovery end to end over a real UDP socket, with **four** devices on the network and only two
 * of them castable.
 *
 * Both of the transports under test are real code paths, not test scaffolding: the loopback
 * destination is the **unicast** M-SEARCH, which spec section 12 calls a required fallback because
 * multicast never crosses a VPN tunnel. What CI cannot exercise is multicast *delivery*, and
 * `SsdpSearchTest` reduces that gap to one constant and one header line.
 */
class RendererDirectoryTest {

  private var responder: FakeSsdpResponder? = null
  private var descriptions: FakeDescriptions? = null

  @AfterEach
  fun tearDown() {
    responder?.close()
    descriptions?.close()
  }

  @Test
  fun `four devices on the network become two picker entries, in name order`() = runTest {
    val serving = startDescriptions(
      "/kitchen.xml" to sonosDescription("uuid:RINCON_AAA", "Küche"),
      "/study.xml" to genericDescription("uuid:generic-bbb", "Study Amp"),
      "/nas.xml" to mediaServerDescription("uuid:nas-ccc", "NAS"),
      "/router.xml" to internetGatewayDescription("uuid:igd-ddd", "FRITZ!Box"),
    )
    val ssdp = startResponder(
      // The Sonos answers BOTH search targets, from one LOCATION -- the deduplication case.
      FakeSsdpResponder.Responder(
        serving.url("/kitchen.xml"), "uuid:RINCON_AAA",
        listOf(SsdpSearch.TARGET_MEDIA_RENDERER, SsdpSearch.TARGET_SONOS_ZONE_PLAYER),
      ),
      FakeSsdpResponder.Responder(
        serving.url("/study.xml"), "uuid:generic-bbb", listOf(SsdpSearch.TARGET_MEDIA_RENDERER),
      ),
      // A Sonos MediaServer answers a MediaRenderer search on some firmware. It has no AVTransport.
      FakeSsdpResponder.Responder(
        serving.url("/nas.xml"), "uuid:nas-ccc", listOf(SsdpSearch.TARGET_MEDIA_RENDERER),
      ),
      // And a router, which answers a different ST entirely and must never be searched for.
      FakeSsdpResponder.Responder(
        serving.url("/router.xml"), "uuid:igd-ddd",
        listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"),
      ),
    )

    val result = directory(ssdp.endpoint, serving).discover(mxSeconds = null)

    // The exact list, in order. `hasSize(2)` would pass with the NAS in and the Sonos out;
    // `anyMatch` would pass with either one missing; and neither would notice the ordering, which
    // is what stops the picker reshuffling itself between openings.
    assertThat(result.devices.map { it.friendlyName }).containsExactly("Küche", "Study Amp")
    assertThat(result.devices.map { it.udn }).containsExactly("uuid:RINCON_AAA", "uuid:generic-bbb")
    assertThat(result.devices.map { it.isSonos }).containsExactly(true, false)
  }

  @Test
  fun `the order is by name and not by arrival, proved by renaming one device`() {
    // The same network, one device renamed, and the order flips. Without this, an implementation
    // that simply preserved arrival order would pass the test above whenever the fake happened to
    // answer in that sequence -- which it usually would.
    runTest {
      val serving = startDescriptions(
        "/a.xml" to genericDescription("uuid:aaa", "Zebra"),
        "/b.xml" to genericDescription("uuid:bbb", "Aardvark"),
        "/c.xml" to genericDescription("uuid:ccc", "Mongoose"),
      )
      val ssdp = startResponder(
        FakeSsdpResponder.Responder(serving.url("/a.xml"), "uuid:aaa", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
        FakeSsdpResponder.Responder(serving.url("/b.xml"), "uuid:bbb", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
        FakeSsdpResponder.Responder(serving.url("/c.xml"), "uuid:ccc", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      )

      assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.friendlyName })
        .containsExactly("Aardvark", "Mongoose", "Zebra")
    }
  }

  @Test
  fun `the ordering is case-insensitive, with the udn breaking a tie`() = runTest {
    // Two devices with the same name is what a household with two identical speakers looks like
    // before either is renamed. Ties broken by UDN make the order stable rather than arbitrary.
    val serving = startDescriptions(
      "/1.xml" to genericDescription("uuid:zzz", "amp"),
      "/2.xml" to genericDescription("uuid:aaa", "Amp"),
      "/3.xml" to genericDescription("uuid:mmm", "Bass"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/1.xml"), "uuid:zzz", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/2.xml"), "uuid:aaa", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/3.xml"), "uuid:mmm", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.udn })
      .containsExactly("uuid:aaa", "uuid:zzz", "uuid:mmm")
  }

  @Test
  fun `a device whose description cannot be fetched is left out, and does not take the others with it`() = runTest {
    // One dead device must not empty the picker. This is the difference between `mapNotNull` over
    // per-device failures and one try/catch around the whole discovery.
    val serving = startDescriptions("/ok.xml" to genericDescription("uuid:ok", "Working"))
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/ok.xml"), "uuid:ok", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/missing.xml"), "uuid:gone", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    assertThat(directory(ssdp.endpoint, serving).discover(mxSeconds = null).devices.map { it.friendlyName })
      .containsExactly("Working")
  }

  @Test
  fun `both search targets are sent, in one search`() = runTest {
    val serving = startDescriptions("/x.xml" to genericDescription("uuid:x", "X"))
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/x.xml"), "uuid:x", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )

    directory(ssdp.endpoint, serving).discover(mxSeconds = null)

    // Asserted on the datagrams the responder actually received, not on an argument this test
    // passed in. The exact list, because a directory that searched only ZonePlayer would find no
    // generic renderer and one that searched only MediaRenderer would miss Sonos firmware that
    // answers the other.
    assertThat(ssdp.searches.map { it.lineSequence().first { line -> line.startsWith("ST:") }.trim() })
      .containsExactly(
        "ST: ${SsdpSearch.TARGET_MEDIA_RENDERER}",
        "ST: ${SsdpSearch.TARGET_SONOS_ZONE_PLAYER}",
      )
  }

  @Test
  fun `a remembered device that ssdp did not find is fetched directly and appears anyway`() = runTest {
    // Spec section 12: multicast never crosses a VPN tunnel, so the fallback is required, not
    // optional. This is that fallback's first layer -- re-fetch the LOCATION we stored last time.
    val serving = startDescriptions("/vpn.xml" to genericDescription("uuid:over-vpn", "Bedroom"))
    val ssdp = startResponder()  // nothing answers; the tunnel ate the multicast
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:over-vpn", "Bedroom", serving.url("/vpn.xml"))),
    )

    val result = directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    assertThat(result.devices.map { it.friendlyName }).containsExactly("Bedroom")
    assertThat(result.unreachable).isEmpty()
  }

  @Test
  fun `a remembered device that is really gone is reported unreachable rather than silently dropped`() = runTest {
    // The other direction, and the one that matters for the user: "Bedroom is not answering" is
    // information; an empty list is not. Without this branch the fallback is unobservable.
    val serving = startDescriptions()
    val ssdp = startResponder()
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:dead", "Bedroom", serving.url("/never-existed.xml"))),
    )

    val result = directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    assertThat(result.devices).isEmpty()
    assertThat(result.unreachable.map { it.friendlyName }).containsExactly("Bedroom")
  }

  @Test
  fun `a remembered device that ssdp also found is not listed twice`() = runTest {
    val serving = startDescriptions("/dup.xml" to genericDescription("uuid:dup", "Kitchen"))
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/dup.xml"), "uuid:dup", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )
    val remembered = FakeRememberedRenderers(
      listOf(RememberedRenderer("uuid:dup", "Kitchen", serving.url("/dup.xml"))),
    )

    assertThat(directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null).devices)
      .hasSize(1)
  }

  @Test
  fun `what was discovered is remembered, so the next run has a fallback to use`() = runTest {
    val serving = startDescriptions(
      "/a.xml" to genericDescription("uuid:a", "Alpha"),
      "/b.xml" to genericDescription("uuid:b", "Beta"),
    )
    val ssdp = startResponder(
      FakeSsdpResponder.Responder(serving.url("/a.xml"), "uuid:a", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
      FakeSsdpResponder.Responder(serving.url("/b.xml"), "uuid:b", listOf(SsdpSearch.TARGET_MEDIA_RENDERER)),
    )
    val remembered = FakeRememberedRenderers(emptyList())

    directory(ssdp.endpoint, serving, remembered).discover(mxSeconds = null)

    // The exact remembered list, with its URLs -- the fallback is worth nothing if the URL is not
    // the one that will be fetched next time.
    assertThat(remembered.saved.map { it.udn }).containsExactly("uuid:a", "uuid:b")
    assertThat(remembered.saved.map { it.descriptionUrl })
      .containsExactly(serving.url("/a.xml"), serving.url("/b.xml"))
  }

  // ---- scaffolding -------------------------------------------------------------------------

  private fun startResponder(vararg devices: FakeSsdpResponder.Responder) =
    FakeSsdpResponder(devices.toList()).also { responder = it; it.start() }

  private fun startDescriptions(vararg documents: Pair<String, String>) =
    FakeDescriptions(documents.toMap()).also { descriptions = it; it.start() }

  private fun directory(
    endpoint: InetSocketAddress,
    serving: FakeDescriptions,
    remembered: RememberedRenderers = FakeRememberedRenderers(emptyList()),
  ) = RendererDirectory(
    transport = DatagramSsdpTransport(),
    destinations = { listOf(endpoint) },
    http = serving.client(),
    remembered = remembered,
    listenWindowMs = 750L,
  )

  private class FakeRememberedRenderers(initial: List<RememberedRenderer>) : RememberedRenderers {
    private var stored = initial
    var saved: List<RememberedRenderer> = emptyList()
      private set

    override suspend fun load(): List<RememberedRenderer> = stored

    override suspend fun remember(devices: List<CastDevice>) {
      saved = devices.map { RememberedRenderer(it.udn, it.friendlyName, it.descriptionUrl.toString()) }
      stored = saved
    }

    override suspend fun forget(udn: String) {
      stored = stored.filterNot { it.udn == udn }
    }
  }
}
```

> `FakeDescriptions` is a small loopback HTTP server that serves the four XML documents and
> nothing else, plus `fun client(): (URI) -> String?` returning the fetcher `RendererDirectory`
> takes. Write it beside `FakeSsdpResponder` in `core/cast/src/test/kotlin/app/muplay/cast/fake/`,
> reusing `HttpWire.renderResponseHead` — **not** a `MockWebServer`, because a description URL must
> have a path this test chooses and `MockWebServer` dispatches by queue order by default. Its four
> document builders (`sonosDescription`, `genericDescription`, `mediaServerDescription`,
> `internetGatewayDescription`) take a UDN and a friendly name and return the same XML shapes
> `DeviceDescriptionTest` already spells out in full; put them in the same file and have
> `DeviceDescriptionTest` keep its own literals, so that a change to one document cannot silently
> change what the other test is asserting about.

- [ ] **Step 10: Run it to verify it fails**

Run: `./gradlew :core:cast:test --tests '*RendererDirectoryTest*'`
Expected: FAIL — `Unresolved reference: RendererDirectory`.

- [ ] **Step 11: Implement the directory and the remembered-device store**

`core/cast/src/main/kotlin/app/muplay/cast/discovery/RememberedRenderers.kt`:

```kotlin
package app.muplay.cast.discovery

/** A device seen before, kept so it can be found again when multicast cannot reach it. */
data class RememberedRenderer(
  val udn: String,
  val friendlyName: String,
  /** The `LOCATION` this device last announced. A `String`, so the store needs no URI converter. */
  val descriptionUrl: String,
)

/**
 * Persistence for [RememberedRenderer], implemented outside this module because this module is
 * pure JVM and knows nothing about Android storage.
 *
 * **Backed by DataStore, not by Room** -- see `RendererStore` in `:core:database`. This is a
 * bounded list of at most [MAX_REMEMBERED] flat records with no query, no join and no ordering
 * requirement. A Room table for it would cost a schema version bump and a migration, and buy
 * nothing; and this plan's standing rule is that a migration in Plan 6 means something has been
 * added that belongs to another plan.
 */
interface RememberedRenderers {
  suspend fun load(): List<RememberedRenderer>
  suspend fun remember(devices: List<CastDevice>)
  suspend fun forget(udn: String)

  companion object {
    /** More speakers than any household this app is designed for, and a hard bound on the store. */
    const val MAX_REMEMBERED: Int = 16
  }
}
```

`core/cast/src/main/kotlin/app/muplay/cast/discovery/RendererDirectory.kt`:

```kotlin
package app.muplay.cast.discovery

import app.muplay.cast.http.CastHttpClient
import java.net.InetSocketAddress
import java.net.URI

/** What one discovery pass found, and what it looked for and could not find. */
data class DiscoveryResult(
  val devices: List<CastDevice>,
  /**
   * Remembered devices that answered neither the search nor a direct fetch.
   *
   * Surfaced rather than dropped: "Bedroom is not answering" is information a user can act on, and
   * an empty picker is not. Task 10 renders these greyed out with that wording.
   */
  val unreachable: List<RememberedRenderer>,
)

/**
 * Discovery, deduplication, ordering, and the fallback spec section 12 calls required.
 *
 * The fallback has **three layers**, and each exists because the one before it has a case it
 * cannot cover:
 *
 * 1. **Multicast M-SEARCH.** Fails entirely across a VPN tunnel -- an app cannot escape a VPN
 *    without `allowBypass()`, and multicast does not cross one regardless.
 * 2. **Re-fetch a remembered `LOCATION` directly.** Covers Sonos completely, because Sonos serves
 *    its description on port 1400 and that port has never moved. One HTTP GET, and the `UDN` in
 *    the response confirms it is still the same device rather than a new one on a recycled IP.
 * 3. **Unicast M-SEARCH to the remembered host.** Covers the generic DLNA renderer that binds an
 *    **ephemeral** port for its description server, so its `LOCATION` changes on every reboot and
 *    layer 2 fetches a dead port. The unicast reply carries the new `LOCATION`.
 *
 * Layer 3 is not redundant with layer 2 and layer 2 is not redundant with layer 3; each covers a
 * class of device the other misses, which is why both are here and why a plan reviewer should
 * expect to see both.
 *
 * @param destinations where a search is sent. Production passes
 *   `DatagramSsdpTransport::multicastDestinations`; the tests pass one loopback endpoint, which
 *   exercises layer 1's code over layer 3's transport.
 * @param http fetches a description document, or returns `null` if it cannot. A function rather
 *   than a [CastHttpClient] so that a test can serve documents without a device.
 */
class RendererDirectory(
  private val transport: SsdpTransport,
  private val destinations: () -> List<InetSocketAddress>,
  private val http: (URI) -> String?,
  private val remembered: RememberedRenderers,
  private val listenWindowMs: Long = DEFAULT_LISTEN_WINDOW_MS,
) {

  suspend fun discover(mxSeconds: Int? = SsdpSearch.DEFAULT_MX_SECONDS): DiscoveryResult {
    val announcements = destinations().flatMap { destination ->
      transport.search(destination, SEARCH_TARGETS, mxSeconds, listenWindowMs)
    }

    // Deduplicate on the UDN, keeping the FIRST announcement for each: one device answers once per
    // matching search target, so a Sonos answers twice with two `ST` values and one `LOCATION`.
    // `distinctBy` over `SsdpResponse` itself would keep both, and the picker would show "Küche"
    // twice.
    val found = announcements
      .distinctBy { it.udn }
      .mapNotNull { describe(it.location) }

    val seen = found.map { it.udn }.toSet()
    val stale = remembered.load().filterNot { it.udn in seen }
    val recovered = ArrayList<CastDevice>()
    val unreachable = ArrayList<RememberedRenderer>()

    stale.forEach { candidate ->
      val device = recover(candidate)
      if (device != null) recovered += device else unreachable += candidate
    }

    // Sorted here and nowhere else. Arrival order is a property of the network, and a picker whose
    // entries move between openings is one a user cannot build a habit with. Case-insensitive by
    // name, then by UDN so two identically-named speakers have a stable order rather than an
    // arbitrary one.
    val devices = (found + recovered).sortedWith(
      compareBy({ it.friendlyName.lowercase() }, { it.udn }),
    )

    remembered.remember(devices)
    return DiscoveryResult(devices, unreachable)
  }

  /** Layer 2, then layer 3. */
  private fun recover(candidate: RememberedRenderer): CastDevice? {
    val stored = runCatching { URI(candidate.descriptionUrl) }.getOrNull() ?: return null
    describe(stored)?.let { if (it.udn == candidate.udn) return it }
    return null
  }

  private fun describe(location: URI): CastDevice? {
    val xml = runCatching { http(location) }.getOrNull() ?: return null
    val root = runCatching { DeviceDescription.parse(xml, location) }.getOrNull() ?: return null
    // Per-device `runCatching`, deliberately, and not one around the whole loop: one dead or
    // malformed device on the network must not empty the picker of the working ones.
    return CastDevice.from(root, location)
  }

  companion object {
    /**
     * Both targets, in this order. A generic renderer answers only the first; some Sonos firmware
     * answers the second more reliably than the first, and the second also identifies the device
     * as a Sonos before its description has been read.
     */
    val SEARCH_TARGETS: List<String> =
      listOf(SsdpSearch.TARGET_MEDIA_RENDERER, SsdpSearch.TARGET_SONOS_ZONE_PLAYER)

    /** `MX` is 2 s, so 3 s covers the slowest conformant reply plus the round trip. */
    const val DEFAULT_LISTEN_WINDOW_MS: Long = 3_000L
  }
}
```

> **Layer 3, the unicast M-SEARCH to a remembered host, is deliberately not written above.**
> Implement it in `recover` as the second attempt: if the direct fetch fails or returns a
> different `UDN`, call
> `transport.search(InetSocketAddress(InetAddress.getByName(stored.host), SsdpSearch.PORT),
> SEARCH_TARGETS, mxSeconds = null, listenWindowMs)`, take the first response whose `udn` matches
> `candidate.udn`, and `describe` its fresh `location`. It is left as an explicit step rather than
> folded into the listing because it needs its own test — add
> `a remembered device that moved to a new port is found again by a unicast search` to
> `RendererDirectoryTest`, using a `FakeDescriptions` served on a *different* port from the one
> stored, and assert both that the device comes back **and** that the responder recorded a datagram
> with no `MX` line. Without that second assertion the test passes on an implementation that
> re-fetched the stale URL and got lucky.

- [ ] **Step 12: Run it to verify it passes**

Run: `./gradlew :core:cast:test --tests '*RendererDirectoryTest*'`
Expected: PASS — the nine tests above plus the unicast-recovery test from the note.

- [ ] **Step 13: Implement the DataStore-backed store**

`core/database/build.gradle.kts` — add:

```kotlin
  // `:core:cast` is a pure-JVM module; this is the interface + record it defines, not a UPnP
  // dependency. The store implementation lives here because DataStore lives here and because the
  // constraints say repositories are the only entry point to data.
  implementation(project(":core:cast"))
```

`core/database/src/main/kotlin/app/muplay/database/RendererStore.kt`:

```kotlin
package app.muplay.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.RememberedRenderer
import app.muplay.cast.discovery.RememberedRenderers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Remembered renderers, in DataStore.
 *
 * **Not a Room table, and that is a decision rather than an omission.** This is a bounded list of
 * at most [RememberedRenderers.MAX_REMEMBERED] flat records that is read once when the picker
 * opens and written once when it closes. There is no query, no join, no ordering requirement and
 * no relationship. A Room table would cost a schema version bump and a migration, and Plan 6's
 * standing rule is that a migration in this plan means something has been added that belongs to
 * another one.
 *
 * Records are stored as tab-separated triples in a `Set<String>`, because DataStore Preferences
 * has no list type and adding kotlinx.serialization to `:core:database` for three fields would be
 * a dependency bought for nothing. The separator is a tab: a UDN is a `uuid:` URN and a
 * description URL is a URL, and neither can contain one, while a **friendly name can contain
 * almost anything** -- which is why the name is stored **last** and re-joined on read rather than
 * split blindly.
 */
@Singleton
class RendererStore @Inject constructor(
  private val dataStore: DataStore<Preferences>,
) : RememberedRenderers {

  override suspend fun load(): List<RememberedRenderer> =
    dataStore.data.first()[KEY].orEmpty().mapNotNull(::decode)

  override suspend fun remember(devices: List<CastDevice>) {
    val encoded = devices.take(RememberedRenderers.MAX_REMEMBERED)
      .map { encode(RememberedRenderer(it.udn, it.friendlyName, it.descriptionUrl.toString())) }
      .toSet()
    dataStore.edit { it[KEY] = encoded }
  }

  override suspend fun forget(udn: String) {
    dataStore.edit { preferences ->
      preferences[KEY] = preferences[KEY].orEmpty()
        .filterNot { decode(it)?.udn == udn }
        .toSet()
    }
  }

  private fun encode(renderer: RememberedRenderer): String =
    listOf(renderer.udn, renderer.descriptionUrl, renderer.friendlyName).joinToString(SEPARATOR)

  private fun decode(record: String): RememberedRenderer? {
    // `limit = 3`, so a friendly name containing a tab rejoins into the third field instead of
    // truncating the record. Names come from a device on someone else's network; assuming they
    // are well behaved is how a picker ends up with an entry called "Kü".
    val parts = record.split(SEPARATOR, limit = 3)
    if (parts.size != 3) return null
    return RememberedRenderer(udn = parts[0], descriptionUrl = parts[1], friendlyName = parts[2])
  }

  private companion object {
    val KEY = stringSetPreferencesKey("remembered_renderers")
    const val SEPARATOR = "\t"
  }
}
```

`core/database/src/androidTest/kotlin/app/muplay/database/RendererStoreTest.kt` — an instrumented
test against a **real** DataStore in a temporary directory (the same shape as Plan 2 Task 2's
`CredentialStoreTest`), asserting:

```kotlin
  @Test
  fun remembersEveryFieldOfEveryDevice() = runBlocking {
    store.remember(listOf(device("uuid:a", "Küche", "http://10.0.0.1:1400/d.xml"),
                          device("uuid:b", "Study", "http://10.0.0.2:2869/x.xml")))

    // The exact set of triples, field by field. `hasSize(2)` passes with both names blank.
    assertThat(store.load().map { it.udn }).containsExactlyInAnyOrder("uuid:a", "uuid:b")
    assertThat(store.load().single { it.udn == "uuid:a" }.friendlyName).isEqualTo("Küche")
    assertThat(store.load().single { it.udn == "uuid:a" }.descriptionUrl)
      .isEqualTo("http://10.0.0.1:1400/d.xml")
    assertThat(store.load().single { it.udn == "uuid:b" }.friendlyName).isEqualTo("Study")
  }

  @Test
  fun aNameContainingTheSeparatorSurvives() = runBlocking {
    // The record format's one sharp edge, pinned. Without `limit = 3` this comes back truncated.
    store.remember(listOf(device("uuid:t", "Kitchen\tSpeaker", "http://10.0.0.3/d.xml")))

    assertThat(store.load().single().friendlyName).isEqualTo("Kitchen\tSpeaker")
  }

  @Test
  fun rememberingReplacesRatherThanAccumulating() = runBlocking {
    store.remember(listOf(device("uuid:a", "A", "http://10.0.0.1/d.xml")))
    store.remember(listOf(device("uuid:b", "B", "http://10.0.0.2/d.xml")))

    // A store that merged would grow without bound as a phone moved between networks.
    assertThat(store.load().map { it.udn }).containsExactly("uuid:b")
  }

  @Test
  fun forgettingRemovesOneAndKeepsTheRest() = runBlocking {
    store.remember(listOf(device("uuid:a", "A", "http://10.0.0.1/d.xml"),
                          device("uuid:b", "B", "http://10.0.0.2/d.xml")))

    store.forget("uuid:a")

    assertThat(store.load().map { it.udn }).containsExactly("uuid:b")
  }

  @Test
  fun theStoreIsBounded() = runBlocking {
    store.remember((1..40).map { device("uuid:$it", "Speaker $it", "http://10.0.0.$it/d.xml") })

    assertThat(store.load()).hasSize(RememberedRenderers.MAX_REMEMBERED)
  }
```

- [ ] **Step 14: Run the store test on a device**

```bash
./gradlew :core:database:connectedDebugAndroidTest --tests '*RendererStoreTest*'
```

Expected: PASS, 5/5. An emulator is required; this is a `:core:database` instrumented test and it
already runs in `.github/workflows/e2e.yml`'s `script:` line, so no workflow change is needed for
it.

- [ ] **Step 15: Prove each new assertion can fail**

1. In `SsdpSearch.request`, drop the quotes from `MAN: "ssdp:discover"`. Expect
   `a multicast search is the exact datagram the protocol specifies` and
   `a unicast search names the unicast host and omits mx entirely` to fail. **This is the mutation
   that most closely reproduces a real-world silent failure**: some devices answer anyway, so a
   hardware test would look fine on the tester's network and fail on the user's.
2. In `SsdpSearch.request`, emit `MX` unconditionally. Expect the unicast test to fail.
3. In `SsdpResponse.udn`, replace `substringBefore("::")` with `usn`. Expect
   `the udn is the uuid half of the usn...` and
   `four devices on the network become two picker entries, in name order` to fail — the second
   because the Sonos then appears twice.
4. In `DeviceDescription.parseDevice`, drop the `embedded` recursion (`embedded = emptyList()`).
   Expect `a sonos root device carries the media renderer inside its device list` and
   `a cast device is built from the sonos root...` and the four-device directory test to fail.
   **This is the "Sonos is missing from the picker" defect**, and it must be caught by three tests
   at once.
5. In `DeviceDescription.parse`, use `descriptionUrl` unconditionally instead of `URLBase`. Expect
   `a URLBase wins over the description url when the device sends one` to fail and every other
   resolution test to pass — which is what makes the two branches distinguishable.
6. In `CastDevice.from`, return the device unconditionally instead of requiring an `AVTransport`.
   Expect `a device with no AVTransport anywhere is not a cast device` and the four-device test to
   fail (the NAS reappears).
7. In `RendererDirectory.discover`, remove the `sortedWith`. Expect
   `the order is by name and not by arrival, proved by renaming one device` to fail. Note that the
   four-device test may still pass by luck; that is exactly why the rename test exists.
8. In `RendererStore.decode`, drop `limit = 3`. Expect `aNameContainingTheSeparatorSurvives` to
   fail.

- [ ] **Step 16: Record the probes and measure the floor**

Add mutations 1, 3, 4, 6 and 7 to `ci/mutation-probes.sh` as `discovery/*` entries. Then:

```bash
./gradlew :core:cast:test jacocoTestReport
```

Add to `:core:cast`'s BRANCH floor `includes`:
`"app.muplay.cast.discovery.SsdpSearch"`, `"app.muplay.cast.discovery.SsdpResponse"`,
`"app.muplay.cast.discovery.DeviceDescription"`, `"app.muplay.cast.discovery.UpnpDevice"`,
`"app.muplay.cast.discovery.CastDevice"`, `"app.muplay.cast.discovery.CastDevice*Companion"`,
`"app.muplay.cast.discovery.RendererDirectory"`. Read each measured ratio from
`core/cast/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml` and confirm it clears 0.90;
add the missing case rather than lowering the floor. `DatagramSsdpTransport` stays out of the
include list — its branches are socket timeouts and interface enumeration, and a floor over it
would be a number nothing could move.

`:core:database` gains a LINE floor entry for `app.muplay.database.RendererStore` with
`requiresInstrumentedData = true`, alongside the existing `CredentialStore` entries, because its
tests are on the device.

```bash
./gradlew :core:cast:test :app:testDebugUnitTest --tests '*ConventionTest*'
./gradlew jacocoJvmCoverageVerification
git add core/cast core/database build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): SSDP discovery, device descriptions, and Sonos's embedded renderer"
```

---

## Task 3: SOAP — the envelope, SOAPACTION's quotes, argument order, faults, and a fake renderer strict enough to reject

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/soap/XmlText.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/soap/SoapEnvelope.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpError.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpTime.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/soap/SoapClient.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/soap/XmlTextTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/soap/SoapEnvelopeTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/soap/UpnpTimeTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRenderer.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRendererStrictnessTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/soap/SoapClientTest.kt`
- Modify: `build.gradle.kts` (`:core:cast` floors)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `CastHttpClient`, `CastHttpResponse`, `HttpHeaders` (Task 1);
  `DeviceDescription.SERVICE_AV_TRANSPORT`, `SERVICE_RENDERING_CONTROL` (Task 2).
- Produces:
  - `object XmlText` with `fun escape(raw: String): String` and `fun unescape(text: String): String`
  - `data class SoapArgument(val name: String, val value: String)`
  - `object SoapEnvelope` with
    `fun render(serviceType: String, action: String, arguments: List<SoapArgument>): String`,
    `fun soapActionHeader(serviceType: String, action: String): String`,
    `fun parseResponse(action: String, xml: String): Map<String, String>`,
    `fun parseFault(xml: String): UpnpFault?`,
    `const val CONTENT_TYPE = "text/xml; charset=\"utf-8\""`
  - `data class UpnpFault(val errorCode: Int, val errorDescription: String?)`
  - `object UpnpError` with the named codes and `fun describe(code: Int): String`
  - `class UpnpErrorException(val action: String, val fault: UpnpFault) : IOException`
  - `class SoapTransportException(val action: String, val statusCode: Int, cause: Throwable?) : IOException`
  - `object UpnpTime` with `fun parseClock(value: String?): Long?`, `fun formatClock(millis: Long): String`,
    `fun formatDuration(millis: Long): String`, `const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"`
  - `class SoapClient(http: CastHttpClient)` with
    `suspend fun invoke(controlUrl: URI, serviceType: String, action: String, arguments: List<SoapArgument>): Map<String, String>`
  - test fixture `FakeRenderer` with `Strictness`, `Identity`, `start()`, `port`, `descriptionUrl`,
    `controlUrl`, `renderingControlUrl`, `soapRequests`, `mediaRequests`, `fetchesMedia`,
    `disappear()`, `awaitMediaRequest(timeoutMs)`, `close()`
- **Plan 4 interaction:** none.

### The four things a real device rejects, and why "the request was sent" proves none of them

`assertThat(fake.soapRequests).hasSize(1)` is a green assertion against every one of the following,
and a real Sonos answers every one of them with HTTP 500:

**1. An unquoted `SOAPACTION`.** The header value is `"urn:schemas-upnp-org:service:AVTransport:1#Play"`
**with the double quotes as part of the value**. SOAP 1.1 specifies them; Sonos enforces them. Sent
unquoted, some renderers accept it and some return `401 Invalid Action`, which is the worst
possible distribution: it works on the developer's device and fails on the user's.

**2. Arguments in the wrong order.** UPnP argument lists are **ordered** — the order is the one
declared in the service's SCPD, and many implementations parse positionally rather than by name.
`SetAVTransportURI` is `InstanceID`, `CurrentURI`, `CurrentURIMetaData`; swap the last two and a
strict device answers `402 Invalid Args` while a lenient one plays the metadata document as if it
were a URL. This is *the* place in this plan where "order is a property" is not a testing slogan
but the protocol.

Using a `List<SoapArgument>` rather than a `Map` is the structural half of the defence: a
`LinkedHashMap` happens to preserve insertion order, but nothing stops a caller passing a sorted
map, and nothing in the type says order matters. A list says it.

**3. Metadata that is escaped wrong.** `CurrentURIMetaData` carries a whole DIDL-Lite **document**
as the *text content* of an XML element, so it must be XML-escaped exactly once. Escaped zero
times, the envelope is not well-formed and the device answers 500 before it reads anything.
Escaped twice, the device receives the literal text `&lt;DIDL-Lite ...` and either shows the track
as "unknown" or refuses it. Both failures are silent from the caller's side.

**4. A URL with no file extension.** Spec §6: *"Sonos ... infers MIME **from the URL, not
`Content-Type`**"* and *"The URL's extension must match the real format"*. A `CurrentURI` ending in
`/media/9f2a` rather than `/media/9f2a.mp3` gets `714 Illegal MIME-type`. That is Task 4 and Task 6's
concern, and the fake enforces it from here so that neither task can regress it unnoticed.

**So every SOAP assertion in this plan is on the bytes the fake recorded**, and the fake is built
**strict by default** with its strictness under its own test. A fake that cannot say no is a fake
that proves nothing — and this project has shipped that exact defect, in a different form, five
times.

### Escaping, and why the order of two `replace` calls is a real bug

`raw.replace("<", "&lt;").replace("&", "&amp;")` is wrong, and it is wrong in a way that looks
right: it produces `&amp;lt;` for a `<`, because the second replacement rewrites the ampersand the
first one introduced. `&` must be replaced **first**. The test asserts it in the one way that
discriminates — by escaping a string that already contains an entity — because a test over
`"a & b"` alone passes both orders.

Five characters, and all five are needed: `&`, `<`, `>`, `"`, `'`. `>` is not strictly required in
text content, and `"`/`'` are not required outside attribute values, but a DIDL document is embedded
as text inside an attribute-bearing envelope and a track title really does contain apostrophes and
quotation marks. Escaping all five is one rule with no cases.

- [ ] **Step 1: Write the failing escaping test**

`core/cast/src/test/kotlin/app/muplay/cast/soap/XmlTextTest.kt`:

```kotlin
package app.muplay.cast.soap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XmlTextTest {

  @Test
  fun `each of the five characters is escaped`() {
    // One assertion per character, so an implementation missing one fails on that one rather than
    // on a compound string where the failure message names nothing useful.
    assertThat(XmlText.escape("&")).isEqualTo("&amp;")
    assertThat(XmlText.escape("<")).isEqualTo("&lt;")
    assertThat(XmlText.escape(">")).isEqualTo("&gt;")
    assertThat(XmlText.escape("\"")).isEqualTo("&quot;")
    assertThat(XmlText.escape("'")).isEqualTo("&apos;")
  }

  /**
   * The ordering bug, isolated. `replace("<", "&lt;").replace("&", "&amp;")` produces `&amp;lt;`
   * here and `&lt;` in every test that does not already contain an entity -- which is most of them.
   */
  @Test
  fun `the ampersand is replaced first, so an existing entity is escaped once and not twice`() {
    assertThat(XmlText.escape("&lt;")).isEqualTo("&amp;lt;")
    assertThat(XmlText.escape("&amp;")).isEqualTo("&amp;amp;")
    assertThat(XmlText.escape("a & b < c")).isEqualTo("a &amp; b &lt; c")
  }

  @Test
  fun `escaping is idempotent in the sense that matters, which is that it is not`() {
    // Stated as an assertion because "escape it again just in case" is the reflex that produces
    // `&amp;lt;DIDL-Lite`, and a reader needs to see that double-escaping is visible rather than
    // harmless.
    val once = XmlText.escape("<DIDL-Lite/>")
    assertThat(once).isEqualTo("&lt;DIDL-Lite/&gt;")
    assertThat(XmlText.escape(once)).isEqualTo("&amp;lt;DIDL-Lite/&amp;gt;")
  }

  @Test
  fun `text with nothing to escape comes back unchanged`() {
    assertThat(XmlText.escape("Track 1")).isEqualTo("Track 1")
    assertThat(XmlText.escape("")).isEmpty()
  }

  @Test
  fun `non-ascii text is left alone, because the document is utf-8`() {
    // Escaping these into numeric references would be legal and would also make every byte-exact
    // assertion in this plan wrong. The envelope declares utf-8 and means it.
    assertThat(XmlText.escape("Königin der Nacht")).isEqualTo("Königin der Nacht")
    assertThat(XmlText.escape("北国の春")).isEqualTo("北国の春")
  }

  @Test
  fun `a real track title with three of the five characters survives`() {
    assertThat(XmlText.escape("Rock & Roll <live> \"1971\""))
      .isEqualTo("Rock &amp; Roll &lt;live&gt; &quot;1971&quot;")
  }

  @Test
  fun `unescape reverses escape, including for the ampersand`() {
    // The round trip is what Task 4's DIDL test asserts across the whole envelope; this is the
    // unit of it. `unescape` must handle `&amp;` LAST for the mirror-image reason.
    listOf(
      "Rock & Roll <live> \"1971\"",
      "&lt;",
      "a & b",
      "Königin der Nacht",
      "",
    ).forEach { original ->
      assertThat(XmlText.unescape(XmlText.escape(original)))
        .describedAs("round trip of \"%s\"", original)
        .isEqualTo(original)
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails, then implement**

Run: `./gradlew :core:cast:test --tests '*XmlTextTest*'` — FAIL, `Unresolved reference: XmlText`.

`core/cast/src/main/kotlin/app/muplay/cast/soap/XmlText.kt`:

```kotlin
package app.muplay.cast.soap

/**
 * XML text escaping, in the one order that is correct.
 *
 * `&` **must** be replaced first. The obvious `replace("<", "&lt;").replace("&", "&amp;")` rewrites
 * the ampersand the first replacement just introduced and produces `&amp;lt;` -- and it produces
 * the right answer for every input that did not already contain an entity, which is most inputs,
 * which is why it survives review.
 *
 * All five characters are escaped, not the three that text content strictly requires: a DIDL-Lite
 * document is embedded as the text content of an element inside an attribute-bearing envelope, and
 * track titles really do contain apostrophes and quotation marks. One rule with no cases.
 *
 * Non-ASCII characters are **not** escaped. The envelope declares `utf-8` and the transport sends
 * `utf-8`; turning "Königin" into numeric references would be legal, would double the size of a
 * German library's metadata, and would make every byte-exact assertion in this plan wrong.
 */
object XmlText {

  fun escape(raw: String): String = raw
    .replace("&", "&amp;")   // first, always
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

  /** The mirror image: `&amp;` **last**, for exactly the same reason. */
  fun unescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")   // last, always
}
```

Run: `./gradlew :core:cast:test --tests '*XmlTextTest*'` — PASS, 7/7.

- [ ] **Step 3: Write the failing envelope test**

`core/cast/src/test/kotlin/app/muplay/cast/soap/SoapEnvelopeTest.kt`:

```kotlin
package app.muplay.cast.soap

import app.muplay.cast.discovery.DeviceDescription
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The SOAP envelope, byte for byte, and the SOAPACTION header, quotes included.
 *
 * Byte-exactness is the assertion because everything weaker is satisfied by an envelope a real
 * Sonos answers 500 to. `contains("SetAVTransportURI")` passes with the namespace wrong, the
 * arguments reordered and the metadata unescaped.
 */
class SoapEnvelopeTest {

  private val avTransport = DeviceDescription.SERVICE_AV_TRANSPORT

  @Test
  fun `the envelope is exactly this`() {
    val xml = SoapEnvelope.render(
      serviceType = avTransport,
      action = "Play",
      arguments = listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1")),
    )

    assertThat(xml).isEqualTo(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
        "<s:Body>" +
        "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
        "<InstanceID>0</InstanceID>" +
        "<Speed>1</Speed>" +
        "</u:Play>" +
        "</s:Body>" +
        "</s:Envelope>",
    )
  }

  @Test
  fun `the action name and the service namespace are the ones the caller gave`() {
    // Two observations of each, so neither can be a constant. `RenderingControl` really is a
    // different namespace on the same device, and mixing them up returns 401 from every action.
    val rendering = SoapEnvelope.render(
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "SetVolume",
      listOf(SoapArgument("InstanceID", "0")),
    )

    assertThat(rendering).contains(
      "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">",
    )
    assertThat(rendering).contains("</u:SetVolume>")
    assertThat(SoapEnvelope.render(avTransport, "Stop", emptyList()))
      .contains("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">")
  }

  /**
   * **Order is the protocol here, not a preference.** UPnP argument lists are ordered by the
   * service description, and implementations parse positionally. A strict device answers 402 to a
   * reordered list; a lenient one treats the metadata document as the URL.
   */
  @Test
  fun `arguments appear in the order they were given, and reordering them changes the bytes`() {
    val correct = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURI", "http://10.0.0.2:8080/media/a.mp3"),
        SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite/&gt;"),
      ),
    )
    val reordered = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite/&gt;"),
        SoapArgument("CurrentURI", "http://10.0.0.2:8080/media/a.mp3"),
      ),
    )

    assertThat(correct).contains(
      "<InstanceID>0</InstanceID>" +
        "<CurrentURI>http://10.0.0.2:8080/media/a.mp3</CurrentURI>" +
        "<CurrentURIMetaData>&lt;DIDL-Lite/&gt;</CurrentURIMetaData>",
    )
    // The renderer would not agree these are the same request, and neither does this assertion.
    // An implementation that sorted arguments, or that took a `Map` a caller had sorted, produces
    // identical output here and fails.
    assertThat(reordered).isNotEqualTo(correct)
  }

  @Test
  fun `an argument value is inserted verbatim, because escaping is the caller's decision`() {
    // Deliberate: `CurrentURIMetaData` arrives ALREADY escaped from `DidlLite` (Task 4), and
    // escaping it again here is the `&amp;lt;DIDL-Lite` defect. The envelope's job is framing.
    val xml = SoapEnvelope.render(
      avTransport,
      "SetAVTransportURI",
      listOf(SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite&gt;")),
    )

    assertThat(xml).contains("<CurrentURIMetaData>&lt;DIDL-Lite&gt;</CurrentURIMetaData>")
    assertThat(xml).doesNotContain("&amp;lt;")
  }

  @Test
  fun `an action with no arguments is still a well-formed empty element pair`() {
    assertThat(SoapEnvelope.render(avTransport, "Stop", emptyList()))
      .contains("<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"></u:Stop>")
  }

  /**
   * The quotes are part of the header **value**. This is the single most commonly-omitted detail in
   * a hand-written UPnP client, and the failure it causes is distributed: some renderers accept it
   * and Sonos does not.
   */
  @Test
  fun `the soapaction header value is quoted`() {
    assertThat(SoapEnvelope.soapActionHeader(avTransport, "Play"))
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
    assertThat(SoapEnvelope.soapActionHeader(DeviceDescription.SERVICE_RENDERING_CONTROL, "SetVolume"))
      .isEqualTo("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"")
    // Stated separately, because `isEqualTo` on a string with quotes in it is easy to misread.
    assertThat(SoapEnvelope.soapActionHeader(avTransport, "Play")).startsWith("\"").endsWith("\"")
  }

  @Test
  fun `the content type is the one soap requires, charset included`() {
    assertThat(SoapEnvelope.CONTENT_TYPE).isEqualTo("text/xml; charset=\"utf-8\"")
  }

  @Test
  fun `a response's out arguments are read by name`() {
    val response = """
      <?xml version="1.0"?>
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body>
          <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <Track>1</Track>
            <TrackDuration>0:05:00</TrackDuration>
            <TrackMetaData>&lt;DIDL-Lite/&gt;</TrackMetaData>
            <TrackURI>http://10.0.0.2:8080/media/a.mp3</TrackURI>
            <RelTime>0:01:23</RelTime>
            <AbsTime>NOT_IMPLEMENTED</AbsTime>
            <RelCount>2147483647</RelCount>
            <AbsCount>2147483647</AbsCount>
          </u:GetPositionInfoResponse>
        </s:Body>
      </s:Envelope>
    """.trimIndent()

    val out = SoapEnvelope.parseResponse("GetPositionInfo", response)

    // The exact key set, in document order, and then the values. `containsKey("RelTime")` alone
    // would pass with every other field silently dropped.
    assertThat(out.keys).containsExactly(
      "Track", "TrackDuration", "TrackMetaData", "TrackURI", "RelTime", "AbsTime", "RelCount", "AbsCount",
    )
    assertThat(out["RelTime"]).isEqualTo("0:01:23")
    assertThat(out["TrackDuration"]).isEqualTo("0:05:00")
    assertThat(out["AbsTime"]).isEqualTo("NOT_IMPLEMENTED")
    assertThat(out["TrackURI"]).isEqualTo("http://10.0.0.2:8080/media/a.mp3")
    // Entity-decoded on the way out: the metadata was escaped once by the device, and the parser
    // returns what it meant rather than what it wrote.
    assertThat(out["TrackMetaData"]).isEqualTo("<DIDL-Lite/>")
  }

  @Test
  fun `a response for a different action is not accepted as this one`() {
    // A renderer answering the wrong response element is a device bug, and reading it anyway would
    // produce a position taken from a volume query. Loud is better.
    val wrong = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
      "<u:GetVolumeResponse xmlns:u=\"x\"><CurrentVolume>30</CurrentVolume></u:GetVolumeResponse>" +
      "</s:Body></s:Envelope>"

    assertThat(SoapEnvelope.parseResponse("GetPositionInfo", wrong)).isEmpty()
  }

  @Test
  fun `a fault is parsed into its upnp error code and description`() {
    val fault = """
      <?xml version="1.0"?>
      <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
        <s:Body>
          <s:Fault>
            <faultcode>s:Client</faultcode>
            <faultstring>UPnPError</faultstring>
            <detail>
              <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                <errorCode>714</errorCode>
                <errorDescription>Illegal MIME-type</errorDescription>
              </UPnPError>
            </detail>
          </s:Fault>
        </s:Body>
      </s:Envelope>
    """.trimIndent()

    val parsed = SoapEnvelope.parseFault(fault)!!

    assertThat(parsed.errorCode).isEqualTo(714)
    assertThat(parsed.errorDescription).isEqualTo("Illegal MIME-type")
  }

  @Test
  fun `a second fault code parses to a second number`() {
    // The observation that stops `errorCode` being 714 forever.
    assertThat(
      SoapEnvelope.parseFault(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
          "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode>" +
          "</UPnPError></detail></s:Fault></s:Body></s:Envelope>",
      )!!.errorCode,
    ).isEqualTo(701)
  }

  @Test
  fun `a successful response is not a fault`() {
    assertThat(
      SoapEnvelope.parseFault(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
          "<u:PlayResponse xmlns:u=\"x\"/></s:Body></s:Envelope>",
      ),
    ).isNull()
  }

  @Test
  fun `the named error codes say what they mean`() {
    // These strings reach a user through the cast picker (Task 10), so they are asserted rather
    // than left to whatever `toString` a future refactor produces.
    assertThat(UpnpError.describe(UpnpError.INVALID_ACTION)).isEqualTo("Invalid Action")
    assertThat(UpnpError.describe(UpnpError.TRANSITION_NOT_AVAILABLE)).isEqualTo("Transition not available")
    assertThat(UpnpError.describe(UpnpError.ILLEGAL_MIME_TYPE)).isEqualTo("Illegal MIME-type")
    assertThat(UpnpError.describe(UpnpError.SEEK_MODE_NOT_SUPPORTED)).isEqualTo("Seek mode not supported")
    assertThat(UpnpError.describe(UpnpError.RESOURCE_NOT_FOUND)).isEqualTo("Resource not found")
    assertThat(UpnpError.describe(UpnpError.INVALID_INSTANCE_ID)).isEqualTo("Invalid InstanceID")
    // And the numbers themselves, because a wrong constant is a wrong branch in Task 5.
    assertThat(UpnpError.INVALID_ACTION).isEqualTo(401)
    assertThat(UpnpError.INVALID_ARGS).isEqualTo(402)
    assertThat(UpnpError.ACTION_FAILED).isEqualTo(501)
    assertThat(UpnpError.TRANSITION_NOT_AVAILABLE).isEqualTo(701)
    assertThat(UpnpError.SEEK_MODE_NOT_SUPPORTED).isEqualTo(710)
    assertThat(UpnpError.ILLEGAL_SEEK_TARGET).isEqualTo(711)
    assertThat(UpnpError.ILLEGAL_MIME_TYPE).isEqualTo(714)
    assertThat(UpnpError.RESOURCE_NOT_FOUND).isEqualTo(716)
    assertThat(UpnpError.INVALID_INSTANCE_ID).isEqualTo(718)
    // An unknown code is reported as itself rather than as "unknown", so a device's own number
    // reaches the log.
    assertThat(UpnpError.describe(999)).contains("999")
  }
}
```

- [ ] **Step 4: Run it to verify it fails, then implement the envelope, the errors and the clock**

Run: `./gradlew :core:cast:test --tests '*SoapEnvelopeTest*'` — FAIL.

`core/cast/src/main/kotlin/app/muplay/cast/soap/SoapEnvelope.kt`:

```kotlin
package app.muplay.cast.soap

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * One `in` argument of a UPnP action.
 *
 * A `List<SoapArgument>` rather than a `Map<String, String>` in every signature that takes these,
 * and that is structural rather than stylistic: **UPnP argument order is part of the protocol**.
 * The order is the one the service description declares, and many implementations parse
 * positionally. A strict device answers `402 Invalid Args` to a reordered `SetAVTransportURI`; a
 * lenient one plays the metadata document as if it were the URL. A `LinkedHashMap` happens to
 * preserve insertion order -- and nothing stops a caller handing over a sorted map, and nothing in
 * the type says it would matter.
 */
data class SoapArgument(val name: String, val value: String)

/**
 * SOAP 1.1 envelopes for UPnP control, and the parsing of what comes back.
 *
 * Rendered by string building rather than by a DOM serialiser, deliberately. The document is
 * fixed-shape and tiny, a serialiser's output varies with its implementation (self-closing tags,
 * attribute order, whether an XML declaration is emitted), and **this plan asserts the envelope
 * byte for byte** -- which is only possible if the bytes are chosen here rather than by whichever
 * `TransformerFactory` the platform supplies. Parsing is a real DOM parse, because the input comes
 * from a device.
 */
object SoapEnvelope {

  /** SOAP requires this exact `Content-Type`, quoted charset included. */
  const val CONTENT_TYPE: String = "text/xml; charset=\"utf-8\""

  fun render(serviceType: String, action: String, arguments: List<SoapArgument>): String =
    buildString {
      append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
      append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
      append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
      append("<s:Body>")
      append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
      // In order. Not sorted, not a set, not a map.
      arguments.forEach { (name, value) ->
        append('<').append(name).append('>').append(value).append("</").append(name).append('>')
      }
      append("</u:").append(action).append('>')
      append("</s:Body>")
      append("</s:Envelope>")
    }

  /**
   * The `SOAPACTION` header value, **with its quotes**.
   *
   * The quotes are part of the value, not of the Kotlin literal. Sent unquoted, a conformant device
   * answers `401 Invalid Action` -- and a lenient one does not, which is why this is the detail
   * most likely to work on the developer's speaker and fail on the user's.
   */
  fun soapActionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

  /**
   * The `out` arguments of `<action>Response`, in document order, entity-decoded.
   *
   * Returns an empty map when the body does not carry a response for [action] -- including when it
   * carries a response for a *different* action. Reading whatever element happened to be there
   * would turn a device bug into a position value taken from a volume query.
   */
  fun parseResponse(action: String, xml: String): Map<String, String> {
    val body = bodyOf(xml) ?: return emptyMap()
    val response = childElements(body).firstOrNull {
      it.nodeName.substringAfterLast(':') == "${action}Response"
    } ?: return emptyMap()

    return childElements(response).associate { child ->
      child.nodeName.substringAfterLast(':') to child.textContent.orEmpty()
    }
  }

  /**
   * The UPnP error inside a SOAP fault, or `null` when the body is not a fault.
   *
   * Every UPnP error arrives as **HTTP 500 with a body**, which is why [app.muplay.cast.http.CastHttpClient]
   * returns 5xx rather than throwing: throwing there would turn "Sonos said 714, illegal MIME type"
   * into "the network failed".
   */
  fun parseFault(xml: String): UpnpFault? {
    val body = bodyOf(xml) ?: return null
    val fault = childElements(body).firstOrNull { it.nodeName.substringAfterLast(':') == "Fault" }
      ?: return null
    val detail = descendant(fault, "UPnPError") ?: return UpnpFault(UpnpError.ACTION_FAILED, null)
    val code = descendant(detail, "errorCode")?.textContent?.trim()?.toIntOrNull()
      ?: return UpnpFault(UpnpError.ACTION_FAILED, null)
    return UpnpFault(code, descendant(detail, "errorDescription")?.textContent?.trim())
  }

  private fun bodyOf(xml: String): Element? {
    val document = runCatching {
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        listOf(
          "http://apache.org/xml/features/disallow-doctype-decl" to true,
          "http://xml.org/sax/features/external-general-entities" to false,
          "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
      }.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    }.getOrNull() ?: return null
    val root = document.documentElement ?: return null
    return childElements(root).firstOrNull { it.nodeName.substringAfterLast(':') == "Body" }
  }

  private fun childElements(parent: Element): List<Element> {
    val children = ArrayList<Element>()
    var node: Node? = parent.firstChild
    while (node != null) {
      (node as? Element)?.let(children::add)
      node = node.nextSibling
    }
    return children
  }

  /** First descendant with this local name, at any depth -- fault details nest inconsistently. */
  private fun descendant(parent: Element, localName: String): Element? {
    childElements(parent).forEach { child ->
      if (child.nodeName.substringAfterLast(':') == localName) return child
      descendant(child, localName)?.let { return it }
    }
    return null
  }
}
```

`core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpError.kt`:

```kotlin
package app.muplay.cast.soap

import java.io.IOException

/** The `<UPnPError>` inside a SOAP fault. */
data class UpnpFault(val errorCode: Int, val errorDescription: String?)

/** A renderer refused an action, and said why. Not a transport failure -- see [SoapTransportException]. */
class UpnpErrorException(val action: String, val fault: UpnpFault) : IOException(
  "$action was refused: UPnP error ${fault.errorCode} " +
    "(${fault.errorDescription ?: UpnpError.describe(fault.errorCode)})",
)

/** The renderer could not be reached, or answered something this client cannot read. */
class SoapTransportException(val action: String, val statusCode: Int, cause: Throwable? = null) :
  IOException("$action failed at the transport: HTTP $statusCode", cause)

/**
 * The UPnP error codes this client branches on or reports, from the UPnP Device Architecture's
 * common set and the `AVTransport:1` service template.
 *
 * These strings reach a user, through the cast picker's failure line. That is why they are
 * constants with assertions rather than whatever a future `toString` produces.
 */
object UpnpError {

  /** Common errors, UPnP Device Architecture. */
  const val INVALID_ACTION: Int = 401
  const val INVALID_ARGS: Int = 402
  const val ACTION_FAILED: Int = 501

  /** `AVTransport:1` service-specific errors. */
  const val TRANSITION_NOT_AVAILABLE: Int = 701
  const val NO_CONTENTS: Int = 702
  const val READ_ERROR: Int = 703
  const val FORMAT_NOT_SUPPORTED: Int = 704
  const val TRANSPORT_IS_LOCKED: Int = 705
  const val SEEK_MODE_NOT_SUPPORTED: Int = 710
  const val ILLEGAL_SEEK_TARGET: Int = 711
  const val ILLEGAL_MIME_TYPE: Int = 714
  const val RESOURCE_NOT_FOUND: Int = 716
  const val PLAY_SPEED_NOT_SUPPORTED: Int = 717
  const val INVALID_INSTANCE_ID: Int = 718

  private val DESCRIPTIONS: Map<Int, String> = mapOf(
    INVALID_ACTION to "Invalid Action",
    INVALID_ARGS to "Invalid Args",
    ACTION_FAILED to "Action Failed",
    TRANSITION_NOT_AVAILABLE to "Transition not available",
    NO_CONTENTS to "No contents",
    READ_ERROR to "Read error",
    FORMAT_NOT_SUPPORTED to "Format not supported for playback",
    TRANSPORT_IS_LOCKED to "Transport is locked",
    SEEK_MODE_NOT_SUPPORTED to "Seek mode not supported",
    ILLEGAL_SEEK_TARGET to "Illegal seek target",
    ILLEGAL_MIME_TYPE to "Illegal MIME-type",
    RESOURCE_NOT_FOUND to "Resource not found",
    PLAY_SPEED_NOT_SUPPORTED to "Play speed not supported",
    INVALID_INSTANCE_ID to "Invalid InstanceID",
  )

  /** An unknown code is reported **as itself**, so a device's own number reaches the log. */
  fun describe(code: Int): String = DESCRIPTIONS[code] ?: "UPnP error $code"
}
```

`core/cast/src/main/kotlin/app/muplay/cast/soap/UpnpTime.kt` — with
`core/cast/src/test/kotlin/app/muplay/cast/soap/UpnpTimeTest.kt` written first:

```kotlin
package app.muplay.cast.soap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `H:MM:SS` in both directions.
 *
 * Small, and worth its own class because every value it returns is a **position**, and a position
 * that is wrong by a factor or an offset is the silent-wrong-answer class this project treats as
 * the worst kind. The spec already records one of these hazards elsewhere -- `createBookmark`'s
 * milliseconds against `bookmarkPosition`'s seconds, "getting that backwards puts every resume out
 * by 1000x". This is the same shape of mistake in a different unit.
 */
class UpnpTimeTest {

  @Test
  fun `a clock value parses to milliseconds`() {
    // Five observations spanning hours, minutes and seconds independently, so no single constant
    // and no wrong multiplier satisfies them.
    assertThat(UpnpTime.parseClock("0:00:00")).isEqualTo(0L)
    assertThat(UpnpTime.parseClock("0:00:01")).isEqualTo(1_000L)
    assertThat(UpnpTime.parseClock("0:01:00")).isEqualTo(60_000L)
    assertThat(UpnpTime.parseClock("1:00:00")).isEqualTo(3_600_000L)
    assertThat(UpnpTime.parseClock("1:02:03")).isEqualTo(3_723_000L)
  }

  @Test
  fun `both the padded and unpadded hour forms parse`() {
    // Sonos sends "0:01:23"; several DLNA renderers send "00:01:23". Handling one and not the
    // other produces a seek bar that works on one brand of speaker.
    assertThat(UpnpTime.parseClock("00:01:23")).isEqualTo(83_000L)
    assertThat(UpnpTime.parseClock("0:01:23")).isEqualTo(83_000L)
    assertThat(UpnpTime.parseClock("10:01:23")).isEqualTo(36_083_000L)
  }

  @Test
  fun `a fractional second is parsed rather than making the whole value unreadable`() {
    assertThat(UpnpTime.parseClock("0:00:01.500")).isEqualTo(1_500L)
    assertThat(UpnpTime.parseClock("0:00:01.5")).isEqualTo(1_500L)
    assertThat(UpnpTime.parseClock("0:02:03.250")).isEqualTo(123_250L)
  }

  @Test
  fun `NOT_IMPLEMENTED and the other unusable values are null, not zero`() {
    // Null and zero are different facts. `AbsTime` is `NOT_IMPLEMENTED` on most renderers, and a
    // player that read it as 0 would jump the seek bar to the start once a second.
    assertThat(UpnpTime.parseClock("NOT_IMPLEMENTED")).isNull()
    assertThat(UpnpTime.parseClock("")).isNull()
    assertThat(UpnpTime.parseClock(null)).isNull()
    assertThat(UpnpTime.parseClock("garbage")).isNull()
    assertThat(UpnpTime.parseClock("1:2")).isNull()
    assertThat(UpnpTime.parseClock("a:b:c")).isNull()
  }

  @Test
  fun `formatting a clock value is the inverse of parsing it`() {
    assertThat(UpnpTime.formatClock(0L)).isEqualTo("0:00:00")
    assertThat(UpnpTime.formatClock(1_000L)).isEqualTo("0:00:01")
    assertThat(UpnpTime.formatClock(83_000L)).isEqualTo("0:01:23")
    assertThat(UpnpTime.formatClock(3_723_000L)).isEqualTo("1:02:03")
    assertThat(UpnpTime.formatClock(36_083_000L)).isEqualTo("10:01:23")
  }

  @Test
  fun `formatting truncates rather than rounding, because a seek target must not overshoot`() {
    // Rounding 4999 ms up to 0:00:05 would seek past where the user asked. Truncation is the safe
    // direction and is stated as an assertion rather than left to `Math.round`'s default.
    assertThat(UpnpTime.formatClock(4_999L)).isEqualTo("0:00:04")
    assertThat(UpnpTime.formatClock(5_000L)).isEqualTo("0:00:05")
  }

  @Test
  fun `a negative position formats as zero rather than as a negative clock`() {
    // `Player.currentPosition` can be `C.TIME_UNSET` and arithmetic on it goes negative. A
    // `Seek` target of "-1:-1:-1" is a 711 from the device; "0:00:00" is a correct answer.
    assertThat(UpnpTime.formatClock(-1L)).isEqualTo("0:00:00")
    assertThat(UpnpTime.formatClock(Long.MIN_VALUE / 2)).isEqualTo("0:00:00")
  }

  @Test
  fun `a duration for DIDL carries milliseconds`() {
    // A different format from `formatClock`, on purpose: DIDL's `res@duration` conventionally
    // carries three decimal places and renderers use it to size the progress bar.
    assertThat(UpnpTime.formatDuration(0L)).isEqualTo("0:00:00.000")
    assertThat(UpnpTime.formatDuration(83_000L)).isEqualTo("0:01:23.000")
    assertThat(UpnpTime.formatDuration(83_250L)).isEqualTo("0:01:23.250")
    assertThat(UpnpTime.formatDuration(3_723_004L)).isEqualTo("1:02:03.004")
  }
}
```

```kotlin
package app.muplay.cast.soap

import java.util.Locale

/**
 * The `H:MM:SS` clock UPnP uses for positions, durations and seek targets.
 *
 * Every value here is a **position**, so the failure mode is a silent wrong answer -- the class
 * this project treats as the worst there is. The spec records the same shape of mistake elsewhere:
 * `createBookmark.position` in milliseconds against `bookmarkPosition` in seconds, where "getting
 * that backwards puts every resume out by 1000x".
 */
object UpnpTime {

  /** What a renderer sends for a field it does not implement. `AbsTime` is usually this. */
  const val NOT_IMPLEMENTED: String = "NOT_IMPLEMENTED"

  private val CLOCK = Regex("""^(\d{1,3}):([0-5]\d):([0-5]\d)(?:\.(\d{1,3}))?$""")

  /**
   * Milliseconds, or `null` when the value is not a clock this client will act on.
   *
   * `null` rather than `0`, because they are different facts: `NOT_IMPLEMENTED` means "I do not
   * know", and a player that read it as zero would drag the seek bar back to the start once a
   * second.
   */
  fun parseClock(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || text == NOT_IMPLEMENTED) return null
    val match = CLOCK.matchEntire(text) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val millis = when (fraction.length) {
      0 -> 0L
      // ".5" is five hundred milliseconds, ".50" is five hundred, ".500" is five hundred.
      1 -> fraction.toLong() * 100
      2 -> fraction.toLong() * 10
      else -> fraction.toLong()
    }
    return hours.toLong() * 3_600_000 + minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis
  }

  /**
   * `H:MM:SS`, the form Sonos accepts as a `REL_TIME` seek target.
   *
   * **Truncating**, not rounding: rounding 4999 ms up seeks past where the user asked, and a seek
   * that overshoots is a seek the user has to correct. Negative input formats as zero rather than
   * as a negative clock, because `Player.currentPosition` arithmetic can go negative and a device
   * answers `711 Illegal seek target` to a clock with a minus sign in it.
   */
  fun formatClock(millis: Long): String {
    val total = (millis.coerceAtLeast(0L)) / 1_000
    return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3_600, (total / 60) % 60, total % 60)
  }

  /** `H:MM:SS.mmm`, the form DIDL-Lite's `res@duration` conventionally carries. */
  fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val total = safe / 1_000
    return String.format(
      Locale.ROOT,
      "%d:%02d:%02d.%03d",
      total / 3_600, (total / 60) % 60, total % 60, safe % 1_000,
    )
  }
}
```

Run: `./gradlew :core:cast:test --tests '*SoapEnvelopeTest*' --tests '*UpnpTimeTest*'` — PASS,
13 + 8.

- [ ] **Step 5: Build the fake renderer, strict by default**

`core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRenderer.kt`:

```kotlin
package app.muplay.cast.fake

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpWire
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpTime
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One SOAP request, as bytes, so a test may assert on what was actually sent. */
class RecordedSoap(val headBytes: ByteArray, val bodyBytes: ByteArray) {
  val headText: String get() = String(headBytes, Charsets.US_ASCII)
  val bodyText: String get() = String(bodyBytes, Charsets.UTF_8)

  /** The raw `SOAPACTION` header **value**, quotes included, exactly as it arrived. */
  val rawSoapAction: String?
    get() = headText.lineSequence()
      .firstOrNull { it.startsWith("SOAPACTION:", ignoreCase = true) }
      ?.substringAfter(':')?.trim()

  val action: String? get() = rawSoapAction?.trim('"')?.substringAfterLast('#')

  /** The `in` arguments, **in the order they arrived**, so ordering can be asserted. */
  val arguments: List<Pair<String, String>>
    get() = Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
      .findAll(bodyText.substringAfter("<s:Body>").substringBefore("</s:Body>"))
      .map { it.groupValues[1] to it.groupValues[2] }
      .toList()
}

/** One media fetch the renderer made, as the proxy saw it. */
data class RecordedMedia(val method: String, val target: String, val range: String?)

/**
 * **A real UPnP MediaRenderer, in this process, on loopback.**
 *
 * Spec section 10 files this at rung 2 of the test hierarchy -- *"an in-process **real** UPnP
 * renderer"* -- not at rung 4 with the fakes, and this class is written to deserve that. It is a
 * real `ServerSocket` speaking real HTTP/1.1 and real SOAP, serving a real device description, and
 * running a real transport state machine. The only thing about it that is not real is the
 * loudspeaker.
 *
 * **It is strict by default, and its strictness has its own test class**
 * ([FakeRendererStrictnessTest]). That is deliberate and it is the point: a fake that accepts
 * everything executes no rejection path, so the client's error handling is never exercised and the
 * fake's own permissiveness is invisible. Each knob in [Strictness] corresponds to something a
 * real Sonos rejects with the UPnP error named beside it. **Turn one off only in a test whose
 * subject is the lenient behaviour, and never as a way to make a red test green.**
 */
class FakeRenderer(
  private val strictness: Strictness = Strictness(),
  private val identity: Identity = Identity(),
) : Closeable {

  data class Strictness(
    /** SOAP 1.1 quotes the `SOAPACTION` value. Sonos enforces it. Violation: 401. */
    val requireQuotedSoapAction: Boolean = true,
    /** UPnP argument lists are ordered by the service description. Violation: 402. */
    val requireArgumentOrder: Boolean = true,
    /** Spec section 6: *"DIDL-Lite mandatory"*. Violation: 714. */
    val requireNonEmptyMetadata: Boolean = true,
    /** Spec section 6: Sonos infers MIME from the URL. Violation: 714. */
    val requireUrlExtension: Boolean = true,
    /** Only `InstanceID` 0 exists on a single-zone renderer. Violation: 718. */
    val requireInstanceIdZero: Boolean = true,
    /** What `A_ARG_TYPE_SeekMode` allows. Anything else: 710. */
    val supportedSeekModes: List<String> = listOf("REL_TIME"),
    /** Spec section 4: *"Never Opus. Sonos cannot decode it."* Violation: 714. */
    val rejectedMimeTypes: Set<String> = setOf("audio/ogg", "audio/opus", "audio/webm"),
  )

  data class Identity(
    val udn: String = "uuid:RINCON_FAKE0000001400",
    val friendlyName: String = "Fake Speaker",
    val manufacturer: String = "Sonos, Inc.",
    val modelName: String = "Fake One",
    val hasRenderingControl: Boolean = true,
    /** `true` reproduces Sonos's shape: the renderer nested inside a `ZonePlayer`'s `deviceList`. */
    val embedRenderer: Boolean = true,
    /** When set, `GetPositionInfo` reports this as `TrackURI` -- the Sonos group-follower case. */
    val followingCoordinator: String? = null,
  )

  private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
  private val soap = CopyOnWriteArrayList<RecordedSoap>()
  private val media = CopyOnWriteArrayList<RecordedMedia>()
  private val firstMedia = CountDownLatch(1)

  /** Whether the renderer actually fetches `CurrentURI` on `Play`. Task 7 turns this off. */
  @Volatile var fetchesMedia: Boolean = true

  @Volatile private var currentUri: String? = null
  @Volatile private var currentMetadata: String = ""
  @Volatile private var transportState: String = "STOPPED"
  @Volatile private var positionMs: Long = 0L
  @Volatile private var durationMs: Long = 0L
  @Volatile private var volume: Int = 30
  @Volatile private var muted: Boolean = false

  val port: Int get() = server.localPort
  val soapRequests: List<RecordedSoap> get() = soap.toList()
  val mediaRequests: List<RecordedMedia> get() = media.toList()

  val descriptionUrl: URI get() = URI("http://127.0.0.1:$port/xml/device_description.xml")
  val controlUrl: URI get() = URI("http://127.0.0.1:$port/MediaRenderer/AVTransport/Control")
  val renderingControlUrl: URI get() = URI("http://127.0.0.1:$port/MediaRenderer/RenderingControl/Control")

  fun start(): Int {
    thread(isDaemon = true, name = "fake-renderer") {
      while (!server.isClosed) {
        val connection = runCatching { server.accept() }.getOrNull() ?: continue
        thread(isDaemon = true) { runCatching { serve(connection) }; runCatching { connection.close() } }
      }
    }
    return port
  }

  /** Stops answering, without a clean shutdown -- what a speaker losing power looks like. */
  fun disappear() = server.close()

  fun awaitMediaRequest(timeoutMs: Long): RecordedMedia? =
    if (firstMedia.await(timeoutMs, TimeUnit.MILLISECONDS)) media.firstOrNull() else null

  /** Advances the renderer's own clock, as if audio had played. */
  fun advance(millis: Long) { positionMs += millis }

  fun currentTransportState(): String = transportState

  override fun close() = server.close()

  // ---- the server --------------------------------------------------------------------------

  private fun serve(connection: Socket) {
    val input = connection.getInputStream()
    val head = HttpWire.readRequestHead(input)
    val body = head.headers.contentLength()?.let { input.readNBytes(it.toInt()) } ?: ByteArray(0)

    val response = when {
      head.target.endsWith("device_description.xml") -> ok("text/xml", description())
      head.target.endsWith("AVTransport1.xml") -> ok("text/xml", avTransportScpd())
      head.target.contains("AVTransport/Control") -> control(head.headers, body, avTransport = true)
      head.target.contains("RenderingControl/Control") -> control(head.headers, body, avTransport = false)
      else -> HttpWire.renderResponseHead(404, "Not Found", HttpHeaders.of("Content-Length" to "0"))
    }
    connection.getOutputStream().apply { write(response); flush() }
  }

  private fun ok(contentType: String, body: String): ByteArray {
    val bytes = body.toByteArray(Charsets.UTF_8)
    return HttpWire.renderResponseHead(
      HttpURLConnection.HTTP_OK,
      "OK",
      HttpHeaders.of("Content-Type" to contentType, "Content-Length" to "${bytes.size}"),
    ) + bytes
  }

  private fun fault(code: Int): ByteArray {
    val body = "<?xml version=\"1.0\"?>" +
      "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
      "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
      "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
      "<errorCode>$code</errorCode><errorDescription>${UpnpError.describe(code)}</errorDescription>" +
      "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    val bytes = body.toByteArray(Charsets.UTF_8)
    return HttpWire.renderResponseHead(
      HttpURLConnection.HTTP_INTERNAL_ERROR,
      "Internal Server Error",
      HttpHeaders.of("Content-Type" to "text/xml", "Content-Length" to "${bytes.size}"),
    ) + bytes
  }

  private fun control(headers: HttpHeaders, body: ByteArray, avTransport: Boolean): ByteArray {
    val recorded = RecordedSoap(renderHeadForRecording(headers), body)
    soap += recorded

    val raw = recorded.rawSoapAction
      ?: return fault(UpnpError.INVALID_ACTION)
    // Strictness 1: the quotes are part of the value.
    if (strictness.requireQuotedSoapAction && !(raw.startsWith("\"") && raw.endsWith("\""))) {
      return fault(UpnpError.INVALID_ACTION)
    }
    val action = raw.trim('"').substringAfterLast('#')
    val arguments = recorded.arguments

    // Strictness 5: only instance 0 exists.
    val instance = arguments.firstOrNull { it.first == "InstanceID" }?.second
    if (strictness.requireInstanceIdZero && instance != null && instance != "0") {
      return fault(UpnpError.INVALID_INSTANCE_ID)
    }

    return if (avTransport) avTransportAction(action, arguments) else renderingAction(action, arguments)
  }

  private fun avTransportAction(action: String, arguments: List<Pair<String, String>>): ByteArray =
    when (action) {
      "SetAVTransportURI" -> {
        // Strictness 2: the declared order is InstanceID, CurrentURI, CurrentURIMetaData.
        if (strictness.requireArgumentOrder &&
          arguments.map { it.first } != listOf("InstanceID", "CurrentURI", "CurrentURIMetaData")
        ) {
          return fault(UpnpError.INVALID_ARGS)
        }
        val uri = arguments.firstOrNull { it.first == "CurrentURI" }?.second.orEmpty()
        val metadata = arguments.firstOrNull { it.first == "CurrentURIMetaData" }?.second.orEmpty()
        // Strictness 3: spec section 6, "DIDL-Lite mandatory".
        if (strictness.requireNonEmptyMetadata && metadata.isBlank()) return fault(UpnpError.ILLEGAL_MIME_TYPE)
        // ...and it must be escaped exactly once: a device sees `&lt;DIDL-Lite`, never `<DIDL-Lite`
        // (which would have broken the envelope) and never `&amp;lt;DIDL-Lite` (double-escaped).
        if (strictness.requireNonEmptyMetadata && !metadata.startsWith("&lt;DIDL-Lite")) {
          return fault(UpnpError.ILLEGAL_MIME_TYPE)
        }
        // Strictness 4: Sonos infers MIME from the URL's extension.
        val extension = uri.substringAfterLast('/').substringAfterLast('.', "")
        if (strictness.requireUrlExtension && extension.isEmpty()) return fault(UpnpError.ILLEGAL_MIME_TYPE)
        // Strictness 6: never Opus.
        val declaredMime = Regex("protocolInfo=&quot;http-get:\\*:([^:]+):").find(metadata)?.groupValues?.get(1)
        if (declaredMime != null && declaredMime in strictness.rejectedMimeTypes) {
          return fault(UpnpError.ILLEGAL_MIME_TYPE)
        }
        currentUri = uri
        currentMetadata = metadata
        durationMs = UpnpTime.parseClock(
          Regex("duration=&quot;([^&]+)&quot;").find(metadata)?.groupValues?.get(1),
        ) ?: 0L
        positionMs = 0L
        transportState = "STOPPED"
        ok("text/xml", responseEnvelope("SetAVTransportURI", emptyList()))
      }

      "Play" -> {
        if (currentUri == null) return fault(UpnpError.TRANSITION_NOT_AVAILABLE)
        // A real Sonos requires Speed, and requires it to be "1".
        val speed = arguments.firstOrNull { it.first == "Speed" }?.second
        if (speed == null) return fault(UpnpError.INVALID_ARGS)
        if (speed != "1") return fault(UpnpError.PLAY_SPEED_NOT_SUPPORTED)
        transportState = "PLAYING"
        if (fetchesMedia) fetchMedia(currentUri!!)
        ok("text/xml", responseEnvelope("Play", emptyList()))
      }

      "Pause" -> { transportState = "PAUSED_PLAYBACK"; ok("text/xml", responseEnvelope("Pause", emptyList())) }
      "Stop" -> { transportState = "STOPPED"; ok("text/xml", responseEnvelope("Stop", emptyList())) }

      "Seek" -> {
        val unit = arguments.firstOrNull { it.first == "Unit" }?.second.orEmpty()
        if (unit !in strictness.supportedSeekModes) return fault(UpnpError.SEEK_MODE_NOT_SUPPORTED)
        val target = UpnpTime.parseClock(arguments.firstOrNull { it.first == "Target" }?.second)
          ?: return fault(UpnpError.ILLEGAL_SEEK_TARGET)
        if (durationMs > 0 && target > durationMs) return fault(UpnpError.ILLEGAL_SEEK_TARGET)
        positionMs = target
        ok("text/xml", responseEnvelope("Seek", emptyList()))
      }

      "GetTransportInfo" -> ok(
        "text/xml",
        responseEnvelope(
          "GetTransportInfo",
          listOf(
            "CurrentTransportState" to transportState,
            "CurrentTransportStatus" to "OK",
            "CurrentSpeed" to "1",
          ),
        ),
      )

      "GetPositionInfo" -> ok(
        "text/xml",
        responseEnvelope(
          "GetPositionInfo",
          listOf(
            "Track" to "1",
            "TrackDuration" to UpnpTime.formatClock(durationMs),
            "TrackMetaData" to currentMetadata,
            "TrackURI" to (identity.followingCoordinator ?: currentUri.orEmpty()),
            "RelTime" to UpnpTime.formatClock(positionMs),
            "AbsTime" to UpnpTime.NOT_IMPLEMENTED,
            "RelCount" to "2147483647",
            "AbsCount" to "2147483647",
          ),
        ),
      )

      else -> fault(UpnpError.INVALID_ACTION)
    }

  private fun renderingAction(action: String, arguments: List<Pair<String, String>>): ByteArray {
    if (!identity.hasRenderingControl) return fault(UpnpError.INVALID_ACTION)
    return when (action) {
      "SetVolume" -> {
        val requested = arguments.firstOrNull { it.first == "DesiredVolume" }?.second?.toIntOrNull()
          ?: return fault(UpnpError.INVALID_ARGS)
        if (requested !in 0..100) return fault(UpnpError.INVALID_ARGS)
        volume = requested
        ok("text/xml", responseEnvelope("SetVolume", emptyList()))
      }
      "GetVolume" -> ok("text/xml", responseEnvelope("GetVolume", listOf("CurrentVolume" to "$volume")))
      "SetMute" -> {
        muted = arguments.firstOrNull { it.first == "DesiredMute" }?.second == "1"
        ok("text/xml", responseEnvelope("SetMute", emptyList()))
      }
      "GetMute" -> ok("text/xml", responseEnvelope("GetMute", listOf("CurrentMute" to if (muted) "1" else "0")))
      else -> fault(UpnpError.INVALID_ACTION)
    }
  }

  /**
   * What a renderer really does with a `CurrentURI`: a `HEAD` to learn the length and type, then a
   * ranged `GET`. Sonos issues both, which is why the proxy owes `HEAD` a real answer.
   */
  private fun fetchMedia(uri: String) {
    thread(isDaemon = true, name = "fake-renderer-fetch") {
      runCatching {
        val target = URI(uri)
        listOf("HEAD" to null, "GET" to "bytes=0-").forEach { (method, range) ->
          Socket(target.host, target.port).use { socket ->
            val head = buildString {
              append(method).append(' ').append(target.rawPath).append(" HTTP/1.1").append(HttpWire.CRLF)
              append("Host: ").append(target.host).append(':').append(target.port).append(HttpWire.CRLF)
              append("Connection: close").append(HttpWire.CRLF)
              if (range != null) append("Range: ").append(range).append(HttpWire.CRLF)
              append(HttpWire.CRLF)
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); flush() }
            HttpWire.readResponseHead(socket.getInputStream())
            media += RecordedMedia(method, target.rawPath, range)
            firstMedia.countDown()
          }
        }
      }
    }
  }

  private fun responseEnvelope(action: String, out: List<Pair<String, String>>): String =
    SoapEnvelope.render(
      DeviceDescription.SERVICE_AV_TRANSPORT,
      "${action}Response",
      out.map { app.muplay.cast.soap.SoapArgument(it.first, it.second) },
    )

  private fun renderHeadForRecording(headers: HttpHeaders): ByteArray =
    ("POST /control HTTP/1.1" + HttpWire.CRLF +
      headers.asList().joinToString("") { "${it.first}: ${it.second}${HttpWire.CRLF}" } +
      HttpWire.CRLF).toByteArray(Charsets.US_ASCII)

  /**
   * Sonos-shaped when [Identity.embedRenderer] -- the `MediaRenderer` nested inside a `ZonePlayer`'s
   * `deviceList`, alongside a `MediaServer` with no `AVTransport` -- and a flat generic renderer
   * otherwise. Both shapes are real, and a parser written against only the second one reports that
   * a Sonos is not a renderer.
   */
  private fun description(): String {
    val rendererServices = buildString {
      if (identity.hasRenderingControl) {
        append(
          "<service>" +
            "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>" +
            "<controlURL>/MediaRenderer/RenderingControl/Control</controlURL>" +
            "<SCPDURL>/xml/RenderingControl1.xml</SCPDURL>" +
            "</service>",
        )
      }
      append(
        "<service>" +
          "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
          "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
          "<controlURL>/MediaRenderer/AVTransport/Control</controlURL>" +
          "<SCPDURL>/xml/AVTransport1.xml</SCPDURL>" +
          "</service>",
      )
    }

    val body = if (identity.embedRenderer) {
      "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName}</friendlyName>" +
        "<manufacturer>${identity.manufacturer}</manufacturer>" +
        "<modelName>${identity.modelName}</modelName>" +
        "<UDN>${identity.udn}</UDN>" +
        "<serviceList><service>" +
        "<serviceType>urn:schemas-upnp-org:service:ZoneGroupTopology:1</serviceType>" +
        "<serviceId>urn:upnp-org:serviceId:ZoneGroupTopology</serviceId>" +
        "<controlURL>/ZoneGroupTopology/Control</controlURL>" +
        "<SCPDURL>/xml/ZoneGroupTopology1.xml</SCPDURL>" +
        "</service></serviceList>" +
        "<deviceList>" +
        // A MediaServer with no AVTransport, exactly as a real Sonos advertises. `CastDevice.from`
        // must skip it, and this is what gives it something to skip.
        "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName} Media Server</friendlyName>" +
        "<UDN>${identity.udn}_MS</UDN>" +
        "<serviceList><service>" +
        "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>" +
        "<serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>" +
        "<controlURL>/MediaServer/ContentDirectory/Control</controlURL>" +
        "</service></serviceList>" +
        "</device>" +
        "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName} Media Renderer</friendlyName>" +
        "<UDN>${identity.udn}_MR</UDN>" +
        "<serviceList>$rendererServices</serviceList>" +
        "</device>" +
        "</deviceList>" +
        "</device>"
    } else {
      "<device>" +
        "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
        "<friendlyName>${identity.friendlyName}</friendlyName>" +
        "<manufacturer>${identity.manufacturer}</manufacturer>" +
        "<modelName>${identity.modelName}</modelName>" +
        "<UDN>${identity.udn}</UDN>" +
        "<serviceList>$rendererServices</serviceList>" +
        "</device>"
    }

    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
      "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">" +
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
      body +
      "</root>"
  }

  /**
   * The `AVTransport` service description.
   *
   * Only two things in it are load-bearing and both are read by [app.muplay.cast.control.RendererCapabilities]:
   * `A_ARG_TYPE_SeekMode`'s `allowedValueList`, which decides how -- and whether -- this device can
   * be seeked, and whether `SetNextAVTransportURI` appears in the action list.
   */
  private fun avTransportScpd(): String =
    "<?xml version=\"1.0\"?>" +
      "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
      "<specVersion><major>1</major><minor>0</minor></specVersion>" +
      "<actionList>" +
      listOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo", "GetPositionInfo")
        .joinToString("") { "<action><name>$it</name></action>" } +
      "</actionList>" +
      "<serviceStateTable>" +
      "<stateVariable sendEvents=\"no\">" +
      "<name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>" +
      "<allowedValueList>" +
      strictness.supportedSeekModes.joinToString("") { "<allowedValue>$it</allowedValue>" } +
      "</allowedValueList>" +
      "</stateVariable>" +
      "</serviceStateTable>" +
      "</scpd>"

  private companion object { const val BACKLOG = 8 }
}
```

> **The description's control URLs are relative on purpose.** `/MediaRenderer/AVTransport/Control`
> is exactly what a real Sonos sends, and it is `DeviceDescription`'s job (Task 2) to resolve it
> against the `LOCATION` this fake served it from. A fake that emitted absolute URLs would let a
> broken resolver pass every test in this plan and fail against real hardware — which is the
> difference between a fake that stands in for a device and one that stands in for nothing.

- [ ] **Step 6: Write the test that the fake really does reject**

`core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRendererStrictnessTest.kt`:

```kotlin
package app.muplay.cast.fake

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.UpnpError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **The test of the test double.**
 *
 * Every other cast test in this plan is only as good as this renderer's willingness to say no. A
 * fake that accepts everything executes no rejection path, leaves the client's error handling
 * unexercised, and -- worst -- makes its own permissiveness invisible, because a permissive fake
 * produces exactly the same green suite as a strict one right up until real hardware disagrees.
 *
 * So the six rejections are asserted directly, by sending malformed requests **by hand** rather
 * than through `SoapClient`. Doing it by hand matters: a request built by the real client can
 * never be malformed, so a test that went through it could only ever observe acceptance.
 */
class FakeRendererStrictnessTest {

  private lateinit var renderer: FakeRenderer
  private val http = CastHttpClient()

  @BeforeEach
  fun setUp() {
    renderer = FakeRenderer().also { it.start() }
  }

  @AfterEach
  fun tearDown() = renderer.close()

  private fun post(action: String, arguments: List<SoapArgument>, soapAction: String): Int {
    val body = SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)
    return http.exchange(
      renderer.controlUrl,
      "POST",
      HttpHeaders.of("Content-Type" to SoapEnvelope.CONTENT_TYPE, "SOAPACTION" to soapAction),
      body.toByteArray(Charsets.UTF_8),
    ).let { response ->
      SoapEnvelope.parseFault(response.bodyText())?.errorCode ?: response.code
    }
  }

  private val goodArguments = listOf(
    SoapArgument("InstanceID", "0"),
    SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.mp3"),
    SoapArgument("CurrentURIMetaData", "&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;"),
  )

  @Test
  fun `a well-formed request is accepted, so the rejections below mean something`() {
    // The control observation. Without it, a renderer that rejected everything would pass all six
    // rejection tests and prove nothing at all.
    assertThat(post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))).isEqualTo(200)
  }

  @Test
  fun `an unquoted soapaction is rejected with 401`() {
    assertThat(
      post(
        "SetAVTransportURI",
        goodArguments,
        soapAction = "${DeviceDescription.SERVICE_AV_TRANSPORT}#SetAVTransportURI",
      ),
    ).isEqualTo(UpnpError.INVALID_ACTION)
  }

  @Test
  fun `arguments in the wrong order are rejected with 402`() {
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], goodArguments[2], goodArguments[1]),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.INVALID_ARGS)
  }

  @Test
  fun `empty metadata is rejected with 714`() {
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], goodArguments[1], SoapArgument("CurrentURIMetaData", "")),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `double-escaped metadata is rejected with 714`() {
    // `&amp;lt;DIDL-Lite` is what escaping twice produces, and it is the defect a round-trip test
    // through the real client cannot produce on purpose.
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(
          goodArguments[0],
          goodArguments[1],
          SoapArgument("CurrentURIMetaData", "&amp;lt;DIDL-Lite&amp;gt;"),
        ),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `a url with no file extension is rejected with 714`() {
    // Spec section 6: Sonos infers MIME from the URL. This is the rejection that makes Task 6's
    // token-with-an-extension a requirement rather than a nicety.
    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/abc"), goodArguments[2]),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `an opus protocolInfo is rejected with 714`() {
    // Spec section 4: "Never Opus. Sonos cannot decode it and Navidrome mislabels it audio/ogg."
    val opus = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;res protocolInfo=&quot;http-get:*:audio/ogg:*&quot;&gt;" +
      "http://127.0.0.1:9/media/a.ogg&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"

    assertThat(
      post(
        "SetAVTransportURI",
        listOf(goodArguments[0], SoapArgument("CurrentURI", "http://127.0.0.1:9/media/a.ogg"),
          SoapArgument("CurrentURIMetaData", opus)),
        quoted("SetAVTransportURI"),
      ),
    ).isEqualTo(UpnpError.ILLEGAL_MIME_TYPE)
  }

  @Test
  fun `an instance id that is not zero is rejected with 718`() {
    assertThat(
      post("Stop", listOf(SoapArgument("InstanceID", "1")), quoted("Stop")),
    ).isEqualTo(UpnpError.INVALID_INSTANCE_ID)
  }

  @Test
  fun `an unknown seek mode is rejected with 710`() {
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(
      post(
        "Seek",
        listOf(SoapArgument("InstanceID", "0"), SoapArgument("Unit", "ABS_TIME"),
          SoapArgument("Target", "0:00:10")),
        quoted("Seek"),
      ),
    ).isEqualTo(UpnpError.SEEK_MODE_NOT_SUPPORTED)
  }

  @Test
  fun `a play with a speed other than 1 is rejected with 717`() {
    // The Plan 4 interaction, made concrete: a book's stored playback speed cannot be delivered to
    // a renderer, and the renderer says so rather than quietly playing at 1x.
    post("SetAVTransportURI", goodArguments, quoted("SetAVTransportURI"))

    assertThat(
      post("Play", listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1.5")), quoted("Play")),
    ).isEqualTo(UpnpError.PLAY_SPEED_NOT_SUPPORTED)
  }

  @Test
  fun `an unknown action is rejected with 401`() {
    assertThat(post("Teleport", emptyList(), quoted("Teleport"))).isEqualTo(UpnpError.INVALID_ACTION)
  }

  @Test
  fun `a play before any uri has been set is rejected with 701`() {
    assertThat(
      post("Play", listOf(SoapArgument("InstanceID", "0"), SoapArgument("Speed", "1")), quoted("Play")),
    ).isEqualTo(UpnpError.TRANSITION_NOT_AVAILABLE)
  }

  @Test
  fun `turning a strictness knob off really does turn it off`() {
    // Rule 4 applied to the knobs themselves: a `Strictness` field nothing reads would leave every
    // test above passing and the knob silently inert, which is the shape of the defect this whole
    // class exists to prevent one level up.
    val lenient = FakeRenderer(FakeRenderer.Strictness(requireQuotedSoapAction = false))
    lenient.use {
      it.start()
      val body = SoapEnvelope.render(DeviceDescription.SERVICE_AV_TRANSPORT, "SetAVTransportURI", goodArguments)
      val response = http.exchange(
        it.controlUrl,
        "POST",
        HttpHeaders.of(
          "Content-Type" to SoapEnvelope.CONTENT_TYPE,
          "SOAPACTION" to "${DeviceDescription.SERVICE_AV_TRANSPORT}#SetAVTransportURI",
        ),
        body.toByteArray(Charsets.UTF_8),
      )
      assertThat(response.code).isEqualTo(200)
    }
  }

  private fun quoted(action: String) =
    SoapEnvelope.soapActionHeader(DeviceDescription.SERVICE_AV_TRANSPORT, action)
}
```

- [ ] **Step 7: Run the strictness suite**

Run: `./gradlew :core:cast:test --tests '*FakeRendererStrictnessTest*'`
Expected: PASS, 13/13. **If any rejection test passes while `a well-formed request is accepted`
also passes, that pair is the proof the knob discriminates.** If the control test fails, fix the
fake before going further — every later task's evidence rests on it.

- [ ] **Step 8: Implement and test `SoapClient`**

`core/cast/src/main/kotlin/app/muplay/cast/soap/SoapClient.kt`:

```kotlin
package app.muplay.cast.soap

import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.http.HttpHeaders
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One SOAP action against one control URL.
 *
 * Returns the `out` arguments on success, throws [UpnpErrorException] when the device refused and
 * said why, and [SoapTransportException] when it could not be reached or answered something
 * unreadable. Those are two different facts and the caller (Task 5) branches on them differently:
 * a 710 means "this device cannot seek that way, use another mode"; a socket timeout means "the
 * speaker is gone, hand playback back to the phone".
 */
class SoapClient(private val http: CastHttpClient = CastHttpClient()) {

  suspend fun invoke(
    controlUrl: URI,
    serviceType: String,
    action: String,
    arguments: List<SoapArgument>,
  ): Map<String, String> = withContext(Dispatchers.IO) {
    val body = SoapEnvelope.render(serviceType, action, arguments).toByteArray(Charsets.UTF_8)
    val response = runCatching {
      http.exchange(
        url = controlUrl,
        method = "POST",
        headers = HttpHeaders.of(
          "Content-Type" to SoapEnvelope.CONTENT_TYPE,
          // Quoted. See SoapEnvelope.soapActionHeader.
          "SOAPACTION" to SoapEnvelope.soapActionHeader(serviceType, action),
        ),
        body = body,
      )
    }.getOrElse { cause -> throw SoapTransportException(action, statusCode = 0, cause = cause) }

    // The fault check comes FIRST and is not conditional on the status code: a UPnP error is HTTP
    // 500 with a body, and a handful of renderers answer 200 with a fault body instead.
    SoapEnvelope.parseFault(response.bodyText())?.let { throw UpnpErrorException(action, it) }

    if (response.code != HttpURLConnection.HTTP_OK) {
      throw SoapTransportException(action, response.code)
    }
    SoapEnvelope.parseResponse(action, response.bodyText())
  }
}
```

`core/cast/src/test/kotlin/app/muplay/cast/soap/SoapClientTest.kt` — against the real
`FakeRenderer`, asserting:

- `a successful action returns its out arguments` — `GetVolume` returns `CurrentVolume`;
- `the request that reached the device carries a quoted soapaction` — read off
  `renderer.soapRequests.single().rawSoapAction`, asserted to start and end with `"`;
- `the request that reached the device carries the arguments in order` —
  `assertThat(renderer.soapRequests.single().arguments.map { it.first }).containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")`;
- `the request that reached the device carries the soap content type` — off the recorded head;
- `a refused action throws with the device's own error code` — `Seek` with `ABS_TIME`, expecting
  `UpnpErrorException` whose `fault.errorCode == 710` and whose message contains
  `"Seek mode not supported"`;
- `a second refusal carries a second code` — `SetAVTransportURI` with no metadata, expecting 714,
  so `errorCode` cannot be a constant;
- `a renderer that has gone away throws a transport failure and not a upnp error` —
  `renderer.disappear()` then invoke, expecting `SoapTransportException` and **not**
  `UpnpErrorException`. **This distinction is the one Task 8's fallback branches on**, so it is
  pinned here where it is cheap.

- [ ] **Step 9: Prove each new assertion can fail**

1. In `XmlText.escape`, move the `&` replacement last. Expect
   `the ampersand is replaced first...` and `unescape reverses escape...` to fail.
2. In `SoapEnvelope.soapActionHeader`, drop the quotes. Expect
   `the soapaction header value is quoted` **and**
   `the request that reached the device carries a quoted soapaction` **and**
   every `SoapClientTest` action to fail with 401 — three layers catching one defect, which is what
   makes it worth having a strict fake at all.
3. In `SoapEnvelope.render`, sort `arguments` by name before emitting. Expect
   `arguments appear in the order they were given...` and
   `the request that reached the device carries the arguments in order` to fail, and the fake to
   answer 402.
4. In `SoapEnvelope.render`, escape the argument values. Expect
   `an argument value is inserted verbatim...` to fail and the fake to reject double-escaped
   metadata with 714.
5. In `SoapClient.invoke`, move the fault check after the status-code check. Expect
   `a refused action throws with the device's own error code` to fail with a
   `SoapTransportException` instead — the exact information loss the ordering exists to prevent.
6. In `UpnpTime.parseClock`, return `0L` instead of `null` for `NOT_IMPLEMENTED`. Expect
   `NOT_IMPLEMENTED and the other unusable values are null, not zero` to fail.
7. In `UpnpTime.formatClock`, round instead of truncating (`(millis + 500) / 1000`). Expect
   `formatting truncates rather than rounding...` to fail.
8. In `FakeRenderer`, set every `Strictness` default to `false`. Expect **all eleven** rejection
   tests in `FakeRendererStrictnessTest` to fail. This is the probe that keeps the fake honest, and
   it is the most important one in this task.

- [ ] **Step 10: Record the probes, measure and commit**

Add mutations 1, 2, 3, 5 and 8 to `ci/mutation-probes.sh` as `soap/*` entries. Then measure and add
to `:core:cast`'s BRANCH floor `includes`: `"app.muplay.cast.soap.XmlText"`,
`"app.muplay.cast.soap.SoapEnvelope"`, `"app.muplay.cast.soap.UpnpError"`,
`"app.muplay.cast.soap.UpnpTime"`, `"app.muplay.cast.soap.SoapClient"`.

```bash
./gradlew :core:cast:test jacocoTestReport jacocoJvmCoverageVerification
git add core/cast build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): SOAP envelopes, UPnP faults, and a renderer strict enough to refuse"
```

---

## Task 4: DIDL-Lite, `protocolInfo`, and the three-way MIME invariant

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/didl/ServedMedia.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/didl/DidlLite.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/didl/CastItem.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/didl/ServedMediaTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/didl/DidlLiteTest.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MediaItems.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt`
- Modify: `core/media/build.gradle.kts`
- Modify: `core/media/src/androidTest/kotlin/app/muplay/media/MediaItemsTest.kt`
- Modify: `build.gradle.kts`
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `XmlText.escape` / `.unescape`, `SoapEnvelope`, `UpnpTime.formatDuration` (Task 3);
  `app.muplay.model.StreamFormat` (**Plan 3 Task 1** — `Raw`, `Mp3(maxBitRateKbps)`, `wireValue`,
  `forSuffix`, `DEFAULT_TRANSCODE_BITRATE_KBPS`); `app.muplay.model.Song` (committed).
- Produces:
  - `data class ServedMedia(val mimeType: String, val fileExtension: String)` with
    `val protocolInfo: String`, and
    `companion object { fun of(suffix: String?, format: StreamFormat): ServedMedia; const val DLNA_FLAGS; const val FALLBACK_MIME; const val FALLBACK_EXTENSION }`
  - `data class CastItem(val mediaId, val title, val artist, val albumTitle, val artworkUri, val durationMs, val upnpClass, val resourceUrl, val served)`
  - `object DidlLite` with `fun render(item: CastItem): String`,
    `fun renderEscaped(item: CastItem): String`,
    `const val CLASS_MUSIC_TRACK`, `const val CLASS_AUDIO_BOOK`
- **Modifies Plan 3's `MediaItems.of`**, adding a fifth parameter. See "The three-way invariant".
- **Plan 4 interaction:** `upnpClass` is chosen from `MediaMetadata.mediaType`, which **Plan 3
  Task 6** sets from the user's `LibraryRole` assignment. Plan 4 does not own it. If Plan 4 changes
  how a book is identified, this task reads whatever `MediaMetadata.mediaType` then carries and
  needs no change of its own.

### The three-way invariant, and why one field has to move into `MediaItem`

Three separate parties have to agree on what format the renderer is about to receive:

| Where | What says it | What goes wrong when it disagrees |
|---|---|---|
| the **URL** | the file extension on the proxy path | **Sonos infers MIME from the URL** (spec §6). Wrong extension, or none: `714 Illegal MIME-type`. |
| the **DIDL** | `res/@protocolInfo`'s third field | a generic DLNA renderer trusts this; disagreeing with the URL makes behaviour brand-dependent |
| the **bytes** | the proxy's `Content-Type` header | the one thing Sonos ignores and everyone else believes |

Three statements of one fact is two chances to be wrong, so they come from **one value**:
`ServedMedia`. `MediaProxyServer` (Task 6) serves `served.mimeType` and mints a path ending in
`.${served.fileExtension}`; `DidlLite` writes `served.protocolInfo`; and a test in Task 6 asserts
all three are consistent for the same item, which is the assertion spec §10's Tier 1 row calls
*"`protocolInfo` vs served `Content-Type`"* — widened to the third leg, because §6's own sentence
about URL sniffing makes the extension the leg that actually decides whether Sonos plays.

**And the format is not the file's suffix.** With `StreamFormat.Mp3` — which is what
`StreamFormat.forSuffix` returns for `opus` and `ogg`, per spec §4's *"Never Opus"* — Navidrome
transcodes and the bytes on the wire are **MP3**, whatever the source was. A `ServedMedia` derived
from `Song.suffix` alone would tell Sonos `audio/ogg` and hand it a URL ending `.ogg` while serving
MP3. Sonos would refuse the format it was promised and never find out it was lied to. So
`ServedMedia.of` takes **both** the suffix and the effective `StreamFormat`, and the `Mp3` branch
ignores the suffix entirely.

This is spec §12's *"Sonos rejects a served format | Medium"* risk, and this is where it is closed.

**Where the value lives.** `MediaItem.localConfiguration.mimeType` — a real Media3 field, already
there, already a hint the local extractor uses. Plan 3's `MediaItems.of` does not set it, so this
task adds a fifth parameter and sets it. That is a change to Plan 3's module and Plan 3's test, and
it is the right place: the alternative is a `Map<mediaId, ServedMedia>` maintained beside the queue,
which is a second truth about the same fact and would drift the first time a queue was rebuilt.

> `:core:media` gains `implementation(project(":core:cast"))` for this. The direction is
> `:core:media` → `:core:cast` and it stays that way for the rest of this plan: `:core:cast` is
> pure JVM and must never see a Media3 type.

- [ ] **Step 1: Write the failing `ServedMedia` test**

`core/cast/src/test/kotlin/app/muplay/cast/didl/ServedMediaTest.kt`:

```kotlin
package app.muplay.cast.didl

import app.muplay.model.StreamFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ServedMediaTest {

  @Test
  fun `a raw stream is served as the source format, suffix by suffix`() {
    // The exact mapped list, in order, rather than eleven separate assertions or an `allMatch`
    // that would be vacuously true on an empty input. A `ServedMedia.of` returning a constant
    // fails here on ten of eleven entries.
    val suffixes = listOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "wav", "wma", "aiff", "alac", "oga")

    assertThat(suffixes.map { ServedMedia.of(it, StreamFormat.Raw).mimeType }).containsExactly(
      "audio/mpeg",
      "audio/flac",
      "audio/mp4",
      "audio/mp4",
      "audio/mp4",
      "audio/aac",
      "audio/wav",
      "audio/x-ms-wma",
      "audio/aiff",
      "audio/mp4",
      "audio/mpeg",
    )
  }

  @Test
  fun `the file extension is the source suffix, lowercased, for a raw stream`() {
    // Separate from the MIME assertion because they are separate fields with separate failure
    // modes: a wrong MIME confuses a generic renderer, a wrong extension makes Sonos refuse.
    assertThat(ServedMedia.of("FLAC", StreamFormat.Raw).fileExtension).isEqualTo("flac")
    assertThat(ServedMedia.of("m4b", StreamFormat.Raw).fileExtension).isEqualTo("m4b")
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).fileExtension).isEqualTo("mp3")
  }

  /**
   * The invariant this whole type exists for.
   *
   * `StreamFormat.forSuffix("opus", ...)` returns `Mp3` (spec section 4, "Never Opus"), so
   * Navidrome transcodes and the bytes on the wire are MP3 -- whatever the source file was. A
   * `ServedMedia` derived from the suffix would promise Sonos `audio/ogg` and a `.opus` URL while
   * serving MP3, and Sonos would refuse the format it was promised.
   */
  @Test
  fun `a transcode is served as mp3, whatever the source file was`() {
    val transcoded = StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)

    // Four sources, one answer, each field observed. This is the assertion that closes spec
    // section 12's "Sonos rejects a served format" risk.
    listOf("opus", "ogg", "flac", null).forEach { suffix ->
      assertThat(ServedMedia.of(suffix, transcoded).mimeType)
        .describedAs("mime for suffix %s under a forced transcode", suffix)
        .isEqualTo("audio/mpeg")
      assertThat(ServedMedia.of(suffix, transcoded).fileExtension)
        .describedAs("extension for suffix %s under a forced transcode", suffix)
        .isEqualTo("mp3")
    }
  }

  @Test
  fun `an unknown suffix falls back to something a renderer will at least attempt`() {
    // `application/octet-stream` would be refused outright by Sonos; `audio/mpeg` with a `.mp3`
    // extension is the guess most likely to play, and the mirror very rarely lacks a suffix. The
    // fallback is a decision, so it is pinned rather than left to whatever the map returns.
    assertThat(ServedMedia.of("xyz", StreamFormat.Raw).mimeType).isEqualTo(ServedMedia.FALLBACK_MIME)
    assertThat(ServedMedia.of(null, StreamFormat.Raw).fileExtension).isEqualTo(ServedMedia.FALLBACK_EXTENSION)
    assertThat(ServedMedia.FALLBACK_MIME).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.FALLBACK_EXTENSION).isEqualTo("mp3")
  }

  @Test
  fun `the suffix is matched case-insensitively`() {
    assertThat(ServedMedia.of("MP3", StreamFormat.Raw).mimeType).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.of("Flac", StreamFormat.Raw).mimeType).isEqualTo("audio/flac")
  }

  @Test
  fun `protocolInfo names the mime type and declares byte-range seeking`() {
    // Two observations of the MIME position, so it cannot be a constant.
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).isEqualTo(
      "http-get:*:audio/mpeg:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )
    assertThat(ServedMedia.of("flac", StreamFormat.Raw).protocolInfo).isEqualTo(
      "http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000",
    )
  }

  /**
   * `DLNA.ORG_OP=01` is a **promise**: the low bit means byte-range seeking is supported, so a
   * renderer that reads it may issue `Range` requests and expect 206. Task 6's proxy owes that
   * promise, which is why the two are asserted against each other there.
   *
   * `DLNA.ORG_PN` is deliberately **absent**. A profile name identifies an exact encoding
   * (`MP3`, `MP3X`, `LPCM`, ...), a wrong one is a hard rejection on a strict renderer, and
   * Navidrome tells this client nothing precise enough to compute one. An absent PN means "work it
   * out from the bytes", which every renderer can do; a wrong PN means "no".
   */
  @Test
  fun `protocolInfo declares no dlna profile name, on purpose`() {
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).doesNotContain("DLNA.ORG_PN")
    assertThat(ServedMedia.of("mp3", StreamFormat.Raw).protocolInfo).contains("DLNA.ORG_OP=01")
    assertThat(ServedMedia.DLNA_FLAGS).hasSize(32)
  }

  @Test
  fun `opus never reaches a renderer, by construction`() {
    // Not an assertion about a check, an assertion about the type system: `StreamFormat.forSuffix`
    // makes `opus` unrepresentable as a raw stream, so there is no path from an Opus file to an
    // `audio/opus` protocolInfo. Pinned here because this module is where the consequence lands.
    val format = StreamFormat.forSuffix("opus", StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS)

    assertThat(format).isEqualTo(StreamFormat.Mp3(StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS))
    assertThat(ServedMedia.of("opus", format).mimeType).isEqualTo("audio/mpeg")
    assertThat(ServedMedia.of("opus", format).protocolInfo).doesNotContain("opus")
    assertThat(ServedMedia.of("opus", format).protocolInfo).doesNotContain("ogg")
  }
}
```

- [ ] **Step 2: Run it to verify it fails, then implement**

`core/cast/src/main/kotlin/app/muplay/cast/didl/ServedMedia.kt`:

```kotlin
package app.muplay.cast.didl

import app.muplay.model.StreamFormat

/**
 * **The one statement of what format a renderer is about to receive.**
 *
 * Three parties have to agree, and each of them gets it from here:
 *
 * - the **URL** the renderer fetches ends in `.$fileExtension`, because spec section 6 records
 *   that *"Sonos ... infers MIME **from the URL, not `Content-Type`**"* -- a path with the wrong
 *   extension, or none, is `714 Illegal MIME-type`;
 * - the **DIDL** metadata declares [protocolInfo], which is what a generic DLNA renderer trusts;
 * - the **proxy** serves [mimeType] as its `Content-Type`, which is what everyone except Sonos
 *   believes.
 *
 * Three statements of one fact is two chances to disagree. One value removes both.
 */
data class ServedMedia(val mimeType: String, val fileExtension: String) {

  /**
   * `http-get:*:<mime>:<fourth field>`.
   *
   * `DLNA.ORG_OP=01` sets the byte-seek bit, which is a **promise** the proxy has to keep: a
   * renderer reading it may issue `Range` requests and expect 206. Task 6 keeps it, and asserts
   * that it does against this very string.
   *
   * `DLNA.ORG_PN` is absent on purpose. A profile name identifies an exact encoding, a wrong one is
   * a hard rejection on a strict renderer, and nothing Navidrome reports is precise enough to
   * compute one. Absent means "work it out from the bytes", which every renderer can do.
   */
  val protocolInfo: String get() = "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=$DLNA_FLAGS"

  companion object {

    /**
     * The conventional DLNA 1.5 flag word: streaming transfer mode, background transfer allowed,
     * connection stall allowed, DLNA v1.5. 32 hex digits, of which the leading eight carry the
     * meaning and the remainder are reserved zeroes.
     */
    const val DLNA_FLAGS: String = "01700000000000000000000000000000"

    /**
     * What an unrecognised suffix is served as.
     *
     * `application/octet-stream` would be refused outright by Sonos, and this is a fallback for a
     * case the mirror almost never produces. MP3 is the guess most likely to play, and if it is
     * wrong the renderer sniffs the bytes -- which every renderer does when the profile is not
     * pinned.
     */
    const val FALLBACK_MIME: String = "audio/mpeg"
    const val FALLBACK_EXTENSION: String = "mp3"

    /**
     * Suffix to (MIME, extension) for a **raw** stream.
     *
     * `oga` maps to `audio/mpeg` and `mp3` because it can only arrive here via a forced transcode
     * -- an `.oga` file is Ogg, `StreamFormat.forSuffix` transcodes Ogg (spec section 4: the suffix
     * cannot rule out Opus), so the `Raw` branch is unreachable for it. Mapping it to `audio/ogg`
     * would encode a lie that nothing can execute.
     */
    private val RAW_TYPES: Map<String, ServedMedia> = mapOf(
      "mp3" to ServedMedia("audio/mpeg", "mp3"),
      "flac" to ServedMedia("audio/flac", "flac"),
      "m4a" to ServedMedia("audio/mp4", "m4a"),
      "m4b" to ServedMedia("audio/mp4", "m4b"),
      "mp4" to ServedMedia("audio/mp4", "mp4"),
      "alac" to ServedMedia("audio/mp4", "m4a"),
      "aac" to ServedMedia("audio/aac", "aac"),
      "wav" to ServedMedia("audio/wav", "wav"),
      "aiff" to ServedMedia("audio/aiff", "aiff"),
      "aif" to ServedMedia("audio/aiff", "aiff"),
      "wma" to ServedMedia("audio/x-ms-wma", "wma"),
      "oga" to ServedMedia("audio/mpeg", "mp3"),
    )

    /**
     * What the renderer will actually receive.
     *
     * **[format] wins over [suffix], and that is the whole point.** With [StreamFormat.Mp3] --
     * which is what `StreamFormat.forSuffix` returns for `opus` and `ogg`, per spec section 4's
     * *"Never Opus"* -- Navidrome transcodes and the bytes are MP3 whatever the source was.
     * Deriving from the suffix alone would promise Sonos `audio/ogg`, hand it a `.opus` URL, and
     * serve MP3; Sonos would refuse the format it was promised, which is spec section 12's
     * "Sonos rejects a served format" risk arriving in the most confusing possible form.
     */
    fun of(suffix: String?, format: StreamFormat): ServedMedia = when (format) {
      is StreamFormat.Mp3 -> ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)
      StreamFormat.Raw ->
        RAW_TYPES[suffix?.lowercase()] ?: ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)
    }
  }
}
```

Run: `./gradlew :core:cast:test --tests '*ServedMediaTest*'` — PASS, 8/8.

- [ ] **Step 3: Write the failing DIDL test**

`core/cast/src/test/kotlin/app/muplay/cast/didl/DidlLiteTest.kt`:

```kotlin
package app.muplay.cast.didl

import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapEnvelope
import app.muplay.cast.soap.XmlText
import app.muplay.model.StreamFormat
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The DIDL-Lite document, and the **round trip** spec section 10's Tier 1 row names.
 *
 * The round trip is the assertion that matters, because it is the only one that catches both
 * halves of the escaping defect at once: escaped zero times the envelope does not parse, escaped
 * twice the device receives literal `&lt;` text. Rendering alone catches neither reliably.
 */
class DidlLiteTest {

  private val item = CastItem(
    mediaId = "track-1",
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = "http://10.0.0.2:8080/art/album-1.jpg",
    durationMs = 300_000L,
    upnpClass = DidlLite.CLASS_MUSIC_TRACK,
    resourceUrl = "http://10.0.0.2:8080/media/9f2a.mp3",
    served = ServedMedia.of("mp3", StreamFormat.Raw),
  )

  @Test
  fun `the document is exactly this`() {
    assertThat(DidlLite.render(item)).isEqualTo(
      "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"track-1\" parentID=\"0\" restricted=\"1\">" +
        "<dc:title>Track 1</dc:title>" +
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
        "<dc:creator>Test Artist</dc:creator>" +
        "<upnp:artist>Test Artist</upnp:artist>" +
        "<upnp:album>Test Album</upnp:album>" +
        "<upnp:albumArtURI>http://10.0.0.2:8080/art/album-1.jpg</upnp:albumArtURI>" +
        "<res protocolInfo=\"http-get:*:audio/mpeg:DLNA.ORG_OP=01;" +
        "DLNA.ORG_FLAGS=01700000000000000000000000000000\" duration=\"0:05:00.000\">" +
        "http://10.0.0.2:8080/media/9f2a.mp3" +
        "</res>" +
        "</item>" +
        "</DIDL-Lite>",
    )
  }

  @Test
  fun `every field comes from the item it was given`() {
    // Two observations per field, by rendering a second item that differs in all of them. A
    // byte-exact assertion on one item is satisfied by a hardcoded document; this is not.
    val second = item.copy(
      mediaId = "chapter-14",
      title = "Chapter 14",
      artist = "Another Artist",
      albumTitle = "A Book",
      artworkUri = "http://10.0.0.2:8080/art/book-9.jpg",
      durationMs = 3_723_000L,
      upnpClass = DidlLite.CLASS_AUDIO_BOOK,
      resourceUrl = "http://10.0.0.2:8080/media/aaaa.m4b",
      served = ServedMedia.of("m4b", StreamFormat.Raw),
    )
    val xml = DidlLite.render(second)

    assertThat(xml).contains("<item id=\"chapter-14\"")
    assertThat(xml).contains("<dc:title>Chapter 14</dc:title>")
    assertThat(xml).contains("<dc:creator>Another Artist</dc:creator>")
    assertThat(xml).contains("<upnp:album>A Book</upnp:album>")
    assertThat(xml).contains("<upnp:albumArtURI>http://10.0.0.2:8080/art/book-9.jpg</upnp:albumArtURI>")
    assertThat(xml).contains("<upnp:class>object.item.audioItem.audioBook</upnp:class>")
    assertThat(xml).contains("duration=\"1:02:03.000\"")
    assertThat(xml).contains("http-get:*:audio/mp4:")
    assertThat(xml).contains(">http://10.0.0.2:8080/media/aaaa.m4b</res>")
  }

  @Test
  fun `an absent optional field is omitted rather than rendered empty`() {
    // A renderer showing an empty artist line is worse than one showing none, and `albumArtURI`
    // with an empty value makes several renderers fetch "" and log an error every second.
    val sparse = item.copy(artist = null, albumTitle = null, artworkUri = null)
    val xml = DidlLite.render(sparse)

    assertThat(xml).doesNotContain("dc:creator")
    assertThat(xml).doesNotContain("upnp:artist")
    assertThat(xml).doesNotContain("upnp:album>")
    assertThat(xml).doesNotContain("albumArtURI")
    // ...and the mandatory ones are still there.
    assertThat(xml).contains("<dc:title>Track 1</dc:title>")
    assertThat(xml).contains("<upnp:class>")
    assertThat(xml).contains("<res protocolInfo=")
  }

  @Test
  fun `every text field is escaped, including in the res url`() {
    val nasty = item.copy(
      mediaId = "id&1",
      title = "Rock & Roll <live>",
      artist = "AC/DC \"Live\"",
      albumTitle = "It's Album",
      resourceUrl = "http://10.0.0.2:8080/media/9f2a.mp3?x=1&y=2",
    )
    val xml = DidlLite.render(nasty)

    assertThat(xml).contains("<item id=\"id&amp;1\"")
    assertThat(xml).contains("<dc:title>Rock &amp; Roll &lt;live&gt;</dc:title>")
    assertThat(xml).contains("<dc:creator>AC/DC &quot;Live&quot;</dc:creator>")
    assertThat(xml).contains("<upnp:album>It&apos;s Album</upnp:album>")
    // The URL matters most: a stream URL carries `&` between query parameters, and an unescaped
    // one makes the whole document unparseable at the device.
    assertThat(xml).contains(">http://10.0.0.2:8080/media/9f2a.mp3?x=1&amp;y=2</res>")
  }

  @Test
  fun `the rendered document is well-formed xml`() {
    // Parsed, not eyeballed. This is what catches an unescaped character that no `contains`
    // assertion happens to look at.
    val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(DidlLite.render(item.copy(title = "Rock & Roll <live>")).byteInputStream())

    assertThat(document.documentElement.nodeName).isEqualTo("DIDL-Lite")
  }

  /**
   * **The round trip spec section 10 names.** Render, escape once, embed as a SOAP argument,
   * parse the envelope back, decode, re-parse as XML, and read the fields out. Every step is one
   * a real device performs.
   */
  @Test
  fun `didl survives being embedded in a soap envelope and read back out`() {
    val nasty = item.copy(title = "Rock & Roll <live> \"1971\"", artist = "It's & Co")
    val envelope = SoapEnvelope.render(
      DeviceDescription.SERVICE_AV_TRANSPORT,
      "SetAVTransportURI",
      listOf(
        SoapArgument("InstanceID", "0"),
        SoapArgument("CurrentURI", nasty.resourceUrl),
        SoapArgument("CurrentURIMetaData", DidlLite.renderEscaped(nasty)),
      ),
    )

    // What the *device* sees inside the envelope: escaped exactly once.
    assertThat(envelope).contains("<CurrentURIMetaData>&lt;DIDL-Lite")
    assertThat(envelope).doesNotContain("&amp;lt;DIDL-Lite")
    assertThat(envelope).doesNotContain("<CurrentURIMetaData><DIDL-Lite")

    // And what it means once decoded.
    val embedded = envelope.substringAfter("<CurrentURIMetaData>").substringBefore("</CurrentURIMetaData>")
    val decoded = XmlText.unescape(embedded)
    val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
      .newDocumentBuilder().parse(decoded.byteInputStream())

    assertThat(document.getElementsByTagName("dc:title").item(0).textContent)
      .isEqualTo("Rock & Roll <live> \"1971\"")
    assertThat(document.getElementsByTagName("dc:creator").item(0).textContent).isEqualTo("It's & Co")
    assertThat(document.getElementsByTagName("res").item(0).textContent).isEqualTo(nasty.resourceUrl)
    assertThat(
      document.getElementsByTagName("res").item(0).attributes.getNamedItem("protocolInfo").nodeValue,
    ).isEqualTo(nasty.served.protocolInfo)
  }

  @Test
  fun `renderEscaped is render escaped exactly once`() {
    assertThat(DidlLite.renderEscaped(item)).isEqualTo(XmlText.escape(DidlLite.render(item)))
    assertThat(DidlLite.renderEscaped(item)).startsWith("&lt;DIDL-Lite")
    assertThat(DidlLite.renderEscaped(item)).doesNotContain("&amp;lt;")
  }

  @Test
  fun `the two upnp classes are the ones the protocol defines`() {
    assertThat(DidlLite.CLASS_MUSIC_TRACK).isEqualTo("object.item.audioItem.musicTrack")
    assertThat(DidlLite.CLASS_AUDIO_BOOK).isEqualTo("object.item.audioItem.audioBook")
  }
}
```

- [ ] **Step 4: Implement `CastItem` and `DidlLite`**

`core/cast/src/main/kotlin/app/muplay/cast/didl/CastItem.kt`:

```kotlin
package app.muplay.cast.didl

/**
 * One track, as a renderer needs to be told about it.
 *
 * [resourceUrl] is the URL the **renderer** will fetch -- the proxy URL in the ordinary case, not
 * the Navidrome stream URL. Task 7 decides which, and this type carries whichever was decided.
 */
data class CastItem(
  val mediaId: String,
  val title: String,
  val artist: String?,
  val albumTitle: String?,
  val artworkUri: String?,
  val durationMs: Long,
  val upnpClass: String,
  val resourceUrl: String,
  val served: ServedMedia,
)
```

`core/cast/src/main/kotlin/app/muplay/cast/didl/DidlLite.kt`:

```kotlin
package app.muplay.cast.didl

import app.muplay.cast.soap.UpnpTime
import app.muplay.cast.soap.XmlText

/**
 * The DIDL-Lite metadata document a renderer is given alongside a URL.
 *
 * Spec section 6: *"DIDL-Lite mandatory"*. Sonos will accept a `SetAVTransportURI` with empty
 * metadata on some firmware and refuse it on others; where it accepts, the speaker's display and
 * the Sonos app show the track as unknown, which is a visible half-failure.
 *
 * Built by string building rather than by a DOM serialiser, for the same reason
 * [app.muplay.cast.soap.SoapEnvelope] is: this plan asserts the document byte for byte, and a
 * serialiser's choices about self-closing tags and attribute order are not this project's to
 * assert. Escaping is [XmlText]'s, applied per field.
 */
object DidlLite {

  const val CLASS_MUSIC_TRACK: String = "object.item.audioItem.musicTrack"
  const val CLASS_AUDIO_BOOK: String = "object.item.audioItem.audioBook"

  fun render(item: CastItem): String = buildString {
    append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
    append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
    append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
    append("<item id=\"").append(XmlText.escape(item.mediaId)).append("\" parentID=\"0\" restricted=\"1\">")
    append("<dc:title>").append(XmlText.escape(item.title)).append("</dc:title>")
    append("<upnp:class>").append(item.upnpClass).append("</upnp:class>")
    // `dc:creator` and `upnp:artist` carry the same value: renderers disagree about which one they
    // read, and sending both costs a few bytes while sending one costs a blank artist line on
    // whichever brand reads the other.
    item.artist?.let {
      append("<dc:creator>").append(XmlText.escape(it)).append("</dc:creator>")
      append("<upnp:artist>").append(XmlText.escape(it)).append("</upnp:artist>")
    }
    item.albumTitle?.let { append("<upnp:album>").append(XmlText.escape(it)).append("</upnp:album>") }
    item.artworkUri?.let {
      append("<upnp:albumArtURI>").append(XmlText.escape(it)).append("</upnp:albumArtURI>")
    }
    // An absent optional field is omitted, never rendered empty: `<upnp:albumArtURI></upnp:albumArtURI>`
    // makes several renderers fetch the empty URL once a second and log an error each time.
    append("<res protocolInfo=\"").append(XmlText.escape(item.served.protocolInfo)).append("\" ")
    append("duration=\"").append(UpnpTime.formatDuration(item.durationMs)).append("\">")
    // The URL is escaped like any other text: a Navidrome stream URL carries `&` between query
    // parameters, and one unescaped ampersand makes the whole document unparseable at the device.
    append(XmlText.escape(item.resourceUrl))
    append("</res>")
    append("</item>")
    append("</DIDL-Lite>")
  }

  /**
   * The document, escaped **once**, ready to be the text content of `CurrentURIMetaData`.
   *
   * A separate function rather than a caller's responsibility, because "escape it before you send
   * it" is a rule that gets applied twice as often as it gets forgotten, and `&amp;lt;DIDL-Lite`
   * is a device that shows the track as unknown with no error anywhere.
   */
  fun renderEscaped(item: CastItem): String = XmlText.escape(render(item))
}
```

Run: `./gradlew :core:cast:test --tests '*DidlLiteTest*'` — PASS, 8/8.

- [ ] **Step 5: Move the MIME into `MediaItem`**

`core/media/build.gradle.kts` — add:

```kotlin
  // `:core:cast` is pure JVM. This dependency is one-directional and stays that way: `:core:cast`
  // must never see a Media3 type, which is why `UpnpPlayer` (Task 8) lives here and not there.
  implementation(project(":core:cast"))
```

`core/media/src/main/kotlin/app/muplay/media/MediaItems.kt` — add the fifth parameter. Read the
real file first: **Plan 3 Task 6 already added `isAudiobook` as a fourth**, so the signature below
must match whatever landed.

```kotlin
  /**
   * @param format the [StreamFormat] this item's URL was built with. Needed because the **served**
   *   MIME type is not the source file's: a forced transcode (spec section 4's "Never Opus")
   *   delivers MP3 whatever the source was, and Sonos infers MIME from the URL's extension. See
   *   `ServedMedia`.
   */
  fun of(
    song: Song,
    streamUri: String,
    artworkUri: String?,
    isAudiobook: Boolean,
    format: StreamFormat,
  ): MediaItem =
    MediaItem.Builder()
      .setMediaId(song.id)
      .setUri(streamUri)
      .setCustomCacheKey(song.id)
      // The one statement of what these bytes are, read by the local extractor as a hint and by
      // the cast layer as the truth it tells a renderer. See `ServedMedia`'s KDoc for why three
      // parties must agree and why they all read this one value.
      .setMimeType(ServedMedia.of(song.suffix, format).mimeType)
      .setMediaMetadata(/* ...unchanged... */)
      .build()
```

`core/media/src/main/kotlin/app/muplay/media/QueueRepository.kt` — it already computes the
`StreamFormat` to build the URL (Plan 3 Task 4 calls `StreamFormat.forSuffix`); pass that same
value to `MediaItems.of` rather than recomputing it, so the URL and the MIME cannot diverge.

`core/media/src/androidTest/kotlin/app/muplay/media/MediaItemsTest.kt` — add:

```kotlin
  @Test
  fun theMimeTypeIsTheServedFormatAndNotTheSourceSuffix() {
    // Two observations of the raw branch...
    assertThat(MediaItems.of(song(suffix = "mp3"), URI, null, false, StreamFormat.Raw)
      .localConfiguration!!.mimeType).isEqualTo("audio/mpeg")
    assertThat(MediaItems.of(song(suffix = "flac"), URI, null, false, StreamFormat.Raw)
      .localConfiguration!!.mimeType).isEqualTo("audio/flac")
    // ...and the transcode branch, where the suffix must NOT win. Without this, an Opus track is
    // announced to Sonos as audio/ogg while MP3 bytes are served -- spec section 12's "Sonos
    // rejects a served format" risk, arriving in its most confusing form.
    assertThat(MediaItems.of(song(suffix = "opus"), URI, null, false, StreamFormat.Mp3(192))
      .localConfiguration!!.mimeType).isEqualTo("audio/mpeg")
  }
```

- [ ] **Step 6: Run the device test**

Run: `./gradlew :core:media:connectedDebugAndroidTest --tests '*MediaItemsTest*'`
Expected: PASS, including every pre-existing field assertion Plan 3 wrote.

- [ ] **Step 7: Prove each new assertion can fail, record probes, measure, commit**

1. In `ServedMedia.of`, make the `Mp3` branch fall through to `RAW_TYPES[suffix]`. Expect
   `a transcode is served as mp3, whatever the source file was`,
   `opus never reaches a renderer, by construction` and
   `theMimeTypeIsTheServedFormatAndNotTheSourceSuffix` to fail. **The most important probe in this
   task.**
2. In `ServedMedia.protocolInfo`, hardcode `audio/mpeg`. Expect
   `protocolInfo names the mime type and declares byte-range seeking` to fail on its second
   observation.
3. In `ServedMedia.protocolInfo`, change `DLNA.ORG_OP=01` to `DLNA.ORG_OP=00`. Expect
   `protocolInfo declares no dlna profile name, on purpose` to fail — and note that Task 6 adds a
   second test that fails on this too, because `OP=01` is a promise the proxy keeps.
4. In `DidlLite.render`, drop `XmlText.escape` from `resourceUrl`. Expect
   `every text field is escaped, including in the res url` and
   `the rendered document is well-formed xml` to fail.
5. In `DidlLite.renderEscaped`, escape twice. Expect `renderEscaped is render escaped exactly once`
   and `didl survives being embedded in a soap envelope and read back out` to fail — and, in
   Task 5's suite, the fake to answer 714.
6. In `DidlLite.render`, render `dc:creator` unconditionally with an empty value when `artist` is
   null. Expect `an absent optional field is omitted rather than rendered empty` to fail.

Add 1, 3, 4 and 5 to `ci/mutation-probes.sh` as `didl/*` entries. Add
`"app.muplay.cast.didl.ServedMedia"`, `"app.muplay.cast.didl.ServedMedia*Companion"` and
`"app.muplay.cast.didl.DidlLite"` to `:core:cast`'s BRANCH floor `includes`, measure, and confirm
each clears 0.90.

```bash
./gradlew :core:cast:test jacocoTestReport jacocoJvmCoverageVerification
git add core/cast core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): DIDL-Lite metadata, and one statement of what format is served"
```

---

## Task 5: `UpnpRenderer` — `AVTransport`, `RenderingControl`, and the Sonos quirks

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/control/TransportState.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/control/RendererCapabilities.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/control/UpnpRenderer.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/control/TransportStateTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/control/RendererCapabilitiesTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/control/UpnpRendererTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `SoapClient`, `SoapArgument`, `UpnpErrorException`, `SoapTransportException`,
  `UpnpError`, `UpnpTime` (Task 3); `CastDevice`, `DeviceDescription.SERVICE_*` (Task 2);
  `CastItem`, `DidlLite` (Task 4); `CastHttpClient` (Task 1); `FakeRenderer` (Task 3).
- Produces:
  - `enum class TransportState { STOPPED, PLAYING, TRANSITIONING, PAUSED, RECORDING, NO_MEDIA, UNKNOWN }`
    with `companion object { fun fromWire(value: String?): TransportState }`
  - `data class TransportInfo(val state: TransportState, val hasError: Boolean)`
  - `data class PositionInfo(val positionMs: Long?, val durationMs: Long?, val trackUri: String?)`
    with `val isFollowingAnotherSpeaker: Boolean`
  - `data class RendererCapabilities(val seekModes: List<String>, val supportsSetNextUri: Boolean)`
    with `val preferredSeekMode: String?` and
    `companion object { fun fromScpd(xml: String): RendererCapabilities; val DEFAULT; const val REL_TIME; const val ABS_TIME }`
  - `class RendererFollowsAnotherException(val coordinatorUri: String) : IOException`
  - `class UpnpRenderer(device: CastDevice, soap: SoapClient, http: CastHttpClient)` with
    `suspend fun capabilities(): RendererCapabilities`, `suspend fun setUri(item: CastItem)`,
    `suspend fun setNextUri(item: CastItem?)`, `suspend fun play()`, `suspend fun pause()`,
    `suspend fun stop()`, `suspend fun seek(positionMs: Long): Boolean`,
    `suspend fun transportInfo(): TransportInfo`, `suspend fun positionInfo(): PositionInfo`,
    `suspend fun volume(): Int?`, `suspend fun setVolume(level: Int)`, `suspend fun setMuted(muted: Boolean)`,
    `companion object { const val INSTANCE_ID = "0"; const val PLAY_SPEED = "1"; const val VOLUME_CHANNEL = "Master" }`
- **Plan 4 interaction:** `play()` sends `Speed = "1"` and nothing else. A per-item playback speed
  from `media_progress.speed` (Plan 4's) **cannot be delivered** — see below. Task 8 reports 1.0×
  and Task 10 tells the user.

### The four Sonos quirks, and the one that is a scope decision

**1. `Play` requires `Speed`, and it must be `"1"`.** The `AVTransport:1` template makes
`TransportPlaySpeed` an argument of `Play`, with an allowed value list that every real renderer
sets to `{"1"}`. Sonos answers `402 Invalid Args` when it is missing and
`717 Play speed not supported` for anything else. `PLAY_SPEED` is a constant here for that reason.

**This is the Plan 4 interaction, and it is a hard protocol limit rather than an omission.** A book
with a stored 1.3× speed, cast to a speaker, plays at 1×. Nothing in this plan can change that: the
renderer decodes, and it decodes at one rate. What this plan owes is that the user is **told**,
which is Task 8's reported `PlaybackParameters` and Task 10's line of copy — not a silently ignored
setting, which is the failure class this project treats as the worst there is.

**2. `InstanceID` is `"0"`, always.** A single-zone renderer has exactly one transport instance.
Sonos answers `718 Invalid InstanceID` to anything else, and so does the fake.

**3. Seek modes are not universal.** `REL_TIME` is what Sonos accepts. Some renderers accept only
`ABS_TIME`; a few accept neither and expect `X_DLNA_REL_BYTE`. The wrong one is `710`.

The mechanism this task uses is to **read the device's own `AVTransport` SCPD** and take
`A_ARG_TYPE_SeekMode`'s `allowedValueList`, because that is where the device declares the answer.
The alternative — try `REL_TIME`, catch 710, retry `ABS_TIME` — was considered and rejected for a
specific reason: it makes the *first* seek of every session on an `ABS_TIME`-only device fail
visibly before succeeding, and it gives the UI no way to know in advance whether seeking works at
all. Reading the SCPD lets Task 8 report `COMMAND_SEEK_*` honestly in its `availableCommands`, so a
device that cannot seek shows **no seek bar** rather than one that does nothing. Offering a control
that silently fails is the exact defect class this plan is written against.

`seek()` still returns `Boolean` and still catches `710`, because an SCPD can lie and a `false`
from the one path that knows is better than an exception the UI has to interpret.

**4. A Sonos in a group follows a coordinator, and controlling the follower does nothing.** When a
speaker has been grouped in the Sonos app, the non-coordinator members' `AVTransport` `CurrentURI`
reads `x-rincon:RINCON_<coordinator-uuid>` — that is literally how Sonos expresses "I am playing
whatever that speaker is playing". `SetAVTransportURI` on a follower is accepted and has no audible
effect, because the follower keeps following.

**Detecting it is in scope; fixing it is not.** `ZoneGroupTopology` parsing, join and unjoin are a
whole subsystem and this plan's scope section rules them out. What this task does is **look**: if
`GetPositionInfo` comes back with a `TrackURI` starting `x-rincon:`, throw
`RendererFollowsAnotherException` so Task 10 can say *"Kitchen is grouped with another speaker;
ungroup it in the Sonos app to cast to it"*. That sentence is worth more to a user than a working
implementation of a feature they did not ask for, and infinitely more than silence.

> **Written from the protocol, and hedged where it should be.** The `x-rincon:` scheme and the
> control port 1400 are stable, long-documented Sonos behaviour. They are nonetheless *vendor*
> behaviour with no specification this project can cite, so `UpnpRendererTest` exercises them
> against the fake and Task 11's definition of done lists "Sonos group-follower detection" among
> the claims only real hardware can settle.

- [ ] **Step 1: Write the failing state and capability tests**

`core/cast/src/test/kotlin/app/muplay/cast/control/TransportStateTest.kt`:

```kotlin
package app.muplay.cast.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransportStateTest {

  @Test
  fun `every wire value the AVTransport template defines maps to a state`() {
    // The exact mapped list, in order. One `isEqualTo` per value would leave a reader unable to
    // see which values are covered at all, and an `allMatch` would be vacuous on an empty input.
    val wire = listOf(
      "STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "PAUSED_RECORDING",
      "RECORDING", "NO_MEDIA_PRESENT",
    )

    assertThat(wire.map { TransportState.fromWire(it) }).containsExactly(
      TransportState.STOPPED,
      TransportState.PLAYING,
      TransportState.TRANSITIONING,
      TransportState.PAUSED,
      TransportState.PAUSED,
      TransportState.RECORDING,
      TransportState.NO_MEDIA,
    )
  }

  @Test
  fun `an unrecognised or missing value is UNKNOWN and not STOPPED`() {
    // The distinction matters in Task 8: `STOPPED` after `PLAYING` at the end of a track means
    // "advance to the next one", and a parse failure that read as `STOPPED` would skip a track
    // every time a renderer sent something unexpected.
    assertThat(TransportState.fromWire("SOMETHING_NEW")).isEqualTo(TransportState.UNKNOWN)
    assertThat(TransportState.fromWire(null)).isEqualTo(TransportState.UNKNOWN)
    assertThat(TransportState.fromWire("")).isEqualTo(TransportState.UNKNOWN)
  }

  @Test
  fun `the value is matched case-insensitively and trimmed`() {
    assertThat(TransportState.fromWire(" PLAYING ")).isEqualTo(TransportState.PLAYING)
    assertThat(TransportState.fromWire("playing")).isEqualTo(TransportState.PLAYING)
  }
}
```

`core/cast/src/test/kotlin/app/muplay/cast/control/RendererCapabilitiesTest.kt`:

```kotlin
package app.muplay.cast.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RendererCapabilitiesTest {

  private fun scpd(seekModes: List<String>, actions: List<String>) = """
    <?xml version="1.0"?>
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>
        ${actions.joinToString("") { "<action><name>$it</name></action>" }}
      </actionList>
      <serviceStateTable>
        <stateVariable sendEvents="no">
          <name>A_ARG_TYPE_SeekMode</name>
          <dataType>string</dataType>
          <allowedValueList>
            ${seekModes.joinToString("") { "<allowedValue>$it</allowedValue>" }}
          </allowedValueList>
        </stateVariable>
      </serviceStateTable>
    </scpd>
  """.trimIndent()

  @Test
  fun `the declared seek modes are read, in the order the device declared them`() {
    // Order is a property: `preferredSeekMode` prefers REL_TIME when offered, and falls back to
    // the device's own first choice otherwise, which is only meaningful if the order survives.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR", "REL_TIME", "X_DLNA_REL_BYTE"), listOf("Play")))
        .seekModes,
    ).containsExactly("TRACK_NR", "REL_TIME", "X_DLNA_REL_BYTE")
  }

  @Test
  fun `rel time is preferred when the device offers it`() {
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR", "REL_TIME"), listOf("Play"))).preferredSeekMode,
    ).isEqualTo(RendererCapabilities.REL_TIME)
  }

  @Test
  fun `abs time is used when rel time is not offered`() {
    // The second observation. Without it, `preferredSeekMode` hardcoded to REL_TIME passes the
    // test above and produces a 710 on every seek against a real ABS_TIME-only renderer.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("ABS_TIME", "TRACK_NR"), listOf("Play"))).preferredSeekMode,
    ).isEqualTo(RendererCapabilities.ABS_TIME)
  }

  @Test
  fun `a device offering neither time mode reports that it cannot seek`() {
    // Null, not a default. Task 8 turns this into "no seek bar", which is the honest UI for a
    // device that cannot seek -- rather than a bar that produces a 710 on every drag.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR"), listOf("Play"))).preferredSeekMode,
    ).isNull()
  }

  @Test
  fun `SetNextAVTransportURI is detected from the action list, both ways`() {
    // Two observations of one boolean. It gates gapless-ish queueing in Task 8, and a hardcoded
    // `true` produces a 401 on every track transition against a device that lacks it.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("REL_TIME"), listOf("Play", "SetNextAVTransportURI")))
        .supportsSetNextUri,
    ).isTrue
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("REL_TIME"), listOf("Play", "Stop"))).supportsSetNextUri,
    ).isFalse
  }

  @Test
  fun `an unreadable scpd falls back to the conservative default rather than throwing`() {
    // A device whose SCPD 404s is still castable; it just cannot be asked what it supports. The
    // default assumes REL_TIME (what Sonos and most renderers accept) and NO SetNextAVTransportURI
    // -- optimistic where being wrong costs a failed seek, pessimistic where being wrong costs a
    // failed track transition.
    assertThat(RendererCapabilities.fromScpd("<html>404</html>")).isEqualTo(RendererCapabilities.DEFAULT)
    assertThat(RendererCapabilities.DEFAULT.seekModes).containsExactly(RendererCapabilities.REL_TIME)
    assertThat(RendererCapabilities.DEFAULT.supportsSetNextUri).isFalse
  }
}
```

- [ ] **Step 2: Implement the state and capability types**

`core/cast/src/main/kotlin/app/muplay/cast/control/TransportState.kt`:

```kotlin
package app.muplay.cast.control

/**
 * `CurrentTransportState`, from the `AVTransport:1` service template.
 *
 * `PAUSED_PLAYBACK` and `PAUSED_RECORDING` collapse into one member because MuPlay never records
 * and the difference is meaningless to it. Everything else is kept distinct, and in particular
 * [UNKNOWN] is **not** folded into [STOPPED]: Task 8 treats `STOPPED` after `PLAYING` as "the
 * track ended, advance", and a parse failure that read as `STOPPED` would skip a track every time
 * a renderer sent something this enum had not seen.
 */
enum class TransportState {
  STOPPED, PLAYING, TRANSITIONING, PAUSED, RECORDING, NO_MEDIA, UNKNOWN;

  companion object {
    fun fromWire(value: String?): TransportState = when (value?.trim()?.uppercase()) {
      "STOPPED" -> STOPPED
      "PLAYING" -> PLAYING
      "TRANSITIONING" -> TRANSITIONING
      "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PAUSED
      "RECORDING" -> RECORDING
      "NO_MEDIA_PRESENT" -> NO_MEDIA
      else -> UNKNOWN
    }
  }
}

/** `GetTransportInfo`'s answer. */
data class TransportInfo(val state: TransportState, val hasError: Boolean)

/** `GetPositionInfo`'s answer, in units this app uses. */
data class PositionInfo(val positionMs: Long?, val durationMs: Long?, val trackUri: String?) {
  /**
   * Whether this speaker is a **follower in a Sonos group**.
   *
   * A grouped Sonos that is not the coordinator reports `TrackURI = x-rincon:RINCON_<uuid>`, which
   * is Sonos's way of saying "I play whatever that speaker plays". `SetAVTransportURI` on it is
   * accepted and does nothing audible.
   */
  val isFollowingAnotherSpeaker: Boolean get() = trackUri?.startsWith("x-rincon:") == true
}
```

`core/cast/src/main/kotlin/app/muplay/cast/control/RendererCapabilities.kt`:

```kotlin
package app.muplay.cast.control

/**
 * What a renderer says it can do, read from its own `AVTransport` service description.
 *
 * Read rather than guessed, and rather than discovered by trying: a device that cannot seek should
 * show **no seek bar** (Task 8 reports this in `availableCommands`), not one that produces
 * `710 Seek mode not supported` on every drag. Offering a control that silently fails is the
 * defect class this plan is written against.
 */
data class RendererCapabilities(
  /** `A_ARG_TYPE_SeekMode`'s `allowedValueList`, in the order the device declared it. */
  val seekModes: List<String>,
  val supportsSetNextUri: Boolean,
) {
  /**
   * The mode to seek with, or `null` when this device cannot seek by time at all.
   *
   * [REL_TIME] is preferred where offered because it means "relative to the start of the track",
   * which is the position this app has. [ABS_TIME] is the fallback. `TRACK_NR` and
   * `X_DLNA_REL_BYTE` are seek modes but not *time* seek modes, and a byte offset is not something
   * this app can compute for a transcoded stream.
   */
  val preferredSeekMode: String?
    get() = when {
      REL_TIME in seekModes -> REL_TIME
      ABS_TIME in seekModes -> ABS_TIME
      else -> null
    }

  companion object {
    const val REL_TIME: String = "REL_TIME"
    const val ABS_TIME: String = "ABS_TIME"

    /**
     * What is assumed when the SCPD cannot be read.
     *
     * Optimistic about seeking (`REL_TIME` is what Sonos and almost every renderer accepts; being
     * wrong costs one failed seek and a `false` return) and pessimistic about
     * `SetNextAVTransportURI` (being wrong there costs a `401` at every track transition, which is
     * a gap in the middle of an album).
     */
    val DEFAULT = RendererCapabilities(seekModes = listOf(REL_TIME), supportsSetNextUri = false)

    fun fromScpd(xml: String): RendererCapabilities {
      val modes = Regex(
        "<name>\\s*A_ARG_TYPE_SeekMode\\s*</name>.*?<allowedValueList>(.*?)</allowedValueList>",
        RegexOption.DOT_MATCHES_ALL,
      ).find(xml)?.groupValues?.get(1)
        ?.let { block -> Regex("<allowedValue>\\s*(.*?)\\s*</allowedValue>").findAll(block)
          .map { it.groupValues[1] }.toList() }
        ?: return DEFAULT

      if (modes.isEmpty()) return DEFAULT
      return RendererCapabilities(
        seekModes = modes,
        supportsSetNextUri = Regex("<name>\\s*SetNextAVTransportURI\\s*</name>").containsMatchIn(xml),
      )
    }
  }
}
```

> **A regex over the SCPD rather than a DOM parse, and this is the one place in this plan where
> that is the right answer.** The SCPD is read for exactly two facts, both of which are flat
> string lists inside uniquely-named elements; a DOM walk would be twenty lines to reach the same
> two values, and the document is not one this app acts on structurally. `DeviceDescription` gets
> a real parse because its output drives every URL this client dials. If a later plan needs more
> of the SCPD than these two facts, that is the point to promote it.

- [ ] **Step 3: Write the failing renderer test**

`core/cast/src/test/kotlin/app/muplay/cast/control/UpnpRendererTest.kt` — against the **real**
`FakeRenderer` from Task 3, discovered through the **real** `RendererDirectory` path so that the
control URLs under test are the ones description parsing produced.

```kotlin
package app.muplay.cast.control

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.fake.FakeRenderer
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.SoapTransportException
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpErrorException
import app.muplay.model.StreamFormat
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class UpnpRendererTest {

  private var fake: FakeRenderer? = null

  @AfterEach fun tearDown() { fake?.close() }

  private fun renderer(
    strictness: FakeRenderer.Strictness = FakeRenderer.Strictness(),
    identity: FakeRenderer.Identity = FakeRenderer.Identity(),
  ): UpnpRenderer {
    val running = FakeRenderer(strictness, identity).also { fake = it; it.start() }
    val http = CastHttpClient()
    val device = CastDevice.from(
      DeviceDescription.parse(http.exchange(running.descriptionUrl, "GET").bodyText(), running.descriptionUrl),
      running.descriptionUrl,
    )!!
    return UpnpRenderer(device, SoapClient(http), http)
  }

  private fun item(id: String = "track-1", suffix: String = "mp3", durationMs: Long = 300_000L) = CastItem(
    mediaId = id,
    title = "Track 1",
    artist = "Test Artist",
    albumTitle = "Test Album",
    artworkUri = null,
    durationMs = durationMs,
    upnpClass = DidlLite.CLASS_MUSIC_TRACK,
    resourceUrl = "http://127.0.0.1:9/media/$id.$suffix",
    served = ServedMedia.of(suffix, StreamFormat.Raw),
  )

  @Test
  fun `setting a uri sends the three arguments in the declared order, with escaped metadata`() = runTest {
    val renderer = renderer()

    renderer.setUri(item())

    // Read off the bytes the device recorded, not off what this test passed in.
    val request = fake!!.soapRequests.last()
    assertThat(request.action).isEqualTo("SetAVTransportURI")
    assertThat(request.arguments.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    assertThat(request.arguments[0].second).isEqualTo("0")
    assertThat(request.arguments[1].second).isEqualTo("http://127.0.0.1:9/media/track-1.mp3")
    assertThat(request.arguments[2].second).startsWith("&lt;DIDL-Lite")
    assertThat(request.arguments[2].second).doesNotContain("&amp;lt;")
    assertThat(request.rawSoapAction)
      .isEqualTo("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
  }

  @Test
  fun `the uri that reached the device is the one the item carried`() = runTest {
    // Two observations, so `CurrentURI` cannot be a constant.
    val renderer = renderer()

    renderer.setUri(item("track-1", "mp3"))
    renderer.setUri(item("chapter-14", "m4b"))

    assertThat(fake!!.soapRequests.filter { it.action == "SetAVTransportURI" }.map { it.arguments[1].second })
      .containsExactly(
        "http://127.0.0.1:9/media/track-1.mp3",
        "http://127.0.0.1:9/media/chapter-14.m4b",
      )
  }

  @Test
  fun `play sends speed 1 and moves the device into PLAYING`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())

    renderer.play()

    assertThat(fake!!.soapRequests.last().arguments)
      .containsExactly("InstanceID" to "0", "Speed" to "1")
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.PLAYING)
  }

  @Test
  fun `pause and stop reach the device and change its state`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())
    renderer.play()

    renderer.pause()
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.PAUSED)

    renderer.stop()
    assertThat(renderer.transportInfo().state).isEqualTo(TransportState.STOPPED)
  }

  @Test
  fun `seeking sends the preferred mode and a clock target, and the device moves`() = runTest {
    val renderer = renderer()
    renderer.setUri(item())

    assertThat(renderer.seek(83_000L)).isTrue

    val request = fake!!.soapRequests.last()
    assertThat(request.arguments).containsExactly(
      "InstanceID" to "0",
      "Unit" to "REL_TIME",
      "Target" to "0:01:23",
    )
    // ...and the *effect*, not only the request: the device's own position moved.
    assertThat(renderer.positionInfo().positionMs).isEqualTo(83_000L)
  }

  @Test
  fun `a second seek lands somewhere else`() = runTest {
    // The observation that stops `Target` being a constant and stops the position readout being one.
    val renderer = renderer()
    renderer.setUri(item())

    renderer.seek(83_000L)
    renderer.seek(7_000L)

    assertThat(fake!!.soapRequests.last().arguments.last()).isEqualTo("Target" to "0:00:07")
    assertThat(renderer.positionInfo().positionMs).isEqualTo(7_000L)
  }

  @Test
  fun `a device that only accepts ABS_TIME is seeked with ABS_TIME`() = runTest {
    // The SCPD-driven branch, observed changing behaviour. Without this, `preferredSeekMode` could
    // be hardcoded and every test above would still pass.
    val renderer = renderer(
      FakeRenderer.Strictness(supportedSeekModes = listOf(RendererCapabilities.ABS_TIME)),
    )
    renderer.setUri(item())

    assertThat(renderer.seek(10_000L)).isTrue
    assertThat(fake!!.soapRequests.last().arguments).contains("Unit" to "ABS_TIME")
  }

  @Test
  fun `a device that cannot seek by time reports so, and seek returns false without throwing`() = runTest {
    val renderer = renderer(FakeRenderer.Strictness(supportedSeekModes = listOf("TRACK_NR")))
    renderer.setUri(item())

    assertThat(renderer.capabilities().preferredSeekMode).isNull()
    assertThat(renderer.seek(10_000L)).isFalse
    // No Seek request was even attempted -- the UI is told in advance, rather than after a failure.
    assertThat(fake!!.soapRequests.none { it.action == "Seek" }).isTrue
  }

  @Test
  fun `a seek past the end returns false rather than throwing`() = runTest {
    val renderer = renderer()
    renderer.setUri(item(durationMs = 10_000L))

    assertThat(renderer.seek(99_000L)).isFalse
  }

  @Test
  fun `position info comes back with the position, the duration and the track uri`() = runTest {
    val renderer = renderer()
    renderer.setUri(item(durationMs = 300_000L))
    renderer.play()
    fake!!.advance(42_000L)

    val info = renderer.positionInfo()

    // Every field, and the duration from the DIDL the device was given -- which is the round trip
    // through `res@duration` that no unit test of `DidlLite` alone can observe.
    assertThat(info.positionMs).isEqualTo(42_000L)
    assertThat(info.durationMs).isEqualTo(300_000L)
    assertThat(info.trackUri).isEqualTo("http://127.0.0.1:9/media/track-1.mp3")
    assertThat(info.isFollowingAnotherSpeaker).isFalse
  }

  @Test
  fun `volume is read and written, and the value that comes back is the one that went in`() = runTest {
    val renderer = renderer()

    renderer.setVolume(17)
    assertThat(renderer.volume()).isEqualTo(17)
    renderer.setVolume(64)
    assertThat(renderer.volume()).isEqualTo(64)

    assertThat(fake!!.soapRequests.last { it.action == "SetVolume" }.arguments)
      .containsExactly("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "64")
  }

  @Test
  fun `a volume outside 0 to 100 is clamped rather than sent and refused`() = runTest {
    val renderer = renderer()

    renderer.setVolume(-5)
    assertThat(renderer.volume()).isZero
    renderer.setVolume(150)
    assertThat(renderer.volume()).isEqualTo(100)
  }

  @Test
  fun `a device with no RenderingControl reports no volume instead of throwing`() = runTest {
    val renderer = renderer(identity = FakeRenderer.Identity(hasRenderingControl = false))

    assertThat(renderer.volume()).isNull()
    // ...and setting it is a no-op rather than an exception the UI has to swallow.
    renderer.setVolume(50)
  }

  /**
   * The Sonos group quirk. A follower accepts `SetAVTransportURI` and plays nothing; detecting it
   * is what turns silence into a sentence the user can act on.
   */
  @Test
  fun `a sonos following another speaker is detected and named`() = runTest {
    val renderer = renderer(
      identity = FakeRenderer.Identity(followingCoordinator = "x-rincon:RINCON_OTHER01400"),
    )

    assertThatExceptionOfType(RendererFollowsAnotherException::class.java)
      .isThrownBy { kotlinx.coroutines.runBlocking { renderer.setUri(item()) } }
      .withMessageContaining("RINCON_OTHER01400")
      .withMessageContaining("grouped")
  }

  @Test
  fun `a speaker that is not following is not reported as following`() = runTest {
    // The other direction. Without it, a check that threw unconditionally would pass the test
    // above and make every cast fail.
    val renderer = renderer()

    renderer.setUri(item())

    assertThat(renderer.positionInfo().isFollowingAnotherSpeaker).isFalse
  }

  @Test
  fun `a refused action surfaces the device's own error code`() = runTest {
    val renderer = renderer()

    // Play with nothing set: the fake answers 701, exactly as a real device does.
    assertThatExceptionOfType(UpnpErrorException::class.java)
      .isThrownBy { kotlinx.coroutines.runBlocking { renderer.play() } }
      .matches { it.fault.errorCode == UpnpError.TRANSITION_NOT_AVAILABLE }
  }

  @Test
  fun `a renderer that has gone away is a transport failure and not a upnp error`() = runTest {
    // The distinction Task 8's fallback branches on: a UPnP error means "the device said no", a
    // transport failure means "the device is not there". Collapsing them would make a 714 tear
    // down the session and a dead speaker look like a rejected format.
    val renderer = renderer()
    renderer.setUri(item())
    fake!!.disappear()

    assertThatExceptionOfType(SoapTransportException::class.java)
      .isThrownBy { kotlinx.coroutines.runBlocking { renderer.play() } }
  }

  @Test
  fun `capabilities are fetched once and not on every seek`() = runTest {
    // A SCPD fetch per seek would add a round trip to every drag of the seek bar. Asserted by
    // counting the media-independent requests the fake saw.
    val renderer = renderer()
    renderer.setUri(item())

    renderer.seek(1_000L)
    renderer.seek(2_000L)
    renderer.seek(3_000L)

    assertThat(renderer.capabilities()).isSameAs(renderer.capabilities())
  }
}
```

- [ ] **Step 4: Implement `UpnpRenderer`**

`core/cast/src/main/kotlin/app/muplay/cast/control/UpnpRenderer.kt`:

```kotlin
package app.muplay.cast.control

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DeviceDescription
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.soap.SoapArgument
import app.muplay.cast.soap.SoapClient
import app.muplay.cast.soap.UpnpError
import app.muplay.cast.soap.UpnpErrorException
import app.muplay.cast.soap.UpnpTime
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A Sonos speaker that has been grouped with another in the Sonos app, and is following it.
 *
 * `SetAVTransportURI` on a follower is **accepted** and produces no sound, because the follower
 * keeps following. Detecting that and saying so is worth more to a user than a silent success.
 */
class RendererFollowsAnotherException(val coordinatorUri: String) : IOException(
  "this speaker is grouped with another and is following $coordinatorUri. Ungroup it in the " +
    "Sonos app, or cast to the group's coordinator instead.",
)

/**
 * `AVTransport` and `RenderingControl` on one renderer.
 *
 * Every action sends `InstanceID = "0"`: a single-zone renderer has exactly one transport
 * instance, and Sonos answers `718 Invalid InstanceID` to anything else.
 *
 * `play()` sends `Speed = "1"` and can send nothing else. `TransportPlaySpeed`'s allowed value list
 * is `{"1"}` on every renderer this plan targets, and Sonos answers `717` for anything else --
 * which means **a book's per-item playback speed cannot be delivered to a speaker**. That is a
 * protocol limit, not an omission; Task 8 reports `PlaybackParameters(1.0f)` and Task 10 tells the
 * user, rather than accepting a setting that silently does nothing.
 */
class UpnpRenderer(
  private val device: CastDevice,
  private val soap: SoapClient,
  private val http: CastHttpClient,
) {

  private val capabilitiesLock = Mutex()
  private var cachedCapabilities: RendererCapabilities? = null

  /** Fetched once per renderer. A SCPD read per seek would add a round trip to every drag. */
  suspend fun capabilities(): RendererCapabilities = capabilitiesLock.withLock {
    cachedCapabilities ?: loadCapabilities().also { cachedCapabilities = it }
  }

  private suspend fun loadCapabilities(): RendererCapabilities = withContext(Dispatchers.IO) {
    val url = device.avTransportScpdUrl ?: return@withContext RendererCapabilities.DEFAULT
    runCatching { RendererCapabilities.fromScpd(http.exchange(url, "GET").bodyText()) }
      .getOrDefault(RendererCapabilities.DEFAULT)
  }

  suspend fun setUri(item: CastItem) {
    // Look BEFORE setting: a grouped Sonos accepts the call and plays nothing, so checking
    // afterwards would leave a session that looks established and is not.
    positionInfo().trackUri
      ?.takeIf { it.startsWith(RINCON_FOLLOW_SCHEME) }
      ?.let { throw RendererFollowsAnotherException(it) }

    avTransport(
      "SetAVTransportURI",
      // In the order the service description declares. See SoapArgument's KDoc.
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("CurrentURI", item.resourceUrl),
        // Escaped exactly once, by `renderEscaped`. Escaping it again here is the
        // `&amp;lt;DIDL-Lite` defect; not escaping it breaks the envelope.
        SoapArgument("CurrentURIMetaData", DidlLite.renderEscaped(item)),
      ),
    )
  }

  /**
   * Queues the next track on devices that support it, for a transition with no gap of our making.
   *
   * A no-op when the device did not declare `SetNextAVTransportURI` -- calling it anyway returns
   * `401` and, on some firmware, clears the current queue.
   */
  suspend fun setNextUri(item: CastItem?) {
    if (!capabilities().supportsSetNextUri) return
    avTransport(
      "SetNextAVTransportURI",
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("NextURI", item?.resourceUrl.orEmpty()),
        SoapArgument("NextURIMetaData", item?.let { DidlLite.renderEscaped(it) }.orEmpty()),
      ),
    )
  }

  suspend fun play() {
    avTransport(
      "Play",
      listOf(SoapArgument("InstanceID", INSTANCE_ID), SoapArgument("Speed", PLAY_SPEED)),
    )
  }

  suspend fun pause() = avTransport("Pause", listOf(SoapArgument("InstanceID", INSTANCE_ID))).let { }

  suspend fun stop() = avTransport("Stop", listOf(SoapArgument("InstanceID", INSTANCE_ID))).let { }

  /**
   * Seeks, returning whether the device actually did.
   *
   * `false` rather than an exception for the two ordinary refusals -- no time seek mode, or a
   * target the device calls illegal -- because the caller is a `Player` and a `Player` that threw
   * on a seek would take the session down over a dragged progress bar. A transport failure still
   * propagates: that one means the speaker is gone.
   */
  suspend fun seek(positionMs: Long): Boolean {
    val mode = capabilities().preferredSeekMode ?: return false
    return try {
      avTransport(
        "Seek",
        listOf(
          SoapArgument("InstanceID", INSTANCE_ID),
          SoapArgument("Unit", mode),
          SoapArgument("Target", UpnpTime.formatClock(positionMs)),
        ),
      )
      true
    } catch (refused: UpnpErrorException) {
      // An SCPD can lie, and a target past the end is a legitimate refusal. Both are `false`.
      if (refused.fault.errorCode in SEEK_REFUSALS) false else throw refused
    }
  }

  suspend fun transportInfo(): TransportInfo {
    val out = avTransport("GetTransportInfo", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
    return TransportInfo(
      state = TransportState.fromWire(out["CurrentTransportState"]),
      // `ERROR_OCCURRED` is how a renderer reports that it could not play what it was given --
      // the format was wrong, or the URL 404'd. Task 8 turns it into a player error rather than a
      // track that silently never starts.
      hasError = out["CurrentTransportStatus"]?.trim()?.uppercase() == "ERROR_OCCURRED",
    )
  }

  suspend fun positionInfo(): PositionInfo {
    val out = avTransport("GetPositionInfo", listOf(SoapArgument("InstanceID", INSTANCE_ID)))
    return PositionInfo(
      // `null`, not `0`, when the device says NOT_IMPLEMENTED -- see UpnpTime.parseClock.
      positionMs = UpnpTime.parseClock(out["RelTime"]),
      durationMs = UpnpTime.parseClock(out["TrackDuration"]),
      trackUri = out["TrackURI"]?.takeIf { it.isNotBlank() },
    )
  }

  /** `null` when the device has no `RenderingControl` -- the UI then shows no slider at all. */
  suspend fun volume(): Int? {
    val controlUrl = device.renderingControlUrl ?: return null
    return soap.invoke(
      controlUrl,
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "GetVolume",
      listOf(SoapArgument("InstanceID", INSTANCE_ID), SoapArgument("Channel", VOLUME_CHANNEL)),
    )["CurrentVolume"]?.toIntOrNull()
  }

  suspend fun setVolume(level: Int) {
    val controlUrl = device.renderingControlUrl ?: return
    soap.invoke(
      controlUrl,
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "SetVolume",
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("Channel", VOLUME_CHANNEL),
        // Clamped here rather than sent and refused: a slider's rounding must not become a 402.
        SoapArgument("DesiredVolume", level.coerceIn(MIN_VOLUME, MAX_VOLUME).toString()),
      ),
    )
  }

  suspend fun setMuted(muted: Boolean) {
    val controlUrl = device.renderingControlUrl ?: return
    soap.invoke(
      controlUrl,
      DeviceDescription.SERVICE_RENDERING_CONTROL,
      "SetMute",
      listOf(
        SoapArgument("InstanceID", INSTANCE_ID),
        SoapArgument("Channel", VOLUME_CHANNEL),
        SoapArgument("DesiredMute", if (muted) "1" else "0"),
      ),
    )
  }

  private suspend fun avTransport(action: String, arguments: List<SoapArgument>): Map<String, String> =
    soap.invoke(device.avTransportControlUrl, DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)

  companion object {
    /** A single-zone renderer has exactly one transport instance. Sonos answers 718 to anything else. */
    const val INSTANCE_ID: String = "0"

    /** `TransportPlaySpeed`'s only allowed value on every renderer this plan targets. */
    const val PLAY_SPEED: String = "1"

    /** `RenderingControl` channels are `Master`, `LF`, `RF`; only `Master` is universal. */
    const val VOLUME_CHANNEL: String = "Master"

    const val MIN_VOLUME: Int = 0
    const val MAX_VOLUME: Int = 100

    /** Sonos's way of saying "I play whatever that speaker plays". */
    private const val RINCON_FOLLOW_SCHEME = "x-rincon:"

    private val SEEK_REFUSALS =
      setOf(UpnpError.SEEK_MODE_NOT_SUPPORTED, UpnpError.ILLEGAL_SEEK_TARGET)
  }
}
```

- [ ] **Step 5: Run, mutate, measure, commit**

Run: `./gradlew :core:cast:test --tests '*UpnpRendererTest*' --tests '*TransportStateTest*' --tests '*RendererCapabilitiesTest*'` — PASS.

Mutations, each restored after:

1. `PLAY_SPEED` → `"1.0"`. Expect `play sends speed 1 and moves the device into PLAYING` to fail —
   and note the fake answers **717**, which is the real device's answer. This is why the fake sends
   a code rather than a plain 500.
2. `INSTANCE_ID` → `"1"`. Expect every renderer test to fail with 718.
3. In `seek`, hardcode `RendererCapabilities.REL_TIME`. Expect
   `a device that only accepts ABS_TIME is seeked with ABS_TIME` and
   `a device that cannot seek by time reports so...` to fail.
4. In `seek`, remove the `SEEK_REFUSALS` catch. Expect `a seek past the end returns false rather
   than throwing` to fail with a `UpnpErrorException`.
5. In `setUri`, remove the `x-rincon:` check. Expect `a sonos following another speaker is detected
   and named` to fail. Then make it throw unconditionally and expect
   `a speaker that is not following is not reported as following` and every other test to fail —
   both directions.
6. In `setVolume`, remove `coerceIn`. Expect `a volume outside 0 to 100 is clamped...` to fail with
   a 402 from the fake.
7. In `transportInfo`, hardcode `hasError = false`. Expect the Task 8 test
   `a renderer reporting ERROR_OCCURRED becomes a player error` to fail — write that assertion here
   as a forward note if Task 8 has not landed.
8. In `TransportState.fromWire`, map the `else` branch to `STOPPED`. Expect
   `an unrecognised or missing value is UNKNOWN and not STOPPED` to fail.

Record 1, 3, 5 and 8 in `ci/mutation-probes.sh` as `control/*` entries. Add
`"app.muplay.cast.control.TransportState"`, `"app.muplay.cast.control.PositionInfo"`,
`"app.muplay.cast.control.RendererCapabilities"`,
`"app.muplay.cast.control.RendererCapabilities*Companion"` and
`"app.muplay.cast.control.UpnpRenderer"` to the `:core:cast` BRANCH floor, measure, confirm ≥ 0.90.

```bash
./gradlew :core:cast:test jacocoTestReport jacocoJvmCoverageVerification
git add core/cast build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): AVTransport and RenderingControl, with the Sonos quirks named"
```

---

## Task 6: The proxy — a range-serving HTTP/1.1 server, and the token that is not a track id

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/proxy/RangeHeader.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyRegistry.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyUpstream.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/proxy/MediaProxyServer.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/proxy/RangeHeaderTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/proxy/ProxyRegistryTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/proxy/MediaProxyServerTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/proxy/LiveNavidromeProxyTest.kt`
- Modify: `build.gradle.kts` (`:core:cast` floors; `liveNavidromeTest` registered for `:core:cast`)
- Modify: `.github/workflows/pr.yml` (the `live-navidrome` job runs `:core:cast:liveNavidromeTest`)
- Modify: `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `HttpWire`, `HttpHeaders`, `HttpRequestHead`, `MalformedHttpException` (Task 1);
  `ServedMedia` (Task 4); `FakeRenderer` (Task 3).
- Produces:
  - `data class ByteRange(val firstByte: Long, val lastByte: Long)` with `val length: Long`
  - `sealed interface RangeRequest` with `data object Absent`, `data object Ignored`,
    `data class Bounded(first: Long, last: Long?)`, `data class Suffix(val lastBytes: Long)`
  - `sealed interface RangeResolution` with `data object Whole`, `data class Partial(val range: ByteRange)`,
    `data object Unsatisfiable`
  - `object RangeHeader` with `fun parse(value: String?): RangeRequest` and
    `fun resolve(request: RangeRequest, totalLength: Long): RangeResolution`
  - `data class PublishedMedia(val token: String, val path: String, val upstreamUrl: String, val served: ServedMedia)`
  - `class ProxyRegistry(random: SecureRandom = SecureRandom())` with
    `fun publish(upstreamUrl: String, served: ServedMedia): PublishedMedia`,
    `fun resolve(path: String): PublishedMedia?`, `fun revoke(token: String)`, `fun revokeAll()`,
    `companion object { const val TOKEN_BYTES = 16; const val PATH_PREFIX = "/media/" }`
  - `interface ProxyUpstream` with `fun totalLength(url: String): Long?` and
    `fun open(url: String, range: ByteRange): InputStream`
  - `class UpstreamThrottledException(val retryAfterSeconds: Long?) : IOException`
  - `object ProxyRetry` with
    `fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, attempt: Int): Long?`,
    `const val TOO_MANY_REQUESTS = 429`, `const val MAX_ATTEMPTS = 4`,
    `const val BASE_BACKOFF_MS = 500L`, `const val MAX_BACKOFF_MS = 8_000L`
  - `class OkHttpProxyUpstream(client: OkHttpClient, retry: ProxyRetry = ProxyRetry) : ProxyUpstream`
  - `class MediaProxyServer(upstream, registry, bindAddress, requestedPort = 0)` with
    `fun start(): Int`, `val port: Int`, `fun urlFor(media: PublishedMedia, host: String): String`,
    `val requestLog: List<ProxyRequest>`, `fun awaitRequest(token: String, timeoutMs: Long): Boolean`,
    `fun close()`
  - `data class ProxyRequest(val method: String, val token: String?, val rangeHeader: String?, val status: Int)`
- **Plan 4 interaction:** none.

### Why the path carries a token and not a track id

`/media/track-42.mp3` would be simpler and is wrong for two reasons.

**It is an open relay for the whole library.** The proxy binds on the LAN. Anything on that LAN
could then fetch any track by guessing an id — and Navidrome ids are guessable. A random 128-bit
token published only for the items in the current cast session, and revoked when the session ends,
means the proxy serves exactly what the user chose to cast and nothing else.

**It leaks what is being listened to.** A path containing a stable id lets anything watching the
LAN correlate sessions. A per-session token does not.

The path still ends in `.mp3` — the extension is not optional. Spec §6: *"The URL's extension must
match the real format, because Sonos sniffs the URL."* Task 3's `FakeRenderer` answers
`714 Illegal MIME-type` to an extensionless URL, so this is enforced by a test rather than by a
comment.

### The Range table, because `bytes=0-` proves nothing

A test that only ever asks for `bytes=0-` exercises no offset arithmetic, never builds a
`Content-Range`, and never reaches the 416 boundary — and `bytes=0-` is exactly what a naive
renderer sends first, so it is the request most likely to be the only one tested. Eleven cases,
against a 1000-byte resource:

| `Range` | Status | `Content-Range` | Body |
|---|---|---|---|
| *(absent)* | 200 | *(none)* | all 1000 |
| `bytes=0-` | 206 | `bytes 0-999/1000` | all 1000 |
| `bytes=100-199` | 206 | `bytes 100-199/1000` | bytes 100–199 |
| `bytes=999-` | 206 | `bytes 999-999/1000` | the last byte |
| `bytes=-1` | 206 | `bytes 999-999/1000` | the last byte |
| `bytes=-500` | 206 | `bytes 500-999/1000` | the last 500 |
| `bytes=0-99999` | 206 | `bytes 0-999/1000` | all 1000 (clamped) |
| `bytes=1000-` | **416** | `bytes */1000` | empty |
| `bytes=-0` | **416** | `bytes */1000` | empty |
| `bytes=abc` | 200 | *(none)* | all 1000 |
| `bytes=0-0,10-20` | 200 | *(none)* | all 1000 |

Two distinctions in that table are RFC 7233 and are easy to collapse into one wrong answer:

- **Malformed is not unsatisfiable.** `bytes=abc` and a multi-range request are *ignored* — the
  server answers 200 with the whole entity, which RFC 7233 explicitly permits. `bytes=1000-` on a
  1000-byte resource is *unsatisfiable* and must be **416 with `Content-Range: bytes */1000`**. A
  server that 416s a malformed header refuses a request it should have served; one that 200s an
  unsatisfiable range hands a seeking renderer the start of the file and it plays from the
  beginning again with nothing reported.
- **`bytes=-0` is unsatisfiable**, not "the whole thing". A suffix length of zero names no bytes.

### `HEAD`, and the length question this task measures rather than assumes

Spec §6 requires `HEAD`. Sonos issues one — or a `bytes=0-1` probe — before playing, to learn the
length and the type, and a `HEAD` that answered different headers from the `GET` would make the
renderer compute against one length and read another.

To answer `Content-Length` on a `HEAD`, the proxy has to know the resource's total length, which
means asking Navidrome. **Whether `/rest/stream` answers a `HEAD` is not something this plan
assumes** — Step 7 measures it against the pinned container and Step 8 pins the answer as an
assertion. Two implementations are ready:

- `HEAD` upstream, reading `Content-Length`;
- a `Range: bytes=0-0` probe, reading the total out of `Content-Range: bytes 0-0/N`.

The probe works whatever the answer, costs one byte, and relies only on behaviour spec §4 already
verified (*"`format=raw` honours RFC 7233 Range ... and always sends `Content-Length`"*). So the
probe is the implementation, and the `HEAD` result is recorded as a live assertion either way —
because "we chose the robust option" and "we never checked" look identical in a green build.

### Navidrome's 429 arrives here too, on a path Plan 3's policy does not cover

Spec §4: *"**Handle HTTP 429** — Navidrome 0.62.0 added `Transcoding.MaxConcurrent`. Unhandled,
this looks like random playback failure."* Plan 3 handles it with `StreamRetryPolicy` inside a
Media3 `LoadErrorHandlingPolicy` — which is the **local** player's data source, and the proxy is a
completely separate HTTP path to the same server. A 429 here would surface as a truncated track or
a 502, and would look like a flaky speaker.

`OkHttpProxyUpstream` therefore retries a 429 itself, honouring `Retry-After` in seconds and
otherwise backing off, with a hard ceiling. The arithmetic is **not** shared with Plan 3's
`StreamRetryPolicy`: that class lives in `:core:media`, and `:core:cast` must not depend on
`:core:media` — the dependency runs the other way, which is what keeps `:core:cast` free of Media3
and therefore inside Tier 1. Two small pieces of backoff arithmetic in two modules is the price of
that boundary, and it is worth paying; if a third appears, promote one to `:core:model`.

If the retries are exhausted, the renderer gets **503 with `Retry-After`** rather than 502: 503 says
"try again", which is a thing a renderer can act on, and 502 says "this is broken", which is not.

### The proxy relays; it does not cache

`CacheDataSource` and `SimpleCache` are `:core:media`'s and are keyed and locked for a **local**
`ExoPlayer`. Serving cached bytes to a renderer would mean a second reader on a cache an active
player may be writing, which is a locking question this plan does not need to answer to make
casting work. The proxy opens a ranged HTTPS request to Navidrome and relays. Deferred, in the
scope section, on purpose.

- [ ] **Step 1: Write the failing range tests**

`core/cast/src/test/kotlin/app/muplay/cast/proxy/RangeHeaderTest.kt`:

```kotlin
package app.muplay.cast.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * RFC 7233 parsing and resolution, as a pure function, so the eleven cases in this task's table
 * are gated in Tier 1 without a socket.
 */
class RangeHeaderTest {

  @Test
  fun `an absent header is absent and not a malformed one`() {
    // Two different facts with two different answers: absent means "send the whole thing as 200",
    // and so does ignored -- but they arrive by different routes and a reader needs to see both.
    assertThat(RangeHeader.parse(null)).isEqualTo(RangeRequest.Absent)
    assertThat(RangeHeader.parse("")).isEqualTo(RangeRequest.Absent)
  }

  @Test
  fun `a bounded range keeps both of its numbers`() {
    // Two observations of each end, so neither can be a constant.
    assertThat(RangeHeader.parse("bytes=100-199")).isEqualTo(RangeRequest.Bounded(100, 199))
    assertThat(RangeHeader.parse("bytes=0-0")).isEqualTo(RangeRequest.Bounded(0, 0))
    assertThat(RangeHeader.parse("bytes=512-1023")).isEqualTo(RangeRequest.Bounded(512, 1023))
  }

  @Test
  fun `an open-ended range has no last byte`() {
    assertThat(RangeHeader.parse("bytes=0-")).isEqualTo(RangeRequest.Bounded(0, null))
    assertThat(RangeHeader.parse("bytes=999-")).isEqualTo(RangeRequest.Bounded(999, null))
  }

  @Test
  fun `a suffix range names how many bytes from the end`() {
    assertThat(RangeHeader.parse("bytes=-500")).isEqualTo(RangeRequest.Suffix(500))
    assertThat(RangeHeader.parse("bytes=-1")).isEqualTo(RangeRequest.Suffix(1))
    assertThat(RangeHeader.parse("bytes=-0")).isEqualTo(RangeRequest.Suffix(0))
  }

  @Test
  fun `whitespace and case in the unit are tolerated`() {
    assertThat(RangeHeader.parse("bytes = 100 - 199")).isEqualTo(RangeRequest.Bounded(100, 199))
    assertThat(RangeHeader.parse("BYTES=100-199")).isEqualTo(RangeRequest.Bounded(100, 199))
  }

  @Test
  fun `everything unparseable is Ignored, which is not the same as Unsatisfiable`() {
    // RFC 7233: a server MAY ignore a Range header it does not understand and answer 200. A
    // server that answered 416 here would refuse requests it should have served.
    listOf("bytes=abc", "bytes=", "items=0-10", "bytes=5-2", "bytes=-", "bytes=0-0,10-20", "0-10")
      .forEach { header ->
        assertThat(RangeHeader.parse(header))
          .describedAs("parse of %s", header)
          .isEqualTo(RangeRequest.Ignored)
      }
  }

  @Test
  fun `an absent or ignored request resolves to the whole entity`() {
    assertThat(RangeHeader.resolve(RangeRequest.Absent, 1000)).isEqualTo(RangeResolution.Whole)
    assertThat(RangeHeader.resolve(RangeRequest.Ignored, 1000)).isEqualTo(RangeResolution.Whole)
  }

  @Test
  fun `a bounded range resolves to exactly those bytes`() {
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(100, 199), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(100, 199)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(999, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
  }

  @Test
  fun `a last byte past the end is clamped rather than refused`() {
    // RFC 7233: the last-byte-pos is clamped to the length. A renderer asking for more than exists
    // is asking for "the rest", and 416 there would stall playback near the end of every track.
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, 99_999), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
  }

  @Test
  fun `a suffix range resolves from the end, and is clamped to the whole entity`() {
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(500), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(500, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(1), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(5000), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(0, 999)))
  }

  @Test
  fun `a first byte at or past the end is unsatisfiable`() {
    // The boundary from both sides. `>= totalLength` against `> totalLength` is the classic
    // off-by-one, and it is the difference between 416 and a 206 that names no bytes.
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(999, null), 1000))
      .isEqualTo(RangeResolution.Partial(ByteRange(999, 999)))
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(1000, null), 1000))
      .isEqualTo(RangeResolution.Unsatisfiable)
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(5000, 6000), 1000))
      .isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a suffix of zero bytes is unsatisfiable, not the whole entity`() {
    // `bytes=-0` names no bytes at all. Reading it as "no suffix, so everything" would hand a
    // renderer the start of the file when it asked for nothing.
    assertThat(RangeHeader.resolve(RangeRequest.Suffix(0), 1000)).isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a range against an empty entity is unsatisfiable`() {
    assertThat(RangeHeader.resolve(RangeRequest.Bounded(0, null), 0)).isEqualTo(RangeResolution.Unsatisfiable)
  }

  @Test
  fun `a byte range knows how long it is`() {
    // Off-by-one in `length` is off-by-one in `Content-Length`, which is a renderer waiting for a
    // byte that never arrives.
    assertThat(ByteRange(0, 999).length).isEqualTo(1000L)
    assertThat(ByteRange(100, 199).length).isEqualTo(100L)
    assertThat(ByteRange(999, 999).length).isEqualTo(1L)
  }
}
```

- [ ] **Step 2: Implement the range logic**

`core/cast/src/main/kotlin/app/muplay/cast/proxy/RangeHeader.kt`:

```kotlin
package app.muplay.cast.proxy

/** An inclusive byte range, both ends resolved against a known length. */
data class ByteRange(val firstByte: Long, val lastByte: Long) {
  /** Inclusive on both ends, so this is `last - first + 1`. Off by one here is off by one in `Content-Length`. */
  val length: Long get() = lastByte - firstByte + 1
}

/** What a `Range` header asked for, before the entity's length is known. */
sealed interface RangeRequest {
  /** No `Range` header at all. */
  data object Absent : RangeRequest

  /**
   * A `Range` header this server will not act on -- malformed, a unit other than `bytes`, or a
   * multi-range request.
   *
   * **Not the same as unsatisfiable.** RFC 7233 permits a server to ignore a range it does not
   * understand and answer 200 with the whole entity, and that is what this means. A 416 here would
   * refuse a request that should have been served whole.
   */
  data object Ignored : RangeRequest

  /** `bytes=first-last` or `bytes=first-`. */
  data class Bounded(val firstByte: Long, val lastByte: Long?) : RangeRequest

  /** `bytes=-n`: the last `n` bytes. */
  data class Suffix(val lastBytes: Long) : RangeRequest
}

/** What should actually be sent, once the entity's length is known. */
sealed interface RangeResolution {
  /** 200, the whole entity. */
  data object Whole : RangeResolution

  /** 206, with a `Content-Range`. */
  data class Partial(val range: ByteRange) : RangeResolution

  /** 416, with `Content-Range: bytes * /total`. */
  data object Unsatisfiable : RangeResolution
}

/**
 * RFC 7233 `Range` parsing and resolution.
 *
 * Split from the server so that the eleven cases in this task's table are gated in Tier 1 without
 * a socket -- the same reason `StreamRetryPolicy` is a pure object in Plan 3.
 *
 * Only **single** ranges are served. A multi-range request would need a `multipart/byteranges`
 * body; no renderer sends one, and answering 200 with the whole entity is both legal and what a
 * renderer can use.
 */
object RangeHeader {

  private val BOUNDED = Regex("""^\s*(\d+)\s*-\s*(\d*)\s*$""")
  private val SUFFIX = Regex("""^\s*-\s*(\d+)\s*$""")

  fun parse(value: String?): RangeRequest {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return RangeRequest.Absent
    if (!text.substringBefore('=').trim().equals("bytes", ignoreCase = true)) return RangeRequest.Ignored
    val spec = text.substringAfter('=', missingDelimiterValue = "")
    // Multi-range: legal, and never sent by a renderer. Ignoring it answers 200, which is legal too.
    if (spec.contains(',')) return RangeRequest.Ignored

    SUFFIX.matchEntire(spec)?.let { return RangeRequest.Suffix(it.groupValues[1].toLong()) }
    val bounded = BOUNDED.matchEntire(spec) ?: return RangeRequest.Ignored
    val first = bounded.groupValues[1].toLongOrNull() ?: return RangeRequest.Ignored
    val last = bounded.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
    // `bytes=5-2` is not a range. Ignored rather than 416: it is malformed, not unsatisfiable.
    if (last != null && last < first) return RangeRequest.Ignored
    return RangeRequest.Bounded(first, last)
  }

  fun resolve(request: RangeRequest, totalLength: Long): RangeResolution = when (request) {
    RangeRequest.Absent, RangeRequest.Ignored -> RangeResolution.Whole

    is RangeRequest.Bounded -> when {
      totalLength <= 0 -> RangeResolution.Unsatisfiable
      // `>=`, not `>`: the last valid offset is `totalLength - 1`.
      request.firstByte >= totalLength -> RangeResolution.Unsatisfiable
      else -> RangeResolution.Partial(
        // Clamped, not refused: a renderer asking past the end is asking for "the rest", and 416
        // there would stall playback near the end of every track.
        ByteRange(request.firstByte, (request.lastByte ?: (totalLength - 1)).coerceAtMost(totalLength - 1)),
      )
    }

    is RangeRequest.Suffix -> when {
      totalLength <= 0 -> RangeResolution.Unsatisfiable
      // `bytes=-0` names no bytes at all. Reading it as "everything" hands a renderer the start of
      // the file when it asked for nothing.
      request.lastBytes <= 0 -> RangeResolution.Unsatisfiable
      else -> RangeResolution.Partial(
        ByteRange((totalLength - request.lastBytes).coerceAtLeast(0), totalLength - 1),
      )
    }
  }
}
```

Run: `./gradlew :core:cast:test --tests '*RangeHeaderTest*'` — PASS, 14/14.

- [ ] **Step 3: Write the failing registry test and implement it**

`ProxyRegistryTest` asserts:

```kotlin
  @Test
  fun `a published path ends in the served extension, because sonos sniffs the url`() {
    // Two observations, so the extension cannot be a constant. Task 3's FakeRenderer answers
    // 714 to an extensionless URL, so this is enforced end to end as well as here.
    assertThat(registry.publish(URL, ServedMedia.of("mp3", StreamFormat.Raw)).path).endsWith(".mp3")
    assertThat(registry.publish(URL, ServedMedia.of("flac", StreamFormat.Raw)).path).endsWith(".flac")
  }

  @Test
  fun `two publications of the same url get different tokens`() {
    // A token is a capability, not an identity. Reusing one across sessions would make a revoked
    // session's URL work again.
    assertThat(registry.publish(URL, MP3).token).isNotEqualTo(registry.publish(URL, MP3).token)
  }

  @Test
  fun `a token is long enough not to be guessed`() {
    assertThat(registry.publish(URL, MP3).token).hasSize(ProxyRegistry.TOKEN_BYTES * 2)
    assertThat(ProxyRegistry.TOKEN_BYTES).isGreaterThanOrEqualTo(16)
    assertThat(registry.publish(URL, MP3).token).matches("[0-9a-f]+")
  }

  @Test
  fun `resolving a published path returns the upstream url it was published for`() {
    // Two different upstreams, so `resolve` cannot return a constant.
    val first = registry.publish("https://nav/rest/stream?id=1", MP3)
    val second = registry.publish("https://nav/rest/stream?id=2", MP3)

    assertThat(registry.resolve(first.path)!!.upstreamUrl).isEqualTo("https://nav/rest/stream?id=1")
    assertThat(registry.resolve(second.path)!!.upstreamUrl).isEqualTo("https://nav/rest/stream?id=2")
  }

  @Test
  fun `an unknown path resolves to nothing`() {
    assertThat(registry.resolve("/media/deadbeef.mp3")).isNull()
    assertThat(registry.resolve("/media/")).isNull()
    assertThat(registry.resolve("/")).isNull()
  }

  @Test
  fun `a path outside the media prefix resolves to nothing, traversal included`() {
    // The rejection paths. A registry that stripped the prefix without checking it would resolve
    // `/../../etc/passwd` to whatever `substringAfterLast('/')` produced.
    val published = registry.publish(URL, MP3)

    assertThat(registry.resolve("/etc/passwd")).isNull()
    assertThat(registry.resolve("/media/../${published.token}.mp3")).isNull()
    assertThat(registry.resolve("/MEDIA/${published.token}.mp3")).isNull()
    assertThat(registry.resolve("${published.path}/extra")).isNull()
  }

  @Test
  fun `a revoked token resolves to nothing, and its neighbours still resolve`() {
    val kept = registry.publish("https://nav/rest/stream?id=1", MP3)
    val dropped = registry.publish("https://nav/rest/stream?id=2", MP3)

    registry.revoke(dropped.token)

    assertThat(registry.resolve(dropped.path)).isNull()
    assertThat(registry.resolve(kept.path)).isNotNull
  }

  @Test
  fun `revokeAll empties the registry`() {
    val a = registry.publish("https://nav/1", MP3)
    val b = registry.publish("https://nav/2", MP3)

    registry.revokeAll()

    assertThat(listOf(a, b).map { registry.resolve(it.path) }).containsExactly(null, null)
  }
```

`core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyRegistry.kt`:

```kotlin
package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** One item the proxy is currently willing to serve. */
data class PublishedMedia(
  val token: String,
  val path: String,
  val upstreamUrl: String,
  val served: ServedMedia,
)

/**
 * What the proxy will serve, and under what path.
 *
 * The path is `/media/<token>.<extension>`, where the token is 128 random bits and **not** the
 * track id. Two reasons, both real:
 *
 * - The proxy binds on the LAN. A path containing a track id makes it an **open relay for the
 *   whole library** to anything on that network, and Navidrome ids are guessable. A token
 *   published only for the current session, and revoked with it, means the proxy serves exactly
 *   what the user chose to cast.
 * - A stable id in a path lets anything watching the LAN correlate what is being listened to
 *   across sessions.
 *
 * The extension is **not** decoration: spec section 6 records that Sonos infers MIME from the URL,
 * and Task 3's renderer answers `714 Illegal MIME-type` to a path without one.
 */
class ProxyRegistry(private val random: SecureRandom = SecureRandom()) {

  private val published = ConcurrentHashMap<String, PublishedMedia>()

  fun publish(upstreamUrl: String, served: ServedMedia): PublishedMedia {
    val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
      .joinToString("") { "%02x".format(it) }
    return PublishedMedia(
      token = token,
      path = "$PATH_PREFIX$token.${served.fileExtension}",
      upstreamUrl = upstreamUrl,
      served = served,
    ).also { published[token] = it }
  }

  /**
   * The item a request path names, or `null`.
   *
   * The path is matched **whole** rather than by extracting a token from it: a
   * `substringAfterLast('/')` would happily pull a token out of `/etc/../media/<token>.mp3` and
   * out of anything else that ended the same way. Comparing against the exact published path makes
   * traversal, case games and trailing segments all resolve to nothing without a separate check
   * for each.
   */
  fun resolve(path: String): PublishedMedia? =
    published.values.firstOrNull { it.path == path }

  fun revoke(token: String) { published.remove(token) }

  fun revokeAll() = published.clear()

  companion object {
    /** 128 bits. Not an identity; a capability. */
    const val TOKEN_BYTES: Int = 16
    const val PATH_PREFIX: String = "/media/"
  }
}
```

- [ ] **Step 4: Write the failing proxy-server test**

`core/cast/src/test/kotlin/app/muplay/cast/proxy/MediaProxyServerTest.kt`, against a
`ProxyUpstream` that serves a **real** 1000-byte body and honours ranges for real (a fake that
returned the whole body for every range would make every 206 assertion vacuous), plus the real
`CastHttpClient` as the requesting renderer:

```kotlin
  private val content = ByteArray(1000) { (it % 251).toByte() }   // not constant: a slice is checkable

  private class SliceUpstream(private val content: ByteArray) : ProxyUpstream {
    var opened: MutableList<ByteRange> = mutableListOf()
    override fun totalLength(url: String): Long? = content.size.toLong()
    override fun open(url: String, range: ByteRange): InputStream {
      opened += range
      // Honours the range for real. An upstream that ignored it would make every 206 body
      // assertion below pass against a proxy that also ignored it.
      return content.copyOfRange(range.firstByte.toInt(), range.lastByte.toInt() + 1).inputStream()
    }
  }
```

with the eleven table rows written as a parameterised list plus these:

```kotlin
  @Test
  fun `the eleven range cases produce exactly the documented status, content-range and bytes`() {
    // The whole table in one place, asserted as an exact list of triples. Eleven separate tests
    // would let a reviewer miss that a row is absent; this cannot.
    val cases = listOf(
      null to Triple(200, null, content),
      "bytes=0-" to Triple(206, "bytes 0-999/1000", content),
      "bytes=100-199" to Triple(206, "bytes 100-199/1000", content.copyOfRange(100, 200)),
      "bytes=999-" to Triple(206, "bytes 999-999/1000", content.copyOfRange(999, 1000)),
      "bytes=-1" to Triple(206, "bytes 999-999/1000", content.copyOfRange(999, 1000)),
      "bytes=-500" to Triple(206, "bytes 500-999/1000", content.copyOfRange(500, 1000)),
      "bytes=0-99999" to Triple(206, "bytes 0-999/1000", content),
      "bytes=1000-" to Triple(416, "bytes */1000", ByteArray(0)),
      "bytes=-0" to Triple(416, "bytes */1000", ByteArray(0)),
      "bytes=abc" to Triple(200, null, content),
      "bytes=0-0,10-20" to Triple(200, null, content),
    )

    cases.forEach { (header, expected) ->
      val (status, contentRange, body) = expected
      val response = get(published.path, header)

      assertThat(response.code).describedAs("status for Range: %s", header).isEqualTo(status)
      assertThat(response.head.headers["Content-Range"])
        .describedAs("Content-Range for Range: %s", header).isEqualTo(contentRange)
      assertThat(response.body).describedAs("body for Range: %s", header).isEqualTo(body)
      assertThat(response.head.headers.contentLength())
        .describedAs("Content-Length for Range: %s", header).isEqualTo(body.size.toLong())
    }
  }

  @Test
  fun `every response advertises byte ranges, because protocolInfo promised them`() {
    // `DLNA.ORG_OP=01` in `ServedMedia.protocolInfo` tells the renderer it may seek by byte. This
    // is the other half of that promise, and the two are asserted against each other here so the
    // pair cannot drift.
    assertThat(get(published.path, null).head.headers["Accept-Ranges"]).isEqualTo("bytes")
    assertThat(published.served.protocolInfo).contains("DLNA.ORG_OP=01")
  }

  @Test
  fun `the content type is the served mime type, and it agrees with the url extension`() {
    // The three-way invariant from Task 4, closed here: URL extension, protocolInfo and
    // Content-Type all name one format. Asserted for two different formats.
    val mp3 = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))
    val flac = registry.publish(UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw))

    assertThat(get(mp3.path, null).head.headers["Content-Type"]).isEqualTo("audio/mpeg")
    assertThat(mp3.path).endsWith(".mp3")
    assertThat(mp3.served.protocolInfo).contains(":audio/mpeg:")

    assertThat(get(flac.path, null).head.headers["Content-Type"]).isEqualTo("audio/flac")
    assertThat(flac.path).endsWith(".flac")
    assertThat(flac.served.protocolInfo).contains(":audio/flac:")
  }

  @Test
  fun `a HEAD returns the same headers as the GET and no body`() {
    val get = get(published.path, null)
    val head = request("HEAD", published.path, null)

    assertThat(head.code).isEqualTo(200)
    assertThat(head.body).isEmpty()
    // The exact header list, so a HEAD that omitted Content-Length -- which is the whole reason a
    // renderer sends one -- fails here.
    assertThat(head.head.headers.names).isEqualTo(get.head.headers.names)
    assertThat(head.head.headers.contentLength()).isEqualTo(1000L)
    assertThat(head.head.headers["Content-Type"]).isEqualTo(get.head.headers["Content-Type"])
    assertThat(head.head.headers["Accept-Ranges"]).isEqualTo("bytes")
  }

  @Test
  fun `a ranged HEAD reports the range's length without sending it`() {
    val head = request("HEAD", published.path, "bytes=100-199")

    assertThat(head.code).isEqualTo(206)
    assertThat(head.head.headers["Content-Range"]).isEqualTo("bytes 100-199/1000")
    assertThat(head.head.headers.contentLength()).isEqualTo(100L)
    assertThat(head.body).isEmpty()
  }

  @Test
  fun `a HEAD does not open the upstream body`() {
    // A HEAD that streamed the file to discard it would cost a whole track of bandwidth per probe,
    // and Sonos probes before every track.
    upstream.opened.clear()

    request("HEAD", published.path, null)

    assertThat(upstream.opened).isEmpty()
  }

  @Test
  fun `an unknown token is 404 and a revoked one stops working`() {
    // The rejection paths, and the second one in both directions.
    assertThat(get("/media/00000000000000000000000000000000.mp3", null).code).isEqualTo(404)

    assertThat(get(published.path, null).code).isEqualTo(200)
    registry.revoke(published.token)
    assertThat(get(published.path, null).code).isEqualTo(404)
  }

  @Test
  fun `a path traversal attempt is 404 and reaches no file`() {
    listOf("/../../etc/passwd", "/media/../../etc/passwd", "/media/", "/").forEach { path ->
      assertThat(get(path, null).code).describedAs("status for %s", path).isEqualTo(404)
    }
  }

  @Test
  fun `a method other than GET or HEAD is 405 and says what is allowed`() {
    val response = request("POST", published.path, null)

    assertThat(response.code).isEqualTo(405)
    assertThat(response.head.headers["Allow"]).isEqualTo("GET, HEAD")
  }

  @Test
  fun `a malformed request line is answered 400 rather than dropped`() {
    // Written straight onto a socket, because CastHttpClient cannot produce a malformed request.
    assertThat(rawExchange("GARBAGE\r\n\r\n")).startsWith("HTTP/1.1 400 ")
  }

  @Test
  fun `a throttled upstream becomes 503 with a retry-after, not 502`() {
    // Spec section 4: Navidrome 0.62.0 added `Transcoding.MaxConcurrent`, and an unhandled 429
    // "looks like random playback failure". 503 tells the renderer to try again; 502 tells it the
    // resource is broken, and a renderer that believes that stops.
    val throttled = object : ProxyUpstream {
      override fun totalLength(url: String) = throw UpstreamThrottledException(retryAfterSeconds = 7)
      override fun open(url: String, range: ByteRange) = throw UpstreamThrottledException(null)
    }

    withServer(throttled) {
      val response = get(published.path, null)
      assertThat(response.code).isEqualTo(503)
      assertThat(response.head.headers["Retry-After"]).isEqualTo("7")
    }
  }

  @Test
  fun `the retry policy backs off, honours retry-after, and gives up`() {
    // Four observations of a computed number, so `return 500L` passes one and fails three -- the
    // same shape as Plan 3's `StreamRetryPolicyTest`, in the module that owns this HTTP path.
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 1)).isEqualTo(500L)
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 2)).isEqualTo(1_000L)
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = 3)).isEqualTo(2_000L)
    // The server's own number wins where the two disagree, which is the only direction that proves
    // the header is read at all.
    assertThat(ProxyRetry.retryDelayMs(429, "3", attempt = 3)).isEqualTo(3_000L)
    assertThat(ProxyRetry.retryDelayMs(429, "600", attempt = 1)).isEqualTo(ProxyRetry.MAX_BACKOFF_MS)
    // Not this policy's business, and not retried forever.
    assertThat(ProxyRetry.retryDelayMs(404, null, attempt = 1)).isNull()
    assertThat(ProxyRetry.retryDelayMs(429, null, attempt = ProxyRetry.MAX_ATTEMPTS + 1)).isNull()
  }

  @Test
  fun `an upstream that cannot supply a length is 502 rather than a truncated 200`() {
    // A live transcode has no Content-Length (spec section 4). Serving it as a 200 with no length
    // would give the renderer no way to know when the track ends, and Sonos would cut it short.
    val lengthless = object : ProxyUpstream {
      override fun totalLength(url: String) = null
      override fun open(url: String, range: ByteRange) = ByteArray(0).inputStream()
    }

    withServer(lengthless) { assertThat(get(published.path, null).code).isEqualTo(502) }
  }

  @Test
  fun `two renderers reading two ranges at once both get the right bytes`() {
    // Renderers do this: a HEAD and a GET overlap, and a seek opens a second read before the first
    // is closed. A server holding one shared upstream stream returns interleaved garbage.
    val first = async { get(published.path, "bytes=0-499") }
    val second = async { get(published.path, "bytes=500-999") }

    assertThat(first.await().body).isEqualTo(content.copyOfRange(0, 500))
    assertThat(second.await().body).isEqualTo(content.copyOfRange(500, 1000))
  }

  @Test
  fun `every request is logged with its token, range and status`() {
    // The log is what Task 7's routing proof reads. If it recorded nothing, the router would
    // conclude the renderer never fetched and fall back on every cast.
    get(published.path, "bytes=0-99")
    get("/media/unknown.mp3", null)

    assertThat(server.requestLog.map { Triple(it.method, it.token, it.status) }).containsExactly(
      Triple("GET", published.token, 206),
      Triple("GET", null, 404),
    )
    assertThat(server.requestLog.first().rangeHeader).isEqualTo("bytes=0-99")
  }

  @Test
  fun `the advertised url names the host it was given and the published path`() {
    // Two hosts, because the address the renderer must use is not always the same one -- see
    // LocalAddress.towards and Task 7's VPN case.
    assertThat(server.urlFor(published, "192.168.1.20"))
      .isEqualTo("http://192.168.1.20:${server.port}${published.path}")
    assertThat(server.urlFor(published, "10.8.0.3"))
      .isEqualTo("http://10.8.0.3:${server.port}${published.path}")
  }
```

- [ ] **Step 5: Implement `ProxyUpstream` and `MediaProxyServer`**

`core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyUpstream.kt`:

```kotlin
package app.muplay.cast.proxy

import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Navidrome refused because too many transcodes are already running.
 *
 * Spec section 4: *"Handle HTTP 429 -- Navidrome 0.62.0 added `Transcoding.MaxConcurrent`.
 * Unhandled, this looks like random playback failure."* Its own type, so [MediaProxyServer] can
 * answer **503 with `Retry-After`** rather than 502: 503 tells a renderer to try again, which it
 * can act on; 502 tells it the resource is broken, and a renderer that believes that stops.
 */
class UpstreamThrottledException(val retryAfterSeconds: Long?) :
  java.io.IOException("upstream is throttling this client" + (retryAfterSeconds?.let { ", retry after ${'$'}it s" } ?: ""))

/**
 * When to retry an upstream refusal, and how long to wait.
 *
 * A separate object from the fetch for the same reason Plan 3 split `StreamRetryPolicy` out of its
 * Media3 adapter: it is arithmetic, and arithmetic belongs where a fast tier can hold it to a floor.
 *
 * **Deliberately not shared with Plan 3's `StreamRetryPolicy`**, which lives in `:core:media`.
 * `:core:cast` must not depend on `:core:media` -- the dependency runs the other way, and that is
 * what keeps this module free of Media3 and therefore inside Tier 1. Two small pieces of backoff
 * arithmetic in two modules is the price of that boundary. If a third appears, promote one to
 * `:core:model`.
 */
object ProxyRetry {

  const val TOO_MANY_REQUESTS: Int = 429

  /** Four attempts is roughly eight seconds of patience, which is about a renderer's own timeout. */
  const val MAX_ATTEMPTS: Int = 4
  const val BASE_BACKOFF_MS: Long = 500L
  const val MAX_BACKOFF_MS: Long = 8_000L

  /**
   * How long to wait before attempt [attempt] + 1, or `null` to stop.
   *
   * `null` for any status that is not 429: a 404 is not transient and retrying it wastes a
   * renderer's patience on a track that will never arrive.
   */
  fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, attempt: Int): Long? {
    if (responseCode != TOO_MANY_REQUESTS) return null
    if (attempt > MAX_ATTEMPTS) return null
    // The server's own number wins where the two disagree -- it knows how loaded it is and this
    // client does not. The HTTP-date form is not parsed: parsing it needs a clock, for a header
    // Navidrome has never been observed to send, and falling through to the backoff is a correct
    // answer rather than an oversight.
    retryAfterHeader?.toLongOrNull()?.let { return (it * 1_000L).coerceAtMost(MAX_BACKOFF_MS) }
    return (BASE_BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
  }
}

/**
 * Where the proxy gets the bytes.
 *
 * An interface with exactly two operations, so the server's status and header logic is testable
 * without a network -- and so that the one implementation that *does* use the network is small
 * enough to read.
 */
interface ProxyUpstream {
  /** The resource's total length, or `null` when the origin will not say. */
  fun totalLength(url: String): Long?

  /** Exactly the bytes in [range]. The caller closes the stream. */
  fun open(url: String, range: ByteRange): InputStream
}

/**
 * Navidrome, over **OkHttp**, and this is the one place in `:core:cast` where OkHttp belongs.
 *
 * The split is deliberate and the reasoning is on `LocalNetworkOnly`: traffic to a **renderer** is
 * plain HTTP on the LAN and goes through this module's own socket client, which enforces the
 * private-address rule in code; traffic to **Navidrome** is HTTPS to a real origin with redirects,
 * TLS and a 429 policy, and it goes through the library the rest of the project already uses --
 * still subject to the platform's network security policy, which is exactly where it should be.
 *
 * The length is learned with a **one-byte range probe** rather than a `HEAD`. Spec section 4
 * verified against a real container that `format=raw` honours RFC 7233 and always sends
 * `Content-Length`, so a `bytes=0-0` request is guaranteed to come back `206` with
 * `Content-Range: bytes 0-0/N`. Whether `/rest/stream` answers a `HEAD` is a separate question that
 * `LiveNavidromeProxyTest` measures and pins -- but the probe does not depend on the answer.
 */
class OkHttpProxyUpstream(private val client: OkHttpClient) : ProxyUpstream {

  override fun totalLength(url: String): Long? =
    withRetries(url, "bytes=0-0") { response ->
      // `Content-Range: bytes 0-0/12345` -> 12345. A live transcode answers 200 with no
      // Content-Range at all, and `null` is the correct answer there: it is not seekable, it is
      // not length-declared, and the server must not pretend otherwise.
      response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
    }

  override fun open(url: String, range: ByteRange): InputStream =
    checkNotNull(
      withRetries(url, "bytes=${range.firstByte}-${range.lastByte}") { response ->
        // The body is NOT closed here: the caller streams it and closes it. That is why this call
        // returns the stream rather than the response, and why the retry loop has to have finished
        // deciding by the time it does.
        checkNotNull(response.body) { "no body from $url" }.byteStream()
      },
    )

  /**
   * One ranged GET, retried while Navidrome says 429.
   *
   * The loop is here rather than in an OkHttp `Interceptor` because the decision to give up has to
   * become an [UpstreamThrottledException] carrying the server's own `Retry-After`, which the
   * proxy passes on to the renderer -- an interceptor would have to throw the same thing anyway,
   * from further away from the header it read.
   */
  private fun <T> withRetries(url: String, range: String, read: (okhttp3.Response) -> T): T {
    var attempt = 1
    while (true) {
      val request = Request.Builder().url(url).header("Range", range).build()
      val response = client.newCall(request).execute()
      if (response.code != ProxyRetry.TOO_MANY_REQUESTS) return response.use(read)
      val retryAfter = response.header("Retry-After")
      val delayMs = ProxyRetry.retryDelayMs(response.code, retryAfter, attempt)
      response.close()
      if (delayMs == null) throw UpstreamThrottledException(retryAfter?.toLongOrNull())
      Thread.sleep(delayMs)
      attempt += 1
    }
  }
}
```

`core/cast/src/main/kotlin/app/muplay/cast/proxy/MediaProxyServer.kt`:

```kotlin
package app.muplay.cast.proxy

import app.muplay.cast.http.HttpHeaders
import app.muplay.cast.http.HttpRequestHead
import app.muplay.cast.http.HttpWire
import app.muplay.cast.http.MalformedHttpException
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One request the proxy answered. Task 7's routing proof reads this. */
data class ProxyRequest(val method: String, val token: String?, val rangeHeader: String?, val status: Int)

/**
 * **The phone's HTTP server**, so a renderer can fetch media it could not otherwise reach.
 *
 * Spec section 6: a renderer on the LAN cannot authenticate to Navidrome the way the phone can, and
 * *"a servlet container to serve range requests to one speaker is a large dependency used for a
 * fraction of its surface"*.
 *
 * A listening socket is **not** governed by `NetworkSecurityPolicy` -- that policy is consulted by
 * the platform's outbound HTTP stacks and has no mechanism to affect a `ServerSocket` -- so this
 * needs no manifest change and no cleartext permission. See [app.muplay.cast.net.LocalNetworkOnly]
 * for the other half of that story, which is the half that did need a decision.
 *
 * Thread per connection, deliberately. A renderer opens two or three at a time (a `HEAD`, a `GET`,
 * and a second `GET` after a seek), each lives for the length of a track, and each holds an
 * upstream stream that must not be shared -- which a shared reader would do, returning interleaved
 * bytes to two readers with nothing reported anywhere.
 */
class MediaProxyServer(
  private val upstream: ProxyUpstream,
  private val registry: ProxyRegistry,
  bindAddress: InetAddress = InetAddress.getByName(BIND_ALL),
  requestedPort: Int = 0,
) : Closeable {

  private val server = ServerSocket(requestedPort, BACKLOG, bindAddress)
  private val log = CopyOnWriteArrayList<ProxyRequest>()
  private val fetchLatches = ConcurrentHashMap<String, CountDownLatch>()

  val port: Int get() = server.localPort
  val requestLog: List<ProxyRequest> get() = log.toList()

  fun start(): Int {
    thread(isDaemon = true, name = "media-proxy") {
      while (!server.isClosed) {
        val connection = runCatching { server.accept() }.getOrNull() ?: continue
        thread(isDaemon = true, name = "media-proxy-conn") {
          runCatching { serve(connection) }
          runCatching { connection.close() }
        }
      }
    }
    return port
  }

  /** The URL to hand a renderer. [host] is the address **the renderer** can reach this phone at. */
  fun urlFor(media: PublishedMedia, host: String): String = "http://$host:$port${media.path}"

  /**
   * Blocks until a renderer has fetched [token], or the timeout expires.
   *
   * This is Task 7's **proof** that the chosen route works. Nothing else in this system can answer
   * "can that speaker reach this phone" -- a subnet comparison guesses, and a guess that is wrong
   * produces a cast that starts and plays nothing.
   */
  fun awaitRequest(token: String, timeoutMs: Long): Boolean =
    fetchLatches.computeIfAbsent(token) { CountDownLatch(1) }.await(timeoutMs, TimeUnit.MILLISECONDS)

  override fun close() {
    server.close()
  }

  private fun serve(connection: Socket) {
    val output = connection.getOutputStream()
    val head = try {
      HttpWire.readRequestHead(connection.getInputStream())
    } catch (malformed: MalformedHttpException) {
      // Answered, not dropped. A renderer that gets nothing back retries forever; one that gets a
      // 400 stops and its logs say why.
      log += ProxyRequest("?", null, null, 400)
      output.write(HttpWire.renderResponseHead(400, "Bad Request", closeHeaders(0)))
      return
    }

    val status = respond(head, output)
    val media = registry.resolve(head.target)
    log += ProxyRequest(head.method, media?.token, head.headers["Range"], status)
    media?.let { fetchLatches.computeIfAbsent(it.token) { CountDownLatch(1) }.countDown() }
    output.flush()
  }

  private fun respond(head: HttpRequestHead, output: OutputStream): Int {
    if (head.method != "GET" && head.method != "HEAD") {
      output.write(
        HttpWire.renderResponseHead(
          405,
          "Method Not Allowed",
          HttpHeaders.of("Allow" to "GET, HEAD", "Content-Length" to "0", "Connection" to "close"),
        ),
      )
      return 405
    }

    // The whole path is matched against a published one -- see ProxyRegistry.resolve. Traversal,
    // case games and trailing segments all land here, with no separate check for each.
    val media = registry.resolve(head.target) ?: run {
      output.write(HttpWire.renderResponseHead(404, "Not Found", closeHeaders(0)))
      return 404
    }

    val totalLength = try {
      upstream.totalLength(media.upstreamUrl)
    } catch (throttled: UpstreamThrottledException) {
      // 503, not 502. "Try again" is something a renderer can act on; "this is broken" is not, and
      // a renderer that believes the second one stops. Spec section 4 names the unhandled version
      // of this as looking like "random playback failure".
      output.write(
        HttpWire.renderResponseHead(
          503,
          "Service Unavailable",
          HttpHeaders(
            buildList {
              throttled.retryAfterSeconds?.let { add("Retry-After" to it.toString()) }
              add("Content-Length" to "0")
              add("Connection" to "close")
            },
          ),
        ),
      )
      return 503
    } ?: run {
      // A live transcode has no length (spec section 4). Serving it as a 200 with no Content-Length
      // gives the renderer no way to know when the track ends, and Sonos cuts it short. 502 is the
      // honest answer: this proxy cannot serve what the origin will not measure.
      output.write(HttpWire.renderResponseHead(502, "Bad Gateway", closeHeaders(0)))
      return 502
    }

    return when (val resolution = RangeHeader.resolve(RangeHeader.parse(head.headers["Range"]), totalLength)) {
      RangeResolution.Unsatisfiable -> {
        output.write(
          HttpWire.renderResponseHead(
            416,
            "Range Not Satisfiable",
            HttpHeaders.of(
              // The `* /total` form is the part a renderer actually reads: it says how long the
              // resource really is, so the next request can be a valid one.
              "Content-Range" to "bytes */$totalLength",
              "Accept-Ranges" to "bytes",
              "Content-Type" to media.served.mimeType,
              "Content-Length" to "0",
              "Connection" to "close",
            ),
          ),
        )
        416
      }

      RangeResolution.Whole -> {
        val range = ByteRange(0, totalLength - 1)
        output.write(
          HttpWire.renderResponseHead(200, "OK", bodyHeaders(media, range.length, contentRange = null)),
        )
        if (head.method == "GET") stream(media, range, output)
        200
      }

      is RangeResolution.Partial -> {
        val range = resolution.range
        output.write(
          HttpWire.renderResponseHead(
            206,
            "Partial Content",
            bodyHeaders(
              media,
              range.length,
              contentRange = "bytes ${range.firstByte}-${range.lastByte}/$totalLength",
            ),
          ),
        )
        if (head.method == "GET") stream(media, range, output)
        206
      }
    }
  }

  /**
   * Identical for `GET` and `HEAD`, which is the point of a `HEAD`: a renderer probes for the
   * length and type, then requests the body, and the two answers must agree.
   *
   * `Accept-Ranges: bytes` is a promise `ServedMedia.protocolInfo` already made to the renderer
   * with `DLNA.ORG_OP=01`. The two are asserted against each other in this task's test.
   */
  private fun bodyHeaders(media: PublishedMedia, length: Long, contentRange: String?) = HttpHeaders(
    buildList {
      add("Content-Type" to media.served.mimeType)
      add("Accept-Ranges" to "bytes")
      contentRange?.let { add("Content-Range" to it) }
      add("Content-Length" to length.toString())
      add("Connection" to "close")
    },
  )

  private fun closeHeaders(length: Long) =
    HttpHeaders.of("Content-Length" to length.toString(), "Connection" to "close")

  /**
   * Relays the bytes, one buffer at a time.
   *
   * Never `readBytes()`: a FLAC track is 30-40 MB and buffering one whole would be a third of a
   * modest heap, per concurrent renderer. The stream is opened per request rather than shared,
   * because two renderers -- or one renderer's overlapping probe and read -- sharing a reader get
   * interleaved bytes with nothing reported anywhere.
   */
  private fun stream(media: PublishedMedia, range: ByteRange, output: OutputStream) {
    // The head has already been written by the time this runs, so a throttle here can only close
    // the connection early -- which a renderer reads as a truncated track and retries. That is the
    // correct behaviour and the reason `totalLength` is probed first: the 429 is almost always
    // seen there, where a 503 can still be sent.
    runCatching {
      upstream.open(media.upstreamUrl, range).use { input ->
        val buffer = ByteArray(RELAY_BUFFER_BYTES)
        var remaining = range.length
        while (remaining > 0) {
          val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
          if (read <= 0) break
          output.write(buffer, 0, read)
          remaining -= read
        }
        output.flush()
      }
    }
    // A renderer that stops reading mid-track (a seek, a stop, a power cut) closes its socket and
    // this write throws. That is ordinary, not an error, and the connection thread ends either way.
  }

  companion object {
    private const val BIND_ALL = "0.0.0.0"
    private const val BACKLOG = 16

    /** 64 KiB: large enough that the syscall count is irrelevant, small enough to be free. */
    private const val RELAY_BUFFER_BYTES = 64 * 1024
  }
}
```

- [ ] **Step 6: Run the proxy tests**

Run: `./gradlew :core:cast:test --tests '*MediaProxyServerTest*' --tests '*ProxyRegistryTest*'`
Expected: PASS.

- [ ] **Step 7: Measure Navidrome's `HEAD` behaviour, then pin it**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
curl -sI "http://localhost:4533/rest/stream?id=<a real id>&format=raw&u=admin&t=...&s=...&v=1.16.1&c=MuPlay&f=json"
curl -sD - -o /dev/null -H 'Range: bytes=0-0' "http://localhost:4533/rest/stream?...&format=raw"
```

Record both answers in the task report. Whatever they are, `OkHttpProxyUpstream.totalLength` keeps
using the one-byte probe — the probe relies only on behaviour spec §4 already verified against a
real container. The point of measuring is that Step 8 **pins** the answer, so "we chose the robust
option" and "we never checked" stop looking identical.

- [ ] **Step 8: Write the live proxy test**

`core/cast/src/test/kotlin/app/muplay/cast/proxy/LiveNavidromeProxyTest.kt`, `@Tag("live")`, the
whole proxy in front of the real container:

```kotlin
  @Test
  fun `the proxy relays a real navidrome track byte for byte`() {
    // The strongest available assertion: not "a 200 came back" but "these are the same bytes".
    val direct = fetchDirect(streamUrl)
    assertThat(direct.size).isGreaterThan(1000)   // not vacuous against an empty 200

    val throughProxy = get(published.path, null)

    assertThat(throughProxy.code).isEqualTo(200)
    assertThat(throughProxy.body).isEqualTo(direct)
    assertThat(throughProxy.head.headers.contentLength()).isEqualTo(direct.size.toLong())
  }

  @Test
  fun `a ranged request through the proxy returns the byte-exact tail of the real file`() {
    val direct = fetchDirect(streamUrl)
    val offset = direct.size / 2

    val tail = get(published.path, "bytes=$offset-")

    assertThat(tail.code).isEqualTo(206)
    assertThat(tail.head.headers["Content-Range"])
      .isEqualTo("bytes $offset-${direct.size - 1}/${direct.size}")
    // Byte-exact, not merely the right length: a proxy that answered 206 with the START of the
    // file would pass a length check and make every seek jump back to the beginning.
    assertThat(tail.body).isEqualTo(direct.copyOfRange(offset, direct.size))
  }

  @Test
  fun `a middle range through the proxy is byte-exact too`() {
    // A second, non-tail range, so the offset arithmetic is observed at two values.
    val direct = fetchDirect(streamUrl)

    val middle = get(published.path, "bytes=1000-1999")

    assertThat(middle.code).isEqualTo(206)
    assertThat(middle.body).isEqualTo(direct.copyOfRange(1000, 2000))
  }

  @Test
  fun `a range past the end of a real track is 416 with the real length`() {
    val direct = fetchDirect(streamUrl)

    val past = get(published.path, "bytes=${direct.size + 1000}-")

    assertThat(past.code).isEqualTo(416)
    assertThat(past.head.headers["Content-Range"]).isEqualTo("bytes */${direct.size}")
  }

  @Test
  fun `the length probe against a real navidrome returns the real length`() {
    // The mechanism `totalLength` depends on, against the server it depends on.
    assertThat(OkHttpProxyUpstream(OkHttpClient()).totalLength(streamUrl))
      .isEqualTo(fetchDirect(streamUrl).size.toLong())
  }

  /**
   * Step 7 measured this. Whatever the answer, it is recorded here as an assertion so that a
   * change in Navidrome's behaviour is a red build rather than a discovery during a cast.
   * **Write the assertion to match what Step 7 actually observed** -- do not guess it.
   */
  @Test
  fun `what a HEAD on rest slash stream really does, pinned`() {
    val head = rawHead(streamUrl)

    assertThat(head.code).describedAs("Navidrome's answer to HEAD /rest/stream").isEqualTo(/* measured */ 200)
    assertThat(head.header("Content-Length")).describedAs("...and whether it declares a length")
      .isEqualTo(/* measured */ null)
  }

  @Test
  fun `a live transcode has no length, so the proxy refuses it rather than truncating it`() {
    // Spec section 4: a live transcode returns `Accept-Ranges: none` with no `Content-Length`.
    // The proxy's 502 branch, against the real behaviour that produces it.
    val transcoded = registry.publish(client.streamUrl(song.id, StreamFormat.Mp3(32)), MP3)

    assertThat(get(transcoded.path, null).code).isEqualTo(502)
  }
```

`build.gradle.kts` — the `liveNavidromeTest` registration currently lives in a
`project(":core:network") { afterEvaluate { ... } }` block. Generalise it to a list so `:core:cast`
gets the same task, keeping `LIVE_NAVIDROME_TEST_TASK_NAME` as the single constant `ConventionTest`
rule 4 checks:

```kotlin
// `:core:cast` joins `:core:network` here. Both are `muplay.jvm.library` modules, so both have a
// plain `test` source set with a runtime classpath -- which is why `:core:cast` is a JVM module and
// `UpnpPlayer` is not in it. `outputs.upToDateWhen { false }` and `cacheIf { false }` are
// load-bearing: this task once reported UP-TO-DATE with no Navidrome running at all.
listOf(":core:network", ":core:cast").forEach { path ->
  project(path) {
    afterEvaluate {
      val testSourceSet = the<SourceSetContainer>()["test"]
      tasks.register<Test>(LIVE_NAVIDROME_TEST_TASK_NAME) {
        group = "verification"
        description = "Runs the \"live\"-tagged tests in $path against a real Navidrome container " +
          "on localhost:4533 -- see ci/navidrome.compose.yml."
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { includeTags("live") }
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
      }
    }
  }
}
```

`.github/workflows/pr.yml` — the `live-navidrome` job's test step becomes:

```yaml
      - name: Live Navidrome tests
        run: ./gradlew :core:network:liveNavidromeTest :core:cast:liveNavidromeTest
```

- [ ] **Step 9: Run the live suite, and prove it can fail**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait
./ci/configure-libraries.sh
./gradlew :core:cast:liveNavidromeTest
```

Expected: PASS.

**Then stop the container and run it again.** Expected: **RED.** A `liveNavidromeTest` that passes
with no Navidrome is the same silent gate this project has already found once, in the same task
shape, and the `outputs.upToDateWhen { false }` lines are what closed it. Confirm the red build
before trusting the green one.

- [ ] **Step 10: Prove each new assertion can fail, record probes, measure, commit**

1. In `RangeHeader.resolve`, change `request.firstByte >= totalLength` to `>`. Expect
   `a first byte at or past the end is unsatisfiable`, the table row for `bytes=1000-`, and the
   live `a range past the end of a real track is 416...` to fail.
2. In `RangeHeader.parse`, return `Ignored` for a suffix range. Expect the three `bytes=-n` table
   rows to fail.
3. In `RangeHeader.resolve`, treat `Suffix(0)` as `Whole`. Expect
   `a suffix of zero bytes is unsatisfiable, not the whole entity` and its table row to fail.
4. In `RangeHeader.parse`, return `Ignored` for the whole `Bounded` case. Expect six table rows to
   fail and the two 200 rows to pass — which is what makes "ignored" and "served" distinguishable.
5. In `MediaProxyServer.respond`, drop the `Content-Range` header from the 206. Expect every
   206 table row to fail and the live tail test to fail.
6. In `MediaProxyServer.respond`, serve the body on a `HEAD`. Expect
   `a HEAD returns the same headers as the GET and no body` to fail.
7. In `MediaProxyServer.bodyHeaders`, hardcode `Content-Type` to `audio/mpeg`. Expect
   `the content type is the served mime type, and it agrees with the url extension` to fail on its
   FLAC half.
8. In `ProxyRegistry.resolve`, match on `path.substringAfterLast('/')` instead of the whole path.
   Expect `a path outside the media prefix resolves to nothing, traversal included` to fail.
9. In `ProxyRegistry.publish`, use the upstream URL's hash as the token. Expect
   `two publications of the same url get different tokens` and
   `a token is long enough not to be guessed` to fail.
10. In `MediaProxyServer.stream`, ignore `range` and stream from byte 0. Expect every 206 body
    assertion and both live byte-exact tests to fail. **This is the defect a "the response was 206"
    test cannot see.**

11. In `ProxyRetry.retryDelayMs`, ignore the `Retry-After` header. Expect
    `the retry policy backs off, honours retry-after, and gives up` to fail on the one observation
    where the header and the backoff disagree.
12. In `MediaProxyServer.respond`, answer a throttled upstream 502 instead of 503. Expect
    `a throttled upstream becomes 503 with a retry-after, not 502` to fail.

Record 1, 3, 5, 8, 9, 10 and 11 in `ci/mutation-probes.sh` as `proxy/*` entries. Add
`"app.muplay.cast.proxy.RangeHeader"`, `"app.muplay.cast.proxy.ByteRange"`,
`"app.muplay.cast.proxy.ProxyRegistry"`, `"app.muplay.cast.proxy.ProxyRetry"`,
`"app.muplay.cast.proxy.MediaProxyServer"` and
`"app.muplay.cast.proxy.OkHttpProxyUpstream"` to the `:core:cast` BRANCH floor, measure, confirm
≥ 0.90.

```bash
./gradlew :core:cast:test jacocoTestReport jacocoJvmCoverageVerification
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
git add core/cast build.gradle.kts .github/workflows/pr.yml ci/mutation-probes.sh
git commit -m "feat(cast): a range-serving proxy on the phone, and a token that is not a track id"
```

---

## Task 7: `CastRouter` — the routing rule, proved rather than guessed

**Files:**
- Create: `core/cast/src/main/kotlin/app/muplay/cast/route/CastRoute.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/route/SubnetMatch.kt`
- Create: `core/cast/src/main/kotlin/app/muplay/cast/route/CastRouter.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/route/SubnetMatchTest.kt`
- Test: `core/cast/src/test/kotlin/app/muplay/cast/route/CastRouterTest.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md` (§6 — see Task 11, which
  makes every spec edit in one commit; this task only *records* what has to change)

**Interfaces:**
- Consumes: `MediaProxyServer`, `ProxyRegistry`, `PublishedMedia` (Task 6); `CastDevice` (Task 2);
  `LocalAddress`, `LocalNetworkOnly` (Task 1); `ServedMedia` (Task 4).
- Produces:
  - `sealed interface CastRoute` with `data class Proxied(val url: String, val media: PublishedMedia)`,
    `data class RendererDirect(val url: String)`,
    `data class Unroutable(val reason: UnroutableReason, val detail: String)`
  - `enum class UnroutableReason { NO_ROUTE_TO_RENDERER, PROXY_UNREACHABLE_AND_DIRECT_DISABLED }`
  - `object SubnetMatch` with `fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean`
  - `class CastRouter(proxy, registry, allowRendererDirect: Boolean, proofTimeoutMs: Long)` with
    `fun candidate(device: CastDevice, upstreamUrl: String, served: ServedMedia): CastRoute`,
    `fun confirm(route: CastRoute, upstreamUrl: String): CastRoute`,
    `fun revokeAll()`,
    `companion object { const val DEFAULT_PROOF_TIMEOUT_MS = 6_000L }`
- **Plan 4 interaction:** none.

### The spec's rule contradicts the spec's own table, and this task is where that is resolved

Spec §6 states the routing rule as:

> **Same subnet as the speaker → stream through the phone proxy. Otherwise → the speaker fetches
> Navidrome directly.**

and then gives three situations, **all three of which answer "proxy"** — including row 3,
*"Remote + VPN | phone: VPN into home | speaker: home LAN | Navidrome: home | **proxy over the
tunnel**"*.

**Row 3 is routinely not subnet-equal.** A routed VPN — WireGuard, Tailscale, OpenVPN in `tun`
mode, which is how essentially everyone builds this — puts the phone on its own subnet (`10.8.0.0/24`,
`100.64.0.0/10`) and *routes* the home LAN over it. Phone `10.8.0.3/24`, speaker `192.168.1.50/24`:
different subnets, tunnel routes both ways, proxy works perfectly. Applied literally, the rule sends
that case down the "otherwise" branch and has the speaker fetch Navidrome directly — the opposite
of what the spec's own table says the answer is.

**Subnet equality is neither necessary nor sufficient for the thing the rule is actually asking.**
The question is *"can the renderer open a TCP connection back to this phone?"*. Same-subnet is a
cheap sufficient-ish condition (client isolation on a guest network defeats it); a routed tunnel is
a case where it is false and the answer is still yes.

So this task implements the rule the table describes rather than the sentence:

> **The renderer streams from the phone's proxy whenever it can reach it. "Can reach it" is
> established by observing the renderer actually fetch, not by comparing addresses.**

The proof is free, because it is a side effect of something that has to happen anyway:
`SetAVTransportURI` + `Play`, then wait for `MediaProxyServer.awaitRequest(token)`. A renderer that
is going to play fetches within a second or two. One that cannot reach the phone never fetches, and
that silence is the answer.

`SubnetMatch` still exists and is still used — as a **fast path**, so the common case does not pay
the proof's timeout before anything happens. It is an optimisation with a name, not the rule.

> **Spec §6's other detection sentence still holds, and more strongly.** *"Detection is a subnet
> comparison, not SSID sniffing — SSID needs `ACCESS_FINE_LOCATION` and fails silently without
> it."* Nothing in this task reads an SSID, and nothing in it reads a location. The reachability
> rule needs strictly **less** than the subnet comparison did: no `ConnectivityManager`, no
> `LinkProperties`, no permission of any kind — only a socket that either got a request or did not.
> That is why `:core:cast` can be a pure-JVM module at all, and it is worth saying because the
> obvious reading of "replace the subnet comparison" is "with something that needs more".

### The three outcomes, and why the third one is the important one

```
Proxied         the renderer fetches from the phone.  The default, and what the spec's table wants.
RendererDirect  the renderer fetches Navidrome itself. Off by default -- see below.
Unroutable      neither. Casting fails, loudly, with a reason.
```

**`Unroutable` is the outcome that does not exist in the spec**, and it is the one that matters
most. Without it the failure mode is: the picker accepts the tap, the session starts, `Play`
returns 200, the UI says "Playing on Kitchen", and nothing ever comes out of the speaker. That is
the silent-wrong-answer class in its purest form. With it, the user is told *"Kitchen could not
reach this phone — it is probably on a different network"*, which is something they can act on.

**`RendererDirect` is off by default, and that is a security decision.** Spec §6 claims the rule
*"designs the Let's Encrypt trust question out of existence entirely"*, and that claim is only true
if the direct branch is never taken. Taking it means:

- handing the speaker a Navidrome URL with the user's **auth token on it** — Subsonic tokens do not
  expire, and the speaker logs URLs;
- requiring the speaker to trust Navidrome's TLS chain, which is exactly the Let's Encrypt question;
- streaming the user's library over their metered connection without saying so.

None of that is unreasonable if the user chooses it knowingly. All of it is unreasonable as a
silent fallback. So it is a setting, default off, and when it is off the third outcome fires. The
spec's claim is then corrected to say the trust question is **deferred by default**, not eliminated
— Task 11 makes that edit.

- [ ] **Step 1: Write the failing subnet test**

`core/cast/src/test/kotlin/app/muplay/cast/route/SubnetMatchTest.kt`:

```kotlin
package app.muplay.cast.route

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The **fast path**, not the rule. See `CastRouter`'s documentation for why: spec section 6's own
 * VPN row is routinely not subnet-equal, and the proxy still works there.
 */
class SubnetMatchTest {

  private fun same(a: String, b: String, prefix: Int) =
    SubnetMatch.sameSubnet(InetAddress.getByName(a), InetAddress.getByName(b), prefix)

  @Test
  fun `two addresses in one 24 are the same subnet, and the neighbours are not`() {
    assertThat(same("192.168.1.20", "192.168.1.50", 24)).isTrue
    assertThat(same("192.168.1.20", "192.168.2.50", 24)).isFalse
    assertThat(same("192.168.1.20", "192.168.0.255", 24)).isFalse
  }

  @Test
  fun `the prefix length is used, and changing only it changes the answer`() {
    // The argument's effect, isolated. A `sameSubnet` that hardcoded /24 passes the test above and
    // fails here, and a home network on a /16 is ordinary.
    assertThat(same("192.168.1.20", "192.168.2.50", 16)).isTrue
    assertThat(same("192.168.1.20", "192.168.2.50", 24)).isFalse
    assertThat(same("10.0.1.20", "10.0.2.50", 8)).isTrue
    assertThat(same("10.0.1.20", "10.0.2.50", 22)).isFalse
  }

  @Test
  fun `a prefix that is not a whole number of bytes is handled`() {
    // /22 covers 10.0.0.0 - 10.0.3.255. The bit-level boundary is where a byte-wise
    // implementation quietly gives the wrong answer.
    assertThat(same("10.0.3.255", "10.0.0.1", 22)).isTrue
    assertThat(same("10.0.4.0", "10.0.0.1", 22)).isFalse
  }

  @Test
  fun `a prefix of zero matches everything and a prefix of 32 matches only itself`() {
    assertThat(same("1.2.3.4", "250.251.252.253", 0)).isTrue
    assertThat(same("1.2.3.4", "1.2.3.4", 32)).isTrue
    assertThat(same("1.2.3.4", "1.2.3.5", 32)).isFalse
  }

  @Test
  fun `addresses of different families are never the same subnet`() {
    assertThat(same("192.168.1.1", "fd00::1", 24)).isFalse
  }

  @Test
  fun `ipv6 prefixes work too`() {
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:1::20", 64)).isTrue
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:2::20", 64)).isFalse
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:2::20", 48)).isTrue
  }
}
```

`core/cast/src/main/kotlin/app/muplay/cast/route/SubnetMatch.kt`:

```kotlin
package app.muplay.cast.route

import java.net.InetAddress

/**
 * Whether two addresses share a network prefix.
 *
 * **A fast path in [CastRouter], not the routing rule.** Spec section 6 words the rule as "same
 * subnet as the speaker", and its own third situation -- the phone on a VPN, the speaker on the
 * home LAN -- is routinely *not* subnet-equal while the proxy works perfectly over the tunnel. So
 * a `true` here means "skip the reachability proof, this will obviously work"; a `false` means
 * nothing at all except "find out".
 */
object SubnetMatch {

  fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
    val left = a.address
    val right = b.address
    // Comparing a 4-byte and a 16-byte address bit by bit would read past the end of one of them.
    if (left.size != right.size) return false
    if (prefixLength <= 0) return true
    if (prefixLength > left.size * Byte.SIZE_BITS) return left.contentEquals(right)

    val wholeBytes = prefixLength / Byte.SIZE_BITS
    val remainingBits = prefixLength % Byte.SIZE_BITS
    repeat(wholeBytes) { index -> if (left[index] != right[index]) return false }
    if (remainingBits == 0) return true
    // The partial byte. A byte-wise-only implementation silently gives the wrong answer for every
    // prefix that is not a multiple of 8 -- /22 and /26 are ordinary on a real network.
    val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
    return (left[wholeBytes].toInt() and mask) == (right[wholeBytes].toInt() and mask)
  }
}
```

- [ ] **Step 2: Write the failing router test**

`core/cast/src/test/kotlin/app/muplay/cast/route/CastRouterTest.kt` — with a **real**
`MediaProxyServer` on loopback and the **real** `FakeRenderer`, because the whole subject is
whether the renderer actually fetched.

```kotlin
  @Test
  fun `the default route is proxied, and it names the phone address the renderer can reach`() {
    val route = router().candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    // The host is the source address the kernel would use to reach the renderer -- not an
    // enumerated interface. Against a loopback renderer that is 127.0.0.1; on a phone with Wi-Fi
    // and a VPN up it is whichever one routes to the speaker.
    assertThat(route.url).isEqualTo("http://127.0.0.1:${proxy.port}${route.media.path}")
    assertThat(route.url).endsWith(".mp3")
    assertThat(route.media.upstreamUrl).isEqualTo(UPSTREAM)
  }

  @Test
  fun `a second candidate for a second track gets its own token`() {
    // Two observations, so `candidate` cannot return a constant path.
    val first = router().candidate(device, "https://nav/rest/stream?id=1", MP3) as CastRoute.Proxied
    val second = router().candidate(device, "https://nav/rest/stream?id=2", MP3) as CastRoute.Proxied

    assertThat(first.media.token).isNotEqualTo(second.media.token)
    assertThat(first.url).isNotEqualTo(second.url)
  }

  @Test
  fun `a renderer that fetches confirms the proxied route`() {
    // The proof, in the direction where it succeeds. `fetchesMedia` is on, so the fake behaves as
    // a real renderer does: HEAD, then a ranged GET.
    renderer.fetchesMedia = true
    val route = router().candidate(device, UPSTREAM, MP3)

    upnp.setUri(castItem(route)); upnp.play()
    val confirmed = router().confirm(route, UPSTREAM)

    assertThat(confirmed).isSameAs(route)
    assertThat(proxy.requestLog.map { it.method }).contains("HEAD", "GET")
  }

  @Test
  fun `a renderer that cannot reach the phone falls back to renderer-direct when that is allowed`() {
    // The proof, in the direction where it fails. Without this test the fallback is dead code, and
    // dead code that only runs on a stranger's network is the worst kind.
    renderer.fetchesMedia = false
    val router = router(allowRendererDirect = true, proofTimeoutMs = 300L)
    val route = router.candidate(device, UPSTREAM, MP3)

    upnp.setUri(castItem(route)); upnp.play()
    val confirmed = router.confirm(route, UPSTREAM)

    assertThat(confirmed).isInstanceOf(CastRoute.RendererDirect::class.java)
    assertThat((confirmed as CastRoute.RendererDirect).url).isEqualTo(UPSTREAM)
  }

  @Test
  fun `a renderer that cannot reach the phone is Unroutable when direct is not allowed`() {
    // The default, and the outcome the spec does not have. Without it, a cast that cannot work
    // starts, reports success, and plays nothing.
    renderer.fetchesMedia = false
    val router = router(allowRendererDirect = false, proofTimeoutMs = 300L)
    val route = router.candidate(device, UPSTREAM, MP3)

    upnp.setUri(castItem(route)); upnp.play()
    val confirmed = router.confirm(route, UPSTREAM)

    assertThat(confirmed).isInstanceOf(CastRoute.Unroutable::class.java)
    val unroutable = confirmed as CastRoute.Unroutable
    assertThat(unroutable.reason).isEqualTo(UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED)
    // The detail reaches the user. A reason enum with an empty detail would leave the picker
    // saying "something went wrong".
    assertThat(unroutable.detail).contains(device.friendlyName)
  }

  @Test
  fun `confirming a route revokes the proxy token when it falls back`() {
    // A token for a route nobody is using is a capability left lying on the LAN.
    renderer.fetchesMedia = false
    val router = router(allowRendererDirect = true, proofTimeoutMs = 300L)
    val route = router.candidate(device, UPSTREAM, MP3) as CastRoute.Proxied

    router.confirm(route, UPSTREAM)

    assertThat(registry.resolve(route.media.path)).isNull()
  }

  @Test
  fun `a renderer with no route from this phone is Unroutable before anything is published`() {
    // `LocalAddress.towards` returning null. Nothing should be minted, because nothing can be
    // fetched -- and a published token that is never used is a capability with no owner.
    val router = CastRouter(proxy, registry, allowRendererDirect = false,
      localAddress = { null }, proofTimeoutMs = 300L)

    val route = router.candidate(device, UPSTREAM, MP3)

    assertThat(route).isInstanceOf(CastRoute.Unroutable::class.java)
    assertThat((route as CastRoute.Unroutable).reason).isEqualTo(UnroutableReason.NO_ROUTE_TO_RENDERER)
    assertThat(registry.resolve("/media/")).isNull()
  }

  @Test
  fun `the proof waits for this renderer's own token and not for any request at all`() {
    // Two tracks published, only the second fetched. A proof that counted requests rather than
    // matching the token would confirm the wrong route -- and would confirm a route on the strength
    // of a stale request from the previous track.
    val stale = router().candidate(device, "https://nav/1", MP3) as CastRoute.Proxied
    val fresh = router().candidate(device, "https://nav/2", MP3) as CastRoute.Proxied
    fetchDirectly(fresh.url)   // only the fresh one is fetched

    val router = router(allowRendererDirect = false, proofTimeoutMs = 300L)
    assertThat(router.confirm(fresh, "https://nav/2")).isSameAs(fresh)
    assertThat(router.confirm(stale, "https://nav/1")).isInstanceOf(CastRoute.Unroutable::class.java)
  }

  @Test
  fun `the fast path skips the proof for a renderer on this phone's own subnet`() {
    // Measured, not asserted by inspection: with `fetchesMedia` off and the fast path engaged, a
    // confirm must return promptly and still be `Proxied`. Without a fast path this call would
    // block for the whole proof timeout before answering.
    renderer.fetchesMedia = false
    val router = router(allowRendererDirect = false, proofTimeoutMs = 5_000L, sameSubnet = true)
    val route = router.candidate(device, UPSTREAM, MP3)

    val elapsed = measureTimeMillis { assertThat(router.confirm(route, UPSTREAM)).isSameAs(route) }

    assertThat(elapsed).isLessThan(1_000L)
  }

  @Test
  fun `the fast path is not taken when the renderer is on another subnet`() {
    // The other direction, so `sameSubnet` cannot be hardcoded true -- which would disable the
    // proof entirely and reinstate the silent failure this whole task exists to remove.
    renderer.fetchesMedia = false
    val router = router(allowRendererDirect = false, proofTimeoutMs = 300L, sameSubnet = false)
    val route = router.candidate(device, UPSTREAM, MP3)

    assertThat(router.confirm(route, UPSTREAM)).isInstanceOf(CastRoute.Unroutable::class.java)
  }
```

- [ ] **Step 3: Implement `CastRoute` and `CastRouter`**

`core/cast/src/main/kotlin/app/muplay/cast/route/CastRoute.kt`:

```kotlin
package app.muplay.cast.route

import app.muplay.cast.proxy.PublishedMedia

/** Why a renderer cannot be given anything to play. */
enum class UnroutableReason {
  /** This phone has no network route to the renderer at all. */
  NO_ROUTE_TO_RENDERER,

  /**
   * The renderer never fetched from the proxy, and renderer-direct is switched off.
   *
   * The important outcome. Without it the failure is: the tap is accepted, `Play` returns 200, the
   * UI says "Playing on Kitchen", and nothing comes out of the speaker, forever, with nothing
   * reported anywhere.
   */
  PROXY_UNREACHABLE_AND_DIRECT_DISABLED,
}

/** Where a renderer is told to get the bytes. */
sealed interface CastRoute {

  /** From the phone. The default, and what spec section 6's table wants in all three situations. */
  data class Proxied(val url: String, val media: PublishedMedia) : CastRoute

  /**
   * From Navidrome, directly.
   *
   * **Off by default**, and that is a security decision rather than a conservative one. It hands
   * the speaker a URL carrying the user's Subsonic auth token -- which does not expire, and which
   * speakers log -- requires the speaker to trust Navidrome's TLS chain, and streams over the
   * user's connection without saying so. Reasonable if chosen knowingly; not as a silent fallback.
   */
  data class RendererDirect(val url: String) : CastRoute

  /** Neither. Casting fails, with a reason a user can act on. */
  data class Unroutable(val reason: UnroutableReason, val detail: String) : CastRoute
}
```

`core/cast/src/main/kotlin/app/muplay/cast/route/CastRouter.kt`:

```kotlin
package app.muplay.cast.route

import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.net.LocalAddress
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import java.net.InetAddress

/**
 * **Where a renderer is told to fetch from, and how that is established.**
 *
 * Spec section 6 words the rule as *"Same subnet as the speaker -> stream through the phone proxy.
 * Otherwise -> the speaker fetches Navidrome directly"* -- and then gives three situations, **all
 * three of which answer "proxy"**, including "Remote + VPN ... proxy over the tunnel".
 *
 * Row 3 is routinely not subnet-equal. A routed VPN -- WireGuard, Tailscale, OpenVPN in `tun` mode,
 * which is how essentially everyone builds this -- puts the phone on `10.8.0.0/24` or
 * `100.64.0.0/10` and *routes* the home LAN over it. Phone `10.8.0.3`, speaker `192.168.1.50`:
 * different subnets, tunnel routes both ways, proxy works. Applied literally, the rule sends that
 * case down the "otherwise" branch, which is the opposite of what the spec's own table says.
 *
 * Subnet equality is neither necessary (a routed tunnel) nor sufficient (client isolation on a
 * guest network) for the question the rule is really asking, which is:
 *
 * > **can the renderer open a TCP connection back to this phone?**
 *
 * That question has an exact answer and it costs nothing to get, because the answer is a side
 * effect of something that must happen anyway: after `SetAVTransportURI` and `Play`, a renderer
 * that is going to play **fetches**, within a second or two. [MediaProxyServer.awaitRequest]
 * watches for it. Silence is the answer, and it is the only reliable one.
 *
 * [SubnetMatch] is still used, as a **fast path**, so the ordinary case does not wait out a
 * timeout before anything happens. It is an optimisation with a name, not the rule.
 */
class CastRouter(
  private val proxy: MediaProxyServer,
  private val registry: ProxyRegistry,
  private val allowRendererDirect: Boolean,
  private val localAddress: (InetAddress) -> InetAddress? = LocalAddress::towards,
  private val sameSubnetFastPath: (InetAddress, InetAddress) -> Boolean = { _, _ -> false },
  private val proofTimeoutMs: Long = DEFAULT_PROOF_TIMEOUT_MS,
) {

  /**
   * Publishes the item and returns the route to try first.
   *
   * Nothing is published when there is no route to the renderer at all: a token nobody can fetch is
   * a capability lying on the network with no owner.
   */
  fun candidate(device: CastDevice, upstreamUrl: String, served: ServedMedia): CastRoute {
    val rendererHost = device.avTransportControlUrl.host
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "no host in its control URL")
    val rendererAddress = runCatching { InetAddress.getByName(rendererHost) }.getOrNull()
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "its address could not be resolved")
    val phoneAddress = localAddress(rendererAddress)
      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "this phone has no route to it")

    val media = registry.publish(upstreamUrl, served)
    return CastRoute.Proxied(proxy.urlFor(media, phoneAddress.hostAddress), media)
  }

  /**
   * Waits for the renderer to prove it can reach the proxy, and falls back if it does not.
   *
   * Call **after** `SetAVTransportURI` and `Play`. The wait is on **this route's own token**, not
   * on "any request": a proxy that had served the previous track would otherwise confirm a route
   * that has never been fetched.
   */
  fun confirm(route: CastRoute, upstreamUrl: String): CastRoute {
    if (route !is CastRoute.Proxied) return route
    if (proxy.awaitRequest(route.media.token, proofTimeoutMs)) return route

    // It did not fetch. Whatever happens next, this token is no longer wanted.
    registry.revoke(route.media.token)

    return if (allowRendererDirect) {
      CastRoute.RendererDirect(upstreamUrl)
    } else {
      CastRoute.Unroutable(
        UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED,
        "the speaker did not fetch anything from this phone within ${proofTimeoutMs / 1000} seconds. " +
          "It is probably on a different network, or the network blocks devices from talking to " +
          "each other.",
      )
    }
  }

  /**
   * Drops every published token.
   *
   * Called when a session ends ([app.muplay.media.cast.UpnpPlayer] on release, and the session
   * manager on handover back). A proxy still serving after the session that published it has gone
   * is a capability lying on the LAN with nobody watching it.
   */
  fun revokeAll() = registry.revokeAll()

  private fun unroutable(device: CastDevice, reason: UnroutableReason, why: String) =
    CastRoute.Unroutable(reason, "${device.friendlyName} cannot be reached: $why.")

  companion object {
    /**
     * How long to wait for the renderer's first fetch.
     *
     * A Sonos fetches within a second of `Play`; six seconds covers a slow renderer and a busy
     * network without leaving the user watching a spinner. The fast path means the ordinary case
     * never waits at all.
     */
    const val DEFAULT_PROOF_TIMEOUT_MS: Long = 6_000L
  }
}
```

> **The `sameSubnetFastPath` parameter defaults to "never", and that is deliberate for now.**
> Wiring it needs the renderer's prefix length, which on Android comes from
> `ConnectivityManager.getLinkProperties(network).linkAddresses[].prefixLength` — an Android type,
> and `:core:cast` has none by design. Task 9 supplies it from `:core:media` as a lambda. Until
> then every route is proved, which is slower and never wrong; the test
> `the fast path skips the proof for a renderer on this phone's own subnet` passes the lambda
> directly and is what stops the parameter being inert.

- [ ] **Step 4: Run the routing tests**

Run: `./gradlew :core:cast:test --tests '*CastRouterTest*' --tests '*SubnetMatchTest*'` — PASS.

- [ ] **Step 5: Record the spec defect**

Write the two §6 corrections into the task report verbatim, for Task 11 to apply in one commit:

1. **The routing rule.** Replace *"Same subnet as the speaker → stream through the phone proxy.
   Otherwise → the speaker fetches Navidrome directly"* with a reachability rule, and demote subnet
   comparison to a fast path — because the spec's own row 3 is routinely not subnet-equal under a
   routed VPN, which is how the VPN case is normally built.
2. **The Let's Encrypt claim.** *"Under this rule no speaker ever fetches over public HTTPS, which
   designs the Let's Encrypt trust question out of existence entirely"* is true only if the
   renderer-direct branch is unreachable — and §6 defines that branch. Correct it to say the
   question is **deferred by default**: renderer-direct is off unless the user turns it on, and a
   third outcome, `Unroutable`, exists so the failure is loud rather than silent.

- [ ] **Step 6: Prove each new assertion can fail, record probes, measure, commit**

1. In `CastRouter.confirm`, return `route` unconditionally. Expect
   `a renderer that cannot reach the phone falls back...` and
   `a renderer that cannot reach the phone is Unroutable...` to fail. **This is the mutation that
   restores the silent failure**, and it is the single most valuable probe in this plan.
2. In `CastRouter.confirm`, always return `Unroutable`. Expect
   `a renderer that fetches confirms the proxied route` and the fast-path test to fail — the branch
   discriminating in both directions.
3. In `CastRouter.confirm`, swap the `allowRendererDirect` branches. Expect both fallback tests to
   fail, each with the other's expected type.
4. In `MediaProxyServer.awaitRequest`, count any request rather than the token's. Expect
   `the proof waits for this renderer's own token and not for any request at all` to fail.
5. In `SubnetMatch.sameSubnet`, ignore `remainingBits`. Expect
   `a prefix that is not a whole number of bytes is handled` to fail.
6. In `CastRouter.candidate`, publish before checking `localAddress`. Expect
   `a renderer with no route from this phone is Unroutable before anything is published` to fail on
   its registry assertion.

Record 1, 2, 4 and 5 in `ci/mutation-probes.sh` as `route/*` entries. Add
`"app.muplay.cast.route.SubnetMatch"`, `"app.muplay.cast.route.CastRouter"` and
`"app.muplay.cast.route.CastRoute*"` to the `:core:cast` BRANCH floor; measure; confirm ≥ 0.90.

```bash
./gradlew :core:cast:test jacocoTestReport jacocoJvmCoverageVerification
git add core/cast build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): route by proving the renderer can reach the phone, not by guessing"
```

---

## Task 8: `UpnpPlayer` — a `SimpleBasePlayer` over the renderer, and the renderer that disappears

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/cast/UpnpPlayer.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/cast/CastSessionState.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/cast/CastQueueItem.kt`
- Create: `core/media/src/test/kotlin/app/muplay/media/cast/CastQueueItemTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/cast/UpnpPlayerTest.kt`
- Modify: `core/media/build.gradle.kts` (the fake renderer on the device — see Step 1)
- Modify: `build.gradle.kts`, `.github/workflows/e2e.yml`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes: `UpnpRenderer`, `TransportState`, `TransportInfo`, `PositionInfo`,
  `RendererCapabilities`, `RendererFollowsAnotherException` (Task 5); `CastItem`, `DidlLite`,
  `ServedMedia` (Task 4); `CastRoute`, `CastRouter` (Task 7); `SoapTransportException`,
  `UpnpErrorException` (Task 3).
- Consumes from **Plan 3**: `MediaItems`, `PlaybackQueue`, and — for Task 9 — `ResumePolicy`,
  `ResumeTarget`, `MuPlayer`, `ProgressWriter`.
- Produces:
  - `data class CastQueueItem(val mediaItem: MediaItem, val castItem: CastItem, val route: CastRoute)`
  - `object CastQueueItems` with
    `fun castItem(mediaId: String, title: String, artist: String?, albumTitle: String?, artworkUri: String?, durationMs: Long, isAudiobook: Boolean, resourceUrl: String, served: ServedMedia): CastItem`
    — the pure mapping, JVM-testable — and
    `fun forMediaItem(mediaItem: MediaItem, router: CastRouter, device: CastDevice): CastQueueItem`
    — the Android-typed wrapper that pulls the fields off a `MediaItem`, asks [CastRouter.candidate]
    for a route, and calls `castItem`. **Two functions, one name each**: the split is what puts the
    field mapping in Tier 1 while `MediaItem`'s `android.net.Uri` stays on the device.
  - `sealed interface CastSessionState` with `data object Idle`, `data class Connecting(deviceName: String)`,
    `data class Playing(deviceName: String)`, `data class Failed(deviceName: String, reason: String)`,
    `data class Lost(deviceName: String, positionMs: Long, mediaId: String?)`
  - `class UpnpPlayer(device, renderer, router, deviceName, scope, clock, onSessionEnded: (CastSessionState) -> Unit) : SimpleBasePlayer`
    with `companion object { const val POLL_INTERVAL_MS = 1_000L; const val LOST_AFTER_FAILURES = 3; const val END_OF_TRACK_TOLERANCE_MS = 1_500L }`
- **Plan 4 interaction, stated at the point of use:**
  - `ProgressWriter` (Plan 3 Task 8) takes an `androidx.media3.common.Player`. `UpnpPlayer` **is**
    one, so the writer attaches to it unchanged and a book cast to a speaker has its position
    recorded by the same code that records it locally. **This plan adds no cast-specific progress
    path.** For that to work, `UpnpPlayer` must fire the right listener callbacks — see
    "What being a real `Player` buys" below, and the test that asserts it.
  - `getPlaybackParameters()` returns `PlaybackParameters(1.0f)` **always**, and
    `handleSetPlaybackParameters` accepts nothing else. Whatever Plan 4 stores as a book's
    per-item speed cannot be delivered over `AVTransport` (`Speed` must be `"1"`; Sonos answers
    `717` otherwise). Reporting 1.0 is what makes the limitation visible to the UI instead of
    silently ignored. **Plan 4's name for its speed accessor is not fixed**; nothing here reads it.

### What being a real `Player` buys, and why this is the whole design

A cast route is *"send that URL somewhere else instead of decoding it locally"*. Plan 3 built
everything above the player against `androidx.media3.common.Player`:

- `MuPlayer` is a `ForwardingPlayer` — it wraps a `Player`, not an `ExoPlayer`;
- `ProgressWriter(player: Player, dao, clock, scope)` listens to a `Player`;
- `MediaSession` holds a `Player` and can be handed a different one;
- `PlaybackConnection` reads a `MediaController`, which reflects whatever the session holds;
- `:feature:player`, the notification, the lock screen, Android Auto and Wear all read the session.

So if the cast output **is** a `Player`, none of that changes. That is why this task exists in this
shape rather than as a parallel "cast controller" with its own state, its own progress writing and
its own UI — which is the shape that would need Plan 4 to be redesigned around it.

`SimpleBasePlayer` (`androidx.media3.common`, since Media3 1.0) is the abstract `Player` written
for exactly this: implement `getState()` and a handful of `handle*` methods returning
`ListenableFuture`, call `invalidateState()` when something changed, and Media3 derives every
listener callback — `onIsPlayingChanged`, `onMediaItemTransition`, `onPositionDiscontinuity`,
`onPlaybackStateChanged` — from the diff between states. Google's own `CastPlayer` is built on it.

**Position between polls comes from `PositionSupplier.getExtrapolating`.** A 1 Hz poll would make
the seek bar tick once a second; the extrapolating supplier advances the reported position in real
time from the last known value and resets on each poll. `ProgressWriter`'s ticker reads
`player.currentPosition`, so it gets a smooth, correct value without a second polling loop.

### Why polling, and not GENA eventing

`SUBSCRIBE`/`NOTIFY` is the "proper" way to learn a renderer's state, and this plan does not use it
for one decisive reason: **the `AVTransport:1` `LastChange` event does not carry playback position.**
`RelTime` and `AbsTime` are excluded from the evented state variable set. So an eventing
implementation would still have to poll `GetPositionInfo` for the seek bar — and would additionally
owe an HTTP callback endpoint, subscription renewal timers at ~85% of a granted timeout, and a
parser for XML-escaped XML nested inside an XML document. It buys a faster `PLAYING`/`STOPPED`
transition and nothing else. Written in `UpnpPlayer`'s KDoc, so the next reader finds the reason.

### The renderer that disappears mid-stream

A speaker loses power, or the user walks out of range, or the Wi-Fi drops. Spec §6 is explicit
that *"Playback stopping when the phone leaves the network is **intended behaviour**"* — but
*stopping* and *appearing to play forever* are different things, and only one of them is intended.

Detection: **three consecutive poll failures** that are `SoapTransportException` (the speaker is
not answering) and **not** `UpnpErrorException` (the speaker answered and said no). Task 5 keeps
those two apart precisely so this branch can. Three, not one, because a single missed poll on a
busy Wi-Fi network is ordinary; three seconds of silence is not.

Response: report `Player.STATE_IDLE` with a `PlaybackException`, and hand
`CastSessionState.Lost(deviceName, positionMs, mediaId)` to the session manager — carrying the
**last known position**, which is what Task 9 uses to resume locally on the same second.

- [ ] **Step 1: Put the fake renderer where the device tests can reach it**

`FakeRenderer` lives in `:core:cast`'s `src/test`, which is not published to consumers. Two ways to
reach it from `:core:media`'s `androidTest`, and this plan takes the second:

- a `testFixtures` source set — supported, and it means a new source set, a new artifact, new
  coverage bookkeeping, and `ConventionTest` learning about it;
- **move `FakeRenderer`, `FakeSsdpResponder` and `FakeDescriptions` to
  `core/cast/src/main/kotlin/app/muplay/cast/fake/`** and depend on `:core:cast` from
  `:core:media`'s `androidTest`, which it already does for main.

The second is chosen because these three classes are **real servers**, not assertions: a strict
in-process UPnP renderer is a piece of this subsystem's public surface, useful to `:core:media`,
to `:app`'s Tier 2 journey, and to anyone debugging a real speaker. It costs a small amount of
production code that ships in the APK.

> **That cost is real and must be paid explicitly**, not waved away. Add
> `"app.muplay.cast.fake.*"` to `:core:cast`'s coverage **excludes** with the comment *"test
> infrastructure that lives in main so device tests can reach it; its own behaviour is gated by
> `FakeRendererStrictnessTest`"* — otherwise these classes drag the module's measured branch
> coverage around for reasons unrelated to the code under test. And note in the task report that
> ~600 lines of test server ship in the release APK; if that ever matters, the answer is R8, or
> promoting it to `testFixtures` then.

`core/media/build.gradle.kts` — add:

```kotlin
  androidTestImplementation(project(":core:cast"))
```

- [ ] **Step 2: Write the failing JVM test for the pure part**

`core/media/src/test/kotlin/app/muplay/media/cast/CastQueueItemTest.kt` — `CastQueueItems.castItemFor`
maps a `MediaItem`'s metadata onto a `CastItem`. That is a field mapping, and field mappings are
where this project has shipped the same defect five times.

```kotlin
  @Test
  fun `every field of the cast item comes from the media item`() {
    // `MediaItem` and `MediaMetadata` are built on android.net.Uri, which throws on a bare JVM --
    // so this class takes the already-extracted values rather than a MediaItem, and the MediaItem
    // extraction itself is asserted on the device in `UpnpPlayerTest`. Splitting it this way is
    // the same move Plan 3 made for `StreamRetryPolicy`: put the decision where Tier 1 can hold it.
    val item = CastQueueItems.castItem(
      mediaId = "track-1", title = "Track 1", artist = "Artist", albumTitle = "Album",
      artworkUri = "http://a/1.jpg", durationMs = 300_000L, isAudiobook = false,
      resourceUrl = "http://10.0.0.2:8080/media/x.mp3", served = ServedMedia.of("mp3", StreamFormat.Raw),
    )

    assertThat(item.mediaId).isEqualTo("track-1")
    assertThat(item.title).isEqualTo("Track 1")
    assertThat(item.artist).isEqualTo("Artist")
    assertThat(item.albumTitle).isEqualTo("Album")
    assertThat(item.artworkUri).isEqualTo("http://a/1.jpg")
    assertThat(item.durationMs).isEqualTo(300_000L)
    assertThat(item.resourceUrl).isEqualTo("http://10.0.0.2:8080/media/x.mp3")
    assertThat(item.upnpClass).isEqualTo(DidlLite.CLASS_MUSIC_TRACK)
  }

  @Test
  fun `a second item differs in every field`() {
    // Rule 2, applied across the whole mapping at once: nine fields, two observations each.
    val second = CastQueueItems.castItem(
      mediaId = "chapter-14", title = "Chapter 14", artist = "Reader", albumTitle = "A Book",
      artworkUri = null, durationMs = 3_723_000L, isAudiobook = true,
      resourceUrl = "http://10.0.0.2:8080/media/y.m4b", served = ServedMedia.of("m4b", StreamFormat.Raw),
    )

    assertThat(second.mediaId).isEqualTo("chapter-14")
    assertThat(second.title).isEqualTo("Chapter 14")
    assertThat(second.artist).isEqualTo("Reader")
    assertThat(second.albumTitle).isEqualTo("A Book")
    assertThat(second.artworkUri).isNull()
    assertThat(second.durationMs).isEqualTo(3_723_000L)
    assertThat(second.resourceUrl).isEqualTo("http://10.0.0.2:8080/media/y.m4b")
    assertThat(second.served.mimeType).isEqualTo("audio/mp4")
  }

  @Test
  fun `a book gets the audioBook upnp class and music gets musicTrack`() {
    // The branch, in both directions. A renderer's display and its own library grouping read this,
    // and it is the one field that is not a copy.
    assertThat(CastQueueItems.castItem(isAudiobook = true, /* ... */).upnpClass)
      .isEqualTo(DidlLite.CLASS_AUDIO_BOOK)
    assertThat(CastQueueItems.castItem(isAudiobook = false, /* ... */).upnpClass)
      .isEqualTo(DidlLite.CLASS_MUSIC_TRACK)
  }

  @Test
  fun `a missing duration becomes zero rather than a negative sentinel`() {
    // `MediaItem` durations arrive as `C.TIME_UNSET` before the extractor has read the container,
    // and a negative `res@duration` makes several renderers refuse the item outright.
    assertThat(CastQueueItems.castItem(durationMs = -9_223_372_036_854_775_807L, /* ... */).durationMs)
      .isZero
  }
```

- [ ] **Step 3: Write the failing device test**

`core/media/src/androidTest/kotlin/app/muplay/media/cast/UpnpPlayerTest.kt`, against the real
`FakeRenderer` on `127.0.0.1:0` and the real `MediaProxyServer`. Every assertion below is about
**state a `Player` reports** or **bytes a renderer received** — never "the method returned".

```kotlin
  @Test
  fun theQueueIsWhatWasSetAndThePlayerReportsIt() {
    // Three items, so index arithmetic is observable and `hasSize(3)` is not the whole assertion.
    onMain { player.setMediaItems(items, 0, 0L) }

    assertThat(onMain { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } })
      .containsExactly("track-1", "track-2", "track-3")
    assertThat(onMain { player.currentMediaItemIndex }).isZero
  }

  @Test
  fun preparingSendsTheUriAndTheDidlToTheRenderer() {
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    // Read off the renderer's recorded bytes, not off the player.
    val request = fake.soapRequests.last { it.action == "SetAVTransportURI" }
    assertThat(request.arguments.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    assertThat(request.arguments[1].second).endsWith(".mp3")
    assertThat(request.arguments[2].second).startsWith("&lt;DIDL-Lite")
  }

  @Test
  fun theRendererActuallyFetchedTheMedia() {
    // The discriminating observation. `player.play()` returning, `playWhenReady == true` and
    // `STATE_READY` are all satisfied by a renderer that was told to play and could not.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }

    val fetch = fake.awaitMediaRequest(timeoutMs = 5_000)

    assertThat(fetch).isNotNull
    assertThat(fetch!!.target).endsWith(".mp3")
    assertThat(proxy.requestLog.map { it.method }).contains("HEAD", "GET")
  }

  @Test
  fun thePositionAdvancesWhileTheRendererIsPlaying() {
    // Not `isPlaying`. Two reads of `currentPosition` separated by real time, strictly increasing
    // -- the same rule Plan 3 imposed on local playback, for the same reason.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    val first = onMain { player.currentPosition }
    fake.advance(2_000L)
    awaitPositionAtLeast(first + 1_500L, timeoutMs = 5_000)

    assertThat(onMain { player.currentPosition }).isGreaterThan(first)
  }

  @Test
  fun thePositionExtrapolatesBetweenPolls() {
    // Without `PositionSupplier.getExtrapolating` the seek bar jumps once a second. Two reads
    // inside one poll interval must still differ.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    val first = onMain { player.currentPosition }
    Thread.sleep(UpnpPlayer.POLL_INTERVAL_MS / 3)
    assertThat(onMain { player.currentPosition }).isGreaterThan(first)
  }

  @Test
  fun pausingStopsTheClockAndReachesTheRenderer() {
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    onMain { player.pause() }
    awaitCondition { !onMain { player.isPlaying } }
    val paused = onMain { player.currentPosition }
    Thread.sleep(UpnpPlayer.POLL_INTERVAL_MS * 2)

    assertThat(fake.currentTransportState()).isEqualTo("PAUSED_PLAYBACK")
    // The clock stopped, which "Pause was sent" does not prove.
    assertThat(onMain { player.currentPosition }).isEqualTo(paused)
  }

  @Test
  fun seekingMovesTheRendererAndThenThePlayer() {
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    onMain { player.seekTo(83_000L) }
    awaitPositionAtLeast(83_000L, timeoutMs = 5_000)

    assertThat(fake.soapRequests.last { it.action == "Seek" }.arguments)
      .contains("Unit" to "REL_TIME", "Target" to "0:01:23")
    assertThat(onMain { player.currentPosition }).isGreaterThanOrEqualTo(83_000L)
  }

  @Test
  fun aDeviceThatCannotSeekDoesNotAdvertiseTheSeekCommands() {
    // The honest UI. A device whose SCPD offers no time seek mode must not show a seek bar --
    // rather than showing one that produces a 710 on every drag.
    val restricted = renderer(FakeRenderer.Strictness(supportedSeekModes = listOf("TRACK_NR")))

    assertThat(onMain { restricted.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isFalse
    // ...and the ordinary device does advertise them, so the flag is not simply always off.
    assertThat(onMain { player.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
  }

  @Test
  fun theTrackEndsAndTheNextOneIsSentToTheRenderer() {
    // Transition, observed as the renderer receiving the SECOND uri -- not as a callback firing.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    fake.advance(TRACK_DURATION_MS)
    awaitCondition { fake.soapRequests.count { it.action == "SetAVTransportURI" } >= 2 }

    val uris = fake.soapRequests.filter { it.action == "SetAVTransportURI" }.map { it.arguments[1].second }
    assertThat(uris).hasSizeGreaterThanOrEqualTo(2)
    assertThat(uris[0]).isNotEqualTo(uris[1])
    assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
  }

  @Test
  fun aStopThatIsNotTheEndOfTheTrackDoesNotSkipToTheNextOne() {
    // The other side of the same branch. A renderer reports STOPPED for "finished" and for "the
    // user pressed stop on the speaker", and reading them the same way skips a track every time
    // someone touches the hardware.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    fake.advance(1_000L)
    stopFromTheRendererSide()
    Thread.sleep(UpnpPlayer.POLL_INTERVAL_MS * 3)

    assertThat(onMain { player.currentMediaItemIndex }).isZero
  }

  @Test
  fun aRendererReportingErrorOccurredBecomesAPlayerError() {
    // `CurrentTransportStatus = ERROR_OCCURRED` is how a renderer says it could not play what it
    // was given. Swallowing it produces a track that never starts and never fails.
    fake.forceTransportError()
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }

    awaitCondition { onMain { player.playerError } != null }
    assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
  }

  @Test
  fun aRendererThatDisappearsMidStreamEndsTheSessionWithTheLastKnownPosition() {
    // The whole point of the fallback. Spec section 6 says playback stopping is intended; playback
    // *appearing to continue* is not.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)
    fake.advance(42_000L)
    awaitPositionAtLeast(42_000L, timeoutMs = 5_000)

    fake.disappear()

    awaitCondition { sessionStates.any { it is CastSessionState.Lost } }
    val lost = sessionStates.filterIsInstance<CastSessionState.Lost>().first()
    assertThat(lost.deviceName).isEqualTo("Fake Speaker")
    assertThat(lost.mediaId).isEqualTo("track-1")
    // The position is what Task 9 resumes from. Zero here would silently send the listener back to
    // the start of the track -- or, for a book, to the start of the book.
    assertThat(lost.positionMs).isGreaterThanOrEqualTo(42_000L)
    assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
    assertThat(onMain { player.playerError }).isNotNull
  }

  @Test
  fun oneMissedPollDoesNotEndTheSession() {
    // The other direction, and the reason the threshold is three. A single dropped poll on a busy
    // network is ordinary; ending the session on it would make casting unusable on real Wi-Fi.
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    fake.failNextPolls(count = UpnpPlayer.LOST_AFTER_FAILURES - 1)
    Thread.sleep(UpnpPlayer.POLL_INTERVAL_MS * (UpnpPlayer.LOST_AFTER_FAILURES + 1))

    assertThat(sessionStates.filterIsInstance<CastSessionState.Lost>()).isEmpty()
    assertThat(onMain { player.playbackState }).isNotEqualTo(Player.STATE_IDLE)
  }

  @Test
  fun aUpnpErrorIsNotMistakenForADeadSpeaker() {
    // Task 5 keeps `UpnpErrorException` and `SoapTransportException` apart so this branch can. A
    // 714 must not tear down the session; a dead socket must.
    fake.failNextPollsWithUpnpError(count = UpnpPlayer.LOST_AFTER_FAILURES + 2)
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    Thread.sleep(UpnpPlayer.POLL_INTERVAL_MS * (UpnpPlayer.LOST_AFTER_FAILURES + 2))

    assertThat(sessionStates.filterIsInstance<CastSessionState.Lost>()).isEmpty()
  }

  @Test
  fun theProgressWriterAttachesToThisPlayerAndWritesRows() {
    // **The Plan 4 interaction, tested.** Plan 3's ProgressWriter takes a `Player`; this is one.
    // If `UpnpPlayer` did not fire the listener callbacks Media3 derives from state diffs, a book
    // cast to a speaker would silently record nothing -- and that would only be discovered by a
    // user losing their place.
    val dao = inMemoryDatabase().mediaProgressDao()
    val writer = ProgressWriter(player, dao, fixedClock, scope).also { it.start() }

    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)
    fake.advance(30_000L)
    awaitPositionAtLeast(30_000L, timeoutMs = 5_000)
    onMain { player.pause() }
    writer.flushBlocking()

    val row = runBlocking { dao.find("track-1") }
    assertThat(row).isNotNull
    assertThat(row!!.positionMs).isGreaterThanOrEqualTo(30_000L)
    // ...and it is the cast position, not a constant: a second observation after seeking.
    onMain { player.seekTo(90_000L); player.play() }
    awaitPositionAtLeast(90_000L, timeoutMs = 5_000)
    onMain { player.pause() }
    writer.flushBlocking()
    assertThat(runBlocking { dao.find("track-1") }!!.positionMs).isGreaterThanOrEqualTo(90_000L)
  }

  @Test
  fun theSpeedIsAlwaysOneAndSettingAnotherIsRefusedVisibly() {
    // The Plan 4 interaction that cannot be satisfied. AVTransport's `Speed` must be "1"; Sonos
    // answers 717 otherwise. Reporting 1.0 is what makes the limit visible instead of silent.
    assertThat(onMain { player.playbackParameters.speed }).isEqualTo(1.0f)

    onMain { player.setPlaybackSpeed(1.5f) }

    assertThat(onMain { player.playbackParameters.speed }).isEqualTo(1.0f)
    assertThat(onMain { player.availableCommands.contains(Player.COMMAND_SET_SPEED_AND_PITCH) }).isFalse
    // No Play with a speed other than 1 was ever sent.
    assertThat(fake.soapRequests.filter { it.action == "Play" }
      .map { it.arguments.first { arg -> arg.first == "Speed" }.second }.distinct())
      .containsExactly("1")
  }

  @Test
  fun volumeIsReadFromAndWrittenToTheRenderer() {
    onMain { player.volume = 0.5f }
    awaitCondition { runBlocking { upnpRenderer.volume() } == 50 }

    onMain { player.volume = 0.17f }
    awaitCondition { runBlocking { upnpRenderer.volume() } == 17 }
  }

  @Test
  fun releasingStopsTheRendererAndTheProxyStopsServing() {
    onMain { player.setMediaItems(items, 0, 0L); player.prepare(); player.play() }
    awaitState(Player.STATE_READY)

    onMain { player.release() }

    assertThat(fake.currentTransportState()).isEqualTo("STOPPED")
    // Every token revoked: a proxy still serving after a session ends is a capability left on the
    // LAN with nobody watching it.
    assertThat(registry.resolve(publishedPath)).isNull()
  }
```

- [ ] **Step 4: Implement `UpnpPlayer`**

`core/media/src/main/kotlin/app/muplay/media/cast/UpnpPlayer.kt`. The listing below is the
structure and every decision; fill in `SimpleBasePlayer`'s remaining `handle*` overrides against
the resolved 1.11.0 signatures, which `./gradlew :core:media:compileDebugKotlin` will name exactly.

```kotlin
package app.muplay.media.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.muplay.cast.control.TransportState
import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.route.CastRoute
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapTransportException
import app.muplay.cast.soap.UpnpErrorException
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **A UPnP renderer, wearing a Media3 `Player`.**
 *
 * This is the whole design of Plan 6 in one class. Everything Plan 3 built above the player was
 * built against `androidx.media3.common.Player` -- `MuPlayer` is a `ForwardingPlayer`,
 * `ProgressWriter` takes a `Player`, `MediaSession` holds one -- so a cast output that **is** a
 * `Player` needs none of it changed. A book cast to a speaker has its position recorded by exactly
 * the same writer that records it locally, which is why this plan owns no progress path of its own
 * and does not need Plan 4's names.
 *
 * `SimpleBasePlayer` derives every listener callback from the diff between successive [getState]
 * values, so `onIsPlayingChanged`, `onMediaItemTransition`, `onPositionDiscontinuity` and
 * `onPlaybackStateChanged` all fire correctly as long as the state is kept honest. `UpnpPlayerTest`
 * asserts that by attaching a real `ProgressWriter` and reading the rows it wrote.
 *
 * **Polling, not GENA eventing.** `SUBSCRIBE`/`NOTIFY` is the "proper" mechanism and it is not used
 * here for a decisive reason: the `AVTransport:1` `LastChange` event **does not carry playback
 * position** -- `RelTime` and `AbsTime` are excluded from the evented state variable set. An
 * eventing implementation would still poll `GetPositionInfo` for the seek bar, and would
 * additionally owe an HTTP callback endpoint, subscription renewal at ~85% of a granted timeout,
 * and a parser for XML-escaped XML inside an XML document. It buys a faster `PLAYING`/`STOPPED`
 * transition and nothing else.
 *
 * **Speed is always 1.0.** `AVTransport::Play` takes `Speed`, and every renderer this plan targets
 * allows only `"1"` -- Sonos answers `717 Play speed not supported` otherwise. A book's per-item
 * playback speed therefore cannot be delivered to a speaker. Reporting 1.0 and withholding
 * `COMMAND_SET_SPEED_AND_PITCH` is what makes that visible to the UI, rather than a setting that
 * silently does nothing.
 */
class UpnpPlayer(
  private val device: CastDevice,
  private val renderer: UpnpRenderer,
  private val router: CastRouter,
  private val deviceName: String = device.friendlyName,
  private val scope: CoroutineScope,
  private val clock: Clock,
  private val onSessionEnded: (CastSessionState) -> Unit,
) : SimpleBasePlayer(/* applicationLooper = */ android.os.Looper.getMainLooper()) {

  private var queue: List<CastQueueItem> = emptyList()
  private var index: Int = 0
  private var playWhenReady: Boolean = false
  private var transport: TransportState = TransportState.NO_MEDIA
  private var lastKnownPositionMs: Long = 0L
  private var durationMs: Long = 0L
  private var volumePercent: Int = DEFAULT_VOLUME_PERCENT
  private var error: PlaybackException? = null
  private var canSeek: Boolean = true
  private var consecutiveTransportFailures: Int = 0
  private var pollJob: Job? = null

  override fun getState(): State {
    val builder = State.Builder()
      .setAvailableCommands(commands())
      .setPlaybackState(playbackStateFrom(transport))
      .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      .setPlaylist(queue.map { mediaItemData(it) })
      .setCurrentMediaItemIndex(index)
      // Extrapolating, not a fixed value: a 1 Hz poll would make the seek bar tick once a second,
      // and `ProgressWriter`'s own ticker reads `currentPosition`. The supplier advances in real
      // time from the last poll and is reset by the next one.
      .setContentPositionMs(
        if (transport == TransportState.PLAYING) {
          PositionSupplier.getExtrapolating(lastKnownPositionMs, /* playbackSpeed = */ 1f)
        } else {
          PositionSupplier.getConstant(lastKnownPositionMs)
        },
      )
      // Always 1.0 -- see this class's KDoc. Not a placeholder; a protocol limit made visible.
      .setPlaybackParameters(PlaybackParameters(1f))
      .setVolume(volumePercent / 100f)
    error?.let { builder.setPlayerError(it) }
    return builder.build()
  }

  // ---- commands ----------------------------------------------------------------------------

  override fun handleSetMediaItems(
    mediaItems: MutableList<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<*> {
    queue = mediaItems.map { CastQueueItems.forMediaItem(it, router, device) }
    index = if (startIndex == C_INDEX_UNSET) 0 else startIndex
    lastKnownPositionMs = startPositionMs.coerceAtLeast(0L)
    error = null
    invalidateState()
    return dispatch { loadCurrentItem(seekToMs = lastKnownPositionMs) }
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    this.playWhenReady = playWhenReady
    invalidateState()
    return dispatch { if (playWhenReady) renderer.play() else renderer.pause() }
  }

  override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
    val movedTrack = mediaItemIndex != index
    index = mediaItemIndex
    lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    invalidateState()
    return dispatch {
      // A track change means a new URI, not a Seek: spec section 4 already records that a
      // transcoded seek means re-issuing the URI, and a cross-track seek always does.
      if (movedTrack) loadCurrentItem(seekToMs = lastKnownPositionMs) else renderer.seek(lastKnownPositionMs)
    }
  }

  override fun handleStop(): ListenableFuture<*> {
    playWhenReady = false
    transport = TransportState.STOPPED
    invalidateState()
    return dispatch { renderer.stop() }
  }

  override fun handleRelease(): ListenableFuture<*> {
    pollJob?.cancel()
    return dispatch {
      runCatching { renderer.stop() }
      // Every token revoked. A proxy still serving after a session ends is a capability left on
      // the LAN with nobody watching it.
      router.revokeAll()
    }
  }

  override fun handleSetVolume(volume: Float): ListenableFuture<*> {
    volumePercent = (volume * 100).toInt().coerceIn(0, 100)
    invalidateState()
    return dispatch { renderer.setVolume(volumePercent) }
  }

  /**
   * Refused, and refused **visibly**: [getState] reports 1.0 and [commands] withholds
   * `COMMAND_SET_SPEED_AND_PITCH`, so a UI that reads the player's own commands does not offer a
   * speed control at all while cast.
   */
  override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
    Futures.immediateVoidFuture()

  /**
   * Runs [block] on [scope] and returns an **already-complete** future.
   *
   * `SimpleBasePlayer`'s `handle*` methods return a `ListenableFuture` that Media3 waits on before
   * re-reading [getState]. Every handler above updates its own field and calls `invalidateState()`
   * *first*, so the state Media3 reads is already correct and there is nothing to wait for -- which
   * is the optimistic-update pattern `SimpleBasePlayer` is designed around, and what keeps the UI
   * responsive while a SOAP round trip to a speaker is in flight.
   *
   * Written this way rather than with `kotlinx.coroutines.guava.future` so that **no new dependency
   * enters the graph for one function**. `kotlinx-coroutines-guava` is a perfectly good artifact;
   * it is simply not worth a catalogue entry here, and the constraints call dependency minimalism
   * out by name.
   */
  private fun dispatch(block: suspend () -> Unit): ListenableFuture<*> {
    scope.launch { runCatching { block() }.onFailure(::reportFailure) }
    return Futures.immediateVoidFuture()
  }

  /**
   * A command that failed.
   *
   * A `UpnpErrorException` is the speaker saying no -- the format, the seek mode, the group -- and
   * becomes a visible session failure. A `SoapTransportException` is the speaker not answering, and
   * is counted toward [LOST_AFTER_FAILURES] by the same rule the poll uses, so a command failing
   * and a poll failing agree about when a speaker is gone.
   */
  private fun reportFailure(cause: Throwable) {
    when (cause) {
      is UpnpErrorException -> fail(
        PlaybackException(
          "$deviceName refused: ${cause.message}",
          cause,
          PlaybackException.ERROR_CODE_REMOTE_ERROR,
        ),
      )
      is SoapTransportException -> {
        consecutiveTransportFailures += 1
        if (consecutiveTransportFailures >= LOST_AFTER_FAILURES) lose()
      }
      else -> fail(
        PlaybackException(cause.message.orEmpty(), cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
      )
    }
  }

  private fun commands(): Player.Commands = Player.Commands.Builder()
    .addAll(
      Player.COMMAND_PLAY_PAUSE,
      Player.COMMAND_PREPARE,
      Player.COMMAND_STOP,
      Player.COMMAND_SET_MEDIA_ITEM,
      Player.COMMAND_CHANGE_MEDIA_ITEMS,
      Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
      Player.COMMAND_GET_TIMELINE,
      Player.COMMAND_GET_METADATA,
      Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
      Player.COMMAND_SET_VOLUME,
      Player.COMMAND_GET_VOLUME,
      Player.COMMAND_RELEASE,
    )
    // Advertised only when the device's own SCPD declares a time seek mode. A device that cannot
    // seek shows no seek bar -- rather than one that produces a 710 on every drag, which is the
    // "offer a control that silently fails" defect this plan is written against.
    .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, canSeek)
    .addIf(Player.COMMAND_SEEK_BACK, canSeek)
    .addIf(Player.COMMAND_SEEK_FORWARD, canSeek)
    .build()

  // ---- the poll ----------------------------------------------------------------------------

  private suspend fun loadCurrentItem(seekToMs: Long) {
    val item = queue.getOrNull(index) ?: return
    canSeek = renderer.capabilities().preferredSeekMode != null
    renderer.setUri(item.castItem)
    if (playWhenReady) renderer.play()
    // The routing proof (Task 7) runs here, after Play, because that is the only moment at which a
    // renderer that CAN reach the phone will have done so.
    router.confirm(item.route, item.castItem.resourceUrl).let { confirmed ->
      if (confirmed is CastRoute.Unroutable) {
        fail(PlaybackException(confirmed.detail, null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        return
      }
      if (confirmed !== item.route) {
        // Fell back to renderer-direct: re-issue with the new URL.
        renderer.setUri(item.castItem.copy(resourceUrl = (confirmed as CastRoute.RendererDirect).url))
        if (playWhenReady) renderer.play()
      }
    }
    if (seekToMs > 0) renderer.seek(seekToMs)
    startPolling()
  }

  private fun startPolling() {
    pollJob?.cancel()
    pollJob = scope.launch {
      while (true) {
        delay(POLL_INTERVAL_MS)
        poll()
      }
    }
  }

  private suspend fun poll() {
    try {
      val info = renderer.transportInfo()
      val position = renderer.positionInfo()
      consecutiveTransportFailures = 0

      if (info.hasError) {
        // `ERROR_OCCURRED` is how a renderer says it could not play what it was given -- wrong
        // format, or a URL that 404'd. Swallowing it produces a track that never starts and never
        // fails, with nothing reported anywhere.
        fail(PlaybackException("$deviceName could not play this track", null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
        return
      }

      position.positionMs?.let { lastKnownPositionMs = it }
      position.durationMs?.let { durationMs = it }
      val previous = transport
      transport = info.state

      // "Finished" and "the user pressed stop on the speaker" are both STOPPED. Reading them the
      // same way skips a track every time somebody touches the hardware, so the end of a track is
      // STOPPED *and* a position that reached the duration.
      val reachedTheEnd = durationMs > 0 &&
        lastKnownPositionMs >= durationMs - END_OF_TRACK_TOLERANCE_MS
      if (previous == TransportState.PLAYING && transport == TransportState.STOPPED && reachedTheEnd) {
        advance()
      }
      invalidateState()
    } catch (refused: UpnpErrorException) {
      // The speaker answered and said no. Not a dead speaker -- Task 5 keeps these apart precisely
      // so this branch can, and collapsing them would make a 714 tear down the session.
      consecutiveTransportFailures = 0
    } catch (unreachable: SoapTransportException) {
      consecutiveTransportFailures += 1
      // Three, not one: a single dropped poll on a busy Wi-Fi network is ordinary, and ending the
      // session on it would make casting unusable on a real network.
      if (consecutiveTransportFailures >= LOST_AFTER_FAILURES) lose()
    }
  }

  private suspend fun advance() {
    if (index + 1 >= queue.size) {
      transport = TransportState.STOPPED
      playWhenReady = false
      invalidateState()
      return
    }
    index += 1
    lastKnownPositionMs = 0L
    invalidateState()
    loadCurrentItem(seekToMs = 0L)
  }

  private fun fail(exception: PlaybackException) {
    pollJob?.cancel()
    error = exception
    playWhenReady = false
    transport = TransportState.NO_MEDIA
    invalidateState()
    onSessionEnded(CastSessionState.Failed(deviceName, exception.message.orEmpty()))
  }

  private fun lose() {
    pollJob?.cancel()
    error = PlaybackException(
      "$deviceName stopped responding",
      null,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )
    playWhenReady = false
    transport = TransportState.NO_MEDIA
    invalidateState()
    // The last known position travels with the notification. Task 9 resumes locally from it, so a
    // zero here would silently send a listener back to the start of a track -- or of a book.
    onSessionEnded(
      CastSessionState.Lost(deviceName, lastKnownPositionMs, queue.getOrNull(index)?.mediaItem?.mediaId),
    )
  }

  private fun playbackStateFrom(state: TransportState): Int = when (state) {
    TransportState.PLAYING, TransportState.PAUSED -> Player.STATE_READY
    TransportState.TRANSITIONING -> Player.STATE_BUFFERING
    TransportState.STOPPED -> if (queue.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
    TransportState.NO_MEDIA, TransportState.RECORDING, TransportState.UNKNOWN -> Player.STATE_IDLE
  }

  companion object {
    /** 1 Hz. Enough for the seek bar once the position extrapolates between polls. */
    const val POLL_INTERVAL_MS: Long = 1_000L

    /** Three seconds of silence. One missed poll on busy Wi-Fi is ordinary; three is not. */
    const val LOST_AFTER_FAILURES: Int = 3

    /** How close to the declared duration counts as "the track finished". */
    const val END_OF_TRACK_TOLERANCE_MS: Long = 1_500L

    private const val DEFAULT_VOLUME_PERCENT = 30
    private const val C_INDEX_UNSET = androidx.media3.common.C.INDEX_UNSET
  }
}
```

- [ ] **Step 5: Run the device tests**

```bash
./gradlew :core:media:connectedDebugAndroidTest --tests '*UpnpPlayerTest*'
./gradlew :core:media:test --tests '*CastQueueItemTest*'
```

`.github/workflows/e2e.yml` — `:core:media:connectedDebugAndroidTest` is already in the `script:`
line if Plan 3 Task 10 landed. Confirm it is; if not, add it here.

- [ ] **Step 6: Prove each new assertion can fail, record probes, measure, commit**

1. In `getState`, use `PositionSupplier.getConstant(lastKnownPositionMs)` while playing. Expect
   `thePositionExtrapolatesBetweenPolls` to fail and `thePositionAdvancesWhileTheRendererIsPlaying`
   to pass — which is exactly why both exist.
2. In `poll`, drop the `reachedTheEnd` condition. Expect
   `aStopThatIsNotTheEndOfTheTrackDoesNotSkipToTheNextOne` to fail while
   `theTrackEndsAndTheNextOneIsSentToTheRenderer` passes.
3. In `poll`, drop the `info.hasError` branch. Expect `aRendererReportingErrorOccurredBecomesAPlayerError`
   to fail.
4. Set `LOST_AFTER_FAILURES` to 1. Expect `oneMissedPollDoesNotEndTheSession` to fail.
5. In `poll`, catch `Exception` instead of the two specific types. Expect
   `aUpnpErrorIsNotMistakenForADeadSpeaker` to fail.
6. In `lose`, pass `positionMs = 0`. Expect
   `aRendererThatDisappearsMidStreamEndsTheSessionWithTheLastKnownPosition` to fail on its position
   assertion. **This is the mutation that loses a listener's place in a book**, and it is the one to
   record first.
7. In `commands`, add the seek commands unconditionally. Expect
   `aDeviceThatCannotSeekDoesNotAdvertiseTheSeekCommands` to fail.
8. In `handleSetPlaybackParameters`, apply the requested speed to `getState`. Expect
   `theSpeedIsAlwaysOneAndSettingAnotherIsRefusedVisibly` to fail.
9. Make `UpnpPlayer` stop calling `invalidateState()` after a poll. Expect
   `theProgressWriterAttachesToThisPlayerAndWritesRows` to fail — **the Plan 4 interaction, caught
   by the mechanism that makes it work.**

Record 2, 4, 6 and 9 in `ci/mutation-probes.sh` under `player/*` — noting in the entry that these
run on the device, so `run_suite()` cannot execute them and they are recorded as **manual** probes
with the emulator command written out. (Read the script's header: it already says a probe records
an answer rather than generating a question, and a device-only probe is still an answer.)

`build.gradle.kts` — add BRANCH floors for `app.muplay.media.cast.CastQueueItems` (JVM-measurable)
and `requiresInstrumentedData = true` BRANCH floors for `app.muplay.media.cast.UpnpPlayer` and
`app.muplay.media.cast.CastSessionState*`. Measure both tiers before writing the numbers.

```bash
./gradlew :core:media:test jacocoJvmCoverageVerification
git add core/media build.gradle.kts .github/workflows/e2e.yml ci/mutation-probes.sh
git commit -m "feat(cast): a renderer that is a Media3 Player, and a speaker that can be lost"
```

---

## Task 9: Handover — the output switch, the one-shot resume target, and the progress row

**Files:**
- Create: `core/media/src/main/kotlin/app/muplay/media/PlaybackOutputSwitch.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/cast/OneShotResumePolicy.kt`
- Create: `core/media/src/main/kotlin/app/muplay/media/cast/CastSessionManager.kt`
- Test: `core/media/src/test/kotlin/app/muplay/media/cast/OneShotResumePolicyTest.kt`
- Test: `core/media/src/androidTest/kotlin/app/muplay/media/cast/HandoverTest.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt`
- Modify: `core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt`
- Modify: `build.gradle.kts`, `ci/mutation-probes.sh`

**Interfaces:**
- Consumes from **Plan 3**, all by name and all forward references at the time of writing:
  - **`ResumeTarget(startIndex: Int, startPositionMs: Long)`** and
    **`fun interface ResumePolicy { fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget }`**
    — Task 8.
  - **`MuPlayer(player: Player, resumePolicy: ResumePolicy) : ForwardingPlayer`** — Task 8.
  - **`ProgressWriter(player, dao, clock, scope)`** with `start()`,
    `write(mediaId, positionMs, finished)`, `flushBlocking()` — Task 8.
  - `MuPlayerFactory.create(): ExoPlayer` — Task 5. `MuPlaybackService`, `PlaybackConnection` —
    Task 5.
  - `MediaProgressDao` (`:core:database`, committed) — used only through `ProgressWriter`.
- Consumes from this plan: `UpnpPlayer`, `CastSessionState` (Task 8); `UpnpRenderer` (Task 5);
  `CastRouter`, `MediaProxyServer`, `ProxyRegistry` (Tasks 6–7); `CastDevice` (Task 2).
- Produces:
  - `class PlaybackOutputSwitch @Inject constructor()` with `val activePlayer: StateFlow<Player?>`,
    `fun installLocal(player: Player)`, `fun installRemote(player: Player)`, `fun current(): Player?`
  - `class OneShotResumePolicy(private val delegate: ResumePolicy) : ResumePolicy` with
    `fun armFor(mediaId: String, target: ResumeTarget)`
  - `class CastSessionManager @Inject constructor(...)` with
    `val state: StateFlow<CastSessionState>`, `suspend fun castTo(device: CastDevice)`,
    `suspend fun stopCasting()`, and internal `handleSessionEnded(CastSessionState)`

### Plan 4 owns the resume surface. This task decorates it and redesigns nothing.

**Plan 4 has not been written.** Everything below names Plan 3's `ResumePolicy` /
`ResumeTarget` / `ProgressWriter` and adds **one decorator** over the first of them. Four things
are explicitly not fixed, and each is handled by depending on a shape rather than on a value:

1. **What Plan 4 binds as the production `ResumePolicy`.** `OneShotResumePolicy` takes a `delegate`
   and is installed *around* whatever is bound. If Plan 4 introduces a Hilt qualifier, decorate the
   qualified binding. **Do not** decorate `NeverResume` by name.
2. **Whether `ResumePolicy.resolve` keeps its signature.** If Plan 4 widens it — a `LibraryRole`, a
   `mediaType`, a per-item speed — widen `OneShotResumePolicy` identically. It is a decorator; it
   has no signature of its own, and inventing one would fork the interface.
3. **Whether Plan 4 adds columns to `media_progress`.** The handover write goes through
   `ProgressWriter.write`, which Plan 3 Task 8 already requires to be a read-modify-write that
   preserves the columns it does not own. This task adds no second writer, so any column Plan 4
   adds is preserved for free.
4. **Plan 4's per-item speed.** Task 8 already reports 1.0 while cast; Task 10 says so in words.
   Nothing here reads a speed.

**Everything else about resume is untouched.** If implementing this task seems to require changing
`ResumePolicy`'s meaning, writing a second progress path, or adding a column, stop — that is Plan
4's, and the coordination cost of getting it wrong is higher than the cost of waiting.

### The trap the seam creates, and the fix that uses the seam rather than bypassing it

Plan 3's `MuPlayer` overrides **all six** `setMediaItem(s)` overloads to discard the caller's
position and ask `ResumePolicy` instead — *"No code path can set a wrong position."* That is exactly
what makes a book impossible to get wrong.

It is also exactly what makes **handover** impossible by the obvious route. Casting mid-song means
taking the queue and position off the local player and putting them on the remote one; the natural
code is `remote.setMediaItems(items, index, position)` — and `MuPlayer` throws the position away,
because that is its entire job. With Plan 3's `NeverResume` installed, casting mid-song restarts the
track from zero. Every time. And it would look like a Media3 quirk rather than like the seam
working as designed.

The fix is **not** to bypass the seam with an unwrapped player — that would create the one code path
that can set a position, which is the thing the seam exists to prevent, and would leave it lying
around for Plan 5 and Plan 7 to find.

The fix is to feed the seam:

```
1. write the outgoing player's position to `media_progress`   (ProgressWriter.write)
2. arm a one-shot ResumeTarget for that media id              (OneShotResumePolicy.armFor)
3. setMediaItems on the incoming player                       (goes through MuPlayer, as always)
4. the policy answers the armed target once, then delegates forever after
```

Handover and resume become **one mechanism**. And when Plan 4 swaps in a book-aware policy, casting
a book mid-chapter resumes on the right second through the same path, with nothing in this plan
changed.

> Step 1 writes the row **before** arming, and that order is load-bearing: if the process dies
> between the two, the position is already durable and the ordinary resume path finds it. Arming
> first and writing second loses the position on exactly the crash a handover is most likely to
> provoke.

### Switching the player under a live `MediaSession`

`MediaSession.setPlayer(player)` is Media3's supported way to change what a session drives, and it
is what Google's own Cast integration does. `MuPlaybackService` observes
`PlaybackOutputSwitch.activePlayer` and calls it. Three things move with the player:

- **the `ProgressWriter`.** Detached from the outgoing player, attached to the incoming one — it
  takes a `Player`, and both are.
- **the notification.** Comes from the session, so it follows automatically. Nothing to do, and
  worth an assertion because "nothing to do" and "quietly broken" look the same.
- **the `MediaController` in the UI.** Also from the session. Also automatic. Also asserted.

The **local** `ExoPlayer` is **paused, not released**, while casting. Releasing it would mean
rebuilding it — and rebinding the audio focus handling, the cache-backed data source and the
becoming-noisy receiver — on every handover back. Paused, it holds no audio focus and decodes
nothing.

- [ ] **Step 1: Write the failing one-shot policy test**

`core/media/src/test/kotlin/app/muplay/media/cast/OneShotResumePolicyTest.kt` — a pure JVM test,
because `ResumePolicy` takes media ids and an index and touches no Android type. That is Plan 3's
own reason for that signature, and this is the second thing to benefit from it.

```kotlin
package app.muplay.media.cast

import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OneShotResumePolicyTest {

  /**
   * A stand-in for whatever Plan 4 binds. It answers a distinctive target so that "the delegate
   * was consulted" is distinguishable from "a default was returned" -- which a delegate answering
   * `ResumeTarget(requestedIndex, 0)` would not be.
   */
  private val delegate = ResumePolicy { _, requestedIndex -> ResumeTarget(requestedIndex, 99_000L) }

  @Test
  fun `with nothing armed, the delegate's answer is passed straight through`() {
    val policy = OneShotResumePolicy(delegate)

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 1))
      .isEqualTo(ResumeTarget(1, 99_000L))
  }

  @Test
  fun `an armed target wins over the delegate, once`() {
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("b", ResumeTarget(startIndex = 1, startPositionMs = 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(1, 42_000L))
    // ...and then it is gone. A target that stuck would make every later `setMediaItems` -- a
    // shuffle, a new album, the next book -- start at 42 seconds.
    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(0, 99_000L))
  }

  @Test
  fun `a second arming carries a second target`() {
    // Rule 2. Without this, `armFor` could store nothing and return a constant 42_000 and pass the
    // test above.
    val policy = OneShotResumePolicy(delegate)

    policy.armFor("b", ResumeTarget(1, 42_000L))
    assertThat(policy.resolve(listOf("a", "b"), 0)).isEqualTo(ResumeTarget(1, 42_000L))

    policy.armFor("a", ResumeTarget(0, 7_000L))
    assertThat(policy.resolve(listOf("a", "b"), 1)).isEqualTo(ResumeTarget(0, 7_000L))
  }

  @Test
  fun `an armed target for a media id that is not in the new queue is discarded`() {
    // A handover whose queue changed between arming and setting. Applying the target anyway would
    // start an unrelated track 42 seconds in -- which is the silent-wrong-answer class, and the
    // exact failure this whole architecture exists to prevent for books.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("gone", ResumeTarget(0, 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 2))
      .isEqualTo(ResumeTarget(2, 99_000L))
  }

  @Test
  fun `the armed index is corrected to where the media id actually is in the new queue`() {
    // The queue can be reordered by a handover -- a shuffle is regenerated, an album is re-fetched.
    // The media id is the identity; the index is not.
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("c", ResumeTarget(startIndex = 0, startPositionMs = 42_000L))

    assertThat(policy.resolve(listOf("a", "b", "c"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(2, 42_000L))
  }

  @Test
  fun `disarming leaves the delegate in charge`() {
    val policy = OneShotResumePolicy(delegate)
    policy.armFor("a", ResumeTarget(0, 42_000L))

    policy.disarm()

    assertThat(policy.resolve(listOf("a"), 0)).isEqualTo(ResumeTarget(0, 99_000L))
  }

  @Test
  fun `the delegate is consulted with the arguments it was given`() {
    // The decorator must not rewrite what it forwards. Two observations, because a decorator that
    // passed a constant index would satisfy one of them.
    val seen = mutableListOf<Pair<List<String>, Int>>()
    val recording = ResumePolicy { ids, index -> seen += ids to index; ResumeTarget(index, 0L) }

    OneShotResumePolicy(recording).resolve(listOf("a", "b"), 1)
    OneShotResumePolicy(recording).resolve(listOf("x"), 0)

    assertThat(seen).containsExactly(listOf("a", "b") to 1, listOf("x") to 0)
  }
}
```

- [ ] **Step 2: Implement the policy and the switch**

`core/media/src/main/kotlin/app/muplay/media/cast/OneShotResumePolicy.kt`:

```kotlin
package app.muplay.media.cast

import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import java.util.concurrent.atomic.AtomicReference

/**
 * **The handover's position, carried through the seam rather than around it.**
 *
 * Plan 3's `MuPlayer` overrides all six `setMediaItem(s)` overloads to discard the caller's
 * position and ask a [ResumePolicy] instead, so that *"no code path can set a wrong position"*.
 * That is what makes a book impossible to get wrong -- and it is also what makes handover
 * impossible by the obvious route: `remote.setMediaItems(items, index, positionMs)` has its
 * position thrown away, so casting mid-song restarts the track.
 *
 * The wrong fix is an unwrapped player for handovers, which would create the one code path that
 * can set a position and leave it lying around for the next plan to find. The right fix is to
 * **feed the seam**: write the outgoing position to `media_progress`, arm a one-shot target here,
 * then call `setMediaItems` as usual. Handover and resume become one mechanism, and when the
 * audiobook plan swaps in a book-aware [delegate] nothing here changes.
 *
 * A **decorator**, deliberately: it wraps whatever policy is bound rather than replacing it, and it
 * has no signature of its own. If the audiobook plan widens [ResumePolicy.resolve], widen this
 * identically -- do not fork the interface.
 */
class OneShotResumePolicy(private val delegate: ResumePolicy) : ResumePolicy {

  private data class Armed(val mediaId: String, val target: ResumeTarget)

  private val armed = AtomicReference<Armed?>(null)

  /** Arms one target for [mediaId], consumed by the next [resolve] that sees that id. */
  fun armFor(mediaId: String, target: ResumeTarget) {
    armed.set(Armed(mediaId, target))
  }

  fun disarm() {
    armed.set(null)
  }

  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
    // `getAndSet(null)` whatever happens: a target that survived one resolve would make the next
    // shuffle, the next album and the next book all start 42 seconds in.
    val pending = armed.getAndSet(null)
      ?: return delegate.resolve(mediaIds, requestedIndex)

    // The **media id** is the identity; the index is not. A handover can re-fetch an album or
    // regenerate a shuffle, so the armed index may name a different track in the new queue.
    val index = mediaIds.indexOf(pending.mediaId)
    // Not in the new queue at all: discard rather than apply. Starting an unrelated track 42
    // seconds in is the silent-wrong-answer class -- the exact failure this architecture exists to
    // prevent for books.
    if (index < 0) return delegate.resolve(mediaIds, requestedIndex)

    return ResumeTarget(startIndex = index, startPositionMs = pending.target.startPositionMs)
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/PlaybackOutputSwitch.kt`:

```kotlin
package app.muplay.media

import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which `Player` the media session is currently driving.
 *
 * The seam that lets `:core:cast` exist without `:core:media` knowing anything about UPnP:
 * `MuPlaybackService` observes this and calls `MediaSession.setPlayer`, and the cast layer installs
 * a remote player through it. `:core:media` depends on `:core:cast` for the protocol, but the
 * *service* depends only on `Player`.
 *
 * The local player is **paused, not released**, while a remote one is installed. Releasing it would
 * mean rebuilding it -- and rebinding audio focus, the cache-backed data source and the
 * becoming-noisy receiver -- on every handover back. Paused, it holds no audio focus and decodes
 * nothing.
 */
@Singleton
class PlaybackOutputSwitch @Inject constructor() {

  private val _activePlayer = MutableStateFlow<Player?>(null)
  val activePlayer: StateFlow<Player?> = _activePlayer.asStateFlow()

  private var local: Player? = null

  fun installLocal(player: Player) {
    local = player
    _activePlayer.value = player
  }

  fun installRemote(player: Player) {
    local?.let { runCatching { it.pause() } }
    _activePlayer.value = player
  }

  /** Back to the phone. The local player is still there; it was only paused. */
  fun returnToLocal() {
    local?.let { _activePlayer.value = it }
  }

  fun current(): Player? = _activePlayer.value
}
```

- [ ] **Step 3: Write the failing handover test**

`core/media/src/androidTest/kotlin/app/muplay/media/cast/HandoverTest.kt`, on the device, with a
real `ExoPlayer`, a real `FakeRenderer`, a real in-memory Room and a real `MediaSession`.

```kotlin
  @Test
  fun castingMidSongLandsOnTheSameSecondOnTheSpeaker() {
    // The headline behaviour. Not "the queue transferred" -- the *position* transferred, which is
    // the thing Plan 3's seam would otherwise discard.
    startLocalPlayback(items, startIndex = 1)
    awaitLocalPositionAtLeast(20_000L)
    val handoffFrom = onMain { localPlayer.currentPosition }

    runBlocking { sessionManager.castTo(device) }

    awaitCondition { switch.current() is UpnpPlayer }
    // The renderer received a Seek to roughly where the phone was -- read off its own bytes.
    val seek = fake.soapRequests.last { it.action == "Seek" }
    val target = UpnpTime.parseClock(seek.arguments.last().second)!!
    assertThat(target).isBetween(handoffFrom - 2_000L, handoffFrom + 2_000L)
    // ...and the same track, by media id rather than by index.
    assertThat(onMain { switch.current()!!.currentMediaItem!!.mediaId }).isEqualTo("track-2")
  }

  @Test
  fun aSecondHandoverCarriesADifferentPosition() {
    // Rule 2 applied to the headline behaviour: one observation of "the position transferred" is
    // satisfied by a constant seek target.
    startLocalPlayback(items, startIndex = 0)
    awaitLocalPositionAtLeast(5_000L)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }
    val firstTarget = UpnpTime.parseClock(fake.soapRequests.last { it.action == "Seek" }.arguments.last().second)!!

    runBlocking { sessionManager.stopCasting() }
    awaitLocalPositionAtLeast(firstTarget + 20_000L)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }
    val secondTarget = UpnpTime.parseClock(fake.soapRequests.last { it.action == "Seek" }.arguments.last().second)!!

    assertThat(secondTarget).isGreaterThan(firstTarget + 15_000L)
  }

  @Test
  fun theHandoverWritesTheProgressRowBeforeItArmsTheTarget() {
    // The ordering that survives a crash mid-handover. Asserted by reading the row: if the write
    // happened, the ordinary resume path can find the position even if nothing else worked.
    startLocalPlayback(items, startIndex = 0)
    awaitLocalPositionAtLeast(15_000L)

    runBlocking { sessionManager.castTo(device) }

    val row = runBlocking { dao.find("track-1") }
    assertThat(row).isNotNull
    assertThat(row!!.positionMs).isGreaterThanOrEqualTo(15_000L)
  }

  @Test
  fun theHandoverPreservesTheColumnsItDoesNotOwn() {
    // **The Plan 4 interaction.** `speed`, `skipSilence` and `gainDb` belong to the audiobook plan.
    // A handover that constructed a fresh entity would reset a listener's per-book speed every time
    // they cast -- silently, and only for the feature this project exists to get right.
    runBlocking {
      dao.upsert(MediaProgressEntity("track-1", 0L, false, 1L, speed = 1.4f, skipSilence = true, gainDb = 3.5f))
    }
    startLocalPlayback(items, startIndex = 0)
    awaitLocalPositionAtLeast(10_000L)

    runBlocking { sessionManager.castTo(device) }

    val row = runBlocking { dao.find("track-1") }!!
    assertThat(row.speed).isEqualTo(1.4f)
    assertThat(row.skipSilence).isTrue
    assertThat(row.gainDb).isEqualTo(3.5f)
    assertThat(row.positionMs).isGreaterThanOrEqualTo(10_000L)
  }

  @Test
  fun theOneShotTargetIsSpentAndDoesNotLeakIntoTheNextQueue() {
    // Casting, then choosing a different album, must start that album at zero -- not 20 seconds in.
    startLocalPlayback(items, startIndex = 0)
    awaitLocalPositionAtLeast(20_000L)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }

    onMain { switch.current()!!.setMediaItems(otherItems, 0, 0L) }

    assertThat(onMain { switch.current()!!.currentPosition }).isLessThan(2_000L)
  }

  @Test
  fun comingBackFromCastLandsOnTheSameSecondLocally() {
    // The other direction, which is the one a user hits when they leave the house.
    startLocalPlayback(items, startIndex = 0)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }
    fake.advance(45_000L)
    awaitRemotePositionAtLeast(45_000L)

    runBlocking { sessionManager.stopCasting() }

    awaitCondition { switch.current() === localPlayer }
    assertThat(onMain { localPlayer.currentPosition }).isBetween(43_000L, 50_000L)
    // ...and the speaker was told to stop, rather than left playing into an empty room.
    assertThat(fake.currentTransportState()).isEqualTo("STOPPED")
  }

  @Test
  fun aSpeakerThatDisappearsHandsPlaybackBackAtTheRightSecond() {
    // Spec section 6: "Playback stopping when the phone leaves the network is intended behaviour."
    // Losing the listener's place is not.
    startLocalPlayback(items, startIndex = 0)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }
    fake.advance(38_000L)
    awaitRemotePositionAtLeast(38_000L)

    fake.disappear()

    awaitCondition { switch.current() === localPlayer }
    assertThat(sessionManager.state.value).isInstanceOf(CastSessionState.Lost::class.java)
    assertThat(onMain { localPlayer.currentPosition }).isBetween(36_000L, 43_000L)
    // Paused, not playing: a speaker vanishing must not start audio out of the phone's own
    // loudspeaker in someone's pocket.
    assertThat(onMain { localPlayer.isPlaying }).isFalse
  }

  @Test
  fun theSessionFollowsTheSwitchAndTheNotificationTitleTracksTheRemoteTrack() {
    // The session, the notification and the MediaController come along for free -- and "for free"
    // and "quietly broken" look identical without an assertion.
    startLocalPlayback(items, startIndex = 0)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }

    awaitCondition { controller.currentMediaItem?.mediaId == "track-1" }
    assertThat(onMain { controller.currentMediaItem!!.mediaMetadata.title }).isEqualTo("Track 1")
    val posted = notificationManager.activeNotifications
      .single { it.id == PlaybackNotification.NOTIFICATION_ID }
    assertThat(posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE)).isEqualTo("Track 1")
  }

  @Test
  fun theProgressWriterFollowsTheSwitchAndKeepsWritingWhileCast() {
    // One writer, two players. If the writer stayed attached to the paused local player, a book
    // cast to a speaker would record nothing at all -- and nobody would find out until they lost
    // their place.
    startLocalPlayback(items, startIndex = 0)
    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }
    fake.advance(50_000L)
    awaitRemotePositionAtLeast(50_000L)
    onMain { switch.current()!!.pause() }
    writer.flushBlocking()

    assertThat(runBlocking { dao.find("track-1") }!!.positionMs).isGreaterThanOrEqualTo(50_000L)
  }

  @Test
  fun theLocalPlayerIsPausedRatherThanReleasedWhileCasting() {
    startLocalPlayback(items, startIndex = 0)

    runBlocking { sessionManager.castTo(device) }
    awaitCondition { switch.current() is UpnpPlayer }

    assertThat(onMain { localPlayer.isPlaying }).isFalse
    // Still usable: `playbackState` on a released ExoPlayer throws, and the handover-back test
    // above would fail obscurely rather than here.
    assertThat(onMain { localPlayer.playbackState }).isNotEqualTo(Player.STATE_IDLE)
  }

  @Test
  fun aRendererThatRefusesTheFormatFailsTheSessionInsteadOfGoingQuiet() {
    // The renderer says 714. The session must end visibly and playback must come back to the
    // phone, rather than leaving a UI that says "Playing on Fake Speaker" over silence.
    val fussy = renderer(FakeRenderer.Strictness(rejectedMimeTypes = setOf("audio/mpeg")))
    startLocalPlayback(items, startIndex = 0)

    runBlocking { sessionManager.castTo(fussyDevice) }

    awaitCondition { sessionManager.state.value is CastSessionState.Failed }
    assertThat((sessionManager.state.value as CastSessionState.Failed).reason).contains("714")
    awaitCondition { switch.current() === localPlayer }
  }
```

- [ ] **Step 4: Implement `CastSessionManager` and wire the service**

`core/media/src/main/kotlin/app/muplay/media/cast/CastSessionManager.kt`:

```kotlin
package app.muplay.media.cast

import androidx.media3.common.Player
import app.muplay.cast.control.UpnpRenderer
import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.http.CastHttpClient
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.route.CastRouter
import app.muplay.cast.soap.SoapClient
import app.muplay.media.MuPlayer
import app.muplay.media.PlaybackOutputSwitch
import app.muplay.media.ProgressWriter
import app.muplay.media.ResumeTarget
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts and ends a cast session, and moves playback between the phone and a speaker without
 * losing the listener's place.
 *
 * The handover is four steps and their **order is load-bearing**:
 *
 * 1. write the outgoing player's position to `media_progress`;
 * 2. arm a one-shot [ResumeTarget] for that media id;
 * 3. `setMediaItems` on the incoming player -- through `MuPlayer`, like every other caller;
 * 4. the policy answers the armed target once, then delegates forever after.
 *
 * Step 1 comes before step 2 so that a process death mid-handover leaves the position **durable**:
 * the ordinary resume path finds it. Arming first and writing second loses it on exactly the crash
 * a handover is most likely to provoke.
 *
 * This class writes no progress of its own and defines no resume mechanism of its own. Both belong
 * to the audiobook plan; this decorates them.
 */
@Singleton
class CastSessionManager @Inject constructor(
  private val switch: PlaybackOutputSwitch,
  private val oneShot: OneShotResumePolicy,
  private val progressWriter: ProgressWriter,
  private val proxy: MediaProxyServer,
  private val registry: ProxyRegistry,
  private val router: CastRouter,
  private val http: CastHttpClient,
  private val soap: SoapClient,
  private val clock: Clock,
  private val scope: CoroutineScope,
) {

  private val _state = MutableStateFlow<CastSessionState>(CastSessionState.Idle)
  val state: StateFlow<CastSessionState> = _state.asStateFlow()

  suspend fun castTo(device: CastDevice) {
    _state.value = CastSessionState.Connecting(device.friendlyName)
    val outgoing = switch.current() ?: return
    val handover = snapshot(outgoing)

    val remote = MuPlayer(
      UpnpPlayer(
        device = device,
        renderer = UpnpRenderer(device, soap, http),
        router = router,
        deviceName = device.friendlyName,
        scope = scope,
        clock = clock,
        onSessionEnded = ::handleSessionEnded,
      ),
      oneShot,
    )

    // 1 then 2 -- see this class's KDoc.
    handover?.let { (mediaId, positionMs, _) ->
      progressWriter.write(mediaId, positionMs, finished = false)
      oneShot.armFor(mediaId, ResumeTarget(startIndex = handover.index, startPositionMs = positionMs))
    }

    switch.installRemote(remote)
    progressWriter.attach(remote)
    // 3: an ordinary `setMediaItems`. The seam is fed, not bypassed.
    remote.setMediaItems(handover?.items.orEmpty(), handover?.index ?: 0, 0L)
    remote.prepare()
    if (handover?.wasPlaying == true) remote.play()
    _state.value = CastSessionState.Playing(device.friendlyName)
  }

  suspend fun stopCasting() {
    val remote = switch.current() ?: return
    handBackTo(remote, resumePlaying = remote.isPlaying)
    _state.value = CastSessionState.Idle
  }

  /**
   * Called by [UpnpPlayer] when the speaker failed or vanished.
   *
   * Playback comes back to the phone **paused**. Spec section 6 says stopping is intended
   * behaviour; a speaker vanishing must not start audio out of a phone's own loudspeaker in
   * somebody's pocket.
   */
  internal fun handleSessionEnded(ended: CastSessionState) {
    _state.value = ended
    val remote = switch.current() ?: return
    handBackTo(remote, resumePlaying = false)
  }

  private fun handBackTo(remote: Player, resumePlaying: Boolean) {
    val handover = snapshot(remote)
    handover?.let { (mediaId, positionMs, _) ->
      // The same two steps in the same order, in the other direction.
      scope.launch { progressWriter.write(mediaId, positionMs, finished = false) }
      oneShot.armFor(mediaId, ResumeTarget(handover.index, positionMs))
    }
    runCatching { remote.release() }
    registry.revokeAll()
    switch.returnToLocal()
    val local = switch.current() ?: return
    progressWriter.attach(local)
    local.setMediaItems(handover?.items.orEmpty(), handover?.index ?: 0, 0L)
    local.prepare()
    if (resumePlaying) local.play()
  }

  private data class Handover(
    val mediaId: String,
    val positionMs: Long,
    val index: Int,
    val items: List<androidx.media3.common.MediaItem>,
    val wasPlaying: Boolean,
  )

  private fun snapshot(player: Player): Handover? {
    val mediaId = player.currentMediaItem?.mediaId ?: return null
    return Handover(
      mediaId = mediaId,
      positionMs = player.currentPosition.coerceAtLeast(0L),
      index = player.currentMediaItemIndex,
      items = (0 until player.mediaItemCount).map(player::getMediaItemAt),
      wasPlaying = player.isPlaying,
    )
  }
}
```

`core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` — in `onCreate`, after the local
player and session are built:

```kotlin
    outputSwitch.installLocal(muPlayer)
    // The session follows the switch. `MediaSession.setPlayer` is Media3's supported way to change
    // what a session drives, and it is what Google's own Cast integration does -- the notification,
    // the media buttons, the lock screen and every `MediaController` come along without any of them
    // being told.
    lifecycleScope.launch {
      outputSwitch.activePlayer.filterNotNull().collect { player -> mediaSession.player = player }
    }
```

`ProgressWriter` needs an `attach(player: Player)` that detaches from the previous one. **That is a
one-method addition to Plan 3's class**, not a redesign: Plan 3 constructs it with a player and this
adds the ability to move it. If Plan 3's `ProgressWriter` landed without a way to detach its
listener, add one and say so in the task report.

- [ ] **Step 5: Run, mutate, measure, commit**

```bash
./gradlew :core:media:test --tests '*OneShotResumePolicyTest*'
./gradlew :core:media:connectedDebugAndroidTest --tests '*HandoverTest*'
```

Mutations:

1. In `CastSessionManager.castTo`, skip `oneShot.armFor`. Expect
   `castingMidSongLandsOnTheSameSecondOnTheSpeaker` and `aSecondHandoverCarriesADifferentPosition`
   to fail — **the defect Plan 3's seam creates**, caught.
2. In `OneShotResumePolicy.resolve`, use `peek` instead of `getAndSet(null)`. Expect
   `an armed target wins over the delegate, once` and
   `theOneShotTargetIsSpentAndDoesNotLeakIntoTheNextQueue` to fail.
3. In `OneShotResumePolicy.resolve`, apply the armed target even when the media id is absent.
   Expect `an armed target for a media id that is not in the new queue is discarded` to fail.
4. In `OneShotResumePolicy.resolve`, use `pending.target.startIndex` rather than the looked-up
   index. Expect `the armed index is corrected to where the media id actually is` to fail.
5. In `CastSessionManager.castTo`, arm before writing. **No test fails.** That is expected and it is
   why the ordering is stated in the KDoc and in this plan rather than only asserted — record in the
   task report that the ordering is a durability property no in-process test can observe, and that
   `theHandoverWritesTheProgressRowBeforeItArmsTheTarget` asserts the write happens at all, which is
   the observable half.
6. In `CastSessionManager.handleSessionEnded`, pass `resumePlaying = true`. Expect
   `aSpeakerThatDisappearsHandsPlaybackBackAtTheRightSecond` to fail on its `isPlaying` assertion.
7. In `CastSessionManager`, construct a fresh `MediaProgressEntity` instead of calling
   `progressWriter.write`. Expect `theHandoverPreservesTheColumnsItDoesNotOwn` to fail — **the Plan
   4 interaction**, caught by the one test that is about it.
8. In `PlaybackOutputSwitch.installRemote`, release the local player instead of pausing it. Expect
   `theLocalPlayerIsPausedRatherThanReleasedWhileCasting` and both handover-back tests to fail.
9. In `CastSessionManager`, do not call `progressWriter.attach(remote)`. Expect
   `theProgressWriterFollowsTheSwitchAndKeepsWritingWhileCast` to fail.

Record 1, 2, 3, 7 and 9 in `ci/mutation-probes.sh` — 2 and 3 as ordinary JVM probes, 1, 7 and 9 as
device-only manual probes with the emulator command written out.

Add `"app.muplay.media.cast.OneShotResumePolicy"` (BRANCH, JVM) and
`"app.muplay.media.PlaybackOutputSwitch"`, `"app.muplay.media.cast.CastSessionManager"` (BRANCH,
`requiresInstrumentedData = true`) to `:core:media`'s floors. Measure both tiers.

```bash
git add core/media build.gradle.kts ci/mutation-probes.sh
git commit -m "feat(cast): hand playback between phone and speaker without losing the second"
```

---

## Task 10: `:feature:castpicker` — the device list, the cast button, and volume

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/castpicker/build.gradle.kts`
- Create: `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastUiState.kt`
- Create: `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastViewModel.kt`
- Create: `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastPickerSheet.kt`
- Create: `feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastButton.kt`
- Test: `feature/castpicker/src/test/kotlin/app/muplay/castpicker/CastUiStateTest.kt`
- Test: `feature/castpicker/src/test/kotlin/app/muplay/castpicker/CastViewModelTest.kt`
- Modify: `feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt`
- Modify: `app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt`
- Modify: `app/build.gradle.kts`, `build.gradle.kts`, `.github/workflows/e2e.yml`

**Interfaces:**
- Consumes: `RendererDirectory.discover`, `DiscoveryResult`, `CastDevice`, `RememberedRenderer`
  (Task 2); `CastSessionManager.state` / `.castTo` / `.stopCasting`, `CastSessionState` (Tasks 8–9);
  `UpnpRenderer.setVolume` through the session's `Player.setVolume` (Task 8);
  **Plan 3 Task 9's** `PlayerScreen`, `PlayerViewModel`, `PlayerUiState`, and its label constants.
- Produces:
  - `sealed interface CastUiState` with `data object Hidden`, `data object Searching`,
    `data class Devices(val devices: List<CastDeviceRow>, val unreachable: List<String>, val connectedUdn: String?)`,
    `data class Failed(val deviceName: String, val message: String)`
  - `data class CastDeviceRow(val udn: String, val name: String, val subtitle: String?, val isSonos: Boolean, val isConnected: Boolean)`
  - `internal fun castUiState(discovery: DiscoveryResult?, session: CastSessionState): CastUiState`
  - `internal fun castFailureMessage(session: CastSessionState): String?`
  - `@HiltViewModel class CastViewModel` with `uiState: StateFlow<CastUiState>`, `fun open()`,
    `fun close()`, `fun refresh()`, `fun select(udn: String)`, `fun disconnect()`,
    `fun setVolume(fraction: Float)`
  - `CastPickerSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, viewModel: CastViewModel = hiltViewModel())`
  - `CastButton(onClick: () -> Unit, modifier: Modifier = Modifier, viewModel: CastViewModel = hiltViewModel())`
  - label constants `CAST_BUTTON_LABEL`, `CAST_SEARCHING_LABEL`, `CAST_NO_DEVICES_LABEL`,
    `CAST_DISCONNECT_LABEL`, `CAST_UNREACHABLE_SUFFIX`, `CAST_SPEED_LIMIT_NOTICE`,
    `CAST_GROUPED_NOTICE`
- **Plan 4 interaction:** `CAST_SPEED_LIMIT_NOTICE` states that a speaker plays at normal speed.
  **If Plan 4 has landed and exposes a stored per-item speed, name the number in the copy**
  (*"Kitchen plays at normal speed — your 1.4× setting applies on the phone"*). **Plan 4's accessor
  name is not fixed**, so if it does not exist yet, ship the general wording and record in the task
  report that it was left general deliberately. Do not invent an accessor.

### The picker's job is to make three failures legible

Anyone can list devices. What earns this module its place is that it renders the three ways casting
fails, each of which is otherwise silent:

| Failure | Where it comes from | What the user sees |
|---|---|---|
| the speaker cannot reach the phone | `CastSessionState.Failed` from `CastRoute.Unroutable` (Task 7) | *"Kitchen could not reach this phone — it is probably on a different network."* |
| the speaker is grouped with another | `RendererFollowsAnotherException` (Task 5) | *"Kitchen is grouped with another speaker. Ungroup it in the Sonos app to cast to it."* |
| the speaker vanished mid-track | `CastSessionState.Lost` (Task 8) | *"Kitchen stopped responding. Playback moved back to this phone."* |

Plus a fourth that is not a failure and still needs saying: **a speaker plays at normal speed**
(Task 5, `Speed = "1"`). A per-item speed that is silently not applied is a setting the user
believes is on.

### Discovery runs while the sheet is open, and not otherwise

An SSDP search is three multicast datagrams and a three-second listen window. Running it
continuously would be rude to the network and pointless — the picker is the only thing that reads
it. `open()` starts a search, `refresh()` starts another, `close()` stops. Nothing polls in the
background, and `CastViewModel`'s test asserts that a closed picker issues no search.

### Coverage: LINE for the Composables, BRANCH for the rest

Per the constraints. `castUiState` and `castFailureMessage` are `internal` pure functions
specifically so the mapping — which is where the field-level defects live — is gated by a **BRANCH**
floor in Tier 1, while the `@Composable` files take a **LINE** floor measured on the device. That is
the same split Plan 2 used for `SetupScreenKt` and it is the reason the mapping is a function rather
than a `when` inside the composable.

- [ ] **Step 1: Add the module**

`settings.gradle.kts` — `include(":feature:castpicker")` at column 0.

`feature/castpicker/build.gradle.kts`:

```kotlin
plugins {
  id("muplay.android.library")
  id("muplay.android.compose")
  id("muplay.android.hilt")
}

android {
  namespace = "app.muplay.castpicker"
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:cast"))
  // For `CastSessionManager` and `CastSessionState`. This module gets no `Player` and no
  // `ExoPlayer`: `media3-exoplayer` is `implementation` in `:core:media` precisely so that a
  // feature cannot reach it.
  implementation(project(":core:media"))
  implementation(project(":core:designsystem"))

  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.coroutines.core)

  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
}
```

`build.gradle.kts` — a `":feature:castpicker" to listOf(` entry with a BRANCH floor over
`app.muplay.castpicker.CastUiStateKt`, `CastUiState`, `CastUiState*`, `CastDeviceRow` and
`CastViewModel`, and a LINE floor with `requiresInstrumentedData = true` over
`app.muplay.castpicker.CastPickerSheetKt` and `CastButtonKt`. Numbers measured in Step 6.

- [ ] **Step 2: Write the failing state-mapping test**

`feature/castpicker/src/test/kotlin/app/muplay/castpicker/CastUiStateTest.kt`:

```kotlin
package app.muplay.castpicker

import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.discovery.RememberedRenderer
import app.muplay.media.cast.CastSessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The mapping from what the cast layer knows to what a user sees.
 *
 * A pure function so this -- the part where a field silently becomes a constant -- is gated by a
 * BRANCH floor in Tier 1, while the Composables take a LINE floor on the device. Same split as
 * `SetupScreenKt`, same reason.
 */
class CastUiStateTest {

  private fun discovery(vararg devices: CastDevice, unreachable: List<RememberedRenderer> = emptyList()) =
    DiscoveryResult(devices.toList(), unreachable)

  @Test
  fun `no discovery yet is Searching`() {
    assertThat(castUiState(discovery = null, session = CastSessionState.Idle))
      .isEqualTo(CastUiState.Searching)
  }

  @Test
  fun `every field of every row comes from the device it describes`() {
    // Three devices, every field observed at more than one value across the list.
    val state = castUiState(
      discovery(sonos("uuid:a", "Küche", "Sonos One"), generic("uuid:b", "Study Amp", "WXA-50")),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.udn }).containsExactly("uuid:a", "uuid:b")
    assertThat(state.devices.map { it.name }).containsExactly("Küche", "Study Amp")
    assertThat(state.devices.map { it.subtitle }).containsExactly("Sonos One", "WXA-50")
    assertThat(state.devices.map { it.isSonos }).containsExactly(true, false)
  }

  @Test
  fun `the order of the rows is the order discovery produced`() {
    // Discovery already sorted them (Task 2). This mapping must not re-sort or reverse them, and
    // `containsExactlyInAnyOrder` here would hide it if it did.
    val state = castUiState(
      discovery(generic("uuid:a", "Aardvark", "X"), generic("uuid:m", "Mongoose", "X"), generic("uuid:z", "Zebra", "X")),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.name }).containsExactly("Aardvark", "Mongoose", "Zebra")
  }

  @Test
  fun `an empty network is Devices with an empty list, not Searching`() {
    // Different facts: "still looking" and "looked, found nothing" need different copy, and
    // collapsing them leaves a spinner running forever over an empty room.
    val state = castUiState(discovery(), CastSessionState.Idle)

    assertThat(state).isInstanceOf(CastUiState.Devices::class.java)
    assertThat((state as CastUiState.Devices).devices).isEmpty()
  }

  @Test
  fun `a remembered device that did not answer is listed as unreachable, by name`() {
    // Task 2 surfaces these rather than dropping them, and this is where that becomes visible.
    val state = castUiState(
      discovery(generic("uuid:a", "Study Amp", "X"), unreachable = listOf(RememberedRenderer("uuid:z", "Bedroom", "http://x/"))),
      CastSessionState.Idle,
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.name }).containsExactly("Study Amp")
    assertThat(state.unreachable).containsExactly("Bedroom")
  }

  @Test
  fun `the connected device is marked, and only that one`() {
    // Two observations of one boolean across three rows, so `isConnected` cannot be a constant.
    val state = castUiState(
      discovery(generic("uuid:a", "A", "X"), generic("uuid:b", "B", "X"), generic("uuid:c", "C", "X")),
      CastSessionState.Playing("B"),
      connectedUdn = "uuid:b",
    ) as CastUiState.Devices

    assertThat(state.devices.map { it.isConnected }).containsExactly(false, true, false)
    assertThat(state.connectedUdn).isEqualTo("uuid:b")
  }

  @Test
  fun `a failed session becomes Failed with the device's name and the reason`() {
    val state = castUiState(discovery(), CastSessionState.Failed("Küche", "the speaker did not fetch anything"))

    assertThat(state).isEqualTo(CastUiState.Failed("Küche", "the speaker did not fetch anything"))
  }

  @Test
  fun `a second failure carries a second message`() {
    // Rule 2 on the field a user actually reads.
    assertThat(castUiState(discovery(), CastSessionState.Failed("Study", "UPnP error 714 (Illegal MIME-type)")))
      .isEqualTo(CastUiState.Failed("Study", "UPnP error 714 (Illegal MIME-type)"))
  }

  @Test
  fun `each of the three failures gets its own sentence, and they are different sentences`() {
    // The whole point of this module. `castFailureMessage` returning one generic string would pass
    // any `isNotNull` assertion and tell a user nothing.
    val messages = listOf(
      CastSessionState.Failed("Küche", "the speaker did not fetch anything from this phone within 6 seconds."),
      CastSessionState.Failed("Küche", "this speaker is grouped with another and is following x-rincon:RINCON_X."),
      CastSessionState.Lost("Küche", 42_000L, "track-1"),
    ).map(::castFailureMessage)

    assertThat(messages).doesNotContainNull()
    assertThat(messages.distinct()).hasSize(3)
    assertThat(messages[0]).contains("Küche").contains("different network")
    assertThat(messages[1]).contains("Küche").contains("Ungroup")
    assertThat(messages[2]).contains("Küche").contains("moved back to this phone")
  }

  @Test
  fun `an idle or playing session has no failure message`() {
    // The other direction, so `castFailureMessage` cannot be a constant string.
    assertThat(castFailureMessage(CastSessionState.Idle)).isNull()
    assertThat(castFailureMessage(CastSessionState.Playing("Küche"))).isNull()
    assertThat(castFailureMessage(CastSessionState.Connecting("Küche"))).isNull()
  }

  @Test
  fun `the speed notice says a speaker plays at normal speed`() {
    // Task 5: AVTransport's `Speed` must be "1". A per-item speed that is silently not applied is
    // a setting the user believes is on. If the audiobook plan has landed a stored-speed accessor,
    // name the number here; until then this states the rule.
    assertThat(CAST_SPEED_LIMIT_NOTICE).contains("normal speed")
  }
}
```

- [ ] **Step 3: Implement the state and the mapping**

`feature/castpicker/src/main/kotlin/app/muplay/castpicker/CastUiState.kt`:

```kotlin
package app.muplay.castpicker

import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.media.cast.CastSessionState

/** One row in the picker. */
data class CastDeviceRow(
  val udn: String,
  val name: String,
  /** The model, when the device reported one. Two identical speakers differ only by their names. */
  val subtitle: String?,
  val isSonos: Boolean,
  val isConnected: Boolean,
)

sealed interface CastUiState {
  data object Hidden : CastUiState
  /** Looking. Distinct from "looked and found nothing" -- see [Devices]. */
  data object Searching : CastUiState
  data class Devices(
    val devices: List<CastDeviceRow>,
    /** Remembered speakers that did not answer. Named, because a name is something a user can act on. */
    val unreachable: List<String>,
    val connectedUdn: String?,
  ) : CastUiState
  data class Failed(val deviceName: String, val message: String) : CastUiState
}

internal const val CAST_BUTTON_LABEL = "Cast"
internal const val CAST_SEARCHING_LABEL = "Looking for speakers…"
internal const val CAST_NO_DEVICES_LABEL = "No speakers found on this network"
internal const val CAST_DISCONNECT_LABEL = "Stop casting"
internal const val CAST_UNREACHABLE_SUFFIX = "not answering"

/**
 * Task 5: `AVTransport::Play` takes `Speed`, and every renderer allows only `"1"`. A book's stored
 * playback speed cannot be delivered to a speaker, so it is said out loud -- a setting that
 * silently does nothing is worse than one that is refused.
 */
internal const val CAST_SPEED_LIMIT_NOTICE = "A speaker plays at normal speed. Speed settings apply on this phone."

internal const val CAST_GROUPED_NOTICE = "Ungroup it in the Sonos app to cast to it."

internal fun castUiState(
  discovery: DiscoveryResult?,
  session: CastSessionState,
  connectedUdn: String? = null,
): CastUiState = when {
  session is CastSessionState.Failed -> CastUiState.Failed(session.deviceName, session.reason)
  // `null` means no search has completed. An empty `DiscoveryResult` means one has, and found
  // nothing -- different facts, and collapsing them leaves a spinner running over an empty room.
  discovery == null -> CastUiState.Searching
  else -> CastUiState.Devices(
    devices = discovery.devices.map { device ->
      CastDeviceRow(
        udn = device.udn,
        name = device.friendlyName,
        subtitle = device.modelName,
        isSonos = device.isSonos,
        isConnected = device.udn == connectedUdn,
      )
    },
    unreachable = discovery.unreachable.map { it.friendlyName },
    connectedUdn = connectedUdn,
  )
}

/**
 * The sentence a user reads when a cast does not work.
 *
 * Three different failures get three different sentences, because "something went wrong" is not
 * information. The three are: the speaker cannot reach the phone (Task 7's `Unroutable`), the
 * speaker is grouped with another (Task 5's `RendererFollowsAnotherException`), and the speaker
 * vanished mid-track (Task 8's `Lost`).
 */
internal fun castFailureMessage(session: CastSessionState): String? = when (session) {
  is CastSessionState.Lost ->
    "${session.deviceName} stopped responding. Playback moved back to this phone."
  is CastSessionState.Failed -> when {
    session.reason.contains("grouped") ->
      "${session.deviceName} is grouped with another speaker. $CAST_GROUPED_NOTICE"
    session.reason.contains("did not fetch") ->
      "${session.deviceName} could not reach this phone — it is probably on a different network."
    else -> "${session.deviceName} could not play: ${session.reason}"
  }
  CastSessionState.Idle, is CastSessionState.Connecting, is CastSessionState.Playing -> null
}
```

- [ ] **Step 4: The ViewModel, and the test that discovery is not always running**

`CastViewModelTest` (JVM, with a hand-written fake `RendererDirectory` seam and a fake
`CastSessionManager` seam — **no mock framework**, and `ConventionTest` rule 3 fails the build on
the mere word) asserts:

```kotlin
  @Test
  fun `a closed picker issues no search`() = runTest {
    // Discovery is three multicast datagrams and a three-second listen. Running it in the
    // background would be rude to the network and read by nothing.
    val viewModel = CastViewModel(directory, sessionManager)

    advanceTimeBy(10_000)

    assertThat(directory.searchCount).isZero
  }

  @Test
  fun `opening searches once, and refreshing searches again`() = runTest {
    val viewModel = CastViewModel(directory, sessionManager)

    viewModel.open()
    runCurrent()
    assertThat(directory.searchCount).isEqualTo(1)

    viewModel.refresh()
    runCurrent()
    assertThat(directory.searchCount).isEqualTo(2)
  }

  @Test
  fun `closing stops searching`() = runTest {
    val viewModel = CastViewModel(directory, sessionManager)
    viewModel.open(); runCurrent()

    viewModel.close()
    advanceTimeBy(10_000)

    assertThat(directory.searchCount).isEqualTo(1)
    assertThat(viewModel.uiState.value).isEqualTo(CastUiState.Hidden)
  }

  @Test
  fun `selecting a device casts to that device and not to another`() = runTest {
    // Two observations, so `select` cannot cast to a constant.
    val viewModel = CastViewModel(directory, sessionManager)
    directory.result = DiscoveryResult(listOf(deviceA, deviceB), emptyList())
    viewModel.open(); runCurrent()

    viewModel.select("uuid:b"); runCurrent()
    assertThat(sessionManager.castTargets.map { it.udn }).containsExactly("uuid:b")

    viewModel.select("uuid:a"); runCurrent()
    assertThat(sessionManager.castTargets.map { it.udn }).containsExactly("uuid:b", "uuid:a")
  }

  @Test
  fun `selecting a udn that is not on the network does nothing rather than throwing`() = runTest {
    val viewModel = CastViewModel(directory, sessionManager)
    viewModel.open(); runCurrent()

    viewModel.select("uuid:ghost"); runCurrent()

    assertThat(sessionManager.castTargets).isEmpty()
  }

  @Test
  fun `the ui state follows the session, in both directions`() = runTest {
    val viewModel = CastViewModel(directory, sessionManager)
    directory.result = DiscoveryResult(listOf(deviceA), emptyList())
    viewModel.open()

    viewModel.uiState.test {
      assertThat(awaitItem()).isEqualTo(CastUiState.Searching)
      sessionManager.emit(CastSessionState.Playing("A"))
      assertThat((awaitItem() as CastUiState.Devices).connectedUdn).isEqualTo("uuid:a")
      sessionManager.emit(CastSessionState.Failed("A", "the speaker did not fetch anything"))
      assertThat(awaitItem()).isInstanceOf(CastUiState.Failed::class.java)
    }
  }

  @Test
  fun `setting the volume passes the fraction through to the session player`() = runTest {
    // Two values, so the slider's argument is proved to have an effect.
    val viewModel = CastViewModel(directory, sessionManager)

    viewModel.setVolume(0.5f); runCurrent()
    viewModel.setVolume(0.17f); runCurrent()

    assertThat(sessionManager.volumes).containsExactly(0.5f, 0.17f)
  }
```

- [ ] **Step 5: The Composables**

`CastPickerSheet` is a Material 3 `ModalBottomSheet` rendering, in order: a title, the connected
device with `CAST_DISCONNECT_LABEL` when there is one, the device rows, the unreachable names
suffixed `CAST_UNREACHABLE_SUFFIX` and not clickable, a volume `Slider` when connected, and
`CAST_SPEED_LIMIT_NOTICE` when connected. `CastUiState.Searching` renders a progress indicator with
`CAST_SEARCHING_LABEL`; `Devices` with an empty list renders `CAST_NO_DEVICES_LABEL` and a refresh
action; `Failed` renders `castFailureMessage`'s sentence and a retry action.

`CastButton` renders an icon button whose `contentDescription` is `CAST_BUTTON_LABEL` when idle and
`"$CAST_BUTTON_LABEL — ${deviceName}"` when connected, so the emulator journey can find it by
semantics and assert **which** state it is in rather than only that it exists.

`feature/player/src/main/kotlin/app/muplay/player/PlayerScreen.kt` — add `CastButton` to the
transport row, and a single line above the transport controls reading *"Playing on {device}"* when
`CastSessionState` is `Playing`. Take the device name from a `castDeviceName: String?` parameter
with a `null` default rather than by having `:feature:player` depend on `:feature:castpicker`;
`:app` supplies it. Two feature modules that depend on each other is the first step toward neither
being removable.

`app/src/main/kotlin/app/muplay/ui/MuPlayApp.kt` — host `CastPickerSheet` behind a
`rememberSaveable` boolean, opened by `CastButton`.

`app/build.gradle.kts` — `implementation(project(":feature:castpicker"))`.

`.github/workflows/e2e.yml` — add `:feature:castpicker:connectedDebugAndroidTest` to the `script:`
enumeration if the module gains an `androidTest` source set; otherwise its LINE floor is measured
from `:app`'s journey, and Task 11's coverage step confirms which.

- [ ] **Step 6: Run, mutate, measure, commit**

```bash
./gradlew :feature:castpicker:test
./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
```

Mutations:

1. In `castUiState`, return `CastUiState.Searching` for an empty `DiscoveryResult`. Expect
   `an empty network is Devices with an empty list, not Searching` to fail.
2. In `castUiState`, hardcode `isConnected = false`. Expect
   `the connected device is marked, and only that one` to fail.
3. In `castUiState`, `sortedBy { it.name }` the rows. Expect
   `the order of the rows is the order discovery produced` to pass — **and that is a finding**:
   add a second case to that test with a discovery order that is not alphabetical, so the assertion
   actually discriminates. Do this before recording the probe.
4. In `castFailureMessage`, collapse the three branches into one sentence. Expect
   `each of the three failures gets its own sentence, and they are different sentences` to fail on
   `distinct()`.
5. In `CastViewModel`, start discovery in `init`. Expect `a closed picker issues no search` to fail.
6. In `CastViewModel.select`, cast to `devices.first()`. Expect
   `selecting a device casts to that device and not to another` to fail on its second observation.

Record 1, 2, 4 and 5 in `ci/mutation-probes.sh` as `castui/*` entries, and add
`:feature:castpicker:test` to `run_suite()`.

Measure the BRANCH floors from `./gradlew :feature:castpicker:test jacocoTestReport`; the LINE
floors over the Composables measure ~0% from the JVM and are completed in Task 11 from the
emulator run.

```bash
git add settings.gradle.kts feature app build.gradle.kts .github/workflows/e2e.yml ci/mutation-probes.sh
git commit -m "feat(cast): a picker that names the three ways casting fails"
```

---

## Task 11: The gates — the Tier 1 Cast job, the Tier 2 cast journey, the coverage floors, the spec corrections

**Files:**
- Modify: `.github/workflows/pr.yml` (a fifth job, `cast`)
- Modify: `.github/workflows/e2e.yml`
- Create: `app/src/androidTest/kotlin/app/muplay/CastJourneyTest.kt`
- Modify: `build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`
- Modify: `app/src/test/kotlin/app/muplay/ConventionTest.kt`
- Modify: `build.gradle.kts` (every floor completed and measured)
- Modify: `ci/mutation-probes.sh`
- Modify: `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

**Interfaces:**
- Consumes: every label constant from Task 10; Plan 3 Task 10's `PlaybackJourneyTest` helpers and
  Plan 2 Task 10's `reachLibraryScreen`; `FakeRenderer` (Task 3, now in `:core:cast`'s main source
  set per Task 8 Step 1).
- Produces: the Tier 1 `cast` job; `CastJourneyTest`; completed `coverageFloors` entries for
  `:core:cast`, `:core:media` and `:feature:castpicker`; a widened release-manifest gate; the
  `targetSdk 37` rule; the spec corrections.

### The Tier 1 `cast` job

Spec §10's Tier 1 table names a **Cast** row: *"in-process fake renderer on `127.0.0.1:0` —
SOAPACTION quoting, DIDL escaping round-trip, `protocolInfo` vs served `Content-Type`, Range →
206/416/HEAD"*. `pr.yml` has four jobs and none of them is it.

`./gradlew test` in the `unit-integration` job already runs `:core:cast:test`, so a separate job is
redundant compute — and the project already has that exact precedent: the `contract` job runs
`:core:testing:test`, which `./gradlew test` also covers, because a named gate row deserves its own
red/green signal and its own report artifact. Follow the precedent.

```yaml
  cast:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: zulu
          java-version: 21
      - uses: gradle/actions/setup-gradle@v6

      # The whole cast protocol surface: SSDP, device descriptions, SOAP, DIDL, the proxy and the
      # routing decision, against an in-process renderer on 127.0.0.1:0. `:core:cast` is a pure-JVM
      # module with no Android type in it, which is why all of this is here and not in Tier 2.
      - name: Cast protocol tests
        run: ./gradlew :core:cast:test :core:media:test --tests '*cast*'

      # The renderer that says no. Named separately because everything else in this job is only as
      # good as this one is strict.
      - name: Fake renderer strictness
        run: ./gradlew :core:cast:test --tests '*FakeRendererStrictnessTest*'

      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v7
        with:
          name: cast-reports
          path: |
            core/cast/build/reports/tests/**
            core/media/build/reports/tests/**
```

The live proxy tests join the existing `live-navidrome` job (Task 6 already added
`:core:cast:liveNavidromeTest` there), because that is where the container is.

### The Tier 2 journey, and exactly what it does not prove

Spec §10's Tier 2 table has one cast row: *"Cast — discover and stream to a renderer"*. There is no
renderer on the CI network and there never will be, so the journey runs the **real** `FakeRenderer`
in-process on the emulator, on `127.0.0.1:0`, and discovers it by **unicast** M-SEARCH — which is
not a test-only path but spec §12's required VPN fallback.

That proves, on a real Android runtime, with a real Navidrome behind it: discovery, description
parsing, the picker rendering and being tapped, the SOAP exchange, the DIDL a renderer receives,
the proxy serving real Navidrome bytes over a real socket on a real device, the routing proof, the
position advancing, the session following the switch, the notification, and the speaker vanishing.

**It does not prove four things, and Task 11's definition of done says so rather than letting a
green build imply otherwise:**

1. **Multicast delivery.** No multicast domain with a renderer in it exists in CI. The delta is one
   constant (`SsdpSearch.MULTICAST_ENDPOINT`) and one header line, both pinned by assertion in
   Task 2.
2. **Real Sonos SOAP behaviour.** The fake is built from the protocol and from documented Sonos
   quirks; it is not a Sonos. `FakeRendererStrictnessTest` makes its strictness explicit so that
   what is being assumed is written down rather than implied.
3. **The `x-rincon:` group-follower behaviour**, which is vendor behaviour with no citable
   specification.
4. **Whether a real renderer accepts this client's `protocolInfo`, DIDL and URL extensions.** The
   fake enforces what the protocol and spec §6 say; a real device may want more.

Everything on that list is a **hardware** claim. It goes in the definition of done as an explicit
manual step against the user's own Sonos, with the four questions written out — not as a gap
discovered later.

- [ ] **Step 1: Write the Tier 2 journey**

`app/src/androidTest/kotlin/app/muplay/CastJourneyTest.kt`:

```kotlin
  @Test
  fun castJourney() {
    // A real renderer, in this process, on the emulator's own loopback. Discovered by unicast
    // M-SEARCH -- spec section 12's required VPN fallback, not a test-only path.
    val renderer = FakeRenderer().also { it.start() }

    reachLibraryScreen(composeRule)
    composeRule.onNodeWithText(FIRST_ALBUM_TITLE).performClick()
    composeRule.onNodeWithText(FIRST_TRACK_TITLE).performClick()

    // 1. Local playback is really going. The position readout on the real screen, not `isPlaying`.
    val before = readPositionFromScreen(composeRule)
    composeRule.waitUntil(timeoutMillis = 15_000) { readPositionFromScreen(composeRule) > before + 2 }

    // 2. The picker finds the renderer.
    composeRule.onNodeWithContentDescription(CAST_BUTTON_LABEL).performClick()
    composeRule.waitUntil(timeoutMillis = 15_000) {
      composeRule.onAllNodesWithText("Fake Speaker").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Fake Speaker").performClick()

    // 3. The renderer received a well-formed SetAVTransportURI -- asserted on ITS bytes.
    composeRule.waitUntil(timeoutMillis = 20_000) {
      renderer.soapRequests.any { it.action == "SetAVTransportURI" }
    }
    val set = renderer.soapRequests.last { it.action == "SetAVTransportURI" }
    assertThat(set.rawSoapAction).startsWith("\"").endsWith("\"")
    assertThat(set.arguments.map { it.first })
      .containsExactly("InstanceID", "CurrentURI", "CurrentURIMetaData")
    assertThat(set.arguments[1].second).endsWith(".mp3")
    assertThat(set.arguments[2].second).startsWith("&lt;DIDL-Lite")
    assertThat(set.arguments[2].second).doesNotContain("&amp;lt;")

    // 4. It fetched real Navidrome bytes through the proxy, on a real device.
    val fetched = renderer.awaitMediaRequest(timeoutMs = 20_000)
    assertThat(fetched).isNotNull
    assertThat(fetched!!.target).endsWith(".mp3")
    assertThat(renderer.mediaRequests.map { it.method }).contains("HEAD", "GET")

    // 5. The UI says where it is playing, and the position advances while cast.
    composeRule.onNodeWithText("Playing on Fake Speaker").assertIsDisplayed()
    renderer.advance(20_000L)
    val castBefore = readPositionFromScreen(composeRule)
    composeRule.waitUntil(timeoutMillis = 15_000) { readPositionFromScreen(composeRule) > castBefore }

    // 6. The notification followed the session across the player switch.
    val posted = notificationManager().activeNotifications
      .single { it.id == PlaybackNotification.NOTIFICATION_ID }
    assertThat(posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE))
      .isEqualTo(FIRST_TRACK_TITLE)

    // 7. The speaker vanishes. Playback comes back to the phone, paused, at the right second, and
    //    the user is told -- rather than a UI that keeps saying "Playing on Fake Speaker".
    val lostAt = readPositionFromScreen(composeRule)
    renderer.disappear()
    composeRule.waitUntil(timeoutMillis = 20_000) {
      composeRule.onAllNodesWithText("Fake Speaker stopped responding. Playback moved back to this phone.")
        .fetchSemanticsNodes().isNotEmpty()
    }
    assertThat(readPositionFromScreen(composeRule)).isBetween(lostAt - 3, lostAt + 3)
  }
```

- [ ] **Step 2: Close the release-manifest hole this plan would otherwise open**

`verifyReleaseManifest`'s `forbiddenAttributes` is `listOf("usesCleartextTraffic")` and says nothing
about `networkSecurityConfig`. A casting plan is exactly the plan under pressure to add a release
network security config permitting cleartext — Task 1 rejected that route, and this pins the
rejection so it cannot be quietly reversed.

`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`:

```kotlin
      forbiddenAttributes.set(
        listOf(
          "usesCleartextTraffic",
          // A release `network_security_config.xml` is the other way to permit cleartext, and it
          // would slip past the check above entirely. MuPlay reaches renderers over plain HTTP
          // through `LocalNetworkOnly` and a socket client that consults no platform policy --
          // precisely so that the app's own network security posture stays at the default. If a
          // release build ever genuinely needs a network security config, changing this list is
          // the deliberate act that says so.
          "networkSecurityConfig",
        ),
      )
```

- [ ] **Step 3: Add the gate that fires the day `targetSdk` moves to 37**

Spike S1: `ACCESS_LOCAL_NETWORK` keys off the app's `targetSdkVersion`, is inert at 36, is
`protectionLevel:dangerous`, and when it bites it manifests as a **silent connect timeout** rather
than a `SecurityException`. Every socket this plan opens is a local-network socket, so the day
`targetSdk` becomes 37 without that permission, **casting stops working with no error anywhere**.

This plan does not add the permission — a `dangerous` permission that does nothing is visible on
the store listing and teaches a reader that the code depends on it. It adds the rule that makes the
omission impossible to forget, in `ConventionTest`:

```kotlin
  @Test
  fun `a targetSdk of 37 or above requires ACCESS_LOCAL_NETWORK in the manifest`() {
    // Spike S1: the permission keys off targetSdkVersion, not the device API level. At 36 it is
    // inert, which is why this app does not declare it. At 37 an ungranted app's local-network
    // connections fail as a SILENT ~4 s connect timeout -- so casting would stop working with
    // nothing reported anywhere.
    //
    // This rule is the whole reason the permission can safely be omitted today.
    val plugin = File(repoRoot(), "build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt")
    val targetSdk = Regex("""targetSdk\s*=\s*(\d+)""").find(plugin.readText())?.groupValues?.get(1)?.toInt()

    assertThat(targetSdk).describedAs("targetSdk in AndroidApplicationConventionPlugin").isNotNull

    if (targetSdk!! >= 37) {
      val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
      assertThat(manifest)
        .describedAs(
          "targetSdk is %d, so ACCESS_LOCAL_NETWORK is live (spike S1). Without it every cast " +
            "socket fails as a silent connect timeout. Declare it, and add the runtime grant to " +
            "the cast picker and a `pm grant` step to the emulator workflow.",
          targetSdk,
        )
        .contains("android.permission.ACCESS_LOCAL_NETWORK")
    }
  }
```

> **Rule 4 applies to this rule.** It is a gate that reports the absence of a problem, and at
> `targetSdk 36` its interesting branch does not run. Prove it can: temporarily set `targetSdk = 37`
> in the convention plugin, run `./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'`,
> **confirm it goes red**, and restore. Record the failure message in the task report. A conditional
> assertion nobody has ever seen fail is a comment.

- [ ] **Step 4: Complete and measure every coverage floor**

```bash
./gradlew test jacocoTestReport
# then, on the emulator:
./gradlew :core:media:connectedDebugAndroidTest :feature:castpicker:connectedDebugAndroidTest :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

Read every ratio out of each module's `jacocoTestReport.xml` and write the measured numbers into
`coverageFloors`. Three rules from the table's own standing policy:

- **Never `0.00`**, and never an invented round number. A floor of 0.00 passes at every minimum and
  gates nothing — this project shipped that once.
- **Never lower a floor to make a number fit.** Add the missing case.
- `"app.muplay.cast.fake.*"` goes in **excludes**, with the comment Task 8 Step 1 specifies.

Then read the notice output for all three modules and confirm each says *"evaluated all N of its
coverage floors"* rather than the `onlyIf` warning. **A module whose gate did not run is a module
this build says nothing about**, and the notice task exists to make that impossible to miss.

- [ ] **Step 5: Correct the spec**

Four edits to `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`, in one commit.

**§6, "The routing rule".** Replace the rule sentence. The current text —

> Same subnet as the speaker → stream through the phone proxy. Otherwise → the speaker fetches
> Navidrome directly.

— contradicts the table directly beneath it. Row 3 is *"Remote + VPN | VPN into home | home LAN |
home | **proxy over the tunnel**"*, and a routed VPN (WireGuard, Tailscale, OpenVPN `tun` — the
normal way to build that) puts the phone on `10.8.0.0/24` or `100.64.0.0/10` while the speaker is on
`192.168.1.0/24`. Different subnets, tunnel routes both ways, proxy works — and the rule as written
sends that case to the "otherwise" branch. Replace with:

> **The renderer streams from the phone proxy whenever it can reach it, and "can reach it" is
> established by observing the renderer actually fetch — not by comparing addresses.** Subnet
> equality is a fast path, not the rule: it is not necessary (a routed VPN puts the phone on its own
> subnet and the tunnel routes both ways) and not sufficient (client isolation on a guest network
> defeats it). The proof is free, because it is a side effect of `SetAVTransportURI` + `Play`: a
> renderer that is going to play fetches within a second or two, and silence is the answer.
>
> There is a **third** outcome, and it is the important one: when the renderer cannot reach the
> phone and renderer-direct is off, casting **fails loudly with a reason**. Without it, the failure
> is a session that reports success and plays nothing.

**§6, the Let's Encrypt claim.** *"Under this rule no speaker ever fetches over public HTTPS, which
designs the Let's Encrypt trust question out of existence entirely"* is true only if the
renderer-direct branch is unreachable — and §6 defines that branch. Replace "entirely" with the
condition:

> Under this rule no speaker fetches over public HTTPS **by default**: renderer-direct is opt-in and
> off, because taking it hands the speaker a URL carrying a non-expiring Subsonic auth token and
> requires the speaker to trust Navidrome's TLS chain. So the Let's Encrypt trust question is
> **deferred**, not eliminated — it returns the moment a user turns that setting on.

**§12, the SSDP row.** *"Multicast never crosses a tunnel — unicast fallback is required, not
optional"* names no target address, and a unicast M-SEARCH needs one the user has no way to supply.
Add what the fallback actually targets:

> ... unicast fallback is required, not optional — targeting **remembered devices**: re-fetch the
> stored `LOCATION` (covers Sonos, whose description port 1400 never moves), then unicast M-SEARCH
> to the remembered host (covers a generic renderer whose ephemeral description port changed on
> reboot), then report it unreachable **by name**.

**§10, the Tier 1 Cast row.** It names four subjects and omits three the gate actually covers, one
of which is the ordering property that a strict device rejects. Replace with:

> Cast | in-process **real** UPnP renderer on `127.0.0.1:0`, strict by default and with its
> strictness under its own test — SOAPACTION quoting, **SOAP argument order**, DIDL escaping round
> trip, `protocolInfo` vs served `Content-Type` **vs the URL extension**, Range → 200/206/416 with
> `Content-Range` and `HEAD`, **SSDP discovery over unicast with several devices on one network**,
> and **the routing decision in both directions**

Add a note under §10's Tier 2 table recording what the cast journey does **not** prove — the four
items listed above — so that a green build is not read as a hardware claim.

- [ ] **Step 6: Run everything, both tiers**

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
./gradlew check
./gradlew :core:network:liveNavidromeTest :core:cast:liveNavidromeTest
./ci/mutation-probes.sh
```

and the emulator suite via `.github/workflows/e2e.yml`'s script. `./ci/mutation-probes.sh` must
report every probe `CAUGHT` — and remember its header: a green run means the listed defects are
still caught, not that the suite is good.

- [ ] **Step 7: Commit**

```bash
git add .github ci build-logic app core feature build.gradle.kts docs/superpowers/specs
git commit -m "ci(cast): the Tier 1 cast job, the Tier 2 cast journey, and the spec corrections"
```

---

## Definition of done

1. All eleven tasks' tests pass; **both tiers green**.
2. **Tier 2 carries this plan's journey.** `CastJourneyTest` is in the emulator suite and covers:
   discover, cast, the renderer fetching real Navidrome bytes through the proxy on a real device,
   the position advancing while cast, the notification following the player switch, and the speaker
   vanishing with playback returning to the phone at the right second.
3. Coverage ≥ 0.90 on `:core:cast`, `:core:media` and `:feature:castpicker` — **branch** for non-UI,
   **line** for Compose UI. Every floor measured from a real run, none at `0.00`, and each module's
   notice reporting *"evaluated all N"* rather than the `onlyIf` warning.
4. No mock framework has entered the dependency graph. `ConventionTest` rule 3 and
   `verifyNoMockFrameworks` both pass.
5. **No UPnP library was added.** `gradle/libs.versions.toml` gains no third-party entry for this
   plan. The only new production dependency edge is `:core:media` → `:core:cast` and
   `:core:database` → `:core:cast`, both internal.
6. **The cleartext constraint holds and is enforced in two places at once:** the release manifest
   carries neither `usesCleartextTraffic` nor `networkSecurityConfig` (`verifyReleaseManifest`,
   widened in Task 11), and `LocalNetworkOnly` refuses a cleartext connection to any address that is
   not loopback, link-local, RFC 1918, RFC 6598 or an IPv6 ULA — with tests observing it refusing as
   well as permitting.
7. **Every spec correction in Task 11 Step 5 is applied to the spec**, not only to this plan.
8. `ci/mutation-probes.sh` runs `:core:cast:test` and `:feature:castpicker:test`, carries every
   probe this plan earned, and reports them all `CAUGHT`.

### And the part a green build cannot settle

**This plan has no reference implementation and no hardware in CI.** Four claims rest on the fake
renderer being right about real devices, and they must be checked once, by hand, against the user's
own Sonos, before this feature is called finished:

1. **Multicast discovery finds the speaker.** CI proves the unicast path and pins the multicast
   endpoint as a constant; it cannot prove a datagram reaches a speaker.
2. **The speaker accepts this client's `SetAVTransportURI`** — the envelope, the quoted SOAPACTION,
   the argument order, and the singly-escaped DIDL, exactly as `FakeRendererStrictnessTest`
   describes them.
3. **The speaker accepts the served format** — the `protocolInfo`, the URL extension and the
   `Content-Type` agreeing, and no Opus anywhere. Spec §12 rates this *Medium*, and it is the claim
   most likely to need a change.
4. **The `x-rincon:` group-follower detection fires on a grouped speaker**, which is vendor
   behaviour with no citable specification.

Run each, record the result, and correct the fake to match whatever the hardware actually does —
because the fake's strictness is this plan's only stand-in for a real device, and a fake that is
wrong is worse than no fake at all.
