# Play Console — getting MuPlay onto Play

The sibling documents cover *what to say*: `STORE-LISTING.md` is the listing copy,
`PLAY-DATA-SAFETY.md` the declaration, `REVIEWER-ACCESS.md` how a reviewer signs in to a server they
do not have. This one covers *how the binary gets there*, and one decision that is permanent.

Facts marked **measured** were executed against this repository or this host on the date given.
Facts marked **read** come from a source file named beside them. Everything else is marked
inferred, because an instruction nobody walked is the failure mode this project is built against.

The console procedure below was measured end-to-end on 2026-09-08 by a sibling session doing the
same thing for another app (`github.com/helios57/familyguard`, `DEPLOYMENT.md` → *Publishing to
Google Play*). Cite `a254c1d` for the original walk-through; the signing-key step has since been
rewritten as `6a60918`, which carries the general rule below rather than the installed-base one it
replaced. What is written here is that procedure **plus** what changes for MuPlay, which is not a
detail — see the signing section.

---

## Measured state of this repository, 2026-09-08

| | |
|---|---|
| `applicationId` | `io.github.helios57.muplay` — permanent once the Play app is created (**read**, `app/build.gradle.kts:14`). Changed from `app.muplay` on 2026-09-08; see *The rename* below. |
| Next release | `versionCode = 202`, `versionName = "0.2.2"` would be next; 200 and 201 are spent (**read**, `app/release-history.tsv`) |
| Spent version codes | `1 / 0.1.0` (never uploaded), `200 / 0.2.0` (GitHub Release), `201 / 0.2.1` (uploaded to Play) |
| Tags pushed | `v0.2.0` and `v0.2.1`, both built green (**measured**, `gh run list`) |
| GitHub Releases | `v0.2.0`, public, carrying `app-release.apk` (**measured**, `gh release view`) |
| Repository secrets | all four set 2026-09-08 (**measured**, `gh secret list`) |
| Upload keystore | exists outside the repo, `muplay-upload`, created 2026-08-31, valid to 2054-01-16 (**measured**, `keytool -list`) |
| Its certificate SHA-256 | `97:D1:B2:C6:16:EC:15:C7:48:C2:99:C5:D7:FE:9B:FF:67:FD:F9:91:76:F9:2B:2E:D7:7E:1E:5E:31:A1:F5:E6` |
| Declared permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (**measured**, grep over every `AndroidManifest.xml`) |
| Play Console app | created 2026-09-08, `io.github.helios57.muplay`, app ID `4972426159135174049`, status *Entwurf* (**measured**, console) |
| App signing key | **muplay's own** `97:D1:B2:C6:…:F5:E6`. Google generated and activated `4F:8A:5D:07:…:F6:60` at app-creation time; it was replaced the same day, before any track or upload existed (**measured**, both halves — see step 2) |

Two consequences follow immediately. **No artifact signed by this key has ever left the machine** —
no release, no tester, no installed base anywhere. And **`release.yml` cannot run today**: its first
step fails naming the four missing secrets, by design (**read**, *"Require the signing secrets"*).

Note what changed on 2026-09-08 and what did not: no signed *artifact* has left, but the **private
key itself now has a second holder**, because step 2 uploaded it to Google. That is what Play App
Signing is, and it is the point of the correction below.

---

## The rename to `io.github.helios57.muplay`, and what it cost

Changed 2026-09-08 at the owner's request, from `app.muplay`, and it happened in **two steps a few
hours apart** — which is worth knowing, because the first step's own note in this file said the
second would not happen.

**Step one: the `applicationId` alone.** One line in `app/build.gradle.kts`, with `namespace` left
at `app.muplay`, so every Kotlin package, import and coverage-floor pattern was untouched. Verified
at the time: `assembleDebug`, `verifyDebugManifest` and the full `:app` JVM tier green, merged
manifest reading `package="io.github.helios57.muplay"`, and the built APK reporting the same id to
`aapt2 dump packagename`.

