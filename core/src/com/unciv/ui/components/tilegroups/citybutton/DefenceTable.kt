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

        borderSize = 1f
        bgColor = WorldScreenStyle.raised.cpy().apply { a = 0.96f }

        bgBorderColor = when {
            city.civ == selectedCiv -> WorldScreenStyle.accent
            city.civ.isAtWarWith(selectedCiv) -> WorldScreenStyle.error
            else -> WorldScreenStyle.hover
        }

        val cityStrength = CityCombatant(city).getDefendingStrength()
        val cityStrengthLabel = "${Fonts.strength}$cityStrength"
            .toLabel(fontColor = WorldScreenStyle.ink, fontSize = 12, alignment = Align.center)
        add(cityStrengthLabel).grow().center()
    }
}
