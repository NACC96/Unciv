package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.unciv.logic.presentation.VisualAttackOutcome
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Artillery and missiles: cannon, modern artillery, rocket salvos and guided missiles.
 */

/**
 * Black-powder guns: a flash and a rolling bank of white smoke, then an iron ball that ploughs into the ground at the
 * defender's feet and skips on through. Industrial guns fire bursting shells instead; a city fires a ripple of three.
 */
fun FxScene.cannon() {
    val city = style.variant == "city"
    val shells = style.era >= 4
    val guns = if (city) 3 else 1
    val along = if (city) 18f else 14f
    val travel = .24f + distanceInTiles * .045f
    val arc = 8f + distanceInTiles * 5f
    fireAt = .05f
    repeat(guns) { gun ->
        val fire = .05f + gun * .07f + if (gun > 0) spread(.01f) else 0f
        val side = when (gun) { 1 -> 13f; 2 -> -13f; else -> 0f }
        muzzleFlash(fire, along, side, scale = 1.25f)
        powderSmoke(fire + .008f, along, side, if (city) .85f else 1f, jets = if (city) 3 else 5)
        val aimed = gun == 0 && outcome != VisualAttackOutcome.WITHDRAWN
        // Round shot strikes the ground just short of the defender, at its feet: the unit is drawn over anything behind it.
        // A ship takes it in the side of the hull.
        val short = when { shells -> 0f; hullHit -> 4f; else -> 12f }
        val drop = when { shells -> 0f; hullHit -> 5f; else -> 12f }
        val toX = ox(-short) + spread(if (aimed) 3f else 12f)
        val toY = oy(-short) - drop + spread(if (aimed) 2f else 6f)
        val landing = roundShot(fire + .01f, travel, arc, along + 2f, side, toX, toY, wisp = gun == 0)
        if (shells) blast(landing, toX, toY, .85f, smoke = true)
        else solidShot(landing, toX, toY, light = gun > 0)
        impact(landing, if (gun == 0) 1.5f else 1f)
    }
}

/** A modern heavy gun: a blast that throws dust around the battery, a glowing shell lobbed high, a crater-digging blast. */
fun FxScene.artillery() {
    val city = style.variant == "city"
    val shells = if (style.heavy || city) 2 else 1
    val power = (if (city) .85f else 1f) + (style.era - 4).coerceIn(0, 3) * .06f
    val along = if (city) 18f else 14f
    val fire = .05f
    val gap = if (defenderKilled) .07f else .1f
    val travel = .3f + distanceInTiles * .04f
    val arc = 36f + distanceInTiles * 13f
    fireAt = fire
    repeat(shells) { index ->
        val at = fire + index * gap
        val side = if (shells == 1) 0f else if (index == 0) -5f else 6f
        gunBlast(at, along, side, power, dusty = index == 0)
        // The first shell is on target; the next falls a little short or long of it.
        val miss = when {
            index > 0 -> (if (chance(.5f)) -1f else 1f) * rand(14f, 20f)
            outcome == VisualAttackOutcome.WITHDRAWN -> spread(10f)
            else -> 0f
        }
        val toX = ox(miss) + spread(if (index == 0) 4f else 8f)
        val toY = oy(miss) + spread(3f)
        val landing = shell(at + .015f, travel, arc, along + 2f, side, toX, toY)
        shellBlast(landing, toX, toY, (if (index == 0) 1.3f else 1.05f) * power)
        impact(landing, (if (index == 0) 3.5f else 2.5f) * if (city) .75f else 1f)
    }
}

/**
 * A launcher ripple-firing its rockets: each leaps off its rail in a gust of smoke and streaks away on a bright plume,
 * drawing a white trail, and the salvo walks across the target in a string of blasts.
 */
