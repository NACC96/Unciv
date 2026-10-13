package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.AutoScrollPane
import kotlin.math.roundToInt

/** Atlas presentation owns only actors and transient display state. CityScreen owns every action. */
internal class AtlasCityLayout(private val screen: CityScreen) {
    private val header = Table()
    private val tabs = Table()
    private val details = Table()
    private val detailBody = Table()
    private val detailScroll = AutoScrollPane(detailBody)
    private val footer = Table()
    private var hasLayout = false

    init {
        header.background = GameUiStyle.panelBackground("Atlas/City/Header", 1f)
        details.background = GameUiStyle.panelBackground("Atlas/City/Details", 1f)
        footer.background = GameUiStyle.panelBackground("Atlas/City/Footer", 1f)
        detailScroll.setOverscroll(false, false)
        detailScroll.setScrollingDisabled(true, false)
        for (actor in listOf(header, tabs, details, footer)) screen.stage.addActor(actor)
        // Reparent once. Refreshes change content without losing screen-level selection or filters.
        screen.cityPickerTable.remove()
        screen.cityStatsTable.remove()
        screen.selectedConstructionTable.remove()
        screen.tileTable.remove()
        screen.exitCityButton.remove()
    }

    fun rememberDisplayState() {
        if (hasLayout && detailScroll.stage != null) screen.displayState.detailScroll = detailScroll.scrollY
    }

    fun update() {
        rememberDisplayState()
        val regions = screen.atlasRegions
        val state = screen.displayState
        val city = screen.cityView
        header.clearChildren()
        header.pad(8f)
        header.add(screen.cityPickerTable).growX().minWidth(0f).row()
        val growth = when {
            city.isStarving() -> "[${city.getNumTurnsToStarvation()}] turns to lose population".tr()
            city.isGrowing() -> "[${city.getNumTurnsToNewPopulation()}] turns to new population".tr()
            else -> "Stopped population growth".tr()
        }
        val yields = Stat.entries.filter { it != Stat.Faith || city.viewingCiv().isReligionEnabled() }
            .joinToString("   ") { stat ->
                val value = if (stat == Stat.Happiness) city.getHappinessList().values.sum() else city.getCurrentCityStats()[stat]
                "${stat.character} ${value.roundToInt()}"
            }
        val subtitle = if (screen.canChangeState) growth else "${"Inspecting".tr()} · $growth"
        header.add("$subtitle\n$yields".toLabel(GameUiStyle.muted, GameUiStyle.Type.body).apply {
            wrap = true
            setAlignment(Align.center)
        }).growX().minWidth(0f)
        header.place(regions.header)

        tabs.clearChildren()
        tabs.isVisible = regions.narrow
        if (regions.narrow) {
            val labels = if (screen.isSpying) listOf("Map", "City") else listOf("Map", "Production", "City")
            val selected = if (screen.isSpying) (if (state.page == 0) 0 else 1) else state.page
            tabs.add(AtlasWidgets.tabs(labels.map { it.tr() }, selected) { index ->
                state.page = if (screen.isSpying && index == 1) 2 else index
                update()
            }).grow()
            tabs.place(regions.tabs)
        }
        screen.mapScrollPane.isVisible = !regions.narrow || state.page == 0
        screen.mapScrollPane.place(regions.map)
        if (!hasLayout) screen.mapScrollPane.apply {
            layout()
            scrollPercentX = .5f
            scrollPercentY = .5f
            updateVisualScroll()
        }
        screen.constructionsTable.layoutAtlas(regions, !screen.isSpying,
            !regions.narrow || state.page == 1)

        details.clearChildren()
        details.pad(8f)
        details.isVisible = !regions.narrow || state.page == 2
        val selection = screen.selectedConstruction != null || screen.selectedTile != null
        details.add(AtlasWidgets.tabs(listOf("City", "Details").map { it.tr() }, if (state.showSelection) 1 else 0) {
            state.showSelection = it == 1
            update()
        }).growX().minWidth(0f).row()
        if (state.showSelection) {
            detailBody.clearChildren()
            val actor = when {
                screen.selectedTile != null -> screen.tileTable
                screen.selectedConstruction != null -> screen.selectedConstructionTable
                else -> AtlasWidgets.header("Details", "Select a construction or tile to inspect it")
            }
            detailBody.add(actor).width((regions.details.width - 24f).coerceAtLeast(0f)).growX().top()
            details.add(detailScroll).grow().minSize(0f, 0f)
        } else {
            details.add(screen.cityStatsTable).grow().minSize(0f, 0f)
        }
        details.place(regions.details)
        detailScroll.layout()
        detailScroll.scrollY = state.detailScroll
        detailScroll.updateVisualScroll()

        footer.clearChildren()
        footer.pad(4f)
        GameUiStyle.applyWidget(screen.razeCityButtonHolder)
        GameUiStyle.apply(screen.exitCityButton, primary = true)
        footer.add(screen.razeCityButtonHolder).minWidth(0f).growX()
        footer.add(screen.exitCityButton).minWidth(0f).growX().padLeft(8f)
        footer.place(regions.footer)
        // Content is capped by scroll panes; the navigation and primary actions never scroll away.
        screen.cityStatsTable.layout()
        screen.cityStatsTable.restoreDisplayState()
        if (!selection) state.showSelection = false
        hasLayout = true
    }

    private fun Actor.place(rectangle: Rectangle) {
        setBounds(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
        if (this is Table) validate()
    }
}
