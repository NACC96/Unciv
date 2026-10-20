package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.unciv.logic.presentation.VisualAttackOutcome

/*
 * What follows a blow: a city taking fire, and each kind of unit going down.
 */

/** A city hit by the attack: masonry bursts where the blow lands, then fires catch among its buildings under smoke. */
fun FxScene.cityHit(at: Float) {
    dust(at, 0f, 0f, 1.2f, color = FxPalette.STONE, puffs = 4)
    repeat(5) {
        val heading = rand(30f, 150f)
        val speed = rand(90f, 170f)
        particle(FxAnchor.TARGET, at, rand(.38f, .5f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = spread(6f); y = 2f; vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; ay = -700f; drag = .6f
            size0 = rand(2.5f, 4.2f); size1 = size0; aspect = rand(.6f, .9f); rotation = rand(0f, 360f); spin = spread(700f)
            color0 = vary(FxPalette.STONE, .08f); fadeOut = .25f
        }
    }
    for ((index, spot) in CITY_FIRES.withIndex()) {
        val start = at + .04f + index * .07f
        blaze(FxAnchor.TARGET, start, spot.first + spread(2f), spot.second + spread(2f), end - start - .04f, scale = if (index == 0) 1f else .75f)
    }
}

/** Where fires catch among a city's buildings, as offsets from its center. */
private val CITY_FIRES = listOf(-13f to 4f, 12f to 8f, 2f to -7f)

/** A unit destroyed at [anchor]'s tile, starting at [at]. */
fun FxScene.death(body: BodyKind?, anchor: FxAnchor, at: Float) {
    if (anchor == FxAnchor.SOURCE && family in AIRCRAFT) {
        // Gunships are land units, so their body says nothing of the helicopter.
        if (family == WeaponFamily.GUNSHIP && outcome != VisualAttackOutcome.INTERCEPTED) return rotorcraftDeath(anchor, at, sourceWater)
        // A plane that dies on its attack goes down in flames in its own script, where the viewer last saw it.
        return
    }
    val water = if (anchor == FxAnchor.TARGET) targetWater else sourceWater
    // The fallen go down away from the other side of the fight.
    val away = if (anchor == FxAnchor.TARGET) 1f else -1f
    when (body) {
        BodyKind.VEHICLE -> vehicleDeath(anchor, at, water)
        BodyKind.SHIP -> shipDeath(anchor, at)
        BodyKind.AIRCRAFT -> aircraftDeath(anchor, at, water)
        BodyKind.MOUNTED -> fall(anchor, at, away, 1.3f, water, weapon = true, twice = true)
        BodyKind.CIVILIAN -> fall(anchor, at, away, .85f, water, weapon = false)
        else -> fall(anchor, at, away, 1f, water, weapon = body == BodyKind.INFANTRY)
    }
}

private val AIRCRAFT = setOf(WeaponFamily.STRAFE, WeaponFamily.GUNSHIP, WeaponFamily.BOMBS, WeaponFamily.MISSILE)

/** The hull bursts, the ammunition cooks off and throws the turret clear, and the wreck burns under black smoke. */
private fun FxScene.vehicleDeath(anchor: FxAnchor, at: Float, water: Boolean) {
    explosion(at, 0f, 2f, 1.05f, anchor, smoke = false)
    if (water) spray(anchor, at + .02f, 0f, 0f, .9f)
    val cook = at + .13f
    particle(anchor, cook, .2f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        y = 8f; size0 = 18f; size1 = 44f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .9f
    }
    // Rounds cooking off: a fountain of burning streaks that arc over and fall.
    repeat(7) {
        val heading = 90f + spread(32f)
        val speed = rand(150f, 260f)
        particle(anchor, cook + rand(0f, .05f), rand(.22f, .32f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            y = 6f; vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; ay = -500f; drag = 3f
            size0 = 4f; size1 = 2f; aspect = .55f; stretch = .04f; alignToMotion = true
            color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .6f
        }
    }
    // The turret, thrown up and coming down beside the hull.
    val toss = .46f
    val vx = spread(50f)
    particle(anchor, cook, toss) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        y = 8f; this.vx = vx; vy = 175f; ay = -2f * (16f + 175f * toss) / (toss * toss)
        size0 = 8f; size1 = 8f; aspect = .62f; rotation = rand(0f, 360f); spin = spread(500f)
        color0 = FxPalette.DEBRIS; fadeIn = 0f; fadeOut = 0f
    }
    if (water) spray(anchor, cook + toss, vx * toss, -8f, .45f) else dust(cook + toss, vx * toss, -6f, .6f, anchor, puffs = 3)
    if (!water) blaze(anchor, at + .05f, 0f, -2f, end - at - .09f, scale = 1.15f)
}

/**
 * A magazine blast throws up fire and spray and a second one breaks her back; she burns on a spreading oil slick,
 * then slides under in a last gout of spray, a ring of foam and air bubbling up.
 */
private fun FxScene.shipDeath(anchor: FxAnchor, at: Float) {
    // Blasts drawn as if on land leave their scorch on the water as burning oil; the spray is added separately.
    explosion(at, 0f, 4f, 1.1f, anchor, smoke = false, debris = HULL)
    spray(anchor, at + .02f, 0f, 0f, 1.1f)
    explosion(at + .15f, -11f, 2f, .6f, anchor, smoke = false, debris = HULL)
    blaze(anchor, at + .05f, -2f, 2f, (end - at - .5f).coerceAtLeast(.2f), scale = 1.05f)
    val sink = at + .42f
    spray(anchor, sink, 0f, -2f, .8f)
    particle(anchor, sink - .04f, end - sink + .04f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        y = -3f; size0 = 20f; size1 = 70f; color0 = FxPalette.WATER; alpha = .55f; fadeOut = .8f
    }
    particle(anchor, sink, end - sink) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        y = -3f; size0 = 18f; size1 = 40f; color0 = FxPalette.WATER; alpha = .45f; fadeIn = .15f; fadeOut = .7f
    }
    repeat(5) {
        particle(anchor, sink + rand(.02f, .3f), rand(.15f, .25f)) {
            shape = FxShape.RING; layer = FxLayer.BODY
            x = spread(14f); y = spread(4f) - 3f; vy = rand(4f, 10f)
            size0 = rand(2f, 3f); size1 = size0 * 2.2f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .5f
        }
    }
}

