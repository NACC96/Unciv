package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.Player
import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup slot-state machine (AI_AGENT_PLAN.md §11.1): tri-state cycling,
 * Human rule parity for agent slots, profile lifecycle, duplicate/fixed-pick
 * fences, snapshot assembly. Pure logic — the widgets delegate here.
 */
@RunWith(BaseTestRunner::class)
class AgentSlotLogicTest {

    private fun player(civ: String, type: PlayerType) = Player(civ, type)

    @Test
    fun `slots cycle ai human agent`() {
        val profiles = HashMap<Player, AgentProfile>()
        val slot = player("Rome", PlayerType.AI)
        Assert.assertEquals(SlotControl.AI, AgentSlotLogic.controlOf(slot, profiles))
        Assert.assertTrue(AgentSlotLogic.isSlotEditable(slot, false))

        AgentSlotLogic.applyControl(slot, SlotControl.HUMAN, profiles)
        Assert.assertEquals(PlayerType.Human, slot.playerType)
        Assert.assertTrue(profiles.isEmpty())

        AgentSlotLogic.applyControl(slot, SlotControl.AGENT, profiles)
        Assert.assertEquals(PlayerType.Human, slot.playerType) // rule parity kept
        Assert.assertTrue(profiles.containsKey(slot))

        AgentSlotLogic.applyControl(slot, SlotControl.AI, profiles)
        Assert.assertEquals(PlayerType.AI, slot.playerType)
        Assert.assertTrue(profiles.isEmpty())

        Assert.assertEquals(SlotControl.HUMAN, AgentSlotLogic.nextControl(SlotControl.AI))
        Assert.assertEquals(SlotControl.AGENT, AgentSlotLogic.nextControl(SlotControl.HUMAN))
        Assert.assertEquals(SlotControl.AI, AgentSlotLogic.nextControl(SlotControl.AGENT))
    }

    @Test
    fun `spectators and managed randoms are locked`() {
        val profiles = HashMap<Player, AgentProfile>()
        Assert.assertFalse(AgentSlotLogic.isSlotEditable(
            player(Constants.spectator, PlayerType.Human), false))
        Assert.assertFalse(AgentSlotLogic.isSlotEditable(
            player(Constants.random, PlayerType.AI), true))
        Assert.assertTrue(AgentSlotLogic.isSlotEditable(
            player(Constants.random, PlayerType.Human), true))
    }

    @Test
    fun `removal forgets profiles`() {
        val profiles = HashMap<Player, AgentProfile>()
        val slot = player("Rome", PlayerType.Human)
        AgentSlotLogic.applyControl(slot, SlotControl.AGENT, profiles)
        AgentSlotLogic.forgetPlayer(slot, profiles)
        Assert.assertTrue(profiles.isEmpty())
        Assert.assertEquals(SlotControl.HUMAN, AgentSlotLogic.controlOf(slot, profiles))
    }

    @Test
    fun `assembly snapshots profiles and fences duplicates`() {
        val profiles = HashMap<Player, AgentProfile>()
        val rome = player("Rome", PlayerType.Human)
        val greece = player("Greece", PlayerType.Human)
        AgentSlotLogic.applyControl(rome, SlotControl.AGENT, profiles)
        profiles[rome]!!.displayName = "Ada"
        profiles[rome]!!.provider = "codex"
        profiles[rome]!!.modelId = "m"
        AgentSlotLogic.applyControl(greece, SlotControl.HUMAN, profiles)

        val setup = AgentSlotLogic.buildSetup(listOf(rome, greece), profiles)
        Assert.assertEquals(setOf("Rome"), setup.slots.keys)
        Assert.assertEquals("Ada", setup.slots["Rome"]!!.profile.displayName)
        profiles[rome]!!.displayName = "MUTATED"
        Assert.assertEquals("Ada", setup.slots["Rome"]!!.profile.displayName)

        val dupA = player("Rome", PlayerType.Human)
        val dupB = player("Rome", PlayerType.Human)
        val dupProfiles = HashMap<Player, AgentProfile>()
        dupProfiles[dupA] = AgentProfile()
        dupProfiles[dupB] = AgentProfile()
        Assert.assertTrue(AgentSlotLogic.collectSlotErrors(
            listOf(dupA, dupB), dupProfiles).any { it.contains("share") })
        val random = player(Constants.random, PlayerType.Human)
        val randomProfiles = HashMap<Player, AgentProfile>()
        randomProfiles[random] = AgentProfile()
        Assert.assertTrue(AgentSlotLogic.collectSlotErrors(
            listOf(random), randomProfiles).any { it.contains("fixed") })
    }
}
