import { afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { startGameMcpServer, type StartedGameMcpServer } from "../src/game-mcp-server.js";
import { StdioEngineBackend } from "../src/stdio-engine.js";

let server: StartedGameMcpServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

const MCP_HEADERS = {
  "content-type": "application/json",
  "accept": "application/json, text/event-stream",
};

async function rpc(url: string, bearer: string | null, body: unknown, extraHeaders: Record<string, string> = {}) {
  const headers: Record<string, string> = { ...MCP_HEADERS, ...extraHeaders };
  if (bearer !== null) headers["authorization"] = `Bearer ${bearer}`;
  const res = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, json: text ? (JSON.parse(text) as Record<string, unknown>) : null };
}

let nextId = 1;
const call = (method: string, params: unknown = {}) => ({ jsonrpc: "2.0", id: nextId++, method, params });

async function handshake(url: string, bearer: string) {
  const init = await rpc(url, bearer, call("initialize", {
    protocolVersion: "2025-11-25",
    capabilities: {},
    clientInfo: { name: "w1-probe", version: "0" },
  }));
  expect(init.status).toBe(200);
  // Initialized notification (no id => no response body expected).
  await fetch(url, {
    method: "POST",
    headers: { ...MCP_HEADERS, authorization: `Bearer ${bearer}` },
    body: JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }),
  });
}

describe("game MCP transport guards (plan §7.3)", () => {
  it("passes real desktop unit and legal-action DTOs through and routes city queries", async () => {
    const unit = { unitId: 17, name: "Settler", ownerCivId: "Rome", x: 2, y: 1, health: 100, movementLeft: 2, action: null };
    const legal = { moveTargets: [{ x: 3, y: 1 }], attackTargets: [], orders: ["SLEEP"], canFound: true,
      constructions: [], researchable: ["Pottery"], freeTechs: 0, freePolicies: 0, promotions: [], canUpgrade: false, upgradeTo: null, greatActions: [] };
    const requests: { method: string; params: Record<string, unknown> }[] = [];
    const engine = new StdioEngineBackend((line) => {
      const frame = JSON.parse(line);
      requests.push(frame);
      const result = frame.method === "inspect" ? (frame.params.unitId === 17 ? unit : null) : legal;
      queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result })));
    }, 30000, () => "Rome");
    server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
    const tool = async (name: string, args: unknown) => {
      const response = await rpc(server!.url, server!.bearer, call("tools/call", { name, arguments: args }));
      const body = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
      return { isError: body.isError ?? false, value: JSON.parse(body.content[0].text) };
    };
    expect(await tool("game.inspect", { unitId: 17 })).toEqual({ isError: false, value: unit });
    expect(await tool("game.legal_actions", { unitId: 17 })).toEqual({ isError: false, value: legal });
    expect(await tool("game.legal_actions", { cityId: "city-1" })).toEqual({ isError: false, value: legal });
    expect(requests.at(-1)).toMatchObject({ method: "legal_actions", params: { civId: "Rome", cityId: "city-1" } });
    expect(await tool("game.inspect", { unitId: 999 })).toMatchObject({ isError: true, value: { code: "ILLEGAL_ACTION" } });
    const count = requests.length;
    expect(await tool("game.legal_actions", {})).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
    expect(await tool("game.legal_actions", { unitId: 17, cityId: "city-1" })).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
    expect(requests.length).toBe(count);
    engine.disconnect();
  });
  it("requires the per-invocation bearer on every request", async () => {
    server = await startGameMcpServer(new FakeGameService(), {
      matchId: "m", timelineId: "t", civId: "civ", runId: "r", authorityEpoch: 1,
    });
    expect(await rpc(server.url, null, call("tools/list"))).toMatchObject({ status: 401 });
    expect(await rpc(server.url, "wrong-bearer", call("tools/list"))).toMatchObject({ status: 401 });
    const okRes = await rpc(server.url, server.bearer, call("tools/list"));
    expect(okRes.status).toBe(200);
  });

  it("rejects browser Origin and non-POST/non-/mcp access", async () => {
    server = await startGameMcpServer(new FakeGameService(), {
      matchId: "m", timelineId: "t", civId: "civ", runId: "r", authorityEpoch: 1,
    });
    const withOrigin = await rpc(server.url, server.bearer, call("tools/list"), { origin: "http://evil.test" });
    expect(withOrigin.status).toBe(403);
    const get = await fetch(server.url, { headers: { authorization: `Bearer ${server.bearer}` } });
    expect(get.status).toBe(405);
  });

  it("rejects tool requests over 64 KiB", async () => {
    server = await startGameMcpServer(new FakeGameService(), {
      matchId: "m", timelineId: "t", civId: "civ", runId: "r", authorityEpoch: 1,
    });
    const big = await rpc(server.url, server.bearer, call("tools/call", {
      name: "game.notes",
      arguments: { expectedNotesRevision: 0, notes: "x".repeat(70 * 1024) },
    }));
    expect([413, 400]).toContain(big.status);
  });

  it("exposes exactly the 10 game tools and round-trips observe/act", async () => {
    server = await startGameMcpServer(new FakeGameService(), {
      matchId: "m", timelineId: "t", civId: "civ", runId: "r", authorityEpoch: 1,
    });
    await handshake(server.url, server.bearer);
    const list = await rpc(server.url, server.bearer, call("tools/list"));
    const names = ((list.json?.["result"] as { tools: { name: string }[] }).tools).map((t) => t.name).sort();
    expect(names).toEqual([
      "game.act", "game.action_result", "game.end_turn", "game.inspect",
      "game.legal_actions", "game.map", "game.notes", "game.observe", "game.rules", "game.tile",
    ]);
    const obs = await rpc(server.url, server.bearer, call("tools/call", { name: "game.observe", arguments: {} }));
    const obsText = (obs.json?.["result"] as { content: { text: string }[] }).content[0].text;
    expect(JSON.parse(obsText)).toMatchObject({ revision: 42, activeTurn: true });
    const act = await rpc(server.url, server.bearer, call("tools/call", {
      name: "game.act",
      arguments: { actionId: "wire-1", expectedRevision: 42, type: "unit.move", args: { unitId: 17, destination: { x: 2, y: -1 } } },
    }));
    const actText = (act.json?.["result"] as { content: { text: string }[] }).content[0].text;
    expect(JSON.parse(actText)).toMatchObject({ status: "applied", revision: 43 });
  });
});

