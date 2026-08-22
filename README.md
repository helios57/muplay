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

Requires JDK 17+ and the Android SDK (`compileSdk 37`).

```bash
./gradlew build     # compile + all unit tests
./gradlew test      # unit tests only
```

The PR gate runs in under ten minutes with **no emulator**. Emulator and
real-server work runs nightly against a pinned Navidrome container — see
[`.github/workflows`](.github/workflows).

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
