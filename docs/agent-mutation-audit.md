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

## Remaining honest gap (UI routing for human civs, W6/W7 polish)

Human civs inside an agent match keep the LEGACY engine path for their own
turns (ordinary pickers wired to legacy handlers). The plan (§11.2) accepts
this for human turns ("Human turns retain ordinary pickers"); full
human-through-coordinator routing (hotseat parity) is specified in the audit
routing pattern below and remains the only open follow-up — it is a UI
consistency improvement, not a control leak: the coordinator owns all
external-agent turns, and human turns are serialized behind it by the turn
stepper.

Routing pattern for that follow-up: resolve the acting civ + current UI
selection, build the matching `AgentCommand`, execute on the desktop host
under its lock, and publish the returned snapshot (no direct engine calls,
no `UncivGame.Current` swaps). Mandatory decisions surfaced by a command must
open the agent inspector, never a modal picker, for external civs.
