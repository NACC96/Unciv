package com.unciv.ui.screens.worldscreen.presentation.combat

/*
 * Gunpowder and modern land weapons: musket lines, rifles, machine guns, tanks and the Giant Death Robot.
 */

fun FxScene.muskets() = standInShot(scale = .6f)
fun FxScene.rifles() = standInShot(scale = .6f)
fun FxScene.machineGun() = standInShot(scale = .5f)
fun FxScene.tank() = standInShot(scale = 1.1f, shake = 1.5f)
fun FxScene.laser() = standInShot(scale = 1.2f, shake = 2f)
