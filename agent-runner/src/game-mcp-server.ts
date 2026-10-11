/**
 * Game MCP transport (AI_AGENT_PLAN.md §7.3).
 *
 * Streamable HTTP on an ephemeral 127.0.0.1 port, fronting a game service
 * (the deterministic fake in W1; the engine-backed service from W2 on).
 * The desktop launches this supervisor and speaks to it over private
 * versioned JSON-over-stdio; provider streams never touch that channel.
 *
 * Security properties enforced HERE (not in adapters):
 * - Fresh random bearer per invocation, held in supervisor memory mapped to
 *   the run binding (match/timeline/civ/run/authority epoch). Required on
 *   EVERY request: initialize, tool list, tool call, reconnect. Never in URLs,
 *   never in model-visible prompts.
 * - Loopback only: Host must be 127.0.0.1. Any Origin header (browser
 *   context) is rejected. No CORS, no non-loopback listener, no
 *   unauthenticated fallback. OAuth discovery stays disabled for this
 *   endpoint (it is a local bearer endpoint, not a user OAuth server).
 * - Bounds: 64 KiB max tool request, 256 KiB max tool response (plan §5.2).
 * - Serialization: one request dispatched at a time per server, so concurrent
 *   provider calls cannot interleave inside the game service. Excess work is
 *   rejected, never queued unboundedly.
 * - Stateless JSON responses (no SSE streams held open): a paused or revoked
 *   invocation gets a prompt structured paused/revoked result instead of a
 *   tool call hanging until the user resumes.
 */

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { z } from "zod";
import type { IGameService } from "./fake-game-mcp.js";
import { acknowledgementActivity, type ActivityEntry } from "./activity.js";

export const MAX_TOOL_REQUEST_BYTES = 64 * 1024;
export const MAX_TOOL_RESPONSE_BYTES = 256 * 1024;
export const MAX_READ_RESPONSE_BYTES = 96 * 1024;
export const MAX_REQUEST_ID_BYTES = 128;

export interface RunBinding {
  matchId: string;
  timelineId: string;
  civId: string;
  runId: string;
  authorityEpoch: number;
}

export interface StartedGameMcpServer {
  /** e.g. http://127.0.0.1:54321/mcp — bearer travels in the header, never here. */
  url: string;
  bearer: string;
  binding: RunBinding;
  close(): Promise<void>;
}

const ok = (result: unknown) => ({
  content: [{ type: "text" as const, text: JSON.stringify(result) }],
});

const err = (code: string, message: string, extra: Record<string, unknown> = {}) => ({
  content: [{ type: "text" as const, text: JSON.stringify({ status: "rejected", code, message, ...extra }) }],
  isError: true,
});

type ReadRejection = { code: string; message: string; currentRevision?: number; actionId?: string; messageIds?: string[] };
const readError = (rejected: ReadRejection) => err(rejected.code, rejected.message, {
  ...(rejected.currentRevision !== undefined ? { currentRevision: rejected.currentRevision } : {}),
  ...(rejected.actionId !== undefined ? { actionId: rejected.actionId } : {}),
  ...(rejected.messageIds !== undefined ? { messageIds: rejected.messageIds } : {}),
});

const serviceResult = async (call: () => Promise<ReturnType<typeof ok> | ReturnType<typeof err>>) => {
  try { return await call(); } catch (error) {
    if (!error || typeof error !== "object" || !("code" in error) || typeof error.code !== "string") throw error;
    return readError(error as ReadRejection);
  }
};

/** Count the actual JSON-RPC result, including the second escape of text content. */
const readResult = async (read: () => Promise<unknown>, requestId: string | number) => {
  let response: ReturnType<typeof ok> | ReturnType<typeof err>;
  try {
    const result = await read();
    response = result && typeof result === "object" && "status" in result && result.status === "rejected"
      ? readError(result as unknown as ReadRejection) : ok(result);
  } catch (error) {
    if (!error || typeof error !== "object" || !("code" in error) || typeof error.code !== "string") throw error;
    response = readError(error as ReadRejection);
  }
  if (Buffer.byteLength(JSON.stringify({ jsonrpc: "2.0", id: requestId, result: response }), "utf8") > MAX_READ_RESPONSE_BYTES)
    return err("INVALID_ARGUMENT", "read response exceeded 96 KiB; request a smaller page");
  return response;
};

