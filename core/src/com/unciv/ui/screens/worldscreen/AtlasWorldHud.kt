package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.worldscreen.status.AgentActivityFeed

/** Bounded, scrollable hosts retain the actual gameplay actors, listeners and selection state. */
class AtlasWorldHud(
    private val screen: WorldScreen,
    tracker: Actor, unit: Actor, actions: Actor, private val minimap: com.unciv.ui.screens.worldscreen.minimap.MinimapHolder, turn: Actor, tileInfo: Actor
) {
    private fun pane(name: String, content: Actor) = AutoScrollPane(content).apply {
        this.name = name
        setOverscroll(false, false)
        setFadeScrollBars(false)
        GameUiStyle.applyWidget(this)
        screen.stage.addActor(this)
    }
    private val trackerContent = Table().apply {
        add(tracker).left().row()
        screen.chatButton?.let { if (it.isVisible) add(it).left().padTop(6f) }
    }
    private val trackerPane = pane("AtlasTracker", trackerContent)
    private val unitContent = Table().apply {
        background = GameUiStyle.panelBackground("Atlas/World/Selection")
        add(unit).top().pad(8f)
        add(actions).top().pad(8f)
    }
    private val unitPane = pane("AtlasUnitDock", unitContent)
    private val mapContent = Table().apply {
        add(minimap).row()
        add(tileInfo).padTop(8f)
    }
    private val mapPane = pane("AtlasMinimap", mapContent)
    private val turnPane = pane("AtlasTurnAction", turn)
    private var selectedTab = 0
    private val tabButtons: Table = AtlasWidgets.tabs(listOf("World", "Activity", "Research", "Selection", "Map"), 0) {
        selectedTab = it
        layout()
    }.apply {
        children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.TextButton>().forEach {
            it.label.wrap = false
            getCell(it).width(maxOf(84f * screen.game.settings.fontSizeMultiplier, it.prefWidth + 12f))
        }
    }
    private val tabs: AutoScrollPane = pane("AtlasWorldTabs", tabButtons).apply { setScrollingDisabled(false, true) }
    var regions = WorldHudLayout.calculate(screen.stage.width, screen.stage.height, screen.game.settings.fontSizeMultiplier, true)
        private set

    fun layout() {
        val feed = screen.stage.root.children.filterIsInstance<AgentActivityFeed>().firstOrNull()
        regions = WorldHudLayout.calculate(screen.stage.width, screen.stage.height, screen.game.settings.fontSizeMultiplier, feed?.isVisible == true)
        fun Actor.place(r: Rectangle) { setBounds(r.x, r.y, r.width, r.height) }
        trackerPane.place(regions.tracker)
        unitPane.place(regions.unitDock)
        mapPane.place(if (minimap.isMaximized) Rectangle(8f, regions.turnAction.height + 16f,
            screen.stage.width - 16f, (regions.header.y - regions.turnAction.height - 24f).coerceAtLeast(1f)) else regions.minimap)
        turnPane.place(regions.turnAction)
        tabs.isVisible = regions.compact
        screen.topBar.isVisible = !regions.compact || selectedTab == 0
        tabs.setBounds(8f, if (regions.compact && selectedTab != 0) screen.stage.height - 44f * screen.game.settings.fontSizeMultiplier else regions.header.y, screen.stage.width - 16f, 44f * screen.game.settings.fontSizeMultiplier)
        trackerPane.isVisible = !regions.compact || selectedTab == 2
        unitPane.isVisible = !regions.compact || selectedTab == 3
        mapPane.isVisible = !regions.compact || selectedTab == 4
        screen.notificationsScroll?.setAtlasLayerVisible(!regions.compact || selectedTab == 0)
        feed?.setAtlasViewport(regions.activity, !regions.compact || selectedTab == 1)
        // The turn action is last in z-order so optional inspection can never cover it.
        listOf(trackerPane, unitPane, mapPane, turnPane).forEach { it.invalidate(); it.validate() }
        turnPane.toFront()
        tabs.toFront()
    }

    fun setVisible(visible: Boolean) {
        listOf(trackerPane, unitPane, mapPane, turnPane, tabs).forEach { it.isVisible = visible }
        if (visible) layout()
        else {
            screen.notificationsScroll?.setAtlasLayerVisible(false)
            screen.stage.root.children.filterIsInstance<AgentActivityFeed>().forEach { it.setAtlasViewport(regions.activity, false) }
            screen.stage.root.children.filterIsInstance<com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable>().forEach { it.isVisible = false }
        }
    }

    /** Actual occupied rectangles, after visibility/collapse, for capture evidence. */
    fun occupiedBounds(): List<Rectangle> = listOf(trackerPane, unitPane, mapPane, turnPane, tabs)
        .filter { it.isVisible }.map { Rectangle(it.x, it.y, it.width, it.height) }
}
