package com.unciv.ui.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.HudWindowLayout
import com.unciv.models.skins.SkinCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.ReadingScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.HudWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import java.nio.file.Files

@RunWith(GdxTestRunner::class)
class HudWindowTest {
    private lateinit var prior: SetupUiStateSnapshot
    private val windows = ArrayList<HudWindow>()
    @Before fun setup() { prior = SetupUiStateSnapshot() }
    @After fun restore() { windows.forEach { it.dispose() }; prior.restore() }

    private class Fixture(var width: Float = 1200f, var height: Float = 800f) {
        val test = TestGame()
        val root = Group()
        val stage: UncivStage = Mockito.mock(UncivStage::class.java)
        var shown = 0
        var hidden = 0
        var resized = 0
        var arranged = 0
        init {
            Fonts.fontImplementation = FontDesktop(); ImageGetter.setNewRuleset(test.ruleset)
            SkinCache.loadSkinConfigs(); UncivGame.Current.settings.skin = "Atlas"; BaseScreen.setSkin()
            UncivGame.Current.settings.hudWindows.clear()
            UncivGame.Current.files = UncivFiles(Gdx.files, Files.createTempDirectory("hud-window").toString() + "/")
            Mockito.`when`(stage.width).thenAnswer { width }
            Mockito.`when`(stage.height).thenAnswer { height }
            Mockito.`when`(stage.root).thenReturn(root)
            Group::class.java.getDeclaredMethod("setStage", Stage::class.java).apply { isAccessible = true }.invoke(root, stage)
            Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
        }
    }

    private fun Fixture.window(panel: Table = Table(BaseScreen.skin).apply { background = GameUiStyle.surface("Test/Window") },
                               defaults: Rectangle = Rectangle(20f, 300f, 400f, 450f)): Pair<Table, HudWindow> {
        root.addActor(panel)
        panel.touchable = com.badlogic.gdx.scenes.scene2d.Touchable.enabled
        val header = Table().apply { add("Title".toLabel()).growX() }
        panel.add(header).growX().height(30f).row()
        panel.add().grow()
        lateinit var window: HudWindow
        window = HudWindow(panel, "test", "Test", Vector2(240f, 200f), { Rectangle(defaults) },
            show = { panel.isVisible = true; shown++; window.onShown() },
            hide = { panel.isVisible = false; hidden++; window.onHidden() },
            onResized = { resized++ }, onArranged = { arranged++ })
        window.attachHeader(header)
        window.place()
        panel.validate()
        windows += window
        return panel to window
    }

    private fun drag(listener: InputListener, target: Actor, from: Vector2, to: Vector2, local: Vector2 = Vector2(2f, 2f)) {
        val event = InputEvent().apply { this.target = target; stageX = from.x; stageY = from.y }
        assertTrue(listener.touchDown(event, local.x, local.y, 0, 0))
        event.stageX = to.x; event.stageY = to.y
        listener.touchDragged(event, local.x, local.y, 0)
        listener.touchUp(event, local.x, local.y, 0, 0)
    }

    private fun Table.header() = children.filterIsInstance<Table>().first()
    private fun Table.headerDrag() = header().listeners.filterIsInstance<InputListener>().single()
    private fun Table.resizeFrame() = children.single { it.javaClass.simpleName == "ResizeFrame" }

    @Test fun `unplaced windows use their default spot`() {
        val f = Fixture()
        val (panel, window) = f.window()
        assertFalse(window.isPlaced)
        assertEquals(Rectangle(20f, 300f, 400f, 450f), Rectangle(panel.x, panel.y, panel.width, panel.height))
    }

