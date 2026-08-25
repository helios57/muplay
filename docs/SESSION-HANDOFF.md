# Handoff — MuPlay, the 2026-08-25 fleet session

Master is `6c7d4b1`, green (`check verifyNoMockFrameworks`), pushed, release
Kotlin and androidTest both compile. The app went from "cannot play audio" to
playing in the background with a session, notification, lock-screen controls, a
media cache, a player UI and proven gapless playback.

## Where the plans stand

Master `f0cb47f`, green, pushed. Playback core's last blocking piece (`MuPlayer`
+ `ProgressWriter`) is in.

| Plan | Merged | Notes |
| --- | --- | --- |
| 3 — playback core | 10 of 12 | 11 (ReplayGain) in flight; **12 deferred — needs the fixture window** |
| 6 — Sonos/DLNA casting | 4 of 12 + SOAP hardening | proxy in flight |
| 5 — Auto/Wear | 3 of 11 | T4 in flight |
| 7 — integrations | 3 of 11 | credential store and request store in |
| 4 — audiobooks | 0 of 10 | fixtures complete, **merge held** — see below |

## Lanes live now

| Worktree | Task | Holds |
| --- | --- | --- |
| `p3t10` | P3 T10 the gates (Tier 2 journey) | `ConventionTest.kt`, `app/androidTest/`, `app/build.gradle.kts`, `e2e.yml` |
| `p6t6` | P6 T6 the media proxy | `core/cast/proxy/`, `pr.yml` |
| `p5t4` | P5 T4 `BrowseItems` + `MuPlayLibraryCallback` | `browse/`, `MuPlaybackService.kt` |
| `p3t11` | P3 T11 ReplayGain | `MediaItems.kt`, `MuPlayerFactory.kt`, `SubsonicClient.kt`, `MuPlayDatabase` v4→5 |
| `p4t1` | P4 T1 audiobook fixtures | **complete, merge held** — needs a deployment window |

**`MediaItems.of` arity is a live hazard.** Two lanes each added a fourth
parameter to it today and collided; `p3t11` is adding a sixth. It was briefed to
say so explicitly in its report.

**Plan 3 Task 12 is deliberately not dispatched.** It adds an Opus file to the
fixture corpus and edits `ci/seed-fixtures.sh` / `ci/fixtures.md5` /
`ci/configure-libraries.sh` — the same files the held `p4t1` branch owns. Both
change the library's scanned-item count, so they land together, in one window,
or they fight.

## Merge routine that this session settled on

1. `git merge --no-edit <branch>` — expect conflicts in `build.gradle.kts` and
   `ci/mutation-probes.sh`; both are append-shaped, so keep **both** sides.
   `revert()`'s file list and the JVM suite list are the two places where taking
   one side silently loses a module.
2. Verify floors did not migrate: a clean auto-merge has reattached
   `CoverageFloor` entries to the **wrong module key** with `check` still green.
   Compare per-module floor counts, then read the `COVERAGE:` notice — it must
   say "evaluated **all** N of its coverage floors".
3. `./gradlew check verifyNoMockFrameworks`
4. **`./gradlew compileDebugAndroidTestKotlin`** for the modules involved —
   `check` does NOT compile androidTest, and master silently carried a broken
   device tier for hours because of it.
5. Re-run the merged lane's probe family on master.

## Open decisions, deliberately not made

- **M4, casting picker trust.** `RendererDirectory` uses `distinctBy { it.udn }`,
  which keeps the **first** announcement — and arrival order is attacker
  controlled: an impostor replying instantly beats a real Sonos waiting its
  random 0..MX, so the picker shows the familiar name pointing at the attacker.
  A source-match check does **not** fix it. Needs a trust design (pin a
  remembered host? require the described `<UDN>` to match the announced `usn` on
  the primary path, as the recovery path already does? user confirmation?).
  Recorded for Plan 6 Task 11.
- **`queuePlan` extraction.** Ruled: do it, as its own task, carrying the
  **strings** (`PlannedItem(song, streamUrl, artworkUrl)`) not just decisions —
  a decisions-only shape moves one mutation and leaves the rest. It moves five
  of six instrumented mutations onto the JVM tier, and forces a floor
  re-measurement because `QueueRepository` drops to zero branches. Do both in
  one task.
- **`check` should depend on androidTest compilation** in the convention plugin.
  Not done because `build-logic` was held by the keystone lane.
