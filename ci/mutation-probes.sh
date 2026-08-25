#!/usr/bin/env bash
#
# A REGRESSION LIST OF MUTATION PROBES. NOT A RULE, AND NOT A GATE.
# ============================================================================================
#
# Every probe in the PROBES table below is a defect that was found by hand -- by an implementer's audit or by an
# independent review -- and then fixed. Each one names the single test that must now fail when the
# defect is reintroduced. Running this script re-applies each mutation to a committed tree, one at
# a time, and checks that the named test really does go red.
#
# WHAT PASSING THIS SCRIPT MEANS: the specific defects listed in PROBES below are still caught.
# (The count is deliberately not written out here. It has gone stale three times in this one file,
#  once in the very commit that existed to correct it; the script prints the real number, derived
#  from the table, every time it runs.)
#
# WHAT PASSING THIS SCRIPT DOES NOT MEAN -- read this before trusting a green run:
#
#   * It is NOT evidence that the "two disjoint observations per value" rule holds. It cannot
#     catch a parameter nobody wrote a probe for, and that is precisely what every finding in this
#     file's history was. F-4 (`offset` hardcoded to "0") and N-1 (all four `libraryId` stamps
#     hardcoded) both passed a full, green `./gradlew check` at 100% branch coverage before anyone
#     thought to probe them. A probe list is a memory of past mistakes, not a detector of new ones.
#   * It is NOT wired into `ConventionTest`, `check`, or any CI job, deliberately. A source scanner
#     for the same rule was built and measured during review: run against 45da49b it reported
#     exactly the nine wire defects with no false negatives, but at 9f747f3 it reported two false
#     positives (a second observation held in a `const val` rather than a string literal is
#     invisible to it), and it is structurally blind to both N-1 and N-2 -- stamps are not
#     `queryParameter` assertions, and neither is a URL host. A check that goes green while
#     `getRandomSongs` stamps every song with library 1 is worse than no check, because a reviewer
#     who trusted it would stop mutating by hand. This project has paid for that lesson enough
#     times; an honest "these are the probes we know about" is the most this can claim.
#   * It probes only what someone thought to probe. Four review rounds each found a *class* of
#     value nobody had asked about -- request parameters, then the library stamp, then ScanStatus,
#     then every other mapped DTO field. Adding a probe records the answer; it does not generate
#     the question.
#   * Its ordinary, visible cost is drift: it needs a line added whenever someone finds a new
#     probe, and nobody is forced to. That cost was accepted knowingly, over silent false
#     assurance.
#
# ADD A PROBE whenever a review or an audit finds a value that one constant could satisfy. That is
# the only way this file stays worth running.
#
# Exits non-zero if any probe is not caught -- verified, not assumed: the first run of the full
# list exited 1 on a stale `auth/empty-authParams` count, and an independent review re-proved it by
# weakening a stamp test in a throwaway worktree.
#
# SCOPE. Production-code mutations only. The falsifiability
# probes for `LiveNavidromeTest`'s six scoping assertions are *test-side* (they change which
# musicFolderId the test sends, to prove the assertion discriminates rather than that the client is
# right), so they do not belong here; they are recorded in task-3-report.md instead. This script
# runs the JVM suites only and needs no Navidrome container.
#
# AND A HARDER LIMIT THAN "PRODUCTION CODE ONLY", MEASURED IN PLAN 3 TASK 7B: this runner cannot see
# a mutation to any file the JVM tier does not declare as a task *input*, however loudly a test
# reading that file at runtime would fail. `run_suite()` deletes the result directories to force a
# re-run, but Gradle then restores `:core:media:testDebugUnitTest` FROM-CACHE, because an
# androidTest source is not an input to it and the cache key therefore did not move.
#
# Measured, not deduced. Task 7b wrote a probe that hand-builds an `ExoPlayer` inside `GaplessTest`
# -- exactly the defect `PlayerConstructionTest`'s scan of `core/media/src` exists to refuse -- and
# this runner reported MISSED with **zero** failures in the whole suite. The same mutation, applied
# by hand and run as `./gradlew --no-build-cache :core:media:test`, fails as designed:
# "PlayerConstructionTest > an ExoPlayer is constructed in exactly one place() FAILED". The probe
# was therefore removed rather than left MISSED, and the falsification is recorded by hand in
# task-7b-report.md.
#
# So: a probe on a repo-walking scanner's *subject* belongs here only if that subject is inside a
# source set the invocation below already compiles. Adding `--no-build-cache` to fix one such probe
# would pay a full recompilation on every one of the probes in this list, which is not a trade this
# script makes.
#
# THE SAME MECHANISM MAKES THE MISSING-RESULTS GUARD FIRE ON A FALSE ALARM, and that is worth
# expecting rather than debugging. The guard is right and should stay; the trigger is broader than
# androidTest. A *filtered* run mutates one module, so every OTHER module's test task has an
# unchanged cache key -- `run_suite()` deletes its result directory, Gradle restores the task
# FROM-CACHE without repopulating that directory, and the guard correctly refuses to count the
# empty directory as a pass. Measured: `./ci/mutation-probes.sh player/` reported
# `no test results were written for ['core/cast']` while `./gradlew :core:cast:test` was green with
# 16 result files a minute later.
#
# So on a filtered run, read that message as "this module was not exercised", not as "this module
# is broken". Confirm with the module's own test task before believing anything is wrong. On a
# full, unfiltered run the message means what it says.
#
# THE INSTRUMENTED TIER IS OUT OF REACH HERE, AND THAT IS A REAL LIMIT, NOT A DESIGN CHOICE THIS
# SCRIPT MAKES GOOD ON ITS OWN. `run_suite()` below runs `./gradlew :core:network:test
# :core:model:test :core:database:test :feature:setup:test :feature:library:test :core:media:test
# :core:cast:test`
# -- seven plain
# JVM invocations (a third module, `:feature:setup`, joined in Task 8's review round 1:
# `SetupViewModel` is a plain ViewModel with hand-written fakes for its two Android-backed
# collaborators, so its own logic needs no device either; a fourth, `:feature:library`, joined in
# Task 9 for the identical reason -- LibraryViewModel/AlbumViewModel are plain ViewModels with
# hand-written fakes for their own Room/network-backed collaborators) -- and `failures()` globs
# both `core/*/build/test-results/test/` (`:core:network`, `:core:model`) and
# `*/build/test-results/testDebugUnitTest/` (`:core:database`, `:feature:setup`,
# `:feature:library`, `:core:media` -- every Android module's JVM-tier results directory).
# `:core:media` joined in Plan 3 Task 2: `StreamRetryPolicy` (the 429 decision) and `MediaModule`
# (the streaming client's timeouts) are deliberately free of every Android and Media3 type so this
# runner can reach them; the Media3 adapter and the data source around them cannot be probed here
# and are recorded in task-2-report.md instead, the same way `LiveNavidromeTest`'s are.
# `:core:database` genuinely does carry JVM test source (`KeystoreCipherTest`, six tests -- its
# cryptographic contract needs no device) and `run_suite()` now runs it; an earlier version of
# this comment said `:core:database` "has no JVM test source at all", which was false and was
# corrected on a re-review that ran `KeystoreCipherTest` itself and found it green today.
#
# What genuinely cannot run here is the *instrumented* tier: `LibraryRepository`, `LibraryDao` and
# everything else that needs Room's real SQLite need a device, and their results land under
# `build/outputs/androidTest-results/connected/`, a directory tree this JVM-only runner has no
# business reading (it would need `ci/prepare-emulator.sh`'s emulator to produce anything there in
# the first place). A green run of this script therefore still says nothing about whether
# `LibraryRepository.idsWithRole` discriminates its argument -- that class of defect (Task 4's
# N-1, N-2, N-4, N-5 among them) is recorded in each task's own `task-N-report.md` instead, the
# same way `LiveNavidromeTest`'s test-side probes already are above. What changed is that the
# JVM-tier defects this module *can* probe (a `KeystoreCipher` regression, for instance) are no
# longer silently outside this runner's reach alongside the ones that genuinely have to be.
#
# USAGE:  ./ci/mutation-probes.sh            # every probe; budget ~45 s each (one full JVM test
#                                            # run per probe). Measured end-to-end at 13 min and
#                                            # at 8.5 min on two different machines -- so size the
#                                            # expectation per probe, not from a total that is
#                                            # wrong the moment a probe is added or the hardware
#                                            # changes. The script prints the count it will run.
#         ./ci/mutation-probes.sh stamp      # only probes whose id contains "stamp"
#
# The tree must be clean: every probe reverts with `git checkout --`, which cannot tell a probe
# from uncommitted work. Committing before mutating is a standing rule on this project because
# that exact revert destroyed real work twice during Plan 2 Task 3.
#
# EXERCISED FOR REAL, not just written defensively: during this project's round-2 re-review of
# this exact file (task-8-round-1-rereview.md and its follow-up), the re-reviewer's own tooling
# was killed mid-mutation by an external timeout, leaving a stray uncommitted mutation in the
# tree. The re-reviewer disclosed that a flawed `&&`-based "is the tree clean" check of its own
# would have silently proceeded past that stray mutation; the `if [ -n "$(git status
# --porcelain)" ]` check below did not, and caught it before it could contaminate a probe result.
# A guard nobody has seen fire is one people eventually delete -- this one has fired, for a real
# incident, not a hypothetical one.

set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo "REFUSING TO RUN: the working tree is dirty." >&2
  echo "Every probe reverts with 'git checkout --', which would destroy uncommitted work." >&2
  git status --short >&2
  exit 2
fi

# -u: without it Python buffers stdout through a pipe and a four-minute run prints nothing
# until it is over, which makes an interrupted run impossible to interpret.
exec python3 -u - "${1:-}" <<'PY'
import glob, html, pathlib, re, shutil, subprocess, sys

FILTER = sys.argv[1] if len(sys.argv) > 1 else ""

CLIENT = "core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt"
AUTH = "core/network/src/main/kotlin/app/muplay/network/SubsonicAuth.kt"
TYPE = "core/model/src/main/kotlin/app/muplay/model/AlbumListType.kt"
MODEL = "core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt"
MIRROR = "core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt"
SETUP_VM = "feature/setup/src/main/kotlin/app/muplay/setup/SetupViewModel.kt"
SYNC_DECISION = "core/database/src/main/kotlin/app/muplay/database/SyncDecision.kt"
LIBRARY_VM = "feature/library/src/main/kotlin/app/muplay/library/LibraryViewModel.kt"
ALBUM_VM = "feature/library/src/main/kotlin/app/muplay/library/AlbumViewModel.kt"
LIBRARY_STATE = "feature/library/src/main/kotlin/app/muplay/library/LibraryUiState.kt"
STREAM_FORMAT = "core/model/src/main/kotlin/app/muplay/model/StreamFormat.kt"
RETRY_POLICY = "core/media/src/main/kotlin/app/muplay/media/StreamRetryPolicy.kt"
MEDIA_MODULE = "core/media/src/main/kotlin/app/muplay/media/di/MediaModule.kt"
RESUME_POLICY = "core/media/src/main/kotlin/app/muplay/media/ResumePolicy.kt"
PCM_ANALYSIS = "core/testing/src/main/kotlin/app/muplay/testing/PcmAnalysis.kt"
PLAYBACK_QUEUE = "core/media/src/main/kotlin/app/muplay/media/PlaybackQueue.kt"
TRACK_ID_KEY = "core/media/src/main/kotlin/app/muplay/media/TrackIdCacheKeyFactory.kt"
CAST_HEADERS = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt"
CAST_WIRE = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt"
CAST_CLIENT = "core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt"
CAST_NET = "core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt"
CAST_ADDRESS = "core/cast/src/main/kotlin/app/muplay/cast/net/LocalAddress.kt"
PLAYER_STATE = "feature/player/src/main/kotlin/app/muplay/player/PlayerUiState.kt"
PLAYER_VM = "feature/player/src/main/kotlin/app/muplay/player/PlayerViewModel.kt"
PLAYBACK_LAUNCHER = "core/media/src/main/kotlin/app/muplay/media/PlaybackLauncher.kt"
BROWSE_ID = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt"
BROWSE_TREE = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseTree.kt"
BROWSE_TEXT = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseText.kt"
BROWSE_SURFACE = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseSurface.kt"
BASE_URL = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt"
STORE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentialStore.kt"
CREDENTIALS = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentials.kt"
INTEGRATION_SERVICE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationService.kt"
REQUEST_STATUS = "integrations/core/src/main/kotlin/app/muplay/integrations/RequestStatus.kt"
MEDIA_REQUEST = "integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequest.kt"
PLAYBACK_SERVICE = "core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt"
TASK_REMOVAL = "core/media/src/main/kotlin/app/muplay/media/TaskRemovalPolicy.kt"
PLAYBACK_STATE = "core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt"
AUDIO_ATTRIBUTES = "core/media/src/main/kotlin/app/muplay/media/PlaybackAudioAttributes.kt"

# Plan 3 Task 5, review round. The rule that decides which MediaControllers may connect to the
# exported playback session at all.
CONTROLLER_ACCESS = "core/media/src/main/kotlin/app/muplay/media/ControllerAccessPolicy.kt"
DISCOVERY_SSDP = "core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpSearch.kt"
DISCOVERY_TRANSPORT = "core/cast/src/main/kotlin/app/muplay/cast/discovery/SsdpTransport.kt"
DISCOVERY_DESC = "core/cast/src/main/kotlin/app/muplay/cast/discovery/DeviceDescription.kt"
DISCOVERY_DEVICE = "core/cast/src/main/kotlin/app/muplay/cast/discovery/CastDevice.kt"
DISCOVERY_DIR = "core/cast/src/main/kotlin/app/muplay/cast/discovery/RendererDirectory.kt"
DISCOVERY_FETCH = "core/cast/src/main/kotlin/app/muplay/cast/discovery/DescriptionFetcher.kt"
SOAP_XML = "core/cast/src/main/kotlin/app/muplay/cast/soap/XmlText.kt"
SOAP_ENVELOPE = "core/cast/src/main/kotlin/app/muplay/cast/soap/SoapEnvelope.kt"
SOAP_NAMES = "core/cast/src/main/kotlin/app/muplay/cast/soap/SoapNames.kt"
SOAP_CLIENT = "core/cast/src/main/kotlin/app/muplay/cast/soap/SoapClient.kt"
DIDL_SERVED = "core/cast/src/main/kotlin/app/muplay/cast/didl/ServedMedia.kt"
DIDL_LITE = "core/cast/src/main/kotlin/app/muplay/cast/didl/DidlLite.kt"
DIDL_MIME = "core/cast/src/main/kotlin/app/muplay/cast/didl/MimeAgreement.kt"
PROXY_RANGE = "core/cast/src/main/kotlin/app/muplay/cast/proxy/RangeHeader.kt"
PROXY_REGISTRY = "core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyRegistry.kt"
PROXY_UPSTREAM = "core/cast/src/main/kotlin/app/muplay/cast/proxy/ProxyUpstream.kt"
PROXY_SERVER = "core/cast/src/main/kotlin/app/muplay/cast/proxy/MediaProxyServer.kt"
# The one probe below that mutates TEST source, named here rather than quietly reached through
# the `core/cast` entry in `revert()`. See `soap/fake-accepts-everything` for why it is not the
# test-side probe this file's SCOPE note excludes: `FakeRenderer` is the *subject* of
# `FakeRendererStrictnessTest`, not one of its assertions.
SOAP_FAKE = "core/cast/src/test/kotlin/app/muplay/cast/fake/FakeRenderer.kt"

