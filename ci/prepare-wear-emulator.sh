#!/usr/bin/env bash
#
# The Wear OS half of `ci/prepare-emulator.sh`: waits for a booted watch emulator, checks it really
# is a watch, and sets up the `adb reverse` that lets `http://localhost:4533` inside the emulator
# reach the Navidrome container on the host (ci/navidrome.compose.yml).
#
# WHY THIS IS A SEPARATE SCRIPT. A Wear system image is a different SDK package, a different device
# profile and a different AVD from the API 37 phone image `ci/prepare-emulator.sh` guards, and the
# two are launched by two separate steps of the same job. Widening the phone script to accept either
# would mean it could no longer fail on "wrong system image", which is the main thing it is for.
#
# WHY THE WATCH CHECK MATTERS MORE THAN THE API CHECK. `:wear:connectedDebugAndroidTest` installs
# and runs happily on a phone image -- `uses-feature` filters Play, not `adb install` -- so a wear
# suite that ran on the phone emulator would be **green and worthless**. Worse here than in the
# general case: `:wear` and `:app` share the applicationId `app.muplay`, so such a run would also
# reinstall the phone app underneath itself. This script fails on it, and
# `WearSessionJourneyTest.thisIsActuallyAWatch` asserts `PackageManager.FEATURE_WATCH` again from
# inside the APK. Two independent checks, because either one can be skipped and neither knows about
# the other.
#
# --- The emulator this expects to be talking to ------------------------------------------------
#
# Locally, once the AVD exists:
#
#   $ANDROID_HOME/emulator/emulator -avd muplaywear \
#       -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
#
# `.github/workflows/e2e.yml`'s "Wear journey" step passes the same list as `emulator-options`.
# Note what is NOT in that list: the `-feature Minigbm -prop qemu.hardware.gralloc=minigbm` pair the
# phone script requires. That workaround was measured against the API 37 *phone* image and this
# repository has no measurement for any Wear image, so it is neither passed nor asserted here -- see
# the gralloc line near the bottom of this script.
set -euo pipefail

readonly NAVIDROME_PORT=4533

# The AVD coordinates, and the only copy of them outside `.github/workflows/e2e.yml`'s own job
# `env:` block. `ConventionTest`'s "the wear emulator coordinates in e2e yml and
# prepare-wear-emulator sh cannot drift apart" reads both files and fails the build if they
# disagree, and also asserts the workflow's `android-emulator-runner` inputs really read that
# `env:` block rather than hardcoding a literal beside it. Same mechanism the phone coordinates
# already have, for the same reason: a comment asking two files to be kept in sync is not one.
#
# `android-wear-signed`, NOT `android-wear`, and this is the plan document's one factual error about
# the SDK. Resolved against `sdkmanager --list` on 2026-08-27: there is no
# `system-images;android-36;android-wear;x86_64` -- Google renamed the tag when the images became
# Google-signed, and the last unsigned x86_64 Wear images are `android-34;android-wear;x86_64`
# (Wear OS 5) and `android-35-ext15;android-wear;x86_64`, the latter of which has no matching
# `platforms;android-35-ext15` package for the emulator action to install. The published
# `android-wear-signed` x86_64 images are android-33 (Wear OS 4 -- unsigned tag), 36 (Wear OS 6.0),
# 36.1 (6.1) and 37.0 (7.0).
#
# `36`, not `37.0`: both packages exist, and 36 is the highest whose *bare* level string is also the
# value the device reports back as `ro.build.version.sdk`. On a 37.0 image the package qualifier and
# the device property disagree ("37.0" vs "37"), which is exactly the mismatch the phone script had
# to reach for `ro.build.version.sdk_full` to resolve. API 36 is Wear OS 6.0, six releases above the
# `minSdk 30` `muplay.android.wear` sets.
readonly WEAR_API_LEVEL=36
readonly WEAR_TARGET=android-wear-signed
readonly WEAR_ARCH=x86_64
readonly WEAR_PROFILE=wearos_small_round

adb wait-for-device

# Never a fixed sleep: `adb wait-for-device` returns as soon as adbd answers, which is long before
# the framework is up.
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done

fail() {
  echo "$1" >&2
  exit 1
}

prop() {
  adb shell getprop "$1" | tr -d '\r'
}

# The check this whole script exists for, and it is first because everything below it is worthless
# on the wrong device. `ro.build.characteristics` is a comma-separated list ("nosdcard,watch" on a
# Wear image, "emulator" on the phone one), so this matches a whole element rather than a substring.
characteristics="$(prop ro.build.characteristics)"
case ",$characteristics," in
  *,watch,*) : ;;
  *) fail "ro.build.characteristics is '$characteristics', which does not include 'watch'.
This is not a Wear OS system image. A wear suite that runs here is green and proves nothing, and
because :wear and :app share the applicationId app.muplay it would also reinstall the phone app
underneath itself -- see this script's header." ;;
esac

# `ro.build.version.sdk`, not the phone script's `ro.build.version.sdk_full`: this image's package
# qualifier is the bare "36", so the bare property is the one that can disagree with it. See the
# WEAR_API_LEVEL comment above.
api_level="$(prop ro.build.version.sdk)"
[ "$api_level" = "$WEAR_API_LEVEL" ] ||
  fail "device reports API $api_level, expected $WEAR_API_LEVEL -- wrong Wear system image"

abi="$(prop ro.product.cpu.abi)"
[ "$abi" = "$WEAR_ARCH" ] ||
  fail "device reports ABI $abi, expected $WEAR_ARCH -- wrong Wear system image"

# Reported, not asserted, and the difference is the whole point. The Minigbm workaround
# `ci/prepare-emulator.sh` documents was measured against the API 37 *phone* image, and this
# repository has no measurement for any Wear image. Asserting a value nobody has measured would be a
# check whose verdict does not depend on the thing it claims to check. If a wear run dies with
# "INSTRUMENTATION_ABORTED: System has crashed", add
#   -feature Minigbm -prop qemu.hardware.gralloc=minigbm
# to this emulator's options in e2e.yml, re-measure, and turn this line into a hard check -- with
# the evidence written down, the way the phone script's header does it.
echo "gralloc=$(prop ro.hardware.gralloc) (not asserted -- no measurement for the Wear image yet)"

adb reverse "tcp:$NAVIDROME_PORT" "tcp:$NAVIDROME_PORT"

echo "wear emulator ready: android-$api_level $WEAR_TARGET $abi ($WEAR_PROFILE)," \
     "tcp:$NAVIDROME_PORT reversed to the host"
adb reverse --list
