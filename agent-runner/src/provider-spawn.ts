import { GAME_INSTRUCTIONS } from "./game-instructions.js";

/**
 * Provider invocation builders (AI_AGENT_PLAN.md §7.2).
 *
 * Absolute executable paths, explicit environments, argument arrays — no
 * shell, no interpolation of names/models/personalities into commands, no
 * interactive profile sourcing. Arguments here are the W1-proven configurations
 * from agent-runner/probes/* (see W1-COMPATIBILITY.md + CP-010–CP-012).
 */
import { accessSync, constants, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, isAbsolute, join, resolve } from "node:path";

export const GAME_TOOL_ALLOWLIST = [
  "game.observe",
  "game.message_image",
  "game.inspect",
  "game.tile",
  "game.map",
  "game.rules",
  "game.legal_actions",
  "game.act",
  "game.act_batch",
  "game.action_result",
  "game.notes",
  "game.end_turn",
];

export interface SpawnSpec {
  executable: string;
  argv: string[];
  cwd: string;
  context?: { matchId: string; civId: string; turnSerial: number; runId: string; provider?: string; model?: string; reasoning?: string;
    sessionArm?: string; sessionAge?: number; resumed?: boolean };
}

type Provider = "codex" | "claude" | "opencode";

/** Only reads executable metadata: no CLI invocations, shells, or profile sourcing. */
export function resolveProviderExecutable(
  provider: Provider,
  options: { env?: NodeJS.ProcessEnv; homeDir?: string } = {},
): string {
  const env = options.env ?? process.env;
  const home = options.homeDir ?? homedir();
  const variable = `UNCIV_${provider.toUpperCase()}_EXECUTABLE`;
  const isExecutable = (candidate: string) => {
    try {
      if (!statSync(candidate).isFile()) return false;
      accessSync(candidate, constants.X_OK);
      return true;
    } catch { return false; }
  };
  const configured = env[variable];
  if (configured !== undefined) {
    if (!isAbsolute(configured) || !isExecutable(configured))
      throw new Error(`${variable} must be an absolute path to an executable ${provider} CLI; fix or unset the override to use discovery.`);
    return resolve(configured);
  }
  const names = process.platform === "win32" ? [`${provider}.exe`, provider] : [provider];
  // Empty PATH entries mean cwd in shells; deliberately exclude them here.
  const directories = (env["PATH"] ?? "").split(delimiter).filter(dir => dir.length > 0);
  if (provider === "opencode") directories.push(join(home, ".opencode", "bin"));
  directories.push(join(home, ".local", "bin"), join(home, ".volta", "bin"), join(home, ".npm-global", "bin"));
  const nvmRoot = join(home, ".nvm", "versions", "node");
  try {
    // GUI launches may lack the PATH supplied by an interactive NVM profile.
    const versions = readdirSync(nvmRoot).filter(name => /^v\d+\.\d+\.\d+$/.test(name));
    versions.sort((a, b) => {
      const av = a.slice(1).split(".").map(Number), bv = b.slice(1).split(".").map(Number);
      for (let index = 0; index < 3; index++) if (av[index] !== bv[index]) return bv[index] - av[index];
      return 0;
    });
    directories.push(...versions.map(version => join(nvmRoot, version, "bin")));
  } catch { /* NVM is optional. */ }
  for (const directory of directories) {
    for (const name of names) {
      const candidate = resolve(directory, name);
      if (isExecutable(candidate)) return candidate;
    }
  }
  throw new Error(`Install the ${provider} CLI and add it to PATH, or set ${variable} to its absolute executable path. No executable was found in PATH or standard per-user installs.`);
}

export function codexSpawn(args: {
  prompt: string;
  resumeId?: string;
  mcpUrl: string;
  bearerEnvVar: string;
  cwd: string;
  sandboxDangerous: true;
  model?: string;
  reasoning?: string;
}): SpawnSpec {
  // macOS seatbelt blocks loopback for sandboxed MCP; danger-full-access is
  // required on this platform (CP-010). Shell restriction for Codex comes
  // from the MCP allowlist + approvals, enforced and audited separately.
  if (!args.sandboxDangerous) throw new Error("codex loopback MCP requires danger-full-access on macOS");
  const argv = [
    "exec", "--json",
    "--ignore-user-config",
    "--skip-git-repo-check",
    // Game runs do not need the desktop's installed plugins, skills or coding tools.
    "--disable", "plugins", "--disable", "apps",
    "--enable", "skip_host_skill_discovery", "--disable", "skill_search",
    "--disable", "shell_tool", "--disable", "in_app_browser", "--disable", "multi_agent",
    "-c", 'web_search="disabled"',
    "-s", "danger-full-access",
    "-c", `developer_instructions=${JSON.stringify(GAME_INSTRUCTIONS)}`,
    "-c", 'model_reasoning_summary="detailed"',

  ];
  if (args.model) {
    argv.push("-m", args.model);
  }
  if (args.reasoning) {
    argv.push("-c", `model_reasoning_effort="${args.reasoning}"`);
  }
  argv.push(
    "-c",
    `mcp_servers.game={url="${args.mcpUrl}", bearer_token_env_var="${args.bearerEnvVar}", required=true, startup_timeout_sec=20, tool_timeout_sec=30, default_tools_approval_mode="auto", enabled_tools=${JSON.stringify(GAME_TOOL_ALLOWLIST)}}`,
  );
  if (args.resumeId) argv.push("resume", args.resumeId);
  argv.push(args.prompt);
  return { executable: resolveProviderExecutable("codex"), argv, cwd: args.cwd };
}

export function claudeSpawn(args: {
  prompt: string;
  resumeId?: string;
  mcpConfigPath: string;
  cwd: string;
  model?: string;
  reasoning?: string;
}): SpawnSpec {
  // MCP tool names normalize dots to underscores (mcp__game__game_observe).
  const allowedTools = GAME_TOOL_ALLOWLIST.map((t) => `mcp__game__${t.replaceAll(".", "_")}`).join(" ");
  const argv = [
    "-p", "--output-format", "stream-json", "--verbose",
    "--include-partial-messages", "--include-hook-events",
    "--append-system-prompt", GAME_INSTRUCTIONS,
    "--restricted", "--strict-mcp-config",
    "--mcp-config", args.mcpConfigPath,
    "--tools", "",
    "--allowedTools", allowedTools,
    "--permission-mode", "dontAsk",
    "--disable-slash-commands",
  ];
  if (args.model) {
    argv.push("--model", args.model);
  }
  if (args.reasoning) {
    argv.push("--effort", args.reasoning);
  }
  if (args.resumeId) argv.push("--resume", args.resumeId);
  argv.push(args.prompt);
  return { executable: resolveProviderExecutable("claude"), argv, cwd: args.cwd };
}

export function opencodeSpawn(args: {
  prompt: string;
  resumeId?: string;
  dir: string;
  model?: string;
  reasoning?: string;
}): SpawnSpec {
  // Game-owned opencode.json in dir carries MCP + tools + share config.
  const argv = ["run", "--format", "json", "--pure", "--dir", args.dir];
  if (args.model) {
    argv.push("-m", args.model);
  }
  if (args.reasoning) {
    argv.push("--variant", args.reasoning);
  }
  if (args.resumeId) argv.push("--session", args.resumeId);
  argv.push(args.prompt);
  return { executable: resolveProviderExecutable("opencode"), argv, cwd: args.dir };
}
