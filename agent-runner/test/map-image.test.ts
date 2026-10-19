import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { inflateSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import {
  assignCivColours, encodePng, hexDistance, hexToWorld, rasterizeMap, renderMapImage,
  type MapImageCity, type MapImageInput, type MapImageUnit,
} from "../src/map-image.js";
import type { ObservedTile } from "../src/map-types.js";

type SampleTile = Partial<ObservedTile> & { x: number; y: number };

/** Independent bitwise CRC-32 (no table) to cross-check the encoder. */
function referenceCrc(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

interface DecodedPng { width: number; height: number; colourType: number; chunkTypes: string[]; rgba: Uint8Array }

function decodePng(png: Buffer): DecodedPng {
  expect([...png.subarray(0, 8)]).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  let offset = 8;
  const chunkTypes: string[] = [];
  const idat: Buffer[] = [];
  let width = 0, height = 0, colourType = -1;
  while (offset < png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.toString("latin1", offset + 4, offset + 8);
    const data = png.subarray(offset + 8, offset + 8 + length);
    const crc = png.readUInt32BE(offset + 8 + length);
    expect(crc, `${type} CRC`).toBe(referenceCrc(png.subarray(offset + 4, offset + 8 + length)));
    chunkTypes.push(type);
    if (type === "IHDR") {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      expect([data[8], data[10], data[11], data[12]]).toEqual([8, 0, 0, 0]);
      colourType = data[9]!;
    } else if (type === "IDAT") idat.push(Buffer.from(data));
    offset += 12 + length;
  }
  expect(offset).toBe(png.length);
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * 4;
  expect(raw.length).toBe((stride + 1) * height);
  const rgba = new Uint8Array(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]!;
    expect(filter).toBeLessThanOrEqual(4);
    for (let i = 0; i < stride; i++) {
      const x = raw[y * (stride + 1) + 1 + i]!;
      const a = i >= 4 ? rgba[y * stride + i - 4]! : 0;
      const b = y > 0 ? rgba[(y - 1) * stride + i]! : 0;
      const c = i >= 4 && y > 0 ? rgba[(y - 1) * stride + i - 4]! : 0;
      let predictor = 0;
      if (filter === 1) predictor = a;
      else if (filter === 2) predictor = b;
      else if (filter === 3) predictor = (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        predictor = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      rgba[y * stride + i] = (x + predictor) & 0xff;
    }
  }
  return { width, height, colourType, chunkTypes, rgba };
}

function pixel(raster: { rgba: Uint8Array; width: number }, px: number, py: number): [number, number, number] {
  const i = (Math.floor(py) * raster.width + Math.floor(px)) * 4;
  return [raster.rgba[i]!, raster.rgba[i + 1]!, raster.rgba[i + 2]!];
}

function hexagon(radius: number, cx = 0, cy = 0): { x: number; y: number }[] {
  const out: { x: number; y: number }[] = [];
  for (let x = cx - radius; x <= cx + radius; x++) {
    for (let y = cy - radius; y <= cy + radius; y++) if (hexDistance(x, y, cx, cy) <= radius) out.push({ x, y });
  }
  return out;
}

/** Deterministic, realistic-looking two-civ map: coastline, river, roads, mixed visibility. */
function buildSample(): MapImageInput {
  const romeCities: MapImageCity[] = [
    { cityId: "rome-1", name: "Rome", ownerCivId: "Rome", x: 0, y: 0, population: 5 },
    { cityId: "rome-2", name: "Antium", ownerCivId: "Rome", x: -3, y: 0, population: 2 },
  ];
  const foreignCities: MapImageCity[] = [
    { cityId: "bab-1", name: "Babylon", ownerCivId: "Babylon", x: 6, y: 4, population: 6 },
    { cityId: "bab-2", name: "Ur", ownerCivId: "Babylon", x: 3, y: 6, population: 3 },
    { cityId: "gen-1", name: "Geneva", ownerCivId: "Geneva", x: -4, y: -6, population: 4 },
  ];
  const cities = [...romeCities, ...foreignCities];
  const units: MapImageUnit[] = [
    { unitId: 1, name: "Warrior", ownerCivId: "Rome", x: 0, y: 0, health: 100 },
    { unitId: 2, name: "Settler", ownerCivId: "Rome", x: 1, y: -2, health: 100 },
    { unitId: 3, name: "Warrior", ownerCivId: "Rome", x: 1, y: -2, health: 100 },
    { unitId: 4, name: "Worker", ownerCivId: "Rome", x: -1, y: 0, health: 100 },
    { unitId: 5, name: "Scout", ownerCivId: "Rome", x: 3, y: 3, health: 55 },
    { unitId: 6, name: "Archer", ownerCivId: "Rome", x: 2, y: 2, health: 100 },
    { unitId: 101, name: "Spearman", ownerCivId: "Babylon", x: 4, y: 4, health: 100 },
    { unitId: 102, name: "Bowman", ownerCivId: "Babylon", x: 4, y: 3, health: 70 },
    { unitId: 201, name: "Brute", ownerCivId: "Barbarians", x: -4, y: -2, health: 100 },
  ];
  const ownership = (x: number, y: number): string | null => {
    let best: { owner: string; d: number } | null = null;
    for (const c of cities) {
      const d = hexDistance(x, y, c.x, c.y);
      const limit = c.ownerCivId === "Geneva" ? 1 : 2;
      if (d <= limit && (!best || d < best.d)) best = { owner: c.ownerCivId, d };
    }
    return best?.owner ?? null;
  };
  const sight = [...romeCities, ...units.filter(u => u.ownerCivId === "Rome")];
  const tiles: SampleTile[] = [];
  for (const { x, y } of hexagon(10)) {
    const w = hexToWorld(x, y);
    const coastLine = 7 + 2.2 * Math.sin(w.y * 0.35) + 1.2 * Math.cos(w.y * 0.9);
    const water = w.x > coastLine;
    const noise = Math.sin(x * 1.3 + y * 0.7) + Math.cos(x * 0.4 - y * 1.1) + 0.5 * Math.sin((x + 2 * y) * 0.9);
    const isCity = cities.some(c => c.x === x && c.y === y);
    let terrain: string;
    const features: string[] = [];
    if (water) terrain = w.x > coastLine + 2.2 ? "Ocean" : "Coast";
    else if (x === 4 && y === -2) terrain = "Lakes";
    else if (!isCity && noise > 1.9) terrain = "Mountain";
    else {
      terrain = w.y > 11 ? "Snow" : w.y > 7 ? "Tundra" : w.y < -9 ? "Desert" : w.y < -4 ? "Plains" : noise > 0 ? "Grassland" : "Plains";
      if (!isCity && noise > 1.2) features.push("Hill");
      if (!isCity && noise < -0.6 && terrain !== "Desert") features.push(w.y > 5 ? "Forest" : noise < -1.3 ? "Jungle" : "Forest");
      if (terrain === "Desert" && noise > 0.6) features.push("Flood plains");
      if (x === 1 && y === 5) features.push("Marsh");
    }
    const seenNow = sight.some(s => hexDistance(x, y, s.x, s.y) <= (s === romeCities[0] ? 4 : 2));
    const farWest = w.x < -11;
    const city = cities.find(c => c.x === x && c.y === y);
    tiles.push({
      x, y,
      visibility: seenNow ? "VISIBLE" : farWest ? "REVEALED" : "REMEMBERED",
      detailsKnown: !farWest,
      terrain: farWest ? null : terrain,
      features: farWest ? null : features,
      land: !water, water,
      hill: features.includes("Hill"),
      naturalWonder: x === 5 && y === -1 ? "Mount Fuji" : null,
      ownerCivId: ownership(x, y),
      city: city ? { cityId: city.cityId, name: city.name, ownerCivId: city.ownerCivId, health: 200, population: city.population ?? 1 } : null,
      road: [[-1, 0], [-2, 0]].some(([rx, ry]) => rx === x && ry === y) ? "Road" : "None",
      riverEdges: x === -2 && y >= -5 && y <= 0 ? { bottomRight: true, bottom: true, bottomLeft: false } : null,
    });
  }
  // Only cities the actor can know about: Geneva sits in remembered land, which is fine.
  return { actorCivId: "Rome", tiles, units, cities, worldWrap: false };
}

describe("encodePng", () => {
  it("produces a valid PNG whose IDAT inflates back to the original pixels with correct CRCs", () => {
    const width = 7, height = 5;
    const rgba = new Uint8Array(width * height * 4);
    for (let i = 0; i < rgba.length; i++) rgba[i] = (i * 37 + (i >> 3) * 11) & 0xff;
    const png = encodePng(width, height, rgba);
    const decoded = decodePng(png);
    expect(decoded.chunkTypes).toEqual(["IHDR", "IDAT", "IEND"]);
    expect([decoded.width, decoded.height, decoded.colourType]).toEqual([7, 5, 6]);
    expect(decoded.rgba).toEqual(rgba);
  });

  it("rejects mismatched buffers", () => {
    expect(() => encodePng(2, 2, new Uint8Array(15))).toThrow(RangeError);
    expect(() => encodePng(0, 2, new Uint8Array(0))).toThrow(RangeError);
  });
});

describe("renderMapImage geometry", () => {
  const ringTerrains = ["Grassland", "Ocean", "Coast", "Lakes", "Plains", "Desert", "Snow"];
  const ring = [[0, 0], [1, 1], [0, 1], [-1, 0], [-1, -1], [0, -1], [1, 0]] as const;
  const ringInput = (): MapImageInput => ({
    actorCivId: "Rome", units: [], cities: [],
    tiles: ring.map(([x, y], i) => ({ x, y, visibility: "VISIBLE" as const, terrain: ringTerrains[i]! })),
  });

  it("draws (0,0) and its six Unciv neighbours as an adjacent, non-overlapping ring with north up", () => {
    const raster = rasterizeMap(ringInput());
    const R = raster.hexRadius;
    const centres = ring.map(([x, y]) => raster.tilePixel(x, y)!);
    const dist = (a: { px: number; py: number }, b: { px: number; py: number }) => Math.hypot(a.px - b.px, a.py - b.py);
    const [middle, ...around] = centres;
    for (const c of around) expect(dist(middle!, c)).toBeCloseTo(SQRT3 * R, 6);
    // Consecutive ring members (clock 12, 2, 4, 6, 8, 10) are adjacent to each other as well.
    for (let i = 0; i < 6; i++) expect(dist(around[i]!, around[(i + 1) % 6]!)).toBeCloseTo(SQRT3 * R, 6);
    for (let i = 0; i < centres.length; i++) {
      for (let j = i + 1; j < centres.length; j++) expect(dist(centres[i]!, centres[j]!)).toBeGreaterThan(SQRT3 * R - 1e-6);
    }
    // North up: (x+1,y+1) straight above, (x+1,y) up-left, (x,y+1) up-right.
    const [north, upRight, , , , upLeft] = around;
    expect(north!.px).toBeCloseTo(middle!.px, 6);
    expect(north!.py).toBeLessThan(middle!.py);
    expect(upLeft!.px).toBeLessThan(middle!.px);
    expect(upLeft!.py).toBeLessThan(middle!.py);
    expect(upRight!.px).toBeGreaterThan(middle!.px);
    expect(upRight!.py).toBeLessThan(middle!.py);
    // Each hex centre shows its own terrain and all seven differ.
    const colours = centres.map(c => pixel(raster, c.px, c.py).join(","));
    expect(new Set(colours).size).toBe(7);
    // No overlap: every pixel of tile i's colour lies inside hex i (closest centre, within R),
    // and the unlabelled ring hexes each cover one full hexagon's area (3√3/2·R²).
    const counts = colours.map(() => 0);
    for (let py = 0; py < raster.height; py++) {
      for (let px = 0; px < raster.width; px++) {
        const owner = colours.indexOf(pixel(raster, px, py).join(","));
        if (owner < 0) continue;
        counts[owner]!++;
        const p = { px: px + 0.5, py: py + 0.5 };
        const own = dist(p, centres[owner]!);
        expect(own).toBeLessThanOrEqual(R);
        for (const other of centres) expect(own).toBeLessThanOrEqual(dist(p, other) + 1e-9);
      }
    }
    const expected = (3 * SQRT3 / 2) * R * R;
    for (const area of counts.slice(1)) {
      expect(area).toBeGreaterThan(expected * 0.88);
      expect(area).toBeLessThan(expected * 1.01);
    }
  });

  it("draws river edges on the matching hex edges (bottomRight=4, bottom=6, bottomLeft=8 o'clock)", () => {
    const input = ringInput();
    input.tiles = input.tiles.map(t => ({ ...t, terrain: "Plains" }));
    input.tiles[0] = { ...input.tiles[0]!, riverEdges: { bottomRight: true, bottom: true, bottomLeft: false } };
    const raster = rasterizeMap(input);
    const centre = raster.tilePixel(0, 0)!;
    const isRiver = ([r, g, b]: number[]) => b > 200 && r < 80 && g < 160;
    const midpoint = (x: number, y: number) => {
      const other = raster.tilePixel(x, y)!;
      return pixel(raster, (centre.px + other.px) / 2, (centre.py + other.py) / 2);
    };
    expect(isRiver(midpoint(-1, 0))).toBe(true);   // 4 o'clock
    expect(isRiver(midpoint(-1, -1))).toBe(true);  // 6 o'clock
    expect(isRiver(midpoint(0, -1))).toBe(false);  // 8 o'clock: no river
    expect(isRiver(midpoint(1, 1))).toBe(false);   // 12 o'clock
    expect(isRiver(pixel(raster, centre.px, centre.py))).toBe(false);
  });
});

const SQRT3 = Math.sqrt(3);

describe("ownership colours", () => {
  it("keeps the actor colour fixed and other civ colours independent of input order", () => {
    const sample = buildSample();
    const a = rasterizeMap(sample);
    const b = rasterizeMap({
      ...sample,
      tiles: [...sample.tiles].reverse(),
      units: [...sample.units].reverse(),
      cities: [...sample.cities].reverse(),
    });
    expect(a.civColours.get("Rome")).toEqual({ name: "magenta", rgb: [255, 0, 220] });
    expect([...b.civColours.entries()].sort()).toEqual([...a.civColours.entries()].sort());
    expect(a.civColours.get("Babylon")!.name).not.toBe(a.civColours.get("Geneva")!.name);

    const first = assignCivColours("Rome", ["Zulu", "Arabia", "Rome", "Egypt"]);
    const second = assignCivColours("Rome", ["Egypt", "Rome", "Arabia", "Zulu"]);
    expect([...first.entries()]).toEqual([...second.entries()]);
    expect(assignCivColours("Arabia", ["Zulu", "Rome", "Arabia"]).get("Arabia")!.name).toBe("magenta");
  });

  it("uses knownCivIds so a civ's colour does not shift when the window shows fewer civs", () => {
    const known = ["Babylon", "Barbarians", "Egypt", "Geneva", "Rome"];
    const sample = buildSample();
    const full = rasterizeMap({ ...sample, knownCivIds: known });
    const window = rasterizeMap({ ...sample, knownCivIds: known, center: { x: -3, y: 0 }, radius: 1 });
    for (const civ of known) expect(window.civColours.get(civ)).toEqual(full.civColours.get(civ));
  });
});

describe("view window", () => {
  it("keeps only tiles within Unciv hex distance of the center", () => {
    const tiles: SampleTile[] = [];
    for (let x = -4; x <= 4; x++) for (let y = -4; y <= 4; y++) tiles.push({ x, y, visibility: "VISIBLE", terrain: "Plains" });
    const raster = rasterizeMap({ actorCivId: "Rome", tiles, units: [], cities: [], center: { x: 0, y: 0 }, radius: 2 });
    expect(raster.tileCount).toBe(19);
    expect(raster.tilePixel(2, 2)).not.toBeNull();   // same-sign offset: distance 2
    expect(raster.tilePixel(1, -1)).not.toBeNull();  // mixed-sign offset: distance 2
    expect(raster.tilePixel(2, -1)).toBeNull();      // mixed-sign offset: distance 3
    expect(raster.tilePixel(3, 3)).toBeNull();
  });

  it("unwraps a tile across the world-wrap seam next to the center", () => {
    const tiles: SampleTile[] = [
      { x: 4, y: -4, visibility: "VISIBLE", terrain: "Grassland" },  // longitude 8
      { x: -4, y: 5, visibility: "VISIBLE", terrain: "Desert" },     // longitude -9: across the seam
      { x: -2, y: 3, visibility: "VISIBLE", terrain: "Snow" },       // genuinely far: distance 7 even unwrapped
    ];
    const base: MapImageInput = { actorCivId: "Rome", tiles, units: [], cities: [], center: { x: 4, y: -4 }, radius: 3 };
    const flat = rasterizeMap(base);
    expect(flat.tileCount).toBe(1);

    const wrapped = rasterizeMap({ ...base, worldWrap: true, maxLongitude: 10 });
    expect(wrapped.tileCount).toBe(2);
    const across = wrapped.tilePixel(-4, 5)!;
    expect(across).toEqual(wrapped.hexToPixel(6, -5)); // nearest copy, hex distance 3 from (4,-4)
    const centre = wrapped.tilePixel(4, -4)!;
    const R = wrapped.hexRadius;
    expect(Math.hypot(across.px - centre.px, across.py - centre.py)).toBeLessThan(5 * R);
    // Labels use engine coordinates, and the legend says the seam was unwrapped.
    expect(wrapped.legend).toContain("seam unwrapped");

    // Without maxLongitude canonical positions are drawn (no unwrapping).
    expect(rasterizeMap({ ...base, worldWrap: true }).tileCount).toBe(1);
  });

  it("without a center, unwraps the whole map around the actor's first city", () => {
    const tiles: SampleTile[] = [
      { x: 4, y: -4, visibility: "VISIBLE", terrain: "Grassland", ownerCivId: "Rome" },
      { x: -4, y: 5, visibility: "VISIBLE", terrain: "Plains", ownerCivId: "Rome" },
    ];
    const cities: MapImageCity[] = [{ cityId: "r1", name: "Rome", ownerCivId: "Rome", x: 4, y: -4 }];
    const raster = rasterizeMap({ actorCivId: "Rome", tiles, units: [], cities, worldWrap: true, maxLongitude: 10 });
    expect(raster.tilePixel(-4, 5)).toEqual(raster.hexToPixel(6, -5));
    const flat = rasterizeMap({ actorCivId: "Rome", tiles, units: [], cities, worldWrap: false });
    expect(flat.tilePixel(-4, 5)).toEqual(flat.hexToPixel(-4, 5));
  });
});

describe("rendering contract", () => {
  it("is deterministic and independent of input order", () => {
    const sample = buildSample();
    const a = renderMapImage(sample);
    const b = renderMapImage(buildSample());
    expect(a.png.equals(b.png)).toBe(true);
    const shuffled = renderMapImage({ ...sample, tiles: [...sample.tiles].reverse(), units: [...sample.units].reverse() });
    expect(shuffled.png.equals(a.png)).toBe(true);
    expect(a.legend).toBe(b.legend);
  });

  it("renders the sample with a compact legend and a decodable PNG", () => {
    const sample = buildSample();
    const result = renderMapImage(sample);
    const decoded = decodePng(result.png);
    expect([decoded.width, decoded.height]).toEqual([result.width, result.height]);
    expect(Math.max(result.width, result.height)).toBeLessThanOrEqual(1024);
    expect(result.tileCount).toBe(sample.tiles.length);
    expect(result.legend.length).toBeLessThan(800);
    expect(result.legend).toContain("Rome (you)=magenta");
    expect(result.legend).toMatch(/Babylon=\w+/);
    expect(result.legend).toContain(`${sample.tiles.length} tiles`);
    expect(result.legend).toContain("9 units, 5 cities");
    expect(result.png.length).toBeLessThan(3 * 1024 * 1024);

    const out = process.env.MAP_IMAGE_SAMPLE_OUT;
    if (out) {
      mkdirSync(dirname(out), { recursive: true });
      writeFileSync(out, result.png);
      writeFileSync(out.replace(/\.png$/, ".legend.txt"), `${result.legend}\n`);
      console.log(`sample ${result.width}x${result.height} ${result.png.length} bytes -> ${out}\n${result.legend}`);
    }
  });

  it("renders ~2,000 tiles at 1024px well under 500 ms", () => {
    const tiles: SampleTile[] = [];
    const civs = ["Rome", "Babylon", "Egypt", "Greece", "Persia"];
    for (const { x, y } of hexagon(25)) {
      const n = Math.sin(x * 0.9) + Math.cos(y * 0.7);
      tiles.push({
        x, y, visibility: (x + y) % 3 === 0 ? "VISIBLE" : "REMEMBERED",
        terrain: n > 1.2 ? "Mountain" : n > 0 ? "Grassland" : n > -1 ? "Plains" : "Ocean",
        features: n > 0.5 && n < 0.9 ? ["Forest"] : [],
        ownerCivId: Math.abs(x) < 20 && Math.abs(y) < 20 ? civs[(Math.floor(x / 6) + Math.floor(y / 6) + 50) % 5]! : null,
        riverEdges: x % 5 === 0 ? { bottomRight: true, bottom: true, bottomLeft: false } : null,
        road: y % 7 === 0 ? "Road" : null,
      });
    }
    expect(tiles.length).toBeGreaterThan(1900);
    const units: MapImageUnit[] = tiles.filter((_, i) => i % 17 === 0)
      .map((t, i) => ({ unitId: i, name: i % 3 ? "Warrior" : "Worker", ownerCivId: civs[i % 5]!, x: t.x, y: t.y, health: i % 4 ? 100 : 50 }));
    const cities: MapImageCity[] = tiles.filter((_, i) => i % 61 === 0)
      .map((t, i) => ({ cityId: `c${i}`, name: `City ${i}`, ownerCivId: civs[i % 5]!, x: t.x, y: t.y }));
    const input: MapImageInput = { actorCivId: "Rome", tiles, units, cities, maxPixels: 1024 };
    renderMapImage(input); // warm-up (JIT)
    const started = performance.now();
    const result = renderMapImage(input);
    const elapsed = performance.now() - started;
    console.log(`perf: ${tiles.length} tiles ${result.width}x${result.height} in ${elapsed.toFixed(1)} ms, ${result.png.length} bytes`);
    expect(Math.max(result.width, result.height)).toBeLessThanOrEqual(1024);
    expect(elapsed).toBeLessThan(500);
    expect(result.png.length).toBeLessThan(3 * 1024 * 1024);
  });

  it("tolerates tiles, units and cities with missing or null optional fields", () => {
    const tiles = [
      { x: 0, y: 0 },
      { x: 1, y: 0, terrain: null, features: null, riverEdges: null, city: null, ownerCivId: null, visibility: "REVEALED" as const },
      { x: 0, y: 1, visibility: "VISIBLE" as const, features: undefined, land: true },
      { x: 1, y: 1, visibility: "VISIBLE" as const, terrain: "Unheard-of Terrain", water: true },
      { x: -1, y: 0, city: { cityId: "c1", name: "Ville-Émeraude", ownerCivId: "France", health: 100, population: 1 } },
      { x: Number.NaN, y: 2 } as { x: number; y: number },
    ];
    const units = [{ unitId: 1, name: "Warrior", ownerCivId: "Rome", x: 0, y: 0 }, { unitId: 2, name: "", ownerCivId: "France", x: 9, y: 9, health: null }];
    const result = renderMapImage({ actorCivId: "Rome", tiles, units, cities: [] });
    expect(result.tileCount).toBe(5);
    expect(result.legend).toContain("France=");
    expect(result.legend).toContain("1 city");
    decodePng(result.png);

    // Callers holding the spec's array-level intersection type or plain ObservedTile[] still type-check.
    const specShaped: Partial<ObservedTile>[] & { x: number; y: number }[] = [];
    const observed: ObservedTile[] = [];
    renderMapImage({ actorCivId: "Rome", tiles: specShaped, units: [], cities: [] });
    renderMapImage({ actorCivId: "Rome", tiles: observed, units: [], cities: [] });

    const empty = renderMapImage({ actorCivId: "Rome", tiles: [], units: [], cities: [] });
    expect(empty.tileCount).toBe(0);
    expect(empty.legend).toContain("No known tiles");
    decodePng(empty.png);
  });
});
