/** Additive observation v2 contract; mutations retain protocol version 1. */
export interface HexPosition { x: number; y: number }
export type MatchVision = "FOG" | "MAP" | "FULL";
export interface MapQuery {
  mode?: "KNOWN" | "VISIBLE";
  center?: HexPosition;
  radius?: number;
  limit?: number;
  cursor?: string;
}
export interface MapSummary {
  observationVersion: 2;
  coordinateSystem: "UNCIV_HEX";
  knownTileCount: number;
  visibleTileCount: number;
  worldWrap: boolean;
  vision: MatchVision;
  /** Longitude wrap radius when worldWrap (HexMath.getUnwrappedNearestTo); null otherwise. */
  maxLongitude?: number | null;
}
export interface ObservedTile extends HexPosition {
  visibility: "VISIBLE" | "REMEMBERED" | "REVEALED";
  detailsKnown: boolean;
  lastSeenTurn: number | null;
  lastSeenRevision: number | null;
  terrain: string | null;
  features: string[] | null;
  naturalWonder: string | null;
  river: boolean | null;
  land: boolean | null;
  water: boolean | null;
  hill: boolean | null;
  coast: boolean | null;
  freshwater: boolean | null;
  riverEdges: { bottomRight: boolean; bottom: boolean; bottomLeft: boolean } | null;
  resource: { name: string; amount: number; updatedTurn: number; updatedRevision: number; source: "SIGHTING" | "TECHNOLOGY" } | null;
  ownerCivId: string | null;
  city: { cityId: string; name: string; ownerCivId: string; health: number; population: number;
    /** Absent/null on legacy sightings; false is an observed non-original city. */
    isOriginalCapital?: boolean | null; foundingCivId?: string | null } | null;
  improvement: string | null;
  improvementPillaged: boolean | null;
  road: string | null;
  roadPillaged: boolean | null;
  yields: { context: "ACTOR_CITY" | "ACTOR_GENERAL"; cityId: string | null; values: Record<string, number> } | null;
  ownWork: { improvement: string; turnsRemaining: number } | null;
}
export interface MapPage {
  protocolVersion: 1;
  observationVersion: 2;
  matchId: string;
  timelineId: string;
  revision: number;
  tiles: ObservedTile[];
  nextCursor: string | null;
}
export interface RulesQuery { section?: string; name?: string; limit?: number; cursor?: string }

export interface ObservedVictoryMilestone {
  description: string;
  type: string | null;
  completed: boolean | null;
  current: number | null;
  target: number | null;
  /** Command capability only; an unsupported route can still be enabled in the saved game. */
  status: "supported" | "unsupported";
  unsupportedReason: string | null;
  remainingSpaceshipParts: Record<string, number> | null;
  partsTruncated: boolean;
}
export interface ObservedVictoryRoute {
  name: string;
  completed: boolean | null;
  completedMilestones: number;
  nextMilestone: number | null;
  milestones: ObservedVictoryMilestone[];
  milestonesTruncated: boolean;
}
export interface ObservedOriginalCapital {
  foundingCivId: string;
  cityId: string | null;
  name: string | null;
  x: number | null;
  y: number | null;
  ownerCivId: string | null;
  knowledge: "CURRENT" | "REVEALED" | "REMEMBERED" | "UNKNOWN";
  lastSeenGameTurn: number | null;
  lastSeenRevision: number | null;
}
export interface ObservedVictoryContext {
  enabledVictoryTypes: string[];
  available: ObservedVictoryRoute[];
  /** Satisfied canonical objective; actual game awarding remains an engine turn operation. */
  achieved: string | null;
  domination: {
    ownedOriginalCapitals: number;
    requiredOriginalCapitals: number | null;
    capitals: ObservedOriginalCapital[];
    capitalsTruncated: boolean;
  } | null;
  availableTruncated: boolean;
}
export interface WorkerImprovementOption {
  name: string;
  kind: "IMPROVEMENT" | "ROAD" | "REMOVE_FEATURE" | "REMOVE_IMPROVEMENT";
  turnsToBuild: number;
  replacesImprovement: string | null;
  connectsResource: boolean;
  yieldDelta: { context: "actor-own-city-tile"; yields: Record<string, number> } | null;
  /** Built at once and uses up the unit (Work Boats on a water resource); omitted otherwise. */
  consumesUnit?: boolean;
}
export interface WorkInProgress { name: string; turnsRemaining: number }
export interface RuleEntry {
  name: string;
  section: string;
  yields: Record<string, number>;
  effects: string[];
  [key: string]: unknown;
}
export interface RulesSummary { rulesetName: string; baseRuleset: string; gameVersion: string; note: string }
export type RulesResult = RulesSummary | { ruleset: string; note: string } | RulesCatalogPage;
export interface RulesCatalogPage {
  rulesetName: string;
  baseRuleset: string;
  rulesetIdentity: string;
  section: string;
  entries: RuleEntry[];
  nextCursor: string | null;
}
