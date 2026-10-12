import { describe, expect, it } from "vitest";
import { createHash } from "node:crypto";
import { ActivityNormalizer, redactImageData, redactImageLine } from "../src/activity.js";
import { FakeGameService, privateCorrespondence, type DiplomaticMessage } from "../src/fake-game-mcp.js";
import { GAME_TOOL_ALLOWLIST } from "../src/provider-spawn.js";
import { GAME_INSTRUCTIONS, DIPLOMATIC_REPLY_INSTRUCTIONS, DIPLOMATIC_OUTREACH_INSTRUCTIONS } from "../src/game-instructions.js";
import { MAX_IMAGE_RESPONSE_BYTES, MAX_TOOL_RESPONSE_BYTES, startGameMcpServer, imageResponseBytes } from "../src/game-mcp-server.js";
import { StdioEngineBackend } from "../src/stdio-engine.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import { Supervisor, type ProviderRunner } from "../src/supervisor.js";

const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3hcAAAAASUVORK5CYII=", "base64");
const attachmentId = createHash("sha256").update(png).digest("hex");
const image = { attachmentId, mimeType: "image/png", width: 1, height: 1, data: png.toString("base64") };
const metadata = { attachmentId, mimeType: "image/png", width: 1, height: 1, byteLength: png.length };
const message: DiplomaticMessage = { messageId: "mail", senderCivId: "Greece", targetCivId: "Rome", text: "Accept peace?", sentTurnSerial: 1, deliveredTurnSerial: 2, origin: "NATION", attachments: [metadata] };
const binding = { matchId: "m", timelineId: "t", civId: "Rome", runId: "image-test", authorityEpoch: 1 };
const context = { displayName: "Augustus", personality: "Proud, cautious negotiator.", rulesetName: "Vanilla", protocolVersion: 1, civId: "Rome", turnSerial: 3, stateRevision: 42, notes: "", recentEvents: [], pendingDecisions: [], guidance: [] };
async function call(game: FakeGameService | StdioEngineBackend, args: object = { messageId: "mail", attachmentId }) {
  const server = await startGameMcpServer(game, binding);
  try {
    const response = await fetch(server.url, { method: "POST", headers: { authorization: `Bearer ${server.bearer}`, "content-type": "application/json", accept: "application/json, text/event-stream" }, body: JSON.stringify({ jsonrpc: "2.0", id: '"request', method: "tools/call", params: { name: "game.message_image", arguments: args } }) });
    const raw = await response.text(); return { raw, value: JSON.parse(raw).result };
  } finally { await server.close(); }
}