**Step two: the namespace and every package with it**, at the owner's request the same afternoon.
55 package directories moved, 635 files rewritten, both Room `schemas/` directories renamed (they
are named after the fully-qualified database class, and Room looks for them under the new name —
miss this and the exported schema history is silently orphaned). `namespace` and `applicationId` are
now the same string again.

What that cost, measured rather than predicted:

- **`verifyReleaseArtifact` was wired to the `applicationId`** and meant the namespace. Between the
  two steps the two differed, and the first release build failed with *"mapping.txt names no
  io.github.helios57.muplay class at all"*. Fixed to read `extension.namespace`; see the comment at
  that call site, which keeps the measurement now that the strings agree again and the divergence is
  invisible.
- **`StoreListingTest`'s `every claim names code that still exists`** went red on nine store-listing
  claims whose evidence paths had moved — the gate doing exactly its job, and the reason the docs
  pass is not optional.
- **The archived plan and spike documents under `docs/superpowers/` were deliberately left alone.**
  They are dated records of the tree as it was, and this repository's own rule is that renaming an
  identifier inside a measurement record does not move it. Expect paths there to read `app/muplay`;
  that is history, not rot. The same applies to the quoted compiler and test output in `CLAUDE.md`.

**It is a package rename, which to Android means a different app.** No update path from a build
carrying the old id, side-by-side install, and no data carried over. That is free while the app has
zero installs and expensive afterwards. MuPlay has at least one: the owner's own phone, installed
during development. Its local state does not survive, and for this app local state is the audiobook
resume positions, which exist nowhere else by design. Reinstalling is the whole remedy and the cost
is a handful of test positions -- but it is the same data-loss shape the signing section is about,
and it should be a decision rather than a surprise.

**The saved server login goes with it, and that is what the owner noticed.** Two independent
mechanisms, both one-time and both in that day's rename: the new `applicationId` is a different app,
so it does not inherit `files/credentials.preferences_pb`; and the Keystore alias moved with the
package in the same commit (`app.muplay.credentials` -> `io.github.helios57.muplay.credentials`),
which on its own would have made an existing sealed password unopenable. Neither is recoverable and
neither needs to be: Android Keystore entries are scoped to the installing app, so the old app's key
was never reachable from the new one, and no fallback alias could have read it.

**Ordinary updates keep the login, and that is measured rather than asserted.** Installed 0.2.2,
saved credentials through the shipped `CredentialStore`, `adb install -r` of 0.2.3, read them back:
the DataStore file was byte-identical and the sealed password still opened with its Keystore key.
`CLAUDE.md`'s *"A connected run uninstalls the app afterwards"* section records how to run that
measurement, and why the first attempt at it produced the opposite answer. Three tests hold the
three identities it depends on -- `CredentialStoreTest` pins the alias and the three preference keys
as literals, and `DataModuleTest.theProvidedCredentialDataStoreWritesWhereTheAppExpects` pins the
file name -- so a rename of any of them is a build failure rather than a silent sign-out.

What is deliberately **not** preserved is a reinstall: `allowBackup="false"` plus non-exportable
Keystore keys means an uninstall, a new device or a Play restore all start at the login screen. The
password is stored only because Subsonic token auth needs the plaintext to compute
`md5(password + salt)` per request, and a copy that could be restored elsewhere is the thing that
protects.

**Whether the rename was necessary was never measured, and that question is now closed.** It was
requested, not forced. The sibling session's `io.github.helios57.familyguard` *was* forced -- an
installed base on a child's phone pinned it to an existing `applicationId` -- and that session is
explicit that it never queried a short name, so its success was no evidence about `app.muplay`'s
availability. The Play Console availability check was the only authority and it was free to run
before creating anything; nobody ran it for `app.muplay`. The app entry now exists under
`io.github.helios57.muplay`, and a Play package name can never change after creation, so this is
settled by fact rather than by argument.

---

## The signing decision, and why the generic answer is wrong for MuPlay

