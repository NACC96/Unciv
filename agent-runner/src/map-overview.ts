/**
 * Civilization-scoped map pictures for leaders (game.map_image and the
 * turn-start overview). Inputs are the actor's own committed reads, the
 * observation and its map knowledge pages, so a picture never shows more
 * than the JSON reads would; it just costs a fraction of their tokens.
 */
import type { IGameService } from "./fake-game-mcp.js";
import type { HexPosition, MapQuery, MapSummary, ObservedTile } from "./map-types.js";
import { renderMapImage, type MapImageCity, type MapImageResult, type MapImageUnit } from "./map-image.js";

/** Whole-map reads page through at most this many tiles. */
const MAX_IMAGE_TILES = 128 * 80;
/** Largest radius game.map serves directly; wider windows read everything and crop. */
const MAX_MAP_QUERY_RADIUS = 8;

type Entity = Record<string, unknown>;

const entities = (observation: Record<string, unknown>, ...lists: string[]): Entity[] =>
  lists.flatMap(list => Array.isArray(observation[list]) ? observation[list] as Entity[] : []);

const isPosition = (entity: Entity) => Number.isInteger(entity["x"]) && Number.isInteger(entity["y"]);

async function readTiles(game: IGameService, query: MapQuery): Promise<ObservedTile[]> {
  const tiles: ObservedTile[] = [];
  let cursor: string | undefined;
  do {
    const page = await game.map({ ...query, limit: 128, ...(cursor ? { cursor } : {}) });
    if ("status" in page) throw Object.assign(new Error(page.message), { code: page.code });
    tiles.push(...page.tiles);
    cursor = page.nextCursor ?? undefined;
  } while (cursor && tiles.length < MAX_IMAGE_TILES);
  return tiles;
}

/** Renders what the actor knows, or null when the requested window holds no known tile. */
export async function renderCivMapImage(
  game: IGameService,
  actorCivId: string,
  view: { center?: HexPosition; radius?: number } = {},
): Promise<MapImageResult | null> {
  const observation = await game.observe() as unknown as Record<string, unknown>;
  const direct = view.center !== undefined && view.radius !== undefined && view.radius <= MAX_MAP_QUERY_RADIUS;
  const tiles = await readTiles(game, direct ? { mode: "KNOWN", center: view.center, radius: view.radius } : { mode: "KNOWN" });
  if (!tiles.length) return null;
  const summary = (observation["mapSummary"] ?? {}) as Partial<MapSummary>;
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
    ...(view.center !== undefined && view.radius !== undefined ? { center: view.center, radius: view.radius } : {}),
    worldWrap: summary.worldWrap ?? false, maxLongitude: summary.maxLongitude ?? null,
  });
  return image.tileCount ? image : null;
}
