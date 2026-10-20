package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.PixmapTextureData
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import kotlin.math.exp
import kotlin.math.sqrt

/** The four drawn shapes: a soft radial glow, a soft ring, a hard-edged disc and a solid rectangle. */
enum class FxShape { SOFT, RING, DISC, RECT }

/** Light adds to what is behind it (flashes, fire, tracers); everything else covers it (smoke, dust, debris). */
enum class FxBlend { NORMAL, ADD }

/** Draw order, back to front. */
enum class FxLayer { GROUND, SMOKE, BODY, LIGHT }

/** Which tile a particle belongs to. It is drawn only while that tile is seen. */
enum class FxAnchor { SOURCE, TARGET }

enum class FxEase {
    LINEAR, IN, OUT, SMOOTH;
    fun apply(t: Float): Float = when (this) {
        LINEAR -> t
        IN -> t * t
        OUT -> 1 - (1 - t) * (1 - t)
        SMOOTH -> t * t * (3 - 2 * t)
    }
}

/**
 * A path from the attacker's tile to the defender's tile; offsets are from each tile's center.
 * Particles on a flight are drawn only when the whole corridor between the tiles is seen.
 *
 * @property arc how far the middle of the path rises on screen, for lobbed stones, shells and arrows
 * @property at when set, the particle stays at this fraction of the path instead of travelling along it (for trails)
 */
class FxFlight(
    val fromX: Float = 0f, val fromY: Float = 0f,
    val toX: Float = 0f, val toY: Float = 0f,
    val arc: Float = 0f,
    val ease: FxEase = FxEase.LINEAR,
    val at: Float? = null
)

/**
 * One drawn element of a combat effect. Its look is a pure function of playback time, so a frame is reproducible:
 * fast mode, a dropped frame or a render check all see the same picture at the same moment.
 *
 * Times are fractions of the attack's playback (0 to 1). Distances are world pixels at zoom 1,
 * where neighbouring tile centers are about 69 px apart and a unit is about 40 px tall.
 * Velocities are pixels per whole playback, and accelerations pixels per playback squared; +y is up the screen.
 */
class FxParticle {
    var shape = FxShape.SOFT
    var blend = FxBlend.NORMAL
    var layer = FxLayer.BODY
    var anchor = FxAnchor.TARGET
    var start = 0f
    var life = .3f
    /** Offset from the anchor tile's center (or from the flight point) at birth. */
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var ax = 0f
    var ay = 0f
    /** Velocity decays as e^(-drag·t), so smoke and debris slow down instead of flying on. */
    var drag = 0f
    var flight: FxFlight? = null
    /** Width at birth and at death; for SOFT, RING and DISC this is the diameter. */
    var size0 = 8f
    var size1 = 8f
    var sizeEase = FxEase.OUT
    /** Height as a fraction of width, for RECT streaks, shards and blades. */
    var aspect = 1f
    /** A RECT lengthens with its speed by this many pixels per pixel-per-playback, for tracers and sparks. */
    var stretch = 0f
    var color0: Color = Color.WHITE
    var color1: Color? = null
    var alpha = 1f
    /** Fractions of the particle's life spent fading in and out. */
    var fadeIn = 0f
    var fadeOut = .5f
    /** Degrees; RECTs point along their motion instead when [alignToMotion] is set. */
    var rotation = 0f
    var spin = 0f
    var alignToMotion = false

    val end: Float get() = start + life
}

/**
 * The drawn part of one attack: the particles a weapon script built, placed every frame from the tiles' current
 * screen points (which follow panning and world wrapping) and drawn in a single pass.
 */
class CombatFx(particles: List<FxParticle>) : Actor(), AutoCloseable {
    private val particles = particles.withIndex().sortedWith(compareBy({ it.value.layer.ordinal }, { it.index })).map { it.value }
    private val count = this.particles.size
    // Per-frame state, kept in flat arrays so drawing allocates nothing.
    private val visible = BooleanArray(count)
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val pw = FloatArray(count)
    private val ph = FloatArray(count)
    private val rotation = FloatArray(count)
    private val tint = Array(count) { Color() }
    private val point = Vector2()
    private val velocity = Vector2()
    private var closed = false

