/**
 * cancel_run over the real stdio protocol: the test plays the desktop,
 * holding engine RPCs to pin the run mid-turn or mid-pre-flight.
 */
import { describe, expect, it } from "vitest";
import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService } from "../src/fake-game-mcp.js";

const runnerRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

type Frame = Record<string, unknown>;
type Answer = (method: string, params: Record<string, unknown>) => Promise<unknown>;

function runControl(runId: string): Frame {
  return {
    protocol: 1, kind: "control", op: "run_turn", runId,
    provider: "scripted", providerConfig: {},
    binding: { matchId: "m", timelineId: "t", civId: "civ", runId, authorityEpoch: 1 },
    profile: { displayName: "Ada", personality: "Bold.", modelLabel: "scripted" },
    context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "civ", turnSerial: 1 },
    guidance: [],
  };
}

const cancelControl = (runId: string): Frame => ({ protocol: 1, kind: "control", op: "cancel_run", runId });

function answerFrom(engine: FakeGameService): Answer {
  return async (method, params) => {
    switch (method) {
      case "observe": return engine.observe();
      case "notes_get": return engine.readNotes();
      case "act": return engine.act({
        protocolVersion: 1, actionId: String(params["actionId"]),
        expectedRevision: Number(params["expectedRevision"]), type: String(params["type"]),
        args: (params["args"] as Record<string, unknown>) ?? {},
      });
      case "end_turn": return engine.endTurn();
      default: throw new Error(`Unexpected engine method ${method}`);
    }
  };
}

/** Desktop side of the protocol; a thrown `{ code }` becomes an engine error response. */
function startDaemon(answer: Answer) {
  const child = spawn(process.execPath, [join(runnerRoot, "dist", "daemon.js")], { stdio: ["pipe", "pipe", "pipe"] });
  const frames: Frame[] = [];
  const listeners = new Set<() => void>();
  let stderr = "", buffer = "";
  const exited = new Promise<void>((resolveExit) => { child.once("exit", () => resolveExit()); });
  const write = (frame: Frame) => { if (!child.stdin.destroyed) child.stdin.write(JSON.stringify(frame) + "\n"); };
  child.stderr.setEncoding("utf-8");
  child.stderr.on("data", (chunk: string) => { stderr += chunk; });
  child.stdout.setEncoding("utf-8");
  child.stdout.on("data", (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.trim()) continue;
      const frame = JSON.parse(line) as Frame;
      frames.push(frame);
      for (const notify of listeners) notify();
      if (frame["kind"] !== "engine_request") continue;
      void answer(String(frame["method"]), (frame["params"] as Record<string, unknown>) ?? {}).then(
        (result) => write({ protocol: 1, kind: "engine_response", id: frame["id"], ok: true, result }),
        (error: { code?: string; message?: string }) => write({ protocol: 1, kind: "engine_response", id: frame["id"], ok: false,
          error: { code: error.code ?? "INTERNAL_ERROR", message: error.message ?? String(error) } }),
      );
    }
  });
  const waitForFrame = (predicate: (frame: Frame) => boolean, timeoutMs = 10000): Promise<Frame> =>
    new Promise((resolveFrame, reject) => {
      const check = () => {
        const found = frames.find(predicate);
        if (!found) return;
        clearTimeout(timer);
        listeners.delete(check);
        resolveFrame(found);
      };
      const timer = setTimeout(() => {
        listeners.delete(check);
        reject(new Error(`Daemon frame did not arrive: ${JSON.stringify(frames)}; stderr: ${stderr}`));
      }, timeoutMs);
      listeners.add(check);
      check();
    });
  const outcomeOf = (runId: string, timeoutMs?: number) => waitForFrame((frame) => frame["runId"] === runId &&
    (frame["event"] === "run_completed" || frame["event"] === "run_failed"), timeoutMs);
  const stop = async () => {
    write({ protocol: 1, kind: "control", op: "shutdown" });
    const timer = setTimeout(() => child.kill("SIGKILL"), 5000);
    await exited;
    clearTimeout(timer);
  };
  return { frames, write, waitForFrame, outcomeOf, stop };
}

