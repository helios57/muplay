# MuPlay — Implementation Roadmap

**Spec:** `docs/superpowers/specs/2026-08-21-muplay-design.md`

The spec covers seven independent subsystems. Each gets its own plan, and each
produces working, testable software on its own — so progress is visible and
reviewable rather than arriving in one lump at the end.

Plans are written **one at a time, just before execution**, so each is informed by
what the previous one actually revealed. Writing all seven up front would bake in
guesses that the first plan's spikes are designed to eliminate.

---

## Sequence

| # | Plan | Produces | Depends on |
|---|---|---|---|
| **1** | **Foundation + Subsonic client** | Talks to a real Navidrome, verified against the OpenAPI spec. CI gate live. | — |
| **2** | Library mirror + browse | Browsable, searchable library with **library-scoped shuffle** | 1 |
| **3** | Playback core | Plays music. Background, notification, lock screen, gapless, cache. | 2 |
| **4** | Audiobooks | Per-book resume, chapters from M4B, speed, sleep timer, smart rewind | 3 |
| **5** | Android Auto + Wear OS | Car and watch playback with resume progress | 3 (better after 4) |
| **6** | Casting — Sonos + DLNA | Cast to speakers across all three network scenarios | 3 |
| **7** | Bindery + Lidarr | Request books and music from inside the app | 2 |

**Why this order.** Plan 1 de-risks everything downstream — if the Navidrome
contract or the CI harness is wrong, every later plan inherits the mistake.
Plans 2–3 build the app you can actually use. Plan 4 delivers the headline
feature. Plans 5–7 are independent of each other and can be reordered or dropped
without disturbing the rest.

Casting (6) is deliberately late despite being a headline requirement: it is the
largest single subsystem (~1,500 lines written from scratch, no reference
implementation) and it needs a working player to cast *from*. Doing it earlier
means testing it against a stub.

---

## Front-loaded spikes

These run inside Plan 1, before anything is built on their assumptions. Each is
cheap and each could invalidate a design decision.

| Spike | Question | Result |
|---|---|---|
| **S1** | Does `ACCESS_LOCAL_NETWORK` gate `10.0.2.2` on API 37? | **Answered — no action needed today.** The gate is triggered by the app's `targetSdkVersion`, not the device's API level: a `targetSdk 36` app (this project's actual configured value) reaches `10.0.2.2` on an API 37 device with zero extra permissions. It only becomes required if/when `targetSdk` is bumped to 37, at which point `ACCESS_LOCAL_NETWORK` must be declared **and** runtime-granted (`pm grant` in CI; a real permission dialog for end users — it is a dangerous/runtime permission, not install-time). `10.0.2.2` and a real cross-subnet LAN address behave differently (only same-subnet destinations are gated). Full evidence: `docs/superpowers/spikes/2026-08-21-s1-local-network-permission.md`. |
| **S2** | Does a *completed* cached Navidrome transcode become Range-seekable? | Not yet run — informational, not blocking. |
| **S3** | Does Media3 1.11 extract M4B chapters from a `faststart` file served over HTTP with `format=raw`? | **Answered against a generic Range server; Task 8 then confirmed against a real Navidrome that its `format=raw` endpoint meets the HTTP precondition (Range support, real `Content-Length`) that mechanism depends on — Media3's own extraction was not separately re-run against Navidrome.** Media3 1.11 extracts both Nero `chpl` and QuickTime `chap` chapters over HTTP, for both faststart and non-faststart files (non-faststart seeks directly to the moov atom via HTTP Range rather than downloading the whole file, confirmed at realistic ~1.4 MB size). Originally verified against a throwaway Range-compliant HTTP server; Task 8 then confirmed, against the real, pinned `deluan/navidrome:0.63.2` image, that `format=raw` honours HTTP Range requests — including the exact tail-seek request a non-faststart file needs, verified byte-for-byte — with correct clamping and a correct `416` on a genuinely unsatisfiable range, and always serves a real `Content-Length`, never chunked transfer encoding. **`MetadataRetriever` also lives in a separate artifact (`media3-inspector`, not pulled in by `media3-exoplayer`) and must be constructed with an explicit `MediaSourceFactory` — the bare `Builder(...).build()` form silently drops QuickTime `chap` chapters entirely and leaves `chpl` chapter end times unpopulated, with no exception.** Full evidence and the exact API surface: `docs/superpowers/spikes/2026-08-21-s3-m4b-chapters-over-http.md`. |
| **S4** | Does Sonos accept the Let's Encrypt certificate? | Not yet run — informational, not blocking. |
| **S5** | Lidarr `POST /api/v1/album` payload against a live instance | Not yet run — informational, not blocking. |

S1 and S3 were blocking and are now both answered — neither invalidates the plan's architecture, but S3 found a required correction to how `MetadataRetriever` must be constructed (see the spike document). S2, S4 and S5 remain informational and can run in parallel whenever convenient.

---

## Global constraints

Copied verbatim from the spec. Every task in every plan inherits these.

- **Java 17 only. No Kotlin in any module**, including tests and build logic.
  Enforced by a build check, not convention.
- **Licence MIT.** No GPL code may be copied. All prior art is architecture-only.
- `@NonNull`/`@Nullable` on every public signature; **NullAway fails the build**.
- Records for DTOs and domain models; sealed interfaces for state and results.
- **Navidrome ≥ 0.62.0** required; ≥ 0.58.0 for multi-library.
- **Media3 1.11.0** minimum (chapters, `PlayerFence`, `InMemoryDatabaseRule`).
- **Room 2.8.x** — Room 3 is Kotlin-codegen-only.
- **`compileSdk 37`, `targetSdk 36`** (Play requires 36 from 2026-08-31),
  `minSdk 26`.
- Subsonic client identifier is **`c=MuPlay`** — never `DSub` or `SubMusic`,
  which Navidrome strips OpenSubsonic fields for.
- Stream requests force **`format=raw` or `format=mp3`**. Never Opus — Sonos
  cannot decode it and Navidrome mislabels it as `audio/ogg`.
- Media3 cache keys derive from the **track ID alone** via `setCustomCacheKey`.
- Book positions are **local only**. No server sync.
- **PR gate ≤ 10 minutes with no emulator.** Emulator work is nightly.
- Goldens are recorded as **separate bot commits**, never in the same commit as
  the change they justify.
- Inject `java.time.Clock`; `System.currentTimeMillis()` is banned outside `:di`.

---

## Definition of done, per plan

1. All tasks' tests pass.
2. PR gate green in under 10 minutes.
3. No new ArchUnit or NullAway suppressions.
4. Branch coverage on touched `:core:*` modules has not decreased.
5. Every new external-API assumption is backed by a contract test against the
   vendored OpenAPI spec, or by a live test against the Navidrome container.
6. Anything discovered to be wrong in the spec is corrected **in the spec**, not
   just worked around in code.
