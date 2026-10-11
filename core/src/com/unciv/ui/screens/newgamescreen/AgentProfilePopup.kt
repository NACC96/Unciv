package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.agents.AgentModelCatalog
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.ModelSpec
import com.unciv.logic.agents.ThinkingLevel
import com.unciv.models.metadata.Player
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onChange
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
    private val contentWidth: Float = min(stageToShowOn.width * 0.85f - 56f, 500f).coerceAtLeast(120f)

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
        innerTable.background = GameUiStyle.background("NewGameScreen/AgentProfilePopup")
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
        val titleLabel = title.toLabel(GameUiStyle.ink, 22)
        titleLabel.wrap = true
        titleLabel.setAlignment(Align.center)
        formTable.add(titleLabel).width(contentWidth).center().padBottom(10f).row()
        formTable.addSeparator(GameUiStyle.hover)

        // Error Banner
        val errorLabel = "".toLabel(GameUiStyle.error, 16)
        errorLabel.wrap = true
        formTable.add(errorLabel).width(contentWidth).padBottom(6f).row()

        // Display Name
        formTable.add("Display Name:".toLabel(GameUiStyle.ink, 16)).row()
        val nameField = UncivTextField("Display name", workingProfile.displayName).apply { polishSetupField() }
        formTable.add(nameField).width(contentWidth).row()

        // Provider Selector
        formTable.add("Provider:".toLabel(GameUiStyle.ink, 16)).padTop(6f).row()
        val providerBox = SetupSelectBox<String>().apply { polishSetupSelect() }
        providerBox.setItems(com.badlogic.gdx.utils.Array(AgentModelCatalog.SUPPORTED_PROVIDERS.toTypedArray()))
        providerBox.selected = workingProfile.provider
        formTable.add(providerBox).width(contentWidth).row()

        // Model Selector
        formTable.add("Model:".toLabel(GameUiStyle.ink, 16)).padTop(6f).row()
        val modelBox = SetupSelectBox<ModelChoice>().apply { polishSetupSelect() }
        formTable.add(modelBox).width(contentWidth).row()

        // Custom Model Field (hidden unless custom selected)
        val customModelLabel = "Custom Model ID:".toLabel(GameUiStyle.muted, 14)
        val customModelField = UncivTextField("e.g. gpt-6-luna", workingProfile.modelId).apply { polishSetupField() }
        val customModelRowLabel = formTable.add(customModelLabel).padTop(2f)
        customModelRowLabel.row()
        val customModelRowField = formTable.add(customModelField).width(contentWidth)
        customModelRowField.row()

        // Thinking Level Selector
        val thinkingHeaderLabel = "Thinking Level / Reasoning Effort:".toLabel(GameUiStyle.ink, 16).apply { wrap = true }
        formTable.add(thinkingHeaderLabel).width(contentWidth).padTop(6f).row()
        val thinkingBox = SetupSelectBox<ThinkingChoice>().apply { polishSetupSelect() }
        val thinkingRow = formTable.add(thinkingBox).width(contentWidth)
        thinkingRow.row()
        val thinkingUnsupportedLabel = "Thinking setting is not supported for this model.".toLabel(GameUiStyle.muted, 14).apply { wrap = true }
        val unsupportedRow = formTable.add(thinkingUnsupportedLabel).width(contentWidth)
        unsupportedRow.row()

        // Personality Instructions
        formTable.add("Personality Instructions:".toLabel(GameUiStyle.ink, 16)).padTop(6f).row()
        val personalityField = UncivTextField("Optional strategic personality instructions", workingProfile.personality).apply { polishSetupField() }
        formTable.add(personalityField).width(contentWidth).row()
        val bonuses = StartingBonusForm(player, table.previousScreen.ruleset, contentWidth)
        formTable.add(bonuses).width(contentWidth).padTop(6f).row()

        var updatingChoices = false

        fun showCustomModelFields(show: Boolean) {
            customModelLabel.isVisible = show
            customModelField.isVisible = show
            customModelRowLabel.height(if (show) customModelLabel.prefHeight else 0f).pad(if (show) 4f else 0f)
            customModelRowField.height(if (show) customModelField.prefHeight else 0f).pad(if (show) 4f else 0f)
            formTable.invalidateHierarchy()
        }

        // Replacing SelectBox items emits ChangeEvents. Ignore those while
        // rebuilding choices so the first item cannot overwrite saved reasoning.
        fun updateThinkingLevels(currentModelSpec: ModelSpec) {
            val wasUpdating = updatingChoices
            updatingChoices = true
            try {
                thinkingBox.isVisible = currentModelSpec.supportsThinking
                thinkingUnsupportedLabel.isVisible = !currentModelSpec.supportsThinking
                thinkingRow.height(if (currentModelSpec.supportsThinking) thinkingBox.prefHeight else 0f)
                    .pad(if (currentModelSpec.supportsThinking) 4f else 0f)
                unsupportedRow.height(if (currentModelSpec.supportsThinking) 0f else thinkingUnsupportedLabel.prefHeight)
                    .pad(if (currentModelSpec.supportsThinking) 0f else 4f)
                if (!currentModelSpec.supportsThinking) {
                    workingProfile.reasoning = null
                } else {
                    val currentLevel = ThinkingLevel.fromWireValue(workingProfile.reasoning)
                    val choices = currentModelSpec.supportedThinkingLevels.map { ThinkingChoice(it) }
                    val matching = choices.firstOrNull { it.level == currentLevel }
                        ?: choices.firstOrNull { it.level == currentModelSpec.defaultThinkingLevel }
                        ?: choices.first()
                    thinkingBox.setItems(com.badlogic.gdx.utils.Array(choices.toTypedArray()))
                    thinkingBox.selected = matching
                    workingProfile.reasoning = matching.level.wireValue
                }
                formTable.invalidateHierarchy()
            } finally {
                updatingChoices = wasUpdating
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

            // Select active model
            val selectedChoice = choices.firstOrNull { !it.isCustomChoice && it.spec.id == workingProfile.modelId }
                ?: choices.firstOrNull { !it.isCustomChoice }
                ?: choices.last()

            val wasUpdating = updatingChoices
            updatingChoices = true
            try {
                modelBox.setItems(com.badlogic.gdx.utils.Array(choices.toTypedArray()))
                modelBox.selected = selectedChoice
            } finally {
                updatingChoices = wasUpdating
            }
            val isCustom = selectedChoice.isCustomChoice
            showCustomModelFields(isCustom)
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
        providerBox.onChange {
            if (updatingChoices) return@onChange
            val newProvider = providerBox.selected
            if (newProvider != workingProfile.provider) {
                workingProfile.provider = newProvider
                val defaultSpec = AgentModelCatalog.getDefaultModel(newProvider)
                workingProfile.modelId = defaultSpec.id
                workingProfile.reasoning = defaultSpec.defaultThinkingLevel.wireValue
                updateModelChoices(newProvider)
            }
        }

        modelBox.onChange {
            if (updatingChoices) return@onChange
            val selected = modelBox.selected ?: return@onChange
            val isCustom = selected.isCustomChoice
            showCustomModelFields(isCustom)
            if (isCustom) {
                workingProfile.modelId = customModelField.text.trim()
                val spec = AgentModelCatalog.customModel(workingProfile.modelId, workingProfile.provider)
                updateThinkingLevels(spec)
            } else {
                workingProfile.modelId = selected.spec.id
                updateThinkingLevels(selected.spec)
            }
        }

        customModelField.onChange {
            if (modelBox.selected?.isCustomChoice == true) {
                workingProfile.modelId = customModelField.text.trim()
            }
        }

        thinkingBox.onChange {
            if (updatingChoices) return@onChange
            val selected = thinkingBox.selected
            if (selected != null) {
                workingProfile.reasoning = selected.level.wireValue
            }
        }

        // Initial setup
        updateModelChoices(workingProfile.provider)

        // Popup.add routes content into the scroll area, above the fixed buttons.
        add(formTable).width(contentWidth).row()

        // Buttons
        addButton("Save", KeyCharAndCode.RETURN) {
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

            val startingBonuses = try { bonuses.read() } catch (ex: IllegalArgumentException) {
                errorLabel.setText(ex.message)
                return@addButton
            }

            // Commit to authoritative profile
            profile.displayName = name
            profile.provider = provider
            profile.modelId = model
            profile.reasoning = thinkingLevel.wireValue
            profile.personality = personality
            profile.profileRevision++
            player.startingBonuses = startingBonuses

            table.update()
            onSave()
            close()
        }.also { GameUiStyle.apply(it.actor, primary = true) }

        addCloseButton("Cancel").also { GameUiStyle.apply(it.actor) }
        equalizeLastTwoButtonWidths()
        open()
    }
}
