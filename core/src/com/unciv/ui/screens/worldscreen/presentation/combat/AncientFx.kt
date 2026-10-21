package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import kotlin.math.abs

/*
 * Ancient and medieval land weapons: bows and slings, catapults, blades and mounted charges.
 * Nothing here burns or explodes, so blows read through light: pale dust, bright steel and flying wood and stone.
 */

/** Pale ash wood, so shafts read against grass and water where dark wood vanishes. */
private val PALE_WOOD = Color(.93f, .86f, .68f, 1f)
/** The palette's dust and stone lifted toward white, so a cloud thrown up from grass stands out from it. */
private val LIGHT_DUST: Color = FxPalette.DUST.cpy().lerp(Color.WHITE, .3f)
private val LIGHT_STONE: Color = FxPalette.STONE.cpy().lerp(Color.WHITE, .35f)

/** A volley lobbed from the archers' line: pale shafts trailing faint streaks, dropping onto the defender and into the ground. */
fun FxScene.arrows() {
    val variant = style.variant
    val d = distanceInTiles.coerceIn(1, 4)
    // Galleys and dromons loose fire-tipped bolts from the deck, even moored in a harbor city.
    val burning = variant == "bolts" && (sourceWater || attackerBody == BodyKind.SHIP)
    val era = style.era.coerceIn(0, 3)
    val count = when (variant) {
        "stones" -> 4; "javelins" -> 3; "bolts" -> if (burning) 4 else 5; "repeating" -> 8; "city" -> 8 + era; else -> 5 + era
    }
    val flat = variant == "bolts" || variant == "repeating"
    val arc = when (variant) { "bolts", "repeating" -> 6f + d * 3f; "javelins" -> 10f + d * 7f; "stones" -> 12f + d * 6f; else -> 18f + d * 9f }
    val travel = when { flat -> .19f; variant == "stones" -> .24f; else -> .28f } + d * (if (flat) .035f else .045f)
    val release = .08f
    val stagger = when (variant) { "repeating" -> .028f; "city" -> .022f; "javelins" -> .02f; else -> .026f }
    val shaft = when (variant) { "javelins" -> 18f; "bolts", "repeating" -> 10f; else -> 14f }
    val thickness = if (variant == "javelins" || flat) 2.3f else 2.1f
    val reach = if (length > 0f) length else 69f * d
    fireAt = release

    when (variant) {
        // The sling whirls once around the slinger's head before letting go.
        "stones" -> swoosh(release - .07f, ox(3f), oy(3f) + 12f, 8f, angle - 90f, 330f, 2f, .06f, FxAnchor.SOURCE, PALE_WOOD, .8f)
        // The throwing arm comes over toward the defender.
        "javelins" -> swoosh(release - .05f, ox(-2f), oy(-2f) + 10f, 10f, angle + 180f - facing * 30f, -facing * 150f, 2.2f, .05f,
            FxAnchor.SOURCE, PALE_WOOD, .75f)
    }

    // A repeating crossbow's stream lands a second blow partway through.
    val secondBlow = if (variant == "repeating") count / 2 + 1 else -1
    repeat(count) { index ->
        val launch = release + index * stagger + (if (variant == "city" && index >= count / 2) .05f else 0f) + spread(.006f)
        val fromAlong = if (variant == "city") rand(-12f, 10f) else 6f
        val fromSide = if (variant == "city") spread(20f)
            else (index - (count - 1) / 2f) * when { flat -> 2.5f; variant == "javelins" -> 8f; else -> 4f }
        // The missiles that land the blows always strike, so the damage shows as one visibly hits; the rest are a coin flip.
        val hit = !withdrawn && (index == 0 || index == secondBlow || chance(if (variant == "city") .45f else .55f))
        val toX: Float; val toY: Float
        if (hit) { toX = spread(6f); toY = rand(-6f, 9f) }
        else { toX = spread(19f); toY = -15f + spread(6f) }
        val flight = FxFlight(ox(fromAlong, fromSide), oy(fromAlong, fromSide) + 8f, toX, toY, arc)
        if (variant == "stones") {
            // A dark rim under a pale pebble keeps it visible over light and dark ground.
            flying(launch, travel, flight) {
                shape = FxShape.DISC; size0 = 5.8f; size1 = 5.8f; color0 = FxPalette.DEBRIS; alpha = .8f; fadeIn = 0f; fadeOut = 0f
            }
            flying(launch, travel, flight) { shape = FxShape.DISC; size0 = 4.2f; size1 = 4.2f; color0 = LIGHT_STONE; fadeIn = 0f; fadeOut = 0f }
        } else flying(launch, travel, flight) {
            shape = FxShape.RECT; alignToMotion = true; size0 = shaft; size1 = shaft; aspect = thickness / shaft
            color0 = PALE_WOOD; fadeIn = 0f; fadeOut = 0f
        }
        // A faint streak a beat behind each missile shows its path at a glance; pale like the shaft, not gunfire yellow.
        flying(launch + .018f, travel, flight) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT; alignToMotion = true
            size0 = 3f; size1 = 3f; aspect = .5f; stretch = if (variant == "stones") .028f else .034f
            color0 = if (burning) FxPalette.FIRE else PALE_WOOD; alpha = if (burning) .75f else .5f; fadeIn = .15f; fadeOut = .2f
        }
        if (burning) flying(launch, travel, flight) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            size0 = 9f; size1 = 8f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; alpha = .85f; fadeIn = 0f; fadeOut = 0f
        }
        val landing = launch + travel
        if (index == 0 || index == secondBlow) impact(landing)
        when {
            hit && targetCity -> {
                chunks(landing, toX, toY, 2, LIGHT_STONE, speed = 90f, size = 2.4f)
                puffs(landing, toX, toY - 2f, 1, .4f, LIGHT_STONE)
            }
            // Stones thud and shatter, on a shield or a hull alike.
            hit && variant == "stones" -> thud(landing, toX, toY)
            // Into a ship's side: splinters off the hull.
            hit && targetWater -> {
                glint(landing, toX, toY, 8f)
                chunks(landing, toX, toY, 2, PALE_WOOD, speed = 100f, size = 2.4f)
            }
            // Steel heads bite into shields and mail with a glint.
            hit -> {
                glint(landing, toX, toY, 10f)
                sparkle(landing, toX, toY, 2, speed = 120f)
            }
            targetWater -> ripple(landing, toX, toY, if (variant == "javelins") 1.2f else .9f)
            else -> {
                puffs(landing, toX, toY - 1f, 2, .42f)
                if (variant == "stones") bounce(landing, toX, toY, 4.2f, LIGHT_STONE)
                else stuck(landing, toX, toY, shaft * .62f, descent(reach, arc), thickness)
            }
        }
        // The first fire bolts to strike catch; those falling short hiss out in the sea or smoulder where they stuck.
        if (burning && index < 2) when {
            hit -> fireHit(landing, toX, toY)
            targetWater -> steam(landing, toX, toY)
        }
        if (burning && !hit && !targetWater) particle(FxAnchor.TARGET, landing, .3f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = toX; y = toY; size0 = 8f; size1 = 3f; color0 = FxPalette.FIRE; alpha = .8f; fadeOut = .7f
        }
    }
}

