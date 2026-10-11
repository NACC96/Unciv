package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Command-family coverage, batch 1 (AI_AGENT_PLAN.md §6): research queue +
 * free techs, city production queue + purchase, founding, explicit orders,
 * disband. Explicit Civ V - Vanilla fixture; every row also refreshes
 * docs/agent-action-coverage.md.
 */
@RunWith(BaseTestRunner::class)
class AgentCommandsTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var warriorId: Int = -1
        var settlerId: Int = -1
        var scoutId: Int = -1
        var cityId: String = ""

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(4)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            // Deterministic research footing: addCiv grants an arbitrary
            // first-era tech, so pin Agriculture explicitly for the tests.
            if (!rome.tech.isResearched("Agriculture")) rome.tech.addTechnology("Agriculture")
            rome.tech.freeTechs = 1
            rome.addGold(2000)
            warriorId = testGame.addUnit("Warrior", rome, testGame.getTile(0, 0)).id
            settlerId = testGame.addUnit("Settler", rome, testGame.getTile(-3, 0)).id
            scoutId = testGame.addUnit("Scout", rome, testGame.getTile(1, 0)).id
            // Production fixture city, far from the settler (settling range).
            val city = testGame.addCity(rome, testGame.getTile(3, 0))
            cityId = city.id
            city.cityConstructions.removeAll()

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p2", "Bob", "claude", "m", null, "", 1))
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun rev() = coordinator.getStateRevision()
    }

    @Test
    fun `research queue select and free tech`() {
        val f = Fixture()
        // Rome starts with Agriculture (cheapest first-era tech); Pottery follows it.
        val sel = f.coordinator.execute(
            SelectResearchCommand(f.romeId, "r1", f.rev(), "Pottery", queue = false, useFreeTech = false)
        ) as ResearchSelectedResult
        Assert.assertEquals("Pottery", sel.currentTech)

        // Unknown techs are invalid arguments, not illegal actions.
        try {
            f.coordinator.execute(
                SelectResearchCommand(f.romeId, "r2", f.rev(), "Steam Power Plants", queue = false, useFreeTech = false))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }

        // Free-tech pick consumes the free tech, verified on committed state.
        val free = f.coordinator.execute(
            SelectResearchCommand(f.romeId, "r3", f.rev(), "Animal Husbandry", queue = false, useFreeTech = true)
        ) as ResearchSelectedResult
        Assert.assertNotNull(free.currentTech)
        // A second free pick with none left is illegal.
        try {
            f.coordinator.execute(
                SelectResearchCommand(f.romeId, "r4", f.rev(), "Mining", queue = false, useFreeTech = true))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `production queue add reorder remove and purchase`() {
        val f = Fixture()
        val addMon = f.coordinator.execute(
            CityProductionCommand(f.romeId, "p1", f.rev(), f.cityId, ProductionOp.ADD_BOTTOM, name = "Monument")
        ) as CityProductionResult
        Assert.assertTrue(addMon.queue.contains("Monument"))
        val addWar = f.coordinator.execute(
            CityProductionCommand(f.romeId, "p2", f.rev(), f.cityId, ProductionOp.ADD_BOTTOM, name = "Warrior")
        ) as CityProductionResult
        Assert.assertEquals(listOf("Monument", "Warrior"), addWar.queue)

        val raised = f.coordinator.execute(
            CityProductionCommand(f.romeId, "p3", f.rev(), f.cityId, ProductionOp.RAISE, index = 1)
        ) as CityProductionResult
        Assert.assertEquals(listOf("Warrior", "Monument"), raised.queue)

        val removed = f.coordinator.execute(
            CityProductionCommand(f.romeId, "p4", f.rev(), f.cityId, ProductionOp.REMOVE, index = 1)
        ) as CityProductionResult
        Assert.assertEquals(listOf("Warrior"), removed.queue)

        // Unknown constructions are invalid; foreign cities are invisible.
        try {
            f.coordinator.execute(
                CityProductionCommand(f.romeId, "p5", f.rev(), f.cityId, ProductionOp.ADD_BOTTOM, name = "Death Ray"))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
        // Off-turn authority is checked before foreign-city legality.
        try {
            f.coordinator.execute(
                CityProductionCommand(f.greeceId, "p6", f.rev(), f.cityId, ProductionOp.ADD_BOTTOM, name = "Monument"))
            Assert.fail("expected NOT_YOUR_TURN")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.NOT_YOUR_TURN, ex.code)
        }

        // Purchase with ample gold succeeds and keeps a legal queue.
        val bought = f.coordinator.execute(
            CityProductionCommand(f.romeId, "p7", f.rev(), f.cityId, ProductionOp.PURCHASE, name = "Monument", index = -1)
        ) as CityProductionResult
        Assert.assertNotNull(bought.queue)
    }

    @Test
    fun `settler founds, warrior cannot`() {
        val f = Fixture()
        val founded = f.coordinator.execute(
            FoundCityCommand(f.romeId, "f1", f.rev(), f.settlerId)
        ) as CityFoundedResult
        Assert.assertTrue(founded.cityId.isNotEmpty())
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.settlerId))

        try {
            f.coordinator.execute(FoundCityCommand(f.romeId, "f2", f.rev(), f.warriorId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `explicit orders follow ui gates, disband removes`() {
        val f = Fixture()
        val fortified = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "o1", f.rev(), f.warriorId, UnitOrder.FORTIFY)
        ) as UnitOrderResult
        Assert.assertEquals(UnitActionType.Fortify.value, fortified.action)
        Assert.assertFalse(fortified.disbanded)

        val woken = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "o2", f.rev(), f.warriorId, UnitOrder.WAKE)
        ) as UnitOrderResult
        Assert.assertNull(woken.action)

        // Warriors fortify; they don't sleep.
        try {
            f.coordinator.execute(
                UnitOrderCommand(f.romeId, "o3", f.rev(), f.warriorId, UnitOrder.SLEEP))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        // ...but settlers (non-fortifying civilians) do.
        val slept = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "o4", f.rev(), f.settlerId, UnitOrder.SLEEP)
        ) as UnitOrderResult
        Assert.assertEquals(UnitActionType.Sleep.value, slept.action)

        val disbanded = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "o5", f.rev(), f.scoutId, UnitOrder.DISBAND)
        ) as UnitOrderResult
        Assert.assertTrue(disbanded.disbanded)
        Assert.assertNull(f.coordinator.getUnitPosition(f.romeId, f.scoutId))

        // Inactive actors are rejected before examining unit ownership.
        try {
            f.coordinator.execute(
                UnitOrderCommand(f.greeceId, "o6", f.rev(), f.warriorId, UnitOrder.WAKE))
            Assert.fail("expected NOT_YOUR_TURN")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.NOT_YOUR_TURN, ex.code)
        }
    }
}
