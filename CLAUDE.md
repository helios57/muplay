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

### Which *declaration* a call resolves to is what decides it, not the method name

Measured on `CastSessionManager` (Plan 6 Task 9), where three calls were flagged and two
identical-looking ones beside them were not:

    incoming.prepare()   // incoming: MuPlayer -> ForwardingPlayer, @UnstableApi  -> ERROR
    local.prepare()      // local:    Player,   stable                            -> fine
    remote.release()     // remote:   UpnpPlayer -> SimpleBasePlayer, @UnstableApi -> ERROR

So "we already call `prepare()` all over this module without an opt-in" is not evidence that a new
`prepare()` needs none. Read the receiver's static type.

This file arrived on master carrying all three errors, **green in its own worktree** and red on the
first `--no-build-cache check` after the merge — which is both this trap and the cross-worktree
build-cache one in the same defect. Annotate with `androidx.annotation.OptIn`, on the declaration,
with a comment naming which member is unstable; that is this module's house style and every other
file in it follows it.

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

**And a floor can be cured by the very commit that was supposed to test it.**
`:app`'s bundle LINE floor was failing at 124/138 = 0.8986 for want of a journey
that navigates to a book. Plan 4 Task 10 wrote that journey — and in the same
commit fixed a navigation defect the journey found, which added six covered lines
to `MuPlayApp`. Measured afterwards: with both new journeys **withheld** the
bundle reads 130/144 = 0.9028, still green. The floor that was meant to be
enforcing the journeys would have passed without them. When a fix and its test
land together, withhold the *test* and re-measure; if the floor still clears, the
floor is not the gate you think it is, and the answer is a narrower rule (there,
a CLASS floor over the two `NavKey`s whose lines only execute when something
pushes them) rather than a comment claiming otherwise.

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

**Swap was the risk that actually bit, and it is now fixed.** Measured 2026-08-27
17:28 with seven worktrees building: the 8 GB `/swap.img` was **100% used**, RAM
available was down to **4 GB**, and `buff/cache` had been squeezed to 3 GB. The
single largest consumer is not a build at all — the emulator's
`qemu-system-x86_64` held **35 GB resident**, which no Gradle setting can reach
and which a restart is forbidden to reclaim.

A 24 GB swapfile now lives on the hot-added raid5 disk at `/mnt/data/swapfile`,
priority 10 so new pressure lands there rather than on the root disk, in
`/etc/fstab` with `nofail`, added live with `fallocate`/`mkswap`/`swapon` and
verified to come back from fstab alone (`swapoff` then `swapon -a`). It is a
safety net, not a performance feature: swapping to a virtio disk is slow, and the
point is that a memory spike degrades instead of OOM-killing somebody's lane.

Check `free -h` alongside `df -h /` before blaming a flaky build, and remember
what the numbers looked like when this was written — `available` under about 5 GB
with every daemon still warming up is the state to act on.

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

## A `Flow` operator inside a `@Composable` is a lint **error**, not a warning

`androidx.compose.runtime`'s `FlowOperatorInvokedInComposition` fails `lintDebug` on

    val x by source.configured().map { it.keys }.collectAsStateWithLifecycle(initialValue = emptySet())

and it is right to: an operator applied during composition builds a new `Flow` on every
recomposition, so the collector is torn down and restarted each time. It compiles clean, every JVM
test passes, and the build goes red much later — the same shape as Media3's `@UnstableApi`.

Hoist the operator to a property of the class that owns the composable (`RendererDirectSection`
collects a flow it does not transform, which is why nothing in `:feature:castpicker` met this).
Measured in Plan 7 Task 10 on `IntegrationsSection`.

## Lint's Android Auto checks cannot see a library module's manifest

`AndroidAutoDetector` switches on the moment an application declares itself an Auto media app
(`com.google.android.gms.car.application` meta-data plus a `res/xml` descriptor whose `uses` names
`media`). It then reports **two errors** that no declaration in this repository can satisfy, because
`MuPlaybackService` is declared in `core/media`'s manifest and the detector reads only the
application module's own manifest sources:

    app/src/debug/AndroidManifest.xml:16: Error: Missing intent-filter for action
      android.media.action.MEDIA_PLAY_FROM_SEARCH [MissingIntentFilterForMediaSearch]
    app/src/debug/AndroidManifest.xml:16: Error: Missing intent-filter for action
      android.media.browse.MediaBrowserService that is required for android auto support
      [MissingMediaBrowserServiceIntentFilter]

Both were measured against a tree where the merged manifest **did** carry the browse action — AGP's
own `app/build/intermediates/merged_manifest/debug/` has it, and `verifyDebugManifest` goes red when
it does not. Adding `android.media.action.MEDIA_PLAY_FROM_SEARCH` to the same service in
`core/media`'s manifest left the first error reported, *unchanged*. So neither check's verdict
depends on the thing it claims to check, and neither can ever go green in this layout.

They are disabled in `app/lint.xml`, which carries the measurement, and
`ConventionTest`'s `no Android Auto lint check is disabled without a named replacement` holds each
disabled id to the gate that took over. Do not "fix" this by copying the service declaration into
`:app`'s manifest: that is a second copy of the thing `core/media`'s manifest header exists to keep
in one place, and lint would then be satisfied by a declaration nothing checks.

Note also the shape, because two rules written on the same afternoon had it: a check that reports
"missing" whatever the truth is reads exactly like a check that found something.

## AGP's merged manifest keeps every source manifest's comments

Measured: `core/media`'s twenty-line comment explaining the browse actions is reproduced verbatim in
`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`.

`VerifyMergedManifestTask` is a plain `contains`, so a comment that quotes the declaration it
explains — which is what a good comment beside `android:name="..."` looks like — used to satisfy the
required half on behalf of a declaration nobody wrote. Proven causally in both directions: with the
browse action replaced by a comment naming it, `:app:verifyDebugManifest` was **BUILD SUCCESSFUL**
before the fix and **BUILD FAILED** after, on the identical manifest.

The **required** half now strips XML comments; the **forbidden** half deliberately does not, because
it is an absence check where over-matching is the safe direction. Both directions are pinned by a
test in `VerifyMergedManifestTaskTest`.

This is the same defect as `verifyReleaseNoDestructiveMigration` reading comments, running the other
way: there prose caused a false *failure*, here it caused a false *pass*.
## An instrumented `fun x() = runBlocking { .. }` is refused before it runs

