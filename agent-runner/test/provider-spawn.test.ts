import { afterEach, describe, expect, it, vi } from "vitest";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { claudeSpawn, codexSpawn, opencodeSpawn, resolveProviderExecutable } from "../src/provider-spawn.js";
import { NodeRunner } from "../src/supervisor.js";
import { GAME_INSTRUCTIONS } from "../src/game-instructions.js";

const roots: string[] = [];
afterEach(() => { vi.unstubAllEnvs(); for (const root of roots.splice(0)) rmSync(root, { recursive: true }); });
function root() { const dir = mkdtempSync(join(tmpdir(), "unciv-cli-test-")); roots.push(dir); return dir; }
function executable(path: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, "test fixture: never execute\n"); chmodSync(path, 0o755); return path;
}

describe("provider executable discovery without provider launches", () => {
  it("uses explicit configuration before PATH and per-user installs", () => {
    const home = root(); const configured = executable(join(home, "custom codex"));
    const path = join(home, "path"); executable(join(path, "codex")); executable(join(home, ".local/bin/codex"));
    expect(resolveProviderExecutable("codex", { homeDir: home, env: { UNCIV_CODEX_EXECUTABLE: configured, PATH: path } })).toBe(configured);
  });

  it("rejects an invalid explicit override instead of silently using another CLI", () => {
    const home = root(); const path = join(home, "path"); executable(join(path, "claude"));
    expect(() => resolveProviderExecutable("claude", { homeDir: home, env: { PATH: path, UNCIV_CLAUDE_EXECUTABLE: join(home, "missing") } }))
      .toThrow(/UNCIV_CLAUDE_EXECUTABLE.*executable/i);
    expect(() => resolveProviderExecutable("claude", { homeDir: home, env: { UNCIV_CLAUDE_EXECUTABLE: "relative/claude" } })).toThrow(/absolute/i);
  });

  it("respects PATH order, ignores empty entries and non-executable files", () => {
    const home = root(); const first = join(home, "first"); const second = join(home, "second");
    const skipped = executable(join(first, "opencode")); chmodSync(skipped, 0o644);
    const selected = executable(join(second, "opencode")); executable(join(home, ".opencode/bin/opencode"));
    expect(resolveProviderExecutable("opencode", { homeDir: home, env: { PATH: `:${first}:${second}` } })).toBe(selected);
  });

  it("discovers standard per-user native installs and NVM Codex without a shell profile", () => {
    const home = root(); const claude = executable(join(home, ".local/bin/claude"));
    const opencode = executable(join(home, ".opencode/bin/opencode"));
    executable(join(home, ".nvm/versions/node/v22.9.0/bin/codex"));
    const codex = executable(join(home, ".nvm/versions/node/v24.13.1/bin/codex"));
    const options = { homeDir: home, env: {} };
    expect(resolveProviderExecutable("claude", options)).toBe(claude);
    expect(resolveProviderExecutable("opencode", options)).toBe(opencode);
    expect(resolveProviderExecutable("codex", options)).toBe(codex);
    expect(() => resolveProviderExecutable("codex", { homeDir: root(), env: {} })).toThrow(/Install.*codex.*PATH.*UNCIV_CODEX_EXECUTABLE/i);
  });

  it("preserves tested invocation/auth arguments and treats all values as literal argv", () => {
    for (const name of ["CODEX", "CLAUDE", "OPENCODE"]) vi.stubEnv(`UNCIV_${name}_EXECUTABLE`, process.execPath);
    const prompt = 'literal $(touch never) "prompt"';
    const codex = codexSpawn({ prompt, resumeId: "codex-session", mcpUrl: "http://127.0.0.1:1234/mcp", bearerEnvVar: "GAME_MCP_BEARER", cwd: "/scratch", sandboxDangerous: true, model: "model", reasoning: "high" });
    expect(codex.executable).toBe(process.execPath);
    expect(codex.argv).toEqual(["exec", "--json", "--ignore-user-config", "--skip-git-repo-check",
      "--disable", "plugins", "--disable", "apps", "--enable", "skip_host_skill_discovery", "--disable", "skill_search",
      "--disable", "shell_tool", "--disable", "in_app_browser", "--disable", "multi_agent", "-c", 'web_search="disabled"',
      "-s", "danger-full-access", "-c", `developer_instructions=${JSON.stringify(GAME_INSTRUCTIONS)}`, "-c", 'model_reasoning_summary="detailed"',
      "-m", "model", "-c", 'model_reasoning_effort="high"', "-c", expect.stringContaining('bearer_token_env_var="GAME_MCP_BEARER"'), "resume", "codex-session", prompt]);
    const claude = claudeSpawn({ prompt, resumeId: "claude-session", mcpConfigPath: "/scratch/mcp.json", cwd: "/scratch", model: "model", reasoning: "high" });
    expect(claude.executable).toBe(process.execPath);
    expect(claude.argv).toEqual(["-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages", "--include-hook-events",
      "--append-system-prompt", GAME_INSTRUCTIONS, "--restricted", "--strict-mcp-config", "--mcp-config", "/scratch/mcp.json", "--tools", "", "--allowedTools", expect.stringContaining("mcp__game__game_act"), "--permission-mode", "dontAsk", "--disable-slash-commands", "--model", "model", "--effort", "high", "--resume", "claude-session", prompt]);
    expect(opencodeSpawn({ prompt, resumeId: "open-session", dir: "/scratch", model: "model", reasoning: "high" }))
      .toEqual({ executable: process.execPath, cwd: "/scratch", argv: ["run", "--format", "json", "--pure", "--dir", "/scratch", "-m", "model", "--variant", "high", "--session", "open-session", prompt] });
  });

  it("runs a harmless Node-script fixture from a GUI PATH with no node executable", async () => {
    const home = root();
    const script = join(home, "node-cli-fixture");
    writeFileSync(script, '#!/usr/bin/env node\nconsole.log(JSON.stringify({arg:process.argv[2],marker:process.env.FIXTURE_MARKER,runtime:process.version}));\n');
    chmodSync(script, 0o755);
    const child = new NodeRunner(join(home, "logs")).spawn({ executable: script, argv: ["literal $(touch never)"], cwd: home }, { PATH: home, FIXTURE_MARKER: "preserved" });
    const lines: string[] = [];
    child.stdoutLines(line => lines.push(line));
    expect(await child.wait()).toEqual({ code: 0, signal: null });
    expect(child.stderrText()).toBe("");
    expect(lines.map(line => JSON.parse(line))).toEqual([{ arg: "literal $(touch never)", marker: "preserved", runtime: process.version }]);
  });
});
