package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offers must be discoverable through the same public observation the recipient agent reads. */
@RunWith(BaseTestRunner::class)
class AgentTradeObservationTest {
    private class Fixture {
        private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        var coordinator: MatchCoordinator
        val bridge get() = AgentEngineBridge(coordinator)
        val romeId: String
        val greeceId: String
        val aztecId: String
        val peaceDuration: Int

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(3)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val aztecs = game.addCiv(game.ruleset.nations["Aztecs"]!!, isPlayer = true)
            romeId = rome.civID
            greeceId = greece.civID
            aztecId = aztecs.civID
            rome.addGold(200)
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(1, 0))
            game.addUnit("Warrior", aztecs, game.getTile(-2, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            game.gameInfo.currentPlayer = romeId
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "trade-observation", timelineId = "t",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece, aztecs)) {
                match.controllers[civ.civID] = CivilizationController(civ.civID, ControllerKind.EXTERNAL_AGENT,
                    AgentProfile("profile-${civ.civID}", civ.civID, "codex", "m", null, "", 1))
            }
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
            peaceDuration = game.gameInfo.speed.peaceDealDuration
        }

        fun act(type: String, args: Map<String, Any?>, id: String = type) = bridge.act(mapOf(
            "protocolVersion" to 1, "actionId" to id, "expectedRevision" to coordinator.getStateRevision(),
            "actorCivId" to coordinator.getCurrentCivId(), "type" to type, "args" to args))

        fun propose(gold: Int, id: String = "offer-peace") {
            val treaty = mapOf("name" to "Peace Treaty", "type" to "Treaty", "amount" to 1)
            act("diplomacy.proposeTrade", mapOf("targetCivId" to greeceId,
                "ourOffers" to listOf(treaty, mapOf("name" to "Gold", "type" to "Gold", "amount" to gold)),
                "theirOffers" to listOf(treaty)), id)
        }

        fun reload() {
            coordinator = MatchCoordinator.loadFromJson(coordinator.saveToJson(), writer)
        }

        @Suppress("UNCHECKED_CAST")
        fun requests(civId: String) = (bridge.observe(civId) as Map<String, Any?>)["pendingTradeRequests"]
            as List<Map<String, Any?>>
    }

    @Test fun `recipient discovers saved peace terms through observe and accepts or declines on its real turn`() {
        for (accept in listOf(true, false)) {
            val f = Fixture()
            f.act("diplomacy.declareWar", mapOf("targetCivId" to f.greeceId))
            f.propose(25)
            f.bridge.endTurn(f.romeId, "end-rome", f.coordinator.getStateRevision())
            assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
            f.reload()

            val writes = f.writer.writeCount
            val request = f.requests(f.greeceId).single()
            assertEquals(f.romeId, request["requestingCivId"])
            assertEquals(1, (request["pendingRequestsFromCiv"] as Number).toInt())
            @Suppress("UNCHECKED_CAST")
            val ours = request["ourOffers"] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val theirs = request["theirOffers"] as List<Map<String, Any?>>
            assertEquals(listOf("Peace Treaty"), ours.map { it["name"] })
            assertEquals(listOf("Peace Treaty", "Gold"), theirs.map { it["name"] })
            for (treaty in listOf(ours.single(), theirs.first())) {
                assertEquals("Treaty", treaty["type"])
                assertEquals(1, (treaty["amount"] as Number).toInt())
                assertEquals(f.peaceDuration, (treaty["duration"] as Number).toInt())
            }
            val gift = theirs.last()
            assertEquals("Gold", gift["type"])
            assertEquals(25, (gift["amount"] as Number).toInt())
            assertEquals(-1, (gift["duration"] as Number).toInt())
            assertEquals(request, f.requests(f.greeceId).single())
            assertEquals(writes, f.writer.writeCount)
            assertTrue(f.requests(f.romeId).isEmpty())
            assertTrue(f.requests(f.aztecId).isEmpty())
            assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))

            val romeGold = f.coordinator.getGold(f.romeId)
            val greeceGold = f.coordinator.getGold(f.greeceId)
            val result = f.act("diplomacy.respondTrade",
                mapOf("requestingCivId" to request["requestingCivId"], "accept" to accept))
            assertEquals(mapOf("accepted" to accept), result["result"])
            assertTrue(f.requests(f.greeceId).isEmpty())
            assertEquals(if (accept) "Peace" else "War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
            assertEquals(if (accept) "Peace" else "War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
            assertEquals(romeGold - if (accept) 25 else 0, f.coordinator.getGold(f.romeId))
            assertEquals(greeceGold + if (accept) 25 else 0, f.coordinator.getGold(f.greeceId))
            f.reload()
            assertTrue(f.requests(f.greeceId).isEmpty())
        }
    }

    @Test fun `observation shows only the request respondTrade will answer and advances after declining`() {
        val f = Fixture()
        f.act("diplomacy.declareWar", mapOf("targetCivId" to f.greeceId))
        f.propose(25, "first")
        f.propose(75, "second")
        f.bridge.endTurn(f.romeId, "end-rome", f.coordinator.getStateRevision())
        f.reload()

        var request = f.coordinator.observe(f.greeceId).pendingTradeRequests.single()
        assertEquals(2, request.pendingRequestsFromCiv)
        assertEquals(25, request.theirOffers.single { it.name == "Gold" }.amount)
        f.act("diplomacy.respondTrade", mapOf("requestingCivId" to request.requestingCivId, "accept" to false), "decline-first")
        request = f.coordinator.observe(f.greeceId).pendingTradeRequests.single()
        assertEquals(1, request.pendingRequestsFromCiv)
        assertEquals(75, request.theirOffers.single { it.name == "Gold" }.amount)
        assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
    }

    @Test fun `trade observation terms are detached and survive DTO serialization`() {
        val f = Fixture()
        f.act("diplomacy.declareWar", mapOf("targetCivId" to f.greeceId))
        f.propose(25)
        val observed = f.coordinator.observe(f.greeceId)
        val serializer = json().apply { setUsePrototypes(false) }
        val restored = serializer.fromJson(ObservationDto::class.java, serializer.toJson(observed))
        assertEquals(f.romeId, restored.pendingTradeRequests.single().requestingCivId)
        assertEquals(25, restored.pendingTradeRequests.single().theirOffers.single { it.name == "Gold" }.amount)
        observed.pendingTradeRequests.single().theirOffers.single { it.name == "Gold" }.amount = 9000
        observed.pendingTradeRequests.single().ourOffers.clear()
        observed.pendingTradeRequests.clear()
        val untouched = f.coordinator.observe(f.greeceId).pendingTradeRequests.single()
        assertEquals(25, untouched.theirOffers.single { it.name == "Gold" }.amount)
        assertEquals("Peace Treaty", untouched.ourOffers.single().name)
    }
}
