package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.presentation.VisualAttackOutcome

/*
 * Gunpowder and modern land weapons: musket lines, rifles, machine guns, tanks and the Giant Death Robot.
 * A tile's center sits at a soldier's belt, so guns fire a few pixels above it and the ground is about 12 px below.
 */

private const val GROUND = -12f
private val BRASS = Color(.95f, .76f, .32f, 1f)
private val GATLING_STREAK = Color(1f, .94f, .78f, 1f)
private val FLAK_TRACER = Color(1f, .55f, .26f, 1f)
private val AP_TRACER = Color(1f, .4f, .2f, 1f)
private val LASER_CORE = Color(1f, .9f, .86f, 1f)
private val GRENADE_RIM = Color(.86f, .85f, .78f, 1f)
/** Dirt kicked up by a round: darker than dust, so it shows on bright grassland. */
private val SPURT = Color(.46f, .38f, .27f, 1f)

/**
 * A ragged musket volley that wraps the line in white smoke, then the charge goes home with the bayonet.
 * From further off (musket-armed ranged units in mods) the volley alone is the blow.
 */
fun FxScene.muskets() {
    val smoothbore = style.era <= 3
    val charge = distanceInTiles <= 1
    val volley = .05f
    val blow = .38f
    val muskets = if (smoothbore) 4 else 3
    var firstHit = Float.MAX_VALUE
    fireAt = volley
    repeat(muskets) { index ->
        val side = (index - (muskets - 1) / 2f) * 7f + spread(1.5f)
        val shot = volley + index * (if (smoothbore) .022f else .012f) + spread(.005f)
        // A flintlock's priming flares in the pan a beat before the barrel fires.
        if (smoothbore) particle(FxAnchor.SOURCE, shot - .03f, .05f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(5f, side); y = oy(5f, side) + 3f; size0 = 8f; size1 = 3f; color0 = FxPalette.FIRE_CORE; fadeOut = .7f
        }
        muzzleFlash(shot, along = 14f, side = side, scale = .85f)
        gunSmoke(shot + .005f, along = 14f, side = side, scale = if (smoothbore) 1.25f else 1.1f, puffs = if (smoothbore) 5 else 4)
        val (toX, toY) = bulletEnd(onBody = !charge && chance(.5f))
        val from = raised(16f, side, 2f)
        val travel = .05f + (distanceInTiles - 1).coerceAtLeast(0) * .03f
        flying(shot + .005f, travel, FxFlight(from.x, from.y, toX, toY)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            size0 = 2f; size1 = 2f; aspect = .55f; stretch = .012f; alignToMotion = true
            color0 = FxPalette.STEEL; alpha = .6f; fadeIn = 0f; fadeOut = 0f
        }
        bulletHit(shot + .005f + travel, toX, toY, 1.2f)
        firstHit = minOf(firstHit, shot + .005f + travel)
    }
    if (charge) {
        bayonets(blow)
        impact(blow)
    } else impact(firstHit)
}

/** The bayonet charge landing: two thrusts flashing in, steel ringing on steel, boots scuffing the ground. */
private fun FxScene.bayonets(blow: Float) {
    val front = -length.coerceAtMost(40f) * .3f
    for ((delay, side) in listOf(-.02f to -4f, .1f to 5f)) {
        particle(FxAnchor.TARGET, blow + delay - .05f, .1f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(front - 16f, side); y = oy(front - 16f, side) + 3f
            vx = dir.x * 320f; vy = dir.y * 320f; drag = 14f
            size0 = 15f; size1 = 6f; aspect = .16f; rotation = angle
            color0 = FxPalette.STEEL; fadeIn = .2f; fadeOut = .5f
        }
    }
    val hitX = ox(front + 3f); val hitY = oy(front + 3f) + 3f
    if (outcome != VisualAttackOutcome.WITHDRAWN) {
        particle(FxAnchor.TARGET, blow, .07f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = hitX; y = hitY; size0 = 20f; size1 = 5f; color0 = FxPalette.FLASH; fadeOut = .8f
        }
        slash(blow - .02f, hitX, hitY, radius = 10f, from = angle + 150f, sweep = -110f)
        sparks(blow, hitX, hitY, count = 8, speed = 170f, heading = angle + 180f, fan = 150f)
        sparks(blow + .12f, hitX + ox(2f, 5f), hitY + oy(2f, 5f), count = 3, speed = 120f, heading = angle + 160f, fan = 120f)
    }
    val feetX = ox(front - 4f); val feetY = oy(front - 4f) + GROUND
    if (targetWater) splash(blow - .03f, feetX, feetY, .35f)
    else dust(blow - .05f, feetX, feetY, .7f, color = if (targetCity) FxPalette.STONE else FxPalette.DUST, puffs = 4)
}

