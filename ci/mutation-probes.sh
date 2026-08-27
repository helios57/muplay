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
GAIN_POLICY = "core/media/src/main/kotlin/app/muplay/media/ReplayGainPolicy.kt"
PCM_ANALYSIS = "core/testing/src/main/kotlin/app/muplay/testing/PcmAnalysis.kt"
BOOK_FIXTURES = "core/testing/src/main/kotlin/app/muplay/testing/BookFixtures.kt"
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
BROWSE_PAGING = "core/model/src/main/kotlin/app/muplay/model/browse/BrowsePaging.kt"
BROWSE_EXTRAS = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseExtras.kt"
BROWSE_SELECTION = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseSelection.kt"
PLAY_FROM_SEARCH = "core/model/src/main/kotlin/app/muplay/model/browse/PlayFromSearch.kt"
BROWSE_TREE_REPOSITORY = "core/database/src/main/kotlin/app/muplay/database/BrowseTreeRepository.kt"
BOOK_SUMMARIES = "core/database/src/main/kotlin/app/muplay/database/BookSummaries.kt"
DATA_MODULE = "core/database/src/main/kotlin/app/muplay/database/di/DataModule.kt"
# Plan 4 Task 2. The two audiobook value types that live on the JVM tier at all -- the schema,
# the DAOs and the migration behind them need a device and are recorded by hand in
# task-2-report.md, per this file's own INSTRUMENTED TIER note above.
CHAPTER = "core/model/src/main/kotlin/app/muplay/model/Chapter.kt"
BOOK_SETTINGS = "core/model/src/main/kotlin/app/muplay/model/BookSettings.kt"
# Plan 4 Task 3. The Android-free half of chapter reading: `ChapterReader` and `ChapterRepository`
# are Media3/Room-shaped and unreachable from this JVM-only runner (their mutations are recorded by
# hand in task-3-report.md, per the SCOPE note above), but the sorting, the end-time filling, the
# de-duplication and the whole timeline are plain Kotlin and are gated here.
CHAPTER_ASSEMBLY = "core/media/src/main/kotlin/app/muplay/media/ChapterAssembly.kt"
BOOK_TIMELINE = "core/media/src/main/kotlin/app/muplay/media/BookTimeline.kt"
# Plan 4 Task 5. A pure function over two Longs, on purpose, so the whole of it is reachable from
# this JVM-only runner -- there is nothing about the smart-rewind table that needs a device.
SMART_REWIND = "core/media/src/main/kotlin/app/muplay/media/SmartRewind.kt"
# Plan 4 Task 8. The two Android-free halves of the sleep timer. `SleepTimerController` needs a real
# `Player` and `ShakeSensor` needs a real `SensorManager`, so both are out of this runner's reach and
# their mutations are recorded by hand in task-8-report.md, per the SCOPE note above -- but the fade
# ramp and the shake decision are plain arithmetic and are gated here.
SLEEP_FADE = "core/media/src/main/kotlin/app/muplay/media/SleepTimerFade.kt"
# Plan 4 Task 6. The resume policy itself is a pure function over an in-memory item and a `Clock`,
# on purpose, so the whole of the decision this project exists for is reachable from this JVM-only
# runner. `AudiobookSnapshot` and `ResumptionQueue` are Room-shaped and are recorded by hand in the
# task report, per the SCOPE note above -- and so is the ONE thing that could not be probed here at
# all until this task changed it: `MediaModule`'s binding, which now takes an `AudiobookItemSource`
# rather than the concrete snapshot precisely so `MediaModuleTest` can call it from this tier.
AUDIOBOOK_POLICY = "core/media/src/main/kotlin/app/muplay/media/AudiobookResumePolicy.kt"
# The local-only guard's two subjects: the port every consumer holds, and the wire it reaches.
SUBSONIC_SOURCE = "core/network/src/main/kotlin/app/muplay/network/SubsonicSource.kt"
SUBSONIC_API = "core/network/src/main/kotlin/app/muplay/network/SubsonicApi.kt"
SHAKE_DETECTOR = "core/media/src/main/kotlin/app/muplay/media/ShakeDetector.kt"
BASE_URL = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt"
STORE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentialStore.kt"
CREDENTIALS = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationCredentials.kt"
INTEGRATION_SERVICE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationService.kt"
REQUEST_STATUS = "integrations/core/src/main/kotlin/app/muplay/integrations/RequestStatus.kt"
MEDIA_REQUEST = "integrations/core/src/main/kotlin/app/muplay/integrations/MediaRequest.kt"
LIDARR_INT = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAuthInterceptor.kt"
LIDARR_CLIENT = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrClient.kt"
LIDARR_EXC = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrException.kt"
LIDARR_API = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrApi.kt"
LIDARR_TARGETS = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddTargets.kt"
LIDARR_PAYLOAD = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrAddPayload.kt"
BINDERY_INT = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyAuthInterceptor.kt"
BINDERY_CLIENT = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyClient.kt"
BINDERY_API = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyApi.kt"
BINDERY_EXC = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyException.kt"
BINDERY_STATUS = "integrations/bindery/src/main/kotlin/app/muplay/integrations/bindery/BinderyStatusMapper.kt"
LIDARR_STATUS = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrStatusMapper.kt"
LIDARR_SOURCE = "integrations/lidarr/src/main/kotlin/app/muplay/integrations/lidarr/LidarrSource.kt"
PLAYBACK_SERVICE = "core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt"
TASK_REMOVAL = "core/media/src/main/kotlin/app/muplay/media/TaskRemovalPolicy.kt"
PLAYBACK_STATE = "core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt"
AUDIO_ATTRIBUTES = "core/media/src/main/kotlin/app/muplay/media/PlaybackAudioAttributes.kt"
TRANSCODE_SEEK = "core/media/src/main/kotlin/app/muplay/media/TranscodeSeek.kt"

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
CONTROL_STATE = "core/cast/src/main/kotlin/app/muplay/cast/control/TransportState.kt"
CONTROL_CAPS = "core/cast/src/main/kotlin/app/muplay/cast/control/RendererCapabilities.kt"
CONTROL_RENDERER = "core/cast/src/main/kotlin/app/muplay/cast/control/UpnpRenderer.kt"
ROUTE_SUBNET = "core/cast/src/main/kotlin/app/muplay/cast/route/SubnetMatch.kt"
ROUTE_ROUTER = "core/cast/src/main/kotlin/app/muplay/cast/route/CastRouter.kt"
SESSION_SESSION = "core/cast/src/main/kotlin/app/muplay/cast/session/CastSession.kt"
SESSION_PLAYBACK = "core/cast/src/main/kotlin/app/muplay/cast/session/CastPlayback.kt"
SESSION_SOURCE = "core/cast/src/main/kotlin/app/muplay/cast/session/CastSource.kt"
HANDOVER_POLICY = "core/media/src/main/kotlin/app/muplay/media/cast/OneShotResumePolicy.kt"
SERVED_MEDIA = "core/cast/src/main/kotlin/app/muplay/cast/didl/ServedMedia.kt"
# The one probe below that mutates TEST source, named here rather than quietly reached through
# the `core/cast` entry in `revert()`. See `soap/fake-accepts-everything` for why it is not the
# test-side probe this file's SCOPE note excludes: `FakeRenderer` is the *subject* of
# `FakeRendererStrictnessTest`, not one of its assertions.
# MOVED in Plan 6 Task 9, from `src/test` to `src/testFixtures`: `:core:media`'s instrumented
# `HandoverTest` drives a handover against this same fake, and a `testFixtures` source set is
# how one module's test helper reaches another's. `revert()` checks out `core/cast` wholesale,
# so the move needed no change there -- only here, where the path is written out.
SOAP_FAKE = "core/cast/src/testFixtures/kotlin/app/muplay/cast/fake/FakeRenderer.kt"

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
     "knows", 8),
    ("album/load-album-id-hardcoded", ALBUM_VM,
     "viewModelScope.launch { album.value = Fetch.Done(source.album(albumId)) }",
     'viewModelScope.launch { album.value = Fetch.Done(source.album("wrong-id")) }',
     # 4 -> 7, same cause as the probe above; this one additionally reddens the album-call-count
     # assertion in `an album still being fetched is Loading...`.
     "the album shown is the one load was called with, not a different one the source also "
     "knows", 9),

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
     "shuffle order is the order the shuffle produced, not resorted", 4),
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
     "an opus source is transcoded rather than streamed raw", 6),
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
    # Plan 4 Task 4 moved `provideClock` (and its test) down into `:core:database`'s `DataModule`:
    # `AudiobookRepository` is the first class there to take a `Clock`, and a binding declared above
    # its consumer leaves that module's Hilt tests without one. The probe followed the binding --
    # its preflight caught the move by refusing to run at all ("0 matches in MediaModule.kt"), which
    # is that guard doing exactly its job. The witness stayed on the **JVM** tier for the reason
    # this file's header gives: `run_suite()` names JVM test tasks, so a test that only runs on a
    # device reports MISSED with zero failures.
    ("progress/clock-frozen", DATA_MODULE,
     "  fun provideClock(): Clock = Clock.systemUTC()",
     "  fun provideClock(): Clock =\n    Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)",
     # A `Clock.fixed` left behind by a test edit compiles, injects, and writes a row every five
     # seconds with `lastPlayedAtEpochMs = 0`. Nothing else in the build would notice.
     "the injected clock is a real clock and not a frozen one", 1),
    ("progress/policy-resumes", MEDIA_MODULE,
     "  fun provideUndecoratedResumePolicy(source: AudiobookItemSource, clock: Clock): ResumePolicy =\n"
     "    AudiobookResumePolicy(source, clock)",
     "  fun provideUndecoratedResumePolicy(source: AudiobookItemSource, clock: Clock): ResumePolicy =\n"
     "    ResumePolicy { _, i -> app.muplay.media.ResumeTarget(i, 30_000L) }",
     # `resume/position-honoured` above breaks `NeverResume` itself; this breaks the *binding*, which
     # is the other way the same defect arrives and the one Plan 4 will be editing. `MuPlayer`
     # faithfully applies whatever is bound here, so a wrong binding is a wrong app.
     #
     # RENAMED in Plan 6 Task 9, which re-annotated this provider `@UndecoratedResumePolicy` so that
     # the one UNQUALIFIED `ResumePolicy` in the graph is the cast decorator. The probe went STALE
     # -- "0 matches ... (need exactly 1)" -- and the family refused to run, which is the guard
     # working: a probe whose search text has drifted is a probe that would silently mutate nothing.
     #
     # REPOINTED in Plan 4 Task 6, which is the task this probe's own comment above predicted
     # ("the one Plan 4 will be editing"). The provider's signature and body both changed, so the
     # search text went stale and `ci/probe-preflight.py` refused the run -- twice now this probe
     # has been the one that catches a signature move, which is the guard working. It remains the
     # OPPOSITE direction to `resume/module-never` below: that one binds a policy that resumes
     # nothing, this one binds a policy that resumes EVERYTHING, music included, at a constant.
     "the undecorated resume policy is the one that actually resumes a book", 2),
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
     "a start index outside the queue is rejected", 4),
    ("queue/songAt-index", PLAYBACK_QUEUE,
     "  fun songAt(index: Int): Song = songs[index]",
     "  fun songAt(index: Int): Song = songs[0]",
     "songAt returns the song at that index", 2),
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
     "a queue holds the songs it was given in the order it was given them", 5),
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
     "a header is found whatever case the peer used", 9),
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
     "a public address is not local", 11),
    ("cast/no-local-guard", CAST_CLIENT,
     "    LocalNetworkOnly.require(host, address)\n", "",
     # The mutation that matters most in this module: without that one line MuPlay becomes an app
     # that will send plaintext anywhere it is pointed, and every other test stays green.
     "a public address is refused before a socket is opened", 2),
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
     "the service display names are the ones a user reads", 2),

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
     "a cast device is built from the sonos root and knows it is a sonos", 10),
    ("discovery/anything-is-castable", DISCOVERY_DEVICE,
     "        }\n        ?: return null\n",
     '        }\n        ?: (root to UpnpService("", "", descriptionUrl, null))\n',
     # The other direction: a NAS, a router's UPnP IGD and Sonos's own MediaServer all answer SSDP
     # and none of them can be cast to. Letting one through fails at SetAVTransportURI with UPnP
     # error 401, long after the user chose it.
     "a device with no AVTransport anywhere is not a cast device", 12),
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

    # ---- Plan 5 Task 6: search, and what a spoken query plays --------------------------------
    #
    # The ordering claim first. "Books rank above music in a search, and that is not a tie-break"
    # is an ORDER, and a test that asserts a result set's contents cannot see a wrong order -- the
    # exact defect this repository has shipped before. These two probes are the two ways the order
    # gets broken: the groups reordered, and one group hoisted to the front.
    ("browse/search-books-not-first", BROWSE_TREE,
     "    bookNodes(books) + artistChildren(albums) + artistNodes(artists) + songNodes(songs)",
     "    artistChildren(albums) + bookNodes(books) + artistNodes(artists) + songNodes(songs)",
     "search results are books, then albums, then artists, then tracks", 3),
    ("browse/search-tracks-first", BROWSE_TREE,
     "    bookNodes(books) + artistChildren(albums) + artistNodes(artists) + songNodes(songs)",
     "    songNodes(songs) + bookNodes(books) + artistChildren(albums) + artistNodes(artists)",
     "search results are books, then albums, then artists, then tracks", 2),
    # A group silently dropped. `allMatch`/`contains` over the remaining rows is satisfied by this;
    # only an exact ordered list is not.
    ("browse/search-drops-the-artists", BROWSE_TREE,
     "    bookNodes(books) + artistChildren(albums) + artistNodes(artists) + songNodes(songs)",
     "    bookNodes(books) + artistChildren(albums) + songNodes(songs)",
     "search results are books, then albums, then artists, then tracks", 1),
    # NOT PROBED, and worth saying rather than leaving as a gap: "the search slot calls the albums
    # *tab* builder" cannot be probed here. `albumsNodes(emptyList(), albums)` is `artistChildren`
    # exactly -- the shuffle rows come from the libraries argument, and an empty one emits none --
    # so the mutation is the identity and no test can go red on it. What the wrong builder would
    # really cost is a "Shuffle Music" row at the top of a car's search results, and that is
    # asserted where the libraries are real: `BrowseSearchBrowserTest.noShuffleRowIsEverASearchResult`
    # on the device tier, which this runner cannot reach (see the header).

    # ---- what a spoken query plays -------------------------------------------------------------
    #
    # Three tiers, each of which has to beat the next. Each probe below demotes exactly one of
    # them, which is the only way to tell "exact match wins" from "the first playable wins" -- on a
    # fixture where they agree, both are the same observation and only one is being tested.
    ("browse/spoken-no-exact-tier", PLAY_FROM_SEARCH,
     "    title == wanted -> EXACT",
     "    title == wanted -> ANYTHING_PLAYABLE",
     "an exact title match wins over an earlier partial match", 4),
    ("browse/spoken-no-containment-tier", PLAY_FROM_SEARCH,
     "    title.contains(wanted) -> CONTAINS",
     "    title.contains(wanted) -> ANYTHING_PLAYABLE",
     "a partial match wins over the first playable node", 3),
    # The plan's mutation 3, and the one a reviewer is most likely to call a fix: "if nothing
    # matched, return nothing". A car that answers a spoken request with silence has done nothing,
    # and the app is what gets blamed.
    ("browse/spoken-nothing-matches-plays-nothing", PLAY_FROM_SEARCH,
     "    return playable.sortedBy { tierOf(normalise(it.title), wanted) }",
     "    return playable.filter { tierOf(normalise(it.title), wanted) != ANYTHING_PLAYABLE }",
     "a query that matches nothing still plays something", 2),
    ("browse/spoken-blank-query-plays-nothing", PLAY_FROM_SEARCH,
     "    if (query.isBlank()) return playable",
     "    if (query.isBlank()) return emptyList()",
     "an empty query plays the first playable node", 2),
    # Containment read the other way round. On a symmetric fixture the two are the same function;
    # `PlayFromSearchTest` carries a query that is a strict substring of one title and a title that
    # is a strict substring of nothing, so they are not.
    ("browse/spoken-containment-reversed", PLAY_FROM_SEARCH,
     "    title.contains(wanted) -> CONTAINS",
     "    wanted.contains(title) -> CONTAINS",
     "containment is on the node's title and not on the query", 3),
    # A browsable-only row handed to a caller that will try to play it.
    ("browse/spoken-ranks-browsable-nodes", PLAY_FROM_SEARCH,
     "    val playable = nodes.filter(BrowseNode::isPlayable)",
     "    val playable = nodes",
     "a browsable-only node is never picked", 3),
    # `normalise` is what makes a punctuated, mis-spaced, lower-cased recogniser transcript reach
    # the right book at all. Both halves of it, separately.
    ("browse/normalise-drops-digits", PLAY_FROM_SEARCH,
     "      .map { if (it.isLetterOrDigit()) it else ' ' }",
     "      .map { if (it.isLetter()) it else ' ' }",
     "normalise keeps digits and collapses every kind of whitespace", 1),
    ("browse/normalise-keeps-empty-tokens", PLAY_FROM_SEARCH,
     "      .split(\" \")\n      .filter(String::isNotEmpty)\n      .joinToString(\" \")",
     "      .split(\" \")\n      .joinToString(\" \")",
     "normalise is the exact transformation the tiers compare on", 3),
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
     # Task 5's knob. It is NOT one of the strictness switches this probe turns off -- it models an
     # SCPD that lies, and `null` is the honest device -- but it sits inside the block this probe
     # matches, so it has to be carried through both halves or the preflight aborts the whole list.
     "    /**\n"
     "     * What the SCPD **advertises**, when that differs from what the device actually accepts.\n"
     "     *\n"
     "     * `null` -- the default -- means the two agree, which is the honest device. Setting it models\n"
     "     * **an SCPD that lies**, which is the reason `UpnpRenderer.seek` catches `710` at all despite\n"
     "     * reading the capability first: firmware has advertised modes it then refuses. Without this\n"
     "     * knob the two lists cannot disagree, and the `710` arm of that catch is unreachable from any\n"
     "     * test -- which is a strictness the fake would be claiming and not providing.\n"
     "     */\n"
     "    val declaredSeekModes: List<String>? = null,\n"
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
     "    val declaredSeekModes: List<String>? = null,\n"
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
    # ---- Plan 5 Task 4: paging, and the extras a car head unit reads -------------------------
    # Every count below was measured by applying the mutation alone against the committed tree and
    # reading `core/model/build/test-results/test/TEST-*.xml`; see task-4-report.md for the
    # transcript. The rest of Task 4 -- `BrowseItems`, `MuPlayLibraryCallback`,
    # `BrowseTreeRepository`, `MirrorBookshelf`, `BookProgress` -- has NO probe here and cannot have
    # one: every test of those lives in `connectedDebugAndroidTest`, which this runner cannot reach,
    # and an androidTest-only mutation reports MISSED with zero failures because androidTest sources
    # are not inputs to the JVM test task (this file's own header, "AND A HARDER LIMIT THAN
    # production code only"). Thirty-five such mutations were applied by hand against the emulator
    # instead and the transcript is in task-4-report.md -- the same treatment Plan 3 Tasks 6 and 8b
    # gave theirs. THREE of them survived the suite as first written, which is what those by-hand
    # runs bought.
    #
    # `page` is four lines, and its whole job is the arithmetic a car's `Int.MAX_VALUE` page size
    # breaks. Two of its probes are worth reading twice: at page 1 with that size the Int product is
    # -2147483648, and a result LONGER than pageSize is not an error a controller sees but a process
    # death -- `MediaLibrarySessionImpl.verifyResultItems` throws on the session's own handler.
    ("browse/page-ignores-the-page", BROWSE_PAGING,
     "    if (page < 0 || pageSize <= 0) return emptyList()\n",
     "    if (page < 0 || pageSize <= 0) return emptyList()\n    if (true) return items\n",
     "pages divide the list and the last page is short", 4),
    ("browse/page-int-overflow", BROWSE_PAGING,
     "    val from = page.toLong() * pageSize.toLong()",
     "    val from = (page * pageSize).toLong()",
     "a page size of Int MAX_VALUE returns everything and does not overflow", 1),
    ("browse/page-no-upper-clamp", BROWSE_PAGING,
     "    val to = minOf(from + pageSize.toLong(), items.size.toLong())",
     "    val to = from + pageSize.toLong()",
     "pages divide the list and the last page is short", 4),
    ("browse/page-negative-page-accepted", BROWSE_PAGING,
     "    if (page < 0 || pageSize <= 0) return emptyList()",
     "    if (pageSize <= 0) return emptyList()",
     "a nonsensical page or size is empty rather than a crash", 1),
    # Order is a property: a `page` over a Set, or one that reversed its slice, satisfies every size
    # assertion in that file and only this one.
    ("browse/page-reorders-within-a-page", BROWSE_PAGING,
     "    return items.subList(from.toInt(), to.toInt())",
     "    return items.subList(from.toInt(), to.toInt()).reversed()",
     "paging preserves order within a page", 4),
    # The extras are a contract with software this project does not own. Each of these is a value a
    # car reads and nothing else in this build inspects.
    ("browse/extras-style-is-a-constant", BROWSE_EXTRAS,
     "      put(CONTENT_STYLE_BROWSABLE, styleValue(node.childStyle))",
     "      put(CONTENT_STYLE_BROWSABLE, STYLE_GRID)",
     "a browsable node carries its own child style and a list style for its playables", 2),
    ("browse/extras-leaf-gets-style-hints", BROWSE_EXTRAS,
     "    if (node.isBrowsable) {", "    if (true) {",
     "a playable leaf carries no style extras at all", 2),
    # Sending a percentage alongside NOT_PLAYED draws an empty progress pip on every unheard book,
    # which is a wrong answer no coverage counter can see -- the branch runs either way.
    ("browse/extras-percentage-always-present", BROWSE_EXTRAS,
     "      if (completion.status == BrowseCompletionStatus.PARTIALLY_PLAYED) {\n        put(COMPLETION_PERCENTAGE, completion.fraction)\n      }",
     "      put(COMPLETION_PERCENTAGE, completion.fraction)",
     "only a partially played item carries a percentage", 1),
    ("browse/extras-percentage-is-a-constant", BROWSE_EXTRAS,
     "        put(COMPLETION_PERCENTAGE, completion.fraction)",
     "        put(COMPLETION_PERCENTAGE, 0.5)",
     "the percentage is the node's own fraction and not a constant", 3),
    ("browse/extras-status-is-a-constant", BROWSE_EXTRAS,
     "      put(COMPLETION_STATUS, statusValue(completion.status))",
     "      put(COMPLETION_STATUS, STATUS_PARTIALLY_PLAYED)",
     "only a partially played item carries a percentage", 1),
    ("browse/extras-root-ignores-the-surface", BROWSE_EXTRAS,
     "    CONTENT_STYLE_BROWSABLE to styleValue(surface.browsableStyle),",
     "    CONTENT_STYLE_BROWSABLE to STYLE_GRID,",
     "the root advertises content style support and the surface's own default", 1),
    # The wire keys themselves. A re-valued key is invisible to every other assertion in that file,
    # because they all reference the constant -- which is exactly why one test asserts the literals,
    # and why this probe reddens exactly one test rather than several.
    ("browse/extras-browsable-key-swapped", BROWSE_EXTRAS,
     '  const val CONTENT_STYLE_BROWSABLE: String = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"',
     '  const val CONTENT_STYLE_BROWSABLE: String = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"',
     "the wire keys and values are exactly the ones Android Auto documents", 1),
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

    # ---- Plan 6 Task 5: AVTransport, RenderingControl, and the Sonos quirks -------------------
    #
    # The defect this task is written against is the one named in the plan's own defect-class
    # section: *"a SOAP test that asserts a request was sent"*. `assertThat(fake.soapRequests)
    # .hasSize(1)` is green against an unquoted SOAPACTION, arguments in the wrong order, doubly
    # escaped metadata and a URL with no extension -- every one of which a real Sonos rejects. So
    # every probe below is a mutation whose ONLY visible effect is which bytes reached the device,
    # and every named test reads those bytes back off `FakeRenderer`'s recording rather than off a
    # convenience object.
    #
    # Every count here was MEASURED, one mutation at a time, against `:core:cast:test` before it was
    # written down -- not predicted. The four with a count above one are the honest ones: a
    # mutation that reddens a neighbour as well is still a precise discrimination if the named
    # test is the one that names the defect, and the count is what notices when that stops being
    # true. Four further mutations were run and are NOT in this list because they redden 20+ tests
    # each (`INSTANCE_ID` -> "1"; a `followedCoordinator` that always fires; pre-escaping the
    # metadata; swapping `CurrentURI` and `CurrentURIMetaData`) -- a count that large is drift
    # waiting to happen, and their transcripts are in task-5-report.md instead.

    # QUIRK 1. `TransportPlaySpeed` is an argument of `Play` and its allowed value list is `{"1"}`
    # on every renderer this plan targets. Sonos answers 402 when it is missing and **717 Play speed
    # not supported** for anything else -- which is why the fake sends a code rather than a plain
    # 500, and why this probe's transcript reads `UPnP error 717`. It is also the shape a future
    # "just pass the book's 1.3x speed through" change would take, and this is what stops it.
    ("control/play-speed-not-one", CONTROL_RENDERER,
     'const val PLAY_SPEED: String = "1"',
     'const val PLAY_SPEED: String = "1.0"',
     "play sends speed 1 and moves the device into PLAYING", 3),

    # QUIRK 3, and the reason the SCPD is READ rather than tried. A `seek` that hardcodes REL_TIME
    # passes every seek assertion against a device that accepts it, and produces `710` on the first
    # drag of the bar against an ABS_TIME-only one -- while ALSO sending a Seek to a device that
    # declared it cannot seek by time at all, which is the "offer a control that silently fails"
    # defect this whole plan is written against.
    ("control/seek-mode-hardcoded", CONTROL_RENDERER,
     "    val mode = capabilities().preferredSeekMode ?: return false",
     "    val mode = RendererCapabilities.REL_TIME",
     "a device that only accepts ABS_TIME is seeked with ABS_TIME", 2),

    # ...and the other half of that decision: an SCPD can lie, so `710` and `711` are still caught
    # and answered `false`. Removing the catch turns a dragged progress bar into an exception the
    # `Player` above has to interpret, which is a session torn down over a seek.
    ("control/seek-refusal-not-caught", CONTROL_RENDERER,
     "      if (refused.fault.errorCode in SEEK_REFUSALS) false else throw refused",
     "      throw refused",
     "a seek past the end returns false rather than throwing", 2),

    # The seek TARGET, which is the value a status assertion cannot see: a `Seek` that always aims
    # at the same place is answered 200 by every renderer there is.
    ("control/seek-target-is-a-constant", CONTROL_RENDERER,
     '          SoapArgument("Target", UpnpTime.formatClock(positionMs)),',
     '          SoapArgument("Target", UpnpTime.formatClock(83_000L)),',
     "a second seek lands somewhere else", 1),

    # QUIRK 4, and the one that is a scope decision. A Sonos grouped in the Sonos app ACCEPTS
    # `SetAVTransportURI` and plays nothing, because it keeps following its coordinator. Without the
    # check the cast succeeds at every layer and the user hears silence with no explanation -- which
    # is why detecting it is in scope even though fixing it is not.
    ("control/no-rincon-check", CONTROL_RENDERER,
     "    positionInfo().followedCoordinator?.let { throw RendererFollowsAnotherException(it) }",
     "    positionInfo()",
     "a sonos following another speaker is detected and named", 1),

    # `x-rincon:` is stated ONCE, in `PositionInfo`, and `UpnpRenderer` asks the property rather than
    # carrying a second copy of the prefix. Loosening the prefix to `x-` -- the plausible way to get
    # this wrong -- calls a line-in source (`x-rincon-stream:`) and an SMB file (`x-file-cifs:`) group
    # followers, and this app then refuses to cast to a speaker that is perfectly free.
    #
    # A `contains`-instead-of-`startsWith` probe was WRITTEN HERE FIRST AND REMOVED, because it was
    # run and came back MISSED with zero failures in the whole suite. That is not a gap in the test:
    # `contains` and `startsWith` can only differ for a `TrackURI` carrying `x-rincon:` somewhere
    # other than at its start, and no value a renderer produces looks like that. The mutation was
    # undetectable because it is not a defect, which is a different thing from an assertion that
    # cannot fail -- and worth writing down, because the next person to reach for it will reach for
    # `contains` too.
    ("control/rincon-prefix-too-loose", CONTROL_STATE,
     "  val followedCoordinator: String? get() = trackUri?.takeIf { it.startsWith(FOLLOW_SCHEME) }",
     '  val followedCoordinator: String? get() = trackUri?.takeIf { it.startsWith("x-") }',
     "a follower is recognised by its scheme and nothing else is", 1),

    # `UNKNOWN` folded into `STOPPED`. Task 8 reads `STOPPED` after `PLAYING` as "the track ended,
    # advance" -- so a renderer sending a state this enum has not seen would skip a track, every
    # time, silently. Both arms of the `when` still execute under the mutation, so no coverage
    # number moves.
    ("control/unknown-reads-as-stopped", CONTROL_STATE,
     "      else -> UNKNOWN",
     "      else -> STOPPED",
     "an unrecognised or missing value is UNKNOWN and not STOPPED", 1),

    # `CurrentTransportStatus` is a SECOND state variable, and a renderer that could not fetch or
    # decode what it was given answers `ERROR_OCCURRED` there while `CurrentTransportState` still
    # reads an ordinary `STOPPED`. Hardcoding `hasError = false` turns "the speaker refused these
    # bytes" into "the track finished", which is a queue that advances past a track nobody heard.
    ("control/transport-error-ignored", CONTROL_STATE,
     "      hasError = STATUS_ERROR_OCCURRED.equals(status.orEmpty().trim(), ignoreCase = true),",
     "      hasError = false,",
     "a device reporting ERROR_OCCURRED is distinguished from one that merely stopped", 2),

    # The volume clamp. A slider's rounding must not become a `402`, and the fake answers exactly
    # that to anything outside 0..100 -- as real hardware does.
    ("control/volume-not-clamped", CONTROL_RENDERER,
     '        SoapArgument("DesiredVolume", level.coerceIn(MIN_VOLUME, MAX_VOLUME).toString()),',
     '        SoapArgument("DesiredVolume", level.toString()),',
     "a volume outside 0 to 100 is clamped rather than sent and refused", 1),

    # `RenderingControl` actions must carry `RenderingControl`'s service type, in the SOAPACTION and
    # in the envelope's `xmlns:u`. A copy-paste of the transport's is a `401` on a conformant device
    # and is invisible to every assertion about arguments -- this fake accepts it, which is exactly
    # why the assertion is on the raw header value rather than on the answer.
    ("control/rendering-uses-transport-service", CONTROL_RENDERER,
     "    soap.invoke(controlUrl, DeviceDescription.SERVICE_RENDERING_CONTROL, action, arguments)",
     "    soap.invoke(controlUrl, DeviceDescription.SERVICE_AV_TRANSPORT, action, arguments)",
     "volume is read and written, and the value that comes back is the one that went in", 1),

    # Reading the wrong out-argument out of a right answer. `GetVolume` answers `CurrentVolume`;
    # `CurrentMute` is a real argument name on the same service, so this is the neighbouring-field
    # defect rather than a typo, and it renders as a volume slider that is always at zero.
    ("control/volume-reads-the-mute-argument", CONTROL_RENDERER,
     '    )["CurrentVolume"]?.toIntOrNull()',
     '    )["CurrentMute"]?.toIntOrNull()',
     "volume is read and written, and the value that comes back is the one that went in", 2),

    # `SetNextAVTransportURI` is called only where the device DECLARED it. Calling it anyway returns
    # `401` and, on some firmware, clears the queue that is already playing -- in the middle of an
    # album, which is the worst possible moment.
    ("control/setnext-capability-ignored", CONTROL_RENDERER,
     "    if (!capabilities().supportsSetNextUri) return",
     "    if (false) return",
     "a device that declares no such action is never asked, rather than asked and refused", 1),

    # ...and the value it carries. A `NextURI` that is always empty queues nothing while answering
    # 200, which is a gap between every pair of tracks and nothing in a log.
    ("control/next-uri-is-a-constant", CONTROL_RENDERER,
     '        SoapArgument("NextURI", item?.resourceUrl.orEmpty()),',
     '        SoapArgument("NextURI", ""),',
     "a device that declares SetNextAVTransportURI is given the next track, in order", 1),

    # The SCPD read, cached once per renderer. Re-fetching adds an HTTP round trip to every drag of
    # the seek bar, and the ONLY place that is visible is the device's own request count -- an
    # identity check on the returned object goes green against a client that re-fetches and memoises
    # the second answer.
    ("control/capabilities-refetched", CONTROL_RENDERER,
     "    cachedCapabilities ?: loadCapabilities().also { cachedCapabilities = it }",
     "    loadCapabilities()",
     "capabilities are fetched once and not on every seek", 1),

    # ORDER IS A PROPERTY, here as in the three other places this plan says so. `preferredSeekMode`
    # falls back to the device's own first choice, so a sorted `allowedValueList` silently changes
    # which mode an ABS_TIME-and-TRACK_NR device gets asked for.
    ("control/scpd-order-sorted", CONTROL_CAPS,
     "      val modes = ALLOWED_VALUE.findAll(block.groupValues[1]).map { it.groupValues[1] }.toList()",
     "      val modes = ALLOWED_VALUE.findAll(block.groupValues[1]).map { it.groupValues[1] }.toList().sorted()",
     "the declared seek modes are read, in the order the device declared them", 1),

    # ---- Plan 6 Task 7: the routing decision, and the silence that is the answer --------------
    #
    # The defect this family exists against is the one the task's own deliverable names: *"the
    # renderer either fetched from the phone or the app says out loud that it could not"*. Every
    # mutation below leaves a cast looking exactly as successful as a working one -- `Play` returns
    # 200, the UI says "Playing on Kitchen" -- and produces silence. No coverage floor can see any
    # of them: each one leaves every branch in the file executing, in both directions.
    #
    # Every count was MEASURED against `:core:cast:test`, one mutation at a time, before it was
    # written down.

    # THE PROBE THIS TASK EXISTS FOR, and the one that restores the silent failure. `confirm` that
    # always says yes is a router that never proves anything: the renderer is handed a URL it
    # cannot reach, the session reports success, and nothing ever comes out of the speaker.
    ("route/proof-always-succeeds", ROUTE_ROUTER,
     "    if (proxy.awaitRequest(route.media.token, proofTimeoutMs)) return route",
     "    if (proxy.awaitRequest(route.media.token, proofTimeoutMs) || true) return route",
     "a renderer that cannot reach the phone is Unroutable when direct is not allowed", 6),

    # The other direction, so the branch is a discrimination and not a constant: a `confirm` that
    # always says no fails every cast that would have worked.
    ("route/proof-always-fails", ROUTE_ROUTER,
     "    if (proxy.awaitRequest(route.media.token, proofTimeoutMs)) return route",
     "    if (proxy.awaitRequest(route.media.token, proofTimeoutMs) && false) return route",
     "a renderer that fetches confirms the proxied route", 5),

    # The two fallbacks swapped. Each test still sees a route object of *some* kind, the enum is
    # still populated, and the user is told the opposite of what happened -- or, worse, handed
    # their Subsonic credentials to a speaker they never agreed to give them to.
    ("route/fallback-branches-swapped", ROUTE_ROUTER,
     "    return if (allowRendererDirect) {",
     "    return if (!allowRendererDirect) {",
     "a renderer that cannot reach the phone is Unroutable when direct is not allowed", 5),

    # The proof waits on THIS route's token. Keyed on a constant instead, a stale request from the
    # previous track confirms the current one -- which is the silent failure again, arriving one
    # track later and looking like a random skip.
    ("route/proof-matches-any-token", PROXY_SERVER,
     "    fetchLatches.computeIfAbsent(token) { CountDownLatch(1) }",
     '    fetchLatches.computeIfAbsent("any") { CountDownLatch(1) }',
     "the proof waits for this renderer's own token and not for any request at all", 2),

    # The partial byte. A /22 or a /26 is ordinary on a real network, and a byte-wise-only
    # comparison answers "same subnet" for addresses that are not -- which takes the fast path,
    # which skips the proof, for exactly the devices the proof was needed for.
    ("route/partial-byte-prefix-ignored", ROUTE_SUBNET,
     "    if (remainingBits == 0) return true",
     "    if (remainingBits >= 0) return true",
     "a prefix that is not a whole number of bytes is handled", 2),

    # The fast path asked about one address twice. `sameSubnet(x, x)` is true for every device on
    # earth, so the proof is switched off globally while the parameter, the branch and the coverage
    # all still look exactly like a working optimisation.
    ("route/fast-path-compares-one-address-twice", ROUTE_ROUTER,
     "      proofRequired = !sameSubnetFastPath(phoneAddress, rendererAddress),",
     "      proofRequired = !sameSubnetFastPath(rendererAddress, rendererAddress),",
     "the fast path is asked about this phone's address and the renderer's, in that order", 1),

    # An IPv6 phone address without its brackets. `http://fd00:0:0:0:0:0:0:1:PORT/media/x.mp3` has
    # a null host -- measured -- so the renderer fetches nothing and the cast plays silence. Every
    # test on an IPv4 test bed stays green.
    ("route/ipv6-host-not-bracketed", ROUTE_ROUTER,
     '      return if (address is Inet6Address) "[$literal]" else literal',
     "      return literal",
     "an ipv6 phone address is bracketed and unscoped, so the url a renderer is handed parses", 1),

    # Publishing before the route is known mints a capability for a device that cannot fetch it --
    # a token left on the LAN with no owner, serving Navidrome-authenticated audio, for a cast that
    # never started. The returned route is identical either way.
    ("route/publishes-before-it-knows-there-is-a-route", ROUTE_ROUTER,
     "    val phoneAddress = localAddress(rendererAddress)\n"
     '      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "this phone has no route to it")\n'
     "\n"
     "    val media = registry.publish(upstreamUrl, served)",
     "    val media = registry.publish(upstreamUrl, served)\n"
     "    val phoneAddress = localAddress(rendererAddress)\n"
     '      ?: return unroutable(device, UnroutableReason.NO_ROUTE_TO_RENDERER, "this phone has no route to it")',
     "a renderer with no route from this phone is Unroutable before anything is published", 1),

    # ---- Plan 6 Task 8: the session over the renderer, and the renderer that disappears -------
    # Every count below was measured by applying the mutation to a committed tree and reading the
    # result XML. `core/cast` is already on `revert()`'s checkout line, wholesale, so these three
    # new files need no entry of their own -- which is exactly why that line names the directory.
    #
    # The first four are the disappearing-speaker branch, which is the whole reason Task 8 exists:
    # spec section 6 says playback STOPPING when the speaker goes away is intended behaviour, and
    # playback APPEARING TO CONTINUE is not.
    ("session/lost-position-zeroed", SESSION_SESSION,
     "CastSessionState.Lost(deviceName, positionMs, queue.getOrNull(index)?.mediaId),",
     "CastSessionState.Lost(deviceName, 0L, queue.getOrNull(index)?.mediaId),",
     # **The mutation that loses a listener's place in a book.** Everything else about the session
     # still behaves: it is declared lost, an error is reported, the clock stops. Only the number
     # Task 9 resumes from is wrong, and a suite that asserted "a Lost was emitted" would not care.
     "a renderer that disappears mid-stream ends the session with the last known position", 1),
    ("session/lost-clock-keeps-running", SESSION_SESSION,
     '    failure = CastFailure(CastFailureKind.RENDERER_UNREACHABLE, "$deviceName stopped responding")\n'
     "    playWhenReady = false\n    transport = TransportState.NO_MEDIA\n    publish()",
     '    failure = CastFailure(CastFailureKind.RENDERER_UNREACHABLE, "$deviceName stopped responding")\n'
     "    publish()",
     # The other half, and the half a `Lost` state alone does not give you: the session reports the
     # loss AND freezes the reported position. Without it the seek bar runs to the end of a track
     # nobody can hear and a progress writer records a position that was never played.
     "a renderer that disappears mid-stream stops the clock instead of playing on forever", 1),
    ("session/lost-after-one-failure", SESSION_SESSION,
     "const val LOST_AFTER_FAILURES: Int = 3", "const val LOST_AFTER_FAILURES: Int = 1",
     # THIS PROBE REPORTED **MISSED** THE FIRST TIME IT WAS RUN, and the answer it recorded is a
     # test-design one rather than a product one. The test withheld `LOST_AFTER_FAILURES - 1`
     # answers from the fake, so at a threshold of 1 it withheld none, nothing failed, and the whole
     # suite stayed green against the mutation the test existed to catch. A test parameterised by
     # the constant under test moves with it and can never fail. It withholds a literal two now.
     "two consecutive missed polls do not end the session, and a good poll resets the count", 1),
    ("session/refusal-counts-as-silence", SESSION_SESSION,
     "      transportFailures = 0\n    } catch (unreachable: SoapTransportException) {",
     "      transportFailures += 1\n      if (transportFailures >= LOST_AFTER_FAILURES) lose()\n"
     "    } catch (unreachable: SoapTransportException) {",
     # Task 5 keeps `UpnpErrorException` and `SoapTransportException` apart so the poll can tell
     # "the speaker said no" from "the speaker is gone". Collapsed, one 501 on some firmware tears
     # down every session three seconds in.
     "a upnp error from a poll is not mistaken for a dead speaker", 1),

    # The end of a track, and the thing that only looks like it.
    ("session/stopped-always-advances", SESSION_SESSION,
     "if (previous == TransportState.PLAYING && transport == TransportState.STOPPED && reachedTheEnd) {",
     "if (previous == TransportState.PLAYING && transport == TransportState.STOPPED) {",
     # A renderer reports STOPPED for "finished" and for "somebody pressed stop on the speaker".
     # Read the same way, a track is skipped every time a listener touches the hardware. Two
     # failures, because `a track whose length this app does not know never declares itself
     # finished` is the same branch reached through a zero duration.
     "a stop that is not the end of the track does not skip to the next one", 2),
    ("session/transport-error-swallowed", SESSION_SESSION,
     "      if (info.hasError) {", "      if (info.hasError && queue.isEmpty()) {",
     # `CurrentTransportStatus = ERROR_OCCURRED` arrives in a DIFFERENT out-argument from the state,
     # usually beside an ordinary STOPPED. Swallowed, it is a track that never starts and never
     # fails, with nothing reported anywhere.
     "a renderer reporting ERROR_OCCURRED becomes a reported failure and not a silent stall", 1),

    # Three defects this task found in itself, each fixed and each pinned here.
    ("session/route-proved-before-play", SESSION_SESSION,
     "    if (playWhenReady) {\n      if (!proveRoute(loaded)) return\n    } else {\n"
     "      unprovedRoute = loaded\n    }",
     "    if (!proveRoute(loaded)) return",
     # `CastRouter.confirm` proves a route by waiting for the renderer to FETCH, and a renderer
     # fetches after `Play`. `SimpleBasePlayer` sets the queue before it sets playWhenReady, so
     # proving at load time sits out the whole proof timeout and then calls a good speaker
     # unroutable. The count is 25 and not 1 on purpose: nothing can play at all under this
     # mutation, so the count here is a measurement of the blast radius rather than a discrimination
     # claim -- if it goes stale because `:core:cast` gained tests, re-measure it, do not delete it.
     "a queue set while paused is not declared unroutable for never having been fetched", 25),
    ("session/failed-session-still-accepts-commands", SESSION_SESSION,
     "private fun refusing(): Boolean = released || failure != null",
     "private fun refusing(): Boolean = released",
     # Measured while writing the grouped-Sonos test: `SetAVTransportURI` failed with "this speaker
     # is grouped with another and is following ...", the `play()` that followed failed with `701
     # Transition not available` because nothing had been loaded, and the second message REPLACED
     # the first. What a user would have been shown is the symptom of the symptom.
     "a failed session ignores later commands rather than replacing the diagnosis with its symptoms", 1),
    ("session/state-change-not-announced", SESSION_SESSION,
     "    onPlaybackChanged()\n  }", "  }",
     # Task 9 passes `SimpleBasePlayer::invalidateState` here and Media3 derives every listener
     # callback from the diff between the snapshots it then reads. A change that does not announce
     # itself is a callback that never fires -- for a book cast to a speaker, a position never
     # recorded, discovered by a listener losing their place.
     "every change to the snapshot is announced, and the announcements carry the changes", 1),
    ("session/release-skips-revoke-when-gone", SESSION_SESSION,
     "      } catch (unreachable: IOException) {", "      } catch (unreachable: NullPointerException) {",
     # The commonest reason a session is released is that the speaker went away, so a `Stop` that
     # throws must not be allowed to skip `CastRouter.revokeAll` -- a proxy still serving after its
     # session has ended is a capability lying on the LAN with nobody watching it.
     "a session released after the renderer has gone still revokes its tokens", 1),

    # The seek bar between two polls, and the credential that must not be printed.
    ("session/no-extrapolation", SESSION_PLAYBACK,
     "positionMs + (nowMs - positionMeasuredAtMs).coerceAtLeast(0L)", "positionMs + 0L",
     # Six failures: the position supplier is read by both the pure test and three session cases.
     "the position extrapolates between polls", 6),
    ("session/seek-always-advertised", SESSION_SESSION,
     "canSeek = renderer.capabilities().preferredSeekMode != null", "canSeek = true",
     # A device whose SCPD offers no time seek mode must show NO seek bar, rather than one that
     # answers 710 to every drag. Task 9 withholds `COMMAND_SEEK_*` on this flag.
     "a device that cannot seek by time does not report that it can, and one that can does", 1),
    ("session/upstream-url-printed", SESSION_SOURCE,
     "upstreamUrl=$REDACTED_UPSTREAM", "upstreamUrl=$upstreamUrl",
     # `:core:model`'s `SubsonicCredentials` and `:integrations`' Lidarr key have the same probe for
     # the same reason: a `data class` prints every field, and a `CastSource` lives in a list inside
     # a live session -- the shape that reaches a log line by accident. A Navidrome stream URL's
     # `t` and `s` are password equivalents.
     "a source does not print its credential-bearing upstream url", 1),

    # ---- Plan 6 Task 9: the handover -----------------------------------------------------------
    # Every count below was measured by applying the mutation to a committed tree and reading the
    # result XML, one at a time, with the file's bytes snapshotted in memory and written back.
    #
    # WHAT IS NOT HERE, AND WHY. The handover's headline behaviour -- casting mid-song lands on the
    # same second -- lives on the DEVICE tier, in `:core:media`'s `HandoverTest` and
    # `UpnpPlayerTest`, and this runner cannot see an androidTest source at all: it reports MISSED
    # with zero failures, because an androidTest file is not an input to the JVM test task and the
    # cache key therefore does not move (this file's own header records the same limit from Plan 3
    # Task 7b). Six device-tier mutations were applied by hand instead and their transcripts are in
    # task-9-report.md, which is the same treatment `LiveNavidromeTest`'s probes get.
    #
    # What IS here is everything the fast tier can hold: the one-shot decorator, which takes media
    # ids and an index and touches no Android type, and the two Hilt bindings whose failure mode is
    # silent.
    ("handover/target-never-spent", HANDOVER_POLICY,
     "val pending = armed.getAndSet(null)", "val pending = armed.get()",
     # A target that survived one `resolve` would make the next shuffle, the next album and the next
     # book all start where the last handover was. Three failures, because "the target is spent" is
     # asserted from three directions: after it is used, after it misses its queue, and after an
     # empty queue clears it.
     "an armed target wins over the delegate, once", 3),
    ("handover/missing-id-applied", HANDOVER_POLICY,
     "    if (index < 0) return delegate.resolve(mediaIds, requestedIndex)",
     "    if (index < -1) return delegate.resolve(mediaIds, requestedIndex)",
     # A handover whose queue changed between arming and setting. Applying the target anyway starts
     # an unrelated track partway in -- the silent-wrong-answer class, and the exact failure this
     # architecture exists to prevent for books.
     "an armed target for a media id that is not in the new queue is discarded", 2),
    ("handover/armed-index-used", HANDOVER_POLICY,
     "return ResumeTarget(startIndex = index, startPositionMs = pending.target.startPositionMs)",
     "return ResumeTarget(startIndex = pending.target.startIndex, "
     "startPositionMs = pending.target.startPositionMs)",
     # The media id is the identity; the index is not. A handover can re-fetch an album or
     # regenerate a shuffle, and the armed index then names a different track.
     "the armed index is corrected to where the media id actually is in the new queue", 1),
    ("handover/decorator-not-singleton", MEDIA_MODULE,
     "  @Provides\n  @Singleton\n  fun provideOneShotResumePolicy(",
     "  @Provides\n  fun provideOneShotResumePolicy(",
     # ONE MISSING ANNOTATION. Dagger hands `CastSessionManager` and `MuPlayerFactory` a decorator
     # each, over the same delegate; every outbound cast still works, and the return leg arms one
     # object and asks the other. No assertion about a position anywhere else in this project moves.
     "the decorator is a singleton, because two decorators over one delegate lose the return leg", 1),
    ("handover/undecorated-binding-wins", MEDIA_MODULE,
     "  fun provideResumePolicy(oneShot: OneShotResumePolicy): ResumePolicy = oneShot",
     "  fun provideResumePolicy(oneShot: OneShotResumePolicy): ResumePolicy = NeverResume",
     # The other half of the same silence: a graph with exactly one unqualified `ResumePolicy` --
     # which is what Hilt requires and what makes this compile -- and it is the WRONG one.
     "the unqualified resume policy the player factory receives IS the cast decorator", 1),
    ("handover/mime-guessed", SERVED_MEDIA,
     "      RAW_TYPES.values.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }",
     "      RAW_TYPES.values.firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }\n"
     "        ?: ServedMedia(FALLBACK_MIME, FALLBACK_EXTENSION)",
     # `forExtension` has the same probe for the same reason: this answers a question about what a
     # *peer* will believe, and "I do not know" is a different answer from "MP3". Guessing here
     # makes `audio/opus` -- the one format spec section 4 forbids outright -- agree with every MP3
     # stream this client serves.
     "forMimeType answers what must be served for a body already decided to be that MIME", 1),

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

    # ---- Plan 7 Task 4: the credential that must never reach a URL -----------------------------
    # THIS FAMILY EXISTS BECAUSE THE COVERAGE GATE CANNOT SEE ANY OF IT, and that was measured
    # rather than assumed. `LidarrAuthInterceptor` has **no branches at all** and its seven lines
    # run on any request whatsoever, so its 7/7 LINE floor stays green under every mutation below;
    # withholding all seven of `LidarrAuthTest`'s tests -- every assertion in this repository about
    # where a Lidarr API key goes -- also leaves that floor at 7/7 and both coverage gates GREEN.
    # A green `./gradlew check` is therefore no evidence at all about the key's placement. These
    # four probes and the named tests are the whole of it.
    #
    # 1. The key itself. A constant here authenticates against nothing and fails loudly in the
    #    field, but it is the shape that ships: a hardcoded value satisfies "the header is present"
    #    and this project has already shipped exactly that defect as `authParams() = emptyMap()`.
    ("integrations/lidarr-api-key-header", LIDARR_INT,
     '.header("X-Api-Key", apiKey)', '.header("X-Api-Key", "constant")',
     # 9, RE-MEASURED AT TASK 7 (was 6 at Task 5, 5 at Task 4). Every test that reads the header
     # back off a RecordedRequest reddens, so this count grows with every task that adds an
     # endpoint -- Task 7's `the queue and album progress requests carry the key only in the
     # header` and the two queue/album requests it drives are the seventh through ninth.
     # RE-MEASURE THIS WHENEVER A TASK ADDS A REQUEST. It has now been stale twice.
     "the header carries whichever key the client was given", 9),

    # 2. The key on the URL -- the defect this whole module is named for. Lidarr really does accept
    #    `?apikey=` (measured against 3.1.0.4875: it answers 200), so this is a live wrong path.
    #    Adding the query parameter while LEAVING the header in place is deliberate: every
    #    response assertion in the module still passes, because the request still authenticates.
    ("integrations/lidarr-api-key-on-url", LIDARR_INT,
     '.header("X-Api-Key", apiKey)',
     '.url(chain.request().url.newBuilder().addQueryParameter("apikey", apiKey).build()).header("X-Api-Key", apiKey)',
     # 7, RE-MEASURED AT TASK 7 (was 4 at Task 5, 2 at Task 4): `LidarrWiringTest`'s factory test
     # asserts the same negative one layer up; Task 5 added the four-endpoint scan and the lookup's
     # exact `encodedQuery`, which a smuggled parameter lengthens; Task 7 adds its own two-endpoint
     # scan, its exact `encodedQuery` pair, and its no-query-value-is-the-key loop.
     "no request this client makes carries the key on its url", 7),

    # 3. Content negotiation. `Startup.cs` sets `ReturnHttpNotAcceptable = true`; measured on
    #    3.1.0.4875, `Accept: application/xml` really is answered 406 (while *no* Accept header is
    #    answered 200). One place, so an endpoint Tasks 5-7 add cannot forget it.
    ("integrations/lidarr-accept-json", LIDARR_INT,
     '.header("Accept", "application/json")', '.header("Accept", "*/*")',
     # 3, RE-MEASURED AT TASK 7 (was 2 at Task 5, 1 at Task 4): Task 5's four-endpoint scan and
     # Task 6's two-endpoint one both assert every non-key header, so each new whole-request scan
     # adds one. Task 7's is the third.
     "every request declares that it accepts json", 3),

    # 4 and 5. Two mapped handshake fields, one representative of each risk: `appName` is the
    #    identity check that stops a Sonarr reading as a working Lidarr, and `urlBase` is the one a
    #    proxied install needs. A constant in either leaves the fixture-based test green, because
    #    the constant a lazy implementation reaches for is the fixture's own value.
    ("integrations/lidarr-appName", LIDARR_CLIENT,
     "appName = body.appName.orEmpty(),", 'appName = "Lidarr",',
     # 2, measured: also reddens `a status body with every optional field omitted maps to empty
     # strings`, which is the second observation of the same field at a third value.
     "status reads the values from the body, not from constants", 2),
    ("integrations/lidarr-urlBase", LIDARR_CLIENT,
     "urlBase = body.urlBase.orEmpty(),", 'urlBase = "",',
     "status reads the values from the body, not from constants", 1),

    # 6. The 401 message. Lidarr returns a byte-identical bare 401 for a wrong key and a missing
    #    one (measured: `Content-Length: 0`, no body, both cases), so a message claiming to know
    #    which is a guess presented to the user as a fact. No ratio moves under this mutation --
    #    the class is still constructed and still thrown.
    ("integrations/lidarr-401-overclaims", LIDARR_EXC,
     'Exception("Lidarr rejected this API key")',
     'Exception("Lidarr says this API key is incorrect")',
     "a 401 is an unauthorized failure whose message does not overclaim", 1),

    # 7. The 503 discriminator. A reverse proxy with no upstream answers 503 too, and mapping every
    #    503 to "starting up" tells a user to wait for a container that is not starting. Every arm
    #    still executes under the mutation, so the BRANCH floor over `LidarrClient` stays green.
    ("integrations/lidarr-503-collapsed", LIDARR_CLIENT,
     "503 -> if (isStartingUp(raw)) LidarrStartingUpException() else LidarrHttpException(503)",
     "503 -> LidarrStartingUpException()",
     # 2, RE-MEASURED AT TASK 5 (was 1). The second is `a lookup that fails upstream is a plain
     # http failure and not a starting-up one`, which is the same discrimination against a REAL
     # 503 -- `fixtures/lidarr/lookup-unavailable.json`, captured by blackholing api.lidarr.audio
     # inside the pinned container. Its body's key is `message`, not `errorMessage`, so this
     # mutation is what would turn "the metadata service is down" into "wait for your container".
     "a 503 starting-up body is its own failure, distinct from any other 503", 2),

    # ---- Plan 7 Task 5: the lookup, the two traps in it, and where an add is aimed -------------
    # Same argument as the family above: the coverage gate cannot see most of this. Every mutation
    # below leaves `LidarrClient`'s BRANCH floor and `LidarrAddTargets$Companion`'s 1.00 BRANCH
    # floor exactly where they are, because each one substitutes a constant for a value rather than
    # removing an arm -- which is the shape a lazy implementation actually ships.
    #
    # 1. The search term. A lookup that ignores its argument returns Lidarr's results for whatever
    #    the implementer was testing with, forever, and looks like a working search box.
    ("integrations/lidarr-lookup-term", LIDARR_CLIENT,
     "call { api.albumLookup(term) }", 'call { api.albumLookup("kind of blue") }',
     # 2, measured: the four-endpoint key scan pins the lookup's exact `encodedQuery` too.
     "the lookup sends whichever term it is given, url-encoded, to album slash lookup", 2),

    # 2. TRAP 1, and the sharpest defect in this task: `AlbumLookupController` sets `RemoteCover`
    #    and only `ArtistLookupController` sets `RemotePoster`. Measured against a real
    #    3.1.0.4875-ls40 lookup, an album element carries NO `remotePoster` key at all -- so this
    #    mutation yields null artwork on every row, with nothing reported anywhere.
    ("integrations/lidarr-remoteCover", LIDARR_CLIENT,
     'remoteCoverUrl = obj.string("remoteCover"),', 'remoteCoverUrl = obj.string("remotePoster"),',
     # 3, measured: also the real-fixture test and the two-values-per-field test, which both read
     # `remoteCoverUrl` back and get null under the mutation.
     "the cover comes from remoteCover and not from remotePoster", 3),

    # 3. The .NET `DateTime.MinValue` sentinel. Measured: an album whose release date Lidarr does
    #    not know sends `0001-01-01T00:00:00Z` rather than omitting the field, so dropping this
    #    `takeIf` prints "the year 1" at a user with no error anywhere.
    ("integrations/lidarr-releaseDate-sentinel", LIDARR_CLIENT,
     'obj.string("releaseDate")?.takeIf { !it.startsWith(DATE_TIME_MIN_VALUE) }',
     'obj.string("releaseDate")',
     "the real lookup body from a pinned lidarr maps as this client claims", 1),

    # 4. The identity guard. An element with no `foreignAlbumId` cannot be added -- `PostValidator`
    #    requires it -- so keeping it produces a row that looks fine and fails at the add.
    ("integrations/lidarr-candidate-needs-album-id", LIDARR_CLIENT,
     'val foreignAlbumId = obj.string("foreignAlbumId") ?: return null',
     'val foreignAlbumId = obj.string("foreignAlbumId").orEmpty()',
     "an element with no usable identity is skipped rather than crashing the list", 1),

    # 5. Two endpoints, not one. Quality and metadata profiles share a DTO and differ only by path,
    #    which is exactly the copy-paste a reviewer's eye slides over -- and the consequence is an
    #    add filed under a metadata profile id that is really a quality profile id.
    ("integrations/lidarr-metadataprofile-endpoint", LIDARR_API,
     '@GET("api/v1/metadataprofile")', '@GET("api/v1/qualityprofile")',
     # 2, measured: the four-endpoint key scan asserts the paths in order, so it sees this too.
     "quality and metadata profiles are two different endpoints and both are read", 2),

    # 6 and 7. Two of `LidarrAddTargets`'s pass-throughs, one representative of each risk: the
    #    path decides where the files land, and the profile id decides what gets downloaded. The
    #    constant a lazy implementation reaches for is the fixture's own value, so a single-folder
    #    test would stay green under both.
    ("integrations/lidarr-targets-rootpath-passthrough", LIDARR_TARGETS,
     "rootFolderPath = rootFolder.path,", 'rootFolderPath = "/music",',
     "every field comes from the folder it was given, not from a constant", 1),
    ("integrations/lidarr-targets-quality-passthrough", LIDARR_TARGETS,
     "qualityProfileId = quality,", "qualityProfileId = 1,",
     # 6, measured. Every test in `LidarrAddTargetsTest` that reads a quality id back reddens --
     # which is what a dense pure-function suite looks like, and is the argument for having one.
     "every field comes from the folder it was given, not from a constant", 6),

    # 8. The fallback that is the reason this class exists. `ValidId` requires a profile id above
    #    zero, and a root folder created through the API rather than the UI can carry zeros; without
    #    the fallback the add fails with a 400 about profiles that the user cannot act on.
    ("integrations/lidarr-targets-zero-fallback", LIDARR_TARGETS,
     "if (preferred > 0) preferred else profiles.firstOrNull()?.id", "preferred",
     # 4, measured: the give-up case flips too -- without the fallback, `resolve` returns a
     # LidarrAddTargets carrying profile id 0, which is exactly the 400 the fallback exists to avoid.
     "a zero default falls back to the first profile the server reports", 4),

    # 9. The inaccessible folder. Offering it produces an add that fails on a path validation --
    #    measured, "Folder '/music' is not writable by user 'abc'" -- shown to somebody who was
    #    choosing an album.
    ("integrations/lidarr-targets-inaccessible", LIDARR_TARGETS,
     "if (!rootFolder.accessible || rootFolder.path.isBlank()) return null",
     "if (rootFolder.path.isBlank()) return null",
     "an inaccessible root folder has no answer", 1),

    # 10. The blank monitor substitution, on the field that is easier to forget. `MonitorTypes` has
    #     no empty member, so an empty string on the wire is a 400 -- and this is the half of the
    #     pair a per-folder substitution would silently discard.
    ("integrations/lidarr-targets-newitem-monitor", LIDARR_TARGETS,
     "newItemMonitorOption = rootFolder.defaultNewItemMonitorOption.ifBlank { DEFAULT_MONITOR },",
     "newItemMonitorOption = rootFolder.defaultNewItemMonitorOption,",
     "a blank monitor default becomes all rather than an empty string on the wire", 2),

    # ---- Plan 7 Task 6: the add payload -- the crux, and the one spec section 8 called unverified -
    #
    # Same argument as the two families above: the coverage gate cannot see any of this. Every
    # mutation below leaves `LidarrAddPayload`'s 1.00 BRANCH floor and `LidarrClient`'s 0.90 BRANCH
    # floor exactly where they are -- each one substitutes a constant for a value, or drops a
    # comparison, rather than removing an arm. That is the shape a lazy implementation actually
    # ships, and it is the shape a ratio is structurally blind to.
    #
    # The defect this whole family exists to refuse, in one sentence: **adding the wrong album to
    # somebody's library.** A live lookup for `kind of blue` returns seven records, four of them
    # titled exactly that by four different artists. Nothing a user sees separates them; only
    # `foreignAlbumId` does.
    #
    # 1 and 2. The two identifiers. A constant here adds one particular stranger's record forever,
    #    with a 201, a monitored album and nothing wrong anywhere on the screen.
    ("integrations/lidarr-add-foreignAlbumId", LIDARR_PAYLOAD,
     'put("foreignAlbumId", candidate.foreignAlbumId)', 'put("foreignAlbumId", "mbid-a")',
     "the body carries the identifier that was asked for, not a constant", 4),
    ("integrations/lidarr-add-foreignArtistId", LIDARR_PAYLOAD,
     'put("foreignArtistId", candidate.foreignArtistId)', 'put("foreignArtistId", "art-a")',
     "the nested artist identifier is the one that was asked for", 6),

    # 3. TRAP 1. `AddAlbumService`: if the artist asks for a missing-albums search, the server
    #    silently sets `album.addOptions.searchForNewAlbum = false` -- a 201, a monitored album, and
    #    no download, with nothing reported anywhere. Upstream issue Lidarr #5012.
    #
    #    Read `LidarrAddPayloadTest`'s own comment on that test before quoting this as measured
    #    server behaviour: on the container Task 6 drove, no album-add search happened for EITHER
    #    value of the flag, so that instance cannot demonstrate the interaction. The probe still
    #    earns its place -- it pins what this client sends, which is the only half this repository
    #    controls.
    ("integrations/lidarr-searchForMissingAlbums", LIDARR_PAYLOAD,
     'put("searchForMissingAlbums", false)', 'put("searchForMissingAlbums", true)',
     "the artist never asks for a missing-albums search, which would cancel the album search", 4),

    # 4. The caller's own search choice, pinned to a constant -- the "always search" version of the
    #    same defect, which looks right in every manual test somebody runs with the box ticked.
    ("integrations/lidarr-searchForNewAlbum", LIDARR_PAYLOAD,
     'put("searchForNewAlbum", searchNow)', 'put("searchForNewAlbum", true)',
     "searchForNewAlbum is whatever the caller asked for", 4),

    # 5 and 6. The two profile ids. A constant files every add under one profile; a swap files each
    #    under the other's -- and a swap is accepted with a 201, because on a default install every
    #    id that exists in one table exists in the other (measured: quality 1..3, metadata 1..2).
    ("integrations/lidarr-add-qualityProfileId", LIDARR_PAYLOAD,
     'put("qualityProfileId", targets.qualityProfileId)', 'put("qualityProfileId", 2)',
     "the three add targets are written onto the nested artist", 2),
    ("integrations/lidarr-add-profile-swap", LIDARR_PAYLOAD,
     'put("metadataProfileId", targets.metadataProfileId)',
     'put("metadataProfileId", targets.qualityProfileId)',
     "the quality and metadata profile ids are not swapped", 4),

    # 7. The album's own monitored flag. An unmonitored album is never fetched whatever the search
    #    flag says, and a lookup element arrives carrying `monitored: false` -- so this is not a
    #    missing default, it is an overwrite that has to happen.
    ("integrations/lidarr-add-monitored", LIDARR_PAYLOAD,
     '      put("monitored", true)\n      put("artist", artist)',
     '      put("monitored", false)\n      put("artist", artist)',
     "both monitored flags are set, because an unmonitored album is never fetched", 2),

    # 8 and 9. The two passthroughs -- the reason `LidarrAlbumCandidate.raw` exists at all. The
    #    pinned Lidarr serves no `openapi.json`, so there is no published statement of what this
    #    endpoint requires; the only complete one is what Lidarr sent. A payload rebuilt from the
    #    typed fields drops the rest, including the `artist.id` that attaches a new album to an
    #    existing artist instead of creating a second one.
    ("integrations/lidarr-add-album-passthrough", LIDARR_PAYLOAD,
     "candidate.raw.forEach { (key, value) -> put(key, value) }", "candidate.raw.let { }",
     "every field the lookup sent that this client does not model survives", 2),
    ("integrations/lidarr-add-artist-passthrough", LIDARR_PAYLOAD,
     '(candidate.raw["artist"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }',
     '(candidate.raw["artist"] as? JsonObject)?.let { }',
     "every field the lookup sent that this client does not model survives", 3),

    # 10. The id the whole of Task 7 correlates on. A constant here points every later status poll
    #     at one album forever, and the poll itself keeps working -- it just reports on the wrong
    #     record, which is the silent-wrong-answer class this plan is built to refuse.
    ("integrations/lidarr-add-album-id", LIDARR_CLIENT,
     'albumId = response.body()?.int("id") ?: throw LidarrHttpException(response.code()),',
     "albumId = 42,",
     "a 201 yields the album id from the response body", 2),

    # 11. The same value from the other direction: a success with no id must fail naming the status
    #     that came back, not the 201 an implementer would type. A proxy that rewrote the status, or
    #     the 200 Lidarr's own generated spec once documented, would be reported as a 201 that never
    #     happened.
    ("integrations/lidarr-add-created-status", LIDARR_CLIENT,
     'albumId = response.body()?.int("id") ?: throw LidarrHttpException(response.code()),',
     'albumId = response.body()?.int("id") ?: throw LidarrHttpException(201),',
     "a created response with no id is a failure naming the status that came back", 1),

    # 12. The read side of the identifier rule, and a live wrong path rather than a hypothetical
    #     one: `GET /api/v1/album` with no `foreignAlbumId` is a legal request that returns the
    #     WHOLE library, 200 (measured). A client that took the first row would hand every later
    #     status poll somebody else's album id.
    ("integrations/lidarr-found-album-must-match", LIDARR_CLIENT,
     '.firstOrNull { it.string("foreignAlbumId") == foreignAlbumId }', ".firstOrNull()",
     "an answer that is not the album that was asked for yields null, not its id", 1),

    # 13 and 14. The two independent signals that identify a duplicate add, each removed alone.
    #     A duplicate is a 400 with the same shape as a real misconfiguration, so getting this
    #     wrong shows a user "Quality Profile does not exist" energy for an album they already own.
    #     Both arms are here because a floor cannot tell you either one is load-bearing -- and one
    #     of them, the `errorCode`, is a field Task 4 read in a fixture and deliberately left
    #     unmodelled on the grounds that nothing needed it.
    ("integrations/lidarr-alreadyAdded-errorCode", LIDARR_EXC,
     "it.errorCode == ALBUM_EXISTS_VALIDATOR ||", 'it.errorCode == "NoSuchValidator" ||',
     "either the validator code or the message alone identifies an already-added album", 1),
    ("integrations/lidarr-alreadyAdded-message", LIDARR_EXC,
     'it.errorMessage?.contains("has already been added", ignoreCase = true) == true',
     'it.errorMessage?.contains("a phrase lidarr never sends", ignoreCase = true) == true',
     # 2, measured: Task 4's own `an already-added validation failure is recognised by its message`
     # in `LidarrHandshakeTest` reddens too, which is the second caller that keeps this arm honest.
     "either the validator code or the message alone identifies an already-added album", 2),

    # 15. The endpoint itself. `api/v1/artist` is the neighbouring controller and the copy-paste a
    #     reviewer's eye slides over, the way `qualityprofile`/`metadataprofile` was at Task 5.
    #
    #     Measured, and the measurement is the good news: posting this exact body there answers
    #     **400**, not a 201 -- `GreaterThanValidator` on a top-level `QualityProfileId` of 0,
    #     because an album payload's profile ids live on its *nested* artist. So this mutation fails
    #     loudly at the server rather than silently adding the wrong kind of thing. The probe stays
    #     because a loud failure at the server is still a broken add for the user, and because the
    #     next such swap need not be lucky.
    ("integrations/lidarr-add-endpoint", LIDARR_API,
     '@POST("api/v1/album")', '@POST("api/v1/artist")',
     "the add is a POST to api v1 album with a json content type", 2),

    # ---- Plan 7 Task 8: Bindery, the second service --------------------------------------------
    # THIS FAMILY EXISTS BECAUSE THE COVERAGE GATE CANNOT SEE MOST OF IT, measured rather than
    # assumed. `BinderyAuthInterceptor` has no branches at all and its seven lines run on any
    # request whatsoever, so its 7/7 LINE floor stays green under every key-placement mutation
    # below; withholding all six of `BinderyAuthTest`'s tests -- every assertion in this repository
    # about where a Bindery API key goes -- also leaves that floor at 7/7 and both coverage gates
    # GREEN. A green `./gradlew check` is no evidence at all about the key's placement.
    #
    # The counts below were MEASURED on this family's first full run, not predicted.

    # 1. The key itself. A constant here authenticates against nothing and fails loudly in the
    #    field, but it is the shape that ships: a hardcoded value satisfies "the header is present"
    #    and this project has already shipped exactly that defect as `authParams() = emptyMap()`.
    ("integrations/bindery-api-key-header", BINDERY_INT,
     '.header("X-Api-Key", apiKey)', '.header("X-Api-Key", "constant")',
     "every request carries the key in the X-Api-Key header, at two values",
     # MEASURED, not predicted. every test that reads the header back off a RecordedRequest reddens,
     # plus `BinderyWiringTest`'s factory test and `BinderyHandshakeTest`'s health-with-a-bad-key
     # test, which both assert the header value one layer up.
     4),

    # 2. The key on the URL. Bindery really does accept `?apikey=` on a GET (measured: 200), so this
    #    is a live wrong path -- and it is WORSE than Lidarr's, because Bindery *refuses* a
    #    query-string key on a mutation (measured: 401). A client that drifted this way would search
    #    and list fine and fail only at the add. Adding the query parameter while LEAVING the header
    #    in place is deliberate: every response assertion in the module still passes, because the
    #    request still authenticates.
    ("integrations/bindery-api-key-on-url", BINDERY_INT,
     '.header("X-Api-Key", apiKey)',
     '.url(chain.request().url.newBuilder().addQueryParameter("apikey", apiKey).build()).header("X-Api-Key", apiKey)',
     "no request this client makes carries the key on its url",
     # MEASURED, not predicted. the whole-request scan, the factory test, and four
     # exact-`encodedQuery` assertions -- every endpoint's query string is pinned exactly, so a
     # smuggled parameter lengthens all of them.
     6),

    # 3. Content negotiation, pinned in one place so an endpoint a later task adds cannot forget it.
    ("integrations/bindery-accept-json", BINDERY_INT,
     '.header("Accept", "application/json")', '.header("Accept", "*/*")',
     "every request declares that it accepts json",
     # MEASURED, not predicted. the Accept assertion and the four-endpoint scan, which asserts
     # Accept too -- the point of putting it on the interceptor rather than on each `@GET`.
     2),

    # 4. THE TRAP. `mediaType` defaults to `ebook` SERVER-SIDE -- measured, a POST omitting it
    #    answers 201 with `"mediaType":"ebook"` on the created book -- so a dropped or constant
    #    field silently acquires an EPUB that Navidrome will never scan. The request then sits at
    #    Imported forever and never becomes Arrived, with nothing anywhere saying why.
    ("integrations/bindery-mediaType", BINDERY_CLIENT,
     "mediaType = mediaType.wireValue,", 'mediaType = "ebook",',
     "the media type is always sent, and is audiobook by default",
     # MEASURED, not predicted. also reddens `the author fields are passed through and are omitted
     # when absent`, which re-asserts mediaType on the no-author body.
     2),

    # 5. The identifier the whole request row is keyed on. Same probe, same reason, as Lidarr's
    #    `-add-foreignAlbumId`: a constant produces a 201, a happy request row, and the wrong book.
    ("integrations/bindery-foreignBookId", BINDERY_CLIENT,
     "foreignBookId = candidate.foreignBookId,", 'foreignBookId = "book-1",',
     "the body carries the book identifier that was asked for, not a constant",
     # MEASURED, not predicted. also the four-endpoint scan (which asserts the POST body contains
     # the id) and the author passthrough test.
     3),

    # 6. The search term. `api.searchBook("dune")` still returns results, still parses, and still
    #    fills a screen -- with somebody else's book.
    ("integrations/bindery-search-term", BINDERY_CLIENT,
     "api.searchBook(term)", 'api.searchBook("dune")',
     "the search parameter is term, and never q",
     # MEASURED, not predicted. also the four-endpoint scan's exact `encodedQuery` list.
     2),

    # 7. The search parameter's NAME, which is the one Bindery's own documentation gets wrong. The
    #    docs say `q`; the handler reads `term`, and `q` answers 400 with a body byte-identical to
    #    the one a request with no parameter at all gets. A client written from the documentation
    #    cannot search, and cannot tell why.
    ("integrations/bindery-search-param-name", BINDERY_API,
     '@Query("term") term: String', '@Query("q") term: String',
     "the search parameter is term, and never q",
     # MEASURED, not predicted. same two, and note the failure differs: the term is absent rather
     # than wrong.
     2),

    # 8. `searchOnAdd`, hardcoded true. Every add still succeeds; the user's "just record it, do not
    #    go and fetch it" choice is silently discarded.
    ("integrations/bindery-searchOnAdd", BINDERY_CLIENT,
     "searchOnAdd = searchOnAdd,", "searchOnAdd = true,",
     "searchOnAdd carries whichever value it was given",
     # MEASURED, not predicted.
     1),

    # 9. The author fields, read from the wrong place -- and this is not a hypothetical mutation,
    #    it is the shape THE PLAN EXPECTED. `authorName` and `foreignAuthorId` are nested under an
    #    `author` object; reading them top-level yields null on every element of a real search, and
    #    an add with neither answers 422.
    ("integrations/bindery-author-nested", BINDERY_CLIENT,
     'authorName = author?.string("authorName")?.takeIf { it.isNotBlank() },',
     'authorName = obj.string("authorName")?.takeIf { it.isNotBlank() },',
     "every candidate field is read from its own element",
     # MEASURED, not predicted. also `a blank optional field is read as nothing and a present one as
     # itself`, which is the second caller through the same arms -- see the BRANCH floor's
     # falsification.
     2),

    # 10. Paging. Constants here read page one forever, which looks exactly like a library that has
    #     stopped changing.
    ("integrations/bindery-books-paging", BINDERY_CLIENT,
     "api.books(status, limit, offset)", "api.books(status, 100, 0)",
     "the status, limit and offset are each sent as given, at two values",
     # MEASURED, not predicted. also the four-endpoint scan's exact `encodedQuery` for the book
     # list.
     2),

    # 11. `downloaded` collapsed onto `Imported`. The file is fetched but has not been moved into
    #     the library folder, so Navidrome cannot have scanned it -- and `Imported` is what Task 9
    #     treats as "start looking for it in the mirror". This mutation starts a search that can
    #     never succeed and looks, to a user, like the arrival detection is broken.
    ("integrations/bindery-status-downloaded", BINDERY_STATUS,
     '"downloaded" -> RequestStatus.Downloading(percentComplete = null)',
     '"downloaded" -> RequestStatus.Imported',
     "downloaded is progress, not arrival",
     # MEASURED, not predicted. three mapper tests and `BinderyBooksTest`'s end-to-end mapping of
     # the real payload.
     4),

    # 12. An unrecognised status turned into a verdict. Bindery has no failure status at all, so
    #     `Failed` here would be a claim the server never made -- reported to a user about a book
    #     that is merely still being looked for.
    ("integrations/bindery-status-unknown", BINDERY_STATUS,
     "else -> RequestStatus.Requested",
     'else -> RequestStatus.Failed("unknown bindery status")',
     "a status this client does not know makes the least possible claim",
     # MEASURED, not predicted. also `no bindery status this client can be handed becomes a failure`
     # and the blank-status row in `BinderyBooksTest`.
     3),

    # 13. A created book with no usable id. `0` is not a hypothetical value: it is what EVERY
    #     Bindery search result carries, so it is precisely the number a wrong parse produces, and a
    #     `BinderyBook(id = 0)` puts a row in the request store that every later poll looks up under
    #     an id no book has.
    ("integrations/bindery-zero-id-accepted", BINDERY_CLIENT,
     "val id = body.id?.takeIf { it != 0 } ?: return null",
     "val id = body.id ?: 0",
     "a created response with no usable id fails loudly rather than returning zero",
     # MEASURED, not predicted. also `a row with no usable id or identifier is dropped and the page
     # survives` -- the same rule on the read side.
     2),

    # 14. The containment boundary this module found by running a test rather than by reasoning:
    #     `BinderyMessageException` carries Bindery's own sentence, and writing it as
    #     `Exception(binderyMessage)` -- the obvious way, and the way `LidarrValidationException`
    #     writes its own -- puts whatever the server said into `toString()`, which is the one thing
    #     a crash reporter uploads. The test enqueues a refusal whose body quotes the API key back.
    ("integrations/bindery-server-text-in-exception", BINDERY_EXC,
     'Exception("Bindery refused this request (HTTP $status)"), BinderyException',
     "Exception(binderyMessage), BinderyException",
     "no failure this client raises names the api key",
     # MEASURED, not predicted. also `a rejected search reports the status and bindery's own
     # message`, which pins `message` as the constant.
     2),
    # ---- Plan 7 Task 7: what happened to the request ------------------------------------------
    # Every count below is MEASURED, one probe at a time, on a committed tree; none is reasoned out.
    # 24 of 24 were caught by the test named beside them, 0 missed, 0 broken.
    #
    # READ THIS BEFORE ADDING A PROBE FOR THE `IN_PROGRESS` SET. There is deliberately no probe
    # that deletes an `in IN_PROGRESS ->` arm from `LidarrStatusMapper.map`, because there is no
    # such arm: an in-progress state and an unrecognised one must return the SAME
    # `Downloading(pct)` (the plan's fail-closed rule), so an arm for the first is behaviourally
    # identical to the fall-through that already handles the second. Written the plan's way first
    # and then probed: deleting that arm left ALL 117 TESTS GREEN. The arm was removed rather than
    # papered over, and `status-known-states-drops-one` below is what now holds the set to Lidarr's
    # nine.

    # 1. The one state whose answer is a terminal success. `imported` reading as anything else
    #    leaves a finished request looking unfinished forever.
    ("integrations/lidarr-status-imported", LIDARR_STATUS,
     "IMPORTED -> RequestStatus.Imported", "IMPORTED -> RequestStatus.Requested",
     "every tracked download state maps to a status, and no two kinds of failure are conflated", 4),

    # 2. Files on disk outrank the queue. Without this the status is whatever the download client
    #    last said, which is wrong from the moment the queue item vanishes -- and it vanishes the
    #    instant an import completes, which is exactly when the answer starts mattering.
    ("integrations/lidarr-status-progress-beats-queue", LIDARR_STATUS,
     "if (progress?.isComplete == true) return RequestStatus.Imported",
     "if (false) return RequestStatus.Imported",
     "complete statistics report Imported even while a queue item still exists", 1),

    # 3. The two KINDS of failure, merged. This is the defect a nine-element `containsExactly`
    #    written from the mutated behaviour would NOT catch, and it is why the mapper test asserts
    #    the five outcome classes separately. "The download failed" sends a user to their indexer;
    #    "Lidarr could not import the files" sends them to their permissions. Same 400, same
    #    `Failed`, different fix.
    ("integrations/lidarr-status-conflate-failures", LIDARR_STATUS,
     'internal const val IMPORT_FAILED_REASON = "Lidarr could not import the files"',
     'internal const val IMPORT_FAILED_REASON = "the download failed"',
     "the five outcome classes are mutually distinguishable", 2),

    # 4. Fail closed on an unknown state. A Lidarr newer than this client will send one, and
    #    reporting `Failed` is a guess that reads to the user as a verdict.
    ("integrations/lidarr-status-unknown-is-a-verdict", LIDARR_STATUS,
     "else -> RequestStatus.Downloading(percentComplete(queueItem))",
     "else -> RequestStatus.Failed(DOWNLOAD_FAILED_REASON)",
     "an unrecognised state reports progress rather than a verdict", 9),

    # 5. The enumeration itself, which is the ONLY falsifiable claim about `IN_PROGRESS` -- see the
    #    note at the head of this family. Dropping a state changes no behaviour whatsoever.
    ("integrations/lidarr-status-known-states-drops-one", LIDARR_STATUS,
     'setOf("downloading", "importPending", "importing")',
     'setOf("downloading", "importPending")',
     "the client recognises exactly lidarrs nine states", 1),

    # 6 and 7. Lidarr's own failure text, and what counts as text. "No files found are eligible for
    #    import" tells a user something the generic wording cannot; a whitespace-only message tells
    #    them nothing and must not displace it.
    ("integrations/lidarr-status-detail-ignored", LIDARR_STATUS,
     "val detail = queueItem.errorMessage?.takeIf { it.isNotBlank() }", "val detail: String? = null",
     "a failure message from lidarr replaces the generic one", 2),
    ("integrations/lidarr-status-blank-detail", LIDARR_STATUS,
     "queueItem.errorMessage?.takeIf { it.isNotBlank() }",
     "queueItem.errorMessage?.takeIf { it.isNotEmpty() }",
     "a failure message from lidarr replaces the generic one", 1),

    # 8-11. The percentage. Lidarr sends none, so all four of these are this client's arithmetic.
    #    `percent-inverted` is the one that matters most: it does not break progress, it makes it
    #    run backwards, and a progress bar counting down looks like a slow download rather than a
    #    bug. `percent-not-clamped` is a -14% a real download client can produce.
    ("integrations/lidarr-percent-not-clamped", LIDARR_STATUS,
     "return (done * 100).roundToInt().coerceIn(0, 100)", "return (done * 100).roundToInt()",
     "a percentage outside zero to one hundred is clamped rather than shown", 1),
    ("integrations/lidarr-percent-truncated", LIDARR_STATUS,
     "return (done * 100).roundToInt().coerceIn(0, 100)",
     "return (done * 100).toInt().coerceIn(0, 100)",
     "the percentage is rounded rather than truncated", 1),
    ("integrations/lidarr-percent-negative-size", LIDARR_STATUS,
     "if (item.sizeBytes <= 0.0) return null", "if (item.sizeBytes == 0.0) return null",
     "a zero-size item has an unknown percentage rather than a divide by zero", 1),
    ("integrations/lidarr-percent-inverted", LIDARR_STATUS,
     "val done = (item.sizeBytes - item.sizeLeftBytes) / item.sizeBytes",
     "val done = item.sizeLeftBytes / item.sizeBytes",
     "the percentage is computed from size and sizeleft, at more than one value", 10),

    # 12 and 13. `isComplete`, from both sides. The zero guard is not belt-and-braces: MEASURED on
    #    a live 3.1.0.4875, an album seconds after a successful add has no releases, no tracks and
    #    no statistics object at all, so `0 >= 0` is the state every request passes through. `>=`
    #    rather than `==` matters for a multi-disc release that files more tracks than Lidarr counts.
    ("integrations/lidarr-progress-zero-tracks", LIDARR_SOURCE,
     "get() = totalTrackCount > 0 && trackFileCount >= totalTrackCount",
     "get() = trackFileCount >= totalTrackCount",
     "an album with no tracks yet is not complete, however many files it has", 1),
    ("integrations/lidarr-progress-exact-equality", LIDARR_SOURCE,
     "get() = totalTrackCount > 0 && trackFileCount >= totalTrackCount",
     "get() = totalTrackCount > 0 && trackFileCount == totalTrackCount",
     "complete statistics report Imported even while a queue item still exists", 1),

    # 14. `sizeleft`, lower-case l -- the field this client would most like to have seen on a real
    #     wire and has not (no download client on the pinned container). Reading the camelCase
    #     spelling yields kotlinx's default 0.0 on every record and shows every download at 100%
    #     forever, with no parse error anywhere.
    ("integrations/lidarr-queue-sizeleft", LIDARR_CLIENT,
     "sizeLeftBytes = record.sizeleft,", "sizeLeftBytes = 0.0,",
     "sizeleft is read from the lower-case field lidarr actually sends", 4),

    # 15 and 16. The two query parameters, neither of which has a safe default. MEASURED: a bare
    #     `GET /api/v1/queue` really does answer `"pageSize": 10`. Accepting either default makes
    #     the client stop seeing its own request -- at eleven concurrent downloads for the first,
    #     and for any item whose artist Lidarr has not resolved for the second, which is precisely
    #     the state a just-added album is in.
    ("integrations/lidarr-queue-pagesize", LIDARR_CLIENT,
     "private const val QUEUE_PAGE_SIZE = 100", "private const val QUEUE_PAGE_SIZE = 10",
     "the queue is asked for a page big enough to contain the answer", 2),
    ("integrations/lidarr-queue-unknown-artist-items", LIDARR_CLIENT,
     "includeUnknownArtistItems = true", "includeUnknownArtistItems = false",
     "the queue is asked for a page big enough to contain the answer", 2),

    # 17 and 18. Two mapped queue fields, one representative of each risk. A defaulted state is a
    #     constant standing in for a value -- and `"imported"` is the one that would silently mark
    #     every request done. A dropped `albumId` is the correlation key for every later poll.
    ("integrations/lidarr-queue-state-default", LIDARR_CLIENT,
     "trackedDownloadState = record.trackedDownloadState.orEmpty(),",
     'trackedDownloadState = record.trackedDownloadState ?: "imported",',
     "a record missing its state fields reads as empty strings rather than failing", 1),
    ("integrations/lidarr-queue-albumid-dropped", LIDARR_CLIENT,
     "albumId = record.albumId,", "albumId = 0,",
     "every queue record field is read from its own record", 3),

    # 19. The id the poll asks about. A constant here reports on one album forever while the poll
    #     itself keeps working -- the silent-wrong-answer class, on the read side.
    ("integrations/lidarr-album-id-constant", LIDARR_CLIENT,
     "call { api.album(albumId) }.statistics", "call { api.album(42) }.statistics",
     "album progress is fetched by id and read from the statistics object", 1),

    # 20 and 21. The 404 swallow, from both sides. Swallowing nothing surfaces a normal answer -- a
    #     user deleted the album in Lidarr while MuPlay still holds the row -- as an error.
    #     Swallowing everything hides a 401, which is the key having stopped working and is not
    #     something a status poll may turn into "no progress information".
    ("integrations/lidarr-album-404-not-swallowed", LIDARR_CLIENT,
     "if (e.status == HTTP_NOT_FOUND) null else throw e", "throw e",
     "an album that is gone yields null rather than throwing", 1),
    ("integrations/lidarr-album-swallows-everything", LIDARR_CLIENT,
     "if (e.status == HTTP_NOT_FOUND) null else throw e", "null",
     "a failure that is not a 404 still propagates from album progress", 1),

    # 22. "Lidarr has not counted yet" collapsed into "Lidarr counted zero of zero". MEASURED to be
    #     a real wire shape, not a hypothetical one: a freshly added album carries no `statistics`
    #     key at all. Only the second of the two could ever be mistaken for a count.
    ("integrations/lidarr-album-no-statistics-zeroed", LIDARR_CLIENT,
     "?.let { LidarrAlbumProgress(it.trackFileCount, it.totalTrackCount) }",
     ".let { LidarrAlbumProgress(it?.trackFileCount ?: 0, it?.totalTrackCount ?: 0) }",
     "an album with no statistics object yields null rather than a zeroed progress", 1),

    # 23. The key smuggled into a header that is not `X-Api-Key`. The existing
    #     `lidarr-api-key-on-url` covers the query string; this is the same leak by another name,
    #     and no URL assertion can see it. A `User-Agent` carrying the key reaches every reverse
    #     proxy access log exactly as a query parameter would.
    ("integrations/lidarr-key-into-another-header", LIDARR_INT,
     '.header("Accept", "application/json")',
     '.header("Accept", "application/json")\n        .header("User-Agent", "MuPlay/" + apiKey)',
     "the queue and album progress requests carry the key only in the header", 3),

    # ---- Plan 3 Task 11: ReplayGain, at the three layers the JVM tier can reach ----------------
    # The fourth layer -- the samples -- is instrumented and therefore out of this runner's reach
    # for the reason the header gives; `GainAudioProcessor`, `ReplayGainController`, `MediaItems`
    # and `MuPlayRenderersFactory` are mutated BY HAND on the device instead, and those transcripts
    # are in task-11-report.md. Every probe here is production code the JVM suites do execute.
    #
    # `gain/sign-inverted` is the one that matters most and the reason this family exists: a sign
    # error does not break ReplayGain, it makes it exactly backwards -- a track tagged quiet plays
    # LOUD -- and nothing about that reads as a bug in a listening test.
    #
    # Every count below was MEASURED on the first run of this family, not predicted: all thirteen
    # reddened their named test on the first attempt and all but two disagreed with the count this
    # author guessed. Read the note on `expected failures` above before changing one -- a count that
    # drifts is a signal, and the named test failing is the claim.
    ("gain/sign-inverted", GAIN_POLICY,
     "    val linear = 10.0f.pow(clamped / DB_PER_AMPLITUDE_DECADE)",
     "    val linear = 10.0f.pow(-clamped / DB_PER_AMPLITUDE_DECADE)",
     "minus six dB is half the amplitude and plus six is double", 5),
    ("gain/always-unchanged", GAIN_POLICY,
     "    if (gainDb == null) return UNCHANGED",
     "    if (gainDb == null || true) return UNCHANGED",
     "minus six dB is half the amplitude and plus six is double", 5),
    ("gain/album-preferred-over-track", GAIN_POLICY,
     "    replayGain?.trackGainDb ?: replayGain?.albumGainDb",
     "    replayGain?.albumGainDb ?: replayGain?.trackGainDb",
     "the track gain is preferred over the album gain", 2),
    # The clamp is a CEILING. Dropping it lets a `+6 dB` tag on a file that already peaks at 0.9
    # of full scale clip -- audible as distortion, and silent in every test of the decibel
    # arithmetic on its own.
    ("gain/peak-clamp-dropped", GAIN_POLICY,
     "    return minOf(linear, 1.0f / peakAmplitude)",
     "    return linear",
     "a peak clamps a positive gain to the point of clipping and no further", 1),
    # The other end of the same clamp: a corrupt `+90 dB` tag.
    ("gain/corrupt-tag-unclamped", GAIN_POLICY,
     "    val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)",
     "    val clamped = gainDb",
     "a corrupt tag cannot deafen anyone", 1),
    # ...and the bounds themselves, which `coerceIn` executing says nothing about. `MAX_GAIN_DB`
    # narrowed to zero is a clamp that silently switches the feature off for every track that asks
    # to be made louder, while every relative assertion above stays true of something.
    ("gain/clamp-bound-narrowed", GAIN_POLICY,
     "  const val MAX_GAIN_DB: Float = 12.0f",
     "  const val MAX_GAIN_DB: Float = 0.0f",
     "the clamp bounds are wide enough to carry a real library and narrow enough to be safe", 4),

    # The wire. A constant `ReplayGain` is this project's signature defect shape, and it passes any
    # single-value check.
    ("gain/client-constant", CLIENT,
     "    return ReplayGain(trackGainDb = trackGainDb, albumGainDb = albumGainDb, peakAmplitude = peak)",
     "    return ReplayGain(trackGainDb = -6.0f, albumGainDb = null, peakAmplitude = null)",
     "each field comes from its own key and not from a neighbour", 6),
    ("gain/client-peak-fallback-swapped", CLIENT,
     "    val peak = this?.trackPeak ?: this?.albumPeak",
     "    val peak = this?.albumPeak ?: this?.trackPeak",
     "a second body, every value disjoint from the first, maps field by field", 3),
    # "The file said nothing" collapsed into "the file said zero", which would apply a decision
    # nobody made to every untagged library there is -- and Navidrome sends `"replayGain": {}` for
    # every untagged file, so this is the common path rather than an edge.
    ("gain/client-empty-object-is-a-decision", CLIENT,
     "    if (trackGainDb == null && albumGainDb == null) return null",
     "    if (false) return null",
     "an untagged file carries no replay gain at all, rather than zeroes", 4),

    # The mirror, both ways. This is the layer the wire tests cannot see at all: drop the columns
    # here and every `:core:network` test stays green while the player gets nothing.
    ("gain/mirror-forward-dropped", MIRROR,
     "    replayGainTrackDb = song.replayGain?.trackGainDb,",
     "    replayGainTrackDb = null,",
     "a second song, every field disjoint from the first, still round-trips", 3),
    ("gain/mirror-forward-peak-dropped", MIRROR,
     "    replayGainPeak = song.replayGain?.peakAmplitude,",
     "    replayGainPeak = null,",
     "a second song, every field disjoint from the first, still round-trips", 3),
    ("gain/mirror-reverse-dropped", MIRROR,
     "    replayGain = entity.replayGain(),",
     "    replayGain = null,",
     "a second song, every field disjoint from the first, still round-trips", 4),
    # The reverse guard's shape: `track gain absent` instead of `both gains absent` drops every
    # album-tagged file on the way out of the mirror.
    ("gain/mirror-reverse-album-only-dropped", MIRROR,
     "    if (replayGainTrackDb == null && replayGainAlbumDb == null) return null",
     "    if (replayGainTrackDb == null) return null",
     "a song with only an album gain still round-trips as a decision", 1),
    # ---- Plan 4 Task 1: the three constants the four-book corpus exists to break -------------
    #
    # These are not defects that shipped -- the corpus and the parser landed together. They are
    # here because the corpus's whole justification is that against ONE book with three equal
    # chapters each of these mutations is undetectable, and a justification nobody re-checks is
    # the kind this project keeps finding to be false. Each reddens exactly one test, measured.
    #
    # If any of these ever reports MISSED, the fixtures have lost the property they were built
    # for -- read `ci/seed-fixtures.sh`'s per-book comments before touching the assertion.
    ("books/duration-is-the-sum", BOOK_FIXTURES,
     "val durationMs: Long get() = tracks.sumOf { it.durationMs }",
     "val durationMs: Long get() = tracks.first().durationMs",
     # `Multi Part Book` is the only fixture that can tell these two programs apart. Against the
     # three single-file books they are the same function.
     "a book's duration is the sum of its files", 1),
    ("books/track-order", BOOK_FIXTURES,
     ".sortedWith(compareBy({ it.trackNumber }, { it.path }))",
     ".sortedBy { it.durationMs }",
     # 4 s / 6 s / 5 s is deliberately not monotonic, so "sorted by duration" and "sorted by track
     # number" are different lists. Give the parts equal or ascending durations and this probe goes
     # MISSED without a single assertion changing.
     "Multi Part Book is three files with three different durations, in track order", 1),
    ("books/chapter-order", BOOK_FIXTURES,
     "chapters = tracks.flatMap { chaptersOf(it.path) },",
     "chapters = tracks.flatMap { chaptersOf(it.path) }.sortedBy { it.title },",
     # Chapter ORDER is a property of a book: a reader that returned them alphabetically would
     # play the epilogue first. `Test Book`'s "Chapter 1/2/3" and `Tail Book`'s "Head/Tail" are
     # already alphabetical, so `Second Book` -- Prologue / The Long Middle / A Turn / Epilogue --
     # is the only fixture in the corpus that catches this at all.
     "Second Book's chapters are unequal in length and in order", 1),
    # ---- Plan 5 Task 5: what a tapped browse row expands to ------------------------------------
    #
    # ONLY THE ARITHMETIC IS HERE, AND THAT IS THIS RUNNER'S LIMIT RATHER THAN A CHOICE. The task's
    # other nine mutations -- the callback's start index, the `localConfiguration` passthrough, the
    # book's scope guard, the shuffle's library id, the resume file, the reversed file list, the
    # swallowed failure, and `MuPlayer` discarding the policy's position -- all live behind
    # `MediaItem`, which reaches `android.net.Uri` and cannot be built on the JVM at all. Their
    # only tier is `connectedDebugAndroidTest`, which this script cannot reach: an androidTest
    # source is not an input to the JVM test task, Gradle restores it FROM-CACHE, and the probe
    # reports MISSED with zero failures. Adding them here would read as a broken test rather than
    # as an unrunnable probe -- see this file's header. They are falsified BY HAND on the device
    # and the transcripts are in task-5-report.md, the same way Plan 3 Task 7b's are.
    #
    # `startIndexOf` is the half that is pure Kotlin, and it is the half a wrong answer is silent
    # in: `indexOfFirst` returns -1 for a miss, `PlaybackQueue.of(songs, -1)` throws inside a
    # `ListenableFuture`, and a car hears nothing with no error anywhere.
    ("browse/start-index-constant-zero", BROWSE_TREE_REPOSITORY,
     "songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)", "0",
     # Every album starts at track one, every book at its first file. The count is 3 because the
     # two fixtures added below to kill the two mutations after this one also see it.
     "the start index is the tapped song's own position", 3),
    ("browse/start-index-not-coerced", BROWSE_TREE_REPOSITORY,
     "songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)",
     "songs.indexOfFirst { it.id == mediaId }",
     "an id that is not in the list starts at the beginning rather than at minus one", 1),
    ("browse/start-index-matches-title", BROWSE_TREE_REPOSITORY,
     "songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)",
     "songs.indexOfFirst { it.title == mediaId || it.id == mediaId }.coerceAtLeast(0)",
     # A WIDENING, not a swap, and it is the shape this mistake actually takes. It survived
     # `BrowseExpansionTest` as first written -- no fixture had a title that was another song's id
     # -- which is why one of them now does.
     "the id is matched on identity and not on any other field", 1),
    ("browse/start-index-last-match", BROWSE_TREE_REPOSITORY,
     "songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)",
     "songs.indexOfLast { it.id == mediaId }.coerceAtLeast(0)",
     # Identical to the right answer on every list with unique ids, which every fixture in this
     # project had. A queue may legitimately hold one track twice; the tapped row is the first.
     "a queue that holds one id twice is positioned at its first appearance", 1),
    ("browse/empty-selection-not-at-zero", BROWSE_SELECTION,
     "val EMPTY: BrowseSelection = BrowseSelection(emptyList(), 0)",
     "val EMPTY: BrowseSelection = BrowseSelection(emptyList(), 1)",
     # `EMPTY` is what a caller that must answer unconditionally hands back. An index of 1 into no
     # songs is an `IllegalArgumentException` the moment anything tries to play it.
     "the empty selection is empty and starts at zero", 1),
    # ---- Plan 4 Task 2: the audiobook value types ---------------------------------------------
    ("audiobook/chapter-contains-upper-bound", CHAPTER,
     "positionMs >= startMs && positionMs < endMs",
     "positionMs >= startMs && positionMs <= endMs",
     # Half-open is what makes "which chapter am I in" one answer. Closed at the top and the
     # instant a chapter ends belongs to two chapters at once, so the answer depends on which end
     # of the list the caller searched from. Only an assertion that names the boundary exactly
     # sees this -- a fixture probed at 61_999/100_000/181_501 would not.
     "containment is half-open, so a position on a boundary is in the later chapter", 3),
    ("audiobook/chapter-contains-lower-bound", CHAPTER,
     "positionMs >= startMs && positionMs < endMs",
     "positionMs > startMs && positionMs < endMs",
     "containment is half-open, so a position on a boundary is in the later chapter", 2),
    ("audiobook/chapter-duration-clamp", CHAPTER,
     "val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)",
     "val durationMs: Long get() = endMs - startMs",
     # A tagger really does write these backwards, and the unclamped answer is a negative length
     # that every consumer renders.
     "a chapter whose atoms are out of order is zero long rather than negative", 1),
    ("audiobook/chapter-duration-offset", CHAPTER,
     "val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)",
     "val durationMs: Long get() = endMs",
     # The fixture starts at 62_000 rather than 0 for exactly this reason: against chapter 0 of
     # any book these two programs are the same function.
     #
     # 2, measured rather than assumed: `endMs` alone also reddens `a chapter whose atoms are out
     # of order is zero long rather than negative` (9_000 instead of 0), because that test's own
     # fixture has a non-zero `endMs` too. Submitted as 1 and reported MISSED on the first run --
     # the named test failed exactly as intended, which is the stale-count case this table's
     # `expected failures` note describes, not a code regression.
     "a chapter's duration is the gap between its two atoms", 2),
    ("audiobook/speed-nan", BOOK_SETTINGS,
     "speed.isNaN() -> DEFAULT_SPEED", "speed.isNaN() -> speed",
     # `Float.NaN.coerceIn(0.5f, 3.0f)` is NaN, and `setPlaybackSpeed(NaN)` throws from a listener
     # callback -- playback dies with no message. Note the assertion this names checks
     # `isNaN()` explicitly: a `containsExactly` row would compare NaN to NaN and pass.
     "not a number becomes the default rather than surviving the clamp", 1),
    ("audiobook/speed-clamp", BOOK_SETTINGS,
     "else -> speed.coerceIn(MIN_SPEED, MAX_SPEED)", "else -> speed",
     "the speed clamp holds both ends and passes everything between them through", 1),
    ("audiobook/default-book-id", BOOK_SETTINGS,
     "BookSettings(bookId = bookId, speed = DEFAULT_SPEED, skipSilence = false)",
     'BookSettings(bookId = "book-42", speed = DEFAULT_SPEED, skipSilence = false)',
     # The second id in that test is what catches this: with one id, "returned the id it was
     # given" and "returned the id this test happens to use" are the same observation.
     "a book with no stored settings plays at one times with no silence skipping", 1),
    # ---- Plan 4 Task 3: chapters out of the file's own bytes ----------------------------------
    # Every count below was MEASURED by applying the mutation alone against the committed tree and
    # reading the result XML -- see task-3-report.md for the transcripts.
    #
    # These seven defects share a symptom and it is the one this whole feature is for: **the book
    # plays its epilogue third**, or shows a chapter list that is plausible and wrong. None of them
    # moves a branch, so no coverage counter can see any of them.
    ("chapters/assembly-unsorted", CHAPTER_ASSEMBLY,
     "val ordered = byStart.values.sortedBy { it.startMs }",
     "val ordered = byStart.values.toList()",
     # Media3 hands back one entry per (track format x atom) and promises nothing about order.
     # `LinkedHashMap` then preserves ARRIVAL order, which is why the fixture arrives shuffled.
     "chapters are ordered by start time, whatever order they arrived in", 2),
    ("chapters/assembly-end-always-duration", CHAPTER_ASSEMBLY,
     "val fallback = ordered.getOrNull(index + 1)?.startMs ?: contentDurationMs",
     "val fallback = contentDurationMs",
     # Every chapter that arrived with `C.TIME_UNSET` would end at the end of the FILE, so every
     # chapter but the last would report itself hours long and overlap every one after it.
     #
     # 2, measured: `a duplicate that carries nothing the first one lacked leaves it alone` also
     # reddens, because its two surviving chapters both end at the fallback. Submitted as 1 and
     # reported MISSED on the first run -- that test was added after this count was written, which
     # is exactly the stale-count case this table's `expected failures` note describes.
     "a missing end time is filled from the next chapter's start", 2),
    ("chapters/assembly-duration-overwrites-end", CHAPTER_ASSEMBLY,
     "val end = (entry.endMs ?: fallback).coerceAtLeast(entry.startMs)",
     "val end = fallback.coerceAtLeast(entry.startMs)",
     # The other direction: throwing away what Media3 actually read. Invisible on the seeded
     # corpus's *abutting* chapters, which is why the fixture that catches it has a gap.
     # 3, measured: the same mutation also reddens `a duplicate contributes its end time to a twin
     # that has none` (its 3000 ms end becomes the next chapter's 4000 ms start) and `an end time
     # before its own start is clamped ...` (its 4000 ms end becomes the content duration).
     "a populated end time is never overwritten by the duration", 3),
    ("chapters/assembly-no-end-clamp", CHAPTER_ASSEMBLY,
     "val end = (entry.endMs ?: fallback).coerceAtLeast(entry.startMs)",
     "val end = (entry.endMs ?: fallback)",
     # A negative `durationMs` reaches a progress bar and reaches `seekTo`.
     "an end time before its own start is clamped rather than producing a negative duration", 1),
    ("chapters/assembly-title-untrimmed", CHAPTER_ASSEMBLY,
     "get() = title?.trim()?.takeIf { it.isNotEmpty() }",
     "get() = title?.takeIf { it.isNotEmpty() }",
     # A `chpl` atom padded to a fixed width is a real thing, and " " is not a title -- but it is
     # not empty either, so it survives as one and de-duplication then prefers it over a real name.
     "a blank title is the same fact as no title", 2),
    ("chapters/assembly-duplicate-drops-title", CHAPTER_ASSEMBLY,
     "        existing.normalisedTitle == null && entry.normalisedTitle != null -> entry\n",
     "",
     # A two-track M4B presents its chapter list twice, once titled and once not. Keeping whichever
     # arrived first is a coin flip between the real names and none.
     "duplicate entries for the same start time collapse to one, keeping the titled one", 1),
    ("chapters/assembly-negative-start-kept", CHAPTER_ASSEMBLY,
     "      if (entry.startMs < 0L) continue\n", "",
     # Clamping or keeping a negative start puts a second, unseekable "chapter 1" in front of the
     # real one.
     "a chapter that starts before the file does is dropped rather than clamped", 1),
    # ---- and the timeline over them -----------------------------------------------------------
    ("timeline/book-offset-ignored", BOOK_TIMELINE,
     "            bookStartMs = bookOffset + chapter.startMs,",
     "            bookStartMs = chapter.startMs,",
     # THE multi-file defect. A single-file book cannot see it at all -- its offset is always 0 --
     # which is exactly why `BookTimelineTest` carries a two-disc book whose files also carry
     # chapters. 2, measured: `the book position is the item's offset, not the chapter's` reads
     # `bookStartMs` back through `bookPositionMs`.
     "a multi-file book whose files also carry chapters is neither of the easy cases", 2),
    ("timeline/book-position-ignores-chapter-start", BOOK_TIMELINE,
     "    return itemStart.bookStartMs - itemStart.startInItemMs + positionInItemMs",
     "    return itemStart.bookStartMs + positionInItemMs",
     # `firstOrNull` finds the item's FIRST chapter, whose `startInItemMs` is 0 for every book in
     # the seeded corpus -- so this is the identity there, and the first version of this probe was
     # MISSED with every assertion in the file green. The fixture the named test now carries gives
     # Disc Two's first chapter atom a 1000 ms start, which is what makes the subtraction
     # observable at all. Read that test's comment before touching its numbers.
     "the book position is the item's offset, not its first chapter's start", 1),
    ("timeline/restart-threshold-strict", BOOK_TIMELINE,
     "    if (intoChapter >= restartThresholdMs) return current",
     "    if (intoChapter > restartThresholdMs) return current",
     # 2, measured: `previous restarts the current chapter unless you are already near its start`
     # probes 12000 ms into a chapter that starts at 9000, which is the threshold exactly.
     "the default restart threshold is the declared one and not some other number", 2),
    ("timeline/previous-uses-raw-position", BOOK_TIMELINE,
     "    val intoChapter = positionInItemMs - current.startInItemMs",
     "    val intoChapter = positionInItemMs",
     # "Am I near the start of this chapter" measured from the start of the FILE. Every chapter
     # after the first is then always "deep inside", so `previous` restarts instead of stepping
     # back -- and chapter 1 of a single-file book cannot see it, because there the two agree.
     # 4, measured: every `previous` test in the file except the empty-timeline one reads a
     # position deeper into the FILE than the threshold, which is the whole defect.
     "previous measures how far into the chapter you are, not how far into the file", 4),
    ("timeline/chapters-unsorted", BOOK_TIMELINE,
     "        for (chapter in chapters.sortedBy { it.startMs }) {",
     "        for (chapter in chapters) {",
     # The map also arrives out of Room and out of a caller's hands, neither of which is
     # `ChapterAssembly`'s output by construction -- and SQLite returns rows in whatever order it
     # likes without an ORDER BY.
     "chapters inside one file are ordered by start time, whatever order they arrived in", 1),
    ("timeline/chapter-at-first-match", BOOK_TIMELINE,
     "    return inItem.lastOrNull { positionInItemMs >= it.startInItemMs } ?: inItem.first()",
     "    return inItem.firstOrNull { positionInItemMs >= it.startInItemMs } ?: inItem.first()",
     # `firstOrNull` answers "chapter 1" for every position in the book, because every position is
     # at or after chapter 1's start. 2, measured: it also reddens `a position past the final
     # chapter's end still answers the final chapter`.
     "a position exactly on a boundary belongs to the chapter that starts there", 2),
    ("timeline/untitled-numbered-per-file", BOOK_TIMELINE,
     "String.format(Locale.ROOT, UNTITLED_FORMAT, result.size + 1)",
     "String.format(Locale.ROOT, UNTITLED_FORMAT, 1)",
     # Chapter 1 of disc two is not "Chapter 1". The fixture puts the second untitled chapter in
     # the SECOND file for exactly this reason.
     "an untitled chapter is numbered by its position in the book", 1),
    ("timeline/chapter-duration-unclamped", BOOK_TIMELINE,
     "  val durationMs: Long get() = (endInItemMs - startInItemMs).coerceAtLeast(0L)",
     "  val durationMs: Long get() = endInItemMs - startInItemMs",
     "a chapter whose atoms run backwards has a zero duration rather than a negative one", 1),

    # ---- Plan 4 Task 4: the shelf's arithmetic and its order ---------------------------------
    #
    # Every failure count below is MEASURED by running this family, not predicted -- several of
    # these mutations redden more than the one test they are named for, because the fixtures
    # deliberately vary one input at a time against a shared rule.
    ("shelf/current-file-furthest", BOOK_SUMMARIES,
     "      if (row != null && (incumbent == null || row.lastPlayedAtEpochMs >= incumbent.lastPlayedAtEpochMs)) {",
     "      if (row != null) {",
     # "the last file with a row" rather than "the most recently written row". A listener who
     # jumped back to chapter 1 gets dragged forward to wherever they had been furthest, which is
     # the defect you only notice by losing your place.
     "an older row on a later file does not win", 2),
    ("shelf/current-file-tie-earlier", BOOK_SUMMARIES,
     "row.lastPlayedAtEpochMs >= incumbent.lastPlayedAtEpochMs",
     "row.lastPlayedAtEpochMs > incumbent.lastPlayedAtEpochMs",
     # `>` is what `maxByOrNull` does: the FIRST maximal element. A batch write really does produce
     # two rows in one millisecond, and this pins such a listener to part one for good.
     "two rows written in the same millisecond resolve to the later file", 1),
    ("shelf/position-no-offset", BOOK_SUMMARIES,
     "    val offsetMs = ordered.take(currentIndex.coerceAtLeast(0))\n      .sumOf { it.durationSeconds * 1_000L }",
     "    val offsetMs = 0L",
     # The whole-book position collapses to the position inside the current file. Every
     # single-file assertion in this class stays green, which is why the fixtures are multi-file.
     "a book's position is the files before the current one plus the position inside it", 3),
    ("shelf/order-by-time-alone", BOOK_SUMMARIES,
     "    compareBy<BookSummary> { group(it) }\n      .thenByDescending { if (group(it) == GROUP_UNSTARTED) 0L else it.lastPlayedAtEpochMs }",
     "    compareBy<BookSummary> { 0 }\n      .thenByDescending { it.lastPlayedAtEpochMs }",
     # The three groups collapse into one. A finished book heard a minute ago goes to the top of
     # the shelf, which is the most annoying shelf available.
     # 2, measured: it also reddens `a finished book drops below an unstarted one even though it
     # was heard more recently`. The three tie-break tests stay green, because collapsing the
     # groups leaves their inputs -- all in one group already -- ordered the same way.
     "the shelf is continue-listening first, then unstarted alphabetically, then finished", 2),
    ("shelf/play-order-untagged-first", BOOK_SUMMARIES,
     "      { it.trackNumber ?: Int.MAX_VALUE },",
     "      { it.trackNumber ?: 0 },",
     # The book opens on its own afterword.
     "a file with no track number sorts after every numbered one, by title", 1),
    ("shelf/play-order-untagged-disc-zero", BOOK_SUMMARIES,
     "      { it.discNumber ?: 1 },",
     "      { it.discNumber ?: 0 },",
     # The other `?:` in the same comparator, and the opposite default for the opposite reason: a
     # single-disc rip leaves the column null on every file.
     "a file with no disc number plays with disc one, not before it", 1),
    ("shelf/finished-on-any-file", BOOK_SUMMARIES,
     "      isFinished = ordered.isNotEmpty() && progress[ordered.last().id]?.isFinished == true,",
     "      isFinished = ordered.any { progress[it.id]?.isFinished == true },",
     # A book comes off the Continue shelf the first time a chapter runs out on its own.
     "a book is finished when its last file is finished, and not before", 1),
    ("shelf/author-from-nowhere", BOOK_SUMMARIES,
     "      author = album.artistName.orEmpty(),",
     '      author = "",',
     # A constant field assignment removes no branch, so coverage cannot see this one at all --
     # which is the defect class this whole plan is written against.
     "the album row supplies the identity a file cannot", 1),
    # ---- Plan 4 Task 5: the smart-rewind band table, its four boundaries, and the clamp --------
    # Every count below was MEASURED by applying the mutation alone against the committed tree and
    # reading the result XML -- see task-5-report.md for the transcripts.
    #
    # The four `*-boundary-inclusive` probes are the point of the family and they are what the
    # deliverable "every boundary asserted on both sides" buys: each turns one `<` into `<=`, which
    # moves the answer at EXACTLY ONE input in the whole Long range. A suite that asserted each
    # band at one interior value only -- the obvious way to test a lookup table, and the way that
    # proves the table equals itself -- is green against all four of them.
    ("rewind/band-table-constant", SMART_REWIND,
     "    awayMs < AWAY_NONE_MS -> REWIND_NONE_MS",
     "    awayMs < Long.MAX_VALUE -> REWIND_MEDIUM_MS",
     # The whole table collapsed to one value. Named against the five-band assertion because that
     # is the one that cannot be satisfied by any constant at all.
     "each band rewinds its own distinct amount", 9),
    ("rewind/none-boundary-inclusive", SMART_REWIND,
     "    awayMs < AWAY_NONE_MS -> REWIND_NONE_MS",
     "    awayMs <= AWAY_NONE_MS -> REWIND_NONE_MS",
     "the fifteen second threshold is where rewinding starts", 1),
    ("rewind/short-boundary-inclusive", SMART_REWIND,
     "    awayMs < AWAY_SHORT_MS -> REWIND_SHORT_MS",
     "    awayMs <= AWAY_SHORT_MS -> REWIND_SHORT_MS",
     "the one minute threshold moves the answer", 1),
    ("rewind/medium-boundary-inclusive", SMART_REWIND,
     "    awayMs < AWAY_MEDIUM_MS -> REWIND_MEDIUM_MS",
     "    awayMs <= AWAY_MEDIUM_MS -> REWIND_MEDIUM_MS",
     "the one hour threshold moves the answer", 1),
    ("rewind/long-boundary-inclusive", SMART_REWIND,
     "    awayMs < AWAY_LONG_MS -> REWIND_LONG_MS",
     "    awayMs <= AWAY_LONG_MS -> REWIND_LONG_MS",
     "the one day threshold moves the answer", 1),
    ("rewind/top-band-unbounded", SMART_REWIND,
     "    else -> REWIND_MAX_MS",
     "    else -> awayMs / AWAY_LONG_MS * REWIND_MAX_MS",
     # The top band is the one band with no upper threshold, so "both sides" cannot pin it and a
     # second, much larger input has to. A scale that keeps going rewinds a listener ten minutes
     # into the previous chapter after a holiday. Note it answers 20_000 at exactly one day, so
     # `the one day threshold moves the answer` is green against it -- the bound is only visible
     # from far above the boundary.
     "a month away rewinds the same as a day away and no more", 2),
    ("rewind/swap-long-and-max", SMART_REWIND,
     "  const val REWIND_LONG_MS = 10_000L\n  const val REWIND_MAX_MS = 20_000L",
     "  const val REWIND_LONG_MS = 20_000L\n  const val REWIND_MAX_MS = 10_000L",
     # Five DISTINCT values is what makes this catchable at all: with two bands sharing a number,
     # a swap is invisible. `a month away ...` is NOT among the three, and that is the honest
     # correction to this task's plan, which expected it: that test names `REWIND_MAX_MS` rather
     # than `20_000L`, so it moves with the constant and stays green. It is only safe because the
     # literal is asserted two tests above it.
     "the one day threshold moves the answer", 3),
    ("rewind/resume-no-clamp", SMART_REWIND,
     "    return if (storedPositionMs <= rewind) 0L else storedPositionMs - rewind",
     "    return storedPositionMs - rewind",
     # A negative reaching `seekTo`. Two seconds into a chapter, gone for a week: -18_000.
     "a rewind never goes past the start of the file", 3),
    ("rewind/resume-clamp-after-subtracting", SMART_REWIND,
     "    return if (storedPositionMs <= rewind) 0L else storedPositionMs - rewind",
     "    return (storedPositionMs - rewind).coerceAtLeast(0L)",
     # The shorter form, which reads better and is wrong at one input: `Long.MIN_VALUE - 20_000`
     # wraps to a huge POSITIVE that a lower clamp cannot see. One failure, and it is the test
     # that exists for it -- `a negative stored position is treated as the start` is green here,
     # which is why the two are separate tests rather than two assertions in one.
     "a stored position at the bottom of the range does not wrap into the far future", 1),
    ("rewind/resume-ignores-away", SMART_REWIND,
     "    val rewind = rewindMs(awayMs)",
     "    val rewind = rewindMs(600_000L)",
     # `resumePositionMs` as a function of one argument. The clamp tests are all green against
     # this, because a clamped answer of 0 is 0 whatever the band said.
     "the resume position is the stored position minus the band's rewind", 1),

    # ---- Plan 3 Task 12: transcoded seek via `timeOffset` ---------------------------------------
    #
    # WHAT IS AND IS NOT HERE. This runner is JVM-only (see this file's own header), so only the
    # mutations a JVM test can see are in this table. The two that matter most for this feature --
    # `TranscodeOffsetSupport` defaulting to "supported", and `MuPlayer.getCurrentPosition` losing
    # its offset base -- are visible ONLY on the device tier, where this runner reports MISSED with
    # zero failures. Both were applied by hand against `:core:media`'s connected suite and the
    # transcripts are in task-12-report.md; they are deliberately absent from this table rather
    # than left MISSED, which is the treatment Task 7b's hand-built-player probe got for the same
    # structural reason.
    #
    # 1. THE SHIPPED DEFECT, RESTORED. `timeOffset` is the only way to seek a live transcode at
    #    all: one carries no `Content-Length` and answers `Accept-Ranges: none`, so ExoPlayer's own
    #    seek either does nothing or resolves against a length it does not have -- and nothing
    #    throws. Dropping the parameter leaves the whole feature compiling, every player test
    #    green, the bar moving and the audio where it was.
    ("stream/no-time-offset", CLIENT,
     'builder.addQueryParameter("timeOffset", timeOffsetSeconds.coerceAtLeast(0).toString())',
     'builder.addQueryParameter("nothing", "")',
     # 3: the offset test, the zero test and the clamp test all read a parameter this line is the
     # sole source of. It also reddens `LiveNavidromeTest`'s two body-size assertions and the whole
     # device tier, neither of which this runner executes.
     "a transcode asked for an offset carries it, in seconds", 3),

    # 2. The same parameter, sent where the server ignores it. `format=raw` disables transcoding,
    #    so `timeOffset` beside it is a parameter the server discards and a reader misreads -- the
    #    same class as `maxBitRate` on a raw request, which this file already probes one family up.
    #    Nothing observable breaks: every raw stream still plays.
    ("stream/time-offset-on-raw", CLIENT,
     "if (format is StreamFormat.Mp3 && timeOffsetSeconds != null) {",
     "if (timeOffsetSeconds != null) {",
     "a raw request never carries a time offset even when one is asked for", 1),

    # 3. `timeOffset=0` mapped to absence. The two produce the same audio -- measured against the
    #    container, 300369 bytes either way -- so this looks like a nicety and is not: `0` is what
    #    "re-issue from the top" means, and collapsing it makes the re-issue path's own boundary
    #    case take a different code path from every other seek, untested and unmeasurable.
    ("stream/time-offset-zero-dropped", CLIENT,
     "if (format is StreamFormat.Mp3 && timeOffsetSeconds != null) {",
     "if (format is StreamFormat.Mp3 && timeOffsetSeconds != null && timeOffsetSeconds > 0) {",
     # 2, measured: `> 0` also swallows the *negative* case, so the clamp test goes red beside the
     # zero test. Submitted as 1 and reported MISSED on the first run -- the stale-count case this
     # table's own note describes, not a code regression.
     "a zero offset is sent, because it is a real request and not an absent one", 2),

    # 4. THE DECISION ITSELF, COLLAPSED TO WHAT EVERY PLAYER DID BEFORE THIS TASK. `InPlace` for a
    #    transcode is the original bug exactly: a seek that appears to work and plays the wrong
    #    audio. On the device it fails on the amplitude of the first frames out of the decoder;
    #    here it fails on the decision.
    ("seek/always-in-place", TRANSCODE_SEEK,
     "    !serverSupportsTranscodeOffset -> SeekMethod.NotOffered\n"
     "    else -> SeekMethod.ReissueWithOffset(offsetSecondsFor(targetPositionMs))",
     "    else -> SeekMethod.InPlace",
     # 5, measured. Every test in `TranscodeSeekTest` that expects a `ReissueWithOffset` or a
     # `NotOffered` goes red: the two-target one this names, the flooring one, the clamping one,
     # the withdrawal one and the wire-value one. Submitted as 4 and reported MISSED on the first
     # run -- the stale-count case, not a code regression.
     "a transcode on a server that supports the extension is re-issued at the offset", 5),

    # 5. The capability gate, ignored. `NotOffered` is the honest form of spec section 4's
    #    "unsupported features are silent no-ops": offering a seek the server cannot perform is the
    #    silent wrong answer that rule exists to forbid, and ignoring the gate re-creates it.
    ("seek/capability-ignored", TRANSCODE_SEEK,
     "    !serverSupportsTranscodeOffset -> SeekMethod.NotOffered",
     "    false -> SeekMethod.NotOffered",
     "a transcode on a server without the extension does not offer the seek at all", 1),

    # 6. Rounding instead of flooring. The server starts the transcode at or before the second
    #    asked for, so a listener never loses audio they asked to hear; rounding up clips the first
    #    word of a sentence and there is nothing to see. 5_999 is the only input in the suite that
    #    tells the two programs apart, which is why it is there.
    ("seek/offset-rounds-up", TRANSCODE_SEEK,
     "    (targetPositionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND).toInt()",
     "    Math.round(targetPositionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND.toDouble()).toInt()",
     "the offset floors rather than rounds", 1),

    # 7. The re-issued stream filed under the full track's cache key. `TrackIdCacheKeyFactory`
    #    files every request under `MediaItem.customCacheKey`, so an offset stream carrying the
    #    bare id is written INTO THE MIDDLE of the full track's cache entry, and every later read
    #    of that track is served audio from the wrong place. Nothing observable breaks on the run
    #    that causes it -- the audio is right THIS time -- which is the whole reason it needs a
    #    probe rather than a comment.
    ("seek/offset-shares-the-track-cache-key", TRANSCODE_SEEK,
     '    if (timeOffsetSeconds <= 0) mediaId else "$mediaId$OFFSET_KEY_SEPARATOR$timeOffsetSeconds"',
     "    mediaId",
     "an offset stream is cached under its own key, and the top of the track under the plain id", 1),
    # ---- Plan 4 Task 8: the sleep timer's arithmetic ----------------------------------------
    ("sleep/fade-zero-divisor", SLEEP_FADE,
     "    fadeMs <= 0L -> if (remainingMs <= 0L) 0f else 1f\n",
     "",
     # A caller that turns the fade off reaches `x / 0f`, which is Infinity or NaN. Both are handed
     # straight to `player.volume`, which throws for one and is undefined for the other.
     "a fade length of zero does not divide by zero", 1),
    ("sleep/fade-negative-volume", SLEEP_FADE,
     "    remainingMs <= 0L -> 0f\n",
     "",
     # The tick lands past the deadline routinely, so this is the ordinary last observation of every
     # timer, not an edge. `Player.setVolume` throws on a negative.
     "a negative remaining time is silence, not a negative volume", 2),
    ("sleep/fade-length-ignored", SLEEP_FADE,
     "    else -> remainingMs.toFloat() / fadeMs",
     "    else -> remainingMs.toFloat() / DEFAULT_FADE_MS",
     # Every other assertion in that file passes 20 000, which is the default -- so a `fadeMs` that
     # is declared and then ignored is invisible to all of them.
     "the fade length is a parameter and it moves the answer", 1),
    ("sleep/shake-peak-gap", SHAKE_DETECTOR,
     "    if (peaks.isNotEmpty() && timestampMs - peaks.last() < minPeakGapMs) return false\n",
     "",
     # A 100 Hz accelerometer produces several above-threshold samples per physical jolt, so without
     # the gap one sharp knock is three peaks and every knock is a shake.
     "two samples from the same jolt do not count twice", 2),
    ("sleep/shake-reset-on-fire", SHAKE_DETECTOR,
     "    if (peaks.size < requiredPeaks) return false\n    reset()\n    return true",
     "    if (peaks.size < requiredPeaks) return false\n    return true",
     # Without it the buffer keeps the peaks that just fired, so the next single jolt fires again and
     # the phone is hair-trigger for the life of the process.
     "a second shake is detected after the first", 1),
    ("sleep/shake-window", SHAKE_DETECTOR,
     "    while (peaks.size > 1 && timestampMs - peaks.first() > windowMs) peaks.removeFirst()",
     "    while (peaks.size > 1 && timestampMs - peaks.first() > windowMs * 10) peaks.removeFirst()",
     # Picking the phone up, putting it down and picking it up again over three seconds is not a
     # shake. Without a window it is.
     "three jolts spread beyond the window are not", 2),
    ("sleep/shake-z-axis-only", SHAKE_DETECTOR,
     "    val magnitudeG = sqrt(x * x + y * y + z * z) / GRAVITY",
     "    val magnitudeG = sqrt(z * z) / GRAVITY",
     # Reading only z looks correct, because a resting phone's gravity is on z -- and every other
     # test in that file jolts z.
     "the magnitude uses all three axes", 1),
    # ---- Plan 4 Task 6: the swap, the policy, and the local-only guard ----------------------
    #
    # `resume/module-never` is the important one. Restoring `NeverResume` here is the whole defect
    # this project exists to fix, it is silent, and until this task it could not be probed at all:
    # the plan specified a provider taking `AudiobookSnapshot`, which needs Room, so every test of
    # the policy constructed it directly and the binding itself was ungated. The provider takes the
    # narrow `AudiobookItemSource` instead, which costs the graph nothing and puts the single most
    # important line in the application on this tier.
    ("resume/module-never", MEDIA_MODULE,
     "    AudiobookResumePolicy(source, clock)",
     "    NeverResume",
     "the undecorated resume policy is the one that actually resumes a book", 2),
    # Music resumes too -- the other half of the headline behaviour, reproduced on demand. A source
    # that answered for an unknown id is what `AudiobookSnapshot` becomes if its map is keyed off
    # `media_progress` rather than off the audiobook item map.
    ("resume/music-resumes", AUDIOBOOK_POLICY,
     "    val item = source.itemFor(mediaId) ?: return ResumeTarget(index, 0L)",
     "    val item = source.itemFor(mediaId)\n"
     "      ?: AudiobookItem(mediaId, mediaId, 12_345L, clock.millis(), false, 1.0f, false)",
     "music is not resumed, however much progress it has", 2),
    # A book you finished drops you two seconds before its end.
    ("resume/finished-ignored", AUDIOBOOK_POLICY,
     "    if (item.isFinished) return ResumeTarget(index, 0L)",
     "    if (item.isFinished && item.positionMs < 0L) return ResumeTarget(index, 0L)",
     "a finished item starts again from the beginning", 1),
    # The smart rewind deleted: every resume lands exactly where the row says, however long ago.
    ("resume/no-rewind", AUDIOBOOK_POLICY,
     "    return ResumeTarget(index, SmartRewind.resumePositionMs(item.positionMs, awayMs))",
     "    return ResumeTarget(index, item.positionMs - 0L * awayMs)",
     "the smart rewind is applied, and it depends on how long the book was away", 4),
    # A stale index from a car or a headset, taken at face value.
    ("resume/index-not-coerced", AUDIOBOOK_POLICY,
     "    val index = requestedIndex.coerceIn(0, (mediaIds.size - 1).coerceAtLeast(0))",
     "    val index = requestedIndex",
     "an index outside the queue does not throw", 1),
    # ---- local-only, which was prose in three specs and a privacy policy until this task --------
    #
    # A write endpoint on the wire. Read the failure message: it is what a future contributor sees,
    # and it names the constraint rather than the assertion.
    ("local-only/savePlayQueue-endpoint", SUBSONIC_API,
     '  @GET("rest/ping")',
     '  @GET("rest/savePlayQueue")\n'
     "  suspend fun savePlayQueue(@QueryMap params: Map<String, String>): SubsonicEnvelope\n"
     "\n"
     '  @GET("rest/ping")',
     "every declared endpoint is one of these reads", 1),
    # ...and the same thing one layer up, on the port every consumer holds. A default body, so the
    # mutation compiles: an abstract member would break `SubsonicClient` and the suite would never
    # run, which is a probe that proves nothing.
    ("local-only/createBookmark-port", SUBSONIC_SOURCE,
     "  suspend fun capabilities(): ServerCapabilities\n}",
     "  suspend fun capabilities(): ServerCapabilities\n"
     "\n"
     "  suspend fun createBookmark(songId: String, positionMs: Long) = Unit\n}",
     "the Subsonic port declares exactly these operations and no way to write progress", 1),
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
    # Plan 4 Task 2. Added AFTER the probes were, which is the wrong order and cost a real stray:
    # the first `audiobook/` probe mutated Chapter.kt, `revert()` named no file that matched, and
    # the second probe aborted the whole run with "PROBE TEXT NOT FOUND ... 0 matches" against a
    # file the first probe had left mutated. This list's own comment predicts that failure exactly.
    # The guard behaved correctly -- it failed loudly rather than probing a mutated tree -- and the
    # stray was found by `git status` immediately afterwards, per CLAUDE.md.
    CHAPTER,
    BOOK_SETTINGS,
    RESUME_POLICY,
    BROWSE_ID,
    BASE_URL,
    INTEGRATION_SERVICE,
    # Plan 7 Task 2, added in the same edit as the two `integrations/` probes -- never after, per
    # this list's own comment: a mutated file no `git checkout` names is left in the tree when the
    # run ends, which is the stray-mutation incident this script's header describes.
    STORE,
    CREDENTIALS,
    # Plan 7 Task 4, added in the same edit as the seven `integrations/lidarr-*` probes -- and
    # this list's own comment is not decoration: adding the probes WITHOUT these three names left
    # `LidarrAuthInterceptor.kt` mutated after the first probe, so the second probe's `apply()`
    # found 0 matches and aborted the family. It failed loudly and `git status` showed the stray
    # immediately, which is the mechanism working, but it cost a run.
    LIDARR_INT,
    LIDARR_CLIENT,
    LIDARR_EXC,
    # Plan 7 Task 5, added in the same edit as the ten `integrations/lidarr-{lookup,targets,...}`
    # probes, per this list's own comment.
    LIDARR_API,
    LIDARR_TARGETS,
    # Plan 7 Task 6, added in the same edit as the fifteen `integrations/lidarr-add-*` and
    # `integrations/lidarr-alreadyAdded-*` probes, per this list's own comment. Only
    # `LIDARR_PAYLOAD` is new -- the other three files this family mutates are already above, which
    # is exactly the state that makes forgetting this line easy and its consequence a stray.
    LIDARR_PAYLOAD,
    # Plan 7 Task 8, the five files the `integrations/bindery-*` family mutates. **Added after the
    # probes were, which is the wrong order and cost exactly the run this list's own comment
    # predicts**: the first probe mutated `BinderyAuthInterceptor.kt`, `revert()` named no file
    # that matched, and the second probe -- which searches for the same line -- aborted the whole
    # family with "PROBE TEXT NOT FOUND ... 0 matches". Fourth time in this file. The guard behaved
    # correctly and `git status` showed the stray immediately, per CLAUDE.md, but it cost 30
    # minutes. If you are adding a probe family, add its files here in the same edit.
    BINDERY_INT,
    BINDERY_CLIENT,
    BINDERY_API,
    BINDERY_EXC,
    BINDERY_STATUS,
    # Plan 7 Task 3, added in the same edit as the three `integrations/request-*` probes, per this
    # list's own comment.
    REQUEST_STATUS,
    MEDIA_REQUEST,
    # Plan 3 Task 12. Added AFTER its probes were -- the wrong order, and this list's own comment
    # says exactly what that costs: `seek/always-in-place` mutated `TranscodeSeek.kt`, `revert()`
    # named no file that matched, and `seek/capability-ignored` aborted the family with "PROBE TEXT
    # NOT FOUND ... 0 matches" against the file the first probe had left mutated. The guard behaved
    # correctly and `git status` showed the stray immediately. It still cost a run.
    TRANSCODE_SEEK,
    PLAYBACK_SERVICE,
    TASK_REMOVAL,
    PLAYBACK_STATE,
    TRACK_ID_KEY,
    BROWSE_TREE,
    BROWSE_TEXT,
    BROWSE_SURFACE,
    # Plan 5 Task 4, added in the same edit as the twelve `browse/page-*` and
    # `browse/extras-*` probes -- never after, per this list's own comment.
    BROWSE_PAGING,
    BROWSE_EXTRAS,
    # Plan 5 Task 5, added in the same edit as the five `browse/start-index-*` and
    # `browse/empty-selection-*` probes -- never after, per this list's own comment.
    BROWSE_SELECTION,
    BROWSE_TREE_REPOSITORY,
    # Plan 5 Task 6, added in the same edit as the eleven `browse/search-*`, `browse/spoken-*` and
    # `browse/normalise-*` probes -- never after, per this list's own comment. `BROWSE_TREE` is
    # already above; only this file is new.
    PLAY_FROM_SEARCH,
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
    # Plan 3 Task 11, added in the same edit as the `gain/` probes above, per this list's own
    # comment. `CLIENT` and `MIRROR` are already on the `git checkout` line inside `revert()`.
    GAIN_POLICY,
    # Plan 4 Task 1. Added in the same edit as the three `books/` probes above, as this list's own
    # comment requires: a mutated file no `git checkout` names is left in the tree when the run
    # ends, and the next agent's dirty-tree guard blames them for it.
    BOOK_FIXTURES,
    # Plan 4 Task 3. Added in the same edit as the fourteen `chapters/` and `timeline/` probes
    # above, as this list's own comment requires -- a mutated file no `git checkout` names is left
    # in the tree when the run ends, and the next agent's dirty-tree guard blames them for it.
    CHAPTER_ASSEMBLY,
    BOOK_TIMELINE,
    # Plan 4 Task 4. Added in the same edit as the eight `shelf/` probes above, as this list's own
    # comment requires -- a mutated file no `git checkout` names is left in the tree when the run
    # ends, and the next agent's dirty-tree guard blames them for it.
    BOOK_SUMMARIES,
    # Plan 4 Task 4, in the same edit that repointed `progress/clock-frozen` at this file.
    DATA_MODULE,
    # Plan 4 Task 5. Added in the same edit as the ten `rewind/` probes above, as this list's own
    # comment requires -- a mutated file no `git checkout` names is left in the tree when the run
    # ends, and the next agent's dirty-tree guard blames them for it.
    SMART_REWIND,
    # Plan 7 Task 7, added in the same edit as the twenty-four `integrations/lidarr-{status,percent,
    # progress,queue,album}-*` probes and `integrations/lidarr-key-into-another-header`, per this
    # list's own comment. `LIDARR_INT` and `LIDARR_CLIENT` are already above; these two are new,
    # and they are exactly the pair that makes forgetting this line easy -- most of the family
    # mutates files already listed, so the first stray would come from probe 1 (`LIDARR_STATUS`)
    # and abort probe 2 with "PROBE TEXT NOT FOUND ... 0 matches".
    LIDARR_STATUS,
    LIDARR_SOURCE,
    # Plan 4 Task 8. Added in the same edit as the seven `sleep/` probes above, as this list's own
    # comment requires -- a mutated file no `git checkout` names is left in the tree when the run
    # ends, and the next agent's dirty-tree guard blames them for it.
    SLEEP_FADE,
    SHAKE_DETECTOR,
    # Plan 6 Task 9, added in the same edit as the six `handover/` probes above, per this
    # list's own comment. `MEDIA_MODULE` and `core/cast` are already on `revert()`'s checkout
    # line -- only the decorator's own file is new, which is exactly the state that makes
    # forgetting this line easy and its consequence a stray mutation the next agent is blamed
    # for.
    HANDOVER_POLICY,
    # Plan 4 Task 6, added in the same edit as the seven `resume/` and `local-only/` probes above,
    # per this list's own comment -- the fifth time getting this order wrong has cost this file a
    # run. `MEDIA_MODULE` is already on `revert()`'s checkout line; these three are new.
    AUDIOBOOK_POLICY,
    SUBSONIC_SOURCE,
    SUBSONIC_API,
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
    # `:integrations:lidarr` joined in Plan 7 Task 4. An Android library, so `testDebugUnitTest`.
    # Its JVM tier reaches every class in the module except one: `LidarrSourceProvider`'s single
    # collaborator is `IntegrationCredentialStore`, which is DataStore over the Android Keystore,
    # so the provider is instrumented-only and no probe here can see it. The severability
    # behaviour it owns -- "not configured yields null" -- is proved by `LidarrSourceProviderTest`
    # on the emulator and recorded in task-4-report.md, the same way `LiveNavidromeTest`'s
    # test-side probes already are.
    "integrations/lidarr": "testDebugUnitTest",
    # `:integrations:bindery` joined in Plan 7 Task 8. An Android library, so `testDebugUnitTest`.
    # Its JVM tier reaches every class in the module except one, for the same reason
    # `:integrations:lidarr`'s does: `BinderySourceProvider`'s single collaborator is
    # `IntegrationCredentialStore`, which is DataStore over the Android Keystore, so the provider is
    # instrumented-only and no probe here can see it. The severability behaviour it owns -- "not
    # configured yields null", and "only Lidarr configured yields null" -- is proved by
    # `BinderySourceProviderTest` on the emulator and recorded in task-8-report.md.
    "integrations/bindery": "testDebugUnitTest",
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
                    ":integrations:core:test", ":integrations:lidarr:test",
                    ":integrations:bindery:test",
                    ":feature:player:test"],
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
