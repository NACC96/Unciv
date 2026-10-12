package com.unciv.logic.agents

import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises public wire requests against real coordinator legality and turn decisions. */
@RunWith(BaseTestRunner::class)
class AgentExtendedBridgeTest {
    private class Fixture(voteOpen: Boolean = false) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge
        val romeId: String
        val greeceId: String
        val aztecId: String
        val unitId: Int
        val cityId: String
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(5)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val aztecs = game.addCiv(game.ruleset.nations["Aztecs"]!!)
            romeId = rome.civID; greeceId = greece.civID; aztecId = aztecs.civID
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            unitId = game.addUnit("Warrior", rome, game.getTile(0, 0)).id
            game.addUnit("Warrior", greece, game.getTile(3, 0))
            game.addUnit("Warrior", aztecs, game.getTile(-4, 0))
            cityId = game.addCity(rome, game.getTile(-2, 0)).id
            game.gameInfo.currentPlayer = romeId; game.gameInfo.currentPlayerCiv = rome
            if (voteOpen) rome.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, 0)
            val match = AgentMatchState(matchId = "extended", timelineId = "t",
                turnPhase = if (voteOpen) TurnPhase.READY_TO_START else TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN)
            match.controllers[greeceId] = CivilizationController(greeceId, ControllerKind.HUMAN)
            match.controllers[aztecId] = CivilizationController(aztecId, ControllerKind.BUILTIN_AI)
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
            if (voteOpen) coordinator.beginAwaitingTurn()
            bridge = AgentEngineBridge(coordinator)
        }
        fun snapshot() = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        fun act(type: String, args: Map<String, Any?>, actor: String = coordinator.getCurrentCivId(),
                id: String = type, revision: Int = coordinator.getStateRevision()) = bridge.act(mapOf(
            "protocolVersion" to 1, "actionId" to id, "expectedRevision" to revision,
            "actorCivId" to actor, "type" to type, "args" to args))
        fun failure(context: String = "", block: () -> Unit): EngineCallException {
            try { block(); fail("$context: Expected rejection") } catch (ex: EngineCallException) { return ex }
            throw AssertionError("unreachable")
        }
    }

    @Test fun `wire peace proposal reports a queue and only recipient acceptance reports peace`() {
        val f = Fixture()
        f.act("diplomacy.declareWar", mapOf("targetCivId" to f.greeceId))
        val before = f.coordinator.saveToJson()
        val error = f.failure {
            f.act("diplomacy.makePeace", mapOf("targetCivId" to f.greeceId))
        }
        assertEquals("ILLEGAL_ACTION", error.code)
        assertTrue(error.message!!.contains("diplomacy.proposeTrade"))
        assertEquals(before, f.coordinator.saveToJson())
        val treaty = mapOf("name" to "Peace Treaty", "type" to "Treaty", "amount" to 1)
        val proposed = f.act("diplomacy.proposeTrade", mapOf(
            "targetCivId" to f.greeceId, "ourOffers" to listOf(treaty), "theirOffers" to listOf(treaty)))
        assertEquals("applied", proposed["status"])
        assertEquals(mapOf("pendingRequestsWithTarget" to 1), proposed["result"])
        assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        val responseArgs = mapOf("requestingCivId" to f.romeId, "accept" to true)
        assertEquals("NOT_YOUR_TURN", f.failure {
            f.act("diplomacy.respondTrade", responseArgs, actor = f.greeceId, id = "early-response")
        }.code)
        f.bridge.endTurn(f.romeId, "end-rome", f.coordinator.getStateRevision())
        assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        val accepted = f.act("diplomacy.respondTrade", responseArgs)
        assertEquals("applied", accepted["status"])
        assertEquals(mapOf("accepted" to true), accepted["result"])
        assertEquals("Peace", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        assertEquals("Peace", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
    }

    @Test fun `wire demand response clears mandatory decision and permits the next turn`() {
        val f = Fixture()
        val issued = f.act("diplomacy.demand", mapOf("targetCivId" to f.greeceId, "demandName" to "DoNotSettleNearUs"))
        assertEquals(mapOf("issued" to true), issued["result"])
        f.bridge.endTurn(f.romeId, "end-rome", f.coordinator.getStateRevision())
        assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        assertTrue(f.coordinator.getPendingDecisions(f.greeceId).any { it.decisionType == "demand-response" })
        assertEquals("DECISIONS_PENDING", f.failure {
            f.bridge.endTurn(f.greeceId, "blocked", f.coordinator.getStateRevision())
        }.code)
        val before = f.coordinator.saveToJson()
        assertEquals("INVALID_ARGUMENT", f.failure {
            f.act("diplomacy.respondDemand", mapOf("requesterCivId" to f.romeId, "demandName" to "DoNotSettleNearUs"))
        }.code)
        assertEquals(before, f.coordinator.saveToJson())
        val args = mapOf("requesterCivId" to f.romeId, "demandName" to "DoNotSettleNearUs", "accept" to true)
        val revision = f.coordinator.getStateRevision()
        val accepted = f.act("diplomacy.respondDemand", args)
        assertEquals("applied", accepted["status"])
        assertEquals(mapOf("accepted" to true), accepted["result"])
        assertTrue((accepted["pendingDecisions"] as List<*>).isEmpty())
        assertEquals(accepted, f.act("diplomacy.respondDemand", args, revision = revision))
        val snapshot = f.snapshot()
        assertTrue(snapshot.getCivilization(f.romeId).getDiplomacyManager(f.greeceId)!!
            .hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        f.bridge.endTurn(f.greeceId, "end-greece", f.coordinator.getStateRevision())
        assertEquals(f.romeId, f.coordinator.getCurrentCivId())
    }

    @Test fun `wire vote and abstention clear their mandatory decisions`() {
        for (abstain in listOf(false, true)) {
            val f = Fixture(voteOpen = true)
            assertTrue(f.coordinator.getPendingDecisions(f.romeId).any { it.decisionType == "diplomatic-vote" })
            assertEquals("DECISIONS_PENDING", f.failure {
                f.bridge.endTurn(f.romeId, "blocked", f.coordinator.getStateRevision())
            }.code)
            val before = f.coordinator.saveToJson()
            assertEquals("INVALID_ARGUMENT", f.failure {
                f.act("diplomacy.castVote", mapOf("targetCivId" to 17))
            }.code)
            assertEquals(before, f.coordinator.saveToJson())
            val args = if (abstain) emptyMap<String, Any?>() else mapOf("targetCivId" to f.greeceId)
            val cast = f.act("diplomacy.castVote", args)
            assertEquals("applied", cast["status"])
            assertEquals(mapOf("targetCivId" to if (abstain) null else f.greeceId), cast["result"])
            assertTrue((cast["pendingDecisions"] as List<*>).isEmpty())
            assertEquals("ILLEGAL_ACTION", f.failure { f.act("diplomacy.castVote", args, id = "second-vote") }.code)
            f.bridge.endTurn(f.romeId, "end", f.coordinator.getStateRevision())
            assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        }
    }

    @Test fun `all extended actions reach real command legality instead of unknown dispatch`() {
        data class Case(val type: String, val args: (Fixture) -> Map<String, Any?>, val code: String, val reason: String)
        val cases = listOf(
            Case("diplomacy.denounce", { mapOf("targetCivId" to "missing") }, "INVALID_ARGUMENT", "Unknown civilization"),
            Case("diplomacy.demand", { mapOf("targetCivId" to it.greeceId, "demandName" to "not-a-demand") }, "INVALID_ARGUMENT", "Unknown demand"),
            Case("diplomacy.respondDemand", { mapOf("requesterCivId" to it.greeceId, "demandName" to "DoNotSettleNearUs", "accept" to false) }, "INVALID_ARGUMENT", "No such demand pending"),
            Case("diplomacy.castVote", { mapOf("targetCivId" to it.greeceId) }, "ILLEGAL_ACTION", "No diplomatic vote is open"),
            Case("city.rename", { mapOf("cityId" to "missing", "newName" to "New City") }, "INVALID_ARGUMENT", "Unknown or foreign city"),
            Case("city.annex", { mapOf("cityId" to it.cityId) }, "ILLEGAL_ACTION", "City is not a puppet"),
            Case("city.raze", { mapOf("cityId" to "missing", "raze" to false) }, "ILLEGAL_ACTION", "No such city under your control"),
            Case("unit.stopImprovement", { mapOf("unitId" to it.unitId) }, "ILLEGAL_ACTION", "Unit cannot work on improvements now"),
            Case("unit.swap", { mapOf("unitId" to it.unitId, "destination" to mapOf("x" to 100, "y" to 100)) }, "ILLEGAL_ACTION", "Destination is not a tile"),
            Case("unit.rename", { mapOf("unitId" to -1, "newName" to "Named Unit") }, "ILLEGAL_ACTION", "No such visible unit"),
            Case("unit.connectRoad", { mapOf("unitId" to it.unitId, "destination" to mapOf("x" to 100, "y" to 100)) }, "ILLEGAL_ACTION", "Destination is not a tile"),
            Case("citystate.tribute", { mapOf("cityStateId" to it.greeceId, "demandWorker" to true) }, "INVALID_ARGUMENT", "Not a city-state"),
            Case("citystate.protection", { mapOf("cityStateId" to it.greeceId, "pledge" to true) }, "INVALID_ARGUMENT", "Not a city-state"),
            Case("citystate.marry", { mapOf("cityStateId" to it.greeceId) }, "ILLEGAL_ACTION", "Civilization cannot marry city-states"),
            Case("diplomacy.retractTrade", { mapOf("targetCivId" to it.greeceId) }, "ILLEGAL_ACTION", "No pending offer to retract"),
            Case("diplomacy.respondProtectorAlert", { mapOf("cityStateId" to it.greeceId, "attackerCivId" to it.aztecId, "choice" to "BREAK_PLEDGE", "alliedCityState" to true) }, "ILLEGAL_ACTION", "No protection pledge"),
            Case("diplomacy.respondFriendshipOffer", { mapOf("requesterCivId" to "missing", "accept" to false) }, "INVALID_ARGUMENT", "Unknown civilization"),
            Case("unit.gift", { mapOf("unitId" to -1) }, "ILLEGAL_ACTION", "No such visible unit")
        )
        for (case in cases) {
            val f = Fixture()
            val before = f.coordinator.saveToJson()
            val error = f.failure(case.type) { f.act(case.type, case.args(f)) }
            assertEquals(case.type, case.code, error.code)
            assertTrue("${case.type}: ${error.message}", error.message!!.contains(case.reason))
            assertEquals(case.type, before, f.coordinator.saveToJson())
            assertEquals(0, f.writer.writeCount)
        }
    }

    @Test fun `response and toggle booleans must be explicit and typed before mutation`() {
        val f = Fixture()
        val cases = listOf(
            Triple("diplomacy.respondDemand", mapOf("requesterCivId" to f.greeceId, "demandName" to "DoNotSettleNearUs"), "accept"),
            Triple("diplomacy.respondFriendshipOffer", mapOf("requesterCivId" to f.greeceId), "accept"),
            Triple("city.raze", mapOf("cityId" to f.cityId), "raze"),
            Triple("citystate.tribute", mapOf("cityStateId" to f.greeceId), "demandWorker"),
            Triple("citystate.protection", mapOf("cityStateId" to f.greeceId), "pledge")
        )
        val before = f.coordinator.saveToJson()
        for ((type, baseArgs, field) in cases) {
            for (args in listOf(baseArgs, baseArgs + (field to "false"))) {
                val error = f.failure(type) { f.act(type, args) }
                assertEquals(type, "INVALID_ARGUMENT", error.code)
                assertTrue(error.message!!.contains(field))
                assertEquals(type, before, f.coordinator.saveToJson())
            }
        }
        assertEquals(0, f.writer.writeCount)
    }

    @Test fun `extended receipt and known tile survive format two save reload and replay`() {
        val f = Fixture()
        val revision = f.coordinator.getStateRevision()
        val args = mapOf("unitId" to f.unitId, "newName" to "Legion")
        val receipt = f.act("unit.rename", args)
        val knownTile = f.bridge.inspectTile(f.romeId, mapOf("x" to 0, "y" to 0))
        assertNotNull(knownTile)
        val saved = f.coordinator.saveToJson()
        val rawBundle = com.badlogic.gdx.utils.JsonReader().parse(saved)
        assertEquals(AGENT_MATCH_FORMAT_VERSION, rawBundle.getInt("bundleVersion"))
        assertEquals(AGENT_MATCH_FORMAT_VERSION,
            com.badlogic.gdx.utils.JsonReader().parse(rawBundle.getString("matchJson")).getInt("formatVersion"))
        val writer = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(saved, writer)
        val bridge = AgentEngineBridge(restored)
        assertEquals(knownTile, bridge.inspectTile(f.romeId, mapOf("x" to 0, "y" to 0)))
        assertEquals(receipt, bridge.act(mapOf(
            "protocolVersion" to 1, "actionId" to "unit.rename", "expectedRevision" to revision,
            "actorCivId" to f.romeId, "type" to "unit.rename", "args" to args)))
        assertEquals(f.coordinator.getStateRevision(), restored.getStateRevision())
        assertEquals(0, writer.writeCount)
    }

    @Test fun `rename arguments reach persisted entities and return sanitized results`() {
        val f = Fixture()
        assertEquals(mapOf("name" to "Legion"), f.act("unit.rename", mapOf("unitId" to f.unitId, "newName" to " Legion "))["result"])
        assertEquals(mapOf("name" to "Roma"), f.act("city.rename", mapOf("cityId" to f.cityId, "newName" to " Roma "))["result"])
        val state = f.snapshot()
        assertEquals("Legion", state.getCivilization(f.romeId).units.getCivUnits().first { it.id == f.unitId }.instanceName)
        assertEquals("Roma", state.getCities().first { it.id == f.cityId }.name)
        assertEquals(mapOf("name" to ""), f.act("unit.rename", mapOf("unitId" to f.unitId, "newName" to ""), id = "clear-name")["result"])
        assertNull(f.snapshot().getCivilization(f.romeId).units.getCivUnits().first { it.id == f.unitId }.instanceName)
    }
}
