package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.agents.AgentMatchSession
import com.unciv.logic.agents.AgentProfile
import com.unciv.logic.agents.AgentRunState
import com.unciv.testing.TestGame
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.overview.CivilizationOverviewPanel
import com.unciv.ui.screens.worldscreen.topbar.WorldScreenTopBar
import com.unciv.view.GameView
import org.mockito.Mockito

/** Real Scene2D and GPU render of a deterministic game fixture; never touches user saves or agents. */
object CivilizationOverviewRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide an output directory for the PNG captures" }
        val sizes = listOf(960 to 720, 420 to 600)
        val synchronous = java.lang.Boolean.getBoolean("unciv.renderCheck.synchronous")
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv overview render check")
            setWindowedMode(sizes[0].first, sizes[0].second)
            setInitialVisible(false)
            disableAudio(true)
        }
        var failure: Throwable? = null
        Lwjgl3Application(object : ApplicationAdapter() {
            lateinit var stage: UncivStage
            lateinit var panel: CivilizationOverviewPanel
            lateinit var bar: WorldScreenTopBar
            lateinit var screen: WorldScreen
            var sizeIndex = 0
            var frames = 0
            var completed = false
            var captureFramebuffer: FrameBuffer? = null
            override fun create() {
                try {
                    val fixture = TestGame()
                    fixture.makeHexagonalMap(8, Constants.grassland)
                    val nations = listOf("Rome", "Korea", "Arabia", "Babylon", "Polynesia", "Mongolia")
                    for ((i, name) in nations.withIndex()) {
                        val civ = fixture.addCiv(fixture.ruleset.nations[name]!!)
                        val tile = fixture.getTile(-5 + i * 2, -3 + i)
                        val city = fixture.addCity(civ, tile, initialPopulation = 2 + i)
                        city.cityConstructions.addToQueue("Scout")
                        fixture.addUnit("Warrior", civ, tile)
                        civ.addGold(120 + i * 77)
                        civ.tech.techsToResearch.add("Pottery")
                        civ.updateStatsForNextTurn()
                    }
                    val spectator = fixture.addCiv(fixture.ruleset.nations[Constants.spectator]!!)
                    fixture.gameInfo.turns = 17
                    Fonts.fontImplementation = FontDesktop()
                    ImageGetter.setNewRuleset(fixture.ruleset)
                    if (synchronous) HdpiUtils.setMode(HdpiMode.Pixels)
                    stage = UncivStage(ScreenViewport())
                    stage.viewport.update(960, 720, true)
                    val backdrop = Table().apply {
                        background = WorldScreenStyle.background("", Color.valueOf("314b4b"))
                        setFillParent(true)
                        add("GPU test fixture · Civilization comparison".toLabel(WorldScreenStyle.muted, 14))
                            .expand().left().top().padTop(100f).padLeft(16f)
                    }
                    stage.addActor(backdrop)
                    screen = Mockito.mock(WorldScreen::class.java)
                    Mockito.`when`(screen.stage).thenReturn(stage)
                    Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
                    Mockito.`when`(screen.gameInfo).thenReturn(fixture.gameInfo)
                    Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
                    Mockito.`when`(screen.selectedCiv).thenReturn(spectator)
                    Mockito.`when`(screen.selectedGameView).thenReturn(GameView(fixture.gameInfo, spectator, true))
                    val session = Mockito.mock(AgentMatchSession::class.java)
                    Mockito.`when`(screen.agentSession).thenReturn(session)
                    Mockito.`when`(session.getActiveCivId()).thenReturn("Korea")
                    Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.AGENT_DECIDING)
                    Mockito.`when`(session.getAgentProfile(Mockito.anyString())).thenAnswer {
                        AgentProfile.defaultFor(it.getArgument(0)).apply {
                            displayName = if (it.getArgument<String>(0) == "Korea") "Nova" else "Atlas ${it.getArgument<String>(0)}"
                        }
                    }
                    bar = WorldScreenTopBar(screen)
                    stage.addActor(bar)
                    updateBar(spectator)
                    panel = CivilizationOverviewPanel(screen)
                    Gdx.files.absolute(args[0]).mkdirs()
                    var setupError = Gdx.gl.glGetError()
                    while (setupError != GL20.GL_NO_ERROR) {
                        println("Setup GL error: $setupError"); setupError = Gdx.gl.glGetError()
                    }
                    if (synchronous) {
                        // Hidden macOS windows can stall in native event polling. Use the current
                        // context and logical-size FBOs for both viewport sizes.
                        while (!completed && failure == null) render()
                        failure?.let { throw it }
                        dispose()
                        kotlin.system.exitProcess(0)
                    }
                } catch (ex: Throwable) {
                    failure = ex
                    if (synchronous) {
                        ex.printStackTrace()
                        dispose()
                        kotlin.system.exitProcess(1)
                    }
                    Gdx.app.exit()
                }
            }
            fun updateBar(civ: com.unciv.logic.civilization.Civilization) {
                bar.javaClass.methods.single { it.name.substringBefore('$') == "update" }.invoke(bar, civ)
            }
            override fun render() {
                if (failure != null || completed) return
                try {
                    val (width, height) = sizes[sizeIndex]
                    if (synchronous && (captureFramebuffer?.width != width || captureFramebuffer?.height != height)) {
                        captureFramebuffer?.dispose()
                        captureFramebuffer = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
                    }
                    val target = captureFramebuffer
                    target?.begin()
                    try {
                        if (target == null) stage.viewport.apply()
                        drawAndCapture(width, height)
                    } finally { target?.end() }
                } catch (ex: Throwable) {
                    failure = ex
                    if (!synchronous) Gdx.app.exit()
                }
            }

            private fun drawAndCapture(width: Int, height: Int) {
                Gdx.gl.glClearColor(0.1f, 0.16f, 0.17f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                stage.root.act(1 / 60f)
                stage.camera.update()
                stage.batch.projectionMatrix = stage.camera.combined
                stage.batch.begin()
                try { stage.root.draw(stage.batch, 1f) } finally { stage.batch.end() }
                val error = Gdx.gl.glGetError()
                check(error == GL20.GL_NO_ERROR) { "Render GL error: $error" }
                if (++frames < 5) return
                if (synchronous) check(stage.width == width.toFloat() && stage.height == height.toFloat()) {
                    "Logical viewport resize was not applied"
                } else check(Gdx.graphics.width == width && Gdx.graphics.height == height)
                val pixels = Pixmap.createFromFrameBuffer(0, 0,
                    if (synchronous) width else Gdx.graphics.backBufferWidth,
                    if (synchronous) height else Gdx.graphics.backBufferHeight)
                try {
                    check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Overview capture readback leaked a GL error" }
                    PixmapIO.writePNG(Gdx.files.absolute(args[0] + "/overview-$width.png"), pixels, -1, true)
                } finally { pixels.dispose() }
                if (++sizeIndex == sizes.size) {
                    println("OVERVIEW_RENDER_CHECK_PASSED: actual GPU, wide and narrow, no GL errors")
                    completed = true
                    if (!synchronous) Gdx.app.exit()
                } else {
                    frames = 0
                    if (synchronous) resizeLogical(sizes[sizeIndex].first, sizes[sizeIndex].second)
                    else Gdx.graphics.setWindowedMode(sizes[sizeIndex].first, sizes[sizeIndex].second)
                }
            }

            private fun resizeLogical(width: Int, height: Int) {
                stage.viewport.update(width, height, true)
                updateBar(screen.viewingCiv)
                panel.refresh()
            }

            override fun resize(width: Int, height: Int) {
                if (synchronous || !::stage.isInitialized || !::panel.isInitialized) return
                resizeLogical(width, height)
            }
            override fun dispose() {
                captureFramebuffer?.dispose()
                captureFramebuffer = null
                if (::panel.isInitialized) panel.dispose()
                if (::stage.isInitialized) stage.dispose()
            }
        }, config)
        failure?.printStackTrace()
        kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }
}
