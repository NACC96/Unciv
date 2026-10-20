package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.unciv.logic.presentation.VisualAttackOutcome
import kotlin.math.exp

/*
 * Ships: rams and boarding, broadsides of sail-era guns, steel-era naval guns and submarines' torpedoes.
 *
 * Ship art is drawn level whatever the attack's direction: the deck runs through the tile's center and the hull
 * spans about 20 px either side of it, with the waterline [WATERLINE] below. Guns sit along that hull and fire
 * toward the defender, and shots strike its flank facing the attacker.
 */

private const val WATERLINE = -7f

private object NavalColors {
    /** Pale timber from inside a split hull, mixed with weathered [FxPalette.WOOD] so splinters read on dark water. */
    val RAW_WOOD = Color(.8f, .64f, .42f, 1f)
    val SHOT = Color(.1f, .09f, .08f, 1f)
    val TIMBER_DUST = Color(.62f, .54f, .42f, 1f)
    /** Cordite smoke: thinner and greyer than black powder's white. */
    val CORDITE = Color(.62f, .6f, .56f, 1f)
}

private val FxScene.slippedAway get() = outcome == VisualAttackOutcome.WITHDRAWN

/** What a hit ashore throws up: masonry from a city, earth elsewhere. */
private fun FxScene.soil() = if (targetCity) FxPalette.STONE else FxPalette.EARTH

/** The attacker's end of the screen, so guns ripple from the bow nearest the defender. */
private val FxScene.facing get() = if (dir.x >= 0f) 1f else -1f

/** The along and side of a screen offset, so the kit's muzzle and flight helpers can sit on a level hull. */
private fun FxScene.alongOf(x: Float, y: Float) = x * dir.x + y * dir.y
private fun FxScene.sideOf(x: Float, y: Float) = x * perp.x + y * perp.y

/**
 * How far a melee ship has surged toward the defender at [time]. It mirrors the timeline's lunge into the first
 * blow at [blow], so guns fired and foam thrown on the way stay with the moving hull.
 */
private fun FxScene.surge(time: Float, blow: Float): Float {
    if (distanceInTiles > 1) return 0f
    val reach = (length * .3f).coerceAtMost(20f)
    val since = time - blow
    fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    return reach * when {
        since < -.22f -> 0f
        since < 0f -> smooth(1f + since / .22f)
        since < .25f -> 1f - smooth(since / .25f)
        else -> 0f
    }
}

/** Where a shot strikes the defender: its flank facing the attacker on a ship, anywhere on a city's walls. */
private fun FxScene.strikePoint(width: Float = 10f): Pair<Float, Float> = when {
    targetCity -> spread(12f) to rand(-6f, 6f)
    targetWater -> -dir.x * 5f + spread(width) to -2f - dir.y * 2f + rand(-3f, 2f)
    else -> spread(8f) to rand(-5f, 3f)
}

/** Open water (or ground) beside the defender where a shot falls short or carries over, about [reach] px off. */
private fun FxScene.nearMiss(reach: Float): Pair<Float, Float> {
    val along = if (chance(.5f)) rand(.6f, 1f) * reach else -rand(.65f, 1f) * reach
    val side = spread(reach * .8f)
    return ox(along, side) to oy(along, side) + WATERLINE
}

/* ---- Rams ---- */

/** A galley rams: it surges in on a bow wave, crashes into the hull in splinters and spray, and its crew boards. */
fun FxScene.ramShip() {
    val weight = 1f + style.era * .15f
    val blow = .36f
    bowWave(blow, weight)
    // The ram strikes at the waterline, on the flank facing the attacker.
    val cx = -dir.x * 12f; val cy = WATERLINE + 3f - dir.y * 3f
    if (targetWater) ramSpray(blow, cx, cy, weight)
    else dust(blow, cx, cy, weight, color = if (targetCity) FxPalette.STONE else FxPalette.DUST, puffs = 5)
    impact(blow, .8f + style.era * .2f)
    if (slippedAway) return
    particle(FxAnchor.TARGET, blow, .08f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = cx; y = cy + 5f; size0 = 14f * weight; size1 = 30f * weight; color0 = FxPalette.FLASH; alpha = .55f; fadeOut = 1f
    }
    // The ram stoves in the planking (or chips a city's quay); a bloodied attacker's bow gives way too.
    timberShards(blow, cx, cy + 6f, (13 * weight).toInt(), angle, 140f, 210f, if (targetCity) FxPalette.STONE else null)
    if ((damageToAttacker ?: 0) > 0) timberShards(blow + .02f, cx, cy + 5f, 5, angle + 180f, 100f, 150f)
    timberDust(blow, cx, cy + 6f, 3, if (targetCity) FxPalette.STONE else NavalColors.TIMBER_DUST)
    boardingFight(blow + .15f)
}