/** A siege engine's throw: the arm whips over, a tumbling stone arcs high over its shadow, and lands in a burst of earth or masonry. */
fun FxScene.catapult() {
    if (style.variant == "bolt") return ballista()
    val heavy = style.heavy || style.variant == "heavy"
    val scale = (if (heavy) 1.45f else 1f) * (1f + .06f * (style.era - 1).coerceIn(0, 3))
    val d = distanceInTiles.coerceIn(1, 4)
    val throwAt = .07f
    fireAt = throwAt
    // The arm swings up and over toward the defender, the frame jolting on its crossbar.
    swoosh(throwAt - .05f, ox(-3f), oy(-3f) + 4f, 12f * scale, angle + 180f - facing * 20f, -facing * 140f, 2.6f, .05f,
        FxAnchor.SOURCE, PALE_WOOD, .7f)
    if (!sourceWater) puffs(throwAt, ox(-2f), oy(-2f) - 12f, 3, .6f * scale, anchor = FxAnchor.SOURCE)

    val travel = (if (heavy) .38f else .32f) + d * .05f
    val arc = (if (heavy) 46f else 32f) + d * (if (heavy) 15f else 11f)
    // Stones come down at the defender's feet and the cloud rises over it.
    val toX = spread(5f); val toY = spread(4f) - 9f
    val flight = FxFlight(ox(8f), oy(8f) + 14f, toX, toY, arc)
    val landing = throwAt + travel
    val size = 7.5f * scale
    // A dark rim under a pale tumbling block reads as a rock against grass and water alike.
    flying(throwAt, travel, flight) {
        shape = FxShape.DISC; size0 = size * 1.3f; size1 = size0; color0 = FxPalette.DEBRIS; alpha = .9f; fadeIn = 0f; fadeOut = 0f
    }
    flying(throwAt, travel, flight) {
        shape = FxShape.RECT; size0 = size; size1 = size; aspect = .8f; rotation = rand(0f, 90f); spin = 720f * facing
        color0 = LIGHT_STONE; fadeIn = 0f; fadeOut = 0f
    }
    // Its shadow runs along the ground below, which sells the height of the lob.
    flying(throwAt, travel, FxFlight(ox(8f), oy(8f) - 12f, toX, toY - 4f)) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND; size0 = size * 2f; size1 = size * 1.6f
        color0 = FxPalette.SMOKE_DARK; alpha = .45f; fadeIn = .1f; fadeOut = .05f
    }
    // Grit shed along the way.
    val grit = if (heavy) 7 else 5
    repeat(grit) { index ->
        val fraction = (index + .5f) / grit * .9f
        flying(throwAt + travel * fraction, .26f, FxFlight(flight.fromX, flight.fromY, toX, toY, arc, at = fraction)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE; vy = 8f; vx = spread(6f)
            size0 = 3f * scale; size1 = 10f * scale; color0 = vary(LIGHT_DUST); alpha = .5f; fadeIn = .1f; fadeOut = .8f
        }
    }

    if (targetWater) {
        splash(landing, toX, toY, scale)
        if (!withdrawn) chunks(landing, toX, toY + 4f, 5, PALE_WOOD, speed = 160f * scale, size = 3.4f)
        if (heavy) ripple(landing + .06f, toX + spread(8f), toY + spread(4f), 1.4f)
    } else {
        val masonry = targetCity && !withdrawn
        val cloud = if (masonry) LIGHT_STONE else LIGHT_DUST
        // The first instant of the blow: a flash of the impact and a pale burst, then the cloud rolling out low
        // and a spout of earth thrown straight up.
        particle(FxAnchor.TARGET, landing, .07f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = toX; y = toY + 2f; size0 = 20f * scale; size1 = 42f * scale; color0 = FxPalette.FLASH; alpha = .5f; fadeOut = 1f
        }
        particle(FxAnchor.TARGET, landing, .2f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = toX; y = toY; size0 = 24f * scale; size1 = 64f * scale; color0 = cloud; fadeOut = .9f
        }
        ring(landing, toX, toY - 3f, 64f * scale, cloud, .85f)
        crater(landing, toX, toY - 3f, 22f * scale)
        puffs(landing, toX, toY, if (heavy) 8 else 6, 1.1f * scale, cloud)
        puffs(landing + .02f, toX, toY + 4f, if (heavy) 3 else 2, .8f * scale, cloud, pushY = 90f * scale)
        chunks(landing, toX, toY, (10 * scale).toInt() + if (masonry) 3 else 0, if (masonry) FxPalette.STONE else FxPalette.EARTH,
            speed = 200f * scale, size = 3.8f * scale.coerceAtMost(1.3f))
        // The stone breaks up on the defender or the walls; on empty ground it bounces once and rolls on.
        if (withdrawn) bounce(landing, toX, toY, size, LIGHT_STONE)
        else chunks(landing, toX, toY + 2f, 4, LIGHT_STONE, speed = 140f, size = 3.2f * scale)
        if (masonry) puffs(landing + .12f, toX, toY + 10f, if (heavy) 4 else 3, 1.2f * scale, LIGHT_STONE, alpha = .6f, pushY = -12f)
    }
    impact(landing, if (heavy) 2.4f else 1.2f)
}

