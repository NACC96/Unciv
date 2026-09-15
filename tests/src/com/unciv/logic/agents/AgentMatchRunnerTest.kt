package com.unciv.logic.agents

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
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
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-match loop proof (AI_AGENT_PLAN.md §9 step/run, §13.3 scenario shape):
 * a real GameStarter Tiny Vanilla game with an external-agent civ and a
 * human civ runs whole rounds through coordinator + scripted policies —
 * begin, turns, rotation, handoff, receipts — with no provider involved.
 */
@RunWith(BaseTestRunner::class)
class AgentMatchRunnerTest {

    private fun startMatch(): Triple<TestGameFacade, MatchCoordinator, AgentMatchRunner> {
        val facade = TestGameFacade()
        val coordinator = MatchCoordinator(
            facade.game, facade.match, InMemoryCheckpointWriter())
        val runner = AgentMatchRunner(coordinator, { PassPolicy() })
        return Triple(facade, coordinator, runner)
    }

    /** Real started game + bound agent match, shared by these tests. */
    private class TestGameFacade {
        val game: com.unciv.logic.GameInfo
        val match: AgentMatchState
        lateinit var romeId: String
        lateinit var greeceId: String

        init {
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
                players.add(Player("Rome", PlayerType.Human))
                players.add(Player("Greece", PlayerType.Human))
            }
            UncivGame.Current = UncivGame()
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.settings = GameSettings()
            val mapParameters = MapParameters().apply {
                mapSize = MapSize(MapSize.Predefined.Tiny)
                seed = 7L
            }
            game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
            UncivGame.Current.gameInfo = game

            val setup = AgentMatchSetup()
            setup.slots["Rome"] = AgentSlotConfig("Rome", AgentProfile(
                "p1", "Ada", "codex", "m", null, "", 1))
            Assert.assertTrue(setup.validate(parameters).isEmpty())
            match = setup.buildMatchState(game)
            romeId = game.civilizations.first { it.nation.name == "Rome" }.civID
            greeceId = game.civilizations.first { it.nation.name == "Greece" }.civID
        }
    }

    @Test
    fun `two full rounds rotate through both leaders`() {
        val (facade, coordinator, runner) = startMatch()
        val turnsBefore = coordinator.getGameTurns()
        val reports = runner.runRounds(2)

        Assert.assertEquals(turnsBefore + 2, coordinator.getGameTurns())
        Assert.assertEquals(4, reports.size) // Rome, Greece, Rome, Greece
        Assert.assertEquals(
            listOf(facade.romeId, facade.greeceId, facade.romeId, facade.greeceId),
            reports.map { it.civId })
        Assert.assertTrue(reports.all { it.endTurnCommitted && it.endReceipt != null })
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, coordinator.getTurnPhase())
    }

    @Test
    fun `human handoff takes ordinary commands through the same path`() {
        val (facade, coordinator, runner) = startMatch()
        // Run until Greece (human) is to move.
        var guard = 8
        while (coordinator.getCurrentCivId() != facade.greeceId && guard-- > 0) {
            if (coordinator.getTurnPhase() == TurnPhase.READY_TO_START)
                coordinator.beginAwaitingTurn()
            else runner.runTurn()
        }
        Assert.assertEquals(facade.greeceId, coordinator.getCurrentCivId())

        // A human-issued unit order commits through the coordinator.
        val settler = coordinator.observe(facade.greeceId).ownUnits
            .firstOrNull { it.name == "Settler" }
        if (settler != null) {
            val result = coordinator.execute(UnitOrderCommand(facade.greeceId,
                "human-1", coordinator.getStateRevision(), settler.unitId, UnitOrder.SLEEP))
            Assert.assertNotNull((result as UnitOrderResult).action)
        }
        runner.runTurn() // human ends (pass policy stands in for the picker)
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, coordinator.getTurnPhase())
    }

    @Test
    fun `quiet policy leaves an incomplete turn with moves retained`() {
        val (_, coordinator, _) = startMatch()
        val quiet = AgentMatchRunner(coordinator, { _ -> AgentPolicy { emptyList() } })
        val report = quiet.runTurn()
        Assert.assertFalse(report.endTurnCommitted)
        Assert.assertEquals(0, report.commandsApplied)
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, coordinator.getTurnPhase())
    }
}
