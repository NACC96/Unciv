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

## Remaining honest gap (secondary funnels)

Routed above: every PRIMARY gameplay funnel (orders, movement, attacks,
queue edits, purchases, citizens, research, policies, war/peace/friendship/
denounce, trades, demand answers). Not yet routed — still LEGACY on human
turns (serialized safely behind the turn stepper, but not coordinator-owned):
promotions and unit upgrades (promotion/upgrade menus), unit disband via
confirm popup, improvement build/pillage via unit action lambdas, sell
building (CityScreenConstructionMenu — coordinator `city.produce SELL` exists
and is tested; only the button is unwired), annex/raze toggles
(`tryAnnexCity/trySetRazing` — engine annex lives behind conquest decisions;
no standalone command), MultiplayerStatus/spectator controls. Each needs the
same one-line `routeThroughAgentSession` guard with the matching command
listed in the routing pattern below; no new coordinator capability is
required.

Routing pattern for that follow-up: resolve the acting civ + current UI
selection, build the matching `AgentCommand`, execute on the desktop host
under its lock, and publish the returned snapshot (no direct engine calls,
no `UncivGame.Current` swaps). Mandatory decisions surfaced by a command must
open the agent inspector, never a modal picker, for external civs.
