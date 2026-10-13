# Atlas management and supporting screens implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry the atlas design through city management, choices, diplomacy, setup and every remaining first-party screen family.

**Architecture:** Apply F2 shared components and deliberately recompose existing screens around their current view/command interfaces. Preserve selection and permission state across snapshots; retain existing game mechanics rather than adding Civ VI systems. Use the F1 inventory to prevent forgotten dialogs and detached tabs.

**Tech Stack:** Kotlin Scene2D, existing GameView/AgentSnapshotScreen, JUnit/Mockito and real-screen GPU fixtures.

**Spec:** [Approved design](../specs/2026-10-12-civ6-atlas-ui-design.md), [master plan](2026-10-12-civ6-atlas-implementation.md).

## Global Constraints

- Master constraints apply; no gameplay, diplomacy, provider or save-schema redesign.
- All screens use reachable primary actions and fixed footers/scrolling where appropriate at the five sizes and 1.0×/1.5× fonts.
- Normal text ≥4.5:1 and essential graphics ≥3:1 on composited surfaces; no blur.
- Keep native/mod glyph fallback and existing user appearance choices; no destructive setting reset.
- No leftover unreviewed screen family or hidden-tab legacy controls at completion.

## Review Focus

1. City snapshot adoption while editing the production queue must preserve stable selection without acting on a different city (M1).
2. Locked research/policy/belief choices must remain locked after layout/style changes (M2).
3. A proposed peace/trade cannot be styled as accepted before recipient consent (M3).
4. Setup refresh/model catalog changes must not erase saved bonuses, personalities, map or reasoning (M4).
5. Narrow localized dialogs and detached overview/tool tabs must remain scrollable and keyboard accessible (M1–M4).

---

## File ownership and interfaces

Paths below are rooted at `core/src/com/unciv/ui/screens/` unless explicitly written otherwise. F2 owns common styles/widgets. Management tasks consume `AtlasWidgets` and current screen callbacks; they do not create duplicate command DTOs.

Each task updates `docs/atlas/screen-coverage.json` with source entry points, fixture variants, interaction trace and capture paths. New screen-specific adapters live beside the screen they describe, not in a global mega-presenter. All new label text uses existing translation mechanisms; add source keys through the repository's normal translation-key workflow, and preserve symbols.

M4 contains independent family deliveries: M4a menu/loading, M4b setup/presets, M4c saves/options/language, M4d Civilopedia/victory, M4e multiplayer/mods, M4f map editor/developer tools/remaining popups. Execute its test–implementation–capture–commit steps separately for each family and review that family before advancing; do not send the whole list to one implementer as an unchecked bulk restyle. The task completes only when all six family records are accepted.

## Task 1: M1: City management and production

**Files:** Modify `cityscreen/CityScreen.kt`, `CityConstructionsTable.kt`, `CityStatsTable.kt`, `CityScreenCityPickerTable.kt`, `CityScreenTileTable.kt`, `ConstructionInfoTable.kt`, `CitizenManagementTable.kt`, `SpecialistAllocationTable.kt`, `BuyButtonFactory.kt`, `CityReligionInfoTable.kt`, `DetailedStatsPopup.kt`, `GreatPersonPointsBreakdownPopup.kt`. Create `cityscreen/CityScreenLayout.kt`, `tests/src/com/unciv/ui/components/AtlasCityScreenTest.kt`; extend `AgentSecondarySnapshotTest.kt` and city fixture.

**Interfaces:** `CityScreenLayout.calculate(width: Float, height: Float, fontScale: Float): CityRegions` returns header/map/queue/catalog/details/footer rectangles. Existing CityView and production/buy/citizen command routes remain authoritative. `CityScreen` retains stable city/construction IDs, filters, scroll anchors and selected tile across refreshes; adapters expose display values only.

