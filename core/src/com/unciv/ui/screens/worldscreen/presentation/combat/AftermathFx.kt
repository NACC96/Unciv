package com.unciv.ui.screens.worldscreen.presentation.combat

/*
 * What follows a blow: a city taking fire, and each kind of unit going down.
 */

/** A city hit by the attack: fires start among its buildings. */
fun FxScene.cityHit(at: Float) = fire(at + .05f, life = (end - at - .1f).coerceAtLeast(.2f), scale = .9f)

/** A unit destroyed at [anchor]'s tile, starting at [at]. */
fun FxScene.death(body: BodyKind?, anchor: FxAnchor, at: Float) {
    when (body) {
        BodyKind.VEHICLE, BodyKind.AIRCRAFT -> explosion(at, scale = 1.1f, anchor = anchor)
        BodyKind.SHIP -> explosion(at, scale = 1.1f, anchor = anchor, water = true)
        else -> dust(at, scale = 1f, anchor = anchor, puffs = 6)
    }
}
