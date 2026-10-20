package com.unciv.ui.screens.worldscreen.presentation.combat

/*
 * Artillery and missiles: cannon, modern artillery, rocket salvos and guided missiles.
 */

fun FxScene.cannon() = standInShot(arc = 14f, scale = .9f, shake = 1f)

/** A heavy gun: a blast at the muzzle, a shell lobbed high, a big explosion that shakes the map. */
fun FxScene.artillery() {
    val city = style.variant == "city"
    val fire = .05f
    fireAt = fire
    val gun = if (city) .9f else 1.3f
    // The blast at the muzzle throws a ring of dust and smoke around the gun.
    muzzleFlash(fire, along = 14f, scale = gun)
    gunSmoke(fire + .01f, along = 12f, scale = gun, puffs = 5)
    if (!sourceWater) dust(fire, ox(-4f), oy(-4f) - 6f, .8f * gun, FxAnchor.SOURCE, puffs = 4)
    particle(FxAnchor.SOURCE, fire, .16f) {
        shape = FxShape.RING; blend = FxBlend.ADD; layer = FxLayer.LIGHT
        x = ox(14f); y = oy(14f); size0 = 10f; size1 = 46f * gun; color0 = FxPalette.FLASH; alpha = .35f; fadeOut = 1f
    }
    val shells = if (style.heavy) 2 else 1
    repeat(shells) { index ->
        val launch = fire + .02f + index * .1f
        val travel = .3f + distanceInTiles * .045f
        val arc = 34f + distanceInTiles * 14f
        val toX = if (index == 0) spread(4f) else spread(16f)
        val toY = if (index == 0) spread(3f) else spread(10f)
        val landing = projectile(launch, travel, arc, fromAlong = 14f, toX = toX, toY = toY) {
            shape = FxShape.DISC; size0 = 4f; size1 = 4f; color0 = FxPalette.DEBRIS
        }
        // A faint glow riding with the shell keeps it readable over dark terrain.
        projectile(launch, travel, arc, fromAlong = 14f, toX = toX, toY = toY) {
            shape = FxShape.SOFT; blend = FxBlend.ADD; layer = FxLayer.LIGHT; size0 = 9f; size1 = 9f; color0 = FxPalette.FIRE; alpha = .45f
        }
        explosion(landing, toX, toY, scale = if (index == 0) 1.6f else 1.2f, water = targetWater)
        impact(landing, shake = if (city) 2f else if (index == 0) 3.5f else 2.5f)
    }
}

fun FxScene.rockets() = standInShot(arc = 24f, scale = 1f, shake = 2f)
fun FxScene.missile() = standInShot(scale = 1.5f, shake = 3f)
