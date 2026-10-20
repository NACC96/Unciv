package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Disposable
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.screens.worldscreen.WorldHudLayout
import com.unciv.ui.screens.worldscreen.WorldScreen

class StatusButtons(
    val nextTurnButton: NextTurnButton,
    private val worldScreen: WorldScreen? = null
) : Table(), Disposable {
    var autoPlayStatusButton: AutoPlayStatusButton? = null
    var multiplayerStatusButton: MultiplayerStatusButton? = null
    var smallUnitButton: SmallUnitButton? = null
    var agentMatchControlTable: AgentMatchControlTable? = null
    private val padXSpace = 10f
    private val padYSpace = 5f
    private var updating = false

    init {
        add(nextTurnButton)
    }

    fun update(verticalWrap: Boolean) {
        updating = true
        try { layoutButtons(verticalWrap) } finally { updating = false }
    }

    private fun layoutButtons(verticalWrap: Boolean) {
        clear()
        val isAtlas = GameUiStyle.isAtlas
        // Atlas regions can have fractional widths. Rounding a child's fixed width up
        // makes its right edge extend beyond the persistent turn host at 960px.
        setRound(!isAtlas)

        val agentSession = worldScreen?.agentSession
        val isSpectator = agentSession?.isSpectatorMode() ?: false
        // Atlas secondaries are icon-only, so the turn action keeps the width.
        if (isAtlas) smallUnitButton?.let { iconOnly(it) }
        val secondaries = listOfNotNull<Actor>(smallUnitButton, autoPlayStatusButton, multiplayerStatusButton)

        if (agentSession != null) {
            val card = agentMatchControlTable ?: AgentMatchControlTable(agentSession, worldScreen).also {
                agentMatchControlTable = it
                // Sheet toggles and Pause/Resume can change the card outside this pass.
                it.onResize = { if (!updating) { pack(); worldScreen.relayoutHud() } }
            }
            // The agent card hosts the human's turn action; spectators and agent turns get none.
            if (isAtlas) card.setTurnControls(if (agentSession.isHumanInputAllowed()) nextTurnButton else null, secondaries)
            card.update()
        } else {
            agentMatchControlTable?.remove()
            agentMatchControlTable?.dispose()
            agentMatchControlTable = null
        }

        if (isAtlas && worldScreen != null) {
            nextTurnButton.label.wrap = true
            nextTurnButton.labelCell.minWidth(0f).growX()
            val card = agentMatchControlTable
            if (card != null) add(card)
            else {
                val allocated = WorldHudLayout.calculate(worldScreen.stage.width, worldScreen.stage.height,
                    worldScreen.game.settings.fontSizeMultiplier, true).turnAction
                val secondaryWidth = secondaries.sumOf { ((it as? Layout)?.prefWidth ?: it.width).toDouble() + 6.0 }.toFloat()
                add(nextTurnButton).width((allocated.width - secondaryWidth).coerceAtLeast(80f)).minWidth(0f)
                for (secondary in secondaries) add(secondary).padLeft(6f)
            }
        } else if (verticalWrap) {
            agentMatchControlTable?.let {
                add(it).padBottom(padYSpace).right().row()
            }
            if (!isSpectator) {
                add(nextTurnButton)
            }
            smallUnitButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            autoPlayStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
            multiplayerStatusButton?.let {
                row()
                add(it).padTop(padYSpace).right()
            }
        } else {
            multiplayerStatusButton?.let { add(it).padRight(padXSpace).top() }
            autoPlayStatusButton?.let { add(it).padRight(padXSpace).top() }
            smallUnitButton?.let { add(it).padRight(padXSpace).top() }
            agentMatchControlTable?.let { add(it).padRight(padXSpace).top() }
            if (!isSpectator) {
                add(nextTurnButton)
            }
        }
        pack()
    }

    /** Skip/Cycle keep their icon; the caption moves into the tooltip. */
    private fun iconOnly(button: SmallUnitButton) {
        if (button.labelCell.actor != null) {
            button.labelCell.clearActor()
            button.iconCell.padRight(0f)
        }
        button.addTooltip(button.label.text.toString(), 18f)
        button.pack()
    }

    override fun dispose() {
        agentMatchControlTable?.remove()
        agentMatchControlTable?.dispose()
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}
