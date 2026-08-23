# MuPlay

An Android music **and audiobook** player for [Navidrome](https://www.navidrome.org/)
and other Subsonic/OpenSubsonic servers, with **Sonos and DLNA casting**.

> **Status: under construction.** The foundation and Subsonic client are done and
> tested. It does not play audio yet — see [Roadmap](#roadmap).

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

- **[Design spec](docs/superpowers/specs/2026-08-21-muplay-design.md)** — the
  full concept: architecture, Navidrome integration, casting, audiobooks,
  testing strategy, risks.
- **[Roadmap](docs/superpowers/plans/2026-08-21-muplay-roadmap.md)** — seven
  plans in dependency order.
- **[Spike findings](docs/superpowers/spikes/)** — empirical answers to the
  questions the design rested on.

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
./gradlew build     # compile + all unit tests
./gradlew test      # unit tests only
```

### The merge gate — two tiers, both required

Both must be green to merge. Neither is nightly and neither is advisory.

- **Tier 1** ([`pr.yml`](.github/workflows/pr.yml)) — under ten minutes, no
  emulator: convention rules, Android Lint, the release-manifest check, every
  JVM test, the OpenAPI fixture contract tests, and `LiveNavidromeTest` against
  a pinned Navidrome container.
- **Tier 2** ([`e2e.yml`](.github/workflows/e2e.yml)) — the first-run journey on
  a real API 37 emulator against that same real container, plus the coverage
  gate (the floors are measured against merged JVM + instrumented execution
  data, so this is the one job where both halves exist).

Running Tier 2 locally needs the container, an emulator, and one prepare step:

```bash
docker compose -f ci/navidrome.compose.yml up -d --wait && ./ci/configure-libraries.sh
"$ANDROID_HOME"/emulator/emulator -avd muplay37 \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
  -feature Minigbm -prop qemu.hardware.gralloc=minigbm &
./ci/prepare-emulator.sh          # waits for boot, checks gralloc, adb reverse
./gradlew :app:connectedDebugAndroidTest
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

The two `minigbm` flags are not optional — see
[`ci/prepare-emulator.sh`](ci/prepare-emulator.sh) for the crash they avoid.

## Project conventions

- **Java 17 only. No Kotlin**, anywhere, including build logic. Enforced by
  ArchUnit rules, not convention.
- `@Nonnull`/`@Nullable` on every public signature; NullAway fails the build.
- Records for DTOs and domain models; sealed interfaces for state and results.
- Every Jackson-deserialised record carries an explicit `@JsonCreator` — D8
  strips record reflection metadata below `minSdk 33`, which breaks Jackson
  silently on-device while unit tests stay green.
- Response fixtures are validated against a vendored copy of the OpenSubsonic
  OpenAPI spec, so response-shape assertions have an external oracle.

## Licence

MIT — see [LICENSE](LICENSE).
