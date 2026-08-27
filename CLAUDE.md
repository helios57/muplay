# Working notes for agents in this repository

Short, hard-won facts that cost real time when they were unknown. Add to this
file when something costs you more than a few minutes to discover.

## `adb` is not on `PATH`

It lives at `/home/helios/Android/Sdk/platform-tools/adb`.

This is not cosmetic. A reviewer concluded it had no emulator available, marked
the **entire instrumented tier unverified**, and accepted those measurements
from the report it was reviewing — for a task whose most important fix lived on
exactly that tier. A later reviewer found the binary, ran the suite, and got
106/106. Before reporting that a device tier cannot be verified, check this path.

## The emulator and the Navidrome container are shared and single-instance

One emulator (`muplay37`) and one container (`ci-navidrome-1`) serve every agent
working here at once. On device-busy, **wait and retry**. Never kill the
emulator, never start a second, and never stop, restart or reseed the container
— another agent's live suite may be mid-run against it.

## `check` does not compile androidTest, so master can carry a broken device tier

`./gradlew check` builds and runs the JVM tier and lints. It does **not** compile
`src/androidTest`. So two lanes can each be green on their own branch, green
together under `check` after merging, and still leave master unable to build a
single instrumented test.

Measured: `GaplessTest` called `RealTrackBytes.client().streamUrl(...)` while a
concurrent lane changed that helper's shape. Both merged green. Master then
failed `:core:media:compileDebugAndroidTestKotlin` with *"Expression 'client' of
type 'SubsonicClient' cannot be invoked as a function"* — invisible to every gate
until somebody ran a device suite.

After merging anything that touches a shared test helper, run:

    ./gradlew compileDebugAndroidTestKotlin

for the modules involved. It is fast, it needs no emulator, and it is the only
cheap check that sees this class of break.

## Two concurrent instrumented runs corrupt each other's results

Both `:app:connectedDebugAndroidTest` runs install the same `applicationId`
(`app.muplay`). When a second agent starts one mid-run, the first is reinstalled
underneath itself and its report shows `<failure></failure>` plus `Process
crashed` **with no stack trace** — indistinguishable from a real product crash.

Two runs were lost to this before `logcat` showed the other agent's journey
starting inside the victim's window. If you see a crash with no stack trace,
check `logcat` for another test class starting before you believe it.

Serialise device runs across agents, or wrap each in wait-and-retry and confirm
no other instrumentation is running first.

## Serialise device runs through `ci/device-lock.sh`

The section above says to serialise device runs across agents. Do it with this:

    ci/device-lock.sh ./gradlew :core:media:connectedDebugAndroidTest

It takes an exclusive kernel lock, so runs queue instead of overlapping, and it
tells you who holds the device while you wait. Exit **75** means the wait ran
out and *nothing was measured* — never report that as a test failure.

**Build outside the lock.** Compiling needs no device, so wrapping the whole
Gradle invocation holds the emulator hostage through a build every other agent
then waits on. Assemble first, unlocked, and lock only the install-and-run:

    ./gradlew :core:media:assembleDebugAndroidTest :core:media:assembleDebug
    ci/device-lock.sh ./gradlew :core:media:connectedDebugAndroidTest

The second command finds its build up to date, so the critical section shrinks
to install plus execution. While iterating, narrow it further to the class you
are working on:

    ci/device-lock.sh ./gradlew :core:media:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=app.muplay.media.MediaCacheTest

Run the module's full connected suite once, at the end, before you report.

The lock is held on an open file descriptor, so a killed agent releases it. Do
not replace it with a PID file, and do not add a liveness check that parses one.

## Media3's `@UnstableApi` is invisible to the Kotlin compiler

It is an `androidx.annotation.RequiresOptIn`, not a `kotlin.RequiresOptIn`, so
Kotlin compiles a use of it clean and `check` then fails much later at
`lintDebug`. Opt in at the module level. `CacheDataSource`, `SimpleCache` and
`MediaSessionService` are all annotated, so any task touching them meets this.

## `ExoPlayer.Builder` has no `setLoadErrorHandlingPolicy`

The retry policy attaches to the `MediaSource.Factory`, not the player builder.
Forgetting it is **silent**: the player quietly keeps Media3's default three
retries in five seconds while every policy unit test stays green. Whoever
builds the shipping player owns wiring it, and the test that proves it wired
has to observe request counts, not the policy object.

