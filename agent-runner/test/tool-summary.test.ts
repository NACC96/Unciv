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
