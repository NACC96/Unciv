# Agent strategy and intelligence

Every leader receives the same strategic doctrine in its shared instructions (`agent-runner/src/game-instructions.ts`, `STRATEGY_INSTRUCTIONS`). A nation's personality text still applies on top of it and can be stricter.

## Doctrine: brutal realpolitik

- **Aim**: win. Other nations are rivals; each decision is judged by whether it improves the leader's relative position in cities, land, production, technology and military power.
- **Expansion**: found cities early and keep founding while reachable land remains and happiness stays non-negative. Settlers never sit idle. With no free land left, expansion continues by conquest.
- **War**: a normal instrument of policy. With a clear military edge over a neighbor, especially when no free land remains, leaders prepare, declare war and take cities, then make peace once the war stops paying. Large armies are not left idle.
- **Agreements**: instruments, not obligations. Leaders keep pacts while they serve them and break them when the gain outweighs the lost trust.
- **Diplomacy**: used every turn as a weapon: alliances of convenience, isolating targets, turning rivals against each other, extracting concessions. Leaders may posture and bluff, but never misreport game facts or engine results to the spectator. Nuclear weapons remain a last resort.

## What leaders can read

`game.observe` adds to the empire overview `happiness`, `goldPerTurn`, `militaryStrength` (Unciv's Demographics "Force"), `score`, and `militaryRank`/`scoreRank` among the major nations listed in `rivals`.

`rivals` lists every living civilization the leader has met, or every living civilization under `MAP`/`FULL` [map vision](agent-map-tools.md#map-vision). Each entry has `cityState`, `met`, `status` with the leader (`War`, `Peace`, `Protector` or `DefensivePact`; null until met), `declarationOfFriendship`, `attitudeTowardYou` (Unciv's relationship level, `Unforgivable` through `Ally`), `cities`, `militaryStrength`, `score` and `atWarWith` (limited to civilizations the leader may know about).

Own units in `game.observe` carry `attackTargets` (how many distinct targets the unit can attack now), `canPromote` and `canUpgrade` (an upgrade the treasury can pay for now) when they apply, so leaders query `game.legal_actions` only for units with something to do.

Each `game.legal_actions` attack target is one target tile with `targetName`, `targetOwnerCivId`, `targetHealth` and the in-game battle preview: `expectedDamageToTarget`, `expectedDamageToAttacker` (the midpoint the battle table shows, capped at the defender's health; cities stop at 1 HP) and `targetDies`. `attackFromX`/`attackFromY` name the tile a melee unit strikes from when it must move first; `unit.attack` uses that same tile.

`game.inspect({cityId})` describes one of the leader's own cities: population, health, defense, food stored and turns to growth or starvation, yields per turn, current construction with turns left, queue, buildings, worked tiles, specialists, focus, bombard readiness, garrison and turns to border growth. Foreign and unknown cities share the safe rejection. City `constructions` list `{name, turns, cost, goldCost}`; `goldCost` is the price `PURCHASE` charges and appears only when gold can buy that construction in the city now (the treasury is not checked).

Rejections explain their cause: an invalid trade names each failing item (for example a Peace Treaty with a nation already at peace, or more gold than a side has, as the native trade screen lists), a worker order names the blocker (territory, movement, technology, terrain, or a Work Boat off its resource tile), and a settler `AUTOMATE` with no reachable site suggests exploring, moving toward unclaimed land, embarking or waiting.

`game.legal_actions` for a city-founding unit adds `citySites`: up to five of Unciv's best-ranked reachable founding sites nearby, best first, each with `x`, `y`, `score` and `turns` to reach (0 for the unit's own tile). The ranking is the one Unciv's own settler automation uses, over explored land.

## Settlers and scouts

`EXPLORE` explores immediately with the unit's remaining movement. `AUTOMATE` on a city-founding unit runs Unciv's settler choice immediately: it settles in place when that is nearly as good as the best ranked reachable site, otherwise heads there and founds on arrival, returning `foundedCityId` when a city is founded. Agent nations' standing automation is cleared at each turn start, so both orders last one turn; the doctrine tells leaders to repeat them, and to automate a settler that has no reachable site or has gone two turns without founding. When `AUTOMATE` is rejected because no reachable site exists, leaders stop retrying it each turn and explore, move or embark the settler instead; in one logged match a settler repeated a rejected `AUTOMATE` for more than 20 turns.

Work Boats create sea-resource improvements the way the in-game "Create [Fishing Boats]" button does: on a water tile whose resource the civilization can see, `legal_actions` lists the resource's first buildable improvement with `consumesUnit:true` and `turnsToBuild:0`, and `unit.build` with that name builds it at once and uses up the boat. The checks are the engine's own (technology, borders, terrain), and a rejection names the failing one, including standing on the wrong tile or naming an improvement that does not improve the resource. Before this, agents had no way to improve sea resources: `unit.build` rejected Work Boats, and the in-game action was not exposed to them.
