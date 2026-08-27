#!/usr/bin/env bash
#
# Regenerates the Play Store phone screenshots from the real app on the real emulator.
#
#   ci/store-screenshots.sh
#   ci/store-screenshots.sh --server https://music.example.com --user alice --password hunter2
#
# With no arguments it drives the app against `ci/navidrome.compose.yml`'s seeded container over
# `adb reverse`, which is what CI and every other Tier 2 journey here use. With `--server` it drives
# the same journey against a real library instead, which is what a human should do before actually
# uploading: the seeded corpus is called "Test Album" and "Test Book", and a store listing full of
# that reads like an unfinished app.
#
# **A password given here reaches the emulator on a Gradle command line, and is therefore visible
# in `ps` to every user on this machine for the duration of the run.** There is no way around that:
# `-Pandroid.testInstrumentationRunnerArguments.*` is the only channel AGP offers for passing a
# value into an instrumentation, and a Gradle project property cannot be set from an environment
# variable whose name contains dots. `MUPLAY_SCREENSHOT_PASSWORD=...` keeps it out of your shell
# history and no further; do not use a password here that protects anything else.
#
# Output: play/screenshots/phone/*.png, one per `capture(output, "...")` call in
# StoreScreenshotsTest. That set is derived from the test source here and again, independently, by
# `StoreListingTest` on the JVM tier, so the listing document, this script and the test cannot drift
# apart in silence.
#
# --- What this does and does not lock ----------------------------------------------------------
#
# The build happens **outside** `ci/device-lock.sh`, because compiling needs no device and wrapping
# the whole Gradle invocation holds the shared emulator hostage through a build every other lane
# then waits on. Only install-and-run is inside the lock. Exit 75 from the lock means the wait ran
# out and **nothing was measured** -- this script passes that code straight through, and it is never
# a test failure.
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly TEST_SOURCE="$REPO_ROOT/app/src/androidTest/kotlin/app/muplay/StoreScreenshotsTest.kt"
readonly OUTPUT_DIR="$REPO_ROOT/play/screenshots/phone"
# Play's own limits for a phone screenshot, and the reason this script checks pixels at all: the
# Console rejects the upload rather than the listing, long after anyone remembers why.
readonly MIN_SIDE_PX=320
readonly MAX_SIDE_PX=3840
readonly MIN_SCREENSHOTS=2
readonly MAX_SCREENSHOTS=8

SERVER_URL=""
USERNAME=""
PASSWORD="${MUPLAY_SCREENSHOT_PASSWORD:-}"

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --server) SERVER_URL="${2:?--server needs a URL}"; shift 2 ;;
    --user) USERNAME="${2:?--user needs a name}"; shift 2 ;;
    --password) PASSWORD="${2:?--password needs a value}"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "${0##*/}: unknown argument $1" >&2; usage 2 ;;
  esac
done

