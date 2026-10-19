package com.unciv.logic.agents

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(BaseTestRunner::class)
class AgentOrchestrationLoopTest {
    private val matches = ArrayList<Pair<DesktopAgentMatch, TestSupervisor>>()
    private val testExecutors = ArrayList<ExecutorService>()

    @After fun drainScriptedMatchesBeforeTheNextFixture() {
        for (executor in testExecutors) {
            executor.shutdown()
            Assert.assertTrue("All test resume requests must finish before draining the match",
                executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        for ((host, supervisor) in matches) {
            host.pause()
            Assert.assertTrue("Every scripted invocation must finish before the next test replaces global game state",
                awaitCondition(10000) {
                    supervisor.activeInvocations.get() == 0 && host.getRunState() in setOf(
                        AgentRunState.PAUSED, AgentRunState.WAITING_HUMAN,
                        AgentRunState.NEEDS_ATTENTION, AgentRunState.FINISHED)
                })
            host.close()
        }
    }

    private class TestSupervisor(
        private val answerEngineRequest: (String, Map<String, Any?>) -> Any?
    ) : SupervisorRunner {
        val activeInvocations = AtomicInteger(0)
        val maxConcurrentInvocations = AtomicInteger(0)
        val completedInvocations = CopyOnWriteArrayList<String>()
        val allTurnInvocations = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<Map<String, Any?>>()

        @Volatile
        var onTurnInvoked: ((civId: String, runner: TestSupervisor) -> Unit)? = null

        @Volatile
        var onTurnCompleted: ((civId: String) -> Unit)? = null

        @Volatile
        var turnAction: ((civId: String, runner: TestSupervisor) -> SupervisorProcess.TurnOutcome?)? = null

        override fun isAlive(): Boolean = true

        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
            @Suppress("UNCHECKED_CAST")
            val binding = control["binding"] as Map<String, Any?>
            val civId = binding["civId"] as String
            allTurnInvocations.add(civId)
            controls.add(control)

            val current = activeInvocations.incrementAndGet()
            maxConcurrentInvocations.updateAndGet { maxOf(it, current) }
            try {
                onTurnInvoked?.invoke(civId, this)
                val custom = turnAction?.invoke(civId, this)
                if (custom != null) return custom

                // Default simulated agent action: observe then end turn
                answerEngineRequest("observe", mapOf("civId" to civId))
                val actionId = "turn-end-${UUID.randomUUID()}"
                answerEngineRequest("end_turn", mapOf("civId" to civId, "actionId" to actionId))

                completedInvocations.add(civId)
                onTurnCompleted?.invoke(civId)
                return SupervisorProcess.TurnOutcome(
                    status = "completed_turn",
                    sessionId = "session-$civId",
                    revision = null,
                    turnEnded = true,
                    toolCalls = listOf("observe", "end_turn"),
                    detail = "completed",
                    summary = "Closing summary ${completedInvocations.size} for $civId."
                )
            } finally {
                activeInvocations.decrementAndGet()
            }
        }

        override fun shutdown() {}
        override fun kill() {}
    }

    private inner class MatchFixture(
        val playerConfigs: List<Pair<String, ControllerKind>>
    ) {
        val game: GameInfo
        val setup: AgentMatchSetup
        val match: AgentMatchState
        val tempDir: File = Files.createTempDirectory("agent-orchestration-test-").toFile()
        lateinit var romeId: String
        lateinit var greeceId: String

        init {
            tempDir.deleteOnExit()
            val parameters = GameParameters().apply {
                baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
                espionageEnabled = false
                numberOfCityStates = 0
                noBarbarians = true
                randomNumberOfPlayers = false
                difficulty = "Prince"
                speed = "Quick"
                victoryTypes = ArrayList(
                    RulesetCache.loadRulesets(noMods = true).let {
                        RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName]!!.victories.keys
                    }
                )
                players.clear()
                for ((civ, _) in playerConfigs) {
                    players.add(Player(civ, PlayerType.Human))
                }
            }

            UncivGame.Current = UncivGame()
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.settings = GameSettings()
            val mapParameters = MapParameters().apply {
                mapSize = MapSize(MapSize.Predefined.Tiny)
                seed = 42L
            }
            game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
            UncivGame.Current.gameInfo = game

            setup = AgentMatchSetup()
            for ((civ, kind) in playerConfigs) {
                if (kind == ControllerKind.EXTERNAL_AGENT) {
                    setup.slots[civ] = AgentSlotConfig(
                        civ,
                        AgentProfile("prof-$civ", "Agent-$civ", "scripted-$civ", "fake-model", null, "", 1)
                    )
                }
            }
            match = setup.buildMatchState(game)
            romeId = game.civilizations.first { it.nation.name == "Rome" }.civID
            greeceId = game.civilizations.first { it.nation.name == "Greece" }.civID
        }

        fun createMatch(supervisor: TestSupervisor): DesktopAgentMatch {
            return DesktopAgentMatch(
                game = game,
                match = match,
                setup = setup,
                dataDir = tempDir,
                supervisorFactory = { supervisor }
            ).also { matches.add(it to supervisor) }
        }
    }

