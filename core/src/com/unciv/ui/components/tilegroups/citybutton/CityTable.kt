package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
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
 *  On the Atlas skin the map variant is a compact one-row pill (see [addBanner]); the popup
 *  variant ([forPopup]) and other skins keep the full plaque.
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
    /** Atlas map banner: one compact pill. The popup and other skins keep the full plaque. */
    private val compact = GameUiStyle.isAtlas && !forPopup
    private var bannerFill: Drawable? = null
    private var bannerRim: Drawable? = null

    init {
        isTransform = false
        touchable = Touchable.enabled
        if (compact) addBanner(city) else addPlaque(city, forPopup)
    }

    private fun addPlaque(city: CityView, forPopup: Boolean) {
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

    /**
     *  One row, [BANNER_HEIGHT] tall: pop · capital · name · religion · construction + turns · nation disc.
     *  Growth and production are thin bars along the bottom inner edge.
     */
    private fun addBanner(city: CityView) {
        val selectedCiv = GUI.getSelectedPlayer()
        val viewingCiv = city.getViewingCiv()
        val isOwn = city.belongsTo(selectedCiv)
        val isAtWar = !isOwn && city.isAtWarWith(selectedCiv)
        // A 2-unit rim marks own and hostile cities; neutral rims stay a quiet hairline.
        borderOnTop = isOwn
        borderSize = if (isOwn || isAtWar) 2f else 1f
        bgBorderColor = when {
            isOwn -> WorldScreenStyle.ownership
            isAtWar -> WorldScreenStyle.error
            else -> WorldScreenStyle.muted.cpy().apply { a = 0.4f }
        }
        bgColor = WorldScreenStyle.surface.cpy().apply { a = 0.88f }
        bannerFill = WorldScreenStyle.pill("WorldScreen/CityButton/Banner", bgColor)
        bannerRim = WorldScreenStyle.pill("WorldScreen/CityButton/BannerRim", Color.CLEAR, bgBorderColor)
        pad(0f, 8f, 0f, 4f)

        val ink = WorldScreenStyle.ink
        if (isAtWar)
            add(ImageGetter.getImage(NotificationIcon.War).apply { color = WorldScreenStyle.error })
                .size(12f).padRight(4f)

        val popCell = add(city.getPopulationCount().tr().toLabel(ink, 14, Align.center))
            .minWidth(10f).height(BANNER_HEIGHT)

        if (city.isCapital()) {
            val capitalIcon = if (city.isCityState()) ImageGetter.getNationIcon("CityState").apply { color = ink }
                else ImageGetter.getImage("OtherIcons/Capital")
            add(capitalIcon).size(12f).padLeft(4f)
        }

        val cityName = city.name.toLabel(fontColor = ink, fontSize = 15, hideIcons = true)
        val nameWidth = cityName.prefWidth.coerceAtMost(BANNER_NAME_WIDTH)
        if (cityName.prefWidth > nameWidth) {
            cityName.clampToLines(nameWidth, 1)
            addTooltip(city.name, 20f, hideIcons = true)
        }
        var textEnd: Cell<*> = add(cityName).width(nameWidth).padTopDescent().padLeft(4f).padRight(2f)

        val cityReligion = city.getMajorityReligion()
        if (cityReligion != null) {
            val religionImage = ImageGetter.getReligionIcon(cityReligion.getIconName())
                .apply { color = ink }.toGroup(14f)
            textEnd = add(religionImage).size(14f).padLeft(4f)
        }

        val isShowDetailedInfo = DebugUtils.VISIBLE_MAP || isOwn || viewingCiv.isSpectator()
        val constructionCell = if (isShowDetailedInfo) addBannerConstruction(city) else null

        if (!city.belongsTo(viewingCiv)) {
            val icon = if (city.isMajorCiv()) ImageGetter.getNationIcon(city.getNationName())
                else ImageGetter.getImage("CityStateIcons/" + city.getCityStateTypeName())
            icon.color = city.getCivInnerColor().cpy()
            val disc = icon.surroundWithCircle(16f, color = city.getNationOuterColor().cpy())
                .surroundWithThinCircle(WorldScreenStyle.muted)
            add(disc).padLeft(6f)
        } else padRight(10f)

        pack()
        if (!isShowDetailedInfo) return
        bannerBar(popCell.actorX - 2f, textEnd.actorX + textEnd.actorWidth, growthProgress(city))
        if (constructionCell != null)
            bannerBar(constructionCell.actorX, constructionCell.actorX + constructionCell.actorWidth, constructionProgress(city))
    }

    private fun addBannerConstruction(city: CityView): Cell<Table> {
        val constructions = city.constructions
        val current = constructions.getCurrentConstruction()
        val group = Table()
        if (constructions.currentConstructionName().isNotEmpty())
            group.add(ImageGetter.getConstructionPortrait(current.name, 16f)).size(16f)
        group.add(constructionProgress(city).turns.toLabel(WorldScreenStyle.muted, 11)).padLeft(2f).bottom()
        return add(group).padLeft(6f)
    }

    /** A 2-unit progress bar along the bottom inner edge, clear of the rounded ends. */
    private fun bannerBar(from: Float, to: Float, progress: BannerProgress) {
        val inset = 6f
        val left = from.coerceAtLeast(inset)
        val right = to.coerceAtMost(width - inset)
        if (right - left < 4f) return
        val bar = ImageGetter.ProgressBar(right - left, 2f, false)
            .setBackground(ImageGetter.CHARCOAL.cpy().apply { a = 0.7f })
            .setProgress(progress.color, progress.percentage)
        if (progress.nextTurnPercentage > progress.percentage)
            bar.setSemiProgress(progress.color.cpy().darken(0.4f), progress.nextTurnPercentage)
        bar.touchable = Touchable.disabled
        bar.setPosition(left, 3f)
        addActor(bar)
    }

    private class BannerProgress(val color: Color, val percentage: Float, val nextTurnPercentage: Float, val turns: String)

    private fun growthProgress(city: CityView): BannerProgress {
        fun percentage(food: Int) = (food.toFloat() / city.getFoodToNextPopulation()).coerceIn(0f, 1f)
        return when {
            city.isStarving() -> BannerProgress(Color.RED, 1f, 1f, "")
            city.isGrowing() -> BannerProgress(CityButton.ColorGrowth, percentage(city.getFoodStored()),
                percentage(city.foodForNextTurn() + city.getFoodStored()), "")
            else -> BannerProgress(CityButton.ColorGrowth, percentage(city.getFoodStored()), 0f, "")
        }
    }

    private fun constructionProgress(city: CityView): BannerProgress {
        val constructions = city.constructions
        return when (val current = constructions.getCurrentConstruction()) {
            PerpetualConstruction.Idle -> BannerProgress(CityButton.ColorConstruction, 0f, 0f, "-")
            is PerpetualConstruction -> BannerProgress(CityButton.ColorConstruction, 0f, 0f, Fonts.infinity.toString())
            else -> {
                current as INonPerpetualConstruction
                val turns = constructions.turnsToConstruction(current.name)
                val workDone = constructions.getWorkDone(current.name).toFloat()
                val cost = city.getConstructionProductionCost(current)
                fun percentage(done: Float) = (done / cost).coerceIn(0f, 1f)
                BannerProgress(CityButton.ColorConstruction, percentage(workDone),
                    percentage(workDone + city.getCurrentCityStats().production),
                    if (turns < 100) turns.tr() else "-")
            }
        }
    }

    override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
        val fill = bannerFill ?: return super.drawBackground(batch, parentAlpha, x, y)
        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        fill.draw(batch, x, y, width, height)
        val rim = bannerRim ?: return
        rim.draw(batch, x, y, width, height)
        // The generated ring is a hairline; a second, inset pass makes the 2-unit rim.
        if (borderSize >= 2f) rim.draw(batch, x + .8f, y + .8f, width - 1.6f, height - 1.6f)
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
        private const val BANNER_HEIGHT = 24f
        private const val BANNER_NAME_WIDTH = 150f
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

/** Atlas map chips draw a rounded pill instead of [BorderedTable]'s plaque shapes. */
internal fun Table.drawChip(chip: Drawable, batch: Batch, parentAlpha: Float, x: Float, y: Float) {
    batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
    chip.draw(batch, x, y, width, height)
}

/** A small Atlas chip fill on busy terrain, with an optional hairline [rim]. */
internal fun cityChip(path: String, rim: Color? = null): Drawable =
    WorldScreenStyle.pill(path, WorldScreenStyle.surface.cpy().apply { a = 0.92f }, rim)
