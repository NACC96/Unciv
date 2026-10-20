import { expect, it } from "vitest";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { StdioEngineBackend } from "../src/stdio-engine.js";
import { startGameMcpServer } from "../src/game-mcp-server.js";

it("China can inspect worker geography, preserve ongoing work and re-query after scout movement", async () => {
  const game = new FakeGameService();
  game.units = [{ id: 17, position: { x: 47, y: -33 }, movementLeft: 2 }, { id: 49, position: { x: 48, y: -33 }, movementLeft: 2 }];
  const grass = game.tiles[0]!;
  game.tiles = [
    { ...grass, x: 47, y: -33, river: true, freshwater: true, lastSeenTurn: 9 },
    { ...grass, x: 48, y: -33, ownerCivId: "China", ownWork: { improvement: "Farm", turnsRemaining: 3 }, lastSeenTurn: 9 },
    { ...grass, x: 49, y: -33, visibility: "REMEMBERED", terrain: "Plains", lastSeenTurn: 7,
      resource: { name: "Iron", amount: 2, updatedTurn: 9, updatedRevision: 42, source: "TECHNOLOGY" } },
  ];
  const frames: any[] = [];
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line);
    frames.push(frame);
    void (async () => {
      let result: unknown;
      switch (frame.method) {
        case "observe": result = { ...await game.observe(), civId: "China", ownUnits: [{ unitId: 17, name: "Scout" }, { unitId: 49, name: "Worker" }] }; break;
        case "inspect": {
          const unit = game.units.find(u => u.id === frame.params.unitId)!;
          result = { unitId: unit.id, name: unit.id === 49 ? "Worker" : "Scout", ownerCivId: "China", ...unit.position, health: 100, movementLeft: unit.movementLeft, action: unit.id === 49 ? "Build improvement" : null, tile: await game.inspectTile(unit.position) };
          break;
        }
        case "inspect_tile": result = await game.inspectTile(frame.params); break;
        case "map": result = await game.map(frame.params); break;
        case "rules": result = await game.rules(frame.params); break;
        case "legal_actions": result = { revision: game.revision, moveTargets: [{ x: 48, y: -33 }], attackTargets: [], orders: [], canFound: false, constructions: [], researchable: [], freeTechs: 0, freePolicies: 0, promotions: [], canUpgrade: false, upgradeTo: null, greatActions: [], improvements: [], workInProgress: frame.params.unitId === 49 ? { name: "Farm", turnsRemaining: 3 } : null, repair: null, canCancelWork: frame.params.unitId === 49 }; break;
        case "act": {
          result = await game.act(frame.params);
          if ((result as any).status === "applied") game.tiles.push({ ...grass, x: 50, y: -33, terrain: "Hill", hill: true, lastSeenTurn: 9, lastSeenRevision: game.revision, resource: null });
          break;
        }
        default: throw Error(`unexpected engine method ${frame.method}`);
      }
      engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result }));
    })();
  }, 5000, () => "China");
  const server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "China", runId: "workflow", authorityEpoch: 1 });
  let id = 0;
  const tool = async (name: string, args: object = {}) => {
    const response = await fetch(server.url, { method: "POST", headers: { authorization: `Bearer ${server.bearer}`, "content-type": "application/json", accept: "application/json, text/event-stream" }, body: JSON.stringify({ jsonrpc: "2.0", id: ++id, method: "tools/call", params: { name, arguments: args } }) });
    const body = await response.json() as any;
    return JSON.parse(body.result.content[0].text);
  };
  try {
    const observed = await tool("game.observe");
    expect(observed).toMatchObject({ civId: "China", revision: 42, observationVersion: 2, mapSummary: { knownTileCount: 3, visibleTileCount: 2 } });
    const worker = await tool("game.inspect", { unitId: 49 });
    expect(worker.tile.ownWork).toEqual({ improvement: "Farm", turnsRemaining: 3 });
    const surroundings = await tool("game.map", { center: { x: worker.x, y: worker.y }, radius: 2 });
    expect(surroundings.tiles.find((t: any) => t.x === 49)).toMatchObject({ visibility: "REMEMBERED", lastSeenTurn: 7, resource: { name: "Iron", source: "TECHNOLOGY", updatedTurn: 9 } });
    expect(await tool("game.rules", { section: "improvements", name: "Farm" })).toMatchObject({ entries: [{ name: "Farm", terrainsCanBeBuiltOn: ["Grassland", "Plains"] }] });
    const options = await tool("game.legal_actions", { unitId: 49 });
    expect(options).toMatchObject({ revision: 42, workInProgress: { name: "Farm", turnsRemaining: 3 }, canCancelWork: true });
    const oldPage = await tool("game.map", { limit: 1 });
    const scout = await tool("game.inspect", { unitId: 17 });
    const scoutOptions = await tool("game.legal_actions", { unitId: scout.unitId });
    expect(await tool("game.act", { actionId: "China-t9-scout-1", expectedRevision: scoutOptions.revision, type: "unit.move", args: { unitId: scout.unitId, destination: scoutOptions.moveTargets[0] } })).toMatchObject({ status: "applied", revision: 43 });
    expect(await tool("game.map", { limit: 1, cursor: oldPage.nextCursor })).toMatchObject({ code: "STALE_STATE" });
    expect(await tool("game.observe")).toMatchObject({ revision: 43, mapSummary: { knownTileCount: 4 } });
    const scouted = await tool("game.tile", { x: 50, y: -33 });
    expect(scouted).toMatchObject({ visibility: "VISIBLE", hill: true, lastSeenRevision: 43 });
    expect(scouted).not.toHaveProperty("resource"); // An absent field means null.
    expect((await tool("game.inspect", { unitId: 49 })).tile.ownWork).toEqual(worker.tile.ownWork);
    expect(frames.filter(f => ["observe", "inspect", "inspect_tile", "map", "legal_actions"].includes(f.method)).every(f => f.params.civId === "China")).toBe(true);
    expect(frames.filter(f => f.method === "act")).toHaveLength(1); // Existing worker progress was left intact.
  } finally {
    engine.disconnect();
    await server.close();
  }
});
