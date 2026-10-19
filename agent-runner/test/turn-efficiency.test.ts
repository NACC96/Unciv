import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { compactLegalActions, compactMapPage, compactObservation, compactTile } from "../src/compact-results.js";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { startGameMcpServer, type StartedGameMcpServer } from "../src/game-mcp-server.js";
import { codexSpawn } from "../src/provider-spawn.js";
import { providerTurnFeatures } from "../src/daemon.js";
import { NodeRunner, Supervisor } from "../src/supervisor.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import { parseCodexStream } from "../src/provider-events.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");

let server: StartedGameMcpServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
});

const MCP_HEADERS = { "content-type": "application/json", "accept": "application/json, text/event-stream" };
let nextId = 1;
async function tool(target: StartedGameMcpServer, name: string, args: unknown) {
  const res = await fetch(target.url, { method: "POST", headers: { ...MCP_HEADERS, authorization: `Bearer ${target.bearer}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: nextId++, method: "tools/call", params: { name, arguments: args } }) });
  const result = (await res.json() as { result: { isError?: boolean; content: { type: string; text?: string }[] } }).result;
  return { isError: result.isError ?? false, value: JSON.parse(result.content[0]!.text!) as Record<string, unknown> };
}

const fullTile = {
  x: 4, y: -2, visibility: "VISIBLE", detailsKnown: true, lastSeenTurn: 31, lastSeenRevision: 1091, terrain: "Plains",
  features: [], naturalWonder: null, river: false, resource: { name: "Deer", amount: 0, updatedTurn: 31, updatedRevision: 1091, source: "SIGHTING" },
  ownerCivId: "America", city: null, improvement: null, improvementPillaged: false, road: "None", roadPillaged: false,
  yields: { context: "ACTOR_CITY", cityId: "city-1", values: { Production: 1, Food: 2, Gold: 0, Science: 0 } },
  ownWork: null, land: true, water: false, hill: true, coast: false, freshwater: false,
  riverEdges: { bottomRight: false, bottom: true, bottomLeft: false },
};

describe("model-facing result compaction", () => {
  it("drops empty tile fields, flattens yields and lists river edges", () => {
    expect(compactTile(fullTile)).toEqual({
      x: 4, y: -2, visibility: "VISIBLE", detailsKnown: true, lastSeenTurn: 31, lastSeenRevision: 1091, terrain: "Plains",
      resource: { name: "Deer", updatedTurn: 31, updatedRevision: 1091, source: "SIGHTING" }, ownerCivId: "America",
      yields: { Production: 1, Food: 2 }, yieldCity: "city-1", land: true, hill: true, riverEdges: ["bottom"],
    });
    expect(JSON.stringify(compactTile(fullTile)).length).toBeLessThan(JSON.stringify(fullTile).length * 0.6);
  });

  it("keeps zero numbers on units, drops own owner IDs and omits correspondence unless asked", () => {
    const observation = {
      revision: 9, isMyTurn: true, diplomaticHistory: [{ messageId: "m1" }],
      ownUnits: [{ unitId: 1, name: "Warrior", ownerCivId: "Rome", x: 0, y: 0, health: 100, movementLeft: 0, action: null, tile: null }],
      visibleForeignUnits: [{ unitId: 2, name: "Spearman", ownerCivId: "Greece", x: 1, y: 0, health: 60, movementLeft: 0, action: "Fortify" }],
    };
    expect(compactObservation(observation, "Rome")).toEqual({
      revision: 9, isMyTurn: true,
      ownUnits: [{ unitId: 1, name: "Warrior", x: 0, y: 0, health: 100, movementLeft: 0 }],
      visibleForeignUnits: [{ unitId: 2, name: "Spearman", ownerCivId: "Greece", x: 1, y: 0, health: 60, movementLeft: 0, action: "Fortify" }],
    });
    expect(compactObservation(observation, "Rome", { includeHistory: true })).toMatchObject({ diplomaticHistory: [{ messageId: "m1" }] });
  });

  it("drops engine bookkeeping from map pages and empty option lists from legal actions", () => {
    expect(compactMapPage({ protocolVersion: 1, observationVersion: 2, matchId: "m", timelineId: "t", revision: 3, tiles: [fullTile], nextCursor: null }))
      .toEqual({ protocolVersion: 1, observationVersion: 2, revision: 3, tiles: [compactTile(fullTile)] });
    expect(compactLegalActions({ moveTargets: [], attackTargets: [{ targetX: 1, targetY: 2, targetKind: "unit", attackFromX: null }],
      orders: [], canFound: false, foundBlockedReason: null, movementLeft: 0, constructions: [{ name: "Walls", turns: 3, cost: 60, goldCost: null }],
      researchable: ["Writing"], freeTechs: 0, revision: 5 }))
      .toEqual({ attackTargets: [{ targetX: 1, targetY: 2, targetKind: "unit" }], movementLeft: 0,
        constructions: [{ name: "Walls", turns: 3, cost: 60 }], researchable: ["Writing"], freeTechs: 0, revision: 5 });
  });
});

describe("game tools", () => {
  it("returns compact observations, opt-in history, city inspection and an end-turn signal", async () => {
    const fake = new FakeGameService();
    let turnsEnded = 0;
    server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 },
      { onTurnEnded: () => { turnsEnded++; } });
    expect((await tool(server, "game.observe", {})).value).not.toHaveProperty("diplomaticHistory");
    expect((await tool(server, "game.observe", { includeHistory: true })).value).toHaveProperty("diplomaticHistory");
    expect(await tool(server, "game.inspect", { cityId: "city-1" })).toMatchObject({ isError: false, value: { cityId: "city-1" } });
    expect(await tool(server, "game.inspect", { cityId: "nowhere" })).toMatchObject({ isError: true, value: { code: "ILLEGAL_ACTION" } });
    expect(await tool(server, "game.inspect", {})).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
    expect(await tool(server, "game.inspect", { unitId: 17, cityId: "city-1" })).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT" } });
    expect(turnsEnded).toBe(0);
    expect(await tool(server, "game.end_turn", {})).toMatchObject({ isError: false, value: { turnEnded: true } });
    expect(turnsEnded).toBe(1);
  });

  it("returns a map picture with its legend from the actor's own reads", async () => {
    server = await startGameMcpServer(new FakeGameService(), { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
    const call = async (args: unknown) => {
      const res = await fetch(server!.url, { method: "POST", headers: { ...MCP_HEADERS, authorization: `Bearer ${server!.bearer}` },
        body: JSON.stringify({ jsonrpc: "2.0", id: nextId++, method: "tools/call", params: { name: "game.map_image", arguments: args } }) });
      return (await res.json() as { result: { isError?: boolean; content: { type: string; text?: string; data?: string; mimeType?: string }[] } }).result;
    };
    const image = await call({});
    expect(image.isError ?? false).toBe(false);
    expect(image.content[0]).toMatchObject({ type: "text", text: expect.stringContaining("Rome") });
    expect(image.content[1]).toMatchObject({ type: "image", mimeType: "image/png" });
    expect(Buffer.from(image.content[1]!.data!, "base64").subarray(1, 4).toString("ascii")).toBe("PNG");
    expect(await call({ radius: 3 })).toMatchObject({ isError: true });
  });

  it("revokes the bearer on close, even on a kept-alive connection", async () => {
    const target = await startGameMcpServer(new FakeGameService(), { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
    expect((await tool(target, "game.observe", {})).isError).toBe(false);
    await target.close();
    await expect(tool(target, "game.observe", {})).rejects.toThrow();
  });
});

describe("turn prompt and provider spawn", () => {
  it("embeds the opening observation and starts from it", () => {
    const prompt = buildTurnPrompt({ displayName: "Ada", personality: "", rulesetName: "Civ V", protocolVersion: 1, civId: "Rome",
      turnSerial: 3, stateRevision: 12, notes: "", recentEvents: [], pendingDecisions: [], guidance: [], includeStaticInstructions: false,
      openingObservation: '{"revision":12,"ownUnits":[]}' });
    expect(prompt).toContain('Opening observation (game.observe at revision 12; absent fields are null, false, zero or empty):\n{"revision":12,"ownUnits":[]}');
    expect(prompt).toContain("Start from the opening observation above instead of calling game.observe");
    expect(prompt).toContain("weigh the balance of power against your rivals");
  });

  it("asks Codex to compact after the turn and attaches the map image without swallowing positionals", () => {
    const base = { prompt: "Play.", mcpUrl: "http://127.0.0.1:1/mcp", bearerEnvVar: "B", cwd: "/tmp", sandboxDangerous: true as const };
    const fresh = codexSpawn({ ...base, compactAfterTurn: true, imagePath: "/tmp/map.png" }).argv;
    expect(fresh).toContain("model_post_turn_compact_threshold_percent=1");
    expect(fresh.slice(-2)).toEqual(["--image=/tmp/map.png", "Play."]);
    const resumed = codexSpawn({ ...base, resumeId: "thread-1", imagePath: "/tmp/map.png" }).argv;
    expect(resumed.slice(-4)).toEqual(["resume", "--image=/tmp/map.png", "thread-1", "Play."]);
    expect(resumed).not.toContain("model_post_turn_compact_threshold_percent=1");
    expect(codexSpawn(base).argv.slice(-1)).toEqual(["Play."]);
  });

  it("enables compaction release and turn-start images only where the provider supports them", () => {
    expect(providerTurnFeatures("codex", { compactAfterTurn: true })).toEqual({ attachMapImage: true, releaseAfterEndTurn: true });
    expect(providerTurnFeatures("codex", {})).toEqual({ attachMapImage: true, releaseAfterEndTurn: false });
    expect(providerTurnFeatures("claude", { compactAfterTurn: true })).toEqual({ attachMapImage: false, releaseAfterEndTurn: false });
  });
});

describe("post-turn compaction", () => {
  it("releases the turn at the closing message, revokes game tools and leaves the provider compacting", async () => {
    const engine = new FakeGameService();
    const logRoot = mkdtempSync(join(tmpdir(), "compaction-logs-"));
    const report = join(logRoot, "report.json");
    const compactMs = 1500;
    const build = (_prompt: string, _resume: string | undefined, url: string, bearer: string) => ({
      spec: { executable: process.execPath, cwd: repoRoot, argv: [join(repoRoot, "probes", "compacting-provider.mjs"),
        "--mcp-url", url, "--bearer", bearer, "--compact-ms", String(compactMs), "--report", report] },
      env: {},
    });
    const supervisor = new Supervisor(build, parseCodexStream, new NodeRunner(logRoot));
    const started = performance.now();
    const outcome = await supervisor.runLeaderTurn({ engine, binding: { matchId: "m", timelineId: "t", civId: "civ", runId: "r1", authorityEpoch: 1 },
      profile: { displayName: "Ada", personality: "", modelLabel: "codex" }, context: { rulesetName: "Civ V", protocolVersion: 1, civId: "civ", turnSerial: 1 },
      guidance: [], cwd: repoRoot, releaseAfterEndTurn: true });
    const releasedAfter = performance.now() - started;
    expect(outcome).toMatchObject({ status: "completed_turn", turnEnded: true });
    expect(outcome.run.finalText).toBe("Turn committed; the border holds.");
    expect(outcome.run.sessionId).toBe("compacting-session");
    expect(outcome.background).toBeDefined();
    await outcome.background!.done;
    expect(performance.now() - started - releasedAfter).toBeGreaterThan(compactMs - 500);
    expect(JSON.parse(readFileSync(report, "utf8"))).toEqual({ lateCall: "rejected" });
    // The released turn keeps its own run-end entry; the compaction is logged after it.
    const runDir = readdirSync(logRoot).find(name => name.startsWith("run-"))!;
    const activity = readFileSync(join(logRoot, runDir, "activity.jsonl"), "utf8").trim().split("\n").map(line => JSON.parse(line));
    expect(activity.filter(entry => entry.id === "run-end")).toEqual([expect.objectContaining({ status: "completed_turn" })]);
    expect(activity.at(-1)).toMatchObject({ id: "session-compaction", title: expect.stringMatching(/^Session compacted in /) });
  }, 30000);
});
