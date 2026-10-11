package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import java.util.UUID

/**
 * Pre-start configuration of an agent-enabled match (AI_AGENT_PLAN.md §11.1).
 *
 * Slot identity is the player's chosen nation name: after GameStarter
 * resolves randoms and orders civilizations (spectators first, barbarians
 * prepended), slot indices are meaningless, but resolved nation names are
 * unique per game. Agent slots keep `PlayerType.Human` (rule parity); this
 * record carries the controller distinction the engine must not infer from
 * player type. Existing hotseat behavior is untouched when no agent slots
 * exist (plain games never construct this).
 */
class AgentSlotConfig(
    /** Resolved-or-explicit nation name, e.g. "Rome" (never "Random" here). */
    var nationName: String = "",
    var profile: AgentProfile = AgentProfile()
) {
    @Suppress("unused")
    constructor() : this("", AgentProfile())
}

class AgentMatchSetup(
    var slots: HashMap<String, AgentSlotConfig> = HashMap()
) {
    /** Validation failures blocking match creation; empty means ready. */
    fun validate(parameters: GameParameters): List<String> {
        val errors = ArrayList<String>()
        if (slots.isEmpty()) return errors
        if (parameters.baseRuleset != BaseRuleset.Civ_V_Vanilla.fullName)
            errors.add("Agent matches require the Civ V - Vanilla ruleset")
        if (parameters.espionageEnabled)
            errors.add("Espionage must stay disabled for agent matches")
        if (parameters.mods.isNotEmpty())
            errors.add("Agent matches allow no mods")
        if (parameters.randomNumberOfPlayers)
            errors.add("Disable random player counts so no configured leader disappears")
        val agentPlayers = parameters.players.filter {
            it.playerType == PlayerType.Human && it.chosenCiv != Constants.spectator && slots.containsKey(it.chosenCiv)
        }
        if (agentPlayers.size != slots.size)
            errors.add("Every configured agent needs its own Human slot with a fixed civilization")
        if (slots.keys.any { it == Constants.random || it == Constants.spectator })
            errors.add("Agent slots need fixed civilizations (no Random, no Spectator)")
        for ((nation, config) in slots) {
            val profileErrors = AgentModelCatalog.validateProfile(config.profile)
            for (err in profileErrors) {
                errors.add("$nation: $err")
            }
        }
        return errors
    }

    /**
     * Binds slot configs to created civilizations by nation name and builds
     * the opening match state (phase READY_TO_START). Every major civ gets a
     * controller: configured agents, Human slots stay human, everything else
     * (AI slots, city-states, barbarians) is built-in AI.
     */
    fun buildMatchState(game: GameInfo): AgentMatchState {
        val match = AgentMatchState(
            matchId = "match-${UUID.randomUUID()}",
            timelineId = "timeline-${UUID.randomUUID()}",
            turnPhase = TurnPhase.READY_TO_START
        )
        for (civ in game.civilizations) {
            if (civ.isSpectator()) continue
            match.controllers[civ.civID] = when {
                slots.containsKey(civ.nation.name) ->
                    CivilizationController(civ.civID, ControllerKind.EXTERNAL_AGENT,
                        slots[civ.nation.name]!!.profile.copy())
                civ.playerType == PlayerType.Human ->
                    CivilizationController(civ.civID, ControllerKind.HUMAN, null)
                else ->
                    CivilizationController(civ.civID, ControllerKind.BUILTIN_AI, null)
            }
        }
        return match
    }
}

/** Controller kind routing helper for setup and coordinator UI. */
fun Civilization.controllerKindIn(match: AgentMatchState): ControllerKind =
    match.controllers[civID]?.kind ?: ControllerKind.BUILTIN_AI
