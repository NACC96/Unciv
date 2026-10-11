package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.Player
import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AgentMatchControlTest {

    @Test
    fun `idempotent controls do not error or transition unexpectedly`() {
        // Repeated RUN while already RUN / STARTING
        val run1 = AgentRunControls.transition(AgentRunState.STARTING, AgentControl.RUN)
        Assert.assertNotNull(run1)
        Assert.assertEquals(AgentRunState.STARTING, run1!!.to)

        val run2 = AgentRunControls.transition(AgentRunState.AGENT_DECIDING, AgentControl.RUN)
        Assert.assertNotNull(run2)
        Assert.assertEquals(AgentRunState.AGENT_DECIDING, run2!!.to)

        // Repeated PAUSE while already PAUSED
        val pause1 = AgentRunControls.transition(AgentRunState.PAUSED, AgentControl.PAUSE)
        Assert.assertNotNull(pause1)
        Assert.assertEquals(AgentRunState.PAUSED, pause1!!.to)

        // Repeated PAUSE while settling (PAUSING)
        val pause2 = AgentRunControls.transition(AgentRunState.PAUSING, AgentControl.PAUSE)
        Assert.assertNotNull(pause2)
        Assert.assertEquals(AgentRunState.PAUSING, pause2!!.to)
    }

    @Test
    fun `profile cache preserves custom settings across slot cycling`() {
        val profiles = HashMap<Player, AgentProfile>()
        val profileCache = HashMap<Player, AgentProfile>()
        val slot = Player("Rome", PlayerType.AI)

        // AI -> HUMAN
        AgentSlotLogic.applyControl(slot, SlotControl.HUMAN, profiles, profileCache)
        Assert.assertEquals(PlayerType.Human, slot.playerType)
        Assert.assertTrue(profiles.isEmpty())

        // HUMAN -> AGENT
        AgentSlotLogic.applyControl(slot, SlotControl.AGENT, profiles, profileCache)
        Assert.assertEquals(PlayerType.Human, slot.playerType)
        val profile = profiles[slot]!!
        profile.displayName = "Super Luna Rome"
        profile.modelId = "gpt-5.6-luna"
        profile.reasoning = "medium"

        // AGENT -> AI (cycling slot)
        AgentSlotLogic.applyControl(slot, SlotControl.AI, profiles, profileCache)
        Assert.assertFalse(profiles.containsKey(slot))
        Assert.assertTrue(profileCache.containsKey(slot))

        // AI -> HUMAN
        AgentSlotLogic.applyControl(slot, SlotControl.HUMAN, profiles, profileCache)
        Assert.assertFalse(profiles.containsKey(slot))

        // HUMAN -> AGENT: Custom profile should be restored!
        AgentSlotLogic.applyControl(slot, SlotControl.AGENT, profiles, profileCache)
        val restored = profiles[slot]!!
        Assert.assertEquals("Super Luna Rome", restored.displayName)
        Assert.assertEquals("gpt-5.6-luna", restored.modelId)
        Assert.assertEquals("medium", restored.reasoning)
    }

    @Test
    fun `civ change updates default agent name but preserves custom name`() {
        val profiles = HashMap<Player, AgentProfile>()
        val profileCache = HashMap<Player, AgentProfile>()
        val slot = Player("Rome", PlayerType.Human)

        AgentSlotLogic.applyControl(slot, SlotControl.AGENT, profiles, profileCache)
        val profile = profiles[slot]!!
        Assert.assertEquals("GPT-6 Luna Rome", profile.displayName)

        // Change civ from Rome to Greece with default name -> should update to GPT-6 Luna Greece
        slot.chosenCiv = "Greece"
        AgentSlotLogic.onCivChanged(slot, "Rome", "Greece", profiles, profileCache)
        Assert.assertEquals("GPT-6 Luna Greece", profile.displayName)

        // Custom name
        profile.displayName = "My Custom Agent"
        slot.chosenCiv = "Egypt"
        AgentSlotLogic.onCivChanged(slot, "Greece", "Egypt", profiles, profileCache)
        // Custom name should NOT be overwritten
        Assert.assertEquals("My Custom Agent", profile.displayName)
    }

    @Test
    fun `setup validator uses AgentModelCatalog validation`() {
        val setup = AgentMatchSetup()
        val badProfile = AgentProfile(
            profileId = "1",
            displayName = "", // missing display name
            provider = "codex",
            modelId = "gpt-5.6-luna"
        )
        setup.slots["Rome"] = AgentSlotConfig("Rome", badProfile)

        val params = com.unciv.models.metadata.GameParameters().apply {
            baseRuleset = com.unciv.models.metadata.BaseRuleset.Civ_V_Vanilla.fullName
            espionageEnabled = false
            randomNumberOfPlayers = false
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece", PlayerType.AI))
        }

        val errors = setup.validate(params)
        Assert.assertTrue(errors.any { it.contains("display name") })
    }
}
