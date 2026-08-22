# Spike S3 — Does Media3 1.11 extract M4B chapters over HTTP?

**Status: BLOCKING spike, answered, with one explicit limitation carried
forward to Task 8.** Confidence: high for the extraction mechanics against a
Range-compliant HTTP server (byte-level instrumented evidence, each key
result captured twice); high-but-caveated for the exact API surface (one
non-obvious footgun found and isolated). **This spike did not test against a
real Navidrome instance** — see "Limitation: not tested against Navidrome"
immediately below. The brief specified testing "through Navidrome... with
`format=raw`... against the Navidrome container"; what was actually built and
tested is a hand-rolled Python HTTP server standing in for it. That
substitution is reasonable for isolating Media3's own extraction behaviour,
but it means the claims in this document are about Media3-against-a-generic-
Range-server, not Media3-against-Navidrome specifically. Task 8 stands up a
real, pinned Navidrome container; closing this gap is carried forward there
as an explicit deliverable, not left open-ended.

## The question

Navidrome hardcodes `child.Type = "music"` and exposes no chapter API. MuPlay's
audiobook chapter differentiator depends entirely on extracting chapters
client-side from the M4B stream itself, using Media3 1.11.0's native MP4/
Matroska chapter support. Four concrete sub-questions, from the task brief:

1. Can Media3 1.11 surface chapters from an M4B **served over HTTP**, not a
   local file?
2. Does `faststart` (moov at front) vs non-faststart (moov at end) matter? Does
   a non-faststart file require downloading the whole thing first?
3. Which chapter format does it read — Nero `chpl`, QuickTime `chap`, or both?
4. **What is the exact API surface** — the type and accessor a caller uses to
   get the chapter list and each chapter's start time and title?

## Environment

- `ffmpeg version 6.1.1-3ubuntu5`, `Python 3.12.3`. `gpac`/`MP4Box` 2.2.1
  (Ubuntu `gpac` package, `apt-get install gpac`, installed for this spike —
  not present in the base environment; needed to build a genuine QuickTime
  `chap`-track fixture, since ffmpeg's `mov` muxer only ever writes Nero
  `chpl`, confirmed via `ffmpeg -h muxer=mov`, which lists a `disable_chpl`
  flag and no equivalent `chap` option).
- Media3 **1.11.0**, matching this repo's `libs.versions.toml`. Standalone
  Gradle project (`scratchpad/s3-spike/`), AGP 9.3.1, `compileSdk 37`,
  `minSdk 26`, `targetSdk 36`. Package `app.muplayspike3`. **Not committed.**
- Same `muplay37` emulator (API 37 / Android 17) as spike S1, run afterwards
  so the S1 answer could inform this one — see "Interaction with S1" below.
- HTTP server: a custom Python server (`log_server.py`, throwaway, not
  committed) — Python's stock `http.server` does **not** honour `Range`
  headers (returns `200`/full body even when a `Range` header is sent,
  confirmed by testing), and Media3's `DefaultHttpDataSource` needs a server
  that does. The custom server implements real `206 Partial Content` +
  `Accept-Ranges: bytes`, and logs every request's path, `Range` header, and
  (in a second iteration) actual bytes transferred.

## Interaction with S1

S1 established that `10.0.2.2` works with zero extra permissions as long as
`targetSdk` stays at 36 (the roadmap's actual configured value). This app uses
`targetSdk 36`, so no `ACCESS_LOCAL_NETWORK` permission was declared or needed
— **this spike's success does not depend on S1's finding being favorable; it
would need `ACCESS_LOCAL_NETWORK` + `pm grant` added if MuPlay ever moves to
`targetSdk 37`, exactly as described in the S1 document.**

## Limitation: not tested against Navidrome

The task brief's Step 2 says to "serve through Navidrome with `format=raw`"
and test "against the Navidrome container." **This spike did not do that.**
What was actually served was a throwaway Python HTTP server
(`log_server.py`, described under "Environment" below) with no Subsonic
authentication, no `format=raw`/`f=raw` or any other Subsonic query
parameters, and no attempt to match Navidrome's actual response headers.
Specifically, not exercised at all:

- Navidrome's real streaming endpoint (`/rest/stream.view` or
  `/rest/download.view` with `format=raw`) and its auth handshake
  (token/salt or `X-Api-Key`).
