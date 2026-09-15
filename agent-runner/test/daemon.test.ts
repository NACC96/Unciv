/**
 * Daemon end-to-end: the test plays the desktop side of the stdio protocol
 * (answers engine RPCs from a FakeGameService), sends a scripted run_turn,
 * and asserts turn events + applied engine effects.
 */
import { describe, expect, it } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService } from "../src/fake-game-mcp.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
const daemonJs = join(repoRoot, "dist", "daemon.js");

interface Collected {
  events: Record<string, unknown>[];
}

async function driveDaemon(
  controls: Record<string, unknown>[],
  answer: (method: string, params: Record<string, unknown>) => Promise<unknown>,
  settleMs = 30000,
): Promise<Collected> {
  if (!existsSync(daemonJs)) throw new Error("run `npm run build` before daemon tests");
  const child: ChildProcess = spawn(process.execPath, [daemonJs], { stdio: ["pipe", "pipe", "pipe"] });
  const events: Record<string, unknown>[] = [];
  let buffer = "";
  let ready = false;
  const collected: Collected = { events };

  const onLine = (line: string) => {
    if (!line.trim()) return;
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(line) as Record<string, unknown>;
    } catch {
      return;
    }
    if (frame["kind"] === "ready") {
      ready = true;
      for (const control of controls) child.stdin?.write(JSON.stringify(control) + "\n");
      return;
    }
    if (frame["kind"] === "turn_event") {
      events.push(frame);
      return;
    }
    if (frame["kind"] === "engine_request") {
      const id = frame["id"] as string;
      const method = frame["method"] as string;
      const params = (frame["params"] as Record<string, unknown>) ?? {};
      void Promise.resolve()
        .then(() => answer(method, params))
        .then((result) =>
          child.stdin?.write(
            JSON.stringify({ protocol: 1, kind: "engine_response", id, ok: true, result }) + "\n",
          ),
        )
        .catch((error: unknown) =>
          child.stdin?.write(
            JSON.stringify({
              protocol: 1,
              kind: "engine_response",
              id,
              ok: false,
              error: { code: "INTERNAL_ERROR", message: error instanceof Error ? error.message : String(error) },
            }) + "\n",
          ),
        );
    }
  };

  child.stdout?.setEncoding("utf-8");
  child.stdout?.on("data", (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) onLine(line);
  });

  const started = Date.now();
  const wantCompletion = controls.some(
    (c) => (c as { op?: string }).op === "run_turn",
  );
  for (;;) {
    await new Promise((r) => setTimeout(r, 100));
    const done = events.some(
      (e) => e["event"] === "run_completed" || e["event"] === "run_failed",
    );
    if ((done && wantCompletion) || Date.now() - started > settleMs) break;
  }
  child.stdin?.write(JSON.stringify({ protocol: 1, kind: "control", op: "shutdown" }) + "\n");
  await new Promise<void>((resolveExit) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolveExit();
    }, 5000);
    child.on("exit", () => {
      clearTimeout(timer);
      resolveExit();
    });
  });
  if (!ready) throw new Error("daemon never became ready");
  return collected;
}

describe("supervisor daemon over stdio", () => {
  it("runs a scripted turn through desktop-answered engine RPCs", async () => {
    const engine = new FakeGameService();
    const answer = async (method: string, params: Record<string, unknown>): Promise<unknown> => {
      switch (method) {
        case "observe":
          return engine.observe();
        case "inspect":
          return engine.inspect(params["unitId"] as number);
        case "rules":
          return { ruleset: "Civ V - Vanilla (stub)" };
        case "legal_actions":
          return { actions: [] };
        case "act":
          return engine.act({
            protocolVersion: 1,
            actionId: String(params["actionId"]),
            expectedRevision: Number(params["expectedRevision"]),
            type: String(params["type"]),
            args: (params["args"] as Record<string, unknown>) ?? {},
          });
        case "action_result":
          return engine.actionResult(String(params["actionId"]));
        case "notes_get":
          return engine.readNotes();
        case "notes_put":
          return engine.updateNotes(String(params["notes"]), Number(params["expectedNotesRevision"]));
        case "end_turn":
          return engine.endTurn();
        default:
          throw new Error(`unknown method ${method}`);
      }
    };

    const collected = await driveDaemon(
      [
        {
          protocol: 1,
          kind: "control",
          op: "run_turn",
          runId: "run-1",
          provider: "scripted",
          providerConfig: {},
          binding: { matchId: "m", timelineId: "t", civId: "civ", runId: "run-1", authorityEpoch: 1 },
          profile: { displayName: "Ada", personality: "Bold.", modelLabel: "scripted" },
          context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "civ", turnSerial: 1 },
          guidance: [],
        },
      ],
      answer,
    );

    const kinds = collected.events.map((e) => e["event"]);
    expect(kinds).toContain("run_started");
    expect(kinds).toContain("session_bound");
    const completed = collected.events.find((e) => e["event"] === "run_completed") as
      | { payload?: { status?: string; toolCalls?: string[]; turnEnded?: boolean } }
      | undefined;
    expect(completed?.payload?.status).toBe("completed_turn");
    expect(completed?.payload?.turnEnded).toBe(true);
    expect(completed?.payload?.toolCalls).toEqual(
      expect.arrayContaining(["game.observe", "game.act", "game.end_turn"]),
    );
    // Engine effects applied through the desktop-answered RPCs.
    expect(engine.revision).toBe(44);
    expect(engine.turnEnded).toBe(true);
  }, 90000);
});
