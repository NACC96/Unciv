import { expect, it } from "vitest";
import { ActivityNormalizer } from "../src/activity.js";
import { summarizeTool } from "../src/tool-summary.js";

it("summarizes actual game commands and keeps active calls distinct from completed ones", () => {
  const move = { type: "unit.move", args: { unitId: 2, destination: { x: 4, y: 0 } } };
  expect(summarizeTool("game.act", move, undefined, "in_progress")).toMatchObject({ title: "Moving unit 2 to (4, 0)", status: "running" });
  expect(summarizeTool("game.act", move, { status: "applied" })).toMatchObject({ title: "Moved unit 2 to (4, 0)", status: "completed" });
  expect(summarizeTool("game.act", { type: "research.select", args: { techName: "Pottery" } }).title).toBe("Selected research: Pottery");
  expect(summarizeTool("game.observe", {}, { ownUnits: [{}, {}], ownCities: [{}] }).summary).toBe("2 units · 1 city");
});

it("does not present an engine rejection or an MCP failure as a successful action", () => {
  const p = new ActivityNormalizer();
  const item = { id: "t", type: "mcp_tool_call", tool: "game.act", arguments: { type: "unit.found", args: { unitId: 1 } }, status: "completed", result: { content: [{ type: "text", text: JSON.stringify({ status: "rejected", code: "STALE_STATE", message: "Observe again." }) }] } };
  expect(p.fromLine(JSON.stringify({ type: "item.completed", item }))[0]).toMatchObject({ title: "Action rejected", status: "failed", summary: "Observe again." });
  expect(p.fromLine(JSON.stringify({ type: "item.completed", item: { ...item, result: { isError: true, content: [] } } }))[0]).toMatchObject({ title: "Tool call failed", status: "failed" });
});

it("keeps Claude tool summaries linked to their actual results and preserves full detail", () => {
  const p = new ActivityNormalizer();
  const started = p.fromLine(JSON.stringify({ type: "assistant", message: { content: [{ type: "tool_use", id: "t", name: "mcp__game__game_observe", input: {} }] } }))[0]!;
  const finished = p.fromLine(JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "t", content: [{ type: "text", text: JSON.stringify({ ownUnits: [{}], ownCities: [] }) }] }] } }))[0]!;
  expect(started.title).toBe("Reading game state");
  expect(finished).toMatchObject({ id: started.id, title: "Read game state", summary: "1 unit · 0 cities", status: "completed" });
  expect(finished.text).toContain("ownUnits");
});

it("retains diagnostics in the transcript without turning startup information into a game error", () => {
  const p = new ActivityNormalizer();
  expect(p.fromLine(JSON.stringify({ type: "item.completed", item: { id: "w", type: "error", message: "Under-development features enabled: skip_host_skill_discovery." } }))[0]?.kind).toBe("diagnostic");
  expect(p.fromLine(JSON.stringify({ type: "item.completed", item: { id: "e", type: "error", message: "Real provider failure" } }))[0]).toMatchObject({ kind: "error", text: "Real provider failure" });
});

it("presents structured map, tile terrain and worker choices compactly without hiding rejection", () => {
  expect(summarizeTool("game.map", {}, { tiles: [{}, {}] })).toMatchObject({ title: "Read map", summary: "2 tiles" });
  expect(summarizeTool("mcp__game__game_tile", {}, { terrain: "Plains" })).toMatchObject({ title: "Inspected tile", summary: "Plains" });
  expect(summarizeTool("game.legal_actions", { unitId: 49 }, { improvements: [{ name: "Farm" }] })).toMatchObject({ title: "Checked worker options", summary: "1 improvements" });
  expect(summarizeTool("game.legal_actions", { cityId: "city" }, { constructions: ["Scout"] }).title).toBe("Checked options for city");
  expect(summarizeTool("game.map", {}, { status: "rejected", code: "STALE_STATE", message: "Read again" })).toMatchObject({ status: "failed", summary: "Read again" });
});

it("does not label default city or combat DTO fields as worker capabilities", () => {
  const defaults = { improvements: [], workInProgress: null, repair: null, canCancelWork: false, constructions: [] };
  expect(summarizeTool("game.legal_actions", { cityId: "city" }, { ...defaults, constructions: ["Scout"] }).title).toBe("Checked options for city");
  expect(summarizeTool("game.legal_actions", { unitId: 17 }, defaults).title).toBe("Checked options for unit 17");
  expect(summarizeTool("game.legal_actions", { unitId: 49 }, { ...defaults, workInProgress: { name: "Farm", turnsRemaining: 3 } }).title).toBe("Checked worker options");
  expect(summarizeTool("game.legal_actions", { unitId: 49 }, { ...defaults, repair: { turnsToRepair: 2 } }).title).toBe("Checked worker options");
});

