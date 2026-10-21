# Agent strategy and intelligence

Every invocation receives the versioned policy in `agent-runner/src/strategy-policy.ts`, including resumed sessions. Saved personalities remain in effect. The organizing objective is a victory the saved match can award to that civilization.

See the [strategy/composer acceptance record](agent-victory-evaluation.md) for matched behavior results, missed or unmeasurable targets, and the separate installed-app verification boundary.

## Objectives and commitments

`game.observe.victory` distinguishes saved enabled victory types, routes available to this civilization, canonical milestone completion and missing agent commands. The adapter reads engine predicates; it does not award victory or advance the game. Scientific spaceship assembly currently has no agent action and is labeled unsupported when still required. This does not disable Scientific victory in the saved rules.

The leader chooses the most achievable supported enabled route. A Domination-only game therefore targets original capitals, not a space victory, score growth or city-state conquest. Known original capitals include their founding civilization and knowledge state: `CURRENT`, `REVEALED` under map vision, stale `REMEMBERED`, or `UNKNOWN` with no hidden location. Replacement administrative capitals do not count as new original capitals.

Private notes remain the same string and revision API, with a small JSON `strategy` section (`version:1`) and short `memory`. The commitment records the canonical victory, one milestone, `expectedGameTurn`, readiness, assigned unit/city IDs, `lastReviewedGameTurn`, last observed progress, next action, fallback condition and blocker/recheck conditions. Old free text remains readable; malformed or incomplete strategy notes request a refresh and never reject a gameplay turn. Notes remain limited to 16 KiB.

## Progress and reassessment

`gameTurn` is the actual engine round. `turnSerial` remains protocol identity and must not be divided by civilization count to estimate rounds.

`strategicProgress` includes current numeric research/production work, incoming changes since the leader's previous turn end, and up to three completed own-turn summaries. Start-turn completions and intervening unit/city losses appear before the next prompt. Each completed summary combines incoming and action-turn changes, plus diagnostic applied-command counts. Event details and production rows are bounded with explicit counts/truncation. Old saves start with incomplete history; three quiet turns are never invented from missing records. The state is civilization-scoped, deep-copied and saved with the coordinator transaction.

A missed deadline or three own turns without progress on the chosen milestone requires a concrete reassessment. Useful travel, healing, production or research may justify continuation with evidence. The runner detects an entirely quiet complete window as a conservative reminder; the leader must still judge relevance when unrelated events changed. Ordinary gold/culture accrual alone is not proof of milestone progress, although a resource-saving milestone may justify continuation using those numeric facts. No quota forces attacks, outgoing mail or a route switch, and reassessment adds no end-turn gate.

## Campaigns, diplomacy and blocked work

A campaign needs a reachable approach, local battle previews, ranged/siege support, healthy capture units, a launch round and a fallback. The official `militaryStrength` ranking includes nuclear and treasury effects. `military.own` separates conventional force, nuclear weapons and unit roles; foreign `VISIBLE_ONLY` composition describes sightings rather than an enemy total. Waiting or defending can be correct when readiness is not yet sufficient, but preparation needs measurable work.

Leaders actively seek useful trades, access agreements, coalitions and campaign coordination, initiate proposals and follow negotiations through to concrete terms. Outgoing diplomacy serves a specific proposal, transaction, changed threat or operational need. Zero outgoing messages is valid on a quiet turn without a useful new proposal. Required delivered incoming acknowledgements remain concise visible replies with attachment review and confirmed receipts; they do not require an additional outgoing message. Deals require their legal engine actions.

For a blocked unit, retain its reason, relevant `recheckOn` condition and bounded `reviewGameTurn`. Recheck after that condition changes, a new threat or the review round, rather than every unrelated revision. Keep existing sleeping/fortifying orders and ongoing worker or movement progress. Mail volume, repeated orders and rewritten notes are diagnostic activity, not success.

## What leaders can read

`game.observe` adds to the empire overview `happiness`, `goldPerTurn`, `militaryStrength` (Unciv's Demographics "Force"), `score`, and `militaryRank`/`scoreRank` among the major nations listed in `rivals`.

`rivals` lists every living civilization the leader has met, or every living civilization under `MAP`/`FULL` [map vision](agent-map-tools.md#map-vision). Each entry has `cityState`, `met`, `status` with the leader (`War`, `Peace`, `Protector` or `DefensivePact`; null until met), `declarationOfFriendship`, `attitudeTowardYou` (Unciv's relationship level, `Unforgivable` through `Ally`), `cities`, `militaryStrength`, `score` and `atWarWith` (limited to civilizations the leader may know about).

Own units in `game.observe` carry `attackTargets` (how many distinct targets the unit can attack now), `canPromote` and `canUpgrade` (an upgrade the treasury can pay for now) when they apply, so leaders query `game.legal_actions` only for units with something to do.

Each `game.legal_actions` attack target is one target tile with `targetName`, `targetOwnerCivId`, `targetHealth` and the in-game battle preview: `expectedDamageToTarget`, `expectedDamageToAttacker` (the midpoint the battle table shows, capped at the defender's health; cities stop at 1 HP) and `targetDies`. `attackFromX`/`attackFromY` name the tile a melee unit strikes from when it must move first; `unit.attack` uses that same tile.

`game.inspect({cityId})` describes one of the leader's own cities: population, health, defense, food stored and turns to growth or starvation, yields per turn, current construction with turns left, queue, buildings, worked tiles, specialists, focus, bombard readiness, garrison and turns to border growth. Foreign and unknown cities share the safe rejection. City `constructions` list `{name, turns, cost, goldCost}`; `goldCost` is the price `PURCHASE` charges and appears only when gold can buy that construction in the city now (the treasury is not checked).

Rejections explain their cause: an invalid trade names each failing item (for example a Peace Treaty with a nation already at peace, or more gold than a side has, as the native trade screen lists), a worker order names the blocker (territory, movement, technology, terrain, or a Work Boat off its resource tile), and a settler `AUTOMATE` with no reachable site suggests exploring, moving toward unclaimed land, embarking or waiting.

`game.legal_actions` for a city-founding unit adds `citySites`: up to five of Unciv's best-ranked reachable founding sites nearby, best first, each with `x`, `y`, `score` and `turns` to reach (0 for the unit's own tile). The ranking is the one Unciv's own settler automation uses, over explored land.

## Settlers and scouts

`EXPLORE` explores immediately with remaining movement. `AUTOMATE` on a city-founding unit runs Unciv's settler choice immediately, settling or moving toward a ranked reachable site. Agent nations' standing automation is cleared at turn start, so those two orders last one turn and may be renewed when useful. A blocked settler can wait safely for a relevant access, technology, land or threat change; it need not be inspected or reordered every turn. Found the opening city promptly when legal and preserve useful exploration.

Work Boats create sea-resource improvements the way the in-game "Create [Fishing Boats]" button does: on a water tile whose resource the civilization can see, `legal_actions` lists the resource's first buildable improvement with `consumesUnit:true` and `turnsToBuild:0`, and `unit.build` with that name builds it at once and uses up the boat. The checks are the engine's own (technology, borders, terrain), and a rejection names the failing one, including standing on the wrong tile or naming an improvement that does not improve the resource. Before this, agents had no way to improve sea resources: `unit.build` rejected Work Boats, and the in-game action was not exposed to them.
