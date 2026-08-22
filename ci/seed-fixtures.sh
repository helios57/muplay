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
# the brief's literal script text (see task-8-report.md). Three chapters
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
find "$OUT" -type f -exec md5sum {} \; | sort -k2 > "$OUT/../fixtures.md5"
