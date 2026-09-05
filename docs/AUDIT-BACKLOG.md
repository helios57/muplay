# Audit backlog — opened 2026-09-02, status 2026-09-05

Findings from three completed audits (UI after two design rounds, theming/dark mode,
failure and empty states). Eight further audits were cut off by a session rate limit and
produced nothing; they are listed at the end as **not done**, so nobody mistakes their
absence for a clean result.

**Status, re-checked against the tree on 2026-09-05 rather than remembered: all eight P0
items are fixed, and all eleven of P1.** What each was and what closed it is below, because a
backlog that deletes its own history stops being reviewable -- but read the marker before
the prose. Every paragraph under a ✅ heading describes code that no longer exists; it is kept so the
fix can be reviewed against what it was for.

The three lines that would otherwise mislead a reader of the 09-02 version:

- Every P0 fix has tests, and every one of those tests was watched to fail first.
- The P1 design-system section was written before two rounds of fixes; all eleven of its
  bullets are now closed, three of them *as deliberate refusals* carrying the measurement.
  One of the three -- the dark `surfaceVariant` collision -- was refused for the reason it
  named and fixed one layer along, where the same colour was a real contrast defect.
- **Nothing here has been re-audited.** These are the findings of three audits from one
  afternoon, fixed. They are not a clean bill of health, and the eight audits at the
  bottom are still the honest summary of what nobody has looked at.

---

## P0 — defects a user meets

### 1. ✅ FIXED — Opening a book while the server is down crashes the app

*`BookUiState.Chapters` is now `Reading | Ready | Unavailable`, and the screen offers a
retry. `BookContentTest` drives all three on a device.*

`feature/book/.../BookViewModel.kt:147`, `BookPlayerViewModel.kt:202` call `timeline()`
inside `viewModelScope.launch` with **no catch**. `ChapterReader.kt:73` throws
`ExecutionException`/`TimeoutException`. Its own KDoc (`:101-105`) claims this "surfaces
as chapters unavailable" — **there is no such state in `BookUiState`**, so the claim is
false and the app dies.

Worse, it repeats: `ChapterRepository.kt:54` stores the scan row only on success, so a
40-file book that fails at file 17 re-crashes on every reopen until the server returns.

Fix: `BookUiState.Content.chapters: ChapterLoad` (`Loading | Ready(list) | Failed`), and
replace the bare `Text` at `BookScreen.kt:246` with a `Message(..., onRetry = …)`.

### 2. ✅ FIXED — Credentials can never be changed, and one failure strands the user

*`ServerSection` pushes `SetupRoute` from settings and calls `CredentialStore.clear()`.
`:app`'s `ServerChangeJourneyTest` walks it end to end against the real container, and
`:feature:setup`'s `ServerSectionTest` (added 2026-09-05) holds the section itself.*

`SetupRoute` is reachable **only** as the start destination (`MuPlayApp.kt:62,180`), and
`CredentialStore.clear()` (`CredentialStore.kt:63`) has **no caller anywhere**. So a user
whose server password changed is stuck on a stale mirror forever.

Sharper: `SetupViewModel.kt:86-87` saves credentials *before* `refreshFromServer()`. If
that throws, the next launch goes to Library → `NoLibraries` → "Finish setup…"
(`LibraryScreen.kt:446-448`) — with **no route back to setup**. First run fails, app is
bricked.

Fix: a "Server" `SettingsSection` that pushes `SetupRoute`; save credentials only after a
successful refresh, or make the empty state route to setup.

### 3. ✅ FIXED — A shuffle makes the library screen unreachable

*The library screen is one `LazyColumn`; nothing renders rows into an unscrollable
`Column` any more.*

`feature/library/.../LibraryScreen.kt:220-248`: `uiState.shuffled.forEachIndexed` renders
up to `DEFAULT_SHUFFLE_SIZE = 100` rows of 48dp into a **`Column` with no
`verticalScroll`**, above the album `LazyColumn`. One tap on "Shuffle this library" pushes
~92 rows and every album off the bottom with no way to scroll back.

The four-track fixture corpus is why no test and no screenshot has ever seen it.

Fix: one `LazyColumn` for the whole screen, with chips/search/shuffle/Books as `item`s.
**Cost: needs test changes** — `PlaybackJourneyTest.shuffledRows` walks composed nodes and
would need `performScrollTo`. No label moves.

### 4. ✅ FIXED — Mid-track failure is invisible, and Play then does nothing

*`PlaybackFailure` exists, `core/media` listens for `onPlayerError`, and the mapping is a
function over an `Int?` error code so the fast tier can gate it -- see CLAUDE.md on why a
Media3 `PlaybackException` cannot be constructed on the JVM.*

