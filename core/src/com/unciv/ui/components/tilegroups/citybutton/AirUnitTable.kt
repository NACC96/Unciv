package com.unciv.ui.components.tilegroups.citybutton

import com.unciv.logic.city.City
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle

/**
 *  This is the topmost badge showing number of air units above the main city button and defence badge.
 *  @param size Used both as size for the aircraft icon and font size for the [numberOfUnits] label
 */
internal class AirUnitTable(
    city: City,
    numberOfUnits: Int,
    size: Float = 14f
) : BorderedTable(
    path="WorldScreen/CityButton/AirUnitTable",
    defaultBgShape = BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
    defaultBgBorder = BaseScreen.skinStrings.roundedEdgeRectangleSmallShape
) {
    init {
        pad(2f, 10f, 2f, 10f)

        val nation = city.civ.nation
        val textColor = WorldScreenStyle.ink
        borderSize = 1f
        bgColor = WorldScreenStyle.surface.cpy().apply { a = 0.94f }
        bgBorderColor = nation.getOuterColor().cpy().lerp(WorldScreenStyle.muted, 0.35f)

        val aircraftImage = ImageGetter.getImage("OtherIcons/Aircraft")
        aircraftImage.color = WorldScreenStyle.accent
        aircraftImage.setSize(size, size)

        add(aircraftImage).size(size).padRight(4f)
        add(numberOfUnits.tr().toLabel(textColor, size.toInt()))
    }
}
