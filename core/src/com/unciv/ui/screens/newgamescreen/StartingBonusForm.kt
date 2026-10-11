package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Array
import com.unciv.models.metadata.Player
import com.unciv.models.metadata.StartingBonuses
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.screens.basescreen.BaseScreen

/** Working fields only; Save commits one validated snapshot and Cancel leaves the player alone. */
class StartingBonusForm(private val player: Player, private val ruleset: Ruleset, private val contentWidth: Float) : Table(BaseScreen.skin) {
    private val gold = number("starting-gold", player.startingBonuses.gold)
    private val culture = number("starting-culture", player.startingBonuses.culture)
    private val unitFields = LinkedHashMap<String, UncivTextField>()
    private val depositFields = LinkedHashMap<String, UncivTextField>()
    private val unitRows = Table()
    private val depositRows = Table()

    init {
        defaults().left().padBottom(6f)
        add("Starting bonuses".toLabel(GameUiStyle.ink, 18)).padTop(12f).row()
        add("Extra gold".toLabel(GameUiStyle.ink, 14)).row()
        add(gold).width(contentWidth).row()
        add("Extra culture".toLabel(GameUiStyle.ink, 14)).row()
        add(culture).width(contentWidth).row()
        add("Extra units".toLabel(GameUiStyle.ink, 14)).padTop(6f).row()
        add("Aircraft and missiles are delivered after a city is founded. Any beyond city capacity stay pending and retry each turn or when another city is founded.".toLabel(GameUiStyle.muted, 12).apply { wrap = true })
            .width(contentWidth).row()
        for ((name, count) in player.startingBonuses.units) unitFields[name] = number("unit-$name", count)
        val units = ruleset.units.values.filter { it.uniqueTo == null || it.uniqueTo == player.chosenCiv }.map { it.name }.sorted()
        addPicker(units, "Add unit", unitFields, unitRows, "unit")
        add(unitRows).width(contentWidth).row()
        add("Extra strategic-resource deposits".toLabel(GameUiStyle.ink, 14).apply { wrap = true }).width(contentWidth).padTop(6f).row()
        add("Counts add deposit tiles within four tiles of the start. Natural locations are preferred; other workable tiles are used when needed. Resources still need ownership, technology and improvements. Set a count to 0 to remove it.".toLabel(GameUiStyle.muted, 12).apply { wrap = true })
            .width(contentWidth).row()
        for ((name, count) in player.startingBonuses.deposits) depositFields[name] = number("deposit-$name", count)
        val resources = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Strategic }.map { it.name }.sorted()
        addPicker(resources, "Add deposit", depositFields, depositRows, "deposit")
        add(depositRows).width(contentWidth).row()
        renderRows(unitFields, unitRows)
        renderRows(depositFields, depositRows)
    }

    private fun number(fieldName: String, value: Int) = UncivTextField("0", value.toString()).apply {
        name = fieldName
        textFieldFilter = TextField.TextFieldFilter.DigitsOnlyFilter()
        polishSetupField()
    }

    private fun addPicker(choices: List<String>, caption: String, fields: MutableMap<String, UncivTextField>, rows: Table, prefix: String) {
        if (choices.isEmpty()) return
        val picker = SetupSelectBox<String>().apply {
            setItems(Array(choices.toTypedArray()))
            polishSetupSelect()
        }
        val button = GameUiStyle.button(caption).apply {
            onClick {
                val name = picker.selected
                if (name !in fields) fields[name] = number("$prefix-$name", 1)
                renderRows(fields, rows)
                stage?.keyboardFocus = fields[name]
            }
        }
        val controls = Table().apply { name = "SetupBonusPicker-$prefix" }
        if (contentWidth < button.prefWidth + 160f) {
            controls.add(picker).width(contentWidth).row()
            controls.add(button).right().padTop(6f).row()
        } else {
            controls.add(picker).width(contentWidth - button.prefWidth - 8f)
            controls.add(button).padLeft(8f)
        }
        add(controls).width(contentWidth).row()
    }

    private fun renderRows(fields: Map<String, UncivTextField>, rows: Table) {
        rows.clearChildren()
        for ((name, field) in fields) {
            val numberWidth = minOf(80f, contentWidth * 0.3f)
            rows.add(name.toLabel(GameUiStyle.ink, 14).apply { wrap = true })
                .width(contentWidth - numberWidth - 8f).left().padRight(8f)
            rows.add(field).width(numberWidth).padBottom(6f).row()
        }
        invalidateHierarchy()
    }

    fun read(): StartingBonuses {
        fun count(field: UncivTextField): Int {
            val value = if (field.text.isBlank()) 0 else field.text.toIntOrNull()
                ?: throw IllegalArgumentException("Starting bonuses must be whole numbers.")
            require(value >= 0) { "Starting bonuses must be nonnegative." }
            return value
        }
        val result = StartingBonuses().apply {
            gold = count(this@StartingBonusForm.gold)
            culture = count(this@StartingBonusForm.culture)
            for ((name, field) in unitFields) count(field).takeIf { it > 0 }?.let { units[name] = it }
            for ((name, field) in depositFields) count(field).takeIf { it > 0 }?.let { deposits[name] = it }
        }
        val errors = result.validate(ruleset, player.chosenCiv)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return result
    }
}

