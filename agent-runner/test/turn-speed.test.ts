import { afterEach, describe, expect, it, vi } from "vitest";
import { FakeGameService } from "../src/fake-game-mcp.js";
import { startGameMcpServer, type StartedGameMcpServer } from "../src/game-mcp-server.js";
import { DIPLOMATIC_OUTREACH_INSTRUCTIONS, GAME_INSTRUCTIONS } from "../src/game-instructions.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import { Supervisor, type ProviderRunner } from "../src/supervisor.js";
import { summarizeTool } from "../src/tool-summary.js";
import { buildSpawnForProvider, promptCarriesStaticInstructions } from "../src/daemon.js";

let server: StartedGameMcpServer | undefined;
afterEach(async () => {
  await server?.close();
  server = undefined;
  vi.unstubAllEnvs();
});

let nextId = 1;
async function tool(name: string, args: unknown) {
  const res = await fetch(server!.url, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json, text/event-stream", authorization: `Bearer ${server!.bearer}` },
    body: JSON.stringify({ jsonrpc: "2.0", id: nextId++, method: "tools/call", params: { name, arguments: args } }),
  });
  const body = (await res.json() as { result: { isError?: boolean; content: { text: string }[] } }).result;
  return { isError: body.isError ?? false, value: JSON.parse(body.content[0]!.text) };
}

const binding = { matchId: "m", timelineId: "t", civId: "Rome", runId: "r", authorityEpoch: 1 };
const move = (actionId: string, x: number) => ({ actionId, type: "unit.move", args: { unitId: 17, destination: { x, y: 0 } } });

describe("game.act_batch", () => {
  it("chains each receipt's revision into the next command and returns every receipt", async () => {
    const game = new FakeGameService();
    server = await startGameMcpServer(game, binding);
    const result = await tool("game.act_batch", { expectedRevision: 42, actions: [move("b-1", 1), move("b-2", 2)] });
    expect(result.isError).toBe(false);
    expect(result.value).toMatchObject({ status: "applied", revision: 44, applied: 2 });
    expect(result.value.receipts.map((r: { actionId: string; revision: number }) => [r.actionId, r.revision])).toEqual([["b-1", 43], ["b-2", 44]]);
    expect(game.units[0]!.position).toEqual({ x: 2, y: 0 });
  });

  it("stops at the first rejection, keeps earlier commands applied and lists skipped ones", async () => {
    const game = new FakeGameService();
    server = await startGameMcpServer(game, binding);
    // The fake unit has two movement points; the third move is rejected.
    const result = await tool("game.act_batch", { expectedRevision: 42,
      actions: [move("p-1", 1), move("p-2", 2), move("p-3", 3), move("p-4", 4)] });
    expect(result.isError).toBe(false);
    expect(result.value).toMatchObject({ status: "partial", revision: 44, applied: 2,
      rejected: { index: 2, code: "ILLEGAL_ACTION", actionId: "p-3" }, skipped: ["p-4"] });
    expect(game.revision).toBe(44);
    expect(summarizeTool("game.act_batch", { actions: [1, 2, 3, 4] }, result.value))
      .toMatchObject({ title: "Applied 2 of 4 actions", status: "completed" });
  });

  it("reports a rejected first command like game.act, so nothing is mistaken for applied", async () => {
    const game = new FakeGameService();
    server = await startGameMcpServer(game, binding);
    const result = await tool("game.act_batch", { expectedRevision: 41, actions: [move("s-1", 1), move("s-2", 2)] });
    expect(result).toMatchObject({ isError: true, value: { status: "rejected", code: "STALE_STATE", currentRevision: 42, index: 0, skipped: ["s-2"] } });
    expect(game.revision).toBe(42);
  });

  it("keeps applied receipts when the engine link fails mid-batch and marks that command's outcome unknown", async () => {
    const game = new FakeGameService();
    const act = game.act.bind(game);
    let calls = 0;
    game.act = async (envelope) => {
      if (++calls === 2) throw new Error("engine call act timed out");
      return act(envelope);
    };
    server = await startGameMcpServer(game, binding);
    const result = await tool("game.act_batch", { expectedRevision: 42, actions: [move("t-1", 1), move("t-2", 2), move("t-3", 3)] });
    expect(result.isError).toBe(false);
    expect(result.value).toMatchObject({ status: "partial", revision: 43, applied: 1,
      rejected: { index: 1, code: "INTERNAL_ERROR", actionId: "t-2" }, skipped: ["t-3"] });
    expect(result.value.rejected.message).toContain("game.action_result");
  });

  it("validates acknowledgements inside a batch exactly like game.act", async () => {
    const game = new FakeGameService();
    server = await startGameMcpServer(game, binding);
    const result = await tool("game.act_batch", { expectedRevision: 42,
      actions: [{ actionId: "a-1", type: "diplomacy.acknowledgeMessage", args: { messageId: "m", text: "  " } }] });
    expect(result).toMatchObject({ isError: true, value: { code: "INVALID_ARGUMENT", actionId: "a-1" } });
  });
});

