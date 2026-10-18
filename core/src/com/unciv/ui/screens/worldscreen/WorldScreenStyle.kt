package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.GameUiStyle

/** Compatible HUD facade for the scoped style shared with nation setup. */
object WorldScreenStyle {
    val inset get() = GameUiStyle.inset
    val surface get() = GameUiStyle.surface
    val raised get() = GameUiStyle.raised
    val hover get() = GameUiStyle.hover
    val pressed get() = GameUiStyle.pressed
    val ink get() = GameUiStyle.ink
    val muted get() = GameUiStyle.muted
    val accent get() = GameUiStyle.accent
    val warning get() = GameUiStyle.warning
    val error get() = GameUiStyle.error
    val ownership get() = GameUiStyle.ownership
    val selection get() = GameUiStyle.selection
    val positive get() = GameUiStyle.positive
    val info get() = GameUiStyle.info
    val Radius = GameUiStyle.Radius

    fun background(path: String, color: Color = surface): Drawable = GameUiStyle.background(path, color)

    fun panelBackground(path: String, opacity: Float = 0.94f): Drawable = GameUiStyle.panelBackground(path, opacity)

    /** Floating card: halo, rounded translucent fill and hairline rim (Atlas); [panelBackground] otherwise. */
    fun surface(path: String, opacity: Float = 0.90f, elevated: Boolean = true): Drawable =
        GameUiStyle.surface(path, opacity, elevated)

    fun pill(path: String, color: Color = raised, rim: Color? = null): Drawable = GameUiStyle.pill(path, color, rim)

    fun control(path: String, color: Color, rim: Color? = null): Drawable = GameUiStyle.control(path, color, rim)

    fun dot(color: Color, size: Float = 8f): Image = GameUiStyle.dot(color, size)

    fun button(text: String, primary: Boolean = false, size: GameUiStyle.ButtonSize = GameUiStyle.ButtonSize.Regular): TextButton =
        GameUiStyle.button(text, primary, size)

    /** Filled, outline-free secondary control; checked shows a brass tint (e.g. while its sheet is open). */
    fun quietButton(text: String, size: GameUiStyle.ButtonSize = GameUiStyle.ButtonSize.Regular): TextButton =
        GameUiStyle.button(text, GameUiStyle.Emphasis.Quiet, size)

    /** Transparent until hovered, for the lowest-priority actions (Logs, Earlier, close). */
    fun ghostButton(text: String, size: GameUiStyle.ButtonSize = GameUiStyle.ButtonSize.Regular): TextButton =
        GameUiStyle.button(text, GameUiStyle.Emphasis.Ghost, size)

    fun apply(button: Button, primary: Boolean = false, size: GameUiStyle.ButtonSize = GameUiStyle.ButtonSize.Regular) =
        GameUiStyle.apply(button, primary, size)

    fun apply(button: Button, emphasis: GameUiStyle.Emphasis, size: GameUiStyle.ButtonSize = GameUiStyle.ButtonSize.Regular) =
        GameUiStyle.apply(button, emphasis, size)

    fun setEnabled(button: Button, enabled: Boolean) = GameUiStyle.setEnabled(button, enabled)

    fun dragGrip() = GameUiStyle.dragGrip()
}