/** Modern infantry: a squad's rifles crackling with small flashes and tracers, then a grenade bursts among the defenders. */
fun FxScene.rifles() {
    val era = style.era
    val blow = .38f
    val automatic = era >= 7
    // The squad fires together, three rifles at a time: bolt-action rifles with few tracers, later all tracing,
    // and assault rifles in pairs of rounds that strike as one.
    fun volley(at: Float, hits: Boolean) = repeat(3) { shooter ->
        val side = (shooter - 1) * 8f + spread(1f)
        val first = at + shooter * .016f + spread(.006f)
        val (toX, toY) = bulletEnd(onBody = hits && chance(.5f))
        repeat(if (automatic) 2 else 1) { round ->
            val shot = first + round * .03f
            smallFlash(shot, 12f + lunge(shot, blow), side, 1f, if (era <= 5) .8f else .7f, tongue = round == 0)
            val from = raised(14f + lunge(shot, blow), side, 1f)
            if (era > 5 || shooter != 1) tracer(shot, .055f, from.x, from.y, toX + spread(2f), toY + spread(2f), glow = if (round == 0) .4f else 0f)
        }
        bulletHit(first + .055f, toX, toY)
    }
    // Rounds fired as the squad closes pin the defenders down, kicking up the ground around them; the grenade is the blow.
    val ranged = distanceInTiles > 1
    fireAt = .07f
    volley(.07f, ranged)
    if (!automatic) volley(.19f, ranged)
    volley(.31f, ranged)
    gunSmoke(.1f, along = 13f + lunge(.1f, blow), scale = if (era <= 5) .6f else .45f, puffs = 2)
    // Out of throwing range (only mods field rifles that shoot from afar) the volleys are the blow.
    if (ranged) {
        impact(.07f + .055f)
        impact(.31f + .055f)
        return
    }
    if (!defenderKilled) volley(blow + .1f, true)

    // The grenade is lobbed from the middle of the squad as it closes and bursts as the blow lands.
    val throwAt = blow - .24f
    val landX = ox(-6f) + spread(4f); val landY = GROUND + 3f + spread(2f)
    val from = raised(6f + lunge(throwAt, blow), 0f, 8f)
    val flight = FxFlight(from.x, from.y, landX, landY, arc = 26f)
    flying(throwAt, blow - throwAt, flight) {
        shape = FxShape.RING; layer = FxLayer.BODY; size0 = 6.5f; size1 = 6.5f; color0 = GRENADE_RIM; alpha = .85f; fadeIn = 0f; fadeOut = 0f
    }
    flying(throwAt, blow - throwAt, flight) {
        shape = FxShape.DISC; layer = FxLayer.BODY; size0 = 4f; size1 = 4f; color0 = FxPalette.DEBRIS; fadeIn = 0f; fadeOut = 0f
    }
    explosion(blow, landX, landY, scale = if (era <= 5) .6f else .68f, water = targetWater, debris = soil())
    impact(blow, .5f)
}