There is **no `onPlayerError` anywhere** in `core/media/src/main`; `PlaybackState.kt:23-56`
has no error field. When the server vanishes, ExoPlayer exhausts its retry policy, goes
`STATE_IDLE`, and the screen shows a *paused* track. `PlayerViewModel.playPause`
(`:111-115`) then calls `play()` on an idle errored player, which needs `prepare()` — so
the button does nothing, forever.

An expired token is worse: `/rest/stream` answers `200` with JSON, and `ParserException`
fails immediately with no retry at all.

### 5. ✅ FIXED — A plain-HTTP LAN address reads as "check your connection"

*`SetupFailureReason.CleartextForbidden` names the scheme and the host. Note what was
measured while fixing it: a release build **can** do cleartext to `localhost`, and only
there (CLAUDE.md).*

Navidrome's default is `http://host:4533`, and release builds forbid cleartext. The
`UnknownServiceException` is caught as a generic `Exception` (`SetupViewModel.kt:73-97`)
and rendered as `Unreachable` (`SetupFailureReason.kt:50`). Nothing tells the user the
**scheme** was the problem. This is the most likely first-run failure for a self-hoster.

Fix: check the scheme before `ping()` and add `SetupFailureReason.CleartextForbidden(host)`
naming HTTPS, a reverse proxy, or Tailscale.

### 6. ✅ FIXED — "Nothing here yet." is four different states

*`LibraryEmptyReason` and `LibraryNotice` split them; `LibraryNoticeTest` asserts the
other three never say that sentence.*

`LibraryScreen.kt:543-544` renders the same bare `Text` for: a search with no match; a
first sync still running; a sync that failed against an empty mirror; and a genuinely
empty library. **This is the typo'd-URL-looks-like-a-broken-app case.** `syncMessage` even
says "Showing your last synced library" when there is none.

### 7. ✅ FIXED — Every post-setup failure collapses to one sentence

*`SyncFailure` types them, with its own test.*

`LibraryViewModel.kt:181` flattens `SyncState.Failed(cause)`: a changed password (code 40),
Navidrome down behind a proxy (502), an expired certificate, `EmptyLibraryListException`
and `NotConfiguredException` all render identically — and `NotConfiguredException`'s KDoc
(`:6-9`) explicitly says the UI must distinguish it.

### 8. ✅ FIXED — Shuffle failure is silent

*`LibraryViewModel.shuffle` now `runCatching`s into a notice; its KDoc names the defect it
replaced.*

`LibraryViewModel.kt:154-156` swallows any exception into an empty result and
`LibraryScreen.kt:525` renders nothing for empty. Server asleep → tap Shuffle → nothing
happens, with no explanation.

---

## P1 — the design system does not hold

**All eleven are now closed.** Three were still open on 2026-09-05 and each is below with what
settled it; two of the three turned out to be about something other than what the audit named.

### Closed 2026-09-05

- ✅ **Dark `surfaceVariant` colliding with `outlineVariant` is Material's own scheme, and the
  harm the audit named had already been fixed elsewhere.** Measured in material3 1.4.0's
  `ColorDarkTokens`: `SurfaceVariant` and `OutlineVariant` are both assigned
  `PaletteTokens.NeutralVariant30` — one token, not two values that drifted together — and
  `SurfaceContainerHighest` is `Neutral22`, darker. So "brighter than `surfaceContainerHighest`"
  is the baseline's own relationship. The stated consequence, *"it is the artwork placeholder, so
  in dark a missing cover is the brightest thing on screen"*, stopped being true at `4d20aff`:
  `CoverArt.kt` draws its placeholder in `surfaceContainerHighest`.

  What the audit was pointing at turned out to be real one layer along, and worth the trip: the
  three **book** screens still drew their progress track in `surfaceVariant`, which in light is
  **1.22:1** against the surface. `PlayerScreen`'s seek bar had already measured that and moved to
  `outlineVariant` (**1.61:1**) — and moved nothing else. The three are now `outlineVariant` too,
  held by a source scan in `BookVoiceApplicationTest` (falsified: revert one and it names the
  file). `Color.kt` carries the measurement at the value itself.
- ✅ **The library chip now keeps the colour setup taught for it.** `LibraryChips` tints its
  *selected* container from `library.role`: `tertiaryContainer` for an audiobook library,
  `primaryContainer` for music — the same pairing `SetupScreen`'s "Tag as Music" / "Tag as
  Audiobooks" chips draw, and the same voice as the `Books` card three rows below. Unselected
  chips stay outlines in both voices; tinting those would turn a row of libraries into a colour
  key nobody asked for. Held by `LibraryChipVoiceTest`, a source scan for the reason
  `BookVoiceApplicationTest` is one — there is no Compose matcher for "what colour is that chip".
  Shuffle was not touched.