    init { touchable = Touchable.disabled }
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = null

    /**
     * @param source the attacker's tile center, or null when it isn't seen
     * @param target the defender's tile center, or null when it isn't seen
     * @param flightSeen whether the corridor between them is seen, so a flight may be drawn
     */
    fun update(time: Float, source: Vector2?, target: Vector2?, flightSeen: Boolean) {
        if (closed) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            val p = particles[i]
            visible[i] = false
            val age = time - p.start
            if (age < 0f || age > p.life || p.life <= 0f) continue
            val u = age / p.life
            val flight = p.flight
            if (flight != null) {
                if (!flightSeen || source == null || target == null) continue
                val along = flight.at ?: flight.ease.apply(u)
                flightPoint(flight, along, source, target, point)
                if (flight.at == null) {
                    // The direction of travel, for streaks and arrows that point where they fly.
                    val ahead = flight.ease.apply((u + .02f).coerceAtMost(1f))
                    val behind = flight.ease.apply((u - .02f).coerceAtLeast(0f))
                    val fx = point.x; val fy = point.y
                    flightPoint(flight, ahead, source, target, point)
                    val ax = point.x; val ay = point.y
                    flightPoint(flight, behind, source, target, point)
                    velocity.set(ax - point.x, ay - point.y).scl(1f / (.04f * p.life).coerceAtLeast(1e-4f))
                    point.set(fx, fy)
                } else velocity.setZero()
            } else {
                val anchor = (if (p.anchor == FxAnchor.SOURCE) source else target) ?: continue
                point.set(anchor)
                velocity.setZero()
            }
            // Ballistic motion from the birth offset, with drag.
            val decay = if (p.drag > 0f) (1f - exp(-p.drag * age)) / p.drag else age
            val slow = if (p.drag > 0f) exp(-p.drag * age) else 1f
            point.add(p.x + p.vx * decay + .5f * p.ax * age * age, p.y + p.vy * decay + .5f * p.ay * age * age)
            velocity.add(p.vx * slow + p.ax * age, p.vy * slow + p.ay * age)

            val size = MathUtils.lerp(p.size0, p.size1, p.sizeEase.apply(u))
            val speed = velocity.len()
            val width = (size + if (p.shape == FxShape.RECT) speed * p.stretch else 0f).coerceAtLeast(0f)
            val height = if (p.shape == FxShape.RECT) (size * p.aspect).coerceAtLeast(.5f) else width
            val fadeIn = if (p.fadeIn > 0f) (u / p.fadeIn).coerceAtMost(1f) else 1f
            val fadeOut = if (p.fadeOut > 0f) ((1f - u) / p.fadeOut).coerceAtMost(1f) else 1f
            val alpha = (p.alpha * fadeIn * fadeOut).coerceIn(0f, 1f)
            if (alpha <= .003f || width <= .05f) continue
            val color = tint[i].set(p.color0)
            p.color1?.let { color.lerp(it, u) }
            color.a = alpha
            visible[i] = true
            px[i] = point.x; py[i] = point.y; pw[i] = width; ph[i] = height
            rotation[i] = if (p.alignToMotion && speed > 1e-3f) velocity.angleDeg() else p.rotation + p.spin * age
            val reach = maxOf(width, height) / 2
            minX = minOf(minX, point.x - reach); maxX = maxOf(maxX, point.x + reach)
            minY = minOf(minY, point.y - reach); maxY = maxOf(maxY, point.y + reach)
        }
        // Bounds cover what is drawn, so the map's culling keeps the effect while any of it is in view.
        if (minX <= maxX) setBounds(minX, minY, maxX - minX, maxY - minY) else setBounds(0f, 0f, 0f, 0f)
    }

    private fun flightPoint(flight: FxFlight, along: Float, source: Vector2, target: Vector2, out: Vector2) {
        val fromX = source.x + flight.fromX; val fromY = source.y + flight.fromY
        val toX = target.x + flight.toX; val toY = target.y + flight.toY
        out.set(MathUtils.lerp(fromX, toX, along), MathUtils.lerp(fromY, toY, along) + flight.arc * 4f * along * (1f - along))
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (closed || count == 0) return
        val regions = FxTextures.regions()
        val oldColor = batch.packedColor
        val oldSrc = batch.blendSrcFunc; val oldDst = batch.blendDstFunc
        val oldSrcAlpha = batch.blendSrcFuncAlpha; val oldDstAlpha = batch.blendDstFuncAlpha
        var additive = false
        try {
            for (i in 0 until count) {
                if (!visible[i]) continue
                val p = particles[i]
                val wantAdd = p.blend == FxBlend.ADD
                if (wantAdd != additive) {
                    if (wantAdd) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
                    else batch.setBlendFunctionSeparate(oldSrc, oldDst, oldSrcAlpha, oldDstAlpha)
                    additive = wantAdd
                }
                val c = tint[i]
                batch.setColor(c.r, c.g, c.b, c.a * parentAlpha * color.a)
                val w = pw[i]; val h = ph[i]
                batch.draw(regions[p.shape.ordinal], px[i] - w / 2, py[i] - h / 2, w / 2, h / 2, w, h, 1f, 1f, rotation[i])
            }
        } finally {
            if (additive) batch.setBlendFunctionSeparate(oldSrc, oldDst, oldSrcAlpha, oldDstAlpha)
            batch.packedColor = oldColor
        }
    }

    override fun close() { if (!closed) { closed = true; remove() } }
}

