/**
 * W1 OpenCode capability probe (AI_AGENT_PLAN.md §12 W1, §7.2 OpenCode row).
 * Same contract: game MCP callable, mutation applied (rev 42 -> 43),
 * exact-ID resume, game-only effective catalog, native auth reuse, sanitized
 * stream fixtures.
 *
 * Isolation: game-owned opencode.json in a scratch dir (MCP remote + bearer
 * header via {env:} expansion, oauth:false to disable OAuth discovery for
 * the local bearer endpoint, deny-by-default tools with a game exception)
 * plus --pure (no external plugins). Whether the global config merges in
 * is VERIFIED from the stream (plan §7.2: prove isolation despite merge
 * behavior) — see checkpoint CP-012.
 *
 * Usage: node probes/opencode-w1.mjs
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
const scratch = join(outDir, "opencode-cwd");
mkdirSync(scratch, { recursive: true });

const OPENCODE = "/Users/noahkenny/.opencode/bin/opencode";

function runOpencode({ prompt, resumeId, outFile }) {
  const args = ["run", "--format", "json", "--pure", "--dir", scratch];
  if (resumeId) args.push("--session", resumeId);
  args.push(prompt);
  return new Promise((resolve, reject) => {
    const child = spawn(OPENCODE, args, { cwd: scratch, env: { ...process.env }, stdio: ["ignore", "pipe", "pipe"] });
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
    try { events.push(JSON.parse(line)); } catch { /* ignore */ }
  }
  const toolCalls = [];
  let sessionId;
  let finalText = "";
  for (const e of events) {
    const s = JSON.stringify(e);
    if (!sessionId) {
      const m = s.match(/"sessionID"\s*:\s*"([^"]+)"|\"session_id\"\s*:\s*\"([^\"]+)\"|\"id\"\s*:\s*\"ses_[^\"]+\"/);
      if (m) sessionId = m[1] ?? m[2] ?? m[0].match(/"([^"]+)"/)?.[1];
    }
    const t = s.match(/"tool"\s*:\s*"([^"]*game[^"]*)"/);
    if (t && !toolCalls.includes(t[1])) toolCalls.push(t[1]);
    if (e.type === "text" || e.type === "result") finalText += String(e.text ?? e.result ?? "").slice(0, 100);
  }
  console.log(`--- ${label}: ${events.length} events, session=${sessionId ?? "NONE"}`);
  console.log(`    game tools called: ${toolCalls.join(", ") || "NONE"}`);
  console.log(`    final: ${finalText.slice(0, 120)}`);
  return { events, toolCalls, sessionId };
}

const game = new FakeGameService();
const server = await startGameMcpServer(game, {
  matchId: "w1-opencode", timelineId: "t1", civId: "civ-rome", runId: "run-1", authorityEpoch: 1,
});
console.log("MCP url:", server.url);
process.env.GAME_MCP_BEARER = server.bearer;

// Game-owned config. Bearer via {env:} expansion (value never on disk outside
// this gitignored file, deleted afterwards). oauth:false per plan §7.3.
const configPath = join(scratch, "opencode.json");
writeFileSync(configPath, JSON.stringify({
  mcp: {
    game: {
      type: "remote",
      url: server.url,
      enabled: true,
      oauth: false,
      headers: { Authorization: "Bearer {env:GAME_MCP_BEARER}" },
      timeout: 30000,
    },
  },
  tools: { "*": false, "game_*": true },
  share: "disabled",
  autoupdate: false,
}));

try {
  const prompt1 = "You have game MCP tools. Do exactly this: (1) call the game observe tool; (2) call the game act tool with actionId 'opencode-probe-1', expectedRevision 42, type 'unit.move', args {unitId: 17, destination: {x: 2, y: -1}}; (3) reply with one word DONE. Use only game tools.";
  const r1 = await runOpencode({ prompt: prompt1, outFile: join(outDir, "opencode-probe-1.jsonl") });
  console.log("opencode exit:", r1.status, "signal:", r1.signal ?? "-", "stderr:", (r1.stderr ?? "").slice(-300));
  const s1 = summarize(r1.stdout ?? "", "invoke-1");
  console.log("server revision after invoke-1:", game.revision);

  let resumed = { sessionId: undefined };
  if (s1.sessionId) {
    const r2 = await runOpencode({
      prompt: "Call the game observe tool and reply with just the revision number you see. Use only game tools.",
      resumeId: s1.sessionId, outFile: join(outDir, "opencode-probe-2-resume.jsonl"),
    });
    console.log("opencode resume exit:", r2.status, "signal:", r2.signal ?? "-");
    resumed = summarize(r2.stdout ?? "", "invoke-2-resume");
  }

  const pass =
    game.revision === 43 &&
    s1.toolCalls.length > 0 &&
    !!s1.sessionId &&
    resumed.sessionId === s1.sessionId;
  console.log(pass ? "OPENCODE-W1: PASS" : "OPENCODE-W1: FAIL (see fixture JSONL + checkpoint log)");
  process.exitCode = pass ? 0 : 1;
} finally {
  rmSync(configPath, { force: true });
  await server.close();
}
