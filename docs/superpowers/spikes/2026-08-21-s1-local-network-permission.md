# Spike S1 — Does `ACCESS_LOCAL_NETWORK` gate `10.0.2.2` on API 37?

**Status: BLOCKING spike, answered.** Confidence: high. Each of the four
variants below was captured cleanly exactly once (not re-run) — the "no
manifest change needed at `targetSdk 36`" and "`targetSdk 37` blocks
same-subnet traffic" findings each rest on that single clean capture per
variant, not on repeated runs. What does corroborate them across runs:
variants 2 and 3 (both `targetSdk 37`) independently produced the identical
failure signature (`SocketTimeoutException` after ~4 s) despite differing in
whether the permission was declared, and variant 4 (same device, same code,
permission granted) went from that timeout to a 145 ms success — a
same-device, single-variable contrast, not a rerun of the same configuration.

## The question

The nightly CI lane plans to reach a containerized Navidrome on the host from an
emulator, using the QEMU host alias `10.0.2.2` (or `adb reverse` to `127.0.0.1`).
Android 16+ introduced a runtime "local network protections" permission,
`ACCESS_LOCAL_NETWORK`. If MuPlay needs this permission to reach `10.0.2.2` on an
API 37 device, every plan's debug manifest and the nightly lane's device setup
need to account for it.

Four concrete sub-questions, from the task brief:

1. Does an app with `targetSdk 36` reach `10.0.2.2` on an API 37 device **without**
   declaring the permission?
2. Does declaring `targetSdk 37` change that answer?
3. Is the permission's effect on `10.0.2.2` the same as on a real LAN address?
4. If required, is it a normal (install-time) permission or a runtime one?

## Environment

- Host: `/home/helios/Android/Sdk`, `avdmanager`/`emulator` from `cmdline-tools/latest`
  and `emulator/`, Java 21 (`openjdk 21.0.11`), Gradle 9.7.1, AGP 9.3.1 (matching
  this repo's `libs.versions.toml`).
- AVD: `muplay37`, created fresh —
  `avdmanager create avd -n muplay37 -k "system-images;android-37.0;google_apis;x86_64" -d pixel_6`.
  Booted headless: `emulator -avd muplay37 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim`.
  (AVD's default `hw.ramSize=1536M` / `disk.dataPartition.size=800M` were too small
  for this Google-APIs image and caused low-memory-killer thrashing and
  `system_server` instability; raised to `hw.ramSize=6144M`,
  `disk.dataPartition.size=6000M`, `vm.heapSize=768M`, `-wipe-data`. This is an
  environment note for whoever sets up the real nightly runner, not part of the
  permission finding itself.)
- Device confirmed: `ro.build.version.sdk=37`, `ro.build.version.release=17`,
  `ro.build.version.release_or_codename=17`, fingerprint
  `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys`,
  security patch `2026-05-05`. This is genuinely Android 17 / API 37, not a preview.
- **Deviation from the task brief:** the brief's Step 1 recipe tests on an API
  36 emulator with `adb shell am compat enable RESTRICT_LOCAL_NETWORK` to
  force the future behaviour on. This spike instead used a real, GA API 37
  image directly (available in this environment, per the task's own
  environment notes) and varied `targetSdk` in the app instead of forcing a
  compat flag on the platform. This tests the real, shipped API 37 enforcement
  rather than a forced approximation of it on API 36, which I judge to be
  strictly stronger evidence — but it is a real substitution for what the
  brief asked for, not what was literally specified, and is disclosed here
  rather than left implicit.
- Spike app: `app.muplayspike`, a minimal throwaway Android app (`compileSdk 37`,
  `minSdk 26`, plugin `com.android.application` 9.3.1 only — no Hilt, no Media3).
  Built as a standalone Gradle project outside the repo
  (`scratchpad/s1-spike/`), never added to `settings.gradle`. **Not committed.**
- Host HTTP server: `python3 -m http.server 8765 --bind 0.0.0.0`, serving a
  1-line `index.html`, reachable at both the QEMU alias (`10.0.2.2:8765`, no
  `adb reverse` needed — this address is the emulator's own built-in NAT alias
  for the host) and the host's real LAN IP (`192.168.0.56:8765`, the machine's
  `enp1s0` address).

## What was run

A single-Activity app (`MainActivity`) that, in `onCreate`, spawns a background
thread and does a plain `HttpURLConnection` GET (4 s connect/read timeout) to
each of two URLs, logging the outcome to `Log.i("S1SPIKE", ...)`:

```java
attempt("QEMU_ALIAS", "http://10.0.2.2:8765/");
attempt("LAN_ADDR",   "http://192.168.0.56:8765/");
```