fun FxScene.rockets() {
    if (style.variant == "fire arrows") return fireArrows()
    val count = if (style.heavy) 6 else 4
    val first = .05f
    val gap = if (defenderKilled) .03f else .036f
    val travel = .26f + distanceInTiles * .04f
    val arc = 22f + distanceInTiles * 9f
    fireAt = first
    if (!sourceWater) dust(first, ox(-8f), oy(-8f) - 4f, .9f, FxAnchor.SOURCE, puffs = 4)
    // The salvo's exhaust gathers into a cloud that hangs over the launcher.
    repeat(3) { index ->
        particle(FxAnchor.SOURCE, first + .03f + index * gap * 1.5f, rand(.6f, .75f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(-6f + index * 4f) + spread(5f); y = oy(-6f + index * 4f) + 4f + spread(3f)
            vx = -dir.x * rand(10f, 20f) + spread(6f); vy = rand(14f, 24f); drag = 1.5f
            size0 = 16f; size1 = rand(38f, 46f); color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .6f; fadeIn = .15f; fadeOut = .6f
        }
    }
    val scatter = if (outcome == VisualAttackOutcome.WITHDRAWN) 1.4f else 1f
    repeat(count) { index ->
        val launch = first + index * gap
        val side = (index % 3 - 1) * 4f
        // Each rocket lands a little further on, so the salvo walks across the tile.
        val walk = (index - (count - 1) / 2f) * 8f
        val toX = (ox(walk) + spread(11f)) * scatter
        val toY = (oy(walk) + spread(8f)) * scatter
        backblast(launch, side)
        // Each flies a slightly different arc and lays its trail between the others', so the salvo draws one smooth band.
        val landing = rocket(launch, travel, arc * rand(.9f, 1.12f), 10f, side, toX, toY, phase = (index + .5f) / count)
        blast(landing, toX, toY, .72f)
        if (index % 2 == 0) risingSmoke(landing + .04f, toX, toY, .7f, 1)
        when (index) {
            0 -> impact(landing, 2f)
            count - 1 -> impact(landing, 1.5f)
            count / 2 -> impact(landing, 1.2f)
        }
    }
}

/** A guided missile: it leaps off its base on a plume of fire, draws a white contrail across the map and dives into a huge blast. */
fun FxScene.missile() {
    when (style.variant) {
        "sam" -> return samVolley()
        "cruiser" -> return cruiserSalvo()
    }
    val launch = .06f
    val travel = .32f + distanceInTiles * .03f
    val arc = 24f + distanceInTiles * 6f
    fireAt = launch
    particle(FxAnchor.SOURCE, launch, .12f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = ox(4f); y = oy(4f) + 4f; size0 = 30f; size1 = 12f; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
    }
    // The motor's blast rolls a low cloud out over the launch site.
    repeat(5) { index ->
        val heading = index * 72f + spread(20f)
        val speed = rand(50f, 80f)
        particle(FxAnchor.SOURCE, launch + index * .01f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(4f); y = oy(4f) + 2f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed * .5f + 10f; drag = 3.5f
            size0 = 8f; size1 = rand(20f, 26f); color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .55f; fadeIn = .08f; fadeOut = .7f
        }
    }
    val toX = spread(if (outcome == VisualAttackOutcome.WITHDRAWN) 14f else 3f)
    val toY = spread(2f)
    val landing = guided(launch, travel, arc, 6f, 0f, toX, toY, FxEase.IN, puffs = trailPuffs(7f, 12, 40))
    warhead(landing, toX, toY, 1.55f)
    impact(landing, 4f)
}

/** A missile cruiser's vertical launch: each missile bursts out of the deck in a column of smoke, pitches over and dives on the target. */
private fun FxScene.cruiserSalvo() {
    val first = .06f
    val gap = if (defenderKilled) .07f else .1f
    val boost = .1f
    val climb = 380f
    val slowing = 760f
    val rise = climb * boost - .5f * slowing * boost * boost
    val travel = .26f + distanceInTiles * .04f
    val arc = 16f + distanceInTiles * 5f
    fireAt = first
    repeat(2) { index ->
        val launch = first + index * gap
        val side = if (index == 0) -5f else 5f
        val deckX = ox(-3f, side); val deckY = oy(-3f, side) + 5f
        particle(FxAnchor.SOURCE, launch, .1f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = deckX; y = deckY; size0 = 26f; size1 = 10f; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
        }
        repeat(3) {
            val sideways = spread(60f)
            particle(FxAnchor.SOURCE, launch + it * .012f, rand(.55f, .7f)) {
                shape = FxShape.SOFT; layer = FxLayer.SMOKE
                x = deckX; y = deckY
                vx = perp.x * sideways + dir.x * spread(30f); vy = perp.y * sideways + rand(10f, 25f); drag = 3f
                size0 = 8f; size1 = rand(20f, 26f); color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .6f; fadeIn = .06f; fadeOut = .7f
            }
        }
        // The climb straight off the deck, leaving a column of smoke.
        particle(FxAnchor.SOURCE, launch, boost) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = deckX; y = deckY - 6f; vy = climb; ay = -slowing; size0 = 18f; size1 = 14f
            color0 = FxPalette.EXHAUST; alpha = .85f; fadeOut = 0f
        }
        particle(FxAnchor.SOURCE, launch, boost) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            x = deckX; y = deckY; vy = climb; ay = -slowing; size0 = 9f; size1 = 9f; aspect = .24f; alignToMotion = true
            color0 = FxPalette.GUN_SMOKE; fadeOut = 0f
        }
        particle(FxAnchor.SOURCE, launch, boost) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = deckX; y = deckY - 3f; vy = climb; ay = -slowing; size0 = 7f; size1 = 7f; color0 = FxPalette.FIRE_CORE; fadeOut = 0f
        }
        repeat(6) { puff ->
            val t = boost * (puff + .5f) / 6
            particle(FxAnchor.SOURCE, launch + t, .55f) {
                shape = FxShape.SOFT; layer = FxLayer.SMOKE
                x = deckX + spread(1.5f); y = deckY + climb * t - .5f * slowing * t * t - 4f
                vx = spread(6f); vy = rand(4f, 10f); drag = 1.5f
                size0 = 8f; size1 = rand(16f, 20f); color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .75f; fadeIn = .05f; fadeOut = .6f
            }
        }
        val aimed = index == 0 && outcome != VisualAttackOutcome.WITHDRAWN
        val toX = spread(if (aimed) 4f else 12f)
        val toY = spread(if (aimed) 3f else 7f)
        val fromX = deckX; val fromY = deckY + rise
        val landing = guided(launch + boost, travel, arc, alongOf(fromX, fromY), sideOf(fromX, fromY), toX, toY, FxEase.LINEAR, puffs = trailPuffs(7f, 12, 30))
        // One flash for the salvo: a second bloom right after the first would only white out the target.
        if (index == 0) warhead(landing, toX, toY, 1.25f) else shellBlast(landing, toX, toY, 1f)
        impact(landing, if (index == 0) 3f else 2.2f)
    }
}

