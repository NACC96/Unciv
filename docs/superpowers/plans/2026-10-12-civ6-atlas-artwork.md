# Atlas artwork and asset delivery implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship complete original illustrated terrain and unit art with validated, bounded animation residency.

**Architecture:** Add separate AtlasTerrain/AtlasUnits selections through existing tile and unit lookup. A versioned manifest maps ruleset names to static art and optional directional clips; static previews are discoverable normally, while animation pages are loaded through a separate bounded owner. Retain legacy/mod fallback behavior.

**Tech Stack:** Built-in image generation, PNG layers, JSON manifests, existing TexturePacker, Kotlin/LibGDX TextureAtlas, JUnit and GPU fixtures.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md), [master plan](2026-10-12-civ6-atlas-implementation.md).

## Global Constraints

- Master constraints apply. Original raster art uses image generation; do not substitute procedurally drawn placeholder units for the approved illustrated art.
- Required union: **127 named units** across both bundled rulesets; complete terrain/resource/improvement/wonder/era-city coverage and distinct unique units.
- Six facings; 128×128 default / 192×192 tall-unit canvas with trimmed frames and retained anchors. Frame counts are state-specific and must visibly animate.
- Preserve default **2048×2048** packing limits for unrelated assets and their existing filters.
- Cache default **128 MiB desktop / 32 MiB elsewhere**, pending upload cap **8 MiB**; account for masks/mipmaps/decoded staging separately. No full animation library in `Atlases.json`.
- Authoring files, provenance, contact sheets and timing reports stay on Lexar. Track shipped source assets/manifests and compact validation reports in Git.

## Review Focus

1. A missing mod sprite or oversized clip must show the correct static fallback, never a blank/wrong unit (A1/A3).
2. Trimmed/mirrored frames must retain feet, weapon hand and mask alignment (A1/A4).
3. Ocean wrapping and unknown neighbors must not produce seams or leak terrain (A2).
4. Eviction during playback or view change must not dispose a pinned texture or leak residency (A3).
5. A complete filename list with duplicate placeholders is not complete original art (A4).

---

## File and interface map

- Create `android/assets/jsons/TileSets/AtlasTerrain.json` and `AtlasUnits.json`; source static art under `android/Images.AtlasTerrain/TileSets/AtlasTerrain/` and `android/Images.AtlasUnits/TileSets/AtlasUnits/Units/`.
- Create `art/atlas/manifest.json`, `art/atlas/provenance.json`, `art/atlas/coverage.json` and `art/atlas/units/<id>/` authoring metadata. Production animation source sheets live under `art/atlas/units/<id>/frames/`; keep prompts/provenance beside them. Large rejected candidates remain only in the Lexar artifact folder.
- Create `buildSrc/src/main/kotlin/AtlasAssetPacker.kt`; extend existing `AndroidImagePacker.kt` with an explicit separate clip-output path. Output runtime clip metadata/pages under `android/assets/atlas-animation/`; they are not ordinary ImageGetter atlases.
- Create `core/src/com/unciv/ui/images/atlas/{AtlasArtManifest.kt,AtlasClipCache.kt,AtlasClipLoader.kt}` and `tests/src/com/unciv/ui/components/{AtlasAssetContractTest.kt,AtlasClipCacheTest.kt,AtlasArtCoverageTest.kt}`.
- Modify `core/src/com/unciv/ui/components/tilegroups/TileSetStrings.kt`, layer asset resolution only where current extension points cannot carry metadata; do not rewrite unrelated tile logic.

## Shared contracts (defined by A1)

Use immutable Kotlin data classes after parsing mutable JSON DTOs. `ArtUnitId` is the exact ruleset unit name string, not a translated label. `HexFacing` has E, NE, NW, W, SW, SE; `UnitVisualState` has IDLE, MOVE, ATTACK, HIT, DEFEAT, WORK. Founding/capture/improvement can use shared effects with unit-specific compatible clips.

`ClipKey(unitName: String, state: UnitVisualState, facing: HexFacing)` identifies a clip. `ClipSpec` holds ordered frame regions, per-frame durationMs, anchor coordinates, static fallback key, page names, optional declared mirrored-facing and looping flag. `UnitArtSpec` holds static layers/masks/shadow, clipping margin, unique-unit identity and applicable states. `AtlasArtManifest.findUnit(name: String): UnitArtSpec?` and `findClip(key: ClipKey): ClipSpec?` never guess a different unit. `AtlasClipCache.request(key: ClipKey): ClipHandle?` returns a pinned loaded handle or null while loading/missing/over budget; `ClipHandle.close()` releases the pin, idempotently. Only the cache owns/disposes textures. `trim()` evicts unpinned pages; `dispose()` tears down all owned resources after playback closes.

