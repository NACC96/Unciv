import { describe, expect, it } from "vitest";
import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService } from "../src/fake-game-mcp.js";

const runnerRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function runControl(runId: string): Record<string, unknown> {
  return {
    protocol: 1, kind: "control", op: "run_turn", runId,
    provider: "scripted", providerConfig: {},
    binding: { matchId: "m", timelineId: "t", civId: "civ", runId, authorityEpoch: 1 },
    profile: { displayName: "Ada", personality: "Bold.", modelLabel: "scripted" },
    context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "civ", turnSerial: 1 },
    guidance: [],
  };
}

describe("daemon graceful shutdown", () => {
  it("keeps an active provider connected until its turn finishes and rejects new turns", async () => {
    const engine = new FakeGameService();
    const child = spawn(process.execPath, [join(runnerRoot, "dist", "daemon.js")], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    const frames: Record<string, unknown>[] = [];
    let stderr = "";
    let buffer = "";
    let observations = 0;
    let releaseObservation: (() => void) | undefined;
    let notifyBlocked: () => void = () => {};
    const providerBlocked = new Promise<void>((resolveBlocked) => { notifyBlocked = resolveBlocked; });
    const listeners = new Set<() => void>();
    const exited = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolveExit) => {
      child.once("exit", (code, signal) => { resolveExit({ code, signal }); });
    });
    const write = (frame: Record<string, unknown>) => { child.stdin.write(JSON.stringify(frame) + "\n"); };
    const waitForFrame = (predicate: (frame: Record<string, unknown>) => boolean): Promise<Record<string, unknown>> =>
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
        }, 10000);
        listeners.add(check);
        check();
      });
    const answer = async (method: string, params: Record<string, unknown>): Promise<unknown> => {
      switch (method) {
        case "observe":
          observations += 1;
          // The first observation is supervisor preflight; the second is the
          // real scripted provider's MCP request. Hold it across shutdown.
          if (observations === 2) {
            await new Promise<void>((resolveObservation) => {
              releaseObservation = resolveObservation;
              notifyBlocked();
            });
          }
          return engine.observe();
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
    child.stderr.setEncoding("utf-8");
    child.stderr.on("data", (chunk: string) => { stderr += chunk; });
    child.stdout.setEncoding("utf-8");
    child.stdout.on("data", (chunk: string) => {
      buffer += chunk;
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.trim()) continue;
        const frame = JSON.parse(line) as Record<string, unknown>;
        frames.push(frame);
        for (const notify of listeners) notify();
        if (frame["kind"] !== "engine_request") continue;
        void answer(String(frame["method"]), (frame["params"] as Record<string, unknown>) ?? {})
          .then((result) => {
            if (!child.stdin.destroyed) write({ protocol: 1, kind: "engine_response", id: frame["id"], ok: true, result });
          });
      }
    });

    try {
      await waitForFrame((frame) => frame["kind"] === "ready");
      write(runControl("original-turn"));
      await Promise.race([
        providerBlocked,
        waitForFrame((frame) => frame["runId"] === "original-turn" &&
          (frame["event"] === "run_completed" || frame["event"] === "run_failed"))
          .then((frame) => { throw new Error(`Provider finished before its observation could be held: ${JSON.stringify(frame)}; stderr: ${stderr}`); }),
      ]);
      write({ protocol: 1, kind: "control", op: "shutdown" });
      write(runControl("rejected-turn"));
      const rejected = await waitForFrame((frame) => frame["runId"] === "rejected-turn" && frame["event"] === "run_failed");
      expect(rejected["payload"]).toMatchObject({ reason: "supervisor shutting down" });
      releaseObservation?.();
      const completed = await waitForFrame((frame) => frame["runId"] === "original-turn" &&
        (frame["event"] === "run_completed" || frame["event"] === "run_failed"));
      expect(completed["event"]).toBe("run_completed");
      expect(completed["payload"]).toMatchObject({ status: "completed_turn", turnEnded: true, revision: 44 });
      expect(engine.turnEnded).toBe(true);
      expect(engine.revision).toBe(44);
      expect(frames.filter((frame) => frame["event"] === "run_started")).toHaveLength(1);
      expect(await exited).toEqual({ code: 0, signal: null });
    } finally {
      releaseObservation?.();
      if (child.exitCode === null && child.signalCode === null && !child.stdin.destroyed) {
        write({ protocol: 1, kind: "control", op: "shutdown" });
      }
      await exited;
    }
  }, 20000);
});