it("round-trips complete map and rules DTOs, validates radius pairs and does not accept actor override", async () => {
  const fake = new FakeGameService();
  const tile = fake.tiles[0]!;
  const page = await fake.map({ mode: "KNOWN" });
  const catalog = { rulesetName: "Civ V", baseRuleset: "Civ V", rulesetIdentity: "pinned", section: "improvements", entries: [{ name: "Farm", techRequired: null, terrainsCanBeBuiltOn: ["Grassland"], yields: { Food: 1 }, effects: [], section: "improvements" }], nextCursor: "opaque" };
  const requests: any[] = [];
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line);
    requests.push(frame);
    const result = frame.method === "inspect_tile" ? (frame.params.x === 0 ? tile : null) : frame.method === "map" ? page : catalog;
    queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result })));
  }, 5000, () => "Rome");
  server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const tool = async (name: string, args: unknown) => {
    const response = await rpc(server!.url, server!.bearer, call("tools/call", { name, arguments: args }));
    const result = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
    return { isError: result.isError ?? false, value: JSON.parse(result.content[0].text) };
  };
  expect(await tool("game.tile", { x: 0, y: 0, civId: "Greece" })).toEqual({ isError: false, value: tile });
  expect(requests.at(-1)).toMatchObject({ method: "inspect_tile", params: { x: 0, y: 0, civId: "Rome" } });
  expect(await tool("game.map", { mode: "VISIBLE", center: { x: 48, y: -33 }, radius: 0, limit: 128, civId: "Greece" })).toEqual({ isError: false, value: page });
  expect(requests.at(-1)).toMatchObject({ method: "map", params: { mode: "VISIBLE", center: { x: 48, y: -33 }, radius: 0, limit: 128, civId: "Rome" } });
  expect(await tool("game.rules", { section: "improvements", name: "Farm", limit: 1, cursor: "opaque" })).toEqual({ isError: false, value: catalog });
  expect(requests.at(-1)).toMatchObject({ method: "rules", params: { section: "improvements", name: "Farm", limit: 1, cursor: "opaque" } });
  const count = requests.length;
  expect(await tool("game.map", { radius: 2 })).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
  expect(await tool("game.map", { center: { x: 1, y: 1 } })).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
  expect(requests).toHaveLength(count);
  expect(await tool("game.tile", { x: 999, y: 0 })).toMatchObject({ isError: true, value: { code: "ILLEGAL_ACTION" } });
  engine.disconnect();
});

