package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.unciv.logic.presentation.VisualAttackOutcome
import kotlin.math.abs
import kotlin.math.exp

/*
 * Aircraft: fighters strafing, helicopter gunships, bombers and an air attack brought down by the defense.
 * Planes are drawn as small silhouettes seen from above that fly in from the attacker's side over their own shadow,
 * so their height reads at a glance; the attacker's own tile (often a city or a carrier) never shows them.
 */

/** A strafing run: the fighter dives in over the defender, walks its guns' strikes through it (a jet opens with rockets) and climbs away. */
fun FxScene.strafe() {
    val frame = fighter()
    val jet = frame == Airframe.JET
    val biplane = frame == Airframe.BIPLANE
    // When the plane passes over the defender, and how high it flies in and at the bottom of its dive.
    val over = when { jet -> .42f; biplane -> .52f; else -> .46f }
    val high = when { jet -> 46f; biplane -> 34f; else -> 40f }
    val low = when { jet -> 28f; biplane -> 20f; else -> 24f }
    val approach = approach(if (jet) 190f else 160f)
    val speed = approach / over
    val bottom = over + .04f
    val bend = (high - low) / (bottom * bottom)
    val run = AirRun(FxAnchor.TARGET, 0f, ox(-approach), oy(-approach), angle, speed, high, climb = -2f * bend * bottom, sink = 2f * bend)

    if (jet) {
        // A pair of rockets from under the wings opens the run.
        listOf(6f, -6f).forEachIndexed { index, wing ->
            val launch = .13f + index * .035f
            val travel = .13f
            val along = -5f + index * 8f + spread(2f)
            val side = wing * .45f
            rocket(launch, travel, run.pointX(launch, 0f, wing), run.pointY(launch, 0f, wing) - 1f, ox(along, side), oy(along, side))
            blast(FxAnchor.TARGET, launch + travel, ox(along, side), oy(along, side), .72f, targetWater, smoke = index == 0)
            impact(launch + travel, if (index == 0) 1.2f else .8f)
        }
    }

    // Wing guns (nose guns firing through a biplane's propeller, a jet's cannon) strike the ground ahead of the plane.
    val guns = when { biplane -> floatArrayOf(1.6f, -1.6f); jet -> floatArrayOf(0f); else -> floatArrayOf(7f, -7f) }
    val gunAt = when { biplane -> 6f; jet -> 8f; else -> 2f }
    val lead = if (jet) 58f else 50f
    val travel = .06f
    // Rounds strike about 14 px apart, so each kick stands on its own and the burst reads as a line.
    val interval = when { biplane -> .045f; jet -> .03f; else -> .04f }
    val rakeFrom = if (jet) -16f else -42f
    val rakeTo = 30f
    var shot = (rakeFrom - lead + approach) / speed
    val lastShot = (rakeTo - lead + approach) / speed
    fireAt = shot
    var onTarget: Float? = null
    var round = 0
    while (shot <= lastShot) {
        guns.forEachIndexed { gun, wing ->
            // The guns fire in turn, so the two lines of strikes stitch along rather than land in pairs.
            val at = shot + gun * interval / 2
            val along = -approach + speed * at + lead + spread(3f)
            val side = wing * .8f + spread(2f)
            val x = ox(along, side); val y = oy(along, side)
            tracer(at, run.pointX(at, gunAt, wing), run.pointY(at, gunAt, wing), x, y, travel)
            val onUnit = abs(along) < 12f && abs(side) < 10f
            if (onUnit && onTarget == null) onTarget = at + travel
            bulletHit(at + travel, x, y, onUnit, if (jet) 1.3f else if (biplane) .85f else 1f, mark = round % 2 == 0)
        }
        // The guns' flash rides with the plane.
        particle(FxAnchor.TARGET, shot, .035f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = run.pointX(shot, gunAt + 2f); y = run.pointY(shot, gunAt + 2f)
            vx = run.hx * speed; vy = run.hy * speed + run.climbAt(shot)
            size0 = if (biplane) 9f else 13f; size1 = 6f; color0 = FxPalette.FIRE_CORE; alpha = .9f; fadeOut = .6f
        }
        shot += interval
        round++
    }
    impact(onTarget ?: (over - lead / speed + travel))
    // A plane the defense brings down is caught as it pulls up off the target.
    finishRun(frame, run, lastShot + travel + .04f)
}

