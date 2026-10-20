package com.unciv.ui.screens.worldscreen.presentation.combat

/*
 * Ships: rams and boarding, broadsides of sail-era guns, steel-era naval guns and submarines' torpedoes.
 */

fun FxScene.ramShip() = standInClash()
fun FxScene.broadside() = standInShot(arc = 8f, scale = .9f)
fun FxScene.navalGuns() = standInShot(arc = 20f, scale = if (style.heavy) 1.6f else 1.2f, shake = if (style.heavy) 3f else 1.5f)
fun FxScene.torpedo() = standInShot(scale = 1.2f)
