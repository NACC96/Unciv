package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen

/** Activity and the ribbon share saved order and the same private-view boundary. */
object NationTurnRoster {
    data class Entry(val civ: Civilization, val ordinal: Int, val marker: String)
    fun read(game: GameInfo, viewer: Civilization, active: String?): List<Entry> {
        val permitted = game.civilizations.filter { it.isMajorCiv() && (viewer.isSpectator() || it.civID == viewer.civID) }
        val living = permitted.filterNot { it.isDefeated() }
        val current = living.indexOfFirst { it.civID == active }
        val previous = if (current >= 0 && living.size > 1) living[(current + living.size - 1) % living.size].civID else null
        val next = if (current >= 0 && living.size > 1) living[(current + 1) % living.size].civID else null
        return permitted.mapIndexed { index, civ -> Entry(civ, index + 1, when {
            civ.civID == active -> "Current"
            civ.isDefeated() -> "Eliminated"
            civ.civID == previous -> "Previous in order"
            civ.civID == next -> "Next in order"
            else -> ""
        }) }
    }
}

/** Scrollable inspection controls. Selection never calls a controller/takeover API. */
class NationTurnRibbon : Table() {
    private val entries = Table()
    val scroll = AutoScrollPane(entries).apply { setScrollingDisabled(false, true); setOverscroll(false, false) }
    private var shown = emptyList<String>()
    init {
        name = "NationTurnRibbon"
        add(scroll).grow().minWidth(0f)
        GameUiStyle.applyWidget(scroll)
    }
    fun update(worldScreen: WorldScreen) {
        val roster = NationTurnRoster.read(worldScreen.gameInfo, worldScreen.viewingCiv,
            worldScreen.agentSession?.getActiveCivId() ?: worldScreen.gameInfo.currentPlayer)
        val signature = roster.map { "${it.civ.civID}:${it.marker}:${it.civ === worldScreen.selectedCiv}" }
        if (shown == signature) return
        shown = signature
        entries.clearChildren()
        for (entry in roster) {
            val id = entry.civ.civID
            val button = GameUiStyle.button("${entry.ordinal}. $id")
            button.name = "NationTurnEntry:$id"
            button.label.setEllipsis(true)
            button.labelCell.minWidth(0f).growX()
            button.add(ImageGetter.getNationPortrait(entry.civ.nation, 24f)).size(24f).padLeft(6f)
            button.row()
            button.add(entry.marker.toLabel(GameUiStyle.muted, GameUiStyle.Type.caption))
                .colspan(2).left()
            button.isChecked = entry.civ === worldScreen.selectedCiv
            button.onActivation {
                // Resolve against the current committed snapshot, never retain an old civ instance.
                val current = NationTurnRoster.read(worldScreen.gameInfo, worldScreen.viewingCiv,
                    worldScreen.gameInfo.currentPlayer).firstOrNull { it.civ.civID == id } ?: return@onActivation
                worldScreen.inspectNation(current.civ.civID)
            }
            entries.add(button).width(210f * worldScreen.game.settings.fontSizeMultiplier).padRight(6f)
        }
        entries.pack()
    }
}
