/**
 * Supervisor daemon entrypoint (AI_AGENT_PLAN.md §3.1, §7).
 *
 * The desktop launches one daemon per agent match; they speak the private
 * versioned JSON-over-stdio control protocol (engine-protocol.ts):
 * stdout carries engine requests + turn events, stdin carries engine
 * responses + run_turn/shutdown controls, stderr carries human logs only.
 *
 * Provider registry: scripted runs anywhere (tests); codex/claude/opencode
 * use the W1-proven spawn builders (their config file/dir setup is owned by
 * the desktop, which passes absolute paths through providerConfig).
 */
import { GAME_TOOL_ALLOWLIST, codexSpawn, claudeSpawn, opencodeSpawn, type SpawnSpec } from "./provider-spawn.js";
import { parseClaudeStream, parseCodexStream, parseOpencodeStream, type NormalizedRun } from "./provider-events.js";
import { Supervisor } from "./supervisor.js";
import { StdioEngineBackend } from "./stdio-engine.js";
import { parseInbound, encodeFrame, type RunTurnControl } from "./engine-protocol.js";
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
              headers: { Authorization: "Bearer {env:OPENCODE_GAME_MCP_BEARER}" }, timeout: 30000,
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

  const finish = () => {
    backend.disconnect();
    process.exit(0);
  };
  const requestShutdown = (transportDisconnected = false) => {
    // EOF cannot carry further engine responses. A shutdown control can, so
    // keep that channel alive until the current provider finishes normally.
    if (transportDisconnected) backend.disconnect();
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
      activeTurn = runTurn(frame).finally(() => {
        activeTurn = null;
      });
    }
  }

  async function runTurn(control: RunTurnControl): Promise<void> {
    currentActorCiv = control.binding.civId;
    const emit = (event: "run_started" | "session_bound" | "tool_activity" | "run_completed" | "run_failed", payload: Record<string, unknown>) => {
      process.stdout.write(encodeFrame({ protocol: 1, kind: "turn_event", runId: control.runId, event, payload }) + "\n");
    };
    emit("run_started", { provider: control.provider, civ: control.binding.civId });
    const supervisor = new Supervisor(
      (prompt, resumeId, serverUrl, bearer) => {
        const built = buildSpawnForProvider(control.provider, prompt, resumeId, serverUrl, bearer, control.providerConfig, scratch);
        return { ...built, spec: { ...built.spec, context: { ...control.binding, turnSerial: control.context.turnSerial,
          provider: control.provider, model: control.providerConfig.model, reasoning: control.providerConfig.reasoning,
          resumed: Boolean(resumeId),
          ...(control.context.session ? { sessionArm: control.context.session.arm, sessionAge: control.context.session.age } : {}) } } };
      },
      parseForProvider(control.provider),
    );
    try {
      const outcome = await supervisor.runLeaderTurn({
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
      });
      if (outcome.run.sessionId) {
        emit("session_bound", { sessionId: outcome.run.sessionId });
      }
      emit(outcome.status === "failed" ? "run_failed" : "run_completed", {
        status: outcome.status,
        revision: outcome.revision,
        turnEnded: outcome.turnEnded,
        detail: outcome.detail,
        toolCalls: outcome.run.toolCalls.map((t) => t.tool),
        ...(outcome.run.finalText?.trim() ? { summary: outcome.run.finalText.trim().slice(0, MAX_TURN_SUMMARY_CHARS) } : {}),
      });
    } catch (error) {
      emit("run_failed", { reason: error instanceof Error ? error.message : String(error) });
    }
  }
}

const invokedDirectly = process.argv[1]?.endsWith("daemon.js") || process.argv[1]?.endsWith("daemon.ts");
if (invokedDirectly) {
  void main();
}
