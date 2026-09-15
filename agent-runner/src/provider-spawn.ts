/**
 * Provider invocation builders (AI_AGENT_PLAN.md §7.2).
 *
 * Absolute executable paths, explicit environments, argument arrays — no
 * shell, no interpolation of names/models/personalities into commands, no
 * interactive profile sourcing. Values here are the W1-proven configurations
 * from agent-runner/probes/* (see W1-COMPATIBILITY.md + CP-010–CP-012).
 */
export const GAME_TOOL_ALLOWLIST = [
  "game.observe",
  "game.inspect",
  "game.rules",
  "game.legal_actions",
  "game.act",
  "game.action_result",
  "game.notes",
  "game.end_turn",
];

export interface SpawnSpec {
  executable: string;
  argv: string[];
  cwd: string;
}

export const CODEX_EXECUTABLE = "/Users/noahkenny/.nvm/versions/node/v24.13.1/bin/codex";
export const CLAUDE_EXECUTABLE = "/Users/noahkenny/.local/bin/claude";
export const OPENCODE_EXECUTABLE = "/Users/noahkenny/.opencode/bin/opencode";

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
    "-s", "danger-full-access",
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
  return { executable: CODEX_EXECUTABLE, argv, cwd: args.cwd };
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
  return { executable: CLAUDE_EXECUTABLE, argv, cwd: args.cwd };
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
  return { executable: OPENCODE_EXECUTABLE, argv, cwd: args.dir };
}
