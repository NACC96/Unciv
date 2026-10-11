package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** The scoped charcoal/teal treatment shared by the HUD and nation setup widgets. */
object GameUiStyle {
    // Palette
    val surface = Color.valueOf("172127")
    val raised = Color.valueOf("27353e")
    val hover = Color.valueOf("394c57")
    val pressed = Color.valueOf("203d43")
    val ink = Color.valueOf("eef2f4")
    val muted = Color.valueOf("b2c0c8")
    val accent = Color.valueOf("a1ded2")
    val warning = Color.valueOf("f0cd8b")
    val error = Color.valueOf("ffa99b")

    // Semantic roles: each colour should mean one thing wherever it appears.
    /** The viewer's own cities and units. */
    val ownership get() = accent
    /** The active choice in a list, sort or toggle. */
    val selection get() = accent
    /** Completed or confirmed progress, such as an acknowledged message. */
    val positive get() = accent
    /** Neutral supplementary facts, such as configured starting bonuses. */
    val info = Color.valueOf("b9cdf5")

    /** One type scale for every polished surface; no label in these panels goes below [caption]. */
    object Type {
        const val caption = 11
        const val small = 12
        const val body = 13
        const val label = 14
        const val title = 17
        const val display = 22
    }

    /** A 4-pt spacing scale. */
    object Space {
        const val xs = 2f
        const val s = 4f
        const val m = 8f
        const val l = 12f
        const val xl = 16f
    }

    enum class ButtonSize(val fontSize: Int, val padVertical: Float, val padHorizontal: Float) {
        Regular(Type.label, Space.m, Space.l),
        Compact(Type.caption, 6f, Space.xs),
        Icon(Type.label, Space.s, Space.s),
    }

    fun background(path: String, color: Color = surface): Drawable =
        BaseScreen.skinStrings.getUiBackground(path, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)

    /** Two existing nine-patches provide a fine rim and a readable surface, without GL resources. */
    fun panelBackground(path: String, opacity: Float = 0.94f): Drawable {
        val alpha = opacity.coerceIn(0f, 1f)
        return outlined(path, Color(surface).apply { a = alpha }, Color(hover).apply { a = alpha },
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape, BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape)
    }

    /** A filled control surface with a rim, for focus, disabled and primary states.
     *  The rim is the same small rounded shape drawn behind an inset fill, so corners match the other states. */
    fun outlinedBackground(path: String, fillColor: Color, rimColor: Color, rimWidth: Float = 1.5f): Drawable {
        val rim = background("$path/Rim", rimColor)
        val fill = background(path, fillColor)
        return object : BaseDrawable(fill) {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                if (width <= 0f || height <= 0f) return
                rim.draw(batch, x, y, width, height)
                fill.draw(batch, x + rimWidth, y + rimWidth,
                    (width - 2 * rimWidth).coerceAtLeast(0f), (height - 2 * rimWidth).coerceAtLeast(0f))
            }
        }
    }

    private fun outlined(path: String, fillColor: Color, rimColor: Color, fillShape: String, rimShape: String): Drawable {
        val rim = BaseScreen.skinStrings.getUiBackground("$path/Rim", rimShape, rimColor)
        val fill = BaseScreen.skinStrings.getUiBackground(path, fillShape, fillColor)
        return object : BaseDrawable(fill) {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                if (width <= 0f || height <= 0f) return
                fill.draw(batch, x, y, width, height)
                rim.draw(batch, x, y, width, height)
            }
        }
    }

    fun button(text: String, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular): TextButton =
        text.toTextButton().also { apply(it, primary, size) }

    fun apply(button: Button, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular) {
        val style = TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            disabled = outlinedBackground("WorldScreen/Controls/Disabled", surface, hover)
            disabledFontColor = muted
            if (primary) {
                // A filled accent action, so the primary choice never reads as a pressed secondary.
                up = background("WorldScreen/Controls/PrimaryUp", accent)
                over = background("WorldScreen/Controls/PrimaryOver", accent.cpy().lerp(ink, 0.35f))
                down = background("WorldScreen/Controls/PrimaryDown", accent.cpy().lerp(surface, 0.3f))
                checked = down
                focused = outlinedBackground("WorldScreen/Controls/PrimaryFocused", accent, ink)
                fontColor = surface
                overFontColor = surface
                downFontColor = surface
                checkedFontColor = surface
            } else {
                up = background("WorldScreen/Controls/Up", raised)
                over = background("WorldScreen/Controls/Over", hover)
                down = background("WorldScreen/Controls/Down", pressed)
                checked = background("WorldScreen/Controls/Checked", pressed)
                // Keyboard focus gets a visible ring instead of borrowing the hover state.
                focused = outlinedBackground("WorldScreen/Controls/Focused", raised, accent)
                fontColor = ink
                overFontColor = ink
                downFontColor = accent
                checkedFontColor = selection
            }
        }
        button.style = if (button is TextButton) style else Button.ButtonStyle(style)
        if (button is TextButton) button.label.setFontSize(size.fontSize)
        button.pad(size.padVertical, size.padHorizontal, size.padVertical, size.padHorizontal)
        button.pack()
    }

    /** Disabled controls still consume hits so clicks cannot pass through to the map. */
    fun setEnabled(button: Button, enabled: Boolean) {
        button.isDisabled = !enabled
        button.touchable = Touchable.enabled
    }

    /** A six-dot grip for movable panel headers; the hint moves into a tooltip. */
    fun dragGrip(tooltip: String = "Drag to move"): Table = Table().apply {
        name = "DragGrip"
        touchable = Touchable.enabled
        for (row in 0 until 3) {
            for (column in 0 until 2)
                add(ImageGetter.getWhiteDot().apply { color = muted }).size(3f).pad(1f)
            row()
        }
        addTooltip(tooltip, 18f, targetAlign = com.badlogic.gdx.utils.Align.bottom,
            tipAlign = com.badlogic.gdx.utils.Align.top)
    }
}
