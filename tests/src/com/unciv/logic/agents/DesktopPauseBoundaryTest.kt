package com.unciv.logic.agents

import com.unciv.app.desktop.agents.DesktopAgentMatch
import com.unciv.app.desktop.agents.SupervisorProcess
import com.unciv.app.desktop.agents.SupervisorRunner
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** Deterministic dispatch boundary: no scheduling sleeps and no real provider process. */
@RunWith(BaseTestRunner::class)
class DesktopPauseBoundaryTest {
    private class Fixture {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_Vanilla)
        val host: DesktopAgentMatch
        var supervisorStarts = 0
        var providerInvocations = 0

        init {
            game.gameInfo.gameParameters.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            game.makeHexagonalMap(4)
            val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            val greece = game.addCiv(game.ruleset.nations["Greece"]!!)
            game.addUnit("Warrior", rome, game.getTile(0, 0))
            game.addUnit("Warrior", greece, game.getTile(2, 0))
            game.gameInfo.currentPlayer = rome.civID
            game.gameInfo.currentPlayerCiv = rome
            val match = AgentMatchState(matchId = "pause-boundary", timelineId = "t1",
                turnPhase = TurnPhase.AWAITING_DECISIONS)
            for (civ in listOf(rome, greece)) {
                match.controllers[civ.civID] = CivilizationController(civ.civID,
                    ControllerKind.EXTERNAL_AGENT, AgentProfile.defaultFor(civ.civID))
            }
            host = DesktopAgentMatch(game.gameInfo, match, AgentMatchSetup(),
                Files.createTempDirectory("unciv-pause-boundary-").toFile(), supervisorFactory = {
                    supervisorStarts++
                    object : SupervisorRunner {
                        override fun isAlive() = true
                        override fun runTurn(control: Map<String, Any?>): SupervisorProcess.TurnOutcome {
                            providerInvocations++
                            return SupervisorProcess.TurnOutcome("failed", null, null, false,
                                emptyList(), "Provider invocation crossed a requested pause")
                        }
                        override fun shutdown() {}
                        override fun kill() {}
                    }
                })
        }

        fun markDispatchSelected() {
            DesktopAgentMatch::class.java.getDeclaredField("runState").apply { isAccessible = true }
                .set(host, AgentRunState.STARTING)
        }

        fun dispatchSelectedTurn(civId: String) {
            DesktopAgentMatch::class.java.getDeclaredMethod("runAgentTurn", String::class.java)
                .apply { isAccessible = true }.invoke(host, civId)
        }
    }

    @Test fun `pause after selecting a turn prevents starting its provider invocation`() {
        val f = Fixture()
        // Replay the exact gap after runLoop selects a civ and releases its lock,
        // before the provider invocation claims that selected turn.
        val selectedCiv = f.host.coordinator.getCurrentCivId()
        f.markDispatchSelected()
        f.host.pause()
        assertEquals(AgentRunState.PAUSING, f.host.getRunState())

        f.dispatchSelectedTurn(selectedCiv)

        assertEquals("Pause must prevent starting even the supervisor", 0, f.supervisorStarts)
        assertEquals("A selected turn is not permission to ignore a later pause", 0, f.providerInvocations)
        assertTrue(f.host.getRunState() in setOf(AgentRunState.PAUSING, AgentRunState.PAUSED))
        assertNull(f.host.getFailureReason())
    }

    @Test fun `an already paused match rejects a previously selected provider dispatch`() {
        val f = Fixture()
        val selectedCiv = f.host.coordinator.getCurrentCivId()
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())

        f.dispatchSelectedTurn(selectedCiv)

        assertEquals(0, f.supervisorStarts)
        assertEquals(0, f.providerInvocations)
        assertEquals(AgentRunState.PAUSED, f.host.getRunState())
        assertNull(f.host.getFailureReason())
    }
}