/** Gatling and machine guns: a stream of tracers walking across the defender's tile, kicking up a line of dirt. */
fun FxScene.machineGun() {
    if (style.variant == "flak") return flak()
    val gatling = style.era <= 4
    val open = .06f
    val travel = .065f
    val rounds = if (gatling) 13 else 15
    val cadence = if (gatling) .032f else .028f
    val muzzle = raised(17f, 0f, 1f)
    fireAt = open
    val sweepFrom = -13f + spread(3f)
    val sweepTo = 13f + spread(3f)
    var onTarget = Float.MAX_VALUE
    repeat(rounds) { index ->
        val shot = open + index * cadence + spread(.003f)
        smallFlash(shot, 15f, 0f, 1f, rand(.8f, 1.05f), tongue = index % 2 == 0)
        // The gunner walks the burst in: the first rounds kick up dirt short of the target, then it rakes across it.
        val u = index / (rounds - 1f)
        val along = MathUtils.lerp(-24f, 0f, (u * 2.2f).coerceAtMost(1f)) + spread(3f)
        val side = MathUtils.lerp(sweepFrom, sweepTo, u) + spread(2.5f)
        val onBody = outcome != VisualAttackOutcome.WITHDRAWN && along > -6f && side in -7f..7f && chance(.55f)
        val toX = ox(along, side); val toY = oy(along, side) + if (onBody) rand(-6f, 5f) else GROUND + spread(2.5f)
        tracer(shot, travel, muzzle.x, muzzle.y, toX, toY, color = if (gatling) GATLING_STREAK else FxPalette.TRACER,
            scale = 1.15f, glow = if (gatling) .3f else .45f)
        bulletHit(shot + travel, toX, toY, 1.35f)
        if (along > -8f) onTarget = minOf(onTarget, shot + travel)
        if (!onBody && !targetWater && index % 3 == 1) ricochet(shot + travel, toX, toY)
        // Spent brass flips out of the gun's right side.
        if (!gatling && index % 3 == 0) particle(FxAnchor.SOURCE, shot + .01f, .26f) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = muzzle.x - ox(10f); y = muzzle.y - oy(10f) - 3f
            vx = -perp.x * rand(40f, 70f) + spread(15f); vy = -perp.y * rand(40f, 70f) + rand(40f, 60f); ay = -700f
            size0 = 2.4f; size1 = 2.4f; aspect = .45f; rotation = rand(0f, 360f); spin = spread(1200f)
            color0 = BRASS; fadeOut = .3f
        }
        if (gatling && index % 3 == 0) gunSmoke(shot + .01f, along = 16f, scale = .7f, puffs = 1)
    }
    if (!gatling) repeat(3) { index -> gunSmoke(open + .1f + index * .15f, along = 16f, scale = .55f, puffs = 1) }
    // The defender reels each time the stream rakes back over it, from the moment it first arrives.
    val last = open + (rounds - 1) * cadence + travel
    var at = onTarget.coerceAtMost(last)
    while (at <= last) { impact(at); at += .16f }
}

/** The Anti-Aircraft Gun turned on ground troops: twin barrels pumping heavy rounds that walk onto the target and burst. */
private fun FxScene.flak() {
    val blow = .34f
    val travel = .05f
    val cadence = .045f
    val short = 3
    val first = blow - travel - short * cadence
    fireAt = first
    repeat(8) { index ->
        val shot = first + index * cadence + spread(.004f)
        val barrel = if (index % 2 == 0) -3.5f else 3.5f
        val muzzle = 16f + lunge(shot, blow)
        smallFlash(shot, muzzle, barrel, 3f, 1.3f)
        if (index % 2 == 0) gunSmoke(shot + .01f, along = muzzle, side = barrel, scale = .6f, puffs = 1)
        val from = raised(muzzle + 2f, barrel, 3f)
        // The first rounds fall short and walk in as the gunner corrects.
        val (toX, toY) = if (index < short) {
            val along = -9f * (short - index) + spread(3f)
            val side = spread(10f)
            Pair(ox(along, side), oy(along, side) + GROUND + spread(2f))
        } else bulletEnd(onBody = chance(.5f), spreadX = 14f)
        tracer(shot, travel, from.x, from.y, toX, toY, color = FLAK_TRACER, scale = 1.4f, glow = .5f)
        shellBurst(shot + travel, toX, toY)
    }
    impact(blow, .6f)
    impact(blow + 2 * cadence, .4f)
    impact(blow + 4 * cadence, .4f)
}