it("enforces read budgets by UTF-8 bytes for both map and rules even below transport limit", async () => {
  const fake = new FakeGameService();
  fake.map = async () => ({ protocolVersion: 1, observationVersion: 2, matchId: "fake", timelineId: "fake", revision: 42, tiles: [{ ...fake.tiles[0]!, terrain: "雪".repeat(33 * 1024) }], nextCursor: null });
  fake.rules = async () => ({ rulesetName: "fake", baseRuleset: "fake", rulesetIdentity: "fake", section: "improvements", entries: [{ name: "雪".repeat(33 * 1024), section: "improvements", yields: {}, effects: [] }], nextCursor: null });
  server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  for (const name of ["game.map", "game.rules"]) {
    const response = await rpc(server.url, server.bearer, call("tools/call", { name, arguments: {} }));
    const result = response.json?.["result"] as { isError: boolean; content: { text: string }[] };
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toMatchObject({ code: "INVALID_ARGUMENT" });
  }
});

it("preserves production stdio rejection codes and receipt metadata through all new reads", async () => {
  const error = { code: "STALE_STATE", message: "cursor is stale", currentRevision: 45, actionId: "original" };
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line);
    queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: false,
      error: frame.method === "rules" ? { ...error, code: "INVALID_ARGUMENT", message: "invalid section" } : error })));
  }, 5000, () => "Rome");
  server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  for (const [name, args] of [["game.map", { cursor: "stale" }], ["game.tile", { x: 0, y: 0 }], ["game.rules", { section: "invalid" }]] as const) {
    const response = await rpc(server.url, server.bearer, call("tools/call", { name, arguments: args }));
    const result = response.json?.["result"] as { isError: boolean; content: { text: string }[] };
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toEqual({ status: "rejected", ...(name === "game.rules" ? { ...error, code: "INVALID_ARGUMENT", message: "invalid section" } : error) });
  }
  engine.disconnect();
});

it("budgets final escaped JSON-RPC read responses with actual bounded request IDs", async () => {
  const fake = new FakeGameService();
  const text = '"\\雪'.repeat(13 * 1024);
  fake.rules = async () => ({ rulesetName: "fake", baseRuleset: "fake", rulesetIdentity: "fake", section: "improvements", entries: [{ name: text, section: "improvements", yields: {}, effects: [] }], nextCursor: null });
  let mapCalls = 0;
  fake.map = async () => {
    mapCalls++;
    return { protocolVersion: 1, observationVersion: 2, matchId: "m", timelineId: "t", revision: 42, tiles: [{ ...fake.tiles[0]!, terrain: text }], nextCursor: null };
  };
  server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const id = "x".repeat(126); // JSON quotes make this exactly 128 serialized bytes.
  for (const name of ["game.map", "game.rules", "game.tile"]) {
    const response = await rpc(server.url, server.bearer, { jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: name === "game.tile" ? { x: 0, y: 0 } : {} } });
    expect(response.status).toBe(200);
    expect(Buffer.byteLength(JSON.stringify(response.json), "utf8")).toBeLessThanOrEqual(96 * 1024);
    const result = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
    if (name !== "game.tile") expect(JSON.parse(result.content[0].text)).toMatchObject({ status: "rejected", code: "INVALID_ARGUMENT" });
  }
  const callsBeforeOverlong = mapCalls;
  const overlong = await rpc(server.url, server.bearer, { jsonrpc: "2.0", id: "雪".repeat(43), method: "tools/call", params: { name: "game.map", arguments: {} } });
  expect(overlong.status).toBe(400);
  expect(mapCalls).toBe(callsBeforeOverlong);
});