/** White water piling up under a galley's bow and peeling off both sides as it surges into [blow]. */
private fun FxScene.bowWave(blow: Float, scale: Float) {
    if (!sourceWater) return
    var at = blow - .21f
    while (at < blow + .01f) {
        val forward = surge(at, blow)
        val bx = dir.x * (12f + forward); val by = WATERLINE + 2f + dir.y * (3f + forward)
        for (arm in listOf(-1f, 1f)) {
            particle(FxAnchor.SOURCE, at, .42f) {
                shape = FxShape.SOFT; layer = FxLayer.GROUND
                x = bx; y = by
                vx = perp.x * arm * 46f - dir.x * 8f; vy = perp.y * arm * 30f - dir.y * 8f; drag = 3.5f
                size0 = 8f * scale; size1 = 20f * scale
                color0 = FxPalette.WATER; alpha = .8f; fadeIn = .1f; fadeOut = .7f
            }
            particle(FxAnchor.SOURCE, at, .3f) {
                shape = FxShape.DISC; layer = FxLayer.BODY
                x = bx; y = by + 1f
                vx = perp.x * arm * 60f + spread(10f); vy = perp.y * arm * 40f + 30f; drag = 3f; ay = -300f
                size0 = 3.2f * scale; size1 = 2f; color0 = FxPalette.WATER; alpha = .95f; fadeOut = .5f
            }
        }
        at += .035f
    }
    // Spray thrown off the bow in the last strokes before the ram strikes.
    repeat(6) {
        val at2 = blow - rand(.02f, .1f)
        val forward = surge(at2, blow)
        val arm = if (it % 2 == 0) 1f else -1f
        particle(FxAnchor.SOURCE, at2, rand(.22f, .3f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            x = dir.x * (12f + forward); y = WATERLINE + 3f + dir.y * (3f + forward)
            vx = perp.x * arm * rand(40f, 80f) + dir.x * 40f; vy = rand(70f, 120f); ay = -700f
            size0 = rand(2.2f, 3.2f); size1 = 1.6f; color0 = FxPalette.WATER; fadeOut = .3f
        }
    }
}

/** White water thrown up where a ram meets a hull: a sheet of spray, droplets raining back and a ring of foam. */
private fun FxScene.ramSpray(at: Float, x: Float, y: Float, scale: Float) {
    // Streaks fanned up and out make a sheet of water; heavier gobs fall back out of it.
    repeat((20 * scale).toInt()) { index ->
        val heading = 90f + spread(60f)
        val speed = rand(150f, 290f) * scale
        particle(FxAnchor.TARGET, at + rand(0f, .02f), rand(.38f, .5f)) {
            layer = FxLayer.BODY
            this.x = x + spread(6f); this.y = y + 2f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed; drag = 2.5f; ay = -520f
            if (index % 3 == 0) { shape = FxShape.DISC; size0 = rand(4f, 6f) * scale; size1 = 2f }
            else { shape = FxShape.RECT; size0 = rand(2.5f, 3.5f); size1 = 2f; aspect = 1f; stretch = .03f; alignToMotion = true }
            color0 = FxPalette.WATER; alpha = .95f; fadeOut = .35f
        }
    }
    repeat(4) {
        particle(FxAnchor.TARGET, at + rand(0f, .04f), rand(.42f, .52f)) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(7f); this.y = y + rand(2f, 8f)
            vx = spread(40f); vy = rand(60f, 130f) * scale; drag = 4.5f; ay = -120f
            size0 = rand(12f, 16f) * scale; size1 = rand(30f, 38f) * scale
            color0 = FxPalette.WATER; alpha = .95f; fadeIn = .04f; fadeOut = .6f
        }
    }
    foam(at + .02f, x, y - 2f, 12f * scale, 60f * scale)
}

/** The crew goes over: blades flash and clash on the defender's deck, with a pistol or two going off. */
private fun FxScene.boardingFight(at: Float, pistols: Boolean = false) {
    val deckX = -dir.x * 4f; val deckY = 3f
    slash(at, deckX - 3f, deckY + 3f, radius = 14f)
    clash(at + .03f, deckX - 8f, deckY)
    sparks(at + .03f, deckX - 8f, deckY, count = 5, speed = 140f, heading = angle + 180f, fan = 150f)
    slash(at + .09f, deckX + 6f, deckY + 1f, radius = 13f, from = 30f, sweep = 130f)
    clash(at + .12f, deckX + 10f, deckY + 4f)
    sparks(at + .12f, deckX + 10f, deckY + 4f, count = 4, speed = 120f)
    if (pistols) repeat(2) { index ->
        val shotAt = at + .04f + index * .08f
        val px = deckX + if (index == 0) -11f else 13f; val py = deckY + 5f
        particle(FxAnchor.TARGET, shotAt, .06f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            x = px; y = py; size0 = 13f; size1 = 6f; color0 = FxPalette.FIRE_CORE; fadeOut = .8f
        }
        particle(FxAnchor.TARGET, shotAt + .01f, .4f) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            x = px; y = py; vx = spread(10f); vy = 18f; drag = 2f
            size0 = 6f; size1 = 16f; color0 = FxPalette.GUN_SMOKE; alpha = .7f; fadeIn = .1f; fadeOut = .7f
        }
    }
    impact(at)
}

