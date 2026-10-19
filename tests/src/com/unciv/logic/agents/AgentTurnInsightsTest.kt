package com.unciv.logic.agents

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.models.UnitActionType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Reads that answer in one call what agents used to probe for: one attack entry per target with
 * the battle preview, own-unit readiness hints, own-city inspection, priced constructions,
 * explained rejections and the world-wrap radius. Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class AgentTurnInsightsTest {

    private class Fixture(gold: Int = 500) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val rome = run {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(6)
            testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
        }
        val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
        val romeId: String = rome.civID
        val greeceId: String = greece.civID
        val cityId = testGame.addCity(rome, testGame.getTile(0, 0), initialPopulation = 3).id
        val greekCity = testGame.addCity(greece, testGame.getTile(4, 0)).apply { health = 1 }

        init {
            // At war before units stand in Greek territory.
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            rome.getDiplomacyManager(greece)!!.declareWar(DeclareWarReason(WarType.DirectWar))
        }

        /** Stands next to a 5 HP Greek warrior. */
        val warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(-3, 0)).apply { promotions.XP = 20 }.id
        val weakGreek = HexCoord(-4, 0)
        /** Two moves from a healthy Greek warrior, sharing two neighbors with it. */
        val flankerId = testGame.addUnit("Warrior", rome, testGame.getTile(-2, -5)).id
        val healthyGreek = HexCoord(-1, -3)
        val archerId = testGame.addUnit("Archer", rome, testGame.getTile(-4, 2)).id
        /** Next to the 1 HP Greek city, and an archer in range of it. */
        val besiegerId = testGame.addUnit("Warrior", rome, testGame.getTile(3, 0)).id
        val siegeArcherId = testGame.addUnit("Archer", rome, testGame.getTile(2, 0)).id
        val workerId = testGame.addUnit("Worker", rome,
            testGame.setTileTerrain(HexCoord(-2, 3), "Grassland")).id
        val workBoatsId = testGame.addUnit("Work Boats", rome,
            testGame.setTileTerrain(HexCoord(-6, 0), "Coast")).id
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge

        init {
            testGame.addUnit("Warrior", greece, testGame.getTile(weakGreek)).health = 5
            testGame.addUnit("Warrior", greece, testGame.getTile(healthyGreek))
            for (tech in listOf("Agriculture", "Bronze Working", "Machinery")) rome.tech.addTechnology(tech)
            rome.addGold(gold)
            testGame.gameInfo.currentPlayer = romeId
            testGame.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT, AgentProfile("p-${civ.civID}", civ.civID, "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter())
            bridge = AgentEngineBridge(coordinator)
        }

        fun rev() = coordinator.getStateRevision()
        fun snapshot() = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun legal(unitId: Int) = coordinator.legalActions(romeId, unitId = unitId)

        fun rejection(command: AgentCommand): String {
            val before = coordinator.saveToJson()
            try {
                coordinator.execute(command)
            } catch (ex: AgentCommandException) {
                assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
                assertEquals(before, coordinator.saveToJson())
                return ex.message!!
            }
            fail("expected ILLEGAL_ACTION")
            throw AssertionError()
        }
    }

    @Test
    fun `each target is listed once with the attack position and preview unit attack uses`() {
        val f = Fixture()
        val snapshot = f.snapshot()
        val flanker = snapshot.getCivilization(f.romeId).units.getCivUnits().single { it.id == f.flankerId }
        val enumerated = TargetHelper.getAttackableEnemies(flanker, flanker.movement.getDistanceToTiles())
            .filter { it.tileToAttack.position == f.healthyGreek }
        assertTrue("The engine offers both shared neighbors", enumerated.size >= 2)

        val target = f.legal(f.flankerId).attackTargets.single()
        assertEquals(f.healthyGreek, HexCoord(target.targetX, target.targetY))
        assertEquals("unit", target.targetKind)
        assertEquals("Warrior", target.targetName)
        assertEquals(f.greeceId, target.targetOwnerCivId)
        assertEquals(100, target.targetHealth)
        val from = HexCoord(target.attackFromX!!, target.attackFromY!!)
        assertEquals(enumerated.first().tileToAttackFrom.position, from)

        // The battle table's midpoint, from the same attack position.
        val attacker = MapUnitCombatant(flanker)
        val defender = enumerated.first().combatant!!
        val fromTile = snapshot.tileMap[from]
        fun midpoint(max: Int, min: Int) = listOf(minOf(max, 100), minOf(min, 100)).average().roundToInt()
        assertEquals(midpoint(BattleDamage.calculateDamageToDefender(attacker, defender, fromTile, 1f),
            BattleDamage.calculateDamageToDefender(attacker, defender, fromTile, 0f)), target.expectedDamageToTarget)
        assertEquals(midpoint(BattleDamage.calculateDamageToAttacker(attacker, defender, fromTile, 1f),
            BattleDamage.calculateDamageToAttacker(attacker, defender, fromTile, 0f)), target.expectedDamageToAttacker)
        assertTrue(target.expectedDamageToTarget!! in 1..99)
        assertTrue(target.expectedDamageToAttacker!! > 0)
        assertEquals(false, target.targetDies)

        f.coordinator.execute(AttackCommand(f.romeId, "flank", f.rev(), attackerUnitId = f.flankerId, target = f.healthyGreek))
        assertEquals("unit.attack moves to the previewed position", from, f.coordinator.getUnitPosition(f.romeId, f.flankerId))
    }

    @Test
    fun `previews follow melee ranged and city combat rules`() {
        val f = Fixture()
        val adjacent = f.legal(f.warriorId).attackTargets.single()
        assertEquals(f.weakGreek, HexCoord(adjacent.targetX, adjacent.targetY))
        assertNull("Attacks from the current tile carry no position", adjacent.attackFromX)
        assertNull(adjacent.attackFromY)
        assertEquals(5, adjacent.targetHealth)
        assertEquals("Damage is clamped to remaining health", 5, adjacent.expectedDamageToTarget)
        assertEquals(true, adjacent.targetDies)

        val ranged = f.legal(f.archerId).attackTargets.single { it.targetX == f.weakGreek.x && it.targetY == f.weakGreek.y }
        assertEquals("Ranged attackers take no retaliation", 0, ranged.expectedDamageToAttacker)
        assertEquals(true, ranged.targetDies)

        val capture = f.legal(f.besiegerId).attackTargets.single()
        assertEquals("city", capture.targetKind)
        assertEquals(f.greekCity.name, capture.targetName)
        assertEquals(f.greeceId, capture.targetOwnerCivId)
        assertEquals(1, capture.targetHealth)
        assertEquals("A melee unit occupies a 1 HP city", 0, capture.expectedDamageToTarget)
        assertEquals(true, capture.targetDies)

        val bombard = f.legal(f.siegeArcherId).attackTargets.single { it.targetKind == "city" }
        assertEquals(0, bombard.expectedDamageToTarget)
        assertEquals(0, bombard.expectedDamageToAttacker)
        assertEquals("Ranged units cannot take cities", false, bombard.targetDies)
    }

    @Test
    fun `observe marks own units that can attack promote or upgrade and nothing else`() {
        val f = Fixture()
        val obs = f.coordinator.observe(f.romeId)
        fun unit(id: Int) = obs.ownUnits.single { it.unitId == id }
        assertEquals(1, unit(f.warriorId).attackTargets)
        assertEquals(1, unit(f.flankerId).attackTargets)
        assertEquals(1, unit(f.besiegerId).attackTargets)
        assertNull(unit(f.workerId).attackTargets)
        assertEquals(true, unit(f.warriorId).canPromote)
        assertNull(unit(f.flankerId).canPromote)
        assertEquals("Archers upgrade to Crossbowmen with Machinery", true, unit(f.archerId).canUpgrade)
        assertNull("Swordsmen need Iron Working", unit(f.warriorId).canUpgrade)
        assertTrue(obs.visibleForeignUnits.isNotEmpty())
        assertTrue(obs.visibleForeignUnits.all { it.attackTargets == null && it.canPromote == null && it.canUpgrade == null })

        val broke = Fixture(gold = 0)
        assertNull("Upgrades must be affordable now",
            broke.coordinator.observe(broke.romeId).ownUnits.single { it.unitId == broke.archerId }.canUpgrade)

        @Suppress("UNCHECKED_CAST")
        val wire = (f.bridge.observe(f.romeId) as Map<String, Any?>)["ownUnits"] as List<Map<String, Any?>>
        assertEquals(1, wire.single { it["unitId"] == f.warriorId }["attackTargets"])
        assertNull(wire.single { it["unitId"] == f.workerId }["attackTargets"])
    }

    @Test
    fun `inspect city shows own cities only with city screen numbers`() {
        val f = Fixture()
        f.coordinator.execute(CityProductionCommand(f.romeId, "queue", f.rev(), f.cityId, ProductionOp.ADD_TOP, "Monument"))
        val city = f.coordinator.inspectCity(f.romeId, f.cityId)!!
        assertEquals(f.cityId, city.cityId)
        assertEquals(0, city.x)
        assertEquals(0, city.y)
        assertEquals(3, city.population)
        assertTrue(city.isCapital)
        assertFalse(city.isPuppet)
        assertTrue(city.health in 1..city.maxHealth)
        assertTrue(city.defenseStrength > 0)
        assertEquals(listOf("Food", "Production", "Gold", "Science", "Culture", "Faith", "Happiness"),
            city.yieldsPerTurn.keys.toList())
        assertEquals("Monument", city.queue.first())
        val snapshot = f.snapshot()
        val committedCity = snapshot.getCities().single { it.id == f.cityId }
        val current = city.currentConstruction!!
        assertEquals("Monument", current.name)
        assertEquals(0, current.progress)
        assertEquals(snapshot.ruleset.buildings["Monument"]!!.getProductionCost(committedCity.civ, committedCity), current.cost)
        assertTrue(current.turnsLeft!! > 0)
        assertTrue(city.buildings.contains("Palace"))
        assertEquals(committedCity.population.foodStored, city.foodStored)
        assertEquals(committedCity.population.getFoodToNextPopulation(), city.foodNeededToGrow)
        assertFalse("Growing and starving are exclusive", city.turnsToGrowth != null && city.turnsToStarvation != null)
        assertEquals(committedCity.workedTiles.size, city.workedTiles)
        assertEquals(committedCity.canBombard(), city.canBombard)

        assertNull("Foreign cities stay private", f.coordinator.inspectCity(f.romeId, f.greekCity.id))
        assertNull(f.coordinator.inspectCity(f.romeId, "no-such-city"))
        assertNull(f.coordinator.inspectCity(f.greeceId, f.cityId))

        @Suppress("UNCHECKED_CAST")
        val wire = f.bridge.inspectCity(f.romeId, f.cityId) as Map<String, Any?>
        assertEquals(3, wire["population"])
        assertTrue("Nullable fields serialize as null", wire.containsKey("turnsToStarvation"))
        assertEquals("Monument", (wire["currentConstruction"] as Map<*, *>)["name"])
        assertNull(f.bridge.inspectCity(f.romeId, f.greekCity.id))
    }

    @Test
    fun `constructions carry turns cost and the gold price purchase charges`() {
        val f = Fixture()
        val options = f.coordinator.legalActions(f.romeId, cityId = f.cityId).constructions
        val monument = options.single { it.name == "Monument" }
        val snapshot = f.snapshot()
        val committedCity = snapshot.getCities().single { it.id == f.cityId }
        val monumentRules = snapshot.ruleset.buildings["Monument"]!!
        assertEquals(monumentRules.getProductionCost(committedCity.civ, committedCity), monument.cost)
        assertTrue(monument.turns > 0)
        assertEquals(monumentRules.getStatBuyCost(committedCity, Stat.Gold), monument.goldCost)
        assertNull("Wonders cannot be bought", options.single { it.name == "Statue of Zeus" }.goldCost)
        assertTrue(options.any { it.name == "Warrior" && it.goldCost != null })

        val broke = Fixture(gold = 0)
        assertEquals("The treasury does not hide the price", monument.goldCost,
            broke.coordinator.legalActions(broke.romeId, cityId = broke.cityId).constructions.single { it.name == "Monument" }.goldCost)

        @Suppress("UNCHECKED_CAST")
        val wire = (f.bridge.legalActions(f.romeId, null, f.cityId) as Map<String, Any?>)["constructions"] as List<Map<String, Any?>>
        assertEquals(setOf("name", "turns", "cost", "goldCost"), wire.first().keys)

        val gold = f.coordinator.getGold(f.romeId)
        f.coordinator.execute(CityProductionCommand(f.romeId, "buy", f.rev(), f.cityId, ProductionOp.PURCHASE, "Monument"))
        assertEquals(gold - monument.goldCost!!, f.coordinator.getGold(f.romeId))
    }

    @Test
    fun `worker rejections name the engine blocker`() {
        val f = Fixture()
        val boats = f.rejection(BuildImprovementCommand(f.romeId, "boats", f.rev(), f.workBoatsId, "Fishing Boats"))
        assertTrue(boats, boats.startsWith("Unit cannot work on improvements now: Work Boats has no worker build ability"))
        assertTrue(boats, boats.contains("consumed"))

        val farm = f.rejection(BuildImprovementCommand(f.romeId, "farm", f.rev(), f.workerId, "Farm"))
        assertTrue(farm, farm.startsWith("Improvement cannot be built on this tile now: Farm "))
        assertTrue(farm, farm.contains("needs a tile inside your territory (this tile is unowned)"))
    }

    @Test
    fun `settler automation without a site suggests the alternatives`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.makeHexagonalMap(2)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        game.addCity(rome, game.getTile(0, 0))
        val settlerId = game.addUnit("Settler", rome, game.getTile(1, 1)).id
        val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
        match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        val coordinator = MatchCoordinator(game.gameInfo, match, InMemoryCheckpointWriter())
        try {
            coordinator.execute(UnitOrderCommand(rome.civID, "auto", coordinator.getStateRevision(), settlerId, UnitOrder.AUTOMATE))
            fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
            val message = ex.message!!
            assertTrue(message, message.startsWith("No reachable city site in explored land nearby: "))
            for (hint in listOf("ranks high enough", "EXPLORE", "unit.move", "SLEEP")) assertTrue(message, message.contains(hint))
        }
    }

    @Test
    fun `map summary carries the wrap radius only on wrapped maps`() {
        assertNull(Fixture().let { it.coordinator.observe(it.romeId) }.mapSummary.maxLongitude)

        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.tileMap.mapParameters.worldWrap = true
        game.makeRectangularMap(6, 10)
        game.tileMap.mapParameters.worldWrap = true
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        game.addUnit("Warrior", rome, game.getTile(0, 0))
        val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
        match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        val coordinator = MatchCoordinator(game.gameInfo, match, InMemoryCheckpointWriter())
        val summary = coordinator.observe(rome.civID).mapSummary
        assertTrue(summary.worldWrap)
        val expected = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication()).tileMap.maxLongitude
        assertTrue(expected > 0)
        assertEquals(expected, summary.maxLongitude)
        @Suppress("UNCHECKED_CAST")
        val wire = (AgentEngineBridge(coordinator).observe(rome.civID) as Map<String, Any?>)["mapSummary"] as Map<String, Any?>
        assertEquals(expected, wire["maxLongitude"])
        assertNotNull(wire["worldWrap"])
    }

    @Test
    fun `air sweep previews describe only defenders the actor can see`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.makeHexagonalMap(9)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
        game.addCity(rome, game.getTile(0, 0))
        game.addCity(greece, game.getTile(-8, -8))
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.getDiplomacyManager(greece)!!.declareWar(DeclareWarReason(WarType.DirectWar))
        // Sight 6, sweep range 8.
        val fighterId = game.addUnit("Fighter", rome, game.getTile(0, 0))
            .apply { action = UnitActionType.AirSweep.value }.id
        val seen = HexCoord(1, 1)
        val hidden = HexCoord(8, 8)
        game.addUnit("Warrior", greece, game.getTile(seen))
        game.addUnit("Warrior", greece, game.getTile(hidden)).health = 37
        game.gameInfo.currentPlayer = rome.civID
        game.gameInfo.currentPlayerCiv = rome
        val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
        match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        val coordinator = MatchCoordinator(game.gameInfo, match, InMemoryCheckpointWriter())
        assertEquals("Precondition: only the near warrior is in sight", listOf(seen),
            coordinator.observe(rome.civID).visibleForeignUnits.map { HexCoord(it.x, it.y) })
        assertNull("Precondition: the Greek city tile is unknown", coordinator.inspectTile(rome.civID, HexCoord(-8, -8)))

        val targets = coordinator.legalActions(rome.civID, unitId = fighterId).attackTargets
        fun at(coord: HexCoord) = targets.single { it.targetX == coord.x && it.targetY == coord.y }
        val visible = at(seen)
        assertEquals("Warrior", visible.targetName)
        assertEquals(greece.civID, visible.targetOwnerCivId)
        assertEquals(100, visible.targetHealth)

        // Sweep geometry stays legal; the unseen defender is not described.
        val unseen = at(hidden)
        assertEquals("unit", unseen.targetKind)
        assertEquals("", unseen.targetName)
        assertEquals("", unseen.targetOwnerCivId)
        assertEquals(0, unseen.targetHealth)
        assertNull(unseen.expectedDamageToTarget)
        assertEquals("An unexplored city is not revealed", "unit", at(HexCoord(-8, -8)).targetKind)
    }
}
