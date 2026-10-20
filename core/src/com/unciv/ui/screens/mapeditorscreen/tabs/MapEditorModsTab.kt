package com.unciv.ui.screens.mapeditorscreen.tabs

import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.logic.map.tile.TileNormalizer
import com.unciv.ui.screens.newgamescreen.ModCheckboxTable

class MapEditorModsTab(
    private val editorScreen: MapEditorScreen
): Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val mods = editorScreen.newMapParameters.mods
    private var modsTable: ModCheckboxTable
    private val modsTableCell: Cell<ModCheckboxTable>
    private val applyButton = "Change map ruleset".toTextButton()
    private val revertButton = "Revert to map ruleset".toTextButton()
    private val baseRulesetSelectBox: TranslatedSelectBox

    init {
        val rulesetName = editorScreen.newMapParameters.baseRuleset
        // Out dirty flag `modsTabNeedsRefresh` will be true on first activation,
        // so this will be replaced and can now be minimal
        modsTable = ModCheckboxTable(linkedSetOf(), rulesetName, editorScreen, false) {}

        val baseRulesets = RulesetCache.getSortedBaseRulesets()
        baseRulesetSelectBox = TranslatedSelectBox(baseRulesets, rulesetName)
        baseRulesetSelectBox.onChange {
            val newBaseRuleset = baseRulesetSelectBox.selected.value
            editorScreen.newMapParameters.baseRuleset = newBaseRuleset
            modsTable.setBaseRuleset(newBaseRuleset)
            modsTable.disableAllCheckboxes()
            enableApplyButton()
        }

        top()
        pad(5f)

        if (GameUiStyle.isAtlas) defaults().growX().minWidth(0f).width(editorScreen.getToolsWidth() - 24f)
        add(if (GameUiStyle.isAtlas) AtlasWidgets.field("Base Ruleset", baseRulesetSelectBox) else Table().apply {
            add("{Base Ruleset}:".toLabel())
            add(baseRulesetSelectBox).fillX()
        }).fillX().padBottom(10f).row()

        add(Table().apply {
            add(applyButton).growX().minWidth(0f).padRight(if (GameUiStyle.isAtlas) 0f else 10f)
            if (GameUiStyle.isAtlas) row()
            add(revertButton).growX().minWidth(0f).padTop(if (GameUiStyle.isAtlas) 8f else 0f)
        }).fillX().pad(10f).row()

        modsTableCell = add(modsTable)
        row()

        applyButton.onClick(this::applyControls)
        applyButton.addTooltip("Change the map to use the ruleset selected on this page", 21f, targetAlign = Align.bottom)

        revertButton.onClick(this::revertControls)
        revertButton.addTooltip("Reset the controls to reflect the current map ruleset", 21f, targetAlign = Align.bottom)
        if (GameUiStyle.isAtlas) {
            GameUiStyle.apply(applyButton, primary = true)
            applyButton.label.wrap = true
            revertButton.label.wrap = true
        }
    }

    private fun enableApplyButton() {
        val currentParameters = editorScreen.tileMap.mapParameters
        val enabled =
            currentParameters.mods != mods ||
            currentParameters.baseRuleset != baseRulesetSelectBox.selected.value
        applyButton.isEnabled = enabled
        revertButton.isEnabled = enabled
    }

    private fun revertControls() {
        val currentParameters = editorScreen.tileMap.mapParameters
        baseRulesetSelectBox.setSelected(currentParameters.baseRuleset)
        mods.clear()
        mods.addAll(currentParameters.mods)  // clone current "into" editorScreen.newMapParameters.mods
        modsTable = ModCheckboxTable(mods, currentParameters.baseRuleset, editorScreen, false) {
            enableApplyButton()
        }
        modsTableCell.setActor(modsTable)
        enableApplyButton()
    }

    private fun applyControls() {
        val newRuleset = RulesetCache.getComplexRuleset(mods, editorScreen.newMapParameters.baseRuleset)
        val incompatibilities = getIncompatibilities(newRuleset)
        if (incompatibilities.isEmpty()) {
            editorScreen.applyRuleset(newRuleset, editorScreen.newMapParameters.baseRuleset, mods)
            enableApplyButton()
        } else {
            AskFitMapToRulesetPopup(editorScreen, incompatibilities) {
                fitMapToRuleset(newRuleset)
                editorScreen.applyRuleset(newRuleset, editorScreen.newMapParameters.baseRuleset, mods)
                enableApplyButton()
            }
        }
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        enableApplyButton()
        if (!editorScreen.modsTabNeedsRefresh) return
        editorScreen.modsTabNeedsRefresh = false
        revertControls()
    }

    private fun getIncompatibilities(newRuleset: Ruleset): List<String> {
        val incompatibilities = HashSet<String>()
        for (tile in editorScreen.tileMap.values) {
            incompatibilities += tile.getRulesetIncompatibility(newRuleset)
        }
        incompatibilities.remove("")
        return incompatibilities.sorted()
    }

    private class AskFitMapToRulesetPopup(
        editorScreen: BaseScreen,
        incompatibilities: List<String>,
        onOK: () -> Unit
    ): Popup(editorScreen) {
        init {
            val incompatibilityTable = Table().apply {
                for (inc in incompatibilities)
                    add(inc.toLabel().apply { wrap = true }).width((stageToShowOn.width * .8f - 32f).coerceAtMost(620f)).row()
            }
            add(ScrollPane(incompatibilityTable)).colspan(2)
                .maxHeight(stageToShowOn.height * 0.5f).row()
            addGoodSizedLabel("Change map to fit selected ruleset?", 24).colspan(2).row()
            addButton(Constants.yes, 'y') {
                onOK()
                close()
            }
            addButton(Constants.no, 'n') { close() }
            equalizeLastTwoButtonWidths()
            open(true)
        }
    }

    private fun fitMapToRuleset(newRuleset: Ruleset) {
        for (tile in editorScreen.tileMap.values)
            TileNormalizer.normalizeToRuleset(tile, newRuleset)
    }
}
