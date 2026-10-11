package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real coordinator/bridge workflows; no provider, profile directory or running match. */
@RunWith(BaseTestRunner::class)
class AgentMapAcceptanceTest {
    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val chinaId: String
        val greeceId: String
        val workers: List<Int>
        val scoutId: Int
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge
        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(7, "Grassland")
            val china = testGame.addCiv(testGame.ruleset.nations["China"]!!, isPlayer = true)
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            chinaId = china.civID
            greeceId = greece.civID
            china.tech.addTechnology("Agriculture")
            testGame.addCity(china, testGame.getTile(0, 0))
            testGame.addCity(greece, testGame.getTile(6, 0))
            workers = listOf(HexCoord(1, 0), HexCoord(0, 1)).map {
                testGame.addUnit("Worker", china, testGame.getTile(it)).id
            }
            scoutId = testGame.addUnit("Scout", china, testGame.getTile(-4, 0)).id
            testGame.gameInfo.currentPlayer = chinaId
            val match = AgentMatchState(matchId = "map-acceptance", timelineId = "initial",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civId in listOf(chinaId, greeceId)) match.controllers[civId] = CivilizationController(
                civId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("profile-$civId", civId, "codex", "gpt-6-luna", "xhigh", "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
            bridge = AgentEngineBridge(coordinator)
        }
    }

    @Test fun `two workers inspect choose listed builds and keep progress through save reload`() {
        val f = Fixture()
        for ((index, workerId) in f.workers.withIndex()) {
            val unit = f.coordinator.inspectUnit(f.chinaId, workerId)!!
            assertNotNull(unit.tile)
            assertEquals("Grassland", unit.tile!!.terrain)
            val legal = f.coordinator.legalActions(f.chinaId, unitId = workerId)
            val farm = legal.improvements.single { it.name == "Farm" }
            val started = f.coordinator.execute(BuildImprovementCommand(f.chinaId, "farm-$index",
                legal.revision, workerId, farm.name)) as ImprovementStartedResult
            assertEquals(farm.turnsToBuild, started.turnsToBuild)
            val work = f.coordinator.legalActions(f.chinaId, unitId = workerId).workInProgress!!
            assertEquals("Farm", work.name)
            assertEquals(started.turnsToBuild, work.turnsRemaining)
            assertFalse(f.coordinator.legalActions(f.chinaId, unitId = workerId).improvements.any { it.name == "Farm" })
            val before = f.coordinator.saveToJson()
            try {
                f.coordinator.execute(BuildImprovementCommand(f.chinaId, "repeat-$index",
                    f.coordinator.getStateRevision(), workerId, farm.name))
                fail("Fresh command must not restart ongoing work")
            } catch (error: AgentCommandException) {
                assertEquals(AgentCommandError.ILLEGAL_ACTION, error.code)
                assertTrue(error.message!!.contains("ALREADY_IN_PROGRESS"))
            }
            assertEquals(before, f.coordinator.saveToJson())
            val replay = f.coordinator.execute(BuildImprovementCommand(f.chinaId, "farm-$index",
                legal.revision, workerId, farm.name)) as ImprovementStartedResult
            assertEquals(started.receipt.afterRevision, replay.receipt.afterRevision)
        }
        val initialRemaining = f.coordinator.legalActions(f.chinaId, unitId = f.workers.first()).workInProgress!!.turnsRemaining
        f.coordinator.execute(EndTurnCommand(f.chinaId, "china-end", f.coordinator.getStateRevision()))
        f.coordinator.execute(EndTurnCommand(f.greeceId, "greece-end", f.coordinator.getStateRevision()))
        val afterTurn = f.coordinator.legalActions(f.chinaId, unitId = f.workers.first()).workInProgress!!
        assertTrue(afterTurn.turnsRemaining < initialRemaining)
        val loaded = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        assertEquals(afterTurn.turnsRemaining,
            loaded.legalActions(f.chinaId, unitId = f.workers.first()).workInProgress!!.turnsRemaining)
        assertEquals(f.coordinator.inspectTile(f.chinaId, HexCoord(1, 0))!!.toWire(),
            loaded.inspectTile(f.chinaId, HexCoord(1, 0))!!.toWire())
    }

