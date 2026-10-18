package com.unciv.ui.components

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.models.skins.SkinCache
import com.unciv.models.skins.SkinStrings
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.tilegroups.citybutton.CityButton
import com.unciv.ui.components.tilegroups.layers.CityButtonMapLayer
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.minimap.MapOverlayToggleButton
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.view.CityView
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Real map annotations: privacy, complete long labels and the existing layer callbacks. */
@RunWith(GdxTestRunner::class)
class MapAnnotationPolishTest {
    private lateinit var priorNames: Map<String, Char>
    private lateinit var priorActors: Map<Char, Actor>

    @Before fun rememberIcons() {
        priorNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        priorActors = FontRulesetIcons.charToRulesetImageActor.toMap()
    }

    @After fun restoreIcons() {
        FontRulesetIcons.rulesetObjectNameToChar.clear()
        FontRulesetIcons.rulesetObjectNameToChar.putAll(priorNames)
        FontRulesetIcons.charToRulesetImageActor.clear()
        FontRulesetIcons.charToRulesetImageActor.putAll(priorActors)
    }

    private class Fixture {
        val game = TestGame()
        val rome: Civilization
        val arabia: Civilization
        val spectator: Civilization
        val romanCity: City
        val foreignCity: City
        val screen = Mockito.mock(WorldScreen::class.java)

        init {
            game.makeHexagonalMap(5, Constants.grassland)
            rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
            arabia = game.addCiv(game.ruleset.nations["Arabia"]!!)
            spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
            romanCity = game.addCity(rome, game.getTile(0, 0), initialPopulation = 6)
            foreignCity = game.addCity(arabia, game.getTile(3, 0), initialPopulation = 7)
            Fonts.fontImplementation = FontDesktop()
            ImageGetter.setNewRuleset(game.ruleset)
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }
                .set(UncivGame.Current, screen)
        }

        fun plaque(city: City, viewer: Civilization): BorderedTable {
            Mockito.`when`(screen.selectedCiv).thenReturn(viewer)
            val view = GameView(game.gameInfo, viewer).getCityView(city)
            // Core is a separate module: preserve the annotation's internal visibility.
            return Class.forName("com.unciv.ui.components.tilegroups.citybutton.CityTable")
                .getConstructor(CityView::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(view, false) as BorderedTable
        }
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }

