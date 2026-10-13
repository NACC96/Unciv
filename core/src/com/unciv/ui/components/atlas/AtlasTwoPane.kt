package com.unciv.ui.components.atlas

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.AutoScrollPane

/** A bounded browser/actions surface. Reflow moves existing actors without recreating their input state. */
class AtlasTwoPane(first: Actor, second: Actor, private val fontScale: Float) : Table() {
    private fun panel(actor: Actor) = Table().apply {
        background = GameUiStyle.panelBackground("Atlas/Browser/Panel", 1f)
        pad(12f)
        val pane = if (actor is ScrollPane) actor else AutoScrollPane(actor)
        pane.setOverscroll(false, false)
        GameUiStyle.applyWidget(pane)
        add(pane).grow().minWidth(0f).minHeight(0f)
    }
    private val firstPanel = panel(first)
    private val secondPanel = panel(second)
    private var stacked: Boolean? = null

    init { pad(12f) }

    override fun layout() {
        val narrow = width < 850f * fontScale
        if (narrow != stacked) {
            stacked = narrow
            clearChildren()
            if (narrow) {
                add(firstPanel).growX().minWidth(0f).minHeight(0f).row()
                add(secondPanel).grow().minWidth(0f).minHeight(0f).padTop(8f)
            } else {
                add(firstPanel).grow().minWidth(0f).minHeight(0f).padRight(12f)
                add(secondPanel).grow().minWidth(0f).minHeight(0f)
            }
        }
        if (narrow) getCell(firstPanel).height((height - 32f).coerceAtLeast(1f) * 0.52f)
        else {
            getCell(firstPanel).width((width - 36f).coerceAtLeast(1f) * 0.55f)
            getCell(secondPanel).width((width - 36f).coerceAtLeast(1f) * 0.45f)
        }
        super.layout()
    }

    override fun getMinWidth() = 0f
    override fun getPrefWidth() = 0f
    override fun getMinHeight() = 0f
    override fun getPrefHeight() = 0f
}
