package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.AutoScrollPane

/** Reflows the same list/actions actors; drafts, focus and file-selection callbacks stay owned by the screen. */
internal class AtlasSaveBrowser(
    saves: VerticalFileListScrollPane,
    actions: Table,
    private val fontScale: Float,
) : Table() {
    private val listPanel = Table().apply {
        background = GameUiStyle.panelBackground("Atlas/Saves/List", 1f)
        pad(12f)
        add("Saved games".toLabel(GameUiStyle.muted, GameUiStyle.Type.label)).left().growX().row()
        add(saves).grow().minWidth(0f).minHeight(0f).padTop(8f)
    }
    private val actionsScroll = AutoScrollPane(actions).apply {
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        GameUiStyle.applyWidget(this)
    }
    private val actionPanel = Table().apply {
        background = GameUiStyle.panelBackground("Atlas/Saves/Actions", 1f)
        pad(12f)
        add(actionsScroll).grow().minWidth(0f).minHeight(0f)
    }
    private var stacked: Boolean? = null

    init {
        name = "AtlasSaveBrowser"
        pad(12f)
        GameUiStyle.applyWidget(saves)
    }

    override fun layout() {
        val narrow = width < 760f * fontScale
        if (stacked != narrow) {
            stacked = narrow
            clearChildren()
            if (narrow) {
                add(listPanel).growX().minWidth(0f).minHeight(0f).row()
                add(actionPanel).grow().minWidth(0f).minHeight(0f).padTop(8f)
            } else {
                add(listPanel).grow().minWidth(0f).minHeight(0f).padRight(12f)
                add(actionPanel).grow().minWidth(0f).minHeight(0f)
            }
        }
        if (narrow) getCell(listPanel).height((height - 32f).coerceAtLeast(1f) * 0.48f)
        else {
            getCell(listPanel).width((width - 36f).coerceAtLeast(1f) * 0.55f)
            getCell(actionPanel).width((width - 36f).coerceAtLeast(1f) * 0.45f)
        }
        super.layout()
    }
}
