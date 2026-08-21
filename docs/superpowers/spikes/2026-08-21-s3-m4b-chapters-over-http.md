# Spike S3 — Does Media3 1.11 extract M4B chapters over HTTP?

**Status: BLOCKING spike, answered.** Confidence: high for the extraction
mechanics (reproducible, byte-level instrumented evidence); high-but-caveated
for the exact API surface (one non-obvious footgun found and isolated).

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
contents, and every `Chapter` metadata entry found were logged. In a second
pass, a `TransferListener` was wired to the underlying `DefaultHttpDataSource`
to record every `DataSpec` open (byte position requested) and cumulative bytes
actually transferred, per fixture — see "Does non-faststart require reading
the whole file?" below for why this was necessary.

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

All five fixtures, served over HTTP, produced chapters:

```
RESULT fixture=book_faststart_chpl.m4b      outcome=SUCCESS elapsedMs=1063 chapterCount=3
RESULT fixture=book_nofaststart_chpl.m4b    outcome=SUCCESS elapsedMs=293  chapterCount=3
RESULT fixture=book_faststart_chpl_big.m4b  outcome=SUCCESS elapsedMs=412  chapterCount=3
RESULT fixture=book_nofaststart_chpl_big.m4b outcome=SUCCESS elapsedMs=507 chapterCount=3
RESULT fixture=book_qt_chap.m4b             outcome=SUCCESS elapsedMs=124  trackGroups=2 chapterCount=4
```

(`book_qt_chap.m4b`'s 4th chapter is the trailing artifact described above,
reproduced identically to `ffprobe`'s own reading of the file — titles/times
for the 3 real chapters: `[start=0 end=5000 title=Chapter One]
[start=5000 end=10000 title=Chapter Two] [start=10000 end=15000 title=Chapter
Three]`, all correct.)

**Both Nero `chpl` and QuickTime `chap` are read — but only under one specific
wiring of `MetadataRetriever`, discovered by accident and then isolated
deliberately.**

## Finding 3 — a real footgun: `MetadataRetriever.Builder` needs an explicit `MediaSourceFactory`

The first pass used the simplest possible call:

```java
new MetadataRetriever.Builder(context, MediaItem.fromUri(url)).build()
```

Result: `chpl`-based chapters were found correctly (titles and start times),
**but every `Chapter.getEndTimeMs()` returned `-9223372036854775807`**
(`Long.MIN_VALUE + 1`, i.e. `C.TIME_UNSET`) — end times were silently
unpopulated. And **`book_qt_chap.m4b` returned `chapterCount=0`** — the
QuickTime `chap`-track chapters were silently dropped entirely, despite the
track being present (`trackGroups=2`).

Adding an explicit `MediaSourceFactory` fixed both, reproducibly:

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
with correct start/end/title. This was reproduced twice each way — default
`Builder()` with no factory consistently drops `chap`-track chapters and
leaves `chpl` end times unset; explicit `DefaultMediaSourceFactory` wiring
consistently gets both right — by rebuilding the app with only the
`.setMediaSourceFactory(...)` call commented out and re-running against the
identical fixtures and server.

I did not trace this to a specific line in Media3's source in the time
available, so I can't say *why* the default path differs — only that it
reliably does, twice over, on 1.11.0. **This is the single most
important finding for Plan 4**: it must call `MetadataRetriever.Builder`
with an explicit `MediaSourceFactory` (a plain `DefaultMediaSourceFactory`
wrapping whatever `DataSource.Factory` the rest of the app already uses for
Subsonic streaming, so cache/auth headers are consistent) — using the bare
`Builder(context, mediaItem).build()` form will silently produce incomplete
or missing chapter data with no exception, which is a much worse failure mode
than a crash.

## Finding 4 — Nero `chpl` never carries end times; the caller must derive them

Separately from the wiring issue above: even in the *correct* wiring,
`chpl`-sourced chapters' end times are literally inferred by Media3 from the
*next* chapter's start (this is the only explanation consistent with every
`chpl` fixture showing exact, correct `end` values equal to the next
chapter's `start`, since the Nero `chpl` atom format itself only stores a
list of `(startTime, title)` pairs — no end times). This matches the spec's
own description of chapter-boundary handling elsewhere (§5, "never cross a
chapter boundary backwards") and requires no code change, but confirms
`getEndTimeMs()` can be trusted once the `MediaSourceFactory` wiring above is
used correctly — it does not need to be independently recomputed by the
caller.

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
server-side or TCP-buffering assumption:

```
book_faststart_chpl.m4b      (64,819 B total):  totalBytesTransferred=4,213-4,349   opens: [pos=0] [pos=4136]
book_nofaststart_chpl.m4b    (64,819 B total):  totalBytesTransferred=64,896        opens: [pos=0] [pos=44]
book_faststart_chpl_big.m4b  (1,479,366 B total): totalBytesTransferred=38,949-90,033  opens: [pos=0] [pos=32584]
book_nofaststart_chpl_big.m4b(1,479,366 B total): totalBytesTransferred=32,661       opens: [pos=0] [pos=1446826] [pos=44]
```

**For the big non-faststart fixture — the behaviourally representative case,
since real audiobooks are tens to hundreds of MB, not 65 KB — only ~32 KB of
a 1.4 MB file was transferred, via an explicit seek straight to the moov
atom's byte offset (`pos=1446826`, exactly matching the box-walker's
independently-computed moov offset).** The `mdat` payload (the actual audio,
1.45 MB of the 1.48 MB file) was never fetched. This directly answers the
brief's concern — chapter extraction from a non-faststart file over HTTP does
**not** require downloading the whole file, at least at realistic audiobook
sizes.

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
   at both small and realistic (1.4 MB) sizes, via the QEMU-alias HTTP path.
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
           chapter.getEndTimeMs();     // long, populated when a next chapter exists
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

**No change needed to the Plan 4 chapter differentiator itself** — the
mechanism works, over HTTP, for realistically-sized non-faststart files,
without requiring Navidrome cooperation. The multi-file-book fallback
(§5, "one track = one chapter") remains available regardless.

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
