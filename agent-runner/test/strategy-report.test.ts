import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";

const script = fileURLToPath(new URL("../scripts/strategy-report.mjs", import.meta.url));
const directories: string[] = [];
function fixture(events: unknown[] = []) {
  const directory = mkdtempSync(join(tmpdir(), "unciv-strategy-report-"));
  directories.push(directory);
  writeFileSync(join(directory, "host-events.jsonl"), events.map((event) => JSON.stringify(event)).join("\n"));
  return {
    directory,
    provider(name: string, meta: object, events: unknown[]) {
      const path = join(directory, "providers", name);
      mkdirSync(path, { recursive: true });
      writeFileSync(join(path, "meta.json"), JSON.stringify(meta));
      writeFileSync(join(path, "events.jsonl"), events.map((event) => JSON.stringify(event)).join("\n"));
    },
    report: (baseline?: string) => JSON.parse(execFileSync(process.execPath, [script, directory, ...baseline ? [baseline] : []], { encoding: "utf8" })),
  };
}
const call = (callId: string, type: string, actionId: string, extra: object = {}) => ({
  event: "engine_call_completed", callId, runId: "r1", actor: "Rome", timelineId: "t1", method: "act", ok: true,
  params: { type, actionId, args: {} }, result: { actionId, status: "applied" }, ...extra,
});
afterEach(() => { for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true }); });

