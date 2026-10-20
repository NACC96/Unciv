#!/usr/bin/env python3
"""Generate the modern Atlas surface nine-patches.

The art is white (black for the shadow) so GameUiStyle tints it at runtime, and it is
authored at 2x: GameUiStyle draws it at half scale, so 24 px here is a 12-unit radius.
Each shape is 4x4 supersampled for antialiasing. Every file has exactly one stretch run
per axis and no padding markers, so callers set their own insets.

Run from the repository root, then repack the skin:
    /opt/homebrew/bin/python3 -I scripts/atlas/gen_ui_ninepatches.py
    scripts/atlas/check.sh --gradle packAtlasSkin
"""
import math
import os
import sys

from PIL import Image

SUPERSAMPLE = 4
STRETCH = 2  # Central stretch run, in pixels.

SURFACE_RADIUS = 24  # 12 units, GameUiStyle.Radius.panel
CONTROL_RADIUS = 16  # 8 units, GameUiStyle.Radius.control
PILL_HEIGHT = 56  # 28 units, fully rounded
RIM_WIDTH = 2  # 1-unit hairline

SHADOW_SIGMA = 10.0  # About a 10-unit CSS blur
SHADOW_OFFSET = 6  # 3 units downward, baked in so the halo is drawn centred on the panel
SHADOW_MARGIN = 36  # 18 units drawn outside the panel on every side; holds the whole falloff


def rounded_rect_distance(px, py, width, height, radius):
    """Signed distance from (px, py) to a rounded rectangle at the origin; negative inside."""
    qx = abs(px - width / 2) - (width / 2 - radius)
    qy = abs(py - height / 2) - (height / 2 - radius)
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - radius


def coverage(width, height, inside):
    """Per-pixel coverage (0..1, row-major) of a shape given an inside(x, y) test."""
    samples = [(index + 0.5) / SUPERSAMPLE for index in range(SUPERSAMPLE)]
    total = SUPERSAMPLE * SUPERSAMPLE
    result = []
    for y in range(height):
        for x in range(width):
            hits = sum(1 for sy in samples for sx in samples if inside(x + sx, y + sy))
            result.append(hits / total)
    return result


def rounded_rect(width, height, radius, ring=None, left=0.0, top=0.0, box=None):
    """Coverage of a rounded rectangle, or of a ring of the given width inside its edge.
    The shape occupies (left, top, *box) within a width x height canvas."""
    shape_width, shape_height = box or (width, height)

    def inside(x, y):
        distance = rounded_rect_distance(x - left, y - top, shape_width, shape_height, radius)
        return distance <= 0 and (ring is None or distance > -ring)

    return coverage(width, height, inside)


def gaussian_blur(values, width, height, sigma):
    """Separable true Gaussian blur on float coverage, with transparent outside the canvas."""
    reach = int(math.ceil(sigma * 4))
    kernel = [math.exp(-(offset * offset) / (2 * sigma * sigma)) for offset in range(-reach, reach + 1)]
    norm = sum(kernel)
    kernel = [weight / norm for weight in kernel]

    def blur_line(line):
        size = len(line)
        out = [0.0] * size
        for index in range(size):
            accumulated = 0.0
            for tap, weight in enumerate(kernel):
                source = index + tap - reach
                if 0 <= source < size:
                    accumulated += line[source] * weight
            out[index] = accumulated
        return out

    rows = [blur_line(values[y * width:(y + 1) * width]) for y in range(height)]
    columns = [blur_line([rows[y][x] for y in range(height)]) for x in range(width)]
    return [columns[x][y] for y in range(height) for x in range(width)]


def nine_patch(values, width, height, stretch_x, stretch_y, rgb):
    """A .9.png image: a 1-pixel marker border around the tinted body.
    Fully transparent pixels keep the body colour so linear filtering never darkens edges."""
    image = Image.new("RGBA", (width + 2, height + 2), rgb + (0,))
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            pixels[x + 1, y + 1] = rgb + (int(round(values[y * width + x] * 255)),)
    for x in range(stretch_x, stretch_x + STRETCH):
        pixels[x + 1, 0] = (0, 0, 0, 255)
    for y in range(stretch_y, stretch_y + STRETCH):
        pixels[0, y + 1] = (0, 0, 0, 255)
    # Corner and padding border pixels stay fully transparent (no pad markers).
    for x in range(width + 2):
        if not stretch_x + 1 <= x < stretch_x + 1 + STRETCH:
            pixels[x, 0] = (0, 0, 0, 0)
        pixels[x, height + 1] = (0, 0, 0, 0)
    for y in range(height + 2):
        if not stretch_y + 1 <= y < stretch_y + 1 + STRETCH:
            pixels[0, y] = (0, 0, 0, 0)
        pixels[width + 1, y] = (0, 0, 0, 0)
    return image


def rounded_patch(radius, ring=None, height=None):
    """A rounded rectangle with a 2-pixel straight run between its corners on both axes."""
    width = 2 * radius + STRETCH
    height = height or width
    values = rounded_rect(width, height, radius, ring)
    return nine_patch(values, width, height, radius, (height - STRETCH) // 2, (255, 255, 255))


def shadow_patch(radius):
    """A soft halo for a panel of the given radius, with its interior knocked out.

    The blur is computed for a panel long enough that the edge profile in the stretch run is a
    true long-edge profile, then the corners and centre run are cut out into a nine-patch whose
    corners are margin + radius. Drawn expanded by the margin, its knocked-out footprint matches
    the panel exactly, so translucent fills stay translucent."""
    margin = SHADOW_MARGIN
    corner = margin + radius
    body = 2 * radius + 2 * int(math.ceil(SHADOW_SIGMA * 4)) + STRETCH
    size = body + 2 * margin
    shifted = rounded_rect(size, size, radius, left=margin, top=margin + SHADOW_OFFSET, box=(body, body))
    footprint = rounded_rect(size, size, radius, left=margin, top=margin, box=(body, body))
    blurred = gaussian_blur(shifted, size, size, SHADOW_SIGMA)
    halo = [value * (1.0 - cover) for value, cover in zip(blurred, footprint)]

    keep = list(range(corner)) + [size // 2 - 1, size // 2] + list(range(size - corner, size))
    patch = 2 * corner + STRETCH
    values = [halo[source_y * size + source_x] for source_y in keep for source_x in keep]
    return nine_patch(values, patch, patch, corner, corner, (0, 0, 0))


def main():
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    output = os.path.join(repo, "android", "Images.Skin", "Skins", "Atlas")
    if not os.path.isdir(output):
        sys.exit(f"Missing skin folder: {output}")
    pill_radius = PILL_HEIGHT // 2
    shapes = {
        "surface": rounded_patch(SURFACE_RADIUS),
        "surface-rim": rounded_patch(SURFACE_RADIUS, ring=RIM_WIDTH),
        "surface-shadow": shadow_patch(SURFACE_RADIUS),
        "control-r8": rounded_patch(CONTROL_RADIUS),
        "control-r8-rim": rounded_patch(CONTROL_RADIUS, ring=RIM_WIDTH),
        "pill": rounded_patch(pill_radius, height=PILL_HEIGHT),
        "pill-rim": rounded_patch(pill_radius, ring=RIM_WIDTH, height=PILL_HEIGHT),
    }
    for name, image in shapes.items():
        path = os.path.join(output, f"{name}.9.png")
        image.save(path, optimize=True)
        print(f"{os.path.relpath(path, repo)}  {image.width}x{image.height}")


if __name__ == "__main__":
    main()
