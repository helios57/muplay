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
# :core:model:test :core:database:test` -- three plain JVM invocations -- and `failures()` globs
# both `core/*/build/test-results/test/` (`:core:network`, `:core:model`) and
# `core/*/build/test-results/testDebugUnitTest/` (`:core:database`, an Android module's JVM-tier
# results directory). `:core:database` genuinely does carry JVM test source
# (`KeystoreCipherTest`, six tests -- its cryptographic contract needs no device) and `run_suite()`
# now runs it; an earlier version of this comment said `:core:database` "has no JVM test source at
# all", which was false and was corrected on a re-review that ran `KeystoreCipherTest` itself and
# found it green today.
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
import glob, html, re, subprocess, sys

FILTER = sys.argv[1] if len(sys.argv) > 1 else ""

CLIENT = "core/network/src/main/kotlin/app/muplay/network/SubsonicClient.kt"
AUTH = "core/network/src/main/kotlin/app/muplay/network/SubsonicAuth.kt"
TYPE = "core/model/src/main/kotlin/app/muplay/model/AlbumListType.kt"
MODEL = "core/network/src/main/kotlin/app/muplay/network/model/SubsonicResponse.kt"
MIRROR = "core/database/src/main/kotlin/app/muplay/database/MirrorMapper.kt"

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
    ("origin/coverArt-host", CLIENT,
     "val builder = normalizeBaseUrl(credentials.baseUrl).toHttpUrl().newBuilder()",
     'val builder = "http://elsewhere.example:9999/".toHttpUrl().newBuilder()',
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
              # 15 as of 7f27d4a. See the note on `expected failures` above before changing this.
              "the password never appears in the parameters", 15)

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


def revert():
    subprocess.run(["git", "checkout", "--", CLIENT, AUTH, TYPE, MODEL, MIRROR], check=True)


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
    subprocess.run(["./gradlew", "--quiet", ":core:network:test", ":core:model:test", ":core:database:test"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
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
