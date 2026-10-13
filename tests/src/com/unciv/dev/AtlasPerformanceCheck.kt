package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.HdpiMode
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Array as GdxArray
import com.unciv.ui.screens.basescreen.BaseScreen
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.math.ceil

/** Repeatable native map trace. CPU submission is never presented as GPU execution time. */
object AtlasPerformanceCheck {
    private const val WARMUP_SECONDS = 10.0
    private const val MEASURE_SECONDS = 30.0
    private const val REPEATS = 3
    private val path = listOf(
        Keyframe(0.0, .25f, .35f, 1.1f), Keyframe(7.5, .65f, .35f, .65f),
        Keyframe(15.0, .7f, .7f, 1.4f), Keyframe(22.5, .3f, .65f, .8f),
        Keyframe(30.0, .25f, .35f, 1.1f))
    private data class Keyframe(val seconds: Double, val x: Float, val y: Float, val zoom: Float)
    private data class Frame(val seconds: Double, val cpuSubmissionWallMs: Double,
        val threadCpuMs: Double, val swapInclusiveMs: Double, val gpuMs: Double?,
        val drawCalls: Int, val textureAllocations: Int, val textureUploadBytes: Long, val heapBytes: Long)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && Path.of(args[0]).isAbsolute) { "Provide an absolute output directory" }
        val output = Path.of(args[0])
        System.setProperty("unciv.atlas.fixtureRoot", output.resolve("fixtures").toString())
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv Atlas isolated native baseline")
            setWindowedMode(1440, 900)
            setHdpiMode(HdpiMode.Pixels)
            setInitialVisible(false)
            useVsync(true)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            private var screen: BaseScreen? = null
            private var idleRenders = 0
            override fun render() {
                screen?.let { AtlasRenderCheck.draw(it, 0f); idleRenders++ }
            }
            override fun create() {
                var code = 1
                val counters = GraphicsCounters()
                try {
                    Gdx.graphics.isContinuousRendering = false
                    screen = AtlasFixtureCatalog.createPerformance()
                    var scene = screen!!
                    var scope = AtlasFixtureCatalog.dataFor(scene)
                    val graphics = Gdx.graphics as Lwjgl3Graphics
                    val window = graphics.window
                    val supportsTimer = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query
                    val query = if (supportsTimer) GL33.glGenQueries() else 0
                    val cpuClock = ManagementFactory.getThreadMXBean()
                    if (cpuClock.isThreadCpuTimeSupported) cpuClock.isThreadCpuTimeEnabled = true
                    while (Gdx.gl.glGetError() != GL20.GL_NO_ERROR) { }
                    try {
                        for (repeat in 1..REPEATS) {
                            if (repeat > 1) {
                                AtlasFixtureCatalog.dispose(scene)
                                screen = AtlasFixtureCatalog.createPerformance()
                                scene = screen!!
                                scope = AtlasFixtureCatalog.dataFor(scene)
                            }
                            val actions = AtlasActionSchedule(scope.fixture)
                            var visibleActions = 0
                            val actionEvents = ArrayList<Map<String, Any?>>()
                            println("ATLAS_TRACE_START $repeat warmup=10s measure=30s seed=${AtlasFixtureCatalog.SEED}")
                            val frames = ArrayList<Frame>()
                            val start = System.nanoTime()
                            var prior = start
                            while (true) {
                                val now = System.nanoTime()
                                val elapsed = (now - start) / 1e9
                                if (elapsed >= WARMUP_SECONDS + MEASURE_SECONDS) break
                                val traceTime = if (elapsed < WARMUP_SECONDS) elapsed / WARMUP_SECONDS * MEASURE_SECONDS else elapsed - WARMUP_SECONDS
                                counters.resetFrame()
                                val cpuStart = if (cpuClock.isThreadCpuTimeSupported) cpuClock.currentThreadCpuTime else 0L
                                val frameStart = System.nanoTime()
                                camera(scene, traceTime)
                                val applied = if (elapsed >= WARMUP_SECONDS) actions.executeThrough(traceTime) else emptyList()
                                for (move in applied) {
                                    val map = scope.map!!
                                    val view = com.unciv.view.GameView(scope.fixture.gameInfo,
                                        scope.fixture.gameInfo.civilizations.first(), true)
                                    for (position in listOf(move.origin, move.destination)) {
                                        map.tileGroups.values.single { it.tileView.position() == position }.update(view.civView)
                                    }
                                    val visible = visible(scene, move.destination)
                                    if (visible) visibleActions++
                                    actionEvents += linkedMapOf("scheduledSeconds" to move.seconds,
                                        "executedSeconds" to traceTime, "unitIndex" to move.unitIndex,
                                        "visible" to visible)
                                }
                                if (query != 0) GL33.glBeginQuery(GL33.GL_TIME_ELAPSED, query)
                                AtlasRenderCheck.draw(scene, ((now - prior) / 1e9).toFloat().coerceAtMost(.1f))
                                if (query != 0) GL33.glEndQuery(GL33.GL_TIME_ELAPSED)
                                val submissionEnd = System.nanoTime()
                                val cpuEnd = if (cpuClock.isThreadCpuTimeSupported) cpuClock.currentThreadCpuTime else 0L
                                GLFW.glfwSwapBuffers(window.windowHandle)
                                val swapEnd = System.nanoTime()
                                // Query readback happens after the measured frame. This blocking
                                // overhead is recorded separately in wall duration, never CPU-as-GPU.
                                val gpuMs = if (query != 0) GL33.glGetQueryObjectui64(query, GL33.GL_QUERY_RESULT) / 1e6 else null
                                if (elapsed >= WARMUP_SECONDS) frames += Frame(traceTime,
                                    (submissionEnd - frameStart) / 1e6, (cpuEnd - cpuStart) / 1e6,
                                    (swapEnd - frameStart) / 1e6, gpuMs, counters.drawCalls,
                                    counters.frameAllocations, counters.frameUploadBytes, usedHeap())
                                if (applied.isNotEmpty()) {
                                    // Evidence readback is outside the measured frame. Redraw the
                                    // committed positions after swap before recording each action.
                                    AtlasRenderCheck.draw(scene, 0f)
                                    for (move in applied) capture(output.resolve("action-$repeat-${move.unitIndex + 1}.png"))
                                }
                                prior = now
                            }
                            check(frames.isNotEmpty() && frames.last().seconds >= 29.0) { "Incomplete baseline trace" }
                            check(actions.executed.size == 6 && visibleActions == 6) { "Scheduled actions were incomplete or offscreen" }
                            check(scope.providerCalls == 0)
                            capture(output.resolve("performance-$repeat.png"))
                            val idle = measureIdle(window, 2.0)
                            val frameTimes = frames.map { it.swapInclusiveMs }.sorted()
                            val median = percentile(frameTimes, .5)
                            val p95 = percentile(frameTimes, .95)
                            val csv = buildString {
                                appendLine("seconds,cpu_submission_wall_ms,thread_cpu_ms,swap_inclusive_ms,gpu_ms,draw_calls,texture_allocations,texture_upload_bytes,heap_bytes")
                                frames.forEach { f -> appendLine("${f.seconds},${f.cpuSubmissionWallMs},${f.threadCpuMs},${f.swapInclusiveMs},${f.gpuMs ?: ""},${f.drawCalls},${f.textureAllocations},${f.textureUploadBytes},${f.heapBytes}") }
                            }
                            Gdx.files.absolute(output.resolve("trace-$repeat.csv").toString()).writeString(csv, false)
                            AtlasRenderCheck.writeJson(output.resolve("trace-$repeat.json"), linkedMapOf(
                                "sourceHash" to System.getProperty("unciv.atlas.sourceHash"),
                                "diffHash" to System.getProperty("unciv.atlas.diffHash"),
                                "harnessHash" to System.getProperty("unciv.atlas.harnessHash"),
                                "fixture" to "seeded mixed-biome world + Activity",
                                "seed" to AtlasFixtureCatalog.SEED, "mapWidth" to 164, "mapHeight" to 80,
                                "majorCivilizations" to 15, "spectators" to 1, "unitsPerCiv" to 12, "unitCount" to 180,
                                "unitTypes" to listOf("Warrior", "Scout"), "viewport" to listOf(1440, 900), "fontScale" to 1f,
                                "nativeDrawable" to listOf(Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight),
                                "renderViewportPixels" to listOf(scene.stage.viewport.screenWidth, scene.stage.viewport.screenHeight),
                                "tileset" to "HexaRealm", "unitset" to "AbsoluteUnits", "worldWrap" to true,
                                "cameraKeyframes" to path.map { listOf(it.seconds, it.x, it.y, it.zoom) },
                                "actionStream" to "six legal engine unit moves, scheduled by trace seconds; zero provider calls",
                                "actionSchedule" to AtlasActionSchedule.moves.map { linkedMapOf("seconds" to it.seconds,
                                    "civId" to "America", "unitIndex" to it.unitIndex,
                                    "origin" to listOf(it.origin.x, it.origin.y),
                                    "destination" to listOf(it.destination.x, it.destination.y)) },
                                "actionFocusWindowSeconds" to 2.0,
                                "cameraActionFocus" to "center on each scheduled origin from one second before to one second after its timestamp",
                                "actionEvents" to actionEvents, "actionCount" to actions.executed.size,
                                "visibleActionCount" to visibleActions,
                                "authoritativeFinalState" to actions.finalState(),
                                "authoritativeFinalStateHash" to actions.finalStateHash(),
                                "stateHashMeaning" to "six engine units: ordered civ/index/name/position/health/movement",
                                "actionCaptureOverhead" to "extra draw/readback after measured swap; outside per-frame timing",
                                "warmupSeconds" to WARMUP_SECONDS, "measurementSeconds" to MEASURE_SECONDS,
                                "repeat" to repeat, "repeats" to REPEATS, "frames" to frames.size,
                                "medianSwapInclusiveMs" to median, "p95SwapInclusiveMs" to p95,
                                "medianCpuSubmissionWallMs" to percentile(frames.map { it.cpuSubmissionWallMs }.sorted(), .5),
                                "medianThreadCpuMs" to percentile(frames.map { it.threadCpuMs }.sorted(), .5),
                                "gpuTiming" to if (supportsTimer) "GL_TIME_ELAPSED timer query; query readback outside measured frame" else "unsupported timer queries; GPU timings are null",
                                "medianGpuMs" to if (supportsTimer) percentile(frames.mapNotNull { it.gpuMs }.sorted(), .5) else null,
                                "medianDrawCalls" to percentile(frames.map { it.drawCalls.toDouble() }.sorted(), .5),
                                "textureAllocationsTotal" to counters.totalAllocations,
                                "textureAllocationsDuringMeasurement" to frames.sumOf { it.textureAllocations },
                                "textureUploadBytesTotal" to counters.totalUploadBytes,
                                "textureMemoryMeaning" to "GL texture upload bytes, including supplied mip levels; driver residency and generated mipmaps are not included",
                                "heapMinBytes" to frames.minOf { it.heapBytes }, "heapMaxBytes" to frames.maxOf { it.heapBytes },
                                "idleSeconds" to 2.0, "idleRenderCount" to idle, "continuousRendering" to Gdx.graphics.isContinuousRendering,
                                "providerCalls" to scope.providerCalls,
                                "resolvedRoots" to scope.resolvedRoots(),
                                "targetMedianMs" to 16.7, "targetP95Ms" to 33.3,
                                "meetsTarget" to (median <= 16.7 && p95 <= 33.3),
                                "renderer" to Gdx.gl.glGetString(GL20.GL_RENDERER),
                                "glVersion" to Gdx.gl.glGetString(GL20.GL_VERSION),
                                "javaVersion" to System.getProperty("java.version"), "osArch" to System.getProperty("os.arch")))
                            println("ATLAS_TRACE_OK $repeat frames=${frames.size} median=$median p95=$p95 idleRenders=$idle")
                        }
                    } finally { if (query != 0) GL33.glDeleteQueries(query) }
                    code = 0
                } catch (failure: Throwable) { failure.printStackTrace() }
                finally {
                    runCatching { screen?.let { AtlasFixtureCatalog.dispose(it) } }
                        .onFailure { it.printStackTrace(); code = 1 }
                    runCatching { counters.close() }.onFailure { it.printStackTrace(); code = 1 }
                    System.out.flush(); System.err.flush()
                    kotlin.system.exitProcess(code)
                }
            }

            private fun measureIdle(window: com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window, seconds: Double): Int {
                // Exercise the real backend request-render gate without hidden-window event
                // polling, which can stall on macOS. No fixture render is called directly here.
                val initialized = window.javaClass.getDeclaredField("listenerInitialized").apply { isAccessible = true }
                initialized.setBoolean(window, true)
                val update = window.javaClass.getDeclaredMethod("update").apply { isAccessible = true }
                update.invoke(window) // consume startup request before the idle observation
                idleRenders = 0
                val end = System.nanoTime() + (seconds * 1e9).toLong()
                while (System.nanoTime() < end) {
                    // Timer posts are normally drained by Lwjgl3Application.loop. Preserve
                    // that behavior while driving the backend synchronously on its GL thread.
                    val field = Gdx.app.javaClass.getDeclaredField("runnables").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val queue = field.get(Gdx.app) as GdxArray<Runnable>
                    val pending = synchronized(queue) { queue.toList().also { queue.clear() } }
                    pending.forEach { it.run() }
                    if (pending.isNotEmpty()) Gdx.graphics.requestRendering()
                    update.invoke(window)
                    Thread.sleep(5)
                }
                return idleRenders
            }
        }, config)
    }

    private fun camera(screen: BaseScreen, seconds: Double) {
        val map = AtlasFixtureCatalog.dataFor(screen).map!!
        val index = path.zipWithNext().indexOfFirst { seconds <= it.second.seconds }.coerceAtLeast(0)
        val a = path[index]; val b = path[index + 1]
        val f = ((seconds - a.seconds) / (b.seconds - a.seconds)).toFloat().coerceIn(0f, 1f)
        map.zoom(a.zoom + (b.zoom - a.zoom) * f)
        map.scrollPercentX = a.x + (b.x - a.x) * f
        map.scrollPercentY = a.y + (b.y - a.y) * f
        map.updateVisualScroll()
        AtlasActionSchedule.moves.firstOrNull { kotlin.math.abs(seconds - it.seconds) <= 1.0 }?.let {
            map.setCenterPosition(it.origin, immediately = true, selectUnit = false)
            map.clearActions()
        }
    }

    private fun visible(screen: BaseScreen, position: com.unciv.logic.map.HexCoord): Boolean {
        val scope = AtlasFixtureCatalog.dataFor(screen)
        val actor = scope.map!!.tileGroups.values.single { it.tileView.position() == position }
        val a = actor.localToStageCoordinates(Vector2())
        val b = actor.localToStageCoordinates(Vector2(actor.width, actor.height))
        return actor.isVisible && maxOf(a.x, b.x) > 0f && minOf(a.x, b.x) < screen.stage.width &&
            maxOf(a.y, b.y) > 0f && minOf(a.y, b.y) < screen.stage.height
    }

    private fun capture(path: Path) {
        val pixels = Pixmap.createFromFrameBuffer(0, 0, 1440, 900)
        try { PixmapIO.writePNG(Gdx.files.absolute(path.toString()), pixels, -1, true) } finally { pixels.dispose() }
        check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR)
    }
    private fun usedHeap() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun percentile(sorted: List<Double>, quantile: Double) = sorted[(ceil(sorted.size * quantile).toInt() - 1).coerceIn(sorted.indices)]

    /** GL API instrumentation, not PNG size or inferred graphics-memory residency. */
    private class GraphicsCounters : AutoCloseable {
        private val original = Gdx.gl20
        var drawCalls = 0; private set
        var frameAllocations = 0; private set
        var totalAllocations = 0; private set
        var frameUploadBytes = 0L; private set
        var totalUploadBytes = 0L; private set
        init {
            val proxy = Proxy.newProxyInstance(GL20::class.java.classLoader, arrayOf(GL20::class.java)) { _, method, args ->
                val arguments = args ?: emptyArray()
                if (method.name == "glDrawElements" || method.name == "glDrawArrays") drawCalls++
                if (method.name == "glGenTexture" || method.name == "glGenTextures") {
                    val count = if (method.name == "glGenTexture") 1 else arguments[0] as Int
                    frameAllocations += count; totalAllocations += count
                }
                if (method.name == "glTexImage2D") {
                    val components = when (arguments[6] as Int) { GL20.GL_RGB -> 3; GL20.GL_ALPHA, GL20.GL_LUMINANCE -> 1; GL20.GL_LUMINANCE_ALPHA -> 2; else -> 4 }
                    val bytes = (arguments[3] as Int).toLong() * (arguments[4] as Int) * components
                    frameUploadBytes += bytes; totalUploadBytes += bytes
                }
                try { method.invoke(original, *arguments) } catch (failure: InvocationTargetException) { throw failure.targetException }
            } as GL20
            Gdx.gl = proxy; Gdx.gl20 = proxy
        }
        fun resetFrame() { drawCalls = 0; frameAllocations = 0; frameUploadBytes = 0 }
        override fun close() { Gdx.gl = original; Gdx.gl20 = original }
    }
}
