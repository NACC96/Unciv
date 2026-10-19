/**
 * Civilization-scoped map pictures for leaders (game.map_image and the
 * turn-start overview). Inputs are the actor's own committed reads, the
 * observation and its map knowledge pages, so a picture never shows more
 * than the JSON reads would; it just costs a fraction of their tokens.
 */
import type { IGameService } from "./fake-game-mcp.js";
import type { HexPosition, MapQuery, MapSummary, ObservedTile } from "./map-types.js";
import { hexDistance, renderMapImage, unwrapNearest, type MapImageCity, type MapImageResult, type MapImageUnit } from "./map-image.js";

/**
 * A picture keeps at most this many tiles: every predefined map and large custom ones (164×80 is
 * 13,120) fit whole. Beyond it the legend says the picture is partial.
 */
export const MAX_IMAGE_TILES = 32 * 1024;
/** Largest radius game.map serves directly; wider windows read everything and crop. */
const MAX_MAP_QUERY_RADIUS = 8;

type Entity = Record<string, unknown>;

const entities = (observation: Record<string, unknown>, ...lists: string[]): Entity[] =>
  lists.flatMap(list => Array.isArray(observation[list]) ? observation[list] as Entity[] : []);

const isPosition = (entity: Entity) => Number.isInteger(entity["x"]) && Number.isInteger(entity["y"]);

/** Pages through [query], keeping tiles that pass [keep]; incomplete when the tile cap stopped it. */
async function readTiles(game: IGameService, query: MapQuery, keep: (tile: ObservedTile) => boolean):
  Promise<{ tiles: ObservedTile[]; complete: boolean }> {
  const tiles: ObservedTile[] = [];
  let cursor: string | undefined;
  do {
    const page = await game.map({ ...query, limit: 128, ...(cursor ? { cursor } : {}) });
    if ("status" in page) throw Object.assign(new Error(page.message), { code: page.code });
    for (const tile of page.tiles) if (keep(tile)) tiles.push(tile);
    cursor = page.nextCursor ?? undefined;
  } while (cursor && tiles.length < MAX_IMAGE_TILES);
  return { tiles, complete: !cursor };
}

/**
 * Renders what the actor knows, or null when the requested window holds no known tile.
 * [complete] is false when the tile cap cut a whole-map picture short; its legend says so.
 */
export async function renderCivMapImage(
  game: IGameService,
  actorCivId: string,
  view: { center?: HexPosition; radius?: number } = {},
): Promise<(MapImageResult & { complete: boolean }) | null> {
  const observation = await game.observe() as unknown as Record<string, unknown>;
  const summary = (observation["mapSummary"] ?? {}) as Partial<MapSummary>;
  const { center, radius } = view;
  const windowed = center !== undefined && radius !== undefined;
  const direct = windowed && radius <= MAX_MAP_QUERY_RADIUS;
  // Wider windows read the whole map but keep only their own tiles, so the cap never empties them.
  const wrapRadius = summary.worldWrap && typeof summary.maxLongitude === "number" && summary.maxLongitude > 0
    ? Math.round(summary.maxLongitude) : null;
  const inWindow = (tile: ObservedTile) => {
    const at = wrapRadius !== null ? unwrapNearest(tile.x, tile.y, center!.x, center!.y, wrapRadius) : tile;
    return hexDistance(at.x, at.y, center!.x, center!.y) <= radius!;
  };
  const { tiles, complete } = await readTiles(game,
    direct ? { mode: "KNOWN", center, radius } : { mode: "KNOWN" },
    windowed && !direct ? inWindow : () => true);
  if (!tiles.length) return null;
  const units: MapImageUnit[] = entities(observation, "ownUnits", "visibleForeignUnits").filter(isPosition).map(unit => ({
    unitId: unit["unitId"] as number, name: String(unit["name"] ?? "Unit"),
    ownerCivId: String(unit["ownerCivId"] ?? actorCivId), x: unit["x"] as number, y: unit["y"] as number,
    health: typeof unit["health"] === "number" ? unit["health"] : null,
  }));
  const cities: MapImageCity[] = entities(observation, "ownCities", "visibleForeignCities").filter(isPosition).map(city => ({
    cityId: String(city["cityId"]), name: String(city["name"] ?? ""),
    ownerCivId: String(city["ownerCivId"] ?? actorCivId), x: city["x"] as number, y: city["y"] as number,
    population: typeof city["population"] === "number" ? city["population"] : null,
  }));
  // Every met nation, so a nation's colour stays the same when it moves out of view.
  const knownCivIds = entities(observation, "rivals").map(rival => rival["civId"]).filter((id): id is string => typeof id === "string");
  const image = renderMapImage({
    actorCivId, tiles, units, cities, knownCivIds,
    ...(windowed ? { center, radius } : {}),
    worldWrap: summary.worldWrap ?? false, maxLongitude: summary.maxLongitude ?? null,
  });
  if (!image.tileCount) return null;
  if (complete) return { ...image, complete };
  const known = typeof summary.knownTileCount === "number" ? ` of ${summary.knownTileCount}` : "";
  return { ...image, complete, legend: `${image.legend}\nPartial picture: it shows the first ${tiles.length}${known} ` +
    `known tiles (lowest x first). Request center and radius for any other area.` };
}