Play App Signing splits one key into two. The **upload key** signs the bundle you upload and only
authenticates the upload; Google strips it and re-signs each delivered APK with the **app signing
key**. Google generates that second key when the app is created, and the create-app form does not
mention it.

The usual rule of thumb is *"no installed base yet, so let Google generate it"*. MuPlay has no
installed base, and that rule still gives the wrong answer here, for a reason specific to this
project:

**MuPlay is deliberately dual-distributed.** `release.yml` attaches the signed APK to a public
GitHub Release, and says why at length: *"MuPlay is distributed to people who are not on Google
Play, and an artifact nobody can download is not a distribution"* (**read**, its `permissions:`
block). If Google holds the app signing key, the GitHub APK and the Play install carry **different
certificates** — so Android refuses to update one with the other, in both directions. A listener who
sideloaded the APK and later wants the Play build has to uninstall first.

For most apps that is an inconvenience. For this one it is data loss: **book positions are local
only and never sent to the server** — the constraint the whole audiobook feature is built around —
so an uninstall drops every resume point permanently, with no server-side copy to sync back.

So the recommendation is to **upload the existing `upload-keystore.jks` as the app signing key**,
which keeps both channels on one certificate and keeps `release.yml`'s promise true.

**What it costs — corrected 2026-09-08 by doing it.** This paragraph used to say the account holder
would then own the key "with no Google-held copy to fall back on", so that losing the file would end
the ability to ship updates under this package name. That was inferred, and it is wrong about what
PEPK does. The tool encrypts the **private key** to a Google-supplied public key, and the zip you
upload contains it — `encryptedPrivateKey` beside `certificate.pem` (**measured**, `unzip -l`).
Google must hold it, because Play App Signing is Google signing every delivery.

So losing the local keystore does *not* end Play updates, and the console additionally offers
*Zurücksetzung des Uploadschlüssels anfordern* to re-key uploads (**observed** on the page; not
exercised). What it ends is the **GitHub half**: nothing can re-create the certificate a sideloaded
APK is signed with, so the two channels drift onto different certificates and the uninstall-to-switch
data loss this section exists to prevent comes back. Back the keystore up — the reason is the APK,
not Play.

**The window for this is before the first upload and before any track exists.** After that,
changing the key is disruptive rather than free.

---

## Before any of this: are you actually signed in?

Every console step below needs a signed-in browser, and an agent session driving Playwright MCP
will very likely **not** have one — measured 2026-09-08, and it cost twenty minutes.

Playwright MCP launches its own Chrome against a persistent profile under
`~/.cache/ms-playwright-mcp/mcp-chrome-<hash>/`, and a different session gets a different hash. So
"the browser is already logged in" can be true of the machine and false of the browser you control.
There were ten such profiles here; one held the session. What varies the hash was not measured, so
do not predict it — check.

Counting cookies is the cheap way to find which profile has it (read-only, immutable URI, counts
only — never read the values):

    sqlite3 "file:<profile>/Default/Cookies?immutable=1" \
      "select count(*) from cookies where host_key like '%google.com'"

Measured: the signed-in profile held **25**, the fresh one **3** — and all three of those were
consent cookies picked up by the failed navigation itself.

**The discriminator, because the console's own behaviour is ambiguous.** A bounce from
`/console/u/0/developers/` to `/console/about/` has at least three causes, and they look identical:
no session at all, a session with no developer account, or an account gated mid-verification. Probe
`myaccount.google.com` instead — it bounces to a marketing page **only** when there is no session,
while a real session missing a developer account keeps you on an account page. Take that reading
before concluding anything about the console.

Two things that follow, both learned the expensive way:

- **Releasing another session's browser lock does not help.** The `SingletonLock` only stops two
  Chromes holding one profile; it has nothing to do with which profile you were given. A handoff
  that frees the lock and stops there solves nothing.
- **Repointing the MCP at someone else's profile is not a neutral fix.** That profile carries a
  whole Google session, not a Play-Console-scoped one. It is a config change and an access
  question, and it belongs to the account holder rather than to whoever is driving.

