package com.unciv.logic.agents

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.unciv.Constants
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.logic.GameInfo
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** An admitted provider drains naturally while recovery controls wait for its worker. */
@RunWith(BaseTestRunner::class)
class DesktopRecoveryLifecycleTest {
    private var previousApp: Application? = null
    private var previousGraphics: Graphics? = null
    private val fixtures = ArrayList<Fixture>()

    @Before fun presentationWithoutAWindow() {
        previousApp = Gdx.app
        previousGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java)
        Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            null
        }.`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }

    private inner class Fixture(private val completeTurn: Boolean = false) {
        val directory = Files.createTempDirectory("unciv-recovery-lifecycle-").toFile()
        val providerStarted = CountDownLatch(1)
        val allowEngineCall = CountDownLatch(1)
        val engineCallFinished = CountDownLatch(1)
        val allowProviderReturn = CountDownLatch(1)
        val providerReturned = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        val allowRetryReturn = CountDownLatch(1)
        val shutdownFinished = CountDownLatch(1)
        val activeInvocations = AtomicInteger()
        val shutdownCalls = AtomicInteger()
        val activeAtShutdown = AtomicInteger(-1)
        val killCalls = AtomicInteger()
        val invocations = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<GameInfo>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val persistenceErrors = CopyOnWriteArrayList<EngineCallException>()
        val romeId: String
        val greeceId: String
        val warriorId: Int
        val host: DesktopAgentMatch

        init {
            val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            game.addCiv(game.ruleset.nations[Constants.spectator]!!)
            romeId = rome.civID
            greeceId = greece.civID
            warriorId = game.addUnit("Warrior", rome, game.getTile(0, 0)).id
            game.addUnit("Warrior", greece, game.getTile(2, 0))
            game.gameInfo.currentPlayer = romeId
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "recovery-lifecycle", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) {
                match.controllers[civ.civID] = CivilizationController(civ.civID,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor(civ.civID))
            }
            val screen = Mockito.mock(WorldScreen::class.java) { invocation ->
                if (invocation.method.name == "installAgentSnapshot") {
                    published.add(invocation.getArgument(0))
                    null
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            Mockito.`when`(screen.currentViewerCivId()).thenReturn(Constants.spectator)
            val supervisor = object : SupervisorRunner {
                override fun isAlive() = shutdownFinished.count > 0

                override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                    val civId = (control["binding"] as Map<*, *>)["civId"] as String
                    val invocation = invocations.size
                    invocations.add(civId)
                    activeInvocations.incrementAndGet()
                    try {
                        if (invocation > 0) {
                            retryStarted.countDown()
                            check(allowRetryReturn.await(10, TimeUnit.SECONDS)) { "Retry was never released" }
                            return outcome("failed", "Scripted retry returned normally")
                        }
                        providerStarted.countDown()
                        check(allowEngineCall.await(10, TimeUnit.SECONDS)) { "Engine call was never released" }
                        try {
                            if (completeTurn) {
                                host.answerEngineRequest("end_turn", mapOf("civId" to civId,
                                    "actionId" to "recovery-end-turn"))
                            } else {
                                try {
                                    host.answerEngineRequest("act", mapOf("protocolVersion" to 1,
                                        "actorCivId" to civId, "actionId" to "recovery-rename",
                                        "expectedRevision" to host.coordinator.getStateRevision(),
                                        "type" to "unit.rename", "args" to mapOf("unitId" to warriorId,
                                            "newName" to "Uncommitted name")))
                                    errors.add(AssertionError("The blocked checkpoint unexpectedly succeeded"))
                                } catch (error: EngineCallException) {
                                    persistenceErrors.add(error)
                                }
                            }
                        } finally { engineCallFinished.countDown() }
                        check(allowProviderReturn.await(10, TimeUnit.SECONDS)) { "Provider was never released" }
                        return outcome(if (completeTurn) "completed_turn" else "failed",
                            if (completeTurn) "completed" else "Scripted provider observed checkpoint failure")
                    } catch (error: Throwable) {
                        errors.add(error)
                        return outcome("failed", error.message ?: "Scripted provider failed")
                    } finally {
                        activeInvocations.decrementAndGet()
                        if (invocation == 0) providerReturned.countDown()
                    }
                }

                private fun outcome(status: String, detail: String) = SupervisorProcess.TurnOutcome(
                    status, null, null, status == "completed_turn", emptyList(), detail)

                override fun shutdown() {
                    shutdownCalls.incrementAndGet()
                    activeAtShutdown.set(activeInvocations.get())
                    shutdownFinished.countDown()
                }
                override fun kill() { killCalls.incrementAndGet() }
            }
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(), directory,
                worldScreen = screen, supervisorFactory = { supervisor })
            fixtures.add(this)
        }

        fun start() {
            host.startAutomaticPlay()
            assertTrue("Provider must be admitted before failure/navigation", providerStarted.await(5, TimeUnit.SECONDS))
        }

        fun blockWrites() {
            assertTrue(!host.recoveryDirectory.exists() || host.recoveryDirectory.deleteRecursively())
            host.recoveryDirectory.parentFile.mkdirs()
            host.recoveryDirectory.writeText("A file blocks this test's checkpoint directory")
        }

        fun repairWrites() {
            assertTrue(host.recoveryDirectory.delete())
            assertTrue(host.recoveryDirectory.mkdirs())
        }

        fun failAdmittedAction() {
            blockWrites()
            allowEngineCall.countDown()
            assertTrue("Failed RPC must finish before assertions", engineCallFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, persistenceErrors.size)
            assertTrue(persistenceErrors.single().message!!.contains("Checkpoint persistence failed"))
        }

        fun assertPublishedPause() {
            assertTrue(host.coordinator.isPaused())
            assertEquals(AgentRunState.NEEDS_ATTENTION, host.getRunState())
            assertFalse(host.canResume())
            val saved = AgentOrdinarySave.read(published.last())!!
            assertTrue("The renderer/ordinary save must contain the recovery metadata", saved.matchPaused)
            assertTrue(saved.matchPausedReason!!.contains("Checkpoint persistence failed"))
            assertEquals(host.getCommittedMatchStateJson(), published.last().agentMatchStateJson)
        }

        fun assertStillAdmitted() {
            assertEquals(1, activeInvocations.get())
            assertEquals(0, shutdownCalls.get())
            assertEquals(0, killCalls.get())
        }
    }

    @After fun drainScriptedProvidersBeforeRestoringTheFixture() {
        try {
            for (f in fixtures) {
                f.host.pause()
                // Release the scripts to return naturally; never interrupt their thread or stdin.
                f.allowEngineCall.countDown()
                f.allowProviderReturn.countDown()
                f.allowRetryReturn.countDown()
                assertTrue("Provider must return naturally", f.providerReturned.await(10, TimeUnit.SECONDS))
                f.host.close()
                assertTrue("Shutdown must follow natural provider return", f.shutdownFinished.await(10, TimeUnit.SECONDS))
                assertEquals(0, f.activeAtShutdown.get())
                assertEquals(0, f.killCalls.get())
                assertTrue(f.errors.joinToString("\n"), f.errors.isEmpty())
                f.directory.deleteRecursively()
            }
        } finally {
            Gdx.app = previousApp
            Gdx.graphics = previousGraphics
        }
    }

    @Test fun `failed admitted action publishes pause but Retry waits for worker return`() {
        val f = Fixture()
        f.start()
        f.failAdmittedAction()
        f.assertPublishedPause()
        f.assertStillAdmitted()
        assertFalse("Recovery must not replace a provider that still owns the admitted turn", f.host.canRetry())

        f.repairWrites()
        val generation = f.host.coordinator.getPublicationGeneration()
        f.host.retry()
        assertTrue("Retry must not clear a durable pause while its worker is live", f.host.coordinator.isPaused())
        assertEquals(generation, f.host.coordinator.getPublicationGeneration())
        assertEquals(listOf(f.romeId), f.invocations)
        f.assertStillAdmitted()

        f.allowProviderReturn.countDown()
        assertTrue(f.providerReturned.await(5, TimeUnit.SECONDS))
        assertTrue("Retry becomes available after the existing worker drains", awaitCondition { f.host.canRetry() })
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        assertEquals(listOf(f.romeId), f.invocations)
    }

    @Test fun `completed provider cannot erase spectator mail persistence failure or dispatch next civ`() {
        val f = Fixture(completeTurn = true)
        f.start()
        f.allowEngineCall.countDown()
        assertTrue("The original turn must commit first", f.engineCallFinished.await(5, TimeUnit.SECONDS))
        assertEquals(f.greeceId, f.host.getActiveCivId())
        val revision = f.host.coordinator.getStateRevision()
        f.blockWrites()
        val callback = CountDownLatch(1)
        var sendError: String? = null
        f.host.sendMessage(f.greeceId, "Explore the coast") { error ->
            sendError = error
            callback.countDown()
        }
        assertTrue(callback.await(5, TimeUnit.SECONDS))
        assertNotNull(sendError)
        f.assertPublishedPause()
        assertEquals(revision, f.host.coordinator.getStateRevision())
        assertTrue(AgentOrdinarySave.read(f.published.last())!!.diplomaticMessages.isEmpty())
        assertFalse(f.host.canRetry())
        f.assertStillAdmitted()

        f.allowProviderReturn.countDown()
        assertTrue(f.providerReturned.await(5, TimeUnit.SECONDS))
        assertTrue("The completed outcome must retain recovery attention after drain", awaitCondition { f.host.canRetry() })
        f.assertPublishedPause()
        assertTrue(f.host.getFailureReason()!!.contains("Checkpoint persistence failed"))
        assertEquals(f.greeceId, f.host.getActiveCivId())
        assertEquals("No next provider may be admitted while checkpoint recovery is required",
            listOf(f.romeId), f.invocations)
    }

    @Test fun `hidden failure reopens with Retry after drain and never runs automatically`() {
        val f = Fixture()
        f.start()
        f.host.onMatchLeft()
        f.failAdmittedAction()
        assertTrue(f.host.coordinator.isPaused())
        assertFalse(f.host.canRetry())
        f.assertStillAdmitted()
        f.allowProviderReturn.countDown()
        assertTrue(f.providerReturned.await(5, TimeUnit.SECONDS))
        assertFalse("Hidden matches expose no recovery controls", f.host.canRetry())

        f.host.onMatchEntered()
        assertTrue("Reopening must reconcile the durable failure after the worker drains",
            awaitCondition { f.host.canRetry() })
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        assertFalse(f.host.canResume())
        f.host.resume()
        f.host.startAutomaticPlay()
        assertEquals("Opening or Resume must not silently clear the failure", listOf(f.romeId), f.invocations)
        assertTrue(f.host.coordinator.isPaused())

        f.repairWrites()
        f.host.retry()
        assertTrue("Explicit Retry admits a new provider", f.retryStarted.await(5, TimeUnit.SECONDS))
        assertFalse(f.host.coordinator.isPaused())
        assertEquals(listOf(f.romeId, f.romeId), f.invocations)
        f.assertStillAdmitted()
        f.host.pause()
        f.allowRetryReturn.countDown()
        assertTrue(awaitCondition { f.activeInvocations.get() == 0 && f.host.getRunState() == AgentRunState.NEEDS_ATTENTION })
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}