JUnit 4 requires `void` test methods. Kotlin infers a `@Test`'s return type from the block's last
expression, and **most AssertJ assertions return the assert object** — so the idiomatic
`@Test fun x() = runBlocking { .. assertThat(a).isEqualTo(b) }` is an `AbstractAssert`-returning
method, and the whole class is rejected at load time:

    org.junit.runners.model.InvalidTestClassError: Invalid test class 'app.muplay.media.ChapterRepositoryTest':
    1. Method forgettingAFileMakesTheNextReadParseItAgain() should be void
    2. Method aSecondReadOfTheSameBookIsServedFromRoomWithoutReParsing() should be void
    ...

Four methods named, **none of the five run**, and the surviving one is the misleading part: it ends
in `isEmpty()`, which happens to return `void`, so exactly one test in the class looks fine. Declare
`fun x(): Unit = runBlocking { .. }`. The JVM tier never sees this — JUnit 5 is happy with any
return type — so a plan's sample code carries the defect straight onto the device.

## Anything in `:core:media` that fetches through `MuPlayDataSourceFactory` must set a cache key

`TrackIdCacheKeyFactory.buildCacheKey` **throws** `MissingCacheKeyException` on a `DataSpec` with no
key rather than falling back to the URI — deliberately, and `MediaCacheTest` pins it. So it is not
only `MediaItems.of` that owes `setCustomCacheKey(song.id)`: **every** `MediaItem` handed to
anything built on that factory does, including ones no player ever sees.

Measured in Plan 4 Task 3: `ChapterReader` built `MediaItem.fromUri(uri)` for a `MetadataRetriever`
— no player, no playback, just a read of the file's `moov` atom — and all six of its device tests
died with *"A media request reached the cache with no custom cache key (track Ra14Y8yMKT8YPrtrt6delD)"*,
wrapped in a `Loader$UnexpectedLoaderException` inside an `ExecutionException`. Nothing in that
stack names `MediaItem`. The plan's own listing had the same shape, so expect it.

    MediaItem.Builder().setUri(uri).setCustomCacheKey(mediaId).build()

The alternative — a plain HTTP factory that skips the cache — also skips `RequestedUriDataSource`,
which is what keeps a credential-bearing redirect target out of `exoplayer_internal.db`. Supply the
key; do not route around the guard.

## A cache-backed read makes "requests that did not happen" the wrong signal

Plan 3 Task 3 proved its media cache by counting HTTP requests, and that is the right shape *there*.
It does not transfer to anything layered **above** a `CacheDataSource`, because the byte cache
satisfies the assertion whether or not the layer under test did anything.

Measured in Plan 4 Task 3: delete `ChapterRepository`'s `findScan` short-circuit — so every call
re-parses the file over HTTP — and `assertThat(requests.get() - afterFirst).isZero` still passes.
The second parse read the same `moov` bytes off `SimpleCache`. A request counter cannot distinguish
"served from Room" from "re-parsed from the byte cache".

What worked was a signal the byte cache cannot fake: a `Clock` the test **moves between reads**, and
an assertion on the row's stored timestamp. A re-parse rewrites it; a cached read does not touch it.
If you are asserting that some layer avoided work, check first that the work leaves a trace the
layer below cannot also produce.

## Sibling lanes share one scratchpad directory, and `falsify.py` is not a unique name

The per-session scratchpad (`/tmp/claude-*/<session-id>/scratchpad`) is shared by **every lane the
fleet runs in that session**, not one per lane. Two lanes writing a harness to the obvious name
collide silently.

Measured: this lane's `cat > .../scratchpad/falsify.py` overwrote another lane's file of the same
name, and its `falsify.log` truncated theirs mid-run, destroying a floor falsification they had
spent minutes measuring. Worse, `pgrep -af falsify.py` then showed **two** running processes, which
read as "I somehow launched twice" — and killing the stranger killed *their* harness between
mutation and revert, leaving a stray `@Disabled` in **their** worktree for their dirty-tree guard
to blame on whoever came next.

So: **prefix every scratchpad file with your lane name** (`p6t8-falsify.py`), and before killing a
process you did not expect, check *which worktree* it is running against — the `cd` in its command
line says so.

**A stray `.py` in that directory shadows the standard library.** Running
`python3 /…/scratchpad/tool.py` puts the scratchpad on `sys.path` *ahead of* stdlib, so another
lane's `inspect.py` sitting there was imported instead of `inspect` — by `dataclasses`, by
`apport`, by anything. Measured in Plan 7 Task 10: a one-line bug in my own script produced a
traceback whose visible cause was `zipfile.BadZipFile` raised from
`scratchpad/inspect.py` at import time, naming a file this lane had never seen. Keep scratch
scripts in a lane-named **subdirectory** (`scratchpad/p7t10/cov.py`), which is enough — the path
that goes on `sys.path` is then the subdirectory, not the shared one.

Two more things that harness taught, both cheap:

- **A `nohup … &` inside a foreground harness call does not outlive the call.** The probe run
  launched that way was killed with the call and left a stray mutation. Launch the long command as
  the background command itself (`run_in_background`), not as a `nohup` inside a short one.
- **Install a SIGTERM/SIGINT handler that restores the snapshot**, not just a `finally`. A default
  SIGTERM terminates without running `finally`, which is exactly how a killed harness leaves a
  stray.

## Two concurrent `ci/mutation-probes.sh` runs trip each other's missing-results guard

Nothing serialises probe runs the way `ci/device-lock.sh` serialises the emulator, and two lanes
running the script at once share one Gradle daemon and one build cache. Measured here:
`./ci/mutation-probes.sh session/` aborted with

    run_suite(): no test results were written for ['core/cast', 'integrations/lidarr', 'feature/player']

while another lane was running `./ci/mutation-probes.sh chapters/` in its own worktree. The
script's own Gradle line, run by hand on the identical tree, was BUILD SUCCESSFUL, and re-running
the family alone was 13/13. `run_suite()` deletes each module's result directory to force a re-run,
and the other run's cache entries then satisfy those tasks FROM-CACHE without repopulating them.

Check `pgrep -af mutation-probes` before blaming your own tree, and re-run rather than debug.

## A bare `Metaspace` failure from `./gradlew check` is the shared daemon, not your code

Twice in one afternoon, with several lanes building at once, `./gradlew --no-build-cache check`
failed with three tasks reporting nothing but

    * What went wrong:
    Metaspace

naming `:feature:library:kspDebugKotlin` and two unnamed others. A plain retry was green both
times, on the identical tree. It names no useful task and it is not a compilation error; retry
before investigating.

## An OOM kill here can be a *cgroup* limit, not the host running out

Measured 2026-08-27 22:14, while the host had **tens of gigabytes free**:

    python3 invoked oom-killer ... constraint=CONSTRAINT_MEMCG
    oom_memcg=/user.slice/.../run-rf90b921e0bfa4e9da2b11c6277dbd7c1.scope
    Killed process 2123100 (python3) total-vm:545072kB, anon-rss:523164kB

