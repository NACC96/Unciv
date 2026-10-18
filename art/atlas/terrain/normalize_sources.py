#!/usr/bin/env python3
"""Cut/resize generated originals into engine source paths; never draws replacement artwork.

Alpha clipping, edge crops and anchoring are technical normalization. Raw generated sheets
stay on Lexar. Candidate source evidence does not constitute seam, contrast or art acceptance.
"""
import hashlib
import json
import math
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
ART = ROOT / 'art/atlas/terrain'
TARGET = ROOT / 'android/Images.AtlasTerrain/TileSets/AtlasTerrain'
CANVAS = (128, 112)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def hex_mask():
    mask = Image.new('L', CANVAS, 0)
    ImageDraw.Draw(mask).polygon([(0, 56), (32, 0), (96, 0), (127, 56), (96, 111), (32, 111)], fill=255)
    return mask


def normalize(crop, mode):
    if mode == 'border':
        # Stretching a generated ribbon to the full strip overshoots every hex corner.
        # Border strips are exact mitred engine geometry drawn by gen_borders.py.
        raise ValueError('Border strips are drawn by art/atlas/terrain/gen_borders.py, not cut from a sheet')
    # Preserve original pixels and alpha, cutting away only transparent cell gutters.
    bounds = crop.getchannel('A').point(lambda a: 255 if a > 12 else 0).getbbox()
    if bounds is None:
        raise ValueError('A generated cell contains no source pixels')
    crop = crop.crop(bounds)
    if mode == 'terrain' or mode == 'hex':
        image = crop.resize(CANVAS, Image.Resampling.LANCZOS)
        image.putalpha(ImageChops.multiply(image.getchannel('A'), hex_mask()))
        return image
    if mode == 'road':
        return crop.resize((96, 32), Image.Resampling.LANCZOS)
    if mode == 'river':
        # River material is positioned against the same regular-hex edge by derive_edges.
        return crop.resize(CANVAS, Image.Resampling.LANCZOS)
    tall = mode in ('natural-wonder', 'era-city')
    canvas = Image.new('RGBA', (128, 144) if tall else CANVAS)
    limit = (120, 136) if tall else ((84, 84) if mode == 'resource' else (120, 104))
    crop.thumbnail(limit, Image.Resampling.LANCZOS)
    canvas.alpha_composite(crop, ((128 - crop.width) // 2, canvas.height - crop.height - 4))
    return canvas


def source_path(item):
    return TARGET / (item[1:] + '.png' if item.startswith('@') else 'Tiles/' + item + '.png')


def process_batch(batch, provenance):
    raw = Path(batch['rawPath'])
    image = Image.open(raw).convert('RGBA')
    columns, rows = batch['columns'], batch['rows']
    outputs, cells = {}, []
    for index, item in enumerate(batch['items']):
        column, row = index % columns, index // columns
        box = (round(column * image.width / columns), round(row * image.height / rows),
               round((column + 1) * image.width / columns), round((row + 1) * image.height / rows))
        category = batch['category']
        if item in ('@Hexagon', '@UnexploredTile', '@RememberedFog', '@CrosshatchHexagon', '@Highlight'):
            category = 'hex'
        elif item in ('@Road', '@Railroad'):
            category = 'road'
        elif item.startswith('River-'):
            category = 'river'
        elif item.startswith('@Borders/'):
            category = 'border'
        elif item.startswith('@'):
            category = 'overlay'
        elif batch['id'] == 'resources-overlays' and index > 2:
            category = 'overlay'
        elif batch['id'] == 'improvements-2' and index >= 9:
            category = 'improvement'
        elif batch['id'] == 'damaged-cities' and item in ('Terrace farm-Pillaged', 'Trading post-Pillaged'):
            category = 'improvement'
        result = normalize(image.crop(box), category)
        path = source_path(item)
        path.parent.mkdir(parents=True, exist_ok=True)
        result.save(path, optimize=True)
        relative = path.relative_to(ROOT).as_posix()
        outputs[relative] = digest(path)
        cells.append({'item': item, 'cell': index, 'cropBox': box, 'sourcePath': relative,
                      'canvas': list(result.size), 'normalization': category, 'sha256': outputs[relative],
                      'clippingMargin': 16 if category in ('era-city', 'natural-wonder') else 0})
    record = ART / (batch['id'] + '-source.json')
    save_json(record, {'schemaVersion': 1, 'batchId': batch['id'], 'tool': 'built-in image_gen',
                      'rawPath': str(raw), 'rawSha256': digest(raw), 'rawDimensions': list(image.size),
                      'promptPath': batch['promptPath'], 'promptSha256': digest(ROOT / batch['promptPath']),
                      'reviewState': 'candidate', 'formalAcceptance': 'deferred by user', 'cells': cells})
    entry = {'assetId': 'terrain-batch-' + batch['id'], 'creator': 'OpenAI built-in image_gen',
             'source': record.relative_to(ROOT).as_posix(), 'promptPath': batch['promptPath'],
             'rights': 'original-generated', 'sourceHash': digest(record), 'outputHashes': outputs}
    provenance['entries'] = [e for e in provenance['entries'] if e['assetId'] != entry['assetId']] + [entry]


def edge_mask(direction, thickness=11):
    # Cutting masks only; the colored pixels all come from generated source material.
    vertices = [(32, 0), (96, 0), (127, 56), (96, 111), (32, 111), (0, 56)]
    pairs = {'Top': (0, 1), 'TopRight': (1, 2), 'BottomRight': (2, 3),
             'Bottom': (3, 4), 'BottomLeft': (4, 5), 'TopLeft': (5, 0)}
    start, end = [vertices[i] for i in pairs[direction]]
    dx, dy = end[0] - start[0], end[1] - start[1]
    length = math.hypot(dx, dy)
    mask = Image.new('L', CANVAS, 0)
    pixels = mask.load()
    inside = hex_mask().load()
    for y in range(112):
        for x in range(128):
            distance = abs(dy * (x - start[0]) - dx * (y - start[1])) / length
            along = ((x - start[0]) * dx + (y - start[1]) * dy) / length
            if inside[x, y] and 0 <= along <= length and distance < thickness:
                pixels[x, y] = round(144 * (1 - distance / thickness))
    return mask


def derive_edges(coverage, provenance):
    outputs = {}
    source_keys = set()
    masks = {direction: edge_mask(direction) for direction in
             ('Top', 'TopRight', 'BottomRight', 'Bottom', 'BottomLeft', 'TopLeft')}
    material_crops = {}
    for row in coverage['rows']:
        if row['category'] != 'neighbor-transition':
            continue
        destination = row['destinationFilter']
        name = {'Land': 'Grassland', 'Water': 'Ocean'}.get(destination, destination)
        source = TARGET / ('Tiles/' + name + '.png')
        if not source.exists():
            continue
        crop_key = (name, row['direction'])
        if crop_key not in material_crops:
            texture = Image.open(source).convert('RGBA').resize(CANVAS, Image.Resampling.LANCZOS)
            texture.putalpha(ImageChops.multiply(texture.getchannel('A'), masks[row['direction']]))
            material_crops[crop_key] = texture
        texture = material_crops[crop_key]
        target = ROOT / row['intendedSource']
        target.parent.mkdir(parents=True, exist_ok=True)
        texture.save(target, optimize=True)
        outputs[row['intendedSource']] = digest(target)
        source_keys.add(source.relative_to(ROOT).as_posix())
    # Re-anchor the three separately generated river strips onto their actual hex edges.
    # This is cropping/resizing/placement only, never painted or procedurally colored art.
    river_record = json.loads((ART / 'resources-overlays-source.json').read_text())
    river_raw = Image.open(river_record['rawPath']).convert('RGBA')
    for direction in ('Bottom', 'BottomLeft', 'BottomRight'):
        river = TARGET / ('Tiles/River-' + direction + '.png')
        if not river.exists():
            continue
        cell = next(c for c in river_record['cells'] if c['item'] == 'River-' + direction)
        material = river_raw.crop(cell['cropBox'])
        bounds = material.getchannel('A').point(lambda a: 255 if a > 12 else 0).getbbox()
        material = material.crop(bounds)
        size, location = {'Bottom': ((64, 8), (32, 104)),
                          'BottomLeft': ((36, 58), (0, 54)),
                          'BottomRight': ((36, 58), (92, 54))}[direction]
        material = material.resize(size, Image.Resampling.LANCZOS)
        canvas = Image.new('RGBA', CANVAS)
        canvas.alpha_composite(material, location)
        canvas.putalpha(ImageChops.multiply(canvas.getchannel('A'), hex_mask()))
        canvas.save(river, optimize=True)
        outputs[river.relative_to(ROOT).as_posix()] = digest(river)
        source_keys.add('art/atlas/terrain/resources-overlays-source.json')
        for entry in provenance['entries']:
            entry['outputHashes'].pop(river.relative_to(ROOT).as_posix(), None)
    record = ART / 'transition-source.json'
    save_json(record, {'schemaVersion': 1, 'tool': 'technical alpha clipping of generated originals',
                      'sourceHashes': {p: digest(ROOT / p) for p in sorted(source_keys)},
                      'directions': ['TopRight', 'BottomRight', 'Bottom', 'BottomLeft', 'TopLeft', 'Top'],
                      'count': len(outputs), 'reviewState': 'candidate', 'formalAcceptance': 'deferred by user',
                      'note': 'Directed lookup aliases share material crops; no hidden-map input, seams/zoom remain unreviewed.'})
    entry = {'assetId': 'terrain-derived-transitions', 'creator': 'Technical source normalization',
             'source': 'art/atlas/terrain/transition-source.json', 'promptPath': 'art/atlas/terrain/bases-prompt.txt',
             'rights': 'original-generated', 'sourceHash': digest(record), 'outputHashes': outputs}
    provenance['entries'] = [e for e in provenance['entries'] if e['assetId'] != entry['assetId']] + [entry]


def main():
    provenance = json.loads((ROOT / 'art/atlas/provenance.json').read_text())
    coverage = json.loads((ROOT / 'art/atlas/coverage.json').read_text())
    batches = json.loads((ART / 'batches.json').read_text())['batches']
    for batch in batches:
        process_batch(batch, provenance)
    derive_edges(coverage, provenance)
    save_json(ROOT / 'art/atlas/provenance.json', provenance)
    authored = {p for entry in provenance['entries'] for p in entry['outputHashes']}
    for row in coverage['rows']:
        if not row['intendedSource'].startswith('android/Images.AtlasTerrain/'):
            continue
        path = ROOT / row['intendedSource']
        if row['intendedSource'] in authored and path.exists():
            row['reviewState'] = 'candidate'
            row['evidence'] = [{'kind': 'source', 'path': row['intendedSource'], 'sha256': digest(path),
                                'reviewer': 'production authoring', 'note': 'Original generated candidate; formal art acceptance deferred.'}]
    coverage['status'] = 'Original Atlas map candidates authored; all acceptance and unit coverage remain separate and deferred'
    # Lookup-layer source changes are part of this production inventory's exact input set.
    coverage['sourceHashes'] = {path: digest(ROOT / path) for path in coverage['sourceHashes']}
    save_json(ROOT / 'art/atlas/coverage.json', coverage)
    counts = {}
    for row in coverage['rows']:
        if row['intendedSource'].startswith('android/Images.AtlasTerrain/'):
            category = row['category']
            counts.setdefault(category, {'candidate': 0, 'missingRequired': 0, 'accepted': 0})
            counts[category][row['reviewState']] = counts[category].get(row['reviewState'], 0) + 1
            if row['required'] and row['reviewState'] == 'missing':
                counts[category]['missingRequired'] += 1
    save_json(ART / 'production-inventory.json', {'schemaVersion': 1, 'status': 'candidate production inventory only',
                                                'categories': counts, 'sourceCount': len(authored),
                                                'artAcceptance': 'deferred', 'renderValidation': 'deferred'})
    print(json.dumps(counts, sort_keys=True))


if __name__ == '__main__':
    main()
