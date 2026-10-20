package com.unciv.ui.screens.newgamescreen

import com.unciv.ui.components.GameUiStyle
import com.unciv.models.metadata.Player
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.popups.Popup

class NationSettingsPopup(private val table: PlayerPickerTable, private val player: Player) : Popup(table.previousScreen.stage, Scrollability.WithoutButtons, 0.85f) {
    init {
        innerTable.background = if (GameUiStyle.isAtlas) GameUiStyle.panelBackground("Atlas/Setup/NationSettings", 1f)
            else GameUiStyle.background("NewGameScreen/NationSettingsPopup")
        val width = minOf(500f, stageToShowOn.width * 0.85f - 56f).coerceAtLeast(120f)
        addGoodSizedLabel("Edit nation: ${player.chosenCiv}", 22, color = GameUiStyle.ink).width(width).row()
        val error = "".toLabel(GameUiStyle.error, 14).apply { wrap = true }
        add(error).width(width).row()
        val bonuses = StartingBonusForm(player, table.previousScreen.ruleset, width)
        add(bonuses).width(width).row()
        addButton("Save", KeyCharAndCode.RETURN) {
            try {
                player.startingBonuses = bonuses.read()
                table.update()
                close()
            } catch (ex: IllegalArgumentException) { error.setText(ex.message) }
        }.also { GameUiStyle.apply(it.actor, primary = true) }
        addButton("Cancel", KeyCharAndCode.ESC) { close() }.also { GameUiStyle.apply(it.actor) }
        equalizeLastTwoButtonWidths()
        open()
    }
}
