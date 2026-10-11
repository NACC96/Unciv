package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import com.unciv.ui.screens.worldscreen.WorldScreenStyle
import com.unciv.utils.Log
import kotlin.math.ceil
import kotlin.math.floor

/** Reads only this panel's backdrop at most ten times per second, then blurs on the GPU.
 *  Avoids framebuffer-to-texture copies that crash Apple's OpenGL driver. */
class AgentActivityGlass(private val actor: Actor,
    private val fallback: Drawable = WorldScreenStyle.background("WorldScreen/AgentActivityFeed",
        Color(WorldScreenStyle.surface).apply { a = 0.82f })
) : BaseDrawable(), Disposable {
    private var capture: Texture? = null
    private var horizontal: FrameBuffer? = null
    private var vertical: FrameBuffer? = null
    private var effectBatch: SpriteBatch? = null
    private var blurShader: ShaderProgram? = null
    private var glassShader: ShaderProgram? = null
    private var unavailable = false
    private val viewport = BufferUtils.newIntBuffer(4)
    private val scissor = BufferUtils.newIntBuffer(4)
    private val binding = BufferUtils.newIntBuffer(1)
    private val savedColor = Color()
    private var lastCaptureNanos = 0L
    private var lastCaptureX = -1
    private var lastCaptureY = -1
    private var lastCaptureFramebuffer = -1
    private var lastBackBufferWidth = -1
    private var lastBackBufferHeight = -1

    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        val stage = actor.stage
        if (unavailable || stage == null || width <= 0f || height <= 0f) {
            fallback.draw(batch, x, y, width, height); return
        }
        val backBufferWidth = Gdx.graphics.backBufferWidth
        val backBufferHeight = Gdx.graphics.backBufferHeight
        if (Gdx.graphics.width <= 0 || Gdx.graphics.height <= 0 || backBufferWidth <= 0 || backBufferHeight <= 0) return
        val scaleX = backBufferWidth.toFloat() / Gdx.graphics.width
        val scaleY = backBufferHeight.toFloat() / Gdx.graphics.height
        val bottom = stage.viewport.project(actor.localToStageCoordinates(Vector2()))
        val top = stage.viewport.project(actor.localToStageCoordinates(Vector2(width, height)))
        val left = floor(bottom.x * scaleX).toInt().coerceIn(0, Gdx.graphics.backBufferWidth)
        val lower = floor(bottom.y * scaleY).toInt().coerceIn(0, Gdx.graphics.backBufferHeight)
        val right = ceil(top.x * scaleX).toInt().coerceIn(left, Gdx.graphics.backBufferWidth)
        val upper = ceil(top.y * scaleY).toInt().coerceIn(lower, Gdx.graphics.backBufferHeight)
        if (right == left || upper == lower) return

        batch.flush()
        val priorShader = batch.shader
        savedColor.set(batch.color)
        viewport.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport)
        val framebuffer = getInteger(GL20.GL_FRAMEBUFFER_BINDING)
        val activeTexture = getInteger(GL20.GL_ACTIVE_TEXTURE)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        val textureBinding = getInteger(GL20.GL_TEXTURE_BINDING_2D)
        val packAlignment = getInteger(GL20.GL_PACK_ALIGNMENT)
        val unpackAlignment = getInteger(GL20.GL_UNPACK_ALIGNMENT)
        val depthMask = getInteger(GL20.GL_DEPTH_WRITEMASK) != 0
        val scissorEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST)
        scissor.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, scissor)
        val blendEnabled = Gdx.gl.glIsEnabled(GL20.GL_BLEND)
        val blendSrcRgb = getInteger(GL20.GL_BLEND_SRC_RGB)
        val blendDstRgb = getInteger(GL20.GL_BLEND_DST_RGB)
        val blendSrcAlpha = getInteger(GL20.GL_BLEND_SRC_ALPHA)
        val blendDstAlpha = getInteger(GL20.GL_BLEND_DST_ALPHA)

        fun restoreDrawingState() {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer)
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, packAlignment)
            Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, unpackAlignment)
            Gdx.gl.glDepthMask(depthMask)
            Gdx.gl.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
            if (scissorEnabled) Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST) else Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            if (blendEnabled) Gdx.gl.glEnable(GL20.GL_BLEND) else Gdx.gl.glDisable(GL20.GL_BLEND)
        }
        try {
            val resized = ensureResources(right - left, upper - lower)
            val now = System.nanoTime()
            if (resized || lastCaptureNanos == 0L || now - lastCaptureNanos >= captureIntervalNanos ||
                lastCaptureX != left || lastCaptureY != lower || lastCaptureFramebuffer != framebuffer ||
                lastBackBufferWidth != backBufferWidth || lastBackBufferHeight != backBufferHeight) {
                Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer)
                val texture = capture!!
                val pixels = Pixmap.createFromFrameBuffer(left, lower, texture.width, texture.height)
                try {
                    Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1)
                    texture.draw(pixels, 0, 0)
                } finally {
                    pixels.dispose()
                }
                // Parent clips are in screen coordinates, not these small offscreen buffers.
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
                blur(texture, horizontal!!, 2f * scaleX / texture.width, 0f)
                blur(horizontal!!.colorBufferTexture, vertical!!, 0f, 2f * scaleY / texture.height)
                lastCaptureNanos = System.nanoTime()
                lastCaptureX = left
                lastCaptureY = lower
                lastCaptureFramebuffer = framebuffer
                lastBackBufferWidth = backBufferWidth
                lastBackBufferHeight = backBufferHeight
            }
            restoreDrawingState()
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)

            batch.shader = glassShader
            glassShader!!.setUniformf("u_size", width, height)
            glassShader!!.setUniformf("u_tint", WorldScreenStyle.surface.r, WorldScreenStyle.surface.g, WorldScreenStyle.surface.b)
            batch.color = savedColor
            batch.draw(TextureRegion(vertical!!.colorBufferTexture).apply { flip(false, true) }, x, y, width, height)
            batch.flush()
        } catch (ex: RuntimeException) {
            // Older GPUs can reject the shader/FBO. Keep the feed usable and log once.
            unavailable = true
            Log.error("Activity glass unavailable; using translucent background", ex)
        } finally {
            restoreDrawingState()
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
            Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, textureBinding)
            Gdx.gl.glActiveTexture(activeTexture)
            batch.shader = priorShader
            priorShader.bind()
            batch.color = savedColor
        }
        if (unavailable) fallback.draw(batch, x, y, width, height)
    }

    private fun getInteger(parameter: Int): Int {
        binding.clear()
        Gdx.gl.glGetIntegerv(parameter, binding)
        return binding[0]
    }

    private fun ensureResources(width: Int, height: Int): Boolean {
        if (blurShader == null) {
            blurShader = ShaderProgram(vertex, blurFragment).also { check(it.isCompiled) { it.log } }
            glassShader = ShaderProgram(vertex, glassFragment).also { check(it.isCompiled) { it.log } }
            effectBatch = SpriteBatch(4, blurShader).apply { disableBlending() }
        }
        if (capture?.width == width && capture?.height == height) return false
        capture?.dispose(); horizontal?.dispose(); vertical?.dispose()
        capture = Texture(width, height, Pixmap.Format.RGBA8888).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        horizontal = FrameBuffer(Pixmap.Format.RGBA8888, maxOf(1, width / 4), maxOf(1, height / 4), false)
        vertical = FrameBuffer(Pixmap.Format.RGBA8888, maxOf(1, width / 4), maxOf(1, height / 4), false)
        for (buffer in listOf(horizontal, vertical)) buffer!!.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        return true
    }

    private fun blur(source: Texture, target: FrameBuffer, stepX: Float, stepY: Float) {
        target.begin()
        val renderer = effectBatch!!
        renderer.projectionMatrix.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
        renderer.begin()
        blurShader!!.setUniformf("u_step", stepX, stepY)
        renderer.setColor(1f, 1f, 1f, 1f)
        renderer.draw(TextureRegion(source).apply { flip(false, true) }, 0f, 0f, target.width.toFloat(), target.height.toFloat())
        renderer.end()
        target.end()
    }

    override fun dispose() {
        capture?.dispose(); horizontal?.dispose(); vertical?.dispose()
        effectBatch?.dispose(); blurShader?.dispose(); glassShader?.dispose()
        capture = null; horizontal = null; vertical = null
        effectBatch = null; blurShader = null; glassShader = null
        lastCaptureNanos = 0L
    }

    companion object {
        private const val captureIntervalNanos = 100_000_000L
        private val vertex = """
            attribute vec4 a_position;
            attribute vec4 a_color;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projTrans;
            varying vec4 v_color;
            varying vec2 v_uv;
            void main() {
                v_color = a_color;
                v_color.a *= 255.0 / 254.0;
                v_uv = a_texCoord0;
                gl_Position = u_projTrans * a_position;
            }
        """.trimIndent()
        private val blurFragment = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_uv;
            uniform sampler2D u_texture;
            uniform vec2 u_step;
            void main() {
                vec3 c = texture2D(u_texture, v_uv).rgb * 0.227027;
                c += texture2D(u_texture, v_uv + u_step * 1.384615).rgb * 0.316216;
                c += texture2D(u_texture, v_uv - u_step * 1.384615).rgb * 0.316216;
                c += texture2D(u_texture, v_uv + u_step * 3.230769).rgb * 0.070270;
                c += texture2D(u_texture, v_uv - u_step * 3.230769).rgb * 0.070270;
                gl_FragColor = vec4(c, 1.0) * v_color;
            }
        """.trimIndent()
        private val glassFragment = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_uv;
            uniform sampler2D u_texture;
            uniform vec2 u_size;
            uniform vec3 u_tint;
            void main() {
                vec2 p = abs((v_uv - 0.5) * u_size) - (u_size * 0.5 - 12.0);
                float edge = length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - 12.0;
                float mask = 1.0 - smoothstep(-0.6, 0.6, edge);
                vec3 c = mix(texture2D(u_texture, v_uv).rgb * 0.9, u_tint, 0.78);
                float rim = smoothstep(-1.4, -0.4, edge) * 0.16;
                c = mix(c, vec3(0.85, 0.91, 0.93), rim);
                gl_FragColor = vec4(c, mask * v_color.a);
            }
        """.trimIndent()
    }
}