`CONSTRAINT_MEMCG` and a `run-*.scope` mean a **transient systemd scope with its
own memory limit**, which is what background commands run inside here. So a
script can be killed at a few hundred megabytes on a machine with 29 GB
available, and `free -h` will show nothing wrong afterwards.

The consequences are the same shape as every other silent kill in this file: a
`finally` does not run, a mutation is left in the tree, and the next agent's
dirty-tree guard blames them. So `grep CONSTRAINT_MEMCG` in `dmesg -T` before
concluding that a script died of its own bug, and keep long-running scripts
small in memory — stream rather than accumulate, and write large outputs
incrementally instead of holding them.

Two settings were added to `~/.gradle/gradle.properties` in the same window, for
the *host* side of the same pressure: `org.gradle.daemon.idletimeout=600000`, so
eight worktrees stop holding eight ~2.5 GB JVMs for Gradle's default three
hours, and `kotlin.daemon.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m`, because
the Kotlin daemon otherwise sizes itself from a 61 GB machine that is already
oversubscribed. Do not cut the Kotlin cap further: a too-small one reproduces
the bare `Metaspace` failure recorded above on purpose.

## This VM runs at 30-44% steal, so "24 cores" is a lie the load average tells

Measured 2026-08-27 17:15 with seven worktrees building: `vmstat 2` reported
**`st` between 30 and 44**, `id` between 33 and 46, and `uptime` a load average
of **35 on 24 vCPUs** — all three at once.

That combination is the signature, and it is worth learning to read: high idle
*and* high load *and* high steal together mean runnable tasks are waiting on
physical CPU the hypervisor is not handing over. Idle is high because this
guest's vCPUs are not being scheduled, not because there is capacity. The
effective machine is roughly **13-16 cores**, and `nproc` cannot see it.

So `org.gradle.workers.max` is sized against the *effective* core count, not
`nproc` — it is now **2** in `~/.gradle/gradle.properties`, with the reasoning
beside it. Seven concurrent builds at 3 workers each oversubscribe a 14-core
machine; at 2 they do not.

Two things follow:

- **Check `vmstat` before concluding a build is slow because of the build.** A
  Gradle run that takes three times as long as it did yesterday, on the same
  tree, is more likely to be the host than the code.
- **Change the cap without `./gradlew --stop` while a fleet is live.** A running
  daemon keeps its old value, so the new cap applies to the next daemon started
  — which is what you want. Stopping daemons to make it take effect immediately
  kills whatever every lane is running.

Note also what is *not* the problem, because it was checked: `si`/`so` were both
0 across every sample, so nothing was thrashing. Swap sat at 8.4 GB used and
still, which is a machine that swapped once under earlier pressure and settled.

## A mechanical "keep both" merge resolution splices comments and breaks files

Four lanes tonight conflicted on the same hand-written lists, and `CLAUDE.md`'s own
rule — *"when two lanes extend the same function, the resolution is usually both"* —
is right about the **intent** and dangerous as an **algorithm**. A script that
deletes the markers and concatenates the two sides produced, in one merge:

- a KDoc whose `/**` was on the ours side and whose body continued on the theirs
  side, so the file ended with *"Syntax error: Unclosed comment"* reported at the
  **last line**, naming nothing useful;
- a second KDoc spliced into the middle of a function body, which moved a `@Provides`
  method inside an unrelated class and produced
  `[ksp] java.lang.IllegalStateException: No enclosing TypeElement for: provideCastPickerScope`;
- a `run:` line duplicated, so `pr.yml` had **two** gradle invocations where the
  union of one was meant;
- a Python list literal closed with the wrong bracket, because one side's `(` and the
  other's `]` were both kept.

The last one is the good case: `ci/probe-preflight.py` refused to parse and said so
in 50 ms. The first two cost a compile round each.

So: **union the *elements*, never the *text*.** A conflict whose two sides are both
items of one list (gradle tasks, module names, probe families, import lines) is a set
union and can be scripted. A conflict that cuts through a declaration, a KDoc or a
function body has to be read. Tell them apart before running anything: if either side
is unbalanced in `{}`, `/**`/`*/` or brackets, it is the second kind.

And check the result with something that parses rather than something that greps —
`./ci/probe-preflight.py` for the probe file, a compile for Kotlin. Balanced-comment
counting (`s.count("/**") == s.count("*/")`) found the unclosed KDoc in one command
when the compiler's own message pointed at the wrong line.

## Two lanes' navigation entries merge cleanly into an app that dies on launch

The same merge left `entry<PlayerRoute>` declared **twice** in one `entryProvider`.
Navigation 3 throws at *composition*:

    IllegalArgumentException: An 'entry' with the same 'clazz' has already been added: PlayerRoute

`./gradlew --no-build-cache check` was **fully green** over it — it compiles, and no
JVM test composes the graph — and it surfaced only because a device run crashed with
one test and a stack trace naming Navigation 3 rather than either lane that caused it.

`ConventionTest`'s `no navigation graph registers one route class twice` now scans for
it on the fast tier. Note its one subtlety, which bit on the first run: the rule's own
KDoc *names* the duplicate it was written against, so a raw-text scan reports
`ConventionTest.kt` as the offender. It strips comments first — the same fix
`VerifyMergedManifestTask`'s required half makes, and the same self-matching failure as
the `pgrep` that matched its own command line.

## `scope.cancel()` on a `@Singleton` kills it for the life of the process

`MuPlayLibraryCallback` is a `@Singleton` — one instance per *process*, injected into
every `MuPlaybackService` the process creates — and its `release()`, called from
`onDestroy`, called `scope.cancel()`. A cancelled `CoroutineScope` is cancelled
permanently: every later `launch` on it returns an already-cancelled `Job` and the
body never runs. The browse callback of the **next** service was therefore silently
inert, its `SettableFuture`s were never set, and Media3 answered browsers by timing
out:

    TimeoutException: Waited 40 seconds ... SequencedFutureManager$SequencedFuture[PENDING]

For a user that is Android Auto, Wear OS or the Assistant browsing and searching
forever after the system has reclaimed the service once — no crash, nothing in the
log. `cancelChildren()` is the fix: it drops the work the dying session owned, which
is all `release()` ever needed to do.

**What makes this worth a section is how it hides.** Every affected test passes when
run alone. `VoiceSearchJourneyTest` was 8/8 by itself and 2/8 after any other `:app`
class, because that class's teardown destroys a service. So:

