package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Protocol bridge coverage (AI_AGENT_PLAN.md §5.1): every game.* entry point
 * through argument maps, receipt/error envelope shapes, unknown-command and
 * malformed-envelope rejection. The desktop host and MCP transport both sit
 * on this mapping, so it is tested once, here.
 */
@RunWith(BaseTestRunner::class)
class AgentEngineBridgeTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        lateinit var bridge: AgentEngineBridge
        lateinit var romeId: String
        var warriorId: Int = -1
        var scoutId: Int = -1
        var rev = 0

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(0, 0)).id
            scoutId = testGame.addUnit("Scout", rome, testGame.getTile(-1, 0)).id
            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            bridge = AgentEngineBridge(MatchCoordinator(testGame.gameInfo, match, InMemoryCheckpointWriter()))
        }

        fun act(
            actionId: String,
            type: String,
            args: Map<String, Any?>,
            expectedRevision: Int = rev
        ): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val receipt = bridge.act(mapOf(
                "protocolVersion" to 1,
                "actionId" to actionId,
                "expectedRevision" to expectedRevision,
                "type" to type,
                "args" to args)) as Map<String, Any?>
            rev = receipt["revision"] as Int
            return receipt
        }

        fun errOf(block: () -> Unit): EngineCallException {
            try {
                block()
                throw AssertionError("expected EngineCallException")
            } catch (ex: EngineCallException) {
                return ex
            }
        }
    }

    @Test
    fun `observe inspect rules legal_actions read through`() {
        val f = Fixture()
        @Suppress("UNCHECKED_CAST")
        val obs = f.bridge.observe(f.romeId) as Map<String, Any?>
        Assert.assertEquals(0, obs["revision"])
        Assert.assertEquals("m1", obs["matchId"])

        @Suppress("UNCHECKED_CAST")
        val inspected = f.bridge.inspect(f.romeId, f.warriorId) as Map<String, Any?>
        Assert.assertEquals(f.warriorId, (inspected["unitId"] as Number).toInt())
        Assert.assertNull(f.bridge.inspect(f.romeId, 424242))

        @Suppress("UNCHECKED_CAST")
        val rules = f.bridge.rules() as Map<String, Any?>
        Assert.assertEquals(BaseRuleset.Civ_V_Vanilla.fullName, rules["baseRuleset"])

        @Suppress("UNCHECKED_CAST")
        val legal = f.bridge.legalActions(f.romeId, unitId = f.scoutId, cityId = null) as Map<String, Any?>
        Assert.assertTrue((legal["moveTargets"] as List<*>).isNotEmpty())
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf { f.bridge.legalActions(f.romeId, null, null) }.code)
    }

    @Test
    fun `act envelopes apply with receipt shapes`() {
        val f = Fixture()
        val moved = f.act("a1", "unit.move", mapOf(
            "actorCivId" to f.romeId, "unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)))
        Assert.assertEquals("applied", moved["status"])
        Assert.assertEquals(1, moved["revision"])
        @Suppress("UNCHECKED_CAST")
        val position = (moved["result"] as Map<String, Any?>)["position"] as Map<String, Any?>
        Assert.assertEquals(1, position["x"])

        val ordered = f.act("a2", "unit.order", mapOf(
            "actorCivId" to f.romeId, "unitId" to f.warriorId, "order" to "FORTIFY"))
        Assert.assertEquals("applied", ordered["status"])

        // Replay returns the original receipt; conflicts and stale revisions map.
        val replay = f.act("a1", "unit.move", mapOf(
            "actorCivId" to f.romeId, "unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)), expectedRevision = 0)
        Assert.assertEquals(1, replay["revision"])
        Assert.assertEquals("ACTION_ID_CONFLICT", f.errOf {
            f.act("a1", "unit.move", mapOf(
                "actorCivId" to f.romeId, "unitId" to f.warriorId,
                "destination" to mapOf("x" to 2, "y" to 0)))
        }.code)
        val stale = f.errOf {
            f.act("a9", "unit.move", mapOf(
                "actorCivId" to f.romeId, "unitId" to f.warriorId,
                "destination" to mapOf("x" to 2, "y" to 0)), expectedRevision = 0)
        }
        Assert.assertEquals("STALE_STATE", stale.code)
        Assert.assertEquals(2, stale.currentRevision)
    }

    @Test
    fun `unknown commands and malformed envelopes rejected`() {
        val f = Fixture()
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf {
            f.bridge.act(mapOf(
                "protocolVersion" to 1, "actionId" to "x", "expectedRevision" to 0,
                "type" to "unit.teleport", "args" to mapOf("actorCivId" to f.romeId)))
        }.code)
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf {
            f.bridge.act(mapOf("protocolVersion" to 2, "actionId" to "x",
                "expectedRevision" to 0, "type" to "unit.move", "args" to emptyMap<String, Any?>()))
        }.code)
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf {
            f.bridge.act(mapOf("protocolVersion" to 1, "actionId" to "x",
                "expectedRevision" to 0, "type" to "unit.move",
                "args" to mapOf("actorCivId" to f.romeId, "unitId" to f.warriorId)))
        }.code) // missing destination
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf {
            f.bridge.observe("Atlantis")
        }.code)
    }

    @Test
    fun `notes and end_turn round-trip with revisions`() {
        val f = Fixture()
        val read = f.bridge.readNotes(f.romeId)
        Assert.assertEquals(0, read["notesRevision"])
        val written = f.bridge.updateNotes(f.romeId, "Rivers first.", 0)
        Assert.assertEquals(1, written["notesRevision"])
        Assert.assertEquals("STALE_STATE", f.errOf {
            f.bridge.updateNotes(f.romeId, "Stale.", 0)
        }.code)

        val ended = f.bridge.endTurn(f.romeId, "e1", f.rev)
        Assert.assertEquals(true, ended["turnEnded"])
        // Unknown-actor end-turn maps to invalid argument, not a crash.
        Assert.assertEquals("INVALID_ARGUMENT", f.errOf {
            f.bridge.endTurn("Atlantis", "e2", 99)
        }.code)
    }

    @Test
    fun `action_result looks up without resubmitting`() {
        val f = Fixture()
        f.act("a1", "unit.order", mapOf(
            "actorCivId" to f.romeId, "unitId" to f.warriorId, "order" to "FORTIFY"))
        @Suppress("UNCHECKED_CAST")
        val found = f.bridge.actionResult("a1") as Map<String, Any?>
        Assert.assertEquals("applied", found["status"])
        @Suppress("UNCHECKED_CAST")
        val missing = f.bridge.actionResult("nope") as Map<String, Any?>
        Assert.assertEquals("rejected", missing["status"])
    }
}
