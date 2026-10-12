import { afterEach, expect, it, vi } from "vitest";
import { GAME_INSTRUCTIONS } from "../src/game-instructions.js";
import { buildTurnPrompt } from "../src/turn-prompt.js";
import { GAME_TOOL_ALLOWLIST, claudeSpawn, codexSpawn, opencodeSpawn } from "../src/provider-spawn.js";
import { buildSpawnForProvider } from "../src/daemon.js";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

afterEach(() => vi.unstubAllEnvs());

it("teaches geography, memory limits and exact worker choices while preserving personality", () => {
  const prompt = buildTurnPrompt({ displayName: "Ada", personality: "Peaceful. Never launch nuclear weapons.", rulesetName: "Civ V", protocolVersion: 1, civId: "Rome", turnSerial: 1, stateRevision: 42, notes: "", recentEvents: [], pendingDecisions: [], guidance: [] });
  for (const text of ["game.inspect({unitId:49})", "game.map({center:{x:48,y:-33},radius:2})", "game.legal_actions({unitId:49})", "terrain", "REMEMBERED", "lastSeenTurn/lastSeenRevision", "historical knowledge", "not current enemy intelligence", "worldWrap", "unit.stopImprovement", "canCancelWork", "fresh worker options before assigning replacement work", "canonical coordinates", "hex coordinates", "improvements", "workInProgress", "turnsToBuild", "separate from city constructions", "re-observe and start a new map query", "Peaceful. Never launch nuclear weapons."]) expect(prompt).toContain(text);
  expect(GAME_INSTRUCTIONS).toContain('"type":"unit.build"');
  expect(GAME_INSTRUCTIONS).toContain('name:"Farm"');
});

it("allows exactly all twelve game tools through each provider configuration", () => {
  for (const name of ["CODEX", "CLAUDE", "OPENCODE"]) vi.stubEnv(`UNCIV_${name}_EXECUTABLE`, process.execPath);
  expect(GAME_TOOL_ALLOWLIST).toHaveLength(12);
  const codex = codexSpawn({ prompt: "play", mcpUrl: "http://127.0.0.1/mcp", bearerEnvVar: "TEST_GAME_BEARER", cwd: "/tmp", sandboxDangerous: true });
  expect(codex.argv.find(a => a.includes("enabled_tools="))).toContain(`enabled_tools=${JSON.stringify(GAME_TOOL_ALLOWLIST)}`);
  const claude = claudeSpawn({ prompt: "play", mcpConfigPath: "/tmp/game.json", cwd: "/tmp" });
  expect(claude.argv[claude.argv.indexOf("--allowedTools") + 1]?.split(" ")).toEqual(GAME_TOOL_ALLOWLIST.map(n => `mcp__game__${n.replaceAll(".", "_")}`));
  const dir = mkdtempSync(join(tmpdir(), "unciv-map-config-"));
  try {
    buildSpawnForProvider("opencode", "play", undefined, "http://127.0.0.1/mcp", "test", { model: "opencode-go/deepseek-v4.1-flash", reasoning: "max" }, dir);
    const tools = JSON.parse(readFileSync(join(dir, "opencode.json"), "utf8")).tools;
    expect(tools).toEqual({ "*": false, ...Object.fromEntries(GAME_TOOL_ALLOWLIST.map(n => [`game_${n.replaceAll(".", "_")}`, true])) });
  } finally { rmSync(dir, { recursive: true, force: true }); }
  const opencode = opencodeSpawn({ prompt: "play", dir: "/tmp", model: "opencode-go/deepseek-v4.1-flash", reasoning: "max" });
  expect(opencode.argv).toEqual(expect.arrayContaining(["-m", "opencode-go/deepseek-v4.1-flash", "--variant", "max"]));
});
