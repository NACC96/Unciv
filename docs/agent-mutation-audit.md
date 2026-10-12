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
| Unit orders: fortify, fortify-until-healed, sleep, sleep-healed, paradrop, explore/stop, automate/stop, guard, set-up, air-sweep, escort/stop | `UnitActionsTable.activateAction` | `routeThroughAgentSession` → `UnitOrderCommand`; legacy lambda only without a session |
| Movement stop (button) | `UnitActionsTable.activateAction` | `UnitActionType.StopMovement` → `UnitOrder.WAKE` |
| Unit movement (right-click, move-here button, single-tap, multi-unit) | `WorldMapHolder.moveUnitToTargetTile`, single-tap branch | `MoveUnitCommand` per unit, batch factories with fresh revisions |
| Unit attacks incl. nukes (right-click path) | `WorldMapHolder.onTileRightClicked` | `AttackCommand` |
| City production queue add/remove/reorder, construction context menu and target-tile addition | `CityView` queue helpers | `CityProductionCommand` (ADD_TOP/ADD_BOTTOM/REMOVE/REMOVE_ALL/RAISE/LOWER/MOVE_TOP/MOVE_END), stable city ID and target tile |
| Construction disable/enable in one or all cities | `CityView.tryDisableConstruction/tryEnableConstruction`, `CivView.tryDisableCivConstruction/tryEnableCivConstruction` | `CityConstructionSettingsCommand`; civ-wide command includes the persistent new-city setting |
| City purchase (gold) | `BuyButtonFactory.purchaseConstruction` | `CityProductionCommand` PURCHASE |
| Citizen work/stop/lock/unlock, tile purchase, rename | `CityView.tryWorkTile/tryStopWorkingTile/tryLockTile/tryUnlockTile/tryBuyTile/tryRenameCity` | `CityCitizensCommand`, `BuyTileCommand`, `RenameCityCommand` |
| Citizen focus, avoid-growth, manual-specialist/assignment and Reset Citizens | `CityView` citizen helpers | `CityCitizensCommand`; Reset Citizens preserves `resetLocked=true` |
| Found City after the existing promise confirmation | `UnitActionsFromUniques.getFoundCityAction` | `FoundCityCommand` in the confirmed callback; cancelling routes nothing |
| Research selection (pick/queue/free tech) | `TechPickerScreen.tryExit` | Copies the complete staged queue; first `SelectResearchCommand` replaces, later entries append; free tech remains one explicit choice |
| Policy adoption (branch + member) | `PolicyPickerScreen` (branch onClick, confirmAction) | `AdoptPolicyCommand` |
| Declare war | `DiplomacyScreen.getDeclareWarButton` | `DeclareWarCommand` |
| Denounce | `MajorCivDiplomacyTable.getDenounceButton` | `DenounceCommand` |
| Declaration of friendship | `MajorCivDiplomacyTable.getDeclareFriendshipButton` | `FriendshipCommand` |
| Incoming trade accept/decline | `TradePopup` buttons | `RespondTradeCommand` |
| Demand answers (agree/refuse) | `AlertPopup.addDemand` buttons | `RespondDemandCommand` |
| Human-command execution core | `logic/agents/HumanCommandExecutor.kt` | revision stamping per command and HUMAN-controller verification; each command commits separately, first failure stops later commands |
| Saved unit promotion defaults and city preference | `PromotionPickerScreen`, `CityView.trySetUnitShouldUseSavedPromotion` | `SaveUnitPromotionDefaultsCommand` copies candidate unit promotions; `SetCityUnitSavedPromotionCommand` updates the existing preference |
| City-state resource improvement gift | `CityStateDiplomacyTable` Gift Improvement callback | `GiftImprovementToCityStateCommand`, validates ownership/resource/buildability, influence and gold before one atomic cost/improvement commit |
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
| City-state tribute (gold/worker) | `CityStateDiplomacyTable` tribute buttons | `TributeCommand` (willingness gates mirror the disabled buttons) |
| City-state protection pledge/revoke | `CityStateDiplomacyTable` confirm popups | `ProtectionCommand` (pledge/withdraw gates mirror the disabled buttons) |
| City-state diplomatic marriage | `CityStateDiplomacyTable` marriage button | `DiplomaticMarriageCommand` (unique + canBeMarriedBy gates; cities auto-puppet, annex via routed conquest options) |
| Trade propose/retract (trade screen) | `TradeTable.offerButton` | `ProposeTradeCommand` (staged offers mapped to DTOs) / `RetractTradeCommand` |
| Conquest dispositions (annex/puppet/raze/liberate) | `AlertPopup` conquest options (also used by the marriage city-choice popup) | `ResolveConquestCommand` with `conquest:<cityId>`; challenger instant-destroy never reaches a decision (engine destroys on capture) |
| Friendship offer accept/decline | `AlertPopup` friendship buttons | `RespondFriendshipOfferCommand` (decline records the refusal flag, mirroring legacy) |
| Denouncement retaliation ("THIS MEANS WAR!") | `AlertPopup.addDenouncement` | `DeclareWarCommand` |
| Protected-minor alert (war / side-with / break pledge) | `AlertPopup.addBulliedOrAttackedProtectedOrAlliedMinor` | `RespondProtectorAlertCommand` (war type follows allied-vs-protected; break-pledge forces withdrawal with notification, mirroring legacy) |
| Diplomatic vote (picker) | `DiplomaticVotePickerScreen.voteAndClose` | `CastVoteCommand` (incl. abstain) |

