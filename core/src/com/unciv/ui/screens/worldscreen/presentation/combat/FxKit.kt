package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils

/*
 * Building blocks shared by every weapon script. Each takes a time in nominal seconds and an offset from a tile's
 * center, and adds particles to the scene. Scripts combine and tune these rather than drawing from scratch,
 * so a cannon's smoke and a tank's smoke look like the same smoke.
 */

/** A muzzle flash pointing at the defender: a hot core, a short flame tongue and a few sparks. */
fun FxScene.muzzleFlash(at: Float, along: Float = 12f, side: Float = 0f, scale: Float = 1f, anchor: FxAnchor = FxAnchor.SOURCE) {
    val x = ox(along, side); val y = oy(along, side)
    particle(anchor, at, .09f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 22f * scale; size1 = 10f * scale; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
    }
    particle(anchor, at, .07f) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x + ox(7f * scale); this.y = y + oy(7f * scale)
        size0 = 15f * scale; size1 = 8f * scale; aspect = .38f; rotation = angle
        color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .7f
    }
    repeat(2) {
        val spreadAngle = angle + spread(25f)
        particle(anchor, at, .1f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(spreadAngle) * 180f * scale; vy = MathUtils.sinDeg(spreadAngle) * 180f * scale; drag = 6f
            size0 = 2f; size1 = 1.5f; aspect = .5f; stretch = .03f; alignToMotion = true
            color0 = FxPalette.TRACER; fadeOut = .6f
        }
    }
}

