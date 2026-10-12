# Atlas committed-action animation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show real movement, combat and world changes for human, agent and built-in AI turns without changing gameplay or revealing hidden information.

**Architecture:** Capture small immutable visual facts at real engine mutation boundaries, publish only after successful commit, and filter facts using the local viewer at event time. A transient bounded journal bridges agent snapshot publications and ordinary world updates into one GL-owned playback timeline. Loaded games start at their final state with an empty timeline.

**Tech Stack:** Kotlin engine hooks, existing GameView privacy, existing MatchCoordinator/desktop publication fence, Scene2D actors/actions, Atlas clip cache.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md), [master plan](2026-10-12-civ6-atlas-implementation.md).

## Global Constraints

- Master constraints apply; this layer cannot execute actions, change damage, delay durable commits or keep provider turns open.
- No visual facts or image bytes in saved game/receipts/checkpoints; the existing three-recovery-checkpoint policy remains untouched.
- Journal ≤256 events, playback ≤64; movement initially 180/70 ms per hex Normal/Fast, capped at 900/300 ms per move. Reduced/off reconciles directly.
- Hidden routes, sources, defenders and terrain cannot leak via sprites, audio, labels, camera or diagnostics exposed to players.
- Ghosts never accept gameplay input. Final actor state and hit targets resolve against current committed state.
- No permanent render loop; clear/reconcile on load, view changes, screen removal, resize, skip and disposal.

## Review Focus

1. Commit failure/replayed action cannot emit an apparently successful animation (V1).
2. Multiple snapshots at the same revision but different publication generations cannot replay/drop authority changes (V1/V2).
3. An attack from darkness can show a permitted hit without exposing its source/unit type (V2).
4. Spectator → human during playback must discard privileged visuals and camera targets immediately (V2/V3).
5. Consumed units, wraps and catch-up must not leave duplicate sprites or pinned texture handles (V3).

---

## File and interface map

- Create engine-side `core/src/com/unciv/logic/presentation/{VisualEvent.kt,VisualJournal.kt,VisualCapture.kt}`. These contain no graphics objects and no providers.
- Create `core/src/com/unciv/view/VisualEventProjector.kt` for event-time privacy projection.
- Create `core/src/com/unciv/ui/screens/worldscreen/presentation/{WorldVisualTimeline.kt,UnitVisualActor.kt,VisualEffects.kt}` for GL-owned playback and effects.
- Modify `GameInfo.kt` only for a `@Transient` per-instance optional capture sink, excluded from cloning/serialization. No global mutable event bus.
- Engine hooks: `logic/map/mapunit/movement/UnitMovement.kt`, `logic/battle/Battle.kt`, `logic/battle/Nuke.kt`, `logic/city/managers/CityFounder.kt`, `logic/map/tile/TileImprovementFunctions.kt` at actual successful mutation boundaries.
- Publication: `logic/agents/MatchCoordinator.kt`, `AgentSnapshotPublication.kt`, `desktop/src/com/unciv/app/desktop/agents/DesktopAgentMatch.kt`, `WorldScreen.installAgentSnapshot`.
- Playback: `worldscreen/worldmap/WorldMapHolder.kt`, `worldscreen/bottombar/BattleTableHelpers.kt`, `tilegroups/layers/TileLayerUnitSprite.kt`, `GameSettings.kt`, `ui/popups/options/DisplayTab.kt`.

## Shared contracts (V1 owns engine types; V2 owns projection)

`VisualViewKey(viewerCivId: String, spectatorMode: Boolean, epoch: Long)` fences the precise authority; epoch increments on load/view changes, not each frame. `VisualEventId(gameId: String, timelineId: String, sequence: Long)` is monotonic within the transient session; agent commit provenance also retains committed revision/action ID as metadata. Ordinary games use a generated session timeline and sequence, without adding a saved revision.

`VisualEvent` is an immutable sealed type: `Move` (permitted unit descriptor, actual visible contiguous path), `Attack` (only permitted source/target/descriptor/damage facts), `RemoveUnit`, `FoundCity`, `ChangeImprovement`, `CaptureCity`. Descriptors are values (stable ID, art name, permitted owner colors, health), never `MapUnit`, `Tile` or `GameInfo` references. Nullable source/descriptor means omit those visuals, not infer them. Split disjoint visible path portions into independent segments; never interpolate across an unseen gap.