## Gated (blocked while an agent turn is live; normal on human turns)

| Entry point | File | Gate |
| --- | --- | --- |
| Map click-to-move / attack arrows | `worldmap/WorldMapHolder.kt` | `canChangeState` |
| Unit actions table (all UnitAction invocations) | `unit/actions/UnitActionsTable.kt`, `UnitActions*.kt` | `canChangeState` |
| Bottom unit panel selections/orders | `unit/UnitTable.kt` | `canChangeState` |
| Battle confirmations/conquest popups | `AlertPopup.kt`, `bottombar/BattleTable.kt` | `BattleTable` checks `canChangeState`; AlertPopup opens only inside the popup block (see below) |
| Next-unit cycling | `WorldScreen.switchToNextUnit` | selection only; no state mutation |
| Automated-units move (banner action) | `NextTurnAction.MoveAutomatedUnits` | disabled while a session exists — automated-unit processing runs inside the coordinator's turn processing |
| Popup block: AlertPopup (demands, promises, conquest), TradePopup accept, free-Great-Person picker, DiplomaticVoteResultScreen | `WorldScreen.kt` update loop | popup block now requires `isPlayersTurn && canChangeState` — closes the human-answers-agent's-decision leak |
| Production queue add/remove/reorder/purchase | `cityscreen/CityConstructionsTable.kt`, `BuyButtonFactory.kt` | `canChangeState` |
| Citizen work/lock/buy/focus/specialists | `cityscreen/CityScreenTileTable.kt`, `CitizenManagementTable.kt`, `SpecialistAllocationTable.kt` | `canChangeState` |
| Rename, annex, raze toggles | `cityscreen/CityScreen.kt` | `canChangeState` |
| View `try...` methods | `view/CityView.kt` | view-model layer only; state mutations reachable through it come from the gated screens above |
| Declare war/peace/friendship/denounce/demands buttons | `DiplomacyScreen.kt`, `MajorCivDiplomacyTable.kt` | `isNotPlayersTurn()` = `!GUI.isAllowedChangeState()` |
| Trade staging/propose/accept/decline/counter | `TradeTable.kt`, `OfferColumnsTable.kt`, `TradePopup.kt` (world screen) | tables inherit the DiplomacyScreen gate; the standalone TradePopup is closed by the popup-block gate |
| City-state gifts/protection/tribute/marriage | `CityStateDiplomacyTable.kt` | all mutation buttons routed (see Routed table); `isNotPlayersTurn()` remains as the agent-turn block |
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

