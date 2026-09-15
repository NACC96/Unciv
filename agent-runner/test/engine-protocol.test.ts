import { describe, expect, it, vi } from "vitest";
import { encodeFrame, ENGINE_PROTOCOL_VERSION, parseInbound } from "../src/engine-protocol.js";
import { StdioEngineBackend } from "../src/stdio-engine.js";

describe("engine protocol framing", () => {
  it("round-trips valid frames and rejects garbage", () => {
    const line = encodeFrame({ protocol: 1, kind: "ready" });
    expect(parseInbound(line)).toBeNull(); // ready is outbound-only
    const req = encodeFrame({
      protocol: 1, kind: "engine_request", id: "eng-1", method: "observe", params: {},
    });
    // engine_request is outbound-only (supervisor -> desktop); inbound ignores it.
    expect(parseInbound(req)).toBeNull();
    expect(parseInbound("not json")).toBeNull();
    expect(parseInbound(JSON.stringify({ protocol: 999, kind: "control", op: "shutdown" }))).toBeNull();
    expect(parseInbound(JSON.stringify({ protocol: ENGINE_PROTOCOL_VERSION, kind: "control", op: "shutdown" })))
      .toMatchObject({ op: "shutdown" });
    expect(parseInbound(JSON.stringify({ protocol: ENGINE_PROTOCOL_VERSION, kind: "nope" }))).toBeNull();
  });
});

describe("StdioEngineBackend", () => {
  it("correlates responses and surfaces engine errors", async () => {
    const sent: string[] = [];
    const backend = new StdioEngineBackend((line) => sent.push(line), 5000);
    const pending = backend.observe();
    expect(sent).toHaveLength(1);
    const req = JSON.parse(sent[0] as string) as { id: string; method: string };
    expect(req.method).toBe("observe");
    backend.feedLine(JSON.stringify({
      protocol: 1, kind: "engine_response", id: req.id, ok: true,
      result: { revision: 7, activeTurn: true, turnEnded: false, requiredDecisions: [], recentEvents: [] },
    }));
    await expect(pending).resolves.toMatchObject({ revision: 7 });
  });

  it("rejects typed engine errors and times out", async () => {
    const sent: string[] = [];
    const backend = new StdioEngineBackend((line) => sent.push(line), 5000);
    const pending = backend.act({ protocolVersion: 1, actionId: "a", expectedRevision: 0, type: "x", args: {} });
    const req = JSON.parse(sent[0] as string) as { id: string };
    backend.feedLine(JSON.stringify({
      protocol: 1, kind: "engine_response", id: req.id, ok: false,
      error: { code: "STALE_STATE", message: "stale" },
    }));
    await expect(pending).rejects.toMatchObject({ code: "STALE_STATE" });

    const slow = new StdioEngineBackend(() => undefined, 20);
    await expect(slow.observe()).rejects.toThrow(/timed out/);
    slow.disconnect();
  });

  it("disconnect rejects everything outstanding", async () => {
    const backend = new StdioEngineBackend(() => undefined, 5000);
    const pending = backend.observe();
    const assertion = expect(pending).rejects.toThrow(/disconnected/);
    backend.disconnect();
    await assertion;
    await expect(backend.observe()).rejects.toThrow(/disconnected/);
  });

  it("ignores malformed and foreign lines", () => {
    const backend = new StdioEngineBackend(() => undefined, 5000);
    expect(backend.feedLine("hello")).toBe(false);
    expect(backend.feedLine(JSON.stringify({ protocol: 1, kind: "control", op: "shutdown" }))).toBe(false);
    backend.disconnect();
    expect(vi.fn()).toBeTruthy();
  });
});