/** A helicopter gunship looses a pair of rockets into the defender, then hoses it with its chin gun. */
fun FxScene.gunship() {
    val blow = .31f
    val rocketTravel = .13f
    // A defender that slipped away leaves the rockets and the gun churning up the ground where it stood, beside it.
    val withdrawn = outcome == VisualAttackOutcome.WITHDRAWN
    fun missX(side: Float) = if (withdrawn) ox(-16f, side * 18f) else 0f
    fun missY(side: Float) = if (withdrawn) oy(-16f, side * 18f) - 4f else 0f
    // Rotor wash blows a ring of dust (or spray) out along the ground under the helicopter as it closes in.
    val washX = ox(lunge(.2f, blow)); val washY = oy(lunge(.2f, blow)) - 14f
    particle(FxAnchor.SOURCE, .04f, .5f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        x = washX; y = washY; size0 = 16f; size1 = 54f
        color0 = if (sourceWater) FxPalette.WATER else FxPalette.DUST; alpha = .6f; fadeIn = .15f; fadeOut = .6f
    }
    if (!sourceWater) repeat(4) { index ->
        val heading = 20f + index * 45f + (if (index >= 2) 90f else 0f) + spread(12f)
        particle(FxAnchor.SOURCE, .04f + index * .03f, rand(.45f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = washX + MathUtils.cosDeg(heading) * 8f; y = washY + MathUtils.sinDeg(heading) * 3f
            vx = MathUtils.cosDeg(heading) * 80f; vy = MathUtils.sinDeg(heading) * 18f + 6f; drag = 3f
            size0 = 8f; size1 = rand(20f, 24f); color0 = vary(FxPalette.DUST, .04f); alpha = .6f; fadeIn = .15f; fadeOut = .6f
        }
    }
    listOf(7f, -7f).forEachIndexed { index, side ->
        val launch = blow - rocketTravel + index * .05f
        val along = 8f + lunge(launch, blow)
        val toX = missX(side / 7f) + spread(5f); val toY = missY(side / 7f) + spread(4f)
        // The pods hang from stub wings at the helicopter's middle.
        val fromX = ox(along, side * .4f); val fromY = oy(along, side * .4f) - 4f
        // Ignition at the pod, with a puff of backblast behind it.
        particle(FxAnchor.SOURCE, launch, .07f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = fromX; y = fromY; size0 = 16f; size1 = 7f; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
        }
        particle(FxAnchor.SOURCE, launch, .45f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = fromX - dir.x * 6f; y = fromY - dir.y * 6f; vx = -dir.x * 45f + spread(8f); vy = -dir.y * 45f + 12f; drag = 3f
            size0 = 7f; size1 = 20f; color0 = vary(FxPalette.GUN_SMOKE, .04f); alpha = .55f; fadeIn = .1f; fadeOut = .7f
        }
        rocketFlight(launch, rocketTravel, FxFlight(fromX, fromY, toX, toY))
        blast(FxAnchor.TARGET, launch + rocketTravel, toX, toY, if (index == 0) .8f else .7f, targetWater, smoke = index == 0)
        impact(launch + rocketTravel, if (index == 0) 1.2f else .9f)
    }
    // The chin gun keeps firing as the helicopter backs off.
    val burst = blow + .09f
    val interval = .026f
    val travel = .05f
    repeat(8) { index ->
        val at = burst + index * interval
        val along = 13f + lunge(at, blow)
        val toX = missX(1f) + spread(12f); val toY = missY(1f) - 3f + spread(7f)
        flying(at, travel, FxFlight(ox(along), oy(along) - 6f, toX, toY)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            size0 = 5f; size1 = 5f; aspect = .45f; stretch = .014f; alignToMotion = true
            color0 = FxPalette.TRACER; fadeIn = 0f; fadeOut = .2f
        }
        bulletHit(at + travel, toX, toY, abs(toX) < 10f && abs(toY) < 8f, mark = index % 2 == 0)
        if (index % 2 == 0) particle(FxAnchor.SOURCE, at, .04f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(along + 2f); y = oy(along + 2f) - 6f; size0 = 11f; size1 = 6f; color0 = FxPalette.FIRE_CORE; fadeOut = .6f
        }
    }
    impact(burst + 4 * interval + travel)
}

/** A bomber passes over the defender and lets go a stick of bombs that walks a chain of blasts across it. */
fun FxScene.bombs() {
    val frame = bomber()
    val count = when (frame) { Airframe.HEAVY -> 6; Airframe.STEALTH -> 3; else -> 4 }
    // Far enough apart that each blast of the stick shows on its own before they merge into one cloud.
    val spacing = when (frame) { Airframe.HEAVY -> 16f; Airframe.STEALTH -> 26f; else -> 22f }
    val blast = when (frame) { Airframe.HEAVY -> 1f; Airframe.STEALTH -> 1.3f; else -> .9f }
    val over = .46f
    val height = if (frame == Airframe.STEALTH) 58f else 52f
    val approach = approach(if (frame == Airframe.STEALTH) 175f else 150f)
    val speed = approach / over
    val run = AirRun(FxAnchor.TARGET, 0f, ox(-approach), oy(-approach), angle, speed, height)
    // Bombs keep the plane's speed as they fall, so each lands under the plane.
    val fall = .22f
    val drop = height - 3f
    val gravity = 2f * drop / (fall * fall)
    var last = 0f
    repeat(count) { index ->
        // The stick walks across the defender's tile; the bomb nearest the middle falls on the defender itself.
        val center = index == count / 2
        val along = (index - (count - 1) / 2f) * spacing + spread(2f)
        val side = spread(4f)
        val land = over + along / speed
        val release = land - fall
        if (index == 0) fireAt = release
        particle(FxAnchor.TARGET, release, fall) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = ox(along - speed * fall, side); y = oy(along - speed * fall, side) + drop
            vx = dir.x * speed; vy = dir.y * speed; ay = -gravity
            size0 = if (frame == Airframe.STEALTH) 9f else 7.5f; size1 = size0; aspect = .42f; alignToMotion = true
            color0 = BOMB; fadeIn = .12f; fadeOut = 0f
        }
        // The outer bombs are smaller, so the blast on the defender stands out and the overlapping flashes don't wash out.
        val size = blast * when { center -> 1.15f; frame == Airframe.HEAVY -> .72f; else -> .8f }
        blast(FxAnchor.TARGET, land, ox(along, side), oy(along, side), size, targetWater, smoke = center || index == 0)
        if (!targetWater && (center || index == 0)) plume(land, ox(along, side), oy(along, side), blast)
        val shake = when (frame) { Airframe.HEAVY -> if (center) 3.5f else 2.5f; Airframe.STEALTH -> if (center) 4f else 3f; else -> if (center) 3f else 2f }
        impact(land, shake)
        last = land
    }
    finishRun(frame, run, last + .05f)
}

/**
 * An air attack met by anti-aircraft fire or fighters: flak bursts around its path until one finds it.
 * Shot down, it falls in flames and crashes short of the defender; driven off, it turns back trailing smoke.
 */