/** A ballista's bolt: the arms snap, a long shaft flies flat and fast, splinters burst where it strikes and it stays in the ground. */
private fun FxScene.ballista() {
    val release = .08f
    fireAt = release
    val d = distanceInTiles.coerceIn(1, 4)
    // The bow arms snap forward on either side of the slider.
    for (side in listOf(-1f, 1f)) swoosh(release - .03f, ox(4f, side * 9f), oy(4f, side * 9f) + 4f, 7f,
        angle + side * 100f, -side * 80f, 1.8f, .03f, FxAnchor.SOURCE, PALE_WOOD, .8f)
    if (!sourceWater) puffs(release, ox(-2f), oy(-2f) - 12f, 2, .5f, anchor = FxAnchor.SOURCE)

    val travel = .16f + d * .035f
    val arc = 4f + d * 3f
    // A bolt that finds no one flies on into the ground (or the sea) beyond where the defender stood.
    val toX = ox(if (withdrawn) 10f else -4f)
    val toY = if (withdrawn) oy(10f) - (if (targetWater) 10f else 14f) else oy(-4f) + 2f
    val flight = FxFlight(ox(12f), oy(12f) + 4f, toX, toY, arc)
    flying(release, travel, flight) {
        shape = FxShape.RECT; alignToMotion = true; size0 = 22f; size1 = 22f; aspect = .12f; color0 = PALE_WOOD; fadeIn = 0f; fadeOut = 0f
    }
    flying(release + .014f, travel, flight) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT; alignToMotion = true
        size0 = 6f; size1 = 6f; aspect = .45f; stretch = .04f; color0 = PALE_WOOD; alpha = .55f; fadeIn = .1f; fadeOut = .15f
    }
    val landing = release + travel
    when {
        withdrawn && targetWater -> ripple(landing, toX, toY, 1.3f)
        withdrawn -> {
            puffs(landing, toX, toY, 3, .6f)
            stuck(landing, toX, toY, 13f, angle - facing * 25f, 2.6f)
        }
        else -> {
            glint(landing, toX, toY, 20f)
            chunks(landing, toX, toY, 7, PALE_WOOD, speed = 200f, heading = angle + facing * 20f, fan = 90f, size = 3.6f)
            if (!targetCity && !targetWater) sparkle(landing, toX, toY, 4, speed = 170f, heading = angle, fan = 100f)
            // Driven through, the bolt ends up in the ground behind the defender, or in the wall; at sea it holes the hull.
            when {
                targetWater -> ripple(landing + .03f, ox(12f), oy(12f) - 10f, 1f)
                targetCity -> {
                    puffs(landing, toX, toY - 8f, 3, .6f, LIGHT_STONE)
                    chunks(landing, toX, toY, 3, FxPalette.STONE, speed = 140f, heading = angle + 180f, fan = 120f, size = 3f)
                    stuck(landing, ox(-8f), oy(-8f), 12f, angle, 2.6f)
                }
                else -> {
                    puffs(landing, toX, toY - 8f, 3, .6f)
                    stuck(landing + .03f, ox(14f), oy(14f) - 14f, 13f, angle - facing * 25f, 2.6f)
                }
            }
        }
    }
    impact(landing, .6f)
}

