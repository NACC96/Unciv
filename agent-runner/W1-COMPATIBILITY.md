# W1 Provider Compatibility Report (plan §12 W1, §7.1–7.2)

Real-CLI probes against the fake game MCP service. Each row: PASS means the
installed CLI met the full contract (game tools callable, mutation applied,
exact-ID resume, game-only effective catalog, native auth reuse, sanitized
stream parsing). Fixtures: `agent-runner/test-fixtures/w1-*.jsonl` (secret-free;
probe scripts: `agent-runner/probes/`). Full story per provider in
`docs/agent-build-checkpoints.md`.

| Provider | Version | Isolated config | Bearer MCP | Game-only catalog | Exact resume | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| Codex | 0.154.0 | `--ignore-user-config` + `-c` TOML (`[mcp_servers.game]`, `required=true`, 8-tool `enabled_tools`) | `bearer_token_env_var` (name only in config) | only `game.observe`/`game.act` in fixtures; 0 shell/web | same `thread_id` across resume | **PASS in `danger-full-access`**; boxed mode denied at approval layer (see CP-010) |
| Claude | 2.1.270 | planned: `--mcp-config` + `--strict-mcp-config` + `--tools ""` | TBD | TBD | TBD (`--resume <id>`) | not probed |
| OpenCode | 1.18.31 | planned: game-owned config + `--pure`, `"oauth": false` | `headers.Authorization` + `{env:VAR}` | TBD (`tools` globs) | TBD (`--session <id>`) | not probed |

Open proof-gate items: hostile inherited-config fixtures, missing-MCP startup,
denied shell/file/connector access, session-ID mismatch, stream-parser faults
(split/malformed/truncated JSONL, unknown events, duplicate usage).