function registerGameTools(mcp: McpServer, game: IGameService, actorCivId: string, onAcknowledgement?: (entry: ActivityEntry) => void): void {
  const recordAcknowledgement = (receipt: unknown) => {
    const entry = acknowledgementActivity(receipt, actorCivId);
    if (entry) onAcknowledgement?.(entry);
  };
  mcp.registerTool("game.observe", {
    description: "Permitted overview: revision, active-turn status, required decisions, recent visible events.",
    inputSchema: {},
  }, async () => ok(await game.observe()));

  mcp.registerTool("game.inspect", {
    description: "Permitted detail for one visible entity. Hidden and nonexistent IDs get the same safe response.",
    inputSchema: { unitId: z.number().int() },
  }, async ({ unitId }) => {
    const res = await game.inspect(unitId);
    if (res === null) return err("ILLEGAL_ACTION", "no such visible entity");
    return "status" in res ? err(res.code, res.message) : ok(res);
  });

  mcp.registerTool("game.tile", {
    description: "Inspect a known tile in engine hex coordinates. REMEMBERED facts are historical; unknown and nonexistent tiles have the same safe response.",
    inputSchema: { x: z.number().int(), y: z.number().int() },
  }, async ({ x, y }, extra) => readResult(async () => {
    const res = await game.inspectTile({ x, y });
    return res === null ? { status: "rejected", code: "ILLEGAL_ACTION", message: "no such known tile" } : res;
  }, extra.requestId));

  mcp.registerTool("game.map", {
    description: "Bounded civilization-scoped structured map: KNOWN includes historical memory, VISIBLE is current sight. Center and radius must be supplied together. Re-query after revision changes; cursors bind to the query/revision.",
    inputSchema: {
      mode: z.enum(["KNOWN", "VISIBLE"]).optional(),
      center: z.object({ x: z.number().int(), y: z.number().int() }).optional(),
      radius: z.number().int().min(0).max(8).optional(),
      limit: z.number().int().min(1).max(128).optional(),
      cursor: z.string().min(1).max(512).optional(),
    },
  }, async (query, extra) => readResult(async () => {
    if ((query.center === undefined) !== (query.radius === undefined))
      return { status: "rejected", code: "INVALID_ARGUMENT", message: "supply center and radius together" };
    return game.map(query);
  }, extra.requestId));

  mcp.registerTool("game.rules", {
    description: "Pinned-ruleset summary by default, or static improvements/terrains/resources catalog. Filter an exact name and page with limit/cursor. Live eligibility remains in legal_actions.",
    inputSchema: {
      section: z.string().min(1).max(128).optional(),
      name: z.string().min(1).max(128).optional(),
      limit: z.number().int().min(1).max(128).optional(),
      cursor: z.string().min(1).max(512).optional(),
    },
  }, async (query, extra) => readResult(() => game.rules(query), extra.requestId));

  mcp.registerTool("game.legal_actions", {
    description: "Legal moveTargets, attackTargets, orders, canFound, improvements, workInProgress, researchable and promotions for a unit; constructions and researchable for a city. Supply exactly one unitId or cityId.",
    inputSchema: { unitId: z.number().int().optional(), cityId: z.string().min(1).max(128).optional() },
  }, async ({ unitId, cityId }) => {
    if ((unitId === undefined) === (cityId === undefined)) return err("INVALID_ARGUMENT", "supply exactly one unitId or cityId");
    const res = await game.legalActions(unitId, cityId);
    return "status" in res ? err(res.code, res.message) : ok(res);
  });

  mcp.registerTool("game.act", {
    description: "One typed state-changing command with idempotency key and expected revision. Includes diplomacy.acknowledgeMessage args {messageId,text} for delivered incoming mail; receipt acknowledgement does not imply agreement. No batching.",
    inputSchema: {
      actionId: z.string().min(1).max(128),
      expectedRevision: z.number().int().min(0),
      type: z.string().min(1).max(128),
      args: z.record(z.unknown()),
    },
  }, async (params) => serviceResult(async () => {
    if (params.type === "diplomacy.acknowledgeMessage" &&
        (typeof params.args.messageId !== "string" || !params.args.messageId.trim() || params.args.messageId.length > 128 ||
          typeof params.args.text !== "string" || !params.args.text.trim() || params.args.text.length > 2000 || Buffer.byteLength(params.args.text, "utf8") > 8192))
      return err("INVALID_ARGUMENT", "supply messageId and nonblank acknowledgement text up to 2000 characters", { actionId: params.actionId });
    const res = await game.act({ protocolVersion: 1, ...params, args: params.args as Record<string, unknown> });
    if ("revision" in res) {
      const body = JSON.stringify(res);
      if (body.length > MAX_TOOL_RESPONSE_BYTES)
        return err("INTERNAL_ERROR", "response exceeded bound; re-observe in pages");
      if (params.type === "diplomacy.acknowledgeMessage") recordAcknowledgement(res);
      return ok(res);
    }
    return err(res.code, res.message, {
      ...(res.actionId ? { actionId: res.actionId } : {}),
      ...(res.currentRevision !== undefined ? { currentRevision: res.currentRevision } : {}),
      ...(res.messageIds !== undefined ? { messageIds: res.messageIds } : {}),
    });
  }));

  mcp.registerTool("game.action_result", {
    description: "Look up a prior receipt by actionId. Never resubmit an uncertain mutation.",
    inputSchema: { actionId: z.string().min(1).max(128) },
  }, async ({ actionId }) => serviceResult(async () => {
    const receipt = await game.actionResult(actionId);
    recordAcknowledgement(receipt);
    return ok(receipt);
  }));

  mcp.registerTool("game.notes", {
    description: "Read/replace the acting leader's private strategy notes with a notes revision.",
    inputSchema: {
      expectedNotesRevision: z.number().int().min(0),
      notes: z.string().max(16 * 1024).optional(),
    },
  }, async ({ expectedNotesRevision, notes }) => {
    if (notes === undefined) return ok(await game.readNotes());
    const res = await game.updateNotes(notes, expectedNotesRevision);
    return "notesRevision" in res ? ok(res) : err(res.code, res.message);
  });

  mcp.registerTool("game.end_turn", {
    description: "Verify decisions and required incoming-message acknowledgements before committing end-turn. MESSAGES_UNACKNOWLEDGED lists messageIds to acknowledge explicitly. Never auto-chooses decisions or acknowledgements.",
    inputSchema: {},
  }, async () => serviceResult(async () => {
    const res = await game.endTurn();
    return "turnEnded" in res ? ok(res) : readError(res);
  }));
}

