package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** Saved diplomatic mail is delivered at a recipient turn boundary, never mid-turn. */
@RunWith(BaseTestRunner::class)
class AgentDiplomaticMessagesTest {
    private class Fixture(
        withExtraContacts: Boolean = false,
        phase: TurnPhase = TurnPhase.AWAITING_DECISIONS
    ) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val writer = InMemoryCheckpointWriter()
        val rome: String
        val greece: String
        val egypt: String
        val spectator: String
        var unmet: String = ""
        var builtin: String = ""
        var defeated: String = ""
        val coordinator: MatchCoordinator
        val bridge: AgentEngineBridge

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(7)
            val roman = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greek = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val egyptian = game.addCiv(game.ruleset.nations["Egypt"]!!, isPlayer = true)
            val observer = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
            rome = roman.civID
            greece = greek.civID
            egypt = egyptian.civID
            spectator = observer.civID
            game.addUnit("Warrior", roman, game.getTile(0, 0))
            game.addUnit("Warrior", greek, game.getTile(4, 0))
            game.addUnit("Warrior", egyptian, game.getTile(-4, 0))
            roman.diplomacyFunctions.makeCivilizationsMeet(greek)
            roman.diplomacyFunctions.makeCivilizationsMeet(egyptian)
            greek.diplomacyFunctions.makeCivilizationsMeet(egyptian)
            game.gameInfo.currentPlayer = rome
            game.gameInfo.currentPlayerCiv = roman
            val match = AgentMatchState(matchId = "mail-match", timelineId = "mail-timeline",
                turnPhase = phase)
            for (civId in listOf(rome, greece)) {
                match.controllers[civId] = CivilizationController(civId,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile(displayName = civId))
            }
            match.controllers[egypt] = CivilizationController(egypt, ControllerKind.HUMAN)
            if (withExtraContacts) {
                val persian = game.addCiv(game.ruleset.nations["Persia"]!!, isPlayer = true)
                val german = game.addCiv(game.ruleset.nations["Germany"]!!)
                val american = game.addCiv(game.ruleset.nations["America"]!!, isPlayer = true)
                unmet = persian.civID
                builtin = german.civID
                defeated = american.civID
                game.addUnit("Warrior", persian, game.getTile(0, 6))
                game.addUnit("Warrior", german, game.getTile(0, -6))
                roman.diplomacyFunctions.makeCivilizationsMeet(german)
                roman.diplomacyFunctions.makeCivilizationsMeet(american)
                match.controllers[unmet] = CivilizationController(unmet,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile(displayName = "Unmet leader"))
                match.controllers[builtin] = CivilizationController(builtin, ControllerKind.BUILTIN_AI)
                match.controllers[defeated] = CivilizationController(defeated,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile(displayName = "Defeated leader"))
            }
            coordinator = MatchCoordinator(game.gameInfo, match, writer)
            bridge = AgentEngineBridge(coordinator)
        }

        fun send(sender: String = rome, target: String = greece, id: String = "mail-1",
                 text: String = "Our borders can remain peaceful."): Map<String, Any?> = bridge.act(mapOf(
            "protocolVersion" to 1, "actionId" to id,
            "expectedRevision" to coordinator.getStateRevision(), "actorCivId" to sender,
            "type" to "diplomacy.message", "args" to mapOf("targetCivId" to target, "text" to text)
        ))

        fun end(civId: String, id: String) {
            // These delivery/history tests explicitly consume mail before advancing an agent turn.
            for (message in coordinator.observe(civId).inbox.filter { it.acknowledgedTurnSerial == null })
                coordinator.execute(AcknowledgeDiplomaticMessageCommand(civId, "$id-ack-${message.messageId}",
                    coordinator.getStateRevision(), message.messageId, "I read this message."))
            coordinator.execute(EndTurnCommand(civId, id, coordinator.getStateRevision()))
        }

        @Suppress("UNCHECKED_CAST")
        fun wireInbox(civId: String): List<Map<String, Any?>> =
            (bridge.observe(civId) as Map<String, Any?>)["inbox"] as List<Map<String, Any?>>
    }

    @Test
    fun `agent message is committed and delivered only on recipient next turn`() {
        val f = Fixture()
        Assert.assertEquals("applied", f.send()["status"])
        Assert.assertTrue(f.wireInbox(f.greece).isEmpty())
        f.end(f.rome, "end-rome")
        val inbox = f.wireInbox(f.greece)
        Assert.assertEquals(1, inbox.size)
        Assert.assertEquals(f.rome, inbox[0]["senderCivId"])
        Assert.assertEquals("Our borders can remain peaceful.", inbox[0]["text"])
        Assert.assertEquals(f.coordinator.getTurnSerial(),
            (inbox[0]["deliveredTurnSerial"] as Number).toInt())
        Assert.assertTrue(f.wireInbox(f.egypt).isEmpty())
    }

    @Test
    fun `an agent cannot send a diplomatic message outside its own turn`() {
        val f = Fixture()
        try {
            f.send(sender = f.greece, target = f.rome)
            Assert.fail("Expected NOT_YOUR_TURN")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("NOT_YOUR_TURN", ex.code)
        }
        Assert.assertEquals(0, f.writer.writeCount)
    }

    @Test
    fun `host mail waits until next recipient turn and survives same-turn retries and reload`() {
        val f = Fixture()
        val revision = f.coordinator.getStateRevision()
        val mail = f.coordinator.queuePlayerMessage(f.egypt, "player-mail", f.rome,
            " Please guard the northern border. ")
        Assert.assertEquals("Please guard the northern border.", mail.text)
        Assert.assertNull(mail.deliveredTurnSerial)
        Assert.assertEquals(revision, f.coordinator.getStateRevision())
        Assert.assertTrue(f.coordinator.observe(f.rome).inbox.isEmpty())
        Assert.assertTrue(f.coordinator.getMessageHistory(f.rome).isEmpty())
        Assert.assertEquals(1, f.coordinator.getMessageHistory(f.egypt).size)
        f.end(f.rome, "r1")
        f.end(f.greece, "g1")
        f.end(f.egypt, "e1")
        val first = f.coordinator.observe(f.rome).inbox.single()
        Assert.assertEquals(f.coordinator.getTurnSerial(), first.deliveredTurnSerial)
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        repeat(3) {
            Assert.assertEquals(first.messageId, restored.observe(f.rome).inbox.single().messageId)
            Assert.assertEquals(first.deliveredTurnSerial,
                restored.observe(f.rome).inbox.single().deliveredTurnSerial)
        }
        restored.observe(f.rome).inbox.single().text = "Modified outside coordinator"
        Assert.assertEquals(first.text, restored.observe(f.rome).inbox.single().text)
        restored.execute(AcknowledgeDiplomaticMessageCommand(f.rome, "loaded-ack",
            restored.getStateRevision(), first.messageId, "I read the northern border request."))
        for ((index, civ) in listOf(f.rome, f.greece, f.egypt).withIndex()) {
            restored.execute(EndTurnCommand(civ, "loaded-end-$index", restored.getStateRevision()))
        }
        Assert.assertTrue(restored.observe(f.rome).inbox.isEmpty())
        Assert.assertEquals(first.messageId, restored.getMessageHistory(f.rome).single().messageId)
    }

    @Test
    fun `mail queued before opening is delivered atomically with the first turn`() {
        val f = Fixture(phase = TurnPhase.READY_TO_START)
        f.coordinator.queuePlayerMessage(f.spectator, "opening-guidance", f.rome, "Explore carefully.")
        Assert.assertTrue(f.coordinator.getMessageHistory(f.rome).isEmpty())
        f.coordinator.beginAwaitingTurn()
        Assert.assertEquals("SPECTATOR", f.coordinator.observe(f.rome).inbox.single().origin)
        Assert.assertEquals(1, f.coordinator.observe(f.rome).inbox.single().deliveredTurnSerial)
    }

    @Test
    fun `spectator mail during an active agent turn waits and model arguments cannot forge its origin`() {
        val f = Fixture()
        f.coordinator.queuePlayerMessage(f.spectator, "late-guidance", f.rome, "Please consider a scout.")
        repeat(2) { Assert.assertTrue(f.coordinator.observe(f.rome).inbox.isEmpty()) }
        val receipt = f.bridge.act(mapOf(
            "protocolVersion" to 1, "actionId" to "forged-origin",
            "expectedRevision" to f.coordinator.getStateRevision(), "actorCivId" to f.rome,
            "type" to "diplomacy.message", "args" to mapOf("targetCivId" to f.greece,
                "text" to "A diplomatic claim.", "origin" to "SPECTATOR", "senderCivId" to f.spectator)
        ))
        @Suppress("UNCHECKED_CAST")
        val result = receipt["result"] as Map<String, Any?>
        Assert.assertEquals("NATION", result["origin"])
        Assert.assertEquals(f.rome, result["senderCivId"])
        f.end(f.rome, "spectator-r")
        f.end(f.greece, "spectator-g")
        f.end(f.egypt, "spectator-e")
        Assert.assertEquals("late-guidance", f.coordinator.observe(f.rome).inbox.single().messageId)
        Assert.assertEquals("SPECTATOR", f.coordinator.observe(f.rome).inbox.single().origin)
    }

    @Test
    fun `private history belongs to senders delivered recipients and real spectators`() {
        val f = Fixture()
        f.coordinator.queuePlayerMessage(f.egypt, "egypt-mail", f.greece, "Our scouts will stay east.")
        f.coordinator.queuePlayerMessage(f.spectator, "observer-mail", f.rome, "A spectator suggestion.")
        Assert.assertEquals(listOf("egypt-mail"), f.coordinator.getMessageHistory(f.egypt).map { it.messageId })
        Assert.assertTrue(f.coordinator.getMessageHistory(f.greece).isEmpty())
        Assert.assertTrue(f.coordinator.getMessageHistory(f.rome).isEmpty())
        Assert.assertEquals(2, f.coordinator.getMessageHistory(f.spectator).size)
        Assert.assertTrue(f.coordinator.isSpectatorViewer(f.spectator))
        Assert.assertFalse(f.coordinator.isSpectatorViewer(f.egypt))
        Assert.assertFalse(f.coordinator.isSpectatorViewer("Missing spectator"))
        f.end(f.rome, "rome-deliver")
        Assert.assertEquals(listOf("egypt-mail"), f.coordinator.getMessageHistory(f.greece).map { it.messageId })
        Assert.assertTrue(f.coordinator.observe(f.rome).inbox.isEmpty())
        f.coordinator.getMessageHistory(f.spectator)[0].text = "Tampered"
        Assert.assertEquals("Our scouts will stay east.",
            f.coordinator.getMessageHistory(f.greece).single().text)
    }

    @Test
    fun `contacts respect diplomatic contact controller kind defeat and spectator origin`() {
        val f = Fixture(withExtraContacts = true)
        Assert.assertEquals(setOf(f.greece, f.egypt),
            f.coordinator.getMessageContacts(f.rome).map { it.civId }.toSet())
        Assert.assertEquals(setOf(f.rome, f.greece),
            f.coordinator.getMessageContacts(f.egypt).map { it.civId }.toSet())
        Assert.assertEquals(setOf(f.rome, f.greece, f.unmet),
            f.coordinator.getMessageContacts(f.spectator).map { it.civId }.toSet())
        Assert.assertEquals(setOf(f.greece, f.egypt),
            f.coordinator.observe(f.rome).messageContacts.map { it.civId }.toSet())
        Assert.assertFalse(f.coordinator.observe(f.rome).messageContacts.single { it.civId == f.egypt }.isAgent)
        for (target in listOf(f.unmet, f.builtin, f.defeated, "Nonexistent", f.rome, f.spectator)) {
            try {
                f.send(target = target, id = "bad-$target")
                Assert.fail("Unexpected eligible contact: $target")
            } catch (ex: EngineCallException) {
                Assert.assertEquals("ILLEGAL_ACTION", ex.code)
                Assert.assertEquals("No eligible diplomatic contact", ex.message)
            }
        }
        val guidance = f.coordinator.queuePlayerMessage(f.spectator, "to-unmet", f.unmet, "Hello from the spectator.")
        Assert.assertEquals("SPECTATOR", guidance.origin)
        try {
            f.coordinator.queuePlayerMessage(f.rome, "agent-impersonates-ui", f.greece, "Forged player mail.")
            Assert.fail("Agent must not use the human composer")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.AUTHORITY_REVOKED, ex.code)
        }
    }

    @Test
    fun `an agent can leave a reply for a met human without waking another agent`() {
        val f = Fixture()
        Assert.assertEquals("applied", f.send(target = f.egypt, text = "I will respect the agreed border.")["status"])
        Assert.assertTrue(f.coordinator.getMessageHistory(f.egypt).isEmpty())
        f.end(f.rome, "rome-to-human")
        f.end(f.greece, "greece-to-human")
        Assert.assertEquals("I will respect the agreed border.", f.coordinator.observe(f.egypt).inbox.single().text)
        Assert.assertEquals(f.egypt, f.coordinator.getCurrentCivId())
    }

    @Test
    fun `senders may delete their own mail only until the recipient's turn delivers it`() {
        val f = Fixture()
        fun rejects(actor: String, id: String, code: AgentCommandError) = try {
            f.coordinator.deletePlayerMessage(actor, id)
            Assert.fail("$actor must not delete $id")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(code, ex.code)
        }
        f.coordinator.queuePlayerMessage(f.egypt, "egypt-draft", f.rome, "Ignore my last note.")
        f.coordinator.queuePlayerMessage(f.spectator, "observer-draft", f.rome, "Try a scout.")
        f.send(id = "rome-agent-mail")
        rejects(f.spectator, "egypt-draft", AgentCommandError.ILLEGAL_ACTION)
        rejects(f.rome, "rome-agent-mail", AgentCommandError.AUTHORITY_REVOKED)
        val revision = f.coordinator.getStateRevision()
        f.coordinator.deletePlayerMessage(f.egypt, "egypt-draft")
        Assert.assertTrue(f.coordinator.getMessageHistory(f.egypt).isEmpty())
        Assert.assertEquals("Mail is metadata, not a gameplay revision", revision, f.coordinator.getStateRevision())
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson(), InMemoryCheckpointWriter())
        Assert.assertTrue(restored.getMessageHistory(f.egypt).isEmpty())
        rejects(f.egypt, "egypt-draft", AgentCommandError.ILLEGAL_ACTION)

        f.end(f.rome, "delete-r"); f.end(f.greece, "delete-g"); f.end(f.egypt, "delete-e")
        val delivered = f.coordinator.getMessageHistory(f.rome).single { it.targetCivId == f.rome }
        Assert.assertEquals("observer-draft", delivered.messageId)
        rejects(f.spectator, "observer-draft", AgentCommandError.ILLEGAL_ACTION)
        Assert.assertEquals(listOf("observer-draft"),
            f.coordinator.getMessageHistory(f.rome).filter { it.targetCivId == f.rome }.map { it.messageId })
    }

    @Test
    fun `deleting queued mail frees its place in the per-turn limit`() {
        val f = Fixture()
        repeat(10) {
            f.coordinator.queuePlayerMessage(f.spectator, "limit-$it", f.rome, "Note $it")
        }
        try {
            f.coordinator.queuePlayerMessage(f.spectator, "limit-extra", f.rome, "One too many")
            Assert.fail("The eleventh message should exceed the limit")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.ILLEGAL_ACTION, ex.code)
        }
        f.coordinator.deletePlayerMessage(f.spectator, "limit-3")
        f.coordinator.queuePlayerMessage(f.spectator, "limit-extra", f.rome, "One too many")
        Assert.assertEquals(10, f.coordinator.getMessageHistory(f.spectator).size)
    }

    @Test
    fun `duplicate IDs are durable and payload changes cannot replace mail`() {
        val f = Fixture()
        f.coordinator.queuePlayerMessage(f.egypt, "durable-player", f.rome, "Border note.")
        val loadedWriter = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson(), loadedWriter)
        val copy = restored.queuePlayerMessage(f.egypt, "durable-player", f.rome, " Border note. ")
        Assert.assertEquals("Border note.", copy.text)
        Assert.assertEquals(0, loadedWriter.writeCount)
        Assert.assertEquals(1, restored.getMessageHistory(f.egypt).size)
        for ((sender, target, text) in listOf(
                Triple(f.egypt, f.rome, "Changed"),
                Triple(f.egypt, f.greece, "Border note."),
                Triple(f.spectator, f.rome, "Border note."))) {
            try {
                restored.queuePlayerMessage(sender, "durable-player", target, text)
                Assert.fail("Changed payload should conflict")
            } catch (ex: AgentCommandException) {
                Assert.assertEquals(AgentCommandError.ACTION_ID_CONFLICT, ex.code)
            }
        }
        f.send(id = "durable-agent")
        val revision = f.coordinator.getStateRevision()
        val writes = f.writer.writeCount
        f.send(id = "durable-agent")
        Assert.assertEquals(revision, f.coordinator.getStateRevision())
        Assert.assertEquals(writes, f.writer.writeCount)
        Assert.assertEquals(1, f.coordinator.getMessageHistory(f.rome).size)
    }

    @Test
    fun `peer mail receipt replays without another commit after later turns and reload`() {
        val f = Fixture()
        val original = f.send(id = "retained-peer-receipt")
        f.end(f.rome, "mail-replay-r")
        f.end(f.greece, "mail-replay-g")
        f.end(f.egypt, "mail-replay-e")
        val restoredWriter = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson(), restoredWriter)
        val before = restored.getStateRevision()
        val replay = AgentEngineBridge(restored).act(mapOf(
            "protocolVersion" to 1, "actionId" to "retained-peer-receipt",
            "expectedRevision" to before, "actorCivId" to f.rome,
            "type" to "diplomacy.message", "args" to mapOf("targetCivId" to f.greece,
                "text" to "Our borders can remain peaceful.")
        ))
        Assert.assertEquals(original["revision"], replay["revision"])
        Assert.assertEquals(before, restored.getStateRevision())
        Assert.assertEquals(0, restoredWriter.writeCount)
        Assert.assertEquals(1, restored.getMessageHistory(f.rome).size)
    }

    @Test
    fun `older saved mail without retained receipts replays without writes or duplicate delivery`() {
        val f = Fixture()
        f.send(id = "legacy-peer-mail")
        f.end(f.rome, "legacy-r")
        f.end(f.greece, "legacy-g")
        f.end(f.egypt, "legacy-e")
        val serializer = com.unciv.json.json()
        val bundle = serializer.fromJson(AgentBundle::class.java, f.coordinator.saveToJson())
        val savedState = serializer.fromJson(AgentMatchState::class.java, bundle.matchJson)
        savedState.diplomaticMessageReceipts.clear()
        bundle.matchJson = serializer.toJson(savedState)
        val restoredWriter = InMemoryCheckpointWriter()
        val restored = MatchCoordinator.loadFromJson(serializer.toJson(bundle), restoredWriter)
        val before = restored.getStateRevision()
        val replay = AgentEngineBridge(restored).act(mapOf(
            "protocolVersion" to 1, "actionId" to "legacy-peer-mail", "expectedRevision" to before,
            "actorCivId" to f.rome, "type" to "diplomacy.message",
            "args" to mapOf("targetCivId" to f.greece, "text" to "Our borders can remain peaceful.")
        ))
        Assert.assertEquals("applied", replay["status"])
        Assert.assertEquals(before, restored.getStateRevision())
        Assert.assertEquals(0, restoredWriter.writeCount)
        Assert.assertEquals(1, restored.getMessageHistory(f.rome).size)
        Assert.assertTrue(restored.observe(f.rome).inbox.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val wire = AgentEngineBridge(restored).observe(f.rome) as Map<String, Any?>
        Assert.assertFalse(wire.containsKey("diplomaticMessageReceipts"))
    }

    @Test
    fun `failed mail persistence and failed delivery never acknowledge unpublished messages`() {
        val f = Fixture()
        f.writer.failNextWrite = true
        try {
            f.coordinator.queuePlayerMessage(f.egypt, "failed-mail", f.greece, "Do not publish this.")
            Assert.fail("Expected failed checkpoint")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        Assert.assertTrue(f.coordinator.getMessageHistory(f.spectator).isEmpty())
        Assert.assertEquals(0, f.coordinator.getStateRevision())
        val delivery = Fixture()
        delivery.send()
        delivery.writer.failNextWrite = true
        try {
            delivery.end(delivery.rome, "failed-delivery")
            Assert.fail("Expected failed delivery checkpoint")
        } catch (ex: AgentCommandException) {
            Assert.assertEquals(AgentCommandError.INTERNAL_ERROR, ex.code)
        }
        Assert.assertEquals(delivery.rome, delivery.coordinator.getCurrentCivId())
        Assert.assertNull(delivery.coordinator.getMessageHistory(delivery.rome).single().deliveredTurnSerial)
        Assert.assertTrue(delivery.coordinator.getMessageHistory(delivery.greece).isEmpty())
    }

    @Test
    fun `bounds and persisted per-turn limits reject flooding while retries remain harmless`() {
        val f = Fixture()
        for (text in listOf(" ", "x".repeat(2001))) {
            try {
                f.send(text = text)
                Assert.fail("Body should be rejected")
            } catch (ex: EngineCallException) {
                Assert.assertEquals("INVALID_ARGUMENT", ex.code)
            }
        }
        repeat(10) { f.send(id = "bounded-$it") }
        val writes = f.writer.writeCount
        f.send(id = "bounded-0")
        Assert.assertEquals(writes, f.writer.writeCount)
        try {
            f.send(id = "eleventh")
            Assert.fail("Expected per-turn message limit")
        } catch (ex: EngineCallException) {
            Assert.assertEquals("ILLEGAL_ACTION", ex.code)
        }
        val restored = MatchCoordinator.loadFromJson(f.coordinator.saveToJson())
        Assert.assertEquals(10, restored.getMessageHistory(f.rome).size)
        f.end(f.rome, "reset-r")
        f.end(f.greece, "reset-g")
        f.end(f.egypt, "reset-e")
        Assert.assertEquals("applied", f.send(id = "next-turn")["status"])
    }

    @Test
    fun `new saved message fields default on old saves and deep copies share no records`() {
        val serializer = com.unciv.json.json()
        val old = serializer.fromJson(AgentMatchState::class.java, "{\"matchId\":\"old-mail-save\"}")
        Assert.assertTrue(old.diplomaticMessages.isEmpty())
        Assert.assertTrue(old.diplomaticMessageReceipts.isEmpty())
        old.diplomaticMessages.add(DiplomaticMessage("copy-mail", "Rome", "Greece", "Original.", 4))
        old.diplomaticMessageReceipts["copy-mail"] = ActionReceipt(actionId = "copy-mail",
            committedEventIds = arrayListOf("event-original"))
        val copied = old.deepCopy()
        copied.diplomaticMessages.single().text = "Altered."
        copied.diplomaticMessages.single().deliveredTurnSerial = 5
        copied.diplomaticMessageReceipts["copy-mail"]!!.committedEventIds.add("event-copy")
        Assert.assertEquals("Original.", old.diplomaticMessages.single().text)
        Assert.assertNull(old.diplomaticMessages.single().deliveredTurnSerial)
        Assert.assertEquals(listOf("event-original"), old.diplomaticMessageReceipts["copy-mail"]!!.committedEventIds)
        val restored = serializer.fromJson(AgentMatchState::class.java, serializer.toJson(old))
        Assert.assertEquals("copy-mail", restored.diplomaticMessages.single().messageId)
        Assert.assertNull(restored.diplomaticMessages.single().deliveredTurnSerial)
        Assert.assertEquals(listOf("event-original"), restored.diplomaticMessageReceipts["copy-mail"]!!.committedEventIds)
    }
}
