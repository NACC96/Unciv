/**
 * Civilization-scoped map image for LLM game agents.
 *
 * Renders ONLY what the caller passes in (tiles/units/cities that are already scoped to the
 * actor civ's knowledge) into a compact PNG plus a short text legend. Nothing is inferred or
 * invented: unknown tiles stay background, remembered tiles are drawn dimmed.
 *
 * Geometry mirrors core/src/com/unciv/logic/map/HexMath.kt:
 *   hex2WorldCoords(x, y) = clock10·√3·x + clock2·√3·y   (world y points up)
 * which gives world = (1.5·(y − x), (√3/2)·(x + y)) for unit circumradius. Hexes are flat-topped
 * (pointy left/right) like the game's tile images, so the six neighbours sit at clock positions
 * 12 (x+1,y+1), 2 (x,y+1), 4 (x−1,y), 6 (x−1,y−1), 8 (x,y−1) and 10 (x+1,y).
 *
 * Pure software rasterizer + PNG encoder; node built-ins only, fully deterministic.
 */
import { deflateSync } from "node:zlib";
import type { ObservedTile } from "./map-types.js";

export interface MapImageUnit { unitId: number; name: string; ownerCivId: string; x: number; y: number; health?: number | null }
export interface MapImageCity { cityId: string; name: string; ownerCivId: string; x: number; y: number; population?: number | null }
export interface MapImageInput {
  actorCivId: string;
  /**
   * Shape-tolerant: only x and y are required; null and absent optional fields are both fine.
   * (Element-level intersection; an array-level `Partial<ObservedTile>[] & {x,y}[]` breaks `.map`.)
   */
  tiles: (Partial<ObservedTile> & { x: number; y: number })[];
  /** Own + visible foreign units. */
  units: MapImageUnit[];
  /** Own + visible/known foreign cities; tile.city entries are merged in as well. */
  cities: MapImageCity[];
  /** Optional view window (Unciv hex distance). Default: every tile passed in. */
  center?: { x: number; y: number };
  radius?: number;
  worldWrap?: boolean;
  /** World-wrap radius in longitude (TileMap.maxLongitude); enables seam unwrapping when worldWrap. */
  maxLongitude?: number | null;
  /** Longest image edge cap in pixels (default 1024). Hex size is scaled to fit. */
  maxPixels?: number;
  /**
   * Optional: every civ id the actor knows. Palette slots are assigned over this set plus any civ
   * seen in the input, sorted by id, so colours stay stable across windows and turns.
   */
  knownCivIds?: string[];
}
export interface MapImageResult { png: Buffer; width: number; height: number; legend: string; tileCount: number }

/** Raw raster plus layout helpers (used by renderMapImage and by tests). */
export interface MapRaster {
  width: number;
  height: number;
  rgba: Uint8Array;
  /** Hex circumradius in pixels. */
  hexRadius: number;
  legend: string;
  tileCount: number;
  /** Civ id -> colour name/RGB as drawn (actor included). */
  civColours: Map<string, { name: string; rgb: RGB }>;
  /** Pixel centre where an engine (canonical) tile coordinate was drawn, or null if not drawn. */
  tilePixel(x: number, y: number): { px: number; py: number } | null;
  /** Pixel centre of an (already unwrapped) hex coordinate in this image's projection. */
  hexToPixel(x: number, y: number): { px: number; py: number };
}

export type RGB = readonly [number, number, number];

const SQRT3 = Math.sqrt(3);
const APOTHEM = SQRT3 / 2;
const DEFAULT_MAX_PIXELS = 1024;
const MAX_HEX_RADIUS = 28;
const MAX_PNG_BYTES = 3 * 1024 * 1024;
const MARGIN = 6;
const LEGEND_MAX = 800;

/** Neighbour offsets by edge index 0..5 = clock 12, 2, 4, 6, 8, 10 (HexMath.clockPositionToHexcoordMap). */
const NEIGHBOURS: readonly (readonly [number, number])[] = [[1, 1], [0, 1], [-1, 0], [-1, -1], [0, -1], [1, 0]];
const EDGE_4 = 2, EDGE_6 = 3, EDGE_8 = 4;

// ---------------------------------------------------------------------------------------------
// Hex math (Unciv conventions)
// ---------------------------------------------------------------------------------------------

/** HexMath.hex2WorldCoords with unit circumradius (neighbour centres are √3 apart); y points up. */
export function hexToWorld(x: number, y: number): { x: number; y: number } {
  return { x: 1.5 * (y - x), y: APOTHEM * (x + y) };
}

/** HexMath.getDistance. */
export function hexDistance(ax: number, ay: number, bx: number, by: number): number {
  const dx = ax - bx, dy = ay - by;
  return dx * dy >= 0 ? Math.max(Math.abs(dx), Math.abs(dy)) : Math.abs(dx) + Math.abs(dy);
}

/** HexMath.getUnwrappedNearestTo: the copy of (x, y) nearest the reference, wrapping longitude. */
export function unwrapNearest(x: number, y: number, refX: number, refY: number, longitudinalRadius: number): { x: number; y: number } {
  const period = longitudinalRadius * 2;
  const refLong = refX - refY;
  const lat = x + y;
  const long = x - y;
  const wrapped = ((((long - refLong + longitudinalRadius) % period) + period) % period) - longitudinalRadius + refLong;
  const ny = (lat - wrapped) / 2;
  return { x: wrapped + ny, y: ny };
}

// ---------------------------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------------------------

