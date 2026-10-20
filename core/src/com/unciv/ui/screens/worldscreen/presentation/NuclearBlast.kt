package com.unciv.ui.screens.worldscreen.presentation

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.images.ImageGetter
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A nuclear detonation drawn from circles and short segments: an incoming streak, a flash, the fireball,
 * shockwave rings across the blast radius, sparks, a rising mushroom cloud and heat that cools toward fallout.
 * Placed only at a permitted ground zero, which the timeline re-resolves every frame.
 */
class NuclearBlast(
    blastRadius: Int,
    private val reduced: Boolean,
    /** Without a drawable flight from a seen launch site, the weapon drops straight onto ground zero. */
    private val dropIn: Boolean
) : Group(), AutoCloseable {
    companion object {
        /** Distance between neighbouring tile centers on the world map. */
        val tileSpacing = TileGroupMap.groupSize * 0.8f * sqrt(3f)
        /** World tile groups are groupSize + 4 wide, so this is a tile's center from its corner. */
        private const val TILE_CENTER = TileGroupMap.groupSize / 2 + 2
        /** The moment of detonation, as a fraction of the strike's playback. */
        const val DETONATION = .24f
        /** The shockwave reaches the edge of the blast within this fraction of the time after detonation. */
        private const val SHOCK_SPAN = .38f
        private const val SHOCK_REACH = 1.12f

        /** Cap puffs as (x, y, radius) in cap widths: back row and crown first, the lit front row last. */
        private val capShape = listOf(
            Triple(-.5f, .3f, .4f), Triple(-.17f, .42f, .46f), Triple(.2f, .4f, .44f), Triple(.52f, .27f, .4f),
            Triple(0f, .62f, .38f),
            Triple(-.68f, 0f, .36f), Triple(-.36f, -.1f, .42f), Triple(0f, -.08f, .48f), Triple(.36f, -.1f, .42f),
            Triple(.68f, 0f, .36f))
        private const val CAP_FRONT_ROW = 5
    }

    /** From ground zero to the outer edge of the last ring of tiles hit. */
    private val reach = blastRadius.coerceIn(0, 6) * tileSpacing + tileSpacing / 2
    private val extent = reach * 2.2f
    private var closed = false

    // Children draw in this order: ground effects, then the fireball, the cloud, and the flash on top.
    private val heat = Glow(3)
    private val wake = Ring(48, 8f)
    private val shock = Ring(64, 3f)
    private val sparks = List(16) { dot(3f, 3f) }
    private val fireball = Glow(3)
    private val fireballCore = circle()
    private val stemHalos = List(9) { circle() }
    private val capHalos = List(capShape.size) { circle() }
    private val stemColumn = dot(1f, 1f)
    private val stem = List(9) { circle() }
    private val cap = List(capShape.size) { circle() }
    private val streakTrail = List(7) { circle() }
    private val streakHead = circle()
    private val flash = Glow(3)

    private val warm = Color(1f, .55f, .2f, 1f)
    private val smoke = Color(.44f, .41f, .4f, 1f)
    private val shade = Color(.33f, .3f, .3f, 1f)
    private val fallout = Color(.72f, .82f, .28f, 1f)
    private val scratch = Color()
    private val flightOffset = Vector2()
    private val streakPosition = Vector2()

    init {
        touchable = Touchable.disabled
        isTransform = false
        setSize(extent * 2, extent * 2)
        children.forEach { it.touchable = Touchable.disabled; it.isVisible = false }
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = null

    /** When the shockwave reaches a tile this many tiles from ground zero, as a fraction of playback. */
    fun arrival(distanceInTiles: Int): Float {
        val fraction = (distanceInTiles * tileSpacing / (reach * SHOCK_REACH)).coerceIn(0f, 1f)
        return DETONATION + SHOCK_SPAN * (1 - DETONATION) * fraction
    }

    /** [launch] is the launch site's tile, for a flight that may be drawn over seen tiles. */
    fun update(progress: Float, tile: Vector2, launch: Vector2? = null) {
        if (closed) return
        val p = progress.coerceIn(0f, 1f)
        setPosition(tile.x + TILE_CENTER - extent, tile.y + TILE_CENTER - extent)
        if (reduced) return updateReduced(p)
        updateStreak(p, launch?.let { flightOffset.set(it).sub(tile) })
        val detonated = p >= DETONATION
        val e = ((p - DETONATION) / (1 - DETONATION)).coerceIn(0f, 1f)

        // Ground heat lingers and cools toward a fallout tint.
        heat.update(0f, 0f, reach, scratch.set(warm).lerp(fallout, e), if (detonated) .2f * (1 - e).pow(1.2f) else 0f)

        val shockT = (e / SHOCK_SPAN).coerceIn(0f, 1f)
        shock.update(reach * SHOCK_REACH * easeOut(shockT), Color.WHITE, if (detonated) .95f * (1 - shockT) else 0f)
        val wakeT = (e / .5f).coerceIn(0f, 1f)
        wake.update(reach * .95f * easeOut(wakeT), warm, if (detonated) .4f * (1 - wakeT) else 0f)

        val sparkT = (e / .35f).coerceIn(0f, 1f)
        sparks.forEachIndexed { index, spark ->
            val angle = index * 360f / sparks.size + (index * 37 % 11) * 2f
            val distance = reach * (.55f + (index % 3) * .15f) * easeOut(sparkT)
            spark.color.set(1f, .85f, .5f, if (detonated) 1 - sparkT else 0f)
            spark.isVisible = detonated && sparkT < 1f
            spark.setPosition(extent + MathUtils.cosDeg(angle) * distance - 1.5f, extent + MathUtils.sinDeg(angle) * distance - 1.5f)
        }

        // The fireball swells at once, then cools, shrinks and gives way to the rising cloud.
        val swell = easeOut((e / .1f).coerceIn(0f, 1f))
        val fireAlpha = if (detonated) 1 - smooth((e - .1f) / .25f) else 0f
        val fireRadius = reach * .36f * swell * (1 - .4f * e)
        fireball.update(0f, 0f, fireRadius * 1.3f, scratch.set(1f, .5f, .15f, 1f).lerp(.75f, .2f, .08f, 1f, e * 2), .55f * fireAlpha)
        fireballCore.color.set(1f, .97f, .82f, 1f).lerp(1f, .72f, .3f, 1f, (e / .25f).coerceAtMost(1f)).a = fireAlpha
        place(fireballCore, 0f, 0f, fireRadius * .6f)

        updateCloud(e, detonated)

        val flashT = (e / .12f).coerceIn(0f, 1f)
        flash.update(0f, 0f, reach * (.9f + .8f * easeOut(flashT)), scratch.set(1f, 1f, .92f, 1f), if (detonated) .9f * (1 - flashT) else 0f)
    }

    /** Reduced motion: a still glow and ring over the blast area that hold, then fade. */
    private fun updateReduced(p: Float) {
        val alpha = if (p < .85f) 1f else (1 - p) / .15f
        heat.update(0f, 0f, reach, warm, .16f * alpha)
        shock.update(reach, Color.WHITE, .8f * alpha)
    }

    /** The incoming weapon: a glowing head and fading trail, flown in from [launch] or dropped from above. */
    private fun updateStreak(p: Float, launch: Vector2?) {
        val start = if (launch != null) 0f else .06f
        val t = ((p - start) / (DETONATION - start)).coerceIn(0f, 1f)
        val visible = p <= DETONATION && (dropIn || launch != null)
        streakHead.color.set(1f, .97f, .85f, 1f)
        streakPoint(t, launch)
        place(streakHead, streakPosition.x, streakPosition.y, if (visible && p >= start) 5.5f else 0f)
        streakTrail.forEachIndexed { index, mark ->
            val fraction = 1 - (index + 1f) / (streakTrail.size + 1)
            mark.color.set(1f, .72f, .38f, .7f * fraction)
            streakPoint((t - (index + 1) * .035f).coerceAtLeast(0f), launch)
            place(mark, streakPosition.x, streakPosition.y, if (visible && p >= start && t > 0f) 1.5f + 3f * fraction else 0f)
        }
    }

    /** Sets [streakPosition] for flight fraction [t]: an arc from the launch site, or an accelerating drop. */
    private fun streakPoint(t: Float, launch: Vector2?) {
        if (launch == null) { streakPosition.set(0f, 190f * (1 - t * t)); return }
        val arc = MathUtils.sin(t * MathUtils.PI) * (launch.len() * .25f).coerceAtMost(90f)
        streakPosition.set(launch).scl(1 - t).add(0f, arc)
    }

    private fun updateCloud(e: Float, detonated: Boolean) {
        val rise = easeOut(((e - .04f) / .55f).coerceIn(0f, 1f))
        // Solid while it stands, so overlapping puffs never show seams; it thins out only at the end.
        val alpha = if (detonated && rise > 0f) smooth((e - .03f) / .07f) * (1 - smooth((e - .72f) / .28f)) else 0f
        val cooled = (e / .55f).coerceIn(0f, 1f)
        val stemTop = reach * 1.05f * rise
        val stemRadius = reach * .085f * (.6f + .4f * rise)
        // A solid column behind the puffs keeps the stem one shape instead of a string of beads.
        stemColumn.color.set(warm).lerp(smoke, (cooled + .2f).coerceAtMost(1f)).a = alpha
        stemColumn.setSize(stemRadius * 1.5f, stemTop)
        stemColumn.setPosition(extent - stemRadius * .75f, extent)
        stemColumn.isVisible = alpha > 0f && stemTop > 1f
        stem.forEachIndexed { index, puff ->
            val height = index / (stem.size - 1f)
            scratch.set(warm).lerp(smoke, (cooled + height * .4f).coerceAtMost(1f))
            val radius = stemRadius * (1.05f + .5f * (1 - height)) * (1f + .12f * ((index * 5) % 3 - 1))
            puff(puff, stemHalos[index], 0f, stemTop * height, radius, scratch, alpha)
        }
        val capWidth = reach * (.24f + .46f * rise)
        val capY = stemTop + reach * .1f
        capShape.forEachIndexed { index, (x, y, size) ->
            // The front row keeps the fireball's glow on its underside longest; the crown cools first.
            val lit = if (index >= CAP_FRONT_ROW) .25f else -.2f
            // Neighbouring puffs differ a little in shade, so the cap reads as billows rather than discs.
            val billow = ((index * 7) % 3 - 1) * .1f
            scratch.set(warm).lerp(if (index < CAP_FRONT_ROW) shade else smoke, (cooled - lit + billow + .1f).coerceIn(0f, 1f))
            puff(cap[index], capHalos[index], x * capWidth, capY + y * capWidth * .55f, size * capWidth, scratch, alpha)
        }
    }

    /** A puff with a faint halo of its own colour behind it, which softens its edge. */
    private fun puff(body: Image, halo: Image, x: Float, y: Float, radius: Float, tint: Color, alpha: Float) {
        halo.color.set(tint).a = .3f * alpha
        place(halo, x, y, radius * 1.25f)
        body.color.set(tint).a = alpha
        place(body, x, y, radius)
    }

    /** Concentric circles at falling opacity: a soft radial glow, since no gradient image exists. */
    private inner class Glow(layers: Int) {
        private val circles = List(layers) { circle() }
        fun update(x: Float, y: Float, radius: Float, tint: Color, alpha: Float) {
            circles.forEachIndexed { index, layer ->
                layer.color.set(tint).a = alpha
                place(layer, x, y, radius * (1 - index / circles.size.toFloat()))
            }
        }
    }

    /** A circle outline made of short tangential segments, since no ring image exists. */
    private inner class Ring(count: Int, private val thickness: Float) {
        private val segments = List(count) { dot(1f, thickness) }
        fun update(radius: Float, tint: Color, alpha: Float) {
            val length = 2 * MathUtils.PI * radius / segments.size + 1.5f
            segments.forEachIndexed { index, segment ->
                val angle = index * 360f / segments.size
                segment.setSize(length, thickness)
                segment.setOrigin(length / 2, thickness / 2)
                segment.rotation = angle + 90f
                segment.color.set(tint).a = alpha
                segment.isVisible = radius > 1f && alpha > 0f
                segment.setPosition(extent + MathUtils.cosDeg(angle) * radius - length / 2,
                    extent + MathUtils.sinDeg(angle) * radius - thickness / 2)
            }
        }
    }

    private fun circle(): Image = ImageGetter.getCircle().also { addActor(it) }

    private fun dot(width: Float, height: Float): Image = ImageGetter.getWhiteDot().also {
        it.setSize(width, height)
        addActor(it)
    }

    /** Centers a circle image on (x, y) relative to ground zero; a transparent one is not drawn at all. */
    private fun place(image: Image, x: Float, y: Float, radius: Float) {
        val size = (radius * 2).coerceAtLeast(0f)
        image.setSize(size, size)
        image.setPosition(extent + x - radius, extent + y - radius)
        image.isVisible = size > 0f && image.color.a > 0f
    }

    private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t) * (1 - t)
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3 - 2 * x) }

    override fun close() { if (!closed) { closed = true; remove(); clearChildren() } }
}