function readBody(req: IncomingMessage, limit: number): Promise<Buffer | null> {
  // Returns null when the body exceeds the bound. The stream is drained (not
  // destroyed) so the caller can still answer 413 on the same connection.
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let tooLarge = false;
    req.on("data", (c: Buffer) => {
      if (tooLarge) return;
      size += c.length;
      if (size > limit) {
        tooLarge = true;
        chunks.length = 0;
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(tooLarge ? null : Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function dbg(...args: unknown[]): void {
  if (process.env["GAME_MCP_DEBUG"]) console.error("[game-mcp]", ...args);
}

export async function startGameMcpServer(game: IGameService, binding: RunBinding, onAcknowledgement?: (entry: ActivityEntry) => void): Promise<StartedGameMcpServer> {
  const bearer = randomBytes(32).toString("hex");
  const bearerBuf = Buffer.from(`Bearer ${bearer}`);

  // Serial dispatch: at most one tool call inside the game service at a time.
  let tail: Promise<void> = Promise.resolve();
  const dispatch = <T>(work: () => Promise<T>): Promise<T> => {
    const run = tail.then(work, work);
    tail = run.then(() => undefined, () => undefined);
    return run;
  };

  let server: Server;
  const onRequest = (req: IncomingMessage, res: ServerResponse) => {
    void dispatch(async () => {
      try {
        const host = req.headers.host ?? "";
        dbg(req.method, req.url, "host=", host, "origin=", req.headers.origin ?? "-", "auth=", (req.headers.authorization ?? "").slice(0, 12));
        if (!host.startsWith("127.0.0.1")) {
          dbg("-> 403 non-loopback");
          res.writeHead(403, { "content-type": "application/json" }).end('{"error":"non-loopback host"}');
          return;
        }
        if (req.headers.origin !== undefined) {
          dbg("-> 403 origin");
          res.writeHead(403, { "content-type": "application/json" }).end('{"error":"browser origin rejected"}');
          return;
        }
        const auth = req.headers.authorization ?? "";
        const authBuf = Buffer.from(auth);
        if (authBuf.length !== bearerBuf.length || !timingSafeEqual(authBuf, bearerBuf)) {
          dbg("-> 401 bearer");
          res.writeHead(401, { "content-type": "application/json" }).end('{"error":"bad or missing bearer"}');
          return;
        }
        if (req.method !== "POST" || req.url?.split("?")[0] !== "/mcp") {
          dbg("-> 405 method/path");
          res.writeHead(405, { "content-type": "application/json" }).end('{"error":"POST /mcp only"}');
          return;
        }
        const raw = await readBody(req, MAX_TOOL_REQUEST_BYTES);
        if (raw === null) {
          dbg("-> 413 too large");
          res.writeHead(413, { "content-type": "application/json" }).end('{"error":"tool request exceeds 64 KiB"}');
          return;
        }
        dbg("body bytes=", raw.length);
        const parsed: unknown = JSON.parse(raw.toString("utf-8"));
        const frames = Array.isArray(parsed) ? parsed : [parsed];
        if (frames.some(frame => frame && typeof frame === "object" && "id" in frame &&
          Buffer.byteLength(JSON.stringify(frame.id), "utf8") > MAX_REQUEST_ID_BYTES)) {
          res.writeHead(400, { "content-type": "application/json" }).end('{"error":"request id exceeds 128 serialized UTF-8 bytes"}');
          return;
        }
        // Stateless mode: a fresh server+transport per request (McpServer is
        // single-transport). Shared `game` stays behind the serial dispatch.
        const mcp = new McpServer({ name: "unciv-game", version: "0.1.0" });
        registerGameTools(mcp, game, binding.civId, onAcknowledgement);
        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: undefined,
          enableJsonResponse: true,
        });
        await mcp.connect(transport);
        try {
          await transport.handleRequest(req, res, parsed);
          // handleRequest dispatches and returns; in JSON-response mode the
          // response is sent asynchronously via transport.send(). Do NOT close
          // here (that truncates to an empty 200) — wait for res to finish.
          await new Promise<void>((resolve) => {
            if (res.writableEnded) resolve();
            else {
              res.once("finish", () => resolve());
              res.once("close", () => resolve());
            }
          });
        } finally {
          await mcp.close().catch(() => undefined);
        }
      } catch (e) {
        if (!res.headersSent) res.writeHead(400, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "bad request" }));
      }
    });
  };

  server = createServer(onRequest);
  await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  if (addr === null || typeof addr === "string") throw new Error("loopback bind failed");
  return {
    url: `http://127.0.0.1:${addr.port}/mcp`,
    bearer,
    binding,
    close: () => new Promise((resolve, reject) => server.close((e) => (e ? reject(e) : resolve()))),
  };
}