describe("native diplomatic image reads", () => {
  it("returns native image content with metadata-only text and no mutation", async () => {
    const game = new FakeGameService(); game.messages = [message]; game.messageImages.set(attachmentId, image);
    const { value } = await call(game);
    expect(value.isError).not.toBe(true);
    expect(value.content).toEqual([expect.objectContaining({ type: "text" }), { type: "image", mimeType: "image/png", data: image.data }]);
    expect(value.content[0].text).not.toContain(image.data); expect(game.revision).toBe(42);
    expect(GAME_TOOL_ALLOWLIST).toContain("game.message_image");
  });
  it("gives hidden, outgoing, undelivered and nonexistent images identical rejections", async () => {
    const game = new FakeGameService(); game.messageImages.set(attachmentId, image); const values = [];
    for (const candidate of [null, { ...message, targetCivId: "China" }, { ...message, senderCivId: "Rome", targetCivId: "Greece" }, { ...message, deliveredTurnSerial: null }, { ...message, deliveredTurnSerial: 4 }]) {
      game.messages = candidate ? [candidate] : []; values.push((await call(game)).value);
    }
    for (const value of values) expect(value).toEqual(values[0]);
    expect(values[0].isError).toBe(true); expect(JSON.stringify(values)).not.toContain(image.data);
  });
  it("binds stdio to the admitted actor and preserves rejection", async () => {
    const frames: any[] = [];
    const engine = new StdioEngineBackend(line => { const frame = JSON.parse(line); frames.push(frame); engine.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: false, error: { code: "ILLEGAL_ACTION", message: "no such delivered attachment" } })); }, 1000, () => "Rome");
    try { const { value } = await call(engine, { messageId: "mail", attachmentId, civId: "China" }); expect(value.isError).toBe(true); expect(frames[0]).toMatchObject({ method: "message_image", params: { civId: "Rome", messageId: "mail", attachmentId } }); }
    finally { engine.disconnect(); }
  });
  it.each([{ data: "not base64" }, { data: Buffer.from("not PNG").toString("base64") }, { mimeType: "image/jpeg" }, { width: 2049 }, { width: 2 }, { attachmentId: "0".repeat(64) }])("rejects malformed engine images: %j", async invalid => {
    const game = new FakeGameService(); game.messageImage = async () => ({ ...image, ...invalid });
    const { value } = await call(game); expect(value.isError).toBe(true); expect(value.content.every((c: any) => c.type === "text")).toBe(true); expect(JSON.stringify(value)).not.toContain(image.data);
  });
  it.each([null, undefined, "not an image", 17, false, [], {}, { ...image, data: "corrupt" }])("makes inaccessible and malformed image responses indistinguishable: %j", async invalid => {
    const game = new FakeGameService();
    const missing = (await call(game)).value;
    game.messageImage = async () => invalid as any;
    expect((await call(game)).value).toEqual(missing);
  });
  it("does not reveal stored image corruption through engine rejection codes", async () => {
    const game = new FakeGameService();
    const missing = (await call(game)).value;
    game.messageImage = async () => { throw { code: "INTERNAL_ERROR", message: "stored PNG is corrupt" }; };
    expect((await call(game)).value).toEqual(missing);
  });
  it("rejects decoded PNG payloads above 4 MiB", async () => {
    const game = new FakeGameService(); game.messageImage = async () => ({ ...image, data: Buffer.alloc(4 * 1024 * 1024 + 1).toString("base64") });
    expect((await call(game)).value.isError).toBe(true);
  });
  it("measures the actual image JSON-RPC envelope independently of ordinary limits", async () => {
    const bytes = Buffer.concat([png, Buffer.alloc(4 * 1024 * 1024 - png.length)]);
    const large = { ...image, attachmentId: createHash("sha256").update(bytes).digest("hex"), data: bytes.toString("base64") };
    const game = new FakeGameService(); game.messageImage = async () => large;
    const { raw, value } = await call(game, { messageId: "mail", attachmentId: large.attachmentId });
    expect(value.content[1].type).toBe("image"); expect(Buffer.byteLength(raw)).toBeGreaterThan(MAX_TOOL_RESPONSE_BYTES); expect(Buffer.byteLength(raw)).toBeLessThan(MAX_IMAGE_RESPONSE_BYTES);
    expect(imageResponseBytes(value, '"request')).toBe(Buffer.byteLength(raw));
    expect(imageResponseBytes({ content: [{ type: "text", text: '"'.repeat(3 * 1024 * 1024) }] }, 1)).toBeGreaterThan(MAX_IMAGE_RESPONSE_BYTES);
  });
});

