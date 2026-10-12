package com.unciv.ui.screens.pickerscreens

import com.unciv.logic.agents.CastVoteCommand

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.unciv.ui.components.GameUiStyle
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter

class DiplomaticVotePickerScreen(private val votingCiv: Civilization) : PickerScreen() {
    private var chosenCiv: String? = null
    private var selectedButton: Button? = null

    init {
        setDefaultCloseAction()
        setChoiceHeader("Diplomatic Victory", votingCiv.civName)
        rightSideButton.setText("Choose a civ to vote for".tr())

        descriptionLabel.setText("Choose who should become the world leader and win a Diplomatic Victory!".tr())

        val choosableCivs = votingCiv.diplomacyFunctions.getKnownCivsSorted(false)
        for (civ in choosableCivs) {
            addButton(civ.civName, "Vote for [${civ.civName}]", civ.civID,
                ImageGetter.getNationPortrait(
                    civ.nation,
                    PickerPane.pickerOptionIconSize
                )
            )
        }
        addButton("Abstain", "Abstain", null,
            ImageGetter.getImage("OtherIcons/Stop").apply {
                setSize(PickerPane.pickerOptionIconSize, PickerPane.pickerOptionIconSize)
            }
        )

        rightSideButton.onClick(UncivSound.Chimes, ::voteAndClose)
    }

    private fun voteAndClose() {
        // Agent matches cast votes through the coordinator (§3.2).
        if (UncivGame.Current.worldScreen?.routeThroughAgentSession {
                listOf({ r: Int ->
                    CastVoteCommand(votingCiv.civID, "ui-${System.nanoTime()}", r, chosenCiv)
                })
            } == true) {
            UncivGame.Current.popScreen()
            return
        }
        votingCiv.diplomaticVoteForCiv(chosenCiv)
        UncivGame.Current.popScreen()
    }

    private fun addButton(caption: String, pickText: String, choice: String?, icon: Actor) {
        val button = PickerPane.getPickerOptionButton(icon, caption)
        button.onClick {
            selectedButton?.let { PickerPane.setChoiceSelected(it, false) }
            selectedButton = button
            PickerPane.setChoiceSelected(button, true)
            chosenCiv = choice
            pick(pickText.tr())
        }
        button.onDoubleClick(UncivSound.Chimes) {
            chosenCiv = choice
            voteAndClose()
        }
        topTable.add(button).width(if (GameUiStyle.isAtlas) (stage.width - 40f).coerceAtMost(760f)
            else button.prefWidth).minWidth(0f).fillX().pad(10f).row()
    }
}
