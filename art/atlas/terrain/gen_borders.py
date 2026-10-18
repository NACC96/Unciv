#!/usr/bin/env python3
"""Draw the eight AtlasTerrain border strips and patch them into the packed atlas page.

Each strip is pure geometry: a thin solid line on the shared hex edge plus a soft tint that
fades towards the tile centre. Pixels are white and only alpha carries the shape, because
TileLayerBorders tints Inner with the nation's outer colour and draws Outer (the line) on top
with its inner colour. Run with /opt/homebrew/bin/python3 -I; the output is deterministic.

Strip geometry follows the engine. Tiles sit 40*sqrt(3) world units apart (TileGroupMap
groupSize 50 * 0.8), so a grid edge is 40 long and 20*sqrt(3) from the tile centre.
setHexagonSize stretches the 128-pixel strip to the 81-unit hexagon image width and rotates it
about sqrt(40.5^2 - 20.25^2) above its bottom row. That puts the shared edge about 0.7 px above
the bottom row, spanning x 32.4..95.6, so two nations' lines sit side by side, not on top of
each other. The first word of a shape name is TileLayerBorders' left end (the clock - 2
neighbour), which is the PNG's right (+x) end; a single word applies to both ends.
"""
import hashlib
import json
import math
import re
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
ART = ROOT / 'art/atlas/terrain'
TARGET = ROOT / 'android/Images.AtlasTerrain/TileSets/AtlasTerrain/Borders'
ATLAS = ROOT / 'android/assets/AtlasTerrain.atlas'
KEY_PREFIX = 'TileSets/AtlasTerrain/Borders/'
SHAPES = ('Concave', 'Convex', 'ConcaveConvex', 'ConvexConcave')

WIDTH, HEIGHT, SUPERSAMPLE = 128, 24, 4
PX = WIDTH / 81  # pixels per world unit
CENTER_Y = math.sqrt(40.5 ** 2 - 20.25 ** 2) * PX
EDGE_Y = CENTER_Y - 20 * math.sqrt(3) * PX
EDGE_LEFT, EDGE_RIGHT = WIDTH / 2 - 20 * PX, WIDTH / 2 + 20 * PX
MITER = math.tan(math.radians(30))
LINE = 2.0 * PX  # solid line, 2 world units
FADE = 15.0  # tint depth in pixels beyond the line
TINT = 0.38  # tint alpha next to the line
# TexturePacker.settings: padding 8 with duplicatePadding copies 4 edge pixels outward.
DUPLICATE_PADDING = 4


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def shape_ends(shape):
    words = re.findall('Concave|Convex', shape)
    return words[0], words[-1]


def profile(height, side):
    """Alpha at a height (pixels) above the shared edge."""
    if side == 'Outer':
        return 1.0 if 0 <= height < LINE else 0.0
    if LINE <= height < LINE + FADE:
        return TINT * (1 - (height - LINE) / FADE) ** 2
    return 0.0


def inside_miters(x, height, right, left):
    # A concave end follows the hex side towards the same-owner neighbour's strip; a convex
    # end follows the corner bisector towards this tile's next strip.
    right_x = EDGE_RIGHT + MITER * height if right == 'Concave' else EDGE_RIGHT - MITER * height
    left_x = EDGE_LEFT - MITER * height if left == 'Concave' else EDGE_LEFT + MITER * height
    return left_x <= x <= right_x


def draw_strip(shape, side):
    right, left = shape_ends(shape)
    alpha = Image.new('L', (WIDTH, HEIGHT))
    pixels = alpha.load()
    samples = [(index + .5) / SUPERSAMPLE for index in range(SUPERSAMPLE)]
    for row in range(HEIGHT):
        for column in range(WIDTH):
            total = 0.0
            for dy in samples:
                height = HEIGHT - row - dy - EDGE_Y
                for dx in samples:
                    if inside_miters(column + dx, height, right, left):
                        total += profile(height, side)
            pixels[column, row] = round(255 * total / SUPERSAMPLE ** 2)
    image = Image.new('RGBA', (WIDTH, HEIGHT), (255, 255, 255, 0))
    image.putalpha(alpha)
    return image


def atlas_regions():
    regions, page, name = {}, None, None
    for line in ATLAS.read_text().splitlines():
        if line.endswith('.png'):
            page, name = line, None
        elif line and not line.startswith(' ') and ':' not in line:
            name = line
            regions[name] = {'page': page}
        elif name and line.startswith('  '):
            key, value = line.strip().split(': ', 1)
            regions[name][key] = [int(v) for v in value.split(', ')] if key in ('xy', 'size') else value
    return regions


def padded_box(region):
    (x, y), (width, height) = region['xy'], region['size']
    return x - DUPLICATE_PADDING, y - DUPLICATE_PADDING, x + width + DUPLICATE_PADDING, y + height + DUPLICATE_PADDING


