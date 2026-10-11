# Agent Map Understanding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give civilization agents reliable, fog-safe terrain knowledge and discoverable worker options so they can explore, settle and improve land without guessing.

**Architecture:** Export explicit tile DTOs from committed state and maintain civilization-scoped last-seen snapshots through the existing candidate/checkpoint transaction. Add bounded map/tile queries, enrich unit inspection, and share worker legality between discovery and commands. Extend the existing desktop/TypeScript/MCP path and provider instructions together.

**Tech Stack:** Kotlin/JVM 21, existing Unciv engine and Gdx JSON, TypeScript, MCP SDK, Zod, JUnit and Vitest; no new dependencies.

**Spec:** [Agent map understanding design](../specs/2026-10-11-agent-map-understanding-design.md).

## Global Constraints

- Status: implementation and automated verification complete; native update installed (2026-10-11). Live provider/UI acceptance is pending. The original step checklist is retained without backfilling unperformed red-phase or manual checks.
- Preserve the running match. Local implementation and automated builds/tests are authorized; installation, restart/resume and live provider checks require the agreed safe boundary.
- Agents retain normal civilization visibility, even when the spectator can see the whole map.
- Use only committed state in read tools; read operations do not mutate game, memory, revisions or checkpoints.
- Failed actions/checkpoint writes discard sightings.
- Radius is 0–8; limit defaults to 64 and is 1–128.
- Each map/rules response has a 96 KiB UTF-8 byte budget, below the existing 256 KiB transport ceiling.
- Coordinates are Unciv's engine hex coordinates, not screen pixels or geographic latitude/longitude.
- Preserve existing tools and mutation protocol 1; expose `observationVersion: 2` for the additive read contract.
- User-approved save policy: version 2 with explicit version-1 migration. Existing saves load in the updated app; updated saves require the updated app so older builds cannot silently discard memory. Compact map memory and compressed managed recovery retain only the latest three verified checkpoints; old archives remain untouched.
- Game data (manual saves, settings, presets/maps, logs and recovery) must use `/Volumes/Lexar/Unciv/userdata`. Copy migration leaves the original profile untouched. Fail closed when Lexar is unavailable, including before every checkpoint; never fall back to internal game-data storage.
- Never interrupt active programmatic providers merely because they are slow or quiet. Future live checks wait for a natural safe boundary.
- Stage/commit only files belonging to this work if commits are requested; preserve unrelated existing local changes.

## Review Focus

1. Raw hidden resources/foreign-city effects must not leak through tile yields or legal options. Distinguish these from legal mechanics legitimately exposed by the human UI; Task 1 and Task 4 own paired-world tests and that boundary audit.
2. Sightings during multi-step movement and opponents' turns must survive only successful commits; Task 2 owns these tests.
3. Reopening legacy saves must not invent historical terrain or silently discard current work; Task 2 and Task 6 own migration tests.
4. Wrapped Earth-map pages must not duplicate coordinates or mix knowledge across actors/revisions; Task 3 owns cursor/seam tests.
5. Existing work and routed human clear-then-build queues must not reset progress or replace the first queued action; Task 4 owns progress/queue tests.

---

## File responsibilities

New production files proposed:

- `core/src/com/unciv/logic/agents/AgentMapObservation.kt`: tile/query/page DTOs, allowlisted exporter and bounded map query.
- `core/src/com/unciv/logic/agents/AgentMapKnowledge.kt`: serialized civ-scoped memory, deep copy, transient candidate sighting collector and migration helpers.
- `core/src/com/unciv/logic/agents/AgentWorkerOptions.kt`: worker option DTOs and shared live eligibility.
- `core/src/com/unciv/logic/agents/AgentRulesCatalog.kt`: bounded static catalog export.
- `agent-runner/src/map-types.ts`: TypeScript map/worker/rules read-contract types.
- `agent-protocol/schemas/map-observation.schema.json`: authoritative read DTO/query contract; existing schemas continue governing mutations.

Existing integration files: `AgentObservation.kt`, `AgentMatchState.kt`, `MatchCoordinator.kt`, `AgentEngineBridge.kt`, `FileCheckpointStore.kt`, `AgentCommand.kt`, `GameInfo.kt`, `CivInfoTransientCache.kt`, `ImprovementPickerScreen.kt`, `DesktopAgentMatch.kt`, and the runner's service, stdio, MCP, provider allowlist, instruction and tool-summary modules. Keep changes narrow; do not broadly refactor the renderer or game engine.

