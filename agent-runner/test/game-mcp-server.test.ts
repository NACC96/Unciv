import { afterEach, describe, expect, it } from "vitest";
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
