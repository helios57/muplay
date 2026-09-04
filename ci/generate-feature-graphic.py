#!/usr/bin/env python3
"""Generates MuPlay's Play Store feature graphic (1024x500) from the launcher icon's own geometry.

Run from the repository root:

    python3 ci/generate-feature-graphic.py

Output, overwritten in place:

  play/feature-graphic.png    1024x500, RGB (no alpha), the Play Console's required size

Why this imports `ci/generate-launcher-icon.py` rather than restating the mark: the feature
graphic and the launcher icon are the same brand, and the one way this repository has repeatedly
been bitten is a second copy of something discoverable from the first. `GEOMETRY` and the shape
rasteriser both come from that module, so editing the mark there changes this asset too and there
is no second set of coordinates to forget. The only thing this file owns is the *layout* -- where
the mark sits on a 1024x500 canvas and what text sits beside it.

**The text is measured, not guessed.** `fit_font` shrinks each string until it fits its column and
raises if it cannot at the minimum legible size, so a longer tagline fails the run instead of
silently rendering off the edge of a store asset nobody looks at closely until it is published.

**The font is looked up from a candidate list and the run fails if none is found.** Pillow's
`ImageFont.load_default()` is a tiny bitmap face; falling back to it would produce a technically
valid 1024x500 PNG with an unreadable wordmark -- a silent degradation, which is the defect class
this project exists to keep out. The face actually used is printed.
"""

import importlib.util
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))


def _launcher_icon_module():
    """`ci/generate-launcher-icon.py`, imported by path.

    A hyphen is not a legal identifier, so a plain `import` cannot reach it; the alternative --
    renaming that script -- would break the reference in its own header and in Task 1's report.
    """
    path = os.path.join(HERE, "generate-launcher-icon.py")
    spec = importlib.util.spec_from_file_location("muplay_launcher_icon", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ICON = _launcher_icon_module()

# --------------------------------------------------------------------------------------------
# Layout. Every number here is in feature-graphic pixels; nothing here describes the mark itself.
# --------------------------------------------------------------------------------------------

WIDTH = 1024
HEIGHT = 500

# Play crops a feature graphic for some placements, so nothing that has to be read lives in the
# outer margin. Checked at the end of `main`, not merely intended.
MARGIN = 48

# The brand field, restated at this canvas's proportions from the icon's own two background
# shapes: the flat field, then one lighter disc offset up-and-left that overflows the canvas so
# what is left inside it is a single soft diagonal edge. Colours are read from the icon's
# GEOMETRY rather than written again here.
FIELD_COLOUR = ICON.GEOMETRY["background"][0]["colour"]
DISC_COLOUR = ICON.GEOMETRY["background"][1]["colour"]
DISC_CENTRE = (150.0, 40.0)
DISC_RADIUS = 470.0

# The mark: the icon's foreground shapes, scaled so the taller of the two is MARK_HEIGHT pixels,
# centred on MARK_CENTRE.
MARK_HEIGHT = 208.0
MARK_CENTRE = (196.0, 250.0)

# The text column, to the right of the mark.
TEXT_LEFT = 356
TEXT_RIGHT = WIDTH - MARGIN
WORDMARK = "MuPlay"
WORDMARK_SIZE = 128
WORDMARK_TOP = 128
TAGLINE = ["Music and audiobooks", "from your own Navidrome server"]
TAGLINE_SIZE = 40
TAGLINE_TOP = 292
TAGLINE_LEADING = 52
# MuPlay's own `primaryContainer` against this field: readable on #005048 without being the same
# pure white as the wordmark, so the two lines read as a hierarchy rather than one block. It was
# #E8DEF8 -- Material's *baseline* purple container -- which is the tone that belongs on the
# template field this app no longer uses.
TAGLINE_COLOUR = "#7FF8E4"
WORDMARK_COLOUR = "#FFFFFF"

# Absolute floor below which a shrunk string stops being a store asset and starts being a defect.
MIN_FONT_SIZE = 24

# The icon script supersamples 8x, which is right for a 108dp viewport and wrong here: this canvas
# is rasterised as a 1024-wide square before cropping, so 8x means a 8192x8192 RGBA buffer -- 268 MB
# held at once. Agents on this host run inside memory-limited transient scopes and a python3 has
# already been OOM-killed at ~523 MB resident with tens of gigabytes free on the machine. At 4x the
# buffer is 67 MB, and for flat fills, circles and rounded polygons at this output size the two are
# visually indistinguishable. Raise it only if you can afford the square of what you raise it to.
FEATURE_SUPERSAMPLE = 4

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
]


def find_font():
    for path in FONT_CANDIDATES:
        if os.path.isfile(path):
            return path
    raise SystemExit(
        "no bold sans font found. Tried:\n  " + "\n  ".join(FONT_CANDIDATES) + "\n"
        "Install one (Debian/Ubuntu: `apt install fonts-dejavu-core`) or add its path to "
        "FONT_CANDIDATES. Refusing to fall back to Pillow's bitmap default: it would render a "
        "1024x500 PNG with an illegible wordmark and report success."
    )


def fit_font(font_path, text, size, max_width, draw):
    """The largest size <= `size` at which `text` fits `max_width`, or a hard failure.

    Returns the font, so a caller never has to ask twice how big the text ended up.
    """
    for candidate in range(size, MIN_FONT_SIZE - 1, -1):
        font = ImageFont.truetype(font_path, candidate)
        if draw.textlength(text, font=font) <= max_width:
            return font
    raise SystemExit(
        f"{text!r} does not fit {max_width}px even at {MIN_FONT_SIZE}px. Shorten it, widen the "
        f"column, or lower MIN_FONT_SIZE deliberately -- but a store asset with text this small "
        f"is not readable in Play's own listing."
    )


