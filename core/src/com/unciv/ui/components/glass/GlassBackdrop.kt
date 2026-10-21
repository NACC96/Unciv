package com.unciv.ui.components.glass

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import com.unciv.UncivGame
import com.unciv.ui.components.GameUiStyle
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Log

/** Frosted glass for the world screen's floating surfaces.
 *
 *  While any glass surface was drawn in the previous frames, the map is rendered into an offscreen
 *  buffer instead of the screen. That buffer is then copied to the screen and reduced to an
 *  eighth of its size with two Gaussian passes; glass surfaces sample that blur under their own
 *  rectangle ([GlassSurface]). Everything happens on the GPU: there is no pixel readback (it cost
 *  about 5 ms per refresh) and no framebuffer-to-texture copy (it crashed Apple's OpenGL driver).
 *
 *  Without a glass surface on screen, the map draws straight to the screen as before. Any GL
 *  failure disables the effect for the session and surfaces use their translucent fill. */
class GlassBackdrop : Disposable {
    private var scene: FrameBuffer? = null
    /** Successive halvings of [scene]: ½, ¼, ⅛. Each step averages 2×2 texels, so the ⅛ copy is stable while panning. */
    private val chain = arrayOfNulls<FrameBuffer>(3)
    private var ping: FrameBuffer? = null
    private var effectBatch: SpriteBatch? = null
    private var blurShader: ShaderProgram? = null
    private var copyShader: ShaderProgram? = null
    internal var glassShader: ShaderProgram? = null
        private set
    private var failed = false
    private var capturedFrame = -1L
    private var requestedFrame = Long.MIN_VALUE / 2

    private val ints = BufferUtils.newIntBuffer(16)
    /** The stage viewport (GL pixels) the map was drawn under, for mapping surfaces onto the blur. */
    private val viewport = IntArray(4)
    private var bufferWidth = 0
    private var bufferHeight = 0
    private val corner = Vector3()

    /** Glass is wanted: the setting is on, the skin is Atlas and the GPU has not refused it. */
    val enabled get() = !failed && GameUiStyle.isAtlas && UncivGame.Current.settings.frostedGlass

    /** Capture while glass surfaces keep asking; a newly shown surface asks once, draws its plain
     *  fill for that frame and requests the next one. */
    private fun wanted() = enabled && Gdx.graphics.frameId - requestedFrame <= 2

