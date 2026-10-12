package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.AutoScrollPane

/** Existing setup actors keep their draft state and callbacks while navigation changes presentation. */
internal class AtlasSetupLayout(
    options: GameOptionsTable,
    map: MapOptionsTable,
    players: PlayerPickerTable,
    stacked: Boolean,
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
) : Table() {
    private val content = Table()
    private val sections: List<Actor>

    init {
        name = "AtlasSetupLayout"
        pad(12f)
        val optionBody = Table().apply {
            top()
            add(options).growX().minWidth(0f).row()
            if (stacked) add(options.modCheckboxes).growX().minWidth(0f).padTop(12f)
        }
        fun scroll(actor: Actor) = AutoScrollPane(actor).apply {
            setOverscroll(false, false)
            GameUiStyle.applyWidget(this)
        }
        sections = listOf(scroll(optionBody), scroll(map), players)
        if (stacked) {
            val selected = selectedSection.coerceIn(sections.indices)
            add(AtlasWidgets.tabs(listOf("Game Options", "Map Options", "Civilizations"), selected) {
                showSection(it)
                onSectionSelected(it)
            }).growX().minWidth(0f).padBottom(12f).row()
            add(content).grow().minWidth(0f).minHeight(0f)
            showSection(selected)
        } else {
            sections.forEachIndexed { index, section ->
                val title = listOf("Game Options", "Map Options", "Civilizations")[index]
                add(Table().apply {
                    background = GameUiStyle.panelBackground("Atlas/Setup/Section", 1f)
                    pad(12f)
                    add(AtlasWidgets.header(title)).growX().minWidth(0f).row()
                    add(section).grow().minWidth(0f).minHeight(0f)
                }).grow().uniformX().minWidth(0f).minHeight(0f).padRight(if (index < 2) 8f else 0f)
            }
        }
    }

    private fun showSection(index: Int) {
        content.clearChildren()
        content.background = GameUiStyle.panelBackground("Atlas/Setup/Section", 1f)
        content.pad(12f)
        content.add(sections[index]).grow().minWidth(0f).minHeight(0f)
    }

    // The screen's fixed header/footer allocate the body. Long forms scroll inside each section.
    override fun getMinHeight() = 0f
    override fun getPrefHeight() = 0f
    override fun getMinWidth() = 0f
    override fun getPrefWidth() = 0f
}