const promptInput = {
  displayName: "Ada", personality: "Bold.", rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "Rome",
  turnSerial: 3, stateRevision: 42, notes: "Settle rivers.", recentEvents: [], pendingDecisions: [], guidance: [],
};

describe("turn prompt size", () => {
  it("omits the static instructions when the provider already receives them, keeping per-turn context", () => {
    const prompt = buildTurnPrompt({ ...promptInput, includeStaticInstructions: false, notesRevision: 7,
      continuityNote: "Your closing summary from your previous turn: Founded Antium." });
    expect(prompt).not.toContain(GAME_INSTRUCTIONS);
    expect(prompt).not.toContain(DIPLOMATIC_OUTREACH_INSTRUCTIONS);
    expect(prompt).toContain("Personality: Bold.");
    expect(prompt).toContain("notesRevision 7");
    expect(prompt).toContain("Settle rivers.");
    expect(prompt).toContain("Continuity: Your closing summary from your previous turn: Founded Antium.");
    expect(prompt.length).toBeLessThan(GAME_INSTRUCTIONS.length);
  });

  it("clips long correspondence in the prompt", () => {
    const long = "x".repeat(2000);
    const prompt = buildTurnPrompt({ ...promptInput, diplomaticHistory: [{ messageId: "long", senderCivId: "Rome", targetCivId: "Greece",
      text: long, sentTurnSerial: 2, deliveredTurnSerial: 3, origin: "NATION" }] });
    expect(prompt).toContain('"messageId":"long"');
    expect(prompt).not.toContain(long);
    expect(prompt).toContain(`${"x".repeat(600)}…`);
  });

  it("sends static instructions in the prompt only where no system/developer channel carries them", () => {
    expect(promptCarriesStaticInstructions("codex", undefined)).toBe(false);
    expect(promptCarriesStaticInstructions("claude", "session")).toBe(false);
    expect(promptCarriesStaticInstructions("opencode", undefined)).toBe(true);
    expect(promptCarriesStaticInstructions("opencode", "session")).toBe(false);
    for (const name of ["CODEX", "CLAUDE"]) vi.stubEnv(`UNCIV_${name}_EXECUTABLE`, process.execPath);
    const codex = buildSpawnForProvider("codex", "play", undefined, "http://127.0.0.1/mcp", "b", {}, "/tmp");
    expect(codex.spec.argv).toContain(`developer_instructions=${JSON.stringify(GAME_INSTRUCTIONS)}`);
    const claude = buildSpawnForProvider("claude", "play", "session", "http://127.0.0.1/mcp", "b", {}, "/tmp");
    expect(claude.spec.argv[claude.spec.argv.indexOf("--append-system-prompt") + 1]).toBe(GAME_INSTRUCTIONS);
  });

  it("hands the stored notes revision to the leader so it can save notes without reading them", async () => {
    const game = new FakeGameService();
    game.notes = "Hold the river.";
    game.notesRevision = 5;
    let prompt = "";
    const runner: ProviderRunner = { spawn: () => ({ pid: undefined, stdoutLines: () => {}, stderrText: () => "",
      wait: async () => ({ code: 0, signal: null }), kill: () => { throw Error("must not kill"); } }) };
    const supervisor = new Supervisor(p => { prompt = p; return { spec: { executable: "unused", argv: [], cwd: process.cwd() }, env: {} }; },
      () => ({ provider: "scripted", sessionId: null, model: null, toolCalls: [], finalText: null, usage: null, completed: true, errorText: null, advertisedTools: null }), runner);
    await supervisor.runLeaderTurn({ engine: game, binding, profile: { displayName: "Ada", personality: "Bold.", modelLabel: "scripted" },
      context: { rulesetName: "Civ V - Vanilla", protocolVersion: 1, civId: "Rome", turnSerial: 3 }, guidance: [], cwd: process.cwd(),
      includeStaticInstructions: false });
    expect(prompt).toContain("notesRevision 5");
    expect(prompt).toContain("Hold the river.");
    expect(prompt).not.toContain(GAME_INSTRUCTIONS);
  });
});
