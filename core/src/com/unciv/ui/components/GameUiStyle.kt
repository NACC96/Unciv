package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton
import com.badlogic.gdx.scenes.scene2d.ui.List
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.glass.GlassSurface
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import java.util.WeakHashMap

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

    /** Corner radii of the world screen's floating surfaces and their controls. */
    object Radius {
        const val panel = 12f
        const val control = 8f
    }

    /** Secondary and Primary are the skin-wide defaults. Quiet (a filled control without an
     * outline), Ghost (transparent until hovered) and Accent (the world HUD's rounded brass
     * primary action) are opt-in roles for world HUD controls. Other skins draw Accent as Primary. */
    enum class Emphasis { Secondary, Primary, Quiet, Ghost, Accent }

    /** UI-thread presentation metadata never occupies Actor.userObject (owned by input/callers).
     * Weak keys and templates containing only copied colors/drawables retain neither actor nor style keys.
     * Templates omit fonts; each pass binds the current selected platform font.
     * A role assigned through an explicit [Emphasis] also remembers its size for later passes. */
    private data class ButtonRole(val emphasis: Emphasis = Emphasis.Secondary,
        val semanticStyle: TextButton.TextButtonStyle? = null,
        val semanticForeground: TextButton.TextButtonStyle? = null,
        val size: ButtonSize? = null) {
        val primary get() = emphasis == Emphasis.Primary
    }
    private val buttonRoles = WeakHashMap<Button, ButtonRole>()
    private val namedSemanticStyles = WeakHashMap<Button.ButtonStyle, String>()

    private fun copyForeground(source: TextButton.TextButtonStyle, target: TextButton.TextButtonStyle) {
        target.fontColor = source.fontColor?.cpy()
        target.overFontColor = source.overFontColor?.cpy()
        target.downFontColor = source.downFontColor?.cpy()
        target.checkedFontColor = source.checkedFontColor?.cpy()
        target.focusedFontColor = source.focusedFontColor?.cpy()
        target.disabledFontColor = source.disabledFontColor?.cpy()
        target.checkedOverFontColor = source.checkedOverFontColor?.cpy()
        target.checkedDownFontColor = source.checkedDownFontColor?.cpy()
    }

    private fun template(source: TextButton.TextButtonStyle) = TextButton.TextButtonStyle(source).also {
        copyForeground(source, it)
        it.font = null
    }

    private fun role(button: Button): ButtonRole = buttonRoles.getOrPut(button) {
        val source = when (button) {
            is TextButton -> button.style
            is ImageTextButton -> button.style
            else -> null
        }
        when {
            source != null && namedSemanticStyles.containsKey(source) -> ButtonRole(semanticStyle = template(source))
            source?.fontColor == surface -> ButtonRole(Emphasis.Primary)
            button is ImageTextButton && source?.fontColor != null && source.fontColor != Color.WHITE &&
                source.fontColor != ink && source.fontColor != Color.valueOf("eef2f4") ->
                ButtonRole(semanticForeground = TextButton.TextButtonStyle().also { copyForeground(source, it) })
            else -> ButtonRole()
        }
    }

    private fun controlStyle(button: Button, source: TextButton.TextButtonStyle,
        minHeight: Float? = null): TextButton.TextButtonStyle {
        val role = role(button)
        return if (role.semanticStyle != null) TextButton.TextButtonStyle(role.semanticStyle).also {
            copyForeground(role.semanticStyle, it)
            it.font = Fonts.font
        } else buttonStyle(source, role.emphasis, minHeight ?: role.size?.controlHeight ?: standardControlHeight).also {
            role.semanticForeground?.let { colors -> copyForeground(colors, it) }
        }
    }

    enum class ButtonSize(val fontSize: Int, val padVertical: Float, val padHorizontal: Float) {
        Regular(Type.label, Space.m, Space.l),
        Compact(Type.caption, 6f, Space.xs),
        Icon(Type.label, Space.s, Space.s);

        val controlHeight get() = if (this == Regular) standardControlHeight else compactControlHeight
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

    /** A floating world-screen card: a soft shadow halo (when [elevated]), a rounded fill and a
     * hairline rim. Its insets are [Space.m], not its corner size, so tables keep their own padding.
     * Other skins keep [panelBackground]. Build once; never per frame. */
    fun surface(path: String, opacity: Float = 0.90f, elevated: Boolean = true): Drawable {
        if (!isAtlas) return panelBackground(path, opacity)
        val fill = rounded(path, "surface", surface.apply { a = opacity.coerceIn(0f, 1f) })
        val rim = rounded("$path/Rim", "surface-rim", ink.apply { a = 0.12f })
        val shadow = if (elevated) rounded("Atlas/Surface/Shadow", "surface-shadow", Color(0f, 0f, 0f, 0.35f)) else null
        return SurfaceDrawable(fill, rim, shadow).apply { inset(Space.m, Space.m, 2 * Radius.panel) }
    }

    /** Frosted glass mapping of a window's opacity (0..1, see HudWindowLayout.opacity). */
    object Glass {
        /** Opacity of world cards nobody has faded: mostly tint, with the map showing through. */
        const val defaultOpacity = 0.55f
        /** How much surface color covers the blurred map. */
        fun tint(opacity: Float) = 0.3f + 0.7f * opacity.coerceIn(0f, 1f)
        /** Fill alpha when glass is off or unavailable. */
        fun fallbackAlpha(opacity: Float) = (0.45f + 0.85f * opacity.coerceIn(0f, 1f)).coerceAtMost(1f)
    }

    /** Sets the user's opacity on a [surface] drawable (other drawables are left as they are). */
    fun setSurfaceOpacity(drawable: Drawable?, opacity: Float?) {
        (drawable as? SurfaceDrawable)?.userOpacity = opacity
    }

    /** A fully rounded chip at any height, optionally with a hairline [rim]. */
    fun pill(path: String, color: Color = raised, rim: Color? = null): Drawable {
        if (!isAtlas) return if (rim == null) background(path, color) else outlinedBackground(path, color, rim)
        val fill = rounded(path, "pill", color, fullyRounded = true)
        val drawable = if (rim == null) fill else Outlined(fill, rounded("$path/Rim", "pill-rim", rim, fullyRounded = true))
        return drawable.apply { inset(Space.l, Space.s, 2 * Space.l) }
    }

    /** A control fill with the [Radius.control] corner, optionally with a hairline [rim]. */
    fun control(path: String, color: Color, rim: Color? = null): Drawable {
        if (!isAtlas) return if (rim == null) background(path, color) else outlinedBackground(path, color, rim)
        val fill = rounded(path, "control-r8", color)
        val drawable = if (rim == null) fill else Outlined(fill, rounded("$path/Rim", "control-r8-rim", rim))
        return drawable.apply { inset(Space.s, Space.s, 2 * Radius.control) }
    }

    /** A tooltip chip: an opaque raised fill, lighter than the panels it floats over, with a
     * hairline rim. Its own path: the skin's "General/Tooltip" variant is the near-black panel
     * that, under the dark base text color, drew tips as unreadable black bars. */
    fun tooltipBackground(): Drawable {
        if (!isAtlas) return background("Atlas/Tooltip", raised)
        return Outlined(rounded("Atlas/Tooltip", "control-r8", raised.lerp(hover, 0.35f).apply { a = 1f }),
            rounded("Atlas/Tooltip/Rim", "control-r8-rim", ink.apply { a = 0.18f }))
            .apply { inset(Space.s, Space.xs, 2 * Radius.control) }
    }

    /** A round status mark, e.g. [info] running, [warning] paused, [error] failed, [muted] idle. */
    fun dot(color: Color, size: Float = 8f): Image = ImageGetter.getCircle(color, size)

    /** Generated 2x art (scripts/atlas/gen_ui_ninepatches.py) drawn at half scale. Mod art at
     * Skins/<skin>/<path>, or a skin variant's own image, keeps its authored size. */
    private fun rounded(path: String, shape: String, tint: Color, fullyRounded: Boolean = false): RoundedPatch {
        val strings = BaseScreen.skinStrings
        val location = "Skins/${strings.skin}/"
        val variant = strings.skinConfig.skinVariants[path]?.image
        val modArt = ImageGetter.ninePatchImageExists(location + path) ||
            variant != null && variant != shape && ImageGetter.ninePatchImageExists(location + variant)
        // Copy before scaling: an untinted lookup returns the shared cached drawable.
        val patch = NinePatch(strings.getUiBackground(path, shape, tint).patch)
        if (!modArt) patch.scale(0.5f, 0.5f)
        return RoundedPatch(patch, fullyRounded)
    }

    private fun BaseDrawable.inset(horizontal: Float, vertical: Float, minSize: Float) {
        leftWidth = horizontal; rightWidth = horizontal
        topHeight = vertical; bottomHeight = vertical
        minWidth = minSize; minHeight = minSize
    }

    /** NinePatch has no clamp: below its corner sum the corners overlap and invert. Smaller sizes
     * shrink the whole patch uniformly instead, so corners stay round. A fully rounded patch always
     * fits its corners to the drawn height. */
    private class RoundedPatch(patch: NinePatch, private val fullyRounded: Boolean) : NinePatchDrawable(patch) {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            if (width <= 0f || height <= 0f) return
            var scale = if (fullyRounded) height / patch.totalHeight else 1f
            val cornersX = patch.leftWidth + patch.rightWidth
            val cornersY = patch.topHeight + patch.bottomHeight
            if (cornersX > 0f) scale = minOf(scale, width / cornersX)
            if (cornersY > 0f) scale = minOf(scale, height / cornersY)
            if (scale == 1f) patch.draw(batch, x, y, width, height)
            else patch.draw(batch, x, y, 0f, 0f, width / scale, height / scale, scale, scale, 0f)
        }
    }

    /** A fill with a hairline ring over it; geometry, insets and minimum size follow the fill. */
    private class Outlined(private val fill: Drawable, private val rim: Drawable) : BaseDrawable(fill) {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            fill.draw(batch, x, y, width, height)
            rim.draw(batch, x, y, width, height)
        }
    }

    /** The halo is generated for a [Radius.panel] footprint with its downward offset baked in.
     * Its corners exceed that radius by the margin it is drawn outside the panel, and its
     * knocked-out interior leaves a translucent fill translucent. */
    /** With frosted glass on (and a captured backdrop this frame) the fill is the blurred map
     * under the card, tinted with the surface color; otherwise the translucent [fill]. */
    private class SurfaceDrawable(private val fill: RoundedPatch, private val rim: RoundedPatch,
        private val shadow: RoundedPatch?) : BaseDrawable() {
        private val tint = Color(fill.patch.color).apply { a = 1f }
        private val baseAlpha = fill.patch.color.a
        /** A window's user opacity; null keeps the card defaults. */
        var userOpacity: Float? = null

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            if (width <= 0f || height <= 0f) return
            if (shadow != null) {
                val scale = minOf(1f, width / (2 * Radius.panel), height / (2 * Radius.panel))
                val margin = (shadow.patch.leftWidth - Radius.panel).coerceAtLeast(0f) * scale
                shadow.draw(batch, x - margin, y - margin, width + 2 * margin, height + 2 * margin)
            }
            val opacity = userOpacity
            if (!GlassSurface.draw(batch, x, y, width, height, Radius.panel, tint, Glass.tint(opacity ?: Glass.defaultOpacity))) {
                fill.patch.color.a = opacity?.let { Glass.fallbackAlpha(it) } ?: baseAlpha
                fill.draw(batch, x, y, width, height)
            }
            rim.draw(batch, x, y, width, height)
        }
    }

    fun button(text: String, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular): TextButton =
        text.toTextButton().also { apply(it, primary, size) }

    /** A button with an explicit [emphasis], e.g. a world HUD [Emphasis.Quiet] or [Emphasis.Ghost] control. */
    fun button(text: String, emphasis: Emphasis, size: ButtonSize = ButtonSize.Regular): TextButton =
        text.toTextButton().also { apply(it, emphasis, size) }

    fun apply(button: Button, primary: Boolean = false, size: ButtonSize = ButtonSize.Regular) {
        val previous = role(button)
        buttonRoles[button] = if (primary) ButtonRole(Emphasis.Primary)
            else if (previous.primary) ButtonRole() else previous.copy(size = previous.size?.let { size })
        restyle(button, size)
    }

    /** Assigns [emphasis] and [size]; later [applyWidget] passes and skin reloads keep both.
     * Other skins draw Quiet and Ghost as their secondary control. */
    fun apply(button: Button, emphasis: Emphasis, size: ButtonSize = ButtonSize.Regular) {
        val previous = role(button)
        buttonRoles[button] = if (emphasis == Emphasis.Secondary && previous.emphasis == Emphasis.Secondary)
            previous.copy(size = size) else ButtonRole(emphasis, size = size)
        restyle(button, size)
    }

    private fun restyle(button: Button, size: ButtonSize) {
        val style = controlStyle(button, BaseScreen.skin.get(TextButton.TextButtonStyle::class.java), size.controlHeight)
        button.style = typedButtonStyle(button, style)
        if (button is TextButton) button.label.setFontSize(size.fontSize)
        if (button is ImageTextButton) button.label.setFontSize(size.fontSize)
        if (button is CheckBox) button.imageCell.minHeight(
            (if (size == ButtonSize.Regular) standardControlHeight else compactControlHeight) - 2 * size.padVertical)
        padControl(button, size)
        button.pack()
    }

    /** Compact Quiet and Ghost text keeps clear of the rounded [Radius.control] corner; a compact
     * Accent action gets a little more room so its brass fill reads as the primary choice. */
    private fun padControl(button: Button, size: ButtonSize) {
        val emphasis = role(button).emphasis
        val rounded = isAtlas && (emphasis == Emphasis.Quiet || emphasis == Emphasis.Ghost || emphasis == Emphasis.Accent)
        val horizontal = if (!rounded || size != ButtonSize.Compact) size.padHorizontal
            else if (emphasis == Emphasis.Accent) Space.l else Space.m
        button.pad(size.padVertical, horizontal, size.padVertical, horizontal)
    }

    private fun buttonStyle(source: TextButton.TextButtonStyle, emphasis: Emphasis = Emphasis.Secondary,
        minHeight: Float = standardControlHeight): TextButton.TextButtonStyle =
        TextButton.TextButtonStyle(source).apply {
            val primary = emphasis == Emphasis.Primary || emphasis == Emphasis.Accent
            font = Fonts.font
            // Atlas: a faded fill with a faint rim; a bright outline made unavailable actions look like inputs.
            disabled = if (isAtlas) control("WorldScreen/Controls/Disabled", surface.lerp(raised, 0.4f), ink.apply { a = 0.1f })
                else outlinedBackground("WorldScreen/Controls/Disabled", surface, hover)
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
            // Clear inherited combined/focus overrides: these states must use this role's palette.
            focusedFontColor = fontColor?.cpy()
            checkedOverFontColor = checkedFontColor?.cpy()
            checkedDownFontColor = checkedFontColor?.cpy()
            checkedOver = if (primary) over else checked
            checkedDown = down
            checkedFocused = null
            if (isAtlas && (emphasis == Emphasis.Quiet || emphasis == Emphasis.Ghost))
                quietStates(emphasis == Emphasis.Ghost)
            if (isAtlas && emphasis == Emphasis.Accent) accentStates()
            if (isAtlas) {
                // The fill's geometry can be small; its actual hit/layout target remains accessible.
                for (drawable in listOf(up, over, down, checked, focused, disabled, checkedOver, checkedDown, checkedFocused))
                    if (drawable is BaseDrawable) drawable.minHeight = minHeight
            }
        }

    /** World HUD states without the secondary outline. Quiet rests on a raised fill; Ghost is
     * transparent until hovered. Checked (e.g. an open sheet) is an opaque brass tint with brass
     * text, and keyboard focus keeps a brass ring in both checked and unchecked states. */
    private fun TextButton.TextButtonStyle.quietStates(ghost: Boolean) {
        val path = if (ghost) "Atlas/Button/Ghost" else "Atlas/Button/Quiet"
        val checkedFill = surface.lerp(accent, 0.2f)
        // Every state shares the control insets, so an unpadded button never shifts between states.
        val transparent = BaseDrawable().apply { inset(Space.s, Space.s, 2 * Radius.control) }
        up = if (ghost) transparent else control("$path/Up", raised)
        over = control("$path/Over", hover)
        down = control("$path/Down", pressed)
        checked = control("$path/Checked", checkedFill)
        checkedOver = control("$path/CheckedOver", surface.lerp(accent, 0.26f))
        checkedDown = down
        focused = if (ghost) Outlined(transparent, rounded("$path/Focused/Rim", "control-r8-rim", accent))
            else control("$path/Focused", raised, accent)
        checkedFocused = control("$path/CheckedFocused", checkedFill, accent)
        disabled = if (ghost) transparent else control("$path/Disabled", surface.lerp(raised, 0.5f))
        fontColor = ink; overFontColor = ink; downFontColor = ink; focusedFontColor = ink
        checkedFontColor = accent; checkedOverFontColor = accent; checkedDownFontColor = accent
        disabledFontColor = muted
    }

    /** The world HUD primary: the brass fill and dark text of [Emphasis.Primary], drawn with the
     * rounded [Radius.control] corner of the quiet controls beside it. */
    private fun TextButton.TextButtonStyle.accentStates() {
        val path = "Atlas/Button/Accent"
        up = control("$path/Up", accent)
        over = control("$path/Over", accent.lerp(ink, 0.35f))
        down = control("$path/Down", accent.lerp(surface, 0.3f))
        checked = down
        checkedOver = over
        checkedDown = down
        focused = control("$path/Focused", accent, ink)
        checkedFocused = control("$path/CheckedFocused", accent.lerp(surface, 0.3f), ink)
        disabled = control("$path/Disabled", surface.lerp(raised, 0.5f))
    }

    /** Scene2D subclasses require their own style type. Preserve semantic image states and fonts. */
    private fun typedButtonStyle(button: Button, common: TextButton.TextButtonStyle): Button.ButtonStyle {
        fun Button.ButtonStyle.withStates() = apply {
            up = common.up; over = common.over; down = common.down; checked = common.checked
            focused = common.focused; disabled = common.disabled
            checkedOver = common.checkedOver; checkedDown = common.checkedDown; checkedFocused = common.checkedFocused
        }
        return when (button) {
            is CheckBox -> if (isAtlas) checkBoxStyle(button.style, preserveImages = true) else CheckBox.CheckBoxStyle(button.style)
            is ImageButton -> ImageButton.ImageButtonStyle(button.style).withStates()
            is ImageTextButton -> ImageTextButton.ImageTextButtonStyle(button.style).apply {
                withStates()
                font = Fonts.font
                copyForeground(common, this)
            }
            is TextButton -> common
            else -> Button.ButtonStyle(common)
        }
    }

    /** Called once after native fonts are rebound, while constructing a fresh selected skin.
     * Only Atlas default styles are replaced. Named semantic/mod styles and other skins are untouched. */
    fun initializeSkin(skin: Skin) {
        // Record every skin's named styles before replacing/copying any defaults. Old style keys
        // remain discoverable only while an existing actor still owns them across skin recreation.
        for (name in listOf("positive", "negative"))
            if (skin.has(name, TextButton.TextButtonStyle::class.java))
                namedSemanticStyles[skin.get(name, TextButton.TextButtonStyle::class.java)] = name
        if (!isAtlas) return
        skin.add("default", buttonStyle(skin.get(TextButton.TextButtonStyle::class.java)), TextButton.TextButtonStyle::class.java)
        skin.add("default", Button.ButtonStyle(skin.get(TextButton.TextButtonStyle::class.java)), Button.ButtonStyle::class.java)
        skin.add("default", Label.LabelStyle(skin.get(Label.LabelStyle::class.java)).apply { fontColor = ink }, Label.LabelStyle::class.java)
        skin.add("default", checkBoxStyle(skin.get(CheckBox.CheckBoxStyle::class.java)), CheckBox.CheckBoxStyle::class.java)
        skin.add("default", fieldStyle(skin.get(TextField.TextFieldStyle::class.java)), TextField.TextFieldStyle::class.java)
        skin.add("default", selectStyle(skin.get(SelectBox.SelectBoxStyle::class.java)), SelectBox.SelectBoxStyle::class.java)
        skin.add("default", listStyle(skin.get(List.ListStyle::class.java)), List.ListStyle::class.java)
        skin.add("default", scrollStyle(skin.get(ScrollPane.ScrollPaneStyle::class.java)), ScrollPane.ScrollPaneStyle::class.java)
        skin.add("default-horizontal", sliderStyle(), Slider.SliderStyle::class.java)
    }

    /** A thin rounded track filled in brass up to a round ink knob (brass while hovered or dragged). */
    private fun sliderStyle() = Slider.SliderStyle().apply {
        fun track(path: String, color: Color) = rounded(path, "control-r8", color).apply { minHeight = 4f; minWidth = 8f }
        fun knob(color: Color, size: Float) = ImageGetter.getCircleDrawable().tint(color).apply { minWidth = size; minHeight = size }
        background = track("Atlas/Slider/Track", raised.lerp(hover, 0.5f))
        knobBefore = track("Atlas/Slider/Fill", accent)
        knob = knob(ink, 14f)
        knobOver = knob(accent, 16f)
        knobDown = knobOver
        disabledKnob = knob(muted, 14f)
    }

    private fun checkBoxStyle(source: CheckBox.CheckBoxStyle, preserveImages: Boolean = false) = CheckBox.CheckBoxStyle(source).apply {
        fontColor = if (source.fontColor == Color.WHITE || source.fontColor == ink) ink else source.fontColor?.cpy()
        disabledFontColor = muted; overFontColor = fontColor?.cpy(); downFontColor = fontColor?.cpy(); checkedFontColor = fontColor?.cpy()
        if (!preserveImages) {
            checkboxOff = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Off", "checkbox", muted))
            checkboxOn = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/On", "checkbox-pressed", accent))
            checkboxOver = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Over", "checkbox", ink))
            checkboxOnOver = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/OnOver", "checkbox-pressed", ink))
            checkboxOffDisabled = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/Disabled", "checkbox", muted))
            checkboxOnDisabled = checkboxTarget(BaseScreen.skinStrings.getUiBackground("Atlas/Checkbox/OnDisabled", "checkbox-pressed", muted))
        }
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

    /** Filled inputs with a hairline rim (a brass rim while focused), rather than bright outlines. */
    private fun fieldStyle(source: TextField.TextFieldStyle) = TextField.TextFieldStyle(source).apply {
        fontColor = ink; disabledFontColor = muted; messageFontColor = muted
        background = control("Atlas/Field/Up", inset.lerp(raised, 0.35f), ink.apply { a = 0.16f })
        focusedBackground = control("Atlas/Field/Focus", inset.lerp(raised, 0.35f), accent)
        disabledBackground = control("Atlas/Field/Disabled", surface, ink.apply { a = 0.08f })
        cursor = BaseScreen.skinStrings.getUiBackground("Atlas/Field/Cursor", tintColor = ink)
        selection = GameUiStyle.background("Atlas/Field/Selection", hover)
        for (drawable in listOf(background, focusedBackground, disabledBackground))
            if (drawable is BaseDrawable) {
                drawable.minHeight = standardControlHeight
                drawable.leftWidth = Space.m; drawable.rightWidth = Space.m
            }
    }

    private fun listStyle(source: List.ListStyle) = List.ListStyle(source).apply {
        fontColorSelected = accent; fontColorUnselected = ink
        selection = control("Atlas/List/Selected", surface.lerp(accent, 0.2f))
        background = panelBackground("Atlas/List/Background", 1f)
        (selection as? BaseDrawable)?.minHeight = standardControlHeight
    }

    private fun scrollStyle(source: ScrollPane.ScrollPaneStyle) = ScrollPane.ScrollPaneStyle(source).apply {
        val knob = muted.apply { a = 0.45f }
        hScrollKnob = ScrollKnob(rounded("Atlas/Scroll/Horizontal", "control-r8", knob), vertical = false)
        vScrollKnob = ScrollKnob(rounded("Atlas/Scroll/Vertical", "control-r8", knob), vertical = true)
        hScroll = null; vScroll = null
    }

    /** A slim capsule centred in a narrow gutter, like overlay scrollbars: the rounded patch
     * shrinks its corners to the bar's width. Long enough to grab even in very long histories. */
    private class ScrollKnob(private val bar: Drawable, private val vertical: Boolean) : BaseDrawable() {
        init {
            if (vertical) { minWidth = gutter; minHeight = minLength } else { minWidth = minLength; minHeight = gutter }
        }
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            if (vertical) bar.draw(batch, x + (width - thickness) / 2f, y + inset, thickness, (height - 2 * inset).coerceAtLeast(thickness))
            else bar.draw(batch, x + inset, y + (height - thickness) / 2f, (width - 2 * inset).coerceAtLeast(thickness), thickness)
        }
        companion object {
            const val gutter = 10f
            const val thickness = 4f
            const val minLength = 28f
            const val inset = 2f
        }
    }

    private fun selectStyle(source: SelectBox.SelectBoxStyle) = SelectBox.SelectBoxStyle(source).apply {
        fontColor = ink; disabledFontColor = muted
        background = control("Atlas/Select/Up", raised, ink.apply { a = 0.12f })
        backgroundOver = control("Atlas/Select/Over", hover, ink.apply { a = 0.18f })
        backgroundOpen = control("Atlas/Select/Open", inset, accent)
        backgroundDisabled = control("Atlas/Select/Disabled", surface, ink.apply { a = 0.08f })
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
                actor.style = checkBoxStyle(actor.style, preserveImages = true)
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
                actor.style = controlStyle(actor, actor.style)
                val size = role(actor).size
                if (size == null) actor.pad(Space.m, Space.l, Space.m, Space.l) else padControl(actor, size)
            }
            is ImageButton, is ImageTextButton -> {
                val source = if (actor is ImageTextButton) actor.style else BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)
                actor.style = typedButtonStyle(actor, controlStyle(actor, source))
            }
            is Button -> actor.style = Button.ButtonStyle(controlStyle(actor, BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)))
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