/** A small cannon round bursting on contact: a flash, a few hot fragments and a puff of dark smoke. */
private fun FxScene.shellBurst(at: Float, x: Float, y: Float) {
    particle(FxAnchor.TARGET, at, .08f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 28f; size1 = 10f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .8f
    }
    particle(FxAnchor.TARGET, at + .01f, .15f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 3f; vy = 30f; drag = 4f
        size0 = 10f; size1 = 17f; color0 = FxPalette.FIRE; color1 = FxPalette.EMBER; alpha = .8f; fadeOut = .7f
    }
    sparks(at, x, y, count = 3, speed = 170f, color = FxPalette.FIRE_CORE)
    if (targetWater) splash(at, x, y, .4f)
    else {
        bulletHit(at, x, y, 1.3f)
        particle(FxAnchor.TARGET, at + .02f, rand(.4f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y + 3f; vx = spread(10f); vy = rand(22f, 36f); drag = 2f
            size0 = 9f; size1 = rand(20f, 26f); color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .7f; fadeIn = .1f; fadeOut = .6f
        }
    }
}

/** Tanks: the main gun's flash and blast, a shell too fast to follow and an explosion, then a coaxial burst. */
fun FxScene.tank() {
    if (style.variant == "gun") return antiTankGun()
    val era = style.era
    val blow = .34f
    val travel = .05f
    val fire = blow - travel
    val gun = when { style.heavy -> 1.35f; era <= 5 -> .85f; else -> 1.1f }
    fireAt = fire
    // The engine coughs exhaust as the tank surges forward; the first tanks far more of it.
    repeat(if (era <= 5) 4 else 2) { index ->
        particle(FxAnchor.SOURCE, blow - .2f + index * .04f, rand(.45f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(-14f, -4f) + spread(2f); y = oy(-14f, -4f) + 6f
            vx = -dir.x * 20f + spread(8f); vy = rand(26f, 40f); drag = 1.5f
            size0 = 5f; size1 = rand(14f, 19f); color0 = vary(FxPalette.SMOKE_DARK); color1 = FxPalette.SMOKE
            alpha = if (era <= 5) .6f else .4f; fadeIn = .1f; fadeOut = .6f
        }
    }
    // Tracks churn up dust as it lurches forward.
    if (!sourceWater) for (at in listOf(blow - .2f, blow - .12f)) {
        val behind = raised(lunge(at, blow) - 8f, spread(6f), GROUND)
        dust(at, behind.x, behind.y, .55f * gun, FxAnchor.SOURCE, puffs = 2)
    }
    val muzzle = 17f * gun + lunge(fire, blow)
    gunBlast(fire, muzzle, 5f, gun, brake = era >= 6)
    val from = raised(muzzle + 2f, 0f, 5f)
    val (toX, toY) = shellEnd(3f)
    tracer(fire, travel, from.x, from.y, toX, toY, color = FxPalette.FIRE_CORE, scale = 1.5f * gun.coerceAtMost(1.2f), glow = .55f)
    shellHit(blow, toX, toY, gun)
    impact(blow, if (style.heavy) 2f else if (era <= 5) 1.2f else 1.5f)

    // A coaxial machine gun rakes the target after the shell; a Landship's sponson guns do the same from the side.
    if (defenderKilled) return
    val burst = blow + .14f
    repeat(5) { index ->
        val shot = burst + index * .028f
        val side = if (era <= 5) 9f else 2f
        val start = raised(14f + lunge(shot, blow), side, 3f)
        smallFlash(shot, 12f + lunge(shot, blow), side, 3f, .6f)
        val (endX, endY) = bulletEnd(onBody = chance(.5f))
        tracer(shot, .045f, start.x, start.y, endX, endY)
        bulletHit(shot + .045f, endX, endY)
    }
    impact(burst + .1f)
}

/** A towed anti-tank gun: a flat blast that throws dust off the ground, a red tracer round, sparks spraying off armor. */
private fun FxScene.antiTankGun() {
    val blow = .34f
    val travel = .045f
    val fire = blow - travel
    fireAt = fire
    val muzzle = 17f + lunge(fire, blow)
    gunBlast(fire, muzzle, 1f, 1f, brake = true)
    // The blast flattens out along the ground and lifts a ring of dust around the gun.
    if (!sourceWater) dust(fire, ox(muzzle - 6f), oy(muzzle - 6f) + GROUND + 3f, 1.25f, FxAnchor.SOURCE, puffs = 6)
    val from = raised(muzzle + 2f, 0f, 1f)
    val (toX, toY) = shellEnd(2f)
    tracer(fire, travel, from.x, from.y, toX, toY, color = AP_TRACER, scale = 1.5f, glow = .6f)
    val armor = defenderBody == BodyKind.VEHICLE && outcome != VisualAttackOutcome.WITHDRAWN && !targetWater
    if (armor) {
        particle(FxAnchor.TARGET, blow, .07f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = toX; y = toY; size0 = 30f; size1 = 12f; color0 = FxPalette.FLASH; fadeOut = .8f
        }
        sparks(blow, toX, toY, count = 12, speed = 260f, heading = angle, fan = 150f)
        sparks(blow, toX, toY, count = 5, speed = 170f, heading = angle + 180f, fan = 90f, color = FxPalette.FIRE_CORE)
    }
    explosion(blow, toX, toY, scale = if (armor) .75f else .9f, water = targetWater, debris = soil())
    impact(blow, 1.2f)
}

/** A heavy gun firing: flash, a ring of blast, side jets from a muzzle brake, smoke and dust thrown off the ground. */
private fun FxScene.gunBlast(fire: Float, muzzle: Float, up: Float, scale: Float, brake: Boolean) {
    val mouth = raised(muzzle, 0f, up)
    val along = alongOf(mouth.x, mouth.y); val side = sideOf(mouth.x, mouth.y)
    muzzleFlash(fire, along = along, side = side, scale = scale)
    particle(FxAnchor.SOURCE, fire, .14f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = mouth.x; y = mouth.y; size0 = 8f; size1 = 40f * scale; color0 = FxPalette.FLASH; alpha = .4f; fadeOut = 1f
    }
    if (brake) for (turn in listOf(100f, -100f)) particle(FxAnchor.SOURCE, fire, .07f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        val heading = angle + turn
        x = mouth.x + MathUtils.cosDeg(heading) * 4f * scale; y = mouth.y + MathUtils.sinDeg(heading) * 4f * scale
        vx = MathUtils.cosDeg(heading) * 90f * scale; vy = MathUtils.sinDeg(heading) * 90f * scale; drag = 8f
        size0 = 9f * scale; size1 = 13f * scale; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; alpha = .8f; fadeOut = .7f
    }
    gunSmoke(fire + .01f, along = along, side = side, scale = scale * .9f, puffs = 4)
    if (!sourceWater) dust(fire + .01f, ox(muzzle - 10f), oy(muzzle - 10f) + GROUND + 2f, .6f * scale, FxAnchor.SOURCE, puffs = 3)
}

/** A tank shell striking: an explosion, and white-hot spall when it hits armor. */
private fun FxScene.shellHit(at: Float, x: Float, y: Float, scale: Float) {
    explosion(at, x, y, scale = scale, water = targetWater, debris = soil())
    if (defenderBody == BodyKind.VEHICLE && outcome != VisualAttackOutcome.WITHDRAWN && !targetWater)
        sparks(at, x, y + 2f, count = 8, speed = 230f, heading = angle, fan = 120f)
}

/** The Giant Death Robot: red light gathers in its emitter, then a searing beam sweeps the target until it blows apart. */
fun FxScene.laser() {
    val blow = .32f
    val beamEnd = .58f
    val emitter = 12f
    val up = 6f
    fun mouth(time: Float) = raised(emitter + lunge(time, blow), 0f, up)
    // Charging: a glow swelling in the emitter while sparks of light are drawn into it.
    val chargeFrom = .08f
    val steps = 5
    repeat(steps) { index ->
        val at = chargeFrom + index * (blow - chargeFrom) / steps
        val grow = (index + 1f) / steps
        val point = mouth(at + .03f)
        particle(FxAnchor.SOURCE, at, (blow - chargeFrom) / steps + .015f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = point.x; y = point.y; size0 = 8f + 14f * grow; size1 = 10f + 18f * grow; color0 = FxPalette.LASER; alpha = .55f + .4f * grow
            fadeIn = 0f; fadeOut = .2f
        }
        particle(FxAnchor.SOURCE, at, (blow - chargeFrom) / steps + .015f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = point.x; y = point.y; size0 = 4f + 5f * grow; size1 = 5f + 7f * grow; color0 = LASER_CORE; alpha = .8f; fadeOut = .2f
        }
    }
    repeat(8) {
        val start = rand(chargeFrom, blow - .1f)
        val life = rand(.08f, .12f)
        val sink = mouth(start + life)
        val heading = rand(0f, 360f)
        val reach = rand(14f, 22f)
        particle(FxAnchor.SOURCE, start, life) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = sink.x + MathUtils.cosDeg(heading) * reach; y = sink.y + MathUtils.sinDeg(heading) * reach
            vx = -MathUtils.cosDeg(heading) * reach / life; vy = -MathUtils.sinDeg(heading) * reach / life
            size0 = 2f; size1 = 1.5f; aspect = .6f; stretch = .02f; alignToMotion = true
            color0 = FxPalette.LASER; color1 = LASER_CORE; fadeIn = .3f; fadeOut = .2f
        }
    }

    // The beam: a few short frames, each a white core in a red sheath, its far end sweeping across the target.
    val frames = 6
    val frame = (beamEnd - blow) / frames
    val withdrawn = outcome == VisualAttackOutcome.WITHDRAWN
    val sweep = if (chance(.5f)) 1f else -1f
    val beam = Vector2()
    repeat(frames) { index ->
        val at = blow + index * frame
        val u = index / (frames - 1f)
        val from = mouth(at + frame / 2)
        val side = sweep * MathUtils.lerp(-9f, 9f, u)
        val toX = ox(-2f, side); val toY = oy(-2f, side) + if (withdrawn) GROUND + 2f else MathUtils.lerp(4f, -4f, u)
        beam.set(dir).scl(length).add(toX - from.x, toY - from.y)
        val span = beam.len()
        val rotation = beam.angleDeg()
        val life = frame + .02f
        if (span > 1f) {
            // The sheath covers rather than adds, so the beam stays red over green ground instead of turning yellow.
            val layers = listOf(Triple(7f, FxPalette.LASER, false), Triple(3.6f, FxPalette.LASER, true), Triple(1.8f, LASER_CORE, true))
            for ((height, color, add) in layers) {
                flying(at, life, FxFlight(from.x, from.y, toX, toY, at = .5f)) {
                    shape = FxShape.RECT; blend = if (add) FxBlend.ADD else FxBlend.NORMAL; layer = FxLayer.LIGHT
                    size0 = span; size1 = span; aspect = height / span; this.rotation = rotation
                    color0 = color; alpha = if (add) 1f else .6f; fadeIn = .15f; fadeOut = .25f
                }
            }
            repeat(4) { dot ->
                flying(at, life, FxFlight(from.x, from.y, toX, toY, at = (dot + .5f) / 4)) {
                    shape = FxShape.SOFT; layer = FxLayer.SMOKE
                    size0 = 16f; size1 = 16f; color0 = FxPalette.LASER; alpha = .3f; fadeIn = .15f; fadeOut = .25f
                }
            }
        }
        particle(FxAnchor.SOURCE, at, life) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = from.x; y = from.y; size0 = rand(14f, 20f); size1 = 12f; color0 = LASER_CORE; color1 = FxPalette.LASER; fadeOut = .3f
        }
        particle(FxAnchor.TARGET, at, life) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = toX; y = toY; size0 = rand(16f, 22f); size1 = 14f; color0 = LASER_CORE; color1 = FxPalette.FIRE; fadeOut = .3f
        }
        when {
            targetWater -> particle(FxAnchor.TARGET, at, rand(.4f, .55f)) {
                shape = FxShape.SOFT; layer = FxLayer.SMOKE
                x = toX; y = toY + 2f; vx = spread(10f); vy = rand(36f, 50f); drag = 1.6f
                size0 = 8f; size1 = 22f; color0 = FxPalette.WATER; alpha = .55f; fadeIn = .1f; fadeOut = .6f
            }
            withdrawn -> dust(at, toX, toY, .4f, puffs = 1)
            else -> sparks(at, toX, toY, count = 3, speed = 170f, heading = angle + 180f, fan = 140f, color = FxPalette.FIRE_CORE)
        }
        // The sweep scores a glowing line into the ground that cools to a scorch.
        if (!targetWater) {
            val groundX = ox(-2f, side); val groundY = oy(-2f, side) + GROUND
            particle(FxAnchor.TARGET, at, end - at) {
                shape = FxShape.SOFT; layer = FxLayer.GROUND
                x = groundX; y = groundY; size0 = 9f; size1 = 11f; color0 = FxPalette.SCORCH; alpha = .5f; fadeOut = .3f
            }
            particle(FxAnchor.TARGET, at, .35f) {
                shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.GROUND
                x = groundX; y = groundY; size0 = 9f; size1 = 6f; color0 = FxPalette.FIRE; color1 = FxPalette.EMBER; alpha = .8f; fadeOut = .7f
            }
        }
    }
    impact(blow, 1.5f)
    // A kill brings its own blast just after, so the beam's is smaller then.
    explosion(beamEnd, ox(-2f), oy(-2f), scale = if (withdrawn) .8f else if (defenderKilled) .95f else 1.25f, water = targetWater, debris = soil())
    impact(beamEnd, if (withdrawn) 1.5f else 3f)
}

