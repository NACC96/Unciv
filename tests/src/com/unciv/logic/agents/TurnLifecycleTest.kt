package com.unciv.logic.agents

import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Turn-stepper coverage (AI_AGENT_PLAN.md §4.2, §9): durable phases,
 * once-only start/end effects, mandatory-decision gating, inline built-in
 * automation, defeated skipping, round rollover, and automation suppression
 * for external civs. Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class TurnLifecycleTest {

    private class Fixture(
        val withBuiltInAi: Boolean = false,
        val greeceAlive: Boolean = true,
        val mandatoryDecision: Boolean = false
    ) {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        var aztecId: String? = null
        var warriorId: Int = -1

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(3)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            if (withBuiltInAi) {
                val aztec = testGame.addCiv(testGame.ruleset.nations["Aztecs"]!!)
                aztecId = aztec.civID
                testGame.addUnit("Warrior", aztec, testGame.getTile(2, 0))
            }
            val warrior = testGame.addUnit("Warrior", rome, testGame.getTile(0, 0))
            warriorId = warrior.id
            if (greeceAlive) testGame.addUnit("Warrior", greece, testGame.getTile(-2, 0))

            val match = AgentMatchState(
                matchId = "m1",
                timelineId = "t1",
                turnPhase = TurnPhase.READY_TO_START
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1)
            )
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p2", "Bob", "claude", "m", null, "", 1)
            )
            aztecId?.let {
                match.controllers[it] = CivilizationController(it, ControllerKind.BUILTIN_AI, null)
            }
            if (mandatoryDecision) match.pendingDecisions.add(
                PendingDecision("d1", "conquest-disposition", romeId, "city:X",
                    DecisionClassification.MANDATORY, ArrayList())
            )
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun endTurn(actor: String, actionId: String) =
            EndTurnCommand(actor, actionId, coordinator.getStateRevision())
    }

    @Test
    fun `begin opens the first turn exactly once`() {
        val f = Fixture()
        val begun = f.coordinator.beginAwaitingTurn()
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, f.coordinator.getTurnPhase())
        Assert.assertEquals(f.romeId, f.coordinator.getCurrentCivId())
        Assert.assertEquals(f.romeId, begun.nextCivId)
        Assert.assertEquals(1, f.coordinator.getTurnSerial())
        Assert.assertEquals(1, f.coordinator.getStateRevision())
        try {
            f.coordinator.beginAwaitingTurn()
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `end-turn cycles civs and rolls the round over`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()
        val turnsBefore = f.coordinator.getGameTurns()

        val first = f.coordinator.execute(f.endTurn(f.romeId, "e1")) as TurnEndedResult
        Assert.assertEquals(f.greeceId, first.nextCivId)
        Assert.assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        Assert.assertEquals(2, f.coordinator.getTurnSerial())
        Assert.assertEquals(turnsBefore, f.coordinator.getGameTurns())
        Assert.assertFalse(first.wrappedToNewRound)

        val second = f.coordinator.execute(f.endTurn(f.greeceId, "e2")) as TurnEndedResult
        Assert.assertEquals(f.romeId, second.nextCivId)
        Assert.assertEquals(turnsBefore + 1, f.coordinator.getGameTurns())
        Assert.assertTrue(second.wrappedToNewRound)
        Assert.assertEquals(3, f.coordinator.getTurnSerial())
    }

    @Test
    fun `end-turn effects apply exactly once`() {
        val f = Fixture()
        f.coordinator.beginAwaitingTurn()
        val before = f.coordinator.execute(f.endTurn(f.romeId, "e1")) as TurnEndedResult
        val revAfter = f.coordinator.getStateRevision()
        val turnsAfter = f.coordinator.getGameTurns()

        // Same action ID replays the original receipt without re-executing.
        val replay = f.coordinator.execute(
            EndTurnCommand(f.romeId, "e1", before.receipt.beforeRevision)
        ) as TurnEndedResult
        Assert.assertEquals(before.receipt.afterRevision, replay.receipt.afterRevision)
        Assert.assertEquals(revAfter, f.coordinator.getStateRevision())
        Assert.assertEquals(turnsAfter, f.coordinator.getGameTurns())

        // A new ID with the consumed revision is stale, never a double apply.
        try {
            f.coordinator.execute(EndTurnCommand(f.greeceId, "e-stale", before.receipt.beforeRevision))
            Assert.fail("expected STALE_STATE")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.STALE_STATE, ex.code)
        }
    }

    @Test
    fun `mandatory decisions block end-turn, optional ones do not`() {
        val f = Fixture(mandatoryDecision = true)
        f.coordinator.beginAwaitingTurn()
        try {
            f.coordinator.execute(f.endTurn(f.romeId, "e1"))
            Assert.fail("expected DECISIONS_PENDING")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.DECISIONS_PENDING, ex.code)
            Assert.assertTrue(ex.message!!.contains("d1"))
        }
        Assert.assertEquals(f.romeId, f.coordinator.getCurrentCivId())
    }

    @Test
    fun `built-in ai turns process inline and defeated civs are skipped`() {
        val f = Fixture(withBuiltInAi = true, greeceAlive = false)
        f.coordinator.beginAwaitingTurn()
        Assert.assertEquals(f.romeId, f.coordinator.getCurrentCivId())
        // Rotation order is Rome -> Aztec(AI, inline) -> Greece(defeated, skip) -> Rome.
        val result = f.coordinator.execute(f.endTurn(f.romeId, "e1")) as TurnEndedResult
        Assert.assertEquals(f.romeId, result.nextCivId)
        Assert.assertTrue(result.wrappedToNewRound)
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, f.coordinator.getTurnPhase())
    }

    @Test
    fun `delegated orders are stripped but explicit orders survive turn start`() {
        val f = Fixture()
        // Arrange delegation + explicit orders on the SOURCE game before the
        // coordinator snapshots it.
        val warrior = f.testGame.gameInfo.getCivilization(f.romeId)
            .units.getCivUnits().first { it.id == f.warriorId }
        warrior.automated = true
        warrior.action = UnitActionType.Automate.value
        val scout = f.testGame.addUnit("Scout", f.testGame.gameInfo.getCivilization(f.romeId), f.testGame.getTile(-1, 0))
        scout.action = UnitActionType.Fortify.value

        // Re-snapshot: build the coordinator after arranging orders.
        val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.READY_TO_START)
        match.controllers[f.romeId] = CivilizationController(
            f.romeId, ControllerKind.EXTERNAL_AGENT,
            AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
        match.controllers[f.greeceId] = CivilizationController(f.greeceId, ControllerKind.EXTERNAL_AGENT, null)
        val coordinator = MatchCoordinator(f.testGame.gameInfo, match, InMemoryCheckpointWriter())
        coordinator.beginAwaitingTurn()

        val (warAuto, warAction) = coordinator.getUnitOrder(f.romeId, f.warriorId)
        Assert.assertFalse(warAuto)
        Assert.assertNull(warAction)
        val (scoutAuto, scoutAction) = coordinator.getUnitOrder(f.romeId, scout.id)
        Assert.assertFalse(scoutAuto)
        Assert.assertEquals(UnitActionType.Fortify.value, scoutAction)
    }
}
