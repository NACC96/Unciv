package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.AgentRunState
import com.unciv.logic.agents.ThinkingLevel
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/** Compact match controls, independent of human turn authority. */
class AgentMatchControlTable(
    private val session: AgentMatchSession,
    private val screen: WorldScreen
) : Table(BaseScreen.skin) {
    private val status = "".toLabel(WorldScreenStyle.ink, 13).apply { wrap = true }
    private val model = "".toLabel(WorldScreenStyle.muted, 11).apply { setEllipsis(true) }
    private val actionButton = WorldScreenStyle.button("")
    private val activityButton = WorldScreenStyle.button("Activity")
    private val activityFeed = AgentActivityFeed(screen, session)
    private val messagesButton = WorldScreenStyle.button("Messages")
    private val messagesPanel = AgentMessagesPanel(screen, session)

    fun dispose() { activityFeed.dispose(); messagesPanel.dispose() }

    init {
        pad(8f)
        background = WorldScreenStyle.background("WorldScreen/AgentControls")
        activityButton.onClick { activityFeed.toggle(); update() }
        messagesButton.onClick { messagesPanel.toggle(); update() }
        actionButton.onClick {
            when {
                session.canPause() -> session.pause()
                session.canResume() -> session.resume()
                session.canRetry() -> session.retry()
            }
            update()
        }
        update()
    }

    fun update() {
        val state = session.getRunState()
        val stateText = when (state) {
            AgentRunState.PAUSED -> "Paused"
            AgentRunState.STARTING -> "Starting"
            AgentRunState.AGENT_DECIDING -> "Thinking"
            AgentRunState.APPLYING -> "Playing"
            AgentRunState.WAITING_HUMAN -> "Your turn"
            AgentRunState.PAUSING -> "Finishing turn…"
            AgentRunState.NEEDS_ATTENTION -> "Needs attention"
            AgentRunState.FINISHED -> "Finished"
        }
        status.setText("$stateText · ${session.getActiveCivId() ?: "Agents"}")
        status.color = when (state) {
            AgentRunState.NEEDS_ATTENTION -> WorldScreenStyle.error
            AgentRunState.PAUSING, AgentRunState.PAUSED -> WorldScreenStyle.warning
            else -> WorldScreenStyle.ink
        }
        val profile = session.getActiveAgentProfile()
        model.setText(if (state == AgentRunState.NEEDS_ATTENTION)
            session.getFailureReason() ?: "Provider failure"
        else if (profile != null) {
            val effort = ThinkingLevel.fromWireValue(profile.reasoning).label
            "${AgentModelCatalog.findModel(profile.provider, profile.modelId).label} · $effort"
        } else session.statusText())

        val action = when {
            session.canPause() -> "Pause"
            session.canResume() -> "Resume"
            session.canRetry() -> "Retry"
            state == AgentRunState.PAUSING -> "Pausing…"
            else -> null
        }
        actionButton.setText(action ?: "")
        WorldScreenStyle.setEnabled(actionButton, action != null && state != AgentRunState.PAUSING)
        activityButton.isChecked = activityFeed.isVisible
        messagesButton.isChecked = messagesPanel.isVisible
        val width = minOf(390f, screen.stage.width - 20f)
        clearChildren()
        val text = Table()
        text.add(status).growX().left().row()
        text.add(model).growX().left().padTop(3f)
        val buttons = Table()
        if (action != null) buttons.add(actionButton).padRight(6f)
        buttons.add(activityButton).padRight(6f)
        buttons.add(messagesButton)
        val narrow = width - buttons.prefWidth - 26f < 120f
        if (narrow) {
            add(text).width(width - 16f).row()
            add(buttons).right().padTop(8f)
        } else {
            add(text).width((width - buttons.prefWidth - 26f).coerceAtLeast(80f)).left().padRight(10f)
            add(buttons).right()
        }
        pack()
    }
}