Four manifest/permission variants were built and run against the identical
emulator, each installed with `adb install -r`, launched with
`adb shell am start -n app.muplayspike/.MainActivity`, and read back via
`adb logcat -d | grep S1SPIKE`:

| # | `targetSdk` | `ACCESS_LOCAL_NETWORK` declared? | Granted (`pm grant`)? |
|---|---|---|---|
| 1 | 36 | no | n/a |
| 2 | 37 | no | n/a |
| 3 | 37 | yes | no (explicitly `pm revoke`d before the run) |
| 4 | 37 | yes | yes (`adb shell pm grant app.muplayspike android.permission.ACCESS_LOCAL_NETWORK`) |

All four manifests also declare `android.permission.INTERNET` and
`android:usesCleartextTraffic="true"` — the first attempt (variant 1, before
this was added) failed both URLs with
`java.io.IOException: Cleartext HTTP traffic to 10.0.2.2 not permitted`, which
is Android's unrelated Network Security Config default for `targetSdk >= 28`,
not the local-network permission. This is a real, separate manifest requirement
worth noting for the nightly-lane debug manifest (see "What this means" below).

Manifest for variant 3/4 (`with_permission.xml`):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
<application android:usesCleartextTraffic="true" ...>
```

## Raw evidence

### Variant 1 — `targetSdk 36`, permission not declared

```
08-21 23:57:48.272  RESULT label=QEMU_ALIAS url=http://10.0.2.2:8765/ outcome=SUCCESS code=200 bodyLen=11 elapsedMs=1353
08-21 23:57:48.480  RESULT label=LAN_ADDR   url=http://192.168.0.56:8765/ outcome=SUCCESS code=200 bodyLen=11 elapsedMs=208
```

### Variant 2 — `targetSdk 37`, permission not declared

```
08-22 00:08:24.872  RESULT label=QEMU_ALIAS outcome=FAILURE exception=java.net.SocketTimeoutException
                     message=failed to connect to /10.0.2.2 (port 8765) from /10.0.2.15 (port 54024) after 4000ms elapsedMs=4527
08-22 00:08:24.938  RESULT label=LAN_ADDR   outcome=SUCCESS code=200 bodyLen=11 elapsedMs=66
```

### Variant 3 — `targetSdk 37`, permission declared but **not** granted

```
$ adb shell pm revoke app.muplayspike android.permission.ACCESS_LOCAL_NETWORK
$ adb shell dumpsys package app.muplayspike | sed -n '/runtime permissions:/,/^$/p'
      runtime permissions:
```

```
08-22 00:10:29.953  RESULT label=QEMU_ALIAS outcome=FAILURE exception=java.net.SocketTimeoutException
                     message=failed to connect to /10.0.2.2 (port 8765) from /10.0.2.15 (port 58980) after 4000ms elapsedMs=4044
08-22 00:10:29.964  RESULT label=LAN_ADDR   outcome=SUCCESS code=200 bodyLen=11 elapsedMs=10
```

Identical failure mode to variant 2 — merely listing the permission in the
manifest does nothing on its own.

### Variant 4 — `targetSdk 37`, permission declared **and granted**

```
$ adb shell pm grant app.muplayspike android.permission.ACCESS_LOCAL_NETWORK
$ adb shell dumpsys package app.muplayspike | sed -n '/runtime permissions:/,/^$/p'
      runtime permissions:
        android.permission.ACCESS_LOCAL_NETWORK: granted=true
```

```
08-22 00:12:19.225  RESULT label=QEMU_ALIAS outcome=SUCCESS code=200 bodyLen=11 elapsedMs=145
08-22 00:12:19.304  RESULT label=LAN_ADDR   outcome=SUCCESS code=200 bodyLen=11 elapsedMs=78
```

Fast and clean once granted — confirms the timeout in variants 2/3 is the
permission gate acting, not incidental network flakiness (the same host, same
server, same emulator boot).

### Permission metadata

```
$ adb shell pm list permissions -f -g | grep -B3 -A8 ACCESS_LOCAL_NETWORK
  + permission:android.permission.ACCESS_LOCAL_NETWORK
    package:android
    label:access local network devices
    description:Allows the app to access local network devices
    protectionLevel:dangerous
```

`protectionLevel:dangerous` = a **runtime** permission, confirmed by the
package dump right after installing the variant-3/4 APK (before any `pm
grant` call):

```
$ adb shell dumpsys package app.muplayspike
    ...
    requested permissions:
      android.permission.ACCESS_LOCAL_NETWORK
      android.permission.INTERNET
    install permissions:
      android.permission.INTERNET: granted=true