## Order of operations

1. Create the app in Play Console.
2. **Set the app signing key.** Before any bundle, any track, any release — and nothing in the
   console will remind you, because creating the app has already silently done it for you.
3. Configure the four repository secrets.
4. Tag, and let CI build and sign.
5. Upload to internal testing.

Steps 1, 2 and 5 need the console and the account holder. Steps 3 and 4 need the keystore.

### 1. Create the app

Play Console → *Create app*. Three choices are permanent: the package name, free vs paid, and the
app's existence — there is no delete, only unpublish. Press *Check availability* on
`io.github.helios57.muplay` first; it must match `applicationId` character for character.

The submit button is never disabled even with required fields empty, so its enabled state is not
evidence the form is valid — the red validation error is. If a submit fails, re-read the app list
before retrying, or you risk creating a duplicate.

### 2. Set the app signing key — the step nothing will prompt you for

**Assume you will not be reminded.** The key page is not part of the creation flow, so no screen
puts this decision in front of you: the app is created, a key is generated and activated, and the
console reports success. There is no warning to dismiss and nothing to get wrong — the failure mode
is not choosing badly, it is never seeing that there was a choice.

Measured from the other side (**sibling session, 2026-09-08**): they nearly missed it too, on an app
where the same interoperation concern applied, and looked only because it happened to be in their
head already. An app where nobody happens to be carrying that concern is an app where nobody looks.
That is the whole reason this step has a heading rather than a sentence.

Go to `…/app/<appId>/keymanagement` and read the current SHA-256 **before uploading anything**.
Then *Change key* → *Export and upload a key from Java KeyStore* (PEPK). The two warnings it shows —
testers lose updates, uploaded versions become unusable — are void while no track and no upload
exist, which is the whole reason this step comes second.

PEPK dies with a `NullPointerException` in `KeystoreHelper.loadKeystore` when stdin is a pipe,
because `System.console()` is null; it reads like a corrupt keystore rather than a missing terminal.
Run it under a pty (**measured** by the sibling session, 2026-09-08):

    printf '%s\n%s\n' "$PW" "$PW" | script -qec "java -jar pepk.jar \
      --keystore=… --alias=muplay-upload --output=output.zip \
      --include-cert --rsa-aes-encryption --encryption-key-path=./encryption-public-key.pem" /dev/null

The pty **echoes the passphrase** into whatever captures that output. Check the log with `grep -qF`
for the password before printing it anywhere, and shred the log afterwards.

After saving, reload the page and check **both halves**: the fingerprint above is present *and*
Google's generated one is gone. "Ours is present" alone cannot tell a replacement from an addition.

**Done, 2026-09-08.** On the reloaded page the Digital Asset Links snippet and the upload-key
certificate both read `97:D1:B2:C6:…:F5:E6`, `4F:8A:5D:…` appears nowhere in the page text, and the
*Bisherige App-Signaturschlüssel* section is gone entirely — a replacement, not an addition. Five
things that were not obvious going in:

- **The public encryption key is a per-app download, not a constant.** Current PEPK takes
  `--rsa-aes-encryption --encryption-key-path=<pem>` and the console serves a 3072-bit RSA public
  key for this app. The old fixed `--encryptionkey=<hex>` form that fills the public internet is
  gone; do not carry one forward from an older runbook.
- **Verify what PEPK exported before uploading it.** `unzip` the output and run
  `openssl x509 -in certificate.pem -noout -fingerprint -sha256`. It is the only check that the
  `--alias` you typed is the key you meant, it costs one command, and it is the difference between
  uploading a key and uploading *a* key.
- **Skip the optional "generate a new upload key" step.** Leaving it alone makes the upload key
  *be* the app signing key, which is the entire point here: one certificate behind the Play build
  and the GitHub APK.
- **The upload-key certificate appears immediately**, contradicting the page's own placeholder —
  *"fingerprints are shown here after you upload your first app bundle"*. That text is written for
  the Google-generated case; ours was on the page with no bundle in existence.
