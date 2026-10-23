/**
 * Daemon end-to-end: the test plays the desktop side of the stdio protocol
 * (answers engine RPCs from a FakeGameService), sends a scripted run_turn,
 * and asserts turn events + applied engine effects.
 */
import { afterEach, describe, expect, it } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import { chmodSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeGameService } from "../src/fake-game-mcp.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
const daemonJs = join(repoRoot, "dist", "daemon.js");
const evaluationDirectories: string[] = [];
afterEach(() => { for (const directory of evaluationDirectories.splice(0)) rmSync(directory, { recursive: true, force: true }); });

/** Harmless Codex-shaped process: real MCP, no model, and a held post-turn completion. */
function evaluationFixture(controls: Record<string, unknown>[], emittedSession = "new-evaluation-session",
  options: { sourceSessions?: Record<string, string>; emittedSessions?: string[] } = {}) {
  const directory = mkdtempSync(join(tmpdir(), "unciv-evaluation-driver-"));
  evaluationDirectories.push(directory);
  const fixture = join(directory, "codex-fixture.mjs");
  const configPath = join(directory, "config.json");
  const invocationsPath = join(directory, "invocations.jsonl");
  const holdPath = join(directory, "compacting");
  const releasePath = join(directory, "release");
  const clientModule = new URL("../node_modules/@modelcontextprotocol/sdk/dist/esm/client/index.js", import.meta.url).href;
  const transportModule = new URL("../node_modules/@modelcontextprotocol/sdk/dist/esm/client/streamableHttp.js", import.meta.url).href;
  writeFileSync(fixture, `#!/usr/bin/env node
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { Client } from ${JSON.stringify(clientModule)};
import { StreamableHTTPClientTransport } from ${JSON.stringify(transportModule)};
const argv = process.argv.slice(2);
appendFileSync(process.env.FIXTURE_INVOCATIONS, JSON.stringify(argv) + "\\n");
const mcp = argv.find(arg => arg.startsWith("mcp_servers.game="));
const url = /url="([^"]+)"/.exec(mcp)[1];
const resume = argv.indexOf("resume");
const invocation = readFileSync(process.env.FIXTURE_INVOCATIONS, "utf8").trim().split("\\n").length - 1;
const emittedSessions = JSON.parse(process.env.FIXTURE_SESSION_SEQUENCE);
const session = emittedSessions[invocation] ?? (resume < 0 ? process.env.FIXTURE_SESSION : argv.slice(resume + 1).find(arg => !arg.startsWith("--image=")));
const emit = event => console.log(JSON.stringify(event));
emit({type:"thread.started",thread_id:session});
const transport = new StreamableHTTPClientTransport(new URL(url), {requestInit:{headers:{authorization:"Bearer " + process.env.GAME_MCP_BEARER}}});
const client = new Client({name:"evaluation-fixture",version:"1"});
await client.connect(transport);
const result = await client.callTool({name:"game.end_turn",arguments:{}});
emit({type:"item.completed",item:{id:"end",type:"mcp_tool_call",tool:"game.end_turn",arguments:{},result}});
emit({type:"item.completed",item:{id:"summary",type:"agent_message",text:"Copied turn complete."}});
if (resume < 0) {
  writeFileSync(process.env.FIXTURE_HOLD, "compacting");
  while (!existsSync(process.env.FIXTURE_RELEASE)) await new Promise(resolve => setTimeout(resolve, 10));
}
emit({type:"turn.completed",usage:{input_tokens:900100,output_tokens:10020}});
await transport.close();
`);
  chmodSync(fixture, 0o755);
  writeFileSync(configPath, JSON.stringify({ daemonJs, outputDirectory: directory,
    sessionPolicy: { mode: "forked-persistent", forkFromSessionIds: options.sourceSessions ?? { Rome: "live-source" } } }));
  const child = spawn(process.execPath, [join(repoRoot, "probes", "strategy-evaluation-driver.mjs")], {
    stdio: ["pipe", "pipe", "pipe"], env: { ...process.env, UNCIV_EVALUATION_CONFIG: configPath,
      UNCIV_CODEX_EXECUTABLE: fixture, UNCIV_AGENT_LOG_DIR: join(directory, "providers"),
      FIXTURE_INVOCATIONS: invocationsPath, FIXTURE_SESSION: emittedSession,
      FIXTURE_SESSION_SEQUENCE: JSON.stringify(options.emittedSessions ?? []), FIXTURE_HOLD: holdPath, FIXTURE_RELEASE: releasePath },
  });
  const engines = new Map<string, FakeGameService>();
  const events: Record<string, unknown>[] = [];
  let buffer = "", stderr = "", next = 0, exited = false;
  const send = (control: Record<string, unknown>) => child.stdin.write(JSON.stringify(control) + "\n");
  const exit = new Promise<void>((resolveExit, reject) => {
    child.on("error", reject);
    child.on("exit", () => { exited = true; resolveExit(); });
  });
  child.stderr.on("data", chunk => { stderr += String(chunk); });
  child.stdout.on("data", chunk => {
    buffer += String(chunk);
    const lines = buffer.split("\n"); buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.trim()) continue;
      const frame = JSON.parse(line) as Record<string, unknown>;
      if (frame.kind === "ready") { send(controls[next++]); continue; }
      if (frame.kind === "engine_request") {
        const params = frame.params as Record<string, unknown>;
        const civ = String(params.civId ?? params.actorCivId ?? "Rome");
        let engine = engines.get(civ);
        if (!engine) { engine = new FakeGameService(); engine.civId = civ; engines.set(civ, engine); }
        const result = frame.method === "observe" ? engine.observe()
          : frame.method === "notes_get" ? engine.readNotes()
          : frame.method === "map" ? engine.map(params)
          : frame.method === "end_turn" ? engine.endTurn()
          : Promise.reject(new Error(`Unexpected fixture engine method ${frame.method}`));
        void Promise.resolve(result).then(value => send({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result: value }));
      }
      if (frame.kind === "turn_event") {
        events.push(frame);
        if (frame.event === "run_completed" || frame.event === "run_failed") {
          if (next < controls.length) send(controls[next++]);
          else send({ protocol: 1, kind: "control", op: "shutdown" });
        }
      }
    }
  });
  return { directory, events, holdPath, releasePath, exit,
    invocations: () => existsSync(invocationsPath) ? readFileSync(invocationsPath, "utf8").trim().split("\n").map(line => JSON.parse(line) as string[]) : [],
    async untilHeld() {
      while (!existsSync(holdPath) && !exited) await new Promise(resolveWait => setTimeout(resolveWait, 10));
      return existsSync(holdPath);
    },
    stderr: () => stderr,
    release: () => writeFileSync(releasePath, "finish naturally"),
  };
}

