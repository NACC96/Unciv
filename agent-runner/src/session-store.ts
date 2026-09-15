/**
 * Leader session bindings (AI_AGENT_PLAN.md §8.1).
 *
 * One binding per match timeline × leader × provider × configuration
 * fingerprint. A binding stores ONLY the provider-returned session ID —
 * never a human-readable title, never a "last session" heuristic.
 *
 * Continuity rules enforced here:
 * - Resume requires the exact recorded session ID for the same fingerprint.
 * - Provider/model/profile change, session loss, or timeline rewind starts a
 *   NEW session (continuity loss is reported, never papered over).
 * - Never import an opponent's session; never resume future context into a
 *   past game state (timeline check).
 * - Authority epochs: every takeover/return/switch bumps the epoch; stale
 *   capabilities (older epoch) are rejected by the MCP layer.
 */
export interface SessionBinding {
  matchId: string;
  timelineId: string;
  civId: string;
  provider: string;
  /** Provider/model/profile/reasoning fingerprint. Any change => new session. */
  configFingerprint: string;
  sessionId: string;
  /** Highest state revision delivered to this session. */
  lastDeliveredRevision: number;
  authorityEpoch: number;
  live: boolean;
}

export class SessionStore {
  private bindings: SessionBinding[] = [];

  private key(b: Pick<SessionBinding, "matchId" | "timelineId" | "civId" | "provider" | "configFingerprint">): string {
    return [b.matchId, b.timelineId, b.civId, b.provider, b.configFingerprint].join("\u0000");
  }

  /** Bind an exact provider-returned session ID. Throws on empty IDs. */
  bind(binding: Omit<SessionBinding, "live">): SessionBinding {
    if (!binding.sessionId) throw new Error("refusing to bind an empty session ID");
    const existing = this.bindings.find((b) => this.key(b) === this.key(binding));
    if (existing) {
      if (existing.sessionId !== binding.sessionId) {
        throw new Error("session ID changed under the same fingerprint; start a new fingerprint instead");
      }
      existing.lastDeliveredRevision = binding.lastDeliveredRevision;
      existing.authorityEpoch = binding.authorityEpoch;
      existing.live = true;
      return existing;
    }
    const fresh: SessionBinding = { ...binding, live: true };
    this.bindings.push(fresh);
    return fresh;
  }

  /** Exact resume lookup. Returns undefined when no binding matches. */
  lookupForResume(args: {
    matchId: string;
    timelineId: string;
    civId: string;
    provider: string;
    configFingerprint: string;
    sessionId: string;
  }): SessionBinding | undefined {
    return this.bindings.find(
      (b) =>
        this.key(b) === this.key(args) &&
        b.sessionId === args.sessionId &&
        b.live,
    );
  }

  /** All live bindings for a leader (at most one per fingerprint). */
  liveFor(matchId: string, timelineId: string, civId: string): SessionBinding[] {
    return this.bindings.filter(
      (b) => b.matchId === matchId && b.timelineId === timelineId && b.civId === civId && b.live,
    );
  }

  /** Revoke without deleting history (takeover/switch/match close). */
  revoke(matchId: string, timelineId: string, civId: string): void {
    for (const b of this.liveFor(matchId, timelineId, civId)) b.live = false;
  }

  /** New timeline (load older save / duplicate match): no binding carries over. */
  hasBindingsFor(matchId: string, timelineId: string): boolean {
    return this.bindings.some((b) => b.matchId === matchId && b.timelineId === timelineId && b.live);
  }
}
