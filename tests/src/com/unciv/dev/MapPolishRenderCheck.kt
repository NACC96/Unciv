package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.MapShape
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.TestGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.UncivTooltip
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.tilegroups.citybutton.CityButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.minimap.Minimap
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.mockito.Mockito

/** Real pixel-art map, city annotations and minimap on a GPU, with synthetic game/settings only. */
object MapPolishRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv map polish render check")
            setWindowedMode(960, 720)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(System.getProperty("unciv.renderCheck.visible") == "true")
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                try {
                    Gdx.files.absolute(args[0]).mkdirs()
                    for ((width, height) in listOf(960 to 720, 420 to 600)) {
                        capture(width, height, spectator = true, worldWrap = false, output = args[0])
                        capture(width, height, spectator = false, worldWrap = false, output = args[0])
                    }
                    capture(960, 720, spectator = true, worldWrap = true, output = args[0])
                    println("MAP_POLISH_RENDER_CHECK_PASSED: real GPU, 960x720 and 420x600, " +
                        "HexaRealm/AbsoluteUnits map, city privacy, complete long names, " +
                        "aircraft/status/defence, minimap controls, wrap camera outlines, stable camera rebuild")
                    // Captures render synchronously on the current context so hidden macOS windows
                    // never depend on GLFW polling to produce a frame.
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(0)
                    Gdx.app.exit()
                } catch (ex: Throwable) {
                    failure = ex
                    ex.printStackTrace()
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") System.exit(1)
                    Gdx.app.exit()
                }
            }
        }, config)
        failure?.let { kotlin.system.exitProcess(1) }
    }

    private fun capture(width: Int, height: Int, spectator: Boolean, worldWrap: Boolean, output: String) {
        val game = TestGame()
        if (worldWrap) {
            game.tileMap.mapParameters.worldWrap = true
            game.makeRectangularMap(14, 22, Constants.grassland)
            game.tileMap.mapParameters.shape = MapShape.rectangular
            game.tileMap.mapParameters.worldWrap = true
        } else game.makeHexagonalMap(8, Constants.grassland)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val arabia = game.addCiv(game.ruleset.nations["Arabia"]!!)
        val observer = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
        val capital = game.addCity(rome, game.getTile(-2, -2), initialPopulation = 12).apply {
            name = "Rome beside the northern mountains and coastal trade routes"
            population.foodStored = 18
        }
        val foreign = game.addCity(arabia, game.getTile(2, 2), initialPopulation = 8).apply {
            name = "Damascus"
            isPuppet = true
        }
        game.addUnit("Fighter", rome, capital.getCenterTile())
        game.addUnit("Bomber", rome, capital.getCenterTile())
        game.addUnit("Warrior", arabia, foreign.getCenterTile())
        game.gameInfo.currentPlayerCiv = rome
        val viewer = if (spectator) observer else rome
        UncivGame.Current.settings.apply {
            minimapSize = 12
            showTileYields = true
            showResourcesAndImprovements = false
            showWorkedTiles = false
            showUnitMovements = true
            showPixelImprovements = true
            tileSet = "HexaRealm"
            unitSet = "AbsoluteUnits"
        }
        // Keep one unexplored tile in the human fixture to verify minimap privacy explicitly.
        val unseen = game.tileMap.values.last()
        // City founding/unit placement may already have explored the selected tile. Set both
        // states explicitly rather than assuming that omitting a reveal makes it unexplored.
        for (tile in game.tileMap.values) tile.setExplored(rome, tile !== unseen)
        rome.viewableTiles = game.tileMap.values.filter { it !== unseen }.toSet()
        // A synthetic spectator has no turn/update loop to populate active vision. Unit
        // visibility reads that cache even though terrain/city information is spectator-wide.
        observer.cache.updateViewableTiles()
        check(capital.getCenterTile().airUnits.size == 2) { "Fixture aircraft left the capital tile" }
        check(capital.getCenterTile() in observer.viewableTiles) { "Fixture spectator vision was not initialized" }
        check(game.tileMap.values.count { !rome.hasExplored(it) } == 1) { "Fixture must retain exactly one unexplored human tile" }
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()

        val stage = UncivStage(ScreenViewport())
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        try {
            stage.viewport.update(width, height, true)
            val screen = Mockito.mock(WorldScreen::class.java)
            val view = GameView(game.gameInfo, viewer, spectatorMode = spectator)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.selectedCiv).thenReturn(viewer)
            Mockito.`when`(screen.viewingCiv).thenReturn(viewer)
            Mockito.`when`(screen.selectedGameView).thenReturn(view)
            val unitTableGetter = screen.javaClass.methods.single {
                it.name.substringBefore('$') == "getBottomUnitTable"
            }
            Mockito.`when`(unitTableGetter.invoke(screen) as UnitTable?).thenReturn(Mockito.mock(UnitTable::class.java))
            setFixtureWorldScreen(screen)
            val holder = WorldMapHolder(screen, game.tileMap)
            holder.callInternal("addTiles")
            stage.addActor(holder)
            for (tile in holder.tileGroups.values) tile.update(view.civView)
            holder.zoom(1.3f)
            holder.layout()
            holder.setCenterPosition(capital.location, immediately = true, selectUnit = false)
            holder.clearActions()

            val minimap = MinimapHolder(holder)
            stage.addActor(minimap)
            minimap.update(viewer)
            minimap.setPosition(stage.width - minimap.width - 12f, 12f)
            check(minimap.x >= 0f && minimap.y + minimap.height <= stage.height) { "Minimap/control stack falls outside $width x $height" }
            stage.addActor(Table().apply {
                background = WorldScreenStyle.background("", WorldScreenStyle.surface)
                add((if (spectator) "Spectator map" else "Human map · foreign production remains private")
                    .toLabel(WorldScreenStyle.ink, 14)).pad(8f)
                pack()
                setPosition(12f, stage.height - this.height - 12f)
            })
            checkCityPrivacy(holder, capital, foreign, spectator)
            checkMinimap(minimap.minimap, viewer, game.tileMap.values.count(), spectator, worldWrap)

            // Ignore atlas/font setup errors, but require the actual capture draw to be clean.
            while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
            val kind = if (spectator) "spectator" else "human"
            val wrap = if (worldWrap) "-wrap" else ""
            drawCapture(stage, frame, width, height, "$output/map-polish-$kind$wrap-$width.png")

            if (width == 420 && spectator) {
                val button = minimap.resourceImageButton
                val before = stage.root.children.toList()
                val tooltip = button.listeners.filterIsInstance<UncivTooltip<*>>().single()
                tooltip.enter(InputEvent().apply { listenerActor = button; type = InputEvent.Type.enter },
                    1f, 1f, -1, null)
                // Exercise the actual pointer-enter placement, then finish its existing fade.
                stage.root.act(0.3f)
                val tip = stage.root.children.single { it !in before } as Group
                val content = tip.children.single()
                drawCapture(stage, frame, width, height, "$output/map-polish-tooltip-$width.png")
                val points = listOf(Vector2(), Vector2(content.width, 0f),
                    Vector2(0f, content.height), Vector2(content.width, content.height))
                    .map { content.localToStageCoordinates(it) }
                val bounds = Rectangle(points.minOf { it.x }, points.minOf { it.y },
                    points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
                println("MAP_TOOLTIP_BOUNDS $width x $height: $bounds")
                check(bounds.x >= -0.5f && bounds.y >= -0.5f &&
                    bounds.x + bounds.width <= stage.width + 0.5f && bounds.y + bounds.height <= stage.height + 0.5f) {
                    "Layer tooltip clips outside the $width x $height viewport: $bounds"
                }
                tooltip.hide(true)
            }

            val expected = minimap.minimap.cameraBounds()
            val replacement = Minimap(holder, 12, if (spectator) null else viewer)
            check(expected == replacement.cameraBounds()) { "A restyled/rebuilt minimap changed the camera bounds" }
            for (button in minimap.buttons) {
                val point = button.localToAscendantCoordinates(minimap, Vector2())
                check(point.x >= 0f && point.y >= 0f && point.x + button.width <= minimap.width + 0.5f &&
                    point.y + button.height <= minimap.height + 0.5f) { "Layer control exceeds minimap bounds" }
                check(button.listeners.any { it is com.unciv.ui.components.UncivTooltip<*> }) { "Layer control has no label" }
            }
        } finally {
            setFixtureWorldScreen(null)
            frame.dispose()
            stage.dispose()
        }
    }

    private fun setFixtureWorldScreen(screen: WorldScreen?) {
        // Fixtures are compiled separately; keep the production setter private.
        UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }
            .set(UncivGame.Current, screen)
    }

    private fun drawCapture(stage: UncivStage, frame: FrameBuffer, width: Int, height: Int, path: String) {
        frame.begin()
        try {
            Gdx.gl.glClearColor(0.07f, 0.1f, 0.11f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            // UncivStage's public act/draw intentionally swallow game crashes. The fixture
            // must propagate them before it can write a PNG or report successful rendering.
            stage.root.act(0f)
            stage.camera.update()
            stage.batch.projectionMatrix = stage.camera.combined
            stage.batch.begin()
            try { stage.root.draw(stage.batch, 1f) }
            finally { stage.batch.end() }
            check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Map capture leaked a GL error" }
            val pixels = Pixmap.createFromFrameBuffer(0, 0, width, height)
            try { PixmapIO.writePNG(Gdx.files.absolute(path), pixels, -1, true) }
            finally { pixels.dispose() }
        } finally { frame.end() }
    }

    private fun checkCityPrivacy(holder: WorldMapHolder, capital: City, foreign: City, spectator: Boolean) {
        val cities = holder.actor!!.descendants().filterIsInstance<CityButton>().toList()
        val own = cities.single { it.foreignCityView.getCity() === capital }
        val other = cities.single { it.foreignCityView.getCity() === foreign }
        check(own.descendants().filterIsInstance<ImageGetter.ProgressBar>().count() == 2)
        check(other.descendants().filterIsInstance<ImageGetter.ProgressBar>().count() == if (spectator) 2 else 0)
        // Long names wrap to at most two lines; a clamped name keeps its prefix and ends with "...".
        check(own.descendants().filterIsInstance<Label>().any {
            val text = it.text.toString()
            it.wrap && (text == capital.name || text.endsWith("...") && capital.name.startsWith(text.removeSuffix("...").trimEnd()))
        })
        check(own.descendants().any { it.javaClass.simpleName == "AirUnitTable" }) { "Visible capital aircraft badge is absent" }
        check(other.descendants().any { it.javaClass.simpleName == "StatusTable" && (it as Table).cells.size > 0 })
    }

    private fun checkMinimap(minimap: Minimap, viewer: Civilization, tileCount: Int, spectator: Boolean, worldWrap: Boolean) {
        minimap.update(viewer)
        val tileLayer = minimap.children.first() as Group
        val visibleCount = tileLayer.children.count { it.isVisible }
        val expected = if (spectator) tileCount else tileCount - 1
        println("MAP_REVEAL_COUNT spectator=$spectator expected=$expected visible=$visibleCount included=${tileLayer.children.size}")
        check(visibleCount == expected) {
            "Minimap styling changed revealed-tile authority: expected=$expected visible=$visibleCount included=${tileLayer.children.size}"
        }
        val cameraLayer = minimap.children.peek() as Group
        check(cameraLayer.children.size == if (worldWrap) 3 else 1)
        check(cameraLayer.children.all { it.width > 0f && it.height > 0f })
    }

    private fun Minimap.cameraBounds(): List<Rectangle> = (children.peek() as Group).children.map {
        Rectangle(it.x, it.y, it.width, it.height)
    }

    private fun WorldMapHolder.callInternal(name: String) {
        javaClass.methods.single { it.name.substringBefore('$') == name }.invoke(this)
    }

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
}
