package com.unciv.logic.agents

import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remaining action-coverage commands (AI_AGENT_PLAN.md §6): paradrop,
 * air rebase via standard movement, join-war trade offers, the agent
 * diplomatic-vote cycle, and checkpoint/reload recovery without duplicate
 * or lost actions (§8.3).
 */
@RunWith(BaseTestRunner::class)
class ActionCompletionAndRecoveryTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        lateinit var aztecId: String
        var unitId: Int = -1
        var cityId: String = ""

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(5)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            val aztecs = testGame.addCiv(testGame.ruleset.nations["Aztecs"]!!)
            aztecId = aztecs.civID
            testGame.addCity(rome, testGame.getTile(0, 0))
            cityId = rome.cities.first().id
            testGame.addCity(rome, testGame.getTile(-3, 0))
            unitId = testGame.addUnit("Warrior", rome, testGame.getTile(-2, 0)).id
            // Greece city far from Rome's borders.
            testGame.addCity(greece, testGame.getTile(3, 0))
            testGame.addCity(aztecs, testGame.getTile(0, 3))
            rebuild()
        }

        fun rebuild(phase: TurnPhase = TurnPhase.AWAITING_DECISIONS) {
            val match = AgentMatchState(matchId = "m1", timelineId = "t1", turnPhase = phase)
            match.controllers[romeId] = CivilizationController(romeId, ControllerKind.HUMAN, null)
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[aztecId] = CivilizationController(aztecId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun rev() = coordinator.getStateRevision()
    }

    @Test
    fun `paradrop order and move teleport the unit`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        f.unitId = f.testGame.addUnit("Paratrooper", rome, f.testGame.getTile(0, 0)).id
        f.rebuild()

        f.coordinator.execute(UnitOrderCommand(f.romeId, "p1", f.rev(), f.unitId, UnitOrder.PARADROP))
        val moved = f.coordinator.execute(
            MoveUnitCommand(f.romeId, "p2", f.rev(), f.unitId, HexCoord(1, 1))) as UnitMovedResult
        // Landed away from the city — a paradrop jump, not a walk.
        Assert.assertEquals(HexCoord(1, 1), moved.finalPosition)
        val (auto, action) = f.coordinator.getUnitOrder(f.romeId, f.unitId)
        Assert.assertFalse(auto)
        Assert.assertNull("Paradrop preparation is consumed by the jump", action)

        // A unit without the unique cannot prepare paradrop.
        val warrior = f.testGame.addUnit("Warrior", rome, f.testGame.getTile(1, 0))
        f.rebuild()
        try {
            f.coordinator.execute(UnitOrderCommand(f.romeId, "p3", f.rev(), warrior.id, UnitOrder.PARADROP))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `air unit rebases between own cities via the move command`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        f.unitId = f.testGame.addUnit("Fighter", rome, f.testGame.getTile(0, 0)).id
        f.rebuild()

        val moved = f.coordinator.execute(
            MoveUnitCommand(f.romeId, "rb1", f.rev(), f.unitId, HexCoord(-3, 0))) as UnitMovedResult
        Assert.assertEquals(HexCoord(-3, 0), moved.finalPosition)
    }

    @Test
    fun `war declaration trade offer commits the offerer to the war`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val greece = f.testGame.gameInfo.getCivilization(f.greeceId)
        val aztecs = f.testGame.gameInfo.getCivilization(f.aztecId)
        rome.diplomacyFunctions.makeCivilizationsMeet(greece)
        rome.diplomacyFunctions.makeCivilizationsMeet(aztecs)
        greece.diplomacyFunctions.makeCivilizationsMeet(aztecs)
        f.rebuild()

        // Greece stages a declaration against the Aztecs; Rome accepts. The
        // engine's TradeLogic charges the declaration to the OFFERER.
        f.coordinator.execute(ProposeTradeCommand(f.greeceId, "jw1", f.rev(), f.romeId,
            ourOffers = listOf(TradeOfferDto(f.aztecId, TradeOfferType.WarDeclaration, 1)),
            theirOffers = emptyList()))
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.romeId, "jw2", f.rev(), f.greeceId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals("War",
            f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals("Peace",
            f.coordinator.getDiplomaticStatus(f.romeId, f.aztecId))
    }

    @Test
    fun `diplomatic vote decision raised, resolved by castVote, blocks end turn`() {
        val f = Fixture()
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        // Prepared fixture: a vote cycle is open (UN built in a real match).
        rome.addFlag(com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name, 0)
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()

        Assert.assertTrue(f.coordinator.getPendingDecisions(f.romeId)
            .any { it.decisionType == "diplomatic-vote" })

        // End turn blocked while the vote is open.
        try {
            f.coordinator.execute(EndTurnCommand(f.romeId, "v-end", f.rev()))
            Assert.fail("expected DECISIONS_PENDING")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.DECISIONS_PENDING, ex.code)
        }

        f.coordinator.execute(CastVoteCommand(f.romeId, "v1", f.rev(), f.greeceId))
        Assert.assertTrue(f.coordinator.getPendingDecisions(f.romeId)
            .none { it.decisionType == "diplomatic-vote" })

        // Double-casting is rejected: the vote is consumed.
        try {
            f.coordinator.execute(CastVoteCommand(f.romeId, "v2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `cast vote command rejects votes when no cycle is open`() {
        val f = Fixture()
        try {
            f.coordinator.execute(CastVoteCommand(f.romeId, "v-bad", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `checkpoint reload replays receipts without duplicating actions`() {
        val f = Fixture()
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()
        val orderRev = f.rev()
        val before = f.coordinator.execute(
            UnitOrderCommand(f.romeId, "h1", orderRev, f.unitId, UnitOrder.FORTIFY)) as UnitOrderResult
        val bundleJson = f.coordinator.saveToJson()

        val reloaded = MatchCoordinator.loadFromJson(bundleJson, f.writer)
        // Same actionId with the same payload: replayed from receipts, no
        // second mutation — revision is unchanged after reload+replay.
        val replay = reloaded.execute(
            UnitOrderCommand(f.romeId, "h1", orderRev, f.unitId, UnitOrder.FORTIFY)) as UnitOrderResult
        Assert.assertEquals(before.receipt.afterRevision, replay.receipt.afterRevision)
        Assert.assertEquals(orderRev + 1, reloaded.getStateRevision())

        // A different request under the same id is still a conflict.
        try {
            reloaded.execute(
                UnitOrderCommand(f.romeId, "h1", reloaded.getStateRevision(), f.unitId, UnitOrder.WAKE))
            Assert.fail("expected ACTION_ID_CONFLICT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ACTION_ID_CONFLICT, ex.code)
        }
    }

    @Test
    fun `controller assignments and profiles survive checkpoint reload`() {
        val f = Fixture()
        f.rebuild(TurnPhase.READY_TO_START)
        f.coordinator.beginAwaitingTurn()
        val bundleJson = f.coordinator.saveToJson()
        val reloaded = MatchCoordinator.loadFromJson(bundleJson, f.writer)

        val controller = reloaded.getController(f.greeceId)
        Assert.assertNotNull(controller)
        Assert.assertEquals(ControllerKind.EXTERNAL_AGENT, controller!!.kind)
        Assert.assertEquals("Ada", controller.agentProfile?.displayName)
        Assert.assertEquals(ControllerKind.HUMAN, reloaded.getController(f.romeId)?.kind)
    }
}