# (id, file, exact text to replace, replacement, test that must fail, total expected failures)
#
# `expected failures` is asserted too, not just "the named test failed": a mutation that reddens
# half the suite is not the precise discrimination these tests claim to provide, and a probe that
# only checked its own test would not notice that.
#
# It also means the counts are MEASUREMENTS, and they go stale when tests are added. The first full
# run of this script reported `auth/empty-authParams` as MISSED at 13 because round 2 had added a
# fourteenth request-asserting test since that number was measured -- the named test failed exactly
# as intended. So: if a probe reports MISSED but its named test *is* in the failing list, the count
# is out of date, not the code. Re-measure and update the number here; do not delete the check.
# Every other probe expects 1, and for those a count above 1 is a real signal worth reading.
PROBES = [
    # ---- F-4, round 1: ten values each observed at exactly one value on the wire -------------
    ("wire/getAlbumList2-offset", CLIENT,
     '"offset" to offset.coerceAtLeast(0).toString(),',
     '"offset" to "0",',
     "getAlbumList2 sends the scope, the type, the page and full authentication", 1),
    ("wire/getAlbumList2-musicFolderId", CLIENT,
     '"offset" to offset.coerceAtLeast(0).toString(),\n          "musicFolderId" to musicFolderId.toString(),',
     '"offset" to offset.coerceAtLeast(0).toString(),\n          "musicFolderId" to "1",',
     "getAlbumList2 sends whichever ordering, scope and page it is given", 1),
    ("wire/getAlbumList2-type", CLIENT,
     '"type" to type.wireValue,', '"type" to "alphabeticalByName",',
     "getAlbumList2 sends whichever ordering, scope and page it is given", 1),
    ("wire/getAlbumList2-size", CLIENT,
     '"size" to size.coerceIn(1, MAX_ALBUM_LIST_PAGE).toString(),',
     '"size" to (if (size < 1) 1 else MAX_ALBUM_LIST_PAGE).toString(),',
     "getAlbumList2 sends the scope, the type, the page and full authentication", 1),
    ("wire/getAlbum-id", CLIENT,
     'mapOf("id" to albumId)', 'mapOf("id" to "abc123")',
     "getAlbum sends whichever album id it is given and must not send a scope", 1),
    ("wire/search3-musicFolderId", CLIENT,
     '"musicFolderId" to musicFolderId.toString(),\n          "artistCount"',
     '"musicFolderId" to "1",\n          "artistCount"',
     "search3 maps artists, albums and songs and stamps all three", 1),
    ("wire/search3-query", CLIENT,
     '"query" to query,', '"query" to "tra ck",',
     "search3 maps artists, albums and songs and stamps all three", 1),
    ("wire/getRandomSongs-musicFolderId", CLIENT,
     '"size" to size.coerceIn(1, MAX_RANDOM_SONGS).toString(),\n          "musicFolderId" to musicFolderId.toString(),',
     '"size" to size.coerceIn(1, MAX_RANDOM_SONGS).toString(),\n          "musicFolderId" to "1",',
     "getRandomSongs sends whichever scope and size it is given", 1),
    ("wire/coverArt-id", CLIENT,
     '.addQueryParameter("id", coverArtId)', '.addQueryParameter("id", "al-abc_0")',
     "the cover art url forwards whichever art id and size it is given", 1),
    ("wire/coverArt-size", CLIENT,
     'builder.addQueryParameter("size", sizePx.toString())',
     'builder.addQueryParameter("size", "256")',
     "the cover art url forwards whichever art id and size it is given", 1),

    # ---- N-1, round 2: the stamp -- the sole source of Album/Song/Artist.libraryId -----------
    # 1 -> 2 in round 5: order/getAlbumList2-multi (N4-1b) also asserts this stamp on the same
    # command, over the new multi-album fixture, so this mutation now reddens that test too.
    ("stamp/getAlbumList2", CLIENT,
     "return body.albumList2?.album.orEmpty().map { it.toAlbum(musicFolderId) }",
     "return body.albumList2?.album.orEmpty().map { it.toAlbum(2) }",
     "getAlbumList2 stamps every album with the library it was scoped to", 2),
    ("stamp/getAlbum", CLIENT,
     "album = album.toAlbum(musicFolderId),\n      songs = album.song.map { it.toSong(musicFolderId) },",
     "album = album.toAlbum(7),\n      songs = album.song.map { it.toSong(7) },",
     "getAlbum stamps the album and every song from the argument, not from the body", 1),
    ("stamp/search3-artist", CLIENT,
     "artists = result?.artist.orEmpty().map { it.toArtist(musicFolderId) },",
     "artists = result?.artist.orEmpty().map { it.toArtist(3) },",
     "search3 stamps every artist, album and song from the argument, not from the body", 1),
    # The one that matters most: this is the command library-scoped shuffle calls, and before
    # round 2 its stamp was asserted at no value at all.
    ("stamp/getRandomSongs", CLIENT,
     "return body.randomSongs?.song.orEmpty().map { it.toSong(musicFolderId) }",
     "return body.randomSongs?.song.orEmpty().map { it.toSong(1) }",
     "getRandomSongs stamps every song with the library it was scoped to", 1),

    # ---- N3-1, round 4: every mapped DTO field, not just the stamp ---------------------------
    # 20 of the 22 non-stamp fields on Album/Artist/Song could each be a constant with the whole
    # build at exit 0. Three representatives are kept here rather than all 22 -- one per type, each
    # a field that was asserted at NO value at all -- because the full sweep is ~22 minutes and its
    # transcript is in task-3-report.md. If a mapper is rewritten, run that sweep again; these three
    # only prove the class of defect is still detected.
    # 2 -> 3 in round 5: order/getAlbumList2-multi (N4-1b) also asserts coverArtId on the new
    # album, so this mutation now reddens that test too.
    ("field/Album.coverArtId", CLIENT,
     "    coverArtId = coverArt,\n    songCount = songCount,",
     '    coverArtId = "al-7orvCZZyWRqsduCdqXoguY_6a8bbb51",\n    songCount = songCount,',
     "search3 maps every artist, album and song field from the second library", 3),
    # 1 -> 2 in round 5: the new N4-2/N4-3 absence test also asserts a default albumCount over a
    # body that omits it, so this mutation now reddens that test too.
    ("field/Artist.albumCount", CLIENT,
     "    albumCount = albumCount,", "    albumCount = 1,",
     "every album and song field the seeded container cannot vary is still read from the body", 2),
    ("field/Song.albumId", CLIENT,
     "    albumId = albumId,\n    albumName = album,",
     '    albumId = "7orvCZZyWRqsduCdqXoguY",\n    albumName = album,',
     "search3 maps every artist, album and song field from the second library", 1),

    # ---- N2-1, round 3: ScanStatus -- the watermark Task 6's sync engine is built on ----------
    # All three hardcoded together left `./gradlew check` at exit 0 with 101 tests green and
    # 56/56 branch coverage. `lastScan` was asserted only isNotNull/isNotBlank, never at a value.
    ("watermark/lastScan", CLIENT,
     "lastScan = status.lastScan,", 'lastScan = "anything-non-blank",',
     "getScanStatus maps navidrome's lastScan watermark", 2),
    ("watermark/isScanning", CLIENT,
     "isScanning = status.scanning,", "isScanning = false,",
     "getScanStatus maps a scan in progress, watermark and all", 1),
    ("watermark/scannedCount", CLIENT,
     "scannedCount = status.count,", "scannedCount = 4,",
     "getScanStatus maps a scan in progress, watermark and all", 1),

    # ---- N-2, round 2: the cover-art origin --------------------------------------------------
    # The anchor carries the `.addPathSegments` line beneath it, and must: Plan 3 Task 1 gave
    # `streamUrl` an identically-worded first line, and a one-line anchor became ambiguous the
    # moment it landed -- the probe then aborted with "PROBE TEXT NOT FOUND (or ambiguous)"
    # rather than reporting MISSED, which is the loud failure this runner is supposed to give.
    # `stream/host-and-scheme` below is the same mutation on the stream URL.
    ("origin/coverArt-host", CLIENT,
     'val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()\n'
     '      .addPathSegments("rest/getCoverArt")',
     'val builder = "http://elsewhere.example:9999/".toHttpUrl().newBuilder()\n'
     '      .addPathSegments("rest/getCoverArt")',
     "the cover art url carries full authentication and the art id", 3),

    # ---- The protocol constant nothing in the build referenced --------------------------------
    ("type/newest-typo", TYPE,
     'NEWEST("newest"),', 'NEWEST("nEwEsT_typo"),',
     "getAlbumList2 sends whichever ordering, scope and page it is given", 1),

    # ---- N4-1 / N4-1b, round 5: collection order, and a multi-album getAlbumList2 body -------
    # Every list assertion in this file was order-blind except getMusicFolders's (order-sensitive
    # by accident of a positional `containsExactly`, not by design) -- reversing getAlbum's,
    # search3's or getAlbumList2's mapped list left `check` at exit 0. getAlbumList2's own reversal
    # was additionally *degenerate* against the two original captures: both held exactly one album,
    # so a one-element list reversed is the identity and exit 0 proved nothing.
    # `get-album-list2-music-multi.json` is a genuine second capture (two albums) that makes this
    # probe non-degenerate -- see order-unit-report.md for how it was taken. Representative, one
    # per command whose order is meaningful; getRandomSongs is deliberately excluded, because its
    # order carries no meaning at all (also explained there).
    ("order/getAlbum-songs", CLIENT,
     "songs = album.song.map { it.toSong(musicFolderId) },",
     "songs = album.song.reversed().map { it.toSong(musicFolderId) },",
     "getAlbum maps the album and its songs and stamps both with the library", 1),
    ("order/search3-songs", CLIENT,
     "songs = result?.song.orEmpty().map { it.toSong(musicFolderId) },",
     "songs = result?.song.orEmpty().reversed().map { it.toSong(musicFolderId) },",
     "search3 maps artists, albums and songs and stamps all three", 1),
    ("order/getAlbumList2-multi", CLIENT,
     "return body.albumList2?.album.orEmpty().map { it.toAlbum(musicFolderId) }",
     "return body.albumList2?.album.orEmpty().reversed().map { it.toAlbum(musicFolderId) }",
     "getAlbumList2 preserves wire order and maps every field across a genuine multi-album body", 1),

    # ---- N4-2 / N4-3, round 5: absence -- a nullable field collapsing to "", and a DTO default --
    # Representative, not exhaustive, same stance as N3-1's three probes above. The full set closed
    # is Album.artistId/artistName and Song.suffix (N4-2: a nullable mapped field must stay null,
    # not collapse through a stray `?: ""`), plus AlbumBody.songCount/duration, ArtistBody.
    # albumCount and ChildBody.duration (N4-3: an absent-key DTO default must be read, not replaced
    # by a different constant) -- all ten measured individually in order-unit-report.md.
    ("nullability/Song.suffix", CLIENT,
     "    suffix = suffix,", '    suffix = suffix ?: "",',
     "absent optional fields default rather than degrading a null into an empty string", 1),
    ("default/ArtistBody.albumCount", MODEL,
     "  val albumCount: Int = 0,", "  val albumCount: Int = 99,",
     "absent optional fields default rather than degrading a null into an empty string", 1),

    # ---- N-2/N-6/N-9, fix round 1: MirrorMapper -- every forward/reverse field observed at
    # exactly one fixture, and searchPattern's own trim/escape logic. All twelve field mutations
    # here are the review's own C/D/E/I/J/K/L/M (forward) and REV-1/2/3/4 (reverse); the two
    # searchPattern mutations are N-7 (trim) and the %/_ escape chain N-9 moved onto the JVM tier.
    ("mapper/song-discNumber-fwd", MIRROR,
     "    discNumber = song.discNumber,", "    discNumber = null,",
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/song-discNumber-rev", MIRROR,
     "    discNumber = entity.discNumber,", "    discNumber = null,",
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/song-trackNumber-fwd", MIRROR,
     "    trackNumber = song.trackNumber,", "    trackNumber = 1,",
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/album-name-fwd", MIRROR,
     "    name = album.name,", '    name = "Test Album",',
     "a second album, every field disjoint from the first, still round-trips", 1),
    ("mapper/song-suffix-fwd", MIRROR,
     "    suffix = song.suffix,", '    suffix = "mp3",',
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/song-durationSeconds-fwd", MIRROR,
     "    durationSeconds = song.durationSeconds,", "    durationSeconds = 5,",
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/artistEntities-albumCount", MIRROR,
     "          albumCount = ordered.size,", "          albumCount = 2,",
     "a derived artist takes its library id from its albums", 1),
    ("mapper/artistEntities-libraryId", MIRROR,
     "          libraryId = ordered.first().libraryId,", "          libraryId = 9,",
     "artists are derived from the albums of one library", 1),
    ("mapper/album-songCount-rev", MIRROR,
     "    songCount = entity.songCount,", "    songCount = 3,",
     "a second album, every field disjoint from the first, still round-trips", 1),
    ("mapper/album-coverArtId-rev", MIRROR,
     "    artistName = entity.artistName,\n    coverArtId = entity.coverArtId,",
     '    artistName = entity.artistName,\n    coverArtId = "al-a1",',
     "a second album, every field disjoint from the first, still round-trips", 1),
    ("mapper/artist-albumCount-rev", MIRROR,
     "    albumCount = entity.albumCount,", "    albumCount = 5,",
     "a second artist entity, every field disjoint from the first, maps field by field", 1),
    ("mapper/album-durationSeconds-rev", MIRROR,
     "    songCount = entity.songCount,\n    durationSeconds = entity.durationSeconds,\n  )",
     "    songCount = entity.songCount,\n    durationSeconds = 15,\n  )",
     "a second album, every field disjoint from the first, still round-trips", 1),
    ("mapper/searchPattern-trim", MIRROR,
     "    val trimmed = query.trim()", "    val trimmed = query",
     # 2, not 1: an all-whitespace query is no longer trimmed to empty, so the blank-query test
     # ("   " -> null) fails alongside the dedicated trim test -- measured, not assumed.
     "searchPattern trims surrounding whitespace before building the pattern", 2),
    ("mapper/searchPattern-escape", MIRROR,
     r'    return "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"',
     r'    return "%" + trimmed + "%"',
     # 2, not 1: removing the whole escape chain also breaks the backslash-ordering test (a
     # literal backslash no longer gets doubled first) -- measured, not assumed.
     "searchPattern escapes the caller's own percent and underscore", 2),

    # ---- N2-3, re-review round 1: the three write-only sort keys -- no round trip can reach
    # them (the reverse mappers never read them back), so each needed its own second, disjoint
    # fixture. A hardcode to either of the two values in use always reddens exactly one test;
    # measured for both literals during the fix, one recorded here.
    ("mapper/song-sortTitle", MIRROR,
     "    sortTitle = sortKey(song.title),", '    sortTitle = "track 1",',
     "a second song, every field disjoint from the first, still round-trips", 1),
    ("mapper/album-sortName", MIRROR,
     "    sortName = sortKey(album.name),", '    sortName = "test album",',
     "a second album, every field disjoint from the first, still round-trips", 1),
    ("mapper/artistEntities-sortName", MIRROR,
     "          sortName = sortKey(name),", '          sortName = "test artist",',
     "a derived artist takes its library id from its albums", 1),
    # ---- Task 8 / review round 1 (task-8-report.md, task-8-review.md): SetupViewModel ----------
    # Every one of these hardcodes or drops a value one constant could satisfy, on the class whose
    # own doc calls its role field "the only value in the entire system that distinguishes a book
    # from a song". N-1's own two production mutants (a hardcoded role in the @Inject constructor's
    # anonymous SetupLibrarySink, and the two FilterChip role literals swapped) are deliberately
    # NOT here: this runner is JVM-only (see this file's own header), and both of those mutants
    # pass every JVM test unchanged -- they are caught only on the emulator, by
    # completingEveryTagPersistsBothRolesAndLandsOnTheLibraryScreen's read-back, and are recorded in
    # task-8-report.md instead, the same way this header already documents for every other
    # instrumented-tier defect this project has found.
    ("setup/cancellation-rethrow", SETUP_VM,
     "      } catch (e: CancellationException) {\n        throw e\n      } catch (e: Exception) {",
     "      } catch (e: Exception) {",
     "a cancelled connection is never reported as a failure", 1),
    ("setup/setRole-nullguard", SETUP_VM,
     "      serverInfo?.let { _uiState.value = tagging(it) }",
     "      _uiState.value = tagging(serverInfo!!)",
     "setting a role before any connection has succeeded stores it but touches no screen state", 1),
    ("setup/tagging-emptylist", SETUP_VM,
     "      canContinue = current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED },",
     "      canContinue = current.none { it.role == LibraryRole.UNASSIGNED },",
     "a server with no libraries at all has nothing to continue past", 1),
    ("setup/continueToLibrary-emptylist", SETUP_VM,
     "      if (current.isNotEmpty() && current.none { it.role == LibraryRole.UNASSIGNED }) {\n        _uiState.value = SetupUiState.Ready",
     "      if (current.none { it.role == LibraryRole.UNASSIGNED }) {\n        _uiState.value = SetupUiState.Ready",
     "continuing with no libraries at all does nothing", 1),
    ("setup/setRole-id-passthrough", SETUP_VM,
     "      libraries.setRole(musicFolderId, role)\n      serverInfo?.let",
     "      libraries.setRole(1, role)\n      serverInfo?.let",
     "tagging every library is what unlocks continuing", 1),
    ("setup/setRole-role-passthrough", SETUP_VM,
     "      libraries.setRole(musicFolderId, role)\n      serverInfo?.let",
     "      libraries.setRole(musicFolderId, LibraryRole.MUSIC)\n      serverInfo?.let",
     "tagging every library is what unlocks continuing", 1),
    ("setup/connect-order", SETUP_VM,
     "        credentials.save(entered)\n        libraries.refreshFromServer()",
     "        libraries.refreshFromServer()\n        credentials.save(entered)",
     "credentials are stored before the libraries are fetched", 1),
    ("setup/connect-trim", SETUP_VM,
     "    val trimmedUrl = serverUrl.trim()", "    val trimmedUrl = serverUrl",
     "the server url is trimmed before it is stored", 1),
    ("setup/tagging-serverinfo-field", SETUP_VM,
     "      serverInfo = info,\n      libraries = current,",
     '      serverInfo = info.copy(serverVersion = "9.9.9"),\n      libraries = current,',
     "a successful connect saves the credentials and lists the libraries for tagging", 1),

    # ---- Task 6: SyncDecision -- the pure watermark ruling SyncEngine.syncIfStale is built on --
    # The only class this task adds that is JVM-measurable at all (SyncEngine itself needs Room,
    # see task-6-report.md); each of the four guards in `decide`'s `when` collapsed to a constant
    # `false` (or, for the final `else`, to the wrong source value) and the test that catches it.
    ("sync/decide-scanning-guard", SYNC_DECISION,
     "      status.isScanning -> ScanInProgress",
     "      false -> ScanInProgress",
     "a scan in progress is never a reason to reconcile", 1),
    ("sync/decide-null-lastScan-guard", SYNC_DECISION,
     "      status.lastScan == null -> Reconcile(null)",
     "      false -> Reconcile(null)",
     "a server that reports no lastScan reconciles every time and stores nothing", 1),
    ("sync/decide-uptodate-guard", SYNC_DECISION,
     "      status.lastScan == stored -> UpToDate",
     "      false -> UpToDate",
     "an unchanged watermark means there is nothing to do", 1),
    # 2, not 1: the reconcile carries the *server's* new value, not whatever was already stored --
    # both "a moved watermark..." (stored != new lastScan) and "the first ever sync..." (stored is
    # null, lastScan is not) construct a `Reconcile` whose argument this mutation gets wrong.
    ("sync/decide-else-watermark", SYNC_DECISION,
     "      else -> Reconcile(status.lastScan)",
     "      else -> Reconcile(stored)",
     "a moved watermark triggers a reconcile carrying the new value", 2),

    # ---- Task 9: LibraryViewModel/AlbumViewModel -- the ruling's own forwarding proofs --------
    # The brief for this task ships no ViewModel tests at all; the ruling that added
    # LibraryViewModelTest/AlbumViewModelTest required every one of these to be provable by
    # mutation before being trusted, not just written. Each was applied and confirmed to redden
    # exactly the named test(s) during task-9's own implementation (see task-9-report.md).
    ("library/albums-ignores-selection", LIBRARY_VM,
     "      libraries.firstOrNull { it.id == selected }?.id ?: libraries.firstOrNull()?.id",
     "      libraries.firstOrNull()?.id",
     "selecting a library shows that library's own albums, not the previous selection's", 2),
    ("library/search-libraryId-hardcoded", LIBRARY_VM,
     "else source.search(id, newQuery, SEARCH_LIMIT).albums",
     "else source.search(1, newQuery, SEARCH_LIMIT).albums",
     # 1 -> 2 in review round 1: `a library switch drops the previous library's search results...`
     # asserts the second search's own (libraryId, query, limit) triple too.
     "searching forwards the exact query and the currently selected library, not a stale or "
     "swapped one", 2),
    ("library/shuffle-size-hardcoded", LIBRARY_VM,
     "source.shuffle(id, ShuffleRepository.DEFAULT_SHUFFLE_SIZE)",
     "source.shuffle(id, 10)",
     "shuffle forwards the exact selected library id and the default shuffle size", 1),
    ("library/currentLibraryId-no-fallback", LIBRARY_VM,
     "(uiState.value as? LibraryUiState.Content)?.selectedLibraryId\n      ?: source.allIds().firstOrNull()",
     "(uiState.value as? LibraryUiState.Content)?.selectedLibraryId",
     "actions fall back to the mirror's own known library ids when no library is selected yet", 1),
    ("library/scan-message-reverts-to-unkept-promise", LIBRARY_VM,
     '"The server is still scanning, so some albums may be missing. Tap $REFRESH_LABEL when it '
     'has finished."',
     '"Your library will update shortly."',
     "a scan in progress names the Refresh control by the screen's own label, not a promise "
     "nothing keeps", 1),
    # AlbumViewModel was rewritten mid-task (see task-9-report.md): a real-device run found
    # savedStateHandle["albumId"] null under Navigation 3, so `load(albumId)` replaced the
    # SavedStateHandle/checkNotNull design these two probes originally targeted. Re-measured
    # against the replacement.
    ("album/songs-id-swapped", ALBUM_VM,
     "combine(album, source.songs(id)) { fetch, songs ->",
     'combine(album, source.songs("wrong-id")) { fetch, songs ->',
     # 4 -> 6 in Task 9's review round 1: two AlbumViewModelTest tests were added that also load a
     # real id and read the resulting Content, so they redden here too. See the note on
     # `expected failures` above -- a count above 1 is a measurement, and it goes stale when tests
     # are added.
     "the album shown is the one load was called with, not a different one the source also "
     "knows", 6),
    ("album/load-album-id-hardcoded", ALBUM_VM,
     "viewModelScope.launch { album.value = Fetch.Done(source.album(albumId)) }",
     'viewModelScope.launch { album.value = Fetch.Done(source.album("wrong-id")) }',
     # 4 -> 7, same cause as the probe above; this one additionally reddens the album-call-count
     # assertion in `an album still being fetched is Loading...`.
     "the album shown is the one load was called with, not a different one the source also "
     "knows", 7),

    # ---- Task 9 / review round 1 (task-9-review.md): the values no test observed ---------------
    # N-2, N-3 and N-5. Every one of these mutations left all 34 of this module's tests green when
    # the review applied it. Two of them (`libraries` at no value, and the album that announces
    # itself deleted while it loads) are user-facing: the first deletes the Music/Audiobooks chip
    # row outright, which is the only control this app has for the distinction it exists to make.
    #
    # N-1 and N-6 from the same review are NOT here and cannot be: both are instrumented-tier
    # (a navigation callback and the album id's two hops above the view model), this runner is
    # JVM-only per this file's own header, and their mutants are recorded in task-9-report.md
    # instead -- the same way task-8-report.md carries SetupViewModel's two device-only mutants.
    ("library/libraries-not-forwarded", LIBRARY_STATE,
     "    libraries = libraries,", "    libraries = emptyList(),",
     "the library selector carries every library, exactly, in the order the mirror gave them", 2),
    ("library/libraries-reordered", LIBRARY_STATE,
     "    libraries = libraries,", "    libraries = libraries.reversed(),",
     "the library selector carries every library, exactly, in the order the mirror gave them", 2),
    ("library/shuffled-order-reversed", LIBRARY_STATE,
     "    shuffled = shuffle?.songs.orEmpty(),", "    shuffled = shuffle?.songs.orEmpty().reversed(),",
     "shuffle order is the order the shuffle produced, not resorted", 2),
    ("library/search-results-resorted", LIBRARY_STATE,
     "    albums = if (searching) searchAlbums else albums,",
     "    albums = if (searching) searchAlbums.sortedBy { it.name } else albums,",
     "search result order is preserved, not resorted", 2),
    ("library/selectLibrary-keeps-stale-search", LIBRARY_VM,
     '    query.value = ""\n    searchAlbums.value = emptyList()\n  }',
     '    query.value = ""\n  }',
     "a library switch drops the previous library's search results before the new search "
     "returns", 1),
    ("album/double-load-guard-deleted", ALBUM_VM,
     "    if (this.albumId.value == albumId) return\n", "",
     "loading the same album twice never fetches it a second time, and keeps what is on "
     "screen", 1),
    ("album/loading-reads-as-notfound", ALBUM_VM,
     "Fetch.Pending -> AlbumUiState.Loading", "Fetch.Pending -> AlbumUiState.NotFound",
     # 2: `switching to another album shows Loading...` observes the same Pending state at the
     # other place it is reachable (across an id change), so it reddens too.
     "an album still being fetched is Loading, not the deleted-album message", 2),
    ("album/load-keeps-previous-album", ALBUM_VM,
     "    album.value = Fetch.Pending\n    this.albumId.value = albumId",
     "    this.albumId.value = albumId",
     "switching to another album shows Loading, never the previous album under the new id", 1),

    # ---- Plan 3 Task 1: /rest/stream, the one URL no Retrofit call site covers ----------------
    # This URL is handed to Media3, which fetches it with its own HTTP stack and none of this
    # client's interceptors -- so a parameter missing here has no second chance to be added, and
    # nothing downstream can notice. Each of the first three below ALSO reddens `LiveNavidromeTest`
    # (recorded in task-1-report.md); this runner is JVM-only per its own header, so the counts
    # here are the JVM half only.
    ("stream/song-id", CLIENT,
     '.addQueryParameter("id", songId)', '.addQueryParameter("id", "track-1")',
     "the song id is on the url and is the one the caller asked for", 1),
    ("stream/format-wire-value", CLIENT,
     '.addQueryParameter("format", format.wireValue)', '.addQueryParameter("format", "raw")',
     # The live half of this one is the interesting half: the JVM test proves the parameter is
     # built, `a live transcode returns no content length and refuses ranges` proves the server
     # acts on it.
     "an mp3 request sends format mp3 and the bitrate cap it was given", 1),
    # Plan 1's own finding -- `authParams()` returning nothing left 81 tests green -- re-armed for
    # a URL that no Retrofit call site covers. 4: `f`, `c`/`v`, the token and the salt-freshness
    # test all read parameters this line is the sole source of.
    # A URL is a compound value: `origin/coverArt-host` (N-2) was a cover-art URL that could have
    # pointed at another host entirely with 97 tests green. The stream URL is the one string
    # Media3 fetches with no interceptor of ours in the path, so it gets the same probe.
    ("stream/host-and-scheme", CLIENT,
     'val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()\n'
     '      .addPathSegments("rest/stream")',
     'val builder = "http://elsewhere.example:9999/".toHttpUrl().newBuilder()\n'
     '      .addPathSegments("rest/stream")',
     # 3: the sub-path test observes the same origin from the other side -- a proxy prefix that
     # this mutation drops -- and, since the N-2 fix below, so does the scheme test (2 -> 3: this
     # mutation swaps https for http as part of the origin, which is now observed on its own).
     "the host and scheme come from the credentials", 3),
    ("stream/no-auth-params", CLIENT,
     '    }\n    authParams().forEach { (name, value) -> builder.addQueryParameter(name, value) }\n'
     '    return builder.build().toString()\n  }\n',
     '    }\n    return builder.build().toString()\n  }\n',
     "the token on this url is a real md5 of the password and the salt beside it", 4),

    # ---- Plan 3 Task 1: "never Opus" is a decision, not an omission ---------------------------
    # `format=raw` means the bytes on the wire are whatever the file is, so the rule cannot be
    # enforced by leaving a parameter off. Both directions of the branch are probed: a policy that
    # never transcodes and a policy that always does are each caught by a different test, which is
    # what makes the branch a discrimination rather than a coincidence.
    ("format/always-raw", STREAM_FORMAT,
     "      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(transcodeBitRateKbps) else Raw",
     "      Raw",
     # 5: the opus case, the ogg case, the case-insensitive pair, the caller's-bitrate pair and
     # (since the N-1 fix, 4 -> 5) the whole-family case all observe a transcode that no longer
     # happens.
     "an opus source is transcoded rather than streamed raw", 5),
    ("format/always-mp3", STREAM_FORMAT,
     "      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(transcodeBitRateKbps) else Raw",
     "      Mp3(transcodeBitRateKbps)",
     "every other suffix streams raw", 1),
    # The constant-in-a-mapped-field defect in its purest form, and the reason the named test
    # exists at all: every other test in `StreamFormatTest` passes with the bitrate hardcoded.
    ("format/bitrate-hardcoded", STREAM_FORMAT,
     "      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(transcodeBitRateKbps) else Raw",
     "      if (suffix?.lowercase() in TRANSCODE_ONLY_SUFFIXES) Mp3(192) else Raw",
     "the transcode bitrate is the one the caller passed", 1),

    # ---- Plan 3 Task 2: the 429 policy, which is mostly branches nobody exercises -------------
    # A retry policy tested only on its happy path is untested: what matters is each status class,
    # each attempt count, each bound, and the give-up path as well as the retry path. The five
    # below drive one arm each, and each one is a value that a single constant could satisfy if
    # only one observation of it existed.
    #
    # Note what is NOT here, and cannot be: the Media3 adapter
    # (`NavidromeLoadErrorHandlingPolicy`) and the data source live on the instrumented tier --
    # every input type in their signatures is a Media3 or Android type -- so mutations of those
    # are recorded in task-2-report.md, the same way `LiveNavidromeTest`'s are. That split is
    # exactly why `StreamRetryPolicy` is a separate type with no Media3 or Android type in its
    # own signature: it puts the decision where this runner can reach it.
    ("retry/not-my-business", RETRY_POLICY,
     "    if (responseCode != TOO_MANY_REQUESTS) return null\n",
     "",
     # Every other test in the file sends 429, so only the one that sends 404/416/500/200 moves.
     "a status that is not 429 is not this policy's business", 1),
    ("retry/backoff-flat", RETRY_POLICY,
     "    return BASE_BACKOFF_MS shl doublings",
     "    return BASE_BACKOFF_MS",
     # 3: the exponential test, the ceiling test and the unparseable-header test all read the
     # backoff at an attempt count above 1.
     "a 429 with no retry-after backs off exponentially from the base delay", 3),
    ("retry/no-ceiling", RETRY_POLICY,
     "    return delay.coerceIn(0L, MAX_BACKOFF_MS)",
     "    return delay",
     # Exactly 1, and that is the point: the `Retry-After` path is clamped *before* the multiply
     # (see `retry/overflow-to-immediate` below), so "an oversized retry-after is clamped" stays
     # green here. Only the backoff's own ceiling moves.
     "the backoff is capped rather than doubling forever", 1),
    ("retry/zero-is-absent", RETRY_POLICY,
     "?.takeIf { it >= 0 }", "?.takeIf { it > 0 }",
     # `Retry-After: 0` is a legal "now" and is not the same as no header at all. A negative value
     # still falls through to the backoff under both forms, so the negative test cannot catch this.
     "a retry-after of zero means retry now and is not mistaken for absent", 1),
    # Found by this task's own audit, not by the plan: `seconds * 1000L` overflows `Long` above
    # ~9.2e15 and the product is negative, which the trailing `coerceIn(0L, MAX)` reads as 0 -- an
    # immediate retry, produced by the branch whose whole purpose is to slow down.
    ("retry/overflow-to-immediate", RETRY_POLICY,
     "      ?.coerceAtMost(MAX_BACKOFF_MS / 1000L)\n", "",
     "a retry-after large enough to overflow milliseconds is still clamped, not immediate", 1),

    # ---- Plan 3 Task 2: the media client's timeouts were three comments and no assertion ------
    # The absence of a `callTimeout` is a decision -- a streaming body is legitimately open for the
    # length of a track -- and "we did not set it" and "we thought about it and must not set it"
    # are different facts, only one of which survives a refactor.
    ("media/call-timeout", MEDIA_MODULE,
     "      .build()", "      .callTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)\n      .build()",
     "there is no call timeout, because a streaming body is legitimately open for a whole track", 1),
    ("media/read-timeout-copied", MEDIA_MODULE,
     "      .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
     "      .readTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
     "the connect and read timeouts are the two the media layer chose, and they are not each other", 1),
    # The first of the two stated reasons for choosing OkHttp over `DefaultHttpDataSource` at all:
    # a Navidrome behind a reverse proxy redirects http to https, and a client that refuses that
    # presents as a dead track. The default is right; the probe is that nobody can quietly change it.
    ("media/no-cross-protocol-redirects", MEDIA_MODULE,
     "      .build()", "      .followSslRedirects(false)\n      .build()",
     "redirects are followed, including across protocols", 1),

    # ---- Plan 3 Task 8a: the resume policy -- two numbers, and one of them is the whole seam ----
    # `NeverResume.resolve` is one expression returning two values, which is precisely the shape
    # this file's history says goes unobserved: a caller's index and a playback position, either of
    # which a single constant could satisfy. It is on the JVM tier for the same reason
    # `StreamRetryPolicy` is -- `resolve(mediaIds, requestedIndex)` names no Media3 or Android type,
    # deliberately, so the one thing in this application allowed to choose a playback position is
    # reachable from here.
    #
    # Note what is NOT here and cannot be: the *application* of the decision. `MuPlayer`'s six
    # `setMediaItem(s)` overrides are what carry the answer to the player, they are instrumented
    # (`ForwardingPlayer`, `MediaItem`, a real `ExoPlayer`), and a policy consulted correctly by
    # five of six overloads would leave every probe below green. Those mutations belong to Task 8b
    # and are recorded in its report, the same way `LiveNavidromeTest`'s test-side probes are.
    # `ProgressTableShapeTest`'s own falsifiability is likewise absent by scope: the only mutation
    # that would exercise it adds a column to a Room `@Entity`, which changes the exported schema
    # and reddens `:core:database` wholesale rather than the one guard; it is recorded in
    # task-8a-report.md as a test-side check instead.
    #
    # 5, 4, 4 and 3 rather than 1: "music restarts from 0" is asserted from several angles on
    # purpose, so breaking it reddens several. The counts are measured, and a count that drifts is
    # the signal -- see the note on `expected failures` above before changing one.
    ("resume/position-honoured", RESUME_POLICY,
     "startPositionMs = 0L)", "startPositionMs = 30_000L)",
     # The defect spec section 3's whole seam exists to prevent, in its purest form: a policy that
     # resumes music. Nothing outside `ResumePolicy` may choose a position, so nothing outside it
     # can be mutated to prove the rule is live.
     "the plan-3 policy starts every item from zero", 5),
    ("resume/position-from-queue", RESUME_POLICY,
     "startPositionMs = 0L)", "startPositionMs = mediaIds.size.toLong())",
     # The same value read off the queue instead of being zero. The index-varying test cannot see
     # this -- it asks about one queue -- which is why the queue-varying one exists.
     "the position is zero whatever queue it is shown", 4),
    ("resume/index-hardcoded", RESUME_POLICY,
     "ResumeTarget(startIndex = requestedIndex,", "ResumeTarget(startIndex = 0,",
     # The index belongs to the CALLER: "play track 3 of this album" is a legitimate request and a
     # policy that discards it breaks that, silently, for every album.
     "the caller's chosen item is respected", 3),
    ("resume/index-from-queue", RESUME_POLICY,
     "ResumeTarget(startIndex = requestedIndex,",
     "ResumeTarget(startIndex = mediaIds.lastIndex - requestedIndex,",
     # An index chosen from a mirrored view of the queue -- the shape a "resume from the end"
     # attempt reaches for. It names the wrong track for every queue longer than one item, and it
     # is the order-sensitivity probe for this file: `mediaIds` is an ordered list and
     # `requestedIndex` indexes into the order the caller passed, not into any other view of it.
     "neither the queue's contents nor its order changes the answer", 4),

    # ---- Plan 3 Task 8b: the two bindings the seam and the writer are actually built from -------
    # `MuPlayer` and `ProgressWriter` themselves have NO probe here and cannot have one, and the
    # reason is the measured limit this file's header describes: both are production classes whose
    # only tests are instrumented (`MuPlayerTest`, `ProgressWriterTest`). A mutation to either
    # recompiles `:core:media:testDebugUnitTest` -- they are `src/main` files, so the cache key does
    # move -- and then no JVM test covers them, so this runner reports MISSED with **zero**
    # failures. That reads like a broken test rather than an unrunnable probe, which is exactly the
    # trap Task 7b fell into and removed a probe over. Every one of those mutations was instead
    # applied BY HAND against a device run, and the transcripts are in task-8b-report.md: all six
    # `setMediaItem(s)` overrides deleted one at a time, the caller's position passed through
    # instead of the policy's, the read-modify-write replaced by a fresh entity, `clock.millis()`
    # replaced by a literal, the silence-skip guard removed, and the ticker removed.
    #
    # What CAN be probed from here is the pair of module bindings this task adds, because
    # `MediaModule` deliberately names no Android and no Media3 type -- the same property that put
    # its timeout decisions in reach above. Both are one-line bindings that are silent when wrong: a
    # frozen clock stamps every row with one instant and `recentlyPlayed`'s ORDER BY becomes
    # arbitrary, and a resume policy that answers a position undoes spec section 3's guarantee at
    # the only point in the graph where it is chosen.
    ("progress/clock-frozen", MEDIA_MODULE,
     "  fun provideClock(): Clock = Clock.systemUTC()",
     "  fun provideClock(): Clock =\n    Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)",
     # A `Clock.fixed` left behind by a test edit compiles, injects, and writes a row every five
     # seconds with `lastPlayedAtEpochMs = 0`. Nothing else in the build would notice.
     "the injected clock is a real clock and not a frozen one", 1),
    ("progress/policy-resumes", MEDIA_MODULE,
     "  fun provideResumePolicy(): ResumePolicy = NeverResume",
     "  fun provideResumePolicy(): ResumePolicy =\n"
     "    ResumePolicy { _, i -> app.muplay.media.ResumeTarget(i, 30_000L) }",
     # `resume/position-honoured` above breaks `NeverResume` itself; this breaks the *binding*, which
     # is the other way the same defect arrives and the one Plan 4 will be editing. `MuPlayer`
     # faithfully applies whatever is bound here, so a wrong binding is a wrong app.
     "the bound resume policy is the one that resumes nothing", 1),
    # ---- Plan 3 Task 1, review round 2 (N-1, N-2): two values with no discriminating observation
    # Both are the shape this whole file exists for, and neither was caught by any of the seven
    # task-1 probes above -- which is the point: a probe list records the questions someone
    # thought to ask, and nobody had asked either of these.
    #
    # N-2. Inserting `.scheme("http")` into the stream URL's builder is a silent HTTPS-to-cleartext
    # downgrade of the one URL in this codebase that carries an authentication token out of this
    # client's control (Media3 fetches it with its own HTTP stack, no interceptor of ours in the
    # path). Measured on the committed tree before the fix: the entire JVM tier stayed green, 217
    # tests 0 failures, and the live tier could not see it either because `ci-navidrome-1` is
    # plain `http://localhost:4533`. Distinct from `stream/host-and-scheme` above, which replaces
    # scheme AND host AND port as one unit and is caught by the host: this one changes the scheme
    # and nothing else, so only an assertion that observes the scheme by itself can catch it.
    ("stream/scheme-downgrade", CLIENT,
     'val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()\n'
     '      .addPathSegments("rest/stream")',
     'val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()\n'
     '      .scheme("http")\n'
     '      .addPathSegments("rest/stream")',
     "the scheme comes from the credentials and is never downgraded to cleartext", 1),
    # N-1. `oga` -- the IANA-registered Ogg *audio* extension, which the pinned server's own
    # audio-extension table carries directly beside `ogg` (read out of `ci-navidrome-1`) -- was
    # missing from TRANSCODE_ONLY_SUFFIXES, so `forSuffix("oga", ...)` returned Raw and an
    # Ogg-Opus track would reach the player as Opus mislabelled `audio/ogg`: the exact harm spec
    # section 4's "never Opus" exists to prevent. It was silent -- StreamFormatTest 9/9 green and
    # both `format/` probes above CAUGHT -- because every assertion named only suffixes the set
    # already held. So this probe is an OMISSION, not a wrong answer: the named test carries the
    # family list independently of the production set, which is the only way a set can be observed
    # for what is missing from it.
    ("format/oga-omitted", STREAM_FORMAT,
     'setOf("opus", "ogg", "oga")', 'setOf("opus", "ogg")',
     "every suffix the ogg container is indexed under is transcoded, in either case", 1),
    # ---- Plan 3 Task 7: the gapless oracle -----------------------------------------------------
    # `PcmAnalysis` is the analyser `GaplessTest` reads its whole claim through, and that claim is
    # the *absence* of a problem: the emulator test passes when `longestZeroRunFrames` returns a
    # small number. An analyser that returned a small number unconditionally would leave that test
    # green having measured nothing at all -- which is exactly why the analyser is pure JVM code in
    # `:core:testing` rather than a private helper in `:core:media`'s androidTest, and why every
    # value in it is probed here rather than trusted on the device tier.
    ("pcm/frame-count-ignores-channels", PCM_ANALYSIS,
     "return byteCount / (BYTES_PER_SAMPLE * channelCount)",
     "return byteCount / BYTES_PER_SAMPLE",
     # 2: the mono/stereo pair pins it directly, and the interleaved-stereo silence test reads
     # frames through it, so a stereo frame count that is silently doubled reddens both.
     "frames are bytes divided by two per channel", 2),
    ("pcm/bytes-per-sample-halved", PCM_ANALYSIS,
     "private const val BYTES_PER_SAMPLE = 2",
     "private const val BYTES_PER_SAMPLE = 1",
     # 7, and broad on purpose: this is the unit every other measurement in the class is expressed
     # in, so there is no narrow probe for it. The count is what makes that visible.
     "frames are bytes divided by two per channel", 7),
    ("pcm/frame-count-unguarded", PCM_ANALYSIS,
     'require(channelCount > 0) { "channelCount must be positive, was $channelCount" }\n    return byteCount',
     "return byteCount",
     # 2, and the second one is the point: `longestZeroRunFrames` deliberately does NOT carry a
     # second copy of this guard (a sweep proved no test could tell that copy from its absence), so
     # the silence scan reaches this one instead -- and without it divides by zero, an
     # ArithmeticException where an IllegalArgumentException naming the argument was promised.
     "a zero channel count is rejected rather than dividing by zero", 2),
    ("pcm/zero-run-blind", PCM_ANALYSIS,
     "    return longest\n  }", "    return 0\n  }",
     # The defect this whole module exists to make impossible: an oracle that always reports "no
     # silence". `GaplessTest` would stay green under it; these five go red.
     "a known run of silence is found and measured exactly", 5),
    ("pcm/zero-run-closes-late", PCM_ANALYSIS,
     "        if (current > longest) longest = current\n      } else {\n        current = 0\n      }",
     "      } else {\n        if (current > longest) longest = current\n        current = 0\n      }",
     # Closing a run only when a non-zero sample arrives misses a run that reaches the end of the
     # buffer -- and encoder *padding*, half of what "gapless" trims, lives exactly there.
     # Exactly 1, measured after predicting 2 and being wrong: the sine test's spliced-in encoder
     # delay is *followed* by signal, so a late-closing scan still records that run correctly. The
     # end-of-buffer test is the only one that can see this defect at all, which is the argument
     # for its existence stated as a number.
     "a run that ends at the end of the buffer still counts", 1),
    ("pcm/zero-run-never-resets", PCM_ANALYSIS,
     "      } else {\n        current = 0\n      }", "      }",
     # Without the reset this counts total silent frames rather than the longest consecutive run,
     # which is a larger number and so fails safe on the device tier -- but it is not the
     # measurement, and it would make a stream of scattered zero-crossings look like a gap.
     "the longest run is reported and not the first or the last", 2),
    ("pcm/zero-run-first-channel-only", PCM_ANALYSIS,
     "for (channel in 0 until channelCount) {", "for (channel in 0 until 1) {",
     # A frame is silent only when EVERY channel is: one silent channel in a stereo stream is real
     # signal, not a gap. Exactly 1 -- only the interleaved-stereo test has a second channel to
     # get wrong.
     "a frame counts as silent only when every channel is silent", 1),
    ("pcm/frames-to-ms-hardcoded-rate", PCM_ANALYSIS,
     "return frames.toLong() * MILLIS_PER_SECOND / sampleRateHz",
     "return frames.toLong() * MILLIS_PER_SECOND / 44100",
     # 44100 is the rate every fixture happens to use, so a hardcoded one is the accident that
     # would never show up on the device tier at all. Exactly 1, measured after predicting 2: every
     # other test in the class is at 44100 too, so the two-rate test is the sole observation that
     # can tell a hardcoded rate from a read one -- which is why it asserts at 48000 as well.
     "frames convert to milliseconds at the sample rate given", 1),
    ("pcm/frames-to-ms-unit", PCM_ANALYSIS,
     "private const val MILLIS_PER_SECOND = 1000L",
     "private const val MILLIS_PER_SECOND = 1L",
     # The unit the device tier's "< 10" is denominated in. Seconds instead of milliseconds would
     # make a 25 ms encoder-delay gap measure 0 and pass.
     "frames convert to milliseconds at the sample rate given", 2),
    ("pcm/frames-to-ms-negative-unguarded", PCM_ANALYSIS,
     'require(frames >= 0) { "frames must not be negative, was $frames" }\n', "",
     # A negative duration satisfies `isLessThan(10L)` as happily as a small positive one, so a
     # negative frame count must not be expressible as a duration at all.
     "a negative frame count is rejected rather than reported as a negative duration", 1),
    ("pcm/frames-to-ms-rate-unguarded", PCM_ANALYSIS,
     'require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }\n', "",
     "a zero sample rate is rejected rather than dividing by zero", 1),
    # ---- Plan 3 Task 4: the queue, and the one architectural rule a comment cannot keep --------
    # `PlaybackQueue` is the only one of this task's three types this JVM-only runner can reach.
    # `MediaItems` and `QueueRepository` are instrumented -- `MediaItem` is built on
    # `android.net.Uri`, which throws off-device, and Robolectric is banned -- so their five
    # mutations are recorded in task-4-report.md instead, the same way task-2-report.md carries
    # the Media3 adapter's and task-8-report.md carries SetupViewModel's two device-only mutants.
    #
    # `queue/position-field` is the important one and it is not a field-mapping probe at all: it
    # asserts that spec section 3's decision -- *the queue is a list of pointers; progress is a
    # property of the item* -- is still structurally enforced. A `positionMs` on this type is the
    # single global "now playing position" every other player has, and it is precisely why a user
    # cannot listen to music between two audiobook sessions without losing their place.
    ("queue/position-field", PLAYBACK_QUEUE,
     "data class PlaybackQueue(val songs: List<Song>, val startIndex: Int) {",
     "data class PlaybackQueue(val songs: List<Song>, val startIndex: Int, val positionMs: Long = 0) {",
     "the queue carries no playback position of its own", 1),
    # Deleting the guard still throws -- the `startIndex` guard catches an empty list next -- so
    # this probe only discriminates because the test asserts the *message*, not just the type.
    ("queue/empty-guard", PLAYBACK_QUEUE,
     '    require(songs.isNotEmpty()) { "a playback queue cannot be empty" }\n', "",
     "an empty queue is rejected", 1),
    # 2: with `startIndex` pinned to 0, the out-of-range call no longer throws either.
    ("queue/startIndex-hardcoded", PLAYBACK_QUEUE,
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)",
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, 0)",
     "a start index outside the queue is rejected", 2),
    ("queue/songAt-index", PLAYBACK_QUEUE,
     "  fun songAt(index: Int): Song = songs[index]",
     "  fun songAt(index: Int): Song = songs[0]",
     "songAt returns the song at that index", 1),
    # Found by this task's own audit, not by its brief: the brief's test observed `size` at
    # exactly one value (3, over a three-song queue), so `get() = 3` passed the whole suite.
    # Measured both ways -- 0 failures before a second, disjoint observation was added, 1 after.
    ("queue/size-hardcoded", PLAYBACK_QUEUE,
     "  val size: Int get() = songs.size", "  val size: Int get() = 3",
     "a queue holds the songs it was given in the order it was given them", 1),
    # A queue is ordered, and N4-1 (round 5) is this project's record of every list assertion in a
    # file being order-blind at once. 2: `songAt` reads the reversed list too.
    ("queue/songs-reversed", PLAYBACK_QUEUE,
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)",
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs.reversed(), startIndex)",
     "a queue holds the songs it was given in the order it was given them", 2),
    # ---- Plan 6 Task 1: the cast module's own codec and its local-network rule ----------------
    # Every count below was measured by applying the mutation by hand and reading the result XML;
    # see task-1-report.md for the transcripts. Counts above 1 are the probe reddening more than
    # its named test, which for these is the point rather than an accident -- a header lookup that
    # became case-sensitive should break every consumer that reads a header, not just one.
    ("cast/headers-case-sensitive", CAST_HEADERS,
     "entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }",
     "entries.filter { it.first == name }.map { it.second }",
     # The defect that makes a Sonos invisible: it sends CONTENT-TYPE, an SSDP reply sends
     # LOCATION, and a null LOCATION is a device that never appears with nothing reported anywhere.
     # 7 until the security review added `two content lengths that disagree are refused` -- which
     # reads a header, so a case-sensitive lookup reddens it too. Re-measured, not adjusted.
     "a header is found whatever case the peer used", 8),
    ("cast/render-bare-lf", CAST_WIRE,
     "append(\"HTTP/1.1 \").append(code).append(' ').append(reason).append(CRLF)",
     "append(\"HTTP/1.1 \").append(code).append(' ').append(reason).append(\"\\n\")",
     # Caught only because the render assertion is on the string. A round-trip test could not see
     # this at all: `readLine` deliberately tolerates a bare LF on receipt.
     "a rendered response head is byte-exact and always uses CRLF", 2),
    ("cast/cgnat-dropped", CAST_NET,
     "    return first == 100 && second in 64..127", "    return false",
     # RFC 6598, 100.64.0.0/10 -- what Tailscale hands out, and what isSiteLocalAddress() says
     # false for. Dropping it makes the spec's "Remote + VPN" row fail as "the speaker is not
     # there".
     "carrier-grade nat is local, and the addresses either side of the block are not", 1),
    ("cast/always-local", CAST_NET,
     "  private fun isLocalIpv4(address: Inet4Address): Boolean {\n    if (",
     "  private fun isLocalIpv4(address: Inet4Address): Boolean {\n    return true\n"
     "    @Suppress(\"UNREACHABLE_CODE\")\n    if (",
     # The same guard from the other side. Without this probe, `cast/cgnat-dropped` above would be
     # satisfied by a rule that permitted everything.
     # 6 until the security review gave this rule its inbound half: `isLocalPeer` and
     # `acceptLocal` ask the same question, so a rule that permits everything reddens their tests
     # too. Re-measured, not adjusted.
     "a public address is not local", 8),
    ("cast/no-local-guard", CAST_CLIENT,
     "    LocalNetworkOnly.require(host, address)\n", "",
     # The mutation that matters most in this module: without that one line MuPlay becomes an app
     # that will send plaintext anywhere it is pointed, and every other test stays green.
     "a public address is refused before a socket is opened", 1),
    # The anchor moved in the security review: every header line, this one included, now goes
    # through `HttpWire.headerLine`, which is the single place the CR/LF check can live. Same
    # mutation, same named test.
    ("cast/host-without-port", CAST_CLIENT,
     "      append(HttpWire.headerLine(\"Host\", hostHeader))",
     "      append(HttpWire.headerLine(\"Host\", host))",
     # A Sonos control endpoint lives on 1400 and answers 400 to a Host with no port.
     "a get request is framed with the request line, the host header and a blank line", 4),
    ("cast/tolerant-truncated-head", CAST_WIRE,
     "        if (endOfInputEndsBlock) return HttpHeaders(entries)\n"
     "        throw MalformedHttpException(\"connection closed inside a header block\")",
     "        return HttpHeaders(entries)",
     # A datagram ends where the packet ends; a socket that ends mid-head is a truncated read. The
     # two must not share an exit, or the proxy routes a request whose Range was still in flight.
     "a stream that ends inside the header block is rejected, not returned half-parsed", 1),
    ("cast/timeouts-swapped", CAST_CLIENT,
     "      socket.soTimeout = readTimeoutMs\n"
     "      socket.connect(InetSocketAddress(address, port), connectTimeoutMs)",
     "      socket.soTimeout = connectTimeoutMs\n"
     "      socket.connect(InetSocketAddress(address, port), readTimeoutMs)",
     # Two Ints of the same type, adjacent in one constructor -- this repo's recorded
     # wrong-argument shape, the same one `media/read-timeout-copied` records one module over.
     "the read timeout is the one the caller gave, and is not the connect timeout", 1),

    # ---- Plan 5 Task 1: BrowseId, the mediaId wire format --------------------------------------
    # `mediaId` is the only handle Android Auto, Wear OS and the Assistant keep for a node, and Auto
    # *persists* it across a reinstall -- so this encoding is a contract with software this project
    # does not own, and every one of these three mutations is a way it can change while every
    # round-trip assertion in the file stays green.
    ("browse/book-encode-drops-payload", BROWSE_ID,
     'override fun encode(): String = "$PREFIX$KIND_BOOK$SEPARATOR$bookId"',
     'override fun encode(): String = "$PREFIX$KIND_BOOK"',
     # 5, and deliberately broad: an encode that ignores its payload is the defect a per-member
     # round-trip test cannot see, so several independent assertions are meant to catch it. The one
     # that matters most is `no two nodes in the whole hierarchy encode to the same string`, which
     # reports the duplicate ("muplay/book" twice) rather than a mismatch -- injectivity is the
     # property, and it is the property no round trip implies.
     #
     # 5 as of 953a6ba, when nothing consumed `BrowseId` yet; 11 once Plan 5 Task 2's browse tree
     # became its first consumer and `BrowseTreeTest` gained six assertions that read
     # `BrowseId.Book(...).encode()` back as a map key or a list element. The named test failed
     # exactly as intended both times -- this is the stale-count case the note on `expected
     # failures` above describes, and Task 1's own report predicted this specific probe going stale
     # here. Re-measured, not deleted; the six new ones are `the book shelf sorts case-insensitively
     # and breaks its own ties by id`, `two books last heard in the same millisecond come out in the
     # same order either way round`, `a single-file book is playable but not browsable, and a
     # multi-file one is both`, `continue lists only started unfinished books, most recently heard
     # first`, `a book's completion is one of three distinct values` and `continue is capped by the
     # surface's own limit` -- every one of them a test that names a book by its encoded id, which
     # is the right way for a browse test to name one.
     "every id encodes to its exact documented string", 11),
    ("browse/library-id-non-canonical", BROWSE_ID,
     "        KIND_LIBRARY -> canonicalInt(payload)?.let(::Library)\n        KIND_SHUFFLE -> canonicalInt(payload)?.let(::Shuffle)",
     "        KIND_LIBRARY -> payload.toIntOrNull()?.let(::Library)\n        KIND_SHUFFLE -> payload.toIntOrNull()?.let(::Shuffle)",
     # The spec section 4 probe. `toIntOrNull` alone accepts "+1", "01" and "-0", so three
     # different strings would name library 1, 1 and 0 -- second spellings of a node in Auto's
     # persisted recents, which is exactly how a "shuffle my music" tap comes back pointing
     # somewhere else. Exactly 1: no other test in the repository reads a library id from a string.
     "a library id that is not canonically numeric is rejected rather than widened", 1),
    ("browse/decode-splits-payload", BROWSE_ID,
     '      val payload = if (hasPayload) body.substring(kind.length + SEPARATOR.length) else ""',
     '      val payload = body.split(SEPARATOR).getOrElse(1) { "" }',
     # A `split` without a limit turns "muplay/book/a/b/c" into a Book whose id is "a" -- silently,
     # for exactly the server ids that contain a separator. Navidrome ids are hex today, and "the
     # ids are hex" is the class of assumption spec section 4 is a catalogue of.
     "the payload survives every character a server id could contain", 1),
    # ---- Plan 7 Task 1: :integrations:core -----------------------------------------------------
    # Written as this task's own audit rather than from shipped defects, with one exception:
    # `baseurl/scheme-check-too-narrow` is the task brief's own bug, reintroduced -- it specified a
    # prefix check for http/https placed *before* HttpUrl, which answers "ftp://host" with
    # MissingScheme while the same brief's test and KDoc both call it Malformed.
    #
    # The rest are the two requirements this type exists for -- a credential must never survive into
    # the stored URL, and the cleartext policy must be unbypassable -- plus the three defect classes
    # this project has a recorded history of: a hardcoded value where an argument was passed, an
    # argument accepted and dropped, and a collection asserted without its order.
    #
    # Several counts are above 1 because `IntegrationBaseUrlTest` deliberately observes the
    # secret-stripping twice: once per component, and once with userinfo, query and fragment on a
    # single URL, so no single-component edit can survive by varying only what one test looks at.
    ("baseurl/query-not-stripped", BASE_URL,
     '        .query(null)\n        .fragment(null)\n',
     '        .fragment(null)\n',
     "a query string is discarded, including one carrying an api key", 2),
    ("baseurl/fragment-not-stripped", BASE_URL,
     '        .query(null)\n        .fragment(null)\n',
     '        .query(null)\n',
     "a fragment is discarded", 2),
    ("baseurl/userinfo-not-stripped", BASE_URL,
     '        .username("")\n        .password("")\n',
     '',
     "userinfo is discarded", 2),
    # The Retrofit invariant. Without the trailing slash, `baseUrl("https://host/lidarr")` resolved
    # against "api/v1/system/status" gives `https://host/api/v1/system/status` -- the urlBase
    # silently dropped, which is a 404 nobody reads backwards to this line.
    ("baseurl/trailing-slash", BASE_URL,
     'return if (stripped.endsWith("/")) stripped else "$stripped/"',
     'return stripped',
     "a url base path is preserved and terminated with a slash", 4),
    # Argument accepted and dropped, on the one argument that decides a security question.
    ("baseurl/cleartext-policy-ignored", BASE_URL,
     'private fun permitsCleartext(policy: CleartextPolicy): Boolean = when (policy) {\n'
     '      CleartextPolicy.Allowed -> true\n'
     '      CleartextPolicy.Forbidden -> false\n'
     '    }',
     'private fun permitsCleartext(policy: CleartextPolicy): Boolean = true',
     "http is refused when the policy forbids cleartext, and the host is reported", 3),
    # The hardcoded-value class, on the host the refusal message has to name.
    ("baseurl/cleartext-host-hardcoded", BASE_URL,
     'return BaseUrlResult.CleartextForbidden(parsed.host)',
     'return BaseUrlResult.CleartextForbidden("192.168.1.20")',
     "http is refused when the policy forbids cleartext, and the host is reported", 3),
    # The specific error collapsed into the general one: "that is not an address MuPlay can reach
    # Lidarr at", printed at someone who typed 192.168.1.20:8686.
    ("baseurl/missing-scheme-collapsed", BASE_URL,
     'if (!ANY_SCHEME.containsMatchIn(trimmed)) return BaseUrlResult.MissingScheme',
     'if (!ANY_SCHEME.containsMatchIn(trimmed)) return BaseUrlResult.Malformed',
     "a url with no scheme is MissingScheme, not Malformed", 1),
    ("baseurl/scheme-check-too-narrow", BASE_URL,
     'if (!ANY_SCHEME.containsMatchIn(trimmed)) return BaseUrlResult.MissingScheme',
     'if (!trimmed.lowercase().startsWith("http://") && !trimmed.lowercase().startsWith("https://")) return BaseUrlResult.MissingScheme',
     "a non-http scheme is Malformed", 1),
    # `message(service)` accepting a service and then naming one.
    ("baseurl/message-service-hardcoded", BASE_URL,
     '    "Start the ${service.displayName} address with https:// \u2014 for example " +\n'
     '      "https://${service.displayName.lowercase()}.example.com."',
     '    "Start the Lidarr address with https:// \u2014 for example " +\n'
     '      "https://lidarr.example.com."',
     "every message varies with the service it is asked about", 2),
    # Equality by type rather than by value: every base URL equal to every other one, which makes
    # this type useless as a map key and two configured integrations indistinguishable.
    ("baseurl/equals-ignores-value", BASE_URL,
     'this === other || (other is IntegrationBaseUrl && value == other.value)',
     'this === other || (other is IntegrationBaseUrl)',
     "two urls with the same value are equal and hash alike", 2),
    # The recorded wrong-collection-order class. `IntegrationService.entries` is rendered in
    # declaration order by every screen in this plan, so the order is the contract.
    ("baseurl/service-order", INTEGRATION_SERVICE,
     '  LIDARR("Lidarr"),\n  BINDERY("Bindery"),',
     '  BINDERY("Bindery"),\n  LIDARR("Lidarr"),',
     "the service display names are the ones a user reads", 1),

    # ---- Plan 3 Task 4, review round 1: what the queue's order and field assertions could not see
    # Appended here rather than folded into the Task 4 block above, so the block stays the record of
    # what that task shipped and this stays the record of what its review found. All three mutate
    # PLAYBACK_QUEUE, which `revert()` already names.
    #
    # M3. `queue/songs-reversed` above genuinely reddens -- `containsExactly` is order-sensitive --
    # and that made the order assertions look stronger than they were. Every fixture in
    # `PlaybackQueueTest` was `song("a"), song("b"), song("c")`: ascending by id, ascending by
    # `"Title $id"`, and *constant* by `trackNumber`. On a fixture like that every sort is the
    # identity, so a reversal was the only reordering the file could see. The fixture is now
    # `c(3), a(1), b(2)`, non-monotone in all three keys at once.
    ("queue/songs-sorted-by-id", PLAYBACK_QUEUE,
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)",
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs.sortedBy { it.id }, startIndex)",
     "a queue holds the songs it was given in the order it was given them", 1),
    # The one that is not hypothetical: "sort the queue by track number for album playback" is a
    # change someone makes on purpose, and it would silently destroy library-scoped shuffle -- this
    # project's headline feature -- by re-sorting a deliberately random order back into track order.
    # A stable sort on equal keys is the identity, which is exactly why the old constant-trackNumber
    # fixture could not see it.
    ("queue/songs-sorted-by-track-number", PLAYBACK_QUEUE,
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs, startIndex)",
     "fun of(songs: List<Song>, startIndex: Int = 0): PlaybackQueue = PlaybackQueue(songs.sortedBy { it.trackNumber }, startIndex)",
     "a queue holds the songs it was given in the order it was given them", 1),
    # L3. The companion-shaped half of `queue/position-field`. That probe adds an *instance*
    # property and the structural test catches it; this one adds the same defect in its worst form
    # -- one `positionMs` shared by every queue that will ever exist -- and until the filter was
    # narrowed from `Modifier.isStatic(...)` to `Modifier.isStatic(...) && name == "Companion"` the
    # test could not see it, because Kotlin emits a companion's property backing fields as static
    # fields on the *containing* class. The blanket static filter was required (the `Companion`
    # handle is ACC_PUBLIC|ACC_STATIC|ACC_FINAL and not synthetic); it was just too wide.
    ("queue/companion-position-field", PLAYBACK_QUEUE,
     "  companion object {\n",
     "  companion object {\n    var positionMs: Long = 0\n",
     "the queue carries no playback position of its own", 1),
    # ---- Plan 6 Task 1's security review: nine findings, and the probes that hold them ---------
    # Every count below was measured by applying the mutation and reading the result XML, the same
    # way the eight probes above it were. These reintroduce the defects a review found in the
    # committed module, not defects its author found while writing it -- which is why several of
    # them redden a dozen tests at once: a codec that stops refusing malformed input stops
    # refusing it everywhere.
    ("cast/header-value-unchecked", CAST_WIRE,
     "  private fun isFieldValueChar(c: Char): Boolean = c == '\\t' || c in ' '..'~'",
     "  private fun isFieldValueChar(c: Char): Boolean = true",
     # HIGH 1, the value half. `HttpHeaders.of("SOAPACTION" to "\"urn:x#Y\"\r\nX-Injected: ...")`
     # put a real second header into a request a real ServerSocket received. Task 3 builds
     # SOAPACTION out of the device-description XML the RENDERER serves, so this is peer-controlled.
     "a header value carrying CRLF cannot write a header of its own, and nothing reaches the wire", 4),
    ("cast/header-name-unchecked", CAST_WIRE,
     "  private fun isTokenChar(c: Char): Boolean =\n    c in 'a'..'z'",
     "  private fun isTokenChar(c: Char): Boolean =\n    true || c in 'a'..'z'",
     # HIGH 1, the name half. A name is a token per RFC 9110: no CR, no LF, no NUL, and no
     # separators or whitespace either -- `X-A: 1\r\nX-Injected` is a name that writes two headers.
     "every character that could end a header line early is refused, in a name and in a value", 4),
    ("cast/method-unchecked", CAST_CLIENT,
     "    HttpWire.requireToken(\"method\", method)\n", "",
     # HIGH 1, the request-line half. `method` was appended raw, so "GET /evil HTTP/1.1\r\nX: 1"
     # was a whole second request line.
     "a method that is not a token is refused, so the request line cannot be split either", 1),
    ("cast/caller-framing-header", CAST_CLIENT,
     "      require(FRAMING_HEADERS.none { it.equals(name, ignoreCase = true) }) {",
     "      require(FRAMING_HEADERS.none { it.equals(name + \"!\", ignoreCase = true) }) {",
     # HIGH 1's last clause: a caller-supplied Content-Length used to travel alongside the one
     # `exchange` appends, which is a request that frames two ways.
     "a caller may not supply a header that decides where the message ends", 1),
    ("cast/body-unbounded", CAST_WIRE,
     "    if (contentLength == null) return readUntilClosed(input, maxBytes)",
     "    if (contentLength == null) return input.readBytes()",
     # HIGH 2, first half, and the exact line that was there: `soTimeout` is per read, so a
     # renderer streaming steadily resets it forever and `readBytes()` never returns.
     "a response with no content length cannot exhaust the heap, however long the peer streams", 2),
    ("cast/chunked-misframed", CAST_WIRE,
     "      return readChunkedBody(input, maxBytes)",
     "      return readUntilClosed(input, maxBytes)",
     # HIGH 2, second half. A chunked response read flat comes back with its chunk sizes inside
     # the body, which Task 3 reports as an XML parse failure -- at the wrong layer, blaming the
     # renderer for MuPlay's bug.
     "a chunked response is decoded, not returned with its chunk sizes inside the body", 5),
    ("cast/content-length-overflow", CAST_WIRE,
     "    if (contentLength > maxBytes) {\n"
     "      throw MalformedHttpException(\n"
     "        \"declared ${HttpHeaders.CONTENT_LENGTH} $contentLength exceeds the $maxBytes byte cap on \" +\n"
     "          \"one body\",\n"
     "      )\n"
     "    }\n"
     "    return input.readNBytes(contentLength.toInt())",
     "    return input.readNBytes(contentLength.toInt())",
     # M4. Without the cap check in front of it, `2147483648` reaches readNBytes as a negative Int
     # (IllegalArgumentException, NOT an IOException, so a caller guarding a socket misses it) and
     # `4294967296` narrows to exactly 0 and returns a silently empty body.
     "a content length that does not fit an Int is refused, not narrowed into a wrong body", 2),
    ("cast/no-inbound-guard", CAST_NET,
     "    val socket = server.accept()\n"
     "    if (isLocalPeer(socket)) return socket\n"
     "    socket.close()\n"
     "    return null",
     "    return server.accept()",
     # HIGH 3. Task 6 binds a ServerSocket serving Navidrome-authenticated audio, reachable by
     # every device on the LAN and every other app on the phone. `isLocal` and `require` existed;
     # nothing called either of them on an accept().
     "a connection from off the local network is refused and closed rather than served", 2),
    ("cast/first-content-length-wins", CAST_HEADERS,
     "    val distinct = values.distinct()",
     "    val distinct = values.take(1)",
     # M9. `Content-Length: 3` and `Content-Length: 10` used to yield a three-byte body, silently.
     # RFC 9110 section 8.6 requires the refusal; this is the classic smuggling primitive.
     "two content lengths that disagree are refused, not silently resolved to the first", 1),
    ("cast/line-limit-off-by-one", CAST_WIRE,
     "    if (buffer.size() == MAX_LINE_BYTES) {",
     "    if (buffer.size() >= MAX_LINE_BYTES - 1) {",
     # M5. The limit was terminator-dependent (8192 + LF accepted, 8192 + CRLF rejected) and no
     # test could see it, because nothing observed MAX_LINE_BYTES from the ACCEPTING side at all.
     # This probe is that missing side: it rejects a line the codec must accept.
     "a line of exactly the maximum length is accepted whichever terminator the peer chose", 1),
    ("cast/route-probe-constant", CAST_ADDRESS,
     "        socket.localAddress.takeUnless { it.isAnyLocalAddress }",
     "        InetAddress.getLoopbackAddress()",
     # M7. This is the exact implementation that satisfied `LocalAddressTest` on a loopback-only
     # host, because its one discriminating assertion sat behind an assumeTrue. The seam added in
     # M6 is what makes this probe possible at all.
     #
     # READ THE NAMES BEFORE CHANGING THIS COUNT. Two of the three are hermetic; the third is
     # `the route to an address on a real interface is that interface's own address`, which is
     # this module's only `assumeTrue` and SKIPS on a host with no non-loopback IPv4 interface.
     # On such a host this probe reports 2 and reads as MISSED while the named test is still red,
     # which is the claim it actually makes. That is a property of the host, not a regression --
     # and it is the same degradation the seam above was added to stop mattering.
     "the address the kernel chose is the answer, whatever address that is", 3),
    ("cast/userinfo-in-message", CAST_CLIENT,
     "    if (userInfo == null) toString() else toString().replace(\"$userInfo@\", \"\")",
     "    toString()",
     # Minor, from the same review. Task 6 puts Subsonic's u/t/s in these URLs, and an exception
     # message is the one string in this project that reliably reaches a bug report.
     "a password in a url's userinfo never reaches an exception message", 1),
    # ---- Plan 3 Task 5: the service, the player factory and the connection -------------------
    # The one defect in this task that no runtime test can see, because it is structural: a second
    # `ExoPlayer.Builder` anywhere in the module compiles, runs, and quietly keeps Media3's own
    # three-retries-in-five-seconds, because the 429 policy attaches to the `MediaSource.Factory`
    # and `ExoPlayer.Builder` has no setter for it at all. `PlayerConstructionTest` is a source
    # scan for exactly that, and this is the probe that it still scans.
    #
    # It caught a real one within hours of being written: Task 3's `MediaCacheTest` hand-built two
    # players, so two of the three instrumented playback suites were driving a player that is not
    # the one that ships.
    #
    # Realigned by Plan 3 Task 8b, which changed the line it searched for: the session is now handed
    # a `MuPlayer` rather than the raw `ExoPlayer`. The mutation is the same defect in the same
    # place, spelled to still compile -- `ExoPlayer` is no longer imported in this file, so the
    # builder is named in full, and the scan `PlayerConstructionTest` runs matches the substring
    # either way. (Realigned rather than left stale: the note at the foot of this file records two
    # occasions when a rewritten line silently took a whole probe family out of service.)
    ("media/second-player-construction", PLAYBACK_SERVICE,
     "    val player: MuPlayer = playerFactory.create()",
     "    val player: MuPlayer =\n"
     "      MuPlayer(androidx.media3.exoplayer.ExoPlayer.Builder(this).build(), NeverResume)",
     # The named test is `production code constructs an ExoPlayer in exactly one place`, and it was
     # recorded here under an older name until Task 8b re-ran this probe: `PlayerConstructionTest`
     # split into a production half and a test-sources half during Task 3's fix round and this line
     # was not moved with it. The probe therefore reported MISSED while its subject was working
     # perfectly -- the second-worst outcome for a regression list, after silently passing.
     "production code constructs an ExoPlayer in exactly one place", 1),
    # `Service.onTaskRemoved` is invoked by the system and by nothing else, so the rule it applies
    # was hoisted out of it. These two probes are why: both halves fail in opposite directions and
    # a policy that lost either one is silently wrong on a device nobody is watching.
    ("media/task-removal-stops-while-playing", TASK_REMOVAL,
     "    playWhenReady != true || (mediaItemCount ?: 0) == 0",
     "    true",
     "music that is playing survives the user tidying their recents list", 1),
    ("media/task-removal-keeps-an-empty-queue-alive", TASK_REMOVAL,
     "    playWhenReady != true || (mediaItemCount ?: 0) == 0",
     "    playWhenReady != true",
     # 2, measured: the same mutation also reddens `no session at all is the clearest reason to
     # stop`, because with `mediaItemCount = null` and `playWhenReady = true` the surviving half of
     # the condition answers "keep running" for a service that has no player.
     "an empty queue stops the service even when the player is ready to play", 2),
    # A format the server transcodes on the fly has no `Content-Length`, so `player.duration` is
    # `C.TIME_UNSET` for the whole track and the metadata is the only source that knows. Dropping
    # the fallback shows every Opus track as unknown length on the lock screen, in Auto, in Wear
    # and in the seek bar -- and moves no other assertion in this project.
    ("media/duration-metadata-ignored", PLAYBACK_STATE,
     "      (playerDurationMs ?: metadataDurationMs ?: 0L).coerceAtLeast(0L)",
     "      (playerDurationMs ?: 0L).coerceAtLeast(0L)",
     "the metadata's duration is used when the extractor had none", 1),
    # The other direction: the metadata is what the *server* said about the file, the player is
    # what the extractor measured of the bytes actually playing. Preferring the wrong one is a
    # seek bar that disagrees with the audio.
    ("media/duration-metadata-wins", PLAYBACK_STATE,
     "      (playerDurationMs ?: metadataDurationMs ?: 0L).coerceAtLeast(0L)",
     "      (metadataDurationMs ?: playerDurationMs ?: 0L).coerceAtLeast(0L)",
     # 2, measured: it also reddens `an unknown duration is zero, never a negative sentinel`,
     # whose `playerDurationMs = -1L` case exists precisely to pin which source is consulted first.
     "the player's own duration wins, because it measured what is playing", 2),
    # `NOTHING_PLAYING` is what four downstream tasks render before anything is loaded. A `true`
    # here is an enabled "next" button with no queue behind it, and it moves no branch anywhere.
    ("media/nothing-playing-has-next", PLAYBACK_STATE,
     "      hasNext = false,\n      hasPrevious = false,",
     "      hasNext = true,\n      hasPrevious = false,",
     "nothing playing can step neither forward nor back", 1),
    # ---- Plan 6 Task 2: SSDP discovery, the device description, Sonos's embedded renderer -----
    # Every count below was measured by applying the mutation alone against the committed tree and
    # reading the result XML; see task-2-report.md for the transcripts. No entry here needs a
    # LATER_PROBE_FILES line: `revert()` already checks out the whole `core/cast` directory, which
    # is exactly the case that comment anticipated.
    #
    # These six defects share a symptom, and it is the worst one this project has: **the speaker is
    # simply not in the picker**, with no error, no log line and nothing to chase. Four of them
    # would have shipped green -- the module was at 100% of its *own* tests before the branch
    # report was read.
    ("discovery/man-unquoted", DISCOVERY_SSDP,
     'append("MAN: \\"ssdp:discover\\"")', 'append("MAN: ssdp:discover")',
     # The quotes are in the protocol. A device that receives MAN unquoted is entitled to ignore
     # the search entirely -- and many answer anyway, so this passes on the tester's network and
     # fails on the user's. That is why it is asserted as an exact datagram and not with contains().
     "a multicast search is the exact datagram the protocol specifies", 3),
    ("discovery/udn-is-whole-usn", DISCOVERY_SSDP,
     'val udn: String get() = usn.substringBefore("::")', 'val udn: String get() = usn',
     # A device answers once per matching search target, so one Sonos answers twice with two USNs
     # and one LOCATION. Without the split it is two picker entries called "Kuche".
     "the udn is the uuid half of the usn, which is what deduplicates a device", 5),
    ("discovery/announcement-not-address-checked", DISCOVERY_SSDP,
     "    if (!LocalNetworkOnly.isLocal(address)) return null\n", "",
     # SSDP has no authentication of any kind: anything that can send a UDP datagram chooses what
     # URL this app fetches next. Task 1's rule has to apply to what a device *claims*, not only to
     # what the app dials.
     "a reply whose location is not a local address is discarded", 1),
    ("discovery/truncated-datagram-accepted", DISCOVERY_TRANSPORT,
     "        if (packet.length == buffer.size) continue\n", "",
     # recvfrom truncates silently, and the datagram parser is deliberately tolerant of a block
     # that ends without its blank line -- so a LOCATION clipped at the buffer boundary parses as a
     # real, shorter URL. The socket readers reject a truncated head; a datagram cannot.
     "a reply too big for the receive buffer is dropped rather than parsed as a short one", 1),
    ("discovery/no-devicelist-recursion", DISCOVERY_DESC,
     '      embedded = childElement(element, "deviceList")\n'
     "        ?.let { list ->\n"
     "          childElements(list, \"device\").map { parseDevice(it, base, descriptionUrl, depth + 1) }\n"
     "        }\n"
     "        .orEmpty(),\n",
     "      embedded = emptyList(),\n",
     # THE Sonos defect. A Sonos root device is a ZonePlayer; the MediaRenderer carrying AVTransport
     # is nested inside its deviceList beside a MediaServer. A parser that reads
     # root/device/serviceList and stops decides a Sonos is not a renderer, and the headline user
     # requirement is silently absent.
     "a cast device is built from the sonos root and knows it is a sonos", 8),
    ("discovery/anything-is-castable", DISCOVERY_DEVICE,
     "        }\n        ?: return null\n",
     '        }\n        ?: (root to UpnpService("", "", descriptionUrl, null))\n',
     # The other direction: a NAS, a router's UPnP IGD and Sonos's own MediaServer all answer SSDP
     # and none of them can be cast to. Letting one through fails at SetAVTransportURI with UPnP
     # error 401, long after the user chose it.
     "a device with no AVTransport anywhere is not a cast device", 13),
    ("discovery/unsorted-picker", DISCOVERY_DIR,
     "val devices = (found + recovered).sortedWith(BY_NAME_THEN_UDN)",
     "val devices = (found + recovered)",
     # Arrival order is a property of the network, not of the app. A picker whose entries move
     # between openings is one nobody can build a habit with -- and the four-device test passes by
     # luck whenever the fake answers in name order, which is why the rename test exists.
     "the order is by name and not by arrival, proved by renaming one device", 2),
    ("discovery/no-dedup", DISCOVERY_DIR, "      .distinctBy { it.udn }\n", "",
     "four devices on the network become two picker entries, in name order", 1),
    ("discovery/forget-the-missing", DISCOVERY_DIR,
     "remembered.remember(devices.map { it.remembered() } + stillMissing)",
     "remembered.remember(devices.map { it.remembered() })",
     # The store exists so a speaker can be found again when multicast cannot reach it. Writing
     # back only the devices that answered deletes that fallback on exactly the run it is for.
     "a remembered device that did not answer is still remembered, so the next run can still name it", 1),
    ("discovery/unicast-anywhere", DISCOVERY_DIR,
     "    if (!LocalNetworkOnly.isLocal(address)) return null\n", "",
     # The remembered URL has been through disk since it was announced. `LocalNetworkOnly` guards
     # the fetch inside CastHttpClient, but the unicast M-SEARCH is a datagram this class sends on
     # its own account and nothing else would stop it leaving for the internet.
     "a remembered url off the local network is never dialled by the unicast fallback", 1),
    ("discovery/any-status-is-a-description", DISCOVERY_FETCH,
     "      ?.takeIf { it.code == HTTP_OK }\n", "",
     # A 404 body parses as "not XML" rather than as "no such device", so the message a user or a
     # maintainer eventually sees is about XML instead of about a device that has moved.
     "a 404 is not a description", 1),

    # ---- Plan 3 Task 3, fix round: the one string this module is allowed to say about a URL ----
    # `MissingCacheKeyException` is thrown inside `CacheDataSource.open`, which runs inside
    # `Loader$LoadTask.run` -- and that method logs it, then wraps it into an `ExoPlaybackException`
    # that `ExoPlayerImplInternal` logs again. A Subsonic stream URL carries `u`, `s` and
    # `t = md5(password + salt)`, and Navidrome tracks no salt nonce, so the triple replays forever:
    # a URL in that message is a password equivalent in logcat and in every bug report. The message
    # used to be `dataSpec.uri.toString()`.
    #
    # Both probes are on the JVM tier, and that is the whole reason `trackIdIn` takes and returns a
    # `String`: the reduction of a credential-bearing URL to a safe diagnostic names no Android and
    # no Media3 type. Same split as `StreamRetryPolicy` against its Media3 adapter. What is NOT
    # here and cannot be is the throw site itself -- `dataSpec.key ?: throw ..` needs a `DataSpec`,
    # so mutating it is a device matter and is recorded in task-3-fix-report.md, the same way
    # `LiveNavidromeTest`'s test-side probes are.
    ("media/cache-key-error-leaks-url", TRACK_ID_KEY,
     "      ?: UNKNOWN_TRACK", "      ?: uri",
     # The natural wrong fix -- "if the id cannot be found, at least print the URL" -- and there is
     # no shape of URL for which it is the right answer.
     # 2: the no-query test asserts the same placeholder over two more URLs.
     "a url with no id says so rather than falling back to the url", 2),
    ("media/cache-key-id-substring-match", TRACK_ID_KEY,
     'it.startsWith("$ID_PARAMETER=")', "it.contains(ID_PARAMETER)",
     # A parameter is matched on its whole name, not on containing one: `contains` also matches
     # `xid=`, `mediaid=` and any token that happens to hold those two characters, and every one of
     # those hands back a value that is not a track id -- which is how a credential gets into the
     # message by a second route.
     "a parameter that merely ends in id is not the id", 1),

    # ---- Plan 5 Task 2: the browse tree ------------------------------------------------------
    # The first three are the mutations that task's brief named by hand. The last two are what a
    # by-hand sweep of the finished suite actually found -- both were mutations that SURVIVED the
    # whole green suite until a fixture or an implementation was changed, which is exactly the
    # class of defect this file exists to remember.
    #
    # The plan was warned by name about "an isAutomotiveController test where both branches return
    # the same tree, so the branch is untested". A `when (surface)` whose arms are equal has 100%
    # branch coverage and asserts nothing, so this collapses the branch outright: every surface
    # gets the PHONE root. Reddens three of the root tests plus the tree-wide credential test,
    # which counts its nodes.
    ("browse/root-ignores-surface", BROWSE_TREE,
     """      if (hasMusic) {
        add(folder(BrowseId.Albums, ALBUMS_TITLE, BrowseMediaType.FOLDER_ALBUMS, surface.browsableStyle))
        // A watch skips Artists: it is a level of indirection that costs two more crown scrolls to
        // reach exactly the album the Albums tab already lists.
        if (surface != BrowseSurface.WATCH) {
          add(folder(BrowseId.Artists, ARTISTS_TITLE, BrowseMediaType.FOLDER_ARTISTS, BrowseStyle.LIST))
        }
        // A library picker is unbounded and is one more level of depth, which is exactly what a
        // driver must not be handed -- and it would push the car root past its four tabs.
        if (surface == BrowseSurface.PHONE) {
          add(folder(BrowseId.Libraries, LIBRARIES_TITLE, BrowseMediaType.FOLDER_MIXED, BrowseStyle.LIST))
        }
      }""",
     """      if (hasMusic) {
        add(folder(BrowseId.Albums, ALBUMS_TITLE, BrowseMediaType.FOLDER_ALBUMS, BrowseSurface.PHONE.browsableStyle))
        add(folder(BrowseId.Artists, ARTISTS_TITLE, BrowseMediaType.FOLDER_ARTISTS, BrowseStyle.LIST))
        add(folder(BrowseId.Libraries, LIBRARIES_TITLE, BrowseMediaType.FOLDER_MIXED, BrowseStyle.LIST))
      }""",
     "the three surfaces produce three different roots", 4),

    # Spec section 1: "Hitting shuffle must not pull chapter 14 of a novel into a music session."
    # On a car surface there is no UI to disable, so the rule is expressed as the absence of a row
    # -- which means the only thing that can enforce it is a test that asserts the absence.
    ("browse/shuffle-for-every-library", BROWSE_TREE,
     """    if (library.role == LibraryRole.MUSIC) {
      listOf(shuffleNode(library)) + albums.map(::albumNode)
    } else {
      albums.map(::albumNode)
    }""",
     "    listOf(shuffleNode(library)) + albums.map(::albumNode)",
     "an audiobook library gets no shuffle node and a music library does", 1),

    # One field fixed to a constant -- the defect class N3-1 found across 20 mapped DTO fields.
    # Reddens the album field-by-field test and the albums tab's own artwork assertion, and
    # nothing else: that precision is the point of asserting field by field rather than by
    # comparing whole objects built from the same fixture.
    ("browse/album-artwork-constant", BROWSE_TREE,
     "    mediaType = BrowseMediaType.ALBUM,\n    artworkId = album.coverArtId,",
     '    mediaType = BrowseMediaType.ALBUM,\n    artworkId = "cov-a",',
     "an album node carries every field of its album", 2),

    # FOUND BY THE SWEEP, NOT BY THE BRIEF. This mutation survived the entire green suite: both
    # library-order fixtures had names whose alphabetical order happened to coincide with their id
    # order, so no test could tell `sortedBy(id)` from `sortedBy(name)`. Fixed by renaming the
    # fixtures so supplied order, name order and id order are three different orders. The probe
    # exists because the defect is invisible to coverage -- both sorts are one fully-covered line.
    ("browse/shuffle-sorted-by-name", BROWSE_TREE,
     "    musicLibraries.sortedBy(MusicLibrary::id).map(::shuffleNode) + albums.map(::albumNode)",
     "    musicLibraries.sortedBy(MusicLibrary::name).map(::shuffleNode) + albums.map(::albumNode)",
     "the albums node puts one shuffle per music library first, in library id order", 1),

    # ALSO FOUND BY THE SWEEP. `remainingLabel` opened with `max(0L, remainingMs)`, and deleting
    # that clamp changed no output for any input at all, `Long.MIN_VALUE` included -- the first
    # band's test is `<`, so every negative satisfies it before any division runs. The clamp was
    # deleted rather than covered, and what actually decides the negative case is the ORDER of the
    # bands. This probe hoists the second band above the first, which is the real defect the
    # negative sample now guards.
    ("browse/remaining-band-order", BROWSE_TEXT,
     '      remainingMs < MINUTE_MS -> "under a minute left"\n      hours == 0L -> "$minutes min left"',
     '      hours == 0L -> "$minutes min left"\n      remainingMs < MINUTE_MS -> "under a minute left"',
     "a negative remaining time is treated as none rather than rendered", 2),
    # ---- Plan 3 Task 9: the player. -----------------------------------------------------------
    # Everything below is a value a user meets on the first tap, and every one of them is on the
    # JVM tier by construction: the state mapping is a pure top-level function, `PlayerViewModel`
    # is built over a `PlaybackControls` seam, and `launchQueue` was split out of
    # `PlaybackLauncher` precisely so the queue decision does not need a device.

    # The seek bar's thumb following the player instead of the finger. Obvious on a device --
    # the thumb springs back every 250ms and the bar cannot be used -- and invisible in a
    # screenshot, which is exactly the kind of defect a probe list is for.
    ("player/scrub-position-ignored", PLAYER_STATE,
     "displayPositionMs = displayPosition(scrubPositionMs ?: playback.positionMs, playback.durationMs),",
     "displayPositionMs = displayPosition(playback.positionMs, playback.durationMs),",
     "the displayed position is the scrub position while scrubbing", 5),
    # ...and the flag that goes with it, which the screen reads to decide whether a drag is in
    # progress at all.
    ("player/is-scrubbing-constant", PLAYER_STATE,
     "isScrubbing = scrubPositionMs != null,", "isScrubbing = false,",
     "the displayed position is the scrub position while scrubbing", 3),
    # The discriminator between "render this track" and "render nothing". Reading `title` instead
    # of `mediaId` is the plausible slip -- both are null in NOTHING_PLAYING, so it passes every
    # other case in the suite -- and it empties the player for any track whose server sent no
    # title.
    ("player/content-discriminator", PLAYER_STATE,
     "if (playback.mediaId == null) {", "if (playback.title == null) {",
     "the media id alone decides, not the metadata around it", 1),
    # Found by looking at the screen on `muplay37`: an MP3's duration is a container estimate and
    # the player's position runs past it, so the last moment of every seeded track rendered
    # "0:05 / 0:04". Each number was individually right; nothing was watching the pair.
    ("player/position-clamp", PLAYER_STATE,
     "if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)",
     "positionMs.coerceAtLeast(0L)",
     "the displayed position never runs past the end of the track", 1),
    # `Player.getDuration()` is a large negative until the extractor has read the container.
    # Without the clamp that renders as "-9223372036854:775" on a lock screen.
    ("player/duration-clamp", PLAYER_STATE,
     "val totalSeconds = (millis.coerceAtLeast(0L)) / 1000",
     "val totalSeconds = millis / 1000",
     "an unknown duration formats as a placeholder rather than a negative time", 1),
    # The play/pause button doing the opposite of what it says.
    ("player/playpause-inverted", PLAYER_VM,
     "if (controls.isPlaying()) controls.pause() else controls.play()",
     "if (controls.isPlaying()) controls.play() else controls.pause()",
     "play pause pauses a playing player", 3),
    # A copy-paste swap between two one-line delegating methods: both run, both measure fully
    # covered, and the transport buttons walk the queue backwards. This project records
    # "argument passthrough on a delegating method" as its own defect class; this is its sibling.
    ("player/next-is-previous", PLAYER_VM,
     "viewModelScope.launch { controls.next() }",
     "viewModelScope.launch { controls.previous() }",
     "next and previous each ask for their own direction", 1),
    # The seek target coming from somewhere other than the finger.
    ("player/seek-target-constant", PLAYER_VM,
     "controls.seekTo(target)", "controls.seekTo(0L)",
     "committing a scrub seeks to where the finger stopped", 1),
    # Without the connect, the screen renders "Nothing playing" forever while audio is audibly
    # playing -- the state flow never starts.
    ("player/never-connects", PLAYER_VM,
     "viewModelScope.launch { controls.connect() }", "Unit",
     "constructing the view model connects to the session", 1),
    # Which item the queue starts on. Tapping track 7 and hearing track 1.
    ("player/launch-start-index", PLAYBACK_LAUNCHER,
     "PlaybackQueue.of(songs, startIndex.coerceIn(songs.indices))",
     "PlaybackQueue.of(songs, 0)",
     "the start index is the one the caller asked for", 2),
    # The same value, one layer up, at each of the two screens that supply it. Both are here
    # rather than one representative: they are separate code paths through separate view models,
    # and the album one is the one a user meets on every album screen.
    ("player/album-play-index", ALBUM_VM,
     "viewModelScope.launch { source.play(content.songs, startIndex) }",
     "viewModelScope.launch { source.play(content.songs, 0) }",
     "playing a track launches this album's songs from that track", 2),
    ("player/shuffle-play-index", LIBRARY_VM,
     "viewModelScope.launch { source.play(content.shuffled, startIndex) }",
     "viewModelScope.launch { source.play(content.shuffled, 0) }",
     "playing a shuffled row launches the shuffle result from that row", 1),
    # `Service.onTaskRemoved` is invoked by the system and by nothing else, so the rule it applies
    # was hoisted out of it. These two probes are why: both halves fail in opposite directions and
    # a policy that lost either one is silently wrong on a device nobody is watching.
    ("media/task-removal-stops-while-playing", TASK_REMOVAL,
     "    playWhenReady != true || (mediaItemCount ?: 0) == 0",
     "    true",
     "music that is playing survives the user tidying their recents list", 1),
    ("media/task-removal-keeps-an-empty-queue-alive", TASK_REMOVAL,
     "    playWhenReady != true || (mediaItemCount ?: 0) == 0",
     "    playWhenReady != true",
     # 2, measured: the same mutation also reddens `no session at all is the clearest reason to
     # stop`, because with `mediaItemCount = null` and `playWhenReady = true` the surviving half of
     # the condition answers "keep running" for a service that has no player.
     "an empty queue stops the service even when the player is ready to play", 2),
    # A format the server transcodes on the fly has no `Content-Length`, so `player.duration` is
    # `C.TIME_UNSET` for the whole track and the metadata is the only source that knows. Dropping
    # the fallback shows every Opus track as unknown length on the lock screen, in Auto, in Wear
    # and in the seek bar -- and moves no other assertion in this project.
    ("media/duration-metadata-ignored", PLAYBACK_STATE,
     "      (playerDurationMs ?: metadataDurationMs ?: 0L).coerceAtLeast(0L)",
     "      (playerDurationMs ?: 0L).coerceAtLeast(0L)",
     "the metadata's duration is used when the extractor had none", 1),
    # The other direction: the metadata is what the *server* said about the file, the player is
    # what the extractor measured of the bytes actually playing. Preferring the wrong one is a
    # seek bar that disagrees with the audio.
    ("media/duration-metadata-wins", PLAYBACK_STATE,
     "      (playerDurationMs ?: metadataDurationMs ?: 0L).coerceAtLeast(0L)",
     "      (metadataDurationMs ?: playerDurationMs ?: 0L).coerceAtLeast(0L)",
     # 2, measured: it also reddens `an unknown duration is zero, never a negative sentinel`,
     # whose `playerDurationMs = -1L` case exists precisely to pin which source is consulted first.
     "the player's own duration wins, because it measured what is playing", 2),
    # `NOTHING_PLAYING` is what four downstream tasks render before anything is loaded. A `true`
    # here is an enabled "next" button with no queue behind it, and it moves no branch anywhere.
    ("media/nothing-playing-has-next", PLAYBACK_STATE,
     "      hasNext = false,\n      hasPrevious = false,",
     "      hasNext = true,\n      hasPrevious = false,",
     "nothing playing can step neither forward nor back", 1),

    # ---- Plan 6 Task 3: the SOAP layer ------------------------------------------------------
    # Counts here are LARGE for three of the seven, and that is the finding rather than noise: the
    # SOAPACTION quotes, the argument order and the fake's strictness are each observed at three
    # layers (a unit assertion on the rendered value, an assertion on the bytes the fake recorded,
    # and the fake's own refusal turning the request into a 401/402/714). A mutation to any of them
    # reddens all three. Measured per probe with `:core:cast:test` and listed test by test in
    # task-3-report.md; if one of these reports MISSED but its named test IS in the failing list,
    # re-measure the count as the note on `expected failures` above describes.

    # `.replace("<", "&lt;")` before `.replace("&", "&amp;")` rewrites the ampersand the first
    # replacement introduced and yields `&amp;lt;DIDL-Lite` -- and it is RIGHT for every input that
    # did not already contain an entity, which is most inputs, which is why it survives review.
    ("soap/escape-ampersand-last", SOAP_XML,
     '    .replace("&", "&amp;") // first, always\n'
     '    .replace("<", "&lt;")\n'
     '    .replace(">", "&gt;")\n'
     '    .replace("\\"", "&quot;")\n'
     '    .replace("\'", "&apos;")',
     '    .replace("<", "&lt;")\n'
     '    .replace(">", "&gt;")\n'
     '    .replace("\\"", "&quot;")\n'
     '    .replace("\'", "&apos;")\n'
     '    .replace("&", "&amp;")',
     # 22 as of Task 3's fix round, up from 5: `SoapEnvelope.render` escapes argument values now,
     # so this ordering defect reaches every test that reads a rendered envelope or sends one to
     # the fake -- which is the point of moving escaping to one layer, seen from the probe side.
     "the ampersand is replaced first, so an existing entity is escaped once and not twice", 22),

    # The quotes are part of the SOAPACTION header VALUE. Sent unquoted, some renderers accept it
    # and Sonos answers 401 -- the worst possible distribution, because it works on the developer's
    # speaker. 25 reds: every `FakeRendererStrictnessTest` and `SoapClientTest` case that sends a
    # well-formed request through `quoted()` now gets a 401 instead.
    ("soap/soapaction-unquoted", SOAP_ENVELOPE,
     '    "\\"${SoapNames.requireServiceType(serviceType)}#${SoapNames.requireAction(action)}\\""',
     '    "${SoapNames.requireServiceType(serviceType)}#${SoapNames.requireAction(action)}"',
     # 28, up from 25: Task 3's fix round added three tests that send a quoted SOAPACTION.
     "the soapaction header value is quoted", 28),

    # UPnP argument lists are ORDERED by the service description and implementations parse
    # positionally. Sorting them is the mutation because it is the plausible one: a `Map` a caller
    # sorted, or a tidy-up that "made the output deterministic", produces exactly this.
    ("soap/arguments-sorted", SOAP_ENVELOPE,
     "      arguments.forEach { (name, value) ->",
     "      arguments.sortedBy { it.name }.forEach { (name, value) ->",
     # 18, up from 15, for the same reason.
     "arguments appear in the order they were given, and reordering them changes the bytes", 18),

    # A UPnP error is HTTP 500 WITH A BODY. Checking the status first turns "Sonos said 714, illegal
    # MIME type" into "HTTP 500" and loses the only thing the caller can act on. Two reds, which is
    # the precision this probe is for -- the ordering matters for faults and for nothing else.
    ("soap/fault-checked-after-status", SOAP_CLIENT,
     "    SoapEnvelope.parseFault(response.bodyText())?.let { throw UpnpErrorException(action, it) }\n"
     "\n"
     "    if (response.code != HttpURLConnection.HTTP_OK) {\n"
     "      throw SoapTransportException(action, response.code)\n"
     "    }",
     "    if (response.code != HttpURLConnection.HTTP_OK) {\n"
     "      throw SoapTransportException(action, response.code)\n"
     "    }\n"
     "    SoapEnvelope.parseFault(response.bodyText())?.let { throw UpnpErrorException(action, it) }",
     "a refused action throws with the device's own error code", 2),

    # THE SECURITY PROBE. `SOAPACTION` is built from the service type parsed out of the
    # device-description XML the renderer itself served -- attacker-controlled input, and a review
    # of Task 1 put a real extra header on the wire through it against a live ServerSocket. The
    # allowlist here is what stops it; `CastHttpClient`'s CR/LF refusal underneath is the backstop,
    # and it refuses with `IllegalArgumentException`, so with this mutation the hostile description
    # reaches the caller as a CRASH rather than as a catchable `IOException`. The named test asserts
    # exactly that difference.
    ("soap/service-type-unchecked", SOAP_NAMES,
     '  private val SERVICE_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9._:+~-]{0,${MAX_SERVICE_TYPE_LENGTH - 1}}")',
     '  private val SERVICE_TYPE = Regex("[\\\\s\\\\S]{0,${MAX_SERVICE_TYPE_LENGTH}}")',
     "a hostile service type parsed from a device description never reaches the wire", 4),

    # The third peer-controlled input, and the one most easily forgotten because it does not look
    # like text: `<controlURL>https://attacker.example/x</controlURL>` resolves to an absolute URL
    # that `CastHttpClient` refuses with `IllegalArgumentException`. One red, and it is the whole
    # point of the check.
    ("soap/control-url-unchecked", SOAP_CLIENT,
     "    SoapNames.requireControlUrl(controlUrl)\n", "",
     "a control url that is not plain http is refused as an IOException, not a crash", 1),

    # THE MOST IMPORTANT PROBE IN THIS TASK, and the reason it is here despite this file's
    # production-code-only SCOPE note: `FakeRenderer` is the SUBJECT of
    # `FakeRendererStrictnessTest`, not one of its assertions, so mutating it is a subject mutation
    # of exactly the kind every other probe here is -- unlike `LiveNavidromeTest`'s scoping probes,
    # which change what a test SENDS in order to prove its assertion discriminates.
    #
    # Every cast test in Plan 6 is only as good as this renderer's willingness to say no. A fake
    # that accepts everything executes no rejection path, leaves the client's error handling
    # unexercised, and produces exactly the same green suite as a strict one right up until real
    # hardware disagrees. With all seven knobs relaxed, 10 tests go red -- eight of them
    # `FakeRendererStrictnessTest`'s own rejections and two of them `SoapClientTest`'s, which is
    # the proof the fake's strictness is load-bearing one layer up as well.
    ("soap/fake-accepts-everything", SOAP_FAKE,
     "  data class Strictness(\n"
     "    /** SOAP 1.1 quotes the `SOAPACTION` value. Sonos enforces it. Violation: 401. */\n"
     "    val requireQuotedSoapAction: Boolean = true,\n"
     "    /** A control body is XML and a device parses it. See [RecordedSoap.arguments]. Violation: 401. */\n"
     "    val requireWellFormedBody: Boolean = true,\n"
     "    /** UPnP argument lists are ordered by the service description. Violation: 402. */\n"
     "    val requireArgumentOrder: Boolean = true,\n"
     '    /** Spec section 6: *"DIDL-Lite mandatory"*. Violation: 714. */\n'
     "    val requireNonEmptyMetadata: Boolean = true,\n"
     "    /** Spec section 6: Sonos infers MIME from the URL. Violation: 714. */\n"
     "    val requireUrlExtension: Boolean = true,\n"
     "    /** Only `InstanceID` 0 exists on a single-zone renderer. Violation: 718. */\n"
     "    val requireInstanceIdZero: Boolean = true,\n"
     "    /** What `A_ARG_TYPE_SeekMode` allows. Anything else: 710. */\n"
     '    val supportedSeekModes: List<String> = listOf("REL_TIME"),\n'
     '    /** Spec section 4: *"Never Opus. Sonos cannot decode it."* Violation: 714. */\n'
     '    val rejectedMimeTypes: Set<String> = setOf("audio/ogg", "audio/opus", "audio/webm"),\n'
     "  )",
     "  data class Strictness(\n"
     "    val requireQuotedSoapAction: Boolean = false,\n"
     "    val requireWellFormedBody: Boolean = false,\n"
     "    val requireArgumentOrder: Boolean = false,\n"
     "    val requireNonEmptyMetadata: Boolean = false,\n"
     "    val requireUrlExtension: Boolean = false,\n"
     "    val requireInstanceIdZero: Boolean = false,\n"
     '    val supportedSeekModes: List<String> = listOf("REL_TIME", "ABS_TIME"),\n'
     "    val rejectedMimeTypes: Set<String> = emptySet(),\n"
     "  )",
     # 12, up from 10: an eighth knob (`requireWellFormedBody`) and the two tests that drive it.
     "an unquoted soapaction is rejected with 401", 12),

    # ---- Plan 6 Task 3, fix round: the two HIGH findings of its security review --------------
    #
    # THE FIRST ONE, AND THE REASON THE SECOND ONE MATTERS. `render` validated the service type,
    # the action and every argument NAME and then interpolated argument VALUES with no escaping at
    # all, while its KDoc called itself "total: well-formed XML or throws". A Navidrome stream URL
    # -- `/rest/stream?u=x&t=y&s=z`, the URL this app builds for every single track -- produced a
    # document that fails at *"The reference to entity `t` must end with ';'"*, so `parseResponse`
    # could not read back what `render` had just written and no device could read it either.
    ("soap/argument-value-unescaped", SOAP_ENVELOPE,
     "        append(XmlText.escape(value))",
     "        append(value)",
     # 17, MEASURED. High, and honestly so: unescaped values break every rendered envelope in the
     # module at once, which is exactly the blast radius the finding described.
     "a navidrome stream url reaches the device intact, ampersands and all", 17),

    # THE MIRROR IMAGE, re-pointed here from `didl/metadata-escaped-twice` when
    # `DidlLite.renderEscaped` was deleted. Escaping is framing and framing now happens in exactly
    # one place, so this is where doing it twice has to be caught: `&amp;lt;DIDL-Lite` inside
    # `CurrentURIMetaData` is a device that shows the track as unknown with no error anywhere, and
    # `FakeRenderer`'s `requireNonEmptyMetadata` answers 714 to it.
    ("soap/argument-escaped-twice", SOAP_ENVELOPE,
     "        append(XmlText.escape(value))",
     "        append(XmlText.escape(XmlText.escape(value)))",
     # 16, MEASURED, for the mirror-image reason.
     "the metadata argument carries the document escaped exactly once", 16),

    # THE SECOND HIGH FINDING, AND THE ONE THAT MADE THE FIRST INVISIBLE. `RecordedSoap.arguments`
    # parsed request bodies with `<(\w+)>(.*?)</\1>`, and a regex cannot tell well-formed XML from
    # malformed XML -- so a fake advertised as "strict by default", with its own strictness test
    # class and its own probe, accepted a document no real renderer could have read, and 311 green
    # tests said nothing. It is a real parser now; this probe is the memory of what happens when
    # what it could not read is treated as a request anyway.
    ("soap/fake-accepts-unparseable-body", SOAP_FAKE,
     "    val arguments = recorded.arguments\n"
     "      ?: if (strictness.requireWellFormedBody) return fault(UpnpError.INVALID_ACTION) else emptyList()",
     "    val arguments = recorded.arguments ?: emptyList()",
     "a body that is not well-formed xml is rejected with 401", 2),

    # THE MEDIUM FROM THE SAME REVIEW. `parseResponse` answered `emptyMap()` both for "this action
    # has no out arguments" and for "there is no answer to this action in this body". Task 5 reads
    # `RelTime` out of a `GetPositionInfo`; given the second dressed up as the first it reads
    # nothing and reports a position of zero for a device that never answered.
    ("soap/unreadable-response-is-empty", SOAP_CLIENT,
     "    SoapEnvelope.parseResponse(action, response.bodyText())\n"
     "      ?: throw SoapTransportException(action, response.code)",
     "    SoapEnvelope.parseResponse(action, response.bodyText()).orEmpty()",
     "a 200 with no response element is a transport failure, while an empty response is a success", 1),

    # ---- Plan 5 Task 3: which browse tree a connected controller gets ------------------------
    #
    # `BrowseSurfaces.of` is a four-argument decision table, and a decision table's characteristic
    # defect is not a wrong answer -- it is two arms that happen to agree on the fixture somebody
    # chose. The four probes below are the four arms that must not agree, each one measured (the
    # failure counts here were read off a run, not predicted).
    #
    # What is NOT here, and cannot be: `DefaultSurfaceResolver`'s `connectionHints.getString(
    # BrowseSurfaces.HINT_KEY)`. Replacing that constant with the literal `"surface"` -- a key that
    # is wrong but plausible, and that no JVM test can see, because every JVM test passes
    # `hintSurface` in directly -- is caught only by `DefaultSurfaceResolverTest`, which needs a
    # real `Bundle` and therefore a device. That is this file's own INSTRUMENTED TIER limit, and
    # the falsification is recorded by hand in task-3-report.md, the same way `LiveNavidromeTest`'s
    # are. It is not hypothetical: that exact mutation was found live in an uncommitted tree, left
    # by a probe run whose process died before its `finally` could revert.

    # Precedence 1 over 2. Media3 owns the real answer to "is this a car"; our package list is a
    # backstop for hosts it does not know, never an override of one it does. Demoting the predicate
    # below the package arms is silent for every car (both arms say CAR) and wrong for exactly one
    # caller: a *watch* package that Media3 has told us is driving a car surface.
    ("browse/surface-predicate-demoted", BROWSE_SURFACE,
     "    isCarController -> BrowseSurface.CAR\n"
     "    packageName in CAR_PACKAGES -> BrowseSurface.CAR\n"
     "    packageName in WATCH_PACKAGES -> BrowseSurface.WATCH\n",
     "    packageName in CAR_PACKAGES -> BrowseSurface.CAR\n"
     "    packageName in WATCH_PACKAGES -> BrowseSurface.WATCH\n"
     "    isCarController -> BrowseSurface.CAR\n",
     "the media3 predicate wins over every package and every hint", 1),

    # THE SECURITY PROBE OF THIS TASK. The connection hint is a *self-declaration*, honoured only
    # from our own package -- `:wear`'s browser connects under this app's own application id, which
    # is the only reason a hint can identify it at all. Drop the `packageName == ownPackageName`
    # guard and the hint becomes a *request* any installed app can make, which is both a different
    # security posture and a test that proves nothing. Four reds, and the fourth matters most:
    # `each of the four arguments changes the answer on its own` exists so that `ownPackageName` is
    # a live argument rather than one the function could ignore.
    #
    # Replaces the whole tail of the `when`, not just the guard line: dropping the guard alone
    # leaves the original `else -> BrowseSurface.PHONE` behind it, which is two `else` arms in one
    # `when` and so a COMPILE error rather than a mutation. That version of this probe aborted the
    # whole filtered list -- and reported it as `run_suite(): no test results were written for
    # [every module]`, which reads exactly like the untouched-module false alarm master documents.
    # It is not that: when the mutated module is in the list, suspect the mutation does not compile.
    ("browse/surface-hint-from-anyone", BROWSE_SURFACE,
     "    packageName == ownPackageName -> when (hintSurface) {\n"
     "      HINT_CAR -> BrowseSurface.CAR\n"
     "      HINT_WATCH -> BrowseSurface.WATCH\n"
     "      else -> BrowseSurface.PHONE\n"
     "    }\n"
     "    else -> BrowseSurface.PHONE\n",
     "    else -> when (hintSurface) {\n"
     "      HINT_CAR -> BrowseSurface.CAR\n"
     "      HINT_WATCH -> BrowseSurface.WATCH\n"
     "      else -> BrowseSurface.PHONE\n"
     "    }\n",
     "a hint is honoured from our own package and refused from any other", 4),

    # Exact match, not prefix. `com.google.android.projection.gearhead.evil` is installable, and a
    # `startsWith` hands it the car tree on the strength of a name it chose for itself.
    ("browse/surface-package-prefix-match", BROWSE_SURFACE,
     "    packageName in CAR_PACKAGES -> BrowseSurface.CAR",
     "    CAR_PACKAGES.any { packageName.startsWith(it) } -> BrowseSurface.CAR",
     "package matching is exact, not a prefix and not case-insensitive", 1),

    # The lists themselves. These are Google's package names; a silent edit to one is a silent
    # change to which tree a car gets, and nothing else in the suite would move.
    ("browse/surface-car-package-dropped", BROWSE_SURFACE,
     '    "com.android.car.carlauncher",\n', "",
     "every known car and watch package maps to its surface", 1),
    # ---- Plan 6 Task 2, fix round: the review's HIGH and MEDIUM findings ----------------------
    # Same `revert()` note as the Task 2 block above: `core/cast` is checked out wholesale, so none
    # of these needs a LATER_PROBE_FILES line. Every count was measured by applying the mutation
    # alone against the committed tree and reading the result XML.
    #
    # The seventh finding in that review, `RendererStore.encode` writing a record whose fields
    # shift when the UDN carries a tab, has NO probe here and cannot have one: `RendererStore`
    # needs a DataStore, so its only tier is `connectedDebugAndroidTest`, which this runner cannot
    # reach. Its falsification is by hand, in task-2-fix-report.md, the same way Plan 3 Task 3's
    # throw site is.
    ("discovery/location-host-resolved", DISCOVERY_SSDP,
     "    if (!isIpLiteral(host)) return null\n", "",
     # The denial of service. M-SEARCH is multicast, so every device on the segment learns this
     # phone's source port, and the reply socket is unconnected -- so one datagram naming a host
     # whose nameserver drops queries parks the read loop in `getByName` for a resolver timeout,
     # and N distinct labels cost N of them. The window then closes with every real speaker's reply
     # still unread. The named test uses `localhost` on purpose: it is the one name that resolves
     # with no network at all, so it cannot pass on this mutation the way an unresolvable one would.
     "a reply whose location names a host rather than an address is discarded without resolving it", 1),
    ("discovery/location-not-source-checked", DISCOVERY_SSDP,
     "    if (address != from) return null\n", "",
     # The other half: a device may announce its own address and nothing else. Without this, one
     # datagram from anywhere on the segment redirects the description fetch at a host of the
     # sender's choosing.
     "a reply that announces an address it did not come from is discarded", 1),
    ("discovery/search-ignores-cancellation", DISCOVERY_TRANSPORT,
     "        ensureActive()\n", "",
     # Nothing in the read loop suspends, so without this a user who opens the picker and goes
     # straight back leaves an IO thread and a bound socket working for the rest of the listen
     # window. The named test's window is twenty times its assertion, so it fails by a factor of
     # twenty rather than by a margin.
     "cancelling a search stops the read loop rather than pinning a thread for the whole window", 1),
    ("discovery/doctype-not-refused", DISCOVERY_DESC,
     "    rejectDoctype(xml, descriptionUrl)\n", "",
     # THE probe that did not exist, and the reason MEDIUM 1 of the review was a finding at all:
     # before the fix round this mutation reddened NOTHING. SAX's own refusal message contains the
     # word "DOCTYPE", so `withMessageContaining("DOCTYPE")` was satisfied by the parser feature
     # this guard exists to be independent of -- on Android that feature is expected to be refused
     # at `setFeature`, so there it is this guard or nothing. All three doctype tests now assert
     # this function's own sentence, which no parser says.
     "a description carrying a doctype is refused outright", 3),
    ("discovery/doctype-scanned-in-a-window", DISCOVERY_DESC,
     'if (xml.contains("<!DOCTYPE", ignoreCase = true))',
     'if (xml.take(4096).contains("<!DOCTYPE", ignoreCase = true))',
     # The 4 KiB blind spot itself, as the code actually had it. A comment is legal Misc in the
     # prolog, so a doctype can sit at index 5008 of a well-formed document that is five hundred
     # kilobytes short of the size guard.
     "a doctype hidden behind a five kilobyte comment is refused too", 1),
    ("discovery/unbounded-device-recursion", DISCOVERY_DESC,
     "    if (depth > MAX_DEVICE_DEPTH) {", "    if (false) {",
     # Ten thousand nested deviceList/device pairs is 420,030 characters -- under every other bound
     # here -- and the recursive walk answered with a StackOverflowError rather than a refusal.
     # `parse` is public API whose KDoc enumerates what it throws, and Task 3 tells Tasks 5, 8 and 9
     # that one `catch (e: IOException)` around a call is complete.
     "a description nested ten thousand deep is refused rather than overflowing the stack", 2),
    ("discovery/anonymous-device-is-a-device", DISCOVERY_DEVICE,
     "      if (root.udn.isEmpty()) return null\n", "",
     # A description with an AVTransport and no <UDN> used to yield a CastDevice whose udn is "",
     # and `RendererDirectory` deduplicates the picker with `distinctBy { it.udn }` -- so every
     # anonymous renderer on a network collapsed into one entry, and the store remembered that
     # empty identity for the fallback to re-fetch.
     "a renderer that declares no udn is not a cast device", 1),

    # ---- Plan 6 Task 2, fix round: the same two defects in the SOAP copy of the same guard ----
    # Found by the Task 3 review while this lane was fixing their siblings in `DeviceDescription`,
    # and taken here because they are the same defect in the same module -- two copies of one
    # security guard is exactly how both came to carry the same 4096-character window.
    ("soap/unbounded-fault-recursion", SOAP_ENVELOPE,
     "    if (depth > MAX_FAULT_DEPTH) return null", "    if (false) return null",
     # WORSE than its `DeviceDescription` sibling, because of where it is called from:
     # `SoapClient.invoke` runs `parseFault` on EVERY response, outside its try/catch and outside
     # the runCatching that guards the parse. ~56 KB of nested elements from an unauthenticated
     # device on the LAN used to answer with a StackOverflowError -- an Error, so the one
     # `catch (e: IOException)` that `SoapClient`'s KDoc promises Tasks 5, 8 and 9 is complete
     # misses it entirely and the coroutine dies. 2: the boundary test goes red as well.
     "a fault nested twenty thousand deep is a plain refusal rather than a stack overflow", 2),
    ("soap/doctype-scanned-in-a-window", SOAP_ENVELOPE,
     'internal fun declaresDoctype(xml: String): Boolean = xml.contains("<!DOCTYPE", ignoreCase = true)',
     'internal fun declaresDoctype(xml: String): Boolean = xml.take(4096).contains("<!DOCTYPE", ignoreCase = true)',
     # The window as the code actually had it. The named test asserts the PREDICATE and not
     # `parseFault(...) == null`, and that is what makes this probe possible at all: on the JVM the
     # `disallow-doctype-decl` feature refuses the document itself, so the end-to-end assertion is
     # green with the scan looking at four kilobytes, at everything, or at nothing whatsoever.
     "a doctype hidden behind a five kilobyte comment is still seen by the guard", 1),

    # ---- Plan 3 Task 6: spec section 5's one-line switch ------------------------------------
    # `audio/`, not `media/`, and that is a usability decision rather than a taxonomy one: there
    # are sixteen `media/` probes now, so `./ci/mutation-probes.sh media/` is a twelve-minute run
    # and the harness that drives this script caps a single call at ten minutes. A prefix per topic
    # is what the rest of this table already does (`retry/`, `queue/`, `resume/`, `pcm/`, `cast/`).
    #
    # NOTE ON SCOPE: everything else this task added is observed on the **instrumented** tier --
    # audio focus, becoming-noisy, wake mode, the per-song audiobook join in `QueueRepository`, and
    # `startIndex` -- and this runner is JVM-only. Worse than merely out of reach: `run_suite()`
    # clears the JVM result directories and Gradle then restores `:core:media:testDebugUnitTest`
    # FROM-CACHE, because androidTest sources are not inputs to it, so an androidTest probe reports
    # MISSED with *zero* failures and reads like a broken test rather than an unrunnable probe.
    # Those mutations were run by hand and their transcripts are in task-6-report.md, the same way
    # Task 3's and Task 7b's are.
    #
    # The switch itself is here because it was deliberately built to be: `contentTypeFor` takes an
    # `Int` and returns an `Int` precisely so the decision is gated by the fast tier.
    ("audio/content-type-always-speech", AUDIO_ATTRIBUTES,
     "    else -> C.AUDIO_CONTENT_TYPE_MUSIC",
     "    else -> C.AUDIO_CONTENT_TYPE_SPEECH",
     # A music player that declares everything to be speech ducks under a navigation prompt and is
     # mixed differently by a car head unit -- and nothing about playback looks wrong.
     "music is music", 3),
    ("audio/content-type-always-music", AUDIO_ATTRIBUTES,
     "    -> C.AUDIO_CONTENT_TYPE_SPEECH",
     "    -> C.AUDIO_CONTENT_TYPE_MUSIC",
     # The other direction, and the one the app shipped until this task: an audiobook that a
     # navigation prompt talks over instead of pausing.
     "an audiobook chapter is speech", 3),
    ("audio/usage-not-media", AUDIO_ATTRIBUTES,
     "      .setUsage(C.USAGE_MEDIA)",
     "      .setUsage(C.USAGE_ASSISTANT)",
     # `USAGE_MEDIA` is what puts this app on the media volume stream. On the assistant stream the
     # volume rocker stops changing the music's volume, which a user reports as "the volume buttons
     # do nothing" and no playback test can see.
     "the usage is always media", 1),

    # ---- Plan 6 Task 4: DIDL-Lite, protocolInfo, and the three-way MIME invariant -------------
    # Three parties decide what format a renderer is about to receive and each reads a different
    # artifact: Sonos reads the URL's file extension, a generic DLNA renderer reads
    # `res/@protocolInfo`, everything else reads the proxy's `Content-Type`. Every count below is
    # MEASURED with `:core:cast:test`, listed test by test in task-4-report.md.

    # THE MOST IMPORTANT PROBE IN THIS TASK. `StreamFormat.forSuffix` sends `opus`/`ogg`/`oga` as
    # `format=mp3`, so the bytes on the wire are MP3 whatever the source file was. A `ServedMedia`
    # that fell back to the source suffix would promise Sonos `audio/ogg` on a `.opus` URL and
    # serve MP3 -- spec section 12's "Sonos rejects a served format" risk, arriving in the form
    # where the device refuses a format it was never actually sent.
    #
    # Note WHICH tests catch it, because it is not the ones an author would guess: `opus never
    # reaches a renderer, by construction` does NOT redden, since `opus` is absent from `RAW_TYPES`
    # and so falls through to the same fallback either way. The suffix that discriminates is a real
    # one -- `flac` -- which is why the transcode test sweeps four sources rather than one.
    ("didl/transcode-falls-through-to-suffix", DIDL_SERVED,
     "      is StreamFormat.Mp3 -> ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)",
     "      is StreamFormat.Mp3 -> RAW_TYPES[suffix?.lowercase()] ?: ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)",
     "a transcode is served as mp3, whatever the source file was", 2),

    # `DLNA.ORG_OP=01` is a PROMISE: the low bit says byte-range seeking is supported, so a renderer
    # that reads it may issue `Range` and expect 206. Task 6's proxy owes that promise. Turning it
    # off is silent -- the document still parses, the MIME is still right, and the seek bar simply
    # stops working on hardware nobody has on the bench.
    ("didl/no-byte-range-promise", DIDL_SERVED,
     'val protocolInfo: String get() = "http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=$DLNA_FLAGS"',
     'val protocolInfo: String get() = "http-get:*:$mimeType:DLNA.ORG_OP=00;DLNA.ORG_FLAGS=$DLNA_FLAGS"',
     "protocolInfo declares no dlna profile name, on purpose", 3),

    # A Navidrome stream URL carries `&` between query parameters, and ONE unescaped ampersand makes
    # the whole metadata document unparseable at the device. The count is 2 rather than 1 only
    # because `the rendered document is well-formed xml` was given a URL containing an ampersand:
    # measured at 1 before that, since the item it started from had none, which made a
    # well-formedness assertion that could not be broken by the defect it exists to catch.
    ("didl/res-url-unescaped", DIDL_LITE,
     "    append(XmlText.escape(item.resourceUrl))",
     "    append(item.resourceUrl)",
     "every text field is escaped, including in the res url", 2),

    # `didl/metadata-escaped-twice` used to live here, mutating `DidlLite.renderEscaped`. That
    # function is gone -- escaping moved to `SoapEnvelope.render`, the one layer that owns the
    # envelope, so that no caller has to remember which of two `DidlLite` functions this argument
    # wanted. The probe was RE-POINTED rather than deleted: it is `soap/argument-escaped-twice`
    # above, on the single site that can now make the mistake. Left stale it would have aborted
    # this whole list at the all-probes preflight.

    # THE INVARIANT PROBE. `> 2` instead of `> 1` is the off-by-one that makes the check report a
    # disagreement only when all three legs differ -- i.e. it goes quiet on exactly the case the
    # whole check exists for, where TWO legs agree and the third silently differs. Every assertion
    # that compares `served.mimeType` with `served.protocolInfo` is already blind to that, because
    # both come from one object; these four are not.
    ("didl/two-legs-agreeing-is-enough", DIDL_MIME,
     "    if (listOfNotNull(declaredMime, urlMime, servedMime).distinct().size > 1) {",
     "    if (listOfNotNull(declaredMime, urlMime, servedMime).distinct().size > 2) {",
     "a protocolInfo that disagrees with the url extension is reported", 4),

    # `ServedMedia.of` guesses `audio/mpeg` for an unknown suffix because it MUST return something.
    # Asking what a *peer* will conclude from a URL is a different question, and guessing there is
    # how `.opus` -- the one suffix spec section 4 forbids outright -- comes to agree with every MP3
    # stream this client serves. One red, and it is the whole point of the distinction.
    ("didl/url-extension-guesses", DIDL_MIME,
     "    val urlMime = ServedMedia.forExtension(extension)?.mimeType",
     "    val urlMime = ServedMedia.forExtension(extension)?.mimeType ?: ServedMedia.FALLBACK_MIME",
     "an extension this client never serves is reported rather than assumed to be mp3", 1),

    # ---- Plan 6 Task 6: the proxy ------------------------------------------------------------
    #
    # The defect this task is written against is the one a status-code assertion cannot see: a
    # proxy that answers 206 with a correct `Content-Range` and streams from byte 0. Six of the ten
    # probes below are offsets, because `bytes=0-` -- the request a naive renderer sends first, and
    # therefore the one most likely to be the only one tested -- is served identically by a correct
    # implementation and by one that ignores the header entirely.
    #
    # MEASURED with `:core:cast:test`, test by test, in task-6-report.md.

    # `>=` against `>`. The whole difference is at EXACTLY `firstByte == totalLength`, which is why
    # the live test that asked for `length + 1000` was green against this mutation and had to be
    # rewritten to ask at the boundary. A renderer that seeks to the end of a track gets a 206
    # naming no bytes instead of the 416 that tells it how long the resource really is.
    ("proxy/range-boundary-off-by-one", PROXY_RANGE,
     "      request.firstByte >= totalLength -> RangeResolution.Unsatisfiable",
     "      request.firstByte > totalLength -> RangeResolution.Unsatisfiable",
     "a first byte at or past the end is unsatisfiable", 2),

    # `bytes=-0` names no bytes at all. Read as "no suffix, so everything", it hands a renderer the
    # START of the file when it asked for nothing -- and RFC 7233 calls it unsatisfiable.
    ("proxy/range-suffix-zero-is-whole", PROXY_RANGE,
     "      request.lastBytes <= 0 -> RangeResolution.Unsatisfiable",
     "      request.lastBytes <= 0 -> RangeResolution.Whole",
     "a suffix of zero bytes is unsatisfiable, not the whole entity", 2),

    # A 206 with no `Content-Range` is a partial response the renderer cannot place. Silent: the
    # status is right, the length is right, and the seek bar simply does not work.
    ("proxy/no-content-range", PROXY_SERVER,
     '              contentRange = "$BYTES_UNIT ${range.firstByte}-${range.lastByte}/$totalLength",',
     "              contentRange = null,",
     "a ranged HEAD reports the range's length without sending it", 2),

    # THE PROBE THIS TASK EXISTS FOR. The head is correct in every particular -- 206, the right
    # `Content-Range`, the right `Content-Length` -- and the bytes are the start of the file. Every
    # seek lands at the beginning of the track, and nothing anywhere reports a problem. No status
    # assertion can see it; only an assertion on the BYTES can, and both the fake-upstream table and
    # the live byte-exact tests do.
    ("proxy/stream-from-byte-zero", PROXY_SERVER,
     "      upstream.open(media.upstreamUrl, range).use { input ->",
     "      upstream.open(media.upstreamUrl, ByteRange(0, range.length - 1)).use { input ->",
     "a 206 body is the bytes that were asked for and not the bytes at the start of the file", 3),

    # The traversal check. Pulling the token out of a path with `substringAfterLast('/')` resolves
    # `/media/../<token>.mp3` -- and anything else ending the same way -- to a real published item.
    ("proxy/token-out-of-any-path", PROXY_REGISTRY,
     "    published.values.firstOrNull { it.path == path }",
     "    published.values.firstOrNull { it.path.substringAfterLast('/') == path.substringAfterLast('/') }",
     "a path outside the media prefix resolves to nothing, traversal included", 1),

    # A token is a CAPABILITY, not an identity. Derived from the upstream URL it is stable across
    # sessions, guessable by anything that knows the library, and a revoked session's URL works
    # again the moment the same track is cast twice.
    ("proxy/token-is-the-url", PROXY_REGISTRY,
     '    val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)\n      .joinToString("") { "%02x".format(it) }',
     "    val token = upstreamUrl.hashCode().toString(16)",
     "two publications of the same url get different tokens", 6),

    # Spec section 6: Sonos infers MIME from the URL, not from `Content-Type`. A path with no
    # extension is `714 Illegal MIME-type` on real hardware -- and a perfectly good 200 here.
    ("proxy/path-without-an-extension", PROXY_REGISTRY,
     "      path = PATH_PREFIX + served.fileName(token),",
     "      path = PATH_PREFIX + token,",
     "a published path ends in the served extension, because sonos sniffs the url", 4),

    # Spec section 4's 429. Ignoring `Retry-After` means backing off 0.5 s when the server asked for
    # 3 -- which is how a transcode limit turns into "random playback failure" rather than a wait.
    ("proxy/retry-after-ignored", PROXY_UPSTREAM,
     "    retryAfterHeader?.toLongOrNull()?.let { return (it * MILLIS_PER_SECOND).coerceIn(0L, MAX_BACKOFF_MS) }\n",
     "",
     "the origin's own retry-after wins over the backoff", 4),

    # 503 says "try again" and 502 says "this is broken". A renderer that believes the second one
    # stops, and the user sees a track that will not play with no way to retry it.
    ("proxy/throttle-is-502", PROXY_SERVER,
     '          503,\n          "Service Unavailable",',
     '          502,\n          "Bad Gateway",',
     "a throttled upstream becomes 503 with a retry-after, not 502", 2),

    # THE SECURITY PROBE, and the companion to `cast/no-inbound-guard` above. That one proves
    # `LocalNetworkOnly.acceptLocal` refuses a peer off the local network; this one proves the proxy
    # -- the only ServerSocket in the app, serving Navidrome-authenticated audio to a LAN -- is
    # actually the thing that calls it. The refusal itself cannot be observed from a loopback-only
    # test, because loopback is local by construction, so the call site is what gets asserted.
    ("proxy/no-inbound-guard-at-the-call-site", PROXY_SERVER,
     "  internal val acceptConnection: (ServerSocket) -> Socket? = LocalNetworkOnly::acceptLocal,",
     "  internal val acceptConnection: (ServerSocket) -> Socket? = { it.accept() },",
     "connections are taken through the inbound local-network guard", 1),

    # ---- Plan 7 Task 2: the two security controls a green suite cannot see --------------------
    # Both mutations leave BRANCH and LINE coverage exactly where they were, which is precisely why
    # they belong here rather than behind a coverage floor.
    #
    # 1. The per-service Keystore alias -- the independence property the whole plan rests on. A
    #    `keyAlias` returning one constant for both services makes `clear(LIDARR)` destroy
    #    Bindery's key too, and no test that configures a single service can see it. The mutated
    #    `when` still has two arms and both still execute, so the 2/2 BRANCH floor over this
    #    companion stays green under the mutation.
    ("integrations/keyAlias-service", STORE,
     'IntegrationService.BINDERY -> "app.muplay.integrations.bindery"',
     'IntegrationService.BINDERY -> "app.muplay.integrations.lidarr"',
     "the two services use two different keystore aliases", 1),

    # 2. The API-key redaction. A Lidarr key is instance-wide and carries admin authority over the
    #    user's download client, and the `toString()` a `data class` generates would print it into
    #    the first `Log.d(state)` anyone writes. This is `:core:model`'s `SubsonicCredentials`
    #    defect one module over; the class's 5/5 LINE floor stays green under the mutation, so the
    #    only thing standing between the key and a crash report is the named assertion.
    ("integrations/credentials-redaction", CREDENTIALS,
     'override fun toString(): String = "Lidarr(baseUrl=$baseUrl, apiKey=<redacted>)"',
     'override fun toString(): String = "Lidarr(baseUrl=$baseUrl, apiKey=$apiKey)"',
     "toString does not leak the api key", 1),

    # ---- Plan 3 Task 5, review round: who may connect to the exported media session ----------
    # The service is exported -- it has to be, or Android Auto, Wear, Assistant and the system
    # media controls cannot find it -- with no `android:permission`, and Media3's default callback
    # accepts every connection unconditionally. A connected controller gets
    # `DEFAULT_UNTRUSTED_PLAYER_COMMANDS`, which withholds transport control and grants metadata
    # reads: i.e. the artwork URI, which carries `u`, `s=salt` and `t=md5(password+salt)` and does
    # not expire.
    #
    # The first probe is the gate failing OPEN, which is the shape that ships silently: the app's
    # own controller still connects, every playback test on every tier stays green, and any local
    # app can read the credential. Nothing in this project noticed it for a whole task.
    ("media/controller-gate-accepts-everyone", CONTROLLER_ACCESS,
     "isTrustedForMediaControl || controllerPackageName == PLATFORM_LEGACY_CONTROLLER_PACKAGE",
     "controllerPackageName.isNotEmpty() || isTrustedForMediaControl",
     # 3, measured: it also reddens `this app's own controller connects, and does so on the trusted
     # arm` (whose second half pins that the decision is the trust flag and not the package name)
     # and `the legacy carve-out is one exact name, not a family of them`.
     "an app the platform does not trust with media control cannot connect", 3),
    # The other direction, and it breaks a real user rather than a real secret: dropping the legacy
    # carve-out refuses the platform's own unattributable caller, which below API 28 is every
    # headset button and Bluetooth AVRCP command that reaches the session (minSdk is 26). Neither
    # this project's emulator nor any device test it can run reproduces that API level -- which is
    # the whole reason this rule is a plain function the JVM tier holds.
    ("media/controller-gate-drops-legacy-carve-out", CONTROLLER_ACCESS,
     "isTrustedForMediaControl || controllerPackageName == PLATFORM_LEGACY_CONTROLLER_PACKAGE",
     "isTrustedForMediaControl",
     "the platform's own unattributable legacy caller connects", 1),

    # ---- Plan 7 Task 3: the request store's JVM-reachable half ---------------------------------
    # ONLY the JVM-reachable half, and that is a limit worth reading before adding to this family.
    # The defect this task is *named* for -- `requests(service)` forwarding to the DAO and dropping
    # its argument -- lives behind real Room and real SQL, so its proof is an instrumented test and
    # this runner cannot see it. That was measured rather than deduced: the plan's Step 9 asked for
    # three probes naming `MediaRequestRepositoryTest` methods, and running one of them here
    # reported MISSED with zero failures in the whole JVM suite -- the same shape as the
    # `PlayerConstructionTest` probe Plan 3 Task 7b removed for the same reason. Those three
    # mutations were applied by hand against the emulator suite instead and the transcript is in
    # task-3-report.md. Do not add them back without a runner that can reach a device.
    #
    # 3. The composite request id. `"<SERVICE>:<externalId>"` is what keeps a Lidarr and a Bindery
    #    request for the same identifier from colliding onto one row, and a hardcoded service half
    #    is invisible to every test that configures one service -- the same shape as
    #    `integrations/keyAlias-service` above, one type over.
    ("integrations/request-id-service", MEDIA_REQUEST,
     'fun idFor(service: IntegrationService, externalId: String): String = "${service.name}:$externalId"',
     'fun idFor(service: IntegrationService, externalId: String): String = "LIDARR:$externalId"',
     "the request id is derived from the service and the external id", 1),

    # 4. The `status_detail` column's value. A `storedDetail` that returned one constant leaves the
    #    status column right and the payload wrong, which renders as a download stuck at one
    #    percentage forever. Two members carry data at two values each precisely so a constant
    #    cannot satisfy them.
    ("integrations/request-status-detail", REQUEST_STATUS,
     'is RequestStatus.Downloading -> percentComplete?.toString()',
     'is RequestStatus.Downloading -> "7"',
     "the detail column carries the member's data and nothing else", 2),

    # 5. The corrupt-row verdict. Reading an unrecognised stored status as `Requested` tells the
    #    user their request is still in progress forever and tells a bug report nothing at all;
    #    every arm of `fromStored` still executes under the mutation, so no coverage floor moves.
    ("integrations/request-status-unknown", REQUEST_STATUS,
     'else -> Failed("unrecognised stored status \\"$name\\"")',
     'else -> Requested',
     "an unrecognised stored status reads as a failure that names itself", 1),
]


