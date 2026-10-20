/**
 * Supervisor turn loop (AI_AGENT_PLAN.md §3.1, §7, §9).
 *
 * Owns: per-invocation MCP server (fresh bearer), provider child lifecycle,
 * prompt assembly, normalized event collection, turn-completion
 * classification, operational guardrails (call caps, invalid streaks), and
 * host cancellation of the in-flight provider (takeover).
 * Owns NOT: engine state (IGameService bridge, implemented by the desktop in
 * production and the fake here), provider model/auth storage, match run
 * state (run-controls.ts transition table, driven by the desktop UI).
 *
 * Provider output and JSONL logs are evidence; engine receipts govern.
 */
import { ActivityNormalizer, redactImageLine, type ActivityEntry } from "./activity.js";
import { StringDecoder } from "node:string_decoder";
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { CANCELLED_DETAIL } from "./engine-protocol.js";
import type { IGameService } from "./fake-game-mcp.js";
import { startGameMcpServer, type RunBinding } from "./game-mcp-server.js";
import { buildTurnPrompt } from "./turn-prompt.js";
import { compactObservation } from "./compact-results.js";
import { renderCivMapImage } from "./map-overview.js";
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
  | "failed"
  /** Host cut the run off (takeover): provider killed or never spawned. */
  | "cancelled";

export interface TurnOutcome {
  status: TurnOutcomeStatus;
  /**
   * Set when the turn was released before the provider exited (post-turn
   * session compaction). The provider keeps running without game tools;
   * resume its session only after `done` settles.
   */
  background?: { done: Promise<unknown>; kill(): void };
  /**
   * Engine revision after the turn (unchanged when nothing committed); null
   * only when a cancellation landed before the pre-flight observe returned.
   */
  revision: number | null;
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
  /**
   * Turn completion is classified from engine commits after the process
   * exits, or at an early release while the provider compacts its session.
   */
  recordOutcome?(outcome: TurnOutcome): void;
}

/** Extra spawn inputs prepared by the supervisor for one invocation. */
export interface SpawnExtras {
  /** Turn-start map picture for providers that accept image files with the prompt. */
  imagePath?: string;
}

export interface LeaderTurnArgs {
  engine: IGameService;
  binding: RunBinding;
  profile: LeaderProfile;
  context: TurnPromptContext;
  guidance: string[];
  resumeId?: string;
  cwd: string;
  extraEnv?: NodeJS.ProcessEnv;
  /** False when the provider already receives GAME_INSTRUCTIONS as system/developer instructions. */
  includeStaticInstructions?: boolean;
  /**
   * Host cancellation (takeover). Aborting kills the provider outright (every
   * engine mutation is atomic and already committed) or, before spawn, keeps
   * it from starting; either way the outcome is "cancelled".
   */
  signal?: AbortSignal;
  /** Attach a rendered overview of the leader's map knowledge to the turn prompt (SpawnExtras.imagePath). */
  attachMapImage?: boolean;
  /**
   * The provider compacts its session after its closing message (Codex
   * post-turn compaction). Once the engine has committed end_turn and that
   * message arrives, the turn is released: game tools are revoked and the
   * outcome returns while the provider finishes compacting in `background`.
   */
  releaseAfterEndTurn?: boolean;
}

/** After a committed end_turn, wait this long for the closing message before releasing anyway. */
const END_TURN_SUMMARY_GRACE_MS = 30_000;

/** Codex stream events that close a turn once end_turn has committed: the closing message, or the turn's end. */
function closesTurnAfterEndTurn(event: Record<string, unknown>): boolean {
  const item = event["item"] as Record<string, unknown> | undefined;
  return event["type"] === "turn.completed" || (event["type"] === "item.completed" && item?.["type"] === "agent_message");
}

/** The stream's own record of a completed game.end_turn call. */
function completesEndTurn(event: Record<string, unknown>): boolean {
  const item = event["item"] as Record<string, unknown> | undefined;
  return event["type"] === "item.completed" && item?.["type"] === "mcp_tool_call" &&
    item["tool"] === "game.end_turn" && !item["error"];
}

/**
 * The opening observation embedded in the turn prompt, without the parts the
 * prompt already states (events, decisions, inbox, correspondence).
 */