/** Sword and spear: feet scuff in, a bright crescent cuts across the defender and steel flares where it bites, then blades clash again. */
fun FxScene.blades() {
    if (style.variant == "ram") return ram()
    val blow = .36f
    val era = style.era.coerceIn(0, 4)
    // Bronze and clubs flash warm; iron and steel flash white.
    val steel = if (era == 0) Color(1f, .9f, .7f, 1f) else FxPalette.STEEL
    // Footwork as the attacker closes in.
    if (!sourceWater) repeat(2) { index ->
        puffs(.14f + index * .09f, ox(4f + index * 10f), oy(4f + index * 10f) - 17f, 2, .45f, anchor = FxAnchor.SOURCE)
    }
    // The overhead swing: a crescent centered short of the defender, sweeping down through its front.
    swoosh(blow - .045f, ox(-27f), oy(-27f) + 5f, 19f, angle + facing * 80f, -facing * 160f, 4f, .05f, color = steel)
    val hx = ox(-11f); val hy = oy(-11f) + 3f
    val feetX = ox(-6f); val feetY = oy(-6f) - 16f
    if (withdrawn) {
        // The defender has slipped away: the blade bites into the ground where it stood.
        if (targetWater) ripple(blow, feetX, feetY, 1f) else {
            puffs(blow, feetX, feetY + 2f, 5, .85f)
            chunks(blow, feetX, feetY + 2f, 5, FxPalette.EARTH, speed = 110f, size = 2.8f)
        }
        impact(blow)
        return
    }
    clang(blow, hx, hy, 22f + era * 2f, 6 + era * 2, steel)
    // Chips off a shield, or off the stones of a wall.
    chunks(blow, hx, hy, if (targetCity) 4 else 3, if (targetCity) LIGHT_STONE else PALE_WOOD, speed = 120f, heading = angle + 180f,
        fan = 120f, size = if (targetCity) 2.8f else 2.4f)
    if (targetWater) ripple(blow + .02f, feetX, feetY, .8f)
    else puffs(blow + .01f, feetX, feetY, 3, .6f, if (targetCity) LIGHT_STONE else LIGHT_DUST)
    impact(blow)

    // The second exchange: the defender's riposte when it struck back and still stands, otherwise the attacker's backhand.
    // The fallen go down .08 s after the last impact, so a riposte that kills the attacker lands just before that.
    val riposte = (damageToAttacker ?: 0) > 0 && !defenderKilled
    val second = blow + if (riposte && attackerKilled) .06f else .13f
    if (riposte) {
        swoosh(second - .04f, ox(-20f), oy(-20f) + 2f, 13f, angle + 180f - facing * 70f, facing * 140f, 2.8f, .045f, color = steel)
        clang(second, ox(-31f), oy(-31f) + 3f, 13f + era, 3 + era, steel)
    } else {
        swoosh(second - .04f, ox(-23f), oy(-23f) + 1f, 13f, angle - facing * 70f, facing * 140f, 2.8f, .045f, color = steel)
        clang(second, ox(-12f), oy(-12f), 13f + era, 3 + era, steel)
        impact(second)
    }
}