## `mockwebserver3-junit5` breaks the androidTest resource merge

It ships a second `META-INF/LICENSE.md` and fails
`mergeDebugAndroidTestJavaResource`. Use the `mockwebserver3` catalogue alias
without the JUnit 5 artifact.

## Wait in bounded foreground commands, not on watchers

Agents here have repeatedly parked waiting for a monitor or background-task
notification that never arrived, while the work they were waiting on had either
finished or died. Run a bounded command and read its exit status yourself.

Two watchers have failed in this exact way: a `pgrep -f mutation-probes` that
matched its own command line and so could never report "finished", and a PID
file containing the literal text `PID=12345` rather than the bare number, which
made every liveness check structurally incapable of reporting "alive".

**Bound the tool's timeout, not just the shell's.** A `timeout 900 ...` inside a
harness call that itself caps lower is killed by the harness, and a killed
mutation run's `finally` never reverts — leaving a stray mutation the next
run's dirty-tree guard blames on whoever comes along. Three agents hit this on
the same afternoon; one of them wrote this paragraph and then hit it again.

**The harness tool timeout maxes out at 10 minutes.** So a shell `timeout` above
that is a lie you tell yourself: the call dies at ten minutes regardless. A full
probe sweep takes far longer than that. Run anything longer in the background
(`run_in_background`), which survives across turns and reports its exit status,
or split it into filtered runs that each finish inside the cap. Either way,
check `git status` immediately after — the dirty-tree guard is the backstop, and
it works, but it blames the next agent rather than the one who left the stray.

## A subagent's `.output` file mtime is not a liveness signal

The per-agent transcript under the session's `tasks/` directory is flushed at
its own cadence, not on every tool round. Measured here: five lanes showed
`.output` untouched for ~36 minutes while every one of them had written source
files 21-88 seconds earlier, and one had committed 8 seconds earlier. A
controller watching those mtimes concluded twice that healthy agents had
stalled.

Use signals the work itself produces: `git -C <worktree> log -1 --format=%ct`,
and the newest source-file mtime in the worktree. Those move when the agent
moves.

This is the third liveness check in this repository that could not report
"alive" — after a `pgrep` that matched its own command line and a PID file
holding `PID=12345` instead of a bare number. The pattern is always the same:
the check returns a falsey value for both "dead" and "I cannot tell", and the
reader takes it for "dead". Prefer a check that fails loudly when it cannot
observe its subject.

## Only one test class may build the shipped DataStores, and it fails at *use*

`DataModule.provideCastDataStore` and `provideCredentialDataStore` each open a file.
DataStore refuses a second instance over the same file **in the same process**, and
it throws when the store is first *used*, not when it is constructed — so a new
`androidTest` class that injects one looks fine until an assertion runs, and the
failure names DataStore rather than the second instance.

`DataModuleTest` holds both behind a companion `by lazy` for this reason. If you
need a shipped DataStore in an instrumented test, put the test there rather than
building a second one somewhere new.

## `ci/mutation-probes.sh` is a regression list, not a rule

Read its header before changing it. Its probe count is derived at runtime and
must stay derived — a hardcoded total has gone stale twice. It refuses to run on
a dirty tree, and that guard has caught a real stray mutation left behind by a
killed run.

## A worktree inside the repo used to redden `:app`'s mock-framework guard — fixed

