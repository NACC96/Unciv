package com.unciv.ui.screens.worldscreen.topbar

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/** The nation switcher, Activity and inspection share saved order and the same private-view boundary. */
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
            // With two living nations the other one plays next, so "next" wins over "previous".
            civ.civID == next -> "Next in order"
            civ.civID == previous -> "Previous in order"
            else -> ""
        }) }
    }
}