private val HULL = Color(.24f, .24f, .25f, 1f)

/**
 * Water thrown up by a blast or a body going in: soft white plumes that rise and fall back, droplets,
 * and a ring spreading on the surface. Softer than a shell's splash column, which reads as a solid block at this size.
 */
private fun FxScene.spray(anchor: FxAnchor, at: Float, x: Float, y: Float, scale: Float) {
    repeat(3) { index ->
        particle(anchor, at + index * .02f, rand(.4f, .5f)) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(4f * scale); this.y = y + 2f; vx = spread(12f); vy = (70f + index * 35f) * scale; ay = -320f * scale; drag = 1.2f
            size0 = 8f * scale; size1 = rand(16f, 20f) * scale; color0 = FxPalette.WATER; alpha = .8f; fadeIn = .05f; fadeOut = .55f
        }
    }
    repeat((6 * scale).toInt().coerceAtLeast(3)) {
        particle(anchor, at, rand(.32f, .45f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(5f * scale); this.y = y + 2f; vx = spread(60f * scale); vy = rand(120f, 200f) * scale; ay = -800f
            size0 = rand(2.2f, 3.2f); size1 = 1.6f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .3f
        }
    }
    particle(anchor, at + .02f, .5f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y - 2f; size0 = 10f * scale; size1 = 50f * scale; color0 = FxPalette.WATER; alpha = .5f; fadeOut = .8f
    }
}

