# Atlas foundation and world UI implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the common atlas components and a usable world screen, including all agent panels.

**Architecture:** Initialize copied semantic widget styles through the existing skin lifecycle, then migrate layouts deliberately. Keep authoritative callbacks and view models; add pure layout policy and synthetic fixtures for bounds, behavior and performance.

**Tech Stack:** Kotlin, Scene2D, existing skin/Fonts infrastructure, JUnit/Mockito, LWJGL fixtures, Gradle.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md) and [master plan](2026-10-12-civ6-atlas-implementation.md).

## Global Constraints

- Master constraints apply: no running-app changes, blur, hidden-state leaks or game-rule changes.
- Body contrast ≥4.5:1; essential graphics ≥3:1. Five target sizes, 1.0×/1.5× fonts; ≥45% map visible in the default 1440×900 Activity layout.
- Preserve current selected fonts/symbols, input focus, user font scale and mod selections.
- New UI is not finished solely because component tests pass; capture and inspect each migrated surface.

## Review Focus

1. Theme reload while controls are detached must retain state/listeners (F2).
2. Long localized nation/resource names must not overlap adjacent regions (W1).
3. Snapshot refresh while typing must preserve draft/caret and suppress world hotkeys (W2).
4. Live updates must not move a reader's historical anchor or nation selection (W2).
5. Spectator view and human control must remain visibly and functionally distinct (W1/W2).

---

## File and interface map

- New `scripts/atlas/{check.sh,render.sh,build.gradle}`: isolated repeatable verification, never installation.
- New `docs/atlas/screen-coverage.json`: inventory of first-party screen families, entry points, fixtures and status, including supporting dialogs.
- New `tests/src/com/unciv/dev/{AtlasRenderCheck.kt,AtlasFixtureCatalog.kt,AtlasPerformanceCheck.kt}`: actual Scene2D/map fixtures and matching baseline/changed traces.
- Existing `core/src/com/unciv/ui/components/GameUiStyle.kt`: semantic colors/type/spacing and widget state styling.
- New `core/src/com/unciv/ui/components/atlas/AtlasWidgets.kt`: reusable header/stat/tab/field/disclosure/footer builders; no game rules.
- New `core/src/com/unciv/ui/screens/worldscreen/WorldHudLayout.kt`: pure rectangle allocation; `WorldScreen.kt` remains lifecycle owner.
- Modify `core/src/com/unciv/ui/screens/worldscreen/WorldScreenStyle.kt` as compatibility facade, not a second theme.

## F1: Establish complete coverage and reproducible baseline

**Files:** Create the scripts, coverage inventory and three dev fixtures above. Create `tests/src/com/unciv/ui/components/AtlasCoverageTest.kt`; modify `tests/build.gradle.kts` only to expose a synthetic runtime classpath task if needed.

**Interfaces:** Produce `AtlasFixtureCatalog.ids(): Set<String>` and `create(id: String, width: Int, height: Int, fontScale: Float): BaseScreen`. Baseline IDs: `world`, `activity`, `messages`, `overview`, `nation-control`, `terrain`, `units`, `city`, `research`, `policies`, `religion`, `promotions`, `diplomacy`, `setup`, `menu`, `supporting`. These construct real actors over isolated fixtures, not placeholder screens. CLI `check.sh <JUnit class glob>...` invokes `:tests:test` with one `--tests` per glob; `check.sh --gradle <task>...` runs explicit wrapper tasks against the same isolated build. `render.sh <id|performance> --output <absolute path> [--matrix]` returns nonzero on assertions or missing captures. The matrix means all five sizes at both font scales; later tasks register additional IDs such as `animation`.

