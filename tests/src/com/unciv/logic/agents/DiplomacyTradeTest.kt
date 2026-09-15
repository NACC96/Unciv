package com.unciv.logic.agents

import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diplomacy and trade coverage (AI_AGENT_PLAN.md §6): war/peace with status
 * receipts, proposals queued as recipient-perspective requests, accept with
 * gold movement, decline, and stale-offer invalidation when resources change.
 * Explicit Civ V - Vanilla fixture.
 */
@RunWith(BaseTestRunner::class)
class DiplomacyTradeTest {

    private class Fixture {
        val testGame = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        lateinit var coordinator: MatchCoordinator
        lateinit var romeId: String
        lateinit var greeceId: String
        lateinit var aztecId: String

        init {
            testGame.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            testGame.makeHexagonalMap(3)
            val rome = testGame.addCiv(testGame.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            val greece = testGame.addCiv(testGame.ruleset.nations["Greece"]!!, isPlayer = true)
            greeceId = greece.civID
            // Unmet third party for contact gates.
            val aztecs = testGame.addCiv(testGame.ruleset.nations["Aztecs"]!!)
            aztecId = aztecs.civID
            rome.addGold(200)
            // Living civs only: unitless cityless civs count as defeated and
            // cannot declare war (same rule as the engine UI).
            testGame.addUnit("Warrior", rome, testGame.getTile(0, 0))
            testGame.addUnit("Warrior", greece, testGame.getTile(1, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)

            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p2", "Bob", "claude", "m", null, "", 1))
            match.controllers[aztecId] = CivilizationController(aztecId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }

        fun rev() = coordinator.getStateRevision()
        fun goldGift(amount: Int) = TradeOfferDto("Gold", TradeOfferType.Gold, amount)

        /** Rebuilds the coordinator after source-game edits so the committed
         *  snapshot picks them up (candidates are serialized clones). */
        fun rebuild() {
            val match = AgentMatchState(
                matchId = "m1", timelineId = "t1", turnPhase = TurnPhase.AWAITING_DECISIONS
            )
            match.controllers[romeId] = CivilizationController(
                romeId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p1", "Ada", "codex", "m", null, "", 1))
            match.controllers[greeceId] = CivilizationController(
                greeceId, ControllerKind.EXTERNAL_AGENT,
                AgentProfile("p2", "Bob", "claude", "m", null, "", 1))
            match.controllers[aztecId] = CivilizationController(aztecId, ControllerKind.BUILTIN_AI, null)
            coordinator = MatchCoordinator(testGame.gameInfo, match, writer)
        }
    }

    @Test
    fun `declare war and make peace cycle statuses`() {
        val f = Fixture()
        val war = f.coordinator.execute(
            DeclareWarCommand(f.romeId, "w1", f.rev(), f.greeceId)) as DiplomacyResult
        Assert.assertEquals("War", war.status)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))

        // A second declaration is illegal, and peace without war is illegal.
        try {
            f.coordinator.execute(DeclareWarCommand(f.romeId, "w2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        val peace = f.coordinator.execute(
            MakePeaceCommand(f.romeId, "p1", f.rev(), f.greeceId)) as DiplomacyResult
        Assert.assertEquals("Peace", peace.status)
        try {
            f.coordinator.execute(MakePeaceCommand(f.romeId, "p2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `diplomacy without contact is illegal`() {
        val f = Fixture()
        try {
            f.coordinator.execute(DeclareWarCommand(f.romeId, "w1", f.rev(), f.aztecId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        try {
            f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.aztecId,
                listOf(f.goldGift(10)), emptyList()))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        Assert.assertNull(f.coordinator.getDiplomaticStatus(f.romeId, f.aztecId))
    }

    @Test
    fun `propose accept moves gold, decline leaves it`() {
        val f = Fixture()
        val romeBefore = f.coordinator.getGold(f.romeId)
        val greeceBefore = f.coordinator.getGold(f.greeceId)

        val proposed = f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(100)), theirOffers = emptyList())) as TradeProposedResult
        Assert.assertEquals(1, proposed.pendingRequestsWithTarget)
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))

        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals(romeBefore - 100, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(greeceBefore + 100, f.coordinator.getGold(f.greeceId))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())

        // A pure gift back, declined: request gone, balances untouched.
        f.coordinator.execute(ProposeTradeCommand(f.greeceId, "t2", f.rev(), f.romeId,
            ourOffers = listOf(f.goldGift(50)), theirOffers = emptyList()))
        val declined = f.coordinator.execute(
            RespondTradeCommand(f.romeId, "r2", f.rev(), f.greeceId, accept = false)) as TradeRespondedResult
        Assert.assertFalse(declined.accepted)
        Assert.assertEquals(greeceBefore + 100, f.coordinator.getGold(f.greeceId))
    }

    @Test
    fun `spent resources invalidate queued offers`() {
        val f = Fixture()
        // Rome holds 200: two 150-gold gifts are each valid alone (0.9 factor) ...
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t2", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        // ... but accepting the first spends the gold out from under the second.
        f.coordinator.execute(RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true))
        try {
            // Same action namespace, second request still queued: re-accept it.
            f.coordinator.execute(RespondTradeCommand(f.greeceId, "r2", f.rev(), f.romeId, accept = true))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `answering a missing request is invalid`() {
        val f = Fixture()
        try {
            f.coordinator.execute(RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }

    @Test
    fun `friendship signs once, open borders trade through`() {
        val f = Fixture()
        f.coordinator.execute(FriendshipCommand(f.romeId, "f1", f.rev(), f.greeceId))
        try {
            f.coordinator.execute(FriendshipCommand(f.romeId, "f2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(TradeOfferDto("Open Borders", TradeOfferType.Agreement, 1)),
            theirOffers = listOf(TradeOfferDto("Open Borders", TradeOfferType.Agreement, 1))))
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertTrue(f.coordinator.hasOpenBorders(f.romeId, f.greeceId))
    }

    @Test
    fun `defensive pact treaty through trade changes status`() {
        val f = Fixture()
        // Vanilla gates pacts behind Chivalry (Enables Defensive Pacts).
        val rome = f.testGame.gameInfo.getCivilization(f.romeId)
        val greece = f.testGame.gameInfo.getCivilization(f.greeceId)
        rome.tech.addTechnology("Chivalry")
        greece.tech.addTechnology("Chivalry")
        f.rebuild()
        f.coordinator.execute(FriendshipCommand(f.romeId, "f1", f.rev(), f.greeceId))
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(TradeOfferDto("Defensive Pact", TradeOfferType.Treaty, 1)),
            theirOffers = listOf(TradeOfferDto("Defensive Pact", TradeOfferType.Treaty, 1))))
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals("DefensivePact",
            f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
    }

    @Test
    fun `counteroffer composes from decline plus richer re-proposal`() {
        val f = Fixture()
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(10)), theirOffers = emptyList()))
        val declined = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = false)) as TradeRespondedResult
        Assert.assertFalse(declined.accepted)

        // The counter: same shape, sweeter terms. Composes from ordinary
        // commands — no dedicated counteroffer command exists.
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "t2", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        val goldBefore = f.coordinator.getGold(f.greeceId)
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "r2", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals(goldBefore + 150, f.coordinator.getGold(f.greeceId))
    }

    @Test
    fun `demand issue and answer cycle with fences`() {
        val f = Fixture()
        val issued = f.coordinator.execute(
            DemandCommand(f.romeId, "d1", f.rev(), f.greeceId, "DoNotSettleNearUs")) as DemandIssuedResult
        Assert.assertTrue(issued.issued)

        // A repeat while the demand is still open is rejected.
        try {
            f.coordinator.execute(DemandCommand(f.romeId, "d2", f.rev(), f.greeceId, "DoNotSettleNearUs"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        // Greece answers: agreeing sets the promise flag the fence reads.
        val answered = f.coordinator.execute(
            RespondDemandCommand(f.greeceId, "rd1", f.rev(),
                f.romeId, "DoNotSettleNearUs", accept = true)) as DemandRespondedResult
        Assert.assertTrue(answered.accepted)
        try {
            f.coordinator.execute(DemandCommand(f.romeId, "d3", f.rev(), f.greeceId, "DoNotSettleNearUs"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        // Answering again is invalid — the demand was consumed.
        try {
            f.coordinator.execute(RespondDemandCommand(f.greeceId, "rd2", f.rev(),
                f.romeId, "DoNotSettleNearUs", accept = false))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }
}
