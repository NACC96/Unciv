# Agent Protocol — shared JSON schemas

**Protocol version:** `1` (see `protocol-version.json`).
**Contract source:** `AI_AGENT_PLAN.md` §5.

- `schemas/mutation-envelope.schema.json` — the single-mutation request envelope for `game.act` (plan §5.1). No batching in v1.
- `schemas/action-receipt.schema.json` — success receipt returned by `game.act` / `game.action_result` (plan §5.1).
- `schemas/error-envelope.schema.json` — structured rejection/error body; exhaustive v1 `code` enum.
- `schemas/supervisor-events.schema.json` — normalized provider-adapter events (§7.1) and authoritative engine events. Provider streams and engine commits stay in separate namespaces.

Compatibility rule (plan §5.1): reject incompatible `protocolVersion` / save-format
versions explicitly. Unknown **provider event** fields may be tolerated; unknown
**gameplay command types** must be rejected, never dispatched (no reflection,
no arbitrary function/shell/path/save-edit payloads).

Kotlin and TypeScript representations must be generated or validated from these
schemas in tests (plan §13.1); no hand-diverged copies.