/** A battering ram: the iron-shod beam slams the wall, masonry and splinters burst out, and dust rolls down the stones. */
private fun FxScene.ram() {
    val blow = .38f
    val walls = targetCity && !withdrawn
    // The ram rumbles forward on its wheels.
    if (!sourceWater) repeat(3) { index ->
        for (side in listOf(-8f, 8f)) {
            puffs(.1f + index * .08f, ox(-6f + index * 8f, side), oy(-6f + index * 8f, side) - 14f, 1, .55f, anchor = FxAnchor.SOURCE)
        }
    }
    // Walls stand at the edge of the city's tile; a unit is struck at its front.
    val hx = ox(if (walls) -28f else -13f); val hy = oy(if (walls) -28f else -13f)
    if (targetWater) {
        splash(blow, hx, hy - 8f, .9f)
        impact(blow, .8f)
        return
    }
    if (withdrawn) {
        puffs(blow, hx, hy - 12f, 5, 1f)
        ring(blow, hx, hy - 12f, 44f, LIGHT_DUST, .6f)
        impact(blow, .8f)
        return
    }
    val cloud = if (walls) LIGHT_STONE else LIGHT_DUST
    particle(FxAnchor.TARGET, blow, .09f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = hx; y = hy + 2f; size0 = 18f; size1 = 40f; color0 = FxPalette.FLASH; alpha = .6f; fadeOut = .9f
    }
    particle(FxAnchor.TARGET, blow, .18f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        x = hx; y = hy; size0 = 22f; size1 = 60f; color0 = cloud; fadeOut = .9f
    }
    ring(blow, hx, hy - 4f, 66f, cloud, .7f)
    puffs(blow, hx, hy - 2f, 8, 1.3f, cloud)
    // Masonry bursts back toward the ram and up; splinters fly off its head.
    val back = MathUtils.atan2(1.2f - dir.y, -dir.x) * MathUtils.radiansToDegrees
    chunks(blow, hx, hy + 4f, if (walls) 11 else 6, if (walls) FxPalette.STONE else FxPalette.EARTH, speed = 200f, heading = back,
        fan = 120f, size = 4.4f)
    chunks(blow, hx, hy + 2f, 5, PALE_WOOD, speed = 150f, heading = back + facing * 30f, fan = 140f, size = 3.6f)
    if (walls) {
        // Loosened stones drop down the wall face, and dust hangs where they fell.
        repeat(5) { index ->
            particle(FxAnchor.TARGET, blow + .08f + index * .05f, .3f) {
                shape = FxShape.RECT; layer = FxLayer.BODY
                x = hx + spread(12f); y = hy + rand(10f, 20f); vy = -20f; ay = -600f
                size0 = rand(3f, 4.6f); size1 = size0; aspect = rand(.6f, .9f); rotation = rand(0f, 90f); spin = spread(300f)
                color0 = vary(FxPalette.STONE); fadeOut = .2f
            }
        }
        puffs(blow + .16f, hx, hy - 8f, 4, 1.3f, LIGHT_STONE, alpha = .6f)
    }
    impact(blow, if (walls) 2f else 1.2f)
}