/** A helicopter blown apart: a fireball, its rotor blades flung off spinning, and the wreck burning where it fell. */
private fun FxScene.rotorcraftDeath(anchor: FxAnchor, at: Float, water: Boolean) {
    explosion(at, 0f, 6f, 1f, anchor, smoke = false, debris = HULL)
    repeat(2) { index ->
        val side = if (index == 0) 1f else -1f
        particle(anchor, at + .02f, rand(.38f, .46f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            y = 10f; vx = side * rand(70f, 110f); vy = rand(130f, 170f); ay = -820f
            size0 = 17f; size1 = 15f; aspect = .1f; rotation = rand(0f, 180f); spin = side * rand(900f, 1300f)
            color0 = HULL; fadeIn = 0f; fadeOut = .2f
        }
    }
    if (water) spray(anchor, at + .08f, 0f, -4f, .9f)
    else blaze(anchor, at + .06f, 0f, -2f, end - at - .1f, scale = .95f)
}

/** A fireball in the air over the tile, burning pieces falling out of it to the ground or the water. */
private fun FxScene.aircraftDeath(anchor: FxAnchor, at: Float, water: Boolean) {
    val height = 30f
    particle(anchor, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        y = height; size0 = 20f; size1 = 54f; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(anchor, at, .16f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        y = height; size0 = 10f; size1 = 60f; color0 = FxPalette.FLASH; alpha = .4f; fadeOut = 1f
    }
    repeat(5) {
        particle(anchor, at + rand(0f, .04f), rand(.24f, .34f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = spread(6f); y = height + spread(5f); vx = spread(40f); vy = spread(30f) + 10f; drag = 4f
            size0 = rand(11f, 15f); size1 = rand(24f, 32f); color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .95f; fadeOut = .65f
        }
    }
    repeat(3) { index ->
        particle(anchor, at + .04f + index * .03f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = spread(8f); y = height + spread(5f); vx = spread(14f); vy = rand(10f, 22f); drag = 1.6f
            size0 = 12f; size1 = rand(26f, 32f); color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .6f; fadeIn = .1f; fadeOut = .6f
        }
    }
    sparks(at, 0f, height, count = 8, speed = 190f, fan = 360f, anchor = anchor, color = FxPalette.FIRE_CORE)
    // Burning pieces fall to the ground, each leaving a thread of smoke.
    repeat(4) {
        val vx = spread(70f)
        val vy = rand(20f, 60f)
        val fall = rand(.36f, .46f)
        val gravity = 2f * (height + vy * fall) / (fall * fall)
        particle(anchor, at, fall) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            y = height; this.vx = vx; this.vy = vy; ay = -gravity
            size0 = rand(2.5f, 4f); size1 = size0; aspect = .6f; rotation = rand(0f, 360f); spin = spread(800f)
            color0 = FxPalette.DEBRIS; fadeIn = 0f; fadeOut = 0f
        }
        particle(anchor, at, fall) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            y = height; this.vx = vx; this.vy = vy; ay = -gravity
            size0 = 8f; size1 = 5f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; alpha = .9f; fadeIn = 0f; fadeOut = .2f
        }
        repeat(3) { step ->
            val age = fall * (step + 1) / 4
            particle(anchor, at + age, .35f) {
                shape = FxShape.SOFT; layer = FxLayer.SMOKE
                x = vx * age; y = height + vy * age - .5f * gravity * age * age; this.vy = 8f; drag = 2f
                size0 = 4f; size1 = 11f; color0 = vary(FxPalette.SMOKE); alpha = .55f; fadeIn = .1f; fadeOut = .7f
            }
        }
        val x = vx * fall
        if (water) particle(anchor, at + fall, .3f) {
            shape = FxShape.RING; layer = FxLayer.GROUND
            this.x = x; y = 0f; size0 = 3f; size1 = 16f; color0 = FxPalette.WATER; alpha = .6f; fadeOut = .8f
        } else particle(anchor, at + fall, .35f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; y = 0f; this.vy = 12f; drag = 3f; size0 = 5f; size1 = 14f
            color0 = vary(FxPalette.DUST); alpha = .6f; fadeIn = .1f; fadeOut = .7f
        }
    }
}

/**
 * A soldier, rider or civilian going down: dust thrown out low as they hit the ground, mostly [away] from the blow,
 * and a weapon spinning out of their hands. A horse and rider come down [twice].
 */
private fun FxScene.fall(anchor: FxAnchor, at: Float, away: Float, scale: Float, water: Boolean, weapon: Boolean, twice: Boolean = false) {
    val pushX = dir.x * away; val pushY = dir.y * away
    if (water) {
        spray(anchor, at + .04f, pushX * 4f, -6f, .6f * scale)
        if (twice) spray(anchor, at + .16f, pushX * 12f, -7f, .5f * scale)
        return
    }
    thump(anchor, at + .04f, pushX * 4f, FEET + pushY * 2f, pushX, pushY, scale)
    if (twice) thump(anchor, at + .17f, pushX * 13f, FEET + pushY * 6f, pushX, pushY, scale * .8f)
    if (!weapon) return
    // It lies where it lands until the scene ends.
    val drop = .3f
    val vx = pushX * 34f + spread(10f)
    val vy = 60f
    val height = 16f
    val gravity = 2f * (height + vy * drop) / (drop * drop)
    val turn = rand(0f, 180f)
    val spin = (if (chance(.5f)) 1f else -1f) * rand(400f, 700f)
    particle(anchor, at, drop) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        x = pushX * 6f; y = FEET + height; this.vx = vx; this.vy = vy; ay = -gravity
        size0 = 11f; size1 = 11f; aspect = .16f; rotation = turn; this.spin = spin
        color0 = FxPalette.SHAFT; fadeIn = 0f; fadeOut = 0f
    }
    particle(anchor, at + drop, end - at - drop) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        x = pushX * 6f + vx * drop; y = FEET
        size0 = 11f; size1 = 11f; aspect = .16f; rotation = turn + spin * drop
        color0 = FxPalette.SHAFT; fadeIn = 0f; fadeOut = .45f
    }
}