```

`ACCESS_LOCAL_NETWORK` is listed under `requested permissions:` (so the
manifest declaration was accepted) but is absent from `install permissions:`
(the auto-granted, normal-protection-level permissions — only `INTERNET`
appears there) and, at this point, absent from `runtime permissions:` too
(quoted above, empty). It only appears under `runtime permissions:` — and
only after an explicit grant — as shown in the variant-3/4 blocks above.
This is what "requires an explicit grant" means concretely: it is not enough
for the manifest to declare it.

Corroborating platform evidence that this is a real, intentionally-shipped
feature on this build (not an artifact of the spike's own setup):

```
08-22 00:13:43.042  PackageCacher: Feature flags for package /product/apex/...:
  {android.permission.flags.access_local_network_permission_enabled=true}
```

This trunk-stable feature flag is `true` platform-wide on this API 37 image.

## Answers

1. **Does `targetSdk 36` reach `10.0.2.2` without the permission?** Yes —
   variant 1, both `10.0.2.2` and the real LAN address succeed with no
   permission declared at all.
2. **Does `targetSdk 37` change that?** Yes — variant 2, identical device,
   identical code, only the manifest's `targetSdk` changed: `10.0.2.2` now
   times out. **This is app-`targetSdkVersion`-gated, not device-`SDK_INT`-gated.**
   The exact same API 37 device enforces or doesn't enforce the permission
   purely based on what the *app* declares as its target SdkVersion. A runtime
   check of `Build.VERSION.SDK_INT` in application code would not correctly
   describe when this restriction applies.
3. **Is `10.0.2.2`'s treatment the same as a real LAN address?** **No.** In
   every targetSdk-37 variant, `10.0.2.2` was blocked while `192.168.0.56` (the
   host's real LAN-facing address) succeeded. The likely mechanism: `10.0.2.2`
   sits on the emulator's own subnet (`10.0.2.0/24` — the guest itself is
   `10.0.2.15`), so it is classified as same-subnet "local network" traffic.
   `192.168.0.56` is a *different*, non-adjacent subnet from the guest's
   point of view — reaching it requires the emulator's slirp NAT to route the
   packet out, which looks to the platform like ordinary internet-bound
   traffic, not local-network traffic, so it is never subject to the gate.
   **This means the LAN-address result in this spike does not simulate the
   real-world case of "phone and Navidrome server on the same home Wi-Fi
   subnet"** — that case is same-subnet, like `10.0.2.2`, and *would* be
   gated at `targetSdk 37`. I did not have a second real device on the same
   subnet to confirm this directly; the mechanism above is inferred from the
   consistent 10.0.2.2-vs-cross-subnet pattern across all three targetSdk-37
   variants, not independently verified against netd/ConnectivityService
   source. Confidence in the *behavioral* finding (10.0.2.2 blocked, routed
   address not blocked) is high; confidence in the *causal explanation*
   (same-subnet classification) is medium.
4. **Normal or runtime permission?** **Runtime (dangerous).** Declaring it in
   the manifest is necessary but not sufficient — an instrumented test (or the
   nightly lane's device-setup step) must also run
   `pm grant <package> android.permission.ACCESS_LOCAL_NETWORK` before the
   app under test can reach a same-subnet address. There is no way to grant it
   through a manifest attribute alone, and a real end-user install would see a
   system permission dialog (this was not directly observed — no UI test was
   run — but `protectionLevel:dangerous` is Android's standard mechanism for
   exactly that behavior).

**Failure mode:** `java.net.SocketTimeoutException` after the configured
connect timeout (~4 s in this test) — packets appear to be silently dropped,
not rejected with an immediate `EPERM`/`SecurityException`/connection-refused.
This matters operationally: a misconfigured nightly lane doesn't fail fast, it
hangs for the full timeout on every connection attempt (and again on every
retry), which reads as "Navidrome is unreachable" or "CI is slow," not as a
permission problem, unless someone knows to look for it.

## What this means for the plan

**No manifest change is required today.** The roadmap's own global constraint
is `targetSdk 36` (`compileSdk 37, targetSdk 36`, from
`docs/superpowers/plans/2026-08-21-muplay-roadmap.md`), and this spike shows a
`targetSdk 36` app reaches `10.0.2.2` on an API 37 device with zero extra
permissions, zero grants, and no behavioral difference from `targetSdk 36` on
an older device. The blocking risk the spike was designed to catch does not
currently apply. **This conclusion rests entirely on `targetSdk 36`, not on
having tested the specific address the nightly lane actually plans to use.**
Spec §10 commits the nightly lane to `adb reverse` → `127.0.0.1` (loopback),
not `10.0.2.2` — this spike tested `10.0.2.2` (the QEMU alias) and a
cross-subnet LAN address, not loopback. Loopback traffic is not obviously
routed through the same local-network classification as either of those; I
did not test it, and variant 1's "nothing is gated at `targetSdk 36`" result
covers it only by extension (targetSdk 36 gates nothing local-network-shaped
at all, by every address tested), not by direct measurement. This is listed
again under "Not directly tested" below because it is exactly the gap that
will matter once `targetSdk` moves to 37.

**But it is a real, precisely-triggered future risk**, not a non-issue:

- **Trigger:** the moment `targetSdk` is bumped to 37 (for any reason — a
  future Play policy change, or a deliberate adoption of API 37 features),
  every instrumented test and every real device on the same subnet as its
  Navidrome server needs `ACCESS_LOCAL_NETWORK` granted, or local-network
  traffic silently times out.
- **Exact fix, precomputed so nobody has to rediscover it:**
  1. Add `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />`
     to the manifest (debug and, if `targetSdk` is bumped for the release build
     too, main).
  2. In the nightly lane's device-setup step (after install, before the test
     run), grant it non-interactively:
     `adb shell pm grant app.muplay android.permission.ACCESS_LOCAL_NETWORK`.
     There is no user available in CI to click a system dialog, so this step
     is mandatory, not optional.
  3. For a *real* Sonos/DLNA/local-Navidrome scenario on `targetSdk 37`,
     the same permission must be runtime-requested from the user (Settings →
     Apps → Nearby devices, per the platform's standard dangerous-permission
     UX) — this is genuinely user-facing UI work, not just a manifest line,
     once `targetSdk 37` is adopted for a release build.
  4. Any code path relying on this must not be written as
     `if (Build.VERSION.SDK_INT >= 37)` — that describes the *device*, not
     whether enforcement is active for *this app*. The correct trigger is
     "we are built with `targetSdk >= 37`," which is a compile-time fact, not
     a runtime branch.

- **Separately, unconditionally on the debug manifest today:** cleartext HTTP
  to `10.0.2.2`/`127.0.0.1` requires `android:usesCleartextTraffic="true"` (or
  an equivalent Network Security Config) regardless of `targetSdk` — this
  bit MuPlay's own spike app immediately and would bite the nightly lane's
  debug build the same way if not already present. This is unrelated to
  `ACCESS_LOCAL_NETWORK` but was discovered in the course of this spike and is
  worth calling out since it produces a similarly-confusing failure
  (`Cleartext HTTP traffic to ... not permitted`) the first time someone wires
  up the containerized-Navidrome lane.

- **Not directly tested / cannot yet confirm:**
  - **`127.0.0.1` / loopback via `adb reverse`, the actual address spec §10
    commits the nightly lane to** — not tested here at all. Only `10.0.2.2`
    (QEMU alias, same-subnet as the guest) and a cross-subnet LAN address
    were tested. Loopback is a distinct code path from both (it never leaves
    the device, whereas `10.0.2.2` is a real routed address even if it maps
    back to the host), so this spike cannot say whether `targetSdk 37` gates
    it the same way. Since it is moot today (variant 1 shows `targetSdk 36`
    gates nothing regardless of address), this is deferred rather than
    re-spiked, but it must be checked directly before `targetSdk` is ever
    bumped to 37, or the nightly lane risks the same silent-timeout failure
    mode this document describes, on the one address it actually uses.
  - UDP and port-1400 (Sonos control) gating specifically — this spike only
    exercised TCP HTTP GET. The spec's claim that the permission also gates
    "outgoing TCP to `:1400`... and all UDP" is a reasonable extrapolation
    from the same mechanism but was not independently verified here.
  - Whether a real end-user sees an actual system permission dialog (the
    dangerous-permission grant UI) — inferred from `protectionLevel:dangerous`
    but not observed directly; no UI-level test was run.
  - The precise underlying classification rule (same-subnet vs RFC1918) —
    behaviorally confirmed, mechanism inferred, not verified against platform
    source.

## Corrections needed elsewhere

Both are applied by this task (see the roadmap and design-spec diffs alongside
this document):

1. `docs/superpowers/plans/2026-08-21-muplay-roadmap.md`'s spike table said
   "If it fails" without stating the actual, now-known trigger condition
   (`targetSdk >= 37`, not "if Android 17 gates RFC1918 addresses" as a vague
   maybe). Updated to state the confirmed condition and point at this
   document.
2. `docs/superpowers/specs/2026-08-21-muplay-design.md` §7 says
   "Android 17 / API 37 makes `ACCESS_LOCAL_NETWORK` mandatory... Built behind
   `SDK_INT >= 37` from the start." This conflates device API level with app
   `targetSdkVersion` — corrected to state the gate is targetSdk-based, that
   it is currently inert for MuPlay (`targetSdk 36`), and to add the `pm grant`
   step required for CI.