if [ -n "$SERVER_URL" ] && { [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; }; then
  echo "${0##*/}: --server also needs --user and a password (--password or " \
       "MUPLAY_SCREENSHOT_PASSWORD)" >&2
  exit 2
fi

# --- adb, which is not on PATH on this host ----------------------------------------------------
ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || {
  echo "${0##*/}: no adb at $ADB. Set ADB= or ANDROID_HOME=." >&2
  exit 2
}

# --- Facts read from the tree rather than written down here ------------------------------------
# A hand-written copy of either of these is exactly the drift this repository has paid for three
# times. The applicationId decides which package `run-as` can reach; the test class decides what
# the instrumentation runs.
APPLICATION_ID="$(sed -n 's/^ *applicationId *= *"\([^"]*\)".*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
[ -n "$APPLICATION_ID" ] || { echo "${0##*/}: could not read applicationId from app/build.gradle.kts" >&2; exit 1; }

TEST_PACKAGE="$(sed -n 's/^package \(.*\)$/\1/p' "$TEST_SOURCE" | head -1)"
TEST_CLASS="$TEST_PACKAGE.$(basename "$TEST_SOURCE" .kt)"
[ -n "$TEST_PACKAGE" ] || { echo "${0##*/}: could not read a package from $TEST_SOURCE" >&2; exit 1; }

# The directory the test writes into, read out of the test's own constant so the two cannot
# disagree about where the files are.
DEVICE_SUBDIR="$(sed -n 's/^ *const val OUTPUT_DIRECTORY = "\([^"]*\)".*/\1/p' "$TEST_SOURCE" | head -1)"
[ -n "$DEVICE_SUBDIR" ] || { echo "${0##*/}: could not read OUTPUT_DIRECTORY from $TEST_SOURCE" >&2; exit 1; }
DEVICE_DIR="files/$DEVICE_SUBDIR"

# The names the test will write, derived from its own `capture(output, "...")` calls.
mapfile -t EXPECTED < <(grep -o 'capture(output, "[^"]*")' "$TEST_SOURCE" | sed 's/.*"\(.*\)".*/\1/' | sort)
if [ "${#EXPECTED[@]}" -lt "$MIN_SCREENSHOTS" ]; then
  echo "${0##*/}: found ${#EXPECTED[@]} capture() calls in $TEST_SOURCE; Play needs at least" \
       "$MIN_SCREENSHOTS phone screenshots. A scan that finds nothing is the failure mode this" \
       "check exists for -- look at the test before believing the number." >&2
  exit 1
fi
if [ "${#EXPECTED[@]}" -gt "$MAX_SCREENSHOTS" ]; then
  echo "${0##*/}: ${#EXPECTED[@]} capture() calls, but Play accepts at most $MAX_SCREENSHOTS" \
       "phone screenshots." >&2
  exit 1
fi
echo "${0##*/}: ${#EXPECTED[@]} screenshots to take, from $TEST_CLASS"

# --- Build, outside the lock -------------------------------------------------------------------
echo "${0##*/}: assembling (no device needed, so no lock held)"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :app:assembleDebug :app:assembleDebugAndroidTest

# --- The emulator's own preconditions ------------------------------------------------------------
# adb reverse, the boot check and the graphics-flag check all live in this one script, which
# `.github/workflows/e2e.yml` runs too. Run unconditionally, including for `--server`: the port
# forward is then unused, but the boot check and the minigbm check decide whether an instrumented
# UI test can run on this emulator at all, and those are wanted either way.
# `prepare-emulator.sh` calls a bare `adb`, and adb is not on PATH on this host -- so put the SDK's
# own platform-tools in front of it rather than leaving that script to fail with "command not
# found" for a binary this one has already located.
PATH="$(dirname "$ADB"):$PATH" "$REPO_ROOT/ci/prepare-emulator.sh"

# --- Run, inside the lock ------------------------------------------------------------------------
# `adb install` + `am instrument`, NOT `./gradlew :app:connectedDebugAndroidTest`.
#
# Measured: the Gradle task is green, writes all seven PNGs, and then **uninstalls the app**, which
# is what AGP's connected-test task does when it finishes. The screenshots live in the app's private
# `filesDir`, so they go with it -- `run-as: unknown package: app.muplay` at the pull step, on a run
# whose own report said `Finished 1 tests` with nothing failed. There is no ordering fix: the task
# owns both ends.
#
# Installing and instrumenting directly leaves the app in place for the pull. It costs the Gradle
# test report, which nothing here reads, and it changes nothing about how CI runs this class --
# `:app:connectedDebugAndroidTest` still executes it on every emulator job, where the assertions are
# the point and the PNGs are discarded.
APP_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$REPO_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
for apk in "$APP_APK" "$TEST_APK"; do
  [ -f "$apk" ] || { echo "${0##*/}: $apk was not produced by the assemble above" >&2; exit 1; }
done

# Both read from the tree, like the applicationId above.
RUNNER="$(sed -n 's/.*testInstrumentationRunner = "\([^"]*\)".*/\1/p' \
  "$REPO_ROOT/build-logic/convention/src/main/kotlin/KotlinAndroid.kt" | head -1)"
[ -n "$RUNNER" ] || { echo "${0##*/}: could not read testInstrumentationRunner from build-logic" >&2; exit 1; }
TEST_APPLICATION_ID="$APPLICATION_ID.test"

INSTRUMENT_ARGS=(-e class "$TEST_CLASS")
if [ -n "$SERVER_URL" ]; then
  INSTRUMENT_ARGS+=(-e muplayServerUrl "$SERVER_URL" -e muplayUsername "$USERNAME" -e muplayPassword "$PASSWORD")
fi

INSTRUMENT_LOG="$(mktemp -t muplay-store-screenshots.XXXXXX)"
trap 'rm -f "$INSTRUMENT_LOG"' EXIT

set +e
"$REPO_ROOT/ci/device-lock.sh" bash -c '
  set -e
  adb="$1"; app="$2"; test="$3"; runner="$4"; testid="$5"; log="$6"; shift 6
  "$adb" install -r -t "$app"
  "$adb" install -r -t "$test"
  "$adb" shell am instrument -w "$@" "$testid/$runner" 2>&1 | tee "$log"
' _ "$ADB" "$APP_APK" "$TEST_APK" "$RUNNER" "$TEST_APPLICATION_ID" "$INSTRUMENT_LOG" "${INSTRUMENT_ARGS[@]}"
run_status=$?
set -e
if [ "$run_status" -eq 75 ]; then
  echo "${0##*/}: the device lock timed out. NOTHING WAS MEASURED and no screenshot was taken." >&2
  exit 75
fi
[ "$run_status" -eq 0 ] || exit "$run_status"

# `am instrument` exits 0 even when every test failed, so its exit status is not the result. The
# runner prints `OK (n tests)` on success and `FAILURES!!!` otherwise; neither appearing means the
# instrumentation did not start at all, which must also be a failure rather than a silent pass.
if grep -q 'FAILURES!!!' "$INSTRUMENT_LOG" || ! grep -q '^OK (' "$INSTRUMENT_LOG"; then
  echo "${0##*/}: the screenshot journey did not pass. Last lines:" >&2
  tail -30 "$INSTRUMENT_LOG" >&2
  exit 1
fi

# --- Pull -----------------------------------------------------------------------------------------
# `run-as`, not `adb pull`: the files are inside the app's private data directory, which is where a
# debuggable app can write without asking for any storage permission the shipped app does not have.
mapfile -t ON_DEVICE < <("$ADB" shell run-as "$APPLICATION_ID" ls "$DEVICE_DIR" 2>/dev/null | tr -d '\r' | sort)
if [ "${#ON_DEVICE[@]}" -eq 0 ]; then
  echo "${0##*/}: $APPLICATION_ID wrote nothing to $DEVICE_DIR. The instrumentation reported" \
       "success, so this is a pull problem, not a test problem: check that the debug build is" \
       "installed and debuggable." >&2
  exit 1
fi

missing=()
for name in "${EXPECTED[@]}"; do
  printf '%s\n' "${ON_DEVICE[@]}" | grep -qxF "$name.png" || missing+=("$name.png")
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "${0##*/}: the device is missing ${missing[*]}, which $TEST_SOURCE says it captures." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
# Cleared only now, once the device is known to hold a full set: a wipe before the run would turn a
# lock timeout or a red journey into "the committed screenshots are gone as well".
rm -f "$OUTPUT_DIR"/*.png
for name in "${ON_DEVICE[@]}"; do
  case "$name" in *.png) ;; *) continue ;; esac
  "$ADB" exec-out run-as "$APPLICATION_ID" cat "$DEVICE_DIR/$name" > "$OUTPUT_DIR/$name"
done

# --- Verify what landed ---------------------------------------------------------------------------
python3 - "$OUTPUT_DIR" "$MIN_SIDE_PX" "$MAX_SIDE_PX" "${EXPECTED[@]}" <<'PY'
import os
import struct
import sys

directory, min_side, max_side = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
expected = sorted(name + ".png" for name in sys.argv[4:])

def png_size(path):
    """Width and height out of the IHDR chunk, or a hard failure.

    Read here rather than trusted: `adb exec-out ... cat` writing a truncated or empty file is a
    silent failure otherwise, and a 0-byte PNG is exactly what Play would reject on upload.
    """
    with open(path, "rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise SystemExit(f"{path} is not a PNG ({len(header)} header bytes read)")
    return struct.unpack(">II", header[16:24])

found = sorted(name for name in os.listdir(directory) if name.endswith(".png"))
if found != expected:
    raise SystemExit(
        "pulled screenshots do not match the test's capture() calls:\n"
        f"  on disk : {found}\n"
        f"  expected: {expected}"
    )

for name in found:
    path = os.path.join(directory, name)
    width, height = png_size(path)
    if min(width, height) < min_side or max(width, height) > max_side:
        raise SystemExit(f"{name} is {width}x{height}, outside Play's {min_side}-{max_side}px range")
    print(f"{os.path.getsize(path):>8}  {name}  {width}x{height}")
print(f"{len(found)} screenshots in {directory}")
PY

echo "${0##*/}: done. Review every file before uploading -- a screenshot is published content."