- The *Quantenbereit (Beta)* badge and the classic/post-quantum fingerprint pair belong to
  Google-generated keys only. A plain RSA keystore has no PQC half, so both disappear — that is
  expected, not a downgrade to investigate.

Java 25 ran `pepk.jar` without complaint, so this step does not need the project's JDK 21.

**If the browser tooling dies mid-flow, do not restart the flow.** The Playwright MCP server
dropped its connection on the *first download click* here and took its Chrome with it, which looks
like a dead end because the profile holding the Google session is the MCP's own. It is not one:
Chrome relaunches against that same profile directory with `--remote-debugging-port=9222`
(`setsid`, detached, so no tool call owns it — CLAUDE.md records what happens otherwise), and
`playwright-core` is already on this machine under `/usr/lib/node_modules/@playwright/mcp/`, so
`chromium.connectOverCDP('http://127.0.0.1:9222')` drives the console with the session intact.
`Browser.setDownloadBehavior` with an explicit `downloadPath` is what makes the two downloads land
somewhere you can find them. Remove `Singleton*` from the profile first or Chrome refuses to open
it.

### 3. Configure the four repository secrets — done 2026-09-08

`release.yml` reads `MUPLAY_KEYSTORE_BASE64`, `MUPLAY_KEYSTORE_PASSWORD`, `MUPLAY_KEY_ALIAS` and
`MUPLAY_KEY_PASSWORD` (**read**). None are set today. The keystore goes in base64; the workflow
materialises it into `RUNNER_TEMP`, outside the workspace, and shreds it on `always()`.

All four are set (`gh secret set`, values from the keystore directory; `gh secret list` shows the
four names and nothing else). The base64 was checked to decode byte-identically to the `.jks` before
it became a secret -- a mis-encoded one reaches AGP as *"keystore was tampered with, or password was
incorrect"*, which names the wrong cause. The store and key passphrases are the same string here,
which is worth knowing rather than assuming, because `release.yml` keeps them as two secrets:
`keytool -certreq -storepass X -keypass X` is the one-command check.

**Run CI's exact Gradle line locally before spending a tag.** It is one command with five environment
variables and it costs 3-5 minutes, against a tag that is awkward to unspend. Doing that is what
caught `verifyReleaseArtifact` reading the applicationId (see *The rename*); a green local run then
made the real tag uneventful.

### 4. Tag — done, twice

Pushing `v0.2.0` is the entire interface — there is no local release step and nobody needs the key
on a laptop. The tag must match `versionName` or `verifyReleaseTag` refuses the build. CI produces
the signed `.aab`, the `mapping.txt`, and a public GitHub Release carrying the APK.

Append the spent code to `app/release-history.tsv` in the same commit that moves the version.

### 5. Upload to internal testing

`…/tracks/internal-testing` → *Create new release*. Internal testing needs **no** store listing, no
content rating and no data-safety declaration — a bundle and a release name are enough. It is by far
the cheapest way to get something published.

One trap: the required *Release name* field, when empty, disables *Next* and reads exactly like the
bundle having been rejected. Fill the name and re-read the button. A genuinely refused bundle looks
different — it surfaces at the review step as *"You need to upload an APK or Android App Bundle for
this app"* with no bundles listed.

Upload `mapping.txt` alongside **this exact bundle**; a mapping file from any other build
deobfuscates nothing.

---

### Where this actually got to, 2026-09-08

- **v0.2.0** tagged and built (6 min). Public GitHub Release with `app-release.apk`, private
  workflow artifact with the `.aab`, `mapping.txt` and both gate censuses.
- **v0.2.1** tagged after the namespace rename; this is the build that went to Play.
- **The bundle is uploaded to the internal test track** as a saved draft: `201 (0.2.1)`, release
  notes written, Play parsing it as *API 26 or higher, target 36, 4 ABIs*. That Play accepted the
  upload at all is the end-to-end proof of step 2 -- an upload signed by anything other than the
  registered key is rejected at this exact point.
