# MuPlay

An Android music **and audiobook** player for [Navidrome](https://www.navidrome.org/)
and other Subsonic/OpenSubsonic servers, with **Sonos and DLNA casting**.

> **Status: under construction.** The foundation and Subsonic client are done and
> tested — the app connects to a real server and lists its libraries. It does not
> play audio yet; see [Roadmap](#roadmap).

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
  seven plans in dependency order.
- **[Spike findings](docs/superpowers/spikes/)** — empirical answers to the
  questions the design rested on.

There is an earlier Java design and roadmap in the same directories, dated
`2026-08-21`. Both are **superseded** and kept only for history; the Java
implementation is tagged `java-prototype`.

## Roadmap

| # | Plan | Status |
|---|---|---|
| 1 | Foundation + Subsonic client | **done** |
| 2 | Library mirror, browse, library-scoped shuffle | in progress |
| 3 | Playback core — background, notification, lock screen, cache | planned |
| 4 | Audiobooks — per-book resume, M4B chapters, speed, sleep timer | planned |
| 5 | Android Auto + Wear OS | planned |
| 6 | Casting — Sonos and DLNA | planned |
| 7 | Bindery and Lidarr requests | planned |

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
  test, the coverage floors that need no device, the OpenAPI fixture contract
  tests, and `LiveNavidromeTest` against a pinned Navidrome container.
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

## Licence

MIT — see [LICENSE](LICENSE).