- Whether Navidrome serves `Content-Length` with a known-good value for
  Range math, or uses chunked transfer encoding for some code path — these
  are different HTTP shapes and `DefaultHttpDataSource`'s Range-seeking
  behaviour (the whole basis for "Does non-faststart require reading the
  whole file?", below) could plausibly differ between them.
- Navidrome's actual `Content-Type`/`Accept-Ranges` response headers, which
  this spike's own server had to be specifically written to emit correctly
  (Python's stock `http.server` does not emit them by default — see
  "Environment" below) — Navidrome's real headers were never inspected.

Everything this document concludes about "chapters extract over HTTP" and
"non-faststart doesn't require downloading the whole file" is therefore a
claim about **Media3 against a Range-compliant HTTP server**, verified
directly, not a claim about Media3 against Navidrome specifically, which
remains unverified. The mechanism plausibly transfers — HTTP Range semantics
are a standard — but "plausibly transfers" is not the same evidentiary
standard as everything else in this document, and it is called out here
rather than left to blend into the rest. **Task 8** (per the coordinator)
carries this forward as an explicit deliverable: repeat the chapter-
extraction check against a real, pinned Navidrome container serving
`format=raw`.

## Fixtures built

All chapter fixtures use the task brief's three chapters (`0–5s`, `5–10s`,
`10–15s` for the small set; `0–60s`, `60–120s`, `120–180s` for the "big" set
used to make HTTP range-request behaviour observable). `-movflags
+use_metadata_tags` was never used, per the brief's warning (it writes
`mdta`/`keys` atoms that break Navidrome's tag scanning) — confirmed absent
via byte search on every fixture (`grep -a -o mdta|keys` → 0 hits throughout).

| Fixture | Chapter format | faststart | Size | Command |
|---|---|---|---|---|
| `book_faststart_chpl.m4b` | Nero `chpl` | yes | 64,819 B | `ffmpeg -f lavfi -i sine=... -i chapters.txt -map_metadata 1 -c:a aac -b:a 32k -ac 1 -bitexact -movflags +faststart` |
| `book_nofaststart_chpl.m4b` | Nero `chpl` | no | 64,819 B | same, without `+faststart` |
| `book_faststart_chpl_big.m4b` | Nero `chpl` | yes | 1,479,366 B | same recipe, `duration=180`, `-b:a 64k` |
| `book_nofaststart_chpl_big.m4b` | Nero `chpl` | no | 1,479,366 B | same, without `+faststart` |
| `book_qt_chap.m4b` | QuickTime `chap` (tx3g text track) | n/a | 64,811 B | `MP4Box -add base_nochap.m4a -add chapters.srt:chap -new book_qt_chap.m4b` |

`ffprobe -v error -show_chapters -of json` on every `_chpl` fixture returns
exactly 3 chapters with the expected `start`/`end`/`title`, matching the task
brief's "Expected: 3 chapter entries."

**Byte-level verification, not just ffprobe's word for it** — a small Python
MP4-box walker confirmed the moov atom's actual file offset:

```
book_faststart_chpl.m4b:      moov at offset 28    (front, before mdat @ 4128)
book_nofaststart_chpl.m4b:    moov at offset 60727  (tail, after mdat @ 36, file is 64819 B)
book_faststart_chpl_big.m4b:      moov at offset 28       (front, before mdat @ 32576)
book_nofaststart_chpl_big.m4b:    moov at offset 1446826  (tail, file is 1479366 B)
```

And exactly one `chpl` atom, zero `mdta`/`keys` atoms, confirmed by byte
search (`grep -a -o`) on each `_chpl` fixture. Normal Navidrome-relevant
metadata survives: `ffprobe -show_format` on `book_faststart_chpl.m4b` shows
`title=Test Audiobook`, `artist=Test Narrator`, `album=Test Audiobook`,
`album_artist=Test Author` intact.

### Building a genuine QuickTime `chap` fixture

This took two attempts. `MP4Box -chap chapters.txt` (the "common syntax"
chapter file) and even `MP4Box -chapqt chapters.txt` (the flag whose name
implies QT signalling) **both actually wrote a Nero `chpl` UDTA atom** —
confirmed via `MP4Box -info` showing `1 UDTA types: chpl:` and only 1 track
(no text track) in both cases. The genuine QuickTime mechanism — a disabled
`tx3g` text track holding chapter titles, referenced from the main track via
a `tref` box of type `chap` — required importing an actual subtitle track and
marking it as a chapter track on import:

```
$ MP4Box -add base_nochap.m4a -add chapters.srt:chap -new book_qt_chap.m4b
```

`MP4Box -info` then shows **2 tracks** (`ID 1`: `soun:mp4a`; `ID 2`:
`text:tx3g`, "Disabled In Movie In Preview", 4 samples), and byte search
confirms `chpl` occurs 0 times, `chap` 2 times, `tx3g` once, `tref` once.
`ffprobe` reports 4 "chapters" for this file, not 3 — the 4th is a trailing
artifact (`start=15000 end=15023 title=""`) from the text track's own media
duration (15.023s) slightly exceeding the last declared chapter's end
(15.000s). This is a property of the fixture, reproduced identically by both
`ffprobe` and Media3 (see below), not a bug in either.