# Plan 1's original defect: `authParams()` returning nothing at all left every one of that plan's
# 81 tests green at 100% branch coverage, because nothing in the build inspected a request. It
# needs two edits (the second closes the paren) and reddens thirteen tests rather than one, so it
# is described separately rather than bent into the table's shape.
AUTH_PROBE = ("auth/empty-authParams", AUTH,
              [('mapOf(\n      "u" to credentials.username,',
                'emptyMap<String, String>().plus(mapOf(\n      "u" to credentials.username,'),
               ('      "f" to "json",\n    )\n}', '      "f" to "json",\n    )).let { emptyMap() }\n}')],
              # Named test is `SubsonicAuthTest`'s security assertion on purpose: until round 3 it
              # was vacuously green (`doesNotContainKey` + `noneMatch`, both true of an empty map)
              # and this exact mutation did NOT redden it. Pinning it here makes "that test is no
              # longer vacuous" a permanent check rather than a one-off fix.
              # 15 as of 7f27d4a; 19 once Plan 3 Task 1's `StreamUrlTest` added four more tests that
              # read a parameter `authParams()` is the sole source of (`f`, `c`/`v`, the token, and
              # the salt-freshness pair). The named test failed exactly as intended both times --
              # this is the stale-count case the note on `expected failures` above describes, not a
              # code regression. See that note before changing this.
              "the password never appears in the parameters", 19)