it("presents a reciprocal peace treaty as a pending proposal without claiming peace", () => {
  const input = { type: "diplomacy.proposeTrade", args: {
    targetCivId: "Korea",
    ourOffers: [{ name: "Peace Treaty", type: "Treaty", amount: 1 }, { name: "Gold", type: "Gold", amount: 50 }],
    theirOffers: [{ name: "Peace Treaty", type: "Treaty", amount: 1 }],
  } };
  const p = new ActivityNormalizer();
  const item = { id: "peace", type: "mcp_tool_call", tool: "game.act", arguments: input, status: "in_progress" };
  expect(p.fromLine(JSON.stringify({ type: "item.started", item }))[0])
    .toMatchObject({ title: "Proposing peace with Korea", status: "running" });
  expect(p.fromLine(JSON.stringify({ type: "item.completed", item: { ...item, status: "completed", result: {
    content: [{ type: "text", text: JSON.stringify({ status: "applied", revision: 43, result: { pendingRequestsWithTarget: 1 } }) }],
  } } }))[0]).toMatchObject({
    title: "Proposed peace with Korea", status: "completed",
    summary: "Offer queued for their next turn; war continues until they accept.",
  });
  expect(summarizeTool("game.act", input, undefined)).toMatchObject({
    title: "Peace proposal call with Korea", summary: "Check action_result to confirm the proposal.",
  });
  expect(summarizeTool("game.act", input, { status: "rejected", message: "Offer is not valid" }))
    .toMatchObject({ title: "Action rejected", status: "failed", summary: "Offer is not valid" });
});

it("keeps ordinary, one-sided and third-party trade proposals distinct from bilateral peace", () => {
  for (const [ourOffers, theirOffers] of [
    [[{ name: "Gold", type: "Gold", amount: 50 }], []],
    [[{ name: "Peace Treaty", type: "Treaty", amount: 1 }], []],
    [[{ name: "Peace Treaty", type: "PeaceProposal", amount: 1 }], [{ name: "Peace Treaty", type: "PeaceProposal", amount: 1 }]],
    [[{ name: "Geneva", type: "PeaceProposal", amount: 1 }], []],
    [[{ name: "Peace Treaty", type: "Treaty", amount: 2 }], [{ name: "Peace Treaty", type: "Treaty", amount: 1 }]],
  ]) {
    expect(summarizeTool("game.act", { type: "diplomacy.proposeTrade", args: { targetCivId: "Korea", ourOffers, theirOffers } }, { status: "applied" }))
      .toMatchObject({ title: "Proposed trade with Korea", summary: "" });
  }
  expect(summarizeTool("game.act", { type: "diplomacy.makePeace", args: { targetCivId: "Geneva" } }, { status: "applied" }))
    .toMatchObject({ title: "Made peace with Geneva" });
});

it.each([
  { accept: false, accepted: true, title: "Accepted trade", summary: "Accepted the pending offer from Japan." },
  { accept: true, accepted: false, title: "Declined trade", summary: "Declined the pending offer from Japan." },
])("reports the committed trade response rather than requested accept=$accept", ({ accept, accepted, title, summary }) => {
  const input = { type: "diplomacy.respondTrade", args: { requestingCivId: "Japan", accept } };
  expect(summarizeTool("game.act", input, { status: "applied", result: { accepted } }))
    .toMatchObject({ title, summary, status: "completed" });
});

it("does not claim a trade response was accepted or declined until its applied result confirms it", () => {
  for (const accept of [true, false]) {
    const input = { type: "diplomacy.respondTrade", args: { requestingCivId: "Japan", accept } };
    expect(summarizeTool("game.act", input, undefined, "in_progress"))
      .toMatchObject({ title: accept ? "Accepting trade" : "Declining trade", status: "running" });
    for (const result of [undefined, { status: "applied" }, { status: "pending", result: { accepted: accept } }]) {
      expect(summarizeTool("game.act", input, result)).toMatchObject({
        title: "Trade response call", summary: "Check action_result to confirm acceptance or decline.", status: "completed",
      });
    }
    expect(summarizeTool("game.act", input, { status: "rejected", message: "Not your turn" }))
      .toMatchObject({ title: "Action rejected", summary: "Not your turn", status: "failed" });
  }
});
