/**
 * Host cancellation (takeover) at the Supervisor boundary: an aborted run
 * kills its provider, or never spawns it, and reports status "cancelled".
 */
import { describe, expect, it, vi } from "vitest";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { CANCELLED_DETAIL } from "../src/engine-protocol.js";
import { FakeGameService } from "../src/fake-game-mcp.js";
import type { NormalizedRun } from "../src/provider-events.js";
import { NodeRunner, Supervisor, type LeaderTurnArgs, type ProviderRunner, type RunningChild } from "../src/supervisor.js";

const turn = (engine: FakeGameService, signal: AbortSignal, cwd = process.cwd()): LeaderTurnArgs => ({
  engine, binding: { matchId: "m", timelineId: "t", civId: "Rome", runId: "run-a", authorityEpoch: 1 },
  profile: { displayName: "Ada", personality: "Bold.", modelLabel: "fixture" },
  context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "Rome", turnSerial: 3 },
  guidance: [], cwd, signal,
});

const unusedSpawn = () => ({ spec: { executable: "unused", argv: [], cwd: process.cwd() }, env: {} });

/** One tool call per game tool line, plus the partial text a killed provider leaves behind. */
const parseLines = (stream: string): NormalizedRun => ({
  provider: "scripted", sessionId: null, model: null,
  toolCalls: stream.split("\n").filter((line) => line.includes('"tool"'))
    .map(() => ({ tool: "game.observe", input: null, completed: true, errorText: null })),
  finalText: stream ? "half a thought" : null, usage: null, completed: false, errorText: null, advertisedTools: null,
});

const revoked = () => Object.assign(new Error("No provider run is admitted"), { code: "AUTHORITY_REVOKED" });

/** A provider that runs until killed; killing it drains a burst of tool lines before it closes. */
function hangingRunner(drainedToolLines = 0) {
  const state = { spawns: 0, kills: 0, emit: (_line: string): void => {} };
  const runner: ProviderRunner = {
    spawn: () => {
      state.spawns++;
      let close: (exit: { code: number | null; signal: string | null }) => void = () => {};
      const exited = new Promise<{ code: number | null; signal: string | null }>((resolve) => { close = resolve; });
      const child: RunningChild = {
        pid: 4242,
        stdoutLines: (onLine) => { state.emit = onLine; },
        stderrText: () => "",
        wait: () => exited,
        kill: () => {
          state.kills++;
          for (let i = 0; i < drainedToolLines; i++) state.emit('{"tool":"game.observe"}');
          close({ code: null, signal: "SIGKILL" });
        },
      };
      return child;
    },
  };
  return { runner, state };
}

