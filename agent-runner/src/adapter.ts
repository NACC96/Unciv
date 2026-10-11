/**
 * Shared provider-adapter contract (AI_AGENT_PLAN.md §7.1).
 *
 * An adapter translates ONE provider CLI's protocol. It owns no policy:
 * the supervisor decides when to start/resume/cancel; the engine validates
 * every game call. Adapters only translate.
 *
 * Identity rule: resume uses the exact session/thread ID the provider
 * returned at session creation. Never "resume last", --continue, or any
 * directory-dependent heuristic.
 */

export type ProviderId = "codex" | "claude" | "opencode";

/** Normalized lifecycle of a provider invocation owned by the supervisor. */
export type RunLifecycle =
  | "starting"
  | "deciding"
  | "awaiting_engine"
  | "completed"
  | "failed"
  | "cancelled";

/** Normalized stream events (provider namespace of supervisor-events.schema.json). */
export type ProviderEventName =
  | "run_started"
  | "session_bound"
  | "agent_message"
  | "tool_activity"
  | "usage"
  | "provider_retry"
  | "run_completed"
  | "run_failed";

export interface ProviderEvent {
  namespace: "provider";
  name: ProviderEventName;
  /** Exact provider-returned session/thread ID. Set on session_bound and repeated after. */
  sessionId?: string;
  payload: Record<string, unknown>;
}

export interface ExecutableInfo {
  /** Absolute executable path used for this invocation (no PATH reliance, no shell). */
  path: string;
  version: string;
}

export interface SessionHandle {
  /** Exact ID returned by the provider. The only legal resume key. */
  sessionId: string;
}

export interface ProviderAdapter {
  readonly provider: ProviderId;
  /** Locate the CLI and report its version. Never install/update automatically. */
  discoverExecutable(): Promise<ExecutableInfo>;
  /**
   * Validate configuration against the EXACT installed version (flags/config keys
   * drift between releases). Report unsupported managed-policy configurations
   * as unavailable — never widen permissions to compensate.
   */
  validateConfiguration(): Promise<{ ok: true } | { ok: false; reason: string }>;
  /** Start a new session. Must prove the game MCP server loaded (exit code 0 is not proof). */
  startSession(args: { argv: string[]; cwd: string; env: NodeJS.ProcessEnv }): AsyncIterable<ProviderEvent>;
  /** Resume by exact session ID. Mismatched/changed IDs are a hard error, not a fallback. */
  resumeSession(
    sessionId: string,
    args: { argv: string[]; cwd: string; env: NodeJS.ProcessEnv },
  ): AsyncIterable<ProviderEvent>;
  /** Read-only liveness: process identity/status, log growth, event timestamps. Silence alone is never failure. */
  checkLiveness(runPid: number, processStartId: string): Promise<boolean>;
  /** Explicit user-requested cancellation only. Targets the recorded process/session. */
  cancel(runPid: number, sessionId: string): Promise<void>;
}
