package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Array
import com.unciv.logic.files.SetupPreset
import com.unciv.logic.files.SetupPresetStore
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup

class SetupPresetsPopup(private val screen: NewGameScreen) : Popup(screen, Scrollability.WithoutButtons) {
    private val store = SetupPresetStore(screen.game.files.getLocalFile("SetupPresets"))
    private val names = SetupSelectBox<SetupPreset>().apply { polishSetupSelect() }
    private val name = UncivTextField("Preset name").apply { polishSetupField() }
    private val status = "".toLabel(GameUiStyle.muted, 14).apply { wrap = true }
    private val contentWidth = minOf(480f, stageToShowOn.width * 0.85f - 56f).coerceAtLeast(120f)

    init {
        innerTable.background = if (GameUiStyle.isAtlas) GameUiStyle.panelBackground("Atlas/Setup/Presets", 1f)
            else GameUiStyle.background("NewGameScreen/SetupPresetsPopup")
        addGoodSizedLabel("Setup presets", 22, color = GameUiStyle.ink).width(contentWidth).row()
        addGoodSizedLabel("Save the nations, agent profiles, starting bonuses, map and game options. Saving an existing name updates that preset.", 14, color = GameUiStyle.muted).width(contentWidth).row()
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
        }.also { GameUiStyle.apply(it.actor, primary = true) }
        fun refresh(selected: String? = null) {
            val (presets, errors) = store.list()
            names.setItems(Array(presets.toTypedArray()))
            presets.firstOrNull { it.id == selected }?.let { names.selected = it }
            GameUiStyle.setEnabled(load.actor, presets.isNotEmpty())
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
        }.also { GameUiStyle.apply(it.actor) }
        addCloseButton(additionalKey = KeyCharAndCode.ESC).also { GameUiStyle.apply(it.actor) }
        refresh()
        open()
    }
}