describe("host cancellation of a provider turn", () => {
  it("kills the running provider and reports cancelled without tripping the tool-call cap", async () => {
    const engine = new FakeGameService();
    const { runner, state } = hangingRunner(10);
    const cancel = new AbortController();
    const outcome = new Supervisor(unusedSpawn, parseLines, runner, 5).runLeaderTurn(turn(engine, cancel.signal));
    await vi.waitFor(() => expect(state.spawns).toBe(1));
    for (let i = 0; i < 3; i++) state.emit('{"tool":"game.observe"}');
    cancel.abort();
    const result = await outcome;
    expect(result).toMatchObject({ status: "cancelled", detail: CANCELLED_DETAIL, revision: 42, turnEnded: false });
    // 3 + 10 drained lines exceed the cap of 5, yet the guard never fired its own kill.
    expect(state.kills).toBe(1);
    expect(result.run.toolCalls).toHaveLength(13);
  });

  it("stays cancelled even when the engine committed end_turn before the cut-off", async () => {
    const engine = new FakeGameService();
    const { runner, state } = hangingRunner();
    const cancel = new AbortController();
    const outcome = new Supervisor(unusedSpawn, parseLines, runner).runLeaderTurn(turn(engine, cancel.signal));
    await vi.waitFor(() => expect(state.spawns).toBe(1));
    await engine.endTurn();
    cancel.abort();
    expect(await outcome).toMatchObject({ status: "cancelled", turnEnded: true, revision: 43 });
  });

  it("never spawns the provider when cancelled during the pre-flight engine calls", async () => {
    const engine = new FakeGameService();
    const { runner, state } = hangingRunner();
    const cancel = new AbortController();
    let notesRead = false;
    // The host revokes authority as it cancels; the held observe then fails.
    engine.observe = () => new Promise((_resolve, reject) => {
      cancel.signal.addEventListener("abort", () => setTimeout(() => reject(revoked()), 20));
    });
    engine.readNotes = async () => { notesRead = true; return { notes: "", notesRevision: 0 }; };
    const outcome = new Supervisor(unusedSpawn, parseLines, runner).runLeaderTurn(turn(engine, cancel.signal));
    cancel.abort();
    expect(await outcome).toMatchObject({ status: "cancelled", detail: CANCELLED_DETAIL, revision: null, turnEnded: false });
    expect(state.spawns).toBe(0);
    expect(notesRead).toBe(false);
  });

  it("makes no engine calls at all for a run cancelled before it began", async () => {
    const engine = new FakeGameService();
    const { runner, state } = hangingRunner();
    const observe = vi.spyOn(engine, "observe");
    const cancel = new AbortController();
    cancel.abort();
    const result = await new Supervisor(unusedSpawn, parseLines, runner).runLeaderTurn(turn(engine, cancel.signal));
    expect(result).toMatchObject({ status: "cancelled", revision: null });
    expect(result.run.toolCalls).toEqual([]);
    expect(observe).not.toHaveBeenCalled();
    expect(state.spawns).toBe(0);
  });

  it("reports cancelled when the revocation error lands just before the cancel frame", async () => {
    const engine = new FakeGameService();
    const { runner, state } = hangingRunner();
    const cancel = new AbortController();
    engine.observe = async () => { throw revoked(); };
    const outcome = new Supervisor(unusedSpawn, parseLines, runner).runLeaderTurn(turn(engine, cancel.signal));
    setTimeout(() => cancel.abort(), 50);
    expect(await outcome).toMatchObject({ status: "cancelled", revision: null });
    expect(state.spawns).toBe(0);
  });

  it("still fails a run whose authority was revoked without any cancel", async () => {
    const engine = new FakeGameService();
    engine.observe = async () => { throw revoked(); };
    const outcome = new Supervisor(unusedSpawn, parseLines, hangingRunner().runner)
      .runLeaderTurn(turn(engine, new AbortController().signal));
    await expect(outcome).rejects.toMatchObject({ code: "AUTHORITY_REVOKED" });
  });

  it.skipIf(process.platform === "win32")("kills a wrapper CLI's whole process tree and logs the run as cancelled", async () => {
    const root = mkdtempSync(join(tmpdir(), "unciv-cancel-test-"));
    const pidFile = join(root, "grandchild.pid");
    const wrapper = join(root, "wrapper.mjs");
    // Like npm's codex.js: the real provider is a grandchild sharing our stdout pipe.
    writeFileSync(wrapper, `
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const native = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'inherit' });
writeFileSync(${JSON.stringify(pidFile)}, String(native.pid));
console.log(JSON.stringify({ tool: 'game.observe' }));
setInterval(() => {}, 1000);
`);
    const logRoot = join(root, "logs");
    const supervisor = new Supervisor(() => ({ spec: { executable: process.execPath, argv: [wrapper], cwd: root }, env: {} }),
      parseLines, new NodeRunner(logRoot));
    const cancel = new AbortController();
    let grandchild = 0;
    try {
      const outcome = supervisor.runLeaderTurn(turn(new FakeGameService(), cancel.signal, root));
      await vi.waitFor(() => { grandchild = Number(readFileSync(pidFile, "utf8")); expect(grandchild).toBeGreaterThan(0); },
        { timeout: 5000 });
      const cancelledAt = Date.now();
      cancel.abort();
      const result = await outcome;
      // Closed by the group kill itself, well inside the abandonment grace.
      expect(Date.now() - cancelledAt).toBeLessThan(1500);
      expect(result).toMatchObject({ status: "cancelled", detail: CANCELLED_DETAIL });
      await vi.waitFor(() => expect(() => process.kill(grandchild, 0)).toThrow(), { timeout: 2000 });
      const runDir = join(logRoot, readdirSync(logRoot)[0]!);
      const final = readFileSync(join(runDir, "activity.jsonl"), "utf8").trim().split("\n")
        .map((line) => JSON.parse(line)).filter((entry) => entry.id === "run-end").at(-1);
      expect(final).toMatchObject({ status: "cancelled" });
      expect(final.title).toContain("Provider cancelled");
      expect(JSON.parse(readFileSync(join(runDir, "meta.json"), "utf8"))).toMatchObject({ turnOutcome: "cancelled" });
    } finally {
      if (grandchild) try { process.kill(grandchild, "SIGKILL"); } catch { /* already gone */ }
      rmSync(root, { recursive: true, force: true });
    }
  }, 15000);
});
