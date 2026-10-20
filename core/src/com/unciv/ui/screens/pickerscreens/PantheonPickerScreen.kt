package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.atlas.AtlasChoiceState

class PantheonPickerScreen(
    choosingCiv: Civilization
) : ReligionPickerScreenCommon(choosingCiv) {
    private var selectedPantheon: Belief? = null
    private val selection = Selection()

    init {
        setChoiceHeader("Choose a pantheon", choosingCiv.civName)
        topTable.defaults().pad(10f).fillX()

        for (belief in ruleset.beliefs.values) {
            if (belief.type != BeliefType.Pantheon) continue
            val beliefButton = getBeliefButton(belief, withTypeLabel = false)
            if (choosingCiv.religionManager.getReligionWithBelief(belief) == null && beliefIsAllowed(belief, choosingCiv)) {
                beliefButton.onClickSelect(selection, belief) {
                    selectedPantheon = belief
                    pick("Follow [${belief.name}]".tr())
                }
            } else {
                val owner = choosingCiv.religionManager.getReligionWithBelief(belief)
                beliefButton.setBeliefState(AtlasChoiceState.LOCKED,
                    if (owner != null) "${"Unavailable".tr()}: ${owner.getReligionDisplayName().tr()}"
                    else belief.uniqueObjects.filter {
                        it.type == UniqueType.OnlyAvailable && !it.conditionalsApply(choosingCiv.state) ||
                            it.type == UniqueType.Unavailable && it.conditionalsApply(choosingCiv.state)
                    }.joinToString("\n") { it.getDisplayText().tr() })
                beliefButton.disable(redDisableColor)
            }
            if (GameUiStyle.isAtlas)
                topTable.add(beliefButton).width((stage.width - 40f).coerceAtMost(760f)).minWidth(0f).row()
            else topTable.add(beliefButton).row()
        }

        setOKAction("Choose a pantheon") {
            chooseBeliefs(listOf(selectedPantheon!!), useFreeBeliefs = usingFreeBeliefs())
        }
    }
    fun beliefIsAllowed(belief: Belief, choosingCiv: Civilization): Boolean {
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(choosingCiv.state) })
            return false
        if (belief.getMatchingUniques(UniqueType.Unavailable, choosingCiv.state).any())
            return false
        return true
    }
}