def mark_shapes():
    """The icon's foreground shapes, translated and scaled into feature-graphic coordinates.

    Returns shapes in the same dict shape `generate-launcher-icon.render` consumes, so the
    rasteriser is shared rather than reimplemented -- including the rounded-polygon construction,
    which is the part that would be worth getting wrong twice.
    """
    xs, ys = [], []
    for shape in ICON.GEOMETRY["foreground"]:
        if shape["kind"] == "rect":
            x0, y0, x1, y1 = shape["bounds"]
            xs += [x0, x1]
            ys += [y0, y1]
        elif shape["kind"] == "polygon":
            xs += [p[0] for p in shape["points"]]
            ys += [p[1] for p in shape["points"]]
        else:
            raise SystemExit(f"unsupported foreground shape {shape['kind']!r}")

    left, right, top, bottom = min(xs), max(xs), min(ys), max(ys)
    scale = MARK_HEIGHT / (bottom - top)
    cx = (left + right) / 2.0
    cy = (top + bottom) / 2.0

    def place(point):
        return (
            MARK_CENTRE[0] + (point[0] - cx) * scale,
            MARK_CENTRE[1] + (point[1] - cy) * scale,
        )

    placed = []
    for shape in ICON.GEOMETRY["foreground"]:
        if shape["kind"] == "rect":
            x0, y0, x1, y1 = shape["bounds"]
            p0, p1 = place((x0, y0)), place((x1, y1))
            placed.append({
                "kind": "rect",
                "bounds": (p0[0], p0[1], p1[0], p1[1]),
                "radius": shape["radius"] * scale,
                "colour": shape["colour"],
            })
        else:
            placed.append({
                "kind": "polygon",
                "points": [place(p) for p in shape["points"]],
                "radius": shape["radius"] * scale,
                "colour": shape["colour"],
            })
    return placed, (left, right, top, bottom, scale)


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    if not os.path.isfile(os.path.join(root, "settings.gradle.kts")):
        raise SystemExit(f"{root} is not the repository root")

    font_path = find_font()

    background = [
        {"kind": "rect", "bounds": (0.0, 0.0, float(WIDTH), float(HEIGHT)), "radius": 0.0,
         "colour": FIELD_COLOUR},
        {"kind": "circle", "centre": DISC_CENTRE, "radius": DISC_RADIUS, "colour": DISC_COLOUR},
    ]
    shapes, mark_box = mark_shapes()

    # `ICON.render` works in a square viewport, which this canvas is not. Rasterise the wide canvas
    # by rendering into a WIDTH x WIDTH square (the viewport is the canvas width in "dp") and
    # cropping the top HEIGHT rows -- every coordinate above is already in final pixels, so the
    # square's own top-left is the canvas's.
    ICON.SUPERSAMPLE = FEATURE_SUPERSAMPLE
    square = ICON.render(background + shapes, WIDTH, float(WIDTH), (0.0, 0.0))
    image = square.crop((0, 0, WIDTH, HEIGHT)).convert("RGB")

    draw = ImageDraw.Draw(image)
    column = TEXT_RIGHT - TEXT_LEFT

    wordmark_font = fit_font(font_path, WORDMARK, WORDMARK_SIZE, column, draw)
    draw.text((TEXT_LEFT, WORDMARK_TOP), WORDMARK, font=wordmark_font, fill=WORDMARK_COLOUR)

    tagline_font = min(
        (fit_font(font_path, line, TAGLINE_SIZE, column, draw) for line in TAGLINE),
        key=lambda font: font.size,
    )
    for index, line in enumerate(TAGLINE):
        draw.text((TEXT_LEFT, TAGLINE_TOP + index * TAGLINE_LEADING), line, font=tagline_font,
                  fill=TAGLINE_COLOUR)

    # The margin check, made rather than intended. Everything drawn as text is asserted to sit
    # inside the safe box; the mark is checked the same way.
    text_bottom = TAGLINE_TOP + (len(TAGLINE) - 1) * TAGLINE_LEADING + tagline_font.size
    widest = max(draw.textlength(line, font=tagline_font) for line in TAGLINE)
    widest = max(widest, draw.textlength(WORDMARK, font=wordmark_font))
    overflow = []
    if TEXT_LEFT < MARGIN:
        overflow.append(f"text column starts at {TEXT_LEFT}px, inside the {MARGIN}px margin")
    if TEXT_LEFT + widest > WIDTH - MARGIN:
        overflow.append(f"text reaches {TEXT_LEFT + widest:.0f}px of {WIDTH - MARGIN}px")
    if WORDMARK_TOP < MARGIN:
        overflow.append(f"wordmark top {WORDMARK_TOP}px is inside the {MARGIN}px margin")
    if text_bottom > HEIGHT - MARGIN:
        overflow.append(f"text reaches {text_bottom:.0f}px of {HEIGHT - MARGIN}px")
    left, right, top, bottom, scale = mark_box
    mark_left = MARK_CENTRE[0] - (right - left) * scale / 2.0
    mark_top = MARK_CENTRE[1] - (bottom - top) * scale / 2.0
    if mark_left < MARGIN or mark_top < MARGIN:
        overflow.append(f"mark starts at ({mark_left:.0f}, {mark_top:.0f})px")
    if overflow:
        raise SystemExit("feature graphic content leaves the safe area:\n  " + "\n  ".join(overflow))

    out_dir = os.path.join(root, "play")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "feature-graphic.png")
    image.save(out, "PNG", optimize=True)

    print(f"font        {font_path}")
    print(f"wordmark    {wordmark_font.size}px")
    print(f"tagline     {tagline_font.size}px")
    print(f"{os.path.getsize(out):>8}  {os.path.relpath(out, root)}  {image.width}x{image.height}")


if __name__ == "__main__":
    main()