const BACKGROUND: RGB = [14, 15, 20];
const ACTOR_COLOUR = { name: "magenta", rgb: [255, 0, 220] as RGB };
/** Other civs: assigned by sorted civ id; repeats after the palette is exhausted. */
const CIV_PALETTE: readonly { name: string; rgb: RGB }[] = [
  { name: "red", rgb: [235, 30, 35] },
  { name: "orange", rgb: [255, 140, 0] },
  { name: "yellow", rgb: [255, 236, 0] },
  { name: "white", rgb: [250, 250, 250] },
  { name: "cyan", rgb: [0, 235, 235] },
  { name: "purple", rgb: [125, 55, 230] },
  { name: "black", rgb: [10, 10, 10] },
  { name: "brown", rgb: [150, 80, 25] },
  { name: "lime", rgb: [170, 255, 40] },
  { name: "teal", rgb: [0, 125, 125] },
  { name: "navy", rgb: [25, 35, 150] },
  { name: "pink", rgb: [255, 160, 205] },
];

const TERRAIN_COLOURS: Record<string, RGB> = {
  Ocean: [30, 66, 135],
  Coast: [58, 118, 198],
  Lakes: [78, 156, 222],
  Grassland: [84, 156, 56],
  Plains: [176, 174, 84],
  Desert: [228, 204, 130],
  Tundra: [140, 146, 120],
  Snow: [238, 242, 250],
  Mountain: [132, 122, 114],
};
const UNKNOWN_LAND: RGB = [150, 142, 118];
const UNKNOWN_WATER: RGB = TERRAIN_COLOURS.Coast!;
const UNKNOWN_TERRAIN: RGB = [96, 96, 102];
const ICE: RGB = [214, 232, 246];
const FLOOD_PLAINS: RGB = [192, 198, 104];
const RIVER: RGB = [35, 125, 255];
const ROAD: RGB = [128, 78, 36];
const RAILROAD: RGB = [52, 52, 58];
const LABEL_TEXT: RGB = [255, 255, 255];
const CITY_TEXT: RGB = [255, 236, 150];
const DAMAGE: RGB = [255, 40, 40];

/** Remembered / not currently visible: ~55% brightness, hue kept so terrain stays readable. */
function dim(c: RGB): RGB {
  return [Math.round(c[0] * 0.55 + 4), Math.round(c[1] * 0.55 + 4), Math.round(c[2] * 0.55 + 6)];
}
function shade(c: RGB, f: number): RGB {
  return [Math.round(c[0] * f), Math.round(c[1] * f), Math.round(c[2] * f)];
}
function luminance(c: RGB): number { return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; }

/** Stable civ -> colour assignment: actor fixed, others by sorted id over every civ known. */
export function assignCivColours(actorCivId: string, civIds: Iterable<string>): Map<string, { name: string; rgb: RGB }> {
  const others = [...new Set(civIds)].filter(id => id !== actorCivId).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const colours = new Map<string, { name: string; rgb: RGB }>();
  colours.set(actorCivId, ACTOR_COLOUR);
  others.forEach((id, i) => colours.set(id, CIV_PALETTE[i % CIV_PALETTE.length]!));
  return colours;
}

// ---------------------------------------------------------------------------------------------
// Tiny 3x5 bitmap font (digits, A-Z, a little punctuation)
// ---------------------------------------------------------------------------------------------

const FONT_ROWS: Record<string, string> = {
  "0": "111101101101111", "1": "010110010010111", "2": "111001111100111", "3": "111001111001111",
  "4": "101101111001001", "5": "111100111001111", "6": "111100111101111", "7": "111001001001001",
  "8": "111101111101111", "9": "111101111001111", "-": "000000111000000", ",": "000000010010100",
  ".": "000000000000010", " ": "000000000000000", "'": "010010000000000",
  A: "010101111101101", B: "110101110101110", C: "011100100100011", D: "110101101101110",
  E: "111100111100111", F: "111100111100100", G: "011100101101011", H: "101101111101101",
  I: "111010010010111", J: "001001001101010", K: "101101110101101", L: "100100100100111",
  M: "101111111101101", N: "110101101101101", O: "010101101101010", P: "110101110100100",
  Q: "010101101110011", R: "110101110101101", S: "011100010001110", T: "111010010010010",
  U: "101101101101111", V: "101101101101010", W: "101101111111101", X: "101101010101101",
  Y: "101101010010010", Z: "111001010100111",
};
const FONT: Record<string, Uint8Array> = Object.fromEntries(
  Object.entries(FONT_ROWS).map(([ch, bits]) => [ch, Uint8Array.from(bits, b => (b === "1" ? 1 : 0))]),
);

function textWidth(text: string, scale: number): number { return (text.length * 4 - 1) * scale; }

/** Uppercase ASCII-only rendition limited to glyphs the font has. */
function fontSafe(text: string, maxChars: number): string {
  const folded = text.normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase();
  let out = "";
  for (const ch of folded) {
    out += FONT[ch] ? ch : " ";
    if (out.length >= maxChars) break;
  }
  return out.replace(/\s+/g, " ").trim();
}

// ---------------------------------------------------------------------------------------------
// Raster helpers
// ---------------------------------------------------------------------------------------------

