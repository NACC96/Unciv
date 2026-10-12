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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Scripted runners and injected clocks only; no provider process or installed app. */
@RunWith(BaseTestRunner::class)
class DesktopAgentRunTimingTest {
    private val fixtures = ArrayList<Fixture>()
    private var priorApp: Application? = null
    private var priorGraphics: Graphics? = null

    @Before fun presentation() {
        priorApp = Gdx.app; priorGraphics = Gdx.graphics
        Gdx.app = Mockito.mock(Application::class.java)
        Gdx.graphics = Mockito.mock(Graphics::class.java)
        Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }
            .`when`(Gdx.app).postRunnable(Mockito.any(Runnable::class.java))
    }

    private inner class Fixture(val outcome: String = "completed_turn", val throws: Boolean = false) {
        val clock = AtomicLong(5_000_000_000L)
        val starts = List(2) { CountDownLatch(1) }
        val releases = List(2) { CountDownLatch(1) }
        val returns = List(2) { CountDownLatch(1) }
        val calls = AtomicInteger()
        val kills = AtomicInteger()
        val shutdowns = AtomicInteger()
        val viewer = AtomicReference(Constants.spectator)
        val screen = Mockito.mock(WorldScreen::class.java)
        val host: DesktopAgentMatch

        init {
            val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!, isPlayer = true)
            val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
            viewer.set(spectator.civID)
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(2, 0))
            game.gameInfo.currentPlayer = rome.civID; game.gameInfo.currentPlayerCiv = rome
            Mockito.`when`(screen.currentViewerCivId()).thenAnswer { viewer.get() }
            val match = AgentMatchState(matchId = "timing-fixture", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS, localViewingCivId = spectator.civID)
            for (civ in listOf(rome, greece)) match.controllers[civ.civID] = CivilizationController(
                civ.civID, ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor(civ.civID))
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-timing-").toFile(), worldScreen = screen,
                supervisorFactory = {
                    // Supervisor preparation is outside the provider timing interval.
                    clock.addAndGet(10_000_000_000L)
                    object : SupervisorRunner {
                        override fun isAlive() = true
                        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                            val index = calls.getAndIncrement()
                            starts[index].countDown()
                            try {
                                check(releases[index].await(10, TimeUnit.SECONDS))
                                if (throws) throw IllegalStateException("scripted invocation error")
                                return SupervisorProcess.TurnOutcome(outcome, null, null, outcome == "completed_turn",
                                    emptyList(), "scripted $outcome")
                            } finally { returns[index].countDown() }
                        }
                        override fun kill() { kills.incrementAndGet() }
                        override fun shutdown() { shutdowns.incrementAndGet() }
                    }
                }, clockNanos = { clock.get() })
            Mockito.`when`(screen.agentSession).thenReturn(host)
            fixtures.add(this)
        }
        fun start() { host.startAutomaticPlay(); assertTrue(starts[0].await(5, TimeUnit.SECONDS)) }
        fun finish(index: Int = 0) { releases[index].countDown(); assertTrue(returns[index].await(5, TimeUnit.SECONDS)) }
    }

    @After fun drain() {
        fixtures.forEach { it.host.pause(); it.releases.forEach(CountDownLatch::countDown); it.host.close() }
        fixtures.forEach { f -> f.starts.indices.filter { f.starts[it].count == 0L }.forEach { assertTrue(f.returns[it].await(5, TimeUnit.SECONDS)) } }
        fixtures.forEach { f -> assertTrue(await {
            !(DesktopAgentMatch::class.java.getDeclaredField("isLoopRunning").apply { isAccessible = true }
                .get(f.host) as java.util.concurrent.atomic.AtomicBoolean).get()
        }) }
        Gdx.app = priorApp; Gdx.graphics = priorGraphics
    }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { if (condition()) return true; Thread.sleep(5) }
        return condition()
    }

    @Test fun `admission starts at zero and pause keeps elapsed running until natural completion`() {
        val f = Fixture()
        assertNull(f.host.getActiveRunTiming())
        f.start()
        val initial = f.host.getActiveRunTiming()!!
        assertEquals("Rome", initial.civId); assertEquals(0L, initial.elapsedMillis)
        val savedMetadata = f.host.getCommittedMatchStateJson()
        assertFalse(savedMetadata.contains(initial.runId))
        assertFalse(savedMetadata.contains("elapsedMillis"))
        f.clock.addAndGet(2_000_000_000L)
        f.host.pause()
        assertEquals(AgentRunState.PAUSING, f.host.getRunState())
        f.clock.addAndGet(3_000_000_000L)
        assertEquals(initial.copy(elapsedMillis = 5_000), f.host.getActiveRunTiming())
        assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        f.finish()
        assertTrue(await { f.host.getRunState() == AgentRunState.PAUSED })
        assertNull(f.host.getActiveRunTiming()); assertEquals(1, f.calls.get())
    }

    @Test fun `failed exit clears timing and retry starts a fresh run identity and interval`() {
        val f = Fixture(outcome = "failed")
        f.start()
        val first = f.host.getActiveRunTiming()!!
        f.clock.addAndGet(65_000_000_000L)
        f.finish()
        assertTrue(await { f.host.canRetry() })
        assertNull(f.host.getActiveRunTiming())
        f.clock.addAndGet(99_000_000_000L)
        f.host.retry()
        assertTrue(f.starts[1].await(5, TimeUnit.SECONDS))
        val retry = f.host.getActiveRunTiming()!!
        assertNotEquals(first.runId, retry.runId); assertEquals(0L, retry.elapsedMillis)
        f.host.pause(); f.finish(1)
        assertTrue(await { f.host.getActiveRunTiming() == null })
        assertEquals(0, f.kills.get())
    }

    @Test fun `throwing invocation clears timing through finally`() {
        val f = Fixture(throws = true)
        f.start(); assertNotNull(f.host.getActiveRunTiming())
        f.finish()
        assertTrue(await { f.host.canRetry() })
        assertNull(f.host.getActiveRunTiming())
    }

    @Test fun `timing remains bound to admitted civilization after end turn advances current civilization`() {
        val f = Fixture()
        f.start()
        val admitted = f.host.getActiveRunTiming()!!
        f.host.answerEngineRequest("end_turn", mapOf("civId" to "Rome", "actionId" to "timing-end-turn"))
        assertNotEquals("Rome", f.host.getActiveCivId())
        f.clock.addAndGet(2_000_000_000L)
        assertEquals(admitted.copy(elapsedMillis = 2_000), f.host.getActiveRunTiming())
        f.host.pause(); f.finish()
    }

    @Test fun `timer privacy follows actual rendered viewer instead of committed spectator metadata`() {
        val f = Fixture()
        f.start(); assertNotNull(f.host.getActiveRunTiming())
        f.viewer.set("Greece"); assertNull(f.host.getActiveRunTiming())
        f.viewer.set("Rome"); assertNotNull(f.host.getActiveRunTiming())
        Mockito.`when`(f.screen.agentSession).thenReturn(null)
        assertNull(f.host.getActiveRunTiming())
        f.host.pause(); f.finish()
    }

    @Test fun `leaving hides timing while the admitted invocation keeps draining and reopening preserves it`() {
        val f = Fixture()
        f.start()
        val run = f.host.getActiveRunTiming()!!
        f.host.onMatchLeft()
        f.clock.addAndGet(9_000_000_000L)
        assertNull(f.host.getActiveRunTiming())
        assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        f.host.onMatchEntered()
        assertEquals(run.copy(elapsedMillis = 9_000), f.host.getActiveRunTiming())
        f.host.close()
        assertNull(f.host.getActiveRunTiming())
        assertEquals(0, f.kills.get()); assertEquals(0, f.shutdowns.get())
        f.finish()
    }

    @Test fun `selected turn paused before admission has no timing`() {
        val f = Fixture()
        DesktopAgentMatch::class.java.getDeclaredField("runState").apply { isAccessible = true }
            .set(f.host, AgentRunState.STARTING)
        f.host.pause()
        DesktopAgentMatch::class.java.getDeclaredMethod("runAgentTurn", String::class.java)
            .apply { isAccessible = true }.invoke(f.host, "Rome")
        assertEquals(0, f.calls.get()); assertNull(f.host.getActiveRunTiming())
    }
}
