package com.unciv.logic.agents

import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Human-through-coordinator routing and snapshot publication
 * (AI_AGENT_PLAN.md §3.2, §9). The [HumanCommandExecutor] is the exact code
 * the desktop host calls for human UI actions; publication tests cover the
 * payload round-trip and stable-ID selection resolution.
 */
@RunWith(BaseTestRunner::class)
class HumanRoutingAndPublicationTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var executor: HumanCommandExecutor
        lateinit var romeId: String
        lateinit var greeceId: String
        var unitId: Int = -1
        var cityId: String = ""

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            testGame.addCity(rome, testGame.getTile(0, 0))
            cityId = rome.cities.first().id
            unitId = testGame.addUnit("Warrior", rome, testGame.getTile(-1, 0)).id
            // outside Rome's city borders (unit placement cannot enter foreign land)
            testGame.addUnit("Warrior", greece, testGame.getTile(3, 0))

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN, null)
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
            executor = HumanCommandExecutor(coordinator)
        }

        fun observe() = coordinator.observe(romeId)
    }

    @Test
    fun `human executor runs gameplay commands on the human turn`() {
        val f = Fixture()
        Assert.assertTrue(f.executor.isHumanTurn())
        Assert.assertEquals(f.romeId, f.executor.currentHumanCivId())

        // Unit order through the same path the desktop UI uses.
        val orderError = f.executor.executeAll(f.romeId) { listOf({ r: Int ->
            UnitOrderCommand(f.romeId, "h1", r, f.unitId, UnitOrder.FORTIFY)
        }) }
        Assert.assertNull(orderError)
        val (auto, action) = f.coordinator.getUnitOrder(f.romeId, f.unitId)
        Assert.assertFalse(auto)
        Assert.assertEquals(UnitActionType.Fortify.value, action)

        // City production through the same path.
        val prodError = f.executor.executeAll(f.romeId) { listOf({ r: Int ->
            CityProductionCommand(f.romeId, "h2", r, f.cityId,
                ProductionOp.ADD_BOTTOM, name = "Monument")
        }) }
        Assert.assertNull(prodError)
        Assert.assertTrue(f.coordinator.observe(f.romeId).ownCities
            .first().productionQueue.contains("Monument"))
    }

    @Test
    fun `human executor rejects commands outside the human turn`() {
        val f = Fixture()
        // An agent-civ actor is rejected outright.
        val error = f.executor.execute(f.greeceId,
            UnitOrderCommand(f.greeceId, "h-bad", 0, 1, UnitOrder.FORTIFY))
        Assert.assertNotNull(error)
    }

    @Test
    fun `batch commands each receive fresh revisions`() {
        val f = Fixture()
        val error = f.executor.executeAll(f.romeId) { _ ->
            listOf(
                { r: Int -> UnitOrderCommand(f.romeId, "h1", r, f.unitId, UnitOrder.FORTIFY) },
                { r: Int -> CityProductionCommand(f.romeId, "h2", r, f.cityId,
                    ProductionOp.ADD_BOTTOM, name = "Monument") }
            )
        }
        Assert.assertNull(error)
        // Both applied: the second command was NOT rejected as stale.
        Assert.assertTrue(f.observe().ownCities.first().productionQueue.contains("Monument"))
    }

    @Test
    fun `publication payload round-trips and re-resolves selections by id`() {
        val f = Fixture()
        // Select the warrior, then publish.
        val payloadBefore = AgentSnapshotPublication.buildPayload(
            f.coordinator, viewerCivId = f.romeId, selectedUnitId = f.unitId,
            selectedTile = com.unciv.logic.map.HexCoord(-1, 0)
        )
        val snapshot = MatchCoordinator.deserializeForPublication(payloadBefore.serializedGame)

        // Same ids resolve in the new object graph.
        val resolved = AgentSnapshotPublication.resolveSelection(snapshot, payloadBefore)
        Assert.assertEquals(f.unitId, resolved.unitId)
        Assert.assertEquals(com.unciv.logic.map.HexCoord(-1, 0), resolved.tile)

        // A removed unit resolves to null, never to a lookalike.
        val payloadGone = AgentSnapshotPublication.buildPayload(
            f.coordinator, viewerCivId = f.romeId, selectedUnitId = 999999
        )
        val resolvedGone = AgentSnapshotPublication.resolveSelection(snapshot, payloadGone)
        Assert.assertNull(resolvedGone.unitId)

        // Foreign units are never selectable via fabricated ids.
        val payloadForeign = AgentSnapshotPublication.buildPayload(
            f.coordinator, viewerCivId = f.romeId, selectedCityId = "no-such-city"
        )
        val resolvedForeign = AgentSnapshotPublication.resolveSelection(snapshot, payloadForeign)
        Assert.assertNull(resolvedForeign.cityId)
    }

    @Test
    fun `snapshot deserialization keeps matches deterministic and idle`() {
        val f = Fixture()
        val payload = AgentSnapshotPublication.buildPayload(f.coordinator, f.romeId)
        val snapshot = MatchCoordinator.deserializeForPublication(payload.serializedGame)
        val rome = snapshot.getCivilization(f.romeId)
        // Restoration must not have auto-filled the queue (CP-027 policy).
        Assert.assertTrue(rome.cities.first().cityConstructions.constructionQueue.isEmpty())
        // Units keep their stable ids across the round trip.
        Assert.assertEquals(f.unitId, rome.units.getCivUnits().first().id)
    }

    @Test
    fun `city sell gates on built buildings`() {
        val f = Fixture()
        // Selling a building the city does not own is rejected.
        try {
            f.coordinator.execute(CityProductionCommand(f.romeId, "c2",
                f.coordinator.getStateRevision(), f.cityId, ProductionOp.SELL, name = "Monument"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `city rename command renames own city`() {
        val f = Fixture()
        val result = f.coordinator.execute(
            RenameCityCommand(f.romeId, "rn1", f.coordinator.getStateRevision(),
                f.cityId, "New Rome")) as CityRenamedResult
        Assert.assertEquals("New Rome", result.name)
        try {
            f.coordinator.execute(
                RenameCityCommand(f.romeId, "rn2", f.coordinator.getStateRevision(),
                    "no-such-city", "X"))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }

    @Test
    fun `denounce cycle with fences`() {
        val f = Fixture()
        f.testGame.gameInfo.getCivilization(f.romeId)
            .diplomacyFunctions.makeCivilizationsMeet(
                f.testGame.gameInfo.getCivilization(f.greeceId))
        f.rebuildFixture()
        val denounce = f.coordinator.execute(
            DenounceCommand(f.romeId, "d1", f.coordinator.getStateRevision(), f.greeceId))
        Assert.assertNotNull(denounce)
        try {
            f.coordinator.execute(
                DenounceCommand(f.romeId, "d2", f.coordinator.getStateRevision(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    private fun Fixture.rebuildFixture() {
        val match = AgentMatchState(
            matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
        )
        match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN, null)
        match.controllers[greeceId] = CivilizationController(
            greeceId, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        executor = HumanCommandExecutor(coordinator)
    }
}