    @Test fun `dragging the header moves, snaps to the margin and remembers the spot`() {
        val f = Fixture()
        val (panel, window) = f.window()
        // 20 → 15 is within snapping reach of the 8-unit screen margin.
        drag(panel.headerDrag(), panel.header(), Vector2(100f, 600f), Vector2(95f, 500f))
        assertEquals(HudWindow.margin, panel.x, 0.01f)
        assertEquals(200f, panel.y, 0.01f)
        assertTrue(window.isPlaced)
        assertEquals(1, f.arranged)
        // The saved spot survives a different screen size, measured from its nearer edges:
        // left, and top (its middle sits in the upper half).
        val saved = UncivGame.Current.settings.hudWindows.getValue("test")
        assertFalse(saved.fromRight)
        assertTrue(saved.fromTop)
        f.width = 900f; f.height = 700f
        window.place()
        assertEquals(HudWindow.margin, panel.x, 0.01f)
        assertEquals(800f - 200f - 450f, f.height - panel.y - panel.height, 0.01f)
    }

    @Test fun `windows near the right and top keep those edges when the screen resizes`() {
        val f = Fixture()
        val (panel, window) = f.window(defaults = Rectangle(700f, 300f, 400f, 450f))
        drag(panel.headerDrag(), panel.header(), Vector2(800f, 700f), Vector2(850f, 720f))
        val saved = UncivGame.Current.settings.hudWindows.getValue("test")
        assertTrue(saved.fromRight && saved.fromTop)
        val right = f.width - (panel.x + panel.width)
        val top = f.height - (panel.y + panel.height)
        f.width = 1000f; f.height = 760f
        window.place()
        assertEquals(right, f.width - (panel.x + panel.width), 0.01f)
        assertEquals(top, f.height - (panel.y + panel.height), 0.01f)
    }

    @Test fun `windows always fit the stage, however small it becomes`() {
        val f = Fixture()
        val (panel, window) = f.window()
        drag(panel.headerDrag(), panel.header(), Vector2(100f, 600f), Vector2(700f, 650f))
        for ((width, height) in listOf(500f to 400f, 300f to 260f, 1200f to 800f)) {
            f.width = width; f.height = height
            window.place()
            assertTrue("$width x $height: $panel", panel.x >= 0f && panel.y >= 0f)
            assertTrue(panel.x + panel.width <= width + 0.01f && panel.y + panel.height <= height + 0.01f)
        }
    }

    @Test fun `edges resize within the minimum size and only inside the panel`() {
        val f = Fixture()
        val (panel, window) = f.window()
        val frame = panel.resizeFrame()
        assertNull("Outside the panel belongs to the map", panel.hit(-2f, panel.height / 2, true))
        assertSame(frame, panel.hit(2f, panel.height / 2, true))
        assertNotNull(panel.hit(panel.width / 2, panel.height / 2, true))
        assertFalse(panel.hit(panel.width / 2, panel.height / 2, true) === frame)
        val listener = frame.listeners.filterIsInstance<InputListener>().single()
        // Right edge: 400 → 520 wide.
        drag(listener, frame, Vector2(420f, 500f), Vector2(540f, 500f), Vector2(panel.width - 2f, panel.height / 2))
        assertEquals(520f, panel.width, 0.01f)
        assertEquals(20f, panel.x, 0.01f)
        assertEquals(1, f.resized)
        // Bottom-left corner pulled past the minimum stops at it, keeping the opposite corner.
        val top = panel.y + panel.height
        val right = panel.x + panel.width
        drag(listener, frame, Vector2(20f, 300f), Vector2(1000f, 1000f), Vector2(2f, 2f))
        assertEquals(240f, panel.width, 0.01f)
        assertEquals(200f, panel.height, 0.01f)
        assertEquals(right, panel.x + panel.width, 0.01f)
        assertEquals(top, panel.y + panel.height, 0.01f)
        assertTrue(window.isPlaced)
    }

    @Test fun `double clicking the header returns to the default spot`() {
        val f = Fixture()
        val (panel, window) = f.window()
        drag(panel.headerDrag(), panel.header(), Vector2(100f, 600f), Vector2(400f, 500f))
        assertTrue(window.isPlaced)
        val event = InputEvent().apply { target = panel.header(); stageX = 0f; stageY = 0f }
        val listener = panel.headerDrag()
        listener.touchDown(event, 2f, 2f, 0, 0); listener.touchUp(event, 2f, 2f, 0, 0)
        listener.touchDown(event, 2f, 2f, 0, 0)
        assertFalse(window.isPlaced)
        assertEquals(20f, panel.x, 0.01f)
    }

