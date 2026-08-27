# MuPlay

An Android music **and audiobook** player for [Navidrome](https://www.navidrome.org/)
and other Subsonic/OpenSubsonic servers, with **Sonos and DLNA casting**.

> **Status: feature-complete and gated; the store listing is the last mile.**
> The app connects to a real Navidrome over HTTPS, mirrors its libraries, plays
> music and audiobooks in the background with a media session, notification and
> lock-screen controls, resumes every book at its own position, shuffles within
> one library, streams to Sonos and generic DLNA renderers, and answers Android
> Auto's browse and voice search. A minified, signed release bundle builds from a
> tag. See [Roadmap](#roadmap) for what is still open.

## Why

Nothing existing does all four of these at once:

- **Library-scoped shuffle.** If music and audiobooks live in separate Navidrome
  libraries, hitting shuffle should not pull chapter 14 of a novel into a music
  session. Symfonium cannot restrict random playback to a library.
- **Real audiobook resume.** Every book remembers its own exact position, and
  keeps it across a music session in between. The queue is a list of pointers;
  progress is a property of the item — so playing something else touches nothing.
- **Sonos.** Streaming to Sonos speakers, plus generic DLNA renderers, whether
  the phone is at home, in the office, or on a VPN back to the home network.
- **Proper Android integration.** Background playback, media notification,
  Android Auto, Wear OS.

## Design

- **[Design spec](docs/superpowers/specs/2026-08-22-muplay-kotlin-design.md)** —
  the full concept: architecture, Navidrome integration, casting, audiobooks,
  testing strategy, risks.
- **[Roadmap](docs/superpowers/plans/2026-08-22-muplay-kotlin-roadmap.md)** —
  the plans in dependency order. Plan 8 (release and Google Play) was added
  after that document was written and is listed in the table below.
- **[Spike findings](docs/superpowers/spikes/)** — empirical answers to the
  questions the design rested on.

There is an earlier Java design and roadmap in the same directories, dated
`2026-08-21`. Both are **superseded** and kept only for history; the Java
implementation is tagged `java-prototype`.

## Roadmap

Eight plans, in dependency order. Each plan is a document in
[`docs/superpowers/plans/`](docs/superpowers/plans/); each of its tasks is a
deliverable a reviewer can accept or reject on its own.

| # | Plan | Status |
|---|---|---|
| 1 | Foundation + Subsonic client | **done** |
| 2 | Library mirror, browse, library-scoped shuffle | **done** |
| 3 | Playback core — background, notification, lock screen, cache, gapless | **done** |
| 4 | Audiobooks — per-book resume, M4B chapters, speed, sleep timer | shelf UI and the plan's own gates remain |
| 5 | Android Auto + Wear OS | Auto done; the watch module and its gates remain |
| 6 | Casting — Sonos and DLNA | the picker UI and the plan's own gates remain |
| 7 | Bindery and Lidarr requests | the request surface and the plan's own gates remain |
| 8 | Release and Google Play | listing copy and form-factor declarations remain |

Two things are **not** in any plan and are deliberately the account holder's:
publishing to Play needs a Google Play developer account, and a brand-new
personal account needs twelve testers running the app for fourteen continuous
days before production access opens. Nothing in this repository can do either.

## What is in the box

| Module | What it owns |
|---|---|
| `:core:model` | domain types, no Android |
| `:core:network` | the Subsonic/OpenSubsonic client, capability negotiation, the vendored spec and its fixture oracle |
| `:core:database` | the Room mirror, progress, book settings, and the Keystore-sealed credential store |
| `:core:media` | Media3, the playback service, the disk cache, gapless, the audiobook policies |
| `:core:cast` | HTTP/1.1, SSDP, SOAP, DIDL-Lite and the range-serving proxy — written rather than depended on |
| `:core:designsystem` | the Material 3 theme |
| `:feature:setup` `:feature:library` `:feature:player` | the Compose surfaces |
| `:integrations:core` `:integrations:lidarr` `:integrations:bindery` | optional request integrations, severable by construction |

`:integrations:*` lives outside `core/` on purpose: `ConventionTest`'s *nothing
outside integrations depends on an integration* is written as a path prefix, and
that rule is what makes "Plan 7 can be deleted" a checked fact rather than a
promise.

## Building

Requires JDK 21 and the Android SDK (`compileSdk 37`).

```bash
./gradlew build     # compile, all unit tests, Lint, and the JVM coverage floors
./gradlew test      # unit tests only
```

### The merge gate — two tiers, both required

Both must be green to merge. Neither is nightly and neither is advisory.

- **Tier 1** ([`pr.yml`](.github/workflows/pr.yml)) — under ten minutes, no
  emulator: convention rules, Android Lint, the release-manifest check, every JVM
  test, the coverage floors that need no device, the OpenAPI oracle's own
  self-tests, and `LiveNavidromeTest` against a pinned Navidrome container. (The
  committed response fixtures are validated against the vendored spec too, by
  `:core:network`'s own tests rather than by the `contract` job.)
- **Tier 2** ([`e2e.yml`](.github/workflows/e2e.yml)) — the first-run journey on
  a real API 37 emulator against that same real container, plus the rest of the
  coverage table: the floors over `@Composable` code, which only a real
  composition can exercise.

Running Tier 2 locally needs the container, an emulator, and one prepare step:

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
"$ANDROID_HOME"/emulator/emulator -avd muplay37 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
  -feature Minigbm -prop qemu.hardware.gralloc=minigbm &
./ci/prepare-emulator.sh          # waits for boot, checks the device, adb reverse
./gradlew :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

The two `minigbm` flags are not optional — see
[`ci/prepare-emulator.sh`](ci/prepare-emulator.sh) for the emulator/system-image
crash they avoid, and note that the script refuses to run without them.

## Project conventions

- **Kotlin only**, anywhere, including build logic. Every module applies its
  plugins through a convention plugin in
  [`build-logic/`](build-logic/convention/src/main/kotlin); a module's own
  `build.gradle.kts` carries `plugins {}`, `dependencies {}` and nothing else
  except its own identity (`namespace`, `applicationId`, `versionCode`,
  `versionName`). [`ConventionTest`](app/src/test/kotlin/app/muplay/ConventionTest.kt)
  enforces that allow-list rather than leaving it to habit.
- **No mock frameworks.** Real collaborators first, then hand-written fakes.
  `ConventionTest` scans the catalogue, every module build file and every
  build-logic source for Mockito/MockK/EasyMock/PowerMock.
- **KSP, never kapt.** Also enforced by `ConventionTest`.
- **JUnit 5 and AssertJ** for JVM tests; Turbine for `Flow` assertions. On-device
  Compose tests are JUnit 4, which `AndroidJUnitRunner` and
  `createAndroidComposeRule` make unavoidable and which costs nothing here.
- Sealed interfaces for state and results; data classes for DTOs and domain
  models, serialised with kotlinx.serialization.
- **Coverage floors are measured, never invented, and must be able to fail.**
  Branch coverage for non-UI code, line coverage for `@Composable` code — the
  Compose compiler emits synthetic branches inside author method bodies that no
  test can reach. The table, every number's derivation, and the split between the
  two tiers live in [`build.gradle.kts`](build.gradle.kts).
- Response fixtures are validated against a vendored copy of the OpenSubsonic
  OpenAPI spec, so response-shape assertions have an external oracle.
- Anything the spec gets wrong is corrected **in the spec**, not worked around in
  code.

## Release

`./gradlew :app:bundleRelease` produces a minified, signed `.aab`. The signing
key is never committed; a test proves no keystore material is tracked, and
`verifyReleaseSigned` refuses an unsigned artifact.

The release gates run against **the artifact**, not the DSL that was supposed to
produce it — minification is proven by comparing DEX type descriptors against
`mapping.txt`, and the merged manifest is checked for what it must and must not
contain. `verifyReleaseManifest`'s guarantee is precisely "no cleartext to a
remote host": Android's own default network security config permits cleartext to
`localhost` on this target level and no manifest can opt out of that, which is
also what lets the on-device cast proxy work in a release build.

Pushing a tag yields the uploadable bundle and its mapping file with no local
step. See [`docs/PRIVACY.md`](docs/PRIVACY.md) and
[`docs/PLAY-DATA-SAFETY.md`](docs/PLAY-DATA-SAFETY.md) — every claim in both is
traceable to code, and a test names the traceability.

**Book positions are local-only.** The Subsonic client declares no `scrobble`,
`nowPlaying` or `savePlayQueue` endpoint; that absence is what makes the privacy
claim checkable rather than promised.

## Licence

MIT — see [LICENSE](LICENSE).
