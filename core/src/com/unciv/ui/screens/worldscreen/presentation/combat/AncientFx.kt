package com.unciv.ui.screens.worldscreen.presentation.combat

import com.badlogic.gdx.graphics.Color

/*
 * Ancient and medieval land weapons: bows and slings, catapults, blades and mounted charges.
 */

/** A volley lobbed from the archers' line onto the defender, each arrow landing a beat after the last. */
fun FxScene.arrows() {
    val variant = style.variant
    val count = when (variant) { "stones", "javelins" -> 3; "repeating" -> 7; "city" -> 7; else -> 5 }
    val flat = variant == "bolts" || variant == "repeating"
    val arc = if (flat) 6f + distanceInTiles * 2f else 16f + distanceInTiles * 7f
    val travel = (if (flat) .22f else .3f) + distanceInTiles * .04f
    val release = .06f
    val stagger = if (variant == "repeating") .03f else .035f
    fireAt = release
    var firstLanding = Float.MAX_VALUE
    repeat(count) { index ->
        val launch = release + index * stagger + spread(.008f)
        val side = (index - (count - 1) / 2f) * if (variant == "city") 7f else 5f
        val toX = spread(11f); val toY = spread(7f)
        val landing = projectile(launch, travel, arc, fromAlong = 6f, fromSide = side, toX = toX, toY = toY) {
            when (variant) {
                "stones" -> { shape = FxShape.DISC; size0 = 3.5f; size1 = 3.5f; color0 = FxPalette.STONE }
                "javelins" -> { shape = FxShape.RECT; size0 = 15f; size1 = 15f; aspect = .1f; color0 = FxPalette.SHAFT }
                else -> { shape = FxShape.RECT; size0 = 10f; size1 = 10f; aspect = .14f; color0 = FxPalette.SHAFT }
            }
        }
        firstLanding = minOf(firstLanding, landing)
        if (targetWater) splash(landing, toX, toY, .3f)
        else {
            dust(landing, toX, toY - 2f, .35f, puffs = 2)
            // Arrows that miss stay stuck in the ground a moment.
            if (variant != "stones" && chance(.5f)) particle(FxAnchor.TARGET, landing, end - landing) {
                shape = FxShape.RECT; layer = FxLayer.BODY
                x = toX; y = toY - 6f; size0 = if (variant == "javelins") 10f else 7f; size1 = size0; aspect = .16f
                rotation = 90f + angle * .2f + spread(20f); color0 = FxPalette.SHAFT; fadeOut = .3f
            }
            sparks(landing, toX, toY, count = 2, speed = 90f, anchor = FxAnchor.TARGET, color = Color(1f, .95f, .8f, 1f))
        }
    }
    impact(firstLanding)
}

fun FxScene.catapult() = standInShot(arc = 40f, scale = .8f)
fun FxScene.blades() = standInClash()
fun FxScene.charge() = standInClash(dusty = true)
