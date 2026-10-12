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
    tracker: Actor, unit: Actor, actions: Actor, minimap: Actor, turn: Actor, tileInfo: Actor
) {
    private fun pane(name: String, content: Actor) = AutoScrollPane(content).apply {
        this.name = name
        setOverscroll(false, false)
        setFadeScrollBars(false)
        GameUiStyle.applyWidget(this)
        screen.stage.addActor(this)
    }
    private val trackerPane = pane("AtlasTracker", tracker)
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
    private val tabs = AtlasWidgets.tabs(listOf("World", "Activity", "Research", "Selection", "Map"), 0) {
        selectedTab = it
        layout()
    }.also { it.name = "AtlasWorldTabs"; screen.stage.addActor(it) }
    var regions = WorldHudLayout.calculate(screen.stage.width, screen.stage.height, screen.game.settings.fontSizeMultiplier, true)
        private set

    fun layout() {
        val feed = screen.stage.root.children.filterIsInstance<AgentActivityFeed>().firstOrNull()
        regions = WorldHudLayout.calculate(screen.stage.width, screen.stage.height, screen.game.settings.fontSizeMultiplier, feed?.isVisible == true)
        fun Actor.place(r: Rectangle) { setBounds(r.x, r.y, r.width, r.height) }
        trackerPane.place(regions.tracker)
        unitPane.place(regions.unitDock)
        mapPane.place(regions.minimap)
        turnPane.place(regions.turnAction)
        tabs.isVisible = regions.compact
        screen.topBar.isVisible = !regions.compact || selectedTab == 0
        tabs.setBounds(8f, if (regions.compact && selectedTab != 0) screen.stage.height - 44f * screen.game.settings.fontSizeMultiplier else regions.header.y, screen.stage.width - 16f, 44f * screen.game.settings.fontSizeMultiplier)
        trackerPane.isVisible = !regions.compact || selectedTab == 2
        unitPane.isVisible = !regions.compact || selectedTab == 3
        mapPane.isVisible = !regions.compact || selectedTab == 4
        feed?.setAtlasViewport(regions.activity, !regions.compact || selectedTab == 1)
        // The turn action is last in z-order so optional inspection can never cover it.
        listOf(trackerPane, unitPane, mapPane, turnPane).forEach { it.invalidate(); it.validate() }
        turnPane.toFront()
        tabs.toFront()
    }

    fun setVisible(visible: Boolean) {
        listOf(trackerPane, unitPane, mapPane, turnPane, tabs).forEach { it.isVisible = visible }
        if (visible) layout()
    }

    /** Actual occupied rectangles, after visibility/collapse, for capture evidence. */
    fun occupiedBounds(): List<Rectangle> = listOf(trackerPane, unitPane, mapPane, turnPane, tabs)
        .filter { it.isVisible }.map { Rectangle(it.x, it.y, it.width, it.height) }
}
