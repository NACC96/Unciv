package com.unciv.ui.screens.mapeditorscreen.tabs

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.Constants
import com.unciv.logic.files.MapSaver
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.logic.map.TileMap
import com.unciv.models.translations.tr
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorFilesScroll
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

class MapEditorSaveTab(
    private val editorScreen: MapEditorScreen,
    headerHeight: Float
): Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val mapFiles = MapEditorFilesScroll(
        initWidth = editorScreen.getToolsWidth() - 40f,
        includeMods = false,
        this::selectFile,
        this::saveHandler
    )

    private val saveButton = "Save map".toTextButton()
    private val deleteButton = "Delete map".toTextButton()
    private val quitButton = "Exit map editor".toTextButton()

    private val mapNameTextField = UncivTextField("Map Name")

    private var chosenMap: FileHandle? = null

    init {
        if (GameUiStyle.isAtlas) {
            top()
            pad(8f)
            defaults().growX().minWidth(0f)
        }
        mapNameTextField.maxLength = 100
        mapNameTextField.textFieldFilter = TextField.TextFieldFilter { _, char -> char != '\\' && char != '/' }
        mapNameTextField.selectAll()
        // do NOT take the keyboard focus here! We're not even visible.
        if (GameUiStyle.isAtlas) {
            add(AtlasWidgets.field("Map Name", mapNameTextField)).growX().padBottom(8f).row()
        } else add(mapNameTextField).pad(10f).fillX().row()

        val buttonTable = Table(skin)
        buttonTable.defaults().pad(if (GameUiStyle.isAtlas) 4f else 10f).growX().minWidth(0f)
        saveButton.onActivation { saveHandler() }
        saveButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        mapNameTextField.keyShortcuts.add(KeyCharAndCode.RETURN) { if (saveButton.isEnabled) saveHandler() }
        mapNameTextField.onChange {
            saveButton.isEnabled = mapNameTextField.text.isNotBlank()
        }
        buttonTable.add(saveButton)

        deleteButton.onActivation { deleteHandler() }
        deleteButton.keyShortcuts.add(KeyCharAndCode.DEL)
        buttonTable.add(deleteButton)
        if (GameUiStyle.isAtlas) {
            deleteButton.style = BaseScreen.skin.get("negative", com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle::class.java)
            GameUiStyle.applyWidget(deleteButton)
            deleteButton.label.wrap = true
        }

        quitButton.onClick(editorScreen::closeEditor)
        if (!GameUiStyle.isAtlas) buttonTable.add(quitButton)
        if (GameUiStyle.isAtlas) {
            GameUiStyle.apply(saveButton, primary = true)
            saveButton.label.wrap = true
        }
        buttonTable.pack()

        val fileTableHeight = (editorScreen.getToolsHeight() - headerHeight - mapNameTextField.prefHeight - buttonTable.height -
            if (GameUiStyle.isAtlas) 62f else 22f).coerceAtLeast(60f)
        add(mapFiles).height(fileTableHeight).fillX().row()
        add(buttonTable).row()
    }

    private fun setSaveButton(enabled: Boolean) {
        saveButton.isEnabled = enabled
        saveButton.setText((if (enabled) "Save map" else Constants.working).tr())
    }

    private fun saveHandler(mapFile: FileHandle? = null) {
        if (mapFile != null) mapNameTextField.text = mapFile.name()
        if (mapNameTextField.text.isBlank()) return
        editorScreen.tileMap.mapParameters.name = mapNameTextField.text
        editorScreen.tileMap.mapParameters.type = MapGeneratedMainType.custom
        editorScreen.tileMap.description = editorScreen.descriptionTextField.text
        setSaveButton(false)
        editorScreen.startBackgroundJob("MapSaver", false) { saverThread() }
    }

    private fun deleteHandler() {
        if (chosenMap == null) return
        ConfirmPopup(
            editorScreen,
            "Are you sure you want to delete this map?",
            "Delete map",
        ) {
            chosenMap!!.delete()
            mapFiles.updateMaps()
        }.open()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        pager.setScrollDisabled(true)
        editorScreen.enableKeyboardPanningListener(enable = false)
        mapFiles.updateMaps()
        selectFile(null)
    }

    override fun deactivated(index: Int, caption: String, pager: TabbedPager) {
        editorScreen.enableKeyboardPanningListener(enable = true)
        pager.setScrollDisabled(false)
        stage.keyboardFocus = null
    }

    private fun selectFile(file: FileHandle?) {
        chosenMap = file
        mapNameTextField.text = file?.name() ?: editorScreen.tileMap.mapParameters.name
        if (mapNameTextField.text.isBlank()) mapNameTextField.text = "My new map".tr()
        mapNameTextField.setSelection(Int.MAX_VALUE, Int.MAX_VALUE)  // sets caret to end of text
        stage.keyboardFocus = mapNameTextField
        saveButton.isEnabled = true
        deleteButton.isEnabled = (file != null)
        deleteButton.color = if (GameUiStyle.isAtlas) Color.WHITE else if (file != null) Color.SCARLET else Color.BROWN
    }

    private fun CoroutineScope.saverThread() {
        try {
            val mapToSave = editorScreen.getMapCloneForSave()
            if (!isActive) return
            mapToSave.assignContinents(TileMap.AssignContinentsMode.Reassign)
            if (!isActive) return
            MapSaver.saveMap(mapNameTextField.text, mapToSave)
            Concurrency.runOnGLThread {
                ToastPopup("Map saved successfully!", editorScreen)
            }
            editorScreen.isDirty = false
            setSaveButton(true)
        } catch (ex: Exception) {
            Log.error("Failed to save map", ex)
            Concurrency.runOnGLThread {
                val cantLoadGamePopup = Popup(editorScreen)
                cantLoadGamePopup.addGoodSizedLabel("It looks like your map can't be saved!").row()
                cantLoadGamePopup.addCloseButton()
                cantLoadGamePopup.open(force = true)
                setSaveButton(true)
            }
        }
    }
}
