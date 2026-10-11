package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.GameUiStyle

/** Compatible HUD facade for the scoped style shared with nation setup. */
object WorldScreenStyle {
    val surface get() = GameUiStyle.surface
    val raised get() = GameUiStyle.raised
    val hover get() = GameUiStyle.hover
    val pressed get() = GameUiStyle.pressed
    val ink get() = GameUiStyle.ink
    val muted get() = GameUiStyle.muted
    val accent get() = GameUiStyle.accent
    val warning get() = GameUiStyle.warning
    val error get() = GameUiStyle.error

    fun background(path: String, color: Color = surface): Drawable = GameUiStyle.background(path, color)

    fun panelBackground(path: String, opacity: Float = 0.94f): Drawable = GameUiStyle.panelBackground(path, opacity)

    fun button(text: String, primary: Boolean = false): TextButton = GameUiStyle.button(text, primary)

    fun apply(button: Button, primary: Boolean = false) = GameUiStyle.apply(button, primary)

    fun setEnabled(button: Button, enabled: Boolean) = GameUiStyle.setEnabled(button, enabled)
}
