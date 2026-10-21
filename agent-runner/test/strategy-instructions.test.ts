import { expect, it } from "vitest";
import { GAME_INSTRUCTIONS, STRATEGY_INSTRUCTIONS } from "../src/game-instructions.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import type { ObservedVictoryContext } from "../src/map-types.js";
import type { StrategicProgress } from "../src/strategy-types.js";

const victory: ObservedVictoryContext = {
  enabledVictoryTypes: ["Domination"], achieved: null, availableTruncated: false, domination: null,
  available: [{ name: "Domination", completed: false, completedMilestones: 0, nextMilestone: 0, milestonesTruncated: false,
    milestones: [{ description: "Capture all original capitals", type: "CaptureAllCapitals", completed: false,
      current: 1, target: 2, status: "supported", unsupportedReason: null, remainingSpaceshipParts: null, partsTruncated: false }] }],
};
const plan = { version: 1, victory: "Domination", milestone: "Stage artillery outside Athens", expectedGameTurn: 108,
  readiness: "Two artillery and one healthy capture unit in range", assignedUnitIds: [5, 8, 9], assignedCityIds: [],
  lastReviewedGameTurn: 104, lastProgress: "Artillery moved two tiles closer", nextAction: "Move artillery toward Athens",
  reassessWhen: "The route closes or the deadline passes", blockers: [] };
const base = { displayName: "Ada", personality: "", rulesetName: "Civ V", protocolVersion: 1, civId: "Rome",
  turnSerial: 524, stateRevision: 4566, notes: "", recentEvents: [], pendingDecisions: [], guidance: [], includeStaticInstructions: false };
function prompt(extra: Record<string, unknown> = {}) {
  return buildTurnPrompt(Object.assign({}, base, { gameTurn: 104, victory, notes: JSON.stringify({ strategy: plan, memory: "Treaty until round 110" }) }, extra));
}
function checkpoint(extra: Record<string, unknown> = {}) {
  const text = prompt(extra);
  const line = text.split("\n").find(line => line.startsWith('Strategic checkpoint: '));
  expect(line, "turn prompt needs structured objective reconciliation").toBeDefined();
  return JSON.parse(line!.slice("Strategic checkpoint: ".length));
}
function progress(counts: Record<string, number> = {}): StrategicProgress {
  const delta = { baselineAvailable: true, fromGameTurn: 103, toGameTurn: 104, eventCounts: counts, events: [], truncated: false };
  return { historyComplete: true, trackedSinceGameTurn: 100, trackedSinceTurnSerial: 500, ownTurnOrdinal: 4,
    current: { research: null, production: [], productionCount: 0, productionTruncated: false },
    incoming: { ...delta, eventCounts: {} }, completedTurns: [1, 2, 3].map(ownTurnOrdinal => ({ ownTurnOrdinal,
      gameTurn: 100 + ownTurnOrdinal, turnSerial: 500 + ownTurnOrdinal * 5, historyComplete: true,
      changes: { ...delta }, appliedCommandCounts: { "diplomacy.message": 3, "unit.order": 5 } })) };
}

it("reconciles canonical enabled and available routes with legacy and incompatible private plans", () => {
  expect(checkpoint()).toMatchObject({ policyVersion: "victory-progress-v1", gameTurn: 104, planState: "active", deadlineState: "future",
    enabledVictoryTypes: ["Domination"], availableVictoryTypes: ["Domination"], reviewRequired: false });
  expect(checkpoint({ notes: "Primary victory: Scientific. Build spaceship parts." })).toMatchObject({ planState: "legacy", reviewRequired: true });
  expect(checkpoint({ notes: JSON.stringify({ strategy: { ...plan, victory: "Scientific" } }) })).toMatchObject({ planState: "route_unavailable", reviewRequired: true });
  expect(prompt()).toContain("Replace a disabled or unavailable victory plan on this turn");
});

