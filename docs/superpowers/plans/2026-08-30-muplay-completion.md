# MuPlay completion plan — 2026-08-30

**Status:** written 2026-08-30 17:55, against master `7fb1005`, after recon on every
remaining task. Supersedes the "Remaining waves" section of `docs/SESSION-HANDOFF.md`.

This plan is deliberately **not** a restatement of the task specs. Plans 4–8 already
carry 8,000–12,000 lines each of step-by-step detail, and duplicating them would cost
tokens and go stale the moment either copy moved. What follows is the **delta**: the
order to work in, the premises those specs get wrong about today's tree, the resources
that actually exist, and the things that cannot be done here at all.

Read this file for *what and when*. Read the numbered plan for *how*.

---

## 1. The finding that sets the order

`:feature:book` does not exist, and **no UI module references the audiobook engine.**
Verified on master:

    grep -rn "AudiobookRepository|BookTimeline|SleepTimerController|bookshelf(" \
      --include=*.kt feature/ app/src/main wear/src/main   ->   no matches

Plan 4 Tasks 1–8 are merged. `AudiobookRepository`, `BookTimeline`, `ChapterRepository`,
`SleepTimerController`, `AudiobookSnapshot`, per-book speed and the resume policy are all
built, tested and gated — and there is no screen anywhere that reaches any of them. A user
installing this app today cannot open a book.

The user's original requirement named audiobooks with exact per-book resume as a headline
differentiator. It is the one stated requirement that is presently **dead code**. Everything
else in the backlog is polish, gates, or a second surface for something already reachable.

So: **Plan 4 Task 9 goes first, and nothing competes with it.**

## 2. What is left, honestly sized

Recon measured each remaining task against the real tree. These are agent-hours of focused
work, not wall-clock.

| # | Task | Hours | Value | Verifiable here? |
|---|---|---|---|---|
| P4 T9 | `:feature:book` — shelf, book, audiobook player | 10–14 | **highest** — unlocks a headline feature | yes |
| P7 T10 | `:feature:requests` — Lidarr/Bindery UI | 10–14 | medium — backend is reachable only by test | yes |
| P5 T10 | `WatchLink` — credentials + progress to the wrist | 10–14 | medium | **yes, fully** |
| P4 T10 | Plan 4 gates — audiobook journeys, floors | 10–16 | high (guards T9) | yes |
| P6 T11 | Casting gates | 8–12 | medium | yes |
| P7 T11 | Integration gates — live Lidarr/Bindery | 14–20 | medium | mostly |
| P5 T9 | The watch surface — Wear UI | 14–20 | medium | **partly — see §4** |
| P5 T11 | Plan 5 gates | 5–8 | medium | **partly — see §4** |
| P8 T6 | Store listing + screenshots | see §5 | low until launch | yes |
| P8 T10 | Form-factor listings | small | low | no — needs P5 |

**Total: roughly 90–130 hours of agent work.** That is the honest number and it does not
fit in one session. The order above is therefore value-ordered rather than plan-ordered, so
that whatever time is available produces the most complete app rather than the most
complete *plan*.

## 3. Execution model — one lane, on master

Changed from the previous session on the user's instruction, and the merge evidence supports
it independently. **All four defects the last session found were introduced by merges**, not
by the lanes: a duplicated navigation entry that crashed the app at launch, a duplicated Hilt
binding, a spliced KDoc, and a duplicated `include`. Two lanes editing one hand-written list
is this repository's most reliable source of defects.

One lane at a time removes that class by construction:

- no concurrent worktrees, so **no cross-worktree build-cache poisoning** — `--no-build-cache`
  can go back to being reserved for merge gates rather than paid on every run (measured: the
  full `check` is 2m50s with the cache, and the cache is now trustworthy because nothing else
  is writing to it);
- every branch is the only branch, so **every merge is a fast-forward** and no conflict
  resolution happens at all;
- the emulator is uncontended, so `ci/device-lock.sh` never queues and exit 75 cannot occur.

Each task lands as a sequence of independently-committable pieces, gated before each commit.

## 4. What cannot be done on this host

**There is no Wear OS emulator and no Wear system image.** Measured:

    emulator -list-avds              ->  muplay37        (a phone)
    ls $ANDROID_HOME/system-images/  ->  android-37.0/google_apis   only

`CLAUDE.md` forbids adding a second device to this shared host, so this is a hard boundary,
not a chore. Consequences, stated precisely so nobody later reports these as done:

- **P5 T9's `WearUiJourneyTest` cannot run here.** Its JVM tier and its compilation can.
- **`:wear`'s coverage floors cannot be measured here** — including the `WearBrowser` floor
  whose own comment in `build.gradle.kts:3745` already marks it *"THE ONE ENTRY IN THIS TABLE
  WHOSE RATIO IS NOT MEASURED"* and hands it to Tasks 9/11. Those numbers can come only from
  a CI Wear run. **Do not invent them.** Inventing a floor is precisely the vacuous-gate
  defect this project exists to keep out.
