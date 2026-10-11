# UI and graphics polish implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Polish the existing pixel-art world, HUD, floating panels and setup without changing gameplay or agent behavior.

**Architecture:** Add a small scoped shared UI style, retain the WorldScreenStyle facade, and refine three disjoint groups of Scene2D widgets. Preserve existing information authority, input callbacks and renderer layers. Build and validate in an isolated worktree; keep real game data on Lexar and the live app untouched.

**Tech Stack:** Kotlin, LibGDX/Scene2D, existing game icons/skins, JUnit/Mockito, LWJGL GPU capture harnesses, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-11-ui-graphics-polish-design.md`

## Global constraints

- No gameplay, prompt/tool, provider, save-schema, checkpoint, preset-content or bonus-delivery changes.
- Preserve human/spectator visibility, private mail and all existing activity filtering/grouping/scroll semantics.
- No global skin mutation, dependencies, replacement art, shaders or continuous animation.
- No native app replacement, provider control or real userdata writes during implementation.
- No automatic hiding of requested map information; existing layer toggles remain authoritative.
- Keep Messages/Overview exclusive and Activity independent; retain input outside floating panels.

## Review focus

1. Narrow landscape setup and long names/bonuses: every visible action and field stays reachable.
2. Live activity growth while reading: the reader's anchor remains fixed until Live is selected.
3. Human versus spectator panels/city labels: visual refinements never expose private information.
4. Disabled/loading/start controls: the visible action uses the original validation state and cannot be activated when disabled.
5. Map zoom, rebind and wrapping: styling never changes camera geometry, culling or city interaction.

## Task 1: Shared style, HUD and floating panels

**Owner:** fresh HUD/panels implementation agent.

**Files:** Create `core/src/com/unciv/ui/components/GameUiStyle.kt`; modify `core/src/com/unciv/ui/screens/worldscreen/WorldScreenStyle.kt`, `topbar/WorldScreenTopBar*.kt`, `status/AgentMatchControlTable.kt`, `status/AgentActivityFeed.kt`, `status/AgentActivityAppearance.kt`, `status/AgentMessagesPanel.kt`, `overview/CivilizationOverviewPanel.kt`; tests `WorldScreenHudTest.kt` and `AgentMessagesPanelTest.kt`.

**Interfaces:** `GameUiStyle` supplies existing named colors (`surface`, `raised`, `hover`, `pressed`, `ink`, `muted`, `accent`, `warning`, `error`), `background(path: String, color: Color = surface): Drawable`, `panelBackground(path: String, opacity: Float = 0.94f): Drawable`, `button(text: String, primary: Boolean = false): TextButton`, `apply(button: Button, primary: Boolean = false)` and `setEnabled(button: Button, enabled: Boolean)`. `WorldScreenStyle` delegates its existing API and adds `panelBackground`; tasks 2/3 consume those interfaces, not private implementation details. Panel backgrounds use existing drawables; no framebuffer capture or GL resources.

- [x] Implement the shared facade and refine typography, spacing and action states while preserving button/input behavior.
- [x] Remove blur use from Messages/Overview and use the shared translucent rimmed surface for all floating panels.
- [x] Refine feed entry hierarchy and existing receipt/comparison presentation without changing filtering, grouping, identity or scroll logic.
- [x] Extend meaningful existing widget bounds/state tests only where behavior/layout changes; preserve all privacy and scroll regression cases.
- [x] Report exact owned files and inspection points; root runs combined Gradle/tests/GPU checks after all workers freeze.

## Task 2: City and minimap graphics

**Owner:** fresh map-graphics implementation agent.

**Files:** `core/src/com/unciv/ui/components/tilegroups/citybutton/{CityTable,DefenceTable,AirUnitTable,StatusTable}.kt`, `core/src/com/unciv/ui/screens/worldscreen/minimap/{MinimapHolder,Minimap,MapOverlayToggleButton}.kt`; own focused city/widget tests if needed. The map worker owns the focused MapPolishRenderCheck fixture; root orchestrates GPU execution and owns shared snapshot regression verification.

**Interfaces:** consume stable `WorldScreenStyle` colors/background/buttons; no shared-style edits. Existing tile/city view visibility and callbacks remain authoritative. Existing layer flags and minimap world-wrap math remain authoritative.

- [x] Replace saturated heavy city backgrounds/borders with calm plaques and restrained ownership accents, keeping population/name/defence/aircraft roles distinct.
- [x] Harmonize minimap frame, controls and camera outline; improve existing layer affordances/tooltips without adding hidden-data policies.
- [x] Verify foreign/human/spectator city labels, click targets, long names and culling-sensitive geometry using existing fixtures.
- [x] Report exact owned files and screenshot fixture requirements; do not edit renderer architecture or assets.

## Task 3: Responsive setup and nation editors

**Owner:** fresh setup implementation agent.

**Files:** `core/src/com/unciv/ui/screens/newgamescreen/{NewGameScreen,PlayerPickerTable,AgentProfilePopup,NationSettingsPopup,SetupPresetsPopup,StartingBonusForm}.kt`; `tests/src/com/unciv/ui/components/AgentProfilePopupTest.kt` and a focused setup layout test if necessary.

**Interfaces:** consume `GameUiStyle` public API from Task 1. Keep settings/model/provider/bonuses/preset callbacks and schemas unchanged. Use the existing PickerPane `rightSideButton` as the visible Start action so validation/working state controls that same widget.

- [x] Make stacked-versus-columns layout depend on usable width as well as aspect ratio; keep the existing option-table behavior.
- [x] Put nation identity and actions on separate rows and constrain wrapping metadata/bonus summaries.
- [x] Style existing popups and form fields with copied widget styles; retain fixed Save/Cancel footers and all save/cancel callbacks.
- [x] Make Start/Presets/Reset and narrow bonus Add controls fit their available bounds.
- [x] Extend bounds/disabled-state tests at 800×500 and 520×360 with long labels/many bonuses; retain full model/bonus/preset round-trip coverage.

## Task 4: Integration and visual acceptance

**Owner:** root verification, with a fresh independent reviewer after source freeze.

**Files:** existing GPU harnesses under `tests/src/com/unciv/dev/`; add one synthetic combined polish capture harness if required. Update selected UI docs and this plan's checklist. Synthetic capture/log output belongs in the plan's ignored workspace on Lexar; no user saves/providers.

- [x] After all workers freeze, run isolated `./gradlew check` and write the test runtime classpath. Build logs must identify the source commit/tree.
- [x] Run real GPU panel/map/setup captures at representative wide/narrow sizes; inspect one batched round and fix observed defects together, with one confirming round.
- [x] Verify long names/bonuses, actual Start/Save controls, receipt/privacy states, latest Live following, manual hold, city visibility, map input and stable zoom/wrap layout.
- [ ] Independently review each worker's changed files and then the complete branch; route material fixes to implementers and record verdicts.
- [ ] Commit only owned source/tests/docs. Package a separate native build without replacing the running app. Report local artifact, verified checks and pending human/native acceptance; do not merge a new PR without authorization.

## Execution ledger

Use the plan-specific ignored SDD workspace for worker reports, ownership, review results and any decisions. Root serializes Gradle/build and commit operations; workers do not run competing builds, make commits or touch one another's files.

## Verification evidence

- Full isolated Gradle `check`: 1,254 tests, zero failures/errors, 15 skipped.
- Real GPU: Messages/Activity and Overview at 960×720 and 420×600; city/minimap human/spectator at both sizes, wrap at 960×720, bounded tooltip at 420×600; setup and editor/menu/footer states at 800×500 and 520×360. All capture processes exit 0.
- Independent task reviews are clean after dropdown/tooltip bounds and synthetic harness corrections. Whole-branch review and separate package pending.
- Installed app JAR hash and primary checkout verified unchanged. Native interaction acceptance of this polish remains pending; no real game/provider was launched for these tests.
