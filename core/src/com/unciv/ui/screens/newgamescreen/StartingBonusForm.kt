package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Array
import com.unciv.models.metadata.Player
import com.unciv.models.metadata.StartingBonuses
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
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
        add("Starting bonuses".toLabel(fontSize = 18)).padTop(12f).row()
        add("Extra gold".toLabel(fontSize = 14)).row()
        add(gold).width(contentWidth).row()
        add("Extra culture".toLabel(fontSize = 14)).row()
        add(culture).width(contentWidth).row()
        add("Extra units".toLabel(fontSize = 14)).padTop(6f).row()
        add("Aircraft and missiles are delivered after a city is founded. Any beyond city capacity stay pending and retry each turn or when another city is founded.".toLabel(Color.LIGHT_GRAY, 12).apply { wrap = true })
            .width(contentWidth).row()
        for ((name, count) in player.startingBonuses.units) unitFields[name] = number("unit-$name", count)
        val units = ruleset.units.values.filter { it.uniqueTo == null || it.uniqueTo == player.chosenCiv }.map { it.name }.sorted()
        addPicker(units, "Add unit", unitFields, unitRows, "unit")
        add(unitRows).width(contentWidth).row()
        add("Extra strategic-resource deposits".toLabel(fontSize = 14)).padTop(6f).row()
        add("Counts add deposit tiles within four tiles of the start. Natural locations are preferred; other workable tiles are used when needed. Resources still need ownership, technology and improvements. Set a count to 0 to remove it.".toLabel(Color.LIGHT_GRAY, 12).apply { wrap = true })
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
    }

    private fun addPicker(choices: List<String>, caption: String, fields: MutableMap<String, UncivTextField>, rows: Table, prefix: String) {
        if (choices.isEmpty()) return
        val picker = SelectBox<String>(skin).apply { setItems(Array(choices.toTypedArray())) }
        val button = caption.toTextButton().apply {
            onClick {
                val name = picker.selected
                if (name !in fields) fields[name] = number("$prefix-$name", 1)
                renderRows(fields, rows)
                stage?.keyboardFocus = fields[name]
            }
        }
        val controls = Table()
        controls.add(picker).width((contentWidth - button.prefWidth - 8f).coerceAtLeast(60f))
        controls.add(button).padLeft(8f)
        add(controls).width(contentWidth).row()
    }

    private fun renderRows(fields: Map<String, UncivTextField>, rows: Table) {
        rows.clearChildren()
        for ((name, field) in fields) {
            rows.add(name.toLabel(fontSize = 14).apply { wrap = true }).width(contentWidth - 88f).left().padRight(8f)
            rows.add(field).width(80f).padBottom(6f).row()
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