# Every probe that lives outside the PROBES table, because it needs more than one text
# substitution (AUTH_PROBE takes two edits, not one) and so does not fit that table's shape. A list,
# not a bare `1`: N4-4, round 5 -- `len(PROBES) + 1` hardcoded "there is exactly one probe outside
# the table" below, silently wrong (not crashing) the moment a second one was added. `len(PROBES) +
# len(EXTRA_PROBES)` cannot go stale that way; add a new out-of-table probe to this list, not to
# the `+ 1`.
EXTRA_PROBES = [AUTH_PROBE]


def apply(path, old, new):
    src = open(path).read()
    if src.count(old) != 1:
        raise SystemExit(f"PROBE TEXT NOT FOUND (or ambiguous) in {path}: {src.count(old)} matches for:\n{old}")
    open(path, "w").write(src.replace(old, new))


# Every file the probes above mutate that is not named on the `git checkout` line inside
# `revert()` below. One name per line, and a separate list rather than more names on that line, for
# a merge reason that is not hypothetical: several branches add a probe file at once, a union merge
# of two edits to the *same* line produces either a duplicated `],` (a SyntaxError, loud) or a
# silently wrong argument list, and this project has already had a union merge collapse two
# `CoverageFloor(...)` calls in the root build script into one broken call. Appending a line to
# this list conflicts with nothing.
#
# Getting an entry wrong here fails in the worst direction rather than the safe one -- a mutated
# file that no `git checkout` names is left in the tree when the run ends, which is exactly the
# stray-mutation incident this script's header describes -- so add the file here in the same edit
# that adds the probe, never after.
LATER_PROBE_FILES = [
    RESUME_POLICY,
    BROWSE_ID,
    BASE_URL,
    INTEGRATION_SERVICE,
    # Plan 7 Task 2, added in the same edit as the two `integrations/` probes -- never after, per
    # this list's own comment: a mutated file no `git checkout` names is left in the tree when the
    # run ends, which is the stray-mutation incident this script's header describes.
    STORE,
    CREDENTIALS,
    # Plan 7 Task 3, added in the same edit as the three `integrations/request-*` probes, per this
    # list's own comment.
    REQUEST_STATUS,
    MEDIA_REQUEST,
    PLAYBACK_SERVICE,
    TASK_REMOVAL,
    PLAYBACK_STATE,
    TRACK_ID_KEY,
    BROWSE_TREE,
    BROWSE_TEXT,
    BROWSE_SURFACE,
    # Plan 3 Task 9. Omitting these three is not a hypothetical: the first run of the player
    # probes left every mutation in the tree, so failures accumulated probe over probe (6, then 7,
    # 8, 11, ... 18) and all eleven reported MISSED against counts that were never measurable.
    # Exactly the "fails in the worst direction" this list's own comment above warns about.
    PLAYER_STATE,
    PLAYER_VM,
    PLAYBACK_LAUNCHER,
    # Plan 3 Task 6.
    AUDIO_ATTRIBUTES,

    # Plan 3 Task 5's review round, added in the same edit as its two probes above -- which is what
    # this list's own comment asks for, and the reason it asks: a mutated file no `git checkout`
    # names is left in the tree when the run ends.
    CONTROLLER_ACCESS,
]