/**
 * The effect shapes, drawn into one small texture at first use. It is managed, so it survives a lost GL context,
 * and lives as long as the app: 16 KB is cheaper than tracking which screens still draw combat.
 */
internal object FxTextures {
    private const val CELL = 64
    private var texture: Texture? = null
    private var regions: Array<TextureRegion>? = null

    fun regions(): Array<TextureRegion> {
        regions?.let { return it }
        val pixmap = Pixmap(CELL * 4, CELL, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        val half = CELL / 2f
        for (py in 0 until CELL) for (px in 0 until CELL) {
            val dx = (px + .5f - half) / half
            val dy = (py + .5f - half) / half
            val r = sqrt(dx * dx + dy * dy)
            // A soft glow: bright core falling smoothly to nothing at the edge.
            val soft = (1f - r).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            // A ring: a soft band around 80% of the radius.
            val ring = (1f - kotlin.math.abs(r - .8f) / .16f).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
            // A disc with an antialiased edge.
            val disc = ((1f - r) * half).coerceIn(0f, 1f)
            pixmap.drawPixel(px, py, Color.rgba8888(1f, 1f, 1f, soft))
            pixmap.drawPixel(CELL + px, py, Color.rgba8888(1f, 1f, 1f, ring))
            pixmap.drawPixel(CELL * 2 + px, py, Color.rgba8888(1f, 1f, 1f, disc))
            pixmap.drawPixel(CELL * 3 + px, py, Color.rgba8888(1f, 1f, 1f, 1f))
        }
        val created = Texture(PixmapTextureData(pixmap, Pixmap.Format.RGBA8888, false, false, true))
        created.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        texture = created
        // The solid cell is sampled away from its edges, so filtering never blends in the neighbouring disc.
        return arrayOf(
            TextureRegion(created, 0, 0, CELL, CELL),
            TextureRegion(created, CELL, 0, CELL, CELL),
            TextureRegion(created, CELL * 2, 0, CELL, CELL),
            TextureRegion(created, CELL * 3 + 8, 8, CELL - 16, CELL - 16)
        ).also { regions = it }
    }
}
