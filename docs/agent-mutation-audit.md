# Agent Mutation Audit (plan §3.2 rule: all human controls through the coordinator)

Every state-changing human control in an agent-enabled match must enter the
single-writer coordinator path — or be hard-gated while an external agent
holds the turn. Ordinary (non-agent) games keep the legacy path unchanged.
Status per entry point: **ROUTED** (goes through the coordinator), **GATED**
(engine session gate blocks it during agent turns; the same control still
works on human turns), or noted read-only.

Central gate: `WorldScreen.canChangeState` →
`agentSession.isHumanInputAllowed()` when a session exists (WorldScreen.kt).
`GUI.isAllowedChangeState()` delegates to it, so every `!GUI.isAllowedChangeState()`
check inherits the gate for free.

## Routed (coordinator-owned)

| Entry point | File | Mechanism |
| --- | --- | --- |
| Next turn / round advance | `WorldScreen.nextTurn`, `NextTurnButton`, `NextTurnAction.AgentMatch`, keyboard binding | `AgentMatchSession.onNextTurnPressed` consumes or routes; legacy clone path unreachable with a session |
| Unit orders: fortify, fortify-until-healed, sleep, sleep-healed, paradrop | `UnitActionsTable.activateAction` | `routeThroughAgentSession` → `UnitOrderCommand`; legacy lambda only without a session |
| Unit movement (right-click, move-here button, single-tap, multi-unit) | `WorldMapHolder.moveUnitToTargetTile`, single-tap branch | `MoveUnitCommand` per unit, batch factories with fresh revisions |
| Unit attacks incl. nukes (right-click path) | `WorldMapHolder.onTileRightClicked` | `AttackCommand` |
| City production queue add/remove/raise/lower | `CityView.tryAddToQueue/tryRemoveFromQueue/tryRaisePriority/tryLowerPriority` | `CityProductionCommand` (ADD_BOTTOM/REMOVE/RAISE/LOWER) |
| City purchase (gold) | `BuyButtonFactory.purchaseConstruction` | `CityProductionCommand` PURCHASE |
| Citizen work/stop/lock/unlock, tile purchase, rename | `CityView.tryWorkTile/tryStopWorkingTile/tryLockTile/tryUnlockTile/tryBuyTile/tryRenameCity` | `CityCitizensCommand`, `BuyTileCommand`, `RenameCityCommand` |
| Research selection (pick/queue/free tech) | `TechPickerScreen.tryExit` | `SelectResearchCommand` |
| Policy adoption (branch + member) | `PolicyPickerScreen` (branch onClick, confirmAction) | `AdoptPolicyCommand` |
| Declare war | `DiplomacyScreen.getDeclareWarButton` | `DeclareWarCommand` |
| Denounce | `MajorCivDiplomacyTable.getDenounceButton` | `DenounceCommand` |
| Declaration of friendship | `MajorCivDiplomacyTable.getDeclareFriendshipButton` | `FriendshipCommand` |
| Incoming trade accept/decline | `TradePopup` buttons | `RespondTradeCommand` |
| Demand answers (agree/refuse) | `AlertPopup.addDemand` buttons | `RespondDemandCommand` |
| Human-command execution core | `logic/agents/HumanCommandExecutor.kt` | single coordinator entry: revision stamping per command, HUMAN-controller verification, batch all-or-nothing — headless-tested |
| Promotion adoption (whole path) | `PromotionPickerScreen.acceptPromotion` | one `PromoteUnitCommand` per path step |
| Unit upgrades (button + menu + upgrade-all) | `UnitActionsTable.activateAction`, `UnitUpgradeMenu.doUpgrade/doAllUpgrade` | `UpgradeUnitCommand` (coordinator revalidates gold/movement/requirements) |
| Unit disband (confirm popup) | `UnitActions.addDisbandAction` | `UnitOrderCommand` DISBAND; popup cancel routes nothing |
| Pillage (confirm popup) | `UnitActionsPillage.getPillageActions` | `PillageCommand`; popup cancel routes nothing |
| Worker improvement build + cancel-order + second queued improvement | `ImprovementPickerScreen.accept` | `BuildImprovementCommand` (x2) / `StopImprovementCommand` |
| Repair pillaged tile | `UnitActionsFromUniques.getRepairActions` lambda | `RepairCommand` (null-safe worldScreen lookup: automation invokes this lambda headlessly) |
| City sell (confirm popup) | `CityView.trySellBuilding` | `CityProductionCommand` SELL; popup cancel routes nothing |
| Annex puppet city | `CityView.tryAnnexCity` | `AnnexCityCommand` |
| Raze / stop razing | `CityView.trySetRazing` | `SetRazingCommand` |
| Queue move-to-top | `CityView.tryMoveEntryToTop` | `CityProductionCommand` MOVE_TOP |
| City-state gold gifts (250/500/1000) | `CityStateDiplomacyTable.getGoldGiftTable` | `GiftGoldToCityStateCommand` |