it("separates an enabled route with an unsupported action from a disabled route", () => {
  const science = structuredClone(victory);
  science.enabledVictoryTypes = ["Scientific"];
  science.available[0].name = "Scientific";
  science.available[0].milestones[0].status = "unsupported";
  science.available[0].milestones[0].unsupportedReason = "Spaceship assembly has no agent command";
  const result = checkpoint({ victory: science, notes: JSON.stringify({ strategy: { ...plan, victory: "Scientific" } }) });
  expect(result).toMatchObject({ enabledVictoryTypes: ["Scientific"], planState: "route_unsupported", reviewRequired: true });
  science.available[0].completed = true;
  science.available[0].milestones[0].completed = true;
  expect(checkpoint({ victory: science, notes: JSON.stringify({ strategy: { ...plan, victory: "Scientific" } }) }).planState).toBe("satisfied");
});

it("uses the actual round for deadlines, never the global turn serial", () => {
  expect(checkpoint({ gameTurn: 107, turnSerial: 9000 }).deadlineState).toBe("future");
  expect(checkpoint({ gameTurn: 108 }).deadlineState).toBe("due");
  expect(checkpoint({ gameTurn: 109 })).toMatchObject({ deadlineState: "overdue", reviewRequired: true });
  expect(checkpoint({ gameTurn: undefined })).toMatchObject({ gameTurn: null, deadlineState: "unknown" });
});

it("requests reassessment after three complete quiet own turns despite mail and repeated orders", () => {
  expect(checkpoint({ strategicProgress: progress({ goldChanged: 1, storedCultureChanged: 1 }) }))
    .toMatchObject({ historyState: "quiet_window", reviewRequired: true });
});

it.each(["unitMoved", "unitHealthChanged", "researchWorkChanged", "productionWorkChanged", "technologyGained", "unitGained"])(
  "does not demand switching a progressing plan because of %s", kind => {
    expect(checkpoint({ strategicProgress: progress({ [kind]: 1 }) })).toMatchObject({ historyState: "changed", reviewRequired: false });
  });

it("does not manufacture quiet turns from incomplete or truncated history", () => {
  for (const mutate of [(p: StrategicProgress) => { p.historyComplete = false; },
    (p: StrategicProgress) => { p.completedTurns.pop(); },
    (p: StrategicProgress) => { p.completedTurns[0].changes.truncated = true; }]) {
    const facts = progress(); mutate(facts);
    expect(checkpoint({ strategicProgress: facts })).toMatchObject({ historyState: "incomplete", reviewRequired: false });
  }
});

it("reviews incoming losses before acting even with a future deadline and a progressing prior window", () => {
  const facts = progress({ researchWorkChanged: 1 });
  facts.incoming.eventCounts = { originalCapitalLost: 1, unitLost: 2 };
  expect(checkpoint({ strategicProgress: facts })).toMatchObject({ reviewRequired: true, reviewReasons: ["incoming_losses"] });
});

it.each(["", "{broken", JSON.stringify({ strategy: { ...plan, expectedGameTurn: "soon" } }), "x".repeat(16 * 1024)])(
  "keeps absent, malformed, legacy and maximum-sized notes nonfatal (%#)", notes => {
    expect(() => checkpoint({ notes })).not.toThrow();
    expect(checkpoint({ notes }).reviewRequired).toBe(true);
  });

it("treats an older engine or truncated available routes as unknown, not disabled", () => {
  expect(checkpoint({ victory: undefined })).toMatchObject({ planState: "context_unavailable", enabledVictoryTypes: null });
  expect(checkpoint({ victory: { ...victory, available: [], availableTruncated: true } })).toMatchObject({ planState: "route_unconfirmed" });
});

it("removes activity mandates while preserving purposeful messages and changed-condition checks", () => {
  for (const text of [GAME_INSTRUCTIONS, prompt(), prompt({ openingObservation: "{}" })]) {
    expect(text).not.toContain("use it every turn to improve your position");
    expect(text).not.toContain("Never leave a settler idle");
    expect(text).not.toContain("keep every settler and scout acting");
    expect(text).toContain("Zero outgoing messages is valid");
    expect(text).toContain("An unrelated revision change is not a recheck condition");
  }
  expect(STRATEGY_INSTRUCTIONS).toContain("battle previews");
  expect(GAME_INSTRUCTIONS).toContain("MESSAGES_UNACKNOWLEDGED");
  expect(GAME_INSTRUCTIONS).toContain("game.message_image");
  expect(GAME_INSTRUCTIONS).toContain("mapSummary.vision");
  expect(GAME_INSTRUCTIONS).toContain("Attacks and other game rules still need your units' real sight");
});