`VisualBatch(view: VisualViewKey, events: List<VisualEvent>, throughSequence: Long, droppedThroughSequence: Long)` is already filtered. `VisualJournal.appendCommitted(events: List<VisualEvent>): Unit`; `readAfter(sequence: Long, view: VisualViewKey): VisualBatch`; `reset(view: VisualViewKey): Unit`. It stores only the current local viewer's permitted events. Capacity loss is explicit through `droppedThroughSequence` and causes reconciliation; it cannot queue unlimited work or retain raw omniscient events.

`VisualCapture.begin(game: GameInfo, view: VisualViewKey): CaptureScope` installs a thread-confined per-instance sink and freezes permitted before-facts at each engine hook. `CaptureScope.finish(): List<VisualEvent>` returns after-projected immutable facts; `close()` clears the sink. The capture owns no durable state. `VisualEventProjector` helpers evaluate `GameView`/existing visibility rules before facts are frozen; no mutable pre-action view reference is stored for later use.

## Task 1: V1: Capture committed engine transitions with no save growth

**Files:** Engine presentation classes, GameInfo transient sink and hook files above; MatchCoordinator mutation/turn-advance boundaries. Create `tests/src/com/unciv/logic/agents/CommittedVisualEventsTest.kt` and `tests/src/com/unciv/logic/presentation/VisualJournalTest.kt`.

**Interfaces:** Produce shared capture/journal/value types. V2 supplies privacy projection before enabling player-visible playback. Capture actual movement nodes after each successful step, including move-before-attack, paradrop, swaps and teleport distinction. Record battle outcomes after resolution, including consumed attackers, retreat/capture/nuclear results, without assuming every attack is melee.

- [ ] Write `failedCommitEmitsNothing`, `duplicateReceiptDoesNotAppend`, `journalIsBoundedAndNeverSerialized`: `assertTrue(journal.readAfter(0, view).events.isEmpty())` after injected persistence failure; replay leaves sequence unchanged; 300 appends retain ≤256 and expose a gap; ordinary game serialization with an active capture is byte-equivalent to the same state without it, excluding pre-existing volatile fields.
- [ ] Run `scripts/atlas/check.sh '*CommittedVisualEventsTest' '*VisualJournalTest'`; expect absent contract/failing assertions.
- [ ] Implement scoped capture, successful mutation hooks and journal. For coordinator candidates, capture while applying to the candidate, finish projection while event-time facts exist, append only after `commit` succeeds. The duplicate-action early return emits nothing. Turn advancement/built-in AI processing uses the same scope; ordinary game mutations publish after their engine call succeeds. Finally blocks always remove sinks on errors. Until V2 projection is implemented and verified, gameplay leaves capture disabled; V1 tests use explicit synthetic projected facts. There is no temporary unfiltered player-visible transport.
- [ ] Keep capture optional/no-op in headless simulations, serializers and fixtures not requesting visuals. Capture is outside save models, provider responses and action receipts. A capture/asset/presentation failure must log a local diagnostic and permit the authoritative action/result; never convert a committed action into an agent retry.
- [ ] Run focused tests plus `*AgentMapTransactionsTest`, `*AgentCommandsTest`, `*AgentOrdinarySaveTest` and `*AgentHumanTurnChoicesTest`. Assert movement final positions, damage, gold, messages and checkpoint counts unchanged with capture enabled/disabled.
- [ ] Commit `feat: capture bounded transient visuals after committed actions`.

## Task 2: V2: Privacy-safe snapshot transport and authority changes

**Files:** `VisualEventProjector.kt`, publication/desktop/WorldScreen files above. Create `tests/src/com/unciv/logic/presentation/VisualEventPrivacyTest.kt`; extend `DesktopSnapshotPublicationTest.kt`, `AgentNationViewTest.kt`.

**Interfaces:** Consume V1 values. Add an optional in-process `visualBatch: VisualBatch? = null` member to `PublicationPayload`, and optional last parameter to `WorldScreen.installAgentSnapshot`. This is not added to game JSON or provider protocol. `VisualEventProjector` freezes before/after permitted identities, route segments, target effects and ownership using current GameView rules. Desktop tracks the last installed sequence/view epoch; GL callbacks keep the existing publication-generation fence.