- [ ] Write `cityRefreshPreservesSelectedConstructionAndQueueAnchor`, `spectatorCannotPurchaseFromStyledCard`, `smallCityLayoutKeepsQueueAndBackReachable`: `assertEquals(oldConstructionKey, screen.selectionKey)` after a synthetic snapshot; observer purchase callback count remains zero; required rectangles fully inside viewport at 420×600/1.5×. Include city capture/destruction during refresh: selection clears, no command targets an obsolete city.
- [ ] Run `scripts/atlas/check.sh '*AtlasCityScreenTest' '*AgentSecondarySnapshotTest'`; expect missing layout/new assertions to fail.
- [ ] Recompose the city header with population/growth/yields, production and city switcher. Provide searchable/filterable construction cards showing cost, turns and requirements; preserve exact queue reorder/remove, purchase and cancellation semantics. Use grouped citizen/specialist/building/tile sections and a stable details area. On narrow screens use tabs for map/catalog/details, keeping primary actions and Back reachable.
- [ ] Keep draft selections outside rebuilt actor subtrees. Resolve stable IDs against the new CityView before enabling actions; show inspection state explicitly. Migrate associated city popups using F2 without changing calculations.
- [ ] Run focused checks and `scripts/atlas/render.sh city --matrix --output /Volumes/Lexar/Unciv/.superpowers/civ6-atlas/city`; exercise queue add/remove/reorder, buy insufficient/sufficient funds, citizen locks, specialists, tile purchase, city switch and agent snapshot refresh. Expected commands/results match the pre-change fixture trace.
- [ ] Commit `feat: redesign city management and production controls`.

## Task 2: M2: Technology, policies, religion, promotions and choice screens

**Files:** Modify `pickerscreens/PickerPane.kt`, `PickerScreen.kt`, `TechPickerScreen.kt`, `TechButton.kt`, `PolicyPickerScreen.kt`, `PromotionPickerScreen.kt`, `PromotionTree.kt`, `PromotionButton.kt`, `PromotionScreenColors.kt`, `ReligionPickerScreenCommon.kt`, `ReligiousBeliefsPickerScreen.kt`, `PantheonPickerScreen.kt`, `GreatPersonPickerScreen.kt`, `ImprovementPickerScreen.kt`, `DiplomaticVotePickerScreen.kt`, `DiplomaticVoteResultScreen.kt`, `CityRenamePopup.kt`, `UnitRenamePopup.kt`. Create `tests/src/com/unciv/ui/components/AtlasChoiceScreensTest.kt`; expand corresponding fixture IDs.

**Interfaces:** Consume existing prerequisites, costs and callbacks. Add reusable `AtlasChoiceState { LOCKED, AVAILABLE, CURRENT, COMPLETED }` and `AtlasChoiceCard(title: String, detail: String, state: AtlasChoiceState, onSelect: () -> Unit): Table` in `ui/components/atlas/AtlasChoiceCard.kt`. State comes from rules/view, not UI inference. Selection alone does not commit a choice.

- [ ] Write `choiceStatesPreserveEligibilityAndCosts`, `treeReflowKeepsFocusAndSelection`, `longBeliefTextKeepsConfirmReachable`: all four states render distinct icon/text/border combinations; locked card never commits; current/completed status matches fixture rules; selected node/focus stays after resize; confirm/back accessible at 420×600/1.5×. Verify prereq lines and labels are still visible after pan/zoom.
- [ ] Run `scripts/atlas/check.sh '*AtlasChoiceScreensTest' '*AgentHumanTurnChoicesTest'`; new card/layout expectations fail first.
- [ ] Implement shared cards and choice footer. Rework research/promotion node framing and prerequisite connectors; retain tree camera context and stable details. Policies/beliefs use grouped readable cards with accurate unlock/cost/conflict reasons. Great-person/improvement/vote/rename flows share consistent headers, fields and action hierarchy. Do not introduce civic trees or change gameplay progression.
- [ ] Run tests and `research`, `policies`, `religion`, `promotions` fixtures with `--matrix`. Include all locked/available/current/completed states, scarce resources, long translated strings and alternative rulesets. Add supporting variants for votes/improvements/great people/rename and inspect their actual actors.
- [ ] Commit `feat: unify research and choice screens with atlas cards`.