private val DUST_LIGHT = Color(.88f, .8f, .64f, 1f)

/** How far below a tile's center a unit stands. */
private const val FEET = -15f

/** A body hitting the ground: a low flat burst of dust, a ring of it along the ground and a few clods. */
private fun FxScene.thump(anchor: FxAnchor, at: Float, x: Float, y: Float, pushX: Float, pushY: Float, scale: Float) {
    // A skirt of dust rolling out along the ground, and a cloud rising off the middle of it.
    repeat(6) { index ->
        val heading = index * 60f + spread(20f)
        val speed = rand(60f, 95f) * scale
        particle(anchor, at + rand(0f, .03f), rand(.5f, .62f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(3f); this.y = y + spread(2f)
            vx = MathUtils.cosDeg(heading) * speed + pushX * 34f * scale; vy = MathUtils.sinDeg(heading) * speed * .32f + 6f + pushY * 10f; drag = 4f
            size0 = 10f * scale; size1 = rand(24f, 30f) * scale; color0 = vary(DUST_LIGHT, .04f); alpha = .85f; fadeIn = .06f; fadeOut = .55f
        }
    }
    repeat(3) {
        particle(anchor, at + rand(.02f, .06f), rand(.5f, .62f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(5f); this.y = y + 3f; vx = spread(14f) + pushX * 20f; vy = rand(22f, 36f) * scale; drag = 2.4f
            size0 = 12f * scale; size1 = rand(26f, 32f) * scale; color0 = vary(FxPalette.DUST, .05f); alpha = .7f; fadeIn = .1f; fadeOut = .6f
        }
    }
    particle(anchor, at, .32f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = 12f; size1 = 50f * scale; color0 = DUST_LIGHT; alpha = .7f; fadeOut = 1f
    }
    repeat(5) {
        particle(anchor, at, rand(.24f, .32f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(6f); this.y = y; vx = spread(60f) + pushX * 25f; vy = rand(60f, 110f); ay = -650f
            size0 = rand(2.2f, 3f); size1 = size0; color0 = vary(FxPalette.EARTH, .05f); fadeOut = .3f
        }
    }
}

/**
 * Something burning from [at] for [life]: tongues of flame licking up over a glow, a few embers, and a column of
 * smoke drifting off it that thins out before the scene ends.
 */
private fun FxScene.blaze(anchor: FxAnchor, at: Float, x: Float, y: Float, life: Float, scale: Float = 1f) {
    if (life < .12f) return
    particle(anchor, at, life) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 3f * scale; size0 = 16f * scale; size1 = 24f * scale; color0 = FxPalette.FIRE; alpha = .45f; fadeIn = .15f; fadeOut = .35f
    }
    // Painted flames hold their color over water and snow, where light alone would wash out to white.
    val tongues = (life / .045f).toInt().coerceIn(4, 16)
    repeat(tongues) { index ->
        val core = index % 2 == 0
        particle(anchor, at + index * life / tongues * .85f, rand(.2f, .3f)) {
            shape = FxShape.SOFT; layer = if (core) FxLayer.LIGHT else FxLayer.BODY
            blend = if (core) FxBlend.ADD else FxBlend.NORMAL
            this.x = x + spread(7f * scale); this.y = y + spread(2f)
            vx = spread(10f); vy = rand(50f, 80f) * scale; drag = 1.2f
            size0 = (if (core) rand(9f, 13f) else rand(12f, 16f)) * scale; size1 = 3f * scale; sizeEase = FxEase.LINEAR
            color0 = if (core) FxPalette.FIRE_CORE else FxPalette.FIRE; color1 = if (core) FxPalette.FIRE else FxPalette.EMBER
            alpha = if (core) .85f else .9f; fadeIn = .12f; fadeOut = .5f
        }
    }
    repeat(2) {
        particle(anchor, at + rand(.05f, life * .7f), rand(.3f, .45f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(6f * scale); this.y = y + 6f * scale; vx = spread(20f) + 6f; vy = rand(50f, 90f); drag = 1.5f
            size0 = 1.6f; size1 = 1.2f; aspect = 1f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; fadeOut = .5f
        }
    }
    val puffs = (life / .15f).toInt().coerceIn(2, 5)
    repeat(puffs) { index ->
        particle(anchor, at + .06f + index * life / puffs * .8f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f * scale); this.y = y + 14f * scale; vx = rand(6f, 14f); vy = rand(42f, 58f) * scale; drag = .9f
            size0 = 10f * scale; size1 = rand(26f, 32f) * scale; color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .5f; fadeIn = .15f; fadeOut = .65f
        }
    }
}