fun FxScene.interception() {
    val frame = when (family) { WeaponFamily.BOMBS -> bomber(); WeaponFamily.MISSILE -> Airframe.MISSILE; else -> fighter() }
    val missile = frame == Airframe.MISSILE
    // When only the attacker's tile is seen, the run plays out just beyond it instead.
    val anchor = if (targetSeen || !sourceSeen) FxAnchor.TARGET else FxAnchor.SOURCE
    val height = when (frame) { Airframe.MISSILE -> 26f; Airframe.BOMBER, Airframe.HEAVY, Airframe.STEALTH -> 52f; else -> 40f }
    val speed = if (missile) 380f else 250f
    val hit = .34f
    // It is caught about halfway in, so a plane shot down falls well short of the defender.
    val caught = if (anchor == FxAnchor.TARGET) -(approach(170f) * .55f).coerceAtLeast(60f) else 90f
    val from = caught - speed * hit
    val run = AirRun(anchor, 0f, ox(from), oy(from), angle, speed, height)
    fly(frame, run, until = hit, fadeOut = 0f)
    // The defense does the firing here, so the attacker at its base never kicks back.
    // Bursts bracket the plane, mostly ahead of it, closing in.
    repeat(if (missile) 3 else 6) { index ->
        val at = .07f + index * .04f + spread(.01f)
        val ahead = rand(-6f, 36f) * (1f - index * .12f)
        val side = spread(18f) * (1f - index * .1f)
        flak(anchor, at, run.groundX(at) + run.hx * ahead - run.hy * side, run.groundY(at) + run.hy * ahead + run.hx * side + run.heightAt(at) + spread(10f))
    }
    // Tracers climbing from the defender's guns.
    if (anchor == FxAnchor.TARGET) repeat(5) { index ->
        val at = .05f + index * .055f
        val aim = at + .12f
        tracer(at, spread(16f), spread(6f), run.pointX(aim) + spread(14f), run.pointY(aim) + spread(10f), .12f, width = 1.5f)
    }
    val x = run.pointX(hit); val y = run.pointY(hit)
    flak(anchor, hit - .01f, x + spread(3f), y + spread(3f), big = true)
    sparks(hit, x, y, count = 6, speed = 150f, heading = angle, fan = 300f, anchor = anchor)
    impact(hit)
    when {
        missile -> airburst(anchor, hit, x, y)
        attackerKilled -> fallInFlames(frame, run, hit)
        else -> turnBack(frame, run, hit)
    }
}

/**
 * Flies [frame] along [run] until [leave]. If the attacker dies, the defense finds it as it pulls away and it falls
 * in flames; otherwise it flies on out of the scene.
 */
private fun FxScene.finishRun(frame: Airframe, run: AirRun, leave: Float) {
    if (!attackerKilled) return fly(frame, run, until = .95f)
    if (!targetSeen) return lostBeyondBase(frame)
    fly(frame, run, until = leave, fadeOut = 0f)
    // Fire climbing from the defender's guns finds it.
    repeat(4) { index ->
        val at = leave - .2f + index * .045f
        val aim = at + .1f
        tracer(at, spread(10f), 4f + spread(4f), run.pointX(aim) + spread(10f), run.pointY(aim) + spread(8f), .1f, width = 1.6f)
    }
    val x = run.pointX(leave); val y = run.pointY(leave)
    flak(FxAnchor.TARGET, leave - .01f, x + spread(3f), y + spread(3f), big = true)
    sparks(leave, x, y, count = 6, speed = 150f, heading = angle, fan = 300f)
    fallInFlames(frame, run, leave)
}

/**
 * A plane lost on an attack the viewer can't see: it climbs away from its base and is caught just beyond it, so the
 * fireball doesn't look like a hit on the base itself.
 */
private fun FxScene.lostBeyondBase(frame: Airframe) {
    val hit = .42f
    val run = AirRun(FxAnchor.SOURCE, 0f, 0f, 0f, angle, 220f, 26f, climb = 30f)
    fly(frame, run, until = hit, fadeOut = 0f)
    val x = run.pointX(hit); val y = run.pointY(hit)
    flak(FxAnchor.SOURCE, hit - .01f, x + spread(3f), y + spread(3f), big = true)
    sparks(hit, x, y, count = 6, speed = 150f, heading = angle, fan = 300f, anchor = FxAnchor.SOURCE)
    fallInFlames(frame, run, hit)
}

private val BOMB = Color(.17f, .17f, .16f, 1f)
private val GLASS = Color(.62f, .78f, .88f, 1f)
private val PROP = Color(.92f, .92f, .88f, 1f)

/**
 * One flat piece of an aircraft seen from above, [u] toward the nose and [w] to the left of its center:
 * [along] long and [across] wide, turned [turn] degrees from the plane's heading.
 */
private class Part(
    val u: Float, val w: Float, val along: Float, val across: Float, val color: Color, val turn: Float = 0f,
    val shape: FxShape = FxShape.RECT, val alpha: Float = 1f, val shadow: Boolean = true, val light: Boolean = false
)

/** Half a wing from a root on the fuselage at [rootU], swept back [sweep] degrees, on the left ([side] 1) or right (-1). */
private fun swept(rootU: Float, side: Float, chord: Float, span: Float, sweep: Float, color: Color) =
    Part(rootU - span / 2 * MathUtils.sinDeg(sweep), side * span / 2 * MathUtils.cosDeg(sweep), chord, span, color, side * sweep)

/**
 * Both halves of a flying wing whose leading edges meet at [noseU] and sweep back [sweep] degrees, each built from
 * root to tip of (span, chord) [segments] with their leading edges in line, so the trailing edge steps like a B-2's.
 */
private fun flyingWing(noseU: Float, sweep: Float, segments: List<Pair<Float, Float>>, color: Color): List<Part> {
    val parts = ArrayList<Part>()
    for (side in listOf(1f, -1f)) {
        var out = 0f
        for ((span, chord) in segments) {
            val s = out + span / 2
            // From the nose along the leading edge, then half a chord back across it.
            val u = noseU - s * MathUtils.sinDeg(sweep) - chord / 2 * MathUtils.cosDeg(sweep)
            val w = side * (s * MathUtils.cosDeg(sweep) - chord / 2 * MathUtils.sinDeg(sweep))
            parts += Part(u, w, chord, span, color, side * sweep)
            out += span
        }
    }
    return parts
}

