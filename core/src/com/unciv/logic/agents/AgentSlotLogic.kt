package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.Player

/**
 * Pure slot-state logic for the new-game player table (AI_AGENT_PLAN.md §11.1).
 * Kept UI-free so it is unit-testable headlessly; PlayerPickerTable delegates
 * its button cycling and setup assembly to these functions.
 *
 * Agent slots stay `PlayerType.Human` (rule parity with human civs — starting
 * units, bonuses, research costs); the agent distinction lives in the
 * profiles map, never in player type.
 */
enum class SlotControl { AI, HUMAN, AGENT }

object AgentSlotLogic {
    fun controlOf(player: Player, agentProfiles: Map<Player, AgentProfile>): SlotControl = when {
        agentProfiles.containsKey(player) -> SlotControl.AGENT
        player.playerType == PlayerType.Human -> SlotControl.HUMAN
        else -> SlotControl.AI
    }

    /** Whether the slot button may change state at all. */
    fun isSlotEditable(player: Player, randomNumberOfPlayers: Boolean): Boolean = when {
        player.chosenCiv == Constants.spectator -> false // robots may not spectate
        randomNumberOfPlayers && player.chosenCiv == Constants.random
            && player.playerType == PlayerType.AI -> false // random AI is system-managed
        else -> true
    }

    fun nextControl(current: SlotControl): SlotControl = when (current) {
        SlotControl.AI -> SlotControl.HUMAN
        SlotControl.HUMAN -> SlotControl.AGENT
        SlotControl.AGENT -> SlotControl.AI
    }

    /** Applies a control state: rule type on the player, profile presence in the map. Preserves profiles via [profileCache]. */
    fun applyControl(
        player: Player,
        control: SlotControl,
        agentProfiles: MutableMap<Player, AgentProfile>,
        profileCache: MutableMap<Player, AgentProfile>? = null
    ) {
        when (control) {
            SlotControl.AI -> {
                player.playerType = PlayerType.AI
                val existing = agentProfiles.remove(player)
                if (existing != null && profileCache != null) {
                    profileCache[player] = existing
                }
            }
            SlotControl.HUMAN -> {
                player.playerType = PlayerType.Human
                val existing = agentProfiles.remove(player)
                if (existing != null && profileCache != null) {
                    profileCache[player] = existing
                }
            }
            SlotControl.AGENT -> {
                player.playerType = PlayerType.Human
                val restored = profileCache?.get(player)?.copy() ?: AgentProfile.defaultFor(player.chosenCiv)
                agentProfiles[player] = restored
                profileCache?.put(player, restored)
            }
        }
    }

    fun forgetPlayer(
        player: Player,
        agentProfiles: MutableMap<Player, AgentProfile>,
        profileCache: MutableMap<Player, AgentProfile>? = null
    ) {
        agentProfiles.remove(player)
        profileCache?.remove(player)
    }

    /** Updates default agent display names when the civilization choice changes. */
    fun onCivChanged(
        player: Player,
        oldCiv: String,
        newCiv: String,
        agentProfiles: MutableMap<Player, AgentProfile>,
        profileCache: MutableMap<Player, AgentProfile>? = null
    ) {
        val profile = agentProfiles[player] ?: profileCache?.get(player) ?: return
        val model = AgentModelCatalog.findModel(profile.provider, profile.modelId)
        val oldDefault = if (oldCiv.isNotBlank()) "${model.label} $oldCiv" else model.label
        if (profile.displayName.isBlank() || profile.displayName == oldDefault) {
            profile.displayName = if (newCiv.isNotBlank()) "${model.label} $newCiv" else model.label
        }
    }

    /** Structural slot errors (duplicates, non-fixed picks); profile/rule validation lives in [AgentMatchSetup.validate]. */
    fun collectSlotErrors(
        players: List<Player>,
        agentProfiles: Map<Player, AgentProfile>
    ): List<String> {
        val errors = ArrayList<String>()
        val agentNations = players.filter { agentProfiles.containsKey(it) }.map { it.chosenCiv }
        if (agentNations.any { it == Constants.random || it == Constants.spectator })
            errors.add("Agent slots need fixed civilizations (no Random, no Spectator)")
        val duplicates = agentNations.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty())
            errors.add("Two agent slots cannot share a civilization: ${duplicates.joinToString()}")
        return errors
    }

    /** Assembles the pre-start setup; profiles are snapshot-copied. */
    fun buildSetup(
        players: List<Player>,
        agentProfiles: Map<Player, AgentProfile>
    ): AgentMatchSetup {
        val setup = AgentMatchSetup()
        for (player in players) {
            val profile = agentProfiles[player] ?: continue
            val nation = player.chosenCiv
            require(nation != Constants.random && nation != Constants.spectator) {
                "Agent slot has no fixed civilization"
            }
            require(!setup.slots.containsKey(nation)) { "Duplicate agent civilization: $nation" }
            setup.slots[nation] = AgentSlotConfig(nation, profile.copy())
        }
        return setup
    }
}
