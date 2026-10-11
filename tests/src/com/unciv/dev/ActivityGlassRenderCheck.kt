package com.unciv.dev

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.unciv.ui.screens.worldscreen.status.AgentActivityGlass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.ceil

/** Optional real-GPU regression check. No game or external agent is started. */
object ActivityGlassRenderCheck {
    @JvmStatic fun main(args: Array<String>) {
        var failure: Throwable? = null
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("Unciv glass render check")
            setWindowedMode(320, 240)
            setInitialVisible(false)
            setForegroundFPS(60)
            disableAudio(true)
        }
        Lwjgl3Application(object : ApplicationAdapter() {
            private lateinit var stage: Stage
            private lateinit var mapTexture: Texture
            private lateinit var whiteTexture: Texture
            private lateinit var panel: Actor
            private lateinit var glass: AgentActivityGlass
            private lateinit var originalGl: GL20
            private lateinit var checkedGl: GL20
            private var unsafeCopies = 0
            private var captures = 0
            private var takingEvidence = false
            private var frame = 0
            private var phaseCaptures = 0
            private var phaseStart = 0L
            private var maxStableRate = 0f
            private var changedBackdropNanos = 0L
            private var capturesBeforeBackdropChange = 0
            private val ints = BufferUtils.newIntBuffer(4)

            override fun create() {
                originalGl = Gdx.gl20
                checkedGl = Proxy.newProxyInstance(GL20::class.java.classLoader,
                    arrayOf(GL20::class.java)) { _, method, parameters ->
                    if (method.name == "glCopyTexSubImage2D") {
                        unsafeCopies++
                        // Never forward this known macOS driver crash path, including in the red run.
                        throw IllegalStateException("Unsafe framebuffer texture copy is prohibited")
                    }
                    if (method.name == "glReadPixels" && !takingEvidence) captures++
                    try { method.invoke(originalGl, *(parameters ?: emptyArray())) }
                    catch (ex: InvocationTargetException) { throw ex.targetException }
                } as GL20
                Gdx.gl = checkedGl
                Gdx.gl20 = checkedGl
                stage = Stage(ScreenViewport())
                stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
                val map = Pixmap(320, 240, Pixmap.Format.RGBA8888)
                for (y in 0 until 240) for (x in 0 until 320) {
                    val stripe = if ((x / 2) % 2 == 0) 1f else 0.2f
                    map.setColor(if (y < 120) Color(stripe, 0f, 0f, 1f) else Color(0f, 0f, stripe, 1f))
                    map.drawPixel(x, y)
                }
                mapTexture = Texture(map)
                map.dispose()
                val white = Pixmap(1, 1, Pixmap.Format.RGBA8888).apply { setColor(Color.WHITE); fill() }
                whiteTexture = Texture(white)
                white.dispose()
                stage.addActor(object : Actor() {
                    override fun draw(batch: Batch, parentAlpha: Float) {
                        batch.color = Color.WHITE
                        batch.draw(mapTexture, 0f, 0f, stage.width, stage.height)
                    }
                })
                panel = object : Actor() {
                    override fun draw(batch: Batch, parentAlpha: Float) {
                        batch.setColor(0.85f, 0.9f, 0.95f, if (frame == 0 || frame >= 160) 1f else 0.72f)
                        batch.flush()
                        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 8)
                        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 8)
                        Gdx.gl.glDepthMask(true)
                        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
                        Gdx.gl.glScissor(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
                        if (frame % 2 == 0) Gdx.gl.glEnable(GL20.GL_BLEND) else Gdx.gl.glDisable(GL20.GL_BLEND)
                        Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ZERO)
                        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
                        val textureZeroBefore = values(GL20.GL_TEXTURE_BINDING_2D, 1)
                        whiteTexture.bind(1)
                        val colorBefore = Color(batch.color)
                        val shaderBefore = batch.shader
                        val viewportBefore = values(GL20.GL_VIEWPORT, 4)
                        val framebufferBefore = values(GL20.GL_FRAMEBUFFER_BINDING, 1)
                        val textureBefore = values(GL20.GL_TEXTURE_BINDING_2D, 1)
                        val activeTextureBefore = values(GL20.GL_ACTIVE_TEXTURE, 1)
                        val scissorBefore = values(GL20.GL_SCISSOR_BOX, 4)
                        val blendBefore = Gdx.gl.glIsEnabled(GL20.GL_BLEND)
                        val blendFactorsBefore = blendFactors()
                        glass.draw(batch, x, y, width, height)
                        check(unsafeCopies == 0) { "GLASS_NO_COPY_CONTRACT_FAILED: $unsafeCopies unsafe copy calls intercepted before reaching driver" }
                        check(batch.shader === shaderBefore && batch.color == colorBefore) {
                            "Glass changed the caller's shader or color/alpha on frame $frame"
                        }
                        check(values(GL20.GL_VIEWPORT, 4).contentEquals(viewportBefore)) { "Glass leaked the viewport on frame $frame" }
                        check(values(GL20.GL_FRAMEBUFFER_BINDING, 1).contentEquals(framebufferBefore)) { "Glass leaked its framebuffer on frame $frame" }
                        check(values(GL20.GL_ACTIVE_TEXTURE, 1).contentEquals(activeTextureBefore)) { "Glass leaked its active texture unit on frame $frame" }
                        check(values(GL20.GL_TEXTURE_BINDING_2D, 1).contentEquals(textureBefore)) { "Glass leaked its texture binding on frame $frame" }
                        check(values(GL20.GL_PACK_ALIGNMENT, 1)[0] == 8 && values(GL20.GL_UNPACK_ALIGNMENT, 1)[0] == 8) { "Glass leaked pixel alignment on frame $frame" }
                        check(values(GL20.GL_DEPTH_WRITEMASK, 1)[0] == 1) { "Glass leaked depth write mask on frame $frame" }
                        check(Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST) && values(GL20.GL_SCISSOR_BOX, 4).contentEquals(scissorBefore)) { "Glass leaked scissor state on frame $frame" }
                        check(Gdx.gl.glIsEnabled(GL20.GL_BLEND) == blendBefore && blendFactors().contentEquals(blendFactorsBefore)) { "Glass leaked separate RGB/alpha blend state on frame $frame" }
                        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
                        check(values(GL20.GL_TEXTURE_BINDING_2D, 1).contentEquals(textureZeroBefore)) { "Glass leaked texture unit zero binding on frame $frame" }
                        // Return the fixture to SpriteBatch's expected state before drawing the next actor.
                        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1)
                        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1)
                        Gdx.gl.glDepthMask(false)
                        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
                    }
                }.apply { setBounds(40f, 20f, 200f, 200f) }
                glass = AgentActivityGlass(panel, BaseDrawable())
                stage.addActor(panel)
                stage.addActor(object : Actor() {
                    override fun draw(batch: Batch, parentAlpha: Float) {
                        batch.setColor(0f, 1f, 0f, 1f)
                        batch.draw(whiteTexture, 260f, 0f, 20f, 20f)
                        batch.setColor(1f, 0f, 0f, 0.5f)
                        batch.draw(whiteTexture, 285f, 0f, 10f, 20f)
                    }
                })
                phaseStart = System.nanoTime()
            }

            private fun values(name: Int, count: Int): IntArray {
                ints.clear()
                Gdx.gl.glGetIntegerv(name, ints)
                return IntArray(count) { ints[it] }
            }

            private fun blendFactors() = intArrayOf(
                values(GL20.GL_BLEND_SRC_RGB, 1)[0], values(GL20.GL_BLEND_DST_RGB, 1)[0],
                values(GL20.GL_BLEND_SRC_ALPHA, 1)[0], values(GL20.GL_BLEND_DST_ALPHA, 1)[0])

            override fun resize(width: Int, height: Int) {
                if (::stage.isInitialized) stage.viewport.update(width, height, true)
            }

            override fun render() {
                if (failure != null) return
                try {
                    // LWJGL reselects its native global GL delegate when a window changes size.
                    Gdx.gl = checkedGl
                    Gdx.gl20 = checkedGl
                    if (frame % 20 == 0) {
                        if (frame != 0) {
                            val seconds = (System.nanoTime() - phaseStart) / 1e9
                            val count = captures - phaseCaptures
                            if (frame <= 100) {
                                check(count <= 2 + ceil(seconds * 12).toInt()) {
                                    "Backdrop capture was not bounded: $count reads in $seconds seconds"
                                }
                                maxStableRate = maxOf(maxStableRate, (count / seconds).toFloat())
                            }
                        }
                        phaseCaptures = captures
                        phaseStart = System.nanoTime()
                    }
                    when (frame) {
                        20 -> panel.setBounds(-40f, 20f, 260f, 200f)
                        40 -> panel.setBounds(30f, 200f, 210f, 160f)
                        60 -> panel.setBounds(330f, 10f, 200f, 160f)
                        80 -> panel.setBounds(10f, 10f, 1f, 1f)
                        100 -> panel.setBounds(30f, 20f, 200f, 200f)
                        120 -> {
                            glass.dispose()
                            check(Gdx.graphics.setWindowedMode(960, 720)) { "Large window resize was rejected" }
                            stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
                            panel.setBounds(20f, 35f, 300f, 500f)
                        }
                        140 -> {
                            glass.dispose()
                            glass = AgentActivityGlass(panel, BaseDrawable())
                            panel.setBounds(40f, 20f, 200f, 200f)
                        }
                        160 -> {
                            val changed = Pixmap(320, 240, Pixmap.Format.RGBA8888)
                            for (y in 0 until 240) for (x in 0 until 320) {
                                val stripe = if ((x / 2) % 2 == 0) 1f else 0.2f
                                changed.setColor(if (y < 120) Color(0f, 0f, stripe, 1f) else Color(stripe, 0f, 0f, 1f))
                                changed.drawPixel(x, y)
                            }
                            mapTexture.draw(changed, 0, 0)
                            changed.dispose()
                            capturesBeforeBackdropChange = captures
                            changedBackdropNanos = System.nanoTime()
                        }
                    }
                    if (frame in 100..117 && frame % 3 == 1) {
                        val size = when ((frame - 100) / 3 % 3) {
                            0 -> 480 to 360
                            1 -> 640 to 480
                            else -> 320 to 240
                        }
                        check(Gdx.graphics.setWindowedMode(size.first, size.second)) { "Window resize was rejected" }
                        stage.viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)
                    }
                    Gdx.gl = checkedGl
                    Gdx.gl20 = checkedGl
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                    stage.act(1f / 60f)
                    stage.draw()
                    check(Gdx.gl.glGetError() == GL20.GL_NO_ERROR) { "Glass rendering leaked a GL error on frame $frame" }
                    if (frame == 0) verifyImage(args.firstOrNull())
                    frame++
                    if (frame >= 180 && System.nanoTime() - changedBackdropNanos >= 220_000_000L) {
                        check(captures > 2) { "Blur backdrop was never refreshed through CPU readback" }
                        check(captures > capturesBeforeBackdropChange) { "Changing map content was never recaptured after the refresh interval" }
                        verifyBackdropChange()
                        println("GLASS_RENDER_CHECK_PASSED: $frame normal render frames; blur/tint/orientation; alpha/color/shader/viewport/framebuffer/texture/scissor/alignment/depth/blend preservation; clipped/offscreen/tiny panels; 7 window resizes including 960x720 with 300x500 feed; resource recreation; backdrop content refresh; unsafeCopies=$unsafeCopies captures=$captures maxStableReadsPerSecond=$maxStableRate")
                        Gdx.app.exit()
                    }
                } catch (ex: Throwable) {
                    failure = ex
                    Gdx.app.exit()
                }
            }

            private fun verifyImage(path: String?) {
                takingEvidence = true
                val pixels = try { Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight) }
                    finally { takingEvidence = false }
                try {
                    fun pixel(x: Int, y: Int) = Color(pixels.getPixel(x * pixels.width / 320, y * pixels.height / 240))
                    val upper = pixel(120, 175)
                    val lower = pixel(120, 65)
                    check(upper.r > upper.b && lower.b > lower.r) { "Backdrop orientation must follow the map: $upper / $lower" }
                    check(upper.r < 0.5f && upper.r > 0.11f) { "Translucent tint should retain map color: $upper" }
                    val neighboring = pixel(122, 175)
                    check(abs(upper.r - neighboring.r) < 0.08f) { "Map stripes should be blurred: $upper / $neighboring" }
                    check(pixel(288, 175).r > 0.5f) { "The map outside the panel must stay sharp" }
                    val green = pixel(270, 10)
                    check(green.g > 0.9f && green.r < 0.01f) { "Later actors lost their texture/color: $green" }
                    val alpha = pixel(288, 10)
                    check(alpha.r in 0.45f..0.55f && alpha.b in 0.45f..0.55f) { "Later actors lost alpha blending: $alpha" }
                    if (path != null) PixmapIO.writePNG(Gdx.files.absolute(path), pixels)
                } finally { pixels.dispose() }
            }

            private fun verifyBackdropChange() {
                takingEvidence = true
                val pixels = try { Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight) }
                    finally { takingEvidence = false }
                try {
                    val x = (140f / stage.width * pixels.width).toInt()
                    val y = (120f / stage.height * pixels.height).toInt()
                    val color = Color(pixels.getPixel(x, y))
                    check(color.r > color.b) { "Glass still displays the old blue backdrop after the map turned red: $color" }
                } finally { pixels.dispose() }
            }

            override fun dispose() {
                if (::glass.isInitialized) glass.dispose()
                if (::mapTexture.isInitialized) mapTexture.dispose()
                if (::whiteTexture.isInitialized) whiteTexture.dispose()
                if (::stage.isInitialized) stage.dispose()
                if (::originalGl.isInitialized) {
                    Gdx.gl = originalGl
                    Gdx.gl20 = originalGl
                }
            }
        }, config)
        failure?.let { throw it }
    }
}
