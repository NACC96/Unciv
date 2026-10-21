import { afterEach, describe, expect, it } from "vitest";
import { mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { compactLegalActions, compactMapPage, compactObservation, compactTile } from "../src/compact-results.js";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { startGameMcpServer, type StartedGameMcpServer } from "../src/game-mcp-server.js";
import { codexSpawn } from "../src/provider-spawn.js";
import { providerTurnFeatures, settleCompaction, type Compaction } from "../src/daemon.js";
import { NodeRunner, Supervisor } from "../src/supervisor.js";
import { MAX_IMAGE_TILES, renderCivMapImage } from "../src/map-overview.js";
import { hexDistance } from "../src/map-image.js";
import type { IGameService } from "../src/fake-game-mcp.js";
import type { MilitaryContext } from "../src/strategy-types.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import { parseCodexStream } from "../src/provider-events.js";
import type { ObservedVictoryContext } from "../src/map-types.js";

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
  it("keeps explicit known-false capital metadata distinct from an unknown legacy sighting", () => {
    const city = { cityId: "replacement", name: "Athens II", ownerCivId: "Greece", health: 100,
      population: 4, isOriginalCapital: false, foundingCivId: "Greece" };
    expect(compactTile({ ...fullTile, city })).toHaveProperty("city.isOriginalCapital", false);
    const unknown = { ...city, isOriginalCapital: null, foundingCivId: null };
    expect(compactTile({ ...fullTile, city: unknown })).toHaveProperty("city.isOriginalCapital", null);
  });

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

  it("keeps the map page envelope and drops empty lists and option fields from legal actions", () => {
    expect(compactMapPage({ protocolVersion: 1, observationVersion: 2, matchId: "m", timelineId: "t", revision: 3, tiles: [fullTile], nextCursor: null }))
      .toEqual({ protocolVersion: 1, observationVersion: 2, matchId: "m", timelineId: "t", revision: 3, tiles: [compactTile(fullTile)] });
    expect(compactLegalActions({ moveTargets: [], attackTargets: [{ targetX: 1, targetY: 2, targetKind: "unit", attackFromX: null }],
      orders: [], canFound: false, foundBlockedReason: null, movementLeft: 0, constructions: [{ name: "Walls", turns: 3, cost: 60, goldCost: null }],
      researchable: ["Writing"], freeTechs: 0, revision: 5, improvements: [
        { name: "Farm", kind: "IMPROVEMENT", turnsToBuild: 5, replacesImprovement: null, connectsResource: false, yieldDelta: null, consumesUnit: false },
        { name: "Fishing Boats", kind: "IMPROVEMENT", turnsToBuild: 0, replacesImprovement: null, connectsResource: true, yieldDelta: null, consumesUnit: true }] }))
      .toEqual({ attackTargets: [{ targetX: 1, targetY: 2, targetKind: "unit" }], movementLeft: 0,
        constructions: [{ name: "Walls", turns: 3, cost: 60 }], researchable: ["Writing"], freeTechs: 0, revision: 5, improvements: [
          { name: "Farm", kind: "IMPROVEMENT", turnsToBuild: 5 },
          { name: "Fishing Boats", kind: "IMPROVEMENT", turnsToBuild: 0, connectsResource: true, consumesUnit: true }] });
  });
});