/** A rifle's flash: the kit's muzzle flash without its sparks, for guns fired many times a second. */
private fun FxScene.smallFlash(at: Float, along: Float, side: Float, up: Float, scale: Float, tongue: Boolean = true) {
    val mouth = raised(along, side, up)
    particle(FxAnchor.SOURCE, at, .05f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = mouth.x; y = mouth.y; size0 = 18f * scale; size1 = 7f * scale; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
    }
    if (tongue) particle(FxAnchor.SOURCE, at, .04f) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = mouth.x + ox(6f * scale); y = mouth.y + oy(6f * scale); size0 = 13f * scale; size1 = 6f * scale; aspect = .36f; rotation = angle
        color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .7f
    }
}

/**
 * How far a melee attacker has lunged toward the defender at [time] when its blow lands at [blow]. It mirrors the
 * timeline's lunge, so a gun fired mid-charge flashes at the unit rather than where it set out from.
 */
private fun FxScene.lunge(time: Float, blow: Float): Float {
    if (distanceInTiles > 1) return 0f
    val since = time - blow
    val reach = (length * .3f).coerceAtMost(20f)
    return reach * when {
        since < -.22f || since >= .25f -> 0f
        since < 0f -> FxEase.SMOOTH.apply(1f + since / .22f)
        else -> 1f - FxEase.SMOOTH.apply(since / .25f)
    }
}