/** A mobile SAM turned on troops: two missiles snap off the rails and burst over the defender, raining fragments. */
private fun FxScene.samVolley() {
    val first = .1f
    val gap = .07f
    val travel = .2f + distanceInTiles * .03f
    val arc = 26f + distanceInTiles * 10f
    val height = 15f
    fireAt = first
    repeat(2) { index ->
        val launch = first + index * gap
        val side = if (index == 0) 3f else -3f
        backblast(launch, side, .8f)
        val burstX = spread(5f)
        val burstY = height + spread(3f)
        val landing = guided(launch, travel, arc, 4f, side, burstX, burstY, FxEase.IN, puffs = trailPuffs(8f, 6, 10), scale = .85f)
        airburst(landing, burstX, burstY, burstY)
        impact(landing, if (index == 0) 1f else .6f)
    }
}

/**
 * A Korean hwach'a's volley of rocket arrows: the cart erupts in sparks and smoke, a swarm of fiery streaks rises on
 * threads of grey smoke, and they rain down across the defender in pops of fire that leave small blazes burning.
 */
private fun FxScene.fireArrows() {
    val count = 12
    val first = .06f
    val travel = .3f + distanceInTiles * .04f
    val arc = 14f + distanceInTiles * 8f
    fireAt = first
    sparks(first, ox(6f), oy(6f) + 4f, count = 6, speed = 130f, heading = angle + 180f, fan = 120f,
        anchor = FxAnchor.SOURCE, color = FxPalette.FIRE_CORE)
    particle(FxAnchor.SOURCE, first, .16f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = ox(6f); y = oy(6f) + 4f; size0 = 24f; size1 = 16f; color0 = FxPalette.FIRE; alpha = .7f; fadeOut = .7f
    }
    // The burning fuses and motors wrap the cart in grey smoke as the arrows ripple off.
    repeat(5) { index ->
        val push = rand(20f, 50f)
        particle(FxAnchor.SOURCE, first + index * .025f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(2f + index * 3f) + spread(5f); y = oy(2f + index * 3f) + 4f + spread(3f)
            vx = dir.x * push + spread(14f); vy = dir.y * push + rand(16f, 28f); drag = 2.5f
            size0 = 10f; size1 = rand(26f, 32f); color0 = vary(FxPalette.GUN_SMOKE, .04f); color1 = vary(FxPalette.SMOKE, .04f)
            alpha = .7f; fadeIn = .06f; fadeOut = .65f
        }
    }
    val landings = ArrayList<Float>()
    val scatter = if (outcome == VisualAttackOutcome.WITHDRAWN) 1.3f else 1f
    repeat(count) { index ->
        val launch = first + index * .011f + spread(.004f)
        val side = (index % 4 - 1.5f) * 4f
        // Each arrow climbs a little differently, so the volley spreads into a swarm instead of flying as one.
        val lift = arc * rand(.7f, 1.35f)
        val flight = travel + spread(.025f)
        val toX = spread(20f) * scatter; val toY = spread(12f) * scatter
        trail(launch, flight, 2, .34f, lift, 8f, side, toX, toY, upTo = .7f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            size0 = 7f; size1 = 15f; vy = 6f; color0 = vary(FxPalette.GUN_SMOKE, .04f); color1 = vary(FxPalette.SMOKE, .04f)
            alpha = .55f; fadeIn = .08f; fadeOut = .7f
        }
        // A burning motor: a glow riding just behind a white-hot streak, which lengthens with speed.
        projectile(launch + .008f, flight, lift, 8f, side, toX, toY) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            size0 = 13f; size1 = 10f; color0 = FxPalette.FIRE; alpha = .75f
        }
        projectile(launch, flight, lift, 8f, side, toX, toY) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            size0 = 3.5f; size1 = 3.5f; aspect = .6f; stretch = .01f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EXHAUST
        }
        landings += launch + flight
        arrowFire(launch + flight, toX, toY)
    }
    landings.sort()
    impact(landings.first())
    impact(landings[landings.size / 2])
}

// Building blocks for the scripts above.

/** What a blow throws up: masonry from a city, earth anywhere else. */
private val FxScene.rubble: Color get() = if (targetCity) FxPalette.STONE else FxPalette.EARTH

/** Whether the blow lands on a ship, rather than on open water or a ship that slipped away. */
private val FxScene.hullHit: Boolean get() = defenderBody == BodyKind.SHIP && outcome != VisualAttackOutcome.WITHDRAWN

/** The kit's blast, throwing up spray at sea and rubble ashore. */
private fun FxScene.blast(at: Float, x: Float, y: Float, scale: Float, smoke: Boolean = false) =
    explosion(at, x, y, scale, water = targetWater, smoke = smoke, debris = rubble)


/** Pale stone dust, which shows against grass where plain masonry grey would not. */
private val MASONRY_DUST = Color(FxPalette.STONE).lerp(FxPalette.GUN_SMOKE, .45f)

/** Dry earth thrown up in a cloud: paler than settled dust, so a soft puff of it reads against olive grass. */
private val THROWN_DUST = Color(FxPalette.DUST).lerp(FxPalette.GUN_SMOKE, .3f)