- **P5 T11's `:wear` half stays open**, and **P8 T10 stays blocked**, because it declares the
  Wear form factor and cannot honestly do so unverified.

**P5 T10 is unaffected and is the counter-example worth noting:** its `WatchLink` seam is two
methods with no decisions, every decision lives in pure types gated on the JVM tier, and its
instrumented test is routed to the *phone* emulator by design because it needs Room, not a
watch. It is fully verifiable here. That is good design paying off, and it is why T10 is
sequenced before T9 despite the lower task number.

## 5. Corrected premises — read before starting any task

Recon checked every claim these specs make about existing code. The specs were written days
ago and the tree moved; this repository's recorded defect class is *"a lane's report describes
master as it was at that lane's last sync"*. The below are **measured false** today. This
list is the single highest-value part of this document.

### Plan 4 Task 9

- **`id("muplay.android.feature")` does not exist.** The convention plugins are
  `muplay.android.{application,library,compose,hilt,room,wear}`, `muplay.jvm.library`,
  `muplay.kotlin.serialization`. Use library+compose+hilt, as `feature/library` does.
- **`CoverArtImage` is not in `:core:designsystem` and takes 5 parameters, not 3.** It is
  `app.muplay.library.CoverArtImage(coverArtId, sizePx, contentDescription, urlProvider, modifier)`
  at `feature/library/.../CoverArt.kt:28`. The spec's three-argument call will not compile.
  Moving it is not the two-file change the spec assumes: it drags `coverArtCacheKey`, two call
  sites, `CoverArtTest`, and **four `:feature:library` coverage floors** that name `CoverArtKt`.
  **Decide this before writing screens**, and prefer giving `:feature:book` its own small cover
  composable over a move that re-measures another module's floors.
- **`BookSummary.author` is non-null `String`**; **`lastPlayedAtEpochMs` is non-null `Long`**
  (0 means never); **`progressFraction` is `Double`, not `Float`**. The spec's fixtures pass
  `null` for the first two and its `LinearProgressIndicator(progress = { … })` will not compile
  against the third.
- **`MuPlayApp` has no `playbackState`**, so the `isAudiobook` mini-player branch needs a new
  state source in `:app`.
- **The spec's `BookViewModel.playChapter` calls a `playbackConnection` that is not in its own
  constructor.** A defect in the plan's listing, not in the tree.
- Catalogue aliases are `libs.activity.compose` and `libs.compose.ui.test.manifest`, not the
  `androidx.`-prefixed names the spec uses.
- **Two `ConventionTest` rules fire the instant `feature/book/src/androidTest` exists** — the
  emulator-job list and the fast-tier compile list are both derived from the tree. T9 must edit
  `e2e.yml` and `pr.yml` even though the spec assigns that to T10. A third rule needs a
  `coverageFloors` entry the instant `include(":feature:book")` lands.
- `Replay30`/`Forward30` live in `material-icons-extended`, which the spec bans. Choose
  `Icons.Default.Replay`/`FastForward` or vector resources; the content descriptions are
  contractual with T10's journeys.

### Plan 4 Task 10

- **`MIGRATION_4_5` does not exist.** The database is at `version = 7` and the only migration
  object is `MIGRATION_6_7`.
- **`TIMEOUT_MILLIS` does not exist**; it is `private const val JOURNEY_TIMEOUT_MILLIS`, and
  `reachLibraryScreen` is `internal`.
- **The spec's `connectController()` deadlocks.** It nests `runBlocking { controller() }` inside
  `onMain`; `PlaybackJourneyTest.kt:117` documents that `controller()` hops to main itself and
  must be called from the test thread.
- The `e2e.yml` snippet is stale: the job already runs 10 modules at `timeout-minutes: 90`, and
  the `probe-chapters.sh --check` step is already in both workflows.

### Plan 5

- **`PlaybackState` has 14 constructor parameters, not 11** — the spec anticipated `mediaType`
  and `speed` but not `isAudiobook`.
- **`:wear` has no JVM test tier yet** and its build file declares none of the six test
  dependencies T9's listing assumes are present.
- **Neither `Application` class has an `applicationScope`**; T10 must add it.
- **T11's workflow steps are already done** — the Wear journey step, the raised timeout and the
  logcat artifact all landed with T8, and two `ConventionTest` wear rules already exist.
- **A genuine open gap T11 does name:** `pr.yml`'s verify-manifest line is `:app`-only and does
  not cover `:wear`.
- `CredentialStore` is a class, not an interface; T10 needs an interface extracted.

### Plan 6 Task 11

- **Step 2 is already done.** `networkSecurityConfig` is in `forbiddenAttributes` at
  `AndroidApplicationConventionPlugin.kt:120`, pinned by `ConventionTest.kt:830`.