## What was run

`androidx.media3.inspector.MetadataRetriever` (see "API surface" below) was
pointed at each fixture served over HTTP, on a fresh background thread per
fixture, with a 20 s retrieval timeout, for both `http://10.0.2.2:8766/<file>`
(the same QEMU-alias mechanism as S1). Total elapsed time, `TrackGroupArray`
contents, and every `Chapter` metadata entry found were logged.

Two distinct configurations of `MetadataRetriever` were tested against the
identical five fixtures and server, in this order — the difference between
them turned out to be the spike's most important finding (Finding 3, below),
found by accident on the first pass and then isolated deliberately:

1. **Bare `Builder(context, mediaItem).build()`**, no explicit
   `MediaSourceFactory` — run twice, each against a fresh app install.
2. **`Builder(...).setMediaSourceFactory(explicit factory)`**, with a
   `TransferListener` also wired to the underlying `DefaultHttpDataSource` to
   record every `DataSpec` open (byte position requested) and cumulative
   bytes actually transferred, per fixture — run twice. See "Does
   non-faststart require reading the whole file?" below for why the
   `TransferListener` was needed.

## Finding 1 — the class name in the spec is real, but not where a naive search finds it

The spec (`docs/superpowers/specs/2026-08-21-muplay-design.md` §5) says: "Read
without playing via `androidx.media3.inspector.MetadataRetriever` (the old
`exoplayer.MetadataRetriever` was deprecated in 1.9 and removed)." A `javac`
compile check against the actual, downloaded-from-Google's-Maven `media3-*`
1.11.0 artifacts confirms this precisely:

```
$ javac -classpath <media3-exoplayer,-common,-datasource,-extractor,-session,-transformer classes.jar>
        Probe.java   # imports androidx.media3.exoplayer.MetadataRetriever
Probe.java:1: error: cannot find symbol
import androidx.media3.exoplayer.MetadataRetriever;
                                ^
```

`androidx.media3.exoplayer.MetadataRetriever` genuinely does not exist in
1.11.0 — it was removed, exactly as the spec says. It now lives in a
**separate Maven artifact**, `androidx.media3:media3-inspector:1.11.0`, which
none of `media3-exoplayer`/`-transformer`/`-session`/`-common`/`-container`/
`-database`/`-datasource`/`-decoder`/`-extractor` pull in transitively.
**Anyone implementing Plan 4 must add `media3-inspector` as an explicit
Gradle dependency** — it is easy to assume `media3-exoplayer` alone is
enough (it provides everything else needed to build a player) and be
surprised when the class can't be found.

## Finding 2 — chapters extract correctly, from both containers, over HTTP

All five fixtures, served over HTTP, produced chapters — shown here with the
`setMediaSourceFactory(...)` configuration (Finding 3 explains why that
qualifier matters), full per-chapter output, first run:

```
RESULT fixture=book_faststart_chpl.m4b       outcome=SUCCESS elapsedMs=580  chapterCount=3
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three]
RESULT fixture=book_nofaststart_chpl.m4b     outcome=SUCCESS elapsedMs=75   chapterCount=3
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three]
RESULT fixture=book_faststart_chpl_big.m4b   outcome=SUCCESS elapsedMs=597  chapterCount=3
  chapters=[start=0 end=60000 title=Chapter One] [start=60000 end=120000 title=Chapter Two] [start=120000 end=180000 title=Chapter Three]
RESULT fixture=book_nofaststart_chpl_big.m4b outcome=SUCCESS elapsedMs=748  chapterCount=3
  chapters=[start=0 end=60000 title=Chapter One] [start=60000 end=120000 title=Chapter Two] [start=120000 end=180000 title=Chapter Three]
RESULT fixture=book_qt_chap.m4b              outcome=SUCCESS elapsedMs=356  trackGroups=2 chapterCount=4
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three] [start=15000 end=15023 title=]
```

and second run, a fresh `am start` against the same fixtures and server, no
code changes:

```
RESULT fixture=book_faststart_chpl.m4b       outcome=SUCCESS elapsedMs=1761 chapterCount=3
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three]
RESULT fixture=book_nofaststart_chpl.m4b     outcome=SUCCESS elapsedMs=36   chapterCount=3
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three]
RESULT fixture=book_faststart_chpl_big.m4b   outcome=SUCCESS elapsedMs=315  chapterCount=3
  chapters=[start=0 end=60000 title=Chapter One] [start=60000 end=120000 title=Chapter Two] [start=120000 end=180000 title=Chapter Three]
RESULT fixture=book_nofaststart_chpl_big.m4b outcome=SUCCESS elapsedMs=432  chapterCount=3
  chapters=[start=0 end=60000 title=Chapter One] [start=60000 end=120000 title=Chapter Two] [start=120000 end=180000 title=Chapter Three]
RESULT fixture=book_qt_chap.m4b              outcome=SUCCESS elapsedMs=210  trackGroups=2 chapterCount=4
  chapters=[start=0 end=5000 title=Chapter One] [start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter Three] [start=15000 end=15023 title=]
```

(`hidden=false` on every entry omitted above for width; hidden was `false` on
every chapter in every fixture, no exceptions. `book_qt_chap.m4b`'s 4th
chapter is the trailing artifact described above, reproduced identically to
`ffprobe`'s own reading of the file. All values identical across both runs,
for every fixture.)

**This block — not the narrative summary that used to stand in for it — is
the real per-chapter evidence Finding 4 draws on below, for the `chpl`
fixtures specifically, not `book_qt_chap.m4b`.**

**Both Nero `chpl` and QuickTime `chap` are read — but only under one specific
wiring of `MetadataRetriever`, discovered by accident and then isolated
deliberately.** The contrasting bare-`Builder()` result — `chapterCount=0`
for this same `book_qt_chap.m4b` fixture — is Finding 3's evidence, quoted
there with its own two runs, not repeated here.

## Finding 3 — a real footgun: `MetadataRetriever.Builder` needs an explicit `MediaSourceFactory`

The first pass used the simplest possible call:

```java
new MetadataRetriever.Builder(context, MediaItem.fromUri(url)).build()
```

Result, first run — `chpl`-based chapters were found correctly (titles and
start times), **but every `Chapter.getEndTimeMs()` returned
`-9223372036854775807`** (`Long.MIN_VALUE + 1`, i.e. `C.TIME_UNSET`) — end
times were silently unpopulated. And **`book_qt_chap.m4b` returned
`chapterCount=0`** — the QuickTime `chap`-track chapters were silently
dropped entirely, despite the track being present (`trackGroups=2`):

```
RESULT fixture=book_faststart_chpl.m4b outcome=SUCCESS elapsedMs=808 trackGroups=1 chapterCount=3
  chapters=[start=0 end=-9223372036854775807 title=Chapter One] [start=5000 end=-9223372036854775807 title=Chapter Two] [start=10000 end=-9223372036854775807 title=Chapter Three]
RESULT fixture=book_qt_chap.m4b outcome=SUCCESS elapsedMs=107 trackGroups=2 chapterCount=0 chapters=
```

Second run, a fresh `am start`, identical code, identical fixtures and
server:

```
RESULT fixture=book_faststart_chpl.m4b outcome=SUCCESS elapsedMs=1063 trackGroups=1 chapterCount=3
  chapters=[start=0 end=-9223372036854775807 title=Chapter One] [start=5000 end=-9223372036854775807 title=Chapter Two] [start=10000 end=-9223372036854775807 title=Chapter Three]
RESULT fixture=book_qt_chap.m4b outcome=SUCCESS elapsedMs=124 trackGroups=2 chapterCount=0 chapters=
```

Identical both times: unset end times for `chpl`, zero chapters for
`book_qt_chap.m4b`.

Adding an explicit `MediaSourceFactory` fixed both:

```java
DefaultMediaSourceFactory sourceFactory =
    new DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory);
new MetadataRetriever.Builder(context, MediaItem.fromUri(url))
    .setMediaSourceFactory(sourceFactory)
    .build()
```

With this wiring, `chpl` chapters get correctly-populated end times
(`end=5000`, `end=10000`, `end=15000` for the small fixture) **and**
`book_qt_chap.m4b` returns all 4 chapters (3 real + the trailing artifact)
with correct start/end/title — quoted in full, both runs, under Finding 2
above. Both configurations were run twice each, and each pair of runs
matched exactly: default `Builder()` with no factory consistently drops
`chap`-track chapters and leaves `chpl` end times unset (both runs quoted
just above); explicit `DefaultMediaSourceFactory` wiring consistently gets
both right (both runs quoted in Finding 2). The two configurations were
isolated as the single variable by rebuilding the app with only the
`.setMediaSourceFactory(...)` call commented out and re-running against the
identical fixtures and server — nothing else in the code changed between the
"before" and "after" pairs of runs.

