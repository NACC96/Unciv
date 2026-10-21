package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.unciv.logic.presentation.VisualAttackOutcome

/*
 * Ships: rams and boarding, broadsides of sail-era guns, steel-era naval guns and submarines' torpedoes.
 *
 * Ship art is drawn level whatever the attack's direction: the deck runs through the tile's center and the hull
 * spans about 20 px either side of it, with the waterline [WATERLINE] below. Guns sit along that hull and fire
 * toward the defender, and shots strike its flank facing the attacker.
 */

private const val WATERLINE = -7f

/** How long a [seaColumn] stands: thrown up against drag 5 and pulled down at 9.6/9.1 of its speed, its water is back by then. */
private const val COLUMN_LIFE = .6f

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
    // The ram strikes a ship at the waterline on the flank facing the attacker, a city at its seaward wall.
    val cx = -dir.x * if (targetWater) 12f else 18f
    val cy = if (targetWater) WATERLINE + 3f - dir.y * 3f else -4f - dir.y * 12f
    impact(blow, .8f + style.era * .2f)
    if (targetWater) ramSpray(blow, cx, cy, if (slippedAway) .75f * weight else weight)
    else dust(blow, cx, cy, 1.2f * weight, color = FxPalette.DUST, puffs = 6)
    if (slippedAway) return
    particle(FxAnchor.TARGET, blow, .08f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = cx; y = cy + 5f; size0 = 14f * weight; size1 = 30f * weight; color0 = FxPalette.FLASH; alpha = .55f; fadeOut = 1f
    }
    // The ram stoves in the planking, or knocks masonry out of the wall.
    if (targetWater) {
        timberShards(blow, cx, cy + 6f, (13 * weight).toInt(), angle, 140f, 210f)
        timberDust(blow, cx, cy + 6f, 3)
    } else rubble(blow, cx, cy + 4f, (13 * weight).toInt(), angle, 190f, soil())
    // A bloodied attacker's bow gives way too.
    if ((damageToAttacker ?: 0) > 0) timberShards(blow + .02f, cx, cy + 5f, 5, angle + 180f, 100f, 150f)
    boardingFight(blow + .15f)
}

