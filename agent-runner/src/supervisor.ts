/**
 * Supervisor turn loop (AI_AGENT_PLAN.md §3.1, §7, §9).
 *
 * Owns: per-invocation MCP server (fresh bearer), provider child lifecycle,
 * prompt assembly, normalized event collection, turn-completion
 * classification, and operational guardrails (call caps, invalid streaks).
 * Owns NOT: engine state (IGameService bridge, implemented by the desktop in
 * production and the fake here), provider model/auth storage, match run
 * state (run-controls.ts transition table, driven by the desktop UI).
 *
 * Provider output and JSONL logs are evidence; engine receipts govern.
 */
import { spawn, type ChildProcess } from "node:child_process";
import type { IGameService } from "./fake-game-mcp.js";
import { startGameMcpServer, type RunBinding } from "./game-mcp-server.js";
import { buildTurnPrompt } from "./turn-prompt.js";
import type { NormalizedRun } from "./provider-events.js";
import type { SpawnSpec } from "./provider-spawn.js";

export interface LeaderProfile {
  displayName: string;
  personality: string;
  modelLabel: string;
}

export interface TurnPromptContext {
  rulesetName: string;
  protocolVersion: number;
  civId: string;
  turnSerial: number;
  continuityNote?: string;
}

export type TurnOutcomeStatus =
  | "completed_turn"
  | "incomplete_turn"
  | "guard_paused"
  | "failed";

export interface TurnOutcome {
  status: TurnOutcomeStatus;
  /** Engine revision after the turn (unchanged when nothing committed). */
  revision: number;
  turnEnded: boolean;
  run: NormalizedRun;
  detail: string;
}

export interface ProviderRunner {
  spawn(spec: SpawnSpec, env: NodeJS.ProcessEnv): RunningChild;
}

export interface RunningChild {
  pid: number | undefined;
  stdoutLines(onLine: (line: string) => void): void;
  stderrText(): string;
  wait(): Promise<{ code: number | null; signal: string | null }>;
  kill(): void;
}

export class NodeRunner implements ProviderRunner {
  spawn(spec: SpawnSpec, env: NodeJS.ProcessEnv): RunningChild {
    const child: ChildProcess = spawn(spec.executable, spec.argv, {
      cwd: spec.cwd,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stderr = "";
    child.stderr?.on("data", (c: Buffer) => {
      stderr += c.toString("utf-8");
    });
    return {
      pid: child.pid,
      stdoutLines(onLine) {
        let pending = "";
        child.stdout?.on("data", (c: Buffer) => {
          pending += c.toString("utf-8");
          const parts = pending.split("\n");
          pending = parts.pop() ?? "";
          for (const line of parts) onLine(line);
        });
      },
      stderrText: () => stderr,
      wait: () =>
        new Promise((resolve) => {
          child.on("close", (code, signal) => resolve({ code, signal: signal?.toString() ?? null }));
        }),
      kill: () => {
        child.kill("SIGKILL");
      },
    };
  }
}

export class Supervisor {
  constructor(
    private readonly buildSpawn: (
      prompt: string,
      resumeId: string | undefined,
      serverUrl: string,
      bearer: string,
    ) => SpawnSpec,
    private readonly parseStream: (jsonl: string) => NormalizedRun,
    private readonly runner: ProviderRunner = new NodeRunner(),
    private readonly maxToolCallsPerTurn = 500,
    private readonly turnTimeoutMs = 10 * 60 * 1000,
  ) {}

  async runLeaderTurn(args: {
    engine: IGameService;
    binding: RunBinding;
    profile: LeaderProfile;
    context: TurnPromptContext;
    guidance: string[];
    resumeId?: string;
    cwd: string;
    extraEnv?: NodeJS.ProcessEnv;
  }): Promise<TurnOutcome> {
    const revisionBefore = args.engine.revision;
    const prompt = buildTurnPrompt({
      displayName: args.profile.displayName,
      personality: args.profile.personality,
      rulesetName: args.context.rulesetName,
      protocolVersion: args.context.protocolVersion,
      civId: args.context.civId,
      turnSerial: args.context.turnSerial,
      stateRevision: revisionBefore,
      notes: args.engine.notes,
      recentEvents: args.engine.observe().recentEvents,
      pendingDecisions: args.engine.observe().requiredDecisions.map((d) => ({ id: d, type: "decision", summary: d })),
      guidance: args.guidance,
      continuityNote: args.resumeId ? `Resumed provider session ${args.resumeId}.` : args.context.continuityNote,
    });

    const server = await startGameMcpServer(args.engine, args.binding);
    try {
      const spec = this.buildSpawn(prompt, args.resumeId, server.url, server.bearer);
      const child = this.runner.spawn(spec, { ...process.env, ...args.extraEnv });
      const lines: string[] = [];
      let toolCallCount = 0;
      let guardHit: string | null = null;
      const toolPattern = /"(?:tool|name)"\s*:\s*"[^"]*game[^"]*"/;
      const done = child.wait();
      child.stdoutLines((line) => {
        lines.push(line);
        if (!guardHit && toolPattern.test(line)) {
          toolCallCount++;
          if (toolCallCount > this.maxToolCallsPerTurn) {
            guardHit = `tool call cap (${this.maxToolCallsPerTurn}) reached; pausing at a safe boundary`;
            child.kill();
          }
        }
      });
      const timeout = setTimeout(() => {
        if (!guardHit) {
          guardHit = "turn timeout; collecting evidence and pausing";
          child.kill();
        }
      }, this.turnTimeoutMs);
      const exit = await done;
      clearTimeout(timeout);

      const run = this.parseStream(lines.join("\n"));
      const revision = args.engine.revision;
      if (guardHit) {
        return { status: "guard_paused", revision, turnEnded: false, run, detail: guardHit };
      }
      // Engine state governs: end_turn committed means a completed turn even
      // if the provider's own stream is ambiguous; without it the turn is
      // incomplete (moves retained) or failed.
      if (args.engine.turnEnded) {
        return { status: "completed_turn", revision, turnEnded: true, run, detail: "engine committed end_turn" };
      }
      if (exit.code === 0 && !run.errorText) {
        return {
          status: "incomplete_turn",
          revision,
          turnEnded: false,
          run,
          detail: "provider exited cleanly without end_turn; committed moves retained",
        };
      }
      return {
        status: "failed",
        revision,
        turnEnded: false,
        run,
        detail: `provider failed (code=${exit.code} signal=${exit.signal}): ${run.errorText ?? child.stderrText().slice(-300)}`,
      };
    } finally {
      await server.close();
    }
  }

}