## Task 1: A1: Manifest, coverage inventory and packing contract

**Files:** Manifest/packer classes and JSON files above; add `scripts/atlas/assets.sh` validation/packing entry point. Test `AtlasAssetContractTest.kt` and new buildSrc packer tests.

**Interfaces:** Produce the shared contracts above and `assets.sh validate|pack|coverage [--require-complete]`. Coverage JSON rows record ruleset key, category, intended source, review state (`missing`, `candidate`, `accepted`) and evidence. `--require-complete` accepts only reviewed final rows; fallback existence alone is insufficient.

- [ ] Write `manifestRejectsBadBoundsMasksAndDurations`, `packingKeepsAnimationOutOfEagerList`, `rulesetUnionIncludesAllUnits`: `assertEquals(127, requiredUnitNames.size)`; reject negative/zero duration, out-of-bounds anchor, incomplete mask or invalid mirroring; `assertTrue(eagerAtlases.intersect(clipPages).isEmpty())`. A missing ordinary mod clip returns null and its original static art still resolves.
- [ ] Run `scripts/atlas/check.sh '*AtlasAssetContractTest'`; expect missing contract failure. Include a tiny explicitly labeled synthetic sheet only in test resources, not production art.
- [ ] Implement manifest schema version 1, strict validation and deterministic packing. Trim transparent margins while preserving anchor; keep mask dimensions/region order matched, pack with padding/edge duplication, choose clip-family pages ≤2048 and below the largest per-platform clip budget. Keep page filters scoped to Atlas assets. Validate exact case-sensitive keys on disk.
- [ ] Generate coverage from both bundled JSON rulesets plus city-era and neighbor-transition requirements. Copy/verify the earlier inventory; do not assume its counts prove final art coverage. Record provenance entries `{assetId, creator, source, promptPath, rights, sourceHash, outputHashes}`; generated images are original, third-party references never silently become shipped assets.
- [ ] Run focused tests, `scripts/atlas/assets.sh validate` and `pack`; expect valid test manifests, separate clip outputs and explicit missing production coverage. Commit `feat: add validated atlas art manifest and separate clip packing`.

## Task 2: A2: Complete illustrated map, cities and improvements

**Files:** AtlasTerrain sources/config, art coverage/provenance; modify `layers/TileLayerTerrain.kt`, `TileLayerFeatures.kt` only if required by verified layer support, and `tilegroups/citybutton/CityButton.kt` for art bounds. Extend `tests/src/com/unciv/ui/components/AtlasArtCoverageTest.kt`, existing `CityButtonMapLayerCullingTest.kt`, and terrain fixture.

**Interfaces:** Consume existing `TileSetStrings` lookup and visible `TileView` neighbor access. Produce complete `AtlasTerrain` art that existing map/map-editor layers can resolve, with declared extra culling margin for taller city/terrain art. No hidden-neighbor access is introduced.

- [ ] Write `everyBuiltInTerrainResourceImprovementAndCityEraResolves`, `unexploredNeighborDoesNotAffectEdgeChoice`, `tallCityStaysVisibleAcrossWrap`: expected missing sets empty for final mode; hidden neighbor variations render identical visible pixels; edge-intersecting art is not culled while its tile center is outside the viewport.
- [ ] Run `scripts/atlas/check.sh '*AtlasArtCoverageTest' '*CityButtonMapLayerCullingTest'`; final coverage initially fails on absent new assets.
- [ ] Author coherent batches: (1) land/water/elevation/coast/river transitions, (2) vegetation/snow/desert/marsh/natural wonders, (3) resource icons/deposits and improvements/roads, (4) era cities, pillaged/damaged/ownership states, fog and remembered terrain. Reuse approved lighting/palette/projection and record the original prompts. Each batch is bounded to a contact sheet of at most 16 assets and has its own source/provenance commit after visual review.
- [ ] Integrate through existing layers; keep resource, road, border, ownership, selection and fog readability at three zoom levels. Use neutral cartographic unexplored artwork independent of hidden map data. Generated and both Earth maps use the same neighbor rules; preserve horizontal wrap.
- [ ] Run focused checks and `scripts/atlas/render.sh terrain --matrix --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/terrain`. Inspect mixed-biome adjacency sheets and actual map captures, not only isolated images. Mark rows accepted only after no seams/bleed/missing edges at small/medium/strategic zoom.
- [ ] Commit final integration `feat: integrate complete illustrated atlas terrain and city art`; task remains open until all map categories pass coverage.

## Task 3: A3: Bounded asynchronous animation clip residency