def revert():
    subprocess.run(
        ["git", "checkout", "--", CLIENT, AUTH, TYPE, MODEL, MIRROR, SETUP_VM, SYNC_DECISION,
         LIBRARY_VM, ALBUM_VM, LIBRARY_STATE, STREAM_FORMAT, RETRY_POLICY, MEDIA_MODULE,
         PCM_ANALYSIS, PLAYBACK_QUEUE,
         # The whole directory, not the four files the cast probes name today: Plan 6 adds five
         # more source files to this module across Tasks 2-11, and a probe on one of them that
         # this list had not been extended for would revert to nothing at all.
         "core/cast"],
        check=True,
    )
    subprocess.run(["git", "checkout", "--", *LATER_PROBE_FILES], check=True)


# Exactly the modules `run_suite()` below invokes, paired with the result directory each one's
# JVM tier actually writes to. A plain `muplay.jvm.library` module (`:core:network`, `:core:model`)
# writes to `test-results/test/`; an Android module's `test<Variant>UnitTest` task (`:core:database`)
# writes to `test-results/test<Variant>UnitTest/` instead -- `:core:database:test` is a lifecycle
# alias for `testDebugUnitTest` here (no other build type is tested), so its results always land
# under `testDebugUnitTest`, not `test`.
#
# Listed module-by-module, not a `core/*` wildcard: this repo has *other* Android modules
# (`:core:designsystem`) whose own `test-results/testDebugUnitTest/` can hold real result files on
# disk from an earlier, unrelated Gradle invocation -- confirmed live, that directory already
# existed with a passing `ThemeTest` result before this runner ever touched it. A wildcard glob
# would read that stale file as if this run had just produced it; `failures()` and `run_suite()`
# must stay paired to exactly the same module set, or a leftover result from a module this script
# never ran could silently manufacture (or hide) a probe's expected failure. `:core:network` is the
# same story from the other direction: it also writes to `test-results/liveNavidromeTest/`, a
# live-container suite this runner has no business scanning.
JVM_TEST_RESULT_DIRS = {
    "core/network": "test",
    "core/model": "test",
    "core/database": "testDebugUnitTest",
    "feature/setup": "testDebugUnitTest",
    "feature/library": "testDebugUnitTest",
    # `:core:media` joined in Plan 3 Task 2. Android module, so `testDebugUnitTest` -- and its JVM
    # tier is the whole point of the module's shape: `StreamRetryPolicy` and `MediaModule` carry no
    # Android or Media3 type in their signatures precisely so this runner can reach them.
    "core/media": "testDebugUnitTest",
    # `:core:testing` joined in Plan 3 Task 7. A plain `muplay.jvm.library` module, so
    # `test-results/test/` -- and reachable here for the same reason `:core:media`'s JVM tier is:
    # `PcmAnalysis` is pure Kotlin over a `ByteArray`, with no Android or Media3 type in it, so the
    # oracle the emulator's gapless assertion is read through is itself probed on this tier. That
    # asymmetry is the whole point of splitting the analyser out: a `longestZeroRunFrames` that
    # returned 0 would leave `GaplessTest` green and only these probes red.
    "core/testing": "test",
    # `:core:cast` joined in Plan 6 Task 1. A plain `muplay.jvm.library`, so `test` -- and the
    # entire module is reachable here by construction: it carries no Android type at all, which is
    # what puts the casting protocol surface, the proxy and the routing decision in Tier 1.
    # Verified by breaking it on purpose: with the cast probes added and this entry (and the
    # invocation below) still absent, `./ci/mutation-probes.sh cast` reported 0/8 caught, every one
    # of them "got 0" failures. That is the same argument this file's header makes about the probe
    # list, applied to the runner -- a runner that runs no tests for a module reports "caught" for
    # nothing and "missed" for nothing, and the only way to tell which you have is to make it fail
    # once deliberately.
    "core/cast": "test",
    # `:integrations:core` joined in Plan 7 Task 1. An Android library (`muplay.android.library` +
    # `muplay.android.hilt`), so `testDebugUnitTest` -- but its whole JVM tier is reachable here by
    # construction: `IntegrationBaseUrl` is pure Kotlin over OkHttp's URL parser and names no
    # Android type, which is exactly why the cleartext decision and the secret-stripping live in
    # this module rather than inside either service client.
    "integrations/core": "testDebugUnitTest",
    # `:feature:player` joined in Plan 3 Task 9. Android module, so `testDebugUnitTest`. Its JVM
    # tier is reachable for the same reason `:feature:library`'s is: the state mapping is a pure
    # top-level function in its own file, and `PlayerViewModel` is constructed over a
    # `PlaybackControls` seam, so neither needs a device. What is NOT reachable here is the
    # module's Compose half -- 24 instrumented tests that compose the real screens -- so a mutation
    # only those can catch (a swapped title/artist, a mini player that navigates when its own
    # button is tapped) is recorded in task-9-report.md instead, the same way `LiveNavidromeTest`'s
    # test-side probes already are.
    "feature/player": "testDebugUnitTest",
}