### Task 1: Define permitted tile information and the read contract

**Files:** Create `AgentMapObservation.kt`, `agent-runner/src/map-types.ts`, `agent-protocol/schemas/map-observation.schema.json`; extend `ObservationVisibilityTest.kt`; create `AgentMapObservationTest.kt`.

**Interfaces:**

- `HexPosition` in TypeScript: `{x: number, y: number}`; Kotlin uses the existing immutable `HexCoord`.
- `MapQueryMode`: `KNOWN` or `VISIBLE` in both languages.
- `ObservedTile`: x/y, visibility (`VISIBLE`/`REMEMBERED`), detailsKnown, nullable lastSeenTurn/lastSeenRevision, nullable terrain/features/river/resource/owner/public-city/improvement/road fields, context-labeled yields and own-work progress. Resource knowledge has its own updatedTurn/updatedRevision/source (`SIGHTING` or `TECHNOLOGY`) when a partial reveal is newer than the whole-tile sighting. Use exact engine names, not translated identifiers.
- `MapQuery(mode: MapQueryMode = KNOWN, center: HexCoord? = null, radius: Int? = null, limit: Int = 64, cursor: String? = null)`.
- `MapPage`: protocolVersion, observationVersion, matchId, timelineId, revision, tiles, nextCursor.
- `exportVisibleTile(game: GameInfo, civ: Civilization, tile: Tile, revision: Int): ObservedTile`: internal exporter; caller must establish normal permitted visibility.
- `MapSummary`: observationVersion, coordinateSystem (`UNCIV_HEX`), knownTileCount, visibleTileCount and worldWrap from public map settings. Counts contain only the actor's permitted knowledge.

- [ ] Write failing tests named `visible_tile_has_actor_permitted_terrain`, `unrevealed_resource_worlds_have_identical_exports`, `foreign_city_modifiers_are_not_exported`, and `spectator_and_debug_views_do_not_expand_agent_knowledge`. Assert resource name/amount and derived yields remain equal in otherwise identical worlds differing only in an unrevealed deposit.
- [ ] Run the new exporter tests and verify they fail because the DTO/exporter does not exist.
- [ ] Define and validate the JSON schema and mirrored Kotlin/TypeScript types. Implement only current-visible export in this task, with unit and resource permission checks; do not consult global replay history or wrap raw TileView objects.
- [ ] Run exporter/visibility tests and schema fixture validation; expected PASS. Verify exported values contain no internal class tags, coordinates retain integer types, and ordinary foreign-unit observations remain unchanged.

### Task 2: Capture and persist civilization map memory transactionally

**Files:** Create `AgentMapKnowledge.kt`; modify `AgentMatchState.kt`, `MatchCoordinator.kt`, `FileCheckpointStore.kt`, `GameInfo.kt`, `CivInfoTransientCache.kt`; create `AgentMapKnowledgeTest.kt`; extend `AgentMatchStateTest.kt`, `MatchCoordinatorTest.kt`, `FileCheckpointStoreTest.kt`, `ActionCompletionAndRecoveryTest.kt`.

**Interfaces:**

- `AgentMapKnowledge` stores civ ID -> canonical coordinate key -> latest sanitized `ObservedTile` snapshot; fully deep-copied by `AgentMatchState.deepCopy()`.
- `buildPermittedTile(game: GameInfo, match: AgentMatchState, actorCivId: String, position: HexCoord): ObservedTile?` consumes Task 1's exporter/DTO, returning current detail, a stored remembered snapshot, an explicitly incomplete explored entry, or null for unknown/nonexistent.
- `AgentMapSightings.recordVisible(civ: Civilization)` and `flushInto(match: AgentMatchState, revision: Int)` collect only normal permitted sightings from the unpublished candidate.
- Add `@Transient var agentMapSightings: AgentMapSightings? = null` to GameInfo. Capture is absent on ordinary games and publication/restoration snapshots.
- A normal agent visibility/exploration policy is active before candidate transient restoration, independently of the post-restoration sighting collector. Its explored accessor ignores spectator/debug view overrides.
- `migrateAgentBundle(bundle: AgentBundle): AgentBundle` verifies matching bundle/state versions 1/2, converts 1 to 2, and rejects mismatched/future versions. Recovery migrates verified old generations before strict current-version validation. Task 6 validates a real save copy.

