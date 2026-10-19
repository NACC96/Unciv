/**
 * Codex-shaped provider for post-turn compaction tests (no model). Ends the
 * turn over real MCP, emits Codex JSONL for the end_turn call and a closing
 * message, then "compacts" for --compact-ms before turn.completed. While
 * compacting it tries one more game tool call and reports whether the
 * supervisor still accepted it (it must not: the turn has been released).
 *
 * Usage: node probes/compacting-provider.mjs --mcp-url URL --bearer TOKEN
 *   --compact-ms 1500 --report /path/report.json [--session ID]
 */
import { writeFileSync } from "node:fs";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, arr) => (a.startsWith("--") ? [a.slice(2), arr[i + 1] ?? ""] : [])).filter((p) => p.length),
);
const emit = (obj) => console.log(JSON.stringify(obj));
const sessionId = args.session || "compacting-session";
emit({ type: "thread.started", thread_id: sessionId });
emit({ type: "turn.started" });

const connect = async () => {
  const client = new Client({ name: "compacting-provider", version: "0.1.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(args["mcp-url"]), {
    requestInit: { headers: { authorization: `Bearer ${args.bearer}`, accept: "application/json, text/event-stream" } },
  }));
  return client;
};

const client = await connect();
const item = { id: "item_1", type: "mcp_tool_call", server: "game", tool: "game.end_turn", arguments: {} };
emit({ type: "item.started", item: { ...item, status: "in_progress" } });
const ended = await client.callTool({ name: "game.end_turn", arguments: {} });
emit({ type: "item.completed", item: { ...item, result: ended, error: null, status: "completed" } });
emit({ type: "item.completed", item: { id: "item_2", type: "agent_message", text: "Turn committed; the border holds." } });

// Compaction: the supervisor has released the turn and revoked the game tools.
// Give the supervisor a moment to see the closing message and release the turn.
await new Promise((resolve) => setTimeout(resolve, 300));
let lateCall = "accepted";
try {
  const late = await client.callTool({ name: "game.observe", arguments: {} });
  if (late.isError) lateCall = "rejected";
} catch { lateCall = "rejected"; }
await new Promise((resolve) => setTimeout(resolve, Number(args["compact-ms"] ?? 1000)));
writeFileSync(args.report, JSON.stringify({ lateCall }));
emit({ type: "turn.completed", usage: { input_tokens: 10, cached_input_tokens: 0, output_tokens: 5 } });
process.exit(0);
