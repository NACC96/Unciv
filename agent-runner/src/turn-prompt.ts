/**
 * Turn-prompt assembly (AI_AGENT_PLAN.md §8.1).
 *
 * Each invocation prompt carries: role/personality, rule-summary version,
 * authoritative civ/turn identity, private structured notes, visible recent
 * events, pending decisions, delivered guidance, and the act/end-turn
 * instructions. It NEVER carries: other leaders' output or notes, unfiltered
 * saves, bearer tokens, or hidden game state.
 */
export interface TurnPromptInput {
  displayName: string;
  personality: string;
  rulesetName: string;
  protocolVersion: number;
  civId: string;
  turnSerial: number;
  stateRevision: number;
  notes: string;
  recentEvents: string[];
  pendingDecisions: { id: string; type: string; summary: string }[];
  guidance: string[];
  continuityNote?: string;
}

const MAX_NOTES_CHARS = 16 * 1024;

export function buildTurnPrompt(input: TurnPromptInput): string {
  if (input.notes.length > MAX_NOTES_CHARS) {
    throw new Error(`private notes exceed ${MAX_NOTES_CHARS} chars; summarize before invoking`);
  }
  const lines = [
    `You are ${input.displayName}, leader of civilization ${input.civId} in a game of Civilization V (ruleset: ${input.rulesetName}, agent protocol v${input.protocolVersion}).`,
    input.personality ? `Personality: ${input.personality}` : "",
    `Turn serial ${input.turnSerial}, state revision ${input.stateRevision}. ` +
      `Act only for ${input.civId}; use only the game.* tools; never invent hidden information.`,
    input.continuityNote ? `Continuity: ${input.continuityNote}` : "",
    `Private strategy notes (your beliefs, not ground truth):\n${input.notes || "(none)"}`,
    `Visible recent events:\n${input.recentEvents.length ? input.recentEvents.map((e) => `- ${e}`).join("\n") : "(none)"}`,
    `Pending decisions (mandatory ones block end of turn):\n${
      input.pendingDecisions.length
        ? input.pendingDecisions.map((d) => `- [${d.id}] ${d.type}: ${d.summary}`).join("\n")
        : "(none)"
    }`,
    input.guidance.length ? `Spectator guidance:\n${input.guidance.map((g) => `- ${g}`).join("\n")}` : "",
    "Inspect with game.observe/game.inspect/game.rules/game.legal_actions, act with game.act (one mutation per call, idempotency keys required), update notes with game.notes, and finish with game.end_turn. Never choose missing mandatory decisions automatically.",
  ];
  return lines.filter(Boolean).join("\n\n");
}

/** Redaction audit for prompt inputs: fails closed on forbidden content. */
export function auditPromptInput(input: {
  notes: string;
  recentEvents: string[];
  guidance: string[];
  forbiddenFingerprints: string[];
}): void {
  const haystack = [input.notes, ...input.recentEvents, ...input.guidance].join("\n");
  for (const fingerprint of input.forbiddenFingerprints) {
    if (fingerprint && haystack.includes(fingerprint)) {
      throw new Error("prompt input contains forbidden content (opponent/save/secret material)");
    }
  }
}
