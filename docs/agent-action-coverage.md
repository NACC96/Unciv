# Agent Action Coverage — Civ V - Vanilla (plan §6)

**Status:** W0 stub. Each row must eventually identify: command schema,
legality source, handler, result events, human UI path, and deterministic fixture.
"Implemented" = those links exist plus a passing fixture — not merely a command name.

**Ruleset authority:** the actual reachable Vanilla actions (including
unique-triggered ones) are authoritative; this table organizes coverage, it does
not permit omitting unusual legal actions. Unsupported required choices must
pause with type/entity + human recovery path, never auto-select or silently skip.

| Family | Required supported choices | Command schema | Legality source | Handler | Result events | Human UI path | Fixture | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Research | Select/change queue; free techs; prerequisites; completed tree | `research.select` *(proposed)* | `TechnologyManager` / ruleset prerequisites | — | — | `TechPickerScreen` | research completes at start; illegal free-tech rejected | not started |
| Policies | Adopt branches/policies; free policies; dismissible reminders | `policy.adopt` *(proposed)* | `PolicyManager` | — | — | `PolicyPickerScreen` | free policy; branch completion; culture-victory prereqs | not started |
| Cities | Found/rename; queue order/remove; buildings/units/projects/perpetual; purchase/sell | `city.*` *(proposed)* | `CityConstructions` | — | — | city screen `ConstructionsTable` | empty queue; blocked purchase; unavailable wonder | not started |
| Population/economy | Citizen assignment/locks; specialists; focus; tile purchase; empire settings | `city.population.*` *(proposed)* | `PopulationManager` | — | — | city screen population tab | growth; starvation; insufficient gold; tile taken | not started |
| Civilian units | Settle; improvements; roads/repair; pillage; sleep/skip/disband; gifts | `unit.civilian.*` *(proposed)* | `UnitActions` | — | — | `WorldScreen` unit actions | promise-break settle; multi-turn work; recaptured civilian | not started |
| Movement | Land/sea; embark/disembark; stacking; ZoC; explicit paths; exploration | `unit.move` | pathfinding (`UnitMovement`) | — | — | map click-to-move | reveal enemy; interrupted path; map wrap; no hidden preview | not started |
| Land/naval combat | Melee/ranged; bombardment; siege setup; capture; heal/fortify; upgrades/promotions | `unit.attack`, `unit.promote`, `unit.upgrade` *(proposed)* | `Battle`, `UnitPromotions` | — | — | `WorldScreen` attack arrows | attacker dies; cost change; capture → decision | not started |
| Air/special combat | Rebase/carriers; strikes/interception/sweep; paradrop; nuclear (if in Vanilla) | `unit.air.*` *(proposed)* | air-combat rules | — | — | air UI | range/slot limits; multi-civ consequences; confirmations | not started |
| Great people | Free-person selections; each legal special action | `unit.great.*` *(proposed)* | `GreatPerson` actions | — | — | unit actions | hurry; golden age; improvement; trade mission | not started |
| Diplomacy/trade | War/peace; offers; accept/decline/counter; friendship; demands/promises | `diplomacy.*` *(proposed)* | `DiplomacyManager`, `Trade` | — | — | diplomacy screens/popups | expired offers; war invalidates; resource change | not started |
| City-states | Gold/unit gifts; protection; tribute; war/peace | `citystate.*` *(proposed)* | city-state rules | — | — | city-state diplomacy | insufficient gold; changed ally; protection conflict | not started |
| Conquest/rewards | Annex/puppet/raze/liberate; captured units; ruins/free-choice | `conquest.resolve`, `ruins.choose` *(proposed)* | conquest logic, `NextTurnAction` | — | — | conquest popups | headless conquest; liberation diplomacy | not started |
| Victory | Spaceship parts/assembly; Utopia; capital capture; UN vote/abstain; score/time | *(end-turn + decisions)* | `VictoryManager` | — | — | victory screens | prepared fixtures ×5 victories; stop after success | not started |

Schemas marked *(proposed)* are W1 work: they must be pinned in `agent-protocol/`
before handlers are built (W2–W4).
