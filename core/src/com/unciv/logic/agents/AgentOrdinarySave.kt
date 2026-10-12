package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.CompatibilityVersion
import com.unciv.logic.GameInfo
import com.unciv.logic.UncivShowableException
import com.unciv.logic.civilization.PlayerType

/** Portable ordinary saves carry one match state paired with their own GameInfo snapshot. */
object AgentOrdinarySave {
    /** Explicit format fields prevent Gdx prototype omission from hiding the format version. */
    fun encode(match: AgentMatchState): String = json().apply { setUsePrototypes(false) }.toJson(match)

    /** Export the immutable captured envelope without decoding another potentially large memory graph. */
    fun prepareForSave(game: GameInfo) {
        if (game.agentMatchStateJson == null && game.version.number >= CompatibilityVersion.AGENT_GAME_COMPATIBILITY_NUMBER)
            throw UncivShowableException("This protected agent save is missing its match metadata.")
        if (game.agentMatchStateJson?.isBlank() == true)
            throw UncivShowableException("This agent save has empty match metadata.")
    }

    /** Validate before transients can auto-assign production or any screen enables legacy controls. */
    fun read(game: GameInfo): AgentMatchState? {
        val encoded = game.agentMatchStateJson
        if (encoded == null) {
            if (game.version.number >= CompatibilityVersion.AGENT_GAME_COMPATIBILITY_NUMBER)
                throw UncivShowableException("This protected agent save is missing its match metadata.")
            return null
        }
        return try {
            require(encoded.isNotBlank()) { "Empty agent metadata" }
            val match = migrateAgentMatchStateJson(encoded)
            require(match.matchId.isNotBlank() && match.timelineId.isNotBlank()) { "Missing match identity" }
            require(match.matchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid match identity" }
            require(match.stateRevision >= 0 && match.turnSerial >= 0 && match.endTurnsCommitted >= 0) {
                "Invalid match revision"
            }
            val civs = game.civilizations.filter { it.civName != Constants.spectator }.associateBy { it.civID }
            require(civs.isNotEmpty() && civs.keys.none { it.isBlank() }) { "Missing civilization identity" }
            require(match.controllers.keys == civs.keys) { "Controllers do not match this saved world" }
            for ((id, controller) in match.controllers) {
                require(controller.civId == id) { "Mismatched controller identity" }
                if (controller.kind == ControllerKind.EXTERNAL_AGENT) {
                    require(civs[id]!!.playerType == PlayerType.Human) { "Agent civilization must use human rules" }
                    val profile = requireNotNull(controller.agentProfile) { "Missing agent profile" }
                    require(profile.profileId.isNotBlank() && profile.provider.isNotBlank() && profile.modelId.isNotBlank()) {
                        "Incomplete agent profile"
                    }
                }
            }
            require(match.privateNotes.keys.all { it in civs } && match.notesRevisions.keys.all { it in civs }) {
                "Notes refer to an unknown civilization"
            }
            require(match.notesRevisions.values.all { it >= 0 }) { "Invalid notes revision" }
            require(match.pendingDecisions.all { it.owningCivId in civs }) { "Invalid decision owner" }
            require(match.guidanceQueue.all { it.targetCivId in civs }) { "Invalid guidance recipient" }
            require(match.currentTurnReceipts.all {
                it.actorCivId in civs && it.timelineId == match.timelineId &&
                    it.beforeRevision >= 0 && it.afterRevision >= it.beforeRevision && it.afterRevision <= match.stateRevision
            }) { "Receipts do not match this saved timeline" }
            NationControl.initialize(game, match)
            NationControl.validateSaved(game, match)
            match
        } catch (ex: Exception) {
            throw UncivShowableException("Cannot restore this agent save: ${ex.message}")
        }
    }

    fun prepareForLoad(game: GameInfo): AgentMatchState? = read(game)?.also {
        game.suppressAutomationDuringTransients = true
        game.agentNormalVisibility = true
    }

    /** Rebuild operational setup only; the saved controller state stays authoritative. */
    fun setupFor(game: GameInfo, match: AgentMatchState): AgentMatchSetup = AgentMatchSetup().apply {
        for (civ in game.civilizations) {
            val controller = match.controllers[civ.civID] ?: continue
            if (controller.kind == ControllerKind.EXTERNAL_AGENT)
                slots[civ.civName] = AgentSlotConfig(civ.civName, controller.agentProfile!!.copy())
        }
    }
}
