package com.unciv.ui.components

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.math.Rectangle
import com.unciv.dev.FontDesktop
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.fonts.FontRulesetIcons
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.minimap.Minimap
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.junit.Assert
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@RunWith(GdxTestRunner::class)
class WorldMapSnapshotLayoutTest {
    private lateinit var priorIconNames: Map<String, Char>
    private lateinit var priorIconActors: Map<Char, Actor>

    @Before
    fun rememberFontIcons() {
        priorIconNames = FontRulesetIcons.rulesetObjectNameToChar.toMap()
        priorIconActors = FontRulesetIcons.charToRulesetImageActor.toMap()
    }

    @After
    fun restoreFontIcons() {
        FontRulesetIcons.rulesetObjectNameToChar.clear()
        FontRulesetIcons.rulesetObjectNameToChar.putAll(priorIconNames)
        FontRulesetIcons.charToRulesetImageActor.clear()
        FontRulesetIcons.charToRulesetImageActor.putAll(priorIconActors)
    }

    // Core is a separate Gradle module; call its internal layout operations
    // without making a production API public solely for this test.
    private fun WorldMapHolder.callInternal(name: String, vararg arguments: Any) {
        javaClass.methods.single { it.name.substringBefore('$') == name }.invoke(this, *arguments)
    }

    private fun createHolder(): WorldMapHolder {
        val game = TestGame()
        game.makeHexagonalMap(4)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()

        val stage = Mockito.mock(Stage::class.java)
        Mockito.`when`(stage.width).thenReturn(800f)
        Mockito.`when`(stage.height).thenReturn(600f)
        val screen = Mockito.mock(WorldScreen::class.java)
        Mockito.`when`(screen.stage).thenReturn(stage)
        Mockito.`when`(screen.game).thenReturn(com.unciv.UncivGame.Current)
        Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
        Mockito.`when`(screen.selectedGameView).thenReturn(GameView(game.gameInfo, rome))
        val holder = WorldMapHolder(screen, game.tileMap)
        holder.callInternal("addTiles")
        return holder
    }

    @Test
    fun `snapshot rebuild preserves the visible viewport while zoomed in`() {
        val holder = createHolder()
        holder.zoom(1.6f)
        holder.layout()
        holder.scrollTo(150f, 120f, immediately = true)
        val beforeX = holder.actor!!.x
        val beforeY = holder.actor!!.y

        // Replacing groups takes the same path as installing an isolated game snapshot.
        val tileMap = holder.javaClass.getDeclaredField("tileMap").apply { isAccessible = true }.get(holder)
        holder.callInternal("rebindToSnapshot", tileMap)

        Assert.assertEquals("Map must still cover the window at the retained zoom", 800f, holder.width * holder.scaleX, 0.01f)
        Assert.assertEquals(600f, holder.height * holder.scaleY, 0.01f)
        Assert.assertEquals("Map origin must not jump after a committed action", beforeX, holder.actor!!.x, 0.01f)
        Assert.assertEquals(beforeY, holder.actor!!.y, 0.01f)
        Assert.assertEquals(150f, holder.scrollX, 0.01f)
        Assert.assertEquals(120f, holder.scrollY, 0.01f)
    }

    private fun Minimap.cameraBounds(): Rectangle {
        val cameraLayer = children.peek() as Group
        val camera = cameraLayer.children.first()
        return Rectangle(camera.x, camera.y, camera.width, camera.height)
    }

    @Test
    fun `a rebuilt minimap immediately shows the current camera viewport`() {
        val holder = createHolder()
        holder.zoom(1.6f)
        holder.layout()
        holder.scrollTo(150f, 120f, immediately = true)
        val first = Minimap(holder, 12, null)
        holder.onViewportChanged()
        val expected = first.cameraBounds()

        val replacement = Minimap(holder, 12, null)

        Assert.assertEquals("A new minimap must not wait for user panning to place its camera outline", expected, replacement.cameraBounds())
    }
}
