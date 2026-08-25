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
later. Two `HEAD`s back to back both say cold; the same URL a few hundred
milliseconds afterwards is warm. So a probe that finds a cold entry has warmed
the entry it found, and the assertion after it races the transcoder: a live suite
built that way passed once and then failed three runs running with `expected: 502
but was: 200`.

Searching *through the thing under test*, so the search's own response is the
observation, is correct and is worse: a run that finds nothing has requested
**every** bitrate below the source and cached all of them. Those entries are a
shared, exhaustible resource — one such sweep left one of `coldTranscode`'s three
candidate tracks with no cold bitrate at all, which is a flake handed to whoever
runs `:core:network:liveNavidromeTest` next. Recreating the container is the only
repair, and it is not something one agent may do to a shared container.

So: prefer a live assertion that needs no cold entry. `:core:cast`'s
`LiveNavidromeProxyTest` gets a real `Content-Range`-less response out of
Navidrome by stripping the credentials from a stream URL instead (200,
`Content-Type: application/json`, a real `Content-Length`, no `Content-Range`),
which is stable, costs nothing, and is a sharper assertion besides.

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