/** A point [along] toward the defender, [side] to its left and [up] pixels higher on screen. */
private fun FxScene.raised(along: Float, side: Float, up: Float) = Vector2(ox(along, side), oy(along, side) + up)

/** The along and side that reach a screen offset, so the kit's gun-mouth helpers can sit above a tile's center. */
private fun FxScene.alongOf(x: Float, y: Float) = x * dir.x + y * dir.y
private fun FxScene.sideOf(x: Float, y: Float) = x * perp.x + y * perp.y

/** What a hit throws up: masonry from a city, earth elsewhere. */
private fun FxScene.soil() = if (targetCity) FxPalette.STONE else FxPalette.EARTH

/** Where a shell ends: in the defender's hull, or in the ground where it stood once it has slipped away. */
private fun FxScene.shellEnd(top: Float): Pair<Float, Float> =
    if (outcome == VisualAttackOutcome.WITHDRAWN) Pair(ox(-4f) + spread(4f), GROUND + 3f + spread(2f))
    else Pair(spread(4f), rand(-5f, top))

/** Where a round ends: on the defender's body, or in the ground around its feet (always, once it has slipped away). */
private fun FxScene.bulletEnd(onBody: Boolean, spreadX: Float = 12f): Pair<Float, Float> =
    if (onBody && outcome != VisualAttackOutcome.WITHDRAWN) Pair(spread(6f), rand(-6f, 5f))
    else Pair(spread(spreadX), GROUND + spread(3f))

