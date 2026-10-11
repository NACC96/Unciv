/**
 * Engine-service seam (AI_AGENT_PLAN.md §3.1, §7).
 *
 * IGameService is the contract the MCP transport serves. The deterministic
 * fake implements it for W1 gates and supervisor-loop tests; the desktop
 * bridges the REAL coordinator (MatchCoordinator homeostasis: observe,
 * act, end_turn, notes) behind the same interface over private stdio.
 * Transports must program to this interface, never to the fake.
 */
export interface GameObserveResult {
  revision: number;
  activeTurn: boolean;
  turnEnded: boolean;
  /** Completed end-turn commits, monotonic: invocation completion signal. */
  endTurnsCommitted: number;
  requiredDecisions: string[];
  recentEvents: string[];
}

export interface IGameService {
  observe(): Promise<GameObserveResult>;
  inspect(unitId: number): Promise<FakeUnit | ObservedUnit | FakeError | null>;
  rules(): Promise<{ ruleset: string; note: string }>;
  legalActions(unitId?: number, cityId?: string): Promise<{ unitId: number; actions: unknown[] } | EngineLegalActions | FakeError>;
  act(envelope: {
    protocolVersion: number;
    actionId: string;
    expectedRevision: number;
    type: string;
    args: Record<string, unknown>;
  }): Promise<FakeReceipt | FakeError>;
  actionResult(actionId: string): Promise<FakeReceipt | { status: "pending" | "rejected"; actionId: string }>;
  readNotes(): Promise<{ notes: string; notesRevision: number }>;
  updateNotes(notes: string, expectedNotesRevision: number): Promise<{ notesRevision: number } | FakeError>;
  endTurn(): Promise<{ turnEnded: boolean } | FakeError>;
}

/** Production engine DTOs: the fake's convenience fields are not transport discriminators. */
export interface ObservedUnit {
  unitId: number;
  name: string;
  ownerCivId: string;
  x: number;
  y: number;
  health: number;
  movementLeft: number;
  action: string | null;
}

export interface EngineLegalActions {
  moveTargets: { x: number; y: number }[];
  attackTargets: unknown[];
  orders: string[];
  canFound: boolean;
  constructions: string[];
  researchable: string[];
  freeTechs: number;
  freePolicies: number;
  promotions: string[];
  canUpgrade: boolean;
  upgradeTo: string | null;
  greatActions: string[];
}

export interface FakeUnit {
  id: number;
  position: { x: number; y: number };
  movementLeft: number;
}

export interface FakeReceipt {
  protocolVersion: 1;
  actionId: string;
  status: "applied";
  revision: number;
  result: Record<string, unknown>;
  pendingDecisions: string[];
}

export interface FakeError {
  protocolVersion: 1;
  status: "rejected";
  code:
    | "STALE_STATE"
    | "NOT_YOUR_TURN"
    | "MATCH_PAUSED"
    | "AUTHORITY_REVOKED"
    | "INVALID_ARGUMENT"
    | "ILLEGAL_ACTION"
    | "DECISIONS_PENDING"
    | "ACTION_ID_CONFLICT"
    | "MUTATION_IN_PROGRESS";
  message: string;
  actionId?: string;
  currentRevision?: number;
}

export class FakeGameService implements IGameService {
  revision = 42;
  /** actionId -> {requestFingerprint, receipt} for idempotency tests. */
  private receipts = new Map<string, { fingerprint: string; receipt: FakeReceipt }>();
  /** Logical single-mutation slot (plan §5.1): true while a mutation is in flight. */
  private mutationInFlight: string | null = null;
  paused = false;
  /** When true, game.end_turn reports a mandatory decision instead of succeeding. */
  decisionsPending: string[] = [];
  units: FakeUnit[] = [{ id: 17, position: { x: 0, y: 0 }, movementLeft: 2 }];
  cities = [{ id: "city-1", constructions: ["Monument", "Warrior"] }];
  notes = "";
  notesRevision = 0;
  turnEnded = false;

  endTurnsCommitted = 0;

  async observe(): Promise<GameObserveResult> {
    return {
      revision: this.revision,
      activeTurn: !this.turnEnded,
      turnEnded: this.turnEnded,
      endTurnsCommitted: this.endTurnsCommitted,
      requiredDecisions: [...this.decisionsPending],
      recentEvents: [`rev-${this.revision}-committed`],
    };
  }

  async readNotes(): Promise<{ notes: string; notesRevision: number }> {
    return { notes: this.notes, notesRevision: this.notesRevision };
  }

  async rules(): Promise<{ ruleset: string; note: string }> {
    return { ruleset: "Civ V - Vanilla (fake stub)", note: "static rules may be cached" };
  }

