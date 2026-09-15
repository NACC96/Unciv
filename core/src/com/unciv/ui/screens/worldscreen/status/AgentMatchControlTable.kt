package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentRunState
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * UI controls for agent matches (AI_AGENT_PLAN.md §9, §11.1).
 * Displays active civilization, agent display name, provider/model, and current activity.
 * Exposes Pause, Resume, and Retry controls decoupled from human turn authority.
 */
class AgentMatchControlTable(
    private val session: AgentMatchSession
) : Table(BaseScreen.skin) {

    private val statusTable = Table(skin)
    private val actionButton: TextButton = "".toTextButton()

    init {
        pad(6f)
        background = BaseScreen.skinStrings.getUiBackground(
            "NewGameScreen/PlayerPickerTable/PlayerTable",
            tintColor = BaseScreen.skinStrings.skinConfig.baseColor.darken(0.85f)
        )

        add(statusTable).left().padRight(8f)
        add(actionButton).right().row()

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
        statusTable.clear()

        val state = session.getRunState()
        val civId = session.getActiveCivId() ?: "—"
        val profile = session.getActiveAgentProfile()

        val stateText = when (state) {
            AgentRunState.PAUSED -> "PAUSED"
            AgentRunState.STARTING -> "STARTING"
            AgentRunState.AGENT_DECIDING -> "DECIDING"
            AgentRunState.APPLYING -> "APPLYING"
            AgentRunState.WAITING_HUMAN -> "PLAYER TURN"
            AgentRunState.PAUSING -> "PAUSING…"
            AgentRunState.NEEDS_ATTENTION -> "NEEDS ATTENTION"
            AgentRunState.FINISHED -> "FINISHED"
        }

        val stateColor = when (state) {
            AgentRunState.AGENT_DECIDING, AgentRunState.APPLYING, AgentRunState.STARTING -> Color.GREEN
            AgentRunState.PAUSED -> Color.YELLOW
            AgentRunState.PAUSING -> Color.ORANGE
            AgentRunState.NEEDS_ATTENTION -> Color.RED
            AgentRunState.WAITING_HUMAN -> Color.CYAN
            AgentRunState.FINISHED -> Color.LIGHT_GRAY
        }

        val headerText = "● $stateText · $civId"
        val headerLabel = headerText.toLabel(stateColor, 14)
        statusTable.add(headerLabel).left().row()

        val detailText = if (profile != null) {
            val thinking = profile.reasoning?.let { " [$it]" } ?: ""
            "${profile.displayName} (${profile.provider}/${profile.modelId}$thinking)"
        } else if (state == AgentRunState.NEEDS_ATTENTION) {
            session.getFailureReason() ?: "Provider failure"
        } else {
            session.statusText()
        }
        val detailLabel = detailText.toLabel(Color.LIGHT_GRAY, 12)
        detailLabel.wrap = true
        statusTable.add(detailLabel).width(240f).left().row()

        when {
            session.canPause() -> {
                actionButton.setText("Pause")
                actionButton.color = Color.YELLOW
                actionButton.isEnabled = true
                actionButton.isVisible = true
            }
            session.canResume() -> {
                actionButton.setText("Resume")
                actionButton.color = Color.GREEN
                actionButton.isEnabled = true
                actionButton.isVisible = true
            }
            session.canRetry() -> {
                actionButton.setText("Retry")
                actionButton.color = Color.ORANGE
                actionButton.isEnabled = true
                actionButton.isVisible = true
            }
            state == AgentRunState.PAUSING -> {
                actionButton.setText("Pausing…")
                actionButton.color = Color.GRAY
                actionButton.isEnabled = false
                actionButton.isVisible = true
            }
            else -> {
                actionButton.isVisible = false
            }
        }

        pack()
    }
}
