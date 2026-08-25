# Handoff — MuPlay, end of the 2026-08-25 fleet session

Master is `e5a4d79`, green (`check verifyNoMockFrameworks`), pushed, androidTest
compiles. **30 merges landed this session.** The app went from "cannot play
audio" to playing in the background with a session, notification, lock-screen
controls, a media cache, a player UI and proven gapless playback.

## Where the plans stand

| Plan | Merged | Notes |
| --- | --- | --- |
| 3 — playback core | 10 of 12 | 8b blocked on 6 + the keystone fix; 11 and 12 blocked on 8b |
| 6 — Sonos/DLNA casting | 3 of 12 | codec, SSDP, SOAP in; DIDL-Lite in flight |
| 5 — Auto/Wear | 2 of 11 | `BrowseId`, browse tree in; surfaces in flight |
| 7 — integrations | 1 of 11 | `:integrations:core` in; credential store in flight |
| 4 — audiobooks | 0 of 10 | fixtures in flight |

## Eight lanes were live when the session ended

All have **committed work in their worktrees** under `.claude/worktrees/`, all
had merged master, none had written its report yet. Resume by sending each agent
a message, or read the worktree and finish it directly.

| Worktree | Task | State |
| --- | --- | --- |
| `p3t6` | P3 T6 audio focus, `startIndex`, wake lock | 8 commits, clean — closest to done |
| `p3t5fix` | Keystone security: `onConnect`, controller race, entry points → `src/debug/`, `build-logic` test source set | 7 commits |
| `p3t10` | P3 T10 the gates (Tier 2 journey) | 3 commits |
| `p6t4` | P6 T4 DIDL-Lite, three-way MIME invariant | 5 commits |
| `p6t2fix` | SSDP security: DNS-in-read-loop, XXE 4 KiB blind spot, depth cap | 3 commits |
| `p5t3` | P5 T3 `BrowseSurfaces` / `SurfaceResolver` | 2 commits |
| `p4t1` | P4 T1 audiobook fixtures | 2 commits |
| `p7t2` | P7 T2 `KeystoreKeys`, integration credential store | 1 commit |

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