describe("daemon cancel_run", () => {
  it("kills the in-flight provider, ignores stale run ids, and accepts the next run", async () => {
    const engine = new FakeGameService();
    const base = answerFrom(engine);
    let observations = 0;
    let notifyHeld: () => void = () => {};
    const providerHeld = new Promise<void>((resolveHeld) => { notifyHeld = resolveHeld; });
    // Observation 1 is pre-flight; 2 is the provider's own MCP call, held so
    // the provider is genuinely mid-turn when the cancel arrives.
    const daemon = startDaemon(async (method, params) => {
      if (method === "observe" && ++observations === 2) {
        notifyHeld();
        return new Promise(() => {});
      }
      return base(method, params);
    });
    try {
      await daemon.waitForFrame((frame) => frame["kind"] === "ready");
      daemon.write(runControl("run-a"));
      await providerHeld;

      daemon.write(cancelControl("stale-run"));
      await new Promise((r) => setTimeout(r, 300));
      expect(daemon.frames.filter((frame) => frame["runId"] === "stale-run")).toEqual([]);
      expect(daemon.frames.some((frame) => frame["runId"] === "run-a" && String(frame["event"]).startsWith("run_") &&
        frame["event"] !== "run_started")).toBe(false);

      const cancelledAt = Date.now();
      daemon.write(cancelControl("run-a"));
      const cancelled = await daemon.outcomeOf("run-a");
      expect(Date.now() - cancelledAt).toBeLessThan(1500);
      expect(cancelled["event"]).toBe("run_failed");
      expect(cancelled["payload"]).toMatchObject({ status: "cancelled", detail: "cancelled by host", turnEnded: false });
      expect(Array.isArray((cancelled["payload"] as Frame)["toolCalls"])).toBe(true);
      expect(cancelled["payload"]).not.toHaveProperty("summary");

      // The slot is free again; a stale cancel naming the old run never touches the new one.
      daemon.write(runControl("run-b"));
      daemon.write(cancelControl("run-a"));
      const next = await daemon.outcomeOf("run-b");
      expect(next["event"]).toBe("run_completed");
      expect(next["payload"]).toMatchObject({ status: "completed_turn", turnEnded: true, revision: 44 });
      expect(daemon.frames.filter((frame) => frame["runId"] === "run-a" && frame["event"] === "run_failed")).toHaveLength(1);
    } finally {
      await daemon.stop();
    }
  }, 30000);

  it("never spawns the provider when cancelled during pre-flight, even as engine calls fail on revoked authority", async () => {
    const engine = new FakeGameService();
    const base = answerFrom(engine);
    let holdPreFlight = true;
    let revoke: () => void = () => {};
    const daemon = startDaemon(async (method, params) => {
      if (method === "observe" && holdPreFlight) {
        holdPreFlight = false;
        await new Promise<void>((resolveRevoke) => { revoke = resolveRevoke; });
        throw { code: "AUTHORITY_REVOKED", message: "No provider run is admitted" };
      }
      return base(method, params);
    });
    try {
      await daemon.waitForFrame((frame) => frame["kind"] === "ready");
      daemon.write(runControl("run-p"));
      await daemon.waitForFrame((frame) => frame["kind"] === "engine_request" && frame["method"] === "observe");
      daemon.write(cancelControl("run-p"));
      // Settles before the held observe is even answered.
      const cancelled = await daemon.outcomeOf("run-p", 2000);
      expect(cancelled["event"]).toBe("run_failed");
      expect(cancelled["payload"]).toMatchObject({ status: "cancelled", detail: "cancelled by host", turnEnded: false, toolCalls: [] });
      revoke();
      await new Promise((r) => setTimeout(r, 300));
      const requests = daemon.frames.filter((frame) => frame["kind"] === "engine_request").map((frame) => frame["method"]);
      expect(requests).toEqual(["observe"]);
      expect(daemon.frames.some((frame) => frame["runId"] === "run-p" && frame["event"] === "session_bound")).toBe(false);

      daemon.write(runControl("run-q"));
      expect(await daemon.outcomeOf("run-q")).toMatchObject({ event: "run_completed", payload: { status: "completed_turn" } });
    } finally {
      await daemon.stop();
    }
  }, 30000);
});
