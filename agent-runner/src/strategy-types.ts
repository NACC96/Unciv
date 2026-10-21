/** Optional additive observation fields; older engines omit them. */
export interface MilitaryForces {
  coverage: "COMPLETE" | "VISIBLE_ONLY";
  knownMilitaryUnits: number;
  /** Null for partial sightings, including when no enemy units are visible. */
  totalMilitaryUnits: number | null;
  conventionalUnits: number;
  nuclearWeapons: number;
  captureCapableUnits: number;
  rangedUnits: number;
  siegeUnits: number;
  aircraft: number;
  interceptors: number;
  damagedUnits: number;
  /** Own non-nuclear unit force only, before treasury/naval ranking multipliers. */
  conventionalUnitForce: number | null;
}

export interface MilitaryContext {
  own: MilitaryForces;
  rivals: { civId: string; forces: MilitaryForces }[];
}

export interface StrategicProgressEvent {
  kind: string;
  unitId: number | null;
  cityId: string | null;
  name: string | null;
  previousName: string | null;
  beforeNumber: number | null;
  afterNumber: number | null;
  fromX: number | null;
  fromY: number | null;
  toX: number | null;
  toY: number | null;
  isOriginalCapital: boolean | null;
  foundingCivId: string | null;
}

export interface StrategicProgressDelta {
  baselineAvailable: boolean;
  fromGameTurn: number | null;
  toGameTurn: number;
  eventCounts: Record<string, number>;
  events: StrategicProgressEvent[];
  truncated: boolean;
}

export interface StrategicProgress {
  historyComplete: boolean;
  trackedSinceGameTurn: number | null;
  trackedSinceTurnSerial: number | null;
  ownTurnOrdinal: number;
  current: {
    research: { name: string; storedScience: number; cost: number; remainingScience: number } | null;
    production: { cityId: string; name: string; construction: string; storedProduction: number; cost: number | null }[];
    productionCount: number;
    productionTruncated: boolean;
  };
  incoming: StrategicProgressDelta;
  completedTurns: {
    ownTurnOrdinal: number;
    gameTurn: number;
    turnSerial: number;
    historyComplete: boolean;
    changes: StrategicProgressDelta;
    appliedCommandCounts: Record<string, number>;
  }[];
}
