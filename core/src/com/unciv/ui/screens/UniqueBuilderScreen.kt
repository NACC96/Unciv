package com.unciv.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.validation.UniqueValidator
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.getPlaceholderParameters
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen

/** Composes ruleset unique strings without applying them to the running game. */
class UniqueBuilderScreen(ruleset: Ruleset) : PickerScreen() {

    private val mainUniqueTable = UniqueTable(true, ruleset, stage) { updateCurrentUniqueText() }
    private val modifierTables = mutableListOf<UniqueTable>()
    private var currentUniqueText = ""

    init {
        setDefaultCloseAction()
        rightSideButton.isVisible = false
        rightSideButton.setText("Copy to clipboard")
        rightSideButton.onClick { Gdx.app.clipboard.contents = currentUniqueText }
        topTable.defaults().pad(5f)
        if (GameUiStyle.isAtlas) {
            topTable.add(AtlasWidgets.header("Unique builder")).growX().row()
            topTable.defaults().growX().minWidth(0f).width((stage.width - 48f).coerceAtMost(980f))
        }

        mainUniqueTable.initialize()
        topTable.add(mainUniqueTable).row()

        val modifierTableHolder = Table().apply { defaults().pad(5f) }
        topTable.add(modifierTableHolder).row()

        val addModifierButton = "Add Modifier".toTextButton()
        addModifierButton.onClick {
            val modifierTable = UniqueTable(false, ruleset, stage){ updateCurrentUniqueText() }
            modifierTables.add(modifierTable)
            val wrapper = Table()
            wrapper.add(modifierTable).growX().minWidth(0f).row()
            val remove = GameUiStyle.button("Remove").apply {
                onClick {
                    modifierTables.remove(modifierTable)
                    wrapper.remove()
                    modifierTableHolder.invalidateHierarchy()
                    updateCurrentUniqueText()
                }
            }
            wrapper.add(remove).right().padBottom(8f).row()
            modifierTableHolder.add(wrapper).growX().minWidth(0f).row()
            modifierTable.initialize()
        }
        topTable.add(addModifierButton)
    }

    private fun updateCurrentUniqueText() {
        currentUniqueText = mainUniqueTable.uniqueTextField.text +
                modifierTables.joinToString("") { " <"+it.uniqueTextField.text+">" }
        descriptionLabel.setText(currentUniqueText)
        rightSideButton.isVisible = true
        rightSideButton.enable()
    }
}

