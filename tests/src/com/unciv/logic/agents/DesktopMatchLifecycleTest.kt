package com.unciv.logic.agents

import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Closing the UI detaches it promptly while a scripted provider finishes normal engine calls. */
@RunWith(BaseTestRunner::class)
class DesktopMatchLifecycleTest {
    private val fixtures = ArrayList<Fixture>()
    private val callers = ArrayList<ExecutorService>()

    private inner class Fixture {
        lateinit var host: DesktopAgentMatch
            private set
        val providerStarted = CountDownLatch(1)
        val allowProviderFinish = CountDownLatch(1)
        val providerReturned = CountDownLatch(1)
        val shutdownStarted = CountDownLatch(1)
        val allowShutdownFinish = CountDownLatch(1)
        val shutdownFinished = CountDownLatch(1)
        val activeInvocations = AtomicInteger()
        val supervisorStarts = AtomicInteger()
        val shutdownCalls = AtomicInteger()
        val activeAtShutdown = AtomicInteger(-1)
        val killCalls = AtomicInteger()
        val successfulObservations = AtomicInteger()
        val successfulEndTurns = AtomicInteger()
        val invocations = CopyOnWriteArrayList<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val romeId: String
        val greeceId: String

        init {
            val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!)
            romeId = rome.civID
            greeceId = greece.civID
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(2, 0))
            game.gameInfo.currentPlayer = romeId
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "lifecycle", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) {
                match.controllers[civ.civID] = CivilizationController(civ.civID,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor(civ.civID))
            }
            val supervisor = object : SupervisorRunner {
                override fun isAlive() = shutdownFinished.count > 0
                override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                    val civId = (control["binding"] as Map<*, *>)["civId"] as String
                    invocations.add(civId)
                    activeInvocations.incrementAndGet()
                    providerStarted.countDown()
                    try {
                        check(allowProviderFinish.await(10, TimeUnit.SECONDS)) { "Test never released its scripted provider" }
                        host.answerEngineRequest("observe", mapOf("civId" to civId))
                        successfulObservations.incrementAndGet()
                        host.answerEngineRequest("end_turn", mapOf("civId" to civId,
                            "actionId" to "lifecycle-end-${UUID.randomUUID()}"))
                        successfulEndTurns.incrementAndGet()
                        return SupervisorProcess.TurnOutcome("completed_turn", "session-$civId", null,
                            true, listOf("observe", "end_turn"), "completed")
                    } catch (error: Throwable) {
                        failures.add(error)
                        return SupervisorProcess.TurnOutcome("failed", null, null, false,
                            emptyList(), error.message ?: "Scripted provider failed")
                    } finally {
                        activeInvocations.decrementAndGet()
                        providerReturned.countDown()
                    }
                }

                override fun shutdown() {
                    shutdownCalls.incrementAndGet()
                    activeAtShutdown.set(activeInvocations.get())
                    shutdownStarted.countDown()
                    try {
                        check(allowShutdownFinish.await(10, TimeUnit.SECONDS)) { "Test never released its scripted shutdown" }
                    } catch (error: Throwable) { failures.add(error) }
                    finally { shutdownFinished.countDown() }
                }
                override fun kill() { killCalls.incrementAndGet() }
            }
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-host-lifecycle-").toFile(), supervisorFactory = {
                    supervisorStarts.incrementAndGet()
                    supervisor
                })
            fixtures.add(this)
        }
    }

    @After fun allowEveryScriptedOperationToFinishBeforeTheNextTest() {
        for (f in fixtures) {
            f.allowProviderFinish.countDown()
            f.allowShutdownFinish.countDown()
        }
        for (caller in callers) {
            caller.shutdown()
            assertTrue("Close caller must finish naturally", caller.awaitTermination(10, TimeUnit.SECONDS))
        }
        for (f in fixtures) {
            f.host.pause()
            f.host.close()
            assertTrue("The scripted provider must drain before the fixture changes",
                awaitCondition(10000) { f.activeInvocations.get() == 0 && f.host.getRunState() in setOf(
                    AgentRunState.PAUSED, AgentRunState.NEEDS_ATTENTION, AgentRunState.FINISHED) })
        }
    }

    private fun closeOnSeparateCaller(f: Fixture): CountDownLatch {
        val returned = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor().also { callers.add(it) }
        caller.submit {
            try { f.host.close() }
            catch (error: Throwable) { f.failures.add(error) }
            finally { returned.countDown() }
        }
        return returned
    }

    @Test fun `close returns while a provider drains normal RPCs then shuts down without another turn or kill`() {
        val f = Fixture()
        val revisionBefore = f.host.coordinator.getStateRevision()
        f.host.startAutomaticPlay()
        assertTrue("The first provider must reach its controlled invocation", f.providerStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1, f.activeInvocations.get())

        val closeReturned = closeOnSeparateCaller(f)
        assertTrue("Closing the UI must not wait on a live provider or shutdown", closeReturned.await(2, TimeUnit.SECONDS))
        assertEquals("The running provider must be allowed to finish normally", 1, f.activeInvocations.get())
        assertEquals("Supervisor shutdown must wait until the provider returns", 1L, f.shutdownStarted.count)
        assertEquals(0, f.killCalls.get())
        assertFalse("A detached match cannot resume", f.host.canResume())
        f.host.resume()
        f.host.run()

        f.allowProviderFinish.countDown()
        assertTrue("The original provider must finish", f.providerReturned.await(5, TimeUnit.SECONDS))
        assertTrue("Shutdown must begin after draining", f.shutdownStarted.await(5, TimeUnit.SECONDS))
        assertEquals("Shutdown must not overlap an active invocation", 0, f.activeAtShutdown.get())
        assertEquals(1, f.successfulObservations.get())
        assertEquals(1, f.successfulEndTurns.get())
        assertTrue("The in-flight turn must still commit normally", f.host.coordinator.getStateRevision() > revisionBefore)
        assertEquals(f.greeceId, f.host.getActiveCivId())
        assertEquals(listOf(f.romeId), f.invocations)
        assertEquals(1, f.supervisorStarts.get())
        assertEquals(0, f.killCalls.get())
        assertTrue(f.failures.joinToString("\n"), f.failures.isEmpty())

        f.allowShutdownFinish.countDown()
        assertTrue(f.shutdownFinished.await(5, TimeUnit.SECONDS))
        f.host.close()
        f.host.startAutomaticPlay()
        f.host.resume()
        assertEquals("Repeated close must shut down only once", 1, f.shutdownCalls.get())
        assertEquals(listOf(f.romeId), f.invocations)
        assertFalse(f.host.canResume())
    }

    @Test fun `an idle closed host cannot start or resume a provider`() {
        val f = Fixture()
        val revisionBefore = f.host.coordinator.getStateRevision()
        val profileBefore = f.host.coordinator.getController(f.romeId)!!.agentProfile!!.profileId
        f.host.close()
        assertFalse(f.host.canResume())
        assertFalse(f.host.canPause())
        assertFalse(f.host.canRetry())

        f.host.run()
        f.host.resume()
        f.host.startAutomaticPlay()
        f.host.retry()
        f.host.stop()
        f.host.stepOnce()
        f.host.takeOverHuman()
        f.host.returnToAgent(f.romeId, AgentProfile.defaultFor(f.romeId).also {
            it.displayName = "Replacement agent"
        })

        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        assertEquals(revisionBefore, f.host.coordinator.getStateRevision())
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.romeId)!!.kind)
        assertEquals(profileBefore, f.host.coordinator.getController(f.romeId)!!.agentProfile!!.profileId)
        assertEquals(0, f.supervisorStarts.get())
        assertEquals(0, f.killCalls.get())
        assertTrue(f.invocations.isEmpty())
    }

    @Test fun `leaving a retained match drains its turn and reopening requires explicit resume`() {
        val f = Fixture()
        val revisionBefore = f.host.coordinator.getStateRevision()
        f.host.startAutomaticPlay()
        assertTrue("The provider must be running before navigation", f.providerStarted.await(5, TimeUnit.SECONDS))

        f.host.onMatchLeft()
        assertFalse("A match outside the UI cannot resume", f.host.canResume())
        assertFalse(f.host.canPause())
        f.host.run()
        f.host.resume()
        f.host.startAutomaticPlay()
        assertEquals("Leaving must preserve the running turn", 1, f.activeInvocations.get())
        assertEquals(listOf(f.romeId), f.invocations)
        assertEquals(0, f.shutdownCalls.get())
        assertEquals(0, f.killCalls.get())

        f.allowProviderFinish.countDown()
        assertTrue("The provider must finish its original turn", f.providerReturned.await(5, TimeUnit.SECONDS))
        assertTrue("Navigation must settle at the next turn boundary",
            awaitCondition(5000) { f.host.getRunState() == AgentRunState.PAUSED })
        assertEquals(1, f.successfulObservations.get())
        assertEquals(1, f.successfulEndTurns.get())
        assertTrue(f.host.coordinator.getStateRevision() > revisionBefore)
        assertEquals(f.greeceId, f.host.getActiveCivId())
        assertEquals("A retained match keeps its supervisor", 0, f.shutdownCalls.get())
        assertEquals(listOf(f.romeId), f.invocations)
        assertFalse(f.host.canResume())

        f.host.onMatchEntered()
        assertEquals("Returning must not start automated play", AgentRunState.PAUSED, f.host.getRunState())
        assertTrue("Explicit resume is available after returning", f.host.canResume())
        assertEquals(listOf(f.romeId), f.invocations)
        assertEquals(1, f.supervisorStarts.get())
        assertEquals(0, f.shutdownCalls.get())
        assertEquals(0, f.killCalls.get())
        assertTrue(f.failures.joinToString("\n"), f.failures.isEmpty())
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
