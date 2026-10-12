package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Shared controls. Atlas is opt-in; the existing scoped treatment remains available to other skins. */
object GameUiStyle {
    val isAtlas get() = BaseScreen.skinStrings.isAtlas
    // Return independent values: actor/style tinting must never mutate a theme token.
    private fun token(atlas: String, legacy: String) = Color.valueOf(if (isAtlas) atlas else legacy)
    val surface get() = token("10232F", "172127")
    val inset get() = token("0B1821", "172127")
    val raised get() = token("213744", "27353e")
    val hover get() = token("344D5B", "394c57")
    val pressed get() = token("0B1821", "203d43")
    val ink get() = token("F2EBDD", "eef2f4")
    val muted get() = token("B8C4C8", "b2c0c8")
    val accent get() = token("D5B776", "a1ded2")
    val warning get() = Color.valueOf("f0cd8b")
    val error get() = Color.valueOf("ffa99b")
    /** Identity and outcome colors retain their established meaning; brass marks actions/selection. */
    val ownership get() = Color.valueOf("a1ded2")
    val selection get() = accent
    val positive get() = Color.valueOf("a1ded2")
    val info get() = Color.valueOf("b9cdf5")
    const val standardControlHeight = 40f
    const val compactControlHeight = 32f

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
        BaseScreen.skinStrings.getUiBackground(path, if (isAtlas) "control" else BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)

