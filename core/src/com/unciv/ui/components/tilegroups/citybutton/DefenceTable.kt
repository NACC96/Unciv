package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.utils.Align
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/**
 *  City defence badge, just above the main plaque and below the air unit indicator.
 */
internal class DefenceTable(city: City, selectedCiv: Civilization) : BorderedTable(
    path="WorldScreen/CityButton/DefenceTable",
    defaultBgShape = BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape,
    defaultBgBorder = BaseScreen.skinStrings.roundedTopEdgeRectangleSmallBorderShape
) {
    init {
        pad(2f, 3f, 0f, 3f)

        bgColor = WorldScreenStyle.raised.cpy().apply { a = 0.96f }

        // Match the main plaque: own and hostile states keep a rim that survives zooming out.
        val isOwn = city.civ == selectedCiv
        val isAtWar = !isOwn && city.civ.isAtWarWith(selectedCiv)
        borderSize = if (isOwn || isAtWar) 2f else 1f
        bgBorderColor = when {
            isOwn -> WorldScreenStyle.ownership
            isAtWar -> WorldScreenStyle.error
            else -> WorldScreenStyle.hover
        }

        val cityStrength = CityCombatant(city).getDefendingStrength()
        val cityStrengthLabel = "${Fonts.strength}$cityStrength"
            .toLabel(fontColor = WorldScreenStyle.ink, fontSize = 12, alignment = Align.center)
        add(cityStrengthLabel).grow().center()
    }
}
