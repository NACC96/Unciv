import { describe, expect, it } from "vitest";
import { SessionStore } from "../src/session-store.js";
import { GuidanceQueue } from "../src/guidance.js";
import { UsageLedger } from "../src/usage.js";
import { nextEpoch, transition } from "../src/run-controls.js";
import { auditPromptInput, buildTurnPrompt } from "../src/turn-prompt.js";

describe("SessionStore (plan §8.1)", () => {
  it("binds exact IDs and resumes only on full match", () => {
    const store = new SessionStore();
    store.bind({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "s1", lastDeliveredRevision: 42, authorityEpoch: 1 });
    expect(store.lookupForResume({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "s1" })?.sessionId).toBe("s1");
    // Wrong session ID: no resume (never "resume last").
    expect(store.lookupForResume({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "other" })).toBeUndefined();
    // Rewound timeline: no binding carries over.
    expect(store.lookupForResume({ matchId: "m", timelineId: "t0", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "s1" })).toBeUndefined();
    expect(store.hasBindingsFor("m", "t0")).toBe(false);
    // Config change needs a new binding, not a silent reuse.
    expect(store.lookupForResume({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f2", sessionId: "s1" })).toBeUndefined();
  });

  it("revokes without deleting history", () => {
    const store = new SessionStore();
    store.bind({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "s1", lastDeliveredRevision: 1, authorityEpoch: 1 });
    store.revoke("m", "t", "rome");
    expect(store.liveFor("m", "t", "rome")).toHaveLength(0);
    expect(store.lookupForResume({ matchId: "m", timelineId: "t", civId: "rome", provider: "codex", configFingerprint: "f1", sessionId: "s1" })).toBeUndefined();
  });
});

describe("GuidanceQueue (plan §8.2)", () => {
  it("delivers at boundaries with states", () => {
    const q = new GuidanceQueue();
    const first = q.queue("rome", "hold the pass");
    q.queue("rome", "actually attack");
    q.supersede("rome", first.id);
    const delivered = q.takeForDelivery("rome");
    expect(delivered.map((m) => m.state)).toEqual(["delivered"]);
    expect(delivered.map((m) => m.id)).toEqual([first.id]);
    expect(q.historyFor("rome").find((m) => m.text === "actually attack")?.state).toBe("superseded");
    expect(q.takeForDelivery("rome")).toHaveLength(0);
  });
});

describe("UsageLedger (plan §8.3)", () => {
  it("deduplicates and never fabricates cost", () => {
    const ledger = new UsageLedger();
    expect(ledger.record({ eventId: "e1", runId: "r1", civId: "rome", inputTokens: 100, outputTokens: 20, cachedTokens: 10, cost: null, durationMs: 1000 })).toBe(true);
    expect(ledger.record({ eventId: "e1", runId: "r1", civId: "rome", inputTokens: 100, outputTokens: 20, cachedTokens: 10, cost: null, durationMs: 1000 })).toBe(false);
    const totals = ledger.forLeader("rome");
    expect(totals).toMatchObject({ inputTokens: 100, outputTokens: 20, invocations: 1, partial: true });
    expect(totals.cost).toBe(0); // no fabricated dollars; partial flags the gap
    expect(ledger.forLeader("greece").inputTokens).toBeNull();
  });
});

describe("run-controls (plan §9)", () => {
  it("pauses without killing and steps without consuming reads", () => {
    expect(transition("agent_deciding", "pause")).toMatchObject({ to: "pausing" });
    expect(transition("paused", "step_action")).toMatchObject({ to: "paused" });
    expect(transition("paused", "run")).toMatchObject({ to: "starting" });
    expect(transition("finished", "run")).toBeUndefined();
    expect(transition("waiting_human", "pause")).toMatchObject({ to: "waiting_human" });
  });

  it("bumps authority epochs monotonically", () => {
    expect(nextEpoch(1)).toBe(2);
    expect(() => nextEpoch(-1)).toThrow();
  });
});

describe("turn-prompt (plan §8.1)", () => {
  const base = {
    displayName: "Ada", personality: "Bold.", rulesetName: "Civ V - Vanilla", protocolVersion: 1,
    civId: "Rome", turnSerial: 3, stateRevision: 42, notes: "Settle rivers.",
    recentEvents: ["Warrior trained"], pendingDecisions: [{ id: "d1", type: "x", summary: "pick one" }],
    guidance: ["Hold."],
  };

  it("assembles identity, notes, events, decisions, guidance", () => {
    const prompt = buildTurnPrompt(base);
    for (const needle of ["Ada", "Rome", "Turn serial 3", "state revision 42", "Settle rivers.", "Warrior trained", "d1", "Hold."]) {
      expect(prompt).toContain(needle);
    }
  });

  it("enforces the notes cap and audits forbidden content", () => {
    expect(() => buildTurnPrompt({ ...base, notes: "x".repeat(16 * 1024 + 1) })).toThrow(/summarize/);
    expect(() => auditPromptInput({ notes: "plan", recentEvents: [], guidance: [], forbiddenFingerprints: ["opponent-secret"] })).not.toThrow();
    expect(() => auditPromptInput({ notes: "leak opponent-secret here", recentEvents: [], guidance: [], forbiddenFingerprints: ["opponent-secret"] })).toThrow(/forbidden/);
  });
});
