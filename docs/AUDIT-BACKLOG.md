# Audit backlog — 2026-09-02

Findings from three completed audits (UI after two design rounds, theming/dark mode,
failure and empty states). Eight further audits — accessibility, Compose performance,
vacuous assertions, coverage, dead code, docs coherence, remaining security surface,
audiobook domain — were cut off by a session rate limit and produced nothing. They are
listed at the end as **not done**, so nobody mistakes their absence for a clean result.

Everything below was read out of the source. Nothing here has been fixed, and nothing
here has been verified on a device.

---

## P0 — defects a user meets

### 1. Opening a book while the server is down crashes the app
`feature/book/.../BookViewModel.kt:147`, `BookPlayerViewModel.kt:202` call `timeline()`
inside `viewModelScope.launch` with **no catch**. `ChapterReader.kt:73` throws
`ExecutionException`/`TimeoutException`. Its own KDoc (`:101-105`) claims this "surfaces
as chapters unavailable" — **there is no such state in `BookUiState`**, so the claim is
false and the app dies.

Worse, it repeats: `ChapterRepository.kt:54` stores the scan row only on success, so a
40-file book that fails at file 17 re-crashes on every reopen until the server returns.

Fix: `BookUiState.Content.chapters: ChapterLoad` (`Loading | Ready(list) | Failed`), and
replace the bare `Text` at `BookScreen.kt:246` with a `Message(..., onRetry = …)`.

### 2. Credentials can never be changed, and one failure strands the user permanently
`SetupRoute` is reachable **only** as the start destination (`MuPlayApp.kt:62,180`), and
`CredentialStore.clear()` (`CredentialStore.kt:63`) has **no caller anywhere**. So a user
whose server password changed is stuck on a stale mirror forever.

Sharper: `SetupViewModel.kt:86-87` saves credentials *before* `refreshFromServer()`. If
that throws, the next launch goes to Library → `NoLibraries` → "Finish setup…"
(`LibraryScreen.kt:446-448`) — with **no route back to setup**. First run fails, app is
bricked.

Fix: a "Server" `SettingsSection` that pushes `SetupRoute`; save credentials only after a
successful refresh, or make the empty state route to setup.

### 3. A shuffle makes the library screen unreachable
`feature/library/.../LibraryScreen.kt:220-248`: `uiState.shuffled.forEachIndexed` renders
up to `DEFAULT_SHUFFLE_SIZE = 100` rows of 48dp into a **`Column` with no
`verticalScroll`**, above the album `LazyColumn`. One tap on "Shuffle this library" pushes
~92 rows and every album off the bottom with no way to scroll back.

The four-track fixture corpus is why no test and no screenshot has ever seen it.

Fix: one `LazyColumn` for the whole screen, with chips/search/shuffle/Books as `item`s.
**Cost: needs test changes** — `PlaybackJourneyTest.shuffledRows` walks composed nodes and
would need `performScrollTo`. No label moves.

### 4. Mid-track failure is invisible, and Play then does nothing
There is **no `onPlayerError` anywhere** in `core/media/src/main`; `PlaybackState.kt:23-56`
has no error field. When the server vanishes, ExoPlayer exhausts its retry policy, goes
`STATE_IDLE`, and the screen shows a *paused* track. `PlayerViewModel.playPause`
(`:111-115`) then calls `play()` on an idle errored player, which needs `prepare()` — so
the button does nothing, forever.

An expired token is worse: `/rest/stream` answers `200` with JSON, and `ParserException`
fails immediately with no retry at all.

### 5. A plain-HTTP LAN address reads as "check your connection"
Navidrome's default is `http://host:4533`, and release builds forbid cleartext. The
`UnknownServiceException` is caught as a generic `Exception` (`SetupViewModel.kt:73-97`)
and rendered as `Unreachable` (`SetupFailureReason.kt:50`). Nothing tells the user the
**scheme** was the problem. This is the most likely first-run failure for a self-hoster.

Fix: check the scheme before `ping()` and add `SetupFailureReason.CleartextForbidden(host)`
naming HTTPS, a reverse proxy, or Tailscale.

### 6. "Nothing here yet." is four different states
`LibraryScreen.kt:543-544` renders the same bare `Text` for: a search with no match; a
first sync still running; a sync that failed against an empty mirror; and a genuinely
empty library. **This is the typo'd-URL-looks-like-a-broken-app case.** `syncMessage` even
says "Showing your last synced library" when there is none.