/** A bullet striking: a spurt of dirt or masonry thrown up, a jet of water at sea, or a spark off armor. */
private fun FxScene.bulletHit(at: Float, x: Float, y: Float, scale: Float = 1f) {
    if (targetWater) {
        particle(FxAnchor.TARGET, at, .22f) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x; this.y = y + 4f * scale; vy = 50f * scale; drag = 5f
            size0 = 2.5f * scale; size1 = 3.5f * scale; aspect = 2.8f; color0 = FxPalette.WATER; alpha = .8f; fadeOut = .6f
        }
        repeat(2) {
            particle(FxAnchor.TARGET, at, rand(.22f, .32f)) {
                shape = FxShape.DISC; layer = FxLayer.BODY
                this.x = x + spread(1.5f); this.y = y
                vx = spread(22f); vy = rand(90f, 150f) * scale; ay = -800f
                size0 = 2.4f * scale; size1 = 1.4f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .3f
            }
        }
        particle(FxAnchor.TARGET, at, .3f) {
            shape = FxShape.RING; layer = FxLayer.GROUND
            this.x = x; this.y = y - 1f; size0 = 3f; size1 = 13f * scale; color0 = FxPalette.WATER; alpha = .55f; fadeOut = .8f
        }
        return
    }
    val onBody = y > GROUND + 4f
    if (onBody && defenderBody == BodyKind.VEHICLE) {
        sparks(at, x, y, count = 3, speed = 130f, heading = angle + 180f, fan = 140f)
        return
    }
    val dirt = if (targetCity) FxPalette.STONE else SPURT
    // A brief glint where each round strikes, so a burst reads as hits rather than drifting dust.
    if (outcome != VisualAttackOutcome.WITHDRAWN) particle(FxAnchor.TARGET, at, .05f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 1f; size0 = 9f * scale; size1 = 4f; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
    }
    if (!onBody) {
        // Into the ground a round throws up a tight jet of dirt that slumps into a wider puff.
        particle(FxAnchor.TARGET, at, .24f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y + 1f; vx = spread(6f); vy = 95f * scale; drag = 7f
            size0 = 4f * scale; size1 = 11f * scale; color0 = vary(dirt, .05f); alpha = 1f; fadeIn = .05f; fadeOut = .6f
        }
        particle(FxAnchor.TARGET, at, rand(.2f, .28f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(1.5f); this.y = y
            vx = spread(45f); vy = rand(70f, 120f) * scale; ay = -650f
            size0 = rand(2f, 2.8f); size1 = size0; aspect = .8f; rotation = rand(0f, 360f); spin = spread(700f)
            color0 = vary(if (targetCity) FxPalette.STONE else FxPalette.DEBRIS, .05f); fadeOut = .3f
        }
    }
    // The soil thrown up pales into dust as it spreads, so a strike shows on dark grass and on pale plains alike.
    particle(FxAnchor.TARGET, at, rand(.32f, .45f)) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x; this.y = y + 2f
        vx = spread(10f); vy = rand(20f, 32f) * scale; drag = 3f
        size0 = 5f * scale; size1 = rand(14f, 18f) * scale
        color0 = vary(dirt, .05f); color1 = if (targetCity) FxPalette.STONE else FxPalette.DUST
        alpha = if (onBody) .5f else .85f; fadeIn = .1f; fadeOut = .7f
    }
}

