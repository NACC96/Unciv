package com.unciv.logic.agents

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.presentation.VisualViewKey
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import java.nio.file.Files

/** Real engine/checkpoint/publication flow; only the GL drawing boundary is replaced. */
@RunWith(BaseTestRunner::class)
class DesktopSnapshotPublicationTest {
    private var previousApp: Application? = null
    private var previousGraphics: Graphics? = null

    @Before
    fun runPresentationTasksWithoutAWindow() {
        previousApp = Gdx.app
        previousGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java)
        Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }

    @After
    fun restoreGraphicsBoundary() {
        Gdx.app = previousApp
        Gdx.graphics = previousGraphics
    }

    private class Fixture(spectatorViewer: Boolean = false, humanController: Boolean = false) {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val published = ArrayList<GameInfo>()
        val host: DesktopAgentMatch
        val romeId: String
        val warriorId: Int
        val settlerId: Int

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            romeId = rome.civID
            game.gameInfo.currentPlayer = romeId
            game.gameInfo.currentPlayerCiv = rome
            warriorId = game.addUnit("Warrior", rome, game.getTile(0, 0)).id
            settlerId = game.addUnit("Settler", rome, game.getTile(0, 0)).id
            val viewer = if (spectatorViewer) game.addCiv(game.ruleset.nations["Spectator"]!!, isPlayer = true) else rome
            val match = AgentMatchState(matchId = "publication-test", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            match.controllers[romeId] = if (humanController) CivilizationController(romeId, ControllerKind.HUMAN)
                else CivilizationController(romeId, ControllerKind.EXTERNAL_AGENT,
                    AgentProfile("p1", "Rome agent", "codex", "gpt-6-luna", "xhigh"))
            val screen = Mockito.mock(WorldScreen::class.java) { invocation ->
                if (invocation.method.name == "installAgentSnapshot") {
                    published.add(invocation.getArgument(0))
                    true
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(viewer.civID)
            Mockito.`when`(screen.currentVisualViewKey()).thenReturn(VisualViewKey(viewer.civID, spectatorViewer, 1L))
            Mockito.`when`(screen.fogOfWar).thenReturn(true)
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-publication-").toFile(), worldScreen = screen)
            // Match the production attachment: human input requires this host's installed view.
            Mockito.`when`(screen.agentSession).thenReturn(host)
            if (!humanController)
                DesktopAgentMatch::class.java.getDeclaredField("providerRunCivId").apply { isAccessible = true }
                    .set(host, romeId)
        }

        fun act(id: String, type: String, args: Map<String, Any?>): Any? = host.answerEngineRequest("act",
            mapOf("protocolVersion" to 1, "actorCivId" to romeId, "actionId" to id,
                "expectedRevision" to host.coordinator.getStateRevision(), "type" to type, "args" to args))
    }

    @Test
    fun `committed warrior movement reaches the map before the provider ends its turn`() {
        val f = Fixture()
        f.act("move-1", "unit.move", mapOf("unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)))

        Assert.assertTrue("Every committed move must publish a render snapshot immediately", f.published.isNotEmpty())
        val unit = f.published.last().getCivilization(f.romeId).units.getCivUnits().first { it.id == f.warriorId }
        Assert.assertEquals(HexCoord(1, 0), unit.getTile().position)
        Assert.assertEquals("The rendered source must stay isolated from engine mutations", HexCoord.Zero,
            f.game.gameInfo.getCivilization(f.romeId).units.getCivUnits().first { it.id == f.warriorId }.getTile().position)
    }

    @Test
    fun `founding replaces the settler with a city in the published snapshot`() {
        val f = Fixture()
        f.act("found-1", "unit.found", mapOf("unitId" to f.settlerId))

        Assert.assertTrue("A founded city must appear without waiting for the full turn", f.published.isNotEmpty())
        val rome = f.published.last().getCivilization(f.romeId)
        Assert.assertEquals(HexCoord.Zero, rome.cities.single().location)
        Assert.assertFalse(rome.units.getCivUnits().any { it.id == f.settlerId })
    }

    @Test
    fun `reads and rejected mutations leave the rendered snapshot alone`() {
        val f = Fixture()
        f.host.answerEngineRequest("observe", mapOf("civId" to f.romeId))
        try {
            f.act("invalid-move", "unit.move", mapOf("unitId" to -1,
                "destination" to mapOf("x" to 1, "y" to 0)))
            Assert.fail("A nonexistent unit must be rejected")
        } catch (_: EngineCallException) {
            // The renderer must not receive uncommitted or fabricated state.
        }
        Assert.assertTrue(f.published.isEmpty())
    }

    @Test
    fun `a snapshot queued before close never updates the detached screen`() {
        val queued = ArrayList<Runnable>()
        Mockito.doAnswer { invocation ->
            queued.add(invocation.getArgument(0))
            null
        }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
        val f = Fixture()
        f.act("move-before-close", "unit.move", mapOf("unitId" to f.warriorId,
            "destination" to mapOf("x" to 1, "y" to 0)))
        Assert.assertTrue("The regression requires a pending GL publication", queued.isNotEmpty())

        f.host.close()
        queued.forEach { it.run() }

        Assert.assertTrue("Disposed screens must not receive previously queued snapshots", f.published.isEmpty())
    }

    @Test
    fun `notes metadata publishes a paired ordinary save without a gameplay revision change`() {
        val f = Fixture()
        val before = f.host.coordinator.getStateRevision()
        f.host.answerEngineRequest("notes_put", mapOf("civId" to f.romeId,
            "notes" to "Remember the northern hills", "expectedNotesRevision" to 0))
        Assert.assertEquals(before, f.host.coordinator.getStateRevision())
        Assert.assertEquals(1, f.published.size)
        val saved = AgentOrdinarySave.read(f.published.single())!!
        Assert.assertEquals("Remember the northern hills", saved.privateNotes[f.romeId])
        Assert.assertEquals(f.host.getCommittedMatchStateJson(), f.published.single().agentMatchStateJson)
    }

    @Test
    fun `spectator composer queues mail in the published ordinary-save pair before the next turn`() {
        val f = Fixture(spectatorViewer = true)
        val before = f.host.coordinator.getStateRevision()
        val done = java.util.concurrent.CountDownLatch(1)
        var sendError: String? = "No callback"
        f.host.sendMessage(f.romeId, "Confirm receipt next turn") { error ->
            sendError = error
            done.countDown()
        }
        Assert.assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Assert.assertNull(sendError)
        Assert.assertEquals(before, f.host.coordinator.getStateRevision())
        Assert.assertEquals(1, f.published.size)
        val saved = AgentOrdinarySave.read(f.published.single())!!
        val mail = saved.diplomaticMessages.single()
        Assert.assertEquals("Confirm receipt next turn", mail.text)
        Assert.assertEquals(f.romeId, mail.targetCivId)
        Assert.assertNull(mail.deliveredTurnSerial)
        Assert.assertEquals(f.host.getCommittedMatchStateJson(), f.published.single().agentMatchStateJson)
    }
    private fun blockCheckpointWrites(f: Fixture) {
        f.host.recoveryDirectory.deleteRecursively()
        f.host.recoveryDirectory.parentFile.mkdirs()
        f.host.recoveryDirectory.writeText("A file blocks the temporary checkpoint directory")
    }

    private fun assertPublishedRecovery(f: Fixture, expectedRevision: Int) {
        Assert.assertTrue(f.host.coordinator.isPaused())
        Assert.assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        Assert.assertTrue("A persistence failure must leave Retry available", f.host.canRetry())
        Assert.assertFalse(f.host.canResume())
        Assert.assertEquals(1, f.published.size)
        val saved = AgentOrdinarySave.read(f.published.single())!!
        Assert.assertTrue(saved.matchPaused)
        Assert.assertTrue(saved.matchPausedReason!!.contains("Checkpoint persistence failed"))
        Assert.assertEquals(expectedRevision, saved.stateRevision)
        Assert.assertEquals(f.host.getCommittedMatchStateJson(), f.published.single().agentMatchStateJson)
    }

    @Test
    fun `failed human action publishes recovery metadata and exposes Retry`() {
        val f = Fixture(humanController = true)
        blockCheckpointWrites(f)
        val error = f.host.executeHumanAction { listOf({ revision: Int ->
            RenameUnitCommand(f.romeId, "ui-rename", revision, f.warriorId, "Uncommitted name")
        }) }
        Assert.assertNotNull(error)
        assertPublishedRecovery(f, 0)
        Assert.assertNull(f.published.single().getCivilization(f.romeId).units.getCivUnits()
            .first { it.id == f.warriorId }.instanceName)
        f.host.close()
    }

    @Test
    fun `failed human end turn publishes recovery metadata without advancing the world`() {
        val f = Fixture(humanController = true)
        blockCheckpointWrites(f)
        Assert.assertTrue(f.host.onNextTurnPressed())
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (f.host.getRunState() != AgentRunState.NEEDS_ATTENTION && System.nanoTime() < deadline)
            Thread.sleep(5)
        assertPublishedRecovery(f, 0)
        Assert.assertEquals(0, f.published.single().turns)
        f.host.close()
    }

    @Test
    fun `failed guidance publishes recovery metadata and leaves uncommitted text out`() {
        val f = Fixture()
        blockCheckpointWrites(f)
        Assert.assertThrows(AgentCommandException::class.java) { f.host.queueGuidance(f.romeId, "Uncommitted guidance") }
        assertPublishedRecovery(f, 0)
        Assert.assertTrue(AgentOrdinarySave.read(f.published.single())!!.guidanceQueue.isEmpty())
        f.host.close()
    }

}