- [ ] Write `hiddenAttackSourceIsAbsentFromBatch`, `hiddenRouteGapIsNeverInterpolated`, `viewChangeRejectsQueuedSpectatorBatch`, `sameRevisionNewGenerationCanClearAuthority`: absent source coordinate/art/owner for hidden attackers; two visible segments remain two, no bridge; stale epoch rejected; metadata-only takeover clears queued effects even if state revision is unchanged. Compare serialized visual DTOs for games differing only in hidden facts to prove no hidden-data channel.
- [ ] Run `scripts/atlas/check.sh '*VisualEventPrivacyTest' '*DesktopSnapshotPublicationTest' '*AgentNationViewTest'`; expect unimplemented transport/privacy failures.
- [ ] Implement event-time projection. Reveal only what each event's before/after GameView permits; if identity/path cannot be proven visible, use the confirmed visible state without an invented effect. Never use activity prose or post-action omniscient route history to reconstruct missing facts.
- [ ] Attach batch to the snapshot while holding the existing host lock; install only with matching game ID, generation and view epoch. Read cursor advances on successful installation, not snapshot creation, so skipped frames do not silently lose all visuals; journal gaps trigger explicit snap-to-current. View changes reset capture and GL timeline, drop old private batches and clear effects before rendering the new view.
- [ ] Re-run focused tests with rapid publications, late callbacks, identical revision/different generation, spectator fog toggle, spectator→France→spectator, screen closed and game replaced. Also run existing movement visibility tests.
- [ ] Commit `feat: transport only viewer-authorized animation facts`.

## Task 3: V3: Shared playback, catch-up and motion settings

**Files:** World presentation classes, WorldMapHolder/BattleTableHelpers/TileLayerUnitSprite integration, settings/DisplayTab. Create `tests/src/com/unciv/ui/components/WorldVisualTimelineTest.kt` and `tests/src/com/unciv/dev/AtlasAnimationRenderCheck.kt`.

**Interfaces:** `WorldVisualTimeline.accept(batch: VisualBatch): Unit`, `advance(deltaSeconds: Float): Unit`, `reconcile(): Unit`, `dispose(): Unit`; owns disposable `UnitVisualActor` ghosts with no input. Consume A1 ClipKey and A3 pinned handles. Settings `unitAnimationMode: String = "Normal"` (Normal/Fast/Reduced), `followVisibleActions: Boolean = false`, `appearanceVersion: Int = 0`; unknown mode safely falls back to Normal. No ruleset/save compatibility change.

- [ ] Write `agentAndHumanTakeoverUseSamePlayback`, `duplicateBatchNoSecondAnimation`, `catchUpRestoresFinalSprites`, `disposeReleasesPinsAndRenderDemand`: accept one action ID through both routing fixtures and expect one movement, final destination matches committed state; 100 rapid events leave queue ≤64 and no ghost at stale positions; all clip pins released at disposal and idle render requests return to baseline. Test wrap edge path, consumed attacker, selected civilian/military stack and offscreen events.
- [ ] Run `scripts/atlas/check.sh '*WorldVisualTimelineTest' '*AtlasClipCacheTest'`; expect missing playback failures.
- [ ] Implement frame playback/interpolation using actual permitted path with shortest wrapped coordinates. Hide only the corresponding destination sprite slot while its ghost is playing; keep authoritative hit targets. Before screen rebind/disposal detach ghosts and restore visibility idempotently. Superseding same-unit events reconcile old state before playing latest; no stale animation may move a unit backward.
- [ ] Replace legacy movement/battle visual routing with this shared path where events are available; do not double-play existing helpers. Preserve ordinary human, human-in-agent-match, spectator agent and built-in AI paths. Render confirmed state for actions lacking retained intermediate facts. Add projectile/recoil/hit/defeat/founding/work/capture effects using visible facts and applicable clips; nuclear visuals remain abstract outcome feedback governed by the same privacy rules.
- [ ] Implement bounded catch-up and visible skip-to-live affordance. Reduced motion removes movement/camera interpolation and looping idle but retains outcome labels/highlights. Idle animation only for selected/relevant visible units; stop when screen is hidden. Request the next frame only while playback/UI motion remains active. Optional camera follow yields immediately to pointer pan/zoom, is visibility-safe and remains off by default.
- [ ] Run focused tests and `scripts/atlas/render.sh animation --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/animation` after registering the fixture ID. Record GPU frame sequences for all effect families, wrap/fog entry/exit, load/resize/nation-change/skip, Normal/Fast/Reduced, and verify final committed state. Use real representative A4 clips for acceptance, not test rectangles.
- [ ] Commit `feat: play committed unit animations with fast and reduced-motion modes`.
