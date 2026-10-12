package com.unciv.logic.agents

import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine answers that spare leaders extra round trips: founding legality that
 * matches unit.found, unit state in receipts, adoptable policies and rules
 * sections in reads, plus the host's session rotation policy.
 */
@RunWith(BaseTestRunner::class)
class AgentTurnSpeedTest {

    private class Fixture(culture: Int = 0) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge
        val romeId: String
        val settlerId: Int
        val crowdedSettlerId: Int
        val tiredSettlerId: Int
        val warriorId: Int
        val scoutId: Int

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            rome.policies.storedCulture = culture
            testGame.addCity(rome, testGame.getTile(3, 0))
            settlerId = testGame.addUnit("Settler", rome, testGame.getTile(-3, 0)).id
            crowdedSettlerId = testGame.addUnit("Settler", rome, testGame.getTile(2, 0)).id
            tiredSettlerId = testGame.addUnit("Settler", rome, testGame.getTile(-2, -2)).also { it.currentMovement = 0f }.id
            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(0, 0)).id
            scoutId = testGame.addUnit("Scout", rome, testGame.getTile(-1, 0)).id
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
            bridge = AgentEngineBridge(coordinator)
        }

        fun envelope(actionId: String, type: String, args: Map<String, Any?>) = mapOf(
            "protocolVersion" to 1, "actionId" to actionId,
            "expectedRevision" to coordinator.getStateRevision(), "type" to type,
            "actorCivId" to romeId, "args" to args)

        fun move(actionId: String, x: Int, y: Int) = envelope(actionId, "unit.move",
            mapOf("unitId" to scoutId, "destination" to mapOf("x" to x, "y" to y)))

        fun legal(unitId: Int) = coordinator.legalActions(romeId, unitId = unitId)

        fun foundRejection(unitId: Int): String = try {
            coordinator.execute(FoundCityCommand(romeId, "found-$unitId", coordinator.getStateRevision(), unitId))
            throw AssertionError("expected unit.found to be rejected")
        } catch (ex: AgentCommandException) {
            ex.message!!
        }
    }

    @Test
    fun `canFound matches what unit found accepts on the current tile`() {
        val f = Fixture()
        val open = f.legal(f.settlerId)
        Assert.assertTrue(open.canFound)
        Assert.assertNull(open.foundBlockedReason)
        Assert.assertTrue(open.movementLeft > 0f)

        val crowded = f.legal(f.crowdedSettlerId)
        Assert.assertFalse(crowded.canFound)
        Assert.assertEquals("Too close to another city", crowded.foundBlockedReason)
        Assert.assertEquals(crowded.foundBlockedReason, f.foundRejection(f.crowdedSettlerId))

        val tired = f.legal(f.tiredSettlerId)
        Assert.assertFalse(tired.canFound)
        Assert.assertEquals(0f, tired.movementLeft)
        Assert.assertEquals("Unit has no movement left", tired.foundBlockedReason)
        Assert.assertEquals(tired.foundBlockedReason, f.foundRejection(f.tiredSettlerId))

        val warrior = f.legal(f.warriorId)
        Assert.assertFalse(warrior.canFound)
        Assert.assertNull("Non-founding units carry no founding reason", warrior.foundBlockedReason)

        val founded = f.coordinator.execute(FoundCityCommand(f.romeId, "found-open", f.coordinator.getStateRevision(), f.settlerId))
        Assert.assertTrue((founded as CityFoundedResult).cityId.isNotEmpty())
    }

    @Test
    fun `unit command receipts carry the unit's committed state`() {
        val f = Fixture()
        val before = f.coordinator.ownUnitState(f.romeId, f.scoutId)!!
        @Suppress("UNCHECKED_CAST")
        val receipt = f.bridge.act(mapOf("protocolVersion" to 1, "actionId" to "move-1",
            "expectedRevision" to f.coordinator.getStateRevision(), "type" to "unit.move", "actorCivId" to f.romeId,
            "args" to mapOf("unitId" to f.scoutId, "destination" to mapOf("x" to -2, "y" to 0))))
        @Suppress("UNCHECKED_CAST")
        val unit = (receipt["result"] as Map<String, Any?>)["unit"] as Map<String, Any?>
        Assert.assertEquals(f.scoutId, unit["unitId"])
        Assert.assertEquals(-2, unit["x"])
        Assert.assertEquals(0, unit["y"])
        Assert.assertTrue((unit["movementLeft"] as Double) < before.movementLeft)
        Assert.assertEquals(f.coordinator.getStateRevision(), receipt["revision"])

        @Suppress("UNCHECKED_CAST")
        val order = f.bridge.act(mapOf("protocolVersion" to 1, "actionId" to "order-1",
            "expectedRevision" to f.coordinator.getStateRevision(), "type" to "unit.order", "actorCivId" to f.romeId,
            "args" to mapOf("unitId" to f.warriorId, "order" to "FORTIFY")))["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        Assert.assertNotNull((order["unit"] as Map<String, Any?>)["action"])
        Assert.assertNull("Units must belong to the actor", f.coordinator.ownUnitState(f.romeId, 999_999))
    }

    @Test
    fun `replayed movement returns the original unit snapshot after later movement and reload`() {
        val f = Fixture()
        val originalCommand = f.move("first", -2, 0)
        val original = f.bridge.act(originalCommand)
        f.bridge.act(f.move("second", -2, 1))
        Assert.assertEquals(1, f.coordinator.ownUnitState(f.romeId, f.scoutId)!!.y)

        val reloaded = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        for (bridge in listOf(f.bridge, AgentEngineBridge(reloaded))) {
            val replay = bridge.act(originalCommand)
            Assert.assertEquals(original["result"], replay["result"])
            Assert.assertEquals(original["revision"], replay["revision"])
            Assert.assertEquals(2, bridge.coordinator.getStateRevision())
        }
        // Receipt snapshots must be detached along with the rest of a copied receipt.
        f.coordinator.getReceipt("first")!!.unitSnapshot!!.y = 99
        Assert.assertEquals(0, f.coordinator.getReceipt("first")!!.unitSnapshot!!.y)
    }

    @Test
    fun `replayed orders preserve alive and consumed snapshots after reload`() {
        val f = Fixture()
        val fortify = f.envelope("fortify", "unit.order", mapOf("unitId" to f.warriorId, "order" to "FORTIFY"))
        val fortified = f.bridge.act(fortify)
        val disband = f.envelope("disband", "unit.order", mapOf("unitId" to f.warriorId, "order" to "DISBAND"))
        val disbanded = f.bridge.act(disband)
        val consumedResult = disbanded["result"] as Map<*, *>
        Assert.assertTrue("A captured absence is explicit", consumedResult.containsKey("unit"))
        Assert.assertNull(consumedResult["unit"])
        Assert.assertNull(f.coordinator.ownUnitState(f.romeId, f.warriorId))

        val restored = AgentEngineBridge(MatchCoordinator.loadFromJson(f.coordinator.saveToJson()))
        Assert.assertEquals(fortified["result"], restored.act(fortify)["result"])
        Assert.assertEquals(disbanded["result"], restored.act(disband)["result"])
        Assert.assertNotNull((restored.act(fortify)["result"] as Map<*, *>)["unit"])
    }

    @Test
    fun `legacy receipts without snapshots omit unit state instead of reading live units`() {
        val f = Fixture()
        val firstCommand = f.move("legacy", -2, 0)
        f.bridge.act(firstCommand)
        f.bridge.act(f.move("later", -2, 1))
        val match = json().fromJson(AgentMatchState::class.java, f.coordinator.serializeCommittedMatchForPublication())
        match.currentTurnReceipts.forEach { it.unitSnapshot = null }
        val compactLegacyMatch = json().toJson(match)
        Assert.assertFalse("Old payloads have no snapshot field", compactLegacyMatch.contains("unitSnapshot"))
        val saved = json().toJson(AgentBundle(f.coordinator.serializeCommittedGameForPublication(), compactLegacyMatch))
        val legacy = AgentEngineBridge(MatchCoordinator.loadFromJson(saved))

        val replay = legacy.act(firstCommand)["result"] as Map<*, *>
        Assert.assertEquals(mapOf("x" to -2, "y" to 0), replay["position"])
        Assert.assertFalse("Missing historical data must not look like current state or a consumed unit", replay.containsKey("unit"))
        Assert.assertEquals(1, legacy.coordinator.ownUnitState(f.romeId, f.scoutId)!!.y)
    }

    @Test
    fun `failed checkpoint does not publish a new unit snapshot or mutate prior receipts`() {
        val f = Fixture()
        f.bridge.act(f.move("committed", -2, 0))
        f.writer.failNextWrite = true
        try {
            f.bridge.act(f.move("failed", -2, 1))
            Assert.fail("Expected checkpoint failure")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("INTERNAL_ERROR", ex.code)
        }
        Assert.assertEquals(1, f.coordinator.getStateRevision())
        Assert.assertNull(f.coordinator.getReceipt("failed"))
        Assert.assertEquals(0, f.coordinator.getReceipt("committed")!!.unitSnapshot!!.y)
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        Assert.assertNull(restored.getReceipt("failed"))
        Assert.assertEquals(0, restored.getReceipt("committed")!!.unitSnapshot!!.y)
        Assert.assertEquals(0, restored.ownUnitState(f.romeId, f.scoutId)!!.y)
    }

    @Test
    fun `observe lists adoptable policies only when one can be adopted`() {
        val poor = Fixture().let { it.coordinator.observe(it.romeId).empire }
        Assert.assertFalse(poor.canAdoptPolicy)
        Assert.assertTrue(poor.adoptablePolicies.isEmpty())

        val rich = Fixture(culture = 2000)
        val empire = rich.coordinator.observe(rich.romeId).empire
        Assert.assertTrue(empire.canAdoptPolicy)
        Assert.assertTrue(empire.adoptablePolicies.contains("Tradition"))
        val adopted = rich.coordinator.execute(AdoptPolicyCommand(rich.romeId, "adopt-1",
            rich.coordinator.getStateRevision(), empire.adoptablePolicies.first())) as PolicyAdoptedResult
        Assert.assertTrue(adopted.adopted.isNotEmpty())
    }

    @Test
    fun `rules name their sections in the summary and in unknown-section errors`() {
        val f = Fixture()
        @Suppress("UNCHECKED_CAST")
        val summary = f.bridge.rules() as Map<String, Any?>
        Assert.assertEquals(RULES_SECTIONS, summary["sections"])
        try {
            f.bridge.rules(mapOf("section" to "policies"))
            throw AssertionError("expected an unknown section rejection")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("INVALID_ARGUMENT", ex.code)
            Assert.assertTrue(ex.message!!.contains("improvements, terrains, resources"))
        }
    }

    @Test
    fun `session settings parse leniently and alternate civs between arms`() {
        val defaults = AgentSessionPolicy.parseSettings(null)
        Assert.assertEquals(1, defaults.sessionRotationTurns)
        Assert.assertTrue(defaults.sessionAbTest)
        Assert.assertEquals(1, AgentSessionPolicy.parseSettings("not json").sessionRotationTurns)
        Assert.assertEquals(1, AgentSessionPolicy.parseSettings("""{"sessionRotationTurns":-3}""").sessionRotationTurns)

        val civs = listOf("Rome", "Greece", "China")
        Assert.assertEquals("rotate-1", AgentSessionPolicy.armFor("Rome", civs, defaults))
        Assert.assertEquals(AgentSessionPolicy.ARM_RESUME, AgentSessionPolicy.armFor("Greece", civs, defaults))
        Assert.assertEquals("rotate-1", AgentSessionPolicy.armFor("China", civs, defaults))

        val everyThird = AgentSessionPolicy.parseSettings("""{"sessionRotationTurns":3,"sessionAbTest":false}""")
        Assert.assertEquals("rotate-3", AgentSessionPolicy.armFor("Greece", civs, everyThird))
        Assert.assertTrue(AgentSessionPolicy.shouldResume("rotate-3", 2, everyThird))
        Assert.assertFalse(AgentSessionPolicy.shouldResume("rotate-3", 3, everyThird))

        val never = AgentSessionPolicy.parseSettings("""{"sessionRotationTurns":0}""")
        Assert.assertEquals(AgentSessionPolicy.ARM_RESUME, AgentSessionPolicy.armFor("Rome", civs, never))
        Assert.assertTrue(AgentSessionPolicy.shouldResume(AgentSessionPolicy.ARM_RESUME, 50, never))
    }
}
