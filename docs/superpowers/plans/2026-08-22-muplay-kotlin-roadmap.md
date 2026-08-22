# MuPlay — Roadmap (Kotlin / Compose)

**Spec:** `docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md`

Supersedes the Java roadmap. The Java implementation is tagged `java-prototype`.

Plans are written **one at a time, just before execution**, so each is informed
by what the previous one revealed. Writing all of them up front bakes in guesses
that the earlier plans exist to eliminate — Plan 1 of the Java attempt proved
this twice, correcting two spec errors and finding a bug 42 green unit tests had
missed.

---

## Sequence

| # | Plan | Produces | Depends on |
|---|---|---|---|
| **1** | **Foundation + Subsonic client** | Kotlin/Compose skeleton, convention plugins, talks to a real Navidrome, both CI tiers live | — |
| **2** | Library mirror + browse | Browsable, searchable library with **library-scoped shuffle** | 1 |
| **3** | Playback core | Plays music. Background, notification, lock screen, gapless, cache. | 2 |
| **4** | Audiobooks | Per-book resume, M4B chapters, speed, sleep timer, smart rewind | 3 |
| **5** | Android Auto + Wear OS | Car and watch playback with resume progress | 3 (better after 4) |
| **6** | Casting — Sonos + DLNA | Cast across all three network situations | 3 |
| **7** | Bindery + Lidarr | Request books and music from inside the app | 2 |

**Why this order.** Plan 1 de-risks everything downstream. Plans 2–3 build the
app you can actually use. Plan 4 delivers the headline feature. Plans 5–7 are
independent of each other and can be reordered or dropped.

Casting is deliberately late despite being a headline requirement: it is the
largest subsystem with no reference implementation, and it needs a working player
to cast *from*.

---

## What carries over from the Java attempt

Reusable as-is — do not redo this work:

- **Both spike answers.** S1 (`ACCESS_LOCAL_NETWORK` keys off `targetSdk`, inert
  at 36, `dangerous` protection level, manifests as a silent connect timeout) and
  S3 (Media3 1.11 extracts `chpl` **and** `chap` over HTTP, faststart or not, but
  **only** with an explicit `MediaSourceFactory`; chapters live in
  `media3-inspector`). Documents are in `docs/superpowers/spikes/`.
- **Every Navidrome behaviour** in spec §4, including the `getIndexes`
  scope-widening trap, the `getRandomSongs` 500 cap, and the confirmation that
  `format=raw` honours Range with `Content-Length`.
- **CI infrastructure**: `ci/navidrome.compose.yml` (pinned 0.63.2,
  `ND_DEVAUTOCREATEADMINPASSWORD`, body-matching healthcheck because the image has
  no `curl` and `/rest/ping.view` returns 200 on auth failure),
  `ci/configure-libraries.sh` (library 1 is path-pinned and undeletable),
  `ci/seed-fixtures.sh`, the committed audio fixtures, and the vendored
  OpenSubsonic OpenAPI spec.
- The **OpenAPI-as-external-oracle** testing idea and its validator design.

Discarded: all Java source, the no-Kotlin enforcement, Views/XML, the
Guava/`ListenableFuture` reasoning, the Jackson-vs-Moshi analysis, and the
D8-record-desugaring finding (a Java-records problem that Kotlin does not have).

---

## Global constraints

Every task in every plan inherits these.

- **Kotlin 2.4.10**, JDK 21 toolchain, **Compose** for all UI.
- Licence **MIT**. No GPL code may be copied.
- `compileSdk 37`, `targetSdk 36`, `minSdk 26`. Play requires 36 from 2026-08-31.
- **KSP only. KAPT is dead** and KSP1 has been removed upstream.
- `data class` for models; **sealed interfaces for state and results**.
- Immutable `UiState` as `StateFlow`, collected with `collectAsStateWithLifecycle()`.
- Repositories are the only entry point to data. **No domain layer** unless logic
  is genuinely shared across features.
- Convention plugins in `build-logic/convention`. No copy-pasted build scripts.
- Subsonic client identifier **`c=MuPlay`**, protocol `v=1.16.1`.
- Stream requests force **`format=raw` or `format=mp3`**. **Never Opus.**
- Media3 cache keys derive from the **track id alone** via `setCustomCacheKey`.
- Book positions are **local only**.
- **No mock frameworks.** Fakes only, and only where the real thing cannot run.
- **Coverage ≥ 90% per module**, generated code excluded, enforced by **JaCoCo**
  merging JVM and emulator execution data (Kover cannot collect instrumented
  coverage). The metric differs by kind of code: **branch** coverage for non-UI
  code, **line** coverage for Compose UI, because the Compose compiler emits
  synthetic branches inside author method bodies that no test can reach and no
  class-level exclusion can filter. Every floor is measured, never invented, and
  **must be able to fail**; a module with no floor entry warns loudly.
- **Two-tier merge gate, both required.** Tier 1 ≤ 10 minutes with a real
  Navidrome container but no emulator. **Tier 2 is emulator end-to-end and must
  be green to merge.**
- Inject a `Clock`; no direct wall-clock reads outside the injection point.

---

## Definition of done, per plan

1. All tasks' tests pass; both tiers green.
2. **Tier 2 carries this plan's E2E journeys.** A plan is not done until its
   journeys are in the emulator suite.
3. Coverage ≥ 90% on every module the plan touches — **branch** for non-UI
   code, **line** for Compose UI. Every floor measured, and able to fail.
4. No mock framework has entered the dependency graph.
5. Every new external-API assumption is backed by a contract test against the
   vendored OpenAPI spec, or a live test against the Navidrome container.
6. Anything discovered to be wrong in the spec is corrected **in the spec**.