`ConventionTest`'s `no mock framework is declared in any build file or
convention plugin` walks the whole repo root. It used to skip only `build/` and
`.git/`, and its one carve-out — the root `build.gradle.kts`, which has to name
the frameworks in `BANNED_MOCK_GROUPS` — is matched by *canonical path*. So a
git worktree checked out **inside** the repo (`.claude/worktrees/<name>/`)
brought its own copy of `build.gradle.kts`, which is not the carved-out file,
and `:app:testDebugUnitTest` failed on a build file nobody had written.

**`.claude` is now in the `onEnter` skip list, so this no longer happens** — in
the main repo with worktrees present, and from inside a worktree (where the
worktree *is* the repo root and contains no nested ones). Left here because the
warning outlived the defect: it was repeated into five lane briefs after the fix
had landed, telling each of them to expect a failure that could not occur. If
you are about to tell an agent to expect a known-benign failure, check first
that it is still known and still occurs.

## Five-second fixtures let time pass a test that its own defect should fail

Three device tests in Plan 3 were green against the very mutation they existed to
catch, all for one reason: the fixtures are 5 s long, and any assertion that
*waits* for a state can be satisfied by playback simply reaching that state on its
own.

The sharpest case: a `startIndex` test awaited `state.mediaId` after starting a
queue at index 1. With `setMediaItems(items, 0, 0L)` — the defect — the queue
started at track 0 and **played into track 1 inside the wait**. Green. The fix was
to read the index back with **no wait at all** (`MediaController` masks
`setMediaItems` synchronously), then re-read after a second of real audio.

So: on this fixture set, prefer an observation that is true *immediately* over one
you wait for. If you must wait, make the waited-for state one that playback cannot
reach by itself within the fixture's duration. A longer seeded track would kill
this whole class of defect, and is worth considering when the fixtures are next
regenerated.

## Navidrome caches transcodes, so a fixed-bitrate transcode assertion is flaky

`/rest/stream` with `format=mp3` behaves two different ways for the same URL.
The first request for a given (track, **requested** bitrate) is streamed live:
chunked, `Accept-Ranges: none`, no `Content-Length`. Every request after it is
served from Navidrome's transcoding cache as an ordinary file — `Accept-Ranges:
bytes`, an accurate `Content-Length`, `Range` answered with a 206.

The cache lives in the container's writable layer (`ND_TRANSCODINGCACHESIZE`,
100MB by default; this compose file mounts no volume over `/data`), so it is
cold in CI and warm on the long-lived shared container. A test pinned to one
bitrate passes on the first run here and fails on the second.
`LiveNavidromeTest.coldTranscode` searches for an unused bitrate instead.

Two more measured facts from the same investigation. First, **below the source
bitrate** the cache is keyed on the bitrate *as requested*, not as encoded (24,
25 and 26 produce identical bytes and occupy three cache entries) — that is the
regime `coldTranscode` searches in, and only that regime. **At or above the
source bitrate** the rule does not hold: Navidrome selects "no cap" and every
such request shares one entry (a `maxBitRate=63` request on the 32 kbps
audiobook was served from the entry an earlier `maxBitRate=320` request
created). Second, `format=mp3` on an `mp3` source with a cap at or above the
file's own bitrate returns the source file untouched — so `StreamFormat.Mp3(192)`
is not always a transcode.

**A `HEAD` warms that cache, and there is therefore no safe way to search for a
cold entry.** Measured in Plan 6 Task 6: `HEAD` on an uncached transcode answers
`Accept-Ranges: none` with no `Content-Length` — it reports "cold" correctly —
and starts a background transcode that has populated the cache about a second
later. So a probe that finds a cold entry has warmed the entry it found. Search
*through the thing under test*, so the search's own GET is the response you
assert on and there is no second request to race.

### Never delete the transcoding cache files. That is what breaks `coldTranscode`

`docker exec ci-navidrome-1 sh -c 'rm -rf /data/cache/transcoding/*'` was
recorded here as a safe diagnostic. **It is the opposite.** Navidrome keeps an
in-memory index of that cache; deleting the files underneath a running server
leaves every key pointing at a file that is gone, and each one then answers,
*forever*:

    200  Content-Type: application/json  (~292 bytes)
    {"...":{"status":"failed","error":{"message":"Internal Server Error:
     open /data/cache/transcoding/xx/yy/...: no such file or directory"}}}

Measured: permanent, not a transient race — four retries each of seven poisoned
bitrates gave 28 errors and no recoveries. Proven causal in **both** directions:

- Deleting **one** file for a known-warm key sent that key alone from
  `Accept-Ranges: bytes` to the error document, and it stayed there.
- **Restarting the container heals every poisoned key.** After an unrelated host
  reboot restarted `ci-navidrome-1`, all **8 of 8** bitrates that had been
  permanently dead came back as live transcodes. The cache *files* live in the
  writable layer and survive a stop — 200 entries were still on disk — but the
  poison never lived on disk. It is the in-memory index, so a restart is the
  repair and a file deletion is the injury. Do not reason about this cache from
  what is on disk; the two disagree exactly when it matters.

So: *do not flush.* If a container is already poisoned, a plain restart fixes it
without a recreate or a reseed.

Note the third state. `Accept-Ranges` is `none` for a live transcode, `bytes`
for a cache hit, and **absent entirely** for this. A predicate of
`header("Accept-Ranges") == "none"` reads the error as "already cached, keep
looking" — which is how the old search walked all 63 bitrates and failed
blaming cache *exhaustion*, and how "recreate the container" got written down as
the diagnosis for what was really "somebody flushed the cache".

### `coldTranscode`'s "one run in three" was which track got drawn

Not a probability and not a race. The old body took
`getRandomSongs(...).first()` — one of the three music fixtures — and after the
flush the census was **63 of 63** bitrates unusable on Track 1, 6 of 10 sampled
on Track 2, 4 of 10 on Track 3. Drawing the dead track is a certain failure and
the other two are near-certain passes: a weighted coin that reads exactly like a
race. Neither half of the test was ever racing the transcoder — the searching
GET's own response is what the cold half asserts on, and over **54** freshly
cold keys the cache-hit re-fetch came back seekable **54** times.

The search space is now the whole music library crossed with the bitrate range,
so one unusable track cannot decide a run, and the failure message reports the
LIVE/CACHED/UNAVAILABLE census so a red is diagnostic. Measured after the fix:
**10 consecutive green runs** of `:core:network:` and `:core:cast:liveNavidromeTest`
(35 tests each) against the warm, partially-poisoned shared container — no flush,
because flushing is the defect.

`:core:cast`'s `LiveNavidromeProxyTest` remains the cheaper pattern where it
fits: it gets a real `Content-Range`-less response by stripping the credentials
from a stream URL, which needs no cold entry at all.

## A fresh worktree has no `local.properties`, and the failure names the wrong thing

`local.properties` is gitignored, so `git worktree add` does not bring it. Every
pure-JVM task still works (`:core:model:test` is green), and then the first
Android-module task fails with *"SDK location not found"* naming the worktree's
own missing file.

Create it before running anything wider than one JVM module:

    printf 'sdk.dir=/home/helios/Android/Sdk\n' > local.properties

The reason this is worth a note is what it looks like through
`ci/mutation-probes.sh`. That script only reports `run_suite(): no test results
were written for ['core/network', 'core/model', ...]` — every module at once,
including the pure-JVM ones that have nothing to do with the SDK — because
`:core:database`'s configuration failure aborts the whole invocation before any
task runs. It reads like the probe list is broken. Run the script's own gradle
line by hand and the real message is the first thing printed.

## A lane's report describes master as it was at that lane's last sync

Plan 6 Task 4 reported, correctly and usefully, that *"the brief's claim that
`isAudiobook` was already a fourth parameter is wrong about master"*. It was
right when that lane last merged master up, and wrong by the time it reported:
Plan 3 Task 6 had landed `isAudiobook` in between.

Both lanes were green alone. Both added a **fourth parameter to
`MediaItems.of`** at the same source line, so git conflicted and the collision
was visible. It was luck that they collided textually — had one added its
parameter a few lines away, `ort` would have merged both signatures cleanly into
a five-argument function while leaving every call site passing four, and the
break would have surfaced as a Kotlin error attributed to neither lane.

Two things follow, and both cost time here:

- **Re-verify any claim a lane makes about master before acting on it.** The
  claim is a measurement with a timestamp, not a fact. Check what the lane's
  last merge commit actually was (`git log --oneline master..<branch>`).
- **When two lanes extend the same function, the resolution is usually "both".**
  `isAudiobook` decides `mediaType`, `format` decides `mimeType`; neither is
  derivable from the other. Reading the merged body first is what shows this —
  it already called *both* new parameters while the signature declared one.

## A recorded floor falsification goes stale when a second caller appears

Coverage floors here carry a comment recording *how the floor was falsified* —
"withhold these two tests and it drops to 0.88". That record is a measurement
with a timestamp, and a new caller **in another module** silently invalidates it.

Measured, twice in one task: withholding `XmlTextTest`'s two `unescape` tests
left `XmlText` at **12/12 and green**, because Task 4's DIDL round trip had since
become a second caller and covered the same lines. The comment at that floor had
*predicted* exactly this and nobody re-ran it. Withholding the three tests
recorded against `SoapEnvelope` left it at 32/34 = 0.9412, also green; six
withheld tests were needed to fire it.

So the floor was still enforcing something — but the thing the comment claimed
would break it no longer would, which means the next person to trust that comment
learns nothing when they withhold those tests and see green. It is the
assertion-that-cannot-fail class, aimed at the gates rather than at the product.

**Re-run the falsification when you touch a floor, and correct the comment when
the number moves.** Do not copy a recorded falsification forward; it is evidence
of one past run, not a property.

## Backticked test names do not reach the device tier at all

This project's JVM tier uses `` fun `a name with spaces`() ``. On the **device** tier that fails
the build outright:

    :integrations:core:dexBuilderDebugAndroidTest FAILED
    D8: Space characters in SimpleName 'the provided clock reads the wall clock in utc' are not
    allowed prior to DEX version 040 (method name ... on class ...IntegrationsDataModuleTest)

`minSdk 26` compiles DEX 035, which forbids spaces in any SimpleName — method or class. Both were
measured here; there is no lambda-free exception.

The variant that costs the time is a backticked `runTest`, because Kotlin names the lambda's
synthetic class after its enclosing method and D8 then reports a **class** nobody wrote:

    Space characters in SimpleName 'app/muplay/integrations/MediaRequestRepositoryTest$setStatus
    round-trips a status that carries data$1'

Every instrumented test class here is camelCase for this reason. Expect plan documents written
from the JVM tier's habits to get this wrong, and expect to rename the whole class at once.

## A module that newly applies `muplay.android.room` needs `schemas/` to exist first

`AndroidRoomConventionPlugin.RoomSchemaArgProvider` declares `<module>/schemas` as an
`@InputDirectory`, and Gradle refuses to *configure* the KSP task against a directory that is not
there — so the failure arrives before any compilation and does not mention Room:

    A problem was found with the configuration of task ':integrations:core:kspDebugKotlin'
    Input file does not exist ... property 'commandLineArgumentProviders.$1.schemaDir'
    specifies directory '.../integrations/core/schemas' which doesn't exist

`mkdir <module>/schemas` once. The first successful build fills it and the JSON is committed, so
only the module that introduces a database ever meets this.

## `verifyReleaseNoDestructiveMigration` reads comments too

`VerifyNoDestructiveMigrationTask` is a plain `contains` over the whole file text. A KDoc sentence
explaining that a provider deliberately does *not* call the destructive-migration escape hatch
fails `check` on its own prose, naming that file as an offender. Describe the hatch without writing
its method name — the same discipline `AndroidRoomConventionPlugin`'s own header keeps around the
banned build tool's name, and the second time this repository has cost someone a build over a
comment.

## Worktrees share one Gradle build cache, and it serves cross-worktree results

Every worktree under `.claude/worktrees/` uses the same `~/.gradle/caches/build-cache-1`.
Measured in Plan 6 Task 6: `./gradlew check` in one worktree failed at
`:core:media:lintDebug` **naming a file that existed in neither that worktree nor
master** —

    .claude/worktrees/p3t8b/core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt

— a replayed failure from *another lane's* tree. The same run printed a stale
`COVERAGE: :core:cast … 8 coverage floors` when the real number was 10.

Both directions of that are bad. A replayed **failure** sends you debugging a file
you cannot see. A replayed **success or notice** is worse, because that is a gate
reporting on a tree it never looked at — the class of defect this repository
exists to keep out of its own gates.

`--no-build-cache` was green on the identical tree, so the cache entry, not the
code, was wrong.

**While more than one worktree is live, gate with `--no-build-cache`.** It costs
about a minute on a warm daemon. Use it for the run whose result you are going to
act on — a merge gate, a floor measurement, a falsification — and let the cache
speed up ordinary iteration. If a failure names a path outside your own tree,
suspect this before you suspect your change.

## The emulator job's module list is hand-written, and it silently omitted two

Plan 3 Task 10 measured that `.github/workflows/e2e.yml` ran
`:core:database`, `:core:media` and `:app` — and **not** `:feature:player`'s 24
instrumented tests, nor `:integrations:core`'s 17. Both modules had a working
device suite that CI had never once executed.

**The coverage gate could not have caught it, and that was measured rather than
assumed.** A floor marked `requiresInstrumentedData` is enforced by
`jacocoTestCoverageVerification`, which runs *in the emulator job* — so a module
missing from that job's command line has its floors skipped by the same omission
that skips its tests. The gate and the thing it gates fail together, silently.

`ConventionTest`'s `every module with instrumented tests is run by the emulator
job` now holds that command line against the repository: any module with a
`src/androidTest` source set must appear in it. It found the second module by
itself, after the author had already fixed the first by hand.

The general shape, which has now cost this repository three separate gates: **a
list written by hand in one file, describing something discoverable from the
tree, drifts and nothing notices.** When you find one, do not just fix the list
— derive it, or assert it against what it claims to describe.

## Neither the emulator nor the container survives a session restart

Measured 2026-08-27, after the parent Claude Code process exited: `adb devices`
was empty and `docker ps` showed no `ci-navidrome-1`. Both looked destroyed.
Neither was.

- **The container had `Exited (0)`, not gone.** `docker start ci-navidrome-1`
  brought it back healthy in seconds, and because the restart is not a recreate,
  its **writable layer survived** — the seeded library still reported all 9 items
  and the transcoding cache was still populated. Note what does *not* survive, and
  is the one good thing about this: the transcoding cache's **in-memory index**
  is rebuilt from the files that are actually on disk, which *repairs* a cache
  poisoned by a previous file deletion. Measured across this very restart: 8 of 8
  permanently-dead bitrates came back. See "Never delete the transcoding cache
  files" above — on-disk state and served behaviour disagree exactly here.
- **The emulator's `qemu-system-x86_64` was still running**; it was the *adb
  server* that had died with the session. `adb start-server` found the device
  `offline`, and it reached `device` and then `sys.boot_completed=1` about a
  minute later. The AVD is still `muplay37`.
- **The app was no longer installed**, so the next `connectedDebugAndroidTest`
  pays a full install.

So the recovery is `docker start ci-navidrome-1` and `adb start-server`, then wait
for `sys.boot_completed`. It is restoration, not the recreate-or-reseed this file
forbids, and nothing is lost.

Why this is worth a section: for about ten minutes every device and live suite
failed with a connection error, and five resumed lanes were about to read those
failures as defects in their own branches. That is the same shape as the reviewer
who marked an entire instrumented tier unverified because `adb` was not on
`PATH`. **Before concluding that a tier cannot be verified — or that a test you
just wrote is broken — check that the emulator and the container are actually
up.**

## A release build *can* do cleartext HTTP — to `localhost`, and only there

`app/src/androidTest/.../FirstRunJourneyTest` says "Cleartext HTTP is allowed only
because this is the debug build". That is not what the platform does, and the
difference matters for anything that reasons about the release variant.

Measured in Plan 8 Task 2, on the **minified, release-signed** APK (no
`usesCleartextTraffic` in its merged manifest — `verifyReleaseManifest` proves
that on every `check`), same install, same run, same credentials, minutes apart:

| Server URL entered            | Result                                    |
|-------------------------------|-------------------------------------------|
| `http://10.0.2.2:4533`        | "Could not reach the server."             |
| `http://localhost:4533`       | connected, library synced, audio played   |