/** A brief glint where two blades meet. */
private fun FxScene.clash(at: Float, x: Float, y: Float) = particle(FxAnchor.TARGET, at, .06f) {
    shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
    this.x = x; this.y = y; size0 = 12f; size1 = 4f; color0 = FxPalette.STEEL; fadeOut = .8f
}

/* ---- Broadsides ---- */

/**
 * A sail-era broadside: the guns ripple down the ship's side in flashes and a rolling bank of white smoke, and round
 * shot raises white plumes around the defender and smashes splinters out of its hull. Light ships close and board.
 */
fun FxScene.broadside() {
    if (style.variant == "light") return lightBroadside()
    val heavy = style.heavy
    // A ship of the line fires two gun decks; later ships carry a few more guns.
    val decks = if (heavy) 2 else 1
    val perDeck = 5 + (style.era - 3).coerceIn(0, 2)
    val fire = .05f
    val ripple = if (heavy) .022f else .032f
    val travel = .15f + distanceInTiles * .045f
    val arc = 4f + distanceInTiles * 3f
    fireAt = fire
    var hits = 0
    for (gun in 0 until perDeck) for (deck in 0 until decks) {
        val index = gun * decks + deck
        val at = fire + index * ripple + spread(.004f)
        // Gun ports along the hull from the end nearest the defender, the lower deck a little below the upper.
        val gx = facing * (13f - 27f * gun / (perDeck - 1)) + deck * 2f + dir.x * 5f
        val gy = -2f - deck * 3f + dir.y * 4f
        powderBlast(at, gx, gy, if (heavy) 1.05f else .95f, puffs = 3 - deck)
        val hit = !slippedAway && (index == 0 || chance(if (heavy) .45f else .4f))
        val (toX, toY) = if (hit) strikePoint() else nearMiss(30f)
        val landing = cannonball(at, travel, arc, gx, gy, toX, toY)
        if (hit) roundShotStrike(landing, toX, toY, if (heavy) 1.1f else 1f) else shotMiss(landing, toX, toY, if (heavy) 1.1f else 1f)
        if (index == 0) impact(landing, if (heavy) 1.5f else 1f)
        else if (hit && hits < 3) impact(landing, .5f)
        if (hit) hits++
    }
}

/** A light ship's attack: its bow guns fired as it closes, shot striking home, then grapples and a boarding fight. */
private fun FxScene.lightBroadside() {
    val blow = .34f
    val guns = if (style.era >= 3) 3 else 2
    val travel = .13f + distanceInTiles * .02f
    fireAt = blow - travel
    repeat(guns) { index ->
        val landing = blow + index * .05f
        val at = landing - travel
        val forward = surge(at, blow)
        val gx = facing * (10f - index * 9f) + dir.x * (6f + forward); val gy = -2f + dir.y * (4f + forward)
        powderBlast(at, gx, gy, .85f)
        val (toX, toY) = if (slippedAway) nearMiss(22f) else strikePoint()
        cannonball(at, travel, 2f, gx, gy, toX, toY)
        if (slippedAway) shotMiss(landing, toX, toY, .9f) else roundShotStrike(landing, toX, toY, .9f)
    }
    impact(blow, .6f)
    if (!slippedAway) boardingFight(blow + .15f, pistols = true)
}

