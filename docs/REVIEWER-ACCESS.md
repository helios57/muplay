# Play Console — App access for a self-hosted app

MuPlay is a client for a server **the user runs**. A Play reviewer opens it and sees three empty
boxes — server URL, username, password — and has none of them. Play's *App access* section is where
that gap is closed, and an app that appears non-functional to the reviewer is rejected. "It is in
the description" is not a remedy.

This document is the route, the exact text to paste into Play Console, the exact taps, and what
only the account holder can do. Every fact marked **measured** was executed against a real server or
a real device; everything else is marked as read from source or inferred, because an instruction
nobody walked is the failure mode this task exists to prevent.

## The decision is the account holder's

Plan 8 lists *"decide how a reviewer signs in to a self-hosted server"* as item **D** among the
things only the account holder can do. This document does not make that decision. It makes the
options real — what each costs, what the app needs for each to work, and what a stranger would
actually type — and recommends one.

---

## The two facts that constrain every option

**1. The URL must be `https://` for any host that is not `localhost`.** Measured previously in this
repository (Plan 8 Task 2, recorded in `CLAUDE.md` and in `FirstRunJourneyTest`'s header) on the
minified, release-signed APK: `http://10.0.2.2:4533` answered *"Could not reach the server."* and
`http://localhost:4533` connected and played, same install, minutes apart. Android's default network
security config for `targetSdk >= 28` forbids cleartext to a remote host and carves out `localhost`,
and no manifest can opt out. So a reviewer given an `http://` address for a remote server sees a
failure that looks exactly like a broken app.

**2. A reviewer account with no library grants dead-ends the app silently.** This is the finding
that matters most, and it is measured, not inferred — see *The zero-library trap* below.

---

## The zero-library trap — measured

Against a throwaway `deluan/navidrome:0.63.2` container (`p8t8-review-navidrome`, port 14533, the
repository's own `ci/fixtures` mounted read-only), using Navidrome's native REST API:

| Step | Request | Result |
|---|---|---|
| Rename library 1 | `PUT /api/library/1 {"name":"Review Music","path":"/music"}` | 200, renamed |
| Add a second library | `POST /api/library {"name":"Review Audiobooks","path":"/audiobooks"}` | `{"id":"2"}` |
| Create a non-admin user | `POST /api/user {"userName":"play-review","isAdmin":false,...}` | created |
| **That user, before any grant** | `GET /rest/getMusicFolders` (Subsonic, as `play-review`) | **`{"musicFolders":{}}` — empty** |
| Grant library 1 only | `PUT /api/user/<id>/library {"libraryIds":[1]}` | 200 |
| That user again | `GET /rest/getMusicFolders` | exactly `[{"id":1,"name":"Review Music"}]` |
| Grant both | `PUT /api/user/<id>/library {"libraryIds":[1,2]}` | 200 |
| That user again | `GET /rest/getMusicFolders` | both libraries |
| Browse as that user | `getAlbumList2?musicFolderId=1` | `Test Album` / `Test Artist` |
| Browse as that user | `getRandomSongs?musicFolderId=2` | the two seeded audiobooks |

Three disjoint observations of the same account at three different grant sets, so this is a real
per-call read of the grant and not a constant.

**Why it is a trap.** A freshly created Navidrome user is granted nothing, and the Subsonic API still
answers `status: ok`. Read from source (`SetupViewModel.tagging`):

    canContinue = current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED }

so with zero libraries the reviewer sees **"Connected to navidrome 0.63.2"**, the heading *"What is
each library for?"*, **no rows at all**, and a **permanently disabled Continue button** — a successful
sign-in that goes nowhere, with nothing on screen saying why. That is precisely the "appears
non-functional" rejection, arrived at by following correct-looking instructions.

The grant step is therefore not an optional polish step in the recipe below. It is the step the
whole route fails on.

---

## The options

### A — the public Navidrome demo, `https://demo.navidrome.org`

**Measured today** (all against the live host):

- `GET /` → 200. `GET /rest/ping` as `demo`/`demo` → `status: ok`, `type: navidrome`,
  `serverVersion: 0.63.2 (be10f89c)`, `openSubsonic: true` — the same Navidrome version this
  repository pins in CI.
- `getScanStatus` → 501 tracks, 50 folders.
- `getAlbumList2` → real, named albums (`8-bit lagerfeuer` / pornophonique, `Between two worlds` /
  Maya Filipič, `The Butcher's Ballroom` / Diablo Swing Orchestra …).
- `GET /rest/stream` with `Range: bytes=0-65535` → **HTTP/2 206**, `content-type: audio/mpeg`,
  `accept-ranges: bytes`, `content-range: bytes 0-65535/4747392`. Served through Cloudflare and
  Caddy; a non-browser client (curl) was not challenged.
- `getMusicFolders` → **exactly one library, id 1, "Music Library"**.
- navidrome.org's own demo page publishes the credentials as user `demo`, password `demo`, and
  notes *"Not all features are enabled in the demo. For instance, settings are disabled."*

**For:** costs nothing, needs no infrastructure, no secret to rotate, HTTPS with a valid certificate,
works right now, and it is the same server software the app targets.

**Against:**

- **One library, and it is music.** The listing's two headline claims — library-scoped shuffle and
  per-book audiobook resume — cannot be exercised at all. Library-scoped shuffle needs at least two
  libraries; there are no audiobooks to resume. A reviewer checking that the app does what the
  listing says has no way to.
- **It is somebody else's server.** Navidrome publishes it as a trial, not as review infrastructure
  for third-party apps. Naming it in App access points every review and re-review of every future
  update at a volunteer project's host.
- **No control.** If the password changes, the host is rate-limited, Cloudflare starts challenging
  the app's requests, or the demo is retired, the next submission is rejected and the account holder
  cannot fix it. A dead demo URL in review notes is worse than none.

### B — a review account on a server the account holder runs (**recommended**)

The account holder already runs a Navidrome; that is the premise of the app. The route is a
**non-admin, throwaway account on it, granted only libraries that hold freely-distributable
content**, reachable over HTTPS.

**For:**

- Under the account holder's control for the life of the listing — the only property that keeps App
  access true across re-reviews.
- Both libraries, so the reviewer can reach the features the listing claims.
- The credential is throwaway, scoped by grant, and revocable the moment review ends. It never
  touches the personal library: an ungranted library is invisible to that account, which is exactly
  what the table above measures.
- Nothing in the app changes, no gate is weakened, and no third party is involved.

**Against:**

- It is a standing obligation, not a one-off: Play re-reviews on every update, so the host and the
  account have to keep working. If it lapses, an update is rejected.
- Needs HTTPS with a valid certificate (constraint 1 above) — a reverse proxy with a real
  certificate, or a tunnel. Not hard, but not nothing.
- Needs content that is legal to serve to a stranger. Public-domain and CC-licensed music, and
  LibriVox recordings for the audiobook library, are the obvious answer.

### C — something in the app itself

Two shapes, both rejected:

- **A hidden demo mode** (a magic URL, a tap pattern, a build flag left reachable). This is a debug
  entry point in a shipped binary. `:app:releaseCheck` exists to fail exactly that, and it would
  have to be defeated rather than satisfied. It is also independently a Play policy problem: undisclosed
  functionality in a shipped app. Not proposed, and not proposed with an exemption either.
- **A visible "try a demo server" button** that prefills someone's URL and credentials. This one
  would survive `releaseCheck` honestly — it is not hidden, it is not debug-only, it introduces no
  type from `src/debug` — so it is worth saying why it is still wrong: it hardcodes a third party's
  host into every shipped copy, so option A's "no control" objection stops applying only at review
  time and starts applying to every user forever. If the account holder ever wants this, it should
  point at **their** server, not Navidrome's, and it is a product decision, not a release-engineering
  one.

---

## Recommendation

**Take option B. Keep option A as the documented fallback, and check it works before each
submission.**

B because it is the only route that stays true and that lets the reviewer reach what the listing
claims. A as a fallback because it demonstrably works today and costs nothing to fall back to — if
the review server is down on submission day, an App-access note pointing at the public demo is far
better than a login box.

What tips it is not convenience but who owns the failure. Option A's failure mode is a rejection the
account holder cannot fix; option B's is a rejection they can fix in ten minutes.

---

## The recipe — standing up the review account (option B)

Run against your own Navidrome, as an admin. Every command below was executed verbatim against
`deluan/navidrome:0.63.2` (see the measured table above); only the base URL differs.

    BASE=https://music.example.com

    # 1. Log in to the native API (this is not the Subsonic API; it needs a JWT).
    TOKEN=$(curl -sS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
      -d '{"username":"<your-admin>","password":"<your-admin-password>"}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
    AUTH="X-ND-Authorization: Bearer $TOKEN"

    # 2. Two libraries holding only freely-distributable content, at disjoint paths.
    #    (Library 1 cannot be repointed or deleted -- Navidrome refuses both -- so if library 1 is
    #    your personal music, create BOTH review libraries new and grant neither library 1 nor
    #    anything else.)
    curl -sS -X POST "$BASE/api/library" -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"name":"Review Music","path":"/review/music"}'
    curl -sS -X POST "$BASE/api/library" -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"name":"Review Audiobooks","path":"/review/audiobooks"}'

    # 3. A non-admin account. Non-admin matters: an admin sees every library implicitly and cannot
    #    be given explicit per-library grants at all.
    curl -sS -X POST "$BASE/api/user" -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"userName":"play-review","name":"Play Review","password":"<generate one>","isAdmin":false}'

    # 4. THE STEP THE ROUTE FAILS ON. Grant that user the two review libraries by id.
    curl -sS -X PUT "$BASE/api/user/<user-id>/library" -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"libraryIds":[<review-music-id>,<review-audiobooks-id>]}'

    # 5. Prove it from the outside, as the reviewer will see it. Must list exactly the two
    #    review libraries and nothing else.
    curl -sS "$BASE/rest/getMusicFolders?u=play-review&p=<password>&v=1.16.1&c=check&f=json"

