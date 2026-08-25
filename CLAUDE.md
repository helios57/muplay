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

## Wait in bounded foreground commands, not on watchers

Agents here have repeatedly parked waiting for a monitor or background-task
notification that never arrived, while the work they were waiting on had either
finished or died. Run a bounded command and read its exit status yourself.

Two watchers have failed in this exact way: a `pgrep -f mutation-probes` that
matched its own command line and so could never report "finished", and a PID
file containing the literal text `PID=12345` rather than the bare number, which
made every liveness check structurally incapable of reporting "alive".

## `ci/mutation-probes.sh` is a regression list, not a rule

Read its header before changing it. Its probe count is derived at runtime and
must stay derived — a hardcoded total has gone stale twice. It refuses to run on
a dirty tree, and that guard has caught a real stray mutation left behind by a
killed run.