/** GDX caps a dropdown's position but not its width; long saved/mod names must still fit. */
internal class SetupSelectBox<T> : SelectBox<T>(BaseScreen.skin) {
    override fun newScrollPane(): SelectBoxScrollPane<T> = object : SelectBoxScrollPane<T>(this) {
        override fun show(stage: Stage) {
            super.show(stage)
            val margin = minOf(8f, stage.width / 2f)
            width = minOf(width, (stage.width - margin * 2f).coerceAtLeast(1f))
            x = x.coerceIn(margin, maxOf(margin, stage.width - width - margin))
            // Horizontal scrolling stays disabled, so List ellipsizes the display
            // while each item and the selected model/preset value remain intact.
            validate()
        }
    }
}

/** Copy each widget style; setup never modifies the shared skin or another screen's fields. */
internal fun TextField.polishSetupField() {
    style = TextField.TextFieldStyle(style).apply {
        background = GameUiStyle.background("NewGameScreen/Field", GameUiStyle.raised)
        focusedBackground = GameUiStyle.background("NewGameScreen/Field/Focused", GameUiStyle.hover)
        disabledBackground = GameUiStyle.background("NewGameScreen/Field/Disabled")
        fontColor = GameUiStyle.ink
        focusedFontColor = GameUiStyle.ink
        disabledFontColor = GameUiStyle.muted
        messageFontColor = GameUiStyle.muted
    }
}

internal fun <T> SelectBox<T>.polishSetupSelect() {
    style = SelectBox.SelectBoxStyle(style).apply {
        background = GameUiStyle.background("NewGameScreen/Select", GameUiStyle.raised)
        backgroundOver = GameUiStyle.background("NewGameScreen/Select/Over", GameUiStyle.hover)
        backgroundOpen = GameUiStyle.background("NewGameScreen/Select/Open", GameUiStyle.pressed)
        backgroundDisabled = GameUiStyle.background("NewGameScreen/Select/Disabled")
        fontColor = GameUiStyle.ink
        disabledFontColor = GameUiStyle.muted
        listStyle = ListStyle(listStyle).apply {
            background = GameUiStyle.background("NewGameScreen/Select/List")
            selection = GameUiStyle.background("NewGameScreen/Select/Selection", GameUiStyle.pressed)
            fontColorSelected = GameUiStyle.accent
            fontColorUnselected = GameUiStyle.ink
        }
        scrollStyle = ScrollPaneStyle(scrollStyle).apply {
            background = GameUiStyle.background("NewGameScreen/Select/Scroll")
        }
    }
}