    @Test
    fun `two agent civilizations sequentially alternate turns across multiple rounds`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        // We want to observe 4 completed turns (2 rounds: Rome, Greece, Rome, Greece).
        // The latch counts COMPLETIONS, not invocations: asserting order on a
        // started-but-unfinished 4th turn would race.
        val turnLatch = CountDownLatch(4)
        supervisor.onTurnCompleted = { _ ->
            turnLatch.countDown()
        }

        val initialTurn = desktopMatch.coordinator.getGameTurns()
        desktopMatch.startAutomaticPlay()

        val completed = turnLatch.await(10, TimeUnit.SECONDS)
        Assert.assertTrue("Expected 4 turns to complete within timeout", completed)

        // Pause to inspect settled state
        desktopMatch.pause()

        Assert.assertEquals(
            "Turns should rotate sequentially Rome -> Greece -> Rome -> Greece",
            listOf(fixture.romeId, fixture.greeceId, fixture.romeId, fixture.greeceId),
            supervisor.completedInvocations.take(4)
        )
        Assert.assertEquals(1, supervisor.maxConcurrentInvocations.get())
        Assert.assertTrue(
            "Game turn counter should have advanced across rounds",
            desktopMatch.coordinator.getGameTurns() >= initialTurn + 2
        )
    }

    @Test
    fun `sessions resume by default and compact after every turn`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)
        val turnLatch = CountDownLatch(4)
        supervisor.onTurnCompleted = { _ -> turnLatch.countDown() }

        desktopMatch.startAutomaticPlay()
        Assert.assertTrue("Expected 4 turns to complete within timeout", turnLatch.await(10, TimeUnit.SECONDS))
        desktopMatch.pause()

        @Suppress("UNCHECKED_CAST")
        fun turn(index: Int): Triple<Map<String, Any?>, Map<String, Any?>, Map<String, Any?>> = supervisor.controls[index].let {
            val context = it["context"] as Map<String, Any?>
            Triple(it["providerConfig"] as Map<String, Any?>, context, context["session"] as Map<String, Any?>)
        }
        for (index in 0..1) {
            val (config, _, session) = turn(index)
            Assert.assertNull(config["resumeId"])
            Assert.assertEquals(true, config["compactAfterTurn"])
            Assert.assertEquals(mapOf("arm" to AgentSessionPolicy.ARM_COMPACT, "age" to 0), session)
        }
        for ((index, civ) in listOf(2 to fixture.romeId, 3 to fixture.greeceId)) {
            val (config, context, session) = turn(index)
            Assert.assertEquals("session-$civ", config["resumeId"])
            Assert.assertEquals(true, config["compactAfterTurn"])
            Assert.assertNull("A resumed session already holds its own summary", context["continuityNote"])
            Assert.assertEquals(mapOf("arm" to AgentSessionPolicy.ARM_COMPACT, "age" to 1), session)
        }
    }

    @Test
    fun `session rotation starts alternate civs fresh with their closing summary and resumes the others`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        File(fixture.tempDir, ".agent-data/${AgentSessionPolicy.SETTINGS_FILE}").apply {
            parentFile.mkdirs()
            writeText("""{"sessionRotationTurns":1,"sessionAbTest":true}""")
        }
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)
        val turnLatch = CountDownLatch(4)
        supervisor.onTurnCompleted = { _ -> turnLatch.countDown() }

        desktopMatch.startAutomaticPlay()
        Assert.assertTrue("Expected 4 turns to complete within timeout", turnLatch.await(10, TimeUnit.SECONDS))
        desktopMatch.pause()

        @Suppress("UNCHECKED_CAST")
        fun turn(index: Int): Triple<Any?, Map<String, Any?>, Map<String, Any?>> = supervisor.controls[index].let {
            val context = it["context"] as Map<String, Any?>
            Triple((it["providerConfig"] as Map<String, Any?>)["resumeId"], context, context["session"] as Map<String, Any?>)
        }
        val (romeFirstResume, romeFirstContext, romeFirstSession) = turn(0)
        Assert.assertNull(romeFirstResume)
        Assert.assertNull(romeFirstContext["continuityNote"])
        Assert.assertEquals(mapOf("arm" to "rotate-1", "age" to 0), romeFirstSession)

        val (greeceFirstResume, _, greeceFirstSession) = turn(1)
        Assert.assertNull(greeceFirstResume)
        Assert.assertEquals(mapOf("arm" to AgentSessionPolicy.ARM_COMPACT, "age" to 0), greeceFirstSession)

        val (romeSecondResume, romeSecondContext, romeSecondSession) = turn(2)
        Assert.assertNull("The rotating arm starts a fresh session every turn", romeSecondResume)
        Assert.assertEquals("Your closing summary from your previous turn: Closing summary 1 for ${fixture.romeId}.",
            romeSecondContext["continuityNote"])
        Assert.assertEquals(mapOf("arm" to "rotate-1", "age" to 0), romeSecondSession)

        val (greeceSecondResume, greeceSecondContext, greeceSecondSession) = turn(3)
        Assert.assertEquals("session-${fixture.greeceId}", greeceSecondResume)
        Assert.assertNull("A resumed session already holds its own summary", greeceSecondContext["continuityNote"])
        Assert.assertEquals(mapOf("arm" to AgentSessionPolicy.ARM_COMPACT, "age" to 1), greeceSecondSession)
    }

    @Test
    fun `at most one provider invocation is active at any time`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        val inFlightActiveCount = AtomicInteger(0)
        val maxSimultaneous = AtomicInteger(0)
        val turnsDone = CountDownLatch(6)

        supervisor.onTurnInvoked = { _, s ->
            val cur = inFlightActiveCount.incrementAndGet()
            maxSimultaneous.updateAndGet { maxOf(it, cur) }
            // Verify inside invocation callback that no other invocation is running
            Assert.assertEquals("Active invocations must be exactly 1", 1, s.activeInvocations.get())
            inFlightActiveCount.decrementAndGet()
            turnsDone.countDown()
        }

        desktopMatch.startAutomaticPlay()
        val finished = turnsDone.await(10, TimeUnit.SECONDS)
        Assert.assertTrue("Turns should progress", finished)

        desktopMatch.pause()
        Assert.assertEquals(1, supervisor.maxConcurrentInvocations.get())
        Assert.assertEquals(1, maxSimultaneous.get())
    }

    @Test
    fun `pause during active invocation waits for turn to drain and halts next invocation`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        val turnStarted = CountDownLatch(1)
        val allowTurnFinish = CountDownLatch(1)
        val turnCompleted = CountDownLatch(1)

        supervisor.onTurnInvoked = { civId, _ ->
            if (civId == fixture.romeId && turnStarted.count > 0) {
                turnStarted.countDown()
                // Wait for pause to be initiated before finishing this turn
                allowTurnFinish.await(5, TimeUnit.SECONDS)
            }
        }

        desktopMatch.startAutomaticPlay()

        // Wait until Rome's turn has begun executing inside the supervisor
        val started = turnStarted.await(5, TimeUnit.SECONDS)
        Assert.assertTrue("Rome turn should have started", started)

        // Request pause while Rome's turn is active
        desktopMatch.pause()

        // Match should reflect pausing intent while invocation is active
        Assert.assertEquals(AgentRunState.PAUSING, desktopMatch.getRunState())
        Assert.assertFalse("canPause should be false while pausing", desktopMatch.canPause())
        Assert.assertEquals("Only Rome in progress", 1, supervisor.activeInvocations.get())
        Assert.assertEquals("Greece must not have started", 0, supervisor.completedInvocations.size)

        // Now allow Rome's turn to finish
        allowTurnFinish.countDown()

        // Await settled state using explicit state polling with timeout
        val settled = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.PAUSED
        }
        Assert.assertTrue("Match must settle to PAUSED after in-flight turn finishes", settled)

        // Verify Rome completed, but Greece was NOT started
        Assert.assertEquals(listOf(fixture.romeId), supervisor.completedInvocations)
        Assert.assertEquals(0, supervisor.activeInvocations.get())
        Assert.assertEquals(fixture.greeceId, desktopMatch.getActiveCivId())
        Assert.assertTrue(desktopMatch.canResume())
    }

    @Test
    fun `resume continues from paused boundary without duplicate actions`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        val romeFinished = CountDownLatch(1)
        val allowRomeToFinish = CountDownLatch(1)
        val romeStarted = CountDownLatch(1)
        supervisor.onTurnInvoked = { civId, _ ->
            if (civId == fixture.romeId) {
                romeStarted.countDown()
                allowRomeToFinish.await(5, TimeUnit.SECONDS)
            }
        }

        desktopMatch.startAutomaticPlay()
        // Gate the pause on Rome's turn actually starting: pausing before the
        // loop thread reaches the first invocation would settle PAUSED with
        // zero turns run, which is correct behavior but not this test.
        Assert.assertTrue("Rome's turn should start", romeStarted.await(5, TimeUnit.SECONDS))
        // Immediately pause so only Rome runs
        desktopMatch.pause()
        allowRomeToFinish.countDown()

        val paused = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.PAUSED && supervisor.completedInvocations.size == 1
        }
        Assert.assertTrue("Must be paused after Rome's turn", paused)
        Assert.assertEquals(listOf(fixture.romeId), supervisor.completedInvocations)
        Assert.assertEquals(fixture.greeceId, desktopMatch.getActiveCivId())

        val revBeforeResume = desktopMatch.coordinator.getStateRevision()

        // Now resume: Greece should take its turn next without re-running Rome
        val greeceTurnStarted = CountDownLatch(1)
        supervisor.onTurnInvoked = { civId, _ ->
            if (civId == fixture.greeceId) {
                greeceTurnStarted.countDown()
            }
        }

        desktopMatch.resume()
        val greeceStarted = greeceTurnStarted.await(5, TimeUnit.SECONDS)
        Assert.assertTrue("Greece must start its turn on resume", greeceStarted)

        // Pause to verify Greece completed its turn
        desktopMatch.pause()
        val settled = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.PAUSED && supervisor.completedInvocations.size >= 2
        }
        Assert.assertTrue("Match settled after Greece completed", settled)

        // Confirm invocation order: Rome first, Greece second — NO duplicated Rome turn
        Assert.assertEquals(fixture.romeId, supervisor.completedInvocations[0])
        Assert.assertEquals(fixture.greeceId, supervisor.completedInvocations[1])
        Assert.assertTrue(desktopMatch.coordinator.getStateRevision() > revBeforeResume)
    }

    @Test
    fun `repeated resume calls cannot create concurrent loops`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        val threadPool = Executors.newFixedThreadPool(8).also { testExecutors.add(it) }
        val turnsToObserve = CountDownLatch(4)
        supervisor.onTurnInvoked = { _, _ ->
            turnsToObserve.countDown()
        }

        desktopMatch.startAutomaticPlay()

        // Fire 20 resume calls concurrently across multiple threads
        for (i in 0 until 20) {
            threadPool.submit {
                desktopMatch.resume()
            }
        }

        val completed = turnsToObserve.await(10, TimeUnit.SECONDS)
        Assert.assertTrue("Turns should advance despite concurrent resumes", completed)

        desktopMatch.pause()
        threadPool.shutdown()

        // Critical invariant: at no point were two provider invocations active
        Assert.assertEquals(1, supervisor.maxConcurrentInvocations.get())
    }

    @Test
    fun `provider failure blocks progression until retry`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        val failOnce = AtomicInteger(1)
        val failureObserved = CountDownLatch(1)

        supervisor.turnAction = { civId, _ ->
            if (civId == fixture.romeId && failOnce.getAndDecrement() > 0) {
                failureObserved.countDown()
                SupervisorProcess.TurnOutcome(
                    status = "failed",
                    sessionId = null,
                    revision = null,
                    turnEnded = false,
                    toolCalls = emptyList(),
                    detail = "Simulated API Rate Limit 429"
                )
            } else {
                null // proceed with normal turn completion
            }
        }

        desktopMatch.startAutomaticPlay()

        val failed = failureObserved.await(5, TimeUnit.SECONDS)
        Assert.assertTrue("Provider failure should be triggered", failed)

        val stateSettled = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.NEEDS_ATTENTION
        }
        Assert.assertTrue("Match must enter NEEDS_ATTENTION on provider failure", stateSettled)
        Assert.assertEquals("Simulated API Rate Limit 429", desktopMatch.getFailureReason())
        Assert.assertFalse("canResume must be false when attention is needed", desktopMatch.canResume())
        Assert.assertTrue("canRetry must be true when attention is needed", desktopMatch.canRetry())
        Assert.assertEquals("Rome is still active civ awaiting action", fixture.romeId, desktopMatch.getActiveCivId())
        Assert.assertEquals("No turns have completed", 0, supervisor.completedInvocations.size)

        // Progression is completely blocked. Greece is NOT invoked.
        Assert.assertEquals(0, supervisor.activeInvocations.get())

        // Now trigger Retry
        val retryDoneLatch = CountDownLatch(2) // Rome succeeds, then Greece succeeds
        supervisor.onTurnCompleted = { _ ->
            retryDoneLatch.countDown()
        }

        desktopMatch.retry()

        val retried = retryDoneLatch.await(10, TimeUnit.SECONDS)
        Assert.assertTrue("Turns should advance after retry", retried)

        desktopMatch.pause()
        val settledAfterRetry = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.PAUSED
        }
        Assert.assertTrue("Match settled after retry", settledAfterRetry)
        Assert.assertNull("Failure reason cleared", desktopMatch.getFailureReason())
        Assert.assertTrue("Rome and Greece completed turns", supervisor.completedInvocations.size >= 2)
        Assert.assertEquals(fixture.romeId, supervisor.completedInvocations[0])
        Assert.assertEquals(fixture.greeceId, supervisor.completedInvocations[1])
    }

    @Test
    fun `human turn halts automatic progression and handoff resumes properly`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.HUMAN
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        desktopMatch.startAutomaticPlay()

        // Wait until Rome finishes and state reaches WAITING_HUMAN for Greece
        val waitingHuman = awaitCondition(5000) {
            desktopMatch.getRunState() == AgentRunState.WAITING_HUMAN &&
                desktopMatch.getActiveCivId() == fixture.greeceId
        }
        Assert.assertTrue("Match must pause and wait for human player on Greece's turn", waitingHuman)
        Assert.assertTrue("Human input must be allowed", desktopMatch.isHumanInputAllowed())
        Assert.assertEquals(listOf(fixture.romeId), supervisor.completedInvocations)
        Assert.assertEquals(0, supervisor.activeInvocations.get())

        // Now human ends turn via onNextTurnPressed()
        val romeSecondTurnStarted = CountDownLatch(1)
        supervisor.onTurnInvoked = { civId, _ ->
            if (civId == fixture.romeId) {
                romeSecondTurnStarted.countDown()
            }
        }

        val handled = desktopMatch.onNextTurnPressed()
        Assert.assertTrue("onNextTurnPressed handled successfully", handled)

        val romeSecondTurn = romeSecondTurnStarted.await(5, TimeUnit.SECONDS)
        Assert.assertTrue("Automatic loop must resume for agent when human completes turn", romeSecondTurn)

        desktopMatch.pause()
    }

    @Test
    fun `match completion halts progression and prevents further run loops`() {
        val fixture = MatchFixture(listOf(
            "Rome" to ControllerKind.EXTERNAL_AGENT,
            "Greece" to ControllerKind.EXTERNAL_AGENT
        ))
        lateinit var desktopMatch: DesktopAgentMatch
        val supervisor = TestSupervisor { method, params -> desktopMatch.answerEngineRequest(method, params) }
        desktopMatch = fixture.createMatch(supervisor)

        // Let one turn complete
        val firstTurnLatch = CountDownLatch(1)
        supervisor.onTurnInvoked = { _, _ -> firstTurnLatch.countDown() }

        desktopMatch.startAutomaticPlay()
        Assert.assertTrue(firstTurnLatch.await(5, TimeUnit.SECONDS))
        desktopMatch.pause()

        Assert.assertTrue("The invocation must drain before forcing match completion",
            awaitCondition(5000) { desktopMatch.getRunState() == AgentRunState.PAUSED })

        // Artificially complete the match on the coordinator
        desktopMatch.coordinator.forcePhaseForTest(TurnPhase.COMPLETED)

        // Calling resume should recognize completion and transition to FINISHED
        desktopMatch.resume()
        Assert.assertEquals(AgentRunState.FINISHED, desktopMatch.getRunState())
        Assert.assertFalse("Cannot resume when match is finished", desktopMatch.canResume())
        Assert.assertFalse("Cannot pause when match is finished", desktopMatch.canPause())
    }

    companion object {
        private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(20)
            }
            return condition()
        }
    }
}
