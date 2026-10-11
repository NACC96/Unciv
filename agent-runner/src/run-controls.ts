/**
 * Match run-state machine (AI_AGENT_PLAN.md §9).
 *
 * Match run state and provider process state are SEPARATE. This machine owns
 * the match side: which transitions the UI may offer and what each control
 * guarantees. Authority epochs move with controller changes; the MCP layer
 * rejects stale epochs even if a child process outlives revocation.
 */
export type RunState =
  | "paused"
  | "starting"
  | "agent_deciding"
  | "applying"
  | "waiting_human"
  | "pausing"
  | "needs_attention"
  | "finished";

export type Control =
  | "run"
  | "pause"
  | "step_action"
  | "step_turn"
  | "retry"
  | "switch_provider"
  | "takeover"
  | "return_to_agent"
  | "stop";

export interface Transition {
  from: RunState;
  control: Control;
  /** State while the (possibly async) handoff settles. */
  to: RunState;
  guarantee: string;
}

const TRANSITIONS: Transition[] = [
  { from: "paused", control: "run", to: "starting", guarantee: "resume at the committed boundary; reuse a live invocation, never overlap" },
  { from: "needs_attention", control: "retry", to: "starting", guarantee: "re-observe committed state; resume exact identity after termination confirmed" },
  { from: "needs_attention", control: "switch_provider", to: "starting", guarantee: "revoke old authority + epoch bump; new session with permitted notes/history" },
  { from: "needs_attention", control: "takeover", to: "waiting_human", guarantee: "revoke old authority first, commit controller change, then enable human controls" },
  { from: "paused", control: "takeover", to: "waiting_human", guarantee: "revoke old authority first, commit controller change, then enable human controls" },
  { from: "waiting_human", control: "return_to_agent", to: "starting", guarantee: "commit controller assignment at a paused boundary; new authority epoch" },
  { from: "starting", control: "pause", to: "pausing", guarantee: "finish the admitted provider turn normally; no next invocation; provider NOT killed" },
  { from: "agent_deciding", control: "pause", to: "pausing", guarantee: "finish the admitted provider turn normally; no next invocation; provider NOT killed" },
  { from: "applying", control: "pause", to: "pausing", guarantee: "finish the admitted provider turn normally, then Paused before the next invocation" },
  { from: "pausing", control: "pause", to: "pausing", guarantee: "idempotent while settling" },
  { from: "paused", control: "step_action", to: "paused", guarantee: "one successful gameplay mutation, then Paused; reads/rejects do not consume it" },
  { from: "paused", control: "step_turn", to: "paused", guarantee: "current civ through its end-turn checkpoint, then Paused before the next begins" },
  { from: "paused", control: "stop", to: "paused", guarantee: "explicit cancellation of the recorded process/session only; match stays paused" },
  { from: "agent_deciding", control: "stop", to: "paused", guarantee: "explicit cancellation of the recorded process/session only; match stays paused" },
  { from: "needs_attention", control: "stop", to: "paused", guarantee: "explicit cancellation of the recorded process/session only; match stays paused" },
  { from: "waiting_human", control: "pause", to: "waiting_human", guarantee: "human turns have no provider to pause; stays put" },
];

export function transition(from: RunState, control: Control): Transition | undefined {
  return TRANSITIONS.find((t) => t.from === from && t.control === control);
}

/** Authority epochs: bumped on every controller/authority change. */
export function nextEpoch(current: number): number {
  if (!Number.isSafeInteger(current) || current < 0) throw new Error("invalid authority epoch");
  return current + 1;
}
