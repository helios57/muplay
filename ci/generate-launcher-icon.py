#!/usr/bin/env python3
"""Generates every MuPlay launcher-icon asset from the one geometry table in this file.

Run from the repository root:

    python3 ci/generate-launcher-icon.py

Outputs, all overwritten in place:

  app/src/main/res/drawable/ic_launcher_background.xml   adaptive background layer (vector)
  app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive foreground layer (vector)
  app/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon layer (vector)
  app/src/main/res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher.png        legacy square buckets
  app/src/main/res/mipmap-{m,h,xh,xxh,xxx}dpi/ic_launcher_round.png  legacy round buckets
  app/src/main/ic_launcher-playstore.png                 512x512 store icon

Why a generator rather than checked-in hand-cut art: the vector drawables and the PNGs have to
be the *same* mark, and the only way to keep them that way across an edit is for both to be
derived from one description. `GEOMETRY` below is that description; nothing else in this file
contains a coordinate. Editing the mark means editing `GEOMETRY` and re-running this script.

Rasterisation is done with Pillow rather than a real SVG renderer because no SVG rasteriser is
installed on this host and adding one is a dependency this project does not want. That is only
sound because every shape in `GEOMETRY` is a primitive Pillow can render exactly: rounded
rectangles, circles, and rounded convex polygons (drawn as an inset polygon plus a round-joined
stroke of width 2r, which is the definition of a rounded polygon). The vector-drawable emitter
turns the *same* primitives into path data with `A` arc commands. Neither path is a re-drawing
of the other by hand.
"""

import math
import os
import sys

from PIL import Image, ImageDraw

# --------------------------------------------------------------------------------------------
# The one geometry table. All coordinates are in the adaptive-icon 108x108dp viewport, whose
# central 66dp-diameter circle is the safe zone every launcher mask is guaranteed to show.
# --------------------------------------------------------------------------------------------

SAFE_CENTRE = (54.0, 54.0)
SAFE_RADIUS = 33.0

# MuPlay's mark: a book spine, and a play triangle. Music and audiobooks, pressed play.
GEOMETRY = {
    # Adaptive background: the brand field, with one lighter disc offset up-and-left so the icon
    # has some depth under a circular mask without carrying any detail that a 48dp render loses.
    "background": [
        {"kind": "rect", "bounds": (0.0, 0.0, 108.0, 108.0), "radius": 0.0, "colour": "#4F378B"},
        # Deliberately overflows the 108x108 viewport on three sides: a VectorDrawable
        # rasterises into its own bounds, so the overflow is clipped by construction and needs no
        # <clip-path>. What is left inside the viewport is one soft diagonal edge, bottom-right.
        {"kind": "circle", "centre": (30.0, 26.0), "radius": 74.0, "colour": "#6750A4"},
    ],
    # Adaptive foreground, and (recoloured) the monochrome layer.
    "foreground": [
        {"kind": "rect", "bounds": (30.0, 32.0, 39.0, 76.0), "radius": 4.5, "colour": "#FFFFFF"},
        {
            "kind": "polygon",
            "points": [(46.0, 30.0), (80.0, 54.0), (46.0, 78.0)],
            "radius": 6.0,
            "colour": "#FFFFFF",
        },
    ],
}

# Density buckets, as multiples of the 48dp mdpi baseline.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
BASE_DP = 48

# A legacy PNG shows the central 72x72dp of the 108x108dp adaptive viewport -- the same crop the
# platform applies to an adaptive icon before masking.
LEGACY_VIEWPORT = 72.0

SUPERSAMPLE = 8


# --------------------------------------------------------------------------------------------
# Geometry helpers, shared by both emitters.
# --------------------------------------------------------------------------------------------

def check_safe_zone():
    """Fails loudly if any foreground vertex leaves the mask-safe circle.

    A launcher mask can crop anything outside it, so an icon that violates this looks fine on the
    emulator's mask and clipped on somebody else's. Checked here rather than trusted, because the
    coordinates above were chosen by hand.
    """
    cx, cy = SAFE_CENTRE
    for shape in GEOMETRY["foreground"]:
        if shape["kind"] == "rect":
            x0, y0, x1, y1 = shape["bounds"]
            points = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
        elif shape["kind"] == "polygon":
            points = shape["points"]
        else:
            points = [shape["centre"]]
        for (x, y) in points:
            distance = math.hypot(x - cx, y - cy)
            if distance > SAFE_RADIUS + 1e-9:
                raise SystemExit(
                    f"foreground vertex ({x}, {y}) is {distance:.2f}dp from the centre, outside "
                    f"the {SAFE_RADIUS}dp adaptive-icon safe radius"
                )


