/**
 * Normalized usage accounting (AI_AGENT_PLAN.md §8.3).
 *
 * - All counts nullable: subscription usage must NEVER be fabricated into
 *   dollar costs.
 * - Deduplicate by event ID before summing (provider retries re-emit).
 * - Distinguish per-invocation deltas from cumulative totals.
 */
export interface UsageDelta {
  /** Provider stream event ID (deduplication key). */
  eventId: string;
  runId: string;
  civId: string;
  inputTokens: number | null;
  outputTokens: number | null;
  cachedTokens: number | null;
  /** Provider-reported cost only; null for subscriptions/unknown. */
  cost: number | null;
  durationMs: number | null;
}

export interface UsageTotals {
  inputTokens: number | null;
  outputTokens: number | null;
  cachedTokens: number | null;
  cost: number | null;
  invocations: number;
  /** True when any contributing delta was null in that field. */
  partial: boolean;
}

const zeroTotals = (): UsageTotals => ({
  inputTokens: 0,
  outputTokens: 0,
  cachedTokens: 0,
  cost: 0,
  invocations: 0,
  partial: false,
});

export class UsageLedger {
  private seen = new Set<string>();
  private deltas: UsageDelta[] = [];

  /** Records a delta; duplicates (same eventId) are ignored. Returns acceptance. */
  record(delta: UsageDelta): boolean {
    if (this.seen.has(delta.eventId)) return false;
    this.seen.add(delta.eventId);
    this.deltas.push(delta);
    return true;
  }

  private sum(rows: UsageDelta[]): UsageTotals {
    const totals = zeroTotals();
    const runIds = new Set<string>();
    for (const d of rows) {
      runIds.add(d.runId);
      if (d.inputTokens == null || d.outputTokens == null || d.cachedTokens == null) totals.partial = true;
      totals.inputTokens = (totals.inputTokens ?? 0) + (d.inputTokens ?? 0);
      totals.outputTokens = (totals.outputTokens ?? 0) + (d.outputTokens ?? 0);
      totals.cachedTokens = (totals.cachedTokens ?? 0) + (d.cachedTokens ?? 0);
      if (d.cost == null) {
        totals.partial = true;
      } else {
        totals.cost = (totals.cost ?? 0) + d.cost;
      }
    }
    totals.invocations = runIds.size;
    if (rows.length === 0) {
      totals.inputTokens = null;
      totals.outputTokens = null;
      totals.cachedTokens = null;
      totals.cost = null;
    }
    return totals;
  }

  forRun(runId: string): UsageTotals {
    return this.sum(this.deltas.filter((d) => d.runId === runId));
  }

  forLeader(civId: string): UsageTotals {
    return this.sum(this.deltas.filter((d) => d.civId === civId));
  }

  forMatch(): UsageTotals {
    return this.sum(this.deltas);
  }
}
