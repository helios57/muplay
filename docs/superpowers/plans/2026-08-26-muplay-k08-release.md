# Plan 8 — Release engineering and Google Play

**Status:** written 2026-08-26, after Plans 1–3 landed complete and 4–7 were in flight.

This plan takes a repository that builds a working debug APK and makes it a thing a stranger can
install from the Play Store. It is release *engineering*, not feature work: nothing here changes
what the app does, and everything here changes whether it can be shipped at all.

## Global constraints

Everything in the project's standing rules still applies — quality is priority 1, coverage floors
hold at 0.90, no Robolectric, no mock frameworks, JUnit Jupiter and AssertJ, dependency
minimalism, and every assertion must be watched failing. Three constraints are specific to this plan:

- **The signing key is not in this repository and never will be.** Not encrypted, not
  base64-encoded in a workflow file, not in `local.properties` committed by accident. A leaked
  upload key means an app that can be impersonated. `keystore.properties` is git-ignored, CI reads
  a secret, and a `ConventionTest` rule asserts no keystore material is tracked.
- **`applicationId` is permanent.** `app.muplay` can never be changed after the first production
  upload — a different id is a different app, with no upgrade path for installed users. Decide once,
  before the first upload, not after.
- **Cleartext HTTP must be impossible in release.** Already enforced for the network layer; this
  plan extends the check to the merged release manifest and the R8-processed release artifact,
  because a debug-only guarantee that nothing verifies on the release variant is not a guarantee.

## What Google Play actually requires

Split deliberately into what this repository can produce and what only the account holder can do.
The second list is short and it is a hard dependency — no amount of engineering removes it.

### Only the account holder can do these

| # | Thing | Why it blocks |
|---|---|---|
| A | A Play Console developer account, one-off registration fee | Nothing can be uploaded without it |
| B | Identity verification (and D-U-N-S if registering as an organisation) | Account stays restricted until it completes |
| C | For personal accounts registered from Nov 2023: a **closed test with 12 testers running 14 continuous days** before production access is granted | This is a calendar dependency, not a work item — it cannot be compressed |
| D | Decide how a reviewer signs in to a **self-hosted** server | Play review must be able to use the app; an app that shows only a login box for a server the reviewer does not have is rejected |
| E | Accept the Play App Signing terms, or take custody of the app signing key | Determines whether a lost key is recoverable |

### This repository produces these

Store listing text and graphics, a signed Android App Bundle, an R8 mapping file, a privacy policy
document, the content of the Data safety declaration, and the release gates that keep all of it true.

### Facts this plan is built on

Stated as of writing and **each one must be re-read from Play Console before the first upload** —
policy text changes and a stale requirement is worse than an unknown one.

- **App Bundle (`.aab`), not APK**, for new apps. `bundleRelease`, not `assembleRelease`.
- **`targetSdk` must stay within one year of the current Android release.** This project targets 36
  and compiles against 37, so it is current; it will need bumping on Play's schedule, not ours.
- **Data safety is a declaration you sign.** MuPlay's honest answer is unusually clean — it talks
  only to a server the user names, stores credentials in the AndroidKeystore, keeps book positions
  local, and has no analytics SDK — but "no data collected" is a claim that must match the binary.
- **A privacy policy URL is required**, reachable and specific to this app.
- **Android Auto and Wear OS are separate review surfaces.** Declaring either pulls in its own
  quality checklist and its own rejection path. They land only when Plan 5 does.
- **16 KB page size** support is required for recent target levels; this matters only for native
  libraries, which here means whatever Media3 ships, so it is a verification step, not a port.

## The defect class this plan is written against

Every other plan in this project defends against the *vacuous assertion*. This one defends against
its release-shaped cousin: **a guarantee that holds on the debug variant and is never checked on the
release one.** The repository has already produced two of exactly that — nothing in `check` compiled
`app/src/release/kotlin`, so a release-side refusal was verified once by hand and never again; and
`check` never compiled `androidTest`, so master silently carried a broken device tier for hours.

R8 makes this sharper, because minification can break reflection-driven code — Room, Hilt,
kotlinx-serialization, Media3's own session plumbing — in ways no unit test sees and no debug run
reproduces. **A release build that has never been run on a device is not a release build.**

## Task list

| # | Task | Deliverable a reviewer can accept or reject on its own |
|---|---|---|
| 1 | App identity — adaptive icon, the app theme, and a splash that is not the platform default | the launcher shows MuPlay's own icon, and the app opens in its own theme in both light and dark |
| 2 | R8 — minification, resource shrinking, and the keep rules that reflection needs | a minified release build installs and plays audio on the emulator |
| 3 | Signing and the bundle — an upload key that is never committed, and `bundleRelease` | a signed `.aab` exists, and a test proves no keystore material is tracked |
| 4 | Versioning — `versionCode`/`versionName` and a gate that they move together | a release cannot be built twice with the same version code |
| 5 | The privacy policy and the Data safety answers, derived from what the binary does | every claim in the policy is traceable to code, and a test names the traceability |
| 6 | Store listing — icon, feature graphic, screenshots taken from the real emulator, and the copy | a complete listing draft, with screenshots regenerable by a script |
| 7 | Release gates — `releaseCheck`, and the pre-launch rules that can be mechanised | cleartext, debug entry points and unminified release all fail the build |
| 8 | App access for review — how a reviewer reaches a self-hosted server | written instructions a stranger can follow, and whatever the app needs to make them true |
| 9 | The CI release pipeline — a tag produces a signed bundle and its mapping file | pushing a tag yields an uploadable artifact with no local step |
| 10 | Form-factor listings — Auto and Wear, when Plan 5 lands | each declared surface has passed its own checklist, or is explicitly not declared |

Tasks 1–4 are the critical path and are mutually independent except that 3 depends on 2. Task 10
depends on Plan 5 and is deliberately last.
