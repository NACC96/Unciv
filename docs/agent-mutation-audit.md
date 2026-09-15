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

## Remaining honest gap (legacy on human turns — safe, serialized behind the stepper)

All plan-listed families are routed. What still runs LEGACY on a human's own
turn inside an agent match (each is a UI lambda invoking the engine directly;
none can execute during agent turns because of the `canChangeState` gates):
unit action lambdas for Explore / Automate / AirSweep / ConnectRoad /
EscortFormation / Guard / Transform / SetUp and their stop-variants, unit
swap overlays, unit rename popup, city-state unit gifts / demand tribute /
pledge-revoke protection buttons (no coordinator commands exist for these
city-state relation actions yet), and MultiplayerStatus/spectator controls.
Routing these follows the identical `routeThroughAgentSession` pattern with
new small commands where missing (city-state relations). They are UI
consistency follow-ups, NOT control leaks: agent turns are fully owned by
the coordinator, and human turns are serialized behind the turn stepper.

Routing pattern for that follow-up: resolve the acting civ + current UI
selection, build the matching `AgentCommand`, execute on the desktop host
under its lock, and publish the returned snapshot (no direct engine calls,
no `UncivGame.Current` swaps). Mandatory decisions surfaced by a command must
open the agent inspector, never a modal picker, for external civs.
