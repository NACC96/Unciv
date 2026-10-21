import type { HexPosition, MatchVision, MapQuery, MapPage, MapSummary, ObservedTile, ObservedVictoryContext, RulesQuery, RulesResult, WorkerImprovementOption, WorkInProgress } from "./map-types.js";

/**
 * Engine-service seam (AI_AGENT_PLAN.md §3.1, §7).
 *
 * IGameService is the contract the MCP transport serves. The deterministic
 * fake implements it for W1 gates and supervisor-loop tests; the desktop
 * bridges the REAL coordinator (MatchCoordinator homeostasis: observe,
 * act, end_turn, notes) behind the same interface over private stdio.
 * Transports must program to this interface, never to the fake.
 */
export interface DiplomaticImageAttachment {
  attachmentId: string;
  mimeType: string;
  width: number;
  height: number;
  byteLength: number;
}

/** Encoded bytes exist only on the dedicated authorized image-read seam. */
export interface MessageImageResult {
  attachmentId: string;
  mimeType: string;
  width: number;
  height: number;
  data: string;
}

export interface DiplomaticMessage {
  messageId: string;
  senderCivId: string;
  targetCivId: string;
  text: string;
  sentTurnSerial: number;
  deliveredTurnSerial: number | null;
  origin: "NATION" | "SPECTATOR";
  /** Absent legacy fields are equivalent to null (not acknowledged). */
  acknowledgedTurnSerial?: number | null;
  acknowledgementText?: string | null;
  acknowledgedRevision?: number | null;
  attachments?: DiplomaticImageAttachment[];
}

/** Project the wire contract, never arbitrary fields or encoded bytes. */
export function messageMetadata(message: DiplomaticMessage): DiplomaticMessage {
  return {
    messageId: message.messageId, senderCivId: message.senderCivId, targetCivId: message.targetCivId,
    text: message.text, sentTurnSerial: message.sentTurnSerial, deliveredTurnSerial: message.deliveredTurnSerial,
    origin: message.origin, acknowledgedTurnSerial: message.acknowledgedTurnSerial,
    acknowledgementText: message.acknowledgementText, acknowledgedRevision: message.acknowledgedRevision,
    ...(message.attachments ? { attachments: message.attachments.map(a => ({
      attachmentId: a.attachmentId, mimeType: a.mimeType, width: a.width, height: a.height, byteLength: a.byteLength,
    })) } : {}),
  };
}

/** Own sent mail and delivered incoming mail only, newest response/message first. */
export function privateCorrespondence(messages: DiplomaticMessage[], civId: string, turnSerial: number): DiplomaticMessage[] {
  return messages.filter(m => m.sentTurnSerial <= turnSerial && (m.senderCivId === civId ||
    (m.targetCivId === civId && m.deliveredTurnSerial != null && m.deliveredTurnSerial <= turnSerial)))
    .map((message, index) => ({ message, index }))
    .sort((a, b) => Math.max(b.message.sentTurnSerial, b.message.deliveredTurnSerial ?? -1, b.message.acknowledgedTurnSerial ?? -1) -
      Math.max(a.message.sentTurnSerial, a.message.deliveredTurnSerial ?? -1, a.message.acknowledgedTurnSerial ?? -1) ||
      (b.message.acknowledgedRevision ?? -1) - (a.message.acknowledgedRevision ?? -1) || a.index - b.index)
    .slice(0, 12).map(({ message }) => messageMetadata(message));
}

export interface MessageContact {
  civId: string;
  displayName: string;
  isAgent: boolean;
}

export interface ObservedTradeOffer {
  name: string;
  type: string;
  amount: number;
  /** -1 means an immediate transfer; positive values are turns. */
  duration: number;
}

export interface ObservedTradeRequest {
  requestingCivId: string;
  /** Includes this first actionable request; re-observe after responding. */
  pendingRequestsFromCiv: number;
  /** The observing recipient's commitments. */
  ourOffers: ObservedTradeOffer[];
  /** The requesting civilization's commitments. */
  theirOffers: ObservedTradeOffer[];
}

