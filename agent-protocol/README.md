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

## Additive observation version 2

Structured read tools use `observationVersion:2`; mutation envelopes, receipts and engine frames remain protocol 1. `game.observe` adds `mapSummary`, own-unit inspection adds its permitted tile, and the ten-tool allowlist includes `game.tile({x,y})` and `game.map({mode?,center?,radius?,limit?,cursor?})`. `game.rules` optionally accepts section, exact name, limit and cursor for static catalogs.

Map reads follow authenticated civilization sight and committed historical memory. `KNOWN`/`VISIBLE` queries use canonical Unciv hex coordinates, radius 0–8 and page limits 1–128 (default 64). Map cursors bind to actor/match/timeline/query/revision; static catalog cursors bind to ruleset identity/query. Stale map cursors return typed `STALE_STATE`; invalid query/cursor/oversized entries return `INVALID_ARGUMENT`. Typed stdio errors preserve code, message and optional currentRevision/actionId through MCP.

The 96 KiB read limit counts UTF-8 JSON-RPC bytes, including the second escape of JSON inside MCP text content and the actual request ID. Paginators reserve 1 KiB for framing after measuring escaped content; request IDs are capped at 128 serialized UTF-8 bytes before dispatch. The broader transport ceiling is 256 KiB.

Agent save format is version 2, with explicit version-1 migration and refusal by older incompatible builds. Legacy explored terrain can remain `detailsKnown:false` until observed normally. Live worker eligibility is exposed as legal-actions revision, exact improvements, workInProgress, repair and canCancelWork. `unit.stopImprovement` uses the existing single-mutation envelope and must be followed by fresh reads before replacement work.

See [Structured map and worker tools](../docs/agent-map-tools.md) for DTO interpretation, resource provenance, progress preservation, static catalogs and current limitations. These are additive read contracts, not a replacement for the mutation schemas above.