- **Two of Play's own "optimisations" are offered and only one is on.** *Releases are signed by
  Google Play* is active, which is step 2. *Automatischer Schutz* (automatic integrity protection)
  is deliberately **off**: it rewrites the delivered artifact, and this project's whole
  dual-distribution promise is that the APK on GitHub and the build on Play are the same code.
  Turning it on would quietly make that false. If anyone ever turns it on, `release.yml`'s
  `permissions:` block is the comment that stops being true.

- **The internal test is live.** The five tester addresses the account holder supplied are an
  account-level email list (*MuPlay internal testers*, 5 users), ticked for this track, and the
  release was published on 2026-09-08 at 18:54: *Aktiv — Für interne Tester verfügbar*, one version
  code, *Nicht überprüft*. Version code **201 is now spent** and `app/release-history.tsv` says so.
- **The opt-in link** testers must open before the Play Store will show them the app is
  `https://play.google.com/apps/internaltest/4700194580516551844`. It is inert until a tester's
  Google account is on that list, so it is not a secret, but it is also not a download link: each
  tester opens it, accepts, and only then does the store page exist for them.
- **Testers see a placeholder name.** Play says so itself: *"Der temporäre Name deiner App ist
  `io.github.helios57.muplay (unreviewed)`"*. That is not a defect and no store listing fixes it on
  the internal track -- it is what an unreviewed app is called until the *App content* forms and the
  listing are completed, which is the production path below, not this one.

**Two console findings worth keeping, because both cost a wrong diagnosis first.**