- ✅ **`displayLarge`, `displayMedium` and `headlineLarge` stay, and `Type.kt` now says why.**
  "Use them or delete them" has a third answer, and the arithmetic decides it: deleting a slot
  from a `Typography` does not remove a number, it substitutes Material's. Measured in
  `TypeScaleTokens` — `DisplayLargeWeight` is `WeightRegular` and `DisplayLargeTracking` is
  `+0.2`, against this scale's SemiBold and negative tracking — so the choice is three numbers
  that continue this ramp against three that argue with it, in the part of the scale where the
  file's two stated rules bite hardest. `displaySmall` *is* drawn (the book player's remaining
  time), and the two steps above it are what make its 34sp read as a series rather than as a
  number somebody liked.

### Closed as a deliberate refusal, with the measurement

- **`outlineVariant` on dark surface is ~2.0:1**, and it is staying that way. `Color.kt`
  carries the working: a track has two contrast obligations that pull against each other,
  exactly one value on the neutral ramp clears 3:1 in dark, and **no** value clears it in
  light -- brute-forced over all 256 greys, the best achievable worst case is 3.18:1 and only
  at `#000000`. The real fix is a track colour role of its own, outside `ColorScheme`.
  Worth doing; not worth doing halfway. **Do not "fix" this by nudging one theme.**

### Closed

- ✅ The cold-start window is no longer the Compose template: `#FBF9F5`/`#0E1413` and a
  `#005048` splash.
- ✅ `BookVoice` maps `primary → tertiary` for the three book screens, with tests in both
  `:core:designsystem` and `:feature:book`; the hand-rolled `tertiary` overrides are gone.
- ✅ `MiniPlayer` reads `isAudiobook`, so the bar follows what is playing.
- ✅ `Type.kt` no longer names a `MuPlaySectionHeader` that never existed; the comment now
  says what actually draws the eyebrow.
- ✅ `Message` owns its centring (`fillMaxWidth` + `CenterHorizontally`) and `onRetry` has
  callers.
- ✅ `:feature:castpicker` and `:feature:requests` use `MuPlaySpacing` throughout, and their
  ~32dp rows are fixed and swept -- see `TapTargets.kt` in five modules and CLAUDE.md's
  "A Compose tap-target assertion is wrong in both obvious directions".
- ✅ `MiniPlayer`'s dead `tonalElevation` is gone, with a comment recording why it did
  nothing.

---

## P2 — assets and consistency

- ✅ **The seven store screenshots are current**, regenerated 2026-09-05 against master
  and reviewed one frame at a time. The entry that stood here called them a release
  blocker, "one design round stale", and named four markers of it. **Three of the four
  were already absent from the pixels** when somebody finally looked: the button row is
  two-across, not three; the artist line is grey, not teal; the play control is
  dead-centre with the cast icon at the edge, not pushed off-axis. The fourth was never
  a marker at all — `OPEN_LABEL` is still in `LibraryScreen.kt:469` and that button is
  meant to be there. The record had simply been carried forward past `2cb4dd3`, which
  regenerated them on 2026-09-04 and said so in its own message.

  They *were* one round behind, for a different and real reason nobody had written
  down: `5bbbfe5` (`Theme.kt` plus the three book screens) and `4d20aff`
  (`PlayerScreen.kt`, `CoverArt.kt`) both landed after that capture. What the
  regeneration then measured is the useful part — **four of the seven came back
  byte-identical**, and the other three differ only in shuffle order and which track was
  current. The design changes are not visible in these seven frames: `BookVoice` moved
  the three *book* screens, and `04-browse-your-audiobooks` is the library's Audiobooks
  tab, which is not one of them.

  The lesson is the one this repository keeps paying for: **the markers in a staleness
  record go stale faster than the thing they describe.** Check the pixels, not the note.
- **OPEN:** two players use two chassis; album and book detail are two layouts for one job;
  `BookScreen.kt:176-209` puts two pills of different heights side by side, with a restart
  action co-equal to the primary one.
- ✅ `CoverArt.kt` no longer hardcodes a corner radius; it uses the shape scale.
- **Investigated, no change:** "progress bars are 5dp and 6dp" is two different components
  with documented reasons -- `ProgressRule` is a 3dp hand-drawn rule with no semantics node,
  `BookPlayerScreen` uses a 6dp `LinearProgressIndicator`.