## Gated (blocked while an agent turn is live; normal on human turns)

| Entry point | File | Gate |
| --- | --- | --- |
| Map click-to-move / attack arrows | `worldmap/WorldMapHolder.kt` | `canChangeState` |
| Unit actions table (all UnitAction invocations) | `unit/actions/UnitActionsTable.kt`, `UnitActions*.kt` | `canChangeState` |
| Bottom unit panel selections/orders | `unit/UnitTable.kt` | `canChangeState` |
| Battle confirmations/conquest popups | `AlertPopup.kt`, `bottombar/BattleTable.kt` | `BattleTable` checks `canChangeState`; AlertPopup opens only inside the popup block (see below) |
| Next-unit cycling, automated-units move | `WorldScreen.switchToNextUnit`, `NextTurnAction.MoveAutomatedUnits` | `canChangeState` / gated actions |
| Popup block: AlertPopup (demands, promises, conquest), TradePopup accept, free-Great-Person picker, DiplomaticVoteResultScreen | `WorldScreen.kt` update loop | popup block now requires `isPlayersTurn && canChangeState` — closes the human-answers-agent's-decision leak |
| Production queue add/remove/reorder/purchase | `cityscreen/CityConstructionsTable.kt`, `BuyButtonFactory.kt` | `canChangeState` |
| Citizen work/lock/buy/focus/specialists | `cityscreen/CityScreenTileTable.kt`, `CitizenManagementTable.kt`, `SpecialistAllocationTable.kt` | `canChangeState` |
| Rename, annex, raze toggles | `cityscreen/CityScreen.kt` | `canChangeState` |
| View `try...` methods | `view/CityView.kt` | view-model layer only; state mutations reachable through it come from the gated screens above |
| Declare war/peace/friendship/denounce/demands buttons | `DiplomacyScreen.kt`, `MajorCivDiplomacyTable.kt` | `isNotPlayersTurn()` = `!GUI.isAllowedChangeState()` |
| Trade staging/propose/accept/decline/counter | `TradeTable.kt`, `OfferColumnsTable.kt`, `TradePopup.kt` (world screen) | tables inherit the DiplomacyScreen gate; the standalone TradePopup is closed by the popup-block gate |
| City-state gifts/protection/tribute | `CityStateDiplomacyTable.kt` | `isNotPlayersTurn()` |
| Tech picker | `TechPickerScreen.kt` | `!GUI.isAllowedChangeState()` disables the pick button |
| Policy picker, promotion picker | `PolicyPickerScreen.kt`, `PromotionPickerScreen.kt` | `canChangeState` predicate on pickability |
| AutoPlay menu / direct autoplay / hotkey | `status/AutoPlayStatusButton.kt` | menu + `startMultiturnAutoPlay` now require `canChangeState` — autoplay can never drive an agent civ |
| Debug cheat buttons (unlock techs, resources), god-mode toggle, simulation controls | `popups/options/DebugTab.kt` | hidden while a session exists |