def overlaps(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def with_duplicate_padding(image):
    # Same as TexturePacker duplicatePadding: edge pixels repeat outward, corners fill corners.
    padded = Image.new('RGBA', (WIDTH + 2 * DUPLICATE_PADDING, HEIGHT + 2 * DUPLICATE_PADDING))
    source, target = image.load(), padded.load()
    for x in range(padded.width):
        for y in range(padded.height):
            target[x, y] = source[min(max(x - DUPLICATE_PADDING, 0), WIDTH - 1),
                                  min(max(y - DUPLICATE_PADDING, 0), HEIGHT - 1)]
    return padded


def patch_atlas(strips):
    """Overwrite the strips' packed regions; returns the page files written."""
    # Region sizes are unchanged, so patching keeps every other region byte-identical in place.
    regions = atlas_regions()
    placements = {}
    for key, image in strips.items():
        region = regions[KEY_PREFIX + key]
        assert region['size'] == [WIDTH, HEIGHT] and region['rotate'] == 'false', key
        assert region['orig'] == f'{WIDTH}, {HEIGHT}' and region['offset'] == '0, 0', key
        box = padded_box(region)
        for other, entry in regions.items():
            assert other == KEY_PREFIX + key or entry['page'] != region['page'] or not overlaps(box, padded_box(entry)), other
        placements.setdefault(region['page'], []).append((box, image))
    written = []
    for page, placed in placements.items():
        path = ATLAS.parent / page
        sheet = Image.open(path).convert('RGBA')
        for box, image in placed:
            sheet.paste(with_duplicate_padding(image), box[:2])
        sheet.save(path, optimize=True)
        written.append(path)
    return written


def update_records(outputs, pages):
    generator = Path(__file__).resolve().relative_to(ROOT).as_posix()
    record = ART / 'borders-source.json'
    save_json(record, {
        'schemaVersion': 1, 'batchId': 'borders', 'tool': generator, 'toolSha256': digest(__file__),
        'geometry': {'canvas': [WIDTH, HEIGHT], 'supersample': SUPERSAMPLE, 'pixelsPerWorldUnit': round(PX, 4),
                     'edgeY': round(EDGE_Y, 4), 'edgeX': [round(EDGE_LEFT, 4), round(EDGE_RIGHT, 4)],
                     'lineHeight': round(LINE, 4), 'tintDepth': FADE, 'tintAlpha': TINT,
                     'miterSlope': 'tan(30deg)', 'nameOrder': 'first word = PNG right (+x) end'},
        'reviewState': 'candidate', 'formalAcceptance': 'deferred by user',
        'cells': [{'item': '@Borders/' + Path(path).stem, 'sourcePath': path, 'canvas': [WIDTH, HEIGHT],
                   'normalization': 'procedural', 'sha256': sha} for path, sha in outputs.items()]})
    provenance_path = ROOT / 'art/atlas/provenance.json'
    provenance = json.loads(provenance_path.read_text())
    entry = {'assetId': 'terrain-borders', 'creator': 'Procedural geometry (' + generator + ')',
             'source': record.relative_to(ROOT).as_posix(), 'promptPath': generator,
             'rights': 'original', 'sourceHash': digest(record), 'outputHashes': outputs}
    entries = provenance['entries']
    index = next((i for i, e in enumerate(entries) if e['assetId'] in ('terrain-borders', 'terrain-batch-borders')), len(entries))
    entries[index:index + 1] = [entry]
    save_json(provenance_path, provenance)
    coverage_path = ROOT / 'art/atlas/coverage.json'
    coverage = json.loads(coverage_path.read_text())
    for row in coverage['rows']:
        if row['intendedSource'] in outputs:
            row['reviewState'] = 'candidate'
            row['evidence'] = [{'kind': 'source', 'path': row['intendedSource'], 'sha256': outputs[row['intendedSource']],
                                'reviewer': 'production authoring', 'note': 'Procedural candidate from ' + generator + '; formal art acceptance deferred.'}]
    save_json(coverage_path, coverage)
    inventory_path = ART / 'packaging-inventory.json'
    inventory = json.loads(inventory_path.read_text())
    for page in inventory['pages']:
        path = ROOT / page['path']
        if path in pages:
            page['sha256'] = digest(path)
            page['compressedBytes'] = path.stat().st_size
    inventory['atlasSha256'] = digest(ATLAS)
    save_json(inventory_path, inventory)


def main():
    strips, outputs = {}, {}
    for shape in SHAPES:
        for side in ('Inner', 'Outer'):
            image = draw_strip(shape, side)
            path = TARGET / (shape + side + '.png')
            image.save(path, optimize=True)
            strips[shape + side] = image
            outputs[path.relative_to(ROOT).as_posix()] = digest(path)
    pages = patch_atlas(strips)
    update_records(outputs, pages)
    print(json.dumps({'strips': len(outputs), 'pages': sorted(p.name for p in pages)}, sort_keys=True))


if __name__ == '__main__':
    main()