    /** Two existing nine-patches provide a fine rim and a readable surface, without GL resources. */
    fun panelBackground(path: String, opacity: Float = 0.94f): Drawable {
        val alpha = opacity.coerceIn(0f, 1f)
        return outlined(path, Color(surface).apply { a = alpha }, Color(hover).apply { a = alpha },
            if (isAtlas) "panel" else BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
            if (isAtlas) "rim" else BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape)
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
        val shadow = if (isAtlas) BaseScreen.skinStrings.getUiBackground("Atlas/Panel/Shadow", "panel", Color(0f, 0f, 0f, 0.22f)) else null
        return object : BaseDrawable(fill) {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                if (width <= 0f || height <= 0f) return
                shadow?.draw(batch, x + 2f, y - 2f, width, height)
                fill.draw(batch, x, y, width, height)
                rim.draw(batch, x, y, width, height)
            }
        }
    }

    fun button(text: String, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular): TextButton =
        text.toTextButton().also { apply(it, primary, size) }

    fun apply(button: Button, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular) {
        val style = buttonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java), primary,
            if (size == ButtonSize.Regular) standardControlHeight else compactControlHeight)
        button.style = if (button is TextButton) style else Button.ButtonStyle(style)
        if (button is TextButton) button.label.setFontSize(size.fontSize)
        button.pad(size.padVertical, size.padHorizontal, size.padVertical, size.padHorizontal)
        button.pack()
    }

    private fun buttonStyle(source: TextButton.TextButtonStyle, primary: Boolean = false, minHeight: Float = standardControlHeight): TextButton.TextButtonStyle =
        TextButton.TextButtonStyle(source).apply {
            disabled = outlinedBackground("WorldScreen/Controls/Disabled", surface, if (isAtlas) muted else hover)
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
                up = if (isAtlas) outlinedBackground("Atlas/Button/Up", raised, muted) else background("WorldScreen/Controls/Up", raised)
                over = if (isAtlas) outlinedBackground("Atlas/Button/Over", hover, ink) else background("WorldScreen/Controls/Over", hover)
                down = if (isAtlas) outlinedBackground("Atlas/Button/Down", pressed, accent) else background("WorldScreen/Controls/Down", pressed)
                checked = if (isAtlas) outlinedBackground("Atlas/Button/Checked", pressed, accent, 2f) else background("WorldScreen/Controls/Checked", pressed)
                // Keyboard focus gets a visible ring instead of borrowing the hover state.
                focused = outlinedBackground("WorldScreen/Controls/Focused", raised, accent)
                fontColor = ink
                overFontColor = ink
                downFontColor = accent
                checkedFontColor = selection
            }
            if (isAtlas) {
                // The fill's geometry can be small; its actual hit/layout target remains accessible.
                for (drawable in listOf(up, over, down, checked, focused, disabled))
                    if (drawable is BaseDrawable) drawable.minHeight = minHeight
            }
        }

    /** Called once after native fonts are rebound, while constructing a fresh selected skin.
     * Only Atlas default styles are replaced. Named semantic/mod styles and other skins are untouched. */
    fun initializeSkin(skin: Skin) {
        if (!isAtlas) return
        skin.add("default", buttonStyle(skin.get(TextButton.TextButtonStyle::class.java)), TextButton.TextButtonStyle::class.java)
        skin.add("default", Button.ButtonStyle(skin.get(TextButton.TextButtonStyle::class.java)), Button.ButtonStyle::class.java)
        skin.add("default", Label.LabelStyle(skin.get(Label.LabelStyle::class.java)).apply { fontColor = ink }, Label.LabelStyle::class.java)
        skin.add("default", checkBoxStyle(skin.get(CheckBox.CheckBoxStyle::class.java)), CheckBox.CheckBoxStyle::class.java)
        skin.add("default", fieldStyle(skin.get(TextField.TextFieldStyle::class.java)), TextField.TextFieldStyle::class.java)
        skin.add("default", selectStyle(skin.get(SelectBox.SelectBoxStyle::class.java)), SelectBox.SelectBoxStyle::class.java)
        skin.add("default", listStyle(skin.get(List.ListStyle::class.java)), List.ListStyle::class.java)
        skin.add("default", scrollStyle(skin.get(ScrollPane.ScrollPaneStyle::class.java)), ScrollPane.ScrollPaneStyle::class.java)
    }

    private fun checkBoxStyle(source: CheckBox.CheckBoxStyle) = CheckBox.CheckBoxStyle(source).apply {
        fontColor = ink; disabledFontColor = muted; overFontColor = ink; downFontColor = ink; checkedFontColor = ink
        checkboxOff = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Off", "checkbox", muted))
        checkboxOn = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/On", "checkbox-pressed", accent))
        checkboxOver = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Over", "checkbox", ink))
        checkboxOnOver = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/OnOver", "checkbox-pressed", ink))
        checkboxOffDisabled = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Disabled", "checkbox", muted))
        checkboxOnDisabled = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/OnDisabled", "checkbox-pressed", muted))
        focused = outlinedBackground("Atlas/Checkbox/Focus", inset, accent)
    }

    /** Twenty-pixel checkbox artwork inside a forty-pixel target; no stretched tick/square. */
    private fun checkboxTarget(art: Drawable): Drawable = object : BaseDrawable(art) {
        init { minWidth = standardControlHeight; minHeight = standardControlHeight }
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val size = minOf(20f, width, height)
            art.draw(batch, x + (width - size) / 2f, y + (height - size) / 2f, size, size)
        }
    }

    private fun fieldStyle(source: TextField.TextFieldStyle) = TextField.TextFieldStyle(source).apply {
        fontColor = ink; disabledFontColor = muted; messageFontColor = muted
        background = outlinedBackground("Atlas/Field/Up", inset, muted)
        focusedBackground = outlinedBackground("Atlas/Field/Focus", inset, accent)
        disabledBackground = outlinedBackground("Atlas/Field/Disabled", surface, muted)
        cursor = BaseScreen.skinStrings.getUiBackground("Atlas/Field/Cursor", tintColor = ink)
        selection = GameUiStyle.background("Atlas/Field/Selection", hover)
        for (drawable in listOf(background, focusedBackground, disabledBackground))
            if (drawable is BaseDrawable) {
                drawable.minHeight = standardControlHeight
                drawable.leftWidth = Space.m; drawable.rightWidth = Space.m
            }
    }

    private fun listStyle(source: List.ListStyle) = List.ListStyle(source).apply {
        fontColorSelected = ink; fontColorUnselected = ink
        selection = outlinedBackground("Atlas/List/Selected", pressed, accent)
        background = panelBackground("Atlas/List/Background", 1f)
        (selection as? BaseDrawable)?.minHeight = standardControlHeight
    }

    private fun scrollStyle(source: ScrollPane.ScrollPaneStyle) = ScrollPane.ScrollPaneStyle(source).apply {
        hScrollKnob = GameUiStyle.background("Atlas/Scroll/Horizontal", muted)
        vScrollKnob = GameUiStyle.background("Atlas/Scroll/Vertical", muted)
    }

    private fun selectStyle(source: SelectBox.SelectBoxStyle) = SelectBox.SelectBoxStyle(source).apply {
        fontColor = ink; disabledFontColor = muted
        background = outlinedBackground("Atlas/Select/Up", raised, muted)
        backgroundOver = outlinedBackground("Atlas/Select/Over", hover, muted)
        backgroundOpen = outlinedBackground("Atlas/Select/Open", inset, accent)
        backgroundDisabled = outlinedBackground("Atlas/Select/Disabled", surface, muted)
        for (drawable in listOf(background, backgroundOver, backgroundOpen, backgroundDisabled))
            if (drawable is BaseDrawable) {
                drawable.minHeight = standardControlHeight
                drawable.leftWidth = Space.m; drawable.rightWidth = Space.m
            }
        listStyle = listStyle(source.listStyle)
        scrollStyle = scrollStyle(source.scrollStyle).apply { background = panelBackground("Atlas/Select/List", 1f) }
    }

    /** One-time creation/reload styling, including a closed ExpanderTab's detached content.
     * Never call from render or snapshot polling. Copies styles, retains fonts, explicit semantic
     * colors, listeners, shortcuts, focus, draft text and disabled/checked state. */
    fun applyWidget(actor: Actor) {
        if (!isAtlas) return
        when (actor) {
            is CheckBox -> {
                val original = actor.label.style.fontColor
                actor.style = checkBoxStyle(actor.style)
                if (original != Color.WHITE && original != ink && original != Color.valueOf("eef2f4"))
                    actor.label.style = Label.LabelStyle(actor.label.style).apply { fontColor = original?.cpy() }
                // Preserve natural preferred width, but permit long translated labels to wrap.
                if (!actor.label.wrap) actor.labelCell.prefWidth(actor.label.prefWidth)
                actor.label.wrap = true
                actor.labelCell.minWidth(0f).growX()
                actor.pad(Space.m)
                actor.cells.firstOrNull()?.minHeight(standardControlHeight - 2 * Space.m)
            }
            is TextButton -> {
                val semanticStyle = listOf("positive", "negative").any {
                    BaseScreen.skin.has(it, TextButton.TextButtonStyle::class.java) &&
                        actor.style === BaseScreen.skin.get(it, TextButton.TextButtonStyle::class.java)
                }
                actor.style = if (semanticStyle) TextButton.TextButtonStyle(actor.style).apply {
                    fontColor = fontColor?.cpy(); overFontColor = overFontColor?.cpy(); downFontColor = downFontColor?.cpy()
                } else buttonStyle(actor.style, primary = actor.style.fontColor == surface)
                actor.pad(Space.m, Space.l, Space.m, Space.l)
            }
            is Button -> actor.style = Button.ButtonStyle(buttonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)))
            is TextField -> actor.style = fieldStyle(actor.style)
            is SelectBox<*> -> actor.style = selectStyle(actor.style)
            is Label -> {
                val original = actor.style.fontColor
                val color = if (original == Color.WHITE || original == ink || original == Color.valueOf("eef2f4")) ink else original?.cpy()
                actor.style = Label.LabelStyle(actor.style).apply { fontColor = color }
            }
            is ExpanderTab -> actor.header.background = panelBackground("Atlas/Disclosure/Header", 1f)
        }
        // A button owns its label style; visiting it again would erase primary/semantic text.
        if (actor is Group && actor !is Button) actor.children.forEach { applyWidget(it) }
        if (actor is ExpanderTab && actor.innerTable.parent == null) applyWidget(actor.innerTable)
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
