package com.unciv.logic.agents

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A prepared campaign position, played through the real public command bridge to a recorded win. */
@RunWith(BaseTestRunner::class)
class AgentVictoryCommandFlowTest {
    private fun Any?.objectMap(): Map<*, *> = this as Map<*, *>
    private fun Map<*, *>.objectAt(key: String) = this[key].objectMap()
    private fun Map<*, *>.objectsAt(key: String) = (this[key] as List<*>).map { it.objectMap() }
    private fun Map<*, *>.numberAt(key: String) = (this[key] as Number).toInt()

    @Test fun `observed legal campaign commands capture capitals and record only domination victory`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        game.gameInfo.gameParameters.maxTurns = 500
        game.makeHexagonalMap(7)
        val rome = game.addCiv(game.ruleset.nations.getValue("Rome"), isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations.getValue("Greece"), isPlayer = true)
        val romanCapital = game.addCity(rome, game.getTile(-3, 0))
        val greekCapital = game.addCity(greece, game.getTile(2, 0))
        // Half of the native 200 HP, representing an existing siege. The peaceful turn below
        // lets native healing run; several real ranged attacks must precede the melee capture.
        greekCapital.health = 100
        val survivingGreekCity = game.addCity(greece, game.getTile(5, 3))
        val melee = game.addUnit("Pikeman", rome, game.getTile(0, 0))
        val firingPositions = greekCapital.getCenterTile().getTilesAtDistance(2)
            .filter { it.getOwner() == null && it.militaryUnit == null }
            .sortedWith(compareBy({ it.position.x }, { it.position.y })).take(6).toList()
        assertEquals(6, firingPositions.size)
        for (tile in firingPositions) game.addUnit("Crossbowman", rome, tile)
        rome.addGold(1000)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        for (civ in listOf(rome, greece)) {
            civ.tech.techsToResearch.add("Pottery")
            for (city in civ.cities) {
                city.cityConstructions.constructionQueue.clear()
                city.cityConstructions.constructionQueue.add("Monument")
            }
        }
        game.gameInfo.turns = 41 // Native battle randomness is turn/location based.
        game.gameInfo.currentPlayer = rome.civID
        game.gameInfo.currentPlayerCiv = rome
        val match = AgentMatchState(matchId = "victory-command-flow", timelineId = "timeline",
            turnPhase = TurnPhase.AWAITING_DECISIONS)
        for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
            civ.civID, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p-${civ.civID}", civ.civName, "codex", "test", null, "", 1))
        // Complete source setup before the coordinator takes its owned snapshot. Every subsequent
        // gameplay mutation below goes through the bridge, including both actors' end turns.
        val coordinator = MatchCoordinator(game.gameInfo, match, InMemoryCheckpointWriter())
        val bridge = AgentEngineBridge(coordinator)
        var actionNumber = 0
        fun observe(actor: String = rome.civID) = bridge.observe(actor).objectMap()
        fun act(type: String, args: Map<String, Any?>): Map<*, *> {
            val observed = observe()
            val receipt = bridge.act(mapOf(
                "protocolVersion" to 1, "actorCivId" to rome.civID,
                "actionId" to "campaign-${actionNumber++}",
                "expectedRevision" to observed.numberAt("revision"), "type" to type, "args" to args))
            assertEquals("applied", receipt["status"])
            assertEquals(observed.numberAt("revision") + 1, receipt["revision"])
            return receipt
        }
        fun endTurn(actor: String = rome.civID) = bridge.endTurn(actor,
            "end-${actionNumber++}", observe(actor).numberAt("revision"))
        fun legal(unitId: Int) = bridge.legalActions(rome.civID, unitId, null).objectMap()
        fun recordedVictory() = coordinator.deserializeForPublication(
            coordinator.serializeCommittedGameForPublication()).victoryData
        fun assertNoOtherRoute(victory: Map<*, *>) {
            val otherRoutes = victory.objectsAt("available").filter { it["name"] != "Domination" }
            assertEquals(setOf("Scientific", "Cultural", "Diplomatic", "Time"),
                otherRoutes.map { it["name"] }.toSet())
            assertTrue(otherRoutes.all { it["completed"] == false })
        }

        val initial = observe()
        val initialVictory = initial.objectAt("victory")
        assertEquals(1, initialVictory.objectAt("domination").numberAt("ownedOriginalCapitals"))
        assertEquals(2, initialVictory.objectAt("domination").numberAt("requiredOriginalCapitals"))
        assertNull(initialVictory["achieved"])
        assertNoOtherRoute(initialVictory)
        assertEquals(true, initial.objectsAt("ownCities").single()["isOriginalCapital"])
        val target = initial.objectsAt("visibleForeignCities").single {
            it["isOriginalCapital"] == true && it["foundingCivId"] == greece.civID
        }
        assertEquals(greekCapital.id, target["cityId"])
        assertEquals(100, target.numberAt("health"))
        val targetX = target.numberAt("x")
        val targetY = target.numberAt("y")
        assertTrue(legal(melee.id).objectsAt("attackTargets").isEmpty())

        // A successful game.end_turn alone must never be interpreted as winning the match.
        assertEquals(true, endTurn()["turnEnded"])
        assertNull(recordedVictory())
        assertEquals(TurnPhase.AWAITING_DECISIONS, coordinator.getTurnPhase())
        assertEquals(greece.civID, coordinator.getCurrentCivId())
        assertEquals(true, endTurn(greece.civID)["turnEnded"])
        assertNull(observe().objectAt("victory")["achieved"])
        val rival = observe().objectsAt("rivals").single { it["civId"] == greece.civID }
        assertEquals("Peace", rival["status"])
        act("diplomacy.declareWar", mapOf("targetCivId" to rival["civId"]))
        assertEquals("War", observe().objectsAt("rivals").single { it["civId"] == greece.civID }["status"])

