package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.translations.tr
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.equalizeColumns
import com.unciv.ui.components.widgets.SortableGrid
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.IconTextButton
import com.unciv.view.CivView

/**
 * Supplies the Unit sub-table for the Empire Overview
 */
class UnitOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData? = null
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    class UnitTabPersistableData : EmpireOverviewTabPersistableData(), SortableGrid.ISortState<UnitOverviewTabColumn> {
        @Transient
        var scrollY: Float? = null
        @Transient var query = ""

        override var sortedBy: UnitOverviewTabColumn = UnitOverviewTabColumn.Name
        override var direction = SortableGrid.SortDirection.None
        override fun isEmpty() = sortedBy == UnitOverviewTabColumn.Name && direction != SortableGrid.SortDirection.Descending
    }
    override val persistableData = (persistedData as? UnitTabPersistableData) ?: UnitTabPersistableData()

    private val fixedContent = Table()
    private val search = UncivTextField("Search", persistableData.query)

    // used for select()
    private var blinkAction: Action? = null
    private var blinkActor: Actor? = null
    private fun removeBlinkAction() {
        if (blinkAction == null || blinkActor == null) return
        blinkActor!!.removeAction(blinkAction)
        blinkAction = null
        blinkActor = null
    }

    //todo The original did its own sort:
/*
    val oldIterator = viewingPlayer.units.getCivUnits().sortedWith(
        compareBy(
            { it.displayName() },
            { !it.due },
            { it.currentMovement <= Constants.minimumMovementEpsilon },
            { abs(it.currentTile.position.x) + abs(it.currentTile.position.y) }
        )
    )
*/
    // We're using getCivUnits enumeration order instead so far.
    // - Adding that sort to the data source using Sequence would be inefficient because it is re-enumerated on every resort, and user sorts would mean that initial sort would largely be overridden
    // - Materializing the sort result would only waste memory
    // - But - isn't getCivUnits() deterministic anyway - controls "Next Unit" order? actually, getCivUnitsStartingAtNextDue would give that, it slices by an internal pointer

    //todo the comments and todo below are copied verbatim from CityOverviewTab - synergies?
    private val grid = SortableGrid(
        columns = UnitOverviewTabColumn.entries.asIterable(),
        data = viewingPlayer.getUnits().asIterable(),
        actionContext = this,
        sortState = persistableData,
        iconSize = 20f,
        paddingVert = 5f,
        paddingHorz = 8f,
        separateHeader = true
    ) { header, details, totals ->
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

    override fun getFixedContent() = fixedContent

    init {
        val supplyTableWidth = (overviewScreen.stage.width * 0.25f).coerceAtLeast(240f)
        val unitSupplyTable = UnitSupplyTable.create(overviewScreen, this, viewingPlayer.getCiv(), supplyTableWidth)
        if (GameUiStyle.isAtlas) {
            fixedContent.add(AtlasWidgets.field("Search", search)).width((overviewScreen.stage.width - 40f).coerceAtMost(720f))
                .minWidth(0f).left().row()
            search.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) { update(true) }
            })
        }
        fixedContent.add(unitSupplyTable).align(Align.top).padBottom(10f).row()
        fixedContent.add(grid.getHeader()).grow()
        top()
        add(grid)
        if (GameUiStyle.isAtlas && persistableData.query.isNotEmpty()) update(true)
        applyAtlasPresentation()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (persistableData.scrollY != null)
            pager.setPageScrollY(index, persistableData.scrollY!!)
        super.activated(index, caption, pager)
    }
    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        persistableData.scrollY = pager.getPageScrollY(index)
        removeBlinkAction()
    }

    internal fun update(unitsChanged: Boolean = false) {
        if (GameUiStyle.isAtlas) {
            persistableData.query = search.text
            grid.update(viewingPlayer.getUnits().filter {
                it.displayName().tr(hideIcons = true).contains(persistableData.query, ignoreCase = true)
            }.asIterable())
        } else if (unitsChanged) grid.update(viewingPlayer.getUnits().asIterable())
        grid.update()
        applyAtlasPresentation()
    }

    override fun select(selection: String): Float? {
        val cell = grid.findCell<IconTextButton>("unit-$selection")
            ?: return null
        val button = cell.actor
        val scrollY = (0 until cell.row)
            .map { grid.details.getRowHeight(it) }.sum() -
            (parent.height - grid.details.getRowHeight(cell.row)) / 2

        removeBlinkAction()
        blinkAction = Actions.repeat(3, Actions.sequence(
            Actions.fadeOut(0.17f),
            Actions.fadeIn(0.17f)
        ))
        blinkActor = button
        button.addAction(blinkAction)
        return scrollY
    }
}