/** White water piling up under a galley's bow and peeling off both sides as it surges into [blow]. */
private fun FxScene.bowWave(blow: Float, scale: Float) {
    if (!sourceWater) return
    var at = blow - .21f
    while (at < blow + .01f) {
        val forward = lunge(at, blow)
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
        val forward = lunge(at2, blow)
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
    repeat(3) {
        particle(FxAnchor.TARGET, at + rand(0f, .04f), rand(.38f, .46f)) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(7f); this.y = y + rand(2f, 8f)
            vx = spread(40f); vy = rand(60f, 130f) * scale; drag = 4.5f; ay = -120f
            size0 = rand(11f, 14f) * scale; size1 = rand(24f, 30f) * scale
            color0 = FxPalette.WATER; alpha = .85f; fadeIn = .04f; fadeOut = .6f
        }
    }
    foam(at + .02f, x, y - 2f, 10f * scale, 38f * scale)
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
    val ripple = if (heavy) .022f else .032f
    val travel = .15f + distanceInTiles * .045f
    val arc = 4f + distanceInTiles * 3f
    // Only a melee ship takes return fire. It fires as it closes, so its first ball lands as its lunge peaks.
    val closing = melee
    val fire = if (closing) .32f - travel else .05f
    fireAt = fire
    var hits = 0
    for (gun in 0 until perDeck) for (deck in 0 until decks) {
        val index = gun * decks + deck
        val at = fire + index * ripple + spread(.004f)
        val forward = if (closing) lunge(at, fire + travel) else 0f
        // Gun ports along the hull from the end nearest the defender, the lower deck a little below the upper.
        val gx = facing * (13f - 27f * gun / (perDeck - 1)) + deck * 2f + dir.x * (5f + forward)
        val gy = -2f - deck * 3f + dir.y * (4f + forward)
        // Smoke from every port would only thicken the same bank, so bigger batteries put out less per gun.
        powderBlast(at, gx, gy, if (heavy) 1.05f else .95f, puffs = if (heavy || perDeck > 5) 2 else 3)
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
        val forward = lunge(at, blow)
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
        val push = rand(100f, 145f) * scale * (1f - index * .3f)
        particle(FxAnchor.SOURCE, at + .01f + index * .02f, rand(.5f, .62f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + dir.x * 5f + spread(3f); this.y = y + dir.y * 5f + spread(3f)
            vx = dir.x * push + spread(12f); vy = dir.y * push + rand(10f, 22f); drag = 4f
            size0 = 12f * scale; size1 = rand(36f, 44f) * scale
            color0 = vary(FxPalette.GUN_SMOKE, .03f); alpha = .8f; fadeIn = .04f; fadeOut = .6f
        }
    }
}

/** A round shot in flight: a dark ball inside a faint smear of its motion. Returns the landing time. */
private fun FxScene.cannonball(at: Float, travel: Float, arc: Float, fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
    val along = alongOf(fromX, fromY); val side = sideOf(fromX, fromY)
    projectile(at, travel, arc, along, side, toX, toY) {
        shape = FxShape.RECT; size0 = 2.4f; size1 = 2.4f; aspect = .9f; stretch = .012f; color0 = NavalColors.SHOT; alpha = .35f
    }
    return projectile(at, travel, arc, along, side, toX, toY) {
        shape = FxShape.DISC; size0 = 4f; size1 = 4f; color0 = NavalColors.SHOT
    }
}

/** Round shot striking home: a burst of light, splinters blown out of the hull and timber dust; masonry or earth ashore. */
private fun FxScene.roundShotStrike(at: Float, x: Float, y: Float, scale: Float) {
    particle(FxAnchor.TARGET, at, .09f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y + 2f; size0 = 16f * scale; size1 = 28f * scale; color0 = FxPalette.FIRE_CORE; alpha = .9f; fadeOut = 1f
    }
    if (targetWater) {
        timberShards(at, x, y + 2f, (8 * scale).toInt(), angle, 120f, 170f)
        timberDust(at, x, y + 2f, 2)
    } else {
        rubble(at, x, y + 2f, (7 * scale).toInt(), 90f, 150f * scale, soil())
        dust(at, x, y, scale, color = if (targetCity) FxPalette.STONE else FxPalette.DUST, puffs = 4)
    }
}

/** Round shot that misses: a white plume where it plunges into the sea, or a spurt of dirt ashore. */
private fun FxScene.shotMiss(at: Float, x: Float, y: Float, scale: Float) {
    if (targetWater) seaColumn(at, x, y, 36f * scale, 10f * scale)
    else {
        dust(at, x, y, .75f * scale, color = if (targetCity) FxPalette.STONE else FxPalette.DUST, puffs = 3)
        rubble(at, x, y, 3, 90f, 110f * scale, soil())
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
    val travel = if (melee) .12f else .1f + distanceInTiles * .045f
    val first = if (melee) .34f else .06f + travel
    fireAt = first - travel
    var hits = 0
    repeat(shots) { index ->
        val landing = first + if (modern) index / 2 * .14f + index % 2 * .04f else index * .15f
        val at = landing - travel
        val turret = if (modern) index / 2 else index
        val forward = lunge(at, first)
        val gx = facing * (if (turret == 0) 9f else -7f) + dir.x * (9f + forward)
        val gy = 2f + dir.y * (4f + forward)
        muzzleFlash(at, alongOf(gx, gy), sideOf(gx, gy), if (modern) 1.25f * power else 1.4f)
        steelGunSmoke(at + .01f, gx, gy, if (modern) 1f else 1.2f, blackPowder = !modern)
        val hit = !slippedAway && (index == 0 || chance(.5f))
        val (toX, toY) = if (hit) strikePoint() else nearMiss(28f)
        val landed = shell(at, travel, 3f + length * .03f, gx, gy, toX, toY, power)
        if (hit) shellHit(landed, toX, toY, if (modern) .85f * power else 1f, full = hits == 0)
        else shellMiss(landed, toX, toY, if (modern) .9f * power else 1f)
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

/** A shell striking home: a blast on a ship's hull, or masonry or earth thrown up ashore. Only a [full] hit sets fires. */
private fun FxScene.shellHit(at: Float, x: Float, y: Float, scale: Float, full: Boolean = true) =
    if (targetWater) hullBlast(at, x, y, scale, full) else explosion(at, x, y, if (full) scale else .75f * scale, smoke = full, debris = soil())

/** A shell that misses: a column of white water, or a burst of earth (or masonry) ashore. */
private fun FxScene.shellMiss(at: Float, x: Float, y: Float, scale: Float) {
    if (targetWater) seaColumn(at, x, y, 38f * scale, 10f * scale)
    else {
        particle(FxAnchor.TARGET, at, .08f) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x; this.y = y; size0 = 14f * scale; size1 = 26f * scale; color0 = FxPalette.FLASH; fadeOut = .9f
        }
        dust(at, x, y, .75f * scale, color = if (targetCity) FxPalette.STONE else FxPalette.EARTH, puffs = 3)
        rubble(at, x, y, 4, 90f, 130f * scale, soil())
    }
}

/**
 * A shell bursting on a ship: a flash and fireball, sparks and torn plating, spray where the blast meets the sea,
 * then black smoke and, for a [full] hit, fire on deck; later hits of the same attack add less to the burning hull.
 * The kit's blast would leave a scorch mark on the water, so this is drawn here.
 */
private fun FxScene.hullBlast(at: Float, x: Float, y: Float, scale: Float, full: Boolean = true) {
    particle(FxAnchor.TARGET, at, .1f) {
        shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 18f * scale; size1 = 56f * scale; color0 = FxPalette.FLASH; fadeOut = .9f
    }
    particle(FxAnchor.TARGET, at, .18f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        this.x = x; this.y = y; size0 = 12f * scale; size1 = 74f * scale; color0 = FxPalette.FLASH; alpha = .45f; fadeOut = 1f
    }
    repeat(if (full) 3 else 2) {
        particle(FxAnchor.TARGET, at + rand(0f, .03f), rand(.22f, .32f)) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT
            this.x = x + spread(7f * scale); this.y = y + spread(4f * scale) + 2f
            vx = spread(30f * scale); vy = rand(15f, 45f) * scale; drag = 4f
            size0 = rand(12f, 18f) * scale; size1 = rand(26f, 34f) * scale
            color0 = FxPalette.FIRE_CORE; color1 = FxPalette.EMBER; alpha = .95f; fadeOut = .65f
        }
    }
    sparks(at, x, y + 2f, count = ((if (full) 7 else 4) * scale).toInt(), speed = 210f * scale, heading = 90f, fan = 220f, color = FxPalette.FIRE_CORE)
    // Torn plating, dark against the water.
    repeat(((if (full) 5 else 3) * scale).toInt()) {
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
    repeat(if (full) 6 else 3) {
        particle(FxAnchor.TARGET, at + rand(0f, .02f), rand(.35f, .45f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(8f * scale); this.y = WATERLINE + 1f
            vx = spread(50f * scale); vy = rand(140f, 230f) * scale; ay = -900f
            size0 = rand(2.6f, 4f); size1 = 2f; color0 = FxPalette.WATER; fadeOut = .3f
        }
    }
    repeat(if (full) 3 else 1) { index ->
        particle(FxAnchor.TARGET, at + .06f + index * .04f, rand(.5f, .62f)) {
            shape = FxShape.SOFT; layer = FxLayer.SMOKE
            this.x = x + spread(6f * scale); this.y = y + 4f
            vx = spread(12f) + 6f; vy = rand(30f, 48f) * scale; drag = 1.4f
            size0 = rand(11f, 15f) * scale; size1 = rand(28f, 38f) * scale
            color0 = vary(FxPalette.SMOKE_DARK); color1 = vary(FxPalette.SMOKE); alpha = .7f; fadeIn = .12f; fadeOut = .6f
        }
    }
    if (full) fire(at + .08f, x, y + 3f, life = .32f, scale = .8f * scale)
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
        torpedoBlast(hitAt, toX, toY, if (heavy) 1.12f else 1f, full = index == 0)
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
    val count = (length / 6f).toInt().coerceIn(10, 30)
    repeat(count) { index ->
        val fraction = (index + .5f) / count
        val passing = at + travel * fraction
        val wake = FxFlight(fromX, fromY, toX, toY, at = fraction)
        // Overlapping soft puffs make an unbroken line of churned water that widens and fades behind the torpedo.
        flying(passing, rand(.32f, .4f), wake) {
            shape = FxShape.SOFT; layer = FxLayer.GROUND
            x = spread(1f); y = spread(1f); size0 = 8f; size1 = 16f
            color0 = FxPalette.WATER; alpha = .95f; fadeOut = .8f
        }
        // Bubbles break the line up, and its arms fan out into a V.
        if (index % 3 == 1) flying(passing, .26f, wake) {
            shape = FxShape.DISC; layer = FxLayer.GROUND
            x = spread(2.5f); y = spread(2f); size0 = 2.6f; size1 = 1.6f; color0 = FxPalette.WATER; fadeOut = .6f
        }
        if (index % 3 == 0) for (arm in listOf(-1f, 1f)) flying(passing + .01f, .34f, wake) {
            shape = FxShape.DISC; layer = FxLayer.GROUND
            vx = perp.x * arm * 30f; vy = perp.y * arm * 30f; drag = 3f
            size0 = 2.4f; size1 = 3.2f; color0 = FxPalette.WATER; alpha = .8f; fadeOut = .7f
        }
    }
    return at + travel
}

/**
 * A torpedo bursting against a hull: a muffled flash and a shock ring racing across the water, a towering white
 * column, then fire and black smoke from the stricken ship; a second torpedo adds its column to the fire the first
 * set. Ashore it is a plain blast.
 */
private fun FxScene.torpedoBlast(at: Float, x: Float, y: Float, scale: Float, full: Boolean) {
    if (!targetWater) {
        explosion(at, x, y, 1.3f * scale, smoke = full, debris = soil())
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
    repeat(if (full) 5 else 3) {
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
    if (!full) return
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
 * A column of white water thrown up from the waterline at (x, y): it climbs about [height] px in a quarter second,
 * hangs, and sinks back into the sea by .6 s as spray rains off its crown and white water boils at its foot.
 */
private fun FxScene.seaColumn(at: Float, x: Float, y: Float, height: Float, width: Float) {
    // Soft blobs stacked up the column overlap into one body of water with soft sides. Rise and gravity both scale
    // with each blob's share of the height, so the whole column peaks together and settles back as one.
    val blobs = (height / 6f).toInt().coerceIn(6, 13)
    repeat(blobs) { index ->
        val reach = .15f + .87f * (index + rand(.25f, .75f)) / blobs
        val crown = ((reach - .75f) * 4f).coerceAtLeast(0f)
        particle(FxAnchor.TARGET, at + rand(0f, .015f), COLUMN_LIFE) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(width * .08f); this.y = y + 1f
            vx = spread(width * (.3f + 2.4f * crown)); vy = 9.1f * height * reach; drag = 5f; ay = -9.6f * height * reach
            size0 = width; size1 = width * (1.05f + crown)
            color0 = FxPalette.WATER; alpha = .9f; fadeOut = .35f
        }
    }
    // Spray thrown off the crown arcs out and rains back.
    repeat(if (width < 12f) 3 else 5) {
        particle(FxAnchor.TARGET, at + rand(.14f, .2f), rand(.3f, .4f)) {
            shape = FxShape.DISC; layer = FxLayer.BODY
            this.x = x + spread(width * .3f); this.y = y + height * rand(.8f, .95f)
            vx = spread(width * 6f); vy = rand(10f, 40f); ay = -420f
            size0 = rand(2.2f, 3.2f) * (width / 12f).coerceIn(.8f, 1.3f); size1 = 1.6f
            color0 = FxPalette.WATER; fadeOut = .4f
        }
    }
    if (height > 45f) particle(FxAnchor.TARGET, at + .14f, .4f) {
        shape = FxShape.SOFT; layer = FxLayer.BODY
        this.x = x; this.y = y + height * .9f; vx = spread(10f); vy = 8f
        size0 = width * 1.2f; size1 = width * 2.8f; color0 = FxPalette.WATER; alpha = .45f; fadeIn = .2f; fadeOut = .7f
    }
    // White water boils up around the foot.
    repeat(2) { index ->
        particle(FxAnchor.TARGET, at + index * .04f, .42f) {
            shape = FxShape.SOFT; layer = FxLayer.BODY
            this.x = x + spread(width * .4f); this.y = y + width * .2f
            vx = spread(width * 1.5f); vy = rand(6f, 14f); drag = 3f
            size0 = width * 1.2f; size1 = width * 2.4f; color0 = FxPalette.WATER; alpha = .8f; fadeIn = .05f; fadeOut = .6f
        }
    }
    foam(at + .02f, x, y - 2f, width * .6f, width * 2.6f)
}

/** A ring of foam spreading from (x, y) over a pale patch of churned water. */
private fun FxScene.foam(at: Float, x: Float, y: Float, from: Float, to: Float, life: Float = .55f) {
    particle(FxAnchor.TARGET, at, life) {
        shape = FxShape.RING; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = from; size1 = to; color0 = FxPalette.WATER; alpha = .4f; fadeOut = .8f
    }
    particle(FxAnchor.TARGET, at + .01f, life) {
        shape = FxShape.SOFT; layer = FxLayer.GROUND
        this.x = x; this.y = y; size0 = from * 1.2f; size1 = to * .7f; color0 = FxPalette.WATER; alpha = .4f; fadeIn = .1f; fadeOut = .7f
    }
}

/** Shards of wood torn off at (x, y), thrown around [heading] degrees, spinning and falling. */
private fun FxScene.timberShards(at: Float, x: Float, y: Float, count: Int, heading: Float, fan: Float, speed: Float) {
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
            color0 = vary(if (chance(.6f)) NavalColors.RAW_WOOD else FxPalette.WOOD, .06f); fadeOut = .3f
        }
    }
}

/**
 * Chunks of [color] (masonry or earth) knocked loose at (x, y) and thrown up around [heading] degrees, with darker
 * lumps among them so the burst reads on pale walls and green fields alike.
 */
private fun FxScene.rubble(at: Float, x: Float, y: Float, count: Int, heading: Float, speed: Float, color: Color) {
    repeat(count) {
        val direction = heading + spread(70f)
        val velocity = rand(.45f, 1f) * speed
        particle(FxAnchor.TARGET, at + rand(0f, .02f), rand(.36f, .5f)) {
            shape = FxShape.RECT; layer = FxLayer.BODY
            this.x = x + spread(4f); this.y = y + spread(2f)
            vx = MathUtils.cosDeg(direction) * velocity * .7f; vy = MathUtils.sinDeg(direction) * velocity * .4f + rand(110f, 210f)
            ay = -760f; drag = .6f
            size0 = rand(3f, 5.5f); size1 = size0; aspect = rand(.65f, 1f); rotation = rand(0f, 360f); spin = spread(700f)
            color0 = if (it % 3 == 2) FxPalette.DEBRIS else vary(color, .05f); fadeOut = .25f
        }
    }
}

/** A puff of shattered planking. */
private fun FxScene.timberDust(at: Float, x: Float, y: Float, puffs: Int) = repeat(puffs) {
    particle(FxAnchor.TARGET, at + it * .02f, rand(.4f, .55f)) {
        shape = FxShape.SOFT; layer = FxLayer.SMOKE
        this.x = x + spread(5f); this.y = y + spread(3f)
        vx = dir.x * 24f + spread(18f); vy = rand(20f, 36f); drag = 2.5f
        size0 = rand(8f, 11f); size1 = rand(20f, 28f)
        color0 = vary(NavalColors.TIMBER_DUST, .05f); alpha = .65f; fadeIn = .06f; fadeOut = .7f
    }
}