- **Docker reclaim.** `docker image prune -a` frees ~28.5 GB. User's call.

## The one thing to keep doing

Seven guards that **could not fail** were found in this repo in one day: a
`ConventionTest` rule green after deleting the attribute it checked; a
permission name that was a prefix of another; a doctype test that passed on the
exact mutation it existed to catch; a cache-key proof that was vacuous by
construction. Every one was found by *running* the falsification rather than
trusting it.

Two of those were in **the gates themselves**: `check` never compiled
androidTest, and a stale probe silently aborted the whole regression list. A
gate that passes because it never looks at the thing it claims to cover is the
defect this project is most prone to.

## SOAP review (Plan 6 Task 3) — landed after the handoff was written

**CRITICAL 1 · HIGH 2 · MEDIUM 4 · LOW 4 · MINOR 4.** The security claim the task
was dispatched to prove **holds** — the reviewer brute-forced every code point
`U+0000`–`U+2FFF` through the allowlists, confirmed `Regex.matches` anchors both
ends (no trailing-newline bypass), and found no input that passes validation and
still injects a header. Ordering is as claimed: validation runs before DNS or
socket. But three defects sit under it, and **Tasks 5/8/9 should not be built on
this until they are fixed.**

- **CRITICAL — `SoapEnvelope.descendant()` recurses unbounded.** Called from
  `parseFault` on *every* response, outside `invoke`'s try/catch. Depth 8000
  (~56 KB, inside the 1 MiB cap) overflows a default JVM stack; depth **3000**
  overflows at `-Xss512k` ≈ an Android worker thread. `SoapClient`'s KDoc tells
  later tasks that `catch (IOException)` is complete — `StackOverflowError` is an
  `Error`. **FIXED and on master** (`MAX_FAULT_DEPTH`); it was the identical
  shape in `DeviceDescription.parseDevice`.
- **HIGH — `render` inserts argument *values* verbatim. ROUTED to `p6t3fix`,
  option A: `render` escapes, `DidlLite.renderEscaped` is deleted.** The Plan 6
  Task 4 lane reproduced this independently and recommended the same fix. The KDoc claims `render`
  is "total: well-formed XML or throws". It is not. A Navidrome stream URL
  (`?u=…&t=…&s=…`) produces `The reference to entity "t" must end with ';'` — not
  well-formed. And `"x</CurrentURI><Speed>99</Speed><CurrentURI>y"` silently
  injects a third argument. Task 4's `DidlLite` escapes *the same URL* and
  documents why; SOAP, the layer that owns framing, does not. Fix: escape values
  in `render` and have `DidlLite` hand over unescaped, or split the type
  (`text(...)` vs `preEscaped(...)`).
- **HIGH — the "strict" fake parses request bodies with a regex. ROUTED to `p6t3fix`.**
  (`FakeRenderer.kt:51-56`), so it cannot see malformed XML at all. That is
  *why* 266 green tests could not see the HIGH above. Fix: parse with
  `DocumentBuilder`, answer 500 when it does not parse, keep `headBytes` raw.
- MEDIUM: an unreadable 200 body is reported as a **successful empty result**,
  indistinguishable from a legitimately argument-less response; the same 4096-char
  DOCTYPE window; no mechanical rule stopping a later task calling
  `CastHttpClient.exchange` with an unvalidated peer name (a `ConventionTest`
  would cost ten lines).
- LOW: `requireControlUrl`'s refusal echoes userinfo where `CastHttpClient`
  deliberately strips it.

Worth keeping from that review: it praised **recording the two *failed*
falsification attempts** as the single best thing in the task report — evidence
that "I withheld a test and the floor still passed" is a result, not an error.

## Plan 7 Task 1 review — HIGH 2 · MEDIUM 3 · LOW 4 · MINOR 2

Spec PASS, quality strong. The security check the task exists for **verifies**:
`CleartextForbidden.host` is genuinely host-only — run against real OkHttp 5.5.0,
`http://user:pw@10.0.0.1:8080/base` yields `host = 10.0.0.1`, no userinfo, no port,
and it is pinned at a value.

- **HIGH-1 — fixed here.** Nothing in `check` or CI compiled `app/src/release/kotlin`,
  so the release-side cleartext refusal was verified exactly once, by hand. Added a
  `Compile the gates check does not` step to `pr.yml` covering release Kotlin **and**
  androidTest (the gap that let master carry a broken device tier earlier today).