Then scan, and confirm `getAlbumList2` returns albums for each `musicFolderId`.

**Never put the review password in this repository, in the store listing, or in a commit.** It
belongs in Play Console's App access form and nowhere else, and it should be rotated after review.

---

## Exact text for Play Console → App access

Select **"All or some functionality is restricted"** and add one instruction set.

*Name:* `Sign in to a self-hosted music server`

*Username:* `play-review`

*Password:* (the generated password — Play Console only)

*Any other instructions:*

<!-- reviewer-taps:start -->
<!--
  Everything in bold between these two markers is a control the reviewer is told to type into
  or tap, and `ConventionTest`'s `every control the reviewer instructions name is a label the app
  renders` holds each one against the shipped Compose sources. Renaming a button without editing
  this block fails the build -- which is the point: these instructions are filed with Google, and
  a rename would falsify them silently, months later, in somebody else's review queue.
-->

> MuPlay is a client for a music server that the user runs themselves (Navidrome or any
> Subsonic-compatible server). It has no accounts and no backend of its own, so a demonstration
> server has been prepared for review.
>
> 1. Launch MuPlay. The first screen is "Connect to your server".
> 2. In **Server URL**, enter exactly: https://<review-host>
>    (the scheme is required; a plain hostname is rejected as invalid)
> 3. In **Username**, enter the username above; in **Password**, the password above.
> 4. Tap **Connect**. The button reads "Connecting…" and then the screen shows
>    "Connected to navidrome <version>".
> 5. The screen then asks "What is each library for?" and lists two libraries,
>    "Review Music" and "Review Audiobooks". Tap **Tag as Music** on the first row and
>    **Tag as Audiobooks** on the second. (This exists because the server protocol cannot say what
>    a library holds; the user decides once.)
> 6. Tap **Continue**. The app opens the library browser, with a chip per library, a
>    "Search this library" box and "Shuffle this library" / "Refresh library" buttons.
> 7. Tap an album's **Open**, then a track, to start playback. Audio plays, a media notification
>    appears, and playback continues with the screen off.
> 8. Tap the "Review Audiobooks" chip to switch libraries; "Shuffle this library" draws only from
>    the selected library.
>
> The app talks to no server other than the one entered here. This demonstration account is
> read-only in effect: MuPlay calls only read endpoints and never writes to the server.

<!-- reviewer-taps:end -->

Substitute the real host. Keep the credential in the form's own fields, not in the free-text box.

---

## If you fall back to the public demo (option A)

Same form, with:

*Username:* `demo`  *Password:* `demo`

and step 2 becomes `https://demo.navidrome.org`; step 5 lists **one** library, `Music Library`, so
tag that single row **Tag as Music** and continue. Step 8 does not apply.

Before submitting with this route, re-check it is alive — the whole objection to option A is that it
can stop being true without warning:

    curl -sS 'https://demo.navidrome.org/rest/ping?u=demo&p=demo&v=1.16.1&c=check&f=json'

Expect `"status":"ok"`.

---

## What the app must do for these instructions to be true

- The literal labels quoted in the instructions must be the labels the app renders. A rename in
  `SetupScreen`/`LibraryScreen` silently falsifies review instructions that are already filed with
  Play. This is exactly the "a list written by hand describing something discoverable from the tree"
  shape this repository has been bitten by three times, so it is held by a test rather than by care.
- The empty-library case must say something. See *The zero-library trap*.

---

## What remains the account holder's

1. Choosing the route (Plan 8's item D). This document recommends B; the decision is theirs.
2. Standing up the review server, its HTTPS certificate, and its freely-distributable content.
3. Creating the account, generating its password, granting its libraries, and **rotating the
   password after review**.
4. Pasting the text above into Play Console, with the real host substituted.
5. Re-checking, before every submission, that the route still works end to end — the account still
   exists, the grants are still there, the host still answers over HTTPS.

Nothing in this repository can do any of these, and no gate here can observe them.