function evaluationControl(runId: string, providerConfig: Record<string, unknown>, civId = "Rome") {
  return { protocol: 1, kind: "control", op: "run_turn", runId, provider: "codex", providerConfig: { compactAfterTurn: true, ...providerConfig },
    binding: { matchId: "copied-match", timelineId: "copied-timeline", civId, runId, authorityEpoch: 1 },
    profile: { displayName: civId, personality: "", modelLabel: "fixture" },
    context: { rulesetName: "Vanilla", protocolVersion: 1, civId, turnSerial: 1, session: { arm: "persistent", age: 0 } }, guidance: [] };
}

describe("opt-in evaluation session driver", () => {
  it("forks once, waits for full post-turn completion, and then resumes only the new session", async () => {
    const fixture = evaluationFixture([evaluationControl("fork", { forkFromSessionId: "live-source" }),
      evaluationControl("resume", { resumeId: "new-evaluation-session" })]);
    try {
      expect(await fixture.untilHeld(), fixture.stderr()).toBe(true);
      expect(fixture.events.some(event => event.event === "run_completed")).toBe(false);
      expect(fixture.invocations()).toHaveLength(1);
    } finally { fixture.release(); await fixture.exit; }
    const invocations = fixture.invocations();
    expect(invocations).toHaveLength(2);
    expect(invocations[0].slice(-4)).toEqual(["fork", expect.stringMatching(/^--image=.*overview-fork\.png$/), "live-source", expect.any(String)]);
    expect(invocations[1].slice(-4)).toEqual(["resume", expect.stringMatching(/^--image=.*overview-resume\.png$/), "new-evaluation-session", expect.any(String)]);
    expect(invocations.every(argv => argv.includes("model_post_turn_compact_threshold_percent=1"))).toBe(true);
    const metadata = readdirSync(join(fixture.directory, "providers")).map(name =>
      JSON.parse(readFileSync(join(fixture.directory, "providers", name, "meta.json"), "utf8")) as Record<string, unknown>);
    expect(metadata.find(meta => meta.runId === "fork")).toMatchObject({ resumed: false, forkedFromSessionId: "live-source", sessionId: "new-evaluation-session" });
    expect(metadata.find(meta => meta.runId === "resume")).toMatchObject({ resumed: true, sessionId: "new-evaluation-session" });
  }, 30000);

  it("refuses to admit a live source ID as a resume before spawning a provider", async () => {
    const fixture = evaluationFixture([evaluationControl("unsafe-resume", { resumeId: "live-source" })]);
    fixture.release();
    await fixture.exit;
    expect(fixture.invocations()).toHaveLength(0);
    expect(fixture.events.find(event => event.event === "run_failed")?.payload).toMatchObject({ reason: expect.stringMatching(/source|fork|session/i) });
  }, 30000);

  it("rejects an emitted original ID so it cannot become a later persistent resume", async () => {
    const fixture = evaluationFixture([evaluationControl("fork", { forkFromSessionId: "live-source" }),
      evaluationControl("unsafe-resume", { resumeId: "live-source" })], "live-source");
    fixture.release();
    await fixture.exit;
    expect(fixture.invocations()).toHaveLength(1);
    expect(fixture.events.filter(event => event.event === "run_failed")).toHaveLength(2);
    expect(fixture.events.some(event => event.event === "session_bound")).toBe(false);
  }, 30000);

  it("rejects a changed emitted resume ID and refuses to spawn on its later reuse", async () => {
    const fixture = evaluationFixture([evaluationControl("fork", { forkFromSessionId: "live-source" }),
      evaluationControl("changed", { resumeId: "new-evaluation-session" }),
      evaluationControl("reuse-invalid", { resumeId: "changed-evaluation-session" })], "new-evaluation-session",
    { emittedSessions: ["new-evaluation-session", "changed-evaluation-session"] });
    fixture.release();
    await fixture.exit;
    expect(fixture.invocations()).toHaveLength(2);
    expect(fixture.events.filter(event => event.event === "session_bound").map(event => event.runId)).toEqual(["fork"]);
    expect(fixture.events.filter(event => event.event === "run_failed").map(event => event.runId)).toEqual(["changed", "reuse-invalid"]);
    expect(fixture.events.find(event => event.runId === "changed" && event.event === "run_failed")?.payload)
      .toMatchObject({ reason: expect.stringMatching(/invalid or unowned evaluation session/) });
  }, 30000);

  it("rejects another civilization's emitted session ID and refuses its later resume", async () => {
    const fixture = evaluationFixture([evaluationControl("rome-fork", { forkFromSessionId: "live-source" }),
      evaluationControl("greece-collision", { forkFromSessionId: "greece-source" }, "Greece"),
      evaluationControl("greece-reuse", { resumeId: "new-evaluation-session" }, "Greece")], "new-evaluation-session",
    { sourceSessions: { Rome: "live-source", Greece: "greece-source" },
      emittedSessions: ["new-evaluation-session", "new-evaluation-session"] });
    fixture.release();
    await fixture.exit;
    expect(fixture.invocations()).toHaveLength(2);
    expect(fixture.events.filter(event => event.event === "session_bound").map(event => event.runId)).toEqual(["rome-fork"]);
    expect(fixture.events.filter(event => event.event === "run_failed").map(event => event.runId)).toEqual(["greece-collision", "greece-reuse"]);
    expect(fixture.events.find(event => event.runId === "greece-collision" && event.event === "run_failed")?.payload)
      .toMatchObject({ reason: expect.stringMatching(/invalid or unowned evaluation session/) });
  }, 30000);
});

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
    expect(collected.events.find(e => e["event"] === "run_started")?.["payload"])
      .toMatchObject({ provider: "scripted", civ: "civ", strategyPolicyVersion: "victory-progress-v3" });
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
