package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
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
        val sizes = listOf(960 to 720, 420 to 600)
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
                    var setupError = Gdx.gl.glGetError()
                    while (setupError != GL20.GL_NO_ERROR) {
                        println("Setup GL error: $setupError"); setupError = Gdx.gl.glGetError()
                    }
                } catch (ex: Throwable) { failure = ex; Gdx.app.exit() }
            }
            fun updateBar(civ: com.unciv.logic.civilization.Civilization) {
                bar.javaClass.methods.single { it.name.substringBefore('$') == "update" }.invoke(bar, civ)
            }
            override fun render() {
                if (failure != null) return
                try {
                    Gdx.gl.glClearColor(0.1f, 0.16f, 0.17f, 1f)
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    stage.act(1 / 60f)
                    stage.draw()
                    val error = Gdx.gl.glGetError()
                    check(error == GL20.GL_NO_ERROR) { "Render GL error: $error" }
                    if (++frames < 5) return
                    val size = sizes[sizeIndex]
                    check(Gdx.graphics.width == size.first && Gdx.graphics.height == size.second)
                    val pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
                    PixmapIO.writePNG(Gdx.files.absolute(args[0] + "/overview-${size.first}.png"), pixels, -1, true)
                    pixels.dispose()
                    if (++sizeIndex == sizes.size) {
                        println("OVERVIEW_RENDER_CHECK_PASSED: actual GPU, wide and narrow, no GL errors")
                        Gdx.app.exit()
                    } else {
                        frames = 0
                        Gdx.graphics.setWindowedMode(sizes[sizeIndex].first, sizes[sizeIndex].second)
                    }
                } catch (ex: Throwable) { failure = ex; Gdx.app.exit() }
            }
            override fun resize(width: Int, height: Int) {
                if (!::stage.isInitialized || !::panel.isInitialized) return
                stage.viewport.update(width, height, true)
                updateBar(screen.viewingCiv)
                panel.refresh()
            }
            override fun dispose() {
                if (::panel.isInitialized) panel.dispose()
                if (::stage.isInitialized) stage.dispose()
            }
        }, config)
        failure?.printStackTrace()
        kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }
}
