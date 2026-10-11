package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.screens.basescreen.BaseScreen

/** Scoped HUD styling; setup and other screens retain their own skin. */
object WorldScreenStyle {
    val surface = Color.valueOf("172127")
    val raised = Color.valueOf("27353e")
    val hover = Color.valueOf("394c57")
    val pressed = Color.valueOf("203d43")
    val ink = Color.valueOf("eef2f4")
    val muted = Color.valueOf("b2c0c8")
    val accent = Color.valueOf("a1ded2")
    val warning = Color.valueOf("f0cd8b")
    val error = Color.valueOf("ffa99b")

    fun background(path: String, color: Color = surface) = BaseScreen.skinStrings.getUiBackground(
        path, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)

    fun button(text: String, primary: Boolean = false): TextButton = text.toTextButton().also { apply(it, primary) }

    fun apply(button: Button, primary: Boolean = false) {
        val style = TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = background("WorldScreen/Controls/Up", if (primary) pressed else raised)
            over = background("WorldScreen/Controls/Over", hover)
            down = background("WorldScreen/Controls/Down", pressed)
            checked = background("WorldScreen/Controls/Checked", pressed)
            focused = over
            disabled = background("WorldScreen/Controls/Disabled", surface)
            fontColor = ink
            overFontColor = ink
            downFontColor = accent
            checkedFontColor = accent
            disabledFontColor = muted
        }
        button.style = if (button is TextButton) style else Button.ButtonStyle(style)
        if (button is TextButton) button.label.setFontSize(14)
        button.pad(8f, 12f, 8f, 12f)
        button.pack()
    }

    /** Keep the scoped style and consume hits inside a disabled control. */
    fun setEnabled(button: Button, enabled: Boolean) {
        button.isDisabled = !enabled
        button.touchable = Touchable.enabled
    }
}
