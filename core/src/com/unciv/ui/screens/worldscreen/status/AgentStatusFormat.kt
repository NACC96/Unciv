package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.ThinkingLevel
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** The one vocabulary and colour set for an agent match's run state, shared by the agent card,
 * the activity feed, the overview and the turn button. The session's full statusText() stays in logs. */
object AgentStatusFormat {
    fun label(state: AgentRunState?): String = when (state) {
        AgentRunState.PAUSED -> "Paused"
        AgentRunState.PAUSING -> "Pausing…"
        AgentRunState.STARTING -> "Starting"
        AgentRunState.AGENT_DECIDING -> "Thinking"
        AgentRunState.APPLYING -> "Playing"
        AgentRunState.WAITING_HUMAN -> "Your turn"
        AgentRunState.NEEDS_ATTENTION -> "Needs attention"
        AgentRunState.FINISHED -> "Finished"
        null -> "Current turn"
    }

    /** "Paused · America": the short state shown on the card and the legacy turn button. */
    fun status(state: AgentRunState?, civId: String?): String = "${label(state)} · ${civId ?: "Agents"}"

    /** Status dot: info while running, warning when paused, error when it needs attention, muted when idle. */
    fun dotColor(state: AgentRunState?): Color = when (state) {
        AgentRunState.STARTING, AgentRunState.AGENT_DECIDING, AgentRunState.APPLYING -> WorldScreenStyle.info
        AgentRunState.PAUSED, AgentRunState.PAUSING -> WorldScreenStyle.warning
        AgentRunState.NEEDS_ATTENTION -> WorldScreenStyle.error
        else -> WorldScreenStyle.muted
    }

    /** Pause, Resume or Retry when the session offers it; "Pausing…" while a pause drains. */
    fun actionLabel(session: AgentMatchSession, state: AgentRunState?): String? = when {
        session.canPause() -> "Pause"
        session.canResume() -> "Resume"
        session.canRetry() -> "Retry"
        state == AgentRunState.PAUSING -> "Pausing…"
        else -> null
    }

    /** "GPT-6.1 Sol · Medium": the catalog label of the model actually in use, never the display name. */
    fun model(profile: AgentProfile): String =
        "${AgentModelCatalog.findModel(profile.provider, profile.modelId).label} · ${ThinkingLevel.fromWireValue(profile.reasoning).label}"
}