class UniqueTable(
    isMainUnique: Boolean,
    val ruleset: Ruleset,
    stage: Stage,
    val onUniqueChange: () -> Unit
) :Table() {
    // in order of presentation
    private val uniqueTargetsSelectBox: SelectBox<UniqueTarget>
    private val uniqueSelectBox = SelectBox<UniqueType>(BaseScreen.skin)
    private val uniqueSearchTextField = TextField("", BaseScreen.skin).apply { messageText = "Search" }
    internal val uniqueTextField = TextField("Unique", BaseScreen.skin)
    private val documentationLabel = WrappableLabel("", stage.width * 0.9f)
    private val documentationCell: Cell<Actor?>
    private val parameterSelectBoxTable = Table().apply { defaults().pad(5f) }
    private val uniqueErrorTable = Table().apply { defaults().pad(5f) }
    private val contentWidth = if (GameUiStyle.isAtlas) (stage.width - 88f).coerceAtMost(920f) else stage.width * .9f

    init {
        this.stage = stage // required for width
        // Main unique should be non-modifier
        val uniqueTargets = if (isMainUnique) UniqueTarget.entries
            .filter { it.modifierType == UniqueTarget.ModifierType.None }
        // Additional ones should be modifiers
        else UniqueTarget.entries
            .filter { it.modifierType != UniqueTarget.ModifierType.None }

        defaults().pad(10f)
        background = ImageGetter.getWhiteDotDrawable().tint(ImageGetter.CHARCOAL.cpy().apply { a=0.3f })
        if (GameUiStyle.isAtlas) {
            background = GameUiStyle.panelBackground("UniqueBuilder/Unique", 1f)
            pad(8f)
            defaults().growX().minWidth(0f).width(contentWidth).pad(5f)
        }
        val uniqueTargetSelectBoxTable = Table().apply { defaults().pad(5f) }
        uniqueTargetsSelectBox = SelectBox<UniqueTarget>(BaseScreen.skin)
        uniqueTargetsSelectBox.setItems(uniqueTargets)
        uniqueTargetsSelectBox.selected = UniqueTarget.Global
        if (GameUiStyle.isAtlas) {
            uniqueTargetSelectBoxTable.defaults().growX().minWidth(0f).width(contentWidth - 10f)
            uniqueTargetSelectBoxTable.add(AtlasWidgets.field("Type", uniqueTargetsSelectBox)).row()
            uniqueTargetSelectBoxTable.add(AtlasWidgets.field("Search", uniqueSearchTextField)).row()
            uniqueTargetSelectBoxTable.add(uniqueSelectBox).minHeight(GameUiStyle.standardControlHeight).row()
        } else {
            uniqueTargetSelectBoxTable.add(uniqueTargetsSelectBox)
            uniqueTargetSelectBoxTable.add(uniqueSelectBox).width(stage.width * 0.5f).row()
            uniqueTargetSelectBoxTable.add()
            uniqueTargetSelectBoxTable.add(uniqueSearchTextField).center().row()
        }
        add(uniqueTargetSelectBoxTable).row()

        uniqueTargetsSelectBox.onChange {
            onUniqueTargetChange()
        }
        if (!isMainUnique) onUniqueTargetChange()
        uniqueSelectBox.onChange {
            onUniqueSelected()
        }
        // Unique search
        uniqueSearchTextField.onChange {
            setUniqueSelectBoxItems()
        }

        row()
        uniqueTextField.onChange {
            updateUnique()
        }
        add(uniqueTextField).width(contentWidth).minHeight(GameUiStyle.standardControlHeight).row()

        documentationCell = add().width(contentWidth).pad(0f)
        row()
        add(parameterSelectBoxTable).row()

        uniqueErrorTable.defaults().pad(5f)
        uniqueErrorTable.background = if (GameUiStyle.isAtlas) GameUiStyle.background("UniqueBuilder/Validation", GameUiStyle.inset)
            else ImageGetter.getWhiteDotDrawable().tint(Color.DARK_GRAY)
        add(uniqueErrorTable).row()
    }

    private fun setUniqueSelectBoxItems() {
        val oldType = uniqueSelectBox.selected
        uniqueSelectBox.selection.clear()
        val target = uniqueTargetsSelectBox.selected
        val uniquesForTarget = UniqueType.entries.filter { it.canAcceptUniqueTarget(target) }
        val uniquesFiltered = uniquesForTarget.filter { it.getDeprecationAnnotation() == null }
            .filter { it.text.contains(uniqueSearchTextField.text, ignoreCase = true) }
        uniqueSelectBox.setItems(uniquesFiltered)
        if (oldType in uniquesFiltered)
            uniqueSelectBox.selected = oldType
    }

    private fun onUniqueTargetChange() = setUniqueSelectBoxItems()

    private fun onUniqueSelected() {
        val uniqueType = uniqueSelectBox.selected ?: return

        uniqueTextField.text = uniqueType.text
        updateUnique()

        if (uniqueType.docDescription.isNullOrEmpty()) {
            documentationCell.setActor(null).pad(0f)
        } else {
            documentationLabel.setText(uniqueType.docDescription)
            if (!GameUiStyle.isAtlas) documentationLabel.optimizePrefWidth()
            documentationCell.setActor(documentationLabel).pad(10f)
        }

        parameterSelectBoxTable.clear()
        for ((index, parameter) in uniqueType.text.getPlaceholderParameters().withIndex()) {
            val paramTable = Table().apply { defaults().pad(10f) }
            paramTable.background = if (GameUiStyle.isAtlas) GameUiStyle.background("UniqueBuilder/Parameter", GameUiStyle.inset)
                else ImageGetter.getWhiteDotDrawable().tint(Color.DARK_GRAY)
            if (GameUiStyle.isAtlas) paramTable.defaults().growX().minWidth(0f).width(contentWidth - 36f).pad(6f)
            paramTable.add("Parameter ${index+1}: $parameter".toLabel().apply { wrap = true }).row()
            val knownParamValues = uniqueType.parameterTypeMap[index]
                .flatMap { it.getKnownValuesForAutocomplete(ruleset) }.toSet()

            if (knownParamValues.isNotEmpty()) {
                val paramSelectBox = SelectBox<String>(BaseScreen.skin)
                paramSelectBox.setItems(knownParamValues)
                paramSelectBox.onChange {
                    val currentParams = uniqueTextField.text.getPlaceholderParameters().toMutableList()
                    currentParams[index] = paramSelectBox.selected ?: return@onChange
                    val newText = uniqueType.text.fillPlaceholders(*currentParams.toTypedArray())
                    uniqueTextField.text = newText
                    updateUnique()
                }
                paramTable.add(paramSelectBox).row()

                val paramSearchTable = TextField("", BaseScreen.skin).apply { messageText = "Search" }
                paramSearchTable.onChange {
                    val filteredValues = knownParamValues.filter { it.contains(paramSearchTable.text, ignoreCase = true) }
                    paramSelectBox.setItems(filteredValues)
                }
                paramTable.add(paramSearchTable).row()
            } else paramTable.add("No known values".toLabel())

            parameterSelectBoxTable.add(paramTable).fillY()
            if (GameUiStyle.isAtlas) parameterSelectBoxTable.row()
        }
    }

    private fun updateUnique() {
        uniqueErrorTable.clear()
        uniqueErrorTable.add("Errors:".toLabel()).row()

        val uniqueErrors = UniqueValidator(ruleset, tryFixUnknownUniques = true)
            .checkUnique(Unique(uniqueTextField.text), null)
        for (error in uniqueErrors)
            uniqueErrorTable.add(error.text.toLabel(if (GameUiStyle.isAtlas) GameUiStyle.error else Color.WHITE).apply { wrap = true })
                .width(if (GameUiStyle.isAtlas) contentWidth - 24f else stage.width / 2).row()
        if (uniqueErrors.isEmpty())
            uniqueErrorTable.add("No errors!".toLabel())

        onUniqueChange()
    }

    /** Needs to come AFTER the UniqueTable is registered in the UniqueBuilderScreen,
     * because it needs to update the final unique text */
    fun initialize() {
        onUniqueTargetChange()
    }
}
