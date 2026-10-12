package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.GameUiStyle

/** Simple class for showing a prompt for a positive integer to the user
 * @param screen The previous screen the user was on
 * @param label A line of text shown to the user
 * @param icon Icon at the top, should have size 80f
 * @param defaultValue The number that should be in the prompt at the start
 * @param amountButtons Buttons that when clicked will add/subtract these amounts to the number
 * @param bounds The bounds in which the number must lie. Defaults to [Int.MIN_VALUE, Int.MAX_VALUE]
 * @param errorText Text that will be shown when an error is detected
 * @param validate Function that should return `true` when a valid input is detected
 * @param actionOnOk Lambda that will be executed after pressing 'OK'.
 */

class AskNumberPopup(
    screen: BaseScreen,
    label: String = "Please enter a number",
    icon: IconCircleGroup = ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(80f),
    defaultValue: Int? = null,
    amountButtons: List<Int> = listOf(),
    bounds: IntRange = IntRange(Int.MIN_VALUE, Int.MAX_VALUE),
    errorText: String = "Invalid input! Please enter a valid number.",
    validate: (input: Int) -> Boolean = { true },
    actionOnOk: (input: Int) -> Unit = { },
): Popup(screen) {
    init {
        val wrapper = Table()
        wrapper.add(icon).padRight(10f)
        wrapper.add(label.toLabel().apply { wrap = GameUiStyle.isAtlas }).growX().minWidth(0f)
        if (GameUiStyle.isAtlas) innerTable.background = GameUiStyle.panelBackground("AskNumberPopup/Panel", 1f)
        add(wrapper).colspan(2).apply { if (GameUiStyle.isAtlas) width((maxPopupWidth - 64f).coerceAtMost(540f)) }.row()

        val nameField = UncivTextField.Integer(label, defaultValue)

        fun clampInBounds(input: Int?): Int? {
            if (input == null) return null

            if (bounds.first > input) {
                return bounds.first
            }
            if (bounds.last < input)
                return bounds.last

            return input
        }

        nameField.onChange {
            nameField.intValue = clampInBounds(nameField.intValue)
        }

        val centerTable = Table(skin)
        var amountColumn = 0

        fun addValueButton(delta: Int) {
            centerTable.add(
                Button(
                    delta.toStringSigned().toLabel(),
                    skin
                ).apply {
                    onClick {
                        val value = nameField.intValue ?: return@onClick
                        nameField.intValue = clampInBounds(value + delta)
                    }
                }
            ).pad(5f).apply { if (GameUiStyle.isAtlas) growX().minWidth(0f).minHeight(40f) }
            if (GameUiStyle.isAtlas && ++amountColumn % 3 == 0) centerTable.row()
        }

        for (value in amountButtons.reversed()) {
            addValueButton(-value)
        }

        if (GameUiStyle.isAtlas) centerTable.row()
        centerTable.add(nameField).growX().minWidth(0f).minHeight(40f).pad(10f).apply {
            if (GameUiStyle.isAtlas) colspan(3)
        }
        if (GameUiStyle.isAtlas) { centerTable.row(); amountColumn = 0 }

        add(centerTable).colspan(2).row()

        for (value in amountButtons) {
            addValueButton(value)
        }

        val errorLabel = errorText.toLabel()
        errorLabel.color = if (GameUiStyle.isAtlas) GameUiStyle.error else Color.RED
        errorLabel.wrap = true
        errorLabel.isVisible = false
        add(errorLabel).colspan(2).width((maxPopupWidth - 64f).coerceAtMost(540f)).row()
        nameField.onChange { errorLabel.isVisible = false }

        addCloseButton()
        addOKButton(
            validate = {
                val errorFound = nameField.intValue?.let { validate(it) } != true
                errorLabel.isVisible = errorFound
                !errorFound
            }
        ) {
            actionOnOk(nameField.intValue!!)
        }
        equalizeLastTwoButtonWidths()
        if (GameUiStyle.isAtlas) for (cell in bottomTable.cells) {
            (cell.actor as? com.badlogic.gdx.scenes.scene2d.ui.TextButton)?.label?.wrap = true
            cell.minWidth(0f).growX().width(((maxPopupWidth - 64f) / 2).coerceAtMost(270f)).minHeight(44f)
        }

        keyboardFocus = nameField
    }
}