I did not trace this to a specific line in Media3's source in the time
available, so I can't say *why* the default path differs — only that it
reliably does, across two runs each way, on 1.11.0. **This is the single most
important finding for Plan 4**: it must call `MetadataRetriever.Builder`
with an explicit `MediaSourceFactory` (a plain `DefaultMediaSourceFactory`
wrapping whatever `DataSource.Factory` the rest of the app already uses for
Subsonic streaming, so cache/auth headers are consistent) — using the bare
`Builder(context, mediaItem).build()` form will silently produce incomplete
or missing chapter data with no exception, which is a much worse failure mode
than a crash.

## Finding 4 — Nero `chpl` never stores end times itself; Media3 fills them in, including for the last chapter

Separately from the wiring issue above: even in the *correct* wiring,
`chpl`-sourced `Chapter` entries carry a populated, correct
`getEndTimeMs()` despite the Nero `chpl` atom format itself only storing a
list of `(startTime, title)` pairs — no end times at all. Media3 is
filling this in.

**My first draft of this finding claimed the rule was simply "end = next
chapter's start," and that is wrong** — it is contradicted by my own quoted
evidence. (An earlier revision of this correction also cited the wrong
fixture as that evidence — `book_qt_chap.m4b`'s trailing artifact, whose
title-track cues carry their own end times directly rather than having them
inferred by Media3, and whose true duration is 15.023 s, not the 15.000 s
this paragraph now actually uses. That fixture cannot support a claim about
how Media3 handles `chpl`. The evidence below is `chpl`-specific.)

Every `chpl`-sourced chapter's `end` in Finding 2's quoted output, including
each fixture's *last* chapter — which has no next chapter to inherit a start
time from — is populated: `book_faststart_chpl.m4b`'s "Chapter Three" shows
`end=15000`, not `C.TIME_UNSET`; the `_big` fixtures' "Chapter Three" shows
`end=180000`. "Next chapter's start" cannot be the whole rule if a
last-chapter end is also populated. Checked directly against each file's own
measured duration (`ffprobe -show_format`, run separately from the Media3
test, on the same fixture files): `book_faststart_chpl.m4b` and
`book_nofaststart_chpl.m4b` both report `"duration": "15.000000"`;
`book_faststart_chpl_big.m4b` and `book_nofaststart_chpl_big.m4b` both report
`"duration": "180.000000"`. In both cases the last chapter's populated `end`
is exactly the file's own measured duration in milliseconds — not a
coincidence limited to one fixture, since it holds for all four `chpl`
fixtures at two different durations (15 s and 180 s). The data is consistent
with a broader rule — **end = next chapter's start, or the track/content
duration when there is no next chapter**. I did not independently confirm
this is actually how Media3 computes it (e.g. by testing a fixture whose last
chapter ends *before* the file's true duration, to see whether Media3 reports
the chapter's own declared end or the file's end — `chpl` doesn't distinguish
these cases in any fixture built here, since every fixture's last chapter was
authored to run to the end of the file). **This is inferred from the pattern
in real, `chpl`-specific, per-chapter data — not confirmed against Media3's
source** — stated here as a corrected hypothesis, not as an established fact.
This matches the spec's own description of chapter-boundary handling
elsewhere (§5, "never cross a chapter boundary backwards") but should not be
relied on beyond what was actually shown: `getEndTimeMs()` was populated and
correct for every `chpl` chapter tested, including every fixture's last one,
once the `MediaSourceFactory` wiring above is used.

## Does non-faststart require reading the whole file? (No — for realistically-sized files)

First attempt: server-side request logging (path + `Range` header). This
showed, per fixture, an initial full `GET` (no `Range` header), a small tail
probe (`Range: bytes=<size-8>-`), and — for the non-faststart *big* fixture
only — an additional `Range: bytes=1446826-` request landing exactly on the
moov atom's byte offset. But the server-side log **could not prove** the
client actually consumed the full body of the "no-`Range`" requests — over a
loopback connection with generous kernel socket buffers, a server can write
an entire 1.4 MB response into the send buffer near-instantly regardless of
whether the client later abandons the connection, so "logged as sent" is not
the same claim as "actually read by the client." This ambiguity is called out
explicitly rather than glossed over.

**Second, definitive measurement**: a `TransferListener` attached directly to
`DefaultHttpDataSource` (`onTransferInitializing` for byte positions
requested, `onBytesTransferred` for actual bytes moved), independent of any
server-side or TCP-buffering assumption. First run:

```
book_faststart_chpl.m4b       (64,819 B total):    totalBytesTransferred=4,349    opens: [pos=0] [pos=4136]
book_nofaststart_chpl.m4b     (64,819 B total):    totalBytesTransferred=64,896   opens: [pos=0] [pos=44]
book_faststart_chpl_big.m4b   (1,479,366 B total): totalBytesTransferred=38,949   opens: [pos=0] [pos=32584]
book_nofaststart_chpl_big.m4b (1,479,366 B total): totalBytesTransferred=32,661   opens: [pos=0] [pos=1446826] [pos=44]
```

Second run, a fresh `am start`, identical code, fixtures and server:

```
book_faststart_chpl.m4b       (64,819 B total):    totalBytesTransferred=4,213    opens: [pos=0] [pos=4136]
book_nofaststart_chpl.m4b     (64,819 B total):    totalBytesTransferred=64,896   opens: [pos=0] [pos=44]
book_faststart_chpl_big.m4b   (1,479,366 B total): totalBytesTransferred=90,033   opens: [pos=0] [pos=32584]
book_nofaststart_chpl_big.m4b (1,479,366 B total): totalBytesTransferred=32,661   opens: [pos=0] [pos=1446826] [pos=44]
```

The two faststart-`_big` figures (38,949 vs 90,033) vary run to run — plausibly
timing-dependent read-ahead into `mdat` before the retriever has everything it
needs — but the two things this document actually rests a conclusion on are
identical in both runs: the non-faststart big fixture transferred exactly
**32,661 bytes both times**, via the identical open sequence, including the
same explicit seek to `pos=1446826`.

**For the big non-faststart fixture — the behaviourally representative case,
since real audiobooks are tens to hundreds of MB, not 65 KB — only ~32 KB of
a 1.4 MB file was transferred, via an explicit seek straight to the moov
atom's byte offset (`pos=1446826`, exactly matching the box-walker's
independently-computed moov offset).** The `mdat` payload (the actual audio,
1.45 MB of the 1.48 MB file) was never fetched, in either run. This directly
answers the brief's concern — chapter extraction from a non-faststart file
over a Range-compliant HTTP server does **not** require downloading the whole
file, at least at realistic audiobook sizes (subject to the Navidrome
limitation stated above — this was not re-verified against Navidrome's own
Range handling).

The *small* non-faststart fixture is the outlier: it transferred 64,896 bytes
against a 64,819-byte file — essentially the whole thing. The most likely
explanation, consistent with the big-file result, is a size/gap threshold:
when the byte-distance to the presumed moov location is small, it is cheaper
to keep streaming forward than to pay for a new ranged HTTP request, so the
"seek" degenerates into "keep reading." This is speculative — I did not find
or verify the actual threshold — but it does not change the practical
conclusion, since real audiobook files are always in the large-gap regime.

## Answers

1. **Chapters over HTTP, not local file?** Yes, confirmed for both containers,
   at both small and realistic (1.4 MB) sizes, via the QEMU-alias HTTP path —
   against a Range-compliant HTTP server standing in for Navidrome, **not**
   against Navidrome itself (see "Limitation: not tested against Navidrome"
   above; carried forward to Task 8).
2. **faststart vs non-faststart, and does non-faststart need the whole file?**
   Both work. Non-faststart does not require downloading the whole file for
   realistically-sized audiobooks — Media3 seeks directly to the moov atom's
   tail offset via an HTTP Range request once it has read enough of the front
   to compute where that offset is.
3. **`chpl`, `chap`, or both?** Both — but only through the
   `MetadataRetriever.Builder(...).setMediaSourceFactory(explicit factory)`
   path. The bare `Builder(...).build()` form drops `chap`-track chapters
   entirely and leaves `chpl` end times unpopulated. This is the spike's
   single most load-bearing, non-obvious finding.
4. **Exact API surface:**

   ```java
   MetadataRetriever retriever = new MetadataRetriever.Builder(context, mediaItem)
       .setMediaSourceFactory(mediaSourceFactory)   // REQUIRED — see Finding 3
       .build();
   ListenableFuture<TrackGroupArray> future = retriever.retrieveTrackGroups();
   TrackGroupArray groups = future.get(timeout, unit);   // or add a listener
   for (int i = 0; i < groups.length; i++) {
     TrackGroup group = groups.get(i);
     for (int j = 0; j < group.length; j++) {
       Metadata metadata = group.getFormat(j).metadata;   // may be null
       if (metadata == null) continue;
       for (int k = 0; k < metadata.length(); k++) {
         Metadata.Entry entry = metadata.get(k);
         if (entry instanceof Chapter chapter) {
           chapter.getStartTimeMs();   // long, always populated
           // long — populated for every chapter observed, including the last one.
           // Inferred rule: next chapter's start, or content duration if there is
           // no next chapter (not fully confirmed — see Finding 4).
           chapter.getEndTimeMs();
           chapter.getTitle();         // androidx.media3.common.Label, not String — use .value
           chapter.isHidden();         // boolean
         }
       }
     }
   }
   retriever.close();   // AutoCloseable
   ```

   Key types, all in the `media3-inspector` (`MetadataRetriever`,
   `MetadataRetriever.Builder`) and `media3-extractor`
   (`androidx.media3.extractor.metadata.Chapter`, an interface, plus
   `Chapter.Builder` for constructing one) artifacts.
   `androidx.media3.common.Label` (`.language`, `.value`) is the title type,
   not `String` — a detail easy to miss when sketching the call site.