def failures():
    out = []
    for module, result_dir in JVM_TEST_RESULT_DIRS.items():
        for f in glob.glob(f"{module}/build/test-results/{result_dir}/TEST-*.xml"):
            body = open(f).read()
            for m in re.finditer(r'<testcase name="([^"]*)"[^>]*>\s*<failure[^>]*>(.*?)</failure>', body, re.S):
                out.append((m.group(1).removesuffix("()"),
                            html.unescape(m.group(2)).split("\n\tat ")[0].replace("\n", " ").strip()))
    return out


def run_suite():
    # N2-2 (round 2 re-review): without both of the next two blocks, a mutation that reddens an
    # EARLIER module in the invocation below (`:core:network:test`, 28 of this file's 37 probes)
    # aborted the whole Gradle invocation before a LATER module's task -- `:feature:setup:test`,
    # added this round -- ever started, and `failures()` then globbed whatever XML that later
    # module's *previous* run had left on disk and counted it as this run's own result. Reproduced
    # deterministically: leave `a successful connect saves the credentials and lists the libraries
    # for tagging` failing on disk (any setup probe does this), then run `auth/empty-authParams`
    # with Gradle serialised to one worker (`-Dorg.gradle.workers.max=1`, which reliably starves
    # `:feature:setup:test` of a scheduling slot before the abort) -- MISSED, 16 instead of 15,
    # the extra failure being that stale result. At normal worker counts this is a race, which is
    # why it looked load-correlated rather than caused, and a prior investigation into it (see
    # task-8-report.md) concluded "build cache" without a check that could have told the two
    # hypotheses apart -- this fix, and the "confirm the false result no longer appears" step
    # below, is that check.
    #
    # Delete every result directory first, so a module whose task genuinely never runs this
    # invocation has nothing old lying around for `failures()` to find.
    #
    # N3-1 (round 2 re-review, second pass): catches only FileNotFoundError -- "there was nothing
    # to delete yet" -- not every error. `ignore_errors=True` was tried first and swallowed *any*
    # deletion failure, which is the same defect this whole function exists to close, one layer
    # down: the reviewer forced a directory read-only (`chmod 555`) with a stale failing result
    # already inside it, and the silently-ignored rmtree failure left that stale file in place for
    # `failures()` to glob -- a genuinely-passing probe came back MISSED with a fabricated extra
    # failure, the exact bug this function was written to close. A permission error (or anything
    # else unexpected) has to stop the run here, loudly, rather than be swallowed the same way.
    for module, result_dir in JVM_TEST_RESULT_DIRS.items():
        try:
            shutil.rmtree(f"{module}/build/test-results/{result_dir}")
        except FileNotFoundError:
            pass
    # --continue: keep scheduling every other requested task after one fails, rather than
    # aborting the whole invocation on the first failure. The five modules here share no
    # compile-time dependency that would make one module's task genuinely unable to run after
    # another's test failure (a *test* task failing does not un-compile anything downstream), so
    # with --continue every one of the five should get a real chance to execute every time.
    # `:feature:library` joined in Task 9, for the same reason `:feature:setup` joined in Task 8:
    # LibraryViewModel/AlbumViewModel are plain ViewModels with hand-written fakes for their own
    # Room/network-backed collaborators, so their forwarding logic needs no device either.
    subprocess.run(["./gradlew", "--quiet", "--continue", ":core:network:test", ":core:model:test",
                    ":core:database:test", ":feature:setup:test", ":feature:library:test",
                    ":core:media:test", ":core:testing:test", ":core:cast:test",
                    ":integrations:core:test", ":feature:player:test"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # A missing result must be loud, not silently globbed as zero failures: if some other cause
    # (a genuine compile failure a dependent task cannot route around, even with --continue)
    # still leaves a module's directory empty, that is exactly the "counts stale/absent results"
    # failure mode this function exists to rule out, so it must stop the run rather than let
    # `failures()` quietly report an artificially clean (or artificially stale) count.
    missing = [module for module, result_dir in JVM_TEST_RESULT_DIRS.items()
               if not glob.glob(f"{module}/build/test-results/{result_dir}/TEST-*.xml")]
    if missing:
        raise SystemExit(
            f"run_suite(): no test results were written for {missing} -- the build likely "
            "aborted or skipped these modules' test tasks. Refusing to let failures() count "
            "zero (or stale) results for them as if this run had produced them."
        )
    return failures()


def check(probe_id, expected_test, expected_count, got):
    names = [n for n, _ in got]
    ok = expected_test in names and len(got) == expected_count
    print(f"  {'CAUGHT ' if ok else 'MISSED '} {probe_id}")
    for n, msg in got[:3]:
        print(f"      {n} | {msg[:110]}")
    if not ok:
        print(f"      EXPECTED: '{expected_test}' to fail, and {expected_count} failure(s) in total;"
              f" got {len(got)}: {names}")
    return ok


selected = [p for p in PROBES if FILTER in p[0]]
run_auth = FILTER in AUTH_PROBE[0]
total = len(selected) + (1 if run_auth else 0)
if total == 0:
    raise SystemExit(f"no probe id matches '{FILTER}'")

# PREFLIGHT: every probe's search text must still match its file exactly once.
#
# A probe's `old` text goes stale the moment somebody edits the line it names, and
# `apply()` then raises SystemExit on the FIRST such probe -- which aborts the whole
# run and, because every run here is filtered to one family, can sit unnoticed for
# hours. That has happened twice in one day: a position clamp rewrote the line
# `player/scrub-position-ignored` searched for, and a depth cap rewrote the line
# `discovery/no-devicelist-recursion` searched for. Both times the regression list
# this repo relies on could not be run at all, by anybody, and nothing said so.
#
# This checks EVERY probe in the list -- not just the selected ones -- before
# mutating anything, and reports all the stale ones together. Checking the whole
# list on a filtered run is deliberate: a filtered run is exactly how the other
# families' staleness stays invisible.
stale = []
for probe_id, path, edit, *_ in PROBES + EXTRA_PROBES:
    # A table probe's third field is one `old` string; an out-of-table probe's is a
    # LIST of (old, new) pairs, because it needs more than one substitution. Handle
    # both -- assuming the string shape is what made the first version of this check
    # crash on AUTH_PROBE the moment it ran.
    olds = [e[0] for e in edit] if isinstance(edit, list) else [edit]
    try:
        src = pathlib.Path(path).read_text()
    except FileNotFoundError:
        stale.append(f"  {probe_id}: file not found: {path}")
        continue
    for old_text in olds:
        n = src.count(old_text)
        if n != 1:
            stale.append(f"  {probe_id}: {n} matches in {path} (need exactly 1)")
if stale:
    raise SystemExit(
        "STALE PROBES -- their search text no longer matches the source exactly once.\n"
        "The list cannot run until these are realigned with the code they name:\n"
        + "\n".join(stale)
    )

print(f"Running {total} mutation probe(s). Each is applied alone and reverted.\n")
results = []
try:
    for probe_id, path, old, new, expected_test, expected_count in selected:
        apply(path, old, new)
        got = run_suite()
        revert()
        results.append(check(probe_id, expected_test, expected_count, got))

    if run_auth:
        probe_id, path, edits, expected_test, expected_count = AUTH_PROBE
        for old, new in edits:
            apply(path, old, new)
        got = run_suite()
        revert()
        results.append(check(probe_id, expected_test, expected_count, got))
finally:
    revert()

missed = results.count(False)
print(f"\n{len(results) - missed}/{len(results)} probes caught "
      f"(of {len(PROBES) + len(EXTRA_PROBES)} in the list).")
if missed:
    print("A MISSED probe means a defect this project already found once is no longer detected.")
    raise SystemExit(1)
print("Remember: this list cannot catch a value nobody wrote a probe for. See the header.")
PY
