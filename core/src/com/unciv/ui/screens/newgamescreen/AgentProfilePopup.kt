package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.ModelSpec
import com.unciv.logic.agents.ThinkingLevel
import com.unciv.models.metadata.Player
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import kotlin.math.min

/**
 * Robust, scrollable agent profile editor (AI_AGENT_PLAN.md §4.1, §11.1).
 * Supports provider-scoped model pickers, thinking-level selectors with capabilities,
 * sensible defaults, validation, and cancel isolation.
 */
class AgentProfilePopup(
    private val table: PlayerPickerTable,
    private val player: Player,
    private val profile: AgentProfile,
    private val onSave: () -> Unit = {}
) : Popup(table.previousScreen.stage, Scrollability.WithoutButtons, 0.85f) {

    private val workingProfile: AgentProfile = profile.copy()
    private val contentWidth: Float = min(stageToShowOn.width * 0.8f, 500f)

    private class ModelChoice(
        val spec: ModelSpec,
        val isCustomChoice: Boolean = false
    ) {
        override fun toString(): String {
            if (isCustomChoice) return "Custom model…"
            val suffix = if (spec.isDiscovered) " (Verified)" else " (Catalog)"
            return "${spec.label}$suffix"
        }
    }

    private class ThinkingChoice(
        val level: ThinkingLevel
    ) {
        override fun toString(): String = level.label
    }

    init {
        // Initialize working profile with Luna defaults if blank
        if (workingProfile.provider.isBlank()) {
            workingProfile.provider = AgentModelCatalog.PROVIDER_CODEX
        }
        if (workingProfile.modelId.isBlank()) {
            val defaultSpec = AgentModelCatalog.getDefaultModel(workingProfile.provider)
            workingProfile.modelId = defaultSpec.id
            workingProfile.reasoning = defaultSpec.defaultThinkingLevel.wireValue
        }
        if (workingProfile.displayName.isBlank()) {
            val spec = AgentModelCatalog.findModel(workingProfile.provider, workingProfile.modelId)
            workingProfile.displayName = if (player.chosenCiv.isNotBlank()) "${spec.label} ${player.chosenCiv}" else spec.label
        }

        val formTable = Table(skin)
        formTable.defaults().pad(4f).align(Align.left)

        // Title
        val title = if (profile.displayName.isBlank()) "Configure Agent for ${player.chosenCiv}"
        else "Edit Agent: ${profile.displayName} (${player.chosenCiv})"
        val titleLabel = title.toLabel(fontSize = 22)
        titleLabel.setAlignment(Align.center)
        formTable.add(titleLabel).width(contentWidth).center().padBottom(10f).row()
        formTable.addSeparator(Color.DARK_GRAY)

        // Error Banner
        val errorLabel = "".toLabel(Color.RED, 16)
        errorLabel.wrap = true
        formTable.add(errorLabel).width(contentWidth).padBottom(6f).row()

        // Display Name
        formTable.add("Display Name:".toLabel(Color.WHITE, 16)).row()
        val nameField = UncivTextField("Display name", workingProfile.displayName)
        formTable.add(nameField).width(contentWidth).row()

        // Provider Selector
        formTable.add("Provider:".toLabel(Color.WHITE, 16)).padTop(6f).row()
        val providerBox = SelectBox<String>(skin)
        providerBox.setItems(com.badlogic.gdx.utils.Array(AgentModelCatalog.SUPPORTED_PROVIDERS.toTypedArray()))
        providerBox.selected = workingProfile.provider
        formTable.add(providerBox).width(contentWidth).row()

        // Model Selector
        formTable.add("Model:".toLabel(Color.WHITE, 16)).padTop(6f).row()
        val modelBox = SelectBox<ModelChoice>(skin)
        formTable.add(modelBox).width(contentWidth).row()

        // Custom Model Field (hidden unless custom selected)
        val customModelLabel = "Custom Model ID:".toLabel(Color.LIGHT_GRAY, 14)
        val customModelField = UncivTextField("e.g. gpt-5.6-luna", workingProfile.modelId)
        val customModelRowLabel = formTable.add(customModelLabel).padTop(2f)
        customModelRowLabel.row()
        val customModelRowField = formTable.add(customModelField).width(contentWidth)
        customModelRowField.row()

        // Thinking Level Selector
        val thinkingHeaderLabel = "Thinking Level / Reasoning Effort:".toLabel(Color.WHITE, 16)
        formTable.add(thinkingHeaderLabel).padTop(6f).row()
        val thinkingBox = SelectBox<ThinkingChoice>(skin)
        val thinkingRow = formTable.add(thinkingBox).width(contentWidth)
        thinkingRow.row()
        val thinkingUnsupportedLabel = "Thinking setting is not supported for this model.".toLabel(Color.GRAY, 14)
        val unsupportedRow = formTable.add(thinkingUnsupportedLabel).width(contentWidth)
        unsupportedRow.row()

        // Personality Instructions
        formTable.add("Personality Instructions:".toLabel(Color.WHITE, 16)).padTop(6f).row()
        val personalityField = UncivTextField("Optional strategic personality instructions", workingProfile.personality)
        formTable.add(personalityField).width(contentWidth).row()

        // Function to populate and sync thinking levels
        fun updateThinkingLevels(currentModelSpec: ModelSpec) {
            if (!currentModelSpec.supportsThinking) {
                thinkingBox.isVisible = false
                thinkingUnsupportedLabel.isVisible = true
                workingProfile.reasoning = null
            } else {
                thinkingBox.isVisible = true
                thinkingUnsupportedLabel.isVisible = false
                val choices = currentModelSpec.supportedThinkingLevels.map { ThinkingChoice(it) }
                thinkingBox.setItems(com.badlogic.gdx.utils.Array(choices.toTypedArray()))
                val currentLevel = ThinkingLevel.fromWireValue(workingProfile.reasoning)
                val matching = choices.firstOrNull { it.level == currentLevel }
                    ?: choices.firstOrNull { it.level == currentModelSpec.defaultThinkingLevel }
                    ?: choices.first()
                thinkingBox.selected = matching
                workingProfile.reasoning = matching.level.wireValue
            }
        }

        // Function to populate model choices for current provider
        fun updateModelChoices(provider: String) {
            val catalogModels = AgentModelCatalog.getModelsForProvider(provider)
            val choices = ArrayList<ModelChoice>()
            for (m in catalogModels) {
                choices.add(ModelChoice(m))
            }
            // If current model is not in catalog, include it as a preserved saved model
            val inCatalog = catalogModels.any { it.id == workingProfile.modelId }
            if (workingProfile.modelId.isNotBlank() && !inCatalog) {
                val preservedSpec = AgentModelCatalog.customModel(workingProfile.modelId, provider)
                choices.add(0, ModelChoice(preservedSpec))
            }
            // Add custom choice escape hatch
            choices.add(ModelChoice(AgentModelCatalog.customModel("", provider), isCustomChoice = true))

            modelBox.setItems(com.badlogic.gdx.utils.Array(choices.toTypedArray()))

            // Select active model
            val selectedChoice = choices.firstOrNull { !it.isCustomChoice && it.spec.id == workingProfile.modelId }
                ?: choices.firstOrNull { !it.isCustomChoice }
                ?: choices.last()

            modelBox.selected = selectedChoice
            val isCustom = selectedChoice.isCustomChoice
            customModelLabel.isVisible = isCustom
            customModelField.isVisible = isCustom
            if (isCustom) {
                customModelField.text = workingProfile.modelId
            }

            val specToUse = if (isCustom) {
                AgentModelCatalog.customModel(customModelField.text.trim(), provider)
            } else {
                selectedChoice.spec
            }
            updateThinkingLevels(specToUse)
        }

        // Listeners
        providerBox.addListener {
            val newProvider = providerBox.selected
            if (newProvider != workingProfile.provider) {
                workingProfile.provider = newProvider
                val defaultSpec = AgentModelCatalog.getDefaultModel(newProvider)
                workingProfile.modelId = defaultSpec.id
                workingProfile.reasoning = defaultSpec.defaultThinkingLevel.wireValue
                updateModelChoices(newProvider)
            }
            false
        }

        modelBox.addListener {
            val selected = modelBox.selected ?: return@addListener false
            val isCustom = selected.isCustomChoice
            customModelLabel.isVisible = isCustom
            customModelField.isVisible = isCustom
            if (isCustom) {
                workingProfile.modelId = customModelField.text.trim()
                val spec = AgentModelCatalog.customModel(workingProfile.modelId, workingProfile.provider)
                updateThinkingLevels(spec)
            } else {
                workingProfile.modelId = selected.spec.id
                updateThinkingLevels(selected.spec)
            }
            false
        }

        customModelField.addListener {
            if (modelBox.selected?.isCustomChoice == true) {
                workingProfile.modelId = customModelField.text.trim()
            }
            false
        }

        thinkingBox.addListener {
            val selected = thinkingBox.selected
            if (selected != null) {
                workingProfile.reasoning = selected.level.wireValue
            }
            false
        }

        // Initial setup
        updateModelChoices(workingProfile.provider)

        // Add formTable to innerTable of popup
        innerTable.add(formTable).width(contentWidth).row()

        // Buttons
        addButton("Save") {
            val name = nameField.text.trim()
            val provider = providerBox.selected
            val model = if (modelBox.selected?.isCustomChoice == true) {
                customModelField.text.trim()
            } else {
                modelBox.selected?.spec?.id?.trim() ?: ""
            }
            val thinkingLevel = if (thinkingBox.isVisible && thinkingBox.selected != null) {
                thinkingBox.selected.level
            } else {
                ThinkingLevel.PROVIDER_DEFAULT
            }
            val personality = personalityField.text.trim()

            // Validate
            val validationErrors = ArrayList<String>()
            if (name.isBlank()) {
                validationErrors.add("Display name cannot be blank.")
            }
            if (model.isBlank()) {
                validationErrors.add("Model ID cannot be blank.")
            }
            val thinkingErr = AgentModelCatalog.validateThinkingLevel(provider, model, thinkingLevel)
            if (thinkingErr != null) {
                validationErrors.add(thinkingErr)
            }

            if (validationErrors.isNotEmpty()) {
                errorLabel.setText(validationErrors.joinToString("\n"))
                return@addButton
            }

            // Commit to authoritative profile
            profile.displayName = name
            profile.provider = provider
            profile.modelId = model
            profile.reasoning = thinkingLevel.wireValue
            profile.personality = personality
            profile.profileRevision++

            table.update()
            onSave()
            close()
        }

        addCloseButton("Cancel")
        open()
    }
}