- [ ] Write failing tests for visible -> remembered transition; unseen feature/owner/improvement changes remaining stale; nested deep-copy isolation; save/reload persistence; and old explored coordinates retaining `detailsKnown=false` rather than reading current unseen terrain.
- [ ] Add transactional tests `multi_step_move_retains_intermediate_sightings`, `inactive_civ_remembers_permitted_opponent_turn_sighting`, `stationary_inactive_observer_records_visible_tile_change`, `rejected_action_discards_sightings`, `checkpoint_failure_discards_sightings`, and `observe_and_publication_do_not_write_memory`.
- [ ] Add `debug_reveal_during_restoration_does_not_mark_agent_world_explored` and `legacy_known_counts_ignore_debug_view_override`; exercise restoration with debug reveal already enabled. Document that older debug-contaminated explored flags have no recoverable historical provenance.
- [ ] Run those focused tests and verify failure before implementation.
- [ ] Install normal agent visibility/exploration policy before candidate transient restoration, then attach the collector afterward. Record normal visibility updates during engine actions and flush final permitted views for all affected controller civs before successful candidate serialization, including stationary inactive observers. Keep game/memory publication atomic. Capture the initial fresh-match view before the first provider observation; load/migration initialization is separate from pure read calls.
- [ ] Add narrowly scoped resource-reveal updates and geometry-only legacy/discovery entries. Never refresh all unseen tile fields because research or a map trade revealed one fact. Test `resource_reveal_preserves_unseen_old_border_and_sighting_timestamp`, including serialized field provenance, and `map_discovery_does_not_claim_complete_sighting`.
- [ ] Implement version-2 save compatibility in all bundle/state/recovery entry points. Migrate existing version-1 metadata without changing match identity, game revision, receipts, profiles, bonuses, mail or work. Test recovery of a genuine v1 checkpoint before strict version validation, rejection of mismatched/future versions, and old-build rejection of v2. Unknown historical detail stays incomplete.
- [ ] Run knowledge, state, checkpoint and recovery tests; expected PASS. Repeated serialization of a read-only coordinator remains byte-stable; metadata-only commits do not rescan terrain.

### Task 3: Add bounded map queries and enriched inspection end to end

**Files:** Modify `AgentMapObservation.kt`, `AgentObservation.kt`, `MatchCoordinator.kt`, `AgentEngineBridge.kt`, `desktop/src/com/unciv/app/desktop/agents/DesktopAgentMatch.kt`; modify `agent-runner/src/fake-game-mcp.ts`, `stdio-engine.ts`, `engine-protocol.ts`, `game-mcp-server.ts`, `provider-spawn.ts`; extend `AgentEngineBridgeTest.kt`, `AgentMapObservationTest.kt`, runner `game-mcp-server.test.ts`, `engine-protocol.test.ts`, `lifecycle.test.ts`.

**Interfaces:**

- Coordinator: `inspectTile(actorCivId: String, position: HexCoord): ObservedTile?` and `map(actorCivId: String, query: MapQuery): MapPage`.
- Engine methods: `inspect_tile` and `map`; desktop injects/validates the authenticated civ, never a model-supplied actor.
- Service: `inspectTile(position: HexPosition): Promise<ObservedTile | FakeError | null>` and `map(query: MapQuery): Promise<MapPage | FakeError>`.
- MCP tools: `game.tile({x,y})`, `game.map({mode?,center?,radius?,limit?,cursor?})`; provider allowlist grows from 8 to 10 tools with matching normalized names for each provider.
- Default observe adds `mapSummary` and observationVersion; own-unit inspect returns its permitted `tile`. Existing inspect(unitId), moveTargets and city constructions keep their existing semantics.

- [ ] Write failing bridge/transport tests proving same DTO at Kotlin -> stdio -> MCP boundaries, both tools are discoverable, actor identity cannot be overridden, and existing 8 tools still work. Update tests that intentionally assert an exact tool allowlist.
- [ ] Add map-query tests: known/visible mode, radius 0/8, limit 1/128, invalid center/radius combination, stable ordering, full traversal without omissions, 96 KiB byte-bound early pagination including envelope/cursor overhead, and unknown/nonexistent tile equivalence. A single oversized Unicode entry returns a bounded typed error; no empty page may repeat the same cursor indefinitely or silently skip an entry.
- [ ] Add Earth seam and cursor tests: canonical coordinates once each, stale revision -> STALE_STATE, wrong civ/timeline/query -> INVALID_ARGUMENT, and new queries after re-observation. Check Unicode names by UTF-8 bytes, not character count.
- [ ] Run focused tests to demonstrate failures.
- [ ] Implement map/inspection paths and common response-budget checks for new read tools. Use engine wrap/distance APIs and permitted memory indexes; unknown geometry cannot affect returned summaries. Map reads never mutate or paginate over changing revisions silently.
- [ ] Run bridge/transport/schema/provider-configuration tests and typecheck; expected PASS. No real provider invocation is necessary.

