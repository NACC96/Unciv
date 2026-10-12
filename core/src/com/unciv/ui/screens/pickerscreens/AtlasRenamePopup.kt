package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.ui.components.AtlasWidgets
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AskTextPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/** The same text validation and callbacks as AskTextPopup, with a wrapped Atlas field and fixed actions. */
internal fun createRenamePopup(
    screen: BaseScreen,
    label: String,
    icon: Group = ImageGetter.getImage("OtherIcons/Pencil")
        .apply { color = ImageGetter.CHARCOAL }.surroundWithCircle(80f),
    defaultText: String,
    validate: (String) -> Boolean,
    actionOnOk: (String) -> Unit
): Popup {
    if (!GameUiStyle.isAtlas) return AskTextPopup(screen, label, icon, defaultText,
        validate = validate, actionOnOk = actionOnOk)
    return object : Popup(screen) {
        init {
            val contentWidth = (screen.stage.width * 0.9f - 32f).coerceIn(160f, 560f)
            val field = UncivTextField(label, defaultText).apply {
                textFieldFilter = TextField.TextFieldFilter { _, char -> char !in "[]{}\"\\<>" }
                maxLength = 32
            }
            add(AtlasWidgets.header(label)).width(contentWidth).minWidth(0f).colspan(2).row()
            add(icon).size(64f).colspan(2).padBottom(GameUiStyle.Space.m).row()
            add(AtlasWidgets.field("Name", field)).width(contentWidth).minWidth(0f).colspan(2).row()
            val error = "Invalid input! Please enter a different string.".toLabel(GameUiStyle.error)
                .apply { wrap = true; isVisible = false }
            add(error).width(contentWidth).minWidth(0f).colspan(2).pad(GameUiStyle.Space.m).row()
            val close = addCloseButton().minWidth(0f).minHeight(GameUiStyle.standardControlHeight).growX()
            val confirm = addOKButton(validate = {
                validate(field.text).also { error.isVisible = !it }
            }) { actionOnOk(field.text) }.minWidth(0f).minHeight(GameUiStyle.standardControlHeight).growX()
            GameUiStyle.apply(close.actor)
            GameUiStyle.apply(confirm.actor, primary = true)
            close.actor.label.wrap = true
            confirm.actor.label.wrap = true
            keyboardFocus = field
        }
    }
}
