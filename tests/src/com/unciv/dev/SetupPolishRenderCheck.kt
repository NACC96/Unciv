package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.Constants
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.components.SetupLayoutFixture
import com.unciv.ui.components.setupDescendants
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.newgamescreen.AgentProfilePopup
import com.unciv.ui.screens.newgamescreen.NationSettingsPopup
import com.unciv.ui.screens.newgamescreen.SetupPresetsPopup

/** Production setup widgets on a real GPU, with isolated synthetic data and no provider or game run. */
object SetupPolishRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 3) { "Provide an isolated output directory, width and height; run each size in a separate JVM" }
        val synchronous = java.lang.Boolean.getBoolean("unciv.renderCheck.synchronous")
        val fontSizeMultiplier = args.getOrNull(3)?.toFloat() ?: 1f
        // ImageGetter's global atlas cache belongs to this JVM's sole GL context.
        val sizes = listOf(args[1].toInt() to args[2].toInt())
        var failure: Throwable? = null
        for ((width, height) in sizes) {
            if (failure != null) break
            val config = Lwjgl3ApplicationConfiguration().apply {
                setTitle("Unciv setup polish render check")
                setWindowedMode(width, height)
                setInitialVisible(false)
                disableAudio(true)
            }
            Lwjgl3Application(object : ApplicationAdapter() {
                private lateinit var stage: UncivStage
                private lateinit var fixture: SetupLayoutFixture

                override fun create() {
                    try {
                        stage = UncivStage(ScreenViewport())
                        stage.viewport.update(width, height, true)
                        fixture = SetupLayoutFixture(width.toFloat(), height.toFloat(), stage, fontSizeMultiplier)
                        val output = Gdx.files.absolute(args[0])
                        output.mkdirs()
                        fixture.includeOptions(output.child("synthetic-$width-$height").path())
                        fixture.longContent()
                        var setupError = Gdx.gl.glGetError()
                        while (setupError != GL20.GL_NO_ERROR) {
                            println("Setup GL error: $setupError")
                            setupError = Gdx.gl.glGetError()
                        }
                        capture("setup-options")

                        val card = fixture.picker.setupDescendants().filterIsInstance<Table>()
                            .single { it.name == "SetupNation-${fixture.player.chosenCiv}" }
                        reveal(card, fixture.pane.scrollPane)
                        capture("setup-cards")

                        val popup = AgentProfilePopup(fixture.picker, fixture.player, fixture.profile)
                        popup.validate()
                        checkFooter(popup)
                        capture("setup-agent")
                        val model = popup.setupDescendants().filterIsInstance<SelectBox<*>>().toList()[1]
                        reveal(model, popup.getScrollPane()!!)
                        model.showList()
                        settleUiActions()
                        val modelMenu = model.scrollPane
                        check(modelMenu.parent === stage.root && modelMenu.isVisible && modelMenu.color.a >= 0.99f) {
                            "Opened model menu has not become visible: alpha=${modelMenu.color.a}, parent=${modelMenu.parent}"
                        }
                        val modelMenuOrigin = modelMenu.localToStageCoordinates(Vector2())
                        check(modelMenuOrigin.x >= 0f && modelMenuOrigin.y >= 0f &&
                            modelMenuOrigin.x + modelMenu.width <= width + 0.5f &&
                            modelMenuOrigin.y + modelMenu.height <= height + 0.5f) {
                            "Opened long-model dropdown escapes the ${width}x$height viewport"
                        }
                        capture("setup-model-menu")
                        model.hideList()
                        settleUiActions()
                        check(model.scrollPane.parent == null) { "Hidden model menu still owns an actor before the next capture" }
                        popup.close()

                        val nation = NationSettingsPopup(fixture.picker, fixture.player)
                        nation.validate()
                        checkFooter(nation)
                        capture("setup-nation")
                        nation.close()

                        val presets = SetupPresetsPopup(fixture.screen)
                        presets.validate()
                        checkFooter(presets)
                        check(presets.setupDescendants().filterIsInstance<TextButton>()
                            .single { it.text.toString() == "Load" }.isDisabled)
                        capture("setup-presets-empty")
                        presets.close()

                        fixture.setStartEnabled(false)
                        fixture.pane.rightSideButton.setText(Constants.working)
                        capture("setup-working")
                        println("SETUP_POLISH_RENDER_OK $width x $height")
                    } catch (ex: Throwable) {
                        failure = ex
                    } finally {
                        // All evidence is rendered synchronously in create: hidden macOS GLFW
                        // windows may not deliver another frame while another app is active.
                        if (synchronous) {
                            try { dispose() } catch (cleanupError: Throwable) {
                                if (failure == null) failure = cleanupError else failure!!.addSuppressed(cleanupError)
                            }
                            failure?.printStackTrace()
                            System.out.flush()
                            System.err.flush()
                            kotlin.system.exitProcess(if (failure == null) 0 else 1)
                        }
                        Gdx.app.exit()
                    }
                }

                private fun settleUiActions() {
                    // GDX's default SelectBox show fade lasts 0.3s, and hide uses
                    // a sequence whose removal action needs a following act tick.
                    repeat(30) { stage.root.act(1f / 60f) }
                }

                private fun capture(name: String) {
                    fixture.pane.validate()
                    stage.root.act(0f)
                    checkSetupFooter()
                    stage.viewport.apply()
                    Gdx.gl.glClearColor(GameUiStyle.surface.r, GameUiStyle.surface.g, GameUiStyle.surface.b, 1f)
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    stage.camera.update()
                    stage.batch.projectionMatrix = stage.camera.combined
                    stage.batch.begin()
                    try { stage.root.draw(stage.batch, 1f) } finally { stage.batch.end() }
                    check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Setup draw leaked a GL error" }
                    val pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
                    try {
                        check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Setup capture readback leaked a GL error" }
                        PixmapIO.writePNG(Gdx.files.absolute(args[0]).child("$name-${width}x$height.png"), pixels, -1, true)
                    } finally { pixels.dispose() }
                    println("CAPTURE $name-${width}x$height.png")
                }

                private fun checkSetupFooter() {
                    val description = fixture.pane.descriptionLabel
                    val descriptionOrigin = description.localToStageCoordinates(Vector2())
                    val actions = fixture.pane.bottomTable.setupDescendants().filterIsInstance<Table>()
                        .single { it.name == "SetupFooterActions" }
                    val actionsOrigin = actions.localToStageCoordinates(Vector2())
                    val optionsOrigin = fixture.pane.scrollPane.localToStageCoordinates(Vector2())
                    check(description.height + 0.5f >= description.prefHeight && descriptionOrigin.y >= 0f &&
                        descriptionOrigin.y >= actionsOrigin.y + actions.height &&
                        descriptionOrigin.y + description.height <= optionsOrigin.y + 0.5f) {
                        "Wrapped setup description is clipped or overlaps actions/options at ${width}x$height"
                    }
                }

                private fun checkFooter(popup: com.unciv.ui.popups.Popup) {
                    val paneOrigin = popup.getScrollPane()!!.localToStageCoordinates(Vector2())
                    for (button in popup.setupDescendants().filterIsInstance<TextButton>()
                        .filter { it.text.toString() in listOf("Save", "Cancel", "Load", "Save preset", "Close") }) {
                        val origin = button.localToStageCoordinates(Vector2())
                        check(origin.x >= 0f && origin.y >= 0f && origin.x + button.width <= width + 0.5f &&
                            origin.y + button.height <= paneOrigin.y + 0.5f) { "${button.text} escapes its fixed popup footer" }
                    }
                }

                private fun reveal(actor: Actor, pane: com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) {
                    pane.validate()
                    val position = actor.localToAscendantCoordinates(pane.actor, Vector2())
                    pane.scrollY = (pane.actor.height - position.y - actor.height).coerceIn(0f, pane.maxY)
                    pane.updateVisualScroll()
                    pane.invalidate()
                    pane.validate()
                }

                override fun dispose() {
                    if (::fixture.isInitialized) fixture.cancelBackgroundJobs()
                    if (::stage.isInitialized) stage.dispose()
                }
            }, config)
        }
        failure?.printStackTrace()
        kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }
}