`10.0.2.2` is the emulator's alias for the same host loopback the `adb reverse`
forward reaches, and the same Navidrome answers on both, so the difference is not
the network. It is Android's **default** network security config for
`targetSdk >= 28`, which sets `cleartextTrafficPermitted="false"` in its
`base-config` and then adds a `domain-config` that permits it for `localhost`.
Every app on this target level has that carve-out and cannot opt out of it from
the manifest.

Two consequences:

- `verifyReleaseManifest`'s guarantee is "no cleartext **to a remote host**", not
  "no cleartext". Read the gate's name accordingly; it is still the right gate.
- Plan 6's on-device cast proxy serves from `localhost`. It will therefore work in
  a release build without any manifest change — which is convenient, and is also
  the reason nobody will notice if the cleartext gate is later weakened to make it
  work. It already works.

It is also what makes a release build drivable against the CI container at all:
`adb reverse tcp:4533 tcp:4533` plus `http://localhost:4533` is the only way to
put a real library in front of a release APK on this emulator.

## A revert built on `git checkout --` destroys uncommitted work

Plan 6 Task 5 lost about forty minutes and four finished tests to its own
falsification harness: the harness applied a mutation, ran the suite, and
reverted with `git checkout -- <file>`. That is correct for the mutated file and
catastrophic for everything else the author had not committed yet — `git
checkout` does not distinguish "the mutation I just made" from "the test I wrote
twenty minutes ago and have not committed".