describe("private correspondence and leader replies", () => {
  it("preserves engine ordering for same-turn same-revision correspondence ties", () => {
    const newestFirst = ["latest", "middle", "earliest"].map(messageId => ({ ...message, messageId,
      acknowledgedTurnSerial: 3, acknowledgedRevision: 50 }));
    expect(privateCorrespondence(newestFirst, "Rome", 3).map(m => m.messageId)).toEqual(["latest", "middle", "earliest"]);
  });
  it("includes each inbox message once and injects diplomatic instructions once", () => {
    const prompt = buildTurnPrompt({ ...context, inbox: [message], diplomaticHistory: [message,
      { ...message, messageId: "own-sent", senderCivId: "Rome", targetCivId: "Greece" }] });
    expect(prompt.match(/"messageId":"mail"/g)).toHaveLength(1);
    expect(prompt).toContain('"messageId":"own-sent"');
    for (const instructions of [DIPLOMATIC_REPLY_INSTRUCTIONS, DIPLOMATIC_OUTREACH_INSTRUCTIONS])
      expect(prompt.split(instructions)).toHaveLength(2);
  });
  it("fills the bounded history after excluding overlapping inbox messages", () => {
    const inbox = Array.from({ length: 12 }, (_, i) => ({ ...message, messageId: `incoming-${i}`, sentTurnSerial: 2 }));
    const own = { ...message, messageId: "older-own", senderCivId: "Rome", targetCivId: "Greece", deliveredTurnSerial: 1 };
    const prompt = buildTurnPrompt({ ...context, inbox, diplomaticHistory: [...inbox, own] });
    expect(prompt).toContain('"messageId":"older-own"');
    for (const incoming of inbox) expect(prompt.split(`"messageId":"${incoming.messageId}"`)).toHaveLength(2);
  });
  it("projects metadata only and caps recent private history including old sent responses", () => {
    const history = Array.from({ length: 15 }, (_, i) => ({ ...message, messageId: `own-${i}`, senderCivId: "Rome", targetCivId: "Greece", sentTurnSerial: 1 }));
    const answer = { ...message, messageId: "old-answer", senderCivId: "Rome", targetCivId: "Greece", acknowledgedTurnSerial: 3, acknowledgedRevision: 50, acknowledgementText: "We accept your proposed border." };
    const prompt = buildTurnPrompt({ ...context, inbox: [{ ...message, attachments: [{ ...metadata, data: image.data } as any] }], diplomaticHistory: [...history, answer, { ...message, messageId: "foreign", senderCivId: "China", targetCivId: "Greece" }, { ...message, messageId: "future", deliveredTurnSerial: 4 }, { ...message, messageId: "pending", deliveredTurnSerial: null }] });
    expect(prompt).toContain("old-answer"); expect(prompt).toContain(answer.acknowledgementText); expect(prompt).not.toContain(image.data);
    for (const hidden of ["foreign", "future", "pending", "own-14"]) expect(prompt).not.toContain(`"messageId":"${hidden}"`);
    expect(prompt.match(/"messageId":"(?:own-\d+|old-answer)"/g)).toHaveLength(12);
    expect(prompt).toContain(context.personality); expect(prompt).toContain("game.message_image"); expect(prompt).toContain("cannot view");
    expect(GAME_INSTRUCTIONS).not.toContain("Message received. I will consider"); expect(prompt).toContain("answer the actual proposal"); expect(prompt).toContain("no automatic reply");
  });
  it("includes stored replies when resuming a session", async () => {
    const game = new FakeGameService(); game.messages = [{ ...message, senderCivId: "Rome", targetCivId: "Greece", acknowledgedTurnSerial: 3, acknowledgementText: "No, the border remains disputed." }]; let prompt = "";
    const runner: ProviderRunner = { spawn: () => ({ pid: undefined, stdoutLines: () => {}, stderrText: () => "", wait: async () => ({ code: 0, signal: null }), kill: () => { throw Error("must not kill"); } }) };
    const supervisor = new Supervisor(p => { prompt = p; return { spec: { executable: "unused", argv: [], cwd: process.cwd() }, env: {} }; }, () => ({ provider: "scripted", sessionId: "existing", model: null, toolCalls: [], finalText: null, usage: null, completed: true, errorText: null, advertisedTools: null }), runner);
    await supervisor.runLeaderTurn({ engine: game, binding, profile: { displayName: context.displayName, personality: context.personality, modelLabel: "scripted" }, context, guidance: [], resumeId: "existing", cwd: process.cwd() });
    expect(prompt).toContain("Resumed provider session existing"); expect(prompt).toContain("No, the border remains disputed."); expect(prompt).toContain("answer the actual proposal");
  });
});

