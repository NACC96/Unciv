package com.unciv.logic.agents

import com.unciv.logic.GameInfo

/**
 * Desktop-owned agent match session (AI_AGENT_PLAN.md §3.1, §11).
 * Implemented by the desktop module ([DesktopAgentMatch]); core UI talks to
 * this interface so no platform code leaks into shared screens. Created
 * through [AgentMatchSessionFactory] after new-game validation passes.
 */
interface AgentMatchSession {
    val matchId: String
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
    /** Detach from the screen; shuts the supervisor down. */
    fun close()
}

object AgentMatchSessionFactory {
    /** Set once by the desktop launcher; null on platforms without hosting. */
    var creator: ((game: GameInfo, match: AgentMatchState, setup: AgentMatchSetup) -> AgentMatchSession)? = null

    fun create(game: GameInfo, setup: AgentMatchSetup): AgentMatchSession? {
        if (setup.slots.isEmpty()) return null
        val creator = creator ?: return null
        return creator(game, setup.buildMatchState(game), setup)
    }
}
