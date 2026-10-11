import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { parseClaudeStream, parseCodexStream, parseOpencodeStream } from "../src/provider-events.js";

const fixtures = join(dirname(fileURLToPath(import.meta.url)), "..", "test-fixtures");
const read = (name: string) => readFileSync(join(fixtures, name), "utf-8");

describe("provider stream parsers against recorded W1 fixtures", () => {
  it("codex: session, tools, receipt-shaped result, usage", () => {
    const run = parseCodexStream(read("w1-codex-invoke1.jsonl"));
    expect(run.provider).toBe("codex");
    expect(run.sessionId).toMatch(/^[0-9a-f-]{10,}$/);
    const tools = run.toolCalls.filter((t) => t.completed).map((t) => t.tool);
    expect(tools).toEqual(expect.arrayContaining(["game.observe", "game.act"]));
    expect(tools.every((t) => t.startsWith("game."))).toBe(true);
    expect(run.finalText).toMatch(/DONE/);
    expect(run.completed).toBe(true);
    expect(run.usage?.inputTokens).toBeGreaterThan(0);
    expect(run.usage?.cost).toBeNull(); // never fabricated
  });

  it("codex resume keeps the same session", () => {
    const first = parseCodexStream(read("w1-codex-invoke1.jsonl"));
    const second = parseCodexStream(read("w1-codex-resume.jsonl"));
    expect(second.sessionId).toBe(first.sessionId);
  });

  it("claude: catalog proof, tools, cost passthrough", () => {
    const run = parseClaudeStream(read("w1-claude-invoke1.jsonl"));
    expect(run.provider).toBe("claude");
    expect(run.sessionId).toBeTruthy();
    expect(run.advertisedTools).toHaveLength(8);
    expect(run.advertisedTools?.every((t) => t.startsWith("mcp__game__"))).toBe(true);
    expect(run.toolCalls.map((t) => t.tool)).toEqual(
      expect.arrayContaining(["mcp__game__game_observe", "mcp__game__game_act"]),
    );
    expect(run.completed).toBe(true);
    expect(typeof run.usage?.cost === "number" || run.usage?.cost === null).toBe(true);
  });

  it("opencode: session stability and game-only tools", () => {
    const first = parseOpencodeStream(read("w1-opencode-invoke1.jsonl"));
    const second = parseOpencodeStream(read("w1-opencode-resume.jsonl"));
    expect(first.sessionId).toMatch(/^ses_/);
    expect(second.sessionId).toBe(first.sessionId);
    const tools = first.toolCalls.map((t) => t.tool);
    expect(tools).toEqual(expect.arrayContaining(["game_game_observe", "game_game_act"]));
    expect(tools.every((t) => t.startsWith("game_"))).toBe(true);
    expect(first.finalText).toMatch(/DONE/);
  });

  it("malformed lines never kill parsing", () => {
    const run = parseCodexStream('{"type":"thread.started","thread_id":"abc"}\nNOT JSON\n{"type":"turn.completed"}\n');
    expect(run.sessionId).toBe("abc");
    expect(run.completed).toBe(true);
  });
});
