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
 * Setup validation and slot→civilization binding (AI_AGENT_PLAN.md §11.1):
 * agent matches pin Vanilla, lock espionage/mods/random counts, require
 * provider configuration, and bind by resolved nation name after GameStarter
 * runs (order-independent, random-safe).
 */
@RunWith(BaseTestRunner::class)
class AgentMatchSetupTest {

    private fun vanillaParameters(): GameParameters {
        return GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
            espionageEnabled = false
            numberOfCityStates = 0
            noBarbarians = true
            randomNumberOfPlayers = false
            difficulty = "Prince"
            speed = "Quick"
            players.clear()
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece", PlayerType.Human))
        }
    }

    private fun setupWithRomeAgent(): AgentMatchSetup {
        val setup = AgentMatchSetup()
        setup.slots["Rome"] = AgentSlotConfig("Rome", AgentProfile(
            "p1", "Ada", "codex", "gpt-5.2-codex", null, "Bold", 1))
        return setup
    }

    private fun startVanillaGame(parameters: GameParameters = vanillaParameters()): com.unciv.logic.GameInfo {
        RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UncivGame.Current.settings = GameSettings()
        val mapParameters = MapParameters().apply {
            mapSize = MapSize(MapSize.Predefined.Tiny)
            seed = 42L
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
        UncivGame.Current.gameInfo = game
        return game
    }

    @Test
    fun `valid vanilla setup passes`() {
        Assert.assertTrue(setupWithRomeAgent().validate(vanillaParameters()).isEmpty())
    }

    @Test
    fun `setup validation fences every first-release boundary`() {
        val setup = setupWithRomeAgent()
        val gnk = vanillaParameters().apply { baseRuleset = BaseRuleset.Civ_V_GnK.fullName }
        Assert.assertTrue(setup.validate(gnk).any { it.contains("Vanilla") })
        val esp = vanillaParameters().apply { espionageEnabled = true }
        Assert.assertTrue(setup.validate(esp).any { it.contains("Espionage") })
        val mods = vanillaParameters().apply { mods.add("Whatever") }
        Assert.assertTrue(setup.validate(mods).any { it.contains("mods") })
        val random = vanillaParameters().apply { randomNumberOfPlayers = true }
        Assert.assertTrue(setup.validate(random).any { it.contains("random") })
        val unconfigured = AgentMatchSetup()
        unconfigured.slots["Rome"] = AgentSlotConfig("Rome", AgentProfile("p1", "Ada", "", "", null, "", 0))
        Assert.assertTrue(unconfigured.validate(vanillaParameters()).any { it.contains("provider") })
        val slotless = AgentMatchSetup()
        slotless.slots["Aztecs"] = AgentSlotConfig("Aztecs", AgentProfile(
            "p9", "Zed", "claude", "m", null, "", 1))
        Assert.assertTrue(slotless.validate(vanillaParameters()).any { it.contains("own Human slot") })
    }

    @Test
    fun `binding assigns controllers by nation after game start`() {
        val setup = setupWithRomeAgent()
        val game = startVanillaGame()
        Assert.assertTrue(setup.validate(vanillaParameters()).isEmpty())
        val match = setup.buildMatchState(game)

        val rome = game.civilizations.first { it.nation.name == "Rome" }
        val greece = game.civilizations.first { it.nation.name == "Greece" }
        Assert.assertEquals(ControllerKind.EXTERNAL_AGENT, match.controllers[rome.civID]!!.kind)
        Assert.assertEquals("Ada", match.controllers[rome.civID]!!.agentProfile!!.displayName)
        Assert.assertEquals(ControllerKind.HUMAN, match.controllers[greece.civID]!!.kind)
        Assert.assertEquals(TurnPhase.READY_TO_START, match.turnPhase)
        Assert.assertTrue(match.matchId.isNotEmpty() && match.timelineId.isNotEmpty())

        // Profile edits after binding never leak into the started match.
        setup.slots["Rome"]!!.profile.displayName = "MUTATED"
        Assert.assertEquals("Ada", match.controllers[rome.civID]!!.agentProfile!!.displayName)

        // The started match actually runs through the coordinator.
        val coordinator = MatchCoordinator(game, match, InMemoryCheckpointWriter())
        coordinator.beginAwaitingTurn()
        Assert.assertEquals(TurnPhase.AWAITING_DECISIONS, coordinator.getTurnPhase())
        Assert.assertEquals(rome.civID, coordinator.getCurrentCivId())
    }
}