def inset_polygon(points, r):
    """`points` moved inward by `r` along each vertex's angle bisector.

    Filling this and stroking it with a round-joined pen of width 2r reproduces the original
    polygon with every corner rounded to radius r -- the rasteriser's half of the rounding.
    """
    out = []
    n = len(points)
    for i in range(n):
        prev = points[(i - 1) % n]
        here = points[i]
        nxt = points[(i + 1) % n]
        u = _unit(here, prev)
        v = _unit(here, nxt)
        bisector = _unit((0.0, 0.0), (u[0] + v[0], u[1] + v[1]))
        half = _angle_between(u, v) / 2.0
        out.append((here[0] + bisector[0] * r / math.sin(half),
                    here[1] + bisector[1] * r / math.sin(half)))
    return out


def _unit(origin, target):
    dx, dy = target[0] - origin[0], target[1] - origin[1]
    length = math.hypot(dx, dy)
    return (dx / length, dy / length)


def _angle_between(u, v):
    dot = max(-1.0, min(1.0, u[0] * v[0] + u[1] * v[1]))
    return math.acos(dot)


def rounded_polygon_path(points, r):
    """SVG/VectorDrawable path data for `points` with every corner rounded to radius r.

    Each corner contributes the tangent point on the incoming edge, then an `A` arc of radius r to
    the tangent point on the outgoing edge. The tangent distance from the vertex is r/tan(theta/2).
    """
    n = len(points)
    segments = []
    for i in range(n):
        prev = points[(i - 1) % n]
        here = points[i]
        nxt = points[(i + 1) % n]
        u = _unit(here, prev)
        v = _unit(here, nxt)
        tangent = r / math.tan(_angle_between(u, v) / 2.0)
        start = (here[0] + u[0] * tangent, here[1] + u[1] * tangent)
        end = (here[0] + v[0] * tangent, here[1] + v[1] * tangent)
        # Cross product of the incoming and outgoing edge directions gives the turn direction,
        # which is the arc's sweep flag.
        cross = (-u[0]) * v[1] - (-u[1]) * v[0]
        sweep = 1 if cross > 0 else 0
        segments.append((start, end, sweep))

    parts = [f"M{_n(segments[0][0][0])},{_n(segments[0][0][1])}"]
    for i, (start, end, sweep) in enumerate(segments):
        if i > 0:
            parts.append(f"L{_n(start[0])},{_n(start[1])}")
        parts.append(f"A{_n(r)},{_n(r)} 0 0 {sweep} {_n(end[0])},{_n(end[1])}")
    parts.append("Z")
    return "".join(parts)


def rounded_rect_path(bounds, r):
    x0, y0, x1, y1 = bounds
    if r <= 0:
        return f"M{_n(x0)},{_n(y0)}H{_n(x1)}V{_n(y1)}H{_n(x0)}Z"
    return (
        f"M{_n(x0 + r)},{_n(y0)}"
        f"H{_n(x1 - r)}A{_n(r)},{_n(r)} 0 0 1 {_n(x1)},{_n(y0 + r)}"
        f"V{_n(y1 - r)}A{_n(r)},{_n(r)} 0 0 1 {_n(x1 - r)},{_n(y1)}"
        f"H{_n(x0 + r)}A{_n(r)},{_n(r)} 0 0 1 {_n(x0)},{_n(y1 - r)}"
        f"V{_n(y0 + r)}A{_n(r)},{_n(r)} 0 0 1 {_n(x0 + r)},{_n(y0)}Z"
    )


def circle_path(centre, r):
    cx, cy = centre
    return (
        f"M{_n(cx - r)},{_n(cy)}"
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(cx + r)},{_n(cy)}"
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(cx - r)},{_n(cy)}Z"
    )


def _n(value):
    """A number formatted for path data: no trailing zeros, no scientific notation."""
    return f"{value:.3f}".rstrip("0").rstrip(".")


def shape_path(shape):
    if shape["kind"] == "rect":
        return rounded_rect_path(shape["bounds"], shape["radius"])
    if shape["kind"] == "circle":
        return circle_path(shape["centre"], shape["radius"])
    if shape["kind"] == "polygon":
        return rounded_polygon_path(shape["points"], shape["radius"])
    raise SystemExit(f"unknown shape kind {shape['kind']!r}")


# --------------------------------------------------------------------------------------------
# Emitters.
# --------------------------------------------------------------------------------------------

HEADER = (
    "<!-- GENERATED by ci/generate-launcher-icon.py from its GEOMETRY table. Do not hand-edit:\n"
    "     the PNG mipmaps and the store icon are generated from the same table and would drift. -->\n"
)


