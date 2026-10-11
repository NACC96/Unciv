package com.unciv.ui.screens.worldscreen.status

import com.badlogic.gdx.graphics.Color
import com.unciv.logic.GameInfo

/** Stable, readable identity accents. Names and portraits also identify each agent. */
internal object AgentActivityAppearance {
    private val colors = listOf("8edfd0", "a7c8ff", "f3cc88", "f0b0d7", "c6b7ff", "b5d899", "ffb5a5", "9edbe8")
        .map(Color::valueOf)

    fun color(game: GameInfo, civId: String): Color {
        val index = game.civilizations.filter { it.isMajorCiv() }.map { it.civID }.sorted().indexOf(civId)
        return if (index in colors.indices) Color(colors[index])
        else Color(Color.WHITE).fromHsv((if (index >= 0) index else (civId.hashCode().toLong() and 0x7fffffff).toInt()) * 137.508f % 360f, 0.3f, 0.95f)
    }
}
