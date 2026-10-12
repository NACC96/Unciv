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
    private val status = "".toLabel(WorldScreenStyle.ink, 14).apply { wrap = true }
    private val model = "".toLabel(WorldScreenStyle.muted, 12).apply { setEllipsis(true) }
    private val actionButton = WorldScreenStyle.button("", primary = true)
    private val activityButton = WorldScreenStyle.button("Activity")
    private val activityFeed = AgentActivityFeed(screen, session)
    private val messagesButton = WorldScreenStyle.button("Messages")
    private val messagesPanel = AgentMessagesPanel(screen, session)

    private val settingsButton = WorldScreenStyle.button("Settings")
    private val settingsPanel = AgentNationSettingsPanel(screen, session)

    fun dispose() { activityFeed.dispose(); messagesPanel.dispose(); settingsPanel.dispose() }

    init {
        pad(8f)
        background = WorldScreenStyle.background("WorldScreen/AgentControls")
        activityButton.onClick { activityFeed.toggle(); update() }
        messagesButton.onClick { messagesPanel.toggle(); update() }
        settingsButton.onClick { settingsPanel.toggle(); update() }
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
        settingsButton.isChecked = settingsPanel.isVisible
        val width = minOf(390f, screen.stage.width - 20f)
        clearChildren()
        val text = Table()
        text.add(status).minWidth(0f).growX().left().row()
        text.add(model).minWidth(0f).growX().left().padTop(4f)
        val buttons = Table()
        if (action != null) buttons.add(actionButton).padRight(6f)
        buttons.add(activityButton).padRight(6f)
        buttons.add(messagesButton).padRight(6f)
        buttons.add(settingsButton)
        if (buttons.prefWidth > width - 16f) {
            buttons.clearChildren()
            val visibleButtons = listOfNotNull(actionButton.takeIf { action != null }, activityButton, messagesButton, settingsButton)
            for ((index, button) in visibleButtons.withIndex()) {
                if (index > 0 && index % 2 == 0) buttons.row()
                buttons.add(button).padRight(6f).padTop(if (index >= 2) 6f else 0f)
            }
        }
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
