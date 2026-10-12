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
    private inner class Fixture(human: Boolean = false, wrongViewer: Boolean = false, failProvider: Boolean = false, attachScreen: Boolean = false, includeSpectator: Boolean = true) {
        lateinit var host: DesktopAgentMatch
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val kills = AtomicInteger()
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
                    publishedViewers.add(invocation.getArgument(3)); null
                } else Answers.RETURNS_DEFAULTS.answer(invocation)
            }.also {
                Mockito.`when`(it.currentViewerCivId()).thenAnswer { renderedViewer.get() }
            } else null
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-nation-boundary-").toFile(), worldScreen = screen, supervisorFactory = {
                    object : SupervisorRunner {
                        override fun isAlive() = true
                        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                            calls.incrementAndGet(); started.countDown()
                            try {
                                check(release.await(10, TimeUnit.SECONDS))
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
        fixtures.forEach { it.release.countDown(); it.host.pause(); it.host.close() }
        fixtures.forEach { f -> if (f.started.count == 0L) assertTrue(f.returned.await(5, TimeUnit.SECONDS)) }
        Gdx.app = previousApp; Gdx.graphics = previousGraphics
    }
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test fun `admitted provider drains with unchanged authority before exactly one durable change`() {
        val f = Fixture(); f.sessions()[f.greece] = "old-greece"; f.sessions()[f.rome] = "old-rome"
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val revision = f.host.coordinator.getStateRevision(); val epoch = f.epoch()
        f.request()
        assertNotNull(f.host.getPendingNationChange())
        assertEquals(AgentRunState.PAUSING, f.host.getRunState())
        assertEquals(revision, f.host.coordinator.getStateRevision())
        assertEquals(epoch, f.epoch()); assertEquals("old-greece", f.sessions()[f.greece])
        assertEquals(0, f.callbacks.get()); assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        f.host.resume(); assertFalse(f.host.canResume())
        f.release.countDown(); assertTrue(f.completed.await(5, TimeUnit.SECONDS))
        assertNull(f.error); assertEquals(1, f.callbacks.get()); assertEquals(1, f.calls.get())
        assertEquals(ControllerKind.HUMAN, f.host.coordinator.getController(f.greece)!!.kind)
        assertEquals(AgentRunState.PAUSED, f.host.getRunState()); assertNull(f.host.getPendingNationChange())
        assertEquals(epoch + 1, f.epoch()); assertNull(f.sessions()[f.greece]); assertEquals("resume-rome", f.sessions()[f.rome])
        assertEquals(0, f.kills.get())
    }

    @Test fun `selected but not admitted turn is rejected after a nation request`() {
        val f = Fixture()
        DesktopAgentMatch::class.java.getDeclaredField("runState").apply { isAccessible = true }.set(f.host, AgentRunState.STARTING)
        f.request(); assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        DesktopAgentMatch::class.java.getDeclaredMethod("runAgentTurn", String::class.java).apply { isAccessible = true }.invoke(f.host, f.rome)
        assertEquals(0, f.calls.get()); assertEquals(AgentRunState.PAUSED, f.host.getRunState())
    }

    @Test fun `leaving and closing cancel pending request without interrupting provider`() {
        for (close in listOf(false, true)) {
            val f = Fixture(); f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
            f.request()
            if (close) f.host.close() else f.host.onMatchLeft()
            assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNotNull(f.error)
            assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
            f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
            assertEquals(ControllerKind.EXTERNAL_AGENT, f.host.coordinator.getController(f.greece)!!.kind)
            assertEquals(1, f.callbacks.get())
        }
    }

    @Test fun `failed provider leaves request blocked until retry applies it without redispatch`() {
        val f = Fixture(failProvider = true)
        f.host.startAutomaticPlay(); assertTrue(f.started.await(5, TimeUnit.SECONDS))
        val profile = f.host.getNationSettings().first { it.civId == f.rome }.profile!!.apply {
            displayName = "Replacement strategist"; personality = "Preserve the partial move"
        }
        f.request(f.rome, NationControlChange.SAVE_AGENT_SETTINGS, profile)
        f.release.countDown(); assertTrue(f.returned.await(5, TimeUnit.SECONDS))
        assertTrue(awaitCondition { f.host.canRetry() && f.host.getPendingNationChange()?.startsWith("Blocked:") == true })
        assertEquals(0, f.callbacks.get()); assertEquals(1, f.calls.get())
        assertEquals(HexCoord(1, 0), f.host.coordinator.getUnitPosition(f.rome, f.romeUnit))
        f.host.retry(); assertTrue(f.completed.await(5, TimeUnit.SECONDS)); assertNull(f.error)
        assertEquals(1, f.callbacks.get()); assertEquals(1, f.calls.get()); assertEquals(0, f.kills.get())
        assertEquals("Replacement strategist", f.host.coordinator.getController(f.rome)!!.agentProfile!!.displayName)
        assertEquals(HexCoord(1, 0), f.host.coordinator.getUnitPosition(f.rome, f.romeUnit))
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
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

    @Test fun `ambiguous old restore cannot infer local authority from current displayed human`() {
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
            assertTrue(restored.getNationSettings().isEmpty())
            assertNull(restored.coordinator.getLocalControlCivId())
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
