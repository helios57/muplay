# Handoff — MuPlay, the 2026-08-25 fleet session

The app went from "cannot play audio" to playing in the background with a
session, notification, lock-screen controls, a media cache, a player UI and
proven gapless playback, and from there to a signed AAB that installs and plays
on the emulator.

*(This paragraph used to name a commit. It named `6c7d4b1` for three days after
master had moved on — the same stale-measurement defect `CLAUDE.md` records
against floor comments and lane reports. The current commit lives in the dated
section below, where the date makes it checkable.)*

## Status — 2026-09-05

**Both full device suites are green on this tree, run today, whole rather than filtered:**

| suite | result | wall clock |
| --- | --- | --- |
| `:app` | **68/68** | 6m 03s |
| `:core:media` | **371/371** | 8m 46s |

That is worth stating precisely because of what this file records two sections down: a
single red in a full device run has repeatedly been an order-dependent flake rather than a
defect, and the failing test moved between runs. This run had none, on suites that have
grown from the 54 and 352 in that table.

`./gradlew check` is green, and `ConventionTest` was re-run with `--rerun` (a repo-wide
rule is skipped as UP-TO-DATE when you edit a file outside `:app`, so a plain green there
is not evidence — CLAUDE.md).

**Eleven modules now have a device tier**, 851 instrumented tests in total. Run today:
`:app` 68, `:core:media` 371, `:feature:book` 52, `:feature:requests` 45,
`:feature:castpicker` 31, `:feature:setup` 10. Not re-run today, unaffected by the day's
changes: `:core:database` 182, `:feature:player` 43, `:integrations:core` 41,
`:integrations:requests` 5, `:feature:settings` 3.

`:feature:setup` is the newest of the eleven and was added on 2026-09-05: `ServerSection`
destroys the AndroidKeystore key and had no device tier at all, so its three documented
decisions — collect the identity rather than read it once, ask before destroying, sign out
*before* navigating and on the main thread — were held only by the comments describing
them. Each is now a test, and each was watched to fail against the mutation it describes.

### What is actually left

`docs/AUDIT-BACKLOG.md` is the list, and it was re-checked against the tree on 2026-09-05
rather than remembered. In short:

- **All eight P0 defects are fixed**, with tests that were watched to fail first.
- **Eight of eleven P1 items are closed**, one of them as a deliberate refusal that carries
  its own contrast measurement. Three remain: dark `surfaceVariant` still collides with
  `outlineVariant` (so a missing cover is the brightest thing on a dark screen), the library
  chip still does not carry the tint setup taught, and three type styles have no call sites.
- **One release blocker:** the seven store screenshots are one design round stale. They
  exist; they show UI that no longer does. `StoreListingTest` checks names, not pixels.
- **Eight audits have still never run** — accessibility (its 48dp half is now done and only
  that half), Compose performance, vacuous assertions, coverage's 92 ungated classes, dead
  code, docs coherence, remaining security surface *including a possible SSRF in the
  `muplay-art:` scheme*, and audiobook domain. Their absence is not a clean result.
- The order-dependent device flakes below are documented and **not fixed**. The fix is to
  make each of those tests set up the state it depends on, not to add sleeps.
- Nothing is pushed. `origin/master` is at `7be51e2`, more than twenty commits behind.

### What this file is, and is not

The plan/lane framing below is retired. It describes a fleet of parallel agents that no
longer runs, and its status lines have twice been read as current when they were a week
old — the same stale-measurement defect CLAUDE.md records against floor comments and lane
reports. **Dated sections only, from here on.** Everything below this line is kept for the
defects and measurements it records, not for its status.

---

## Where the plans stand — 2026-08-28 (HISTORICAL — see the status section above)

*Kept as written. The plan/lane framing below describes a fleet that no longer runs, and
the module counts are a week stale: there are now eleven modules with a device tier, not
the set named here. Read it for the four defects it records, not for its status.*

Master is green: `--no-build-cache check`, androidTest compiling for all ten
modules, `:build-logic:convention:test`, and `ci/probe-preflight.py` at 491 probes
across 108 files. **71 of 82 tasks merged**, up from 64.

Seven of the eight in-flight lanes were merged and gated after the fleet was lost to
a weekly rate limit (resets **Aug 31 16:00** Europe/Vienna — no subagent can run
before then; this was done from the main session).

| Plan | Merged | Remaining |
| --- | --- | --- |
| 1 foundation · 2 library | 16/16 | complete |
| 3 playback core | 12/12 | complete |
| 4 audiobooks | 8/10 | T9, T10 not started |
| 5 Auto/Wear | 8/11 | T9, T10, T11 not started |
| 6 casting | 11/12 | T11 not started |
| 7 integrations | 9/11 | T10, T11 not started |
| 8 release & Play | 8/10 | **T6 blocked** (below) · T10 not started |

### Four real defects the merges introduced or exposed, all found by gates

1. **The app died on launch.** P6 T10 and T12 each added navigation entries, leaving
   `entry<PlayerRoute>` declared twice; Navigation 3 throws at composition. `check`
   was green over it. Fixed in `5799c7a` with a derived `ConventionTest` rule.
