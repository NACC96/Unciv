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
  it("applies a legal move and bumps the revision", async () => {
    const game = new FakeGameService();
    const res = await game.act(move("a1", 42));
    expect(res).toMatchObject({ status: "applied", revision: 43 });
  });

  it("rejects stale revisions for fresh actionIds", async () => {
    const game = new FakeGameService();
    const res = await game.act(move("a1", 41));
    expect(res).toMatchObject({ status: "rejected", code: "STALE_STATE", currentRevision: 42 });
  });

  it("returns the original receipt for same-ID same-request replays", async () => {
    const game = new FakeGameService();
    const first = await game.act(move("a1", 42));
    // Deliberately stale revision: duplicate check must win over STALE_STATE.
    const replay = await game.act(move("a1", 0));
    expect(replay).toEqual(first);
  });

  it("rejects same-ID different-payload as ACTION_ID_CONFLICT", async () => {
    const game = new FakeGameService();
    await game.act(move("a1", 42));
    const clash = await game.act(move("a1", 43, 17, { x: 5, y: 5 }));
    expect(clash).toMatchObject({ status: "rejected", code: "ACTION_ID_CONFLICT" });
  });

  it("rejects unknown command types without dispatching", async () => {
    const game = new FakeGameService();
    const res = await game.act({ protocolVersion: 1, actionId: "x", expectedRevision: 42, type: "unit.teleport", args: {} });
    expect(res).toMatchObject({ status: "rejected", code: "ILLEGAL_ACTION" });
    expect(game.revision).toBe(42);
  });

  it("never auto-resolves pending decisions on end_turn", async () => {
    const game = new FakeGameService();
    game.decisionsPending = ["conquest:rome"];
    expect(await game.endTurn()).toMatchObject({ status: "rejected", code: "DECISIONS_PENDING" });
    expect(game.turnEnded).toBe(false);
  });

  it("pauses reject mutations with MATCH_PAUSED", async () => {
    const game = new FakeGameService();
    game.paused = true;
    expect(await game.act(move("a1", 42))).toMatchObject({ status: "rejected", code: "MATCH_PAUSED" });
    expect(await game.endTurn()).toMatchObject({ status: "rejected", code: "MATCH_PAUSED" });
  });

  it("action_result reports applied / rejected without reapplying", async () => {
    const game = new FakeGameService();
    await game.act(move("a1", 42));
    expect(await game.actionResult("a1")).toMatchObject({ status: "applied", revision: 43 });
    expect(await game.actionResult("nope")).toMatchObject({ status: "rejected" });
    expect(game.revision).toBe(43);
  });
});

it("fake read contract preserves memory and traverses bounded pages without omissions", async () => {
  const game = new FakeGameService();
  const tile = game.tiles[0]!;
  game.tiles = [2, -1, 0, 1].map(x => ({ ...tile, x, visibility: x === 2 ? "REMEMBERED" : "VISIBLE" }));
  const seen: number[] = [];
  let cursor: string | undefined;
  do {
    const page = await game.map({ limit: 1, ...(cursor ? { cursor } : {}) });
    if ("status" in page) throw Error(page.message);
    expect(page.tiles).toHaveLength(1);
    seen.push(page.tiles[0]!.x);
    cursor = page.nextCursor ?? undefined;
  } while (cursor);
  expect(seen).toEqual([-1, 0, 1, 2]);
  expect(await game.map({ mode: "VISIBLE" })).toMatchObject({ tiles: [{ x: -1 }, { x: 0 }, { x: 1 }] });
  expect(await game.map({ center: { x: 0, y: 0 }, radius: 0 })).toMatchObject({ tiles: [{ x: 0 }] });
  expect(await game.map({ center: { x: 0, y: 0 }, radius: 8, limit: 128 })).toMatchObject({ tiles: [{ x: -1 }, { x: 0 }, { x: 1 }, { x: 2 }] });
  expect(await game.map({ center: { x: 0, y: 0 } })).toMatchObject({ code: "INVALID_ARGUMENT" });
  const first = await game.map({ limit: 1 });
  if ("status" in first || !first.nextCursor) throw Error("missing cursor");
  expect(await game.map({ limit: 2, cursor: first.nextCursor })).toMatchObject({ code: "INVALID_ARGUMENT" });
  game.revision++;
  expect(await game.map({ limit: 1, cursor: first.nextCursor })).toMatchObject({ code: "STALE_STATE" });
  expect(await game.inspectTile({ x: 999, y: 999 })).toBeNull();
  expect(game.tiles[0]).toMatchObject({ lastSeenRevision: 42 });
});

it("fake pagination accounts for Unicode page/cursor overhead and rejects a single oversized tile", async () => {
  const game = new FakeGameService();
  const tile = { ...game.tiles[0]!, terrain: "雪".repeat(18 * 1024) };
  game.tiles = [tile, { ...tile, x: 1 }];
  const first = await game.map({ limit: 128 });
  if ("status" in first || !first.nextCursor) throw Error("expected early bounded page");
  expect(first.tiles).toHaveLength(1);
  expect(Buffer.byteLength(JSON.stringify(first), "utf8")).toBeLessThanOrEqual(96 * 1024);
  expect(await game.map({ limit: 128, cursor: first.nextCursor })).toMatchObject({ tiles: [{ x: 1 }], nextCursor: null });
  game.tiles[0]!.terrain = "雪".repeat(33 * 1024);
  expect(await game.map()).toMatchObject({ status: "rejected", code: "INVALID_ARGUMENT" });
});
