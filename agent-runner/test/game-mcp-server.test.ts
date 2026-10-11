import { afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { StdioEngineBackend } from "../src/stdio-engine.js";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { startGameMcpServer, type StartedGameMcpServer } from "../src/game-mcp-server.js";

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

  it("exposes exactly the 8 game tools and round-trips observe/act", async () => {
    server = await startGameMcpServer(new FakeGameService(), {
      matchId: "m", timelineId: "t", civId: "civ", runId: "r", authorityEpoch: 1,
    });
    await handshake(server.url, server.bearer);
    const list = await rpc(server.url, server.bearer, call("tools/list"));
    const names = ((list.json?.["result"] as { tools: { name: string }[] }).tools).map((t) => t.name).sort();
    expect(names).toEqual([
      "game.act", "game.action_result", "game.end_turn", "game.inspect",
      "game.legal_actions", "game.notes", "game.observe", "game.rules",
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


it("accepts desktop DTOs and safely rejects hidden or nonexistent units", async () => {
  const unit = { unitId: 17, name: "Warrior", ownerCivId: "Rome", x: 2, y: 1, health: 100, movementLeft: 2, action: null };
  const legal = { moveTargets: [{ x: 3, y: 1 }], attackTargets: [], orders: ["SLEEP"], canFound: false,
    constructions: [], researchable: ["Pottery"], promotions: [], canUpgrade: false };
  const engine = new StdioEngineBackend(line => {
    const frame = JSON.parse(line);
    const result = frame.method === "inspect" ? (frame.params.unitId === 17 ? unit : null) : legal;
    queueMicrotask(() => engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result })));
  }, 5000, () => "Rome");
  server = await startGameMcpServer(engine, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  const tool = async (name: string, unitId: number) => {
    const response = await rpc(server!.url, server!.bearer, call("tools/call", { name, arguments: { unitId } }));
    const body = response.json?.["result"] as { isError?: boolean; content: { text: string }[] };
    return { isError: body.isError ?? false, value: JSON.parse(body.content[0].text) };
  };
  try {
    expect(await tool("game.inspect", 17)).toEqual({ isError: false, value: unit });
    expect(await tool("game.legal_actions", 17)).toEqual({ isError: false, value: legal });
    const hidden = await tool("game.inspect", 18);
    const missing = await tool("game.inspect", 999);
    expect(hidden).toEqual(missing);
    expect(hidden).toMatchObject({ isError: true, value: { code: "ILLEGAL_ACTION" } });
  } finally { engine.disconnect(); }
});

it("retains structured read rejection codes", async () => {
  const fake = new FakeGameService();
  const rejection = { protocolVersion: 1 as const, status: "rejected" as const, code: "NOT_YOUR_TURN" as const, message: "not active" };
  fake.inspect = async () => rejection;
  fake.legalActions = async () => rejection;
  server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
  for (const name of ["game.inspect", "game.legal_actions"]) {
    const response = await rpc(server.url, server.bearer, call("tools/call", { name, arguments: { unitId: 17 } }));
    const body = response.json?.["result"] as { isError: boolean; content: { text: string }[] };
    expect(body.isError).toBe(true);
    expect(JSON.parse(body.content[0].text)).toMatchObject({ status: "rejected", code: "NOT_YOUR_TURN", message: "not active" });
  }
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
  for (const action of ["unit.move", "research.select", "city.produce", "unit.found", "unit.order", "unit.attack", "conquest.resolve", "diplomacy.declareWar", "diplomacy.makePeace", "diplomacy.friendship", "diplomacy.proposeTrade", "diplomacy.respondTrade", "civics.adopt", "city.citizens", "city.buyTile", "unit.build", "unit.promote", "unit.upgrade", "unit.great", "citystate.giftGold", "unit.pillage", "unit.repair", "diplomacy.denounce", "diplomacy.demand", "diplomacy.respondDemand", "diplomacy.castVote", "city.rename", "city.annex", "city.raze", "unit.stopImprovement", "unit.swap", "unit.rename", "unit.connectRoad", "citystate.tribute", "citystate.protection", "citystate.marry", "diplomacy.retractTrade", "diplomacy.respondProtectorAlert", "diplomacy.respondFriendshipOffer", "unit.gift"])
    expect(act.description).toContain(action);
  for (const shape of ["destination:{x:integer,y:integer}", "techName:string", "improvementName:string", "ourOffers:TradeOffer[]", "ADD_TOP", "SLEEP", "ANNEX", "DECLARE_WAR", "demandWorker:boolean", "accept:boolean", "attackerUnitId", "attackerCityId"])
    expect(act.description).toContain(shape);
  expect(act.description).toContain("actor identity");
  expect(act.description).not.toContain("controller.set");
});
