package com.unciv.logic.agents

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMessageAcknowledgementTest {
    private class Fixture(messages: List<DiplomaticMessage> = emptyList(), actorKind: ControllerKind = ControllerKind.EXTERNAL_AGENT) {
        val writer = InMemoryCheckpointWriter()
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge
        val actor: String
        val other: String

        init {
            val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            actor = rome.civID
            other = greece.civID
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(3, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            game.gameInfo.currentPlayer = actor
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "ack-match", timelineId = "ack-timeline",
                turnSerial = 5, turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(actor, other)) match.controllers[civ] = CivilizationController(
                civ, ControllerKind.EXTERNAL_AGENT, AgentProfile(displayName = civ))
            match.controllers[actor]!!.kind = actorKind
            match.diplomaticMessages.addAll(messages)
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
            bridge = AgentEngineBridge(coordinator)
        }

        fun ack(messageId: String, text: String = "I read your request and will consider it.",
                actionId: String = "ack-1", revision: Int = coordinator.getStateRevision(),
                actorId: String = actor): Map<String, Any?> = bridge.act(mapOf(
            "protocolVersion" to 1, "actorCivId" to actorId, "actionId" to actionId,
            "expectedRevision" to revision, "type" to "diplomacy.acknowledgeMessage",
            "args" to mapOf("messageId" to messageId, "text" to text)))
    }

    private fun mail(id: String = "incoming", target: String = "Rome", delivered: Int? = 5) =
        DiplomaticMessage(id, "Greece", target, "Please avoid my eastern border.", 2, delivered)

    @Test
    fun `acknowledgement commits a visible response with an idempotent receipt`() {
        val f = Fixture(listOf(mail()))
        val before = f.coordinator.getStateRevision()
        val receipt = f.ack("incoming", " I read your border request. ", revision = before)
        Assert.assertEquals("applied", receipt["status"])
        @Suppress("UNCHECKED_CAST")
        val result = receipt["result"] as Map<String, Any?>
        Assert.assertEquals("I read your border request.", result["acknowledgementText"])
        Assert.assertEquals(5, (result["acknowledgedTurnSerial"] as Number).toInt())
        Assert.assertEquals(before + 1, (result["acknowledgedRevision"] as? Number)?.toInt())
        Assert.assertEquals(before + 1, f.coordinator.getStateRevision())
        val writes = f.writer.writeCount
        Assert.assertEquals(receipt, f.ack("incoming", " I read your border request. ", revision = before))
        Assert.assertEquals(writes, f.writer.writeCount)
        Assert.assertEquals(before + 1, f.coordinator.getStateRevision())
    }

    @Test
    fun `same turn acknowledgement ordering survives reload and receipt replay`() {
        val f = Fixture(listOf(mail("first"), mail("second")))
        f.ack("second", actionId = "ack-second")
        f.ack("first", actionId = "ack-first")
        Assert.assertEquals(listOf("second", "first"), f.coordinator.getMessageHistory(f.actor).map { it.messageId })
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        val messages = restored.getMessageHistory(f.actor)
        Assert.assertEquals(listOf(1, 2), messages.map { it.acknowledgedRevision })
        AgentEngineBridge(restored).act(mapOf("protocolVersion" to 1, "actorCivId" to f.actor,
            "actionId" to "ack-second", "expectedRevision" to 0,
            "type" to "diplomacy.acknowledgeMessage", "args" to mapOf(
                "messageId" to "second", "text" to "I read your request and will consider it.")))
        Assert.assertEquals(listOf("second", "first"), restored.getMessageHistory(f.actor).map { it.messageId })
        Assert.assertEquals(listOf(1, 2), restored.getMessageHistory(f.actor).map { it.acknowledgedRevision })
    }

    @Test
    fun `private recent correspondence exposes replies only to sender and delivered recipient`() {
        val f = Fixture(listOf(mail(), DiplomaticMessage("queued", "Egypt", "Greece", "Hidden", 2),
            DiplomaticMessage("opponents", "Egypt", "Greece", "Secret", 1, 2)))
        f.ack("incoming", "I accept peace.")
        @Suppress("UNCHECKED_CAST")
        val observation = f.bridge.observe(f.actor) as Map<String, Any?>
        val history = observation["diplomaticHistory"] as? List<*>
        Assert.assertNotNull("Observation must include private correspondence", history)
        Assert.assertEquals(listOf("incoming"), history!!.map { (it as Map<*, *>)["messageId"] })
        Assert.assertEquals("I accept peace.", (history.single() as Map<*, *>)["acknowledgementText"])
        @Suppress("UNCHECKED_CAST")
        val sender = f.bridge.observe(f.other) as Map<String, Any?>
        Assert.assertEquals(listOf("incoming", "opponents"),
            (sender["diplomaticHistory"] as List<*>).map { (it as Map<*, *>)["messageId"] })
    }

    @Test
    fun `external agent cannot end its turn with delivered unacknowledged mail`() {
        val f = Fixture(listOf(mail()))
        val before = f.coordinator.saveToJson()
        try {
            f.bridge.endTurn(f.actor, "end-unread", f.coordinator.getStateRevision())
            Assert.fail("Expected MESSAGES_UNACKNOWLEDGED")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("MESSAGES_UNACKNOWLEDGED", ex.code)
            Assert.assertEquals(listOf("incoming"), ex.messageIds)
            Assert.assertTrue(ex.message!!.contains("incoming"))
        }
        Assert.assertEquals(before, f.coordinator.saveToJson())
        Assert.assertEquals(0, f.writer.writeCount)
    }

    @Test
    fun `older delivered unread mail stays in the actor inbox`() {
        val f = Fixture(listOf(mail(delivered = 3), mail("queued", delivered = null),
            mail("future", delivered = 6), mail("foreign", target = "Greece")))
        Assert.assertEquals(listOf("incoming"), f.coordinator.observe(f.actor).inbox.map { it.messageId })
    }

    @Test
    fun `unknown foreign queued and future IDs share a private rejection`() {
        val f = Fixture(listOf(mail("foreign", target = "Greece"),
            mail("queued", delivered = null), mail("future", delivered = 6)))
        val before = f.coordinator.saveToJson()
        for (id in listOf("unknown", "foreign", "queued", "future")) {
            try {
                f.ack(id, actionId = "try-$id")
                Assert.fail("Should reject $id")
            } catch (ex: EngineCallException) {
                Assert.assertEquals("ILLEGAL_ACTION", ex.code)
                Assert.assertEquals("No such delivered incoming message", ex.message)
                Assert.assertNull(ex.messageIds)
            }
        }
        Assert.assertEquals(before, f.coordinator.saveToJson())
        Assert.assertEquals(0, f.writer.writeCount)
    }

    @Test
    fun `acknowledgement validates active actor text and revision without consuming mail`() {
        val f = Fixture(listOf(mail()))
        val before = f.coordinator.saveToJson()
        for ((text, revision, identity) in listOf(
            Triple(" ", 0, f.actor to "INVALID_ARGUMENT"),
            Triple("x".repeat(2001), 0, f.actor to "INVALID_ARGUMENT"),
            Triple("I read this.", -1, f.actor to "STALE_STATE"),
            Triple("I read this.", 0, f.other to "NOT_YOUR_TURN"))) {
            try {
                f.ack("incoming", text, revision = revision, actorId = identity.first)
                Assert.fail("Expected ${identity.second}")
            } catch (ex: EngineCallException) {
                Assert.assertEquals(identity.second, ex.code)
            }
        }
        Assert.assertEquals(before, f.coordinator.saveToJson())
        Assert.assertNull(f.coordinator.observe(f.actor).inbox.single().acknowledgementText)
        Assert.assertEquals(0, f.writer.writeCount)
    }

    @Test
    fun `acknowledged mail is copied saved and replayable without rescanning map memory`() {
        val f = Fixture(listOf(mail(delivered = 3), mail("current")))
        val serializer = com.unciv.json.json()
        fun mapMemory(save: String): String {
            val bundle = serializer.fromJson(AgentBundle::class.java, save)
            val state = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
            return serializer.toJson(state.mapKnowledge)
        }
        val memoryBefore = mapMemory(f.coordinator.saveToJson())
        val first = f.ack("incoming", "I read the old border request.")
        Assert.assertEquals(memoryBefore, mapMemory(f.coordinator.saveToJson()))
        Assert.assertEquals(listOf("current"), f.coordinator.observe(f.actor).inbox.map { it.messageId })
        f.ack("current", "I read today's request.", actionId = "ack-current")
        val snapshot = f.coordinator.getMessageHistory(f.actor).first()
        snapshot.acknowledgementText = "Tampered"
        snapshot.acknowledgedTurnSerial = 99
        Assert.assertEquals("I read the old border request.",
            f.coordinator.getMessageHistory(f.actor).first().acknowledgementText)
        val restoredWriter = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson(), restoredWriter)
        val bridge = AgentEngineBridge(restored)
        val retry = bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to f.actor,
            "actionId" to "ack-1", "expectedRevision" to 0,
            "type" to "diplomacy.acknowledgeMessage", "args" to mapOf(
                "messageId" to "incoming", "text" to "I read the old border request.")))
        Assert.assertEquals(first["revision"], retry["revision"])
        Assert.assertEquals(first["result"], retry["result"])
        Assert.assertEquals(0, restoredWriter.writeCount)
        bridge.endTurn(f.actor, "end-read", restored.getStateRevision())
        bridge.endTurn(f.other, "end-other", restored.getStateRevision())
        Assert.assertTrue(restored.observe(f.actor).inbox.isEmpty())
        // The acknowledgement receipt remains durable after the turn-scoped receipt list rotates.
        val replay = bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to f.actor,
            "actionId" to "ack-1", "expectedRevision" to 0,
            "type" to "diplomacy.acknowledgeMessage", "args" to mapOf(
                "messageId" to "incoming", "text" to "I read the old border request.")))
        Assert.assertEquals(first["revision"], replay["revision"])
        Assert.assertEquals(2, restoredWriter.writeCount)
        @Suppress("UNCHECKED_CAST")
        val recovered = bridge.actionResult("ack-1", f.actor) as Map<String, Any?>
        Assert.assertEquals(first["result"], recovered["result"])
        val loadedAgain = MatchCoordinator.loadFromJson(restored.saveToJson())
        Assert.assertEquals(recovered, AgentEngineBridge(loadedAgain).actionResult("ack-1", f.actor))
    }

    @Test
    fun `failed acknowledgement persistence publishes no acknowledgement or receipt`() {
        val f = Fixture(listOf(mail()))
        f.writer.failNextWrite = true
        try {
            f.ack("incoming")
            Assert.fail("Expected failed checkpoint")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("INTERNAL_ERROR", ex.code)
        }
        val message = f.coordinator.getMessageHistory(f.actor).single()
        Assert.assertNull(message.acknowledgedTurnSerial)
        Assert.assertNull(message.acknowledgementText)
        Assert.assertNull(com.unciv.json.json().toJson(message).let { com.badlogic.gdx.utils.JsonReader().parse(it).get("acknowledgedRevision") })
        Assert.assertEquals(0, f.coordinator.getStateRevision())
        val serializer = com.unciv.json.json()
        val bundle = serializer.fromJson(AgentBundle::class.java, f.coordinator.saveToJson())
        val state = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
        Assert.assertFalse(state.diplomaticMessageReceipts.containsKey("ack-1"))
        Assert.assertTrue(state.currentTurnReceipts.isEmpty())
    }

    @Test
    fun `reused acknowledgement action IDs cannot change their payload`() {
        val f = Fixture(listOf(mail(), mail("second")))
        f.ack("incoming")
        val before = f.coordinator.saveToJson()
        for ((id, text) in listOf("incoming" to "Changed acknowledgement.",
            "second" to "I read your request and will consider it.")) {
            try {
                f.ack(id, text)
                Assert.fail("Expected conflict")
            } catch (ex: EngineCallException) {
                Assert.assertEquals("ACTION_ID_CONFLICT", ex.code)
            }
        }
        Assert.assertEquals(before, f.coordinator.saveToJson())
    }

    @Test
    fun `human turns may end without agent acknowledgement enforcement`() {
        val f = Fixture(listOf(mail()), ControllerKind.HUMAN)
        Assert.assertEquals(true, f.bridge.endTurn(f.actor, "human-end", 0)["turnEnded"])
    }

    @Test
    fun `legacy message records default unacknowledged and deep copies keep response fields`() {
        val serializer = com.unciv.json.json()
        val old = serializer.fromJson(DiplomaticMessage::class.java,
            "{\"messageId\":\"old\",\"senderCivId\":\"Greece\",\"targetCivId\":\"Rome\",\"text\":\"Border request\",\"deliveredTurnSerial\":3}")
        Assert.assertNull(old.acknowledgedTurnSerial)
        Assert.assertNull(old.acknowledgementText)
        old.acknowledgedTurnSerial = 5
        old.acknowledgementText = "I read this."
        val copy = old.copy()
        copy.acknowledgementText = "Changed"
        copy.acknowledgedTurnSerial = 7
        val restored = serializer.fromJson(DiplomaticMessage::class.java, serializer.toJson(old))
        Assert.assertEquals(5, restored.acknowledgedTurnSerial)
        Assert.assertEquals("I read this.", restored.acknowledgementText)
    }

    @Test
    fun `action result recovers the visible acknowledgement response`() {
        val f = Fixture(listOf(mail()))
        val applied = f.ack("incoming")
        @Suppress("UNCHECKED_CAST")
        val recovered = f.bridge.actionResult("ack-1") as Map<String, Any?>
        Assert.assertTrue("Confirmed acknowledgement result must contain its visible response", recovered["result"] is Map<*, *>)
        Assert.assertEquals(applied["result"], recovered["result"])
    }

    @Test
    fun `guessed send and acknowledgement action IDs reveal no private receipt metadata`() {
        val f = Fixture(listOf(mail()))
        f.ack("incoming")
        val sent = f.bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to f.actor,
            "actionId" to "sent-mail", "expectedRevision" to f.coordinator.getStateRevision(),
            "type" to "diplomacy.message", "args" to mapOf("targetCivId" to f.other, "text" to "Private reply.")))
        for (id in listOf("ack-1", "sent-mail", "missing"))
            Assert.assertEquals(mapOf("status" to "rejected", "actionId" to id),
                f.bridge.actionResult(id, f.other))
        @Suppress("UNCHECKED_CAST")
        val sendReceipt = f.bridge.actionResult("sent-mail", f.actor) as Map<String, Any?>
        Assert.assertEquals(sent["result"], sendReceipt["result"])
        val loaded = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        Assert.assertEquals(mapOf("status" to "rejected", "actionId" to "ack-1"),
            AgentEngineBridge(loaded).actionResult("ack-1", f.other))
        Assert.assertEquals(mapOf("status" to "rejected", "actionId" to "sent-mail"),
            AgentEngineBridge(loaded).actionResult("sent-mail", f.other))
    }

    @Test
    fun `queued future and foreign messages do not block an external agent end turn`() {
        val f = Fixture(listOf(mail("queued", delivered = null),
            mail("future", delivered = 6), mail("foreign", target = "Greece")))
        Assert.assertEquals(true, f.bridge.endTurn(f.actor, "no-incoming", 0)["turnEnded"])
    }

    @Test
    fun `retained diplomatic IDs reject later gameplay and end turn reuse after reload`() {
        val f = Fixture(listOf(mail()))
        f.ack("incoming", actionId = "reserved-ack")
        f.bridge.act(mapOf("protocolVersion" to 1, "actorCivId" to f.actor,
            "actionId" to "reserved-send", "expectedRevision" to f.coordinator.getStateRevision(),
            "type" to "diplomacy.message", "args" to mapOf("targetCivId" to f.other, "text" to "Private reply.")))
        f.bridge.endTurn(f.actor, "rotate-one", f.coordinator.getStateRevision())
        f.ack("reserved-send", actionId = "other-ack", actorId = f.other)
        f.bridge.endTurn(f.other, "rotate-two", f.coordinator.getStateRevision())
        val save = f.coordinator.saveToJson()
        for (id in listOf("reserved-ack", "reserved-send")) for (endTurn in listOf(false, true)) {
            val writer = InMemoryCheckpointWriter()
            val restored = MatchCoordinator.loadFromJson(save, writer)
            val before = restored.saveToJson()
            try {
                if (endTurn) restored.execute(EndTurnCommand(f.actor, id, restored.getStateRevision()))
                else restored.execute(RenameUnitCommand(f.actor, id, restored.getStateRevision(),
                    restored.observe(f.actor).ownUnits.first().unitId, "Changed name"))
                Assert.fail("Retained diplomatic action $id must remain reserved")
            } catch (ex: AgentCommandException) {
                Assert.assertEquals(AgentCommandError.ACTION_ID_CONFLICT, ex.code)
            }
            Assert.assertEquals(before, restored.saveToJson())
            Assert.assertEquals(0, writer.writeCount)
        }
    }
}
