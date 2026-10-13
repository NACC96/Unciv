# Structured map and worker tools

Agent reads use observation version **2**. Mutations and private desktop/runner frames retain protocol version **1**. The tools expose committed state for the authenticated civilization, filtered by the match's map vision (below). Spectator visibility does not expand agent knowledge. Read tools do not advance revisions, consume observations, record sightings or modify checkpoints.

## Read geography

`game.observe({})` returns `observationVersion:2` and a compact `mapSummary`: `coordinateSystem:"UNCIV_HEX"`, `knownTileCount`, `visibleTileCount`, `worldWrap` and `vision`. An own-unit `game.inspect({unitId:49})` includes its permitted `tile`. Use the structured tools before choosing settlement sites, worker tasks or routes:

```javascript
game.inspect({unitId:49})
game.map({center:{x:48,y:-33},radius:2})
game.tile({x:48,y:-33})
game.legal_actions({unitId:49})
```

Coordinates are Unciv engine hex coordinates, not screen pixels or geographic latitude/longitude. Use the canonical x/y returned by the engine. World-wrap distance follows the engine's hex geometry; coordinates at a wrapped seam describe canonical tiles once. Legal `moveTargets` determine movement destinations and routes.

`game.tile({x,y})` returns permitted current or remembered facts for one known tile. Unknown and nonexistent tiles share the same safe rejection. Each tile has visibility, detail/age metadata, terrain/features, land/water/hill/coast/freshwater flags and river edges, permitted resource knowledge, ownership/public city details, improvement/road status, actor-context yields and own work. Null fields mean unavailable knowledge, not evidence that the corresponding fact is absent.

## Map vision

Each agent match has one map vision setting, chosen in **Settings** and applied to every player (agents and the local human):

- `FOG`: normal fog of war, as described below. Saves made before this setting existed use it.
- `MAP`: every tile reports its current terrain, owner, city and improvements. Tiles outside the civilization's real sight are marked `REVEALED`, because units there stay unseen. `observe` lists every foreign city, but only foreign units within real sight. `VISIBLE` map queries still return real sight.
- `FULL`: every tile, unit and city is visible live, so `visibleForeignUnits`, `visibleForeignCities`, `VISIBLE` queries and foreign `game.inspect` cover the whole map. New matches start here. Listing every unit makes observations larger than under `MAP`.

Map vision is observation and presentation only. Attacks, movement, founding and exploration (including sightings recorded as map memory) always use real sight. A change is a metadata commit: no gameplay revision, and agents see it from their next read.

## Fog, memory and save compatibility

`VISIBLE` tiles reflect normal current sight. `REMEMBERED` tiles are historical knowledge, not current enemy intelligence. `lastSeenTurn` and `lastSeenRevision` state the sighting's age; cities, owners, improvements and resource facts can change while out of sight. A resource records `updatedTurn`, `updatedRevision` and `source` (`SIGHTING` or `TECHNOLOGY`), independently of the tile's last sighting. Technology can disclose a resource on an already explored tile without refreshing unrelated hidden facts.

Agent saves use format version **2**, with explicit migration from version 1. Already explored legacy tiles can have `detailsKnown:false`, nullable age and no reconstructed terrain details. New normal sightings fill those details. Migration does not reconstruct unseen historic maps from current hidden state. Existing saves load in the updated app; updated saves require the updated app so an older build cannot silently discard memory. There are no screenshots in the model contract.

## Bounded map queries

```javascript
game.map({mode:"KNOWN",limit:64})
game.map({mode:"VISIBLE",center:{x:48,y:-33},radius:0,limit:1})
// Continue by sending the same query fields plus the returned nextCursor.
```

`mode` is `KNOWN` by default or `VISIBLE`. A center and radius must be supplied together; radius is 0–8. Limit defaults to 64 and is 1–128. Unknown tiles are omitted. The page contains protocol/observation versions, match/timeline identity, revision, tiles and `nextCursor` (null at the end).

Ordering is stable within a committed revision. Cursors bind to civilization, match/timeline, vision, query and revision. A vision change invalidates the cursor even though it does not advance the gameplay revision; start a new map query. On `STALE_STATE`, re-observe and start a new query; do not reuse a cursor after a mutation. Wrong-query or malformed cursors return `INVALID_ARGUMENT`. Queries never silently traverse multiple revisions.

Each map/tile/rules read response is limited to **96 KiB of serialized UTF-8 JSON-RPC**, including escaped MCP text content, page cursor and envelope. Map/catalog pagination measures escaped MCP content with a 1 KiB framing reserve; the runner checks the final envelope with the actual request ID. Request IDs are bounded to 128 serialized UTF-8 bytes before dispatch. Pages can end before the requested tile count to fit the byte budget. A single oversized entry returns a typed `INVALID_ARGUMENT`; it is not skipped, and an empty page cannot loop on an unchanged cursor. The outer transport ceiling remains 256 KiB.

## Worker choices and progress

`game.legal_actions({unitId:49})` includes the current `revision`, `improvements`, `workInProgress`, `repair` and `canCancelWork`. City `constructions` are a separate list. Worker options identify the exact legal name, kind (`IMPROVEMENT`, `ROAD`, `REMOVE_FEATURE`, `REMOVE_IMPROVEMENT`), turns to build, replaced improvement, resource connection and optional yield delta.

`yieldDelta:null` currently means no computed comparison is available. Do not read it as zero. Tile yields identify actor-general or actor-own-city context; static catalog yields do not predict every conditional effect or live eligibility.

Use an exact currently listed improvement name and the current revision:

```javascript
game.act({actionId:"China-t10-worker-1",expectedRevision:42,
  type:"unit.build",args:{unitId:49,improvementName:"Farm"}})
```