/** A muzzle-loader firing: flash and flame from the port, then a gout of white powder smoke rolling out toward the enemy. */
private fun FxScene.powderBlast(at: Float, x: Float, y: Float, scale: Float, puffs: Int = 3) {
    muzzleFlash(at, alongOf(x, y), sideOf(x, y), scale)
    repeat(puffs) { index ->
        val push = rand(85f, 130f) * scale * (1f - index * .3f)
        particle(FxAnchor.SOURCE, at + .01f + index * .02f, rand(.6f, .75f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + dir.x * 3f + spread(3f); this.y = y + dir.y * 3f + spread(3f)
            vx = dir.x * push + spread(12f); vy = dir.y * push + rand(10f, 22f); drag = 4f
            size0 = 12f * scale; size1 = rand(40f, 48f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .92f; fadeIn = .04f; fadeOut = .6f
        }
    }
}

/** A round shot in flight: a dark ball with a short motion smear. Returns the landing time. */
private fun FxScene.cannonball(at: Float, travel: Float, arc: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Float =
    projectile(at, travel, arc, alongOf(fromX, fromY), sideOf(fromX, fromY), toX, toY) {
        shape = FxShape.RECT; size0 = 3.6f; size1 = 3.6f; aspect = 1f; stretch = .008f; color0 = NavalColors.SHOT
    }

/** Round shot striking home: a burst of light, splinters blown out of the hull and timber dust; masonry on a city. */
private fun FxScene.roundShotStrike(at: Float, x: Float, y: Float, scale: Float) {
    particle(FxAnchor.TARGET, at, .09f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 2f; size0 = 16f * scale; size1 = 28f * scale; color0 = FxPalette.FIRE_CORE; alpha = .9f; fadeOut = 1f
    }
    when {
        targetCity -> {
            timberShards(at, x, y + 2f, (7 * scale).toInt(), angle, 140f, 160f, FxPalette.STONE)
            dust(at, x, y, .8f * scale, color = FxPalette.STONE, puffs = 3)
        }
        targetWater -> {
            timberShards(at, x, y + 2f, (8 * scale).toInt(), angle, 120f, 170f)
            timberDust(at, x, y + 2f, 2)
        }
        else -> {
            timberShards(at, x, y + 2f, (5 * scale).toInt(), 90f, 140f, 140f, soil())
            dust(at, x, y, .8f * scale, color = FxPalette.DUST, puffs = 3)
        }
    }
}

/** Round shot that misses: a white plume where it plunges into the sea, or a spurt of dirt ashore. */
private fun FxScene.shotMiss(at: Float, x: Float, y: Float, scale: Float) {
    if (targetWater) seaColumn(at, x, y, 36f * scale, 10f * scale)
    else {
        dust(at, x, y, .75f * scale, color = if (targetCity) FxPalette.STONE else FxPalette.DUST, puffs = 3)
        timberShards(at, x, y, 3, 90f, 120f, 110f * scale, soil())
    }
}

/* ---- Steel ships' guns ---- */

/**
 * Steel ships' turret guns: hard flashes and powder smoke, glowing shells, and hits that burst into fire and black
 * smoke among the white columns of near misses. The Battleship fires two full salvos that straddle its target.
 */
fun FxScene.navalGuns() {
    if (style.heavy) return battleshipSalvos()
    // An ironclad's two big guns still burn black powder; later ships fire quick pairs of cordite rounds.
    val modern = style.era >= 5
    // Later destroyers carry heavier, faster-firing guns.
    val power = 1f + (style.era - 5).coerceIn(0, 2) * .12f
    val shots = if (modern) 4 else 2
    val melee = distanceInTiles <= 1
    val travel = if (melee) .12f else .1f + distanceInTiles * .045f
    val first = if (melee) .34f else .06f + travel
    fireAt = first - travel
    var hits = 0
    repeat(shots) { index ->
        val landing = first + if (modern) index / 2 * .14f + index % 2 * .04f else index * .15f
        val at = landing - travel
        val turret = if (modern) index / 2 else index
        val forward = surge(at, first)
        val gx = facing * (if (turret == 0) 9f else -7f) + dir.x * (9f + forward)
        val gy = 2f + dir.y * (4f + forward)
        muzzleFlash(at, alongOf(gx, gy), sideOf(gx, gy), if (modern) 1.1f * power else 1.3f)
        steelGunSmoke(at + .01f, gx, gy, if (modern) .9f else 1.2f, blackPowder = !modern)
        val hit = !slippedAway && (index == 0 || chance(.5f))
        val (toX, toY) = if (hit) strikePoint() else nearMiss(28f)
        val landed = shell(at, travel, 3f + length * .03f, gx, gy, toX, toY, power)
        if (hit) shellHit(landed, toX, toY, if (modern) .85f * power else 1f) else shellMiss(landed, toX, toY, if (modern) .9f * power else 1f)
        if (index == 0) impact(landed, if (modern) 1.3f else 1.6f)
        else if (hit && hits < 2) impact(landed, .8f)
        if (hit) hits++
    }
}

/** Two full salvos from three turrets, each straddling the target: one shell strikes home, two raise towering columns. */
private fun FxScene.battleshipSalvos() {
    val fire = .05f
    val travel = .2f + distanceInTiles * .04f
    val arc = 10f + distanceInTiles * 6f
    fireAt = fire
    repeat(2) { salvo ->
        val volley = fire + salvo * .14f
        // The blast flattens the water around the ship.
        if (sourceWater) particle(FxAnchor.SOURCE, volley, .32f) {
            shape = FxShape.RING; layer = FxLayer.GROUND
            x = dir.x * 8f; y = WATERLINE + 2f + dir.y * 4f; size0 = 30f; size1 = 120f
            color0 = FxPalette.WATER; alpha = .6f; fadeOut = 1f
        }
        repeat(3) { gun ->
            val at = volley + gun * .018f
            val gx = facing * (14f - gun * 13f) + dir.x * 10f; val gy = 3f + dir.y * 6f
            turretBlast(at, gx, gy)
            val hit = gun == 0 && !slippedAway
            // The other shells raise their columns clear either side of the target rather than out of the hit.
            val straddle = if ((gun + salvo) % 2 == 0) 1f else -1f
            val (toX, toY) = if (hit) strikePoint(8f) else straddle * rand(24f, 34f) to WATERLINE + rand(-8f, 6f)
            val landed = shell(at, travel, arc, gx, gy, toX, toY, 1.4f)
            if (hit) shellHit(landed, toX, toY, 1.45f) else shellMiss(landed, toX, toY, 1.55f)
            if (gun == 0) impact(landed, if (salvo == 0) 3.5f else 2.5f)
        }
    }
}

/** Powder smoke from a steel ship's gun: white from an ironclad's black powder, thinner grey cordite later. */
private fun FxScene.steelGunSmoke(at: Float, x: Float, y: Float, scale: Float, blackPowder: Boolean) {
    repeat(2) { index ->
        val push = rand(60f, 100f) * scale
        particle(FxAnchor.SOURCE, at + index * .02f, rand(.5f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + dir.x * index * 4f + spread(3f); this.y = y + dir.y * index * 4f + spread(3f)
            vx = dir.x * push + spread(12f); vy = dir.y * push + rand(12f, 24f); drag = 3.5f
            size0 = 9f * scale; size1 = rand(24f, 32f) * scale
            color0 = vary(if (blackPowder) FxPalette.GUN_SMOKE else NavalColors.CORDITE, .04f)
            color1 = if (blackPowder) null else FxPalette.GUN_SMOKE
            alpha = if (blackPowder) .8f else .65f; fadeIn = .06f; fadeOut = .75f
        }
    }
}

/** A big gun's discharge: a fireball thrown out of the barrels, a hard flash, and a rolling cloud of cordite smoke. */
private fun FxScene.turretBlast(at: Float, x: Float, y: Float) {
    particle(FxAnchor.SOURCE, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 30f; size1 = 54f; color0 = FxPalette.FLASH; alpha = .9f; fadeOut = .9f
    }
    repeat(3) { index ->
        val push = (150f + index * 70f) * rand(.85f, 1.15f)
        particle(FxAnchor.SOURCE, at, rand(.14f, .2f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y
            vx = dir.x * push + perp.x * spread(30f); vy = dir.y * push + perp.y * spread(30f); drag = 9f
            size0 = 12f; size1 = 26f - index * 3f; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .9f; fadeOut = .7f
        }
    }
    repeat(3) { index ->
        val push = rand(60f, 140f)
        particle(FxAnchor.SOURCE, at + .03f + index * .02f, rand(.55f, .72f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(3f); this.y = y + spread(3f)
            vx = dir.x * push + perp.x * spread(20f); vy = dir.y * push + perp.y * spread(20f) + 16f; drag = 3.5f
            size0 = 14f; size1 = rand(34f, 44f)
            color0 = vary(NavalColors.CORDITE, .04f); color1 = FxPalette.GUN_SMOKE; alpha = .65f; fadeIn = .08f; fadeOut = .7f
        }
    }
}

/** A shell in flight: a hot streak inside a glow, readable over water and land alike. Returns the landing time. */
private fun FxScene.shell(at: Float, travel: Float, arc: Float, fromX: Float, fromY: Float, toX: Float, toY: Float, scale: Float): Float {
    val along = alongOf(fromX, fromY); val side = sideOf(fromX, fromY)
    projectile(at, travel, arc, along, side, toX, toY) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 10f * scale; size1 = 10f * scale; color0 = FxPalette.FIRE; alpha = .55f
    }
    return projectile(at, travel, arc, along, side, toX, toY) {
        shape = FxShape.RECT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        size0 = 2.8f * scale; size1 = 2.8f * scale; aspect = .6f; stretch = .014f; color0 = FxPalette.TRACER
    }
}

/** A shell striking home: a blast on a ship's hull, or masonry or earth thrown up ashore. */
private fun FxScene.shellHit(at: Float, x: Float, y: Float, scale: Float) =
    if (targetWater) hullBlast(at, x, y, scale) else explosion(at, x, y, scale, debris = soil())

/** A shell that misses: a column of white water, or a burst of earth (or masonry) ashore. */
private fun FxScene.shellMiss(at: Float, x: Float, y: Float, scale: Float) {
    if (targetWater) seaColumn(at, x, y, 38f * scale, 10f * scale)
    else {
        particle(FxAnchor.TARGET, at, .08f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y; size0 = 14f * scale; size1 = 26f * scale; color0 = FxPalette.FLASH; fadeOut = .9f
        }
        dust(at, x, y, .75f * scale, color = if (targetCity) FxPalette.STONE else FxPalette.EARTH, puffs = 3)
        timberShards(at, x, y, 4, 90f, 120f, 130f * scale, soil())
    }
}

/**
 * A shell bursting on a ship: a flash and fireball, sparks and torn plating, spray where the blast meets the sea,
 * then fire on deck and black smoke. The kit's blast would leave a scorch mark on the water, so this is drawn here.
 */
private fun FxScene.hullBlast(at: Float, x: Float, y: Float, scale: Float) {
    particle(FxAnchor.TARGET, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 18f * scale; size1 = 56f * scale; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(FxAnchor.TARGET, at, .18f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 12f * scale; size1 = 74f * scale; color0 = FxPalette.FLASH; alpha = .45f; fadeOut = 1f
    }
    repeat(3) {
        particle(FxAnchor.TARGET, at + rand(0f, .03f), rand(.22f, .32f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(7f * scale); this.y = y + spread(4f * scale) + 2f
            vx = spread(30f * scale); vy = rand(15f, 45f) * scale; drag = 4f
            size0 = rand(12f, 18f) * scale; size1 = rand(26f, 34f) * scale
            color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .95f; fadeOut = .65f
        }
    }
    sparks(at, x, y + 2f, count = (7 * scale).toInt(), speed = 210f * scale, heading = 90f, fan = 220f, color = FxPalette.FIRE_CORE)
    // Torn plating, dark against the water.
    repeat((5 * scale).toInt()) {
        val heading = rand(25f, 155f)
        val speed = rand(100f, 190f) * scale
        particle(FxAnchor.TARGET, at, rand(.38f, .5f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(4f); this.y = y + 2f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed + 50f; ay = -760f; drag = .6f
            size0 = rand(2.5f, 4.5f); size1 = size0; aspect = rand(.45f, .8f)
            rotation = rand(0f, 360f); spin = spread(900f); color0 = FxPalette.DEBRIS; fadeOut = .25f
        }
    }
    // Spray thrown up where the blast meets the sea alongside.
    repeat(6) {
        particle(FxAnchor.TARGET, at + rand(0f, .02f), rand(.35f, .45f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(8f * scale); this.y = WATERLINE + 1f
            vx = spread(50f * scale); vy = rand(140f, 230f) * scale; ay = -900f
            size0 = rand(2.6f, 4f); size1 = 2f; color0 = FxPalette.WATER; fadeOut = .3f
        }
    }
    repeat(3) { index ->
        particle(FxAnchor.TARGET, at + .06f + index * .04f, rand(.55f, .7f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(6f * scale); this.y = y + 4f
            vx = spread(12f) + 6f; vy = rand(30f, 48f) * scale; drag = 1.4f
            size0 = rand(11f, 15f) * scale; size1 = rand(28f, 38f) * scale
            color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE); alpha = .7f; fadeIn = .12f; fadeOut = .6f
        }
    }
    fire(at + .08f, x, y + 3f, life = .32f, scale = .8f * scale)
}

/* ---- Torpedoes ---- */

/** A submarine's torpedo: a white wake runs under the water to the target and lifts it on a huge column of water. */
fun FxScene.torpedo() {
    val heavy = style.heavy
    val launch = .06f
    fireAt = launch
    val travel = .32f + distanceInTiles * .03f
    repeat(if (heavy) 2 else 1) { index ->
        val at = launch + index * .07f
        val lane = if (heavy) (index - .5f) * 8f else 0f
        // From the bow tube below the waterline, to the hull's flank or the foot of a shore.
        val fromX = dir.x * 14f + perp.x * lane; val fromY = WATERLINE + 1f + dir.y * 3f + perp.y * lane
        val toX: Float; val toY: Float
        if (targetWater) { toX = -dir.x * 10f + perp.x * lane * .4f; toY = WATERLINE + 1f - dir.y * 3f }
        else { toX = -dir.x * 26f; toY = WATERLINE - dir.y * 20f }
        if (sourceWater) torpedoLaunch(at, fromX, fromY)
        val hitAt = torpedoRun(at, travel, fromX, fromY, toX, toY)
        torpedoBlast(hitAt, toX, toY, if (heavy) 1.12f else 1f)
        impact(hitAt, if (index > 0) 2.5f else if (heavy) 3.5f else 3f)
    }
}

/** Bubbles and a ring of disturbed water at the submarine's bow as the torpedo leaves its tube. */
private fun FxScene.torpedoLaunch(at: Float, x: Float, y: Float) {
    particle(FxAnchor.SOURCE, at, .4f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = 6f; size1 = 34f; color0 = FxPalette.WATER; alpha = .75f; fadeOut = .8f
    }
    repeat(5) {
        particle(FxAnchor.SOURCE, at + rand(0f, .08f), rand(.2f, .35f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + dir.x * rand(0f, 10f) + spread(3f); this.y = y + dir.y * rand(0f, 10f) + spread(3f)
            vy = rand(6f, 16f); size0 = rand(1.8f, 3f); size1 = size0 * 1.4f
            color0 = FxPalette.WATER; alpha = .9f; fadeIn = .2f; fadeOut = .5f
        }
    }
}

/** The torpedo's run: a dark shape under the surface pushing a bubbling white head, its foaming wake spreading into a V. */
private fun FxScene.torpedoRun(at: Float, travel: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
    val path = FxFlight(fromX, fromY, toX, toY)
    flying(at, travel, path) {
        shape = FxShape.RECT; layer = FxLayer.GROUND; size0 = 10f; size1 = 10f; aspect = .32f; alignToMotion = true
        color0 = FxPalette.SMOKE_DARK; alpha = .5f; fadeIn = .1f; fadeOut = 0f
    }
    flying(at, travel, path) {
        shape = FxShape.SOFT; layer = FxLayer.BODY; size0 = 11f; size1 = 11f
        color0 = FxPalette.WATER; alpha = .8f; fadeIn = .1f; fadeOut = 0f
    }
    flying(at, travel, path) {
        shape = FxShape.DISC; layer = FxLayer.BODY; size0 = 4.5f; size1 = 4.5f
        color0 = FxPalette.WATER; fadeIn = .1f; fadeOut = 0f
    }
    // The run's heading on screen, from tube to hull.
    val heading = MathUtils.atan2(dir.y * length + toY - fromY, dir.x * length + toX - fromX) * MathUtils.radiansToDegrees
    val count = ((length - 24f) / 6f).toInt().coerceIn(8, 26)
    repeat(count) { index ->
        val fraction = (index + .5f) / count
        val passing = at + travel * fraction
        val wake = FxFlight(fromX, fromY, toX, toY, at = fraction)
        // Churned foam right behind the torpedo, broken rather than drawn as one line.
        flying(passing, rand(.3f, .4f), wake) {
            shape = FxShape.RECT; layer = FxLayer.GROUND
            x = spread(1.2f); y = spread(1.2f); size0 = 4.5f; size1 = 7.5f; aspect = .6f; rotation = heading + spread(8f)
            color0 = FxPalette.WATER; alpha = .9f; fadeOut = .85f
        }
        // The wake's arms fan out behind it.
        if (index % 2 == 0) for (arm in listOf(-1f, 1f)) flying(passing + .01f, .36f, wake) {
            shape = FxShape.DISC; layer = FxLayer.GROUND
            vx = perp.x * arm * 30f; vy = perp.y * arm * 30f; drag = 3f
            size0 = 2.4f; size1 = 3.4f; color0 = FxPalette.WATER; alpha = .85f; fadeOut = .7f
        }
    }
    return at + travel
}

/**
 * A torpedo bursting against a hull: a muffled flash and a shock ring racing across the water, a towering white
 * column, then fire and black smoke from the stricken ship. Ashore it is a plain blast.
 */
private fun FxScene.torpedoBlast(at: Float, x: Float, y: Float, scale: Float) {
    if (!targetWater) {
        explosion(at, x, y, 1.3f * scale, debris = soil())
        return
    }
    particle(FxAnchor.TARGET, at, .14f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 3f; size0 = 22f * scale; size1 = 50f * scale; color0 = FxPalette.FIRE_CORE; alpha = .7f; fadeOut = 1f
    }
    particle(FxAnchor.TARGET, at, .3f) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = 14f * scale; size1 = 110f * scale; color0 = FxPalette.WATER; alpha = .7f; fadeOut = .9f
    }
    seaColumn(at + .02f, x, y, 74f * scale, 18f * scale)
    if (slippedAway) return
    repeat(2) {
        particle(FxAnchor.TARGET, at + .02f, rand(.18f, .24f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(5f); this.y = y + 6f; vy = rand(20f, 50f); drag = 3f
            size0 = 12f * scale; size1 = 26f * scale; color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .85f; fadeOut = .6f
        }
    }
    repeat(5) {
        val heading = rand(30f, 150f)
        val speed = rand(110f, 200f)
        particle(FxAnchor.TARGET, at + .02f, rand(.4f, .52f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(4f); this.y = y + 4f
            vx = MathUtils.cosDeg(heading) * speed; vy = MathUtils.sinDeg(heading) * speed + 60f; ay = -760f; drag = .6f
            size0 = rand(2.6f, 4.6f); size1 = size0; aspect = rand(.45f, .8f)
            rotation = rand(0f, 360f); spin = spread(900f); color0 = FxPalette.DEBRIS; fadeOut = .25f
        }
    }
    fire(at + .14f, x - dir.x * 4f, y + 8f, life = .4f, scale = scale)
    repeat(3) { index ->
        particle(FxAnchor.TARGET, at + .16f + index * .06f, rand(.45f, .6f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(6f); this.y = y + 12f
            vx = spread(10f) + 6f; vy = rand(30f, 45f); drag = 1.2f
            size0 = 12f; size1 = rand(28f, 36f); color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE)
            alpha = .7f; fadeIn = .15f; fadeOut = .6f
        }
    }
}

/* ---- Water and wreckage ---- */

/**
 * A column of white water thrown up from the waterline at (x, y): a jet climbs about [height] px, hangs and
 * collapses with its crown blowing off as mist, while white water boils at its foot and a ring of foam spreads.
 */
private fun FxScene.seaColumn(at: Float, x: Float, y: Float, height: Float, width: Float) {
    // Drag makes the water shoot up, hang and fall back: its top reaches [height] about .24 s in and the sea by .6 s.
    val hold = 5f
    val gravity = 9.6f * height
    val jets = (height / 4f).toInt().coerceIn(7, 18)
    repeat(jets) { index ->
        // Spread evenly up the column so it reads as one body of water rather than a fountain of drops.
        val reach = .3f + .75f * (index + rand(0f, 1f)) / jets
        val rise = 9.1f * height * reach
        val crown = ((reach - .7f) * 3f).coerceAtLeast(0f)
        particle(FxAnchor.TARGET, at + rand(0f, .025f), fallTime(rise, hold, gravity)) {
            layer = FxLayer.BODY
            this.x = x + spread(width * .15f); this.y = y + 1f
            vx = spread(width * (1f + 3f * crown)); vy = rise; drag = hold; ay = -gravity
            // Fast water streaks; slower blobs fill the body and round off the crown.
            if (index % 3 != 2) {
                shape = FxShape.RECT; size0 = width * rand(.3f, .42f); size1 = size0 * .8f; aspect = 1f; stretch = .025f; alignToMotion = true
            } else {
                shape = FxShape.DISC; size0 = width * rand(.45f, .6f); size1 = size0 * (.6f + .5f * crown)
            }
            color0 = FxPalette.WATER; alpha = .95f; fadeOut = .3f
        }
    }
    // Mist blows off the crown and hangs a moment.
    repeat(2) { index ->
        particle(FxAnchor.TARGET, at + .02f, .55f) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(width * .2f); this.y = y + 2f
            vx = spread(width * 2f); vy = 9.1f * height * (.8f + .15f * index); drag = hold; ay = -gravity * .55f
            size0 = width * .8f; size1 = width * 2.8f; color0 = FxPalette.WATER; alpha = .75f; fadeIn = .1f; fadeOut = .6f
        }
    }
    // White water boils up around the foot.
    repeat(if (width < 12f) 2 else 3) { index ->
        particle(FxAnchor.TARGET, at + index * .03f, .45f) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(width * .5f); this.y = y + width * .25f
            vx = spread(width * 1.5f); vy = rand(8f, 18f); drag = 3f
            size0 = width * 1.2f; size1 = width * 2.6f; color0 = FxPalette.WATER; alpha = .9f; fadeIn = .05f; fadeOut = .6f
        }
    }
    foam(at + .02f, x, y - 2f, width * .6f, width * 3.6f)
}

/** When water thrown up at [rise] against [drag] and [gravity] falls back to where it started. */
private fun fallTime(rise: Float, drag: Float, gravity: Float): Float {
    var t = .05f
    while (t < 1.2f && rise * (1f - exp(-drag * t)) / drag > .5f * gravity * t * t) t += .01f
    return t
}

/** A ring of foam spreading from (x, y) over a pale patch of churned water. */
private fun FxScene.foam(at: Float, x: Float, y: Float, from: Float, to: Float, life: Float = .6f) {
    particle(FxAnchor.TARGET, at, life) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = from; size1 = to; color0 = FxPalette.WATER; alpha = .65f; fadeOut = .8f
    }
    particle(FxAnchor.TARGET, at + .01f, life) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = from * 1.2f; size1 = to * .7f; color0 = FxPalette.WATER; alpha = .5f; fadeIn = .1f; fadeOut = .7f
    }
}

/** Shards of wood (or [color]) torn off at (x, y), thrown around [heading] degrees, spinning and falling. */
private fun FxScene.timberShards(at: Float, x: Float, y: Float, count: Int, heading: Float, fan: Float, speed: Float,
                                 color: Color? = null) {
    repeat(count) {
        val direction = heading + spread(fan / 2)
        val velocity = rand(.5f, 1.1f) * speed
        particle(FxAnchor.TARGET, at + rand(0f, .025f), rand(.38f, .55f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(3f); this.y = y + spread(2f)
            vx = MathUtils.cosDeg(direction) * velocity; vy = MathUtils.sinDeg(direction) * velocity * .5f + rand(90f, 190f)
            ay = -700f; drag = .8f
            size0 = rand(4.5f, 8f); size1 = size0 * .8f; aspect = rand(.25f, .42f)
            rotation = rand(0f, 360f); spin = spread(1100f)
            color0 = color ?: vary(if (chance(.6f)) NavalColors.RAW_WOOD else FxPalette.WOOD, .06f); fadeOut = .3f
        }
    }
}

/** A puff of shattered planking (or masonry). */
private fun FxScene.timberDust(at: Float, x: Float, y: Float, puffs: Int, color: Color = NavalColors.TIMBER_DUST) = repeat(puffs) {
    particle(FxAnchor.TARGET, at + it * .02f, rand(.4f, .55f)) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x + spread(5f); this.y = y + spread(3f)
        vx = dir.x * 24f + spread(18f); vy = rand(20f, 36f); drag = 2.5f
        size0 = rand(8f, 11f); size1 = rand(20f, 28f)
        color0 = vary(color, .05f); alpha = .65f; fadeIn = .06f; fadeOut = .7f
    }
}