    @Test fun `minimize collapses to a pill that restores the window`() {
        val f = Fixture()
        val (panel, window) = f.window()
        window.minimize()
        assertTrue(window.isMinimized)
        assertFalse(panel.isVisible)
        val pill = f.root.children.single { it.name == "HudWindowPill/test" } as Table
        assertTrue("The pill sits where the window's top edge was", pill.y + pill.height <= panel.y + panel.height + 0.01f)
        assertTrue(UncivGame.Current.settings.hudWindows.getValue("test").minimized)
        val listener = pill.listeners.filterIsInstance<InputListener>().single()
        val event = InputEvent().apply { target = pill; stageX = pill.x; stageY = pill.y }
        listener.touchDown(event, 1f, 1f, 0, 0); listener.touchUp(event, 1f, 1f, 0, 0)
        assertFalse(window.isMinimized)
        assertTrue(panel.isVisible)
        assertNull(pill.parent)
        assertFalse(UncivGame.Current.settings.hudWindows.getValue("test").minimized)
    }

    @Test fun `opacity fades only the frosted surface and stays within range`() {
        val f = Fixture()
        UncivGame.Current.settings.hudWindows["test"] = HudWindowLayout().apply { opacity = 0.2f }
        val (_, window) = f.window()
        assertEquals(0.2f, window.opacity, 0.001f)
        assertTrue(GameUiStyle.Glass.tint(0.2f) < GameUiStyle.Glass.tint(HudWindowLayout.DEFAULT_OPACITY))
        assertEquals(1f, GameUiStyle.Glass.tint(1f), 0.001f)
        assertTrue(GameUiStyle.Glass.fallbackAlpha(HudWindowLayout.MIN_OPACITY) >= 0.5f)
        assertEquals(1f, GameUiStyle.Glass.fallbackAlpha(1f), 0.001f)
    }

    @Test fun `reset forgets every arrangement`() {
        val f = Fixture()
        val (panel, window) = f.window()
        drag(panel.headerDrag(), panel.header(), Vector2(100f, 600f), Vector2(400f, 500f))
        HudWindow.resetAll()
        assertFalse(window.isPlaced)
        assertEquals(20f, panel.x, 0.01f)
    }

    @Test fun `wheel notches move a few lines whatever the history length`() {
        Fixture()
        for (contentHeight in listOf(2_000f, 200_000f)) {
            val content = Table().apply { add().size(200f, contentHeight) }
            val pane = ReadingScrollPane(content)
            pane.setSize(200f, 400f); pane.layout()
            pane.scrollY = 1000f; pane.updateVisualScroll()
            pane.fire(InputEvent().apply { type = InputEvent.Type.scrolled; scrollAmountY = 1f })
            assertEquals(ReadingScrollPane.step(1f, ReadingScrollPane.baseLineHeight, true), pane.scrollY - 1000f, 0.01f)
        }
    }

    @Test fun `whole values inside a precise trackpad stream stay precise`() {
        // Ten seconds ago, so the stream state this leaves behind is long over for other tests.
        val start = System.nanoTime() - 10_000_000_000L
        assertTrue("A lone whole value is a wheel notch", ReadingScrollPane.classify(1f, start))
        assertFalse(ReadingScrollPane.classify(0.4f, start + 1_000_000))
        assertFalse("A 30-point flick reports 3.0 mid-stream", ReadingScrollPane.classify(3f, start + 20_000_000))
        assertTrue("After the stream ends a whole value is a notch again", ReadingScrollPane.classify(1f, start + 2_000_000_000L))
        // Precise steps never exceed the notch distance per unit.
        assertTrue(ReadingScrollPane.step(1f, 20f, false) <= ReadingScrollPane.step(1f, 20f, true))
    }
}
