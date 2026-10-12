package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.UncivTooltip.Companion.removeTooltips
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.NextTurnAction.Default
import com.unciv.utils.Concurrency
import yairm210.purity.annotations.Readonly

class NextTurnButton(
    private val worldScreen: WorldScreen
) : IconTextButton("", null, 16) {
    private var nextTurnAction = Default
    private var atlasIconUsesAuthoredColors = false
    private val unitsDueLabel = Label("", BaseScreen.skin)
    private val unitsDueCell: Cell<Label>

    init {
        WorldScreenStyle.apply(this, primary = com.unciv.ui.components.GameUiStyle.isAtlas)
        onActivation { nextTurnAction.action(worldScreen) }
        addContextMenu { NextTurnMenu(stage, this, worldScreen) }
        keyShortcuts.add(KeyboardBinding.NextTurn)
        keyShortcuts.add(KeyboardBinding.NextTurnAlternate)
        labelCell.row()
        unitsDueCell = add(unitsDueLabel).padTop(6f).colspan(2).center()
    }

    fun update() {
        nextTurnAction = getNextTurnAction(worldScreen)
        updateButton(nextTurnAction)
        val agentSession = worldScreen.agentSession
        if (agentSession != null) {
            // Agent matches own the button: status text comes from the match,
            // and it is enabled only while a human may act.
            WorldScreenStyle.setEnabled(this, agentSession.isHumanInputAllowed())
            removeTooltips()
            worldScreen.smallUnitButton.update()
            return
        }
        val autoPlay = worldScreen.autoPlay
        if (autoPlay.shouldContinueAutoPlaying() && worldScreen.isPlayersTurn
            && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning()) {
            autoPlay.runAutoPlayJobInNewThread("MultiturnAutoPlay", worldScreen, false) {
                TurnManager(worldScreen.selectedGameView.civView.getCiv()).automateTurn()
                Concurrency.runOnGLThread { worldScreen.nextTurn() }
                autoPlay.endTurnMultiturnAutoPlay()
            }
        }

        WorldScreenStyle.setEnabled(this, nextTurnAction.getText(worldScreen) == "AutoPlay"
            || ((worldScreen.isPlayersTurn || worldScreen.failedUpload) && !worldScreen.waitingForAutosave && !worldScreen.isNextTurnUpdateRunning()))
        if (!isDisabled) {
            addTooltip(KeyboardBinding.NextTurn)
        } else {
            removeTooltips()
        }

        worldScreen.smallUnitButton.update()
    }

    internal fun updateButton(nextTurnAction: NextTurnAction) {
        label.setText(nextTurnAction.getText(worldScreen).tr())
        label.color = nextTurnAction.color
        var iconName = nextTurnAction.icon
        val isAtlas = com.unciv.ui.components.GameUiStyle.isAtlas
        fun isBaseIcon(name: String) = ImageGetter.getDrawable(name).region === ImageGetter.getSpecificAtlas("Icons")?.findRegion(name)
        // The built-in construction badge is a low-contrast colored circle. Atlas uses
        // the existing monochrome gear; an authored mod replacement keeps its artwork.
        if (isAtlas && nextTurnAction == NextTurnAction.PickConstruction && iconName != null && ImageGetter.imageExists(iconName) && isBaseIcon(iconName))
            iconName = "OtherIcons/Settings"
        if (iconName != null && ImageGetter.imageExists(iconName)) {
            atlasIconUsesAuthoredColors = isAtlas && (!isBaseIcon(iconName) || when (nextTurnAction) {
                NextTurnAction.PickTech, NextTurnAction.PickPolicy, NextTurnAction.FoundPantheon,
                NextTurnAction.FoundReligion, NextTurnAction.EnhanceReligion, NextTurnAction.ReformReligion -> true
                else -> false
            })
            iconCell.setActor(ImageGetter.getImage(iconName).apply {
                name = iconName
                setSize(20f)
                color = nextTurnAction.color
            })
        } else
            iconCell.clearActor()

        nextTurnAction.getSubText(worldScreen)?.let {
            unitsDueLabel.setText(it.tr())
            unitsDueCell.setActor(unitsDueLabel)
        } ?: unitsDueCell.clearActor()

        updateAtlasForeground()
        pack()
    }

    override fun setDisabled(disabled: Boolean) {
        super.setDisabled(disabled)
        updateAtlasForeground()
    }

    // IconTextButton owns plain Labels, so ButtonStyle.fontColor cannot color its children.
    // Update on state publication, never in draw/act.
    private fun updateAtlasForeground() {
        if (!com.unciv.ui.components.GameUiStyle.isAtlas) return
        val foreground = if (isDisabled) WorldScreenStyle.muted else WorldScreenStyle.surface
        label?.let { it.color = com.badlogic.gdx.graphics.Color.WHITE; if (it.style.fontColor != foreground) it.setFontColor(foreground) }
        unitsDueLabel?.let { it.color = com.badlogic.gdx.graphics.Color.WHITE; if (it.style.fontColor != foreground) it.setFontColor(foreground) }
        iconCell?.actor?.color = if (atlasIconUsesAuthoredColors) com.badlogic.gdx.graphics.Color.WHITE else foreground
    }

    private fun getNextTurnAction(worldScreen: WorldScreen): NextTurnAction {
        val session = worldScreen.agentSession
        if (session != null) {
            if (!session.isHumanInputAllowed()) return NextTurnAction.AgentMatch
            if (listOf(NextTurnAction.MoveSpies, NextTurnAction.FoundPantheon, NextTurnAction.ExpandPantheon,
                    NextTurnAction.FoundReligion, NextTurnAction.EnhanceReligion, NextTurnAction.ReformReligion)
                    .any { it.isChoice(worldScreen) }) return NextTurnAction.AgentUnsupportedChoice
            // Human choices must be reachable before the coordinator's end-turn fallback.
            // Automation and legacy turn advancement remain owned by the session.
            return listOf(NextTurnAction.PickGreatPerson, NextTurnAction.PickConstruction,
                NextTurnAction.PickTech, NextTurnAction.PickPolicy,
                NextTurnAction.WorldCongressVote, NextTurnAction.NextUnit)
                .firstOrNull { it.isChoice(worldScreen) } ?: NextTurnAction.AgentMatch
        }
        return NextTurnAction.entries.first { it.isChoice(worldScreen) }
    }

    @Readonly fun isNextUnitAction(): Boolean = nextTurnAction == NextTurnAction.NextUnit

}