function openingObservation(observation: unknown, civId: string): string {
  const { recentEvents: _events, requiredDecisions: _decisions, inbox: _inbox, ...rest } =
    observation as Record<string, unknown>;
  return JSON.stringify(compactObservation(rest, civId));
}

/**
 * A takeover revokes the run's engine authority as it sends cancel_run, so an
 * AUTHORITY_REVOKED engine error can land just before the cancel frame does.
 * Wait this long for that cancel before treating the revocation as a failure.
 */
const REVOKED_CANCEL_GRACE_MS = 1000;

/** A killed provider closes at once; a straggler must not hold the daemon's single turn slot past this. */
const CANCELLED_EXIT_GRACE_MS = 2000;

/**
 * Runs an engine call unless the host already cancelled, and settles early if
 * it cancels mid-call (the abandoned call then fails on revoked authority, unobserved).
 */
function unlessCancelled<T>(call: () => Promise<T>, signal: AbortSignal | undefined): Promise<T> {
  if (!signal) return call();
  if (signal.aborted) return Promise.reject(signal.reason);
  const work = call();
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(signal.reason);
    signal.addEventListener("abort", onAbort, { once: true });
    work.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
  });
}

function abortsWithin(signal: AbortSignal, ms: number): Promise<boolean> {
  if (signal.aborted) return Promise.resolve(true);
  return new Promise((resolve) => {
    const onAbort = () => { clearTimeout(timer); resolve(true); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", onAbort); resolve(false); }, ms);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

/** Resolves null `ms` after `signal` aborts; never settles without an abort. */
function nullAfterAbort(signal: AbortSignal | undefined, ms: number): Promise<null> {
  return new Promise((resolve) => {
    signal?.addEventListener("abort", () => { setTimeout(() => resolve(null), ms).unref(); }, { once: true });
  });
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
    const elapsedMs = () => Math.max(0, this.monotonicNow() - startedAtMonotonic);
    // Own process group (POSIX): wrapper CLIs (npm's codex.js) run the real
    // provider as a grandchild on our stdout pipe, and kill() must reach it.
    const ownGroup = process.platform !== "win32";
    const child: ChildProcess = spawn(spec.executable, spec.argv, {
      cwd: spec.cwd,
      // GUI CLI discovery may find a Node script without node on PATH.
      env: { ...env, PATH: [dirname(process.execPath), env["PATH"] ?? ""].filter(Boolean).join(delimiter) },
      stdio: ["ignore", "pipe", "pipe"],
      detached: ownGroup,
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
    // Set when the turn was released before exit (post-turn compaction).
    let releasedAtMs: number | undefined;
    child.on("close", (code, signal) => {
      const durationMs = elapsedMs();
      Object.assign(metadata, { endedAt: new Date().toISOString(), exitCode: code, signal, durationMs });
      saveMetadata();
      if (eventBuffer.trim()) {
        const line = redactImageLine(eventBuffer);
        appendFileSync(join(runDir, "events.jsonl"), line + "\n", { mode: 0o600 });
        for (const entry of normalizer.fromLine(line)) appendActivity(entry);
      }
      flushStderr(true);
      if (releasedAtMs !== undefined) {
        // The turn's own run-end entry was recorded at release and stays the run's outcome.
        const compaction = formatRunDuration(durationMs - releasedAtMs);
        appendActivity({ id: "session-compaction", kind: "system",
          title: code === 0 ? `Session compacted in ${compaction}` : `Session compaction failed after ${compaction}`,
          text: JSON.stringify(metadata, null, 2), timestamp: metadata["endedAt"] });
        return;
      }
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
        if (outcome.background && metadata["durationMs"] === undefined) {
          releasedAtMs = elapsedMs();
          Object.assign(metadata, { releasedAt: new Date().toISOString(), turnDurationMs: releasedAtMs });
        }
        Object.assign(metadata, { turnOutcome: outcome.status, turnDetail: outcome.detail });
        saveMetadata();
        const label = { completed_turn: "completed", incomplete_turn: "incomplete", guard_paused: "paused", failed: "failed",
          cancelled: "cancelled" }[outcome.status];
        // A cancelled or released provider may still be running when its outcome is recorded.
        const durationMs = (metadata["durationMs"] as number | undefined) ?? elapsedMs();
        const duration = formatRunDuration(durationMs);
        appendActivity({ id: "run-end", kind: "run", status: outcome.status, durationMs,
          title: `Provider ${label} in ${duration}`, summary: `Duration ${duration}. ${outcome.detail}`,
          text: JSON.stringify(metadata, null, 2), timestamp: metadata["endedAt"] ?? new Date().toISOString() });
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
        if (ownGroup && child.pid !== undefined) {
          try {
            process.kill(-child.pid, "SIGKILL");
            return;
          } catch { /* Group already gone; fall through to the direct child. */ }
        }
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
      extras: SpawnExtras,
    ) => { spec: SpawnSpec; env: NodeJS.ProcessEnv },
    private readonly parseStream: (jsonl: string) => NormalizedRun,
    private readonly runner: ProviderRunner = new NodeRunner(),
    private readonly maxToolCallsPerTurn = 500,
    private readonly turnTimeoutMs = 10 * 60 * 1000,
  ) {}

  async runLeaderTurn(args: LeaderTurnArgs): Promise<TurnOutcome> {
    const { signal } = args;
    try {
      return await this.playLeaderTurn(args);
    } catch (error) {
      // An engine call cut off by the cancel, or failing on the authority the
      // takeover revoked alongside it, is the cancellation, not a fault.
      const revoked = (error as { code?: unknown } | null)?.code === "AUTHORITY_REVOKED";
      if (!signal || !(signal.aborted || (revoked && await abortsWithin(signal, REVOKED_CANCEL_GRACE_MS)))) throw error;
      return { status: "cancelled", revision: null, turnEnded: false, run: this.parseStream(""), detail: CANCELLED_DETAIL };
    }
  }

  private async playLeaderTurn(args: LeaderTurnArgs): Promise<TurnOutcome> {
    const { signal } = args;
    const preFlight = await unlessCancelled(() => args.engine.observe(), signal);
    const revisionBefore = preFlight.revision;
    const storedNotes = await unlessCancelled(() => args.engine.readNotes(), signal);
    const prompt = buildTurnPrompt({
      displayName: args.profile.displayName,
      personality: args.profile.personality,
      rulesetName: args.context.rulesetName,
      protocolVersion: args.context.protocolVersion,
      civId: args.context.civId,
      turnSerial: args.context.turnSerial,
      stateRevision: revisionBefore,
      notes: storedNotes.notes,
      notesRevision: storedNotes.notesRevision,
      recentEvents: preFlight.recentEvents,
      pendingDecisions: preFlight.requiredDecisions.map((d) => typeof d === "string"
        ? { id: d, type: "decision", summary: d }
        : { id: d.decisionId, type: d.decisionType, summary: JSON.stringify({ sourceRef: d.sourceRef, classification: d.classification, options: d.options }) }),
      guidance: args.guidance,
      inbox: preFlight.inbox,
      diplomaticHistory: preFlight.diplomaticHistory,
      continuityNote: args.resumeId ? `Resumed provider session ${args.resumeId}.` : args.context.continuityNote,
      includeStaticInstructions: args.includeStaticInstructions,
      openingObservation: openingObservation(preFlight, args.binding.civId),
    });
    const imagePath = args.attachMapImage ? await this.writeOverviewImage(args) : undefined;

    let currentChild: RunningChild | undefined;
    // Early release (post-turn compaction) needs both the engine's commit and the stream's closing message.
    let engineTurnEnded = false;
    let streamTurnEnded = false;
    let releaseTurn: (() => void) | undefined;
    const released = new Promise<"released">((resolve) => { releaseTurn = () => resolve("released"); });
    let releaseTimer: NodeJS.Timeout | undefined;
    const server = await startGameMcpServer(args.engine, args.binding, {
      onAcknowledgement: entry => currentChild?.recordActivity?.(entry),
      onTurnEnded: () => {
        if (!args.releaseAfterEndTurn) return;
        engineTurnEnded = true;
        releaseTimer ??= setTimeout(() => releaseTurn?.(), END_TURN_SUMMARY_GRACE_MS);
      },
    });
    let background: TurnOutcome["background"];
    try {
      // Cancelled before spawn: the provider never starts.
      if (signal?.aborted) {
        return { status: "cancelled", revision: revisionBefore, turnEnded: false, run: this.parseStream(""), detail: CANCELLED_DETAIL };
      }
      const built = this.buildSpawn(prompt, args.resumeId, server.url, server.bearer, imagePath ? { imagePath } : {});
      // Invocation credentials must win over inherited or supplemental env.
      const child = this.runner.spawn({ ...built.spec, context: { ...built.spec.context,
        matchId: args.binding.matchId, civId: args.binding.civId, turnSerial: args.context.turnSerial, runId: args.binding.runId } }, { ...process.env, ...args.extraEnv, ...built.env });
      currentChild = child;
      const killOnCancel = () => child.kill();
      signal?.addEventListener("abort", killOnCancel, { once: true });
      const lines: string[] = [];
      let toolCallCount = 0;
      let guardHit: string | null = null;
      const toolPattern = /"(?:tool|name)"\s*:\s*"[^"]*game[^"]*"/;
      const done = child.wait();
      let releasedEarly = false;
      child.stdoutLines((line) => {
        if (releasedEarly) return;
        lines.push(redactImageLine(line));
        if (args.releaseAfterEndTurn && line.startsWith("{")) {
          try {
            const event = JSON.parse(line) as Record<string, unknown>;
            if (completesEndTurn(event)) streamTurnEnded = true;
            else if (engineTurnEnded && streamTurnEnded && closesTurnAfterEndTurn(event)) releaseTurn?.();
          } catch { /* Partial or non-JSON output never releases a turn. */ }
        }
        // Output still draining from a cancelled provider never counts toward the cap.
        if (!guardHit && !signal?.aborted && toolPattern.test(line)) {
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
      const settled = await Promise.race([done, nullAfterAbort(signal, CANCELLED_EXIT_GRACE_MS), released]);
      clearTimeout(timeout);
      clearTimeout(releaseTimer);
      signal?.removeEventListener("abort", killOnCancel);
      if (settled === "released") {
        // The engine committed end_turn and the leader gave its closing message:
        // the turn is over while the provider compacts its session. Game tools
        // are revoked in finally, before the next leader can be admitted.
        releasedEarly = true;
        background = { done, kill: () => child.kill() };
        const run = this.parseStream(lines.join("\n"));
        const postFlight = await args.engine.observe().catch(() => null);
        const outcome: TurnOutcome = { status: "completed_turn", revision: postFlight?.revision ?? revisionBefore,
          turnEnded: true, run, detail: "engine committed end_turn; provider compacting its session", background };
        child.recordOutcome?.(outcome);
        return outcome;
      }
      if (!settled) {
        process.stderr.write(`Provider pid=${child.pid} still open ${CANCELLED_EXIT_GRACE_MS}ms after its cancellation kill; abandoning it.\n`);
      }
      const exit = settled ?? { code: null, signal: "SIGKILL" };

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
      // Host cancellation outranks every other classification: the run was
      // cut off, not paused or failed. Engine state still reports turnEnded.
      if (signal?.aborted) {
        return finish({ status: "cancelled", revision, turnEnded, run, detail: CANCELLED_DETAIL });
      }
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
      // A background provider read its image at startup; it can go once the provider is done.
      if (imagePath) {
        const removeImage = () => rmSync(imagePath, { force: true });
        if (background) void background.done.finally(removeImage);
        else removeImage();
      }
    }
  }

  /** Renders the turn-start overview; a failed render only skips the picture. */
  private async writeOverviewImage(args: LeaderTurnArgs): Promise<string | undefined> {
    try {
      const image = await renderCivMapImage(args.engine, args.binding.civId);
      if (!image) return undefined;
      // The prompt carries no legend, so a partial picture would pass for the whole map.
      if (!image.complete) {
        process.stderr.write(`Turn-start map image skipped: ${image.legend.split("\n").at(-1)}\n`);
        return undefined;
      }
      const path = join(args.cwd, `overview-${args.binding.runId}.png`);
      writeFileSync(path, image.png, { mode: 0o600 });
      return path;
    } catch (error) {
      process.stderr.write(`Turn-start map image skipped: ${error instanceof Error ? error.message : String(error)}\n`);
      return undefined;
    }
  }
}
