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
  requiredDecisions: string[];
  recentEvents: string[];
}

export interface IGameService {
  observe(): GameObserveResult;
  inspect(unitId: number): FakeUnit | FakeError;
  act(envelope: {
    protocolVersion: number;
    actionId: string;
    expectedRevision: number;
    type: string;
    args: Record<string, unknown>;
  }): FakeReceipt | FakeError;
  actionResult(actionId: string): FakeReceipt | { status: "pending" | "rejected"; actionId: string };
  updateNotes(notes: string, expectedNotesRevision: number): { notesRevision: number } | FakeError;
  endTurn(): { turnEnded: boolean } | FakeError;
  readonly revision: number;
  readonly notes: string;
  readonly notesRevision: number;
  /** True once game.end_turn commits. Engine receipts govern; this is the signal. */
  readonly turnEnded: boolean;
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
  notes = "";
  notesRevision = 0;
  turnEnded = false;

  observe(): { revision: number; activeTurn: boolean; requiredDecisions: string[]; recentEvents: string[] } {
    return {
      revision: this.revision,
      activeTurn: !this.turnEnded,
      requiredDecisions: [...this.decisionsPending],
      recentEvents: [`rev-${this.revision}-committed`],
    };
  }

  inspect(unitId: number): FakeUnit | FakeError {
    const unit = this.units.find((u) => u.id === unitId);
    if (!unit) {
      // Externally safe response: hidden and nonexistent are identical.
      return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such visible entity" };
    }
    return { ...unit, position: { ...unit.position } };
  }

  act(envelope: {
    protocolVersion: number;
    actionId: string;
    expectedRevision: number;
    type: string;
    args: Record<string, unknown>;
  }): FakeReceipt | FakeError {
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

  actionResult(actionId: string): FakeReceipt | { status: "pending" | "rejected"; actionId: string } {
    const prior = this.receipts.get(actionId);
    if (prior) return prior.receipt;
    if (this.mutationInFlight === actionId) return { status: "pending", actionId };
    return { status: "rejected", actionId };
  }

  updateNotes(notes: string, expectedNotesRevision: number): { notesRevision: number } | FakeError {
    if (expectedNotesRevision !== this.notesRevision)
      return { protocolVersion: 1, status: "rejected", code: "STALE_STATE", message: "notes changed; re-read" };
    this.notes = notes;
    this.notesRevision += 1;
    return { notesRevision: this.notesRevision };
  }

  endTurn(): { turnEnded: boolean } | FakeError {
    if (this.paused)
      return { protocolVersion: 1, status: "rejected", code: "MATCH_PAUSED", message: "match is paused" };
    // Never auto-choose missing decisions (plan §5.1 game.end_turn).
    if (this.decisionsPending.length > 0)
      return { protocolVersion: 1, status: "rejected", code: "DECISIONS_PENDING", message: `resolve: ${this.decisionsPending.join(",")}` };
    this.turnEnded = true;
    this.revision += 1;
    return { turnEnded: true };
  }
}
