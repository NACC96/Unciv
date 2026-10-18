package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.logic.civilization.Civilization
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ScalingTableWrapper
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class WorldScreenTopBarStats(private val topbar: WorldScreenTopBar) : ScalingTableWrapper() {

    private val goldLabel = "0".toLabel(WorldScreenStyle.ink, 14)
    private val goldPerTurnLabel = "+0"
        .toLabel(WorldScreenStyle.muted, 12)

    private val scienceLabel = "0".toLabel(WorldScreenStyle.ink, 14)
    private val happinessLabel = "0".toLabel(WorldScreenStyle.ink, 14)
    private val cultureLabel = "0".toLabel(WorldScreenStyle.ink, 14)

    private val faithLabel = "0".toLabel(WorldScreenStyle.ink, 14)
    private val faithPerTurnLabel = "+0"
        .toLabel(WorldScreenStyle.muted, 12)

    private val happinessContainer = Group()

    // These are all to improve performance IE reduce update time (was 150 ms on my phone, which is a lot!)
    private val malcontentColor = WorldScreenStyle.error
    private val happinessColor = WorldScreenStyle.ink
    private val malcontentImage = ImageGetter.getStatIcon("Malcontent")
    private val happinessImage = ImageGetter.getStatIcon("Happiness")

    private val worldScreen = topbar.worldScreen
    private val atlas = com.unciv.ui.components.GameUiStyle.isAtlas
    /** Atlas only: culture turns and the golden age text; dropped first when the bar is narrow. */
    var showSuffixes = true


    companion object {
        const val defaultImageSize = 16f
        const val defaultHorizontalPad = 2f
        const val defaultTopPad = 3f
        const val defaultBottomPad = 2f
        const val defaultImageBottomPad = 2f
        const val padRightBetweenStats = 12f
    }

    init {
        isTransform = false

        defaults().pad(defaultTopPad, defaultHorizontalPad, defaultBottomPad, defaultHorizontalPad)

        fun addStat(
            icon: String,
            label: Label,
            noPad: Boolean = false,
            screenFactory: () -> BaseScreen?
        ) {
            val image = ImageGetter.getStatIcon(icon)
            val action = {
                val screen = screenFactory()
                // I don't like that it's ANR-able but to not do so we'd need to get rid of the nullable option
                if (screen != null) worldScreen.game.pushScreen{ screen } 
            }
            label.onClick(action)
            image.onClick(action)
            add(image).padBottom(defaultImageBottomPad).size(defaultImageSize)
            add(label).padRight(if (noPad) 0f else padRightBetweenStats)
        }

        fun addStat(
            icon: String,
            label: Label,
            overviewPage: EmpireOverviewCategories,
            noPad: Boolean = false
        ) = addStat(icon, label, noPad) {
            EmpireOverviewScreen(topbar.displayedGameView.civView, overviewPage)
        }

        fun addPerTurnLabel(label: Label) {
            add(label).padRight(padRightBetweenStats)
                .padLeft(0f)
        }


        addStat("Gold", goldLabel, EmpireOverviewCategories.Stats, true)
        addPerTurnLabel(goldPerTurnLabel)

        addStat("Science", scienceLabel) { TechPickerScreen(topbar.displayedCiv) }

        val invokeResourcesPage = {
            topbar.openOverview(EmpireOverviewCategories.Resources)
        }
        happinessContainer.onClick(invokeResourcesPage)
        happinessLabel.onClick(invokeResourcesPage)
        add(happinessContainer).padBottom(defaultImageBottomPad).size(defaultImageSize)
        add(happinessLabel).padRight(padRightBetweenStats)

        addStat("Culture", cultureLabel) {
            if (worldScreen.gameInfo.ruleset.policyBranches.isEmpty()) null
            else PolicyPickerScreen(topbar.displayedCiv, worldScreen.canChangeState)
        }

        if (worldScreen.gameInfo.isReligionEnabled()) {
            addStat("Faith", faithLabel, EmpireOverviewCategories.Religion, true)
            addPerTurnLabel(faithPerTurnLabel)
        }


    }


    fun update(civInfo: Civilization) {
        resetScale()

        val nextTurnStats = civInfo.stats.statsForNextTurn

        goldLabel.setText(civInfo.gold.tr())
        goldPerTurnLabel.setText(rateLabel(nextTurnStats.gold))
        goldPerTurnLabel.setFontColor(rateColor(nextTurnStats.gold))

        scienceLabel.setText(valueLabel(nextTurnStats.science))

        happinessLabel.setText(getHappinessText(civInfo))

        if (civInfo.getHappiness() < 0) {
            happinessLabel.setFontColor(malcontentColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(malcontentImage)
        } else {
            happinessLabel.setFontColor(happinessColor)
            happinessContainer.clearChildren()
            happinessContainer.addActor(happinessImage)
        }

        cultureLabel.setText(getCultureText(civInfo, nextTurnStats))

        faithLabel.setText(civInfo.religionManager.storedFaith.tr())
        faithPerTurnLabel.setText(rateLabel(nextTurnStats.faith))
        faithPerTurnLabel.setFontColor(rateColor(nextTurnStats.faith))

        if (!com.unciv.ui.components.GameUiStyle.isAtlas) scaleTo(worldScreen.stage.width)
    }

    /** Positive rates stay quiet; a deficit is the value the player must notice. */
    private fun rateColor(rate: Float) = if (rate.roundToInt() < 0) WorldScreenStyle.error else WorldScreenStyle.muted

    private fun getCultureText(civInfo: Civilization, nextTurnStats: Stats): String {
        var cultureString = valueLabel(nextTurnStats.culture)
        // kotlin Float division by Zero produces `Float.POSITIVE_INFINITY`, not an exception
        val turnsToNextPolicy = (civInfo.policies.getCultureNeededForNextPolicy() - civInfo.policies.storedCulture) / nextTurnStats.culture
        if (atlas) {
            // Only a finite, actionable suffix: never "(∞)".
            if (!showSuffixes) return cultureString
            return cultureString + when {
                turnsToNextPolicy <= 0f -> "\u2004(!)"
                nextTurnStats.culture <= 0 -> ""
                else -> "\u2004(" + Fonts.turn + "\u2009" + ceil(turnsToNextPolicy).toInt().tr() + ")"
            }
        }
        cultureString += when {
            turnsToNextPolicy <= 0f -> "\u2004(!)" // Can choose policy right now
            nextTurnStats.culture <= 0 -> "\u2004(${Fonts.infinity})" // when you start the game, you're not producing any culture
            else -> "\u2004(" + Fonts.turn + "\u2009" + ceil(turnsToNextPolicy).toInt().tr() + ")" // U+2004: Three-Per-Em Space, U+2009: Thin Space
        }
        return cultureString
    }

    private fun getHappinessText(civInfo: Civilization): String {
        var happinessText = civInfo.getHappiness().tr()
        val goldenAges = civInfo.goldenAges
        // Atlas drops the "(stored/required)" progress; an active golden age stays visible.
        if (atlas) return if (showSuffixes && goldenAges.isGoldenAge())
            happinessText + "    {GOLDEN AGE}(${goldenAges.turnsLeftForCurrentGoldenAge})".tr() else happinessText
        happinessText +=
            if (goldenAges.isGoldenAge())
                "    {GOLDEN AGE}(${goldenAges.turnsLeftForCurrentGoldenAge})".tr()
            else
                " (${goldenAges.storedHappiness.tr()}/${goldenAges.happinessRequiredForNextGoldenAge().tr()})"
        return happinessText
    }

    /** Per-turn suffix after a stored amount: Atlas hides a zero rate entirely. */
    private fun rateLabel(value: Float): String {
        if (value.roundToInt() == 0) return if (atlas) "" else "±0"
        return value.roundToInt().toStringSigned()
    }

    /** A rate shown as the stat's own value (science, culture): Atlas shows a plain "0". */
    private fun valueLabel(value: Float): String =
        if (atlas && value.roundToInt() == 0) "0" else rateLabel(value)
}