def vector_drawable(shapes, override_colour=None):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        HEADER.rstrip("\n"),
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for shape in shapes:
        colour = override_colour or shape["colour"]
        lines.append(f'  <path\n      android:fillColor="{colour}"\n'
                     f'      android:pathData="{shape_path(shape)}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def render(shapes, size_px, viewport, offset, mask=None):
    """Rasterises `shapes` into an RGBA image of `size_px`.

    `viewport` is the width in dp mapped onto `size_px`; `offset` is the dp coordinate of the
    image's top-left corner. Drawn at SUPERSAMPLE times the target size and downsampled, which is
    the whole of the antialiasing.
    """
    big = size_px * SUPERSAMPLE
    scale = big / viewport
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def to_px(point):
        return ((point[0] - offset[0]) * scale, (point[1] - offset[1]) * scale)

    for shape in shapes:
        colour = shape["colour"]
        if shape["kind"] == "rect":
            x0, y0, x1, y1 = shape["bounds"]
            p0, p1 = to_px((x0, y0)), to_px((x1, y1))
            r = shape["radius"] * scale
            if r > 0:
                draw.rounded_rectangle([p0, p1], radius=r, fill=colour)
            else:
                draw.rectangle([p0, p1], fill=colour)
        elif shape["kind"] == "circle":
            cx, cy = to_px(shape["centre"])
            r = shape["radius"] * scale
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour)
        elif shape["kind"] == "polygon":
            r = shape["radius"] * scale
            inset = [to_px(p) for p in inset_polygon(shape["points"], shape["radius"])]
            draw.polygon(inset, fill=colour)
            # Closing the ring with two extra points is what makes PIL emit a round joint at the
            # start vertex too; without them that one corner comes out square.
            draw.line(inset + [inset[0], inset[1]], fill=colour, width=int(round(2 * r)), joint="curve")

    if mask is not None:
        stencil = Image.new("L", (big, big), 0)
        stencil_draw = ImageDraw.Draw(stencil)
        if mask == "circle":
            stencil_draw.ellipse([0, 0, big - 1, big - 1], fill=255)
        elif mask == "squircle":
            stencil_draw.rounded_rectangle([0, 0, big - 1, big - 1], radius=big * 0.20, fill=255)
        else:
            raise SystemExit(f"unknown mask {mask!r}")
        image.putalpha(stencil)

    return image.resize((size_px, size_px), Image.LANCZOS)


def legacy(size_px, mask):
    """Background + foreground, cropped to the central 72dp the platform shows, then masked."""
    inset = (108.0 - LEGACY_VIEWPORT) / 2.0
    return render(
        GEOMETRY["background"] + GEOMETRY["foreground"],
        size_px,
        LEGACY_VIEWPORT,
        (inset, inset),
        mask=mask,
    )


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
    if not os.path.isfile(os.path.join(root, "settings.gradle.kts")):
        raise SystemExit(f"{root} is not the repository root")
    check_safe_zone()

    res = os.path.join(root, "app", "src", "main", "res")
    drawable = os.path.join(res, "drawable")
    os.makedirs(drawable, exist_ok=True)

    written = []

    def write_text(path, text):
        with open(path, "w") as handle:
            handle.write(text)
        written.append(path)

    write_text(os.path.join(drawable, "ic_launcher_background.xml"),
               vector_drawable(GEOMETRY["background"]))
    write_text(os.path.join(drawable, "ic_launcher_foreground.xml"),
               vector_drawable(GEOMETRY["foreground"]))
    # The monochrome layer is a silhouette the system tints, so it carries no colour of its own.
    write_text(os.path.join(drawable, "ic_launcher_monochrome.xml"),
               vector_drawable(GEOMETRY["foreground"], override_colour="#FFFFFF"))

    for bucket, factor in DENSITIES.items():
        size = int(round(BASE_DP * factor))
        folder = os.path.join(res, f"mipmap-{bucket}")
        os.makedirs(folder, exist_ok=True)
        for name, mask in (("ic_launcher", "squircle"), ("ic_launcher_round", "circle")):
            path = os.path.join(folder, f"{name}.png")
            legacy(size, mask).save(path, "PNG", optimize=True)
            written.append(path)

    # Play's store icon: 512x512, full-bleed square, no mask -- Play applies its own rounding, and
    # a pre-rounded upload gets rounded twice.
    store = os.path.join(root, "app", "src", "main", "ic_launcher-playstore.png")
    inset = (108.0 - LEGACY_VIEWPORT) / 2.0
    render(GEOMETRY["background"] + GEOMETRY["foreground"], 512, LEGACY_VIEWPORT,
           (inset, inset)).convert("RGB").save(store, "PNG", optimize=True)
    written.append(store)

    total = 0
    for path in written:
        size = os.path.getsize(path)
        total += size
        print(f"{size:>8}  {os.path.relpath(path, root)}")
    print(f"{total:>8}  total")


if __name__ == "__main__":
    main()
