package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.atlas.AtlasChoiceCard
import com.unciv.ui.components.atlas.AtlasChoiceState
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.addSeparatorVertical
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.packIfNeeded
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AskTextPopup

class ReligiousBeliefsPickerScreen (
    choosingCiv: Civilization,
    numberOfBeliefsCanChoose: Counter<BeliefType>,
    private val pickIconAndName: Boolean
): ReligionPickerScreenCommon(choosingCiv, disableScroll = true) {
    // Roughly follows the layout of the original (although I am not very good at UI designing, so please improve this)

    private val topReligionIcons = Table() // Top of the layout, contains icons for religions
    private val leftChosenBeliefs = Table() // Left middle part, contains buttons to select the types of beliefs to choose
    private val leftScrollPane = AutoScrollPane(leftChosenBeliefs)
    private val rightBeliefsToChoose = Table() // Right middle part, contains the beliefs to choose
    private val rightScrollPane = AutoScrollPane(rightBeliefsToChoose)

    private val middlePanes = object : Table() {
        private var stacked: Boolean? = null
        override fun layout() {
            if (GameUiStyle.isAtlas) {
                val narrow = width < 700f
                if (stacked != narrow) {
                    stacked = narrow
                    clearChildren()
                    if (narrow) {
                        add(leftScrollPane).growX().minWidth(0f)
                            .height(Value.percentHeight(0.32f, this)).row()
                        add(rightScrollPane).grow().minWidth(0f).minHeight(0f)
                    } else {
                        add(leftScrollPane).grow().minWidth(0f).width(Value.percentWidth(0.4f, this))
                        add(rightScrollPane).grow().minWidth(0f).width(Value.percentWidth(0.6f, this))
                    }
                }
            }
            super.layout()
        }
    }

    private var iconSelection = Selection()
    private var displayName: String? = null
    private var religionName: String? = null

    // One entry per new Belief to choose - the left side will offer these below the choices from earlier in the game
    class BeliefToChoose(val type: BeliefType, var belief: Belief? = null)
    private val beliefsToChoose: Array<BeliefToChoose> =
        numberOfBeliefsCanChoose.flatMap { entry -> (0 until entry.value).map { BeliefToChoose(entry.key) } }.toTypedArray()

    private var leftSelection = Selection()
    private var leftSelectedIndex = -1
    private var rightSelection = Selection()

    private val currentReligion = choosingCiv.religionManager.religion
        ?: Religion("None", gameInfo, choosingCiv)

    init {
        setChoiceHeader(if (pickIconAndName) "Choose a Religion" else "Enhance [${currentReligion.getReligionDisplayName()}]",
            choosingCiv.civName)
        leftChosenBeliefs.defaults().right().pad(10f).fillX()
        rightBeliefsToChoose.defaults().left().pad(10f).fillX()

        if (pickIconAndName) setupChoosableReligionIcons()
        else setupVisibleReligionIcons()

        updateLeftTable()

        if (GameUiStyle.isAtlas) {
            leftChosenBeliefs.top().defaults().minWidth(0f).growX().pad(GameUiStyle.Space.s)
            rightBeliefsToChoose.top().defaults().minWidth(0f).growX().pad(GameUiStyle.Space.s)
            leftScrollPane.setScrollingDisabled(true, false)
            rightScrollPane.setScrollingDisabled(true, false)
            topTable.add(topReligionIcons).growX().minWidth(0f).row()
            topTable.add(middlePanes).grow().minWidth(0f).minHeight(0f)
        } else {
            middlePanes.add(leftScrollPane)
            middlePanes.addSeparatorVertical()
            middlePanes.add(rightScrollPane)
            topTable.add(topReligionIcons).minHeight(topReligionIcons.prefHeight).row()
            topTable.addSeparator()
            topTable.add(middlePanes)
        }

        setOKAction(
            if (pickIconAndName) "Choose a Religion"
            else "Enhance [${currentReligion.getReligionDisplayName()}]"
        ) {
            if (civInfo.religionManager.religionState == ReligionState.FoundingReligion)
                civInfo.religionManager.foundReligion(displayName!!, religionName!!)
            chooseBeliefs(beliefsToChoose.map { it.belief!! }, usingFreeBeliefs())
        }
    }

    private fun checkAndEnableRightSideButton() {
        if (religionName == null || displayName == null) return
        if (beliefsToChoose.any { it.belief == null }) return
        rightSideButton.enable()
    }

    private fun setupChoosableReligionIcons() {
        val descriptionLabel = "Choose an Icon and name for your Religion".toLabel()

        fun changeDisplayedReligionName(newReligionName: String) {
            displayName = newReligionName
            rightSideButton.setText("Found [$newReligionName]".tr())
            descriptionLabel.setText(newReligionName.tr())
        }

        val changeReligionNameButton = Button(
            ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(30f),
            skin
        )

        addIconsScroll { button, religionName ->
            button.onClickSelect(iconSelection, null) {
                changeDisplayedReligionName(religionName)
                this.religionName = religionName
                changeReligionNameButton.enable()

                checkAndEnableRightSideButton()
            }
        }

        val labelTable = Table()
        if (GameUiStyle.isAtlas) descriptionLabel.wrap = true
        labelTable.add(descriptionLabel).growX().minWidth(0f).pad(5f)
        labelTable.add(changeReligionNameButton).pad(5f).row()
        topReligionIcons.add(labelTable).growX().minWidth(0f).pad(5f).row()

        changeReligionNameButton.onClick {
            AskTextPopup(
                this,
                label = "Choose a name for your religion",
                icon = ImageGetter.getReligionPortrait(religionName!!, 80f),
                defaultText = religionName!!,
                validate = { religionName ->
                    religionName != Constants.noReligionName
                    && ruleset.religions.none { it == religionName }
                    && gameInfo.religions.none { it.value.name == religionName }
                    && religionName != ""
                },
                actionOnOk = { changeDisplayedReligionName(it) }
            ).open()
        }
        changeReligionNameButton.disable()
    }

    private fun setupVisibleReligionIcons() {
        topReligionIcons.clear()
        religionName = currentReligion.name
        displayName = currentReligion.getReligionDisplayName()
        val descriptionLabel = displayName!!.toLabel()
        addIconsScroll { button, _ ->
            button.disable()
        }
        topReligionIcons.add(descriptionLabel).center().padBottom(15f)
    }

    private fun addIconsScroll(buttonSetup: (Button, String)->Unit) {
        var scrollTo = 0f
        val iconsTable = Table()
        iconsTable.align(Align.center)

        for (religionName in ruleset.religions) {
            if (religionName == this.religionName)
                scrollTo = iconsTable.packIfNeeded().prefWidth
            val button = if (GameUiStyle.isAtlas) Button(skin).apply {
                pad(0f)
                val state = when {
                    religionName == this@ReligiousBeliefsPickerScreen.religionName -> AtlasChoiceState.CURRENT
                    !pickIconAndName -> AtlasChoiceState.LOCKED
                    gameInfo.religions.containsKey(religionName) -> AtlasChoiceState.LOCKED
                    else -> AtlasChoiceState.AVAILABLE
                }
                add(AtlasChoiceCard(religionName, "", state) {}
                    .withIcon(ImageGetter.getReligionPortrait(religionName, 36f)))
                    .width(190f).minWidth(0f)
            } else Button(ImageGetter.getReligionPortrait(religionName, 60f), skin)
            buttonSetup(button, religionName)
            if (religionName == this.religionName) button.disable(Color(greenDisableColor))
            else if (gameInfo.religions.keys.any { it == religionName }) button.disable(
                redDisableColor
            )
            iconsTable.add(button).pad(5f)
        }
        iconsTable.row()

        AutoScrollPane(iconsTable, skin).apply {
            setScrollingDisabled(false, true)
            setupFadeScrollBars(0f, 0f)  // only way to "remove" scrollbar
            setScrollbarsOnTop(true) // don't waste space on scrollbar
            topReligionIcons.add(this).growX().minWidth(0f)
                .height(if (GameUiStyle.isAtlas) iconsTable.prefHeight else 80f).padBottom(10f).row()
            layout()
            scrollX = scrollTo - (width - 70f) / 2  // 70 = button width incl pad
        }
    }

    private fun updateLeftTable() {
        leftChosenBeliefs.clear()
        leftSelection.clear()

        for (belief in currentReligion.getAllBeliefsOrdered()) {
            val beliefButton = getBeliefButton(belief)
            leftChosenBeliefs.add(beliefButton).row()
            beliefButton.setBeliefState(AtlasChoiceState.COMPLETED)
            beliefButton.disable(Color.GREEN)
        }

        for ((index, entry) in beliefsToChoose.withIndex()) {
            addChoosableBeliefButton(entry.belief, entry.type, index)
        }

        equalizeAllButtons(leftChosenBeliefs)
    }

    private fun loadRightTable(beliefType: BeliefType, leftButtonIndex: Int) {
        var selectedButtonY = 0f
        var selectedButtonHeight = 0f
        rightBeliefsToChoose.clear()
        rightSelection.clear()
        val availableBeliefs = ruleset.beliefs.values
            .filter { it.type == beliefType || beliefType == BeliefType.Any }

        val civReligionManager = currentReligion.foundingCiv.religionManager

        for (belief in availableBeliefs) {
            val beliefButton = getBeliefButton(belief)
            val otherReligion = civReligionManager.getReligionWithBelief(belief)
            val restrictions = belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .filter { !it.conditionalsApply(choosingCiv.state) }.toList() +
                belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).toList()
            beliefButton.setBeliefState(when {
                currentReligion.hasBelief(belief.name) -> AtlasChoiceState.COMPLETED
                beliefsToChoose.any { it.belief == belief } -> AtlasChoiceState.CURRENT
                otherReligion != null && otherReligion != currentReligion || restrictions.isNotEmpty() -> AtlasChoiceState.LOCKED
                else -> AtlasChoiceState.AVAILABLE
            }, when {
                otherReligion != null && otherReligion != currentReligion ->
                    "${"Unavailable".tr()}: ${otherReligion.getReligionDisplayName().tr()}"
                restrictions.isNotEmpty() -> restrictions.joinToString("\n") { it.getDisplayText().tr() }
                else -> ""
            })
            when {
                beliefsToChoose[leftButtonIndex].belief == belief -> {
                    selectedButtonY = rightBeliefsToChoose.packIfNeeded().prefHeight
                    selectedButtonHeight = beliefButton.packIfNeeded().prefHeight + 20f
                    rightSelection.switch(beliefButton)
                }
                beliefsToChoose.any { it.belief == belief } ||
                        currentReligion.hasBelief(belief.name) -> {
                    // The Belief button should be disabled because you already have it selected
                    beliefButton.disable(greenDisableColor)
                }
                civReligionManager.getReligionWithBelief(belief) != null
                        && civReligionManager.getReligionWithBelief(belief) != currentReligion -> {
                    // The Belief is not available because someone already has it
                    beliefButton.disable(redDisableColor)
                }
                belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                    .any { !it.conditionalsApply(choosingCiv.state) } ->
                    // The Belief is blocked
                    beliefButton.disable(redDisableColor)
                belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any() ->
                    // The Belief is blocked
                    beliefButton.disable(redDisableColor)

                else ->
                    beliefButton.onClickSelect(rightSelection, belief) {
                        beliefsToChoose[leftButtonIndex].belief = belief
                        updateLeftTable()
                        checkAndEnableRightSideButton()
                    }
            }
            rightBeliefsToChoose.add(beliefButton).row()
        }
        equalizeAllButtons(rightBeliefsToChoose)

        if (rightSelection.isEmpty()) return
        rightScrollPane.layout()
        rightScrollPane.scrollY = selectedButtonY - (rightScrollPane.height - selectedButtonHeight) / 2
    }

    private fun equalizeAllButtons(table: Table) {
        if (GameUiStyle.isAtlas) return
        val minWidth = table.cells.maxOfOrNull { it.prefWidth }
            ?: return

        for (buttonCell in table.cells) {
            if (buttonCell.actor is Button)
                buttonCell.minWidth(minWidth)
        }
    }

    private fun addChoosableBeliefButton(belief: Belief?, beliefType: BeliefType, index: Int) {
        val newBeliefButton = getBeliefButton(belief, beliefType)
        if (belief != null) newBeliefButton.setBeliefState(AtlasChoiceState.CURRENT)

        if (index == leftSelectedIndex) {
            leftSelection.switch(newBeliefButton)
            leftScrollPane.scrollY = leftChosenBeliefs.packIfNeeded().prefHeight -
                    (leftScrollPane.height - (newBeliefButton.prefHeight + 20f)) / 2
            leftScrollPane.updateVisualScroll()
        }
        leftChosenBeliefs.add(newBeliefButton).row()

        newBeliefButton.onClickSelect(leftSelection, belief) {
            leftSelectedIndex = index
            loadRightTable(beliefType, index)
        }
    }
}
