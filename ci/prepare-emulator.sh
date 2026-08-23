#!/usr/bin/env bash
#
# Waits for a booted emulator and gives it the one thing `FirstRunJourneyTest` needs but cannot do
# for itself: `adb reverse tcp:4533 tcp:4533`, so `http://localhost:4533` inside the emulator
# reaches the Navidrome container on the host (ci/navidrome.compose.yml). Note that a missing
# forward does not fail loudly -- the app's connection attempt simply times out.
#
# Run once per emulator boot, before `./gradlew :app:connectedDebugAndroidTest` -- locally and in
# `.github/workflows/e2e.yml` alike, so both run against the same device state.
#
# --- The emulator this expects to be talking to ------------------------------------------------
#
# Launch flags matter here, and one of them is not optional. Locally:
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
# not withdraw it. `mapper.ranchu.so` in `system-images;android-37.0;google_apis;x86_64` rev 6 --
# whose own `source.properties` asks for `emulator#36.5.11` -- asserts that capability is *absent*
# on its CPU-read path, so every `GraphicBuffer::lock` of a host colour buffer aborts the calling
# process. SurfaceFlinger's luma sampling hits it on a timer; system_server's task-snapshot
# persister hits it every time an activity is torn down, which is once per instrumented test. Both
# take system_server down with them and the run dies with "INSTRUMENTATION_ABORTED: System has
# crashed."
#
# The system image ships `mapper.minigbm.so` alongside `mapper.ranchu.so`; `ro.hardware.gralloc`
# picks between them, and `-prop qemu.hardware.gralloc=minigbm` sets it (`-prop` accepts `qemu.*`
# names only, which is why this is not `-prop debug.sf.luma_sampling=0` or similar).
# `-feature Minigbm` tells the host renderer the guest is doing so. Measured on `muplay37`:
# 4 SurfaceFlinger aborts per 150s window and a system_server abort at every activity teardown
# without these flags; 0 aborts and both journeys green with them, on the SDK's own emulator
# 37.1.11. Confirmed independent of `-gpu` (swiftshader_indirect / guest / host all abort without
# them) and of `hw.gltransport` (pipe / asg / virtio-gpu all abort without them).
#
# Remove the two flags once the image and the emulator agree again on their own.
set -euo pipefail

readonly NAVIDROME_PORT=4533

adb wait-for-device

# Never a fixed sleep: `adb wait-for-device` returns as soon as adbd answers, which is long before
# the framework is up.
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

# Fails the script (set -e) rather than letting the journey time out later against a device whose
# gralloc is still the broken one.
gralloc="$(adb shell getprop ro.hardware.gralloc | tr -d '\r')"
if [ "$gralloc" != "minigbm" ]; then
  echo "ro.hardware.gralloc is '$gralloc', expected 'minigbm' -- start the emulator with" >&2
  echo "  -feature Minigbm -prop qemu.hardware.gralloc=minigbm" >&2
  echo "See this script's header for why an instrumented UI test cannot survive without it." >&2
  exit 1
fi

adb reverse "tcp:$NAVIDROME_PORT" "tcp:$NAVIDROME_PORT"

echo "emulator ready: gralloc=$gralloc, tcp:$NAVIDROME_PORT reversed to the host"
adb reverse --list
