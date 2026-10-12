package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMessagePreflightAndOrderingTest {
    private class Fixture(messages: List<DiplomaticMessage> = emptyList()) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val actor: String
        val target: String
        val spectator: String
        val coordinator: MatchCoordinator
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val r = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val g = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val s = game.addCiv(game.ruleset.nations[Constants.spectator]!!, isPlayer = true)
            actor = r.civID; target = g.civID; spectator = s.civID
            game.addUnit("Warrior", r, game.getTile(0, 0))
            game.addUnit("Warrior", g, game.getTile(3, 0))
            r.diplomacyFunctions.makeCivilizationsMeet(g)
            game.gameInfo.currentPlayer = actor; game.gameInfo.currentPlayerCiv = r
            val match = AgentMatchState(matchId = "message-preflight", timelineId = "t",
                turnPhase = TurnPhase.AWAITING_DECISIONS,
                localControlCivId = spectator, localViewingCivId = spectator)
            match.controllers[actor] = CivilizationController(actor, ControllerKind.HUMAN)
            match.controllers[target] = CivilizationController(target, ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Greece"))
            match.diplomaticMessages.addAll(messages)
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
        }
        fun reject(block: () -> Unit): AgentCommandException {
            try { block(); fail("Expected rejection") } catch (error: AgentCommandException) { return error }
            throw AssertionError("unreachable")
        }
    }

    @Test fun `preflight validates text image identity and contact without changing any committed generation`() {
        val f = Fixture(); val before = f.coordinator.saveToJson()
        val generation = f.coordinator.getPublicationGeneration()
        val image = DiplomaticImageAttachment("a".repeat(64), "image/png", 100, 100, 1000)
        f.coordinator.validatePlayerMessage(f.actor, "image", f.target, "", listOf(image))
        f.coordinator.validatePlayerMessage(f.actor, "text", f.target, "hello")
        assertEquals(AgentCommandError.INVALID_ARGUMENT, f.reject {
            f.coordinator.validatePlayerMessage(f.actor, "bad-image", f.target, "", listOf(image.copy().apply { attachmentId = "bad" }))
        }.code)
        assertEquals(AgentCommandError.ILLEGAL_ACTION, f.reject {
            f.coordinator.validatePlayerMessage(f.actor, "no-contact", f.spectator, "hello")
        }.code)
        assertEquals(before, f.coordinator.saveToJson())
        assertEquals(generation, f.coordinator.getPublicationGeneration())
        assertEquals(0, f.writer.writeCount)
    }

    @Test fun `queue repeats preflight rejects changed authority and deduplicates identical mail`() {
        val f = Fixture()
        f.coordinator.validatePlayerMessage(f.actor, "message", f.target, "hello")
        val sent = f.coordinator.queuePlayerMessage(f.actor, "message", f.target, "hello")
        val writes = f.writer.writeCount
        f.coordinator.validatePlayerMessage(f.actor, "message", f.target, " hello ")
        assertEquals(sent.messageId, f.coordinator.queuePlayerMessage(f.actor, "message", f.target, "hello").messageId)
        assertEquals(writes, f.writer.writeCount)
        assertEquals(AgentCommandError.ACTION_ID_CONFLICT, f.reject {
            f.coordinator.validatePlayerMessage(f.actor, "message", f.target, "different")
        }.code)
        f.coordinator.validatePlayerMessage(f.actor, "before-handback", f.target, "hello")
        f.coordinator.applyNationChange(f.actor, NationControlChange.HAND_TO_AGENT, AgentProfile.defaultFor("Rome"))
        assertEquals(AgentCommandError.AUTHORITY_REVOKED, f.reject {
            f.coordinator.queuePlayerMessage(f.actor, "before-handback", f.target, "hello")
        }.code)
    }

    private fun mail(id: String, turn: Int?, revision: Int?) = DiplomaticMessage(
        messageId = id, acknowledgedTurnSerial = turn, acknowledgedRevision = revision
    )

    @Test fun `mixed legacy acknowledgement group preserves whole saved order and complete groups sort by revision`() {
        val messages = listOf(mail("late", 4, 20), mail("legacy", 4, null), mail("early", 4, 10),
            mail("known-late", 3, 9), mail("unread-a", null, null), mail("known-early", 3, 5), mail("unread-b", null, null))
        val expected = listOf("known-early", "known-late", "late", "legacy", "early", "unread-a", "unread-b")
        val savedOrder = messages.map { it.messageId }
        assertEquals(expected, orderedDiplomaticMessages(messages).map { it.messageId })
        val f = Fixture(messages)
        assertEquals(expected, f.coordinator.getMessageHistory(f.spectator).map { it.messageId })
        assertEquals(savedOrder, messages.map { it.messageId })
    }
}
