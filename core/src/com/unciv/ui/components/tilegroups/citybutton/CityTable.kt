package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.surroundWithThinCircle
import com.unciv.ui.components.extensions.toGroup
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.utils.DebugUtils
import com.unciv.view.CityView

/**
 *  This is the main "button" inside [CityButton], the one with a rounded edge look,
 *  excluding the air units / defence badges above and influence / status indicators below.
 *
 *  It's the only touchable component of `CityButton`.
 *
 *  Components (* only if you're allowed to see - you're owner, spectator, debug flag):
 *  - population number
 *  - pop growth bar (vertical)*
 *  - capital indicator icon
 *  - name
 *  - religion icon (unless [forPopup] is `true`)
 *  - construction turns to completion* ('∞' for perpetual constructions, "-" for idle)
 *  - construction progress bar* (showing 0% for perpetual constructions)
 *  - construction icon* (perpetual ones show empty space instead)
 *  - nation or city-state icon (unless yours)
 */
internal class CityTable(
    city: CityView,
    forPopup: Boolean = false
) : BorderedTable(
    path = "WorldScreen/CityButton/IconTable",
    defaultBgShape = BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
    defaultBgBorder = BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape
) {
    init {
        isTransform = false
        touchable = Touchable.enabled
        pad(0f, 4f, 0f, 4f) // outer pad left and right

        val selectedCiv = GUI.getSelectedPlayer()
        val viewingCiv = city.getViewingCiv()
        // Own and hostile cities keep a heavy rim that survives zooming out; nation identity
        // lives in the icon disc, so neutral rims stay quiet for every nation palette.
        val isAtWar = !city.belongsTo(selectedCiv) && city.isAtWarWith(selectedCiv)
        when {
            city.belongsTo(selectedCiv) -> {
                borderOnTop = true
                borderSize = 3f
                bgBorderColor = WorldScreenStyle.ownership
            }
            isAtWar -> {
                borderSize = 3f
                bgBorderColor = WorldScreenStyle.error
            }
            else -> {
                borderSize = 1.5f
                bgBorderColor = WorldScreenStyle.muted.cpy().apply { a = 0.55f }
            }
        }
        bgColor = WorldScreenStyle.surface.cpy().apply { a = 0.94f }

        // War is also marked by shape, so the state does not depend on hue alone.
        if (isAtWar)
            add(ImageGetter.getImage(NotificationIcon.War).apply { color = WorldScreenStyle.error })
                .size(16f).padLeft(4f)

        val isShowDetailedInfo = DebugUtils.VISIBLE_MAP
                || city.belongsTo(selectedCiv)
                || viewingCiv.isSpectator()

        addCityPopNumber(city)

        if (isShowDetailedInfo)
            addCityGrowthBar(city)

        addCityText(city, forPopup)

        if (isShowDetailedInfo)
            addCityConstruction(city)

        if (!city.belongsTo(viewingCiv))
            addCivIcon(city)
    }

    private fun addCityPopNumber(city: CityView) {
        val textColor = WorldScreenStyle.ink
        val popLabel = city.getPopulationCount().tr()
            .toLabel(fontColor = textColor, fontSize = 18, alignment = Align.center)
        add(popLabel).minWidth(26f)
    }

    private fun addCityGrowthBar(city: CityView) {
        val table = Table()
        fun calcGrowthPercentage(food: Int) = (food.toFloat() / city.getFoodToNextPopulation()).coerceIn(0f, 1f)
        val isGrowing = city.isGrowing()
        val isStarving = city.isStarving()

        val growthPercentage = calcGrowthPercentage(city.getFoodStored())
        val growthBar = ImageGetter.getProgressBarVertical(
            4f, 30f,
            if (isStarving) 1.0f else growthPercentage,
            if (isStarving) Color.RED else CityButton.ColorGrowth,
            ImageGetter.CHARCOAL, 1f
        )
        growthBar.color.a = 0.8f
        if (isGrowing) {
            val nextTurnPercentage = calcGrowthPercentage(city.foodForNextTurn() + city.getFoodStored())
            growthBar.setSemiProgress(CityButton.ColorGrowth.cpy().darken(0.4f), nextTurnPercentage, 1f)
        }

        val turnLabelText = when {
            isGrowing -> {
                val turnsToGrowth = city.getNumTurnsToNewPopulation()
                if (turnsToGrowth != null && turnsToGrowth < 100) turnsToGrowth.tr() else Fonts.infinity.toString()
            }
            isStarving -> {
                val turnsToStarvation = city.getNumTurnsToStarvation()
                if (turnsToStarvation != null && turnsToStarvation < 100) turnsToStarvation.tr() else Fonts.infinity.toString()
            }
            else -> "-"
        }
        val textColor = WorldScreenStyle.muted
        val turnLabel = turnLabelText.toLabel(fontColor = textColor, fontSize = 13)

        table.add(growthBar).padRight(2f)
        table.add(turnLabel).expandY().bottom()
        add(table).minWidth(6f).padLeft(2f)
    }

    private fun addCityText(city: CityView, forPopup: Boolean) {
        val textColor = WorldScreenStyle.ink
        val table = Table().apply { isTransform = false }

        if (city.isCapital()) {
            val capitalIcon = when {
                city.isCityState() -> ImageGetter.getNationIcon("CityState")
                    .apply { color = textColor }
                else -> ImageGetter.getImage("OtherIcons/Capital")
            }
            table.add(capitalIcon).size(20f).padRight(5f)
        }

        val cityName = city.name.toLabel(fontColor = textColor, alignment = Align.center, hideIcons = true)
        val nameWidth = cityName.prefWidth.coerceAtMost(MAX_NAME_WIDTH)
        if (cityName.prefWidth > nameWidth) cityName.clampToLines(nameWidth, MAX_NAME_LINES)
        table.add(cityName).width(nameWidth).growY().center().padTopDescent()

        if (!forPopup) {
            val cityReligion = city.getMajorityReligion()
            if (cityReligion != null) {
                val religionImage = ImageGetter.getReligionIcon(cityReligion.getIconName()).apply {
                    color = textColor }.toGroup(20f)
                table.add(religionImage).size(20f).padLeft(5f)
            }
        }

        table.pack()
        add(table)
            .minHeight(34f)
            .padLeft(10f)
            .padRight(10f)
            .expandY().center()
    }

    private fun addCityConstruction(city: CityView) {
        val textColor = WorldScreenStyle.muted

        val cityConstructions = city.constructions
        val cityCurrentConstruction = cityConstructions.getCurrentConstruction()

        val progressTable = Table()

        // There's two different "idle" states: No entry in the queue and PerpetualConstruction.idle queued.
        // getCurrentConstruction does not distinuish these, only currentConstructionName does. And we want the icon to only show in the second case.
        var nextTurnPercentage = 0f
        var percentage = 0f
        val icon = if (cityConstructions.currentConstructionName().isEmpty()) null
            else ImageGetter.getConstructionPortrait(cityCurrentConstruction.name, 24f)
        val turns = when (cityCurrentConstruction) {
            PerpetualConstruction.Idle -> {
                "-"
            }
            is PerpetualConstruction -> {
                Fonts.infinity.toString()
            }
            else -> {
                cityCurrentConstruction as INonPerpetualConstruction
                val turnsToConstruction = cityConstructions.turnsToConstruction(cityCurrentConstruction.name)
                val workDone = cityConstructions.getWorkDone(cityCurrentConstruction.name).toFloat()
                val cost = city.getConstructionProductionCost(cityCurrentConstruction)
                fun getPercentage(done: Float) = (done / cost).coerceIn(0f, 1f)
                nextTurnPercentage = getPercentage(workDone + city.getCurrentCityStats().production)
                percentage = getPercentage(workDone)
                if (turnsToConstruction < 100) turnsToConstruction.tr() else "-"
            }
        }

        val productionBar = ImageGetter.getProgressBarVertical(4f, 30f, percentage,
            CityButton.ColorConstruction, ImageGetter.CHARCOAL, 1f)
        productionBar.setSemiProgress(CityButton.ColorConstruction.cpy().darken(0.4f), nextTurnPercentage, 1f)
        productionBar.color.a = 0.8f

        progressTable.add(turns.toLabel(textColor, 13)).expandY().bottom()
        progressTable.add(productionBar).padLeft(2f)

        add(progressTable).minWidth(6f).padRight(2f)
        add(icon).minWidth(26f)
    }

    private fun addCivIcon(city: CityView) {
        val icon = when {
            city.isMajorCiv() -> ImageGetter.getNationIcon(city.getNationName())
            else -> ImageGetter.getImage("CityStateIcons/" + city.getCityStateTypeName())
        }
        // The nation's own inner/outer pair is designed for contrast; a thin ring
        // separates dark outer colours (all city-states) from the charcoal plaque.
        icon.color = city.getCivInnerColor().cpy()
        val disc = icon.surroundWithCircle(20f, color = city.getNationOuterColor().cpy())
            .surroundWithThinCircle(WorldScreenStyle.muted)

        add(disc).minWidth(26f)
    }

    companion object {
        private const val MAX_NAME_WIDTH = 220f
        private const val MAX_NAME_LINES = 2
    }
}

/** Map labels stay at most [maxLines] tall over the terrain; the city screen keeps the full name. */
internal fun Label.clampToLines(maxWidth: Float, maxLines: Int) {
    val full = text.toString()
    wrap = true
    width = maxWidth
    fun lineCount(): Int {
        layout()
        return glyphLayout.runs.map { it.y }.distinct().size
    }
    if (lineCount() <= maxLines) return
    // Fonts maps U+2026 to the range symbol, so the ellipsis is spelled out.
    fun truncated(length: Int) = full.take(length).trimEnd() + "..."
    var low = 0
    var high = full.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        setText(truncated(mid))
        if (lineCount() <= maxLines) low = mid else high = mid - 1
    }
    // Prefer ending on a whole word when that keeps most of the second line.
    val wordEnd = full.take(low).lastIndexOf(' ')
    setText(truncated(if (wordEnd > low * 0.8f) wordEnd else low))
}