The routed entries listed above use the authoritative `MatchCoordinator` via
`WorldScreen.routeThroughAgentSession`. The October 2026 takeover audit found
legacy snapshot-only dependencies in citizen controls, founding, construction
menus and city-state gifts, plus incomplete research intent. The listed routes
now cover those confirmed controls. This is a bounded Vanilla audit, not proof
that every legacy or mod mutation has been exercised. In ordinary (non-agent)
matches, interceptors yield false and execute the legacy callbacks.

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

### 5. Popup Decisions, Trade Screen & Remaining Closures (CP-034)

- **Conquest dispositions** (`AlertPopup.addCityConquered` via `addAnnexOption /
  addPuppetOption / addRazeOption / addLiberateOption`): each button resolves
  `ResolveConquestCommand` with decision id `conquest:<cityId>`. The challenger
  instant-destroy branch is unreachable in agent matches (the engine destroys
  on capture with no decision raised). Resolving also clears the CityConquered
  alert (AlertPopup.close parity, added to `executeResolveConquest`).
- **Friendship offers** (`AlertPopup` accept/decline): `RespondFriendshipOfferCommand`
  (accept signs; decline records the refusal flag, mirroring legacy).
- **Denouncement retaliation** (`AlertPopup.addDenouncement` "THIS MEANS WAR!"):
  `DeclareWarCommand`.
- **Protected-minor alerts** (war / side-with / break-pledge):
  `RespondProtectorAlertCommand` with the allied-vs-protected war type; break-pledge
  forces withdrawal with the legacy notification text.
- **Diplomatic vote picker** (`DiplomaticVotePickerScreen.voteAndClose`):
  `CastVoteCommand` (incl. abstain).
- **Trade screen** (`TradeTable.offerButton`): propose maps staged offers to
  `ProposeTradeCommand`; retract maps to the new `RetractTradeCommand`.
- **Demands table** (`MajorCivDiplomacyTable` demands buttons): `DemandCommand`.
- **Move-automated-units banner** (`NextTurnAction.MoveAutomatedUnits`): disabled
  while a session exists — automated-unit processing runs inside the
  coordinator's turn processing, never as a mid-turn bypass.

## Remaining: genuinely out-of-scope (no gameplay mutation in Vanilla agent matches)

Each entry was inspected against the Vanilla ruleset and the plan scope;
none of them can change match state in an agent-enabled match:

| Entry point | Reason it is out of scope |
| --- | --- |
| Espionage screens and spy actions (`EspionageOverviewScreen`) | Vanilla has no espionage; `AgentMatchSetup.validate` locks espionage/mods for agent matches, so these screens are unreachable |
| Religion screens/pickers/founding | Vanilla has no religion; ruleset-gated, unreachable |
| `UnitActionType.Transform` | mod-only unique (`UniqueType.CanTransform`); no transformable unit exists in the Vanilla ruleset |
| Unit gifts to major civilizations | no such control exists: the engine has no major-civ unit-gift function and `TradeOfferType` has no unit type (unit gifts exist only toward city-states, which are routed) |
| Dev console, debug cheats, god-mode toggle, simulation controls | forbidden outright while a session exists (see above), not merely gated |
| Multiplayer status popup (`MultiplayerStatusButton`) | presentation-only: loads file previews of remote multiplayer matches, never writes to the current game |
| Spectator perspective controls | presentation-only observation; `canChangeState` is false for spectators so no mutation control is reachable |
| Minimap, tile info, overlay toggles, save/load, options, civilopedia | read-only or out of match scope |

Unlisted controls require source and executable verification before claiming
complete mutation coverage. Secondary-screen rebinding and mandatory-choice
reachability are separate integration requirements from durable route handling.
