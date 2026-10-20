package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.bottombar.TileInfoTable
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed
import com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable
import com.unciv.ui.screens.worldscreen.unit.UnitTable

/** Bounded, scrollable hosts retain the actual gameplay actors, listeners and selection state.
 *  Each pane hugs its content and is hidden (never recreated) when it has nothing to show. */
class AtlasWorldHud(
    private val screen: WorldScreen,
    private val tracker: Actor, private val unit: Actor, private val unitActions: Actor,
    private val minimap: MinimapHolder, private val turn: Actor, private val tileInfo: Actor
) {
    companion object {
        /** A [WorldScreenStyle.surface] halo draws this far outside its card. ScrollPanes clip,
         *  so card hosts pad their content by it and extend their bounds by the same amount. */
        const val halo = 18f
        private val gap = WorldHudLayout.gap

        /** Whether [this] shows anything: an empty Table, Container or Group counts as empty. */
        internal fun Actor.hasVisibleContent(): Boolean = isVisible && when (this) {
            is Container<*> -> actor?.hasVisibleContent() == true
            is Group -> children.any { it.hasVisibleContent() }
            else -> true
        }
    }

    private fun pane(name: String, content: Actor) = AutoScrollPane(content).apply {
        this.name = name
        setOverscroll(false, false)
        setFadeScrollBars(false)
        GameUiStyle.applyWidget(this)
        // Margins and gaps around the content pass input through to the map.
        touchable = Touchable.childrenOnly
        screen.stage.addActor(this)
    }
    private fun haloHost() = Table().apply { pad(halo) }

    private val trackerContent = Table()
    private var trackerChat: Actor? = null
    private val trackerPane = pane("AtlasTracker", trackerContent)
    private var dockCompact: Boolean? = null
    private val unitContent = haloHost()
    private val unitPane = pane("AtlasUnitDock", unitContent)
    private val mapContent = haloHost()
    private val mapCell: Cell<MinimapHolder> = mapContent.add(minimap).expand()
    private val mapPane = pane("AtlasMinimap", mapContent)
    private val tileInfoContent = haloHost().apply { add(tileInfo).expand().fill() }
    private val tileInfoPane = pane("AtlasTileInfo", tileInfoContent)
    private val turnContent = haloHost().apply { add(turn).grow() }
    private val turnPane = pane("AtlasTurnAction", turnContent)
    private var selectedTab = 0
    private val tabButtons: Table = AtlasWidgets.tabs(listOf("World", "Activity", "Research", "Selection", "Map"), 0) {
        selectTab(it)
    }.apply {
        children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextButton>().forEach {
            it.label.wrap = false
            getCell(it).width(maxOf(84f * screen.game.settings.fontSizeMultiplier, it.prefWidth + 12f))
        }
    }
    private val tabs: AutoScrollPane = pane("AtlasWorldTabs", tabButtons).apply { setScrollingDisabled(false, true) }
    var regions = WorldHudLayout.calculate(screen.stage.width, screen.stage.height, screen.game.settings.fontSizeMultiplier, true)
        private set
    /** Stage-space band (from the bottom, from the top) that the notification layer must keep clear. */
    var notificationCover = Vector2(regions.rightStackTop, regions.header.height)
        private set

    init {
        refreshTracker()
        arrangeUnitContent(screen.stage.width < WorldHudLayout.compactBreakpoint)
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        if (index == 1) screen.stage.root.children.filterIsInstance<AgentActivityFeed>().firstOrNull()?.show()
        layout()
        tabs.validate()
        val button = tabButtons.children[index]
        tabs.scrollTo(button.x, button.y, button.width, button.height, true, false)
        tabs.updateVisualScroll()
    }

    /** The chat button joins the tracker whenever it becomes visible, not only at construction. */
    private fun refreshTracker() {
        val chat = screen.chatButton?.takeIf { it.isVisible }
        if (trackerContent.hasChildren() && chat === trackerChat) return
        trackerChat = chat
        trackerContent.clearChildren()
        trackerContent.add(tracker).left().row()
        if (chat != null) trackerContent.add(chat).left().padTop(6f)
    }

    private fun arrangeUnitContent(compact: Boolean) {
        if (dockCompact == compact) return
        dockCompact = compact
        unitContent.clearChildren()
        unitContent.add(unit).bottom()
        if (compact) unitContent.row()
        unitContent.add(unitActions).bottom().pad(if (compact) 8f else 0f, if (compact) 0f else 8f, 0f, 0f)
    }

    private fun Actor.prefSize(): Vector2 = if (this is Layout) Vector2(prefWidth, prefHeight) else Vector2(width, height)
    /** Content size of a [haloHost], without the halo margin. */
    private fun Table.cardSize(): Vector2 { invalidate(); return Vector2(prefWidth - 2 * halo, prefHeight - 2 * halo) }

    /** A unit, city or spy is selected. The idle-units summary alone does not open the card. */
    private fun hasSelection(): Boolean {
        val unitShown = (unit as? UnitTable)?.let { it.isVisible && it.hasSelection } ?: unit.hasVisibleContent()
        return unitShown || unitActions.hasVisibleContent()
    }

    fun layout() {
        val feed = screen.stage.root.children.filterIsInstance<AgentActivityFeed>().firstOrNull()
        val stageWidth = screen.stage.width
        val stageHeight = screen.stage.height
        val fontScale = screen.game.settings.fontSizeMultiplier
        val compact = stageWidth < WorldHudLayout.compactBreakpoint
        refreshTracker()
        arrangeUnitContent(compact)
        trackerContent.invalidate()
        val selection = hasSelection()
        val showTileInfo = tileInfo.isVisible && ((tileInfo as? TileInfoTable)?.hasContent ?: tileInfo.hasVisibleContent())
        regions = WorldHudLayout.calculate(stageWidth, stageHeight, fontScale, HudContent(
            tracker = if (tracker.hasVisibleContent()) trackerContent.prefSize() else null,
            selection = if (selection) unitContent.cardSize() else null,
            // A feed the user moved elsewhere floats over the map; only the docked one takes the column.
            activityOpen = feed?.isVisible == true && feed.isDocked,
            turnAction = turnContent.cardSize(),
            minimap = minimap.prefSize().takeIf { !minimap.isMaximized },
            tileInfo = if (showTileInfo) tileInfoContent.cardSize() else null,
        ))
        fun Actor.place(r: Rectangle, margin: Float = halo) = setBounds(r.x - margin, r.y - margin, r.width + 2 * margin, r.height + 2 * margin)
        val turnTop = regions.turnAction.y + regions.turnAction.height
        trackerPane.place(regions.tracker, 0f)
        trackerPane.isVisible = regions.tracker.height > 0f && (!compact || selectedTab == 2)

        var selectionRect: Rectangle? = null
        if (!compact) {
            unitPane.isVisible = selection
            if (selection) selectionRect = regions.unitDock
        } else if (selectedTab == 3) {
            unitPane.isVisible = true
            selectionRect = regions.unitDock
        } else if (selectedTab == 0 && selection) {
            // Compact World tab: the selection floats directly above the turn action.
            unitPane.isVisible = true
            val size = unitContent.cardSize()
            selectionRect = Rectangle(gap, turnTop + gap, stageWidth - gap * 2,
                minOf(size.y, stageHeight * .35f, (regions.header.y - turnTop - gap * 2).coerceAtLeast(1f)))
        } else unitPane.isVisible = false
        selectionRect?.let { unitPane.place(it) }

        mapCell.align(if (compact || minimap.isMaximized) Align.center else Align.bottomRight)
        if (minimap.isMaximized) mapPane.place(Rectangle(gap, turnTop + gap, stageWidth - gap * 2,
            (regions.header.y - turnTop - gap * 3).coerceAtLeast(1f)))
        else mapPane.place(if (compact) regions.minimap else regions.minimapCard)
        mapPane.isVisible = !compact || selectedTab == 4
        tileInfoPane.isVisible = !compact && showTileInfo && !minimap.isMaximized
        if (tileInfoPane.isVisible) tileInfoPane.place(regions.tileInfo)
        turnPane.place(regions.turnAction)

        tabs.isVisible = compact
        screen.topBar.isVisible = !compact || selectedTab == 0
        tabs.setBounds(gap, if (compact && selectedTab != 0) stageHeight - WorldHudLayout.compactTabsHeight * fontScale else regions.header.y,
            stageWidth - gap * 2, WorldHudLayout.compactTabsHeight * fontScale)
        applyNotificationLayer()
        feed?.setAtlasViewport(regions.activity, !compact || selectedTab == 1)
        placeBattlePreview(if (unitPane.isVisible) selectionRect else null)

        // Notifications clear the header (and a visible top-right control box) and the right card stack.
        val control = screen.stage.root.children.filterIsInstance<AgentMatchControlTable>().firstOrNull { it.isVisible }
        val coveredTop = maxOf(regions.header.height, control?.let { stageHeight - it.y + gap } ?: 0f)
        val coveredBottom = if (compact) (selectionRect?.takeIf { selectedTab == 0 }?.let { it.y + it.height } ?: turnTop)
            else regions.rightStackTop
        notificationCover = Vector2(coveredBottom, coveredTop)

        // The turn action is last in z-order so optional inspection can never cover it.
        listOf(unitContent, mapContent, tileInfoContent, turnContent).forEach { it.invalidate() }
        listOf(trackerPane, unitPane, mapPane, tileInfoPane, turnPane).forEach { it.invalidate(); it.validate() }
        turnPane.toFront()
        tabs.toFront()
    }

    /** Compact inspection tabs occlude the notification layer; only the World tab shows it. */
    internal fun applyNotificationLayer() {
        screen.notificationsScroll?.setAtlasLayerVisible(!regions.compact || selectedTab == 0)
    }

    /** The attack preview sits directly above the selection card, inside the stage. */
    private fun placeBattlePreview(selection: Rectangle?) {
        val battle = screen.stage.root.children.filterIsInstance<BattleTable>().firstOrNull { it.isVisible } ?: return
        val stage = screen.stage
        val bottom = selection?.let { it.y + it.height + gap } ?: gap
        val centre = selection?.let { it.x + it.width / 2f } ?: stage.width / 2f
        battle.setPosition((centre - battle.width / 2f).coerceIn(0f, maxOf(0f, stage.width - battle.width)),
            bottom.coerceAtMost(maxOf(0f, regions.header.y - battle.height)))
    }

    fun setVisible(visible: Boolean) {
        listOf(trackerPane, unitPane, mapPane, tileInfoPane, turnPane, tabs).forEach { it.isVisible = visible }
        if (visible) layout()
        else {
            screen.notificationsScroll?.setAtlasLayerVisible(false)
            screen.stage.root.children.filterIsInstance<AgentActivityFeed>().forEach { it.setAtlasViewport(regions.activity, false) }
            screen.stage.root.children.filterIsInstance<AgentMatchControlTable>().forEach { it.isVisible = false }
        }
    }

    /** Actual occupied rectangles, after visibility/collapse, for capture evidence. */
    fun occupiedBounds(): List<Rectangle> = listOf(trackerPane, unitPane, mapPane, tileInfoPane, turnPane, tabs)
        .filter { it.isVisible }.map { Rectangle(it.x, it.y, it.width, it.height) }
}