/** A mounted charge: hooves throw up a trail of dust, the lance drives home in a flash with splinters flying, and dust rolls over the clash. */
fun FxScene.charge() {
    val blow = .38f
    val era = style.era.coerceIn(0, 5)
    val heavy = era >= 2
    val sabre = era >= 4
    val kick = if (heavy) 1.15f else 1f
    // Hoofbeats: dust and clods thrown back as the rider gallops in, keeping pace with the lunge.
    if (!sourceWater) {
        val lungeStart = blow - .22f
        val beats = 8
        repeat(beats) { index ->
            val t = .05f + index * (blow - .03f) / beats
            val p = ((t - lungeStart) / .22f).coerceIn(0f, 1f)
            val along = 20f * p * p * (3 - 2 * p) - 8f
            val side = spread(7f)
            puffs(t, ox(along, side), oy(along, side) - 16f, 1, .8f * kick, pushX = -dir.x * 35f, anchor = FxAnchor.SOURCE)
            if (index % 2 == 1) chunks(t, ox(along), oy(along) - 16f, 1, FxPalette.EARTH, speed = 90f,
                heading = MathUtils.atan2(1f - dir.y, -dir.x) * MathUtils.radiansToDegrees, fan = 50f, size = 2.6f, anchor = FxAnchor.SOURCE)
        }
    }
    val hx = ox(-11f); val hy = oy(-11f) + 3f
    if (sabre) {
        // Cavalry cuts with the sabre from the saddle.
        swoosh(blow - .045f, ox(-25f), oy(-25f) + 6f, 17f, angle + facing * 80f, -facing * 160f, 3.4f, .05f)
    } else {
        // The couched lance drives in: a long bright line along the charge with speed lines beside it.
        particle(FxAnchor.TARGET, blow - .07f, .1f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(-34f); y = oy(-34f) + 3f; vx = dir.x * 220f; vy = dir.y * 220f
            size0 = 32f; size1 = 22f; aspect = .085f; rotation = angle; color0 = FxPalette.STEEL; alpha = .95f; fadeIn = .2f; fadeOut = .6f
        }
        for (side in listOf(-8f, 8f)) particle(FxAnchor.TARGET, blow - .09f, .1f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(-40f, side); y = oy(-40f, side) + 1f; vx = dir.x * 200f; vy = dir.y * 200f
            size0 = 18f; size1 = 10f; aspect = .07f; rotation = angle; color0 = PALE_WOOD; alpha = .5f; fadeIn = .2f; fadeOut = .6f
        }
    }
    val feetX = ox(-6f); val feetY = oy(-6f) - 16f
    val cloud = if (targetCity) LIGHT_STONE else LIGHT_DUST
    if (withdrawn) {
        if (targetWater) ripple(blow, feetX, feetY, 1f) else {
            puffs(blow, feetX, feetY, 5, 1.1f * kick, pushX = dir.x * 40f)
            ring(blow, feetX, feetY, 52f, LIGHT_DUST, .6f)
        }
        impact(blow)
        return
    }
    glint(blow, hx, hy, if (heavy) 24f else 20f)
    val forwardUp = MathUtils.atan2(dir.y + .9f, dir.x) * MathUtils.radiansToDegrees
    if (sabre) sparkle(blow, hx, hy, 8, speed = 200f)
    else {
        // The lance shatters.
        chunks(blow, hx, hy, if (heavy) 8 else 6, PALE_WOOD, speed = 210f, heading = forwardUp, fan = 120f, size = 3.8f)
        if (heavy) sparkle(blow, hx, hy, 5, speed = 190f, heading = forwardUp, fan = 140f)
    }
    if (targetWater) splash(blow, feetX, feetY, .7f)
    else {
        puffs(blow, feetX, feetY, 6, 1.2f * kick, cloud, pushX = dir.x * 45f, pushY = dir.y * 20f)
        ring(blow, feetX, feetY, 60f * kick, cloud, .65f)
    }
    impact(blow, if (heavy) .5f else 0f)

    // The melee that follows: the defender's counter when it struck back and still stands, otherwise the rider's
    // follow-up cut. As with blades, a counter that unhorses the rider lands before it falls.
    val counter = (damageToAttacker ?: 0) > 0 && !defenderKilled
    val second = blow + if (counter && attackerKilled) .06f else .15f
    if (counter) {
        particle(FxAnchor.TARGET, second - .05f, .09f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = ox(-12f); y = oy(-12f) + 6f; vx = -dir.x * 200f; vy = -dir.y * 200f
            size0 = 22f; size1 = 14f; aspect = .09f; rotation = angle; color0 = FxPalette.STEEL; alpha = .9f; fadeIn = .2f; fadeOut = .6f
        }
        clang(second, ox(-33f), oy(-33f) + 5f, 15f, 4, FxPalette.STEEL)
    } else {
        swoosh(second - .045f, ox(-24f), oy(-24f) + 4f, 14f, angle - facing * 70f, facing * 140f, 2.8f, .045f)
        clang(second, ox(-12f), oy(-12f) + 2f, 15f, 4, FxPalette.STEEL)
        impact(second)
    }
}

/**
 * A blade's sweep: a bright crescent of [radius] around (x, y), drawn from [from] degrees through [sweep] over [duration],
 * thickest mid-swing, with a faint glow so it carries on bright ground.
 */
private fun FxScene.swoosh(at: Float, x: Float, y: Float, radius: Float, from: Float, sweep: Float, width: Float = 3f,
                           duration: Float = .05f, anchor: FxAnchor = FxAnchor.TARGET, color: Color = FxPalette.STEEL, alpha: Float = .9f) {
    val segments = 10
    val step = abs(sweep) * MathUtils.degreesToRadians * radius / (segments - 1)
    repeat(segments) { index ->
        val t = index / (segments - 1f)
        val heading = from + sweep * t
        val thickness = .7f + width * MathUtils.sin(MathUtils.PI * (.12f + .76f * t))
        particle(anchor, at + duration * t, .12f) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + MathUtils.cosDeg(heading) * radius; this.y = y + MathUtils.sinDeg(heading) * radius
            size0 = step * 1.6f; size1 = step * 1.1f; aspect = thickness / (step * 1.6f); rotation = heading + 90f
            color0 = color; this.alpha = alpha; fadeOut = .85f
        }
    }
    repeat(3) { index ->
        val heading = from + sweep * (.2f + .3f * index)
        particle(anchor, at + duration * (.2f + .3f * index), .1f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + MathUtils.cosDeg(heading) * radius * .9f; this.y = y + MathUtils.sinDeg(heading) * radius * .9f
            size0 = radius * 1.1f; size1 = radius * .8f; color0 = color; this.alpha = alpha * .22f; fadeOut = .9f
        }
    }
}

