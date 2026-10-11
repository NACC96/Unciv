package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.utils.Array
import com.unciv.logic.files.SetupPreset
import com.unciv.logic.files.SetupPresetStore
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup

class SetupPresetsPopup(private val screen: NewGameScreen) : Popup(screen, Scrollability.WithoutButtons) {
    private val store = SetupPresetStore(screen.game.files.getLocalFile("SetupPresets"))
    private val names = SelectBox<SetupPreset>(skin)
    private val name = UncivTextField("Preset name")
    private val status = "".toLabel(Color.LIGHT_GRAY, 14).apply { wrap = true }
    private val contentWidth = minOf(480f, stageToShowOn.width * 0.85f - 40f)

    init {
        addGoodSizedLabel("Setup presets", 22).row()
        addGoodSizedLabel("Save the nations, agent profiles, starting bonuses, map and game options. Saving an existing name updates that preset.", 14).row()
        add(names).width(contentWidth).padTop(12f).row()
        add(name).width(contentWidth).padTop(10f).row()
        add(status).width(contentWidth).padTop(8f).row()
        val load = addButton("Load") {
            val preset = names.selected ?: return@addButton
            try {
                preset.restoreMapFile()
                screen.loadPreset(preset)
                close()
            } catch (ex: Exception) {
                Gdx.input.inputProcessor = screen.stage
                status.setText(ex.message ?: "Could not load the preset.")
            }
        }
        fun refresh(selected: String? = null) {
            val (presets, errors) = store.list()
            names.setItems(Array(presets.toTypedArray()))
            presets.firstOrNull { it.id == selected }?.let { names.selected = it }
            load.actor.isEnabled = presets.isNotEmpty()
            names.isDisabled = presets.isEmpty()
            if (errors.isNotEmpty()) status.setText("${errors.size} unreadable preset files were left unchanged.")
        }
        names.onChange { names.selected?.let { name.text = it.name } }
        addButton("Save preset") {
            try {
                val preset = store.save(name.text, screen.gameSetupInfo, screen.playerPickerTable.agentProfiles, screen.playerPickerTable.profileCache)
                refresh(preset.id)
                status.setText("Saved ${preset.name}.")
            } catch (ex: Exception) { status.setText(ex.message ?: "Could not save the preset.") }
        }
        addCloseButton(additionalKey = KeyCharAndCode.ESC)
        refresh()
        open()
    }
}