## Task 3: M3: Diplomacy, trade and empire information

**Files:** Modify `diplomacyscreen/DiplomacyScreen.kt`, `LeaderIntroTable.kt`, `MajorCivDiplomacyTable.kt`, `CityStateDiplomacyTable.kt`, `TradeTable.kt`, `OfferColumnsTable.kt`, `OffersListScroll.kt`, `worldscreen/TradePopup.kt`, `worldscreen/AlertPopup.kt`. Migrate `overviewscreen/EmpireOverviewScreen.kt`, `EmpireOverviewTab.kt`, `CityOverviewTab.kt`, `UnitOverviewTab.kt`, `ResourcesOverviewTab.kt`, `TradesOverviewTab.kt`, `StatsOverviewTab.kt`, `ReligionOverviewTab.kt`, `WonderOverviewTab.kt`, `GlobalPoliticsOverviewTable.kt`, `GlobalPoliticsDiagramGroup.kt`, `NotificationsOverviewTable.kt`, `EspionageOverviewScreen.kt`, `UnitSupplyTable.kt` and their column/helpers. Create `tests/src/com/unciv/ui/components/AtlasDiplomacyTest.kt`, `AtlasEmpireOverviewTest.kt`.

**Interfaces:** Keep existing diplomacy/trade legality and pending-offer models. Display state `ProposalDisplayState { DRAFT, PENDING, ACCEPTED, REJECTED, EXPIRED }` is derived from existing authority, not persisted separately. `ProposalStatusView.render(state: ProposalDisplayState, turn: Int?, detail: String): Table` in `diplomacyscreen/ProposalStatusView.kt` displays text/icon status with no actions of its own.

- [ ] Write `peaceRemainsPendingUntilRecipientAccepts`, `proposalTermsRemainExactAfterRelayout`, `unmetNationDataStaysHidden`, `detachedEmpireTabsUseAtlasControls`: proposer submits and war remains active, UI says Pending; recipient action alone changes it to Accepted; quantity/duration/gold unchanged by style/layout; unknown/forbidden details absent in human view; each overview tab styled when first opened, not just the initial one.
- [ ] Run `scripts/atlas/check.sh '*AtlasDiplomacyTest' '*AtlasEmpireOverviewTest' '*AgentTradeObservationTest' '*AgentCityStatePeaceTest'`; verify new presentation tests fail before implementation.
- [ ] Build nation identity/relationship summary, readable terms grouped by category and clearly separated sides, proposal state and action footer. Keep existing accept/reject/withdraw/war callbacks, input validation, quantity limits and human/agent consent. Style alerts and city-state options without broadening permissions.
- [ ] Migrate empire tabs into consistent searchable/sortable tables, stat cells and chart panels; use scroll/tabs at narrow sizes, retaining selection/filters and their existing privacy. Preserve existing chart math and notifications, rather than recomputing totals in widgets.
- [ ] Run focused tests and diplomacy/supporting fixture matrices with pending/accepted/expired offers, 15 nations, long terms, resources, charts and espionage. Inspect stats against source fixture values and verify all tab/tooltip text is readable.
- [ ] Commit `feat: modernize diplomacy trade and empire overview screens`.

## Task 4: M4: Main menu, setup, saves, options and remaining tools

