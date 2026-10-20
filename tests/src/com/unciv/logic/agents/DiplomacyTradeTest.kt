package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
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
            testGame.gameInfo.currentPlayer = romeId
            testGame.gameInfo.currentPlayerCiv = rome

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

        /** Selects the actor in this command-level fixture, preserving offers and receipts.
         * Turn rotation and start/end effects are covered by the lifecycle suites. */
        fun selectActor(civId: String) {
            if (coordinator.getCurrentCivId() == civId) return
            val serializer = com.unciv.json.json().apply { setUsePrototypes(false) }
            val bundle = serializer.fromJson(AgentBundle::class.java, coordinator.saveToJson())
            val snapshot = serializer.fromJson(com.unciv.logic.GameInfo::class.java, bundle.gameJson)
            snapshot.currentPlayer = civId
            bundle.gameJson = serializer.toJson(snapshot)
            coordinator = MatchCoordinator.loadFromJson(serializer.toJson(bundle), writer)
        }

        fun executeOnActorsTurn(command: AgentCommand): CommandResult {
            selectActor(command.actorCivId)
            return coordinator.execute(command)
        }

        fun rev() = coordinator.getStateRevision()
        fun goldGift(amount: Int) = TradeOfferDto("Gold", TradeOfferType.Gold, amount)
        fun peaceTreaty() = TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)
        fun snapshot() = coordinator.deserializeForPublication(coordinator.serializeCommittedGameForPublication())

        /** Models an older saved request without routing it through today's proposal validation. */
        fun editSavedBundle(edit: (GameInfo, AgentMatchState) -> Unit) {
            val serializer = com.unciv.json.json().apply { setUsePrototypes(false) }
            val bundle = serializer.fromJson(AgentBundle::class.java, coordinator.saveToJson())
            val game = snapshot()
            val match = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
            edit(game, match)
            bundle.gameJson = serializer.toJson(game)
            bundle.matchJson = serializer.toJson(match)
            coordinator = MatchCoordinator.loadFromJson(serializer.toJson(bundle), writer)
        }

        fun prepareThirdPartyWar(warringCivId: String) {
            val aztecs = testGame.gameInfo.getCivilization(aztecId)
            testGame.addUnit("Warrior", aztecs, testGame.getTile(-2, 0))
            for (civId in listOf(romeId, greeceId))
                testGame.gameInfo.getCivilization(civId).diplomacyFunctions.makeCivilizationsMeet(aztecs)
            testGame.gameInfo.getCivilization(warringCivId).getDiplomacyManager(aztecs)!!
                .declareWar(DeclareWarReason(WarType.DirectWar))
            rebuild()
        }

        fun addLegacyThirdPartyPeaceRequest(name: String = aztecId) {
            editSavedBundle { game, _ ->
                val trade = Trade()
                trade.ourOffers.add(TradeOffer(name, TradeOfferType.PeaceProposal, 1, game.speed))
                trade.theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 100, game.speed))
                game.getCivilization(greeceId).tradeRequests.add(TradeRequest(romeId, trade))
            }
        }

        fun addCityState(): String {
            val cityState = testGame.addCiv(testGame.ruleset.nations["Geneva"]!!)
            testGame.addUnit("Warrior", cityState, testGame.getTile(2, 0))
            for (civId in listOf(romeId, greeceId))
                testGame.gameInfo.getCivilization(civId).diplomacyFunctions.makeCivilizationsMeet(cityState)
            rebuild()
            editSavedBundle { _, match ->
                match.controllers[cityState.civID] = CivilizationController(cityState.civID, ControllerKind.BUILTIN_AI)
            }
            return cityState.civID
        }

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

    private fun rejectedWithoutMutation(
        f: Fixture, command: AgentCommand, code: AgentCommandError
    ): AgentCommandException {
        val before = f.coordinator.saveToJson()
        val writes = f.writer.writeCount
        try {
            f.coordinator.execute(command)
            Assert.fail("expected $code")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(code, ex.code)
            Assert.assertEquals(before, f.coordinator.saveToJson())
            Assert.assertEquals(writes, f.writer.writeCount)
            Assert.assertFalse(f.coordinator.isPaused())
            return ex
        }
        throw AssertionError("unreachable")
    }

    @Test
    fun `fresh direct major peace rejects without ending either side of the war`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        val error = rejectedWithoutMutation(f,
            MakePeaceCommand(f.romeId, "direct-peace", f.rev(), f.greeceId), AgentCommandError.ILLEGAL_ACTION)
        Assert.assertTrue(error.message!!.contains("diplomacy.proposeTrade"))
        Assert.assertTrue(error.message!!.contains("diplomacy.respondTrade"))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        Assert.assertNull(f.coordinator.getReceipt("direct-peace"))
    }

    @Test
    fun `malformed bilateral peace proposal returns treaty schema guidance`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        val error = rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "bad-peace", f.rev(), f.greeceId,
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.PeaceProposal, 1)), emptyList()),
            AgentCommandError.INVALID_ARGUMENT)
        Assert.assertTrue(error.message!!.contains("Treaty"))
        Assert.assertTrue(error.message!!.contains("ourOffers"))
        Assert.assertTrue(error.message!!.contains("theirOffers"))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
    }

    @Test
    fun `invalid offers name each failing item once`() {
        val f = Fixture()
        // At peace already, and Rome holds only 200 Gold.
        val error = rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "peace-at-peace", f.rev(), f.greeceId,
            listOf(f.peaceTreaty(), f.goldGift(2000)), listOf(f.peaceTreaty())), AgentCommandError.ILLEGAL_ACTION)
        val message = error.message!!
        Assert.assertTrue(message, message.startsWith("Offer is not valid: "))
        Assert.assertTrue(message, message.contains("Peace Treaty: you are not at war with ${f.greeceId} (status Peace)"))
        Assert.assertEquals(message, 1, Regex("Peace Treaty:").findAll(message).count())
        Assert.assertTrue(message, message.contains("2000 Gold exceeds the 200 Gold you have"))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())

        val theirs = rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "ask-gold", f.rev(), f.greeceId,
            emptyList(), listOf(f.goldGift(5000))), AgentCommandError.ILLEGAL_ACTION).message!!
        Assert.assertTrue(theirs, theirs.contains("5000 Gold exceeds the ${f.snapshot().getCivilization(f.greeceId).gold} Gold ${f.greeceId} has"))
    }

    @Test
    fun `third party peace offers cannot impose peace on an unconsenting major`() {
        for (onOurSide in listOf(true, false)) {
            val f = Fixture()
            f.prepareThirdPartyWar(if (onOurSide) f.romeId else f.greeceId)
            val peace = listOf(TradeOfferDto(f.aztecId, TradeOfferType.PeaceProposal, 1))
            val error = rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "mediated-peace", f.rev(), f.greeceId,
                if (onOurSide) peace else emptyList(), if (onOurSide) emptyList() else peace),
                AgentCommandError.ILLEGAL_ACTION)
            Assert.assertTrue(error.message!!.contains("consent"))
            Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
            Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(
                if (onOurSide) f.romeId else f.greeceId, f.aztecId))
        }
    }

    @Test
    fun `unknown third party peace name is a schema rejection without a native lookup failure`() {
        val f = Fixture()
        val error = rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "unknown-peace", f.rev(), f.greeceId,
            listOf(TradeOfferDto("unknown-civ", TradeOfferType.PeaceProposal, 1)), emptyList()),
            AgentCommandError.INVALID_ARGUMENT)
        Assert.assertTrue(error.message!!.contains("civilization"))
        Assert.assertTrue(error.message!!.contains("PeaceProposal"))
        Assert.assertTrue(error.message!!.contains("Treaty"))
    }

    @Test
    fun `legacy third party major peace cannot be accepted by a different recipient`() {
        val f = Fixture()
        f.prepareThirdPartyWar(f.greeceId)
        f.addLegacyThirdPartyPeaceRequest()
        f.selectActor(f.greeceId)
        val error = rejectedWithoutMutation(f,
            RespondTradeCommand(f.greeceId, "unsafe-accept", f.rev(), f.romeId, true),
            AgentCommandError.ILLEGAL_ACTION)
        Assert.assertTrue(error.message!!.contains("consent"))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        Assert.assertEquals(200, f.coordinator.getGold(f.romeId))
    }

    @Test
    fun `built in turn discards unsafe legacy peace before native trade automation`() {
        val f = Fixture()
        f.prepareThirdPartyWar(f.greeceId)
        f.addLegacyThirdPartyPeaceRequest()
        f.editSavedBundle { _, match ->
            match.controllers[f.greeceId] = CivilizationController(f.greeceId, ControllerKind.BUILTIN_AI)
            match.controllers[f.aztecId] = CivilizationController(f.aztecId, ControllerKind.HUMAN)
        }
        val initial = f.snapshot()
        val greece = initial.getCivilization(f.greeceId)
        val rome = initial.getCivilization(f.romeId)
        val legacy = greece.tradeRequests.single()
        // Establish that the old native path would accept this real offer.
        Assert.assertTrue(TradeEvaluation().isTradeValid(legacy.trade, greece, rome))
        Assert.assertTrue(TradeEvaluation().isTradeAcceptable(legacy.trade, greece, rome))
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        Assert.assertEquals(f.aztecId, f.coordinator.getCurrentCivId())
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.aztecId, f.greeceId))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        Assert.assertEquals(200, f.coordinator.getGold(f.romeId))
    }

    @Test
    fun `bilateral peace stays queued until recipient accepts on its real turn`() {
        val f = Fixture()
        val war = f.coordinator.execute(
            DeclareWarCommand(f.romeId, "w1", f.rev(), f.greeceId)) as DiplomacyResult
        Assert.assertEquals("War", war.status)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))

        rejectedWithoutMutation(f, DeclareWarCommand(f.romeId, "w2", f.rev(), f.greeceId),
            AgentCommandError.ILLEGAL_ACTION)
        val proposalRevision = f.rev()
        val proposal = ProposeTradeCommand(f.romeId, "offer-peace", proposalRevision, f.greeceId,
            listOf(f.peaceTreaty(), f.goldGift(25)), listOf(f.peaceTreaty()))
        val proposed = f.coordinator.execute(proposal) as TradeProposedResult
        Assert.assertEquals(1, proposed.pendingRequestsWithTarget)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        Assert.assertEquals(200, f.coordinator.getGold(f.romeId))
        val stored = f.snapshot().getCivilization(f.greeceId).tradeRequests.single().trade
        Assert.assertEquals(listOf("Peace Treaty"), stored.ourOffers.map { it.name })
        Assert.assertEquals(listOf("Peace Treaty", "Gold"), stored.theirOffers.map { it.name })
        Assert.assertEquals(25, stored.theirOffers.single { it.type == TradeOfferType.Gold }.amount)
        val writesAfterProposal = f.writer.writeCount
        Assert.assertEquals(proposed.receipt.afterRevision,
            (f.coordinator.execute(proposal) as TradeProposedResult).receipt.afterRevision)
        Assert.assertEquals(writesAfterProposal, f.writer.writeCount)

        rejectedWithoutMutation(f, RespondTradeCommand(f.greeceId, "early-accept", f.rev(), f.romeId, true),
            AgentCommandError.NOT_YOUR_TURN)
        rejectedWithoutMutation(f, RespondTradeCommand(f.romeId, "self-accept", f.rev(), f.greeceId, true),
            AgentCommandError.INVALID_ARGUMENT)
        rejectedWithoutMutation(f, MakePeaceCommand(f.romeId, "bypass-offer", f.rev(), f.greeceId),
            AgentCommandError.ILLEGAL_ACTION)
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))

        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        Assert.assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        val romeGold = f.coordinator.getGold(f.romeId)
        val greeceGold = f.coordinator.getGold(f.greeceId)
        val acceptRevision = f.rev()
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "accept-peace", acceptRevision, f.romeId, true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals("Peace", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("Peace", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        Assert.assertEquals(romeGold - 25, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(greeceGold + 25, f.coordinator.getGold(f.greeceId))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        val state = f.snapshot()
        Assert.assertEquals(state.speed.peaceDealDuration,
            state.getCivilization(f.romeId).getDiplomacyManager(f.greeceId)!!.turnsToPeaceTreaty())
        Assert.assertEquals(state.speed.peaceDealDuration,
            state.getCivilization(f.greeceId).getDiplomacyManager(f.romeId)!!.turnsToPeaceTreaty())
        rejectedWithoutMutation(f, DeclareWarCommand(f.greeceId, "break-treaty", f.rev(), f.romeId),
            AgentCommandError.ILLEGAL_ACTION)
        val restoredWriter = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson(), restoredWriter)
        val replay = restored.execute(
            RespondTradeCommand(f.greeceId, "accept-peace", acceptRevision, f.romeId, true)) as TradeRespondedResult
        Assert.assertTrue(replay.accepted)
        Assert.assertEquals(accepted.receipt.afterRevision, replay.receipt.afterRevision)
        Assert.assertEquals(f.rev(), restored.getStateRevision())
        Assert.assertEquals(greeceGold + 25, restored.getGold(f.greeceId))
        Assert.assertEquals(0, restoredWriter.writeCount)
    }

    @Test
    fun `declining peace on recipient turn preserves war and the offered gold`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "peace", f.rev(), f.greeceId,
            listOf(f.peaceTreaty(), f.goldGift(25)), listOf(f.peaceTreaty())))
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        val romeGold = f.coordinator.getGold(f.romeId)
        val greeceGold = f.coordinator.getGold(f.greeceId)
        val result = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "decline-peace", f.rev(), f.romeId, false)) as TradeRespondedResult
        Assert.assertFalse(result.accepted)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        Assert.assertEquals(romeGold, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(greeceGold, f.coordinator.getGold(f.greeceId))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
    }

    @Test
    fun `peace acceptance checks revision and checkpoints before exposing effects`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "peace", f.rev(), f.greeceId,
            listOf(f.peaceTreaty(), f.goldGift(25)), listOf(f.peaceTreaty())))
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        rejectedWithoutMutation(f, RespondTradeCommand(f.greeceId, "stale-accept", f.rev() - 1, f.romeId, true),
            AgentCommandError.STALE_STATE)
        val revision = f.rev()
        val writes = f.writer.writeCount
        val romeGold = f.coordinator.getGold(f.romeId)
        val greeceGold = f.coordinator.getGold(f.greeceId)
        f.writer.failNextWrite = true
        try {
            f.coordinator.execute(RespondTradeCommand(f.greeceId, "failed-accept", revision, f.romeId, true))
            Assert.fail("expected checkpoint failure")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        Assert.assertTrue(f.coordinator.isPaused())
        Assert.assertEquals(revision, f.rev())
        Assert.assertEquals(writes, f.writer.writeCount)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        Assert.assertEquals(romeGold, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(greeceGold, f.coordinator.getGold(f.greeceId))
        Assert.assertNull(f.coordinator.getReceipt("failed-accept"))
    }

    @Test
    fun `bilateral peace validates both raw offer sides before duplicate amounts merge`() {
        val cases = listOf(
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)) to emptyList(),
            emptyList<TradeOfferDto>() to listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)),
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Agreement, 1)) to listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)),
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 0)) to listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)),
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1), TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, -1)) to emptyList(),
            listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1), TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1)) to listOf(TradeOfferDto("Peace Treaty", TradeOfferType.Treaty, 1))
        )
        for ((ourOffers, theirOffers) in cases) {
            val f = Fixture()
            f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
            rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "bad-shape", f.rev(), f.greeceId,
                ourOffers, theirOffers), AgentCommandError.INVALID_ARGUMENT)
            Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
            Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        }
    }

    @Test
    fun `malformed legacy peace rejects acceptance but permits an explicit decline`() {
        for (name in listOf("unknown-civ", "Peace Treaty")) {
            val f = Fixture()
            f.prepareThirdPartyWar(f.greeceId)
            f.addLegacyThirdPartyPeaceRequest(name)
            f.selectActor(f.greeceId)
            rejectedWithoutMutation(f, RespondTradeCommand(f.greeceId, "accept", f.rev(), f.romeId, true),
                AgentCommandError.INVALID_ARGUMENT)
            val declined = f.coordinator.execute(
                RespondTradeCommand(f.greeceId, "decline", f.rev(), f.romeId, false)) as TradeRespondedResult
            Assert.assertFalse(declined.accepted)
            Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
            Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        }
    }

    @Test
    fun `commandable start turn removes malformed peace and preserves ordinary and declinable requests`() {
        for (malformedName in listOf("unknown-civ", "Peace Treaty")) {
            val f = Fixture()
            f.prepareThirdPartyWar(f.greeceId)
            f.addLegacyThirdPartyPeaceRequest(malformedName)
            f.addLegacyThirdPartyPeaceRequest()
            f.editSavedBundle { game, _ ->
                val gift = Trade()
                gift.theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 25, game.speed))
                game.getCivilization(f.greeceId).tradeRequests.add(TradeRequest(f.romeId, gift))
            }
            f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
            Assert.assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
            Assert.assertFalse(f.coordinator.isPaused())
            Assert.assertEquals(listOf(f.romeId, f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
            rejectedWithoutMutation(f, RespondTradeCommand(f.greeceId, "unsafe", f.rev(), f.romeId, true),
                AgentCommandError.ILLEGAL_ACTION)
            f.coordinator.execute(RespondTradeCommand(f.greeceId, "decline-unsafe", f.rev(), f.romeId, false))
            val romeGold = f.coordinator.getGold(f.romeId)
            val accepted = f.coordinator.execute(
                RespondTradeCommand(f.greeceId, "ordinary-gift", f.rev(), f.romeId, true)) as TradeRespondedResult
            Assert.assertTrue(accepted.accepted)
            Assert.assertEquals(romeGold - 25, f.coordinator.getGold(f.romeId))
            Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
            Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        }
    }

    private fun rotateWithMixedLegacyPeace(majorFirst: Boolean, malformedOnTheirSide: Boolean = false) {
        val f = Fixture()
        f.prepareThirdPartyWar(f.greeceId)
        f.editSavedBundle { game, _ ->
            val mixed = Trade()
            val majorPeace = TradeOffer(f.aztecId, TradeOfferType.PeaceProposal, 1, game.speed)
            val unknownPeace = TradeOffer("unknown-civ", TradeOfferType.PeaceProposal, 1, game.speed)
            if (malformedOnTheirSide) {
                mixed.ourOffers.add(majorPeace)
                mixed.theirOffers.add(unknownPeace)
            } else {
                mixed.ourOffers.add(if (majorFirst) majorPeace else unknownPeace)
                mixed.ourOffers.add(if (majorFirst) unknownPeace else majorPeace)
            }
            val gift = Trade()
            gift.theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 25, game.speed))
            val recipient = game.getCivilization(f.greeceId)
            recipient.tradeRequests.add(TradeRequest(f.romeId, mixed))
            recipient.tradeRequests.add(TradeRequest(f.romeId, gift))
        }
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        Assert.assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        Assert.assertFalse(f.coordinator.isPaused())
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        val remaining = f.snapshot().getCivilization(f.greeceId).tradeRequests.single().trade
        Assert.assertTrue(remaining.ourOffers.isEmpty())
        Assert.assertEquals(listOf("Gold"), remaining.theirOffers.map { it.name })
        val romeGold = f.coordinator.getGold(f.romeId)
        val accepted = f.coordinator.execute(
            RespondTradeCommand(f.greeceId, "accept-gift", f.rev(), f.romeId, true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals(romeGold - 25, f.coordinator.getGold(f.romeId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
    }

    @Test
    fun `major then unknown peace in one legacy request cannot block the recipient turn`() {
        rotateWithMixedLegacyPeace(majorFirst = true)
    }

    @Test
    fun `unknown then major peace in one legacy request is sanitized before the recipient turn`() {
        rotateWithMixedLegacyPeace(majorFirst = false)
    }

    @Test
    fun `unknown peace on the opposite side of a legacy major proposal cannot block the recipient turn`() {
        rotateWithMixedLegacyPeace(majorFirst = true, malformedOnTheirSide = true)
    }

    @Test
    fun `major followed by malformed city state peace is removed before the recipient turn`() {
        val f = Fixture()
        f.prepareThirdPartyWar(f.greeceId)
        val cityStateId = f.addCityState()
        f.editSavedBundle { game, _ ->
            val recipient = game.getCivilization(f.greeceId)
            recipient.getDiplomacyManager(cityStateId)!!.declareWar(DeclareWarReason(WarType.DirectWar))
            val mixed = Trade()
            mixed.ourOffers.add(TradeOffer(f.aztecId, TradeOfferType.PeaceProposal, 1, game.speed))
            mixed.ourOffers.add(TradeOffer(cityStateId, TradeOfferType.PeaceProposal, 2, game.speed))
            recipient.tradeRequests.add(TradeRequest(f.romeId, mixed))
        }
        val initial = f.snapshot()
        val recipient = initial.getCivilization(f.greeceId)
        Assert.assertTrue(TradeEvaluation().isTradeValid(recipient.tradeRequests.single().trade,
            recipient, initial.getCivilization(f.romeId)))
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        Assert.assertEquals(f.greeceId, f.coordinator.getCurrentCivId())
        Assert.assertFalse(f.coordinator.isPaused())
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, cityStateId))
    }

    @Test
    fun `duplicate city state peace on proposer side rejects before queueing a merged invalid offer`() {
        val f = Fixture()
        val cityStateId = f.addCityState()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), cityStateId))
        val peace = TradeOfferDto(cityStateId, TradeOfferType.PeaceProposal, 1)
        rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "duplicate-peace", f.rev(), f.greeceId,
            listOf(peace, peace, f.goldGift(25)), emptyList()), AgentCommandError.INVALID_ARGUMENT)
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        Assert.assertNull(f.coordinator.getReceipt("duplicate-peace"))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, cityStateId))
    }

    @Test
    fun `duplicate city state peace on recipient side rejects before queueing a merged invalid offer`() {
        val f = Fixture()
        val cityStateId = f.addCityState()
        f.editSavedBundle { game, _ ->
            game.getCivilization(f.greeceId).getDiplomacyManager(cityStateId)!!
                .declareWar(DeclareWarReason(WarType.DirectWar))
        }
        val peace = TradeOfferDto(cityStateId, TradeOfferType.PeaceProposal, 1)
        rejectedWithoutMutation(f, ProposeTradeCommand(f.romeId, "duplicate-peace", f.rev(), f.greeceId,
            listOf(f.goldGift(25)), listOf(peace, peace)), AgentCommandError.INVALID_ARGUMENT)
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        Assert.assertNull(f.coordinator.getReceipt("duplicate-peace"))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, cityStateId))
    }

    @Test
    fun `turn sanitation rolls back with a failed checkpoint`() {
        val f = Fixture()
        f.prepareThirdPartyWar(f.greeceId)
        f.addLegacyThirdPartyPeaceRequest("unknown-civ")
        val revision = f.rev()
        val writes = f.writer.writeCount
        f.writer.failNextWrite = true
        try {
            f.coordinator.execute(EndTurnCommand(f.romeId, "failed-turn", revision))
            Assert.fail("expected checkpoint failure")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        Assert.assertTrue(f.coordinator.isPaused())
        Assert.assertEquals(revision, f.rev())
        Assert.assertEquals(writes, f.writer.writeCount)
        Assert.assertEquals(f.romeId, f.coordinator.getCurrentCivId())
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        Assert.assertEquals("unknown-civ", f.snapshot().getCivilization(f.greeceId).tradeRequests.single().trade.ourOffers.single().name)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
        Assert.assertNull(f.coordinator.getReceipt("failed-turn"))
    }

    @Test
    fun `historical committed direct major peace receipt replays without applying fresh peace`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        f.editSavedBundle { game, match ->
            game.getCivilization(f.romeId).getDiplomacyManager(f.greeceId)!!.makePeace()
            match.stateRevision = 2
            match.currentTurnReceipts.add(ActionReceipt("legacy-peace", f.romeId, "t1",
                "diplomacy.makePeace|${f.greeceId}", 1, 2, "applied", ArrayList(), "Peace"))
        }
        val before = f.coordinator.saveToJson()
        val writes = f.writer.writeCount
        val replay = f.coordinator.execute(MakePeaceCommand(f.romeId, "legacy-peace", 1, f.greeceId)) as DiplomacyResult
        Assert.assertEquals("Peace", replay.status)
        Assert.assertEquals(2, replay.receipt.afterRevision)
        Assert.assertEquals(before, f.coordinator.saveToJson())
        Assert.assertEquals(writes, f.writer.writeCount)
        rejectedWithoutMutation(f, MakePeaceCommand(f.romeId, "fresh-peace", f.rev(), f.greeceId),
            AgentCommandError.ILLEGAL_ACTION)
        rejectedWithoutMutation(f, MakePeaceCommand(f.romeId, "legacy-peace", f.rev(), f.aztecId),
            AgentCommandError.ACTION_ID_CONFLICT)
    }

    @Test
    fun `city state direct peace retains native status and absence of treaty cooldown`() {
        val f = Fixture()
        val cityStateId = f.addCityState()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), cityStateId))
        val peace = f.coordinator.execute(MakePeaceCommand(f.romeId, "peace", f.rev(), cityStateId)) as DiplomacyResult
        Assert.assertEquals("Peace", peace.status)
        Assert.assertEquals("Peace", f.coordinator.getDiplomaticStatus(f.romeId, cityStateId))
        Assert.assertEquals("Peace", f.coordinator.getDiplomaticStatus(cityStateId, f.romeId))
        val state = f.snapshot()
        Assert.assertEquals(0, state.getCivilization(f.romeId).getDiplomacyManager(cityStateId)!!.turnsToPeaceTreaty())
        Assert.assertTrue(state.getCivilization(cityStateId).getDiplomacyManager(f.romeId)!!.canDeclareWar())
        rejectedWithoutMutation(f, MakePeaceCommand(f.romeId, "second-peace", f.rev(), cityStateId),
            AgentCommandError.ILLEGAL_ACTION)
    }

    @Test
    fun `city state actor cannot end war with a major target using direct peace`() {
        val f = Fixture()
        val cityStateId = f.addCityState()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), cityStateId))
        f.editSavedBundle { game, match ->
            game.currentPlayer = cityStateId
            match.controllers[cityStateId] = CivilizationController(cityStateId, ControllerKind.HUMAN)
        }
        rejectedWithoutMutation(f, MakePeaceCommand(cityStateId, "force-major-peace", f.rev(), f.romeId),
            AgentCommandError.ILLEGAL_ACTION)
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, cityStateId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(cityStateId, f.romeId))
    }

    @Test
    fun `changed gold invalidates queued peace before recipient acceptance`() {
        val f = Fixture()
        f.coordinator.execute(DeclareWarCommand(f.romeId, "war", f.rev(), f.greeceId))
        f.coordinator.execute(ProposeTradeCommand(f.romeId, "peace", f.rev(), f.greeceId,
            listOf(f.peaceTreaty(), f.goldGift(150)), listOf(f.peaceTreaty())))
        f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
        f.editSavedBundle { game, _ -> game.getCivilization(f.romeId).addGold(-180) }
        rejectedWithoutMutation(f, RespondTradeCommand(f.greeceId, "expired-accept", f.rev(), f.romeId, true),
            AgentCommandError.ILLEGAL_ACTION)
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
        Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.romeId))
    }

    @Test
    fun `built in legacy sanitation removes malformed peace without consuming ordinary gift`() {
        for (name in listOf("unknown-civ", "Peace Treaty")) {
            val f = Fixture()
            f.prepareThirdPartyWar(f.greeceId)
            f.addLegacyThirdPartyPeaceRequest(name)
            f.editSavedBundle { game, match ->
                val gift = Trade()
                gift.theirOffers.add(TradeOffer("Gold", TradeOfferType.Gold, 25, game.speed))
                game.getCivilization(f.greeceId).tradeRequests.add(TradeRequest(f.romeId, gift))
                match.controllers[f.greeceId] = CivilizationController(f.greeceId, ControllerKind.BUILTIN_AI)
                match.controllers[f.aztecId] = CivilizationController(f.aztecId, ControllerKind.HUMAN)
            }
            f.coordinator.execute(EndTurnCommand(f.romeId, "end-rome", f.rev()))
            Assert.assertEquals(f.aztecId, f.coordinator.getCurrentCivId())
            Assert.assertFalse(f.coordinator.isPaused())
            Assert.assertEquals("War", f.coordinator.getDiplomaticStatus(f.greeceId, f.aztecId))
            Assert.assertEquals(175, f.coordinator.getGold(f.romeId))
            Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())
        }
    }

    @Test
    fun `diplomacy without contact is illegal`() {
        val f = Fixture()
        try {
            f.executeOnActorsTurn(DeclareWarCommand(f.romeId, "w1", f.rev(), f.aztecId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        try {
            f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.aztecId,
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

        val proposed = f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(100)), theirOffers = emptyList())) as TradeProposedResult
        Assert.assertEquals(1, proposed.pendingRequestsWithTarget)
        Assert.assertEquals(listOf(f.romeId), f.coordinator.getIncomingTradeRequests(f.greeceId))

        val accepted = f.executeOnActorsTurn(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals(romeBefore - 100, f.coordinator.getGold(f.romeId))
        Assert.assertEquals(greeceBefore + 100, f.coordinator.getGold(f.greeceId))
        Assert.assertTrue(f.coordinator.getIncomingTradeRequests(f.greeceId).isEmpty())

        // A pure gift back, declined: request gone, balances untouched.
        f.executeOnActorsTurn(ProposeTradeCommand(f.greeceId, "t2", f.rev(), f.romeId,
            ourOffers = listOf(f.goldGift(50)), theirOffers = emptyList()))
        val declined = f.executeOnActorsTurn(
            RespondTradeCommand(f.romeId, "r2", f.rev(), f.greeceId, accept = false)) as TradeRespondedResult
        Assert.assertFalse(declined.accepted)
        Assert.assertEquals(greeceBefore + 100, f.coordinator.getGold(f.greeceId))
    }

    @Test
    fun `spent resources invalidate queued offers`() {
        val f = Fixture()
        // Rome holds 200: two 150-gold gifts are each valid alone (0.9 factor) ...
        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t2", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        // ... but accepting the first spends the gold out from under the second.
        f.executeOnActorsTurn(RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true))
        try {
            // Same action namespace, second request still queued: re-accept it.
            f.executeOnActorsTurn(RespondTradeCommand(f.greeceId, "r2", f.rev(), f.romeId, accept = true))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
    }

    @Test
    fun `answering a missing request is invalid`() {
        val f = Fixture()
        try {
            f.executeOnActorsTurn(RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }

    @Test
    fun `friendship signs once, open borders trade through`() {
        val f = Fixture()
        f.executeOnActorsTurn(FriendshipCommand(f.romeId, "f1", f.rev(), f.greeceId))
        try {
            f.executeOnActorsTurn(FriendshipCommand(f.romeId, "f2", f.rev(), f.greeceId))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(TradeOfferDto("Open Borders", TradeOfferType.Agreement, 1)),
            theirOffers = listOf(TradeOfferDto("Open Borders", TradeOfferType.Agreement, 1))))
        val accepted = f.executeOnActorsTurn(
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
        f.executeOnActorsTurn(FriendshipCommand(f.romeId, "f1", f.rev(), f.greeceId))
        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(TradeOfferDto("Defensive Pact", TradeOfferType.Treaty, 1)),
            theirOffers = listOf(TradeOfferDto("Defensive Pact", TradeOfferType.Treaty, 1))))
        val accepted = f.executeOnActorsTurn(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals("DefensivePact",
            f.coordinator.getDiplomaticStatus(f.romeId, f.greeceId))
    }

    @Test
    fun `counteroffer composes from decline plus richer re-proposal`() {
        val f = Fixture()
        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t1", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(10)), theirOffers = emptyList()))
        val declined = f.executeOnActorsTurn(
            RespondTradeCommand(f.greeceId, "r1", f.rev(), f.romeId, accept = false)) as TradeRespondedResult
        Assert.assertFalse(declined.accepted)

        // The counter: same shape, sweeter terms. Composes from ordinary
        // commands — no dedicated counteroffer command exists.
        f.executeOnActorsTurn(ProposeTradeCommand(f.romeId, "t2", f.rev(), f.greeceId,
            ourOffers = listOf(f.goldGift(150)), theirOffers = emptyList()))
        val goldBefore = f.coordinator.getGold(f.greeceId)
        val accepted = f.executeOnActorsTurn(
            RespondTradeCommand(f.greeceId, "r2", f.rev(), f.romeId, accept = true)) as TradeRespondedResult
        Assert.assertTrue(accepted.accepted)
        Assert.assertEquals(goldBefore + 150, f.coordinator.getGold(f.greeceId))
    }

    @Test
    fun `demand issue and answer cycle with fences`() {
        val f = Fixture()
        val issued = f.executeOnActorsTurn(
            DemandCommand(f.romeId, "d1", f.rev(), f.greeceId, "DoNotSettleNearUs")) as DemandIssuedResult
        Assert.assertTrue(issued.issued)

        // A repeat while the demand is still open is rejected.
        try {
            f.executeOnActorsTurn(DemandCommand(f.romeId, "d2", f.rev(), f.greeceId, "DoNotSettleNearUs"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        // Greece answers: agreeing sets the promise flag the fence reads.
        val answered = f.executeOnActorsTurn(
            RespondDemandCommand(f.greeceId, "rd1", f.rev(),
                f.romeId, "DoNotSettleNearUs", accept = true)) as DemandRespondedResult
        Assert.assertTrue(answered.accepted)
        try {
            f.executeOnActorsTurn(DemandCommand(f.romeId, "d3", f.rev(), f.greeceId, "DoNotSettleNearUs"))
            Assert.fail("expected ILLEGAL_ACTION")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }

        // Answering again is invalid — the demand was consumed.
        try {
            f.executeOnActorsTurn(RespondDemandCommand(f.greeceId, "rd2", f.rev(),
                f.romeId, "DoNotSettleNearUs", accept = false))
            Assert.fail("expected INVALID_ARGUMENT")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INVALID_ARGUMENT, ex.code)
        }
    }
}