/** A four-pointed star of light where steel bites, over a short white flash. */
private fun FxScene.glint(at: Float, x: Float, y: Float, size: Float = 16f) {
    particle(FxAnchor.TARGET, at, .09f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = size * 1.3f; size1 = size * .5f; color0 = FxPalette.FLASH; alpha = .9f; fadeOut = .9f
    }
    val tilt = spread(15f)
    for (rotation in listOf(45f + tilt, 135f + tilt)) particle(FxAnchor.TARGET, at, .08f) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = size; size1 = size * .35f; aspect = .1f; this.rotation = rotation
        color0 = FxPalette.FLASH; fadeOut = .7f
    }
}

/** Steel on steel: a glint and a spray of sparks thrown up and sideways. */
private fun FxScene.clang(at: Float, x: Float, y: Float, size: Float, sparks: Int, color: Color) {
    glint(at, x, y, size)
    sparkle(at, x, y, sparks, color = color)
}

/** Sparks off struck steel: short hot streaks, a little heavier than gunfire's, thrown around [heading] and arcing down as they cool. */
private fun FxScene.sparkle(at: Float, x: Float, y: Float, count: Int, speed: Float = 190f, heading: Float = 90f, fan: Float = 220f,
                            color: Color = FxPalette.STEEL) {
    repeat(count) {
        val direction = heading + spread(fan / 2)
        val velocity = rand(.55f, 1.1f) * speed
        particle(FxAnchor.TARGET, at + rand(0f, .015f), rand(.13f, .22f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(direction) * velocity; vy = MathUtils.sinDeg(direction) * velocity; ay = -520f; drag = 3f
            size0 = 2.6f; size1 = 1.6f; aspect = .6f; stretch = .045f; alignToMotion = true
            color0 = color; color1 = FxPalette.FIRE; fadeOut = .5f
        }
    }
}

/**
 * Dust thrown up by a blow, a landing or hooves: pale puffs billowing out low over the ground, then thinning.
 * A soft puff only reads near its middle, so they are large and dense to show on grass.
 * [pushX] and [pushY] carry the whole cloud, for dust driven on by a charge or flung up by a heavy landing.
 */
private fun FxScene.puffs(at: Float, x: Float, y: Float, count: Int, scale: Float = 1f, color: Color = LIGHT_DUST, alpha: Float = .9f,
                          pushX: Float = 0f, pushY: Float = 0f, anchor: FxAnchor = FxAnchor.TARGET) {
    repeat(count) {
        val heading = rand(0f, 360f)
        val speed = rand(30f, 80f) * scale
        particle(anchor, at + rand(0f, .03f), rand(.4f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f * scale); this.y = y + spread(2f * scale)
            vx = MathUtils.cosDeg(heading) * speed + pushX; vy = MathUtils.sinDeg(heading) * speed * .4f + 14f + pushY; drag = 3.2f
            size0 = rand(10f, 14f) * scale; size1 = rand(28f, 36f) * scale
            color0 = vary(color, .04f); this.alpha = alpha; fadeIn = .06f; fadeOut = .6f
        }
    }
}

/** Chunks thrown out of the ground or a wall: earth clods, masonry or splinters that tumble and fall back. */
private fun FxScene.chunks(at: Float, x: Float, y: Float, count: Int, color: Color, speed: Float = 150f, heading: Float = 90f,
                           fan: Float = 140f, size: Float = 3.5f, anchor: FxAnchor = FxAnchor.TARGET) {
    repeat(count) {
        val direction = heading + spread(fan / 2)
        val velocity = rand(.55f, 1.1f) * speed
        particle(anchor, at + rand(0f, .02f), rand(.3f, .45f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(3f); this.y = y + spread(2f)
            vx = MathUtils.cosDeg(direction) * velocity; vy = MathUtils.sinDeg(direction) * velocity + 40f; ay = -760f; drag = .6f
            size0 = rand(.6f, 1.15f) * size; size1 = size0 * .8f; aspect = rand(.45f, .85f)
            rotation = rand(0f, 360f); spin = spread(800f); color0 = vary(color, .05f); fadeOut = .25f
        }
    }
}

/** A ring of dust pushed out along the ground by a heavy blow. */
private fun FxScene.ring(at: Float, x: Float, y: Float, size: Float, color: Color, alpha: Float = .5f) {
    particle(FxAnchor.TARGET, at, .32f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = size * .2f; size1 = size; color0 = color; this.alpha = alpha; fadeOut = 1f
    }
}

/** Torn ground where something heavy came down, fading as the attack ends. */
private fun FxScene.crater(at: Float, x: Float, y: Float, size: Float) {
    particle(FxAnchor.TARGET, at + .02f, end - at - .02f) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = size * .5f; size1 = size; color0 = FxPalette.EARTH; alpha = .35f; fadeIn = .08f; fadeOut = .35f
    }
}

/** A light missile dropping into water: a ring and a few droplets. */
private fun FxScene.ripple(at: Float, x: Float, y: Float, scale: Float = 1f) {
    particle(FxAnchor.TARGET, at, .4f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = 4f * scale; size1 = 24f * scale; color0 = FxPalette.WATER; alpha = .7f; fadeOut = .8f
    }
    repeat(3) {
        particle(FxAnchor.TARGET, at, rand(.2f, .3f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(2f); this.y = y; vx = spread(30f * scale); vy = rand(70f, 120f) * scale; ay = -700f
            size0 = 2.6f; size1 = 1.8f; color0 = FxPalette.WATER; alpha = .9f; fadeOut = .3f
        }
    }
}

/** A sling stone striking home: a dull flash, a puff of grit and the stone breaking back off the shield. */
private fun FxScene.thud(at: Float, x: Float, y: Float) {
    particle(FxAnchor.TARGET, at, .08f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 16f; size1 = 7f; color0 = FxPalette.FLASH; alpha = .6f; fadeOut = .9f
    }
    // A brief puff: a lasting cloud would hang over the defender after every stone.
    particle(FxAnchor.TARGET, at, .22f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x; this.y = y; vy = 20f; drag = 3f; size0 = 8f; size1 = 18f; color0 = vary(LIGHT_DUST); alpha = .7f; fadeOut = .8f
    }
    chunks(at, x, y, 3, LIGHT_STONE, speed = 110f, heading = angle + 180f, fan = 150f, size = 2.6f)
}

/** A fire bolt striking home: a flare of burning pitch, a spit of embers, and flames catching where it bit. */
private fun FxScene.fireHit(at: Float, x: Float, y: Float) {
    particle(FxAnchor.TARGET, at, .12f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 22f; size1 = 10f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .8f
    }
    sparkle(at, x, y, 3, speed = 140f, color = FxPalette.FIRE_CORE)
    fire(at + .02f, x, y - 3f, life = .4f, scale = .75f)
}

/** A hiss of steam where a burning bolt drops into the sea. */
private fun FxScene.steam(at: Float, x: Float, y: Float) {
    particle(FxAnchor.TARGET, at + .02f, .35f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x; this.y = y + 2f; vy = 24f; drag = 2f; size0 = 6f; size1 = 16f
        color0 = FxPalette.WATER; alpha = .55f; fadeIn = .15f; fadeOut = .7f
    }
}

/** A missile that misses and stays where it fell, tail up, until the attack ends: a [shaft] px shaft, [thickness] px thick. */
private fun FxScene.stuck(at: Float, x: Float, y: Float, shaft: Float, heading: Float, thickness: Float) {
    particle(FxAnchor.TARGET, at, end - at) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        // Centered half a shaft back along the way it came, so the head is in the ground.
        this.x = x - MathUtils.cosDeg(heading) * shaft / 2; this.y = y - MathUtils.sinDeg(heading) * shaft / 2
        size0 = shaft; size1 = shaft; aspect = thickness / shaft; rotation = heading; color0 = PALE_WOOD; fadeOut = .25f
    }
}

/** The heading of a lobbed missile as it comes down, for arrows left standing in the ground. */
private fun FxScene.descent(reach: Float, arc: Float): Float =
    MathUtils.atan2(dir.y * reach - 4f * arc, dir.x * reach) * MathUtils.radiansToDegrees

/** A stone that lands on earth, hops once and rolls to a stop. */
private fun FxScene.bounce(at: Float, x: Float, y: Float, size: Float, color: Color) {
    val hop = .2f
    particle(FxAnchor.TARGET, at, hop) {
        shape = FxShape.DISC; layer = FxLayer.BODY
        this.x = x; this.y = y; vx = dir.x * 50f; vy = 50f; ay = -500f
        size0 = size; size1 = size; color0 = color; fadeOut = 0f
    }
    particle(FxAnchor.TARGET, at + hop, end - at - hop) {
        shape = FxShape.DISC; layer = FxLayer.BODY
        this.x = x + dir.x * 50f * hop; this.y = y; vx = dir.x * 14f; drag = 6f
        size0 = size; size1 = size; color0 = color; fadeOut = .3f
    }
}
