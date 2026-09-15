import { describe, expect, it } from "vitest";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { Supervisor } from "../src/supervisor.js";
import type { SpawnSpec } from "../src/provider-spawn.js";
import type { NormalizedRun } from "../src/provider-events.js";

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
function scriptedBuild(prompt: string, resumeId: string | undefined, serverUrl: string, bearer: string): SpawnSpec {
  void prompt;
  const argv = [
    join(repoRoot, "probes", "scripted-provider.mjs"),
    "--mcp-url", serverUrl,
    "--bearer", bearer,
  ];
  if (resumeId) argv.push("--resume", resumeId);
  return { executable: process.execPath, argv, cwd: repoRoot };
}

const binding = (runId: string) => ({
  matchId: "m", timelineId: "t", civId: "civ", runId, authorityEpoch: 1,
});

const profile = { displayName: "Ada", personality: "Bold.", modelLabel: "scripted-0" };
const context = { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "civ", turnSerial: 1 };

describe("supervisor turn loop with a scripted provider", () => {
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
    const loopBuild = (prompt: string, resumeId: string | undefined, serverUrl: string, bearer: string): SpawnSpec => {
      const spec = scriptedBuild(prompt, resumeId, serverUrl, bearer);
      return { ...spec, argv: [...spec.argv, "--plan", "loop"] };
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
