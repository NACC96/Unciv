package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.logic.city.City
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
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
    private var chip: Drawable? = null

    init {
        pad(2f, 10f, 2f, 10f)
        if (GameUiStyle.isAtlas) {
            pad(1f, 6f, 1f, 6f)
            chip = cityChip("WorldScreen/CityButton/AirUnitChip", WorldScreenStyle.muted.cpy().apply { a = 0.5f })
        }

        val nation = city.civ.nation
        val textColor = WorldScreenStyle.ink
        borderSize = 1f
        bgColor = WorldScreenStyle.surface.cpy().apply { a = 0.94f }
        bgBorderColor = nation.getOuterColor().cpy().lerp(WorldScreenStyle.muted, 0.35f)

        val aircraftImage = ImageGetter.getImage("OtherIcons/Aircraft")
        aircraftImage.color = WorldScreenStyle.accent
        aircraftImage.setSize(size, size)

        add(aircraftImage).size(size).padRight(if (chip != null) 2f else 4f)
        add(numberOfUnits.tr().toLabel(textColor, size.toInt()))
    }

    override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
        chip?.let { drawChip(it, batch, parentAlpha, x, y) } ?: super.drawBackground(batch, parentAlpha, x, y)
    }
}
