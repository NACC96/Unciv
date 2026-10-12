import { expect, it } from "vitest";
import { buildTurnPrompt } from "../src/turn-prompt.js";

it("gives each leader a usable reciprocal peace proposal and recipient consent procedure", () => {
  const prompt = buildTurnPrompt({ displayName: "Ada", personality: "Seek peace.", rulesetName: "Civ V", protocolVersion: 1,
    civId: "Rome", turnSerial: 1, stateRevision: 42, notes: "", recentEvents: [], pendingDecisions: [], guidance: [] });
  // A leader must be able to copy the example into the existing action envelope.
  const examples = [...prompt.matchAll(/game\.act\((\{"actionId"[^\n]+?\})\)/g)].map(match => JSON.parse(match[1]));
  const proposal = examples.find(example => example.type === "diplomacy.proposeTrade");
  expect(proposal).toMatchObject({ actionId: expect.any(String), expectedRevision: expect.any(Number), args: {
    targetCivId: "Greece",
    ourOffers: [{ name: "Peace Treaty", type: "Treaty", amount: 1 }],
    theirOffers: [{ name: "Peace Treaty", type: "Treaty", amount: 1 }],
  } });
  expect(prompt).toMatch(/diplomacy\.makePeace[^\n]*city-state-only/);
  expect(prompt).toMatch(/war continues until the recipient accepts[^\n]*own turn/);
  expect(prompt).toMatch(/diplomacy\.respondTrade[^\n]*requestingCivId[^\n]*accept/);
  expect(prompt).toMatch(/PeaceProposal[^\n]*third-party[^\n]*civilization ID/);
  expect(prompt).toMatch(/PeaceProposal[^\n]*cannot[^\n]*major/);
});
