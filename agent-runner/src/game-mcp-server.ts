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

export const MAX_TOOL_REQUEST_BYTES = 64 * 1024;
export const MAX_TOOL_RESPONSE_BYTES = 256 * 1024;

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

function registerGameTools(mcp: McpServer, game: IGameService): void {
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

  mcp.registerTool("game.rules", {
    description: "Pinned-ruleset summary (static rules may be cached).",
    inputSchema: { section: z.string().max(128).optional() },
  }, async () => ok(await game.rules()));

  mcp.registerTool("game.legal_actions", {
    description: "Typed legal actions for one entity.",
    inputSchema: { unitId: z.number().int() },
  }, async ({ unitId }) => {
    const res = await game.legalActions(unitId);
    return "status" in res ? err(res.code, res.message) : ok(res);
  });

  mcp.registerTool("game.act", {
    description: "One typed state-changing command with idempotency key and expected revision. No batching.",
    inputSchema: {
      actionId: z.string().min(1).max(128),
      expectedRevision: z.number().int().min(0),
      type: z.string().min(1).max(128),
      args: z.record(z.unknown()),
    },
  }, async (params) => {
    const res = await game.act({ protocolVersion: 1, ...params, args: params.args as Record<string, unknown> });
    if ("revision" in res) {
      const body = JSON.stringify(res);
      if (body.length > MAX_TOOL_RESPONSE_BYTES)
        return err("INTERNAL_ERROR", "response exceeded bound; re-observe in pages");
      return ok(res);
    }
    return err(res.code, res.message, {
      ...(res.actionId ? { actionId: res.actionId } : {}),
      ...(res.currentRevision !== undefined ? { currentRevision: res.currentRevision } : {}),
    });
  });

  mcp.registerTool("game.action_result", {
    description: "Look up a prior receipt by actionId. Never resubmit an uncertain mutation.",
    inputSchema: { actionId: z.string().min(1).max(128) },
  }, async ({ actionId }) => ok(await game.actionResult(actionId)));

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
    description: "Verify completion requirements and commit end-turn. Never auto-chooses missing decisions.",
    inputSchema: {},
  }, async () => {
    const res = await game.endTurn();
    return "turnEnded" in res ? ok(res) : err(res.code, res.message);
  });
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

export async function startGameMcpServer(game: IGameService, binding: RunBinding): Promise<StartedGameMcpServer> {
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
        // Stateless mode: a fresh server+transport per request (McpServer is
        // single-transport). Shared `game` stays behind the serial dispatch.
        const mcp = new McpServer({ name: "unciv-game", version: "0.1.0" });
        registerGameTools(mcp, game);
        const transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: undefined,
          enableJsonResponse: true,
        });
        await mcp.connect(transport);
        try {
          await transport.handleRequest(req, res, JSON.parse(raw.toString("utf-8")));
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
