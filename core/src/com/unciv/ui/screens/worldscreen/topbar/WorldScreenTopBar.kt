package com.unciv.ui.screens.worldscreen.topbar

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.worldscreen.WorldHudLayout
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.view.GameView
import kotlin.math.max

/** A compact HUD. Narrow windows put the stats below the identity instead of overlapping it.
 *  On Atlas the bar itself is unpainted: three floating clusters (identity, economy, turn and
 *  Overview), and the gaps between them pass input through to the map. */
class WorldScreenTopBar(internal val worldScreen: WorldScreen) : Table() {
    internal var displayedCiv = worldScreen.selectedCiv
        private set
    internal val displayedGameView get() = if (displayedCiv === worldScreen.selectedCiv)
        worldScreen.selectedGameView else GameView(worldScreen.gameInfo, displayedCiv, spectatorMode = true)

    /** Fixed per screen: a skin change rebuilds the world screen. */
    private val atlas = GameUiStyle.isAtlas
    private val statsTable = WorldScreenTopBarStats(this)
    private val resourceTable = WorldScreenTopBarResources(this)
    private val identity = Table()
    private val names = Table()
    private val civLabel = "".toLabel(WorldScreenStyle.ink, if (atlas) 15 else 17, hideIcons = true).apply { setEllipsis(true) }
    private val viewingLabel = "".toLabel(WorldScreenStyle.muted, GameUiStyle.Type.caption)
    private val portraitCell: Cell<Group>
    private var portraitCiv = ""
    private val overview = Table()
    private val supply = ImageGetter.getImage("OtherIcons/ExclamationMark").apply { color = WorldScreenStyle.warning }
    private val supplyCell: Cell<Actor?>

    // Atlas floating clusters
    private val leftCluster = Table()
    private val centreCluster = Table()
    private val rightCluster = Table()
    private val switcherArrow = Container(ImageGetter.getArrowImage(Align.bottom).apply {
        setSize(10f, 10f); setOrigin(Align.center); color = WorldScreenStyle.muted
    }).size(10f)
    private var switcher: NationSwitcher? = null
    /** "Following" or "Viewing" when a spectator sees another nation's economy. */
    private var caption: String? = null
    private var captionShown: Boolean? = null

    init {
        val menu = if (atlas) WorldScreenStyle.ghostButton("", GameUiStyle.ButtonSize.Compact) else WorldScreenStyle.button("")
        menu.add(ImageGetter.getImage("OtherIcons/MenuIcon")).size(18f)
        menu.onActivation(binding = KeyboardBinding.Menu) { WorldScreenMenuPopup(worldScreen) }
        menu.onRightClick { WorldScreenMenuPopup(worldScreen, true) }
        val overviewButton = if (atlas) WorldScreenStyle.quietButton("Overview", GameUiStyle.ButtonSize.Compact)
            else WorldScreenStyle.button("Overview")
        overviewButton.onActivation(binding = KeyboardBinding.EmpireOverview) { openOverview() }
        supply.onClick { openOverview(EmpireOverviewCategories.Units) }

        if (atlas) {
            touchable = Touchable.childrenOnly
            for (cluster in listOf(leftCluster, centreCluster, rightCluster)) {
                cluster.background = WorldScreenStyle.surface("Atlas/World/TopBar")
                cluster.pad(4f, 8f, 4f, 10f)
                cluster.touchable = Touchable.enabled
            }
            leftCluster.pad(4f, 4f, 4f, 12f)
            leftCluster.add(menu).size(32f).padRight(6f)
            portraitCell = identity.add(Group()).size(24f).padRight(8f)
            identity.add(names).minWidth(0f).maxWidth(200f * worldScreen.game.settings.fontSizeMultiplier)
            identity.add(switcherArrow).padLeft(8f)
            identity.touchable = Touchable.enabled
            identity.onClick {
                if (worldScreen.viewingCiv.isSpectator()) nationSwitcher().toggle(identity)
                else worldScreen.openCivilopedia(displayedCiv.nation.makeLink())
            }
            identity.addTooltip("Era", dynamicTextProvider = { displayedCiv.tech.era.name })
            leftCluster.add(identity).minWidth(0f).growX()
            rightCluster.add(resourceTable.turnsLabel).padRight(10f)
            supplyCell = rightCluster.add()
            rightCluster.add(overviewButton)
        } else {
            background = BaseScreen.skinStrings.getUiBackground("WorldScreen/TopBar", tintColor = WorldScreenStyle.surface)
            pad(6f, 10f, 6f, 10f)
            identity.add(menu).size(34f).padRight(10f)
            portraitCell = identity.add(Group()).size(26f).padRight(8f)
            names.add(civLabel).minWidth(0f).growX().left().row()
            names.add(viewingLabel).minWidth(0f).growX().left().padTop(3f)
            identity.add(names).growX()
            names.onClick { worldScreen.openCivilopedia(displayedCiv.nation.makeLink()) }
            supplyCell = overview.add()
            overview.add(overviewButton)
        }
    }

