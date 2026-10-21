package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.presentation.VisualAttackOutcome
import kotlin.random.Random

/** The moment a blow lands: damage numbers appear, the defender flinches and heavy weapons shake the map. */
data class FxImpact(val at: Float, val shake: Float = 0f)

/**
 * Everything a weapon script may know about one attack, and the particles it builds.
 *
 * Times are nominal seconds of the attack at normal speed. The attack lasts [end] (1 s, longer when a unit dies);
 * fast mode plays the same timeline quicker. Offsets are world pixels from a tile's center at zoom 1:
 * neighbouring tile centers are about 69 px apart, a unit is about 40 px tall, and +y is up the screen.
 *
 * Only facts the viewer may see reach a script: an unseen attacker's tile gives [sourceSeen] = false, and
 * particles anchored there are never drawn. The same holds for the defender's tile and the flight between them.
 */
class FxScene(
    val style: WeaponStyle,
    /** What the attacker and defender are made of; null when the viewer can't see them. */
    val attackerBody: BodyKind?,
    val defenderBody: BodyKind?,
    direction: Vector2?,
    /** Distance between the two tile centers in pixels, or 0 when either is unseen. */
    val length: Float,
    val distanceInTiles: Int,
    val sourceSeen: Boolean,
    val targetSeen: Boolean,
    val flightSeen: Boolean,
    val sourceWater: Boolean,
    val targetWater: Boolean,
    val outcome: VisualAttackOutcome,
    val damageToDefender: Int?,
    val damageToAttacker: Int?,
    val defenderKilled: Boolean,
    val attackerKilled: Boolean,
    seed: Long,
    /** The attacker closes in to strike, so its unit lunges into the first blow; otherwise it fires from where it stands. */
    val melee: Boolean = false
) {
    val family: WeaponFamily get() = style.family
    val targetCity: Boolean get() = defenderBody == BodyKind.CITY
    /** Unit vector from the attacker toward the defender, on screen. Points right when either side is unseen. */
    val dir: Vector2 = Vector2(direction ?: Vector2(1f, 0f)).let { if (it.isZero(1e-4f)) it.set(1f, 0f) else it.nor() }
    /** [dir] turned a quarter to the left. */
    val perp: Vector2 = Vector2(-dir.y, dir.x)
    val angle: Float get() = dir.angleDeg()
    /** How long the attack plays: a death adds time for the unit to go down after the blow. */
    val end: Float = if (defenderKilled || attackerKilled) 1.3f else 1f
    val random = Random(seed)
    val particles = ArrayList<FxParticle>()
    val impacts = ArrayList<FxImpact>()
    /** When the attacker fires, for its recoil. Melee attackers lunge into the first impact instead. */
    var fireAt: Float? = null

    fun rand(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)
    /** A value in -[amount]..[amount]. */
    fun spread(amount: Float): Float = (random.nextFloat() * 2f - 1f) * amount
    fun chance(probability: Float): Boolean = random.nextFloat() < probability

    /** x of a point [along] pixels toward the defender and [side] pixels to its left. */
    fun ox(along: Float, side: Float = 0f): Float = dir.x * along + perp.x * side
    fun oy(along: Float, side: Float = 0f): Float = dir.y * along + perp.y * side

    fun impact(at: Float, shake: Float = 0f) { impacts += FxImpact(at, shake) }

    /**
     * How far a melee attacker has moved toward the defender at [time] when its first blow lands at [blow]: it closes
     * over 0.22 s, strikes, and falls back over 0.25 s. The timeline moves the attacker's unit by exactly this, so
     * guns fired and dust thrown on the way can leave from where the unit is. Always 0 for ranged attacks.
     */
    fun lunge(time: Float, blow: Float): Float {
        if (!melee) return 0f
        val since = time - blow
        val reach = (length * .3f).coerceAtMost(20f)
        return reach * when {
            since < -.22f || since >= .25f -> 0f
            since < 0f -> FxEase.SMOOTH.apply(1f + since / .22f)
            else -> 1f - FxEase.SMOOTH.apply(since / .25f)
        }
    }

    /** A particle at [anchor]'s tile. Its life is cut to end with the attack. */
    fun particle(anchor: FxAnchor, start: Float, life: Float, init: FxParticle.() -> Unit): FxParticle =
        FxParticle().also {
            it.anchor = anchor
            it.start = start
            it.life = life.coerceAtMost(end - start)
            it.init()
            if (it.life > 0f) particles += it
        }

    /** A particle on the path from the attacker to the defender. */
    fun flying(start: Float, life: Float, flight: FxFlight, init: FxParticle.() -> Unit): FxParticle =
        particle(FxAnchor.TARGET, start, life) { this.flight = flight; init() }

    /** A copy of [color] with each channel nudged by up to [amount], so repeated puffs don't look stamped. */
    fun vary(color: Color, amount: Float = .06f): Color {
        val shift = spread(amount)
        return Color((color.r + shift).coerceIn(0f, 1f), (color.g + shift).coerceIn(0f, 1f), (color.b + shift).coerceIn(0f, 1f), color.a)
    }
}

/** Shared colors, so every family's fire, smoke and dust read as the same world. */
object FxPalette {
    val FLASH = Color(1f, .97f, .86f, 1f)
    val FIRE_CORE = Color(1f, .92f, .6f, 1f)
    val FIRE = Color(1f, .6f, .18f, 1f)
    val EMBER = Color(.86f, .26f, .06f, 1f)
    val SMOKE = Color(.46f, .44f, .41f, 1f)
    val SMOKE_DARK = Color(.16f, .15f, .14f, 1f)
    val GUN_SMOKE = Color(.9f, .89f, .86f, 1f)
    val DUST = Color(.78f, .68f, .5f, 1f)
    val EARTH = Color(.36f, .29f, .2f, 1f)
    val DEBRIS = Color(.2f, .17f, .14f, 1f)
    val WOOD = Color(.5f, .34f, .18f, 1f)
    val STONE = Color(.58f, .55f, .5f, 1f)
    val WATER = Color(.86f, .94f, 1f, 1f)
    val SCORCH = Color(.07f, .06f, .05f, 1f)
    val TRACER = Color(1f, .84f, .42f, 1f)
    val STEEL = Color(1f, .99f, .92f, 1f)
    val SHAFT = Color(.3f, .22f, .13f, 1f)
    val LASER = Color(1f, .22f, .18f, 1f)
    val EXHAUST = Color(1f, .78f, .4f, 1f)
}
