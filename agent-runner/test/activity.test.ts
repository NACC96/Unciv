import { expect, it } from "vitest";
import { ActivityNormalizer } from "../src/activity.js";
it("updates a live tool entry with full arguments and results, and exposes reasoning summaries", () => {
  const parser = new ActivityNormalizer();
  const item = { id: "i1", type: "mcp_tool_call", tool: "game.act", arguments: { type: "unit.found", args: { unitId: 17 } }, status: "in_progress" };
  const start = parser.fromLine(JSON.stringify({ type: "item.started", item }))[0]!;
  expect(start.text).toContain('"unitId": 17');
  expect(start).toMatchObject({ title: "Founding a city", status: "running" });
  const full = "visible data ".repeat(4000);
  const done = parser.fromLine(JSON.stringify({ type: "item.completed", item: { ...item, status: "completed", result: { content: [{ type: "text", text: JSON.stringify({ full }) }] } } }))[0]!;
  expect(done.id).toBe(start.id);
  expect(done.text).toContain(full);
  const reasoning = parser.fromLine(JSON.stringify({ type: "item.completed", item: { id: "r", type: "reasoning", text: "Found on the river for growth." } }))[0]!;
  expect(reasoning).toMatchObject({ kind: "reasoning", text: "Found on the river for growth." });
});
it("streams thinking into one growing paragraph rather than one row per token", () => {
  const p = new ActivityNormalizer();
  const stream = (event: unknown) => p.fromLine(JSON.stringify({ type: "stream_event", event }));
  stream({ type: "message_start", message: { id: "m" } });
  stream({ type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "" } });
  const a = stream({ type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "Settle " } })[0]!;
  const b = stream({ type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "near the river." } })[0]!;
  const done = p.fromLine(JSON.stringify({ type: "assistant", message: { id: "m", content: [{ type: "thinking", thinking: "Settle near the river." }] } }))[0]!;
  expect(b).toMatchObject({ id: a.id, kind: "reasoning", text: "Settle near the river." });
  expect(done.id).toBe(b.id);
});
it("retains unknown provider events and Claude thinking/tool results", () => {
  const p = new ActivityNormalizer();
  expect(p.fromLine("null")[0]!.text).toBe("null");
  expect(p.fromLine("plain text")[0]!.text).toBe("plain text");
  expect(p.fromLine('{"type":"future_event","data":{"a":1}}')[0]!.text).toContain('"a": 1');
  const input = p.fromLine(JSON.stringify({ type: "assistant", message: { id: "msg", content: [{ type: "thinking", thinking: "Exposed thinking" }, { type: "tool_use", id: "t", name: "game.observe", input: {} }] } }));
  expect(input[0]).toMatchObject({ kind: "reasoning", text: "Exposed thinking" });
  const result = p.fromLine(JSON.stringify({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "t", content: "revision 1" }] } }))[0]!;
  expect(result.id).toBe(input[1]!.id);
  expect(result.text).toContain("revision 1");
});
