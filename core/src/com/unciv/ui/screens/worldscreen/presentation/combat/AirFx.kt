package com.unciv.ui.screens.worldscreen.presentation.combat

/*
 * Aircraft: fighters strafing, helicopter gunships, bombers and an air attack brought down by the defense.
 */

fun FxScene.strafe() = standInShot(scale = .6f)
fun FxScene.gunship() = standInShot(scale = .8f)
fun FxScene.bombs() = standInShot(arc = 30f, scale = 1.5f, shake = 3f)

/** An air attack met by anti-aircraft fire or fighters: it never reaches its target. */
fun FxScene.interception() {
    fireAt = .05f
    if (sourceSeen) explosion(.4f, ox(length * .5f), oy(length * .5f) + 20f, .7f, FxAnchor.SOURCE, smoke = true)
    impact(.4f)
}
