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
    report: () => JSON.parse(execFileSync(process.execPath, [script, directory], { encoding: "utf8" })),
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