## Forbidden outright in agent matches

| Entry point | File | Mechanism |
| --- | --- | --- |
| Developer console | `WorldScreen.openDeveloperConsole` | returns immediately when `agentSession != null` (engine-level mutation bypasses the coordinator) |
| Simulation (`Simulate until turn`) | `logic/simulation`, `DebugTab` | controls not added when a session exists; the button itself routes through the gated NextTurn path |

## Read-only by construction

| Entry point | File | Note |
| --- | --- | --- |
| Minimap, tile info, overlay toggles | `worldmap/*`, `TileInfoTable.kt` | display only; no state writes |
| Save/load, options, civilopedia | various | out of match scope |

## Human Mutation Routing Closure (CP-033)

All applicable human mutation routes in the Vanilla scope have now been routed
through the authoritative `MatchCoordinator` via `WorldScreen.routeThroughAgentSession`,
publishing fresh snapshots to the GL thread upon completion. In ordinary (non-agent)
matches, each interceptor safely yields false and executes legacy callbacks untouched.

### 1. Unit Orders & Actions (`UnitActionsTable.kt`)
- **Unit orders**: `EXPLORE`, `STOP_EXPLORATION`, `AUTOMATE`, `STOP_AUTOMATION`,
  `GUARD`, `SET_UP`, `AIR_SWEEP`, `ESCORT_FORMATION`, and `STOP_ESCORT_FORMATION`
  are routed via `UnitOrderCommand`.
- **Unit movement cancellation**: `UnitActionType.StopMovement` maps to `UnitOrder.WAKE`,
  clearing movement and wake-up state through `UnitOrderCommand`.
- **Unit gifts**: `UnitActionType.GiftUnit` is routed via `GiftUnitCommand`. Validated
  under Vanilla rules: recipient territory required; city-states accept military units
  (or units permitted by `GainInfluenceWithUnitGiftToCityState` uniques) and award
  influence; friendly major civs accept units in their territory.

### 2. Map & Unit Operations
- **Connect Road**: `OverlayButtonData.connectRoadToTargetTile` is routed via
  `ConnectRoadCommand`, assigning automated road connection pathing through the coordinator.
- **Unit Swap**: `WorldMapHolder.swapMoveUnitToTargetTile` is routed via `SwapUnitsCommand`,
  atomically validating movement points and swapping positions of two own military/civilian units.
- **Unit Rename**: `UnitRenamePopup` routes renaming via `RenameUnitCommand`.

### 3. City-State Diplomacy (`CityStateDiplomacyTable.kt`)
- **Tribute**: Gold and worker tribute demands are routed via `TributeCommand`.
  Enforces military force ratio and CS willingness gates before transferring assets
  and applying the -15 CS influence hit.
- **Protection**: Pledging and revoking protection are routed via `ProtectionCommand`.
  Enforces 10-turn pledge cooldown (`RecentlyPledgedProtection`) and 20-turn revoke
  cooldown (`RecentlyWithdrewProtection`) along with non-negative influence rules.
- **Diplomatic Marriage**: Austria-specific diplomatic marriage is routed via
  `DiplomaticMarriageCommand`, validating gold costs and alliance duration.

### 4. Non-Gameplay & Presentation-Only Distinction
- **`MultiplayerStatusButton`**: Presentation-only external match viewer.
  It opens `MultiplayerStatusPopup` which only loads file previews of remote
  multiplayer matches (`files.loadMultiplayerGamePreview(...)`). It executes no
  in-match mutations on the current game or civilization state.
- **Spectator Mode (`CivView.isSpectator()`)**: Presentation-only observation mode.
  When viewing as a spectator, `canChangeState` returns `false`, and all mutation
  action buttons are hidden. Spectators cannot issue commands.
- **`UnitActionType.Transform`**: Mod-only unique (`UniqueType.CanTransform`).
  Excluded from Vanilla Civ 5 scope as no transformable units exist in the Vanilla ruleset.