- **HIGH-2 — FIXED by Plan 7 Task 2's lane, falsified with `--rerun-tasks`.** `CleartextPolicy`'s KDoc claims
  no release-compiled code names `Allowed`. False: `IntegrationBaseUrl.kt:84` is
  `CleartextPolicy.Allowed -> true`, in the same module, compiled into release
  (`:app` uses `implementation`, not `debugImplementation`). And the cited
  `ConventionTest` rule opens three hardcoded paths — it never scans `app/src/main`
  or `integrations/`, so a ViewModel passing `Allowed` literally would leave every
  gate green. Property holds today **by luck, not construction**. Either widen the
  rule or narrow the sentence.
- **MEDIUM — userinfo is discarded silently.** Right to discard, wrong to be silent:
  a self-hoster fronting Lidarr with nginx basic-auth pastes
  `https://user:pw@host/`, sees "Valid", and every request 401s with nothing in the
  UI. Wants discard-and-tell.
- **MEDIUM — "a base URL that cannot carry a secret" is true for three components,
  not the URL.** The **path** is preserved verbatim (`/api/TOKEN123/v1`), and must be
  (Servarr `urlBase`). Narrow the claim before Task 2 stores this next to a Bindery
  admin key.
- **MEDIUM — the Android-library choice is unpaid-for.** `muplay.android.hilt` drags
  dagger/androidx into `:integrations:core` and **nothing in the module uses it** —
  no `@Inject`, `@Module` or dagger import. Task 2 is still working in
  `:core:database`. If its store lands there, that module cut off four JVM modules
  for nothing.
- LOW: `kotlinCode` strips full-line `//` but not trailing `//`, so the rule is still
  prose-satisfiable via an import alias; the severability grep misses
  `project(path = ":integrations:core")`; and the "three assertions were prose-
  satisfiable" comment overstates its own measurement 3:1 — exactly one was.

## HELD MERGE — Plan 4 Task 1 (audiobook fixtures), branch `p4t1-branch`

**Complete on the JVM tier, deliberately not merged.** `check` green,
`:core:testing` 35/35, `BookFixtures` 18/18 BRANCH + 46/46 LINE, `books/` probes
3/3, all four compile gates green.

**Why it is held:** merging makes master's **live suite red** until the fixtures
are deployed and Navidrome rescans. The container mounts the main repo's
`ci/fixtures/`, the library goes 4 → 9 scanned items, and
`ci/configure-libraries.sh` then waits for 9. That rescan changes what every
other lane's live tests see, so it needs a window when no live suite is running.

**To land it:** merge `p4t1-branch`, then deploy the fixtures into the main
repo's `ci/fixtures/` and let Navidrome rescan, then re-run `LiveNavidromeTest`
and the Tier 2 journeys. Expect `getScanStatus.count` 4 → 9.

**Fold the Ogg/Opus fixture into the SAME window.** The lane priced it: two
`-bitexact libopus` runs, identical md5, ~30 min, zero risk to existing
checksums — and it closes the one decision in this project that is still argued
rather than measured (`StreamFormat`'s "never Opus"). One real gotcha it found:
**Ogg puts its tags on the *stream*, not `format.tags`**, so `probe-chapters.sh`
would silently record blank titles (~3 lines to fix). Name it `.ogg`/`.oga`, not
`.opus` — Navidrome's extension table carries the first two, not the third.
It moves `scannedCount` 9 → 10, so doing it separately means rescanning twice.

**Two things it measured that are worth keeping:**
- The mp3 fixtures are **4049/6034/5042 ms**, not the round 4000/6000/5000 the
  brief assumed — libmp3lame pads to a whole 1152-sample frame and both ffprobe
  and Media3's `Mp3Extractor` report the untrimmed span. The m4b books *are*
  exact (21000/15000/12000).
- The chapter oracle is **independently checked**, which was the thing I most
  wanted: `books.tsv` is derived by `ffprobe`, no project source produces any
  value in it, and three separate checks guard it (`probe-chapters.sh --check`
  re-derives and diffs, `md5sum -c` guards the bytes the derivation reads, and
  the tests assert literals written from the brief rather than copied from
  script output). Falsified, not argued.

**Known gap it named:** `Tail Book`'s non-faststart layout has no automated
guard — ffprobe reads chapters identically from either layout, so nothing would
notice if someone added `+faststart`. Belongs with Plan 4 Task 3.
