package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.view.GameView
import kotlin.math.max

/** A compact HUD. Narrow windows put the stats below the identity instead of overlapping it. */
class WorldScreenTopBar(internal val worldScreen: WorldScreen) : Table() {
    internal var displayedCiv = worldScreen.selectedCiv
        private set
    internal val displayedGameView get() = if (displayedCiv === worldScreen.selectedCiv)
        worldScreen.selectedGameView else GameView(worldScreen.gameInfo, displayedCiv, spectatorMode = true)

    private val statsTable = WorldScreenTopBarStats(this)
    private val resourceTable = WorldScreenTopBarResources(this)
    private val identity = Table()
    private val civLabel = "".toLabel(WorldScreenStyle.ink, 16, hideIcons = true).apply { setEllipsis(true) }
    private val viewingLabel = "".toLabel(WorldScreenStyle.muted, 10)
    private val portraitCell: Cell<Group>
    private var portraitCiv = ""
    private val overview = Table()
    private val supply = ImageGetter.getImage("OtherIcons/ExclamationMark").apply { color = WorldScreenStyle.warning }
    private val supplyCell: Cell<Actor?>

    init {
        background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar", tintColor = WorldScreenStyle.surface)
        pad(6f, 10f, 6f, 10f)
        val menu = WorldScreenStyle.button("")
        menu.add(ImageGetter.getImage("OtherIcons/MenuIcon")).size(18f)
        menu.onActivation(binding = KeyboardBinding.Menu) { WorldScreenMenuPopup(worldScreen) }
        menu.onRightClick { WorldScreenMenuPopup(worldScreen, true) }
        identity.add(menu).size(34f).padRight(10f)
        portraitCell = identity.add(Group()).size(24f).padRight(8f)
        val names = Table()
        names.add(civLabel).growX().left().row()
        names.add(viewingLabel).growX().left().padTop(2f)
        identity.add(names).growX()
        names.onClick { worldScreen.openCivilopedia(displayedCiv.nation.makeLink()) }

        supply.onClick { openOverview(EmpireOverviewCategories.Units) }
        supplyCell = overview.add()
        val overviewButton = WorldScreenStyle.button("Overview")
        overviewButton.onActivation(binding = KeyboardBinding.EmpireOverview) { openOverview() }
        overview.add(overviewButton)
    }

    internal fun openOverview(category: EmpireOverviewCategories? = null) {
        if (category == null) worldScreen.toggleCivilizationOverview()
        else worldScreen.game.pushScreen { EmpireOverviewScreen(displayedGameView.civView, category) }
    }

    internal fun update(civInfo: Civilization) {
        // Displaying an agent never switches the spectator's map view or grants human turn authority.
        displayedCiv = if (worldScreen.viewingCiv.isSpectator())
            worldScreen.gameInfo.civilizations.firstOrNull { it.civID == worldScreen.agentSession?.getActiveCivId() } ?: civInfo
        else civInfo
        statsTable.update(displayedCiv)
        resourceTable.update(displayedCiv)
        civLabel.setText(displayedCiv.civID.tr(hideIcons = true))
        viewingLabel.setText(if (worldScreen.viewingCiv.isSpectator()) "Spectator".tr() else "${displayedCiv.tech.era.name}".tr())
        if (portraitCiv != displayedCiv.civID) {
            portraitCell.setActor(ImageGetter.getNationPortrait(displayedCiv.nation, 24f))
            portraitCiv = displayedCiv.civID
        }
        val deficit = displayedCiv.stats.getUnitSupplyDeficit() > 0
        supplyCell.setActor(if (deficit) supply else null).size(if (deficit) 18f else 0f).padRight(if (deficit) 8f else 0f)
        updateLayout()
    }

    internal fun getYForTutorialTask(): Float = y

    private fun updateLayout() {
        val available = (stage.width - 20f).coerceAtLeast(1f)
        val identityWidth = minOf(190f, available * 0.38f)
        val controlsWidth = overview.prefWidth
        val middleWidth = available - identityWidth - controlsWidth - 28f
        val stack = middleWidth < max(statsTable.prefWidth, resourceTable.prefWidth)
        clearChildren()
        if (stack) {
            add(identity).width(identityWidth).left()
            add().growX()
            add(overview).right().row()
            statsTable.scaleTo(available)
            resourceTable.scaleTo(available)
            add(statsTable).colspan(3).growX().padTop(4f).row()
            add(resourceTable).colspan(3).growX().row()
        } else {
            statsTable.scaleTo(middleWidth)
            resourceTable.scaleTo(middleWidth)
            val center = Table()
            center.add(statsTable).growX().row()
            center.add(resourceTable).growX()
            add(identity).width(identityWidth).left().padRight(14f)
            add(center).growX()
            add(overview).right().padLeft(14f)
        }
        setSize(stage.width, prefHeight)
        setPosition(0f, stage.height - height)
        validate()
    }
}
