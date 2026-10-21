package com.unciv.ui.screens.worldscreen.presentation.combat

import com.unciv.logic.presentation.VisualAttackOutcome

/** The families that fly to their target, so the defense may intercept them. */
internal val AIRCRAFT = setOf(WeaponFamily.STRAFE, WeaponFamily.GUNSHIP, WeaponFamily.BOMBS, WeaponFamily.MISSILE)

/** Builds one attack's particles: the weapon's own script, then hits on cities and any deaths. */
object CombatScripts {
    fun build(scene: FxScene) {
        if (scene.style == CombatStyles.unseen) scene.unseenHit()
        else if (scene.outcome == VisualAttackOutcome.INTERCEPTED && scene.family in AIRCRAFT) scene.interception()
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
        val consumed = scene.family == WeaponFamily.MISSILE && scene.attackerBody == BodyKind.AIRCRAFT &&
            scene.outcome != VisualAttackOutcome.INTERCEPTED
        if (scene.attackerKilled && !consumed) scene.death(scene.attackerBody, FxAnchor.SOURCE, lastImpact + .08f)
    }
}