**Files:** Implement `AtlasClipCache.kt`, `AtlasClipLoader.kt`, extend `AtlasClipCacheTest.kt` and `AtlasRenderCheck.kt`; touch `ImageGetter.kt` only for reset/disposal coordination, never add clip pages to eager atlas discovery.

**Interfaces:** Consume A1 ClipKey/ClipSpec/ClipHandle. Produce `AtlasClipCache.request/trim/dispose` plus `metrics(): ClipCacheMetrics` with allocatedBytes, pinnedBytes, pendingDecodedBytes, hit/miss/eviction counts. Loader parses/reads off-thread and schedules allocation/upload/disposal on the GL thread, outside actor draw. CPU queues are bounded; saturated work retries on a later request, not blocking gameplay.

- [ ] Write `cacheBudgetIncludesMasksAndMipmaps`, `pinnedClipSurvivesEviction`, `oversizedClipFallsBack`, `resetDropsStaleAsyncLoads`: `assertTrue(metrics.allocatedBytes <= budgetBytes)` after every request; no disposal while handle open; oversize returns null; a load completed from an old cache generation cannot allocate/install into a new generation. Also assert decoded staging ≤8 MiB and no upload/disposal invoked from draw callbacks.
- [ ] Run `scripts/atlas/check.sh '*AtlasClipCacheTest'`; expect missing cache behavior failure.
- [ ] Implement page-aware LRU sharing, pin counts, in-flight request deduplication and generation fences. Reserve allocation before GL upload; if page set cannot fit, use static fallback rather than exceed cap. Budget includes all resident levels and layers. Enforce UI/game lifecycle cleanup without touching gameplay snapshots.
- [ ] Re-run tests and a GPU load/play/release/view-switch stress loop using real A4 representative clips when available. Metrics must return to steady baseline after 100 cycles; no unit disappears under pressure. Record at both 128 and 32 MiB budgets.
- [ ] Commit `feat: bound atlas animation memory with on-demand clip loading`.

## Task 4: A4: Complete new unit designs and animated states

**Files:** AtlasUnits source static art, `art/atlas/units/` clips and manifests/provenance/coverage; extend `AtlasArtCoverageTest.kt` and `units` fixture. Connect static masks via `TileLayerUnitSprite.kt` without changing unit input/authority.

**Interfaces:** Consume A1 static/clip contract, A3 cache, and V3 playback once ready. Produce accepted distinct artwork for every required ruleset name; applicable clip states resolve or have an explicit shared-effect treatment appropriate to that unit. A missing MOVE/ATTACK clip for an applicable built-in unit is unfinished coverage, not accepted fallback.

- [ ] Write `all127UnitDesignsHaveAcceptedArtAndRequiredMotion`, `uniqueUnitsAreNotOnlyRecolors`, `framesKeepStableAnchorsAndTintMasks`: `assertEquals(requiredNames, acceptedNames)`; each unique's base-art hash differs from its parent and has a visual review record; animation frame hashes vary; anchors/masks/declared facing bounds are valid. Hash variation is a mechanical check, not proof of good animation.
- [ ] Run `scripts/atlas/check.sh '*AtlasArtCoverageTest'` and `scripts/atlas/assets.sh coverage --require-complete`; expect incomplete-unit failures until all batches finish.
- [ ] First produce six representative map-projection units: Warrior, Worker, Horseman, Rifleman, Tank and Trireme, with nation masks, shadow, idle/move/attack or work, hit/defeat. Generate actual aligned motion sequences, not six duplicate poses. Integrate with V3 and inspect frames in motion before multiplying the library.
- [ ] Complete batches of at most 12 unit types: civilian/great people; ancient/classical infantry/ranged; mounted; medieval/renaissance; industrial/modern infantry/armor/siege; naval; aircraft/missiles; unique-unit remainder. Derive exact batch membership from the inventory, assign each key once, and make a source/provenance/review commit per accepted batch. Applicable naval/air animations must differ from walking infantry; missiles use launch/travel/impact and consumed-unit cleanup.
- [ ] Review all designs in contact sheets AND real tiles at three zooms with multiple nation colors, civilian/military stacks and aircraft/cities. Safe mirroring is explicit; flag placement, handedness and tall-art culling are inspected. Keep detailed designs recognizable at actual play zoom.
- [ ] Run final coverage/tests, packing and `scripts/atlas/render.sh units --matrix --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/units`. Record clip-cache measured residency during mixed-unit playback. All 127 rows must be accepted with evidence, no candidate placeholders.
- [ ] Commit final integration `feat: complete atlas unit designs and animation library`. Keep task open if any unit/state remains missing, even if the representative demo looks finished.
