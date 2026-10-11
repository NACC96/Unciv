# Agent map understanding design

Status: implementation and automated verification complete; native update installed (2026-10-11). User-authorized compact map serialization, latest-three verified managed recovery and Lexar game-data storage are implemented. Live provider/UI acceptance remains pending; no active provider was interrupted.

## Purpose and evidence

Enable each leader to make terrain-dependent exploration, settlement and worker decisions from its civilization's permitted information. Preserve the existing game engine, UI, provider identities, personalities, bonuses, messages and turn lifecycle.

The current interface exposes unit/city coordinates and adjacent legal movement targets, but no tile terrain, resources, yields or explored geography. Unit inspection returns basic unit fields. Worker legal actions have no improvement options, and the rules tool ignores its section parameter. China/GPT-6.1 Sol's recorded terrain uncertainty was therefore justified.

A related issue belongs in this fix: `executeBuildImprovement` checks `MapUnit.canBuildImprovement`, which checks builder capability, but omits the tile restrictions used by the improvement picker. An accepted build receipt alone does not establish compliance with those tile restrictions.

## Proposed approach and alternatives

Use structured, bounded map queries and a shared terrain exporter, plus explicit worker options backed by the same validation as commands. This supplies exact names and numbers and works with every existing provider.

Alternatives considered:

- A screenshot-first interface would make broad geography intuitive, but still needs exact coordinates, visibility filtering and legal worker options. Rendering, image transport and image token costs are additional work. Defer it.
- Sending the entire explored map on every observation is simple but unnecessarily repeats large Earth-map payloads. Use compact summaries and pagination instead.
- Using global `TileHistory` would avoid new memory storage but is incorrect: it records replay ownership events, not civilization sightings or terrain snapshots. Use civilization-scoped snapshots.

## Load-bearing assumptions and review decisions

- Agents retain normal civilization visibility, even when the spectator can see the whole map. This follows the existing distinction between spectator inspection and agent control.
- Known terrain survives save/reload as last-seen knowledge; it never silently becomes current knowledge under fog.
- The user selected protected map memory: use agent bundle/state version 2 with an explicit version-1 migration. Existing saves remain loadable by the updated app; updated version-2 saves require the updated app. Older builds must reject them rather than silently discard memory.
- Existing matches may gain the tools after a future safe app update, but historical terrain snapshots absent from old saves cannot be reconstructed honestly.
- This is an information/worker-correctness feature, not a promise of stronger play or a request to automate strategy.

## Visibility and memory contract

Define an explicit allowlisted tile DTO rather than exposing a raw Tile, TileView or save object.

Permitted visible fields: canonical x/y; base terrain; terrain features/natural wonder; land/water/hill/coast/freshwater indicators; river edges; visible resource name/amount; known owner and public city presence; improvement/road/pillage state; and context-labeled tile yields. Construction progress is restricted to the actor's work, not an opponent's private queue. Resource identity, quantity and derived values require the actor's resource-reveal permission.

Use these knowledge states:

- `VISIBLE`: current facts available to the actor.
- `REMEMBERED`: a stored sanitized snapshot with `lastSeenTurn` and `lastSeenRevision`; no current unseen facts are substituted.
- Explored coordinates without a historical snapshot: `REMEMBERED`, `detailsKnown=false`, null snapshot fields and null last-seen timestamp. This honestly handles old saves and permitted discoveries that lack complete sightings.

Unknown and nonexistent tile requests have the same rejection. Map lists omit unknown tiles. Do not retain enemy units as current map occupants; their live visibility remains in the existing observation lists.

Sightings are collected during unpublished candidate rule execution and committed with the game and agent metadata. This captures geography briefly seen during multi-step movement and observations available to inactive nations during opponents' actions. A transient collector is absent from ordinary games, restored/publication snapshots and read-only tool calls. Attach it after candidate transient restoration, then flush final permitted views for all affected controller civilizations, including stationary inactive observers whose visible tiles changed. Failed actions/checkpoint writes discard sightings.

Never grant agent knowledge through a spectator/debug UI reveal. Factor normal visibility and genuine explored membership into an explicit agent knowledge policy. Install this policy before restoring candidate transients: the existing debug override can otherwise mark the entire map explored during restoration, before a collector is attached. Exporters and legacy fallback must not call debug-sensitive `Tile.isExplored` unguarded. Existing legacy explored flags contaminated by an earlier debug reveal cannot be distinguished from legitimate exploration without historical provenance; document that limit rather than claim to reconstruct it.