- a lane that runs one class while iterating sees nothing;
- `--no-build-cache check` sees nothing, in either direction;
- the failure surfaces only in a full-suite device run, and then as six timeouts in
  a class that has no defect in it.

**Run one class to iterate, the module's whole suite before believing it.** That is
already the rule for `connectedDebugAndroidTest` in this file; this is what it costs
when the whole suite is the only thing that can see the bug. And when a device
failure names a timeout rather than an assertion, suspect process-scoped state
left behind by an earlier class before suspecting the class that reported it.

## A `media_progress` row is only final once its item stops being current

`ProgressWriter`'s ticker calls `captureCurrent` every five seconds **whether or not
anything is playing**, and `captureCurrent` writes the position of whatever the player
says is current. So pausing does not freeze a row: for up to one tick afterwards the
row can still be rewritten, and the value that lands is the one the pause callbacks
raced to store. Measured writing Plan 4 Task 10's resume journey, which read the row
1.5 s after a pause and asserted it again later:

    expected:<6773L> but was:<6785L>      (and, in the second journey, <6475L> vs <6671L>)

Neither is a race in the product. `onPlayWhenReadyChanged` and `onIsPlayingChanged`
both fire on a pause, both `launch` into the same scope, and the audio sink drains
between them — so the settled value arrives on the *next tick*, not on the pause.

The fix is not a longer settle. It is to read the row once the book has **stopped being
the current item** — after music, or another book, has taken the session over. Nothing
writes a row whose item is not current, so that is the first moment "where the listener
left it" is a number rather than a moving target. Any journey that wants an exact
stored position needs this; a tolerance instead would have hidden the two-second rewind
assertion the same journey exists to make.

## Which player screen opens is decided from state that has not changed yet

`MuPlayApp` chose between `PlayerRoute` and `BookPlayerRoute` from
`PlaybackState.isAudiobook` **at the moment of the tap**, and every caller does
`viewModel.resume(); onOpenPlayer()` — the first of which launches a coroutine. So the
player that opened was always the *previous* item's. Measured on a device from a cold
session: tapping a book opened `PlayerScreen` (no chapters, no speed, no sleep timer),
and tapping a music track straight afterwards opened `BookPlayerScreen`, which renders
"Nothing playing" and has no transport at all — with the mini player hidden underneath
it, because both player screens hide it. The audiobook player was in practice
unreachable, and `./gradlew check` was green over all of it: nothing composes the graph
on the JVM tier.

A `LaunchedEffect(isAudiobook)` now swaps the top entry once the session answers. The
general shape is worth recognising: **a navigation decision read from a flow that the
same tap is about to change is always one event stale**, and the only test that can see
it is one that drives the real screens.

## A Compose tap-target assertion is wrong in both obvious directions

Writing a sweep that measures every `hasClickAction()` node on a screen, both natural choices are
wrong, and each was measured wrong rather than argued wrong (Plan 8, `:feature:requests`):

- **`SemanticsNode.touchBoundsInRoot` cannot fail.** Compose's hit-testing grows a small
  pointer-input area to the minimum touch target on its own. A deliberate ~20dp
  `Text(modifier = Modifier.clickable {})` injected to falsify the sweep measured
  `Rect.fromLTRB(-23.5, 53.5, 102.5, 179.5)` — 126x126px, which at 420dpi is exactly **48.0dp**,
  the same number being asserted. Note the **negative left edge**: the rectangle had been grown
  outside its own parent. The sweep passed over a target built to fail it.
- **`SemanticsNode.size` reports Material as broken.** On the unmerged tree a `TextButton`'s
  *clickable* node measures **40.00dp** tall — `minimumInteractiveComponentSize` reserves its 48dp
  on an ancestor, not on the node carrying the click. Measured: seven controls at 40.00dp across
  two screens (`setup:test`, `setup:save`, `Cancel`, `integrations:setup:BINDERY`,
  `Asked already`, `Play`, `Forget`), every one of them fine.

What works is asserting that non-nested targets' touch bounds **do not overlap**. Expansion is only
worth anything when the space it expands into is free: two 32dp rows in a column each grow to 48dp
and then collide, which is the real defect, while a lone small control with room around it is
genuinely hittable. Falsified — two one-line rows at ~32dp report
`settings:integrations and settings:requests: 7.62dp of their touch bounds is the same place`.

Two limits of that rule, both measured, both worth knowing before trusting it:

- A **40dp** row in a `spacedBy(8.dp)` column passes: 4dp of expansion each side exactly meets the
  8dp gap, and `Rect.overlaps` is strict. It is not a "48dp" rule, it is a "nobody is fighting over
  the same pixels" rule.
- `useUnmergedTree = true` is required. `Modifier.clickable` applies
  `semantics(mergeDescendants = true)`, so a tappable thing *inside* a tappable row vanishes on the
  merged tree — the first falsification above reported nothing at all for that second, independent
  reason.

