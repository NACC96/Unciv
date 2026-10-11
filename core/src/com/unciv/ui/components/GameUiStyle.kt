package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.screens.basescreen.BaseScreen

/** The scoped charcoal/teal treatment shared by the HUD and nation setup widgets. */
object GameUiStyle {
    val surface = Color.valueOf("172127")
    val raised = Color.valueOf("27353e")
    val hover = Color.valueOf("394c57")
    val pressed = Color.valueOf("203d43")
    val ink = Color.valueOf("eef2f4")
    val muted = Color.valueOf("b2c0c8")
    val accent = Color.valueOf("a1ded2")
    val warning = Color.valueOf("f0cd8b")
    val error = Color.valueOf("ffa99b")

    fun background(path: String, color: Color = surface): Drawable =
        BaseScreen.skinStrings.getUiBackground(path, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)

    /** Two existing nine-patches provide a fine rim and a readable surface, without GL resources. */
    fun panelBackground(path: String, opacity: Float = 0.94f): Drawable {
        val alpha = opacity.coerceIn(0f, 1f)
        val rim = BaseScreen.skinStrings.getUiBackground("$path/Rim",
            BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape, Color(hover).apply { a = alpha })
        val fill = BaseScreen.skinStrings.getUiBackground(path,
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape, Color(surface).apply { a = alpha })
        return object : BaseDrawable(fill) {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                if (width <= 0f || height <= 0f) return
                fill.draw(batch, x, y, width, height)
                rim.draw(batch, x, y, width, height)
            }
        }
    }

    fun button(text: String, primary: Boolean = false): TextButton =
        text.toTextButton().also { apply(it, primary) }

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

    /** Disabled controls still consume hits so clicks cannot pass through to the map. */
    fun setEnabled(button: Button, enabled: Boolean) {
        button.isDisabled = !enabled
        button.touchable = Touchable.enabled
    }
}
