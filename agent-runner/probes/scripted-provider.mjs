/**
 * Scripted provider for supervisor-loop tests (no model, fully deterministic).
 * Speaks real MCP to the supervisor's game server, then exits. Emits a tiny
 * JSONL event stream on stdout that parseScripted() normalizes.
 *
 * Usage: node probes/scripted-provider.mjs --mcp-url URL --bearer TOKEN
 *   [--resume SESSION] [--plan observe,act,end | loop | persist] [--action-id ID]
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const args = Object.fromEntries(
  process.argv.slice(2).map((a, i, arr) => (a.startsWith("--") ? [a.slice(2), arr[i + 1] ?? ""] : [])).filter((p) => p.length),
);
const url = args["mcp-url"];
const bearer = args["bearer"];
const plan = (args.plan ?? "observe,act,end").split(",");
const actionId = args["action-id"] ?? "scripted-1";
// Real engines assign their own IDs/tiles; the fake uses unit 17 at (2,-1).
const unitId = Number(args["unit-id"] ?? 17);
const dest = { x: Number(args["dest-x"] ?? 2), y: Number(args["dest-y"] ?? -1) };
const sessionId = args.resume ?? `scripted-${Math.random().toString(36).slice(2, 10)}`;

const emit = (obj) => console.log(JSON.stringify(obj));
emit({ kind: "scripted", event: "run_started", sessionId });

const transport = new StreamableHTTPClientTransport(new URL(url), {
  requestInit: {
    headers: {
      authorization: `Bearer ${bearer}`,
      accept: "application/json, text/event-stream",
    },
  },
});
const client = new Client({ name: "scripted-provider", version: "0.1.0" });
await client.connect(transport);
emit({ kind: "scripted", event: "session_bound", sessionId });

const tools = await client.listTools();
emit({ kind: "scripted", event: "tools", count: tools.tools.length });

const call = async (name, toolArgs) => {
  const res = await client.callTool({ name, arguments: toolArgs });
  const text = res.content?.[0]?.text ?? "";
  emit({ kind: "scripted", event: "tool", tool: name, input: toolArgs, output: text.slice(0, 300) });
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (parseError) {
    throw new Error(`tool ${name} returned non-JSON text: ${text.slice(0, 300)}`);
  }
};

if (plan.includes("persist")) {
  // Like a model that keeps retrying after the engine has gone away.
  for (;;) {
    await client.callTool({ name: "game.observe", arguments: {} }).catch(() => null);
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

if (plan.includes("loop")) {
  for (;;) {
    await call("game.observe", {});
  }
}

let revision = 42;
for (const step of plan) {
  if (step === "observe") {
    const obs = await call("game.observe", {});
    if (obs && typeof obs.revision === "number") revision = obs.revision;
  } else if (step === "act") {
    await call("game.act", {
      actionId,
      expectedRevision: revision,
      type: "unit.move",
      args: { unitId, destination: dest },
    });
    revision += 1;
  } else if (step === "end") {
    await call("game.end_turn", {});
  }
}
emit({ kind: "scripted", event: "run_completed", sessionId });
await transport.close();
