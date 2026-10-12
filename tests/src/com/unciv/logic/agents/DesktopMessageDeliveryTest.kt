package com.unciv.logic.agents

import com.unciv.Constants
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.unciv.app.desktop.agents.AgentActivityJournal
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(BaseTestRunner::class)
class DesktopMessageDeliveryTest {
    private var priorApp: Application? = null
    private var priorGraphics: Graphics? = null
    @Before fun callbacksWithoutNativeWindow() {
        priorApp = Gdx.app; priorGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java); Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }
    @After fun restoreGdx() { Gdx.app = priorApp; Gdx.graphics = priorGraphics }

    private class Fixture(viewer: String = "Rome", active: String = "Rome") {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val dataDir = Files.createTempDirectory("unciv-mail-host-").toFile()
        val screen = Mockito.mock(WorldScreen::class.java)
        val host: DesktopAgentMatch
        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(5)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!)
            game.addCiv(game.ruleset.nations[Constants.spectator]!!)
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(3, 0))
            rome.diplomacyFunctions.makeCivilizationsMeet(greece)
            game.gameInfo.currentPlayer = active
            game.gameInfo.currentPlayerCiv = game.gameInfo.getCivilization(active)
            val match = AgentMatchState(matchId = "host-mail", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[rome.civID] = CivilizationController(rome.civID, ControllerKind.HUMAN)
            match.controllers[greece.civID] = CivilizationController(greece.civID,
                ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor(greece.civID))
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(viewer)
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(), dataDir, worldScreen = screen)
            writeActivity("roman-run", "Rome", "own", "Own old activity")
            writeActivity("greek-run", "Greece", "secret", "A private diplomatic message body")
            val journal = DesktopAgentMatch::class.java.getDeclaredField("activity").apply { isAccessible = true }
                .get(host) as AgentActivityJournal
            journal.refresh()
        }
        fun send(target: String, body: String, image: MessageImageDraft? = null): String? {
            val done = CountDownLatch(1)
            var result: String? = "No callback"
            if (image == null) host.sendMessage(target, body) { result = it; done.countDown() }
            else host.sendMessage(target, body, image) { result = it; done.countDown() }
            assertTrue("Send must acknowledge after saving", done.await(10, TimeUnit.SECONDS))
            return result
        }
        private fun writeActivity(run: String, civ: String, id: String, body: String) {
            val dir = File(dataDir, ".agent-data/logs/host-mail/$run").apply { mkdirs() }
            File(dir, "meta.json").writeText(AgentJson.stringify(mapOf("civId" to civ, "turnSerial" to 1)))
            File(dir, "activity.jsonl").writeText(AgentJson.stringify(mapOf("id" to id,
                "kind" to "message", "text" to body, "timestamp" to "2026-10-11T00:00:00Z")) + "\n")
        }
    }

    private fun imageDraft(): MessageImageDraft {
        val output = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 1,
            java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output)
        return MessageImageDraft(output.toByteArray(), "image/png", 2, 1)
    }

    @Test fun `host image send makes a verified durable blob before checkpoint success`() {
        val f = Fixture(active = "Greece")
        try {
            val draft = imageDraft()
            assertNull(f.send("Greece", "", draft))
            val metadata = f.host.getMessages().single().attachments.single()
            assertArrayEquals(draft.bytes, com.unciv.app.desktop.agents.FileMessageImageStore(f.dataDir).read(metadata).bytes)
            val recovery = FileCheckpointStore(f.host.recoveryDirectory, f.host.matchId).recover()!!
            val saved = MatchCoordinator.loadFromJson(recovery.bundleJson).getMessageHistory("Rome").single()
            assertEquals(metadata.attachmentId, saved.attachments.single().attachmentId)
            assertEquals("", saved.text)
        } finally { f.host.close(); f.dataDir.deleteRecursively() }
    }

    @Test fun `failed blob publication never commits mail and failed checkpoint leaves only an orphan blob`() {
        for (failBlob in listOf(true, false)) {
            val f = Fixture(active = "Greece")
            try {
                val blocked = if (failBlob) File(f.dataDir, ".agent-data/message-images") else f.host.recoveryDirectory
                blocked.parentFile.mkdirs()
                blocked.writeText("Block publication")
                assertNotNull(f.send("Greece", "", imageDraft()))
                assertTrue(f.host.getMessages().isEmpty())
                assertNull(FileCheckpointStore(f.host.recoveryDirectory, f.host.matchId).recover())
                if (!failBlob) assertEquals(1, File(f.dataDir, ".agent-data/message-images").listFiles()!!.count { it.name.endsWith(".png") })
            } finally { f.host.close(); f.dataDir.deleteRecursively() }
        }
    }

    @Test fun `human cannot inspect another nations private activity or raw log directory`() {
        val f = Fixture()
        assertEquals(listOf("Rome"), f.host.getActivity().map { it.civId })
        assertEquals("", f.host.getActivityDetails("greek-run/secret"))
        assertNull(f.host.getActivityDirectory())
        assertEquals("Own old activity", f.host.getActivityDetails("roman-run/own"))
    }

    @Test fun `spectator can inspect all activity including full details`() {
        val f = Fixture(Constants.spectator)
        assertEquals(setOf("Rome", "Greece"), f.host.getActivity().map { it.civId }.toSet())
        assertEquals("A private diplomatic message body", f.host.getActivityDetails("greek-run/secret"))
        assertNotNull(f.host.getActivityDirectory())
    }

    @Test fun `new map read rpc cannot select a different controller`() {
        val f = Fixture()
        val own = f.host.answerEngineRequest("map", mapOf("civId" to "Rome", "limit" to 1)) as Map<*, *>
        assertEquals(2, own["observationVersion"])
        for (method in listOf("map", "inspect_tile")) {
            try {
                f.host.answerEngineRequest(method, mapOf("civId" to "Greece", "x" to 3, "y" to 0))
                fail("RPC may not choose another civilization")
            } catch (error: EngineCallException) { assertEquals("AUTHORITY_REVOKED", error.code) }
        }
        f.host.close()
    }

    @Test fun `paused human can queue during an agent turn without invalidating its revision`() {
        val f = Fixture(active = "Greece")
        val revision = f.host.coordinator.getStateRevision()
        assertEquals(listOf("Greece"), f.host.getMessageContacts().map { it.civId })
        assertNull(f.send("Greece", "Can we agree to peace?"))
        assertEquals(revision, f.host.coordinator.getStateRevision())
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        val sent = f.host.getMessages().single()
        assertEquals("Rome", sent.senderCivId)
        assertEquals("NATION", sent.origin)
        assertNull(sent.deliveredTurnSerial)
        assertTrue(f.host.coordinator.observe("Greece").inbox.isEmpty())
        val checkpoint = FileCheckpointStore(DesktopAgentMatch.checkpointDir(f.dataDir, f.host.matchId), f.host.matchId).recover()
        assertNotNull("Success must have a checksum-verified durable checkpoint", checkpoint)
        val restored = MatchCoordinator.loadFromJson(checkpoint!!.bundleJson)
        assertEquals(sent.messageId, restored.getMessageHistory("Rome").single().messageId)
        restored.execute(EndTurnCommand("Greece", "end-greece", restored.getStateRevision()))
        restored.execute(EndTurnCommand("Rome", "end-rome", restored.getStateRevision()))
        assertEquals(sent.messageId, restored.observe("Greece").inbox.single().messageId)
    }

    @Test fun `spectator message is labeled and rejected sends create no mail`() {
        val f = Fixture(Constants.spectator)
        assertNull(f.send("Greece", "Explore the coast."))
        assertEquals("SPECTATOR", f.host.getMessages().single().origin)
        assertEquals(Constants.spectator, f.host.getMessages().single().senderCivId)
        assertNotNull(f.send("Greece", "   "))
        assertEquals(1, f.host.getMessages().size)
        f.host.close()
        assertNotNull(f.send("Greece", "This session is closed."))
        assertEquals(1, f.host.getMessages().size)
    }
}
