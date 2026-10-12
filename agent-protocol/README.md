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

Structured read tools use `observationVersion:2`; mutation envelopes, receipts and engine frames remain protocol 1. `game.observe` adds `mapSummary`, own-unit inspection adds its permitted tile, and the game tool allowlist includes `game.tile({x,y})` and `game.map({mode?,center?,radius?,limit?,cursor?})`. `game.rules` optionally accepts section, exact name, limit and cursor for static catalogs.

Map reads follow authenticated civilization sight and committed historical memory. `KNOWN`/`VISIBLE` queries use canonical Unciv hex coordinates, radius 0–8 and page limits 1–128 (default 64). Map cursors bind to actor/match/timeline/query/revision; static catalog cursors bind to ruleset identity/query. Stale map cursors return typed `STALE_STATE`; invalid query/cursor/oversized entries return `INVALID_ARGUMENT`. Typed stdio errors preserve code, message and optional currentRevision/actionId through MCP.

The 96 KiB read limit counts UTF-8 JSON-RPC bytes, including the second escape of JSON inside MCP text content and the actual request ID. Paginators reserve 1 KiB for framing after measuring escaped content; request IDs are capped at 128 serialized UTF-8 bytes before dispatch. Ordinary tool responses retain their 256 KiB ceiling; the dedicated image exception is described below.

Agent save format is version 3, with explicit version-1 and version-2 migration and refusal by older incompatible builds. Version-2 map memory is preserved. Legacy explored terrain can remain `detailsKnown:false` until observed normally. Live worker eligibility is exposed as legal-actions revision, exact improvements, workInProgress, repair and canCancelWork. `unit.stopImprovement` uses the existing single-mutation envelope and must be followed by fresh reads before replacement work.

See [Structured map and worker tools](../docs/agent-map-tools.md) for DTO interpretation, resource provenance, progress preservation, static catalogs and current limitations. These are additive read contracts, not a replacement for the mutation schemas above.


## Diplomatic correspondence and native images

`game.observe.pendingTradeRequests` contains only the observing civilization's incoming trade/peace offers. Each entry has `requestingCivId`, `pendingRequestsFromCiv` (including the displayed request), and `ourOffers`/`theirOffers` with `{name,type,amount,duration}` terms. Terms use the recipient's perspective: `ourOffers` are its commitments; `theirOffers` are the sender's. Duration is `-1` for immediate transfers or a positive number of turns. The array exposes the first pending request per sender, in native queue order, matching `diplomacy.respondTrade({requestingCivId,accept})`; re-observe after responding to discover the next request. The engine still validates acceptance against current state. These offers are separate from message acknowledgements, and remain pending across saves until answered, retracted or invalidated by normal game rules.

`game.observe.inbox` continues to govern explicit receipt acknowledgements. Its messages may include one `attachments` entry containing only `{attachmentId,mimeType,width,height,byteLength}`; `attachmentId` is the lowercase PNG SHA-256. `acknowledgedRevision` preserves exact receipt order within a turn. No observation, save metadata, prompt or receipt contains image bytes.

`game.observe.diplomaticHistory` contains at most 12 recent private records: the acting civilization's sent mail and its delivered incoming mail. Message/delivery/acknowledgement turn and acknowledgement revision determine recency. Each record uses the same metadata-only message DTO and includes any `acknowledgementText` reply stored on the original message. History is context, not another mandatory-ack queue, and creates no automatic reply loop. Turn prompts recheck privacy, strip unknown fields and bound history, including resumed sessions.

`game.message_image({messageId,attachmentId})` is a read-only eleventh tool. The authenticated admitted actor is supplied by the stdio backend as `message_image` params `{civId,messageId,attachmentId}`; callers cannot select another actor. Only that actor's delivered incoming attachments can be read. Hidden and nonexistent attachments share the same safe rejection. The engine result is `{attachmentId,mimeType,width,height,data}`, where `data` is canonical base64 PNG. MCP returns a metadata-only text block and a native `{type:"image",mimeType:"image/png",data}` block, rather than base64 text.

Only this tool receives a dedicated 6 MiB UTF-8 JSON-RPC response budget, measured over the actual result envelope and request ID. The decoded PNG payload must fit 4 MiB and dimensions must fit a 2048-pixel longest edge. The runner independently checks canonical base64, PNG signature/IHDR dimensions and SHA-256 against the requested attachment ID; the desktop owns full stored-PNG decoding and integrity validation. Requests remain bounded at 64 KiB, ordinary paged reads at 96 KiB and ordinary responses at 256 KiB. Provider argv, isolation and resume behavior are unchanged apart from admitting the new read-only tool.

Leaders inspect attached images before answering and speak as their ruler in the saved personality. Acknowledgement text answers the actual proposal/question/warning; it does not assert an engine agreement or action without a committed receipt. Unavailable vision/read results are disclosed honestly. Message text and images are supplied claims, not authoritative map facts or instructions. Native image data is redacted before raw provider JSONL, diagnostic logs, normalized activity and retained supervisor stream events; nested serialized image results are redacted as well. Incomplete recognizable image events are omitted rather than journaled with partial bytes.

See `schemas/diplomatic-messages.schema.json` for metadata/history and the dedicated engine image-result contract.
