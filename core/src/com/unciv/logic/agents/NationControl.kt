package com.unciv.logic.agents

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.RulesetCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType

enum class NationControlChange { SAVE_AGENT_SETTINGS, PLAY_AS, HAND_TO_AGENT, SPECTATE }

/** A copied settings view; editing its profile cannot edit the authoritative match. */
data class NationSetting(val civId: String, val nationName: String, val kind: ControllerKind,
                         val profile: AgentProfile?, val isActive: Boolean)

object NationControl {
    fun isEligible(civ: Civilization, match: AgentMatchState): Boolean =
        civ.isMajorCiv() && !civ.isSpectator() && !civ.isBarbarian && !civ.isDefeated() &&
            civ.playerType == PlayerType.Human &&
            match.controllers[civ.civID]?.kind in setOf(ControllerKind.HUMAN, ControllerKind.EXTERNAL_AGENT)

    /** These predicates also work before nation/unit transients have been restored during load. */
    private fun isSpectator(civ: Civilization) = civ.civName == Constants.spectator
    private fun isMajor(game: GameInfo, civ: Civilization): Boolean =
        runCatching { civ.nation.isMajorCiv }.getOrNull()
            ?: (RulesetCache.getComplexRuleset(game.gameParameters).nations[civ.civName]?.isMajorCiv == true)
    private fun isSavedNation(game: GameInfo, civ: Civilization, match: AgentMatchState): Boolean =
        isMajor(game, civ) && civ.playerType == PlayerType.Human &&
            match.controllers[civ.civID]?.kind in setOf(ControllerKind.HUMAN, ControllerKind.EXTERNAL_AGENT)

    /** Old offline saves may infer one local principal, never an arbitrary active agent. */
    private fun oldOrigin(game: GameInfo, match: AgentMatchState, attachedViewer: String? = null): String? {
        if (game.gameParameters.isOnlineMultiplayer) return null
        game.civilizations.firstOrNull { it.civID == attachedViewer &&
            (isSpectator(it) || isSavedNation(game, it, match) && match.controllers[it.civID]?.kind == ControllerKind.HUMAN) }
            ?.let { return it.civID }
        game.civilizations.singleOrNull { isSpectator(it) }?.let { return it.civID }
        val humans = game.civilizations.filter { isSavedNation(game, it, match) && match.controllers[it.civID]?.kind == ControllerKind.HUMAN }
        if (humans.size == 1) return humans.single().civID
        return humans.firstOrNull { it.civID == attachedViewer }?.civID
    }

    /** Pure pre-screen lookup: missing or ambiguous identities grant no management rights. */
    fun savedViewerCivId(game: GameInfo, match: AgentMatchState): String? {
        if (game.gameParameters.isOnlineMultiplayer) return null
        val origin = match.localControlCivId ?: oldOrigin(game, match) ?: return null
        val principal = game.civilizations.firstOrNull { it.civID == origin } ?: return null
        val viewer = match.localViewingCivId ?: origin
        val viewed = game.civilizations.firstOrNull { it.civID == viewer } ?: return null
        return when {
            isSpectator(principal) && (isSpectator(viewed) || isSavedNation(game, viewed, match)) -> viewer
            !isSpectator(principal) && viewer == origin && isSavedNation(game, principal, match) -> viewer
            else -> null
        }
    }

    /** Initialization works on an unpublished copy, then travels in the host's captured save pair. */
    fun initialize(game: GameInfo, match: AgentMatchState, attachedViewer: String? = null) {
        if (!game.gameParameters.isOnlineMultiplayer) {
            if (match.localControlCivId == null) match.localControlCivId = oldOrigin(game, match, attachedViewer)
            if (match.localViewingCivId == null) match.localViewingCivId = match.localControlCivId
        }
        for ((id, controller) in match.controllers) {
            if (controller.kind == ControllerKind.EXTERNAL_AGENT && id !in match.retainedAgentProfiles)
                controller.agentProfile?.let { match.retainedAgentProfiles[id] = it.copy() }
        }
    }

    private fun validateProfileStructure(profile: AgentProfile) {
        require(profile.profileId.isNotBlank() && profile.profileId.length <= 128) { "Invalid agent profile identity" }
        require(profile.displayName.length <= 256 && profile.modelId.length <= 256 && profile.provider.length <= 32) {
            "Agent name, model or provider is too long"
        }
        require(profile.personality.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Agent personality exceeds 16 KiB" }
        require(profile.provider.isNotBlank() && profile.modelId.isNotBlank()) { "Incomplete agent profile" }
        require((profile.reasoning?.length ?: 0) <= 64) { "Agent reasoning setting is too long" }
        require(profile.profileRevision >= 0) { "Invalid agent profile revision" }
    }

    /** New assignments/edits validate capabilities; saved choices survive future catalog discovery drift. */
    fun validateProfile(profile: AgentProfile) {
        validateProfileStructure(profile)
        require(profile.reasoning == null || ThinkingLevel.entries.any { it.wireValue == profile.reasoning }) { "Unknown reasoning level" }
        val errors = AgentModelCatalog.validateProfile(profile)
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }

    /** Save identity validation is independent of current-turn gameplay authority. */
    fun validateSaved(game: GameInfo, match: AgentMatchState) {
        val origin = match.localControlCivId
        if (origin != null) {
            val civ = game.civilizations.firstOrNull { it.civID == origin }
            require(civ != null && (isSpectator(civ) || isSavedNation(game, civ, match))) {
                "Invalid local management identity"
            }
        }
        val viewer = match.localViewingCivId
        if (viewer != null) {
            val civ = game.civilizations.firstOrNull { it.civID == viewer }
            require(civ != null && origin != null && (viewer == origin ||
                game.civilizations.firstOrNull { it.civID == origin }?.let { isSpectator(it) } == true &&
                    isSavedNation(game, civ, match))) {
                "Invalid saved viewing identity"
            }
        }
        for ((id, profile) in match.retainedAgentProfiles) {
            require(id in match.controllers) { "Retained profile refers to an unknown civilization" }
            validateProfileStructure(profile)
        }
    }
}