- **OPEN, found by the absence-assertion sweep 2026-09-05:** `:feature:library` has **no
  `src/androidTest` at all**, so nothing anywhere renders `AlbumScreen`. Its three states --
  `Loading`, `NotFound`, `Content` -- are reachable only through an `:app` journey that drives
  the healthy one, and `AlbumUiState.Loading/NotFound` is a thing the "genuinely good" section
  below singles out for praise. `AlbumViewModelTest` covers the state machine; the `when` that
  turns a state into a screen is covered by nothing.

  The sweep's fix (sharing `NOT_FOUND_LABEL` so the journey asserts the absence of whatever the
  screen really says) removes the way that assertion could silently stop meaning anything. It
  does not make the message *renderable* by any test, and no gate here can tell those apart.

  What it costs to close: the same shape `:feature:book` already has -- split a stateless
  `AlbumContent(uiState, ...)` out of the `hiltViewModel()` wrapper, add the six androidTest
  dependencies `feature/book/build.gradle.kts` documents, and add `:feature:library` to
  `e2e.yml`'s module list (`ConventionTest` will demand that second part on its own). Belongs
  with the ungated-class work rather than here.

- **Partly fixed 2026-09-05 — a teardown that throws hides the failure that caused it.**
  `CLAUDE.md` records this against `GaplessTest`, and a full `:app` device run on 2026-09-05 hit it
  again for real: the report's only message was
  `UninitializedPropertyAccessException: lateinit property browser has not been initialized at
  CarResumeJourneyTest.tearDown`, and whatever actually failed in `setUp` was unrecoverable from
  the run.

  A scan of every `@After` in the tree found **23** that dereference a `lateinit` with no
  `::x.isInitialized` guard. Three are now guarded — the three where `@Before` does something
  fallible *before* the assignment, which is what makes the guard more than decoration:

  | class | what runs first |
  | --- | --- |
  | `CarResumeJourneyTest` | `reachLibraryScreen()` — the whole setup flow against a real server |
  | `TranscodeSeekSessionTest` | `getRandomSongs(...).single { it.suffix == "opus" }` over HTTP |
  | `SleepTimerFadeAudioTest` | `audiobookFiles()` over HTTP, then `first { it.title == ... }` |

  The last two are the corpus-change shape exactly: `single`/`first` throw when the fixture set
  moves under them, which is how `GaplessTest`'s hardcoded `3` broke.

  **OPEN: the other 20** — seven in `:core:database`, twelve in `:core:media`, one in
  `:integrations:core` — assign their `lateinit`s from local construction (an in-memory Room
  database, a temp `File`, an `AudioManager`) with nothing fallible ahead of them, so the guard
  would be code no failure has ever reached. Deliberately not written, and deliberately not made a
  `ConventionTest` rule: a rule needing twenty carve-outs is a rule somebody switches off. If one
  of those `@Before`s ever grows a network call, guard it then.

---

## What the audits say is genuinely good — do not disturb

The **cast** error handling is the model the rest should copy: `CastUiState.kt:237-257`
types `Lost` vs `Failed`, names the device, hands playback back paused, and "Try again"
really re-searches. Also good: `AlbumUiState.Loading/NotFound`, `BookshelfUiState`,
`Tagging.prompt`, `ConnectionCheck`, and the book player's own layout — 68dp play pinned
low, 56dp nudges, the whole bar tappable — which is the one screen that is genuinely
one-handed.

---

## NOT DONE — eight audits produced nothing

Cut off by a session rate limit before reporting. Their absence is not a clean bill:

accessibility (TalkBack, roles, 48dp, contrast) · Compose performance (recomposition
scope, lazy keys, Flow-in-composition) · vacuous assertions · coverage (92 ungated
classes) · dead code and reachability · docs coherence · remaining security surface
(including possible SSRF in the new `muplay-art:` scheme) · audiobook domain.

The security and vacuous-assertion sweeps are the two worth running first: both target
defect classes this repository has already shipped more than once.

**One of the eight has been partly overtaken, and only partly.** The 48dp half of the
accessibility audit was done on 2026-09-05: every clickable node on eight screens across
five modules is now swept for touch-target size and crowding, two real ~32dp defects were
fixed, and what the sweep can and cannot catch is measured rather than argued (CLAUDE.md,
"A Compose tap-target assertion is wrong in both obvious directions"). **TalkBack ordering,
semantic roles, state descriptions and contrast were not looked at.** Do not read the
sweeps as an accessibility pass; they are one rule, falsified, on one axis.
