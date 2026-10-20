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
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.Nuke
import com.unciv.logic.presentation.VisualBatch
import com.unciv.logic.presentation.VisualCapture
import com.unciv.logic.presentation.VisualEvent
import com.unciv.logic.presentation.VisualEventId
import com.unciv.logic.presentation.VisualJournal
import com.unciv.logic.presentation.VisualPosition
import com.unciv.logic.presentation.VisualProvenance
import com.unciv.logic.presentation.VisualUnitDescriptor
import com.unciv.logic.presentation.VisualViewKey
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.presentation.NuclearBlast
import com.unciv.ui.screens.worldscreen.presentation.WorldVisualTimeline
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.ui.screens.worldscreen.unit.UnitTable
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import com.unciv.view.VisualEventProjector
import org.mockito.Mockito

/**
 * Frames of a nuclear detonation drawn over a real world map, at fixed moments of its playback,
 * plus the reduced-motion version. Real GPU, synthetic game only; no save, session or provider I/O.
 */
object NuclearStrikeRenderCheck {
    private val moments = listOf(.18f, .27f, .34f, .45f, .6f, .8f, .95f)

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv nuclear strike render check")
            setWindowedMode(900, 640)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            override fun create() {
                try {
                    Gdx.files.absolute(args[0]).mkdirs()
                    captureBlast(900, 640, args[0])
                    captureTimeline(900, 640, args[0])
                    println("NUCLEAR_STRIKE_RENDER_CHECK_PASSED")
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

    private fun captureBlast(width: Int, height: Int, output: String) {
        val game = TestGame()
        game.makeHexagonalMap(7, Constants.grassland)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!)
        game.addCity(rome, game.getTile(-5, 0), initialPopulation = 4).apply { name = "Rome" }
        game.addCity(greece, game.getTile(1, 1), initialPopulation = 6).apply { name = "Athens" }
        game.addUnit("Warrior", greece, game.getTile(0, 0))
        game.gameInfo.currentPlayerCiv = rome
        UncivGame.Current.settings.apply {
            minimapSize = 12; showTileYields = false; showResourcesAndImprovements = false
            showWorkedTiles = false; showUnitMovements = false; showPixelImprovements = true
            tileSet = "HexaRealm"; unitSet = "AbsoluteUnits"
        }
        for (tile in game.tileMap.values) tile.setExplored(rome, true)
        rome.viewableTiles = game.tileMap.values.toSet()
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()

        val stage = UncivStage(ScreenViewport())
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        try {
            stage.viewport.update(width, height, true)
            val screen = Mockito.mock(WorldScreen::class.java)
            val played = GameView(game.gameInfo, rome)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.selectedCiv).thenReturn(rome)
            Mockito.`when`(screen.viewingCiv).thenReturn(rome)
            Mockito.`when`(screen.selectedGameView).thenReturn(played)
            Mockito.`when`(screen.getGameViewConsideringForOfWar()).thenReturn(played)
            val unitTableGetter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getBottomUnitTable" }
            Mockito.`when`(unitTableGetter.invoke(screen) as UnitTable?).thenReturn(Mockito.mock(UnitTable::class.java))
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
            val holder = WorldMapHolder(screen, game.tileMap)
            holder.javaClass.methods.single { it.name.substringBefore('$') == "addTiles" }.invoke(holder)
            stage.addActor(holder)
            for (tile in holder.tileGroups.values) tile.update(played.civView)
            holder.zoom(1f)
            holder.layout()
            holder.setCenterPosition(game.getTile(0, 0).position, immediately = true, selectUnit = false)
            holder.clearActions()

            val groundZero = VisualPosition(0, 0)
            val point = holder.visualPoint(groundZero) ?: error("Ground zero must be drawable")
            for (reduced in listOf(false, true)) {
                val blast = NuclearBlast(blastRadius = 2, reduced = reduced, dropIn = true)
                holder.addVisualActor(blast)
                val shown = if (reduced) listOf(.5f) else moments
                for (moment in shown) {
                    blast.update(moment, Vector2(point))
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    val name = if (reduced) "reduced" else "t" + (moment * 100).toInt().toString().padStart(2, '0')
                    drawCapture(stage, frame, width, height, "$output/nuke-$name.png")
                }
                blast.close()
            }
            check(holder.visualShake.isZero) { "A blast alone must never shake the map" }
        } finally {
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, null)
            frame.dispose()
            stage.dispose()
        }
    }

    /** A real Atomic Bomb strike on Athens, captured from the engine and played by the world's timeline. */
    private fun captureTimeline(width: Int, height: Int, output: String) {
        val game = TestGame()
        game.makeHexagonalMap(7, Constants.grassland)
        val rome = game.addCiv(game.ruleset.nations["Rome"]!!, isPlayer = true)
        val greece = game.addCiv(game.ruleset.nations["Greece"]!!)
        game.addCity(rome, game.getTile(-5, 0), initialPopulation = 4).apply { name = "Rome" }
        val bomb = game.addUnit("Atomic Bomb", rome, game.getTile(-5, 0))
        game.addCity(greece, game.getTile(1, 1), initialPopulation = 6).apply { name = "Athens" }
        game.addUnit("Warrior", greece, game.getTile(0, 0)).health = 30
        game.addUnit("Spearman", greece, game.getTile(-1, 0))
        game.gameInfo.currentPlayerCiv = rome
        UncivGame.Current.settings.apply {
            minimapSize = 12; showTileYields = false; showResourcesAndImprovements = false
            showWorkedTiles = false; showUnitMovements = false; showPixelImprovements = true
            tileSet = "AtlasTerrain"; unitSet = "AtlasUnits"; skin = "Atlas"
            unitAnimationMode = "Normal"; followVisibleActions = false
        }
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        SkinCache.loadSkinConfigs()
        BaseScreen.setSkin()
        TileSetCache.loadTileSetConfigs()

        val view = VisualViewKey(rome.civID, false, 1L, revealMap = true, revealUnits = true)
        val journal = VisualJournal(game.gameInfo.gameId).apply { reset(view) }
        VisualCapture.install(game.gameInfo, VisualCapture.Session(view, VisualEventProjector, journal))
        Nuke.NUKE(MapUnitCombatant(bomb), game.getTile(0, 0))
        VisualCapture.install(game.gameInfo, null)
        val strike = journal.readAfter(0L, view).events.single() as VisualEvent.NuclearStrike
        check(strike.parts.any { it is VisualEvent.RemoveUnit && it.unit.artName == "Warrior" }) { "The warrior must die" }
        for (tile in game.tileMap.values) tile.setExplored(rome, true)
        rome.viewableTiles = game.tileMap.values.toSet()
        // A busy turn: far more routine movement than the screen plays before catching up, then the strike.
        val scout = VisualUnitDescriptor(9999, "Scout", rome.civID)
        val routine = (1L..40L).map {
            VisualEvent.Move(VisualEventId(game.gameInfo.gameId, journal.timelineId, it), view, VisualProvenance(), scout,
                listOf(VisualPosition(-3, 0), VisualPosition(-2, 0), VisualPosition(-1, 0)))
        }
        val lateStrike = VisualEvent.NuclearStrike(VisualEventId(game.gameInfo.gameId, journal.timelineId, 41L), view,
            strike.provenance, strike.source, strike.target, strike.weapon, strike.blastRadius, strike.intercepted, strike.parts)
        val busyTurn = VisualBatch(view, routine + lateStrike, 41L, 0L)

        val stage = UncivStage(ScreenViewport())
        val frame = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        var timeline: WorldVisualTimeline? = null
        try {
            stage.viewport.update(width, height, true)
            val screen = Mockito.mock(WorldScreen::class.java)
            val played = GameView(game.gameInfo, rome)
            Mockito.`when`(screen.stage).thenReturn(stage)
            Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
            Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
            Mockito.`when`(screen.selectedCiv).thenReturn(rome)
            Mockito.`when`(screen.viewingCiv).thenReturn(rome)
            Mockito.`when`(screen.selectedGameView).thenReturn(played)
            Mockito.`when`(screen.getGameViewConsideringForOfWar()).thenReturn(played)
            Mockito.`when`(screen.currentVisualViewKey()).thenReturn(view)
            Mockito.`when`(screen.isVisualTransportAvailable()).thenReturn(true)
            Mockito.`when`(screen.takeVisualBatch()).thenReturn(busyTurn, null)
            fun getter(name: String) = screen.javaClass.methods.single { it.name.substringBefore('$') == name }
            Mockito.`when`(getter("getBottomUnitTable").invoke(screen) as UnitTable?).thenReturn(Mockito.mock(UnitTable::class.java))
            Mockito.`when`(getter("getTopBar").invoke(screen) as WorldScreenTopBar?).thenReturn(Mockito.mock(WorldScreenTopBar::class.java))
            UncivGame::class.java.getDeclaredField("worldScreen").apply { isAccessible = true }.set(UncivGame.Current, screen)
            val holder = WorldMapHolder(screen, game.tileMap)
            Mockito.`when`(screen.mapHolder).thenReturn(holder)
            holder.javaClass.methods.single { it.name.substringBefore('$') == "addTiles" }.invoke(holder)
            stage.addActor(holder)
            for (tile in holder.tileGroups.values) tile.update(played.civView)
            holder.rebuildVisualUnitIndex()
            holder.zoom(1f)
            holder.layout()
            holder.setCenterPosition(game.getTile(-1, 0).position, immediately = true, selectUnit = false)
            holder.clearActions()

            timeline = WorldVisualTimeline(screen)
            val step = 1 / 60f
            val captures = ArrayDeque(listOf(.3f, .66f, .78f, .95f, 1.2f, 1.6f, 2.2f))
            var time = 0f
            var shook = false
            while (time < 2.6f) {
                timeline.advance(step)
                time += step
                if (time <= step) check(timeline.pendingCount == 1) { "Catching up must skip the routine moves but keep the strike" }
                shook = shook || !holder.visualShake.isZero
                if (captures.isNotEmpty() && time >= captures.first()) {
                    val at = captures.removeFirst()
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    drawCapture(stage, frame, width, height, "$output/timeline-${(at * 100).toInt().toString().padStart(3, '0')}.png")
                }
            }
            check(shook) { "The detonation must shake the map" }
            check(holder.visualShake.isZero && timeline.pendingCount == 0) { "Playback must finish and settle the map" }
        } finally {
            timeline?.dispose()
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
            check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Capture leaked a GL error" }
            val pixels = Pixmap.createFromFrameBuffer(0, 0, width, height)
            try { PixmapIO.writePNG(Gdx.files.absolute(path), pixels, -1, true) }
            finally { pixels.dispose() }
        } finally { frame.end() }
    }
}
