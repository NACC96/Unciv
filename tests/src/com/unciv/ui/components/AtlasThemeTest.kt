package com.unciv.ui.components

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.ui.components.fonts.Fonts
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.ImageTextButton
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.UncivGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.models.skins.SkinCache
import com.unciv.models.skins.SkinConfig
import com.unciv.json.json
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.ActivationListener
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.widgets.ExpanderTab
import org.mockito.Mockito
import com.unciv.ui.screens.basescreen.BaseScreen
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.testing.GdxTestRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

@RunWith(GdxTestRunner::class)
class AtlasThemeTest {
    private lateinit var snapshot: SetupUiStateSnapshot
    @Before fun rememberState() { snapshot = SetupUiStateSnapshot(); SetupLayoutFixture(800f, 500f) }
    @After fun restoreState() { snapshot.restore() }

    private fun selectSkin(name: String) {
        UncivGame.Current.settings.skin = name
        BaseScreen.setSkin()
    }

    @Test fun atlasContrastOnBrightAndDarkMaps() {
        selectSkin("Atlas")
        assertEquals(Color.valueOf("10232F"), GameUiStyle.surface)
        assertEquals(Color.valueOf("F2EBDD"), BaseScreen.skin.get(Label.LabelStyle::class.java).fontColor)
        val terrain = listOf(Color.valueOf("F1E7C7"), Color.valueOf("092B45"))
        val composited = terrain.map { composite(GameUiStyle.surface.cpy().apply { a = 0.94f }, it) }
        val minBodyContrast = composited.flatMap { listOf(contrast(GameUiStyle.ink, it), contrast(GameUiStyle.muted, it)) }.min()
        val minControlContrast = composited.map { contrast(GameUiStyle.accent, it) }.min()
        assertTrue(minBodyContrast >= 4.5)
        assertTrue(minControlContrast >= 3.0)
        val button = GameUiStyle.button("Confirm", primary = true)
        assertTrue(contrast(button.style.fontColor, GameUiStyle.accent) >= 4.5)
        assertTrue(contrast(button.style.downFontColor, GameUiStyle.accent.cpy().lerp(GameUiStyle.surface, 0.3f)) >= 4.5)
        assertTrue(contrast(GameUiStyle.muted, GameUiStyle.raised) >= 4.5)
        assertTrue(contrast(GameUiStyle.accent, GameUiStyle.raised) >= 3.0)
        assertTrue(contrast(Color.RED, GameUiStyle.surface) >= 3.0) // status symbol, paired with ivory text.
    }