it("returns escape-aware paginated map pages inside the real wire envelope", async () => {
  const fake = new FakeGameService();
  const terrain = '"\\雪'.repeat(5 * 1024);
  fake.tiles = [{ ...fake.tiles[0]!, terrain }, { ...fake.tiles[0]!, x: 1, terrain }];
  server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const tiles: number[] = [];
  let cursor: string | undefined;
  do {
    const response = await rpc(server.url, server.bearer, { jsonrpc: "2.0", id: "x".repeat(126), method: "tools/call", params: { name: "game.map", arguments: { limit: 128, ...(cursor ? { cursor } : {}) } } });
    expect(Buffer.byteLength(JSON.stringify(response.json), "utf8")).toBeLessThanOrEqual(96 * 1024);
    const result = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
    expect(result.isError).not.toBe(true);
    const page = JSON.parse(result.content[0].text);
    expect(page.tiles).toHaveLength(1);
    tiles.push(page.tiles[0].x);
    cursor = page.nextCursor ?? undefined;
  } while (cursor);
  expect(tiles).toEqual([0, 1]);
});

it("requires exactly one entity selector before dispatching legal_actions", async () => {
  const fake = new FakeGameService(); const legal = vi.spyOn(fake, "legalActions");
  server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  for (const args of [{}, { unitId: 17, cityId: "city-1" }]) {
    const response = await rpc(server.url, server.bearer, call("tools/call", { name: "game.legal_actions", arguments: args }));
    const result = response.json?.["result"] as { isError: boolean; content: { text: string }[] };
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toMatchObject({ code: "INVALID_ARGUMENT" });
  }
  expect(legal).not.toHaveBeenCalled();
});

it("supports concrete fake city options without exposing unit choices", async () => {
  const fake = new FakeGameService();
  expect(await fake.legalActions(undefined, "city-1")).toMatchObject({ constructions: ["Monument", "Warrior"], moveTargets: [], orders: [] });
  expect(await fake.legalActions(undefined, "hidden-city")).toEqual(await fake.legalActions(undefined, "missing-city"));
  expect(await fake.legalActions()).toMatchObject({ code: "INVALID_ARGUMENT" });
  expect(await fake.legalActions(17, "city-1")).toMatchObject({ code: "INVALID_ARGUMENT" });
});

it("retains every typed legal_actions rejection field from real stdio", async () => {
  const error = { code: "STALE_STATE", message: "re-observe", currentRevision: 47, actionId: "recovered-query", messageIds: ["message-1"] };
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line);
    queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: false, error })));
  }, 5000, () => "Rome");
  server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  try {
    const response = await rpc(server.url, server.bearer, call("tools/call", { name: "game.legal_actions", arguments: { cityId: "city-1" } }));
    const result = response.json?.["result"] as { isError: boolean; content: { text: string }[] };
    expect(result.isError).toBe(true);
    expect(JSON.parse(result.content[0].text)).toEqual({ status: "rejected", ...error });
  } finally { engine.disconnect(); }
});

it("passes actor-bound city legal choices through real stdio and treats hidden/missing cities equally", async () => {
  const requests: { method: string; params: Record<string, unknown> }[] = [];
  const choices = { moveTargets: [], attackTargets: [], orders: [], canFound: false, constructions: ["Monument", "Warrior"], researchable: ["Pottery"], freeTechs: 0, freePolicies: 0, promotions: [], canUpgrade: false, upgradeTo: null, greatActions: [] };
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line); requests.push(frame);
    const reply = frame.params.cityId === "owned-city" ? { ok: true, result: choices }
      : { ok: false, error: { code: "ILLEGAL_ACTION", message: "no such visible entity" } };
    queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ...reply })));
  }, 5000, () => "Rome");
  server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const query = async (cityId: string) => {
    const response = await rpc(server!.url, server!.bearer, call("tools/call", { name: "game.legal_actions", arguments: { cityId } }));
    const result = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
    return { isError: result.isError ?? false, value: JSON.parse(result.content[0].text) };
  };
  try {
    expect(await query("owned-city")).toEqual({ isError: false, value: choices });
    expect(requests[0]).toMatchObject({ method: "legal_actions", params: { civId: "Rome", cityId: "owned-city" } });
    expect(requests[0].params).not.toHaveProperty("unitId");
    const hidden = await query("hidden-city"); const missing = await query("missing-city");
    expect(hidden).toEqual(missing);
    expect(hidden).toMatchObject({ isError: true, value: { status: "rejected", code: "ILLEGAL_ACTION" } });
  } finally { engine.disconnect(); }
});