it("redacts truncated data-before-type image payloads while preserving ordinary diagnostic text", () => {
  const truncated = '{"content":[{"data":"' + image.data;
  expect(redactImageLine(truncated)).not.toContain(image.data);
  expect(JSON.stringify(redactImageData({ output: truncated }))).not.toContain(image.data);
  expect(JSON.stringify(new ActivityNormalizer().fromLine(truncated))).not.toContain(image.data);
  expect(redactImageLine('{"data":"ordinary incomplete note')).toBe('{"data":"ordinary incomplete note');
});

it("redacts native/Claude image blocks and nested serialized OpenCode results before activity/logging", () => {
  const native = { type: "image", data: image.data, mimeType: "image/png" };
  const claude = { type: "image", source: { type: "base64", media_type: "image/png", data: image.data } };
  const nested = { output: JSON.stringify({ content: [native, claude, { type: "text", text: "safe metadata" }] }) };
  const cleaned = JSON.stringify(redactImageData(nested)); expect(cleaned).not.toContain(image.data); expect(cleaned).toContain("safe metadata"); expect(cleaned).toContain("image/png"); expect(JSON.stringify(nested)).toContain(image.data);
  const rows = new ActivityNormalizer().fromLine(JSON.stringify({ type: "item.completed", item: { type: "mcp_tool_call", tool: "game.message_image", result: { content: [native] } } }));
  expect(JSON.stringify(rows)).not.toContain(image.data); expect(rows[0]?.title).toBe("Read message image");
  expect(redactImageLine(JSON.stringify(nested))).not.toContain(image.data);
  expect(redactImageLine(JSON.stringify(nested).slice(0, -10))).not.toContain(image.data);
  expect(redactImageLine('{"type":"image","data":"' + image.data)).not.toContain(image.data);
});

it.each([
  ["prefixed", (encoded: string) => "tool result: " + encoded],
  ["JSON-quoted", (encoded: string) => JSON.stringify(encoded)],
])("redacts %s serialized image output while preserving outer tool/session identity", (_label, wrap) => {
  const encoded = JSON.stringify({ content: [{ type: "image", mimeType: "image/png", data: image.data }] });
  const event = { type: "tool_use", sessionID: "session-image-privacy", part: { id: "call-image-privacy", tool: "game.message_image",
    state: { status: "completed", input: { messageId: "mail", attachmentId }, output: wrap(encoded), ordinaryText: "A plain diplomatic note." } } };
  const line = redactImageLine(JSON.stringify(event)), cleaned = JSON.parse(line);
  expect(line).not.toContain(image.data);
  expect(cleaned).toMatchObject({ type: "tool_use", sessionID: event.sessionID, part: { id: event.part.id, tool: event.part.tool,
    state: { input: event.part.state.input, ordinaryText: event.part.state.ordinaryText } } });
  expect(JSON.stringify(new ActivityNormalizer().fromLine(JSON.stringify(event)))).not.toContain(image.data);
  expect(new ActivityNormalizer().fromLine(JSON.stringify(event))[0]?.id).toBe("part-call-image-privacy");
});

it("redacts image data URIs in file-shaped provider parts while retaining IDs and ordinary strings", () => {
  const event = { type: "tool_use", sessionID: "session-file-image", part: { id: "call-file-image", tool: "game.message_image", state: { status: "completed",
    output: "metadata available", attachments: [{ type: "file", id: "file-image", mime: "image/png", url: `data:image/png;base64,${image.data}` }],
    text: "plain text", quotedText: JSON.stringify("ordinary quoted text"), url: "https://example.invalid/image.png" } } };
  const line = redactImageLine(JSON.stringify(event)), cleaned = JSON.parse(line);
  expect(line).not.toContain(image.data);
  expect(cleaned.part.state.attachments[0]).toMatchObject({ type: "file", id: "file-image", mime: "image/png" });
  expect(cleaned).toMatchObject({ sessionID: event.sessionID, part: { id: event.part.id, state: { text: "plain text", quotedText: event.part.state.quotedText, url: event.part.state.url } } });
  expect(JSON.stringify(new ActivityNormalizer().fromLine(JSON.stringify(event)))).not.toContain(image.data);
});