**Files:** Modify `mainmenuscreen/MainMenuScreen.kt`, `GameStartScreen.kt`, `LoadingScreen.kt`, `LanguagePickerScreen.kt`; `newgamescreen/NewGameScreen.kt`, `GameOptionsTable.kt`, `MapOptionsTable.kt`, `MapParametersTable.kt`, `MapFileSelectTable.kt`, `PlayerPickerTable.kt`, `NationTable.kt`, `NationPickerPopup.kt`, `NationSettingsPopup.kt`, `AgentProfilePopup.kt`, `StartingBonusForm.kt`, `SetupPresetsPopup.kt`, `ScenarioSelectTable.kt`, `AcceptModErrorsPopup.kt`, `FriendTable.kt`, `ModCheckboxTable.kt`, `MultiCheckboxTable.kt`; `savescreens/LoadOrSaveScreen.kt`, `LoadGameScreen.kt`, `SaveGameScreen.kt`, `VerticalFileListScrollPane.kt`; `ui/popups/options/OptionsPopup.kt` and its registered tab factories; `civilopediascreen/CivilopediaScreen.kt`, `CivilopediaSearchPopup.kt`, `MarkupRenderer.kt`; `victoryscreen/VictoryScreen.kt` and its charts/rankings/demographics/replay/illustration tables; `multiplayerscreens/MultiplayerScreen.kt`, `GameList.kt`, `AddMultiplayerGameScreen.kt`, `AddFriendScreen.kt`, `EditFriendScreen.kt`, `ViewFriendsListScreen.kt`, `FriendPickerList.kt`; `modmanager/ModManagementScreen.kt`, `ModInfoAndActionPane.kt`, `ModDecoratedButton.kt`; `mapeditorscreen/MapEditorScreen.kt`, `MapEditorMainTabs.kt`, `MapEditorToolsDrawer.kt` and registered tab factories; `devconsole/DevConsolePopup.kt`, `UniqueBuilderScreen.kt`, `worldscreen/PlayerReadyScreen.kt`, `worldscreen/mainmenu/WorldScreenMenuPopup.kt`, `WorldScreenMusicPopup.kt`, and shared popup/widgets identified by the F1 inventory. Create `tests/src/com/unciv/ui/components/AtlasSupportingScreensTest.kt`; extend setup layout/lifecycle tests.

**Interfaces:** Consume F2 controls and existing navigation/save/preset/mod/multiplayer APIs. New `Use Atlas appearance` option updates only appearance fields after required assets pass coverage; preserve all other GameSettings fields. Main menu remains usable offline; synthetic fixtures stub network/mod queries and never start agents.

- [ ] Write `presetRoundTripKeepsAllGameplayAndAgentFields`, `mapRefreshRetainsCustomEarthAndRoster`, `appearanceOptInPreservesOtherSettings`, `everyRegisteredSupportingSurfaceHasReachablePrimaryAction`: compare preset nations/model/reasoning/personality/bonuses/map/game settings before/after; selected Earth map and roster survive preview refresh; appearance update does not change language, resolution, saves, providers; save/cancel/back fields are reachable at all sizes. Cover hidden option/editor tabs and open-dropdown reflow.
- [ ] Run `scripts/atlas/check.sh '*AtlasSupportingScreensTest' '*SetupLayoutTest' '*SetupStyleLifecycleTest' '*AgentProfilePopupTest'`; expect new theme/reflow checks to fail.
- [ ] Recompose menu/launcher/loading and setup using clear navigation, map preview, roster cards and a fixed start/preset/footer region. Keep validation errors beside relevant fields; preserve setup state across theme changes. Keep native window resize/resolution settings intact.
- [ ] Migrate save/load, options/language, Civilopedia, victory, multiplayer/friends, mods, map editor and developer tool chrome. Use shared widgets for rare tools, but explicitly open every registered tab/dialog from the inventory and correct bespoke colors/layouts. Existing destructive confirmations, privacy covers and shortcuts remain; focused text entry blocks unrelated shortcuts.
- [ ] Run tests plus `menu`, `setup` and `supporting` fixture matrices. Use the coverage inventory to enumerate every registered variant; no “covered by skin” entry is accepted without an actual open/render trace. Test legacy/mod appearances, original presets and Earth previews in isolated test data.
- [ ] Commit per independently verified family, then `feat: complete atlas setup navigation and supporting screens`. Task remains open while any inventory family lacks a capture/interaction record.