/** Powder smoke from a gun mouth, pushed toward the defender and rising. */
fun FxScene.gunSmoke(at: Float, along: Float = 12f, side: Float = 0f, scale: Float = 1f, puffs: Int = 3, anchor: FxAnchor = FxAnchor.SOURCE) {
    repeat(puffs) { index ->
        val push = rand(25f, 55f) * scale
        particle(anchor, at + index * .015f, rand(.55f, .8f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(along + index * 3f, side) + spread(3f); y = oy(along + index * 3f, side) + spread(3f)
            vx = dir.x * push + spread(12f); vy = dir.y * push + rand(14f, 26f); drag = 2.2f
            size0 = 9f * scale; size1 = rand(22f, 30f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .04f); alpha = .62f; fadeIn = .08f; fadeOut = .75f
        }
    }
}

/**
 * A blast: flash, fireball, shock ring, sparks, debris, then rising smoke and a scorch mark.
 * [scale] 1 suits a cannon shell; .6 a grenade-sized hit, 1.6 a heavy shell or bomb.
 */
fun FxScene.explosion(
    at: Float, x: Float = 0f, y: Float = 0f, scale: Float = 1f, anchor: FxAnchor = FxAnchor.TARGET,
    water: Boolean = false, smoke: Boolean = true, debris: Color = FxPalette.DEBRIS
) {
    particle(anchor, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 18f * scale; size1 = 58f * scale; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(anchor, at, .18f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 12f * scale; size1 = 78f * scale; color0 = FxPalette.FLASH; alpha = .45f; fadeOut = 1f
    }
    repeat((4 * scale).toInt().coerceAtLeast(3)) {
        particle(anchor, at + rand(0f, .03f), rand(.22f, .32f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(7f * scale); this.y = y + spread(5f * scale)
            vx = spread(30f * scale); vy = rand(10f, 40f) * scale; drag = 4f
            size0 = rand(12f, 18f) * scale; size1 = rand(26f, 36f) * scale
            color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .95f; fadeOut = .65f
        }
    }
    repeat((9 * scale).toInt().coerceAtLeast(5)) {
        val heading = rand(0f, 360f)
        val speed = rand(140f, 260f) * scale
        particle(anchor, at, rand(.22f, .38f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed * .8f + 40f; ay = -320f; drag = 1.8f
            size0 = 2.5f; size1 = 1.5f; aspect = .55f; stretch = .03f; alignToMotion = true
            color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .5f
        }
    }
    if (water) splash(at, x, y, scale * .9f, anchor)
    else repeat((6 * scale).toInt().coerceAtLeast(3)) {
        val heading = rand(15f, 165f)
        val speed = rand(90f, 190f) * scale
        particle(anchor, at, rand(.38f, .55f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(4f); this.y = y
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed + 60f; ay = -760f; drag = .6f
            size0 = rand(2.5f, 4.5f) * scale.coerceAtMost(1.4f); size1 = size0; aspect = rand(.5f, .9f)
            rotation = rand(0f, 360f); spin = spread(900f); color0 = debris; fadeIn = 0f; fadeOut = .25f
        }
    }
    if (smoke) repeat((4 * scale).toInt().coerceAtLeast(3)) { index ->
        particle(anchor, at + .05f + index * .03f, rand(.6f, .85f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(9f * scale); this.y = y + spread(4f * scale)
            vx = spread(14f) + 6f; vy = rand(30f, 52f) * scale; drag = 1.4f
            size0 = rand(12f, 18f) * scale; size1 = rand(32f, 44f) * scale
            color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE); alpha = .68f; fadeIn = .12f; fadeOut = .6f
        }
    }
    // Effects draw over the units, so the scorch lies flat at their feet rather than darkening them.
    if (!water) particle(anchor, at + .02f, end - at - .02f) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y - 9f; size0 = 16f * scale; size1 = 34f * scale; sizeEase = FxEase.OUT; aspect = .45f
        color0 = FxPalette.SCORCH; alpha = .32f; fadeIn = .1f; fadeOut = .45f
    }
}

/** Water thrown up by a hit or a miss at sea: a white column, droplets, foam and a spreading ripple. */
fun FxScene.splash(at: Float, x: Float = 0f, y: Float = 0f, scale: Float = 1f, anchor: FxAnchor = FxAnchor.TARGET) {
    // The column is soft spray that slows, spreads and falls away; a solid bar would show hard edges over a ship.
    repeat(if (scale < .7f) 2 else 3) { index ->
        particle(anchor, at + index * .015f, rand(.4f, .5f)) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(2f * scale); this.y = y + 3f * scale
            vx = spread(8f * scale); vy = (90f + index * 70f) * scale; ay = -120f * scale; drag = 4.5f
            size0 = (13f - index * 2f) * scale; size1 = (24f - index * 4f) * scale; aspect = 1.25f
            color0 = FxPalette.WATER; alpha = .85f; fadeOut = .6f
        }
    }
    repeat((10 * scale).toInt().coerceAtLeast(5)) {
        particle(anchor, at, rand(.35f, .5f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(5f * scale); this.y = y
            vx = spread(55f * scale); vy = rand(150f, 260f) * scale; ay = -900f
            size0 = rand(2.5f, 4f); size1 = 2f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .3f
        }
    }
    particle(anchor, at + .03f, .55f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y - 2f; size0 = 10f * scale; size1 = 52f * scale
        color0 = FxPalette.WATER; alpha = .55f; fadeOut = .8f
    }
    particle(anchor, at + .02f, .5f) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y - 2f; size0 = 14f * scale; size1 = 30f * scale
        color0 = FxPalette.WATER; alpha = .45f; fadeIn = .1f; fadeOut = .7f
    }
}

/** Dust kicked up along the ground: by a charge, a stone landing, or a shot going into the dirt. */
fun FxScene.dust(at: Float, x: Float = 0f, y: Float = 0f, scale: Float = 1f, anchor: FxAnchor = FxAnchor.TARGET,
                 color: Color = FxPalette.DUST, puffs: Int = 5) {
    repeat(puffs) {
        val heading = rand(0f, 360f)
        val speed = rand(30f, 70f) * scale
        particle(anchor, at + rand(0f, .04f), rand(.4f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f); this.y = y - 3f + spread(2f)
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed * .35f + 12f; drag = 3f
            size0 = rand(7f, 10f) * scale; size1 = rand(18f, 26f) * scale
            color0 = vary(color, .05f); alpha = .55f; fadeIn = .1f; fadeOut = .7f
        }
    }
}

/** Sparks thrown off metal on metal, fanned around [heading] degrees. */
fun FxScene.sparks(at: Float, x: Float = 0f, y: Float = 0f, count: Int = 6, speed: Float = 160f, heading: Float = 90f,
                   fan: Float = 180f, anchor: FxAnchor = FxAnchor.TARGET, color: Color = FxPalette.STEEL) {
    repeat(count) {
        val direction = heading + spread(fan / 2)
        val velocity = rand(.6f, 1.1f) * speed
        particle(anchor, at + rand(0f, .02f), rand(.14f, .24f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(direction) * velocity; vy = MathUtils.sinDeg(direction) * velocity; ay = -400f; drag = 2.5f
            size0 = 2f; size1 = 1.2f; aspect = .5f; stretch = .035f; alignToMotion = true
            color0 = color; color1 = FxPalette.FIRE; fadeOut = .6f
        }
    }
}

/** A blade's swing: a bright arc swept around (x, y), [sweep] degrees starting at [from] degrees. */
fun FxScene.slash(at: Float, x: Float = 0f, y: Float = 0f, radius: Float = 14f, from: Float = 120f, sweep: Float = -120f,
                  anchor: FxAnchor = FxAnchor.TARGET, color: Color = FxPalette.STEEL) {
    val segments = 7
    repeat(segments) { index ->
        val angleAt = from + sweep * index / (segments - 1)
        particle(anchor, at + index * .01f, .1f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + MathUtils.cosDeg(angleAt) * radius; this.y = y + MathUtils.sinDeg(angleAt) * radius
            size0 = radius * .55f; size1 = radius * .3f; aspect = .14f; rotation = angleAt + 90f
            color0 = color; alpha = .9f; fadeOut = .8f
        }
    }
}

/** Flames licking up from a burning tile, with dark smoke over them, for as long as [life]. */
fun FxScene.fire(at: Float, x: Float = 0f, y: Float = 0f, life: Float = .6f, scale: Float = 1f, anchor: FxAnchor = FxAnchor.TARGET) {
    val flames = (life / .05f).toInt().coerceIn(3, 18)
    repeat(flames) { index ->
        particle(anchor, at + index * life / flames * .8f, rand(.18f, .28f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(7f * scale); this.y = y + spread(2f)
            vy = rand(40f, 70f) * scale; drag = 1f; vx = spread(8f)
            size0 = rand(9f, 13f) * scale; size1 = 3f * scale; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER
            alpha = .85f; fadeIn = .15f; fadeOut = .5f
        }
    }
    repeat((flames / 3).coerceAtLeast(2)) { index ->
        particle(anchor, at + .05f + index * life / flames * 2.4f, rand(.5f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(5f * scale); this.y = y + 8f * scale
            vy = rand(40f, 60f) * scale; vx = rand(4f, 14f); drag = .8f
            size0 = 10f * scale; size1 = 28f * scale; color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .55f; fadeIn = .15f; fadeOut = .6f
        }
    }
}

/**
 * Something flying from the attacker to the defender, launched at [at] and landing [travel] later.
 * Offsets are from each tile's center; [arc] lifts the middle of the path. Returns the landing time.
 * Use [look] to set the shape, size and color; the flight and timing are set here.
 */
fun FxScene.projectile(
    at: Float, travel: Float, arc: Float = 0f,
    fromAlong: Float = 10f, fromSide: Float = 0f, toX: Float = 0f, toY: Float = 0f,
    ease: FxEase = FxEase.LINEAR, look: FxParticle.() -> Unit
): Float {
    flying(at, travel, FxFlight(ox(fromAlong, fromSide), oy(fromAlong, fromSide), toX, toY, arc, ease)) {
        alignToMotion = true; fadeIn = 0f; fadeOut = 0f
        look()
    }
    return at + travel
}

/**
 * A trail left along a flight: [count] particles dropped at even fractions of the path as the projectile passes,
 * each then living [life] and drifting by [look]'s velocity. Must match the projectile's flight to line up.
 */
fun FxScene.trail(
    at: Float, travel: Float, count: Int, life: Float, arc: Float = 0f,
    fromAlong: Float = 10f, fromSide: Float = 0f, toX: Float = 0f, toY: Float = 0f,
    ease: FxEase = FxEase.LINEAR, upTo: Float = 1f, look: FxParticle.(Int) -> Unit
) {
    repeat(count) { index ->
        val fraction = (index + .5f) / count * upTo
        // Invert the ease so each puff appears as the projectile passes its point.
        val passTime = when (ease) {
            FxEase.LINEAR -> fraction
            FxEase.IN -> kotlin.math.sqrt(fraction)
            FxEase.OUT -> 1 - kotlin.math.sqrt(1 - fraction)
            FxEase.SMOOTH -> fraction
        }
        flying(at + travel * passTime, life, FxFlight(ox(fromAlong, fromSide), oy(fromAlong, fromSide), toX, toY, arc, ease, ease.apply(passTime))) {
            look(index)
        }
    }
}
