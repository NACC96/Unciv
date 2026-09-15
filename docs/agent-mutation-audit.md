# Agent Mutation Audit (plan §3.2 rule: all human controls through the coordinator)

Every state-changing human control in an agent-enabled match must enter the
single-writer coordinator path — including secondary screens, popups,
shortcuts, and view `try...` methods. Ordinary (non-agent) games keep the
legacy path. Status per entry point: ROUTED (done), GATED (blocked/flagged
for agent matches, routing pending), or OPEN.

Routed/gated so far (this increment):
- WorldScreen.nextTurn + NextTurnButton/NextTurnAction.AgentMatch + keyboard
  NextTurn binding → `AgentMatchSession.onNextTurnPressed` — ROUTED.
- WorldScreen.canChangeState → session-gated — GATED (all UI checking it
  degrades to read-only during agent turns; screens that bypass the flag
  remain OPEN below).

## Map and units (WorldScreen)

| Entry point | File | Status |
| --- | --- | --- |
| Map click-to-move / attack arrows | `worldscreen/worldmap/WorldMapHolder.kt` | OPEN |
| Unit actions table (all UnitAction invocations) | `worldscreen/unit/actions/UnitActionsTable.kt`, `UnitActions*.kt` | OPEN |
| Bottom unit panel selections/orders | `worldscreen/unit/UnitTable.kt` | OPEN |
| Battle confirmations/conquest popups | `worldscreen/AlertPopup.kt`, `worldscreen/bottombar/BattleTable.kt` | OPEN |
| Next-unit cycling, automated-units move | `WorldScreen.switchToNextUnit`, `NextTurnAction.MoveAutomatedUnits` | OPEN |
| Minimap / tile info interactions | `worldscreen/worldmap/*`, `TileInfoTable.kt` | OPEN |

## City screens

| Entry point | File | Status |
| --- | --- | --- |
| Production queue add/remove/reorder/purchase | `cityscreen/CityConstructionsTable.kt`, `BuyButtonFactory.kt` | OPEN |
| Citizen work/lock/buy/focus/specialists | `cityscreen/CityScreenTileTable.kt`, `CitizenManagementTable.kt`, `SpecialistAllocationTable.kt` | OPEN |
| Rename, annex, raze toggles | `cityscreen/CityScreen.kt` | OPEN |
| View `try...` methods (lock/unlock/buy/work/stop/queue/rename/annex/raze) | `view/CityView.kt:152-206` | OPEN |

## Diplomacy and trade

| Entry point | File | Status |
| --- | --- | --- |
| Declare war/peace/friendship buttons | `diplomacyscreen/DiplomacyScreen.kt`, `MajorCivDiplomacyTable.kt` | OPEN |
| Trade staging/propose/accept/decline/counter | `diplomacyscreen/TradeTable.kt`, `OfferColumnsTable.kt`, `worldscreen/TradePopup.kt` | OPEN |
| City-state gifts/protection/tribute | `diplomacyscreen/CityStateDiplomacyTable.kt` | OPEN |
| Demands, promises, votes, religion/founding pickers | various picker screens | OPEN |

## Pickers, keyboard, misc

| Entry point | File | Status |
| --- | --- | --- |
| Tech/policy picker exits | `pickerscreens/TechPickerScreen.kt`, `PolicyPickerScreen.kt` | OPEN |
| Promotion picker, ruin/ruins-adjacent popups | pickers, `AlertPopup.kt` | OPEN |
| Keyboard shortcuts issuing orders | `components/input/KeyboardBinding.kt` + bindings | OPEN (NextTurn routed) |
| Console commands | `devconsole/*` | OPEN (dev-only; forbid in agent matches) |
| Autoplay/simulation entry | `status/AutoPlay*`, `logic/simulation/*` | OPEN (must reject agent matches) |

Routing pattern per item: resolve the acting civ + current UI selection,
build the matching `AgentCommand`, execute on the desktop host under its
lock, and publish the returned snapshot (no direct engine calls, no
`UncivGame.Current` swaps). Mandatory decisions surfaced by a command must
open the agent inspector, never a modal picker, for external civs.
