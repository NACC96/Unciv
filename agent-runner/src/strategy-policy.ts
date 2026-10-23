import type { ObservedVictoryContext } from "./map-types.js";
import type { StrategicProgress } from "./strategy-types.js";

export const STRATEGY_POLICY_VERSION = "victory-progress-v3";

/** Carried on every invocation, including adapters that resume an old system prompt. */
export const CURRENT_STRATEGY_POLICY = `Current strategy policy ${STRATEGY_POLICY_VERSION}. This supersedes earlier strategy instructions, including every-turn outreach and every-settler movement mandates.
Win through one of victory.available's canonical routes enabled in victory.enabledVictoryTypes. Choose the most achievable supported route; do not change the saved rules. Replace a disabled or unavailable victory plan on this turn. Enabled and unsupported are distinct: report a missing required command honestly and choose a feasible alternative when available. If victory context is missing or truncated, obtain the available facts and state uncertainty instead of inventing a route. Only engine victory data establishes a win.
Retain one primary route and concrete next milestone in private notes. Compare that commitment with strategicProgress, including incoming changes before this turn. Reassess after a missed expectedGameTurn or three consecutive own turns without progress on THAT milestone: name the observed blocker and revise the next action, target, deadline or measurable readiness condition. Productive travel, healing, research and construction can justify continuing with evidence. Unrelated construction, mail volume, repeated SLEEP/FORTIFY, nuclear stockpiles and rewritten prose do not prove progress. History marked incomplete or truncated cannot prove a quiet interval. Use gameTurn for rounds; turnSerial is a protocol identity, not a clock. Reassessment never forces war or blocks end-turn.
Use victory.available[].progress as the host-owned objective counter and observed unchanged interval, independently of preparation and notes. A null lastChangedGameTurn means earlier changes are unknown; trackedSinceGameTurn is the start of observation, not the start of the match. When a blocker recurs, change the blocker, take a concrete step that removes it, or give evidence of current infeasibility with a specific alternative and recheck condition. Extending a deadline or restating the same blocker is not reassessment. Explain why continued preparation changes feasibility and when that change should occur.
For Domination, target original capitals; a replacement capital, ordinary city or city-state is not an original-capital gain. Respect capital knowledge and freshness. Judge a campaign by a reachable approach, assigned ranged/siege support, healthy capture-capable units, local defenders, battle previews when legally available, launch round and fallback. Peacetime legal attackTargets may be empty because war is required; do not require a legal battle preview before declaring war. Empty peacetime targets establish neither military weakness nor a safe attack. Assess possible operations from permitted positions, terrain, roles, ranges and strengths, state uncertainty, and consider expulsion, cancelled trades, retaliation and other opponents before declaring war. After any declaration refresh legal actions before moving or attacking. Your readiness must be attainable under your own agreements: do not promise no entry and then wait indefinitely for an in-border firing position. Respect existing promises; renegotiate or allow their stated expiry rather than silently breaking them. State the benefit and expiry of a standstill, and reconsider renewal against victory progress and alternatives. Official militaryStrength includes nuclear and treasury effects; it is not a city-capture forecast. military.rivals with VISIBLE_ONLY coverage is partial, not an enemy army total. Prepare, defend or negotiate when an assault is infeasible; make preparation measurable.
Diagnose blocked settlers/workers once, then record the entity, reason and recheckOn condition plus a bounded reviewGameTurn. Recheck when that relevant condition changes, a new threat arises or the review round arrives. An unrelated revision change is not a recheck condition. Keep safe waiting units in their existing order; do not reissue unchanged sleep/fortify orders. Found and explore where legal and useful; preserve ongoing worker progress and long movement orders. AUTOMATE and EXPLORE act for one turn and may be renewed when they advance the objective.
Actively seek useful diplomatic opportunities: negotiate trades, coordinate campaigns, secure access, form coalitions and respond to changing threats. Initiate a concrete proposal when it advances your strategy; do not wait for incoming mail. Follow negotiations through to agreement, counteroffer or a reasoned refusal, and implement accepted deals with confirmed legal engine actions. Send new mail for substantive proposals or changed information. Zero outgoing messages is valid on a quiet turn with no useful new proposal; do not repeat unchanged requests awaiting replies. Required incoming acknowledgements remain concise replies on the original correspondence, with attachment review and confirmed receipts; they do not require another outgoing message.
Before end-turn, save JSON in the existing notes string: {"strategy":{"version":1,"victory":"canonical enabled name","milestone":"one concrete objective","expectedGameTurn":123,"readiness":"observable completion condition","assignedUnitIds":[],"assignedCityIds":[],"lastReviewedGameTurn":120,"lastProgress":"last observed progress or none","nextAction":"specific action","reassessWhen":"fallback condition","blockers":[{"unitId":17,"reason":"known blocker","recheckOn":"relevant change","reviewGameTurn":123}]},"memory":"short commitments and useful discoveries"}. Use actual IDs/rounds; empty assignments/blockers are valid. Old or malformed notes are beliefs to refresh, never a gameplay error. Keep all notes below 16 KiB. Briefly report actual progress, next action and the real blocker in public activity.`;