The email-list dialog saves behind a **second, stacked confirmation dialog** (*"E-Mail-Liste
erstellen? Diese E-Mail-Liste steht dir dann für alle Apps in deinem Entwicklerkonto zur
Verfügung."*). Until it is answered, the testers tab still reads *"Create an email list to add
users that have access to this testing program"* -- which reads exactly like the save having failed
validation, and sent two retries of the whole form. The tab is telling the truth about a list that
does not exist yet; the dialog underneath is where the list is.

And a **substring match on an accessible name clicks the wrong button**. `Erstellen` matched the
page's disabled *"Neuen Release erstellen"* before the dialog's *"Erstellen"*, and earlier in this
same session a substring `Weiter` navigated away from the draft release entirely and lost it. Match
the accessible name **exactly**, and prefer the last enabled match, which is the topmost dialog's.
This is the same shape as every self-matching check in `CLAUDE.md`: the query returned a real
observation of the wrong thing.


### 203 went to the internal track, 2026-09-11

Uploaded and published as `203 (0.2.3)` at 12:22, *Aktiv -- Für interne Tester verfügbar*, one
version code, *Nicht überprüft*. Play parsed the bundle exactly as it parsed 201 -- *API 26 oder
höher, Ziel-SDK 36, 4 ABIs* -- which is the signing key proved end to end a second time: an upload
signed by anything but the registered key is refused at this exact point.

**Note what the release actually was, because it is the part worth remembering.** `master` and the
`v0.2.3` tag are *identical in app code*; the only commit between them moves `versionCode` to 204.
So there was nothing to build: the newest build already existed, and the bundle uploaded here is
the one CI produced from the tag on 2026-09-09 (workflow artifact `muplay-v0.2.3`, 90-day
retention) rather than anything built on a laptop. Version code 203 is now spent twice over -- a
public GitHub Release and an internal Play release -- and that is fine. Play refuses a *reused*
code and it had only ever seen 201.

The tester list is a property of the **track**, not of the release: *MuPlay internal testers*,
5 users, was still attached with nothing to re-tick, and the opt-in link is unchanged.

Both console traps from the 2026-09-08 account above still hold, so read that section first. Two
things to add to it:

- The *Release-Name* field arrives **pre-filled** from the bundle (`203 (0.2.3)`, 11 characters),
  so the empty-name trap does not fire on an upload that succeeded. It is still the field to read
  when *Weiter* looks dead.
- The publish button is `Speichern und veröffentlichen`, and it appears **twice**: once on the
  review page and once in the confirmation dialog that the first click opens (*"Änderung bei Google
  Play veröffentlichen? Diese Änderung wird sofort veröffentlicht."*). Same accessible name, same
  exact string -- so this is the stacked-dialog shape from the email-list trap above, and
  exact-match-then-take-the-last-enabled clicks the right one both times.

### The two channels really are one certificate — measured 2026-09-08

The argument for replacing Google's generated app signing key was that a GitHub-released APK and a
Play-served install should be the *same* app to Android, so a tester can move between the channels
without an uninstall. That was the intention; here is the measurement, taken when a tester could
see the internal test listing but got *"nicht gefunden"* on download and needed an install that
worked immediately.

Three fingerprints, three independent sources, one value
`97:D1:B2:C6:16:EC:15:C7:48:C2:99:C5:D7:FE:9B:FF:67:FD:F9:91:76:F9:2B:2E:D7:7E:1E:5E:31:A1:F5:E6`:

- `apksigner verify --print-certs` on the **public `v0.2.1` APK** from GitHub Releases —
  `V3.0 Signer: certificate SHA-256 digest: 97d1b2c6…f5e6`.
- The console's **upload key** certificate.
- The console's **Digital Asset Links** snippet, which Play generates from the *app signing* key —
  the one that signs what a user actually installs. This is the load-bearing one: the upload key
  matching proves nothing about what Play serves, and this is the only place on that page where the
  app signing key's fingerprint appears as text rather than behind a copy button.

So the GitHub APK is installable now and Play updates it in place later. Worth knowing which way
round that argument runs: it is the *third* fingerprint that closes it, and reading only the first
two would have produced the same conclusion for the wrong reason.

**And the draft-status red herring, since it looks like the obvious cause.** The app list shows
MuPlay as *App-Entwurf*, which reads like "not published, therefore not downloadable". Play's own
dashboard contradicts it in the same breath: *"Du kannst direkt damit beginnen, deine App mit
internen Tests zu prüfen."* Draft is the normal state of an app whose only track is internal
testing, and it does not gate the install. What does gate it is propagation (Play's own dialog says
"normalerweise innerhalb von einer Stunde … manchmal kann es aber auch länger dauern") and the Play
Store app's active account, which is tracked separately from the browser's.


## Permissions

A bundle can be refused outright over a permission, with no help link and no declaration form —
measured by the sibling session on `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, proven as a clean A/B on
one tree.

**MuPlay declares nothing in that class** (**measured**, above). The one that needs paperwork later
is `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Play requires a foreground-service-type declaration under
*App content* for production, though not for internal testing. `POST_NOTIFICATIONS` is a normal
runtime permission and needs no declaration — it is requested at launch (`MainActivity`) and held
there by `ConventionTest`'s *every runtime permission this app declares is requested somewhere in
src main*.

---

## Open question, unanswered

Whether publishing to Play makes a **sideloaded** build of the same package and key stop being
flagged by Play Protect. Google's guidance says nothing about signing certificates, developer
reputation or install volume, so there is no authority to read — it has to be measured on a real
handset.

**The test is cheap and needs no version bump**, because Play Protect scans at install time: once
the listing has propagated (about an hour), reinstall *the same APK* from the phone's browser and
see whether the warning still appears.

It matters here far more than it would for a Play-only app, and the bad answer is the interesting
one. If a Play listing does **not** launder the sideload, then the GitHub Releases channel stays
warned-about however the Play side is configured — and `release.yml`'s argument for publishing an
APK at all (*"an artifact nobody can download is not a distribution"*) needs revisiting rather than
simply defending, because a download most people are told not to open is a weaker distribution than
that comment assumes. Note that this is orthogonal to the signing decision above: same-certificate
distribution fixes the *update* path between the two channels, and says nothing about the warning.

---

## What only the account holder can do

Creating the app, checking name availability, the key upload, the secrets, and every console step.
This document does not make the signing decision — it makes it real, and recommends one.