Legitimate technology/map-reveal effects get narrowly scoped updates: a technology revealing a resource on explored land may update that resource field without refreshing unseen ownership, terrain or improvements. Keep the original whole-tile `lastSeenTurn`/`lastSeenRevision`; record resource-specific update turn, revision and source so an old border does not appear newly observed.

Store only each nation's latest tile snapshot, not an unbounded historical timeline. Memory keys are civilization ID plus canonical coordinates. Copies and checkpoint generations must not share mutable nested state.

Tile yields must identify their context: actual own-city tile yields or a civilization-level estimate without foreign-city private modifiers. Remembered yields stay marked as last seen. Improvement yield comparisons use the same context and exclude empire income, road maintenance and trade-connection benefits.

## Read API contract

Keep existing tools/command envelopes compatible. Add two read-only tools:

- `game.tile({x,y})`: permitted current or remembered tile detail.
- `game.map({mode?,center?,radius?,limit?,cursor?})`: pages of visible or known tile DTOs. `mode` is `KNOWN` by default or `VISIBLE`. Center/radius are supplied together; without them it pages the known map. A radius query requires a known center. Radius is 0–8; limit defaults to 64 and is 1–128. Results are sorted by canonical x then y and deduplicated across wrap.

Map responses contain protocol version, match/timeline identity, current revision, tiles and a nullable next cursor. A cursor is opaque, validated against the authenticated actor, match, timeline, revision and normalized query; it never grants authority. Changed revisions return `STALE_STATE`, prompting a fresh query. Changed/malformed query bindings return `INVALID_ARGUMENT`.

Each map/rules response has a 96 KiB UTF-8 byte budget, including envelope/cursor overhead, below the existing 256 KiB transport ceiling. End a page early when needed; return its cursor without silently dropping entries. If one entry cannot fit on an otherwise empty page, return a bounded typed error identifying the oversized entry rather than an empty page with a non-progressing cursor. Default observations add a compact `mapSummary` with known/visible counts, engine hex-coordinate explanation and available map tools, not the whole map. Own-unit inspection adds a `tile` field from the shared exporter.

Coordinates are Unciv's engine hex coordinates, not screen pixels or geographic latitude/longitude. Use engine neighbor/distance/wrap functions and return canonical coordinates. Do not infer unseen land, map resources or foreign starts from the installed Earth map.

## Worker and rules contract

Add to `game.legal_actions({unitId})`: revision, `improvements`, `workInProgress`, `repair` and `canCancelWork`. Preserve existing move/attack/order fields and city `constructions`.

Each improvement option contains an exact command name, kind (`IMPROVEMENT`, `ROAD`, `REMOVE_FEATURE`, `REMOVE_IMPROVEMENT`), turns to build, visible resource connection, replacement warning and safely computed tile-yield delta when available. Non-workers receive an empty list. Exclude the already active improvement from legal-now options and expose it only in `workInProgress`, with its existing remaining turns.

One shared core helper determines build legality for both option enumeration and candidate mutation validation. It combines builder eligibility with researched technology, terrain/features, ownership, resource requirements, city-center/one-time restrictions and the UI's action eligibility. Great-person instant improvements stay on their existing path. A farm that requires a separate clearing action is not listed as legal now.

Continuing the current improvement must preserve progress and resource charges. A duplicate action ID keeps its existing receipt; a fresh command requesting the already active improvement returns `ILLEGAL_ACTION` with an `ALREADY_IN_PROGRESS` explanation and leaves work untouched. Switching work is explicit and reports replacement implications. Successful work assignment wakes the worker consistently with the human picker.

Expose `unit.stopImprovement` through the existing StopImprovementCommand, with `canCancelWork`; reuse `unit.repair` for repairs. Share cancellation eligibility between discovery and execution, requiring a capable builder, authorized work and a cancellable queue rather than a one-time improvement marker. Share repair eligibility too, including the builder's improvement filter and outside-border restrictions currently checked by the human repair action. An applied cancellation must actually change cancellable work; reject successful no-ops. Never pass the UI's cancel label as a build name.