/** Puffs enough for a trail to read as one line: about one every [spacing] pixels of the attack's distance. */
private fun FxScene.trailPuffs(spacing: Float, fewest: Int, most: Int) = (length / spacing).toInt().coerceIn(fewest, most)

/** How far toward the defender, and how far to its left, a tile offset lies. */
private fun FxScene.alongOf(x: Float, y: Float) = x * dir.x + y * dir.y
private fun FxScene.sideOf(x: Float, y: Float) = x * perp.x + y * perp.y

/**
 * Black-powder smoke: [jets] puffs blown hard out of the muzzle toward the defender, braking in the air into a white
 * bank that hangs over the gun and drifts up as it thins.
 */
private fun FxScene.powderSmoke(at: Float, along: Float, side: Float, scale: Float, jets: Int) {
    val mouthX = ox(along, side); val mouthY = oy(along, side) + 2f
    repeat(jets) { index ->
        val push = rand(120f, 190f) * scale
        val sideways = spread(30f)
        particle(FxAnchor.SOURCE, at + index * .008f, rand(.7f, .85f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = mouthX + spread(2f); y = mouthY + spread(2f)
            vx = dir.x * push + perp.x * sideways; vy = dir.y * push + perp.y * sideways + rand(10f, 20f); drag = 6f
            size0 = 17f * scale; size1 = rand(36f, 44f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .92f; fadeIn = .03f; fadeOut = .55f
        }
    }
    repeat(3) { index ->
        particle(FxAnchor.SOURCE, at + .02f + index * .025f, rand(.75f, .9f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = mouthX + ox(8f + index * 9f) + spread(4f); y = mouthY + oy(8f + index * 9f) + spread(3f)
            vx = dir.x * rand(15f, 30f) + spread(6f); vy = dir.y * rand(15f, 30f) + rand(18f, 28f); drag = 1.4f
            size0 = 18f * scale; size1 = rand(42f, 52f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .75f; fadeIn = .1f; fadeOut = .6f
        }
    }
}

/**
 * An iron ball in flight, ringed with a pale haze so it shows against dark grass, its shadow running along the ground.
 * With [wisp], a thread of powder smoke is dragged out of the bore behind it for the first part of the way.
 */
private fun FxScene.roundShot(launch: Float, travel: Float, arc: Float, fromAlong: Float, fromSide: Float, toX: Float, toY: Float,
                              wisp: Boolean): Float {
    groundShadow(launch, travel, fromAlong, fromSide, toX, toY, 6f)
    if (wisp) {
        val puffs = trailPuffs(12f, 4, 8)
        trail(launch, travel, puffs, .3f, arc, fromAlong, fromSide, toX, toY, upTo = .5f) { index ->
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            size0 = 7f; size1 = 13f; vy = 8f; color0 = vary(FxPalette.GUN_SMOKE, .03f)
            alpha = .7f * (1f - .6f * index / puffs); fadeIn = .1f; fadeOut = .6f
        }
    }
    projectile(launch, travel, arc, fromAlong, fromSide, toX, toY) {
        shape = FxShape.SOFT; size0 = 14f; size1 = 14f; color0 = FxPalette.GUN_SMOKE; alpha = .5f
    }
    return projectile(launch, travel, arc, fromAlong, fromSide, toX, toY) {
        shape = FxShape.DISC; size0 = 6f; size1 = 6f; color0 = FxPalette.SMOKE_DARK
    }
}

/** A dark blot sliding along the ground under something in flight, which makes its arc read as height. */
private fun FxScene.groundShadow(launch: Float, travel: Float, fromAlong: Float, fromSide: Float, toX: Float, toY: Float, size: Float) {
    projectile(launch, travel, 0f, fromAlong, fromSide, toX, toY - 2f) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND; size0 = size; size1 = size * .8f
        color0 = FxPalette.SCORCH; alpha = .3f; alignToMotion = false; fadeIn = .2f
    }
}

/**
 * Solid shot striking. On open ground it bursts a dark spray of earth up over the defender and skips on past; a city's
 * wall stops it in a burst of masonry, a ship's hull in a spray of splinters, and open water throws up a splash as it skips.
 * A [light] strike, for the second and third guns of a battery, throws less.
 */
private fun FxScene.solidShot(at: Float, x: Float, y: Float, light: Boolean) {
    val hull = hullHit
    if (targetWater && !hull) {
        splash(at, x, y, .75f)
        ricochet(at, x, y)
        return
    }
    val chunks = when { light -> 6; targetCity || hull -> 11; else -> 9 }
    repeat(chunks) { index ->
        val heading = angle + spread(55f)
        val ground = rand(40f, 110f)
        val climb = rand(140f, 250f)
        // Dark pieces are mixed in so a burst of pale masonry, dry earth or planking still shows against grass or sea.
        val color = when {
            index % 3 == 0 -> FxPalette.DEBRIS
            hull -> FxPalette.WOOD
            targetCity -> FxPalette.STONE
            else -> FxPalette.EARTH
        }
        particle(FxAnchor.TARGET, at, rand(.4f, .55f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(3f); this.y = y
            vx = MathUtils.cosDeg(heading) * ground; vy = MathUtils.sinDeg(heading) * ground * .6f + climb; ay = -820f; drag = .5f
            size0 = if (hull) rand(6f, 9f) else rand(3f, 5f); size1 = size0; aspect = if (hull) rand(.3f, .45f) else rand(.55f, .9f)
            rotation = rand(0f, 360f); spin = spread(700f); color0 = vary(color, .05f); fadeOut = .25f
        }
    }
    // Iron striking stone or timber throws sparks.
    if (targetCity || hull) sparks(at, x, y + 4f, count = 5, speed = 140f, heading = angle + 180f, fan = 160f, color = FxPalette.FIRE_CORE)
    if (hull) {
        // Smashed planking bursts out of the side in a grey cloud that shows against sails, and the sea heaves up against the hull.
        repeat(2) {
            particle(FxAnchor.TARGET, at, rand(.4f, .5f)) {
                shape = FxShape.SOFT; layer = FxLayer.SMOKE
                this.x = x + spread(3f); this.y = y + 6f; vx = dir.x * 30f + spread(15f); vy = rand(40f, 70f); drag = 4.5f
                size0 = 20f; size1 = rand(40f, 46f); color0 = vary(FxPalette.SMOKE, .04f); color1 = vary(MASONRY_DUST, .04f)
                alpha = .85f; fadeOut = .65f
            }
        }
        splash(at + .02f, x + dir.x * 6f, y - 6f, .6f)
        return
    }
    val cloud = if (targetCity) MASONRY_DUST else THROWN_DUST
    // The strike itself: a cloud bursting out of the ground at once, big from its first frame. The second and third
    // guns of a battery throw less, so three strikes don't bury the defender.
    val heft = if (light) .75f else 1f
    repeat(if (light) 1 else 2) {
        particle(FxAnchor.TARGET, at, rand(.4f, .5f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f); this.y = y + 4f; vx = dir.x * 40f + spread(20f); vy = dir.y * 40f + rand(30f, 60f); drag = 5f
            size0 = 30f * heft; size1 = rand(54f, 62f) * heft; color0 = vary(cloud, .04f); alpha = .92f * heft; fadeOut = .65f
        }
    }
    // A fountain shooting up past the defender's head.
    repeat(if (light) 2 else 3) { index ->
        particle(FxAnchor.TARGET, at + index * .012f, rand(.45f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(5f); this.y = y + 4f
            vx = dir.x * rand(20f, 50f) + spread(16f); vy = rand(220f, 280f) * heft; drag = 5f
            size0 = 20f * heft; size1 = rand(34f, 40f) * heft
            color0 = vary(cloud, .04f); alpha = .88f; fadeOut = .55f
        }
    }
    // Dust the ball throws on along the ground, fanning out to either side of the defender.
    repeat(if (light) 3 else 4) { index ->
        val heading = angle + (index - 1.5f) * 40f + spread(12f)
        val push = rand(70f, 120f)
        particle(FxAnchor.TARGET, at + .01f + index * .012f, rand(.5f, .65f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(heading) * push; vy = MathUtils.sinDeg(heading) * push * .6f + rand(15f, 35f); drag = 3.5f
            size0 = 16f * heft; size1 = rand(38f, 46f) * heft; color0 = vary(cloud, .05f); alpha = .75f * heft; fadeIn = .05f; fadeOut = .65f
        }
    }
    groundShock(at, x, y, .6f)
    if (!targetCity) ricochet(at, x, y)
}

/** The ball bouncing on past where it struck, raising a puff (or a splash) where it touches down again. */
private fun FxScene.ricochet(at: Float, x: Float, y: Float) {
    val hop = .22f
    val speed = 150f
    val climb = 95f
    val reach = speed * hop
    particle(FxAnchor.TARGET, at, hop) {
        shape = FxShape.DISC; layer = FxLayer.BODY
        this.x = x; this.y = y + 1f; vx = dir.x * speed; vy = dir.y * speed + climb; ay = -2 * climb / hop
        size0 = 5f; size1 = 5f; color0 = FxPalette.SMOKE_DARK; fadeOut = 0f
    }
    val downX = x + dir.x * reach; val downY = y + dir.y * reach
    if (targetWater) {
        splash(at + hop, downX, downY, .4f)
        return
    }
    dust(at + hop, downX, downY, .5f, puffs = 2)
    // It rolls to a stop and is lost in the grass, rather than lying there as a stray dot.
    particle(FxAnchor.TARGET, at + hop, .3f) {
        shape = FxShape.DISC; layer = FxLayer.BODY
        this.x = downX; this.y = downY + 1f; vx = dir.x * 90f; vy = dir.y * 90f; drag = 5f
        size0 = 5f; size1 = 5f; color0 = FxPalette.SMOKE_DARK; fadeOut = .5f
    }
}

/**
 * A modern gun firing: flash, a pressure ring, blast smoke rolling out of the muzzle and, when [dusty], the shock
 * slamming a ring of dust out along the ground all round the gun.
 */
private fun FxScene.gunBlast(at: Float, along: Float, side: Float, scale: Float, dusty: Boolean) {
    muzzleFlash(at, along, side, scale = 1.45f * scale)
    particle(FxAnchor.SOURCE, at, .16f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = ox(along, side); y = oy(along, side); size0 = 10f; size1 = 54f * scale; color0 = FxPalette.FLASH; alpha = .45f; fadeOut = 1f
    }
    repeat(4) { index ->
        val push = rand(80f, 130f) * scale
        particle(FxAnchor.SOURCE, at + .01f + index * .012f, rand(.5f, .65f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(along + index * 3f, side) + spread(2f); y = oy(along + index * 3f, side) + 2f + spread(2f)
            vx = dir.x * push + spread(18f); vy = dir.y * push + rand(14f, 26f); drag = 4.5f
            size0 = 11f * scale; size1 = rand(26f, 32f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .04f); color1 = vary(FxPalette.SMOKE, .04f); alpha = .7f; fadeIn = .05f; fadeOut = .65f
        }
    }
    if (!dusty || sourceWater) return
    particle(FxAnchor.SOURCE, at, .3f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        x = ox(2f, side); y = oy(2f, side) - 8f; size0 = 14f; size1 = 74f * scale; color0 = FxPalette.DUST; alpha = .55f; fadeOut = .9f
    }
    repeat(8) { index ->
        val heading = index * 45f + spread(15f)
        val speed = rand(90f, 130f) * scale
        particle(FxAnchor.SOURCE, at, rand(.45f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(2f, side); y = oy(2f, side) - 8f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed * .45f + 8f; drag = 4.5f
            size0 = 14f * scale; size1 = rand(30f, 36f) * scale; color0 = vary(THROWN_DUST, .05f); alpha = .6f; fadeIn = .05f; fadeOut = .7f
        }
    }
}

/** A shell in flight: a hot glowing round with a short glowing tail, a thin smoke thread and its shadow running along the ground. */
private fun FxScene.shell(launch: Float, travel: Float, arc: Float, fromAlong: Float, fromSide: Float, toX: Float, toY: Float): Float {
    groundShadow(launch, travel, fromAlong, fromSide, toX, toY, 8f)
    trail(launch, travel, trailPuffs(10f, 8, 22), .34f, arc, fromAlong, fromSide, toX, toY, upTo = .95f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        size0 = 6f; size1 = 12f; vy = 6f; color0 = vary(FxPalette.GUN_SMOKE, .04f); color1 = vary(FxPalette.SMOKE, .03f)
        alpha = .55f; fadeIn = .05f; fadeOut = .6f
    }
    // The tail lags the round a moment along the same path, so it trails behind it whichever way the shell is moving.
    projectile(launch + .012f, travel, arc, fromAlong, fromSide, toX, toY) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 3f; size1 = 3f; aspect = .8f; stretch = .014f; color0 = FxPalette.FIRE; alpha = .55f
    }
    projectile(launch, travel, arc, fromAlong, fromSide, toX, toY) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 17f; size1 = 17f; color0 = FxPalette.FIRE; alpha = .7f
    }
    return projectile(launch, travel, arc, fromAlong, fromSide, toX, toY) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 4.5f; size1 = 4.5f; aspect = .75f; stretch = .006f; color0 = FxPalette.FIRE_CORE
    }
}

/**
 * A heavy shell going off: the shared blast, a column of earth and the shock running out along the ground, and smoke
 * that climbs off the target instead of sitting on it.
 */
private fun FxScene.shellBlast(at: Float, x: Float, y: Float, scale: Float) {
    blast(at, x, y, scale)
    // A ship that is hit burns; a miss at sea only leaves a little smoke hanging over the splash.
    val hull = hullHit
    risingSmoke(at + .05f, x, y, scale, if (targetWater && !hull) 2 else 4)
    if (targetWater) {
        if (hull && scale >= 1f) fire(at + .06f, x, y + 2f, life = .35f, scale = .7f)
        return
    }
    earthColumn(at, x, y, scale / 1.5f)
    groundShock(at, x, y, scale / 1.5f)
}

/** Dark smoke from a blast, rising off the ground it came from and paling as it climbs. */
private fun FxScene.risingSmoke(at: Float, x: Float, y: Float, scale: Float, puffs: Int) {
    repeat(puffs) { index ->
        particle(FxAnchor.TARGET, at + index * .03f, rand(.55f, .75f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(8f * scale); this.y = y + 6f + spread(4f)
            vx = spread(12f) + 6f; vy = rand(50f, 75f) * scale; drag = 1.2f
            size0 = rand(14f, 18f) * scale; size1 = rand(34f, 42f) * scale
            color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE); alpha = .7f; fadeIn = .12f; fadeOut = .6f
        }
    }
}

/** A warhead going off: a hard white flash over a heavy blast. */
private fun FxScene.warhead(at: Float, x: Float, y: Float, scale: Float) {
    particle(FxAnchor.TARGET, at, .12f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 4f; size0 = 24f * scale; size1 = 60f * scale; color0 = FxPalette.FLASH; alpha = .35f; fadeOut = 1f
    }
    shellBlast(at, x, y, scale)
}

/** The column a heavy blast digs out of the ground: a plume of earth shooting up over the crater and clods falling back. */
private fun FxScene.earthColumn(at: Float, x: Float, y: Float, scale: Float) {
    val plume = if (targetCity) MASONRY_DUST else FxPalette.DEBRIS
    val clods = if (targetCity) FxPalette.STONE else FxPalette.DEBRIS
    // Thrown faster than the fireball rises, so the column stands clear above it.
    repeat(5) { index ->
        particle(FxAnchor.TARGET, at + .03f + index * .02f, rand(.5f, .65f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f * scale); this.y = y + 4f
            vx = spread(10f) * scale; vy = rand(190f, 270f) * scale; drag = 4.2f
            size0 = 13f * scale; size1 = rand(28f, 34f) * scale
            color0 = vary(plume, .04f); color1 = vary(FxPalette.SMOKE, .04f); alpha = .9f; fadeIn = .04f; fadeOut = .55f
        }
    }
    repeat((6 * scale).toInt().coerceAtLeast(4)) {
        val heading = rand(60f, 120f)
        val speed = rand(170f, 260f) * scale
        particle(FxAnchor.TARGET, at, rand(.45f, .6f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(4f); this.y = y + 2f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; ay = -900f; drag = .5f
            size0 = rand(3f, 5f) * scale.coerceAtMost(1.3f); size1 = size0; aspect = rand(.6f, .9f)
            rotation = rand(0f, 360f); spin = spread(800f); color0 = vary(clods, .04f); fadeOut = .2f
        }
    }
}

/** A blast's shock running out along the ground as a ring of dust. */
private fun FxScene.groundShock(at: Float, x: Float, y: Float, scale: Float) {
    particle(FxAnchor.TARGET, at, .38f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y - 3f; size0 = 16f * scale; size1 = 80f * scale
        color0 = if (targetCity) MASONRY_DUST else FxPalette.DUST; alpha = .55f; fadeOut = .9f
    }
}

/** A rocket leaving its rail: a flash at the launcher and a gust of smoke blown out behind it. */
private fun FxScene.backblast(at: Float, side: Float, scale: Float = 1f) {
    particle(FxAnchor.SOURCE, at, .07f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = ox(6f, side); y = oy(6f, side) + 4f; size0 = 18f * scale; size1 = 8f * scale; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
    }
    repeat(2) {
        val push = rand(60f, 100f) * scale
        particle(FxAnchor.SOURCE, at + it * .01f, rand(.5f, .65f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = ox(-2f, side); y = oy(-2f, side) + 3f
            vx = -dir.x * push + spread(14f); vy = -dir.y * push + rand(8f, 20f); drag = 4f
            size0 = 7f * scale; size1 = rand(20f, 26f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .04f); alpha = .55f; fadeIn = .06f; fadeOut = .7f
        }
    }
}

/**
 * A rocket in flight: a white-hot motor in an orange plume, speeding up as it goes and drawing a white trail that lingers.
 * [phase], from 0 to 1, shifts where along the path its trail puffs fall, so a salvo's trails fill each other's gaps.
 */
private fun FxScene.rocket(launch: Float, travel: Float, arc: Float, fromAlong: Float, fromSide: Float, toX: Float, toY: Float,
                           phase: Float = .5f): Float {
    val puffs = trailPuffs(16f, 5, 10)
    repeat(puffs) { index ->
        val along = (index + phase) / puffs * .94f
        // The rocket speeds up (an IN ease), so it passes this point at the square root of the way through its flight.
        flying(launch + travel * sqrt(along), .44f,
            FxFlight(ox(fromAlong, fromSide), oy(fromAlong, fromSide), toX, toY, arc, FxEase.IN, along)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            size0 = 12f; size1 = rand(21f, 25f); vx = spread(4f); vy = rand(4f, 10f); drag = 1f
            color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .62f; fadeIn = .03f; fadeOut = .6f
        }
    }
    projectile(launch, travel, arc, fromAlong, fromSide, toX, toY, FxEase.IN) {
        shape = FxShape.RECT; size0 = 7f; size1 = 7f; aspect = .3f; color0 = FxPalette.SMOKE_DARK
    }
    projectile(launch + .012f, travel, arc, fromAlong, fromSide, toX, toY, FxEase.IN) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 15f; size1 = 13f; color0 = FxPalette.EXHAUST; alpha = .6f
    }
    projectile(launch + .008f, travel, arc, fromAlong, fromSide, toX, toY, FxEase.IN) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 5f; size1 = 5f; aspect = .5f; stretch = .008f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EXHAUST
    }
    return launch + travel
}

/** A missile in flight: a white body with a hot motor and an orange plume, drawing a contrail behind it. */
private fun FxScene.guided(launch: Float, travel: Float, arc: Float, fromAlong: Float, fromSide: Float, toX: Float, toY: Float,
                           ease: FxEase, puffs: Int, scale: Float = 1f): Float {
    trail(launch, travel, puffs, .55f, arc, fromAlong, fromSide, toX, toY, ease, upTo = .95f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        size0 = 10f * scale; size1 = rand(18f, 22f) * scale; vx = spread(5f); vy = rand(5f, 10f)
        color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .7f; fadeIn = .03f; fadeOut = .5f
    }
    projectile(launch + .014f, travel, arc, fromAlong, fromSide, toX, toY, ease) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 16f * scale; size1 = 14f * scale; color0 = FxPalette.EXHAUST; alpha = .7f
    }
    projectile(launch, travel, arc, fromAlong, fromSide, toX, toY, ease) {
        shape = FxShape.RECT; size0 = 10f * scale; size1 = size0; aspect = .24f; color0 = FxPalette.GUN_SMOKE
    }
    projectile(launch + .006f, travel, arc, fromAlong, fromSide, toX, toY, ease) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 7f * scale; size1 = size0; color0 = FxPalette.FIRE_CORE
    }
    return launch + travel
}

/** A proximity warhead bursting in the air: a flash, a puff of black smoke left hanging, fragments raining onto the ground [height] below. */
private fun FxScene.airburst(at: Float, x: Float, y: Float, height: Float) {
    particle(FxAnchor.TARGET, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 16f; size1 = 46f; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(FxAnchor.TARGET, at, .16f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 8f; size1 = 48f; color0 = FxPalette.FLASH; alpha = .5f; fadeOut = 1f
    }
    repeat(2) {
        particle(FxAnchor.TARGET, at + rand(0f, .02f), rand(.18f, .24f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(4f); this.y = y + spread(3f); vx = spread(20f); vy = rand(5f, 20f); drag = 4f
            size0 = rand(10f, 13f); size1 = rand(20f, 24f); color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .9f; fadeOut = .6f
        }
    }
    // The black puff a proximity warhead leaves hanging where it burst.
    repeat(2) {
        particle(FxAnchor.TARGET, at + .02f + it * .025f, rand(.45f, .55f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(4f); this.y = y + spread(3f); vx = -dir.x * 25f + spread(10f); vy = rand(4f, 10f); drag = 2f
            size0 = 14f; size1 = rand(26f, 30f); color0 = vary(FxPalette.SMOKE_DARK, .04f); color1 = vary(FxPalette.SMOKE, .04f)
            alpha = .8f; fadeIn = .08f; fadeOut = .6f
        }
    }
    repeat(12) { index ->
        // Most fragments drive down onto the defender; a few fly out to the sides.
        val heading = if (index < 9) -90f + spread(65f) else rand(-20f, 200f)
        val speed = rand(180f, 300f)
        particle(FxAnchor.TARGET, at, rand(.16f, .26f)) {
            shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; ay = -400f; drag = 2f
            size0 = 2f; size1 = 1.4f; aspect = .5f; stretch = .03f; alignToMotion = true
            color0 = FxPalette.STEEL; color1 = FxPalette.FIRE; fadeOut = .5f
        }
    }
    if (targetWater) splash(at + .05f, x, y - height, .45f)
    else dust(at + .05f, x, y - height, .7f, color = if (targetCity) MASONRY_DUST else FxPalette.DUST, puffs = 4)
}

/**
 * A fire arrow coming down: a hiss on water; on land or a ship's deck its charge pops in a spit of fire and sparks, and
 * here and there it sets a small fire that burns on under a thread of smoke.
 */
private fun FxScene.arrowFire(at: Float, x: Float, y: Float) {
    val onDeck = hullHit && abs(x) < 13f && abs(y) < 8f
    if (targetWater && !onDeck) {
        particle(FxAnchor.TARGET, at, .35f) {
            shape = FxShape.RING; layer = FxLayer.GROUND
            this.x = x; this.y = y - 2f; size0 = 4f; size1 = 18f; color0 = FxPalette.WATER; alpha = .6f; fadeOut = .8f
        }
        particle(FxAnchor.TARGET, at, .3f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x; this.y = y; vy = 25f; drag = 3f; size0 = 4f; size1 = 12f; color0 = FxPalette.WATER; alpha = .5f; fadeOut = .8f
        }
        return
    }
    // The arrow's powder charge bursts: a spit of fire and sparks and a puff of grey smoke.
    particle(FxAnchor.TARGET, at, .12f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 2f; size0 = 22f; size1 = 9f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.FIRE; fadeOut = .7f
    }
    sparks(at, x, y, count = 3, speed = 110f, color = FxPalette.FIRE_CORE)
    particle(FxAnchor.TARGET, at + .02f, rand(.35f, .45f)) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x + spread(2f); this.y = y + 3f; vx = spread(10f); vy = rand(25f, 40f); drag = 2.5f
        size0 = 8f; size1 = rand(18f, 22f); color0 = vary(FxPalette.GUN_SMOKE, .04f); color1 = vary(FxPalette.SMOKE, .04f)
        alpha = .7f; fadeIn = .05f; fadeOut = .65f
    }
    if (chance(.35f)) particle(FxAnchor.TARGET, at, end - at) {
        shape = FxShape.RECT; layer = FxLayer.BODY
        this.x = x; this.y = y - 4f; size0 = 7f; size1 = 7f; aspect = .16f
        rotation = 90f + angle * .2f + spread(20f); color0 = FxPalette.SHAFT; fadeOut = .3f
    }
    if (!chance(if (onDeck) .65f else .45f)) {
        if (!onDeck) dust(at, x, y - 2f, .4f, puffs = 1)
        return
    }
    // A little fire catches where it fell and burns on with a thread of smoke.
    particle(FxAnchor.TARGET, at, .5f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y - 2f; size0 = 18f; size1 = 12f; color0 = FxPalette.FIRE; color1 = FxPalette.EMBER; alpha = .8f; fadeIn = .1f; fadeOut = .5f
    }
    repeat(2) { flame ->
        particle(FxAnchor.TARGET, at + .03f + flame * .12f, rand(.22f, .28f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(2.5f); this.y = y - 2f; vy = rand(35f, 50f); drag = 1f
            size0 = rand(13f, 16f); size1 = 5f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; fadeIn = .12f; fadeOut = .5f
        }
    }
    particle(FxAnchor.TARGET, at + .08f, .45f) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x; this.y = y + 4f; vx = spread(6f) + 4f; vy = rand(26f, 36f); drag = 1f
        size0 = 7f; size1 = 18f; color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE); alpha = .6f; fadeIn = .15f; fadeOut = .6f
    }
}
