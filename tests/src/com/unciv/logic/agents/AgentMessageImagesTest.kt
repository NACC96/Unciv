package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.FileMessageImageStore
import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

@RunWith(BaseTestRunner::class)
class AgentMessageImagesTest {
    private fun png(width: Int = 2, height: Int = 1): MessageImageDraft {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output)
        return MessageImageDraft(output.toByteArray(), "image/png", width, height)
    }

    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val state = AgentMatchState(matchId = "images", timelineId = "t",
            turnSerial = 5, turnPhase = TurnPhase.AWAITING_DECISIONS)
        val writer = InMemoryCheckpointWriter()
        val rome: String
        val greece: String
        val egypt: String
        val spectator: String
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(5)
            val roman = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greek = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val egyptian = game.addCiv(game.ruleset.nations["Egypt"]!!, isPlayer = true)
            spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!).civID
            rome = roman.civID; greece = greek.civID; egypt = egyptian.civID
            for ((civ, x) in listOf(roman to 0, greek to 3, egyptian to -3)) {
                game.addUnit("Warrior", civ, game.getTile(x, 0))
                state.controllers[civ.civID] = CivilizationController(civ.civID,
                    if (civ == egyptian) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT)
            }
            roman.diplomacyFunctions.makeCivilizationsMeet(greek)
            roman.diplomacyFunctions.makeCivilizationsMeet(egyptian)
            greek.diplomacyFunctions.makeCivilizationsMeet(egyptian)
            game.gameInfo.currentPlayer = rome; game.gameInfo.currentPlayerCiv = roman
        }
        fun coordinator() = MatchCoordinator(game.gameInfo, state, writer)
        fun mail(id: String, attachment: DiplomaticImageAttachment, delivered: Int? = 5,
                 sender: String = egypt, target: String = rome) = DiplomaticMessage(
            id, sender, target, "", 1, delivered,
            attachments = arrayListOf(attachment.copy()))
    }

    @Test fun image_only_mail_is_saved_as_isolated_metadata_and_duplicate_payloads_cannot_replace_it() {
        val dir = Files.createTempDirectory("message-images-").toFile()
        try {
            val attachment = FileMessageImageStore(dir).store(png())
            val f = Fixture(); val coordinator = f.coordinator()
            val mail = coordinator.queuePlayerMessage(f.egypt, "image-only", f.rome, " ", listOf(attachment))
            assertEquals("", mail.text); assertEquals(attachment.attachmentId, mail.attachments.single().attachmentId)
            attachment.width = 123; mail.attachments.single().height = 321
            val saved = coordinator.getMessageHistory(f.egypt).single()
            assertEquals(2, saved.attachments.single().width); assertEquals(1, saved.attachments.single().height)
            assertFalse(coordinator.saveToJson().contains(Base64.getEncoder().encodeToString(png().bytes)))
            val restored = MatchCoordinator.loadFromJson(coordinator.saveToJson())
            assertEquals(2, restored.getMessageHistory(f.egypt).single().attachments.single().width)
            try {
                coordinator.queuePlayerMessage(f.egypt, "image-only", f.rome, "Changed", saved.attachments)
                fail("Changed mail must conflict")
            } catch (expected: AgentCommandException) { assertEquals(AgentCommandError.ACTION_ID_CONFLICT, expected.code) }
            f.writer.failNextWrite = true
            try {
                coordinator.queuePlayerMessage(f.egypt, "failed-image", f.rome, "", saved.attachments)
                fail("Failed checkpoint must not publish mail")
            } catch (expected: AgentCommandException) { assertEquals(AgentCommandError.INTERNAL_ERROR, expected.code) }
            assertEquals(listOf("image-only"), coordinator.getMessageHistory(f.egypt).map { it.messageId })
        } finally { dir.deleteRecursively() }
    }

    @Test fun hidden_missing_queued_and_future_images_share_one_rejection_and_viewer_reads_follow_history() {
        val dir = Files.createTempDirectory("message-image-access-").toFile()
        try {
            val attachment = FileMessageImageStore(dir).store(png())
            val f = Fixture()
            f.state.diplomaticMessages.addAll(listOf(f.mail("incoming", attachment),
                f.mail("queued", attachment, null), f.mail("future", attachment, 6),
                f.mail("foreign", attachment, target = f.greece)))
            val coordinator = f.coordinator()
            val authorized = coordinator.getDeliveredMessageImage(f.rome, "incoming", attachment.attachmentId)
            authorized.width = 111
            assertEquals(2, coordinator.getDeliveredMessageImage(f.rome, "incoming", attachment.attachmentId).width)
            for ((id, imageId) in listOf("missing" to attachment.attachmentId, "queued" to attachment.attachmentId,
                "future" to attachment.attachmentId, "foreign" to attachment.attachmentId, "incoming" to "a".repeat(64))) {
                try { coordinator.getDeliveredMessageImage(f.rome, id, imageId); fail("Must reject $id") }
                catch (expected: AgentCommandException) {
                    assertEquals(AgentCommandError.ILLEGAL_ACTION, expected.code)
                    assertEquals("No such accessible message image", expected.message)
                }
            }
            assertEquals(2, coordinator.getViewerMessageImage(f.egypt, "queued", attachment.attachmentId).width)
            assertEquals(2, coordinator.getViewerMessageImage(f.spectator, "foreign", attachment.attachmentId).width)
            try { coordinator.getViewerMessageImage(f.rome, "queued", attachment.attachmentId); fail("Undelivered") }
            catch (expected: AgentCommandException) { assertEquals(AgentCommandError.ILLEGAL_ACTION, expected.code) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun store_deduplicates_verifies_hash_and_rejects_missing_corrupt_or_inconsistent_pngs() {
        val dir = Files.createTempDirectory("message-image-store-").toFile()
        try {
            val store = FileMessageImageStore(dir); val draft = png()
            val metadata = store.store(draft)
            assertEquals(metadata.attachmentId, store.store(draft.copy()).attachmentId)
            val blobDir = File(dir, ".agent-data/message-images")
            assertEquals(1, blobDir.listFiles()!!.size)
            assertArrayEquals(draft.bytes, FileMessageImageStore(dir).read(metadata).bytes)
            val blob = File(blobDir, "${metadata.attachmentId}.png")
            blob.writeBytes(draft.bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            try { store.read(metadata); fail("Corruption must fail") } catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
            try { store.store(draft); fail("Corrupt existing blob must not be silently replaced") } catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
            blob.delete()
            try { store.read(metadata); fail("Missing blob must fail") } catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
            for (bad in listOf(MessageImageDraft(draft.bytes, "image/jpeg", 2, 1),
                MessageImageDraft(draft.bytes, "image/png", 3, 1),
                MessageImageDraft(ByteArray(4 * 1024 * 1024 + 1), "image/png", 2, 1),
                MessageImageDraft(byteArrayOf(1, 2, 3), "image/png", 2, 1), png(2049, 1))) {
                try { store.store(bad); fail("Malformed or oversized image must fail") }
                catch (expected: Exception) { assertTrue(expected.message!!.isNotBlank()) }
            }
            assertEquals(0, blobDir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun unavailable_external_volume_and_directory_sync_failure_never_return_publishable_metadata() {
        val missing = File("/Volumes/UncivMissing-${UUID.randomUUID()}/userdata")
        try { FileMessageImageStore(missing).store(png()); fail("Missing volume") }
        catch (expected: Exception) { assertTrue(expected.message!!.contains("volume")) }
        assertFalse(missing.exists())
        val dir = Files.createTempDirectory("message-image-sync-").toFile()
        try {
            val store = FileMessageImageStore(dir, directorySync = { throw java.io.IOException("sync failed") })
            try { store.store(png()); fail("No durability acknowledgement") }
            catch (expected: Exception) { assertTrue(expected.message!!.contains("sync failed")) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun image_rpc_binds_admitted_actor_and_returns_verified_base64_without_mutation() {
        val dir = Files.createTempDirectory("message-image-rpc-").toFile()
        try {
            val attachment = FileMessageImageStore(dir).store(png())
            val f = Fixture(); f.state.diplomaticMessages.add(f.mail("incoming", attachment))
            val host = DesktopAgentMatch(f.game.gameInfo, f.state, AgentMatchSetup(), dir)
            DesktopAgentMatch::class.java.getDeclaredField("providerRunCivId").apply { isAccessible = true }.set(host, f.rome)
            try {
                val before = host.coordinator.saveToJson()
                @Suppress("UNCHECKED_CAST")
                val response = host.answerEngineRequest("message_image", mapOf("civId" to f.rome,
                    "messageId" to "incoming", "attachmentId" to attachment.attachmentId)) as Map<String, Any?>
                assertEquals("image/png", response["mimeType"])
                assertEquals(attachment.attachmentId, response["attachmentId"])
                assertArrayEquals(png().bytes, Base64.getDecoder().decode(response["data"] as String))
                try { host.answerEngineRequest("message_image", mapOf("civId" to f.greece,
                    "messageId" to "incoming", "attachmentId" to attachment.attachmentId)); fail("Spoofed read") }
                catch (expected: EngineCallException) { assertEquals("AUTHORITY_REVOKED", expected.code) }
                assertEquals(before, host.coordinator.saveToJson())
                java.io.File(dir, ".agent-data/message-images/${attachment.attachmentId}.png").writeText("corrupt")
                val inaccessible = assertThrows(EngineCallException::class.java) {
                    host.answerEngineRequest("message_image", mapOf("civId" to f.rome,
                        "messageId" to "incoming", "attachmentId" to attachment.attachmentId))
                }
                assertEquals("ILLEGAL_ACTION", inaccessible.code)
                assertEquals("No such accessible message image", inaccessible.message)
            } finally { host.close() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun v2_migration_keeps_map_memory_and_backfills_exact_acknowledgement_revision_from_receipts() {
        val f = Fixture()
        f.state.formatVersion = 2
        f.state.mapKnowledge.put(f.rome, ObservedTile(1, 2, terrain = "Grassland"))
        val first = DiplomaticMessage("first", f.egypt, f.rome, "A", 1, 3,
            acknowledgedTurnSerial = 5, acknowledgementText = "Accepted")
        val second = first.copy().apply { messageId = "second"; acknowledgementText = "Refused" }
        f.state.diplomaticMessages.addAll(listOf(first, second))
        f.state.diplomaticMessageReceipts["ack-later"] = ActionReceipt("ack-later", f.rome, "t",
            "diplomacy.acknowledgeMessage|" + json().toJson(arrayListOf(f.rome, "first", "Accepted")),
            8, 9, "applied", resultJson = "first")
        f.state.diplomaticMessageReceipts["ack-earlier"] = ActionReceipt("ack-earlier", f.rome, "t",
            "diplomacy.acknowledgeMessage|" + json().toJson(arrayListOf(f.rome, "second", "Refused")),
            6, 7, "applied", resultJson = "second")
        val migrated = migrateAgentMatchStateJson(json().apply { setUsePrototypes(false) }.toJson(f.state), 2)
        assertEquals(3, migrated.formatVersion)
        assertEquals("Grassland", migrated.mapKnowledge.get(f.rome, com.unciv.logic.map.HexCoord(1, 2))!!.terrain)
        assertEquals(listOf(9, 7), migrated.diplomaticMessages.map { it.acknowledgedRevision })
        val coordinator = MatchCoordinator(f.game.gameInfo, migrated, f.writer)
        assertEquals(listOf("second", "first"), coordinator.getMessageHistory(f.rome).map { it.messageId })
        assertEquals(listOf("first", "second"), coordinator.observe(f.rome).diplomaticHistory.map { it.messageId })
    }

    @Test fun recent_history_is_capped_at_twelve_without_hidden_mail_or_image_bytes() {
        val f = Fixture()
        repeat(20) { f.state.diplomaticMessages.add(DiplomaticMessage("sent-$it", f.rome, f.greece, "Note", it)) }
        f.state.diplomaticMessages.add(DiplomaticMessage("undelivered", f.egypt, f.rome, "Hidden", 99))
        val history = f.coordinator().observe(f.rome).diplomaticHistory
        assertEquals(12, history.size)
        assertEquals((19 downTo 8).map { "sent-$it" }, history.map { it.messageId })
    }
}