Preserve normal non-agent picker behavior. Routed human clear-then-build queues require an explicit internal atomic queue intent. Validate the whole sequence on a disposable projected tile/context before starting and queuing work through engine APIs. An invalid later entry leaves the earlier entry uncommitted. Projection must not spend resources, award chopping yields, trigger notifications or record sightings. Validation must bind improvement conditionals to the projected tile, not the unit's original tile. Keep this queue support internal; agents can clear and reconsider on a later turn.

Privacy tests distinguish raw hidden geography from mechanics legitimately available in the human UI. Builder eligibility may reveal a lawful action or publicly derived freshwater/adjacency property. Preserve that human-equivalent information without exporting hidden resource identities, private city effects or raw unseen neighbor tiles; audit yield comparisons against this boundary.

Make `game.rules` actually honor `section`, with exact-name lookup and bounded catalog pages for `improvements`, `terrains` and `resources`. Default calls retain the ruleset/version summary. Static entries describe technology, terrain restrictions, effects, resource connections and maintenance; live legal actions decide what is available here now.

Update instructions to require terrain inspection before location-dependent choices, exact listed worker names, distinction between city constructions and worker improvements, preservation of current work, and skepticism about remembered facts. Tools and prompts carry structured information only; no screenshot capability is implied.

## Compatibility and performance

All Kotlin/TypeScript schemas and provider allowlists change together. Existing mutation protocol 1 remains supported; expose `observationVersion: 2` so the provider can distinguish the new read contract. Save migration updates both bundle and state version checks, checkpoint recovery and deep-copy tests. Recovery verifies a matching version-1 bundle/state pair, migrates it, then applies strict current-version validation; it must not discard genuine old checkpoints first.

Do not put map memory updates or JSON assembly on the GL thread. Metadata-only commits must not rescan terrain. Queries must be bounded and pure. Measure memory serialization/checkpoint cost separately from provider token cost on an Earth-sized fixture (164×80, 13,120 tiles), including 15 nations and complete discovered maps. The storage gate was triggered by a measured roughly 147 MB Earth checkpoint (about 1.65 s), implying roughly 1.47 TB for 10,000 immutable generations. The user approved compact dictionary map-memory serialization and compressed managed recovery limited to the latest three verified checkpoints. Verify new writes before pruning only generations owned by that managed window; preserve all old archive files. Report compact/compressed sizes, three-checkpoint storage and recovery/latency results before release. Manual saves are separate from the recovery window. Legacy verbose memory/checkpoint inputs must remain readable where their bundle/state versions are supported; do not reconstruct missing historical terrain.

All game data (manual saves, settings, presets/maps, logs and recovery) must live under `/Volumes/Lexar/Unciv/userdata`. Migration copies the existing profile and leaves the original untouched. The app must fail closed when Lexar is unavailable, including before each checkpoint; it must not recreate an unmounted volume path or fall back to internal game-data storage. Developer JDK/build caches are separate from this game-data policy.

The local feed-blur benchmark measured refresh medians of about 4.97 ms with blur and 0.46 ms for the plain feed on M4 Pro/2x Retina, so feed blur was removed. This is a measured UI refresh cost, not a whole-game FPS result.

Existing presets, bonuses, agents and map files are preserved. Never patch or reload the currently running match while implementing later. Installation and resumed-provider tool refresh happen only after a safe boundary; active providers finish normally.

## Acceptance

1. A worker can inspect its terrain/resource/improvement, list legal choices and build a listed option without guessing.
2. Guessed terrain/technology/ownership-invalid builds are rejected without changing game, progress, resource charges or memory.
3. Scouts can query nearby known geography and retain sightings after moving away and after save/reload.
4. Raw unknown tiles/resources and unseen changes cannot leak through yields, option lists, pagination, debug settings or spectator inspection. Human-equivalent legal mechanics remain available and are tested separately from raw hidden geography.
5. Earth seam queries return canonical, unique tiles with bounded payloads and consistent pagination.
6. Repeated reads do not change game revisions, checkpoint bytes or memory; failed mutations do not disclose candidate sightings.
7. A scripted regression reproducing China's two-worker scenario uses tile data and listed improvements. Build time/progress matches the engine; continuing work does not restart it.
8. An older real-save copy migrates without losing roster, personality, bonuses, messages, receipts or current work. Unknown historical details remain explicitly incomplete.

Application implementation is now authorized and in progress. Root records final verified steps and release readiness; this document does not claim completed live acceptance or authorize disrupting an active provider.
