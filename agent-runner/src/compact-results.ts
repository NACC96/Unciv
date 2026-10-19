/**
 * Model-facing result compaction.
 *
 * Tool results are most of every provider request: a 65-turn match log held
 * ~100 KB of tool output per turn, mostly map tiles and observations that
 * spelled out every null, false and zero field, plus a copy of the private
 * correspondence the turn prompt already carries on every game.observe.
 * Model-visible reads drop those fields: an absent field means null, false,
 * zero or empty. Engine DTOs and the supervisor's own reads are unchanged.
 */

type Json = Record<string, unknown>;

const isObject = (value: unknown): value is Json =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const isEmpty = (value: unknown): boolean =>
  value === null || value === undefined || value === false || value === "" ||
  (Array.isArray(value) && value.length === 0) || (isObject(value) && Object.keys(value).length === 0);

/** Drops null/false/empty fields one level deep; numbers (including 0) are kept. */
export function dropEmpty<T extends Json>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, field]) => !isEmpty(field))) as Partial<T>;
}

/**
 * Tile: nonzero yields flatten into `yields` (with `yieldCity` naming the own
 * city whose bonuses apply), river edges become the list of edges carrying a
 * river, and absent roads, zero resource amounts and empty fields disappear.
 */
export function compactTile(tile: Json): Json {
  const out: Json = {};
  for (const [key, value] of Object.entries(tile)) {
    if (key === "yields") {
      if (!isObject(value)) continue;
      const values = isObject(value["values"]) ? value["values"] : {};
      const nonzero = Object.fromEntries(Object.entries(values).filter(([, amount]) => amount !== 0));
      if (Object.keys(nonzero).length) out["yields"] = nonzero;
      if (value["context"] === "ACTOR_CITY" && typeof value["cityId"] === "string") out["yieldCity"] = value["cityId"];
      continue;
    }
    if (key === "riverEdges") {
      if (!isObject(value)) continue;
      const edges = Object.entries(value).filter(([, river]) => river === true).map(([edge]) => edge);
      if (edges.length) out["riverEdges"] = edges;
      continue;
    }
    if (key === "resource" && isObject(value)) {
      out["resource"] = Object.fromEntries(Object.entries(value).filter(([field, amount]) =>
        !isEmpty(amount) && !(field === "amount" && amount === 0)));
      continue;
    }
    if (key === "city" && isObject(value)) {
      out["city"] = dropEmpty(value);
      continue;
    }
    if (key === "road" && value === "None") continue;
    if (isEmpty(value)) continue;
    out[key] = value;
  }
  return out;
}

/** Units and cities: own entries also drop the redundant ownerCivId. */
function compactEntity(entity: unknown, actorCivId: string): unknown {
  if (!isObject(entity)) return entity;
  const out = dropEmpty(entity) as Json;
  if (out["ownerCivId"] === actorCivId) delete out["ownerCivId"];
  if (isObject(out["tile"])) out["tile"] = compactTile(out["tile"]);
  return out;
}

const ENTITY_LISTS = ["ownUnits", "visibleForeignUnits", "ownCities", "visibleForeignCities"];

/**
 * game.observe for the model. diplomaticHistory repeats the turn prompt's
 * recent correspondence, so it is returned only on request.
 */
export function compactObservation(observation: unknown, actorCivId: string, options: { includeHistory?: boolean } = {}): unknown {
  if (!isObject(observation)) return observation;
  const out: Json = { ...observation };
  if (!options.includeHistory) delete out["diplomaticHistory"];
  for (const list of ENTITY_LISTS) {
    const entries = out[list];
    if (Array.isArray(entries)) out[list] = entries.map(entry => compactEntity(entry, actorCivId));
  }
  return out;
}

/** game.inspect for the model: a unit (with its tile) or an own city. */
export function compactInspection(entity: unknown, actorCivId: string): unknown {
  return compactEntity(entity, actorCivId);
}

/** game.map page for the model: the MapPage envelope with compact tiles. */
export function compactMapPage(page: unknown): unknown {
  if (!isObject(page) || !Array.isArray(page["tiles"])) return page;
  const out: Json = { ...page, tiles: page["tiles"].map(tile => isObject(tile) ? compactTile(tile) : tile) };
  if (out["nextCursor"] === null) delete out["nextCursor"];
  return out;
}

/** game.legal_actions for the model: empty option lists and unset flags disappear. */
export function compactLegalActions(result: unknown): unknown {
  if (!isObject(result)) return result;
  const out = dropEmpty(result) as Json;
  for (const list of ["attackTargets", "constructions", "citySites", "improvements"]) {
    const entries = out[list];
    if (Array.isArray(entries)) out[list] = entries.map(entry => isObject(entry) ? dropEmpty(entry) : entry);
  }
  return out;
}