describe("strategy evaluation report", () => {
  it("counts only unique applied receipts and separates acknowledgements from new messages", () => {
    const events = [
      { event: "engine_call_started", callId: "1", method: "act" },
      call("1", "diplomacy.message", "mail"),
      call("2", "diplomacy.message", "mail"), // replayed receipt
      call("3", "diplomacy.acknowledgeMessage", "ack"),
      call("4", "unit.attack", "rejected", { result: { status: "rejected" } }),
      call("5", "unit.attack", "failed", { ok: false, error: { code: "ILLEGAL_ACTION" } }),
      call("6", "unit.attack", "skipped", { result: { status: "skipped" } }),
      { event: "turn_completed", runId: "r1", status: "completed_turn", endTurnsCommittedDelta: 1, timeToCommitMs: 1200 },
      { event: "turn_completed", runId: "r1", status: "completed_turn", endTurnsCommittedDelta: 1, timeToCommitMs: 1200 },
    ];
    const report = fixture(events).report();
    expect(report.turns.completed).toBe(1);
    expect(report.commands.applied).toBe(2);
    expect(report.commands.outgoingMessages).toBe(1);
    expect(report.commands.acknowledgements).toBe(1);
    expect(report.commands.attacks).toBe(0);
    expect(report.exclusions.replayedReceipts).toBe(1);
    expect(report.exclusions.failedCalls).toBe(1);
    expect(report.exclusions.nonAppliedReceipts).toBe(2);
  });

  it("requires matching relevant state for unchanged reads and orders", () => {
    const inspect = (id: string, health: number) => ({ event: "engine_call_completed", callId: id, runId: "r1", actor: "Rome",
      method: "inspect", ok: true, params: { unitId: 7 }, result: { revision: Number(id), unitId: 7, health, movementLeft: 2 } });
    const order = (id: string, health: number) => call(id, "unit.order", id, {
      params: { type: "unit.order", args: { unitId: 7, order: "SLEEP" } },
      relevantState: { unitId: 7, health, action: "Sleep", threats: [] },
    });
    const report = fixture([inspect("1", 100), inspect("2", 100), inspect("3", 80), order("4", 80), order("5", 80), order("6", 70)]).report();
    expect(report.repetition.unchangedReads).toBe(1);
    expect(report.repetition.unchangedOrders).toBe(1);
    expect(report.repetition.unknownOrderState).toBe(0);
  });

  it("ignores fresh live sighting clocks while preserving real facts and remembered freshness", () => {
    const state = (revision: number, visibility = "VISIBLE") => ({
      unitId: 7, name: "Worker", health: 100, movementLeft: 2, action: "Sleep", x: 1, y: 0,
      tile: { x: 1, y: 0, visibility, detailsKnown: true, lastSeenTurn: revision, lastSeenRevision: revision,
        terrain: "Grassland", features: [], ownerCivId: "Rome", improvement: "Plantation",
        resource: { name: "Silk", amount: 1, updatedTurn: revision, updatedRevision: revision, source: "SIGHTING" },
        city: null, improvementPillaged: false, road: "None", roadPillaged: false,
        yields: { context: "ACTOR_CITY", cityId: "Rome", values: { Food: 2, Gold: 3 } }, ownWork: null,
        land: true, water: false, hill: false, coast: false, freshwater: false,
        riverEdges: { bottomRight: false, bottom: false, bottomLeft: false } },
    });
    const inspect = (id: string, result: object) => ({ event: "engine_call_completed", callId: id, runId: "r1", actor: "Rome",
      method: "inspect", ok: true, params: { unitId: 7 }, result });
    const order = (id: string, relevantState: object) => call(id, "unit.order", id, {
      params: { type: "unit.order", args: { unitId: 7, order: "SLEEP" } }, relevantState,
    });
    for (const visibility of ["VISIBLE", "REVEALED"]) {
      const first = state(1, visibility), next = state(2, visibility);
      const report = fixture([inspect("1", first), inspect("2", next), order("3", first), order("4", next)]).report();
      expect(report.repetition.unchangedReads).toBe(1);
      expect(report.repetition.unchangedOrders).toBe(1);
    }
    const remembered = state(2, "REMEMBERED");
    const hurt = state(3); hurt.health = 80;
    const terrain = state(4); terrain.tile.terrain = "Plains";
    for (const changed of [remembered, hurt, terrain])
      expect(fixture([inspect("1", state(1)), inspect("2", changed)]).report().repetition.unchangedReads).toBe(0);
    expect(fixture([inspect("1", state(1, "REMEMBERED")), inspect("2", remembered)]).report().repetition.unchangedReads).toBe(0);
  });

  it("does not score missing order evidence as a reduction", () => {
    const order = (id: string, extra: object = {}) => call(id, "unit.order", id, {
      params: { type: "unit.order", args: { unitId: 7, order: "SLEEP" } }, ...extra,
    });
    const baseline = fixture([order("1", { relevantState: { health: 100 } }), order("2", { relevantState: { health: 100 } })]);
    const unknown = fixture([order("1"), order("2")]).report(baseline.directory);
    expect(unknown.repetition.unknownOrderState).toBe(2);
    expect(unknown.comparison.unchangedOrdersReduction).toBeNull();
    expect(fixture([order("1", { relevantState: { health: 100 } }), order("2", { relevantState: { health: 80 } })])
      .report(baseline.directory).comparison.unchangedOrdersReduction).toBe(1);
  });

  it.each(["truncated batch", "unknown command mapping", "unfinished tool", "malformed stream"])(
    "keeps incomplete legacy command evidence unknown: %s", failure => {
      const baseline = fixture([call("1", "diplomacy.message", "mail"),
        { event: "turn_completed", runId: "r1", status: "completed_turn", endTurnsCommittedDelta: 1 }]);
      const candidate = fixture();
      const item = { id: "batch", type: "mcp_tool_call", tool: "game.act_batch",
        arguments: { actions: [{ actionId: "mail", type: "diplomacy.message", args: { targetCivId: "Greece", text: "Offer" } }] },
        result: { content: [{ type: "text", text: failure === "truncated batch" ? '{"receipts":[' :
          JSON.stringify({ status: "applied", receipts: [{ actionId: "mail", status: "applied" }] }) }] } };
      if (failure === "unknown command mapping") item.arguments.actions = [];
      const events = [{ type: "item.started", item }, ...failure === "unfinished tool" ? [] : [{ type: "item.completed", item }]];
      candidate.provider("run", { runId: "r1", provider: "codex", civId: "Rome", turnOutcome: "completed_turn",
        startedAt: "2026-01-01T00:00:00Z", endedAt: "2026-01-01T00:00:10Z" }, [
          { type: "item.completed", item: { id: "observe", type: "mcp_tool_call", tool: "game.observe", arguments: {},
            result: { structuredContent: { revision: 0 } } } }, ...events,
        ]);
      if (failure === "malformed stream") writeFileSync(join(candidate.directory, "providers/run/events.jsonl"),
        events.map(event => JSON.stringify(event)).join("\n") + '\n{"type":"item.completed"');
      const report = candidate.report(baseline.directory);
      expect(report.coverage.completeCommandCoverage).toBe(false);
      expect(report.commands.outgoingMessagesPerCompletedTurn).toBeNull();
      expect(report.comparison.outgoingMessagesPerTurnReduction).toBeNull();
    });

  it.each(["applied", "partial"])("retains complete legacy batch evidence after an explicit %s outcome", status => {
    const candidate = fixture();
    const actions = [{ actionId: "mail", type: "diplomacy.message", args: { targetCivId: "Greece", text: "Offer" } },
      { actionId: "order", type: "unit.order", args: { unitId: 7, order: "SLEEP" } }];
    const receipts = [{ actionId: "mail", status: "applied" }, ...status === "applied" ? [{ actionId: "order", status: "applied" }] : []];
    candidate.provider("run", { runId: "r1", provider: "codex", civId: "Rome", turnOutcome: "completed_turn",
      startedAt: "2026-01-01T00:00:00Z", endedAt: "2026-01-01T00:00:10Z" }, [
      { type: "item.completed", item: { id: "batch", type: "mcp_tool_call", tool: "game.act_batch", arguments: { actions },
        result: { structuredContent: { status, receipts, ...status === "partial" ?
          { rejected: { index: 1, code: "ILLEGAL_ACTION" }, skipped: [] } : {} } } } },
    ]);
    const report = candidate.report();
    expect(report.coverage.completeCommandCoverage).toBe(true);
    expect(report.commands.applied).toBe(status === "applied" ? 2 : 1);
    expect(report.commands.outgoingMessagesPerCompletedTurn).toBe(1);
  });

  it("uses actual checkpoint capital identities and victory data, not captures or completion claims", () => {
    const snapshot = (index: number, owner: string, victoryData: unknown = null) => ({ event: "snapshot", index,
      facts: { gameRound: index, turnPhase: "COMPLETED", victoryData, capitals: [
        { cityId: "Athens", foundingCivId: "Greece", ownerCivId: owner, major: true },
        { cityId: "Geneva", foundingCivId: "Geneva", ownerCivId: owner, major: false },
      ] } });
    const report = fixture([snapshot(0, "Greece"), call("1", "conquest.resolve", "capture"),
      { event: "provider_summary", text: "I won!" }, snapshot(1, "Rome")]).report();
    expect(report.outcome.originalCapitalOwnershipChanges).toEqual([
      { cityId: "Athens", foundingCivId: "Greece", from: "Greece", to: "Rome", gameRound: 1 },
    ]);
    expect(report.outcome.winner).toBeNull();
    expect(report.outcome.winRateVerdict).toBe("inconclusive");
    const won = fixture([snapshot(0, "Greece"), snapshot(1, "Rome", { winningCiv: "Rome", victoryType: "Domination", victoryTurn: 1 })]).report();
    expect(won.outcome.winner).toBe("Rome");
  });

  it("takes the final cumulative token total per attempt, including failures in session baselines", () => {
    const f = fixture();
    const meta = { provider: "codex", sessionId: "s", turnOutcome: "completed_turn", endedAt: "2026-01-01T00:00:10Z" };
    f.provider("run-1", { ...meta, startedAt: "2026-01-01T00:00:00Z", resumed: false }, [
      { type: "turn.completed", usage: { input_tokens: 90, output_tokens: 10 } },
      { type: "turn.completed", usage: { input_tokens: 100, output_tokens: 20 } },
    ]);
    f.provider("run-2", { ...meta, startedAt: "2026-01-01T00:01:00Z", resumed: true, turnOutcome: "failed" }, [
      { type: "turn.completed", usage: { input_tokens: 150, output_tokens: 30 } },
    ]);
    f.provider("run-3", { ...meta, startedAt: "2026-01-01T00:02:00Z", resumed: true }, [
      { type: "turn.completed", usage: { input_tokens: 180, output_tokens: 40 } },
    ]);
    expect(f.report().tokens.completedTurns).toMatchObject({ input: { total: 130, samples: 2 }, output: { total: 30, samples: 2 } });
  });

  it("keeps inherited fork usage unknown and counts only later deltas on the new session", () => {
    const f = fixture();
    const meta = { provider: "codex", sessionId: "new-fork", turnOutcome: "completed_turn", endedAt: "2026-01-01T00:00:10Z" };
    f.provider("fork", { ...meta, startedAt: "2026-01-01T00:00:00Z", resumed: false, forkedFromSessionId: "live-source" }, [
      { type: "turn.completed", usage: { input_tokens: 900100, output_tokens: 10020 } },
    ]);
    expect(f.report().tokens.completedTurns).toMatchObject({ input: { total: null, samples: 0 }, output: { total: null, samples: 0 } });
    f.provider("resume", { ...meta, startedAt: "2026-01-01T00:01:00Z", resumed: true }, [
      { type: "turn.completed", usage: { input_tokens: 900150, output_tokens: 10030 } },
    ]);
    expect(f.report().tokens.completedTurns).toMatchObject({ input: { total: 50, samples: 1 }, output: { total: 10, samples: 1 } });
  });

  it("marks absent evidence unknown and accepts only completed provider tool calls for legacy logs", () => {
    const f = fixture();
    const item = { id: "tool-1", type: "mcp_tool_call", tool: "game.act_batch", arguments: {}, result: { content: [
      { type: "text", text: JSON.stringify({ results: [
        { actionId: "a1", status: "applied", type: "diplomacy.message" },
        { actionId: "a2", status: "skipped", type: "unit.attack" },
      ] }) },
    ] } };
    f.provider("run-1", { provider: "codex", civId: "Rome", turnOutcome: "completed_turn", startedAt: "2026-01-01T00:00:00Z", endedAt: "2026-01-01T00:00:10Z" }, [
      { type: "item.started", item }, { type: "item.completed", item }, { type: "item.completed", item },
    ]);
    const report = f.report();
    expect(report.commands.applied).toBe(1);
    expect(report.timing.medianTimeToCommitMs).toBeNull();
    expect(report.outcome.winner).toBeNull();
    expect(report.limitations.join(" ")).toContain("unknown");
  });
});
