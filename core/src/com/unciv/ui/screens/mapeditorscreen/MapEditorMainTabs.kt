package com.unciv.ui.screens.mapeditorscreen

import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorEditTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorGenerateTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorLoadTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorModsTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorOptionsTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorSaveTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorViewTab

class MapEditorMainTabs(
    editorScreen: MapEditorScreen
) : TabbedPager(
    minimumWidth = if (GameUiStyle.isAtlas) editorScreen.getToolsWidth() else 0f,
    maximumWidth = if (GameUiStyle.isAtlas) editorScreen.getToolsWidth() else Float.MAX_VALUE,
    minimumHeight = editorScreen.getToolsHeight() - if (GameUiStyle.isAtlas) 52f else 0f,
    maximumHeight = editorScreen.getToolsHeight() - if (GameUiStyle.isAtlas) 52f else 0f,
    headerFontSize = if (GameUiStyle.isAtlas) GameUiStyle.Type.label else 24,
    headerFontColor = if (GameUiStyle.isAtlas) GameUiStyle.ink else com.badlogic.gdx.graphics.Color.WHITE,
    highlightColor = if (GameUiStyle.isAtlas) GameUiStyle.accent else com.badlogic.gdx.graphics.Color.BLUE,
    backgroundColor = if (GameUiStyle.isAtlas) GameUiStyle.surface else com.unciv.ui.screens.basescreen.BaseScreen.skinStrings.skinConfig.baseColor.darken(.5f),
    capacity = 7
) {
    val view = MapEditorViewTab(editorScreen)
    val generate = MapEditorGenerateTab(editorScreen, headerHeight)
    val edit = MapEditorEditTab(editorScreen, headerHeight)
    val load = MapEditorLoadTab(editorScreen, headerHeight)
    val save = MapEditorSaveTab(editorScreen, headerHeight)
    val mods = MapEditorModsTab(editorScreen)
    val options = MapEditorOptionsTab(editorScreen)

    init {
        prefWidth = editorScreen.getToolsWidth()

        addPage("View", view,
            ImageGetter.getImage("OtherIcons/Search"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('i'))
        addPage("Generate", generate,
            ImageGetter.getImage("OtherIcons/New"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('n'))
        addPage("Edit", edit,
            ImageGetter.getImage("OtherIcons/Terrains"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('e'))
        addPage("Load", load,
            ImageGetter.getImage("OtherIcons/Load"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('l'),
            disabled = load.noMapsAvailable())
        addPage("Save", save,
            ImageGetter.getImage("OtherIcons/Checkmark"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('s'))
        addPage("Mods", mods,
            ImageGetter.getImage("OtherIcons/Mods"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('d'))
        addPage("Options", options,
            ImageGetter.getImage("OtherIcons/Settings"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('o'))
        selectPage(0)

        headerScroll.fadeScrollBars = false
    }
}
