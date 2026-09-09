#!/bin/sh
# Prints host memory and the emulator's own resident size to stdout every 15 seconds.
#
# ## Why this exists
#
# `.github/workflows/e2e.yml` died twice in a row with
#
#     ##[error]The runner has received a shutdown signal.
#
# roughly twenty minutes in, part-way through the fifth of twelve module suites, with 83 GB free
# on disk and no test failure anywhere in the log. Nothing in that message names memory, and
# nothing in the job observed it -- the `Host capacity` step reads `free -h` once, before the
# emulator exists, which is the moment it cannot tell you anything.
#
# The suspicion is the emulator, and it is measured rather than assumed. On the development host,
# after one full device tier had run through it, `muplay37`'s qemu reported:
#
#     VmRSS 59.3 GB   RssAnon 59.2 GB   RssFile 0.1 GB   VmSwap 0
#
# for a guest configured `hw.ramSize=8192`. That is **7.4x the guest's RAM in private, anonymous
# pages** -- not shared mappings, not page cache, nothing the kernel can drop under pressure.
# CLAUDE.md has recorded the same thing from the host's side for weeks ("the emulator's qemu held
# 35 GB resident, which no Gradle setting can reach"); what was never done is join it to CI,
# because CI had never once booted an emulator to grow.
#
# A GitHub-hosted `ubuntu-latest` runner has **15 GB of RAM and 3 GB of swap**. At the same ratio a
# 4 GB AVD wants about 30 GB, so the runner cannot finish -- and it dies later each run as the
# earlier suites get faster, which is exactly the pattern observed.
#
# So this samples the one thing that was never watched. It writes to stdout on purpose: a killed
# runner runs no cleanup step and uploads no artifact, so the last line before the shutdown has to
# already be in the streamed log. That is the same reasoning that put `-show-kernel` on the
# emulator -- reach for what observes the subject.
#
# Remove it once the number is known and written down.
while :; do
  printf 'MEM %s %s | qemu_rss=%s MB\n' \
    "$(date -u +%H:%M:%S)" \
    "$(free -m | awk '/^Mem:/ { printf "used=%sMB avail=%sMB", $3, $7 } /^Swap:/ { printf " swap=%sMB", $3 }')" \
    "$(ps -eo rss,comm | awk '/qemu/ { s += $1 } END { printf "%d", s / 1024 }')"
  sleep 15
done