/** A round glancing off the ground: a hot streak skipping on along its line of fire and up. */
private fun FxScene.ricochet(at: Float, x: Float, y: Float) {
    val heading = angle + (if (dir.x >= 0f) 1f else -1f) * rand(15f, 40f)
    val speed = rand(180f, 260f)
    particle(FxAnchor.TARGET, at, rand(.08f, .13f)) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 1f
        vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; ay = -300f; drag = 4f
        size0 = 2.4f; size1 = 1.6f; aspect = .5f; stretch = .03f; alignToMotion = true
        color0 = FxPalette.TRACER; color1 = FxPalette.FIRE; fadeOut = .6f
    }
}

/** A bright round in flight: a hot streak with a glow riding on it, so a stream of them reads over dark ground. */
private fun FxScene.tracer(at: Float, travel: Float, fromX: Float, fromY: Float, toX: Float, toY: Float,
                           color: Color = FxPalette.TRACER, scale: Float = 1f, glow: Float = .4f) {
    val flight = FxFlight(fromX, fromY, toX, toY)
    flying(at, travel, flight) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 3f * scale; size1 = size0; aspect = .45f; stretch = .016f; alignToMotion = true
        color0 = color; fadeIn = 0f; fadeOut = 0f
    }
    if (glow > 0f) flying(at, travel, flight) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 10f * scale; size1 = size0; color0 = color; alpha = glow; fadeIn = 0f; fadeOut = 0f
    }
}