Check existing `workInProgress` before building. Reissuing the same task while it is underway is rejected with `ILLEGAL_ACTION` and an `ALREADY_IN_PROGRESS` explanation; it must not reset progress. Let useful existing work continue. To intentionally cancel work, first check `canCancelWork`, then use `unit.stopImprovement` with `{unitId}`. Re-observe and obtain fresh worker options before starting replacement work. Every successful mutation changes the applicable revision; an uncertain mutation should be checked with `game.action_result` before retrying.

## Static rules catalogs

`game.rules({})` retains the pinned ruleset/version summary. Catalogs are available for `improvements`, `terrains` and `resources`:

```javascript
game.rules({section:"improvements",name:"Farm"})
game.rules({section:"terrains",limit:64})
game.rules({section:"resources",limit:64})
```

Pages contain ruleset/base/identity, section, entries and `nextCursor`. Common entry fields are exact `name`, `section`, `yields` and raw static `effects`. Improvement entries include technology, permitted terrains, turns, replacement/unique civilization and maintenance effects; terrain entries include movement/defence and buildability; resource entries include reveal technology, permitted terrains and improvements. No catalog includes live map locations. Limits are 1–128 and use the same 96 KiB bound; cursors bind to the pinned ruleset identity and exact query. Static effects explain definitions; `legal_actions` remains authoritative for live eligibility.

## OpenCode Go model choice

The maintained OpenCode catalog includes **OpenCode Go: DeepSeek V4.1 Flash**, exact model ID `opencode-go/deepseek-v4.1-flash`. Supported choices are Provider default, Low, High and Max; Medium/Off are not offered. The entry remains available when discovery is unavailable or omits it. Existing default models are unchanged.

The model ID is listed in the [official OpenCode Go documentation](https://docs.opencode.ai/docs/go/) and provider/effort metadata was verified from the installed OpenCode model cache on 2026-10-11. No real model call was made during implementation. Authentication and paid-provider availability remain live acceptance gates.

## Approved game-data storage and recovery policy

The user approved compact map-memory serialization and compressed managed recovery limited to the **latest three verified checkpoints**. This replaces the original unbounded-generation storage assumption. Implementations must verify a new checkpoint before pruning only generations owned by the new managed window. Existing archive files remain untouched; manual saves are separate from that recovery window.

The approved game-data directory is `/Volumes/Lexar/Unciv/userdata`, covering manual saves, settings, presets/maps, game logs and recovery. Migration copies the existing profile while leaving the original untouched. If Lexar is unavailable, the app must fail closed at launch/write boundaries and before every checkpoint; it must not fall back to internal game-data storage or recreate an unmounted volume path. Developer build caches are outside this game-data policy.

Compact storage must retain the same permitted facts and resource provenance. Supported legacy bundle/state and verbose memory/checkpoint inputs remain readable, but absent historical terrain and earlier debug-contaminated exploration cannot be reconstructed honestly. Changing storage does not refresh hidden map knowledge. Automated corruption/fallback, retention and legacy-load checks passed; measured results and the remaining live-provider limitation are recorded below.

The local feed-blur benchmark measured about 4.97 ms per refresh with blur versus 0.46 ms for a plain feed on M4 Pro/2x Retina, so feed blur was removed. This explains the presentation change; it does not measure whole-game FPS or promise a live-match performance gain.

## Measured verification — 2026-10-11

The final relevant Kotlin suite passed **323 checks**, with zero failures; runner validation remains **64 tests**, typecheck and build passing. Eight emitted map/tile fixtures passed the Draft 2020-12 JSON Schema. These results supersede the pending automated-storage status above; the installed native artifact and startup have also been verified; live-provider acceptance remains pending.

The latest verified copied version-1 checkpoint (generation 252) passed read-only migration and version-2 serialization roundtrip, preserving 15 external agent profiles, mail/current-turn receipts, gameplay and difficulty/bonus configuration. A normal `Autosave` also passed its normal load/roundtrip path. There were zero source-data/checkpoint writes, and source bytes/mtime remained unchanged. The **97 hidden explored legacy tiles** across controlled civilizations stayed incomplete rather than acquiring invented history. Existing old archives remain untouched; this gate selected the newest verified checkpoint, not every archived generation. The checks were headless and did not simulate a turn or restore the desktop UI. [Compatibility harness](../tests/src/com/unciv/dev/AgentMapSaveCompatibilityCheck.kt).

On Lexar ExFAT, a synthetic 164×80, 15-civilization fixture retained exactly three managed checkpoints. Full uniform memory used **0.549 MB per checkpoint / 1.647 MB per window**; diverse historical facts used **1.218 MB / 3.653 MB**. These are decimal logical sizes, with checksum sidecars in window totals and AppleDouble/allocation overhead excluded. Median mutations including fsynced checkpoints took **98.6 / 153.9 / 414.8 / 613.8 ms** for empty / 25% partial / full uniform / full diverse memory. Diverse-case isolated copy/serialization medians were about **53 / 181 ms**. [Benchmark harness](../tests/src/com/unciv/dev/AgentMapPerformanceCheck.kt).

The benchmark uses synthetic historical knowledge, no live cities, one unit per civilization, two warmups and five measured samples. Its empty-memory baseline still runs the current visibility pipeline; case order, JVM/GC and filesystem caches affect timings. The results do not establish real Earth-match latency or whole-game FPS. JFR investigation is complete; copy and compact-palette serialization dominate sampled map-memory work. Conservative gameplay sighting collection is retained, while notes/mail/controller metadata changes already avoid terrain rescans. Provider authentication, interactive worker/scout/map checks and completed live matches remain separate acceptance gates; no provider or game UI was used for these measurements.