For a single known row, `assertHeightIsAtLeast` on the node is still the right tool and it does go
red honestly (32.38dp on the cast picker's speaker row).

**The fixture gates as much as the assertion does.** Falsifying the ported sweep in
`:feature:castpicker` against the real defect -- delete `DeviceRow`'s `heightIn`, restoring 32.38dp
rows -- the sweep stayed **green** while the height assertion beside it went red, because the
fixture gave one of the two speakers a model name. A subtitle makes a row two lines and tall enough
to need no expansion, so nothing can collide with it and an overlap check has nothing to see. With
both speakers subtitle-less it fires: `Study Amp and Kitchen Display: 3.43dp of their touch bounds
is the same place`. One short row is not a crowd.

**And when you port a test helper, copy the helper and not its falsification record.** The first
port of that file was `sed 's/SettingsRow/DeviceRow/'`, which left a KDoc claiming, of
`:feature:castpicker`, three measurements taken in `:feature:requests` -- including an overlap
between two rows that do not exist in this module. A measurement record is about one tree at one
moment; renaming an identifier inside it does not move it.

## The device suite has order-dependent flakes, and the failing test moves between runs

Measured across five full `:app` + `:core:media` runs on one tree (2026-08-28), after
the real defects above were fixed:

| run | `:app` | `:core:media` | failing test |
| --- | --- | --- | --- |
| 1 | 0/54 | 0/352 | — |
| 2 | 0/54 | 1/352 | `BrowseSearchBrowserTest.onSearchReportsTheCount…` |
| 3 | 0/54 | 0/352 | — |
| 4 | 1/54 | 1/352 | `MuPlaybackServiceTest.theSessionOffersTheTransportCommands…`, `MediaCacheTest.theProductionCacheLivesInAKnownDirectory…` |

**Every one of them passes when its class runs alone** — checked for all four:
`MediaCacheTest` 15/15, `MuPlaybackServiceTest` 16/16, `BrowseSearchBrowserTest`
15/15, `VoiceSearchJourneyTest` 8/8.

The two known mechanisms, both worth recognising:

- **A test that asserts on state another class created.**
  `theProductionCacheLivesInAKnownDirectoryUnderCacheDir` expects
  `…/cache/media` to exist; it is created lazily, so the assertion holds only if
  something made a cached read first in that process.
- **A test that reads a value the player only has once a queue is loaded.**
  `theSessionOffersTheTransportCommandsALockScreenNeeds` got
  `[true, true, true, false, true]` — one command unavailable — where a moment later
  it is available.

So: **a single red in a full device run is not evidence on its own.** Re-run the
class alone before believing it, and compare against a full run of the same tree
rather than against a memory of one. What *is* evidence is a failure that
reproduces in isolation — every real defect found on 2026-08-28 did (the duplicate
navigation entry, the cancelled singleton scope, the NaN insert, the reflection
filter), and every flake did not.

Do not "fix" these by adding sleeps. Two of them want the state they depend on made
explicit in their own `@Before`; that is the change, and it has not been made yet.

## The mini player is in every `:app` journey's text matches, and it carries fixture strings

`MiniPlayer`'s bar is a `MergeDescendants` node with `contentDescription = "Now playing"`, so on
the merged tree **the playing track's title and artist both resolve to the bar itself**. Measured:

    ContentDescription = '[Now playing]'
    Text = '[Test Book, Test Author]'
    MergeDescendants = 'true'

The strings it can carry are exactly the strings these journeys assert on, because `books.tsv`
gives the seeded book's one file the *track title* `Test Book` and the artist `Test Author`, and
the music tracks the artist `Test Artist`. From the second class of a full `:app` run onwards
something is always playing, so the bar is always there.

Three failure directions, all measured on this suite rather than reasoned about:

- **A single-node matcher throws.** `onNodeWithText("Offset Track")` -> *"Expected exactly '1' node
  but found '2'"*. This is what sent `TranscodeSeekJourneyTest` red in two consecutive full runs
  while passing alone, and it is not an emulator flake — it reproduces the moment the bar happens
  to carry that title.
- **`assertDoesNotExist()` reports a library-scoping leak that did not happen.**
  `ScopedShuffleJourneyTest` searched for `Test Book` to prove a music shuffle never surfaces an
  audiobook; the bar showing the book satisfies that search. A **false red on the app's central
  promise**, caused by the app working correctly.
- **A non-vacuity guard passes on the bar's behalf.** The `check(... .isNotEmpty())` beside that
  assertion — there precisely so the assertion cannot be vacuous — is satisfied by a bar showing
  `Track 1` even if the shuffle returned nothing. A **false green on the guard against false
  greens**, which is the shape this repository exists to keep out of its own gates.

The filter is `JourneyNavigation`'s `notTheMiniPlayer()` / `onNodeWithTextOutsideMiniPlayer()`, and
`ScopedShuffleJourneyTest.aMusicShuffleIsScopedEvenWhileTheMiniPlayerIsShowingABook` drives the
first two **deterministically** — it plays the book, pauses, returns to the library and shuffles,
so the bar is guaranteed rather than left to test order. Falsified: with the filter removed it
fails with the node dump above; with it, green.

Note what this does *not* explain. The `:core:media` entries in the order-dependent-flake table
above (`MediaCacheTest`, `MuPlaybackServiceTest`, `BrowseSearchBrowserTest`) are a different
mechanism and are untouched by this. What it does explain is why an `:app` journey could fail in a
full run and pass alone with no product defect anywhere — and the general shape is the one this
file keeps recording: **the check returned a real observation of the wrong thing.**

Two lessons that generalise past this bar:

- A `contentDescription` on a merging node does not *replace* its children's text for the test
  matchers the way it does for TalkBack — the text is still there to be matched. Any app chrome
  that echoes content (a now-playing bar, a header, a breadcrumb) is inside every text match on
  the screen below it.
- **Write the wait and the assertion against the same filtered set.** Several waits here were
  `waitUntil { onAllNodesWithText(title).isNotEmpty() }` followed by a filtered assertion, so the
  wait returned on the bar and the assertion then failed against a list that had not arrived yet.

## A coverage floor that names a nested class needs dots, and `$2` fails silently

JaCoCo matches a rule's `includes` against the class's **qualified** name, where the
nested-class separator is a **dot**, not `$`. So the anonymous class JaCoCo's own
report calls `app/muplay/ui/StartDestinationViewModel$2` is
`app.muplay.ui.StartDestinationViewModel.2` to a floor.

Measured 2026-09-05 with a probe floor at `minimum = 1.00` over a class known to sit at
0.14 BRANCH, written both ways on the same tree:

    includes = listOf("app.muplay.database.CastSettings\$allowRendererDirect\$1")
      -> matches nothing, BUILD SUCCESSFUL
    includes = listOf("app.muplay.database.CastSettings.allowRendererDirect.1")
      -> Rule violated ... branches covered ratio is 0.14, but expected minimum is 1.00

Every existing floor that reaches a nested class does it with a `*`
(`AlbumViewModel*Fetch*`, `SyncFailure*`), which is why this had not bitten before.

**The two ways of getting it wrong fail very differently, and that is the trap.**
`"...ViewModel$1"` in a Kotlin string is a template, so it does not compile and you find
out immediately. `"...ViewModel$2"` is `$` followed by a digit, which Kotlin leaves
literal — so it compiles, runs, matches nothing, gates nothing, and the build goes
green. A floor written that way shipped here for exactly one run.

`warnVacuousFloors` is what caught it, and this is the case it exists for: it reports
that a floor "currently enforces nothing ... its COVEREDRATIO is NaN when the total is
0". Read that warning as a defect in the floor, not as noise — a floor with nothing to
gate is indistinguishable from one that has lost what it used to gate.

## A `coerceAtLeast`/`coerceIn` clamp is invisible to a BRANCH coverage floor

Measured in Plan 4 Task 9 while falsifying `:feature:book`'s floors. Kotlin's
`Long.coerceAtLeast` compiles to `Math.max`, which carries **no JaCoCo branch
counter at all**. So a test written specifically to prove a clamp fires moves no
number in `coverageFloors`, and withholding it leaves the floor green.

Two floors' worth of falsification were written as predictions on this and both
were wrong: withholding `a position past the declared duration has nothing
remaining` left `BookPlayerUiStateKt` at 19/20, and withholding the three
missing-id cases that prove `startIndexFor` folds `-1` to `0` left
`BookPlaybackLauncherKt` at 2/2. What those classes' branch counters actually
measure is the **null checks** beside the clamps -- `chapter?.title ?: ...`,
`resumeAt?.mediaId` -- which is not what either file looks like it is about.

Two consequences:

- **Run the falsification; never write one from reading.** This is already the
  rule at that table, and this is the failure mode it exists for: the predicted
  withholding produced green, which reads exactly like "the floor is too low".
- **A clamp needs its test for the clamp's own sake**, and the floor is not what
  holds it there. Say so at the floor, or the next person deletes a test that no
  number defends.

Related, same session: a null-safe chain on a **non-null** property
(`chapter?.title` where `title: String`) emits two decisions, one of them
unreachable -- so 3/4 or 19/20 is the honest ceiling, not a rounded-down number.
`:feature:library`'s `CoverArtCacheKeyKt` (0.75) and `:feature:setup`'s
`SetupFailureReasonKt` (0.85) are the same shape; check for one before assuming a
floor a branch or two short is a missing test.

## An ANR dialog outlives the app, so a UI dump can report a crash that already ended

Measured 2026-08-30 while smoke-testing the audiobook UI. `uiautomator dump` after
relaunching returned exactly three strings — `MuPlay isn't responding`, `Wait`,
`Close app` — and nothing from the app. That reads unambiguously as "the build I
just installed ANRs on launch", and it was one step from being reported as a
regression in the four commits that had just landed.

It was a **stale system dialog from an earlier launch**. The Application Not
Responding window belongs to `system_server`, not to the app, so it survives all
three of the things you would reach for to get a clean slate:

    adb shell am force-stop app.muplay
    adb shell pm clear app.muplay
    adb install -r ...            # even a full uninstall/reinstall

It sits on top of whatever launches next, and a dump reads the topmost window. The
app underneath was fine: dismissing the dialog first (`input keyevent KEYCODE_BACK`,
or uninstalling and reading the dump after a fresh start) showed the setup screen
rendering normally, on the identical APK.

Two things follow, and the second is the general one:

- **Dismiss or uninstall before you dump.** A `force-stop` is not enough, and the
  reading it produces is confidently wrong rather than empty.
- This is the same shape as every other stale-measurement trap in this file — the
  build-cache serving another worktree's failure, a floor comment describing a run
  that no longer happens, a lane's report describing master as it was at its last
  sync. **The check returned a real observation of the wrong moment.** When a
  device reading contradicts what the code says should be true, establish *when*
  the reading was taken before believing what it says.

## The emulator will not boot: `/dev/kvm` lost its ACL, and qemu now segfaults

Recorded 2026-08-30 22:20, unresolved, so nobody spends another hour on it.

At 19:20 `/dev/kvm` was **recreated** (its mtime says so) and the running `muplay37`
emulator died with it — no OOM in the kernel log, no crash record, ~18 GB freed.
Two separate problems then stack up, and the first hides the second:

**1. The ACL no longer grants this user.** `getfacl /dev/kvm` lists `user:gdm:rw-`
and no entry for `helios`; access now depends on the `kvm` *group*. `/etc/group`
does contain `kvm:x:993:helios`, but a shell whose session predates that addition
has no `kvm` in its effective set — `id` shows only `helios,sudo,docker`. So the
emulator prints *"This user doesn't have permissions to use KVM"* while
`/etc/group` looks correct, which sends you to the wrong page of the docs.

    sg kvm -c "<command>"        # works, changes no system state

Under `sg kvm`, `emulator -accel-check` reports **"KVM (version 12) is installed
and usable."** So permissions are solvable and are *not* the blocker.

**2. qemu's guest CPUs do not execute.** Every boot attempt under `sg kvm` logs

    ERROR | detected a hanging thread 'QEMU2 CPU0 thread'. No response for 15000 ms

repeatedly, reaches `adb devices` state `offline`, never sets `sys.boot_completed`,
and ends in `Segmentation fault (core dumped)`. Measured across four attempts, and
these are the hypotheses that are already **eliminated** — do not re-test them:

- **Not graphics.** Identical with `-gpu swiftshader_indirect -feature Minigbm` and
  with a plain `-gpu off`. (The `libgfxstream_backend.so: undefined symbol:
  stream_renderer_set_service_ops` line in the log is a red herring; it is present
  in runs from the three days this emulator worked.)
- **Not an SDK update.** `emulator/` and `libgfxstream_backend.so` are unchanged
  since 2026-08-17; the system image since 08-21.
- **Not memory pressure or a cgroup cap.** 49 GB available; the launching scope's
  `memory.max` reads `max`; no `CONSTRAINT_MEMCG` in the log.
- **Not host CPU contention.** Reproduced at load 33 *and* at load 11, and `vmstat`
  showed `id` 86-89% with `st` 0-2 throughout — the CPU was idle both times.
- **Not stale lock files.** `hardware-qemu.ini.lock`, `multiinstance.lock` and
  `/run/user/1001/avd/running/pid_*.ini` were cleared before two of the attempts.

KVM itself is healthy: an unrelated `qemu-system-x86_64` VM on this host
(`kissdesk-at`) ran throughout on `accel=kvm`. So it is something about this
emulator's use of KVM specifically, after whatever recreated the device node.

Until it is fixed, **the entire device tier is unavailable** and no floor marked
`requiresInstrumentedData` can be measured. Write the instrumented tests anyway —
`compileDebugAndroidTestKotlin` needs no device and is real verification that they
are well-formed — but **do not invent the floors**, and say in the commit message
that the tests have never been executed.

### `pkill -f` matches its own command line

While recovering the above: `pkill -f 'qemu-system.*muplay37'` killed the shell
running it, because that shell's command line contains the pattern. Exit 143, no
emulator touched. This file already records a `pgrep` that could never report
"finished" for the same reason, and a `ConventionTest` rule that reported its own
KDoc — **that is now four self-matching checks in this repository.** Before running
a pattern over process lists or source, ask whether the thing doing the asking
contains the pattern. Prefer an explicit PID from a prior `pgrep`, read and checked.

## A repo-wide `ConventionTest` rule can be skipped as UP-TO-DATE while it is being violated

Measured 2026-08-30, falsifying a new rule in `ConventionTest`. With the violating
line sitting in the tree — an `import com.google.android.gms.wearable.Wearable`
added to `wear/src/main/kotlin/.../MuPlayWearApplication.kt` — this happened:

    $ ./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'
    > Task :app:testDebugUnitTest UP-TO-DATE
    BUILD SUCCESSFUL in 4s

    $ ./gradlew :app:testDebugUnitTest --rerun --tests '*ConventionTest*'
    ... Expecting actual: [".../DataLayerWatchLink.kt", ".../MuPlayWearApplication.kt"]

Nothing under another module's source set is a **declared input** of
`:app:testDebugUnitTest`, so Gradle skipped the task entirely and the scan never
ran. Every rule in that class reads files outside `:app` — the workflow YAML, the
other modules' build files and manifests, `settings.gradle.kts`, `build.gradle.kts`
— and every one of them has this hole locally.

CI is a fresh checkout and is unaffected. A local `./gradlew check` after editing
anything outside `:app` is not: **the gate reports on a tree it never looked at**,
which is the defect class this repository's gates exist to keep out of themselves.

So: **falsify a `ConventionTest` rule with `--rerun`**, and do not read a green
`:app:testDebugUnitTest` as evidence about a file you changed in another module
unless the task actually executed. The same caution applies to `--no-build-cache`
for the cross-worktree case above; this is the *inputs* version of it, and
`--no-build-cache` alone does **not** fix it — an UP-TO-DATE task never consults
the cache in the first place.

## `advanceUntilIdle()` did not run work launched on `runTest`'s `backgroundScope`

kotlinx-coroutines **1.11.0**, measured while writing `WatchSyncEngineTest`. A
collector started with `engine.start(backgroundScope)`, fed from a
`MutableSharedFlow(replay = 1)` that already held a value, had **not run** after
`advanceUntilIdle()`: three tests failed reporting an empty table, which reads
exactly like a broken fake rather than a scheduler question.

Do not spend the time re-deriving which release changed this. Start the collector
on a scope you own, dispatched eagerly:

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.startCollecting(): CoroutineScope =
      CoroutineScope(UnconfinedTestDispatcher(testScheduler)).also(engine::start)

`emit` then resumes the collector inline, the assertion after it needs no advance
at all, and the test stops depending on scheduler semantics it is not about. The
scope is yours to `cancel()` at the end.

## `:core:testing` is a JVM module, so an Android module's fake cannot live in it

`:core:testing` applies `muplay.jvm.library`. A plain Kotlin JVM project cannot
depend on an Android library at all, so a fake implementing an interface declared
in an Android module (`:core:watchlink`'s `WatchLink`, say) has nowhere to sit
there. It is not a dependency cycle to break; it is an impossibility, and a plan
that puts one there is wrong about the module.

`testFixtures` is not the escape either. `:core:cast` has a `src/testFixtures` and
can, because it is a **JVM** module where the `java-test-fixtures` plugin is all it
takes. An Android library needs `android { testFixtures { enable = true } }`, which
`ConventionTest`'s `no module configures android or kotlin blocks directly`
refuses.

What is left is the module's own `src/test`, which is correct when that module is
the only consumer — and if a second consumer appears, the answer is to move the
interface, not to copy the fake.

## Moving `outputs/code_coverage` out from under a running connected test loses the `.ec`

That directory is `connectedDebugAndroidTest`'s declared `@OutputDirectory`. Move
it mid-run — to check whether another module's `.ec` is inflating your ratios, which
is a reasonable thing to want — and the run still reports **BUILD SUCCESSFUL** and
`Finished 42 tests`. It simply has nowhere to put the coverage file. The only
complaint is a Google-test-platform stack trace naming
`NonInteractiveServerStrategy`, which names no directory and reads like emulator
noise.

Measured 2026-08-31 on `:feature:requests`: 42 tests green, zero coverage, and
the wasted device run could not be repeated because the emulator died before the
retry. **A green connected run with no `.ec` behind it is the shape to recognise.**
Confirm `<module>/build/outputs/code_coverage/.../coverage.ec` exists before
reading any ratio, and stash `.ec` files *between* runs or not at all.

Worth knowing what the check itself is for: `mergedExecutionData` hands every
module **every** project's `.ec`, so `:app`'s journeys can pay for a feature
module's Compose ratios and a per-module floor can be gating another module's
suite without saying so. It is a real question. On `:feature:book` the answer was
that `:app` contributed nothing — all four ratios came back byte-identical with
the other modules' data stashed — but that was measured, and it is the first time
anyone in that table had checked rather than hoped.

## `@Disabled` is JVM-tier only; withholding a device test needs `@Ignore`

`coverageFloors`' falsification comments all say `@Disabled`, because they are all
JVM-tier withholdings and the JVM tier is JUnit 5. Every `src/androidTest` here
imports `org.junit.Test` — JUnit 4 — so `@Disabled` is not on that classpath at
all. Use `@Ignore`.

And note the harder half, which no annotation fixes: **withholding a device test
and re-reading the report measures nothing.** `@Ignore` changes the *test* class;
the production class is untouched, so JaCoCo goes on matching the previous run's
`.ec` to it by class id and credits every line the withheld test used to cover.
The re-read reproduces the baseline exactly — which reads like a floor that cannot
fire, and would get written down as one. Falsifying a `requiresInstrumentedData`
floor requires a **fresh `connectedDebugAndroidTest`**, i.e. a device you still
have. `Jacoco.kt`'s own header records the underlying footgun from the other side.

## An emulator can die outright, and it does not look like the recorded failure

CLAUDE.md's "Neither the emulator nor the container survives a session restart"
describes qemu surviving while the *adb server* dies, repaired by `adb
start-server`. Measured 2026-08-31, mid-task, between two module suites: `adb
devices` empty, **the adb server itself still running**, `last reboot` still
2026-08-27, no `CONSTRAINT_MEMCG` or any OOM in the kernel log — and no
`qemu-system-x86_64` for `muplay37` anywhere on the host. The emulator process was
simply gone, and `adb start-server` cannot reach a device that is not running.

So check for the *qemu process*, not just for `adb devices`, before deciding which
of the two failures you have — and get the check right, because the obvious form of
it is the **fourth** liveness check in this file that cannot report "dead".

Measured on a host with no emulator running at all:

    pgrep -af "qemu-system-x86_64.*muplay37|emulator64|/emulator/" | wc -l   -> 1
    pgrep -c  -f "qemu-system-x86_64.*muplay37|emulator64|/emulator/"        -> 1
    pgrep -c  -x qemu-system-x86_64                                          -> 0, with a warning
    pgrep -af "qemu-system-x86_64.*muplay37" | grep -v "bin/bash" | wc -l    -> 0   <-- correct

The first two match **this harness's own `/bin/bash -c ...` wrapper**, whose command
line contains the pattern text. `pgrep` skips its own pid, so "pgrep excludes itself"
is true and does not help — the process it finds is the shell one layer up. Note the
third line especially: `-x` is the natural fix and it is a trap of its own, because
`qemu-system-x86_64` is 18 characters and `pgrep` silently cannot match a process
*name* longer than 15 — it prints a warning and returns 0, i.e. the right answer here
for the wrong reason, and the wrong answer on a host where the emulator is up.

Filter the shell out explicitly, and sanity-check any process probe by running it
once when you know the answer is "alive".

## A JVM test that reads a data file is `FROM-CACHE` when only that file changes

`StoreListingTest` holds `docs/STORE-LISTING.md` against the tree. Gradle does not know the
document is an input of `:app:testDebugUnitTest`, so editing it changes nothing Gradle can see:

    $ sed -i 's/.../There is a sleep timer. .../' docs/STORE-LISTING.md
    $ ./gradlew :app:testDebugUnitTest --tests '*StoreListingTest*'
    > Task :app:testDebugUnitTest FROM-CACHE
    BUILD SUCCESSFUL in 4s

That claim is one the same test's banned list forbids. The gate over the one artefact in this
repository that strangers read did not run when that artefact changed — it runs only on a cold
cache, in CI, or when an unrelated Kotlin edit drags it along.

Two consequences, and the second is what cost the time:

- **A falsification harness that mutates only a data file measures nothing** and reports every rule
  as unfalsifiable. Three mutations came back NOT CAUGHT and the rules were innocent; re-running
  with `--rerun` on the test task caught all three. If a mutation is "not caught", check the task
  actually ran before you go looking at the rule.
- **Declare the files as task inputs.** `app/build.gradle.kts` now does, for the listing, the
  release workflow and `play/`. Falsified both ways on one tree: unchanged → `UP-TO-DATE`, listing
  edited → the task re-runs and goes red.

`ConventionTest` has the same shape — it walks the repository root and none of what it reads is a
declared input. Any new gate of this kind owes the same declaration.

## An absence probe written as a Java setter call can never match Kotlin

`StoreListingTest` disclaimed silence skipping behind

    "Silence skipping" to Regex("""\.setSkipSilenceEnabled\(""")

and the feature had been shipping for weeks, with a switch on the book screen. `ExoPlayer` exposes
it as a Java setter, so Kotlin addresses it as a synthetic property and `BookSpeedController`
writes `player.skipSilenceEnabled = settings.skipSilence`. The regex could not match this codebase
and never could. A second probe in the same list, `SleepTimerController\(`, was equally blind: the
class is `internal constructor` and Hilt-injected, so nothing writes that either — right answer,
wrong reason, and it would have kept saying "absent" after somebody wired the timer up.

So when you write a probe for the *absence* of a wiring, write the line that would make it fire and
run it. A probe nobody has seen go red is a probe nobody has seen work — and this file already
records three liveness checks that returned the same falsey value for "dead" and "I cannot tell".

Related, from the same rule set: **a module existing is not a capability shipping.** The
form-factor rule derived "this build declares Wear OS" from `include(":wear")` in
`settings.gradle.kts`. That is true, and obeying it would have published *"Wear OS: Yes"* over a
`WearApp` that renders the single word "MuPlay", with nothing declaring `wearApp(...)`,
`release.yml` assembling and signing `:app` and only `:app`, and `:core:watchlink` named by no build
file at all. Probe for the artifact, not the directory.

## A Media3 `PlaybackException` cannot be constructed on the JVM tier

Its constructor timestamps itself off `SystemClock.elapsedRealtime()`, so a pure-JVM test that
builds one to feed a mapping function dies before the assertion:

    java.lang.RuntimeException: Method elapsedRealtime in android.os.SystemClock not mocked.
      at androidx.media3.common.PlaybackException.<init>(PlaybackException.java:492)

Measured writing `PlaybackFailureTest`: **all seven tests failed**, none of them for a reason the
test was about, and the message names `SystemClock` rather than Media3.

The fix is the split this repository already states at `PlaybackState.durationMsOf` -- *"a plain
function over two nullable `Long`s, with no Media3 type in its signature, so the fast tier can hold
this decision to a floor"*. `PlaybackFailure.of` now takes an `Int?` error code, not the exception.
The `ERROR_CODE_*` constants are `static final int`s and are inlined by the compiler, so naming
them in either the production file or the test loads no Android class.

So: **a decision that maps a framework value belongs behind a signature made of primitives.** The
device tier is the only place the framework object itself has to appear, and that is one line in
`PlaybackConnection` (`player.playerError?.errorCode`) rather than a whole test class.

## Editing a file under a running `check` makes the result unreadable, so stop the run first

A gate started, then a source file edited while it was still in its configuration phase, is a
result you cannot act on: Gradle's up-to-date checks see the new bytes for tasks that have not
started and the old ones for tasks that have, and nothing in the output says which. Twice now the
cheaper move has been to `TaskStop` the run, finish the edit, and start once -- a `--no-build-cache
check` here is under three minutes, and re-reading an ambiguous one costs more than that.

Related, and the reason this comes up at all: **reviewing your own new code while its gate runs is
worth doing, and it will find things.** One pass over the diff this session found a `scope.launch`
on `Dispatchers.Default` that navigated a Compose back stack (a `SnapshotStateList` accepts a write
from any thread, so it would have *appeared* to work and misbehaved once a week), a `remember` that
should have been `rememberSaveable`, and a defaulted `onRetry = {}` that would have shipped a
"Try again" button wired to nothing. None of the three is visible to any gate in this repository.

### The emulator died again on 2026-09-03, and idle host rules out the last hypothesis

`muplay37` vanished mid-session (no qemu process, adb server still alive, no host reboot, no OOM
of any kind, RAM used dropping 38 GB -> 10 GB as it went). A restart with the documented flags
under `sg kvm` reproduced the recorded failure exactly: `Gfxstream initialized successfully!`, then
repeated `detected a hanging thread 'QEMU2 CPU3 thread'. No response for 15004 ms`, `adb devices`
stuck at `offline`, `sys.boot_completed` never set, and finally `Segmentation fault (core dumped)`
(exit 139) after about twelve minutes.

What this run adds to the section above is one eliminated hypothesis, measured rather than assumed:
**it is not host load.** `vmstat` through the whole attempt read `id` 83%, `st` 0, with 51 GB free
and load under 3. An earlier recovery had been attributed to "host I/O load dropping"; that
attribution is now doubtful, because the quietest host this file has recorded still segfaults.

Practical consequence, and it is the same as before: when this happens the device tier is gone and
no `requiresInstrumentedData` floor can be measured. **Get the device runs in early.** This session
kept its results only because the four module suites had been run and their XML read (with mtimes
checked) before the emulator went; the one thing lost was a final whole-`:app` pass and fresh
`.ec` coverage, which is why two new classes here carry no floor rather than an invented one.
