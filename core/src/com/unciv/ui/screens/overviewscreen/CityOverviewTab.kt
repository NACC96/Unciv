package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.equalizeColumns
import com.unciv.ui.components.widgets.SortableGrid
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.ISortableGridContentProvider
import com.unciv.view.CivView

/**
 *  Provides the Cities tab for Empire Overview.
 *
 *  Uses [SortableGrid], so all actual content implementation is in the [CityOverviewTabColumn] enum.
 */
class CityOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class CityTabPersistableData : EmpireOverviewTabPersistableData(),
        SortableGrid.ISortState<ISortableGridContentProvider<com.unciv.logic.city.City, EmpireOverviewScreen>> {
        override var sortedBy: ISortableGridContentProvider<com.unciv.logic.city.City, EmpireOverviewScreen> = CityOverviewTabColumn.CityColumn
        @Transient var query = ""
        override var direction: SortableGrid.SortDirection = SortableGrid.SortDirection.None
        override fun isEmpty() = sortedBy == CityOverviewTabColumn.CityColumn && direction != SortableGrid.SortDirection.Descending
    }

    override val persistableData = (persistedData as? CityTabPersistableData) ?: CityTabPersistableData()

    private val grid = SortableGrid(
        columns = CityOverviewTabColumn.getColumns(viewingPlayer.getCiv()),
        data = viewingPlayer.getCiv().cities,
        actionContext = overviewScreen,
        sortState = persistableData,
        iconSize = 50f,  //if you set this too low, there is a chance that the tables will be misaligned
        paddingVert = 5f,
        paddingHorz = 8f,
        separateHeader = true
    ) {
        header, details, totals ->
        // Notes: header.parent is the LinkedScrollPane of TabbedPager. Its linked twin is details.parent.parent.parent however!
        // horizontal "slack" if available width > content width is taken up between SortableGrid and CityOverviewTab for the details,
        // but not so for the header. We must force the LinkedScrollPane somehow (no? how?) to do so - or the header Table itself.

        AtlasEmpireUi.grid(header, details, totals)
        equalizeColumns(details, header, totals)
        // todo Kludge! Positioning and alignment of the header Table within its parent has quirks when content width < stage width
        //      This code should likely be included in SortableGrid anyway?
        if (header.width < this.width) header.width = this.width
        this.validate()
    }

    private val fixedContent = Table()
    private val search = UncivTextField("Search", persistableData.query)
    override fun getFixedContent() = if (GameUiStyle.isAtlas) fixedContent else grid.getHeader()

    private fun filterCities() {
        persistableData.query = search.text
        grid.update(viewingPlayer.getCiv().cities.filter {
            it.name.tr(hideIcons = true).contains(persistableData.query, ignoreCase = true)
        })
        applyAtlasPresentation()
    }

    init {
        top()
        if (GameUiStyle.isAtlas) {
            fixedContent.add(AtlasWidgets.field("Search", search)).width((overviewScreen.stage.width - 40f).coerceAtMost(720f))
                .minWidth(0f).left().row()
            fixedContent.add(grid.getHeader()).growX().minWidth(0f)
            search.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = filterCities()
            })
            if (persistableData.query.isNotEmpty()) filterCities()
        }
        add(grid)
        applyAtlasPresentation()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        // Being here can mean the user closed a CityScreen we opened from the first column - or the overview was just opened.
        // To differentiate, the EmpireOverviewScreen.resume code lies and passes an empty caption - a kludge, but a clean
        // callback architecture would mean a lot more work
        if (caption.isEmpty()) {
            if (GameUiStyle.isAtlas) filterCities() else grid.updateDetails()
        }
        applyAtlasPresentation()
    }
}