        // March from neutral ground to the observed legal tile beside the observed capital.
        val move = legal(melee.id).objectsAt("moveTargets").single {
            it.numberAt("x") == targetX - 1 && it.numberAt("y") == targetY
        }
        act("unit.move", mapOf("unitId" to melee.id,
            "destination" to mapOf("x" to move["x"], "y" to move["y"])))
        var rangedAttacks = 0
        val rangedUnits = observe().objectsAt("ownUnits").filter { it["name"] == "Crossbowman" }
        for (unit in rangedUnits) {
            val city = observe().objectsAt("visibleForeignCities").single { it["cityId"] == target["cityId"] }
            if (city.numberAt("health") == 1) break
            val option = legal(unit.numberAt("unitId")).objectsAt("attackTargets").single {
                it.numberAt("targetX") == targetX && it.numberAt("targetY") == targetY
            }
            assertEquals(greece.civID, option["targetOwnerCivId"])
            val receipt = act("unit.attack", mapOf("attackerUnitId" to unit["unitId"],
                "target" to mapOf("x" to option["targetX"], "y" to option["targetY"])))
            assertTrue(receipt.objectAt("result").numberAt("attackerDamageDealt") > 0)
            assertTrue(receipt.objectsAt("pendingDecisions").isEmpty())
            rangedAttacks++
        }
        assertTrue("The capital must withstand at least two ranged attacks", rangedAttacks >= 2)
        val softened = observe().objectsAt("visibleForeignCities").single { it["cityId"] == target["cityId"] }
        assertEquals(1, softened.numberAt("health"))
        assertEquals(greece.civID, softened["ownerCivId"])
        assertNull(observe().objectAt("victory")["achieved"])

        val captureOption = legal(melee.id).objectsAt("attackTargets").single {
            it.numberAt("targetX") == targetX && it.numberAt("targetY") == targetY
        }
        assertEquals(true, captureOption["targetDies"])
        val capture = act("unit.attack", mapOf("attackerUnitId" to melee.id,
            "target" to mapOf("x" to captureOption["targetX"], "y" to captureOption["targetY"])))
        val decision = capture.objectsAt("pendingDecisions").single { it["decisionType"] == "conquest-disposition" }
        assertEquals("city:${target["cityId"]}", decision["sourceRef"])
        assertEquals("MANDATORY", decision["classification"])
        val observedDecision = observe().objectsAt("requiredDecisions")
            .single { it["decisionId"] == decision["decisionId"] }
        assertEquals("Observation and receipt must expose the same actionable decision", decision, observedDecision)
        for (wireDecision in listOf(decision, observedDecision)) {
            for (option in wireDecision.objectsAt("options")) {
                assertTrue("Decision option values must be primitive strings: $option",
                    option.values.all { it is String })
            }
        }
        try {
            endTurn()
            fail("Captured cities must be disposed before game.end_turn")
        } catch (ex: EngineCallException) {
            assertEquals("DECISIONS_PENDING", ex.code)
        }
        val dispositions = decision.objectsAt("options")
        val disposition = dispositions.singleOrNull { it["optionId"] == "PUPPET" }
        assertNotNull("Wire conquest options must offer a string PUPPET optionId: $dispositions", disposition)
        act("conquest.resolve", mapOf("decisionId" to decision["decisionId"],
            "disposition" to disposition!!["optionId"]))
        val conquered = observe()
        assertTrue(conquered.objectsAt("requiredDecisions").isEmpty())
        val victory = conquered.objectAt("victory")
        val domination = victory.objectAt("domination")
        assertEquals(2, domination.numberAt("ownedOriginalCapitals"))
        assertEquals(2, domination.numberAt("requiredOriginalCapitals"))
        assertEquals(setOf(romanCapital.id, greekCapital.id),
            conquered.objectsAt("ownCities").filter { it["isOriginalCapital"] == true }.map { it["cityId"] }.toSet())
        assertEquals("Domination", victory["achieved"])
        assertNoOtherRoute(victory)
        assertNull("Satisfying the objective does not itself award the game", recordedVictory())

        assertEquals(true, endTurn()["turnEnded"])
        assertEquals(TurnPhase.COMPLETED, coordinator.getTurnPhase())
        val finished = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())
        assertEquals("Domination", finished.victoryData?.victoryType)
        assertEquals(rome.civID, finished.victoryData?.winningCiv)
        assertEquals(rome.civID, finished.getCities().single { it.id == greekCapital.id }.civ.civID)
        assertEquals(greece.civID, finished.getCities().single { it.id == survivingGreekCity.id }.civ.civID)
        assertFalse("Domination must work without eliminating every rival", finished.getCivilization(greece.civID).isDefeated())
        val finalObservation = observe()
        assertNoOtherRoute(finalObservation.objectAt("victory"))
        val completed = finalObservation.objectAt("strategicProgress").objectsAt("completedTurns").last()
        // Directly typed numeric maps must also remain primitive values across the wire.
        assertEquals(1, completed.objectAt("changes").objectAt("eventCounts")["originalCapitalGained"])
        val commands = completed.objectAt("appliedCommandCounts")
        assertEquals(1, commands["diplomacy.declareWar"])
        assertEquals(1, commands["unit.move"])
        assertEquals(rangedAttacks + 1, commands["unit.attack"])
        assertEquals(1, commands["conquest.resolve"])
    }
}
