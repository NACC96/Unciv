package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.input.onActivation
import com.badlogic.gdx.utils.Disposable
import com.unciv.ui.screens.worldscreen.WorldScreen

class StatusButtons(
    val nextTurnButton: NextTurnButton,
    private val worldScreen: WorldScreen? = null
) : Table(), Disposable {
    var autoPlayStatusButton: AutoPlayStatusButton? = null
    var multiplayerStatusButton: MultiplayerStatusButton? = null
    var smallUnitButton: SmallUnitButton? = null
    var agentMatchControlTable: AgentMatchControlTable? = null
    private var controlsOpen = false
    private val controlsButton = com.unciv.ui.components.GameUiStyle.button("Control").apply {
        onActivation {
            controlsOpen = !controlsOpen
            agentMatchControlTable?.isVisible = controlsOpen
        }
    }
    private val padXSpace = 10f
    private val padYSpace = 5f
    
    init {
        add(nextTurnButton)
    }
    
    fun update(verticalWrap: Boolean) {
        clear()

        val agentSession = worldScreen?.agentSession
        val isSpectator = agentSession?.isSpectatorMode() ?: false

        if (agentSession != null) {
            if (agentMatchControlTable == null) {
                agentMatchControlTable = AgentMatchControlTable(agentSession, worldScreen)
            } else {
                agentMatchControlTable?.update()
            }
        } else {
            agentMatchControlTable?.remove()
            agentMatchControlTable?.dispose()
            agentMatchControlTable = null
        }

        if (com.unciv.ui.components.GameUiStyle.isAtlas && worldScreen != null) {
            val allocated = com.unciv.ui.screens.worldscreen.WorldHudLayout.calculate(worldScreen.stage.width,
                worldScreen.stage.height, worldScreen.game.settings.fontSizeMultiplier, true).turnAction
            nextTurnButton.label.wrap = true
            nextTurnButton.labelCell.minWidth(0f).growX()
            val controlWidth = if (agentMatchControlTable != null) controlsButton.prefWidth + 6f else 0f
            add(nextTurnButton).width((allocated.width - controlWidth).coerceAtLeast(80f)).minWidth(0f)
            agentMatchControlTable?.let {
                add(controlsButton).padLeft(6f)
                if (it.parent !== worldScreen.stage.root) worldScreen.stage.addActor(it)
                it.isVisible = controlsOpen
                val regions = com.unciv.ui.screens.worldscreen.WorldHudLayout.calculate(worldScreen.stage.width,
                    worldScreen.stage.height, worldScreen.game.settings.fontSizeMultiplier, true)
                it.setPosition((worldScreen.stage.width - it.width - 8f).coerceAtLeast(0f),
                    (regions.header.y - it.height - 8f).coerceAtLeast(regions.turnAction.y + regions.turnAction.height))
            }
            smallUnitButton?.let { add(it).padLeft(6f) }
            autoPlayStatusButton?.let { add(it).padLeft(6f) }
            multiplayerStatusButton?.let { add(it).padLeft(6f) }
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

    override fun dispose() {
        agentMatchControlTable?.remove()
        agentMatchControlTable?.dispose()
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}
