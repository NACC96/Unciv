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
  it("forwards unit or city legal selectors with the authenticated actor, never a default unit", async () => {
    const sent: { id: string; method: string; params: Record<string, unknown> }[] = [];
    const backend = new StdioEngineBackend(line => {
      const frame = JSON.parse(line); sent.push(frame);
      queueMicrotask(() => backend.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result: { constructions: [] } })));
    }, 5000, () => "Rome");
    try {
      await backend.legalActions(17);
      await backend.legalActions(undefined, "rome-capital");
      expect(sent.map(({ method, params }) => ({ method, params }))).toEqual([
        { method: "legal_actions", params: { civId: "Rome", unitId: 17 } },
        { method: "legal_actions", params: { civId: "Rome", cityId: "rome-capital" } },
      ]);
    } finally { backend.disconnect(); }
  });
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

it("forwards additive map/tile/rules reads with immutable authenticated actor and mutation protocol 1", async () => {
  const sent: any[] = [];
  const backend = new StdioEngineBackend(line => sent.push(JSON.parse(line)), 5000, () => "Rome");
  const checks: [() => Promise<unknown>, string, object, unknown][] = [
    [() => backend.inspectTile({ x: 48, y: -33 }), "inspect_tile", { x: 48, y: -33, civId: "Rome" }, { terrain: "Grassland", visibility: "REMEMBERED" }],
    [() => backend.map({ mode: "KNOWN", center: { x: 48, y: -33 }, radius: 2, limit: 1, cursor: "cursor", civId: "Greece" } as any), "map", { mode: "KNOWN", center: { x: 48, y: -33 }, radius: 2, limit: 1, cursor: "cursor", civId: "Rome" }, { observationVersion: 2, tiles: [], nextCursor: "next" }],
    [() => backend.rules({ section: "improvements", name: "Farm", limit: 1, cursor: "rules-next" }), "rules", { section: "improvements", name: "Farm", limit: 1, cursor: "rules-next" }, { rulesetName: "Civ V", baseRuleset: "Civ V", rulesetIdentity: "pinned", section: "improvements", entries: [{ name: "Farm", techRequired: null }], nextCursor: null }],
  ];
  for (const [invoke, method, params, result] of checks) {
    const pending = invoke();
    const frame = sent.at(-1);
    expect(frame).toMatchObject({ protocol: 1, method, params });
    backend.feedLine(JSON.stringify({ protocol: 1, kind: "engine_response", id: frame.id, ok: true, result }));
    await expect(pending).resolves.toEqual(result);
  }
  backend.disconnect();
});