2. **A Hilt duplicate binding.** P4 T7's stand-in `provideAudiobookItemSource` and
   P4 T6's real `@Binds` are two unqualified bindings of one type. The build failed
   loudly, which T7's KDoc had named in advance as the good outcome.
3. **Android Auto browse died for the life of the process.** `MuPlaybackService`
   called `release()` — `scope.cancel()` — on the `@Singleton` browse callback, so
   every service after the first was inert and answered browsers with 40-second
   timeouts. `:app` went 48/54 → 54/54. See `CLAUDE.md`.
4. **Two device tests that could not pass**: an insert of `Float.NaN` into a NOT NULL
   `REAL` (SQLite stores NaN as NULL), and a reflection filter that reported
   `Companion` as a field.

### Device tier

406 tests across `:app` (54) and `:core:media` (352). A clean full run has been
observed; individual runs show 0–2 failures whose identity **changes between runs**,
and all four such tests pass in isolation. `CLAUDE.md`'s "The device suite has
order-dependent flakes" records the measurements and the two mechanisms. The fix —
making those tests set up the state they depend on — has not been done.

`:feature:castpicker`, `:feature:settings` and `:integrations:requests` now appear in
both workflow module lists (the union resolution of four lanes' hand-edits, checked
by `ConventionTest`), but their device suites have not been run here.

### P8 T6 — LANDED. Both halves are on master; one is stale rather than missing

*Corrected 2026-09-05. Everything below the first paragraph was true on 08-28 and is not
true now, which is exactly the failure mode this file keeps recording against other
people's notes.*

`docs/STORE-LISTING.md` **is** on master, gated by `StoreListingTest`, and no longer
disclaims the five capabilities: it claims casting to Sonos and DLNA, per-book speed and
silence skipping, and Lidarr/Bindery requests, and it correctly says there is **no** Wear
OS app, because no watch artifact is built or signed. `app/build.gradle.kts` declares the
listing, the release workflow and `play/` as inputs of that test, after it was measured
running `FROM-CACHE` over an edit to the very document it gates.

The seven phone screenshots are captured and committed under `play/screenshots/phone/`.
What is wrong with them is not that they are missing: they were regenerated at `976b0bc`
**between** the two design rounds, so they show `Open` buttons under rows, a three-across
button row, a teal artist line and an off-axis play control, none of which exists in the
code. `StoreListingTest` checks names, not pixels, so nothing goes red. **Regenerate with
`ci/store-screenshots.sh` before any submission** — this is the one item in this file that
blocks a Play release.

## Remaining waves

- **B** — P4 T6 (resume policy: the swap that makes per-book resume land on the
  *second*, not just the file), P4 T7, P6 T10, P6 T12, P7 T9, P5 T8.
- **C** — P4 T9 `:feature:book`, P5 T9/T10 watch surface + `WatchLink`,
  P7 T10 `:feature:requests`, P8 T6 store listing.
- **D, the gates** — P4 T10, P5 T11, P6 T11, P7 T11, P8 T10.

## Infrastructure, as of now

- **A 500 GB disk was hot-added live** from the hypervisor (`192.168.0.10`,
  domain `ubuntu24.04`, identity confirmed by MAC `52:54:00:6d:4e:64`): qcow2 on
  `/media/raid5`, `virsh attach-disk --live --persistent`, ext4, mounted at
  `/mnt/data`, `fstab` with `nofail`. **No reboot** — uptime unbroken.
- `/` is at ~34 GB free. **`~/.gradle` (~28 GB) still needs relocating to
  `/mnt/data`** — deferred because every lane is building against it.
- `docker info` reports its root as `/var/lib/docker`, but that path is 4 KB and
  is not a mountpoint, so Docker's real data is elsewhere on `/`. Unresolved.
- Gradle capped at `workers.max=3`, `priority=low` (user's IntelliJ and Rust
  builds share this host). Emulator `muplay37` and `ci-navidrome-1` both healthy.

## Open decisions, deliberately not made

- **RESOLVED, and the advice that stood here was the defect.** This section used
  to recommend `docker exec ci-navidrome-1 sh -c 'rm -rf /data/cache/transcoding/*'`
  as a safe repair for `coldTranscode`. **It is the opposite.** Navidrome keeps an
  in-memory index of that cache; deleting the files underneath a running server
  leaves every key pointing at a file that is gone, and each one then answers a
  ~292-byte JSON error document with **no `Accept-Ranges` header at all** —
  permanently, measured across 28 retries with no recoveries. A restart of the
  container heals every poisoned key (the index is rebuilt from what is on disk);
  a file deletion is what creates them. Do not flush.

  The `one run in three` failure was also not a race and not a probability: it was
  **which track got drawn**. After the flush, the census was 63 of 63 bitrates
  unusable on Track 1 and only 4-6 of 10 on Tracks 2 and 3, so drawing the dead
  track was a certain failure and the other two near-certain passes. `coldTranscode`
  now searches the whole music library crossed with the bitrate range and reports a
  LIVE/CACHED/UNAVAILABLE census when it fails. Measured after the fix: **10
  consecutive green runs** against the warm, still partially-poisoned container.
  See CLAUDE.md, "Never delete the transcoding cache files".

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

## RESOLVED — Plan 4 Task 1 (audiobook fixtures) merged; kept for the lesson below

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