private fun prop(u: Float, w: Float = 0f, size: Float = 9f) = Part(u, w, size, size, PROP, shape = FxShape.SOFT, alpha = .42f, shadow = false)

/**
 * Airframes drawn from rectangles and discs, wings first so the fuselage lies over them, then grown by [scale].
 * [wreck] is the length and span of what is left falling.
 */
private enum class Airframe(val parts: List<Part>, val wreck: Pair<Float, Float>, val scale: Float = 1.2f) {
    BIPLANE(listOf(
        Part(2f, 0f, 5.6f, 23f, Color(.7f, .62f, .42f, 1f)),
        Part(-6.5f, 0f, 2.8f, 8f, Color(.7f, .62f, .42f, 1f)),
        Part(0f, 0f, 15f, 3.4f, Color(.45f, .34f, .22f, 1f)),
        Part(2f, 7.6f, 3f, 3f, Color(.8f, .22f, .16f, 1f), shape = FxShape.DISC, shadow = false),
        Part(2f, -7.6f, 3f, 3f, Color(.8f, .22f, .16f, 1f), shape = FxShape.DISC, shadow = false),
        Part(.5f, 0f, 2.6f, 2.6f, Color(.18f, .15f, .12f, 1f), shape = FxShape.DISC, shadow = false),
        prop(8.5f, size = 8f)
    ), 15f to 23f),
    FIGHTER(listOf(
        Part(1.5f, 0f, 4.6f, 23f, Color(.52f, .56f, .6f, 1f)),
        Part(-7.5f, 0f, 2.6f, 9f, Color(.52f, .56f, .6f, 1f)),
        Part(0f, 0f, 19f, 3.4f, Color(.52f, .56f, .6f, 1f)),
        Part(8.6f, 0f, 2.4f, 3.8f, Color(.3f, .32f, .35f, 1f)),
        Part(2.5f, 0f, 3.2f, 3.2f, GLASS, shape = FxShape.DISC, shadow = false),
        prop(10.5f)
    ), 19f to 23f),
    JET(listOf(
        swept(1.5f, 1f, 4.2f, 11f, 38f, Color(.64f, .67f, .71f, 1f)),
        swept(1.5f, -1f, 4.2f, 11f, 38f, Color(.64f, .67f, .71f, 1f)),
        swept(-7.5f, 1f, 2.4f, 4.6f, 38f, Color(.64f, .67f, .71f, 1f)),
        swept(-7.5f, -1f, 2.4f, 4.6f, 38f, Color(.64f, .67f, .71f, 1f)),
        Part(0f, 0f, 21f, 3.2f, Color(.64f, .67f, .71f, 1f)),
        Part(11.5f, 0f, 3f, 1.8f, Color(.36f, .38f, .42f, 1f)),
        Part(5f, 0f, 4.4f, 2.2f, GLASS, shadow = false),
        Part(-11.5f, 0f, 7f, 7f, FxPalette.EXHAUST, shape = FxShape.SOFT, alpha = .85f, shadow = false, light = true)
    ), 21f to 18f),
    BOMBER(listOf(
        Part(2f, 0f, 5.5f, 34f, Color(.46f, .5f, .42f, 1f)),
        Part(4f, 8f, 7f, 3.4f, Color(.28f, .3f, .27f, 1f)),
        Part(4f, -8f, 7f, 3.4f, Color(.28f, .3f, .27f, 1f)),
        Part(-10f, 0f, 3f, 12f, Color(.46f, .5f, .42f, 1f)),
        Part(0f, 0f, 24f, 4.4f, Color(.46f, .5f, .42f, 1f)),
        Part(10.5f, 0f, 3f, 3f, GLASS, shape = FxShape.DISC, shadow = false),
        prop(8f, 8f), prop(8f, -8f)
    ), 24f to 34f, 1.15f),
    HEAVY(listOf(
        Part(2.5f, 0f, 7f, 46f, Color(.68f, .69f, .7f, 1f)),
        Part(5f, 8f, 7.5f, 3.6f, Color(.38f, .39f, .4f, 1f)),
        Part(5f, -8f, 7.5f, 3.6f, Color(.38f, .39f, .4f, 1f)),
        Part(4f, 15.5f, 6.5f, 3.2f, Color(.38f, .39f, .4f, 1f)),
        Part(4f, -15.5f, 6.5f, 3.2f, Color(.38f, .39f, .4f, 1f)),
        Part(-13f, 0f, 3.6f, 16f, Color(.68f, .69f, .7f, 1f)),
        Part(0f, 0f, 30f, 5f, Color(.68f, .69f, .7f, 1f)),
        Part(15f, 0f, 2.6f, 3.4f, GLASS, shadow = false),
        prop(9.5f, 8f), prop(9.5f, -8f), prop(8f, 15.5f), prop(8f, -15.5f)
    ), 30f to 46f, 1.1f),
    STEALTH(flyingWing(12f, 33f, listOf(10f to 13f, 9f to 9f, 8f to 5f), Color(.3f, .32f, .35f, 1f)) + listOf(
        Part(4f, 0f, 13f, 5f, Color(.36f, .38f, .42f, 1f)),
        Part(7.5f, 0f, 2.6f, 3.6f, Color(.16f, .18f, .22f, 1f), shadow = false)
    ), 16f to 40f, 1.1f),
    MISSILE(listOf(
        Part(-5f, 0f, 2.4f, 6f, Color(.6f, .6f, .58f, 1f)),
        Part(0f, 0f, 13f, 2.6f, Color(.86f, .86f, .84f, 1f)),
        Part(6.8f, 0f, 1.6f, 1.8f, Color(.36f, .38f, .42f, 1f)),
        Part(-8.5f, 0f, 8f, 8f, FxPalette.EXHAUST, shape = FxShape.SOFT, alpha = .9f, shadow = false, light = true)
    ), 13f to 6f, 1.1f)
}