  async legalActions(unitId?: number, cityId?: string): Promise<{ unitId: number; actions: unknown[] } | EngineLegalActions | FakeError> {
    if ((unitId === undefined) === (cityId === undefined))
      return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "supply exactly one unitId or cityId" };
    if (cityId !== undefined) {
      const city = this.cities.find(candidate => candidate.id === cityId);
      if (!city) return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such visible entity" };
      return { moveTargets: [], attackTargets: [], orders: [], canFound: false,
        constructions: [...city.constructions], researchable: ["Pottery"], freeTechs: 0, freePolicies: 0,
        promotions: [], canUpgrade: false, upgradeTo: null, greatActions: [] };
    }
    const seen = this.units.find((u) => u.id === unitId);
    if (!seen) {
      return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such visible entity" };
    }
    return { unitId: seen.id, actions: [{ type: "unit.move", costs: { movement: 1 }, targets: "adjacent-explored-tiles" }] };
  }

  async inspect(unitId: number): Promise<FakeUnit | FakeError> {
    const unit = this.units.find((u) => u.id === unitId);
    if (!unit) {
      // Externally safe response: hidden and nonexistent are identical.
      return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such visible entity" };
    }
    return { ...unit, position: { ...unit.position } };
  }

  async act(envelope: {
    protocolVersion: number;
    actionId: string;
    expectedRevision: number;
    type: string;
    args: Record<string, unknown>;
  }): Promise<FakeReceipt | FakeError> {
    if (envelope.protocolVersion !== 1)
      return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "unsupported protocolVersion" };
    if (this.paused)
      return { protocolVersion: 1, status: "rejected", code: "MATCH_PAUSED", message: "match is paused", actionId: envelope.actionId };
    // Duplicate actionId precedes stale-revision rejection within receipt scope (plan §5.1).
    const prior = this.receipts.get(envelope.actionId);
    const fingerprint = JSON.stringify([envelope.type, envelope.args]);
    if (prior) {
      if (prior.fingerprint === fingerprint) return prior.receipt;
      return { protocolVersion: 1, status: "rejected", code: "ACTION_ID_CONFLICT", message: "actionId reused with different request", actionId: envelope.actionId };
    }
    if (envelope.expectedRevision !== this.revision)
      return { protocolVersion: 1, status: "rejected", code: "STALE_STATE", message: "re-observe and retry", actionId: envelope.actionId, currentRevision: this.revision };
    if (this.mutationInFlight !== null)
      return { protocolVersion: 1, status: "rejected", code: "MUTATION_IN_PROGRESS", message: "one mutation in flight", actionId: envelope.actionId };
    if (envelope.type !== "unit.move")
      return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: `unknown command type ${envelope.type}`, actionId: envelope.actionId };

    this.mutationInFlight = envelope.actionId;
    try {
      const unit = this.units.find((u) => u.id === (envelope.args["unitId"] as number));
      const dest = envelope.args["destination"] as { x: number; y: number } | undefined;
      if (!unit || !dest || unit.movementLeft < 1) {
        return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "unit cannot move there", actionId: envelope.actionId };
      }
      unit.position = { ...dest };
      unit.movementLeft -= 1;
      this.revision += 1;
      const receipt: FakeReceipt = {
        protocolVersion: 1,
        actionId: envelope.actionId,
        status: "applied",
        revision: this.revision,
        result: { unitId: unit.id, position: { ...unit.position } },
        pendingDecisions: [...this.decisionsPending],
      };
      this.receipts.set(envelope.actionId, { fingerprint, receipt });
      return receipt;
    } finally {
      if (this.mutationInFlight === envelope.actionId) this.mutationInFlight = null;
    }
  }

  async actionResult(actionId: string): Promise<FakeReceipt | { status: "pending" | "rejected"; actionId: string }> {
    const prior = this.receipts.get(actionId);
    if (prior) return prior.receipt;
    if (this.mutationInFlight === actionId) return { status: "pending", actionId };
    return { status: "rejected", actionId };
  }

  async updateNotes(notes: string, expectedNotesRevision: number): Promise<{ notesRevision: number } | FakeError> {
    if (expectedNotesRevision !== this.notesRevision)
      return { protocolVersion: 1, status: "rejected", code: "STALE_STATE", message: "notes changed; re-read" };
    this.notes = notes;
    this.notesRevision += 1;
    return { notesRevision: this.notesRevision };
  }

  async endTurn(): Promise<{ turnEnded: boolean } | FakeError> {
    if (this.paused)
      return { protocolVersion: 1, status: "rejected", code: "MATCH_PAUSED", message: "match is paused" };
    // Never auto-choose missing decisions (plan §5.1 game.end_turn).
    if (this.decisionsPending.length > 0)
      return { protocolVersion: 1, status: "rejected", code: "DECISIONS_PENDING", message: `resolve: ${this.decisionsPending.join(",")}` };
    this.turnEnded = true;
    this.endTurnsCommitted += 1;
    this.revision += 1;
    return { turnEnded: true };
  }
}
