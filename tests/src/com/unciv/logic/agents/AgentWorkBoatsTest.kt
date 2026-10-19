package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Work Boats create sea-resource improvements through unit.build, as the in-game "Create [Fishing Boats]"
 * action does: at once, using up the boat, under the engine's own checks. Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class AgentWorkBoatsTest {

    private class Fixture(techs: List<String> = listOf("Sailing")) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val rome = run {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
        }
        val romeId: String = rome.civID
        /** Inside Rome's borders, next to the capital. */
        val ownFish = HexCoord(1, 0)
        /** Coast inside the borders without a resource. */
        val bareCoast = HexCoord(1, 1)
        /** Fish beyond the borders. */
        val farFish = HexCoord(3, 0)

        init {
            for (position in listOf(ownFish, bareCoast, farFish, HexCoord(2, 0))) testGame.setTileTerrain(position, "Coast")
            for (position in listOf(ownFish, farFish)) testGame.getTile(position).setTileResource("Fish")
            for (tech in techs) rome.tech.addTechnology(tech)
        }

        val cityId = testGame.addCity(rome, testGame.getTile(0, 0)).id
        val boatId = testGame.addUnit("Work Boats", rome, testGame.getTile(ownFish)).id
        val bareBoatId = testGame.addUnit("Work Boats", rome, testGame.getTile(bareCoast)).id
        val farBoatId = testGame.addUnit("Work Boats", rome, testGame.getTile(farFish)).id
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge

        init {
            testGame.gameInfo.currentPlayer = romeId
            testGame.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter())
            bridge = AgentEngineBridge(coordinator)
        }

        fun rev() = coordinator.getStateRevision()
        fun snapshot() = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun build(unitId: Int, improvement: String, id: String = "build-$unitId-$improvement") =
            BuildImprovementCommand(romeId, id, rev(), unitId, improvement)

        fun rejection(command: AgentCommand): String {
            val before = coordinator.saveToJson()
            try {
                coordinator.execute(command)
            } catch (ex: AgentCommandException) {
                assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
                assertEquals("A rejection leaves the game untouched", before, coordinator.saveToJson())
                return ex.message!!
            }
            fail("expected ILLEGAL_ACTION")
            throw AssertionError()
        }
    }

    @Test
    fun `legal actions offer the resource improvement that uses up the boat`() {
        val f = Fixture()
        val option = f.coordinator.legalActions(f.romeId, unitId = f.boatId).improvements.single()
        assertEquals("Fishing Boats", option.name)
        assertEquals("IMPROVEMENT", option.kind)
        assertEquals(0, option.turnsToBuild)
        assertTrue(option.connectsResource)
        assertTrue(option.consumesUnit)
        assertNull(option.replacesImprovement)

        @Suppress("UNCHECKED_CAST")
        val wire = (f.bridge.legalActions(f.romeId, f.boatId, null) as Map<String, Any?>)["improvements"] as List<Map<String, Any?>>
        assertEquals(true, wire.single()["consumesUnit"])

        assertTrue("No resource, no option", f.coordinator.legalActions(f.romeId, unitId = f.bareBoatId).improvements.isEmpty())
        assertTrue("Outside the borders, no option", f.coordinator.legalActions(f.romeId, unitId = f.farBoatId).improvements.isEmpty())
        assertTrue("Without Sailing, no option",
            Fixture(techs = emptyList()).let { it.coordinator.legalActions(it.romeId, unitId = it.boatId).improvements.isEmpty() })
    }

    @Test
    fun `unit build creates the improvement at once and uses up the boat`() {
        val f = Fixture()
        val before = f.rev()
        @Suppress("UNCHECKED_CAST")
        val receipt = f.bridge.act(mapOf("protocolVersion" to 1, "actionId" to "fish", "expectedRevision" to before,
            "actorCivId" to f.romeId, "type" to "unit.build", "args" to mapOf("unitId" to f.boatId, "improvementName" to "Fishing Boats")))
        assertEquals(before + 1, receipt["revision"])
        val result = receipt["result"] as Map<*, *>
        assertEquals(0, result["turnsToBuild"])
        assertTrue("The receipt reports the boat as gone", result.containsKey("unit") && result["unit"] == null)

        val game = f.snapshot()
        val tile = game.tileMap[f.ownFish]
        assertEquals("Fishing Boats", tile.improvement)
        assertFalse(tile.hasImprovementInProgress())
        assertTrue(game.getCivilization(f.romeId).units.getCivUnits().none { it.id == f.boatId })
        assertNull(f.coordinator.inspectUnit(f.romeId, f.boatId))
        assertEquals(listOf(f.bareBoatId, f.farBoatId).sorted(),
            game.getCivilization(f.romeId).units.getCivUnits().map { it.id }.toList().sorted())
    }

    @Test
    fun `rejections name what stops the boat`() {
        val f = Fixture()
        val wrongImprovement = f.rejection(f.build(f.boatId, "Farm"))
        assertEquals("Farm does not improve Fish; it is improved by Fishing Boats", wrongImprovement)

        val bare = f.rejection(f.build(f.bareBoatId, "Fishing Boats"))
        assertEquals("Unit cannot create improvements here: Work Boats must stand on a water tile with a resource", bare)

        val outside = f.rejection(f.build(f.farBoatId, "Fishing Boats"))
        assertTrue(outside, outside.startsWith("Improvement cannot be built on this tile now: Fishing Boats "))
        assertTrue(outside, outside.contains("needs a tile inside your territory (this tile is unowned)"))

        val noSailing = Fixture(techs = emptyList())
        val tech = noSailing.rejection(noSailing.build(noSailing.boatId, "Fishing Boats"))
        assertTrue(tech, tech.contains("requires Sailing"))

        val repair = f.rejection(RepairCommand(f.romeId, "repair", f.rev(), f.boatId))
        assertTrue(repair, repair.contains("Work Boats only creates improvements on water resources, using itself up"))
    }

    @Test
    fun `a spent boat cannot build again and the action replays without a second boat`() {
        val f = Fixture()
        val command = f.build(f.boatId, "Fishing Boats", id = "fish-once")
        val first = f.coordinator.execute(command) as ImprovementStartedResult
        assertEquals(0, first.turnsToBuild)
        val revision = f.rev()
        // The same actionId replays its receipt instead of applying again.
        val replay = f.coordinator.execute(command) as ImprovementStartedResult
        assertEquals(first.receipt.afterRevision, replay.receipt.afterRevision)
        assertEquals(revision, f.rev())
        assertFalse(first.receipt.unitSnapshot!!.present)
        try {
            f.coordinator.execute(f.build(f.boatId, "Fishing Boats", id = "fish-again"))
            fail("A used-up boat must not act again")
        } catch (ex: AgentCommandException) {
            assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }
}
