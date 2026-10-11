package com.unciv.logic.agents

import com.unciv.logic.GameInfo
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * Desktop-owned agent match session (AI_AGENT_PLAN.md §3.1, §11).
 * Implemented by the desktop module ([DesktopAgentMatch]); core UI talks to
 * this interface so no platform code leaks into shared screens. Created
 * through [AgentMatchSessionFactory] after new-game validation passes.
 */
interface AgentMatchSession {
    val matchId: String

    /** Immutable metadata from the host's committed post-construction state, captured under its lock. */
    fun getCommittedMatchStateJson(): String? = null

    /** One-line status for the turn button: phase, active civ/provider, revision. */
    fun statusText(): String

    /** Whether human map/unit controls may act right now. */
    fun isHumanInputAllowed(): Boolean

    /**
     * End-turn button/keyboard pressed. Returns true when consumed by the
     * match (human turn end routed to the coordinator); false to fall
     * through to the legacy path. Never ends an agent's turn out from under
     * its provider (takeover is a separate explicit control).
     */
    fun onNextTurnPressed(): Boolean

    /**
     * Human gameplay mutations through the coordinator (plan §3.2): unit
     * orders, city management, research, policies, diplomacy, trades,
     * mandatory decisions. Commands execute in order under the host lock,
     * each stamped with the current revision; the first failure aborts the
     * rest. Returns null when all applied, or a human-readable error.
     * When the session is present but the active civ is an agent,
     * implementations must reject with an error rather than fall through
     * to the legacy path.
     */
    fun executeHumanAction(
        buildCommands: (expectedRevision: Int) -> List<(Int) -> AgentCommand>
    ): String?

    /** Stop scheduling when navigation leaves this match; an active provider may finish normally. */
    fun onMatchLeft() = pause()

    /** Reopen the match's controls without automatically resuming its agents. */
    fun onMatchEntered() {}

    /** Detach from the screen; an active provider may finish before its supervisor shuts down. */
    fun close()

    // Platform-independent match controls (AI_AGENT_PLAN.md §9, §11)
    fun canPause(): Boolean
    fun canResume(): Boolean
    fun canRetry(): Boolean

    fun pause()
    fun resume()
    fun retry()

    // Status inspection
    fun getRunState(): AgentRunState
    fun getActiveCivId(): String?
    fun getActiveAgentProfile(): AgentProfile?
    /** Identity for activity from an earlier civilization, independent of the active turn. */
    fun getAgentProfile(civId: String): AgentProfile? =
        if (civId == getActiveCivId()) getActiveAgentProfile() else null
    fun getFailureReason(): String?
    fun isSpectatorMode(): Boolean
    /** Live provider activity, available while a turn is still running. */
    fun getActivity(): List<AgentActivityEntry> = emptyList()
    fun getActivityDetails(id: String): String = ""
    fun getActivityDirectory(): String? = null
    /** Private mail for the attached viewer; spectators may inspect all conversations. */
    fun getMessageContacts(): List<MessageContact> = emptyList()
    fun getMessages(): List<DiplomaticMessage> = emptyList()
    /** Saves off the rendering thread. Callback runs on GL; null means durably queued. */
    fun sendMessage(targetCivId: String, text: String, onComplete: (String?) -> Unit) {
        onComplete("Messaging is unavailable for this match")
    }
    fun startAutomaticPlay()
}

object AgentMatchSessionFactory {
    /** Set once by the desktop launcher; null on platforms without hosting. */
    var creator: ((game: GameInfo, match: AgentMatchState, setup: AgentMatchSetup, screen: WorldScreen) -> AgentMatchSession)? = null

    /** Restores an exact ordinary save; this callback must construct an idle, paused host. */
    var restorer: ((game: GameInfo, match: AgentMatchState, setup: AgentMatchSetup, screen: WorldScreen) -> AgentMatchSession)? = null

    fun requireRestoreSupport() {
        if (restorer == null)
            throw com.unciv.logic.UncivShowableException("This platform cannot host saved agent matches.")
    }

    fun create(game: GameInfo, setup: AgentMatchSetup, screen: WorldScreen): AgentMatchSession? {
        if (setup.slots.isEmpty()) return null
        val creator = creator ?: return null
        val session = creator(game, setup.buildMatchState(game), setup, screen)
        return attachCapturedState(game, session)
    }

    private fun attachCapturedState(game: GameInfo, session: AgentMatchSession): AgentMatchSession {
        try {
            val captured = session.getCommittedMatchStateJson()
            if (captured.isNullOrBlank())
                throw com.unciv.logic.UncivShowableException("Agent host did not capture its committed match metadata.")
            game.agentMatchStateJson = captured
            return session
        } catch (ex: Exception) {
            session.close()
            throw ex
        }
    }

    fun restore(game: GameInfo, match: AgentMatchState, screen: WorldScreen): AgentMatchSession {
        requireRestoreSupport()
        val session = restorer!!(game, match.deepCopy(), AgentOrdinarySave.setupFor(game, match), screen)
        return attachCapturedState(game, session)
    }
}