interface StrategyNote {
  version: 1;
  victory: string;
  milestone: string;
  expectedGameTurn: number;
}

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const isRound = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const isText = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0;

function readStrategy(notes: string): { state: "missing" | "legacy" | "invalid" | "active"; plan?: StrategyNote } {
  if (!notes.trim()) return { state: "missing" };
  let parsed: unknown;
  try { parsed = JSON.parse(notes); }
  catch { return { state: notes.trimStart().startsWith("{") ? "invalid" : "legacy" }; }
  if (!isObject(parsed) || !isObject(parsed.strategy)) return { state: "legacy" };
  const plan = parsed.strategy;
  if (plan.version !== 1 || !isRound(plan.expectedGameTurn) || !isRound(plan.lastReviewedGameTurn) ||
      !["victory", "milestone", "readiness", "lastProgress", "nextAction", "reassessWhen"].every(key => isText(plan[key])) ||
      !Array.isArray(plan.assignedUnitIds) || !plan.assignedUnitIds.every(isRound) ||
      !Array.isArray(plan.assignedCityIds) || !plan.assignedCityIds.every(isText) ||
      !Array.isArray(plan.blockers) || !plan.blockers.every(blocker => isObject(blocker) &&
        isText(blocker.reason) && isText(blocker.recheckOn) && isRound(blocker.reviewGameTurn))) return { state: "invalid" };
  return { state: "active", plan: plan as unknown as StrategyNote };
}

const incidentalChanges = new Set(["goldChanged", "storedCultureChanged", "researchSelectionChanged", "productionSelectionChanged"]);

export function strategyCheckpoint(input: { notes: string; gameTurn?: number; victory?: ObservedVictoryContext; strategicProgress?: StrategicProgress }) {
  const { plan, state } = readStrategy(input.notes);
  const gameTurn = isRound(input.gameTurn) ? input.gameTurn : null;
  const victory = input.victory;
  const route = plan && victory?.available.find(route => route.name === plan.victory);
  let planState: string = state;
  if (!victory) planState = "context_unavailable";
  else if (plan) {
    if (!victory.enabledVictoryTypes.includes(plan.victory)) planState = "route_unavailable";
    else if (!route) planState = victory.availableTruncated ? "route_unconfirmed" : "route_unavailable";
    else if (route.completed === true) planState = "satisfied";
    else if (route.milestones.some(milestone => milestone.completed !== true && milestone.status === "unsupported")) planState = "route_unsupported";
  }
  const deadlineState = !plan || gameTurn === null ? "unknown" : gameTurn > plan.expectedGameTurn ? "overdue" :
    gameTurn === plan.expectedGameTurn ? "due" : "future";
  const progress = input.strategicProgress;
  const window = progress?.completedTurns.slice(-3) ?? [];
  const complete = progress?.historyComplete === true && window.length === 3 &&
    window.every(turn => turn.historyComplete && turn.changes.baselineAvailable && !turn.changes.truncated);
  const historyState = !complete ? "incomplete" : window.some(turn =>
    Object.entries(turn.changes.eventCounts).some(([kind, count]) => count > 0 && !incidentalChanges.has(kind))) ? "changed" : "quiet_window";
  const reviewReasons: string[] = [];
  if (planState !== "active" && planState !== "satisfied") reviewReasons.push(planState);
  if (deadlineState === "overdue") reviewReasons.push("missed_deadline");
  if (historyState === "quiet_window") reviewReasons.push("three_quiet_own_turns");
  if (progress && ["unitLost", "cityLost", "originalCapitalLost"].some(kind => (progress.incoming.eventCounts[kind] ?? 0) > 0))
    reviewReasons.push("incoming_losses");
  return {
    policyVersion: STRATEGY_POLICY_VERSION, gameTurn,
    enabledVictoryTypes: victory?.enabledVictoryTypes ?? null,
    availableVictoryTypes: victory?.available.map(route => route.name) ?? null,
    victoryProgress: route?.progress ? { ...route.progress, unchangedGameRounds: gameTurn === null ? null :
      Math.max(0, gameTurn - route.progress.unchangedSinceGameTurn) } : null,
    planState, commitment: plan ? { victory: plan.victory, milestone: plan.milestone, expectedGameTurn: plan.expectedGameTurn } : null,
    deadlineState, historyState, reviewRequired: reviewReasons.length > 0, reviewReasons,
  };
}
