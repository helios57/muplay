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

## `ci/mutation-probes.sh` is a regression list, not a rule

Read its header before changing it. Its probe count is derived at runtime and
must stay derived — a hardcoded total has gone stale twice. It refuses to run on
a dirty tree, and that guard has caught a real stray mutation left behind by a
killed run.

## A worktree inside the repo reddens `:app`'s mock-framework guard

`ConventionTest`'s `no mock framework is declared in any build file or
convention plugin` walks the whole repo root and skips only `build/` and
`.git/`. The one carve-out it makes — the root `build.gradle.kts`, which has to
name the frameworks in `BANNED_MOCK_GROUPS` — is matched by *canonical path*.

So a git worktree checked out **inside** the repo (`.claude/worktrees/<name>/`)
brings its own copy of `build.gradle.kts`, which is not the carved-out file, and
`:app:testDebugUnitTest` fails on a build file no agent in this session wrote.
The assertion message names the offending path — read it before assuming your
own change caused it. Nothing to fix in the product; it clears when the worktree
goes away.

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
