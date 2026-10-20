package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.screens.basescreen.BaseScreen

class PlayerReadyScreen(worldScreen: WorldScreen, val retainedWorldInStack: Boolean = false) : BaseScreen() {
    init {
        val table = Table()
        table.touchable = Touchable.enabled
        val curCiv = worldScreen.selectedGameView.civView
        table.background = skinStrings.getUiBackground(
            "PlayerReadyScreen/Background",
            tintColor = curCiv.getOuterColor()
        )

        if (GameUiStyle.isAtlas) {
            // The entire screen stays opaque. This handover must never expose the previous player's map.
            table.background = GameUiStyle.background("PlayerReadyScreen/Privacy", GameUiStyle.inset)
            val card = Table().apply {
                background = GameUiStyle.panelBackground("PlayerReadyScreen/Card", 1f)
                pad(20f)
                add(AtlasWidgets.header("[${curCiv.civName}] ready?")).growX().row()
                add(GameUiStyle.button("Continue", primary = true).apply {
                    touchable = Touchable.disabled
                }).growX().minHeight(48f).row()
            }
            table.add(card).width((stage.width - 40f).coerceAtMost(460f))
        } else table.add("[${curCiv.civName}] ready?".toLabel(curCiv.getInnerColor(), Constants.headingFontSize))

        table.onActivation {
            if (retainedWorldInStack) game.popScreen() else game.replaceCurrentScreen{ worldScreen }
        }
        // Doing this separately instead of passing the binding to onActivation avoids the tooltip
        table.keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        table.setFillParent(true)
        stage.addActor(table)
    }
}
