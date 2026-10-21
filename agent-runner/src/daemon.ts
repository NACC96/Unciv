/**
 * Supervisor daemon entrypoint (AI_AGENT_PLAN.md §3.1, §7).
 *
 * The desktop launches one daemon per agent match; they speak the private
 * versioned JSON-over-stdio control protocol (engine-protocol.ts):
 * stdout carries engine requests + turn events, stdin carries engine
 * responses + run_turn/cancel_run/shutdown controls, stderr carries human
 * logs only.
 *
 * Provider registry: scripted runs anywhere (tests); codex/claude/opencode
 * use the W1-proven spawn builders (their config file/dir setup is owned by
 * the desktop, which passes absolute paths through providerConfig).
 */
import { GAME_TOOL_ALLOWLIST, TOOL_TIMEOUT_SEC, codexSpawn, claudeSpawn, opencodeSpawn, type SpawnSpec } from "./provider-spawn.js";
import { parseClaudeStream, parseCodexStream, parseOpencodeStream, type NormalizedRun } from "./provider-events.js";
import { Supervisor, type SpawnExtras } from "./supervisor.js";
import { STRATEGY_POLICY_VERSION } from "./strategy-policy.js";
import { StdioEngineBackend } from "./stdio-engine.js";
import { CANCELLED_DETAIL, parseInbound, encodeFrame, type RunTurnControl } from "./engine-protocol.js";
import { mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export function buildSpawnForProvider(
  provider: RunTurnControl["provider"],
  prompt: string,
  resumeId: string | undefined,
  serverUrl: string,
  bearer: string,
  config: RunTurnControl["providerConfig"],
  scratchCwd: string,
  extras: SpawnExtras = {},
): { spec: SpawnSpec; env: NodeJS.ProcessEnv } {
  switch (provider) {
    case "codex":
      return {
        spec: codexSpawn({
          prompt,
          resumeId,
          mcpUrl: serverUrl,
          bearerEnvVar: "GAME_MCP_BEARER",
          cwd: scratchCwd,
          sandboxDangerous: true,
          model: config.model,
          reasoning: config.reasoning,
          compactAfterTurn: config.compactAfterTurn,
          imagePath: extras.imagePath,
        }),
        // Bearer travels by environment; only the var NAME is in argv/config.
        env: { GAME_MCP_BEARER: bearer },
      };
    case "claude": {
      // No env indirection exists: bearer VALUE goes into a temp MCP config
      // (0600, process scratch, never logged). Same pattern as the W1 probe.
      const configPath = join(
        scratchCwd,
        `claude-mcp-${Math.random().toString(36).slice(2, 10)}.json`,
      );
      writeFileSync(
        configPath,
        JSON.stringify({ mcpServers: { game: { type: "http", url: serverUrl, headers: { Authorization: `Bearer ${bearer}` } } } }),
        { mode: 0o600 },
      );
      return {
        spec: claudeSpawn({
          prompt,
          resumeId,
          mcpConfigPath: configPath,
          cwd: scratchCwd,
          model: config.model,
          reasoning: config.reasoning,
        }),
        env: {},
      };
    }
    case "opencode": {
      writeFileSync(
        join(scratchCwd, "opencode.json"),
        JSON.stringify({
          mcp: {
            game: {
              type: "remote", url: serverUrl, enabled: true, oauth: false,
              headers: { Authorization: "Bearer {env:OPENCODE_GAME_MCP_BEARER}" }, timeout: TOOL_TIMEOUT_SEC * 1000,
            },
          },
          tools: { "*": false, ...Object.fromEntries(GAME_TOOL_ALLOWLIST.map(name => [`game_${name.replaceAll(".", "_")}`, true])) },
          share: "disabled",
          autoupdate: false,
        }),
      );
      return {
        spec: opencodeSpawn({
          prompt,
          resumeId,
          dir: scratchCwd,
          model: config.model,
          reasoning: config.reasoning,
        }),
        env: { OPENCODE_GAME_MCP_BEARER: bearer },
      };
    }
    case "scripted": {
      const plan = typeof config.extra?.["plan"] === "string" ? (config.extra["plan"] as string) : "observe,act,end";
      const unitId = typeof config.extra?.["unitId"] === "number" ? String(config.extra["unitId"]) : "17";
      const destX = typeof config.extra?.["destX"] === "number" ? String(config.extra["destX"]) : "2";
      const destY = typeof config.extra?.["destY"] === "number" ? String(config.extra["destY"]) : "-1";
      return {
        spec: {
          executable: process.execPath,
          argv: [
            join(new URL(".", import.meta.url).pathname, "..", "probes", "scripted-provider.mjs"),
            "--mcp-url", serverUrl,
            "--bearer", bearer,
            ...(resumeId ? ["--resume", resumeId] : []),
            "--plan", plan,
            "--unit-id", unitId,
            "--dest-x", destX,
            "--dest-y", destY,
          ],
          cwd: scratchCwd,
        },
        env: {},
      };
    }
  }
}

/**
 * Codex (developer_instructions) and Claude (--append-system-prompt) receive
 * GAME_INSTRUCTIONS on every invocation, so their turn prompt omits the copy.
 * OpenCode has no system-prompt flag here: a fresh session needs it in the prompt.
 */
export function promptCarriesStaticInstructions(provider: RunTurnControl["provider"], resumeId: string | undefined): boolean {
  if (provider === "codex" || provider === "claude") return false;
  if (provider === "opencode") return !resumeId;
  return true;
}

/**
 * Codex alone attaches image files to its prompt and compacts after a turn
 * (other providers still read map pictures through game.map_image).
 */
export function providerTurnFeatures(provider: RunTurnControl["provider"], config: RunTurnControl["providerConfig"]):
  { attachMapImage: boolean; releaseAfterEndTurn: boolean } {
  const codex = provider === "codex";
  return { attachMapImage: codex, releaseAfterEndTurn: codex && config.compactAfterTurn === true };
}

/**
 * A session compacting after its turn is resumed only once compaction ends.
 * Other leaders' turns normally hide it; a lone leader waits at most this long
 * before the compaction is abandoned and the uncompacted session resumes.
 */
export const COMPACTION_WAIT_MS = 4 * 60 * 1000;

/** A provider process still compacting its session after a released turn. */
export interface Compaction { done: Promise<unknown>; kill(): void }

/**
 * Waits until [sessionId] has no compaction in [compactions], abandoning one that outlasts
 * [waitMs]. A cancel ends the wait at once ("cancelled") and leaves the compaction running,
 * so the session stays excluded until it finishes.
 */
export async function settleCompaction(
  compactions: Map<string, Compaction>,
  sessionId: string | undefined,
  signal: AbortSignal,
  waitMs = COMPACTION_WAIT_MS,
): Promise<"settled" | "cancelled"> {
  const compaction = sessionId ? compactions.get(sessionId) : undefined;
  if (!compaction) return "settled";
  let timer: NodeJS.Timeout | undefined;
  let onAbort: (() => void) | undefined;
  const result = await Promise.race([
    compaction.done.then(() => "finished" as const, () => "finished" as const),
    new Promise<"timeout">(resolve => { timer = setTimeout(() => resolve("timeout"), waitMs); }),
    new Promise<"cancelled">(resolve => {
      onAbort = () => resolve("cancelled");
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    }),
  ]);
  clearTimeout(timer);
  signal.removeEventListener("abort", onAbort!);
  if (result === "cancelled") return result;
  if (result === "timeout") {
    process.stderr.write(`Session ${sessionId} still compacting after ${waitMs}ms; resuming it uncompacted.\n`);
    compaction.kill();
    await compaction.done.catch(() => undefined);
  }
  if (compactions.get(sessionId!) === compaction) compactions.delete(sessionId!);
  return "settled";
}

/** The leader's closing summary seeds the next fresh session; bounded so it cannot crowd the prompt. */
export const MAX_TURN_SUMMARY_CHARS = 2000;

function parseForProvider(provider: RunTurnControl["provider"]): (jsonl: string) => NormalizedRun {
  switch (provider) {
    case "codex":
      return parseCodexStream;
    case "claude":
      return parseClaudeStream;
    case "opencode":
      return parseOpencodeStream;
    case "scripted":
      return (stream: string): NormalizedRun => {
        const run: NormalizedRun = {
          provider: "scripted", sessionId: null, model: "scripted-0", toolCalls: [],
          finalText: null, usage: null, completed: false, errorText: null, advertisedTools: null,
        };
        for (const line of stream.split("\n")) {
          if (!line.trim()) continue;
          try {
            const e = JSON.parse(line) as Record<string, unknown>;
            if (e["kind"] !== "scripted") continue;
            if (typeof e["sessionId"] === "string" && !run.sessionId) run.sessionId = e["sessionId"] as string;
            if (e["event"] === "tool") {
              run.toolCalls.push({ tool: String(e["tool"]), input: e["input"] ?? null, completed: true, errorText: null });
            }
            if (e["event"] === "run_completed") run.completed = true;
          } catch {
            // ignore malformed lines
          }
        }
        return run;
      };
  }
}

export async function main(): Promise<void> {
  const backend = new StdioEngineBackend(
    (line) => {
      process.stdout.write(line + "\n");
    },
    30000,
    // Actor identity is per-run; rebound for every run_turn below.
    () => currentActorCiv,
  );
  let currentActorCiv = "";
  const scratch = join(tmpdir(), `unciv-supervisor-${process.pid}`);
  mkdirSync(scratch, { recursive: true });

  let shuttingDown = false;
  let activeTurn: Promise<void> | null = null;
  // Sessions still compacting after a released turn, by provider session ID.
  const compactions = new Map<string, Compaction>();
  // The only run cancel_run may touch; cleared with activeTurn.
  let activeRun: { runId: string; cancel: AbortController } | null = null;

  const finish = () => {
    backend.disconnect();
    // An unfinished compaction only leaves its session uncompacted.
    for (const compaction of compactions.values()) compaction.kill();
    process.exit(0);
  };
  const requestShutdown = (transportDisconnected = false) => {
    // EOF means the desktop is gone (exit or crash) and no engine response can
    // arrive, so the provider could only fail every remaining call: stop it now.
    // A shutdown control can still be answered, so that drains the turn instead.
    if (transportDisconnected) {
      backend.disconnect();
      activeRun?.cancel.abort();
    }
    if (shuttingDown) return;
    shuttingDown = true;
    if (activeTurn) void activeTurn.then(finish, finish);
    else finish();
  };
  process.on("SIGTERM", () => { requestShutdown(); });

  process.stdout.write(JSON.stringify({ protocol: 1, kind: "ready" }) + "\n");

  let buffer = "";
  process.stdin.setEncoding("utf-8");
  process.stdin.on("data", (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) handleLine(line);
  });
  process.stdin.on("end", () => {
    requestShutdown(true);
  });

  function handleLine(line: string): void {
    const frame = parseInbound(line);
    if (!frame) return;
    if (frame.kind === "engine_response") {
      backend.feedLine(line);
      return;
    }
    if (frame.kind === "control" && frame.op === "shutdown") {
      requestShutdown();
      return;
    }
    if (frame.kind === "control" && frame.op === "cancel_run") {
      // Stale or unknown runIds are ignored silently; the outcome frame of the
      // cancelled run is the only reply.
      if (activeRun?.runId === frame.runId) activeRun.cancel.abort();
      return;
    }
    if (frame.kind === "control" && frame.op === "run_turn") {
      if (shuttingDown || activeTurn) {
        process.stdout.write(
          encodeFrame({
            protocol: 1,
            kind: "turn_event",
            runId: frame.runId,
            event: "run_failed",
            payload: { reason: shuttingDown ? "supervisor shutting down" : "turn already in flight" },
          }) + "\n",
        );
        return;
      }
      const run = { runId: frame.runId, cancel: new AbortController() };
      activeRun = run;
      activeTurn = runTurn(frame, run.cancel.signal).finally(() => {
        activeTurn = null;
        if (activeRun === run) activeRun = null;
      });
    }
  }

  async function runTurn(control: RunTurnControl, signal: AbortSignal): Promise<void> {
    currentActorCiv = control.binding.civId;
    const emit = (event: "run_started" | "session_bound" | "tool_activity" | "run_completed" | "run_failed", payload: Record<string, unknown>) => {
      process.stdout.write(encodeFrame({ protocol: 1, kind: "turn_event", runId: control.runId, event, payload }) + "\n");
    };
    emit("run_started", { provider: control.provider, civ: control.binding.civId, strategyPolicyVersion: STRATEGY_POLICY_VERSION });
    const supervisor = new Supervisor(
      (prompt, resumeId, serverUrl, bearer, extras) => {
        const built = buildSpawnForProvider(control.provider, prompt, resumeId, serverUrl, bearer, control.providerConfig, scratch, extras);
        return { ...built, spec: { ...built.spec, context: { ...control.binding, turnSerial: control.context.turnSerial,
          provider: control.provider, model: control.providerConfig.model, reasoning: control.providerConfig.reasoning,
          resumed: Boolean(resumeId),
          ...(control.context.session ? { sessionArm: control.context.session.arm, sessionAge: control.context.session.age } : {}) } } };
      },
      parseForProvider(control.provider),
    );
    try {
      // A takeover must not wait out the previous turn's compaction.
      if (await settleCompaction(compactions, control.providerConfig.resumeId, signal) === "cancelled") {
        emit("run_failed", { status: "cancelled", revision: null, turnEnded: false, detail: CANCELLED_DETAIL, toolCalls: [] });
        return;
      }
      const outcome = await supervisor.runLeaderTurn({
        ...providerTurnFeatures(control.provider, control.providerConfig),
        engine: backend,
        binding: control.binding,
        profile: {
          displayName: control.profile.displayName,
          personality: control.profile.personality,
          modelLabel: control.profile.modelLabel,
        },
        context: control.context,
        guidance: control.guidance,
        resumeId: control.providerConfig.resumeId,
        cwd: scratch,
        includeStaticInstructions: promptCarriesStaticInstructions(control.provider, control.providerConfig.resumeId),
        signal,
      });
      if (outcome.run.sessionId) {
        emit("session_bound", { sessionId: outcome.run.sessionId });
      }
      if (outcome.background) {
        const sessionId = outcome.run.sessionId;
        const background = outcome.background;
        if (sessionId) {
          compactions.set(sessionId, background);
          void background.done.finally(() => { if (compactions.get(sessionId) === background) compactions.delete(sessionId); });
        } else background.kill(); // An unbound session can never be resumed.
      }
      const cancelled = outcome.status === "cancelled";
      emit(outcome.status === "failed" || cancelled ? "run_failed" : "run_completed", {
        status: outcome.status,
        revision: outcome.revision,
        turnEnded: outcome.turnEnded,
        detail: outcome.detail,
        toolCalls: outcome.run.toolCalls.map((t) => t.tool),
        // A cut-off provider wrote no closing summary; partial text must not seed the next session.
        ...(!cancelled && outcome.run.finalText?.trim() ? { summary: outcome.run.finalText.trim().slice(0, MAX_TURN_SUMMARY_CHARS) } : {}),
      });
    } catch (error) {
      if (signal.aborted) {
        emit("run_failed", { status: "cancelled", revision: null, turnEnded: false, detail: CANCELLED_DETAIL, toolCalls: [] });
      } else {
        emit("run_failed", { reason: error instanceof Error ? error.message : String(error) });
      }
    }
  }
}

const invokedDirectly = process.argv[1]?.endsWith("daemon.js") || process.argv[1]?.endsWith("daemon.ts");
if (invokedDirectly) {
  void main();
}