### Task 4: Make worker options and build validation agree

**Files:** Create `AgentWorkerOptions.kt`; modify `AgentObservation.kt`, `AgentEngineBridge.kt`, `MatchCoordinator.kt`, `AgentCommand.kt`, and the narrow agent-routing branch of `ImprovementPickerScreen.kt` if needed for explicit queued work; extend runner map types; create `AgentWorkerOptionsTest.kt`; extend `DomesticOrdersTest.kt`, `RaidAndRepairTest.kt`, `HumanRoutingAndPublicationTest.kt`, `AgentEngineBridgeTest.kt`.

**Interfaces:**

- `WorkerImprovementOption`: name, kind, turnsToBuild, replacesImprovement, connectsResource and nullable context-labeled tile yieldDelta.
- `WorkerOptions`: improvements (`ArrayList<WorkerImprovementOption>`), workInProgress (`WorkInProgress?`), repair (`RepairOption?`), canCancelWork (`Boolean`). `WorkInProgress` has name/turnsRemaining; `RepairOption` has turnsToRepair. Already active work is excluded from improvements. TypeScript mirrors these fields.
- LegalActionsDto adds revision, improvements, workInProgress, repair and canCancelWork. Empty worker options are distinct from city constructions.
- `buildWorkerOptions(game: GameInfo, actorCivId: String, unit: MapUnit): WorkerOptions`.
- `requireWorkerBuildAllowed(context: WorkerBuildContext, improvement: TileImprovement): Unit` uses the same full engine restrictions as option enumeration. Context binds game, actor, unit and tile, including projected tile state for queue validation and tile-sensitive conditionals.
- Shared repair/cancellation eligibility helpers back both advertised options and candidate mutation guards; cancellation requires a capable builder, authorized work and a genuinely cancellable queue, not a one-time marker.
- `unit.stopImprovement {unitId}` routes the existing StopImprovementCommand. Repair retains its existing command.
- For routed human queues, represent an explicit internal atomic queue intent separately from immediate BuildImprovementCommand. Validate the entire sequence on a disposable projection before starting/queuing via engine APIs. Reject an invalid later entry without committing earlier work. Projection has no stockpile, chopping-yield, notification, completion or sighting side effects; bind conditional checks to projected tile state. Never advertise future worker options as legal-now immediate builds.

- [ ] Write failing tests for grassland, dry desert, hills, forests, water, city centers, own/unowned/enemy territory, missing tech, hidden resources, resource requirements and unique improvement availability.
- [ ] Assert every advertised option is accepted at the same revision; guessed invalid builds fail with unchanged revision/tile/resources/memory. Non-workers get no worker options and cannot issue worker builds.
- [ ] Add progress tests: same action ID keeps its receipt; a new action ID requesting active work is rejected with ALREADY_IN_PROGRESS without resetting remaining turns or charging again; stopping and switching work are explicit; successful assignment wakes a sleeping worker.
- [ ] Add road/railroad/removal/repair/cancel coverage and forest-clearing prerequisites. Test builder improvement-filter and outside-border repair restrictions; a military unit cannot cancel work, unauthorized foreign work cannot be cancelled, and one-time markers cannot yield successful no-ops. Turn costs must match engine game-speed and worker modifiers.
- [ ] Add routed human clear-then-build queue tests for success, invalid-second-entry atomic rollback and projection side effects. Do not silently bypass queued-work support to make stricter validation pass.
- [ ] Run those focused tests and confirm failure under the current incomplete builder-only validation.
- [ ] Implement shared build, repair and cancellation eligibility from unit capability plus tile building problems and action eligibility; call each in discovery and mutation handlers on the candidate. Keep great-person instant improvements separate. Compute yield deltas on a private sanitized tile clone with actor/own-city context; omit a delta when that context cannot safely establish it. Audit lawful human-visible freshwater/adjacency mechanics separately from raw hidden geography so privacy tests do not wrongly reject human-equivalent legal options.
- [ ] Run worker, domestic, repair, routing and paired-hidden-resource tests; expected PASS. No normal non-agent gameplay regression or implicit worker automation is introduced.

