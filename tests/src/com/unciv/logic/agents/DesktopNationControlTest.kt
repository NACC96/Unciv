package com.unciv.logic.agents

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.unciv.Constants
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.logic.map.HexCoord
import com.unciv.json.json
import org.mockito.Mockito
import org.mockito.Answers
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(BaseTestRunner::class)
class DesktopNationControlTest {
    private val fixtures = ArrayList<Fixture>()
    private var previousApp: Application? = null
    private var previousGraphics: Graphics? = null
    @Before fun mockPresentation() {
        previousApp = Gdx.app; previousGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java); Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { invocation -> invocation.getArgument<Runnable>(0).run(); null }
            .`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }
    private inner class Fixture(human: Boolean = false, wrongViewer: Boolean = false, failProvider: Boolean = false, attachScreen: Boolean = false, includeSpectator: Boolean = true, stallSupervisor: Boolean = false) {
        lateinit var host: DesktopAgentMatch
        val supervisorEntered = CountDownLatch(1)
        val releaseSupervisor = CountDownLatch(if (stallSupervisor) 1 else 0)
        val installAttempts = AtomicInteger()
        val installFence = AgentPublicationFence()
        val acceptedGenerations = CopyOnWriteArrayList<Long>()
        var installFailure: String? = null
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val kills = AtomicInteger()
        val cancels = CopyOnWriteArrayList<String>()
        @Volatile var cancelled = false
        val callAfterCancel = AtomicReference<String>()
        val shutdowns = AtomicInteger()
        val callbacks = AtomicInteger()
        val completed = CountDownLatch(1)
        var error: String? = "not called"
        val rome: String
        val greece: String
        val spectator: String
        val romeUnit: Int
        val renderedViewer = AtomicReference<String>()
        val publishedViewers = CopyOnWriteArrayList<String>()
        val screen: WorldScreen?
        init {
            val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val r = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val g = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val s = if (includeSpectator) game.addCiv(game.ruleset.nations[Constants.spectator]!!, isPlayer = true) else null
            rome = r.civID; greece = g.civID; spectator = s?.civID ?: Constants.spectator
            romeUnit = game.addUnit("Warrior", r, game.getTile(0, 0)).id; game.addUnit("Warrior", g, game.getTile(2, 0))
            game.gameInfo.currentPlayer = rome; game.gameInfo.currentPlayerCiv = r
            val match = AgentMatchState(matchId = "desktop-nation-control", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS,
                localControlCivId = if (includeSpectator) spectator else if (human) rome else null,
                localViewingCivId = if (wrongViewer) greece else if (human) rome else spectator)
            match.controllers[rome] = CivilizationController(rome, if (human) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT,
                if (human) null else AgentProfile.defaultFor("Rome"))
            match.controllers[greece] = CivilizationController(greece, ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor("Greece"))
            renderedViewer.set(match.localViewingCivId)
            screen = if (attachScreen) Mockito.mock(WorldScreen::class.java) { invocation ->
                if (invocation.method.name == "installAgentSnapshot") {
                    installAttempts.incrementAndGet()
                    val generation = invocation.getArgument<Long>(2)
                    if (!installFence.accept(generation)) return@mock null
                    acceptedGenerations.add(generation)
                    installFailure?.let { throw IllegalStateException(it) }
                    publishedViewers.add(invocation.getArgument(3)); null
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }.also {
                Mockito.`when`(it.currentViewerCivId()).thenAnswer { renderedViewer.get() }
            } else null
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-nation-boundary-").toFile(), worldScreen = screen, supervisorFactory = {
                    supervisorEntered.countDown()
                    check(releaseSupervisor.await(10, TimeUnit.SECONDS))
                    object : SupervisorRunner {
                        override fun isAlive() = true
                        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                            calls.incrementAndGet(); started.countDown()
                            try {
                                check(release.await(10, TimeUnit.SECONDS))
                                if (cancelled) {
                                    // A revoked run cannot commit anything more after the takeover.
                                    callAfterCancel.set(try {
                                        host.answerEngineRequest("observe", mapOf("civId" to rome)); "accepted"
                                    } catch (ex: EngineCallException) { ex.code })
                                    return SupervisorProcess.TurnOutcome("cancelled", "resume-rome", null, false, emptyList(), "cancelled by host")
                                }
                                if (failProvider) {
                                    host.answerEngineRequest("act", mapOf("protocolVersion" to 1, "actionId" to "partial-move",
                                        "expectedRevision" to host.coordinator.getStateRevision(), "type" to "unit.move",
                                        "actorCivId" to rome, "args" to mapOf("unitId" to romeUnit,
                                            "destination" to mapOf("x" to 1, "y" to 0))))
                                    return SupervisorProcess.TurnOutcome("failed", "resume-rome", null, false, emptyList(), "scripted provider failure")
                                }
                                host.answerEngineRequest("end_turn", mapOf("civId" to rome, "actionId" to "nation-control-end"))
                                // Final reads remain admitted even after the next civ's turn begins.
                                host.answerEngineRequest("observe", mapOf("civId" to rome))
                                return SupervisorProcess.TurnOutcome("completed_turn", "resume-rome", null, true, emptyList(), "done")
                            } finally { returned.countDown() }
                        }
                        override fun cancelRun(runId: String) { cancels.add(runId); cancelled = true; release.countDown() }
                        override fun shutdown() { shutdowns.incrementAndGet() }
                        override fun kill() { kills.incrementAndGet() }
                    }
                })
            screen?.let { Mockito.`when`(it.agentSession).thenReturn(host) }
            fixtures.add(this)
        }
        fun request(target: String = greece, change: NationControlChange = NationControlChange.PLAY_AS, profile: AgentProfile? = null) {
            host.requestNationChange(target, change, profile) {
                error = it; callbacks.incrementAndGet(); completed.countDown()
            }
        }
        fun epoch(): Int = DesktopAgentMatch::class.java.getDeclaredField("authorityEpoch").apply { isAccessible = true }.getInt(host)
        @Suppress("UNCHECKED_CAST")
        fun sessions(): HashMap<String, String> = DesktopAgentMatch::class.java.getDeclaredField("sessions").apply { isAccessible = true }.get(host) as HashMap<String, String>
    }
    @After fun drain() {
        fixtures.forEach { it.releaseSupervisor.countDown(); it.release.countDown(); it.host.pause(); it.host.close() }
        fixtures.forEach { f -> if (f.started.count == 0L) assertTrue(f.returned.await(5, TimeUnit.SECONDS)) }
        Gdx.app = previousApp; Gdx.graphics = previousGraphics
    }
    private fun Fixture.lock(): Any = DesktopAgentMatch::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(host)
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test fun `another nation's change applies mid-run without disturbing the admitted provider`() {
        val f = Fixture(); f.sessions()[f.greece] = "old-greece"; f.sessions()[f.rome] = "old-rome"
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val revision = f.host.coordinator.getStateRevision(); val epoch = f.epoch()
        f.request(); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNull(f.error); assertEquals(1, f.callbacks.get()); assertNull(f.host.getPendingNationChange())
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.greece)!!.kind)
        assertEquals(revision + 1, f.host.coordinator.getStateRevision())
        assertEquals("Rome's admitted run keeps its authority", epoch, f.epoch()); assertNull(f.sessions()[f.greece])
        assertEquals(AgentRunState.AGENT_DECIDING, f.host.getRunState())
        assertTrue(f.cancels.isEmpty()); assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        // Play continues to Greece's turn, which now waits for its human.
        assertTrue(awaitCondition { f.host.getRunState() == AgentRunState.WAITING_HUMAN })
        assertEquals(f.greece, f.host.getActiveCivId())
        assertEquals(1, f.calls.get()); assertEquals("resume-rome", f.sessions()[f.rome]); assertEquals(0, f.kills.get())
    }

    @Test fun `play as the active nation cuts off its admitted provider and hands over the turn`() {
        val f = Fixture(); f.sessions()[f.rome] = "old-rome"
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val epoch = f.epoch()
        f.request(f.rome); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNull(f.error)
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.rome)!!.kind)
        assertEquals(f.rome, f.host.coordinator.getLocalViewingCivId())
        assertEquals(1, f.cancels.size); assertEquals(epoch + 1, f.epoch()); assertNull(f.sessions()[f.rome])
        assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        assertEquals("AUTHORITY_REVOKED", f.callAfterCancel.get())
        assertTrue(awaitCondition { f.host.isHumanInputAllowed() })
        assertEquals(AgentRunState.WAITING_HUMAN, f.host.getRunState()); assertEquals(f.rome, f.host.getActiveCivId())
        assertNull("A cancelled run's session must not survive the takeover", f.sessions()[f.rome])
        assertEquals(1, f.calls.get()); assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        assertNull(f.host.getFailureReason())
    }

    @Test fun `spectate applies mid-run without touching the admitted provider`() {
        val f = Fixture(wrongViewer = true)
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val epoch = f.epoch()
        f.request(change = NationControlChange.SPECTATE); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNull(f.error); assertEquals(f.spectator, f.host.coordinator.getLocalViewingCivId())
        assertEquals(epoch, f.epoch()); assertTrue(f.cancels.isEmpty())
        assertEquals(AgentRunState.AGENT_DECIDING, f.host.getRunState())
        f.host.pause(); f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        assertTrue(awaitCondition { f.host.getRunState() == AgentRunState.PAUSED })
        assertEquals("resume-rome", f.sessions()[f.rome]); assertEquals(1, f.calls.get())
    }

    @Test fun `handing the active human turn to an agent keeps play going`() {
        val f = Fixture(human = true)
        f.host.startAutomaticPlay(); assertEquals(AgentRunState.WAITING_HUMAN, f.host.getRunState())
        f.request(f.rome, NationControlChange.HAND_TO_AGENT, AgentProfile.defaultFor("Rome"))
        assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.rome)!!.kind)
        assertTrue("The agent picks up the rest of the turn without Resume", f.started.await(5, TimeUnit.SECONDS))
        assertEquals(1, f.calls.get())
    }

    @Test fun `takeover during supervisor setup never admits the provider`() {
        val f = Fixture(stallSupervisor = true)
        f.host.startAutomaticPlay(); assertTrue(f.supervisorEntered.await(5, TimeUnit.SECONDS))
        f.request(f.rome); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNull(f.error); assertEquals(0, f.calls.get())
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.rome)!!.kind)
        f.releaseSupervisor.countDown()
        assertTrue(awaitCondition { f.host.getRunState() == AgentRunState.WAITING_HUMAN })
        assertEquals(0, f.calls.get()); assertEquals(0, f.kills.get()); assertTrue(f.cancels.isEmpty())
        assertEquals(1, f.callbacks.get())
    }

    @Test fun `queued GL install failure reports saved change and Retry installs without provider dispatch`() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        Mockito.doAnswer { invocation -> queued.add(invocation.getArgument(0)); null }
            .`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
        fun drainGl(): Throwable? {
            var thrown: Throwable? = null
            while (true) {
                val runnable = queued.poll() ?: break
                try { runnable.run() } catch (error: Throwable) { thrown = error }
            }
            return thrown
        }
        val f = Fixture(attachScreen = true)
        f.installFailure = "injected queued install failure"
        val epoch = f.epoch(); f.sessions()[f.rome] = "old-rome"
        f.request(f.rome)
        assertTrue(awaitCondition { f.host.coordinator.getController(f.rome)!!.kind == ControllerKind.HUMAN && queued.size >= 2 })
        assertEquals(0, f.callbacks.get()); assertEquals(epoch + 1, f.epoch()); assertNull(f.sessions()[f.rome])
        val resumableBeforeInstall = f.host.canResume()
        val globalFailure = drainGl()
        assertNull("Install failure must stay in the host, not reach global crash handling", globalFailure)
        assertFalse("Scheduling waits for GL installation", resumableBeforeInstall)
        assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertTrue(f.error, f.error?.contains("was saved") == true)
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState()); assertEquals(1, f.callbacks.get())
        f.installFailure = null; f.host.retry()
        assertFalse(f.host.canResume()); assertNull(drainGl())
        assertEquals(AgentRunState.PAUSED, f.host.getRunState()); assertEquals(listOf(f.rome), f.publishedViewers)
        assertEquals(2, f.acceptedGenerations.size)
        assertTrue("Retry must pass a fence that accepted the failed frame", f.acceptedGenerations[1] > f.acceptedGenerations[0])
        assertEquals(0, f.calls.get()); assertEquals(1, f.callbacks.get())
        f.renderedViewer.set(f.rome); assertTrue(f.host.isHumanInputAllowed())
        // A queued committed view must never install into a screen after its host closes.
        val closed = Fixture(attachScreen = true)
        closed.request(closed.rome)
        assertTrue(awaitCondition { closed.host.coordinator.getController(closed.rome)!!.kind == ControllerKind.HUMAN && queued.size >= 2 })
        closed.host.close(); assertNull(drainGl())
        assertEquals(0, closed.installAttempts.get()); assertEquals(1, closed.callbacks.get()); assertNotNull(closed.error)
    }

    @Test fun `screen entry during failed provider publication drains once and requires view retry`() {
        val f = Fixture(attachScreen = true)
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        f.installFailure = "injected publication error"
        // Reproduce the native navigation callback while the failed run still owns the loop.
        DesktopAgentMatch::class.java.getDeclaredMethod("markViewRefreshFailedLocked", String::class.java)
            .apply { isAccessible = true }.invoke(f.host, "injected publication error")
        assertEquals(AgentRunState.PAUSING, f.host.getRunState())
        f.host.onMatchEntered()
        assertEquals(AgentRunState.PAUSING, f.host.getRunState())
        f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        assertTrue(awaitCondition { f.host.canRetry() })
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        assertEquals(1, f.calls.get()); assertEquals(0, f.kills.get())
        f.installFailure = null; f.host.retry()
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        assertEquals(1, f.calls.get())
    }

    @Test fun `leaving before idle nation worker dispatch returns to a resumable pause`() {
        val f = Fixture(human = true)
        // Holding the host monitor deterministically defers the worker until leave cancels it.
        synchronized(f.lock()) {
            f.request()
            assertEquals("A nation change never pauses play", AgentRunState.PAUSED, f.host.getRunState())
            f.host.onMatchLeft()
            assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        }
        assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNotNull(f.error)
        f.host.onMatchEntered()
        assertTrue(f.host.isHumanInputAllowed())
        assertTrue(f.host.canResume())
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
        assertEquals(1, f.callbacks.get())
    }

    @Test fun `selected but not admitted turn applies a pending takeover before admission`() {
        val f = Fixture()
        // Holding the host monitor keeps the request pending until dispatch reaches its reservation.
        synchronized(f.lock()) {
            DesktopAgentMatch::class.java.getDeclaredField("runState").apply { isAccessible = true }.set(f.host, AgentRunState.STARTING)
            f.request(f.rome)
            assertNotNull(f.host.getPendingNationChange())
            DesktopAgentMatch::class.java.getDeclaredMethod("runAgentTurn", String::class.java).apply { isAccessible = true }.invoke(f.host, f.rome)
        }
        assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        assertEquals(0, f.calls.get()); assertEquals(1, f.callbacks.get())
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.rome)!!.kind)
    }

    @Test fun `leaving and closing cancel pending request without interrupting provider`() {
        for (close in listOf(false, true)) {
            val f = Fixture(); f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
            synchronized(f.lock()) {
                f.request()
                if (close) f.host.close() else f.host.onMatchLeft()
            }
            assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNotNull(f.error)
            assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get()); assertTrue(f.cancels.isEmpty())
            f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
            assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
            assertEquals(1, f.callbacks.get())
        }
    }

    @Test fun `settings saved mid-run apply at once and the running turn keeps its old session out`() {
        val f = Fixture(failProvider = true)
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val epoch = f.epoch()
        val profile = f.host.getNationSettings().first { it.civId == f.rome }.profile!!.apply {
            displayName = "Replacement strategist"; personality = "Preserve the partial move"
        }
        f.request(f.rome, NationControlChange.SAVE_AGENT_SETTINGS, profile)
        assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        assertEquals("Replacement strategist", f.host.coordinator.getController(f.rome)!!.agentProfile!!.displayName)
        assertEquals("Settings never cut off the running turn", epoch, f.epoch()); assertTrue(f.cancels.isEmpty())
        f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        assertTrue(awaitCondition { f.host.canRetry() })
        assertEquals(1, f.callbacks.get()); assertEquals(1, f.calls.get()); assertEquals(0, f.kills.get())
        assertEquals(HexCoord(1, 0), f.host.coordinator.getUnitPosition(f.rome, f.romeUnit))
        assertNull("The old profile's session must not resume under the new settings", f.sessions()[f.rome])
    }

    @Test fun `committed viewer change allows no input until actual rendered view installs`() {
        val f = Fixture(attachScreen = true)
        f.request(f.rome); assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        assertEquals(f.rome, f.host.coordinator.getLocalViewingCivId())
        assertEquals(f.spectator, f.renderedViewer.get())
        assertFalse(f.host.isHumanInputAllowed()); assertTrue(f.host.isSpectatorMode())
        f.renderedViewer.set(f.rome)
        assertTrue(f.host.isHumanInputAllowed()); assertFalse(f.host.isSpectatorMode())
    }

    @Test fun `detached host cannot accept a request from its former screen`() {
        val f = Fixture(attachScreen = true)
        Mockito.`when`(f.screen!!.agentSession).thenReturn(null)
        f.request(); assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNotNull(f.error)
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
    }

    @Test fun `durable controller commit revokes target session even if view publication fails`() {
        val f = Fixture(attachScreen = true); val epoch = f.epoch()
        f.sessions()[f.rome] = "old-rome"
        Mockito.`when`(f.screen!!.currentSelectedCityId()).thenThrow(IllegalStateException("injected view publication failure"))
        f.request(f.rome); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.rome)!!.kind)
        assertEquals(f.rome, f.host.coordinator.getLocalViewingCivId())
        assertEquals(epoch + 1, f.epoch()); assertNull(f.sessions()[f.rome])
        assertTrue(f.error, f.error?.contains("was saved") == true)
        assertEquals(1, f.callbacks.get()); assertNull(f.host.getPendingNationChange())
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        f.host.resume(); f.host.startAutomaticPlay()
        assertEquals(AgentRunState.NEEDS_ATTENTION, f.host.getRunState())
        val revision = f.host.coordinator.getStateRevision()
        Mockito.doReturn(null).`when`(f.screen).currentSelectedCityId()
        f.host.retry()
        assertEquals(listOf(f.rome), f.publishedViewers)
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        assertEquals(revision, f.host.coordinator.getStateRevision())
        assertEquals(1, f.callbacks.get()); assertEquals(0, f.calls.get())
        f.renderedViewer.set(f.rome); assertTrue(f.host.isHumanInputAllowed())
    }

    @Test fun `old local hotseat restore grants only the active human local authority`() {
        val f = Fixture(human = true, attachScreen = true, includeSpectator = false)
        val game = f.host.coordinator.deserializeForPublication(f.host.coordinator.serializeCommittedGameForPublication())
        val match = json().fromJson(AgentMatchState::class.java, f.host.coordinator.serializeCommittedMatchForPublication()).apply {
            localControlCivId = null; localViewingCivId = null
            controllers[f.greece] = CivilizationController(f.greece, ControllerKind.HUMAN)
        }
        val restored = DesktopAgentMatch.restore(game, match, Files.createTempDirectory("unciv-ambiguous-restore-").toFile(),
            worldScreen = f.screen, supervisorFactory = { error("Restoration must not launch providers") })
        Mockito.`when`(f.screen!!.agentSession).thenReturn(restored)
        try {
            assertEquals(listOf(f.rome), restored.getNationSettings().map { it.civId })
            assertEquals(f.rome, restored.coordinator.getLocalControlCivId())
            assertTrue("Legacy actual matching Human view remains playable", restored.isHumanInputAllowed())
            assertEquals(AgentRunState.PAUSED, restored.getRunState())
        } finally { restored.close() }
    }

    @Test fun `failed checkpoint retains machine sessions authority and controller`() {
        val f = Fixture(); val writer = InMemoryCheckpointWriter().apply { failNextWrite = true }
        MatchCoordinator::class.java.getDeclaredField("writer").apply { isAccessible = true }.set(f.host.coordinator, writer)
        f.sessions()[f.greece] = "old-greece"; val epoch = f.epoch()
        f.request(); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNotNull(f.error); assertEquals(epoch, f.epoch())
        assertEquals("old-greece", f.sessions()[f.greece])
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
        assertTrue(f.host.coordinator.isPaused())
    }

    @Test fun `wrong saved viewer cannot mutate or end another human turn`() {
        val f = Fixture(human = true, wrongViewer = true)
        assertFalse(f.host.isHumanInputAllowed()); assertFalse(f.host.onNextTurnPressed())
        val revision = f.host.coordinator.getStateRevision()
        assertNotNull(f.host.executeHumanAction { listOf({ rev -> SelectResearchCommand(f.rome, "foreign-action", rev, "Pottery", false, false) }) })
        assertEquals(revision, f.host.coordinator.getStateRevision())
    }

    @Test fun `foreign command factory cannot use host human authorization for metadata`() {
        val f = Fixture(human = true); val revision = f.host.coordinator.getStateRevision()
        assertNotNull(f.host.executeHumanAction { listOf({ rev -> SetControllerCommand(f.greece, "foreign-controller", rev,
            f.greece, ControllerKind.HUMAN, null) }) })
        assertEquals(revision, f.host.coordinator.getStateRevision())
        assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
    }
}