    @Test fun legacySkinSurvivesAtlasSelectionCycle() {
        SkinCache["SyntheticModSkin"] = json().fromJson(SkinConfig::class.java,
            """{"clearColor":{"r":0.4,"g":0.1,"b":0.2,"a":1},"skinVariants":{"Probe":{"tint":{"r":0.2,"g":0.6,"b":0.3,"a":1}}}}""")
        for (name in listOf("Minimal", "SyntheticModSkin")) {
            selectSkin(name)
            val before = BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)
            val beforeField = BaseScreen.skin.get(TextField.TextFieldStyle::class.java)
            val beforeColor = GameUiStyle.surface.cpy()
            val beforeProbe = BaseScreen.skinStrings.getUIColor("Probe")
            selectSkin("Atlas")
            selectSkin(name)
            val after = BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)
            assertEquals(before.fontColor, after.fontColor)
            assertEquals(before.overFontColor, after.overFontColor)
            assertEquals(before.up.minHeight, after.up.minHeight, 0f)
            assertEquals(beforeField.fontColor, BaseScreen.skin.get(TextField.TextFieldStyle::class.java).fontColor)
            assertEquals(beforeColor, GameUiStyle.surface)
            assertEquals(beforeProbe, BaseScreen.skinStrings.getUIColor("Probe"))
            assertNotEquals(Color.valueOf("F2EBDD"), after.fontColor)
        }
    }

    @Test fun semanticColorsAndSharedStylesRemainIndependent() {
        selectSkin("Atlas")
        val red = "✘ Invalid".toLabel(Color.RED)
        val nationColor = Color.valueOf("7254CB")
        val nation = "Rome".toLabel(nationColor)
        val shared = BaseScreen.skin.get(TextField.TextFieldStyle::class.java)
        val field = TextField("draft", BaseScreen.skin)
        GameUiStyle.applyWidget(red)
        GameUiStyle.applyWidget(nation)
        GameUiStyle.applyWidget(field)
        assertEquals(Color.RED, red.style.fontColor)
        assertEquals(nationColor, nation.style.fontColor)
        assertNotSame(shared, field.style)
        field.style.fontColor.a = 0.2f
        assertEquals(1f, shared.fontColor.a, 0f)
        assertEquals(1f, GameUiStyle.ink.a, 0f)
        val negative = TextButton("Delete", BaseScreen.skin, "negative")
        val negativeBackground = negative.style.up
        GameUiStyle.applyWidget(negative)
        assertSame(negativeBackground, negative.style.up)
        red.style.fontColor.a = 0.1f
        assertEquals(1f, Color.RED.a, 0f)
    }

    @Test fun buildersKeepCallbacksShortcutsAndAccessibleTargets() {
        selectSkin("Atlas")
        var calls = 0
        val action = GameUiStyle.button("Confirm").apply { onActivation { calls++ }; keyShortcuts.add('c') }
        val listeners = action.listeners.toList()
        val footer = AtlasWidgets.footer(action, listOf(GameUiStyle.button("Cancel")))
        footer.pack()
        assertEquals(listeners, action.listeners.toList())
        assertTrue(KeyCharAndCode('c') in action.keyShortcuts)
        assertTrue(action.height >= 40f)
        action.silenceSetupActivationSounds()
        action.listeners.filterIsInstance<ActivationListener>().single()
            .tap(InputEvent().apply { listenerActor = action }, 1f, 1f, 1, 0)
        assertEquals(1, calls)
        val compact = GameUiStyle.button("+", size = GameUiStyle.ButtonSize.Compact)
        assertTrue(compact.height >= 32f)
        val input = TextField("draft", BaseScreen.skin)
        val form = AtlasWidgets.field("Message", input)
        form.pack()
        assertSame(input, form.cells.last().actor)
        assertTrue(input.height >= 40f)
        val checkBox = "Enabled".toCheckBox()
        checkBox.pack()
        assertTrue(checkBox.height >= 40f)
        var tab = -1
        val tabs = AtlasWidgets.tabs(listOf("Economy", "Science"), 0) { tab = it }
        val second = tabs.children.filterIsInstance<TextButton>().last()
        second.silenceSetupActivationSounds()
        second.listeners.filterIsInstance<ActivationListener>().single()
            .tap(InputEvent().apply { listenerActor = second }, 1f, 1f, 1, 0)
        assertEquals(1, tab)
        assertTrue(second.isChecked)
        assertTrue(second.text.startsWith("✓"))
    }

    @Test fun detachedAndAttachedDisclosureContentIsVisitedExactlyOnce() {
        selectSkin("Atlas")
        val button = Mockito.spy(TextButton("Action", BaseScreen.skin))
        val disclosure = ExpanderTab("Details", startsOutOpened = false) { it.add(button) }
        for (open in listOf(false, true)) {
            disclosure.isOpen = open
            Mockito.clearInvocations(button)
            GameUiStyle.applyWidget(disclosure)
            Mockito.verify(button, Mockito.times(1)).setStyle(Mockito.any(com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle::class.java))
        }
    }

    @Test fun selectedSkinDiscoveryAndColorCopiesRetainModOverrides() {
        assertTrue("Atlas" in ImageGetter.getAvailableSkins().toList())
        selectSkin("Atlas")
        SkinCache["Atlas"] = json().fromJson(SkinConfig::class.java,
            """{"skinVariants":{"Probe":{"tint":{"r":0.2,"g":0.6,"b":0.3,"a":1},"foregroundColor":{"r":0.4,"g":0.3,"b":0.2,"a":1}}}}""")
        BaseScreen.setSkin()
        val before = BaseScreen.skinStrings.getUIColor("Probe")
        val returned = BaseScreen.skinStrings.getUIColor("Probe")
        returned.a = 0f
        assertEquals(before, BaseScreen.skinStrings.getUIColor("Probe"))
        val foreground = BaseScreen.skinStrings.getUIFontColor("Probe")!!
        foreground.a = 0f
        assertEquals(1f, BaseScreen.skinStrings.getUIFontColor("Probe")!!.a, 0f)
        assertSame(com.unciv.ui.components.fonts.Fonts.font, BaseScreen.skin.get(Label.LabelStyle::class.java).font)
    }

    @Test fun stylingPreservesRealStageKeyboardFocus() {
        selectSkin("Atlas")
        val stage = Stage(ScreenViewport(), Mockito.mock(Batch::class.java))
        try {
            stage.viewport.update(800, 500, true)
            val field = TextField("draft", BaseScreen.skin)
            stage.addActor(field)
            stage.keyboardFocus = field
            val listeners = field.listeners.toList()
            BaseScreen.setSkin()
            GameUiStyle.applyWidget(stage.root)
            assertSame(field, stage.keyboardFocus)
            assertEquals("draft", field.text)
            assertEquals(listeners, field.listeners.toList())
        } finally { stage.dispose() }
    }

    @Test fun reusableRowsFitNarrowViewportAtLargeTextScale() {
        UncivGame.Current.settings.fontSizeMultiplier = 1.5f
        com.unciv.ui.components.fonts.Fonts.fontImplementation = com.unciv.app.desktop.DesktopFont()
        selectSkin("Atlas")
        val width = 372f // 420px viewport minus outer/panel padding.
        val stats = Table().apply {
            add(AtlasWidgets.stat("Gold", "1,204")).uniformX().growX()
            add(AtlasWidgets.stat("Wheat", "Wheat 12")).uniformX().growX()
            add(AtlasWidgets.stat("Rome", "● Nation")).uniformX().growX()
        }
        val checkbox = "Enabled status with a long translated description".toCheckBox()
        GameUiStyle.applyWidget(checkbox)
        assertTrue("Checkbox label must wrap at narrow widths", checkbox.label.wrap)
        checkbox.setSize(width, checkbox.prefHeight)
        checkbox.validate()
        assertTrue(checkbox.label.x >= 0f)
        assertTrue(checkbox.label.x + checkbox.label.width <= width + 0.1f)
        val disclosure = AtlasWidgets.disclosure("Long detail — expand for secondary context", "Body".toLabel(), true) as ExpanderTab
        val heading = disclosure.header.children.filterIsInstance<Label>().single()
        assertTrue("Disclosure heading must wrap at narrow widths", heading.wrap)
        disclosure.setSize(width, disclosure.prefHeight)
        // Scene2D invalidates ancestor preferred heights when a wrapping label receives its width.
        repeat(3) {
            disclosure.setHeight(disclosure.prefHeight)
            disclosure.validate()
            disclosure.header.validate()
        }
        assertTrue(heading.x >= 0f)
        assertTrue(heading.x + heading.width <= disclosure.header.width + 0.1f)
        assertTrue("Wrapped heading height ${heading.height} is below ${heading.prefHeight}", heading.height >= heading.prefHeight - 0.1f)
        val tabs = AtlasWidgets.tabs(listOf("Civilization details", "Research prerequisites", "International diplomacy"), 0) {}
        for (row in listOf(stats, tabs)) {
            row.setSize(width, row.prefHeight)
            row.validate()
            for (child in row.children) {
                assertTrue("${child.javaClass.simpleName} starts outside narrow row: ${child.x}", child.x >= -0.1f)
                assertTrue("${child.javaClass.simpleName} exceeds narrow row: ${child.x + child.width}", child.x + child.width <= width + 0.1f)
            }
        }
    }

    @Test fun imageControlsRetainTypedStylesImagesAndCallbacks() {
        selectSkin("Atlas")
        val image = ImageGetter.getDrawable("OtherIcons/Close")
        val semanticImage = ImageGetter.getDrawable("OtherIcons/Checkmark")
        val icon = ImageButton(ImageButton.ImageButtonStyle().apply {
            imageUp = image; imageOver = semanticImage; imageChecked = semanticImage; imageDisabled = semanticImage
        })
        val captioned = ImageTextButton("Shortcut", ImageTextButton.ImageTextButtonStyle().apply {
            font = com.unciv.ui.components.fonts.Fonts.font
            fontColor = GameUiStyle.ink
            imageUp = image; imageOver = semanticImage; imageChecked = semanticImage; imageDisabled = semanticImage
        })
        val checkbox = "Keep choice".toCheckBox(true).apply { isDisabled = true }
        checkbox.style = CheckBox.CheckBoxStyle(checkbox.style).apply {
            checkboxOn = ImageGetter.getWhiteDot().drawable // small explicit artwork must retain a full target
        }
        val checkboxImage = checkbox.style.checkboxOn
        for (button in listOf(icon, captioned, checkbox)) {
            button.isChecked = true
            button.isDisabled = true
            button.onActivation {}
            button.keyShortcuts.add('x')
            val listeners = button.listeners.toList()
            GameUiStyle.applyWidget(button)
            assertTrue(button.isChecked)
            assertTrue(button.isDisabled)
            assertEquals(listeners, button.listeners.toList())
            assertTrue(KeyCharAndCode('x') in button.keyShortcuts)
            when (button) {
                is ImageButton -> {
                    assertSame(image, button.style.imageUp)
                    assertSame(semanticImage, button.style.imageChecked)
                    assertSame(semanticImage, button.style.imageOver)
                    assertSame(semanticImage, button.style.imageDisabled)
                }
                is ImageTextButton -> {
                    assertSame(image, button.style.imageUp)
                    assertSame(semanticImage, button.style.imageChecked)
                    assertSame(semanticImage, button.style.imageOver)
                    assertSame(semanticImage, button.style.imageDisabled)
                }
                is CheckBox -> assertSame(checkboxImage, button.style.checkboxOn)
            }
            GameUiStyle.apply(button)
            assertTrue(button.height >= 40f)
            assertTrue(button.isChecked)
            assertTrue(button.isDisabled)
            assertEquals(listeners, button.listeners.toList())
            when (button) {
                is ImageButton -> {
                    assertSame(image, button.style.imageUp)
                    assertSame(semanticImage, button.style.imageChecked)
                    assertSame(semanticImage, button.style.imageOver)
                    assertSame(semanticImage, button.style.imageDisabled)
                }
                is ImageTextButton -> {
                    assertSame(image, button.style.imageUp)
                    assertSame(semanticImage, button.style.imageChecked)
                    assertSame(semanticImage, button.style.imageOver)
                    assertSame(semanticImage, button.style.imageDisabled)
                }
                is CheckBox -> assertSame(checkboxImage, button.style.checkboxOn)
            }
        }
    }

    @Test fun semanticButtonRolesSurviveRepeatPassesAndSkinRecreation() {
        selectSkin("Atlas")
        for (name in listOf("positive", "negative")) {
            for (reloadBeforeFirstPass in listOf(false, true)) {
                val button = TextButton(name, BaseScreen.skin, name)
                val backgrounds = backgrounds(button.style)
                val colors = foregrounds(button.style).map { it?.cpy() }
                button.onActivation {}
                button.keyShortcuts.add('z')
                val owner = button.userObject
                button.isChecked = true; button.isDisabled = true
                val listeners = button.listeners.toList()
                if (reloadBeforeFirstPass) BaseScreen.setSkin()
                repeat(2) {
                    GameUiStyle.applyWidget(button)
                    backgrounds.zip(backgrounds(button.style)).forEach { (before, after) -> assertSame(before, after) }
                    assertEquals(colors, foregrounds(button.style))
                    assertSame(Fonts.font, button.style.font)
                }
                BaseScreen.setSkin()
                GameUiStyle.applyWidget(button)
                backgrounds.zip(backgrounds(button.style)).forEach { (before, after) -> assertSame(before, after) }
                assertEquals(colors, foregrounds(button.style))
                assertSame(Fonts.font, button.style.font)
                assertSame(owner, button.userObject)
                assertEquals(listeners, button.listeners.toList())
                assertTrue(button.isChecked && button.isDisabled)
                assertTrue(KeyCharAndCode('z') in button.keyShortcuts)
            }
        }
    }

    @Test fun everyPrimaryButtonSubclassKeepsItsRoleAfterRootPassAndReload() {
        selectSkin("Atlas")
        val image = ImageGetter.getDrawable("OtherIcons/Checkmark")
        val icon = ImageButton(ImageButton.ImageButtonStyle().apply { imageUp = image })
        val plain = Button(BaseScreen.skin)
        val text = GameUiStyle.button("Confirm", primary = true)
        for (button in listOf(plain, icon, text)) {
            val owner = Any(); button.userObject = owner
            val footer = AtlasWidgets.footer(button, emptyList())
            repeat(2) {
                GameUiStyle.applyWidget(footer)
                assertTrue("Primary fill replaced by secondary states", button.style.up is NinePatchDrawable)
                assertEquals(GameUiStyle.accent, (button.style.up as NinePatchDrawable).patch.color)
            }
            BaseScreen.setSkin()
            GameUiStyle.applyWidget(footer)
            assertEquals(GameUiStyle.accent, (button.style.up as NinePatchDrawable).patch.color)
            assertSame(owner, button.userObject)
            if (button is ImageButton) assertSame(image, button.style.imageUp)
        }
    }

    @Test fun typedPrimaryTransfersEveryForegroundStateAndCopiesColors() {
        selectSkin("Atlas")
        val image = ImageGetter.getDrawable("OtherIcons/Keyboard")
        val incoming = ImageTextButton.ImageTextButtonStyle().apply {
            font = Fonts.font; imageUp = image; fontColor = GameUiStyle.ink
            overFontColor = GameUiStyle.ink; downFontColor = GameUiStyle.ink
            checkedFontColor = GameUiStyle.ink; focusedFontColor = GameUiStyle.ink
            checkedOverFontColor = GameUiStyle.ink; checkedDownFontColor = GameUiStyle.ink
        }
        val button = ImageTextButton("Confirm", incoming)
        button.onActivation {}; button.keyShortcuts.add('y')
        val owner = button.userObject
        val listeners = button.listeners.toList()
        val footer = AtlasWidgets.footer(button, emptyList())
        fun verify() {
            val reference = GameUiStyle.button("Reference", primary = true).style
            assertEquals(foregrounds(reference), foregrounds(button.style))
            assertSame(Fonts.font, button.style.font)
            assertSame(image, button.style.imageUp)
            assertSame(owner, button.userObject)
            assertEquals(listeners, button.listeners.toList())
        }
        verify()
        repeat(2) { GameUiStyle.applyWidget(footer); verify() }
        BaseScreen.setSkin(); GameUiStyle.applyWidget(footer); verify()
        button.style.overFontColor.a = 0.2f
        assertEquals(1f, incoming.overFontColor.a, 0f)
    }

    @Test fun explicitTypedSemanticForegroundSurvivesRepeatPassesAndReload() {
        selectSkin("Atlas")
        val image = ImageGetter.getDrawable("OtherIcons/Checkmark")
        val incoming = ImageTextButton.ImageTextButtonStyle().apply {
            font = Fonts.font; imageUp = image
            fontColor = GameUiStyle.positive; overFontColor = GameUiStyle.warning
            downFontColor = GameUiStyle.error; checkedFontColor = GameUiStyle.info
            focusedFontColor = GameUiStyle.ownership; disabledFontColor = GameUiStyle.muted
            checkedOverFontColor = GameUiStyle.warning; checkedDownFontColor = GameUiStyle.error
        }
        val colors = foregrounds(incoming).map { it?.cpy() }
        val button = ImageTextButton("Semantic status", incoming)
        button.onActivation {}; button.keyShortcuts.add('s')
        val owner = button.userObject
        val listeners = button.listeners.toList()
        button.isChecked = true; button.isDisabled = true
        repeat(2) { GameUiStyle.applyWidget(button); assertEquals(colors, foregrounds(button.style)) }
        BaseScreen.setSkin(); GameUiStyle.applyWidget(button)
        assertEquals(colors, foregrounds(button.style))
        assertSame(Fonts.font, button.style.font)
        assertSame(image, button.style.imageUp)
        assertSame(owner, button.userObject)
        assertEquals(listeners, button.listeners.toList())
        assertTrue(button.isChecked && button.isDisabled)
        assertTrue(KeyCharAndCode('s') in button.keyShortcuts)
        button.style.checkedDownFontColor.a = 0.2f
        assertEquals(1f, incoming.checkedDownFontColor.a, 0f)
    }

    @Test fun nativeTypedSelectorsUseTheAccessiblePaletteForEveryState() {
        selectSkin("Atlas")
        for (state in com.unciv.dev.AtlasButtonState.entries) {
            val button = com.unciv.dev.AtlasStateButton(state, ImageTextButton.ImageTextButtonStyle().apply {
                font = Fonts.font; fontColor = GameUiStyle.ink; overFontColor = GameUiStyle.ink
                downFontColor = GameUiStyle.ink; focusedFontColor = GameUiStyle.ink
                checkedFontColor = GameUiStyle.ink; checkedOverFontColor = GameUiStyle.ink
                checkedDownFontColor = GameUiStyle.ink
            })
            GameUiStyle.apply(button, primary = true)
            button.setSize(150f, 64f)
            button.validate()
            assertTrue("Controlled-state text must have a real width", button.label.width > 0f)
            assertTrue(button.label.height >= button.label.prefHeight)
            assertEquals(state.name, if (state.disabled) GameUiStyle.muted else GameUiStyle.surface, button.resolvedFontColor())
        }
    }

    @Test fun modernShapesArePackedAsNinePatches() {
        for (shape in listOf("surface", "surface-rim", "surface-shadow", "control-r8", "control-r8-rim", "pill", "pill-rim"))
            assertTrue("Skins/Atlas/$shape must be packed into Skin.atlas", ImageGetter.ninePatchImageExists("Skins/Atlas/$shape"))
        // The original shapes keep serving every other Atlas screen.
        for (shape in listOf("control", "panel", "rim"))
            assertTrue(ImageGetter.ninePatchImageExists("Skins/Atlas/$shape"))
    }

    @Test fun modernSurfacesDrawHalfScaleArtWithTheirOwnInsets() {
        selectSkin("Atlas")
        val card = GameUiStyle.surface("Probe/Card")
        assertTrue(card.minWidth > 0f && card.minHeight > 0f)
        // Tables using a card as background keep a small default inset, not the 12-unit corner.
        for (inset in listOf(card.leftWidth, card.rightWidth, card.topHeight, card.bottomHeight))
            assertEquals(GameUiStyle.Space.m, inset, 0f)
        val control = GameUiStyle.control("Probe/Control", GameUiStyle.raised) as NinePatchDrawable
        assertEquals(GameUiStyle.Radius.control, control.patch.leftWidth, 0.01f)
        assertEquals(GameUiStyle.raised, control.patch.color)
        val pill = GameUiStyle.pill("Probe/Pill") as NinePatchDrawable
        assertEquals(GameUiStyle.raised, pill.patch.color)
        assertEquals(14f, pill.patch.leftWidth, 0.01f)
        for (drawable in listOf(control, pill, GameUiStyle.pill("Probe/Pill", GameUiStyle.accent, GameUiStyle.ink),
            GameUiStyle.control("Probe/Control", GameUiStyle.raised, GameUiStyle.accent)))
            assertTrue(drawable.minWidth > 0f && drawable.minHeight > 0f)
        // Scaling works on copies; the shared cached patch keeps its 2x geometry.
        assertEquals(24f, ImageGetter.getNinePatch("Skins/Atlas/surface").patch.leftWidth, 0f)
        assertEquals(16f, ImageGetter.getNinePatch("Skins/Atlas/control-r8").patch.leftWidth, 0f)
        val dot = GameUiStyle.dot(GameUiStyle.info)
        assertEquals(8f, dot.prefWidth, 0f)
        assertEquals(GameUiStyle.info, dot.color)
    }

    @Test fun modArtAtAnOverridePathKeepsItsAuthoredSize() {
        @Suppress("UNCHECKED_CAST")
        val patches = ImageGetter::class.java.getDeclaredField("ninePatchDrawables").apply { isAccessible = true }
            .get(null) as MutableMap<String, NinePatchDrawable>
        patches["Skins/Atlas/Probe/Override"] = patches.getValue("Skins/Atlas/control")
        SkinCache["Atlas"] = json().fromJson(SkinConfig::class.java,
            """{"fallbackSkin":"Minimal","skinVariants":{"Probe/Variant":{"image":"control"}}}""")
        selectSkin("Atlas")
        fun corner(path: String) = (GameUiStyle.control(path, GameUiStyle.raised) as NinePatchDrawable).patch.leftWidth
        assertEquals(4f, corner("Probe/Override"), 0f)
        assertEquals(4f, corner("Probe/Variant"), 0f)
        assertEquals(GameUiStyle.Radius.control, corner("Probe/Default"), 0f)
    }

    @Test fun roundedShapesNeverDrawOutsideTheirBoundsWhenSmallerThanTheirCorners() {
        selectSkin("Atlas")
        val quads = ArrayList<FloatArray>()
        val batch = Mockito.mock(Batch::class.java)
        Mockito.`when`(batch.color).thenReturn(Color.WHITE)
        Mockito.doAnswer { quads += it.getArgument<FloatArray>(1).copyOf(it.getArgument(3)); null }
            .`when`(batch).draw(Mockito.any(Texture::class.java), Mockito.any(FloatArray::class.java), Mockito.anyInt(), Mockito.anyInt())
        fun extent(drawable: Drawable, width: Float, height: Float): List<Float> {
            quads.clear()
            drawable.draw(batch, 100f, 50f, width, height)
            assertTrue(quads.isNotEmpty())
            val xs = quads.flatMap { v -> (v.indices step 5).map { v[it] } }
            val ys = quads.flatMap { v -> (v.indices step 5).map { v[it + 1] } }
            return listOf(xs.min() - 100f, ys.min() - 50f, xs.max() - 100f, ys.max() - 50f)
        }
        fun assertFills(expected: List<Float>, actual: List<Float>) =
            expected.zip(actual).forEach { (e, a) -> assertEquals("Drawn extent $actual", e, a, 0.01f) }
        // A 6-unit control, a 10-unit pill and a 10-unit flat card are all below their corner sums.
        assertFills(listOf(0f, 0f, 6f, 6f), extent(GameUiStyle.control("Probe/Small", GameUiStyle.raised), 6f, 6f))
        assertFills(listOf(0f, 0f, 40f, 10f), extent(GameUiStyle.pill("Probe/Small"), 40f, 10f))
        assertFills(listOf(0f, 0f, 60f, 44f), extent(GameUiStyle.pill("Probe/Tall", GameUiStyle.raised, GameUiStyle.ink), 60f, 44f))
        assertFills(listOf(0f, 0f, 10f, 10f), extent(GameUiStyle.surface("Probe/Small", elevated = false), 10f, 10f))
        // The halo surrounds an elevated card by its margin, with the card's footprint knocked out.
        assertFills(listOf(-18f, -18f, 118f, 78f), extent(GameUiStyle.surface("Probe/Card"), 100f, 60f))
    }

    @Test fun modernHelpersFallBackToLegacyShapesOutsideAtlas() {
        selectSkin("Minimal")
        val card = GameUiStyle.surface("Probe/Card", 0.9f)
        val reference = GameUiStyle.panelBackground("Probe/Card", 0.9f)
        assertSame(reference.javaClass, card.javaClass)
        assertEquals(listOf(reference.minWidth, reference.minHeight, reference.leftWidth, reference.topHeight),
            listOf(card.minWidth, card.minHeight, card.leftWidth, card.topHeight))
        val legacyShape = BaseScreen.skinStrings.getUiBackground("Probe", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape).patch
        for (drawable in listOf(GameUiStyle.control("Probe/Control", GameUiStyle.raised), GameUiStyle.pill("Probe/Pill"))) {
            val patch = (drawable as NinePatchDrawable).patch
            assertSame(legacyShape.texture, patch.texture)
            assertEquals(legacyShape.totalWidth, patch.totalWidth, 0f)
            assertEquals(GameUiStyle.raised, patch.color)
        }
        val outlined = GameUiStyle.outlinedBackground("Probe/Outlined", GameUiStyle.raised, GameUiStyle.ink)
        assertSame(outlined.javaClass, GameUiStyle.pill("Probe/Pill", GameUiStyle.raised, GameUiStyle.ink).javaClass)
        assertSame(outlined.javaClass, GameUiStyle.control("Probe/Control", GameUiStyle.raised, GameUiStyle.ink).javaClass)
        // Quiet and Ghost are the legacy secondary control on other skins.
        val secondary = GameUiStyle.button("Secondary").style
        for (emphasis in listOf(GameUiStyle.Emphasis.Quiet, GameUiStyle.Emphasis.Ghost)) {
            val style = GameUiStyle.button("Quiet", emphasis).style
            assertEquals(foregrounds(secondary), foregrounds(style))
            assertEquals(secondary.up.minHeight, style.up.minHeight, 0f)
            assertEquals((secondary.up as NinePatchDrawable).patch.color, (style.up as NinePatchDrawable).patch.color)
        }
    }

    @Test fun quietAndGhostRolesSurviveRootPassesAndSkinReload() {
        selectSkin("Atlas")
        for (emphasis in listOf(GameUiStyle.Emphasis.Quiet, GameUiStyle.Emphasis.Ghost)) {
            for (size in listOf(GameUiStyle.ButtonSize.Regular, GameUiStyle.ButtonSize.Compact)) {
                val button = GameUiStyle.button("Activity", emphasis, size)
                button.onActivation {}; button.keyShortcuts.add('a')
                val owner = button.userObject
                val listeners = button.listeners.toList()
                val row = Table().apply { add(button) }
                fun verify() {
                    val style = button.style
                    assertEquals(GameUiStyle.ink, style.fontColor)
                    assertEquals(GameUiStyle.accent, style.checkedFontColor)
                    assertEquals(GameUiStyle.muted, style.disabledFontColor)
                    // No outline: hover is a plain rounded fill, not the secondary rim/fill pair.
                    assertEquals(GameUiStyle.hover, (style.over as NinePatchDrawable).patch.color)
                    if (emphasis == GameUiStyle.Emphasis.Quiet)
                        assertEquals(GameUiStyle.raised, (style.up as NinePatchDrawable).patch.color)
                    else assertFalse("Ghost rests transparent", style.up is NinePatchDrawable)
                    assertNotNull("Keyboard focus stays visible while checked", style.checkedFocused)
                    assertNotNull(style.focused)
                    for (state in backgrounds(style)) assertEquals(size.controlHeight, state.minHeight, 0f)
                    button.pack()
                    assertTrue(button.height >= size.controlHeight)
                    // Compact text stays clear of the rounded corner.
                    assertEquals(maxOf(size.padHorizontal, GameUiStyle.Radius.control), button.padLeft, 0f)
                    assertSame(Fonts.font, style.font)
                    assertSame(owner, button.userObject)
                    assertEquals(listeners, button.listeners.toList())
                    assertTrue(KeyCharAndCode('a') in button.keyShortcuts)
                }
                verify()
                repeat(2) { GameUiStyle.applyWidget(row); verify() }
                BaseScreen.setSkin()
                GameUiStyle.applyWidget(row)
                verify()
                // Re-sizing through the boolean overload keeps the role.
                GameUiStyle.apply(button, size = size)
                verify()
            }
        }
        val promoted = GameUiStyle.button("Resume", GameUiStyle.Emphasis.Quiet)
        GameUiStyle.apply(promoted, primary = true)
        assertEquals(GameUiStyle.accent, (promoted.style.up as NinePatchDrawable).patch.color)
        GameUiStyle.apply(promoted, GameUiStyle.Emphasis.Secondary)
        assertEquals(GameUiStyle.selection, promoted.style.checkedFontColor)
        assertFalse("Secondary keeps its outlined fill", promoted.style.over is NinePatchDrawable)
    }

    @Test fun quietControlsKeepReadableText() {
        selectSkin("Atlas")
        val quiet = GameUiStyle.button("Messages", GameUiStyle.Emphasis.Quiet).style
        val ghost = GameUiStyle.button("Logs", GameUiStyle.Emphasis.Ghost).style
        assertTrue(contrast(quiet.fontColor, GameUiStyle.raised) >= 4.5)
        assertTrue(contrast(quiet.overFontColor, GameUiStyle.hover) >= 4.5)
        assertTrue(contrast(quiet.downFontColor, GameUiStyle.pressed) >= 4.5)
        assertTrue(contrast(ghost.fontColor, GameUiStyle.surface) >= 4.5)
        val checkedFill = composite(GameUiStyle.accent.cpy().apply { a = 0.2f }, GameUiStyle.surface)
        val drawnFill = (quiet.checked as NinePatchDrawable).patch.color
        assertEquals(checkedFill.r, drawnFill.r, 0.002f)
        assertEquals(checkedFill.g, drawnFill.g, 0.002f)
        assertEquals(checkedFill.b, drawnFill.b, 0.002f)
        assertEquals("Checked fill is opaque, so the map never shows through it", 1f, drawnFill.a, 0f)
        assertTrue(contrast(quiet.checkedFontColor, checkedFill) >= 3.0)
        assertTrue(contrast(quiet.checkedOverFontColor, (quiet.checkedOver as NinePatchDrawable).patch.color) >= 3.0)
    }

    private fun backgrounds(style: Button.ButtonStyle) = listOf(style.up, style.over, style.down,
        style.checked, style.focused, style.disabled, style.checkedOver, style.checkedDown)
    private fun foregrounds(style: TextButton.TextButtonStyle) = listOf(style.fontColor, style.overFontColor,
        style.downFontColor, style.checkedFontColor, style.focusedFontColor, style.disabledFontColor,
        style.checkedOverFontColor, style.checkedDownFontColor)

    companion object {
        fun composite(foreground: Color, background: Color) = Color(
            foreground.r * foreground.a + background.r * (1 - foreground.a),
            foreground.g * foreground.a + background.g * (1 - foreground.a),
            foreground.b * foreground.a + background.b * (1 - foreground.a), 1f)
        fun contrast(a: Color, b: Color): Double {
            fun luminance(c: Color): Double {
                fun channel(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
                return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
            }
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
    }
}