describe("game tools", () => {
  it("preserves exact round and victory unknown false zero and unsupported values through MCP", async () => {
    const fake = new FakeGameService();
    const legacyObservation = await fake.observe();
    const victory: ObservedVictoryContext = { enabledVictoryTypes: ["Scientific"], achieved: null, available: [{
      name: "Scientific", completed: false, completedMilestones: 0, nextMilestone: 0, milestonesTruncated: false,
      milestones: [{ description: "Add all [spaceship parts] in capital", type: "AddedSSPartsInCapital",
        completed: false, current: 0, target: 6, status: "unsupported", unsupportedReason: "No assembly command",
        remainingSpaceshipParts: { "SS Booster": 3 }, partsTruncated: false }],
    }], domination: null, availableTruncated: false };
    const forces = { coverage: "COMPLETE" as const, knownMilitaryUnits: 0, totalMilitaryUnits: 0,
      conventionalUnits: 0, nuclearWeapons: 0, captureCapableUnits: 0, rangedUnits: 0, siegeUnits: 0,
      aircraft: 0, interceptors: 0, damagedUnits: 0, conventionalUnitForce: 0 };
    const military: MilitaryContext = { own: forces, rivals: [{ civId: "Greece", forces: {
      ...forces, coverage: "VISIBLE_ONLY", totalMilitaryUnits: null, conventionalUnitForce: null,
    } }] };
    fake.observe = async () => ({ ...legacyObservation, gameTurn: 104, turnSerial: 524, victory, military });
    server = await startGameMcpServer(fake, { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 });
    expect((await tool(server, "game.observe", {})).value).toMatchObject({ gameTurn: 104, turnSerial: 524, victory, military });
    fake.observe = async () => legacyObservation;
    const legacy = await tool(server, "game.observe", {});
    expect(legacy.isError).toBe(false);
    expect(legacy.value).not.toHaveProperty("victory");
    expect(legacy.value).not.toHaveProperty("gameTurn");
    expect(legacy.value).not.toHaveProperty("military");
  });

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

/** A known map of width × height axial tiles served the way the engine pages game.map (x, then y). */
function largeMapGame(width: number, height: number): IGameService {
  const tiles: { x: number; y: number; visibility: string; detailsKnown: boolean; terrain: string }[] = [];
  for (let x = 0; x < width; x++) for (let y = 0; y < height; y++)
    tiles.push({ x, y, visibility: "REMEMBERED", detailsKnown: true, terrain: "Grassland" });
  const game = {
    observe: async () => ({ mapSummary: { worldWrap: false, knownTileCount: tiles.length }, ownUnits: [], ownCities: [] }),
    map: async (query: { center?: { x: number; y: number }; radius?: number; limit?: number; cursor?: string } = {}) => {
      if ((query.radius ?? 0) > 8) return { status: "rejected", code: "INVALID_ARGUMENT", message: "radius 0..8" };
      const selected = query.center ? tiles.filter(t => hexDistance(t.x, t.y, query.center!.x, query.center!.y) <= query.radius!) : tiles;
      const start = Number(query.cursor ?? 0);
      const end = start + (query.limit ?? 64);
      return { protocolVersion: 1, observationVersion: 2, matchId: "m", timelineId: "t", revision: 1,
        tiles: selected.slice(start, end), nextCursor: end < selected.length ? String(end) : null };
    },
  };
  return game as unknown as IGameService;
}

describe("map pictures on large maps", () => {
  it("reads every known tile of a 164×80-sized map and finds wide close-ups anywhere on it", async () => {
    const game = largeMapGame(164, 80);
    const whole = await renderCivMapImage(game, "Rome");
    expect(whole).toMatchObject({ complete: true, tileCount: 164 * 80 });
    expect(whole!.legend).not.toContain("Partial");

    // The last columns, which a prefix of the whole map would miss.
    const tail = await renderCivMapImage(game, "Rome", { center: { x: 160, y: 40 }, radius: 9 });
    expect(tail).not.toBeNull();
    let inWindow = 0;
    for (let x = 0; x < 164; x++) for (let y = 0; y < 80; y++) if (hexDistance(x, y, 160, 40) <= 9) inWindow++;
    expect(tail!.tileCount).toBe(inWindow);
  });

  it("says when the tile cap cut the whole-map picture short", async () => {
    const width = Math.ceil((MAX_IMAGE_TILES + 1000) / 100);
    const partial = await renderCivMapImage(largeMapGame(width, 100), "Rome");
    expect(partial!.complete).toBe(false);
    expect(partial!.legend).toContain(`Partial picture: it shows the first ${MAX_IMAGE_TILES} of ${width * 100} known tiles`);
  });
});

describe("turn prompt and provider spawn", () => {
  it("embeds the opening observation and starts from it", () => {
    const prompt = buildTurnPrompt({ displayName: "Ada", personality: "", rulesetName: "Civ V", protocolVersion: 1, civId: "Rome",
      turnSerial: 3, stateRevision: 12, notes: "", recentEvents: [], pendingDecisions: [], guidance: [], includeStaticInstructions: false,
      openingObservation: '{"revision":12,"ownUnits":[]}' });
    expect(prompt).toContain('Opening observation (game.observe at revision 12; absent fields are null, false, zero or empty):\n{"revision":12,"ownUnits":[]}');
    expect(prompt).toContain("Start from the opening observation above instead of calling game.observe");
    expect(prompt).toContain("Reconcile your victory commitment with the strategic checkpoint and factual progress");
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
  function pendingCompaction() {
    let finish!: () => void;
    const kills: number[] = [];
    const compaction: Compaction = {
      done: new Promise<void>(resolve => { finish = resolve; }),
      kill: () => { kills.push(Date.now()); finish(); },
    };
    return { compaction, finish: () => finish(), kills };
  }

  it("lets a cancel end the wait for a compacting session while the session stays excluded", async () => {
    const { compaction, finish, kills } = pendingCompaction();
    const compactions = new Map([["s1", compaction]]);
    const cancel = new AbortController();
    const waiting = settleCompaction(compactions, "s1", cancel.signal, 60_000);
    cancel.abort();
    expect(await waiting).toBe("cancelled");
    expect(compactions.get("s1")).toBe(compaction);
    expect(kills).toEqual([]);

    const next = settleCompaction(compactions, "s1", new AbortController().signal, 60_000);
    finish();
    expect(await next).toBe("settled");
    expect(compactions.has("s1")).toBe(false);
  });

  it("abandons a compaction that outlasts the wait", async () => {
    const { compaction, kills } = pendingCompaction();
    const compactions = new Map([["s1", compaction]]);
    expect(await settleCompaction(compactions, "s1", new AbortController().signal, 10)).toBe("settled");
    expect(kills).toHaveLength(1);
    expect(compactions.has("s1")).toBe(false);
    expect(await settleCompaction(compactions, "other", AbortSignal.abort(), 10)).toBe("settled");
  });

  it("releases the turn at the closing message, revokes game tools and leaves the provider compacting", async () => {
    const engine = new FakeGameService();
    const observe = engine.observe.bind(engine);
    engine.observe = async () => ({ ...await observe(), gameTurn: 77 });
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
    const runDir = readdirSync(logRoot).find(name => name.startsWith("run-"))!;
    const releasedMeta = JSON.parse(readFileSync(join(logRoot, runDir, "meta.json"), "utf8"));
    await outcome.background!.done;
    expect(performance.now() - started - releasedAfter).toBeGreaterThan(compactMs - 500);
    expect(JSON.parse(readFileSync(report, "utf8"))).toEqual({ lateCall: "rejected" });
    // The released turn keeps its own run-end entry; the compaction is logged after it.
    const activity = readFileSync(join(logRoot, runDir, "activity.jsonl"), "utf8").trim().split("\n").map(line => JSON.parse(line));
    expect(activity.filter(entry => entry.id === "run-end")).toEqual([expect.objectContaining({ status: "completed_turn" })]);
    expect(activity.at(-1)).toMatchObject({ id: "session-compaction", title: expect.stringMatching(/^Session compacted in /) });
    const identity = { strategyPolicyVersion: "victory-progress-v1", gameTurn: 77, turnSerial: 1,
      matchId: "m", timelineId: "t", civId: "civ" };
    expect(releasedMeta).toMatchObject({ ...identity, turnOutcome: "completed_turn", sessionId: "compacting-session" });
    expect(JSON.parse(readFileSync(join(logRoot, runDir, "meta.json"), "utf8"))).toMatchObject(identity);
    for (const entry of activity.filter(entry => ["run-start", "run-end", "session-compaction"].includes(entry.id)))
      expect(JSON.parse(entry.text)).toMatchObject(identity);
  }, 30000);
});