`ci/mutation-probes.sh` avoids this by refusing to run on a dirty tree at all,
which is the same guard from the other side. A hand-rolled harness needs the
same discipline:

- **commit before you mutate**, so the revert has a clean baseline to return to,
  or
- snapshot the file's bytes in memory and write them back, rather than asking git.

The same rule applies to clearing a stray mutation by hand. `git checkout -- .`
is safe only once you have looked at `git status` and confirmed the *only*
modified file is the one you mean to revert. Read the diff first; it costs one
command and it is the difference between undoing a mutation and undoing an hour.

## This host is shared with the user's own work — build like a guest

Measured 2026-08-27 while six agent worktrees were building: **load average 32.6
on 24 cores**, alongside the user's IntelliJ (3.6 GB resident) and several
concurrent `rustc`/`rust-lld` processes. Memory was never the problem — 34 GB
available, swap barely touched, **zero OOM kills in the kernel log**.

Two settings live in `~/.gradle/gradle.properties` rather than the repo's, because
CI reads the repo's and these numbers are wrong there:

    org.gradle.workers.max=3
    org.gradle.priority=low

`org.gradle.parallel=true` in the repo lets **each** build take one worker per
processor, so N concurrent worktrees ask for N x 24. Capping workers matches the
machine rather than the process; `priority=low` makes the daemons yield to
interactive work instead of competing with it. After both, load fell to 24.
Run `./gradlew --stop` after changing either — a running daemon keeps its old
settings and will not pick them up.

