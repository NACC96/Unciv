package com.unciv.ui.screens.newgamescreen

import com.unciv.logic.agents.AgentProfile
import com.unciv.models.metadata.Player
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup

/**
 * Minimal per-leader agent editor (AI_AGENT_PLAN.md §11.1): display name,
 * provider, model, optional reasoning setting, personality. Writes into the
 * table's live profile on Save; validation (provider/model required) runs at
 * match creation via [PlayerPickerTable.collectAgentErrors].
 */
class AgentProfilePopup(
    private val table: PlayerPickerTable,
    private val player: Player,
    private val profile: AgentProfile,
    private val onSave: () -> Unit = {}
) : Popup(table.previousScreen.stage) {

    init {
        val title = if (profile.displayName.isBlank()) "Agent for ${player.chosenCiv}"
        else "Agent ${profile.displayName} (${player.chosenCiv})"
        addGoodSizedLabel(title)

        val nameField = UncivTextField("Display name", profile.displayName)
        addGoodSizedLabel("Display name (separate from the civilization's leader):")
        add(nameField).row()

        addGoodSizedLabel("Provider:")
        val providerBox = TranslatedSelectBox(
            listOf("codex", "claude", "opencode"),
            profile.provider.ifBlank { "codex" }
        )
        add(providerBox).row()

        val modelField = UncivTextField("Model ID", profile.modelId)
        addGoodSizedLabel("Model ID (required before first execution):")
        add(modelField).row()

        val reasoningField = UncivTextField("Reasoning (optional)", profile.reasoning ?: "")
        addGoodSizedLabel("Reasoning setting (provider-supported values only, optional):")
        add(reasoningField).row()

        val personalityField = UncivTextField("Personality instructions", profile.personality)
        addGoodSizedLabel("Personality instructions:")
        add(personalityField).row()

        addButton("Save") {
            profile.displayName = nameField.text.trim()
            profile.provider = providerBox.selected.value
            profile.modelId = modelField.text.trim()
            profile.reasoning = reasoningField.text.trim().ifBlank { null }
            profile.personality = personalityField.text.trim()
            profile.profileRevision++
            table.update()
            onSave()
            close()
        }
        addCloseButton("Cancel")
        open()
    }
}
