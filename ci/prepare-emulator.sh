#!/usr/bin/env bash
#
# Waits for a booted emulator, checks it is the device this repository's Tier 2 gate is written
# against, and gives it the one thing `FirstRunJourneyTest` needs but cannot do for itself:
# `adb reverse tcp:4533 tcp:4533`, so `http://localhost:4533` inside the emulator reaches the
# Navidrome container on the host (ci/navidrome.compose.yml). Note that a missing forward does not
# fail loudly -- the app's connection attempt simply times out.
#
# Run once per emulator boot, before `./gradlew :app:connectedDebugAndroidTest` -- locally and in
# `.github/workflows/e2e.yml` alike, so both run against the same device state.
#
# --- The emulator this expects to be talking to ------------------------------------------------
#
# Launch flags matter here, and two of them are not optional. Locally:
#
#   $ANDROID_HOME/emulator/emulator -avd muplay37 \
#       -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
#       -feature Minigbm -prop qemu.hardware.gralloc=minigbm
#
# `.github/workflows/e2e.yml` passes the same list as `emulator-options`. The last two switch the
# guest from the goldfish ("ranchu") gralloc implementation to minigbm, and without them this
# emulator + system-image pair cannot run an instrumented UI test at all:
#
#   Abort message: 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma'
#     #03 GoldfishMapper::readFromHost(cb_handle_t const&) const
#     #05 android::Gralloc5Mapper::lock(...)
#     #09 android::RegionSamplingThread::threadMain()          <- SurfaceFlinger, ~every 40s
#     ... or, from system_server, TaskSnapshotPersister        <- at every activity teardown
#
# Emulator 37.1.11's host renderer advertises `ANDROID_EMU_read_color_buffer_dma` (the string is
# in its own `lib64/libgfxstream_backend.so`), unconditionally -- disabling `GLDMA`/`GLDMA2` does
# not withdraw it. `mapper.ranchu.so` in the system image named below -- whose own
# `source.properties` asks for `emulator#36.5.11`, an *older* emulator than the SDK ships --
# asserts that capability is *absent* on its CPU-read path, so every `GraphicBuffer::lock` of a
# host colour buffer aborts the calling process. SurfaceFlinger's luma sampling hits it on a
# timer; system_server's task-snapshot persister hits it every time an activity is torn down,
# which is once per instrumented test. Both take system_server down with them and the run dies
# with "INSTRUMENTATION_ABORTED: System has crashed."
#
# The system image ships `mapper.minigbm.so` alongside `mapper.ranchu.so`; `ro.hardware.gralloc`
# picks between them, and `-prop qemu.hardware.gralloc=minigbm` sets it (`-prop` accepts `qemu.*`
# names only). `-feature Minigbm` tells the host renderer the guest is doing so. Measured on
# `muplay37`: 4 SurfaceFlinger aborts per 150s window and a system_server abort at every activity
# teardown without these flags; 0 aborts and both journeys green with them, on the SDK's own
# emulator 37.1.11. Confirmed independent of `-gpu` (swiftshader_indirect / guest / host all abort
# without them) and of `hw.gltransport` (pipe / asg / virtio-gpu all abort without them).
#
# Remove the two flags once the image and the emulator agree again on their own.
set -euo pipefail

readonly NAVIDROME_PORT=4533

# The AVD coordinates, and the only copy of them outside `.github/workflows/e2e.yml`'s own job
# `env:` block. `ConventionTest`'s "the emulator coordinates in e2e yml and prepare-emulator sh
# cannot drift apart" reads both files and fails the build if they disagree, and also asserts the
# workflow's `android-emulator-runner` inputs actually read that `env:` block rather than
# hardcoding a literal beside it -- the same kind of mechanism `LIVE_NAVIDROME_TEST_TASK_NAME`
# gets, rather than a comment asking for hand-sync.
#
# `37.0`, not `37`: `reactivecircus/android-emulator-runner` interpolates its `api-level` verbatim
# into `platforms;android-<level>` and `system-images;android-<level>;<target>;<arch>`, and there
# is no `android-37` package -- API 36 has a bare alias, API 37 does not (only 37.0, 37.1 and
# 37.2-beta*). `api-level: 37` shipped once and could not create an AVD at all.
readonly EMULATOR_API_LEVEL=37.0
readonly EMULATOR_TARGET=google_apis
readonly EMULATOR_ARCH=x86_64

adb wait-for-device

# Never a fixed sleep: `adb wait-for-device` returns as soon as adbd answers, which is long before
# the framework is up.
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

# Fails the script rather than letting the journey report something misleading later.
fail() {
  echo "$1" >&2
  exit 1
}

prop() {
  adb shell getprop "$1" | tr -d '\r'
}

# `ro.build.version.sdk_full` carries the minor version ("37.0"), which is exactly the qualifier
# the SDK package name uses -- unlike `ro.build.version.sdk`, which reports a bare "37" and so
# cannot tell the two apart. `EMULATOR_TARGET` has no equivalent system property to check against,
# so it is held only by the ConventionTest drift assertion above, not here.
api_level="$(prop ro.build.version.sdk_full)"
[ "$api_level" = "$EMULATOR_API_LEVEL" ] ||
  fail "device reports API $api_level, expected $EMULATOR_API_LEVEL -- wrong system image"

abi="$(prop ro.product.cpu.abi)"
[ "$abi" = "$EMULATOR_ARCH" ] ||
  fail "device reports ABI $abi, expected $EMULATOR_ARCH -- wrong system image"

gralloc="$(prop ro.hardware.gralloc)"
[ "$gralloc" = "minigbm" ] ||
  fail "ro.hardware.gralloc is '$gralloc', expected 'minigbm' -- start the emulator with
  -feature Minigbm -prop qemu.hardware.gralloc=minigbm
See this script's header for why an instrumented UI test cannot survive without it."

adb reverse "tcp:$NAVIDROME_PORT" "tcp:$NAVIDROME_PORT"

echo "emulator ready: android-$api_level $EMULATOR_TARGET $abi, gralloc=$gralloc," \
     "tcp:$NAVIDROME_PORT reversed to the host"
adb reverse --list