### Task 5: Teach the agents to use geography and real rules

**Files:** Create `AgentRulesCatalog.kt`; modify coordinator/bridge/desktop dispatch and runner service, stdio and MCP rules paths; modify `agent-runner/src/game-instructions.ts`, `turn-prompt.ts`, `tool-summary.ts`; create `agent-runner/test/map-instructions.test.ts`; extend `tool-summary.test.ts`, `game-mcp-server.test.ts`, `AgentEngineBridgeTest.kt`; add `docs/agent-map-tools.md` and update `agent-protocol/README.md` during implementation.

**Interfaces:**

- `RulesQuery(section: String? = null, name: String? = null, limit: Int = 64, cursor: String? = null)`; `rules(query)` retains summary behavior by default and supports improvements/terrains/resources catalogs.
- `RulesResult` is the existing ruleset/version summary or `RulesCatalogPage` containing ruleset identity, section, entries and nextCursor. Each entry identifies its exact name and section-specific technology, terrain/effect, yield and maintenance data from static definitions; never include live map locations.
- Static catalog pages obey the same 96 KiB UTF-8 budget and 1–128 limit, with cursor binding to pinned ruleset identity and query. Live eligibility remains in legal_actions.
- Tool summaries: `Read map · N tiles`, `Inspected tile · terrain`, and `Checked worker options · N improvements`; raw details remain expandable.

- [ ] Write failing tests showing section/name are forwarded through every layer; named Farm rules include technology/terrain/effects, default rules still returns identity, invalid sections fail clearly, and large catalogs paginate within bounds. Apply the same oversized-entry/non-progress guard as map pagination.
- [ ] Add prompt assertions covering structured-map access, stale remembered facts, coordinate/wrap conventions, terrain inspection before settling/building, worker improvements versus constructions, existing work preservation, and re-query after state changes. Preserve personalities and nuclear-restraint text supplied through profiles.
- [ ] Run the focused rule/prompt/tool-summary tests and confirm failure.
- [ ] Implement bounded catalogs from installed pinned ruleset definitions, and update exact examples: `game.inspect({unitId:49})`, `game.map({center:{x:48,y:-33},radius:2})`, then `game.legal_actions({unitId:49})`; issue unit.build only using a listed name/current revision. Explain that map memory is historical knowledge, not current enemy intelligence.
- [ ] Run rule/prompt/summary/typecheck tests; expected PASS. Document save migration and limitations, including incomplete pre-upgrade terrain history and no screenshots.

### Task 6: Validate the combined behavior and prepare a safe release

**Files:** Create `tests/src/com/unciv/logic/agents/AgentMapAcceptanceTest.kt` and `agent-runner/test/map-workflow.test.ts`; extend schema fixtures and `docs/agent-acceptance.md` during implementation. Keep diagnostics in an ignored manual-test directory, never in the active user's profile.

