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
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.TestGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.tilegroups.citybutton.CityButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.minimap.MinimapHolder
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.mockito.Mockito

/**
 * A nation's view under fog, map-only vision (every tile, units still fogged) and full vision (a spectator
 * lifting fog, or the agent match's Everything setting): the played nation's own city keeps its private
 * details throughout. Real GPU, synthetic game only; no provider, save or session I/O.
 */
object RevealMapRenderCheck {
    private enum class Mode(val file: String, val caption: String) {
        FOGGED("fogged", "Playing Rome · fog of war"),
        MAP("map-only", "Playing Rome · map revealed, units fogged"),
        LIFTED("lifted", "Playing Rome · everything visible");
        val revealsTiles get() = this != FOGGED
        val revealsUnits get() = this == LIFTED
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv reveal map render check")
            setWindowedMode(960, 720)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                try {
                    Gdx.files.absolute(args[0]).mkdirs()
                    for (mode in Mode.entries) capture(960, 720, mode, args[0])
                    println("REVEAL_MAP_RENDER_CHECK_PASSED")
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

    private fun capture(width: Int, height: Int, mode: Mode, output: String) {
        val reveal = mode.revealsTiles
        val game = TestGame()
        game.makeHexagonalMap(7, Constants.grassland)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val arabia = game.addCiv(game.ruleset.nations["Arabia"]!!)
        val capital = game.addCity(rome, game.getTile(-3, -1), initialPopulation = 6).apply { name = "Rome" }
        val foreign = game.addCity(arabia, game.getTile(3, 1), initialPopulation = 5).apply { name = "Damascus" }
        game.addUnit("Warrior", rome, game.getTile(-2, -1))
        val hiddenWarrior = game.addUnit("Warrior", arabia, game.getTile(2, 0))
        hiddenWarrior.getTile().roadStatus = RoadStatus.Road
        game.getTile(2, 1).roadStatus = RoadStatus.Road
        game.gameInfo.currentPlayerCiv = rome
        UncivGame.Current.settings.apply {
            minimapSize = 12; showTileYields = false; showResourcesAndImprovements = false
            showWorkedTiles = false; showUnitMovements = false; showPixelImprovements = true
            tileSet = "HexaRealm"; unitSet = "AbsoluteUnits"
        }
        // Rome has explored only its western half; Arabia's city and warrior sit in unexplored land.
        val known = game.tileMap.values.filter { it.position.x - it.position.y <= 0 }.toSet()
        for (tile in game.tileMap.values) tile.setExplored(rome, tile in known)
        rome.viewableTiles = known
        check(!rome.hasExplored(foreign.getCenterTile()) && hiddenWarrior.getTile() !in rome.viewableTiles) {
            "Fixture must hide Arabia from Rome"
        }
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()

        val stage = UncivStage(ScreenViewport())
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        try {
            stage.viewport.update(width, height, true)
            val screen = Mockito.mock(WorldScreen::class.java)
            val played = GameView(game.gameInfo, rome)
            val rendered = if (reveal) GameView(game.gameInfo, rome, revealMap = true, revealUnits = mode.revealsUnits) else played
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.selectedCiv).thenReturn(rome)
            Mockito.`when`(screen.viewingCiv).thenReturn(rome)
            Mockito.`when`(screen.selectedGameView).thenReturn(played)
            val unitTableGetter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getBottomUnitTable" }
            Mockito.`when`(unitTableGetter.invoke(screen) as UnitTable?).thenReturn(Mockito.mock(UnitTable::class.java))
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
            val holder = WorldMapHolder(screen, game.tileMap)
            holder.javaClass.methods.single { it.name.substringBefore('$') == "addTiles" }.invoke(holder)
            stage.addActor(holder)
            for (tile in holder.tileGroups.values) tile.update(rendered.civView)
            holder.zoom(1.2f)
            holder.layout()
            holder.setCenterPosition(game.getTile(0, 0).position, immediately = true, selectUnit = false)
            holder.clearActions()

            val minimap = MinimapHolder(holder)
            stage.addActor(minimap)
            // Explicit map reveal must work without any spectator civilization in the match.
            minimap.update(rome, reveal)
            minimap.setPosition(stage.width - minimap.width - 12f, 12f)
            stage.addActor(Table().apply {
                background = WorldScreenStyle.background("", WorldScreenStyle.surface)
                add(mode.caption.toLabel(WorldScreenStyle.ink, 14)).pad(8f)
                pack()
                setPosition(12f, stage.height - this.height - 12f)
            })

            val cities = holder.actor!!.descendants().filterIsInstance<CityButton>().toList()
            val own = cities.single { it.foreignCityView.getCity() === capital }
            check(own.descendants().filterIsInstance<ImageGetter.ProgressBar>().count() == 2) {
                "The played nation's own city must keep its production and growth details"
            }
            val other = cities.firstOrNull { it.foreignCityView.getCity() === foreign }
            check((other?.isVisible == true && other.parent?.isVisible == true) == reveal) {
                "Damascus must show only with fog lifted (reveal=$reveal)"
            }
            if (other != null) check(other.descendants().filterIsInstance<ImageGetter.ProgressBar>().none()) {
                "Lifting fog must not expose a foreign city's private production"
            }
            val hiddenTile = holder.tileGroups.values.single { it.tileView.position() == hiddenWarrior.getTile().position }
            check((hiddenTile.tileView.militaryUnit != null) == mode.revealsUnits) { "Arabia's warrior must show only with everything visible ($mode)" }
            check(hiddenTile.isViewable(rendered.civView) == mode.revealsUnits) { "Out-of-sight tiles keep fog shading unless everything is visible ($mode)" }
            val unexplored = hiddenTile.layerOverlay.javaClass.getDeclaredField("unexplored").apply { isAccessible = true }
                .get(hiddenTile.layerOverlay)
            check((unexplored == null) == reveal) { "Map reveal must remove the unexplored mask ($mode)" }
            if (reveal) {
                val roads = hiddenTile.layerFeatures.javaClass.getDeclaredField("roadImages").apply { isAccessible = true }
                    .get(hiddenTile.layerFeatures) as Map<*, *>
                check(roads.values.any { road ->
                    road!!.javaClass.getDeclaredField("image").apply { isAccessible = true }.get(road) != null
                }) { "Map reveal must render roads outside the played nation's real sight ($mode)" }
            }
            check(!rome.hasExplored(hiddenWarrior.getTile())) { "Presentation must not explore terrain for Rome" }
            val minimapTiles = (minimap.minimap.children.first() as Group).children
            val shown = minimapTiles.count { it.isVisible }
            check(if (reveal) shown == game.tileMap.values.count() else shown < game.tileMap.values.count()) {
                "Minimap reveal mismatch: reveal=$reveal shown=$shown"
            }

            while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
            drawCapture(stage, frame, width, height, "$output/reveal-map-${mode.file}.png")
        } finally {
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, null)
            frame.dispose()
            stage.dispose()
        }
    }

    private fun drawCapture(stage: UncivStage, frame: FrameBuffer, width: Int, height: Int, path: String) {
        frame.begin()
        try {
            Gdx.gl.glClearColor(0.07f, 0.1f, 0.11f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
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

    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants)
        if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
}
