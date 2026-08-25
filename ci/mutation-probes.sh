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
# SCOPE. Production-code mutations only. The falsifiability probes for `LiveNavidromeTest`'s six
# scoping assertions are *test-side* (they change which musicFolderId the test sends, to prove the
# assertion discriminates rather than that the client is right), so they do not belong here; they
# are recorded in task-3-report.md instead. This script runs the JVM suites only and needs no
# Navidrome container.
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
import glob, html, re, shutil, subprocess, sys

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
CAST_HEADERS = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpHeaders.kt"
CAST_WIRE = "core/cast/src/main/kotlin/app/muplay/cast/http/HttpWire.kt"
CAST_CLIENT = "core/cast/src/main/kotlin/app/muplay/cast/http/CastHttpClient.kt"
CAST_NET = "core/cast/src/main/kotlin/app/muplay/cast/net/LocalNetworkOnly.kt"
BROWSE_ID = "core/model/src/main/kotlin/app/muplay/model/browse/BrowseId.kt"
BASE_URL = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt"
INTEGRATION_SERVICE = "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationService.kt"
PLAYBACK_SERVICE = "core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt"
TASK_REMOVAL = "core/media/src/main/kotlin/app/muplay/media/TaskRemovalPolicy.kt"
PLAYBACK_STATE = "core/media/src/main/kotlin/app/muplay/media/PlaybackState.kt"

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
     "a header is found whatever case the peer used", 7),
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
     "a public address is not local", 6),
    ("cast/no-local-guard", CAST_CLIENT,
     "    LocalNetworkOnly.require(host, address)\n", "",
     # The mutation that matters most in this module: without that one line MuPlay becomes an app
     # that will send plaintext anywhere it is pointed, and every other test stays green.
     "a public address is refused before a socket is opened", 1),
    ("cast/host-without-port", CAST_CLIENT,
     "      append(\"Host: \").append(hostHeader).append(HttpWire.CRLF)",
     "      append(\"Host: \").append(host).append(HttpWire.CRLF)",
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
     "every id encodes to its exact documented string", 5),
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
    ("media/second-player-construction", PLAYBACK_SERVICE,
     "    val player: ExoPlayer = playerFactory.create()",
     "    val player: ExoPlayer = ExoPlayer.Builder(this).build()",
     "an ExoPlayer is constructed in exactly one place", 1),
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
     "an empty queue stops the service even when the player is ready to play", 1),
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
     "the player's own duration wins, because it measured what is playing", 1),
    # `NOTHING_PLAYING` is what four downstream tasks render before anything is loaded. A `true`
    # here is an enabled "next" button with no queue behind it, and it moves no branch anywhere.
    ("media/nothing-playing-has-next", PLAYBACK_STATE,
     "      hasNext = false,\n      hasPrevious = false,",
     "      hasNext = true,\n      hasPrevious = false,",
     "nothing playing can step neither forward nor back", 1),
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
    PLAYBACK_SERVICE,
    TASK_REMOVAL,
    PLAYBACK_STATE,
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
                    ":integrations:core:test"],
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
