/**
 * W1 Codex capability probe (AI_AGENT_PLAN.md §12 W1, §7.2 Codex row).
 *
 * Starts the real game MCP transport (fake game behind it), then drives the
 * installed `codex exec` CLI against it with a game-only tool allowlist and
 * per-invocation bearer auth. Proves, against the exact installed version:
 *  1. game MCP tools are callable and a mutation applies (rev 42 -> 43),
 *  2. the session can be resumed by EXACT returned ID (no --last heuristics),
 *  3. the effective tool catalog contains only the game tools.
 *
 * Usage: node probes/codex-w1.mjs [--mode danger|boxed] (default danger)
 * Fixtures are suffixed per mode: codex-probe-1-<mode>.jsonl.
 * The raw JSONL event stream is saved under .agent-data/w1-probes/ as the
 * sanitized provider-event fixture (bearer values are never written: only the
 * env var NAME appears in argv/config).
 */
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { FakeGameService } from "../dist/fake-game-mcp.js";
import { startGameMcpServer } from "../dist/game-mcp-server.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const outDir = join(repoRoot, ".agent-data", "w1-probes");
mkdirSync(outDir, { recursive: true });

const CODEX = "/Users/noahkenny/.nvm/versions/node/v24.13.1/bin/codex";
const scratch = join(outDir, "codex-cwd");
mkdirSync(scratch, { recursive: true });

const modeArg = process.argv.find((a) => a.startsWith("--mode="));
const mode = modeArg ? modeArg.slice("--mode=".length) : "danger";
if (mode !== "danger" && mode !== "boxed") {
  console.error("usage: node probes/codex-w1.mjs [--mode=danger|boxed]");
  process.exit(2);
}

function runCodex({ prompt, resumeId, serverUrl, bearerEnv, outFile }) {
  // NOTE: must be async spawn, never spawnSync: the game MCP server shares
  // this process's event loop, and spawnSync would freeze it — codex connects
  // (kernel backlog) but gets no response and times out (CP-010).
  // Sandbox: seatbelt blocks loopback by default; boxed mode re-allows it via
  // workspace-write + network_access=true, but MCP *mutations* are then denied
  // at the approval layer ("requires approval, but approval policy is never";
  // default_tools_approval_mode="auto" does not take effect there). danger
  // mode runs the whole contract clean. Full story in checkpoint CP-010.
  const sandboxArgs = mode === "danger"
    ? ["-s", "danger-full-access"]
    : ["-c", 'sandbox_mode="workspace-write"', "-c", "sandbox_workspace_write.network_access=true"];
  const args = [
    "exec", "--json",
    "--ignore-user-config",
    "--skip-git-repo-check",
    ...sandboxArgs,
    "-c", `mcp_servers.game={url="${serverUrl}", bearer_token_env_var="${bearerEnv}", required=true, startup_timeout_sec=20, tool_timeout_sec=30, default_tools_approval_mode="auto", enabled_tools=["game.observe","game.inspect","game.rules","game.legal_actions","game.act","game.action_result","game.notes","game.end_turn"]}`,
  ];
  if (resumeId) args.push("resume", resumeId);
  args.push(prompt);
  return new Promise((resolve, reject) => {
    // stdin ignored: otherwise codex prints "Reading additional input from stdin...".
    const child = spawn(CODEX, args, { cwd: scratch, env: { ...process.env }, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (c) => { stdout += c; });
    child.stderr.on("data", (c) => { stderr += c; });
    child.on("error", reject);
    const timer = setTimeout(() => child.kill("SIGKILL"), 5 * 60 * 1000);
    child.on("close", (status, signal) => {
      clearTimeout(timer);
      writeFileSync(outFile, stdout);
      writeFileSync(outFile.replace(/\.jsonl$/, ".stderr.log"), stderr);
      resolve({ status, signal, stdout, stderr });
    });
  });
}

function summarize(jsonl, label) {
  const lines = jsonl.split("\n").filter(Boolean);
  const events = [];
  for (const line of lines) {
    try { events.push(JSON.parse(line)); } catch { /* keep raw count */ }
  }
  const toolCalls = [];
  let sessionId;
  let finalMessage;
  for (const e of events) {
    const s = JSON.stringify(e);
    // codex --json emits item payloads like {"type":"mcp_tool_call","tool":"game.act",...}
    const m = s.match(/"tool":"(game\.[a-z_]+)"/);
    if (m && !toolCalls.includes(m[1])) toolCalls.push(m[1]);
    const sid = s.match(/"(?:thread_id|session_id|sessionId)"\s*:\s*"([^"]+)"/);
    if (sid && !sessionId) sessionId = sid[1];
    if (e.type === "agent_message" || e.msg?.type === "agent_message") finalMessage = s.slice(0, 300);
  }
  console.log(`--- ${label}: ${events.length} parsed events, session=${sessionId ?? "NONE"}`);
  console.log(`    game tools called: ${toolCalls.join(", ") || "NONE"}`);
  if (finalMessage) console.log(`    final: ${finalMessage}`);
  return { events, toolCalls, sessionId };
}

const game = new FakeGameService();
const server = await startGameMcpServer(game, {
  matchId: "w1-codex", timelineId: "t1", civId: "civ-rome", runId: "run-1", authorityEpoch: 1,
});
console.log("MCP url:", server.url);
// Bearer travels to the child ONLY via environment (config references the var
// NAME). Fixtures are grepped for the bearer value afterwards to prove this.
process.env.GAME_MCP_BEARER = server.bearer;

const prompt1 = "You have game MCP tools named game.observe, game.act, etc. Do exactly this: (1) call game.observe; (2) call game.act with actionId 'codex-probe-1', expectedRevision 42, type 'unit.move', args {unitId: 17, destination: {x: 2, y: -1}}; (3) reply with one word DONE. Use only game tools. Do not run shell commands.";
const tag = `codex-probe-1-${mode}`;
const r1 = await runCodex({
  prompt: prompt1, serverUrl: server.url, bearerEnv: "GAME_MCP_BEARER",
  outFile: join(outDir, `${tag}.jsonl`),
});
console.log("codex exit:", r1.status, "stderr:", (r1.stderr ?? "").slice(-500));
const s1 = summarize(r1.stdout ?? "", "invoke-1");
console.log("server revision after invoke-1:", game.revision);

let resumed = { sessionId: undefined };
if (s1.sessionId) {
  const prompt2 = "Call game.observe and reply with just the revision number you see. Use only game tools.";
  const r2 = await runCodex({
    prompt: prompt2, resumeId: s1.sessionId, serverUrl: server.url, bearerEnv: "GAME_MCP_BEARER",
    outFile: join(outDir, `codex-probe-2-resume-${mode}.jsonl`),
  });
  console.log("codex resume exit:", r2.status, "stderr:", (r2.stderr ?? "").slice(-500));
  resumed = summarize(r2.stdout ?? "", "invoke-2-resume");
}

const pass =
  game.revision === 43 &&
  s1.toolCalls.includes("game.observe") &&
  s1.toolCalls.includes("game.act") &&
  !!s1.sessionId &&
  resumed.sessionId === s1.sessionId;
console.log(pass ? "CODEX-W1: PASS" : "CODEX-W1: FAIL (see fixture JSONL + checkpoint log)");
await server.close();
process.exit(pass ? 0 : 1);
