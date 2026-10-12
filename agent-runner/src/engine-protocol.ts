/**
 * Desktop↔supervisor control protocol (AI_AGENT_PLAN.md §7.3).
 *
 * The desktop launches the supervisor and owns the match; the supervisor
 * owns provider processes and the per-invocation MCP endpoint. They speak
 * private versioned JSON-over-stdio:
 *
 * - Supervisor STDOUT: engine requests + turn events (NEVER provider
 *   streams; those stay on piped grandchild stdio).
 * - Supervisor STDIN: engine responses + desktop controls.
 * - Supervisor STDERR: human-readable logs only, never parsed.
 * - EOF on stdin: graceful shutdown — drain children/logs, reject new game
 *   calls, exit naturally after owned children finish (plan §9).
 */
export const ENGINE_PROTOCOL_VERSION = 1;

export type EngineMethod =
  | "observe"
  | "message_image"
  | "inspect"
  | "inspect_tile"
  | "map"
  | "rules"
  | "legal_actions"
  | "act"
  | "action_result"
  | "notes_get"
  | "notes_put"
  | "end_turn";

export interface EngineRequestFrame {
  protocol: 1;
  kind: "engine_request";
  id: string;
  method: EngineMethod;
  params: Record<string, unknown>;
}

export interface EngineResponseFrame {
  protocol: 1;
  kind: "engine_response";
  id: string;
  ok: boolean;
  result?: unknown;
  error?: { code: string; message: string; currentRevision?: number; actionId?: string; messageIds?: string[] };
}

export interface TurnEventFrame {
  protocol: 1;
  kind: "turn_event";
  runId: string;
  event: "run_started" | "session_bound" | "tool_activity" | "run_completed" | "run_failed";
  payload: Record<string, unknown>;
}

export interface RunTurnControl {
  protocol: 1;
  kind: "control";
  op: "run_turn";
  runId: string;
  provider: "scripted" | "codex" | "claude" | "opencode";
  providerConfig: {
    model?: string;
    reasoning?: string;
    resumeId?: string;
    /** Provider-specific extras (cwd override, scripted plan, bearer env name). */
    extra?: Record<string, unknown>;
  };
  binding: {
    matchId: string;
    timelineId: string;
    civId: string;
    runId: string;
    authorityEpoch: number;
  };
  profile: { displayName: string; personality: string; modelLabel: string };
  context: {
    rulesetName: string;
    protocolVersion: number;
    civId: string;
    turnSerial: number;
    continuityNote?: string;
    /** Desktop session policy for this run, recorded in run metadata for A/B analysis. */
    session?: { arm: string; age: number };
  };
  guidance: string[];
}

export interface ShutdownControl {
  protocol: 1;
  kind: "control";
  op: "shutdown";
}

export type InboundFrame = EngineResponseFrame | RunTurnControl | ShutdownControl;

export function parseInbound(line: string): InboundFrame | null {
  const trimmed = line.trim();
  if (!trimmed) return null;
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(trimmed) as Record<string, unknown>;
  } catch {
    return null;
  }
  if (parsed["protocol"] !== ENGINE_PROTOCOL_VERSION) return null;
  if (parsed["kind"] === "engine_response" && typeof parsed["id"] === "string") {
    return parsed as unknown as EngineResponseFrame;
  }
  if (parsed["kind"] === "control" && parsed["op"] === "run_turn") {
    return parsed as unknown as RunTurnControl;
  }
  if (parsed["kind"] === "control" && parsed["op"] === "shutdown") {
    return parsed as unknown as ShutdownControl;
  }
  return null;
}

export function encodeFrame(frame: EngineRequestFrame | TurnEventFrame | { protocol: 1; kind: "ready" }): string {
  return JSON.stringify(frame);
}