- `pr.yml` has **five** jobs, not four. Still no `cast` job.
- **`FakeRenderer` is in `testFixtures`, not `main`**, and `:app` has no dependency on it.
- **Only 4 of 161 floors are unmeasured** (`:feature:castpicker` ×2, `:feature:settings` ×2),
  not the broad sweep the spec implies.
- **M4 — the casting-picker trust defect — is not in this spec at all.** It exists only in the
  handoff, which merely *records* it against T11. `RendererDirectory.kt:69`'s
  `distinctBy { it.udn }` keeps the first announcement and arrival order is attacker-controlled.
  The spec specifies no fix and imposes no requirement. **Treat M4 as unscoped design work
  (4–8 h) and decide it deliberately, not as a step inside T11.**

### Plan 7

- **`RequestsRepository` has no `search`.** T10's screens need one added, or the
  `LidarrSourceFactory`/`BinderySourceFactory` wired into the ViewModel. Either is an unlisted
  modify that moves floors. Decide before writing the ViewModel.
- **`BinderySource` has `health()`, not `ping()`.**
- **The settings row cannot be added to the settings screen.** `ConventionTest.kt:1281`
  (`the settings slot never learns what is in it`) forbids naming a section there; the row must
  be a Hilt-multibound `SettingsSection` contributed *from* `:feature:requests`. Both branches
  the spec offers are wrong.
- **T11's live-task registration will fail as written.** It copies a `the<SourceSetContainer>()["test"]`
  pattern that exists only for `muplay.jvm.library`; `:integrations:lidarr` and `:bindery` are
  `muplay.android.library`, and this raises *"Extension of type 'SourceSetContainer' does not
  exist"* — the very error the surrounding comment records. Must be re-derived from the AGP
  unit-test component. **This is T11's real risk, not the containers.**
- **The containers are mostly free.** `lidarr-capture` is **running now**
  (`lscr.io/linuxserver/lidarr:3.1.0.4875-ls40`, port 8686, `/ping` OK) and is **already seeded
  exactly as the spec's configure script would leave it** — root folder `/music` accessible,
  three quality profiles. Bindery's image is pulled (`ghcr.io/vavallee/bindery:v1.32.1`) but no
  container exists and **its API-key mechanism is unknown — the spec says so itself.**

## 6. Order of work

Sequential, one lane, each landing on master before the next starts.

1. **P4 T9 `:feature:book`** — in the four pieces recon identified, each its own commit:
   (a) module skeleton + `settings.gradle.kts` + placeholder floor + **both workflow lines**,
   which keeps master green on its own; (b) `BookPlaybackLauncher` + the three UiState types +
   all four JVM suites — pure, no Compose, no emulator, highest value per hour; (c) the
   cover-art decision as its own commit; (d) screens + ViewModels + `:app` navigation + the
   two Compose tests + real floor measurement.
2. **P4 T10** — the journeys that guard what T9 just built.
3. **P7 T10 `:feature:requests`**, after settling the search path and the `SettingsSection` route.
4. **P5 T10 `WatchLink`** — fully verifiable here.
5. **P6 T11** casting gates (M4 held separately as a decision).
6. **P7 T11** integration gates — Bindery stand-up and the live-task registration first, since
   both are the risk.
7. **P5 T9** watch surface — JVM tier and compilation only; journeys and floors deferred to CI.
8. **P5 T11 / P8 T10 / P8 T6** — the remainder, each explicitly partial where §4 says so.

## 7. Cross-cutting gaps not in any task

- **The device tier's order-dependent flakes are unfixed.** Two tests want the state they
  depend on made explicit in their own `@Before`; `CLAUDE.md` records both mechanisms. Cheap,
  and it removes the standing "a single red is not evidence" caveat.
- **`:feature:castpicker`, `:feature:settings` and `:integrations:requests` device suites have
  never been run here**, though they are now in both workflow lists.
- **`~/.gradle` is ~28 GB on `/`.** Not urgent — `/` has 142 GB free after pruning eleven
  merged worktrees — but `/mnt/data` has 346 GB and is the better home.
- **`org.gradle.workers.max` was 2, measured against a host at 30–44% steal.** Re-measured
  today: `st=0` on five consecutive samples, `id` 90–96%, load 2.15 on 24 vCPUs. Raised to 5,
  with the reasoning and the re-measurement recorded in `~/.gradle/gradle.properties`. **This
  was itself the stale-measurement defect, living in a config file.**

## 8. What "finished" should mean

Not "all 82 tasks merged". The honest definition, in priority order:

1. Every stated user requirement is **reachable from the app**. Today exactly one is not:
   audiobooks. That is item 1 above and it is why it outranks everything.
2. Every claim the gates make is **measured**, and every floor comment records a falsification
   that was actually run.
3. Anything that cannot be verified on this host is **named as unverified** rather than
   quietly asserted — the Wear surface being the live example.
