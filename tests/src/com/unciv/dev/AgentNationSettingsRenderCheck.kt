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
import com.badlogic.gdx.graphics.glutils.HdpiUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.logic.agents.*
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.status.AgentMatchControlTable
import com.unciv.ui.screens.worldscreen.status.AgentNationSettingsPanel
import org.mockito.Mockito

/** Production panel and real GL, entirely synthetic session data; no provider, save or clipboard I/O. */
object AgentNationSettingsRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide a capture directory" }
        val sizes = listOf(960 to 720, 420 to 600)
        val synchronous = java.lang.Boolean.getBoolean("unciv.renderCheck.synchronous")
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv nation settings render check")
            setWindowedMode(960, 720); setInitialVisible(false); disableAudio(true)
        }
        var failure: Throwable? = null
        Lwjgl3Application(object : ApplicationAdapter() {
            lateinit var stage: UncivStage
            lateinit var panel: AgentNationSettingsPanel
            lateinit var controls: AgentMatchControlTable
            var target: FrameBuffer? = null
            var sizeIndex = 0
            var stateIndex = 0
            var frames = 0
            var complete = false
            var pending: String? = null
            var completion: ((String?) -> Unit)? = null
            var requests = 0
            val states = listOf("form", "models", "actions", "pending", "error")
            override fun create() {
                try {
                    val game = TestGame()
                    val spectator = game.addCiv(game.ruleset.nations[Constants.spectator]!!)
                    Fonts.fontImplementation = FontDesktop(); ImageGetter.setNewRuleset(game.ruleset)
                    if (synchronous) HdpiUtils.setMode(HdpiMode.Pixels)
                    stage = UncivStage(ScreenViewport()); stage.viewport.update(960, 720, true)
                    val screen = Mockito.mock(WorldScreen::class.java)
                    Mockito.`when`(screen.stage).thenReturn(stage)
                    Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
                    Mockito.`when`(screen.viewingCiv).thenReturn(spectator)
                    Mockito.`when`(screen.currentViewerCivId()).thenReturn(spectator.civID)
                    val session = Mockito.mock(AgentMatchSession::class.java)
                    Mockito.`when`(screen.agentSession).thenReturn(session)
                    val nations = listOf("America", "Arabia", "Germany", "China", "Russia", "The Ottomans", "Persia", "England", "France")
                    val settings = nations.mapIndexed { index, nation ->
                        NationSetting(nation, nation, if (index == 0) ControllerKind.HUMAN else ControllerKind.EXTERNAL_AGENT,
                            AgentProfile.defaultFor(nation).apply {
                                if (index == 0) {
                                    displayName = "A patient ruler of coastlines, trade routes and a very long imperial title"
                                    modelId = "saved-provider/custom-model-with-a-long-identity"; reasoning = "high"
                                    personality = "Protect the river border, negotiate peaceful trade and expand carefully along the coast."
                                }
                            }, index == 0)
                    }
                    Mockito.`when`(session.getNationSettings()).thenReturn(settings)
                    Mockito.`when`(session.getRunState()).thenReturn(AgentRunState.PAUSED)
                    Mockito.`when`(session.getActiveCivId()).thenReturn("America")
                    Mockito.`when`(session.getActiveAgentProfile()).thenReturn(settings.first().profile)
                    Mockito.`when`(session.canResume()).thenReturn(true)
                    val tech = Mockito.mock(com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons::class.java)
                    Mockito.`when`(tech.y).thenAnswer { stage.height - 80f }
                    val getter = screen.javaClass.methods.single { it.name.substringBefore('$') == "getTechPolicyAndDiplomacy" }
                    Mockito.`when`(getter.invoke(screen) as com.unciv.ui.screens.worldscreen.TechPolicyDiplomacyButtons?).thenReturn(tech)
                    Mockito.`when`(session.canSpectate()).thenReturn(true)
                    Mockito.`when`(session.getPendingNationChange()).thenAnswer { pending }
                    Mockito.doAnswer {
                        requests++; completion = it.getArgument(3); pending = "Finishing the admitted agent turn before applying settings…"; null
                    }.`when`(session).requestNationChange(Mockito.nullable(String::class.java),
                        Mockito.any(NationControlChange::class.java) ?: NationControlChange.SAVE_AGENT_SETTINGS, Mockito.nullable(AgentProfile::class.java),
                        Mockito.any<(String?) -> Unit>() ?: {})
                    controls = AgentMatchControlTable(session, screen); stage.addActor(controls)
                    placeControls()
                    panel = stage.root.children.filterIsInstance<AgentNationSettingsPanel>().single()
                    controls.descendants().filterIsInstance<TextButton>().single { it.text.toString() == "Settings" }.press()
                    Gdx.files.absolute(args[0]).mkdirs()
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { /* drain benign asset setup errors */ }
                    if (synchronous) {
                        while (!complete && failure == null) render()
                        failure?.let { throw it }
                        dispose(); kotlin.system.exitProcess(0)
                    }
                } catch (error: Throwable) {
                    failure = error
                    if (synchronous) { error.printStackTrace(); dispose(); kotlin.system.exitProcess(1) }
                    Gdx.app.exit()
                }
            }
            override fun render() {
                if (complete || failure != null) return
                try {
                    val (width, height) = sizes[sizeIndex]
                    if (synchronous && (target?.width != width || target?.height != height)) {
                        target?.dispose(); target = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
                    }
                    target?.begin()
                    try {
                        if (target == null) stage.viewport.apply()
                        Gdx.gl.glClearColor(0.12f, 0.2f, 0.22f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                        stage.root.act(1 / 60f); stage.camera.update()
                        stage.batch.projectionMatrix = stage.camera.combined
                        stage.batch.begin()
                        try { stage.root.draw(stage.batch, 1f) } finally { stage.batch.end() }
                        check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Nation settings GL draw failed" }
                        if (++frames >= 30) capture(width, height)
                    } finally { target?.end() }
                } catch (error: Throwable) { failure = error; if (!synchronous) Gdx.app.exit() }
            }
            private fun capture(width: Int, height: Int) {
                panel.validate(); controls.validate()
                check(controls.width <= stage.width - 16f && controls.x >= 0f && controls.x + controls.width <= stage.width)
                check(controls.y >= 0f && controls.y + controls.height <= stage.height)
                for (button in controls.descendants().filterIsInstance<TextButton>()) {
                    val point = button.localToAscendantCoordinates(controls, Vector2())
                    check(point.x >= 0 && point.x + button.width <= controls.width + 0.5f) { "Match controls button exceeds the compact bar" }
                }
                check(panel.x >= 0 && panel.y >= 0 && panel.x + panel.width <= stage.width && panel.y + panel.height <= stage.height)
                for (name in listOf("NationPicker", "NationProvider", "NationModel", "NationCustomModel", "NationReasoning", "NationDisplayName", "NationPersonality", "NationSave", "NationPlay", "NationHandback", "NationSpectate")) {
                    val actor = panel.named(name); val point = actor.localToAscendantCoordinates(panel, Vector2())
                    check(point.x >= -0.5f && point.x + actor.width <= panel.width + 0.5f) { "$name exceeds the panel at ${width}x$height" }
                }
                if (stateIndex == 1) {
                    val popup = (panel.named("NationModel") as SelectBox<*>).scrollPane
                    check(popup.x >= 0 && popup.x + popup.width <= stage.width + 0.5f) { "Model dropdown exceeds viewport" }
                }
                if (stateIndex == 3) {
                    check((panel.named("NationSave") as TextButton).isDisabled)
                    check((panel.named("NationStatus") as Label).text.toString().contains("Finishing"))
                    panel.named("NationSave").press(); check(requests == sizeIndex + 1) { "Pending request duplicated" }
                }
                if (stateIndex == 4) {
                    check((panel.named("NationStatus") as Label).text.toString() == "Synthetic checkpoint failure")
                    check((panel.named("NationDisplayName") as TextField).text.contains("patient ruler"))
                }
                val pixels = Pixmap.createFromFrameBuffer(0, 0, if (synchronous) width else Gdx.graphics.backBufferWidth,
                    if (synchronous) height else Gdx.graphics.backBufferHeight)
                try { PixmapIO.writePNG(Gdx.files.absolute("${args[0]}/nation-settings-${states[stateIndex]}-$width.png"), pixels, -1, true) }
                finally { pixels.dispose() }
                check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Nation settings readback failed" }
                frames = 0
                when (++stateIndex) {
                    1 -> (panel.named("NationModel") as SelectBox<*>).showList()
                    2 -> {
                        (panel.named("NationModel") as SelectBox<*>).hideList()
                        val scroll = panel.named("NationSettingsScroll") as ScrollPane
                        scroll.scrollY = scroll.maxY; scroll.updateVisualScroll()
                    }
                    3 -> { panel.named("NationSave").press(); panel.refresh() }
                    4 -> { pending = null; completion!!("Synthetic checkpoint failure") }
                    5 -> {
                        if (++sizeIndex == sizes.size) {
                            println("NATION_SETTINGS_RENDER_CHECK_PASSED: 960x720 and 420x600, custom model/reasoning, scrollable actions, dropdown bounds, pending duplicate guard and retained error draft; no providers")
                            complete = true; if (!synchronous) Gdx.app.exit()
                        } else {
                            stateIndex = 0
                            if (synchronous) resizeLogical(sizes[sizeIndex].first, sizes[sizeIndex].second)
                            else Gdx.graphics.setWindowedMode(sizes[sizeIndex].first, sizes[sizeIndex].second)
                            val scroll = panel.named("NationSettingsScroll") as ScrollPane
                            scroll.scrollY = 0f; scroll.updateVisualScroll()
                        }
                    }
                }
            }
            private fun placeControls() {
                controls.update(); controls.setPosition(stage.width - controls.width - 8f, stage.height - controls.height - 8f)
            }
            private fun resizeLogical(width: Int, height: Int) { stage.viewport.update(width, height, true); panel.refresh(); placeControls() }
            override fun resize(width: Int, height: Int) { if (!synchronous && ::panel.isInitialized) resizeLogical(width, height) }
            override fun dispose() { target?.dispose(); if (::controls.isInitialized) controls.dispose() else if (::panel.isInitialized) panel.dispose(); if (::stage.isInitialized) stage.dispose() }
        }, config)
        failure?.printStackTrace(); kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }
    private fun Actor.descendants(): Sequence<Actor> = sequence {
        yield(this@descendants); if (this@descendants is Group) children.forEach { yieldAll(it.descendants()) }
    }
    private fun Actor.named(name: String) = descendants().single { it.name == name }
    private fun Actor.press() = listeners.filterIsInstance<ActivationListener>().single().tap(InputEvent().apply { listenerActor = this@press }, 1f, 1f, 1, 0)
}