private fun FxScene.fighter() = when {
    style.variant == "biplane" -> Airframe.BIPLANE
    style.variant == "jet" -> Airframe.JET
    style.era <= 5 -> Airframe.BIPLANE
    style.era >= 7 -> Airframe.JET
    else -> Airframe.FIGHTER
}

private fun FxScene.bomber() = when {
    !style.heavy -> Airframe.BOMBER
    style.era >= 7 -> Airframe.STEALTH
    else -> Airframe.HEAVY
}

/** How far short of the defender a plane comes into view: from its own side, but never off in the distance. */
private fun FxScene.approach(most: Float) = if (length > 0f) length.coerceIn(110f, most) else most

/**
 * A bomb or rocket going off: the shared explosion on land or on a ship's deck (thrown masonry over a city), and in
 * open water a fireball under a tall column of white water, which reads better from above than the splash's spout.
 */
private fun FxScene.blast(anchor: FxAnchor, at: Float, x: Float, y: Float, scale: Float, water: Boolean, smoke: Boolean = true) {
    val onShip = water && anchor == FxAnchor.TARGET && defenderBody == BodyKind.SHIP && outcome != VisualAttackOutcome.WITHDRAWN &&
        abs(x) < 14f && abs(y + 2f) < 10f
    if (!water || onShip) return explosion(at, x, y, scale, anchor, smoke = smoke,
        debris = if (targetCity && anchor == FxAnchor.TARGET) FxPalette.STONE else FxPalette.DEBRIS)
    particle(anchor, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 2f; size0 = 16f * scale; size1 = 44f * scale; color0 = FxPalette.FIRE_CORE; fadeOut = .9f
    }
    repeat(2) {
        particle(anchor, at + rand(0f, .02f), rand(.16f, .22f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(5f * scale); this.y = y + 4f; vx = spread(20f); vy = rand(20f, 40f) * scale; drag = 4f
            size0 = 12f * scale; size1 = 24f * scale; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .9f; fadeOut = .6f
        }
    }
    // The column: soft white masses thrown up one above another, that slow, hang and fall back.
    repeat(4) { index ->
        particle(anchor, at + .01f + index * .02f, rand(.5f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(3f * scale); this.y = y + 2f; vx = spread(8f); vy = (90f + index * 50f) * scale; ay = -300f * scale; drag = 1.5f
            size0 = 10f * scale; size1 = rand(20f, 26f) * scale; color0 = FxPalette.WATER; alpha = .85f; fadeIn = .05f; fadeOut = .5f
        }
    }
    repeat((7 * scale).toInt().coerceAtLeast(4)) {
        particle(anchor, at, rand(.34f, .46f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(5f * scale); this.y = y + 2f; vx = spread(80f * scale); vy = rand(130f, 220f) * scale; ay = -800f
            size0 = rand(2.4f, 3.4f); size1 = 1.8f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .3f
        }
    }
    particle(anchor, at + .02f, .6f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y - 2f; size0 = 12f * scale; size1 = 60f * scale; color0 = FxPalette.WATER; alpha = .45f; fadeOut = .8f
    }
    particle(anchor, at + .04f, .6f) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y - 2f; size0 = 16f * scale; size1 = 36f * scale; color0 = FxPalette.WATER; alpha = .45f; fadeIn = .1f; fadeOut = .7f
    }
}

/**
 * A plane's straight flight: over the ground point ([x], [y]) at [start], heading [heading] degrees at [speed],
 * [height] above the ground, which changes by [climb] per second and [sink] per second squared.
 */
private class AirRun(
    val anchor: FxAnchor, val start: Float, val x: Float, val y: Float, val heading: Float, val speed: Float,
    val height: Float, val climb: Float = 0f, val sink: Float = 0f
) {
    val hx = MathUtils.cosDeg(heading)
    val hy = MathUtils.sinDeg(heading)
    fun groundX(t: Float) = x + hx * speed * (t - start)
    fun groundY(t: Float) = y + hy * speed * (t - start)
    fun heightAt(t: Float): Float { val age = t - start; return height + climb * age + .5f * sink * age * age }
    fun climbAt(t: Float) = climb + sink * (t - start)
    /** Where a point on the plane is drawn, [u] toward its nose and [w] to its left. */
    fun pointX(t: Float, u: Float = 0f, w: Float = 0f) = groundX(t) + hx * u - hy * w
    fun pointY(t: Float, u: Float = 0f, w: Float = 0f) = groundY(t) + hy * u + hx * w + heightAt(t)
}

/**
 * Draws [frame] along [run] until [until]: a dark rim under every piece so it holds against any terrain, the pieces,
 * and the plane's shadow on the ground beneath it.
 */
private fun FxScene.fly(frame: Airframe, run: AirRun, until: Float, fadeIn: Float = .1f, fadeOut: Float = .15f) {
    val life = until - run.start
    val scale = frame.scale
    fun piece(part: Part, layer: FxLayer, grow: Float, color: Color, alpha: Float, drop: Float) {
        val x = run.pointX(run.start, part.u * scale, part.w * scale)
        val y = run.pointY(run.start, part.u * scale, part.w * scale) - drop
        val along = part.along * scale + grow
        particle(run.anchor, run.start, life) {
            shape = part.shape; this.layer = layer; blend = if (part.light) FxBlend.ADD else FxBlend.NORMAL
            this.x = x; this.y = y; vx = run.hx * run.speed; vy = run.hy * run.speed
            if (drop == 0f) { vy += run.climb; ay = run.sink }
            size0 = along; size1 = along; aspect = (part.across * scale + grow) / along; rotation = run.heading + part.turn
            color0 = color; this.alpha = alpha; this.fadeIn = fadeIn; this.fadeOut = fadeOut
        }
    }
    for (part in frame.parts) if (part.shadow) piece(part, FxLayer.GROUND, 0f, Color.BLACK, .2f, run.height + 1f)
    for (part in frame.parts) if (part.shadow) piece(part, FxLayer.BODY, 1.4f, RIM, .8f, 0f)
    for (part in frame.parts) piece(part, if (part.light) FxLayer.LIGHT else FxLayer.BODY, 0f, part.color, part.alpha, 0f)
}

private val RIM = Color(.1f, .1f, .1f, 1f)

/** A tracer round from (x0, y0) striking (x1, y1) after [travel]: a bright streak along its path. */
private fun FxScene.tracer(at: Float, x0: Float, y0: Float, x1: Float, y1: Float, travel: Float, width: Float = 2.4f) {
    particle(FxAnchor.TARGET, at, travel) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = x0; y = y0; vx = (x1 - x0) / travel; vy = (y1 - y0) / travel
        size0 = 6f; size1 = 6f; aspect = width / 6f; stretch = .02f; alignToMotion = true
        color0 = FxPalette.TRACER; fadeIn = 0f; fadeOut = .15f
    }
}

/**
 * A round striking the defender's tile: a spout of water, or a wink of light and a spurt of dirt (masonry on a city)
 * settling into a dust puff, with a pock mark left behind when [mark] is set so the line of the burst shows.
 * Hard targets throw sparks.
 */
private fun FxScene.bulletHit(at: Float, x: Float, y: Float, onUnit: Boolean, scale: Float = 1f, mark: Boolean = true) {
    val hard = defenderBody == BodyKind.VEHICLE || defenderBody == BodyKind.SHIP || defenderBody == BodyKind.CITY
    val struck = onUnit && outcome != VisualAttackOutcome.WITHDRAWN
    if (targetWater && struck && defenderBody == BodyKind.SHIP) {
        // Rounds hammering a deck: a wink, sparks and a whiff of smoke instead of a spout.
        particle(FxAnchor.TARGET, at, .06f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y + 1f; size0 = 10f * scale; size1 = 3f; color0 = FxPalette.FIRE_CORE; alpha = .9f; fadeOut = .7f
        }
        particle(FxAnchor.TARGET, at + .02f, .35f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y + 3f; vx = spread(8f); vy = rand(14f, 22f); drag = 2.5f
            size0 = 5f; size1 = 14f; color0 = vary(FxPalette.SMOKE, .04f); alpha = .5f; fadeIn = .1f; fadeOut = .7f
        }
        sparks(at, x, y + 3f, count = 3, speed = 130f)
        return
    }
    // Thin jets thrown straight up: a streak while fast, shrinking to a speck as they slow, which reads at any zoom.
    fun jets(color: Color, count: Int, speed: Float) = repeat(count) {
        particle(FxAnchor.TARGET, at, rand(.2f, .26f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(1.5f); this.y = y + 1f; vx = spread(22f); vy = rand(.8f, 1.1f) * speed * scale; ay = -700f
            size0 = 2.6f * scale; size1 = 2.2f * scale; stretch = .07f; alignToMotion = true
            color0 = vary(color, .04f); alpha = .95f; fadeOut = .45f
        }
    }
    if (targetWater) {
        jets(FxPalette.WATER, 2, 165f)
        particle(FxAnchor.TARGET, at, .36f) {
            shape = FxShape.RING; layer = FxLayer.GROUND
            this.x = x; this.y = y; size0 = 4f; size1 = 18f * scale; color0 = FxPalette.WATER; alpha = .75f; fadeOut = .8f
        }
        particle(FxAnchor.TARGET, at + .02f, .3f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y + 4f; vy = 12f; drag = 2f
            size0 = 5f * scale; size1 = 13f * scale; color0 = FxPalette.WATER; alpha = .5f; fadeIn = .1f; fadeOut = .7f
        }
    } else {
        val dirt = if (targetCity) FxPalette.STONE else FxPalette.EARTH
        val dust = if (targetCity) FxPalette.STONE else SPURT
        particle(FxAnchor.TARGET, at, .06f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y + 1f; size0 = 9f * scale; size1 = 3f; color0 = FxPalette.FIRE_CORE; alpha = .95f; fadeOut = .7f
        }
        jets(dust, 2, 150f)
        particle(FxAnchor.TARGET, at + .02f, rand(.34f, .42f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(1.5f); this.y = y + 3f; vx = spread(6f); vy = rand(10f, 18f) * scale; drag = 2.5f
            size0 = 6f * scale; size1 = rand(14f, 17f) * scale; color0 = vary(dust, .05f); alpha = .55f; fadeIn = .1f; fadeOut = .7f
        }
        particle(FxAnchor.TARGET, at, .24f) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x; this.y = y + 1f; vx = spread(45f); vy = rand(90f, 130f) * scale; ay = -650f
            size0 = 2.6f; size1 = 2.2f; color0 = vary(dirt, .04f); fadeOut = .3f
        }
        if (mark) particle(FxAnchor.TARGET, at, end - at) {
            shape = FxShape.SOFT; layer = FxLayer.GROUND
            this.x = x; this.y = y; size0 = 4f * scale; size1 = 5f * scale; color0 = FxPalette.SCORCH; alpha = .35f; fadeIn = .05f; fadeOut = .35f
        }
    }
    if (struck && hard) sparks(at, x, y + 4f, count = 2, speed = 120f)
}

/** Dirt kicked up by a bullet: a shade lighter than drifting dust, so a spurt stands out against it. */
private val SPURT = Color(.86f, .79f, .62f, 1f)

/** A rocket from (x0, y0) to (x1, y1) by the defender: a bright motor drawing a pale smoke trail. */
private fun FxScene.rocket(at: Float, travel: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
    val vx = (x1 - x0) / travel; val vy = (y1 - y0) / travel
    particle(FxAnchor.TARGET, at, travel) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        x = x0; y = y0; this.vx = vx; this.vy = vy; size0 = 5.5f; size1 = 5.5f; aspect = .32f; alignToMotion = true
        color0 = ROCKET; fadeIn = 0f; fadeOut = 0f
    }
    particle(FxAnchor.TARGET, at, travel) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = x0; y = y0; this.vx = vx; this.vy = vy; size0 = 13f; size1 = 11f; color0 = FxPalette.EXHAUST; alpha = .95f; fadeIn = 0f; fadeOut = 0f
    }
    repeat(5) { index ->
        val fraction = (index + .5f) / 5
        particle(FxAnchor.TARGET, at + travel * fraction, rand(.3f, .4f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = x0 + (x1 - x0) * fraction; y = y0 + (y1 - y0) * fraction; this.vx = spread(6f); this.vy = rand(6f, 14f); drag = 2f
            size0 = 3f; size1 = rand(9f, 12f); color0 = vary(FxPalette.GUN_SMOKE, .04f); alpha = .5f; fadeIn = .1f; fadeOut = .7f
        }
    }
}

/** A rocket on a flight from the attacker's tile to the defender's, with its smoke trail. */
private fun FxScene.rocketFlight(at: Float, travel: Float, flight: FxFlight) {
    flying(at, travel, flight) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        size0 = 5.5f; size1 = 5.5f; aspect = .32f; alignToMotion = true; color0 = ROCKET; fadeIn = 0f; fadeOut = 0f
    }
    flying(at, travel, flight) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 13f; size1 = 11f; color0 = FxPalette.EXHAUST; alpha = .95f; fadeIn = 0f; fadeOut = 0f
    }
    flying(at, travel, flight) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 4f; size1 = 4f; aspect = .5f; stretch = .018f; alignToMotion = true; color0 = FxPalette.FIRE_CORE; fadeIn = 0f; fadeOut = 0f
    }
    repeat(6) { index ->
        val fraction = (index + .5f) / 6 * .92f
        flying(at + travel * fraction, rand(.32f, .4f), FxFlight(flight.fromX, flight.fromY, flight.toX, flight.toY, at = fraction)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            vx = spread(6f); vy = rand(6f, 14f); drag = 2f
            size0 = 5f; size1 = rand(11f, 14f); color0 = vary(FxPalette.GUN_SMOKE, .04f); alpha = .65f; fadeIn = .05f; fadeOut = .7f
        }
    }
}

