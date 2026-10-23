import { describe, expect, it, vi } from "vitest";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService, type GameObserveResult } from "../src/fake-game-mcp.js";
import { Supervisor } from "../src/supervisor.js";
import type { SpawnSpec } from "../src/provider-spawn.js";
import type { NormalizedRun } from "../src/provider-events.js";
import * as turnPrompt from "../src/turn-prompt.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");

function parseScripted(stream: string): NormalizedRun {
  const run: NormalizedRun = {
    provider: "scripted", sessionId: null, model: "scripted-0",
    toolCalls: [], finalText: null, usage: null, completed: false,
    errorText: null, advertisedTools: null,
  };
  for (const line of stream.split("\n")) {
    if (!line.trim()) continue;
    let e: Record<string, unknown>;
    try {
      e = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (e["kind"] !== "scripted") continue;
    if (typeof e["sessionId"] === "string" && !run.sessionId) run.sessionId = e["sessionId"] as string;
    if (e["event"] === "tool") {
      run.toolCalls.push({ tool: String(e["tool"]), input: e["input"] ?? null, completed: true, errorText: null });
    }
    if (e["event"] === "run_completed") run.completed = true;
  }
  return run;
}

/** Spawns the scripted provider with the per-invocation bearer. */
function scriptedBuild(prompt: string, resumeId: string | undefined, serverUrl: string, bearer: string): {
  spec: SpawnSpec;
  env: NodeJS.ProcessEnv;
} {
  void prompt;
  const argv = [
    join(repoRoot, "probes", "scripted-provider.mjs"),
    "--mcp-url", serverUrl,
    "--bearer", bearer,
  ];
  if (resumeId) argv.push("--resume", resumeId);
  return { spec: { executable: process.execPath, argv, cwd: repoRoot }, env: {} };
}

const binding = (runId: string) => ({
  matchId: "m", timelineId: "t", civId: "civ", runId, authorityEpoch: 1,
});

const profile = { displayName: "Ada", personality: "Bold.", modelLabel: "scripted-0" };
const context = { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "civ", turnSerial: 1 };

describe("supervisor turn loop with a scripted provider", () => {
  it.each([undefined, "existing-opencode-session"])("delivers current observation policy to session %s without replacing provider metadata", async resumeId => {
    const engine = new FakeGameService();
    engine.notes = "Old plan: pursue a Science victory.";
    const observe = engine.observe.bind(engine);
    const victory: NonNullable<GameObserveResult["victory"]> = {
      enabledVictoryTypes: ["Domination"], available: [], achieved: null, domination: {
        ownedOriginalCapitals: 1, requiredOriginalCapitals: 4, capitals: [], capitalsTruncated: false,
      }, availableTruncated: false,
    };
    const strategicProgress: NonNullable<GameObserveResult["strategicProgress"]> = {
      historyComplete: false, trackedSinceGameTurn: 124, trackedSinceTurnSerial: 901, ownTurnOrdinal: 0,
      current: { research: null, production: [], productionCount: 0, productionTruncated: false },
      incoming: { baselineAvailable: false, fromGameTurn: null, toGameTurn: 124, eventCounts: {}, events: [], truncated: false },
      completedTurns: [],
    };
    engine.observe = async () => ({ ...await observe(), gameTurn: 124, victory, strategicProgress });
    let prompt = "";
    let spawned: SpawnSpec | undefined;
    const promptSpy = vi.spyOn(turnPrompt, "buildTurnPrompt");
    const supervisor = new Supervisor((text, sessionId) => {
      prompt = text;
      expect(sessionId).toBe(resumeId);
      return { spec: { executable: "/fixture", argv: [], cwd: repoRoot,
        context: { matchId: "provider-old-match", civId: "provider-old-civ", runId: "provider-old-run", turnSerial: -1,
          provider: "opencode", model: "selected-model", reasoning: "high", resumed: Boolean(resumeId),
          sessionArm: "resume", sessionAge: 8 } }, env: {} };
    }, parseScripted, {
      spawn(spec) {
        spawned = spec;
        return { pid: 1, stdoutLines() {}, stderrText: () => "", wait: async () => ({ code: 0, signal: null }), kill: vi.fn() };
      },
    });
    try {
      await supervisor.runLeaderTurn({ engine, binding: binding("policy-run"), profile,
        context: { ...context, turnSerial: 901 }, guidance: [], cwd: repoRoot, resumeId, includeStaticInstructions: !resumeId });
      expect(promptSpy).toHaveBeenCalledWith(expect.objectContaining({
        gameTurn: 124, victory, strategicProgress, notes: engine.notes, turnSerial: 901,
      }));
      expect(prompt).toContain("victory-progress-v3");
      expect(prompt).toContain("Domination");
      expect(spawned?.context).toMatchObject({ matchId: "m", timelineId: "t", civId: "civ", runId: "policy-run",
        turnSerial: 901, gameTurn: 124, strategyPolicyVersion: "victory-progress-v3", provider: "opencode",
        model: "selected-model", reasoning: "high", resumed: Boolean(resumeId), sessionArm: "resume", sessionAge: 8 });
    } finally { promptSpy.mockRestore(); }
  });

  it.each([0, undefined])("preserves observed game turn %s without guessing from turn serial", async gameTurn => {
    const engine = new FakeGameService();
    const observe = engine.observe.bind(engine);
    engine.observe = async () => ({ ...await observe(), ...(gameTurn !== undefined ? { gameTurn } : {}) });
    let spawned: SpawnSpec | undefined;
    const supervisor = new Supervisor(() => ({ spec: { executable: "/fixture", argv: [], cwd: repoRoot }, env: {} }), parseScripted, {
      spawn(spec) {
        spawned = spec;
        return { pid: 1, stdoutLines() {}, stderrText: () => "", wait: async () => ({ code: 0, signal: null }), kill: vi.fn() };
      },
    });
    await supervisor.runLeaderTurn({ engine, binding: binding("clock-run"), profile,
      context: { ...context, turnSerial: 901 }, guidance: [], cwd: repoRoot });
    expect(spawned?.context).toMatchObject({ turnSerial: 901, strategyPolicyVersion: "victory-progress-v3" });
    expect(spawned?.context?.gameTurn).toBe(gameTurn);
  });

  it("preserves fresh provider credentials over extra environment values", async () => {
    const engine = new FakeGameService();
    const build = (_prompt: string, _resume: string | undefined, url: string, bearer: string) => ({
      spec: {
        executable: process.execPath,
        argv: ["--input-type=module", "-e",
          'process.argv = ["node", "script", ...process.argv.slice(1), "--bearer", process.env.GAME_MCP_BEARER]; await import("./probes/scripted-provider.mjs");',
          "--", "--mcp-url", url],
        cwd: repoRoot,
      },
      env: { GAME_MCP_BEARER: bearer },
    });
    const supervisor = new Supervisor(build, parseScripted);
    const outcome = await supervisor.runLeaderTurn({
      engine, binding: binding("credential-run"), profile, context,
      guidance: [], cwd: repoRoot,
      extraEnv: { GAME_MCP_BEARER: "unused-by-scripted" },
    });
    expect(outcome.status).toBe("completed_turn");
    expect(outcome.turnEnded).toBe(true);
    expect(engine.revision).toBe(44);
  }, 60000);

  it("lets an active provider finish after the elapsed-time warning", async () => {
    const engine = new FakeGameService();
    const supervisor = new Supervisor(scriptedBuild, parseScripted, undefined, 500, 1);
    const outcome = await supervisor.runLeaderTurn({
      engine, binding: binding("slow-run"), profile, context,
      guidance: [], cwd: repoRoot,
    });
    expect(outcome.status).toBe("completed_turn");
    expect(outcome.turnEnded).toBe(true);
    expect(outcome.run.sessionId).toMatch(/^scripted-/);
  }, 60000);

  it("observe-act-end completes a turn and binds the session", async () => {
    const engine = new FakeGameService();
    const supervisor = new Supervisor(scriptedBuild, parseScripted);
    const outcome = await supervisor.runLeaderTurn({
      engine,
      binding: binding("run-1"),
      profile,
      context,
      guidance: ["Hold the pass."],
      cwd: repoRoot,
      extraEnv: {},
    });
    // Bearer travels via env captured at spawn time by the test wrapper below.
    expect(outcome.status).toBe("completed_turn");
    expect(outcome.turnEnded).toBe(true);
    expect(outcome.revision).toBe(44); // act 42->43, end_turn 43->44
    expect(outcome.run.sessionId).toMatch(/^scripted-/);
    expect(outcome.run.toolCalls.map((t) => t.tool)).toEqual(
      expect.arrayContaining(["game.observe", "game.act", "game.end_turn"]),
    );
  }, 60000);

  it("exact-ID resume continues the same session", async () => {
    const engine = new FakeGameService();
    const supervisor = new Supervisor(scriptedBuild, parseScripted);
    const first = await supervisor.runLeaderTurn({
      engine, binding: binding("run-1"), profile, context, guidance: [], cwd: repoRoot, extraEnv: {},
    });
    expect(first.status).toBe("completed_turn");
    // New turn, new engine state would normally follow; here we just prove
    // the resume plumbing echoes the exact session ID.
    const second = await supervisor.runLeaderTurn({
      engine, binding: binding("run-2"), profile,
      context: { ...context, turnSerial: 2 },
      guidance: [], resumeId: first.run.sessionId ?? undefined, cwd: repoRoot, extraEnv: {},
    });
    expect(second.run.sessionId).toBe(first.run.sessionId);
  }, 90000);

  it("tool-call guard pauses runaway providers", async () => {
    const engine = new FakeGameService();
    const supervisor = new Supervisor(scriptedBuild, parseScripted, undefined, 5, 60000);
    const loopBuild = (prompt: string, resumeId: string | undefined, serverUrl: string, bearer: string): {
      spec: SpawnSpec;
      env: NodeJS.ProcessEnv;
    } => {
      const built = scriptedBuild(prompt, resumeId, serverUrl, bearer);
      return { spec: { ...built.spec, argv: [...built.spec.argv, "--plan", "loop"] }, env: {} };
    };
    const loopSupervisor = new Supervisor(loopBuild, parseScripted, undefined, 5, 60000);
    const outcome = await loopSupervisor.runLeaderTurn({
      engine, binding: binding("run-loop"), profile, context, guidance: [], cwd: repoRoot, extraEnv: {},
    });
    expect(outcome.status).toBe("guard_paused");
    expect(outcome.turnEnded).toBe(false);
    expect(engine.revision).toBe(42); // reads only; nothing committed
  }, 90000);
});