- [ ] Write `coverageInventoryHasAnEntryPointAndFixtureForEveryScreenFamily`: assert inventory includes all families in the readiness audit, each entry has nonempty source paths/fixture IDs/status, and IDs resolve. Write `fixturesNeverUseLiveDataOrProviders`: assert temp settings/save roots and a provider stub that throws on invocation. Test code assertions: `assertEquals(emptySet<String>(), uncoveredFamilies)`; `assertTrue(dataRoot.startsWith(fixtureRoot))`; `assertEquals(0, providerCalls)`.
- [ ] Run the initial test with the current wrapper: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/Users/noahkenny/.gradle-unciv ./gradlew -I /Volumes/Lexar/Unciv/.superpowers/ui-graphics-polish/polish-build.gradle :tests:test --tests '*AtlasCoverageTest'`; expect missing fixture/inventory failure, not an unrelated build error.
- [ ] Implement fixtures by reusing the existing `MapPolishRenderCheck`, `AgentMessagesRenderCheck`, `SetupPolishRenderCheck` and their isolated test setup. Make new scratch default `/Users/noahkenny/.unciv-atlas-build/<worktree-id>` and parameterize it; use existing Gradle cache. Logs/captures go to the supplied Lexar output. The wrapper must never invoke `desktop:run`, `macApp` or provider CLIs.
- [ ] Implement the seeded mixed-biome 164×80/15-civ trace: fixed unit counts, zoom/camera keyframes, warm-up 10 seconds, measure 30 seconds, repeated three times. Record frame CPU, swap-inclusive frame time, draw calls, texture allocations, heap and idle render count. GPU-only timings are nullable when timer queries are unsupported, explicitly labeled; never report CPU as GPU time. Save source hash and fixture parameters.
- [ ] Run `scripts/atlas/check.sh '*AtlasCoverageTest'` and `scripts/atlas/render.sh performance --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/baseline`; expect passing tests and three complete baseline traces. Capture existing screens before style changes. A baseline exceeding target is recorded for E1 optimization.
- [ ] Commit only scripts, fixtures, inventory and compact baseline metadata: `test: establish atlas screen coverage and rendering baseline`.

## F2: Common atlas skin and reusable controls

**Files:** Modify `GameUiStyle.kt`, `WorldScreenStyle.kt`, `core/src/com/unciv/models/skins/SkinStrings.kt`, `core/src/com/unciv/ui/screens/basescreen/BaseScreen.kt` at style initialization, and `core/src/com/unciv/ui/popups/options/DisplayTab.kt`. Create `AtlasWidgets.kt`, `android/assets/jsons/Skins/Atlas.json`, `android/Images.Skin/Skins/Atlas/` drawables. Create `tests/src/com/unciv/ui/components/AtlasThemeTest.kt`; extend `SetupStyleLifecycleTest.kt`.

**Interfaces:** Produce `AtlasWidgets.header(title: String, subtitle: String? = null): Table`, `stat(label: String, value: String, tint: Color? = null): Table`, `tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Table`, `field(label: String, input: Actor): Table`, `disclosure(title: String, body: Actor, expanded: Boolean): Table`, `footer(primary: Actor, secondary: List<Actor>): Table`. Keep existing `GameUiStyle.button/apply/setEnabled` signatures. Add `GameUiStyle.applyWidget(actor: Actor): Unit` for copied styles, not recursive per-frame recoloring.

- [ ] Write `themeReloadRetainsControlState` and `atlasContrastOnBrightAndDarkMaps`: `assertEquals(oldListeners, field.listeners.toList())`; `assertEquals("draft", field.text)`; `assertTrue(disabledButton.isDisabled)`; `assertTrue(minBodyContrast >= 4.5)`; `assertTrue(minControlContrast >= 3.0)`. Cover detached ExpanderTab content, inline resource glyphs and semantic red/nation colors. Add `legacySkinSurvivesAtlasSelectionCycle`: original/mod styles resolve identically after switching Atlas on and back off.
- [ ] Run `scripts/atlas/check.sh '*AtlasThemeTest' '*SetupStyleLifecycleTest'`; expect new theme/style assertions to fail before implementation.
- [ ] Implement navy/inset/raised/ivory/muted/brass from the master plan. Use fine nine-patch rims and modest shadows; do not edit game imagery with shell scripts. Initialize Atlas styles when the skin is created and gate them by the selected skin; keep old skins selectable. Default body sizes scale through the existing Fonts path. Add distinct hover/focus/pressed/disabled/selected states and symbols/text for statuses.
- [ ] Implement reusable builders; preserve original callbacks, keyboard registration and minimum touch targets. Use 40 logical pixels for standard controls, 32 for compact pointer controls, expanded hit targets where artwork is smaller. Skin changes must not mutate existing shared color objects.
- [ ] Re-run focused tests; capture `supporting --matrix`. Inspect actual widget gallery over bright/dark terrain, long text, diacritics, CJK and resource symbols, with focus and disabled states. Expect no missing glyphs, clipped content or contrast failures.
- [ ] Commit: `feat: add atlas theme and accessible shared controls`.

## W1: World HUD, ordered nations and contextual command dock

**Files:** Create `WorldHudLayout.kt` and `core/src/com/unciv/ui/screens/worldscreen/topbar/NationTurnRibbon.kt`. Modify `WorldScreen.kt`, `topbar/WorldScreenTopBar.kt`, `WorldScreenTopBarStats.kt`, `WorldScreenTopBarResources.kt`, `TechPolicyDiplomacyButtons.kt`, `unit/UnitTable.kt`, `unit/actions/UnitActionsTable.kt`, `NotificationsScroll.kt`, `minimap/MinimapHolder.kt`, `minimap/MapOverlayToggleButton.kt`, `status/NextTurnButton.kt`, `status/AgentMatchControlTable.kt`. Create `tests/src/com/unciv/ui/components/AtlasWorldHudTest.kt`; retain `WorldScreenHudTest.kt`.

**Interfaces:** Produce `WorldHudLayout.calculate(width: Float, height: Float, fontScale: Float, activityOpen: Boolean): HudRegions` where `HudRegions` holds header/tracker/activity/unitDock/minimap/turnAction rectangles and `unobstructedMapFraction: Float`. Produce `NationTurnRibbon.update(worldScreen: WorldScreen): Unit`, deriving eligible identities/order from the same permitted turn-order source as `AgentNationSelectBox`, not a parallel list.

- [ ] Write `defaultHudLeavesMapVisible`, `smallHudKeepsNextTaskReachable`, `viewSelectionDoesNotGrantControl`: `assertTrue(layout.unobstructedMapFraction >= .45f)` at 1440×900 with Activity; all essential rectangles intersect screen fully at every target size; clicking a ribbon entry never invokes takeover/handback; human view excludes unknown nation data. Test 15 long names at 1.5×.
- [ ] Run `scripts/atlas/check.sh '*AtlasWorldHudTest' '*WorldScreenHudTest' '*AgentNationViewTest'`; confirm new layout expectations fail.
- [ ] Implement compact identity/economy header, current/last/next ribbon, tracker, unit/city dock, compact minimap and notification stack using F2. Below 960 width collapse optional sections behind explicit controls and let large rosters scroll; do not shrink body text to fit. Compute uncovered map area as the union of occupied rectangles, not by adding overlapping panel areas.
- [ ] Wire existing action permission predicates/callbacks unchanged. Spectator has read-only inspection with an explicit control entry point. Unit dock reflects actual selected unit and current committed state; turn button states come from existing `NextTurnAction` logic.
- [ ] Run focused tests and `scripts/atlas/render.sh world --matrix --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/world`; verify all action states and layouts against the concept, with correct real identities and no overlap.
- [ ] Commit: `feat: rebuild world HUD around atlas hierarchy`.

## W2: Activity, messages, overview and nation control

**Files:** Modify `status/AgentActivityFeed.kt`, `AgentActivityAppearance.kt`, `AgentNationSelectBox.kt`, `AgentMessagesPanel.kt`, `AgentNationSettingsPanel.kt`, `overview/CivilizationOverviewPanel.kt`. Add `tests/src/com/unciv/ui/components/AtlasAgentPanelsTest.kt`; extend existing `AgentMessagesPanelTest.kt`, `AgentNationSettingsPanelTest.kt`, `AgentSecondarySnapshotTest.kt`.

**Interfaces:** Consume F2 controls and W1 regions. Keep panel public `show/hide/refresh` and existing session commands. Add view-only `AgentPanelLayout.calculate(width: Float, height: Float, fontScale: Float): PanelRegions` in `status/AgentPanelLayout.kt` to allocate reading area/composer/footer without recreating inputs. `PanelRegions` contains `content`, `composer`, `footer` rectangles; no authority fields.

- [ ] Write `streamingKeepsHistoricalAnchor`, `liveJumpsToNewest`, `refreshRetainsComposerAndSelection`, `viewSwitchRemovesPrivateContent`: `assertEquals(beforeAnchorId, afterAnchorId)` while reading; `assertEquals(scroll.maxY, scroll.scrollY, 1f)` after Live/layout validation; `assertEquals(draftBefore, composer.text)` and unchanged caret/recipient after snapshot; no spectator-only rows after human view switch. Ten consecutive tools must yield one collapsed group plus count/latest-call, not ten exposed rows.
- [ ] Run `scripts/atlas/check.sh '*AtlasAgentPanelsTest' '*AgentMessagesPanelTest' '*AgentNationSettingsPanelTest' '*AgentSecondarySnapshotTest'`; new layout tests should fail without implementation.
- [ ] Apply atlas layout with permanent identity/status and repeated speaker headings, clear pending/error tools, immediate Live and anchored historical reading. Keep loaded history bounded, fetching details only when expanded. Retain elapsed timing and chronological nation selector; no fabricated thinking content.
- [ ] Recompose Messages around persistent composer/footer and clear conversation/receipt display; retain image preview/paste/unsupported warnings and private visibility. Retain floating Overview sorting/details. Make viewing vs controller vs pending change explicit in Settings. Preserve panel mutual exclusion and keyboard-input guards.
- [ ] Run focused checks and all four fixture IDs (`activity`, `messages`, `overview`, `nation-control`) with `--matrix`. Exercise image-only/text-only drafts, unread/acknowledged order, long messages, 15-civ filters, manual scroll, Live, pending takeover and handback. No send or model-provider action runs outside synthetic fixtures.
- [ ] Commit: `feat: modernize agent panels while preserving live interaction state`.
