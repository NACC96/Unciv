package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentWorkerOptionsTest {
    private class Fixture(terrain: String = "Grassland", vararg features: String) {
        val test = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val game get() = test.gameInfo
        val civ = test.addCiv(test.ruleset.nations["Rome"]!!, isPlayer = true)
        val position = HexCoord(2, 0)
        val tile get() = test.getTile(position)
        val worker: com.unciv.logic.map.mapunit.MapUnit
        val context get() = WorkerBuildContext(game, civ.civID, worker)
        init {
            game.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            test.makeHexagonalMap(4)
            test.setTileTerrainAndFeatures(position, terrain, *features)
            val city = test.addCity(civ, test.getTile(0, 0))
            test.addTileToCity(city, tile)
            tile.setOwningCity(city)
            if (!civ.tech.isResearched("Agriculture")) civ.tech.addTechnology("Agriculture")
            worker = test.addUnit("Worker", civ, tile)
        }
        fun options() = buildWorkerOptions(game, civ.civID, worker)
        fun names() = options().improvements.map { it.name }
    }
    private fun rejected(code: AgentCommandError = AgentCommandError.ILLEGAL_ACTION, action: () -> Unit): AgentCommandException {
        try { action(); fail("Expected rejection") } catch (e: AgentCommandException) { assertEquals(code, e.code); return e }
        error("unreachable")
    }

    @Test fun `all advertised options pass shared validator without any serialized changes`() {
        val f = Fixture()
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("The Wheel")
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        val options = f.options()
        assertTrue(options.improvements.any { it.name == "Farm" })
        options.improvements.forEach {
            requireWorkerBuildAllowed(f.context, f.game.ruleset.tileImprovements[it.name]!!)
            assertEquals(f.game.ruleset.tileImprovements[it.name]!!.getTurnsToBuild(f.civ, f.worker), it.turnsToBuild)
            assertNull(it.yieldDelta)
        }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
    }
    @Test fun `dry desert hills and forests follow installed terrain restrictions`() {
        val dry = Fixture("Desert")
        assertTrue("Farm" in dry.names())
        requireWorkerBuildAllowed(dry.context, dry.game.ruleset.tileImprovements["Farm"]!!)
        val hills = Fixture("Grassland", "Hill")
        hills.civ.tech.addTechnology("Mining")
        assertTrue("Mine" in hills.names())
        val forest = Fixture("Grassland", "Forest")
        forest.civ.tech.addTechnology("Mining")
        forest.civ.tech.addTechnology("Bronze Working")
        assertFalse("Farm" in forest.names())
        assertTrue("Remove Forest" in forest.names())
    }
    @Test fun `flood plains provide lawful freshwater farm options`() {
        val f = Fixture("Desert", "Flood plains")
        assertTrue("Farm" in f.names())
    }
    @Test fun `missing tech hidden resources and unique civilization requirements stay private`() {
        val f = Fixture()
        f.tile.setTileResource("Iron")
        f.tile.resourceAmount = 2
        assertFalse("Mine" in f.names())
        f.civ.tech.addTechnology("Mining")
        val hidden = f.names()
        assertFalse("Mine" in hidden)
        f.tile.setTileResource(null)
        assertEquals(hidden, f.names())
        f.tile.setTileResource("Iron")
        f.civ.tech.addTechnology("Iron Working")
        assertTrue("Mine" in f.names())
        val unique = f.test.createTileImprovement().apply {
            turnsToBuild = 4; terrainsCanBeBuiltOn = listOf("Land"); uniqueTo = "Greece"
        }
        assertFalse(unique.name in f.names())
        rejected { requireWorkerBuildAllowed(f.context, unique) }
    }
    @Test fun `unowned tile and city center reject ordinary worker builds`() {
        val f = Fixture()
        val farm = f.game.ruleset.tileImprovements["Farm"]!!
        rejected { requireWorkerBuildAllowed(f.context.copy(tile = f.test.getTile(-3, 0)), farm) }
        rejected { requireWorkerBuildAllowed(f.context.copy(tile = f.test.getTile(0, 0)), farm) }
    }
    @Test fun `water workers military and instant great improvements are not ordinary builds`() {
        val f = Fixture()
        f.test.setTileTerrain(f.position, "Coast")
        assertTrue(f.options().improvements.isEmpty())
        rejected { requireWorkerBuildAllowed(f.context, f.game.ruleset.tileImprovements["Farm"]!!) }
        val land = Fixture()
        val military = land.test.addUnit("Warrior", land.civ, land.tile)
        assertTrue(buildWorkerOptions(land.game, land.civ.civID, military).improvements.isEmpty())
        rejected { requireWorkerBuildAllowed(land.context.copy(unit = military), land.game.ruleset.tileImprovements["Farm"]!!) }
        rejected { requireWorkerBuildAllowed(land.context, land.game.ruleset.tileImprovements["Manufactory"]!!) }
    }
    @Test fun `active work is preserved and switching requires an explicit stop`() {
        val f = Fixture()
        f.tile.queueImprovement("Farm", 3)
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        assertEquals("Farm", f.options().workInProgress!!.name)
        assertEquals(3, f.options().workInProgress!!.turnsRemaining)
        assertTrue(f.options().canCancelWork)
        assertTrue(f.names().isEmpty())
        assertTrue(rejected { requireWorkerBuildAllowed(f.context, f.game.ruleset.tileImprovements["Farm"]!!) }
            .message!!.startsWith("ALREADY_IN_PROGRESS"))
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
        f.tile.stopWorkingOnImprovement()
        assertTrue("Farm" in f.names())
    }
    @Test fun `repair uses builder filter borders and engine turn costs`() {
        val f = Fixture()
        f.tile.setImprovementBasic("Farm")
        f.tile.improvementIsPillaged = true
        val turns = minOf(f.game.ruleset.tileImprovements[Constants.repair]!!.getTurnsToBuild(f.civ, f.worker),
            f.game.ruleset.tileImprovements["Farm"]!!.getTurnsToBuild(f.civ, f.worker))
        assertEquals(turns, requireWorkerRepairAllowed(f.context))
        assertEquals(turns, f.options().repair!!.turnsToRepair)
        val neutral = f.test.getTile(-3, 0)
        neutral.setImprovementBasic("Farm"); neutral.improvementIsPillaged = true
        rejected { requireWorkerRepairAllowed(f.context.copy(tile = neutral)) }
        val roadOnly = f.test.createBaseUnit("Civilian", "Can build [Road] improvements on tiles")
        val builder = f.test.addUnit(roadOnly.name, f.civ, f.test.getTile(1, 0))
        rejected { requireWorkerRepairAllowed(f.context.copy(unit = builder)) }
        f.tile.queueImprovement(Constants.repair, 2)
        assertNull(f.options().repair)
        assertTrue(rejected { requireWorkerRepairAllowed(f.context) }.message!!.startsWith("ALREADY_IN_PROGRESS"))
    }
    @Test fun `cancel rejects military foreign work and one-time markers`() {
        val f = Fixture()
        f.tile.queueImprovement("Farm", 3)
        val warrior = f.test.addUnit("Warrior", f.civ, f.tile)
        rejected { requireWorkerCancelAllowed(f.context.copy(unit = warrior)) }
        val neutral = f.test.getTile(-3, 0)
        neutral.queueImprovement("Farm", 3)
        rejected { requireWorkerCancelAllowed(f.context.copy(tile = neutral)) }
        f.tile.stopWorkingOnImprovement()
        f.tile.queueImprovement("Farm", -1)
        assertFalse(f.options().canCancelWork)
        rejected { requireWorkerCancelAllowed(f.context) }
    }
    @Test fun `road railroad and removal reflect technology and existing road`() {
        val f = Fixture()
        assertFalse("Road" in f.names())
        f.civ.tech.addTechnology("The Wheel")
        assertTrue("Road" in f.names())
        f.tile.roadStatus = RoadStatus.Road
        assertFalse("Road" in f.names())
        assertTrue("Remove Road" in f.names())
        f.civ.tech.addTechnology("Railroads")
        assertTrue("Railroad" in f.names())
        f.tile.roadStatus = RoadStatus.Railroad
        assertTrue("Remove Railroad" in f.names())
    }
    @Test fun `human clear then build projection succeeds without map memory or completion effects`() {
        val f = Fixture("Grassland", "Forest")
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("Bronze Working")
        f.civ.setLastSeenImprovement(f.position, "Lumber mill")
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        assertEquals(listOf("Remove Forest", "Farm"), validateWorkerImprovementQueue(f.context,
            listOf("Remove Forest", "Farm")).map { it.name })
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
        assertEquals("Lumber mill", f.civ.getLastSeenImprovement(f.position))
        assertTrue("Forest" in f.tile.terrainFeatures)
    }
    @Test fun `invalid later queued entry rejects atomically and binds conditional checks to projection`() {
        val f = Fixture("Grassland", "Forest")
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("Bronze Working")
        val needsForest = f.test.createTileImprovement("Can only be built on [Forest] tiles").apply {
            turnsToBuild = 3; terrainsCanBeBuiltOn = listOf("Land")
        }
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        rejected { validateWorkerImprovementQueue(f.context, listOf("Remove Forest", needsForest.name)) }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
        rejected(AgentCommandError.INVALID_ARGUMENT) { validateWorkerImprovementQueue(f.context, listOf("Remove Forest", "Imaginary Farm")) }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
    }
    @Test fun `queue starts once wakes sleeping worker and charges all entries once`() {
        val f = Fixture("Grassland", "Forest")
        val resource = f.test.createResource("Stockpiled")
        resource.setTransients(f.test.ruleset)
        f.civ.resourceStockpiles.add(resource.name, 10)
        val first = f.test.createTileImprovement("Costs [2] [${resource.name}]").apply {
            turnsToBuild = 3
        }
        f.test.ruleset.tileImprovements.remove(first.name)
        first.name = "Remove Forest"
        f.test.ruleset.tileImprovements[first.name] = first
        val second = f.test.createTileImprovement("Costs [3] [${resource.name}]").apply {
            turnsToBuild = 4; terrainsCanBeBuiltOn = listOf("Land")
        }
        f.worker.action = "Sleep"
        val turns = startValidatedWorkerQueue(f.context, listOf(first.name, second.name))
        assertEquals(first.getTurnsToBuild(f.civ, f.worker), turns)
        assertEquals(first.name, f.tile.improvementInProgress)
        val serializedTile = AgentJson.parse(json().apply { setUsePrototypes(false) }.toJson(f.tile)) as Map<*, *>
        assertEquals(2, (serializedTile["improvementQueue"] as List<*>).size)
        assertNull(f.worker.action)
        assertEquals(5, f.civ.getResourceAmount(resource.name))
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        rejected { startValidatedWorkerQueue(f.context, listOf(first.name, second.name)) }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
    }
    @Test fun `queue reserves cumulative prices and invalid later work charges nothing`() {
        val f = Fixture("Grassland", "Forest")
        val resource = f.test.createResource("Stockpiled")
        resource.setTransients(f.test.ruleset)
        f.civ.resourceStockpiles.add(resource.name, 4)
        val first = f.test.createTileImprovement("Costs [2] [${resource.name}]").apply {
            turnsToBuild = 3
        }
        f.test.ruleset.tileImprovements.remove(first.name)
        first.name = "Remove Forest"
        f.test.ruleset.tileImprovements[first.name] = first
        val second = f.test.createTileImprovement("Costs [3] [${resource.name}]").apply {
            turnsToBuild = 4; terrainsCanBeBuiltOn = listOf("Land")
        }
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        rejected { startValidatedWorkerQueue(f.context, listOf(first.name, second.name)) }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
        assertEquals(4, f.civ.getResourceAmount(resource.name))
    }
    @Test fun `projected builder conditionals cannot refer to the original forest tile`() {
        val f = Fixture("Grassland", "Forest")
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("Bronze Working")
        val base = f.test.createBaseUnit("Civilian", "Can build [Land] improvements on tiles <in [Forest] tiles>")
        val unit = f.test.addUnit(base.name, f.civ, f.test.getTile(1, 0))
        val context = f.context.copy(unit = unit)
        val before = json().apply { setUsePrototypes(false) }.toJson(f.game)
        rejected { validateWorkerImprovementQueue(context, listOf("Remove Forest", "Farm")) }
        assertEquals(before, json().apply { setUsePrototypes(false) }.toJson(f.game))
    }

    @Test fun `work progress remains visible after the worker spends its movement`() {
        val f = Fixture()
        f.tile.queueImprovement("Farm", 3)
        f.worker.currentMovement = 0f
        assertEquals(3, f.options().workInProgress!!.turnsRemaining)
        assertTrue(f.options().improvements.isEmpty())
        assertFalse(f.options().canCancelWork)
    }
    @Test fun `foreign owned road work cannot be cancelled despite outside border permission`() {
        val f = Fixture()
        val other = f.test.addCiv(f.test.ruleset.nations["Greece"]!!)
        val otherCity = f.test.addCity(other, f.test.getTile(-2, 0))
        val foreignTile = f.test.getTile(-1, 0)
        f.test.addTileToCity(otherCity, foreignTile)
        foreignTile.setOwningCity(otherCity)
        foreignTile.queueImprovement("Road", 2)
        rejected { requireWorkerCancelAllowed(f.context.copy(tile = foreignTile)) }
    }
    @Test fun `later queue prices bind to the cleared tile rather than original forest`() {
        val f = Fixture("Grassland", "Forest")
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("Bronze Working")
        val resource = f.test.createResource("Stockpiled")
        resource.setTransients(f.test.ruleset)
        f.civ.resourceStockpiles.add(resource.name, 10)
        val improvement = f.test.createTileImprovement("Costs [3] [${resource.name}] <in tiles without [Forest]>").apply {
            turnsToBuild = 3; terrainsCanBeBuiltOn = listOf("Land")
        }
        startValidatedWorkerQueue(f.context, listOf("Remove Forest", improvement.name))
        assertEquals(7, f.civ.getResourceAmount(resource.name))
        assertTrue("Forest" in f.tile.terrainFeatures)
    }

    @Test fun `clearing a pillaged lumber mill projects engine legality and prices without clearing live work`() {
        val f = Fixture("Grassland", "Forest")
        f.civ.tech.addTechnology("Mining")
        f.civ.tech.addTechnology("Bronze Working")
        f.tile.setImprovementBasic("Lumber mill")
        f.tile.improvementIsPillaged = true
        val resource = f.test.createResource("Stockpiled")
        resource.setTransients(f.test.ruleset)
        f.civ.resourceStockpiles.add(resource.name, 10)
        val build = f.test.createTileImprovement(
            "Only available <in tiles without [pillaged]>",
            "Costs [3] [${resource.name}] <in tiles without [pillaged]>",
            "Costs [7] [${resource.name}] <in [pillaged] tiles>"
        ).apply { turnsToBuild = 3; terrainsCanBeBuiltOn = listOf("Land") }
        val before = json().toJson(f.game)

        validateWorkerImprovementQueue(f.context, listOf("Remove Forest", build.name))
        assertEquals(before, json().toJson(f.game))
        assertEquals("Lumber mill", f.tile.improvement)
        assertTrue(f.tile.improvementIsPillaged)

        // Compare the projection with actual engine completion on the same rules/context.
        f.tile.setImprovement("Remove Forest", f.civ, f.worker)
        assertNull(f.tile.improvement)
        assertFalse(f.tile.improvementIsPillaged)
        assertFalse("Forest" in f.tile.terrainFeatures)
        requireWorkerBuildAllowed(f.context, build)
        val completedPrice = build.getStockpiledResourceRequirements(f.context.state)[resource.name]
        assertEquals(3, completedPrice)

        // Queueing charges the completed-state price, while the live feature/pillage remain.
        f.tile.setTerrainFeatures(listOf("Forest"))
        f.tile.setImprovementBasic("Lumber mill")
        f.tile.improvementIsPillaged = true
        startValidatedWorkerQueue(f.context, listOf("Remove Forest", build.name))
        assertEquals(10 - completedPrice, f.civ.getResourceAmount(resource.name))
        assertEquals("Remove Forest", f.tile.improvementInProgress)
        assertEquals("Lumber mill", f.tile.improvement)
        assertTrue(f.tile.improvementIsPillaged)
        assertTrue("Forest" in f.tile.terrainFeatures)
    }

}
