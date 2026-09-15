package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Table
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
                agentMatchControlTable = AgentMatchControlTable(agentSession)
            } else {
                agentMatchControlTable?.update()
            }
        } else {
            agentMatchControlTable = null
        }

        if (verticalWrap) {
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
        autoPlayStatusButton?.dispose()
        multiplayerStatusButton?.dispose()
    }
}