**Disk is the risk that is not yet fixed.** `/` was at **94% (26 GB free of
393 GB)** during the same window, and under that load `du` and `docker system df`
both exceeded a 100-second timeout, which is itself the symptom. A full disk on
this host produces exactly the unexplained mid-build failures that get
misattributed to the code. Check `df -h /` before blaming a flaky build.

And note what a **host reboot** looks like from inside a session, because it has
happened here: every agent stops at once with no completion record, the emulator's
qemu process is gone, and the container shows `Exited (0)` — a clean stop, not a
crash. `last reboot` distinguishes it from anything you did in one command.

## A plan's sample code does not necessarily pass the plan's own sample tests

Plan 6 Task 7 shipped both halves in the plan document: a `CastRouterTest` and the
`CastRouter` meant to satisfy it. Three of the listed tests could not pass against
the listed implementation, and none of the three was a typo:

- a test required the failure message to name the device, and the function that
  builds that message was given no device and no way to reach one;
- two tests exercised a "fast path" constructor parameter that **nothing in the
  listing ever called** — it was declared, defaulted, and dead;
- one assertion was arithmetically false: `10.0.1.20` and `10.0.2.50` really are
  in the same `/22` (`10.0.0.0/22` spans `10.0.0.0–10.0.3.255`), and the listing
  asserted they are not.

