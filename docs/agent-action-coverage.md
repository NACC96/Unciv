# Agent Action Coverage — Civ V - Vanilla (plan §6)

**Status:** W4 in progress (2026-09-14). Each row identifies: command schema,
legality source, handler, result events, human UI path, and deterministic fixture.
"Implemented" = those links exist plus a passing fixture — not merely a command name.

**Ruleset authority:** the actual reachable Vanilla actions (including
unique-triggered ones) are authoritative; this table organizes coverage, it does
not permit omitting unusual legal actions. Unsupported required choices must
pause with type/entity + human recovery path, never auto-select or silently skip.

| Family | Required supported choices | Command schema | Legality source | Handler | Result events | Human UI path | Fixture | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Research | Select/change queue; free techs; prerequisites; completed tree | `research.select` | `TechManager.canBeResearched/getRequiredTechsToDestination/getFreeTechnology` | `MatchCoordinator.executeResearch` | `ResearchSelectedResult` + receipt | `TechPickerScreen` modes mirrored | AgentCommandsTest: queue select, free pick, unknown/illegal | implemented |
| Policies | Adopt branches/policies; free policies; dismissible reminders | — | `PolicyManager` | — | — | `PolicyPickerScreen` | — | not started |
| Cities | Found/rename; queue order/remove; buildings/units/projects/perpetual; purchase/sell | `city.produce` (add/remove/raise/lower/purchase) | `CityConstructions.canAddToQueue/addToQueue/removeFromQueue/raisePriority/lowerPriority/purchaseConstruction/isConstructionPurchaseAllowed` | `MatchCoordinator.executeProduction` | `CityProductionResult` (queue) + receipt | city screen `ConstructionsTable` | AgentCommandsTest: queue ops, purchase, foreign-city oracle | implemented (purchase gold-only; sell/rename deferred) |
| Population/economy | Citizen assignment/locks; specialists; focus; tile purchase; empire settings | — | `PopulationManager` | — | — | city screen population tab | — | not started |
| Civilian units | Settle; improvements; roads/repair; pillage; sleep/skip/disband; gifts | `unit.found`, `unit.order` (fortify/sleep/wake/disband) | `Civilization.addCity`, `MapUnit.fortify/disband`, UI action predicates as gates | `executeFound`, `executeOrder` | `CityFoundedResult`, `UnitOrderResult` + receipts | `WorldScreen` unit actions | AgentCommandsTest: found, orders, disband, oracle | partial (improvements/roads/pillage/gifts deferred) |
| Movement | Land/sea; embark/disembark; stacking; ZoC; explicit paths; exploration | `unit.move` | `UnitMovement.getTileToMoveToThisTurn/moveToTile` (human-click semantics) | `MatchCoordinator.executeMove` | `UnitMovedResult` (final pos) + receipt | map click-to-move | MatchCoordinatorTest, CombatConquestTest marches | implemented (multi-turn goto deferred) |
| Land/naval combat | Melee/ranged attacks; bombardment; siege setup; capture; heal/fortify; upgrades/promotions | `unit.attack` (unit + city attackers) | `TargetHelper`, `Battle.movePreparingAttack/attackOrNuke`, `DamageDealt` | `MatchCoordinator.executeAttack` | `AttackResult` + receipt; captures raise decisions | `WorldScreen` attack arrows | CombatConquestTest: damage receipts, multi-turn conquest | partial (air/nukes/paradrop/upgrades/promotions deferred) |
| Air/special combat | Rebase/carriers; strikes/interception/sweep; paradrop; nuclear (if in Vanilla) | — | air-combat rules | — | — | air UI | — | not started |
| Great people | Free-person selections; each legal special action | — | great-person actions | — | — | unit actions | — | not started |
| Diplomacy/trade | War/peace; offers; accept/decline/counter; friendship; demands/promises | `diplomacy.declareWar/makePeace/proposeTrade/respondTrade` | `DiplomacyManager`, `TradeLogic.acceptTrade`, `TradeEvaluation.isTradeValid`, `TradeRequest.decline` | `executeDeclareWar/MakePeace/ProposeTrade/RespondTrade` | `DiplomacyResult`, `TradeProposed/RespondedResult` + receipts | diplomacy screens, TradePopup accept/decline | DiplomacyTradeTest: cycle, proposal, accept gold flow, expiry, decline, contact gates | partial (friendship/pacts/counteroffers/demands deferred) |
| City-states | Gold/unit gifts; protection; tribute; war/peace | — | city-state rules | — | — | city-state diplomacy | — | not started |
| Conquest/rewards | Annex/puppet/raze/liberate; captured units; ruins/free-choice outcomes | `conquest.resolve` (all 5 dispositions) | `CityConquestFunctions`, `AlertPopup.addCityConquered` option matrix | `executeAttack` (decision source) + `executeResolveConquest` | `ConquestResolvedResult` + receipt | conquest popups | CombatConquestTest: live puppet/annex/raze over real turns | partial (liberate/destroy paths unexercised; ruins deferred) |
| Victory | Spaceship parts/assembly; Utopia; capital capture; UN vote/abstain; score/time | *(end-turn + decisions)* | `VictoryManager` | turn stepper victory stop | phase COMPLETED | victory screens | — | not started |
