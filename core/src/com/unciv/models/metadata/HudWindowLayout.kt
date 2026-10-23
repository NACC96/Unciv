package com.unciv.models.metadata

/** Saved arrangement of one floating world-screen window (Activity, Messages, …).
 *
 *  Position is kept relative to the nearer horizontal and vertical stage edges, so a window
 *  docked at the right or top keeps that edge when the game window is resized. All values are
 *  stage units. A layout with [placed] false only carries [minimized] and [opacity]; the window
 *  then uses its default spot. */
class HudWindowLayout {
    /** The user moved or resized the window; [x]..[height] hold its bounds. */
    var placed = false
    /** Distance from the left edge, or from the right edge when [fromRight]. */
    var x = 0f
    /** Distance from the bottom edge, or from the top edge when [fromTop]. */
    var y = 0f
    var width = 0f
    var height = 0f
    var fromRight = false
    var fromTop = false
    var minimized = false
    /** 0 lets the most of the map through, 1 is fully opaque. */
    var opacity = DEFAULT_OPACITY

    companion object {
        const val DEFAULT_OPACITY = 0.55f
        const val MIN_OPACITY = 0.15f
    }
}