private val ROCKET = Color(.82f, .82f, .78f, 1f)

/**
 * How far a melee attacker has lunged toward the defender at [time] for a first blow at [blow]; the timeline moves
 * the attacker's unit this way, and weapons fired from it must leave from where it is.
 */
private fun FxScene.lunge(time: Float, blow: Float): Float {
    val since = time - blow
    val t = when {
        since < -.22f -> 0f
        since < 0f -> 1f + since / .22f
        since < .25f -> 1f - since / .25f
        else -> 0f
    }
    return (length * .3f).coerceAtMost(20f) * t * t * (3 - 2 * t)
}

/** A column of earth (or masonry) thrown straight up by a bomb, so each blast of a stick stands apart. */
private fun FxScene.plume(at: Float, x: Float, y: Float, scale: Float) {
    val color = if (targetCity) FxPalette.STONE else FxPalette.EARTH
    repeat(2) { index ->
        particle(FxAnchor.TARGET, at + .02f + index * .02f, rand(.45f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(3f); this.y = y + 2f; vx = spread(10f); vy = (100f + index * 60f) * scale; drag = 3.5f
            size0 = 9f * scale; size1 = rand(18f, 24f) * scale; color0 = vary(color, .05f); color1 = vary(FxPalette.DUST, .05f)
            alpha = .85f; fadeIn = .05f; fadeOut = .55f
        }
    }
}

/** A flak shell bursting in the air: a flash, then a lumpy black puff that hangs and spreads. */
private fun FxScene.flak(anchor: FxAnchor, at: Float, x: Float, y: Float, big: Boolean = false) {
    val scale = if (big) 1.4f else 1f
    particle(anchor, at, .08f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 22f * scale; size1 = 8f * scale; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .7f
    }
    repeat(2) { index ->
        particle(anchor, at + index * .015f, rand(.5f, .62f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f) * index; this.y = y + spread(3f) * index; vx = spread(8f); vy = rand(4f, 10f); drag = 3f
            size0 = 10f * scale; size1 = rand(26f, 32f) * scale * (1f - index * .25f); sizeEase = FxEase.OUT
            color0 = vary(FxPalette.SMOKE_DARK, .03f); alpha = .9f; fadeIn = .03f; fadeOut = .6f
        }
    }
    repeat(if (big) 3 else 2) {
        val heading = rand(0f, 360f)
        particle(anchor, at, .14f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y; vx = MathUtils.cosDeg(heading) * 140f; vy = MathUtils.sinDeg(heading) * 140f; drag = 4f
            size0 = 2f; size1 = 1.2f; aspect = .5f; stretch = .03f; alignToMotion = true; color0 = FxPalette.TRACER; fadeOut = .6f
        }
    }
}

/** A missile blown apart in the air: flash, fireball, a ring of shock, and fragments falling out of a dark cloud. */
private fun FxScene.airburst(anchor: FxAnchor, at: Float, x: Float, y: Float) {
    particle(anchor, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 18f; size1 = 48f; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(anchor, at, .16f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 10f; size1 = 56f; color0 = FxPalette.FLASH; alpha = .4f; fadeOut = 1f
    }
    repeat(4) {
        particle(anchor, at + rand(0f, .03f), rand(.2f, .3f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(5f); this.y = y + spread(5f); vx = spread(40f); vy = spread(30f); drag = 4f
            size0 = rand(10f, 14f); size1 = rand(22f, 28f); color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .95f; fadeOut = .65f
        }
    }
    repeat(3) { index ->
        particle(anchor, at + .04f + index * .03f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(7f); this.y = y + spread(5f); vx = spread(14f); vy = rand(10f, 20f); drag = 1.6f
            size0 = 12f; size1 = rand(26f, 32f); color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .7f; fadeIn = .1f; fadeOut = .6f
        }
    }
    sparks(at, x, y, count = 8, speed = 190f, fan = 360f, anchor = anchor, color = FxPalette.FIRE_CORE)
    repeat(4) {
        particle(anchor, at, rand(.4f, .5f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x; this.y = y; vx = spread(80f); vy = rand(-10f, 70f); ay = -420f; drag = .8f
            size0 = rand(2f, 3.5f); size1 = size0; aspect = .6f; rotation = rand(0f, 360f); spin = spread(700f)
            color0 = FxPalette.DEBRIS; fadeOut = .3f
        }
    }
}

/** The plane hit by [run]'s last burst tumbles down burning, trailing smoke, and blows up where it hits the ground. */
private fun FxScene.fallInFlames(frame: Airframe, run: AirRun, hit: Float) {
    val anchor = run.anchor
    val water = if (anchor == FxAnchor.TARGET) targetWater else sourceWater
    val x0 = run.groundX(hit); val y0 = run.groundY(hit); val h0 = run.heightAt(hit)
    // It keeps some way on, is knocked up a moment, then drops: drag slows the whole motion, gravity pulls it down.
    val drag = 2.4f
    val forward = run.speed * .55f
    val pop = 16f
    val fall = .38f
    val decay = (1 - exp(-drag * fall)) / drag
    val gravity = 2f * (h0 + pop * decay) / (fall * fall)
    val vx = run.hx * forward; val vy = run.hy * forward
    val body = frame.wreck.first * frame.scale
    val span = frame.wreck.second * frame.scale
    val color = frame.parts.first().color
    // The wreck: its body and wings about one center, so the cross keeps its shape as it tumbles.
    for ((long, wide) in listOf(body to body * .2f, body * .3f to span)) {
        particle(anchor, hit, fall) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = x0; y = y0 + h0; this.vx = vx; this.vy = vy + pop; this.drag = drag; ay = -gravity
            size0 = long; size1 = long * .9f; aspect = wide / long; rotation = run.heading; spin = 520f
            color0 = color; color1 = FxPalette.SCORCH; fadeIn = 0f; fadeOut = 0f
        }
        particle(anchor, hit, fall) {
            shape = FxShape.RECT; layer = FxLayer.GROUND
            x = x0; y = y0 - 1f; this.vx = vx; this.vy = vy; this.drag = drag
            size0 = long; size1 = long * .9f; aspect = wide / long; rotation = run.heading; spin = 520f
            color0 = Color.BLACK; alpha = .2f; fadeIn = 0f; fadeOut = 0f
        }
    }
    particle(anchor, hit, fall) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = x0; y = y0 + h0; this.vx = vx; this.vy = vy + pop; this.drag = drag; ay = -gravity
        size0 = 18f; size1 = 13f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; alpha = .95f; fadeIn = 0f; fadeOut = 0f
    }
    // Fire and smoke left hanging along its fall.
    val puffs = 6
    repeat(puffs) { index ->
        val age = fall * (index + .5f) / puffs
        val moved = (1 - exp(-drag * age)) / drag
        val x = x0 + vx * moved
        val y = y0 + h0 + (vy + pop) * moved - .5f * gravity * age * age
        particle(anchor, hit + age, rand(.45f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y; this.vx = spread(6f); this.vy = rand(8f, 16f); this.drag = 2f
            size0 = 7f; size1 = rand(17f, 22f); color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .75f; fadeIn = .05f; fadeOut = .7f
        }
        particle(anchor, hit + age, .12f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y; size0 = 12f; size1 = 4f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; alpha = .85f; fadeOut = .6f
        }
    }
    // Pieces shed as it breaks up.
    repeat(3) {
        particle(anchor, hit, rand(.4f, .5f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = x0; y = y0 + h0; this.vx = vx * .4f + spread(60f); this.vy = rand(10f, 60f); ay = -500f; this.drag = .8f
            size0 = rand(2.5f, 4f); size1 = size0; aspect = .5f; rotation = rand(0f, 360f); spin = spread(800f)
            color0 = color; color1 = FxPalette.SCORCH; fadeOut = .3f
        }
    }
    val crash = hit + fall
    val x = x0 + vx * decay; val y = y0 + vy * decay
    blast(anchor, crash, x, y, .9f, water, smoke = false)
    if (!water) fire(crash + .05f, x, y, life = end - crash - .1f, scale = .9f, anchor = anchor)
}

/** The plane, hit but flying, breaks off its run and turns for home trailing smoke. */
private fun FxScene.turnBack(frame: Airframe, run: AirRun, hit: Float) {
    val side = if (chance(.5f)) 1f else -1f
    val back = AirRun(run.anchor, hit, run.groundX(hit), run.groundY(hit), run.heading + side * 135f, run.speed * .85f,
        run.heightAt(hit), climb = 22f)
    fly(frame, back, until = .95f, fadeIn = 0f, fadeOut = .3f)
    repeat(8) { index ->
        val at = hit + .04f + index * .065f
        particle(run.anchor, at, .4f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = back.pointX(at, -10f); y = back.pointY(at, -10f); vx = spread(5f); vy = rand(6f, 12f); drag = 2f
            size0 = 4f; size1 = rand(11f, 14f); color0 = vary(FxPalette.SMOKE); alpha = .55f - index * .04f; fadeIn = .1f; fadeOut = .7f
        }
    }
}
