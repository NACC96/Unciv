package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.ui.screens.worldscreen.status.AgentActivityGlass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.math.ceil

/** Optional GPU component benchmark. No match, save, provider or external agent is started. */
object ActivityGlassPerformanceCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide a JSON report path" }
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv glass performance check")
            setWindowedMode(1728, 1038)
            // Hidden macOS windows can stop rendering entirely; opt in for an isolated check.
            setInitialVisible(System.getProperty("unciv.renderCheck.visible") == "true")
            useVsync(false)
            setForegroundFPS(120)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            private lateinit var stage: Stage
            private lateinit var panel: Actor
            private lateinit var backdrop: Texture
            private lateinit var glass: AgentActivityGlass
            private lateinit var originalGl: GL20
            private lateinit var measuredGl: GL20
            private var captures = 0
            private var captureWidth = 0
            private var captureHeight = 0
            private var frame = 0
            private var phase = 0
            private var phaseStart = 0L
            private var measuredPhaseStart = 0L
            private var measuredCaptures = 0
            private val plainTimes = mutableListOf<Double>()
            private val cachedTimes = mutableListOf<Double>()
            private val refreshTimes = mutableListOf<Double>()
            private val sizes = listOf(300f to 680f, 230f to 450f, 400f to 880f)
            private val reports = mutableListOf<Map<String, Any>>()

            override fun create() {
                try {
                    originalGl = Gdx.gl20
                    measuredGl = Proxy.newProxyInstance(GL20::class.java.classLoader,
                        arrayOf(GL20::class.java)) { _, method, parameters ->
                        check(method.name != "glCopyTexSubImage2D") { "Unsafe framebuffer copy attempted" }
                        if (method.name == "glReadPixels") {
                            captures++
                            captureWidth = parameters!![2] as Int
                            captureHeight = parameters[3] as Int
                        }
                        try { method.invoke(originalGl, *(parameters ?: emptyArray())) }
                        catch (ex: InvocationTargetException) { throw ex.targetException }
                    } as GL20
                    Gdx.gl = measuredGl
                    Gdx.gl20 = measuredGl
                    val fixture = TestGame()
                    Fonts.fontImplementation = FontDesktop()
                    ImageGetter.setNewRuleset(fixture.ruleset)
                    stage = Stage(ScreenViewport())
                    stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
                    panel = Actor().apply { setBounds(16f, 16f, sizes[0].first, sizes[0].second) }
                    stage.addActor(panel)
                    glass = AgentActivityGlass(panel)
                    val pixels = Pixmap(1024, 1024, Pixmap.Format.RGBA8888)
                    for (y in 0 until 1024 step 16) for (x in 0 until 1024 step 16) {
                        pixels.setColor(if ((x / 16 + y / 16) % 3 == 0) Color.valueOf("387984")
                            else if ((x / 16 + y / 16) % 3 == 1) Color.valueOf("b2b64f")
                            else Color.valueOf("797f50"))
                        pixels.fillRectangle(x, y, 16, 16)
                    }
                    backdrop = Texture(pixels)
                    pixels.dispose()
                    // Check only the benchmark draws, after atlas/font initialization finishes.
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    phaseStart = System.nanoTime()
                    // macOS can block in GLFW polling for an unbundled diagnostic process.
                    // Run component draws on this already-current GL context when requested.
                    if (System.getProperty("unciv.renderCheck.synchronous") == "true") {
                        while (phase < sizes.size && failure == null) {
                            render()
                            Thread.sleep(8)
                        }
                        failure?.let { throw it }
                        System.exit(0)
                    }
                } catch (ex: Throwable) {
                    failure = ex
                    Gdx.app.exit()
                }
            }

            private fun drawBackdrop() {
                stage.viewport.apply()
                Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                val batch = stage.batch
                batch.begin()
                batch.color = Color.WHITE
                batch.draw(backdrop, 0f, 0f, stage.width, stage.height)
                batch.end()
                // Finish the fixture backdrop before starting a component timing.
                Gdx.gl.glFinish()
            }

            private fun timed(draw: () -> Unit): Double {
                val started = System.nanoTime()
                stage.batch.begin()
                stage.batch.color = Color.WHITE
                draw()
                stage.batch.end()
                // These timings include completed GPU work, not just command submission.
                Gdx.gl.glFinish()
                return (System.nanoTime() - started) / 1e6
            }

            override fun render() {
                if (failure != null) return
                try {
                    Gdx.gl = measuredGl
                    Gdx.gl20 = measuredGl
                    val plain = WorldScreenStyle.background("WorldScreen/AgentActivityFeed",
                        Color(WorldScreenStyle.surface).apply { a = 0.82f })
                    drawBackdrop()
                    checkGl("plain backdrop")
                    val plainMs = timed { plain.draw(stage.batch, panel.x, panel.y, panel.width, panel.height) }
                    checkGl("plain background")
                    drawBackdrop()
                    checkGl("blur backdrop")
                    val before = captures
                    val glassMs = timed { glass.draw(stage.batch, panel.x, panel.y, panel.width, panel.height) }
                    checkGl("glass background")
                    if (frame >= 30) {
                        if (measuredPhaseStart == 0L) measuredPhaseStart = System.nanoTime()
                        plainTimes.add(plainMs)
                        if (captures > before) {
                            refreshTimes.add(glassMs)
                            measuredCaptures++
                        } else cachedTimes.add(glassMs)
                    }
                    frame++
                    val seconds = (System.nanoTime() - phaseStart) / 1e9
                    if (refreshTimes.size >= 20 && cachedTimes.size >= 120 || seconds > 8.0) {
                        check(refreshTimes.size >= 10 && cachedTimes.size >= 60) { "Too few benchmark samples" }
                        reports.add(linkedMapOf(
                            "panelLogicalWidth" to panel.width, "panelLogicalHeight" to panel.height,
                            "capturedPixelWidth" to captureWidth, "capturedPixelHeight" to captureHeight,
                            "cpuReadbackBytesPerCapture" to captureWidth.toLong() * captureHeight * 4,
                            "captureRatePerSecond" to measuredCaptures / ((System.nanoTime() - measuredPhaseStart) / 1e9),
                            "plainCompletedDrawMs" to statistics(plainTimes),
                            "cachedBlurCompletedDrawMs" to statistics(cachedTimes),
                            "refreshBlurCompletedDrawMs" to statistics(refreshTimes)
                        ))
                        phase++
                        if (phase == sizes.size) finishReport(args[0])
                        else {
                            glass.dispose()
                            panel.setSize(sizes[phase].first, sizes[phase].second)
                            glass = AgentActivityGlass(panel)
                            frame = 0
                            phaseStart = System.nanoTime()
                            measuredPhaseStart = 0L
                            measuredCaptures = 0
                            plainTimes.clear(); cachedTimes.clear(); refreshTimes.clear()
                        }
                    }
                } catch (ex: Throwable) {
                    failure = ex
                    Gdx.app.exit()
                }
            }

            private fun statistics(samples: List<Double>): Map<String, Any> {
                val sorted = samples.sorted()
                return linkedMapOf("samples" to sorted.size,
                    "median" to sorted[sorted.size / 2],
                    "p95" to sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)],
                    "mean" to sorted.average(), "max" to sorted.last())
            }

            private fun checkGl(where: String) {
                val error = Gdx.gl.glGetError()
                check(error == GL20.GL_NO_ERROR) { "GL error $error after $where on frame $frame" }
            }

            private fun finishReport(path: String) {
                val report = linkedMapOf<String, Any>(
                    "fixture" to "Actual AgentActivityGlass versus actual rounded translucent WorldScreenStyle background; separate completed GPU component timings",
                    "limitations" to "Not whole-game FPS. Isolated native OpenGL window; simple textured backdrop; each sample includes SpriteBatch begin/end, glFinish and GL proxy instrumentation. Warmup excluded. Stable panel geometry with normal 100 ms capture cadence. No game or agents.",
                    "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
                    "gpuVendor" to Gdx.gl.glGetString(GL20.GL_VENDOR),
                    "gpuRenderer" to Gdx.gl.glGetString(GL20.GL_RENDERER),
                    "glVersion" to Gdx.gl.glGetString(GL20.GL_VERSION),
                    "windowLogicalWidth" to Gdx.graphics.width, "windowLogicalHeight" to Gdx.graphics.height,
                    "backBufferPixelWidth" to Gdx.graphics.backBufferWidth,
                    "backBufferPixelHeight" to Gdx.graphics.backBufferHeight,
                    "pixelScaleX" to Gdx.graphics.backBufferWidth.toDouble() / Gdx.graphics.width,
                    "pixelScaleY" to Gdx.graphics.backBufferHeight.toDouble() / Gdx.graphics.height,
                    "panelResults" to reports
                )
                val json = Json().apply { setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json) }
                Gdx.files.absolute(path).writeString(json.prettyPrint(report), false)
                println("GLASS_PERFORMANCE_CHECK_PASSED " + json.toJson(report))
                Gdx.app.exit()
            }

            override fun dispose() {
                if (::glass.isInitialized) glass.dispose()
                if (::stage.isInitialized) stage.dispose()
                if (::backdrop.isInitialized) backdrop.dispose()
                if (::originalGl.isInitialized) { Gdx.gl = originalGl; Gdx.gl20 = originalGl }
            }
        }, config)
        failure?.let { throw it }
    }
}
