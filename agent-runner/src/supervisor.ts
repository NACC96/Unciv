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
import { ActivityNormalizer, redactImageLine, type ActivityEntry } from "./activity.js";
import { StringDecoder } from "node:string_decoder";
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join } from "node:path";
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
  /** Committed engine receipts remain visible even if the provider omits its tool-result stream. */
  recordActivity?(entry: ActivityEntry): void;
  /** Turn completion is classified from engine commits after the process exits. */
  recordOutcome?(outcome: TurnOutcome): void;
}

function formatRunDuration(durationMs: number): string {
  const seconds = Math.floor(durationMs / 1000);
  const minutes = Math.floor(seconds / 60);
  const remainder = String(seconds % 60).padStart(2, "0");
  return minutes < 60 ? `${String(minutes).padStart(2, "0")}:${remainder}`
    : `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, "0")}:${remainder}`;
}

export class NodeRunner implements ProviderRunner {
  constructor(
    private readonly logRoot = process.env["UNCIV_AGENT_LOG_DIR"] ?? join(tmpdir(), "unciv-agent-runs"),
    private readonly monotonicNow: () => number = () => performance.now(),
  ) {}

  spawn(spec: SpawnSpec, env: NodeJS.ProcessEnv): RunningChild {
    mkdirSync(this.logRoot, { recursive: true, mode: 0o700 });
    const runDir = mkdtempSync(join(this.logRoot, "run-"));
    const startedAtMonotonic = this.monotonicNow();
    const child: ChildProcess = spawn(spec.executable, spec.argv, {
      cwd: spec.cwd,
      // GUI CLI discovery may find a Node script without node on PATH.
      env: { ...env, PATH: [dirname(process.execPath), env["PATH"] ?? ""].filter(Boolean).join(delimiter) },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const metadata: Record<string, unknown> = {
      pid: child.pid,
      ...spec.context,
      command: [spec.executable, ...spec.argv.map((arg, i) => spec.argv[i - 1] === "--bearer" ? "[redacted]" : arg)],
      cwd: spec.cwd,
      startedAt: new Date().toISOString(),
      sessionId: null,
    };
    const saveMetadata = () => writeFileSync(join(runDir, "meta.json"), JSON.stringify(metadata, null, 2), { mode: 0o600 });
    saveMetadata();
    writeFileSync(join(runDir, "pid"), `${child.pid}\n`, { mode: 0o600 });
    writeFileSync(join(runDir, "events.jsonl"), "", { mode: 0o600 });
    writeFileSync(join(runDir, "stderr.log"), "", { mode: 0o600 });
    const activityPath = join(runDir, "activity.jsonl");
    const normalizer = new ActivityNormalizer();
    const appendActivity = (entry: unknown) => appendFileSync(activityPath, JSON.stringify(entry) + "\n", { mode: 0o600 });
    appendActivity({ id: "run-start", kind: "system", title: "Provider started", text: JSON.stringify(metadata, null, 2), timestamp: metadata["startedAt"] });
    const eventDecoder = new StringDecoder("utf8");
    let eventBuffer = "";
    child.stdout?.on("data", (c: Buffer) => {
      eventBuffer += eventDecoder.write(c);
      const events = eventBuffer.split("\n");
      eventBuffer = events.pop() ?? "";
      for (const rawLine of events) {
        const line = redactImageLine(rawLine);
        appendFileSync(join(runDir, "events.jsonl"), line + "\n", { mode: 0o600 });
        if (line.trim()) for (const entry of normalizer.fromLine(line)) appendActivity(entry);
        try {
          const event = JSON.parse(line) as Record<string, unknown>;
          const sessionId = event["thread_id"] ?? event["session_id"] ?? event["sessionID"] ?? event["sessionId"];
          if (!metadata["sessionId"] && typeof sessionId === "string") {
            metadata["sessionId"] = sessionId;
            saveMetadata();
          }
        } catch { /* Partial/non-JSON output remains in the raw stream log. */ }
      }
    });
    child.on("close", (code, signal) => {
      const durationMs = Math.max(0, this.monotonicNow() - startedAtMonotonic);
      Object.assign(metadata, { endedAt: new Date().toISOString(), exitCode: code, signal, durationMs });
      saveMetadata();
      if (eventBuffer.trim()) {
        const line = redactImageLine(eventBuffer);
        appendFileSync(join(runDir, "events.jsonl"), line + "\n", { mode: 0o600 });
        for (const entry of normalizer.fromLine(line)) appendActivity(entry);
      }
      flushStderr(true);
      const status = code === 0 ? "exited" : "failed";
      const duration = formatRunDuration(durationMs);
      appendActivity({ id: "run-end", kind: "run", status, durationMs, title: `Provider ${status} in ${duration}`,
        summary: `Duration ${duration}`, text: JSON.stringify(metadata, null, 2), timestamp: metadata["endedAt"] });
    });
    process.stderr.write(`Provider pid=${child.pid} cwd=${spec.cwd} logs=${runDir}\n`);
    const stderrDecoder = new StringDecoder("utf8");
    let stderr = "", stderrBuffer = "";
    const flushStderr = (final = false) => {
      const parts = stderrBuffer.split("\n");
      stderrBuffer = final ? "" : parts.pop() ?? "";
      for (const part of parts) {
        if (!part && final) continue;
        const decoded = redactImageLine(part) + "\n";
        stderr += decoded;
        appendFileSync(join(runDir, "stderr.log"), decoded, { mode: 0o600 });
        appendActivity({ id: `stderr-${Date.now()}-${Math.random()}`, kind: "diagnostic", title: "Provider diagnostic", text: decoded, timestamp: new Date().toISOString() });
      }
    };
    child.stderr?.on("data", (c: Buffer) => { stderrBuffer += stderrDecoder.write(c); flushStderr(); });
    return {
      pid: child.pid,
      recordActivity: appendActivity,
      recordOutcome(outcome) {
        Object.assign(metadata, { turnOutcome: outcome.status, turnDetail: outcome.detail });
        saveMetadata();
        const label = { completed_turn: "completed", incomplete_turn: "incomplete", guard_paused: "paused", failed: "failed" }[outcome.status];
        const durationMs = metadata["durationMs"] as number;
        const duration = formatRunDuration(durationMs);
        appendActivity({ id: "run-end", kind: "run", status: outcome.status, durationMs,
          title: `Provider ${label} in ${duration}`, summary: `Duration ${duration}. ${outcome.detail}`,
          text: JSON.stringify(metadata, null, 2), timestamp: metadata["endedAt"] });
      },
      stdoutLines(onLine) {
        const decoder = new StringDecoder("utf8");
        let pending = "";
        child.stdout?.on("data", (c: Buffer) => {
          pending += decoder.write(c);
          const parts = pending.split("\n");
          pending = parts.pop() ?? "";
          for (const line of parts) onLine(redactImageLine(line));
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
    ) => { spec: SpawnSpec; env: NodeJS.ProcessEnv },
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
    const preFlight = await args.engine.observe();
    const revisionBefore = preFlight.revision;
    const storedNotes = await args.engine.readNotes();
    const prompt = buildTurnPrompt({
      displayName: args.profile.displayName,
      personality: args.profile.personality,
      rulesetName: args.context.rulesetName,
      protocolVersion: args.context.protocolVersion,
      civId: args.context.civId,
      turnSerial: args.context.turnSerial,
      stateRevision: revisionBefore,
      notes: storedNotes.notes,
      recentEvents: preFlight.recentEvents,
      pendingDecisions: preFlight.requiredDecisions.map((d) => typeof d === "string"
        ? { id: d, type: "decision", summary: d }
        : { id: d.decisionId, type: d.decisionType, summary: JSON.stringify({ sourceRef: d.sourceRef, classification: d.classification, options: d.options }) }),
      guidance: args.guidance,
      inbox: preFlight.inbox,
      diplomaticHistory: preFlight.diplomaticHistory,
      continuityNote: args.resumeId ? `Resumed provider session ${args.resumeId}.` : args.context.continuityNote,
    });

    let currentChild: RunningChild | undefined;
    const server = await startGameMcpServer(args.engine, args.binding, entry => currentChild?.recordActivity?.(entry));
    try {
      const built = this.buildSpawn(prompt, args.resumeId, server.url, server.bearer);
      // Invocation credentials must win over inherited or supplemental env.
      const child = this.runner.spawn({ ...built.spec, context: { ...built.spec.context,
        matchId: args.binding.matchId, civId: args.binding.civId, turnSerial: args.context.turnSerial, runId: args.binding.runId } }, { ...process.env, ...args.extraEnv, ...built.env });
      currentChild = child;
      const lines: string[] = [];
      let toolCallCount = 0;
      let guardHit: string | null = null;
      const toolPattern = /"(?:tool|name)"\s*:\s*"[^"]*game[^"]*"/;
      const done = child.wait();
      child.stdoutLines((line) => {
        lines.push(redactImageLine(line));
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
          process.stderr.write(`Provider pid=${child.pid} is still active after ${this.turnTimeoutMs}ms; inspect its logs and process before taking action.\n`);
        }
      }, this.turnTimeoutMs);
      const exit = await done;
      clearTimeout(timeout);

      const run = this.parseStream(lines.join("\n"));
      const postFlight = await args.engine.observe().catch(() => null);
      const revision = postFlight?.revision ?? revisionBefore;
      const endsBefore = preFlight.endTurnsCommitted;
      const endsAfter = postFlight?.endTurnsCommitted ?? endsBefore;
      const turnEnded = endsAfter > endsBefore;
      const finish = (outcome: TurnOutcome): TurnOutcome => {
        child.recordOutcome?.(outcome);
        return outcome;
      };
      if (guardHit) {
        return finish({ status: "guard_paused", revision, turnEnded, run, detail: guardHit });
      }
      // Engine state governs: a completed end-turn commit during THIS
      // invocation means a completed turn even if the provider's own stream
      // is ambiguous; otherwise the turn is incomplete (moves retained) or
      // failed. Counter-based (a sticky boolean lies after the first turn).
      if (turnEnded) {
        return finish({ status: "completed_turn", revision, turnEnded: true, run, detail: "engine committed end_turn" });
      }
      if (exit.code === 0 && !run.errorText) {
        return finish({
          status: "incomplete_turn",
          revision,
          turnEnded: false,
          run,
          detail: "provider exited cleanly without end_turn; committed moves retained",
        });
      }
      return finish({
        status: "failed",
        revision,
        turnEnded: false,
        run,
        detail: `provider failed (code=${exit.code} signal=${exit.signal}): ${run.errorText ?? child.stderrText().slice(-300)}`,
      });
    } finally {
      await server.close();
    }
  }

}
