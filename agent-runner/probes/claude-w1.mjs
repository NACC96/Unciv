/**
 * W1 Claude capability probe (AI_AGENT_PLAN.md §12 W1, §7.2 Claude row).
 * Same contract as the Codex probe: game MCP callable, mutation applied
 * (rev 42 -> 43), exact-ID resume, game-only effective catalog, native auth
 * reuse, sanitized stream fixtures.
 *
 * Isolation: --restricted (ignores user/project/local settings, removes
 * command-running built-ins) + --strict-mcp-config (only our --mcp-config
 * server loads) + --tools "" (no built-in tools at all). MCP tools are
 * allowlisted via --allowedTools mcp__game__*.
 *
 * Bearer note: claude --mcp-config has no env-var indirection for headers,
 * so the bearer VALUE goes into a temp config file under .agent-data/
 * (gitignored, 0600, deleted after the run). Fixtures are grepped for the
 * bearer before anything is copied to test-fixtures/.
 *
 * Usage: node probes/claude-w1.mjs
 */
import { spawn } from "node:child_process";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { FakeGameService } from "../dist/fake-game-mcp.js";
import { startGameMcpServer } from "../dist/game-mcp-server.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const outDir = join(repoRoot, ".agent-data", "w1-probes");
mkdirSync(outDir, { recursive: true });
const scratch = join(outDir, "claude-cwd");
mkdirSync(scratch, { recursive: true });

const CLAUDE = "/Users/noahkenny/.local/bin/claude";
const GAME_TOOLS = [
  "game.observe", "game.inspect", "game.rules", "game.legal_actions",
  "game.act", "game.action_result", "game.notes", "game.end_turn",
];
// Claude normalizes MCP tool names to mcp__<server>__<tool> with DOTS
// converted to underscores (init event proves: mcp__game__game_observe).
// Allowlisting must use the normalized form, or every call is denied.
const allowedTools = GAME_TOOLS.map((t) => `mcp__game__${t.replaceAll(".", "_")}`).join(" ");

function runClaude({ prompt, resumeId, mcpConfig, outFile }) {
  const args = [
    "-p", "--output-format", "stream-json", "--verbose",
    "--restricted", "--strict-mcp-config",
    "--mcp-config", mcpConfig,
    "--tools", "",
    "--allowedTools", allowedTools,
    "--permission-mode", "dontAsk",
    "--disable-slash-commands",
  ];
  if (resumeId) args.push("--resume", resumeId);
  args.push(prompt);
  return new Promise((resolve, reject) => {
    const child = spawn(CLAUDE, args, { cwd: scratch, env: { ...process.env }, stdio: ["ignore", "pipe", "pipe"] });
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
  const events = [];
  for (const line of jsonl.split("\n")) {
    if (!line.trim()) continue;
    try { events.push(JSON.parse(line)); } catch { /* partials may split lines */ }
  }
  const toolCalls = [];
  let sessionId;
  let finalText = "";
  for (const e of events) {
    if (e.session_id && !sessionId) sessionId = e.session_id;
    const s = JSON.stringify(e);
    const m = s.match(/"name":"(mcp__game__[a-z_.]+)"/);
    if (m && !toolCalls.includes(m[1])) toolCalls.push(m[1]);
    if (e.type === "result") finalText = String(e.result ?? "").slice(0, 200);
  }
  console.log(`--- ${label}: ${events.length} events, session=${sessionId ?? "NONE"}, exit-text=${finalText.slice(0, 80)}`);
  console.log(`    game tools called: ${toolCalls.join(", ") || "NONE"}`);
  return { events, toolCalls, sessionId };
}

const game = new FakeGameService();
const server = await startGameMcpServer(game, {
  matchId: "w1-claude", timelineId: "t1", civId: "civ-rome", runId: "run-1", authorityEpoch: 1,
});
console.log("MCP url:", server.url);

// Temp config holds the bearer VALUE (no env indirection available). 0600,
// gitignored dir, unlinked in `finally` below.
const mcpConfig = join(outDir, "claude-mcp.tmp.json");
writeFileSync(mcpConfig, JSON.stringify({
  mcpServers: {
    game: { type: "http", url: server.url, headers: { Authorization: `Bearer ${server.bearer}` } },
  },
}), { mode: 0o600 });

try {
  const prompt1 = "You have game MCP tools named mcp__game__game_observe etc. Do exactly this: (1) call the game observe tool; (2) call the game act tool with actionId 'claude-probe-1', expectedRevision 42, type 'unit.move', args {unitId: 17, destination: {x: 2, y: -1}}; (3) reply with one word DONE. Use only game tools.";
  const r1 = await runClaude({ prompt: prompt1, mcpConfig, outFile: join(outDir, "claude-probe-1.jsonl") });
  console.log("claude exit:", r1.status, "signal:", r1.signal ?? "-", "stderr:", (r1.stderr ?? "").slice(-300));
  const s1 = summarize(r1.stdout ?? "", "invoke-1");
  console.log("server revision after invoke-1:", game.revision);

  let resumed = { sessionId: undefined };
  if (s1.sessionId) {
    const r2 = await runClaude({
      prompt: "Call the game observe tool and reply with just the revision number you see. Use only game tools.",
      resumeId: s1.sessionId, mcpConfig, outFile: join(outDir, "claude-probe-2-resume.jsonl"),
    });
    console.log("claude resume exit:", r2.status, "signal:", r2.signal ?? "-");
    resumed = summarize(r2.stdout ?? "", "invoke-2-resume");
  }

  const pass =
    game.revision === 43 &&
    s1.toolCalls.some((t) => t.includes("observe")) &&
    s1.toolCalls.some((t) => t.endsWith("act") || t.includes(".act")) &&
    !!s1.sessionId &&
    resumed.sessionId === s1.sessionId;
  console.log(pass ? "CLAUDE-W1: PASS" : "CLAUDE-W1: FAIL (see fixture JSONL + checkpoint log)");
  process.exitCode = pass ? 0 : 1;
} finally {
  rmSync(mcpConfig, { force: true });
  await server.close();
}
