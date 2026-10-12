import { expect, it } from "vitest";
import { GAME_INSTRUCTIONS, STRATEGY_INSTRUCTIONS } from "../src/game-instructions.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";

it("teaches realpolitik: the balance of power, war as policy and agreements as instruments", () => {
  for (const text of ["brutal realpolitik", "empire.militaryStrength, militaryRank, score and scoreRank", "rivals (militaryStrength",
    "War is a normal instrument of policy", "diplomacy.declareWar", "do not leave large forces idle",
    "Agreements are instruments, not obligations", "break them when breaking gains more than the lost trust costs"])
    expect(STRATEGY_INSTRUCTIONS).toContain(text);
  expect(GAME_INSTRUCTIONS).toContain(STRATEGY_INSTRUCTIONS);
});

it("keeps settlers and scouts moving, with Unciv's automation as the settler fallback", () => {
  for (const text of ["Never leave a settler idle", "legal_actions citySites", "two turns without founding, order AUTOMATE",
    "EXPLORE toward unexplored and unclaimed land", "last only this turn, so repeat them each turn", "expansion continues by conquest"])
    expect(STRATEGY_INSTRUCTIONS).toContain(text);
});

it("explains each map vision level and that rules keep real sight", () => {
  for (const text of ["mapSummary.vision", "FOG is normal fog of war", "marked REVEALED", "FULL shows every tile, unit and city live",
    "Attacks and other game rules still need your units' real sight"])
    expect(GAME_INSTRUCTIONS).toContain(text);
});

it("closes every turn prompt with the balance of power and moving settlers", () => {
  const prompt = buildTurnPrompt({ displayName: "Ada", personality: "", rulesetName: "Civ V", protocolVersion: 1, civId: "Rome",
    turnSerial: 1, stateRevision: 1, notes: "", recentEvents: [], pendingDecisions: [], guidance: [], includeStaticInstructions: false });
  expect(prompt).toContain("weigh the balance of power against your rivals");
  expect(prompt).toContain("keep every settler and scout acting");
});