    @Test fun `human foreign plaques keep private growth and production absent while spectators see them`() {
        val f = Fixture()
        val own = f.plaque(f.romanCity, f.rome)
        val foreign = f.plaque(f.foreignCity, f.rome)
        val spectator = f.plaque(f.foreignCity, f.spectator)

        assertEquals(2, own.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
        assertEquals(0, foreign.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
        assertEquals(2, spectator.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
        assertTrue(foreign.descendants().filterIsInstance<Label>().any { it.text.toString() == "7" })
        assertEquals(Touchable.enabled, foreign.touchable)
    }

    @Test fun `a long city name is clamped to two lines inside the plaque`() {
        val f = Fixture()
        f.romanCity.name = "A very long capital name beside the northern mountains and coastal trade routes " +
            "that an agent chose while renaming every city after a complete regional history"
        val plaque = f.plaque(f.romanCity, f.rome)
        plaque.pack()
        plaque.validate()
        val label = plaque.descendants().filterIsInstance<Label>()
            .single { it.text.toString().startsWith("A very long capital") }
        assertTrue("Long names wrap rather than overrun the map annotation", label.wrap)
        assertTrue("A clamped name shows that it was shortened", label.text.toString().endsWith("..."))
        assertTrue(f.romanCity.name.startsWith(label.text.toString().removeSuffix("...").trimEnd()))
        assertTrue(label.width <= 220.5f)
        val lines = label.glyphLayout.runs.map { it.y }.distinct().size
        assertEquals("Map labels stay at most two lines tall", 2, lines)
        for (corner in listOf(com.badlogic.gdx.math.Vector2(), com.badlogic.gdx.math.Vector2(label.width, label.height))) {
            val point = label.localToAscendantCoordinates(plaque, corner)
            assertTrue(point.x >= 0f && point.x <= plaque.width + 0.5f)
            assertTrue(point.y >= -0.5f && point.y <= plaque.height + 0.5f)
        }
        assertSame(f.rome, f.screen.selectedCiv)
    }

    @Test fun `a moderately long city name wraps without truncation`() {
        val f = Fixture()
        f.romanCity.name = "The Eternal City upon the Seven Hills"
        val plaque = f.plaque(f.romanCity, f.rome)
        plaque.pack()
        assertTrue(plaque.descendants().filterIsInstance<Label>().any { it.text.toString() == f.romanCity.name })
    }

    @Test fun `a two-line city with aircraft and status stays visible at the bottom viewport edge`() {
        val f = Fixture()
        f.romanCity.name = "Rome beside the northern mountains and coastal trade routes"
        f.romanCity.setFlag(CityFlags.WeLoveTheKing, 10)
        // Unit placement explores tiles. Keep that fixture mutation independent of
        // the mocked screen's undo handler, as in ordinary game setup.
        val screenField = UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }
        screenField.set(UncivGame.Current, null)
        try {
            f.game.addUnit("Fighter", f.rome, f.romanCity.getCenterTile())
            f.rome.cache.updateViewableTiles()
        } finally {
            screenField.set(UncivGame.Current, f.screen)
        }
        Mockito.`when`(f.screen.selectedCiv).thenReturn(f.rome)
        val unitTableGetter = f.screen.javaClass.methods.single {
            it.name.substringBefore('$') == "getBottomUnitTable"
        }
        Mockito.`when`(unitTableGetter.invoke(f.screen) as UnitTable?)
            .thenReturn(Mockito.mock(UnitTable::class.java))
        TileSetCache.loadTileSetConfigs()
        val view = GameView(f.game.gameInfo, f.rome)
        val tile = WorldTileGroup(view.getTile(f.romanCity.getCenterTile()), TileSetStrings())
        val button = Mockito.spy(CityButton(view.getForeignCityView(f.romanCity), tile))
        Mockito.doNothing().`when`(button).draw(Mockito.any(Batch::class.java), Mockito.anyFloat())
        val wrapper = Group().apply {
            isTransform = false
            setPosition(400f, -96f)
            addActor(button)
        }
        button.update(true)
        assertTrue("Full city stack must extend into the visible viewport", wrapper.y + button.height > 0f)
        assertTrue(button.descendants().any { it.javaClass.simpleName == "AirUnitTable" })
        assertTrue(button.descendants().any { it.javaClass.simpleName == "StatusTable" && (it as com.badlogic.gdx.scenes.scene2d.ui.Table).cells.size > 0 })
        val area = Rectangle(-75f, -75f, 1150f, 790f)
        val layer = CityButtonMapLayer(1).apply {
            cullingArea = area
            addActor(wrapper)
        }
        val batch = Mockito.mock(Batch::class.java)

        layer.draw(batch, 1f)

        Mockito.verify(button).draw(batch, 1f)
        assertSame(area, layer.cullingArea)
        assertEquals(-96f, wrapper.y, 0f)
        assertEquals(0f, wrapper.height, 0f)
    }

    @Test fun `own cities keep a heavy rim and foreign identity uses the nation's own colour pair`() {
        val f = Fixture()
        val own = f.plaque(f.romanCity, f.rome)
        assertTrue("Own cities need a rim that survives zooming out", own.borderSize >= 3f)
        assertTrue(own.borderOnTop)
        val foreign = f.plaque(f.foreignCity, f.rome)
        assertTrue("Neutral rims stay quiet", foreign.borderSize < 3f)
        val disc = foreign.descendants().filterIsInstance<com.unciv.ui.images.IconCircleGroup>().first()
        val glyph = disc.descendants().filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.Image>().last()
        assertEquals(f.arabia.nation.getInnerColor().toString(), glyph.color.toString())
    }

    @Test fun `atlas map banners are one compact row with the same privacy and rim contract`() {
        val f = Fixture()
        val prior = BaseScreen.skinStrings
        SkinCache.loadSkinConfigs()
        BaseScreen.skinStrings = SkinStrings("Atlas")
        try {
            f.romanCity.name = "A very long capital name beside the northern mountains and coastal trade routes"
            val own = f.plaque(f.romanCity, f.rome)
            val foreign = f.plaque(f.foreignCity, f.rome)
            val spectator = f.plaque(f.foreignCity, f.spectator)
            assertEquals(2, own.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
            assertEquals(0, foreign.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
            assertEquals(2, spectator.descendants().filterIsInstance<ImageGetter.ProgressBar>().count())
            assertTrue("One row, not the stacked plaque: ${own.height}", own.height <= 24.5f)
            val label = own.descendants().filterIsInstance<Label>().single { it.text.startsWith("A very long capital") }
            assertTrue(label.text.toString().endsWith("..."))
            assertTrue(label.width <= 150.5f)
            assertEquals(1, label.glyphLayout.runs.map { it.y }.distinct().size)
            assertTrue("Own banners keep a 2-unit rim", own.borderSize >= 2f && own.borderOnTop)
            assertTrue("Neutral rims stay a hairline", foreign.borderSize < 2f)
            val disc = foreign.descendants().filterIsInstance<com.unciv.ui.images.IconCircleGroup>().first()
            val glyph = disc.descendants().filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.Image>().last()
            assertEquals(f.arabia.nation.getInnerColor().toString(), glyph.color.toString())
        } finally {
            BaseScreen.skinStrings = prior
        }
    }

    @Test fun `layer controls initialize from flags and keep their original toggle callback`() {
        val f = Fixture()
        var flag = true
        var writes = 0
        val button = MapOverlayToggleButton("TileIcons/MapOverlayToggleYields",
            getter = { flag }, setter = { flag = it; writes++ })
        assertTrue(button.isChecked)
        button.toggle()
        assertFalse(flag)
        assertEquals(1, writes)
        Mockito.verify(f.screen).shouldUpdate = true
        button.update()
        assertFalse(button.isChecked)
        flag = true
        button.update()
        assertTrue(button.isChecked)
        assertEquals("Refreshing a control must not write its backing setting", 1, writes)
    }

    @Test fun `long layer tooltips wrap and remain inside a narrow viewport after pointer entry`() {
        Fixture()
        val root = Group()
        val stage = Mockito.mock(Stage::class.java)
        Mockito.`when`(stage.width).thenReturn(420f)
        Mockito.`when`(stage.height).thenReturn(600f)
        Group::class.java.getDeclaredMethod("setStage", Stage::class.java)
            .apply { isAccessible = true }.invoke(root, stage)
        Mockito.doAnswer { root.addActor(it.getArgument(0)); null }.`when`(stage).addActor(Mockito.any(Actor::class.java))
        var writes = 0
        val button = MapOverlayToggleButton("TileIcons/MapOverlayToggleResources",
            getter = { false }, setter = { writes++ })
        button.pack()
        button.setPosition(180f, 40f)
        root.addActor(button)
        val text = "Show resources and improvements with a long translated label that keeps every word visible beside the minimap"
        button.javaClass.methods.single { it.name.substringBefore('$') == "setLayerTooltip" }.invoke(button, text)
        val tooltip = button.listeners.filterIsInstance<UncivTooltip<*>>().single()
        tooltip.enter(InputEvent().apply { listenerActor = button; type = InputEvent.Type.enter }, 1f, 1f, -1, null)
        root.act(0.3f)
        val tip = root.children.single { it !== button } as Group
        val content = tip.children.single()
        for (corner in listOf(com.badlogic.gdx.math.Vector2(), com.badlogic.gdx.math.Vector2(content.width, content.height))) {
            val point = content.localToStageCoordinates(corner)
            assertTrue("Layer tooltip must fit the narrow viewport: $point", point.x >= 0f && point.x <= 420f)
            assertTrue(point.y >= 0f && point.y <= 600f)
        }
        val label = content.descendants().filterIsInstance<Label>().single()
        assertEquals(text, label.text.toString())
        assertTrue(label.wrap)
        assertEquals(0, writes)
        tooltip.hide(true)
    }
}
