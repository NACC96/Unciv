/**
 * Scripted provider for supervisor-loop tests (no model, fully deterministic).
 * Speaks real MCP to the supervisor's game server, then exits. Emits a tiny
 * JSONL event stream on stdout that parseScripted() normalizes.
 *
 * Usage: node probes/scripted-provider.mjs --mcp-url URL --bearer TOKEN
 *   [--resume SESSION] [--plan observe,act,end | loop] [--action-id ID]
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
  return text ? JSON.parse(text) : null;
};

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
      args: { unitId: 17, destination: { x: 2, y: -1 } },
    });
    revision += 1;
  } else if (step === "end") {
    await call("game.end_turn", {});
  }
}
emit({ kind: "scripted", event: "run_completed", sessionId });
await transport.close();