- [ ] Add a scripted China-style two-worker fixture: inspect terrain, obtain legal improvements, build only listed options, commit a turn, inspect continuing work, then save/reload without lost progress. Assert that no strategy substitution or random farm guessing is required by the interface.
- [ ] Add a scout/settler fixture: page nearby terrain, move through multiple visible regions, retain remembered geography, identify a known candidate city site and found only where the engine allows. Test the information flow, not a claim of strategic superiority.
- [ ] Exercise the real bridge with fake providers and copied save inputs; verify all new schema fields and errors reach the agent, while spectator visibility does not widen its map.
- [ ] Measure a 164×80 Earth fixture with up to 15 civs, partially and fully discovered maps. Record query bytes, serialized checkpoint size, deep-copy time, and mutation/checkpoint latency against the baseline. Profile regressions exceeding 20% in median mutation latency; report absolute times and fix avoidable scans before release. This threshold is an investigation gate, not a promise about whole-game FPS.
- [ ] Implement and verify the user-approved compact map-memory representation and compressed managed recovery window: retain only the latest three verified recovery checkpoints after a successful verified write. Report measured sizes/latencies and the bounded three-checkpoint estimate. Preserve every existing archive file; prune only generations owned by the new managed window. Verify fallback recovery, interrupted-write/corruption behavior and retention failure handling. Keep manual saves, settings, presets/maps, game logs and recovery under `/Volumes/Lexar/Unciv/userdata`, preserve the original profile during copy migration, and fail closed if Lexar is unavailable before any checkpoint.
- [ ] Run focused new tests, then the full existing agent suite, runner typecheck/tests and build. Run normal save/turn/worker regressions appropriate to the actual files changed. Root records command results separately; command examples are not completion evidence.
- [ ] On explicit implementation/release authorization, wait for the live match's safe pause or user exit before packaging/installing. Back up original saves/checkpoints, audit the installed read-tool classes/modules against tested output, and preserve presets/maps/bonuses.
- [ ] Refresh tool configuration only for subsequent provider invocations. Verify Codex/Claude/OpenCode sessions expose the added tools after resume; start a fresh provider session with retained strategy notes only if that provider cannot refresh tool discovery, and record that transition. Never replace an active invocation mid-turn.
- [ ] Perform a short user-approved live check on a resumed copied match or new test match: worker reads terrain/options, scouting reveals geography, memory persists, feed summaries stay compact, and leaving the match still stops additional scheduling. Record automated checks separately from live acceptance.

## Verification commands

Kotlin focused gates (use the repository's current configured JDK/build roots):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/Users/noahkenny/.gradle-unciv ./gradlew :tests:test --tests 'com.unciv.logic.agents.AgentMap*' --tests 'com.unciv.logic.agents.AgentWorkerOptionsTest' --tests 'com.unciv.logic.agents.ObservationVisibilityTest' --tests 'com.unciv.logic.agents.AgentEngineBridgeTest' --offline
```

Combined agent regression gate:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/Users/noahkenny/.gradle-unciv ./gradlew :tests:test --tests 'com.unciv.logic.agents.*' --offline
```

Runner, each command run separately from `agent-runner/` with the configured local Node runtime:

```sh
npm run typecheck
npm test
npm run build
```

Expected: zero test failures, schema/transport agreement, no live providers started by automated checks, and payload/visibility/progress assertions met. Do not claim live gameplay acceptance from unit-test results.

## Plan review and implementation boundary

Save compatibility is decided: version 2 protects map memory, with version-1 migration for existing saves and updated-app requirements for new saves. The other proposed defaults are structured queries first, normal fog of war, persistent last-seen memory, explicit worker choices and shared legality. The storage gate was triggered by a measured roughly 147 MB Earth checkpoint (about 1.65 s; approximately 1.47 TB for 10,000 immutable generations). The user subsequently approved compact memory plus compressed recovery limited to the latest three verified managed checkpoints, leaving old archives untouched and placing game data on Lexar. Implementation and retention/recovery validation are in progress.

This document originally recorded planning-only work. The user has since explicitly authorized implementation and the storage policy above. Root owns final test/release status; this update does not mark unchecked steps complete or authorize interruption of the running match.

The measured feed-blur refresh cost was about 4.97 ms versus 0.46 ms for the plain feed on the local M4 Pro/2x Retina benchmark, so the feed blur was removed. These refresh timings do not establish whole-game FPS or guarantee live performance.


## Execution record — 2026-10-11

Tasks 1–5 are implemented: permitted tile DTOs and v2 schema, transactional map memory with legacy migration, bounded actor-bound read transport and all provider allowlists, shared worker legality/progress/atomic human queue behavior, rules catalogs and agent instructions. OpenCode Go DeepSeek V4.1 Flash is available as an additional maintained model choice. Feed blur was removed after the measured refresh cost.

Task 6 automated gates passed:323 Kotlin checks,64 runner tests/typecheck/build,eight schema fixtures, actual copied v1 checkpoint and normal-save restoration/roundtrip, compact/GZIP latest-three recovery on Lexar, JFR performance investigation and native artifact/startup audit. Gameplay data is `/Volumes/Lexar/Unciv/userdata`; every copied profile file was SHA256 checked and the source remains preserved. Installed class/module hashes match tested outputs.

Live provider authentication and user interaction acceptance remain pending; no paid provider invocation was started. App-control clicks fail with `noWindowsAvailable`. Detailed evidence and measurement limits are in [agent acceptance](../../agent-acceptance.md) and the ignored `.superpowers/sdd/2026-10-11-agent-map-understanding/` ledger.