export interface GameObserveResult {
  observationVersion?: 2;
  /** Actual engine round; independent of turnSerial and roster size. Absent on older engines. */
  gameTurn?: number;
  turnSerial?: number;
  victory?: ObservedVictoryContext | null;
  mapSummary?: MapSummary;
  revision: number;
  activeTurn: boolean;
  turnEnded: boolean;
  /** Completed end-turn commits, monotonic: invocation completion signal. */
  endTurnsCommitted: number;
  requiredDecisions: (string | {
    decisionId: string; decisionType: string; sourceRef: string;
    classification: string; options: Record<string, string>[];
  })[];
  recentEvents: string[];
  /** Current delivery and earlier unacknowledged own mail; reads never consume it. */
  inbox?: DiplomaticMessage[];
  /** Met, living human/agent nations eligible for diplomatic mail. */
  messageContacts?: MessageContact[];
  /** Bounded private correspondence, including replies stored on own sent mail. */
  diplomaticHistory?: DiplomaticMessage[];
  /** Recipient-private, first pending request per sender in native queue order. */
  pendingTradeRequests?: ObservedTradeRequest[];
}

export interface IGameService {
  observe(): Promise<GameObserveResult>;
  messageImage(messageId: string, attachmentId: string): Promise<MessageImageResult | FakeError>;
  inspect(unitId: number): Promise<FakeUnit | ObservedUnit | FakeError | null>;
  /** Own cities only; foreign, hidden and nonexistent cities share the safe null/rejection. */
  inspectCity(cityId: string): Promise<ObservedCityDetail | FakeError | null>;
  inspectTile(position: HexPosition): Promise<ObservedTile | FakeError | null>;
  map(query?: MapQuery): Promise<MapPage | FakeError>;
  rules(query?: RulesQuery): Promise<RulesResult | FakeError>;
  legalActions(unitId?: number, cityId?: string): Promise<LegalActionsResult | FakeError>;
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

/** Own-city detail from the desktop's inspect_city; transports pass it through. */
export interface ObservedCityDetail {
  cityId: string; name: string; x: number; y: number; population: number;
  [field: string]: unknown;
}

/** Production desktop DTOs; transports must not assume the fake's field names. */
export interface ObservedUnit {
  unitId: number; name: string; ownerCivId: string; x: number; y: number;
  health: number; movementLeft: number; action: string | null;
  tile?: ObservedTile;
}
export type LegalActionsResult = { unitId: number; actions: unknown[] } | {
  moveTargets: { x: number; y: number }[]; attackTargets: unknown[];
  orders: string[]; canFound: boolean; constructions: string[]; researchable: string[];
  freeTechs: number; freePolicies: number; promotions: string[];
  canUpgrade: boolean; upgradeTo: string | null; greatActions: string[];
  revision?: number;
  improvements?: WorkerImprovementOption[];
  workInProgress?: WorkInProgress | null;
  repair?: { turnsToRepair: number } | null;
  canCancelWork?: boolean;
};

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
    | "MESSAGES_UNACKNOWLEDGED"
    | "ACTION_ID_CONFLICT"
    | "MUTATION_IN_PROGRESS";
  message: string;
  actionId?: string;
  currentRevision?: number;
  messageIds?: string[];
}

export class FakeGameService implements IGameService {
  revision = 42;
  vision: MatchVision = "FOG";
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
  civId = "Rome";
  turnSerial = 3;
  messages: DiplomaticMessage[] = [];
  messageImages = new Map<string, MessageImageResult>();

  async observe(): Promise<GameObserveResult> {
    return {
      observationVersion: 2,
      mapSummary: { observationVersion: 2, coordinateSystem: "UNCIV_HEX", knownTileCount: this.tiles.length,
        visibleTileCount: this.tiles.filter(t => t.visibility === "VISIBLE").length, worldWrap: false, vision: this.vision },
      revision: this.revision,
      activeTurn: !this.turnEnded,
      turnEnded: this.turnEnded,
      endTurnsCommitted: this.endTurnsCommitted,
      requiredDecisions: [...this.decisionsPending],
      recentEvents: [`rev-${this.revision}-committed`],
      diplomaticHistory: privateCorrespondence(this.messages, this.civId, this.turnSerial),
      inbox: this.turnEnded ? [] : this.messages.filter(message =>
        message.targetCivId === this.civId && message.deliveredTurnSerial != null && message.deliveredTurnSerial <= this.turnSerial &&
        (message.deliveredTurnSerial === this.turnSerial || message.acknowledgedTurnSerial == null)).map(messageMetadata),
    };
  }

