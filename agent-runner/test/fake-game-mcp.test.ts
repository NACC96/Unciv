import { describe, expect, it } from "vitest";
import { FakeGameService } from "../src/fake-game-mcp.js";

const move = (actionId: string, expectedRevision: number, unitId = 17, destination = { x: 2, y: -1 }) => ({
  protocolVersion: 1,
  actionId,
  expectedRevision,
  type: "unit.move",
  args: { unitId, destination },
});

describe("FakeGameService game.act contract (plan §5.1)", () => {
  it("applies a legal move and bumps the revision", () => {
    const game = new FakeGameService();
    const res = game.act(move("a1", 42));
    expect(res).toMatchObject({ status: "applied", revision: 43 });
  });

  it("rejects stale revisions for fresh actionIds", () => {
    const game = new FakeGameService();
    const res = game.act(move("a1", 41));
    expect(res).toMatchObject({ status: "rejected", code: "STALE_STATE", currentRevision: 42 });
  });

  it("returns the original receipt for same-ID same-request replays", () => {
    const game = new FakeGameService();
    const first = game.act(move("a1", 42));
    // Deliberately stale revision: duplicate check must win over STALE_STATE.
    const replay = game.act(move("a1", 0));
    expect(replay).toEqual(first);
  });

  it("rejects same-ID different-payload as ACTION_ID_CONFLICT", () => {
    const game = new FakeGameService();
    game.act(move("a1", 42));
    const clash = game.act(move("a1", 43, 17, { x: 5, y: 5 }));
    expect(clash).toMatchObject({ status: "rejected", code: "ACTION_ID_CONFLICT" });
  });

  it("rejects unknown command types without dispatching", () => {
    const game = new FakeGameService();
    const res = game.act({ protocolVersion: 1, actionId: "x", expectedRevision: 42, type: "unit.teleport", args: {} });
    expect(res).toMatchObject({ status: "rejected", code: "ILLEGAL_ACTION" });
    expect(game.revision).toBe(42);
  });

  it("never auto-resolves pending decisions on end_turn", () => {
    const game = new FakeGameService();
    game.decisionsPending = ["conquest:rome"];
    expect(game.endTurn()).toMatchObject({ status: "rejected", code: "DECISIONS_PENDING" });
    expect(game.turnEnded).toBe(false);
  });

  it("pauses reject mutations with MATCH_PAUSED", () => {
    const game = new FakeGameService();
    game.paused = true;
    expect(game.act(move("a1", 42))).toMatchObject({ status: "rejected", code: "MATCH_PAUSED" });
    expect(game.endTurn()).toMatchObject({ status: "rejected", code: "MATCH_PAUSED" });
  });

  it("action_result reports applied / rejected without reapplying", () => {
    const game = new FakeGameService();
    game.act(move("a1", 42));
    expect(game.actionResult("a1")).toMatchObject({ status: "applied", revision: 43 });
    expect(game.actionResult("nope")).toMatchObject({ status: "rejected" });
    expect(game.revision).toBe(43);
  });
});