class Canvas {
  readonly rgba: Uint8Array;
  constructor(readonly width: number, readonly height: number) {
    this.rgba = new Uint8Array(width * height * 4);
  }
  set(x: number, y: number, c: RGB): void {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
    const i = (y * this.width + x) * 4;
    this.rgba[i] = c[0]; this.rgba[i + 1] = c[1]; this.rgba[i + 2] = c[2]; this.rgba[i + 3] = 255;
  }
  blend(x: number, y: number, c: RGB, alpha: number): void {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
    const i = (y * this.width + x) * 4;
    const a = this.rgba;
    a[i] = Math.round(a[i]! * (1 - alpha) + c[0] * alpha);
    a[i + 1] = Math.round(a[i + 1]! * (1 - alpha) + c[1] * alpha);
    a[i + 2] = Math.round(a[i + 2]! * (1 - alpha) + c[2] * alpha);
    a[i + 3] = 255;
  }
  fillRect(x0: number, y0: number, w: number, h: number, c: RGB): void {
    for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) this.set(x, y, c);
  }
  blendRect(x0: number, y0: number, w: number, h: number, c: RGB, alpha: number): void {
    for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) this.blend(x, y, c, alpha);
  }
  /** Filled disc (r measured to pixel centres). */
  fillCircle(cx: number, cy: number, r: number, c: RGB): void {
    const r2 = r * r;
    for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++) {
      for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
        const dx = x + 0.5 - cx, dy = y + 0.5 - cy;
        if (dx * dx + dy * dy <= r2) this.set(x, y, c);
      }
    }
  }
  fillDiamond(cx: number, cy: number, r: number, c: RGB): void {
    for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++) {
      for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
        if (Math.abs(x + 0.5 - cx) + Math.abs(y + 0.5 - cy) <= r) this.set(x, y, c);
      }
    }
  }
  /** Thick segment by distance-to-segment test over its bounding box. */
  line(x0: number, y0: number, x1: number, y1: number, width: number, c: RGB): void {
    const hw = width / 2;
    const vx = x1 - x0, vy = y1 - y0;
    const len2 = vx * vx + vy * vy || 1;
    for (let y = Math.floor(Math.min(y0, y1) - hw); y <= Math.ceil(Math.max(y0, y1) + hw); y++) {
      for (let x = Math.floor(Math.min(x0, x1) - hw); x <= Math.ceil(Math.max(x0, x1) + hw); x++) {
        const px = x + 0.5 - x0, py = y + 0.5 - y0;
        const t = Math.max(0, Math.min(1, (px * vx + py * vy) / len2));
        const dx = px - t * vx, dy = py - t * vy;
        if (dx * dx + dy * dy <= hw * hw) this.set(x, y, c);
      }
    }
  }
  text(text: string, x0: number, y0: number, scale: number, c: RGB): void {
    let x = x0;
    for (const ch of text) {
      const glyph = FONT[ch];
      if (glyph) {
        for (let row = 0; row < 5; row++) {
          for (let col = 0; col < 3; col++) {
            if (glyph[row * 3 + col]) this.fillRect(x + col * scale, y0 + row * scale, scale, scale, c);
          }
        }
      }
      x += 4 * scale;
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Input normalisation
// ---------------------------------------------------------------------------------------------

const enum Pattern { None, Forest, Jungle, Marsh, Dots }

interface PreparedTile {
  x: number; y: number;          // engine (canonical) coordinates
  ux: number; uy: number;        // unwrapped coordinates used for drawing
  visible: boolean;
  owner: string | null;
  ownerRgb: RGB | null;
  base: RGB;
  pattern: Pattern;
  patternRgb: RGB;
  hill: boolean;
  hillRgb: RGB;
  mountain: boolean;
  peakRgb: RGB;
  snowRgb: RGB;
  oasis: boolean;
  wonder: boolean;
  road: 0 | 1 | 2;
  hasCity: boolean;
  riverOwn: number;              // own bottom-edge river bits (edge mask)
  borderMask: number;
  riverMask: number;
}

interface PreparedMarker { ux: number; uy: number; x: number; y: number }
interface PreparedUnit extends PreparedMarker { unitId: number; name: string; owner: string; health: number | null; civilian: boolean }
interface PreparedCity extends PreparedMarker { cityId: string; name: string; owner: string }

function str(v: unknown): string | null { return typeof v === "string" && v.length > 0 ? v : null; }
function int(v: unknown): number | null { return typeof v === "number" && Number.isInteger(v) ? v : null; }
function strings(v: unknown): string[] { return Array.isArray(v) ? v.filter((s): s is string => typeof s === "string") : []; }

const CIVILIAN_UNIT = /^(settler|worker|work ?boats?|caravan|cargo ship|missionary|inquisitor|archaeologist|prophet|khan|great .+)$/i;

function terrainOf(tile: Partial<ObservedTile>, features: string[]): { base: RGB; hill: boolean; mountain: boolean } {
  const terrain = str(tile.terrain);
  let base: RGB | undefined = terrain ? TERRAIN_COLOURS[terrain] : undefined;
  const hill = tile.hill === true || features.includes("Hill") || terrain === "Hill";
  const mountain = terrain === "Mountain";
  if (!base) base = tile.water === true ? UNKNOWN_WATER : tile.land === true || terrain === "Hill" ? UNKNOWN_LAND : UNKNOWN_TERRAIN;
  if (features.includes("Ice")) base = ICE;
  else if (features.includes("Flood plains")) base = FLOOD_PLAINS;
  return { base, hill, mountain };
}

function patternOf(features: string[]): { pattern: Pattern; rgb: RGB } {
  if (features.includes("Jungle")) return { pattern: Pattern.Jungle, rgb: [6, 92, 64] };
  if (features.includes("Forest")) return { pattern: Pattern.Forest, rgb: [22, 78, 24] };
  if (features.includes("Marsh")) return { pattern: Pattern.Marsh, rgb: [36, 104, 116] };
  if (features.includes("Fallout")) return { pattern: Pattern.Dots, rgb: [160, 60, 170] };
  if (features.includes("Atoll")) return { pattern: Pattern.Dots, rgb: [232, 214, 152] };
  return { pattern: Pattern.None, rgb: [0, 0, 0] };
}

// ---------------------------------------------------------------------------------------------
// Rasterizer
// ---------------------------------------------------------------------------------------------

/** Rasterize the civ-scoped map to RGBA (see renderMapImage). Exported for tests/diagnostics. */
export function rasterizeMap(input: MapImageInput): MapRaster {
  const actor = input.actorCivId;
  const wrapRadius = input.worldWrap === true && typeof input.maxLongitude === "number"
    && Number.isFinite(input.maxLongitude) && input.maxLongitude > 0 ? Math.round(input.maxLongitude) : null;

  // --- Gather tiles (first occurrence of a coordinate wins), cities and units -------------------
  const rawTiles: (Partial<ObservedTile> & { x: number; y: number })[] = [];
  const seen = new Set<string>();
  for (const tile of input.tiles ?? []) {
    if (!tile || int(tile.x) === null || int(tile.y) === null) continue;
    const key = `${tile.x},${tile.y}`;
    if (seen.has(key)) continue;
    seen.add(key);
    rawTiles.push(tile);
  }

  const cityById = new Map<string, { cityId: string; name: string; owner: string; x: number; y: number }>();
  for (const city of input.cities ?? []) {
    const cityId = str(city?.cityId), owner = str(city?.ownerCivId);
    if (!cityId || !owner || int(city.x) === null || int(city.y) === null || cityById.has(cityId)) continue;
    cityById.set(cityId, { cityId, name: str(city.name) ?? "", owner, x: city.x, y: city.y });
  }
  for (const tile of rawTiles) {
    const city = tile.city;
    const cityId = str(city?.cityId), owner = str(city?.ownerCivId);
    if (!city || !cityId || !owner || cityById.has(cityId)) continue;
    cityById.set(cityId, { cityId, name: str(city.name) ?? "", owner, x: tile.x, y: tile.y });
  }

  const unitByKey = new Map<string, MapImageUnit>();
  for (const unit of input.units ?? []) {
    const owner = str(unit?.ownerCivId);
    if (!owner || int(unit.unitId) === null || int(unit.x) === null || int(unit.y) === null) continue;
    const key = `${owner}:${unit.unitId}`;
    if (!unitByKey.has(key)) unitByKey.set(key, unit);
  }

  // --- Palette over every civ known (stable across windows) ------------------------------------
  const civIds = new Set<string>(strings(input.knownCivIds));
  for (const tile of rawTiles) { const owner = str(tile.ownerCivId); if (owner) civIds.add(owner); }
  for (const city of cityById.values()) civIds.add(city.owner);
  for (const unit of unitByKey.values()) civIds.add(unit.ownerCivId);
  const civColours = assignCivColours(actor, civIds);

  // --- Unwrap reference + window -----------------------------------------------------------------
  const center = input.center && int(input.center.x) !== null && int(input.center.y) !== null ? { x: input.center.x, y: input.center.y } : null;
  const radius = center && typeof input.radius === "number" && Number.isFinite(input.radius) && input.radius >= 0 ? Math.floor(input.radius) : null;
  let reference = center;
  if (!reference) {
    const ownCity = [...cityById.values()].find(c => c.owner === actor);
    const ownUnit = [...unitByKey.values()].find(u => u.ownerCivId === actor);
    reference = ownCity ? { x: ownCity.x, y: ownCity.y } : ownUnit ? { x: ownUnit.x, y: ownUnit.y } : null;
  }
  const place = (x: number, y: number): { x: number; y: number } =>
    wrapRadius !== null && reference ? unwrapNearest(x, y, reference.x, reference.y, wrapRadius) : { x, y };
  const inWindow = (ux: number, uy: number): boolean =>
    radius === null || !center || hexDistance(ux, uy, center.x, center.y) <= radius;

  // --- Prepare drawable tiles ------------------------------------------------------------------
  const cityTiles = new Set([...cityById.values()].map(c => `${c.x},${c.y}`));
  const tiles: PreparedTile[] = [];
  for (const tile of rawTiles) {
    const u = place(tile.x, tile.y);
    if (!inWindow(u.x, u.y)) continue;
    const features = strings(tile.features);
    const visible = tile.visibility === "VISIBLE";
    const { base, hill, mountain } = terrainOf(tile, features);
    const { pattern, rgb: patternRgb } = patternOf(features);
    const owner = str(tile.ownerCivId);
    const hillRgb = shade(base, 0.72);
    const peakRgb: RGB = [72, 66, 62];
    const snowRgb: RGB = [244, 244, 250];
    const look = (c: RGB): RGB => (visible ? c : dim(c));
    const roadName = str(tile.road);
    const edges = tile.riverEdges;
    let riverOwn = 0;
    if (edges && typeof edges === "object") {
      if (edges.bottomRight === true) riverOwn |= 1 << EDGE_4;
      if (edges.bottom === true) riverOwn |= 1 << EDGE_6;
      if (edges.bottomLeft === true) riverOwn |= 1 << EDGE_8;
    }
    tiles.push({
      x: tile.x, y: tile.y, ux: u.x, uy: u.y, visible, owner,
      ownerRgb: owner ? civColours.get(owner)!.rgb : null,
      base: look(hill ? shade(base, 0.86) : base), pattern, patternRgb: look(patternRgb),
      hill, hillRgb: look(hillRgb), mountain, peakRgb: look(peakRgb), snowRgb: look(snowRgb),
      oasis: features.includes("Oasis"), wonder: str(tile.naturalWonder) !== null,
      road: roadName === "Railroad" ? 2 : roadName && roadName !== "None" ? 1 : 0,
      hasCity: cityTiles.has(`${tile.x},${tile.y}`), riverOwn, borderMask: 0, riverMask: 0,
    });
  }
  tiles.sort((a, b) => a.x - b.x || a.y - b.y);

  const key = (x: number, y: number): number => (x + 32768) * 65536 + (y + 32768);
  const byUnwrapped = new Map<number, number>();
  // Out-of-range engine coordinates could unwrap onto an existing hex: keep the first only.
  for (let i = 0; i < tiles.length; i++) {
    const k = key(tiles[i]!.ux, tiles[i]!.uy);
    if (byUnwrapped.has(k)) tiles.splice(i--, 1);
    else byUnwrapped.set(k, i);
  }
  const at = (x: number, y: number): PreparedTile | undefined => {
    const i = byUnwrapped.get(key(x, y));
    return i === undefined ? undefined : tiles[i];
  };

  for (const t of tiles) {
    for (let k = 0; k < 6; k++) {
      const n = at(t.ux + NEIGHBOURS[k]![0], t.uy + NEIGHBOURS[k]![1]);
      if (t.owner && (!n || n.owner !== t.owner)) t.borderMask |= 1 << k;
      if (t.riverOwn & (1 << k)) {
        t.riverMask |= 1 << k;
        if (n) n.riverMask |= 1 << ((k + 3) % 6);
      }
    }
  }

  const cities: PreparedCity[] = [];
  for (const c of [...cityById.values()].sort((a, b) => a.x - b.x || a.y - b.y || (a.cityId < b.cityId ? -1 : 1))) {
    const u = place(c.x, c.y);
    if (inWindow(u.x, u.y)) cities.push({ ...c, ux: u.x, uy: u.y });
  }
  const units: PreparedUnit[] = [];
  for (const unit of unitByKey.values()) {
    const u = place(unit.x, unit.y);
    if (!inWindow(u.x, u.y)) continue;
    units.push({
      unitId: unit.unitId, name: str(unit.name) ?? "", owner: unit.ownerCivId, x: unit.x, y: unit.y, ux: u.x, uy: u.y,
      health: typeof unit.health === "number" && Number.isFinite(unit.health) && unit.health < 100 ? unit.health : null,
      civilian: CIVILIAN_UNIT.test(str(unit.name) ?? ""),
    });
  }

  // --- Layout ------------------------------------------------------------------------------------
  let minWX = Infinity, maxWX = -Infinity, minWY = Infinity, maxWY = -Infinity;
  const extend = (x: number, y: number): void => {
    const w = hexToWorld(x, y);
    minWX = Math.min(minWX, w.x - 1); maxWX = Math.max(maxWX, w.x + 1);
    minWY = Math.min(minWY, w.y - APOTHEM); maxWY = Math.max(maxWY, w.y + APOTHEM);
  };
  for (const t of tiles) extend(t.ux, t.uy);
  for (const c of cities) extend(c.ux, c.uy);
  for (const u of units) extend(u.ux, u.uy);
  const empty = minWX === Infinity;
  if (empty) { minWX = -1; maxWX = 1; minWY = -APOTHEM; maxWY = APOTHEM; }

  const maxPixels = Math.max(64, Math.min(4096, Math.round(typeof input.maxPixels === "number" && Number.isFinite(input.maxPixels) ? input.maxPixels : DEFAULT_MAX_PIXELS)));
  const spanX = maxWX - minWX, spanY = maxWY - minWY;
  // maxPixels is a hard cap (predictable image-token cost). Typical maps land at R 10-28 px; only
  // very large whole-map renders drop below ~6 px, where markers shrink and labels thin out.
  const fit = (maxPixels - 2 * MARGIN) / Math.max(spanX, spanY);
  const R = Math.max(1, Math.min(MAX_HEX_RADIUS, fit));
  const width = Math.max(1, Math.ceil(spanX * R + 2 * MARGIN));
  const height = Math.max(1, Math.ceil(spanY * R + 2 * MARGIN));
  const ox = MARGIN - minWX * R;
  const oy = MARGIN + maxWY * R;
  const hexToPixel = (x: number, y: number): { px: number; py: number } => {
    const w = hexToWorld(x, y);
    return { px: ox + w.x * R, py: oy - w.y * R };
  };

  const canvas = new Canvas(width, height);
  const rgba = canvas.rgba;

  // --- Pixel pass: terrain, patterns, grid, borders, rivers ---------------------------------------
  const gridPx = 0.6;
  const borderPx = Math.max(2, Math.round(R * 0.17));
  const riverPx = Math.max(1.2, R * 0.09);
  const invR = 1 / R;
  const showGlyphs = R >= 7;
  let lastHx = NaN, lastHy = NaN;
  let tile: PreparedTile | undefined;
  for (let py = 0; py < height; py++) {
    const wy = (oy - (py + 0.5)) * invR;
    const s = wy / APOTHEM;             // x + y
    for (let px = 0; px < width; px++) {
      const wx = (px + 0.5 - ox) * invR;
      const q = wx / 1.5;               // y - x
      const fx = (s - q) / 2, fy = (s + q) / 2;
      // Cube rounding with Unciv's hex2CubicCoords = (y - x, x, -y).
      const ca = fy - fx, cb = fx, cc = -fy;
      let ra = Math.round(ca), rb = Math.round(cb), rc = Math.round(cc);
      const da = Math.abs(ra - ca), db = Math.abs(rb - cb), dc = Math.abs(rc - cc);
      if (da > db && da > dc) ra = -rb - rc;
      else if (db > dc) rb = -ra - rc;
      else rc = -ra - rb;
      const hx = rb, hy = -rc;
      if (hx !== lastHx || hy !== lastHy) { lastHx = hx; lastHy = hy; tile = at(hx, hy); }
      const o = (py * width + px) * 4;
      if (!tile) {
        rgba[o] = BACKGROUND[0]; rgba[o + 1] = BACKGROUND[1]; rgba[o + 2] = BACKGROUND[2]; rgba[o + 3] = 255;
        continue;
      }
      const dx = wx - 1.5 * (hy - hx);
      const dy = wy - APOTHEM * (hx + hy);
      let c: RGB = tile.base;
      switch (tile.pattern) {
        case Pattern.Forest:
          if ((px & 3) < 2 && (py & 3) < 2 && (((px >> 2) + (py >> 2)) & 1) === 0) c = tile.patternRgb;
          break;
        case Pattern.Jungle:
          if ((px & 3) < 2 && (py & 3) < 2) c = tile.patternRgb;
          break;
        case Pattern.Marsh:
          if (py % 4 === 0 && px % 6 < 4) c = tile.patternRgb;
          break;
        case Pattern.Dots:
          if (px % 4 === 0 && py % 4 === 0) c = tile.patternRgb;
          break;
        default:
          break;
      }
      if (tile.hill && (px + py) % 5 === 0) c = tile.hillRgb;
      if (tile.mountain && showGlyphs && dy >= -0.42 && dy <= 0.52 && Math.abs(dx) <= 0.6 * (0.52 - dy)) {
        c = dy > 0.26 ? tile.snowRgb : tile.peakRgb;
      }
      if (tile.oasis && dx * dx + dy * dy < 0.05) c = tile.visible ? [70, 200, 235] : dim([70, 200, 235]);
      if (tile.wonder && showGlyphs) {
        const m = Math.abs(dx) + Math.abs(dy * 1.1);
        if (m < 0.3) c = [255, 206, 0];
        else if (m < 0.38) c = [50, 36, 0];
      }
      let r = c[0], g = c[1], b = c[2];
      const d0 = 0.5 * dy + APOTHEM * dx;   // towards 2 o'clock
      const d2 = 0.5 * dy - APOTHEM * dx;   // towards 10 o'clock
      const edge = (APOTHEM - Math.max(Math.abs(dy), Math.abs(d0), Math.abs(d2))) * R;
      if (edge < gridPx) { r *= 0.62; g *= 0.62; b *= 0.62; }
      if (tile.borderMask !== 0 || tile.riverMask !== 0) {
        // Pixel distance to each edge, indexed 12, 2, 4, 6, 8, 10 o'clock.
        const e0 = (APOTHEM - dy) * R, e1 = (APOTHEM - d0) * R, e2 = (APOTHEM + d2) * R;
        const e3 = (APOTHEM + dy) * R, e4 = (APOTHEM + d0) * R, e5 = (APOTHEM - d2) * R;
        if (tile.borderMask !== 0) {
          const m = tile.borderMask;
          let nearest = Infinity;
          if (m & 1) nearest = Math.min(nearest, e0);
          if (m & 2) nearest = Math.min(nearest, e1);
          if (m & 4) nearest = Math.min(nearest, e2);
          if (m & 8) nearest = Math.min(nearest, e3);
          if (m & 16) nearest = Math.min(nearest, e4);
          if (m & 32) nearest = Math.min(nearest, e5);
          const oc = tile.ownerRgb!;
          if (nearest < borderPx) { r = oc[0]; g = oc[1]; b = oc[2]; }
          else if (nearest < borderPx + 1) { r *= 0.35; g *= 0.35; b *= 0.35; }
        }
        if (tile.riverMask !== 0) {
          const m = tile.riverMask;
          if (((m & 1) && e0 < riverPx) || ((m & 2) && e1 < riverPx) || ((m & 4) && e2 < riverPx)
            || ((m & 8) && e3 < riverPx) || ((m & 16) && e4 < riverPx) || ((m & 32) && e5 < riverPx)) {
            r = RIVER[0]; g = RIVER[1]; b = RIVER[2];
          }
        }
      }
      rgba[o] = r; rgba[o + 1] = g; rgba[o + 2] = b; rgba[o + 3] = 255;
    }
  }

  // --- Roads (centre to centre between adjacent road/city tiles) -----------------------------------
  const roadWidth = Math.max(1.5, R * 0.11);
  for (const t of tiles) {
    if (!t.road && !t.hasCity) continue;
    const p = hexToPixel(t.ux, t.uy);
    let linked = false;
    for (let k = 0; k < 6; k++) {
      const n = at(t.ux + NEIGHBOURS[k]![0], t.uy + NEIGHBOURS[k]![1]);
      if (!n || (!n.road && !n.hasCity) || (!t.road && !n.road)) continue;
      linked = true;
      if (k >= 3) continue; // each pair once: draw from the lower tile towards 12/2/4... (edges 0..2)
      const q = hexToPixel(n.ux, n.uy);
      const colour = t.road === 2 && (n.road === 2 || n.hasCity) || n.road === 2 && t.hasCity ? RAILROAD : ROAD;
      canvas.line(p.px, p.py, q.px, q.py, roadWidth, t.visible || n.visible ? colour : dim(colour));
    }
    if (!linked && t.road) canvas.fillCircle(p.px, p.py, roadWidth, t.visible ? (t.road === 2 ? RAILROAD : ROAD) : dim(ROAD));
  }

  // --- Cities ------------------------------------------------------------------------------------
  const markersOn = R >= 3;
  const cityHalf = Math.max(3, R * 0.36);
  const outline = R >= 14 ? 2 : 1;
  const cityAt = new Set<number>();
  if (markersOn) {
    for (const c of cities) {
      cityAt.add(key(c.ux, c.uy));
      const p = hexToPixel(c.ux, c.uy);
      const x0 = Math.round(p.px - cityHalf), y0 = Math.round(p.py - cityHalf), size = Math.round(cityHalf * 2);
      canvas.fillRect(x0 - outline - 1, y0 - outline - 1, size + 2 * outline + 2, size + 2 * outline + 2, [0, 0, 0]);
      canvas.fillRect(x0 - outline, y0 - outline, size + 2 * outline, size + 2 * outline, [255, 255, 255]);
      canvas.fillRect(x0, y0, size, size, civColours.get(c.owner)!.rgb);
    }
  }

  // --- Units (up to 3 markers per tile) ------------------------------------------------------------
  const unitsByTile = new Map<number, PreparedUnit[]>();
  for (const u of units) {
    const k = key(u.ux, u.uy);
    const list = unitsByTile.get(k);
    if (list) list.push(u); else unitsByTile.set(k, [u]);
  }
  const unitR = Math.max(2.5, R * 0.22);
  const barHeight = Math.max(2, Math.round(R * 0.09));
  if (markersOn) {
    for (const list of [...unitsByTile.values()].sort((a, b) => a[0]!.ux - b[0]!.ux || a[0]!.uy - b[0]!.uy)) {
      list.sort((a, b) => Number(a.owner !== actor) - Number(b.owner !== actor)
        || Number(a.civilian) - Number(b.civilian) || a.unitId - b.unitId);
      const first = list[0]!;
      const p = hexToPixel(first.ux, first.uy);
      const onCity = cityAt.has(key(first.ux, first.uy));
      const shown = list.slice(0, 3);
      const slots: [number, number][] = onCity
        ? [[-0.52, 0.42], [0.52, 0.42], [0, 0.62]]
        : shown.length === 1 ? [[0, -0.3]] : [[-0.4, -0.32], [0.4, -0.32], [0, 0.06]];
      shown.forEach((u, i) => {
        const [sx, sy] = slots[i]!;
        const cx = p.px + sx * R, cy = p.py + sy * R;
        const fill = civColours.get(u.owner)!.rgb;
        const ring: RGB = luminance(fill) < 60 ? [235, 235, 235] : [0, 0, 0];
        if (u.civilian) {
          canvas.fillDiamond(cx, cy, unitR * 1.25 + 1.2, ring);
          canvas.fillDiamond(cx, cy, unitR * 1.25, fill);
        } else {
          canvas.fillCircle(cx, cy, unitR + 1, ring);
          canvas.fillCircle(cx, cy, unitR, fill);
        }
        if (u.health !== null) {
          // Health bar under damaged units: black track, fill length = HP fraction.
          const barWidth = Math.max(5, Math.round(unitR * 2 + 2));
          const x0 = Math.round(cx - barWidth / 2), y0 = Math.round(cy + unitR * (u.civilian ? 1.25 : 1) + 2);
          const filled = Math.max(1, Math.round(((barWidth - 2) * Math.max(0, u.health)) / 100));
          const colour: RGB = u.health < 34 ? DAMAGE : u.health < 67 ? [255, 210, 0] : [70, 230, 70];
          canvas.fillRect(x0, y0, barWidth, barHeight + 2, [0, 0, 0]);
          canvas.fillRect(x0 + 1, y0 + 1, filled, barHeight, colour);
        }
      });
    }
  }

  // --- Labels --------------------------------------------------------------------------------------
  // Always 2x-scaled 3x5 glyphs (10 px tall): smaller text is not reliably legible to vision models.
  // Density adapts instead: the x,y lattice step grows as hexes shrink, and colliding labels are skipped.
  const labelsOn = R >= 4;
  const scale = 2;
  const pad = 2;
  const sampleWidth = textWidth("-22,-22", scale) + 2 * pad;
  const latticeStep = Math.max(3, Math.ceil((2 * sampleWidth) / (SQRT3 * R)));
  const namesOn = R >= 10;
  const placed: { x0: number; y0: number; x1: number; y1: number; text: string; colour: RGB }[] = [];
  const addLabel = (text: string, cx: number, cy: number, colour: RGB): boolean => {
    const w = textWidth(text, scale) + 2 * pad, h = 5 * scale + 2 * pad;
    if (w > width || h > height) return false;
    // Keep the whole label inside the image (nudged inwards at the edges, never clipped).
    const x0 = Math.max(0, Math.min(width - w, Math.round(cx - w / 2)));
    const y0 = Math.max(0, Math.min(height - h, Math.round(cy - h / 2)));
    const rect = { x0, y0, x1: x0 + w, y1: y0 + h, text, colour };
    if (placed.some(o => rect.x0 < o.x1 + 1 && o.x0 < rect.x1 + 1 && rect.y0 < o.y1 + 1 && o.y0 < rect.y1 + 1)) return false;
    placed.push(rect);
    return true;
  };
  let centerLabelled = false;
  if (labelsOn) {
    const belowCentre = Math.min(0.42 * R, APOTHEM * R - 2);
    for (const c of cities) {
      const p = hexToPixel(c.ux, c.uy);
      const cy = p.py - cityHalf - outline - 3 - (5 * scale) / 2 - pad;
      const name = namesOn ? fontSafe(c.name, 8) : "";
      if (!(name && addLabel(`${name} ${c.x},${c.y}`, p.px, cy, CITY_TEXT))) addLabel(`${c.x},${c.y}`, p.px, cy, CITY_TEXT);
    }
    if (center && !cities.some(c => c.x === center.x && c.y === center.y)) {
      const p = hexToPixel(center.x, center.y);
      centerLabelled = addLabel(`${center.x},${center.y}`, p.px, p.py + belowCentre, LABEL_TEXT);
    }
    for (const t of tiles) {
      if (t.x % latticeStep !== 0 || t.y % latticeStep !== 0 || t.hasCity) continue;
      if (center && t.x === center.x && t.y === center.y) continue;
      const p = hexToPixel(t.ux, t.uy);
      addLabel(`${t.x},${t.y}`, p.px, p.py + belowCentre, LABEL_TEXT);
    }
    for (const l of placed) {
      canvas.blendRect(l.x0, l.y0, l.x1 - l.x0, l.y1 - l.y0, [6, 6, 10], 0.74);
      canvas.text(l.text, l.x0 + pad, l.y0 + pad, scale, l.colour);
    }
  }

  // --- Legend --------------------------------------------------------------------------------------
  const count = (n: number, one: string, many: string): string => `${n} ${n === 1 ? one : many}`;
  const visibleCount = tiles.filter(t => t.visible).length;
  const shownCivs = new Set<string>([actor]);
  for (const t of tiles) if (t.owner) shownCivs.add(t.owner);
  for (const c of cities) shownCivs.add(c.owner);
  for (const u of units) shownCivs.add(u.owner);
  const others = [...shownCivs].filter(id => id !== actor).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const xs = tiles.map(t => t.x), ys = tiles.map(t => t.y);
  const bounds = tiles.length ? `x ${Math.min(...xs)}..${Math.max(...xs)}, y ${Math.min(...ys)}..${Math.max(...ys)}` : "";
  const windowText = center ? `; center ${center.x},${center.y}${radius !== null ? ` r${radius}` : ""}` : "";
  const buildLegend = (maxCivs: number, withTerrain: boolean): string => {
    const listed = others.slice(0, maxCivs).map(id => `${id}=${civColours.get(id)!.name}`);
    if (others.length > maxCivs) listed.push(`+${others.length - maxCivs} more`);
    const parts = [
      `Map known to ${actor} (Unciv hex x,y; north up; x+1=up-left, y+1=up-right).`,
      empty ? "No known tiles in view." : `Tiles ${bounds}${windowText}${wrapRadius !== null && reference ? "; seam unwrapped" : ""}. ${count(tiles.length, "tile", "tiles")} (${visibleCount} visible), ${count(units.length, "unit", "units")}, ${count(cities.length, "city", "cities")}.`,
      `Colours: ${[`${actor} (you)=magenta`, ...listed].join(", ")}; band inside hex edge=territory.`,
      "Square=city; circle=military, diamond=civilian; bar below unit=damaged (length=HP); max 3 units/tile.",
      "Dimmed=remembered, not visible now; black=unknown. Blue edges=rivers; brown=road, grey=rail.",
      withTerrain ? "Terrain: blue water, green grassland, olive plains, tan desert, grey-green tundra, white snow, peak=mountain, stripes=hill, dots=forest/jungle, gold diamond=wonder." : "",
      labelsOn
        ? `Labels: yellow ${namesOn ? "NAME x,y" : "x,y"} above cities; white x,y where x and y are multiples of ${latticeStep}${centerLabelled ? ", and the center" : ""}.`
        : "No labels (hexes too small); use a smaller radius.",
    ];
    return parts.filter(Boolean).join(" ");
  };
  let legend = buildLegend(6, true);
  if (legend.length > LEGEND_MAX) legend = buildLegend(6, false);
  for (let n = 5; legend.length > LEGEND_MAX && n >= 0; n--) legend = buildLegend(n, false);
  if (legend.length > LEGEND_MAX) legend = `${legend.slice(0, LEGEND_MAX - 3)}...`;

  const drawnAt = new Map<string, { ux: number; uy: number }>();
  for (const t of tiles) drawnAt.set(`${t.x},${t.y}`, { ux: t.ux, uy: t.uy });
  return {
    width, height, rgba, hexRadius: R, legend, tileCount: tiles.length, civColours,
    tilePixel: (x, y) => { const d = drawnAt.get(`${x},${y}`); return d ? hexToPixel(d.ux, d.uy) : null; },
    hexToPixel,
  };
}

/**
 * Render a civilization-scoped map as a PNG plus a short plain-text legend.
 * Deterministic: identical input produces a byte-identical PNG.
 */
export function renderMapImage(input: MapImageInput): MapImageResult {
  let maxPixels = typeof input.maxPixels === "number" && Number.isFinite(input.maxPixels) ? input.maxPixels : DEFAULT_MAX_PIXELS;
  for (;;) {
    const raster = rasterizeMap({ ...input, maxPixels });
    const png = encodePng(raster.width, raster.height, raster.rgba);
    if (png.length <= MAX_PNG_BYTES || maxPixels <= 128) {
      return { png, width: raster.width, height: raster.height, legend: raster.legend, tileCount: raster.tileCount };
    }
    maxPixels = Math.floor(maxPixels * 0.75);
  }
}


// ---------------------------------------------------------------------------------------------
// PNG encoder (RGBA8, adaptive filtering, zlib deflate, CRC32)
// ---------------------------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

/** CRC-32 (ISO-HDLC / PNG). */
export function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]!) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Uint8Array): Buffer {
  const chunk = Buffer.alloc(12 + data.length);
  chunk.writeUInt32BE(data.length, 0);
  chunk.write(type, 4, "latin1");
  chunk.set(data, 8);
  chunk.writeUInt32BE(crc32(chunk.subarray(4, 8 + data.length)), 8 + data.length);
  return chunk;
}

/**
 * Encode RGBA8 pixels (row-major, width*height*4 bytes) as a PNG.
 * Every scanline uses filter type 0 (None): for flat-colour, dot-patterned maps it compresses
 * smaller than Sub/Up/adaptive and is several times faster.
 */
export function encodePng(width: number, height: number, rgba: Uint8Array): Buffer {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new RangeError(`Invalid PNG size ${width}x${height}`);
  }
  if (rgba.length !== width * height * 4) {
    throw new RangeError(`RGBA buffer has ${rgba.length} bytes; expected ${width * height * 4}`);
  }
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height); // filter byte 0 per row is already zero
  for (let y = 0; y < height; y++) raw.set(rgba.subarray(y * stride, (y + 1) * stride), y * (stride + 1) + 1);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // colour type RGBA
  ihdr[10] = 0;  // deflate
  ihdr[11] = 0;  // adaptive filtering
  ihdr[12] = 0;  // no interlace
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", deflateSync(raw, { level: 9 })),
    pngChunk("IEND", new Uint8Array(0)),
  ]);
}