it("publishes actionable names, arguments and enum choices in model-visible game.act metadata", async () => {
  server = await startGameMcpServer(new FakeGameService(), { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const response = await rpc(server.url, server.bearer, call("tools/list"));
  const tools = (response.json?.["result"] as { tools: { name: string; description: string; inputSchema: unknown }[] }).tools;
  const act = tools.find(tool => tool.name === "game.act")!;
  // Cross-language contract: newly bridged commands must remain discoverable to models.
  const bridge = readFileSync(new URL("../../core/src/com/unciv/logic/agents/AgentEngineBridge.kt", import.meta.url), "utf8");
  const dispatch = bridge.split("return when (type)")[1].split("else ->")[0];
  const bridgeNames = [...dispatch.matchAll(/"([a-z][A-Za-z.]+)"\s*->/g)].map(match => match[1]).sort();
  const documentedNames = [...act.description.matchAll(/^([a-z][A-Za-z.]+) -> \{/gm)].map(match => match[1]).sort();
  expect(documentedNames).toEqual(bridgeNames);
  for (const action of ["unit.move", "research.select", "city.produce", "unit.found", "unit.order", "unit.attack", "conquest.resolve", "diplomacy.declareWar", "diplomacy.makePeace", "diplomacy.friendship", "diplomacy.proposeTrade", "diplomacy.respondTrade", "civics.adopt", "city.citizens", "city.buyTile", "unit.build", "unit.promote", "unit.upgrade", "unit.great", "citystate.giftGold", "unit.pillage", "unit.repair", "diplomacy.denounce", "diplomacy.demand", "diplomacy.respondDemand", "diplomacy.castVote", "city.rename", "city.annex", "city.raze", "unit.stopImprovement", "unit.swap", "unit.rename", "unit.connectRoad", "citystate.tribute", "citystate.protection", "citystate.marry", "diplomacy.retractTrade", "diplomacy.respondProtectorAlert", "diplomacy.respondFriendshipOffer", "unit.gift", "diplomacy.message", "diplomacy.acknowledgeMessage"])
    expect(act.description).toContain(action);
  for (const shape of ["destination:{x:integer,y:integer}", "techName:string", "improvementName:string", "ourOffers:TradeOffer[]", "ADD_TOP", "SLEEP", "ANNEX", "DECLARE_WAR", "demandWorker:boolean", "accept:boolean", "attackerUnitId", "attackerCityId"])
    expect(act.description).toContain(shape);
  expect(act.description).toContain("actor identity");
  expect(act.description).not.toContain("controller.set");
});

it("exposes the major-nation peace consent contract in the model-visible tool catalog", async () => {
  server = await startGameMcpServer(new FakeGameService(), { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const response = await rpc(server.url, server.bearer, call("tools/list"));
  const tools = (response.json?.["result"] as { tools: { name: string; description: string }[] }).tools;
  const description = tools.find(tool => tool.name === "game.act")!.description;
  expect(description).toMatch(/diplomacy\.makePeace -> \{targetCivId:string\}[^\n]*city-state-only/);
  expect(description).toMatch(/diplomacy\.proposeTrade[^\n]*queues[^\n]*does not end war/);
  const offer = { name: "Peace Treaty", type: "Treaty", amount: 1 };
  const example = description.match(/Major-nation peace example: (\{[^\n]+\})/);
  expect(example).not.toBeNull();
  expect(JSON.parse(example![1])).toEqual({ targetCivId: "Greece", ourOffers: [offer], theirOffers: [offer] });
  expect(description).toMatch(/recipient[^\n]*own turn[^\n]*diplomacy\.respondTrade/);
  expect(description).toMatch(/PeaceProposal[^\n]*third-party[^\n]*civilization ID/);
  expect(description).toMatch(/PeaceProposal[^\n]*cannot[^\n]*major/);
});
