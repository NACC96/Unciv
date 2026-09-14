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
| Claude | 2.1.270 CLI (stream reports 2.1.271) | `--restricted` + `--strict-mcp-config` + `--tools ""`, MCP allowlist `--allowedTools mcp__game__*` | temp `--mcp-config` file (0600, gitignored, deleted after run); fixtures leak-free | init event lists exactly the 8 `mcp__game__*` tools; 0 builtin/shell | same `session_id` across `--resume`; resume replied live revision `43` | **PASS** (see CP-011) |
| OpenCode | 1.18.31 | game-owned `opencode.json` in scratch dir + `--pure`; `oauth:false`, `tools: {"*":false,"game_*":true}`, `share:"disabled"` | `headers.Authorization: Bearer {env:GAME_MCP_BEARER}` | only `game_game_observe`/`game_game_act` in stream; no non-game tool refs | same `ses_*` across `--session`; prompt DONE | **PASS** (see CP-012) |

Open proof-gate items: hostile inherited-config fixtures, missing-MCP startup,
denied shell/file/connector access, session-ID mismatch, stream-parser faults
(split/malformed/truncated JSONL, unknown events, duplicate usage).

W1 exit also requires: schema↔code generation tests (Kotlin/TS from
agent-protocol/), and the deterministic-CI scripted-provider path.
