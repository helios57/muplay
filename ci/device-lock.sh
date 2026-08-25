#!/usr/bin/env bash
# Serialise every device-tier run in this repository.
#
# One emulator (`muplay37`) serves every agent working here at once, and two
# concurrent `connectedDebugAndroidTest` runs install the same applicationId
# (`app.muplay`): the second reinstalls the first underneath itself, and the
# victim's report shows `<failure></failure>` plus `Process crashed` with **no
# stack trace** — indistinguishable from a real product crash. Two runs were
# lost to this before logcat showed the other agent's test class starting
# inside the victim's window.
#
# Wrap every device command in this script and they queue instead of colliding:
#
#   ci/device-lock.sh ./gradlew :core:media:connectedDebugAndroidTest
#
# The lock is held by the kernel on an open file descriptor, not by a PID file.
# That distinction is deliberate: a PID file left behind by a killed agent
# deadlocks everyone after it, while a kernel lock is released the moment the
# holding process dies, however it dies. Nothing here parses a PID to decide
# whether the holder is alive — a check that cannot distinguish "alive" from
# "malformed" has already cost this project a corrupted run.
#
# The line written into the lock file is for humans waiting at a terminal. It
# is never read back to make a decision.
set -euo pipefail

LOCK_FILE="${MUPLAY_DEVICE_LOCK:-/tmp/muplay-device.lock}"
WAIT_SECONDS="${MUPLAY_DEVICE_LOCK_WAIT:-5400}"

if [ "$#" -eq 0 ]; then
  echo "usage: ${0##*/} <command...>" >&2
  exit 2
fi

command -v flock >/dev/null || { echo "${0##*/}: flock not found (util-linux)" >&2; exit 2; }

# Append, never truncate: `9>` would erase the holder's line the moment a
# waiter opened the file — leaving every waiter reading back an empty holder.
exec 9>>"$LOCK_FILE"

if ! flock --exclusive --nonblock 9; then
  echo "${0##*/}: device busy — held by: $(head -1 "$LOCK_FILE" 2>/dev/null || echo '<unrecorded>')" >&2
  echo "${0##*/}: waiting up to ${WAIT_SECONDS}s..." >&2
  if ! flock --exclusive --timeout "$WAIT_SECONDS" 9; then
    echo "${0##*/}: gave up after ${WAIT_SECONDS}s. The device tier did NOT run." >&2
    # 75 = EX_TEMPFAIL. A caller must never read this as a product failure:
    # nothing was measured.
    exit 75
  fi
fi

# Safe to truncate through a second open: we hold the lock.
printf 'pid=%s since=%s cmd=%s\n' "$$" "$(date -Is)" "$*" > "$LOCK_FILE"

echo "${0##*/}: device lock acquired (pid $$)" >&2
"$@"
