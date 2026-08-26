#!/usr/bin/env bash
set -euo pipefail
# -bitexact as an OUTPUT option is what makes this reproducible; -fflags
# +bitexact alone still leaves TAG:encoder=Lavf. Ogg/Opus embed a random
# stream serial without it.
OUT="$(dirname "$0")/fixtures"
mkdir -p "$OUT/Music/Test Artist/Test Album" "$OUT/Audiobooks/Test Author/Test Book"

for i in 1 2 3; do
  ffmpeg -y -f lavfi -i "sine=frequency=$((330 + i * 55)):duration=5:sample_rate=44100" \
    -c:a libmp3lame -b:a 64k -ac 1 -bitexact -map_metadata -1 \
    -metadata title="Track $i" -metadata artist="Test Artist" \
    -metadata album="Test Album" -metadata track="$i" \
    "$OUT/Music/Test Artist/Test Album/0$i - Track $i.mp3"
done

# The brief's own recipe hands this step a pre-built /tmp/book.m4b. That file
# doesn't build itself, so its generation is folded in here rather than left
# as an undocumented external prerequisite — this is the one deviation from
# the brief's literal script text. Three chapters
# (0-5s, 5-10s, 10-15s), Nero `chpl` (ffmpeg's mov muxer default — it never
# writes QuickTime `chap`), faststart (moov at front) — the common case per
# spike S3's own recommendation for the nightly fixture. `-movflags
# +use_metadata_tags` is deliberately never used: it writes `mdta`/`keys`
# atoms that break Navidrome's tag scanning (see spike S3).
if [ ! -f /tmp/book.m4b ]; then
  CHAPTERS="$(mktemp)"
  cat > "$CHAPTERS" << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=5000
title=Chapter 1
[CHAPTER]
TIMEBASE=1/1000
START=5000
END=10000
title=Chapter 2
[CHAPTER]
TIMEBASE=1/1000
START=10000
END=15000
title=Chapter 3
EOF
  ffmpeg -y -f lavfi -i "sine=frequency=220:duration=15:sample_rate=44100" \
    -i "$CHAPTERS" -map_metadata 1 \
    -c:a aac -b:a 32k -ac 1 -bitexact \
    -metadata title="Test Book" -metadata artist="Test Author" -metadata album="Test Book" \
    -movflags +faststart \
    /tmp/book.m4b
  rm -f "$CHAPTERS"
fi

cp /tmp/book.m4b "$OUT/Audiobooks/Test Author/Test Book/Test Book.m4b"

mkdir -p "$OUT/Audiobooks/Second Author/Second Book" \
         "$OUT/Audiobooks/Third Author/Tail Book" \
         "$OUT/Audiobooks/Fourth Author/Multi Part Book"

# ---------------------------------------------------------------------------------------------
# Second Book: four chapters of DELIBERATELY UNEQUAL length (4 s, 5 s, 6 s, 6 s).
#
# Equal-length chapters are why the original single fixture cannot discriminate: with 5 s
# chapters, `startMs == index * 5000` satisfies every assertion anyone would write, and a chapter
# reader that ignored the file entirely would pass. Unequal lengths make that constant wrong at
# chapter 2 and every chapter after it.
#
# The sine frequency (180 Hz) differs from every other fixture's on purpose. `ci/fixtures.md5`
# proves the *files* are distinct; a distinct tone is what lets a test that has only decoded PCM
# in hand -- `PcmAnalysis`, the gapless measurement -- tell which book it is actually hearing.
# ---------------------------------------------------------------------------------------------
CHAPTERS="$(mktemp)"
cat > "$CHAPTERS" << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=4000
title=Prologue
[CHAPTER]
TIMEBASE=1/1000
START=4000
END=9000
title=The Long Middle
[CHAPTER]
TIMEBASE=1/1000
START=9000
END=15000
title=A Turn
[CHAPTER]
TIMEBASE=1/1000
START=15000
END=21000
title=Epilogue
EOF
ffmpeg -y -f lavfi -i "sine=frequency=180:duration=21:sample_rate=44100" \
  -i "$CHAPTERS" -map_metadata 1 \
  -c:a aac -b:a 32k -ac 1 -bitexact \
  -metadata title="Second Book" -metadata artist="Second Author" -metadata album="Second Book" \
  -movflags +faststart \
  "$OUT/Audiobooks/Second Author/Second Book/Second Book.m4b"
rm -f "$CHAPTERS"

# ---------------------------------------------------------------------------------------------
# Tail Book: two chapters, and NO +faststart -- `moov` trails `mdat`.
#
# Spike S3 found that Media3 reads a non-faststart file over HTTP by issuing a targeted Range
# request to the tail rather than downloading the whole file, but it measured that against a
# hand-rolled Python server. Whether Navidrome's `format=raw` path behaves the same is the open
# question spec section 5 and section 12 both carry. This file is the only way to answer it.
#
# Note the missing `-movflags +faststart`. That omission is the entire point of this fixture; do
# not "fix" it. `ci/probe-chapters.sh` is not what guards it -- ffprobe reads chapters out of
# either layout identically, which is precisely why the atom order needs its own assertion
# somewhere that looks at the bytes.
# ---------------------------------------------------------------------------------------------
CHAPTERS="$(mktemp)"
cat > "$CHAPTERS" << 'EOF'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=7000
title=Head
[CHAPTER]
TIMEBASE=1/1000
START=7000
END=12000
title=Tail
EOF
ffmpeg -y -f lavfi -i "sine=frequency=160:duration=12:sample_rate=44100" \
  -i "$CHAPTERS" -map_metadata 1 \
  -c:a aac -b:a 32k -ac 1 -bitexact \
  -metadata title="Tail Book" -metadata artist="Third Author" -metadata album="Tail Book" \
  "$OUT/Audiobooks/Third Author/Tail Book/Tail Book.m4b"
rm -f "$CHAPTERS"

# ---------------------------------------------------------------------------------------------
# Multi Part Book: one file per chapter, no chapter atoms anywhere.
#
# This is the ordinary shape of a ripped audiobook and it is the only fixture that can prove
# "resume came back on the RIGHT FILE", which is half of per-book resume. The three durations are
# 4 s / 6 s / 5 s -- deliberately different from each other, so a duration mapping cannot pass by
# returning a constant, and deliberately NOT monotonic, so "sorted by duration" is not
# accidentally the same list as "sorted by track number".
#
# Three different frequencies for the same reason, and because every existing music fixture is
# exactly 40638 bytes: a test that told two fixtures apart by *length* was found to be comparing
# the same number. These three differ in duration, in bytes and in tone.
# ---------------------------------------------------------------------------------------------
part_titles=("Part One" "Part Two" "Part Three")
part_durations=(4 6 5)
part_freqs=(200 240 280)
for i in 0 1 2; do
  n=$((i + 1))
  ffmpeg -y -f lavfi \
    -i "sine=frequency=${part_freqs[$i]}:duration=${part_durations[$i]}:sample_rate=44100" \
    -c:a libmp3lame -b:a 64k -ac 1 -bitexact -map_metadata -1 \
    -metadata title="${part_titles[$i]}" -metadata artist="Fourth Author" \
    -metadata album="Multi Part Book" -metadata track="$n" \
    "$OUT/Audiobooks/Fourth Author/Multi Part Book/0$n - ${part_titles[$i]}.mp3"
done

find "$OUT" -type f -exec md5sum {} \; | sort -k2 > "$OUT/../fixtures.md5"