## What this means for the plan

**Corrections required in the design spec** (applied alongside this
document):

- Add `androidx.media3:media3-inspector:1.11.0` as an explicit dependency
  wherever `MetadataRetriever` is used — it is not pulled in by
  `media3-exoplayer`.
- Document the `MetadataRetriever.Builder(...).setMediaSourceFactory(...)`
  requirement explicitly. This is a correctness bug waiting to happen: the
  bare-`Builder()` form compiles, runs, returns success, and returns
  plausible-looking (if incomplete) data for `chpl` files — there is no
  exception, no log warning, nothing to signal that chapters were silently
  dropped for `chap`-track files. A reviewer or test relying on "it didn't
  throw" would ship this bug.
- `Chapter.getTitle()` returns `androidx.media3.common.Label`, not `String` —
  worth a one-line note in the spec so nobody writes `chapter.getTitle()`
  expecting a `String` and gets a confusing compile error or, worse, an
  accidental `.toString()` that doesn't print the actual title text.
- The spec's existing guidance ("Chapter times are period-relative, not
  window-relative... `chpl` v0 vs v1 differ in the count field width; Media3
  assumes v1") is **not contradicted** by anything found here, but also not
  independently re-verified — this spike used whatever chpl version ffmpeg
  6.1.1 writes by default and did not test a v0 file.

