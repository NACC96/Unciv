package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Observation and visibility coverage (AI_AGENT_PLAN.md §5.1–5.2): own empire
 * completeness, foreign visibility through fog (seen vs unseen), no oracle in
 * movement targets, gated legal actions, static rules. Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class ObservationVisibilityTest {

    private class Fixture(pendingBonuses: Boolean = false) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var scoutId: Int = -1
        var farGreekId: Int = -1
        var nearGreekId: Int = -1
        var cityId: String = ""

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            if (!rome.tech.isResearched("Agriculture")) rome.tech.addTechnology("Agriculture")
            rome.addGold(500)
            val city = testGame.addCity(rome, testGame.getTile(0, 0), initialPopulation = 2)
            cityId = city.id
            scoutId = testGame.addUnit("Scout", rome, testGame.getTile(0, 1)).id
            // One Greek warrior far in the fog, one adjacent and visible.
            farGreekId = testGame.addUnit("Warrior", greece, testGame.getTile(4, 0)).id
            nearGreekId = testGame.addUnit("Warrior", greece, testGame.getTile(2, 0)).id
            testGame.addCity(greece, testGame.getTile(4, 1))
            if (pendingBonuses) {
                rome.pendingStartingUnits["Nuclear Missile"] = 2
                greece.pendingStartingUnits["Bomber"] = 4
            }

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
    fun `own empire is complete, foreigners filtered by visibility`() {
        val f = Fixture()
        val obs = f.coordinator.observe(f.romeId)

        Assert.assertEquals(0, obs.revision)
        Assert.assertEquals("m1", obs.matchId)
        // setTransients anchors the empty current player to the first human:
        // Rome is to move, so this IS its turn.
        Assert.assertTrue(obs.isMyTurn)
        Assert.assertEquals(500, obs.empire.gold)
        Assert.assertEquals(1, obs.ownCities.size)
        Assert.assertEquals(f.cityId, obs.ownCities[0].cityId)
        Assert.assertTrue(obs.ownUnits.any { it.unitId == f.scoutId })
        // Own city exposes its queue; foreign cities never do.
        Assert.assertNotNull(obs.ownCities[0].productionQueue)

        val foreignIds = obs.visibleForeignUnits.map { it.unitId }
        Assert.assertTrue(foreignIds.contains(f.nearGreekId))
        Assert.assertFalse(foreignIds.contains(f.farGreekId))
        for (unit in obs.visibleForeignUnits) {
            Assert.assertEquals(f.greeceId, unit.ownerCivId)
            // ... but no movement points or orders leak for foreign units.
            Assert.assertEquals(0f, unit.movementLeft)
            Assert.assertNull(unit.action)
        }
        // Far Greek city is out of sight entirely.
        Assert.assertTrue(obs.visibleForeignCities.isEmpty())
    }

    @Test
    fun `scouting reveals, and legal actions stay gated`() {
        val f = Fixture()
        // March the scout toward the far warrior over two full rounds.
        repeat(2) { round ->
            f.coordinator.execute(MoveUnitCommand(f.romeId, "m$round", f.rev(), f.scoutId, HexCoord(3, 0)))
            f.coordinator.execute(EndTurnCommand(f.romeId, "e$round-r", f.rev()))
            f.coordinator.execute(EndTurnCommand(f.greeceId, "e$round-g", f.rev()))
        }
        val obs = f.coordinator.observe(f.romeId)
        Assert.assertTrue(
            "scout at ${f.coordinator.getUnitPosition(f.romeId, f.scoutId)} should see the far warrior",
            obs.visibleForeignUnits.any { it.unitId == f.farGreekId })

        // Movement targets are adjacent-only: no destination oracle.
        val legal = f.coordinator.legalActions(f.romeId, unitId = f.scoutId)
        val scoutPos = f.coordinator.getUnitPosition(f.romeId, f.scoutId)!!
        for (target in legal.moveTargets) {
            val dx = target.x - scoutPos.x
            val dy = target.y - scoutPos.y
            Assert.assertTrue(kotlin.math.abs(dx) <= 2 && kotlin.math.abs(dy) <= 2)
        }
        Assert.assertTrue(legal.researchable.contains("Pottery"))
        // Scouts are military recon: they fortify (settler sleep is covered
        // in AgentCommandsTest).
        Assert.assertTrue(legal.orders.contains(UnitOrder.FORTIFY))

        // City answers constructions; foreign cities are invisible to it.
        val cityLegal = f.coordinator.legalActions(f.romeId, cityId = f.cityId)
        Assert.assertTrue(cityLegal.constructions.any { it.name == "Monument" })
        try {
            f.coordinator.legalActions(f.greeceId, cityId = f.cityId)
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        try {
            f.coordinator.legalActions(f.romeId)
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }

    @Test
    fun `rules summary pins the ruleset`() {
        val f = Fixture()
        val rules = f.coordinator.rules()
        Assert.assertEquals(BaseRuleset.Civ_V_Vanilla.fullName, rules.baseRuleset)
        Assert.assertTrue(rules.note.isNotEmpty())
    }

    @Test
    fun `agent observation shows copied pending bonuses for its own civilization only`() {
        val f = Fixture(pendingBonuses = true)
        val obs = f.coordinator.observe(f.romeId)
        val encoded = json().toJson(obs.empire)
        Assert.assertTrue("Waiting units must be visible to the agent", encoded.contains("Nuclear Missile"))
        Assert.assertFalse("Another nation's waiting bonuses are private", encoded.contains("Bomber"))
        obs.empire.pendingStartingUnits.clear()
        Assert.assertEquals(2, f.coordinator.observe(f.romeId).empire.pendingStartingUnits["Nuclear Missile"])
    }
}
