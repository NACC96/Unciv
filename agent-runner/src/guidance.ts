/**
 * Guidance queue (AI_AGENT_PLAN.md §8.2).
 *
 * Guidance is a queued user message with target, ID, timestamp, and delivery
 * state. Delivered ONLY at invocation boundaries — never injected into a
 * running provider, never silently merged into a (possibly cached) system
 * prompt. Personality/profile changes are NOT guidance: they start a fresh
 * session (see SessionStore).
 */
export type GuidanceState = "queued" | "delivered" | "superseded";

export interface GuidanceMessage {
  id: string;
  targetCivId: string;
  text: string;
  createdAt: string;
  state: GuidanceState;
}

export class GuidanceQueue {
  private messages: GuidanceMessage[] = [];
  private counter = 0;

  queue(targetCivId: string, text: string, nowIso?: string): GuidanceMessage {
    const msg: GuidanceMessage = {
      id: `guidance-${++this.counter}`,
      targetCivId,
      text,
      createdAt: nowIso ?? new Date().toISOString(),
      state: "queued",
    };
    this.messages.push(msg);
    return msg;
  }

  /** Queued messages for one leader, in order. */
  pendingFor(civId: string): GuidanceMessage[] {
    return this.messages.filter((m) => m.targetCivId === civId && m.state === "queued");
  }

  /**
   * Drain pending guidance for delivery at an invocation boundary.
   * Superseded messages (replaced by newer guidance before delivery) are
   * marked, never delivered.
   */
  takeForDelivery(civId: string): GuidanceMessage[] {
    const pending = this.pendingFor(civId);
    // Keep only the newest queued message per leader per boundary would drop
    // user intent; deliver all, in order, and mark delivered atomically.
    for (const m of pending) m.state = "delivered";
    return pending;
  }

  /** A newer message supersedes older still-queued ones for the same leader. */
  supersede(civId: string, keepId: string): void {
    for (const m of this.pendingFor(civId)) {
      if (m.id !== keepId) m.state = "superseded";
    }
  }

  historyFor(civId: string): GuidanceMessage[] {
    return this.messages.filter((m) => m.targetCivId === civId);
  }
}