All three surfaced within one run of writing the code and running the tests, and
none of them is visible by reading. So: **type the plan's tests in first and run
them before believing either half.** A red there is as likely to be the plan as
the implementation, and the third case above — an expectation that is simply
wrong about the world — only ever surfaces as a failure against *correct* code.

Related: a defaulted constructor parameter no caller omits compiles to a second,
synthetic constructor that no test can reach, and it measures as permanently
uncovered lines. `CastRoute.Proxied` read LINE 5/6 with the default and 5/5
without it. If a floor is a line or two short on a `data class`, look for a
default before looking for a missing test.

## An IPv6 address interpolated into a URL silently produces a URL with no host

Measured on JDK 21, and it is the whole reason `CastRouter` has a `urlHost`:

    URI("http://fd00:0:0:0:0:0:0:1:8080/m/x.mp3").host   ->  null
    URI("http://[fd00:0:0:0:0:0:0:1]:8080/m/x.mp3").host  ->  "[fd00:0:0:0:0:0:0:1]", port 8080

`InetAddress.hostAddress` never brackets, and for a link-local address it appends
this machine's own scope id (`fe80::1%7`), which means nothing to a peer. So
`"http://$host:$port$path"` over an `InetAddress` is correct for IPv4 and produces
an unfetchable URL for IPv6 — with no error anywhere, on a test bed that is
loopback IPv4 and therefore cannot see it.

