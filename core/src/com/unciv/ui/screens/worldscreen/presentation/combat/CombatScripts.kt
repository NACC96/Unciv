package com.unciv.ui.screens.worldscreen.presentation.combat

import com.unciv.logic.presentation.VisualAttackOutcome

/** Builds one attack's particles: the weapon's own script, then hits on cities and any deaths. */
object CombatScripts {
    private val aircraft = setOf(WeaponFamily.STRAFE, WeaponFamily.GUNSHIP, WeaponFamily.BOMBS, WeaponFamily.MISSILE)

    fun build(scene: FxScene) {
        if (scene.outcome == VisualAttackOutcome.INTERCEPTED && scene.family in aircraft) scene.interception()
        else when (scene.family) {
            WeaponFamily.ARROWS -> scene.arrows()
            WeaponFamily.CATAPULT -> scene.catapult()
            WeaponFamily.BLADES -> scene.blades()
            WeaponFamily.CHARGE -> scene.charge()
            WeaponFamily.MUSKETS -> scene.muskets()
            WeaponFamily.RIFLES -> scene.rifles()
            WeaponFamily.MACHINE_GUN -> scene.machineGun()
            WeaponFamily.TANK -> scene.tank()
            WeaponFamily.LASER -> scene.laser()
            WeaponFamily.CANNON -> scene.cannon()
            WeaponFamily.ARTILLERY -> scene.artillery()
            WeaponFamily.ROCKETS -> scene.rockets()
            WeaponFamily.MISSILE -> scene.missile()
            WeaponFamily.RAM_SHIP -> scene.ramShip()
            WeaponFamily.BROADSIDE -> scene.broadside()
            WeaponFamily.NAVAL_GUNS -> scene.navalGuns()
            WeaponFamily.TORPEDO -> scene.torpedo()
            WeaponFamily.STRAFE -> scene.strafe()
            WeaponFamily.GUNSHIP -> scene.gunship()
            WeaponFamily.BOMBS -> scene.bombs()
        }
        if (scene.impacts.isEmpty()) scene.impact(.45f)
        val lastImpact = scene.impacts.maxOf { it.at }
        if (scene.targetCity && scene.outcome != VisualAttackOutcome.INTERCEPTED) scene.cityHit(scene.impacts.minOf { it.at })
        if (scene.defenderKilled) scene.death(scene.defenderBody, FxAnchor.TARGET, lastImpact + .08f)
        // A missile spends itself in its own blast; only a weapon brought down by the defense dies on screen.
        val consumed = scene.family == WeaponFamily.MISSILE && scene.outcome != VisualAttackOutcome.INTERCEPTED
        if (scene.attackerKilled && !consumed) scene.death(scene.attackerBody, FxAnchor.SOURCE, lastImpact + .08f)
    }
}

/** A plain shot for families still waiting for their own look: flash, a round in flight, a blast. */
internal fun FxScene.standInShot(arc: Float = 0f, scale: Float = 1f, shake: Float = 0f) {
    fireAt = .05f
    muzzleFlash(.05f, scale = scale)
    gunSmoke(.06f, scale = scale)
    val landing = projectile(.06f, .22f + distanceInTiles * .05f, arc) {
        shape = FxShape.DISC; size0 = 3.5f; size1 = 3.5f; color0 = FxPalette.DEBRIS
    }
    explosion(landing, scale = scale, water = targetWater)
    impact(landing, shake)
}

/** A plain melee blow for families still waiting for their own look: the attacker closes, steel meets steel. */
internal fun FxScene.standInClash(dusty: Boolean = false) {
    val blow = .32f
    val front = -length.coerceAtMost(40f) * .35f
    slash(blow - .03f, ox(front), oy(front) + 6f)
    sparks(blow, ox(front), oy(front) + 4f, count = 7, heading = angle + 180f, fan = 140f)
    if (dusty) dust(blow - .12f, ox(front - 10f), oy(front - 10f), 1.2f, puffs = 7)
    else dust(blow, ox(front), oy(front), .6f, puffs = 3)
    impact(blow)
}