    /** Draws [content] (the map) through the capture buffer when glass is on screen, else directly.
     *  Must be called while [batch] is drawing; it is drawing again on return. */
    fun drawCaptured(batch: Batch, content: () -> Unit) {
        active = this
        if (!wanted()) { content(); return }
        batch.end()
        val framebuffer = integer(GL20.GL_FRAMEBUFFER_BINDING)
        ints.clear(); Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, ints)
        for (i in 0..3) viewport[i] = ints[i]
        // The buffer spans the stage viewport from the target's origin, so presenting it is a 1:1 copy
        // whatever the target is (the window's back buffer, or a capture buffer in render checks).
        val width = viewport[0] + viewport[2]
        val height = viewport[1] + viewport[3]
        val ready = viewport[2] > 0 && viewport[3] > 0 &&
            try { ensureResources(width, height); true } catch (ex: RuntimeException) { fail(ex); false }
        if (!ready) { batch.begin(); content(); return }
        try {
            scene!!.bind()
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            val clear = BaseScreen.clearColor
            Gdx.gl.glClearColor(clear.r, clear.g, clear.b, clear.a)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            batch.begin()
            try { content() } finally { batch.end() }
        } finally {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer)
        }
        try {
            present(width, height)
            blur()
            capturedFrame = Gdx.graphics.frameId
        } catch (ex: RuntimeException) {
            fail(ex)
        } finally {
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer)
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }
        batch.begin()
    }

    /** This frame's blur for a surface about to draw, or null to draw the plain fill. */
    internal fun sample(): Texture? {
        val frame = Gdx.graphics.frameId
        val askedRecently = requestedFrame >= frame - 1
        requestedFrame = frame
        if (enabled && capturedFrame == frame) return chain[2]?.colorBufferTexture
        // A surface that just appeared gets one more frame, now with a capture. Never a loop:
        // if the map still isn't captured (e.g. hidden), surfaces simply stay plain.
        if (enabled && !askedRecently) Gdx.graphics.requestRendering()
        return null
    }

    /** Texture coordinate of stage point ([x], [y]) as drawn by [batch], into [out] (x = u, y = v). */
    internal fun textureCoordinate(batch: Batch, x: Float, y: Float, out: Vector3): Vector3 {
        corner.set(x, y, 0f).mul(batch.transformMatrix).prj(batch.projectionMatrix)
        val pixelX = viewport[0] + (corner.x + 1f) / 2f * viewport[2]
        val pixelY = viewport[1] + (corner.y + 1f) / 2f * viewport[3]
        return out.set(pixelX / bufferWidth, pixelY / bufferHeight, 0f)
    }

    /** Copies the captured map onto the screen, opaque, pixel for pixel. */
    private fun present(width: Int, height: Int) {
        Gdx.gl.glViewport(0, 0, width, height)
        draw(scene!!.colorBufferTexture, copyShader!!, width, height)
    }

    private fun blur() {
        var source: Texture = scene!!.colorBufferTexture
        for (target in chain) {
            target!!.bind()
            Gdx.gl.glViewport(0, 0, target.width, target.height)
            draw(source, copyShader!!, target.width, target.height)
            source = target.colorBufferTexture
        }
        val last = chain[2]!!
        val temp = ping!!
        // Two separable passes, twice: a wide, smooth frost from a 9-tap kernel.
        repeat(2) {
            gaussian(last.colorBufferTexture, temp, 1f / last.width, 0f)
            gaussian(temp.colorBufferTexture, last, 0f, 1f / last.height)
        }
    }

    private fun gaussian(source: Texture, target: FrameBuffer, stepX: Float, stepY: Float) {
        target.bind()
        Gdx.gl.glViewport(0, 0, target.width, target.height)
        val shader = blurShader!!
        draw(source, shader, target.width, target.height) { shader.setUniformf("u_step", stepX, stepY) }
    }

    private fun draw(source: Texture, shader: ShaderProgram, width: Int, height: Int, uniforms: () -> Unit = {}) {
        val batch = effectBatch!!
        batch.shader = shader
        batch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        batch.begin()
        uniforms()
        batch.setColor(1f, 1f, 1f, 1f)
        batch.draw(source, 0f, 0f, width.toFloat(), height.toFloat(), 0f, 0f, 1f, 1f)
        batch.end()
    }

    private fun ensureResources(width: Int, height: Int) {
        if (effectBatch == null) {
            copyShader = compile(vertex, copyFragment)
            blurShader = compile(vertex, blurFragment)
            glassShader = compile(vertex, glassFragment)
            effectBatch = SpriteBatch(4).apply { disableBlending() }
        }
        if (scene?.width == width && scene?.height == height) return
        disposeBuffers()
        scene = buffer(width, height)
        var w = width; var h = height
        for (i in chain.indices) {
            w = maxOf(1, w / 2); h = maxOf(1, h / 2)
            chain[i] = buffer(w, h)
        }
        ping = buffer(w, h)
        bufferWidth = width; bufferHeight = height
    }

    private fun buffer(width: Int, height: Int) = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false).also {
        it.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        it.colorBufferTexture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
    }

    private fun compile(vertex: String, fragment: String) = ShaderProgram(vertex, fragment).also {
        check(it.isCompiled) { it.log }
    }

    private fun integer(parameter: Int): Int {
        ints.clear()
        Gdx.gl.glGetIntegerv(parameter, ints)
        return ints[0]
    }

    private fun fail(ex: RuntimeException) {
        if (failed) return
        failed = true
        capturedFrame = -1L
        Log.error("Frosted glass unavailable; panels use their translucent fill", ex)
    }

    private fun disposeBuffers() {
        scene?.dispose(); scene = null
        for (i in chain.indices) { chain[i]?.dispose(); chain[i] = null }
        ping?.dispose(); ping = null
        capturedFrame = -1L
    }

    override fun dispose() {
        disposeBuffers()
        effectBatch?.dispose(); effectBatch = null
        copyShader?.dispose(); blurShader?.dispose(); glassShader?.dispose()
        copyShader = null; blurShader = null; glassShader = null
        if (active === this) active = null
    }

    companion object {
        /** The backdrop of the screen currently drawing, if it captures one. */
        var active: GlassBackdrop? = null

        private val vertex = """
            attribute vec4 ${ShaderProgram.POSITION_ATTRIBUTE};
            attribute vec4 ${ShaderProgram.COLOR_ATTRIBUTE};
            attribute vec2 ${ShaderProgram.TEXCOORD_ATTRIBUTE}0;
            uniform mat4 u_projTrans;
            varying vec4 v_color;
            varying vec2 v_uv;
            void main() {
                v_color = ${ShaderProgram.COLOR_ATTRIBUTE};
                v_color.a *= 255.0 / 254.0;
                v_uv = ${ShaderProgram.TEXCOORD_ATTRIBUTE}0;
                gl_Position = u_projTrans * ${ShaderProgram.POSITION_ATTRIBUTE};
            }
        """.trimIndent()

        private val copyFragment = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_uv;
            uniform sampler2D u_texture;
            void main() { gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb, 1.0); }
        """.trimIndent()

        /** 9-tap Gaussian using linear sampling between texel pairs. */
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
                gl_FragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        /** Rounded-rectangle mask over the blurred map, slightly dimmed with a touch of extra
         *  saturation (vibrancy), then the surface tint, a faint top sheen and dithering so the
         *  dark gradients do not band.
         *  u_uv maps the quad's texture range back to local surface units for the mask. */
        private val glassFragment = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_uv;
            uniform sampler2D u_texture;
            uniform vec4 u_uv;
            uniform vec2 u_size;
            uniform float u_radius;
            uniform float u_aa;
            uniform vec4 u_tint;
            void main() {
                vec2 local = (v_uv - u_uv.xy) / (u_uv.zw - u_uv.xy) * u_size;
                vec2 q = abs(local - 0.5 * u_size) - (0.5 * u_size - u_radius);
                float edge = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - u_radius;
                float mask = 1.0 - smoothstep(-u_aa, u_aa, edge);
                vec3 back = texture2D(u_texture, v_uv).rgb * 0.88;
                float luma = dot(back, vec3(0.299, 0.587, 0.114));
                back = mix(vec3(luma), back, 1.12);
                vec3 c = mix(back, u_tint.rgb, u_tint.a);
                c += 0.035 * smoothstep(0.55, 1.0, local.y / u_size.y);
                float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                c += (noise - 0.5) / 128.0;
                gl_FragColor = vec4(c, mask * v_color.a);
            }
        """.trimIndent()
    }
}
