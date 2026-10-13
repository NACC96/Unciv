import { expect, it } from "vitest";
import { FakeGameService } from "../src/fake-game-mcp.js";

it("represents revealed map facts without counting them as visible unit intelligence", async () => {
  const game = new FakeGameService();
  game.vision = "MAP";
  game.tiles.push({ ...game.tiles[0]!, x: 1, visibility: "REVEALED" });

  expect((await game.observe()).mapSummary).toMatchObject({
    vision: "MAP", knownTileCount: 2, visibleTileCount: 1,
  });
  expect(await game.inspectTile({ x: 1, y: 0 })).toMatchObject({ visibility: "REVEALED" });
  const visible = await game.map({ mode: "VISIBLE" });
  expect("tiles" in visible && visible.tiles.map(tile => tile.x)).toEqual([0]);
  const known = await game.map({ mode: "KNOWN" });
  expect("tiles" in known && known.tiles.map(tile => tile.visibility)).toEqual(["VISIBLE", "REVEALED"]);
});

it("rejects a map cursor when vision changes without a gameplay revision", async () => {
  const game = new FakeGameService();
  game.tiles.push({ ...game.tiles[0]!, x: 1 });
  const first = await game.map({ limit: 1 });
  if (!("tiles" in first) || !first.nextCursor) throw new Error("Expected a paginated map");
  const revision = game.revision;
  game.vision = "FULL";
  expect(await game.map({ limit: 1, cursor: first.nextCursor })).toMatchObject({ code: "INVALID_ARGUMENT" });
  expect(game.revision).toBe(revision);
  expect((await game.observe()).mapSummary?.vision).toBe("FULL");
});