Note also that `URI.getHost()` **keeps the brackets** for an IPv6 literal, and
`InetAddress.getByName` accepts them, so a host string round-trips; it is only
string interpolation that breaks.
## Kotlin block comments **nest**, so a glob in a KDoc can eat the rest of the file

`/*` inside a `/** ... */` opens a nested comment. Writing a perfectly ordinary
path in prose is enough:

    /** ... it parses `META-INF/*.SF`, checks the signature ... */

That `/*` opens a comment the closing `*/` then only half-closes, and the
compiler reports **`Syntax error: Unclosed comment`** at the *last line of the
file* — plus, in the same run, seventeen `Unresolved reference` errors in a
**different** file that happened to use the swallowed declarations. Nothing in
that output names the glob. `*/` in prose (`*/src/debug/kotlin/**`) closes the
KDoc early and produces the same shape from the other direction.

Both were hit in one compile while writing Plan 8's release gates. Grep for
`/\*` and `\*/src` inside comments before believing a nonsensical
`Unresolved reference` list.

## `build-logic` is an included build, so root `check` never runs its tests

`settings.gradle.kts` has `pluginManagement { includeBuild("build-logic") }`.
`./gradlew check` at the root builds and tests the eleven project modules and
stops — it does not reach `build-logic`.

Measured 2026-08-27: no workflow file mentioned `build-logic` in any `run:` line,
so **thirteen tests had never executed in CI** — including
`VerifyMergedManifestTaskTest`, whose own header records that it is the only
thing in this repository that goes red when the merged-manifest gate stops
checking. `pr.yml` now runs `./gradlew :build-logic:convention:test` and
`ConventionTest`'s `build-logic's own tests are run by CI` derives the module
list from the tree.

To run them yourself: `./gradlew :build-logic:convention:test` from the root
(the `:build-logic:` prefix addresses the included build), or
`./gradlew -p build-logic test`.

## A `tearDown` that throws replaces the real failure with its own

`GaplessTest`'s `setUp` ends with `server = MockWebServer(); server.start()`. Any
failure before that line — a `check()` on the fixture count, a network refusal —
leaves `server` unset, and `@After tearDown()` then throws

    kotlin.UninitializedPropertyAccessException: lateinit property server has not been initialized
    at app.muplay.media.GaplessTest.tearDown(GaplessTest.kt:115)

which is the **only** message the report carries. The real cause never appears.

Measured 2026-08-27: a corpus change added a fourth music fixture, `setUp`'s
`check(songs.size == TRACK_COUNT)` fired, and four tests reported nothing but the
`tearDown` exception. One lane read those failures and concluded *"`:core:media`'s
audio-sink and disk-cache suites are red on master too"*, which sent the next
reader looking at host disk and emulator storage. Both were fine; the cause was a
hardcoded `3`.

Guard every `@After` that touches a `lateinit` set late in `@Before`:

    if (::server.isInitialized) server.close()

It is one line, and it is the difference between a report that names the defect
and a report that names the cleanup.

## A shared fixture corpus breaks every hardcoded count at once

The container bind-mounts `<repo>/ci/fixtures/Music -> /music` from the **main**
worktree, so a corpus change is visible to every lane the instant the file lands
— not when that lane's branch merges. Adding one Opus fixture took the music
library from 3 tracks to 4 and turned `:core:media`'s device tier red on master
while the lane that added it was still working.

So a count over the corpus must be **derived, not written down**.
`LiveNavidromeTest` does this correctly —
`SEEDED_TRACK_COUNT = BookFixtures.allTrackPaths().size` — and survived the change
untouched. `GaplessTest`'s `const val TRACK_COUNT = 3` did not.

Where a test genuinely needs one *kind* of track, filter rather than count:
`RealTrackBytes.bytesOf` already does `musicTracks().first { it.suffix == "mp3" }`
and was unaffected.