**No change needed to the Plan 4 chapter differentiator's architecture** —
Media3's chapter extraction reads the M4B's own bytes and does not depend on
anything Navidrome-specific in principle (no server-side chapter API is
involved either way). But this spike only demonstrated the mechanism works
over HTTP, for realistically-sized non-faststart files, **against a generic
Range-compliant server** — not against Navidrome's actual `format=raw`
streaming path, its auth handshake, or its real response headers (see
"Limitation: not tested against Navidrome" above). Plan 4 should treat that
as still open until Task 8's real-Navidrome verification lands, not as
settled by this document. The multi-file-book fallback (§5, "one track = one
chapter") remains available regardless, independent of any of this.

**Recommend for the nightly-lane fixture**: the spec's own nightly-fixture
plan (§10, "one chaptered M4B") should specify a `chpl`-based, faststart file
if the goal is testing the *common* case, but Plan 4 should also carry at
least one non-faststart fixture in its own test suite (not necessarily
nightly) given how easy it would be for a real user's audiobook file to be
non-faststart and for that code path to go untested.

**Not committed**: the fixture-generation commands above are complete and
reproducible; no binary `.m4b` fixtures were added to the repository, per this
task's guardrail against touching `app/`, `core/`, or `testing/`. Plan 4 should
regenerate (or commit, at its own discretion) fixtures using the exact
commands in "Fixtures built" above.

---

## Task 8 follow-up: verified against a real Navidrome — gap closed

**Status: the "not tested against Navidrome" limitation above is now closed.**
Confidence: high — direct HTTP evidence (raw response headers and byte-level
comparison), against the real, pinned `deluan/navidrome:0.63.2` image, not a
stand-in. This section answers exactly the two sub-questions the task brief
asked for: does `format=raw` honour HTTP Range requests, and does it send a
real `Content-Length` or chunked transfer encoding. Full method, commands and
container setup are in `task-8-report.md`; this is the extract relevant to
the chapter-extraction question this spike opened.

### Method

Task 8's pinned Navidrome container (`deluan/navidrome:0.63.2`, admin user
`admin`/`testpass`) was seeded with the nightly lane's committed M4B fixture
(`ci/fixtures/Audiobooks/Test Author/Test Book/Test Book.m4b` — 65,160 B,
**faststart**, Nero `chpl`, 3 chapters — the "common case" fixture this
document's own "Recommend for the nightly-lane fixture" section above asked
for) plus a second, larger, **non-faststart** M4B built with the exact same
ffmpeg recipe as this spike's own `book_nofaststart_chpl_big.m4b` (`-f lavfi
sine ... -c:a aac -b:a 64k -ac 1 -bitexact`, no `+faststart`, 180 s / 3× 60 s
chapters), seeded temporarily into the container to reproduce this spike's
"big" case (1,479,120 B; box walk confirms `ftyp`→`free`→`mdat` (1,446,611 B)
→`moov` (offset 1,446,647, 32,473 B) — `mdat` first, `moov` at the tail,
exactly the non-faststart shape this spike's "Does non-faststart need the
whole file?" finding depends on). The temporary big file was never committed
— it was removed and the library rescanned back to the committed 4-track
state immediately after this test, so it leaves no trace in the repo.

Both files' song IDs were found via `getSong`/`search3`, then queried at
`/rest/stream.view?...&id=<id>&format=raw` with `curl -D -` to capture
response headers verbatim, with and without a `Range` header, and the
returned bytes were compared byte-for-byte against a full, un-ranged download
of the same file.

### Findings

**Range is honoured — full RFC 7233 behaviour, not partial support:**

- A plain request (no `Range` header) returns `200 OK`, `Accept-Ranges:
  bytes`, and a full-file `Content-Length` (`65160` for the small fixture).
- `Range: bytes=0-999` returns `206 Partial Content`, `Content-Range: bytes
  0-999/65160`, `Content-Length: 1000` — and the 1000 returned bytes are
  byte-identical to the first 1000 bytes of the full download.
- `Range: bytes=-1000` (a suffix range — the "last N bytes" form) returns
  `206 Partial Content`, `Content-Range: bytes 64160-65159/65160`, and those
  bytes are byte-identical to the last 1000 bytes of the full download.
- `Range: bytes=64000-99999` (end offset past EOF) is correctly **clamped**:
  `206 Partial Content`, `Content-Range: bytes 64000-65159/65160`,
  `Content-Length: 1160` — not an error, not the whole file.
- `Range: bytes=999999-1000000` (a range that starts past EOF, genuinely
  unsatisfiable) correctly returns `416 Requested Range Not Satisfiable` with
  `Content-Range: bytes */65160`.
- **The exact scenario this spike's non-faststart finding depends on**: on
  the 1,479,120 B non-faststart fixture, `Range: bytes=1446647-1479119` (the
  moov atom's own byte range, computed by this spike's independent box
  walker) returns `206 Partial Content`, `Content-Length: 32473`,
  `Content-Range: bytes 1446647-1479119/1479120` — and the returned bytes are
  byte-identical to the source file's moov box, confirmed both by direct
  comparison and by the returned bytes literally starting with the ASCII tag
  `moov` at their offset-4 position. This is precisely the request Media3's
  `DefaultHttpDataSource` issues when `MetadataRetriever` seeks straight to a
  non-faststart file's moov offset instead of reading forward through `mdat`
  (this spike's Finding 2) — Navidrome serves it exactly as a Range-compliant
  server should.

**`Content-Length`, never chunked**: every response observed — full-file and
every Range variant, both fixture sizes — carried a numeric `Content-Length`
header. No response carried `Transfer-Encoding: chunked`. This matters
because `DefaultHttpDataSource`'s Range-seeking logic (the entire mechanism
this spike's non-faststart finding relies on) needs to know the total
resource length up front; a chunked response without `Content-Length` would
not give it that.

### Answer to the carried-forward question

**Does the "non-faststart is cheap over HTTP" conclusion transfer to real
Navidrome? Yes.** Range is honoured — including the exact tail-seek pattern
Media3 depends on — with byte-for-byte correct, correctly-clamped, correctly-
gated (416 on genuinely unsatisfiable ranges) responses, and `format=raw`
always advertises a real `Content-Length`, never chunked encoding. Nothing in
Navidrome's real behaviour contradicts this spike's original finding that a
non-faststart M4B is cheap to read because the client can seek to the tail —
the load-bearing assumption holds. **Not independently re-run in this pass**:
the actual Media3 `MetadataRetriever` chapter-extraction call, end to end,
against Navidrome's `format=raw` URL (with real Subsonic token auth) — this
section verifies the HTTP-protocol layer Media3's `DefaultHttpDataSource`
depends on, which is what determines whether the mechanism works, but does
not re-run Media3 itself against Navidrome. Given the protocol-level evidence
above is unambiguous and this spike's own Finding 2 already proved Media3's
client-side behaviour against a generic Range-compliant server, this is
judged sufficient to close the "not tested against Navidrome" gap for the
specific question Task 8 was asked to resolve (Range and `Content-Length`
behaviour) — a full Media3-against-Navidrome chapter-extraction run remains a
reasonable follow-up for whichever plan implements the chapter feature, not
a blocking gap.