### 7. Every post-setup failure collapses to one sentence
`LibraryViewModel.kt:181` flattens `SyncState.Failed(cause)`: a changed password (code 40),
Navidrome down behind a proxy (502), an expired certificate, `EmptyLibraryListException`
and `NotConfiguredException` all render identically — and `NotConfiguredException`'s KDoc
(`:6-9`) explicitly says the UI must distinguish it.

### 8. Shuffle failure is silent
`LibraryViewModel.kt:154-156` swallows any exception into an empty result and
`LibraryScreen.kt:525` renders nothing for empty. Server asleep → tap Shuffle → nothing
happens, with no explanation.

---

## P1 — the design system does not hold

- **The cold-start window is the Compose template.** `app/src/main/res/values/colors.xml:14-15`
  and `values-night/colors.xml:6` use `#FFFBFE`/`#1C1B1F` — baseline Material purple-tinted
  surfaces — while MuPlay's are `#FBF9F5`/`#0E1413`. The splash field `#4F378B` is baseline
  M3 primary purple. Every dark launch steps from L≈11 to L=6, in the template's brand.
- **Dark `surfaceVariant` is mis-toned and collides with `outlineVariant`** — both `#3F4946`
  (`Color.kt:124,127`), brighter than `surfaceContainerHighest`. It is the artwork
  placeholder and the progress track, so in dark **a missing cover is the brightest thing on
  screen**, and a hairline and a fill are the same colour.
- **`outlineVariant` on dark surface is ~2.0:1** — the `Slider` inactive track and
  `ProgressRule` at 3dp are near-invisible. (A ~1.1:1 track was already fixed on the player;
  this is its sibling.)
- **The audiobook half leaks teal through Material defaults.** Unstyled `OutlinedButton`,
  `Switch`, every `TextButton`, and `Message`'s spinner all draw `primary`. So the book
  screens are amber where hand-coloured and music-blue everywhere else — 24 explicit
  `tertiary` sites fighting the defaults. Proposed: a `BookVoice {}` wrapper that maps
  `primary → tertiary` for the three book screens, then delete the overrides.
- **`MiniPlayer` speaks the music voice under a book** (`MiniPlayer.kt:208-227`) — it ignores
  `isAudiobook`, which `PlaybackState` already carries. A teal hairline under an amber shelf
  is the one place "what is playing follows you" is visibly wrong.
- **The library chip contradicts what setup just taught**: setup tints "Tag as Audiobooks"
  `tertiaryContainer`, then the browse screen renders the selected Audiobooks library chip in
  default `secondaryContainer`. **Do not reword or hide Shuffle for audiobook libraries** —
  `ScopedShuffleJourneyTest:100-105` finds it by exact text.
- **`Type.kt:54` names `MuPlaySectionHeader`, which does not exist.** There are three private
  section headers, two without the hairline rule the KDoc promises. Both audits found this
  independently.
- **`displayLarge`, `displayMedium`, `headlineLarge` have zero call sites.**
- **`Message` does not own its centring** — four callers wrap it, three do not, so states are
  centred in one half of the app and top-aligned in the other. **`Message.onRetry` has zero
  callers anywhere**, while three screens hand-roll their own retry.
- **`:feature:castpicker` and `:feature:requests` use no `MuPlaySpacing` at all** and received
  neither design round; several rows there are ~32dp tap targets.
- Dead elevation: `MiniPlayer.kt:151-152` passes both `color = surfaceContainer` and
  `tonalElevation = 3.dp`; M3 tints only when the colour is `surface`, so it does nothing.

---

## P2 — assets and consistency

- **The seven store screenshots are one design round stale.** Regenerated at `976b0bc`
  (2026-08-31), *between* the two rounds: they still show `Open` buttons under rows, a
  three-across button row, a teal artist line and the cast icon pushing play off-axis — none
  of which exists in the code now. `StoreListingTest` checks names, not pixels, so nothing
  goes red. Regenerate with `ci/store-screenshots.sh` before any submission.
- Two players use two chassis; album and book detail are two layouts for one job;
  `CoverArt.kt:38,60` hardcodes `RoundedCornerShape(4.dp)` while book covers use the shape
  scale; progress bars are 5dp and 6dp; `BookScreen.kt:176-209` puts two pills of different
  heights side by side, with a restart action co-equal to the primary one.

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