    private fun nationSwitcher() = switcher ?: NationSwitcher(worldScreen).also { switcher = it }

    internal fun openOverview(category: EmpireOverviewCategories? = null) {
        if (category == null) worldScreen.toggleCivilizationOverview()
        else worldScreen.game.pushScreen { EmpireOverviewScreen(displayedGameView.civView, category) }
    }

    internal fun update(civInfo: Civilization) {
        // Displaying an agent never switches the spectator's map view or grants human turn authority.
        val viewer = worldScreen.viewingCiv
        val active = if (viewer.isSpectator())
            worldScreen.gameInfo.civilizations.firstOrNull { it.civID == worldScreen.agentSession?.getActiveCivId() } else null
        if (!atlas) {
            caption = null
            displayedCiv = if (viewer.isSpectator()) active ?: civInfo else civInfo
        } else when {
            !viewer.isSpectator() -> { displayedCiv = civInfo; caption = null }
            !civInfo.isSpectator() -> { displayedCiv = civInfo; caption = "Viewing" }
            active != null && !active.isSpectator() -> { displayedCiv = active; caption = "Following" }
            // Nothing to follow: no economy at all rather than the Spectator's zeros.
            else -> { displayedCiv = civInfo; caption = null }
        }
        statsTable.update(displayedCiv)
        resourceTable.update(displayedCiv)
        civLabel.setText(displayedCiv.civID.tr(hideIcons = true))
        if (atlas) viewingLabel.setText(caption?.tr() ?: "")
        else viewingLabel.setText(if (viewer.isSpectator()) "Spectator".tr() else "${displayedCiv.tech.era.name}".tr())
        if (portraitCiv != displayedCiv.civID) {
            val size = if (atlas) 24f else 26f
            portraitCell.setActor(ImageGetter.getNationPortrait(displayedCiv.nation, size))
            portraitCiv = displayedCiv.civID
        }
        val deficit = displayedCiv.stats.getUnitSupplyDeficit() > 0
        supplyCell.setActor(if (deficit) supply else null).size(if (deficit) 18f else 0f).padRight(if (deficit) 8f else 0f)
        if (atlas) updateAtlasLayout() else updateLayout()
    }

    internal fun getYForTutorialTask(): Float = y

    private fun updateAtlasLayout() {
        val stage = stage ?: return
        val scale = worldScreen.game.settings.fontSizeMultiplier
        val header = WorldHudLayout.headerHeight(stage.width, stage.height, scale)
        val compact = stage.width < WorldHudLayout.compactBreakpoint
        val tabsHeight = if (compact) WorldHudLayout.compactTabsHeight * scale else 0f
        val barHeight = header - tabsHeight
        val clusterHeight = (barHeight - WorldHudLayout.gap * 2).coerceAtLeast(24f)

        if (captionShown != (caption != null)) {
            captionShown = caption != null
            names.clearChildren()
            if (caption != null) names.add(viewingLabel).minWidth(0f).left().row()
            names.add(civLabel).minWidth(0f).left()
        }
        switcherArrow.isVisible = worldScreen.viewingCiv.isSpectator()
        identity.getCell(switcherArrow).width(if (switcherArrow.isVisible) 10f else 0f)
            .padLeft(if (switcherArrow.isVisible) 8f else 0f)

        // Priority collapse: resources go first, then stat suffixes, then the whole centre cluster.
        val showEconomy = !compact && !displayedCiv.isSpectator()
        val available = stage.width - WorldHudLayout.gap * 2
        val sides = leftCluster.prefWidth + rightCluster.prefWidth + WorldHudLayout.gap * 2
        statsTable.resetScale(); resourceTable.resetScale()
        var centreShown = false
        if (showEconomy) {
            for (level in 0..2) {
                val suffixes = level < 2
                if (statsTable.showSuffixes != suffixes) {
                    statsTable.showSuffixes = suffixes
                    statsTable.update(displayedCiv)
                }
                centreCluster.clearChildren()
                centreCluster.add(statsTable)
                if (level == 0 && resourceTable.hasResources) centreCluster.add(resourceTable).padLeft(10f)
                centreCluster.invalidate()
                if (sides + centreCluster.prefWidth <= available) { centreShown = true; break }
            }
        }
        if (!centreShown && !statsTable.showSuffixes) {
            statsTable.showSuffixes = true
            statsTable.update(displayedCiv)
        }

        clearChildren()
        pad(WorldHudLayout.gap)
        add(leftCluster).left().minWidth(0f).height(clusterHeight)
        if (centreShown) add(centreCluster).expandX().height(clusterHeight).padLeft(WorldHudLayout.gap).padRight(WorldHudLayout.gap)
        else add().expandX()
        add(rightCluster).right().height(clusterHeight)
        setBounds(0f, stage.height - header + tabsHeight, stage.width, barHeight)
        validate()
    }

    private fun updateLayout() {
        val available = (stage.width - 20f).coerceAtLeast(1f)
        val identityWidth = minOf(220f, available * 0.44f)
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
            add(statsTable).colspan(3).growX().padTop(6f).row()
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