  async messageImage(messageId: string, attachmentId: string): Promise<MessageImageResult | FakeError> {
    const message = this.messages.find(m => m.messageId === messageId && m.targetCivId === this.civId &&
      m.deliveredTurnSerial != null && m.deliveredTurnSerial <= this.turnSerial &&
      m.attachments?.some(a => a.attachmentId === attachmentId));
    const image = message ? this.messageImages.get(attachmentId) : undefined;
    return image ? { ...image } : { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such delivered attachment" };
  }

  async readNotes(): Promise<{ notes: string; notesRevision: number }> {
    return { notes: this.notes, notesRevision: this.notesRevision };
  }

  tiles: ObservedTile[] = [{ x: 0, y: 0, visibility: "VISIBLE", detailsKnown: true,
    lastSeenTurn: 0, lastSeenRevision: 42, terrain: "Grassland", features: [], naturalWonder: null,
    river: false, land: true, water: false, hill: false, coast: false, freshwater: false,
    riverEdges: { bottomRight: false, bottom: false, bottomLeft: false }, resource: null, ownerCivId: null, city: null, improvement: null,
    improvementPillaged: false, road: null, roadPillaged: false,
    yields: { context: "ACTOR_GENERAL", cityId: null, values: { Food: 2 } }, ownWork: null }];

  async inspectTile(position: HexPosition): Promise<ObservedTile | null> {
    return structuredClone(this.tiles.find(t => t.x === position.x && t.y === position.y) ?? null);
  }

  async map(query: MapQuery = {}): Promise<MapPage | FakeError> {
    const limit = query.limit ?? 64;
    if (!Number.isInteger(limit) || limit < 1 || limit > 128 ||
        (query.mode !== undefined && query.mode !== "KNOWN" && query.mode !== "VISIBLE") ||
        (query.radius !== undefined && (!Number.isInteger(query.radius) || query.radius < 0 || query.radius > 8)) ||
        ((query.center === undefined) !== (query.radius === undefined)) ||
        (query.center !== undefined && (!Number.isInteger(query.center.x) || !Number.isInteger(query.center.y))))
      return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "invalid map query" };
    const key = JSON.stringify([this.vision, query.mode ?? "KNOWN", query.center ?? null, query.radius ?? null, limit]);
    let offset = 0;
    if (query.cursor !== undefined) {
      try {
        const cursor = JSON.parse(Buffer.from(query.cursor, "base64url").toString());
        if (cursor.key !== key || !Number.isInteger(cursor.offset) || cursor.offset < 0) throw Error();
        if (cursor.revision !== this.revision)
          return { protocolVersion: 1, status: "rejected", code: "STALE_STATE", message: "re-observe and start a new map query" };
        offset = cursor.offset;
      } catch {
        return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "invalid map cursor" };
      }
    }
    const tiles = this.tiles.filter(t => (query.mode !== "VISIBLE" || t.visibility === "VISIBLE") &&
      (!query.center || Math.max(Math.abs(t.x - query.center.x), Math.abs(t.y - query.center.y), Math.abs(t.x - query.center.x - t.y + query.center.y)) <= query.radius!))
      .sort((a, b) => a.x - b.x || a.y - b.y);
    const page: MapPage = { protocolVersion: 1, observationVersion: 2, matchId: "fake", timelineId: "fake", revision: this.revision, tiles: [], nextCursor: null };
    for (let i = offset; i < tiles.length && i < offset + limit; i++) {
      const nextCursor = i + 1 < tiles.length ? Buffer.from(JSON.stringify({ key, revision: this.revision, offset: i + 1 })).toString("base64url") : null;
      const candidate = { ...page, tiles: [...page.tiles, tiles[i]!], nextCursor };
      if (Buffer.byteLength(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(candidate) }] }), "utf8") + 1024 > 96 * 1024) {
        if (!page.tiles.length) return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "tile exceeds response byte budget" };
        break;
      }
      page.tiles = candidate.tiles;
      page.nextCursor = nextCursor;
    }
    return structuredClone(page);
  }

  async rules(query: RulesQuery = {}): Promise<RulesResult | FakeError> {
    const ruleset = "Civ V - Vanilla (fake stub)";
    if (!query.section) {
      if (query.name || query.cursor) return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "rules name/cursor requires a section" };
      return { ruleset, note: "static rules may be cached" };
    }
    if (!["improvements", "terrains", "resources"].includes(query.section) ||
        (query.limit !== undefined && (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 128)) || query.cursor)
      return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "invalid rules query" };
    const entries = query.section === "improvements" ? [{ name: "Farm", section: "improvements", techRequired: null, terrainsCanBeBuiltOn: ["Grassland", "Plains"], effects: [], yields: { Food: 1 }, maintenance: [], turnsToBuild: 6, uniqueTo: null, replaces: null }] : [];
    return { rulesetName: ruleset, baseRuleset: "Civ V - Vanilla", rulesetIdentity: "fake", section: query.section, entries: entries.filter(e => !query.name || e.name === query.name), nextCursor: null };
  }

  async legalActions(unitId?: number, cityId?: string): Promise<LegalActionsResult | FakeError> {
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

  async inspectCity(cityId: string): Promise<ObservedCityDetail | FakeError> {
    const city = this.cities.find(candidate => candidate.id === cityId);
    if (!city) return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no such visible entity" };
    return { cityId: city.id, name: city.id, x: 0, y: 0, population: 1, currentConstruction: null, queue: [...city.constructions] };
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
    if (envelope.type !== "unit.move" && envelope.type !== "diplomacy.acknowledgeMessage")
      return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: `unknown command type ${envelope.type}`, actionId: envelope.actionId };

    this.mutationInFlight = envelope.actionId;
    try {
      if (envelope.type === "diplomacy.acknowledgeMessage") {
        const messageId = envelope.args["messageId"];
        const text = envelope.args["text"];
        if (typeof messageId !== "string" || !messageId.trim() || messageId.length > 128 ||
            typeof text !== "string" || !text.trim() || text.length > 2000 || Buffer.byteLength(text, "utf8") > 8192)
          return { protocolVersion: 1, status: "rejected", code: "INVALID_ARGUMENT", message: "supply a messageId and concise nonblank acknowledgement text", actionId: envelope.actionId };
        const message = this.messages.find(m => m.messageId === messageId && m.targetCivId === this.civId && m.deliveredTurnSerial != null && m.deliveredTurnSerial <= this.turnSerial);
        if (this.turnEnded || !message)
          return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "no delivered incoming message to acknowledge", actionId: envelope.actionId };
        if (message.acknowledgedTurnSerial != null)
          return { protocolVersion: 1, status: "rejected", code: "ILLEGAL_ACTION", message: "message already acknowledged", actionId: envelope.actionId };
        message.acknowledgedTurnSerial = this.turnSerial;
        message.acknowledgementText = text.trim();
        this.revision += 1;
        message.acknowledgedRevision = this.revision;
        const receipt: FakeReceipt = { protocolVersion: 1, actionId: envelope.actionId, status: "applied", revision: this.revision,
          result: structuredClone(message) as unknown as Record<string, unknown>, pendingDecisions: [...this.decisionsPending] };
        this.receipts.set(envelope.actionId, { fingerprint, receipt });
        return structuredClone(receipt);
      }
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
    const messageIds = this.messages.filter(m => m.targetCivId === this.civId && m.deliveredTurnSerial != null && m.deliveredTurnSerial <= this.turnSerial && m.acknowledgedTurnSerial == null).map(m => m.messageId);
    if (messageIds.length)
      return { protocolVersion: 1, status: "rejected", code: "MESSAGES_UNACKNOWLEDGED", message: `Acknowledge incoming messages before ending the turn: ${messageIds.join(", ")}`, messageIds };
    // Never auto-choose missing decisions (plan §5.1 game.end_turn).
    if (this.decisionsPending.length > 0)
      return { protocolVersion: 1, status: "rejected", code: "DECISIONS_PENDING", message: `resolve: ${this.decisionsPending.join(",")}` };
    this.turnEnded = true;
    this.endTurnsCommitted += 1;
    this.revision += 1;
    return { turnEnded: true };
  }
}
