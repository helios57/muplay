# Google Play store listing — MuPlay

Everything a human needs in front of them to fill in Play Console's **Main store listing** page,
plus the inventory of graphics and who or what produces each one.

**Every capability this copy claims is one a user of the shipped app can actually reach.** That is
not a promise, it is checked: `StoreListingTest` (JVM tier, runs on every `./gradlew check`) holds
each claim below against the file that implements it, and separately refuses a description that
names any of the capabilities this build does **not** have — the list under
[Not in this version](#not-in-this-version) is the same list the gate bans from the paragraphs above
it. See that test for what each rule can be made to fail on.

Re-read Play's own current requirements before the first upload. Field limits and asset sizes here
were correct when this file was written and Play changes them without notice; a stale requirement is
worse than an unknown one.

---

## Listing text

### App name (Play limit: 30 characters)

```text
MuPlay: Music and Audiobooks
```

### Short description (Play limit: 80 characters)

```text
Music and audiobooks from the Navidrome or Subsonic server you run yourself.
```

### Full description (Play limit: 4000 characters)

```text
MuPlay plays the music and audiobooks that are already on your own server.

It is a client, not a service. There is no MuPlay catalogue, no MuPlay account and no MuPlay
subscription — it plays what is on the Navidrome server (or any Subsonic / OpenSubsonic compatible
server) that you run. If you do not already run one, MuPlay will have nothing to play. Start at
navidrome.org, then come back.

MUSIC AND AUDIOBOOKS, KEPT APART

Tell MuPlay once which of your server's libraries hold music and which hold audiobooks. After that,
Shuffle means "shuffle this library" — shuffling your music can never pull an audiobook into the
middle of it. Anything the server hands back that is not from the library you asked for is thrown
away before it reaches the queue, and MuPlay says so rather than quietly playing it.

BROWSING THAT DOES NOT WAIT FOR THE NETWORK

Your library's names are mirrored onto the phone, so opening a library, scrolling it and searching
it are immediate rather than a round trip to the server. Browse albums with their cover art, open
one, and start from any track. Refresh pulls in whatever changed on the server, when you ask it to.

MADE FOR LISTENING WITH THE SCREEN OFF

- A media notification and lock-screen controls
- Headset and Bluetooth buttons; playback pauses when your headphones come out
- Quiets for navigation prompts and pauses for calls
- Albums play through without gaps between tracks
- Volume levelling taken from your own files' ReplayGain tags, automatically
- The seek bar works even on formats your server has to re-encode on the fly

IN THE CAR

MuPlay is an Android Auto media app. The car screen offers Continue, Books, Albums and Artists.
Books you have started show how much of them is left and pick up in the part you stopped in. One tap
on the first row of Albums shuffles a whole library. "Hey Google, play <a title> on MuPlay" works
even with the app closed.

WHAT YOU LISTEN TO STAYS YOURS

- No account, no sign-up, no telemetry, no analytics, no advertising, no crash reporting
- The only computer MuPlay talks to is the server whose address you typed in
- It never reports back to your server what you played. The Subsonic protocol has endpoints for
  exactly that, and MuPlay deliberately does not call them
- Where you are in each book is written to your phone and is never uploaded anywhere
- Your server password is sealed with a key held in the Android Keystore, and is excluded from
  device backups
- Free software, MIT licence, whole source public

NOT IN THIS VERSION

MuPlay is young, and this listing claims only what it does today. There is no sleep timer, no
playback speed control, no chapter list, no downloading for offline listening (recently played audio
is cached, which is a different and much weaker thing), no casting to Sonos, DLNA or Chromecast
speakers, and no Wear OS app. Those are planned. They are not in this build, and nothing above
pretends otherwise.

WHAT YOU NEED

Android 8.0 or newer, and a Navidrome or Subsonic-compatible server you can reach from your phone.

Source, issue tracker and privacy policy: github.com/helios57/muplay
```

---

## The rest of the Main store listing page

| Field | Value | Who supplies it |
|---|---|---|
| App or game | App | — |
| Category | Music & Audio | — |
| Tags | Music player, Audiobooks | — |
| Email address | *(an address the developer monitors)* | **account holder** |
| Website | `https://github.com/helios57/muplay` | — |
| Phone / external marketing | leave empty | — |
| Privacy policy URL | a public URL serving `docs/PRIVACY.md` | **account holder** — see below |
| Data safety declaration | the answers in `docs/PLAY-DATA-SAFETY.md` | **account holder** |
| Content rating questionnaire | answer it honestly; MuPlay has no user-generated content, no ads and no in-app purchases | **account holder** |
| Target audience | 13+; MuPlay is not directed at children (`docs/PRIVACY.md`) | **account holder** |
| Ads | contains no ads | — |

`docs/PRIVACY.md` is the policy text; Play needs it at a **URL**, which this repository cannot
provide. The cheapest honest option is GitHub Pages over this repository, so the policy and the code
it describes stay in one history.

---

## Graphics

### Fixed-size assets

Play mandates these two sizes exactly, and rejects the upload rather than the listing when they are
wrong. `StoreListingTest` reads each file's own PNG header and checks it against this table.

| Asset | File | Pixels | Produced by |
|---|---|---|---|
| App icon | `app/src/main/ic_launcher-playstore.png` | 512x512 | `ci/generate-launcher-icon.py` |
| Feature graphic | `play/feature-graphic.png` | 1024x500 | `ci/generate-feature-graphic.py` |

Both are the same mark: `ci/generate-feature-graphic.py` imports the icon script's `GEOMETRY` table
rather than restating it, so editing the icon changes the feature graphic too and there is no second
set of coordinates to forget.

### Phone screenshots

Taken from the real app on the real emulator against a real Navidrome by
**`ci/store-screenshots.sh`**, and re-taken by re-running it. Play accepts 2 to 8; it displays them
in the order they are uploaded, which is why they are numbered.

| File | What it shows |
|---|---|
| `01-connect-to-your-own-server.png` | The first screen: server URL, username, password. The whole premise, stated before anything else. |
| `02-choose-what-each-library-is-for.png` | The connected server's real libraries, each being tagged Music or Audiobooks. |
| `03-browse-your-music.png` | The music library: search, shuffle, refresh, and albums with cover art. |
| `04-browse-your-audiobooks.png` | The same screen switched to the audiobook library — the two are separate places. |
| `05-shuffle-only-this-library.png` | A shuffle that drew only from the music library. |
| `06-now-playing.png` | The player: artwork, title, artist, album, seek bar, transport. |
| `07-what-is-playing-follows-you.png` | The mini player over the library, so what is playing is one tap away from anywhere. |

The names come from `StoreScreenshotsTest`'s own `capture(...)` calls. `StoreListingTest` holds
those, this table and the committed files against each other, so a screenshot added, renamed or
deleted fails `check` until all three agree.

**These screenshots are of the CI fixture library, whose albums are called "Test Album" and "Test
Book".** That is honest and it is unappealing, and a listing full of it reads like an unfinished app.
Before uploading, re-run the script against a real library:

```bash
MUPLAY_SCREENSHOT_PASSWORD='…' ci/store-screenshots.sh \
  --server https://music.example.com --user alice
```

The screenshots show `https://music.example.com` and `alice` on the setup screen no matter which
server the journey actually connects to — see `StoreScreenshotsTest`'s own note on why, and note
that the password field is masked in every frame either way.

### Still to supply

| Asset | Required? | Why this repository cannot produce it |
|---|---|---|
| Android Auto screenshots | **Yes, because this app declares Android Auto.** Play's Auto listing has its own screenshot slot and its own review checklist. | An Auto screenshot needs the Desktop Head Unit or a real head unit, which needs the Android Auto app and Play services — neither is on this emulator. |
| 7-inch and 10-inch tablet screenshots | Optional, but Play marks the listing as not optimised for large screens without them | Needs a tablet AVD; `ci/store-screenshots.sh` would take them unchanged against one, and the output directory would need a second folder. |
| Promo video (YouTube URL) | Optional | Not something a build produces. |
| Wear OS screenshots | Not applicable — no Wear app is declared | See the form-factor table below. |
| Android TV banner and screenshots | Not applicable — no TV support is declared | See the form-factor table below. |

---

## Form factors

Each row's answer is held against the tree by `StoreListingTest`, so declaring a surface in the build
without declaring it here (or the reverse) fails `check`.

| Form factor | Declared? | What decides it |
|---|---|---|
| Phone | Yes | the application module itself |
| Android Auto | Yes | `androidAuto = true` in `app/build.gradle.kts` |
| Wear OS | No | no `:wear` module in `settings.gradle.kts` |
| Android TV | No | no `android.software.leanback` in any source manifest |

Declaring Android Auto is not free: it opens a separate review surface with its own quality
checklist and its own rejection path. Plan 8 Task 10 owns walking that checklist.

---

## Claims, and where each one is implemented

The gate over this table checks that each named file still exists and still contains the named
symbol. It is deliberately a weak check of a strong discipline: it cannot tell you the copy is
*true*, but it does mean a claim outlives the code it rests on for exactly zero commits.

| Claim | File | Must contain |
|---|---|---|
| Connects to a Navidrome / Subsonic server you name | `feature/setup/src/main/kotlin/app/muplay/setup/SetupViewModel.kt` | `class SetupViewModel` |
| Each library is tagged Music or Audiobooks | `core/model/src/main/kotlin/app/muplay/model/LibraryRole.kt` | `AUDIOBOOKS` |
| Shuffle is scoped to one library, and out-of-scope tracks are dropped and reported | `core/database/src/main/kotlin/app/muplay/database/ShuffleRepository.kt` | `class ShuffleRepository` |
| Browsing and searching read a local mirror, not the network | `core/database/src/main/kotlin/app/muplay/database/dao/BrowseDao.kt` | `LIKE` |
| Album art | `feature/library/src/main/kotlin/app/muplay/library/CoverArt.kt` | `CoverArtImage` |
| Refresh pulls server-side changes on demand | `feature/library/src/main/kotlin/app/muplay/library/LibraryScreen.kt` | `Refresh library` |
| Media notification and lock-screen controls | `core/media/src/main/kotlin/app/muplay/media/PlaybackNotification.kt` | `NOTIFICATION_ID` |
| Pauses when headphones are unplugged | `core/media/src/main/kotlin/app/muplay/media/MuPlayerFactory.kt` | `setHandleAudioBecomingNoisy` |
| Ducks for navigation prompts, pauses for calls | `core/media/src/main/kotlin/app/muplay/media/PlaybackAudioAttributes.kt` | `USAGE_MEDIA` |
| Gapless playback | `core/media/src/androidTest/kotlin/app/muplay/media/GaplessTest.kt` | `class GaplessTest` |
| Volume levelling from ReplayGain tags, automatically | `core/media/src/main/kotlin/app/muplay/media/ReplayGainController.kt` | `class ReplayGainController` |
| Seeking works on a re-encoded stream | `core/media/src/main/kotlin/app/muplay/media/TranscodeSeek.kt` | `TranscodeSeek` |
| Android Auto browse tree: Continue, Books, Albums, Artists | `core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt` | `Continue` |
| Books show how much is left and resume in the right part | `core/database/src/main/kotlin/app/muplay/database/AudiobookRepository.kt` | `resumeFileId` |
| One tap shuffles a library in the car | `core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt` | `Shuffle ` |
| "Play … on MuPlay" works with the app closed | `core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt` | `playFromSearch` |
| No analytics, advertising or crash reporting dependency | `gradle/libs.versions.toml` | `[libraries]` |
| Listening history is never sent to the server | `core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt` | `class ProgressWriter` |
| Book positions are written to the phone only | `core/database/src/main/kotlin/app/muplay/database/dao/MediaProgressDao.kt` | `MediaProgressDao` |
| The password is sealed with an Android Keystore key | `core/database/src/main/kotlin/app/muplay/database/KeystoreCipher.kt` | `AndroidKeyStore` |
| Excluded from device backups | `app/src/main/AndroidManifest.xml` | `android:allowBackup="false"` |
| MIT licence | `LICENSE` | `MIT` |
| Android 8.0 or newer | `build-logic/convention/src/main/kotlin/KotlinAndroid.kt` | `minSdk = 26` |

### Not in this version

The full description says so in as many words, and `StoreListingTest` refuses a description that
claims any of them in the paragraphs above that section. Each was measured, not assumed — see the
audit that produced this list.

| Capability | Status in this build |
|---|---|
| Sleep timer | `SleepTimerController` exists and is attached to nothing. `MuPlaybackService` says attaching it is future work. |
| Shake to extend the sleep timer | `ShakeSensor` is never injected and never started. |
| Casting to Sonos, DLNA/UPnP or Chromecast | `core/cast` is complete and unreachable: `CastSessionManager.castTo` is called from one file, and it is a test. There is no device picker, and SSDP discovery is never started. **The README still advertises this; the README is wrong.** |
| Playback speed | Stored, never set on a player, and no control anywhere. |
| Silence skipping | Stored, never enabled. |
| Chapter list / jump to chapter | `ChapterRepository` has no production caller. Android Auto lists a book's *files* ("Part 2 of 5"), not chapters. |
| Exact-second book resume | The right file resumes; the position inside it is always 0, because `MediaModule` binds `NeverResume`. |
| Downloads for offline listening | Only a 512 MiB opportunistic byte cache in `cacheDir`, which the OS may reclaim. |
| Lidarr / Bindery requests | No `:feature:requests` module; `:app` depends on neither integration, so their code is not in the APK. |
| Scrobbling / play counts sent back to the server | Never. `ProgressWriter` writes locally and the Subsonic write endpoints are deliberately not declared. |
| Wear OS app | No module, no declaration. |
| Material You / dynamic colour | Light and dark only, from a fixed palette. |

---

## Only the account holder can do these

Plan 8's own list, plus what this task found on top of it.

1. Register a Play Console developer account and complete identity verification.
2. For a personal account registered after November 2023: run a **closed test with 12 testers for 14
   continuous days** before production access is granted. This is a calendar dependency; nothing in
   this repository shortens it.
3. Accept the Play App Signing terms.
4. **Host `docs/PRIVACY.md` at a public URL** and paste that URL into the listing.
5. Supply a contact email address.
6. Answer the Data safety form from `docs/PLAY-DATA-SAFETY.md` and the content rating questionnaire.
7. **Supply Android Auto screenshots** and walk Play's Auto quality checklist (Plan 8 Task 10).
8. **Decide how a reviewer signs in** (Plan 8 Task 8). This is the listing's real risk: a reviewer
   who opens MuPlay, sees a login box and has no Navidrome to point it at will reject the app as
   non-functional. The short description and the first line of the full description exist to make
   that expectation unmissable *before* install, but they do not substitute for giving the reviewer
   working credentials in App access.