    @Test fun `bridge pages maps without class tags or mutations and binds cursors`() {
        val f = Fixture()
        val saved = f.coordinator.saveToJson()
        @Suppress("UNCHECKED_CAST")
        val page = f.bridge.map(f.chinaId, mapOf("limit" to 1)) as Map<String, Any?>
        assertEquals(2, page["observationVersion"])
        assertEquals(1, (page["tiles"] as List<*>).size)
        assertFalse(AgentJson.stringify(page).contains("com.unciv"))
        val cursor = page["nextCursor"] as String
        f.bridge.map(f.chinaId, mapOf("limit" to 1, "cursor" to cursor))
        f.bridge.rules(mapOf("section" to "improvements", "name" to "Farm"))
        f.bridge.inspect(f.chinaId, f.workers.first())
        assertEquals(saved, f.coordinator.saveToJson())
        assertNull(f.bridge.inspectTile(f.chinaId, mapOf("x" to 6, "y" to -4)))
        assertNull(f.bridge.inspectTile(f.chinaId, mapOf("x" to 5000, "y" to 5000)))
        try {
            f.bridge.map(f.greeceId, mapOf("limit" to 1, "cursor" to cursor))
            fail("Cross-civilization cursor accepted")
        } catch (error: EngineCallException) { assertEquals("INVALID_ARGUMENT", error.code) }
        try {
            f.bridge.inspectTile(f.chinaId, mapOf("x" to 0.5, "y" to 0))
            fail("Fractional tile coordinate accepted")
        } catch (error: EngineCallException) { assertEquals("INVALID_ARGUMENT", error.code) }
        f.coordinator.execute(BuildImprovementCommand(f.chinaId, "build", 0, f.workers.first(), "Farm"))
        try {
            f.bridge.map(f.chinaId, mapOf("limit" to 1, "cursor" to cursor))
            fail("Stale map page accepted")
        } catch (error: EngineCallException) {
            assertEquals("STALE_STATE", error.code)
            assertEquals(f.coordinator.getStateRevision(), error.currentRevision)
        }
    }

    @Test fun `scout keeps last seen geography after moving out of sight and reload`() {
        val f = Fixture()
        val previouslySeen = HexCoord(-3, 0)
        val before = f.coordinator.inspectTile(f.chinaId, previouslySeen)!!
        assertEquals(TileKnowledgeVisibility.VISIBLE, before.visibility)
        f.coordinator.execute(MoveUnitCommand(f.chinaId, "scout-west", 0, f.scoutId, HexCoord(-6, 0)))
        val remembered = f.coordinator.inspectTile(f.chinaId, previouslySeen)!!
        assertEquals(TileKnowledgeVisibility.REMEMBERED, remembered.visibility)
        assertEquals(before.terrain, remembered.terrain)
        val loaded = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        assertEquals(remembered.toWire(), loaded.inspectTile(f.chinaId, previouslySeen)!!.toWire())
        val raw = com.badlogic.gdx.utils.JsonReader().parse(loaded.saveToJson())
        assertEquals(2, raw.getInt("bundleVersion"))
        assertEquals(2, com.badlogic.gdx.utils.JsonReader().parse(raw.getString("matchJson")).getInt("formatVersion"))
    }

    @Test fun `bridge can explicitly stop worker progress before assigning another build`() {
        val f = Fixture()
        val worker = f.workers.first()
        f.coordinator.execute(BuildImprovementCommand(f.chinaId, "build-first", 0, worker, "Farm"))
        val stopped = f.bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to f.chinaId,
            "actionId" to "stop", "expectedRevision" to 1, "type" to "unit.stopImprovement",
            "args" to mapOf("unitId" to worker))) as Map<*, *>
        assertEquals("applied", stopped["status"])
        assertNull(f.coordinator.legalActions(f.chinaId, unitId = worker).workInProgress)
        assertTrue(f.coordinator.legalActions(f.chinaId, unitId = worker).improvements.any { it.name == "Farm" })
    }
}
